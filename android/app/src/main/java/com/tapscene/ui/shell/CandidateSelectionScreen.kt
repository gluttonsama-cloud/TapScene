package com.tapscene.ui.shell

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapscene.data.SourceDraft
import com.tapscene.media.CandidateAnalysisStatus
import com.tapscene.media.CandidateDecision
import com.tapscene.media.CandidateReason
import com.tapscene.media.FrameCandidate
import com.tapscene.ui.CandidateWorkspace
import com.tapscene.ui.CandidateWorkspaceState
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** All thumbnails here are private source suggestions, never reviewed StepAssets. */
@Composable
fun CandidateSelectionScreen(
    state: CandidateWorkspaceState,
    sources: List<SourceDraft>,
    workspace: CandidateWorkspace,
    sourceReady: Boolean,
    remainingSteps: Int,
    onSource: (String) -> Unit,
    onReview: (List<String>) -> Unit,
    onBack: () -> Unit,
    onImport: () -> Unit,
) {
    CandidateSelectionContent(
        state = state,
        sources = sources,
        sourceReady = sourceReady,
        remainingSteps = remainingSteps,
        callbacks = CandidateSelectionCallbacks(
            onSource = onSource,
            onReview = onReview,
            onBack = onBack,
            onImport = onImport,
            onAnalyze = workspace::analyze,
            onCancel = workspace::cancel,
            onSetDecision = workspace::setDecision,
            onSetDecisions = workspace::setDecisions,
        ),
        candidateThumbnail = { candidate -> CandidateThumbnail(workspace, candidate, state.busy || state.loading) },
    )
}

data class CandidateSelectionCallbacks(
    val onSource: (String) -> Unit,
    val onReview: (List<String>) -> Unit,
    val onBack: () -> Unit,
    val onImport: () -> Unit,
    val onAnalyze: () -> Unit,
    val onCancel: () -> Unit,
    val onSetDecision: (String, CandidateDecision) -> Unit,
    val onSetDecisions: (List<String>, CandidateDecision) -> Unit,
)

