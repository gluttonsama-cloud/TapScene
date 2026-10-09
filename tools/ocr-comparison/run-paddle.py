#!/usr/bin/env python3
# Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
# Adapted for synthetic host comparison. Licensed under Apache-2.0;
# see LICENSE-PaddleOCR.txt. This modified port is not the upstream SDK.
"""Host-only official Paddle Android pipeline port; synthetic raw pixels only.

Pre/postprocess adapted from PaddlePaddle/PaddleOCR (Apache-2.0), commit
dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf, deploy/ppocr-android/ppocr-sdk.
Source filenames and hashes are recorded in paddle-locks.json. This is an
evaluation port, not an Android runtime or a claim of identical native binaries.
No network or truth-dependent preprocessing is used. Do not feed user media.
"""
import argparse
import gzip
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import resource
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent


def raw_fixture(fixture, folder):
    data = gzip.decompress((folder / fixture['file']).read_bytes())
    if hashlib.sha256(data).hexdigest() != fixture['pgm_sha256']:
        raise ValueError('Fixture digest mismatch')
    header = f"P5\n{fixture['width']} {fixture['height']}\n255\n".encode()
    if not data.startswith(header) or len(data) != len(header) + fixture['width'] * fixture['height']:
        raise ValueError('Unexpected raw PGM encoding')
    gray = np.frombuffer(data[len(header):], np.uint8).reshape(fixture['height'], fixture['width'])
    return np.repeat(gray[:, :, None], 3, axis=2)


def ordered_rect(points):
    # QuadGeometry.orderMinAreaRectPoints, stable x sort.
    p = sorted(points, key=lambda p: float(p[0]))
    tl, bl = (p[0], p[1]) if p[1][1] > p[0][1] else (p[1], p[0])
    tr, br = (p[2], p[3]) if p[3][1] > p[2][1] else (p[3], p[2])
    return np.array([tl, tr, br, bl], np.float32)


def unclip(points, ratio=1.5):
    # PolygonUnclip.kt round joins, without introducing another geometry library.
    p = np.asarray(points, np.float64)
    area = sum(p[i, 0]*p[(i+1)%4, 1]-p[(i+1)%4, 0]*p[i, 1] for i in range(4))/2
    edges = np.roll(p, -1, axis=0)-p
    lengths = np.linalg.norm(edges, axis=1)
    if abs(area) <= 1e-6 or min(lengths) <= 1e-6:
        return points
    distance = abs(area)*ratio/sum(lengths)
    normals = [(np.array([e[1], -e[0]]) if area > 0 else np.array([-e[1], e[0]]))/l
               for e, l in zip(edges, lengths)]
    step_angle = 2*math.acos(min(1., max(-1., 1-.25/distance)))
    if not math.isfinite(step_angle) or step_angle <= 1e-6:
        step_angle = math.pi/8
    result = []
    for i in range(4):
        start = math.atan2(normals[(i-1)%4][1], normals[(i-1)%4][0])
        end = math.atan2(normals[i][1], normals[i][0])
        if area > 0:
            while end < start:
                end += 2*math.pi
        else:
            while end > start:
                end -= 2*math.pi
        steps = max(1, math.ceil(abs(end-start)/step_angle))
        for n in range(steps+1):
            a = start+(end-start)*n/steps
            q = p[i]+distance*np.array([math.cos(a), math.sin(a)])
            if not result or np.linalg.norm(q-result[-1]) > 1e-6:
                result.append(q)
    return np.asarray(result, np.float32)


def det_preprocess(src):
    # Official Android defaults: BGR, limit type min, side 64, max side 4000.
    h, w = src.shape[:2]
    ratio = 64/min(h, w) if min(h, w) < 64 else 1.
    nh, nw = int(h*ratio), int(w*ratio)
    if max(nh, nw) > 4000:
        ratio = 4000/max(nh, nw)
        nh, nw = int(nh*ratio), int(nw*ratio)
    nh, nw = max(round(nh/32)*32, 32), max(round(nw/32)*32, 32)
    image = cv2.resize(src, (nw, nh), interpolation=cv2.INTER_LINEAR).astype(np.float32)
    image *= np.float32(1/255)
    image -= np.array([.485, .456, .406], np.float32)
    image /= np.array([.229, .224, .225], np.float32)
    return np.ascontiguousarray(image.transpose(2, 0, 1)[None])


