import {
  createHash,
  createHmac,
  randomBytes,
  createCipheriv,
  createDecipheriv,
  timingSafeEqual,
} from "node:crypto";
export const uuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export class ApiError extends Error {
  constructor(
    public statusCode: number,
    public code: string,
  ) {
    super(code);
  }
}
export function requireValue(
  condition: unknown,
  code = "INVALID_REQUEST",
  status = 400,
): asserts condition {
  if (!condition) throw new ApiError(status, code);
}
export function uuid(value: unknown): string {
  requireValue(typeof value === "string" && uuidPattern.test(value));
  return value;
}
export function sha(data: string | Buffer): Buffer {
  return createHash("sha256").update(data).digest();
}
export function token(): string {
  return randomBytes(32).toString("base64url");
}
export function codeMac(
  key: Buffer,
  id: string,
  email: string,
  code: string,
): Buffer {
  return createHmac("sha256", key).update(`${id}\n${email}\n${code}`).digest();
}
export function equal(a: Buffer, b: Buffer): boolean {
  return a.length === b.length && timingSafeEqual(a, b);
}
export function encryptToken(key: Buffer, value: string): Buffer {
  const iv = randomBytes(12),
    c = createCipheriv("aes-256-gcm", key, iv);
  const bytes = Buffer.concat([c.update(value, "utf8"), c.final()]);
  return Buffer.concat([iv, c.getAuthTag(), bytes]);
}
export function decryptToken(key: Buffer, value: Buffer): string {
  const c = createDecipheriv("aes-256-gcm", key, value.subarray(0, 12));
  c.setAuthTag(value.subarray(12, 28));
  return Buffer.concat([c.update(value.subarray(28)), c.final()]).toString(
    "utf8",
  );
}
export function bodyObject(
  value: unknown,
  keys: string[],
): Record<string, unknown> {
  requireValue(
    value !== null && typeof value === "object" && !Array.isArray(value),
  );
  const b = value as Record<string, unknown>;
  requireValue(
    Object.keys(b).every((k) => keys.includes(k)) && keys.every((k) => k in b),
  );
  return b;
}
