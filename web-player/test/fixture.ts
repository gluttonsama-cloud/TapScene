import { validateScene, type Scene } from "@tapscene/runtime-ts";
import type { HostedManifest } from "../src/api";

export const id = (n: number) =>
  `00000000-0000-4000-8000-${n.toString().padStart(12, "0")}`;

export function fixture(video = false): HostedManifest {
  const scene: Scene = {
    schemaVersion: video ? 2 : 1,
    policyVersion: video ? "video-viewer-2" : "static-viewer-1",
    compilerVersion: "tapscene-android-1",
    releaseId: id(1),
    title: "把报名流程，讲清楚",
    goal: "跟随画面探索报名路径，体验不同结果。所有操作均为演示。",
    createdAt: 1,
    startStateId: id(2),
    states: [
      {
        id: id(2),
        imageAssetId: id(12),
        width: 360,
        height: 640,
        title: "选择你的参加方式",
        description:
          "在活动页面选择「立即报名」，或先了解活动安排。\n点击画面中的标记，也可以使用下方的文字操作。",
        sourceKind: "authored",
        terminal: false,
      },
      {
        id: id(3),
        imageAssetId: id(13),
        width: 360,
        height: 640,
        title: "确认报名信息",
        description: "核对示例信息后继续。可以返回上一页，选择其他路径。",
        sourceKind: "authored",
        terminal: false,
      },
      {
        id: id(4),
        imageAssetId: id(14),
        width: 360,
        height: 640,
        title: "报名成功",
        description: "你已经完成了这条演示路径。",
        sourceKind: "authored",
        terminal: true,
      },
    ],
    edges: [
      {
        id: id(22),
        fromStateId: id(2),
        to: { stateId: id(3) },
        hotspotId: id(32),
        label: "立即报名",
        trigger: "tap",
        transitionAssetId: video ? id(15) : null,
        sourceKind: "authored",
      },
      {
        id: id(23),
        fromStateId: id(2),
        to: { stateId: id(4) },
        hotspotId: id(33),
        label: "查看报名结果",
        trigger: "tap",
        transitionAssetId: null,
        sourceKind: "authored",
      },
      {
        id: id(24),
        fromStateId: id(3),
        to: { stateId: id(4) },
        hotspotId: null,
        label: "确认信息并继续",
        trigger: "continue",
        transitionAssetId: null,
        sourceKind: "authored",
      },
      {
        id: id(25),
        fromStateId: id(3),
        to: { stateId: id(2) },
        hotspotId: id(34),
        label: "重新选择",
        trigger: "tap",
        transitionAssetId: null,
        sourceKind: "authored",
      },
    ],
    hotspots: [
      {
        id: id(32),
        stateId: id(2),
        label: "立即报名",
        coordinateSpace: "state-normalized",
        rect: { x: 0.1, y: 0.73, width: 0.8, height: 0.08 },
      },
      {
        id: id(33),
        stateId: id(2),
        label: "查看报名结果",
        coordinateSpace: "state-normalized",
        rect: { x: 0.1, y: 0.84, width: 0.8, height: 0.07 },
      },
      {
        id: id(34),
        stateId: id(3),
        label: "重新选择",
        coordinateSpace: "state-normalized",
        rect: { x: 0.1, y: 0.84, width: 0.8, height: 0.07 },
      },
    ],
    regions: [],
    assets: [12, 13, 14].map((n) => ({
      id: id(n),
      path: `assets/${id(n)}.png`,
      role: "state-image" as const,
      mime: "image/png" as const,
      byteLength: 100,
      sha256: "a".repeat(64),
      width: 360,
      height: 640,
      durationMs: null,
    })),
  };
  if (video)
    scene.assets.push({
      id: id(15),
      path: `assets/${id(15)}.mp4`,
      role: "transition",
      mime: "video/mp4",
      byteLength: 100,
      sha256: "b".repeat(64),
      width: 360,
      height: 640,
      durationMs: 1000,
    });
  return {
    releaseId: scene.releaseId,
    contentDigest: "a".repeat(64),
    versionOrdinal: 3,
    expiresAt: "2099-10-17T10:00:00.000Z",
    scene: validateScene(scene),
  };
}
