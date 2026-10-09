"""Restore only generated, hash-checked fixtures; never reads user media."""
import base64
import hashlib
import json
from pathlib import Path
import shutil
import sys

source = Path(__file__).parent / "fixtures"
destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=True)
assets = Path(__file__).resolve().parents[2] / "android/app/src/androidTest/assets"
assets.mkdir(parents=True, exist_ok=True)
for name, expected in json.loads((source / "manifest.json").read_text()).items():
    assert Path(name).name == name and name.endswith((".csd", ".mp4"))
    data = base64.b64decode(b"".join((source / (name + ".b64")).read_bytes().split()), validate=True)
    assert len(data) == expected["bytes"], name
    assert hashlib.sha256(data).hexdigest() == expected["sha256"], name
    target = destination / name
    target.write_bytes(data)
    if name.endswith(".mp4"):
        shutil.copyfile(target, assets / name)
    print(f"{name}: verified {len(data)} bytes")
