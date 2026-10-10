import { randomUUID } from "node:crypto";
import { validateScene, canonicalJson, type Asset } from "@tapscene/runtime-ts";
import type { Db } from "./db.js";
import { transaction } from "./db.js";
import type { Config } from "./config.js";
import { PrivateFiles } from "./storage.js";
import {
  token,
  sha,
  encryptToken,
  requireValue,
  ApiError,
} from "./security.js";
import { validateMedia, validateRegionCrops } from "./media.js";
/** Durable intent and leases, one bounded decoder at a time per process. */
export class PublicationWorker {
  private busy = false;
  private transient(error: unknown): boolean {
    const e = error as NodeJS.ErrnoException & {
      killed?: boolean;
      signal?: string;
    };
    return Boolean(
      e.killed ||
        e.signal ||
        [
          "ENOENT",
          "EACCES",
          "EIO",
          "EMFILE",
          "ENFILE",
          "ENOMEM",
          "ENOSPC",
          "EBUSY",
          "EAGAIN",
        ].includes(String(e.code)),
    );
  }
  private timer: NodeJS.Timeout | undefined;
  private active: Promise<void> | undefined;
  constructor(
    private db: Db,
    private files: PrivateFiles,
    private cfg: Config,
  ) {}
  start() {
    this.timer = setInterval(() => {
      void this.tick().catch(() => {});
    }, 1000);
    this.timer.unref();
    void this.tick().catch(() => {});
  }
  async stop() {
    if (this.timer) clearInterval(this.timer);
    await this.active;
  }
  async tick() {
    if (this.busy) return;
    this.busy = true;
    this.active = this.run();
    try {
      await this.active;
    } finally {
      this.busy = false;
      this.active = undefined;
    }
  }
  private async run() {
    const job = await transaction(this.db, async (c) => {
      await c.query(
        `UPDATE publication_uploads SET state='expired',lease_token=NULL,lease_until=NULL WHERE state IN ('receiving','validating') AND reserved_until<=clock_timestamp()`,
      );
      const q =
        await c.query(`SELECT * FROM publication_uploads WHERE state='validating' AND reserved_until>clock_timestamp()
    AND (lease_until IS NULL OR lease_until<clock_timestamp()) AND (next_attempt_at IS NULL OR next_attempt_at<=clock_timestamp()) ORDER BY commit_requested_at FOR UPDATE SKIP LOCKED LIMIT 1`);
      if (!q.rowCount) return null;
      const row = q.rows[0];
      row.lease_token = randomUUID();
      await c.query(
        `UPDATE publication_uploads SET lease_token=$2,lease_until=clock_timestamp()+interval '2 minutes',attempt=attempt+1 WHERE upload_id=$1`,
        [row.upload_id, row.lease_token],
      );
      return row;
    });
    if (!job) return;
    let leaseLost = false;
    const heartbeat = setInterval(() => {
      void this.db
        .query(
          `UPDATE publication_uploads SET lease_until=clock_timestamp()+interval '2 minutes' WHERE upload_id=$1 AND lease_token=$2 AND state='validating' AND reserved_until>clock_timestamp()`,
          [job.upload_id, job.lease_token],
        )
        .then((r) => {
          if (!r.rowCount) leaseLost = true;
        })
        .catch(() => {
          leaseLost = true;
        });
    }, 20000);
    heartbeat.unref();
    try {
      const scene = validateScene(job.scene_json);
      requireValue(
        sha(canonicalJson(scene)).equals(job.content_digest),
        "INVALID_SCENE",
      );
      const rows = (
        await this.db.query(
          "SELECT * FROM publication_assets WHERE upload_id=$1",
          [job.upload_id],
        )
      ).rows;
      requireValue(rows.length === scene.assets.length, "INVALID_ASSET_SET");
      const keys = new Map<string, string>();
      for (const asset of scene.assets) {
        requireValue(!leaseLost, "LEASE_LOST", 503);
        const a = rows.find((x) => x.asset_id === asset.id);
        requireValue(
          a && a.staging_key && a.state !== "declared",
          "MISSING_ASSETS",
          409,
        );
        requireValue(
          Number(a.byte_length) === asset.byteLength &&
            a.sha256.toString("hex") === asset.sha256,
          "INVALID_ASSET_SET",
        );
        keys.set(asset.id, a.staging_key);
        await this.files.verify(a.staging_key, asset.byteLength, asset.sha256);
        try {
          await validateMedia(this.files.location(a.staging_key), asset);
        } catch (e) {
          if (this.transient(e)) throw e;
          throw new ApiError(422, "INVALID_MEDIA");
        }
      }
      try {
        await validateRegionCrops(scene, (asset: Asset) =>
          this.files.location(keys.get(asset.id)!),
        );
      } catch (e) {
        if (this.transient(e)) throw e;
        throw new ApiError(422, "INVALID_REGION_PIXELS");
      }
      for (const asset of scene.assets) {
        requireValue(!leaseLost, "LEASE_LOST", 503);
        await transaction(this.db, async (c) => {
          await c.query("SELECT pg_advisory_xact_lock(794188002)");
          await this.files.finalize(
            keys.get(asset.id)!,
            `final/${job.upload_id}-${asset.id}-${asset.sha256}`,
            asset.byteLength,
            asset.sha256,
          );
        });
      }
      await transaction(this.db, async (c) => {
        const p = (
          await c.query(
            "SELECT * FROM hosted_projects WHERE project_id=$1 FOR UPDATE",
            [job.project_id],
          )
        ).rows[0];
        const u = (
          await c.query(
            `SELECT *,reserved_until>clock_timestamp() valid,lease_until>clock_timestamp() lease_valid FROM publication_uploads WHERE upload_id=$1 FOR UPDATE`,
            [job.upload_id],
          )
        ).rows[0];
        if (
          u.state !== "validating" ||
          u.lease_token !== job.lease_token ||
          !u.valid ||
          !u.lease_valid ||
          leaseLost
        )
          return;
        // Lock ordering agrees with creation. Slot remains reserved until this transaction replaces it.
        const count = await c.query(
          `SELECT count(*) n FROM shares s JOIN releases r USING(release_id) WHERE r.project_id=$1 AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp()`,
          [job.project_id],
        );
        requireValue(Number(count.rows[0].n) < 5, "PUBLICATION_LIMIT", 409);
        for (const a of scene.assets)
          await c.query(
            `UPDATE publication_assets SET final_key=$3,state='verified',verified_at=clock_timestamp() WHERE upload_id=$1 AND asset_id=$2`,
            [job.upload_id, a.id, `final/${job.upload_id}-${a.id}-${a.sha256}`],
          );
        await c.query(
          `INSERT INTO releases(release_id,project_id,owner_id,upload_id,version_ordinal,schema_version,policy_version,scene_json,content_digest) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
          [
            job.release_id,
            job.project_id,
            job.owner_id,
            job.upload_id,
            p.next_version,
            String(scene.schemaVersion),
            scene.policyVersion,
            scene,
            job.content_digest,
          ],
        );
        const share = token();
        await c.query(
          `INSERT INTO shares(share_id,release_id,token_hash,token_ciphertext,token_key_version,expires_at) VALUES($1,$2,$3,$4,'local-v1',clock_timestamp()+make_interval(days=>$5))`,
          [
            randomUUID(),
            job.release_id,
            sha(share),
            encryptToken(this.cfg.shareKey, share),
            job.expiry_days,
          ],
        );
        await c.query(
          "UPDATE hosted_projects SET next_version=next_version+1 WHERE project_id=$1",
          [job.project_id],
        );
        await c.query(
          `UPDATE publication_uploads SET state='committed',committed_at=clock_timestamp(),lease_token=NULL,lease_until=NULL,error_code=NULL WHERE upload_id=$1`,
          [job.upload_id],
        );
      });
    } catch (caught) {
      const error =
        (caught as { code?: string }).code === "23505"
          ? new ApiError(409, "RELEASE_ALREADY_PUBLISHED")
          : caught;
      const permanent = error instanceof ApiError && error.statusCode < 500;
      await this.db
        .query(
          `UPDATE publication_uploads SET state=$3,error_code=$4,lease_token=NULL,lease_until=NULL,next_attempt_at=clock_timestamp()+interval '5 seconds' WHERE upload_id=$1 AND lease_token=$2 AND state='validating'`,
          [
            job.upload_id,
            job.lease_token,
            permanent ? "rejected" : "validating",
            permanent ? (error as ApiError).code : "VALIDATION_RETRY",
          ],
        )
        .catch(() => {});
    } finally {
      clearInterval(heartbeat);
    }
  }
}
