import { test } from "node:test";
import assert from "node:assert/strict";
import { ViewerController } from "../src/controller";
import { ViewerError, type LoadedImage } from "../src/api";
import { fixture, id } from "./fixture";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function resource() {
  let disposed = false;
  return {
    element: {} as HTMLImageElement,
    dispose: () => {
      disposed = true;
    },
    get disposed() {
      return disposed;
    },
  };
}
function setup(video = false) {
  const manifest = fixture(video);
  let manifestCalls = 0,
    concealed = 0;
  let denied: ViewerError | null = null;
  let getImage = async (
    _assetId: string,
    _signal: AbortSignal,
  ): Promise<LoadedImage> => resource();
  const controller = new ViewerController({
    manifest: async () => {
      manifestCalls++;
      if (denied) throw denied;
      return manifest;
    },
    image: (assetId, signal) => getImage(assetId, signal),
    conceal: () => {
      concealed++;
    },
    timeoutMs: 1000,
  });
  return {
    controller,
    manifest,
    get manifestCalls() {
      return manifestCalls;
    },
    get concealed() {
      return concealed;
    },
    deny: (error: ViewerError) => {
      denied = error;
    },
    image: (loader: typeof getImage) => {
      getImage = loader;
    },
  };
}

test("initial content stays opaque until first image actually decodes", async () => {
  const h = setup(),
    pending = deferred<LoadedImage>();
  h.image(() => pending.promise);
  const task = h.controller.resume();
  await Promise.resolve();
  assert.equal(h.controller.getSnapshot().phase, "authorizing");
  assert.equal(h.controller.getSnapshot().manifest, null);
  pending.resolve(resource());
  await task;
  assert.equal(h.controller.getSnapshot().phase, "ready");
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2)]);
  h.controller.dispose();
});

test("navigation and history commit only after decoded target; repeated clicks are locked", async () => {
  const h = setup();
  await h.controller.resume();
  const source = h.controller.getSnapshot().image;
  const pending = deferred<LoadedImage>();
  let calls = 0;
  h.image(async () => {
    calls++;
    return pending.promise;
  });
  const task = h.controller.act({ type: "advance", edgeId: id(22) });
  await h.controller.act({ type: "advance", edgeId: id(23) });
  assert.equal(calls, 1);
  assert.equal(h.controller.getSnapshot().loadingImage, true);
  assert.equal(h.controller.getSnapshot().image, source);
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2)]);
  pending.resolve(resource());
  await task;
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2), id(3)]);
  h.controller.dispose();
});

test("Back cancels pending image without adding a visit; stale completion disposes pixels", async () => {
  const h = setup();
  await h.controller.resume();
  const pending = deferred<LoadedImage>(),
    late = resource();
  h.image(() => pending.promise);
  const task = h.controller.act({ type: "advance", edgeId: id(22) });
  await h.controller.act({ type: "back" });
  pending.resolve(late);
  await task;
  assert.equal(late.disposed, true);
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2)]);
  assert.equal(h.controller.getSnapshot().loadingImage, false);
  h.controller.dispose();
});

test("Back follows actual branching history, restart resets it", async () => {
  const h = setup();
  await h.controller.resume();
  await h.controller.act({ type: "advance", edgeId: id(22) });
  await h.controller.act({ type: "advance", edgeId: id(25) });
  await h.controller.act({ type: "advance", edgeId: id(23) });
  assert.deepEqual(h.controller.getSnapshot().player?.history, [
    id(2),
    id(3),
    id(2),
    id(4),
  ]);
  await h.controller.act({ type: "back" });
  assert.deepEqual(h.controller.getSnapshot().player?.history, [
    id(2),
    id(3),
    id(2),
  ]);
  await h.controller.act({ type: "restart" });
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2)]);
  h.controller.dispose();
});

test("failed target keeps source history and offers retry only after reauthorization", async () => {
  const h = setup();
  await h.controller.resume();
  h.image(async () => {
    throw new ViewerError("IMAGE_FAILED", true);
  });
  await h.controller.act({ type: "advance", edgeId: id(22) });
  assert.equal(h.manifestCalls, 2);
  assert.equal(h.controller.getSnapshot().imageFailed, true);
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2)]);
  h.image(async () => resource());
  await h.controller.retryImage();
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2), id(3)]);
  h.controller.dispose();
});