def db_postprocess(prob, height, width):
    mask = (prob > .3).astype(np.uint8)*255
    contours, _ = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    ph, pw = prob.shape
    boxes = []
    for contour in contours[:3000]:
        rect = cv2.minAreaRect(contour)
        if min(rect[1]) < 3:
            continue
        pts = ordered_rect(cv2.boxPoints(rect))
        x0, y0 = np.maximum(np.floor(pts.min(axis=0)), 0).astype(int)
        x1, y1 = np.minimum(np.ceil(pts.max(axis=0)), [pw-1, ph-1]).astype(int)
        region_mask = np.zeros((y1-y0+1, x1-x0+1), np.uint8)
        cv2.fillPoly(region_mask, [(pts-[x0, y0]).astype(np.int32)], 1)
        score = cv2.mean(prob[y0:y1+1, x0:x1+1], region_mask)[0]
        if score < .6:
            continue
        expanded = cv2.minAreaRect(unclip(pts))
        if min(expanded[1]) < 5:
            continue
        pts = ordered_rect(cv2.boxPoints(expanded))
        pts = np.clip(np.rint(pts.astype(np.float64)*[width/pw, height/ph]), [0, 0], [width, height]).astype(np.float32)
        if np.linalg.norm(pts[1]-pts[0]) <= 3 or np.linalg.norm(pts[3]-pts[0]) <= 3:
            continue
        boxes.append(pts)
    # BoxSorter.kt, 10 pixel row band.
    boxes.sort(key=lambda b: (b[0, 1], b[0, 0]))
    for i in range(len(boxes)-1):
        j = i
        while j >= 0 and abs(boxes[j+1][0, 1]-boxes[j][0, 1]) < 10 and boxes[j+1][0, 0] < boxes[j][0, 0]:
            boxes[j], boxes[j+1] = boxes[j+1], boxes[j]
            j -= 1
    return boxes


