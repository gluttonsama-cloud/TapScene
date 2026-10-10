/** Server-only media trust boundary, adapted from the independent Remotion reader. */
import fs from "node:fs/promises";
import { createHash } from "node:crypto";
import { promisify } from "node:util";
import { execFile } from "node:child_process";
import { inflateSync } from "node:zlib";
import { PNG } from "pngjs";
import type { Asset, Scene } from "@tapscene/runtime-ts";
const run = promisify(execFile);
function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}
export function crc32(data: Uint8Array): number {
  let crc = 0xffffffff;
  for (const v of data) {
    crc ^= v;
    for (let j = 0; j < 8; j++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
export function decodePng(
  bytes: Buffer,
  asset: Pick<Asset, "width" | "height">,
): PNG {
  assert(
    Number.isSafeInteger(asset.width) &&
      Number.isSafeInteger(asset.height) &&
      Math.min(asset.width, asset.height) > 0 &&
      Math.min(asset.width, asset.height) <= 1080 &&
      Math.max(asset.width, asset.height) <= 2400 &&
      bytes.length <= 50 * 1024 * 1024,
    "PNG dimensions or byte limit",
  );
  assert(
    bytes.length >= 57 &&
      bytes
        .subarray(0, 8)
        .equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])),
    "Invalid PNG signature",
  );
  let offset = 8,
    chunks = 0,
    color = 0,
    seenPixels = false,
    closedPixels = false,
    ended = false;
  const meta = new Set<string>(),
    data: Buffer[] = [];
  while (offset < bytes.length) {
    assert(
      ++chunks <= 4096 && offset + 12 <= bytes.length,
      "PNG truncated or chunk limit",
    );
    const size = bytes.readUInt32BE(offset),
      type = bytes.toString("ascii", offset + 4, offset + 8);
    assert(
      offset + size + 12 <= bytes.length &&
        ["IHDR", "IDAT", "IEND", "sRGB", "gAMA", "cHRM", "sBIT"].includes(type),
      "Unsupported PNG metadata or bounds",
    );
    assert(
      crc32(bytes.subarray(offset + 4, offset + 8 + size)) ===
        bytes.readUInt32BE(offset + 8 + size),
      "PNG CRC mismatch",
    );
    assert(chunks !== 1 || type === "IHDR", "PNG needs header");
    if (seenPixels && type !== "IDAT") closedPixels = true;
    if (type === "IHDR") {
      assert(chunks === 1 && size === 13, "Duplicate PNG header");
      color = bytes[offset + 17];
      assert(
        bytes.readUInt32BE(offset + 8) === asset.width &&
          bytes.readUInt32BE(offset + 12) === asset.height &&
          bytes[offset + 16] === 8 &&
          [2, 6].includes(color) &&
          bytes[offset + 18] === 0 &&
          bytes[offset + 19] === 0 &&
          bytes[offset + 20] === 0,
        "PNG format or dimensions mismatch",
      );
    } else if (type === "IDAT") {
      assert(!closedPixels, "Noncontiguous PNG pixels");
      seenPixels = true;
      data.push(bytes.subarray(offset + 8, offset + 8 + size));
    } else if (type === "IEND") {
      assert(seenPixels && size === 0, "Invalid PNG end");
      ended = true;
      offset += 12;
      break;
    } else {
      assert(!seenPixels && !meta.has(type), "Duplicate or late PNG metadata");
      meta.add(type);
      assert(
        size ===
          (type === "sRGB"
            ? 1
            : type === "gAMA"
              ? 4
              : type === "cHRM"
                ? 32
                : color === 6
                  ? 4
                  : 3),
        "PNG metadata size",
      );
      if (type === "sRGB") assert(bytes[offset + 8] <= 3, "Invalid sRGB");
      if (type === "gAMA")
        assert(bytes.readUInt32BE(offset + 8) > 0, "Invalid gamma");
      if (type === "sBIT")
        for (const b of bytes.subarray(offset + 8, offset + 8 + size))
          assert(b >= 1 && b <= 8, "Invalid sBIT");
    }
    offset += size + 12;
  }
  assert(ended && offset === bytes.length, "PNG trailing data or no end");
  const row = 1 + asset.width * (color === 6 ? 4 : 3),
    expected = row * asset.height;
  const inflated = inflateSync(Buffer.concat(data), {
    maxOutputLength: expected,
    info: true,
  }) as unknown as { buffer: Buffer; engine: { bytesWritten: number } };
  assert(
    inflated.buffer.length === expected &&
      inflated.engine.bytesWritten === Buffer.concat(data).length,
    "PNG inflated length or trailing compressed data",
  );
  for (let y = 0; y < asset.height; y++)
    assert(inflated.buffer[y * row] <= 4, "Invalid PNG filter");
  const decoded = PNG.sync.read(bytes, { checkCRC: true });
  for (let i = 3; i < decoded.data.length; i += 4)
    assert(decoded.data[i] === 255, "PNG must be opaque");
  return decoded;
}
async function command(tool: string, args: string[]) {
  const result = await run(tool, args, {
    timeout: 30000,
    killSignal: "SIGKILL",
    maxBuffer: 12 * 1024 * 1024,
    encoding: "utf8",
  });
  assert(!result.stderr.trim(), `${tool} reported media errors`);
  return result.stdout;
}
const inputOptions = [
  "-threads",
  "1",
  "-max_alloc",
  "67108864",
  "-max_pixels",
  "2592000",
  "-v",
  "error",
  "-protocol_whitelist",
  "file",
  "-enable_drefs",
  "0",
  "-use_absolute_path",
  "0",
];
export async function validateVideo(file: string, a: Asset) {
  const raw = await command("ffprobe", [
    ...inputOptions,
    "-show_streams",
    "-show_format",
    "-of",
    "json",
    file,
  ]);
  const m = JSON.parse(raw);
  assert(m.streams.length === 1, "Video must have exactly one track");
  const s = m.streams[0];
  assert(
    s.codec_type === "video" &&
      s.codec_name === "h264" &&
      ["avc1", "avc3"].includes(s.codec_tag_string) &&
      [
        "Baseline",
        "Constrained Baseline",
        "Main",
        "Extended",
        "High",
        "Constrained High",
      ].includes(s.profile) &&
      s.pix_fmt === "yuv420p" &&
      !["smpte2084", "arib-std-b67"].includes(s.color_transfer),
    "Unsupported AVC or HDR video",
  );
  assert(
    s.width === a.width &&
      s.height === a.height &&
      (!s.sample_aspect_ratio ||
        s.sample_aspect_ratio === "1:1" ||
        s.sample_aspect_ratio === "N/A"),
    "Video dimensions mismatch",
  );
  assert(
    m.format.format_name === "mov,mp4,m4a,3gp,3g2,mj2" &&
      [
        "isom",
        "iso2",
        "iso3",
        "iso4",
        "iso5",
        "iso6",
        "mp41",
        "mp42",
        "avc1",
      ].includes(m.format.tags?.major_brand?.trim()),
    "Unsupported container",
  );
  assert(
    !s.tags?.rotate || Number(s.tags.rotate) === 0,
    "Video rotation not baked",
  );
  for (const side of s.side_data_list ?? [])
    assert(
      !side.rotation || Number(side.rotation) === 0,
      "Video rotation not baked",
    );
  const checkDuration = (n: unknown) =>
    assert(
      typeof n === "string" &&
        Number.isFinite(Number(n)) &&
        Number(n) > 0 &&
        Number(n) <= 10 &&
        Math.ceil(Number(n) * 1000 - 1e-7) === a.durationMs,
      "Video duration mismatch",
    );
  checkDuration(s.duration);
  checkDuration(m.format.duration);
  await validateEncodedSps(file, a);
  const frames = JSON.parse(
    await command("ffprobe", [
      ...inputOptions,
      "-show_frames",
      "-show_entries",
      "frame=media_type,best_effort_timestamp_time,pkt_duration_time,duration_time,width,height,pix_fmt,color_transfer,sample_aspect_ratio",
      "-of",
      "json",
      file,
    ]),
  ).frames;
  assert(frames.length > 0 && frames.length <= 2400, "Video frame limit");
  let previous = -1,
    end = 0;
  for (const f of frames) {
    assert(
      f.media_type === "video" &&
        f.width === a.width &&
        f.height === a.height &&
        f.pix_fmt === "yuv420p" &&
        (!f.sample_aspect_ratio ||
          ["1:1", "N/A"].includes(f.sample_aspect_ratio)) &&
        !["smpte2084", "arib-std-b67"].includes(f.color_transfer),
      "Video frame changed",
    );
    const pts = Number(f.best_effort_timestamp_time),
      duration = Number(f.duration_time ?? f.pkt_duration_time);
    assert(
      Number.isFinite(pts) &&
        Number.isFinite(duration) &&
        duration > 0 &&
        (previous < 0 ? pts === 0 : pts > previous),
      "Invalid frame timeline",
    );
    previous = pts;
    end = Math.max(end, pts + duration);
  }
  checkDuration(String(end));
  const output = await command("ffmpeg", [
    "-nostdin",
    "-max_alloc",
    "67108864",
    "-max_pixels",
    "2592000",
    "-v",
    "error",
    "-xerror",
    "-err_detect",
    "explode",
    "-protocol_whitelist",
    "file",
    "-enable_drefs",
    "0",
    "-use_absolute_path",
    "0",
    "-threads",
    "1",
    "-i",
    file,
    "-map",
    "0:v:0",
    "-fps_mode",
    "passthrough",
    "-progress",
    "pipe:1",
    "-nostats",
    "-f",
    "null",
    "-",
  ]);
  const values = Object.fromEntries(
    output
      .trim()
      .split("\n")
      .map((s) => s.split("=")),
  );
  assert(
    values.progress === "end" && Number(values.frame) === frames.length,
    "Video did not fully decode",
  );
}

