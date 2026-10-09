package com.tapscene.ocr

import java.util.concurrent.atomic.AtomicBoolean

/** Unreviewed local evidence; neither a click record nor a privacy guarantee. */
data class OcrWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
    val blockIndex: Int,
    val lineIndex: Int,
) {
    override fun toString(): String = "OcrWord(private text, block=$blockIndex, line=$lineIndex)"
}

data class OcrResult(
    val width: Int,
    val height: Int,
    val words: List<OcrWord>,
    val truncated: Boolean,
    val engineVersion: String = OfflineOcrEngine.ENGINE_VERSION,
    val modelVersion: String = OfflineOcrEngine.MODEL_VERSION,
) {
    override fun toString(): String = "OcrResult(${words.size} private words, truncated=$truncated)"
}

enum class OcrError {
    INPUT_TOO_LARGE, INVALID_INPUT, MODEL_UNAVAILABLE, ENGINE_UNAVAILABLE,
    ENGINE_BUSY, RESOURCE_LIMIT, RECOGNITION_FAILED, TIMEOUT,
}

/** This exception intentionally carries a fixed code and no upstream cause/message. */
class OcrException(val code: OcrError) : Exception(code.name)

/** One-shot cancellation. Native callbacks are only bound during an owned recognition. */
class OcrCancellation {
    private val cancelled = AtomicBoolean(false)
    private var nativeSignal = 0L
    val isCancelled: Boolean get() = cancelled.get()

    @Synchronized fun cancel() {
        cancelled.set(true)
        if (nativeSignal != 0L) OcrNative.cancelSignal(nativeSignal)
    }

    @Synchronized internal fun bind(signal: Long) {
        check(nativeSignal == 0L)
        nativeSignal = signal
        if (cancelled.get()) OcrNative.cancelSignal(signal)
    }

    @Synchronized internal fun unbind() { nativeSignal = 0L }
}

/** One conservative policy used by candidate UI and the synthetic-fixture gate. */
object OcrTitlePolicy {
    fun suggestions(result: OcrResult): List<String> = result.words
        .groupBy { it.blockIndex to it.lineIndex }
        .values.asSequence().filter(::isEligible).map(::text).distinct().take(8).toList()

    fun isEligible(words: List<OcrWord>): Boolean {
        if (words.isEmpty() || words.any { !it.confidence.isFinite() || it.confidence !in 80f..100f }) return false
        val text = text(words)
        return text.length <= 80 && text.codePoints().filter { Character.isLetterOrDigit(it) }.count() >= 3
    }

    fun text(words: List<OcrWord>): String = buildString {
        for (word in words) {
            val part = word.text.trim()
            if (part.isEmpty()) continue
            if (isNotEmpty() && !isCjk(Character.codePointBefore(this, length)) && !isCjk(part.codePointAt(0))) append(' ')
            append(part)
        }
    }

    private fun isCjk(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) in setOf(
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
        )
}
