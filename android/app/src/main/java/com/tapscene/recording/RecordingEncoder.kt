package com.tapscene.recording

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.tapscene.media.MediaLimits
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class RecordingCanvas(val width: Int, val height: Int, val densityDpi: Int)
internal data class RecordingEncoding(val width: Int, val height: Int, val fps: Int, val bitrate: Int)
internal data class PreparedRecording(val recorder: MediaRecorder, val encoding: RecordingEncoding)

/** Advertised capabilities narrow candidates; actual MediaRecorder.prepare is the final check. */
internal object RecordingEncoder {
    @Suppress("DEPRECATION")
    fun canvas(context: Context): RecordingCanvas {
        val manager = context.getSystemService(WindowManager::class.java)
        val size = if (Build.VERSION.SDK_INT >= 30) {
            manager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            DisplayMetrics().also(manager.defaultDisplay::getRealMetrics).let { it.widthPixels to it.heightPixels }
        }
        check(size.first > 0 && size.second > 0)
        return RecordingCanvas(size.first, size.second, context.resources.configuration.densityDpi.coerceAtLeast(1))
    }

    @Suppress("DEPRECATION")
    fun prepare(
        context: Context,
        canvas: RecordingCanvas,
        file: File,
        maxDurationMs: Int,
        maxBytes: Long,
        onInfo: (Int) -> Unit,
        onError: () -> Unit,
    ): PreparedRecording {
        for (encoding in candidates(canvas, maxDurationMs, maxBytes)) {
            val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            try {
                // No audio source, audio encoder, microphone or playback capture is configured.
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setVideoSize(encoding.width, encoding.height)
                recorder.setVideoFrameRate(encoding.fps)
                recorder.setVideoEncodingBitRate(encoding.bitrate)
                recorder.setOrientationHint(0)
                recorder.setMaxDuration(maxDurationMs)
                recorder.setMaxFileSize(maxBytes)
                recorder.setOutputFile(file.absolutePath)
                recorder.setOnInfoListener { _, what, _ -> onInfo(what) }
                recorder.setOnErrorListener { _, _, _ -> onError() }
                recorder.prepare()
                return PreparedRecording(recorder, encoding)
            } catch (_: Exception) {
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
                // Retry negotiation BEFORE creating/consuming any MediaProjection session.
                file.outputStream().use { it.fd.sync() }
            }
        }
        throw RecordingStartException("本机暂时无法配置无声 H.264 录制，请关闭其他录制应用后重试。")
    }

    internal fun candidates(canvas: RecordingCanvas, durationMs: Int, maxBytes: Long): List<RecordingEncoding> {
        val scale = min(1.0, min(
            MediaLimits.MAX_WIDTH.toDouble() / min(canvas.width, canvas.height),
            MediaLimits.MAX_HEIGHT.toDouble() / max(canvas.width, canvas.height),
        ))
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        val values = mutableListOf<RecordingEncoding>()
        for (factor in listOf(1.0, 0.85, 0.75, 0.625, 0.5, 0.375, 0.25)) {
            for (fps in listOf(30, 24, 15)) {
                for (codec in codecs) {
                    try {
                        val capabilities = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in capabilities.colorFormats) continue
                        val video = capabilities.videoCapabilities ?: continue
                        val width = alignDown(floor(canvas.width * scale * factor).toInt(), video.widthAlignment)
                        val height = alignDown(floor(canvas.height * scale * factor).toInt(), video.heightAlignment)
                        if (width < 64 || height < 64 || !video.areSizeAndRateSupported(width, height, fps.toDouble())) continue
                        // Leave muxing/headroom room. The recorder's own file limit remains authoritative.
                        val budgetRate = (maxBytes.toDouble() * 8 * 0.85 / (durationMs / 1_000.0)).toLong()
                            .coerceIn(128_000L, 8_000_000L).toInt()
                        val targetRate = (width.toLong() * height * fps / 10).coerceIn(500_000L, budgetRate.toLong().coerceAtLeast(500_000L)).toInt()
                        val bitrate = video.bitrateRange.clamp(targetRate)
                        if (bitrate > budgetRate) continue
                        values += RecordingEncoding(width, height, fps, bitrate)
                        // Keep at least one real prepare attempt at every lower scale instead
                        // of filling the retry budget with many codecs at the largest size.
                        break
                    } catch (_: Exception) {
                        // Some OEMs publish incomplete capabilities. Try another advertised configuration.
                    }
                }
            }
        }
        return values.distinct()
    }

    private fun alignDown(value: Int, alignment: Int): Int = value / alignment.coerceAtLeast(1) * alignment.coerceAtLeast(1)
}

internal class RecordingStartException(message: String) : Exception(message)
