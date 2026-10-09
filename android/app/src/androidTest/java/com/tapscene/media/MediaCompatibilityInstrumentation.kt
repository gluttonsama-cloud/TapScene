package com.tapscene.media

import android.app.Activity
import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.Image
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Platform-only device smoke checks. Run with adb am instrument -w; no test framework is needed.
 * Assets are synthetic CI fixtures, never user media. This deliberately bypasses the system picker
 * and SourceImporter's content-provider copy/registration flow; those still need separate coverage.
 */
class MediaCompatibilityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val result = Bundle()
        try {
            // Instrumentation's worker must not occupy the main looper: Transformer switches there.
            check(Looper.myLooper() != Looper.getMainLooper()) { "Checks must not block the main looper" }
            runBlocking(Dispatchers.IO) {
                withTimeout(180_000) { runChecks() }
            }
            result.putString("stream", "TAPSCENE_MEDIA_CHECKS_OK")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "TAPSCENE_MEDIA_CHECKS_FAILED")
            result.putString("stack", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private suspend fun runChecks() {
        val privateRoot = targetContext.noBackupFilesDir
        val outputRoot = File(privateRoot, "media-checks-${UUID.randomUUID()}")
        check(outputRoot.mkdir()) { "Could not reserve private smoke-check directory" }
        val sources = mutableListOf<Fixture>()
        val ownedSources = mutableListOf<File>()
        var failure: Throwable? = null
        try {
            // Record each exclusively-created path before reading bytes, so failed copies clean up.
            val hevc = copyFixture("hevc-sdr.mp4", ownedSources).also(sources::add)
            val avc = copyFixture("avc-sdr.mp4", ownedSources).also(sources::add)
            val tenBit = copyFixture("hevc-10bit-sdr.mp4", ownedSources).also(sources::add)
            val hdr = copyFixture("hevc-hdr.mp4", ownedSources).also(sources::add)
            val unknownColour = copyFixture("hevc-unknown-colour.mp4", ownedSources).also(sources::add)
            checkFixture(hevc, MediaFormat.MIMETYPE_VIDEO_HEVC, hasAudio = true)
            checkFixture(avc, MediaFormat.MIMETYPE_VIDEO_AVC, hasAudio = false)
            checkFixture(tenBit, MediaFormat.MIMETYPE_VIDEO_HEVC, hasAudio = false)
            checkFixture(hdr, MediaFormat.MIMETYPE_VIDEO_HEVC, hasAudio = false)
            checkFixture(unknownColour, MediaFormat.MIMETYPE_VIDEO_HEVC, hasAudio = false)

            val writer = SafeMediaWriter(targetContext)
            val decoder = VideoFrameDecoder(targetContext)
            checkSupported(hevc, writer, decoder, File(outputRoot, "hevc"))
            checkSourcesUnchanged(sources)
            status("HEVC Main 8-bit SDR: full decode, PTS, masked PNG and silent H.264 passed")
            checkSupported(avc, writer, decoder, File(outputRoot, "avc"))
            checkSourcesUnchanged(sources)
            status("AVC baseline regression: full decode, PTS, masked PNG and video passed")

            checkRejected(tenBit, decoder, writer, File(outputRoot, "reject-10bit"))
            checkRejected(hdr, decoder, writer, File(outputRoot, "reject-hdr"))
            checkRejected(unknownColour, decoder, writer, File(outputRoot, "reject-unknown-colour"))
            checkSourcesUnchanged(sources)
            status("HEVC Main 10 SDR, Main 8 HDR and unknown colour were rejected without output")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Only paths created by this run; never sweep the app's shared sources folder.
            var cleanupError: Throwable? = null
            for (file in ownedSources + outputRoot) {
                try {
                    check(file.deleteRecursively() && !file.exists()) { "Synthetic fixture cleanup failed" }
                } catch (error: Throwable) {
                    if (cleanupError == null) cleanupError = error else cleanupError.addSuppressed(error)
                }
            }
            if (cleanupError != null) {
                if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }

    private data class Fixture(val source: ImportedSource, val file: File)

    private fun copyFixture(assetName: String, ownedSources: MutableList<File>): Fixture {
        val id = UUID.randomUUID().toString()
        val relativePath = "sources/$id.mp4"
        val file = File(targetContext.noBackupFilesDir, relativePath)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        check(file.createNewFile()) { "Synthetic source UUID collision" }
        ownedSources.add(file)
        context.assets.open(assetName).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        check(file.length() > 0) { "Empty fixture: $assetName" }
        val extractor = MediaExtractor()
        val metadata = try {
            extractor.setDataSource(file.absolutePath)
            val format = videoTrack(extractor).second
            SourceMetadata(
                mime = "video/mp4", byteLength = file.length(), sha256 = sha256(file),
                width = visibleSize(format, horizontal = true),
                height = visibleSize(format, horizontal = false),
                rotationDeg = format.intOrZero(MediaFormat.KEY_ROTATION),
                durationUs = format.getLong(MediaFormat.KEY_DURATION),
            )
        } finally {
            extractor.release()
        }
        return Fixture(ImportedSource(id, relativePath, assetName, metadata), file)
    }

    private fun checkFixture(fixture: Fixture, mime: String, hasAudio: Boolean) {
        val metadata = fixture.source.metadata
        check(metadata.width == 160 && metadata.height == 288 && metadata.rotationDeg == 0)
        check(metadata.durationUs == 1_000_000L) { "Fixture must have a one-second video track" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(fixture.file.absolutePath)
            val (track, format) = videoTrack(extractor)
            check(format.getString(MediaFormat.KEY_MIME) == mime)
            val audioMimes = (0 until extractor.trackCount).mapNotNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.takeIf { it.startsWith("audio/") }
            }
            check(audioMimes == if (hasAudio) listOf(MediaFormat.MIMETYPE_AUDIO_AAC) else emptyList<String>()) {
                "Unexpected fixture audio tracks"
            }
            check(extractor.trackCount == if (hasAudio) 2 else 1)
            extractor.selectTrack(track)
            val times = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) {
                times.add(extractor.sampleTime)
                check(times.size <= 10) { "Fixture contains extra video samples" }
                if (!extractor.advance()) break
            }
            // Extractor reads decoding order; HEVC B-frame input PTS may legitimately be reordered.
            check(times.distinct().size == 10 && times.sorted() == List(10) { it * 100_000L }) {
                "Fixture must contain ten distinct exact 10 fps PTS values"
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun checkSupported(
        fixture: Fixture,
        writer: SafeMediaWriter,
        decoder: VideoFrameDecoder,
        outputDirectory: File,
    ) {
        val metadata = fixture.source.metadata
        var decodedFrames = 0
        decoder.validate(fixture.file, metadata) { decodedFrames++ }
        check(decodedFrames == 10) { "Full input decoding did not reach all ten frames" }
        val frame = decoder.decode(fixture.source, 123_456)
        try {
            check(frame.presentationTimeUs == 200_000L) { "Decoder substituted requested time for real PTS" }
            check(frame.bitmap.width == 160 && frame.bitmap.height == 288)
            val last = decoder.decode(fixture.source, metadata.durationUs)
            try {
                check(last.presentationTimeUs == 900_000L) { "EOS must return the real last frame's PTS" }
            } finally {
                last.bitmap.recycle()
            }
            val stages = mutableListOf<SafeMediaWriter.Stage>()
            val masks = listOf(OpaqueMask(0.20f, 0.25f, 0.70f, 0.65f))
            val png = writer.writePng(frame.bitmap, masks, outputDirectory) { stages.add(it) }
            check(stages == SafeMediaWriter.Stage.entries.toList())
            checkCandidate(png, "image/png")
            check(png.durationUs == null)
            checkPngPixels(png.file, masks.single())

            stages.clear()
            val video = writer.writeVideo(fixture.file, 100_000, 900_000, masks, outputDirectory) {
                stages.add(it)
            }
            check(stages == SafeMediaWriter.Stage.entries.toList())
            checkCandidate(video, "video/mp4")
            checkVideoTracks(video)
            var outputFrames = 0
            decoder.validate(
                video.file,
                SourceMetadata("video/mp4", video.file.length(), video.sha256, video.width,
                    video.height, 0, checkNotNull(video.durationUs)),
            ) { image ->
                checkMaskedPixels(image, masks.single())
                outputFrames++
            }
            check(outputFrames == 8) { "Trim must decode all eight selected frames" }
            checkCancelledWrite(outputDirectory) { onStage ->
                writer.writePng(frame.bitmap, masks, outputDirectory, onStage)
            }
            checkCancelledWrite(outputDirectory) { onStage ->
                writer.writeVideo(fixture.file, 100_000, 900_000, masks, outputDirectory, onStage)
            }
            check(!frame.bitmap.isRecycled) { "Writer recycled caller-owned input" }
            checkCandidate(png, "image/png")
            checkCandidate(video, "video/mp4")
        } finally {
            frame.bitmap.recycle()
        }
    }

    private fun checkCandidate(candidate: SafeMediaWriter.CandidateMedia, mime: String) {
        check(candidate.file.isFile && candidate.file.length() > 0)
        check(candidate.file.canonicalFile.toPath().startsWith(targetContext.noBackupFilesDir.canonicalFile.toPath()))
        check(candidate.mimeType == mime && candidate.width == 160 && candidate.height == 288)
        check(candidate.sha256 == sha256(candidate.file)) { "Candidate digest does not describe actual bytes" }
    }

    private fun checkPngPixels(file: File, mask: OpaqueMask) {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            val rect = mask.toPixelRect(bitmap.width, bitmap.height)
            var visiblePixels = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                check(Color.alpha(pixel) == 255) { "PNG contains transparent pixels" }
                if (rect.contains(x, y)) check(pixel == Color.BLACK) { "PNG mask was not burned in" }
                else if (pixel != Color.BLACK) visiblePixels++
            }
            check(visiblePixels > 0) { "PNG unexpectedly erased all unmasked content" }
        } finally {
            bitmap.recycle()
        }
    }

    private fun checkVideoTracks(candidate: SafeMediaWriter.CandidateMedia) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(candidate.file.absolutePath)
            check(extractor.trackCount == 1) { "Output retained audio or another track" }
            val format = extractor.getTrackFormat(0)
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
            check(format.intOrZero(MediaFormat.KEY_ROTATION) == 0)
            val actualDuration = format.getLong(MediaFormat.KEY_DURATION)
            check(actualDuration in 1..800_000L && candidate.durationUs == actualDuration) {
                "Candidate duration must match its actual trimmed video track"
            }
        } finally {
            extractor.release()
        }
    }

    private fun checkMaskedPixels(image: Image, mask: OpaqueMask) {
        val crop = image.cropRect
        val rect = mask.toPixelRect(crop.width(), crop.height()).apply { inset(3, 3) }
        for (planeIndex in 0..2) {
            val plane = image.planes[planeIndex]
            val data = plane.buffer.duplicate()
            val origin = data.position()
            val divisor = if (planeIndex == 0) 1 else 2
            for (y in (crop.top + rect.top) / divisor..(crop.top + rect.bottom - 1) / divisor) {
                for (x in (crop.left + rect.left) / divisor..(crop.left + rect.right - 1) / divisor) {
                    val sample = data.get(origin + y * plane.rowStride + x * plane.pixelStride).toInt() and 255
                    check(if (planeIndex == 0) sample <= 58 else sample in 96..160) {
                        "An output frame has non-black pixels inside the fixed mask"
                    }
                }
            }
        }
    }

    /** Deterministic cancellation after real encoding/verification, before candidate publication. */
    private suspend fun checkCancelledWrite(
        outputDirectory: File,
        write: suspend ((SafeMediaWriter.Stage) -> Unit) -> SafeMediaWriter.CandidateMedia,
    ) = coroutineScope {
        val before = snapshot(outputDirectory)
        var reachedFinalizing = false
        val pending = async {
            val owner = currentCoroutineContext()
            write { stage ->
                if (stage == SafeMediaWriter.Stage.FINALIZING) {
                    reachedFinalizing = true
                    owner.cancel(CancellationException("Intentional smoke-check cancellation"))
                }
            }
        }
        try {
            pending.await()
            error("Cancelled write returned a candidate")
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
        check(reachedFinalizing && pending.isCancelled) { "Cancellation checkpoint was not exercised" }
        check(snapshot(outputDirectory) == before) { "Cancellation left partial output or changed an older candidate" }
    }

    private suspend fun checkRejected(
        fixture: Fixture,
        decoder: VideoFrameDecoder,
        writer: SafeMediaWriter,
        outputDirectory: File,
    ) {
        var frames = 0
        try {
            decoder.validate(fixture.file, fixture.source.metadata) { frames++ }
            error("Unsupported fixture decoded successfully: ${fixture.source.displayName}")
        } catch (_: FrameDecodeException) {
            check(frames == 0) { "Unsupported format reached decoded output" }
        }
        var rejected = false
        var startedRendering = false
        try {
            writer.writeVideo(fixture.file, 0, 1_000_000, emptyList(), outputDirectory) {
                if (it == SafeMediaWriter.Stage.RENDERING) startedRendering = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FrameDecodeException) {
            rejected = true
        } catch (_: IllegalStateException) {
            rejected = true
        }
        check(rejected && !startedRendering) { "Writer did not reject unsupported input before rendering" }
        check(snapshot(outputDirectory).isEmpty()) { "Rejected input left a candidate or partial file" }
    }

    private fun snapshot(directory: File): Map<String, String> =
        if (!directory.exists()) emptyMap() else checkNotNull(directory.listFiles()).associate {
            check(it.isFile) { "Unexpected nested output" }
            it.name to sha256(it)
        }

    private fun checkSourcesUnchanged(sources: List<Fixture>) {
        for (fixture in sources) {
            check(fixture.file.isFile && fixture.file.length() == fixture.source.metadata.byteLength &&
                sha256(fixture.file) == fixture.source.metadata.sha256) {
                "A writer operation changed or removed the source fixture"
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun status(message: String) {
        sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
}
