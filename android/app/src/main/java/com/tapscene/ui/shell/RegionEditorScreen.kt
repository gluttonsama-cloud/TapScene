package com.tapscene.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.ProjectRegion
import com.tapscene.data.RegionBox
import com.tapscene.media.OpaqueMask
import com.tapscene.ui.RegionEdit
import com.tapscene.ui.RegionUiState
import com.tapscene.ui.RegionWorkspace
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.round

@Composable
fun RegionEditorScreen(workspace: RegionWorkspace, onBack: () -> Unit) {
    val state by workspace.state.collectAsStateWithLifecycle()
    val back: () -> Unit = {
        when {
            state.busy -> workspace.cancel()
            state.cropBitmap != null -> workspace.closeCrop()
            else -> { workspace.leave(); onBack() }
        }
    }
    BackHandler(onBack = back)
    RegionEditorContent(state, RegionEditorCallbacks(back, workspace::select, workspace::save,
        workspace::generate, workspace::openCrop, workspace::review, workspace::delete,
        workspace::reload, workspace::cancel, workspace::displayed, workspace::closeCrop))
}

data class RegionEditorCallbacks(
    val onBack: () -> Unit,
    val onSelect: (String?) -> Unit,
    val onSave: (RegionEdit, () -> Unit) -> Unit,
    val onGenerate: () -> Unit,
    val onOpenCrop: () -> Unit,
    val onReview: () -> Unit,
    val onDelete: () -> Unit,
    val onReload: () -> Unit,
    val onCancel: () -> Unit,
    val onDisplayed: (String) -> Unit,
    val onCloseCrop: () -> Unit,
)

