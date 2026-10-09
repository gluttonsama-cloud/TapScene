#!/usr/bin/env python3
"""Aggregate actual GitHub needs results; missing or unsuccessful jobs fail closed."""
from __future__ import annotations

import argparse
import json
import os
import sys

REQUIRED_JOBS = {"docs-checks"}


def failures(needs: object) -> list[str]:
    errors = []
    if not isinstance(needs, dict) or set(needs) != REQUIRED_JOBS:
        errors.append("Expected exactly the required job results: docs-checks; missing/unexpected jobs fail closed")
    if isinstance(needs, dict):
        for name in sorted(REQUIRED_JOBS):
            job = needs.get(name)
            result = job.get("result") if isinstance(job, dict) else None
            if result != "success":
                errors.append(f"Required job {name}: {result!r}; only actual success is accepted")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--needs-json", default=os.environ.get("NEEDS_JSON"))
    args = parser.parse_args()
    try:
        if args.needs_json is None:
            raise ValueError("NEEDS_JSON is missing")
        errors = failures(json.loads(args.needs_json))
    except ValueError as exc:
        errors = [f"Cannot load results: {exc}"]
    for error in errors:
        print(f"FAIL: {error}", file=sys.stderr)
    if errors:
        return 1
    print("PASS: required docs-checks actually succeeded")
    return 0


if __name__ == "__main__":
    sys.exit(main())
