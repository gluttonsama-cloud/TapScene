package com.tapscene.recording

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.SourceRepository
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

/** Native PNG host checks only. No assertion here establishes device memory/latency or capture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameEvidenceThumbnailHostTest {
    private lateinit var root: File
    private lateinit var context: ContextWrapper

    @Before fun begin() {
        val application = RuntimeEnvironment.getApplication()
        root = File(application.noBackupFilesDir, "frame-thumbnail-fixture-${id()}")
        check(root.mkdirs())
        context = object : ContextWrapper(application) {
            override fun getNoBackupFilesDir(): File = root
            override fun getApplicationContext() = this
        }
        HostFileSyncShadow.begin(root)
    }

    @After fun end() {
        try { HostFileSyncShadow.reset() } finally { root.deleteRecursively() }
    }

    @Test fun opaqueRgbAndRgbaAllFiltersCrossPixelRowAndInflateBufferBoundaries() {
        for (channels in listOf(3, 4)) {
            for ((width, height) in listOf(1 to 5, 5 to 1, 19 to 7, 8193 to 5)) {
                for (filter in 0..4) {
                    val bytes = png(width, height, channels, filter = { filter })
                    FrameEvidencePng.decodeThumbnail(bytes, width, height).useBitmap { image ->
                        assertEquals(width, image.width)
                        assertEquals(height, image.height)
                        for ((x, y) in listOf(0 to 0, width - 1 to 0, 0 to height - 1, width - 1 to height - 1)) {
                            assertEquals(pixel(x, y), image.getPixel(x, y))
                        }
                    }
                }
                // Filters also change at row boundaries; IDAT chunks may split anywhere in zlib.
                FrameEvidencePng.decodeThumbnail(
                    png(width, height, channels, splitIdat = true), width, height,
                ).useBitmap { image -> assertEquals(pixel(width - 1, height - 1), image.getPixel(width - 1, height - 1)) }
            }
            // One-byte chunks split both the zlib header and trailer, not only its pixel payload.
            FrameEvidencePng.decodeThumbnail(
                png(19, 7, channels, splitIdat = true, idatChunkSize = 1), 19, 7,
            ).recycle()
        }
    }

    @Test fun transparentOriginalPixelOmittedByNativeSamplingStillFailsTheWholePngGate() {
        val width = 1024
        val height = 1024
        // Native sampling offsets may differ. Establish an actually omitted pixel rather than
        // assuming a particular Skia build starts sampling at a fixed coordinate.
        val omitted = checkNotNull(listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).firstOrNull { point ->
            nativeSampleIsOpaque(png(width, height, filter = { 0 }, transparent = point))
        }) { "Fixture must demonstrate a transparent original pixel absent from the native sample" }
        for (filter in 0..4) {
            val bytes = png(width, height, filter = { filter }, transparent = omitted)
            // Demonstrate why merely checking the sampled Bitmap's alpha is insufficient.
            assertTrue("Fixture transparency must fall outside the native sampled pixels", nativeSampleIsOpaque(bytes))
            expectFailure { FrameEvidencePng.decodeThumbnail(bytes, width, height).recycle() }
            expectFailure { FrameEvidencePng.decodeOriginal(bytes, width, height).recycle() }
        }
    }

    @Test fun malformedCrcLengthsDeflateFiltersColorInterlaceAndSuffixAreRejected() {
        val valid = png(7, 5)
        val malformed = listOf(
            valid.copyOf().apply { this[29] = (this[29].toInt() xor 1).toByte() }, // IHDR CRC
            valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0), // bytes after IEND
            valid.copyOf().apply { this[8] = 0x7f }, // impossible chunk length
            png(7, 5, rawChange = { it.copyOf(it.size - 1) }),
            png(7, 5, rawChange = { it + byteArrayOf(0) }),
            png(7, 5, rawChange = { it.copyOf().apply { this[0] = 5 } }),
            png(7, 5, compressedChange = { it.copyOf(it.size - 1) }),
            png(7, 5, compressedChange = { it + byteArrayOf(0) }),
            png(7, 5, compressedChange = { it + deflate(byteArrayOf(0)) }),
            png(7, 5, headerChange = { it[8] = 16 }), // unsupported bit depth
            png(7, 5, headerChange = { it[9] = 3 }), // palette
            png(7, 5, headerChange = { it[12] = 1 }), // interlace
            png(7, 5, channels = 3, beforePixels = chunk("tRNS", byteArrayOf(0, 0, 0, 0, 0, 0))),
            png(7, 5, beforePixels = chunk("tEXt", "private metadata".toByteArray())),
            png(7, 5, beforePixels = chunk("sRGB", byteArrayOf(4))),
            png(7, 5, beforePixels = chunk("sBIT", byteArrayOf(8, 8, 8))),
            png(7, 5, afterPixels = chunk("sRGB", byteArrayOf(0))),
            png(7, 5, afterPixels = chunk("IDAT", byteArrayOf(0))),
        )
        malformed.forEachIndexed { index, bytes ->
            expectFailure("malformed PNG $index") { FrameEvidencePng.decodeThumbnail(bytes, 7, 5).recycle() }
        }
        expectFailure { FrameEvidencePng.decodeThumbnail(valid, 8, 5).recycle() }
        expectFailure { FrameEvidencePng.decodeThumbnail(valid, 7, 6).recycle() }
    }

    @Test fun thumbnailUsesBoundedArgbPixelsAndLeavesOriginalEvidenceAndFullDecodeUnchanged() {
        val fixture = ready(1001, 1003)
        val before = fixture.manifest.readBytes()
        val original = fixture.pngFile.readBytes()
        var sourceReads = 0
        val accessor = FrameSourceAccessor { project, session, source ->
            assertEquals(fixture.source.projectId, project)
            assertEquals(fixture.source.sessionId, session)
            assertEquals(fixture.source.sourceId, source)
            sourceReads++
            fixture.source
        }
        repeat(2) {
            fixture.store.readUnreviewedThumbnail(fixture.candidate, accessor).useBitmap { image ->
                assertEquals(Bitmap.Config.ARGB_8888, image.config)
                assertTrue(image.width.toLong() * image.height <= 220_000L)
                assertTrue(image.width < fixture.candidate.width && image.height < fixture.candidate.height)
                assertTrue(image.width in 250..251 && image.height in 250..251)
            }
        }
        assertEquals("No source observation is cached between requests", 2, sourceReads)
        var full: Bitmap? = null
        fixture.store.withDecodedFrame(fixture.candidate, fixture.accessor()) {
            full = it
            assertEquals(1001, it.width)
            assertEquals(1003, it.height)
            assertEquals(pixel(1000, 1002), it.getPixel(1000, 1002))
        }
        assertTrue(full!!.isRecycled)
        assertArrayEquals(original, fixture.pngFile.readBytes())
        assertArrayEquals(before, fixture.manifest.readBytes())
        assertEquals(FrameCandidateResult.Available(fixture.candidate),
            fixture.store.readCandidate(fixture.source.sessionId, fixture.candidate.ticket.ticketId, fixture.accessor()))
        // Odd, thin dimensions exercise both axes without relying on exotic codec dimensions.
        for ((width, height) in listOf(16_385 to 15, 15 to 16_385)) {
            FrameEvidencePng.decodeThumbnail(png(width, height), width, height).useBitmap {
                assertTrue(it.width > 0 && it.height > 0 && it.width.toLong() * it.height <= 220_000L)
            }
        }
    }

    @Test fun thumbnailRequiresExactCandidateFreshOwnershipShaGeometryAndContainerSample() {
        val fixture = ready()
        val current = fixture.candidate
        val alteredCandidates = listOf(
            current.copy(pngSha256 = "b".repeat(64)),
            current.copy(width = current.width + 1),
            current.copy(height = current.height + 1),
            current.copy(encoderPtsUs = current.encoderPtsUs + 1),
            current.copy(muxSampleOrdinal = 0),
            current.copy(containerPtsUs = current.containerPtsUs + 1),
            current.copy(sourceSha256 = "b".repeat(64)),
            current.copy(ticket = current.ticket.copy(epoch = current.ticket.epoch + 1)),
            current.copy(ticket = current.ticket.copy(ticketId = id())),
            current.copy(ticket = current.ticket.copy(action = current.ticket.action.copy(runId = id()))),
            current.copy(ticket = current.ticket.copy(geometry = current.ticket.geometry.copy(geometryId = id()))),
        )
        alteredCandidates.forEach { candidate ->
            expectFailure { fixture.store.readUnreviewedThumbnail(candidate, fixture.accessor()).recycle() }
        }
        val source = fixture.source
        val alteredSources = listOf(
            source.copy(projectId = id()), source.copy(sessionId = id()), source.copy(sourceId = id()),
            source.copy(sourceSha256 = "b".repeat(64)), source.copy(frameWidth = 8), source.copy(frameHeight = 8),
            source.copy(samplePtsUs = emptyList()), source.copy(samplePtsUs = listOf(7_000)),
            source.copy(samplePtsUs = listOf(0, 7_001, 50_000)),
        )
        alteredSources.forEach { observed ->
            expectFailure { fixture.store.readUnreviewedThumbnail(current, FrameSourceAccessor { _, _, _ -> observed }).recycle() }
        }
        expectFailure { fixture.store.readUnreviewedThumbnail(current, FrameSourceAccessor { _, _, _ -> null }).recycle() }
        val pngBytes = fixture.pngFile.readBytes()
        fixture.pngFile.writeBytes(pngBytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        expectFailure { fixture.store.readUnreviewedThumbnail(current, fixture.accessor()).recycle() }
        fixture.pngFile.writeBytes(pngBytes)
        fixture.store.readUnreviewedThumbnail(current, fixture.accessor()).recycle()
        // Even with internally consistent persisted SHA/length, malformed pixels are never trusted.
        val corrupt = png(current.width, current.height, transparent = 1 to 1)
        fixture.pngFile.writeBytes(corrupt)
        val changed = JSONObject(fixture.manifest.readText())
        changed.getJSONArray("frames").getJSONObject(0).getJSONObject("png")
            .put("sha256", hash(corrupt)).put("bytes", corrupt.size)
        fixture.manifest.writeText(changed.toString())
        expectFailure { fixture.store.readUnreviewedThumbnail(current.copy(pngSha256 = hash(corrupt)), fixture.accessor()).recycle() }
        assertTrue(fixture.store.readCandidate(source.sessionId, current.ticket.ticketId, fixture.accessor()) is FrameCandidateResult.Missing)
    }

    @Test fun sourceObservationIsOutsideEvidenceLockAndChangedOrRevokedSessionsFailClosed() {
        val fixture = ready()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val accessor = FrameSourceAccessor { _, session, _ ->
                // This would time out if a repository callback ran while holding the evidence lock.
                assertEquals(fixture.candidate.ticket.ticketId,
                    executor.submit<String?> { fixture.store.listBoundaries(session).single().ticketId }.get(5, TimeUnit.SECONDS))
                fixture.source
            }
            fixture.store.readUnreviewedThumbnail(fixture.candidate, accessor).recycle()
        } finally { executor.shutdownNow() }
        val changed = FrameSourceAccessor { _, _, _ ->
            fixture.store.missing(fixture.candidate.ticket, FrameMissingReason.PngFailed)
            fixture.source
        }
        expectFailure { fixture.store.readUnreviewedThumbnail(fixture.candidate, changed).recycle() }
        val revoked = ready()
        val deleting = FrameSourceAccessor { _, _, _ ->
            revoked.store.deleteAfterSourceCommit(revoked.source.sourceId)
            revoked.source
        }
        expectFailure { revoked.store.readUnreviewedThumbnail(revoked.candidate, deleting).recycle() }
        assertFalse(revoked.pngFile.exists())
        expectFailure { revoked.store.readUnreviewedThumbnail(revoked.candidate, revoked.accessor()).recycle() }
    }

    @Test fun cancellationPropagatesThroughSourceHashAndEveryPngDecodeCheckpoint() {
        val cancellation = CancellationException("fixture cancellation")
        val fixture = ready()
        val cancelledSource = FrameSourceAccessor { _, _, _ -> throw cancellation }
        expectCancellation(cancellation) { fixture.store.readUnreviewedThumbnail(fixture.candidate, cancelledSource).recycle() }
        expectCancellation(cancellation) {
            fixture.store.readCandidate(fixture.source.sessionId, fixture.candidate.ticket.ticketId, cancelledSource)
        }
        expectCancellation(cancellation) {
            SourceRepository(context).frameEvidenceSourceAccessor { throw cancellation }
                .registeredSource(fixture.source.projectId, fixture.source.sessionId, fixture.source.sourceId)
        }
        val file = File(root, "source-hash-fixture.mp4").apply { writeBytes(ByteArray(256 * 1024) { it.toByte() }) }
        var hashChecks = 0
        expectCancellation(cancellation) {
            FrameRecordingBackend.sha256(file) { if (++hashChecks == 5) throw cancellation }
        }
        assertEquals(5, hashChecks)
        assertEquals(hash(file.readBytes()), FrameRecordingBackend.sha256(file))
        expectCancellation(cancellation) { FrameRecordingBackend.readContainerSamples(file, 4, 4) { throw cancellation } }

        val bytes = png(19, 7, splitIdat = true)
        var checkpoints = 0
        FrameEvidencePng.decodeThumbnail(bytes, 19, 7) { checkpoints++ }.recycle()
        assertTrue(checkpoints > 20)
        for (stopAt in 1..checkpoints) {
            var calls = 0
            expectCancellation(cancellation) {
                FrameEvidencePng.decodeThumbnail(bytes, 19, 7) { if (++calls == stopAt) throw cancellation }.recycle()
            }
            assertEquals(stopAt, calls)
        }
        val original = fixture.manifest.readBytes()
        var storeChecks = 0
        fixture.store.readUnreviewedThumbnail(fixture.candidate, fixture.accessor()) { storeChecks++ }.recycle()
        // The final checkpoint occurs after native allocation and the fresh session recheck.
        var calls = 0
        expectCancellation(cancellation) {
            fixture.store.readUnreviewedThumbnail(fixture.candidate, fixture.accessor()) {
                if (++calls == storeChecks) throw cancellation
            }.recycle()
        }
        assertArrayEquals(original, fixture.manifest.readBytes())
        fixture.store.readUnreviewedThumbnail(fixture.candidate, fixture.accessor()).recycle()
    }

    @Test
    @Config(shadows = [HostFileSyncShadow::class, TraversalExtractorShadow::class])
    fun containerTraversalCancellationStopsReadingAndReleasesExtractor() {
        // A synthetic extractor isolates traversal/cancellation. This is not a real MP4 decode.
        val file = File(root, "extractor-traversal.mp4").apply { writeBytes(byteArrayOf(1)) }
        val cancellation = CancellationException("cancel after first sample")
        TraversalExtractorShadow.reset()
        expectCancellation(cancellation) {
            FrameRecordingBackend.readContainerSamples(file, 4, 4) {
                if (TraversalExtractorShadow.reads == 1) throw cancellation
            }
        }
        assertEquals(1, TraversalExtractorShadow.reads)
        assertEquals(0, TraversalExtractorShadow.advances)
        assertEquals(1, TraversalExtractorShadow.releases)
        TraversalExtractorShadow.reset()
        assertEquals(listOf(0L, 7_000L, 50_000L), FrameRecordingBackend.readContainerSamples(file, 4, 4))
        assertEquals(3, TraversalExtractorShadow.reads)
        assertEquals(3, TraversalExtractorShadow.advances)
        assertEquals(1, TraversalExtractorShadow.releases)
    }

    @Implements(MediaExtractor::class)
    class TraversalExtractorShadow {
        private var index = 0
        @Implementation fun __constructor__() = Unit
        @Implementation fun setDataSource(path: String) { check(File(path).isFile) }
        @Implementation fun getTrackCount(): Int = 1
        @Implementation fun getTrackFormat(track: Int): MediaFormat {
            check(track == 0)
            return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 4, 4).apply {
                setLong(MediaFormat.KEY_DURATION, 60_000)
            }
        }
        @Implementation fun selectTrack(track: Int) { check(track == 0) }
        @Implementation fun getSampleTrackIndex(): Int = if (index < samples.size) 0 else -1
        @Implementation fun getSampleTime(): Long = samples.getOrNull(index) ?: -1
        @Implementation fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
            check(index < samples.size)
            reads++
            buffer.put(offset, 1)
            return 1
        }
        @Implementation fun advance(): Boolean { advances++; index++; return index < samples.size }
        @Implementation fun release() { releases++ }

        companion object {
            private val samples = listOf(0L, 7_000L, 50_000L)
            var reads = 0
            var advances = 0
            var releases = 0
            @JvmStatic @Resetter fun reset() { reads = 0; advances = 0; releases = 0 }
        }
    }

    private data class Fixture(
        val store: FrameEvidenceStore,
        val candidate: FrameEvidenceCandidate,
        val source: FrameRegisteredSource,
        val manifest: File,
        val pngFile: File,
    ) {
        fun accessor() = FrameSourceAccessor { _, _, _ -> source }
    }

    private fun ready(width: Int = 4, height: Int = 4): Fixture {
        val project = id()
        val session = id()
        val source = id()
        val store = FrameEvidenceStore(context)
        store.createSession(project, session, source)
        val ticket = FrameTicket(id(), FrameAnchorAction(id(), id(), session, source, 4), FrameBoundary.Before,
            3, 1, 1, 900_000_001, 40_000, FrameGeometry.fitCenter(width, height, width, height), FrameFreshness.Fresh)
        store.persistTicket(ticket)
        store.recordPng(ticket, png(width, height))
        store.markEncoder(session, 1, 40_000, 1)
        store.seal(session, listOf(0, 7_000, 50_000), "a".repeat(64))
        store.registered(session, source, "a".repeat(64))
        val observed = FrameRegisteredSource(project, session, source, "a".repeat(64), width, height, listOf(0, 7_000, 50_000))
        val candidate = (store.readCandidate(session, ticket.ticketId, FrameSourceAccessor { _, _, _ -> observed })
            as FrameCandidateResult.Available).candidate
        return Fixture(store, candidate, observed, File(root, "frame-evidence/$session/evidence.json"),
            File(root, "frame-evidence/$session/${ticket.ticketId}.png"))
    }

    private fun png(width: Int, height: Int, channels: Int = 4, filter: (Int) -> Int = { it % 5 },
        transparent: Pair<Int, Int>? = null, splitIdat: Boolean = false, idatChunkSize: Int = 17,
        rawChange: (ByteArray) -> ByteArray = { it }, compressedChange: (ByteArray) -> ByteArray = { it },
        headerChange: (ByteArray) -> Unit = {}, beforePixels: ByteArray = byteArrayOf(),
        afterPixels: ByteArray = byteArrayOf()): ByteArray {
        val raw = ByteArrayOutputStream()
        var previous = ByteArray(width * channels)
        for (y in 0 until height) {
            val row = ByteArray(width * channels)
            for (x in 0 until width) {
                val color = pixel(x, y)
                row[x * channels] = Color.red(color).toByte()
                row[x * channels + 1] = Color.green(color).toByte()
                row[x * channels + 2] = Color.blue(color).toByte()
                if (channels == 4) row[x * channels + 3] = (if (transparent == (x to y)) 0 else 255).toByte()
            }
            val kind = filter(y)
            raw.write(kind)
            for (index in row.indices) {
                val left = if (index >= channels) row[index - channels].toInt() and 255 else 0
                val up = previous[index].toInt() and 255
                val upperLeft = if (index >= channels) previous[index - channels].toInt() and 255 else 0
                val predictor = when (kind) {
                    0 -> 0; 1 -> left; 2 -> up; 3 -> (left + up) / 2
                    else -> paeth(left, up, upperLeft)
                }
                raw.write(((row[index].toInt() and 255) - predictor) and 255)
            }
            previous = row
        }
        val header = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8,
                if (channels == 4) 6 else 2, 0, 0, 0)) }
        }.toByteArray().also(headerChange)
        val compressed = compressedChange(deflate(rawChange(raw.toByteArray())))
        val idat = if (!splitIdat) chunk("IDAT", compressed) else {
            ByteArrayOutputStream().also { out ->
                var offset = 0
                while (offset < compressed.size) {
                    val count = minOf(idatChunkSize, compressed.size - offset)
                    out.write(chunk("IDAT", compressed.copyOfRange(offset, offset + count)))
                    offset += count
                }
            }.toByteArray()
        }
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) +
            beforePixels + idat + afterPixels + chunk("IEND", byteArrayOf())
    }

    private fun nativeSampleIsOpaque(bytes: ByteArray): Boolean {
        val image = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = 4
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }))
        try {
            val row = IntArray(image.width)
            for (y in 0 until image.height) {
                image.getPixels(row, 0, row.size, 0, y, row.size, 1)
                if (row.any { Color.alpha(it) != 255 }) return false
            }
            return true
        } finally { image.recycle() }
    }

    private fun deflate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DeflaterOutputStream(output).use { it.write(bytes) }
    }.toByteArray()

    private fun chunk(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(name); update(payload) }
        DataOutputStream(output).use { it.writeInt(payload.size); it.write(name); it.write(payload); it.writeInt(crc.value.toInt()) }
    }.toByteArray()

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun pixel(x: Int, y: Int): Int = Color.rgb((x * 17 + y * 3) and 255, (x * 5 + y * 11) and 255, (x * 7 + y * 13) and 255)
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun id(): String = UUID.randomUUID().toString()
    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }

    private fun expectFailure(label: String = "operation", block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected $label to fail closed", failed)
    }

    private fun expectCancellation(expected: CancellationException, block: () -> Unit) {
        try { block(); fail("Cancellation was swallowed") }
        catch (actual: CancellationException) { assertSame(expected, actual) }
    }
}
