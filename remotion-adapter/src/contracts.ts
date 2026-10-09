import { z } from "zod";
import { createHash } from "node:crypto";
import { assert, canonical } from "./json";
const uuid = z
  .string()
  .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
const hash = z.string().regex(/^[0-9a-f]{64}$/);
const integer = z.number().int().safe();
const text = (n: number, nonempty = false) =>
  z
    .string()
    .max(n)
    .refine((s) => !nonempty || s.trim().length > 0);
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
      r.x + r.width <= 1.000000001 &&
      r.y + r.height <= 1.000000001,
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
const visit = z.strictObject({
  visitId: uuid,
  stateId: uuid,
  selectedEdgeId: uuid.nullable(),
  holdFrames: integer.min(1).max(1800),
});
const effect = z.strictObject({
  type: z.enum(["click", "focus", "transition", "highlight", "annotation"]),
  visitId: uuid,
  startFrame: integer.min(0),
  durationFrames: integer.min(1).max(1800),
  hotspotId: uuid.nullable(),
  regionId: uuid.nullable(),
  text: text(240).nullable(),
  rect: rect.nullable(),
});
const timeline = z.strictObject({
  visitId: uuid,
  startFrame: integer.min(0),
  durationFrames: integer.min(1),
  transitionFrames: integer.min(0),
  overlapFrames: integer.min(0),
});
export const planSchema = z.strictObject({
  schemaVersion: z.literal(1),
  adapterVersion: z.literal("tapscene-remotion-1"),
  compositionId: z.literal("TapSceneDemo"),
  releaseId: uuid,
  contentDigest: hash,
  fps: z.literal(30),
  canvas: z.union([
    z.strictObject({ width: z.literal(1080), height: z.literal(1920) }),
    z.strictObject({ width: z.literal(1920), height: z.literal(1080) }),
  ]),
  visits: z.array(visit).min(1).max(256),
  effects: z.array(effect).max(256),
  timeline: z.array(timeline).min(1).max(256),
  totalFrames: integer.min(1).max(18000),
});
export const manifestSchema = z.strictObject({
  schemaVersion: integer.min(1).max(3),
  exportKind: z.literal("ai"),
  releaseId: uuid,
  contentDigest: hash,
  files: z
    .array(
      z.strictObject({
        path: z.string(),
        byteLength: integer.min(1).max(50 * 1024 * 1024),
        sha256: hash,
      }),
    )
    .min(4)
    .max(204),
});
export type Scene = z.infer<typeof sceneSchema>;
export type Plan = z.infer<typeof planSchema>;
export type Asset = Scene["assets"][number];
export const sha256 = (bytes: Uint8Array | string) =>
  createHash("sha256").update(bytes).digest("hex");
export function validateContracts(sceneInput: unknown, planInput: unknown) {
  const scene = sceneSchema.parse(sceneInput),
    plan = planSchema.parse(planInput);
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
    edges = new Map(scene.edges.map((e) => [e.id, e])),
    hotspots = new Map(scene.hotspots.map((h) => [h.id, h])),
    regions = new Map(scene.regions.map((r) => [r.id, r]));
  const used = new Set<string>(),
    usedHotspots = new Set<string>(),
    continues = new Set<string>();
  let bytes = 0,
    transitionMs = 0;
  assert(states.has(scene.startStateId), "Missing start");
  for (const a of scene.assets) {
    assert(Math.min(a.width, a.height) <= 1080, "Asset dimension limit");
    const video = a.role === "transition";
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
  assert(
    plan.releaseId === scene.releaseId &&
      plan.contentDigest === sha256(canonical(scene)),
    "Plan release binding mismatch",
  );
  assert(
    plan.visits[0].stateId === scene.startStateId &&
      plan.timeline.length === plan.visits.length,
    "Invalid plan start/timeline",
  );
  const visits = new Map(plan.visits.map((v) => [v.visitId, v]));
  assert(visits.size === plan.visits.length, "Duplicate visit");
  const overlaps = new Map<string, number>();
  for (const e of plan.effects) {
    const v = visits.get(e.visitId);
    assert(
      v && e.startFrame + e.durationFrames <= v.holdFrames,
      "Effect exceeds hold",
    );
    if (e.type === "click") {
      const edge = edges.get(v.selectedEdgeId!);
      assert(
        e.hotspotId !== null &&
          edge?.hotspotId === e.hotspotId &&
          e.regionId === null &&
          e.text === null &&
          e.rect === null,
        "Invalid click effect",
      );
    } else if (e.type === "focus" || e.type === "highlight") {
      assert(
        e.hotspotId === null &&
          e.text === null &&
          (e.regionId !== null) !== (e.rect !== null),
        "Invalid region/rect effect",
      );
      if (e.regionId)
        assert(
          regions.get(e.regionId)?.stateId === v.stateId,
          "Foreign region",
        );
    } else if (e.type === "annotation") {
      assert(
        e.hotspotId === null &&
          e.regionId === null &&
          e.text !== null &&
          e.text.trim().length > 0 &&
          e.rect !== null,
        "Invalid annotation",
      );
    } else {
      const edge = edges.get(v.selectedEdgeId!),
        index = plan.visits.indexOf(v);
      assert(
        edge &&
          "stateId" in edge.to &&
          edge.transitionAssetId === null &&
          index < plan.visits.length - 1 &&
          !overlaps.has(v.visitId) &&
          e.startFrame === v.holdFrames - e.durationFrames &&
          e.durationFrames < v.holdFrames &&
          e.durationFrames < plan.visits[index + 1].holdFrames &&
          e.hotspotId === null &&
          e.regionId === null &&
          e.text === null &&
          e.rect === null,
        "Invalid crossfade",
      );
      overlaps.set(v.visitId, e.durationFrames);
    }
  }
  let start = 0;
  plan.visits.forEach((v, i) => {
    const s = states.get(v.stateId);
    assert(s, "Missing visit state");
    const edge =
      v.selectedEdgeId === null ? undefined : edges.get(v.selectedEdgeId);
    const last = i === plan.visits.length - 1;
    if (edge) {
      assert(edge.fromStateId === s.id, "Disconnected path");
      if ("stateId" in edge.to)
        assert(
          !last && plan.visits[i + 1].stateId === edge.to.stateId,
          "Missing next visit",
        );
      else assert(last, "Ending before final visit");
    } else
      assert(
        v.selectedEdgeId === null && last && s.terminal,
        "Path must explicitly finish",
      );
    const video = edge?.transitionAssetId
      ? assets.get(edge.transitionAssetId)
      : undefined;
    const frames = video
      ? Math.floor((video.durationMs! * 30) / 1000 + 0.5)
      : 0;
    const overlap = overlaps.get(v.visitId) ?? 0;
    const incoming =
      i === 0 ? 0 : (overlaps.get(plan.visits[i - 1].visitId) ?? 0);
    assert(
      incoming + overlap < v.holdFrames,
      "Crossfades must preserve an independent hold",
    );
    const t = plan.timeline[i];
    assert(
      t.visitId === v.visitId &&
        t.startFrame === start &&
        t.transitionFrames === frames &&
        t.overlapFrames === overlap &&
        t.durationFrames === v.holdFrames + frames,
      "Derived timeline mismatch",
    );
    start += t.durationFrames - overlap;
  });
  assert(start === plan.totalFrames, "Total frame mismatch");
  return { scene, plan };
}
