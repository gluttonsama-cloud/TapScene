#!/usr/bin/env python3
"""Run six synthetic cases without network access, Pillow, or user media."""
import argparse
import csv
import gzip
import hashlib
import io
import json
import math
import os
from pathlib import Path
import platform
import statistics
import subprocess
import time
import unicodedata

ROOT = Path(__file__).resolve().parent


def normalize(text):
    return "".join(unicodedata.normalize("NFC", text).split())


def distance(reference, actual):
    """Levenshtein edit counts; deterministic tie-break substitution/delete/insert."""
    rows = [[(j, 0, 0, j) for j in range(len(actual) + 1)]]
    for i, ref in enumerate(reference, 1):
        row = [(i, 0, i, 0)]
        for j, got in enumerate(actual, 1):
            d, s, deletion, insertion = rows[-1][j - 1]
            change = int(ref != got)
            candidates = [(d + change, s + change, deletion, insertion)]
            d, s, deletion, insertion = rows[-1][j]
            candidates.append((d + 1, s, deletion + 1, insertion))
            d, s, deletion, insertion = row[j - 1]
            candidates.append((d + 1, s, deletion, insertion + 1))
            row.append(min(candidates, key=lambda v: v[0]))
        rows.append(row)
    d, s, deletion, insertion = rows[-1][-1]
    return {"edits": d, "substitutions": s, "deletions": deletion, "insertions": insertion}


def parse_tsv(tsv, scale, width, height):
    words = []
    reader = csv.DictReader(io.StringIO(tsv), delimiter="\t", quoting=csv.QUOTE_NONE)
    if not {"level", "left", "top", "width", "height", "conf", "text"}.issubset(reader.fieldnames or []):
        raise ValueError("OCR output does not contain the required TSV header")
    for row in reader:
        if row.get("level") != "5" or not row.get("text", "").strip():
            continue
        left, top, w, h = [int(row[key]) / scale for key in ("left", "top", "width", "height")]
        confidence = float(row["conf"])
        if not all(math.isfinite(v) for v in [left, top, w, h, confidence]):
            raise ValueError("Non-finite OCR output")
        if left < 0 or top < 0 or w <= 0 or h <= 0 or left + w > width or top + h > height:
            raise ValueError("OCR word box is outside image")
        words.append({"text": row["text"], "confidence": confidence,
                      "line_id": [row.get(key, "0") for key in ("page_num", "block_num", "par_num", "line_num")],
                      "box": [left, top, left + w, top + h],
                      "normalized_box": [left / width, top / height, w / width, h / height]})
    return words


def conservative_candidates(words):
    """Diagnostic review suggestions, not evidence of buttons, taps, or sensitivity.

    Use engine-provided line membership only. Never use expected labels/boxes to
    construct candidates. Even these high-confidence results can be incomplete.
    """
    groups = {}
    for word in words:
        groups.setdefault(tuple(word["line_id"]), []).append(word)
    candidates = []
    seen = set()
    for group in groups.values():
        # Same input ordering, join, whole-line threshold and deduplication as
        # OcrTitlePolicy for this fixture charset (Han + Latin + ASCII digits).
        label = ""
        for word in group:
            part = word["text"].strip()
            if not part:
                continue
            if label and not is_cjk(label[-1]) and not is_cjk(part[0]):
                label += " "
            label += part
        letters_digits = sum(unicodedata.category(char).startswith("L") or
                             unicodedata.category(char) == "Nd" for char in label)
        if (letters_digits < 3 or len(label.encode("utf-16-le")) // 2 > 80 or
                min(word["confidence"] for word in group) < 80 or label in seen):
            continue
        seen.add(label)
        candidates.append({"text": label,
                           "minimum_word_confidence": min(word["confidence"] for word in group),
                           "box": [min(w["box"][0] for w in group), min(w["box"][1] for w in group),
                                   max(w["box"][2] for w in group), max(w["box"][3] for w in group)]})
        if len(candidates) == 8:
            break
    return candidates


def is_cjk(char):
    # Script-equivalent for all fixture text. This diagnostic helper is not a
    # replacement for Android's UnicodeScript implementation on arbitrary input.
    cp = ord(char)
    return (0x3400 <= cp <= 0x4DBF or 0x4E00 <= cp <= 0x9FFF or
            0xF900 <= cp <= 0xFAFF or 0x20000 <= cp <= 0x323AF or
            0x3041 <= cp <= 0x3096 or 0x30A1 <= cp <= 0x30FA or
            0x1100 <= cp <= 0x11FF or 0xAC00 <= cp <= 0xD7A3)


