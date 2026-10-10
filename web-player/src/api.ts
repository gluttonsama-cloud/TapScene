import { validateScene, type Scene } from "@tapscene/runtime-ts";

export type HostedManifest = {
  releaseId: string;
  contentDigest: string;
  versionOrdinal: number;
  expiresAt: string | number;
  scene: Scene;
};

export class ViewerError extends Error {
  constructor(
    readonly code: string,
    readonly retryable = false,
  ) {
    super(code);
    this.name = "ViewerError";
  }
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** The capability is used only in same-origin paths, never storage or logs. */
export function shareBase(pathname: string): string {
  const match = /^\/s\/([A-Za-z0-9_-]{16,256})\/?$/.exec(pathname);
  if (!match) throw new ViewerError("NOT_FOUND");
  return `/s/${match[1]}`;
}

export function assetUrl(base: string, assetId: string): string {
  if (!/^\/s\/[A-Za-z0-9_-]{16,256}$/.test(base) || !uuid.test(assetId)) {
    throw new ViewerError("INVALID_CONTENT");
  }
  return `${base}/assets/${assetId}`;
}

export function expiryTime(expiresAt: string | number): number {
  const value =
    typeof expiresAt === "number" ? expiresAt : Date.parse(expiresAt);
  if (!Number.isSafeInteger(value) || value < 0)
    throw new ViewerError("INVALID_CONTENT");
  return value;
}

export function parseManifest(input: unknown): HostedManifest {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    throw new ViewerError("INVALID_CONTENT");
  }
  const data = input as Record<string, unknown>;
  if (
    Object.keys(data).sort().join(",") !==
      "contentDigest,expiresAt,releaseId,scene,versionOrdinal" ||
    typeof data.releaseId !== "string" ||
    !uuid.test(data.releaseId) ||
    typeof data.contentDigest !== "string" ||
    !/^[0-9a-f]{64}$/.test(data.contentDigest) ||
    !Number.isSafeInteger(data.versionOrdinal) ||
    Number(data.versionOrdinal) < 1 ||
    (typeof data.expiresAt !== "string" && typeof data.expiresAt !== "number")
  )
    throw new ViewerError("INVALID_CONTENT");
  try {
    expiryTime(data.expiresAt);
    const scene = validateScene(data.scene);
    if (scene.releaseId !== data.releaseId)
      throw new ViewerError("INVALID_CONTENT");
    return {
      releaseId: data.releaseId,
      contentDigest: data.contentDigest,
      versionOrdinal: data.versionOrdinal as number,
      expiresAt: data.expiresAt,
      scene,
    };
  } catch {
    throw new ViewerError("INVALID_CONTENT");
  }
}

export async function fetchManifest(
  base: string,
  signal: AbortSignal,
): Promise<HostedManifest> {
  let response: Response;
  try {
    response = await fetch(`${base}/manifest`, {
      signal,
      cache: "no-store",
      credentials: "omit",
      redirect: "error",
      referrerPolicy: "no-referrer",
      headers: { Accept: "application/json" },
    });
  } catch (error) {
    if (signal.aborted) throw error;
    throw new ViewerError("SERVICE_UNAVAILABLE", true);
  }
  if (!response.ok) {
    let code = "";
    try {
      const body = (await response.json()) as { error?: { code?: unknown } };
      if (typeof body.error?.code === "string") code = body.error.code;
    } catch {
      /* Error bodies are optional and never shown verbatim. */
    }
    if (response.status === 404) throw new ViewerError("NOT_FOUND");
    if (response.status === 410) {
      throw new ViewerError(
        code === "SHARE_REVOKED" ? "SHARE_REVOKED" : "SHARE_EXPIRED",
      );
    }
    throw new ViewerError("SERVICE_UNAVAILABLE", true);
  }
  try {
    return parseManifest(await response.json());
  } catch (error) {
    if (error instanceof ViewerError) throw error;
    throw new ViewerError("INVALID_CONTENT");
  }
}

export type LoadedImage = { element: HTMLImageElement; dispose(): void };

/** Load and decode once; the same element is later placed into the visible stage. */
export function loadImage(
  base: string,
  assetId: string,
  signal: AbortSignal,
): Promise<LoadedImage> {
  return new Promise((resolve, reject) => {
    const element = new Image();
    element.referrerPolicy = "no-referrer";
    element.decoding = "async";
    element.draggable = false;
    element.alt = "";
    let settled = false;
    const cleanup = () => {
      element.onload = null;
      element.onerror = null;
      signal.removeEventListener("abort", aborted);
    };
    const dispose = () => {
      element.removeAttribute("src");
      element.remove();
    };
    const fail = (error: Error) => {
      if (settled) return;
      settled = true;
      cleanup();
      dispose();
      reject(error);
    };
    const aborted = () => fail(new DOMException("Aborted", "AbortError"));
    element.onerror = () => fail(new ViewerError("IMAGE_FAILED", true));
    element.onload = async () => {
      try {
        await element.decode();
        if (settled || signal.aborted) return;
        if (!element.naturalWidth || !element.naturalHeight)
          throw new Error("Empty image");
        settled = true;
        cleanup();
        resolve({ element, dispose });
      } catch {
        fail(new ViewerError("IMAGE_FAILED", true));
      }
    };
    signal.addEventListener("abort", aborted, { once: true });
    if (signal.aborted) return aborted();
    element.src = assetUrl(base, assetId);
  });
}
