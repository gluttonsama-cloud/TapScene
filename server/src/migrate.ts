import fs from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { database, transaction, type Db } from "./db.js";
import { config } from "./config.js";
import { sha } from "./security.js";
export async function migrate(db: Db) {
  const sql = await fs.readFile(
      new URL("../migrations/001_hosted.sql", import.meta.url),
      "utf8",
    ),
    digest = sha(sql).toString("hex");
  await transaction(db, async (c) => {
    await c.query("SELECT pg_advisory_xact_lock(794188001)");
    await c.query(
      "CREATE TABLE IF NOT EXISTS schema_migrations(version integer PRIMARY KEY,digest text NOT NULL,applied_at timestamptz NOT NULL DEFAULT now())",
    );
    const old = await c.query(
      "SELECT digest FROM schema_migrations WHERE version=1",
    );
    if (old.rowCount) {
      if (old.rows[0].digest !== digest)
        throw new Error("Applied migration checksum mismatch");
      return;
    }
    await c.query(sql);
    await c.query(
      "INSERT INTO schema_migrations(version,digest) VALUES(1,$1)",
      [digest],
    );
  });
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const db = database(config().databaseUrl);
  try {
    await migrate(db);
    process.stdout.write("Local hosted schema is current.\n");
  } finally {
    await db.end();
  }
}