/** Size/hash and full decoding are mandatory before a staged asset becomes publishable. */
export async function validateMedia(
  filePath: string,
  asset: Asset,
): Promise<void> {
  const stat = await fs.lstat(filePath);
  assert(
    stat.isFile() &&
      !stat.isSymbolicLink() &&
      stat.size === asset.byteLength &&
      stat.size <= 50 * 1024 * 1024,
    "Invalid media file or byte length",
  );
  assert(
    Number.isSafeInteger(asset.width) &&
      Number.isSafeInteger(asset.height) &&
      Math.min(asset.width, asset.height) > 0 &&
      Math.min(asset.width, asset.height) <= 1080 &&
      Math.max(asset.width, asset.height) <= 2400,
    "Media dimension limit",
  );
  const bytes = await fs.readFile(filePath);
  assert(
    bytes.length === asset.byteLength &&
      createHash("sha256").update(bytes).digest("hex") === asset.sha256,
    "Media digest mismatch",
  );
  if (asset.role === "transition") {
    assert(
      asset.mime === "video/mp4" &&
        asset.durationMs !== null &&
        asset.durationMs > 0 &&
        asset.durationMs <= 10000,
      "Invalid video declaration",
    );
    await validateVideo(filePath, asset);
  } else {
    assert(
      (asset.role === "state-image" || asset.role === "region-crop") &&
        asset.mime === "image/png" &&
        asset.durationMs === null,
      "Invalid PNG declaration",
    );
    decodePng(bytes, asset);
  }
}

