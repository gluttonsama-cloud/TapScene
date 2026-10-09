package com.tapscene.packageformat;

import static com.tapscene.packageformat.StrictJson.require;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The real viewer's immutable reducer, shared by Android and host package checks.
 * A transition fixes its edge while leaving the source visit and coverage unchanged.
 * Only EOS or an explicit skip commits that edge. The platform must validate/decode
 * the destination PNG before committing the returned state or recording coverage.
 * This class performs no IO; run IDs are process-unique, never saved as resumable media.
 */
public final class ViewerTraversal {
    public static final int MAX_VISITS = 256;
    private static final AtomicLong NEXT_MEDIA_RUN_ID = new AtomicLong();
    public final String currentStateId, endLabel, endEdgeId;
    /** Actual visit history including the current state as its last element. */
    public final List<String> history;
    public final boolean ended, completedFromStart;
    /** Session/review coverage survives back and restart; it is not a package-supplied claim. */
    public final Set<String> visitedEdgeIds;
    public final String pendingEdgeId, pendingTransitionAssetId, pendingToStateId, pendingEndLabel;
    /** Identifies one playback attempt. Retry and new sessions receive different IDs. */
    public final long mediaRunId;
    public final boolean transitionFailed, closed;

    public ViewerTraversal(String currentStateId, List<String> history, boolean ended,
            String endLabel, String endEdgeId, Set<String> visitedEdgeIds, boolean completedFromStart) {
        this(currentStateId, history, ended, endLabel, endEdgeId, visitedEdgeIds, completedFromStart,
                null, null, null, null, 0, false, false);
    }
    private ViewerTraversal(String currentStateId, List<String> history, boolean ended,
            String endLabel, String endEdgeId, Set<String> visitedEdgeIds, boolean completedFromStart,
            String pendingEdgeId, String pendingTransitionAssetId, String pendingToStateId, String pendingEndLabel, long mediaRunId,
            boolean transitionFailed, boolean closed) {
        require(history != null && visitedEdgeIds != null, "Missing viewer history");
        this.currentStateId = currentStateId;
        this.history = Collections.unmodifiableList(new ArrayList<>(history));
        this.ended = ended; this.endLabel = endLabel; this.endEdgeId = endEdgeId;
        this.visitedEdgeIds = Collections.unmodifiableSet(new HashSet<>(visitedEdgeIds));
        this.completedFromStart = completedFromStart;
        this.pendingEdgeId = pendingEdgeId; this.pendingTransitionAssetId = pendingTransitionAssetId;
        this.pendingToStateId = pendingToStateId; this.pendingEndLabel = pendingEndLabel;
        this.mediaRunId = mediaRunId; this.transitionFailed = transitionFailed; this.closed = closed;
    }
    public static ViewerTraversal start(ViewerScene scene) {
        ViewerPackageCodec.validateScene(scene); ViewerScene.State first = state(scene, scene.startStateId);
        return new ViewerTraversal(first.id, Collections.singletonList(first.id), first.terminal,
                first.terminal ? first.title : null, null, Collections.emptySet(), first.terminal);
    }
    public ViewerTraversal advance(ViewerScene scene, String edgeId) {
        validate(scene);
        if (closed || pendingEdgeId != null) return this;
        require(!ended, "The current visit has ended; go back or restart");
        ViewerScene.Edge selected = edge(scene, edgeId);
        require(selected.fromStateId.equals(currentStateId), "Action does not leave the current state");
        require(selected.toStateId == null || history.size() < MAX_VISITS, "Visit history limit reached; go back or restart");
        if (selected.transitionAssetId != null) return pending(selected, newRunId(), false);
        return commit(scene, selected);
    }
    public ViewerTraversal completeTransition(ViewerScene scene, long runId) {
        validate(scene);
        if (!active(runId) || transitionFailed) return this;
        return commit(scene, edge(scene, pendingEdgeId));
    }
    public ViewerTraversal failTransition(ViewerScene scene, long runId) {
        validate(scene);
        if (!active(runId) || transitionFailed) return this;
        return pending(edge(scene, pendingEdgeId), mediaRunId, true);
    }
    public ViewerTraversal retryTransition(ViewerScene scene) {
        validate(scene);
        if (closed || pendingEdgeId == null || !transitionFailed) return this;
        return pending(edge(scene, pendingEdgeId), newRunId(), false);
    }
    public ViewerTraversal skipTransition(ViewerScene scene) {
        return skipTransition(scene, mediaRunId);
    }
    public ViewerTraversal skipTransition(ViewerScene scene, long runId) {
        validate(scene);
        if (!active(runId)) return this;
        return commit(scene, edge(scene, pendingEdgeId));
    }
    public ViewerTraversal cancelTransition(ViewerScene scene) {
        validate(scene);
        if (closed || pendingEdgeId == null) return this;
        return new ViewerTraversal(currentStateId, history, ended, endLabel, endEdgeId,
                visitedEdgeIds, completedFromStart, null, null, null, null, mediaRunId, false, false);
    }
    public ViewerTraversal close(ViewerScene scene) {
        validate(scene);
        if (closed) return this;
        return new ViewerTraversal(currentStateId, history, ended, endLabel, endEdgeId,
                visitedEdgeIds, completedFromStart, null, null, null, null, mediaRunId, false, true);
    }
    public ViewerTraversal previous(ViewerScene scene) {
        validate(scene);
        if (closed) return this;
        // Back during a transition cancels it; the unvisited target is never pushed.
        if (pendingEdgeId != null) return cancelTransition(scene);
        if (endEdgeId != null) return idle(currentStateId, history, false, null, null,
                visitedEdgeIds, completedFromStart);
        require(history.size() > 1, "No previous visit");
        List<String> previous = new ArrayList<>(history); previous.remove(previous.size()-1);
        ViewerScene.State target = state(scene, previous.get(previous.size()-1));
        return idle(target.id, previous, target.terminal, target.terminal ? target.title : null,
                null, visitedEdgeIds, completedFromStart);
    }
    public ViewerTraversal restart(ViewerScene scene) {
        validate(scene);
        if (closed) return this;
        ViewerScene.State first = state(scene, scene.startStateId);
        return idle(first.id, Collections.singletonList(first.id), first.terminal,
                first.terminal ? first.title : null, null, visitedEdgeIds, completedFromStart || first.terminal);
    }
    private boolean active(long runId) {
        return !closed && pendingEdgeId != null && runId > 0 && mediaRunId == runId;
    }
    private static long newRunId() {
        long id = NEXT_MEDIA_RUN_ID.incrementAndGet();
        require(id > 0, "Media attempt identifiers exhausted"); return id;
    }
    private ViewerTraversal pending(ViewerScene.Edge selected, long runId, boolean failed) {
        return new ViewerTraversal(currentStateId, history, ended, endLabel, endEdgeId,
                visitedEdgeIds, completedFromStart, selected.id, selected.transitionAssetId, selected.toStateId, selected.endLabel, runId, failed, false);
    }
    private ViewerTraversal idle(String current, List<String> visits, boolean end, String label, String edgeId,
            Set<String> visited, boolean completed) {
        return new ViewerTraversal(current, visits, end, label, edgeId, visited, completed,
                null, null, null, null, mediaRunId, false, false);
    }
    private ViewerTraversal commit(ViewerScene scene, ViewerScene.Edge selected) {
        Set<String> visited = new HashSet<>(visitedEdgeIds); visited.add(selected.id);
        if (selected.toStateId == null) return idle(currentStateId, history, true,
                selected.endLabel, selected.id, visited, true);
        require(history.size() < MAX_VISITS, "Visit history limit reached; go back or restart");
        ViewerScene.State target = state(scene, selected.toStateId);
        List<String> nextHistory = new ArrayList<>(history); nextHistory.add(target.id);
        return idle(target.id, nextHistory, target.terminal,
                target.terminal ? target.title : null, null, visited, completedFromStart || target.terminal);
    }
    private void validate(ViewerScene scene) {
        ViewerPackageCodec.validateScene(scene);
        require(!history.isEmpty() && history.size() <= MAX_VISITS && scene.startStateId.equals(history.get(0))
                && currentStateId != null && currentStateId.equals(history.get(history.size()-1)), "Viewer history does not belong to this scene");
        ViewerScene.State current = state(scene,currentStateId);
        for (String id : history) state(scene,id);
        for (int i=1;i<history.size();i++) {
            String from=history.get(i-1), to=history.get(i); boolean connected=false;
            for (ViewerScene.Edge e : scene.edges) if (e.fromStateId.equals(from) && to.equals(e.toStateId)) { connected=true; break; }
            require(connected, "Viewer history contains an unvisited graph jump");
        }
        for (String id : visitedEdgeIds) edge(scene,id);
        if (endEdgeId != null) {
            ViewerScene.Edge end = edge(scene,endEdgeId);
            require(ended && end.toStateId == null && end.fromStateId.equals(currentStateId)
                    && end.endLabel.equals(endLabel) && visitedEdgeIds.contains(end.id), "Invalid ended action");
        } else require(ended == current.terminal && (ended ? current.title.equals(endLabel) : endLabel == null), "Invalid viewer ending state");
        require(!ended || completedFromStart, "Completed visit is missing completion state");
        require(mediaRunId >= 0, "Invalid media attempt identifier");
        if (pendingEdgeId != null) {
            ViewerScene.Edge pending = edge(scene, pendingEdgeId);
            require(!closed && !ended && mediaRunId > 0 && pending.fromStateId.equals(currentStateId)
                    && pending.transitionAssetId != null && pending.transitionAssetId.equals(pendingTransitionAssetId)
                    && Objects.equals(pending.toStateId, pendingToStateId) && Objects.equals(pending.endLabel, pendingEndLabel)
                    && (pending.toStateId == null || history.size() < MAX_VISITS), "Invalid pending transition");
        } else require(pendingTransitionAssetId == null && pendingToStateId == null && pendingEndLabel == null && !transitionFailed, "Unexpected pending media state");
    }
    private static ViewerScene.State state(ViewerScene scene,String id) {
        for (ViewerScene.State s : scene.states) if (s.id.equals(id)) return s;
        throw new IllegalArgumentException("Viewer references missing state");
    }
    private static ViewerScene.Edge edge(ViewerScene scene,String id) {
        for (ViewerScene.Edge e : scene.edges) if (e.id.equals(id)) return e;
        throw new IllegalArgumentException("Viewer references missing action");
    }
}
