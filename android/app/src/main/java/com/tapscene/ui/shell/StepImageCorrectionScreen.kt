package com.tapscene.ui.shell

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapscene.media.OpaqueMask
import com.tapscene.ui.FrameEditor
import com.tapscene.ui.MaskDialog
import com.tapscene.ui.WorkspaceUiState
import com.tapscene.ui.ScreenshotEditorDraft
import java.util.Locale

enum class StepImageCorrectionMode(val label: String) {
    FRAME("画面"), MASK("遮挡"), REVIEW("复核"),
}

data class CorrectionUiCallbacks(
    val onBack: () -> Unit,
    val onCancelTask: () -> Unit,
    val onTakeFrame: (Long) -> Unit,
    val onAddMask: (OpaqueMask) -> Unit,
    val onUndoMask: () -> Unit,
    val onMakeImage: () -> Unit,
    val onConfirm: (String) -> Unit,
    val onRetry: () -> Unit,
    val onSelectSource: (String) -> Unit,
    val onUseSafeImage: () -> Unit = {},
)

/** Existing-step correction only. The caller owns its isolated draft and atomic replacement. */
@Composable
fun StepImageCorrectionContent(
    state: WorkspaceUiState,
    fallbackBitmap: Bitmap?,
    initialMode: StepImageCorrectionMode,
    callbacks: CorrectionUiCallbacks,
    modifier: Modifier = Modifier,
    screenshot: ScreenshotEditorDraft? = null,
) {
    val correction = state.correction
    val source = state.selected
    val frame = state.frame?.takeUnless { it.bitmap.isRecycled }
    val safeImage = state.safeImageDraft
    val editableBitmap = screenshot?.bitmap?.takeUnless { it.isRecycled } ?: if (safeImage != null) safeImage.bitmap?.takeUnless { it.isRecycled } else frame?.bitmap
    val candidate = state.candidate?.takeIf { it.mimeType == "image/png" }
    val output = state.candidateImage?.takeUnless { it.isRecycled }
    val hasOutput = candidate != null && output != null
    val sessionId = screenshot?.sessionId ?: correction?.sessionId
    var mode by rememberSaveable(sessionId) { mutableStateOf(initialMode) }
    var sourceMenu by remember(sessionId) { mutableStateOf(false) }
    var maskDialog by remember(sessionId) { mutableStateOf(false) }
    var timeDialog by remember(sessionId) { mutableStateOf(false) }
    var displayedDigest by remember(sessionId, candidate?.sha256, output) { mutableStateOf<String?>(null) }
    val idle = !state.busy
    // Shared source-list metadata can fail while this step's own stored source is still usable.
    val canEdit = idle && (correction != null || screenshot != null) && !state.unsavedEdits
    val canSelectSource = canEdit && !state.loadFailed && state.drafts.isNotEmpty()
    val canEditFrame = canEdit && source != null && frame != null && safeImage == null
    val canEditImage = canEdit && editableBitmap != null && screenshot?.reviewLocked != true
    val canGenerate = canEditImage && (safeImage == null || safeImage.masks.isNotEmpty())
    val canConfirm = (canGenerate || screenshot?.reviewLocked == true && idle) && mode == StepImageCorrectionMode.REVIEW && hasOutput &&
        displayedDigest == candidate?.sha256
    val masks = screenshot?.masks ?: safeImage?.masks ?: source?.masks.orEmpty()
    val durationUs = source?.source?.metadata?.durationUs ?: 0L
    val lastTimeUs = (durationUs - 1L).coerceAtLeast(0L)
    var requestedTime by remember(sessionId, source?.source?.sourceId, frame?.presentationTimeUs) {
        mutableFloatStateOf((frame?.presentationTimeUs ?: source?.frameTimeUs ?: 0L).coerceIn(0L, lastTimeUs).toFloat())
    }
    val panelScroll = rememberScrollState()
    val back: () -> Unit = { if (idle) callbacks.onBack() }
    BackHandler(enabled = !maskDialog && !timeDialog, onBack = back)

    // An output may be reviewed only as its actual decoded PNG, never its source preview.
    LaunchedEffect(safeImage?.binding, safeImage?.bitmap) {
        if (safeImage?.bitmap != null && !hasOutput) mode = StepImageCorrectionMode.MASK
    }
    LaunchedEffect(candidate?.sha256, output) {
        if (hasOutput) mode = StepImageCorrectionMode.REVIEW
    }
    LaunchedEffect(sessionId, mode, candidate?.sha256, output) {
        displayedDigest = null
        panelScroll.scrollTo(0)
        if (mode == StepImageCorrectionMode.REVIEW && hasOutput) {
            withFrameNanos { }
            withFrameNanos { }
            displayedDigest = candidate?.sha256
        }
    }
    LaunchedEffect(candidate, state.busy) {
        if (candidate == null && !state.busy && mode == StepImageCorrectionMode.REVIEW) {
            mode = if (screenshot != null || initialMode == StepImageCorrectionMode.MASK) StepImageCorrectionMode.MASK else StepImageCorrectionMode.FRAME
        }
    }
    LaunchedEffect(state.busy) {
        if (state.busy) { sourceMenu = false; maskDialog = false; timeDialog = false }
    }

    val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
        Box(canvasModifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            when {
                mode == StepImageCorrectionMode.REVIEW && hasOutput -> CorrectionImage(
                    requireNotNull(output), "待复核的实际生成 PNG，未叠加编辑标记", idle, Modifier.fillMaxSize())
                mode == StepImageCorrectionMode.REVIEW -> CorrectionEmptyCanvas("实际输出尚未就绪，请重新生成。")
                editableBitmap != null -> FrameEditor(editableBitmap, masks,
                    enabled = canEditImage && mode == StepImageCorrectionMode.MASK && masks.size < 20,
                    onMask = callbacks.onAddMask, modifier = Modifier.fillMaxSize(), fitHeight = true)
                fallbackBitmap != null && !fallbackBitmap.isRecycled -> CorrectionImage(
                    fallbackBitmap, "当前步骤已保存的安全图片；选择追加遮挡后才会核对并作为底图", idle, Modifier.fillMaxSize())
                else -> CorrectionEmptyCanvas(if (state.busy) "正在读取本机画面" else "暂时无法显示画面")
            }
        }
    }
    val modeTabs: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth()) {
            StepImageCorrectionMode.entries.filter { screenshot == null || it != StepImageCorrectionMode.FRAME }.forEach { item ->
                Column(Modifier.weight(1f)) {
                    Tab(selected = mode == item, onClick = { mode = item },
                        enabled = idle && when (item) {
                            StepImageCorrectionMode.FRAME -> true
                            StepImageCorrectionMode.MASK -> canEditImage
                            StepImageCorrectionMode.REVIEW -> hasOutput
                        }, text = { Text(if (item == StepImageCorrectionMode.FRAME && safeImage != null) "底图" else item.label) }, modifier = Modifier.heightIn(min = 48.dp),
                        selectedContentColor = ShellColors.Accent, unselectedContentColor = ShellColors.Muted)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (mode == item) ShellColors.Accent else ShellColors.Divider))
                }
            }
        }
    }
    val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
        Column(controlsModifier.fillMaxWidth().verticalScroll(panelScroll)
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            // A missing old source must not strand a step when another local source exists.
            if (mode != StepImageCorrectionMode.REVIEW && state.drafts.isNotEmpty()) Box {
                TextButton(onClick = { sourceMenu = true }, enabled = canSelectSource,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("${source?.source?.displayName ?: "选择本机素材"} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(sourceMenu && idle, { sourceMenu = false }) {
                    state.drafts.forEach { draft ->
                        DropdownMenuItem(text = { Text(draft.source.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            enabled = canSelectSource, onClick = {
                                sourceMenu = false
                                mode = StepImageCorrectionMode.FRAME
                                callbacks.onSelectSource(draft.source.sourceId)
                            })
                    }
                }
            }
            if (mode != StepImageCorrectionMode.REVIEW && safeImage == null && source != null) {
                TextButton(onClick = callbacks.onUseSafeImage, enabled = canEdit && correction?.safeImageBase != null,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("在安全画面上追加遮挡") }
            }
            if (safeImage != null) Text("以当前已保存安全图为底图。旧遮挡已烧入，不能移除；本轮新增矩形可撤销。",
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            when {
                mode == StepImageCorrectionMode.REVIEW && hasOutput -> {
                    Text("${candidate!!.width} × ${candidate.height} 像素 · 实际生成图片",
                        style = MaterialTheme.typography.labelMedium)
                    if (screenshot == null) Text("热点与连线保留，请检查热点是否仍对齐。",
                        style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    if (screenshot == null) Text(correctionImpactNote(correction?.regionCount ?: 0, correction?.transitionCount ?: 0),
                        style = MaterialTheme.typography.bodySmall, color = ShellColors.Accent)
                    Text(if (screenshot != null) "检查完整画面的文字、边缘与遮挡；可双指放大。确认后${if (screenshot.creatingProject) "新建项目并" else ""}加入步骤。"
                        else "检查完整画面的文字、边缘与遮挡；可双指放大。确认后替换当前步骤的安全图。",
                        style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    TextButton(onClick = { mode = StepImageCorrectionMode.MASK }, enabled = canEditImage,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("继续调整遮挡") }
                }
                editableBitmap == null -> {
                    Text(when {
                        screenshot != null && state.busy -> "正在读取并校验所选截图。"
                        screenshot != null -> "截图未就绪，请重新选择 PNG 或 JPEG。"
                        safeImage != null && state.busy -> "正在核对当前正式图片的来源、摘要和尺寸。"
                        safeImage != null -> "安全底图未就绪，无法生成或保存。请重试读取，或返回步骤。"
                        source == null -> "这是安全图片步骤。可在当前安全画面上追加遮挡，也可选择本机录屏重新取帧。"
                        state.busy -> "本机原片正在读取，当前只展示已保存的安全图。"
                        else -> "原片未就绪，已保存安全图仍保留。可追加遮挡、重选本机录屏，或重试读取。"
                    }, style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    if (safeImage == null && screenshot == null) Text("追加遮挡不会恢复原像素，旧遮挡不能移除。",
                        style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                }
                else -> {
                    if (screenshot != null) {
                        Text("可按需遮挡；无敏感内容也需生成并复核。透明区域已铺黑。",
                            style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    } else if (safeImage != null) {
                        Text("${editableBitmap.width} × ${editableBitmap.height} 像素 · 已核对安全底图",
                            style = MaterialTheme.typography.labelMedium)
                    } else if (frame != null) {
                        Text("实际帧 ${correctionSeconds(frame.presentationTimeUs)} 秒 · 精度 ${correctionPrecision(frame.timePrecisionUs)} 毫秒",
                            style = MaterialTheme.typography.labelMedium)
                    }
                    if (mode == StepImageCorrectionMode.FRAME && safeImage == null) {
                        Slider(value = requestedTime, onValueChange = { requestedTime = it },
                            onValueChangeFinished = { if (canEditFrame) callbacks.onTakeFrame(requestedTime.toLong().coerceIn(0L, lastTimeUs)) },
                            valueRange = 0f..lastTimeUs.coerceAtLeast(1L).toFloat(), enabled = canEditFrame && durationUs > 1L,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "定位本机录屏画面，解码后显示实际帧时间" })
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("定位 ${correctionSeconds(requestedTime.toLong())} / ${correctionSeconds(durationUs)} 秒",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                            TextButton(onClick = { timeDialog = true }, enabled = canEditFrame,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("输入时间") }
                        }
                        Text("拖动后读取真实帧；原有遮挡保留，请检查新画面是否仍被完整覆盖。",
                            style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    } else if (mode == StepImageCorrectionMode.MASK) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${if (safeImage != null) "本轮新增" else "遮挡"} ${masks.size} / 20", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { maskDialog = true }, enabled = canEditImage && masks.size < 20,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("精确添加") }
                            TextButton(onClick = callbacks.onUndoMask, enabled = canEditImage && masks.isNotEmpty(),
                                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (safeImage != null) "撤销新增" else "移除末块") }
                        }
                        Text("在画面上拖出不透明矩形。生成后需查看实际图片，确认没有遗漏的敏感内容。",
                            style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    } else {
                        TextButton(onClick = { mode = StepImageCorrectionMode.MASK }, enabled = canEditImage,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("追加矩形遮挡") }
                    }
                }
            }
            if (state.busy) Text(state.stage ?: "正在处理", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        }
    }
    val footer: @Composable () -> Unit = {
        HorizontalDivider(color = ShellColors.Divider)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.busy -> OutlinedButton(onClick = callbacks.onCancelTask,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消处理") }
                editableBitmap == null && source == null && safeImage == null && screenshot == null -> Button(onClick = callbacks.onUseSafeImage,
                    enabled = canEdit && correction?.safeImageBase != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("在安全画面上追加遮挡") }
                editableBitmap == null || state.unsavedEdits -> OutlinedButton(onClick = callbacks.onRetry,
                    enabled = correction != null || screenshot != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (screenshot != null) "重新选择截图" else "重试读取") }
                mode == StepImageCorrectionMode.REVIEW -> Button(onClick = {
                    if (canConfirm) candidate?.sha256?.let(callbacks.onConfirm)
                }, enabled = canConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (screenshot?.reviewLocked == true) "原样重试确认" else if (screenshot != null)
                    if (screenshot.creatingProject) "确认并创建项目" else "确认并加入" else "确认并替换") }
                else -> Button(onClick = callbacks.onMakeImage, enabled = canGenerate,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("生成并复核") }
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Wide screens keep the image tall; only the separate controls pane scrolls.
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        val controlsWidth = (maxWidth * 0.4f).coerceAtLeast(280.dp)
        val panelHeight = (maxHeight * 0.28f).coerceIn(92.dp, 244.dp)
        Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(if (screenshot != null) if (mode == StepImageCorrectionMode.REVIEW) "复核截图" else "导入截图"
                        else if (mode == StepImageCorrectionMode.REVIEW) "复核替换画面" else "校正步骤画面",
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (screenshot == null) Text(correction?.title?.ifBlank { "未命名步骤" } ?: "正在读取步骤",
                        style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (mode == StepImageCorrectionMode.REVIEW) "实际 PNG" else if (safeImage != null) "安全底图" else if (frame != null || screenshot != null) "本机私有" else "已保存安全图",
                    Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted)
            }
            HorizontalDivider(color = ShellColors.Divider)
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    canvas(Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider(Modifier.fillMaxHeight(), color = ShellColors.Divider)
                    Column(Modifier.width(controlsWidth).fillMaxHeight()) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        modeTabs()
                        controls(Modifier.weight(1f))
                        footer()
                    }
                }
            } else {
                canvas(Modifier.weight(1f).fillMaxWidth())
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                modeTabs()
                controls(Modifier.heightIn(max = panelHeight))
                footer()
            }
        }
    }
    if (maskDialog && canEditImage) MaskDialog(onDismiss = { maskDialog = false }) { mask ->
        maskDialog = false
        if (canEditImage && masks.size < 20) callbacks.onAddMask(mask)
    }
    if (timeDialog && canEditFrame) CorrectionTimeDialog(requestedTime.toLong(), lastTimeUs,
        onDismiss = { timeDialog = false }, onConfirm = { time ->
            timeDialog = false
            if (canEditFrame) callbacks.onTakeFrame(time)
        })
}

@Composable
private fun CorrectionEmptyCanvas(message: String) {
    Box(Modifier.fillMaxSize().background(ShellColors.Quiet, RoundedCornerShape(8.dp)).padding(16.dp),
        contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
    }
}

/** Both review and safe fallback display a complete bitmap, without editable overlays. */
@Composable
private fun CorrectionImage(bitmap: Bitmap, description: String, enabled: Boolean, modifier: Modifier) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Box(modifier.background(ShellColors.Quiet, RoundedCornerShape(8.dp)).clipToBounds()
        .pointerInput(bitmap, enabled) {
            if (enabled) detectTransformGestures { _, movement, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                val next = pan + movement
                pan = Offset(next.x.coerceIn(-size.width * (scale - 1f) / 2f, size.width * (scale - 1f) / 2f),
                    next.y.coerceIn(-size.height * (scale - 1f) / 2f, size.height * (scale - 1f) / 2f))
            }
        }, contentAlignment = Alignment.Center) {
        Image(remember(bitmap) { bitmap.asImageBitmap() }, description,
            Modifier.fillMaxSize().padding(12.dp).graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y
            }, contentScale = ContentScale.Fit)
        if (scale > 1f) TextButton(onClick = { scale = 1f; pan = Offset.Zero }, enabled = enabled,
            modifier = Modifier.align(Alignment.BottomEnd).background(ShellColors.Surface).heightIn(min = 48.dp)) { Text("完整画面") }
    }
}

