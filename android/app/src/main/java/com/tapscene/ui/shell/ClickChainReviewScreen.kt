package com.tapscene.ui.shell

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapscene.clickplan.ClickChainActionEditor
import com.tapscene.clickplan.ClickChainActionRow
import com.tapscene.clickplan.ClickChainFrameEditor
import com.tapscene.clickplan.ClickChainFrameRow
import com.tapscene.clickplan.ClickChainReviewUiState
import com.tapscene.clickplan.ClickChainReviewTarget
import com.tapscene.clickplan.nextRequiredReview
import com.tapscene.clickplan.validationIssue
import com.tapscene.clickplan.ClickChainStageRow
import com.tapscene.clickplan.ClickChainThumbnailRequest
import com.tapscene.clickplan.ClickChainThumbnailSource
import com.tapscene.clickplan.INITIAL_SNAPSHOT_NOTICE
import com.tapscene.clickplan.visibleThumbnailRequests
import com.tapscene.data.ProjectHotspot
import com.tapscene.media.OpaqueMask
import com.tapscene.ui.MaskDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Author intent only. The workspace owns evidence, review, cancellation and commit guards. */
internal data class ClickChainReviewCallbacks(
    val onBack: () -> Unit = {},
    val onTitleChange: (String) -> Unit = {},
    val onRangeChange: (String, String) -> Unit = { _, _ -> },
    val onFramesOnly: (Boolean) -> Unit = {},
    val onStageChoice: (Int, String) -> Unit = { _, _ -> },
    val onToggleFrame: (String, Boolean) -> Unit = { _, _ -> },
    val onReviewFrame: (String) -> Unit = {},
    val onReviewAction: (String) -> Unit = {},
    val onMarkLastTerminal: (Boolean) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onOpenProject: (String) -> Unit = {},
    val onStartAnotherSegment: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onReloadSaved: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onCloseEditor: () -> Unit = {},
    val onFrameTitleChange: (String) -> Unit = {},
    val onAddMask: (OpaqueMask) -> Unit = {},
    val onUndoMask: () -> Unit = {},
    val onGenerateFrame: () -> Unit = {},
    val onConfirmFrame: (String) -> Unit = {},
    val onActionLabelChange: (String) -> Unit = {},
    val onActionRectChange: (OpaqueMask) -> Unit = {},
    val onConfirmAction: () -> Unit = {},
    val loadThumbnails: suspend (List<ClickChainThumbnailRequest>) -> Map<ClickChainThumbnailRequest, Bitmap> = { emptyMap() },
)

/** One workbench, with focused image/action subpanels rather than a multi-page wizard. */
@Composable
internal fun ClickChainReviewContent(state: ClickChainReviewUiState, callbacks: ClickChainReviewCallbacks) {
    // Editors replace the workbench, but must not discard this run's reading position.
    // A new review operation, including another segment of this recording, starts at the top.
    key(state.runId, state.reviewSessionId) {
        val listState = rememberLazyListState()
        var settingsExpanded by rememberSaveable { mutableStateOf(false) }
        var expandedDetails by rememberSaveable { mutableStateOf(emptyList<String>()) }
        when {
            state.frameEditor != null -> ClickChainFramePanel(state.frameEditor, state, callbacks)
            state.actionEditor != null -> ClickChainActionPanel(state.actionEditor, state, callbacks)
            else -> ClickChainWorkbench(state, callbacks, listState, settingsExpanded, { settingsExpanded = it },
                expandedDetails.toSet(), { detail ->
                    expandedDetails = if (detail in expandedDetails) expandedDetails - detail else expandedDetails + detail
                })
        }
    }
}

@Composable
private fun ClickChainWorkbench(state: ClickChainReviewUiState, callbacks: ClickChainReviewCallbacks,
    listState: LazyListState, settingsExpanded: Boolean, onSettingsExpanded: (Boolean) -> Unit,
    expandedDetails: Set<String>, onToggleDetails: (String) -> Unit) {
    // Use this pane's remaining space, including IME insets, not the device configuration.
    // A short pane can live inside a taller window without changing screenHeightDp.
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        ClickChainWorkbenchBody(state, callbacks, listState, settingsExpanded, onSettingsExpanded,
            expandedDetails, onToggleDetails, compactHeight = maxHeight < 480.dp && LocalDensity.current.fontScale <= 1.2f)
    }
}

