package com.tapscene.ui

import android.graphics.Bitmap
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun MediaScreen(workspace: MediaWorkspace) {
    val state by workspace.state.collectAsStateWithLifecycle()
    var pendingDigest by rememberSaveable { mutableStateOf("") }
    var savePending by rememberSaveable { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var maskDialog by remember { mutableStateOf(false) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) workspace.importVideo(uri)
    }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        savePending = false
        if (uri != null) workspace.saveCandidate(uri, pendingDigest) else workspace.cancelSavePicker()
    }
    val saveMp4 = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        savePending = false
        if (uri != null) workspace.saveCandidate(uri, pendingDigest) else workspace.cancelSavePicker()
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF356B58), surface = Color(0xFFF8F9F6))) {
        Scaffold { insets ->
            Column(
                modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("TapScene", style = MaterialTheme.typography.headlineMedium)
                Text("素材与遮挡", style = MaterialTheme.typography.titleLarge)
                Text("MP4 / H.264 / SDR 竖屏，单段不超过 3 分钟、200 MiB。原片只保存在本机。",
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { import.launch(arrayOf("video/mp4")) }, enabled = !state.busy && !savePending && !state.loadFailed,
                    modifier = Modifier.fillMaxWidth()) { Text("选择录屏") }
                if (state.loadFailed) {
                    Text("已保存记录暂时无法读取。为保留原有内容，当前不能导入新素材。")
                    OutlinedButton(onClick = workspace::reload, enabled = !state.busy && !savePending) { Text("重新读取") }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(state.stage.orEmpty(), Modifier.weight(1f))
                        TextButton(onClick = workspace::cancel) { Text("取消") }
                    }
                }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                state.drafts.forEachIndexed { index, draft ->
                    OutlinedButton(onClick = { workspace.selectSource(draft.source.sourceId) },
                        enabled = !state.busy && !savePending, modifier = Modifier.fillMaxWidth()) {
                        Text("${if (draft.source.sourceId == state.selectedId) "已选 · " else ""}${index + 1}. ${draft.source.displayName}")
                    }
                }
                state.selected?.let { selected ->
                    val meta = selected.source.metadata
                    Text("${meta.displayWidth} × ${meta.displayHeight} · ${seconds(meta.durationUs)} 秒 · ${String.format(Locale.ROOT, "%.1f", meta.byteLength / 1048576.0)} MiB")
                    key(selected.source.sourceId) {
                        var requestedSeconds by rememberSaveable { mutableStateOf(selected.frameTimeUs / 1_000_000f) }
                        Text("定位：${seconds((requestedSeconds * 1_000_000).toLong())} 秒")
                        Slider(value = requestedSeconds, onValueChange = { requestedSeconds = it },
                            valueRange = 0f..(meta.durationUs / 1_000_000f).coerceAtLeast(0.001f), enabled = !state.busy && !savePending,
                            modifier = Modifier.semantics { contentDescription = "录屏取帧时间" })
                        Button(onClick = { workspace.takeFrame((requestedSeconds * 1_000_000).toLong()) },
                            enabled = !state.busy && !savePending, modifier = Modifier.fillMaxWidth()) { Text("取这一帧") }
                    }
                    state.frame?.let { frame ->
                        Text("原片编辑 · 实际帧 ${frame.presentationTimeUs} µs", style = MaterialTheme.typography.labelLarge)
                        Text("在画面拖出黑色遮挡，或按比例添加。这里仍是本机原片，生成后才能复核和保存。",
                            style = MaterialTheme.typography.bodySmall)
                        FrameEditor(frame.bitmap, selected.masks, !state.busy && !savePending, workspace::addMask)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { maskDialog = true }, enabled = !state.busy && !savePending && selected.masks.size < 20) { Text("按比例添加") }
                            TextButton(onClick = workspace::undoMask, enabled = !state.busy && !savePending && selected.masks.isNotEmpty()) { Text("撤销最后遮挡") }
                        }
                        Text("${selected.masks.size} 块不透明遮挡", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = workspace::makeImage, enabled = !state.busy && !savePending, modifier = Modifier.fillMaxWidth()) {
                            Text("生成遮挡图片")
                        }
                        HorizontalDivider()
                        Text("短视频", style = MaterialTheme.typography.titleMedium)
                        Text("遮挡固定覆盖整段。请覆盖敏感内容的完整运动范围。输出会移除所有原音轨。",
                            style = MaterialTheme.typography.bodySmall)
                        key(selected.source.sourceId) {
                            var start by rememberSaveable { mutableStateOf("0.000") }
                            var end by rememberSaveable { mutableStateOf(seconds(min(meta.durationUs, 3_000_000))) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(value = start, onValueChange = { start = it; workspace.invalidateCandidate() },
                                    label = { Text("起点（秒）") }, singleLine = true, enabled = !state.busy && !savePending,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                                OutlinedTextField(value = end, onValueChange = { end = it; workspace.invalidateCandidate() },
                                    label = { Text("终点（秒）") }, singleLine = true, enabled = !state.busy && !savePending,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                            }
                            val startUs = parseSeconds(start)
                            val endUs = parseSeconds(end)
                            val valid = startUs != null && endUs != null && startUs >= 0 && endUs > startUs &&
                                endUs <= meta.durationUs && endUs - startUs <= 10_000_000
                            Text("每段最多 10 秒", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { workspace.makeVideo(requireNotNull(startUs), requireNotNull(endUs)) },
                                enabled = !state.busy && !savePending && valid, modifier = Modifier.fillMaxWidth()) { Text("生成遮挡视频") }
                        }
                    }
                    TextButton(onClick = { deleteDialog = true }, enabled = !state.busy && !savePending) { Text("删除这段本机素材") }
                }
                state.candidate?.let { candidate ->
                    HorizontalDivider()
                    Text("复核实际输出", style = MaterialTheme.typography.titleLarge)
                    Text("${candidate.width} × ${candidate.height} · ${if (candidate.mimeType == "image/png") "PNG" else "无音轨 MP4"}")
                    if (candidate.mimeType == "image/png") {
                        state.candidateImage?.let { output ->
                            Image(output.asImageBitmap(), contentDescription = "已重新解码的遮挡图片，等待复核",
                                modifier = Modifier.fillMaxWidth().aspectRatio(output.width.toFloat() / output.height))
                        }
                    } else {
                        key(candidate.sha256) { ReviewVideo(candidate, workspace::videoWatched) }
                        if (state.watchedDigest != candidate.sha256) Text("请从头完整播放一次，再确认复核。")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.reviewedDigest == candidate.sha256, onCheckedChange = workspace::review,
                            enabled = !state.busy && !savePending && (candidate.mimeType == "image/png" || state.watchedDigest == candidate.sha256))
                        Text("我已检查实际输出，确认遮挡完整", modifier = Modifier.weight(1f))
                    }
                    Button(onClick = {
                        if (!savePending && workspace.beginSave(candidate.sha256)) {
                            pendingDigest = candidate.sha256
                            savePending = true
                            try {
                                if (candidate.mimeType == "image/png") savePng.launch("tapscene-frame.png")
                                else saveMp4.launch("tapscene-transition.mp4")
                            } catch (_: android.content.ActivityNotFoundException) {
                                savePending = false
                                workspace.cancelSavePicker()
                                workspace.message("系统没有可用的文件保存工具")
                            }
                        }
                    }, enabled = !state.busy && !savePending && state.reviewedDigest == candidate.sha256, modifier = Modifier.fillMaxWidth()) {
                        Text("保存到文件")
                    }
                    Text("保存的是本次图片或短视频。演示图与离线观看包尚未接入。", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false },
            title = { Text("删除本机素材？") },
            text = { Text("这段录屏及其已保存的取帧位置、遮挡会从本机移除，无法撤销。系统中原来的文件和已导出的文件不受影响。") },
            confirmButton = { TextButton(onClick = { deleteDialog = false; workspace.deleteSelected() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
        if (maskDialog) MaskDialog(onDismiss = { maskDialog = false }) { mask ->
            maskDialog = false
            workspace.addMask(mask)
        }
    }
}

@Composable
private fun FrameEditor(bitmap: Bitmap, masks: List<OpaqueMask>, enabled: Boolean, onMask: (OpaqueMask) -> Unit) {
    var start by remember(bitmap) { mutableStateOf<Offset?>(null) }
    var end by remember(bitmap) { mutableStateOf<Offset?>(null) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
            Image(bitmap.asImageBitmap(), contentDescription = "仅本机原片，可添加遮挡", modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().pointerInput(bitmap, enabled, masks.size) {
                if (enabled) detectDragGestures(
                    onDragStart = { start = it; end = it },
                    onDragCancel = { start = null; end = null },
                    onDragEnd = {
                        val from = start
                        val to = end
                        if (from != null && to != null && abs(from.x - to.x) >= 4 && abs(from.y - to.y) >= 4) {
                            val left = (min(from.x, to.x) / size.width).coerceIn(0f, 1f)
                            val top = (min(from.y, to.y) / size.height).coerceIn(0f, 1f)
                            val right = (max(from.x, to.x) / size.width).coerceIn(0f, 1f)
                            val bottom = (max(from.y, to.y) / size.height).coerceIn(0f, 1f)
                            if (right > left && bottom > top) onMask(OpaqueMask(left, top, right, bottom))
                        }
                        start = null; end = null
                    },
                    onDrag = { change, _ -> change.consume(); end = change.position },
                )
            }) {
                masks.forEach { mask ->
                    drawRect(Color.Black, Offset(mask.left * size.width, mask.top * size.height),
                        Size((mask.right - mask.left) * size.width, (mask.bottom - mask.top) * size.height))
                }
                val from = start
                val to = end
                if (from != null && to != null) drawRect(Color.Black,
                    Offset(min(from.x, to.x), min(from.y, to.y)), Size(abs(from.x - to.x), abs(from.y - to.y)))
            }
        }
    }
}

@Composable
private fun MaskDialog(onDismiss: () -> Unit, onConfirm: (OpaqueMask) -> Unit) {
    var left by remember { mutableStateOf("10") }
    var top by remember { mutableStateOf("10") }
    var right by remember { mutableStateOf("60") }
    var bottom by remember { mutableStateOf("25") }
    val rectangle = runCatching {
        OpaqueMask(left.toFloat() / 100, top.toFloat() / 100, right.toFloat() / 100, bottom.toFloat() / 100)
    }.getOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加遮挡（画面百分比）") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("左", left) { value: String -> left = value }, Triple("上", top) { value: String -> top = value },
                Triple("右", right) { value: String -> right = value }, Triple("下", bottom) { value: String -> bottom = value })
                .forEach { (label, value, change) ->
                    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
        }
    }, confirmButton = { TextButton(onClick = { rectangle?.let(onConfirm) }, enabled = rectangle != null) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ReviewVideo(candidate: SafeMediaWriter.CandidateMedia, onWatched: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(candidate.sha256) { VideoView(context) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    DisposableEffect(player, owner) {
        player.setOnPreparedListener { ready = true }
        player.setOnCompletionListener { onWatched(candidate.sha256) }
        player.setOnErrorListener { _, _, _ -> error = true; true }
        player.setVideoPath(candidate.file.path)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.stopPlayback() }
    }
    AndroidView(factory = { player }, modifier = Modifier.fillMaxWidth().aspectRatio(candidate.width.toFloat() / candidate.height))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { player.seekTo(0); player.start() }, enabled = ready && !error) { Text("从头播放") }
        TextButton(onClick = { if (player.isPlaying) player.pause() else player.start() }, enabled = ready && !error) { Text("暂停 / 继续") }
    }
    if (error) Text("生成视频无法播放，请重新生成；当前不能确认复核。")
}

private fun seconds(value: Long): String = String.format(Locale.ROOT, "%.3f", value / 1_000_000.0)
private fun parseSeconds(value: String): Long? = value.toDoubleOrNull()
    ?.takeIf { it.isFinite() && it in 0.0..180.0 }?.let { (it * 1_000_000).toLong() }
