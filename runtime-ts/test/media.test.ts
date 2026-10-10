import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { createHash } from "node:crypto";
import { PNG } from "pngjs";
import {
  decodePng,
  validateMedia,
  validateRegionCrops,
} from "../../server/src/media.js";
import type { Asset, Scene } from "../src/index.js";
import { fixture, id } from "./fixtures.js";
const run = promisify(execFile);
const hash = (b: Buffer) => createHash("sha256").update(b).digest("hex");
function png(width = 32, height = 48, red = 40, alpha = 255): Buffer {
  const p = new PNG({ width, height });
  for (let i = 0; i < p.data.length; i += 4) {
    p.data[i] = red;
    p.data[i + 1] = 80;
    p.data[i + 2] = 120;
    p.data[i + 3] = alpha;
  }
  return PNG.sync.write(p, { colorType: 6 });
}
function asset(bytes: Buffer, video = false): Asset {
  return {
    id: id(100),
    path: `assets/${id(100)}.${video ? "mp4" : "png"}`,
    role: video ? "transition" : "state-image",
    mime: video ? "video/mp4" : "image/png",
    byteLength: bytes.length,
    sha256: hash(bytes),
    width: 32,
    height: 48,
    durationMs: video ? 1000 : null,
  };
}
async function withRoot(fn: (root: string) => Promise<void>): Promise<void> {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "tapscene-media-test-"));
  try {
    await fn(root);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
}
test("full PNG validation verifies size/hash, dimensions, opacity, CRC and trailing data", async () => {
  await withRoot(async (root) => {
    const bytes = png(),
      a = asset(bytes),
      file = path.join(root, "image");
    await fs.writeFile(file, bytes);
    await validateMedia(file, a);
    await assert.rejects(
      validateMedia(file, { ...a, sha256: "0".repeat(64) }),
      /digest/,
    );
    await assert.rejects(
      validateMedia(file, { ...a, width: 33 }),
      /dimensions/,
    );
    await assert.rejects(
      validateMedia(file, { ...a, byteLength: bytes.length + 1 }),
      /byte length/,
    );
    assert.throws(() => decodePng(png(32, 48, 40, 254), a), /opaque/);
    assert.throws(
      () => decodePng(Buffer.concat([bytes, Buffer.from([0])]), a),
      /trailing/,
    );
    const corrupt = Buffer.from(bytes);
    corrupt[corrupt.length - 1] ^= 1;
    assert.throws(() => decodePng(corrupt, a), /CRC/);
    await fs.symlink(file, path.join(root, "link"));
    await assert.rejects(
      validateMedia(path.join(root, "link"), a),
      /Invalid media file/,
    );
  });
});
test("region crop equality uses server-resolved private file paths", async () => {
  await withRoot(async (root) => {
    const baseBytes = png(),
      cropBytes = png(10, 12),
      base = asset(baseBytes);
    const crop: Asset = {
      ...asset(cropBytes),
      id: id(101),
      path: `assets/${id(101)}.png`,
      role: "region-crop",
      width: 10,
      height: 12,
    };
    const scene: Scene = {
      ...fixture(),
      assets: [base, crop],
      regions: [
        {
          id: id(200),
          stateId: id(1),
          baseAssetId: base.id,
          assetId: crop.id,
          name: "Crop",
          kind: "screenshotCrop",
          coordinateSpace: "source-pixels",
          sourceWidth: 32,
          sourceHeight: 48,
          bbox: { x: 2, y: 3, width: 10, height: 12 },
          group: null,
          zIndex: 0,
          anchor: { coordinateSpace: "layer-normalized", x: 0.5, y: 0.5 },
        },
      ],
    };
    const resolve = (a: Asset) => path.join(root, a.id);
    await fs.writeFile(resolve(base), baseBytes);
    await fs.writeFile(resolve(crop), cropBytes);
    await validateRegionCrops(scene, resolve);
    await fs.writeFile(resolve(crop), png(10, 12, 41));
    await assert.rejects(validateRegionCrops(scene, resolve), /Crop pixels/);
  });
});
async function video(file: string, additional: string[] = []): Promise<Buffer> {
  await run(
    "ffmpeg",
    [
      "-nostdin",
      "-v",
      "error",
      "-threads",
      "1",
      "-f",
      "lavfi",
      "-i",
      "color=c=black:s=32x48:r=10:d=1",
      ...additional,
      "-c:v",
      "libx264",
      "-threads",
      "1",
      "-pix_fmt",
      "yuv420p",
      "-profile:v",
      "baseline",
      "-movflags",
      "+faststart",
      "-y",
      file,
    ],
    { timeout: 30000 },
  );
  return fs.readFile(file);
}
test("real H.264 SDR MP4 decodes to EOS, missing colour tags use SDR defaults", async () => {
  await withRoot(async (root) => {
    const file = path.join(root, "video.mp4"),
      bytes = await video(file);
    await validateMedia(file, asset(bytes, true));
    await assert.rejects(
      validateMedia(file, { ...asset(bytes, true), durationMs: 999 }),
      /duration/,
    );
    await assert.rejects(
      validateMedia(file, { ...asset(bytes, true), height: 46 }),
      /dimensions/,
    );
  });
});
test("actual audio track and explicit HDR tags are rejected", async () => {
  await withRoot(async (root) => {
    const audioFile = path.join(root, "audio.mp4");
    const audio = await video(audioFile, [
      "-f",
      "lavfi",
      "-i",
      "sine=frequency=1000:duration=1",
      "-c:a",
      "aac",
      "-shortest",
    ]);
    await assert.rejects(
      validateMedia(audioFile, asset(audio, true)),
      /one track/,
    );
    const hdrFile = path.join(root, "hdr.mp4"),
      hdr = await video(hdrFile, [
        "-x264-params",
        "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc",
      ]);
    await assert.rejects(validateMedia(hdrFile, asset(hdr, true)), /HDR/);
  });
});
test("truncated actual MP4 cannot pass declaration/hash rebinding", async () => {
  await withRoot(async (root) => {
    const file = path.join(root, "truncated.mp4"),
      bytes = await video(file);
    const truncated = bytes.subarray(0, bytes.length - 30);
    await fs.writeFile(file, truncated);
    await assert.rejects(validateMedia(file, asset(truncated, true)));
  });
});
