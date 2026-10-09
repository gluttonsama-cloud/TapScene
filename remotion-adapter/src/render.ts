import fs from "node:fs/promises";
import { constants, createReadStream } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { bundle } from "@remotion/bundler";
import {
  ensureBrowser,
  selectComposition,
  renderMedia,
  makeCancelSignal,
} from "@remotion/renderer";
import { loadPackage } from "./package";
import { assert } from "./json";
import { sha256 } from "./contracts";
const run = promisify(execFile);

/** Publish only complete, synced files. Hard links atomically refuse existing destinations.
 * The complete report is installed first; the MP4 is the final publication marker.
 * A process interruption can leave private staging or a complete report, never a partial MP4.
 */
export async function publishOutput(
  source: string,
  output: string,
  report: Record<string, unknown>,
) {
  const outputPath = path.resolve(output), parent = path.dirname(outputPath);
  const temporary = await fs.mkdtemp(path.join(parent, ".tapscene-output-"));
  const media = path.join(temporary, "complete.mp4"),
    metadata = path.join(temporary, "complete.json");
  const published: { temporary: string; final: string }[] = [];
  const sync = async (file: string) => {
    const handle = await fs.open(file, "r");
    try { await handle.sync(); } finally { await handle.close(); }
  };
  try {
    await fs.copyFile(source, media, constants.COPYFILE_EXCL);
    await fs.writeFile(metadata, JSON.stringify(report, null, 2) + "\n", {
      flag: "wx", mode: 0o600,
    });
    await sync(media);
    await sync(metadata);
    await sync(temporary);
    for (const pair of [
      { temporary: metadata, final: outputPath + ".json" },
      { temporary: media, final: outputPath },
    ]) {
      await fs.link(pair.temporary, pair.final);
      published.push(pair);
      await sync(parent);
    }
  } catch (error) {
    // Roll back only links created by this call, never an existing or replaced output.
    for (const pair of published.reverse()) {
      const own = await fs.lstat(pair.temporary);
      const actual = await fs.lstat(pair.final).catch(() => null);
      if (actual?.dev === own.dev && actual.ino === own.ino)
        await fs.unlink(pair.final);
    }
    await sync(parent);
    throw error;
  } finally {
    await fs.rm(temporary, { recursive: true, force: true });
  }
}

export async function render(input: string, output: string) {
  assert(
    !(await fs.lstat(output).then(
      () => true,
      () => false,
    )) &&
      !(await fs.lstat(output + ".json").then(
        () => true,
        () => false,
      )),
    "Output already exists",
  );
  const loaded = await loadPackage(path.resolve(input));
  const { scene, plan, root } = loaded;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const publicDir = path.join(root, "public"),
    bundleDir = path.join(root, "bundle");
  const outputPath = path.resolve(output),
    partial = path.join(root, "render.mp4");
  try {
    // Only validated local assets enter the trusted bundle. Package code/schema never executes.
    await fs.mkdir(publicDir);
    await fs.rename(path.join(root, "assets"), path.join(publicDir, "assets"));
    await ensureBrowser();
    const serveUrl = await bundle({
      entryPoint: fileURLToPath(new URL("./index.tsx", import.meta.url)),
      publicDir,
      outDir: bundleDir,
      enableCaching: false,
      gitSource: null,
    });
    const inputProps = { scene, plan };
    const composition = await selectComposition({
      serveUrl,
      id: "TapSceneDemo",
      inputProps,
      timeoutInMilliseconds: 30000,
    });
    const { cancelSignal, cancel } = makeCancelSignal();
    timer = setTimeout(cancel, 30 * 60 * 1000);
    await renderMedia({
      serveUrl,
      composition,
      inputProps,
      codec: "h264",
      pixelFormat: "yuv420p",
      outputLocation: partial,
      overwrite: false,
      muted: true,
      enforceAudioTrack: false,
      concurrency: 2,
      timeoutInMilliseconds: 30000,
      cancelSignal,
      offthreadVideoCacheSizeInBytes: 64 * 1024 * 1024,
      mediaCacheSizeInBytes: 64 * 1024 * 1024,
      offthreadVideoThreads: 1,
      crf: 18,
      logLevel: "warn",
    });
    const probe = await run(
      "ffprobe",
      [
        "-v",
        "error",
        "-count_frames",
        "-show_streams",
        "-show_format",
        "-of",
        "json",
        partial,
      ],
      { timeout: 120000, maxBuffer: 1024 * 1024 },
    );
    assert(!probe.stderr.trim(), "Output probe errors");
    const inspected = JSON.parse(probe.stdout);
    assert(inspected.streams.length === 1, "Output includes unexpected tracks");
    const v = inspected.streams[0];
    assert(
      v.codec_name === "h264" &&
        v.pix_fmt === "yuv420p" &&
        v.width === plan.canvas.width &&
        v.height === plan.canvas.height &&
        v.r_frame_rate === "30/1" &&
        Number(v.nb_read_frames) === plan.totalFrames,
      "Output frame/dimension/codec mismatch",
    );
    const decoded = await run(
      "ffmpeg",
      [
        "-v",
        "error",
        "-xerror",
        "-err_detect",
        "explode",
        "-threads",
        "1",
        "-i",
        partial,
        "-map",
        "0:v:0",
        "-f",
        "null",
        "-",
      ],
      { timeout: 180000, maxBuffer: 1024 * 1024 },
    );
    assert(!decoded.stderr.trim(), "Output decode errors");
    const size = (await fs.stat(partial)).size;
    assert(size > 0 && size <= 512 * 1024 * 1024, "Rendered output exceeds 512 MiB");
    const digest = createHash("sha256");
    for await (const block of createReadStream(partial)) digest.update(block);
    const report = {
      adapterVersion: plan.adapterVersion,
      releaseId: scene.releaseId,
      contentDigest: plan.contentDigest,
      renderPlanSha256: sha256(
        await fs.readFile(path.join(root, "render-plan.json")),
      ),
      outputFile: path.basename(outputPath),
      byteLength: size,
      sha256: digest.digest("hex"),
      width: v.width,
      height: v.height,
      fps: 30,
      totalFrames: plan.totalFrames,
      audioTracks: 0,
      fullyDecoded: true,
    };
    await publishOutput(partial, outputPath, report);
    return report;
  } finally {
    if (timer) clearTimeout(timer);
    await loaded.cleanup();
  }
}
if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const [input, output, ...rest] = process.argv.slice(2);
  if (!input || !output || rest.length) {
    console.error(
      "Usage: npm run render -- package.tapscene-ai new-output.mp4",
    );
    process.exitCode = 2;
  } else
    render(input, output)
      .then((result) => console.log(JSON.stringify(result, null, 2)))
      .catch((error) => {
        console.error(error instanceof Error ? error.message : String(error));
        process.exit(1);
      });
}