@Composable
private fun ClickChainWorkbenchBody(state: ClickChainReviewUiState, callbacks: ClickChainReviewCallbacks,
    listState: LazyListState, settingsExpanded: Boolean, onSettingsExpanded: (Boolean) -> Unit,
    expandedDetails: Set<String>, onToggleDetails: (String) -> Unit, compactHeight: Boolean) {
    val editable = !state.busy && !state.loading && !state.editingLocked && state.createdProjectId == null && !state.outcomeUnknown
    val hasResult = !state.outcomeUnknown && (state.createdProjectId != null || state.canStartAnotherSegment)
    val next = state.nextRequiredReview()
    val validation = state.validationIssue()
    val largeText = LocalDensity.current.fontScale > 1.2f
    LaunchedEffect(validation) { if (validation != null) onSettingsExpanded(true) }
    val visibleKeys by remember(listState) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() } }
    val frames = remember(state.frames) { state.frames.associateBy { it.key } }
    val nextFrame = (next as? ClickChainReviewTarget.Frame)?.let { frames[it.key] }
    val nextStage = (next as? ClickChainReviewTarget.Frame)?.let { target ->
        state.stages.firstOrNull { it.selectedKey == target.key }
    }
    val nameRepair = next is ClickChainReviewTarget.Frame &&
        (if (state.framesOnly) nextFrame?.let { it.reviewed && it.title.isBlank() } == true
            else nextStage?.let { it.reviewed && it.title.isBlank() } == true)
    val nextFrameLabel = if (next is ClickChainReviewTarget.Frame) {
        (if (state.framesOnly) nextFrame?.title else nextStage?.title)?.takeIf { it.isNotBlank() }
            ?: "画面 ${if (state.framesOnly) state.frames.indexOfFirst { it.key == next.key } + 1 else (nextStage?.index ?: 0) + 1}"
    } else null
    val requests = if (hasResult) emptyList() else state.visibleThumbnailRequests(visibleKeys)
    val loader by rememberUpdatedState(callbacks.loadThumbnails)
    val loaded by produceState<ClickChainThumbnailBatch?>(null, state.runId, requests) {
        value = null
        if (requests.isEmpty()) return@produceState
        var pending: Map<ClickChainThumbnailRequest, Bitmap> = emptyMap()
        var published = false
        try {
            pending = loader(requests)
            // A non-cooperative loader may return after this run, output or viewport was replaced.
            currentCoroutineContext().ensureActive()
            value = ClickChainThumbnailBatch(state.runId, requests, pending)
            published = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing thumbnails are not missing evidence; the original review control stays usable.
        } finally {
            // Published images can be shared by revisits and still belong to a rendered frame.
            // Only never-published results are safe to recycle; displayed batches are left to GC.
            if (!published) pending.values.distinct().forEach { if (!it.isRecycled) it.recycle() }
        }
    }
    val thumbnails = loaded?.takeIf { it.runId == state.runId && it.requests == requests }?.bitmaps.orEmpty()
    fun thumbnail(frame: ClickChainFrameRow?, visible: Boolean): Bitmap? {
        // Only synthetic previews supply pixels directly; Layoutlib may not settle viewport state.
        frame?.thumbnail?.let { return it }
        if (!visible) return null
        val source = frame?.thumbnailSource ?: return null
        val request = ClickChainThumbnailRequest(frame.key, source)
        return if (request in requests) thumbnails[request] else null
    }
    BackHandler { if (!state.busy) callbacks.onBack() }
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("整理点击链", onBack = { if (!state.busy) callbacks.onBack() })
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("click_chain_workbench"), state = listState, contentPadding = PaddingValues(16.dp, 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "setup") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasResult) ClickChainCommittedResult(state)
                    else {
                        if (state.runLabel.isNotBlank() && (!compactHeight || settingsExpanded)) Text(state.runLabel, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ onSettingsExpanded(validation != null || !settingsExpanded) },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("click_chain_settings"),
                            enabled = editable) {
                            val summary = "${state.title.ifBlank { "未填写名称" }} · 动作 ${state.firstAction}–${state.lastAction}" +
                                if (state.framesOnly) " · 仅加入画面" else ""
                            if (compactHeight && !settingsExpanded && validation == null) Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("项目与范围 · 修改", style = MaterialTheme.typography.labelLarge)
                            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("项目与范围 · ${if (validation != null) "待完善" else if (settingsExpanded) "收起" else "修改"}",
                                    style = MaterialTheme.typography.labelLarge)
                                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (settingsExpanded || validation != null) {
                            OutlinedTextField(state.title, callbacks.onTitleChange, Modifier.fillMaxWidth(),
                                enabled = editable, label = { Text("新项目名称") }, singleLine = true, isError = state.title.isBlank())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(state.firstAction, { callbacks.onRangeChange(it, state.lastAction) }, Modifier.weight(1f),
                                    enabled = editable, label = { Text("起始动作") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                Text("至", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(state.lastAction, { callbacks.onRangeChange(state.firstAction, it) }, Modifier.weight(1f),
                                    enabled = editable, label = { Text("最后动作") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            }
                            validation?.let { ClickChainMessage(it) }
                            Text("整理方式", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val modeChip: @Composable (Boolean, Modifier) -> Unit = { framesOnly, modifier ->
                                FilterChip(state.framesOnly == framesOnly, { callbacks.onFramesOnly(framesOnly) }, modifier = modifier,
                                    label = { Text(if (framesOnly) "仅加入画面" else "按点击链") }, enabled = editable)
                            }
                            if (largeText) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                modeChip(false, Modifier.fillMaxWidth())
                                modeChip(true, Modifier.fillMaxWidth())
                            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                modeChip(false, Modifier.weight(1f))
                                modeChip(true, Modifier.weight(1f))
                            }
                            if (state.framesOnly) Text("仅加入已选画面，不生成点击连线；画面仍须逐张复核。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    state.issue?.takeIf { it != validation }?.let { ClickChainMessage(it) }
                    state.message?.takeIf { it != state.issue && it != validation }?.let { ClickChainMessage(it) }
                    when {
                        state.issue?.startsWith("输入尚未保存") == true -> ClickChainSaveRetry(state, callbacks)
                        validation == null && !state.loading && !state.busy && !state.editingLocked && !state.outcomeUnknown && state.createdProjectId == null &&
                            state.stages.isEmpty() && state.actions.isEmpty() && state.frames.isEmpty() &&
                            (state.issue != null || state.message != null) -> TextButton(callbacks.onRetry) { Text("重新读取") }
                    }
                }
            }
            if (!hasResult && state.framesOnly) {
                item { Text("选择要加入的画面", style = MaterialTheme.typography.titleSmall) }
                items(state.frames, key = { "frame:${it.key}" }) { frame ->
                    val detailKey = "frame:${frame.key}"
                    ClickChainFrameRowContent(frame, editable, thumbnail(frame, detailKey in visibleKeys),
                        next == ClickChainReviewTarget.Frame(frame.key), detailKey in expandedDetails,
                        { onToggleDetails(detailKey) }, callbacks)
                }
                if (state.frames.isEmpty() && !state.loading) item { Text("暂无可加入的画面。", style = MaterialTheme.typography.bodyMedium) }
            } else if (!hasResult) {
                if (!compactHeight || settingsExpanded) item {
                    Text("依次检查画面与点击", style = MaterialTheme.typography.titleSmall)
                    Text("回到同一画面时，路线仍保留这次访问。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.stages.forEachIndexed { position, stage ->
                    item(key = "stage:${stage.index}") {
                        val revisit = stage.selectedKey != null && state.stages.take(position).any { it.selectedKey == stage.selectedKey }
                        val detailKey = "stage:${stage.index}:${stage.selectedKey}"
                        ClickChainStageContent(stage, frames[stage.selectedKey], revisit, editable,
                            thumbnail(frames[stage.selectedKey], "stage:${stage.index}" in visibleKeys),
                            next is ClickChainReviewTarget.Frame && next.key == stage.selectedKey && !revisit,
                            detailKey in expandedDetails, { onToggleDetails(detailKey) }, callbacks)
                    }
                    state.actions.getOrNull(position)?.let { action ->
                        item(key = "action:${action.actionId}") {
                            val detailKey = "action:${action.actionId}"
                            // A warning already attached to the selected source frame is not repeated
                            // as another warning on its action. A different source keeps its own notice.
                            val notice = action.selectionNotice?.takeUnless { it == frames[stage.selectedKey]?.selectionNotice }
                            ClickChainActionRowContent(action, editable, notice,
                                next == ClickChainReviewTarget.Action(action.actionId), detailKey in expandedDetails,
                                { onToggleDetails(detailKey) }, callbacks)
                        }
                    }
                }
                if (state.actions.isEmpty() && !state.loading && state.createdProjectId == null)
                    item { Text("请选择可用的动作范围。", style = MaterialTheme.typography.bodyMedium) }
            }
            if (!hasResult && state.frameCount > 0) item {
                HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.markLastTerminal, callbacks.onMarkLastTerminal, enabled = editable)
                    Text("将最后一张设为演示终点", style = MaterialTheme.typography.bodyMedium)
                }
                Text("最多 40 步 · 80 个动作 · 每步 6 个点击区域", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!hasResult) Text("画面 ${state.reviewedFrameCount}/${state.frameCount}" +
                if (state.framesOnly) " · 仅加入画面" else " · 动作 ${state.confirmedActionCount}/${state.actions.size}",
                style = MaterialTheme.typography.labelLarge)
            if (editable && next != null) Text(when (next) {
                is ClickChainReviewTarget.Frame -> "下一项：$nextFrameLabel${if (nameRepair) " · 补全名称" else ""}"
                is ClickChainReviewTarget.Action -> "下一项：核对动作 ${state.actions.firstOrNull { it.actionId == next.actionId }?.number} 的点击区域与去向"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.retryCreate && state.editingLocked && !state.busy && !state.loading && !state.outcomeUnknown)
                TextButton(callbacks.onCancel) { Text("取消这次生成，继续编辑") }
            val button = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            when {
                state.busy || state.loading -> ClickChainFooterAction("取消处理", callbacks.onCancel, button)
                state.outcomeUnknown -> Button(callbacks.onRetry, button) { Text("核对生成结果") }
                state.createdProjectId != null -> {
                    Button({ callbacks.onOpenProject(state.createdProjectId) }, button) { Text("打开新项目") }
                    if (state.canStartAnotherSegment) ClickChainFooterAction("再整理一段", callbacks.onStartAnotherSegment,
                        Modifier.fillMaxWidth().heightIn(min = 48.dp))
                }
                state.canStartAnotherSegment -> Button(callbacks.onStartAnotherSegment, button) { Text("再整理一段") }
                state.issue?.startsWith("输入尚未保存") == true -> Button(callbacks.onRetry, button) { Text("重试保存输入") }
                next is ClickChainReviewTarget.Frame -> Button({ callbacks.onReviewFrame(next.key) }, button) {
                    Text(if (nameRepair) "补全画面名称" else "继续复核画面")
                }
                next is ClickChainReviewTarget.Action -> Button({ callbacks.onReviewAction(next.actionId) }, button) { Text("继续核对动作") }
                state.editingLocked && !state.retryCreate -> ClickChainFooterAction("返回录制", callbacks.onBack, button)
                else -> Button(callbacks.onCreate, button, enabled = state.canCreate) {
                    Text(when { state.retryCreate -> "重试生成同一项目"; state.canCreate -> "生成独立项目"; else -> "先处理上方待办" })
                }
            }
        }
    }
}

@Composable
private fun ClickChainCommittedResult(state: ClickChainReviewUiState) {
    val exists = state.createdProjectId != null
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
        .padding(20.dp).testTag("click_chain_result"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (exists) "新项目已生成" else "本段已生成过项目", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        Text(state.title, style = MaterialTheme.typography.headlineSmall)
        if (state.frameCount > 0) Text("${state.frameCount} 个画面" +
            if (state.framesOnly) " · 仅加入画面" else " · ${state.actions.size} 个动作",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (exists) "打开新项目，继续编辑和预览。原录屏项目保持原样。"
            else "生成的项目已被删除。重试原操作不会重新创建旧项目。",
            style = MaterialTheme.typography.bodyMedium)
        if (state.canStartAnotherSegment) Text("“再整理一段”会开启独立整理，从选择范围和复核开始，不改变本段结果。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClickChainStageContent(stage: ClickChainStageRow, frame: ClickChainFrameRow?, revisit: Boolean,
    editable: Boolean, thumbnail: Bitmap?, current: Boolean, detailsExpanded: Boolean,
    onToggleDetails: () -> Unit, callbacks: ClickChainReviewCallbacks) {
    val complete = stage.reviewed && stage.title.isNotBlank()
    val notice = frame?.selectionNotice
    val showDetails = !complete || detailsExpanded
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().testTag("click_chain_stage_${stage.index}")
        .background(if (complete) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow, shape)
        .then(if (current) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            ClickChainThumbnail(frame, stage.selectedKey == null, thumbnail, compact = complete)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("画面 ${(stage.index + 1).toString().padStart(2, '0')}${if (revisit) " · 回访" else if (current) " · 当前" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stage.title.ifBlank { if (stage.selectedKey == null) "待补画面" else "未填写名称" }, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(when { stage.selectedKey == null -> "缺少画面证据"; frame != null -> frame.thumbnailLabel(); stage.reviewed -> "已复核"; else -> "待复核" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ stage.selectedKey?.let(callbacks.onReviewFrame) }, enabled = editable && stage.selectedKey != null,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                        Text(if (stage.reviewed) "查看画面" else "复核画面")
                    }
                    if (complete) ClickChainDetailsToggle("画面 ${stage.index + 1}", detailsExpanded, onToggleDetails,
                        snapshot = notice == INITIAL_SNAPSHOT_NOTICE)
                }
            }
        }
        if (notice != null && (showDetails || notice != INITIAL_SNAPSHOT_NOTICE))
            Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (complete && detailsExpanded) Text("已复核实际输出；查看画面可继续核对或修改。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (stage.choices.size > 1 && showDetails) Column {
            Text("代表画面 · 选择后须按当前画面核对", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            stage.choices.forEach { choice ->
                FilterChip(stage.selectedKey == choice.frameKey, { callbacks.onStageChoice(stage.index, choice.frameKey) },
                    label = { Text(choice.label) }, enabled = editable)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClickChainFrameRowContent(frame: ClickChainFrameRow, editable: Boolean, thumbnail: Bitmap?,
    current: Boolean, detailsExpanded: Boolean, onToggleDetails: () -> Unit, callbacks: ClickChainReviewCallbacks) {
    val complete = frame.reviewed && frame.title.isNotBlank()
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().testTag("click_chain_frame_${frame.key}")
        .background(if (complete) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow, shape)
        .then(if (current) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
        .padding(vertical = 8.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(frame.selected, { callbacks.onToggleFrame(frame.key, it) }, enabled = editable)
            ClickChainThumbnail(frame, false, thumbnail, compact = complete)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(frame.title.ifBlank { "未填写名称" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(frame.thumbnailLabel(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ callbacks.onReviewFrame(frame.key) }, enabled = editable,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text(if (frame.reviewed) "查看画面" else "复核画面") }
                    if (complete) ClickChainDetailsToggle(frame.title, detailsExpanded, onToggleDetails,
                        snapshot = frame.selectionNotice == INITIAL_SNAPSHOT_NOTICE)
                }
            }
        }
        frame.selectionNotice?.takeIf { !complete || detailsExpanded || it != INITIAL_SNAPSHOT_NOTICE }?.let {
            Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (complete && detailsExpanded) Text("已复核实际输出；查看画面可继续核对或修改。",
            Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ClickChainDetailsToggle(subject: String, expanded: Boolean, onToggle: () -> Unit, snapshot: Boolean = false) {
    TextButton(onToggle, Modifier.heightIn(min = 48.dp).semantics {
        contentDescription = "${if (expanded) "收起" else "展开"}$subject 的详情${if (snapshot) "与初始快照提示" else ""}"
        stateDescription = if (expanded) "已展开" else "已收起"
    }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
        Text(if (expanded) "收起详情" else if (snapshot) "初始快照" else "详情", style = MaterialTheme.typography.labelMedium)
    }
}

/** No batch survives a different run, source identity or set of visible requests. */
private data class ClickChainThumbnailBatch(val runId: String?, val requests: List<ClickChainThumbnailRequest>,
    val bitmaps: Map<ClickChainThumbnailRequest, Bitmap>)

private fun ClickChainFrameRow.thumbnailLabel(): String = when (thumbnailSource) {
    is ClickChainThumbnailSource.OriginalUnreviewed -> "原始画面 · 未复核"
    is ClickChainThumbnailSource.ReviewedOutput -> "已复核画面"
    null -> if (reviewed) "已复核" else "待复核"
}

@Composable
private fun ClickChainThumbnail(frame: ClickChainFrameRow?, missing: Boolean, thumbnail: Bitmap?, compact: Boolean = false) {
    val bitmap = thumbnail?.takeUnless { it.isRecycled }
    Box(Modifier.size(if (compact) 56.dp else 76.dp, if (compact) 84.dp else 114.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), "${frame?.title}，${frame?.thumbnailLabel()}缩略图",
            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text(when { missing -> "待补画面"; frame?.reviewed == true -> "已复核\n查看画面"; else -> "待复核\n打开画面" },
            Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClickChainActionRowContent(action: ClickChainActionRow, editable: Boolean, notice: String?, current: Boolean,
    detailsExpanded: Boolean, onToggleDetails: () -> Unit, callbacks: ClickChainReviewCallbacks) {
    val complete = action.confirmed && action.detail == null
    Row(Modifier.fillMaxWidth().testTag("click_chain_action_${action.actionId}").padding(start = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(2.dp).heightIn(min = if (complete) 64.dp else 112.dp)
            .background(if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (complete) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onToggleDetails, Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "${if (detailsExpanded) "收起" else "展开"}动作 ${action.number} 的详情"
                    stateDescription = if (detailsExpanded) "已展开" else "已收起"
                }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                    Text("动作 ${action.number.toString().padStart(2, '0')} · 已确认${if (detailsExpanded) " · 收起" else " · 详情"}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ callbacks.onReviewAction(action.actionId) }, enabled = editable && action.enabled,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("查看点击区域") }
            } else Text("动作 ${action.number.toString().padStart(2, '0')} · ${action.status}${if (current) " · 当前" else ""}",
                style = MaterialTheme.typography.labelLarge,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${action.fromTitle.ifBlank { "未填写名称" }} → ${action.toTitle.ifBlank { "未填写名称" }}", style = MaterialTheme.typography.bodyMedium,
                maxLines = if (complete && !detailsExpanded) 2 else 3, overflow = TextOverflow.Ellipsis)
            if (!complete || detailsExpanded) {
                if (complete) Text(action.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(action.detail ?: when { action.confirmed -> "点击区域与去向已确认"; action.enabled -> "点击区域与去向待确认"; else -> "先复核前后画面" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (notice != null) Text(if (complete && !detailsExpanded && notice == INITIAL_SNAPSHOT_NOTICE)
                "初始快照：展开动作详情查看提示。" else notice,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!complete) TextButton({ callbacks.onReviewAction(action.actionId) }, enabled = editable && action.enabled) {
                Text(if (action.confirmed) "查看点击区域" else "核对点击区域")
            }
        }
    }
}

@Composable
private fun ClickChainFramePanel(editor: ClickChainFrameEditor, state: ClickChainReviewUiState, callbacks: ClickChainReviewCallbacks) {
    val source = editor.bitmap?.takeUnless { it.isRecycled }
    val output = editor.output?.takeUnless { it.isRecycled }
    val digest = editor.outputDigest
    val hasOutput = output != null && !digest.isNullOrBlank()
    var reviewMode by remember(editor.key) { mutableStateOf(hasOutput) }
    var maskDialog by remember(editor.key) { mutableStateOf(false) }
    var displayedDigest by remember(editor.key, digest, output, editor.title, editor.masks, reviewMode) { mutableStateOf<String?>(null) }
    val idle = !state.busy
    val close: () -> Unit = { if (idle) callbacks.onCloseEditor() }
    BackHandler(enabled = !maskDialog, onBack = close)
    LaunchedEffect(editor.key, output, digest) { reviewMode = hasOutput }
    LaunchedEffect(state.busy) { if (state.busy) maskDialog = false }
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("复核画面", onBack = close)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!reviewMode, { reviewMode = false }, label = { Text("遮挡") }, enabled = idle && source != null)
            FilterChip(reviewMode, { reviewMode = true }, label = { Text("查看输出") }, enabled = idle && hasOutput)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val availableHeight = maxHeight
            val landscape = maxWidth > maxHeight
            val canvas: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    when {
                        reviewMode && hasOutput -> key(editor.key, digest, output, editor.title, editor.masks) {
                            ActualClickChainOutput(requireNotNull(output), requireNotNull(digest), { displayedDigest = it })
                        }
                        !reviewMode && source != null -> ClickChainMaskCanvas(source, editor.masks,
                            idle && editor.masks.size < 20, callbacks.onAddMask)
                        else -> ClickChainEmptyCanvas(if (state.busy) "正在读取画面" else "画面暂不可用，请返回重试。")
                    }
                }
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(editor.title, callbacks.onFrameTitleChange, Modifier.fillMaxWidth(),
                        enabled = idle, label = { Text("画面标题") }, singleLine = true, isError = editor.title.isBlank(),
                        supportingText = if (editor.title.isBlank()) {
                            { Text("请填写画面名称；已有画面复核与遮挡会保留。") }
                        } else null)
                    editor.selectionNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (reviewMode) Text("可双指放大检查完整输出，确认没有遗漏的敏感内容。", style = MaterialTheme.typography.bodySmall)
                    else {
                        Text("拖动画面添加遮挡；无敏感内容可直接生成。", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${editor.masks.size} 处遮挡", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            TextButton({ maskDialog = true }, enabled = idle && source != null && editor.masks.size < 20) { Text("精确添加") }
                            TextButton(callbacks.onUndoMask, enabled = idle && editor.masks.isNotEmpty()) { Text("撤销") }
                        }
                    }
                    state.issue?.takeIf { it.startsWith("输入尚未保存") }?.let { ClickChainMessage(it) }
                    state.message?.takeIf { it != state.issue }?.let { ClickChainMessage(it) }
                    ClickChainSaveRetry(state, callbacks)
                }
            }
            if (landscape) Row(Modifier.fillMaxSize()) {
                canvas(Modifier.weight(1f).fillMaxSize())
                controls(Modifier.weight(1f).fillMaxSize())
            } else Column(Modifier.fillMaxSize()) {
                canvas(Modifier.weight(1f).fillMaxWidth())
                controls(Modifier.fillMaxWidth().heightIn(max = (availableHeight * .43f).coerceAtLeast(130.dp)))
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ClickChainEditorFooter(state.busy, callbacks.onCancel, close,
            if (reviewMode) "确认这张画面" else "生成并查看输出",
            idle && editor.title.isNotBlank() && if (reviewMode) hasOutput && displayedDigest == digest else source != null,
            { if (reviewMode) digest?.let(callbacks.onConfirmFrame) else callbacks.onGenerateFrame() })
    }
    if (maskDialog) MaskDialog({ maskDialog = false }) { mask -> maskDialog = false; callbacks.onAddMask(mask) }
}

/** Existing full-image/zoom review, without editable masks or hotspot overlays. */
@Composable
private fun ActualClickChainOutput(bitmap: Bitmap, digest: String, onDisplayed: (String) -> Unit) {
    var laidOut by remember(bitmap, digest) { mutableStateOf(false) }
    val currentDisplayed by rememberUpdatedState(onDisplayed)
    LaunchedEffect(bitmap, digest, laidOut) {
        if (laidOut) {
            withFrameNanos { }
            withFrameNanos { }
            currentDisplayed(digest)
        }
    }
    CorrectionImage(bitmap, "待复核的实际生成画面，没有编辑标记", enabled = true,
        modifier = Modifier.fillMaxSize().onSizeChanged { laidOut = it.width > 0 && it.height > 0 })
}

/** The input is the private derived preview supplied by the writer, never a raw file path. */
@Composable
private fun ClickChainMaskCanvas(bitmap: Bitmap, masks: List<OpaqueMask>, enabled: Boolean, onMask: (OpaqueMask) -> Unit) {
    var start by remember(bitmap, enabled) { mutableStateOf<Offset?>(null) }
    var end by remember(bitmap, enabled) { mutableStateOf<Offset?>(null) }
    val currentMask by rememberUpdatedState(onMask)
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
        val ratio = bitmap.width.toFloat() / bitmap.height
        val width = minOf(maxWidth, maxHeight * ratio)
        Box(Modifier.size(width, width / ratio)) {
            Image(remember(bitmap) { bitmap.asImageBitmap() }, "待复核的画面，拖动可添加遮挡", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize().pointerInput(bitmap, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(onDragStart = { start = it; end = it }, onDragCancel = { start = null; end = null },
                    onDragEnd = {
                        val a = start; val b = end
                        if (a != null && b != null && size.width > 0 && size.height > 0 && abs(a.x - b.x) >= 4 && abs(a.y - b.y) >= 4) {
                            val left = (min(a.x, b.x) / size.width).coerceIn(0f, 1f)
                            val top = (min(a.y, b.y) / size.height).coerceIn(0f, 1f)
                            val right = (max(a.x, b.x) / size.width).coerceIn(0f, 1f)
                            val bottom = (max(a.y, b.y) / size.height).coerceIn(0f, 1f)
                            if (right > left && bottom > top) currentMask(OpaqueMask(left, top, right, bottom))
                        }
                        start = null; end = null
                    }, onDrag = { change, _ -> change.consume(); end = change.position })
            }) {
                masks.forEach { mask -> drawRect(Color.Black, Offset(mask.left * size.width, mask.top * size.height),
                    Size((mask.right - mask.left) * size.width, (mask.bottom - mask.top) * size.height)) }
                val a = start; val b = end
                if (a != null && b != null) {
                    val left = min(a.x, b.x).coerceIn(0f, size.width)
                    val top = min(a.y, b.y).coerceIn(0f, size.height)
                    val right = max(a.x, b.x).coerceIn(0f, size.width)
                    val bottom = max(a.y, b.y).coerceIn(0f, size.height)
                    drawRect(Color.Black, Offset(left, top), Size(right - left, bottom - top))
                }
            }
        }
    }
}

@Composable
private fun ClickChainActionPanel(editor: ClickChainActionEditor, state: ClickChainReviewUiState, callbacks: ClickChainReviewCallbacks) {
    val bitmap = editor.bitmap?.takeUnless { it.isRecycled }
    val target = editor.targetThumbnail?.takeUnless { it.isRecycled }
    val idle = !state.busy
    val precisionEnabled = idle && !state.loading && !state.editingLocked && !state.outcomeUnknown &&
        state.createdProjectId == null && bitmap != null && target != null
    // Only this editor opening and actual source image own the raw strings. They are not saved
    // across configuration/process recreation, or borrowed by a replacement run/action/image.
    var precisionForm by remember(state.runId, editor.actionId, editor.instanceKey, bitmap) {
        mutableStateOf<ClickChainRectPercentages?>(null)
    }
    var precisionOriginal by remember(state.runId, editor.actionId, editor.instanceKey, bitmap) {
        mutableStateOf(editor.rect)
    }
    val close: () -> Unit = { if (idle) callbacks.onCloseEditor() }
    BackHandler(onBack = close)
    // Re-keying resets EditorCanvas's in-flight drag on a different action or safe image.
    // Canvas-only rectangle; the successor is supplied and shown separately by the workspace.
    val hotspot = ProjectHotspot(editor.actionId, editor.label, editor.rect, null, null, editor.actionId)
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("确认点击区域", onBack = close)
        Text("按编排位置预填，可调整范围", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val availableHeight = maxHeight
            val canvas: @Composable (Modifier) -> Unit = { modifier ->
                key(editor.actionId, bitmap) {
                    EditorCanvas(bitmap, listOf(hotspot), modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        .clearAndSetSemantics { contentDescription = "当前已复核画面；拖动点击区域，拖右下角调整大小" },
                        enabled = idle && bitmap != null, busy = state.busy, selectedHotspotId = editor.actionId,
                        onChangeRect = { id, rect -> if (id == editor.actionId) callbacks.onActionRectChange(rect) }, objectLabel = "点击区域")
                }
            }
            val details: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("拖动区域移动，拖右下角调整大小。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ precisionOriginal = editor.rect; precisionForm = ClickChainRectPercentages.from(editor.rect) },
                        enabled = precisionEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("范围精调") }
                    editor.selectionNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedTextField(editor.label, callbacks.onActionLabelChange, Modifier.fillMaxWidth(),
                        enabled = idle, label = { Text("动作名称") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (target != null) Image(remember(target) { target.asImageBitmap() }, "后继画面：${editor.targetTitle}",
                            Modifier.size(40.dp, 60.dp).background(MaterialTheme.colorScheme.surfaceContainerLow), contentScale = ContentScale.Fit)
                        Column(Modifier.weight(1f)) {
                            Text("点击后到达", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(editor.targetTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (target == null) Text("后继画面待读取", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("编排点位：横向 ${(editor.point.first * 100).roundToInt()}% · 纵向 ${(editor.point.second * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (editor.selfLoop) ClickChainMessage("此动作返回同一画面，请确认这是你要的后继。")
                    if (editor.samePageActionCount > 1) ClickChainMessage("同一画面保留 ${editor.samePageActionCount} 个动作，请逐个核对。")
                    state.issue?.takeIf { it.startsWith("输入尚未保存") }?.let { ClickChainMessage(it) }
                    state.message?.takeIf { it != state.issue }?.let { ClickChainMessage(it) }
                    ClickChainSaveRetry(state, callbacks)
                }
            }
            if (maxWidth > maxHeight) Row(Modifier.fillMaxSize()) {
                canvas(Modifier.weight(1f).fillMaxSize())
                details(Modifier.weight(1f).fillMaxSize())
            } else Column(Modifier.fillMaxSize()) {
                canvas(Modifier.weight(1f).fillMaxWidth())
                details(Modifier.fillMaxWidth().heightIn(max = (availableHeight * .48f).coerceAtLeast(140.dp)))
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ClickChainEditorFooter(state.busy, callbacks.onCancel, close, "确认点击区域与后继",
            idle && bitmap != null && target != null && editor.label.isNotBlank(), callbacks.onConfirmAction)
    }
    precisionForm?.let { form ->
        ClickChainActionPrecisionDialog(precisionOriginal, form, precisionEnabled,
            onChange = { precisionForm = it }, onDismiss = { precisionForm = null }, onApply = { rect ->
                if (precisionEnabled && precisionForm != null) {
                    precisionForm = null
                    // Opening, dismissing and applying unchanged geometry must not clear confirmation.
                    if (rect != editor.rect) callbacks.onActionRectChange(rect)
                }
            })
    }
}

@Composable
private fun ClickChainEditorFooter(busy: Boolean, onCancel: () -> Unit, onClose: () -> Unit,
    label: String, enabled: Boolean, onConfirm: () -> Unit) {
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) ClickChainFooterAction("取消处理", onCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp))
        else {
            ClickChainFooterAction("返回整理", onClose, Modifier.heightIn(min = 48.dp))
            Button(onConfirm, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled) { Text(label) }
        }
    }
}

/** These secondary exits remain available even when editing is locked. */
@Composable
private fun ClickChainFooterAction(label: String, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick, modifier, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(label) }
}

@Composable
private fun ClickChainSaveRetry(state: ClickChainReviewUiState, callbacks: ClickChainReviewCallbacks) {
    if (state.issue?.startsWith("输入尚未保存") == true) {
        var reload by remember { mutableStateOf(false) }
        TextButton(callbacks.onRetry, enabled = !state.loading && !state.busy) { Text("重试保存") }
        TextButton({ reload = true }, enabled = !state.loading && !state.busy && !state.outcomeUnknown) { Text("重新读取已保存复核") }
        if (reload) AlertDialog(onDismissRequest = { reload = false },
            title = { Text("重新读取已保存复核？") },
            text = { Text("当前窗口尚未保存的修改会被放弃。已保存的画面复核和点击区域会重新载入。") },
            confirmButton = { TextButton({ reload = false; callbacks.onReloadSaved() }) { Text("放弃未保存修改并读取") } },
            dismissButton = { TextButton({ reload = false }) { Text("保留当前输入") } })
    }
}

@Composable
private fun ClickChainMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ClickChainEmptyCanvas(text: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
