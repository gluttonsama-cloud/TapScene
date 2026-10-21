package com.tapscene.recording

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.math.abs

/** Private original-evidence decoding. Neither entry point confers privacy-review authority. */
internal object FrameEvidencePng {
    const val MAX_THUMBNAIL_PIXELS = 220_000L
    private const val MAX_ORIGINAL_PIXELS = 12_000_000L
    private const val BUFFER_BYTES = 32 * 1024
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val allowedChunks = setOf("IHDR", "IDAT", "IEND", "sRGB", "gAMA", "cHRM", "sBIT")

    fun decodeOriginal(bytes: ByteArray, width: Int, height: Int, checkActive: () -> Unit = {}): Bitmap =
        decode(bytes, width, height, thumbnail = false, checkActive = checkActive)

    fun decodeThumbnail(bytes: ByteArray, width: Int, height: Int, checkActive: () -> Unit = {}): Bitmap =
        decode(bytes, width, height, thumbnail = true, checkActive = checkActive)

    /** Validation is unconditional and covers the entire original, not just sampled pixels. */
    private fun decode(bytes: ByteArray, width: Int, height: Int, thumbnail: Boolean, checkActive: () -> Unit): Bitmap {
        validate(bytes, width, height, checkActive)
        checkActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        checkActive()
        check(bounds.outMimeType == "image/png" && bounds.outWidth == width && bounds.outHeight == height)
        var sample = 1
        if (thumbnail) {
            // Ceil, rather than integer truncation, also bounds very thin and odd-sized originals.
            while (sampledLength(width, sample) * sampledLength(height, sample) > MAX_THUMBNAIL_PIXELS) {
                checkActive()
                sample *= 2
            }
        }
        checkActive()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inSampleSize = sample
        }) ?: throw IOException("Frame PNG does not fully decode")
        try {
            // Native decode itself cannot be interrupted. Cancellation immediately afterward must
            // recycle its result instead of publishing it to a stale consumer.
            checkActive()
            check(bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.width > 0 && bitmap.height > 0)
            if (thumbnail) {
                check(bitmap.width.toLong() * bitmap.height <= MAX_THUMBNAIL_PIXELS &&
                    bitmap.width <= sampledLength(width, sample) && bitmap.height <= sampledLength(height, sample))
            } else check(bitmap.width == width && bitmap.height == height)
            val pixels = IntArray(minOf(bitmap.width, 4096))
            for (y in 0 until bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    checkActive()
                    val count = minOf(pixels.size, bitmap.width - x)
                    bitmap.getPixels(pixels, 0, count, x, y, count, 1)
                    for (index in 0 until count) check(Color.alpha(pixels[index]) == 255) {
                        "Frame PNG contains transparent pixels"
                    }
                    x += count
                }
            }
            checkActive()
            return bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    private fun sampledLength(length: Int, sample: Int): Long = (length.toLong() + sample - 1) / sample

    /**
     * Strict finite RGB/RGBA PNG with no transparency metadata, unknown chunks, suffix or partial
     * deflate stream. Inflation uses a fixed buffer and input slices, not whole decoded rows or
     * another collected copy of IDAT. Encoded input itself may still be up to 12 MiB.
     */
    private fun validate(bytes: ByteArray, width: Int, height: Int, checkActive: () -> Unit) {
        checkActive()
        require(bytes.size in signature.size..FrameEvidenceStore.MAX_PNG_BYTES &&
            width > 0 && height > 0 && width.toLong() * height <= MAX_ORIGINAL_PIXELS)
        check(signature.indices.all { bytes[it] == signature[it] }) { "Invalid PNG signature" }
        val inflater = Inflater()
        try {
            var offset = signature.size
            var channels = 0
            var header = false
            var pixels = false
            var ended = false
            val ancillary = mutableSetOf<String>()
            var stream: PixelStream? = null
            val buffer = ByteArray(BUFFER_BYTES)
            while (!ended) {
                checkActive()
                check(bytes.size - offset >= 12) { "Incomplete PNG chunk" }
                val length = unsignedInt(bytes, offset)
                check(length <= bytes.size.toLong() - offset - 12) { "Invalid PNG chunk length" }
                val count = length.toInt()
                val typeOffset = offset + 4
                val payload = offset + 8
                val type = String(bytes, typeOffset, 4, Charsets.US_ASCII)
                check(type in allowedChunks && (header || type == "IHDR")) { "Unexpected PNG chunk" }
                val crc = CRC32()
                var crcOffset = typeOffset
                val crcEnd = payload + count
                while (crcOffset < crcEnd) {
                    checkActive()
                    val size = minOf(BUFFER_BYTES, crcEnd - crcOffset)
                    crc.update(bytes, crcOffset, size)
                    crcOffset += size
                }
                check(unsignedInt(bytes, crcEnd) == crc.value) { "PNG CRC differs" }
                when (type) {
                    "IHDR" -> {
                        check(!header && count == 13)
                        check(unsignedInt(bytes, payload) == width.toLong() &&
                            unsignedInt(bytes, payload + 4) == height.toLong() && byte(bytes, payload + 8) == 8)
                        channels = when (byte(bytes, payload + 9)) {
                            2 -> 3
                            6 -> 4
                            else -> error("Unexpected PNG color type")
                        }
                        check(byte(bytes, payload + 10) == 0 && byte(bytes, payload + 11) == 0 &&
                            byte(bytes, payload + 12) == 0) { "Unsupported PNG encoding" }
                        stream = PixelStream(width, height, channels)
                        header = true
                    }
                    "IDAT" -> {
                        pixels = true
                        if (count > 0) {
                            check(!inflater.finished() && inflater.needsInput()) { "PNG compressed suffix" }
                            inflater.setInput(bytes, payload, count)
                            while (!inflater.finished()) {
                                checkActive()
                                val inflated = inflater.inflate(buffer)
                                checkActive()
                                check(!inflater.needsDictionary()) { "Unexpected PNG dictionary" }
                                if (inflated > 0) checkNotNull(stream).accept(buffer, inflated)
                                else {
                                    check(inflater.finished() || inflater.needsInput()) { "Incomplete PNG pixel stream" }
                                    break
                                }
                            }
                            check(inflater.remaining == 0) { "PNG compressed suffix" }
                        }
                    }
                    "IEND" -> {
                        check(pixels && count == 0 && inflater.finished()) { "Incomplete PNG pixel stream" }
                        checkNotNull(stream).finish()
                        ended = true
                    }
                    else -> {
                        // These are the sole non-pixel metadata accepted from the local encoder.
                        // PNG requires them before IDAT and at most once.
                        check(!pixels && ancillary.add(type)) { "Invalid PNG metadata order" }
                        when (type) {
                            "sRGB" -> check(count == 1 && byte(bytes, payload) in 0..3)
                            "gAMA" -> check(count == 4 && unsignedInt(bytes, payload) > 0)
                            "cHRM" -> check(count == 32)
                            "sBIT" -> check(count == channels &&
                                (0 until count).all { byte(bytes, payload + it) in 1..8 })
                        }
                    }
                }
                offset = crcEnd + 4
            }
            check(offset == bytes.size) { "Trailing PNG bytes" }
            checkActive()
        } finally { inflater.end() }
    }

    /**
     * Alpha reconstruction is independent of RGB bytes. All accepted earlier alpha values are
     * 255, so left/up/upper-left predictors are 255 or a PNG boundary zero. This induction checks
     * every original pixel without allocating a row, even for a very wide image.
     */
    private class PixelStream(width: Int, height: Int, private val channels: Int) {
        private val rowLength = width.toLong() * channels + 1
        private val expected = rowLength * height
        private var decoded = 0L
        private var filter = 0

        fun accept(bytes: ByteArray, count: Int) {
            check(decoded + count <= expected) { "Overlong PNG pixel stream" }
            for (index in 0 until count) {
                val position = decoded % rowLength
                val value = byte(bytes, index)
                if (position == 0L) {
                    check(value in 0..4) { "Invalid PNG row filter" }
                    filter = value
                } else if (channels == 4 && position % channels == 0L) {
                    val hasLeft = position > channels
                    val hasUp = decoded >= rowLength
                    val left = if (hasLeft) 255 else 0
                    val up = if (hasUp) 255 else 0
                    val upperLeft = if (hasLeft && hasUp) 255 else 0
                    val predictor = when (filter) {
                        0 -> 0
                        1 -> left
                        2 -> up
                        3 -> (left + up) / 2
                        else -> paeth(left, up, upperLeft)
                    }
                    check(((value + predictor) and 0xff) == 255) { "Frame PNG contains transparent pixels" }
                }
                decoded++
            }
        }

        fun finish() { check(decoded == expected) { "PNG pixel length differs" } }
    }

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val prediction = left + up - upperLeft
        val a = abs(prediction - left)
        val b = abs(prediction - up)
        val c = abs(prediction - upperLeft)
        return if (a <= b && a <= c) left else if (b <= c) up else upperLeft
    }

    private fun byte(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff
    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        (byte(bytes, offset).toLong() shl 24) or (byte(bytes, offset + 1).toLong() shl 16) or
            (byte(bytes, offset + 2).toLong() shl 8) or byte(bytes, offset + 3).toLong()
}
