import com.tapscene.packageformat.AiDraftImportPolicy;
import com.tapscene.packageformat.AiPackageCodec;
import com.tapscene.packageformat.RenderPlan;
import com.tapscene.packageformat.ViewerPackageCodec;
import com.tapscene.packageformat.ViewerScene;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/** Host-only checks for the exact Java policy used by the Android import boundary. */
public final class AiDraftImportChecks {
    private static final ViewerPackageCodec.CancelCheck NO_CANCEL = () -> {};
    private static int checks;
    private AiDraftImportChecks() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass a new fixture directory");
        Path root = Files.createDirectory(Path.of(args[0])), source = Files.createDirectory(root.resolve("source"));
        Files.createDirectory(source.resolve("assets"));
        ViewerScene scene = fixture(source);
        RenderPlan plan = plan(scene);
        byte[] original = ViewerPackageCodec.writeScene(scene);
        require(AiDraftImportPolicy.inspect(scene).isEmpty(), "Authored static graph should be losslessly editable");
        Path archive = root.resolve("static.tapscene-ai");
        AiPackageCodec.writePackage(scene, plan, source.toFile(), archive.toFile(), NO_CANCEL, null);
        Path extraction = Files.createDirectory(root.resolve("validated"));
        AiPackageCodec.LoadedPackage loaded = AiPackageCodec.readPackage(archive.toFile(), extraction.toFile(), NO_CANCEL, null);
        require(AiDraftImportPolicy.inspect(loaded.scene).isEmpty(), "Validated complete package became incompatible");
        require(Arrays.equals(original, ViewerPackageCodec.writeScene(loaded.scene)), "Core codec changed scene content");
        AiDraftImportPolicy.Preview review = AiDraftImportPolicy.review(loaded.scene, loaded.renderPlan, null);
        require(!review.hasBaseline && review.differences.isEmpty(), "No baseline generated invented differences");
        require(review.summary.contains(AiDraftImportPolicy.NO_BASELINE_NOTICE), "No-baseline caveat missing");
        require(review.summary.contains("不认证作者身份或隐私安全") && review.trustNotice.contains("sourceKind 仅是外部来源声明"), "Trust boundary missing");
        require(review.summary.contains("没有本机计划基线，不声明路径差异"), "Path baseline caveat missing");
        for (ViewerScene.State state : scene.states) require(review.summary.contains(state.id) && review.summary.contains(state.title), "State missing from inventory");
        for (ViewerScene.Edge edge : scene.edges) require(review.summary.contains(edge.id), "Edge missing from inventory");
        for (ViewerScene.Region region : scene.regions) require(review.summary.contains(region.id), "Region missing from inventory");
        for (ViewerScene.Asset asset : scene.assets) require(review.summary.contains(asset.sha256) && review.summary.contains(asset.path), "Asset missing from inventory");
        for (RenderPlan.Visit visit : plan.visits) require(review.summary.contains(visit.visitId), "Visit missing from inventory");
        require(review.summary.contains("不可截断的标注内容") && review.summary.contains("时间轴："), "Effects or timeline omitted");
        require(scene.states.size() == 4 && plan.visits.size() == 4
                && plan.visits.get(0).stateId.equals(plan.visits.get(2).stateId), "Finite revisit fixture broken");
        require(loaded.scene.states.size() == 4 && loaded.scene.states.stream().anyMatch(s -> s.id.equals(id(14))),
                "Unvisited branch removed or revisit expanded into a new state");
        RenderPlan shortPlan = RenderPlan.build(scene, 1920, 1080,
                List.of(visit(81, 11, 42), visit(82, 13, null)), List.of());
        AiDraftImportPolicy.Preview shortReview = AiDraftImportPolicy.review(scene, shortPlan, scene);
        require(shortReview.differences.isEmpty() && shortReview.summary.contains("完整图：4 个步骤"),
                "Short path pruned the full scene or fabricated a path difference");
        require(Arrays.equals(original, ViewerPackageCodec.writeScene(scene)), "Policy mutated scene");
        rejected("immutable issue list", () -> review.issues.add(new AiDraftImportPolicy.Issue("x", "x", "x")));
        rejected("immutable difference list", () -> review.differences.add(new AiDraftImportPolicy.Difference("x", "x", "x", null, null)));

