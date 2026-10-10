import test from "node:test";
import assert from "node:assert/strict";
import {
  parseScene,
  validateScene,
  canonicalJson,
  parseJson,
} from "../src/index.js";
import { fixture, id } from "./fixtures.js";

test("schema 1 and 2 scene JSON round trips and preserves digest bytes", () => {
  for (const scene of [fixture(), fixture(true)]) {
    assert.deepEqual(parseScene(canonicalJson(scene)), scene);
    assert.equal(
      canonicalJson(parseScene(JSON.stringify(scene))),
      canonicalJson(scene),
    );
  }
  assert.equal(
    canonicalJson({ z: 0, a: 0.000001, b: -0 }),
    '{"a":0.000001,"b":0,"z":0}',
  );
});
test("strict JSON rejects duplicates, unsafe numbers, Unicode, depth and aggregate budgets", () => {
  for (const raw of [
    '{"x":1,"x":2}',
    '"\\ud800"',
    '"\ud800"',
    "1e2",
    "0.1234567",
    "9007199254740992",
    "2.5",
    "[0,]",
    "1 true",
    "[".repeat(17) + "0" + "]".repeat(17),
    JSON.stringify(Array.from({ length: 513 }, () => 0)),
  ])
    assert.throws(() => parseJson(raw, 512 * 1024), raw);
  assert.throws(() => parseScene(" ".repeat(512 * 1024 + 1)));
  assert.throws(() => canonicalJson({ bad: undefined }));
  assert.throws(() => canonicalJson({ bad: Number.NaN }));
  assert.throws(
    () =>
      canonicalJson({
        get script() {
          throw new Error("getter must never execute");
        },
      }),
    /enumerable data/,
  );
  assert.throws(() => canonicalJson(new Date()));
  assert.throws(() => canonicalJson([, 1]));
  const cycle: any = {};
  cycle.cycle = cycle;
  assert.throws(() => canonicalJson(cycle), /Cyclic/);
});
const mutations: Record<string, (s: any) => void> = {
  "unknown top field": (s) => {
    s.script = "run";
  },
  "unknown nested field": (s) => {
    s.states[0].rawPath = "/private";
  },
  "uppercase UUID": (s) => {
    s.releaseId = "AAAAAAAA-0000-0000-0000-000000000000";
  },
  "duplicate cross-type UUID": (s) => {
    s.edges[0].id = s.states[0].id;
  },
  "policy/schema mismatch": (s) => {
    s.policyVersion = "video-viewer-2";
  },
  "foreign source": (s) => {
    s.states[0].sourceKind = "guessed";
  },
  "missing asset": (s) => {
    s.states[0].imageAssetId = id(200);
  },
  "foreign URL": (s) => {
    s.assets[0].path = "https://evil.example/x.png";
  },
  "wrong path identity": (s) => {
    s.assets[0].path = `assets/${id(200)}.png`;
  },
  "wrong mime": (s) => {
    s.assets[0].mime = "image/jpeg";
  },
  "state image duration": (s) => {
    s.assets[0].durationMs = 1;
  },
  "state dimensions": (s) => {
    s.states[0].width = 101;
  },
  "terminal with edge": (s) => {
    s.states[0].terminal = true;
  },
  "missing destination": (s) => {
    s.edges[0].to = { stateId: id(200) };
  },
  "ambiguous destination": (s) => {
    s.edges[0].to.endLabel = "End";
  },
  "choice unsupported": (s) => {
    s.edges[0].trigger = "choice";
  },
  "continue unauthored": (s) => {
    s.edges[2].sourceKind = "recorded";
  },
  "continue hotspot": (s) => {
    s.edges[2].hotspotId = id(20);
  },
  "tap missing hotspot": (s) => {
    s.edges[0].hotspotId = null;
  },
  "tap foreign hotspot": (s) => {
    s.edges[0].hotspotId = id(21);
  },
  "two actions per hotspot": (s) => {
    s.edges[3].hotspotId = id(21);
  },
  "unreachable state": (s) => {
    s.edges[2].to = { endLabel: "Done" };
  },
  "out of bounds hotspot": (s) => {
    s.hotspots[0].rect.x = 0.700001;
  },
  "fraction too precise": (s) => {
    s.hotspots[0].rect.x = 0.1000001;
  },
  "unpaired surrogate object": (s) => {
    s.title = "\ud800";
  },
  "asset byte budget": (s) => {
    s.assets[0].byteLength = 50 * 1024 * 1024 + 1;
  },
  "missing start": (s) => {
    s.startStateId = id(200);
  },
  "region role before schema3": (s) => {
    s.assets[0].role = "region-crop";
  },
};
for (const [name, mutate] of Object.entries(mutations))
  test(`reject ${name}`, () => {
    const scene = fixture();
    mutate(scene);
    assert.throws(() => validateScene(scene));
  });
test("closed cycle and nonterminal dead end are rejected", () => {
  const scene = fixture();
  scene.states.pop();
  scene.edges.splice(2);
  scene.hotspots.pop();
  assert.throws(() => validateScene(scene), /ending/);
  scene.edges.pop();
  scene.hotspots.pop();
  assert.throws(() => validateScene(scene), /exit/);
});
test("schema3 regions bind unique real crop assets and geometry", () => {
  const scene = fixture();
  scene.schemaVersion = 3;
  scene.policyVersion = "scene-regions-3";
  scene.assets.push({
    ...scene.assets[0],
    id: id(102),
    path: `assets/${id(102)}.png`,
    role: "region-crop",
    width: 40,
    height: 50,
  });
  scene.regions.push({
    id: id(200),
    stateId: id(1),
    baseAssetId: id(100),
    assetId: id(102),
    name: "Crop",
    kind: "screenshotCrop",
    coordinateSpace: "source-pixels",
    sourceWidth: 100,
    sourceHeight: 200,
    bbox: { x: 10, y: 20, width: 40, height: 50 },
    group: null,
    zIndex: 0,
    anchor: { coordinateSpace: "layer-normalized", x: 0.5, y: 0.5 },
  });
  assert.equal(validateScene(scene).regions.length, 1);
  scene.regions.push({ ...scene.regions[0], id: id(201) });
  assert.throws(() => validateScene(scene), /Region bounds/);
  scene.regions.pop();
  scene.regions[0].bbox.x = 61;
  assert.throws(() => validateScene(scene), /Region bounds/);
});
test("shared transitions count once per bound edge in total duration", () => {
  const scene = fixture(true);
  scene.assets[1].durationMs = 10000;
  // Add seven self-loop tap actions across two source states, preserving the exit graph.
  for (let i = 0; i < 7; i++) {
    const stateId = id(i < 3 ? 1 : 2),
      hotspotId = id(300 + i);
    scene.hotspots.push({ ...scene.hotspots[0], id: hotspotId, stateId });
    scene.edges.push({
      ...scene.edges[0],
      id: id(400 + i),
      fromStateId: stateId,
      to: { stateId },
      hotspotId,
    });
  }
  assert.throws(() => validateScene(scene), /transition budget/);
});
