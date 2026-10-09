import com.tapscene.packageformat.ViewerPackageCodec;
import com.tapscene.packageformat.ViewerScene;
import com.tapscene.packageformat.ViewerTraversal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/** Synthetic, no-personal-data real-MP4 corpus. Uses installed FFmpeg, never downloads. */
public final class VideoPackageChecks {
    private static final ViewerPackageCodec.CancelCheck NO_CANCEL = () -> { };
    private static final String RELEASE = id(700), S1 = id(701), S2 = id(702), IMAGE = id(710), VIDEO = id(711), EDGE = id(720);
    private static int serial;
    private static final List<String> failures = new ArrayList<>();
    private static Path root;
    private static byte[] png, video;
    private static final HostVideoValidator VALIDATOR = new HostVideoValidator();

    private VideoPackageChecks() { }
    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Pass a new synthetic fixture directory"); run(Path.of(args[0]));
    }
    public static void run(Path output) throws Exception {
        root = Files.createDirectory(output); failures.clear();
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB); image.setRGB(0, 0, 0xff336699);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(); require(ImageIO.write(image, "png", encoded), "PNG encoder missing"); png = encoded.toByteArray();
        Path validFile = synthesize("valid", "1", false, "libx264", "bt709"); video = Files.readAllBytes(validFile);
        ViewerScene valid = scene(video, 1000L, 64, 64);
        Path assets = assets("valid-assets", valid, video);
        Path archive = root.resolve("video-viewer.tapscene");

        check("real-H264-SDR-no-audio-package-roundtrip-and-complete-decode", () -> {
            ViewerPackageCodec.writePackage(valid, assets.toFile(), archive.toFile(), NO_CANCEL, VALIDATOR);
            Path target = empty();
            ViewerPackageCodec.LoadedPackage loaded = ViewerPackageCodec.readPackage(archive.toFile(), target.toFile(), NO_CANCEL, VALIDATOR);
            require(loaded.scene.schemaVersion == 2 && ViewerPackageCodec.VIDEO_POLICY_VERSION.equals(loaded.scene.policyVersion), "Version was silently migrated");
            require(loaded.scene.edges.get(0).transitionAssetId.equals(VIDEO) && loaded.scene.assets.get(1).durationMs == 1000L, "Media binding did not survive parse");
            require(Arrays.equals(ViewerPackageCodec.writeScene(valid), ViewerPackageCodec.writeScene(loaded.scene)), "Canonical video scene changed");
            require(VALIDATOR.decodedFiles() >= 2, "Import and export did not fully decode");
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
                require(zip.size() == 4 && Arrays.equals(video, zip.getInputStream(zip.getEntry(valid.assets.get(1).path)).readAllBytes()), "Independent ZIP inspection differs");
            }
        });
        check("legacy-overloads-fail-closed-for-video-import-export-directory", () -> {
            rejected(() -> ViewerPackageCodec.validateDirectory(valid, assets.toFile(), NO_CANCEL));
            Path missingValidator = root.resolve("must-not-export.tapscene");
            rejected(() -> ViewerPackageCodec.writePackage(valid, assets.toFile(), missingValidator.toFile(), NO_CANCEL));
            require(!Files.exists(missingValidator), "Rejected export leaked a file");
            Path target = empty(); rejected(() -> ViewerPackageCodec.readPackage(archive.toFile(), target.toFile(), NO_CANCEL)); requireEmpty(target);
        });
        check("schema2-canonical-digest-binds-profile-transition-role-and-duration", () -> {
            String json = new String(ViewerPackageCodec.writeScene(valid), StandardCharsets.UTF_8);
            rejectJson(json.replace("video-viewer-2", "static-viewer-1"));
            rejectJson(json.replace("\"schemaVersion\":2", "\"schemaVersion\":1"));
            rejectJson(json.replace("\"durationMs\":1000", "\"durationMs\":null"));
            rejectJson(json.replace("\"role\":\"transition\"", "\"role\":\"script\""));
            rejectJson(json.replace("\"transitionAssetId\":\"" + VIDEO + "\"", "\"transitionAssetId\":\"https://example.invalid/movie.mp4\""));
            rejectJson(json.replace("\"transitionAssetId\":", "\"onEnded\":\"alert(1)\",\"transitionAssetId\":"));
            require(!ViewerPackageCodec.contentDigest(valid).equals(ViewerPackageCodec.contentDigest(scene(video, 999L, 64, 64))), "Duration did not affect digest");
        });
        check("video-reference-role-orphan-and-duration-graph-rejections", () -> {
            rejected(() -> ViewerPackageCodec.validateScene(scene(video, 0L, 64, 64)));
            rejected(() -> ViewerPackageCodec.validateScene(scene(video, 10001L, 64, 64)));
            ViewerScene.Edge wrongRole = new ViewerScene.Edge(EDGE, S1, S2, null, null, "Continue", "continue", "authored", IMAGE);
            rejected(() -> ViewerPackageCodec.validateScene(copy(valid, valid.states, List.of(wrongRole), valid.hotspots, valid.assets)));
            ViewerScene.Edge missing = new ViewerScene.Edge(EDGE, S1, S2, null, null, "Continue", "continue", "authored", id(999));
            rejected(() -> ViewerPackageCodec.validateScene(copy(valid, valid.states, List.of(missing), valid.hotspots, valid.assets)));
            ViewerScene.Edge unbound = new ViewerScene.Edge(EDGE, S1, S2, null, null, "Continue", "continue", "authored");
            rejected(() -> ViewerPackageCodec.validateScene(copy(valid, valid.states, List.of(unbound), valid.hotspots, valid.assets)));
            List<ViewerScene.State> wrongImage = List.of(new ViewerScene.State(S1, VIDEO, 64, 64, "Bad", "", "authored", false), valid.states.get(1));
            rejected(() -> ViewerPackageCodec.validateScene(copy(valid, wrongImage, valid.edges, valid.hotspots, valid.assets)));
        });
        check("six-10s-bindings-allowed-seven-repeated-bindings-rejected", () -> {
            ViewerPackageCodec.validateScene(chain(6, 10000L));
            rejected(() -> ViewerPackageCodec.validateScene(chain(7, 10000L)));
            ViewerPackageCodec.validateScene(chain(7, 8571L));
            rejected(() -> ViewerPackageCodec.validateScene(chain(7, 8572L)));
        });
        check("maximum-40-states-80-edges-120-assets-122-files-real-package", () -> boundary());
        check("transition-pending-commits-only-EOS-or-explicit-skip", () -> traversal(valid));
        check("audio-track-rejected-despite-correct-hash-and-declaration", () -> badMedia("audio", Files.readAllBytes(synthesize("audio", "1", true, "libx264", "bt709")), 1000, 64, 64));
        check("non-AVC-codec-rejected-despite-MP4-extension", () -> badMedia("mpeg4", Files.readAllBytes(synthesize("mpeg4", "1", false, "mpeg4", "bt709")), 1000, 64, 64));
        check("HDR-transfer-rejected", () -> badMedia("pq", Files.readAllBytes(synthesize("pq", "1", false, "libx264", "smpte2084")), 1000, 64, 64));
        check("encoded-high10-and-422-rejected-before-decoder-conversion", () -> {
            badMedia("high10", Files.readAllBytes(synthesize("high10", "1", false, "libx264", "bt709", "yuv420p10le")), 1000, 64, 64);
            badMedia("chroma422", Files.readAllBytes(synthesize("chroma422", "1", false, "libx264", "bt709", "yuv422p")), 1000, 64, 64);
        });
        check("actual-duration-declaration-mismatch-rejected", () -> { badMedia("duration-short", video, 999, 64, 64); badMedia("duration-long", video, 1001, 64, 64); });
        check("actual-dimensions-declaration-mismatch-rejected", () -> badMedia("dimensions", video, 1000, 62, 64));
        check("real-over-10s-cannot-hide-behind-10s-declaration", () -> badMedia("overlong", Files.readAllBytes(synthesize("overlong", "10.25", false, "libx264", "bt709")), 10000, 64, 64));
        check("PNG-renamed-MP4-rejected-after-matching-hash", () -> badMedia("not-video", png, 1000, 64, 64));
        check("corrupted-real-sample-rejected-after-matching-hash", () -> {
            byte[] corrupt = video.clone(); int at = indexOf(corrupt, "mdat".getBytes(StandardCharsets.US_ASCII));
            require(at > 0, "Synthetic MP4 has no media data box");
            Arrays.fill(corrupt, at + 4, Math.min(corrupt.length, at + 300), (byte) 0x7f);
            badMedia("corrupt-sample", corrupt, 1000, 64, 64);
        });
        check("validator-exception-and-cancellation-clean-isolated-import", () -> {
            Path target = empty();
            rejected(() -> ViewerPackageCodec.readPackage(archive.toFile(), target.toFile(), NO_CANCEL, (file, asset, cancel) -> { throw new IOException("Synthetic decode failure"); }));
            requireEmpty(target);
            Path canceled = empty();
            try { ViewerPackageCodec.readPackage(archive.toFile(), canceled.toFile(), NO_CANCEL, (file, asset, cancel) -> { throw new Canceled(); });
                throw new AssertionError("Cancellation swallowed"); } catch (Canceled expected) { requireEmpty(canceled); }
        });
        check("validator-mutated-video-is-rejected-before-import-registration", () -> {
            Path target = empty();
            rejected(() -> ViewerPackageCodec.readPackage(archive.toFile(), target.toFile(), NO_CANCEL,
                    (file, asset, cancel) -> Files.write(file.toPath(), new byte[(int) asset.byteLength])));
            requireEmpty(target);
        });
        String result = failures.isEmpty() ? "TAPSCENE_VIDEO_PACKAGE_CHECKS_OK" : "TAPSCENE_VIDEO_PACKAGE_CHECKS_FAILED";
        Files.writeString(root.resolve("results.txt"), result + "\n" + String.join("\n", failures) + "\n"); System.out.println(result);
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    }
    private static void traversal(ViewerScene scene) throws Exception {
        ViewerTraversal start = ViewerTraversal.start(scene), pending = start.advance(scene, EDGE);
        require(pending.currentStateId.equals(S1) && pending.history.equals(start.history) && pending.visitedEdgeIds.isEmpty()
                && !pending.ended && !pending.completedFromStart && EDGE.equals(pending.pendingEdgeId), "Selection committed target too soon");
        require(pending.advance(scene, EDGE) == pending && pending.advance(scene, id(999)) == pending, "Pending selection was not locked");
        require(pending.completeTransition(scene, pending.mediaRunId + 1) == pending, "Old/unknown EOS was accepted");
        ViewerTraversal failed = pending.failTransition(scene, pending.mediaRunId);
        require(failed.transitionFailed && failed.currentStateId.equals(S1) && failed.visitedEdgeIds.isEmpty(), "Failure committed history");
        require(failed.completeTransition(scene, failed.mediaRunId) == failed, "EOS after failed run committed");
        ViewerTraversal retry = failed.retryTransition(scene);
        require(!retry.transitionFailed && retry.mediaRunId != failed.mediaRunId && retry.pendingEdgeId.equals(EDGE), "Retry did not preserve fixed edge and renew attempt");
        require(retry.completeTransition(scene, failed.mediaRunId) == retry && retry.failTransition(scene, failed.mediaRunId) == retry
                && retry.skipTransition(scene, failed.mediaRunId) == retry, "Old attempt affected retry");
        ViewerTraversal ended = retry.completeTransition(scene, retry.mediaRunId);
        require(ended.currentStateId.equals(S2) && ended.history.equals(List.of(S1, S2)) && ended.ended && ended.completedFromStart
                && ended.visitedEdgeIds.equals(Set.of(EDGE)) && ended.pendingEdgeId == null, "EOS did not commit chosen destination");
        require(ended.completeTransition(scene, retry.mediaRunId) == ended, "Duplicate EOS changed state");
        require(ended.previous(scene).history.equals(List.of(S1)), "Back did not follow actual history");
        ViewerTraversal skip = failed.skipTransition(scene); require(skip.history.equals(List.of(S1, S2)) && skip.visitedEdgeIds.equals(Set.of(EDGE)), "Static fallback failed");
        ViewerTraversal canceled = pending.previous(scene);
        require(canceled.currentStateId.equals(S1) && canceled.history.equals(List.of(S1)) && canceled.pendingEdgeId == null && canceled.visitedEdgeIds.isEmpty(), "Back during transition visited target");
        require(canceled.completeTransition(scene, pending.mediaRunId) == canceled, "Canceled run EOS was accepted");
        ViewerTraversal restarted = pending.restart(scene);
        require(restarted.pendingEdgeId == null && restarted.completeTransition(scene, pending.mediaRunId) == restarted, "Restart accepted stale callback");
        ViewerTraversal closed = pending.close(scene);
        require(closed.closed && closed.pendingEdgeId == null && closed.completeTransition(scene, pending.mediaRunId) == closed
                && closed.failTransition(scene, pending.mediaRunId) == closed && closed.advance(scene, EDGE) == closed, "Closed viewer accepted callback or selection");
        ViewerTraversal newSession = ViewerTraversal.start(scene).advance(scene, EDGE);
        require(newSession.mediaRunId != pending.mediaRunId && newSession.completeTransition(scene, pending.mediaRunId) == newSession, "Old session callback affected new session");
        ViewerScene.Edge endEdge = new ViewerScene.Edge(EDGE, S1, null, "Finished", null, "Continue", "continue", "authored", VIDEO);
        ViewerScene explicitEnd = copy(scene, List.of(scene.states.get(0)), List.of(endEdge), List.of(), scene.assets);
        rejected(() -> pending.completeTransition(explicitEnd, pending.mediaRunId));
        rejected(() -> pending.skipTransition(explicitEnd, pending.mediaRunId));
        ViewerTraversal endPending = ViewerTraversal.start(explicitEnd).advance(explicitEnd, EDGE);
        ViewerTraversal end = endPending.skipTransition(explicitEnd);
        require(end.ended && EDGE.equals(end.endEdgeId) && end.history.equals(List.of(S1)), "Explicit ending changed visit history");
        require(!end.previous(explicitEnd).ended, "Back from explicit ending failed");
    }
    private static void boundary() throws Exception {
        byte[] shortVideo = Files.readAllBytes(synthesize("boundary", "0.75", false, "libx264", "bt709"));
        List<ViewerScene.State> states = new ArrayList<>(); List<ViewerScene.Edge> edges = new ArrayList<>();
        List<ViewerScene.Hotspot> hotspots = new ArrayList<>(); List<ViewerScene.Asset> items = new ArrayList<>();
        Path dir = root.resolve("boundary-assets"); Files.createDirectories(dir.resolve("assets"));
        for (int i = 0; i < 40; i++) {
            String stateId = id(1000+i), imageId = id(2000+i), hotspotId = id(3000+i);
            ViewerScene.Asset image = image(imageId); items.add(image); Files.write(dir.resolve(image.path), png);
            states.add(new ViewerScene.State(stateId, imageId, 1, 1, "Step " + i, "Synthetic", "authored", false));
            hotspots.add(new ViewerScene.Hotspot(hotspotId, stateId, "Choose", new ViewerScene.Rect(0,0,1,1)));
            for (int j = 0; j < 2; j++) {
                String assetId = id(4000+i*2+j); ViewerScene.Asset asset = video(assetId, shortVideo, 750L, 64,64); items.add(asset); Files.write(dir.resolve(asset.path), shortVideo);
                edges.add(new ViewerScene.Edge(id(5000+i*2+j), stateId, i < 39 ? id(1001+i) : null, i == 39 ? "Done" : null,
                        j == 0 ? hotspotId : null, "Continue", j == 0 ? "tap" : "continue", "authored", assetId));
            }
        }
        ViewerScene max = new ViewerScene(2, ViewerPackageCodec.VIDEO_POLICY_VERSION, ViewerPackageCodec.COMPILER_VERSION,
                RELEASE, "Maximum synthetic package", "No personal data", 1, states.get(0).id, states, edges, hotspots, items);
        ViewerPackageCodec.validateScene(max); require(ViewerPackageCodec.MAX_FILES == 122, "ZIP file cap differs");
        // All 80 files have identical verified bytes and media declarations. Cache ONLY
        // after a real full decode, and independently hash each file before cache reuse.
        Set<String> decoded = new HashSet<>();
        ViewerPackageCodec.VideoValidator cached = (file, asset, cancel) -> {
            String key = ViewerPackageCodec.sha256(file) + ":" + asset.width + ":" + asset.height + ":" + asset.durationMs;
            cancel.check(); if (!decoded.contains(key)) { VALIDATOR.validate(file, asset, cancel); decoded.add(key); }
        };
        Path archive = root.resolve("boundary-122-files.tapscene");
        ViewerPackageCodec.writePackage(max, dir.toFile(), archive.toFile(), NO_CANCEL, cached);
        ViewerPackageCodec.LoadedPackage loaded = ViewerPackageCodec.readPackage(archive.toFile(), empty().toFile(), NO_CANCEL, cached);
        require(loaded.scene.assets.size() == 120 && loaded.files.size() == 121, "Boundary assets lost");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) { require(zip.size() == 122, "Boundary file count differs"); }
        List<ViewerScene.Asset> tooMany = new ArrayList<>(items); tooMany.add(image(id(9000)));
        rejected(() -> ViewerPackageCodec.validateScene(copy(max, states, edges, hotspots, tooMany)));
        require(ViewerPackageCodec.MAX_PACKAGE_BYTES == 50L*1024*1024, "Package byte budget changed");
    }
    private static ViewerScene chain(int count, long duration) throws Exception {
        List<ViewerScene.State> states = new ArrayList<>(); List<ViewerScene.Edge> edges = new ArrayList<>();
        for (int n=0;n<=count;n++) {
            states.add(new ViewerScene.State(id(800+n), IMAGE, 1,1,"Step", "", "authored", n==count));
            if (n<count) edges.add(new ViewerScene.Edge(id(900+n), id(800+n), id(801+n), null, null, "Continue", "continue", "authored", VIDEO));
        }
        return new ViewerScene(2, ViewerPackageCodec.VIDEO_POLICY_VERSION, ViewerPackageCodec.COMPILER_VERSION, RELEASE,
                "Repeated transition", "", 1, id(800), states, edges, List.of(), List.of(image(IMAGE), video(VIDEO, video, duration,64,64)));
    }
    private static void badMedia(String name, byte[] bytes, long duration, int width, int height) throws Exception {
        ViewerScene scene = scene(bytes,duration,width,height);
        Path archive = rawPackage(name, scene, bytes), target = empty();
        int[] visited = {0}; String[] reason = {""};
        rejected(() -> ViewerPackageCodec.readPackage(archive.toFile(), target.toFile(), NO_CANCEL, (file, declared, cancel) -> {
            visited[0]++;
            try { VALIDATOR.validate(file, declared, cancel); }
            catch (IOException failure) { reason[0] = failure.getMessage(); throw failure; }
        }));
        require(visited[0] == 1 && !reason[0].isEmpty(), "Negative fixture did not reach real media validation");
        Files.writeString(root.resolve("rejected-" + name + ".reason.txt"), reason[0] + "\n"); requireEmpty(target);
    }
    private static Path rawPackage(String name, ViewerScene scene, byte[] bytes) throws Exception {
        Path archive = root.resolve("rejected-" + name + ".tapscene");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            entry(zip,"manifest.json", ViewerPackageCodec.manifestBytes(scene)); entry(zip,"scene.json", ViewerPackageCodec.writeScene(scene));
            entry(zip,scene.assets.get(0).path,png); entry(zip,scene.assets.get(1).path,bytes);
        }
        return archive;
    }
    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name); entry.setMethod(ZipEntry.STORED); entry.setTime(315532800000L);
        CRC32 crc = new CRC32(); crc.update(bytes); entry.setCrc(crc.getValue()); entry.setSize(bytes.length); entry.setCompressedSize(bytes.length);
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }
    private static Path synthesize(String name, String duration, boolean audio, String codec, String transfer) throws IOException {
        return synthesize(name, duration, audio, codec, transfer, "yuv420p");
    }
    private static Path synthesize(String name, String duration, boolean audio, String codec, String transfer, String pixelFormat) throws IOException {
        Path target = root.resolve(name + ".mp4");
        List<String> args = new ArrayList<>(List.of("ffmpeg","-v","error","-f","lavfi","-i","testsrc2=size=64x64:rate=4:duration="+duration));
        if (audio) args.addAll(List.of("-f","lavfi","-i","sine=frequency=440:sample_rate=8000:duration="+duration));
        args.addAll(List.of("-map","0:v:0")); if (audio) args.addAll(List.of("-map","1:a:0","-c:a","aac"));
        args.addAll(List.of("-c:v",codec,"-threads","1"));
        if (codec.equals("libx264")) args.addAll(List.of("-preset","ultrafast","-bf","0","-x264-params","transfer="+transfer+":colorprim=bt709:colormatrix=bt709"));
        args.addAll(List.of("-pix_fmt",pixelFormat,"-color_trc",transfer,"-colorspace","bt709","-color_primaries","bt709","-map_metadata","-1","-movflags","+faststart",target.toString()));
        HostVideoValidator.command(args, NO_CANCEL); return target;
    }
    private static ViewerScene scene(byte[] bytes, Long duration, int width, int height) throws Exception {
        List<ViewerScene.State> states = List.of(new ViewerScene.State(S1,IMAGE,1,1,"Start","Synthetic","authored",false),
                new ViewerScene.State(S2,IMAGE,1,1,"Done","Synthetic","authored",true));
        return new ViewerScene(2,ViewerPackageCodec.VIDEO_POLICY_VERSION,ViewerPackageCodec.COMPILER_VERSION,RELEASE,"Synthetic video viewer","No personal data",1,S1,states,
                List.of(new ViewerScene.Edge(EDGE,S1,S2,null,null,"Continue","continue","authored",VIDEO)),List.of(),
                List.of(image(IMAGE), video(VIDEO,bytes,duration,width,height)));
    }
    private static ViewerScene copy(ViewerScene scene, List<ViewerScene.State> states, List<ViewerScene.Edge> edges,
            List<ViewerScene.Hotspot> hotspots, List<ViewerScene.Asset> assets) {
        return new ViewerScene(scene.schemaVersion,scene.policyVersion,scene.compilerVersion,scene.releaseId,scene.title,scene.goal,scene.createdAt,scene.startStateId,states,edges,hotspots,assets);
    }
    private static ViewerScene.Asset image(String id) throws Exception { return new ViewerScene.Asset(id,"assets/"+id+".png","image/png",png.length,hash(png),1,1); }
    private static ViewerScene.Asset video(String id, byte[] bytes, Long duration, int width, int height) throws Exception {
        return new ViewerScene.Asset(id,"assets/"+id+".mp4","video/mp4",bytes.length,hash(bytes),width,height,ViewerScene.Asset.ROLE_TRANSITION,duration);
    }
    private static Path assets(String name, ViewerScene scene, byte[] bytes) throws IOException {
        Path dir=root.resolve(name); Files.createDirectories(dir.resolve("assets")); Files.write(dir.resolve(scene.assets.get(0).path),png); Files.write(dir.resolve(scene.assets.get(1).path),bytes); return dir;
    }
    private static Path empty() throws IOException { return Files.createDirectory(root.resolve("isolated-"+(++serial))); }
    private static void requireEmpty(Path path) throws IOException { try(var children=Files.list(path)) { require(children.findAny().isEmpty(),"Rejected package left isolated files"); } }
    private static void rejectJson(String json) throws Exception { rejected(() -> ViewerPackageCodec.parseScene(json.getBytes(StandardCharsets.UTF_8))); }
    private static void rejected(Checked action) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; } throw new AssertionError("Invalid input was accepted");
    }
    private static void check(String name, Checked action) {
        try { action.run(); System.out.println("PASS video-"+name); }
        catch(Exception|AssertionError failure) { String message="FAIL video-"+name+": "+failure; failures.add(message); System.err.println(message); }
    }
    private static int indexOf(byte[] bytes, byte[] needle) { for(int n=0;n<=bytes.length-needle.length;n++) { boolean equal=true; for(int j=0;j<needle.length;j++) if(bytes[n+j]!=needle[j]) equal=false; if(equal)return n; } return -1; }
    private static String hash(byte[] bytes) throws Exception { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String id(int value) { return new UUID(0,value).toString(); }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static final class Canceled extends RuntimeException { private static final long serialVersionUID=1L; }
}
