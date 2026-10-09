#!/usr/bin/env python3
"""Aggregate actual GitHub needs results and PR policy; missing data fails closed."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys

from requirements import REGISTRY, load_registry

REQUIRED_JOBS = {"docs-checks"}
VALID_IDS = {f"F{i:02}" for i in range(1, 30)} | {f"A{i:02}" for i in range(1, 17)}
ID = re.compile(r"(?<![A-Za-z0-9_])([FA][0-9]+|(?:ENG|BUG)-[0-9]+)(?![A-Za-z0-9_])")


def failures(event: object, needs: object, registered_ids: set[str]) -> list[str]:
    errors = []
    valid_ids = VALID_IDS | registered_ids
    if not isinstance(needs, dict) or set(needs) != REQUIRED_JOBS:
        errors.append("Expected exactly the required job results: docs-checks; missing/unexpected jobs fail closed")
    if isinstance(needs, dict):
        for name in sorted(REQUIRED_JOBS):
            job = needs.get(name)
            result = job.get("result") if isinstance(job, dict) else None
            if result != "success":
                errors.append(f"Required job {name}: {result!r}; only actual success is accepted")
    pr = event.get("pull_request") if isinstance(event, dict) else None
    if not isinstance(pr, dict):
        errors.append("Missing pull_request event metadata")
        return errors
    if pr.get("draft") is not False:
        errors.append("PR is draft or draft status is missing; ci/gate cannot pass until ready for review")
    for field in ("title", "body"):
        value = pr.get(field)
        ids = ID.findall(value) if isinstance(value, str) else []
        if not ids or any(identifier not in valid_ids for identifier in ids):
            errors.append(f"PR {field} must contain explicit valid requirement IDs (F01–F29, A01–A16, or registered ENG/BUG IDs), without unknown IDs")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", type=Path, required=True)
    parser.add_argument("--needs-json", default=os.environ.get("NEEDS_JSON"))
    parser.add_argument("--registry", type=Path, default=Path(__file__).resolve().parents[1] / REGISTRY)
    args = parser.parse_args()
    try:
        if args.needs_json is None:
            raise ValueError("NEEDS_JSON is missing")
        registered_ids = load_registry(args.registry, Path(__file__).resolve().parents[1])
        errors = failures(json.loads(args.event.read_text(encoding="utf-8")), json.loads(args.needs_json), registered_ids)
    except (OSError, UnicodeError, ValueError) as exc:
        errors = [f"Cannot load event/results: {exc}"]
    for error in errors:
        print(f"FAIL: {error}", file=sys.stderr)
    if errors:
        return 1
    print("PASS: required docs-checks actually succeeded; PR is ready and title/body identify requirements")
    return 0


if __name__ == "__main__":
    sys.exit(main())
