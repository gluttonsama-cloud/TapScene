import test from "node:test";
import assert from "node:assert/strict";
import { start, reduce, MAX_VISITS } from "../src/index.js";
import { fixture, id } from "./fixtures.js";

test("actual revisit history/back, terminal title and restart coverage match Java", () => {
  const scene = fixture();
  let state = start(scene);
  state = reduce(scene, state, { type: "advance", edgeId: id(10) });
  state = reduce(scene, state, { type: "advance", edgeId: id(11) });
  assert.deepEqual(state.history, [id(1), id(2), id(1)]);
  state = reduce(scene, state, { type: "back" });
  assert.deepEqual(state.history, [id(1), id(2)]);
  state = reduce(scene, state, { type: "advance", edgeId: id(12) });
  assert.equal(state.ended, true);
  assert.equal(state.endLabel, "State 3");
  assert.equal(state.completedFromStart, true);
  assert.throws(
    () => reduce(scene, state, { type: "advance", edgeId: id(12) }),
    /ended/,
  );
  state = reduce(scene, state, { type: "restart" });
  assert.deepEqual(state.history, [id(1)]);
  assert.equal(state.visitedEdgeIds.length, 3);
  assert.equal(state.completedFromStart, true);
  assert.ok(Object.isFrozen(state) && Object.isFrozen(state.history));
});
test("endLabel action stays on source and first back dismisses ending", () => {
  const scene = fixture();
  let state = reduce(scene, start(scene), { type: "advance", edgeId: id(10) });
  state = reduce(scene, state, { type: "advance", edgeId: id(13) });
  assert.equal(state.endLabel, "Explicit ending");
  assert.equal(state.currentStateId, id(2));
  assert.deepEqual(state.history, [id(1), id(2)]);
  state = reduce(scene, state, { type: "back" });
  assert.equal(state.ended, false);
  assert.equal(state.currentStateId, id(2));
  state = reduce(scene, state, { type: "back" });
  assert.equal(state.currentStateId, id(1));
});
test("transition locks source; failure, retry and stale EOS never advance", () => {
  const scene = fixture(true);
  let state = start(scene);
  state = reduce(scene, state, { type: "advance", edgeId: id(10) });
  const oldRun = state.mediaRunId;
  assert.deepEqual(state.history, [id(1)]);
  assert.deepEqual(state.visitedEdgeIds, []);
  assert.equal(state.pendingToStateId, id(2));
  assert.strictEqual(
    reduce(scene, state, { type: "advance", edgeId: id(10) }),
    state,
  );
  assert.strictEqual(
    reduce(scene, state, {
      type: "completeTransition",
      mediaRunId: oldRun + 1,
    }),
    state,
  );
  state = reduce(scene, state, { type: "failTransition", mediaRunId: oldRun });
  assert.equal(state.transitionFailed, true);
  assert.strictEqual(
    reduce(scene, state, { type: "completeTransition", mediaRunId: oldRun }),
    state,
  );
  state = reduce(scene, state, { type: "retryTransition" });
  assert.notEqual(state.mediaRunId, oldRun);
  assert.strictEqual(
    reduce(scene, state, { type: "completeTransition", mediaRunId: oldRun }),
    state,
  );
  state = reduce(scene, state, {
    type: "completeTransition",
    mediaRunId: state.mediaRunId,
  });
  assert.deepEqual(state.history, [id(1), id(2)]);
  assert.deepEqual(state.visitedEdgeIds, [id(10)]);
});
test("failed transition can explicitly skip; back/cancel/restart/close invalidate callback", () => {
  const scene = fixture(true);
  for (const type of [
    "back",
    "cancelTransition",
    "restart",
    "close",
  ] as const) {
    let state = reduce(scene, start(scene), {
      type: "advance",
      edgeId: id(10),
    });
    const runId = state.mediaRunId;
    state = reduce(scene, state, { type });
    assert.strictEqual(
      reduce(scene, state, { type: "completeTransition", mediaRunId: runId }),
      state,
    );
    assert.deepEqual(state.history, [id(1)]);
    assert.equal(state.pendingEdgeId, null);
    if (type === "close")
      assert.strictEqual(reduce(scene, state, { type: "restart" }), state);
  }
  let state = reduce(scene, start(scene), { type: "advance", edgeId: id(10) });
  state = reduce(scene, state, {
    type: "failTransition",
    mediaRunId: state.mediaRunId,
  });
  state = reduce(scene, state, {
    type: "skipTransition",
    mediaRunId: state.mediaRunId,
  });
  assert.equal(state.currentStateId, id(2));
});
test("new sessions have distinct media attempt IDs", () => {
  const scene = fixture(true);
  const old = reduce(scene, start(scene), { type: "advance", edgeId: id(10) });
  const current = reduce(scene, start(scene), {
    type: "advance",
    edgeId: id(10),
  });
  assert.notEqual(old.mediaRunId, current.mediaRunId);
  assert.strictEqual(
    reduce(scene, current, {
      type: "completeTransition",
      mediaRunId: old.mediaRunId,
    }),
    current,
  );
});
test("256 visits bounds loops but permits explicit end, back and restart", () => {
  const scene = fixture();
  let state = start(scene);
  for (let i = 1; i < MAX_VISITS; i++)
    state = reduce(scene, state, {
      type: "advance",
      edgeId: id(i % 2 ? 10 : 11),
    });
  assert.equal(state.history.length, 256);
  assert.equal(state.currentStateId, id(2));
  assert.throws(
    () => reduce(scene, state, { type: "advance", edgeId: id(11) }),
    /limit/,
  );
  assert.throws(
    () => reduce(scene, state, { type: "advance", edgeId: id(12) }),
    /limit/,
  );
  assert.equal(
    reduce(scene, state, { type: "advance", edgeId: id(13) }).ended,
    true,
  );
  assert.equal(reduce(scene, state, { type: "back" }).history.length, 255);
  assert.equal(reduce(scene, state, { type: "restart" }).history.length, 1);
});
test("invalid foreign actions and forged histories fail closed", () => {
  const scene = fixture(),
    state = start(scene);
  assert.throws(
    () => reduce(scene, state, { type: "advance", edgeId: id(11) }),
    /current state/,
  );
  assert.throws(() => reduce(scene, state, { type: "back" }), /previous/);
  assert.throws(
    () =>
      reduce(
        scene,
        { ...state, history: [id(1), id(3)], currentStateId: id(3) },
        { type: "restart" },
      ),
    /graph jump/,
  );
  assert.throws(
    () =>
      reduce(scene, { ...state, pendingToStateId: id(3) }, { type: "restart" }),
    /Unexpected pending/,
  );
});
