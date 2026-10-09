#!/usr/bin/env python3
"""Validated engineering/bug requirement registry shared by offline CI checks."""
from __future__ import annotations

import json
from pathlib import Path
import re

REGISTRY = Path("docs/engineering/requirements.json")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_registry(path: Path, root: Path) -> set[str]:
    """Read IDs only after validating all fields, uniqueness and source files."""
    root = root.resolve()
    try:
        if path.is_symlink():
            raise ValueError("registry cannot be a symlink")
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
        entries = value.get("requirements") if isinstance(value, dict) else None
        if not isinstance(entries, list) or not entries:
            raise ValueError("requirements must be a nonempty array")
        ids = set()
        for entry in entries:
            if not isinstance(entry, dict) or any(not isinstance(entry.get(key), str) or not entry[key].strip() for key in ("id", "title", "source", "status")):
                raise ValueError("each requirement needs nonempty id, title, source and status")
            identifier = entry["id"]
            if not re.fullmatch(r"(?:ENG|BUG)-[0-9]{3}", identifier) or identifier in ids:
                raise ValueError(f"malformed or duplicate registry ID: {identifier}")
            source = Path(entry["source"])
            destination = (root / source).resolve()
            if source.is_absolute() or not destination.is_relative_to(root) or not destination.is_file():
                raise ValueError(f"requirement source must be an existing repository file: {source}")
            ids.add(identifier)
        return ids
    except (OSError, UnicodeError, ValueError) as exc:
        raise ValueError(f"{path}: invalid requirement registry: {exc}") from exc
