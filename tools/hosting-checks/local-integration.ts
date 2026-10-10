/** Actual production Android Java engine → loopback Fastify/PostgreSQL, synthetic content only. */
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import pg from "pg";
import { buildApp } from "../../server/src/app.js";
import { config } from "../../server/src/config.js";
import { migrate } from "../../server/src/migrate.js";
import { syntheticFixture } from "../../server/scripts/synthetic.js";
import { canonicalJson } from "../../runtime-ts/src/index.js";

const url = process.env.TEST_DATABASE_URL;
assert(url, "NOT_RUN: TEST_DATABASE_URL required for actual Android HTTP check");
const classes = path.resolve(process.argv[2]);
const admin = new pg.Pool({ connectionString: url });
const schema = `android_${randomUUID().replaceAll("-", "")}`;
await admin.query(`CREATE SCHEMA ${schema}`);
const db = new pg.Pool({ connectionString: url, options: `-c search_path=${schema}` });
const root = await fs.mkdtemp(path.join(os.tmpdir(), "tapscene-android-http-"));
const fixtureRoot = path.join(root, "synthetic-fixture");
await fs.mkdir(path.join(fixtureRoot, "assets"), { recursive: true });
const { scene, bytes } = syntheticFixture();
await fs.writeFile(path.join(fixtureRoot, "scene.json"), canonicalJson(scene));
for (const asset of scene.assets) await fs.writeFile(path.join(fixtureRoot, asset.path), bytes.get(asset.id)!);
const cfg = { ...config({ DATABASE_URL: url, PORT: "4173" }), dataRoot: path.join(root, "server"),
  webRoot: path.resolve("web-player/dist") };
const { app, worker } = await buildApp(cfg, db);
try {
  await migrate(db);
  await app.listen({ host: cfg.host, port: cfg.port });
  worker.start();
  await new Promise<void>((resolve, reject) => {
    const child = spawn("java", ["-cp", classes, "com.tapscene.hosting.HostedHttpIntegration", root, fixtureRoot],
      { stdio: "inherit" });
    const timer = setTimeout(() => { child.kill("SIGTERM"); reject(new Error("Android Java HTTP check timed out")); }, 90000);
    child.once("error", error => { clearTimeout(timer); reject(error); });
    child.once("exit", code => { clearTimeout(timer); code === 0 ? resolve() : reject(new Error(`Android Java HTTP check exit=${code}`)); });
  });
  const counts = await db.query("SELECT (SELECT count(*) FROM releases) releases, (SELECT count(*) FROM shares WHERE revoked_at IS NOT NULL) revoked");
  assert.equal(Number(counts.rows[0].releases), 1, "Recovery must never create a second publication");
  assert.equal(Number(counts.rows[0].revoked), 1);
  console.log("HOST_HOSTED_HTTP database one-release/one-revocation after response-loss recovery PASS");
} finally {
  await app.close();
  await db.end();
  await admin.query(`DROP SCHEMA ${schema} CASCADE`);
  await admin.end();
  await fs.rm(root, { recursive: true, force: true });
}
