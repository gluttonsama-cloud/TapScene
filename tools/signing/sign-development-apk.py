#!/usr/bin/env python3
"""Re-sign an existing APK with an explicitly supplied, existing development key."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


EXPECTED_CERT_SHA256 = "0929d62a4336760a8c0395cea92c6e9b272c897f02b126f84f5f0a4e0a4fefc2"
REPOSITORY = Path(__file__).resolve().parents[2]
CERT_DIGEST = re.compile(
    r"^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$", re.MULTILINE
)


class SigningError(Exception):
    """A signing prerequisite or required verification failed."""


def outside_repository(path):
    if path == REPOSITORY or REPOSITORY in path.parents:
        raise SigningError("signing directory and its credential files must be outside the repository")


def apksigner_call(executable, arguments, stage):
    result = subprocess.run(
        [executable, *arguments], stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    if result.returncode:
        # Never replay signing-tool output or a command containing credential
        # locations. Passwords are read only by apksigner from the existing file.
        raise SigningError(f"apksigner {stage} failed (exit {result.returncode}); no APK published")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(
        description=__doc__,
        epilog="Development certificate only. First use cannot replace an old APK with a different certificate; do not uninstall it automatically.",
    )
    parser.add_argument("input_apk", type=Path, help="existing CI APK; never modified")
    parser.add_argument("output_apk", type=Path, help="new output APK; must not already exist")
    parser.add_argument(
        "--signing-dir", type=Path, required=True,
        help="explicit repository-external directory containing existing development.p12 and store.pass",
    )
    args = parser.parse_args()
    try:
        input_apk = args.input_apk.resolve(strict=True)
        if not input_apk.is_file():
            raise SigningError("input APK must be a regular file")
        output_apk = args.output_apk.parent.resolve(strict=True) / args.output_apk.name
        if output_apk == input_apk:
            raise SigningError("input and output must have different paths")
        if os.path.lexists(output_apk):
            raise SigningError("output already exists; choose a new output path")
        signing_dir = args.signing_dir.resolve(strict=True)
        outside_repository(signing_dir)
        if not signing_dir.is_dir():
            raise SigningError("signing directory does not exist")
        key = (signing_dir / "development.p12").resolve(strict=True)
        password_file = (signing_dir / "store.pass").resolve(strict=True)
        for credential in (key, password_file):
            outside_repository(credential)
            if not credential.is_file():
                raise SigningError("both existing signing credential files must be regular files")
        executable = os.environ.get("APKSIGNER", "apksigner")
        if not executable:
            raise SigningError("APKSIGNER must name the trusted Android SDK executable")
        verifier = Path(__file__).with_name("verify-resigned-apk.py").resolve(strict=True)
        with tempfile.TemporaryDirectory(prefix=".tapscene-sign-", dir=output_apk.parent) as directory:
            candidate = Path(directory) / "candidate.apk"
            apksigner_call(executable, [
                "sign", "--ks", str(key), "--ks-type", "PKCS12",
                # PKCS12 uses the same password for store and key. Repeating the
                # same file for --key-pass would consume a second password line.
                "--ks-pass", f"file:{password_file}",
                "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                "--v4-signing-enabled", "false", "--lib-page-alignment", "16384",
                "--out", str(candidate), str(input_apk),
            ], "sign")
            verification = apksigner_call(
                executable, ["verify", "--verbose", "--print-certs", str(candidate)], "verify"
            )
            digests = [value.replace(":", "").lower() for value in CERT_DIGEST.findall(verification)]
            if digests != [EXPECTED_CERT_SHA256]:
                raise SigningError("APK must have exactly the approved development certificate SHA-256; no APK published")
            result = subprocess.run(
                [sys.executable, str(verifier), str(input_apk), str(candidate)],
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            )
            if result.returncode:
                raise SigningError(f"payload/alignment verification failed; no APK published\n{result.stderr.strip()}")
            # Same-filesystem hard-link creation is atomic and refuses an existing
            # destination, including a symlink created after the earlier check.
            os.link(candidate, output_apk)
        print(result.stdout, end="")
        print(f"PASS: apksigner verification and approved development certificate SHA-256: {EXPECTED_CERT_SHA256}")
        print(f"Created: {output_apk}")
        print("NOT CHECKED: Android installation/runtime. A different old signing certificate prevents an in-place first update.")
    except (SigningError, OSError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
