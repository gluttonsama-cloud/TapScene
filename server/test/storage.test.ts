import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { Readable } from "node:stream";
import { randomUUID } from "node:crypto";
import { PrivateFiles } from "../src/storage.js";
import { ApiError, sha } from "../src/security.js";

async function withFiles(fn: (files: PrivateFiles, root: string) => Promise<void>) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "tapscene-storage-test-"));
  try { await fn(new PrivateFiles(root), root); }
  finally { await fs.rm(root, { recursive: true, force: true }); }
}
const errorCode = (code: string) => (e: unknown) => e instanceof ApiError && e.code === code;
function keys(bytes: Buffer) {
  const suffix = `${randomUUID()}-${randomUUID()}-${sha(bytes).toString("hex")}`;
  return { staging: `staging/${suffix}`, final: `final/${suffix}`, digest: sha(bytes).toString("hex") };
}

test("private storage refuses symlink directories and permissive roots", async () => {
  for (const child of ["staging", "final", "inbox"]) {
    await withFiles(async (files, root) => {
      const outside = await fs.mkdtemp(path.join(os.tmpdir(), "tapscene-outside-test-"));
      try {
        await fs.symlink(outside, path.join(root, child));
        await assert.rejects(files.init(), errorCode("UNSAFE_STORAGE"));
        assert.deepEqual(await fs.readdir(outside), []);
      } finally { await fs.rm(outside, { recursive: true, force: true }); }
    });
  }
  await withFiles(async (files, root) => {
    await fs.chmod(root, 0o750);
    await assert.rejects(files.init(), errorCode("UNSAFE_STORAGE"));
  });
});

test("capacity counts orphaned and partial bytes toward the local 1 GiB cap", async () => {
  await withFiles(async (files, root) => {
    await files.init();
    const cap = 1024 * 1024 * 1024;
    // Sparse test files exercise the declared disk budget without allocating 1 GiB.
    const orphan = path.join(root, "staging", ".partial-orphan");
    const final = path.join(root, "final", ".partial-final");
    await fs.writeFile(orphan, Buffer.alloc(0));
    await fs.truncate(orphan, cap - 16);
    await fs.writeFile(final, Buffer.alloc(8));
    await files.capacity(8);
    await assert.rejects(files.capacity(9), errorCode("LOCAL_STORAGE_LIMIT"));
    await fs.writeFile(path.join(root, "inbox", "retained.json"), Buffer.alloc(9));
    await assert.rejects(files.capacity(0), errorCode("LOCAL_STORAGE_LIMIT"));
  });
});

test("final publication installs complete bytes atomically and is idempotent", async () => {
  await withFiles(async (files, root) => {
    await files.init();
    const bytes = Buffer.from("Synthetic verified media bytes"), k = keys(bytes);
    await files.receive(Readable.from([bytes.subarray(0, 7), bytes.subarray(7)]), k.staging, bytes.length, k.digest);
    await Promise.all([
      files.finalize(k.staging, k.final, bytes.length, k.digest),
      files.finalize(k.staging, k.final, bytes.length, k.digest),
    ]);
    assert.deepEqual(await fs.readFile(files.location(k.final)), bytes);
    const before = await fs.stat(files.location(k.final));
    assert.equal(before.nlink, 1);
    await files.finalize(k.staging, k.final, bytes.length, k.digest);
    const after = await fs.stat(files.location(k.final));
    assert.equal(after.ino, before.ino);
    assert.equal(after.size, bytes.length);
    assert.deepEqual(await fs.readdir(path.join(root, "final")), [path.basename(k.final)]);
    assert.deepEqual(await fs.readFile(files.location(k.staging)), bytes);
  });
});

test("broken pre-existing final keys fail closed without overwrite or partial leftovers", async () => {
  for (const sameLength of [false, true]) {
    await withFiles(async (files, root) => {
      await files.init();
      const bytes = Buffer.from("Verified synthetic bytes"), k = keys(bytes);
      await files.receive(Readable.from([bytes]), k.staging, bytes.length, k.digest);
      const broken = Buffer.alloc(sameLength ? bytes.length : 3, 65);
      await fs.writeFile(files.location(k.final), broken, { mode: 0o600 });
      await assert.rejects(files.finalize(k.staging, k.final, bytes.length, k.digest), errorCode("STORAGE_CORRUPT"));
      assert.deepEqual(await fs.readFile(files.location(k.final)), broken);
      assert.deepEqual(await fs.readFile(files.location(k.staging)), bytes);
      assert.deepEqual(await fs.readdir(path.join(root, "final")), [path.basename(k.final)]);
    });
  }
});

test("failed incoming length or digest leaves no accepted asset or partial", async () => {
  await withFiles(async (files, root) => {
    await files.init();
    const bytes = Buffer.from("Synthetic bytes"), k = keys(bytes);
    await assert.rejects(files.receive(Readable.from([bytes]), k.staging, bytes.length - 1, k.digest), errorCode("ASSET_LENGTH_MISMATCH"));
    await assert.rejects(files.receive(Readable.from([bytes]), k.staging, bytes.length, "0".repeat(64)), errorCode("ASSET_DIGEST_MISMATCH"));
    assert.deepEqual(await fs.readdir(path.join(root, "staging")), []);
  });
});
