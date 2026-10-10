import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type CSSProperties,
} from "react";
import {
  assetUrl,
  expiryTime,
  fetchManifest,
  loadImage,
  shareBase,
  ViewerError,
  type LoadedImage,
} from "./api";
import { ViewerController } from "./controller";
import { MAX_VISITS } from "@tapscene/runtime-ts";

function Icon({
  name,
  size = 20,
}: {
  name: "arrow" | "back" | "restart" | "play" | "check" | "lock" | "clock";
  size?: number;
}) {
  const paths = {
    arrow: "M5 12h14m-6-6 6 6-6 6",
    back: "M19 12H5m6-6-6 6 6 6",
    restart: "M3 10a9 9 0 1 1 2 8M3 4v6h6",
    play: "m9 5 10 7-10 7V5Z",
    check: "m5 12 4 4L19 6",
    lock: "M6 10h12v11H6V10Zm3 0V6a3 3 0 0 1 6 0v4",
    clock: "M12 8v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z",
  };
  return (
    <svg
      aria-hidden="true"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d={paths[name]} />
    </svg>
  );
}

function Brand() {
  return (
    <div className="brand" aria-label="TapScene 点演">
      <span>
        TapScene<span className="brand-name">点演</span>
      </span>
    </div>
  );
}

const errorCopy: Record<string, [string, string]> = {
  SHARE_REVOKED: [
    "这份演示已被撤销",
    "作者已关闭此链接。请联系作者获取新的演示。",
  ],
  SHARE_EXPIRED: [
    "这份演示已到期",
    "链接的有效期已结束。请联系作者获取新的观看链接。",
  ],
  NOT_FOUND: [
    "未找到这份演示",
    "链接可能不完整或已失效，请检查作者发给你的完整链接。",
  ],
  SERVICE_UNAVAILABLE: [
    "暂时无法验证访问权限",
    "网络或服务暂时不可用。为保护演示内容，画面已隐藏，稍后可重试。",
  ],
  IMAGE_FAILED: ["画面暂时无法加载", "未能读取完整画面。请检查网络后重试。"],
  INVALID_CONTENT: [
    "暂时无法播放这份演示",
    "演示内容未通过完整性检查，请联系作者。",
  ],
  CONTENT_CHANGED: [
    "演示版本无法确认",
    "链接返回的版本与已打开的内容不一致，请联系作者。",
  ],
};

function Gate({
  phase,
  error,
  retry,
}: {
  phase: string;
  error: ViewerError | null;
  retry: () => void;
}) {
  const loading = phase === "authorizing";
  const [title, description] = errorCopy[error?.code ?? ""] ?? [
    "演示已暂停",
    "回到页面后，将重新确认链接的访问权限。",
  ];
  return (
    <main className="gate" aria-live="polite" aria-busy={loading}>
      <div className={`gate-symbol ${loading ? "is-loading" : ""}`}>
        <Icon name={loading ? "clock" : "lock"} size={28} />
      </div>
      <h1>{loading ? "正在打开演示" : title}</h1>
      <p>{loading ? "正在确认访问权限并加载画面…" : description}</p>
      {error?.retryable && (
        <button className="button primary" onClick={retry}>
          <Icon name="restart" /> 重新尝试
        </button>
      )}
    </main>
  );
}

function ImageStage({
  image,
  title,
  width,
  height,
}: {
  image: LoadedImage;
  title: string;
  width: number;
  height: number;
}) {
  const host = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    image.element.alt = title;
    image.element.width = width;
    image.element.height = height;
    host.current?.replaceChildren(image.element);
    return () => {
      image.element.remove();
    };
  }, [image, title, width, height]);
  return <div className="screen-image" ref={host} />;
}

