package com.tapscene.ui.shell

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapscene.data.EditorFormKind
import com.tapscene.data.EditorPendingForm
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.ProjectLimits
import com.tapscene.data.ProjectNextAction
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep
import com.tapscene.media.OpaqueMask
import com.tapscene.ui.DraftRecoveryStatus
import com.tapscene.ui.StepEditDraft
import java.util.UUID

/** A single correction workspace. This does not own project persistence or source media. */
enum class EditorMode(val label: String) {
    FRAME("画面"), HOTSPOTS("热点"), BRANCHES("分支"), REDACTIONS("遮挡"), REGIONS("区域")
}

data class EditorCallbacks(
    val onBack: () -> Unit,
    val onTitleChange: (String) -> Unit,
    val onDescriptionChange: (String) -> Unit,
    val onTerminalChange: (Boolean) -> Unit,
    val onPutHotspot: (ProjectHotspot) -> Unit,
    val onRemoveHotspot: (String) -> Unit,
    val onSave: () -> Unit,
    val onDiscard: () -> Unit,
    val onPreview: () -> Unit,
    val onOpenTransition: (String) -> Unit,
    val onPutNextAction: (ProjectNextAction) -> Unit = {},
    val onRemoveNextAction: () -> Unit = {},
    val onOpenRegions: () -> Unit = {},
    val onCorrectImage: (Boolean) -> Unit = {},
    val onPendingFormChange: (EditorPendingForm?) -> Unit = {},
    val onRetryStaging: () -> Unit = {},
    val onResolveConflict: (Boolean) -> Unit = {},
)

