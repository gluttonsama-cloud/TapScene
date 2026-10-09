package com.tapscene.media

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Input compatibility is decided by the platform decoder, not by a second bitstream parser. */
internal object MediaInputPolicy {
    fun inspect(file: File, byteLength: Long = file.length(), sha256: String = ""): InputVideoInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val (track, format) = videoTrack(extractor)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val safeMime = mime.takeIf { it.matches(Regex("[a-zA-Z0-9.+/-]{1,64}")) } ?: "unknown"
            if (mime !in setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                throw MediaImportException("当前录屏编码尚未接入：$safeMime。请选择本机 H.264 或 HEVC 录屏；原文件未改动。")
            }
            val width = visibleSize(format, true)
            val height = visibleSize(format, false)
            val rotation = format.intOrZero(MediaFormat.KEY_ROTATION)
            if (rotation !in setOf(0, 90, 180, 270)) {
                throw MediaImportException("读取方向失败：mime=$safeMime, rotation=$rotation。只支持固定的直角旋转。")
            }
            // Resource guard, not the output canvas budget. Common 1440p/4K/long-screen inputs
            // are scaled on the GPU before a bitmap is read back.
            val codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (width !in 1..8192 || height !in 1..8192 || codedWidth !in 1..8192 || codedHeight !in 1..8192 ||
                codedWidth.toLong() * codedHeight > 40_000_000L) {
                throw MediaImportException("输入尺寸超过本机处理预算：mime=$safeMime, ${width}x$height（最长边 8192、4000 万像素）。")
            }
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (duration !in 1..MediaLimits.MAX_DURATION_US) {
                throw MediaImportException("单段录屏须有有效时长，且不能超过 3 分钟。")
            }
            val sarWidth = format.intOrZero("sar-width")
            val sarHeight = format.intOrZero("sar-height")
            val ratio = if (sarWidth > 0 && sarHeight > 0) sarWidth.toFloat() / sarHeight else 1f
            if (!ratio.isFinite() || ratio !in 0.125f..8f) {
                throw MediaImportException("读取像素比例失败：mime=$safeMime, sar=$sarWidth:$sarHeight。")
            }
            val metadata = SourceMetadata("video/mp4", byteLength, sha256, width, height, rotation, duration, ratio)
            val info = InputVideoInfo(metadata, format, mime,
                format.intOrZero(MediaFormat.KEY_COLOR_TRANSFER) in setOf(
                    MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG,
                ))
            extractor.selectTrack(track)
            var samples = 0
            // Packet inspection is streaming and does not allocate decoded full-resolution images.
            // Do not infer frame identity or cadence from nominal fps or sample count.
            while (extractor.sampleTime >= 0) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                    throw MediaImportException("不支持加密录屏。${info.diagnostic("读取样本")}")
                }
                samples++
                if (samples > 100_000) throw MediaImportException("录屏样本数量超出处理预算。${info.diagnostic("读取样本")}")
                if (!extractor.advance()) break
            }
            if (samples == 0) throw MediaImportException("录屏没有视频画面。${info.diagnostic("读取样本")}")
            return info
        } finally {
            extractor.release()
        }
    }

    /** Preserve the complete displayed image. Encoder alignment is negotiated before masking. */
    fun fitOutputSize(displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        require(displayWidth > 0 && displayHeight > 0)
        val scale = min(1.0, min(1080.0 / min(displayWidth, displayHeight), 2400.0 / max(displayWidth, displayHeight)))
        return max(1, floor(displayWidth * scale).toInt()) to max(1, floor(displayHeight * scale).toInt())
    }
}

internal data class InputVideoInfo(
    val metadata: SourceMetadata,
    val format: MediaFormat,
    val mime: String,
    val isHdr: Boolean,
) {
    /** Only bounded technical fields. Never include URI, source name, path, pixels or OCR text. */
    fun diagnostic(stage: String): String = buildString {
        append("阶段=$stage; mime=$mime; size=${metadata.width}x${metadata.height}; rotation=${metadata.rotationDeg}")
        append("; profile=${format.intOrZero(MediaFormat.KEY_PROFILE)}")
        append("; color=${format.intOrZero(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrZero(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrZero(MediaFormat.KEY_COLOR_TRANSFER)}")
    }
}
