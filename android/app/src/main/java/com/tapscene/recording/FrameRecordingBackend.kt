package com.tapscene.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Click-chain-only backend. One display feeds one normalized FBO and its AVC input surface. */
@android.annotation.TargetApi(33)
internal class FrameRecordingBackend private constructor(
    private val context: Context,
    private val projectId: String,
    private val session: RecordingClickSession,
    private val canvas: RecordingCanvas,
    override val encoding: RecordingEncoding,
    private val file: File,
    private val onReady: () -> Unit,
    private val onError: () -> Unit,
) : RecordingBackend {
    private lateinit var codec: MediaCodec
    private lateinit var muxer: MediaMuxer
    private lateinit var codecSurface: Surface
    private lateinit var renderer: RecordingGlRenderer
    private val ledger = RecordingPresentationLedger()
    private val presentationClock = RecordingPresentationClock(encoding.fps)
    private var sourceSequence = 0L
    private var lastSourceTimestampNs = 0L
    @Volatile private var endPresentationPtsUs: Long? = null
    private val presentationTick = Runnable { presentLatest() }
    private val evidence by lazy { FrameEvidenceStore(context) }
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256)) { task ->
        Thread(task, "TapSceneFrameEvidence").apply { isDaemon = true }
    }
    private val pngPermits = Semaphore(2)
    private val geometry = FrameGeometry.fitCenter(canvas.width, canvas.height, encoding.width, encoding.height)
    private var evidenceAvailable = true
    @Volatile private var accepting = false
    @Volatile private var captureClosed = false
    @Volatile private var failed = false
    @Volatile private var encodedSample = false
    @Volatile private var stopStarted = false
    private var codecStarted = false
    private var glResourcesReleased = false
    private var track = -1
    private var muxStarted = false
    private val drain = RecordingDrainGate()
    private var resourcesReleased = false
    private var cleanupStarted = false
    private val done = CompletableDeferred<RecordingBackendResult>()
    private var finishIssued = false
    private val window = FrameBoundaryWindow(session.sessionId, session.sourceId)
    private var imageCount = 0
    private var lastBoundarySequence = -1L
    private val tickets = mutableListOf<FrameTicket>()
    private val workers = CaptureWorkers()
    private val glThread = workers.gl
    private val codecThread = workers.codec
    private val gl = Handler(glThread.looper)
    private val output = Handler(codecThread.looper)
    override val captureSurface: Surface get() = renderer.captureSurface
    override val captureWidth = canvas.width
    override val captureHeight = canvas.height
    override val hasEncodedSample get() = encodedSample && !failed && !stopStarted
    override val anchors: FrameAnchorPort = object : FrameAnchorPort {
        override fun before(action: FrameAnchorAction): AnchorResult = freeze(action, FrameBoundary.Before)
        override fun markGestureCompleted(action: FrameAnchorAction) {
            synchronized(renderer.slotLock) {
                if (accepts(action)) window.complete(action, sourceSequence)
            }
        }
        override fun afterWait(action: FrameAnchorAction): AnchorResult = freeze(action, FrameBoundary.After)
        override fun cancelEpoch(reason: FrameMissingReason) {
            synchronized(renderer.slotLock) { window.cancel() }
        }
    }

    private fun codecCallback(): MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // Surface input only
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (cleanupStarted) return
            try {
                check(!muxStarted && track == -1) { "Unexpected codec format change" }
                requireNormalizedFormat(format, encoding.width, encoding.height)
                track = muxer.addTrack(format); muxer.start(); muxStarted = true
            } catch (_: Exception) { fail() }
        }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (cleanupStarted) return
            try {
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (end && !captureClosed && !stopStarted) {
                    // An unsolicited terminal callback is a main-video fault, including when
                    // it carries data. Do not announce readiness or keep dispatching clicks.
                    drain.observedEnd(false)
                    fail()
                    return
                }
                check(info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME == 0) { "Partial codec samples are unsupported" }
                if (!config && info.size > 0) {
                    check(muxStarted && !drain.eosSeen && !failed)
                    check(info.presentationTimeUs >= 0)
                    val association = ledger.outputSeen(info.presentationTimeUs)
                    val previous = ledger.lastWrittenPtsUs()
                    check(previous == null || info.presentationTimeUs > previous) { "Reordered encoder output" }
                    val bytes = checkNotNull(codec.getOutputBuffer(index))
                    check(info.offset >= 0 && info.size <= bytes.capacity() - info.offset)
                    bytes.position(info.offset); bytes.limit(info.offset + info.size)
                    val sampleInfo = MediaCodec.BufferInfo().apply {
                        set(info.offset, info.size, info.presentationTimeUs,
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    }
                    muxer.writeSampleData(track, bytes, sampleInfo)
                    // Only the real successful write receives an ordinal and exact source match.
                    ledger.muxed(association)
                    checkReadiness()
                }
                if (end) drain.observedEnd(true)
            } catch (_: Exception) { fail() }
            finally { runCatching { codec.releaseOutputBuffer(index, false) }.onFailure { fail() } }
            if (drain.mayFinishAfterEnd()) finishCodec()
        }
        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) { fail() }
    }

    private suspend fun prepare(format: MediaFormat) {
        val setup = CompletableDeferred<Unit>()
        output.post {
            try {
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                // Callback registration precedes configure, as required by asynchronous codecs.
                codec.setCallback(codecCallback(), output)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                check(!stopStarted && !captureClosed) { "Preparation cancelled" }
                codecSurface = codec.createInputSurface()
                check(!stopStarted && !captureClosed) { "Preparation cancelled" }
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                check(!stopStarted && !captureClosed) { "Preparation cancelled" }
                check(gl.post {
                    try {
                        // A late setup runnable may be drained after quitSafely; never allocate then.
                        check(!stopStarted && !captureClosed) { "Preparation cancelled" }
                        renderer = RecordingGlRenderer(canvas.width, canvas.height, encoding.width, encoding.height,
                            codecSurface, gl, ::frameAvailable)
                        renderer.prepare()
                        setup.complete(Unit)
                    } catch (failure: Throwable) { setup.completeExceptionally(failure) }
                }) { "Capture worker is closed" }
            } catch (failure: Throwable) { setup.completeExceptionally(failure) }
        }
        check(withTimeoutOrNull(7_000) { setup.await(); true } == true) { "Recording preparation timed out" }
        evidenceAvailable = withContext(Dispatchers.IO) {
            runCatching { evidence.createSession(projectId, session.sessionId, session.sourceId); true }.getOrDefault(false)
        }
    }

    override fun start() {
        output.post {
            if (stopStarted || captureClosed || failed) return@post
            try {
                codec.start(); codecStarted = true; accepting = !captureClosed && !stopStarted && !failed
                gl.post { frameAvailable() } // consume a first buffer queued before start on a static screen
            } catch (_: Exception) { fail() }
        }
    }

    private fun frameAvailable() {
        if (!accepting || failed) return
        try {
            // Reserve before the latch, outside which no driver call holds the boundary lock.
            // Completion conservatively excludes every already in-flight latch from its after.
            val captureSequence = synchronized(renderer.slotLock) {
                if (!accepting) return
                ++sourceSequence
            }
            val timestampNs = renderer.acquireSourceTimestamp()
            if (timestampNs <= 0 || timestampNs <= lastSourceTimestampNs) return
            lastSourceTimestampNs = timestampNs
            val observation = VideoSourceObservation(captureSequence, captureSequence, timestampNs)
            renderer.drawObservation(observation)
            synchronized(renderer.slotLock) {
                if (!accepting) return
                presentationClock.observe(observation)
            }
            schedulePresentation()
        } catch (_: Exception) { fail() }
        catch (_: OutOfMemoryError) { fail() }
    }

    /** Single GL queue, one deadline runnable. Late work never queues synthetic catch-up frames. */
    private fun schedulePresentation() {
        gl.removeCallbacks(presentationTick)
        val delayNs = synchronized(renderer.slotLock) {
            if (!accepting || failed) null
            else presentationClock.delayUntilNextPresentationNs(SystemClock.elapsedRealtimeNanos())
        } ?: return
        check(gl.postDelayed(presentationTick, (delayNs + 999_999L) / 1_000_000L)) { "Presentation worker closed" }
    }

    private fun presentLatest() {
        if (!accepting || failed) return
        var slot: RecordingGlRenderer.PinnedSlot? = null
        try {
            val sample = synchronized(renderer.slotLock) {
                if (!accepting) return
                val decision = presentationClock.present(SystemClock.elapsedRealtimeNanos())
                if (decision !is PresentationDecision.Submit) return@synchronized null
                slot = checkNotNull(renderer.pinLatest()) { "Observation has no normalized frame" }
                check(slot!!.frame == decision.sample.observation) { "Presentation observation changed" }
                ledger.planned(decision.sample)
                decision.sample
            }
            if (sample != null) {
                renderer.submitPinned(checkNotNull(slot), sample.presentationPtsUs)
                // The callback may have already muxed this exact PTS. Readiness joins both facts.
                ledger.submitted(sample.presentationSampleId)
                output.post { checkReadiness() }
            }
            schedulePresentation()
        } catch (_: Exception) { fail() }
        catch (_: OutOfMemoryError) { fail() }
        finally { slot?.let(renderer::releasePin) }
    }

    /** Called only on the codec worker; start() is not evidence of a real encoded sample. */
    private fun checkReadiness() {
        if (!encodedSample && !failed && !stopStarted && ledger.hasCanonicalMatch()) {
            encodedSample = true
            onReady()
        }
    }

    private fun accepts(action: FrameAnchorAction): Boolean = accepting && !failed && evidenceAvailable &&
        window.accepts(action)

    private fun freeze(action: FrameAnchorAction, boundary: FrameBoundary): AnchorResult {
        synchronized(renderer.slotLock) {
            window.begin(action, boundary)?.let { return it }
            if (!accepts(action)) return AnchorResult.Missing(FrameMissingReason.CaptureStopped)
            fun missing(reason: FrameMissingReason): AnchorResult = AnchorResult.Missing(reason).also {
                window.retain(action, boundary, it)
                val selectedEpoch = window.epoch
                enqueueEvidence { evidence.persistMissing(action, boundary, selectedEpoch, reason) }
            }
            if (boundary == FrameBoundary.After && window.afterLowerBound(action) == null) {
                return missing(FrameMissingReason.ClosedEpoch)
            }
            if (imageCount >= 80) return missing(FrameMissingReason.BudgetExceeded)
            if (!pngPermits.tryAcquire()) return missing(FrameMissingReason.QueueFull)
            val slot = renderer.pinLatest()
            if (slot == null) { pngPermits.release(); return missing(FrameMissingReason.NoFrame) }
            val observed = slot.frame
            if (boundary == FrameBoundary.After && observed.captureSequence <= checkNotNull(window.afterLowerBound(action))) {
                renderer.releasePin(slot); pngPermits.release()
                return missing(FrameMissingReason.NoNewFrame)
            }
            val frame = ledger.canonicalForSource(observed.captureSequence)
            if (frame == null) {
                renderer.releasePin(slot); pngPermits.release()
                return missing(FrameMissingReason.EncoderUnmatched)
            }
            val ticket = FrameTicket(UUID.randomUUID().toString(), action, boundary, window.epoch, frame.sequence,
                frame.sequence, frame.timestampNs, frame.submittedPtsUs, geometry,
                if (frame.sequence == lastBoundarySequence) FrameFreshness.StaticReuse else FrameFreshness.Fresh)
            if (!enqueueEvidence { evidence.persistTicket(ticket) }) {
                renderer.releasePin(slot); pngPermits.release()
                return missing(FrameMissingReason.QueueFull)
            }
            imageCount++; lastBoundarySequence = frame.sequence; tickets += ticket
            val selected = AnchorResult.Ticket(ticket)
            window.retain(action, boundary, selected)
            // Selection and the pin happened synchronously. This task can never read a newer slot.
            if (!gl.post {
                var bitmap: android.graphics.Bitmap? = null
                try {
                    bitmap = renderer.readPinned(slot)
                    val owned = bitmap
                    if (enqueueEvidence {
                        try { evidence.recordPng(ticket, checkNotNull(owned)) }
                        catch (_: Exception) { runCatching { evidence.missing(ticket, FrameMissingReason.PngFailed) } }
                        catch (_: OutOfMemoryError) { runCatching { evidence.missing(ticket, FrameMissingReason.PngFailed) } }
                        finally { owned?.recycle(); pngPermits.release() }
                    }) bitmap = null else pngPermits.release()
                } catch (_: Exception) {
                    enqueueEvidence { evidence.missing(ticket, FrameMissingReason.PngFailed) }
                    pngPermits.release()
                } catch (_: OutOfMemoryError) {
                    enqueueEvidence { evidence.missing(ticket, FrameMissingReason.PngFailed) }
                    pngPermits.release()
                } finally { bitmap?.recycle(); renderer.releasePin(slot) }
            }) {
                renderer.releasePin(slot); pngPermits.release()
                enqueueEvidence { evidence.missing(ticket, FrameMissingReason.CaptureStopped) }
            }
            return selected
        }
    }

    private fun enqueueEvidence(task: () -> Unit): Boolean = try {
        writer.execute { runCatching(task) }; true
    } catch (_: java.util.concurrent.RejectedExecutionException) { false }

    private fun fail() {
        if (cleanupStarted) return
        if (!failed) { failed = true; stopAcceptingFrames(); onError() }
    }

    override fun stopAcceptingFrames() {
        if (::renderer.isInitialized) synchronized(renderer.slotLock) {
            if (captureClosed) return
            captureClosed = true
            accepting = false
            // Freeze at the capture gate, never after potentially slow VD/GL/codec cleanup.
            endPresentationPtsUs = presentationClock.close(SystemClock.elapsedRealtimeNanos())
            window.cancel()
        } else {
            captureClosed = true
            accepting = false
        }
        gl.removeCallbacks(presentationTick)
    }

    @Synchronized override fun finish(stop: Boolean): CompletableDeferred<RecordingBackendResult> {
        if (finishIssued) return done
        finishIssued = true; stopStarted = true; stopAcceptingFrames()
        // VD has already been detached by the coordinator. Earlier pinned reads finish first.
        gl.post {
            val glReleased = runCatching { if (::renderer.isInitialized) renderer.release(); true }.getOrDefault(false)
            glResourcesReleased = glReleased
            if (!glReleased) failed = true
            glThread.quitSafely()
            output.post {
                // Only this post from the returned GL teardown authorizes codec/surface cleanup.
                // A failed GL release still needs best-effort codec cleanup, but is never sealed.
                if (drain.glReturned(stop && codecStarted && !failed) == RecordingDrainGate.Teardown.FinishCodec) {
                    finishCodec(); return@post
                }
                try { codec.signalEndOfInputStream() } catch (_: Exception) { failed = true; finishCodec(); return@post }
                // This is a drain deadline, not a claim that a blocked native call was interrupted.
                output.postDelayed({ if (!resourcesReleased) { failed = true; finishCodec() } }, 5_000)
            }
        }
        return done
    }

    private fun finishCodec() {
        if (cleanupStarted) return
        check(drain.glTeardownReturned) { "Codec cleanup cannot precede GL teardown" }
        cleanupStarted = true
        val endPtsUs = endPresentationPtsUs
        val lastPtsUs = ledger.lastWrittenPtsUs()
        val completed = !failed && drain.eosSeen && lastPtsUs != null && endPtsUs != null && endPtsUs > lastPtsUs && muxStarted
        var closed = true
        var muxStopped = false
        if (completed) runCatching {
            // MediaMuxer supports an explicit empty EOS marker to set the final sample duration.
            // It is not a source observation, encoded sample, or sample ordinal.
            val end = MediaCodec.BufferInfo().apply { set(0, 0, checkNotNull(endPtsUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
            muxer.writeSampleData(track, java.nio.ByteBuffer.allocate(0), end)
        }.onFailure { failed = true }
        if (muxStarted) muxStopped = runCatching { muxer.stop(); true }.getOrDefault(false)
        if (codecStarted && ::codec.isInitialized) runCatching { codec.stop() }.onFailure { failed = true }
        runCatching { if (::codec.isInitialized) codec.release() }.onFailure { closed = false }
        runCatching { if (::muxer.isInitialized) muxer.release() }.onFailure { closed = false }
        runCatching { if (::codecSurface.isInitialized) codecSurface.release() }.onFailure { closed = false }
        resourcesReleased = closed && glResourcesReleased
        done.complete(RecordingBackendResult(completed && !failed && muxStopped && resourcesReleased, resourcesReleased))
        codecThread.quitSafely()
    }

    /** Duration is a main-video contract, independent of optional PNG availability. */
    suspend fun validateVideo(sealedFile: File) = withContext(Dispatchers.IO) {
        val container = readContainer(sealedFile, encoding.width, encoding.height)
        check(ledger.verifyContainer(container.samples)) { "Sealed sample count or chronology differs" }
        val first = checkNotNull(ledger.samples().firstOrNull()).encoderPtsUs
        val expected = checkNotNull(endPresentationPtsUs) - first
        check(expected > 0 && container.durationUs > 0 &&
            kotlin.math.abs(container.durationUs - expected) <= 1_000L) {
            "Container duration does not preserve the explicit recording interval"
        }
    }

    /** Sealed MP4 is read by actual sample ordinal; no millisecond FrameExtractor conversion. */
    suspend fun validateEvidence(sealedFile: File) {
        val samples = withContext(Dispatchers.IO) { readContainerSamples(sealedFile, encoding.width, encoding.height) }
        check(ledger.verifyContainer(samples)) { "Sealed sample count or chronology differs" }
        val sha = withContext(Dispatchers.IO) { sha256(sealedFile) }
        val finalized = CompletableDeferred<Unit>()
        if (!enqueueEvidence {
            try {
                val anchors = synchronized(renderer.slotLock) { tickets.toList() }
                for (ticket in anchors) {
                    val sample = ledger.matchSource(ticket.captureSequence)
                    if (sample == null) evidence.missing(ticket, FrameMissingReason.EncoderUnmatched)
                    else evidence.markEncoder(session.sessionId, ticket.sourceFrameId, sample.encoderPtsUs, sample.ordinal)
                }
                evidence.seal(session.sessionId, samples, sha)
                finalized.complete(Unit)
            } catch (failure: Exception) { finalized.completeExceptionally(failure) }
        }) return
        // PNG/evidence are optional. A hung writer is never promoted; the video can still register.
        withTimeoutOrNull(7_000) { finalized.await() }
    }

    fun markRegistered(sourceSha256: String) {
        enqueueEvidence { evidence.registered(session.sessionId, session.sourceId, sourceSha256) }
        writer.shutdown()
    }
    fun closeEvidenceWriter() { writer.shutdown() }

    fun discardEvidence() {
        enqueueEvidence { evidence.deleteUnregisteredSession(session.sessionId, session.sourceId) }
        writer.shutdown()
    }

    private class CaptureWorkers {
        val gl = HandlerThread("TapSceneCaptureGL")
        val codec = HandlerThread("TapSceneCaptureCodec")
        init {
            try { gl.start(); codec.start() }
            catch (failure: Throwable) { gl.quitSafely(); codec.quitSafely(); throw failure }
        }
    }

    companion object {
        suspend fun prepare(context: Context, projectId: String, session: RecordingClickSession,
            canvas: RecordingCanvas, file: File, maxDurationMs: Int, maxBytes: Long,
            onReady: () -> Unit, onError: () -> Unit,
            onOwned: (FrameRecordingBackend) -> Unit): FrameRecordingBackend {
            // Negotiate before consuming the projection grant. No second VirtualDisplay is tried.
            for (encoding in RecordingEncoder.candidates(canvas, maxDurationMs, maxBytes)) {
                var backend: FrameRecordingBackend? = null
                try {
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encoding.width, encoding.height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, encoding.bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, encoding.fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    }
                    backend = FrameRecordingBackend(context, projectId, session, canvas, encoding, file, onReady, onError)
                    // Coordinator owns every driver handle before the first suspendable prepare.
                    onOwned(backend)
                    backend.prepare(format)
                    return backend
                } catch (failure: Throwable) {
                    if (failure !is Exception && failure !is OutOfMemoryError) throw failure
                    if (backend != null) {
                        val result = backend.awaitFinish(false)
                        if (result?.released != true) throw RecordingStartException("编码器清理尚未完成，请关闭本次录制后重新打开应用。")
                        backend.writer.shutdown()
                    }
                    file.outputStream().use { it.fd.sync() }
                }
            }
            throw RecordingStartException("本机暂时无法配置共帧 H.264 录制，请改用手动录屏。")
        }

        internal fun readContainerSamples(file: File, width: Int, height: Int): List<Long> =
            readContainer(file, width, height).samples

        private data class ContainerVideo(val samples: List<Long>, val durationUs: Long)
        private fun readContainer(file: File, width: Int, height: Int): ContainerVideo {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                check(extractor.trackCount == 1)
                val format = extractor.getTrackFormat(0)
                requireNormalizedFormat(format, width, height)
                extractor.selectTrack(0)
                val values = mutableListOf<Long>()
                val sample = java.nio.ByteBuffer.allocateDirect(16 * 1024 * 1024)
                while (extractor.sampleTime >= 0) {
                    check(extractor.sampleTrackIndex == 0 && values.size < 12_000)
                    sample.clear()
                    check(extractor.readSampleData(sample, 0) in 1..sample.capacity()) { "Invalid MP4 sample" }
                    values += extractor.sampleTime
                    if (!extractor.advance()) break
                }
                check(values.isNotEmpty() && values.zipWithNext().all { (a, b) -> b > a })
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                return ContainerVideo(values, duration)
            } finally { extractor.release() }
        }
        private fun requireNormalizedFormat(format: MediaFormat, width: Int, height: Int) {
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
            check(format.getInteger(MediaFormat.KEY_WIDTH) == width && format.getInteger(MediaFormat.KEY_HEIGHT) == height)
            check(!format.containsKey(MediaFormat.KEY_ROTATION) || format.getInteger(MediaFormat.KEY_ROTATION) == 0)
            check(!format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) || format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) !in
                setOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG))
            check(!format.containsKey(MediaFormat.KEY_MAX_B_FRAMES) || format.getInteger(MediaFormat.KEY_MAX_B_FRAMES) == 0)
            val crop = mapOf("crop-left" to 0, "crop-top" to 0, "crop-right" to width - 1, "crop-bottom" to height - 1)
            crop.forEach { (key, value) -> check(!format.containsKey(key) || format.getInteger(key) == value) }
            if (format.containsKey("sar-width") || format.containsKey("sar-height")) {
                check(format.containsKey("sar-width") && format.containsKey("sar-height") &&
                    format.getInteger("sar-width") > 0 && format.getInteger("sar-width") == format.getInteger("sar-height"))
            }
        }

        internal fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { hash ->
            file.inputStream().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) { val count = input.read(bytes); if (count < 0) break; check(count > 0); hash.update(bytes, 0, count) }
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
