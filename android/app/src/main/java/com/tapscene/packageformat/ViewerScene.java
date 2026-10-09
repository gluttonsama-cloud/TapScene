package com.tapscene.packageformat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable, data-only static viewer profile. No object can contain executable behavior. */
public final class ViewerScene {
    public final String releaseId, title, goal, startStateId;
    public final long createdAt;
    public final List<State> states;
    public final List<Edge> edges;
    public final List<Hotspot> hotspots;
    public final List<Asset> assets;

    public ViewerScene(String releaseId, String title, String goal, long createdAt,
            String startStateId, List<State> states, List<Edge> edges,
            List<Hotspot> hotspots, List<Asset> assets) {
        this.releaseId = releaseId; this.title = title; this.goal = goal;
        this.createdAt = createdAt; this.startStateId = startStateId;
        this.states = frozen(states); this.edges = frozen(edges);
        this.hotspots = frozen(hotspots); this.assets = frozen(assets);
    }
    private static <T> List<T> frozen(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(values)));
    }
    public static final class State {
        public final String id, imageAssetId, title, description, sourceKind;
        public final int width, height;
        public final boolean terminal;
        public State(String id, String imageAssetId, int width, int height,
                String title, String description, String sourceKind, boolean terminal) {
            this.id = id; this.imageAssetId = imageAssetId; this.width = width; this.height = height;
            this.title = title; this.description = description; this.sourceKind = sourceKind;
            this.terminal = terminal;
        }
    }
    public static final class Edge {
        public final String id, fromStateId, toStateId, endLabel, hotspotId, label, trigger, sourceKind;
        public Edge(String id, String fromStateId, String toStateId, String endLabel,
                String hotspotId, String label, String trigger, String sourceKind) {
            this.id = id; this.fromStateId = fromStateId; this.toStateId = toStateId;
            this.endLabel = endLabel; this.hotspotId = hotspotId; this.label = label;
            this.trigger = trigger; this.sourceKind = sourceKind;
        }
    }
    public static final class Hotspot {
        public final String id, stateId, label;
        public final Rect rect;
        public Hotspot(String id, String stateId, String label, Rect rect) {
            this.id = id; this.stateId = stateId; this.label = label; this.rect = rect;
        }
    }
    public static final class Rect {
        public final double x, y, width, height;
        public Rect(double x, double y, double width, double height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }
    public static final class Asset {
        public final String id, path, mime, sha256;
        public final long byteLength;
        public final int width, height;
        public Asset(String id, String path, String mime, long byteLength,
                String sha256, int width, int height) {
            this.id = id; this.path = path; this.mime = mime; this.byteLength = byteLength;
            this.sha256 = sha256; this.width = width; this.height = height;
        }
    }
}
