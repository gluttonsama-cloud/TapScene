/** Local-only browser evidence. Uses a deterministic same-origin fixture gateway,
 * not the hosted server; integrated PostgreSQL/API checks remain separate. */
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { readFile, mkdir, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { execFileSync } from "node:child_process";
import { chromium, type Page } from "playwright";
import { fixture, id } from "./fixture";

const output = resolve("playwright-results");
await mkdir(output, { recursive: true });
const browser = await chromium.launch({
  ...(process.env.CHROMIUM_PATH
    ? { executablePath: process.env.CHROMIUM_PATH }
    : {}),
  headless: true,
  args: ["--no-sandbox"],
});
const context = await browser.newContext({
  viewport: { width: 1440, height: 1050 },
  reducedMotion: "reduce",
});
// tsx preserves function names; evaluated test callbacks need its inert name helper.
await context.addInitScript("globalThis.__name = (fn) => fn");
const page = await context.newPage();
await page.evaluate("globalThis.__name = (fn) => fn");
const temp = await mkdtemp(join(tmpdir(), "tapscene-browser-"));
let video = false,
  status = 200,
  code = "",
  manifestRequests = 0,
  failImage = false,
  failVideo = false;
let holdImage: Promise<void> | null = null;
const base = "/s/browser_smoke_capability_1234567890";
const requests: { path: string; referer?: string }[] = [];
const pngs = new Map<string, Buffer>();

async function canvasImage(step: number): Promise<Buffer> {
  const encoded = await page.evaluate((step) => {
    const c = document.createElement("canvas");
    c.width = 360;
    c.height = 640;
    const g = c.getContext("2d")!;
    const rect = (
      x: number,
      y: number,
      w: number,
      h: number,
      color: string,
      r = 0,
    ) => {
      g.fillStyle = color;
      g.beginPath();
      g.roundRect(x, y, w, h, r);
      g.fill();
    };
    const text = (
      value: string,
      x: number,
      y: number,
      size = 14,
      color = "#243644",
      weight = 400,
    ) => {
      g.fillStyle = color;
      g.font = `${weight} ${size}px sans-serif`;
      g.fillText(value, x, y);
    };
    rect(0, 0, 360, 640, "#ffffff");
    text("9:41", 22, 26, 12, "#26384a", 700);
    text("•••  ▰", 292, 26, 12, "#26384a", 700);
    text("‹", 20, 68, 27);
    text("活动详情", 141, 65, 15, "#26384a", 700);
    text("···", 316, 64, 20);
    rect(0, 86, 360, 190, "#e6edff");
    rect(190, 97, 138, 157, "#ccdcff", 40);
    rect(241, 117, 101, 136, "#bad1f8", 36);
    rect(212, 142, 58, 95, "#8eaee3", 13);
    text("WEEKEND", 24, 128, 11, "#5476a8", 700);
    text("城市漫游计划", 24, 168, 28, "#26456c", 700);
    text("让灵感，在路上发生。", 25, 201, 12, "#657b9b");
    text("一起出发，发现城市另一面", 22, 313, 19, "#26384a", 700);
    rect(22, 330, 66, 23, "#eff4fc", 4);
    text("城市探索", 31, 346, 10, "#6d83a4");
    text("活动时间", 22, 390, 12, "#8895a0");
    text("10 月 24 日  ·  周六  14:00", 109, 390, 12, "#40576b");
    text("集合地点", 22, 421, 12, "#8895a0");
    text("城市艺术中心  ·  南门", 109, 421, 12, "#40576b");
    if (step === 1) {
      rect(36, 467, 288, 51, "#1d60de", 8);
      text("立即报名", 150, 499, 14, "#ffffff", 700);
      rect(36, 538, 288, 44, "#f4f7fb", 8);
      text("查看报名结果", 140, 566, 12, "#5d7390");
    } else if (step === 2) {
      rect(36, 467, 288, 51, "#eaf3ff", 8);
      text("请确认示例报名信息", 107, 499, 14, "#2869ce", 700);
      rect(36, 538, 288, 44, "#f4f7fb", 8);
      text("重新选择", 151, 566, 12, "#5d7390");
    } else {
      rect(36, 465, 288, 90, "#e9f5ef", 8);
      text("✓ 报名成功", 127, 507, 20, "#408362", 700);
      text("期待与你在城市中相遇", 113, 533, 12, "#729b84");
    }
    rect(130, 621, 100, 4, "#23364a", 3);
    return c.toDataURL("image/png").split(",")[1]!;
  }, step);
  return Buffer.from(encoded, "base64");
}
for (let i = 0; i < 3; i++) pngs.set(id(12 + i), await canvasImage(i + 1));
const videoPath = join(temp, "transition.mp4");
execFileSync("ffmpeg", [
  "-hide_banner",
  "-loglevel",
  "error",
  "-f",
  "lavfi",
  "-i",
  "color=c=0x1d60de:s=360x640:r=25",
  "-t",
  "1",
  "-an",
  "-c:v",
  "libx264",
  "-pix_fmt",
  "yuv420p",
  "-movflags",
  "+faststart",
  videoPath,
]);
const mp4 = await readFile(videoPath);
const server = createServer(async (req, res) => {
  const path = new URL(req.url!, "http://localhost").pathname;
  requests.push({ path, referer: req.headers.referer });
  res.setHeader("Cache-Control", "private, no-store");
  res.setHeader("Referrer-Policy", "no-referrer");
  if (path === `${base}/manifest`) {
    manifestRequests++;
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(
      JSON.stringify(status === 200 ? fixture(video) : { error: { code } }),
    );
    return;
  }
  if (path.startsWith(`${base}/assets/`)) {
    const asset = path.split("/").at(-1)!;
    if (asset === id(15)) {
      res.writeHead(failVideo ? 503 : 200, { "Content-Type": "video/mp4" });
      res.end(failVideo ? "unavailable" : mp4);
      return;
    }
    if (asset === id(13) && holdImage) await holdImage;
    if (asset === id(13) && failImage) {
      res.writeHead(503);
      res.end();
      return;
    }
    const png = pngs.get(asset);
    res.writeHead(png ? 200 : 404, { "Content-Type": "image/png" });
    res.end(png);
    return;
  }
  try {
    const file =
      path === base
        ? "index.html"
        : /^\/player-assets\/[A-Za-z0-9_.-]+$/.test(path)
          ? path.slice(1)
          : "missing";
    const content = await readFile(resolve("dist", file));
    res.writeHead(200, {
      "Content-Type": file.endsWith(".js")
        ? "text/javascript"
        : file.endsWith(".css")
          ? "text/css"
          : "text/html",
    });
    res.end(content);
  } catch {
    res.writeHead(404);
    res.end();
  }
});
await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
const address = server.address();
assert(address && typeof address === "object");
const url = `http://127.0.0.1:${address.port}${base}`;
const errors: string[] = [];
const requestUrls: string[] = [];
page.on("request", (request) => requestUrls.push(request.url()));
page.on("pageerror", (error) => errors.push(error.message));
const stepTitle = (value: string) =>
  page.locator(".guide h2").filter({ hasText: value });
async function visibleTitle(value: string) {
  await stepTitle(value).waitFor({ state: "visible" });
}
async function fresh() {
  status = 200;
  code = "";
  failImage = false;
  holdImage = null;
  await page.goto(url);
  await visibleTitle("选择你的参加方式");
}
async function visibility(target: Page, hidden: boolean) {
  await target.evaluate((hidden) => {
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      get: () => (hidden ? "hidden" : "visible"),
    });
    document.dispatchEvent(new Event("visibilitychange"));
  }, hidden);
}
try {
  await fresh();
  assert.equal(await page.locator(".hotspot").count(), 2);
  assert.equal(
    await page
      .getByRole("button", { name: "上一步", exact: true })
      .isDisabled(),
    true,
  );
  await page.screenshot({
    path: join(output, "viewer-desktop.png"),
    fullPage: true,
  });
  await page.locator(".action").first().focus();
  await page.keyboard.press("Enter");
  await visibleTitle("确认报名信息");
  await page.getByRole("button", { name: "上一步", exact: true }).click();
  await visibleTitle("选择你的参加方式");
  await page.locator(".action").nth(1).click();
  await visibleTitle("报名成功");
  await page.getByRole("button", { name: "重新开始" }).click();
  await visibleTitle("选择你的参加方式");
  console.log(
    "PASS: keyboard actions, actual branch history, terminal and restart",
  );

  let release!: () => void;
  holdImage = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.locator(".action").first().click();
  await page.locator(".image-loading").waitFor();
  assert.equal(await page.locator(".action").first().isDisabled(), true);
  assert.equal(
    await page.locator(".guide h2").textContent(),
    "选择你的参加方式",
  );
  await page.getByRole("button", { name: "取消切换" }).click();
  release();
  holdImage = null;
  await page.waitForTimeout(100);
  assert.equal(
    await page.locator(".guide h2").textContent(),
    "选择你的参加方式",
  );
  failImage = true;
  await page.locator(".action").first().click();
  await page.getByText("目标画面暂时无法加载").waitFor();
  assert.equal(
    await page.locator(".guide h2").textContent(),
    "选择你的参加方式",
  );
  failImage = false;
  await page.getByRole("button", { name: "重试画面" }).click();
  await visibleTitle("确认报名信息");
  console.log(
    "PASS: pending target remains uncommitted, Back aborts, failure retries",
  );

  for (const [width, height] of [
    [390, 844],
    [320, 640],
  ] as const) {
    await page.setViewportSize({ width, height });
    await fresh();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
      true,
    );
    const bounds = await page.locator(".navigation").boundingBox();
    assert(
      bounds && bounds.y >= 0 && bounds.y + bounds.height <= height,
      "Back/restart must be in the initial mobile viewport",
    );
    const actionBounds = await page.locator(".actions").boundingBox();
    assert(
      actionBounds && actionBounds.y + actionBounds.height <= height,
      "Current actions must be reachable without scrolling past the image",
    );
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollHeight <= window.innerHeight + 1,
      ),
      true,
    );
    await page.screenshot({
      path: join(output, `viewer-mobile-${width}.png`),
      fullPage: true,
    });
    await page.locator(".mobile-details summary").click();
    const explanationNav = await page.locator(".navigation").boundingBox();
    assert(
      explanationNav &&
        explanationNav.y >= 0 &&
        explanationNav.y + explanationNav.height <= height,
      "Navigation stays reachable with expanded explanations",
    );
    await page.locator(".mobile-details summary").click();
    const fitted = await page.locator(".scene-frame").boundingBox();
    await page.getByRole("button", { name: "放大画面", exact: true }).click();
    const enlarged = await page.locator(".scene-frame").boundingBox();
    assert(
      fitted && enlarged && enlarged.height > fitted.height,
      "Explicit enlarge must expose larger pixels",
    );
    await page.evaluate(() => window.scrollTo(0, 200));
    const fixed = await page.locator(".navigation").boundingBox();
    assert(
      fixed && fixed.y >= 0 && fixed.y + fixed.height <= height,
      "Navigation stays reachable while scrolling enlarged pixels",
    );
    await page.screenshot({
      path: join(output, `viewer-mobile-${width}-enlarged.png`),
      fullPage: false,
    });
    await page.getByRole("button", { name: "收起大图", exact: true }).click();
    await page.locator(".action").first().click();
    await visibleTitle("确认报名信息");
    await page.getByRole("button", { name: "上一步", exact: true }).click();
    await visibleTitle("选择你的参加方式");
    await page.locator(".hotspot").first().click();
    await visibleTitle("确认报名信息");
    await page.getByRole("button", { name: "重新开始", exact: true }).click();
    await visibleTitle("选择你的参加方式");
  }
  await page.setViewportSize({ width: 390, height: 844 });
  console.log(
    "PASS: 390/320 viewport-fit actions and navigation, enlarged scrolling, text and scaled hotspot actions",
  );

  video = true;
  failVideo = true;
  await fresh();
  await page.locator(".action").first().click();
  await page.getByText("过渡视频暂时无法播放").waitFor();
  assert.equal(
    await page.locator(".guide h2").textContent(),
    "选择你的参加方式",
  );
  await page.getByRole("button", { name: "跳过过渡" }).click();
  await visibleTitle("确认报名信息");
  failVideo = false;
  await fresh();
  await page.locator(".action").first().click();
  const h264 = await page.evaluate(() =>
    document
      .createElement("video")
      .canPlayType('video/mp4; codecs="avc1.42E01E"'),
  );
  if (h264) {
    await visibleTitle("确认报名信息");
    console.log("PASS: native H.264 MP4 EOS");
  } else {
    await page.getByText("过渡视频暂时无法播放").waitFor();
    const retried = page.waitForResponse((r) =>
      r.url().endsWith(`/assets/${id(15)}`),
    );
    await page.getByRole("button", { name: "重试视频" }).click();
    await retried;
    await page.getByText("过渡视频暂时无法播放").waitFor();
    await page.screenshot({
      path: join(output, "viewer-video-unavailable.png"),
      fullPage: true,
    });
    await page.getByRole("button", { name: "跳过过渡" }).click();
    await visibleTitle("确认报名信息");
    console.log(
      "NOT_RUN: native H.264 EOS; this Chromium Headless Shell reports no H.264 codec",
    );
  }
  console.log("PASS: native video failure, retry and explicit skip fallback");
  video = false;

  for (const [nextStatus, nextCode, copy] of [
    [410, "SHARE_REVOKED", "这份演示已被撤销"],
    [410, "SHARE_EXPIRED", "这份演示已到期"],
    [404, "NOT_FOUND", "未找到这份演示"],
    [503, "SERVICE_UNAVAILABLE", "暂时无法验证访问权限"],
  ] as const) {
    await fresh();
    const previous = manifestRequests;
    await visibility(page, true);
    assert.equal(await page.locator(".screen-image img").count(), 0);
    status = nextStatus;
    code = nextCode;
    await visibility(page, false);
    await page.getByRole("heading", { name: copy, exact: true }).waitFor();
    assert(manifestRequests > previous);
    assert.equal(await page.locator("[data-protected]").count(), 0);
    assert.equal(
      await page.getByText("把报名流程，讲清楚", { exact: true }).count(),
      0,
    );
  }
  await fresh();
  status = 410;
  code = "SHARE_REVOKED";
  await page.evaluate(() =>
    window.dispatchEvent(
      new PageTransitionEvent("pageshow", { persisted: true }),
    ),
  );
  await page
    .getByRole("heading", { name: "这份演示已被撤销", exact: true })
    .waitFor();
  await page.screenshot({
    path: join(output, "viewer-revoked.png"),
    fullPage: true,
  });
  console.log(
    "PASS: visibility and bfcache reauthorization fail closed on 404/410/503",
  );

  assert.deepEqual(
    await page.evaluate(() => ({
      local: localStorage.length,
      session: sessionStorage.length,
    })),
    { local: 0, session: 0 },
  );
  assert.equal(
    requests.filter((r) => r.path.startsWith(`${base}/`) && r.referer).length,
    0,
  );
  assert(
    requestUrls.every(
      (request) => new URL(request).origin === new URL(url).origin,
    ),
  );
  assert.equal(
    await page.evaluate(
      async () => (await navigator.serviceWorker.getRegistrations()).length,
    ),
    0,
  );
  assert.deepEqual(errors, []);
  console.log(
    "PASS: no capability persistence, no media Referer, no browser exceptions",
  );
  console.log(`Screenshots: ${output}`);
} finally {
  await browser.close();
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
  await rm(temp, { recursive: true, force: true });
}
