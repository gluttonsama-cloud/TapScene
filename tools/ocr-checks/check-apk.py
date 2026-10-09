#!/usr/bin/env python3
"""Inspect the actual APK, without loading or executing its native libraries."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libjnigraphics.so", "libonnxruntime.so"}
FORBIDDEN_SYMBOLS = {"connect", "socket", "send", "recv", "system", "popen", "execve", "execvp"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--readelf", required=True)
    parser.add_argument("--abis", nargs="+", choices=sorted(ABIS), default=sorted(ABIS))
    args = parser.parse_args()
    packaged_abis = set(args.abis)
    lock = json.loads((ROOT / "tools/ocr-native/dependencies.lock.json").read_text())
    apk_size = args.apk.stat().st_size
    budget_mib = 80 if len(packaged_abis) == 1 else 200
    assert apk_size <= budget_mib * 1024 * 1024, f"Preview APK exceeds the {budget_mib} MiB packaging budget"
    with zipfile.ZipFile(args.apk) as archive, args.apk.open("rb") as apk, tempfile.TemporaryDirectory() as directory:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate APK entries"
        expected_models = {"assets/ocr/" + item["name"] for item in lock["models"]["files"]}
        assert not any(name.endswith(".traineddata") or "/libopencv_java" in name for name in names)
        assert {name for name in names if name.startswith("assets/ocr/") and (name.endswith(".onnx") or name.endswith("characters.txt"))} == expected_models
        for item in lock["models"]["files"]:
            data = archive.read("assets/ocr/" + item["name"])
            assert len(data) == item["bytes"] and hashlib.sha256(data).hexdigest() == item["sha256"]
        for license_file in (ROOT / "tools/ocr-native/licenses").iterdir():
            if license_file.is_file():
                assert archive.read("assets/ocr/licenses/" + license_file.name) == license_file.read_bytes()
        assert {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")} == packaged_abis
        libraries = [name for name in names if name.endswith(("/libtapscene_ocr.so", "/libonnxruntime.so"))]
        for library in ("libtapscene_ocr.so", "libonnxruntime.so"):
            assert {name.split("/")[1] for name in libraries if name.endswith("/" + library)} == packaged_abis
        assert len(libraries) == 2 * len(packaged_abis)
        for item in lock["ort"]["native"]:
            if item["path"].split("/")[1] not in packaged_abis:
                continue
            data = archive.read("lib/" + item["path"].removeprefix("jni/"))
            assert len(data) == item["bytes"] and hashlib.sha256(data).hexdigest() == item["sha256"]
        total_native = 0
        for name in sorted(libraries):
            info = archive.getinfo(name)
            assert info.compress_type == zipfile.ZIP_STORED, "Native library must support direct loading"
            apk.seek(info.header_offset)
            header = apk.read(30)
            assert header[:4] == b"PK\x03\x04"
            filename_length, extra_length = struct.unpack_from("<HH", header, 26)
            assert (info.header_offset + 30 + filename_length + extra_length) % 16384 == 0, "Native APK entry is not 16 KiB aligned"
            local = Path(directory) / (name.split("/")[1] + "-" + name.split("/")[-1])
            local.write_bytes(archive.read(name))
            total_native += info.file_size
            dynamic = subprocess.check_output([args.readelf, "--dynamic", "--wide", str(local)], text=True)
            needed = set(re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic))
            assert needed <= SYSTEM_LIBS, f"Unexpected OCR native dependency: {needed - SYSTEM_LIBS}"
            symbols = subprocess.check_output([args.readelf, "--dyn-syms", "--wide", str(local)], text=True)
            undefined = {line.split()[-1].split("@")[0] for line in symbols.splitlines()
                         if " UND " in line and line.split()}
            assert not undefined & FORBIDDEN_SYMBOLS, "OCR library must not import process or socket APIs"
            program = subprocess.check_output([args.readelf, "--program-headers", "--wide", str(local)], text=True)
            alignments = [int(line.split()[-1], 16) for line in program.splitlines() if line.strip().startswith("LOAD ")]
            assert alignments and all(value >= 16384 for value in alignments), "ELF LOAD alignment below 16 KiB"
            print(f"{name}: {info.file_size} bytes; allowed system dependencies; aligned ELF and ZIP entry")
    model_size = sum(item["bytes"] for item in lock["models"]["files"])
    print(f"OCR_APK_INSPECTION_OK abis={','.join(sorted(packaged_abis))} apk_bytes={apk_size} models_bytes={model_size} native_bytes={total_native}")
    print("Build inspection only. Native loading, memory and recognition speed still require Android devices.")


if __name__ == "__main__":
    main()
