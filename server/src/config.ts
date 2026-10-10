import path from "node:path";
import { createHash } from "node:crypto";
export type Config = {
  host: "127.0.0.1";
  port: number;
  origin: string;
  databaseUrl: string;
  dataRoot: string;
  webRoot: string;
  authKey: Buffer;
  shareKey: Buffer;
};
export function config(env: NodeJS.ProcessEnv = process.env): Config {
  // This adapter deliberately cannot become a public deployment by flipping a flag.
  if (
    env.NODE_ENV === "production" ||
    (env.TAPSCENE_MODE && env.TAPSCENE_MODE !== "local")
  )
    throw new Error("Local adapters are forbidden in production");
  const host = env.HOST ?? "127.0.0.1";
  if (host !== "127.0.0.1")
    throw new Error("Local development must bind 127.0.0.1");
  const port = Number(env.PORT ?? 4173);
  if (!Number.isInteger(port) || port < 1024 || port > 65535)
    throw new Error("Invalid local port");
  const databaseUrl =
    env.DATABASE_URL ??
    "postgres://tapscene:tapscene-local-only@127.0.0.1:5432/tapscene";
  const url = new URL(databaseUrl);
  if (
    !["postgres:", "postgresql:"].includes(url.protocol) ||
    !["127.0.0.1", "localhost", "[::1]"].includes(url.hostname) ||
    Boolean(url.search || url.hash)
  )
    throw new Error("Local database must use loopback");
  return {
    host,
    port,
    origin: `http://${host}:${port}`,
    databaseUrl,
    dataRoot: path.resolve(env.TAPSCENE_DATA_DIR ?? ".local-data"),
    webRoot: path.resolve(env.TAPSCENE_WEB_DIR ?? "../web-player/dist"),
    authKey: Buffer.from("tapscene-local-synthetic-auth-key"),
    shareKey: createHash("sha256")
      .update("tapscene-local-synthetic-share-key")
      .digest(),
  };
}
