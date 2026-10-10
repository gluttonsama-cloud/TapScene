import Fastify, { type FastifyRequest } from "fastify";
import { randomUUID, randomInt } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import type { Readable } from "node:stream";
import { parseJson, validateScene, canonicalJson } from "@tapscene/runtime-ts";
import type { Config } from "./config.js";
import { database, transaction, type Db } from "./db.js";
import { PrivateFiles } from "./storage.js";
import {
  ApiError,
  requireValue,
  bodyObject,
  uuid,
  sha,
  token,
  codeMac,
  equal,
} from "./security.js";
import { PublicationWorker } from "./worker.js";
import { publicationSql, publication, receipt } from "./publications.js";
const MAX_BYTES = 50 * 1024 * 1024;
export async function buildApp(cfg: Config, providedDb?: Db) {
  const db = providedDb ?? database(cfg.databaseUrl),
    files = new PrivateFiles(cfg.dataRoot);
  await files.init();
  const app = Fastify({
    logger: false,

    bodyLimit: 600 * 1024,
    requestTimeout: 30000,
    connectionTimeout: 30000,
    trustProxy: false,
  });
  const worker = new PublicationWorker(db, files, cfg);
  app.removeContentTypeParser("application/json");
  app.addContentTypeParser(
    "application/json",
    { parseAs: "buffer", bodyLimit: 600 * 1024 },
    (_r, body, done) => {
      try {
        done(
          null,
          parseJson(
            new TextDecoder("utf-8", { fatal: true }).decode(body as Buffer),
            600 * 1024,
          ),
        );
      } catch {
        done(new ApiError(400, "INVALID_JSON"));
      }
    },
  );
  app.addContentTypeParser(["image/png", "video/mp4"], (_r, stream, done) =>
    done(null, stream),
  );
  app.addHook("onRequest", async (req, reply) => {
    reply.headers({
      "Cache-Control": "private, no-store",
      "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "DENY",
      "Content-Security-Policy":
        "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; media-src 'self'; connect-src 'self'; font-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    });
    requireValue(
      req.headers.host === `127.0.0.1:${cfg.port}`,
      "LOCAL_HOST_REQUIRED",
      403,
    );
    requireValue(
      ["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(req.ip),
      "LOCAL_REQUEST_REQUIRED",
      403,
    );
    requireValue(
      !req.headers.origin || req.headers.origin === cfg.origin,
      "ORIGIN_FORBIDDEN",
      403,
    );
  });
  app.setErrorHandler((e, _req, reply) => {
    const error = e as Error & { statusCode?: number; code?: string };
    const status =
      error instanceof ApiError
        ? error.statusCode
        : error.statusCode && error.statusCode >= 400 && error.statusCode < 500
          ? error.statusCode
          : 503;
    reply.status(status).send({
      error: {
        code:
          error instanceof ApiError
            ? error.code
            : status === 503
              ? "SERVICE_UNAVAILABLE"
              : "INVALID_REQUEST",
      },
    });
  });
  async function owner(req: FastifyRequest) {
    const h = req.headers.authorization;
    requireValue(
      typeof h === "string" && /^Bearer [A-Za-z0-9_-]{43}$/.test(h),
      "AUTH_REQUIRED",
      401,
    );
    const q = await db.query(
      `SELECT a.account_id,a.email,s.session_id,s.expires_at FROM sessions s JOIN accounts a USING(account_id) WHERE s.token_hash=$1 AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp() AND a.disabled_at IS NULL`,
      [sha(h.slice(7))],
    );
    requireValue(q.rowCount, "AUTH_REQUIRED", 401);
    return q.rows[0];
  }
  function params(req: FastifyRequest) {
    return req.params as Record<string, string>;
  }
  function key(req: FastifyRequest) {
    return uuid(req.headers["idempotency-key"]);
  }
  function pagination(req: FastifyRequest) {
    const q = req.query as Record<string, string>;
    requireValue(Object.keys(q).every((k) => ["cursor", "limit"].includes(k)));
    const limit = q.limit === undefined ? 20 : Number(q.limit);
    requireValue(Number.isInteger(limit) && limit >= 1 && limit <= 50);
    return { limit, cursor: q.cursor ? uuid(q.cursor) : null };
  }
  async function ownedUpload(id: string, accountId: string) {
    const q = await db.query(
      `SELECT * FROM publication_uploads WHERE upload_id=$1 AND owner_id=$2`,
      [id, accountId],
    );
    requireValue(q.rowCount, "NOT_FOUND", 404);
    return q.rows[0];
  }
  app.get("/api/v1/capabilities", async () => ({
    schemaVersions: ["1", "2", "3"],
    policyVersions: ["static-viewer-1", "video-viewer-2", "scene-regions-3"],
    limits: {
      states: 40,
      edges: 80,
      hotspotsPerState: 6,
      assets: 200,
      packageBytes: MAX_BYTES,
      activePublications: 5,
      uploadReservationSeconds: 3600,
    },
    expiryDays: [1, 7, 30],
    defaultExpiryDays: 7,
    mode: "local",
    authentication: "synthetic-mailbox-only",
  }));
  app.post("/api/v1/auth/challenges", async (req, reply) => {
    const b = bodyObject(req.body, ["email"]);
    requireValue(
      typeof b.email === "string" &&
        /^[a-zA-Z0-9.!#$%&'*+\-/=?^_`{|}~]+@example\.test$/.test(b.email) &&
        b.email.length <= 254,
      "SYNTHETIC_EMAIL_REQUIRED",
    );
    const email = b.email.toLowerCase(),
      id = randomUUID(),
      code = String(randomInt(1000000)).padStart(6, "0");
    const row = await transaction(db, async (c) => {
      await c.query("SELECT pg_advisory_xact_lock(hashtextextended($1,0))", [
        email,
      ]);
      const n = await c.query(
        `SELECT count(*) n FROM auth_challenges WHERE email_normalized=$1 AND created_at>clock_timestamp()-interval '60 seconds'`,
        [email],
      );
      requireValue(Number(n.rows[0].n) === 0, "RESEND_TOO_SOON", 429);
      const all = await c.query(
        `SELECT count(*) n FROM auth_challenges WHERE created_at>clock_timestamp()-interval '1 minute'`,
      );
      requireValue(Number(all.rows[0].n) < 30, "RATE_LIMITED", 429);
      return (
        await c.query(
          `INSERT INTO auth_challenges(challenge_id,email_normalized,code_mac,expires_at) VALUES($1,$2,$3,clock_timestamp()+interval '5 minutes') RETURNING expires_at`,
          [id, email, codeMac(cfg.authKey, id, email, code)],
        )
      ).rows[0];
    });
    await files.inbox(id, {
      challengeId: id,
      email,
      code,
      expiresAt: row.expires_at,
      localSyntheticOnly: true,
    });
    return reply.code(202).send({
      challengeId: id,
      expiresAt: row.expires_at,
      resendAfterSeconds: 60,
    });
  });
  app.post("/api/v1/auth/sessions", async (req, reply) => {
    const b = bodyObject(req.body, ["challengeId", "code"]),
      id = uuid(b.challengeId);
    requireValue(typeof b.code === "string" && /^\d{6}$/.test(b.code));
    const result = await transaction(db, async (c) => {
      const q = await c.query(
        `SELECT *,expires_at>clock_timestamp() valid FROM auth_challenges WHERE challenge_id=$1 FOR UPDATE`,
        [id],
      );
      if (!q.rowCount) return null;
      const ch = q.rows[0];
      if (!ch.valid || ch.consumed_at || ch.attempt_count >= ch.max_attempts)
        return null;
      await c.query(
        "UPDATE auth_challenges SET attempt_count=attempt_count+1 WHERE challenge_id=$1",
        [id],
      );
      if (
        !equal(
          ch.code_mac,
          codeMac(cfg.authKey, id, ch.email_normalized, b.code as string),
        )
      )
        return null;
      await c.query(
        "UPDATE auth_challenges SET consumed_at=clock_timestamp() WHERE challenge_id=$1",
        [id],
      );
      const a = (
        await c.query(
          `INSERT INTO accounts(account_id,email,email_normalized) VALUES($1,$2,$2) ON CONFLICT(email_normalized) DO UPDATE SET email_normalized=excluded.email_normalized RETURNING *`,
          [randomUUID(), ch.email_normalized],
        )
      ).rows[0];
      if (a.disabled_at) return null;
      const value = token();
      const s = (
        await c.query(
          `INSERT INTO sessions(session_id,account_id,token_hash,expires_at) VALUES($1,$2,$3,clock_timestamp()+interval '1 day') RETURNING expires_at`,
          [randomUUID(), a.account_id, sha(value)],
        )
      ).rows[0];
      return {
        sessionToken: value,
        expiresAt: s.expires_at,
        account: { accountId: a.account_id, email: a.email },
      };
    });
    requireValue(result, "INVALID_CHALLENGE", 401);
    return reply.code(201).send(result);
  });
  app.get("/api/v1/me", async (req) => {
    const a = await owner(req);
    return {
      accountId: a.account_id,
      email: a.email,
      sessionExpiresAt: a.expires_at,
    };
  });
  app.delete("/api/v1/auth/sessions/current", async (req, reply) => {
    const a = await owner(req);
    await db.query(
      "UPDATE sessions SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE session_id=$1",
      [a.session_id],
    );
    return reply.code(204).send();
  });
  app.post("/api/v1/projects", async (req, reply) => {
    const a = await owner(req),
      b = bodyObject(req.body, ["clientProjectId", "title"]),
      id = uuid(b.clientProjectId);
    requireValue(
      typeof b.title === "string" &&
        b.title.trim().length > 0 &&
        b.title.length <= 240,
    );
    const q = await db.query(
      `INSERT INTO hosted_projects(project_id,owner_id,client_project_id,title) VALUES($1,$2,$3,$4) ON CONFLICT(owner_id,client_project_id) DO NOTHING RETURNING *`,
      [randomUUID(), a.account_id, id, b.title],
    );
    const p = q.rowCount
      ? q.rows[0]
      : (
          await db.query(
            "SELECT * FROM hosted_projects WHERE owner_id=$1 AND client_project_id=$2",
            [a.account_id, id],
          )
        ).rows[0];
    return reply.code(q.rowCount ? 201 : 200).send({
      serverProjectId: p.project_id,
      clientProjectId: p.client_project_id,
      title: p.title,
    });
  });
  app.get("/api/v1/projects", async (req) => {
    const a = await owner(req),
      { limit, cursor } = pagination(req);
    const q = await db.query(
      `SELECT p.*,(SELECT count(*) FROM shares s JOIN releases r USING(release_id) WHERE r.project_id=p.project_id AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp()) active_count FROM hosted_projects p WHERE p.owner_id=$1 AND ($2::uuid IS NULL OR p.project_id>$2) ORDER BY p.project_id LIMIT $3`,
      [a.account_id, cursor, limit + 1],
    );
    return {
      items: q.rows.slice(0, limit).map((p) => ({
        serverProjectId: p.project_id,
        clientProjectId: p.client_project_id,
        title: p.title,
        activePublicationCount: Number(p.active_count),
        latestVersionOrdinal: p.next_version - 1,
      })),
      nextCursor: q.rows.length > limit ? q.rows[limit - 1].project_id : null,
    };
  });
  app.post("/api/v1/publication-uploads", async (req, reply) => {
    const a = await owner(req),
      b = bodyObject(req.body, [
        "serverProjectId",
        "releaseId",
        "contentDigest",
        "expiryDays",
        "scene",
      ]),
      projectId = uuid(b.serverProjectId),
      releaseId = uuid(b.releaseId),
      idem = key(req);
    let scene;
    try {
      scene = validateScene(b.scene);
    } catch {
      throw new ApiError(400, "INVALID_SCENE");
    }
    requireValue(
      Buffer.byteLength(canonicalJson(scene)) +
        scene.assets.reduce((n, a) => n + a.byteLength, 0) <=
        MAX_BYTES,
      "PACKAGE_BYTES_EXCEEDED",
      413,
    );
    const digest = sha(canonicalJson(scene));
    requireValue(
      scene.releaseId === releaseId &&
        b.contentDigest === digest.toString("hex"),
      "CONTENT_DIGEST_MISMATCH",
    );
    requireValue([1, 7, 30].includes(b.expiryDays as number), "INVALID_EXPIRY");
    const requestDigest = sha(canonicalJson(b));
    const result = await transaction(db, async (c) => {
      await c.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [
        a.account_id,
      ]);
      const p = await c.query(
        "SELECT * FROM hosted_projects WHERE project_id=$1 AND owner_id=$2 FOR UPDATE",
        [projectId, a.account_id],
      );
      requireValue(p.rowCount, "NOT_FOUND", 404);
      const old = await c.query(
        "SELECT * FROM publication_uploads WHERE owner_id=$1 AND idempotency_key=$2",
        [a.account_id, idem],
      );
      if (old.rowCount) {
        requireValue(
          old.rows[0].request_digest.equals(requestDigest),
          "IDEMPOTENCY_CONFLICT",
          409,
        );
        return { row: old.rows[0], created: false };
      }
      await c.query(
        `UPDATE publication_uploads SET state='expired',lease_token=NULL,lease_until=NULL WHERE project_id=$1 AND state IN ('receiving','validating') AND reserved_until<=clock_timestamp()`,
        [projectId],
      );
      const published = await c.query(
        "SELECT 1 FROM releases WHERE release_id=$1",
        [releaseId],
      );
      requireValue(!published.rowCount, "RELEASE_ALREADY_PUBLISHED", 409);
      const pending = await c.query(
        `SELECT 1 FROM publication_uploads WHERE owner_id=$1 AND release_id=$2 AND state IN ('receiving','validating')`,
        [a.account_id, releaseId],
      );
      requireValue(!pending.rowCount, "RELEASE_UPLOAD_EXISTS", 409);
      const q = await c.query(
        `SELECT (SELECT count(*) FROM publication_uploads WHERE project_id=$1 AND state IN ('receiving','validating') AND reserved_until>clock_timestamp())+(SELECT count(*) FROM shares s JOIN releases r USING(release_id) WHERE r.project_id=$1 AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp()) n`,
        [projectId],
      );
      requireValue(Number(q.rows[0].n) < 5, "PUBLICATION_LIMIT", 409);
      const id = randomUUID(),
        row = (
          await c.query(
            `INSERT INTO publication_uploads(upload_id,project_id,owner_id,release_id,scene_json,content_digest,expiry_days,state,idempotency_key,request_digest,reserved_until) VALUES($1,$2,$3,$4,$5,$6,$7,'receiving',$8,$9,clock_timestamp()+interval '1 hour') RETURNING *`,
            [
              id,
              projectId,
              a.account_id,
              releaseId,
              scene,
              digest,
              b.expiryDays,
              idem,
              requestDigest,
            ],
          )
        ).rows[0];
      for (const asset of scene.assets)
        await c.query(
          `INSERT INTO publication_assets(upload_id,asset_id,relative_path,role,mime,byte_length,sha256,width,height,duration_ms,state) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,'declared')`,
          [
            id,
            asset.id,
            asset.path,
            asset.role,
            asset.mime,
            asset.byteLength,
            Buffer.from(asset.sha256, "hex"),
            asset.width,
            asset.height,
            asset.durationMs,
          ],
        );
      return { row, created: true };
    });
    const missing = (
      await db.query(
        `SELECT asset_id FROM publication_assets WHERE upload_id=$1 AND state='declared' ORDER BY asset_id`,
        [result.row.upload_id],
      )
    ).rows.map((x) => x.asset_id);
    return reply.code(result.created ? 201 : 200).send({
      uploadId: result.row.upload_id,
      state: result.row.state,
      reservedUntil: result.row.reserved_until,
      missingAssetIds: missing,
    });
  });
  app.put(
    "/api/v1/publication-uploads/:uploadId/assets/:assetId",
    { bodyLimit: MAX_BYTES },
    async (req) => {
      const a = await owner(req),
        id = uuid(params(req).uploadId),
        assetId = uuid(params(req).assetId);
      return transaction(db, async (c) => {
        const uq = await c.query(
          `SELECT *,reserved_until>clock_timestamp() valid FROM publication_uploads WHERE upload_id=$1 AND owner_id=$2 FOR UPDATE`,
          [id, a.account_id],
        );
        requireValue(uq.rowCount, "NOT_FOUND", 404);
        const u = uq.rows[0];
        requireValue(
          u.state === "receiving" && u.valid,
          "UPLOAD_NOT_RECEIVING",
          409,
        );
        const aq = await c.query(
          "SELECT * FROM publication_assets WHERE upload_id=$1 AND asset_id=$2",
          [id, assetId],
        );
        requireValue(aq.rowCount, "NOT_FOUND", 404);
        const asset = aq.rows[0],
          length = Number(asset.byte_length),
          digest = asset.sha256.toString("hex");
        requireValue(
          req.headers["content-type"] === asset.mime,
          "ASSET_TYPE_MISMATCH",
        );
        requireValue(
          req.headers["content-length"] === String(length),
          "ASSET_LENGTH_REQUIRED",
        );
        requireValue(
          req.headers["x-content-sha256"] === digest,
          "ASSET_DIGEST_MISMATCH",
        );
        requireValue(
          req.body &&
            typeof (req.body as Readable)[Symbol.asyncIterator] === "function",
          "ASSET_BYTES_REQUIRED",
        );
        const storageKey = `staging/${id}-${assetId}-${digest}`;
        await c.query("SELECT pg_advisory_xact_lock(794188002)");
        await files.capacity(length * 2);
        await files.receive(req.body as Readable, storageKey, length, digest);
        await c.query(
          `UPDATE publication_assets SET state='received',staging_key=$3,received_at=clock_timestamp() WHERE upload_id=$1 AND asset_id=$2`,
          [id, assetId, storageKey],
        );
        return {
          assetId,
          state: "received",
          byteLength: length,
          sha256: digest,
        };
      });
    },
  );
  app.get("/api/v1/publication-uploads/:uploadId", async (req) => {
    const a = await owner(req),
      id = uuid(params(req).uploadId),
      u = await ownedUpload(id, a.account_id);
    const assets = (
      await db.query(
        "SELECT asset_id,state FROM publication_assets WHERE upload_id=$1 ORDER BY asset_id",
        [id],
      )
    ).rows;
    return {
      uploadId: id,
      releaseId: u.release_id,
      state: u.state,
      stage: u.state,
      reservedUntil: u.reserved_until,
      assets: assets.map((x) => ({ assetId: x.asset_id, state: x.state })),
      missingAssetIds: assets
        .filter((x) => x.state === "declared")
        .map((x) => x.asset_id),
      error: u.error_code ? { code: u.error_code } : null,
      publication: u.state === "committed" ? await receipt(db, cfg, id) : null,
    };
  });
  app.delete("/api/v1/publication-uploads/:uploadId", async (req) => {
    const a = await owner(req),
      id = uuid(params(req).uploadId);
    return transaction(db, async (c) => {
      const q = await c.query(
        "SELECT * FROM publication_uploads WHERE upload_id=$1 AND owner_id=$2 FOR UPDATE",
        [id, a.account_id],
      );
      requireValue(q.rowCount, "NOT_FOUND", 404);
      if (q.rows[0].state === "committed")
        return {
          uploadId: id,
          state: "committed",
          publication: await receipt(c, cfg, id),
        };
      await c.query(
        `UPDATE publication_uploads SET state='cancelled',lease_token=NULL,lease_until=NULL WHERE upload_id=$1`,
        [id],
      );
      return { uploadId: id, state: "cancelled" };
    });
  });
  app.post(
    "/api/v1/publication-uploads/:uploadId/commit",
    async (req, reply) => {
      const a = await owner(req),
        id = uuid(params(req).uploadId),
        idem = key(req),
        b = bodyObject(req.body, ["releaseId", "contentDigest"]),
        digest = sha(canonicalJson(b));
      const result = await transaction(db, async (c) => {
        const q = await c.query(
          `SELECT *,reserved_until>clock_timestamp() valid FROM publication_uploads WHERE upload_id=$1 AND owner_id=$2 FOR UPDATE`,
          [id, a.account_id],
        );
        requireValue(q.rowCount, "NOT_FOUND", 404);
        const u = q.rows[0];
        requireValue(
          b.releaseId === u.release_id &&
            b.contentDigest === u.content_digest.toString("hex"),
          "CONTENT_DIGEST_MISMATCH",
          409,
        );
        if (u.commit_key)
          requireValue(
            u.commit_key === idem && u.commit_request_digest.equals(digest),
            "IDEMPOTENCY_CONFLICT",
            409,
          );
        if (u.state === "committed") return await receipt(c, cfg, id);
        requireValue(
          ["receiving", "validating"].includes(u.state) && u.valid,
          "UPLOAD_NOT_COMMITTABLE",
          409,
        );
        const missing = await c.query(
          `SELECT 1 FROM publication_assets WHERE upload_id=$1 AND state='declared' LIMIT 1`,
          [id],
        );
        requireValue(!missing.rowCount, "MISSING_ASSETS", 409);
        await c.query(
          `UPDATE publication_uploads SET state='validating',commit_key=$2,commit_request_digest=$3,commit_requested_at=COALESCE(commit_requested_at,clock_timestamp()) WHERE upload_id=$1`,
          [id, idem, digest],
        );
        return null;
      });
      if (result) return result;
      reply.header("Retry-After", "1");
      return reply.code(202).send({
        uploadId: id,
        state: "validating",
        statusUrl: `/api/v1/publication-uploads/${id}`,
        retryAfterSeconds: 1,
      });
    },
  );
  app.get("/api/v1/projects/:serverProjectId/publications", async (req) => {
    const a = await owner(req),
      id = uuid(params(req).serverProjectId),
      { limit, cursor } = pagination(req);
    const p = await db.query(
      "SELECT 1 FROM hosted_projects WHERE project_id=$1 AND owner_id=$2",
      [id, a.account_id],
    );
    requireValue(p.rowCount, "NOT_FOUND", 404);
    const q = await db.query(
      `${publicationSql} WHERE r.project_id=$1 AND r.owner_id=$2 AND ($3::uuid IS NULL OR s.share_id>$3) ORDER BY s.share_id LIMIT $4`,
      [id, a.account_id, cursor, limit + 1],
    );
    return {
      items: q.rows.slice(0, limit).map((x) => publication(x, cfg)),
      nextCursor: q.rows.length > limit ? q.rows[limit - 1].share_id : null,
    };
  });
  app.get("/api/v1/publications/:publicationId", async (req) => {
    const a = await owner(req),
      id = uuid(params(req).publicationId),
      q = await db.query(
        `${publicationSql} WHERE s.share_id=$1 AND r.owner_id=$2`,
        [id, a.account_id],
      );
    requireValue(q.rowCount, "NOT_FOUND", 404);
    return publication(q.rows[0], cfg);
  });
  app.post("/api/v1/publications/:publicationId/revoke", async (req) => {
    const a = await owner(req),
      id = uuid(params(req).publicationId);
    requireValue(req.body === undefined || req.body === null);
    return transaction(db, async (c) => {
      const q = await c.query(
        `SELECT s.* FROM shares s JOIN releases r USING(release_id) WHERE s.share_id=$1 AND r.owner_id=$2 FOR UPDATE OF s`,
        [id, a.account_id],
      );
      requireValue(q.rowCount, "NOT_FOUND", 404);
      const row = (
        await c.query(
          `UPDATE shares SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE share_id=$1 RETURNING revoked_at,clock_timestamp() confirmed`,
          [id],
        )
      ).rows[0];
      await c.query(
        "INSERT INTO share_revocations(share_id,revoked_at) SELECT share_id,revoked_at FROM shares WHERE share_id=$1 ON CONFLICT(share_id) DO NOTHING",
        [id],
      );
      return {
        publicationId: id,
        status: "revoked",
        revokedAt: row.revoked_at,
        serverConfirmedAt: row.confirmed,
      };
    });
  });
  async function authorize(req: FastifyRequest) {
    const t = params(req).shareToken;
    requireValue(/^[A-Za-z0-9_-]{43}$/.test(t), "NOT_FOUND", 404);
    const q = await db.query(`${publicationSql} WHERE s.token_hash=$1`, [
      sha(t),
    ]);
    requireValue(q.rowCount, "NOT_FOUND", 404);
    const row = q.rows[0];
    requireValue(
      row.status === "active",
      row.status === "revoked" ? "SHARE_REVOKED" : "SHARE_EXPIRED",
      410,
    );
    return row;
  }
  app.get("/s/:shareToken", async (req, reply) => {
    // The shell has no protected data. Denied direct links retain their status
    // and load an accessible error page through the manifest authorization check.
    try {
      await authorize(req);
    } catch (error) {
      reply.code(error instanceof ApiError ? error.statusCode : 503);
    }
    return reply
      .type("text/html; charset=utf-8")
      .send(await fs.readFile(path.join(cfg.webRoot, "index.html")));
  });
  app.get("/s/:shareToken/manifest", async (req) => {
    const row = await authorize(req);
    return {
      releaseId: row.release_id,
      contentDigest: row.content_digest.toString("hex"),
      versionOrdinal: row.version_ordinal,
      expiresAt: row.expires_at,
      scene: row.scene_json,
    };
  });
  app.route({
    method: ["GET", "HEAD"],
    url: "/s/:shareToken/assets/:assetId",
    handler: async (req, reply) => {
      const row = await authorize(req),
        id = uuid(params(req).assetId);
      const q = await db.query(
        `SELECT a.* FROM publication_assets a JOIN releases r USING(upload_id) WHERE r.release_id=$1 AND a.asset_id=$2 AND a.state='verified'`,
        [row.release_id, id],
      );
      requireValue(q.rowCount, "NOT_FOUND", 404);
      const a = q.rows[0],
        size = Number(a.byte_length);
      let start = 0,
        end = size - 1;
      const range = req.headers.range;
      if (range) {
        const m = /^bytes=(\d*)-(\d*)$/.exec(range);
        if (!m || (!m[1] && !m[2]))
          return reply
            .code(416)
            .header("Content-Range", `bytes */${size}`)
            .send();
        if (m[1]) {
          start = Number(m[1]);
          end = m[2] ? Math.min(Number(m[2]), size - 1) : size - 1;
        } else {
          const suffix = Number(m[2]);
          start = Math.max(0, size - suffix);
          if (suffix === 0) start = size;
        }
        if (
          !Number.isSafeInteger(start) ||
          !Number.isSafeInteger(end) ||
          start > end ||
          start >= size
        )
          return reply
            .code(416)
            .header("Content-Range", `bytes */${size}`)
            .send();
      }
      const f = await files.open(a.final_key, size);
      reply.type(a.mime).headers({
        "Accept-Ranges": "bytes",
        "Content-Length": String(end - start + 1),
        "Content-Disposition": "inline",
      });
      if (range)
        reply
          .code(206)
          .header("Content-Range", `bytes ${start}-${end}/${size}`);
      if (req.method === "HEAD") {
        await f.close();
        return reply.send();
      }
      return reply.send(f.createReadStream({ start, end, autoClose: true }));
    },
  });
  app.get("/player-assets/:file", async (req, reply) => {
    const name = params(req).file;
    requireValue(/^[a-zA-Z0-9_-]+\.(js|css)$/.test(name), "NOT_FOUND", 404);
    reply.header("Cache-Control", "public, max-age=31536000, immutable");
    return reply
      .type(
        name.endsWith(".js")
          ? "text/javascript; charset=utf-8"
          : "text/css; charset=utf-8",
      )
      .send(await fs.readFile(path.join(cfg.webRoot, "player-assets", name)));
  });
  app.addHook("onClose", async () => {
    await worker.stop();
    if (!providedDb) await db.end();
  });
  return { app, db, files, worker };
}
