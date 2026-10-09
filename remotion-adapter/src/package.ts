import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { promisify } from "node:util";
import { execFile } from "node:child_process";
import { inflateSync } from "node:zlib";
import yauzl from "yauzl";
import { PNG } from "pngjs";
import { assert, parseJson } from "./json";
import {
  manifestSchema,
  sha256,
  validateContracts,
  type Asset,
} from "./contracts";
const run = promisify(execFile),
  MAX_BYTES = 50 * 1024 * 1024,
  MAX_FILES = 205;
const fixed = new Set([
  "scene.json",
  "manifest.json",
  "render-plan.json",
  "README.txt",
  "schema.json",
]);
export function safePath(name: string) {
  return (
    fixed.has(name) ||
    /^assets\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(png|mp4)$/.test(
      name,
    )
  );
}
export async function extractPackage(
  input: string,
  destination: string,
): Promise<Set<string>> {
  const stat = await fs.lstat(input);
  assert(
    stat.isFile() &&
      !stat.isSymbolicLink() &&
      stat.size >= 22 &&
      stat.size <= MAX_BYTES,
    "Invalid ZIP file or byte limit",
  );
  const bytes = await fs.readFile(input);
  const end = bytes.length - 22;
  assert(
    bytes.readUInt32LE(end) === 0x06054b50 &&
      bytes.readUInt16LE(end + 4) === 0 &&
      bytes.readUInt16LE(end + 6) === 0 &&
      bytes.readUInt16LE(end + 20) === 0,
    "ZIP comments, trailing data, or multiple disks are forbidden",
  );
  const count = bytes.readUInt16LE(end + 10);
  assert(
    count === bytes.readUInt16LE(end + 8) &&
      count <= MAX_FILES &&
      count >= 5 &&
      bytes.readUInt32LE(end + 12) + bytes.readUInt32LE(end + 16) === end,
    "ZIP64 or malformed directory",
  );
  assert(
    (await fs.readdir(destination)).length === 0,
    "Destination must be empty",
  );
  const seen = new Set<string>();
  let total = 0;
  const zip = await new Promise<yauzl.ZipFile>((resolve, reject) =>
    yauzl.fromBuffer(
      bytes,
      { lazyEntries: true, validateEntrySizes: true, autoClose: false },
      (error, result) => (error ? reject(error) : resolve(result!)),
    ),
  );
  try {
    await new Promise<void>((resolve, reject) => {
      zip.on("error", reject);
      zip.on("end", () => {
        try {
          assert(seen.size === count, "ZIP entry count mismatch");
          resolve();
        } catch (e) {
          reject(e);
        }
      });
      zip.on("entry", (entry: yauzl.Entry) => {
        void (async () => {
          const name = entry.fileName;
          assert(
            safePath(name) && !seen.has(name),
            "Unsafe or duplicate ZIP path",
          );
          seen.add(name);
          assert(
            entry.extraFields.length === 0 &&
              entry.fileCommentLength === 0 &&
              (entry.generalPurposeBitFlag & ~0x0808) === 0 &&
              [0, 8].includes(entry.compressionMethod),
            "ZIP encrypted or unsupported options",
          );
          const mode = (entry.externalFileAttributes >>> 16) & 0xf000;
          assert(
            mode === 0 || mode === 0x8000,
            "ZIP links/directories are forbidden",
          );
          assert(
            entry.uncompressedSize > 0 &&
              entry.uncompressedSize <= MAX_BYTES &&
              entry.compressedSize > 0,
            "ZIP entry size invalid",
          );
          total += entry.uncompressedSize;
          assert(total <= MAX_BYTES, "Unpacked byte limit");
          assert(
            entry.uncompressedSize <= 1024 * 1024 ||
              entry.uncompressedSize / entry.compressedSize <= 200,
            "ZIP expansion ratio exceeded",
          );
          const offset = entry.relativeOffsetOfLocalHeader;
          assert(
            offset + 30 <= end && bytes.readUInt32LE(offset) === 0x04034b50,
            "Missing local header",
          );
          const nameLength = bytes.readUInt16LE(offset + 26),
            extraLength = bytes.readUInt16LE(offset + 28);
          assert(
            extraLength === 0 &&
              bytes
                .subarray(offset + 30, offset + 30 + nameLength)
                .toString("utf8") === name &&
              bytes.readUInt16LE(offset + 6) === entry.generalPurposeBitFlag &&
              bytes.readUInt16LE(offset + 8) === entry.compressionMethod,
            "Local/central ZIP disagreement",
          );
          assert(
            offset + 30 + nameLength + entry.compressedSize <=
              bytes.readUInt32LE(end + 16),
            "ZIP data overlaps directory",
          );
          const stream = await new Promise<NodeJS.ReadableStream>((res, rej) =>
            zip.openReadStream(entry, (e, s) => (e ? rej(e) : res(s!))),
          );
          const blocks: Buffer[] = [];
          let size = 0;
          for await (const block of stream) {
            const b = Buffer.from(block);
            size += b.length;
            assert(
              size <= entry.uncompressedSize,
              "ZIP expanded size mismatch",
            );
            blocks.push(b);
          }
          assert(size === entry.uncompressedSize, "ZIP truncated entry");
          const out = Buffer.concat(blocks);
          assert(crc32(out) === entry.crc32, "ZIP CRC mismatch");
          const file = path.join(destination, name);
          await fs.mkdir(path.dirname(file), { recursive: true });
          await fs.writeFile(file, out, { flag: "wx", mode: 0o600 });
          zip.readEntry();
        })().catch(reject);
      });
      zip.readEntry();
    });
  } finally {
    zip.close();
  }
  return seen;
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
    timeout: 120000,
    maxBuffer: 12 * 1024 * 1024,
    encoding: "utf8",
  });
  assert(!result.stderr.trim(), `${tool} reported media errors`);
  return result.stdout;
}
const inputOptions = [
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
  const frames = JSON.parse(
    await command("ffprobe", [
      ...inputOptions,
      "-show_frames",
      "-show_entries",
      "frame=media_type,best_effort_timestamp_time,pkt_duration_time,duration_time,width,height,pix_fmt,color_transfer",
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
export async function loadPackage(input: string) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "tapscene-ai-"));
  try {
    const files = await extractPackage(input, root);
    const read = (n: string) => fs.readFile(path.join(root, n));
    const manifest = manifestSchema.parse(
      parseJson(await read("manifest.json"), 64 * 1024),
    );
    const { scene, plan } = validateContracts(
      parseJson(await read("scene.json"), 512 * 1024),
      parseJson(await read("render-plan.json"), 256 * 1024),
    );
    assert(
      manifest.schemaVersion === scene.schemaVersion &&
        manifest.releaseId === scene.releaseId &&
        manifest.contentDigest === plan.contentDigest,
      "Manifest identity mismatch",
    );
    const expected = new Set([...fixed, ...scene.assets.map((a) => a.path)]);
    assert(
      files.size === expected.size && [...files].every((n) => expected.has(n)),
      "Package file set mismatch",
    );
    const listed = new Set<string>();
    for (const f of manifest.files) {
      assert(
        f.path !== "manifest.json" &&
          expected.has(f.path) &&
          !listed.has(f.path),
        "Unexpected manifest file",
      );
      listed.add(f.path);
      const bytes = await read(f.path);
      assert(
        bytes.length === f.byteLength && sha256(bytes) === f.sha256,
        "File integrity mismatch",
      );
    }
    assert(listed.size === expected.size - 1, "Missing manifest file");
    for (const a of scene.assets) {
      const f = manifest.files.find((f) => f.path === a.path);
      assert(
        f?.sha256 === a.sha256 && f.byteLength === a.byteLength,
        "Scene asset mismatch",
      );
      if (a.role === "transition")
        await validateVideo(path.join(root, a.path), a);
      else decodePng(await read(a.path), a);
    }
    // Compare every crop to the actual safe base pixels. Never accept substituted/generated layer art.
    for (const r of scene.regions) {
      const base = scene.assets.find((a) => a.id === r.baseAssetId)!,
        crop = scene.assets.find((a) => a.id === r.assetId)!;
      const b = decodePng(await read(base.path), base),
        c = decodePng(await read(crop.path), crop);
      for (let y = 0; y < crop.height; y++) {
        const at = ((r.bbox.y + y) * base.width + r.bbox.x) * 4;
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
    return {
      root,
      scene,
      plan,
      cleanup: () => fs.rm(root, { recursive: true, force: true }),
    };
  } catch (error) {
    await fs.rm(root, { recursive: true, force: true });
    throw error;
  }
}
