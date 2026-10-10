package com.tapscene.packageformat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lossless static-draft compatibility and human review for an already validated AI package.
 * This is not a parser or a media/trust validator: call AiPackageCodec.readPackage first.
 * The caller alone selects a local baseline. A matching external release ID is not proof
 * that any local project, author, review, or render plan is its source.
 */
public final class AiDraftImportPolicy {
    private AiDraftImportPolicy() {}

    // Keep aligned with ProjectStore.text/saveStepDraft/saveRegion, not the looser viewer limits.
    public static final int TITLE_LIMIT = 120, GOAL_LIMIT = 1000, DESCRIPTION_LIMIT = 4000;
    public static final int LABEL_LIMIT = 120, END_LABEL_LIMIT = 300, REGION_TEXT_LIMIT = 120;
    public static final String TRUST_NOTICE =
            "包完整性校验只证明文件符合格式且内容一致，不认证作者身份或隐私安全。"
            + "sourceKind 仅是外部来源声明，导入步骤统一标记为 imported；全部画面、文字、热点、连线和区域仍需在本机重新复核。"
            + "将新建独立项目，不覆盖原草稿或旧成品。";
    public static final String NO_BASELINE_NOTICE =
            "未提供明确的本机基线，无法判断外部修改了哪些内容；以下为完整待导入摘要，不是差异结论。";
    public static final String PATH_NOTICE =
            "路径、画布、停留、效果和时间轴按包内计划原样列示；没有本机计划基线，不声明路径差异。"
            + "访问序列只作参考，不展开为步骤，也不会裁掉未访问分支。";

    public static final class Issue {
        public final String code, subjectId, message;
        public Issue(String code, String subjectId, String message) {
            this.code = Objects.requireNonNull(code); this.subjectId = Objects.requireNonNull(subjectId);
            this.message = Objects.requireNonNull(message);
        }
        @Override public String toString() { return message + " [" + subjectId + "]"; }
    }

    /** before/after are null only for an absent field/object or an actual nullable field. */
    public static final class Difference {
        public final String category, subjectId, field, before, after;
        public Difference(String category, String subjectId, String field, String before, String after) {
            this.category = category; this.subjectId = subjectId; this.field = field;
            this.before = before; this.after = after;
        }
    }

    public static final class Preview {
        public final List<Issue> issues;
        public final List<Difference> differences;
        public final String summary, trustNotice;
        public final boolean hasBaseline;
        private Preview(List<Issue> issues, List<Difference> differences, String summary, boolean hasBaseline) {
            this.issues = frozen(issues); this.differences = frozen(differences);
            this.summary = summary; this.trustNotice = TRUST_NOTICE; this.hasBaseline = hasBaseline;
        }
    }

    /** Collect every editor-specific incompatibility; do not silently trim, truncate or rewrite. */
    public static List<Issue> inspect(ViewerScene scene) {
        Objects.requireNonNull(scene, "scene");
        List<Issue> issues = new ArrayList<>();
        checkText(issues, "project", "项目名称", scene.title, TITLE_LIMIT, false);
        checkText(issues, "project", "项目目标", scene.goal, GOAL_LIMIT, true);
        Map<String, ViewerScene.Hotspot> hotspots = new LinkedHashMap<>();
        for (ViewerScene.Asset asset : scene.assets) {
            if (ViewerScene.Asset.ROLE_TRANSITION.equals(asset.role) || "video/mp4".equals(asset.mime)
                    || asset.durationMs != null) {
                issues.add(new Issue("VIDEO_ASSET", asset.id, "静态 AI 回流不支持视频资产；请移除视频并重新生成完整包。"));
            }
        }
        for (ViewerScene.State state : scene.states) {
            checkText(issues, state.id, "步骤标题", state.title, TITLE_LIMIT, false);
            checkText(issues, state.id, "步骤说明", state.description, DESCRIPTION_LIMIT, true);
        }
        for (ViewerScene.Hotspot hotspot : scene.hotspots) {
            hotspots.put(hotspot.id, hotspot);
            checkText(issues, hotspot.id, "热点标签", hotspot.label, LABEL_LIMIT, false);
            if (!roundTripsThroughEditor(hotspot.rect)) {
                issues.add(new Issue("HOTSPOT_PRECISION", hotspot.id,
                        "热点矩形无法经本机编辑器浮点坐标及六位舍入无损保留；请调整矩形后重新生成包。"));
            }
        }
        for (ViewerScene.Edge edge : scene.edges) {
            checkText(issues, edge.id, "连线标签", edge.label, LABEL_LIMIT, false);
            if (edge.endLabel != null) checkText(issues, edge.id, "结束说明", edge.endLabel, END_LABEL_LIMIT, false);
            if (edge.transitionAssetId != null) {
                issues.add(new Issue("VIDEO_EDGE", edge.id, "静态 AI 回流不支持带视频过渡的连线。"));
            }
            if (!"authored".equals(edge.sourceKind)) {
                issues.add(new Issue("EDGE_SOURCE_KIND", edge.id,
                        "本机编辑器只支持 authored 作者编排连线，不能无损保留此连线的外部来源声明。"));
            }
            if ("continue".equals(edge.trigger) && edge.toStateId == null) {
                issues.add(new Issue("CONTINUE_TO_END", edge.id,
                        "本机下一步动作必须指向步骤，不能将 continue 直接结束转换为缺失目标。"));
            }
            ViewerScene.Hotspot hotspot = hotspots.get(edge.hotspotId);
            if (hotspot != null && !Objects.equals(hotspot.label, edge.label)) {
                issues.add(new Issue("HOTSPOT_EDGE_LABEL", edge.id,
                        "热点与其连线标签不同，本机编辑器共用一个标签，无法无损导入。"));
            }
        }
        for (ViewerScene.Region region : scene.regions) {
            checkText(issues, region.id, "区域名称", region.name, REGION_TEXT_LIMIT, false);
            if (region.group != null) checkText(issues, region.id, "区域分组", region.group, REGION_TEXT_LIMIT, false);
        }
        return frozen(issues);
    }

