package com.tapscene.packageformat;

import static com.tapscene.packageformat.StrictJson.require;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
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

/** Fail-closed, offline-only versioned viewer package. This is not a general-purpose ZIP/JSON loader. */
public final class ViewerPackageCodec {
    public static final long MAX_PACKAGE_BYTES = 50L * 1024 * 1024;
    public static final int MAX_SCENE_BYTES = 512 * 1024, MAX_MANIFEST_BYTES = 64 * 1024, MAX_FILES = 202;
    public static final int MAX_ASSETS = 200, MAX_REGIONS = 80;
    public static final long MAX_TRANSITION_MS = 10_000, MAX_TOTAL_TRANSITION_MS = 60_000;
    public static final String POLICY_VERSION = "static-viewer-1", COMPILER_VERSION = "tapscene-android-1";
    public static final String REGION_POLICY_VERSION = "scene-regions-3";
    public static final String VIDEO_POLICY_VERSION = "video-viewer-2";
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private ViewerPackageCodec() {}

    @FunctionalInterface public interface CancelCheck { void check(); }
    /**
     * Mandatory platform trust boundary for each transition, after size/hash checks.
     * Implementations must inspect the actual MP4 tracks and fully decode the only track:
     * exactly one H.264/AVC video track, SDR 8-bit 4:2:0, no audio/subtitle/data tracks,
     * no encryption, rotation or format changes, package-declared dimensions and duration.
     * Reject missing dimensions/duration and unsupported media facts rather than trust a
     * MIME or filename. Duration must be positive, at most MAX_TRANSITION_MS, and
     * ceil(actualUs / 1000) must equal durationMs. Decode
     * through EOS, reject corrupt/truncated samples and check cancellation throughout.
     * Media may not access network/external resources. Never register or publish the root
     * from this callback: the caller commits only after the entire package has succeeded.
     */
    @FunctionalInterface public interface VideoValidator {
        void validate(File file, ViewerScene.Asset declared, CancelCheck cancel) throws IOException;
    }
    public static final class FileEntry {
        public final String path, sha256;
        public final long byteLength;
        public FileEntry(String path, long byteLength, String sha256) {
            this.path = path; this.byteLength = byteLength; this.sha256 = sha256;
        }
    }
    public static final class LoadedPackage {
        public final ViewerScene scene;
        public final String contentDigest;
        public final List<FileEntry> files;
        public LoadedPackage(ViewerScene scene, String contentDigest, List<FileEntry> files) {
            this.scene = scene; this.contentDigest = contentDigest;
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
        }
    }
    public static byte[] writeScene(ViewerScene scene) {
        validateScene(scene); byte[] bytes = StrictJson.canonical(sceneObject(scene));
        require(bytes.length <= MAX_SCENE_BYTES, "Scene exceeds byte budget");
        // Enforce exactly the same parser-wide limits on locally authored data.
        StrictJson.parse(bytes, MAX_SCENE_BYTES); return bytes;
    }
    public static ViewerScene parseScene(byte[] bytes) {
        Map<String, Object> root = object(StrictJson.parse(bytes, MAX_SCENE_BYTES),
                "schemaVersion", "policyVersion", "compilerVersion", "releaseId", "title", "goal", "createdAt",
                "startStateId", "states", "edges", "hotspots", "regions", "assets");
        int schemaVersion = smallInt(root.get("schemaVersion"));
        String policyVersion = string(root.get("policyVersion")), compilerVersion = string(root.get("compilerVersion"));
        version(schemaVersion, policyVersion, compilerVersion);
        List<ViewerScene.Region> regions = new ArrayList<>();
        for (Object raw : array(root.get("regions"))) {
            Map<String, Object> r = object(raw, "id", "stateId", "baseAssetId", "assetId", "name", "kind",
                    "coordinateSpace", "sourceWidth", "sourceHeight", "bbox", "group", "zIndex", "anchor");
            require("screenshotCrop".equals(string(r.get("kind"))) && "source-pixels".equals(string(r.get("coordinateSpace"))),
                    "Unsupported region kind or coordinate space");
            Map<String, Object> b = object(r.get("bbox"), "x", "y", "width", "height");
            Map<String, Object> a = object(r.get("anchor"), "coordinateSpace", "x", "y");
            require("layer-normalized".equals(string(a.get("coordinateSpace"))), "Unsupported anchor coordinates");
            regions.add(new ViewerScene.Region(string(r.get("id")), string(r.get("stateId")), string(r.get("baseAssetId")),
                    string(r.get("assetId")), string(r.get("name")), smallInt(r.get("sourceWidth")), smallInt(r.get("sourceHeight")),
                    new ViewerScene.PixelRect(smallInt(b.get("x")), smallInt(b.get("y")), smallInt(b.get("width")), smallInt(b.get("height"))),
                    nullableString(r.get("group")), signedInt(r.get("zIndex")), new ViewerScene.Anchor(decimal(a.get("x")), decimal(a.get("y")))));
        }
        List<ViewerScene.State> states = new ArrayList<>();
        for (Object raw : array(root.get("states"))) {
            Map<String, Object> s = object(raw, "id", "imageAssetId", "width", "height", "title", "description", "sourceKind", "terminal");
            require(s.get("terminal") instanceof Boolean, "Invalid terminal flag");
            states.add(new ViewerScene.State(string(s.get("id")), string(s.get("imageAssetId")),
                    smallInt(s.get("width")), smallInt(s.get("height")), string(s.get("title")),
                    string(s.get("description")), string(s.get("sourceKind")), (Boolean) s.get("terminal")));
        }
        List<ViewerScene.Edge> edges = new ArrayList<>();
        for (Object raw : array(root.get("edges"))) {
            Map<String, Object> e = object(raw, "id", "fromStateId", "to", "hotspotId", "label", "trigger", "transitionAssetId", "sourceKind");
            Map<String, Object> to = map(e.get("to"));
            require(to.size() == 1 && (to.containsKey("stateId") || to.containsKey("endLabel")), "Invalid edge destination");
            edges.add(new ViewerScene.Edge(string(e.get("id")), string(e.get("fromStateId")),
                    to.containsKey("stateId") ? string(to.get("stateId")) : null,
                    to.containsKey("endLabel") ? string(to.get("endLabel")) : null,
                    nullableString(e.get("hotspotId")), string(e.get("label")), string(e.get("trigger")), string(e.get("sourceKind")),
                    nullableString(e.get("transitionAssetId"))));
        }
        List<ViewerScene.Hotspot> hotspots = new ArrayList<>();
        for (Object raw : array(root.get("hotspots"))) {
            Map<String, Object> h = object(raw, "id", "stateId", "label", "coordinateSpace", "rect");
            require("state-normalized".equals(string(h.get("coordinateSpace"))), "Unsupported coordinates");
            Map<String, Object> r = object(h.get("rect"), "x", "y", "width", "height");
            hotspots.add(new ViewerScene.Hotspot(string(h.get("id")), string(h.get("stateId")), string(h.get("label")),
                    new ViewerScene.Rect(decimal(r.get("x")), decimal(r.get("y")), decimal(r.get("width")), decimal(r.get("height")))));
        }
        List<ViewerScene.Asset> assets = new ArrayList<>();
        for (Object raw : array(root.get("assets"))) {
            Map<String, Object> a = object(raw, "id", "path", "role", "mime", "byteLength", "sha256", "width", "height", "durationMs");
            assets.add(new ViewerScene.Asset(string(a.get("id")), string(a.get("path")), string(a.get("mime")),
                    integer(a.get("byteLength")), string(a.get("sha256")), smallInt(a.get("width")), smallInt(a.get("height")),
                    string(a.get("role")), a.get("durationMs") == null ? null : integer(a.get("durationMs"))));
        }
        ViewerScene scene = new ViewerScene(schemaVersion, policyVersion, compilerVersion,
                string(root.get("releaseId")), string(root.get("title")),
                string(root.get("goal")), integer(root.get("createdAt")), string(root.get("startStateId")), states, edges, hotspots, regions, assets);
        validateScene(scene); return scene;
    }
    public static String contentDigest(ViewerScene scene) { return digest(writeScene(scene)); }
    public static List<FileEntry> fileList(ViewerScene scene) {
        byte[] json = writeScene(scene); List<FileEntry> files = new ArrayList<>();
        files.add(new FileEntry("scene.json", json.length, digest(json)));
        for (ViewerScene.Asset a : scene.assets) files.add(new FileEntry(a.path, a.byteLength, a.sha256));
        files.sort((a, b) -> a.path.compareTo(b.path)); return Collections.unmodifiableList(files);
    }
    public static byte[] manifestBytes(ViewerScene scene) {
        List<Object> files = new ArrayList<>();
        for (FileEntry f : fileList(scene)) files.add(obj("path", f.path, "byteLength", f.byteLength, "sha256", f.sha256));
        byte[] manifest = StrictJson.canonical(obj("schemaVersion", scene.schemaVersion, "exportKind", "viewer", "releaseId", scene.releaseId,
                "contentDigest", contentDigest(scene), "files", files));
        require(manifest.length <= MAX_MANIFEST_BYTES, "Manifest exceeds budget"); return manifest;
    }
    public static void validateScene(ViewerScene scene) {
        require(scene != null, "Missing scene"); version(scene.schemaVersion, scene.policyVersion, scene.compilerVersion); id(scene.releaseId); text(scene.title, 240, true); text(scene.goal, 8192, false);
        require(scene.createdAt >= 0 && scene.createdAt <= StrictJson.MAX_SAFE_INTEGER, "Invalid creation time"); id(scene.startStateId);
        require(!scene.states.isEmpty() && scene.states.size() <= 40 && scene.edges.size() <= 80
                && scene.hotspots.size() <= 240 && scene.regions.size() <= MAX_REGIONS && !scene.assets.isEmpty() && scene.assets.size() <= (scene.schemaVersion == 1 ? 40 : MAX_ASSETS), "Scene exceeds object budget");
        require(scene.schemaVersion == 3 || scene.regions.isEmpty(), "Regions require scene schema 3");
        Set<String> allIds = new HashSet<>();
        Map<String, ViewerScene.State> states = new HashMap<>(); Map<String, ViewerScene.Asset> assets = new HashMap<>();
        Map<String, ViewerScene.Hotspot> hotspots = new HashMap<>();
        long total = 0;
        for (ViewerScene.Asset a : scene.assets) {
            require(a != null, "Missing asset"); unique(allIds, a.id); safePath(a.path);
            if (ViewerScene.Asset.ROLE_IMAGE.equals(a.role) || scene.schemaVersion == 3 && ViewerScene.Asset.ROLE_REGION_CROP.equals(a.role)) {
                require(a.path.equals("assets/" + a.id + ".png") && "image/png".equals(a.mime)
                        && a.durationMs == null, "Invalid package-local PNG asset");
            } else {
                require(scene.schemaVersion >= 2 && ViewerScene.Asset.ROLE_TRANSITION.equals(a.role)
                        && a.path.equals("assets/" + a.id + ".mp4") && "video/mp4".equals(a.mime)
                        && a.durationMs != null && a.durationMs > 0 && a.durationMs <= MAX_TRANSITION_MS,
                        "Unsupported transition asset or duration");
            }
            require(a.byteLength > 0 && a.byteLength <= MAX_PACKAGE_BYTES && HASH.matcher(a.sha256 == null ? "" : a.sha256).matches(), "Invalid asset size or digest");
            dimensions(a.width, a.height); assets.put(a.id, a); total += a.byteLength;
        }
        require(total <= MAX_PACKAGE_BYTES, "Assets exceed unpacked byte budget");
        Set<String> usedAssets = new HashSet<>();
        for (ViewerScene.State s : scene.states) {
            require(s != null, "Missing state"); unique(allIds, s.id); source(s.sourceKind); text(s.title, 240, true); text(s.description, 8192, false);
            ViewerScene.Asset a = assets.get(s.imageAssetId); require(a != null && ViewerScene.Asset.ROLE_IMAGE.equals(a.role) && a.width == s.width && a.height == s.height, "Missing image or state dimensions mismatch");
            usedAssets.add(a.id); states.put(s.id, s);
        }
        require(states.containsKey(scene.startStateId), "Missing start state");
        Map<String, Integer> hotspotCounts = new HashMap<>();
        for (ViewerScene.Hotspot h : scene.hotspots) {
            require(h != null, "Missing hotspot"); unique(allIds, h.id); text(h.label, 240, true);
            require(states.containsKey(h.stateId), "Hotspot references missing state");
            int count = hotspotCounts.containsKey(h.stateId) ? hotspotCounts.get(h.stateId) + 1 : 1;
            require(count <= 6, "More than six hotspots in one state"); hotspotCounts.put(h.stateId, count);
            require(h.rect != null, "Missing hotspot rectangle");
            BigDecimal x = coord(h.rect.x), y = coord(h.rect.y), w = coord(h.rect.width), hgt = coord(h.rect.height);
            require(w.signum() > 0 && hgt.signum() > 0 && x.add(w).compareTo(BigDecimal.ONE) <= 0
                    && y.add(hgt).compareTo(BigDecimal.ONE) <= 0, "Hotspot is empty or outside image"); hotspots.put(h.id, h);
        }
        Map<String, List<String>> forward = new HashMap<>(), reverse = new HashMap<>();
        Set<String> hasExit = new HashSet<>(), ending = new HashSet<>(), usedHotspots = new HashSet<>(), continueStates = new HashSet<>();
        for (ViewerScene.State s : scene.states) if (s.terminal) ending.add(s.id);
        long boundTransitionMs = 0;
        for (ViewerScene.Edge e : scene.edges) {
            require(e != null, "Missing edge"); unique(allIds, e.id); text(e.label, 240, true); source(e.sourceKind);
            ViewerScene.State from = states.get(e.fromStateId);
            require(from != null && !from.terminal, "Edge leaves missing or terminal state");
            require((e.toStateId == null) != (e.endLabel == null), "Edge needs exactly one destination");
            if (e.toStateId != null) {
                require(states.containsKey(e.toStateId), "Edge references missing target");
                forward.computeIfAbsent(e.fromStateId, k -> new ArrayList<>()).add(e.toStateId);
                reverse.computeIfAbsent(e.toStateId, k -> new ArrayList<>()).add(e.fromStateId);
            } else { text(e.endLabel, 240, true); ending.add(e.fromStateId); }
            if ("tap".equals(e.trigger)) {
                ViewerScene.Hotspot h = hotspots.get(e.hotspotId);
                require(h != null && h.stateId.equals(e.fromStateId), "Tap needs a hotspot on its source state");
                require(usedHotspots.add(h.id), "A hotspot may have only one tap action");
            } else {
                require("continue".equals(e.trigger) && e.hotspotId == null && "authored".equals(e.sourceKind),
                        "Only tap or explicitly authored continue actions are supported");
                require(continueStates.add(e.fromStateId), "A state may have only one continue action");
            }
            if (e.transitionAssetId != null) {
                ViewerScene.Asset transition = assets.get(e.transitionAssetId);
                require(scene.schemaVersion >= 2 && transition != null
                        && ViewerScene.Asset.ROLE_TRANSITION.equals(transition.role), "Missing transition video or invalid role");
                usedAssets.add(transition.id); boundTransitionMs += transition.durationMs;
                require(boundTransitionMs <= MAX_TOTAL_TRANSITION_MS, "Bound transitions exceed 60 seconds");
            }
            hasExit.add(e.fromStateId);
        }
        Set<String> cropAssets = new HashSet<>();
        Map<String, Integer> regionCounts = new HashMap<>();
        for (ViewerScene.Region r : scene.regions) {
            require(r != null, "Missing region");
            int regionCount = regionCounts.getOrDefault(r.stateId, 0) + 1;
            require(regionCount <= 12, "More than twelve regions in one state"); regionCounts.put(r.stateId, regionCount);
            unique(allIds, r.id); text(r.name, 240, true);
            if (r.group != null) text(r.group, 120, true);
            ViewerScene.State state = states.get(r.stateId);
            ViewerScene.Asset base = assets.get(r.baseAssetId), crop = assets.get(r.assetId);
            require(state != null && state.imageAssetId.equals(r.baseAssetId) && base != null
                    && ViewerScene.Asset.ROLE_IMAGE.equals(base.role), "Region base must be its state image");
            require(r.sourceWidth == base.width && r.sourceHeight == base.height, "Region source dimensions mismatch");
            require(r.bbox != null && r.bbox.x >= 0 && r.bbox.y >= 0 && r.bbox.width > 0 && r.bbox.height > 0
                    && (long)r.bbox.x + r.bbox.width <= base.width && (long)r.bbox.y + r.bbox.height <= base.height,
                    "Region bounds are empty or outside its base");
            require(crop != null && ViewerScene.Asset.ROLE_REGION_CROP.equals(crop.role)
                    && crop.width == r.bbox.width && crop.height == r.bbox.height && cropAssets.add(crop.id),
                    "Region crop missing, reused or wrong size");
            require(r.anchor != null, "Missing region anchor"); coord(r.anchor.x); coord(r.anchor.y);
            require(r.zIndex >= -10000 && r.zIndex <= 10000, "Region layer exceeds limit");
            usedAssets.add(crop.id);
        }
        require(usedAssets.size() == assets.size(), "Unreferenced asset");
        require(usedHotspots.size() == hotspots.size(), "Hotspot has no action");
        for (ViewerScene.State s : scene.states) require(s.terminal || hasExit.contains(s.id), "Nonterminal state has no exit");
        require(reachable(Collections.singleton(scene.startStateId), forward).size() == states.size(), "Unreachable state");
        require(reachable(ending, reverse).size() == states.size(), "A graph component has no route to an ending");
        byte[] json = StrictJson.canonical(sceneObject(scene));
        require(json.length <= MAX_SCENE_BYTES, "Scene exceeds byte budget");
        StrictJson.parse(json, MAX_SCENE_BYTES);
    }
    public static void validateDirectory(ViewerScene scene, File root, CancelCheck cancel) throws IOException {
        validateDirectory(scene, root, cancel, null);
    }
    public static void validateDirectory(ViewerScene scene, File root, CancelCheck cancel, VideoValidator validator) throws IOException {
        check(cancel); validateScene(scene); secureRoot(root);
        Set<String> expected = new HashSet<>();
        for (ViewerScene.Asset asset : scene.assets) {
            check(cancel); expected.add(asset.path); File file = resolve(root, asset.path);
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() == asset.byteLength, "Missing or wrong-sized asset");
            require(hash(file, cancel).equals(asset.sha256), "Asset digest mismatch");
            if (!ViewerScene.Asset.ROLE_TRANSITION.equals(asset.role)) SafePng.validate(file, asset.width, asset.height, cancel);
            else {
                require(validator != null, "Video assets require a platform full-decode validator");
                validator.validate(file, asset, cancel); check(cancel);
            }
            require(file.length() == asset.byteLength && hash(file, cancel).equals(asset.sha256), "Media changed during validation");
        }
        validateRegionPixels(scene, root, cancel);
        // Authoring roots may also contain the canonical scene/manifest; no arbitrary payloads.
        expected.add("scene.json"); expected.add("manifest.json");
        listDirectory(root.toPath(), root.toPath(), expected, cancel);
        byte[] sceneBytes = writeScene(scene), manifest = manifestBytes(scene);
        long total = sceneBytes.length + manifest.length;
        for (ViewerScene.Asset a : scene.assets) total += a.byteLength;
        require(total <= MAX_PACKAGE_BYTES, "Package exceeds unpacked byte budget"); check(cancel);
    }
    /** Shared viewer/AI boundary. A matching manifest hash cannot disguise invented crop pixels. */
    public static void validateRegionPixels(ViewerScene scene, File root, CancelCheck cancel) throws IOException {
        validateRegionPixels(scene, root, null, cancel);
    }
    public static void validateRegionPixels(ViewerScene scene, File root, String regionId, CancelCheck cancel) throws IOException {
        check(cancel); validateScene(scene); secureRoot(root);
        require(regionId == null || scene.regions.stream().anyMatch(r -> r.id.equals(regionId)), "Missing requested region");
        Map<String, ViewerScene.Asset> assets = new HashMap<>();
        for (ViewerScene.Asset a : scene.assets) assets.put(a.id, a);
        for (ViewerScene.Region region : scene.regions) {
            if (regionId != null && !region.id.equals(regionId)) continue;
            check(cancel);
            ViewerScene.Asset base = assets.get(region.baseAssetId), crop = assets.get(region.assetId);
            File baseFile = resolve(root, base.path), cropFile = resolve(root, crop.path);
            require(baseFile.isFile() && cropFile.isFile() && baseFile.length() == base.byteLength && cropFile.length() == crop.byteLength
                    && hash(baseFile, cancel).equals(base.sha256) && hash(cropFile, cancel).equals(crop.sha256), "Region media digest mismatch");
            SafePng.validateCrop(baseFile, base.width, base.height, cropFile, region.bbox, cancel);
            require(baseFile.length() == base.byteLength && cropFile.length() == crop.byteLength
                    && hash(baseFile, cancel).equals(base.sha256) && hash(cropFile, cancel).equals(crop.sha256), "Region media changed during pixel comparison");
        }
    }
    public static void writePackage(ViewerScene scene, File assetRoot, File outputZip, CancelCheck cancel) throws IOException {
        writePackage(scene, assetRoot, outputZip, cancel, null);
    }
    public static void writePackage(ViewerScene scene, File assetRoot, File outputZip, CancelCheck cancel, VideoValidator validator) throws IOException {
        validateDirectory(scene, assetRoot, cancel, validator);
        require(!outputZip.exists(), "Package output must be a new file");
        require(!outputZip.toPath().toAbsolutePath().normalize().startsWith(assetRoot.toPath().toAbsolutePath().normalize()), "Package output must be outside the asset root");
        Map<String, byte[]> json = new LinkedHashMap<>(); json.put("manifest.json", manifestBytes(scene)); json.put("scene.json", writeScene(scene));
        StrictZip.write(outputZip, assetRoot, fileList(scene), json, cancel);
    }
    public static LoadedPackage readPackage(File zip, File emptyDestination, CancelCheck cancel) throws IOException {
        return readPackage(zip, emptyDestination, cancel, null);
    }
    public static LoadedPackage readPackage(File zip, File emptyDestination, CancelCheck cancel, VideoValidator validator) throws IOException {
        check(cancel); secureRoot(emptyDestination);
        String[] existing = emptyDestination.list(); require(existing != null && existing.length == 0, "Import destination must be empty");
        // Destination is owned by the caller and remains isolated until this method AND platform decode succeed.
        try {
            StrictZip.extract(zip, emptyDestination, cancel); check(cancel);
            byte[] manifestBytes = readBytes(resolve(emptyDestination, "manifest.json"), MAX_MANIFEST_BYTES, cancel);
            Map<String, Object> manifest = object(StrictJson.parse(manifestBytes, MAX_MANIFEST_BYTES),
                    "schemaVersion", "exportKind", "releaseId", "contentDigest", "files");
            require("viewer".equals(string(manifest.get("exportKind"))), "Unsupported package type");
            ViewerScene scene = parseScene(readBytes(resolve(emptyDestination, "scene.json"), MAX_SCENE_BYTES, cancel));
            require(integer(manifest.get("schemaVersion")) == scene.schemaVersion, "Manifest/scene schema mismatch");
            String digest = contentDigest(scene);
            require(scene.releaseId.equals(string(manifest.get("releaseId"))) && digest.equals(string(manifest.get("contentDigest"))), "Manifest identity mismatch");
            Map<String, FileEntry> declared = new HashMap<>();
            for (Object raw : array(manifest.get("files"))) {
                Map<String, Object> f = object(raw, "path", "byteLength", "sha256");
                String path = string(f.get("path")); safePath(path); require(!path.equals("manifest.json"), "Manifest cannot list itself");
                long length = integer(f.get("byteLength")); String sha = string(f.get("sha256"));
                require(length > 0 && length <= MAX_PACKAGE_BYTES && HASH.matcher(sha).matches()
                        && !declared.containsKey(path), "Invalid or duplicate manifest file");
                declared.put(path, new FileEntry(path, length, sha));
            }
            List<FileEntry> canonicalFiles = fileList(scene);
            require(declared.size() == canonicalFiles.size(), "Manifest file set mismatch");
            Set<String> expected = new HashSet<>(); expected.add("manifest.json");
            for (FileEntry f : canonicalFiles) {
                check(cancel); FileEntry listed = declared.get(f.path); require(listed != null, "Manifest is missing a file");
                File file = resolve(emptyDestination, f.path);
                // scene.json can be noncanonical JSON; hash its actual bytes separately from canonical contentDigest.
                require(file.length() == listed.byteLength && hash(file, cancel).equals(listed.sha256), "Manifest byte length or hash mismatch");
                if (!f.path.equals("scene.json")) require(f.byteLength == listed.byteLength && f.sha256.equals(listed.sha256), "Asset manifest mismatch");
                expected.add(f.path);
            }
            listDirectory(emptyDestination.toPath(), emptyDestination.toPath(), expected, cancel);
            require(countFiles(emptyDestination) == expected.size(), "Missing or undeclared package file");
            validateDirectory(scene, emptyDestination, cancel, validator); check(cancel);
            List<FileEntry> actualFiles = new ArrayList<>(declared.values()); actualFiles.sort((a,b) -> a.path.compareTo(b.path));
            return new LoadedPackage(scene, digest, actualFiles);
        } catch (IOException | RuntimeException failure) {
            try { clearDirectory(emptyDestination.toPath()); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public static String sha256(File file) throws IOException { return hash(file, () -> {}); }
    static String hash(File file, CancelCheck cancel) throws IOException {
        MessageDigest digest = newDigest(); byte[] block = new byte[16384]; long count = 0;
        try (InputStream in = new FileInputStream(file)) { int n; while ((n = in.read(block)) != -1) {
            check(cancel); count += n; require(count <= MAX_PACKAGE_BYTES, "Hash input exceeds byte budget"); digest.update(block, 0, n);
        }} check(cancel); return hex(digest.digest());
    }
    static byte[] readBytes(File file, int limit, CancelCheck cancel) throws IOException {
        require(file.isFile() && file.length() <= limit, "JSON missing or exceeds byte budget");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] block = new byte[8192];
        try (InputStream in = new FileInputStream(file)) { int n; while ((n = in.read(block)) != -1) {
            check(cancel); require(out.size() + n <= limit, "JSON exceeds byte budget"); out.write(block, 0, n);
        }} return out.toByteArray();
    }
    static void check(CancelCheck cancel) { require(cancel != null, "Cancellation callback required"); cancel.check(); }
    static MessageDigest newDigest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }
    static String digest(byte[] bytes) { return hex(newDigest().digest(bytes)); }
    static String hex(byte[] bytes) { StringBuilder b = new StringBuilder(bytes.length * 2); for (byte n : bytes) b.append(Character.forDigit((n >>> 4) & 15, 16)).append(Character.forDigit(n & 15, 16)); return b.toString(); }
    static void safePath(String path) {
        require(path != null && (path.equals("manifest.json") || path.equals("scene.json")
                || path.startsWith("assets/") && (path.endsWith(".png") || path.endsWith(".mp4")) && UUID.matcher(path.substring(7, path.length() - 4)).matches()), "Unsafe or unsupported package path");
    }
    static File resolve(File root, String relative) throws IOException {
        safePath(relative); Path base = root.toPath().toAbsolutePath().normalize(); Path target = base.resolve(relative).normalize();
        require(target.startsWith(base), "Path escapes package root");
        for (Path at = target; at != null && at.startsWith(base); at = at.getParent()) require(!Files.isSymbolicLink(at), "Symlink in package path");
        return target.toFile();
    }
    private static void secureRoot(File root) throws IOException {
        require(root != null && Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS), "Package root must be an existing directory");
        // The caller owns this newly created private directory. Android may use trusted
        // OS-managed aliases above it (for example /data/user/0); reject links in the
        // package root itself and every entry, rather than rejecting those OS aliases.
        require(!Files.isSymbolicLink(root.toPath()), "Symlink package root");
    }
    private static void listDirectory(Path root, Path directory, Set<String> expected, CancelCheck cancel) throws IOException {
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                check(cancel); require(!Files.isSymbolicLink(path), "Symlink in package directory");
                String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    require(relative.equals("assets"), "Unexpected package directory"); listDirectory(root, path, expected, cancel);
                } else require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && expected.contains(relative), "Undeclared package file");
            }
        }
    }
    private static int countFiles(File root) throws IOException {
        int result = 0; File[] list = root.listFiles(); require(list != null, "Unreadable package directory");
        for (File file : list) result += file.isDirectory() ? countFiles(file) : 1; return result;
    }
    static void clearDirectory(Path directory) throws IOException {
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) { if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) clearDirectory(path); Files.delete(path); }
        }
    }
    private static Set<String> reachable(Set<String> seeds, Map<String, List<String>> graph) {
        Set<String> visited = new HashSet<>(seeds); ArrayDeque<String> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty()) for (String target : graph.getOrDefault(queue.removeFirst(), Collections.emptyList())) if (visited.add(target)) queue.add(target);
        return visited;
    }
    private static void version(int schemaVersion, String policyVersion, String compilerVersion) {
        require((schemaVersion == 1 && POLICY_VERSION.equals(policyVersion)
                || schemaVersion == 2 && VIDEO_POLICY_VERSION.equals(policyVersion)
                || schemaVersion == 3 && REGION_POLICY_VERSION.equals(policyVersion))
                && COMPILER_VERSION.equals(compilerVersion), "Unsupported viewer schema, policy or compiler version");
    }
    private static void dimensions(int w, int h) { require(w > 0 && h > 0 && Math.min(w, h) <= 1080 && Math.max(w, h) <= 2400, "Media dimensions exceed profile"); }
    private static void id(String value) { require(value != null && UUID.matcher(value).matches(), "Invalid canonical UUID"); }
    private static void unique(Set<String> ids, String value) { id(value); require(ids.add(value), "Duplicate object id"); }
    private static void source(String value) { require("recorded".equals(value) || "authored".equals(value) || "imported".equals(value), "Unsupported source kind"); }
    private static void text(String value, int max, boolean nonblank) { StrictJson.validUnicode(value); require(value.length() <= max && (!nonblank || !value.trim().isEmpty()), "Invalid or oversized display text"); }
    private static BigDecimal coord(double value) {
        require(Double.isFinite(value) && value >= 0 && value <= 1, "Invalid normalized coordinate");
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }
    private static Map<String, Object> sceneObject(ViewerScene s) {
        List<Object> states = new ArrayList<>(), edges = new ArrayList<>(), hotspots = new ArrayList<>(), regions = new ArrayList<>(), assets = new ArrayList<>();
        for (ViewerScene.State a : s.states) states.add(obj("id", a.id, "imageAssetId", a.imageAssetId, "width", a.width, "height", a.height,
                "title", a.title, "description", a.description, "sourceKind", a.sourceKind, "terminal", a.terminal));
        for (ViewerScene.Edge a : s.edges) edges.add(obj("id", a.id, "fromStateId", a.fromStateId,
                "to", a.toStateId == null ? obj("endLabel", a.endLabel) : obj("stateId", a.toStateId), "hotspotId", a.hotspotId,
                "label", a.label, "trigger", a.trigger, "transitionAssetId", a.transitionAssetId, "sourceKind", a.sourceKind));
        for (ViewerScene.Hotspot a : s.hotspots) hotspots.add(obj("id", a.id, "stateId", a.stateId, "label", a.label,
                "coordinateSpace", "state-normalized", "rect", obj("x", coord(a.rect.x), "y", coord(a.rect.y), "width", coord(a.rect.width), "height", coord(a.rect.height))));
        for (ViewerScene.Region r : s.regions) regions.add(obj("id", r.id, "stateId", r.stateId,
                "baseAssetId", r.baseAssetId, "assetId", r.assetId, "name", r.name, "kind", "screenshotCrop",
                "coordinateSpace", "source-pixels", "sourceWidth", r.sourceWidth, "sourceHeight", r.sourceHeight,
                "bbox", obj("x", r.bbox.x, "y", r.bbox.y, "width", r.bbox.width, "height", r.bbox.height),
                "group", r.group, "zIndex", r.zIndex,
                "anchor", obj("coordinateSpace", "layer-normalized", "x", coord(r.anchor.x), "y", coord(r.anchor.y))));
        for (ViewerScene.Asset a : s.assets) assets.add(obj("id", a.id, "path", a.path, "role", a.role, "mime", a.mime,
                "byteLength", a.byteLength, "sha256", a.sha256, "width", a.width, "height", a.height, "durationMs", a.durationMs));
        return obj("schemaVersion", s.schemaVersion, "policyVersion", s.policyVersion, "compilerVersion", s.compilerVersion,
                "releaseId", s.releaseId, "title", s.title, "goal", s.goal, "createdAt", s.createdAt, "startStateId", s.startStateId,
                "states", states, "edges", edges, "hotspots", hotspots, "regions", regions, "assets", assets);
    }
    private static Map<String, Object> obj(Object... values) { Map<String, Object> r = new LinkedHashMap<>(); for (int i=0;i<values.length;i+=2) r.put((String)values[i], values[i+1]); return r; }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object raw) { require(raw instanceof Map, "Expected JSON object"); return (Map<String,Object>) raw; }
    private static Map<String, Object> object(Object raw, String... fields) { Map<String, Object> m = map(raw); require(m.keySet().equals(new HashSet<>(Arrays.asList(fields))), "Missing or unsupported JSON field"); return m; }
    @SuppressWarnings("unchecked") private static List<Object> array(Object raw) { require(raw instanceof List, "Expected JSON array"); return (List<Object>) raw; }
    private static String string(Object raw) { require(raw instanceof String, "Expected JSON string"); return (String) raw; }
    private static String nullableString(Object raw) { return raw == null ? null : string(raw); }
    private static long integer(Object raw) { require(raw instanceof BigDecimal && ((BigDecimal)raw).scale() <= 0, "Expected JSON integer"); return ((BigDecimal)raw).longValueExact(); }
    private static int signedInt(Object raw) { long n = integer(raw); require(n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE, "Invalid signed integer"); return (int)n; }
    private static int smallInt(Object raw) { long n = integer(raw); require(n >= 0 && n <= Integer.MAX_VALUE, "Invalid integer dimension"); return (int)n; }
    private static double decimal(Object raw) { require(raw instanceof BigDecimal, "Expected coordinate number"); BigDecimal n = (BigDecimal)raw; require(n.signum() >= 0 && n.compareTo(BigDecimal.ONE) <= 0, "Coordinate out of range"); return n.doubleValue(); }
}
