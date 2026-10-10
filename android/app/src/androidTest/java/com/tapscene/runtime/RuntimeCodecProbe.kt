package com.tapscene.runtime

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import com.tapscene.media.MediaInputPolicy
import com.tapscene.media.VideoFrameDecoder
import com.tapscene.recording.FrameRecordingBackend
import com.tapscene.recording.RecordingCanvas
import com.tapscene.recording.RecordingClickSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One synthetic Surface buffer exercises the SAME OES/FBO/EGL/codec/muxer path without projection. */
internal object RuntimeCodecProbe {
    suspend fun run(context: Context, output: File): JSONObject {
        val project = UUID.randomUUID().toString()
        val session = RecordingClickSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 160, 288)
        val ready = CompletableDeferred<Unit>()
        val failed = AtomicBoolean(false)
        var owned: FrameRecordingBackend? = null
        var released = false
        try {
            val backend = FrameRecordingBackend.prepare(context, project, session, RecordingCanvas(160, 288, 160),
                output, 10_000, 16L * 1024 * 1024,
                onReady = { ready.complete(Unit) }, onError = { failed.set(true); ready.completeExceptionally(IllegalStateException("Production EGL/codec probe failed")) },
                onOwned = { owned = it })
            // Queue the actual first buffer before start, including the production static-first-frame path.
            val canvas = backend.captureSurface.lockCanvas(null)
            try { canvas.drawColor(Color.rgb(220, 40, 190)) }
            finally { backend.captureSurface.unlockCanvasAndPost(canvas) }
            backend.start()
            withTimeout(12_000) { ready.await() }
            val readyAt = SystemClock.elapsedRealtimeNanos()
            delay(1_600) // No producer buffers are submitted during this static interval.
            check(!failed.get()) { "EGL/codec probe reported an asynchronous failure" }
            backend.stopAcceptingFrames()
            val stoppedAt = SystemClock.elapsedRealtimeNanos()
            val outcome = withTimeout(7_000) { backend.finish(true).await() }
            released = outcome.released
            check(outcome.released && outcome.sealable) { "Probe did not release/seal within unchanged production stop boundaries" }
            backend.validateVideo(output)
            val metadata = MediaInputPolicy.inspect(output, output.length(), FrameRecordingBackend.sha256(output)).metadata
            val samples = FrameRecordingBackend.readContainerSamples(output, metadata.width, metadata.height)
            val decoded = VideoFrameDecoder(context).validateOutput(output, metadata)
            check(samples == decoded && samples.size >= 2) { "Probe MP4 samples did not all decode in actual container order" }
            check(metadata.durationUs >= (stoppedAt - readyAt) / 1_000 - 1_000) { "Static probe duration collapsed" }
            return JSONObject().put("status", "PASS").put("producerBuffers", 1)
                .put("sampleCount", samples.size).put("durationUs", metadata.durationUs)
                .put("staticObservedUs", (stoppedAt - readyAt) / 1_000)
                .put("width", metadata.width).put("height", metadata.height)
                .put("productionValidateVideo", true).put("allSamplesDecoded", true)
        } finally {
            val backend = owned
            if (backend != null) {
                backend.stopAcceptingFrames()
                if (!released) {
                    // A timed-out driver retains ownership. Never delete a file it may still hold.
                    released = kotlinx.coroutines.withTimeoutOrNull(7_000) { backend.finish(false).await() }?.released == true
                }
                if (released) backend.discardEvidence()
                backend.closeEvidenceWriter()
            }
        }
    }
}
