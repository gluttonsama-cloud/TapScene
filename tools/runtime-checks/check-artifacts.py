#!/usr/bin/env python3
"""Require persisted real-run evidence, not merely an instrumentation exit code."""
import json
from pathlib import Path, PurePosixPath
import sys
import tarfile

REQUIRED_ASSERTIONS = [
    "isolated_synthetic_preflight",
    "production_oes_egl_codec_single_buffer_static_probe",
    "service_connected_through_settings_ui",
    "visible_locator_pixels_at_configured_point",
    "fresh_system_capture_consent_and_overlay_detachment",
    "actual_target_down_up_coordinates_order_and_waits",
    "production_video_seal_duration_gate_and_registration",
    "every_sample_decoded_and_checked_for_locator_ring_with_static_target_intervals_preserved",
    "registered_source_real_container_ordinals_and_chain_route",
]


def check(path):
    with tarfile.open(path, "r:") as archive:
        members = archive.getmembers()
        names = [member.name for member in members]
        if len(names) != len(set(names)) or len(names) > 100:
            raise ValueError("Duplicate or excessive evidence entries")
        total = 0
        for member in members:
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts or name.parts[:2] != ("files", "runtime-smoke"):
                raise ValueError("Evidence entry outside the run directory")
            if not (member.isfile() or member.isdir()):
                raise ValueError("Evidence links/devices are forbidden")
            total += member.size
        if total > 200 * 1024 * 1024:
            raise ValueError("Evidence exceeds this small run's bound")
        for filename in ("result.json", "probe.mp4", "capture.mp4", "events.json", "chain.json", "anchors.json", "overlay-before.png"):
            member = archive.getmember("files/runtime-smoke/" + filename)
            if not member.isfile() or member.size <= 0:
                raise ValueError("Missing real-run artifact: " + filename)
        result = archive.getmember("files/runtime-smoke/result.json")
        if result.size > 4 * 1024 * 1024:
            raise ValueError("Unbounded result JSON")
        report = json.load(archive.extractfile(result))
        if report.get("status") != "PASS":
            raise ValueError("Persisted harness status is not PASS")
        assertions = report.get("assertions", [])
        if ([row.get("name") for row in assertions] != REQUIRED_ASSERTIONS or
                any(row.get("status") != "PASS" for row in assertions)):
            raise ValueError("Persisted assertions are missing or failed")
        # No archive extraction. Preserve the exact original MP4/JSON/PNG evidence bytes.
        print(json.dumps({"status": "PASS", "artifactCount": len(names), "bytes": total,
                          "assertions": [row["name"] for row in assertions]}, sort_keys=True))


if __name__ == "__main__":
    check(Path(sys.argv[1]))
