import { randomUUID, createHash } from "node:crypto";
import { PNG } from "pngjs";
import type { Scene } from "@tapscene/runtime-ts";
/** Pure synthetic artwork: no screenshot, source file or user information. */
export function syntheticFixture(releaseId: string = randomUUID()): {
  scene: Scene;
  bytes: Map<string, Buffer>;
} {
  const ids = Array.from({ length: 12 }, () => randomUUID()),
    bytes = new Map<string, Buffer>();
  const states = ids
    .slice(0, 4)
    .map((id, i) => ({
      id,
      imageAssetId: ids[i + 4],
      width: 390,
      height: 700,
      title: ["选择演示路线", "确认报名信息", "换一条路线", "报名完成"][i],
      description: [
        "这是纯合成示例。点画面热点或下方动作，体验不同分支。",
        "当前路线已记录。你可以返回起点，或继续完成。",
        "这个分支展示替代结果。返回会沿实际访问记录回退。",
        "演示已结束，你可以返回检查，也可以从头重来。",
      ][i],
      sourceKind: "authored" as const,
      terminal: i === 3,
    }));
  const assets = states.map((s, i) => {
    const png = new PNG({ width: s.width, height: s.height });
    const rect = (
      x: number,
      y: number,
      w: number,
      h: number,
      color: number[],
    ) => {
      for (let yy = y; yy < y + h; yy++)
        for (let xx = x; xx < x + w; xx++) {
          const p = (yy * s.width + xx) * 4;
          png.data[p] = color[0];
          png.data[p + 1] = color[1];
          png.data[p + 2] = color[2];
          png.data[p + 3] = 255;
        }
    };
    rect(0, 0, 390, 700, [240, 244, 247]);
    rect(0, 0, 390, 120, [26, 49, 67]);
    rect(25, 42, 120, 12, [243, 246, 249]);
    rect(25, 65, 75, 7, [150, 175, 191]);
    for (let n = 0; n < 3; n++) {
      rect(22, 150 + n * 125, 346, 103, [255, 255, 255]);
      rect(
        40,
        170 + n * 125,
        46,
        46,
        i === 3 ? [69, 146, 108] : [64 + 25 * i, 122, 167],
      );
      rect(105, 176 + n * 125, 175 - n * 24, 8, [69, 86, 99]);
      rect(105, 199 + n * 125, 138, 5, [174, 187, 195]);
      rect(105, 217 + n * 125, 190, 5, [206, 216, 222]);
    }
    rect(22, 577, 346, 65, i === 3 ? [69, 146, 108] : [42, 104, 153]);
    rect(100, 604, 188, 8, [255, 255, 255]);
    const b = PNG.sync.write(png, { colorType: 2, inputColorType: 6 });
    bytes.set(s.imageAssetId, b);
    return {
      id: s.imageAssetId,
      path: `assets/${s.imageAssetId}.png`,
      role: "state-image" as const,
      mime: "image/png" as const,
      byteLength: b.length,
      sha256: createHash("sha256").update(b).digest("hex"),
      width: s.width,
      height: s.height,
      durationMs: null,
    };
  });
  const h1 = randomUUID(),
    h2 = randomUUID();
  const edge = (
    from: number,
    to: number,
    label: string,
    hotspotId: string | null,
  ) => ({
    id: randomUUID(),
    fromStateId: states[from].id,
    to: { stateId: states[to].id },
    hotspotId,
    label,
    trigger: hotspotId ? ("tap" as const) : ("continue" as const),
    transitionAssetId: null,
    sourceKind: "authored" as const,
  });
  const scene: Scene = {
    schemaVersion: 1,
    policyVersion: "static-viewer-1",
    compilerVersion: "tapscene-android-1",
    releaseId,
    title: "分支报名 · 本地合成演示",
    goal: "验证托管和网页观看，不上传真实资料",
    createdAt: 0,
    startStateId: states[0].id,
    states,
    edges: [
      edge(0, 1, "进入报名", h1),
      edge(0, 2, "查看另一条路线", h2),
      edge(1, 3, "确认并完成", null),
      edge(2, 1, "回到报名流程", null),
    ],
    hotspots: [
      {
        id: h1,
        stateId: states[0].id,
        label: "进入报名",
        coordinateSpace: "state-normalized",
        rect: { x: 0.05, y: 0.21, width: 0.9, height: 0.16 },
      },
      {
        id: h2,
        stateId: states[0].id,
        label: "查看另一条路线",
        coordinateSpace: "state-normalized",
        rect: { x: 0.05, y: 0.39, width: 0.9, height: 0.16 },
      },
    ],
    regions: [],
    assets,
  };
  return { scene, bytes };
}