def score(fixture, words):
    groups = [[] for _ in fixture["lines"]]
    unmatched = []
    for word in words:
        left, top, right, bottom = word["box"]
        cx, cy = (left + right) / 2, (top + bottom) / 2
        # Assignment only evaluates output. OCR gets entire frame, never truth boxes.
        matches = [i for i, line in enumerate(fixture["lines"])
                   if line["box"][0] - 4 <= cx <= line["box"][2] + 4
                   and line["box"][1] - 4 <= cy <= line["box"][3] + 4]
        (groups[matches[0]] if matches else unmatched).append(word)
    lines = []
    counts = dict(edits=0, substitutions=0, deletions=0, insertions=0)
    total = 0
    for truth, group in zip(fixture["lines"], groups):
        actual = "".join(word["text"] for word in sorted(group, key=lambda w: w["box"][0]))
        ref = normalize(truth["text"])
        errors = distance(ref, normalize(actual))
        total += len(ref)
        for key in counts:
            counts[key] += errors[key]
        box = None
        iou = 0
        if group:
            box = [min(w["box"][0] for w in group), min(w["box"][1] for w in group),
                   max(w["box"][2] for w in group), max(w["box"][3] for w in group)]
            target = truth["box"]
            intersection = max(0, min(box[2], target[2]) - max(box[0], target[0])) * max(
                0, min(box[3], target[3]) - max(box[1], target[1]))
            union = ((box[2] - box[0]) * (box[3] - box[1]) +
                     (target[2] - target[0]) * (target[3] - target[1]) - intersection)
            iou = intersection / union if union else 0
        lines.append({"expected": truth["text"], "actual": actual, **errors,
                      "expected_box": truth["box"], "actual_box": box, "box_iou": round(iou, 4)})
    extra = sum(len(normalize(word["text"])) for word in unmatched)
    counts["edits"] += extra
    counts["insertions"] += extra
    return {"characters": total, **counts, "cer": round(counts["edits"] / total, 4) if total else None,
            "lines": lines, "unmatched_words": unmatched,
            "false_positive_characters": extra, "all_word_boxes_in_bounds": True,
            "conservative_review_candidates": conservative_candidates(words),
            "words": words}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--native-runner', type=Path, required=True)
    parser.add_argument('--fixtures', type=Path, default=ROOT / 'fixtures')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repeat', type=int, choices=range(1, 6), default=2)
    parser.add_argument('--materialize-only', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((args.fixtures / 'manifest.json').read_text())
    lock = json.loads((ROOT.parent / 'ocr-native/dependencies.lock.json').read_text())
    models = {}
    if not args.materialize_only:
        for entry in lock['models']['files']:
            data = (args.model_dir / entry['name']).read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            if len(data) != entry['bytes'] or digest != entry['sha256']:
                raise ValueError('Bundled OCR model bytes differ from lock')
            models[entry['name']] = digest
    version = 'not run'
    if not args.materialize_only:
        result = subprocess.run([str(args.native_runner), '--version'], capture_output=True,
                                text=True, encoding='utf-8', timeout=10, check=True)
        version = result.stdout.strip()
        if result.stderr:
            raise ValueError('Native runner unexpectedly wrote diagnostics')
    report = {
        'schema': 2, 'scope': 'production native core; synthetic host-only checks; not Android acceptance',
        'platform': platform.platform(), 'engine_version': version, 'model_sha256': models,
        'fixture_manifest_sha256': hashlib.sha256((args.fixtures / 'manifest.json').read_bytes()).hexdigest(),
        'timing_scope': 'fresh native process and model load each run; host wall time, not phone performance',
        'cases': [],
    }
    failed = []
    for fixture in manifest['fixtures']:
        pgm = gzip.decompress((args.fixtures / fixture['file']).read_bytes())
        header = f"P5\n{fixture['width']} {fixture['height']}\n255\n".encode()
        if (hashlib.sha256(pgm).hexdigest() != fixture['pgm_sha256'] or not pgm.startswith(header) or
                len(pgm) != len(header) + fixture['width'] * fixture['height']):
            raise ValueError('Synthetic fixture differs from fixed pixels')
        input_path = args.output / (fixture['name'] + '.pgm')
        input_path.write_bytes(pgm)
        if args.materialize_only:
            continue
        command = [str(args.native_runner), str(input_path), str(args.model_dir)]
        elapsed, runs = [], []
        for index in range(args.repeat):
            start = time.perf_counter()
            result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', timeout=45,
                                    env={**os.environ, 'OMP_THREAD_LIMIT': '1'})
            elapsed.append(round((time.perf_counter() - start) * 1000, 2))
            (args.output / f"{fixture['name']}.{index}.tsv").write_text(result.stdout, encoding='utf-8')
            (args.output / f"{fixture['name']}.{index}.stderr").write_text(result.stderr, encoding='utf-8')
            if result.returncode or result.stderr:
                raise RuntimeError('Native synthetic check failed or emitted unexpected diagnostics: ' + fixture['name'])
            runs.append(score(fixture, parse_tsv(result.stdout, 1, fixture['width'], fixture['height'])))
        case = {'name': fixture['name'], 'group': fixture['group'], **runs[0], 'elapsed_ms': elapsed,
                'median_ms': statistics.median(elapsed), 'repeat_output_identical': all(run == runs[0] for run in runs)}
        passed = case['repeat_output_identical']
        if fixture['lines']:
            passed &= (case['cer'] <= .15 and all(line['actual'] and line['box_iou'] >= .5 for line in case['lines']))
            case['check_scope'] = 'synthetic text accuracy and image-coordinate boxes'
        else:
            # A + glyph can legitimately be read as text. Keep ALL raw output and its
            # unmatched-character count; test the product boundary, not a fragile symbol ban.
            case['raw_output_empty'] = not case['words']
            case['check_scope'] = 'symbol-control: no semantic title suggestion; raw symbols are reported'
            passed &= not case['conservative_review_candidates']
        case['scoped_check'] = 'PASS' if passed else 'FAIL'
        if not passed:
            failed.append(fixture['name'])
        report['cases'].append(case)
        print(f"{fixture['name']}: CER={case['cer']} unmatched={case['false_positive_characters']} "
              f"title_suggestions={len(case['conservative_review_candidates'])} median={case['median_ms']:.1f}ms "
              f"{case['scoped_check']} ({case['check_scope']})")
    if not args.materialize_only:
        report['scoped_check'] = 'FAIL' if failed else 'PASS'
        report['failed_cases'] = failed
        (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        if not failed:
            print('TAPSCENE_OCR_SYNTHETIC_CHECKS_OK')
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