/** Uses the production layout without a ViewModel, file access or media decoding. */
@Composable
fun CandidateSelectionContent(
    state: CandidateWorkspaceState,
    sources: List<SourceDraft>,
    sourceReady: Boolean,
    remainingSteps: Int,
    callbacks: CandidateSelectionCallbacks,
    candidateThumbnail: @Composable (FrameCandidate) -> Bitmap?,
) {
    var menu by remember { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showDismissed by rememberSaveable(state.sourceId) { mutableStateOf(false) }
    val disabled = state.busy || state.loading || !sourceReady
    val selectable = state.candidates.filter { it.usedStepId == null && it.decision != CandidateDecision.DISMISSED }
    val selected = state.candidates.filter { it.decision == CandidateDecision.KEPT && it.usedStepId == null }
    val visible = state.candidates.filter { showDismissed || it.decision != CandidateDecision.DISMISSED }
    val source = sources.firstOrNull { it.source.sourceId == state.sourceId }
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("候选步骤", callbacks.onBack) {
            Box {
                TextButton(onClick = { menu = true }, enabled = !state.busy && sources.isNotEmpty()) { Text("素材 ▾") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    sources.forEach { draft ->
                        DropdownMenuItem(text = { Text(draft.source.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            onClick = { menu = false; callbacks.onSource(draft.source.sourceId) })
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("source") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(source?.source?.displayName ?: "选择来源录屏", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("画面分析 · 原片私有", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                        TextButton(onClick = { showHelp = true }) { Text("说明") }
                    }
                    if (state.busy) {
                        if (state.totalSamples > 0) LinearProgressIndicator(
                            progress = { (state.completedSamples.toFloat() / state.totalSamples).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.totalSamples > 0) "已检查 ${state.completedSamples}/${state.totalSamples} 个时间位置" else "正在准备画面分析…",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = callbacks.onCancel) { Text("取消") }
                        }
                    } else if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.message?.let { StatusNote(it) }
                    if (!state.busy && !state.loading) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = callbacks.onAnalyze, enabled = !disabled,
                                shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(when (state.status) {
                                    CandidateAnalysisStatus.NOT_STARTED -> "整理候选"
                                    CandidateAnalysisStatus.INTERRUPTED, CandidateAnalysisStatus.CANCELLED, CandidateAnalysisStatus.FAILED -> "继续整理"
                                    else -> "重新分析"
                                })
                            }
                            if (state.candidates.isNotEmpty()) TextButton(onClick = { showDismissed = !showDismissed }) {
                                Text(if (showDismissed) "收起略过" else "查看略过")
                            }
                        }
                    }
                }
            }
            if (sources.isEmpty()) item("no_source") {
                ScreenEmpty("还没有录屏", "录制或导入之后再整理画面。", "导入录屏", callbacks.onImport)
            } else if (!state.busy && !state.loading && visible.isEmpty()) item("empty") {
                ScreenEmpty(if (state.candidates.isEmpty()) "还没有候选画面" else "候选都已略过", "选择需要的画面，再一起校正。")
            }
            items(visible, key = { it.id }) { candidate ->
                val bitmap = candidateThumbnail(candidate)
                val checked = candidate.decision == CandidateDecision.KEPT
                val used = candidate.usedStepId != null
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(92.dp, 140.dp).background(ShellColors.Quiet).border(1.dp, ShellColors.Divider), contentAlignment = Alignment.Center) {
                        if (bitmap != null) Image(bitmap.asImageBitmap(), "私有候选画面 ${candidate.actualTimeUs / 1000} 毫秒", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else Text(if (state.busy) "分析中" else "画面待载入", style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(candidateTime(candidate.actualTimeUs), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Checkbox(checked = checked || used, onCheckedChange = {
                                callbacks.onSetDecision(candidate.id, if (it) CandidateDecision.KEPT else CandidateDecision.SUGGESTED)
                            }, enabled = !disabled && !used)
                        }
                        Text(when (candidate.reason) {
                            CandidateReason.FIRST_FRAME -> "开始画面"
                            CandidateReason.VISUAL_CHANGE -> "画面变化"
                            CandidateReason.LAST_FRAME -> "末尾画面"
                        }, style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                        Text(if (used) "已加入步骤" else if (candidate.decision == CandidateDecision.DISMISSED) "已略过" else "待校正与复核",
                            style = MaterialTheme.typography.labelMedium, color = if (used) ShellColors.Accent else ShellColors.Muted)
                        if (!used) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { callbacks.onReview(listOf(candidate.id)) }, enabled = !disabled && remainingSteps > 0) { Text("校正") }
                            TextButton(onClick = {
                                callbacks.onSetDecision(candidate.id, if (candidate.decision == CandidateDecision.DISMISSED) CandidateDecision.SUGGESTED else CandidateDecision.DISMISSED)
                            }, enabled = !disabled) { Text(if (candidate.decision == CandidateDecision.DISMISSED) "恢复" else "略过") }
                        }
                    }
                }
                ShellDivider(Modifier.padding(top = 12.dp))
            }
        }
        ShellDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val eligible = selectable
                val allSelected = eligible.isNotEmpty() && eligible.all { it.decision == CandidateDecision.KEPT }
                callbacks.onSetDecisions(eligible.map { it.id }, if (allSelected) CandidateDecision.SUGGESTED else CandidateDecision.KEPT)
            }, enabled = !disabled && state.candidates.any { it.usedStepId == null }) { Text(if (selectable.isNotEmpty() && selectable.all { it.decision == CandidateDecision.KEPT }) "清空" else "全选") }
            Button(onClick = { callbacks.onReview(selected.map { it.id }) }, enabled = !disabled && selected.isNotEmpty() && selected.size <= remainingSteps,
                shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (selected.size > remainingSteps) "最多再加入 $remainingSteps 帧" else "校正所选 ${selected.size} 帧")
            }
        }
    }
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false }, title = { Text("画面分析建议") },
        text = { Text("建议来自录屏画面变化，可能漏掉短暂或细微变化；可在校正页调帧，也可手动补充。\n\n这里没有记录原始点击、识别文字或自动确定热点。选择后仍需检查实际输出，再加入步骤。") },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } })
}

@Composable
private fun CandidateThumbnail(workspace: CandidateWorkspace, candidate: FrameCandidate, paused: Boolean): Bitmap? {
    var bitmap by remember(candidate.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(candidate.id, paused) {
        if (!paused && bitmap == null) {
            try { bitmap = workspace.loadThumbnail(candidate.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A missing private preview never falls back to a StepAsset. */ }
        }
    }
    // Compose can still hold a previous draw; do not recycle published images in onDispose.
    return bitmap
}

private fun candidateTime(timeUs: Long): String = String.format(Locale.ROOT, "%.3f 秒", timeUs / 1_000_000.0)
