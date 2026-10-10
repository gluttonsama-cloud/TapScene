import { z } from "zod";
import { assert, canonicalJson, parseJson } from "./json.js";
const uuid = z
  .string()
  .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
const hash = z.string().regex(/^[0-9a-f]{64}$/);
const integer = z.number().int().safe();
const text = (n: number, nonempty = false) =>
  z
    .string()
    .max(n)
    .refine(
      (s) =>
        !nonempty || s.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, "").length > 0,
    );
const normalized = z
  .number()
  .min(0)
  .max(1)
  .refine((n) => Math.abs(Math.round(n * 1e6) - n * 1e6) < 1e-7);
const rect = z
  .strictObject({
    x: normalized,
    y: normalized,
    width: normalized,
    height: normalized,
  })
  .refine(
    (r) =>
      r.width > 0 &&
      r.height > 0 &&
      Math.round(r.x * 1e6) + Math.round(r.width * 1e6) <= 1e6 &&
      Math.round(r.y * 1e6) + Math.round(r.height * 1e6) <= 1e6,
  );
const dimensions = integer.min(1).max(2400);
const source = z.enum(["recorded", "authored", "imported"]);
const state = z.strictObject({
  id: uuid,
  imageAssetId: uuid,
  width: dimensions,
  height: dimensions,
  title: text(240, true),
  description: text(8192),
  sourceKind: source,
  terminal: z.boolean(),
});
const edge = z.strictObject({
  id: uuid,
  fromStateId: uuid,
  to: z.union([
    z.strictObject({ stateId: uuid }),
    z.strictObject({ endLabel: text(240, true) }),
  ]),
  hotspotId: uuid.nullable(),
  label: text(240, true),
  trigger: z.enum(["tap", "continue"]),
  transitionAssetId: uuid.nullable(),
  sourceKind: source,
});
const hotspot = z.strictObject({
  id: uuid,
  stateId: uuid,
  label: text(240, true),
  coordinateSpace: z.literal("state-normalized"),
  rect,
});
const asset = z.strictObject({
  id: uuid,
  path: z.string(),
  role: z.enum(["state-image", "region-crop", "transition"]),
  mime: z.enum(["image/png", "video/mp4"]),
  byteLength: integer.min(1).max(50 * 1024 * 1024),
  sha256: hash,
  width: dimensions,
  height: dimensions,
  durationMs: integer.min(1).max(10000).nullable(),
});
const region = z.strictObject({
  id: uuid,
  stateId: uuid,
  baseAssetId: uuid,
  assetId: uuid,
  name: text(240, true),
  kind: z.literal("screenshotCrop"),
  coordinateSpace: z.literal("source-pixels"),
  sourceWidth: dimensions,
  sourceHeight: dimensions,
  bbox: z.strictObject({
    x: integer.min(0),
    y: integer.min(0),
    width: dimensions,
    height: dimensions,
  }),
  group: text(120, true).nullable(),
  zIndex: integer.min(-10000).max(10000),
  anchor: z.strictObject({
    coordinateSpace: z.literal("layer-normalized"),
    x: normalized,
    y: normalized,
  }),
});
export const sceneSchema = z.strictObject({
  schemaVersion: z.union([z.literal(1), z.literal(2), z.literal(3)]),
  policyVersion: z.enum([
    "static-viewer-1",
    "video-viewer-2",
    "scene-regions-3",
  ]),
  compilerVersion: z.literal("tapscene-android-1"),
  releaseId: uuid,
  title: text(240, true),
  goal: text(8192),
  createdAt: integer.min(0),
  startStateId: uuid,
  states: z.array(state).min(1).max(40),
  edges: z.array(edge).max(80),
  hotspots: z.array(hotspot).max(240),
  regions: z.array(region).max(80),
  assets: z.array(asset).min(1).max(200),
});

export type Scene = z.infer<typeof sceneSchema>;
export type Asset = Scene["assets"][number];
export type SceneState = Scene["states"][number];
export type Edge = Scene["edges"][number];
export type Hotspot = Scene["hotspots"][number];
export type Region = Scene["regions"][number];

/** Parse the same bounded, duplicate-key rejecting JSON profile as Android. */
export function parseScene(raw: string): Scene {
  return validateScene(parseJson(raw, 512 * 1024));
}

