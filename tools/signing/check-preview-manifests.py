#!/usr/bin/env python3
"""Check co-install identity and unchanged production identity in actual merged manifests."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path("android/app/build/intermediates/merged_manifests")
ns = "{http://schemas.android.com/apk/res/android}"
for variant, package in (("preview", "com.tapscene.preview.r33"), ("release", "com.tapscene")):
    candidates = list(root.glob(f"{variant}/**/AndroidManifest.xml"))
    assert len(candidates) == 1, candidates
    manifest = ET.parse(candidates[0]).getroot()
    assert manifest.get("package") == package
    assert manifest.get(ns + "sharedUserId") is None
    assert not manifest.findall("instrumentation")
    app = manifest.find("application")
    assert app is not None and app.get(ns + "allowBackup") == "false"
    assert app.get(ns + "usesCleartextTraffic") == "false"
    assert app.get(ns + "networkSecurityConfig") is None
    permissions = {p.get(ns + "name") for p in manifest.findall("uses-permission")}
    assert not permissions & {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}
    providers = [p for p in app.findall("provider") if p.get(ns + "name") == "com.tapscene.sharing.OfflineShareProvider"]
    assert len(providers) == 1 and providers[0].get(ns + "authorities") == package + ".offline-share"
    assert providers[0].get(ns + "exported") == "false" and providers[0].get(ns + "grantUriPermissions") == "true"
    recording = [p for p in app.findall("service") if p.get(ns + "name") == "com.tapscene.recording.RecordingService"]
    assert len(recording) == 1 and recording[0].get(ns + "exported") == "false"
    assert recording[0].get(ns + "foregroundServiceType") == "mediaProjection"
    clicks = [p for p in app.findall("service") if p.get(ns + "name") == "com.tapscene.clickplan.ClickAccessibilityService"]
    assert len(clicks) == 1 and clicks[0].get(ns + "permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE"
    if variant == "preview":
        assert app.get(ns + "label") == "点演预览33"
print("PREVIEW_MANIFEST_ISOLATION_OK: distinct preview and unchanged production applicationId/provider; offline product services")
