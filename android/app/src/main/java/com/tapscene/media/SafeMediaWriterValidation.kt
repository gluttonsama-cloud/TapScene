package com.tapscene.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.Image
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Internal technical checks. None of these substitute for the author's actual-output review. */
internal object SafeMediaWriterValidation {
    suspend fun verifyPng(file: File, width: Int, height: Int, masks: List<OpaqueMask>) {
        verifyPngChunks(file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outMimeType == "image/png" && bounds.outWidth == width && bounds.outHeight == height) {
            "实际输出不是预期尺寸的 PNG。"
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: error("新生成的 PNG 无法重新解码。")
        try {
            check(decoded.width == width && decoded.height == height) { "PNG 重解码尺寸改变。" }
            val row = IntArray(width)
            // The output is flattened onto opaque black. Check every alpha, including pixels
            // outside the masks, so transparent hidden content cannot be carried into a PNG.
            for (y in 0 until height) {
                currentCoroutineContext().ensureActive()
                decoded.getPixels(row, 0, width, 0, y, width, 1)
                check(row.all { Color.alpha(it) == 255 }) { "PNG 含有透明像素，已丢弃。" }
            }
            for (mask in masks) {
                val rect = mask.toPixelRect(width, height)
                for (y in rect.top until rect.bottom) {
                    currentCoroutineContext().ensureActive()
                    decoded.getPixels(row, 0, rect.width(), rect.left, y, rect.width(), 1)
                    for (x in 0 until rect.width()) {
                        check(row[x] == Color.BLACK) { "PNG 实际遮挡像素不是完全不透明的纯黑色。" }
                    }
                }
            }
        } finally {
            decoded.recycle()
        }
    }

    /** Reject ancillary payloads rather than relying only on a .png filename or decoder success. */
    private suspend fun verifyPngChunks(file: File) {
        RandomAccessFile(file, "r").use { input ->
            val signature = ByteArray(8).also(input::readFully)
            check(signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) {
                "输出 PNG 文件头无效。"
            }
            var hasHeader = false
            var hasPixels = false
            var ended = false
            val block = ByteArray(16 * 1024)
            val allowed = setOf("IHDR", "IDAT", "IEND", "sRGB", "gAMA", "cHRM", "sBIT")
            while (!ended) {
                currentCoroutineContext().ensureActive()
                check(input.length() - input.filePointer >= 12) { "PNG 文件不完整。" }
                val length = input.readInt().toLong() and 0xffffffffL
                val typeBytes = ByteArray(4).also(input::readFully)
                val type = typeBytes.toString(Charsets.US_ASCII)
                check(type in allowed && length <= input.length() - input.filePointer - 4) {
                    "PNG 含有不允许的元数据或损坏的数据块。"
                }
                check(hasHeader || type == "IHDR") { "PNG 缺少首部。" }
                when (type) {
                    "IHDR" -> {
                        check(!hasHeader && length == 13L) { "PNG 首部无效。" }
                        hasHeader = true
                    }
                    "IDAT" -> hasPixels = true
                    "IEND" -> {
                        check(hasPixels && length == 0L) { "PNG 没有实际像素。" }
                        ended = true
                    }
                }
                val crc = CRC32().apply { update(typeBytes) }
                var remaining = length
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val size = minOf(remaining, block.size.toLong()).toInt()
                    input.readFully(block, 0, size)
                    crc.update(block, 0, size)
                    remaining -= size
                }
                check(crc.value == (input.readInt().toLong() and 0xffffffffL)) { "PNG 校验和不匹配。" }
            }
            check(input.filePointer == input.length()) { "PNG 尾部含有额外内容。" }
        }
    }

    fun verifyVideoInput(file: File, endUs: Long): InputVideoInfo {
        val input = MediaInputPolicy.inspect(file)
        check(input.metadata.durationUs >= endUs) { "裁剪区间超出实际视频时长。" }
        return input
    }

    data class VideoInfo(val width: Int, val height: Int, val durationUs: Long)