    public static Preview review(ViewerScene scene, RenderPlan plan, ViewerScene baseline) {
        Objects.requireNonNull(scene, "scene"); Objects.requireNonNull(plan, "plan");
        return new Preview(inspect(scene), compare(scene, baseline), summarize(scene, plan, baseline), baseline != null);
    }

    /**
     * Compare incoming content against the explicitly selected baseline. Stable object IDs
     * identify their subjects. Asset IDs, package paths, release IDs and creation timestamps
     * are deliberately absent; picture identity is its SHA-256 and dimensions.
     * No plan baseline is accepted, so this method never invents a path difference.
     */
    public static List<Difference> compare(ViewerScene incoming, ViewerScene baseline) {
        Objects.requireNonNull(incoming, "incoming");
        if (baseline == null) return Collections.emptyList();
        Map<Key, String> before = content(baseline), after = content(incoming);
        Set<Key> keys = new LinkedHashSet<>(before.keySet()); keys.addAll(after.keySet());
        List<Difference> result = new ArrayList<>();
        for (Key key : keys) {
            String previous = before.get(key), next = after.get(key);
            if (!Objects.equals(previous, next)) {
                result.add(new Difference(key.category, key.subject, key.field, previous, next));
            }
        }
        return frozen(result);
    }

    /** Complete plain-text inventory: never truncate content, omit unvisited branches, or unfold visits. */
    public static String summarize(ViewerScene scene, RenderPlan plan, ViewerScene baseline) {
        Objects.requireNonNull(scene, "scene"); Objects.requireNonNull(plan, "plan");
        StringBuilder text = new StringBuilder();
        line(text, TRUST_NOTICE);
        line(text, baseline == null ? NO_BASELINE_NOTICE : "仅与明确提供的本机基线比较；相同 releaseId 不代表可信来源。");
        line(text, "来源包版本：" + scene.releaseId + "；声明创建时间：" + scene.createdAt);
        line(text, "格式：" + scene.schemaVersion + " / " + scene.policyVersion + " / " + scene.compilerVersion);
        line(text, "项目名称：" + quoted(scene.title)); line(text, "项目目标：" + quoted(scene.goal));
        line(text, "起点：" + scene.startStateId);
        line(text, "完整图：" + scene.states.size() + " 个步骤，" + scene.hotspots.size() + " 个热点，"
                + scene.edges.size() + " 条连线，" + scene.regions.size() + " 个区域，" + scene.assets.size() + " 个资产。");
        int order = 0;
        for (ViewerScene.State state : scene.states) {
            line(text, "步骤 " + (++order) + "：" + state.id + "；标题=" + quoted(state.title));
            line(text, "  说明=" + quoted(state.description) + "；结束步骤=" + state.terminal
                    + "；尺寸=" + state.width + "×" + state.height + "；图片=" + state.imageAssetId
                    + "；外部来源声明=" + state.sourceKind);
        }
        for (ViewerScene.Hotspot hotspot : scene.hotspots) line(text,
                "热点：" + hotspot.id + "；步骤=" + hotspot.stateId + "；标签=" + quoted(hotspot.label) + "；矩形=" + rect(hotspot.rect));
        for (ViewerScene.Edge edge : scene.edges) line(text,
                "连线：" + edge.id + "；来源步骤=" + edge.fromStateId + "；目标步骤=" + nullable(edge.toStateId)
                        + "；结束说明=" + quoted(edge.endLabel) + "；热点=" + nullable(edge.hotspotId)
                        + "；标签=" + quoted(edge.label) + "；触发=" + edge.trigger + "；外部来源声明=" + edge.sourceKind
                        + "；视频资产=" + nullable(edge.transitionAssetId));
        for (ViewerScene.Region region : scene.regions) line(text,
                "区域：" + region.id + "；步骤=" + region.stateId + "；名称=" + quoted(region.name)
                        + "；分组=" + quoted(region.group) + "；底图=" + region.baseAssetId + "；裁片=" + region.assetId
                        + "；底图尺寸=" + region.sourceWidth + "×" + region.sourceHeight + "；像素矩形=" + box(region.bbox)
                        + "；层级=" + region.zIndex + "；裁片局部锚点=" + anchor(region.anchor));
        for (ViewerScene.Asset asset : scene.assets) line(text,
                "资产：" + asset.id + "；包内路径=" + asset.path + "；用途=" + asset.role + "；格式=" + asset.mime
                        + "；字节=" + asset.byteLength + "；SHA-256=" + asset.sha256 + "；尺寸=" + asset.width + "×" + asset.height
                        + "；时长毫秒=" + nullable(asset.durationMs));
        line(text, PATH_NOTICE);
        line(text, "计划绑定：" + plan.releaseId + "；内容摘要=" + plan.contentDigest);
        line(text, "画布：" + plan.width + "×" + plan.height + "；帧率=" + RenderPlan.FPS + "；总帧数=" + plan.totalFrames);
        order = 0;
        for (RenderPlan.Visit visit : plan.visits) line(text,
                "访问 " + (++order) + "：" + visit.visitId + "；步骤=" + visit.stateId + "；所选连线="
                        + nullable(visit.selectedEdgeId) + "；停留帧=" + visit.holdFrames);
        order = 0;
        for (RenderPlan.Effect effect : plan.effects) line(text,
                "效果 " + (++order) + "：" + effect.type + "；访问=" + effect.visitId + "；起帧=" + effect.startFrame
                        + "；帧数=" + effect.durationFrames + "；热点=" + nullable(effect.hotspotId) + "；区域=" + nullable(effect.regionId)
                        + "；文字=" + quoted(effect.text) + "；矩形=" + (effect.rect == null ? "无" : rect(effect.rect)));
        for (RenderPlan.Timeline timeline : plan.timeline) line(text,
                "时间轴：" + timeline.visitId + "；起帧=" + timeline.startFrame + "；帧数=" + timeline.durationFrames
                        + "；视频帧=" + timeline.transitionFrames + "；重叠帧=" + timeline.overlapFrames);
        return text.toString();
    }

