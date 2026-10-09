package com.tapscene.media

import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A fixed black, fully opaque rectangle in the displayed image's coordinate space.
 *
 * The origin is the top-left of the actual image, after applying video orientation, excluding
 * player letterboxing. Coordinates are normalized, not pixels. Invalid or empty rectangles are
 * rejected rather than silently clamped. A moving secret needs a rectangle covering its ENTIRE
 * path for the selected video interval.
 */
data class OpaqueMask(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f }) {
            "遮挡坐标必须是 0 到 1 之间的有限数。"
        }
        require(left < right && top < bottom) { "遮挡矩形必须有实际面积。" }
    }

    /** Left/top round down, right/bottom round up: every intersected pixel is covered. */
    fun toPixelRect(width: Int, height: Int): Rect {
        require(width > 0 && height > 0) { "画面尺寸无效。" }
        return Rect(
            floor(left.toDouble() * width).toInt().coerceIn(0, width),
            floor(top.toDouble() * height).toInt().coerceIn(0, height),
            ceil(right.toDouble() * width).toInt().coerceIn(0, width),
            ceil(bottom.toDouble() * height).toInt().coerceIn(0, height),
        )
    }
}
