/** Same deliberately narrow numeric/Unicode domain as the Android package codec. */
export function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}
export function parseJson(s: string, maxBytes: number): unknown {
  assert(
    typeof s === "string" && new TextEncoder().encode(s).length <= maxBytes,
    "JSON byte budget exceeded",
  );
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

/** RFC 8785 ordering/escaping within TapScene's deliberately restricted number domain. */
export function canonicalJson(value: unknown): string {
  let nodes = 0,
    chars = 0;
  const parents = new Set<object>();
  function quote(s: string): string {
    assert(s.length <= 8192, "JSON string budget exceeded");
    chars += s.length;
    assert(chars <= 262144, "JSON text budget exceeded");
    for (let i = 0; i < s.length; i++) {
      const c = s.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff) {
        const n = s.charCodeAt(++i);
        assert(n >= 0xdc00 && n <= 0xdfff, "Unpaired surrogate");
      } else assert(c < 0xdc00 || c > 0xdfff, "Unpaired surrogate");
    }
    return JSON.stringify(s);
  }
  function visit(v: unknown, depth: number): string {
    assert(depth <= 16 && ++nodes <= 8192, "JSON structure budget exceeded");
    if (v === null) return "null";
    if (typeof v === "string") return quote(v);
    if (typeof v === "boolean") return String(v);
    if (typeof v === "number") {
      assert(
        Number.isFinite(v) && Math.abs(v) <= Number.MAX_SAFE_INTEGER,
        "Unsafe JSON number",
      );
      const n = JSON.stringify(v);
      assert(
        Number.isInteger(v) || (Math.abs(v) <= 1 && /^-?0\.\d{1,6}$/.test(n)),
        "Unsupported JSON number",
      );
      return n;
    }
    assert(typeof v === "object", "Unsupported JSON value");
    assert(!parents.has(v), "Cyclic JSON value");
    assert(
      Object.getOwnPropertySymbols(v).length === 0,
      "Unexpected JSON symbol",
    );
    parents.add(v);
    let out: string;
    if (Array.isArray(v)) {
      assert(
        v.length <= 512 && Object.keys(v).length === v.length,
        "JSON array budget or sparse/extra fields",
      );
      const values: string[] = [];
      for (let i = 0; i < v.length; i++) {
        const d = Object.getOwnPropertyDescriptor(v, String(i));
        assert(
          d && "value" in d && d.enumerable,
          "JSON arrays require data items",
        );
        values.push(visit(d.value, depth + 1));
      }
      out = "[" + values.join(",") + "]";
    } else {
      assert(
        Object.getPrototypeOf(v) === Object.prototype ||
          Object.getPrototypeOf(v) === null,
        "JSON object must be plain data",
      );
      const keys = Object.getOwnPropertyNames(v).sort();
      assert(keys.length <= 64, "JSON object budget exceeded");
      out =
        "{" +
        keys
          .map((key) => {
            const d = Object.getOwnPropertyDescriptor(v, key)!;
            assert(
              "value" in d && d.enumerable,
              "JSON objects require enumerable data fields",
            );
            return quote(key) + ":" + visit(d.value, depth + 1);
          })
          .join(",") +
        "}";
    }
    parents.delete(v);
    return out;
  }
  const result = visit(value, 0);
  assert(
    new TextEncoder().encode(result).length <= 512 * 1024,
    "JSON byte budget exceeded",
  );
  return result;
}
