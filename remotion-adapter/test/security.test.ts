import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { PNG } from "pngjs";
import { parseJson, canonical } from "../src/json";
import { validateContracts, sha256 } from "../src/contracts";
import { crc32, loadPackage, decodePng, safePath } from "../src/package";
const id = (n: number) =>
  `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`;
const png = (width: number, height: number, red = 20) => {
  const p = new PNG({ width, height });
  for (let i = 0; i < p.data.length; i += 4) {
    p.data[i] = red;
    p.data[i + 1] = 60;
    p.data[i + 2] = 90;
    p.data[i + 3] = 255;
  }
  return PNG.sync.write(p, { colorType: 6 });
};
function fixture() {
  const base = png(100, 200),
    crop = png(40, 50);
  const asset = (n: number, b: Buffer, w: number, h: number, role: string) => ({
    id: id(n),
    path: `assets/${id(n)}.png`,
    role,
    mime: "image/png",
    byteLength: b.length,
    sha256: sha256(b),
    width: w,
    height: h,
    durationMs: null,
  });
  const scene: any = {
    schemaVersion: 3,
    policyVersion: "scene-regions-3",
    compilerVersion: "tapscene-android-1",
    releaseId: id(1),
    title: "Safe fixture",
    goal: "Synthetic only",
    createdAt: 0,
    startStateId: id(2),
    states: [
      {
        id: id(2),
        imageAssetId: id(3),
        width: 100,
        height: 200,
        title: "Done",
        description: "Safe pixels",
        sourceKind: "authored",
        terminal: true,
      },
    ],
    edges: [],
    hotspots: [],
    regions: [
      {
        id: id(5),
        stateId: id(2),
        baseAssetId: id(3),
        assetId: id(4),
        name: "Visible region",
        kind: "screenshotCrop",
        coordinateSpace: "source-pixels",
        sourceWidth: 100,
        sourceHeight: 200,
        bbox: { x: 10, y: 20, width: 40, height: 50 },
        group: null,
        zIndex: 0,
        anchor: { coordinateSpace: "layer-normalized", x: 0.5, y: 0.5 },
      },
    ],
    assets: [
      asset(3, base, 100, 200, "state-image"),
      asset(4, crop, 40, 50, "region-crop"),
    ],
  };
  const plan: any = {
    schemaVersion: 1,
    adapterVersion: "tapscene-remotion-1",
    compositionId: "TapSceneDemo",
    releaseId: id(1),
    contentDigest: sha256(canonical(scene)),
    fps: 30,
    canvas: { width: 1080, height: 1920 },
    visits: [
      { visitId: id(6), stateId: id(2), selectedEdgeId: null, holdFrames: 30 },
    ],
    effects: [
      {
        type: "focus",
        visitId: id(6),
        startFrame: 0,
        durationFrames: 30,
        hotspotId: null,
        regionId: id(5),
        text: null,
        rect: null,
      },
    ],
    timeline: [
      {
        visitId: id(6),
        startFrame: 0,
        durationFrames: 30,
        transitionFrames: 0,
        overlapFrames: 0,
      },
    ],
    totalFrames: 30,
  };
  return {
    scene,
    plan,
    buffers: new Map([
      [scene.assets[0].path, base],
      [scene.assets[1].path, crop],
    ]) as Map<string, Buffer>,
  };
}
function files(f = fixture()) {
  f.plan.contentDigest = sha256(canonical(f.scene));
  const all = new Map(f.buffers);
  all.set("scene.json", Buffer.from(canonical(f.scene)));
  all.set("render-plan.json", Buffer.from(canonical(f.plan)));
  all.set("README.txt", Buffer.from("Synthetic data only."));
  all.set("schema.json", Buffer.from("{}"));
  const manifest = {
    schemaVersion: f.scene.schemaVersion,
    exportKind: "ai",
    releaseId: f.scene.releaseId,
    contentDigest: f.plan.contentDigest,
    files: [...all].map(([path, b]) => ({
      path,
      sha256: sha256(b),
      byteLength: b.length,
    })),
  };
  all.set("manifest.json", Buffer.from(canonical(manifest)));
  return all;
}
function zip(entries: Iterable<[string, Buffer]>, mode = 0) {
  const parts: Buffer[] = [],
    central: Buffer[] = [];
  let offset = 0,
    count = 0;
  for (const [name, b] of entries) {
    const n = Buffer.from(name),
      local = Buffer.alloc(30),
      c = Buffer.alloc(46);
    local.writeUInt32LE(0x04034b50);
    local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc32(b), 14);
    local.writeUInt32LE(b.length, 18);
    local.writeUInt32LE(b.length, 22);
    local.writeUInt16LE(n.length, 26);
    c.writeUInt32LE(0x02014b50);
    c.writeUInt16LE(20, 4);
    c.writeUInt16LE(20, 6);
    c.writeUInt32LE(crc32(b), 16);
    c.writeUInt32LE(b.length, 20);
    c.writeUInt32LE(b.length, 24);
    c.writeUInt16LE(n.length, 28);
    c.writeUInt32LE(mode >>> 0, 38);
    c.writeUInt32LE(offset, 42);
    parts.push(local, n, b);
    central.push(c, n);
    offset += local.length + n.length + b.length;
    count++;
  }
  const dir = Buffer.concat(central),
    end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(count, 8);
  end.writeUInt16LE(count, 10);
  end.writeUInt32LE(dir.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...parts, dir, end]);
}
async function withZip(data: Buffer, fn: (p: string) => Promise<void>) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "tapscene-test-"));
  try {
    const p = path.join(root, "input.tapscene-ai");
    await fs.writeFile(p, data);
    await fn(p);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
}
test("strict parser rejects duplicates, exponents, surrogates, unknown numeric precision", () => {
  for (const s of [
    '{"x":1,"x":2}',
    "1e2",
    '"\\ud800"',
    "0.1234567",
    "9007199254740992",
    "2.5",
    "[0,]",
  ])
    assert.throws(() => parseJson(Buffer.from(s), 1000));
  assert.equal(
    canonical(parseJson(Buffer.from('{"z":0,"a":0.5}'), 100)),
    '{"a":0.5,"z":0}',
  );
});
test("paths cannot address URLs, scripts, private sources or traversal", () => {
  for (const p of [
    "../scene.json",
    "/etc/passwd",
    "https://x/a.png",
    "assets/foo.svg",
    "assets/a.js",
    "raw/source.mp4",
    "assets/%2e%2e/a",
  ])
    assert.equal(safePath(p), false);
});
test("safe scene and independent plan validate", () => {
  const { scene, plan } = fixture();
  assert.equal(validateContracts(scene, plan).plan.totalFrames, 30);
  const digest = sha256(canonical(scene));
  plan.canvas = { width: 1920, height: 1080 };
  validateContracts(scene, plan);
  assert.equal(sha256(canonical(scene)), digest);
});
for (const [name, mutation] of Object.entries({
  unknown: (f: any): unknown => (f.plan.script = "alert(1)"),
  externalAsset: (f: any): unknown =>
    (f.scene.assets[0].path = "https://example.com/a.png"),
  unknownEffect: (f: any): unknown => (f.plan.effects[0].type = "eval"),
  frameBudget: (f: any): unknown => (f.plan.totalFrames = 18001),
  disconnected: (f: any): unknown => (f.plan.visits[0].stateId = id(99)),
  duplicateVisit: (f: any): unknown =>
    f.plan.visits.push({ ...f.plan.visits[0] }),
  timeline: (f: any): unknown => (f.plan.timeline[0].startFrame = 1),
  foreignRegion: (f: any): unknown => (f.plan.effects[0].regionId = id(99)),
  outsideCrop: (f: any): unknown => (f.scene.regions[0].bbox.x = 90),
  staleBase: (f: any): unknown => (f.scene.regions[0].baseAssetId = id(4)),
  missingEnd: (f: any): unknown => (f.scene.states[0].terminal = false),
  unreferenced: (f: any): unknown => (f.scene.regions = []),
})) {
  test(`rejects ${name}`, () => {
    const f = fixture();
    mutation(f);
    f.plan.contentDigest = sha256(canonical(f.scene));
    assert.throws(() => validateContracts(f.scene, f.plan));
  });
}
test("ZIP is validated and every real crop pixel is checked", async () =>
  withZip(zip(files()), async (p) => {
    const loaded = await loadPackage(p);
    assert.equal(loaded.scene.regions.length, 1);
    await loaded.cleanup();
  }));
