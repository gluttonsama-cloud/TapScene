import com.tapscene.packageformat.ViewerPackageCodec;
import com.tapscene.packageformat.ViewerScene;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.zip.Deflater;
import java.util.zip.CRC32;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/** Real host PNG/ZIP bytes plus schema3 negative corpus; no Android UI claim. */
public final class RegionPackageChecks {
    private RegionPackageChecks() { }
    public static void run(Path root) throws Exception {
        Files.createDirectories(root.resolve("assets"));
        BufferedImage base = new BufferedImage(8, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) for (int x = 0; x < 8; x++) base.setRGB(x, y, 0xff000000 | x * 1024 + y * 257);
        Path baseFile = root.resolve("assets/" + id(1) + ".png"), cropFile = root.resolve("assets/" + id(2) + ".png");
        ImageIO.write(base, "png", baseFile.toFile()); ImageIO.write(base.getSubimage(2, 3, 4, 5), "png", cropFile.toFile());
        ViewerScene.Asset image = asset(baseFile, 1, 8, 10, ViewerScene.Asset.ROLE_IMAGE);
        ViewerScene.Asset crop = asset(cropFile, 2, 4, 5, ViewerScene.Asset.ROLE_REGION_CROP);
        ViewerScene.Region region = new ViewerScene.Region(id(3), id(4), image.id, crop.id, "Visible crop", 8, 10,
                new ViewerScene.PixelRect(2, 3, 4, 5), "Main", -1, new ViewerScene.Anchor(.25, .75));
        ViewerScene scene = scene(region, List.of(image, crop));
        byte[] json = ViewerPackageCodec.writeScene(scene);
        ViewerScene parsed = ViewerPackageCodec.parseScene(json);
        require(Arrays.equals(json, ViewerPackageCodec.writeScene(parsed)), "Region canonical roundtrip differs");
        require(parsed.regions.size() == 1 && parsed.regions.get(0).bbox.x == 2 && parsed.regions.get(0).zIndex == -1,
                "Lost pixel bounds or signed layer");
        Path archive = root.resolveSibling("synthetic-regions.tapscene");
        ViewerPackageCodec.writePackage(scene, root.toFile(), archive.toFile(), () -> {});
        Path imported = Files.createDirectory(root.resolveSibling("imported-regions"));
        ViewerScene loaded = ViewerPackageCodec.readPackage(archive.toFile(), imported.toFile(), () -> {}).scene;
        require(ViewerPackageCodec.contentDigest(scene).equals(ViewerPackageCodec.contentDigest(loaded)), "Imported region digest differs");
        BufferedImage actual = ImageIO.read(imported.resolve(crop.path).toFile());
        for (int y = 0; y < 5; y++) for (int x = 0; x < 4; x++) require(actual.getRGB(x, y) == base.getRGB(x + 2, y + 3), "PNG is not a real crop");
        String valid = new String(json, StandardCharsets.UTF_8);
        String[][] replacements = {
            {"\"schemaVersion\":3", "\"schemaVersion\":2"}, {"scene-regions-3", "video-viewer-2"},
            {"screenshotCrop", "nativeComponent"}, {"source-pixels", "state-normalized"},
            {"layer-normalized", "source-pixels"}, {"\"sourceWidth\":8", "\"sourceWidth\":9"},
            {"\"x\":2", "\"x\":2147483647"}, {"\"x\":2", "\"x\":-1"},
            {"\"height\":5,\"width\":4,\"x\":2", "\"height\":5,\"width\":0,\"x\":2"},
            {"\"zIndex\":-1", "\"zIndex\":10001"}, {"\"x\":0.25", "\"x\":1.1"},
            {"\"baseAssetId\":\"" + image.id, "\"baseAssetId\":\"" + crop.id},
            {"\"assetId\":\"" + crop.id, "\"assetId\":\"" + image.id},
            {"region-crop", "state-image"}, {"\"name\":\"Visible crop\"", "\"name\":\"\""},
            {"\"name\":\"Visible crop\"", "\"script\":\"evil()\",\"name\":\"Visible crop\""},
            {"\"group\":\"Main\"", "\"group\":\"\""}, {"\"zIndex\":-1", "\"zIndex\":0.5"},
        };
        for (String[] change : replacements) {
            require(valid.contains(change[0]), "Missing mutation needle " + change[0]);
            rejects(() -> ViewerPackageCodec.parseScene(valid.replace(change[0], change[1]).getBytes(StandardCharsets.UTF_8)));
        }
        List<ViewerScene.Region> duplicate = List.of(region, region);
        rejects(() -> ViewerPackageCodec.validateScene(copy(scene, duplicate, scene.assets)));
        ViewerScene.Region reuse = new ViewerScene.Region(id(8), id(4), image.id, crop.id, "Reused", 8, 10, region.bbox, null, 0, region.anchor);
        rejects(() -> ViewerPackageCodec.validateScene(copy(scene, List.of(region, reuse), scene.assets)));
        List<ViewerScene.Region> tooMany = new ArrayList<>(); List<ViewerScene.Asset> assets = new ArrayList<>(List.of(image));
        for (int i = 0; i < 13; i++) {
            String cropId = id(100 + i);
            assets.add(new ViewerScene.Asset(cropId, "assets/" + cropId + ".png", "image/png", crop.byteLength, crop.sha256, 4, 5, crop.role, null));
            tooMany.add(new ViewerScene.Region(id(200 + i), id(4), image.id, cropId, "Region " + i, 8, 10, region.bbox, null, i, region.anchor));
        }
        rejects(() -> ViewerPackageCodec.validateScene(copy(scene, tooMany, assets)));
        byte[] old = Files.readAllBytes(cropFile); Files.write(cropFile, new byte[]{1, 2, 3});
        rejects(() -> ViewerPackageCodec.validateDirectory(scene, root.toFile(), () -> {})); Files.write(cropFile, old);
        ViewerScene wrongSize = scene(region, List.of(image, new ViewerScene.Asset(crop.id, crop.path, crop.mime, crop.byteLength, crop.sha256, 5, 5, crop.role, null)));
        rejects(() -> ViewerPackageCodec.validateScene(wrongSize));
        // Matching dimensions, size declarations and freshly recomputed hashes must not bless an invented layer.
        BufferedImage invented = base.getSubimage(2, 3, 4, 5);
        invented.setRGB(1, 2, 0xffabcdef); ImageIO.write(invented, "png", cropFile.toFile());
        ViewerScene inventedScene = scene(region, List.of(image, asset(cropFile, 2, 4, 5, crop.role)));
        rejects(() -> ViewerPackageCodec.validateDirectory(inventedScene, root.toFile(), () -> {}));
        rejects(() -> ViewerPackageCodec.validateRegionPixels(inventedScene, root.toFile(), region.id, () -> {}));
        Files.write(cropFile, old);
        // Independent PNG encoder covers every PNG filter, both RGB/RGBA, and different valid byte encodings.
        for (int channels : new int[]{3, 4}) for (int filter = 0; filter <= 4; filter++) {
            BufferedImage originalBase = ImageIO.read(baseFile.toFile());
            BufferedImage originalCrop = originalBase.getSubimage(2, 3, 4, 5);
            Files.write(baseFile, encoded(originalBase, channels, filter));
            Files.write(cropFile, encoded(originalCrop, channels == 3 ? 4 : 3, (filter + 2) % 5));
            ViewerScene equivalent = scene(region, List.of(asset(baseFile, 1, 8, 10, image.role), asset(cropFile, 2, 4, 5, crop.role)));
            ViewerPackageCodec.validateRegionPixels(equivalent, root.toFile(), region.id, () -> {});
        }
        System.out.println("PASS regions pixels: recomputed-hash invented PNG rejected; filters 0-4, RGB/RGBA and differing encodings compare exactly");
        System.out.println("PASS regions schema3: real crop PNG + viewer ZIP roundtrip, canonical bytes, 23 metadata/reference/tamper/capacity rejections");
    }
    private static byte[] encoded(BufferedImage image, int channels, int filter) throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream head = new ByteArrayOutputStream(); DataOutputStream header = new DataOutputStream(head);
        header.writeInt(image.getWidth()); header.writeInt(image.getHeight()); header.writeByte(8); header.writeByte(channels == 3 ? 2 : 6);
        header.write(new byte[]{0,0,0}); chunk(png, "IHDR", head.toByteArray());
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] previous = new byte[image.getWidth() * channels];
        for (int y = 0; y < image.getHeight(); y++) {
            byte[] line = new byte[previous.length];
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y), at = x * channels;
                line[at] = (byte)(pixel >>> 16); line[at + 1] = (byte)(pixel >>> 8); line[at + 2] = (byte)pixel;
                if (channels == 4) line[at + 3] = (byte)(pixel >>> 24);
            }
            raw.write(filter);
            for (int i = 0; i < line.length; i++) {
                int left = i >= channels ? line[i - channels] & 255 : 0;
                int up = previous[i] & 255, corner = i >= channels ? previous[i - channels] & 255 : 0;
                int p = left + up - corner, dl = Math.abs(p - left), du = Math.abs(p - up), dc = Math.abs(p - corner);
                int predictor = filter == 0 ? 0 : filter == 1 ? left : filter == 2 ? up : filter == 3 ? (left + up) / 2
                        : dl <= du && dl <= dc ? left : du <= dc ? up : corner;
                raw.write(((line[i] & 255) - predictor) & 255);
            }
            previous = line;
        }
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw.toByteArray()); deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(); byte[] block = new byte[512];
            while (!deflater.finished()) { int n = deflater.deflate(block); compressed.write(block, 0, n); }
            byte[] idat = compressed.toByteArray();
            int middle = idat.length / 2;
            chunk(png, "IDAT", Arrays.copyOfRange(idat, 0, middle)); chunk(png, "IDAT", Arrays.copyOfRange(idat, middle, idat.length));
        } finally { deflater.end(); }
        chunk(png, "IEND", new byte[0]); return png.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream output, String type, byte[] data) throws Exception {
        byte[] tag = type.getBytes(StandardCharsets.US_ASCII); CRC32 crc = new CRC32(); crc.update(tag); crc.update(data);
        DataOutputStream sink = new DataOutputStream(output); sink.writeInt(data.length); sink.write(tag); sink.write(data); sink.writeInt((int)crc.getValue());
    }
    private static ViewerScene scene(ViewerScene.Region region, List<ViewerScene.Asset> assets) {
        return new ViewerScene(3, ViewerPackageCodec.REGION_POLICY_VERSION, ViewerPackageCodec.COMPILER_VERSION,
                id(5), "Region fixture", "No private data", 1, id(4),
                List.of(new ViewerScene.State(id(4), id(1), 8, 10, "End", "Safe base", "authored", true)),
                List.of(), List.of(), List.of(region), assets);
    }
    private static ViewerScene copy(ViewerScene s, List<ViewerScene.Region> regions, List<ViewerScene.Asset> assets) {
        return new ViewerScene(s.schemaVersion, s.policyVersion, s.compilerVersion, s.releaseId, s.title, s.goal, s.createdAt,
                s.startStateId, s.states, s.edges, s.hotspots, regions, assets);
    }
    private static ViewerScene.Asset asset(Path p, int id, int w, int h, String role) throws Exception {
        return new ViewerScene.Asset(id(id), "assets/" + p.getFileName(), "image/png", Files.size(p), ViewerPackageCodec.sha256(p.toFile()), w, h, role, null);
    }
    private static String id(int n) { return String.format("a1000000-0000-0000-0000-%012d", n); }
    private static void require(boolean v, String msg) { if (!v) throw new AssertionError(msg); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked work) throws Exception { try { work.run(); } catch (IllegalArgumentException | java.io.IOException expected) { return; } throw new AssertionError("Invalid region accepted"); }
}
