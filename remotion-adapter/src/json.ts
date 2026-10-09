/** Same deliberately narrow numeric/Unicode domain as the Android package codec. */
export function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}
export function parseJson(bytes: Uint8Array, maxBytes: number): unknown {
  assert(bytes.length <= maxBytes, "JSON byte budget exceeded");
  const s = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  let at = 0,
    nodes = 0,
    chars = 0;
  const space = () => {
    while (/[ \t\r\n]/.test(s[at] ?? "x")) at++;
  };
  function string(): string {
    const start = at++;
    let escaped = false;
    while (at < s.length) {
      const c = s[at++];
      if (c === '"' && !escaped) {
        const result: string = JSON.parse(s.slice(start, at));
        assert(result.length <= 8192, "JSON string budget exceeded");
        chars += result.length;
        assert(chars <= 262144, "JSON text budget exceeded");
        for (let i = 0; i < result.length; i++) {
          const c = result.charCodeAt(i);
          if (c >= 0xd800 && c <= 0xdbff) {
            const n = result.charCodeAt(++i);
            assert(n >= 0xdc00 && n <= 0xdfff, "Unpaired surrogate");
          } else assert(c < 0xdc00 || c > 0xdfff, "Unpaired surrogate");
        }
        return result;
      }
      if (c === "\\" && !escaped) escaped = true;
      else escaped = false;
    }
    throw new Error("Unterminated string");
  }
  function value(depth: number): unknown {
    assert(depth <= 16 && ++nodes <= 8192, "JSON structure budget exceeded");
    space();
    const c = s[at];
    if (c === '"') return string();
    if (c === "{" || c === "[") {
      const object = c === "{";
      at++;
      space();
      const close = object ? "}" : "]";
      const out: any = object ? Object.create(null) : [];
      const keys = new Set<string>();
      let count = 0;
      if (s[at] === close) {
        at++;
        return out;
      }
      while (true) {
        assert(
          ++count <= (object ? 64 : 512),
          "JSON collection budget exceeded",
        );
        space();
        if (object) {
          assert(s[at] === '"', "Expected key");
          const key = string();
          assert(!keys.has(key), "Duplicate JSON key");
          keys.add(key);
          space();
          assert(s[at++] === ":", "Expected colon");
          out[key] = value(depth + 1);
        } else out.push(value(depth + 1));
        space();
        if (s[at] === close) {
          at++;
          return out;
        }
        assert(s[at++] === ",", "Expected comma");
      }
    }
    for (const [word, result] of [
      ["true", true],
      ["false", false],
      ["null", null],
    ] as const)
      if (s.startsWith(word, at)) {
        at += word.length;
        return result;
      }
    const token = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,6})?/.exec(
      s.slice(at),
    )?.[0];
    assert(token && token.length <= 24, "Invalid JSON value");
    at += token.length;
    assert(!/[eE.0-9]/.test(s[at] ?? "x"), "Unsupported JSON number");
    const n = Number(token);
    assert(
      Number.isFinite(n) &&
        Math.abs(n) <= Number.MAX_SAFE_INTEGER &&
        (!token.includes(".") || Math.abs(n) <= 1),
      "Unsafe JSON number",
    );
    return n;
  }
  const out = value(0);
  space();
  assert(at === s.length, "Trailing JSON content");
  return out;
}
export function canonical(value: unknown): string {
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
  if (value !== null && typeof value === "object")
    return (
      "{" +
      Object.keys(value)
        .sort()
        .map(
          (k) =>
            JSON.stringify(k) +
            ":" +
            canonical((value as Record<string, unknown>)[k]),
        )
        .join(",") +
      "}"
    );
  return JSON.stringify(value);
}
