package com.tapscene.ui.shell

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.ProjectLimits
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep
import com.tapscene.media.OpaqueMask
import com.tapscene.ui.StepEditDraft
import java.util.Locale
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
) {
    var mode by rememberSaveable(draft.stepId) { mutableStateOf(EditorMode.FRAME) }
    var adding by rememberSaveable(draft.stepId) { mutableStateOf(false) }
    var selectedId by rememberSaveable(draft.stepId) { mutableStateOf<String?>(null) }
    var showName by rememberSaveable(draft.stepId) { mutableStateOf(false) }
    var editingHotspot by remember(draft.stepId) { mutableStateOf<ProjectHotspot?>(null) }
    var showDiscard by rememberSaveable(draft.stepId) { mutableStateOf(false) }
    val step = project.steps.firstOrNull { it.id == draft.stepId }
    val selected = draft.hotspots.firstOrNull { it.id == selectedId }
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
    BackHandler(enabled = !showName && editingHotspot == null && !showDiscard) { goBack() }
    val openNew: (OpaqueMask) -> Unit = { rect ->
        if (canAdd) {
            editingHotspot = ProjectHotspot(UUID.randomUUID().toString(), "", rect, null, "演示结束", UUID.randomUUID().toString())
            adding = false
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelMaxHeight = (maxHeight * 0.32f).coerceIn(92.dp, 216.dp)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = goBack, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                Column(Modifier.weight(1f).clickable(enabled = !busy) { showName = true }.padding(vertical = 8.dp)) {
                    Text(draft.title.ifBlank { "未命名步骤" }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("步骤 ${project.steps.indexOfFirst { it.id == draft.stepId } + 1} · 点名称编辑",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = callbacks.onSave, enabled = !busy && draft.dirty, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (busy) "处理中" else if (draft.dirty) "有未保存修改" else "已保存",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = if (draft.dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (adding) "拖出新的点击区域" else mode.label,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                            Column(Modifier.weight(1f)) {
                                Text("已复核画面", style = MaterialTheme.typography.titleSmall)
                                Text(step?.asset?.let { "${it.width} × ${it.height}" } ?: "图片信息不可用",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {}, enabled = false) { Text("替换画面") }
                        }
                        Text("代表帧替换尚未接入。当前画面和来源保持原样。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = draft.isTerminal, onCheckedChange = callbacks.onTerminalChange,
                                enabled = !busy && (draft.isTerminal || draft.hotspots.isEmpty()))
                            Text("在这一步结束", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = callbacks.onPreview, enabled = !busy && previewEnabled && hasBitmap) { Text("预览") }
                        }
                        if (!previewEnabled) Text("保存所有步骤的修改后可预览。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (draft.hotspots.isNotEmpty()) Text("设为终点前，请先移除本步热点。", style = MaterialTheme.typography.bodySmall,
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
                            TextButton(onClick = { editingHotspot = selected }, enabled = !busy) { Text("精调 / 动作") }
                            TextButton(onClick = { callbacks.onRemoveHotspot(selected.id); selectedId = null }, enabled = !busy) { Text("移除") }
                        } else TextButton(onClick = { openNew(OpaqueMask(0.25f, 0.3f, 0.75f, 0.5f)) }, enabled = canAdd) {
                            Text("按比例添加（无需拖动）")
                        }
                    }
                    EditorMode.BRANCHES -> {
                        Text("本步的出口", style = MaterialTheme.typography.titleSmall)
                        if (draft.hotspots.isEmpty()) Text(if (draft.isTerminal) "此处结束，没有后续分支。" else "还没有出口。先添加一个热点动作。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        draft.hotspots.forEachIndexed { index, hotspot ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable(enabled = !busy) { editingHotspot = hotspot }.padding(vertical = 4.dp)) {
                                    Text("${index + 1}  ${hotspot.label}", style = MaterialTheme.typography.bodyMedium)
                                    Text("→ ${editorTargetLabel(hotspot, project.steps)}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { callbacks.onOpenTransition(hotspot.id) },
                                    enabled = !busy && hotspot.targetStepId != null) { Text("静态 ›") }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Text("现有分支为静态切换。录制过渡尚未接入。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    EditorMode.REDACTIONS -> {
                        Text("固定遮挡", style = MaterialTheme.typography.titleSmall)
                        Text("当前安全画面包含 ${step?.masks?.size ?: 0} 处已生成遮挡。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {}, enabled = false) { Text("画遮挡") }
                            TextButton(onClick = {}, enabled = false) { Text("对象列表") }
                            TextButton(onClick = {}, enabled = false) { Text("重新生成") }
                        }
                        Text("修改既有步骤遮挡、生成和重新复核尚未接入。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    EditorMode.REGIONS -> {
                        Text("可见区域裁片", style = MaterialTheme.typography.titleSmall)
                        Text("为后续动画标记画面中的内容区域。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {}, enabled = false) { Text("框选区域") }
                            TextButton(onClick = {}, enabled = false) { Text("名称与用途") }
                            TextButton(onClick = {}, enabled = false) { Text("导出裁片") }
                        }
                        Text("区域保存、从安全画面裁片和导出尚未接入。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth()) {
                EditorMode.entries.forEach { item ->
                    Column(Modifier.weight(1f).heightIn(min = 56.dp).selectable(selected = mode == item, enabled = !busy, role = Role.Tab,
                        onClick = { mode = item; adding = false; selectedId = null }).padding(top = 8.dp, bottom = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.width(20.dp).height(2.dp).background(
                            if (mode == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background))
                        Text(item.label, style = MaterialTheme.typography.labelLarge,
                            color = if (mode == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showName) StepNameSheet(draft, !busy, onDismiss = { showName = false }, onConfirm = { title, description ->
        callbacks.onTitleChange(title); callbacks.onDescriptionChange(description); showName = false
    })
    editingHotspot?.let { hotspot ->
        HotspotEditorSheet(hotspot, project.steps, !busy, onDismiss = { editingHotspot = null }, onConfirm = {
            callbacks.onPutHotspot(it); selectedId = it.id; editingHotspot = null
        })
    }
    if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false }, title = { Text("放弃本步修改？") },
        text = { Text("这一步将恢复到最近一次保存的内容。") },
        confirmButton = { TextButton(onClick = { callbacks.onDiscard(); showDiscard = false }, enabled = !busy) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("继续编辑") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepNameSheet(draft: StepEditDraft, enabled: Boolean, onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit) {
    var title by rememberSaveable(draft.stepId) { mutableStateOf(draft.title) }
    var description by rememberSaveable(draft.stepId) { mutableStateOf(draft.description) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("名称与讲解", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(title, { title = it }, label = { Text("步骤名称") }, singleLine = true,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("讲解（可选）") }, minLines = 3,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            Text("应用后记得保存步骤。关闭此面板将丢弃未应用的内容。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { onConfirm(title.trim(), description.trim()) }, enabled = enabled && title.isNotBlank()) { Text("应用") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HotspotEditorSheet(hotspot: ProjectHotspot, steps: List<ProjectStep>, enabled: Boolean,
    onDismiss: () -> Unit, onConfirm: (ProjectHotspot) -> Unit) {
    var label by rememberSaveable(hotspot.id) { mutableStateOf(hotspot.label) }
    var targetId by rememberSaveable(hotspot.id) { mutableStateOf(hotspot.targetStepId) }
    var ends by rememberSaveable(hotspot.id) { mutableStateOf(hotspot.endLabel != null) }
    var endLabel by rememberSaveable(hotspot.id) { mutableStateOf(hotspot.endLabel ?: "演示结束") }
    var showGeometry by rememberSaveable(hotspot.id) { mutableStateOf(false) }
    var geometryChanged by rememberSaveable(hotspot.id) { mutableStateOf(false) }
    var left by rememberSaveable(hotspot.id) { mutableStateOf(editorPercent(hotspot.rect.left)) }
    var top by rememberSaveable(hotspot.id) { mutableStateOf(editorPercent(hotspot.rect.top)) }
    var width by rememberSaveable(hotspot.id) { mutableStateOf(editorPercent(hotspot.rect.right - hotspot.rect.left)) }
    var height by rememberSaveable(hotspot.id) { mutableStateOf(editorPercent(hotspot.rect.bottom - hotspot.rect.top)) }
    val rectangle = if (geometryChanged) editorPercentageRect(left, top, width, height) else hotspot.rect
    val validTarget = if (ends) endLabel.isNotBlank() else steps.any { it.id == targetId }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("热点动作", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(label, { label = it }, label = { Text("动作名称") }, singleLine = true,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            Text("点击后前往", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            EditorTargetRow("结束演示", null, ends, enabled) { ends = true }
            if (ends) OutlinedTextField(endLabel, { endLabel = it }, label = { Text("结束说明") },
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            steps.forEachIndexed { index, step ->
                EditorTargetRow("${index + 1}  ${step.title}", step.source.displayName, !ends && targetId == step.id, enabled) {
                    targetId = step.id; ends = false
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = { showGeometry = !showGeometry }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showGeometry) "收起范围精调 ↑" else "范围精调（画面百分比） ↓")
            }
            if (showGeometry) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorPercentageField("左边 %", left, { left = it; geometryChanged = true }, enabled, Modifier.weight(1f))
                    EditorPercentageField("顶部 %", top, { top = it; geometryChanged = true }, enabled, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorPercentageField("宽度 %", width, { width = it; geometryChanged = true }, enabled, Modifier.weight(1f))
                    EditorPercentageField("高度 %", height, { height = it; geometryChanged = true }, enabled, Modifier.weight(1f))
                }
            }
            if (rectangle == null) Text("范围须在画面内，宽高须大于 0。", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { rectangle?.let { rect -> onConfirm(hotspot.copy(label = label.trim(), rect = rect,
                    targetStepId = if (ends) null else targetId, endLabel = if (ends) endLabel.trim() else null)) } },
                    enabled = enabled && label.isNotBlank() && rectangle != null && validTarget) { Text("应用动作") }
            }
        }
    }
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

/** Page 06 is an honest capability scaffold: no transition persistence or generation is claimed. */
@Composable
fun TransitionWorkspaceContent(project: ProjectSnapshot, stepId: String, hotspotId: String?, bitmap: Bitmap?, busy: Boolean,
    onBack: () -> Unit, modifier: Modifier = Modifier, draft: StepEditDraft? = null) {
    val step = project.steps.firstOrNull { it.id == stepId }
    val currentDraft = draft?.takeIf { it.stepId == stepId }
    val hotspot = (currentDraft?.hotspots ?: step?.hotspots)?.firstOrNull { it.id == hotspotId }
    BackHandler { if (!busy) onBack() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelMaxHeight = (maxHeight * 0.46f).coerceIn(100.dp, 310.dp)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ShellTopBar("录制过渡", onBack = { if (!busy) onBack() })
            Text("${currentDraft?.title ?: step?.title ?: "当前步骤"} → ${hotspot?.let { editorTargetLabel(it, project.steps) } ?: "目标步骤"}",
                style = MaterialTheme.typography.titleSmall, maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(16.dp))
            EditorCanvas(bitmap, emptyList(), Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                enabled = false, busy = busy)
            Column(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("当前为静态切换", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = {}, enabled = false) { Text("选择已有片段") }
                }
                Text("尚未绑定过渡片段", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = 0f, onValueChange = {}, enabled = false, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("起点 —  /  终点 —", style = MaterialTheme.typography.labelSmall)
                    Text("单段 —  ·  累计 —", style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {}, enabled = false) { Text("播放 / 重放") }
                    TextButton(onClick = {}, enabled = false) { Text("逐帧") }
                    TextButton(onClick = {}, enabled = false) { Text("固定遮挡") }
                }
                Text("过渡绑定、裁剪和整段安全生成尚未接入。后续需完整复核视频。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.weight(1f)) { Text("返回编辑") }
                    Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("保存过渡") }
                }
            }
        }
    }
}

internal fun editorTargetLabel(hotspot: ProjectHotspot, steps: List<ProjectStep>): String =
    hotspot.endLabel?.let { "结束 · $it" } ?: steps.indexOfFirst { it.id == hotspot.targetStepId }.let { index ->
        if (index >= 0) "${index + 1} ${steps[index].title}" else "目标已失效"
    }

private fun editorPercent(value: Float): String = String.format(Locale.ROOT, "%.2f", value * 100f).trimEnd('0').trimEnd('.')

private fun editorPercentageRect(left: String, top: String, width: String, height: String): OpaqueMask? {
    val values = listOf(left, top, width, height).map { it.toFloatOrNull() ?: return null }
    if (values.any { !it.isFinite() }) return null
    val (x, y, w, h) = values.map { it / 100f }
    if (x < 0 || y < 0 || x >= 1 || y >= 1 || w <= 0 || h <= 0 || x + w <= x || y + h <= y ||
        x + w > 1.000001f || y + h > 1.000001f) return null
    return OpaqueMask(x, y, (x + w).coerceAtMost(1f), (y + h).coerceAtMost(1f))
}
