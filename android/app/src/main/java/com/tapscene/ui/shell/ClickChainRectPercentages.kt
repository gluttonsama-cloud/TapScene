package com.tapscene.ui.shell

import com.tapscene.media.OpaqueMask
import java.math.BigDecimal

/** Staged image-relative input only. No drag minimum, pixel crop or persistence policy. */
internal data class ClickChainRectPercentages(val left: String, val top: String, val right: String, val bottom: String) {
    fun rectangle(original: OpaqueMask): OpaqueMask? {
        val initial = from(original)
        val raw = listOf(left, top, right, bottom)
        val formatted = listOf(initial.left, initial.top, initial.right, initial.bottom)
        val edges = listOf(original.left, original.top, original.right, original.bottom)
        val parsed = raw.mapIndexed { index, value ->
            val text = value.trim()
            if (!decimal.matches(text)) return null
            val percent = text.toBigDecimalOrNull() ?: return null
            if (percent < BigDecimal.ZERO || percent > hundred) return null
            // An untouched edge keeps its exact Float, even while another edge changes.
            if (text == formatted[index]) edges[index] else percent.movePointLeft(2).toFloat()
        }
        val (left, top, right, bottom) = parsed
        // Decimal input can be non-empty yet collapse after conversion to stored Float geometry.
        if (parsed.any { !it.isFinite() || it !in 0f..1f } || left >= right || top >= bottom) return null
        if (parsed == edges) return original
        return OpaqueMask(left, top, right, bottom)
    }

    companion object {
        private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")
        private val hundred = BigDecimal("100")

        fun from(rect: OpaqueMask) = ClickChainRectPercentages(
            percent(rect.left), percent(rect.top), percent(rect.right), percent(rect.bottom),
        )

        // Float's round-trip decimal, then an exact decimal shift: tiny existing edges do not
        // become equal, scientific notation or locale-specific commas in the initial fields.
        private fun percent(value: Float): String =
            BigDecimal(value.toString()).movePointRight(2).stripTrailingZeros().toPlainString()
    }
}
