import fs from "node:fs/promises";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { canonicalJson } from "@tapscene/runtime-ts";
import { config } from "../src/config.js";
import { sha } from "../src/security.js";
import { syntheticFixture } from "./synthetic.js";
const cfg = config(),
  origin = cfg.origin;
async function request(
  method: string,
  url: string,
  body?: unknown,
  session?: string,
  key?: string,
) {
  const r = await fetch(origin + url, {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(session ? { Authorization: `Bearer ${session}` } : {}),
      ...(key ? { "Idempotency-Key": key } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const data = await r.json();
  if (!r.ok)
    throw new Error(`${method} ${url}: ${r.status} ${JSON.stringify(data)}`);
  return data as any;
}
const login = await request("POST", "/api/v1/auth/challenges", {
  email: `fixture-${randomUUID()}@example.test`,
});
const inbox = JSON.parse(
  await fs.readFile(
    path.join(cfg.dataRoot, "inbox", `${login.challengeId}.json`),
    "utf8",
  ),
);
const auth = await request("POST", "/api/v1/auth/sessions", {
    challengeId: login.challengeId,
    code: inbox.code,
  }),
  session = auth.sessionToken;
const project = await request(
  "POST",
  "/api/v1/projects",
  { clientProjectId: randomUUID(), title: "本地合成演示" },
  session,
);
const { scene, bytes } = syntheticFixture(),
  contentDigest = sha(canonicalJson(scene)).toString("hex");
const upload = await request(
  "POST",
  "/api/v1/publication-uploads",
  {
    serverProjectId: project.serverProjectId,
    releaseId: scene.releaseId,
    contentDigest,
    expiryDays: 1,
    scene,
  },
  session,
  randomUUID(),
);
for (const asset of scene.assets) {
  const r = await fetch(
    `${origin}/api/v1/publication-uploads/${upload.uploadId}/assets/${asset.id}`,
    {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${session}`,
        "Content-Type": asset.mime,
        "Content-Length": String(asset.byteLength),
        "X-Content-SHA256": asset.sha256,
      },
      body: new Uint8Array(bytes.get(asset.id)!),
    },
  );
  if (!r.ok) throw new Error(`Asset rejected: ${r.status}`);
}
await request(
  "POST",
  `/api/v1/publication-uploads/${upload.uploadId}/commit`,
  { releaseId: scene.releaseId, contentDigest },
  session,
  randomUUID(),
);
const deadline = Date.now() + 60000;
let result;
while (Date.now() < deadline) {
  result = await request(
    "GET",
    `/api/v1/publication-uploads/${upload.uploadId}`,
    undefined,
    session,
  );
  if (result.state === "committed") break;
  if (["rejected", "cancelled", "expired"].includes(result.state))
    throw new Error(`Publish ${result.state}: ${JSON.stringify(result.error)}`);
  await new Promise((r) => setTimeout(r, 300));
}
if (result?.state !== "committed")
  throw new Error("Publication still pending; query upload status to resume");
await fs.writeFile(
  path.join(cfg.dataRoot, "fixture-publication.json"),
  JSON.stringify(
    { publication: result.publication, sessionToken: session },
    null,
    2,
  ),
  { mode: 0o600 },
);
process.stdout.write(
  `Synthetic viewer ready: ${result.publication.shareUrl}\nLocal receipt: ${path.join(cfg.dataRoot, "fixture-publication.json")}\n`,
);
