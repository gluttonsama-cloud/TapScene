package com.tapscene.packageformat;

import static com.tapscene.packageformat.StrictJson.require;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic finite animation configuration, independent of the immutable release scene. */
public final class RenderPlan {
    public static final int SCHEMA_VERSION = 1, FPS = 30, MAX_VISITS = 256, MAX_HOLD_FRAMES = 1800;
    public static final int MAX_TOTAL_FRAMES = 18000, MAX_EFFECTS = 256, MAX_BYTES = 256 * 1024;
    public static final String ADAPTER_VERSION = "tapscene-remotion-1", COMPOSITION_ID = "TapSceneDemo";
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    public final String releaseId, contentDigest;
    public final int width, height, totalFrames;
    public final List<Visit> visits;
    public final List<Effect> effects;
    public final List<Timeline> timeline;

    private RenderPlan(ViewerScene scene, int width, int height, List<Visit> visits, List<Effect> effects,
            List<Timeline> timeline, int totalFrames) {
        this.releaseId = scene.releaseId; this.contentDigest = ViewerPackageCodec.contentDigest(scene);
        this.width = width; this.height = height; this.visits = frozen(visits); this.effects = frozen(effects);
        this.timeline = frozen(timeline); this.totalFrames = totalFrames;
    }
    private static <T> List<T> frozen(List<T> value) { return Collections.unmodifiableList(new ArrayList<>(value)); }
    public static final class Visit {
        public final String visitId, stateId, selectedEdgeId;
        public final int holdFrames;
        public Visit(String visitId, String stateId, String selectedEdgeId, int holdFrames) {
            this.visitId = visitId; this.stateId = stateId; this.selectedEdgeId = selectedEdgeId; this.holdFrames = holdFrames;
        }
    }
    /** Effect startFrame is relative to this visit's start; plain text is never interpreted. */
    public static final class Effect {
        public final String type, visitId, hotspotId, regionId, text;
        public final int startFrame, durationFrames;
        public final ViewerScene.Rect rect;
        public Effect(String type, String visitId, int startFrame, int durationFrames,
                String hotspotId, String regionId, String text, ViewerScene.Rect rect) {
            this.type = type; this.visitId = visitId; this.startFrame = startFrame; this.durationFrames = durationFrames;
            this.hotspotId = hotspotId; this.regionId = regionId; this.text = text; this.rect = rect;
        }
    }
    public static final class Timeline {
        public final String visitId;
        public final int startFrame, durationFrames, transitionFrames, overlapFrames;
        private Timeline(String visitId, int startFrame, int durationFrames, int transitionFrames, int overlapFrames) {
            this.visitId = visitId; this.startFrame = startFrame; this.durationFrames = durationFrames;
            this.transitionFrames = transitionFrames; this.overlapFrames = overlapFrames;
        }
    }
    public static int millisecondsToFrames(long ms) {
        require(ms > 0 && ms <= ViewerPackageCodec.MAX_TRANSITION_MS, "Invalid transition duration");
        int frames = (int)((ms * FPS + 500) / 1000);
        require(frames > 0, "Transition rounds to zero frames"); return frames;
    }
    public static RenderPlan build(ViewerScene scene, int width, int height, List<Visit> visits, List<Effect> effects) {
        ViewerPackageCodec.validateScene(scene);
        require(width == 1080 && height == 1920 || width == 1920 && height == 1080, "Unsupported canvas preset");
        require(visits != null && !visits.isEmpty() && visits.size() <= MAX_VISITS, "Finite path must have 1 to 256 visits");
        require(effects != null && effects.size() <= MAX_EFFECTS, "Too many animation effects");
        Map<String, ViewerScene.State> states = new HashMap<>(); for (ViewerScene.State state : scene.states) states.put(state.id, state);
        Map<String, ViewerScene.Edge> edges = new HashMap<>(); for (ViewerScene.Edge edge : scene.edges) edges.put(edge.id, edge);
        Map<String, ViewerScene.Asset> assets = new HashMap<>(); for (ViewerScene.Asset asset : scene.assets) assets.put(asset.id, asset);
        Map<String, Visit> byVisit = new HashMap<>(); Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < visits.size(); i++) {
            Visit visit = visits.get(i); require(visit != null, "Missing visit"); id(visit.visitId);
            require(byVisit.put(visit.visitId, visit) == null, "Repeated state visits need distinct visitId values"); indexes.put(visit.visitId, i);
            ViewerScene.State state = states.get(visit.stateId); require(state != null, "Visit references missing state");
            require(visit.holdFrames >= 1 && visit.holdFrames <= MAX_HOLD_FRAMES, "Hold must be 1 to 1800 frames");
            if (i == 0) require(visit.stateId.equals(scene.startStateId), "Path must begin at release start");
            if (visit.selectedEdgeId == null) require(i == visits.size() - 1 && state.terminal, "Path must explicitly reach an ending");
            else {
                ViewerScene.Edge edge = edges.get(visit.selectedEdgeId);
                require(edge != null && edge.fromStateId.equals(visit.stateId), "Selected edge is not on this visit");
                if (edge.toStateId == null) require(i == visits.size() - 1, "Path continues beyond an explicit end");
                else require(i + 1 < visits.size() && visits.get(i + 1) != null && edge.toStateId.equals(visits.get(i + 1).stateId), "Path skips or changes selected edge destination");
            }
        }
        Map<String, Integer> overlaps = new HashMap<>();
        for (Effect effect : effects) {
            require(effect != null, "Missing effect"); Visit visit = byVisit.get(effect.visitId);
            require(visit != null, "Effect references missing visit");
            require(effect.startFrame >= 0 && effect.durationFrames > 0 && (long)effect.startFrame + effect.durationFrames <= visit.holdFrames, "Effect exceeds visit hold interval");
            if (effect.rect != null) rect(effect.rect);
            switch (effect.type == null ? "" : effect.type) {
                case "click": {
                    ViewerScene.Edge selected = edges.get(visit.selectedEdgeId);
                    require(effect.hotspotId != null && effect.regionId == null && effect.text == null && effect.rect == null,
                            "Click accepts only its selected hotspot");
                    require(selected != null && effect.hotspotId.equals(selected.hotspotId), "Click must refer to selected tap hotspot");
                    break;
                }
                case "focus": case "highlight": {
                    require(effect.hotspotId == null && effect.text == null && (effect.regionId == null) != (effect.rect == null), "Focus and highlight need exactly one region or rectangle");
                    if (effect.regionId != null) {
                        boolean found = false;
                        for (ViewerScene.Region region : scene.regions) if (region.id.equals(effect.regionId) && region.stateId.equals(visit.stateId)) found = true;
                        require(found, "Effect region is not on this visit");
                    }
                    break;
                }
                case "annotation":
                    require(effect.hotspotId == null && effect.regionId == null && effect.rect != null && effect.text != null,
                            "Annotation needs plain text and a rectangle");
                    StrictJson.validUnicode(effect.text);
                    require(!effect.text.trim().isEmpty() && effect.text.length() <= 240, "Invalid annotation text"); break;
                case "transition": {
                    int index = indexes.get(effect.visitId); ViewerScene.Edge edge = edges.get(visit.selectedEdgeId);
                    require(effect.hotspotId == null && effect.regionId == null && effect.text == null && effect.rect == null,
                            "Transition cannot carry object references or text");
                    require(edge != null && edge.toStateId != null && edge.transitionAssetId == null && index + 1 < visits.size(), "Crossfade requires a static edge to a next visit");
                    require(effect.startFrame + effect.durationFrames == visit.holdFrames && effect.durationFrames < visit.holdFrames
                            && effect.durationFrames < visits.get(index + 1).holdFrames, "Crossfade must end at hold end and leave visible holds");
                    require(overlaps.put(effect.visitId, effect.durationFrames) == null, "Only one crossfade per visit"); break;
                }
                default: throw new IllegalArgumentException("Unsupported effect type");
            }
        }
        // Preserve a private hold frame between incoming/outgoing fades. This also
        // bounds simultaneous image layers to two, even for short repeated visits.
        for (int i = 0; i < visits.size(); i++) {
            Visit visit = visits.get(i);
            int incoming = i == 0 ? 0 : overlaps.getOrDefault(visits.get(i - 1).visitId, 0);
            int outgoing = overlaps.getOrDefault(visit.visitId, 0);
            require(incoming + outgoing < visit.holdFrames, "Adjacent crossfades must leave one independent hold frame");
        }
        List<Timeline> timeline = new ArrayList<>(); int cursor = 0;
        for (Visit visit : visits) {
            ViewerScene.Edge edge = edges.get(visit.selectedEdgeId);
            int transition = edge == null || edge.transitionAssetId == null ? 0 : millisecondsToFrames(assets.get(edge.transitionAssetId).durationMs);
            int overlap = overlaps.getOrDefault(visit.visitId, 0), duration = visit.holdFrames + transition;
            timeline.add(new Timeline(visit.visitId, cursor, duration, transition, overlap)); cursor += duration - overlap;
            require(cursor <= MAX_TOTAL_FRAMES, "Animation exceeds 18000 frames (10 minutes)");
        }
        RenderPlan plan = new RenderPlan(scene, width, height, visits, effects, timeline, cursor);
        byte[] bytes = StrictJson.canonical(plan.object()); StrictJson.parse(bytes, MAX_BYTES); return plan;
    }
    public byte[] toBytes() { return StrictJson.canonical(object()); }
    public static RenderPlan parse(ViewerScene scene, byte[] bytes) {
        Map<String, Object> root = object(StrictJson.parse(bytes, MAX_BYTES), "schemaVersion", "adapterVersion", "compositionId", "releaseId", "contentDigest", "fps", "canvas", "visits", "effects", "timeline", "totalFrames");
        require(number(root.get("schemaVersion")) == SCHEMA_VERSION && ADAPTER_VERSION.equals(root.get("adapterVersion"))
                && COMPOSITION_ID.equals(root.get("compositionId")) && number(root.get("fps")) == FPS, "Unsupported animation profile");
        require(scene.releaseId.equals(root.get("releaseId")) && ViewerPackageCodec.contentDigest(scene).equals(root.get("contentDigest")), "Render plan belongs to another release");
        Map<String, Object> canvas = object(root.get("canvas"), "width", "height");
        List<Visit> visits = new ArrayList<>();
        for (Object raw : array(root.get("visits"))) {
            Map<String, Object> visit = object(raw, "visitId", "stateId", "selectedEdgeId", "holdFrames");
            visits.add(new Visit(string(visit.get("visitId")), string(visit.get("stateId")), nullable(visit.get("selectedEdgeId")), number(visit.get("holdFrames"))));
        }
        List<Effect> effects = new ArrayList<>();
        for (Object raw : array(root.get("effects"))) {
            Map<String, Object> effect = object(raw, "type", "visitId", "startFrame", "durationFrames", "hotspotId", "regionId", "text", "rect");
            ViewerScene.Rect rect = null;
            if (effect.get("rect") != null) {
                Map<String, Object> r = object(effect.get("rect"), "x", "y", "width", "height");
                rect = new ViewerScene.Rect(decimal(r.get("x")), decimal(r.get("y")), decimal(r.get("width")), decimal(r.get("height")));
            }
            effects.add(new Effect(string(effect.get("type")), string(effect.get("visitId")), number(effect.get("startFrame")), number(effect.get("durationFrames")),
                    nullable(effect.get("hotspotId")), nullable(effect.get("regionId")), nullable(effect.get("text")), rect));
        }
        RenderPlan plan = build(scene, number(canvas.get("width")), number(canvas.get("height")), visits, effects);
        require(number(root.get("totalFrames")) == plan.totalFrames, "Total frame count differs from resolved path");
        List<Object> supplied = array(root.get("timeline")); require(supplied.size() == plan.timeline.size(), "Timeline visit count mismatch");
        for (int i = 0; i < supplied.size(); i++) {
            Map<String, Object> row = object(supplied.get(i), "visitId", "startFrame", "durationFrames", "transitionFrames", "overlapFrames");
            Timeline expected = plan.timeline.get(i);
            require(expected.visitId.equals(row.get("visitId")) && expected.startFrame == number(row.get("startFrame")) && expected.durationFrames == number(row.get("durationFrames"))
                    && expected.transitionFrames == number(row.get("transitionFrames")) && expected.overlapFrames == number(row.get("overlapFrames")), "Timeline is not derived from visits and media");
        }
        return plan;
    }
    private Map<String, Object> object() {
        List<Object> v = new ArrayList<>(), e = new ArrayList<>(), t = new ArrayList<>();
        for (Visit a : visits) v.add(obj("visitId", a.visitId, "stateId", a.stateId, "selectedEdgeId", a.selectedEdgeId, "holdFrames", a.holdFrames));
        for (Effect a : effects) e.add(obj("type", a.type, "visitId", a.visitId, "startFrame", a.startFrame, "durationFrames", a.durationFrames,
                "hotspotId", a.hotspotId, "regionId", a.regionId, "text", a.text, "rect", a.rect == null ? null : obj("x", BigDecimal.valueOf(a.rect.x), "y", BigDecimal.valueOf(a.rect.y), "width", BigDecimal.valueOf(a.rect.width), "height", BigDecimal.valueOf(a.rect.height))));
        for (Timeline a : timeline) t.add(obj("visitId", a.visitId, "startFrame", a.startFrame, "durationFrames", a.durationFrames, "transitionFrames", a.transitionFrames, "overlapFrames", a.overlapFrames));
        return obj("schemaVersion", SCHEMA_VERSION, "adapterVersion", ADAPTER_VERSION, "compositionId", COMPOSITION_ID, "releaseId", releaseId, "contentDigest", contentDigest,
                "fps", FPS, "canvas", obj("width", width, "height", height), "visits", v, "effects", e, "timeline", t, "totalFrames", totalFrames);
    }
    private static void id(String value) { require(value != null && UUID.matcher(value).matches(), "Invalid visit UUID"); }
    private static void rect(ViewerScene.Rect r) {
        double[] values = {r.x, r.y, r.width, r.height}; for (double value : values) {
            require(Double.isFinite(value) && value >= 0 && value <= 1 && BigDecimal.valueOf(value).stripTrailingZeros().scale() <= 6, "Invalid normalized effect rectangle");
        }
        require(r.width > 0 && r.height > 0 && BigDecimal.valueOf(r.x).add(BigDecimal.valueOf(r.width)).compareTo(BigDecimal.ONE) <= 0
                && BigDecimal.valueOf(r.y).add(BigDecimal.valueOf(r.height)).compareTo(BigDecimal.ONE) <= 0, "Effect rectangle exceeds state image");
    }
    static Map<String, Object> obj(Object... fields) { Map<String, Object> result = new LinkedHashMap<>(); for (int i=0;i<fields.length;i+=2) result.put((String)fields[i], fields[i+1]); return result; }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object raw, String... fields) {
        require(raw instanceof Map, "Expected JSON object"); Map<String,Object> value=(Map<String,Object>)raw;
        require(value.keySet().equals(new HashSet<>(Arrays.asList(fields))), "Missing or unsupported JSON field"); return value;
    }
    @SuppressWarnings("unchecked") static List<Object> array(Object raw) { require(raw instanceof List, "Expected JSON array"); return (List<Object>)raw; }
    static String string(Object raw) { require(raw instanceof String, "Expected JSON string"); return (String)raw; }
    private static String nullable(Object raw) { return raw == null ? null : string(raw); }
    static int number(Object raw) { require(raw instanceof BigDecimal && ((BigDecimal)raw).scale() <= 0, "Expected integer frame count"); try { return ((BigDecimal)raw).intValueExact(); } catch (ArithmeticException e) { throw new IllegalArgumentException("Integer exceeds frame budget", e); } }
    private static double decimal(Object raw) { require(raw instanceof BigDecimal && ((BigDecimal)raw).scale() <= 6, "Invalid effect coordinate precision"); return ((BigDecimal)raw).doubleValue(); }
}
