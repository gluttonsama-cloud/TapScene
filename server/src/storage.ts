import fs from "node:fs/promises";
import { constants } from "node:fs";
import path from "node:path";
import { createHash, randomUUID } from "node:crypto";
import type { Readable } from "node:stream";
import { ApiError, requireValue } from "./security.js";
/** Private, service-owned filesystem adapter. Keys never come from package paths. */
export class PrivateFiles {
  constructor(readonly root: string) {}
  async init() {
    await fs.mkdir(this.root, { recursive: true, mode: 0o700 });
    for (const dir of [
      this.root,
      ...["staging", "final", "inbox"].map((d) => path.join(this.root, d)),
    ]) {
      await fs.mkdir(dir, { mode: 0o700 }).catch((e: NodeJS.ErrnoException) => {
        if (e.code !== "EEXIST") throw e;
      });
      const s = await fs.lstat(dir);
      requireValue(
        s.isDirectory() && !s.isSymbolicLink() && (s.mode & 0o077) === 0,
        "UNSAFE_STORAGE",
        500,
      );
      requireValue(
        (await fs.realpath(dir)) === path.resolve(dir),
        "UNSAFE_STORAGE",
        500,
      );
    }
  }
  /** Bounded local orphan retention. All asset writes hold the DB storage lock. */
  async capacity(additional: number) {
    let used = 0;
    for (const dir of ["staging", "final", "inbox"]) {
      for (const entry of await fs.readdir(path.join(this.root, dir), {
        withFileTypes: true,
      })) {
        requireValue(
          entry.isFile() && !entry.isSymbolicLink(),
          "UNSAFE_STORAGE",
          503,
        );
        const info = await fs.lstat(path.join(this.root, dir, entry.name));
        used += info.size;
      }
    }
    requireValue(
      used + additional <= 1024 * 1024 * 1024,
      "LOCAL_STORAGE_LIMIT",
      507,
    );
    const available = await fs.statfs(this.root);
    requireValue(
      available.bavail * available.bsize >= additional + 64 * 1024 * 1024,
      "LOCAL_STORAGE_FULL",
      507,
    );
  }
  location(key: string) {
    requireValue(
      /^(staging|final)\/[0-9a-f-]{36}-[0-9a-f-]{36}-[0-9a-f]{64}$/.test(key),
      "UNSAFE_STORAGE_KEY",
      500,
    );
    return path.join(this.root, key);
  }
  async receive(
    stream: Readable,
    key: string,
    length: number,
    digest: string,
  ): Promise<void> {
    const destination = this.location(key),
      temporary = path.join(this.root, "staging", `.partial-${randomUUID()}`);
    const file = await fs.open(
      temporary,
      constants.O_WRONLY |
        constants.O_CREAT |
        constants.O_EXCL |
        constants.O_NOFOLLOW,
      0o600,
    );
    let size = 0;
    const hash = createHash("sha256");
    try {
      for await (const raw of stream) {
        const block = Buffer.from(raw);
        size += block.length;
        requireValue(size <= length, "ASSET_LENGTH_MISMATCH", 413);
        hash.update(block);
        await file.writeFile(block);
      }
      requireValue(size === length, "ASSET_LENGTH_MISMATCH");
      requireValue(hash.digest("hex") === digest, "ASSET_DIGEST_MISMATCH");
      await file.sync();
      await file.close();
      await fs.rename(temporary, destination);
      await this.syncDirectory("staging");
    } catch (e) {
      await file.close().catch(() => {});
      await fs.unlink(temporary).catch(() => {});
      throw e;
    }
  }
  async verify(key: string, length: number, digest: string) {
    const file = await fs.open(
      this.location(key),
      constants.O_RDONLY | constants.O_NOFOLLOW,
    );
    try {
      const s = await file.stat();
      requireValue(s.isFile() && s.size === length, "STORAGE_CORRUPT", 503);
      const hash = createHash("sha256");
      for await (const bytes of file.createReadStream({ autoClose: false }))
        hash.update(bytes);
      requireValue(hash.digest("hex") === digest, "STORAGE_CORRUPT", 503);
    } finally {
      await file.close();
    }
  }
  async finalize(
    staging: string,
    final: string,
    length: number,
    digest: string,
  ) {
    await this.verify(staging, length, digest);
    try {
      await this.verify(final, length, digest);
      await this.syncDirectory("final");
      return;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
    await this.capacity(length);
    // No final key can ever name partially copied bytes. A crashed partial is orphaned.
    const temporary = path.join(this.root, "final", `.partial-${randomUUID()}`);
    try {
      await fs.copyFile(
        this.location(staging),
        temporary,
        constants.COPYFILE_EXCL,
      );
      const f = await fs.open(
        temporary,
        constants.O_RDONLY | constants.O_NOFOLLOW,
      );
      try {
        await f.sync();
      } finally {
        await f.close();
      }
      // link is an atomic no-replace install on the same filesystem.
      try {
        await fs.link(temporary, this.location(final));
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
      }
      await this.syncDirectory("final");
    } finally {
      await fs.unlink(temporary).catch(() => {});
    }
    await this.verify(final, length, digest);
  }
  async open(key: string, length: number) {
    const f = await fs.open(
      this.location(key),
      constants.O_RDONLY | constants.O_NOFOLLOW,
    );
    try {
      const s = await f.stat();
      if (!s.isFile() || s.size !== length)
        throw new ApiError(503, "STORAGE_UNAVAILABLE");
      return f;
    } catch (error) {
      await f.close();
      throw error;
    }
  }
  async inbox(id: string, value: unknown) {
    requireValue(/^[0-9a-f-]{36}$/.test(id));
    await this.capacity(4096);
    const f = await fs.open(
      path.join(this.root, "inbox", `${id}.json`),
      "wx",
      0o600,
    );
    try {
      await f.writeFile(JSON.stringify(value));
      await f.sync();
    } finally {
      await f.close();
    }
  }
  private async syncDirectory(dir: string) {
    const f = await fs.open(path.join(this.root, dir), constants.O_RDONLY);
    try {
      await f.sync();
    } finally {
      await f.close();
    }
  }
}
