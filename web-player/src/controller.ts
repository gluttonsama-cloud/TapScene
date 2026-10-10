import {
  reduce,
  start,
  type ViewerEvent,
  type ViewerState,
} from "@tapscene/runtime-ts";
import { ViewerError, type HostedManifest, type LoadedImage } from "./api";

export type ViewerSnapshot = {
  phase: "authorizing" | "ready" | "hidden" | "error";
  manifest: HostedManifest | null;
  player: ViewerState | null;
  image: LoadedImage | null;
  loadingImage: boolean;
  imageFailed: boolean;
  error: ViewerError | null;
};

export type ViewerDependencies = {
  manifest(signal: AbortSignal): Promise<HostedManifest>;
  image(assetId: string, signal: AbortSignal): Promise<LoadedImage>;
  /** Must synchronously conceal protected DOM, before any asynchronous state update. */
  conceal(): void;
  timeoutMs?: number;
};

const opaque = (
  phase: ViewerSnapshot["phase"],
  error: ViewerError | null = null,
): ViewerSnapshot => ({
  phase,
  manifest: null,
  player: null,
  image: null,
  loadingImage: false,
  imageFailed: false,
  error,
});

/** Owns requests/resources; the pure runtime owns graph/history/transition semantics. */
export class ViewerController {
  private snapshot: ViewerSnapshot = opaque("authorizing");
  private listeners = new Set<() => void>();
  private checkpoint: { manifest: HostedManifest; player: ViewerState } | null =
    null;
  private retryCandidate: ViewerState | null = null;
  private sequence = 0;
  private request: AbortController | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private disposed = false;

  constructor(private readonly dependencies: ViewerDependencies) {}

  getSnapshot = (): ViewerSnapshot => this.snapshot;
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  private publish(snapshot: ViewerSnapshot) {
    if (this.disposed) return;
    this.snapshot = snapshot;
    for (const listener of this.listeners) listener();
  }

  private invalidate() {
    this.sequence += 1;
    this.request?.abort();
    this.request = null;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  private begin() {
    this.invalidate();
    const request = new AbortController();
    this.request = request;
    const sequence = this.sequence;
    this.timer = setTimeout(
      () => request.abort(),
      this.dependencies.timeoutMs ?? 20_000,
    );
    return {
      signal: request.signal,
      current: () => !this.disposed && sequence === this.sequence,
    };
  }

  private finishRequest() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.request = null;
  }

  private conceal() {
    this.dependencies.conceal();
    this.snapshot.image?.dispose();
  }

  private verifyIdentity(manifest: HostedManifest) {
    const previous = this.checkpoint?.manifest;
    if (
      previous &&
      (previous.releaseId !== manifest.releaseId ||
        previous.contentDigest !== manifest.contentDigest ||
        previous.versionOrdinal !== manifest.versionOrdinal ||
        previous.expiresAt !== manifest.expiresAt)
    )
      throw new ViewerError("CONTENT_CHANGED");
  }

  private fail(error: unknown) {
    this.conceal();
    this.publish(
      opaque(
        "error",
        error instanceof ViewerError
          ? error
          : new ViewerError("SERVICE_UNAVAILABLE", true),
      ),
    );
  }

  /** Resume always authorizes first, including bfcache restores and visibility changes. */
  resume = (): Promise<void> => this.authorize(false);

  private async authorize(keepPending: boolean): Promise<void> {
    if (this.disposed) return;
    const operation = this.begin();
    this.retryCandidate = null;
    this.conceal();
    this.publish(opaque("authorizing"));
    let loaded: LoadedImage | null = null;
    try {
      const manifest = await this.dependencies.manifest(operation.signal);
      if (!operation.current()) return;
      this.verifyIdentity(manifest);
      let player = this.checkpoint?.player ?? start(manifest.scene);
      if (player.pendingEdgeId && !keepPending)
        player = reduce(manifest.scene, player, { type: "cancelTransition" });
      this.checkpoint = { manifest, player };
      const target = manifest.scene.states.find(
        (state) => state.id === player.currentStateId,
      );
      if (!target) throw new ViewerError("INVALID_CONTENT");
      loaded = await this.dependencies.image(
        target.imageAssetId,
        operation.signal,
      );
      if (!operation.current()) {
        loaded.dispose();
        return;
      }
      this.finishRequest();
      this.publish({
        phase: "ready",
        manifest,
        player,
        image: loaded,
        loadingImage: false,
        imageFailed: false,
        error: null,
      });
    } catch (error) {
      loaded?.dispose();
      if (!operation.current()) return;
      this.finishRequest();
      this.fail(error);
    }
  }