/** Verify crops are literal safe-base pixels after every asset has passed validateMedia. */
export async function validateRegionCrops(
  scene: Scene,
  resolveAsset: (asset: Asset) => string,
): Promise<void> {
  for (const region of scene.regions) {
    const base = scene.assets.find((a) => a.id === region.baseAssetId);
    const crop = scene.assets.find((a) => a.id === region.assetId);
    assert(base && crop, "Missing region media");
    const b = decodePng(await fs.readFile(resolveAsset(base)), base);
    const c = decodePng(await fs.readFile(resolveAsset(crop)), crop);
    for (let y = 0; y < crop.height; y++) {
      const at = ((region.bbox.y + y) * base.width + region.bbox.x) * 4;
      assert(
        b.data
          .subarray(at, at + crop.width * 4)
          .equals(
            c.data.subarray(y * crop.width * 4, (y + 1) * crop.width * 4),
          ),
        "Crop pixels disagree with safe base",
      );
    }
  }
}

/** Inspect each encoded SPS, including in-band configuration changes; decoded output
 * alone could conceal an unsupported encoded profile/chroma/bit-depth conversion. */
async function validateEncodedSps(file: string, asset: Asset): Promise<void> {
  const result = await run(
    "ffmpeg",
    [
      "-nostdin",
      ...inputOptions,
      "-i",
      file,
      "-map",
      "0:v:0",
      "-c:v",
      "copy",
      "-bsf:v",
      "h264_mp4toannexb",
      "-f",
      "h264",
      "pipe:1",
    ],
    {
      encoding: "buffer",
      timeout: 30000,
      killSignal: "SIGKILL",
      maxBuffer: 64 * 1024 * 1024,
    },
  );
  assert(result.stderr.length === 0, "Encoded AVC inspection failed");
  const bytes = result.stdout;
  const starts: { start: number; header: number }[] = [];
  for (let i = 0; i + 3 < bytes.length; i++) {
    if (bytes[i] === 0 && bytes[i + 1] === 0) {
      const prefix =
        bytes[i + 2] === 1
          ? 3
          : bytes[i + 2] === 0 && bytes[i + 3] === 1
            ? 4
            : 0;
      if (prefix) {
        starts.push({ start: i, header: i + prefix });
        i += prefix - 1;
      }
    }
  }
  assert(
    starts.length > 0 && starts.length <= 100000 && starts[0].start === 0,
    "Invalid encoded AVC units",
  );
  let count = 0;
  starts.forEach((unit, index) => {
    const end = starts[index + 1]?.start ?? bytes.length;
    assert(
      unit.header < end && !(bytes[unit.header] & 0x80),
      "Truncated/invalid AVC NAL",
    );
    if ((bytes[unit.header] & 31) !== 7) return;
    inspectSps(bytes.subarray(unit.header + 1, end), asset);
    count++;
  });
  assert(count > 0, "Missing AVC SPS");
}

