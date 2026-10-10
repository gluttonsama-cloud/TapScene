package com.tapscene.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Original PNG pixels, with exclusive right/bottom edges. No padding or button inference. */
data class TextRegionBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    internal val width: Int get() = right - left
    internal val height: Int get() = bottom - top
    internal val area: Long get() = width.toLong() * height

    internal fun isInside(width: Int, height: Int): Boolean =
        left >= 0 && top >= 0 && right > left && bottom > top && right <= width && bottom <= height
}

/** Unreviewed text and its visible bounds, never evidence that this region is clickable. */
data class TextRegionSuggestion(val text: String, val bounds: TextRegionBounds) {
    override fun toString(): String = "TextRegionSuggestion(private text, bounds=$bounds)"
}

/**
 * Conservative, deterministic text-region proposals from an already recognized image.
 * The caller must bind the result and existing hotspots to the same current safe PNG.
 * Recognition confidence only gates text quality; it is not a click probability.
 * This policy does not recognize icons, merge paragraphs, expand to controls, or create actions.
 */
object TextRegionPolicy {
    const val MAX_SUGGESTIONS = 8
    private const val MAX_WORDS = 256
    private const val MAX_CHARACTERS = 24
    private const val MIN_CONFIDENCE = 80f

    fun suggestions(
        result: OcrResult,
        existingHotspots: List<TextRegionBounds> = emptyList(),
    ): List<TextRegionSuggestion> {
        if (result.width <= 0 || result.height <= 0 || result.words.size > MAX_WORDS) return emptyList()
        val lines = result.words.groupBy { it.blockIndex to it.lineIndex }.values.mapNotNull { words ->
            line(words, result.width, result.height)
        }
        val bodyLines = denseBodyLines(lines, result.width)
        val existing = existingHotspots.filter { it.isInside(result.width, result.height) }
        val accepted = mutableListOf<Line>()
        // Prefer the better reading of overlapping OCR detections, then stable image coordinates.
        lines.filter { it !in bodyLines && eligible(it, result.width, result.height) }
            .sortedWith(compareByDescending<Line> { it.confidence }
                .thenBy { it.bounds.top }.thenBy { it.bounds.left }.thenBy { it.text }
                .thenBy { it.bounds.area }.thenBy { it.bounds.right }.thenBy { it.bounds.bottom })
            .forEach { candidate ->
                if (existing.none { overlaps(candidate.bounds, it) } &&
                    accepted.none { overlaps(candidate.bounds, it.bounds) }) accepted += candidate
            }
        return accepted.sortedWith(compareBy<Line> { it.bounds.top }.thenBy { it.bounds.left }.thenBy { it.text })
            .take(MAX_SUGGESTIONS).map { TextRegionSuggestion(it.text, it.bounds) }
    }

    private data class Line(val text: String, val bounds: TextRegionBounds, val confidence: Float) {
        val characters: Int get() = text.codePointCount(0, text.length)
    }

    private fun line(words: List<OcrWord>, width: Int, height: Int): Line? {
        if (words.isEmpty() || words.any {
                !it.confidence.isFinite() || it.confidence !in 0f..100f ||
                    it.blockIndex !in 0..4096 || it.lineIndex !in 0..4096 ||
                    !bounds(it).isInside(width, height) || it.text.isBlank() || it.text.length > 512 ||
                    it.text.codePoints().anyMatch { cp -> Character.isISOControl(cp) ||
                        Character.getType(cp) in setOf(Character.LINE_SEPARATOR.toInt(),
                            Character.PARAGRAPH_SEPARATOR.toInt(), Character.FORMAT.toInt(),
                            Character.SURROGATE.toInt()) }
            }) return null
        val sorted = words.sortedWith(compareBy<OcrWord> { it.left }.thenBy { it.top })
        val box = TextRegionBounds(sorted.minOf { it.left }, sorted.minOf { it.top },
            sorted.maxOf { it.right }, sorted.maxOf { it.bottom })
        val shortestHeight = sorted.minOf { it.bottom - it.top }
        // A shared line id does not permit joining separate rows or distant columns/buttons.
        if (box.height > shortestHeight * 1.5) return null
        if (sorted.zipWithNext().any { (a, b) ->
                val smallerHeight = min(a.bottom - a.top, b.bottom - b.top)
                val verticalOverlap = min(a.bottom, b.bottom) - max(a.top, b.top)
                val gap = b.left.toLong() - a.right
                verticalOverlap < smallerHeight * 0.65 || gap > smallerHeight * 1.5 ||
                    -gap > min(a.right - a.left, b.right - b.left) * 0.25
            }) return null
        return Line(OcrTitlePolicy.text(sorted), box, sorted.minOf { it.confidence })
    }

    private fun eligible(line: Line, imageWidth: Int, imageHeight: Int): Boolean {
        val box = line.bounds
        if (line.confidence < MIN_CONFIDENCE || line.characters !in 2..MAX_CHARACTERS ||
            box.width < 4 || box.height < 6 || box.width < box.height * 1.1 ||
            box.width > imageWidth * 0.65 || box.height > imageHeight * 0.10 ||
            box.area > imageWidth.toLong() * imageHeight * 0.04) return false
        val lettersOrDigits = line.text.codePoints().filter { Character.isLetterOrDigit(it) }.count()
        // Two-character labels such as 确定, 返回 and OK are deliberately eligible.
        return lettersOrDigits >= 2 && lettersOrDigits * 2 >= line.characters &&
            box.width <= lettersOrDigits * box.height * 1.8
    }

    /** Dense, similarly indented rows are context for rejection, never merged output. */
    private fun denseBodyLines(lines: List<Line>, imageWidth: Int): Set<Line> {
        val excluded = mutableSetOf<Line>()
        val visited = mutableSetOf<Line>()
        for (start in lines) {
            if (!visited.add(start)) continue
            val connected = mutableListOf(start)
            var index = 0
            while (index < connected.size) {
                val current = connected[index++]
                lines.filter { it !in visited && adjacentBodyRows(current.bounds, it.bounds) }.forEach {
                    visited += it
                    connected += it
                }
            }
            // Also reject a short paragraph tail next to a long body line.
            if (connected.size >= 3 || (connected.size >= 2 && connected.any {
                    it.characters > MAX_CHARACTERS || it.bounds.width > imageWidth * 0.65
                })) excluded += connected
        }
        return excluded
    }

    private fun adjacentBodyRows(a: TextRegionBounds, b: TextRegionBounds): Boolean {
        val shorter = min(a.height, b.height)
        val taller = max(a.height, b.height)
        if (taller > shorter * 1.6 || abs(a.left.toLong() - b.left) > taller * 0.55) return false
        val upper = if (a.top <= b.top) a else b
        val lower = if (a.top <= b.top) b else a
        val gap = lower.top.toLong() - upper.bottom
        // Overlapping duplicate detections cannot form a paragraph by themselves.
        return gap >= -shorter * 0.15 && gap <= taller * 0.85
    }

    private fun overlaps(a: TextRegionBounds, b: TextRegionBounds): Boolean {
        val width = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0)
        val height = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0)
        val intersection = width.toLong() * height
        return intersection >= min(a.area, b.area) * 0.80 ||
            intersection.toDouble() / (a.area + b.area - intersection) >= 0.50
    }

    private fun bounds(word: OcrWord) = TextRegionBounds(word.left, word.top, word.right, word.bottom)
}
