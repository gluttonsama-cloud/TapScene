package com.tapscene.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tapscene.data.ProjectSummary
import com.tapscene.ui.ProjectUiState

/** A visible-list guard, not an atomic storage revision check. */
internal fun ProjectUiState.matchesProjectDeletion(item: ProjectSummary): Boolean =
    (project?.project?.takeIf { it.id == item.id } ?: projects.firstOrNull { it.id == item.id }) == item

@Composable
internal fun ProjectDeleteDialog(item: ProjectSummary, current: Boolean, enabled: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ProjectDeleteConfirmationContent(item, current, enabled, onDismiss, onConfirm)
        }
    }
}

/** Shared by the actual dialog and host layouts; actions stay outside the scrollable details. */
@Composable
internal fun ProjectDeleteConfirmationContent(item: ProjectSummary, current: Boolean, enabled: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("删除本机项目？", style = MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text("项目 ID：${item.id}", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            Text("${item.stepCount} 个步骤 · 修订 ${item.revision}", style = MaterialTheme.typography.bodySmall)
            if (item.goal.isNotBlank()) Text("开场说明摘要：${item.goal}", maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            Text("将删除", style = MaterialTheme.typography.titleSmall)
            Text("此项目的步骤、热点与下一步、区域、动画计划和暂存修改；项目内图片、过渡短片、原截图副本及文字建议。无法撤销。",
                style = MaterialTheme.typography.bodyMedium)
            Text("仍然保留", style = MaterialTheme.typography.titleSmall)
            Text("演示库中的固定候选与封存版本（含独立图片），需要在演示库分别管理。",
                style = MaterialTheme.typography.bodyMedium)
            Text("原录屏、外部原文件和已导出文件保留。原录屏可在“设置 → 本机保留素材”中管理。",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (!current) Text("项目内容已变化，请取消后重新打开并核对。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else if (!enabled) Text("当前暂不可删除，可取消后重新核对。",
            style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            TextButton(onClick = onConfirm, enabled = enabled && current,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("删除", color = if (enabled && current)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) }
        }
    }
}