@Composable
fun RegionEditorContent(state: RegionUiState, callbacks: RegionEditorCallbacks, modifier: Modifier = Modifier) {
    var adding by rememberSaveable(state.stepId) { mutableStateOf(false) }
    var editing by remember(state.stepId) { mutableStateOf<RegionEdit?>(null) }
    var deleting by rememberSaveable(state.stepId) { mutableStateOf(false) }
    var regenerating by rememberSaveable(state.stepId) { mutableStateOf(false) }
    val step = state.step
    val selected = state.selected
    val canEdit = state.canEdit && state.cropBitmap == null
    val currentBase = selected != null && step != null && selected.matchesBase(step.asset)
    val create: (OpaqueMask) -> Unit = { rect ->
        if (state.canAdd && step != null) {
            editing = RegionEdit(null, "", null, regionPixelBox(rect, step.asset.width, step.asset.height),
                0, 0.5, 0.5, state.revision)
            adding = false
        }
    }
    val edit: (ProjectRegion, RegionBox) -> Unit = { region, box ->
        if (canEdit) editing = RegionEdit(region.id, region.name, region.group, box,
            region.zIndex, region.anchorX, region.anchorY, state.revision)
    }
    val visible = if (step == null) emptyList() else state.regions.filter { it.matchesBase(step.asset) }
    // ProjectHotspot is only a canvas rectangle carrier here; no actions are saved or invented.
    val overlays = visible.map { region -> ProjectHotspot(region.id, region.name,
        regionNormalizedBox(region.bbox, region.sourceWidth, region.sourceHeight), null, null, region.id) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelHeight = (maxHeight * 0.35f).coerceIn(128.dp, 276.dp)
        Column(Modifier.fillMaxSize()) {
            ShellTopBar(if (state.cropBitmap == null) "可见区域" else "复核实际裁片", onBack = callbacks.onBack)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.cropBitmap == null) step?.title ?: "正在读取步骤" else selected?.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (state.cropBitmap == null) "安全截图裁片 · ${state.regions.size}/12"
                        else selected?.asset?.let { "实际 PNG · ${it.width} × ${it.height} 像素" }.orEmpty(),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.cropBitmap == null) TextButton(onClick = callbacks.onReload, enabled = !state.busy) { Text("重读") }
            }
            if (state.cropBitmap != null) {
                ActualRegionCrop(state, callbacks.onDisplayed, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp))
            } else {
                EditorCanvas(state.bitmap, overlays, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    enabled = canEdit, busy = state.busy, selectedHotspotId = state.selectedId,
                    adding = adding && state.canAdd, onSelect = { callbacks.onSelect(it); adding = false }, onCreate = create,
                    onChangeRect = { id, rect -> visible.firstOrNull { it.id == id }?.let { region ->
                        if (step != null) edit(region, regionPixelBox(rect, step.asset.width, step.asset.height))
                    } }, objectLabel = "区域")
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Column(Modifier.fillMaxWidth().heightIn(max = panelHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                if (state.failedToLoad) Text("安全画面尚未就绪，请重新读取；已保存内容仍保留。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (state.cropBitmap != null) {
                    Text(if (selected?.reviewedAt != null) "这张裁片已人工复核。" else "检查完整图片的文字、边缘和遮挡后，再确认无遗漏的敏感内容。",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("双指放大与移动可检查细节。锚点位于裁片内，不会重建遮住的背景。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = callbacks.onCloseCrop, enabled = !state.busy) { Text("返回安全图") }
                        TextButton(onClick = { regenerating = true }, enabled = !state.busy) { Text("重新生成") }
                    }
                } else {
                    Text(when {
                        adding -> "在安全图上拖出矩形；也可按像素输入。"
                        state.regions.isEmpty() -> "只裁取当前安全图中可见的像素，保留截图外观。"
                        else -> "点选区域后拖移或调整右下角，再确认像素范围。"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.regions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.regions.forEach { region ->
                            FilterChip(region.id == state.selectedId, { callbacks.onSelect(region.id); adding = false },
                                enabled = canEdit, label = { Text(region.name, maxLines = 1) })
                        }
                    }
                    if (selected != null) {
                        Text(when {
                            !currentBase -> "底图已改变：先校正范围并保存，再重新生成。"
                            selected.asset == null -> "待生成实际裁片"
                            selected.reviewedAt == null -> "裁片已生成 · 待人工复核"
                            else -> "实际裁片已人工复核"
                        }, style = MaterialTheme.typography.labelMedium,
                            color = if (!currentBase) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("x ${selected.bbox.x} · y ${selected.bbox.y} · ${selected.bbox.width} × ${selected.bbox.height} px" +
                            "\n组 ${selected.group ?: "未分组"} · 次序 ${selected.zIndex} · 锚点 (${selected.anchorX}, ${selected.anchorY})",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { edit(selected, selected.bbox) }, enabled = canEdit) { Text("精调 / 命名") }
                            if (selected.asset != null) TextButton(onClick = { regenerating = true }, enabled = canEdit && currentBase) {
                                Text("重生成")
                            }
                            TextButton(onClick = { deleting = true }, enabled = canEdit) { Text("移除") }
                        }
                    } else TextButton(onClick = { create(OpaqueMask(0.25f, 0.3f, 0.75f, 0.5f)) }, enabled = state.canAdd) {
                        Text("按像素添加（无需拖动）")
                    }
                    Text("移动截图内容需要真实干净底板；此处不生成原生组件或隐藏背景。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.busy) Text(state.stage.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.busy -> OutlinedButton(onClick = callbacks.onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消处理") }
                    state.cropBitmap != null -> Button(onClick = callbacks.onReview, enabled = state.canReview,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (selected?.reviewedAt != null) "已复核这张裁片" else "确认实际裁片已复核")
                    }
                    else -> {
                        OutlinedButton(onClick = { adding = !adding; callbacks.onSelect(null) }, enabled = state.canAdd,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (adding) "取消框选" else "+ 框选区域") }
                        Button(onClick = if (selected?.asset == null) callbacks.onGenerate else callbacks.onOpenCrop,
                            enabled = canEdit && selected != null && currentBase,
                            modifier = Modifier.weight(1.2f).heightIn(min = 48.dp)) {
                            Text(if (selected?.asset == null) "生成裁片" else if (selected.reviewedAt == null) "查看并复核" else "查看裁片")
                        }
                    }
                }
            }
        }
    }
    editing?.let { form ->
        RegionDefinitionSheet(form, step?.asset?.width ?: 0, step?.asset?.height ?: 0, !state.busy,
            message = state.message, onDismiss = { if (!state.busy) editing = null }, onCancel = callbacks.onCancel,
            onConfirm = { value -> callbacks.onSave(value) { editing = null } })
    }
    if (deleting && selected != null) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text("移除区域？") }, text = { Text("移除“${selected.name}”的定义、裁片和复核记录。已封存版本保持原样。") },
        confirmButton = { TextButton(onClick = { deleting = false; callbacks.onDelete() }, enabled = !state.busy) { Text("移除") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
    if (regenerating) AlertDialog(onDismissRequest = { regenerating = false },
        title = { Text("重新生成裁片？") }, text = { Text("重新从当前安全图生成 PNG，已有复核将清除，需查看新图片后再次确认。") },
        confirmButton = { TextButton(onClick = { regenerating = false; callbacks.onGenerate() }, enabled = !state.busy) { Text("重新生成") } },
        dismissButton = { TextButton(onClick = { regenerating = false }) { Text("取消") } })
}

@Composable
private fun ActualRegionCrop(state: RegionUiState, onDisplayed: (String) -> Unit, modifier: Modifier) {
    val bitmap = requireNotNull(state.cropBitmap)
    val sha256 = requireNotNull(state.cropSha256)
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val currentDisplayed by rememberUpdatedState(onDisplayed)
    LaunchedEffect(bitmap, sha256) {
        withFrameNanos { }
        withFrameNanos { }
        currentDisplayed(sha256)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).clipToBounds()
        .pointerInput(bitmap) {
            detectTransformGestures { _, movement, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                val next = pan + movement
                pan = Offset(next.x.coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2),
                    next.y.coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2))
            }
        }, contentAlignment = Alignment.Center) {
        Image(remember(bitmap) { bitmap.asImageBitmap() }, "待检查的实际区域 PNG，未叠加标记或锚点",
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y },
            contentScale = ContentScale.Fit)
        if (scale > 1f) TextButton(onClick = { scale = 1f; pan = Offset.Zero },
            modifier = Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface)) { Text("完整裁片") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionDefinitionSheet(initial: RegionEdit, sourceWidth: Int, sourceHeight: Int, enabled: Boolean,
    message: String?, onDismiss: () -> Unit, onCancel: () -> Unit, onConfirm: (RegionEdit) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var group by rememberSaveable { mutableStateOf(initial.group.orEmpty()) }
    var x by rememberSaveable { mutableStateOf(initial.bbox.x.toString()) }
    var y by rememberSaveable { mutableStateOf(initial.bbox.y.toString()) }
    var width by rememberSaveable { mutableStateOf(initial.bbox.width.toString()) }
    var height by rememberSaveable { mutableStateOf(initial.bbox.height.toString()) }
    var zIndex by rememberSaveable { mutableStateOf(initial.zIndex.toString()) }
    var anchorX by rememberSaveable { mutableStateOf(initial.anchorX.toString()) }
    var anchorY by rememberSaveable { mutableStateOf(initial.anchorY.toString()) }
    val box = parseRegionBox(x, y, width, height, sourceWidth, sourceHeight)
    val z = zIndex.toIntOrNull()?.takeIf { it in -10_000..10_000 }
    val ax = anchorX.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
    val ay = anchorY.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
    val valid = name.isNotBlank() && name.trim().length <= 120 && group.trim().length <= 120 && box != null && z != null && ax != null && ay != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (initial.regionId == null) "新增可见区域" else "编辑可见区域", style = MaterialTheme.typography.titleMedium)
            Text("范围基于当前安全图的 $sourceWidth × $sourceHeight 像素。保存会清除旧裁片和复核。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            OutlinedTextField(name, { name = it }, label = { Text("名称（必填，最多 120 字）") },
                enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(group, { group = it }, label = { Text("分组（选填，最多 120 字）") },
                enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RegionNumberField("左侧 x（px）", x, { x = it }, enabled, Modifier.weight(1f))
                RegionNumberField("顶部 y（px）", y, { y = it }, enabled, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RegionNumberField("宽度（px）", width, { width = it }, enabled, Modifier.weight(1f))
                RegionNumberField("高度（px）", height, { height = it }, enabled, Modifier.weight(1f))
            }
            if (box == null) Text("输入画面内的整数像素范围，宽和高须大于 0。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            RegionNumberField("叠放次序（−10000 至 10000）", zIndex, { zIndex = it }, enabled, Modifier.fillMaxWidth())
            Text("锚点相对裁片：0 是左/上边，1 是右/下边，0.5 是中心。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RegionNumberField("锚点 x（0–1）", anchorX, { anchorX = it }, enabled, Modifier.weight(1f), decimal = true)
                RegionNumberField("锚点 y（0–1）", anchorY, { anchorY = it }, enabled, Modifier.weight(1f), decimal = true)
            }
            if (z == null || ax == null || ay == null) Text("次序须为范围内整数，锚点须为 0–1 的有限数值。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = if (enabled) onDismiss else onCancel) { Text(if (enabled) "取消" else "取消处理") }
                Button(onClick = { onConfirm(initial.copy(name = name.trim(), group = group.trim().ifEmpty { null },
                    bbox = requireNotNull(box), zIndex = requireNotNull(z), anchorX = requireNotNull(ax), anchorY = requireNotNull(ay))) },
                    enabled = enabled && valid) { Text("保存区域定义") }
            }
        }
    }
}

@Composable
private fun RegionNumberField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean,
    modifier: Modifier, decimal: Boolean = false) {
    OutlinedTextField(value, onValue, label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number), modifier = modifier)
}

