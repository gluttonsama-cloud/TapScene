import com.tapscene.packageformat.ViewerPackageCodec;
import com.tapscene.packageformat.ViewerScene;
import com.tapscene.packageformat.ViewerTraversal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/** Desktop-only, dependency-free corpus. Do not put AWT/ImageIO in the Android APK. */
public final class PackageSecurityChecks {
    private static final long MIB = 1024L * 1024L;
    private static final String RELEASE = id(1);
    private static final String S1 = id(11), S2 = id(12), S3 = id(13), A1 = id(21);
    private static final String H1 = id(31), H2 = id(32);
    private static final String ASSET_PATH = "assets/" + A1 + ".png";
    private static final ViewerPackageCodec.CancelCheck NEVER_CANCEL = () -> { };
    private static Path root;
    private static byte[] png;
    private static ViewerScene minimal, branch;
    private static int passed, failed, serial;
    private static final List<String> failures = new ArrayList<>();

    private PackageSecurityChecks() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "pass a fixture output directory");
        root = Path.of(args[0]);
        Files.createDirectories(root);
        png = png(1, 1);
        minimal = scene(List.of(state(S1, true)), List.of(), List.of(), png, 1, 1);
        branch = scene(List.of(state(S1, false), state(S2, true), state(S3, false)),
                List.of(tap(id(41), S1, S2, H1), tap(id(42), S1, S3, H2),
                        next(id(43), S3, null, "Done")),
                List.of(hotspot(H1, S1, 0, 0, .5, 1), hotspot(H2, S1, .5, 0, .5, 1)),
                png, 1, 1);
        run("smallest-static-package-round-trip-and-independent-inspection", () -> {
            Path assets = assetRoot("minimal-assets", png);
            Path archive = root.resolve("smallest-viewer.tapscene");
            ViewerPackageCodec.writePackage(minimal, assets.toFile(), archive.toFile(), NEVER_CANCEL);
            inspectArchive(archive, minimal, 1, 0);
            readValid(archive, minimal);
        });
        run("branch-tap-continue-end-and-history-independent-walk", () -> {
            Path assets = assetRoot("branch-assets", png);
            Path archive = root.resolve("branch-viewer.tapscene");
            ViewerPackageCodec.writePackage(branch, assets.toFile(), archive.toFile(), NEVER_CANCEL);
            inspectArchive(archive, branch, 3, 3);
            Files.copy(archive, root.resolve("synthetic.tapscene"));
            readValid(archive, branch);
            independentTraverse(branch, List.of(id(42), id(43)), List.of(S1, S3));
            independentTraverse(branch, List.of(id(41)), List.of(S1, S2));
        });
        run("canonical-output-stable-and-digest-binds-text", () -> {
            byte[] bytes = ViewerPackageCodec.writeScene(branch);
            require(Arrays.equals(bytes, ViewerPackageCodec.writeScene(ViewerPackageCodec.parseScene(bytes))),
                    "parse/write must preserve canonical scene bytes");
            require(hash(bytes).equals(ViewerPackageCodec.contentDigest(branch)), "scene digest mismatch");
            ViewerScene changed = copy(branch, "Changed title", branch.states, branch.edges,
                    branch.hotspots, branch.assets);
            require(!ViewerPackageCodec.contentDigest(branch).equals(ViewerPackageCodec.contentDigest(changed)),
                    "changing display text must change contentDigest");
            List<ViewerScene.State> reversed = new ArrayList<>(branch.states);
            Collections.reverse(reversed);
            ViewerScene reordered = copy(branch, branch.title, reversed, branch.edges, branch.hotspots, branch.assets);
            ViewerPackageCodec.validateScene(reordered);
        });
        run("export-rounds-coordinates-to-six-decimals", () -> {
            ViewerScene precise = scene(List.of(state(S1, false)), List.of(tap(id(41), S1, null, H1)),
                    List.of(hotspot(H1, S1, 0.12345649, 0, 0.87654351, 1)), png, 1, 1);
            byte[] encoded = ViewerPackageCodec.writeScene(precise);
            ViewerScene rounded = ViewerPackageCodec.parseScene(encoded);
            require(rounded.hotspots.get(0).rect.x == 0.123456 && rounded.hotspots.get(0).rect.width == 0.876544,
                    "coordinate export did not round to six decimal places");
            require(Arrays.equals(encoded, ViewerPackageCodec.writeScene(rounded)), "rounded scene not stable");
        });
        run("explicit-tap-loop-with-exit-is-valid", () -> {
            ViewerScene loop = scene(List.of(state(S1, false), state(S2, true)),
                    List.of(tap(id(41), S1, S1, H1), tap(id(42), S1, S2, H2)),
                    List.of(hotspot(H1, S1, 0, 0, .5, 1), hotspot(H2, S1, .5, 0, .5, 1)), png, 1, 1);
            ViewerPackageCodec.validateScene(loop);
            independentTraverse(loop, List.of(id(41), id(41), id(42)), List.of(S1, S1, S1, S2));
            readValid(writeRaw("loop-with-exit", entries(loop, png)), loop);
        });
        run("display-text-is-inert-data", () -> {
            ViewerScene text = copy(minimal, "<script>alert(1)</script> & \"quoted\" 中文 😀",
                    minimal.states, minimal.edges, minimal.hotspots, minimal.assets);
            ViewerScene parsed = ViewerPackageCodec.parseScene(ViewerPackageCodec.writeScene(text));
            require(parsed.title.equals(text.title), "pure display text should survive without execution");
        });
        run("exact-40-states-40-assets-42-files-boundary", () -> {
            List<ViewerScene.State> states = new ArrayList<>();
            List<ViewerScene.Asset> assets = new ArrayList<>();
            List<ViewerScene.Edge> edges = new ArrayList<>();
            Path dir = Files.createDirectory(root.resolve("boundary-assets"));
            Files.createDirectory(dir.resolve("assets"));
            for (int n = 0; n < 40; n++) {
                String stateId = n == 0 ? S1 : id(1001 + n);
                String assetId = id(10000 + n), path = "assets/" + assetId + ".png";
                states.add(new ViewerScene.State(stateId, assetId, 1, 1, "Step " + n, "Synthetic", "authored", n == 39));
                assets.add(new ViewerScene.Asset(assetId, path, "image/png", png.length, hash(png), 1, 1));
                Files.write(dir.resolve(path), png);
                if (n < 39) edges.add(next(id(20000 + n), stateId, id(1002 + n), null));
            }
            ViewerScene boundary = new ViewerScene(RELEASE, "Boundary viewer", "Synthetic", 1, S1, states, edges, List.of(), assets);
            Path archive = root.resolve("boundary-42-files.tapscene");
            ViewerPackageCodec.writePackage(boundary, dir.toFile(), archive.toFile(), NEVER_CANCEL);
            try (ZipFile zip = new ZipFile(archive.toFile())) { require(zip.size() == 42, "boundary file count differs"); }
            readValid(archive, boundary);
        });
        jsonCorpus();
        graphCorpus();
        traversalCorpus();
        zipCorpus();
        pngCorpus();
        directoryAndCancellationCorpus();
        String summary = "TAPSCENE_PACKAGE_CHECKS_" + (failed == 0 ? "OK" : "FAILED")
                + ": passed=" + passed + " failed=" + failed;
        System.out.println(summary);
        Files.writeString(root.resolve("results.txt"), summary + "\n" + String.join("\n", failures) + "\n");
        if (failed != 0) throw new AssertionError(String.join("\n", failures));
    }

    private static void jsonCorpus() throws Exception {
        String valid = utf8(ViewerPackageCodec.writeScene(branch));
        rejectJson("duplicate-json-root-key", valid.replaceFirst("\\{", "{\"schemaVersion\":1,"));
        rejectJson("duplicate-json-nested-key", valid.replace("\"width\":1", "\"width\":1,\"width\":1"));
        rejectJson("unknown-root-script-field", valid.replaceFirst("\\{", "{\"script\":\"alert(1)\","));
        rejectJson("unknown-nested-expression-field", valid.replace("\"terminal\":", "\"expression\":\"1+1\",\"terminal\":"));
        rejectJson("unsupported-schema-version", replaceRequired(valid, "\"schemaVersion\":1", "\"schemaVersion\":2"));
        rejectJson("unsupported-policy-version", replaceRequired(valid, "static-viewer-1", "static-viewer-999"));
        rejectJson("unsupported-compiler-version", replaceRequired(valid, "tapscene-android-1", "remote-code-1"));
        rejectJson("script-transition-reference", replaceRequired(valid, "\"transitionAssetId\":null", "\"transitionAssetId\":\"script.js\""));
        rejectJson("unexpected-regions", replaceRequired(valid, "\"regions\":[]", "\"regions\":[{}]"));
        rejectJson("unsupported-choice-trigger", replaceRequired(valid, "\"trigger\":\"tap\"", "\"trigger\":\"choice\""));
        rejectJson("external-media-url", replaceRequired(valid, ASSET_PATH, "https://example.invalid/image.png"));
        rejectJson("script-asset-path", replaceRequired(valid, ASSET_PATH, "assets/script.js"));
        rejectJson("unsupported-asset-mime", replaceRequired(valid, "image/png", "text/javascript"));
        rejectJson("unsupported-asset-role", replaceRequired(valid, "state-image", "script"));
        rejectJson("static-asset-duration", replaceRequired(valid, "\"durationMs\":null", "\"durationMs\":10"));
        rejectJson("unknown-coordinate-space", replaceRequired(valid, "state-normalized", "screen-pixels"));
        rejectJson("uppercase-uuid", replaceRequired(valid, RELEASE, "ABCDEFAB-0000-0000-0000-000000000001"));
        rejectJson("null-required-array", replaceRequired(valid, "\"regions\":[]", "\"regions\":null"));
        rejectJson("trailing-json-payload", valid + "{}");
        rejectJson("json-comments", "/* external schema */" + valid);
        rejectJson("json-byte-order-mark", "\ufeff" + valid);
        rejectJson("unpaired-escaped-high-surrogate", replaceRequired(valid, "\"title\":\"Synthetic viewer\"", "\"title\":\"\\ud800\""));
        rejectJson("unpaired-escaped-low-surrogate", replaceRequired(valid, "\"title\":\"Synthetic viewer\"", "\"title\":\"\\udc00\""));
        rejectJson("raw-control-character", replaceRequired(valid, "Synthetic viewer", "bad\u0001title"));
        rejectJson("overprecise-coordinate", replaceRequired(valid, "\"x\":0", "\"x\":0.0000001"));
        rejectJson("exponent-coordinate", replaceRequired(valid, "\"x\":0", "\"x\":0e0"));
        rejectJson("unsafe-integer", replaceRequired(valid, "\"createdAt\":1", "\"createdAt\":9007199254740992"));
        rejectJson("overlong-title", replaceRequired(valid, "Synthetic viewer", "x".repeat(241)));
        rejectJson("not-finite-coordinate", replaceRequired(valid, "\"x\":0", "\"x\":NaN"));
        rejectJson("leading-zero-number", replaceRequired(valid, "\"schemaVersion\":1", "\"schemaVersion\":01"));
        rejectJson("fractional-schema-version", replaceRequired(valid, "\"schemaVersion\":1", "\"schemaVersion\":1.5"));
        rejectJson("deeply-nested-json", "[".repeat(20) + "0" + "]".repeat(20));
        run("reject-invalid-utf8", () -> expectRejected(() -> ViewerPackageCodec.parseScene(new byte[] {(byte) 0xc0, (byte) 0xaf})));
        run("reject-utf8-encoded-surrogate", () -> {
            byte[] prefix = "{\"title\":\"".getBytes(StandardCharsets.UTF_8);
            byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
            expectRejected(() -> ViewerPackageCodec.parseScene(concat(prefix, new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, suffix)));
        });
        run("reject-scene-over-512-kib", () -> expectRejected(() -> ViewerPackageCodec.parseScene(new byte[512 * 1024 + 1])));
        run("reject-excessive-string", () -> expectRejected(() -> ViewerPackageCodec.parseScene(
                valid.replace("Synthetic viewer", "x".repeat(70 * 1024)).getBytes(StandardCharsets.UTF_8))));
        run("reject-excessive-json-nodes", () -> expectRejected(() -> ViewerPackageCodec.parseScene(
                ("[" + "[],".repeat(20_000) + "[]]").getBytes(StandardCharsets.UTF_8))));
    }

    private static void graphCorpus() throws Exception {
        rejectScene("dangling-start-state", new ViewerScene(RELEASE, "Synthetic viewer", "Walk", 1,
                id(999), minimal.states, minimal.edges, minimal.hotspots, minimal.assets));
        rejectScene("duplicate-state-id", scene(List.of(state(S1, true), state(S1, true)), List.of(), List.of(), png, 1, 1));
        rejectScene("unreachable-state", scene(List.of(state(S1, true), state(S2, true)), List.of(), List.of(), png, 1, 1));
        rejectScene("nonterminal-dead-end", scene(List.of(state(S1, false)), List.of(), List.of(), png, 1, 1));
        rejectScene("dangling-target-state", scene(List.of(state(S1, false)), List.of(next(id(41), S1, S2, null)), List.of(), png, 1, 1));
        rejectScene("dangling-source-state", scene(minimal.states, List.of(next(id(41), S2, null, "Done")), List.of(), png, 1, 1));
        rejectScene("closed-strongly-connected-component", scene(List.of(state(S1, false), state(S2, false)),
                List.of(next(id(41), S1, S2, null), next(id(42), S2, S1, null)), List.of(), png, 1, 1));
        rejectScene("closed-self-loop", scene(List.of(state(S1, false)), List.of(tap(id(41), S1, S1, H1)),
                List.of(hotspot(H1, S1, 0, 0, 1, 1)), png, 1, 1));
        rejectScene("terminal-has-outgoing-edge", scene(minimal.states, List.of(next(id(41), S1, null, "Done")), List.of(), png, 1, 1));
        rejectScene("tap-without-hotspot", scene(List.of(state(S1, false)),
                List.of(new ViewerScene.Edge(id(41), S1, null, "Done", null, "Finish", "tap", "authored")), List.of(), png, 1, 1));
        rejectScene("same-hotspot-has-two-tap-actions", scene(List.of(state(S1, false), state(S2, true)),
                List.of(tap(id(41), S1, S2, H1), tap(id(42), S1, null, H1)),
                List.of(hotspot(H1, S1, 0, 0, 1, 1)), png, 1, 1));
        rejectScene("same-state-has-two-continue-actions", scene(List.of(state(S1, false), state(S2, true)),
                List.of(next(id(41), S1, S2, null), next(id(42), S1, null, "Done")), List.of(), png, 1, 1));
        rejectScene("continue-with-hotspot", scene(List.of(state(S1, false)),
                List.of(new ViewerScene.Edge(id(41), S1, null, "Done", H1, "Finish", "continue", "authored")),
                List.of(hotspot(H1, S1, 0, 0, 1, 1)), png, 1, 1));
        rejectScene("continue-is-not-author-confirmed", scene(List.of(state(S1, false)),
                List.of(new ViewerScene.Edge(id(41), S1, null, "Done", null, "Finish", "continue", "recorded")), List.of(), png, 1, 1));
        rejectScene("edge-has-both-state-and-end-target", scene(List.of(state(S1, false), state(S2, true)),
                List.of(next(id(41), S1, S2, "Done")), List.of(), png, 1, 1));
        rejectScene("edge-has-no-target", scene(List.of(state(S1, false)), List.of(next(id(41), S1, null, null)), List.of(), png, 1, 1));
        rejectScene("dangling-hotspot-reference", scene(List.of(state(S1, false)),
                List.of(tap(id(41), S1, null, H1)), List.of(), png, 1, 1));
        rejectScene("wrong-state-hotspot-reference", scene(List.of(state(S1, false), state(S2, true)),
                List.of(tap(id(41), S1, S2, H1)), List.of(hotspot(H1, S2, 0, 0, 1, 1)), png, 1, 1));
        rejectScene("orphan-hotspot", scene(minimal.states, List.of(), List.of(hotspot(H1, S1, 0, 0, 1, 1)), png, 1, 1));
        rejectScene("dangling-image-asset", copy(minimal, minimal.title, minimal.states, minimal.edges, minimal.hotspots, List.of()));
        rejectScene("duplicate-asset-id", copy(minimal, minimal.title, minimal.states, minimal.edges, minimal.hotspots,
                List.of(minimal.assets.get(0), minimal.assets.get(0))));
        rejectScene("invalid-state-dimensions", scene(List.of(new ViewerScene.State(S1, A1, 2, 1, "Step", "", "authored", true)),
                List.of(), List.of(), png, 1, 1));
        for (double[] rect : List.of(new double[] {-0.1, 0, 1, 1}, new double[] {0, 0, 0, 1},
                new double[] {.5, 0, .6, 1}, new double[] {0, .9, 1, .2}, new double[] {Double.NaN, 0, 1, 1},
                new double[] {0, 0, Double.POSITIVE_INFINITY, 1})) {
            rejectScene("invalid-hotspot-rectangle-" + (++serial), scene(List.of(state(S1, false)),
                    List.of(tap(id(41), S1, null, H1)), List.of(hotspot(H1, S1, rect[0], rect[1], rect[2], rect[3])), png, 1, 1));
        }
        List<ViewerScene.State> manyStates = new ArrayList<>();
        List<ViewerScene.Edge> manyEdges = new ArrayList<>();
        for (int n = 0; n < 41; n++) {
            String s = n == 0 ? S1 : id(1000 + n);
            manyStates.add(state(s, n == 40));
            if (n < 40) manyEdges.add(next(id(2000 + n), s, id(1001 + n), null));
        }
        rejectScene("more-than-40-states", scene(manyStates, manyEdges, List.of(), png, 1, 1));
        List<ViewerScene.Hotspot> manyHotspots = new ArrayList<>();
        manyEdges.clear();
        for (int n = 0; n < 7; n++) {
            manyHotspots.add(hotspot(id(3000 + n), S1, 0, 0, 1, 1));
            manyEdges.add(new ViewerScene.Edge(id(4000 + n), S1, null, "Outcome " + n, id(3000 + n),
                    "Outcome " + n, "tap", "authored"));
        }
        rejectScene("more-than-six-hotspots-in-state", scene(List.of(state(S1, false)), manyEdges, manyHotspots, png, 1, 1));
        manyEdges = new ArrayList<>(); manyStates = new ArrayList<>(); manyHotspots = new ArrayList<>();
        for (int n = 0; n < 14; n++) {
            String source = n == 0 ? S1 : id(6000 + n);
            manyStates.add(state(source, false));
            for (int action = 0; action < (n == 13 ? 3 : 6); action++) {
                int index = n * 6 + action;
                String hotspotId = id(7000 + index);
                manyHotspots.add(hotspot(hotspotId, source, 0, 0, 1, 1));
                manyEdges.add(tap(id(8000 + index), source, action == 0 && n < 13 ? id(6001 + n) : null, hotspotId));
            }
        }
        rejectScene("more-than-80-edges", scene(manyStates, manyEdges, manyHotspots, png, 1, 1));
        manyEdges.remove(manyEdges.size() - 1); manyHotspots.remove(manyHotspots.size() - 1);
        ViewerScene edgeBoundary = scene(manyStates, manyEdges, manyHotspots, png, 1, 1);
        run("exact-80-edges-six-hotspots-per-state-boundary", () -> {
            ViewerPackageCodec.validateScene(edgeBoundary);
            ViewerScene parsed = ViewerPackageCodec.parseScene(ViewerPackageCodec.writeScene(edgeBoundary));
            require(parsed.edges.size() == 80, "edge limit boundary changed");
            independentGraph(object(new Json(utf8(ViewerPackageCodec.writeScene(parsed))).read()));
        });
    }

    private static void traversalCorpus() {
        run("real-reducer-loaded-package-both-branches-actual-back-and-restart", () -> {
            ViewerScene loaded = read(root.resolve("synthetic.tapscene")).scene;
            ViewerTraversal start = ViewerTraversal.start(loaded);
            require(start.currentStateId.equals(S1) && start.history.equals(List.of(S1)) && !start.ended,
                    "reducer start differs");
            require(start.visitedEdgeIds.isEmpty() && !start.completedFromStart, "unvisited start has coverage");
            ViewerTraversal second = start.advance(loaded, id(42));
            require(second.currentStateId.equals(S3) && !second.ended && second.history.equals(List.of(S1, S3)),
                    "continue action was executed automatically or visit history differs");
            ViewerTraversal ended = second.advance(loaded, id(43));
            require(ended.ended && ended.completedFromStart && "Done".equals(ended.endLabel)
                    && id(43).equals(ended.endEdgeId) && ended.history.equals(List.of(S1, S3)), "end action differs");
            ViewerTraversal clearEnd = ended.previous(loaded);
            require(!clearEnd.ended && clearEnd.endEdgeId == null && clearEnd.currentStateId.equals(S3)
                    && clearEnd.history.equals(ended.history), "back from explicit ending skipped a visit");
            ViewerTraversal atStart = clearEnd.previous(loaded);
            require(atStart.currentStateId.equals(S1) && atStart.history.equals(List.of(S1)), "back did not follow actual history");
            ViewerTraversal terminal = atStart.advance(loaded, id(41));
            require(terminal.ended && terminal.endEdgeId == null && "Step".equals(terminal.endLabel)
                    && terminal.currentStateId.equals(S2) && terminal.history.equals(List.of(S1, S2)), "terminal state differs");
            ViewerTraversal back = terminal.previous(loaded);
            require(!back.ended && back.currentStateId.equals(S1) && back.history.equals(List.of(S1)), "terminal back differs");
            ViewerTraversal restarted = terminal.restart(loaded);
            require(restarted.currentStateId.equals(S1) && restarted.history.equals(List.of(S1)) && !restarted.ended,
                    "restart did not reset visits");
            require(restarted.visitedEdgeIds.equals(Set.of(id(41), id(42), id(43))) && restarted.completedFromStart,
                    "back/restart lost review coverage");
            require(start.visitedEdgeIds.isEmpty() && start.history.equals(List.of(S1)) && !start.completedFromStart,
                    "advancing mutated previous reducer instance");
        });
        run("real-reducer-single-terminal-package-starts-ended", () -> {
            ViewerScene loaded = read(root.resolve("smallest-viewer.tapscene")).scene;
            ViewerTraversal terminal = ViewerTraversal.start(loaded);
            require(terminal.ended && terminal.completedFromStart && terminal.endEdgeId == null && terminal.visitedEdgeIds.isEmpty(),
                    "terminal start is not completed");
            require(terminal.restart(loaded).ended, "restart changed terminal-only scene");
            expectRejected(() -> terminal.previous(loaded));
            expectRejected(() -> terminal.advance(loaded, id(41)));
        });
        run("real-reducer-rejects-missing-wrong-source-and-ended-actions", () -> {
            ViewerScene loaded = read(root.resolve("synthetic.tapscene")).scene;
            ViewerTraversal start = ViewerTraversal.start(loaded);
            expectRejected(() -> start.advance(loaded, id(999)));
            expectRejected(() -> start.advance(loaded, id(43)));
            expectRejected(() -> start.previous(loaded));
            ViewerTraversal terminal = start.advance(loaded, id(41));
            expectRejected(() -> terminal.advance(loaded, id(42)));
            ViewerTraversal ended = start.advance(loaded, id(42)).advance(loaded, id(43));
            expectRejected(() -> ended.advance(loaded, id(43)));
        });
        run("real-reducer-explicit-loop-visit-limit-and-end-at-limit", () -> {
            ViewerScene loop = scene(List.of(state(S1, false)),
                    List.of(tap(id(41), S1, S1, H1), next(id(43), S1, null, "Done")),
                    List.of(hotspot(H1, S1, 0, 0, 1, 1)), png, 1, 1);
            ViewerScene loaded = read(writeRaw("real-reducer-loop", entries(loop, png))).scene;
            ViewerTraversal traversal = ViewerTraversal.start(loaded);
            for (int n = 1; n < 256; n++) traversal = traversal.advance(loaded, id(41));
            require(traversal.history.size() == 256 && !traversal.ended, "explicit loop visit count differs");
            ViewerTraversal atLimit = traversal;
            expectRejected(() -> atLimit.advance(loaded, id(41)));
            ViewerTraversal ending = atLimit.advance(loaded, id(43));
            require(ending.ended && ending.history.size() == 256, "end action should remain available at visit limit");
            ViewerTraversal previous = ending.previous(loaded).previous(loaded);
            require(previous.history.size() == 255 && !previous.ended, "back did not recover visit capacity");
            require(previous.advance(loaded, id(41)).history.size() == 256, "explicit loop failed after back");
            ViewerTraversal restarted = ending.restart(loaded);
            require(restarted.history.equals(List.of(S1)) && restarted.visitedEdgeIds.equals(Set.of(id(41), id(43)))
                    && restarted.completedFromStart, "loop restart coverage differs");
        });
        run("real-reducer-collections-immutable-and-defensively-copied", () -> {
            List<String> history = new ArrayList<>(List.of(S1));
            Set<String> coverage = new HashSet<>();
            ViewerTraversal traversal = new ViewerTraversal(S1, history, false, null, null, coverage, false);
            history.add(S2); coverage.add(id(41));
            require(traversal.history.equals(List.of(S1)) && traversal.visitedEdgeIds.isEmpty(), "constructor retained mutable collections");
            expectUnsupported(() -> traversal.history.add(S2));
            expectUnsupported(() -> traversal.visitedEdgeIds.add(id(41)));
        });
        run("real-reducer-rejects-forged-checkpoint-structure", () -> {
            ViewerScene loaded = read(root.resolve("synthetic.tapscene")).scene;
            for (ViewerTraversal invalid : List.of(
                    new ViewerTraversal(S3, List.of(S1, S2, S3), false, null, null, Set.of(), false),
                    new ViewerTraversal(S3, List.of(S1), false, null, null, Set.of(), false),
                    new ViewerTraversal(S1, List.of(S1), false, null, null, Set.of(id(999)), false),
                    new ViewerTraversal(S3, List.of(S1, S3), true, "Wrong end", id(43), Set.of(id(43)), true),
                    new ViewerTraversal(S2, List.of(S1, S2), true, "Step", null, Set.of(id(41)), false))) {
                expectRejected(() -> invalid.restart(loaded));
            }
        });
    }

    private static void zipCorpus() throws Exception {
        run("independent-stored-zip-is-accepted", () -> readValid(writeRaw("independent-stored", entries(minimal, png)), minimal));
        run("independent-deflated-zip-is-accepted", () -> {
            List<Entry> compressed = entries(branch, png);
            for (Entry entry : compressed) entry.method = 8;
            readValid(writeRaw("independent-deflated", compressed), branch);
        });
        for (int descriptorLength : List.of(12, 16)) {
            run("independent-deflate-data-descriptor-" + descriptorLength + "-is-accepted", () -> {
                List<Entry> streamed = entries(branch, png);
                for (Entry entry : streamed) { entry.method = 8; entry.flags |= 8; entry.descriptorLength = descriptorLength; }
                readValid(writeRaw("descriptor-" + descriptorLength, streamed), branch);
            });
        }
        List<Entry> compressedTail = entries(minimal, png);
        compressedTail.get(2).method = 8;
        compressedTail.get(2).compressedOverride = concat(deflate(png), new byte[] {1, 2, 3});
        rejectEntries("trailing-deflate-payload", compressedTail);
        List<Entry> corruptDeflate = entries(minimal, png);
        corruptDeflate.get(2).method = 8;
        corruptDeflate.get(2).compressedOverride = new byte[] {7, 7, 7, 7};
        rejectEntries("malformed-deflate-stream", corruptDeflate);
        List<Entry> missingDescriptor = entries(minimal, png);
        missingDescriptor.get(2).flags |= 8;
        rejectEntries("missing-data-descriptor", missingDescriptor);
        List<Entry> duplicate = entries(minimal, png);
        duplicate.add(new Entry("scene.json", ViewerPackageCodec.writeScene(minimal)));
        rejectEntries("duplicate-zip-entry", duplicate);
        for (String name : List.of("../escaped.png", "/absolute.png", "assets/../../escaped.png",
                "assets\\escaped.png", "C:\\escaped.png", "assets//image.png", "assets/./image.png")) {
            List<Entry> bad = entries(minimal, png);
            bad.set(2, new Entry(name, png));
            rejectEntries("unsafe-zip-path-" + (++serial), bad);
        }
        run("zip-traversal-cannot-overwrite-outside-destination", () -> {
            Path parent = root.resolve("traversal-sentinel");
            Files.createDirectories(parent);
            Path sentinel = parent.resolve("escaped.png");
            byte[] original = "existing user data".getBytes(StandardCharsets.UTF_8);
            Files.write(sentinel, original);
            List<Entry> bad = entries(minimal, png);
            bad.set(2, new Entry("../escaped.png", png));
            Path destination = parent.resolve("unpack");
            Files.createDirectory(destination);
            Path archive = writeRaw("traversal-overwrite", bad);
            expectRejected(() -> ViewerPackageCodec.readPackage(archive.toFile(), destination.toFile(), NEVER_CANCEL));
            require(Arrays.equals(original, Files.readAllBytes(sentinel)), "traversal changed an outside file");
        });
        List<Entry> missing = entries(minimal, png);
        missing.remove(2);
        rejectEntries("missing-declared-asset", missing);
        List<Entry> missingScene = entries(minimal, png);
        missingScene.remove(1);
        rejectEntries("missing-scene", missingScene);
        List<Entry> missingManifest = entries(minimal, png);
        missingManifest.remove(0);
        rejectEntries("missing-manifest", missingManifest);
        List<Entry> extra = entries(minimal, png);
        extra.add(new Entry("script.js", "alert(1)".getBytes(StandardCharsets.UTF_8)));
        rejectEntries("unlisted-script-file", extra);
        extra = entries(minimal, png);
        extra.add(new Entry("assets/", new byte[0]));
        rejectEntries("directory-entry", extra);
        extra = entries(minimal, png);
        extra.add(new Entry("nested.zip", zip(List.of(new Entry("script.js", new byte[] {1})))));
        rejectEntries("nested-archive", extra);
        List<Entry> tooMany = entries(minimal, png);
        for (int n = 0; n < 40; n++) tooMany.add(new Entry("extra-" + n + ".txt", new byte[] {1}));
        rejectEntries("more-than-42-zip-files", tooMany);
        List<Entry> symlink = entries(minimal, png);
        symlink.get(2).madeBy = 0x0314;
        symlink.get(2).externalAttributes = 0120777L << 16;
        rejectEntries("unix-symlink-entry", symlink);
        List<Entry> encrypted = entries(minimal, png);
        encrypted.get(2).flags |= 1;
        rejectEntries("encrypted-entry", encrypted);
        List<Entry> unsupportedMethod = entries(minimal, png);
        unsupportedMethod.get(2).method = 99;
        rejectEntries("unsupported-compression-method", unsupportedMethod);
        List<Entry> mismatchedName = entries(minimal, png);
        mismatchedName.get(2).localName = "assets/" + id(22) + ".png";
        rejectEntries("local-central-filename-mismatch", mismatchedName);
        byte[] normal = zip(entries(minimal, png));
        byte[] badCrc = normal.clone();
        badCrc[14] ^= 1;
        rejectBytes("local-central-crc-mismatch", badCrc);
        byte[] badFlags = normal.clone();
        badFlags[6] ^= 1;
        rejectBytes("local-central-flags-mismatch", badFlags);
        byte[] multiDisk = normal.clone();
        put16(multiDisk, multiDisk.length - 22 + 4, 1);
        rejectBytes("multidisk-archive", multiDisk);
        byte[] zip64 = normal.clone();
        put32(zip64, 18, 0xffff_ffffL);
        put32(zip64, 22, 0xffff_ffffL);
        int central = signature(zip64, 0x02014b50);
        put32(zip64, central + 20, 0xffff_ffffL);
        put32(zip64, central + 24, 0xffff_ffffL);
        rejectBytes("zip64-size-sentinel", zip64);
        rejectBytes("trailing-payload-after-eocd", concat(normal, "<script>payload</script>".getBytes(StandardCharsets.UTF_8)));
        rejectBytes("truncated-archive", Arrays.copyOf(normal, normal.length - 1));
        rejectBytes("prepended-polyglot-payload", concat("<html>".getBytes(StandardCharsets.UTF_8), normal));
        List<Entry> tamperedAsset = entries(minimal, png);
        byte[] otherPng = png.clone();
        otherPng[otherPng.length - 1] ^= 1;
        tamperedAsset.set(2, new Entry(ASSET_PATH, otherPng));
        rejectEntries("asset-hash-mismatch", tamperedAsset);
        List<Entry> tamperedScene = entries(minimal, png);
        tamperedScene.set(1, new Entry("scene.json", utf8(ViewerPackageCodec.writeScene(minimal))
                .replace("Synthetic viewer", "Synthetic other!").getBytes(StandardCharsets.UTF_8)));
        rejectEntries("scene-hash-mismatch", tamperedScene);
        List<Entry> manifestDigest = entries(minimal, png);
        manifestDigest.set(0, new Entry("manifest.json", utf8(ViewerPackageCodec.manifestBytes(minimal))
                .replace(ViewerPackageCodec.contentDigest(minimal), "0".repeat(64)).getBytes(StandardCharsets.UTF_8)));
        rejectEntries("manifest-content-digest-mismatch", manifestDigest);
        List<Entry> duplicateManifestKey = entries(minimal, png);
        duplicateManifestKey.set(0, new Entry("manifest.json", utf8(ViewerPackageCodec.manifestBytes(minimal))
                .replaceFirst("\\{", "{\"schemaVersion\":1,").getBytes(StandardCharsets.UTF_8)));
        rejectEntries("duplicate-manifest-json-key", duplicateManifestKey);
        List<Entry> duplicateManifestFile = entries(minimal, png);
        String manifest = utf8(ViewerPackageCodec.manifestBytes(minimal));
        Map<String, Object> parsedManifest = object(new Json(manifest).read());
        String firstEntry = independentJson(array(parsedManifest.get("files")).get(0));
        duplicateManifestFile.set(0, new Entry("manifest.json", manifest.replace("\"files\":[", "\"files\":[" + firstEntry + ",")
                .getBytes(StandardCharsets.UTF_8)));
        rejectEntries("duplicate-manifest-file-path", duplicateManifestFile);
        List<Entry> oversizedManifest = entries(minimal, png);
        oversizedManifest.set(0, new Entry("manifest.json", new byte[64 * 1024 + 1]));
        rejectEntries("manifest-over-64-kib", oversizedManifest);
        List<Entry> ratio = entries(minimal, png);
        Entry bomb = new Entry(ASSET_PATH, new byte[2 * 1024 * 1024]);
        bomb.method = 8;
        ratio.set(2, bomb);
        require(bomb.body.length > 200L * Math.max(1, deflate(bomb.body).length), "ratio fixture not sufficiently compressed");
        rejectEntries("excessive-deflate-expansion-ratio", ratio);
        byte[] oversizeDeclared = normal.clone();
        put32(oversizeDeclared, 22, 50 * MIB + 1);
        put32(oversizeDeclared, central + 24, 50 * MIB + 1);
        rejectBytes("declared-unpacked-size-over-50-mib", oversizeDeclared);
        run("reject-archive-over-50-mib", () -> {
            Path huge = root.resolve("oversized-sparse.zip");
            try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) { file.setLength(50 * MIB + 1); }
            try { expectRejected(() -> read(huge)); } finally { Files.delete(huge); }
        });
    }

    private static void pngCorpus() throws Exception {
        run("png-allowed-srgb-metadata", () -> {
            byte[] srgb = insertPngChunk(png, "sRGB", new byte[] {0});
            ViewerScene metadata = scene(minimal.states, List.of(), List.of(), srgb, 1, 1);
            readValid(writeRaw("allowed-srgb-png", entries(metadata, srgb)), metadata);
        });
        byte[] duplicateSrgb = insertPngChunk(insertPngChunk(png, "sRGB", new byte[] {0}), "sRGB", new byte[] {0});
        rejectEntries("duplicate-png-color-metadata", entries(scene(minimal.states, List.of(), List.of(), duplicateSrgb, 1, 1), duplicateSrgb));
        byte[] invalidIntent = insertPngChunk(png, "sRGB", new byte[] {4});
        rejectEntries("png-invalid-srgb-intent", entries(scene(minimal.states, List.of(), List.of(), invalidIntent, 1, 1), invalidIntent));
        for (byte[] rawPixels : List.of(new byte[] {5, 0x33, 0x66, (byte) 0x99, (byte) 0xff}, new byte[50], new byte[] {0})) {
            byte[] bad = replaceIdat(png, zlib(rawPixels));
            rejectEntries("invalid-png-scanline-or-size-" + (++serial), entries(scene(minimal.states, List.of(), List.of(), bad, 1, 1), bad));
        }
        byte[] brokenZlib = replaceIdat(png, new byte[] {0, 0, 0, 0});
        rejectEntries("malformed-png-zlib-with-valid-chunk-crc", entries(scene(minimal.states, List.of(), List.of(), brokenZlib, 1, 1), brokenZlib));
        byte[] crc = png.clone();
        crc[29] ^= 1; // IHDR CRC, preserving all advertised lengths and asset digest.
        rejectEntries("png-crc-invalid-with-matching-manifest-hash", entries(scene(minimal.states, List.of(), List.of(), crc, 1, 1), crc));
        byte[] signature = png.clone();
        signature[0] = 'x';
        rejectEntries("png-signature-invalid-with-matching-hash", entries(scene(minimal.states, List.of(), List.of(), signature, 1, 1), signature));
        byte[] text = insertPngChunk(png, "tEXt", "comment\0private metadata".getBytes(StandardCharsets.UTF_8));
        rejectEntries("png-text-metadata-is-rejected", entries(scene(minimal.states, List.of(), List.of(), text, 1, 1), text));
        byte[] apng = insertPngChunk(png, "acTL", new byte[] {0, 0, 0, 1, 0, 0, 0, 0});
        rejectEntries("animated-png-control-is-rejected", entries(scene(minimal.states, List.of(), List.of(), apng, 1, 1), apng));
        byte[] pngTail = concat(png, "hidden payload".getBytes(StandardCharsets.UTF_8));
        rejectEntries("png-tail-after-iend", entries(scene(minimal.states, List.of(), List.of(), pngTail, 1, 1), pngTail));
        byte[] missingEnd = Arrays.copyOf(png, png.length - 12);
        rejectEntries("png-missing-iend", entries(scene(minimal.states, List.of(), List.of(), missingEnd, 1, 1), missingEnd));
        ViewerScene wrongDimensions = scene(List.of(new ViewerScene.State(S1, A1, 2, 1, "Step", "", "authored", true)),
                List.of(), List.of(), png, 2, 1);
        rejectEntries("actual-png-dimensions-disagree-with-scene", entries(wrongDimensions, png));
        byte[] oversizedDimensions = png.clone();
        putBig32(oversizedDimensions, 16, 100_000);
        CRC32 chunkCrc = new CRC32();
        chunkCrc.update(oversizedDimensions, 12, 17);
        putBig32(oversizedDimensions, 29, chunkCrc.getValue());
        rejectEntries("png-dimension-bomb-with-valid-crc-and-file-hash",
                entries(scene(minimal.states, List.of(), List.of(), oversizedDimensions, 1, 1), oversizedDimensions));
    }

    private static void directoryAndCancellationCorpus() throws Exception {
        run("directory-validation-and-file-sha256", () -> {
            Path dir = assetRoot("validate-good", png);
            ViewerPackageCodec.validateDirectory(minimal, dir.toFile(), NEVER_CANCEL);
            require(ViewerPackageCodec.sha256(dir.resolve(ASSET_PATH).toFile()).equals(hash(png)), "sha256(File) differs from independent digest");
        });
        run("reject-missing-directory-asset", () -> {
            Path dir = Files.createDirectory(root.resolve("validate-missing"));
            expectRejected(() -> ViewerPackageCodec.validateDirectory(minimal, dir.toFile(), NEVER_CANCEL));
        });
        run("reject-nonempty-import-destination-without-damaging-existing-data", () -> {
            Path dir = Files.createDirectory(root.resolve("nonempty-import"));
            Path sentinel = dir.resolve("existing.txt");
            Files.writeString(sentinel, "keep me");
            Path zip = writeRaw("nonempty-target", entries(minimal, png));
            expectRejected(() -> ViewerPackageCodec.readPackage(zip.toFile(), dir.toFile(), NEVER_CANCEL));
            require(Files.readString(sentinel).equals("keep me"), "existing destination changed");
        });
        run("reject-symlinked-asset-in-directory", () -> {
            Path dir = Files.createDirectory(root.resolve("symlink-assets"));
            Files.createDirectory(dir.resolve("assets"));
            Path outside = root.resolve("outside-image.png");
            Files.write(outside, png);
            Files.createSymbolicLink(dir.resolve(ASSET_PATH), outside.toAbsolutePath());
            expectRejected(() -> ViewerPackageCodec.validateDirectory(minimal, dir.toFile(), NEVER_CANCEL));
        });
        run("cancellation-aborts-export", () -> {
            Path dir = assetRoot("cancel-export-assets", png);
            Path output = root.resolve("cancel-export.zip");
            expectCancelled(() -> ViewerPackageCodec.writePackage(minimal, dir.toFile(), output.toFile(), () -> { throw new CheckCancelled(); }));
        });
        run("mid-export-cancellation-removes-partial-archive", () -> {
            Path dir = assetRoot("cancel-mid-export-assets", png);
            Path output = root.resolve("cancel-mid-export.zip");
            expectCancelled(() -> ViewerPackageCodec.writePackage(minimal, dir.toFile(), output.toFile(), () -> {
                if (Files.exists(output)) throw new CheckCancelled();
            }));
            require(!Files.exists(output), "cancelled export retained partial archive");
        });
        run("mid-import-cancellation-cleans-isolated-files", () -> {
            Path input = writeRaw("cancel-mid-import", entries(minimal, png));
            Path output = Files.createDirectory(root.resolve("cancel-mid-import-output"));
            expectCancelled(() -> ViewerPackageCodec.readPackage(input.toFile(), output.toFile(), () -> {
                if (Files.exists(output.resolve("manifest.json"))) throw new CheckCancelled();
            }));
            try (var files = Files.list(output)) { require(files.findAny().isEmpty(), "cancelled import retained partial files"); }
        });
        run("cancellation-aborts-import", () -> {
            Path input = writeRaw("cancel-import", entries(minimal, png));
            Path output = Files.createDirectory(root.resolve("cancel-import-output"));
            expectCancelled(() -> ViewerPackageCodec.readPackage(input.toFile(), output.toFile(), () -> { throw new CheckCancelled(); }));
        });
    }

    private static void inspectArchive(Path archive, ViewerScene expected, int stateCount, int edgeCount) throws Exception {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                require(!entry.isDirectory(), "unexpected directory in exported archive");
                require(contents.put(entry.getName(), zip.getInputStream(entry).readAllBytes()) == null,
                        "duplicate exported ZIP name");
            }
        }
        require(contents.keySet().equals(Set.of("manifest.json", "scene.json", ASSET_PATH)), "unexpected exported files");
        byte[] sceneBytes = contents.get("scene.json");
        Map<String, Object> scene = object(new Json(utf8(sceneBytes)).read());
        Map<String, Object> manifest = object(new Json(utf8(contents.get("manifest.json"))).read());
        require(scene.keySet().equals(Set.of("schemaVersion", "policyVersion", "compilerVersion", "releaseId", "title",
                "goal", "createdAt", "startStateId", "states", "edges", "hotspots", "regions", "assets")), "scene field leak");
        require(manifest.keySet().equals(Set.of("schemaVersion", "exportKind", "releaseId", "contentDigest", "files")), "manifest field leak");
        require(utf8(sceneBytes).equals(independentJson(scene)), "exported scene is not lexicographically canonical JSON");
        require(manifest.get("contentDigest").equals(hash(sceneBytes)), "independent manifest content digest mismatch");
        require(manifest.get("releaseId").equals(RELEASE), "manifest release mismatch");
        require(manifest.get("exportKind").equals("viewer"), "manifest export kind mismatch");
        require(number(manifest.get("schemaVersion")) == 1, "manifest schema mismatch");
        require(scene.get("policyVersion").equals("static-viewer-1"), "scene policy mismatch");
        require(scene.get("compilerVersion").equals("tapscene-android-1"), "scene compiler mismatch");
        require(scene.get("title").equals(expected.title), "scene title mismatch");
        require(array(scene.get("states")).size() == stateCount, "state count mismatch");
        require(array(scene.get("edges")).size() == edgeCount, "edge count mismatch");
        Set<String> listed = new HashSet<>();
        for (Object value : array(manifest.get("files"))) {
            Map<String, Object> file = object(value);
            String path = (String) file.get("path");
            require(listed.add(path), "duplicate manifest path");
            byte[] body = contents.get(path);
            require(body != null, "manifest file missing");
            require(number(file.get("byteLength")) == body.length, "independent manifest byte length mismatch");
            require(file.get("sha256").equals(hash(body)), "independent manifest file digest mismatch");
        }
        require(listed.equals(Set.of("scene.json", ASSET_PATH)), "manifest list should omit itself and include every payload");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(contents.get(ASSET_PATH)));
        require(decoded != null && decoded.getWidth() == 1 && decoded.getHeight() == 1, "independent PNG decode failed");
        require(decoded.getRGB(0, 0) == 0xff336699, "synthetic PNG pixel differs");
        independentGraph(scene);
    }

    private static void independentGraph(Map<String, Object> scene) {
        Map<String, Map<String, Object>> states = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> outgoing = new LinkedHashMap<>();
        Set<String> ending = new HashSet<>();
        for (Object value : array(scene.get("states"))) {
            Map<String, Object> state = object(value);
            String stateId = (String) state.get("id");
            require(states.put(stateId, state) == null, "duplicate state in independent graph");
            outgoing.put(stateId, new ArrayList<>());
            if (Boolean.TRUE.equals(state.get("terminal"))) ending.add(stateId);
        }
        for (Object value : array(scene.get("edges"))) {
            Map<String, Object> edge = object(value);
            String source = (String) edge.get("fromStateId");
            require(outgoing.containsKey(source), "independent dangling source");
            Map<String, Object> target = object(edge.get("to"));
            if (target.containsKey("stateId")) require(states.containsKey(target.get("stateId")), "independent dangling target");
            else { require(target.containsKey("endLabel"), "independent invalid edge target"); ending.add(source); }
            outgoing.get(source).add(edge);
        }
        Set<String> reached = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add((String) scene.get("startStateId"));
        while (!queue.isEmpty()) {
            String current = queue.remove();
            if (!reached.add(current)) continue;
            require(outgoing.containsKey(current), "independent invalid start");
            for (Map<String, Object> edge : outgoing.get(current)) {
                Object next = object(edge.get("to")).get("stateId");
                if (next != null) queue.add((String) next);
            }
        }
        require(reached.equals(states.keySet()), "independent unreachable graph state");
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, List<Map<String, Object>>> source : outgoing.entrySet()) {
                for (Map<String, Object> edge : source.getValue()) {
                    if (ending.contains(object(edge.get("to")).get("stateId"))) changed |= ending.add(source.getKey());
                }
            }
        } while (changed);
        require(ending.containsAll(states.keySet()), "independent graph has a closed non-ending component");
    }

    private static void independentTraverse(ViewerScene scene, List<String> choices, List<String> expectedHistory) {
        List<String> history = new ArrayList<>();
        String current = scene.startStateId;
        history.add(current);
        boolean ended = false;
        for (String choice : choices) {
            ViewerScene.Edge selected = null;
            for (ViewerScene.Edge edge : scene.edges) if (edge.id.equals(choice)) selected = edge;
            require(selected != null && selected.fromStateId.equals(current) && !ended, "invalid selected path");
            if (selected.toStateId == null) { require(selected.endLabel != null, "end has no label"); ended = true; }
            else { current = selected.toStateId; history.add(current); }
        }
        boolean terminal = false;
        for (ViewerScene.State state : scene.states) if (state.id.equals(current)) terminal = state.terminal;
        require(ended || terminal, "independent selected path did not finish");
        require(history.equals(expectedHistory), "independent visit history differs");
        List<String> backHistory = new ArrayList<>(history);
        while (backHistory.size() > 1) backHistory.remove(backHistory.size() - 1);
        require(backHistory.equals(List.of(scene.startStateId)), "back traversal did not return along actual visits");
    }

    private static void readValid(Path archive, ViewerScene expected) throws Exception {
        ViewerPackageCodec.LoadedPackage loaded = read(archive);
        require(Arrays.equals(ViewerPackageCodec.writeScene(loaded.scene), ViewerPackageCodec.writeScene(expected)), "round-trip scene differs");
        require(loaded.contentDigest.equals(ViewerPackageCodec.contentDigest(expected)), "round-trip digest differs");
        require(loaded.files.size() == expected.assets.size() + 1, "round-trip file list differs");
        Map<String, ViewerPackageCodec.FileEntry> declared = new LinkedHashMap<>();
        for (ViewerPackageCodec.FileEntry file : ViewerPackageCodec.fileList(expected)) declared.put(file.path, file);
        for (ViewerPackageCodec.FileEntry file : loaded.files) {
            ViewerPackageCodec.FileEntry original = declared.get(file.path);
            require(original != null && original.byteLength == file.byteLength && original.sha256.equals(file.sha256), "fileList differs after read");
        }
    }

    private static ViewerPackageCodec.LoadedPackage read(Path archive) throws IOException {
        Path directory = Files.createDirectory(root.resolve("unpacked-" + (++serial)));
        return ViewerPackageCodec.readPackage(archive.toFile(), directory.toFile(), NEVER_CANCEL);
    }

    private static void rejectJson(String name, String json) {
        run("reject-" + name, () -> expectRejected(() -> ViewerPackageCodec.parseScene(json.getBytes(StandardCharsets.UTF_8))));
    }
    private static void rejectScene(String name, ViewerScene scene) {
        run("reject-" + name, () -> expectRejected(() -> ViewerPackageCodec.validateScene(scene)));
    }
    private static void rejectEntries(String name, List<Entry> entries) throws IOException { rejectBytes(name, zip(entries)); }
    private static void rejectBytes(String name, byte[] bytes) throws IOException {
        Path path = root.resolve("rejected-" + name + ".zip");
        Files.write(path, bytes);
        run("reject-" + name, () -> expectRejected(() -> read(path)));
    }

    private static void run(String name, CheckedRunnable check) {
        try {
            check.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError failure) {
            failed++;
            String message = "FAIL " + name + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
            failures.add(message);
            System.err.println(message);
        }
    }
    private static void expectRejected(CheckedRunnable check) throws Exception {
        try { check.run(); }
        catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("malformed input was accepted");
    }
    private static void expectCancelled(CheckedRunnable check) throws Exception {
        try { check.run(); }
        catch (CheckCancelled expected) { return; }
        throw new AssertionError("cancellation was swallowed");
    }
    private static void expectUnsupported(CheckedRunnable check) throws Exception {
        try { check.run(); }
        catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("immutable collection was mutable");
    }
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
    private static final class CheckCancelled extends RuntimeException { private static final long serialVersionUID = 1L; }
    private static void require(boolean truth, String message) { if (!truth) throw new AssertionError(message); }

    private static String id(int value) { return new UUID(0, value).toString(); }
    private static ViewerScene.State state(String stateId, boolean terminal) {
        return new ViewerScene.State(stateId, A1, 1, 1, "Step", "Synthetic image only", "authored", terminal);
    }
    private static ViewerScene.Hotspot hotspot(String hotspotId, String stateId, double x, double y, double width, double height) {
        return new ViewerScene.Hotspot(hotspotId, stateId, "Choose", new ViewerScene.Rect(x, y, width, height));
    }
    private static ViewerScene.Edge tap(String edgeId, String source, String target, String hotspotId) {
        return new ViewerScene.Edge(edgeId, source, target, target == null ? "Done" : null, hotspotId, "Choose", "tap", "authored");
    }
    private static ViewerScene.Edge next(String edgeId, String source, String target, String endLabel) {
        return new ViewerScene.Edge(edgeId, source, target, endLabel, null, "Continue", "continue", "authored");
    }
    private static ViewerScene scene(List<ViewerScene.State> states, List<ViewerScene.Edge> edges,
            List<ViewerScene.Hotspot> hotspots, byte[] image, int width, int height) throws Exception {
        ViewerScene.Asset asset = new ViewerScene.Asset(A1, ASSET_PATH, "image/png", image.length, hash(image), width, height);
        return new ViewerScene(RELEASE, "Synthetic viewer", "Walk the synthetic graph", 1L, S1, states, edges, hotspots, List.of(asset));
    }
    private static ViewerScene copy(ViewerScene scene, String title, List<ViewerScene.State> states,
            List<ViewerScene.Edge> edges, List<ViewerScene.Hotspot> hotspots, List<ViewerScene.Asset> assets) {
        return new ViewerScene(scene.releaseId, title, scene.goal, scene.createdAt, scene.startStateId, states, edges, hotspots, assets);
    }
    private static Path assetRoot(String name, byte[] image) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir.resolve("assets"));
        Files.write(dir.resolve(ASSET_PATH), image);
        return dir;
    }
    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, 0xff336699);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        require(ImageIO.write(image, "png", bytes), "no built-in PNG writer");
        return bytes.toByteArray();
    }
    private static byte[] insertPngChunk(byte[] original, String type, byte[] payload) throws IOException {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(chunk)) {
            out.writeInt(payload.length);
            byte[] kind = type.getBytes(StandardCharsets.US_ASCII);
            out.write(kind);
            out.write(payload);
            CRC32 crc = new CRC32();
            crc.update(kind); crc.update(payload);
            out.writeInt((int) crc.getValue());
        }
        return concat(Arrays.copyOfRange(original, 0, 33), chunk.toByteArray(), Arrays.copyOfRange(original, 33, original.length));
    }
    private static byte[] replaceIdat(byte[] original, byte[] payload) throws IOException {
        // The generated 1x1 ImageIO fixture has one IHDR, one IDAT, then IEND.
        int length = ((original[33] & 255) << 24) | ((original[34] & 255) << 16)
                | ((original[35] & 255) << 8) | (original[36] & 255);
        require(new String(original, 37, 4, StandardCharsets.US_ASCII).equals("IDAT"), "fixture IDAT offset changed");
        byte[] withoutIdat = concat(Arrays.copyOfRange(original, 0, 33), Arrays.copyOfRange(original, 45 + length, original.length));
        return insertPngChunk(withoutIdat, "IDAT", payload);
    }
    private static byte[] zlib(byte[] bytes) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(bytes); deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer); require(count > 0, "zlib made no progress"); output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { deflater.end(); }
    }
    private static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }
    private static String utf8(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static String replaceRequired(String source, String needle, String replacement) {
        require(source.contains(needle), "fixture replacement did not match: " + needle);
        return source.replace(needle, replacement);
    }

    /** Deliberately independent ZIP writer: can create duplicate names java.util.zip refuses to write. */
    private static final class Entry {
        final String name;
        final byte[] body;
        String localName;
        int method, descriptorLength, flags = 0x0800, madeBy = 20;
        byte[] compressedOverride;
        long externalAttributes;
        Entry(String name, byte[] body) { this.name = name; this.body = body; }
    }
    private static List<Entry> entries(ViewerScene scene, byte[] image) {
        return new ArrayList<>(List.of(new Entry("manifest.json", ViewerPackageCodec.manifestBytes(scene)),
                new Entry("scene.json", ViewerPackageCodec.writeScene(scene)), new Entry(ASSET_PATH, image)));
    }
    private static Path writeRaw(String name, List<Entry> entries) throws IOException {
        Path path = root.resolve(name + ".zip"); Files.write(path, zip(entries)); return path;
    }
    private static byte[] zip(List<Entry> entries) throws IOException {
        ByteArrayOutputStream locals = new ByteArrayOutputStream(), central = new ByteArrayOutputStream();
        for (Entry entry : entries) {
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            byte[] localName = (entry.localName == null ? entry.name : entry.localName).getBytes(StandardCharsets.UTF_8);
            byte[] payload = entry.compressedOverride != null ? entry.compressedOverride : entry.method == 8 ? deflate(entry.body) : entry.body;
            CRC32 checksum = new CRC32(); checksum.update(entry.body); long crc = checksum.getValue();
            int offset = locals.size();
            le32(locals, 0x04034b50L); le16(locals, 20); le16(locals, entry.flags); le16(locals, entry.method);
            le16(locals, 0); le16(locals, 33); le32(locals, crc); le32(locals, payload.length); le32(locals, entry.body.length);
            le16(locals, localName.length); le16(locals, 0); locals.write(localName); locals.write(payload);
            if (entry.descriptorLength != 0) {
                if (entry.descriptorLength == 16) le32(locals, 0x08074b50L);
                le32(locals, crc); le32(locals, payload.length); le32(locals, entry.body.length);
            }
            le32(central, 0x02014b50L); le16(central, entry.madeBy); le16(central, 20); le16(central, entry.flags);
            le16(central, entry.method); le16(central, 0); le16(central, 33); le32(central, crc);
            le32(central, payload.length); le32(central, entry.body.length); le16(central, name.length);
            le16(central, 0); le16(central, 0); le16(central, 0); le16(central, 0);
            le32(central, entry.externalAttributes); le32(central, offset); central.write(name);
        }
        int centralOffset = locals.size(), centralSize = central.size();
        locals.write(central.toByteArray());
        le32(locals, 0x06054b50L); le16(locals, 0); le16(locals, 0); le16(locals, entries.size()); le16(locals, entries.size());
        le32(locals, centralSize); le32(locals, centralOffset); le16(locals, 0);
        return locals.toByteArray();
    }
    private static byte[] deflate(byte[] bytes) {
        Deflater deflater = new Deflater(6, true);
        try {
            deflater.setInput(bytes); deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                require(count > 0, "deflater made no progress"); output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { deflater.end(); }
    }
    private static void le16(ByteArrayOutputStream out, long value) { out.write((int) value & 0xff); out.write((int) (value >>> 8) & 0xff); }
    private static void le32(ByteArrayOutputStream out, long value) { le16(out, value); le16(out, value >>> 16); }
    private static void put16(byte[] bytes, int offset, long value) { for (int i = 0; i < 2; i++) bytes[offset + i] = (byte) (value >>> (8 * i)); }
    private static void put32(byte[] bytes, int offset, long value) { for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i)); }
    private static void putBig32(byte[] bytes, int offset, long value) { for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * (3 - i))); }
    private static int signature(byte[] bytes, int value) {
        for (int i = 0; i <= bytes.length - 4; i++) {
            long actual = 0;
            for (int n = 0; n < 4; n++) actual |= (bytes[i + n] & 0xffL) << (8 * n);
            if (actual == (value & 0xffff_ffffL)) return i;
        }
        throw new AssertionError("fixture ZIP signature is missing");
    }
    private static byte[] concat(byte[]... arrays) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] bytes : arrays) output.write(bytes);
        return output.toByteArray();
    }

    private static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?>, "independent JSON object expected");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            require(entry.getKey() instanceof String, "independent JSON key must be a string");
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }
    private static List<?> array(Object value) { require(value instanceof List<?>, "independent JSON array expected"); return (List<?>) value; }
    private static long number(Object value) {
        require(value instanceof java.math.BigDecimal, "independent JSON number expected");
        return ((java.math.BigDecimal) value).longValueExact();
    }
    private static String independentJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return quote((String) value);
        if (value instanceof Boolean) return value.toString();
        if (value instanceof java.math.BigDecimal) return ((java.math.BigDecimal) value).stripTrailingZeros().toPlainString();
        if (value instanceof List<?>) {
            List<String> children = new ArrayList<>();
            for (Object child : (List<?>) value) children.add(independentJson(child));
            return "[" + String.join(",", children) + "]";
        }
        Map<String, Object> map = object(value);
        List<String> keys = new ArrayList<>(map.keySet()); Collections.sort(keys);
        List<String> pairs = new ArrayList<>();
        for (String key : keys) pairs.add(quote(key) + ":" + independentJson(map.get(key)));
        return "{" + String.join(",", pairs) + "}";
    }
    private static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            switch (value) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (value < 32) quoted.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) value));
                    else quoted.append(value);
                }
            }
        }
        return quoted.append('"').toString();
    }

    /** Small independent reader, only used on generated good JSON. Never part of production code. */
    private static final class Json {
        final String input;
        int position;
        Json(String input) { this.input = input; }
        Object read() {
            Object result = value(); whitespace(); require(position == input.length(), "independent JSON trailing text"); return result;
        }
        Object value() {
            whitespace(); require(position < input.length(), "independent JSON unexpected EOF");
            char c = input.charAt(position);
            if (c == '"') return string();
            if (c == '{') {
                position++; whitespace(); Map<String, Object> map = new LinkedHashMap<>();
                if (take('}')) return map;
                do {
                    whitespace(); String key = string(); whitespace(); require(take(':'), "independent JSON missing colon");
                    require(!map.containsKey(key), "independent JSON duplicate key"); map.put(key, value()); whitespace();
                } while (take(','));
                require(take('}'), "independent JSON object not closed"); return map;
            }
            if (c == '[') {
                position++; whitespace(); List<Object> list = new ArrayList<>();
                if (take(']')) return list;
                do { list.add(value()); whitespace(); } while (take(','));
                require(take(']'), "independent JSON array not closed"); return list;
            }
            if (input.startsWith("true", position)) { position += 4; return true; }
            if (input.startsWith("false", position)) { position += 5; return false; }
            if (input.startsWith("null", position)) { position += 4; return null; }
            int start = position;
            while (position < input.length() && "-+0123456789.eE".indexOf(input.charAt(position)) >= 0) position++;
            require(position > start, "independent JSON invalid number");
            return new java.math.BigDecimal(input.substring(start, position));
        }
        String string() {
            require(take('"'), "independent JSON string expected"); StringBuilder text = new StringBuilder();
            while (position < input.length()) {
                char c = input.charAt(position++);
                if (c == '"') return text.toString();
                if (c != '\\') { require(c >= 32, "independent JSON control char"); text.append(c); continue; }
                require(position < input.length(), "independent JSON escape EOF");
                char escaped = input.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> text.append(escaped);
                    case 'b' -> text.append('\b');
                    case 'f' -> text.append('\f');
                    case 'n' -> text.append('\n');
                    case 'r' -> text.append('\r');
                    case 't' -> text.append('\t');
                    case 'u' -> {
                        require(position + 4 <= input.length(), "independent JSON Unicode EOF");
                        text.append((char) Integer.parseInt(input.substring(position, position + 4), 16)); position += 4;
                    }
                    default -> throw new AssertionError("independent JSON invalid escape");
                }
            }
            throw new AssertionError("independent JSON unterminated string");
        }
        void whitespace() { while (position < input.length() && " \t\r\n".indexOf(input.charAt(position)) >= 0) position++; }
        boolean take(char c) { if (position < input.length() && input.charAt(position) == c) { position++; return true; } return false; }
    }
}
