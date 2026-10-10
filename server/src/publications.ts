import type { PoolClient } from "pg";
import type { Config } from "./config.js";
import { decryptToken, ApiError } from "./security.js";
import type { Db } from "./db.js";
export const publicationSql = `SELECT s.*,r.project_id,r.owner_id,r.version_ordinal,r.content_digest,r.scene_json,
 CASE WHEN s.revoked_at IS NOT NULL THEN 'revoked' WHEN s.expires_at<=clock_timestamp() THEN 'expired' ELSE 'active' END status
 FROM shares s JOIN releases r USING(release_id)`;
export function publication(row: any, cfg: Config) {
  return {
    publicationId: row.share_id,
    serverProjectId: row.project_id,
    releaseId: row.release_id,
    versionOrdinal: row.version_ordinal,
    contentDigest: row.content_digest.toString("hex"),
    title: row.scene_json.title,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
    status: row.status,
    shareUrl: `${cfg.origin}/s/${decryptToken(cfg.shareKey, row.token_ciphertext)}`,
    revokedAt: row.revoked_at,
  };
}
export async function receipt(
  db: Db | PoolClient,
  cfg: Config,
  uploadId: string,
) {
  const q = await db.query(`${publicationSql} WHERE r.upload_id=$1`, [
    uploadId,
  ]);
  if (!q.rowCount) throw new ApiError(503, "PUBLICATION_UNAVAILABLE");
  return publication(q.rows[0], cfg);
}