    suspend fun verifyVideoOutput(
        context: Context,
        file: File,
        selectedDurationUs: Long,
        masks: List<OpaqueMask>,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedFrameCount: Int,
    ): VideoInfo {
        currentCoroutineContext().ensureActive()
        requireMp4(file)
        val extractor = MediaExtractor()
        val samplePts = mutableListOf<Long>()
        // Bound verification work by the real selected duration, not nominal-fps metadata.
        // This is a 120 fps equivalent resource budget, not an enforced frame cadence; keep
        // sparse VFR frames and their final state rather than dropping or inventing samples.
        val frameBudget = ((selectedDurationUs * 120 + 999_999) / 1_000_000 + 3).toInt()
        val info = try {
            extractor.setDataSource(file.absolutePath)
            check(extractor.trackCount == 1) { "输出含有音频或其他额外轨道，已丢弃。" }
            val format = extractor.getTrackFormat(0)
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC) {
                "输出不是 H.264 视频，已丢弃。"
            }
            check(format.intOrZero(MediaFormat.KEY_ROTATION) == 0) {
                "输出依赖旋转元数据，无法确认遮挡坐标。"
            }
            check(format.intOrZero(MediaFormat.KEY_COLOR_TRANSFER) !in setOf(
                MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG,
            )) { "输出仍标为 HDR，无法确认已完成真实 SDR 色调映射。" }
            // An omitted container color field is valid: controlled AVC output uses the same
            // BT.709/limited/SDR defaults as Media3. Do not demand a complete container whitelist.
            val sarWidth = format.intOrZero("sar-width")
            val sarHeight = format.intOrZero("sar-height")
            check(sarWidth <= 0 || sarHeight <= 0 || sarWidth == sarHeight) {
                "输出仍依赖非方形像素比例，无法确认遮挡坐标。"
            }
            check(format.containsKey(MediaFormat.KEY_DURATION)) { "输出缺少有效时长。" }
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            check(durationUs in 1..selectedDurationUs + MediaLimits.TIMESTAMP_TOLERANCE_US &&
                durationUs <= 10_000_000L + MediaLimits.TIMESTAMP_TOLERANCE_US
            ) {
                "输出时长无效或超过选择的区间。"
            }
            val width = visibleSize(format, horizontal = true)
            val height = visibleSize(format, horizontal = false)
            check(width > 0 && height > 0 && minOf(width, height) <= 1080 && maxOf(width, height) <= 2400) {
                "输出画面超出长边 2400、短边 1080 的预算。"
            }
            check(width == expectedWidth && height == expectedHeight) {
                "输出尺寸与遮挡前协商的画布不一致，已丢弃。"
            }
            extractor.selectTrack(0)
            while (extractor.sampleTime >= 0) {
                currentCoroutineContext().ensureActive()
                check(extractor.sampleTime < selectedDurationUs + MediaLimits.TIMESTAMP_TOLERANCE_US &&
                    extractor.sampleTime < durationUs + MediaLimits.TIMESTAMP_TOLERANCE_US
                ) { "输出包含裁剪区间外的视频样本。" }
                check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                    "输出含有加密样本。"
                }
                samplePts += extractor.sampleTime
                check(samplePts.size <= frameBudget) { "输出的实际视频帧数超过所选时长的处理预算。" }
                if (!extractor.advance()) break
            }
            check(samplePts.isNotEmpty()) { "输出没有视频样本。" }
            check(samplePts.size == expectedFrameCount && samplePts.distinct().size == samplePts.size) {
                "实际输出的样本数或 PTS 与编码结果不一致，已丢弃。"
            }
            VideoInfo(width, height, durationUs)
        } finally {
            extractor.release()
        }

        // Decode ALL output frames to EOS with actual PTS / crop / YUV checks. This is a new
        // decoder of the actual saved bytes, not an encoder callback or a preview overlay.
        val decodedPts = VideoFrameDecoder(context).validateOutput(
            file,
            SourceMetadata(
                mime = "video/mp4", byteLength = file.length(), sha256 = "",
                width = info.width, height = info.height, rotationDeg = 0,
                durationUs = info.durationUs,
            ),
        ) { image -> verifyMasksOnDecodedFrame(image, masks) }
        check(decodedPts == samplePts.sorted()) {
            "输出没有逐帧完整解码，或实际解码 PTS 与封装样本不一致，已丢弃。"
        }
        verifyDecodedVideoSamples(file, info, masks)
        return info
    }

    /**
     * Checks every masked interior pixel in EVERY decoded output frame, accounting for the
     * decoder's crop, strides and buffer offset. Encoded black can differ from RGB(0,0,0): SDR
     * video uses limited/full-range YUV and H.264 adds quantization/chroma-edge noise. Consequently
     * this is a fail-closed burn-in sanity check with a tolerance, not a privacy certification.
     * Drawing itself still covers the complete outward-rounded rectangle with alpha exactly 255.
     */
    private suspend fun verifyMasksOnDecodedFrame(image: Image, masks: List<OpaqueMask>) {
        val crop = image.cropRect
        for (mask in masks) {
            val rect = mask.toPixelRect(crop.width(), crop.height())
            val insetX = minOf(3, (rect.width() - 1) / 2)
            val insetY = minOf(3, (rect.height() - 1) / 2)
            val left = crop.left + rect.left + insetX
            val top = crop.top + rect.top + insetY
            val right = crop.left + rect.right - insetX
            val bottom = crop.top + rect.bottom - insetY
            for (planeIndex in 0..2) {
                val plane = image.planes[planeIndex]
                val buffer = plane.buffer.duplicate()
                val start = buffer.position()
                val divisor = if (planeIndex == 0) 1 else 2
                val xFirst = left / divisor
                val xLast = (right - 1) / divisor
                val yFirst = top / divisor
                val yLast = (bottom - 1) / divisor
                for (y in yFirst..yLast) {
                    currentCoroutineContext().ensureActive()
                    val rowOffset = start + y * plane.rowStride
                    for (x in xFirst..xLast) {
                        val value = buffer.get(rowOffset + x * plane.pixelStride).toInt() and 0xff
                        val matchesBlack = if (planeIndex == 0) value <= 58 else value in 96..160
                        check(matchesBlack) {
                            "完整视频中的实际遮挡像素检查未通过，请扩大遮挡后重新生成。"
                        }
                    }
                }
            }
        }
    }

    /**
     * Separately exercises Android's RGB playback-facing frame path at first/middle/last. The
     * per-frame YUV check above covers the full clip; neither check replaces the author's review.
     */
    private suspend fun verifyDecodedVideoSamples(file: File, info: VideoInfo, masks: List<OpaqueMask>) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION).isNullOrBlank()) {
                "输出仍含位置元数据，已丢弃。"
            }
            val times = listOf(0L, info.durationUs / 2, (info.durationUs - 1).coerceAtLeast(0)).distinct()
            for (time in times) {
                currentCoroutineContext().ensureActive()
                val frame = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: error("输出视频的实际帧无法重新解码。")
                try {
                    check(frame.width == info.width && frame.height == info.height) {
                        "重新解码的视频尺寸改变。"
                    }
                    for (mask in masks) {
                        val rect = mask.toPixelRect(frame.width, frame.height)
                        val insetX = minOf(3, (rect.width() - 1) / 2)
                        val insetY = minOf(3, (rect.height() - 1) / 2)
                        val rowWidth = rect.width() - insetX * 2
                        val row = IntArray(rowWidth)
                        for (y in rect.top + insetY until rect.bottom - insetY) {
                            currentCoroutineContext().ensureActive()
                            frame.getPixels(row, 0, rowWidth, rect.left + insetX, y, rowWidth, 1)
                            check(row.all {
                                Color.alpha(it) == 255 && Color.red(it) <= 48 &&
                                    Color.green(it) <= 48 && Color.blue(it) <= 48
                            }) { "实际视频帧的遮挡检查未通过，请扩大遮挡后重新生成。" }
                        }
                    }
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            retriever.release()
        }
    }

    private fun requireMp4(file: File) {
        RandomAccessFile(file, "r").use { input ->
            check(input.length() >= 16) { "MP4 输出不完整。" }
            val length = input.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            check(type == "ftyp" && length in 16..input.length()) { "文件不是有效的 MP4 容器。" }
        }
    }
}
