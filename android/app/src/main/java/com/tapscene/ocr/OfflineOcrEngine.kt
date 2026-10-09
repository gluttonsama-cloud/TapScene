package com.tapscene.ocr

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Offline, one image at a time; callers own the bitmap and must not mutate/recycle it mid-call. */
class OfflineOcrEngine(context: Context) {
    private val models = OcrModelStore(context)

    suspend fun recognize(bitmap: Bitmap, cancellation: OcrCancellation = OcrCancellation()): OcrResult =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                // Register before model preparation and JNI. Cancellation can reach native code
                // while this IO worker is inside the blocking recognizer.
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    var signal = 0L
                    var pixels: ByteArray? = null
                    try {
                        checkCancellation(cancellation)
                        if (!OcrNative.available) throw OcrException(OcrError.ENGINE_UNAVAILABLE)
                        if (bitmap.isRecycled || bitmap.width < 1 || bitmap.height < 1) throw OcrException(OcrError.INVALID_INPUT)
                        val width = bitmap.width
                        val height = bitmap.height
                        if (width > MAX_EDGE || height > MAX_EDGE || width.toLong() * height > MAX_PIXELS) {
                            throw OcrException(OcrError.INPUT_TOO_LARGE)
                        }
                        val modelDir = models.ensureReady(cancellation)
                        pixels = rgbPixels(bitmap, cancellation)
                        signal = OcrNative.createSignal()
                        cancellation.bind(signal)
                        checkCancellation(cancellation)
                        val encoded = OcrNative.recognizeRgb(pixels, width, height, modelDir.absolutePath, signal)
                        val result = try { decode(encoded, width, height) } finally { encoded.fill(0) }
                        checkCancellation(cancellation)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (cancel: CancellationException) {
                        if (continuation.isActive) continuation.resumeWithException(cancel)
                    } catch (error: OcrException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } catch (error: IllegalStateException) {
                        val sanitized = if (error.message == "CANCELLED") CancellationException("OCR_CANCELLED") else {
                            val code = OcrError.entries.firstOrNull { it.name == error.message } ?: OcrError.RECOGNITION_FAILED
                            OcrException(code)
                        }
                        if (continuation.isActive) continuation.resumeWithException(sanitized)
                    } catch (_: OutOfMemoryError) {
                        if (continuation.isActive) continuation.resumeWithException(OcrException(OcrError.RESOURCE_LIMIT))
                    } catch (_: LinkageError) {
                        if (continuation.isActive) continuation.resumeWithException(OcrException(OcrError.ENGINE_UNAVAILABLE))
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(OcrException(OcrError.RECOGNITION_FAILED))
                    } finally {
                        pixels?.fill(0)
                        cancellation.unbind()
                        if (signal != 0L) OcrNative.releaseSignal(signal)
                    }
                }
            }
        }

    private fun rgbPixels(bitmap: Bitmap, cancellation: OcrCancellation): ByteArray {
        val rgb = ByteArray(bitmap.width * bitmap.height * 3)
        val row = IntArray(bitmap.width)
        try {
            for (y in 0 until bitmap.height) {
                checkCancellation(cancellation)
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in row.indices) {
                    val argb = row[x]
                    val alpha = argb ushr 24
                    val offset = (y * bitmap.width + x) * 3
                    for (channel in 0..2) {
                        val value = (argb ushr (16 - channel * 8)) and 255
                        rgb[offset + channel] = ((value * alpha + 255 * (255 - alpha) + 127) / 255).toByte()
                    }
                }
            }
            return rgb
        } catch (error: Throwable) {
            rgb.fill(0)
            throw error
        } finally {
            row.fill(0)
        }
    }

    private fun decode(bytes: ByteArray, width: Int, height: Int): OcrResult {
        if (bytes.size !in 20..65536) throw OcrException(OcrError.RECOGNITION_FAILED)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != 0x54534f31 || input.readInt() != width || input.readInt() != height) {
                throw OcrException(OcrError.RECOGNITION_FAILED)
            }
            val flags = input.readInt()
            if (flags !in 0..1) throw OcrException(OcrError.RECOGNITION_FAILED)
            var truncated = flags == 1
            val count = input.readInt()
            if (count !in 0..MAX_WORDS) throw OcrException(OcrError.RECOGNITION_FAILED)
            val words = ArrayList<OcrWord>(count)
            var textUnits = 0
            repeat(count) {
                val left = input.readInt(); val top = input.readInt()
                val right = input.readInt(); val bottom = input.readInt()
                val confidence = input.readFloat()
                val block = input.readInt(); val line = input.readInt()
                val length = input.readInt()
                if (left !in 0 until right || right > width || top !in 0 until bottom || bottom > height ||
                    !confidence.isFinite() || confidence !in 0f..100f || block !in 0..4096 || line !in 0..4096 || length !in 1..2048 || length > input.available()) {
                    throw OcrException(OcrError.RECOGNITION_FAILED)
                }
                val textBytes = ByteArray(length)
                input.readFully(textBytes)
                val text = try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(textBytes))
                        .toString().filterNot { it.isISOControl() }.trim()
                } finally { textBytes.fill(0) }
                if (text.isNotEmpty() && text.length <= 512 && textUnits + text.length <= MAX_TEXT_UNITS) {
                    words += OcrWord(text, left, top, right, bottom, confidence, block, line)
                    textUnits += text.length
                } else if (text.isNotEmpty()) {
                    truncated = true
                }
            }
            if (input.available() != 0) throw OcrException(OcrError.RECOGNITION_FAILED)
            return OcrResult(width, height, words, truncated)
        }
    }

    private fun checkCancellation(cancellation: OcrCancellation) {
        if (cancellation.isCancelled) throw CancellationException("OCR_CANCELLED")
    }

    companion object {
        const val ENGINE_VERSION = "ppocrv6-tiny-ort-1.31.0-opencv-4.14.0-tapscene-1"
        const val MODEL_VERSION = "ppocrv6-tiny-det-2ba1506c-rec-2612ab37"
        const val MAX_EDGE = 2400
        const val MAX_PIXELS = 1080 * 2400
        const val MAX_WORDS = 256
        const val MAX_TEXT_UNITS = 8192
        private val processMutex = Mutex()
    }
}
