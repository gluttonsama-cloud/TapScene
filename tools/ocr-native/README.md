# Offline PP-OCRv6_tiny raw-pixel engine

TapScene's private C++/JNI pipeline uses the official PP-OCRv6_tiny detection and
recognition models, Microsoft's official ONNX Runtime 1.31.0 Android library,
and only OpenCV 4.14.0 `core` and `imgproc`, compiled statically from official source.
This is a modified port, not the upstream Paddle Android SDK.

## Reproducible inputs

`dependencies.lock.json` records immutable source/model commits, official URLs,
lengths and SHA-256 digests. Models are fixed data bundled in the APK. Neither
model imports nor runtime downloads/configuration are exposed.

- [Paddle Android algorithm source](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk):
  resize, DB quad extraction, round-join polygon expansion, reading-order sort,
  perspective crop, RGB recognizer normalization and CTC decoding.
- [Official detector](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/tree/2ba1506c0380b8f0b03dd142459aac66d4421f6c),
  [official recognizer](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/tree/2612ab37152ae0a677521bae4e1e3d4fb4cf7c30).
  Apache-2.0. Detector 1,780,590 bytes; recognizer 4,462,639 bytes.
- [ONNX Runtime 1.31.0 official Maven artifact](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/):
  extract only its hash-locked C/C++ headers and four ABI `libonnxruntime.so`
  members. No Java wrapper, Java JNI bridge, community AAR or runtime build.
