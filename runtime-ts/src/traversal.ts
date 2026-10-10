import { validateScene, type Scene, type Edge } from "./contracts.js";
import { assert } from "./json.js";

export const MAX_VISITS = 256;
/** No IO/platform APIs. Like Java, playback IDs are unique within this runtime instance. */
let nextMediaRunId = 0;
function newRunId(): number {
  assert(
    nextMediaRunId < Number.MAX_SAFE_INTEGER,
    "Media attempt identifiers exhausted",
  );
  return ++nextMediaRunId;
}

/** Actual history includes the current state; coverage survives back/restart in this session. */
export interface ViewerState {
  readonly currentStateId: string;
  readonly history: readonly string[];
  readonly ended: boolean;
  readonly endLabel: string | null;
  readonly endEdgeId: string | null;
  readonly visitedEdgeIds: readonly string[];
  readonly completedFromStart: boolean;
  readonly pendingEdgeId: string | null;
  readonly pendingTransitionAssetId: string | null;
  readonly pendingToStateId: string | null;
  readonly pendingEndLabel: string | null;
  readonly mediaRunId: number;
  readonly transitionFailed: boolean;
  readonly closed: boolean;
}
/** EOS/failure/skip must carry the exact playback attempt ID, never a freshly read ID. */
export type ViewerEvent =
  | { type: "advance"; edgeId: string }
  | {
      type: "completeTransition" | "failTransition" | "skipTransition";
      mediaRunId: number;
    }
  | {
      type:
        | "retryTransition"
        | "cancelTransition"
        | "back"
        | "restart"
        | "close";
    };

function immutable(state: ViewerState): ViewerState {
  return Object.freeze({
    ...state,
    history: Object.freeze([...state.history]),
    visitedEdgeIds: Object.freeze([...state.visitedEdgeIds]),
  });
}
function getState(scene: Scene, id: string) {
  const state = scene.states.find((s) => s.id === id);
  assert(state, "Viewer references missing state");
  return state;
}
function getEdge(scene: Scene, id: string) {
  const edge = scene.edges.find((e) => e.id === id);
  assert(edge, "Viewer references missing action");
  return edge;
}
function idle(
  state: ViewerState,
  change: Partial<ViewerState> = {},
): ViewerState {
  return immutable({
    ...state,
    pendingEdgeId: null,
    pendingTransitionAssetId: null,
    pendingToStateId: null,
    pendingEndLabel: null,
    transitionFailed: false,
    ...change,
  });
}
function pending(
  state: ViewerState,
  edge: Edge,
  mediaRunId: number,
  transitionFailed: boolean,
): ViewerState {
  return immutable({
    ...state,
    pendingEdgeId: edge.id,
    pendingTransitionAssetId: edge.transitionAssetId,
    pendingToStateId: "stateId" in edge.to ? edge.to.stateId : null,
    pendingEndLabel: "endLabel" in edge.to ? edge.to.endLabel : null,
    mediaRunId,
    transitionFailed,
  });
}

/** Callers must actually decode the first image before displaying/committing this state. */
export function start(input: Scene): ViewerState {
  const scene = validateScene(input),
    first = getState(scene, scene.startStateId);
  return immutable({
    currentStateId: first.id,
    history: [first.id],
    ended: first.terminal,
    endLabel: first.terminal ? first.title : null,
    endEdgeId: null,
    visitedEdgeIds: [],
    completedFromStart: first.terminal,
    pendingEdgeId: null,
    pendingTransitionAssetId: null,
    pendingToStateId: null,
    pendingEndLabel: null,
    mediaRunId: 0,
    transitionFailed: false,
    closed: false,
  });
}

