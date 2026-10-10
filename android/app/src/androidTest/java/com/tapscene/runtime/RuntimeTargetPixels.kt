package com.tapscene.runtime

import com.tapscene.recording.FrameGeometry
import org.json.JSONObject
import kotlin.math.roundToInt

/** Recognize the fixture by its invariant checkerboard, independently of the locator-check ROI. */
internal class RuntimeTargetPixels(
    private val fixture: JSONObject,
    private val geometry: FrameGeometry,
    private val luminance: (Int, Int) -> Int,
) {
    fun recognizedState(): Int? {
        val static = fixture.getJSONObject("staticRegion")
        val cell = minOf(width(static) / 12, height(static) / 4).coerceAtLeast(1)
        for (row in 0..3) for (column in 0..3) {
            val value = sample(static.getInt("left") + (column + 0.5) * cell,
                static.getInt("top") + (row + 0.5) * cell)
            if ((row + column) % 2 == 0) { if (value > 85) return null }
            else if (value < 160) return null
        }
        val region = fixture.getJSONObject("stateRegion")
        val bitCell = minOf(width(region) / 17, height(region) / 4).coerceAtLeast(1)
        var state = 0
        for (bit in 0..7) {
            val value = sample(region.getInt("left") + (2 * bit + 1.5) * bitCell,
                region.getInt("centerY").toDouble())
            when {
                value >= 175 -> state = state or (1 shl bit)
                value <= 85 -> Unit
                else -> return null
            }
        }
        return state
    }

    private fun width(rect: JSONObject) = rect.getInt("right") - rect.getInt("left")
    private fun height(rect: JSONObject) = rect.getInt("bottom") - rect.getInt("top")
    private fun sample(x: Double, y: Double): Int = luminance(
        (x * geometry.scale + geometry.offsetX).roundToInt(),
        (y * geometry.scale + geometry.offsetY).roundToInt())
}
