#!/usr/bin/env python3
"""Read a saved synthetic runtime-smoke export; never create or repair an anchor.

Requires local ffprobe/ffmpeg. This checks lossy MP4 pixels, not the private same-FBO
PNGs, and cannot recover an absent acquisition ticket or encoder association.
"""
import argparse
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import subprocess


def read_json(directory, name):
    path = directory / name
    if not path.is_file() or path.stat().st_size > 256 * 1024:
        raise ValueError(f"Missing or oversized {name}")
    return json.loads(path.read_text())


def state_in_frame(frame, width, height, geometry):
    # Same invariant checkerboard and eight-bit state definition as RuntimeTargetPixels.
    # Only the pinned, full-screen synthetic fixture is supported; no guessed transform.
    def sample(x, y):
        x, y = int(x + 0.5), int(y + 0.5)
        if not (0 <= x < width and 0 <= y < height):
            raise ValueError("Fixture sample outside encoded frame")
        offset = (y * width + x) * 3
        r, g, b = frame[offset:offset + 3]
        return int(r * 0.2126 + g * 0.7152 + b * 0.0722 + 0.5)

    static = geometry["staticRegion"]
    cell = max(1, min((static["right"] - static["left"]) // 12,
                      (static["bottom"] - static["top"]) // 4))
    for row in range(4):
        for column in range(4):
            value = sample(static["left"] + (column + 0.5) * cell,
                           static["top"] + (row + 0.5) * cell)
            if (row + column) % 2 == 0 and value > 85:
                return None
            if (row + column) % 2 and value < 160:
                return None
    region = geometry["stateRegion"]
    cell = max(1, min((region["right"] - region["left"]) // 17,
                      (region["bottom"] - region["top"]) // 4))
    state = 0
    for bit in range(8):
        value = sample(region["left"] + (2 * bit + 1.5) * cell, region["centerY"])
        if value >= 175:
            state |= 1 << bit
        elif value > 85:
            return None
    return state


def inspect(directory):
    report = read_json(directory, "result.json")
    geometry = read_json(directory, "geometry.json")
    anchors = read_json(directory, "anchors.json")
    chain = read_json(directory, "chain.json")
    assert chain["phase"] == "Completed"
    assert chain["recordingSessionId"] == report["capture"]["sessionId"]
    assert chain["sourceId"] == report["capture"]["sourceId"]
    video = directory / "capture.mp4"
    if not video.is_file() or not 0 < video.stat().st_size <= 256 * 1024 * 1024:
        raise ValueError("Missing or oversized capture.mp4")
    with video.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    assert digest == report["capture"]["sourceSha256"], "Video SHA changed"
    probe = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_packets", "-show_frames",
        "-show_streams", "-show_entries", "stream=width,height,time_base,codec_name:packet=pts:frame=pts",
        "-of", "json", str(video)], timeout=60))
    stream, = probe["streams"]
    assert stream["codec_name"] == "h264"
    width, height = stream["width"], stream["height"]
    assert (width, height) == (480, 800) == (geometry["width"], geometry["height"])
    assert geometry["fullScreen"] and geometry["rotation"] == 0
    assert (geometry["viewOriginX"], geometry["viewOriginY"]) == (0, 0)
    entries = probe["packets_and_frames"]
    packet_pts = [entry["pts"] for entry in entries if entry["type"] == "packet"]
    frame_pts = [entry["pts"] for entry in entries if entry["type"] == "frame"]
    assert 0 < len(packet_pts) <= 12000 and packet_pts == frame_pts, "Decode order/count differs"
    # Android MediaExtractor truncates rational ticks to microseconds. Do not round ffprobe's
    # six-decimal pts_time string, align with uptime, or use nearest-PTS matching.
    time_base = Fraction(stream["time_base"])
    pts_us = [int(pts * time_base * 1_000_000) for pts in packet_pts]
    assert pts_us == report["capture"]["containerSamplePtsUs"], "Container sample identity differs"
    assert len(pts_us) == report["capture"]["samples"] == report["capture"]["allFramesDecoded"]

    states = []
    with subprocess.Popen(["ffmpeg", "-v", "error", "-i", str(video), "-map", "0:v:0",
                           "-fps_mode", "passthrough", "-enc_time_base", "demux", "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE) as decoder:
        try:
            for _ in pts_us:
                frame = decoder.stdout.read(width * height * 3)
                assert len(frame) == width * height * 3, "Incomplete decoded frame"
                states.append(state_in_frame(frame, width, height, geometry))
            assert decoder.stdout.read(1) == b"", "Extra decoded frames"
            _, errors = decoder.communicate(timeout=30)
            assert decoder.returncode == 0 and not errors, f"MP4 decoder error: {errors.decode()[:1000]}"
        finally:
            if decoder.poll() is None:
                decoder.kill()
                decoder.wait()
    assert sum(state is not None for state in states) == report["capture"]["recognizedTargetFrames"]
    assert len(anchors) == len(chain["actions"]) * 2
    seen = set()
    bindings = {}
    checks = []
    for index, action in enumerate(chain["actions"]):
        assert action["outcome"] == "Completed"
        for boundary, expected in (("Before", index), ("After", index + 1)):
            rows = [row for row in anchors if row["actionId"] == action["actionId"] and row["boundary"] == boundary]
            row, = rows
            key = (row["actionId"], boundary)
            assert key not in seen
            seen.add(key)
            result = {"actionNumber": index + 1, "boundary": boundary}
            if "missing" in row:
                assert "muxSampleOrdinal" not in row, "Missing cannot acquire a fabricated binding"
                result.update(missing=row["missing"], provenanceKnown="ticketId" in row)
                if "ticketId" in row:
                    result.update(ticketId=row["ticketId"], storedMissing=row["storedMissing"])
            else:
                ordinal = row["muxSampleOrdinal"]
                assert isinstance(ordinal, int) and 0 <= ordinal < len(pts_us)
                assert row["containerPtsUs"] == pts_us[ordinal]
                assert row["submittedPtsUs"] == row["encoderPtsUs"]
                identity = (row["sourceFrameId"], row["captureSequence"])
                binding = tuple(row[field] for field in ("sourceTimestampNs", "submittedPtsUs", "encoderPtsUs",
                                                        "muxSampleOrdinal", "containerPtsUs", "pngSha256"))
                assert identity not in bindings or bindings[identity] == binding, "Reused source was rebound"
                bindings[identity] = binding
                assert states[ordinal] == expected, "Actual bound MP4 sample has a different fixture state"
                result.update(muxSampleOrdinal=ordinal, containerPtsUs=pts_us[ordinal],
                              decodedVideoState=states[ordinal], sourceFrameId=row["sourceFrameId"])
                if "selection" in row:
                    selected = row["selection"]
                    assert index == 0 and boundary == "Before"
                    assert selected["kind"] == "AdmissionTimeSnapshot"
                    confirmed, deadline = selected["confirmedAtUptimeMs"], selected["deadlineUptimeMs"]
                    assert 0 <= confirmed < deadline and deadline - confirmed <= 15000
                    assert confirmed <= selected["selectedAtUptimeMs"] <= selected["consumedAtUptimeMs"] <= deadline
                    assert selected["selectionPresentationSampleId"] > 0
                    assert row["submittedPtsUs"] <= selected["selectionPresentationPtsUs"]
                    result["selection"] = selected
            checks.append(result)
    return {"sourceSha256": digest, "decodedSamples": len(states), "boundaries": checks,
            "allBoundaryBindingsPresent": not any("missing" in row for row in anchors),
            "privateSameFboPngsVerified": False,
            "scope": "Saved MP4/sample/sidecar consistency only; absent bindings remain Missing. No runtime acceptance or acquisition-cause proof."}


if __name__ == "__main__":
    if not __debug__:
        raise RuntimeError("Run without -O: inspection assertions must remain enabled")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export_directory", type=Path, help="Extracted app/files/runtime-smoke directory")
    args = parser.parse_args()
    print(json.dumps(inspect(args.export_directory.resolve()), indent=2))
