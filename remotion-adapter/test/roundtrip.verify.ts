// This is run after Android exports its real package; no replacement fixture is generated here.
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { loadPackage } from "../src/package";
import { canonical } from "../src/json";

const directory = process.argv[2];
assert(directory, "Pass the executed Android round-trip evidence directory");
const incoming = await loadPackage(path.join(directory, "incoming.tapscene-ai"));
try {
  const output = await loadPackage(path.join(directory, "round-trip.tapscene-ai"));
  try {
    const expectedScene = JSON.parse(await fs.readFile(path.join(directory, "expected-scene.json"), "utf8"));
    const expectedPlan = JSON.parse(await fs.readFile(path.join(directory, "expected-plan.json"), "utf8"));
    assert.equal(canonical(output.scene), canonical(expectedScene));
    assert.equal(canonical(output.plan), canonical(expectedPlan));
    assert.notEqual(output.scene.releaseId, incoming.scene.releaseId);
    assert.notEqual(output.plan.contentDigest, incoming.plan.contentDigest);
    const { releaseId: inputRelease, contentDigest: inputDigest, ...inputPlan } = incoming.plan;
    const { releaseId: outputRelease, contentDigest: outputDigest, ...outputPlan } = output.plan;
    assert.deepEqual(outputPlan, inputPlan, "Canvas, visits, effects and timeline must survive exactly");
    assert.equal(output.scene.states.length, 3);
    assert.equal(output.scene.edges.length, 3);
    assert.equal(output.scene.regions.length, 1);
    assert.equal(output.scene.assets.length, 4, "Shared image is expanded into independent private assets");
    assert.equal(output.plan.visits.length, 3);
    assert.equal(output.plan.effects.length, 6);
    assert.equal(output.plan.visits.filter(v => v.stateId === output.scene.startStateId).length, 2);
    const unselected = output.scene.states.find(s => s.title === "Edited unselected branch");
    assert(unselected && !output.plan.visits.some(v => v.stateId === unselected.id));
    assert(output.scene.edges.some(e => "stateId" in e.to && e.to.stateId === unselected.id && e.label === "Alternate ending"));
    // Compare semantics independently of Android's expected JSON. Only asset IDs/provenance change.
    assert.equal(output.scene.title, incoming.scene.title);
    assert.equal(output.scene.goal, incoming.scene.goal);
    assert.equal(output.scene.startStateId, incoming.scene.startStateId);
    assert.deepEqual(output.scene.states.map(({ imageAssetId, sourceKind, ...state }) => state),
      incoming.scene.states.map(({ imageAssetId, sourceKind, ...state }) => state));
    assert(output.scene.states.every(s => s.sourceKind === "imported"));
    const byId = <T extends { id: string }>(values: T[]) => [...values].sort((a, b) => a.id.localeCompare(b.id));
    assert.deepEqual(byId(output.scene.edges), byId(incoming.scene.edges));
    assert.deepEqual(byId(output.scene.hotspots), byId(incoming.scene.hotspots));
    assert.deepEqual(output.scene.regions.map(({ baseAssetId, assetId, ...region }) => region),
      incoming.scene.regions.map(({ baseAssetId, assetId, ...region }) => region));
    for (const state of output.scene.states) {
      const old = incoming.scene.states.find(s => s.id === state.id)!;
      const actual = output.scene.assets.find(a => a.id === state.imageAssetId)!;
      const previous = incoming.scene.assets.find(a => a.id === old.imageAssetId)!;
      assert.notEqual(actual.id, previous.id);
      assert.equal(actual.sha256, previous.sha256);
      assert.equal(actual.byteLength, previous.byteLength);
    }
    console.log("TAPSCENE_AI_ROUNDTRIP_TS_OK: actual Android export read by trusted TS reader; complete graph, native PNGs, region pixels and exact rebound plan preserved");
  } finally { await output.cleanup(); }
} finally { await incoming.cleanup(); }