test("changed crop with self-consistent hashes still fails pixel check", async () => {
  const f = fixture(),
    b = png(40, 50, 90);
  f.buffers.set(f.scene.assets[1].path, b);
  f.scene.assets[1].byteLength = b.length;
  f.scene.assets[1].sha256 = sha256(b);
  await withZip(zip(files(f)), async (p) => {
    await assert.rejects(loadPackage(p), /Crop pixels/);
  });
});
test("manifest corruption fails", async () => {
  const f = files();
  f.set("README.txt", Buffer.from("Changed file"));
  await withZip(zip(f), async (p) => {
    await assert.rejects(loadPackage(p), /integrity/);
  });
});
test("ZIP duplicate path fails", async () => {
  const f = files();
  await withZip(
    zip([...f, ["README.txt", Buffer.from("duplicate")]]),
    async (p) => {
      await assert.rejects(loadPackage(p), /duplicate/);
    },
  );
});
test("ZIP symlink fails", async () =>
  withZip(zip(files(), 0xa000 << 16), async (p) => {
    await assert.rejects(loadPackage(p), /links/);
  }));
test("ZIP script fails", async () => {
  const f = files();
  f.set("index.js", Buffer.from("throw 0"));
  await withZip(zip(f), async (p) => {
    await assert.rejects(loadPackage(p), /Unsafe/);
  });
});
test("ZIP local and central filenames must match", async () => {
  const data = zip(files());
  data[30] = 120;
  await withZip(data, async (p) => {
    await assert.rejects(loadPackage(p), /disagreement/);
  });
});
test("PNG opacity, CRC, and trailing bytes are checked", () => {
  const b = png(10, 10),
    a = { width: 10, height: 10 };
  decodePng(b, a);
  assert.throws(() => decodePng(Buffer.concat([b, Buffer.from([1])]), a));
  const bad = Buffer.from(b);
  bad[bad.length - 1] ^= 1;
  assert.throws(() => decodePng(bad, a));
  const transparent = PNG.sync.read(b);
  transparent.data[3] = 0;
  assert.throws(() => decodePng(PNG.sync.write(transparent), a), /opaque/);
});

