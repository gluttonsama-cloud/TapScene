#!/usr/bin/env python3
"""Inspect actual merged manifests; source overlays alone do not prove variant isolation."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path("android/app/build/intermediates/merged_manifests")
ns = "{http://schemas.android.com/apk/res/android}"
for variant, package, network in [
    ("debug", "com.tapscene.preview.hevc", False),
    ("hostedDebug", "com.tapscene.hosted.dev", True),
]:
    candidates = list(root.glob(f"{variant}/**/AndroidManifest.xml"))
    assert len(candidates) == 1, candidates
    manifest = ET.parse(candidates[0]).getroot()
    assert manifest.get("package") == package
    permissions = {p.get(ns + "name") for p in manifest.findall("uses-permission")}
    assert ("android.permission.INTERNET" in permissions) is network, permissions
    assert "android.permission.ACCESS_NETWORK_STATE" not in permissions
    app = manifest.find("application")
    assert app is not None and app.get(ns + "allowBackup") == "false"
    assert app.get(ns + "usesCleartextTraffic") == "false"
    assert (app.get(ns + "networkSecurityConfig") is not None) is network
policy = ET.parse("android/app/src/hostedDebug/res/xml/hosted_network_security.xml").getroot()
assert policy.find("base-config").get("cleartextTrafficPermitted") == "false"
configs = policy.findall("domain-config")
assert len(configs) == 1 and configs[0].get("cleartextTrafficPermitted") == "true"
domains = configs[0].findall("domain")
assert len(domains) == 1 and domains[0].text == "127.0.0.1" and domains[0].get("includeSubdomains") == "false"
print("HOST_HOSTED_MANIFEST actual offline debug + separate hostedDebug loopback policy PASS; Android OS enforcement NOT_RUN")
