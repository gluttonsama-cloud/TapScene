#!/usr/bin/env python3
"""Fail-closed, offline checks for TapScene's v0.1 proposal documents.

Supported Markdown: ATX headings, fenced/inline code, pipe tables, task lists,
inline [label](target) links/images and HTTP(S) autolinks. Link targets cannot
contain whitespace, parentheses or titles (percent-encode them). Reference and
shortcut links, Setext headings, HTML (except <br>) and multiline inline syntax
are rejected rather than silently ignored. External URLs are syntax-checked,
not fetched; product semantics and human approvals are outside this checker.
"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import html
import json
from pathlib import Path
import re
import sys
import unicodedata
from urllib.parse import unquote, urlsplit

from requirements import REGISTRY, load_registry

PRODUCT = Path("docs/product/product-spec-v0.1.md")
TRACE = Path("docs/product/requirements-traceability.md")
MANIFEST = Path("docs/product/baseline-manifest.json")
EXPECTED = {f"F{i:02}" for i in range(1, 30)} | {f"A{i:02}" for i in range(1, 17)}
TRACE_HEADERS = ["ID", "来源与理由", "优先级", "负责人", "验收条件", "依赖", "提案/ADR", "实现 PR", "测试证据", "目标版本", "状态"]
LINK = re.compile(r"!?\[([^\[\]\n]*)\]\(([^\s()]+)\)")
ID = re.compile(r"(?<![A-Za-z0-9_])([FA][0-9]+)(?![A-Za-z0-9_])")


class CheckError(ValueError):
    pass


@dataclass
class Document:
    path: Path
    text: str
    headings: list[tuple[int, str]]
    anchors: set[str]
    rows: list[tuple[int, list[str]]]
    links: list[tuple[int, str]]


def read_utf8(path: Path) -> str:
    try:
        if path.is_symlink():
            raise CheckError(f"{path}: symlinks are not supported")
        raw = path.read_bytes()
        text = raw.decode("utf-8")
    except (OSError, UnicodeError) as exc:
        raise CheckError(f"{path}: cannot read strict UTF-8: {exc}") from exc
    if not text.strip() or text.startswith("\ufeff") or "\x00" in text or "\r" in text:
        raise CheckError(f"{path}: require nonempty UTF-8 without BOM, NUL or CR")
    if not text.endswith("\n"):
        raise CheckError(f"{path}: missing final newline")
    return text


def plain_label(text: str) -> str:
    return re.sub(r"[`*_~]", "", LINK.sub(lambda m: m[1], text)).strip()


def slug(text: str) -> str:
    """GitHub-style anchors for the documented heading subset (including CJK)."""
    text = html.unescape(plain_label(text)).lower()
    return "".join(c for c in text if c in "-_ " or unicodedata.category(c)[0] in "LNM").replace(" ", "-")


def without_code(line: str, where: str) -> str:
    result, pos = [], 0
    while pos < len(line):
        if line[pos] == "\\" and pos + 1 < len(line):
            # Escaped Markdown punctuation is literal, never an unchecked link.
            result.append("  ")
            pos += 2
        elif line[pos] == "`":
            fence = re.match(r"`+", line[pos:])[0]
            end = pos + len(fence)
            close = re.search(r"(?<!`)" + re.escape(fence) + r"(?!`)", line[end:])
            if close is None:
                raise CheckError(f"{where}: unclosed inline code; multiline code spans unsupported")
            stop = end + close.end()
            result.append(" " * (stop - pos))
            pos = stop
        else:
            result.append(line[pos])
            pos += 1
    return "".join(result)


def table_cells(line: str) -> list[str]:
    return [cell.strip() for cell in re.split(r"(?<!\\)\|", line.strip())[1:-1]]


def parse_markdown(path: Path) -> Document:
    text = read_utf8(path)
    headings, rows, links, anchors = [], [], [], set()
    counts: Counter[str] = Counter()
    fence = None
    previous_level = 0
    h1 = 0
    table = []

    def finish_table() -> None:
        if not table:
            return
        if len(table) < 2 or not all(re.fullmatch(r":?-{3,}:?", c) for c in table[1][1]):
            raise CheckError(f"{path}:{table[0][0]}: table requires a header and delimiter row")
        width = len(table[0][1])
        if width == 0 or any(len(cells) != width for _, cells in table):
            raise CheckError(f"{path}:{table[0][0]}: inconsistent table column counts")
        rows.extend(table)
        table.clear()

    lines = text.splitlines()
    for number, raw in enumerate(lines, 1):
        where = f"{path}:{number}"
        marker = re.match(r"^ {0,3}(`{3,}|~{3,})(.*)$", raw)
        if fence:
            if marker and marker[1][0] == fence[0] and len(marker[1]) >= len(fence) and not marker[2].strip():
                fence = None
            continue
        if marker:
            finish_table()
            fence = marker[1]
            continue
        line = without_code(raw, where)
        if line.strip().startswith("|"):
            if not line.strip().endswith("|"):
                raise CheckError(f"{where}: table rows must have closing pipes")
            table.append((number, table_cells(raw)))
        else:
            finish_table()
        if re.match(r"^ {0,3}={3,}\s*$", line) or (re.match(r"^ {0,3}-{3,}\s*$", line) and number > 1 and lines[number - 2].strip()):
            raise CheckError(f"{where}: Setext headings unsupported; use # headings")
        heading = re.match(r"^ {0,3}(#{1,6})\s+(.+?)\s*#*\s*$", raw)
        if re.match(r"^ {0,3}#", raw) and not heading:
            raise CheckError(f"{where}: malformed or empty ATX heading")
        if heading:
            level, label = len(heading[1]), heading[2]
            if level > previous_level + 1:
                raise CheckError(f"{where}: heading levels skip from {previous_level} to {level}")
            previous_level = level
            h1 += level == 1
            headings.append((number, label))
            base = slug(label)
            anchor = base if counts[base] == 0 else f"{base}-{counts[base]}"
            while anchor in anchors:
                counts[base] += 1
                anchor = f"{base}-{counts[base]}"
            anchors.add(anchor)
            counts[base] += 1
        for match in LINK.finditer(line):
            links.append((number, match[2]))
        rest = LINK.sub("", line)
        rest = re.sub(r"^\s*[-*+]\s+\[[ xX]\](?=\s|$)", "", rest)
        # Autolinks are allowed only for external HTTP(S) destinations.
        for match in re.finditer(r"<(https?://[^<>\s]+)>", rest):
            links.append((number, match[1]))
        rest = re.sub(r"<https?://[^<>\s]+>", "", rest)
        rest = re.sub(r"<br\s*/?>", "", rest, flags=re.I)
        if "[" in rest or "]" in rest:
            raise CheckError(f"{where}: unsupported/malformed link; use [label](target), escape literal brackets")
        if re.search(r"<[/!?A-Za-z][^>]*>", rest):
            raise CheckError(f"{where}: raw HTML unsupported; use Markdown links/headings")
    finish_table()
    if fence:
        raise CheckError(f"{path}: unclosed fenced code block")
    if h1 != 1:
        raise CheckError(f"{path}: expected exactly one H1, found {h1}")
    return Document(path, text, headings, anchors, rows, links)


def check_links(root: Path, documents: dict[Path, Document]) -> None:
    for path, document in documents.items():
        for line, target in document.links:
            where = f"{path}:{line}"
            try:
                url = urlsplit(target)
            except ValueError as exc:
                raise CheckError(f"{where}: invalid URL {target!r}") from exc
            if url.scheme:
                if url.scheme not in {"http", "https", "mailto"} or (url.scheme != "mailto" and not url.netloc):
                    raise CheckError(f"{where}: unsupported/invalid link scheme: {target}")
                continue
            if url.netloc or url.query or "\\" in target or target.startswith("/"):
                raise CheckError(f"{where}: local links must be relative paths without queries: {target}")
            dest = (path.parent / unquote(url.path)).resolve() if url.path else path
            if not dest.is_relative_to(root) or not dest.exists():
                raise CheckError(f"{where}: broken/outside-repository local link: {target}")
            if dest.is_dir():
                dest = dest / "README.md"
                if not dest.is_file():
                    raise CheckError(f"{where}: directory link requires README.md: {target}")
            if url.fragment and (dest not in documents or unquote(url.fragment) not in documents[dest].anchors):
                raise CheckError(f"{where}: broken or unsupported anchor: {target}")


def require_ids(values: list[str], where: str) -> None:
    counts = Counter(values)
    missing = sorted(EXPECTED - counts.keys())
    unexpected = sorted(counts.keys() - EXPECTED)
    duplicate = sorted(key for key, count in counts.items() if count != 1)
    if missing or unexpected or duplicate:
        raise CheckError(f"{where}: invalid IDs; missing={missing}, unexpected={unexpected}, duplicates={duplicate}")


def check_known_references(document: Document) -> None:
    unknown = sorted(set(ID.findall(document.text)) - EXPECTED)
    if unknown:
        raise CheckError(f"{document.path}: invalid IDs; out-of-range/malformed requirement references: {unknown}")


def check_product(document: Document) -> None:
    check_known_references(document)
    features = [m[1] for _, label in document.headings if (m := re.match(r"^(F\d+)(?:\s|$)", plain_label(label)))]
    acceptance = [m[1] for _, cells in document.rows if (m := re.match(r"^(A\d+)(?:\s|$)", plain_label(cells[0])))]
    require_ids(features + acceptance, str(PRODUCT))


def check_trace(document: Document) -> None:
    check_known_references(document)
    headers = [cells for _, cells in document.rows if cells[0] == "ID"]
    if not headers or any(header != TRACE_HEADERS for header in headers):
        raise CheckError(f"{TRACE}: every trace table must use headers {TRACE_HEADERS}")
    definitions = []
    for number, cells in document.rows:
        if cells[0] == "ID" or all(re.fullmatch(r":?-{3,}:?", c) for c in cells):
            continue
        value = plain_label(cells[0])
        if not re.fullmatch(r"[FA]\d+", value):
            raise CheckError(f"{TRACE}:{number}: unexpected trace row ID {value!r}")
        definitions.append(value)
        if len(cells) != len(TRACE_HEADERS) or any(not plain_label(c) or c.strip() in {"-", "—", "TBD", "TODO"} for c in cells):
            raise CheckError(f"{TRACE}:{number}: all trace fields must be explicit; use truthful pending/not-run explanations")
    require_ids(definitions, str(TRACE))


def check_baseline(root: Path) -> None:
    try:
        manifest = json.loads(read_utf8(root / MANIFEST))
        expected = manifest["sha256"]
        if manifest["path"] != PRODUCT.as_posix() or not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ValueError("invalid path/sha256 fields")
    except (KeyError, TypeError, ValueError) as exc:
        raise CheckError(f"{MANIFEST}: invalid baseline manifest: {exc}") from exc
    actual = hashlib.sha256((root / PRODUCT).read_bytes()).hexdigest()
    if actual != expected:
        raise CheckError(f"{PRODUCT}: SHA-256 differs from baseline manifest; review baseline changes explicitly")


def check_adrs(root: Path, documents: dict[Path, Document]) -> None:
    expected = {root / f"docs/adr/{i:03}-decision.md" for i in range(1, 8)}
    actual = {p for p in documents if p.parent == root / "docs/adr" and p.name != "README.md"}
    if actual != expected:
        raise CheckError("docs/adr: require exactly 001-decision.md through 007-decision.md")
    for path in sorted(expected):
        statuses = re.findall(r"^状态[：:]\s*([^。\n]+)", documents[path].text, flags=re.M)
        if statuses != ["Proposed"]:
            raise CheckError(f"{path}: exactly one status must be Proposed, found {statuses}")


def check_repository(root: Path) -> int:
    root = root.resolve()
    paths = sorted(p for p in root.rglob("*") if p.suffix.lower() in {".md", ".markdown"} and ".git" not in p.relative_to(root).parts)
    if not paths:
        raise CheckError("no Markdown documents found")
    documents = {p: parse_markdown(p) for p in paths}
    for required in (PRODUCT, TRACE):
        if root / required not in documents:
            raise CheckError(f"missing required document: {required}")
    try:
        load_registry(root / REGISTRY, root)
    except ValueError as exc:
        raise CheckError(str(exc)) from exc
    check_links(root, documents)
    check_product(documents[root / PRODUCT])
    check_trace(documents[root / TRACE])
    check_baseline(root)
    check_adrs(root, documents)
    return len(documents)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        count = check_repository(args.root)
    except (CheckError, OSError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"PASS: {count} Markdown files; local links/anchors, 45 definitions/trace rows, baseline SHA, 7 Proposed ADRs, engineering registry")
    return 0


if __name__ == "__main__":
    sys.exit(main())