        compatibility(scene);
        differences(scene, plan);
        String sceneJson = new String(original, StandardCharsets.UTF_8);
        rejected("unknown scene structure stays in strict codec", () -> ViewerPackageCodec.parseScene(
                sceneJson.replace("\"goal\":", "\"executable\":\"run()\",\"goal\":").getBytes(StandardCharsets.UTF_8)));
        rejected("unknown plan structure stays in strict codec", () -> RenderPlan.parse(scene,
                new String(plan.toBytes(), StandardCharsets.UTF_8).replace("\"fps\":30", "\"fps\":30,\"script\":\"run()\"").getBytes(StandardCharsets.UTF_8)));
        System.out.println("TAPSCENE_AI_DRAFT_IMPORT_CHECKS_PASSED: " + checks + " assertions/rejections; fixtures=" + root);
    }

    private static void compatibility(ViewerScene original) throws Exception {
        Change c = new Change(original); c.title = repeat(121); issue(c.scene(), "TEXT_TOO_LONG", "project", "项目名称");
        c = new Change(original); c.goal = repeat(1001); issue(c.scene(), "TEXT_TOO_LONG", "project", "项目目标");
        c = new Change(original); c.states.set(0, state(original.states.get(0), repeat(121), "", "authored", false));
        issue(c.scene(), "TEXT_TOO_LONG", id(11), "步骤标题");
        c = new Change(original); c.states.set(0, state(original.states.get(0), "Start", repeat(4001), "authored", false));
        issue(c.scene(), "TEXT_TOO_LONG", id(11), "步骤说明");
        c = new Change(original); replaceLabels(c, repeat(121));
        issue(c.scene(), "TEXT_TOO_LONG", id(31), "热点标签"); issue(c.scene(), "TEXT_TOO_LONG", id(41), "连线标签");
        c = new Change(original); ViewerScene.Edge old = c.edges.get(1);
        c.edges.set(1, new ViewerScene.Edge(old.id, old.fromStateId, null, repeat(240), old.hotspotId, old.label, old.trigger, old.sourceKind));
        require(AiDraftImportPolicy.inspect(c.scene()).isEmpty(), "Codec-legal 240-character end label was narrowed below actual editor limit 300");
        c = new Change(original); old = c.edges.get(1);
        c.edges.set(1, new ViewerScene.Edge(old.id, old.fromStateId, null, repeat(301), old.hotspotId, old.label, old.trigger, old.sourceKind));
        ViewerScene oversizedEnd = c.scene();
        require(AiDraftImportPolicy.inspect(oversizedEnd).stream().anyMatch(i -> i.code.equals("TEXT_TOO_LONG") && i.message.contains("结束说明")), "Editor end-label limit not enforced");
        rejected("overlong end label is already blocked by codec", () -> ViewerPackageCodec.validateScene(oversizedEnd));
        c = new Change(original); ViewerScene.Region region = c.regions.get(0);
        c.regions.set(0, region(region, repeat(121), region.group, region.bbox, region.anchor));
        issue(c.scene(), "TEXT_TOO_LONG", region.id, "区域名称");
        c = new Change(original); region = c.regions.get(0);
        c.regions.set(0, region(region, region.name, " group ", region.bbox, region.anchor));
        issue(c.scene(), "TEXT_TRIM_CHANGE", region.id, "区域分组");
        c = new Change(original); region = c.regions.get(0);
        c.regions.set(0, region(region, region.name, repeat(121), region.bbox, region.anchor));
        ViewerScene oversizedGroup = c.scene();
        require(AiDraftImportPolicy.inspect(oversizedGroup).stream().anyMatch(i -> i.code.equals("TEXT_TOO_LONG") && i.message.contains("区域分组")), "Editor region-group limit not enforced");
        rejected("overlong region group is already blocked by codec", () -> ViewerPackageCodec.validateScene(oversizedGroup));
        c = new Change(original); c.title = " title"; issue(c.scene(), "TEXT_TRIM_CHANGE", "project", "项目名称");
        c = new Change(original); c.title = "title\u00a0"; issue(c.scene(), "TEXT_TRIM_CHANGE", "project", "项目名称");
        c = new Change(original); c.title = "title\u2003"; issue(c.scene(), "TEXT_TRIM_CHANGE", "project", "项目名称");
        c = new Change(original); c.goal = " \t "; issue(c.scene(), "TEXT_TRIM_CHANGE", "project", "项目目标");
        for (char control : new char[]{'\0', '\r', 0x7f, 0x85}) {
            c = new Change(original); c.goal = "Before" + control + "After";
            issue(c.scene(), "TEXT_CONTROL_CHARACTER", "project", "项目目标");
        }
        c = new Change(original); c.goal = "Allowed\nline\ttab";
        require(AiDraftImportPolicy.inspect(c.scene()).isEmpty(), "Internal newline/tab was rejected");
        c = new Change(original); old = c.edges.get(2);
        c.edges.set(2, new ViewerScene.Edge(old.id, old.fromStateId, null, "End", null, old.label, "continue", "authored"));
        issue(c.scene(), "CONTINUE_TO_END", old.id, "continue");
        c = new Change(original); old = c.edges.get(0);
        c.edges.set(0, new ViewerScene.Edge(old.id, old.fromStateId, old.toStateId, null, old.hotspotId, "Different", old.trigger, old.sourceKind));
        issue(c.scene(), "HOTSPOT_EDGE_LABEL", old.id, "标签不同");
        for (String kind : List.of("recorded", "imported")) {
            c = new Change(original); old = c.edges.get(0);
            c.edges.set(0, new ViewerScene.Edge(old.id, old.fromStateId, old.toStateId, null, old.hotspotId, old.label, old.trigger, kind));
            issue(c.scene(), "EDGE_SOURCE_KIND", old.id, "来源声明");
        }
        c = new Change(original); old = c.edges.get(0);
        c.assets.add(new ViewerScene.Asset(id(99), "assets/" + id(99) + ".mp4", "video/mp4", 100,
                "a".repeat(64), 64, 96, ViewerScene.Asset.ROLE_TRANSITION, 1000L));
        c.edges.set(0, new ViewerScene.Edge(old.id, old.fromStateId, old.toStateId, null, old.hotspotId,
                old.label, old.trigger, old.sourceKind, id(99)));
        issue(c.scene(), "VIDEO_ASSET", id(99), "视频资产"); issue(c.scene(), "VIDEO_EDGE", old.id, "视频过渡");
        c = new Change(original); c.title = repeat(121); c.goal = "\u00a0leading";
        c.states.set(0, state(original.states.get(0), "Start", "with\rcontrol", "recorded", false));
        replaceLabels(c, repeat(121));
        require(AiDraftImportPolicy.inspect(c.scene()).size() == 5, "Compatibility stopped at first issue rather than aggregating all fields");
        c = new Change(original); c.title = repeat(120); c.goal = repeat(1000);
        c.states.set(0, state(original.states.get(0), repeat(120), repeat(4000), "recorded", false));
        replaceLabels(c, repeat(120)); region = c.regions.get(0);
        c.regions.set(0, region(region, repeat(120), repeat(120), region.bbox, region.anchor));
        require(AiDraftImportPolicy.inspect(c.scene()).isEmpty(), "Exact editor text boundaries were rejected");
        c = new Change(original); ViewerScene.Hotspot hotspot = c.hotspots.get(0);
        c.hotspots.set(0, new ViewerScene.Hotspot(hotspot.id, hotspot.stateId, hotspot.label,
                new ViewerScene.Rect(.999999, .999999, .000001, .000001)));
        require(AiDraftImportPolicy.inspect(c.scene()).isEmpty(), "Smallest legal six-decimal rectangle lost Float round-trip precision");
        // Codec rejects finer coordinates first. Exercise the policy's additional fail-closed
        // editor check directly so future parser precision changes cannot silently lose a box.
        c.hotspots.set(0, new ViewerScene.Hotspot(hotspot.id, hotspot.stateId, hotspot.label,
                new ViewerScene.Rect(.99999999, .5, .00000001, .1)));
        require(AiDraftImportPolicy.inspect(c.scene()).stream().anyMatch(i -> i.code.equals("HOTSPOT_PRECISION")),
                "Collapsed Float rectangle accepted");
        ViewerScene finer = c.scene();
        rejected("finer-than-six coordinates still rejected by codec", () -> ViewerPackageCodec.validateScene(finer));
    }

    private static void differences(ViewerScene original, RenderPlan plan) {
        Change c = new Change(original); c.release = id(500); c.created++;
        require(AiDraftImportPolicy.compare(c.scene(), original).isEmpty(), "Release metadata created false differences");
        c = new Change(original); ViewerScene.Asset asset = c.assets.get(0); String remapped = id(501);
        c.assets.set(0, new ViewerScene.Asset(remapped, "assets/" + remapped + ".png", asset.mime, asset.byteLength,
                asset.sha256, asset.width, asset.height, asset.role, asset.durationMs));
        ViewerScene.State state = c.states.get(0);
        c.states.set(0, new ViewerScene.State(state.id, remapped, state.width, state.height, state.title, state.description, state.sourceKind, state.terminal));
        ViewerScene.Region r = c.regions.get(0);
        c.regions.set(0, new ViewerScene.Region(r.id, r.stateId, remapped, r.assetId, r.name, r.sourceWidth, r.sourceHeight, r.bbox, r.group, r.zIndex, r.anchor));
        require(AiDraftImportPolicy.compare(c.scene(), original).isEmpty(), "Random local asset ID/path created false differences");
        c = new Change(original); asset = c.assets.get(4); remapped = id(502);
        c.assets.set(4, new ViewerScene.Asset(remapped, "assets/" + remapped + ".png", asset.mime, asset.byteLength,
                asset.sha256, asset.width, asset.height, asset.role, asset.durationMs));
        r = c.regions.get(0);
        c.regions.set(0, new ViewerScene.Region(r.id, r.stateId, r.baseAssetId, remapped, r.name, r.sourceWidth, r.sourceHeight, r.bbox, r.group, r.zIndex, r.anchor));
        require(AiDraftImportPolicy.compare(c.scene(), original).isEmpty(), "Random local crop ID/path created false differences");
        c = new Change(original); java.util.Collections.reverse(c.assets); java.util.Collections.reverse(c.edges);
        require(AiDraftImportPolicy.compare(c.scene(), original).isEmpty(), "Non-semantic asset/edge ordering created differences");
        c = new Change(original); c.title = "Changed title";
        difference(c.scene(), original, "text", "project", "title", "Package title", "Changed title");
        c = new Change(original); state = original.states.get(0); c.states.set(0, state(state, state.title, "Changed description", state.sourceKind, false));
        difference(c.scene(), original, "text", state.id, "description", state.description, "Changed description");
        c = new Change(original); c.start = id(12);
        difference(c.scene(), original, "state", "project", "startStateId", id(11), id(12));
        c = new Change(original); ViewerScene.Hotspot h = c.hotspots.get(0);
        c.hotspots.set(0, new ViewerScene.Hotspot(h.id, h.stateId, h.label, new ViewerScene.Rect(.2, .1, .3, .2)));
        require(AiDraftImportPolicy.compare(c.scene(), original).stream().anyMatch(d -> d.category.equals("hotspot") && d.field.equals("rect")), "Hotspot geometry difference missing");
        c = new Change(original); ViewerScene.Edge edge = c.edges.get(0);
        c.edges.set(0, new ViewerScene.Edge(edge.id, edge.fromStateId, id(14), null, edge.hotspotId, edge.label, edge.trigger, edge.sourceKind));
        difference(c.scene(), original, "edge", edge.id, "toStateId", id(12), id(14));
        c = new Change(original); asset = c.assets.get(0);
        c.assets.set(0, new ViewerScene.Asset(asset.id, asset.path, asset.mime, asset.byteLength,
                "b".repeat(64), asset.width, asset.height, asset.role, asset.durationMs));
        require(AiDraftImportPolicy.compare(c.scene(), original).stream().anyMatch(d -> d.category.equals("image")), "Image byte hash difference missing");
        require(AiDraftImportPolicy.compare(c.scene(), original).stream().anyMatch(d -> d.category.equals("region") && d.field.equals("baseImage")), "Region base pixel difference missing");
        c = new Change(original); r = c.regions.get(0);
        c.regions.set(0, region(r, r.name, r.group, new ViewerScene.PixelRect(1, 2, 8, 8), new ViewerScene.Anchor(.2, .7)));
        require(AiDraftImportPolicy.compare(c.scene(), original).stream().anyMatch(d -> d.category.equals("region") && d.field.equals("bbox")), "Region box difference missing");
        require(AiDraftImportPolicy.compare(c.scene(), original).stream().anyMatch(d -> d.category.equals("region") && d.field.equals("anchor")), "Region anchor difference missing");
        c = new Change(original); c.regions.remove(1);
        require(AiDraftImportPolicy.compare(c.scene(), original).stream().anyMatch(d -> d.subjectId.equals(id(52)) && d.after == null), "Removed region missing");
        require(AiDraftImportPolicy.compare(original, c.scene()).stream().anyMatch(d -> d.subjectId.equals(id(52)) && d.before == null), "Added region missing");
        String text = AiDraftImportPolicy.summarize(original, plan, original);
        require(text.contains("仅与明确提供的本机基线比较"), "Explicit baseline wording missing");
        for (AiDraftImportPolicy.Difference d : AiDraftImportPolicy.compare(c.scene(), original)) {
            require(d.before != null || d.after != null, "Empty-to-empty pseudo-difference");
            require(!d.category.equals("path") && !d.field.contains("releaseId") && !d.field.contains("createdAt"), "Invented path or identity diff");
        }
    }

    private static ViewerScene fixture(Path source) throws Exception {
        List<ViewerScene.Asset> assets = new ArrayList<>();
        BufferedImage image = new BufferedImage(64, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, ((x * 3) << 16) | ((y * 2) << 8) | 75);
        for (int i = 21; i <= 24; i++) assets.add(png(source, i, image, ViewerScene.Asset.ROLE_IMAGE));
        assets.add(png(source, 25, image.getSubimage(1, 1, 8, 8), ViewerScene.Asset.ROLE_REGION_CROP));
        assets.add(png(source, 26, image.getSubimage(2, 2, 8, 8), ViewerScene.Asset.ROLE_REGION_CROP));
        return new ViewerScene(3, ViewerPackageCodec.REGION_POLICY_VERSION, ViewerPackageCodec.COMPILER_VERSION,
                id(1), "Package title", "完整图包含未访问分支", 1700000000000L, id(11),
                List.of(new ViewerScene.State(id(11), id(21), 64, 96, "Start", "First state", "recorded", false),
                        new ViewerScene.State(id(12), id(22), 64, 96, "Middle", "Second state", "authored", false),
                        new ViewerScene.State(id(13), id(23), 64, 96, "Finish", "Terminal state", "imported", true),
                        new ViewerScene.State(id(14), id(24), 64, 96, "Unvisited branch", "Must remain", "authored", false)),
                List.of(edge(41, 11, 12, 31, "Open"), edge(42, 11, 13, 32, "Finish"),
                        edge(43, 12, 11, null, "Back"), edge(44, 12, 13, 33, "Save"),
                        edge(45, 11, 14, 34, "Branch"), edge(46, 14, 13, null, "Done")),
                List.of(spot(31, 11, "Open"), spot(32, 11, "Finish"), spot(33, 12, "Save"), spot(34, 11, "Branch")),
                List.of(new ViewerScene.Region(id(51), id(11), id(21), id(25), "Visible card", 64, 96,
                                new ViewerScene.PixelRect(1, 1, 8, 8), "Group", 2, new ViewerScene.Anchor(.5, .5)),
                        new ViewerScene.Region(id(52), id(14), id(24), id(26), "Unvisited region", 64, 96,
                                new ViewerScene.PixelRect(2, 2, 8, 8), null, 0, new ViewerScene.Anchor(.5, .5))), assets);
    }
    private static RenderPlan plan(ViewerScene scene) {
        return RenderPlan.build(scene, 1080, 1920, List.of(visit(61, 11, 41), visit(62, 12, 43), visit(63, 11, 42), visit(64, 13, null)),
                List.of(new RenderPlan.Effect("annotation", id(61), 0, 10, null, null, "不可截断的标注内容", new ViewerScene.Rect(.1, .1, .5, .2)),
                        new RenderPlan.Effect("focus", id(61), 10, 10, null, id(51), null, null)));
    }
    private static ViewerScene.Asset png(Path source, int number, BufferedImage image, String role) throws Exception {
        Path path = source.resolve("assets/" + id(number) + ".png");
        require(ImageIO.write(image, "png", path.toFile()), "PNG encoder unavailable");
        return new ViewerScene.Asset(id(number), "assets/" + id(number) + ".png", "image/png", Files.size(path),
                ViewerPackageCodec.sha256(path.toFile()), image.getWidth(), image.getHeight(), role, null);
    }
    private static ViewerScene.Edge edge(int number, int from, int to, Integer hotspot, String label) {
        return new ViewerScene.Edge(id(number), id(from), id(to), null, hotspot == null ? null : id(hotspot), label, hotspot == null ? "continue" : "tap", "authored");
    }
    private static ViewerScene.Hotspot spot(int number, int state, String label) {
        return new ViewerScene.Hotspot(id(number), id(state), label, new ViewerScene.Rect(.1, .1, .3, .2));
    }
    private static RenderPlan.Visit visit(int number, int state, Integer edge) { return new RenderPlan.Visit(id(number), id(state), edge == null ? null : id(edge), 60); }
    private static ViewerScene.State state(ViewerScene.State s, String title, String description, String kind, boolean terminal) {
        return new ViewerScene.State(s.id, s.imageAssetId, s.width, s.height, title, description, kind, terminal);
    }
    private static ViewerScene.Region region(ViewerScene.Region r, String name, String group, ViewerScene.PixelRect bbox, ViewerScene.Anchor anchor) {
        return new ViewerScene.Region(r.id, r.stateId, r.baseAssetId, r.assetId, name, r.sourceWidth, r.sourceHeight, bbox, group, r.zIndex, anchor);
    }
    private static void replaceLabels(Change c, String label) {
        ViewerScene.Hotspot h = c.hotspots.get(0); c.hotspots.set(0, new ViewerScene.Hotspot(h.id, h.stateId, label, h.rect));
        ViewerScene.Edge e = c.edges.get(0); c.edges.set(0, new ViewerScene.Edge(e.id, e.fromStateId, e.toStateId, e.endLabel, e.hotspotId, label, e.trigger, e.sourceKind, e.transitionAssetId));
    }
    private static final class Change {
        final ViewerScene original;
        String title, goal, release, start;
        long created;
        final List<ViewerScene.State> states;
        final List<ViewerScene.Edge> edges;
        final List<ViewerScene.Hotspot> hotspots;
        final List<ViewerScene.Region> regions;
        final List<ViewerScene.Asset> assets;
        Change(ViewerScene scene) {
            original = scene; title = scene.title; goal = scene.goal; release = scene.releaseId; start = scene.startStateId; created = scene.createdAt;
            states = new ArrayList<>(scene.states); edges = new ArrayList<>(scene.edges); hotspots = new ArrayList<>(scene.hotspots);
            regions = new ArrayList<>(scene.regions); assets = new ArrayList<>(scene.assets);
        }
        ViewerScene scene() { return new ViewerScene(original.schemaVersion, original.policyVersion, original.compilerVersion,
                release, title, goal, created, start, states, edges, hotspots, regions, assets); }
    }
    private static void issue(ViewerScene scene, String code, String subject, String messagePart) {
        ViewerPackageCodec.validateScene(scene);
        require(AiDraftImportPolicy.inspect(scene).stream().anyMatch(i -> i.code.equals(code) && i.subjectId.equals(subject) && i.message.contains(messagePart)), "Missing issue: " + code + "/" + subject + "/" + messagePart);
    }
    private static void difference(ViewerScene scene, ViewerScene baseline, String category, String subject, String field, String before, String after) {
        require(AiDraftImportPolicy.compare(scene, baseline).stream().anyMatch(d -> d.category.equals(category) && d.subjectId.equals(subject)
                && d.field.equals(field) && java.util.Objects.equals(d.before, before) && java.util.Objects.equals(d.after, after)), "Missing difference: " + category + "/" + field);
    }
    private static String repeat(int count) { return "字".repeat(count); }
    private static String id(int number) { return String.format("00000000-0000-4000-8000-%012d", number); }
    private static void require(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private interface Checked { void run() throws Exception; }
    private static void rejected(String label, Checked action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError("Accepted " + label);
    }
}
