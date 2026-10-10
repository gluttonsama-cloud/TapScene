#!/usr/bin/env python3
"""Check the official packages used by the repository's successful TCG boot.

This reads metadata only; installation remains sdkmanager's job. It never accepts
licenses, writes license hashes, or downloads an executable itself. A changed
stable package fails before installation rather than silently upgrading the test.
"""
import json
import os
from pathlib import Path
import sys
import urllib.request
import xml.etree.ElementTree as ET

PACKAGES = (
    {
        "package": "emulator", "revision": "37.2.12",
        "feed": "https://dl.google.com/android/repository/repository2-3.xml",
        "archive": "emulator-linux_x64-16428233.zip", "bytes": 349654171,
        "sha1": "cd7362ea55dfb86a418958138dc396e74165dd01",
        "properties": "emulator/source.properties",
    },
    {
        "package": "system-images;android-35;default;x86_64", "revision": "2",
        "feed": "https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml",
        "archive": "x86_64-35_r02.zip", "bytes": 782404023,
        "sha1": "2d857d170c0d1b827149565da34b3383e5306f7f",
        "properties": "system-images/android-35/default/x86_64/source.properties",
    },
)


def remote():
    for lock in PACKAGES:
        with urllib.request.urlopen(lock["feed"], timeout=60) as response:
            if response.url != lock["feed"]:
                raise RuntimeError("Unexpected SDK metadata redirect")
            root = ET.fromstring(response.read(8 * 1024 * 1024))
        packages = [p for p in root.findall("remotePackage")
                    if p.get("path") == lock["package"]
                    and p.find("channelRef").get("ref") == "channel-0"]
        if len(packages) != 1:
            raise RuntimeError("Expected one stable package: " + lock["package"])
        package = packages[0]
        revision = ".".join(package.find("revision/" + key).text
                            for key in ("major", "minor", "micro")
                            if package.find("revision/" + key) is not None)
        if revision != lock["revision"] or package.find("uses-license").get("ref") != "android-sdk-license":
            raise RuntimeError("SDK version/license identity changed; review required: " + lock["package"])
        archives = [a for a in package.findall("archives/archive")
                    if a.findtext("host-os") in (None, "linux")]
        matching = [a.find("complete") for a in archives
                    if a.findtext("complete/url") == lock["archive"]]
        if len(matching) != 1:
            raise RuntimeError("SDK archive changed: " + lock["package"])
        archive = matching[0]
        if int(archive.findtext("size")) != lock["bytes"] or archive.findtext("checksum") != lock["sha1"]:
            raise RuntimeError("SDK archive digest changed: " + lock["package"])
        print(json.dumps(lock, sort_keys=True))


def installed():
    sdk = Path(os.environ["ANDROID_HOME"])
    for lock in PACKAGES:
        path = sdk / lock["properties"]
        properties = dict(line.split("=", 1) for line in path.read_text().splitlines()
                          if "=" in line and not line.lstrip().startswith("#"))
        properties = {key.strip(): value.strip() for key, value in properties.items()}
        if properties.get("Pkg.Revision") != lock["revision"]:
            raise RuntimeError("Installed SDK revision mismatch: " + str(path))
        print(json.dumps({"package": lock["package"], "properties": properties}, sort_keys=True))


if __name__ == "__main__":
    if sys.argv[1:] == ["remote"]:
        remote()
    elif sys.argv[1:] == ["installed"]:
        installed()
    else:
        raise SystemExit("usage: sdk-lock.py remote|installed")
