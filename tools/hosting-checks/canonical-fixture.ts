import fs from "node:fs/promises";
import path from "node:path";
import { createHash } from "node:crypto";
import { canonicalJson, parseJson, validateScene } from "../../runtime-ts/src/index.js";
import { fixture, id } from "../../runtime-ts/test/fixtures.js";

// Shared test bytes, not app data. Exercise Unicode and the restricted fractional number domain.
const output = path.resolve(process.argv[2]);
await fs.mkdir(output, { recursive: true });
const scenes = [fixture(false), fixture(true), fixture(true)];
scenes[2].schemaVersion = 3;
scenes[2].policyVersion = "scene-regions-3";
scenes[2].assets.push({ id: id(102), path: `assets/${id(102)}.png`, role: "region-crop", mime: "image/png",
  byteLength: 64, sha256: "c".repeat(64), width: 10, height: 20, durationMs: null });
scenes[2].regions = [{ id: id(200), stateId: id(1), baseAssetId: id(100), assetId: id(102),
  name: "可见裁片 🟦", kind: "screenshotCrop", coordinateSpace: "source-pixels", sourceWidth: 100, sourceHeight: 200, bbox: { x: 1, y: 2, width: 10, height: 20 },
  group: "header", zIndex: -2, anchor: { coordinateSpace: "layer-normalized", x: .000001, y: .999999 } }];
for (const [index, source] of scenes.entries()) {
  source.title = "合成闭环：中文 / quote \" / emoji 🟦";
  source.goal = "line 1\nline 2\t\\";
  const scene = validateScene(source);
  const bytes = Buffer.from(canonicalJson(scene));
  const digest = createHash("sha256").update(bytes).digest("hex");
  const name = `schema-${index + 1}`;
  await fs.writeFile(path.join(output, `${name}.json`), bytes);
  await fs.writeFile(path.join(output, `${name}.sha256`), digest);
  if (canonicalJson(validateScene(parseJson(bytes.toString(), 512 * 1024))) !== bytes.toString()) throw new Error("TS canonical round-trip changed bytes");
}
console.log("HOST_HOSTED_CANONICAL TS generated schemas=1,2,3 synthetic shared fixture");
