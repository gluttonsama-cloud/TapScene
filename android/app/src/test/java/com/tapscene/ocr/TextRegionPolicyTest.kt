package com.tapscene.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic OCR records exercise the production policy, not model accuracy or device behavior. */
class TextRegionPolicyTest {
    @Test fun emptyInvalidAndOversizedInputsReturnNoSuggestions() {
        assertTrue(suggest().isEmpty())
        assertTrue(TextRegionPolicy.suggestions(result(word("确定")).copy(width = 0)).isEmpty())
        assertTrue(TextRegionPolicy.suggestions(result(word("确定")).copy(height = -1)).isEmpty())
        assertTrue(TextRegionPolicy.suggestions(result(*Array(257) { word("确定", line = it) })).isEmpty())
    }

    @Test fun twoCharacterLabelsKeepExactSourceBoundsWithoutPadding() {
        val confirm = word("确定", left = 120, top = 180, right = 168, bottom = 204)
        val back = word("返回", left = 20, top = 30, right = 68, bottom = 54, line = 1)
        val ok = word("OK", left = 450, top = 800, right = 480, bottom = 824, line = 2)
        val suggestions = suggest(confirm, back, ok)
        assertEquals(listOf("返回", "确定", "OK"), suggestions.map { it.text })
        assertEquals(TextRegionBounds(120, 180, 168, 204), suggestions[1].bounds)
        assertFalse(suggestions[1].toString().contains("确定"))
        assertEquals("𠀀𠀁", suggest(word("𠀀𠀁")).single().text)
    }