def crop_quad(src, points):
    pts = ordered_rect(cv2.boxPoints(cv2.minAreaRect(points)))
    w = max(1, int(max(np.linalg.norm(pts[0]-pts[1]), np.linalg.norm(pts[2]-pts[3]))))
    h = max(1, int(max(np.linalg.norm(pts[0]-pts[3]), np.linalg.norm(pts[1]-pts[2]))))
    dst = np.array([[0, 0], [w, 0], [w, h], [0, h]], np.float32)
    transform = cv2.getPerspectiveTransform(pts, dst)
    result = cv2.warpPerspective(src, transform, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    return cv2.rotate(result, cv2.ROTATE_90_COUNTERCLOCKWISE) if h/w >= 1.5 else result


def recognize(src, det, rec, characters):
    t0 = time.perf_counter()
    tensor = det_preprocess(src)
    t1 = time.perf_counter()
    prob = det.run(None, {det.get_inputs()[0].name: tensor})[0][0, 0]
    t2 = time.perf_counter()
    h, w = src.shape[:2]
    boxes = db_postprocess(prob, h, w)
    t3 = time.perf_counter()
    words = []
    rec_pre = rec_inf = rec_post = 0.
    for i, box in enumerate(boxes):
        t = time.perf_counter()
        crop = crop_quad(src, box)
        rgb = cv2.cvtColor(crop, cv2.COLOR_BGR2RGB)
        nw = min(3200, math.ceil(48*rgb.shape[1]/rgb.shape[0]))
        normalized = cv2.resize(rgb, (nw, 48), interpolation=cv2.INTER_LINEAR).astype(np.float32)/np.float32(127.5)-np.float32(1)
        tensor = np.ascontiguousarray(normalized.transpose(2, 0, 1)[None])
        t4 = time.perf_counter()
        pred = rec.run(None, {rec.get_inputs()[0].name: tensor})[0][0]
        t5 = time.perf_counter()
        if pred.shape[-1] != len(characters)+1:
            raise ValueError(f'Unexpected dictionary shape: {pred.shape}, {len(characters)}')
        indices = pred.argmax(axis=-1)
        confs, text, previous = [], [], -1
        for timestep, index in enumerate(indices):
            if index != 0 and index != previous:
                text.append(characters[index-1])
                confs.append(float(pred[timestep, index]))
            previous = index
        confidence = statistics.mean(confs) if confs else 0.
        bounds = [float(box[:, 0].min()), float(box[:, 1].min()), float(box[:, 0].max()), float(box[:, 1].max())]
        if text:  # Official recScoreThresh=0, no extra quality filtering.
            words.append({'text': ''.join(text), 'confidence': confidence*100,
                          'line_id': [1, i+1, 1, 1], 'box': bounds, 'quad': box.tolist(),
                          'normalized_box': [bounds[0]/w, bounds[1]/h, (bounds[2]-bounds[0])/w, (bounds[3]-bounds[1])/h]})
        rec_pre += t4-t
        rec_inf += t5-t4
        rec_post += time.perf_counter()-t5
    return {'words': words, 'detected_quads': [b.tolist() for b in boxes],
            'pipeline_ms': (time.perf_counter()-t0)*1000,
            'stage_ms': {'det_pre': (t1-t0)*1000, 'det_inference': (t2-t1)*1000,
                         'det_post': (t3-t2)*1000, 'rec_pre_crop': rec_pre*1000,
                         'rec_inference': rec_inf*1000, 'rec_post': rec_post*1000}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case')
    parser.add_argument('--repeat', type=int, choices=range(1, 4), default=3)
    args = parser.parse_args()
    fixtures = ROOT.parent/'ocr-checks'/'fixtures'
    manifest = json.loads((fixtures/'manifest.json').read_text())
    locks = json.loads((ROOT/'paddle-locks.json').read_text())
    if args.case:
        global cv2, np
        import cv2
        import numpy as np
        import onnxruntime as ort
        ort.disable_telemetry_events()  # Supported API, before any inference session.
        import yaml
        cv2.setNumThreads(1)
        for item in locks['files']:
            if item['path'].startswith('PP-OCR'):
                if hashlib.sha256((args.models/item['path']).read_bytes()).hexdigest() != item['sha256']:
                    raise ValueError('Model digest mismatch: '+item['path'])
        fixture = next(x for x in manifest['fixtures'] if x['name'] == args.case)
        config = yaml.safe_load((args.models/'PP-OCRv6_tiny_rec_onnx'/'inference.yml').read_text())
        characters = config['PostProcess']['character_dict']
        if not characters or characters[-1] != ' ':
            characters.append(' ')
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 1
        opts.inter_op_num_threads = 1
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        t = time.perf_counter()
        det = ort.InferenceSession(str(args.models/'PP-OCRv6_tiny_det_onnx'/'inference.onnx'), sess_options=opts, providers=['CPUExecutionProvider'])
        rec = ort.InferenceSession(str(args.models/'PP-OCRv6_tiny_rec_onnx'/'inference.onnx'), sess_options=opts, providers=['CPUExecutionProvider'])
        load_ms = (time.perf_counter()-t)*1000
        result = recognize(raw_fixture(fixture, fixtures), det, rec, characters)
        result.update(model_load_ms=load_ms, peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                      versions={'onnxruntime': ort.__version__, 'opencv': cv2.__version__, 'numpy': np.__version__})
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
        return
    args.output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('metrics', ROOT.parent/'ocr-checks'/'check-ocr.py')
    metrics = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(metrics)
    report = {'scope': 'synthetic host-only; not Android/device acceptance', 'platform': platform.platform(),
              'pipeline_source_commit': locks['source_commit'], 'models': locks['models'],
              'settings': 'Official Android default Bitmap pipeline; CPU 1 thread; rec batch 1; telemetry disabled',
              'timing_scope': 'each subprocess includes imports, digest check, YAML parse, model load, one full-image inference; separate stage times exclude setup',
              'confidence_scope': 'Paddle per-line CTC retained-character mean x100; not calibrated to Tesseract word confidence',
              'cases': []}
    failed = []
    for fixture in manifest['fixtures']:
        results, elapsed = [], []
        for n in range(args.repeat):
            output = args.output/f"{fixture['name']}-{n+1}.json"
            start = time.perf_counter()
            proc = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--models', str(args.models),
                                   '--output', str(output), '--case', fixture['name']], capture_output=True, text=True,
                                  timeout=120, env={**os.environ, 'OMP_NUM_THREADS': '1', 'OPENBLAS_NUM_THREADS': '1'})
            elapsed.append((time.perf_counter()-start)*1000)
            (args.output/f"{fixture['name']}-{n+1}.stderr").write_text(proc.stderr)
            if proc.returncode:
                raise RuntimeError(proc.stderr)
            results.append(json.loads(output.read_text()))
        scored = metrics.score(fixture, results[0]['words'])
        # Tesseract-specific conservative candidate policy is not an engine score.
        scored.pop('conservative_review_candidates')
        stable = all(r['words'] == results[0]['words'] for r in results)
        passed = stable and (fixture['group'] != 'core' or
                 (scored['cer'] <= .15 and all(l['actual'] and l['box_iou'] >= .5 for l in scored['lines'])))
        if fixture['group'] == 'negative':
            passed = passed and not scored['words']
        if not passed:
            failed.append(fixture['name'])
        case = {'name': fixture['name'], 'group': fixture['group'], 'stable': stable,
                'status': ('REPORTED' if fixture['group'] == 'stress' else ('PASS' if passed else 'FAIL')),
                'elapsed_ms': elapsed, 'median_elapsed_ms': statistics.median(elapsed),
                'median_pipeline_ms': statistics.median(r['pipeline_ms'] for r in results),
                'peak_rss_kib': max(r['peak_rss_kib'] for r in results), **scored, 'runs': results}
        report['cases'].append(case)
        print(f"{case['name']}: {case['status']} CER={case['cer']} text="+repr([w['text'] for w in case['words']]), flush=True)
    report['gate'] = 'FAIL' if failed else 'PASS'
    report['failed_cases'] = failed
    (args.output/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    if failed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