function TransitionVideo({
  src,
  runId,
  failed,
  onEnd,
  onFailure,
  onRetry,
  onSkip,
}: {
  src: string;
  runId: number;
  failed: boolean;
  onEnd: (id: number) => void;
  onFailure: (id: number) => void;
  onRetry: () => void;
  onSkip: (id: number) => void;
}) {
  const video = useRef<HTMLVideoElement>(null);
  const [needsPlay, setNeedsPlay] = useState(false);
  useEffect(() => {
    if (failed) return;
    const element = video.current;
    if (!element) return;
    let active = true;
    element.play().catch(() => {
      if (active) setNeedsPlay(true);
    });
    return () => {
      active = false;
      element.pause();
      element.removeAttribute("src");
      element.load();
    };
  }, [src, runId, failed]);
  return (
    <div className="transition" aria-label="步骤过渡">
      {!failed && (
        <video
          ref={video}
          src={src}
          playsInline
          muted
          controls
          preload="none"
          disablePictureInPicture
          controlsList="nodownload noremoteplayback"
          onEnded={() => onEnd(runId)}
          onError={() => onFailure(runId)}
          onPlaying={() => setNeedsPlay(false)}
        />
      )}
      {(failed || needsPlay) && (
        <div className="transition-message" role="status">
          <span className="transition-icon">
            <Icon name="play" size={28} />
          </span>
          <h3>{failed ? "过渡视频暂时无法播放" : "点击播放过渡"}</h3>
          <p>
            {failed
              ? "可重试视频，或跳过后继续到已选步骤。"
              : "浏览器需要你点击后开始播放。"}
          </p>
          {failed ? (
            <button className="button primary" onClick={onRetry}>
              <Icon name="restart" /> 重试视频
            </button>
          ) : (
            <button
              className="button primary"
              onClick={() => {
                void video.current?.play().catch(() => setNeedsPlay(true));
              }}
            >
              <Icon name="play" /> 播放视频
            </button>
          )}
        </div>
      )}
      <div className="transition-bar">
        <span>
          <span className="status-dot" />{" "}
          {failed ? "过渡未完成" : "正在切换步骤"}
        </span>
        <button className="button small" onClick={() => onSkip(runId)}>
          跳过过渡 <Icon name="arrow" size={16} />
        </button>
      </div>
    </div>
  );
}