  suspend = () => {
    this.invalidate();
    this.retryCandidate = null;
    this.conceal();
    this.publish(opaque("hidden"));
  };

  expire = () => {
    this.invalidate();
    this.retryCandidate = null;
    this.fail(new ViewerError("SHARE_EXPIRED"));
  };

  /** Repeated actions cannot overtake image IO or an active video run. */
  async act(event: ViewerEvent): Promise<void> {
    const { manifest, player, phase, loadingImage } = this.snapshot;
    if (this.disposed || phase !== "ready" || !manifest || !player) return;
    if (loadingImage) {
      if (event.type === "back" || event.type === "cancelTransition") {
        this.invalidate();
        this.retryCandidate = null;
        const cancelled = player.pendingEdgeId
          ? reduce(manifest.scene, player, { type: "cancelTransition" })
          : player;
        this.checkpoint = { manifest, player: cancelled };
        this.publish({
          ...this.snapshot,
          player: cancelled,
          loadingImage: false,
          imageFailed: false,
        });
        return;
      }
      if (event.type !== "restart") return;
      this.invalidate();
    }
    let candidate: ViewerState;
    try {
      candidate = reduce(manifest.scene, player, event);
    } catch {
      return;
    } // Ignore stale/invalid controls instead of exposing internal graph data.
    if (candidate === player && !loadingImage) return;
    if (event.type === "failTransition") {
      this.checkpoint = { manifest, player: candidate };
      await this.authorize(true);
      return;
    }
    await this.applyCandidate(candidate);
  }

  retryImage = async (): Promise<void> => {
    if (
      this.snapshot.phase !== "ready" ||
      this.snapshot.loadingImage ||
      !this.retryCandidate
    )
      return;
    await this.applyCandidate(this.retryCandidate);
  };

  private async applyCandidate(candidate: ViewerState): Promise<void> {
    const { manifest, player, image } = this.snapshot;
    if (!manifest || !player || !image) return;
    this.retryCandidate = null;
    // A transition starts or an end label changes without changing the screenshot.
    if (candidate.currentStateId === player.currentStateId) {
      this.invalidate();
      this.checkpoint = { manifest, player: candidate };
      this.publish({
        ...this.snapshot,
        player: candidate,
        loadingImage: false,
        imageFailed: false,
      });
      return;
    }
    const operation = this.begin();
    this.publish({ ...this.snapshot, loadingImage: true, imageFailed: false });
    let loaded: LoadedImage | null = null;
    try {
      const target = manifest.scene.states.find(
        (state) => state.id === candidate.currentStateId,
      );
      if (!target) throw new ViewerError("INVALID_CONTENT");
      loaded = await this.dependencies.image(
        target.imageAssetId,
        operation.signal,
      );
      if (!operation.current()) {
        loaded.dispose();
        return;
      }
      this.finishRequest();
      image.dispose();
      this.checkpoint = { manifest, player: candidate };
      this.publish({
        phase: "ready",
        manifest,
        player: candidate,
        image: loaded,
        loadingImage: false,
        imageFailed: false,
        error: null,
      });
    } catch {
      loaded?.dispose();
      if (!operation.current()) return;
      // An image event does not expose its HTTP status. Recheck authorization before
      // allowing the existing protected screen to remain visible or offering retry.
      this.dependencies.conceal();
      try {
        if (operation.signal.aborted)
          throw new ViewerError("SERVICE_UNAVAILABLE", true);
        const authorization = await this.dependencies.manifest(
          operation.signal,
        );
        if (!operation.current()) return;
        this.verifyIdentity(authorization);
        this.finishRequest();
        this.retryCandidate = candidate;
        this.publish({
          ...this.snapshot,
          loadingImage: false,
          imageFailed: true,
        });
      } catch (error) {
        if (!operation.current()) return;
        this.finishRequest();
        this.fail(error);
      }
    }
  }

  dispose = () => {
    this.invalidate();
    this.conceal();
    this.checkpoint = null;
    this.retryCandidate = null;
    this.disposed = true;
    this.listeners.clear();
  };
}
