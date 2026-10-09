import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { publishOutput } from "../src/render";

async function fixture(run: (root: string, source: string, output: string) => Promise<void>) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "tapscene-publish-test-"));
  const source = path.join(root, "validated.mp4"), output = path.join(root, "result.mp4");
  await fs.writeFile(source, Buffer.alloc(128 * 1024, 37));
  try { await run(root, source, output); }
  finally { await fs.rm(root, { recursive: true, force: true }); }
}

test("publishes complete media and basename-only report; staging is private until linked", async (t) => {
  await fixture(async (root, source, output) => {
    const copy = fs.copyFile.bind(fs);
    t.mock.method(fs, "copyFile", async (...args: Parameters<typeof fs.copyFile>) => {
      assert.notEqual(args[1], output);
      assert.equal(await fs.lstat(output).catch(() => null), null);
      assert.equal(await fs.lstat(output + ".json").catch(() => null), null);
      const staging = await fs.stat(path.dirname(String(args[1])));
      assert.equal(staging.mode & 0o077, 0);
      return copy(...args);
    });
    await publishOutput(source, output, { outputFile: path.basename(output), totalFrames: 90 });
    assert.deepEqual(await fs.readFile(output), await fs.readFile(source));
    const report = await fs.readFile(output + ".json", "utf8");
    assert.equal(JSON.parse(report).outputFile, "result.mp4");
    assert.equal(report.includes(root), false);
    assert.deepEqual((await fs.readdir(root)).sort(), ["result.mp4", "result.mp4.json", "validated.mp4"]);
  });
});

test("existing media is never overwritten and the new report is rolled back", async () => {
  await fixture(async (root, source, output) => {
    await fs.writeFile(output, "existing media");
    await assert.rejects(publishOutput(source, output, {}));
    assert.equal(await fs.readFile(output, "utf8"), "existing media");
    assert.equal(await fs.lstat(output + ".json").catch(() => null), null);
    assert.deepEqual((await fs.readdir(root)).sort(), ["result.mp4", "validated.mp4"]);
  });
});

test("existing report is never overwritten and no media is published", async () => {
  await fixture(async (root, source, output) => {
    await fs.writeFile(output + ".json", "existing report");
    await assert.rejects(publishOutput(source, output, {}));
    assert.equal(await fs.readFile(output + ".json", "utf8"), "existing report");
    assert.equal(await fs.lstat(output).catch(() => null), null);
    assert.deepEqual((await fs.readdir(root)).sort(), ["result.mp4.json", "validated.mp4"]);
  });
});

test("copy failure removes only private staging and publishes neither artifact", async (t) => {
  await fixture(async (root, source, output) => {
    t.mock.method(fs, "copyFile", async (_source: unknown, destination: string) => {
      await fs.writeFile(destination, "partial private bytes");
      throw new Error("simulated full disk");
    });
    await assert.rejects(publishOutput(source, output, {}), /simulated full disk/);
    assert.deepEqual(await fs.readdir(root), ["validated.mp4"]);
  });
});

test("media publication failure rolls back the complete report", async (t) => {
  await fixture(async (root, source, output) => {
    const link = fs.link.bind(fs);
    t.mock.method(fs, "link", async (from: string, to: string) => {
      if (to === output) throw new Error("simulated publication failure");
      return link(from, to);
    });
    await assert.rejects(publishOutput(source, output, {}), /simulated publication failure/);
    assert.deepEqual(await fs.readdir(root), ["validated.mp4"]);
  });
});

test("directory sync failure after media publication rolls back both artifacts", async (t) => {
  await fixture(async (root, source, output) => {
    const open = fs.open.bind(fs);
    let directorySyncs = 0;
    t.mock.method(fs, "open", async (...args: Parameters<typeof fs.open>) => {
      if (args[0] === root && ++directorySyncs === 2)
        throw new Error("simulated sync failure");
      return open(...args);
    });
    await assert.rejects(publishOutput(source, output, {}), /simulated sync failure/);
    assert.deepEqual(await fs.readdir(root), ["validated.mp4"]);
  });
});
