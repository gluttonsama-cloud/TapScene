package com.tapscene.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapscene.data.ProjectLimits
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep

/** Author confirmation is the only graph-changing event; local ordering never changes sortOrder. */
@Composable
fun AuthoredPathScreen(
    project: ProjectSnapshot,
    initialStepIds: List<String>?,
    busy: Boolean,
    onBack: () -> Unit,
    onBuild: (List<String>, Boolean, Long) -> Unit,
    onEditConflict: (String) -> Unit,
    initialMarkLastTerminal: Boolean? = null,
    onDraftChanged: ((List<String>, Boolean) -> Unit)? = null,
) {
    var selectedIds by rememberSaveable(project.project.id) {
        mutableStateOf(initialStepIds?.distinct() ?: project.steps.map { it.id })
    }
    var markLastTerminal by rememberSaveable(project.project.id) {
        mutableStateOf(initialMarkLastTerminal ?: project.steps.firstOrNull { it.id == selectedIds.lastOrNull() }.hasNoActions())
    }
    val expectedRevision by rememberSaveable(project.project.id) { mutableStateOf(project.project.revision) }
    val stepById = project.steps.associateBy { it.id }
    val selected = selectedIds.map { stepById[it] }
    val missingIds = selectedIds.filter { it !in stepById }
    val last = selected.lastOrNull()
    val intermediateTerminalIds = selected.dropLast(1).filterNotNull().filter { it.isTerminal }.map { it.id }.toSet()
    val lastHasConflict = markLastTerminal && last != null && !last.hasNoActions()
    val changedTargetCount = selectedIds.zipWithNext().count { (fromId, targetId) ->
        stepById[fromId]?.nextAction?.let { it.targetStepId != targetId } == true
    }
    val newActionCount = selectedIds.dropLast(1).count { stepById[it]?.nextAction == null && it in stepById }
    val totalEdges = project.steps.sumOf { it.hotspots.size + if (it.nextAction != null) 1 else 0 } + newActionCount
    val stale = expectedRevision != project.project.revision
    val canBuild = !busy && !stale && selectedIds.size >= 2 && missingIds.isEmpty() &&
        intermediateTerminalIds.isEmpty() && !lastHasConflict && totalEdges <= ProjectLimits.MAX_EDGES
    val changeSelection: (List<String>) -> Unit = { ids ->
        val terminal = if (selectedIds.lastOrNull() != ids.lastOrNull()) stepById[ids.lastOrNull()].hasNoActions()
            else markLastTerminal
        markLastTerminal = terminal
        selectedIds = ids
        onDraftChanged?.invoke(ids.toList(), terminal)
    }
    LaunchedEffect(project.project.id) {
        onDraftChanged?.invoke(selectedIds.toList(), markLastTerminal)
    }
    BackHandler { if (!busy) onBack() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ShellTopBar("按顺序连接", onBack = { if (!busy) onBack() })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("path_summary") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("本次通路 · 已选 ${selectedIds.size} 步", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    Text("作者编排 · 上下移动调整本次顺序", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(selectedIds, key = { _, id -> "selected_$id" }) { index, id ->
                val step = stepById[id]
                val target = selectedIds.getOrNull(index + 1)?.let(stepById::get)
                val conflict = when {
                    step == null -> "步骤已不存在，请移出本次通路。"
                    id in intermediateTerminalIds -> "中间步骤是终点，请先取消终点。"
                    index == selectedIds.lastIndex && lastHasConflict -> "已有动作，不能同时设为终点。"
                    else -> null
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = true, onCheckedChange = { changeSelection(selectedIds - id) }, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "移出通路：${step?.title ?: "缺失步骤"}" })
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${index + 1}  ${step?.title ?: "步骤已不存在"}", style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(when {
                                index < selectedIds.lastIndex -> "下一步 → ${target?.title ?: "目标已不存在"}"
                                markLastTerminal -> "末步 · 设为终点"
                                step?.isTerminal == true -> "末步 · 已是终点"
                                step?.nextAction != null -> "末步 · 保留下一步与热点"
                                else -> "末步 · 保留现有动作"
                            }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        PathMoveButton("↑", "上移 ${step?.title ?: "缺失步骤"}", !busy && index > 0) {
                            changeSelection(selectedIds.toMutableList().apply { add(index - 1, removeAt(index)) })
                        }
                        PathMoveButton("↓", "下移 ${step?.title ?: "缺失步骤"}", !busy && index < selectedIds.lastIndex) {
                            changeSelection(selectedIds.toMutableList().apply { add(index + 1, removeAt(index)) })
                        }
                    }
                    if (conflict != null) Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(conflict, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        if (step != null) TextButton(onClick = { onEditConflict(id) }, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("去修正") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            val remaining = project.steps.filter { it.id !in selectedIds }
            if (remaining.isNotEmpty()) item("available_heading") {
                Text("可加入步骤", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            }
            items(remaining, key = { "available_${it.id}" }) { step ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = false, enabled = !busy, role = Role.Checkbox,
                        onValueChange = { changeSelection(selectedIds + step.id) }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = false, onCheckedChange = null, enabled = !busy)
                    Text(step.title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (step.isTerminal) Text("终点", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (project.steps.isEmpty()) item("empty") {
                Text("还没有可连接的步骤。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = markLastTerminal, enabled = !busy && last != null, role = Role.Checkbox,
                    onValueChange = {
                        markLastTerminal = it
                        onDraftChanged?.invoke(selectedIds.toList(), it)
                    }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = markLastTerminal, onCheckedChange = null, enabled = !busy && last != null)
                Text("最后一步设为终点", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (changedTargetCount > 0) "将改写 $changedTargetCount 个下一步目标；手动热点保留。"
                else "按上方顺序连接；手动热点与分镜排序保留。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val blockingMessage = when {
                stale -> "项目已有更新，请返回后重新打开。"
                missingIds.isNotEmpty() -> "移出缺失步骤后再生成。"
                intermediateTerminalIds.isNotEmpty() -> "先修正上方终点冲突。"
                lastHasConflict -> "取消末步终点选项，或先移除末步动作。"
                selectedIds.size < 2 -> "至少选择 2 个步骤。"
                totalEdges > ProjectLimits.MAX_EDGES -> "连接后超过 ${ProjectLimits.MAX_EDGES} 个动作，请减少所选步骤。"
                else -> null
            }
            blockingMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { if (canBuild) onBuild(selectedIds.toList(), markLastTerminal, expectedRevision) },
                enabled = canBuild, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (busy) "正在生成…" else "按此顺序生成通路")
            }
        }
    }
}

@Composable
private fun PathMoveButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

private fun ProjectStep?.hasNoActions(): Boolean = this != null && hotspots.isEmpty() && nextAction == null