@Composable
private fun CorrectionTimeDialog(initialUs: Long, lastTimeUs: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    // The three-decimal field must not round the last valid instant past the source duration.
    val lastMillisecondUs = lastTimeUs / 1_000L * 1_000L
    var input by remember { mutableStateOf(correctionSeconds(initialUs.coerceIn(0L, lastMillisecondUs) / 1_000L * 1_000L)) }
    val value = input.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it <= lastTimeUs / 1_000_000.0 }
        ?.let { (it * 1_000_000.0).toLong().coerceIn(0L, lastTimeUs) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("定位画面") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("时间（秒）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = value == null)
            Text("范围 0–${correctionSeconds(lastMillisecondUs)} 秒。读取后会显示实际帧时间。",
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        }
    }, confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null) { Text("读取画面") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun correctionSeconds(timeUs: Long): String = String.format(Locale.ROOT, "%.3f", timeUs / 1_000_000.0)
private fun correctionPrecision(timeUs: Long): String = if (timeUs % 1_000L == 0L) (timeUs / 1_000L).toString()
    else String.format(Locale.ROOT, "%.3f", timeUs / 1_000.0)

private fun correctionImpactNote(regionCount: Int, transitionCount: Int): String {
    val affected = buildList {
        if (regionCount > 0) add("$regionCount 个区域裁片")
        if (transitionCount > 0) add("$transitionCount 段相关过渡")
    }
    return (if (affected.isEmpty()) "" else "替换后，${affected.joinToString("及")}需重新生成、复核。") + "已封存版本保留。"
}
