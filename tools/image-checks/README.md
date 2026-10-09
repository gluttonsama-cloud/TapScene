# Screenshot input checks

Run `python3 tools/image-checks/check-images.py --report /tmp/screenshot-host-report.json`
with Python Pillow and a JDK. This
compiles and invokes the exact production `StrictImageInput.java` with a 128 MiB
heap. Fixtures are synthetic and generated in a temporary directory; no personal
screenshots, network access or binary fixture download is used.

Coverage: actual PNG/JPEG signatures despite misleading filenames; PNG CRC,
complete zlib stream and Adler checksum, raster size, filters, Adam7 pass layouts,
indexed palette bounds and all five filters, all eight EXIF orientations in both byte orders; baseline and
progressive JPEG, grayscale/RGB, common sampling factors and restart intervals,
complete Huffman/MCU consumption including each progressive scan; ICC color
metadata, thumbnail removal and retained JFIF color interpretation; cancellation. Rejection cases include APNG, MPO, CMYK/unsupported JPEG,
empty/mislabeled formats, >10 MiB, >12 MP, missing/trailing data, truncated entropy,
invalid EXIF, and compressed ICC bombs. Metadata sent to Android is sanitized;
private EXIF/XMP/thumbnails/gainmaps never enter the decoder.

`ScreenshotImportChecks.run` performs separate Android checks against actual
BitmapFactory and Canvas pixels: all EXIF mirrors and rotations for PNG/JPEG,
black alpha flattening, fresh opaque sRGB output, sampled 2160x4800 input,
private reread, owned staging cleanup, active-session isolation and cold recovery.
The instrumentation runner must call this function. Host parsing does **not**
prove Android decoding, device memory behavior, SAF picker behavior or UI lifecycle.
If no device/emulator runs the instrumentation, report those checks as `NOT_RUN`.

Input policy is deliberately conservative: PNG is static; JPEG is ordinary 8-bit
Huffman baseline/progressive with one or three components. Arithmetic, lossless,
CMYK and multi-picture JPEG are explicitly rejected. Dimensions are <=12 MP and
<=32768 on either axis. Every allocation stage checks heap headroom; output is
aspect-fit to short side<=1080 and long side<=2400. Both display name and original
metadata remain private. No original pixels enter public assets except through
SafeMediaWriter and explicit review.
