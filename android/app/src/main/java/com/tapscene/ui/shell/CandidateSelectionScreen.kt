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
import com.tapscene.data.CandidateOcrStatus
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
            onRecognizeText = workspace::recognizeText,
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
    val onRecognizeText: (List<String>) -> Unit = {},
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
    val recognizing = state.ocrStatus == CandidateOcrStatus.RUNNING
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("候选步骤", callbacks.onBack) {
            Box {
                TextButton(onClick = { menu = true }, enabled = !state.busy && !state.loading && sources.isNotEmpty()) { Text("素材 ▾") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    sources.forEach { draft ->
                        DropdownMenuItem(text = { Text(draft.source.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            onClick = { menu = false; callbacks.onSource(draft.source.sourceId) }, enabled = !disabled)
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
                        val total = if (recognizing) state.ocrTotal else if (state.status == CandidateAnalysisStatus.RUNNING) state.totalSamples else 0
                        val completed = if (recognizing) state.ocrCompleted else state.completedSamples
                        if (total > 0) LinearProgressIndicator(
                            progress = { (completed.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (recognizing) "本机识别文字 · $completed/$total 帧"
                                else if (state.status == CandidateAnalysisStatus.RUNNING && total > 0) "已检查 $completed/$total 个时间位置"
                                else "正在处理候选…",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (recognizing || state.status == CandidateAnalysisStatus.RUNNING) TextButton(onClick = callbacks.onCancel) { Text("取消") }
                        }
                    } else if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.message?.let { StatusNote(it) }
                    state.ocrMessage?.let { StatusNote(it) }
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
                        val ocr = state.ocrResults[candidate.id]
                        val lines = remember(ocr) { ocr?.let(::ocrTextSuggestions).orEmpty() }
                        Text(if (candidate.id == state.ocrFailedCandidateId && ocr == null) "识别未完成，可重试"
                            else if (ocr == null) "文字未识别" else if (ocr.words.isEmpty()) "未识别到文字"
                            else "文字建议 · ${lines.size} 行 / ${ocr.words.size} 段",
                            style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted)
                        val title = lines.firstNotNullOfOrNull { it.title }
                        if (title != null) Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else if (ocr != null && ocr.words.isNotEmpty()) Text("暂无标题建议", style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted)
                        if (ocr?.truncated == true) Text("仅识别了部分文字", style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted)
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
            OutlinedButton(onClick = { callbacks.onRecognizeText(selected.map { it.id }) },
                enabled = !disabled && selected.any { it.id !in state.ocrResults },
                shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("识别文字") }
            Button(onClick = { callbacks.onReview(selected.map { it.id }) }, enabled = !disabled && selected.isNotEmpty() && selected.size <= remainingSteps,
                shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (selected.size > remainingSteps) "最多 $remainingSteps 帧" else "校正 ${selected.size} 帧")
            }
        }
    }
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false }, title = { Text("画面分析建议") },
        text = { Text("候选来自画面变化，可能遗漏；可在校正页调帧，也可手动补充。\n\n选好画面后可批量“识别文字”。文字建议只在本机保留，可能识错；在校正页采用标题或按文字框遮挡后，仍需检查实际输出。采用的标题会进入作品，并在成品交付前再次复核。这里没有记录点击或自动确定热点，也不会自动识别全部敏感内容。") },
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
