package com.tapscene.recording

import android.view.Surface
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Projection and VirtualDisplay remain exclusively owned by RecordingCoordinator. */
internal interface RecordingBackend {
    val encoding: RecordingEncoding
    val captureSurface: Surface
    val captureWidth: Int
    val captureHeight: Int
    val anchors: FrameAnchorPort get() = FrameAnchorPort.None
    val hasEncodedSample: Boolean get() = false
    fun start()
    /** Synchronously fences new submissions, without waiting for a driver or PNG writer. */
    fun stopAcceptingFrames()
    /** Result is delivered only after all encoder/muxer file handles were actually released. */
    fun finish(stop: Boolean): CompletableDeferred<RecordingBackendResult>
}

internal data class RecordingBackendResult(val sealable: Boolean, val released: Boolean)

/** Manual recording deliberately keeps its established MediaRecorder behavior. */
internal class RecorderRecordingBackend(
    private val prepared: PreparedRecording,
) : RecordingBackend {
    override val encoding = prepared.encoding
    private var ownedSurface: Surface? = null
    override val captureSurface: Surface get() = ownedSurface ?: prepared.recorder.surface.also { ownedSurface = it }
    override val captureWidth = encoding.width
    override val captureHeight = encoding.height
    private var started = false
    private var completion: CompletableDeferred<RecordingBackendResult>? = null
    private val cleanup = Executors.newSingleThreadExecutor { task -> Thread(task, "TapSceneRecorderCleanup").apply { isDaemon = true } }
    override fun start() { prepared.recorder.start(); started = true }
    override fun stopAcceptingFrames() = Unit
    @Synchronized override fun finish(stop: Boolean): CompletableDeferred<RecordingBackendResult> {
        completion?.let { return it }
        val result = CompletableDeferred<RecordingBackendResult>().also { completion = it }
        cleanup.execute {
            val stopped = stop && started && runCatching { prepared.recorder.stop(); true }.getOrDefault(false)
            val released = runCatching { prepared.recorder.release(); true }.getOrDefault(false)
            runCatching { ownedSurface?.release() }
            result.complete(RecordingBackendResult(stopped && released, released))
            cleanup.shutdown()
        }
        return result
    }
}

/** Timing out does not cancel a driver call or claim that its file handle has closed. */
internal suspend fun RecordingBackend.awaitFinish(stop: Boolean): RecordingBackendResult? =
    withTimeoutOrNull(7_000) { finish(stop).await() }
