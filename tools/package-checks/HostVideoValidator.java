import com.tapscene.packageformat.ViewerPackageCodec;
import com.tapscene.packageformat.ViewerScene;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Desktop-only validator: installed ffprobe inspects tracks; ffmpeg decodes through EOS. */
public final class HostVideoValidator implements ViewerPackageCodec.VideoValidator {
    // Match Android/Media3: absent or unrecognized transfer metadata uses SDR defaults;
    // explicit PQ/HLG is never reinterpreted as SDR. This does not classify source imports.
    private static final Set<String> HDR_TRANSFERS = Set.of("smpte2084", "arib-std-b67");
    private static final Set<String> AVC_PROFILES = Set.of("Baseline", "Constrained Baseline", "Main", "Extended", "High", "Constrained High");
    private static final Set<String> BRANDS = Set.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1");
    private int decoded;

    public int decodedFiles() { return decoded; }

    @Override public void validate(File file, ViewerScene.Asset declared, ViewerPackageCodec.CancelCheck cancel) throws IOException {
        cancel.check();
        String trackOutput = command(List.of("ffprobe", "-v", "error", "-protocol_whitelist", "file", "-enable_drefs", "0", "-use_absolute_path", "0", "-show_entries",
                "format=format_name,duration:format_tags=major_brand:stream=index,codec_type,codec_name,codec_tag_string,profile,pix_fmt,width,height,duration,color_transfer,bits_per_raw_sample,sample_aspect_ratio:stream_tags=rotate:stream_side_data=rotation",
                "-of", "flat", file.getAbsolutePath()), cancel);
        Map<String, String> fields = flat(trackOutput);
        Set<String> streamIds = new java.util.HashSet<>();
        for (String key : fields.keySet()) if (key.startsWith("streams.stream.")) streamIds.add(key.split("\\.")[2]);
        require(streamIds.equals(Set.of("0")), "MP4 must have exactly one track, with no audio, subtitles or data");
        String prefix = "streams.stream.0.";
        require("video".equals(fields.get(prefix + "codec_type")) && "h264".equals(fields.get(prefix + "codec_name")), "Video is not H.264");
        require(Set.of("avc1", "avc3").contains(fields.getOrDefault(prefix + "codec_tag_string", "")), "Unsupported or encrypted video sample entry");
        require(AVC_PROFILES.contains(fields.get(prefix + "profile")), "Unsupported AVC profile");
        require(Set.of("N/A", "1:1").contains(fields.getOrDefault(prefix + "sample_aspect_ratio", "N/A")), "Non-square video pixels");
        require("yuv420p".equals(fields.get(prefix + "pix_fmt")), "Video must decode as 8-bit 4:2:0");
        require(!HDR_TRANSFERS.contains(fields.getOrDefault(prefix + "color_transfer", "unknown")), "Video transfer is not SDR");
        require("mov,mp4,m4a,3gp,3g2,mj2".equals(fields.get("format.format_name"))
                && BRANDS.contains(fields.getOrDefault("format.tags.major_brand", "").trim()), "Unsupported MP4 container");
        require(integer(fields.get(prefix + "width")) == declared.width && integer(fields.get(prefix + "height")) == declared.height, "Actual video dimensions disagree");
        for (Map.Entry<String, String> entry : fields.entrySet()) if (entry.getKey().endsWith(".rotation") || entry.getKey().endsWith(".rotate"))
            require(new BigDecimal(entry.getValue()).signum() == 0, "Rotated video must first be baked to the declared canvas");
        checkDuration(fields.get(prefix + "duration"), declared);
        checkDuration(fields.get("format.duration"), declared);

        // ffprobe's decoded frames also detect dynamic dimension/format/timeline changes.
        // ffmpeg below independently decodes with fatal error handling, rather than treating
        // successful metadata probing or the first decoded frame as a complete validation.
        String frameOutput = command(List.of("ffprobe", "-v", "error", "-protocol_whitelist", "file", "-enable_drefs", "0", "-use_absolute_path", "0", "-show_frames", "-show_entries",
                "frame=media_type,best_effort_timestamp_time,duration_time,pkt_duration_time,width,height,pix_fmt,color_transfer",
                "-of", "compact=p=0:nk=0", file.getAbsolutePath()), cancel);
        int frames = 0;
        BigDecimal previous = null, end = BigDecimal.ZERO;
        for (String line : frameOutput.split("\\R")) {
            if (line.isBlank()) continue;
            Map<String, String> frame = compact(line);
            if (!frame.containsKey("media_type")) continue; // e.g. an empty side-data record
            require("video".equals(frame.get("media_type")), "Unexpected decoded track");
            require(integer(frame.get("width")) == declared.width && integer(frame.get("height")) == declared.height
                    && "yuv420p".equals(frame.get("pix_fmt")), "Video changes dimensions or pixel format");
            require(!HDR_TRANSFERS.contains(frame.getOrDefault("color_transfer", "unknown")), "Decoded frame is not SDR");
            BigDecimal pts = number(frame.get("best_effort_timestamp_time"));
            BigDecimal duration = number(frame.getOrDefault("duration_time", frame.get("pkt_duration_time")));
            require(pts.signum() >= 0 && duration.signum() > 0 && (previous == null ? pts.signum() == 0 : pts.compareTo(previous) > 0), "Invalid decoded frame timeline");
            previous = pts; end = end.max(pts.add(duration)); frames++;
        }
        require(frames > 0, "No fully decoded frames"); checkDuration(end.toPlainString(), declared);
        String decodedOutput = command(List.of("ffmpeg", "-v", "error", "-xerror", "-err_detect", "explode", "-protocol_whitelist", "file",
                "-enable_drefs", "0", "-use_absolute_path", "0", "-threads", "1", "-i", file.getAbsolutePath(), "-map", "0:v:0", "-fps_mode", "passthrough", "-progress", "pipe:1",
                "-nostats", "-f", "null", "-"), cancel);
        Map<String, String> progress = flat(decodedOutput);
        require("end".equals(progress.get("progress")) && integer(progress.get("frame")) == frames, "Full decode did not reach EOS with every frame");
        decoded++; cancel.check();
    }
    private static void checkDuration(String seconds, ViewerScene.Asset declared) throws IOException {
        BigDecimal duration = number(seconds);
        require(duration.signum() > 0 && duration.compareTo(BigDecimal.TEN) <= 0 && declared.durationMs != null
                && duration.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.CEILING).longValueExact() == declared.durationMs,
                "Actual video duration exceeds limit or disagrees with declaration");
    }
    private static int integer(String value) throws IOException {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { throw new IOException("Missing or invalid numeric video field", invalid); }
    }
    private static BigDecimal number(String value) throws IOException {
        try { return new BigDecimal(value == null ? "" : value); }
        catch (NumberFormatException invalid) { throw new IOException("Missing or invalid media time", invalid); }
    }
    private static Map<String, String> compact(String line) {
        Map<String, String> fields = new HashMap<>();
        for (String entry : line.split("\\|")) { int at = entry.indexOf('='); if (at > 0) fields.put(entry.substring(0, at), entry.substring(at + 1)); }
        return fields;
    }
    private static Map<String, String> flat(String output) throws IOException {
        Map<String, String> fields = new HashMap<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) continue;
            int at = line.indexOf('='); require(at > 0, "Media tool reported a decode/probe error");
            String value = line.substring(at + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            fields.put(line.substring(0, at), value);
        }
        return fields;
    }
    static String command(List<String> args, ViewerPackageCodec.CancelCheck cancel) throws IOException {
        Process process = new ProcessBuilder(new ArrayList<>(args)).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (InputStream output = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            while (process.isAlive() || output.available() > 0) {
                cancel.check();
                require(System.nanoTime() < deadline, "Media validation exceeded execution budget");
                int available = output.available();
                if (available > 0) {
                    int n = output.read(buffer, 0, Math.min(available, buffer.length));
                    if (n < 0) break;
                    bytes.write(buffer, 0, n); require(bytes.size() <= 1024 * 1024, "Media output exceeds bounded inspection budget");
                } else try { Thread.sleep(5); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Media validation interrupted", interrupted); }
            }
            String result = bytes.toString(StandardCharsets.UTF_8);
            require(process.exitValue() == 0, "Media command failed: " + result.substring(0, Math.min(600, result.length())));
            return result;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
}