test("crossfade chain cannot activate more than two visits", () => {
  const f = fixture(),
    state = f.scene.states[0];
  state.terminal = false;
  f.scene.states.push({ ...state, id: id(13), terminal: true });
  f.scene.hotspots = [
    {
      id: id(11),
      stateId: state.id,
      label: "Finish",
      coordinateSpace: "state-normalized",
      rect: { x: 0, y: 0, width: 1, height: 1 },
    },
  ];
  f.scene.edges = [
    {
      id: id(7),
      fromStateId: state.id,
      to: { stateId: state.id },
      hotspotId: null,
      label: "Again",
      trigger: "continue",
      transitionAssetId: null,
      sourceKind: "authored",
    },
    {
      id: id(9),
      fromStateId: state.id,
      to: { stateId: id(13) },
      hotspotId: id(11),
      label: "Finish",
      trigger: "tap",
      transitionAssetId: null,
      sourceKind: "authored",
    },
  ];
  f.plan.visits = [
    {
      visitId: id(6),
      stateId: state.id,
      selectedEdgeId: id(7),
      holdFrames: 30,
    },
    {
      visitId: id(8),
      stateId: state.id,
      selectedEdgeId: id(9),
      holdFrames: 30,
    },
    { visitId: id(10), stateId: id(13), selectedEdgeId: null, holdFrames: 30 },
  ];
  function setOverlap(overlap: number) {
    f.plan.effects = [id(6), id(8)].map((visitId) => ({
      type: "transition",
      visitId,
      startFrame: 30 - overlap,
      durationFrames: overlap,
      hotspotId: null,
      regionId: null,
      text: null,
      rect: null,
    }));
    f.plan.timeline = f.plan.visits.map((v: any, i: number) => ({
      visitId: v.visitId,
      startFrame: i * (30 - overlap),
      durationFrames: 30,
      transitionFrames: 0,
      overlapFrames: i < 2 ? overlap : 0,
    }));
    f.plan.totalFrames = 90 - 2 * overlap;
    f.plan.contentDigest = sha256(canonical(f.scene));
  }
  setOverlap(14);
  validateContracts(f.scene, f.plan);
  setOverlap(20);
  assert.throws(() => validateContracts(f.scene, f.plan), /independent hold/);
});
