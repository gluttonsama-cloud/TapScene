package com.tapscene.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.inspector.FrameExtractor
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Surface decoding accepts the platform's supported AVC/HEVC inputs, including Main10. */
@OptIn(UnstableApi::class)
class VideoFrameDecoder(context: Context) {
    private val appContext = PrivateMediaContext(context)
    private val privateRoot = appContext.noBackupFilesDir

    /**
     * Returns Media3's representative decoded frame near the requested time, with its actual
     * millisecond PTS. No promise of next-frame selection or invented microsecond precision.
     * The caller owns only our bounded copy, never Media3's cached/deduplicated bitmap.
     */
    suspend fun decode(source: ImportedSource, requestedTimeUs: Long): DecodedFrame {
        var ownedBitmap: Bitmap? = null
        try {
            if (requestedTimeUs < 0) throw FrameDecodeException("取帧时刻不能小于零。[stage=request]")
            val (file, info) = withContext(Dispatchers.IO) {
                val file = resolve(source)
                file to inspect(file, source.metadata)
            }
            return withSurface(file, info) {
                val frame = frameAt(requestedTimeUs.coerceAtMost(info.metadata.durationUs - 1))
                stage = "bitmap_copy"
                currentCoroutineContext().ensureActive()
                val bitmap = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw FrameDecodeException("无法分配缩小后的画面缓存。")
                ownedBitmap = bitmap
                // FrameExtractor 1.9.4 truncates the source PTS to milliseconds internally.
                DecodedFrame(bitmap, frame.presentationTimeMs * 1_000L, timePrecisionUs = 1_000L)
            }
        } catch (failure: Throwable) {
            // Also covers prompt cancellation while returning across dispatcher boundaries.
            ownedBitmap?.recycle()
            throw failure
        }
    }