function validateState(scene: Scene, state: ViewerState): void {
  assert(
    state &&
      Array.isArray(state.history) &&
      Array.isArray(state.visitedEdgeIds),
    "Missing viewer history",
  );
  assert(
    state.history.length > 0 &&
      state.history.length <= MAX_VISITS &&
      state.history[0] === scene.startStateId &&
      state.currentStateId === state.history.at(-1),
    "Viewer history does not belong to this scene",
  );
  const current = getState(scene, state.currentStateId);
  state.history.forEach((id, i) => {
    getState(scene, id);
    if (i)
      assert(
        scene.edges.some(
          (e) =>
            e.fromStateId === state.history[i - 1] &&
            "stateId" in e.to &&
            e.to.stateId === id,
        ),
        "Viewer history contains an unvisited graph jump",
      );
  });
  state.visitedEdgeIds.forEach((id) => getEdge(scene, id));
  assert(
    new Set(state.visitedEdgeIds).size === state.visitedEdgeIds.length,
    "Duplicate visited edge",
  );
  for (const value of [
    state.ended,
    state.completedFromStart,
    state.transitionFailed,
    state.closed,
  ])
    assert(typeof value === "boolean", "Invalid viewer flag");
  if (state.endEdgeId !== null) {
    const end = getEdge(scene, state.endEdgeId);
    assert(
      state.ended &&
        "endLabel" in end.to &&
        end.fromStateId === state.currentStateId &&
        end.to.endLabel === state.endLabel &&
        state.visitedEdgeIds.includes(end.id),
      "Invalid ended action",
    );
  } else
    assert(
      state.ended === current.terminal &&
        state.endLabel === (state.ended ? current.title : null),
      "Invalid viewer ending state",
    );
  assert(
    !state.ended || state.completedFromStart,
    "Completed visit is missing completion state",
  );
  assert(
    Number.isSafeInteger(state.mediaRunId) && state.mediaRunId >= 0,
    "Invalid media attempt identifier",
  );
  if (state.pendingEdgeId !== null) {
    const edge = getEdge(scene, state.pendingEdgeId);
    assert(
      !state.closed &&
        !state.ended &&
        state.mediaRunId > 0 &&
        edge.fromStateId === state.currentStateId &&
        edge.transitionAssetId !== null &&
        edge.transitionAssetId === state.pendingTransitionAssetId &&
        ("stateId" in edge.to ? edge.to.stateId : null) ===
          state.pendingToStateId &&
        ("endLabel" in edge.to ? edge.to.endLabel : null) ===
          state.pendingEndLabel &&
        (!("stateId" in edge.to) || state.history.length < MAX_VISITS),
      "Invalid pending transition",
    );
  } else
    assert(
      state.pendingTransitionAssetId === null &&
        state.pendingToStateId === null &&
        state.pendingEndLabel === null &&
        !state.transitionFailed,
      "Unexpected pending media state",
    );
}
function commit(scene: Scene, state: ViewerState, edge: Edge): ViewerState {
  const visitedEdgeIds = [...new Set([...state.visitedEdgeIds, edge.id])];
  if ("endLabel" in edge.to)
    return idle(state, {
      ended: true,
      endLabel: edge.to.endLabel,
      endEdgeId: edge.id,
      visitedEdgeIds,
      completedFromStart: true,
    });
  assert(
    state.history.length < MAX_VISITS,
    "Visit history limit reached; go back or restart",
  );
  const target = getState(scene, edge.to.stateId);
  return idle(state, {
    currentStateId: target.id,
    history: [...state.history, target.id],
    ended: target.terminal,
    endLabel: target.terminal ? target.title : null,
    endEdgeId: null,
    visitedEdgeIds,
    completedFromStart: state.completedFromStart || target.terminal,
  });
}

/**
 * Returns an immutable proposed state. Before assigning any state with a new current
 * image, callers must validate/decode that image and discard superseded async work.
 * Pending media keeps source history/coverage unchanged until EOS or explicit skip.
 * Invalid navigation throws; stale callbacks, duplicate actions and closed sessions no-op.
 */
export function reduce(
  input: Scene,
  state: ViewerState,
  event: ViewerEvent,
): ViewerState {
  const scene = validateScene(input);
  validateState(scene, state);
  if (state.closed) return state;
  switch (event.type) {
    case "advance": {
      if (state.pendingEdgeId !== null) return state;
      assert(!state.ended, "The current visit has ended; go back or restart");
      const edge = getEdge(scene, event.edgeId);
      assert(
        edge.fromStateId === state.currentStateId,
        "Action does not leave the current state",
      );
      assert(
        !("stateId" in edge.to) || state.history.length < MAX_VISITS,
        "Visit history limit reached; go back or restart",
      );
      return edge.transitionAssetId !== null
        ? pending(state, edge, newRunId(), false)
        : commit(scene, state, edge);
    }
    case "completeTransition":
    case "failTransition":
    case "skipTransition": {
      if (
        state.pendingEdgeId === null ||
        event.mediaRunId <= 0 ||
        event.mediaRunId !== state.mediaRunId
      )
        return state;
      if (event.type === "skipTransition")
        return commit(scene, state, getEdge(scene, state.pendingEdgeId));
      if (state.transitionFailed) return state;
      return event.type === "failTransition"
        ? pending(
            state,
            getEdge(scene, state.pendingEdgeId),
            state.mediaRunId,
            true,
          )
        : commit(scene, state, getEdge(scene, state.pendingEdgeId));
    }
    case "retryTransition":
      return state.pendingEdgeId !== null && state.transitionFailed
        ? pending(state, getEdge(scene, state.pendingEdgeId), newRunId(), false)
        : state;
    case "cancelTransition":
      return state.pendingEdgeId !== null ? idle(state) : state;
    case "back": {
      if (state.pendingEdgeId !== null) return idle(state);
      if (state.endEdgeId !== null)
        return idle(state, { ended: false, endLabel: null, endEdgeId: null });
      assert(state.history.length > 1, "No previous visit");
      const history = state.history.slice(0, -1),
        target = getState(scene, history.at(-1)!);
      return idle(state, {
        currentStateId: target.id,
        history,
        ended: target.terminal,
        endLabel: target.terminal ? target.title : null,
        endEdgeId: null,
      });
    }
    case "restart": {
      const first = getState(scene, scene.startStateId);
      return idle(state, {
        currentStateId: first.id,
        history: [first.id],
        ended: first.terminal,
        endLabel: first.terminal ? first.title : null,
        endEdgeId: null,
        completedFromStart: state.completedFromStart || first.terminal,
      });
    }
    case "close":
      return idle(state, { closed: true });
    default:
      throw new Error("Unknown viewer event");
  }
}
