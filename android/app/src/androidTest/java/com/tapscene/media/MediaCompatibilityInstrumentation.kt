package com.tapscene.media

import android.app.Activity
import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.Image
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
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
 * Platform-only device smoke checks. Run with adb am instrument -w; no extra test framework.
 * Synthetic fixtures bypass the system picker and SourceImporter's provider-copy/registration
 * flow. Those flows and phone-specific hardware support still need separate device checks.
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
            check(Looper.myLooper() != Looper.getMainLooper()) { "Checks must not block the main looper" }
            runBlocking(Dispatchers.IO) { withTimeout(240_000) { runChecks() } }
            result.putString("stream", "TAPSCENE_MEDIA_CHECKS_OK (read individual capability results)")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "TAPSCENE_MEDIA_CHECKS_FAILED")
            result.putString("stack", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private suspend fun runChecks() {
        val outputRoot = File(targetContext.noBackupFilesDir, "media-checks-${UUID.randomUUID()}")
        check(outputRoot.mkdir()) { "Could not reserve private smoke-check directory" }
        val sources = mutableListOf<Fixture>()
        val ownedSources = mutableListOf<File>()
        var failure: Throwable? = null
        try {
            val writer = SafeMediaWriter(targetContext)
            val decoder = VideoFrameDecoder(targetContext)
            for (spec in fixtureSpecs()) {
                val fixture = copyFixture(spec, ownedSources).also(sources::add)
                checkFixture(fixture)
                val output = File(outputRoot, spec.name.removeSuffix(".mp4"))
                when {
                    spec.hdr && Build.VERSION.SDK_INT < 29 -> {
                        checkHdrApiGuard(fixture, decoder, writer, output)
                        status("NOT_SUPPORTED ${spec.name}: API ${Build.VERSION.SDK_INT} < 29; explicit HDR guard and cleanup passed")
                    }
                    spec.optionalProfile != null && advertisedDecoder(fixture, spec.optionalProfile) == null -> {
                        status("NOT_SUPPORTED ${spec.name}: no decoder advertises profile=${spec.optionalProfile} at ${spec.width}x${spec.height}; no decode/export pass claimed")
                    }
                    else -> {
                        // An advertised decoder is only a prerequisite. Runtime failures fail the
                        // check; never relabel arbitrary exceptions as an unsupported device.
                        if (spec.hdr) status("CHECK ${spec.name}: API ${Build.VERSION.SDK_INT}; actual Surface/GL tone mapping is required")
                        checkSupported(fixture, writer, decoder, output, checkCancellation = spec.name == "avc-sdr.mp4")
                        status("PASS ${spec.name}: sampled Surface decode, actual millisecond PTS, geometry, PNG and every silent H.264 output frame")
                    }
                }
                checkSourcesUnchanged(sources)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var cleanupError: Throwable? = null
            try {
                checkSourcesUnchanged(sources)
            } catch (error: Throwable) {
                cleanupError = error
            }
            // Only paths exclusively created by this run, never sweep the shared sources folder.
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

    /** PTS are fixture facts from FFprobe, not frame-index / nominal-fps calculations. */
    private data class FixtureSpec(
        val name: String,
        val mime: String,
        val width: Int = 160,
        val height: Int = 288,
        val rotation: Int = 0,
        val sar: Float = 1f,
        val surfaceWidth: Int = 160,
        val surfaceHeight: Int = 288,
        val durationUs: Long = 1_000_000L,
        val ptsUs: List<Long> = listOf(0, 100_000, 200_000, 300_000, 400_000, 500_000, 600_000, 700_000, 800_000, 900_000),
        val hasAudio: Boolean = false,
        val optionalProfile: Int? = null,
        val hdr: Boolean = false,
        val fullClip: Boolean = false,
    )

    private fun fixtureSpecs() = listOf(
        FixtureSpec("avc-sdr.mp4", MediaFormat.MIMETYPE_VIDEO_AVC),
        FixtureSpec("hevc-sdr.mp4", MediaFormat.MIMETYPE_VIDEO_HEVC, hasAudio = true),
        FixtureSpec("hevc-unknown-colour.mp4", MediaFormat.MIMETYPE_VIDEO_HEVC),
        FixtureSpec("hevc-10bit-sdr.mp4", MediaFormat.MIMETYPE_VIDEO_HEVC,
            optionalProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
        FixtureSpec("avc-long-screen-unknown-colour.mp4", MediaFormat.MIMETYPE_VIDEO_AVC,
            width = 1440, height = 3200, surfaceWidth = 1080, surfaceHeight = 2400,
            ptsUs = listOf(0, 250_000, 500_000, 750_000), fullClip = true),
        FixtureSpec("avc-rotated-sar-vfr.mp4", MediaFormat.MIMETYPE_VIDEO_AVC,
            width = 180, height = 320, rotation = 270, sar = 1.5f,
            surfaceWidth = 320, surfaceHeight = 270, durationUs = 933_333,
            ptsUs = listOf(0, 66_667, 233_333, 466_667, 500_000, 900_000), fullClip = true),
        FixtureSpec("hevc-hdr.mp4", MediaFormat.MIMETYPE_VIDEO_HEVC,
            optionalProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, hdr = true),
    )

    private data class Fixture(val source: ImportedSource, val file: File, val spec: FixtureSpec, val ptsUs: List<Long>)

    private fun copyFixture(spec: FixtureSpec, ownedSources: MutableList<File>): Fixture {
        val id = UUID.randomUUID().toString()
        val relativePath = "sources/$id.mp4"
        val file = File(targetContext.noBackupFilesDir, relativePath)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        check(file.createNewFile()) { "Synthetic source UUID collision" }
        ownedSources.add(file)
        context.assets.open(spec.name).use { input -> file.outputStream().use { input.copyTo(it) } }
        check(file.length() > 0) { "Empty fixture: ${spec.name}" }
        val metadata = MediaInputPolicy.inspect(file, file.length(), sha256(file)).metadata
        return Fixture(ImportedSource(id, relativePath, spec.name, metadata), file, spec, samplePts(file))
    }

    private fun checkFixture(fixture: Fixture) {
        val metadata = fixture.source.metadata
        val spec = fixture.spec
        check(metadata.width == spec.width && metadata.height == spec.height && metadata.rotationDeg == spec.rotation)
        check(abs(metadata.pixelWidthHeightRatio - spec.sar) < 0.0001f)
        check(abs(metadata.durationUs - spec.durationUs) <= 1) { "Fixture duration differs from its probed track" }
        // FFprobe rounds a 1/30000 timebase to microseconds; Android may truncate by one us.
        check(fixture.ptsUs.size == spec.ptsUs.size && fixture.ptsUs.zip(spec.ptsUs).all { (a, b) -> abs(a - b) <= 1 }) {
            "Actual fixture PTS differ from recorded FFprobe sample times"
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(fixture.file.absolutePath)
            check(videoTrack(extractor).second.getString(MediaFormat.KEY_MIME) == spec.mime)
            val audioMimes = (0 until extractor.trackCount).mapNotNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.takeIf { it.startsWith("audio/") }
            }
            check(audioMimes == if (spec.hasAudio) listOf(MediaFormat.MIMETYPE_AUDIO_AAC) else emptyList<String>())
            check(extractor.trackCount == if (spec.hasAudio) 2 else 1)
        } finally {
            extractor.release()
        }
    }

    private fun advertisedDecoder(fixture: Fixture, profile: Int): String? {
        val format = MediaFormat.createVideoFormat(fixture.spec.mime, fixture.spec.width, fixture.spec.height)
        format.setInteger(MediaFormat.KEY_PROFILE, profile)
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
    }

    private suspend fun checkSupported(
        fixture: Fixture,
        writer: SafeMediaWriter,
        decoder: VideoFrameDecoder,
        outputDirectory: File,
        checkCancellation: Boolean,
    ) {
        val metadata = fixture.source.metadata
        decoder.validate(fixture.file, metadata) // First/middle/last Surface samples, not full source decode.
        for (timeUs in listOf(0L, metadata.durationUs)) {
            val edge = decoder.decode(fixture.source, timeUs)
            try {
                checkFrameIdentity(edge, fixture, timeUs)
                val expected = if (timeUs == 0L) fixture.ptsUs.first() else fixture.ptsUs.last()
                check(edge.presentationTimeUs == expected / 1_000 * 1_000) { "First/last frame lost its actual PTS" }
            } finally {
                edge.bitmap.recycle()
            }
        }
        val requestedUs = 123_456L
        val frame = decoder.decode(fixture.source, requestedUs)
        try {
            checkFrameIdentity(frame, fixture, requestedUs)
            check(frame.presentationTimeUs != requestedUs) { "Decoder relabeled a frame with the requested time" }
            val stages = mutableListOf<SafeMediaWriter.Stage>()
            val mask = OpaqueMask(0.20f, 0.25f, 0.70f, 0.65f)
            val masks = listOf(mask)
            val png = writer.writePng(frame.bitmap, masks, outputDirectory) { stages.add(it) }
            check(stages == SafeMediaWriter.Stage.entries.toList())
            checkCandidate(png, "image/png")
            check(png.durationUs == null && png.width == fixture.spec.surfaceWidth && png.height == fixture.spec.surfaceHeight)
            checkPngPixels(png.file, mask)

            val startUs = if (fixture.spec.fullClip) 0L else 100_000L
            val endUs = if (fixture.spec.fullClip) metadata.durationUs else 900_000L
            stages.clear()
            val video = writer.writeVideo(fixture.file, startUs, endUs, masks, outputDirectory) { stages.add(it) }
            check(stages == SafeMediaWriter.Stage.entries.toList())
            checkCandidate(video, "video/mp4")
            check(video.width <= fixture.spec.surfaceWidth && video.height <= fixture.spec.surfaceHeight)
            checkVideoTracks(video, endUs - startUs)
            val outputMask = fittedMask(mask, fixture.spec.surfaceWidth, fixture.spec.surfaceHeight, video.width, video.height)
            var outputFrames = 0
            val decodedPts = decoder.validateOutput(
                video.file,
                SourceMetadata("video/mp4", video.file.length(), video.sha256, video.width,
                    video.height, 0, checkNotNull(video.durationUs)),
            ) { image ->
                checkMaskedPixels(image, outputMask)
                outputFrames++
            }
            val writtenPts = samplePts(video.file)
            check(outputFrames == writtenPts.size && decodedPts == writtenPts) { "Every written output frame must be decoded and inspected" }
            // Preserve the actual selected VFR samples, including the final sparse frame.
            // The encoder rate hint is not a frame-index clock or permission to drop frames.
            val selectedPts = fixture.ptsUs.filter { it >= startUs && it < endUs }.map { it - startUs }
            check(decodedPts.size == selectedPts.size && decodedPts.zip(selectedPts).all { (actual, expected) -> abs(actual - expected) < 1_000 }) {
                "Trimmed output lost a fixture frame or changed its actual presentation time"
            }
            if (checkCancellation) {
                checkCancelledWrite(outputDirectory) { onStage -> writer.writePng(frame.bitmap, masks, outputDirectory, onStage) }
                checkCancelledWrite(outputDirectory) { onStage -> writer.writeVideo(fixture.file, startUs, endUs, masks, outputDirectory, onStage) }
            }
            check(!frame.bitmap.isRecycled) { "Writer recycled caller-owned input" }
            checkCandidate(png, "image/png")
            checkCandidate(video, "video/mp4")
        } finally {
            frame.bitmap.recycle()
        }
    }

    private fun checkFrameIdentity(frame: DecodedFrame, fixture: Fixture, requestedUs: Long) {
        check(frame.timePrecisionUs == 1_000L && frame.presentationTimeUs % 1_000L == 0L) {
            "Media3 Frame.presentationTimeMs must be represented with explicit millisecond precision"
        }
        val actualSample = fixture.ptsUs.firstOrNull { it / 1_000 * 1_000 == frame.presentationTimeUs }
        check(actualSample != null && actualSample - frame.presentationTimeUs in 0L..999L) {
            "Frame timestamp does not identify an actual source PTS at the advertised precision"
        }
        val requestMs = requestedUs.coerceAtMost(fixture.source.metadata.durationUs - 1) / 1_000
        val before = fixture.ptsUs.lastOrNull { it / 1_000 <= requestMs }
        val after = fixture.ptsUs.firstOrNull { it / 1_000 >= requestMs }
        check(actualSample == before || actualSample == after) { "Frame is not adjacent to the requested position" }
        check(frame.bitmap.width == fixture.spec.surfaceWidth && frame.bitmap.height == fixture.spec.surfaceHeight) {
            "Surface did not apply source rotation/SAR and bounded aspect-preserving geometry"
        }
    }

    private fun checkCandidate(candidate: SafeMediaWriter.CandidateMedia, mime: String) {
        check(candidate.file.isFile && candidate.file.length() > 0)
        check(candidate.file.canonicalFile.toPath().startsWith(targetContext.noBackupFilesDir.canonicalFile.toPath()))
        check(candidate.mimeType == mime && minOf(candidate.width, candidate.height) in 1..1080 && maxOf(candidate.width, candidate.height) <= 2400)
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

    private fun checkVideoTracks(candidate: SafeMediaWriter.CandidateMedia, maxDurationUs: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(candidate.file.absolutePath)
            check(extractor.trackCount == 1) { "Output retained audio or another track" }
            val format = extractor.getTrackFormat(0)
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
            check(format.intOrZero(MediaFormat.KEY_ROTATION) == 0)
            check(visibleSize(format, true) == candidate.width && visibleSize(format, false) == candidate.height)
            check(format.intOrZero(MediaFormat.KEY_COLOR_TRANSFER) !in setOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG))
            val actualDuration = format.getLong(MediaFormat.KEY_DURATION)
            check(actualDuration in 1..(maxDurationUs + 1_000L) && candidate.durationUs == actualDuration) {
                "Candidate duration must match its actual trimmed video track"
            }
        } finally {
            extractor.release()
        }
    }

    private fun fittedMask(mask: OpaqueMask, contentWidth: Int, contentHeight: Int, width: Int, height: Int): OpaqueMask {
        val scale = minOf(width.toDouble() / contentWidth, height.toDouble() / contentHeight)
        val w = contentWidth * scale / width
        val h = contentHeight * scale / height
        return OpaqueMask(((1 - w) / 2 + mask.left * w).toFloat(), ((1 - h) / 2 + mask.top * h).toFloat(),
            ((1 - w) / 2 + mask.right * w).toFloat(), ((1 - h) / 2 + mask.bottom * h).toFloat())
    }

    private fun checkMaskedPixels(image: Image, mask: OpaqueMask) {
        val crop = image.cropRect
        val rect = mask.toPixelRect(crop.width(), crop.height()).apply { inset(3, 3) }
        check(!rect.isEmpty) { "Mask inspection must contain actual output pixels" }
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

    private suspend fun checkHdrApiGuard(fixture: Fixture, decoder: VideoFrameDecoder, writer: SafeMediaWriter, directory: File) {
        try {
            decoder.validate(fixture.file, fixture.source.metadata)
            error("API <29 decoded explicit HDR without supported tone mapping")
        } catch (expected: FrameDecodeException) {
            check(expected.message.orEmpty().contains("hdr_capability")) { "Wrong HDR failure stage: ${expected.message}" }
        }
        try {
            writer.writeVideo(fixture.file, 0, fixture.source.metadata.durationUs, emptyList(), directory)
            error("API <29 exported explicit HDR without supported tone mapping")
        } catch (expected: MediaExportException) {
            check(expected.message.orEmpty().contains("HDR 色调映射准备")) { "Wrong HDR export failure stage: ${expected.message}" }
        }
        check(snapshot(directory).isEmpty()) { "Unsupported HDR left an unpublished output" }
    }

    private fun samplePts(file: File): List<Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(videoTrack(extractor).first)
            val times = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) {
                times.add(extractor.sampleTime)
                check(times.size <= 100) { "Synthetic fixture/output has unexpected extra samples" }
                if (!extractor.advance()) break
            }
            check(times.isNotEmpty() && times.distinct().size == times.size)
            return times.sorted() // Container packets can be in decode order for B frames.
        } finally {
            extractor.release()
        }
    }

    private fun snapshot(directory: File): Map<String, String> =
        if (!directory.exists()) emptyMap() else checkNotNull(directory.listFiles()).associate {
            check(it.isFile) { "Unexpected nested output" }
            it.name to sha256(it)
        }

    private fun checkSourcesUnchanged(sources: List<Fixture>) {
        for (fixture in sources) {
            check(fixture.file.isFile && fixture.file.length() == fixture.source.metadata.byteLength && sha256(fixture.file) == fixture.source.metadata.sha256) {
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
