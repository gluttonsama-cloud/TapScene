package com.tapscene.packageformat;

import static com.tapscene.packageformat.StrictJson.require;
import static com.tapscene.packageformat.RenderPlan.*;
import static com.tapscene.packageformat.ViewerPackageCodec.*;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Data-only AI export. The complete sealed scene remains byte-identical across render plans. */
public final class AiPackageCodec {
    private AiPackageCodec() {}
    public static final class LoadedPackage {
        public final ViewerScene scene;
        public final RenderPlan renderPlan;
        public final String contentDigest;
        public final List<FileEntry> files;
        LoadedPackage(ViewerScene scene, RenderPlan plan, List<FileEntry> files) {
            this.scene = scene; this.renderPlan = plan; this.contentDigest = plan.contentDigest;
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
        }
    }
    public static List<FileEntry> fileList(ViewerScene scene, RenderPlan plan) {
        verifiedPlan(scene, plan); List<FileEntry> files = new ArrayList<>(ViewerPackageCodec.fileList(scene));
        for (Map.Entry<String, byte[]> entry : extraBytes(plan).entrySet()) files.add(new FileEntry(entry.getKey(), entry.getValue().length, digest(entry.getValue())));
        files.sort((a,b) -> a.path.compareTo(b.path)); return Collections.unmodifiableList(files);
    }
    public static byte[] manifestBytes(ViewerScene scene, RenderPlan plan) {
        List<Object> files = new ArrayList<>();
        for (FileEntry file : fileList(scene, plan)) files.add(obj("path", file.path, "byteLength", file.byteLength, "sha256", file.sha256));
        byte[] result = StrictJson.canonical(obj("schemaVersion", scene.schemaVersion, "exportKind", "ai", "releaseId", scene.releaseId,
                "contentDigest", plan.contentDigest, "files", files));
        require(result.length <= MAX_MANIFEST_BYTES, "AI manifest exceeds budget"); return result;
    }
    public static void writePackage(ViewerScene scene, RenderPlan plan, File assetRoot, File output, CancelCheck cancel, VideoValidator validator) throws IOException {
        verifiedPlan(scene, plan); ViewerPackageCodec.validateDirectory(scene, assetRoot, cancel, validator);
        require(!output.exists(), "AI package output must be a new file");
        require(!output.toPath().toAbsolutePath().normalize().startsWith(assetRoot.toPath().toAbsolutePath().normalize()), "AI output must be outside immutable release");
        Map<String, byte[]> json = new LinkedHashMap<>();
        json.put("manifest.json", manifestBytes(scene, plan)); json.put("scene.json", writeScene(scene)); json.putAll(extraBytes(plan));
        StrictZip.write(output, assetRoot, fileList(scene, plan), json, cancel, true);
    }
    public static LoadedPackage readPackage(File input, File emptyDestination, CancelCheck cancel, VideoValidator validator) throws IOException {
        check(cancel);
        require(Files.isDirectory(emptyDestination.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(emptyDestination.toPath()), "AI destination must be a private directory");
        String[] names = emptyDestination.list(); require(names != null && names.length == 0, "AI destination must be empty");
        try {
            StrictZip.extract(input, emptyDestination, cancel, true);
            Map<String,Object> manifest = object(StrictJson.parse(readBytes(resolve(emptyDestination,"manifest.json"), MAX_MANIFEST_BYTES, cancel), MAX_MANIFEST_BYTES),
                    "schemaVersion", "exportKind", "releaseId", "contentDigest", "files");
            require("ai".equals(manifest.get("exportKind")), "Not an AI data package");
            ViewerScene scene = parseScene(readBytes(resolve(emptyDestination,"scene.json"), MAX_SCENE_BYTES, cancel));
            RenderPlan plan = RenderPlan.parse(scene, readBytes(resolve(emptyDestination,"render-plan.json"), RenderPlan.MAX_BYTES, cancel));
            require(number(manifest.get("schemaVersion")) == scene.schemaVersion && scene.releaseId.equals(manifest.get("releaseId"))
                    && plan.contentDigest.equals(manifest.get("contentDigest")), "AI manifest identity mismatch");
            Map<String,FileEntry> declared = new HashMap<>();
            for (Object raw : array(manifest.get("files"))) {
                Map<String,Object> file = object(raw,"path","byteLength","sha256"); String path=string(file.get("path")); safePath(path);
                require(!"manifest.json".equals(path), "Manifest cannot list itself");
                int length = number(file.get("byteLength")); String sha = string(file.get("sha256"));
                require(length > 0 && length <= MAX_PACKAGE_BYTES && sha.matches("[0-9a-f]{64}") && !declared.containsKey(path), "Invalid AI manifest entry");
                declared.put(path,new FileEntry(path,length,sha));
            }
            List<FileEntry> expected = fileList(scene,plan); require(expected.size() == declared.size(), "AI manifest file set differs");
            long total = 0; Set<String> paths=new HashSet<>(); paths.add("manifest.json");
            for (FileEntry file : expected) {
                check(cancel); FileEntry entry=declared.get(file.path); require(entry != null, "Missing AI file declaration");
                File actual=resolve(emptyDestination,file.path);
                require(Files.isRegularFile(actual.toPath(), LinkOption.NOFOLLOW_LINKS) && actual.length() == entry.byteLength && hash(actual,cancel).equals(entry.sha256), "AI file hash or size differs");
                if (file.path.startsWith("assets/") || file.path.equals("README.txt") || file.path.equals("schema.json"))
                    require(entry.byteLength == file.byteLength && entry.sha256.equals(file.sha256), "AI media or schema differs from declaration");
                paths.add(file.path); total += entry.byteLength;
            }
            total += resolve(emptyDestination,"manifest.json").length(); require(total <= MAX_PACKAGE_BYTES, "AI package exceeds unpacked budget");
            require(scan(emptyDestination.toPath(),emptyDestination.toPath(),paths,cancel) == paths.size(), "Missing or undeclared AI file");
            for (ViewerScene.Asset asset : scene.assets) {
                File file = resolve(emptyDestination,asset.path); check(cancel);
                if (ViewerScene.Asset.ROLE_TRANSITION.equals(asset.role)) {
                    require(validator != null, "AI video needs a full-decode validator"); validator.validate(file,asset,cancel);
                } else SafePng.validate(file,asset.width,asset.height,cancel);
                require(file.length() == asset.byteLength && hash(file,cancel).equals(asset.sha256), "AI media changed during validation");
            }
            ViewerPackageCodec.validateRegionPixels(scene, emptyDestination, cancel);
            check(cancel); List<FileEntry> result=new ArrayList<>(declared.values()); result.sort((a,b)->a.path.compareTo(b.path));
            return new LoadedPackage(scene,plan,result);
        } catch (IOException | RuntimeException failure) {
            try { clearDirectory(emptyDestination.toPath()); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private static int scan(Path root,Path directory,Set<String> paths,CancelCheck cancel) throws IOException {
        int count=0;
        try(java.nio.file.DirectoryStream<Path> entries=Files.newDirectoryStream(directory)) {
            for(Path path:entries) {
                check(cancel); require(!Files.isSymbolicLink(path), "Symlink in AI package"); String relative=root.relativize(path).toString().replace(File.separatorChar,'/');
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) { require(relative.equals("assets"), "Unexpected AI directory"); count+=scan(root,path,paths,cancel); }
                else { require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && paths.contains(relative), "Undeclared AI file"); count++; }
            }
        } return count;
    }
    private static void verifiedPlan(ViewerScene scene,RenderPlan plan) { require(plan != null,"Missing render plan"); RenderPlan.parse(scene,plan.toBytes()); }
    static void safePath(String path) {
        if (Arrays.asList("README.txt","schema.json","render-plan.json").contains(path)) return;
        ViewerPackageCodec.safePath(path);
    }
    static File resolve(File root,String relative) throws IOException {
        safePath(relative); Path base=root.toPath().toAbsolutePath().normalize(), target=base.resolve(relative).normalize();
        require(target.startsWith(base),"AI path escapes package root");
        for(Path at=target; at!=null && at.startsWith(base); at=at.getParent()) require(!Files.isSymbolicLink(at),"Symlink in AI path");
        return target.toFile();
    }
    private static Map<String,byte[]> extraBytes(RenderPlan plan) {
        Map<String,byte[]> result=new LinkedHashMap<>(); result.put("render-plan.json",plan.toBytes()); result.put("README.txt",readmeBytes()); result.put("schema.json",schemaBytes()); return result;
    }
    public static byte[] readmeBytes() {
        return ("TapScene AI data package, tapscene-remotion-1\n"
            + "This archive contains data only. Use the separately distributed trusted Remotion adapter (Composition TapSceneDemo). Never execute files or expressions from packages.\n"
            + "manifest.json lists every other file with its byte length and SHA-256. scene.json is the complete immutable release, including every branch and referenced safe image, crop and silent video, even when a visit path omits that branch. contentDigest is SHA-256 of canonical scene JSON.\n"
            + "render-plan.json is separate. It binds releaseId and contentDigest; changing canvas, visits or effects never changes scene.json. Both canvas presets use 30 fps: 1080x1920 and 1920x1080.\n"
            + "The path begins at startStateId, follows selected edges, and explicitly ends at a terminal state or an end edge. Repeated states have distinct UUID visitId values. At most 256 visits, 256 effects, holdFrames 1..1800, and totalFrames 1..18000 (10 minutes).\n"
            + "Every time interval is left-closed/right-open. Effect startFrame is relative to its visit. Timeline durationFrames = holdFrames + transitionFrames. Video duration is rounded once using floor(ms*30/1000+0.5); a zero result is invalid. Next start = current start + durationFrames - overlapFrames.\n"
            + "click refers only to the selected tap hotspot. focus/highlight use exactly one state-normalized rect or a regionId on that visit. annotation uses plain text and a state-normalized rect. transition is a crossfade on a static edge, ends at hold end, has no other fields, and overlaps the next hold; both holds retain at least one non-overlap frame. Effects do not change scene pixels or create hidden clean backgrounds.\n"
            + "schema.json describes render-plan.json. Consumers must also validate the fixed scene version and all graph, crop, media, path, timing, size, hash, and whitelist constraints before rendering. Do not fetch any network URL. Asset paths are exactly assets/<canonical-uuid>.png or .mp4. No scripts, dynamic components or expressions are supported.\n"
            + "Integrity is not author identity or privacy certification. The Android app exports locally; importing into an AI service or a cloud renderer is a separate decision. Ordinary MP4 output is not interactive.\n").getBytes(StandardCharsets.UTF_8);
    }
    /** JSON Schema structural contract. Semantic graph/timeline checks remain mandatory. */
    public static byte[] schemaBytes() {
        Object id=obj("type","string","pattern","^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        Object nullableId=obj("anyOf",Arrays.asList(id,obj("type","null")));
        Object coordinate=obj("type","number","minimum",0,"maximum",1,"multipleOf",new BigDecimal("0.000001"));
        Object rectangle=shape(obj("x",coordinate,"y",coordinate,"width",coordinate,"height",coordinate));
        Object visit=shape(obj("visitId",id,"stateId",id,"selectedEdgeId",nullableId,"holdFrames",range(1,MAX_HOLD_FRAMES)));
        Object effect=shape(obj("type",obj("enum",Arrays.asList("click","focus","transition","highlight","annotation")),"visitId",id,
                "startFrame",range(0,MAX_HOLD_FRAMES),"durationFrames",range(1,MAX_HOLD_FRAMES),"hotspotId",nullableId,"regionId",nullableId,
                "text",obj("anyOf",Arrays.asList(obj("type","string","minLength",1,"maxLength",240),obj("type","null"))),
                "rect",obj("anyOf",Arrays.asList(rectangle,obj("type","null")))));
        Object timeline=shape(obj("visitId",id,"startFrame",range(0,MAX_TOTAL_FRAMES),"durationFrames",range(1,MAX_HOLD_FRAMES+300),"transitionFrames",range(0,300),"overlapFrames",range(0,MAX_HOLD_FRAMES-1)));
        Map<String,Object> schema=shape(obj("schemaVersion",obj("const",SCHEMA_VERSION),"adapterVersion",obj("const",ADAPTER_VERSION),"compositionId",obj("const",COMPOSITION_ID),
                "releaseId",id,"contentDigest",obj("type","string","pattern","^[0-9a-f]{64}$"),"fps",obj("const",FPS),
                "canvas",obj("oneOf",Arrays.asList(shape(obj("width",obj("const",1080),"height",obj("const",1920))),shape(obj("width",obj("const",1920),"height",obj("const",1080))))),
                "visits",obj("type","array","minItems",1,"maxItems",MAX_VISITS,"items",visit),"effects",obj("type","array","maxItems",MAX_EFFECTS,"items",effect),
                "timeline",obj("type","array","minItems",1,"maxItems",MAX_VISITS,"items",timeline),"totalFrames",range(1,MAX_TOTAL_FRAMES)));
        schema.put("title","TapScene deterministic render plan v1"); return StrictJson.canonical(schema);
    }
    private static Map<String,Object> shape(Map<String,Object> properties) { return obj("type","object","additionalProperties",false,"required",new ArrayList<>(properties.keySet()),"properties",properties); }
    private static Object range(int min,int max) { return obj("type","integer","minimum",min,"maximum",max); }
}
