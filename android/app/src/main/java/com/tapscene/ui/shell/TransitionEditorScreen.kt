package com.tapscene.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapscene.media.OpaqueMask
import com.tapscene.ui.FrameEditor
import com.tapscene.ui.LocalVideoPlayback
import com.tapscene.ui.MaskDialog
import com.tapscene.ui.TransitionUiState
import com.tapscene.ui.TransitionWorkspace
import java.util.Locale

/** Page 06 shares the real media pipeline; source preview is never passed into a player/export. */
@Composable
fun TransitionEditorScreen(workspace: TransitionWorkspace, onBack: () -> Unit, onSaved: (String, String) -> Unit) {
    val state by workspace.state.collectAsStateWithLifecycle()
    val back: () -> Unit = {
        if (state.busy) workspace.cancel() else { workspace.leave(); onBack() }
    }
    BackHandler(onBack = back)
    TransitionEditorContent(state, back, workspace::selectSource, workspace::setRange, workspace::takeFrame,
        workspace::addMask, workspace::undoMask, workspace::generate, workspace::editAgain,
        { workspace.confirmAndBind(onSaved) }, { workspace.useStatic(onSaved) }, workspace::cancel,
        workspace::watched, workspace::interrupted, workspace::replay)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitionEditorContent(
    state: TransitionUiState,
    onBack: () -> Unit,
    onSource: (String) -> Unit,
    onRange: (Long, Long) -> Unit,
    onFrame: (Long) -> Unit,
    onMask: (OpaqueMask) -> Unit,
    onUndoMask: () -> Unit,
    onGenerate: () -> Unit,
    onEditAgain: () -> Unit,
    onConfirm: () -> Unit,
    onStatic: () -> Unit,
    onCancel: () -> Unit,
    onCompleted: (Long) -> Unit,
    onInterrupted: (Long) -> Unit,
    onReplay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sourcesOpen by remember { mutableStateOf(false) }
    var showRange by remember { mutableStateOf(false) }
    var showMask by remember { mutableStateOf(false) }
    var masking by remember(state.edgeId) { mutableStateOf(false) }
    var confirmStatic by remember { mutableStateOf(false) }
    var frameTime by remember(state.startUs, state.endUs, state.frame?.presentationTimeUs) {
        mutableStateOf((state.frame?.presentationTimeUs ?: state.startUs).coerceIn(state.startUs, maxOf(state.startUs, state.endUs - 1)).toFloat())
    }
    val generated = state.candidate
    val canEdit = !state.busy && !state.failedToLoad && generated == null
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelHeight = (maxHeight * 0.39f).coerceIn(120.dp, 310.dp)
        Column(Modifier.fillMaxSize()) {
            ShellTopBar(if (generated == null) "录制过渡" else "复核过渡", onBack = onBack)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.fromTitle.ifBlank { "当前步骤" }, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("→ ${state.targetTitle.ifBlank { "目标步骤" }}", style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (generated != null) "实际输出 · 无声" else "仅本机编辑", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                when {
                    generated != null -> LocalVideoPlayback(generated.file, generated.width, generated.height,
                        state.runId, Modifier.fillMaxSize(), onCompleted, onInterrupted, onInterrupted, onReplay)
                    state.frame != null -> FrameEditor(state.frame.bitmap, state.masks, canEdit && masking && state.masks.size < 20,
                        onMask, Modifier.fillMaxSize(), fitHeight = true)
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (state.busy) "正在读取画面" else "请选择可用的本机录屏", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Column(Modifier.fillMaxWidth().heightIn(max = panelHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (generated != null) {
                    Text("${transitionSeconds(requireNotNull(generated.durationUs))} 秒 · 合计 ${transitionSeconds(state.otherDurationUs + generated.durationUs)} / 60 秒",
                        style = MaterialTheme.typography.labelMedium)
                    Text(if (state.canConfirm) "已完整播放，请确认画面没有遗漏的敏感内容。"
                        else "完整观看后可保存；暂停可以继续，离开应用需重播。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onEditAgain, enabled = !state.busy) { Text("调整区间 / 遮挡") }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            TextButton(onClick = { sourcesOpen = true }, enabled = canEdit && state.sources.isNotEmpty()) {
                                Text(state.source?.displayName ?: "选择本机录屏", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(sourcesOpen, { sourcesOpen = false }) {
                                state.sources.forEach { source -> DropdownMenuItem(text = { Text(source.displayName, maxLines = 2) },
                                    onClick = { sourcesOpen = false; onSource(source.sourceId) }) }
                            }
                        }
                        TextButton(onClick = { showRange = true }, enabled = canEdit && state.source != null) { Text("裁剪") }
                    }
                    Text("${transitionSeconds(state.startUs)}–${transitionSeconds(state.endUs)} 秒 · 合计 ${transitionSeconds(state.otherDurationUs + state.endUs - state.startUs)} / 60 秒",
                        style = MaterialTheme.typography.labelMedium)
                    Slider(value = frameTime, onValueChange = { frameTime = it },
                        onValueChangeFinished = { onFrame(frameTime.toLong()) },
                        valueRange = state.startUs.toFloat()..maxOf(state.startUs + 1, state.endUs - 1).toFloat(),
                        enabled = canEdit && state.source != null,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "查看区间内实际画面" })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(masking, { masking = !masking }, label = { Text("遮挡 ${state.masks.size}") },
                            enabled = canEdit && state.frame != null)
                        if (masking) {
                            TextButton(onClick = { showMask = true }, enabled = canEdit && state.masks.size < 20) { Text("精确添加") }
                            TextButton(onClick = onUndoMask, enabled = canEdit && state.masks.isNotEmpty()) { Text("撤销") }
                        } else Text(state.frame?.let { "画面 ${transitionSeconds(it.presentationTimeUs)} 秒" } ?: "", Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (masking) Text("拖出固定遮挡，覆盖整段运动范围。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.busy) Text(state.stage.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.busy) OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消处理") }
                else {
                    OutlinedButton(onClick = { if (state.existing != null) confirmStatic = true else onStatic() },
                        enabled = !state.failedToLoad && state.projectId != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("静态切换") }
                    Button(onClick = if (generated == null) onGenerate else onConfirm,
                        enabled = if (generated == null) state.canRender else state.canConfirm,
                        modifier = Modifier.weight(1.25f).heightIn(min = 48.dp)) {
                        Text(if (generated == null) "生成并复核" else "确认并保存")
                    }
                }
            }
        }
    }
    if (showRange) TransitionRangeDialog(state, { showRange = false }) { start, end ->
        showRange = false; onRange(start, end)
    }
    if (showMask) MaskDialog({ showMask = false }) { mask -> showMask = false; onMask(mask) }
    if (confirmStatic) AlertDialog(onDismissRequest = { confirmStatic = false }, title = { Text("改为静态切换？") },
        text = { Text("移除这条动作的短片。已封存版本保持原样。") },
        confirmButton = { TextButton(onClick = { confirmStatic = false; onStatic() }) { Text("改为静态") } },
        dismissButton = { TextButton(onClick = { confirmStatic = false }) { Text("取消") } })
}

@Composable
private fun TransitionRangeDialog(state: TransitionUiState, onDismiss: () -> Unit, onConfirm: (Long, Long) -> Unit) {
    var start by remember { mutableStateOf(transitionSeconds(state.startUs)) }
    var end by remember { mutableStateOf(transitionSeconds(state.endUs)) }
    fun parse(text: String) = text.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..180.0 }?.let { (it * 1_000_000).toLong() }
    val a = parse(start)
    val b = parse(end)
    val valid = a != null && b != null && b > a && b <= (state.source?.metadata?.durationUs ?: 0) &&
        b - a <= 10_000_000L && state.otherDurationUs + b - a <= 60_000_000L
    AlertDialog(onDismissRequest = onDismiss, title = { Text("裁剪短片") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(start, { start = it }, label = { Text("起点（秒）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(end, { end = it }, label = { Text("终点（秒）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("单段最多 10 秒；可用 ${transitionSeconds((60_000_000L - state.otherDurationUs).coerceAtLeast(0))} 秒。",
                style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(requireNotNull(a), requireNotNull(b)) }, enabled = valid) { Text("使用区间") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun transitionSeconds(us: Long): String = String.format(Locale.ROOT, "%.3f", us / 1_000_000.0).trimEnd('0').trimEnd('.')
