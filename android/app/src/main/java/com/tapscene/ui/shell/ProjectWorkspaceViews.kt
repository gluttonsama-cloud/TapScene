package com.tapscene.ui.shell

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep
import com.tapscene.data.ProjectSummary
import com.tapscene.data.SourceDraft
import com.tapscene.data.StepAsset
import com.tapscene.ui.ProjectUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Content only: the host owns navigation, creation, confirmations, and persistent state. */
@Composable
fun ProjectHomeContent(
    state: ProjectUiState,
    onOpenProject: (String) -> Unit,
    onRenameProject: (ProjectSummary) -> Unit,
    onDeleteProject: (ProjectSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visibleProjects = remember(state.projects, query) {
        val term = query.trim()
        state.projects.filter { term.isEmpty() || it.title.contains(term, ignoreCase = true) ||
            it.goal.contains(term, ignoreCase = true) }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.projects.isNotEmpty()) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索项目") },
                    placeholder = { Text("名称或演示目标") },
                    shape = RoundedCornerShape(4.dp),
                    trailingIcon = if (query.isNotEmpty()) {
                        { TextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清除") } }
                    } else null,
                )
            }
            item(key = "summary") {
                WorkspaceSectionTitle("本机项目", "${state.projects.size} 个")
            }
        }
        if (state.loadFailed && state.projects.isEmpty()) {
            item(key = "unreadable") {
                WorkspaceEmpty("暂时无法读取项目", "已有内容仍保留在本机，请重新读取后继续。")
            }
        } else if (state.busy && state.projects.isEmpty()) {
            item(key = "loading") { WorkspaceEmpty("正在读取本机项目", "读取完成后，项目会显示在这里。") }
        } else if (state.projects.isEmpty()) {
            item(key = "empty") {
                WorkspaceEmpty("从一段操作开始", "录下一段操作，或导入已有录屏。")
            }
        } else if (visibleProjects.isEmpty()) {
            item(key = "no_matches") { WorkspaceEmpty("没有找到匹配项目", "换个关键词，或清除搜索查看全部项目。") }
        }
        items(visibleProjects, key = { it.id }) { project ->
            ProjectRow(
                project = project,
                enabled = !state.busy && !state.loadFailed,
                onOpen = { onOpenProject(project.id) },
                onRename = { onRenameProject(project) },
                onDelete = { onDeleteProject(project) },
            )
        }
        if (state.projects.isNotEmpty()) {
            item(key = "local_notice") {
                Text("项目与原素材保存在这台设备。封面使用中性图形，不读取原录屏。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by rememberSaveable(project.id) { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).clickable(enabled = enabled, role = Role.Button,
                    onClickLabel = "打开项目", onClick = onOpen).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NeutralProjectCover(Modifier.size(width = 64.dp, height = 80.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(project.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (project.goal.isNotBlank()) {
                        Text(project.goal, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Text("${project.stepCount} 个步骤 · 修订 ${project.revision}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("修改于 ${formatProjectDate(project.updatedAt)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                MoreButton("${project.title}，项目更多操作", enabled) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; onRename() },
                        enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
                    DropdownMenuItem(text = { Text("删除本机项目", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Never resolves source paths. [stepThumbnail] may only return the step's derived PNG. */
@Composable
fun StoryboardContent(
    state: ProjectUiState,
    onOpenStep: (String) -> Unit,
    onAddSource: () -> Unit,
    onSetStart: (String) -> Unit,
    onMoveStep: (String, Int) -> Unit,
    onDeleteStep: (ProjectStep) -> Unit,
    stepThumbnail: @Composable (StepAsset) -> Bitmap?,
    modifier: Modifier = Modifier,
    onBuildPath: (() -> Unit)? = null,
) {
    val snapshot = state.project
    if (snapshot == null) {
        Box(modifier.fillMaxSize().padding(16.dp)) {
            WorkspaceEmpty("未打开项目", "返回项目首页，选择一个项目后继续。")
        }
        return
    }
    val editable = !state.busy && !state.loadFailed
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "storyboard_summary") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("分镜", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() })
                        Text("${snapshot.steps.size} 个步骤 · ${snapshot.steps.sumOf { it.hotspots.size + if (it.nextAction != null) 1 else 0 }} 个出口",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onAddSource, enabled = editable, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("添加内容")
                    }
                }
                if (snapshot.steps.size >= 2 && onBuildPath != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("排序只调整分镜位置", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onBuildPath, enabled = editable,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("按顺序连接") }
                    }
                }
                if (snapshot.project.goal.isNotBlank()) {
                    Text(snapshot.project.goal, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.dirtyStepIds.isNotEmpty()) {
                    Text("${state.dirtyStepIds.size} 个步骤有未保存修改，列表显示已保存内容。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (snapshot.steps.isEmpty()) {
            item(key = "empty_storyboard") {
                WorkspaceEmpty("第一步还未加入", "先添加内容。确认的画面进入分镜后，可编辑标题、讲解和热点目标。")
            }
        }
        itemsIndexed(snapshot.steps, key = { _, step -> step.id }) { index, step ->
            StoryboardStepRow(
                step = step, index = index, snapshot = snapshot, enabled = editable,
                hasUnsavedChanges = step.id in state.dirtyStepIds,
                onOpenStep = onOpenStep, onSetStart = onSetStart,
                onMoveStep = onMoveStep, onDeleteStep = onDeleteStep,
                stepThumbnail = stepThumbnail,
            )
        }
    }
}

@Composable
private fun StoryboardStepRow(
    step: ProjectStep,
    index: Int,
    snapshot: ProjectSnapshot,
    enabled: Boolean,
    hasUnsavedChanges: Boolean,
    onOpenStep: (String) -> Unit,
    onSetStart: (String) -> Unit,
    onMoveStep: (String, Int) -> Unit,
    onDeleteStep: (ProjectStep) -> Unit,
    stepThumbnail: @Composable (StepAsset) -> Bitmap?,
) {
    var menuOpen by rememberSaveable(step.id) { mutableStateOf(false) }
    var allExitsVisible by rememberSaveable(step.id) { mutableStateOf(false) }
    val isStart = snapshot.project.startStepId == step.id
    val ordinal = (index + 1).toString().padStart(2, '0')
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val outletColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.drawBehind {
            val x = 12.dp.toPx()
            if (index > 0) drawLine(railColor, Offset(x, 0f), Offset(x, 6.dp.toPx()), 1.dp.toPx())
            if (index < snapshot.steps.lastIndex) {
                drawLine(railColor, Offset(x, 32.dp.toPx()), Offset(x, size.height + 16.dp.toPx()), 1.dp.toPx())
            }
        },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ordinal, modifier = Modifier.width(24.dp).padding(top = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.weight(1f)
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = "编辑步骤") { onOpenStep(step.id) }
                    .semantics {
                        customActions = if (enabled) buildList {
                            if (!isStart) add(CustomAccessibilityAction("设为起点") { onSetStart(step.id); true })
                            if (index > 0) add(CustomAccessibilityAction("上移步骤") { onMoveStep(step.id, -1); true })
                            if (index < snapshot.steps.lastIndex) add(CustomAccessibilityAction("下移步骤") { onMoveStep(step.id, 1); true })
                        } else emptyList()
                    },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DerivedStepThumbnail(step.asset, stepThumbnail, Modifier.size(width = 92.dp, height = 136.dp))
                Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(step.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val status = buildList {
                        if (isStart) add("起点")
                        if (step.isTerminal) add("终点")
                        if (hasUnsavedChanges) add("有未保存修改")
                    }.joinToString(" · ")
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    if (step.description.isNotBlank()) Text(step.description,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (step.hotspots.isEmpty() && step.nextAction == null && !step.isTerminal) {
                        Text("尚无出口", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Box {
                MoreButton("步骤 $ordinal ${step.title}，更多操作", enabled) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(if (isStart) "已是起点" else "设为起点") }, enabled = enabled && !isStart,
                        onClick = { menuOpen = false; onSetStart(step.id) }, modifier = Modifier.heightIn(min = 48.dp))
                    DropdownMenuItem(text = { Text("上移") }, enabled = enabled && index > 0,
                        onClick = { menuOpen = false; onMoveStep(step.id, -1) }, modifier = Modifier.heightIn(min = 48.dp))
                    DropdownMenuItem(text = { Text("下移") }, enabled = enabled && index < snapshot.steps.lastIndex,
                        onClick = { menuOpen = false; onMoveStep(step.id, 1) }, modifier = Modifier.heightIn(min = 48.dp))
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("删除步骤", color = MaterialTheme.colorScheme.error) }, enabled = enabled,
                        onClick = { menuOpen = false; onDeleteStep(step) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        step.nextAction?.let { action ->
            val target = snapshot.steps.firstOrNull { it.id == action.targetStepId }
            val targetIndex = snapshot.steps.indexOfFirst { it.id == action.targetStepId }
            Row(
                modifier = Modifier.padding(start = 32.dp).fillMaxWidth().heightIn(min = 48.dp)
                    .drawBehind {
                        drawLine(outletColor, Offset(0f, 10.dp.toPx()),
                            Offset(0f, size.height - 10.dp.toPx()), 1.5.dp.toPx())
                    }
                    .clickable(enabled = enabled, role = Role.Button,
                        onClickLabel = if (target != null) "编辑目标步骤 ${target.title}" else "修复下一步目标") {
                        onOpenStep(target?.id ?: step.id)
                    }
                    .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(action.label, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("作者编排", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("→", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(if (target != null) "${(targetIndex + 1).toString().padStart(2, '0')} ${target.title}" else "待修复 · 目标缺失",
                    Modifier.weight(1.1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (target == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
        if (step.hotspots.isNotEmpty()) {
            Column(Modifier.padding(start = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                step.hotspots.take(if (allExitsVisible) step.hotspots.size else 2).forEach { hotspot ->
                    val target = snapshot.steps.firstOrNull { it.id == hotspot.targetStepId }
                    val targetIndex = snapshot.steps.indexOfFirst { it.id == hotspot.targetStepId }
                    val targetLabel = when {
                        target != null -> "${(targetIndex + 1).toString().padStart(2, '0')} ${target.title}"
                        hotspot.endLabel != null -> "结束 · ${hotspot.endLabel}"
                        else -> "目标未找到"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .drawBehind {
                                drawLine(outletColor, Offset(0f, 10.dp.toPx()),
                                    Offset(0f, size.height - 10.dp.toPx()), 1.5.dp.toPx())
                            }
                            .then(if (target != null) Modifier.clickable(enabled = enabled, role = Role.Button,
                                onClickLabel = "编辑目标步骤 ${target.title}") { onOpenStep(target.id) } else Modifier)
                            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(hotspot.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("→", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(targetLabel, Modifier.weight(1.1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (target == null && hotspot.endLabel == null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (step.hotspots.size > 2) {
                    TextButton(onClick = { allExitsVisible = !allExitsVisible }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (allExitsVisible) "收起出口" else "展开其余 ${step.hotspots.size - 2} 个出口")
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 32.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Source metadata is real; recording preparation is navigation, never a permission request. */
@Composable
fun ProjectSourcesContent(
    drafts: List<SourceDraft>,
    busy: Boolean,
    loadFailed: Boolean,
    onRecord: () -> Unit,
    onImportVideo: () -> Unit,
    onOpenSource: (String) -> Unit,
    onOpenCandidates: () -> Unit,
    onAnalyzeSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !busy && !loadFailed
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "record") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WorkspaceSectionTitle("录制一段操作")
                Text("从 TapScene 开始录制，再操作目标 App。录制结束后，在这里整理为可点击的演示。",
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onRecord, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("开始录制")
                }
                Text("按画面变化整理候选，点击与热点由你校正。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "import") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已有录屏", style = MaterialTheme.typography.titleMedium)
                        Text("从系统文件选择，作为补充素材。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = onImportVideo, enabled = enabled && drafts.size < 3,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("导入录屏") }
                }
            }
        }
        item(key = "source_summary") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkspaceSectionTitle("本机素材", "${drafts.size} / 3 段")
                if (drafts.isNotEmpty()) Text(
                    "合计 ${formatSourceDuration(drafts.sumOf { it.source.metadata.durationUs })} · ${formatSourceBytes(drafts.sumOf { it.source.metadata.byteLength })}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("原录屏仅保存在本机。进入单条素材后可手动校正画面与遮挡。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (drafts.isEmpty()) {
            item(key = "source_empty") {
                WorkspaceEmpty(
                    if (loadFailed) "暂时无法读取素材" else if (busy) "正在读取素材" else "还没有素材",
                    if (loadFailed) "读取失败不代表素材已删除，请重试后继续。"
                    else if (busy) "读取完成后会显示实际时长和大小。"
                    else "录制或导入的素材会列在这里，候选确认后再加入分镜。",
                )
            }
        }
        items(drafts, key = { it.source.sourceId }) { draft ->
            SourceDraftRow(draft = draft, enabled = enabled, onOpen = { onOpenSource(draft.source.sourceId) },
                onAnalyze = { onAnalyzeSource(draft.source.sourceId) })
        }
        item(key = "candidates") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkspaceSectionTitle("整理与确认")
                Text("按画面变化整理候选；批量选择后，在同一校正页调帧、遮挡和确认。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onOpenCandidates, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("整理候选步骤") }
            }
        }
    }
}

@Composable
private fun SourceDraftRow(draft: SourceDraft, enabled: Boolean, onOpen: () -> Unit, onAnalyze: () -> Unit) {
    val source = draft.source
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(width = 48.dp, height = 64.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) {
                Text("视频", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(source.displayName, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatSourceDuration(source.metadata.durationUs)} · ${formatSourceBytes(source.metadata.byteLength)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${source.metadata.displayWidth} × ${source.metadata.displayHeight} · 已存本机",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onAnalyze, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("整理候选") }
            TextButton(onClick = onOpen, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("手动校正") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun DerivedStepThumbnail(
    asset: StepAsset,
    stepThumbnail: @Composable (StepAsset) -> Bitmap?,
    modifier: Modifier,
) {
    val bitmap = stepThumbnail(asset)
    val placeholderInk = MaterialTheme.colorScheme.outline
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Canvas(Modifier.fillMaxSize().padding(7.dp)) {
                val stroke = 1.dp.toPx()
                val width = size.width.coerceAtLeast(1f)
                val height = size.height.coerceAtLeast(1f)
                drawRect(placeholderInk, topLeft = Offset(width * .15f, height * .23f),
                    size = Size(width * .7f, height * .54f), style = Stroke(stroke))
                drawLine(placeholderInk, Offset(width * .25f, height * .65f),
                    Offset(width * .7f, height * .35f), stroke)
            }
        }
    }
}

/** Deliberately neutral: project covers must not decode originals or imply a reviewed release. */
@Composable
private fun NeutralProjectCover(modifier: Modifier = Modifier) {
    val border = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    Canvas(modifier.background(background).border(1.dp, border)) {
        val stroke = 1.dp.toPx()
        val x = size.width * .22f
        val y = size.height * .22f
        val w = size.width * .56f
        val h = size.height * .25f
        drawRect(ink.copy(alpha = .65f), Offset(x, y), Size(w, h), style = Stroke(stroke))
        drawLine(ink.copy(alpha = .55f), Offset(x, size.height * .61f), Offset(x + w, size.height * .61f), stroke)
        drawLine(ink.copy(alpha = .35f), Offset(x, size.height * .72f), Offset(x + w * .62f, size.height * .72f), stroke)
    }
}

@Composable
private fun MoreButton(description: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp)) {
        Text("⋯", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun WorkspaceSectionTitle(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkspaceEmpty(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatProjectDate(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))

private fun formatSourceDuration(durationUs: Long): String {
    val milliseconds = durationUs.coerceAtLeast(0L) / 1_000L
    return String.format(Locale.getDefault(), "%d:%02d.%03d", milliseconds / 60_000,
        milliseconds / 1_000 % 60, milliseconds % 1_000)
}

private fun formatSourceBytes(bytes: Long): String =
    if (bytes < 1_048_576L) String.format(Locale.getDefault(), "%.1f KiB", bytes.coerceAtLeast(0L) / 1024.0)
    else String.format(Locale.getDefault(), "%.1f MiB", bytes / 1_048_576.0)
