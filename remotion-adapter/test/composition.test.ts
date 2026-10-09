import test from "node:test";
import assert from "node:assert/strict";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { Internals } from "remotion";
import { TapSceneDemo } from "../src/Composition";
import type { Scene, Plan } from "../src/contracts";

function fixture() {
  const scene: Scene = {
    schemaVersion: 3,
    policyVersion: "scene-regions-3",
    compilerVersion: "tapscene-android-1",
    releaseId: "release",
    title: "Example",
    goal: "",
    createdAt: 0,
    startStateId: "state",
    states: [
      {
        id: "state",
        imageAssetId: "base",
        width: 100,
        height: 200,
        title: "Example",
        description: "",
        sourceKind: "authored",
        terminal: true,
      },
    ],
    edges: [],
    hotspots: [],
    regions: [
      {
        id: "region",
        stateId: "state",
        baseAssetId: "base",
        assetId: "crop",
        name: "Visible crop",
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
      {
        id: "base",
        path: "assets/base.png",
        role: "state-image",
        mime: "image/png",
        width: 100,
        height: 200,
        byteLength: 1,
        sha256: "hash",
        durationMs: null,
      },
      {
        id: "crop",
        path: "assets/crop.png",
        role: "region-crop",
        mime: "image/png",
        width: 40,
        height: 50,
        byteLength: 1,
        sha256: "hash",
        durationMs: null,
      },
    ],
  };
  const plan: Plan = {
    schemaVersion: 1,
    adapterVersion: "tapscene-remotion-1",
    compositionId: "TapSceneDemo",
    releaseId: "release",
    contentDigest: "hash",
    fps: 30,
    canvas: { width: 1080, height: 1920 },
    visits: [
      {
        visitId: "visit",
        stateId: "state",
        selectedEdgeId: null,
        holdFrames: 90,
      },
    ],
    effects: [
      {
        type: "annotation",
        visitId: "visit",
        startFrame: 0,
        durationFrames: 90,
        hotspotId: null,
        regionId: null,
        text: "<script>first & literal</script>",
        rect: { x: 0.1, y: 0.1, width: 0.4, height: 0.1 },
      },
      {
        type: "annotation",
        visitId: "visit",
        startFrame: 0,
        durationFrames: 90,
        hotspotId: null,
        regionId: null,
        text: "Second annotation",
        rect: { x: 0.2, y: 0.5, width: 0.4, height: 0.1 },
      },
      {
        type: "focus",
        visitId: "visit",
        startFrame: 0,
        durationFrames: 90,
        hotspotId: null,
        regionId: "region",
        text: null,
        rect: null,
      },
    ],
    timeline: [
      {
        visitId: "visit",
        startFrame: 0,
        durationFrames: 90,
        transitionFrames: 0,
        overlapFrames: 0,
      },
    ],
    totalFrames: 90,
  };
  return { scene, plan };
}

function markup(scene: Scene, plan: Plan, frame = 0) {
  // Static React structure only. Actual Chromium layout/video remains the CI render's job.
  return renderToStaticMarkup(
    React.createElement(
      Internals.CanUseRemotionHooks.Provider,
      { value: true },
      React.createElement(
        Internals.TimelineContext.Provider,
        {
          value: {
            frame: { "asset:test": frame },
            isPlaying: () => false,
            isInsideFreeze: false,
            audioAndVideoTags: { current: [] },
          },
        },
        React.createElement(
          Internals.CompositionManager.Provider,
          {
            value: {
              compositions: [],
              folders: [],
              currentCompositionMetadata: null,
              canvasContent: { type: "asset", asset: "test" },
              currentAssetMetadata: {
                asset: "test",
                src: "",
                props: {},
                durationInFrames: plan.totalFrames,
                fps: 30,
                width: 1080,
                height: 1920,
                defaultCodec: null,
                defaultOutName: null,
                defaultVideoImageFormat: null,
                defaultPixelFormat: null,
                defaultProResProfile: null,
                defaultSampleRate: null,
              },
            },
          },
          React.createElement(TapSceneDemo, { scene, plan }),
        ),
      ),
    ),
  );
}

test("each annotation renders literal text inside its own normalized rectangle", () => {
  const { scene, plan } = fixture();
  const html = markup(scene, plan);
  const annotations = [
    ...html.matchAll(/<div data-tapscene-annotation="true"[^>]*>.*?<\/div>/g),
  ].map((m) => m[0]);
  assert.equal(annotations.length, 2);
  assert.match(annotations[0], /left:58px;top:116px;width:232px;height:116px/);
  assert.match(
    annotations[0],
    /&lt;script&gt;first &amp; literal&lt;\/script&gt;/,
  );
  assert.match(annotations[1], /left:116px;top:580px;width:232px;height:116px/);
  assert.match(annotations[1], /Second annotation/);
  assert.equal(html.includes("<script>"), false);
  // Remotion assigns image src in an effect, so SSR checks the preserved layer structure only.
  assert.equal((html.match(/<img /g) ?? []).length, 2);
  assert.match(html, /Visible crop/);
  assert.match(html, /transform-origin:50% 50%/);
  assert.match(html, /font-size:55px;[^>]*-webkit-line-clamp:2/);
});

test("crossfades preserve same-color luminance and unmount each old visit", () => {
  const { scene, plan } = fixture();
  scene.states[0].terminal = false;
  scene.states.push(
    { ...scene.states[0], id: "middle" },
    { ...scene.states[0], id: "end", terminal: true },
  );
  scene.edges = [
    {
      id: "first-edge",
      fromStateId: "state",
      to: { stateId: "middle" },
      label: "Next",
      trigger: "continue",
      sourceKind: "authored",
      hotspotId: null,
      transitionAssetId: null,
    },
    {
      id: "second-edge",
      fromStateId: "middle",
      to: { stateId: "end" },
      label: "Done",
      trigger: "continue",
      sourceKind: "authored",
      hotspotId: null,
      transitionAssetId: null,
    },
  ];
  plan.visits = [
    {
      visitId: "first",
      stateId: "state",
      selectedEdgeId: "first-edge",
      holdFrames: 90,
    },
    {
      visitId: "second",
      stateId: "middle",
      selectedEdgeId: "second-edge",
      holdFrames: 90,
    },
    { visitId: "third", stateId: "end", selectedEdgeId: null, holdFrames: 90 },
  ];
  plan.effects = ["first", "second"].map((visitId) => ({
    type: "transition",
    visitId,
    startFrame: 60,
    durationFrames: 30,
    hotspotId: null,
    regionId: null,
    text: null,
    rect: null,
  }));
  plan.timeline = plan.visits.map((visit, i) => ({
    visitId: visit.visitId,
    startFrame: i * 60,
    durationFrames: 90,
    transitionFrames: 0,
    overlapFrames: i < 2 ? 30 : 0,
  }));
  plan.totalFrames = 210;
  const opacities = (frame: number) =>
    Object.fromEntries(
      [
        ...markup(scene, plan, frame).matchAll(
          /<div[^>]*data-tapscene-visit="([^"]+)"[^>]*>/g,
        ),
      ].map((match) => [
        match[1],
        Number(match[0].match(/opacity:([0-9.]+)/)?.[1]),
      ]),
    );
  assert.deepEqual(opacities(0), { first: 1 });
  assert.deepEqual(opacities(60), { first: 1, second: 0 });
  assert.deepEqual(opacities(75), { first: 1, second: 0.5 });
  assert.deepEqual(opacities(90), { second: 1 });
  assert.deepEqual(opacities(135), { second: 1, third: 0.5 });
  assert.deepEqual(opacities(150), { third: 1 });
  for (let frame = 0; frame < plan.totalFrames; frame++) {
    const layers = Object.values(opacities(frame));
    assert.ok(layers.length <= 2, "at most two active visits");
    // Source-over compositing of two equal pixels must preserve their color,
    // not reveal the dark page behind two partially transparent layers.
    const pixel = layers.reduce((below, alpha) => 220 * alpha + below * (1 - alpha), 17);
    assert.ok(Math.abs(pixel - 220) < 1e-9, `same-color pixel dimmed at frame ${frame}`);
  }
  const incoming = markup(scene, plan, 75).match(/<div[^>]*data-tapscene-visit="second"[^>]*>/)?.[0];
  assert.match(incoming ?? "", /background:#111816/);
});