for (const code of [
  "NOT_FOUND",
  "SHARE_REVOKED",
  "SHARE_EXPIRED",
  "SERVICE_UNAVAILABLE",
]) {
  test(`resume ${code} fails closed and never displays cached pixels or text`, async () => {
    const h = setup();
    await h.controller.resume();
    const source = h.controller.getSnapshot().image as ReturnType<
      typeof resource
    >;
    h.controller.suspend();
    assert.equal(source.disposed, true);
    assert.equal(h.controller.getSnapshot().manifest, null);
    h.deny(new ViewerError(code, code === "SERVICE_UNAVAILABLE"));
    await h.controller.resume();
    assert.equal(h.controller.getSnapshot().phase, "error");
    assert.equal(h.controller.getSnapshot().image, null);
    assert.equal(h.controller.getSnapshot().player, null);
    assert.equal(h.controller.getSnapshot().manifest, null);
    assert.equal(h.controller.getSnapshot().error?.code, code);
    h.controller.dispose();
  });
}

test("hidden interrupts in-flight target and restored session reauthorizes source", async () => {
  const h = setup();
  await h.controller.resume();
  const pending = deferred<LoadedImage>(),
    late = resource();
  let aborted = false;
  h.image(async (_id, signal) => {
    signal.addEventListener("abort", () => {
      aborted = true;
    });
    return pending.promise;
  });
  const task = h.controller.act({ type: "advance", edgeId: id(22) });
  h.controller.suspend();
  pending.resolve(late);
  await task;
  assert.equal(aborted, true);
  assert.equal(late.disposed, true);
  assert.equal(h.controller.getSnapshot().phase, "hidden");
  h.image(async () => resource());
  await h.controller.resume();
  assert.equal(h.manifestCalls, 2);
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2)]);
  h.controller.dispose();
});

test("video retry uses a fresh run, stale EOS is ignored, skip waits for target decode", async () => {
  const h = setup(true);
  await h.controller.resume();
  await h.controller.act({ type: "advance", edgeId: id(22) });
  const original = h.controller.getSnapshot().player!.mediaRunId;
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2)]);
  await h.controller.act({ type: "failTransition", mediaRunId: original });
  assert.equal(h.manifestCalls, 2);
  assert.equal(h.controller.getSnapshot().player?.transitionFailed, true);
  await h.controller.act({ type: "retryTransition" });
  const current = h.controller.getSnapshot().player!.mediaRunId;
  assert.notEqual(current, original);
  await h.controller.act({ type: "completeTransition", mediaRunId: original });
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2)]);
  const pending = deferred<LoadedImage>();
  h.image(() => pending.promise);
  const skip = h.controller.act({
    type: "skipTransition",
    mediaRunId: current,
  });
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2)]);
  pending.resolve(resource());
  await skip;
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2), id(3)]);
  h.controller.dispose();
});

test("video failure after revoke hides all protected content", async () => {
  const h = setup(true);
  await h.controller.resume();
  await h.controller.act({ type: "advance", edgeId: id(22) });
  const mediaRunId = h.controller.getSnapshot().player!.mediaRunId;
  h.deny(new ViewerError("SHARE_REVOKED"));
  await h.controller.act({ type: "failTransition", mediaRunId });
  assert.equal(h.controller.getSnapshot().phase, "error");
  assert.equal(h.controller.getSnapshot().image, null);
  h.controller.dispose();
});

test("restoring cancels pending video and rejects the old media callback", async () => {
  const h = setup(true);
  await h.controller.resume();
  await h.controller.act({ type: "advance", edgeId: id(22) });
  const mediaRunId = h.controller.getSnapshot().player!.mediaRunId;
  h.controller.suspend();
  await h.controller.resume();
  await h.controller.act({ type: "completeTransition", mediaRunId });
  assert.equal(h.controller.getSnapshot().player?.pendingEdgeId, null);
  assert.deepEqual(h.controller.getSnapshot().player?.history, [id(2)]);
  h.controller.dispose();
});

test("same token cannot replace immutable manifest version during resume", async () => {
  const h = setup();
  await h.controller.resume();
  h.controller.suspend();
  h.manifest.contentDigest = "b".repeat(64);
  // The production parser returns a fresh object on every request.
  const first = fixture(),
    next = { ...first, contentDigest: "b".repeat(64) };
  let requests = 0;
  const controller = new ViewerController({
    manifest: async () => (++requests === 1 ? first : next),
    image: async () => resource(),
    conceal: () => {},
  });
  await controller.resume();
  controller.suspend();
  await controller.resume();
  assert.equal(controller.getSnapshot().error?.code, "CONTENT_CHANGED");
  assert.equal(controller.getSnapshot().manifest, null);
  h.controller.dispose();
  controller.dispose();
});

test("expiry synchronously conceals and disposes loaded resources", async () => {
  const h = setup();
  await h.controller.resume();
  const image = h.controller.getSnapshot().image as ReturnType<typeof resource>;
  h.controller.expire();
  assert.equal(image.disposed, true);
  assert.equal(h.controller.getSnapshot().error?.code, "SHARE_EXPIRED");
  assert.equal(h.controller.getSnapshot().manifest, null);
  h.controller.dispose();
});
