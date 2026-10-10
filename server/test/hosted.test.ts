import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import pg from "pg";
import { canonicalJson } from "@tapscene/runtime-ts";
import { config } from "../src/config.js";
import { migrate } from "../src/migrate.js";
import { buildApp } from "../src/app.js";
import { sha, encryptToken, token } from "../src/security.js";
import { PublicationWorker } from "../src/worker.js";
import { syntheticFixture } from "../scripts/synthetic.js";

test("local adapter refuses public/auth production configuration", () => {
  for (const env of [
    { NODE_ENV: "production" },
    { HOST: "0.0.0.0" },
    { TAPSCENE_MODE: "production" },
    { DATABASE_URL: "postgres://x@db.example.test/x" },
    { DATABASE_URL: "postgres://x@127.0.0.1/x?host=db.example.test" },
  ])
    assert.throws(() => config(env));
});

test("PostgreSQL local hosting boundary and recovery", async (t) => {
  if (!process.env.TEST_DATABASE_URL) {
    t.skip("NOT_RUN: TEST_DATABASE_URL with real PostgreSQL is required");
    return;
  }
  const url = process.env.TEST_DATABASE_URL;
  config({ DATABASE_URL: url });
  const admin = new pg.Pool({ connectionString: url }),
    schema = `test_${randomUUID().replaceAll("-", "")}`;
  await admin.query(`CREATE SCHEMA ${schema}`);
  const db = new pg.Pool({
    connectionString: url,
    options: `-c search_path=${schema}`,
    max: 8,
  });
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "tapscene-hosted-")),
    cfg = {
      ...config({ DATABASE_URL: url }),
      dataRoot: root,
      webRoot: path.resolve("../web-player/dist"),
    };
  const { app, files, worker } = await buildApp(cfg, db);
  await migrate(db);
  await migrate(db);
  const request = async (
    method: string,
    url: string,
    body?: unknown,
    session?: string,
    extra: Record<string, string> = {},
  ) =>
    app.inject({
      method: method as any,
      url,
      headers: {
        host: "127.0.0.1:4173",
        ...(session ? { authorization: `Bearer ${session}` } : {}),
        ...extra,
      },
      ...(body !== undefined ? { payload: body as any } : {}),
    });
  const login = async (email: string) => {
    const q = await request("POST", "/api/v1/auth/challenges", { email });
    assert.equal(q.statusCode, 202, q.body);
    const id = q.json().challengeId,
      mail = JSON.parse(
        await fs.readFile(path.join(root, "inbox", `${id}.json`), "utf8"),
      );
    const r = await request("POST", "/api/v1/auth/sessions", {
      challengeId: id,
      code: mail.code,
    });
    assert.equal(r.statusCode, 201, r.body);
    return { session: r.json().sessionToken, id, code: mail.code };
  };
  let user: any, other: any, project: string, first: any;
  const create = async (
    pid = project,
    options: { releaseId?: string; expiryDays?: number; key?: string } = {},
  ) => {
    const f = syntheticFixture(options.releaseId),
      digest = sha(canonicalJson(f.scene)).toString("hex"),
      body = {
        serverProjectId: pid,
        releaseId: f.scene.releaseId,
        contentDigest: digest,
        expiryDays: options.expiryDays ?? 7,
        scene: f.scene,
      },
      key = options.key ?? randomUUID();
    const r = await request(
      "POST",
      "/api/v1/publication-uploads",
      body,
      user.session,
      { "idempotency-key": key },
    );
    return { ...f, digest, body, key, response: r, upload: r.json() };
  };
  const put = async (
    f: any,
    asset = f.scene.assets[0],
    body = f.bytes.get(asset.id),
    session = user.session,
  ) =>
    request(
      "PUT",
      `/api/v1/publication-uploads/${f.upload.uploadId}/assets/${asset.id}`,
      body,
      session,
      {
        "content-type": asset.mime,
        "content-length": String(asset.byteLength),
        "x-content-sha256": asset.sha256,
      },
    );
  const submit = async (f: any, key = randomUUID()) =>
    request(
      "POST",
      `/api/v1/publication-uploads/${f.upload.uploadId}/commit`,
      { releaseId: f.scene.releaseId, contentDigest: f.digest },
      user.session,
      { "idempotency-key": key },
    );
  try {
    await t.test(
      "synthetic mailbox one-use, attempt limits, logout and ownership",
      async () => {
        assert.equal(
          (
            await request("POST", "/api/v1/auth/challenges", {
              email: "real@example.com",
            })
          ).statusCode,
          400,
        );
        assert.equal(
          (
            await app.inject({
              url: "/api/v1/capabilities",
              headers: { host: "evil.example" },
            })
          ).statusCode,
          403,
        );
        assert.equal(
          (
            await request("GET", "/api/v1/capabilities", undefined, undefined, {
              origin: "https://evil.example",
            })
          ).statusCode,
          403,
        );
        const malformed = Buffer.concat([
          Buffer.from('{"email":"'),
          Buffer.from([0xc3, 0x28]),
          Buffer.from('@example.test"}'),
        ]);
        assert.equal(
          (
            await request(
              "POST",
              "/api/v1/auth/challenges",
              malformed,
              undefined,
              { "content-type": "application/json" },
            )
          ).statusCode,
          400,
        );
        user = await login("owner@example.test");
        other = await login("other@example.test");
        assert.equal(
          (
            await request("POST", "/api/v1/auth/sessions", {
              challengeId: user.id,
              code: user.code,
            })
          ).statusCode,
          401,
        );
        const p = await request(
          "POST",
          "/api/v1/projects",
          { clientProjectId: randomUUID(), title: "Fixture" },
          user.session,
        );
        project = p.json().serverProjectId;
        assert.equal(
          (
            await request(
              "GET",
              `/api/v1/projects/${project}/publications`,
              undefined,
              other.session,
            )
          ).statusCode,
          404,
        );
        const challenge = (
          await request("POST", "/api/v1/auth/challenges", {
            email: "attempts@example.test",
          })
        ).json();
        const mail = JSON.parse(
          await fs.readFile(
            path.join(root, "inbox", `${challenge.challengeId}.json`),
            "utf8",
          ),
        );
        for (let i = 0; i < 5; i++)
          assert.equal(
            (
              await request("POST", "/api/v1/auth/sessions", {
                challengeId: challenge.challengeId,
                code: mail.code === "000000" ? "000001" : "000000",
              })
            ).statusCode,
            401,
          );
        assert.equal(
          (
            await request("POST", "/api/v1/auth/sessions", {
              challengeId: challenge.challengeId,
              code: mail.code,
            })
          ).statusCode,
          401,
        );
      },
    );
    await t.test(
      "declared bytes, digest, duplicate JSON, resumable upload and durable commit",
      async () => {
        first = await create();
        assert.equal(first.response.statusCode, 201, first.response.body);
        const replay = await request(
          "POST",
          "/api/v1/publication-uploads",
          first.body,
          user.session,
          { "idempotency-key": first.key },
        );
        assert.equal(replay.json().uploadId, first.upload.uploadId);
        assert.equal(
          (
            await request(
              "POST",
              "/api/v1/publication-uploads",
              { ...first.body, expiryDays: 30 },
              user.session,
              { "idempotency-key": first.key },
            )
          ).statusCode,
          409,
        );
        assert.equal(
          (
            await request(
              "POST",
              "/api/v1/auth/challenges",
              '{"email":"a@example.test","email":"b@example.test"}',
              undefined,
              { "content-type": "application/json" },
            )
          ).statusCode,
          400,
        );
        assert.equal((await submit(first)).statusCode, 409);
        assert.equal(
          (await put(first, undefined, undefined, other.session)).statusCode,
          404,
        );
        const asset = first.scene.assets[0],
          bad = Buffer.from(first.bytes.get(asset.id));
        bad[0] ^= 1;
        assert.equal((await put(first, asset, bad)).statusCode, 400);
        for (const a of first.scene.assets)
          assert.equal((await put(first, a)).statusCode, 200);
        const key = randomUUID();
        assert.equal((await submit(first, key)).statusCode, 202);
        assert.equal((await submit(first, key)).statusCode, 202);
        assert.equal((await put(first)).statusCode, 409);
        // A new worker instance recovers persisted intent, without in-memory job state.
        const recovered = new PublicationWorker(db, files, cfg);
        await recovered.tick();
        const status = (
          await request(
            "GET",
            `/api/v1/publication-uploads/${first.upload.uploadId}`,
            undefined,
            user.session,
          )
        ).json();
        assert.equal(status.state, "committed", JSON.stringify(status));
        first.publication = status.publication;
        assert.equal(
          (await submit(first, key)).json().publicationId,
          status.publication.publicationId,
        );
        assert.equal((await submit(first)).statusCode, 409);
        await assert.rejects(
          () =>
            db.query(
              `UPDATE releases SET scene_json='{}' WHERE release_id=$1`,
              [first.scene.releaseId],
            ),
          /immutable/,
        );
        await assert.rejects(
          () =>
            db.query(
              `UPDATE shares SET expires_at=expires_at+interval '1 day' WHERE share_id=$1`,
              [status.publication.publicationId],
            ),
          /immutable/,
        );
      },
    );
    await t.test(
      "strong authorization gates manifest HTML HEAD Range and revocation",
      async () => {
        const route = new URL(first.publication.shareUrl).pathname,
          asset = first.scene.assets[0],
          media = `${route}/assets/${asset.id}`;
        const manifest = await request("GET", route + "/manifest");
        assert.equal(manifest.statusCode, 200);
        assert.equal(manifest.headers["cache-control"], "private, no-store");
        assert.equal(manifest.headers["referrer-policy"], "no-referrer");
        assert.equal(
          manifest.json().scene.assets[0].path.startsWith("assets/"),
          true,
        );
        const full = await request("GET", media);
        assert.deepEqual(full.rawPayload, first.bytes.get(asset.id));
        const head = await request("HEAD", media);
        assert.equal(head.statusCode, 200);
        assert.equal(head.rawPayload.length, 0);
        const range = await request("GET", media, undefined, undefined, {
          range: "bytes=1-7",
        });
        assert.equal(range.statusCode, 206);
        assert.deepEqual(range.rawPayload, full.rawPayload.subarray(1, 8));
        assert.equal(
          (
            await request("GET", media, undefined, undefined, {
              range: "bytes=0-1,4-8",
            })
          ).statusCode,
          416,
        );
        assert.equal(
          (await request("GET", `${route}/assets/${randomUUID()}`)).statusCode,
          404,
        );
        const revoke = await request(
          "POST",
          `/api/v1/publications/${first.publication.publicationId}/revoke`,
          undefined,
          user.session,
        );
        assert.equal(revoke.statusCode, 200, revoke.body);
        assert.equal(
          (
            await request(
              "POST",
              `/api/v1/publications/${first.publication.publicationId}/revoke`,
              undefined,
              user.session,
            )
          ).json().revokedAt,
          revoke.json().revokedAt,
        );
        for (const [method, url] of [
          ["GET", route],
          ["GET", route + "/manifest"],
          ["HEAD", media],
          ["GET", media],
        ])
          assert.equal(
            (
              await request(method, url, undefined, undefined, {
                range: "bytes=0-7",
                "if-none-match": "anything",
              })
            ).statusCode,
            410,
          );
        assert.equal(
          Number(
            (await db.query("SELECT count(*) n FROM share_revocations")).rows[0]
              .n,
          ),
          1,
        );
        assert.equal(
          (
            await db.query(
              "SELECT bool_and(r.revoked_at=s.revoked_at) exact FROM share_revocations r JOIN shares s USING(share_id)",
            )
          ).rows[0].exact,
          true,
        );
      },
    );
    await t.test(
      "expired links fail before HTML, manifest, HEAD or Range bytes",
      async () => {
        // Seed an already-aged release in this isolated schema. Production expiry cannot be updated.
        const uploadId = randomUUID(),
          releaseId = randomUUID(),
          shareId = randomUUID(),
          capability = token();
        const scene = { ...first.scene, releaseId },
          digest = sha(canonicalJson(scene));
        await db.query(
          `INSERT INTO publication_uploads(upload_id,project_id,owner_id,release_id,scene_json,content_digest,expiry_days,state,idempotency_key,request_digest,reserved_until)
        SELECT $1,project_id,owner_id,$2,$3,$4,1,'committed',$5,request_digest,clock_timestamp() FROM publication_uploads WHERE upload_id=$6`,
          [
            uploadId,
            releaseId,
            scene,
            digest,
            randomUUID(),
            first.upload.uploadId,
          ],
        );
        await db.query(
          `INSERT INTO releases(release_id,project_id,owner_id,upload_id,version_ordinal,schema_version,policy_version,scene_json,content_digest)
        SELECT $1,project_id,owner_id,$2,900,'1','static-viewer-1',$3,$4 FROM releases WHERE release_id=$5`,
          [releaseId, uploadId, scene, digest, first.scene.releaseId],
        );
        await db.query(
          `INSERT INTO shares(share_id,release_id,token_hash,token_ciphertext,token_key_version,expires_at)
        VALUES($1,$2,$3,$4,'local-v1',clock_timestamp()-interval '1 second')`,
          [
            shareId,
            releaseId,
            sha(capability),
            encryptToken(cfg.shareKey, capability),
          ],
        );
        for (const [method, suffix] of [
          ["GET", ""],
          ["GET", "/manifest"],
          ["HEAD", `/assets/${first.scene.assets[0].id}`],
          ["GET", `/assets/${first.scene.assets[0].id}`],
        ]) {
          const result = await request(
            method,
            `/s/${capability}${suffix}`,
            undefined,
            undefined,
            { range: "bytes=0-5" },
          );
          assert.equal(result.statusCode, 410);
          if (suffix === "/manifest")
            assert.equal(result.json().error.code, "SHARE_EXPIRED");
        }
      },
    );
    await t.test(
      "concurrent reservations enforce five slots; cancel and expiry release slots",
      async () => {
        const results = await Promise.all(
          Array.from({ length: 6 }, () => create()),
        );
        assert.equal(
          results.filter((x) => x.response.statusCode === 201).length,
          5,
        );
        assert.equal(
          results.filter((x) => x.response.statusCode === 409).length,
          1,
        );
        const active = results.filter((x) => x.response.statusCode === 201);
        assert.equal(
          (
            await request(
              "DELETE",
              `/api/v1/publication-uploads/${active[0].upload.uploadId}`,
              undefined,
              user.session,
            )
          ).json().state,
          "cancelled",
        );
        assert.equal((await submit(active[0])).statusCode, 409);
        assert.equal((await create()).response.statusCode, 201);
        await db.query(
          `UPDATE publication_uploads SET reserved_until=clock_timestamp()-interval '1 second' WHERE upload_id=$1`,
          [active[1].upload.uploadId],
        );
        assert.equal((await create()).response.statusCode, 201);
        for (const f of active)
          await request(
            "DELETE",
            `/api/v1/publication-uploads/${f.upload.uploadId}`,
            undefined,
            user.session,
          );
      },
    );
    await t.test(
      "owner-wide key and release races conflict deterministically",
      async () => {
        const p1 = (
          await request(
            "POST",
            "/api/v1/projects",
            { clientProjectId: randomUUID(), title: "Race A" },
            user.session,
          )
        ).json().serverProjectId;
        const p2 = (
          await request(
            "POST",
            "/api/v1/projects",
            { clientProjectId: randomUUID(), title: "Race B" },
            user.session,
          )
        ).json().serverProjectId;
        const key = randomUUID();
        const race = await Promise.all([
          create(p1, { key }),
          create(p2, { key }),
        ]);
        assert.deepEqual(
          race.map((x) => x.response.statusCode).sort(),
          [201, 409],
        );
        const releaseId = randomUUID();
        const releases = await Promise.all([
          create(p1, { releaseId }),
          create(p2, { releaseId }),
        ]);
        assert.deepEqual(
          releases.map((x) => x.response.statusCode).sort(),
          [201, 409],
        );
      },
    );
    await t.test(
      "final-copy interruption recovers; cancel wins before publication",
      async () => {
        const pid = (
          await request(
            "POST",
            "/api/v1/projects",
            { clientProjectId: randomUUID(), title: "Recovery" },
            user.session,
          )
        ).json().serverProjectId;
        const f = await create(pid);
        for (const a of f.scene.assets)
          assert.equal((await put(f, a)).statusCode, 200);
        assert.equal((await submit(f)).statusCode, 202);
        const finalize = files.finalize.bind(files);
        let interrupted = false;
        files.finalize = async (...args: Parameters<typeof finalize>) => {
          await finalize(...args);
          if (!interrupted) {
            interrupted = true;
            throw Object.assign(new Error("Synthetic interrupted copy"), {
              code: "EIO",
            });
          }
        };
        try {
          await worker.tick();
        } finally {
          files.finalize = finalize;
        }
        let status = (
          await request(
            "GET",
            `/api/v1/publication-uploads/${f.upload.uploadId}`,
            undefined,
            user.session,
          )
        ).json();
        assert.equal(status.state, "validating");
        assert.equal(status.publication, null);
        assert.equal(status.error.code, "VALIDATION_RETRY");
        await db.query(
          "UPDATE publication_uploads SET next_attempt_at=NULL WHERE upload_id=$1",
          [f.upload.uploadId],
        );
        await new PublicationWorker(db, files, cfg).tick();
        status = (
          await request(
            "GET",
            `/api/v1/publication-uploads/${f.upload.uploadId}`,
            undefined,
            user.session,
          )
        ).json();
        assert.equal(status.state, "committed");
        assert(status.publication.shareUrl);
        const cancelled = await create(pid);
        for (const a of cancelled.scene.assets)
          assert.equal((await put(cancelled, a)).statusCode, 200);
        assert.equal((await submit(cancelled)).statusCode, 202);
        assert.equal(
          (
            await request(
              "DELETE",
              `/api/v1/publication-uploads/${cancelled.upload.uploadId}`,
              undefined,
              user.session,
            )
          ).json().state,
          "cancelled",
        );
        await worker.tick();
        const result = (
          await request(
            "GET",
            `/api/v1/publication-uploads/${cancelled.upload.uploadId}`,
            undefined,
            user.session,
          )
        ).json();
        assert.equal(result.state, "cancelled");
        assert.equal(result.publication, null);
        const committedCancel = (
          await request(
            "DELETE",
            `/api/v1/publication-uploads/${f.upload.uploadId}`,
            undefined,
            user.session,
          )
        ).json();
        assert.equal(committedCancel.state, "committed");
        assert.equal(
          committedCancel.publication.publicationId,
          status.publication.publicationId,
        );
      },
    );
    await t.test(
      "invalid actual media is never published; database outage fails closed",
      async () => {
        const p = (
          await request(
            "POST",
            "/api/v1/projects",
            { clientProjectId: randomUUID(), title: "Bad media" },
            user.session,
          )
        ).json().serverProjectId;
        const f = await create(p);
        const a = f.scene.assets[0],
          b = Buffer.alloc(a.byteLength, 65);
        f.bytes.set(a.id, b);
        a.sha256 = sha(b).toString("hex");
        // Cancel initial reservation and submit a correctly declared but invalid PNG.
        await request(
          "DELETE",
          `/api/v1/publication-uploads/${f.upload.uploadId}`,
          undefined,
          user.session,
        );
        f.digest = sha(canonicalJson(f.scene)).toString("hex");
        f.body = { ...f.body, scene: f.scene, contentDigest: f.digest };
        f.upload = (
          await request(
            "POST",
            "/api/v1/publication-uploads",
            f.body,
            user.session,
            { "idempotency-key": randomUUID() },
          )
        ).json();
        for (const asset of f.scene.assets)
          assert.equal((await put(f, asset)).statusCode, 200);
        assert.equal((await submit(f)).statusCode, 202);
        await worker.tick();
        const s = (
          await request(
            "GET",
            `/api/v1/publication-uploads/${f.upload.uploadId}`,
            undefined,
            user.session,
          )
        ).json();
        assert.equal(s.state, "rejected");
        assert.equal(s.publication, null);
        assert.equal(
          (
            await request(
              "DELETE",
              "/api/v1/auth/sessions/current",
              undefined,
              other.session,
            )
          ).statusCode,
          204,
        );
        assert.equal(
          (await request("GET", "/api/v1/me", undefined, other.session))
            .statusCode,
          401,
        );
        await db.end();
        assert.equal(
          (
            await request(
              "GET",
              new URL(first.publication.shareUrl).pathname + "/manifest",
            )
          ).statusCode,
          503,
        );
      },
    );
  } finally {
    await app.close();
    await db.end().catch(() => {});
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
    await fs.rm(root, { recursive: true, force: true });
  }
});
