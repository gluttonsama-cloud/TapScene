package com.tapscene.runtime

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import com.tapscene.media.SourceMetadata
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Test-only raw-recording inspection. The production ten-second transition policy stays intact. */
internal object RuntimeVideoDecode {
    suspend fun decode(file: File, metadata: SourceMetadata, inspect: (Image, Long) -> Unit): List<Long> {
        require(metadata.durationUs in 1..45_000_000 && metadata.rotationDeg == 0)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        var failure: Throwable? = null
        try {
            extractor.setDataSource(file.absolutePath)
            check(extractor.trackCount == 1)
            val format = extractor.getTrackFormat(0)
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
            check(format.getLong(MediaFormat.KEY_DURATION) == metadata.durationUs)
            check(format.getInteger(MediaFormat.KEY_WIDTH) == metadata.width && format.getInteger(MediaFormat.KEY_HEIGHT) == metadata.height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val name = checkNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format))
            val decoder = MediaCodec.createByCodecName(name).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start(); started = true
            extractor.selectTrack(0)
            val info = MediaCodec.BufferInfo()
            val timestamps = mutableListOf<Long>()
            var inputEnded = false
            var inputs = 0
            val startedAt = SystemClock.elapsedRealtime()
            var progressAt = startedAt
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = SystemClock.elapsedRealtime()
                check(now - progressAt < 15_000 && now - startedAt < 180_000) { "Bounded raw-capture decoder timed out" }
                if (!inputEnded) {
                    val slot = decoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val bytes = checkNotNull(decoder.getInputBuffer(slot)).apply { clear() }
                        val pts = extractor.sampleTime
                        if (pts < 0) {
                            decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            check(++inputs <= 3_000 && pts < metadata.durationUs)
                            check(extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) == 0)
                            val length = extractor.readSampleData(bytes, 0)
                            check(length in 1..bytes.capacity())
                            decoder.queueInputBuffer(slot, 0, length, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                val slot = decoder.dequeueOutputBuffer(info, 10_000)
                if (slot >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(timestamps.size < 3_000 && info.presentationTimeUs in 0 until metadata.durationUs)
                            check(timestamps.lastOrNull()?.let { info.presentationTimeUs > it } != false)
                            checkNotNull(decoder.getOutputImage(slot)) { "Selected decoder supplies no inspectable YUV images" }.use { image ->
                                check(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3)
                                check(image.cropRect.width() == metadata.width && image.cropRect.height() == metadata.height)
                                inspect(image, info.presentationTimeUs)
                            }
                            timestamps += info.presentationTimeUs
                            progressAt = SystemClock.elapsedRealtime()
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            check(timestamps.isNotEmpty() && timestamps.size == inputs)
                            return timestamps
                        }
                    } finally { decoder.releaseOutputBuffer(slot, false) }
                } else check(slot in setOf(MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED))
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var cleanup: Throwable? = null
            fun release(block: () -> Unit) = try { block() } catch (error: Throwable) {
                if (cleanup == null) cleanup = error else cleanup!!.addSuppressed(error)
            }
            if (started) release { codec?.stop() }
            release { codec?.release() }
            release { extractor.release() }
            cleanup?.let { if (failure != null) failure.addSuppressed(it) else throw it }
        }
    }
}