- [OpenCV 4.14.0 fixed source](https://github.com/opencv/opencv/tree/0654a42e19215ef25b1d367d822f3c630447e7c7).
  Unlike 5.0, its `imgproc` module does not require geometry/flann. 4.14 also
  includes the upstream VBLAS reduction and Mat-expression lifetime fixes noted
  in its [release change log](https://github.com/opencv/opencv/wiki/OpenCV-Change-Logs#version4140).
  This is not a claim of being vulnerability-free.

`characters.txt` is the ordered `PostProcess.character_dict` from the locked
recognizer YAML plus its final space, represented as one UTF-8 character per line.
It contains 6,905 entries, 27,158 bytes, SHA-256
`d95c0dcd7abe0d9ee9dbaeec5af4cb11711de2c4d37b3145cba529043d749898`.
The YAML is a verified build input only; runtime has no YAML parser. The text
file is committed so preparation needs only the Python standard library.
Model, source, runtime and relevant third-party license notices are in `licenses/`
and packaged beside the APK models. These notices do not add dependencies.

## Build contract

From the repository root:

```
python3 tools/ocr-native/prepare.py
# Also prepare the standalone official x86_64 Linux host ORT, without Python OCR:
python3 tools/ocr-native/prepare.py --host
```

`--offline` uses only a verified populated cache; `--cache PATH` and `--output PATH`
change build locations. Preparation must finish before CMake starts. A failed
preparation does not silently change the lock; CMake rejects a different prepared
lock. Source archives reject traversal, symlinks, special entries and expansion
above 224 MiB. ZIP extraction selects only exact hash/length-locked members.
All downloads use exact allow-listed official HTTPS destinations on port 443,
including redirects. The current Hugging Face CDN edges `us.aws.cdn.hf.co` and
`us.gcp.cdn.hf.co` are explicitly listed in its
[official download documentation](https://huggingface.co/docs/hub/models-downloading#downloading-behind-a-proxy-or-firewall)
and [official endpoint metadata](https://huggingface.co/.well-known/meta.json).
No wildcard domains or automatic endpoint-list expansion are allowed; all bytes
still must match the fixed model lengths and SHA-256 digests.

Generated paths, not committed:

- `android/app/build/ocr-downloads`: verified byte cache
- `android/app/build/ocr/src/opencv`: patched official source
- `android/app/build/ocr/include/onnxruntime`: verified official AAR headers
- `android/app/build/ocr/ort/{arm64-v8a,armeabi-v7a,x86,x86_64}/libonnxruntime.so`
- `android/app/build/ocr/ort/host`: optional host-only runtime
- `android/app/build/ocr/assets/ocr/{det.onnx,rec.onnx,characters.txt,licenses/*}`

Android uses NDK 28.2.13676358, CMake 3.22.1, API 26+, `c++_static` and target
`tapscene_ocr`. CMake imports the official ORT shared library and statically links
OpenCV into `libtapscene_ocr.so`. Gradle packages the imported runtime; no Gradle
ORT/OpenCV Java dependency is needed. The wrapper requests 16 KiB ELF alignment.
Each final packaged library and APK ZIP alignment must still be measured.

```
cmake -S android/app/src/main/cpp -B android/app/build/ocr-host \
  -DCMAKE_BUILD_TYPE=Release -DTAPSCENE_HOST_TEST=ON
cmake --build android/app/build/ocr-host \
  --target tapscene_ocr_fixture tapscene_ocr_core_tests -j2
android/app/build/ocr-host/tapscene_ocr_core_tests android/app/build/ocr/assets/ocr
android/app/build/ocr-host/tapscene_ocr_fixture --version
android/app/build/ocr-host/tapscene_ocr_fixture fixture.pgm android/app/build/ocr/assets/ocr
```

The explicit host runner is not shipped. It accepts bounded P5 grayscale or P6 RGB
synthetic fixtures; grayscale is repeated into RGB. It uses the exact production
C++ core, prints standard word-level TSV, and accepts no PSM/configuration option.
Use synthetic inputs only: its TSV intentionally exposes fixture text to tests.
No Python OCR or separate pre/postprocessor substitutes for the production core.

## Dependency boundary

All optional OpenCV `WITH_*` backends are OFF. IPP/IPP-IW/ITT, OpenCL, CUDA,
TBB/OpenMP/pthread-pool, Eigen/LAPACK, all accelerated external HALs, Java,
Python modules, GAPI/ADE, GUI, video, imgcodecs and DNN are disabled. Only core
and imgproc are built; extra CPU-dispatch variants are omitted. OpenCV filesystem
and environment-driven configuration are disabled. No third-party dependency
is built in this profile.

Exact-match preparation patches additionally:

1. Make `ocv_download` fail closed, so even a mistakenly enabled module cannot
   fetch an unpinned dependency while configuring.
2. Remove unconditional zlib discovery/build and disable persistence compression.
   No zlib/archive or encoded-image API is used by the core. OpenCV's generic
   summary may still print a blank `ZLib: build (ver )` label; inspect actual link
   dependencies and symbols, rather than interpreting that upstream label.

`cv::setNumThreads(1)` plus sequential single-thread CPU ORT provide one bounded
pipeline. ORT telemetry is explicitly disabled immediately after its private
custom-logger environment is created. ORT logs go to a discard callback, with
fatal-only severity, profiling off and no run log identifiers containing inputs.
OpenCV's internal logger/error callback discards payloads. Static-library stdio
references are also wrapped locally. There is no process-wide stdout/stderr
redirection, OCR text log, raw-image log, network permission, dynamic provider,
custom operation library, external model execution or SDK background service.
These controls do not suppress OS crash reporting or prove a device log audit.

## API and limits

Public Kotlin API and `OcrResult`/`OcrWord` remain compatible. Recognition is
suspending, off the UI thread and process-wide serialized. Callers own the Bitmap
and must not mutate/recycle it until the call exits. Kotlin reads bounded rows,
composites alpha on white and retains original RGB channels. JNI copies RGB bytes
without pinning the JVM heap. Only validated fixed APK models from a versioned
`noBackupFilesDir` directory are accepted by this private bridge; length and
SHA-256 are checked every call and replacements use a verified atomic rename.

Input: edge <= 2,400; pixels <= 1,080 × 2,400. Official detector preprocessing uses
BGR, minimum side 64, maximum side 1,280 and maximum area 786,432 pixels (TapScene’s global resource budget), nearest-even multiples of 32 (with a further proportional, downward-32 adjustment if rounding exceeds the area budget), and the
upstream mean/std constants. Long screens are uniformly resized; this can miss small text. No image-specific threshold or selective crop is used. Recognizer uses one crop at a time, RGB, height 48,
width <= 3,200 and CTC retained-character mean confidence × 100. This line score
is not calibrated to Tesseract word confidence. Returned axis-aligned bounds are
in original input coordinates, derived from the detected quadrilateral.

Additional safety bounds: detector tensor <= 786,432 pixels; mask foreground
runs <= 32,768 before contours; <= 3,000 examined contours; <= 256 retained lines;
crop <= 3,000,000 pixels; recognizer output <= 800 × 6,906 float values; <= 512
UTF-16 units/2,048 UTF-8 bytes per line; <= 8,192 UTF-16 units/32,768 UTF-8 bytes
in total. Count/text omissions set `truncated`; pathological allocation/shape
conditions fail with fixed codes. No threshold is special-cased for icons.

Cancellation is a one-shot flag checked in pixel, mask, geometry and CTC loops.
A joined watchdog observes cancellation/deadline every 5 ms and invokes ORT
`RunOptions.SetTerminate` to interrupt inference. There is a 20-second cooperative
native deadline. OpenCV calls and ORT model initialization do not expose progress
callbacks, so this is not a hard real-time deadline. No detached worker outlives
recognition. Sessions, tensors and temporary crops are released each call;
subsequent frames currently pay model initialization again. Device latency and
peak-memory budgets remain a separate acceptance task.

Raw lines are unreviewed evidence. They never establish an observed tap,
sensitive-content coverage or a formal title/hotspot/mask. `OcrTitlePolicy` stays
engine-independent: >= 80 confidence for every line element, >= three letters or
digits, <= 80 UTF-16 units, at most eight distinct suggestions. A semantic-length
rule naturally excludes a one-character symbol, but raw OCR is preserved.

## Verification status

Preparation and the final native build/fixture results must be reported separately
from the earlier Python/OpenCV5 comparison. The same six immutable synthetic
fixtures are used; low-confidence icon output is reported rather than hidden.
Check actual results in `tools/ocr-checks` and the current task report. Host passes
are not Android/JNI/physical-device evidence. APK dependencies/alignment, true
offline operation, cancellation latency, process logs, memory and backup exclusion
still require their own measurements.

### Final host measurements, 2026-10-09

The final 1,280-max-side/786,432-area C++ core was compiled with GCC 14.2/CMake 3.31.10.
Core safety smoke passed; all six immutable fixtures repeated three times passed
the scoped check: 73/73 normalized characters across five text fixtures, 16 line
box IoUs 0.5558–0.9227, identical repeated outputs. The icon fixture retained `+`
with 63.45 confidence and zero semantic title suggestions. No icon-specific
filter was added. All recognition/core-test stderr files were empty. Final six-case cold-process medians were 124–137 ms; consult the report
for per-run timing, not device claims.

The additional uniform long-screen resource fixture and two blank frames can be
reproduced without Python image/OCR packages:

```
python3 tools/ocr-native/check-resources.py \
  --native-runner android/app/build/ocr-host/tapscene_ocr_fixture \
  --model-dir android/app/build/ocr/assets/ocr --output /tmp/ocr-resource-check
```

One local run of the final cap measured:

- 1,080 × 2,400 blank, detector 576 × 1,280: 143,176 KiB peak RSS, 211 ms
- 1,280 × 1,280 blank, detector 864 × 864: 141,064 KiB peak RSS, 200 ms
- 1,080 × 2,400 composite text screen: 143,184 KiB peak RSS, 270 ms

The composite uses unchanged pixels from six original fixtures in equal 400-row
panels, each uniformly omitting the bottom 40 rows. Truth is used only after full
image inference for scoring. It retained all 73 normalized characters, including
the 12–14-pixel text, but two expanded boxes had IoU below 0.5 (`上一步` 0.4794,
`尚未确认` 0.4987). It is not a passed long-screen box-quality gate. Real long
screens may lose small text. The square case is important: without the area budget it measured
248,328 KiB/429 ms, despite the same maximum edge. All figures exclude Android
UI/video/Bitmap overhead.

The pre-cap full-resolution blank measured 332,636 KiB peak RSS/743 ms and is
retained as a resource comparison, not the shipping profile. Memory figures are
host process high-water marks and must not be presented as phone budgets.
