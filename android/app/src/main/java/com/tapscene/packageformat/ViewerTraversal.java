package com.tapscene.packageformat;

import static com.tapscene.packageformat.StrictJson.require;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The real static viewer's immutable reducer, shared by Android and host package checks.
 * Each action is explicit. The platform must validate/decode the destination PNG before
 * committing the returned state or recording its coverage. This class performs no IO.
 */
public final class ViewerTraversal {
    public static final int MAX_VISITS = 256;
    public final String currentStateId, endLabel, endEdgeId;
    /** Actual visit history including the current state as its last element. */
    public final List<String> history;
    public final boolean ended, completedFromStart;
    /** Session/review coverage survives back and restart; it is not a package-supplied claim. */
    public final Set<String> visitedEdgeIds;

    public ViewerTraversal(String currentStateId, List<String> history, boolean ended,
            String endLabel, String endEdgeId, Set<String> visitedEdgeIds, boolean completedFromStart) {
        require(history != null && visitedEdgeIds != null, "Missing viewer history");
        this.currentStateId = currentStateId;
        this.history = Collections.unmodifiableList(new ArrayList<>(history));
        this.ended = ended; this.endLabel = endLabel; this.endEdgeId = endEdgeId;
        this.visitedEdgeIds = Collections.unmodifiableSet(new HashSet<>(visitedEdgeIds));
        this.completedFromStart = completedFromStart;
    }
    public static ViewerTraversal start(ViewerScene scene) {
        ViewerPackageCodec.validateScene(scene); ViewerScene.State first = state(scene, scene.startStateId);
        return new ViewerTraversal(first.id, Collections.singletonList(first.id), first.terminal,
                first.terminal ? first.title : null, null, Collections.emptySet(), first.terminal);
    }
    public ViewerTraversal advance(ViewerScene scene, String edgeId) {
        validate(scene); require(!ended, "The current visit has ended; go back or restart");
        ViewerScene.Edge selected = edge(scene, edgeId);
        require(selected.fromStateId.equals(currentStateId), "Action does not leave the current state");
        Set<String> visited = new HashSet<>(visitedEdgeIds); visited.add(selected.id);
        if (selected.toStateId == null) return new ViewerTraversal(currentStateId, history, true,
                selected.endLabel, selected.id, visited, true);
        require(history.size() < MAX_VISITS, "Visit history limit reached; go back or restart");
        ViewerScene.State target = state(scene, selected.toStateId);
        List<String> nextHistory = new ArrayList<>(history); nextHistory.add(target.id);
        return new ViewerTraversal(target.id, nextHistory, target.terminal,
                target.terminal ? target.title : null, null, visited, completedFromStart || target.terminal);
    }
    public ViewerTraversal previous(ViewerScene scene) {
        validate(scene);
        if (endEdgeId != null) return new ViewerTraversal(currentStateId, history, false, null, null,
                visitedEdgeIds, completedFromStart);
        require(history.size() > 1, "No previous visit");
        List<String> previous = new ArrayList<>(history); previous.remove(previous.size()-1);
        ViewerScene.State target = state(scene, previous.get(previous.size()-1));
        return new ViewerTraversal(target.id, previous, target.terminal, target.terminal ? target.title : null,
                null, visitedEdgeIds, completedFromStart);
    }
    public ViewerTraversal restart(ViewerScene scene) {
        validate(scene); ViewerScene.State first = state(scene, scene.startStateId);
        return new ViewerTraversal(first.id, Collections.singletonList(first.id), first.terminal,
                first.terminal ? first.title : null, null, visitedEdgeIds, completedFromStart || first.terminal);
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