    private static void checkText(List<Issue> issues, String id, String label, String value, int limit, boolean allowEmpty) {
        if (value == null) { issues.add(new Issue("TEXT_MISSING", id, label + "缺失，无法导入。")); return; }
        int start = 0, end = value.length();
        // Kotlin Char.isWhitespace() is Character.isWhitespace || Character.isSpaceChar.
        while (start < end && whitespace(value.charAt(start))) start++;
        while (end > start && whitespace(value.charAt(end - 1))) end--;
        if (start != 0 || end != value.length()) issues.add(new Issue("TEXT_TRIM_CHANGE", id,
                label + "含首尾空白，本机保存会 trim 并改变原文，无法无损导入。"));
        if (!allowEmpty && start == end) issues.add(new Issue("TEXT_EMPTY", id, label + "不能为空或全为空白。"));
        if (value.length() > limit) issues.add(new Issue("TEXT_TOO_LONG", id, label + "超过本机编辑器 " + limit + " 字限制。"));
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t') {
                issues.add(new Issue("TEXT_CONTROL_CHARACTER", id, label + "含本机不支持的控制字符。")); break;
            }
        }
    }
    private static boolean whitespace(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }
    private static boolean roundTripsThroughEditor(ViewerScene.Rect r) {
        if (r == null) return false;
        double left = six((float) r.x), top = six((float) r.y);
        double right = six((float) (r.x + r.width)), bottom = six((float) (r.y + r.height));
        return right > left && bottom > top && same(left, r.x) && same(top, r.y)
                && same(six(right - left), r.width) && same(six(bottom - top), r.height);
    }
    private static double six(double value) { return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue(); }
    private static boolean same(double a, double b) { return BigDecimal.valueOf(a).compareTo(BigDecimal.valueOf(b)) == 0; }

    private static Map<Key, String> content(ViewerScene scene) {
        Map<Key, String> result = new LinkedHashMap<>(); Map<String, ViewerScene.Asset> assets = new LinkedHashMap<>();
        for (ViewerScene.Asset asset : scene.assets) assets.put(asset.id, asset);
        put(result, "text", "project", "title", scene.title); put(result, "text", "project", "goal", scene.goal);
        put(result, "state", "project", "startStateId", scene.startStateId);
        List<String> order = new ArrayList<>(); for (ViewerScene.State state : scene.states) order.add(state.id);
        put(result, "state", "project", "order", String.join(",", order));
        for (ViewerScene.State state : scene.states) {
            put(result, "text", state.id, "title", state.title); put(result, "text", state.id, "description", state.description);
            put(result, "state", state.id, "terminal", Boolean.toString(state.terminal));
            put(result, "state", state.id, "sourceKindDeclaration", state.sourceKind);
            put(result, "image", state.id, "image", picture(assets.get(state.imageAssetId)));
        }
        for (ViewerScene.Hotspot hotspot : scene.hotspots) {
            put(result, "text", hotspot.id, "label", hotspot.label);
            put(result, "hotspot", hotspot.id, "stateId", hotspot.stateId); put(result, "hotspot", hotspot.id, "rect", rect(hotspot.rect));
        }
        for (ViewerScene.Edge edge : scene.edges) {
            put(result, "text", edge.id, "label", edge.label); put(result, "text", edge.id, "endLabel", edge.endLabel);
            put(result, "edge", edge.id, "fromStateId", edge.fromStateId); put(result, "edge", edge.id, "toStateId", edge.toStateId);
            put(result, "edge", edge.id, "hotspotId", edge.hotspotId); put(result, "edge", edge.id, "trigger", edge.trigger);
            put(result, "edge", edge.id, "sourceKindDeclaration", edge.sourceKind);
            put(result, "edge", edge.id, "transition", edge.transitionAssetId == null ? null : picture(assets.get(edge.transitionAssetId)));
        }
        for (ViewerScene.Region region : scene.regions) {
            put(result, "text", region.id, "name", region.name); put(result, "text", region.id, "group", region.group);
            put(result, "region", region.id, "stateId", region.stateId);
            put(result, "region", region.id, "baseImage", picture(assets.get(region.baseAssetId)));
            put(result, "region", region.id, "cropImage", picture(assets.get(region.assetId)));
            put(result, "region", region.id, "sourceDimensions", region.sourceWidth + "×" + region.sourceHeight);
            put(result, "region", region.id, "bbox", box(region.bbox));
            put(result, "region", region.id, "zIndex", Integer.toString(region.zIndex));
            put(result, "region", region.id, "anchor", anchor(region.anchor));
        }
        return result;
    }
    private static void put(Map<Key, String> target, String category, String subject, String field, String value) {
        target.put(new Key(category, subject, field), value);
    }
    private static final class Key {
        final String category, subject, field;
        Key(String category, String subject, String field) { this.category = category; this.subject = subject; this.field = field; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other; return category.equals(key.category) && subject.equals(key.subject) && field.equals(key.field);
        }
        @Override public int hashCode() { return Objects.hash(category, subject, field); }
    }
    private static String picture(ViewerScene.Asset asset) {
        Objects.requireNonNull(asset, "validated scene asset");
        return "SHA-256=" + asset.sha256 + "; dimensions=" + asset.width + "×" + asset.height
                + (asset.durationMs == null ? "" : "; durationMs=" + asset.durationMs);
    }
    private static String rect(ViewerScene.Rect r) { return "x=" + number(r.x) + ", y=" + number(r.y) + ", width=" + number(r.width) + ", height=" + number(r.height); }
    private static String box(ViewerScene.PixelRect r) { return "x=" + r.x + ", y=" + r.y + ", width=" + r.width + ", height=" + r.height; }
    private static String anchor(ViewerScene.Anchor a) { return "x=" + number(a.x) + ", y=" + number(a.y); }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String nullable(Object value) { return value == null ? "无" : value.toString(); }
    private static String quoted(String value) {
        if (value == null) return "无";
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (Character.isISOControl(c)) {
                        String hex = Integer.toHexString(c); result.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) result.append('0'); result.append(hex);
                    } else result.append(c);
            }
        }
        return result.append('"').toString();
    }
    private static void line(StringBuilder text, String line) { text.append(line).append('\n'); }
    private static <T> List<T> frozen(List<T> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }
}
