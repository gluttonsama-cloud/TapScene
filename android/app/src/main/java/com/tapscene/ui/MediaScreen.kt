package com.tapscene.ui

import android.graphics.Bitmap
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapscene.data.ReviewedStepInput
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.ui.shell.ScreenEmpty
import com.tapscene.ui.shell.CandidateTextSuggestions
import com.tapscene.ui.shell.OcrSuggestions
import com.tapscene.ui.shell.ShellColors
import com.tapscene.ui.shell.ShellTopBar
import com.tapscene.ui.shell.StatusNote
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class ManualMediaMode(val label: String) {
    FRAME("画面"), MASK("遮挡"), CLIP("短片"), REVIEW("复核"),
}

@Composable
fun MediaScreen(
    workspace: MediaWorkspace,
    onBack: (() -> Unit)? = null,
    headerTitle: String = "手动校正",
    confirmLabel: String = "确认画面并加入步骤",
    batchReview: Boolean = false,
    reviewItemId: String? = null,
    onSkip: (() -> Unit)? = null,
    textSuggestions: CandidateTextSuggestions? = null,
    stepTitle: String? = null,
    onStepTitleChange: (String) -> Unit = {},
    onUseSuggestedTitle: (String) -> Unit = {},
    onReviewedImage: (suspend (ReviewedStepInput) -> Unit)? = null,
) {
    val state by workspace.state.collectAsStateWithLifecycle()
    var pendingDigest by rememberSaveable { mutableStateOf("") }
    var savePending by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(ManualMediaMode.FRAME) }
    var sourceMenu by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var maskDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val idle = !state.busy && !savePending
    val canEdit = idle && !state.unsavedEdits
    val canLeave = canEdit
    val matchingSuggestions = textSuggestions?.takeIf {
        it.candidateId == reviewItemId && it.matches(workspace.projectId, state.selected?.source, state.frameReviewId, state.frame)
    }
    val suggestionsPanel: @Composable () -> Unit = {
        if (matchingSuggestions != null && stepTitle != null) {
            OcrSuggestions(matchingSuggestions.result, stepTitle, canEdit, (state.selected?.masks?.size ?: 20) < 20,
                onUseTitle = { suggestion ->
                    val current = workspace.state.value
                    if (!savePending && !current.busy && !current.unsavedEdits && stepTitle.isBlank() &&
                        matchingSuggestions.matches(workspace.projectId, current.selected?.source, current.frameReviewId, current.frame)) {
                        onUseSuggestedTitle(suggestion)
                    }
                },
                onMask = { mask ->
                    val current = workspace.state.value
                    if (!savePending && !current.busy && !current.unsavedEdits && (current.selected?.masks?.size ?: 20) < 20 &&
                        matchingSuggestions.matches(workspace.projectId, current.selected?.source, current.frameReviewId, current.frame)) {
                        workspace.addMask(mask)
                        mode = ManualMediaMode.MASK
                    }
                })
        } else if (textSuggestions != null && !state.busy && state.frame != null) {
            Text("画面已调整，原文字建议不再适用。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        }
    }
    val leave: () -> Unit = {
        if (canLeave) onBack?.invoke() else workspace.message("请等待处理完成，或先取消处理并保存修改")
    }
    BackHandler(enabled = onBack != null, onBack = leave)
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) workspace.importVideo(uri)
    }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        savePending = false
        if (uri != null) workspace.saveCandidate(uri, pendingDigest) else workspace.cancelSavePicker()
    }
    val saveMp4 = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        savePending = false
        if (uri != null) workspace.saveCandidate(uri, pendingDigest) else workspace.cancelSavePicker()
    }
    // Only a generated candidate opens review. Source pixels never stand in for the output.
    LaunchedEffect(state.candidate?.sha256) {
        if (state.candidate != null) mode = ManualMediaMode.REVIEW
    }
    LaunchedEffect(state.candidate?.sha256, state.busy) {
        if (state.candidate == null && !state.busy && mode == ManualMediaMode.REVIEW) mode = ManualMediaMode.FRAME
    }
    LaunchedEffect(mode, state.candidate?.sha256) { scrollState.scrollTo(0) }

    Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
        ShellTopBar(title = headerTitle, onBack = if (onBack != null) leave else null) {
            if (batchReview) {
                if (onSkip != null) TextButton(onClick = onSkip, enabled = canEdit) { Text("稍后") }
            } else Box {
                TextButton(onClick = { sourceMenu = true }, enabled = idle) { Text("素材 ▾") }
                DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                    state.drafts.forEachIndexed { index, draft ->
                        DropdownMenuItem(
                            text = {
                                Text("${if (draft.source.sourceId == state.selectedId) "✓ " else ""}${index + 1}. ${draft.source.displayName}",
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            onClick = {
                                sourceMenu = false
                                workspace.selectSource(draft.source.sourceId)
                                mode = ManualMediaMode.FRAME
                            },
                            enabled = canEdit,
                        )
                    }
                    if (state.drafts.isNotEmpty()) HorizontalDivider()
                    DropdownMenuItem(text = { Text("导入录屏…") }, onClick = {
                        sourceMenu = false
                        import.launch(arrayOf("video/mp4"))
                    }, enabled = canEdit && !state.loadFailed)
                    if (state.selected != null) DropdownMenuItem(text = { Text("删除 App 中的素材副本") }, onClick = {
                        sourceMenu = false
                        deleteDialog = true
                    }, enabled = canEdit)
                }
            }
        }
        state.selected?.let { selected ->
            Text("本机私有 · ${selected.source.displayName}",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth()) {
            ManualMediaMode.entries.filterNot { batchReview && it == ManualMediaMode.CLIP }.forEach { item ->
                val available = when (item) {
                    ManualMediaMode.FRAME -> true
                    ManualMediaMode.MASK, ManualMediaMode.CLIP -> state.frame != null
                    ManualMediaMode.REVIEW -> state.candidate != null
                }
                Column(Modifier.weight(1f)) {
                    Tab(selected = mode == item, onClick = { mode = item }, enabled = idle && available,
                        modifier = Modifier.heightIn(min = 48.dp), text = { Text(item.label) },
                        selectedContentColor = ShellColors.Accent, unselectedContentColor = ShellColors.Muted)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(
                        if (mode == item) ShellColors.Accent else ShellColors.Divider))
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loadFailed) {
                StatusNote("已保存记录暂时无法读取。为保留原有内容，当前不能导入新素材。")
                OutlinedButton(onClick = workspace::reload, enabled = idle) { Text("重新读取") }
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(state.stage.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = workspace::cancel) { Text("取消") }
                }
            }
            if (savePending) StatusNote("请在系统文件窗口选择保存位置；取消后可继续校正。")
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ShellColors.Accent) }
            if (state.unsavedEdits) {
                StatusNote("遮挡尚未保存，修改仍保留在当前画面。保存成功后才能生成成品。")
                OutlinedButton(onClick = workspace::retryEdits, enabled = idle) { Text("重试保存遮挡") }
            }
            val selected = state.selected
            if (selected == null && !state.loadFailed) {
                ScreenEmpty("选择要校正的素材", "手动选取录屏中的画面，按需遮挡，再确认实际输出。原片仅保留在本机私有空间。")
                OutlinedButton(onClick = { import.launch(arrayOf("video/mp4")) }, enabled = canEdit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("导入已有录屏") }
                Text("支持 H.264 / H.265；单段不超过 3 分钟、200 MiB，按本机能力解码。",
                    style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            }
            if (selected != null) key(selected.source.sourceId) {
                val meta = selected.source.metadata
                // Keep local time fields when moving between modes; they still invalidate output on edit.
                var requestedSeconds by rememberSaveable { mutableStateOf(selected.frameTimeUs / 1_000_000f) }
                LaunchedEffect(state.frame?.presentationTimeUs, batchReview) {
                    if (batchReview) state.frame?.let { requestedSeconds = it.presentationTimeUs / 1_000_000f }
                }
                var start by rememberSaveable { mutableStateOf("0.000") }
                var end by rememberSaveable { mutableStateOf(seconds(min(meta.durationUs, 3_000_000))) }
                when (mode) {
                    ManualMediaMode.FRAME -> {
                        state.frame?.let { frame ->
                            FrameEditor(frame.bitmap, selected.masks, false, workspace::addMask)
                            Text("当前画面 ${frame.presentationTimeUs / 1000} ms · 毫秒精度",
                                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                        } ?: StatusNote("选择时间并手动取帧，查看这段录屏中的实际画面。")
                        Text("取帧位置 ${seconds((requestedSeconds * 1_000_000).toLong())} 秒",
                            style = MaterialTheme.typography.titleSmall)
                        Slider(value = requestedSeconds, onValueChange = { requestedSeconds = it },
                            valueRange = 0f..(meta.durationUs / 1_000_000f).coerceAtLeast(0.001f), enabled = canEdit,
                            modifier = Modifier.semantics { contentDescription = "录屏取帧时间" })
                        OutlinedButton(onClick = {
                            val time = (requestedSeconds * 1_000_000).toLong()
                            if (batchReview) workspace.prepareCandidateImage(selected.source.sourceId, time, requireNotNull(reviewItemId)) else workspace.takeFrame(time)
                        },
                            enabled = canEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("手动取这一帧") }
                        suggestionsPanel()
                        if (state.frame != null && !batchReview) {
                            Text("没有敏感内容可直接继续；需要遮挡时切换到“遮挡”。",
                                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                            Button(onClick = workspace::makeImage, enabled = canEdit,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("生成画面并复核") }
                        }
                        Text("${meta.displayWidth} × ${meta.displayHeight} · ${seconds(meta.durationUs)} 秒 · ${String.format(Locale.ROOT, "%.1f", meta.byteLength / 1048576.0)} MiB",
                            style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    }
                    ManualMediaMode.MASK -> {
                        state.frame?.let { frame ->
                            FrameEditor(frame.bitmap, selected.masks, canEdit && selected.masks.size < 20, workspace::addMask)
                            Text("拖出不透明遮挡 · ${selected.masks.size}/20 块", style = MaterialTheme.typography.titleSmall)
                            Text("仅在需要时添加。这里是本机原片，生成后还需检查实际输出。",
                                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { maskDialog = true }, enabled = canEdit && selected.masks.size < 20,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("按比例添加") }
                                TextButton(onClick = workspace::undoMask, enabled = canEdit && selected.masks.isNotEmpty(),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("撤销最后一块") }
                            }
                            suggestionsPanel()
                            Button(onClick = workspace::makeImage, enabled = canEdit,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("生成画面并复核") }
                        } ?: StatusNote("先在“画面”中取一帧，再按需添加遮挡。")
                    }
                    ManualMediaMode.CLIP -> {
                        state.frame?.let { frame -> FrameEditor(frame.bitmap, selected.masks, false, workspace::addMask) }
                        Text("截取无声短片", style = MaterialTheme.typography.titleMedium)
                        Text("每段最多 10 秒。遮挡固定覆盖整段，请覆盖敏感内容的完整运动范围；输出移除全部原音轨。",
                            style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(value = start, onValueChange = { start = it; workspace.invalidateCandidate() },
                                label = { Text("起点（秒）") }, singleLine = true, enabled = canEdit,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                            OutlinedTextField(value = end, onValueChange = { end = it; workspace.invalidateCandidate() },
                                label = { Text("终点（秒）") }, singleLine = true, enabled = canEdit,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        }
                        val startUs = parseSeconds(start)
                        val endUs = parseSeconds(end)
                        val valid = startUs != null && endUs != null && startUs >= 0 && endUs > startUs &&
                            endUs <= meta.durationUs && endUs - startUs <= 10_000_000
                        Button(onClick = { workspace.makeVideo(requireNotNull(startUs), requireNotNull(endUs)) },
                            enabled = canEdit && state.frame != null && valid,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("生成短片并复核") }
                        Text("短片可单独保存为文件，尚未接入步骤过渡。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    }
                    ManualMediaMode.REVIEW -> {
                        state.candidate?.let { candidate ->
                            val isImage = candidate.mimeType == "image/png"
                            Text("检查实际输出", style = MaterialTheme.typography.titleMedium)
                            if (isImage) {
                                state.candidateImage?.let { output ->
                                    MediaCanvas(output.width, output.height) {
                                        Image(output.asImageBitmap(), contentDescription = "已重新解码的实际输出图片，等待复核",
                                            modifier = Modifier.fillMaxSize())
                                    }
                                } ?: StatusNote("实际输出图片暂时无法显示，请重新生成后复核。")
                            } else {
                                key(candidate.sha256) { ReviewVideo(candidate, workspace::videoWatched) }
                                if (state.watchedDigest != candidate.sha256) Text("请从头完整播放一次，再确认复核。",
                                    style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                            }
                            Text("${candidate.width} × ${candidate.height} · ${if (isImage) "PNG" else "无音轨 MP4"}",
                                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                            if (isImage && onReviewedImage != null) {
                                if (stepTitle != null) {
                                    OutlinedTextField(value = stepTitle, onValueChange = { value ->
                                        if (value.length <= 120 && value.none { it.isISOControl() }) onStepTitleChange(value)
                                    }, label = { Text("步骤标题（可选）") }, singleLine = true, enabled = canEdit,
                                        modifier = Modifier.fillMaxWidth())
                                    Text("标题会进入作品，请一并复核。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                                }
                                suggestionsPanel()
                                Text("请检查上方画面；如有敏感内容，确认已完整遮挡。",
                                    style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = {
                                    // Do not approve a replacement candidate from a stale composition.
                                    val current = workspace.state.value
                                    if (!savePending && !current.busy && !current.unsavedEdits &&
                                        current.candidate?.sha256 == candidate.sha256 && current.candidateImage != null &&
                                        (!batchReview || current.frameReviewId == reviewItemId)) {
                                        workspace.review(true)
                                        workspace.saveReviewedImage(onReviewedImage)
                                    }
                                }, enabled = canEdit && state.candidateImage != null && (!batchReview || state.frameReviewId == reviewItemId),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(confirmLabel) }
                                HorizontalDivider()
                            }
                            if (!batchReview) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = state.reviewedDigest == candidate.sha256,
                                    onCheckedChange = { checked ->
                                        val current = workspace.state.value
                                        if (!savePending && !current.busy && !current.unsavedEdits &&
                                            current.candidate?.sha256 == candidate.sha256) workspace.review(checked)
                                    },
                                    enabled = canEdit && (if (isImage) state.candidateImage != null else state.watchedDigest == candidate.sha256))
                                Text("已检查实际输出，可保存到文件", modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            OutlinedButton(onClick = {
                                if (!savePending && workspace.beginSave(candidate.sha256)) {
                                    pendingDigest = candidate.sha256
                                    savePending = true
                                    try {
                                        if (isImage) savePng.launch("tapscene-frame.png")
                                        else saveMp4.launch("tapscene-transition.mp4")
                                    } catch (_: android.content.ActivityNotFoundException) {
                                        savePending = false
                                        workspace.cancelSavePicker()
                                        workspace.message("系统没有可用的文件保存工具")
                                    }
                                }
                            }, enabled = canEdit && state.reviewedDigest == candidate.sha256 && (!isImage || state.candidateImage != null),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存到文件") }
                            Text("仅导出本次已复核的图片或短片，不包含原片。离线观看包尚未接入。",
                                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false },
        title = { Text("删除 App 中的素材副本？") },
        text = { Text("只删除 TapScene 私有空间中的这段录屏副本及取帧、遮挡记录、本机文字建议，无法撤销。系统原文件和已导出文件不受影响；仍被项目步骤引用的素材会保留。") },
        confirmButton = { TextButton(onClick = { deleteDialog = false; workspace.deleteSelected() }, enabled = canEdit) { Text("删除 App 副本") } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
    if (maskDialog) MaskDialog(onDismiss = { maskDialog = false }) { mask ->
        maskDialog = false
        if (canEdit) workspace.addMask(mask)
    }
}

/** Fit the complete image inside a generous canvas, without a second window-inset owner. */
@Composable
private fun MediaCanvas(width: Int, height: Int, content: @Composable BoxScope.() -> Unit) {
    val ratio = width.toFloat() / height
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().background(ShellColors.Quiet, RoundedCornerShape(8.dp)).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val canvasWidth = minOf(maxWidth, 440.dp * ratio)
        Box(Modifier.width(canvasWidth).aspectRatio(ratio), content = content)
    }
}

@Composable
private fun FrameEditor(bitmap: Bitmap, masks: List<OpaqueMask>, enabled: Boolean, onMask: (OpaqueMask) -> Unit) {
    var start by remember(bitmap) { mutableStateOf<Offset?>(null) }
    var end by remember(bitmap) { mutableStateOf<Offset?>(null) }
    MediaCanvas(bitmap.width, bitmap.height) {
        Image(bitmap.asImageBitmap(), contentDescription = if (enabled) "本机原片，拖动画面可添加遮挡" else "本机原片及当前遮挡，仅供编辑参考", modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().pointerInput(bitmap, enabled, masks.size) {
            if (enabled) detectDragGestures(
                onDragStart = { start = it; end = it },
                onDragCancel = { start = null; end = null },
                onDragEnd = {
                    val from = start
                    val to = end
                    if (from != null && to != null && abs(from.x - to.x) >= 4 && abs(from.y - to.y) >= 4) {
                        val left = (min(from.x, to.x) / size.width).coerceIn(0f, 1f)
                        val top = (min(from.y, to.y) / size.height).coerceIn(0f, 1f)
                        val right = (max(from.x, to.x) / size.width).coerceIn(0f, 1f)
                        val bottom = (max(from.y, to.y) / size.height).coerceIn(0f, 1f)
                        if (right > left && bottom > top) onMask(OpaqueMask(left, top, right, bottom))
                    }
                    start = null; end = null
                },
                onDrag = { change, _ -> change.consume(); end = change.position },
            )
        }) {
            masks.forEach { mask ->
                drawRect(Color.Black, Offset(mask.left * size.width, mask.top * size.height),
                    Size((mask.right - mask.left) * size.width, (mask.bottom - mask.top) * size.height))
            }
            val from = start
            val to = end
            if (from != null && to != null) drawRect(Color.Black,
                Offset(min(from.x, to.x), min(from.y, to.y)), Size(abs(from.x - to.x), abs(from.y - to.y)))
        }
    }
}

@Composable
private fun MaskDialog(onDismiss: () -> Unit, onConfirm: (OpaqueMask) -> Unit) {
    var left by remember { mutableStateOf("10") }
    var top by remember { mutableStateOf("10") }
    var right by remember { mutableStateOf("60") }
    var bottom by remember { mutableStateOf("25") }
    val rectangle = runCatching {
        OpaqueMask(left.toFloat() / 100, top.toFloat() / 100, right.toFloat() / 100, bottom.toFloat() / 100)
    }.getOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加遮挡（画面百分比）") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("左", left) { value: String -> left = value }, Triple("上", top) { value: String -> top = value },
                Triple("右", right) { value: String -> right = value }, Triple("下", bottom) { value: String -> bottom = value })
                .forEach { (label, value, change) ->
                    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
        }
    }, confirmButton = { TextButton(onClick = { rectangle?.let(onConfirm) }, enabled = rectangle != null) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ReviewVideo(candidate: SafeMediaWriter.CandidateMedia, onWatched: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(candidate.sha256) { VideoView(context) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var startedFromBeginning by remember { mutableStateOf(false) }
    DisposableEffect(player, owner) {
        player.setOnPreparedListener { ready = true }
        player.setOnCompletionListener { if (startedFromBeginning && !error) onWatched(candidate.sha256) }
        player.setOnErrorListener { _, _, _ -> error = true; true }
        player.setVideoPath(candidate.file.path)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.stopPlayback() }
    }
    MediaCanvas(candidate.width, candidate.height) {
        AndroidView(factory = { player }, modifier = Modifier.fillMaxSize())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { player.seekTo(0); startedFromBeginning = true; player.start() }, enabled = ready && !error) { Text("从头播放") }
        TextButton(onClick = { if (player.isPlaying) player.pause() else player.start() }, enabled = ready && startedFromBeginning && !error) { Text("暂停 / 继续") }
    }
    if (error) Text("生成视频无法播放，请重新生成；当前不能确认复核。")
}

private fun seconds(value: Long): String = String.format(Locale.ROOT, "%.3f", value / 1_000_000.0)
private fun parseSeconds(value: String): Long? = value.toDoubleOrNull()
    ?.takeIf { it.isFinite() && it in 0.0..180.0 }?.let { (it * 1_000_000).toLong() }
