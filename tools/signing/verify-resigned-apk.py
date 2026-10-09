#!/usr/bin/env python3
"""Read-only APK payload/alignment comparison; NOT a signature or device test."""

import argparse
import hashlib
from pathlib import Path
import re
import struct
import sys
import zipfile
import zlib


LOCAL_HEADER = struct.Struct("<4s5H3I2H")
JAR_SIGNATURE = re.compile(
    r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))",
    re.IGNORECASE | re.ASCII,
)
CHUNK_SIZE = 1024 * 1024


class VerificationError(Exception):
    """An APK failed a required comparison or ZIP layout check."""


def inventory(path):
    """Hash uncompressed payloads and validate both archives independently."""
    entries = {}
    signatures = []
    stored_count = 0
    native_count = 0
    with path.open("rb") as raw, zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if not infos:
            raise VerificationError(f"{path}: empty ZIP archive")
        names = set()
        for info in infos:
            name = info.filename
            if info.orig_filename != name:
                raise VerificationError(f"{path}: ambiguous ZIP filename {info.orig_filename!r}")
            if name in names:
                raise VerificationError(f"{path}: duplicate ZIP entry {name!r}")
            names.add(name)
            if info.flag_bits & 1:
                raise VerificationError(f"{path}: encrypted ZIP entry {name!r}")

            raw.seek(info.header_offset)
            header = raw.read(LOCAL_HEADER.size)
            if len(header) != LOCAL_HEADER.size:
                raise VerificationError(f"{path}: truncated local header for {name!r}")
            fields = LOCAL_HEADER.unpack(header)
            if fields[0] != b"PK\x03\x04":
                raise VerificationError(f"{path}: invalid local header for {name!r}")
            if fields[2] != info.flag_bits or fields[3] != info.compress_type:
                raise VerificationError(f"{path}: local/central ZIP flags or method differ for {name!r}")
            # Alignment is the start of file data, not the local header or its
            # central-directory extra field. Local extra lengths can differ.
            data_offset = info.header_offset + LOCAL_HEADER.size + fields[-2] + fields[-1]
            if info.compress_type == zipfile.ZIP_STORED:
                stored_count += 1
                alignment = 4
                if name.startswith("lib/") and name.endswith(".so"):
                    native_count += 1
                    alignment = 16384
                if data_offset % alignment:
                    raise VerificationError(
                        f"{path}: {name!r} data offset {data_offset} is not {alignment}-byte aligned"
                    )

            digest = hashlib.sha256()
            size = 0
            # Read even excluded signature files: malformed ZIP/CRC errors must
            # not disappear merely because an entry has a signing filename.
            with archive.open(info) as entry:
                while True:
                    chunk = entry.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    size += len(chunk)
                    digest.update(chunk)
            if size != info.file_size:
                raise VerificationError(f"{path}: inconsistent uncompressed size for {name!r}")
            if JAR_SIGNATURE.fullmatch(name):
                signatures.append(name)
            else:
                entries[name] = (size, digest.hexdigest(), info.compress_type)
    if not entries:
        raise VerificationError(f"{path}: no non-signature ZIP entries")
    return entries, signatures, stored_count, native_count


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_apk", type=Path, help="original CI APK, kept unchanged")
    parser.add_argument("output_apk", type=Path, help="APK re-signed with the fixed test key")
    args = parser.parse_args()
    try:
        if args.input_apk.samefile(args.output_apk):
            raise VerificationError("input and output must be distinct files; preserve the CI APK")
        before, old_signatures, old_stored, old_native = inventory(args.input_apk)
        after, new_signatures, new_stored, new_native = inventory(args.output_apk)
        removed = sorted(before.keys() - after.keys())
        added = sorted(after.keys() - before.keys())
        if removed or added:
            raise VerificationError(f"non-signature entry set changed: removed={removed!r}; added={added!r}")
        changed = [name for name in sorted(before) if before[name] != after[name]]
        if changed:
            raise VerificationError(f"payload SHA-256, size, or compression method changed: {changed!r}")
        print(f"PASS: {len(before)} non-signature entries have identical SHA-256, size, and compression method")
        print(f"PASS: no duplicate entries; stored entries aligned to 4 bytes (input={old_stored}, output={new_stored})")
        print(f"PASS: stored lib/*.so entries aligned to 16384 bytes (input={old_native}, output={new_native})")
        print(f"Excluded signing entries: input={old_signatures!r}; output={new_signatures!r}")
        print(f"Input APK SHA-256:  {file_sha256(args.input_apk)}")
        print(f"Output APK SHA-256: {file_sha256(args.output_apk)}")
        print("NOT CHECKED: cryptographic signatures, expected certificate, package/version, ELF alignment, or Android installation/runtime")
    except (VerificationError, OSError, ValueError, RuntimeError, zipfile.BadZipFile,
            NotImplementedError, EOFError, zlib.error) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
