package com.tapscene.ui.shell

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tapscene.data.ProjectHotspot
import com.tapscene.media.OpaqueMask
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Only accepts the reviewed bitmap supplied by the host. The image, overlay and gesture surface
 * share exactly the same fitted bounds, so normalized coordinates never include letterboxing.
 * Gestures are committed once at their end; cancellation leaves the parent draft unchanged.
 */
@Composable
fun EditorCanvas(
    bitmap: Bitmap?,
    hotspots: List<ProjectHotspot>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    selectedHotspotId: String? = null,
    adding: Boolean = false,
    onSelect: ((String?) -> Unit)? = null,
    onCreate: ((OpaqueMask) -> Unit)? = null,
    onChangeRect: ((String, OpaqueMask) -> Unit)? = null,
    onTap: ((Float, Float) -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val line = MaterialTheme.colorScheme.outlineVariant
    val backdrop = MaterialTheme.colorScheme.surfaceContainerLow
    val currentHotspots by rememberUpdatedState(hotspots)
    val currentSelectedId by rememberUpdatedState(selectedHotspotId)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentCreate by rememberUpdatedState(onCreate)
    val currentChange by rememberUpdatedState(onChangeRect)
    val currentTap by rememberUpdatedState(onTap)
    var drawnFrom by remember(bitmap, adding) { mutableStateOf<Offset?>(null) }
    var drawnTo by remember(bitmap, adding) { mutableStateOf<Offset?>(null) }
    var editedId by remember(bitmap) { mutableStateOf<String?>(null) }
    var editedRect by remember(bitmap) { mutableStateOf<OpaqueMask?>(null) }

    BoxWithConstraints(modifier.background(backdrop).border(1.dp, line), contentAlignment = Alignment.Center) {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (busy) "正在读取安全画面" else "安全画面暂不可用", style = MaterialTheme.typography.titleSmall)
                Text(if (busy) "读取完成后可继续操作" else "返回后重新打开这一步。不会展示原片。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@BoxWithConstraints
        }
        val ratio = bitmap.width.toFloat() / bitmap.height
        val fittedWidth = minOf(maxWidth, maxHeight * ratio)
        val fittedHeight = fittedWidth / ratio
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        val labelPaint = remember(ink) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.toArgb(); isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        }
        Box(Modifier.size(fittedWidth, fittedHeight)) {
            Image(image, "已复核的步骤画面", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            val gestures = Modifier
                .pointerInput(bitmap, enabled, adding, onTap != null) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { point ->
                        if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                        val x = (point.x / size.width).coerceIn(0f, 1f)
                        val y = (point.y / size.height).coerceIn(0f, 1f)
                        if (currentTap != null) currentTap?.invoke(x, y)
                        else if (!adding) currentSelect?.invoke(currentHotspots.lastOrNull { it.rect.contains(x, y) }?.id)
                    }
                }
                .pointerInput(bitmap, enabled, adding, onChangeRect != null, onCreate != null) {
                    if (!enabled || (currentChange == null && currentCreate == null)) return@pointerInput
                    var original: OpaqueMask? = null
                    var resize = false
                    var start = Offset.Zero
                    detectDragGestures(
                        onDragStart = { point ->
                            start = point
                            if (adding && currentCreate != null) {
                                drawnFrom = point; drawnTo = point
                            } else if (currentChange != null && size.width > 0 && size.height > 0) {
                                val selected = currentHotspots.firstOrNull { it.id == currentSelectedId }
                                val radius = 24.dp.toPx()
                                resize = selected != null &&
                                    abs(point.x - selected.rect.right * size.width) <= radius &&
                                    abs(point.y - selected.rect.bottom * size.height) <= radius
                                val hit = if (resize) selected else currentHotspots.lastOrNull {
                                    it.rect.contains(point.x / size.width, point.y / size.height)
                                }
                                original = hit?.rect
                                editedId = hit?.id
                                editedRect = hit?.rect
                                if (hit != null) currentSelect?.invoke(hit.id)
                            }
                        },
                        onDrag = { change, _ ->
                            if (adding && drawnFrom != null) {
                                change.consume(); drawnTo = change.position
                            } else {
                                val rect = original
                                if (rect != null && size.width > 0 && size.height > 0) {
                                    change.consume()
                                    val delta = change.position - start
                                    val dx = delta.x / size.width
                                    val dy = delta.y / size.height
                                    editedRect = if (resize) {
                                        val minWidth = min(0.025f, 1f - rect.left)
                                        val minHeight = min(0.025f, 1f - rect.top)
                                        OpaqueMask(rect.left, rect.top,
                                            (rect.right + dx).coerceIn(rect.left + minWidth, 1f),
                                            (rect.bottom + dy).coerceIn(rect.top + minHeight, 1f))
                                    } else {
                                        val boundedX = dx.coerceIn(-rect.left, 1f - rect.right)
                                        val boundedY = dy.coerceIn(-rect.top, 1f - rect.bottom)
                                        translatedRect(rect, boundedX, boundedY)
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            val from = drawnFrom
                            val to = drawnTo
                            if (adding && from != null && to != null && size.width > 0 && size.height > 0 &&
                                abs(from.x - to.x) >= 8.dp.toPx() && abs(from.y - to.y) >= 8.dp.toPx()) {
                                val left = (min(from.x, to.x) / size.width).coerceIn(0f, 1f)
                                val top = (min(from.y, to.y) / size.height).coerceIn(0f, 1f)
                                val right = (max(from.x, to.x) / size.width).coerceIn(0f, 1f)
                                val bottom = (max(from.y, to.y) / size.height).coerceIn(0f, 1f)
                                if (right > left && bottom > top) currentCreate?.invoke(OpaqueMask(left, top, right, bottom))
                            } else if (editedId != null && editedRect != null && editedRect != original) {
                                currentChange?.invoke(editedId!!, editedRect!!)
                            }
                            drawnFrom = null; drawnTo = null; editedId = null; editedRect = null; original = null
                        },
                        onDragCancel = {
                            drawnFrom = null; drawnTo = null; editedId = null; editedRect = null; original = null
                        },
                    )
                }
            Canvas(Modifier.fillMaxSize().then(gestures).semantics {
                contentDescription = when {
                    !enabled -> "安全步骤画面"
                    onTap != null -> "点击热点继续；也可选择下方文字动作"
                    adding -> "新增热点模式：拖出矩形；也可使用按比例添加"
                    else -> "点选热点后拖移，拖动右下角调整尺寸；也可使用对象列表和精调表单"
                }
            }) {
                hotspots.forEachIndexed { index, hotspot ->
                    val rect = if (hotspot.id == editedId) editedRect ?: hotspot.rect else hotspot.rect
                    val selected = hotspot.id == selectedHotspotId || hotspot.id == editedId
                    val position = Offset(rect.left * size.width, rect.top * size.height)
                    val dimensions = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height)
                    drawRect(accent.copy(alpha = if (selected) 0.20f else 0.09f), position, dimensions)
                    drawRect(accent.copy(alpha = if (selected) 1f else 0.8f), position, dimensions,
                        style = Stroke(if (selected) 2.5.dp.toPx() else 1.5.dp.toPx()))
                    val badgeRadius = 10.dp.toPx().coerceAtMost(size.width / 8f)
                    val center = Offset((position.x + badgeRadius).coerceAtMost(size.width - badgeRadius),
                        (position.y + badgeRadius).coerceAtMost(size.height - badgeRadius))
                    drawCircle(accent, badgeRadius, center)
                    labelPaint.textSize = 11.dp.toPx()
                    drawContext.canvas.nativeCanvas.drawText("${index + 1}", center.x,
                        center.y - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint)
                    if (selected && onChangeRect != null) {
                        val handle = Offset(rect.right * size.width, rect.bottom * size.height)
                        drawCircle(Color.White, 7.dp.toPx(), handle)
                        drawCircle(accent, 7.dp.toPx(), handle, style = Stroke(2.dp.toPx()))
                    }
                }
                val from = drawnFrom
                val to = drawnTo
                if (from != null && to != null) {
                    val left = min(from.x, to.x).coerceIn(0f, size.width)
                    val top = min(from.y, to.y).coerceIn(0f, size.height)
                    val right = max(from.x, to.x).coerceIn(0f, size.width)
                    val bottom = max(from.y, to.y).coerceIn(0f, size.height)
                    drawRect(accent.copy(alpha = 0.18f), Offset(left, top), Size(right - left, bottom - top))
                    drawRect(accent, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

private fun OpaqueMask.contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

/** Extremely small valid rectangles can collapse under Float translation; retain them safely. */
private fun translatedRect(rect: OpaqueMask, dx: Float, dy: Float): OpaqueMask {
    val left = (rect.left + dx).coerceIn(0f, 1f)
    val top = (rect.top + dy).coerceIn(0f, 1f)
    val right = (rect.right + dx).coerceIn(0f, 1f)
    val bottom = (rect.bottom + dy).coerceIn(0f, 1f)
    return if (right > left && bottom > top) OpaqueMask(left, top, right, bottom) else rect
}