    /**
     * A bounded, sequential batch shares one Surface decoder and its release/cancellation gate.
     * [onSample] only borrows Media3's bitmap until it returns: never retain or recycle it.
     * Callbacks include duplicate returned PTS so completed requests remain honest progress;
     * consumers must deduplicate by actual PTS, not by requested time or nominal frame rate.
     */
    internal suspend fun sampleFrames(
        source: ImportedSource,
        requestedTimesUs: List<Long>,
        onSample: suspend (bitmap: Bitmap, actualTimeUs: Long, timePrecisionUs: Long) -> Unit,
    ) {
        require(requestedTimesUs.isNotEmpty() && requestedTimesUs.size <= 512) { "画面采样数量无效。" }
        require(requestedTimesUs.all { it >= 0 } && requestedTimesUs.zipWithNext().all { (a, b) -> a < b }) {
            "画面采样时间必须递增。"
        }
        val (file, info) = withContext(Dispatchers.IO) {
            val file = resolve(source)
            file to inspect(file, source.metadata)
        }
        withSurface(file, info) {
            for (requested in requestedTimesUs) {
                currentCoroutineContext().ensureActive()
                val frame = frameAt(requested.coerceAtMost(info.metadata.durationUs - 1))
                stage = "sample_features"
                onSample(frame.bitmap, frame.presentationTimeMs * 1_000L, 1_000L)
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /** Input preflight samples beginning/middle/end. It does not decode the whole video again. */
    internal suspend fun validate(
        file: File,
        metadata: SourceMetadata,
        onFrame: (suspend (Image) -> Unit)? = null,
    ) {
        if (onFrame != null) {
            // Compatibility for old generated-output callers; arbitrary input must not use this.
            validateOutput(file, metadata, onFrame)
            return
        }
        val info = withContext(Dispatchers.IO) { inspect(file, metadata) }
        withSurface(file, info) {
            for (timeUs in listOf(0L, metadata.durationUs / 2, metadata.durationUs - 1).distinct()) {
                currentCoroutineContext().ensureActive()
                frameAt(timeUs)
                // Do not recycle the original: another seek may return this same cached bitmap.
            }
        }
    }

    /**
     * Every generated AVC/SDR frame is checked in unrotated YUV, with the real output PTS.
     * [onFrame] borrows each Image only during the callback; it must neither retain nor close it.
     */
    internal suspend fun validateOutput(
        file: File,
        metadata: SourceMetadata,
        onFrame: (suspend (Image) -> Unit)? = null,
    ): List<Long> = GeneratedVideoDecoder().validate(file, metadata, onFrame)

    private suspend fun inspect(file: File, expected: SourceMetadata): InputVideoInfo {
        val info = try {
            runInterruptible { MediaInputPolicy.inspect(file, expected.byteLength, expected.sha256) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val detail = if (failure is MediaImportException) failure.message else "读取视频轨失败。"
            throw FrameDecodeException("$detail [stage=inspect, ${safeFailure(failure)}]", failure)
        }
        if (info.metadata != expected) {
            throw FrameDecodeException("录屏信息已改变，请重新导入。[${info.diagnostic("inspect_geometry")}]")
        }
        if (info.isHdr) {
            if (Build.VERSION.SDK_INT < 29) {
                throw FrameDecodeException("此素材使用 PQ/HLG HDR，当前 Android 版本不支持本机 HDR 转 SDR。[${info.diagnostic("hdr_capability")}]")
            }
            // Media3 1.9.4 replaces incomplete ColorInfo with SDR defaults. Never allow that
            // default to silently reinterpret an explicitly tagged PQ/HLG source as SDR.
            val standard = info.format.intOrZero(MediaFormat.KEY_COLOR_STANDARD)
            val range = info.format.intOrZero(MediaFormat.KEY_COLOR_RANGE)
            if (standard !in setOf(MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_STANDARD_BT601_PAL,
                    MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT2020) ||
                range !in setOf(MediaFormat.COLOR_RANGE_FULL, MediaFormat.COLOR_RANGE_LIMITED)
            ) {
                throw FrameDecodeException("此 PQ/HLG 素材的色域或范围标记不完整，当前转换器不能可靠转换为 SDR。[${info.diagnostic("hdr_color_info")}]")
            }
        }
        return info
    }

    private suspend fun <T> withSurface(
        file: File,
        info: InputVideoInfo,
        block: suspend SurfaceSession.() -> T,
    ): T = withContext(Dispatchers.Main.immediate) {
        // FrameExtractor instances require one application thread. Never block the main looper
        // on Future.get(): Media3's player and its completion queue also run on this looper.
        val owner = Any()
        var acquired = false
        var session: SurfaceSession? = null
        var failure: Throwable? = null
        var stage = "surface_queue"
        try {
            val ready = withTimeoutOrNull(QUEUE_TIMEOUT_MS) {
                surfaceGate.lock(owner)
                acquired = true
                true
            } ?: false
            if (!ready) throw FrameDecodeException("上次取帧仍在由设备释放，请稍后重试；若持续无响应，请退出应用后重开。")
            stage = "surface_prepare"
            session = SurfaceSession(file, info)
            session.block()
        } catch (cancelled: CancellationException) {
            failure = cancelled
            throw cancelled
        } catch (cause: Exception) {
            val geometryFailure = generateSequence<Throwable>(cause) { it.cause }.take(8)
                .filterIsInstance<SourceGeometryException>().firstOrNull()
            val actualStage = if (geometryFailure != null) "surface_geometry" else session?.stage ?: stage
            val message = if (geometryFailure != null) "视频显示尺寸与导入信息不一致。"
                else if (cause is FrameDecodeException) cause.message
                else if (info.isHdr) "此素材的 Surface 解码或 HDR 转 SDR 失败，请重试或将该素材转换为 SDR。"
                else "此素材的 Surface 取帧失败，请重试或重新选择录屏。"
            val reported = FrameDecodeException("$message [${info.diagnostic(actualStage)}; ${safeFailure(cause)}]", cause)
            failure = reported
            throw reported
        } catch (outOfMemory: OutOfMemoryError) {
            val reported = FrameDecodeException("设备内存不足，无法完成此素材取帧。[${info.diagnostic(session?.stage ?: stage)}; cause=OutOfMemoryError]", outOfMemory)
            failure = reported
            throw reported
        } catch (fatal: Throwable) {
            failure = fatal
            throw fatal
        } finally {
            if (acquired) {
                try {
                    // close() queues asynchronous release in Media3; it is not a release barrier.
                    session?.close()
                } catch (cleanup: Exception) {
                    if (failure != null) failure.addSuppressed(cleanup)
                    else throw FrameDecodeException("取帧资源释放请求失败。[${info.diagnostic("surface_close")}; ${safeFailure(cleanup)}]", cleanup)
                } finally {
                    val inFlight = session?.inFlight
                    if (inFlight != null && !inFlight.isDone) {
                        // Cancelling a running ExecutionSequencer future does not stop its codec.
                        // Keep the gate until the original work really completes, even after the
                        // caller cancels/times out; this prevents accumulating abandoned decoders.
                        inFlight.addListener({ surfaceGate.unlock(owner) }, MoreExecutors.directExecutor())
                    } else {
                        surfaceGate.unlock(owner)
                    }
                }
            }
        }
    }

    private inner class SurfaceSession(file: File, val info: InputVideoInfo) {
        private val presentation = BoundedPresentation(info.metadata)
        private val extractor = FrameExtractor.Builder(appContext, MediaItem.fromUri(Uri.fromFile(file)))
            .setEffects(listOf(presentation))
            .setSeekParameters(SeekParameters.EXACT)
            // Keep Media3 1.9.4's PREFER_SOFTWARE default: hardware flush+effects is known
            // to crash on some devices; decoder support is still resolved by Media3.
            // Default extractHdrFrames=false actually tone maps HDR to SDR. Calling the setter
            // would incorrectly require API 34, although false/default works on older devices.
            .build()
        var stage = "surface_prepare"
        var inFlight: ListenableFuture<FrameExtractor.Frame>? = null
            private set

        suspend fun frameAt(timeUs: Long): FrameExtractor.Frame {
            stage = if (info.isHdr) "surface_decode_tonemap" else "surface_decode"
            currentCoroutineContext().ensureActive()
            val pending = extractor.getFrame(timeUs / 1_000L)
            inFlight = pending
            val frame = withTimeoutOrNull(FRAME_TIMEOUT_MS) { pending.awaitWithoutCancellingWork() }
                ?: throw FrameDecodeException("此素材取帧超时，设备仍可能正在结束解码；请稍后重试。")
            stage = "surface_geometry"
            val expectedSize = MediaInputPolicy.fitOutputSize(info.metadata.displayWidth, info.metadata.displayHeight)
            if (frame.bitmap.isRecycled || frame.bitmap.width != expectedSize.first ||
                frame.bitmap.height != expectedSize.second || frame.bitmap.config != Bitmap.Config.ARGB_8888
            ) throw FrameDecodeException("设备返回的 SDR 画面尺寸或像素格式与处理预算不一致。")
            if (frame.presentationTimeMs < 0 || frame.presentationTimeMs > info.metadata.durationUs / 1_000L) {
                throw FrameDecodeException("设备返回的画面时间戳超出素材范围。")
            }
            return frame
        }

        fun close() = extractor.close()
    }

    /** Rotation/SAR have already been applied by Media3 when these GL dimensions are configured. */
    private class BoundedPresentation(private val metadata: SourceMetadata) : MatrixTransformation {
        private var presentation: Presentation? = null
        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            // Media3 expands the shorter pixel axis to square pixels; when SAR < 1 this
            // expands height rather than shrinking width as our display-budget model does.
            val ratio = metadata.pixelWidthHeightRatio
            val squareWidth = if (ratio > 1f) (metadata.width * ratio).toInt() else metadata.width
            val squareHeight = if (ratio < 1f) (metadata.height / ratio).toInt() else metadata.height
            val rotated = metadata.rotationDeg == 90 || metadata.rotationDeg == 270
            val expectedWidth = if (rotated) squareHeight else squareWidth
            val expectedHeight = if (rotated) squareWidth else squareHeight
            if (abs(inputWidth - expectedWidth) > 1 || abs(inputHeight - expectedHeight) > 1) {
                throw SourceGeometryException(inputWidth, inputHeight, expectedWidth, expectedHeight)
            }
            val (width, height) = MediaInputPolicy.fitOutputSize(metadata.displayWidth, metadata.displayHeight)
            return Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
                .also { presentation = it }.configure(inputWidth, inputHeight)
        }

        override fun getMatrix(presentationTimeUs: Long): Matrix =
            checkNotNull(presentation).getMatrix(presentationTimeUs)
    }

    private fun resolve(source: ImportedSource): File = try {
        resolvePrivateSource(source)
    } catch (failure: FrameDecodeException) {
        throw failure
    } catch (failure: Exception) {
        throw FrameDecodeException("读取本机素材失败，请重新选择。[stage=resolve, ${safeFailure(failure)}]", failure)
    }

    private fun resolvePrivateSource(source: ImportedSource): File {
        val expectedPath = "${MediaLimits.SOURCE_DIRECTORY}/${source.sourceId}.mp4"
        if (!source.sourceId.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) ||
            source.privateRelativePath != expectedPath
        ) throw FrameDecodeException("素材路径无效，请重新导入。[stage=resolve]")
        val directory = File(privateRoot, MediaLimits.SOURCE_DIRECTORY).canonicalFile
        val file = File(privateRoot, expectedPath).canonicalFile
        if (directory.parentFile != privateRoot.canonicalFile || file.parentFile != directory ||
            !file.isFile || file.length() != source.metadata.byteLength
        ) throw FrameDecodeException("本机素材缺失或已改变，请重新导入。[stage=resolve]")
        return file
    }

    private companion object {
        val surfaceGate = Mutex()
        const val FRAME_TIMEOUT_MS = 30_000L
        const val QUEUE_TIMEOUT_MS = 30_000L
    }
}

/** Cancel only our wait; retain the real work's completion signal for bounded cleanup/queuing. */
private suspend fun <T> ListenableFuture<T>.awaitWithoutCancellingWork(): T = suspendCancellableCoroutine { continuation ->
    val waiting = Futures.nonCancellationPropagating(this)
    continuation.invokeOnCancellation { waiting.cancel(false) }
    waiting.addListener({
        try {
            continuation.resume(waiting.get())
        } catch (failure: ExecutionException) {
            continuation.resumeWithException(failure.cause ?: failure)
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }, MoreExecutors.directExecutor())
}

private class SourceGeometryException(
    val actualWidth: Int, val actualHeight: Int, val expectedWidth: Int, val expectedHeight: Int,
) : IllegalArgumentException("source_display_geometry_changed")

/** Exception messages can contain a private URI/path; expose only fixed fields and safe tokens. */
private fun safeFailure(failure: Throwable): String {
    val causes = generateSequence(failure) { it.cause }.take(8).toList()
    val playback = causes.filterIsInstance<PlaybackException>().firstOrNull()
    val geometry = causes.filterIsInstance<SourceGeometryException>().firstOrNull()
    val codec = causes.filterIsInstance<MediaCodec.CodecException>().firstOrNull()
    val codecDiagnostic = codec?.diagnosticInfo?.take(80)?.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    return "cause=${causes.last().javaClass.simpleName}" +
        (playback?.let { "; media3=${it.errorCode}" } ?: "") +
        (codecDiagnostic?.let { "; codec=$it" } ?: "") +
        (geometry?.let { "; glSize=${it.actualWidth}x${it.actualHeight}; expectedGlSize=${it.expectedWidth}x${it.expectedHeight}" } ?: "")
}
