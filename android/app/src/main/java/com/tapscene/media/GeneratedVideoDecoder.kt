package com.tapscene.media

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Derived and viewer-package AVC outputs; ordinary source import never enters here. */
internal class GeneratedVideoDecoder {
    suspend fun validate(
        file: File,
        metadata: SourceMetadata,
        onFrame: (suspend (Image) -> Unit)?,
    ): List<Long> = try {
        withContext(Dispatchers.IO) { decodePass(file, metadata, onFrame) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val detail = if (failure is FrameDecodeException) failure.message else "新生成的视频无法完成逐帧复核。"
        throw FrameDecodeException(
            "$detail [stage=output_yuv, mime=video/avc, size=${metadata.width}x${metadata.height}, " +
                "rotation=${metadata.rotationDeg}, cause=${failure.javaClass.simpleName}]", failure,
        )
    }

    private data class Colour(val standard: Int, val range: Int, val transfer: Int)
    private data class Layout(val width: Int, val height: Int, val crop: Rect, val colour: Colour)
    private data class StartedDecoder(val codec: MediaCodec, val name: String)
    private class DecoderSelection {
        // One finite snapshot per pass; failed names are never retried during that pass.
        val codecs: List<MediaCodecInfo> by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }
        val excludedNames = mutableSetOf<String>()
    }
    private class DecoderImageUnavailable(val codecName: String, val mime: String) : Exception()