/** Include touched pixels; normalized gestures never include the canvas letterboxing. */
internal fun regionPixelBox(rect: OpaqueMask, width: Int, height: Int): RegionBox {
    require(width > 0 && height > 0)
    val x = floor(regionPixelEdge(rect.left, width)).toInt().coerceIn(0, width - 1)
    val y = floor(regionPixelEdge(rect.top, height)).toInt().coerceIn(0, height - 1)
    val right = ceil(regionPixelEdge(rect.right, width)).toInt().coerceIn(x + 1, width)
    val bottom = ceil(regionPixelEdge(rect.bottom, height)).toInt().coerceIn(y + 1, height)
    return RegionBox(x, y, right - x, bottom - y)
}

// A persisted integer boundary must survive Float canvas round-trips without growing by one pixel.
private fun regionPixelEdge(normalized: Float, dimension: Int): Double {
    val raw = normalized.toDouble() * dimension
    val integer = round(raw)
    return if (abs(raw - integer) < 0.001) integer else raw
}

internal fun regionNormalizedBox(box: RegionBox, width: Int, height: Int): OpaqueMask =
    OpaqueMask(box.x.toFloat() / width, box.y.toFloat() / height,
        (box.x + box.width).toFloat() / width, (box.y + box.height).toFloat() / height)

internal fun parseRegionBox(x: String, y: String, width: String, height: String, sourceWidth: Int, sourceHeight: Int): RegionBox? {
    val left = x.toIntOrNull() ?: return null
    val top = y.toIntOrNull() ?: return null
    val w = width.toIntOrNull() ?: return null
    val h = height.toIntOrNull() ?: return null
    if (left < 0 || top < 0 || w <= 0 || h <= 0 || left.toLong() + w > sourceWidth || top.toLong() + h > sourceHeight) return null
    return RegionBox(left, top, w, h)
}