function inspectSps(escaped: Buffer, asset: Asset): void {
  assert(
    escaped.length >= 4 && escaped.length <= 65536,
    "Invalid AVC SPS size",
  );
  const rbsp: number[] = [];
  for (let i = 0; i < escaped.length; i++) {
    if (
      i >= 2 &&
      escaped[i] === 3 &&
      escaped[i - 1] === 0 &&
      escaped[i - 2] === 0
    ) {
      assert(
        i + 1 < escaped.length && escaped[i + 1] <= 3,
        "Invalid AVC emulation prevention",
      );
      continue;
    }
    rbsp.push(escaped[i]);
  }
  let offset = 0;
  function bits(n: number): number {
    assert(offset + n <= rbsp.length * 8, "Truncated AVC SPS");
    let v = 0;
    for (let i = 0; i < n; i++, offset++)
      v = v * 2 + ((rbsp[offset >>> 3] >>> (7 - (offset & 7))) & 1);
    return v;
  }
  function ue(): number {
    let zeros = 0;
    while (!bits(1)) {
      assert(++zeros <= 24, "AVC SPS integer budget");
    }
    return 2 ** zeros - 1 + bits(zeros);
  }
  function se(): number {
    const n = ue();
    return n % 2 ? (n + 1) / 2 : -n / 2;
  }
  const profile = bits(8);
  assert(
    [66, 77, 88, 100].includes(profile),
    "Unsupported encoded AVC profile",
  );
  bits(16); // constraint flags and level_idc
  assert(ue() <= 31, "Invalid AVC SPS identity");
  if (profile === 100) {
    assert(
      ue() === 1 && ue() === 0 && ue() === 0,
      "Encoded AVC must be 8-bit 4:2:0",
    );
    bits(1);
    if (bits(1))
      for (let list = 0; list < 8; list++)
        if (bits(1)) {
          let last = 8,
            next = 8;
          for (let i = 0; i < (list < 6 ? 16 : 64); i++) {
            if (next !== 0) {
              const delta = se();
              assert(
                delta >= -128 && delta <= 127,
                "Invalid AVC scaling delta",
              );
              next = (last + delta + 256) % 256;
            }
            last = next === 0 ? last : next;
          }
        }
  }
  assert(ue() <= 12, "Invalid AVC frame number");
  const order = ue();
  assert(order <= 2, "Invalid AVC picture order");
  if (order === 0) assert(ue() <= 12, "Invalid AVC picture order precision");
  else if (order === 1) {
    bits(1);
    se();
    se();
    const cycle = ue();
    assert(cycle <= 255, "AVC SPS cycle limit");
    for (let i = 0; i < cycle; i++) se();
  }
  ue();
  bits(1);
  const widthMbs = ue() + 1,
    heightUnits = ue() + 1,
    frameOnly = bits(1);
  if (!frameOnly) bits(1);
  bits(1);
  let left = 0,
    right = 0,
    top = 0,
    bottom = 0;
  if (bits(1)) {
    left = ue();
    right = ue();
    top = ue();
    bottom = ue();
  }
  assert(
    widthMbs * 16 - 2 * (left + right) === asset.width &&
      (2 - frameOnly) * heightUnits * 16 -
        2 * (2 - frameOnly) * (top + bottom) ===
        asset.height,
    "Encoded AVC dimensions changed",
  );
  if (bits(1)) {
    if (bits(1)) {
      const aspect = bits(8);
      if (aspect === 255) {
        const w = bits(16),
          h = bits(16);
        assert(w > 0 && w === h, "Encoded AVC pixels must be square");
      } else
        assert(
          aspect === 0 || aspect === 1,
          "Encoded AVC pixels must be square",
        );
    }
    if (bits(1)) bits(1);
    if (bits(1)) {
      bits(4);
      if (bits(1)) {
        bits(8);
        const transfer = bits(8);
        bits(8);
        assert(
          transfer !== 16 && transfer !== 18,
          "Encoded AVC transfer is HDR",
        );
      }
    }
  }
}
