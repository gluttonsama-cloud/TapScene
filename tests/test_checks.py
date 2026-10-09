#!/usr/bin/env python3
"""Synthetic, offline checker tests. These are not product acceptance tests."""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from requirements import load_registry


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


docs = load("check_docs")
gate = load("check_gate")


class DocsChecksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.product = self.root / docs.PRODUCT
        self.trace = self.root / docs.TRACE
        self.write("README.md", "# Fixture\n\n[spec](docs/product/product-spec-v0.1.md#f01-feature-1)\n")
        product = "# Fixture product\n\n## Features\n\n"
        product += "\n".join(f"### F{i:02} Feature {i}\n\nSynthetic criterion.\n" for i in range(1, 30))
        product += "\n## Acceptance\n\n| ID | Criterion |\n| --- | --- |\n"
        product += "".join(f"| A{i:02} Case {i} | Synthetic acceptance. |\n" for i in range(1, 17))
        self.write(docs.PRODUCT, product)
        trace = "# Fixture trace\n\n| " + " | ".join(docs.TRACE_HEADERS) + " |\n"
        trace += "| " + " | ".join(["---"] * len(docs.TRACE_HEADERS)) + " |\n"
        trace += "".join("| " + " | ".join([identifier] + ["未运行；合成测试"] * 10) + " |\n" for identifier in sorted(docs.EXPECTED))
        self.write(docs.TRACE, trace)
        for number in range(1, 8):
            self.write(f"docs/adr/{number:03}-decision.md", f"# ADR {number:03}\n\n状态：Proposed。日期：2026-10-09。\n")
        self.write("docs/engineering/engineering-standard.md", "# Fixture standard\n")
        self.registry = self.root / "docs/engineering/requirements.json"
        self.registry_entry = {"id": "ENG-001", "title": "Fixture governance", "source": "docs/engineering/engineering-standard.md", "status": "Proposed"}
        self.write(self.registry, json.dumps({"requirements": [self.registry_entry]}) + "\n")
        self.refresh_manifest()

    def write(self, path, text):
        path = self.root / path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def refresh_manifest(self):
        self.write(docs.MANIFEST, json.dumps({"path": docs.PRODUCT.as_posix(), "sha256": hashlib.sha256(self.product.read_bytes()).hexdigest()}) + "\n")

    def reject(self, pattern):
        with self.assertRaisesRegex(docs.CheckError, pattern):
            docs.check_repository(self.root)

    def test_complete_repository(self):
        self.assertEqual(docs.check_repository(self.root), 11)

    def test_empty_repository(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(docs.CheckError, "no Markdown"):
                docs.check_repository(Path(root))

    def test_missing_product(self):
        self.product.unlink()
        self.reject("missing required document")

    def test_bad_utf8(self):
        (self.root / "README.md").write_bytes(b"# Title\n\xff\n")
        self.reject("strict UTF-8")

    def test_invalid_text_formats(self):
        for content in ("\ufeff# Title\n", "# Title\n\x00\n", "# Title\r\n", "# Title", "\n"):
            with self.subTest(content=repr(content)):
                self.write("README.md", content)
                self.reject("BOM|newline")

    def test_unclosed_fence(self):
        self.write("README.md", "# Title\n\n```text\nunfinished\n")
        self.reject("unclosed fenced")

    def test_unclosed_inline_code(self):
        self.write("README.md", "# Title\n\n`unfinished\n")
        self.reject("unclosed inline")

    def test_code_examples_do_not_create_links(self):
        self.write("README.md", "# Title\n\n`[not a link](absent.md)`\n\n```md\n[not a link](absent.md)\n```\n")
        self.assertEqual(docs.check_repository(self.root), 11)

    def test_heading_structure(self):
        for content in ("# One\n# Two\n", "# One\n### Skipped\n", "## No title\n", "#\n"):
            with self.subTest(content=content):
                self.write("README.md", content)
                self.reject("H1|heading")

    def test_table_structure(self):
        for content in ("| a | b |\n| data | value |\n", "| a | b |\n| --- | --- |\n| missing |\n", "| a | b\n"):
            with self.subTest(content=content):
                self.write("README.md", "# Title\n\n" + content)
                self.reject("table")

    def test_setext_headings_are_rejected(self):
        for underline in ("===", "---"):
            self.write("README.md", f"# Title\n\nUnsupported heading\n{underline}\n")
            self.reject("Setext headings unsupported")

    def test_uppercase_markdown_is_checked(self):
        self.write("extra.MD", "# Extra\n\n[broken](missing.md)\n")
        self.reject("broken/outside-repository")

    def test_broken_file_link(self):
        self.write("README.md", "# Title\n\n[missing](absent.md)\n")
        self.reject("broken/outside-repository")

    def test_broken_anchor(self):
        self.write("README.md", "# Title\n\n[missing](docs/product/product-spec-v0.1.md#absent)\n")
        self.reject("broken or unsupported anchor")

    def test_anchor_unicode_duplicates_percent_encoding(self):
        self.write("README.md", "# Title\n\n## 中文标题\n\n## 中文标题\n\n[one](#%E4%B8%AD%E6%96%87%E6%A0%87%E9%A2%98) [two](#中文标题-1)\n")
        self.assertEqual(docs.check_repository(self.root), 11)

    def test_path_traversal(self):
        self.write("README.md", "# Title\n\n[escape](../../etc/passwd)\n")
        self.reject("broken/outside-repository")

    def test_directory_requires_readme(self):
        (self.root / "empty").mkdir()
        self.write("README.md", "# Title\n\n[empty](empty/)\n")
        self.reject("directory link requires")

    def test_symlink_document(self):
        (self.root / "linked.md").symlink_to(self.root / "README.md")
        self.reject("symlinks")

    def test_unsupported_links_fail_clearly(self):
        for value in ("[link][ref]\n\n[ref]: absent.md", "[shortcut]", '[title](absent.md "Title")', "[broken](absent.md", '<a href="absent.md">link</a>', "[nested](a(b).md)"):
            with self.subTest(value=value):
                self.write("README.md", f"# Title\n\n{value}\n")
                self.reject("unsupported/malformed|raw HTML")

    def test_unsupported_schemes(self):
        for target in ("javascript:evil", "file:///tmp/file", "//example.com/file", "http:/missing-host", "/absolute/path", "README.md?ignored=true"):
            with self.subTest(target=target):
                self.write("README.md", f"# Title\n\n[link]({target})\n")
                self.reject("scheme|relative paths")

    def test_tasks_and_explicit_escaped_literals(self):
        self.write("README.md", "# Title\n\n- [ ] unchecked\n- [x] checked\n\nInterval \\[0,1\\].\n")
        self.assertEqual(docs.check_repository(self.root), 11)

    def test_feature_missing_duplicate_out_of_range(self):
        original = self.product.read_text()
        for mutated in (original.replace("### F29 Feature 29", "### Removed Feature"), original + "\n### F01 Duplicate\n", original.replace("### F29 Feature 29", "### F30 Wrong Feature")):
            with self.subTest(mutated=mutated[-100:]):
                self.product.write_text(mutated)
                self.reject("invalid IDs")

    def test_acceptance_missing_duplicate_out_of_range(self):
        original = self.product.read_text()
        for mutated in (original.replace("| A16 Case 16 | Synthetic acceptance. |\n", ""), original + "| A01 Duplicate | Criterion |\n", original.replace("| A16 Case 16", "| A17 Wrong Case")):
            with self.subTest(mutated=mutated[-100:]):
                self.product.write_text(mutated)
                self.reject("invalid IDs")

    def test_trace_missing_duplicate_out_of_range(self):
        original = self.trace.read_text()
        row = next(line for line in original.splitlines(True) if line.startswith("| F29 |"))
        for mutated in (original.replace(row, ""), original + row, original.replace("| F29 |", "| F30 |")):
            with self.subTest(mutated=mutated[-100:]):
                self.trace.write_text(mutated)
                self.reject("invalid IDs")

    def test_trace_empty_or_placeholder_fields(self):
        original = self.trace.read_text()
        for value in ("", "-", "TBD", "TODO"):
            with self.subTest(value=value):
                self.trace.write_text(original.replace("| F01 | 未运行；合成测试 |", f"| F01 | {value} |"))
                self.reject("all trace fields")

    def test_trace_out_of_range_reference(self):
        self.trace.write_text(self.trace.read_text().replace("| F01 | 未运行；合成测试 |", "| F01 | Depends on F99 |"))
        self.reject("out-of-range/malformed requirement references")

    def test_trace_bad_headers(self):
        self.trace.write_text(self.trace.read_text().replace("| 来源与理由 |", "| Wrong |"))
        self.reject("headers")

    def test_registry_invalid_definitions(self):
        valid = self.registry_entry
        for value in ({}, {"requirements": []}, {"requirements": [valid, valid]}, {"requirements": [{**valid, "id": "ENG-1"}]}, {"requirements": [{**valid, "title": ""}]}, {"requirements": [{**valid, "status": ""}]}, {"requirements": [{**valid, "source": "absent.md"}]}, {"requirements": [{**valid, "source": "/etc/passwd"}]}, {"requirements": [{**valid, "source": "../../../etc/passwd"}]}):
            with self.subTest(value=value):
                self.registry.write_text(json.dumps(value))
                self.reject("invalid requirement registry")

    def test_registry_missing_or_duplicate_json_key(self):
        self.registry.write_text('{"requirements": [], "requirements": []}')
        self.reject("duplicate JSON key")
        self.registry.unlink()
        self.reject("invalid requirement registry")

    def test_baseline_tamper(self):
        self.product.write_text(self.product.read_text() + "\nChanged content.\n")
        self.reject("SHA-256 differs")

    def test_manifest_missing_invalid(self):
        self.write(docs.MANIFEST, '{}\n')
        self.reject("invalid baseline manifest")
        (self.root / docs.MANIFEST).unlink()
        self.reject("cannot read")

    def test_adr_status_changed_or_missing(self):
        path = self.root / "docs/adr/001-decision.md"
        original = path.read_text()
        for mutated in (original.replace("Proposed", "Accepted"), "# ADR 001\n", original + "\n状态：Proposed。\n"):
            with self.subTest(mutated=mutated):
                path.write_text(mutated)
                self.reject("status must be Proposed")

    def test_adr_missing_or_extra(self):
        path = self.root / "docs/adr/007-decision.md"
        original = path.read_text()
        path.unlink()
        self.reject("exactly 001-decision")
        path.write_text(original)
        self.write("docs/adr/008-decision.md", original)
        self.reject("exactly 001-decision")


class GateChecksTest(unittest.TestCase):
    def test_negative_and_positive_fixtures(self):
        cases = json.loads((ROOT / "tests/fixtures/gate_cases.json").read_text())
        self.assertGreaterEqual(len(cases), 16)
        self.assertEqual(len({case["name"] for case in cases}), len(cases))
        for case in cases:
            with self.subTest(case=case["name"]):
                self.assertEqual(not gate.failures(case["event"], case["needs"], {"ENG-001"}), case["pass"])

    def test_cli_fixture_exit_codes(self):
        cases = json.loads((ROOT / "tests/fixtures/gate_cases.json").read_text())
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "event.json"
            registry = Path(root) / "registry.json"
            registry.write_text(json.dumps({"requirements": [{"id": "ENG-001", "title": "Synthetic fixture", "source": "scripts/check_gate.py", "status": "Proposed"}]}))
            for case in cases:
                with self.subTest(case=case["name"]):
                    path.write_text(json.dumps(case["event"]))
                    result = subprocess.run([sys.executable, str(ROOT / "scripts/check_gate.py"), "--event", str(path), "--registry", str(registry), "--needs-json", json.dumps(case["needs"])], capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0 if case["pass"] else 1, result.stdout + result.stderr)
                    self.assertIn("PASS:" if case["pass"] else "FAIL:", result.stdout + result.stderr)

    def test_malformed_event_and_needs(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "event.json"
            registry = Path(root) / "registry.json"
            registry.write_text(json.dumps({"requirements": [{"id": "ENG-001", "title": "Synthetic fixture", "source": "scripts/check_gate.py", "status": "Proposed"}]}))
            for text, needs in (("{bad", "{}"), ("{}", "{bad")):
                path.write_text(text)
                result = subprocess.run([sys.executable, str(ROOT / "scripts/check_gate.py"), "--event", str(path), "--registry", str(registry), "--needs-json", needs], capture_output=True, text=True)
                self.assertEqual(result.returncode, 1)
                self.assertIn("Cannot load event/results", result.stderr)

    def test_cli_missing_duplicate_registry_fail_closed(self):
        with tempfile.TemporaryDirectory() as root:
            event = Path(root) / "event.json"
            event.write_text(json.dumps({"pull_request": {"draft": False, "title": "ENG-001", "body": "ENG-001"}}))
            registry = Path(root) / "registry.json"
            command = [sys.executable, str(ROOT / "scripts/check_gate.py"), "--event", str(event), "--registry", str(registry), "--needs-json", '{"docs-checks":{"result":"success"}}']
            missing = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(missing.returncode, 1)
            self.assertIn("invalid requirement registry", missing.stderr)
            entry = {"id": "ENG-001", "title": "Fixture", "source": "scripts/check_gate.py", "status": "Proposed"}
            registry.write_text(json.dumps({"requirements": [entry, entry]}))
            duplicate = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(duplicate.returncode, 1)
            self.assertIn("duplicate registry ID", duplicate.stderr)

    def test_false_like_draft_is_rejected(self):
        for draft in (0, "false", None, True):
            event = {"pull_request": {"draft": draft, "title": "F01", "body": "A01"}}
            self.assertTrue(gate.failures(event, {"docs-checks": {"result": "success"}}, {"ENG-001"}))

    def test_ids_need_real_boundaries_and_padding(self):
        for invalid in ("F1", "F001", "A00", "XF01", "F01suffix", "A17"):
            event = {"pull_request": {"draft": False, "title": invalid, "body": "A01"}}
            self.assertTrue(gate.failures(event, {"docs-checks": {"result": "success"}}, {"ENG-001"}))


class WorkflowChecksTest(unittest.TestCase):
    def test_security_and_gate_wiring_contract(self):
        # This narrow structural contract checks our own simple YAML, not arbitrary YAML.
        text = (ROOT / ".github/workflows/docs.yml").read_text()
        for required in ("  pull_request:\n", "permissions:\n  contents: read\n", "  cancel-in-progress: true\n", "    needs: [docs-checks]\n    if: ${{ always() }}\n", "    name: ci/gate\n", 'run: python3 scripts/check_gate.py --event "$GITHUB_EVENT_PATH"', "NEEDS_JSON: ${{ toJSON(needs) }}", "run: python3 tests/test_checks.py", "run: python3 scripts/check_docs.py"):
            self.assertIn(required, text)
        for forbidden in ("pull_request_target", "continue-on-error", "secrets.", "paths:", "paths-ignore:", "contents: write", "workflow_dispatch", "push:"):
            self.assertNotIn(forbidden, text)
        self.assertEqual(text.count("uses:"), 2)
        self.assertEqual(text.count("uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683"), 2)
        self.assertEqual(text.count("persist-credentials: false"), 2)
        self.assertEqual(text.count("timeout-minutes: 5"), 2)
        self.assertEqual(text.count("runs-on: ubuntu-24.04"), 2)


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    if suite.countTestCases() == 0:
        sys.exit("FAIL: empty test suite")
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