export function Player({ base }: { base: string }) {
  const [controller] = useState(
    () =>
      new ViewerController({
        manifest: (signal) => fetchManifest(base, signal),
        image: (assetId, signal) => loadImage(base, assetId, signal),
        conceal: () => {
          document.documentElement.dataset.protectedHidden = "true";
          // Stop native media synchronously, before React unmounts on the next commit.
          document
            .querySelectorAll<HTMLVideoElement>("[data-protected] video")
            .forEach((element) => {
              element.pause();
              element.removeAttribute("src");
              element.load();
            });
        },
      }),
  );
  const snapshot = useSyncExternalStore(
    controller.subscribe,
    controller.getSnapshot,
  );
  const { manifest, player, image } = snapshot;
  const heading = useRef<HTMLHeadingElement>(null);
  const [enlarged, setEnlarged] = useState(false);
  const focusedState = useRef<string | null>(null);

  useEffect(() => {
    const resume = () => {
      if (document.visibilityState !== "hidden") void controller.resume();
    };
    const visibility = () => {
      if (document.visibilityState === "hidden") controller.suspend();
      else resume();
    };
    const pagehide = () => controller.suspend();
    const pageshow = (event: PageTransitionEvent) => {
      if (event.persisted) resume();
    };
    document.addEventListener("visibilitychange", visibility);
    window.addEventListener("pagehide", pagehide);
    window.addEventListener("pageshow", pageshow);
    resume();
    return () => {
      document.removeEventListener("visibilitychange", visibility);
      window.removeEventListener("pagehide", pagehide);
      window.removeEventListener("pageshow", pageshow);
      controller.dispose();
    };
  }, [controller]);

  useLayoutEffect(() => {
    if (snapshot.phase === "ready" && document.visibilityState !== "hidden") {
      delete document.documentElement.dataset.protectedHidden;
      if (player && focusedState.current !== player.currentStateId) {
        if (focusedState.current !== null) heading.current?.focus();
        focusedState.current = player.currentStateId;
      }
    }
  }, [snapshot, player]);

  useEffect(() => {
    if (!manifest) return;
    const remaining = expiryTime(manifest.expiresAt) - Date.now();
    if (remaining <= 0) {
      controller.expire();
      return;
    }
    // Timers are bounded because browser setTimeout is signed 32-bit.
    const timer = setTimeout(
      () => {
        void controller.resume();
      },
      Math.min(remaining, 2_147_000_000),
    );
    return () => clearTimeout(timer);
  }, [manifest, controller]);

  if (snapshot.phase !== "ready" || !manifest || !player || !image) {
    return (
      <Gate
        phase={snapshot.phase}
        error={snapshot.error}
        retry={() => {
          void controller.resume();
        }}
      />
    );
  }
  const state = manifest.scene.states.find(
    (item) => item.id === player.currentStateId,
  )!;
  const stateIndex = manifest.scene.states.findIndex(
    (item) => item.id === state.id,
  );
  const edges = manifest.scene.edges.filter(
    (edge) => edge.fromStateId === state.id,
  );
  const hotspots = manifest.scene.hotspots.filter(
    (spot) => spot.stateId === state.id,
  );
  const transitioning = Boolean(player.pendingEdgeId);
  const locked = snapshot.loadingImage || transitioning || player.ended;
  const complete = player.ended || state.terminal;
  const expiry = new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    timeZoneName: "short",
  }).format(new Date(manifest.expiresAt));
  const act = (event: Parameters<typeof controller.act>[0]) => {
    void controller.act(event);
  };
  const targetLabel = (edge: (typeof edges)[number]) => {
    const target = edge.to;
    return "stateId" in target
      ? manifest.scene.states.find((s) => s.id === target.stateId)?.title
      : target.endLabel;
  };

  return (
    <main
      className={`viewer${enlarged ? " is-enlarged" : ""}`}
      data-protected="true"
    >
      <section className="intro" aria-labelledby="demo-title">
        <div>
          <div className="meta-row">
            <span className="eyebrow">互动演示</span>
            <span className="version">版本 {manifest.versionOrdinal}</span>
            <span className="valid">
              <span className="status-dot" /> 链接有效
            </span>
          </div>
          <h1 id="demo-title">{manifest.scene.title}</h1>
          {manifest.scene.goal && <p className="goal">{manifest.scene.goal}</p>}
        </div>
        <div className="expiry">
          <Icon name="clock" size={16} />
          <span>有效至 {expiry}</span>
        </div>
      </section>

      <div className="viewer-grid">
        <section className="stage-panel" aria-label="演示画面">
          <div className="stage-toolbar">
            <span>
              <span className="live-indicator" /> 演示画面
            </span>
            <button
              className="image-size-toggle"
              aria-pressed={enlarged}
              onClick={() => setEnlarged(!enlarged)}
            >
              {enlarged ? "适应屏幕" : "放大画面"}
            </button>
          </div>
          <div className="stage">
            <div
              className="scene-frame"
              style={
                {
                  width: `min(100%, ${Math.round((650 * state.width) / state.height)}px)`,
                  aspectRatio: `${state.width} / ${state.height}`,
                  "--frame-ratio": state.width / state.height,
                } as CSSProperties
              }
            >
              <ImageStage
                image={image}
                title={state.title}
                width={state.width}
                height={state.height}
              />
              {!complete &&
                !transitioning &&
                hotspots.map((spot) => {
                  const matching = edges.find(
                    (edge) => edge.hotspotId === spot.id,
                  );
                  if (!matching) return null;
                  const ordinal = edges.indexOf(matching) + 1;
                  return (
                    <button
                      key={spot.id}
                      className="hotspot"
                      disabled={
                        locked ||
                        ("stateId" in matching.to &&
                          player.history.length >= MAX_VISITS)
                      }
                      aria-label={`${ordinal}. ${spot.label}：${matching.label}`}
                      title={spot.label}
                      style={
                        {
                          left: `${spot.rect.x * 100}%`,
                          top: `${spot.rect.y * 100}%`,
                          width: `${spot.rect.width * 100}%`,
                          height: `${spot.rect.height * 100}%`,
                        } as CSSProperties
                      }
                      onClick={() =>
                        act({ type: "advance", edgeId: matching.id })
                      }
                    >
                      <span>{ordinal}</span>
                    </button>
                  );
                })}
              {transitioning &&
                player.pendingTransitionAssetId &&
                !snapshot.loadingImage &&
                !snapshot.imageFailed && (
                  <TransitionVideo
                    key={player.mediaRunId}
                    src={assetUrl(base, player.pendingTransitionAssetId)}
                    runId={player.mediaRunId}
                    failed={player.transitionFailed}
                    onEnd={(mediaRunId) =>
                      act({ type: "completeTransition", mediaRunId })
                    }
                    onFailure={(mediaRunId) =>
                      act({ type: "failTransition", mediaRunId })
                    }
                    onRetry={() => act({ type: "retryTransition" })}
                    onSkip={(mediaRunId) =>
                      act({ type: "skipTransition", mediaRunId })
                    }
                  />
                )}
              {snapshot.loadingImage && (
                <div className="image-loading" role="status">
                  <span className="spinner" /> 正在加载下一幅画面…
                </div>
              )}
            </div>
          </div>
        </section>

        <aside className="guide" aria-label="当前步骤与操作">
          <div className="step-meta">
            <span className="step-number">
              {String(stateIndex + 1).padStart(2, "0")}
            </span>
            <span>
              当前步骤
              <span className="step-total">
                {" "}
                / 共 {manifest.scene.states.length} 个画面
              </span>
            </span>
          </div>
          <h2 ref={heading} tabIndex={-1}>
            {complete ? (player.endLabel ?? state.title) : state.title}
          </h2>
          {state.description && (
            <p className="description">{state.description}</p>
          )}
          {(manifest.scene.goal || state.description) && (
            <details className="mobile-details">
              <summary>查看说明与讲解</summary>
              <div>
                {manifest.scene.goal && <p>{manifest.scene.goal}</p>}
                {state.description && <p>{state.description}</p>}
              </div>
            </details>
          )}
          {complete ? (
            <div className="completion" role="status">
              <span className="complete-icon">
                <Icon name="check" />
              </span>
              <div>
                <h3>已到达演示终点</h3>
                <p>可以返回刚才的步骤，探索另一条路径。</p>
              </div>
            </div>
          ) : (
            <div className="actions">
              <div className="section-label">
                {edges.length > 1 ? "选择下一步" : "继续演示"}
                <span>{edges.length > 1 ? `${edges.length} 个操作` : ""}</span>
              </div>
              {edges.map((edge, index) => (
                <button
                  key={edge.id}
                  className={`action ${edge.trigger === "continue" ? "continue-action" : ""}`}
                  disabled={
                    locked ||
                    ("stateId" in edge.to &&
                      player.history.length >= MAX_VISITS)
                  }
                  onClick={() => act({ type: "advance", edgeId: edge.id })}
                >
                  <span className="action-number">{index + 1}</span>
                  <span className="action-copy">
                    <strong>{edge.label}</strong>
                    <span>{targetLabel(edge)}</span>
                  </span>
                  <Icon name="arrow" size={18} />
                </button>
              ))}
            </div>
          )}
          {player.history.length >= MAX_VISITS && (
            <p className="transition-note" role="status">
              本次已访问 {MAX_VISITS} 步。可返回之前的步骤，或重新开始。
            </p>
          )}
          {snapshot.imageFailed && (
            <div className="media-warning" role="alert">
              <strong>目标画面暂时无法加载</strong>
              <p>仍停留在当前步骤，访问历史尚未前进。</p>
              <button
                className="button"
                onClick={() => {
                  void controller.retryImage();
                }}
              >
                重试画面
              </button>
            </div>
          )}
          {transitioning && !snapshot.imageFailed && (
            <p className="transition-note" role="status">
              {snapshot.loadingImage
                ? "视频已结束，正在确认目标画面。"
                : "完成或跳过过渡后，才会进入下一步。"}
            </p>
          )}
          <div className="guide-bottom">
            <div className="navigation">
              <button
                className="button"
                disabled={
                  player.history.length <= 1 &&
                  !player.endEdgeId &&
                  !transitioning &&
                  !snapshot.loadingImage
                }
                onClick={() => act({ type: "back" })}
              >
                <Icon name="back" size={18} />
                {transitioning || snapshot.loadingImage ? "取消切换" : "上一步"}
              </button>
              {enlarged && (
                <button
                  className="button mobile-fit"
                  onClick={() => setEnlarged(false)}
                >
                  收起大图
                </button>
              )}
              <button
                className="button quiet"
                onClick={() => act({ type: "restart" })}
              >
                <Icon name="restart" size={18} /> 重新开始
              </button>
            </div>
          </div>
        </aside>
      </div>
      <p className="privacy-note">
        <Icon name="lock" size={15} />{" "}
        作者可撤销链接；已保存的副本无法远程收回。
      </p>
    </main>
  );
}

export function App() {
  let base: string | null = null;
  try {
    base = shareBase(window.location.pathname);
  } catch {
    /* Generic, opaque invalid-link state. */
  }
  return (
    <div className="app">
      <header className="site-header">
        <Brand />
        <span className="viewer-badge">只读演示</span>
      </header>
      {base ? (
        <Player base={base} />
      ) : (
        <Gate
          phase="error"
          error={new ViewerError("NOT_FOUND")}
          retry={() => {}}
        />
      )}
    </div>
  );
}
