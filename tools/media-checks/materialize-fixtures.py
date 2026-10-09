"""Restore synthetic MP4 fixtures and check their manifest; no input bitstream policy."""
import argparse
import base64
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("destination", type=Path)
parser.add_argument("--verify-media", action="store_true", help="also run installed FFprobe and FFmpeg")
args = parser.parse_args()
source = Path(__file__).parent / "fixtures"
manifest = json.loads((source / "manifest.json").read_text())
assert isinstance(manifest, dict) and manifest, "Empty fixture manifest"
restored = {}
for name, expected in manifest.items():
    assert Path(name).name == name and name.endswith(".mp4"), name
    assert re.fullmatch(r"[0-9a-f]{64}", expected["sha256"]), name
    assert 0 < expected["bytes"] <= 128 * 1024, name
    streams = expected["streams"]
    assert streams and streams[0]["codec_name"] in ("h264", "hevc"), name
    assert all(stream["codec_name"] == "aac" for stream in streams[1:]), name
    pts = expected["pts_us"]
    assert pts and all(type(time) is int for time in pts), name
    assert pts == sorted(set(pts)) and pts[0] == 0, name
    assert pts[-1] < expected["duration_us"] <= 1_000_000, name
    assert int(streams[0]["nb_frames"]) == len(pts), name
    assert expected["android_rotation_degrees"] in (0, 90, 180, 270), name
    width, height = expected["surface_size"]
    assert 0 < min(width, height) <= 1080 and max(width, height) <= 2400, name
    assert expected["expectation"] in ("supported-sdr", "advertised-main10", "device-tone-map"), name
    data = base64.b64decode(b"".join((source / (name + ".b64")).read_bytes().split()), validate=True)
    assert len(data) == expected["bytes"], name
    assert hashlib.sha256(data).hexdigest() == expected["sha256"], name
    restored[name] = data
assert {path.name.removesuffix(".b64") for path in source.glob("*.b64")} == set(manifest)

args.destination.mkdir(parents=True, exist_ok=True)
assets = Path(__file__).resolve().parents[2] / "android/app/src/androidTest/assets"
assets.mkdir(parents=True, exist_ok=True)
for name, data in restored.items():
    target = args.destination / name
    target.write_bytes(data)
    expected = manifest[name]
    if args.verify_media:
        result = json.loads(subprocess.check_output([
            "ffprobe", "-v", "error", "-show_streams", "-of", "json", str(target),
        ]))
        assert len(result["streams"]) == len(expected["streams"]), name
        for actual, stream in zip(result["streams"], expected["streams"]):
            for key, value in stream.items():
                if key == "side_data_list":
                    assert [item.get("rotation") for item in actual.get(key, []) if "rotation" in item] == [
                        item["rotation"] for item in value
                    ], name
                else:
                    assert actual.get(key) == value, (name, key, actual.get(key), value)
        frames = json.loads(subprocess.check_output([
            "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time",
            "-of", "json", str(target),
        ]))["frames"]
        actual_pts = [int(Decimal(frame["pts_time"]) * 1_000_000) for frame in frames]
        assert actual_pts == expected["pts_us"], (name, actual_pts)
        subprocess.run([
            "ffmpeg", "-v", "error", "-xerror", "-i", str(target), "-map", "0:v:0", "-f", "null", "-",
        ], check=True)
    shutil.copyfile(target, assets / name)
    print(f"{name}: manifest and SHA-256 verified ({len(data)} bytes)" +
          ("; FFprobe PTS and full FFmpeg decode verified" if args.verify_media else ""))
print("TAPSCENE_FIXTURE_CHECKS_OK")
