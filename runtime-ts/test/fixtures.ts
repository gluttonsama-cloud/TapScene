import type { Scene } from "../src/index.js";
export const id = (n: number) =>
  `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`;
export function fixture(video = false): Scene {
  const states = [1, 2, 3].map((n) => ({
    id: id(n),
    imageAssetId: id(100),
    width: 100,
    height: 200,
    title: `State ${n}`,
    description: "Synthetic only",
    sourceKind: "authored" as const,
    terminal: n === 3,
  }));
  return {
    schemaVersion: video ? 2 : 1,
    policyVersion: video ? "video-viewer-2" : "static-viewer-1",
    compilerVersion: "tapscene-android-1",
    releaseId: id(999),
    title: "Synthetic demo",
    goal: "",
    createdAt: 0,
    startStateId: id(1),
    states,
    edges: [
      {
        id: id(10),
        fromStateId: id(1),
        to: { stateId: id(2) },
        hotspotId: id(20),
        label: "Go",
        trigger: "tap",
        transitionAssetId: video ? id(101) : null,
        sourceKind: "recorded",
      },
      {
        id: id(11),
        fromStateId: id(2),
        to: { stateId: id(1) },
        hotspotId: id(21),
        label: "Revisit",
        trigger: "tap",
        transitionAssetId: null,
        sourceKind: "authored",
      },
      {
        id: id(12),
        fromStateId: id(2),
        to: { stateId: id(3) },
        hotspotId: null,
        label: "Continue",
        trigger: "continue",
        transitionAssetId: null,
        sourceKind: "authored",
      },
      {
        id: id(13),
        fromStateId: id(2),
        to: { endLabel: "Explicit ending" },
        hotspotId: id(22),
        label: "Finish",
        trigger: "tap",
        transitionAssetId: null,
        sourceKind: "authored",
      },
    ],
    hotspots: [20, 21, 22].map((n) => ({
      id: id(n),
      stateId: id(n === 20 ? 1 : 2),
      label: "Action",
      coordinateSpace: "state-normalized" as const,
      rect: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
    })),
    regions: [],
    assets: [
      {
        id: id(100),
        path: `assets/${id(100)}.png`,
        role: "state-image",
        mime: "image/png",
        byteLength: 128,
        sha256: "a".repeat(64),
        width: 100,
        height: 200,
        durationMs: null,
      },
      ...(video
        ? [
            {
              id: id(101),
              path: `assets/${id(101)}.mp4`,
              role: "transition" as const,
              mime: "video/mp4" as const,
              byteLength: 128,
              sha256: "b".repeat(64),
              width: 100,
              height: 200,
              durationMs: 1000,
            },
          ]
        : []),
    ],
  };
}
