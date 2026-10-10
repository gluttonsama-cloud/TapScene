/** Actual PostgreSQL → HTTP uploads → committed private assets → browser → revoke. */
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import pg from "pg";
import { chromium } from "playwright";
import { buildApp } from "../src/app.js";
import { config } from "../src/config.js";
import { migrate } from "../src/migrate.js";
const url = process.env.TEST_DATABASE_URL;
assert(
  url,
  "NOT_RUN: TEST_DATABASE_URL is required for actual hosted browser check",
);
const admin = new pg.Pool({ connectionString: url }),
  schema = `browser_${randomUUID().replaceAll("-", "")}`;
await admin.query(`CREATE SCHEMA ${schema}`);
const db = new pg.Pool({
  connectionString: url,
  options: `-c search_path=${schema}`,
});
const root = await fs.mkdtemp(
  path.join(os.tmpdir(), "tapscene-browser-hosted-"),
);
const cfg = {
  ...config({ DATABASE_URL: url, PORT: "4174" }),
  dataRoot: root,
  webRoot: path.resolve("../web-player/dist"),
};
const { app, worker } = await buildApp(cfg, db);
await migrate(db);
await app.listen({ host: cfg.host, port: cfg.port });
worker.start();
const output = path.resolve("playwright-results");
await fs.mkdir(output, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  ...(process.env.CHROMIUM_PATH
    ? { executablePath: process.env.CHROMIUM_PATH }
    : {}),
  args: ["--no-sandbox"],
});
try {
  await promisify(execFile)(
    process.execPath,
    ["--import", "tsx", "scripts/fixture.ts"],
    {
      env: {
        ...process.env,
        PORT: "4174",
        DATABASE_URL: url,
        TAPSCENE_DATA_DIR: root,
      },
      timeout: 90000,
    },
  );
  const { publication, sessionToken } = JSON.parse(
    await fs.readFile(path.join(root, "fixture-publication.json"), "utf8"),
  );
  const page = await browser.newPage({
    viewport: { width: 1440, height: 980 },
  });
  const failures: string[] = [];
  page.on("pageerror", (e) => failures.push(e.message));
  await page.goto(publication.shareUrl);
  await page.locator(".guide h2").filter({ hasText: "选择演示路线" }).waitFor();
  await page.screenshot({
    path: path.join(output, "hosted-desktop.png"),
    fullPage: true,
  });
  await page.locator(".action").nth(1).click();
  await page.locator(".guide h2").filter({ hasText: "换一条路线" }).waitFor();
  await page.locator(".action").first().click();
  await page.locator(".guide h2").filter({ hasText: "确认报名信息" }).waitFor();
  await page.getByRole("button", { name: "上一步", exact: true }).click();
  await page.locator(".guide h2").filter({ hasText: "换一条路线" }).waitFor();
  await page.getByRole("button", { name: "重新开始" }).click();
  await page.locator(".guide h2").filter({ hasText: "选择演示路线" }).waitFor();
  await page.setViewportSize({ width: 390, height: 844 });
  assert(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  );
  await page.screenshot({
    path: path.join(output, "hosted-mobile.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 320, height: 640 });
  assert(
    await page.evaluate(
      () =>
        document.documentElement.scrollWidth <= innerWidth &&
        document.documentElement.scrollHeight <= innerHeight,
    ),
  );
  const navigation = await page
    .getByRole("button", { name: "重新开始" })
    .boundingBox();
  assert(navigation && navigation.y + navigation.height <= 640);
  await page.screenshot({
    path: path.join(output, "hosted-mobile-320.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "放大画面" }).click();
  await page.screenshot({
    path: path.join(output, "hosted-mobile-320-enlarged.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "收起大图" }).click();
  await page.setViewportSize({ width: 390, height: 844 });

  await page.locator(".hotspot").first().click();
  await page.locator(".guide h2").filter({ hasText: "确认报名信息" }).waitFor();
  await page.locator(".action").first().click();
  await page.locator(".guide h2").filter({ hasText: "报名完成" }).waitFor();
  const revoke = await fetch(
    `${cfg.origin}/api/v1/publications/${publication.publicationId}/revoke`,
    { method: "POST", headers: { Authorization: `Bearer ${sessionToken}` } },
  );
  assert.equal(revoke.status, 200);
  await page.evaluate(() =>
    window.dispatchEvent(
      new PageTransitionEvent("pageshow", { persisted: true }),
    ),
  );
  await page
    .getByRole("heading", { name: "这份演示已被撤销", exact: true })
    .waitFor();
  assert.equal(await page.locator("[data-protected]").count(), 0);
  await page.screenshot({
    path: path.join(output, "hosted-revoked.png"),
    fullPage: true,
  });
  const deniedPage = await page.reload();
  assert.equal(deniedPage?.status(), 410);
  await page
    .getByRole("heading", { name: "这份演示已被撤销", exact: true })
    .waitFor();
  assert.deepEqual(failures, []);
  process.stdout.write(
    "PASS: actual PostgreSQL + upload/commit + browser branches/history/mobile + server revocation\n",
  );
} finally {
  await browser.close();
  await app.close();
  await db.end();
  await admin.query(`DROP SCHEMA ${schema} CASCADE`);
  await admin.end();
  await fs.rm(root, { recursive: true, force: true });
}
