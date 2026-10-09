package com.tapscene.ui.shell

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapscene.data.ProjectSnapshot
import com.tapscene.ui.PreviewState

/** Page 07 displays the host's real frozen preview state and never invents traversal or coverage. */
@Composable
fun DraftPreviewContent(
    project: ProjectSnapshot,
    preview: PreviewState,
    bitmap: Bitmap?,
    busy: Boolean,
    onHotspot: (String) -> Unit,
    onTap: (Float, Float) -> Unit,
    onPrevious: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasBitmap = bitmap != null && !bitmap.isRecycled
    val step = project.steps.firstOrNull { it.id == preview.currentStepId }
    var showExplanation by rememberSaveable(preview.currentStepId) { mutableStateOf(false) }
    BackHandler { if (!busy) onExit() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelMaxHeight = (maxHeight * 0.3f).coerceIn(64.dp, 220.dp)
        val explanationMaxHeight = (maxHeight * 0.16f).coerceIn(44.dp, 120.dp)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("草稿预览", style = MaterialTheme.typography.titleMedium)
                    Text("修订 ${preview.revision} · 本地试走", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onExit, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("退出预览") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = step != null) { showExplanation = !showExplanation }
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(step?.title ?: "步骤不可用", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (showExplanation) "收起讲解 ↑" else "讲解 ↓", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showExplanation) Column(Modifier.fillMaxWidth().heightIn(max = explanationMaxHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(step?.description?.takeIf { it.isNotBlank() } ?: "这一步没有讲解。", style = MaterialTheme.typography.bodyMedium)
                Text("本次访问 ${preview.history.size} 步 · 已试走 ${preview.visitedActionIds.size} 个动作",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorCanvas(bitmap = bitmap, hotspots = if (preview.ended) emptyList() else step?.hotspots.orEmpty(),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                enabled = !busy && !preview.ended && step != null, busy = busy, onTap = onTap)
            Column(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    step == null -> Text("当前步骤不存在。退出预览后重新打开项目。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                    preview.ended -> {
                        Text("演示结束", style = MaterialTheme.typography.titleMedium)
                        preview.endLabel?.takeIf { it.isNotBlank() && it != step.title }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    step.hotspots.isEmpty() -> Text("这一步还没有动作。退出预览后可继续编辑。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        Text(if (preview.matchingHotspotIds.size > 1) "重叠区域：请选择一个动作" else "选择动作",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        step.hotspots.forEachIndexed { index, hotspot ->
                            val validTarget = hotspot.endLabel != null || project.steps.any { it.id == hotspot.targetStepId }
                            val matching = hotspot.id in preview.matchingHotspotIds
                            OutlinedButton(onClick = { onHotspot(hotspot.id) }, enabled = !busy && hasBitmap && validTarget,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 12.dp))
                                    Text(hotspot.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text(when { !validTarget -> "目标缺失"; matching -> "选中区域"; else -> "→" },
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onPrevious, enabled = !busy && preview.canGoBack,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上一步") }
                TextButton(onClick = onRestart, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("重来") }
            }
        }
    }
}
