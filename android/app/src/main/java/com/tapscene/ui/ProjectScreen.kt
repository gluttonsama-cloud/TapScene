package com.tapscene.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.ProjectLimits
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep
import com.tapscene.data.ProjectSummary
import com.tapscene.data.ReviewedStepInput
import com.tapscene.media.OpaqueMask
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val SceneGreen = Color(0xFF356B58)
private val SceneBackground = Color(0xFFF5F7F4)

/** Project screens only show the reviewed, privately copied PNG associated with a step. */
@Composable
fun ProjectScreen(projects: ProjectWorkspace, media: MediaWorkspace) {
    val state by projects.state.collectAsStateWithLifecycle()
    var newProject by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deletingProject by remember { mutableStateOf<ProjectSummary?>(null) }
    var deletingStep by remember { mutableStateOf<ProjectStep?>(null) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var hotspotEditor by remember { mutableStateOf<ProjectHotspot?>(null) }
    var mediaError by remember { mutableStateOf<String?>(null) }
    var showLegacyMedia by rememberSaveable { mutableStateOf(false) }

    val goBack: () -> Unit = {
        if (!state.busy) {
            if (state.route == ProjectRoute.EDIT && state.stepDraft?.dirty == true) confirmLeave = true
            else projects.back()
        }
    }
    BackHandler(enabled = state.route != ProjectRoute.PROJECTS && state.route != ProjectRoute.MEDIA) { goBack() }

    if (showLegacyMedia) {
        MediaScreen(workspace = media, onBack = { showLegacyMedia = false })
        return
    }
    val screenScroll = key(state.route, state.selectedStepId) { rememberScrollState() }

    MaterialTheme(colorScheme = lightColorScheme(primary = SceneGreen, background = SceneBackground,
        surface = Color.White, surfaceContainer = Color(0xFFEEF2EC))) {
        if (state.route == ProjectRoute.MEDIA) {
            ProjectMediaRoute(projects, media, state)
        } else {
            Scaffold(containerColor = SceneBackground) { insets ->
                Column(Modifier.fillMaxSize().padding(insets).verticalScroll(screenScroll).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (state.route != ProjectRoute.PROJECTS) {
                            TextButton(onClick = goBack, enabled = !state.busy,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.route == ProjectRoute.PREVIEW) "退出预览" else "返回") }
                        }
                        Text(when (state.route) {
                            ProjectRoute.PROJECTS -> "TapScene"
                            ProjectRoute.STEPS -> state.project?.project?.title.orEmpty()
                            ProjectRoute.EDIT -> "编辑步骤"
                            ProjectRoute.PREVIEW -> "草稿预览"
                            ProjectRoute.MEDIA -> "添加步骤"
                        }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(state.stage ?: "正在保存…", style = MaterialTheme.typography.bodySmall)
                    }
                    (mediaError ?: state.message)?.let { message ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0E9))) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { mediaError = null; projects.clearMessage() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
                            }
                        }
                    }
                    if (state.loadFailed) {
                        Text("项目暂时无法读取，已有内容仍保留在本机。")
                        OutlinedButton(onClick = projects::reload, enabled = !state.busy,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取") }
                    }
                    when (state.route) {
                        ProjectRoute.PROJECTS -> ProjectList(state,
                            onCreate = { newProject = true }, onOpen = projects::openProject,
                            onRename = { renaming = it }, onDelete = { deletingProject = it },
                            onLegacyMedia = {
                                if (media.activateProject(null)) { mediaError = null; showLegacyMedia = true }
                                else mediaError = media.state.value.message ?: "素材仍在处理中，请稍后再试。"
                            })
                        ProjectRoute.STEPS -> state.project?.let { project ->
                            StepList(project, state, projects,
                                onRename = { renaming = project.project }, onDelete = { deletingStep = it },
                                onOpenMedia = {
                                    if (media.activateProject(project.project.id)) { mediaError = null; projects.openMedia() }
                                    else mediaError = media.state.value.message ?: "素材仍在处理中，请稍后再试。"
                                })
                        }
                        ProjectRoute.EDIT -> state.project?.let { project ->
                            state.stepDraft?.let { draft ->
                                StepEditor(project, draft, state, projects,
                                    onEditHotspot = { hotspotEditor = it },
                                    onNewHotspot = { rect -> hotspotEditor = newHotspot(rect) })
                            }
                        }
                        ProjectRoute.PREVIEW -> PreviewContent(state, projects)
                        ProjectRoute.MEDIA -> Unit
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        if (newProject) ProjectNameDialog(title = "新建项目", showGoal = true,
            initialTitle = "", initialGoal = "", enabled = !state.busy,
            onDismiss = { newProject = false }, onConfirm = { title, goal ->
                newProject = false
                projects.createProject(title, goal)
            })
        renaming?.let { item ->
            ProjectNameDialog(title = "重命名项目", initialTitle = item.title, initialGoal = item.goal,
                showGoal = false, enabled = !state.busy, onDismiss = { renaming = null },
                onConfirm = { title, _ -> renaming = null; projects.renameProject(item.id, title) })
        }
        deletingProject?.let { item ->
            AlertDialog(onDismissRequest = { deletingProject = null },
                title = { Text("删除“${item.title}”？") },
                text = { Text("本机的项目、${item.stepCount} 个步骤、热点和项目图片将被删除，无法撤销。共享的原录屏和已经导出的文件会保留。") },
                confirmButton = { TextButton(onClick = { deletingProject = null; projects.deleteProject(item.id) },
                    enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除项目") } },
                dismissButton = { TextButton(onClick = { deletingProject = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
        }
        deletingStep?.let { step ->
            val affected = projects.deletionHotspotCount(step.id)
            val isStart = state.project?.project?.startStepId == step.id
            AlertDialog(onDismissRequest = { deletingStep = null }, title = { Text("删除“${step.title}”？") },
                text = { Text(buildString {
                    append("这一步和它的项目图片将被删除，无法撤销。共影响 $affected 个关联热点，包含尚未保存的热点。")
                    if (isStart) append("\n它是当前起点，删除后需要重新设置起点。")
                    append("\n这一步的未保存编辑，以及其他未保存草稿中指向它的热点也会移除。原录屏会保留。")
                }) },
                confirmButton = { TextButton(onClick = { deletingStep = null; projects.deleteStep(step.id) },
                    enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除步骤") } },
                dismissButton = { TextButton(onClick = { deletingStep = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
        }
        if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("步骤有未保存修改") },
            text = { Text("可以继续编辑，或先返回并保留本次修改。保留的修改尚未写入本机，关闭应用后可能丢失。") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; projects.back() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保留修改并返回") } },
            dismissButton = {
                Column {
                    TextButton(onClick = { confirmLeave = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续编辑") }
                    TextButton(onClick = { confirmLeave = false; projects.discardStepDraft(); projects.back() },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("丢弃修改并返回") }
                }
            })
        hotspotEditor?.let { hotspot ->
            state.project?.let { project ->
                HotspotDialog(hotspot, project.steps, enabled = !state.busy,
                    onDismiss = { hotspotEditor = null },
                    onSave = { updated -> projects.putHotspot(updated); hotspotEditor = null })
            }
        }
        val matching = state.preview?.matchingHotspotIds.orEmpty()
        if (state.route == ProjectRoute.PREVIEW && matching.size > 1) {
            val step = state.project?.steps?.firstOrNull { it.id == state.preview?.currentStepId }
            AlertDialog(onDismissRequest = projects::dismissPreviewChoices,
                title = { Text("这里有多个动作") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    step?.hotspots?.filter { it.id in matching }?.forEach { hotspot ->
                        OutlinedButton(onClick = { projects.chooseHotspot(hotspot.id) }, enabled = !state.busy && state.bitmap != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(hotspot.label) }
                    }
                } },
                confirmButton = { TextButton(onClick = projects::dismissPreviewChoices,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
        }
    }
}

@Composable
private fun ProjectList(state: ProjectUiState, onCreate: () -> Unit, onOpen: (String) -> Unit,
    onRename: (ProjectSummary) -> Unit, onDelete: (ProjectSummary) -> Unit, onLegacyMedia: () -> Unit) {
    Text("我的项目", style = MaterialTheme.typography.headlineMedium)
    Button(onClick = onCreate, enabled = !state.busy && !state.loadFailed,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("新建项目") }
    TextButton(onClick = onLegacyMedia, enabled = !state.busy,
        modifier = Modifier.heightIn(min = 48.dp)) { Text("原素材工作台") }
    if (state.projects.isEmpty() && !state.busy && !state.loadFailed) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("还没有项目", style = MaterialTheme.typography.titleMedium)
                Text("给演示起个名字，再从录屏中加入第一步。", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    state.projects.forEach { project ->
        key(project.id) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().clickable(enabled = !state.busy && !state.loadFailed) { onOpen(project.id) }.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.title, style = MaterialTheme.typography.titleLarge)
                    if (project.goal.isNotBlank()) Text(project.goal, style = MaterialTheme.typography.bodyMedium)
                    Text("${project.stepCount} 个步骤", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onRename(project) }, enabled = !state.busy && !state.loadFailed,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("重命名") }
                    TextButton(onClick = { onDelete(project) }, enabled = !state.busy && !state.loadFailed,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun StepList(project: ProjectSnapshot, state: ProjectUiState, workspace: ProjectWorkspace,
    onRename: () -> Unit, onDelete: (ProjectStep) -> Unit, onOpenMedia: () -> Unit) {
    if (project.project.goal.isNotBlank()) Text(project.project.goal, style = MaterialTheme.typography.bodyLarge)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${project.steps.size} 个步骤", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onRename, enabled = !state.busy && !state.loadFailed, modifier = Modifier.heightIn(min = 48.dp)) { Text("重命名") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onOpenMedia, enabled = !state.busy && !state.loadFailed && project.steps.size < ProjectLimits.MAX_STEPS,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("添加步骤") }
        OutlinedButton(onClick = { workspace.startPreview(false) }, enabled = !state.busy && !state.loadFailed && project.project.startStepId != null && state.dirtyStepIds.isEmpty(),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("从起点预览") }
    }
    if (project.steps.size >= ProjectLimits.MAX_STEPS) Text("已达到 40 个步骤。", style = MaterialTheme.typography.bodySmall)
    if (state.dirtyStepIds.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1D8))) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("这些步骤还有未保存修改", style = MaterialTheme.typography.titleSmall)
                Text("保存或丢弃后可预览。", style = MaterialTheme.typography.bodySmall)
                project.steps.filter { it.id in state.dirtyStepIds }.forEach { step ->
                    TextButton(onClick = { workspace.openStep(step.id) }, enabled = !state.busy && !state.loadFailed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(step.title, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
    if (state.issues.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1D8))) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("待完善", style = MaterialTheme.typography.titleSmall)
                state.issues.forEach { issue ->
                    if (issue.stepId != null) TextButton(onClick = { workspace.openStep(issue.stepId) }, enabled = !state.busy && !state.loadFailed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(issue.message, Modifier.fillMaxWidth()) }
                    else {
                        Text(issue.message, style = MaterialTheme.typography.bodyMedium)
                        if (project.project.startStepId == null && project.steps.isNotEmpty()) Text(
                            "在下方步骤卡点“设为起点”。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (project.steps.isEmpty()) Text("还没有步骤，先从录屏加入一张已复核画面。")
    project.steps.forEachIndexed { index, step ->
        key(step.id) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().clickable(enabled = !state.busy && !state.loadFailed) { workspace.openStep(step.id) }.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(48.dp).background(Color(0xFFE7EEE6), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text(String.format(Locale.ROOT, "%02d", index + 1), fontWeight = FontWeight.Bold, color = SceneGreen)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(step.title, style = MaterialTheme.typography.titleMedium)
                            val marks = buildList {
                                if (step.id == project.project.startStepId) add("起点")
                                if (step.isTerminal) add("终点")
                                if (step.id in state.dirtyStepIds) add("有未保存修改")
                            }
                            if (marks.isNotEmpty()) Text(marks.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = SceneGreen)
                        }
                        Text("编辑", color = SceneGreen, style = MaterialTheme.typography.labelLarge)
                    }
                    if (step.description.isNotBlank()) Text(step.description, style = MaterialTheme.typography.bodyMedium)
                    Text(if (step.hotspots.isEmpty()) (if (step.isTerminal) "此处结束" else "还没有热点动作")
                        else step.hotspots.joinToString("\n") { "${it.label} → ${targetLabel(it, project.steps)}" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = SceneBackground)
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { workspace.setStart(step.id) }, enabled = !state.busy && !state.loadFailed && step.id != project.project.startStepId,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("设为起点") }
                    TextButton(onClick = { workspace.moveStep(step.id, -1) }, enabled = !state.busy && !state.loadFailed && index > 0,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("上移") }
                    TextButton(onClick = { workspace.moveStep(step.id, 1) }, enabled = !state.busy && !state.loadFailed && index < project.steps.lastIndex,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("下移") }
                    TextButton(onClick = { onDelete(step) }, enabled = !state.busy && !state.loadFailed,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun StepEditor(project: ProjectSnapshot, draft: StepEditDraft, state: ProjectUiState, workspace: ProjectWorkspace,
    onEditHotspot: (ProjectHotspot) -> Unit, onNewHotspot: (OpaqueMask) -> Unit) {
    OutlinedTextField(value = draft.title, onValueChange = workspace::editTitle, label = { Text("步骤标题") },
        enabled = !state.busy && !state.loadFailed, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = draft.description, onValueChange = workspace::editDescription, label = { Text("讲解（可选）") },
        enabled = !state.busy && !state.loadFailed, minLines = 2, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (draft.dirty) "有未保存修改" else "已保存", Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge, color = if (draft.dirty) Color(0xFF8D5D13) else SceneGreen)
        Button(onClick = workspace::saveStepDraft, enabled = !state.busy && !state.loadFailed && draft.dirty,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("保存修改") }
    }
    state.bitmap?.let { bitmap ->
        Text("步骤画面", style = MaterialTheme.typography.titleMedium)
        SafeStepCanvas(bitmap, draft.hotspots, enabled = !state.busy && !state.loadFailed && !draft.isTerminal && draft.hotspots.size < ProjectLimits.MAX_HOTSPOTS_PER_STEP,
            onDraw = onNewHotspot)
        if (!draft.isTerminal) Text("在画面拖出热点，或按比例添加。", style = MaterialTheme.typography.bodySmall)
    } ?: run {
        if (!state.busy && !state.loadFailed) Text("步骤图片暂时无法读取。请返回后重新打开这一步。", color = MaterialTheme.colorScheme.error)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = draft.isTerminal, onCheckedChange = workspace::editTerminal,
            enabled = !state.busy && !state.loadFailed && (draft.isTerminal || draft.hotspots.isEmpty()))
        Column(Modifier.weight(1f)) {
            Text("这一步是终点")
            if (draft.hotspots.isNotEmpty()) Text("先移除已有热点，才能设为终点。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (!draft.isTerminal) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("热点动作 · ${draft.hotspots.size}/6", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { onNewHotspot(OpaqueMask(0.25f, 0.25f, 0.75f, 0.5f)) },
                enabled = !state.busy && !state.loadFailed && state.bitmap != null && draft.hotspots.size < ProjectLimits.MAX_HOTSPOTS_PER_STEP,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("按比例添加") }
        }
        if (draft.hotspots.isEmpty()) Text("为一个可点击区域起名，再选择目标步骤或结束。", style = MaterialTheme.typography.bodyMedium)
    }
    draft.hotspots.forEachIndexed { index, hotspot ->
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${index + 1}. ${hotspot.label}", style = MaterialTheme.typography.titleSmall)
                Text("→ ${targetLabel(hotspot, project.steps)}", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onEditHotspot(hotspot) }, enabled = !state.busy && !state.loadFailed,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("编辑范围与动作") }
                    TextButton(onClick = { workspace.removeHotspot(hotspot.id) }, enabled = !state.busy && !state.loadFailed,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("移除") }
                }
            }
        }
    }
    HorizontalDivider()
    OutlinedButton(onClick = { workspace.startPreview(true) }, enabled = !state.busy && !state.loadFailed && state.dirtyStepIds.isEmpty() && state.bitmap != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("从这一步预览") }
    if (state.dirtyStepIds.isNotEmpty()) Text(
        if (draft.dirty) "保存修改后可预览。" else "其他步骤还有未保存修改，请返回处理后再预览。",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PreviewContent(state: ProjectUiState, workspace: ProjectWorkspace) {
    val preview = state.preview ?: return
    val project = state.project ?: return
    val step = project.steps.firstOrNull { it.id == preview.currentStepId } ?: return
    if (preview.ended) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("演示结束", style = MaterialTheme.typography.headlineMedium)
                Text(preview.endLabel ?: step.title, style = MaterialTheme.typography.bodyLarge)
            }
        }
        state.bitmap?.let { bitmap -> SafeStepCanvas(bitmap, emptyList(), enabled = false) }
    } else {
        Text(step.title, style = MaterialTheme.typography.headlineSmall)
        if (step.description.isNotBlank()) Text(step.description, style = MaterialTheme.typography.bodyLarge)
        state.bitmap?.let { bitmap ->
            SafeStepCanvas(bitmap, step.hotspots, enabled = !state.busy, onTap = workspace::tapPreview)
        } ?: run {
            if (!state.busy) Text("步骤图片无法读取，当前不能点击画面。", color = MaterialTheme.colorScheme.error)
        }
        if (step.isTerminal) Text("已到达终点", style = MaterialTheme.typography.titleMedium)
        else if (step.hotspots.isEmpty()) Text("这一步还没有动作，可以回到编辑添加。")
        else {
            Text("选择动作", style = MaterialTheme.typography.titleMedium)
            step.hotspots.forEach { hotspot ->
                OutlinedButton(onClick = { workspace.chooseHotspot(hotspot.id) }, enabled = !state.busy && state.bitmap != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(hotspot.label) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = workspace::previousPreview, enabled = !state.busy && preview.canGoBack,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上一步") }
        OutlinedButton(onClick = workspace::restartPreview, enabled = !state.busy,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("重来") }
    }
    TextButton(onClick = workspace::exitPreview, enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("回到这一步编辑") }
}

/** The gesture surface and Image have identical bounds and aspect ratio; padding is outside it. */
@Composable
private fun SafeStepCanvas(bitmap: Bitmap, hotspots: List<ProjectHotspot>, enabled: Boolean,
    onDraw: ((OpaqueMask) -> Unit)? = null, onTap: ((Float, Float) -> Unit)? = null) {
    var start by remember(bitmap) { mutableStateOf<Offset?>(null) }
    var end by remember(bitmap) { mutableStateOf<Offset?>(null) }
    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val contentWidth = minOf(maxWidth, 420.dp, (380f * ratio).dp)
        Box(Modifier.width(contentWidth).aspectRatio(ratio)) {
            Image(image, contentDescription = "已复核的步骤画面", contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize())
            val gestures = if (onDraw != null) Modifier.pointerInput(bitmap, enabled, hotspots.size) {
                if (enabled) detectDragGestures(
                    onDragStart = { start = it; end = it },
                    onDragCancel = { start = null; end = null },
                    onDragEnd = {
                        val from = start
                        val to = end
                        if (from != null && to != null && size.width > 0 && size.height > 0 &&
                            abs(from.x - to.x) >= 4 && abs(from.y - to.y) >= 4) {
                            val left = (min(from.x, to.x) / size.width).coerceIn(0f, 1f)
                            val top = (min(from.y, to.y) / size.height).coerceIn(0f, 1f)
                            val right = (max(from.x, to.x) / size.width).coerceIn(0f, 1f)
                            val bottom = (max(from.y, to.y) / size.height).coerceIn(0f, 1f)
                            if (right > left && bottom > top) onDraw(OpaqueMask(left, top, right, bottom))
                        }
                        start = null
                        end = null
                    },
                    onDrag = { change, _ -> change.consume(); end = change.position },
                )
            } else Modifier.pointerInput(bitmap, enabled) {
                if (enabled && onTap != null) detectTapGestures { offset ->
                    if (size.width > 0 && size.height > 0 && offset.x >= 0 && offset.y >= 0 &&
                        offset.x <= size.width && offset.y <= size.height) onTap(offset.x / size.width, offset.y / size.height)
                }
            }
            Canvas(Modifier.fillMaxSize().then(gestures).semantics {
                contentDescription = when {
                    !enabled -> "已复核的步骤画面"
                    onDraw != null -> "拖动画面添加热点；也可使用下方按比例添加按钮"
                    else -> "点击画面中的热点；也可使用下方文字动作"
                }
            }) {
                hotspots.forEach { hotspot ->
                    val rect = hotspot.rect
                    val position = Offset(rect.left * size.width, rect.top * size.height)
                    val bounds = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height)
                    drawRect(SceneGreen.copy(alpha = if (onDraw != null) 0.18f else 0.10f), position, bounds)
                    drawRect(SceneGreen, position, bounds, style = Stroke(2.dp.toPx()))
                }
                val from = start
                val to = end
                if (from != null && to != null) {
                    val position = Offset(min(from.x, to.x).coerceIn(0f, size.width), min(from.y, to.y).coerceIn(0f, size.height))
                    val right = max(from.x, to.x).coerceIn(0f, size.width)
                    val bottom = max(from.y, to.y).coerceIn(0f, size.height)
                    val bounds = Size((right - position.x).coerceAtLeast(0f), (bottom - position.y).coerceAtLeast(0f))
                    drawRect(SceneGreen.copy(alpha = 0.25f), position, bounds)
                    drawRect(SceneGreen, position, bounds, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun ProjectNameDialog(title: String, initialTitle: String, initialGoal: String, showGoal: Boolean,
    enabled: Boolean, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    var goal by rememberSaveable(initialGoal) { mutableStateOf(initialGoal) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("项目名称") },
                singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            if (showGoal) OutlinedTextField(value = goal, onValueChange = { goal = it }, label = { Text("演示目标（可选）") },
                enabled = enabled, minLines = 2, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim(), goal.trim()) }, enabled = enabled && name.isNotBlank(),
            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showGoal) "创建" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun HotspotDialog(hotspot: ProjectHotspot, steps: List<ProjectStep>, enabled: Boolean,
    onDismiss: () -> Unit, onSave: (ProjectHotspot) -> Unit) {
    var label by rememberSaveable(hotspot.id) { mutableStateOf(hotspot.label) }
    var targetId by rememberSaveable(hotspot.id) { mutableStateOf(hotspot.targetStepId) }
    var ends by rememberSaveable(hotspot.id) { mutableStateOf(hotspot.endLabel != null) }
    var endLabel by rememberSaveable(hotspot.id) { mutableStateOf(hotspot.endLabel ?: "演示结束") }
    var left by rememberSaveable(hotspot.id) { mutableStateOf(percent(hotspot.rect.left)) }
    var top by rememberSaveable(hotspot.id) { mutableStateOf(percent(hotspot.rect.top)) }
    var width by rememberSaveable(hotspot.id) { mutableStateOf(percent(hotspot.rect.right - hotspot.rect.left)) }
    var height by rememberSaveable(hotspot.id) { mutableStateOf(percent(hotspot.rect.bottom - hotspot.rect.top)) }
    val rectangle = percentageRect(left, top, width, height)
    val validTarget = if (ends) endLabel.isNotBlank() else steps.any { it.id == targetId }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("热点动作") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("动作标签") }, enabled = enabled,
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("范围（画面百分比）", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PercentageField("左边 %", left, { left = it }, enabled, Modifier.weight(1f))
                PercentageField("顶部 %", top, { top = it }, enabled, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PercentageField("宽度 %", width, { width = it }, enabled, Modifier.weight(1f))
                PercentageField("高度 %", height, { height = it }, enabled, Modifier.weight(1f))
            }
            if (rectangle == null) Text("范围须在画面内，宽高须大于 0。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text("点击后", style = MaterialTheme.typography.titleSmall)
            ActionRadioRow("结束演示", ends, enabled) { ends = true }
            if (ends) OutlinedTextField(value = endLabel, onValueChange = { endLabel = it }, label = { Text("结束说明") },
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            steps.forEachIndexed { index, step ->
                ActionRadioRow("${index + 1}. ${step.title}", !ends && targetId == step.id, enabled) {
                    ends = false
                    targetId = step.id
                }
            }
        } },
        confirmButton = { TextButton(onClick = {
            val rect = rectangle ?: return@TextButton
            onSave(hotspot.copy(label = label.trim(), rect = rect,
                targetStepId = if (ends) null else targetId, endLabel = if (ends) endLabel.trim() else null))
        }, enabled = enabled && label.isNotBlank() && rectangle != null && validTarget,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("确认动作") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun PercentageField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean, modifier: Modifier) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = enabled, modifier = modifier)
}

@Composable
private fun ActionRadioRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
        selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 8.dp))
    }
}

private fun newHotspot(rect: OpaqueMask) = ProjectHotspot(id = UUID.randomUUID().toString(), label = "",
    rect = rect, targetStepId = null, endLabel = "演示结束", edgeId = UUID.randomUUID().toString())

private fun targetLabel(hotspot: ProjectHotspot, steps: List<ProjectStep>): String =
    hotspot.endLabel?.let { "结束 · $it" } ?: steps.firstOrNull { it.id == hotspot.targetStepId }?.title ?: "目标已失效"

private fun percent(value: Float) = String.format(Locale.ROOT, "%.2f", value * 100f).trimEnd('0').trimEnd('.')

private fun percentageRect(left: String, top: String, width: String, height: String): OpaqueMask? {
    val values = listOf(left, top, width, height).map { it.toFloatOrNull() ?: return null }
    if (values.any { !it.isFinite() }) return null
    val (x, y, w, h) = values.map { it / 100f }
    if (x < 0 || y < 0 || x >= 1 || y >= 1 || w <= 0 || h <= 0 || x + w <= x || y + h <= y ||
        x + w > 1.000001f || y + h > 1.000001f) return null
    return OpaqueMask(x, y, (x + w).coerceAtMost(1f), (y + h).coerceAtMost(1f))
}

// The media workspace revalidates reviewed bytes under its operation lock before this callback.
@Composable
private fun ProjectMediaRoute(projects: ProjectWorkspace, media: MediaWorkspace, state: ProjectUiState) {
    val projectId = remember { state.project?.project?.id }
    val onReviewedImage: (suspend (ReviewedStepInput) -> Unit)? = if (projectId == null) null else {
        { input -> projects.saveReviewedStep(projectId, input); Unit }
    }
    MediaScreen(workspace = media, onBack = projects::back, onReviewedImage = onReviewedImage)
}
