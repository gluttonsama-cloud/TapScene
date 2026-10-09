package com.tapscene.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Native byte-buffer decoding: frame identity always comes from BufferInfo.presentationTimeUs. */
class VideoFrameDecoder(context: Context) {
    private val privateRoot = context.applicationContext.noBackupFilesDir

    /** First decoded frame at/after the request, or the actual last frame if the request reaches EOS. */
    suspend fun decode(source: ImportedSource, requestedTimeUs: Long): DecodedFrame {
        var ownedBitmap: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                if (requestedTimeUs < 0) throw FrameDecodeException("取帧时刻不能小于零。")
                val file = resolve(source)
                val target = requestedTimeUs.coerceAtMost(source.metadata.durationUs)
                val first = decodePass(file, source.metadata, target)
                val frame = first.frame ?: run {
                    // Never relabel a previous frame with the requested time. Decode the actual
                    // last PTS a second time, avoiding an RGB allocation for every skipped frame.
                    val lastPts = first.lastPts ?: throw FrameDecodeException("录屏没有可解码的画面。")
                    decodePass(file, source.metadata, lastPts).frame
                        ?: throw FrameDecodeException("无法读取录屏的最后一帧。")
                }
                ownedBitmap = frame.bitmap
                frame
            }
        } catch (cancelled: CancellationException) {
            ownedBitmap?.recycle()
            throw cancelled
        } catch (known: FrameDecodeException) {
            ownedBitmap?.recycle()
            throw known
        } catch (failure: Exception) {
            ownedBitmap?.recycle()
            throw FrameDecodeException("录屏解码失败，文件可能损坏或此设备不支持该格式。", failure)
        }
    }

    /**
     * Decode every video sample before registration. [onFrame] may inspect each validated image
     * while it is owned by the decoder; it must neither retain nor close it. Images are cropped
     * YUV planes in unrotated coordinates. Generated-media checks use this to verify every mask.
     */
    internal suspend fun validate(
        file: File,
        metadata: SourceMetadata,
        onFrame: (suspend (Image) -> Unit)? = null,
    ) {
        try {
            withContext(Dispatchers.IO) { decodePass(file, metadata, targetUs = null, onFrame = onFrame) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: FrameDecodeException) {
            throw known
        } catch (failure: Exception) {
            throw FrameDecodeException("无法完整解码录屏，请重新录制或在外部转换后导入。", failure)
        }
    }

    private fun resolve(source: ImportedSource): File {
        val expectedPath = "${MediaLimits.SOURCE_DIRECTORY}/${source.sourceId}.mp4"
        if (!source.sourceId.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) ||
            source.privateRelativePath != expectedPath
        ) throw FrameDecodeException("素材路径无效，请重新导入。")
        val directory = File(privateRoot, MediaLimits.SOURCE_DIRECTORY).canonicalFile
        val file = File(privateRoot, expectedPath).canonicalFile
        if (directory.parentFile != privateRoot.canonicalFile || file.parentFile != directory ||
            !file.isFile || file.length() != source.metadata.byteLength
        ) throw FrameDecodeException("本机素材缺失或已改变，请重新导入。")
        return file
    }

    private data class PassResult(val frame: DecodedFrame?, val lastPts: Long?)
    private data class Colour(val standard: Int, val range: Int, val transfer: Int)
    private data class Layout(val width: Int, val height: Int, val crop: Rect, val colour: Colour)

    private suspend fun decodePass(
        file: File,
        metadata: SourceMetadata,
        targetUs: Long?,
        onFrame: (suspend (Image) -> Unit)? = null,
    ): PassResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var failure: Throwable? = null
        var selectedBitmap: Bitmap? = null
        try {
            currentCoroutineContext().ensureActive()
            if (metadata.width <= 0 || metadata.height <= 0 || metadata.durationUs !in 1..MediaLimits.MAX_DURATION_US ||
                metadata.rotationDeg !in setOf(0, 90, 180, 270) || metadata.displayWidth >= metadata.displayHeight ||
                metadata.displayWidth > MediaLimits.MAX_WIDTH || metadata.displayHeight > MediaLimits.MAX_HEIGHT
            ) throw FrameDecodeException("录屏尺寸、方向或时长超出支持范围。")
            extractor.setDataSource(file.absolutePath)
            val (track, inputFormat) = videoTrack(extractor)
            if (inputFormat.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_VIDEO_AVC) {
                throw FrameDecodeException("只支持 H.264 录屏。")
            }
            requireBoundedDimensions(inputFormat)
            val parameterSets = requireEightBitAvc(inputFormat)
            rejectUnsupportedColour(inputFormat)
            if (visibleSize(inputFormat, true) != metadata.width ||
                visibleSize(inputFormat, false) != metadata.height ||
                inputFormat.intOrZero(MediaFormat.KEY_ROTATION) != metadata.rotationDeg ||
                inputFormat.getLong(MediaFormat.KEY_DURATION) != metadata.durationUs
            ) throw FrameDecodeException("录屏信息已经改变，请重新导入。")
            extractor.selectTrack(track)
            if (targetUs != null) {
                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                // Some extractors report EOS when seeking exactly to duration.
                if (extractor.sampleTime < 0) extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            inputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            // Rotation is applied exactly once after YUV conversion, not by the decoder.
            inputFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var inputCount = 0
            var frameCount = 0
            var lastPts: Long? = null
            val recentPts = ArrayDeque<Long>()
            var layout: Layout? = null
            val startedAt = SystemClock.elapsedRealtime()
            var lastOutputAt = startedAt
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = SystemClock.elapsedRealtime()
                if (now - lastOutputAt > 15_000 || now - startedAt > 180_000) {
                    throw FrameDecodeException("录屏解码超时，请重试或使用较短的录屏。")
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
                            if (pts >= metadata.durationUs || pts > MediaLimits.MAX_DURATION_US ||
                                ++inputCount > MediaLimits.MAX_FRAMES
                            ) throw FrameDecodeException("录屏时间戳、时长或帧数超出支持范围。")
                            val size = extractor.readSampleData(buffer, 0)
                            if (size <= 0 || size > buffer.capacity()) throw FrameDecodeException("录屏包含不完整的视频样本。")
                            checkInBandParameterSets(buffer, size, parameterSets)
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
                                if (pts < 0 || pts >= metadata.durationUs || ++frameCount > MediaLimits.MAX_FRAMES) {
                                    throw FrameDecodeException("解码后的画面时间戳或帧数无效。")
                                }
                                if (lastPts != null && pts <= lastPts) throw FrameDecodeException("录屏画面时间戳无效。")
                                recentPts.addLast(pts)
                                if (recentPts.size > 61) recentPts.removeFirst()
                                // Check all recent 0.1–1 second spans so slow preceding frames
                                // cannot hide a later high-rate burst. A 1 kHz MP4 clock legitimately
                                // gives 16/17 ms, so allow only the clock's 1 ms quantization margin.
                                var intervals = recentPts.size - 1
                                for (previous in recentPts) {
                                    if (intervals >= 6 && pts - previous <
                                        intervals * 1_000_000L / 60 - MediaLimits.TIMESTAMP_TOLERANCE_US
                                    ) throw FrameDecodeException("录屏帧率超过 60 fps。")
                                    intervals--
                                }
                                val outputFormat = decoder.getOutputFormat(outputIndex)
                                val colour = verifyOutputFormat(outputFormat, inputFormat, metadata)
                                val outputImage = decoder.getOutputImage(outputIndex)
                                    ?: throw FrameDecodeException("此设备不能提供可检查的 YUV 画面。")
                                outputImage.use { image ->
                                    checkImage(image, metadata)
                                    val currentLayout = Layout(image.width, image.height, Rect(image.cropRect), colour)
                                    if (layout != null && layout != currentLayout) {
                                        throw FrameDecodeException("录屏中途改变了尺寸、方向或色彩格式，请使用固定竖屏录屏。")
                                    }
                                    layout = currentLayout
                                    lastPts = pts
                                    onFrame?.invoke(image)
                                    if (targetUs != null && pts >= targetUs) {
                                        selectedBitmap = toBitmap(image, colour, metadata.rotationDeg)
                                    }
                                }
                                if (selectedBitmap != null) return PassResult(DecodedFrame(selectedBitmap!!, pts), pts)
                            }
                            if (eos) {
                                if (frameCount == 0 || (targetUs == null && frameCount != inputCount)) {
                                    throw FrameDecodeException("录屏没有完整解码，请重新录制或转换后导入。")
                                }
                                if (targetUs == null && frameCount * 1_000_000L >
                                    60L * (metadata.durationUs + MediaLimits.TIMESTAMP_TOLERANCE_US)
                                ) throw FrameDecodeException("录屏实际帧率超过 60 fps。")
                                return PassResult(null, lastPts)
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
            failure = error
            selectedBitmap?.recycle()
            throw error
        } finally {
            // Release even when configure/start failed; cleanup must not replace cancellation.
            var cleanupFailure: Throwable? = null
            try { codec?.release() } catch (error: Exception) { cleanupFailure = error }
            try { extractor.release() } catch (error: Exception) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure) else {
                    selectedBitmap?.recycle()
                    throw FrameDecodeException("解码资源未能正常释放，请重试。", cleanupFailure)
                }
            }
        }
    }

    private fun verifyOutputFormat(output: MediaFormat, input: MediaFormat, metadata: SourceMetadata): Colour {
        requireBoundedDimensions(output)
        rejectUnsupportedColour(output)
        if (visibleSize(output, true) != metadata.width || visibleSize(output, false) != metadata.height ||
            output.intOrZero(MediaFormat.KEY_ROTATION) != 0
        ) throw FrameDecodeException("录屏中途改变了尺寸或方向，暂不支持此格式。")
        fun value(key: String) = output.intOrZero(key).takeIf { it != 0 } ?: input.intOrZero(key)
        val colour = Colour(value(MediaFormat.KEY_COLOR_STANDARD), value(MediaFormat.KEY_COLOR_RANGE), value(MediaFormat.KEY_COLOR_TRANSFER))
        if (colour.standard !in setOf(MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC) ||
            colour.range !in setOf(MediaFormat.COLOR_RANGE_FULL, MediaFormat.COLOR_RANGE_LIMITED) ||
            colour.transfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        ) throw FrameDecodeException("无法确认录屏为受支持的 SDR 色彩格式，请在外部转换后导入。")
        return colour
    }

    private fun requireBoundedDimensions(format: MediaFormat) {
        // A tiny crop must not hide an enormous coded image. Permit macroblock padding only.
        val maximum = MediaLimits.MAX_HEIGHT + 16
        if (format.getInteger(MediaFormat.KEY_WIDTH) !in 1..maximum ||
            format.getInteger(MediaFormat.KEY_HEIGHT) !in 1..maximum
        ) throw FrameDecodeException("录屏编码尺寸超出支持范围。")
    }

    private fun rejectUnsupportedColour(format: MediaFormat) {
        val transfer = format.intOrZero(MediaFormat.KEY_COLOR_TRANSFER)
        val standard = format.intOrZero(MediaFormat.KEY_COLOR_STANDARD)
        if (format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) || format.containsKey("hdr10-plus-info") ||
            (transfer != 0 && transfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO) ||
            (standard != 0 && standard !in setOf(MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC))
        ) throw FrameDecodeException("暂不支持 HDR 或未知色彩格式，请使用 SDR 录屏。")
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

    private suspend fun toBitmap(image: Image, colour: Colour, rotation: Int): Bitmap {
        val crop = image.cropRect
        val pixels = IntArray(crop.width() * crop.height())
        val planes = image.planes
        val buffers = planes.map { it.buffer.duplicate() }
        val starts = buffers.map { it.position() }
        val full = colour.range == MediaFormat.COLOR_RANGE_FULL
        val bt709 = colour.standard == MediaFormat.COLOR_STANDARD_BT709
        val connector = srgbConnector(colour.standard)
        val rgb = FloatArray(3)
        fun sample(plane: Int, x: Int, y: Int): Int {
            val index = starts[plane] + y * planes[plane].rowStride + x * planes[plane].pixelStride
            return buffers[plane].get(index).toInt() and 0xff
        }
        for (row in 0 until crop.height()) {
            currentCoroutineContext().ensureActive()
            val y = row + crop.top
            for (column in 0 until crop.width()) {
                val x = column + crop.left
                val luma = sample(0, x, y)
                val u = sample(1, x / 2, y / 2) - 128.0
                val v = sample(2, x / 2, y / 2) - 128.0
                val yy = if (full) luma.toDouble() else (luma - 16.0) * (255.0 / 219.0)
                val chromaScale = if (full) 1.0 else 255.0 / 224.0
                val red = yy + (if (bt709) 1.5748 else 1.402) * chromaScale * v
                val green = yy - (if (bt709) 0.187324 else 0.344136) * chromaScale * u -
                    (if (bt709) 0.468124 else 0.714136) * chromaScale * v
                val blue = yy + (if (bt709) 1.8556 else 1.772) * chromaScale * u
                // The YUV matrix produces source-encoded RGB, not sRGB. Convert transfer and
                // primaries before putting pixels into Android's default sRGB bitmap.
                rgb[0] = (red / 255.0).toFloat().coerceIn(0f, 1f)
                rgb[1] = (green / 255.0).toFloat().coerceIn(0f, 1f)
                rgb[2] = (blue / 255.0).toFloat().coerceIn(0f, 1f)
                connector.transform(rgb)
                pixels[row * crop.width() + column] = (0xff shl 24) or
                    ((rgb[0] * 255).roundToInt().coerceIn(0, 255) shl 16) or
                    ((rgb[1] * 255).roundToInt().coerceIn(0, 255) shl 8) or
                    (rgb[2] * 255).roundToInt().coerceIn(0, 255)
            }
        }
        currentCoroutineContext().ensureActive()
        val bitmap = Bitmap.createBitmap(pixels, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        if (rotation == 0) return bitmap
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, false).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun srgbConnector(standard: Int): ColorSpace.Connector {
        val source = if (standard == MediaFormat.COLOR_STANDARD_BT709) {
            ColorSpace.get(ColorSpace.Named.BT709)
        } else {
            // BT.601 625-line (PAL) and 525-line (SMPTE-C/NTSC) use different primaries.
            val primaries = if (standard == MediaFormat.COLOR_STANDARD_BT601_PAL) {
                floatArrayOf(0.640f, 0.330f, 0.290f, 0.600f, 0.150f, 0.060f)
            } else {
                floatArrayOf(0.630f, 0.340f, 0.310f, 0.595f, 0.155f, 0.070f)
            }
            ColorSpace.Rgb("TapScene BT.601 SDR", primaries, floatArrayOf(0.3127f, 0.3290f),
                ColorSpace.Rgb.TransferParameters(1.0 / 1.099, 0.099 / 1.099, 1.0 / 4.5, 0.081, 1.0 / 0.45))
        }
        return ColorSpace.connect(source, ColorSpace.get(ColorSpace.Named.SRGB))
    }

    /** Reject High-10/4:2:2/4:4:4 before a codec can silently down-convert to 8-bit output. */
    private fun requireEightBitAvc(format: MediaFormat): List<ByteArray> {
        val csd = format.getByteBuffer("csd-0")?.duplicate()
            ?: throw FrameDecodeException("录屏缺少可验证的 H.264 参数。")
        if (csd.remaining() !in 5..65_536) throw FrameDecodeException("H.264 参数无效。")
        val parameterSets = ArrayList<ByteArray>()
        val data = csd.slice()
        forEachNal(data, data.remaining()) { start, end ->
            if (data.get(start).toInt() and 0x1f == 7) {
                val nal = ByteArray(end - start) { data.get(start + it) }
                val payload = ArrayList<Byte>()
                var zeros = 0
                for (i in 1 until nal.size) {
                    val value = nal[i].toInt() and 0xff
                    if (zeros >= 2 && value == 3) { zeros = 0; continue }
                    payload.add(nal[i])
                    zeros = if (value == 0) zeros + 1 else 0
                }
                val bits = SpsBits(payload.toByteArray())
                val profile = bits.read(8)
                bits.read(8) // constraint flags
                bits.read(8) // level_idc
                bits.ue() // seq_parameter_set_id
                if (profile !in setOf(66, 77, 88, 100)) throw FrameDecodeException("只支持 8 位 H.264 SDR 录屏。")
                if (profile == 100 && (bits.ue() != 1 || bits.ue() != 0 || bits.ue() != 0)) {
                    throw FrameDecodeException("只支持 8 位、4:2:0 的 H.264 录屏。")
                }
                parameterSets.add(nal)
            }
        }
        if (parameterSets.isEmpty()) throw FrameDecodeException("无法验证录屏的 H.264 编码参数。")
        return parameterSets
    }

    private fun checkInBandParameterSets(buffer: ByteBuffer, size: Int, allowed: List<ByteArray>) {
        // Android's MP4 extractor delivers AVC samples with Annex-B start codes. Inspect every
        // replacement SPS before queueing it: a decoder can otherwise hide a High-10 change by
        // converting it into the requested 8-bit YUV output, without changing frame dimensions.
        forEachNal(buffer, size) { start, end ->
            val type = buffer.get(start).toInt() and 0x1f
            if (type == 7 && allowed.none { nal ->
                    nal.size == end - start && nal.indices.all { buffer.get(start + it) == nal[it] }
                }
            ) throw FrameDecodeException("录屏中途改变了 H.264 编码参数，请重新录制固定格式的视频。")
            if (type !in setOf(1, 5, 6, 7, 8, 9, 10, 11, 12)) {
                throw FrameDecodeException("录屏包含暂不支持的 H.264 扩展。")
            }
        }
    }

    private inline fun forEachNal(buffer: ByteBuffer, size: Int, block: (Int, Int) -> Unit) {
        var nalStart = -1
        var zeros = 0
        var found = false
        for (i in 0 until size) {
            val value = buffer.get(i).toInt() and 0xff
            if (value == 1 && zeros >= 2) {
                if (nalStart >= 0) {
                    val end = i - zeros
                    if (end <= nalStart) throw FrameDecodeException("H.264 视频样本不完整。")
                    block(nalStart, end)
                } else if (i != zeros) {
                    throw FrameDecodeException("H.264 视频样本格式无效。")
                }
                nalStart = i + 1
                found = true
            }
            zeros = if (value == 0) zeros + 1 else 0
        }
        val end = size - zeros
        if (!found || nalStart < 0 || end <= nalStart) throw FrameDecodeException("H.264 视频样本格式无效。")
        block(nalStart, end)
    }

    private class SpsBits(private val bytes: ByteArray) {
        private var bit = 0
        fun read(count: Int): Int {
            if (count !in 0..30 || bit + count > bytes.size * 8) throw FrameDecodeException("H.264 参数已损坏。")
            var value = 0
            repeat(count) { value = (value shl 1) or ((bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1); bit++ }
            return value
        }
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) {
                if (++zeros > 20) throw FrameDecodeException("H.264 参数超出支持范围。")
            }
            return ((1 shl zeros) - 1) + read(zeros)
        }
    }
}