@Composable
fun EditorWorkspaceContent(
    project: ProjectSnapshot,
    draft: StepEditDraft,
    bitmap: Bitmap?,
    busy: Boolean,
    callbacks: EditorCallbacks,
    modifier: Modifier = Modifier,
    previewEnabled: Boolean = !draft.dirty,
    regionsEnabled: Boolean = !draft.dirty,
    correctionEnabled: Boolean = true,
) {
    var mode by rememberSaveable(draft.stepId) { mutableStateOf(EditorMode.FRAME) }
    var adding by rememberSaveable(draft.stepId) { mutableStateOf(false) }
    var selectedId by rememberSaveable(draft.stepId) { mutableStateOf<String?>(null) }
    var terminalConflict by rememberSaveable(draft.stepId) { mutableStateOf(false) }
    var showDiscard by rememberSaveable(draft.stepId) { mutableStateOf(false) }
    val step = project.steps.firstOrNull { it.id == draft.stepId }
    val selected = draft.hotspots.firstOrNull { it.id == selectedId }
    val pendingForm = draft.pendingForm
    val hasBitmap = bitmap != null && !bitmap.isRecycled
    val canAdd = !busy && hasBitmap && !draft.isTerminal && draft.hotspots.size < ProjectLimits.MAX_HOTSPOTS_PER_STEP
    val goBack: () -> Unit = {
        if (!busy) {
            when {
                adding -> adding = false
                mode != EditorMode.FRAME -> { mode = EditorMode.FRAME; selectedId = null }
                else -> callbacks.onBack()
            }
        }
    }
    BackHandler(enabled = pendingForm == null && draft.conflicts.isEmpty() && !showDiscard) { goBack() }
    val openNew: (OpaqueMask) -> Unit = { rect ->
        if (canAdd) {
            callbacks.onPendingFormChange(ProjectHotspot(UUID.randomUUID().toString(), "", rect, null,
                "演示结束", UUID.randomUUID().toString()).toPendingForm())
            adding = false
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelMaxHeight = (maxHeight * 0.32f).coerceIn(92.dp, 216.dp)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = goBack, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                Column(Modifier.weight(1f).clickable(enabled = !busy, role = Role.Button,
                    onClickLabel = "编辑步骤名称与讲解") {
                    callbacks.onPendingFormChange(EditorPendingForm(EditorFormKind.NAME,
                        title = draft.title, description = draft.description))
                }.padding(vertical = 8.dp)) {
                    Text(draft.title.ifBlank { "未命名步骤" }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val saveStatus = when {
                        draft.conflicts.isNotEmpty() -> "需核对"
                        draft.recoveryStatus == DraftRecoveryStatus.FAILED -> "暂存失败"
                        busy -> "处理中"
                        draft.recoveryStatus == DraftRecoveryStatus.STAGING -> "暂存中"
                        draft.recoveryStatus == DraftRecoveryStatus.STAGED -> "已暂存"
                        draft.dirty || pendingForm != null -> "未保存"
                        else -> "已保存"
                    }
                    Text("步骤 ${project.steps.indexOfFirst { it.id == draft.stepId } + 1} · $saveStatus",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            draft.recoveryStatus == DraftRecoveryStatus.FAILED -> MaterialTheme.colorScheme.error
                            draft.dirty || pendingForm != null -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                }
                if (draft.recoveryStatus == DraftRecoveryStatus.FAILED) {
                    TextButton(onClick = callbacks.onRetryStaging, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
                }
                TextButton(onClick = callbacks.onSave,
                    enabled = !busy && draft.dirty && pendingForm == null && draft.conflicts.isEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("保存") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EditorCanvas(bitmap = bitmap,
                hotspots = if (mode == EditorMode.HOTSPOTS || mode == EditorMode.BRANCHES) draft.hotspots else emptyList(),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                enabled = !busy && mode == EditorMode.HOTSPOTS,
                busy = busy, selectedHotspotId = selectedId, adding = adding && canAdd,
                onSelect = { selectedId = it }, onCreate = openNew,
                onChangeRect = { id, rect -> draft.hotspots.firstOrNull { it.id == id }?.let { callbacks.onPutHotspot(it.copy(rect = rect)) } })
            // The panel is bounded; the canvas receives every remaining pixel, including landscape.
            Column(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (mode) {
                    EditorMode.FRAME -> {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(step?.asset?.let { "${it.width} × ${it.height}" } ?: "图片信息不可用",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { callbacks.onCorrectImage(false) }, enabled = !busy && correctionEnabled) { Text("替换画面") }
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = draft.isTerminal, onCheckedChange = { terminal ->
                                if (terminal && (draft.hotspots.isNotEmpty() || draft.nextAction != null)) terminalConflict = true
                                else { terminalConflict = false; callbacks.onTerminalChange(terminal) }
                            }, enabled = !busy)
                            Text("在这一步结束", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = callbacks.onPreview, enabled = !busy && previewEnabled && hasBitmap) { Text("预览") }
                        }
                        if (terminalConflict && (draft.hotspots.isNotEmpty() || draft.nextAction != null)) {
                            Text("先移除本步动作，再设为终点。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        if (!previewEnabled) Text("保存所有步骤的修改后可预览。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (draft.dirty) TextButton(onClick = { showDiscard = true }, enabled = !busy) { Text("放弃本步修改") }
                    }
                    EditorMode.HOTSPOTS -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("点击区域  ${draft.hotspots.size}/${ProjectLimits.MAX_HOTSPOTS_PER_STEP}", Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { adding = !adding; selectedId = null }, enabled = canAdd) {
                                Text(if (adding) "取消新增" else "+ 新增热点")
                            }
                        }
                        Text(when {
                            draft.isTerminal -> "当前是终点，取消终点标记后可添加动作。"
                            adding -> "在画面拖出矩形，再填写动作和目标。"
                            selected != null -> "拖动选中区域移动，拖右下角调整尺寸。"
                            else -> "点选画面中的区域，或从对象列表选择。"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (draft.hotspots.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            draft.hotspots.forEachIndexed { index, hotspot ->
                                FilterChip(selected = hotspot.id == selectedId, onClick = { selectedId = hotspot.id; adding = false },
                                    enabled = !busy, label = { Text("${index + 1} ${hotspot.label}", maxLines = 1) })
                            }
                        }
                        if (selected != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("→ ${editorTargetLabel(selected, project.steps)}", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { callbacks.onPendingFormChange(selected.toPendingForm()) }, enabled = !busy) { Text("精调 / 动作") }
                            TextButton(onClick = { callbacks.onRemoveHotspot(selected.id); selectedId = null }, enabled = !busy) { Text("移除") }
                        } else TextButton(onClick = { openNew(OpaqueMask(0.25f, 0.3f, 0.75f, 0.5f)) }, enabled = canAdd) {
                            Text("按比例添加（无需拖动）")
                        }
                    }
                    EditorMode.BRANCHES -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("本步的出口", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (draft.nextAction == null) TextButton(onClick = {
                                callbacks.onPendingFormChange(ProjectNextAction(UUID.randomUUID().toString(), "下一步", null).toPendingForm())
                            }, enabled = !busy && !draft.isTerminal) { Text("+ 下一步按钮") }
                        }
                        if (draft.isTerminal) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("当前是终点。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { callbacks.onTerminalChange(false) }, enabled = !busy) { Text("取消终点") }
                        }
                        draft.nextAction?.let { action ->
                            val validTarget = project.steps.any { it.id == action.targetStepId }
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable(enabled = !busy && !draft.isTerminal, role = Role.Button,
                                    onClickLabel = "编辑下一步按钮") { callbacks.onPendingFormChange(action.toPendingForm()) }.padding(vertical = 4.dp)) {
                                    Text(action.label, style = MaterialTheme.typography.bodyMedium)
                                    Text("画布外按钮 · 作者编排", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("→ ${editorNextTargetLabel(action, project.steps)}", style = MaterialTheme.typography.bodySmall,
                                        color = if (validTarget) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                                }
                                TextButton(onClick = { callbacks.onPendingFormChange(action.toPendingForm()) }, enabled = !busy && !draft.isTerminal) {
                                    Text(if (validTarget) "编辑" else "选目标")
                                }
                                TextButton(onClick = { callbacks.onOpenTransition(action.id) }, enabled = !busy && validTarget) {
                                    Text(if (action.transition == null) "静态 ›" else "短片 ›")
                                }
                                TextButton(onClick = callbacks.onRemoveNextAction, enabled = !busy) { Text("移除") }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        if (draft.hotspots.isEmpty() && draft.nextAction == null && !draft.isTerminal) {
                            Text("添加下一步按钮，或在画面上画热点。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        draft.hotspots.forEachIndexed { index, hotspot ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable(enabled = !busy) { callbacks.onPendingFormChange(hotspot.toPendingForm()) }.padding(vertical = 4.dp)) {
                                    Text("${index + 1}  ${hotspot.label}", style = MaterialTheme.typography.bodyMedium)
                                    Text("→ ${editorTargetLabel(hotspot, project.steps)}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { callbacks.onOpenTransition(hotspot.edgeId) },
                                    enabled = !busy && (hotspot.targetStepId != null || hotspot.endLabel != null)) { Text(if (hotspot.transition == null) "静态 ›" else "短片 ›") }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    EditorMode.REDACTIONS -> {
                        Text("固定遮挡", style = MaterialTheme.typography.titleSmall)
                        Text("当前安全画面包含 ${step?.masks?.size ?: 0} 处已生成遮挡。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { callbacks.onCorrectImage(true) }, enabled = !busy && correctionEnabled) {
                            Text("重新遮挡")
                        }
                        Text("从本步原片校正，检查新图片后替换。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    EditorMode.REGIONS -> {
                        Text("可见区域裁片 · ${step?.regions?.size ?: 0}", style = MaterialTheme.typography.titleSmall)
                        Text("从当前安全图框选，生成实际 PNG 后逐张人工复核。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = callbacks.onOpenRegions, enabled = !busy && regionsEnabled && hasBitmap) {
                            Text("编辑区域")
                        }
                        if (!regionsEnabled) Text("先保存或放弃所有步骤修改，再编辑区域。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth()) {
                EditorMode.entries.forEach { item ->
                    val tint = if (mode == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.weight(1f).heightIn(min = 56.dp).selectable(selected = mode == item, enabled = !busy, role = Role.Tab,
                        onClick = { mode = item; adding = false; selectedId = null }).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        EditorModeIcon(item, tint)
                        Text(item.label, style = MaterialTheme.typography.labelLarge, color = tint)
                    }
                }
            }
        }
    }

    // The persisted pending form is the only source of modal inputs, including invalid text.
    // A restored form opens automatically; a conflict must be resolved before applying it.
    if (draft.conflicts.isEmpty()) pendingForm?.let { form ->
        val dismiss = { callbacks.onPendingFormChange(null) }
        when (form.kind) {
            EditorFormKind.NAME -> StepNameSheet(form, !busy, draft.recoveryStatus,
                callbacks.onRetryStaging, callbacks.onPendingFormChange, dismiss) { title, description ->
                callbacks.onTitleChange(title)
                callbacks.onDescriptionChange(description)
                callbacks.onPendingFormChange(null)
            }
            EditorFormKind.HOTSPOT -> HotspotEditorSheet(form,
                draft.hotspots.firstOrNull { it.id == form.objectId }, project.steps, !busy && !draft.isTerminal,
                draft.recoveryStatus, callbacks.onRetryStaging, callbacks.onPendingFormChange, dismiss) {
                callbacks.onPutHotspot(it)
                selectedId = it.id
                callbacks.onPendingFormChange(null)
            }
            EditorFormKind.NEXT_ACTION -> NextActionEditorSheet(form,
                draft.nextAction?.takeIf { it.id == form.objectId }, project.steps, !busy && !draft.isTerminal,
                draft.recoveryStatus, callbacks.onRetryStaging, callbacks.onPendingFormChange, dismiss) {
                callbacks.onPutNextAction(it)
                callbacks.onPendingFormChange(null)
            }
        }
    }
    if (draft.conflicts.isNotEmpty()) DraftConflictDialog(project, draft, !busy, callbacks.onResolveConflict)
    if (showDiscard && draft.conflicts.isEmpty()) AlertDialog(onDismissRequest = { showDiscard = false }, title = { Text("放弃本步修改？") },
        text = { Text("这一步将恢复到最近一次保存的内容。") },
        confirmButton = { TextButton(onClick = { callbacks.onDiscard(); showDiscard = false }, enabled = !busy) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("继续编辑") } })
}

/** Decorative geometry only; the enclosing tab exposes its visible label and selected state. */
@Composable
private fun EditorModeIcon(mode: EditorMode, tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        fun point(x: Float, y: Float) = Offset(size.width * x / 24f, size.height * y / 24f)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, point(x1, y1), point(x2, y2), stroke, StrokeCap.Round)
        when (mode) {
            EditorMode.FRAME -> {
                drawRect(tint, point(3f, 4f), Size(size.width * .75f, size.height * 2f / 3f), style = Stroke(stroke))
                drawCircle(tint, size.width / 12f, point(8f, 9f))
                line(5f, 17f, 10f, 12f)
                line(10f, 12f, 14f, 16f)
                line(14f, 16f, 18f, 11f)
            }
            EditorMode.HOTSPOTS -> {
                drawRect(tint, point(5f, 5f), Size(size.width * 14f / 24f, size.height * 14f / 24f), style = Stroke(stroke))
                listOf(point(5f, 5f), point(19f, 19f)).forEach { corner ->
                    drawRect(tint, corner - Offset(stroke, stroke), Size(stroke * 2f, stroke * 2f))
                }
            }
            EditorMode.BRANCHES -> {
                line(5f, 12f, 10f, 12f)
                line(10f, 5f, 10f, 19f)
                line(10f, 5f, 19f, 5f)
                line(10f, 19f, 19f, 19f)
                listOf(point(4f, 12f), point(20f, 5f), point(20f, 19f)).forEach {
                    drawCircle(tint, size.width * 2f / 24f, it)
                }
            }
            EditorMode.REDACTIONS -> {
                drawRect(tint, point(3f, 4f), Size(size.width * .75f, size.height * 2f / 3f), style = Stroke(stroke))
                drawRect(tint, point(3f, 9f), Size(size.width * .75f, size.height / 4f))
            }
            EditorMode.REGIONS -> {
                line(3f, 8f, 3f, 3f); line(3f, 3f, 8f, 3f)
                line(16f, 3f, 21f, 3f); line(21f, 3f, 21f, 8f)
                line(3f, 16f, 3f, 21f); line(3f, 21f, 8f, 21f)
                line(16f, 21f, 21f, 21f); line(21f, 21f, 21f, 16f)
                drawRect(tint, point(8f, 8f), Size(size.width / 3f, size.height / 3f), style = Stroke(stroke))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepNameSheet(form: EditorPendingForm, enabled: Boolean, recoveryStatus: DraftRecoveryStatus,
    onRetry: () -> Unit, onChange: (EditorPendingForm) -> Unit, onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("名称与讲解", style = MaterialTheme.typography.titleMedium)
            PendingFormStatus(recoveryStatus, enabled, onRetry)
            OutlinedTextField(form.title, { onChange(form.copy(title = it)) }, label = { Text("步骤名称") }, singleLine = true,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(form.description, { onChange(form.copy(description = it)) }, label = { Text("讲解（可选）") }, minLines = 3,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            Text("应用后记得保存步骤。关闭此面板将丢弃未应用的内容。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { onConfirm(form.title.trim(), form.description.trim()) },
                    enabled = enabled && form.title.isNotBlank()) { Text("应用") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HotspotEditorSheet(form: EditorPendingForm, original: ProjectHotspot?, steps: List<ProjectStep>, enabled: Boolean,
    recoveryStatus: DraftRecoveryStatus, onRetry: () -> Unit, onChange: (EditorPendingForm) -> Unit,
    onDismiss: () -> Unit, onConfirm: (ProjectHotspot) -> Unit) {
    // Visibility is presentation-only; every editable value belongs to the pending form.
    var showGeometry by rememberSaveable(form.objectId) { mutableStateOf(false) }
    val rectangle = if (original != null && listOf(form.left, form.top, form.right, form.bottom) ==
        listOf(original.rect.left, original.rect.top, original.rect.right, original.rect.bottom).map(::editorPercent)) {
        original.rect // Editing only the label or target must not round an existing rectangle.
    } else editorPercentageRect(form.left, form.top, form.right, form.bottom)
    val validTarget = if (form.endsDemo) form.endLabel.isNotBlank() else steps.any { it.id == form.targetStepId }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("热点动作", style = MaterialTheme.typography.titleMedium)
            PendingFormStatus(recoveryStatus, enabled, onRetry)
            OutlinedTextField(form.label, { onChange(form.copy(label = it)) }, label = { Text("动作名称") }, singleLine = true,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            Text("点击后前往", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            EditorTargetRow("结束演示", null, form.endsDemo, enabled) { onChange(form.copy(endsDemo = true)) }
            if (form.endsDemo) OutlinedTextField(form.endLabel, { onChange(form.copy(endLabel = it)) }, label = { Text("结束说明") },
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            steps.forEachIndexed { index, step ->
                EditorTargetRow("${index + 1}  ${step.title}", step.source.displayName,
                    !form.endsDemo && form.targetStepId == step.id, enabled) {
                    onChange(form.copy(targetStepId = step.id, endsDemo = false))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = { showGeometry = !showGeometry }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showGeometry) "收起范围精调 ↑" else "范围精调（画面百分比） ↓")
            }
            if (showGeometry || rectangle == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorPercentageField("左边 %", form.left, { onChange(form.copy(left = it)) }, enabled, Modifier.weight(1f))
                    EditorPercentageField("顶部 %", form.top, { onChange(form.copy(top = it)) }, enabled, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorPercentageField("右边 %", form.right, { onChange(form.copy(right = it)) }, enabled, Modifier.weight(1f))
                    EditorPercentageField("底部 %", form.bottom, { onChange(form.copy(bottom = it)) }, enabled, Modifier.weight(1f))
                }
            }
            if (rectangle == null) Text("范围须在 0–100% 内，右边大于左边，底部大于顶部。", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = {
                    val rect = rectangle
                    val objectId = form.objectId
                    val edgeId = form.edgeId
                    if (rect != null && objectId != null && edgeId != null) onConfirm(ProjectHotspot(
                        id = objectId, label = form.label.trim(), rect = rect,
                        targetStepId = if (form.endsDemo) null else form.targetStepId,
                        endLabel = if (form.endsDemo) form.endLabel.trim() else null, edgeId = edgeId,
                        transition = original?.transition,
                    ))
                }, enabled = enabled && form.label.isNotBlank() && rectangle != null && validTarget &&
                    form.objectId != null && form.edgeId != null) { Text("应用动作") }
            }
        }
    }
}

/** This action has no rectangle and remains separate from the canvas hotspot editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NextActionEditorSheet(form: EditorPendingForm, original: ProjectNextAction?, steps: List<ProjectStep>, enabled: Boolean,
    recoveryStatus: DraftRecoveryStatus, onRetry: () -> Unit, onChange: (EditorPendingForm) -> Unit,
    onDismiss: () -> Unit, onConfirm: (ProjectNextAction) -> Unit) {
    val validTarget = steps.any { it.id == form.targetStepId }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("下一步按钮", style = MaterialTheme.typography.titleMedium)
            PendingFormStatus(recoveryStatus, enabled, onRetry)
            Text("画布外按钮 · 作者编排", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(form.label, { onChange(form.copy(label = it)) }, label = { Text("按钮文字") }, singleLine = true,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            Text("点击后前往", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            if (!validTarget) Text(if (form.targetStepId == null) "请选择目标步骤。" else "原目标已失效，请重新选择。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            steps.forEachIndexed { index, step ->
                EditorTargetRow("${index + 1}  ${step.title}", step.source.displayName, form.targetStepId == step.id, enabled) {
                    onChange(form.copy(targetStepId = step.id))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { form.objectId?.let { id -> onConfirm(ProjectNextAction(
                    id, form.label.trim(), form.targetStepId, original?.transition)) } },
                    enabled = enabled && form.objectId != null && form.label.isNotBlank() && validTarget) { Text("应用动作") }
            }
        }
    }
}

@Composable
private fun PendingFormStatus(status: DraftRecoveryStatus, enabled: Boolean, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(when (status) {
            DraftRecoveryStatus.NONE -> "尚未应用到步骤"
            DraftRecoveryStatus.STAGING -> "暂存中 · 尚未应用"
            DraftRecoveryStatus.STAGED -> "已暂存 · 尚未应用"
            DraftRecoveryStatus.FAILED -> "暂存失败 · 退出前请重试"
        }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = if (status == DraftRecoveryStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant)
        if (status == DraftRecoveryStatus.FAILED) TextButton(onClick = onRetry, enabled = enabled) { Text("重试暂存") }
    }
}

private fun ProjectHotspot.toPendingForm() = EditorPendingForm(
    kind = EditorFormKind.HOTSPOT, objectId = id, edgeId = edgeId, label = label,
    left = editorPercent(rect.left), top = editorPercent(rect.top),
    right = editorPercent(rect.right), bottom = editorPercent(rect.bottom),
    targetStepId = targetStepId, endLabel = endLabel ?: "演示结束", endsDemo = endLabel != null,
)

private fun ProjectNextAction.toPendingForm() = EditorPendingForm(
    kind = EditorFormKind.NEXT_ACTION, objectId = id, label = label, targetStepId = targetStepId,
)

@Composable
private fun DraftConflictDialog(project: ProjectSnapshot, draft: StepEditDraft, enabled: Boolean,
    onResolve: (Boolean) -> Unit) {
    val saved = project.steps.firstOrNull { it.id == draft.stepId }
    val pending = draft.pendingForm
    fun hotspotText(hotspots: List<ProjectHotspot>): String = hotspots.joinToString("\n\n") { hotspot ->
        "${hotspot.label.ifBlank { "未命名动作" }} → ${editorTargetLabel(hotspot, project.steps)}\n" +
            "左 ${editorPercent(hotspot.rect.left)}% · 上 ${editorPercent(hotspot.rect.top)}%\n" +
            "右 ${editorPercent(hotspot.rect.right)}% · 下 ${editorPercent(hotspot.rect.bottom)}%"
    }.ifEmpty { "没有热点" }
    fun nextText(action: ProjectNextAction?): String = action?.let {
        "${it.label}\n→ ${editorNextTargetLabel(it, project.steps)}"
    } ?: "没有下一步按钮"
    fun pendingTarget(form: EditorPendingForm): String = if (form.endsDemo) "结束 · ${form.endLabel}" else
        project.steps.firstOrNull { it.id == form.targetStepId }?.title ?: "尚未选择或目标已失效"
    val pendingHotspot = pending?.takeIf { it.kind == EditorFormKind.HOTSPOT }?.let {
        "\n\n面板中待应用：\n${it.label.ifBlank { "未命名动作" }} → ${pendingTarget(it)}\n" +
            "左 ${it.left}% · 上 ${it.top}%\n右 ${it.right}% · 下 ${it.bottom}%"
    }.orEmpty()
    val pendingNext = pending?.takeIf { it.kind == EditorFormKind.NEXT_ACTION }?.let {
        "\n\n面板中待应用：\n${it.label}\n→ ${pendingTarget(it)}"
    }.orEmpty()
    val fields = listOf(
        Triple("标题", saved?.title ?: "步骤已移除",
            if (pending?.kind == EditorFormKind.NAME) "${pending.title}\n（面板中待应用）" else draft.title),
        Triple("讲解", saved?.description.orEmpty().ifBlank { "未填写" },
            if (pending?.kind == EditorFormKind.NAME) "${pending.description.ifBlank { "未填写" }}\n（面板中待应用）"
            else draft.description.ifBlank { "未填写" }),
        Triple("终点", if (saved?.isTerminal == true) "在这一步结束" else "继续演示",
            if (draft.isTerminal) "在这一步结束" else "继续演示"),
        Triple("热点", hotspotText(saved?.hotspots.orEmpty()), hotspotText(draft.hotspots) + pendingHotspot),
        Triple("下一步", nextText(saved?.nextAction), nextText(draft.nextAction) + pendingNext),
    ).filter { it.first in draft.conflicts }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("核对有冲突的修改") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("已保存内容有更新。选择前，两份内容都会保留；其它已合并的修改不受影响。",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("当前已保存", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text("我的本机草稿", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                fields.forEach { (label, formal, local) ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(formal.ifBlank { "未填写" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(local.ifBlank { "未填写" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onResolve(true) }, enabled = enabled) { Text("保留我的修改") } },
        dismissButton = { TextButton(onClick = { onResolve(false) }, enabled = enabled) { Text("使用已保存内容") } },
    )
}

@Composable
private fun EditorPercentageField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean, modifier: Modifier) {
    OutlinedTextField(value, onValue, label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier)
}

@Composable
private fun EditorTargetRow(title: String, subtitle: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected = selected, enabled = enabled,
        role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

internal fun editorTargetLabel(hotspot: ProjectHotspot, steps: List<ProjectStep>): String =
    hotspot.endLabel?.let { "结束 · $it" } ?: steps.indexOfFirst { it.id == hotspot.targetStepId }.let { index ->
        if (index >= 0) "${index + 1} ${steps[index].title}" else "目标已失效"
    }

internal fun editorNextTargetLabel(action: ProjectNextAction, steps: List<ProjectStep>): String =
    steps.indexOfFirst { it.id == action.targetStepId }.let { index ->
        if (index >= 0) "${index + 1} ${steps[index].title}" else "待修复 · 请选择目标"
    }

private fun editorPercent(value: Float): String = (value * 100f).toString().removeSuffix(".0")

private fun editorPercentageRect(left: String, top: String, right: String, bottom: String): OpaqueMask? {
    val values = listOf(left, top, right, bottom).map { it.toFloatOrNull() ?: return null }
    if (values.any { !it.isFinite() || it < 0f || it > 100f }) return null
    val (x1, y1, x2, y2) = values.map { it / 100f }
    if (x2 <= x1 || y2 <= y1) return null
    return OpaqueMask(x1, y1, x2, y2)
}
