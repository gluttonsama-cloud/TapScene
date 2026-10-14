#!/usr/bin/env python3
"""Check the actual co-installable product APK, separately from its signing certificate."""
import argparse
from pathlib import Path
import re
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--require-unsigned", action="store_true", help="reject both JAR and APK signing structures in CI input")
    args = parser.parse_args()
    badging = subprocess.check_output([args.aapt, "dump", "badging", str(args.apk)], text=True)
    manifest = subprocess.check_output([args.aapt, "dump", "xmltree", str(args.apk), "AndroidManifest.xml"], text=True)
    assert re.search(r"^package: name='com\.tapscene\.preview\.r33' versionCode='33' versionName='0\.28\.0-preview-integration-dev-preview'", badging, re.M)
    assert "application-label:'点演预览33'" in badging
    assert "native-code: 'arm64-v8a'" in badging
    assert "sdkVersion:'26'" in badging and "targetSdkVersion:'36'" in badging
    permissions = set(re.findall(r"uses-permission: name='([^']+)'", badging))
    assert permissions == {
        "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "android.permission.POST_NOTIFICATIONS", "com.tapscene.preview.r33.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        # Existing pinned Media3 ExoPlayer manifest; no new preview permission.
        "android.permission.WAKE_LOCK",
    }, permissions
    assert '"com.tapscene.preview.r33.offline-share"' in manifest
    assert not any(f'"{old}.offline-share"' in manifest for old in
                   ("com.tapscene", "com.tapscene.preview", "com.tapscene.preview.hevc", "com.tapscene.hosted.dev"))
    for component in ("com.tapscene.MainActivity", "com.tapscene.recording.RecordingService",
                      "com.tapscene.clickplan.ClickAccessibilityService", "com.tapscene.sharing.OfflineShareProvider"):
        assert f'"{component}"' in manifest, component
    assert "instrumentation" not in manifest and "runtime-target" not in manifest
    assert re.search(r"android:allowBackup\([^)]*\)=\(type 0x12\)0x0\b", manifest)
    assert "android.permission.INTERNET" not in manifest
    with zipfile.ZipFile(args.apk) as archive:
        assert archive.testzip() is None
        names = archive.namelist()
        if args.require_unsigned:
            assert not any(re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", name, re.I) for name in names)
            with args.apk.open("rb") as raw:
                raw.seek(archive.start_dir - 16)
                assert raw.read(16) != b"APK Sig Block 42", "CI input contains an APK signing block"
        assert {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")} == {"arm64-v8a"}
        assert "lib/arm64-v8a/libtapscene_ocr.so" in names and "lib/arm64-v8a/libonnxruntime.so" in names
    print("PREVIEW_APK_IDENTITY_OK package=com.tapscene.preview.r33 versionCode=33 arm64/offline/product components; installation NOT_RUN")


if __name__ == "__main__":
    main()