    @Test fun recognitionThresholdDoesNotBecomeClickProbability() {
        assertEquals(listOf("返回"), suggest(word("返回", confidence = 80f)).map { it.text })
        for (confidence in listOf(79.9f, -1f, 101f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(suggest(word("返回", confidence = confidence)).isEmpty())
        }
        // Short text remains merely a text proposal, including non-action headings.
        assertEquals("标题", suggest(word("标题")).single().text)
    }

    @Test fun blankSymbolsControlsAndOneCharacterNoiseAreRejected() {
        for (text in listOf("", "   ", "+", "++", "☰", "→", "A", "确", "O++++", "返\n回", "返\u2028回", "返\u202E回", "返\uD800回")) {
            assertTrue("Unexpected proposal for synthetic noise", suggest(word(text)).isEmpty())
        }
    }

    @Test fun invalidBoxesSmallFragmentsAndPageSizedRegionsAreRejected() {
        val original = word("确定")
        val invalid = listOf(
            original.copy(left = -1), original.copy(top = -1), original.copy(right = 1001),
            original.copy(bottom = 1001), original.copy(right = original.left),
            original.copy(bottom = original.top), original.copy(left = 90, right = 80),
            original.copy(left = 0, top = 0, right = 1000, bottom = 1000),
            original.copy(left = 0, top = 0, right = 3, bottom = 6),
            original.copy(left = 0, top = 0, right = 12, bottom = 5),
            original.copy(left = 0, top = 0, right = 24, bottom = 60),
            original.copy(left = 0, top = 0, right = 400, bottom = 24),
        )
        invalid.forEach { assertTrue(suggest(it).isEmpty()) }
        assertTrue(suggest(word("abcdefghijklmnopqrstuvwx", left = 0, top = 0, right = 600, bottom = 80)).isEmpty())
        assertTrue(suggest(original.copy(blockIndex = -1)).isEmpty())
        assertTrue(suggest(original.copy(lineIndex = 4097)).isEmpty())
    }

    @Test fun onlyCloseAlignedWordsInTheSameReportedLineAreJoined() {
        val first = word("Start", left = 100, right = 160)
        val second = word("over", left = 172, right = 220)
        assertEquals(TextRegionSuggestion("Start over", TextRegionBounds(100, 100, 220, 124)),
            suggest(second, first).single())
        assertTrue(suggest(first, second.copy(left = 600, right = 648)).isEmpty())
        assertTrue(suggest(first, second.copy(top = 128, bottom = 152)).isEmpty())
        assertTrue(suggest(first, second.copy(confidence = 40f)).isEmpty())
        assertTrue(suggest(first, second.copy(right = 1001)).isEmpty())
    }

    @Test fun longParagraphAndShortDenseRowsAreNotOffered() {
        assertTrue(suggest(word("This is a long paragraph that should never become a region", right = 720)).isEmpty())
        val rows = listOf(
            word("这是第一行正文", right = 290),
            word("这是第二行正文", top = 134, bottom = 158, right = 290, line = 1),
            word("最后一行", top = 168, bottom = 192, right = 200, line = 2),
        )
        assertTrue(suggest(*rows.toTypedArray()).isEmpty())
        // Separate, unchanged text proposal survives near unrelated body text.
        val action = word("确定", left = 450, right = 498, top = 800, bottom = 824, line = 3)
        assertEquals(listOf("确定"), suggest(*(rows + action).toTypedArray()).map { it.text })
    }

    @Test fun shortTailOfLongParagraphIsRejectedButSparseLabelsRemain() {
        val long = word("This long paragraph exceeds the short text region limit", right = 650)
        val tail = word("short tail", top = 134, bottom = 158, right = 220, line = 1)
        assertTrue(suggest(long, tail).isEmpty())
        assertEquals(2, suggest(word("设置"), word("帮助", top = 180, bottom = 204, line = 1)).size)
    }

    @Test fun overlappingDetectionsKeepBestReadingButDistinctRepeatedLabelsRemain() {
        val original = word("确定", confidence = 90f)
        val duplicate = original.copy(left = 101, right = 149, confidence = 99f, lineIndex = 1)
        val elsewhere = original.copy(left = 450, right = 498, lineIndex = 2)
        val suggestions = suggest(original, duplicate, elsewhere)
        assertEquals(2, suggestions.size)
        assertEquals(TextRegionBounds(101, 100, 149, 124), suggestions[0].bounds)
        assertEquals(TextRegionBounds(450, 100, 498, 124), suggestions[1].bounds)
        assertEquals(suggestions, suggest(elsewhere, duplicate, original))
    }

    @Test fun existingHotspotsSuppressContainmentAndHighOverlapOnly() {
        val record = word("确定")
        assertTrue(TextRegionPolicy.suggestions(result(record),
            listOf(TextRegionBounds(80, 80, 180, 150))).isEmpty())
        assertTrue(TextRegionPolicy.suggestions(result(record),
            listOf(TextRegionBounds(101, 100, 149, 124))).isEmpty())
        assertEquals(1, TextRegionPolicy.suggestions(result(record),
            listOf(TextRegionBounds(140, 120, 180, 150))).size)
        assertEquals(1, TextRegionPolicy.suggestions(result(record),
            listOf(TextRegionBounds(-1, 0, 1000, 1000), TextRegionBounds(0, 0, 0, 0))).size)
    }

    @Test fun resultsAreBoundedAndStableInReadingOrderWithoutChangingInput() {
        val words = (0..11).map { index ->
            word("选项", left = 100 + index % 3 * 250, right = 148 + index % 3 * 250,
                top = 100 + index / 3 * 180, bottom = 124 + index / 3 * 180, line = index)
        }.reversed()
        val result = result(*words.toTypedArray()).copy(truncated = true)
        val suggestions = TextRegionPolicy.suggestions(result)
        assertEquals(8, suggestions.size)
        assertEquals(TextRegionBounds(100, 100, 148, 124), suggestions.first().bounds)
        assertEquals(TextRegionBounds(350, 460, 398, 484), suggestions.last().bounds)
        assertEquals(words, result.words)
        assertEquals(suggestions, TextRegionPolicy.suggestions(result.copy(words = words.reversed())))
    }

    private fun word(
        text: String,
        left: Int = 100,
        top: Int = 100,
        right: Int = 148,
        bottom: Int = 124,
        confidence: Float = 95f,
        line: Int = 0,
    ) = OcrWord(text, left, top, right, bottom, confidence, blockIndex = 0, lineIndex = line)

    private fun result(vararg words: OcrWord) = OcrResult(1000, 1000, words.toList(), truncated = false)

    private fun suggest(vararg words: OcrWord) = TextRegionPolicy.suggestions(result(*words))
}
