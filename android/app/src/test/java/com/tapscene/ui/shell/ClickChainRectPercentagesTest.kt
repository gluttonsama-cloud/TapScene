package com.tapscene.ui.shell

import com.tapscene.media.OpaqueMask
import java.util.Locale
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickChainRectPercentagesTest {
    private val original = OpaqueMask(.12345679f, .2345679f, .8765432f, .9876543f)

    @Test fun acceptsFullBoundsAndDecimalPercentages() {
        assertEquals(OpaqueMask(0f, 0f, 1f, 1f), ClickChainRectPercentages("0", "0", "100", "100").rectangle(original))
        assertEquals(OpaqueMask(.00125f, .105f, .70375f, .995f),
            ClickChainRectPercentages("0.125", "10.5", "70.375", "99.5").rectangle(original))
    }

    @Test fun preservesOriginalInstanceOnUnchangedInput() {
        assertSame(original, ClickChainRectPercentages.from(original).rectangle(original))
    }

    @Test fun equivalentFormattingIsNoOp() {
        val form = ClickChainRectPercentages.from(original)
        assertSame(original, form.copy(left = " +${form.left}0 ", right = "0${form.right}").rectangle(original))
    }

    @Test fun singleEdgeEditPreservesOtherFloatBits() {
        val initial = ClickChainRectPercentages.from(original)
        val result = checkNotNull(initial.copy(left = "11.125").rectangle(original))
        assertEquals(.11125f.toRawBits(), result.left.toRawBits())
        assertEquals(original.top.toRawBits(), result.top.toRawBits())
        assertEquals(original.right.toRawBits(), result.right.toRawBits())
        assertEquals(original.bottom.toRawBits(), result.bottom.toRawBits())
    }

    @Test fun tinyExistingRectanglesKeepValidInitialDefaults() {
        val tiny = listOf(
            OpaqueMask(0f, 0f, Float.MIN_VALUE, Float.MIN_VALUE),
            OpaqueMask(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE * 2, Float.MIN_VALUE * 2),
            OpaqueMask(.5f, .5f, Math.nextUp(.5f), Math.nextUp(.5f)),
            OpaqueMask(Math.nextDown(1f), Math.nextDown(1f), 1f, 1f),
        )
        tiny.forEach { assertSame(it, ClickChainRectPercentages.from(it).rectangle(it)) }
    }

    @Test fun acceptsTinyRegionsWithoutDragMinimum() {
        val result = ClickChainRectPercentages("50", "50", "50.00001", "50.00001").rectangle(original)
        assertNotNull(result)
        assertTrue(checkNotNull(result).right - result.left < .025f)
        assertTrue(result.right > result.left && result.bottom > result.top)
    }

    @Test fun rejectsBlankIncompleteAndMalformedDecimals() {
        val form = ClickChainRectPercentages.from(original)
        listOf("", " ", ".", "-", "+", "1.", "1.2.3", "12,5", "0x1.0p0", "1e1", "1%", "one").forEach {
            assertNull(it, form.copy(left = it).rectangle(original))
        }
    }

    @Test fun rejectsNonfiniteInputs() {
        val form = ClickChainRectPercentages.from(original)
        listOf("NaN", "Infinity", "-Infinity", "+Infinity", "∞", "1e999").forEach {
            assertNull(it, form.copy(left = it).rectangle(original))
        }
    }

    @Test fun rejectsPercentagesOutsideBoundsBeforeFloatRounding() {
        val form = ClickChainRectPercentages.from(original)
        assertNull(form.copy(left = "-0.0000000000000000001").rectangle(original))
        assertNull(form.copy(right = "100.000000000000000001").rectangle(original))
        assertNull(form.copy(bottom = "101").rectangle(original))
    }

    @Test fun rejectsInvertedAndZeroAreaRectangles() {
        listOf(
            ClickChainRectPercentages("70", "10", "20", "80"),
            ClickChainRectPercentages("20", "80", "70", "10"),
            ClickChainRectPercentages("20", "10", "20", "80"),
            ClickChainRectPercentages("20", "10", "70", "10"),
        ).forEach { assertNull(it.rectangle(original)) }
    }

    @Test fun rejectsGeometryThatCollapsesToFloat() {
        assertNull(ClickChainRectPercentages("50", "10", "50.000000001", "80").rectangle(original))
        assertNull(ClickChainRectPercentages("10", "50", "80", "50.000000001").rectangle(original))
        assertNull(ClickChainRectPercentages("0", "0", "0.000000000000000000000000000000000000000000000001", "1")
            .rectangle(original))
    }

    @Test fun formattingIsPlainDecimalAndLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val form = ClickChainRectPercentages.from(OpaqueMask(Float.MIN_VALUE, .12345679f, .5f, 1f))
            assertFalse(form.left.contains('E', ignoreCase = true))
            assertEquals("12.345679", form.top)
            assertEquals("50", form.right)
            assertEquals("100", form.bottom)
            assertNotNull(form.rectangle(OpaqueMask(Float.MIN_VALUE, .12345679f, .5f, 1f)))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun initialInputRoundTripsAcrossFloatMagnitudes() {
        val random = Random(411)
        repeat(256) {
            val horizontal = List(2) { Float.fromBits(random.nextInt(1f.toRawBits())) }.sorted()
            val vertical = List(2) { Float.fromBits(random.nextInt(1f.toRawBits())) }.sorted()
            if (horizontal[0] < horizontal[1] && vertical[0] < vertical[1]) {
                val rect = OpaqueMask(horizontal[0], vertical[0], horizontal[1], vertical[1])
                assertSame(rect, ClickChainRectPercentages.from(rect).rectangle(rect))
            }
        }
    }
}