    private suspend fun decodePass(
        file: File,
        metadata: SourceMetadata,
        onFrame: (suspend (Image) -> Unit)? = null,
    ): List<Long> {
        val selection = DecoderSelection()
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return decodePassOnce(file, metadata, onFrame, selection)
            } catch (unavailable: DecoderImageUnavailable) {
                currentCoroutineContext().ensureActive()
                // decodePassOnce has closed every image/buffer, codec and extractor before retry.
                // A failed cleanup is not permission to open more device resources.
                if (unavailable.suppressed.isNotEmpty()) {
                    throw FrameDecodeException("解码资源未能正常释放，请重试。", unavailable)
                }
                if (!selection.excludedNames.add(unavailable.codecName)) {
                    throw FrameDecodeException(decoderUnavailableMessage(unavailable.mime), unavailable)
                }
            }
        }
    }

    private suspend fun decodePassOnce(
        file: File,
        metadata: SourceMetadata,
        onFrame: (suspend (Image) -> Unit)?,
        selection: DecoderSelection,
    ): List<Long> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var failure: Throwable? = null
        var activeMime: String? = null
        try {
            currentCoroutineContext().ensureActive()
            if (metadata.width <= 0 || metadata.height <= 0 || metadata.durationUs !in 1..(10_000_000L + MediaLimits.TIMESTAMP_TOLERANCE_US) ||
                metadata.rotationDeg != 0 ||
                minOf(metadata.width, metadata.height) > MediaLimits.MAX_WIDTH ||
                maxOf(metadata.width, metadata.height) > MediaLimits.MAX_HEIGHT
            ) throw FrameDecodeException("输出视频尺寸、方向或时长超出支持范围。")
            extractor.setDataSource(file.absolutePath)
            val (track, inputFormat) = videoTrack(extractor)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
            if (inputMime != MediaFormat.MIMETYPE_VIDEO_AVC) {
                throw FrameDecodeException("自产视频复核只接受 H.264 输出。")
            }
            activeMime = inputMime
            requireBoundedDimensions(inputFormat)
            rejectHdrTransfer(inputFormat)
            AvcOutputPolicy.checkConfiguration(inputFormat, metadata.width, metadata.height)
            if (visibleSize(inputFormat, true) != metadata.width ||
                visibleSize(inputFormat, false) != metadata.height ||
                inputFormat.intOrZero(MediaFormat.KEY_ROTATION) != metadata.rotationDeg ||
                inputFormat.getLong(MediaFormat.KEY_DURATION) != metadata.durationUs
            ) throw FrameDecodeException("输出视频信息与本次生成结果不一致，无法完成复核。")
            extractor.selectTrack(track)
            inputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            // Generated output must already have rotation baked into its pixels.
            inputFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            val startedDecoder = createStartedDecoder(inputFormat, inputMime, selection)
            val decoder = startedDecoder.codec
            codec = decoder
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var inputCount = 0
            var frameCount = 0
            var lastPts: Long? = null
            val timestamps = ArrayList<Long>()
            // Closest-PTS frame dropping is nominally 60 fps; its local cadence can approach
            // 120 fps. Match the writer's bounded budget rather than assuming a hard 60 fps.
            val frameBudget = ((metadata.durationUs * 120 + 999_999) / 1_000_000 + 3).toInt()
            val latestAllowedPts = metadata.durationUs + MediaLimits.TIMESTAMP_TOLERANCE_US
            var layout: Layout? = null
            var producedInspectableOutput = false
            fun imageUnavailable(): Nothing {
                // Never replay validated frames or callbacks. Content/colour/geometry failures use
                // FrameDecodeException, which the outer retry loop deliberately does not catch.
                if (producedInspectableOutput) throw FrameDecodeException(decoderUnavailableMessage(inputMime))
                throw DecoderImageUnavailable(startedDecoder.name, inputMime)
            }
            val startedAt = SystemClock.elapsedRealtime()
            var lastOutputAt = startedAt
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = SystemClock.elapsedRealtime()
                if (now - lastOutputAt > 15_000 || now - startedAt > 180_000) {
                    throw FrameDecodeException("输出视频解码超时，请重试或使用较短的输出视频。")
                }
                if (!inputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                            ?: throw FrameDecodeException("设备未提供可用的解码输入缓冲区。")
                        buffer.clear()
                        val pts = extractor.sampleTime
                        if (pts < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            if (extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                                throw FrameDecodeException("不支持受保护或不完整的视频样本。")
                            }
                            if (pts >= latestAllowedPts || ++inputCount > frameBudget
                            ) throw FrameDecodeException("输出视频时间戳、时长或帧数超出支持范围。")
                            val size = extractor.readSampleData(buffer, 0)
                            if (size <= 0 || size > buffer.capacity()) throw FrameDecodeException("输出视频包含不完整的视频样本。")
                            AvcOutputPolicy.checkSample(buffer, size, metadata.width, metadata.height)
                            decoder.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        verifyOutputFormat(decoder.outputFormat, inputFormat, metadata)
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        var outputFailure: Throwable? = null
                        try {
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && !isConfig) {
                                lastOutputAt = SystemClock.elapsedRealtime()
                                val pts = info.presentationTimeUs
                                if (pts < 0 || pts >= latestAllowedPts || ++frameCount > frameBudget) {
                                    throw FrameDecodeException("解码后的画面时间戳或帧数无效。")
                                }
                                if (lastPts != null && pts <= lastPts) throw FrameDecodeException("输出视频画面时间戳无效。")
                                val outputFormat = decoder.getOutputFormat(outputIndex)
                                val colour = verifyOutputFormat(outputFormat, inputFormat, metadata)
                                val outputImage = decoder.getOutputImage(outputIndex) ?: imageUnavailable()
                                outputImage.use { image ->
                                    if (image.format != ImageFormat.YUV_420_888) imageUnavailable()
                                    checkImage(image, metadata)
                                    producedInspectableOutput = true
                                    val currentLayout = Layout(image.width, image.height, Rect(image.cropRect), colour)
                                    if (layout != null && layout != currentLayout) {
                                        throw FrameDecodeException("输出视频中途改变了尺寸、方向或色彩格式，无法完成复核。")
                                    }
                                    layout = currentLayout
                                    lastPts = pts
                                    timestamps.add(pts)
                                    onFrame?.invoke(image)
                                }
                            }
                            if (eos) {
                                if (frameCount == 0 || frameCount != inputCount) {
                                    throw FrameDecodeException("新生成视频未完整解码到结束，不能确认遮挡。")
                                }
                                return timestamps
                            }
                        } catch (error: Throwable) {
                            outputFailure = error
                            throw error
                        } finally {
                            try {
                                decoder.releaseOutputBuffer(outputIndex, false)
                            } catch (error: Exception) {
                                if (outputFailure != null) outputFailure.addSuppressed(error) else throw error
                            }
                        }
                    } else throw FrameDecodeException("设备返回了未知的解码状态。")
                }
            }
        } catch (error: Throwable) {
            val reported = if (error is MediaCodec.CodecException) {
                FrameDecodeException(decoderUnavailableMessage(activeMime ?: "未知视频编码"), error)
            } else error
            failure = reported
            throw reported
        } finally {
            // Release even when configure/start failed; cleanup must not replace cancellation.
            var cleanupFailure: Throwable? = null
            try { codec?.release() } catch (error: Exception) { cleanupFailure = error }
            try { extractor.release() } catch (error: Exception) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure) else {
                    throw FrameDecodeException("解码资源未能正常释放，请重试。", cleanupFailure)
                }
            }
        }
    }

    private fun verifyOutputFormat(output: MediaFormat, input: MediaFormat, metadata: SourceMetadata): Colour {
        requireBoundedDimensions(output)
        rejectHdrTransfer(output)
        if (visibleSize(output, true) != metadata.width || visibleSize(output, false) != metadata.height ||
            output.intOrZero(MediaFormat.KEY_ROTATION) != 0
        ) throw FrameDecodeException("输出视频中途改变了尺寸或方向，暂不支持此格式。")
        // Encoded configuration and every in-band SPS pass AvcOutputPolicy first.
        // Some muxers/codecs omit colour tags. Missing tags are not evidence of HDR.
        fun value(key: String, fallback: Int) = output.intOrZero(key).takeIf { it != 0 }
            ?: input.intOrZero(key).takeIf { it != 0 } ?: fallback
        val colour = Colour(
            value(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709),
            value(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED),
            value(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO),
        )
        return colour
    }

    private fun requireBoundedDimensions(format: MediaFormat) {
        // A tiny crop must not hide an enormous coded image; AVC may add macroblock padding.
        val maximum = MediaLimits.MAX_HEIGHT + 16
        if (format.getInteger(MediaFormat.KEY_WIDTH) !in 1..maximum ||
            format.getInteger(MediaFormat.KEY_HEIGHT) !in 1..maximum
        ) throw FrameDecodeException("输出视频编码尺寸超出支持范围。")
    }

    private fun rejectHdrTransfer(format: MediaFormat) {
        // BT.2020 primaries, 10-bit profiles and absent tags do not establish HDR. The
        // all-frame mask check operates on decoded black YUV pixels, not an RGB conversion.
        if (format.intOrZero(MediaFormat.KEY_COLOR_TRANSFER) in setOf(
                MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG,
            )
        ) throw FrameDecodeException("输出仍使用 PQ/HLG HDR，无法按本次 SDR 输出完成复核。")
    }

    private fun checkImage(image: Image, metadata: SourceMetadata) {
        val crop = image.cropRect
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size != 3 || crop.left < 0 || crop.top < 0 ||
            crop.right > image.width || crop.bottom > image.height ||
            crop.width() != metadata.width || crop.height() != metadata.height
        ) throw FrameDecodeException("设备输出了不支持的画面格式或裁切范围。")
        image.planes.forEachIndexed { index, plane ->
            val divisor = if (index == 0) 1 else 2
            val lastX = (crop.right - 1) / divisor
            val lastY = (crop.bottom - 1) / divisor
            val buffer = plane.buffer
            val lastIndex = buffer.position().toLong() + lastY.toLong() * plane.rowStride + lastX.toLong() * plane.pixelStride
            if (plane.rowStride <= 0 || plane.pixelStride <= 0 || lastIndex >= buffer.limit() ||
                lastX.toLong() * plane.pixelStride >= plane.rowStride
            ) throw FrameDecodeException("设备输出的 YUV 缓冲区不完整。")
        }
    }

    /** Only generated output needs inspectable YUV planes for all-frame mask verification. */
    private suspend fun createStartedDecoder(
        format: MediaFormat,
        mime: String,
        selection: DecoderSelection,
    ): StartedDecoder {
        var lastFailure: Exception? = null
        val candidates = try {
            selection.codecs.filter { candidate ->
                if (candidate.name in selection.excludedNames || candidate.isEncoder ||
                    !candidate.supportedTypes.any { it.equals(mime, ignoreCase = true) }) false
                else try {
                    val capabilities = candidate.getCapabilitiesForType(mime)
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in capabilities.colorFormats &&
                        capabilities.isFormatSupported(format)
                } catch (_: Exception) { false }
            }
        } catch (error: Exception) {
            throw FrameDecodeException(decoderUnavailableMessage(mime), error)
        }
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            var decoder: MediaCodec? = null
            try {
                decoder = MediaCodec.createByCodecName(candidate.name)
                decoder.configure(format, null, null, 0)
                decoder.start()
                return StartedDecoder(decoder, candidate.name)
            } catch (cancelled: CancellationException) {
                try { decoder?.release() } catch (cleanup: Exception) { cancelled.addSuppressed(cleanup) }
                throw cancelled
            } catch (error: Exception) {
                try { decoder?.release() } catch (cleanup: Exception) {
                    error.addSuppressed(cleanup)
                    throw FrameDecodeException("解码资源未能正常释放，请重试。", error)
                }
                lastFailure = error
                selection.excludedNames.add(candidate.name)
            } catch (fatal: Throwable) {
                try { decoder?.release() } catch (cleanup: Throwable) { fatal.addSuppressed(cleanup) }
                throw fatal
            }
        }
        throw FrameDecodeException(decoderUnavailableMessage(mime), lastFailure)
    }

    private fun decoderUnavailableMessage(mime: String): String =
        "此设备无法复核新生成的 $mime 视频遮挡。原素材已保留；请重试或改用图片。"
}
