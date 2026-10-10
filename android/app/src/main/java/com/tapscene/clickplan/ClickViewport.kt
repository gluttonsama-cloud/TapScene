package com.tapscene.clickplan

import kotlin.math.abs

data class ClickGeometry(val width: Int, val height: Int, val rotation: Int, val displayId: Int = 0)
data class ClickScreenPoint(val x: Int, val y: Int)

/** System-reported exclusions only. This says nothing about the target app's business safety. */
data class ClickViewport(
    val geometry: ClickGeometry,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(geometry.displayId == 0 && geometry.width > 0 && geometry.height > 0 && geometry.rotation in 0..3)
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0)
        require(left.toLong() + right < geometry.width && top.toLong() + bottom < geometry.height)
    }

    fun matches(plan: ClickPlan): Boolean = geometry == ClickGeometry(plan.width, plan.height, plan.rotation, plan.displayId)
    fun contains(x: Int, y: Int): Boolean = x >= left && y >= top &&
        x < geometry.width - right && y < geometry.height - bottom
    fun contains(plan: ClickPlan): Boolean = matches(plan) && plan.actions.all { contains(it.x, it.y) }

    /** A fitted overlay is valid too, provided its actual screen frame covers the usable region. */
    fun acceptsFrame(frame: ClickOverlayFrame): Boolean = frame.width > 0 && frame.height > 0 &&
        frame.x in 0..left && frame.y in 0..top &&
        frame.x.toLong() + frame.width in (geometry.width - right).toLong()..geometry.width.toLong() &&
        frame.y.toLong() + frame.height in (geometry.height - bottom).toLong()..geometry.height.toLong()

    fun screenPoint(frame: ClickOverlayFrame, localX: Float, localY: Float, rawX: Float, rawY: Float): ClickScreenPoint? {
        if (!acceptsFrame(frame) || !localX.isFinite() || !localY.isFinite() || !rawX.isFinite() || !rawY.isFinite()) return null
        if (localX < 0 || localY < 0 || localX >= frame.width || localY >= frame.height) return null
        val x = localX + frame.x
        val y = localY + frame.y
        // Verify the platform's local/screen relationship rather than assuming a zero origin.
        if (abs(x - rawX) > 1.5f || abs(y - rawY) > 1.5f) return null
        return ClickScreenPoint(x.toInt(), y.toInt()).takeIf { contains(it.x, it.y) }
    }

    fun clampControl(x: Int, y: Int, width: Int, height: Int): ClickScreenPoint? {
        if (width <= 0 || height <= 0 || width > geometry.width - left - right || height > geometry.height - top - bottom) return null
        return ClickScreenPoint(x.coerceIn(left, geometry.width - right - width), y.coerceIn(top, geometry.height - bottom - height))
    }
}

data class ClickOverlayFrame(val x: Int, val y: Int, val width: Int, val height: Int)

enum class ClickEditorMode { Closed, Locating, Picking, Browsing }
enum class ClickEditorEvent { AddPoint, Browse, Resume, CancelPick }

/** Browsing only changes editor visibility. It cannot issue a playback or gesture command. */
data class ClickEditorState(val mode: ClickEditorMode = ClickEditorMode.Closed, val generation: Long = 0) {
    fun open(): ClickEditorState = ClickEditorState(ClickEditorMode.Locating, generation + 1)
    fun close(): ClickEditorState = ClickEditorState(ClickEditorMode.Closed, generation + 1)
    fun change(event: ClickEditorEvent, expectedGeneration: Long): ClickEditorState {
        if (generation != expectedGeneration) return this
        val next = when {
            mode == ClickEditorMode.Locating && event == ClickEditorEvent.AddPoint -> ClickEditorMode.Picking
            mode == ClickEditorMode.Locating && event == ClickEditorEvent.Browse -> ClickEditorMode.Browsing
            mode == ClickEditorMode.Browsing && event == ClickEditorEvent.Resume -> ClickEditorMode.Locating
            mode == ClickEditorMode.Picking && event == ClickEditorEvent.CancelPick -> ClickEditorMode.Locating
            else -> return this
        }
        return ClickEditorState(next, generation + 1)
    }
}