/** Validate untrusted JSON data without dropping unknown fields or weakening graph rules. */
export function validateScene(value: unknown): Scene {
  // Also enforce Unicode, numeric and aggregate parser limits for object callers.
  canonicalJson(value);
  const scene = sceneSchema.parse(value);
  const ids = new Set<string>();
  for (const row of [
    ...scene.states,
    ...scene.assets,
    ...scene.edges,
    ...scene.hotspots,
    ...scene.regions,
  ]) {
    assert(!ids.has(row.id), "Duplicate scene identity");
    ids.add(row.id);
  }
  assert(
    scene.policyVersion ===
      ["", "static-viewer-1", "video-viewer-2", "scene-regions-3"][
        scene.schemaVersion
      ],
    "Scene version mismatch",
  );
  assert(
    scene.schemaVersion === 3 || scene.regions.length === 0,
    "Regions require schema 3",
  );
  assert(
    scene.schemaVersion !== 1 || scene.assets.length <= 40,
    "Static asset limit",
  );
  const assets = new Map(scene.assets.map((a) => [a.id, a])),
    states = new Map(scene.states.map((s) => [s.id, s])),
    hotspots = new Map(scene.hotspots.map((h) => [h.id, h]));
  const used = new Set<string>(),
    cropAssets = new Set<string>(),
    usedHotspots = new Set<string>(),
    continues = new Set<string>();
  let bytes = 0,
    transitionMs = 0;
  assert(states.has(scene.startStateId), "Missing start");
  for (const a of scene.assets) {
    assert(Math.min(a.width, a.height) <= 1080, "Asset dimension limit");
    const video = a.role === "transition";
    assert(
      a.role !== "region-crop" || scene.schemaVersion === 3,
      "Region crops require schema 3",
    );
    assert(
      a.path === `assets/${a.id}.${video ? "mp4" : "png"}` &&
        a.mime === (video ? "video/mp4" : "image/png"),
      "Invalid asset path or type",
    );
    assert(
      video
        ? scene.schemaVersion >= 2 && a.durationMs !== null
        : a.durationMs === null,
      "Invalid asset duration",
    );
    bytes += a.byteLength;
  }
  assert(bytes <= 50 * 1024 * 1024, "Asset byte limit");
  for (const s of scene.states) {
    const a = assets.get(s.imageAssetId);
    assert(
      a?.role === "state-image" && a.width === s.width && a.height === s.height,
      "State image mismatch",
    );
    used.add(a.id);
  }
  const hotspotCounts = new Map<string, number>(),
    regionCounts = new Map<string, number>();
  for (const h of scene.hotspots) {
    assert(states.has(h.stateId), "Missing hotspot state");
    const n = (hotspotCounts.get(h.stateId) ?? 0) + 1;
    assert(n <= 6, "Hotspot limit");
    hotspotCounts.set(h.stateId, n);
  }
  for (const r of scene.regions) {
    const s = states.get(r.stateId),
      a = assets.get(r.assetId);
    assert(
      s &&
        r.baseAssetId === s.imageAssetId &&
        r.sourceWidth === s.width &&
        r.sourceHeight === s.height,
      "Region base mismatch",
    );
    assert(
      a?.role === "region-crop" &&
        !cropAssets.has(a.id) &&
        a.width === r.bbox.width &&
        a.height === r.bbox.height &&
        r.bbox.x + r.bbox.width <= s.width &&
        r.bbox.y + r.bbox.height <= s.height,
      "Region bounds mismatch",
    );
    const n = (regionCounts.get(r.stateId) ?? 0) + 1;
    assert(n <= 12, "Region limit");
    regionCounts.set(r.stateId, n);
    used.add(a.id);
    cropAssets.add(a.id);
  }
  const forward = new Map<string, string[]>(),
    reverse = new Map<string, string[]>(),
    ending = new Set(scene.states.filter((s) => s.terminal).map((s) => s.id));
  for (const e of scene.edges) {
    assert(
      states.has(e.fromStateId) && !states.get(e.fromStateId)!.terminal,
      "Invalid edge source",
    );
    if ("stateId" in e.to) {
      assert(states.has(e.to.stateId), "Missing edge target");
      forward.set(e.fromStateId, [
        ...(forward.get(e.fromStateId) ?? []),
        e.to.stateId,
      ]);
      reverse.set(e.to.stateId, [
        ...(reverse.get(e.to.stateId) ?? []),
        e.fromStateId,
      ]);
    } else ending.add(e.fromStateId);
    if (e.trigger === "tap") {
      const h = hotspots.get(e.hotspotId!);
      assert(
        h?.stateId === e.fromStateId && !usedHotspots.has(h.id),
        "Invalid tap hotspot",
      );
      usedHotspots.add(h.id);
    } else {
      assert(
        e.hotspotId === null &&
          e.sourceKind === "authored" &&
          !continues.has(e.fromStateId),
        "Invalid continue",
      );
      continues.add(e.fromStateId);
    }
    if (e.transitionAssetId) {
      const a = assets.get(e.transitionAssetId);
      assert(a?.role === "transition", "Missing video");
      used.add(a.id);
      transitionMs += a.durationMs!;
    }
  }
  assert(
    used.size === assets.size &&
      usedHotspots.size === hotspots.size &&
      transitionMs <= 60000,
    "Unreferenced assets/hotspots or transition budget",
  );
  for (const s of scene.states)
    assert(
      s.terminal || scene.edges.some((e) => e.fromStateId === s.id),
      "Nonterminal without exit",
    );
  function reachable(initial: Set<string>, map: Map<string, string[]>) {
    const out = new Set(initial),
      queue = [...initial];
    while (queue.length)
      for (const id of map.get(queue.shift()!) ?? [])
        if (!out.has(id)) {
          out.add(id);
          queue.push(id);
        }
    return out;
  }
  assert(
    reachable(new Set([scene.startStateId]), forward).size === states.size &&
      reachable(ending, reverse).size === states.size,
    "Graph unreachable or without ending",
  );
  return scene;
}
