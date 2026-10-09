import React from "react";
import {
  AbsoluteFill,
  Img,
  OffthreadVideo,
  Sequence,
  interpolate,
  staticFile,
  useCurrentFrame,
} from "remotion";
import type { Scene, Plan } from "./contracts";
export type RenderProps = { scene: Scene; plan: Plan };
const accent = "#c5ff72",
  ink = "#f3f5ef",
  muted = "#a3ada7";
const fit = (w: number, h: number, maxW: number, maxH: number) => {
  const scale = Math.min(maxW / w, maxH / h);
  return { width: w * scale, height: h * scale, scale };
};
export const TapSceneDemo: React.FC<RenderProps> = ({ scene, plan }) => {
  const frame = useCurrentFrame(),
    portrait = plan.canvas.height > plan.canvas.width;
  return (
    <AbsoluteFill
      style={{
        background: "#111816",
        color: ink,
        fontFamily: 'Arial, "Noto Sans CJK SC", sans-serif',
      }}
    >
      <div
        style={{
          position: "absolute",
          left: portrait ? 70 : 90,
          top: portrait ? 76 : 62,
          fontSize: 22,
          letterSpacing: 5,
          color: accent,
        }}
      >
        TAPSCENE / DEMO
      </div>
      <div
        style={{
          position: "absolute",
          right: portrait ? 70 : 90,
          top: portrait ? 76 : 62,
          fontSize: 21,
          color: muted,
          fontVariantNumeric: "tabular-nums",
        }}
      >
        {String(
          Math.min(
            plan.visits.length,
            plan.timeline.filter((t) => frame >= t.startFrame).length,
          ),
        ).padStart(2, "0")}{" "}
        / {String(plan.visits.length).padStart(2, "0")}
      </div>
      {plan.visits.map((visit, i) => {
        const time = plan.timeline[i];
        if (
          frame < time.startFrame ||
          frame >= time.startFrame + time.durationFrames
        )
          return null;
        const state = scene.states.find((s) => s.id === visit.stateId)!,
          asset = scene.assets.find((a) => a.id === state.imageAssetId)!,
          edge = scene.edges.find((e) => e.id === visit.selectedEdgeId);
        const incoming = i > 0 ? plan.timeline[i - 1].overlapFrames : 0;
        const local = frame - time.startFrame;
        const incomingOpacity =
          incoming > 0
            ? interpolate(local, [0, incoming], [0, 1], {
                extrapolateRight: "clamp",
              })
            : 1;
        const outgoingOpacity =
          time.overlapFrames > 0
            ? interpolate(
                local,
                [time.durationFrames - time.overlapFrames, time.durationFrames],
                [1, 0],
                { extrapolateLeft: "clamp", extrapolateRight: "clamp" },
              )
            : 1;
        const opacity = incomingOpacity * outgoingOpacity;
        const panel = fit(
          state.width,
          state.height,
          portrait ? 860 : 960,
          portrait ? 1160 : 780,
        );
        const left = portrait
          ? (1080 - panel.width) / 2
          : 90 + (960 - panel.width) / 2;
        const top = portrait ? 410 : 166 + (780 - panel.height) / 2;
        const effects = plan.effects.filter(
          (e) =>
            e.visitId === visit.visitId &&
            local >= e.startFrame &&
            local < e.startFrame + e.durationFrames &&
            e.type !== "transition",
        );
        const focus = effects.find((e) => e.type === "focus" && e.regionId);
        const transitioning =
          local >= visit.holdFrames && edge?.transitionAssetId;
        const transition = scene.assets.find(
          (a) => a.id === edge?.transitionAssetId,
        );
        return (
          <AbsoluteFill
            key={visit.visitId}
            data-tapscene-visit={visit.visitId}
            style={{ opacity }}
          >
            <div
              style={{
                position: "absolute",
                left: portrait ? 70 : 1130,
                top: portrait ? 168 : 260,
                width: portrait ? 940 : 700,
              }}
            >
              <div
                style={{
                  fontSize: portrait ? 55 : 56,
                  fontWeight: 700,
                  lineHeight: 1.16,
                  letterSpacing: -1,
                  display: "-webkit-box",
                  WebkitLineClamp: portrait ? 2 : 3,
                  WebkitBoxOrient: "vertical",
                  overflow: "hidden",
                }}
              >
                {state.title}
              </div>
              <div
                style={{
                  fontSize: portrait ? 26 : 29,
                  color: muted,
                  lineHeight: 1.45,
                  marginTop: 24,
                  display: "-webkit-box",
                  WebkitLineClamp: portrait ? 2 : 5,
                  WebkitBoxOrient: "vertical",
                  overflow: "hidden",
                }}
              >
                {state.description || scene.goal}
              </div>
              {!portrait && (
                <div
                  style={{
                    marginTop: 56,
                    borderTop: "1px solid #344038",
                    paddingTop: 22,
                    fontSize: 21,
                    color: accent,
                  }}
                >
                  {transitioning
                    ? "RECORDED TRANSITION"
                    : focus
                      ? "VISIBLE CROP / SAFE PIXELS"
                      : "REVIEWED SCREEN"}
                </div>
              )}
            </div>
            <div
              style={{
                position: "absolute",
                left,
                top,
                width: panel.width,
                height: panel.height,
                boxShadow: "0 30px 80px #0008",
                border: "1px solid #465149",
                borderRadius: 10,
                background: "#26302a",
              }}
            >
              <Img
                src={staticFile(asset.path)}
                style={{
                  width: "100%",
                  height: "100%",
                  objectFit: "contain",
                  borderRadius: 9,
                }}
              />
              {transitioning && transition && (
                <Sequence
                  from={time.startFrame + visit.holdFrames}
                  durationInFrames={time.transitionFrames}
                  layout="none"
                >
                  <OffthreadVideo
                    src={staticFile(transition.path)}
                    muted
                    style={{
                      position: "absolute",
                      inset: 0,
                      width: "100%",
                      height: "100%",
                      objectFit: "contain",
                      background: "#26302a",
                    }}
                  />
                </Sequence>
              )}
              {focus && !transitioning && (
                <AbsoluteFill
                  style={{ background: "#0007", borderRadius: 9 }}
                />
              )}
              {!transitioning &&
                effects
                  .sort((a, b) => {
                    const ra = scene.regions.find((r) => r.id === a.regionId),
                      rb = scene.regions.find((r) => r.id === b.regionId);
                    return (ra?.zIndex ?? 0) - (rb?.zIndex ?? 0);
                  })
                  .map((effect, index) => {
                    const region = scene.regions.find(
                        (r) => r.id === effect.regionId,
                      ),
                      hotspot = scene.hotspots.find(
                        (h) => h.id === effect.hotspotId,
                      );
                    const box = region
                      ? {
                          x: region.bbox.x / state.width,
                          y: region.bbox.y / state.height,
                          width: region.bbox.width / state.width,
                          height: region.bbox.height / state.height,
                        }
                      : (effect.rect ?? hotspot?.rect);
                    if (!box) return null;
                    const age = local - effect.startFrame,
                      remaining = effect.durationFrames - age;
                    const ramp = Math.min(1, age / 8, remaining / 8);
                    const rectangle: React.CSSProperties = {
                      position: "absolute",
                      left: box.x * panel.width,
                      top: box.y * panel.height,
                      width: box.width * panel.width,
                      height: box.height * panel.height,
                    };
                    if (effect.type === "annotation") {
                      return (
                        <div
                          key={index}
                          data-tapscene-annotation="true"
                          style={{
                            ...rectangle,
                            boxSizing: "border-box",
                            padding: 8,
                            background: "#111816ed",
                            color: ink,
                            fontSize: Math.max(
                              10,
                              Math.min(25, panel.width / 30),
                            ),
                            lineHeight: 1.25,
                            whiteSpace: "pre-wrap",
                            overflowWrap: "anywhere",
                            overflow: "hidden",
                            zIndex: 10 + index,
                          }}
                        >
                          {effect.text}
                        </div>
                      );
                    }
                    if (effect.type === "focus" && region) {
                      const crop = scene.assets.find(
                        (a) => a.id === region.assetId,
                      )!;
                      return (
                        <div
                          key={index}
                          style={{
                            ...rectangle,
                            transform: `translateY(${-18 * ramp}px) scale(${1 + 0.06 * ramp})`,
                            transformOrigin: `${region.anchor.x * 100}% ${region.anchor.y * 100}%`,
                            boxShadow: "0 14px 38px #0009",
                            outline: `3px solid ${accent}`,
                            zIndex: 10 + index,
                          }}
                        >
                          <Img
                            src={staticFile(crop.path)}
                            style={{ width: "100%", height: "100%" }}
                          />
                          <div
                            style={{
                              position: "absolute",
                              left: 0,
                              bottom: -35,
                              fontSize: 18,
                              color: accent,
                              background: "#111816",
                              padding: "5px 10px",
                              whiteSpace: "nowrap",
                              maxWidth: panel.width,
                              overflow: "hidden",
                              textOverflow: "ellipsis",
                            }}
                          >
                            {region.name}
                          </div>
                        </div>
                      );
                    }
                    return (
                      <div
                        key={index}
                        style={{
                          ...rectangle,
                          border: `${effect.type === "click" ? 5 : 3}px solid ${accent}`,
                          borderRadius: 8,
                          background:
                            effect.type === "highlight"
                              ? `rgba(197,255,114,${0.12 * ramp})`
                              : "transparent",
                          boxShadow: `0 0 0 ${effect.type === "click" ? 8 + 12 * ramp : 0}px #c5ff7225`,
                        }}
                      />
                    );
                  })}
            </div>
            <div
              style={{
                position: "absolute",
                left: portrait ? 70 : 1130,
                top: portrait ? 1645 : 700,
                width: portrait ? 940 : 700,
              }}
            >
              <div
                style={{
                  color: accent,
                  fontSize: 25,
                  lineHeight: 1.45,
                  display: "-webkit-box",
                  WebkitLineClamp: 3,
                  WebkitBoxOrient: "vertical",
                  overflow: "hidden",
                }}
              >
                {edge ? edge.label : "演示完成"}
              </div>
              <div
                style={{
                  marginTop: 20,
                  fontSize: 19,
                  lineHeight: 1.4,
                  color: muted,
                }}
              >
                {focus
                  ? "可见裁片来自当前安全截图；原底图保留。"
                  : "已复核画面 · 无原音输出"}
              </div>
            </div>
          </AbsoluteFill>
        );
      })}
      <div
        style={{
          position: "absolute",
          left: portrait ? 70 : 90,
          right: portrait ? 70 : 90,
          bottom: 66,
          height: 3,
          background: "#344038",
        }}
      >
        <div
          style={{
            width: `${(100 * (frame + 1)) / plan.totalFrames}%`,
            height: 3,
            background: accent,
          }}
        />
      </div>
      <div
        style={{
          position: "absolute",
          left: portrait ? 70 : 90,
          bottom: 30,
          fontSize: 16,
          color: "#718177",
          letterSpacing: 1,
        }}
      >
        SAFE SCREEN CAPTURES · FIXED RELEASE · FINITE PATH
      </div>
    </AbsoluteFill>
  );
};
