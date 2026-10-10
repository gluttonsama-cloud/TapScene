import { test } from "node:test";
import assert from "node:assert/strict";
import {
  assetUrl,
  expiryTime,
  fetchManifest,
  parseManifest,
  shareBase,
  ViewerError,
} from "../src/api";
import { fixture, id } from "./fixture";
const base = "/s/abcdefghijklmnop1234567890";

test("capability and media paths remain exact same-origin gateway routes", () => {
  assert.equal(shareBase(`${base}/`), base);
  assert.equal(assetUrl(base, id(12)), `${base}/assets/${id(12)}`);
  for (const path of [
    "/",
    "/s/a",
    `${base}/manifest`,
    "/s/../../secret",
    "/s/abc%2Fdefabcdefghijkl",
    "https://example.com/s/abcdefghijklmnop",
  ])
    assert.throws(() => shareBase(path));
  for (const asset of [
    "../../private",
    "https://example.com/a.png",
    "a%2fb",
    `${id(12)}?a=b`,
  ])
    assert.throws(() => assetUrl(base, asset));
  assert.throws(() => assetUrl("https://example.com", id(12)));
});

test("manifest rejects changed identities, unexpected fields and unsafe scenes", () => {
  assert.equal(parseManifest(fixture()).scene.title, fixture().scene.title);
  assert.throws(() => parseManifest({ ...fixture(), releaseId: id(99) }));
  assert.throws(() => parseManifest({ ...fixture(), privatePath: "/private" }));
  assert.throws(() => parseManifest({ ...fixture(), versionOrdinal: 1.5 }));
  assert.throws(() => parseManifest({ ...fixture(), expiresAt: "nonsense" }));
  assert.throws(() =>
    parseManifest({
      ...fixture(),
      scene: { ...fixture().scene, html: "<script>" },
    }),
  );
  assert.throws(() => expiryTime(Number.NaN));
});

test("manifest fetch uses no-store, no credentials, no Referer, and refuses redirects", async () => {
  const previous = globalThis.fetch;
  let options: RequestInit | undefined;
  globalThis.fetch = (async (url, init) => {
    assert.equal(url, `${base}/manifest`);
    options = init;
    return Response.json(fixture());
  }) as typeof fetch;
  try {
    await fetchManifest(base, new AbortController().signal);
    assert.equal(options?.cache, "no-store");
    assert.equal(options?.credentials, "omit");
    assert.equal(options?.referrerPolicy, "no-referrer");
    assert.equal(options?.redirect, "error");
  } finally {
    globalThis.fetch = previous;
  }
});

for (const [status, code] of [
  [404, "NOT_FOUND"],
  [410, "SHARE_REVOKED"],
  [410, "SHARE_EXPIRED"],
  [503, "SERVICE_UNAVAILABLE"],
] as const) {
  test(`manifest ${status}/${code} is an opaque access failure`, async () => {
    const previous = globalThis.fetch;
    globalThis.fetch = (async () =>
      Response.json(
        { error: { code, message: "never display untrusted details" } },
        { status },
      )) as typeof fetch;
    try {
      await assert.rejects(
        fetchManifest(base, new AbortController().signal),
        (error: unknown) =>
          error instanceof ViewerError &&
          error.code === code &&
          error.message === code,
      );
    } finally {
      globalThis.fetch = previous;
    }
  });
}
