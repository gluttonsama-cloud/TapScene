package com.tapscene.ui.shell

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tapscene.data.ReleaseCandidate
import com.tapscene.data.ReleaseSummary
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.LocalVideoPlayback
import com.tapscene.ui.ReleaseUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Navigation reflects persisted confirmations; it never grants any review or traversal credit. */
internal data class ReleaseReviewCategory(val section: Int, val label: String, val complete: Boolean)

internal fun releaseReviewCategories(candidate: ReleaseCandidate): List<ReleaseReviewCategory> {
    val scene = candidate.scene
    return listOf(
        ReleaseReviewCategory(0, "画面逐项", scene.states.isNotEmpty() &&
            candidate.reviewedStateIds == scene.states.map { it.id }.toSet()),
        ReleaseReviewCategory(1, "整段视频", candidate.reviewedTransitionAssetIds == scene.assets
            .filter { it.role == ViewerScene.Asset.ROLE_TRANSITION }.map { it.id }.toSet()),
        ReleaseReviewCategory(5, "区域裁片", candidate.reviewedRegionIds == scene.regions.map { it.id }.toSet()),
        ReleaseReviewCategory(2, "项目文字", candidate.summaryReviewed),
        ReleaseReviewCategory(3, "包清单", candidate.fileListReviewed),
        ReleaseReviewCategory(4, "实际试走", candidate.completedPath &&
            candidate.visitedEdgeIds == scene.edges.map { it.id }.toSet()),
    )
}

internal fun nextIncompleteReviewCategory(categories: List<ReleaseReviewCategory>, section: Int): ReleaseReviewCategory? {
    if (categories.isEmpty()) return null
    val current = categories.indexOfFirst { it.section == section }
    return (1..categories.size).asSequence().map { categories[(current + it) % categories.size] }
        .firstOrNull { !it.complete }
}

/** All review decisions refer to the host's immutable candidate, never the changing draft. */
@Composable
fun ReleaseReviewContent(
    state: ReleaseUiState,
    currentDraftRevision: Long?,
    onBack: () -> Unit,
    onReviewStep: (String) -> Unit,
    onConfirmState: () -> Unit,
    onSummary: () -> Unit,
    onFileList: () -> Unit,
    onTryPath: () -> Unit,
    onSeal: () -> Unit,
    onRebuild: () -> Unit,
    onReviewVideo: (String) -> Unit = {},
    onVideoCompleted: (Long) -> Unit = {},
    onVideoInterrupted: (Long) -> Unit = {},
    onVideoReplay: (Long) -> Unit = {},
    onConfirmVideo: () -> Unit = {},
    onCloseVideo: () -> Unit = {},
    initialSection: Int = 0,
    onReviewRegion: (String) -> Unit = {},
    onConfirmRegion: () -> Unit = {},
) {
    val candidate = state.candidate
    var section by rememberSaveable(candidate?.id) { mutableStateOf(initialSection.coerceIn(0, 5)) }
    var showSealConfirmation by rememberSaveable(candidate?.id) { mutableStateOf(false) }
    var showImage by rememberSaveable(candidate?.id, state.reviewStateId) { mutableStateOf(false) }
    var showHotspots by rememberSaveable(candidate?.id) { mutableStateOf(false) }
    val categoryScroll = rememberLazyListState()
    val closeVideo by rememberUpdatedState(onCloseVideo)
    DisposableEffect(candidate?.id) { onDispose { closeVideo() } }
    LaunchedEffect(section, candidate?.id) {
        if (section == 1 && state.reviewVideo == null) candidate?.scene?.assets
            ?.firstOrNull { it.role == ViewerScene.Asset.ROLE_TRANSITION && it.id !in candidate.reviewedTransitionAssetIds }
            ?.let { onReviewVideo(it.id) }
    }
    BackHandler(enabled = !state.busy) { onCloseVideo(); onBack() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val detailMaxHeight = (maxHeight * .24f).coerceIn(96.dp, 190.dp)
        val scrollMedia = maxHeight < 500.dp && (section == 0 || section == 1)
        Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
            ShellTopBar(candidate?.let { "复核修订 ${it.projectRevision}" } ?: "成品逐项复核", { if (!state.busy) { onCloseVideo(); onBack() } }, actions = {
                if (candidate != null) Text(
                    when {
                        currentDraftRevision == null -> "来源项目已移除"
                        currentDraftRevision != candidate.projectRevision -> "当前草稿 $currentDraftRevision"
                        else -> "固定候选"
                    }, modifier = Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted,
                )
            })
            if (candidate == null) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ReleaseStatus(state)
                    ScreenEmpty("还没有待复核成品", "回到交付检查，生成固定修订的图片、文字和点击路径。", "返回交付检查", onBack)
                }
                return@Column
            }
            val scene = candidate.scene
            val reviewedCount = scene.states.count { it.id in candidate.reviewedStateIds }
            val categories = releaseReviewCategories(candidate)
            val currentCategory = categories.single { it.section == section }
            val nextCategory = nextIncompleteReviewCategory(categories, section)
            val allComplete = categories.all { it.complete }
            LaunchedEffect(section, candidate.id) {
                categoryScroll.scrollToItem(categories.indexOfFirst { it.section == section })
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), state = categoryScroll) {
                items(categories, key = { it.section }) { category ->
                    TextButton(
                        onClick = { if (category.section != section) { onCloseVideo(); section = category.section } }, enabled = !state.busy, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.heightIn(min = 48.dp).then(if (section == category.section) Modifier.background(ShellColors.AccentSoft, RoundedCornerShape(8.dp)) else Modifier),
                    ) { Text("${if (category.complete) "✓ " else ""}${category.label}", color = if (section == category.section) ShellColors.Accent else ShellColors.Muted) }
                }
            }
            ShellDivider()
            Column(Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .then(if (scrollMedia) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                when (section) {
                    0 -> {
                        val step = scene.states.firstOrNull { it.id == state.reviewStateId } ?: scene.states.firstOrNull()
                        val bitmap = state.reviewBitmap.takeIf { state.reviewStateId == step?.id }
                        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(scene.states, key = { _, item -> item.id }) { index, item ->
                                TextButton(onClick = { onReviewStep(item.id) }, enabled = !state.busy, shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("${index + 1} ${if (item.id in candidate.reviewedStateIds) "已确认" else "待复核"}",
                                        color = if (item.id == step?.id) ShellColors.Accent else ShellColors.Muted)
                                }
                            }
                        }
                        val hotspots = scene.hotspots.filter { it.stateId == step?.id }
                        ReleaseImageCanvas(bitmap, if (showHotspots) hotspots else emptyList(),
                            (if (scrollMedia) Modifier.height(180.dp) else Modifier.weight(1f)).fillMaxWidth().padding(horizontal = 16.dp), busy = state.busy,
                            imageDescription = "候选实际输出：${step?.title.orEmpty()}")
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { showHotspots = !showHotspots }, enabled = hotspots.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (showHotspots) "隐藏热点标记" else "显示热点标记")
                            }
                            TextButton(onClick = { showImage = true }, enabled = bitmap.isUsable(), modifier = Modifier.heightIn(min = 48.dp)) { Text("放大画面") }
                        }
                        key(step?.id) {
                            Column(Modifier.fillMaxWidth().heightIn(max = detailMaxHeight).verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (step == null) Text("候选中没有可复核的步骤。", color = MaterialTheme.colorScheme.error)
                                else {
                                    Text(step.title, style = MaterialTheme.typography.titleMedium)
                                    Text(step.description.ifBlank { "此步骤未填写讲解。" }, style = MaterialTheme.typography.bodyMedium,
                                        color = if (step.description.isBlank()) ShellColors.Muted else ShellColors.Ink)
                                    if (step.terminal) Text("结束步骤", style = MaterialTheme.typography.labelMedium, color = ShellColors.Accent)
                                    hotspots.forEachIndexed { index, hotspot ->
                                        Text("热点 ${index + 1} · ${hotspot.label}", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                                    }
                                    scene.edges.filter { it.fromStateId == step.id }.forEach { edge ->
                                        ShellActionRow(edge.label, "${if (edge.trigger == "continue") "画布外按钮" else "画面热点"} · ${edgeTarget(scene, edge)}")
                                    }
                                }
                                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ShellColors.Accent) }
                            }
                        }
                        if (!currentCategory.complete) Button(onClick = onConfirmState, enabled = !state.busy && step != null && bitmap.isUsable(), shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
                            Text(when {
                                step?.id in candidate.reviewedStateIds -> "本项已确认 · 继续"
                                else -> "确认画面、文字与动作"
                            })
                        }
                        if (showImage && bitmap.isUsable()) ReviewImageDialog(bitmap!!, step?.title.orEmpty()) { showImage = false }
                    }
                    1 -> {
                        val videos = scene.assets.filter { it.role == ViewerScene.Asset.ROLE_TRANSITION }
                        val review = state.reviewVideo?.takeIf { it.candidateId == candidate.id && it.contentDigest == candidate.contentDigest }
                        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            itemsIndexed(videos, key = { _, item -> item.id }) { index, item ->
                                TextButton(onClick = { onReviewVideo(item.id) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("${index + 1} ${if (item.id in candidate.reviewedTransitionAssetIds) "已确认" else "待复核"}",
                                        color = if (review?.assetId == item.id) ShellColors.Accent else ShellColors.Muted)
                                }
                            }
                        }
                        val video = review?.video
                        val file = video?.file
                        if (video != null && file != null && !video.failed) {
                            LocalVideoPlayback(file, video.width, video.height, video.runId,
                                (if (scrollMedia) Modifier.height(180.dp) else Modifier.weight(1f)).fillMaxWidth().padding(horizontal = 16.dp),
                                onCompleted = onVideoCompleted, onError = onVideoInterrupted,
                                onInterrupted = onVideoInterrupted, onReplay = onVideoReplay)
                        } else Box((if (scrollMedia) Modifier.height(180.dp) else Modifier.weight(1f)).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(when {
                                videos.isEmpty() -> "这份成品没有视频过渡"
                                video?.failed == true -> "播放中断，请从头复核"
                                video == null -> "选择一段视频开始复核"
                                else -> "正在检查实际视频"
                            }, color = ShellColors.Muted)
                        }
                        if (review != null) {
                            val edges = scene.edges.filter { it.transitionAssetId == review.assetId }
                            Text(edges.joinToString(" · ") { it.label }, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
                            Text(if (review.watchedCompletely) "已完整播放，请确认可见内容" else "完整播放后，才能确认本段视频",
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                            if (video?.failed == true) OutlinedButton(onClick = { onVideoReplay(video.runId) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)) { Text("从头重播") }
                            if (!currentCategory.complete) Button(onClick = onConfirmVideo,
                                enabled = !state.busy && review.watchedCompletely && review.assetId !in candidate.reviewedTransitionAssetIds,
                                shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
                                Text(if (review.assetId in candidate.reviewedTransitionAssetIds) "本段已确认" else "确认整段视频")
                            }
                        }
                    }
                    2 -> ReleaseScrollPanel {
                        SectionHeader("随包交付的项目文字", "检查接收者会看到的标题和开场说明。")
                        ShellLabelValue("标题", scene.title)
                        ShellDivider()
                        Text("开场说明", style = MaterialTheme.typography.labelLarge, color = ShellColors.Muted)
                        Text(scene.goal.ifBlank { "未填写开场说明。" }, style = MaterialTheme.typography.bodyLarge)
                        ShellDivider()
                        ShellLabelValue("起点", scene.states.firstOrNull { it.id == scene.startStateId }?.title ?: "起点不可用")
                        ShellLabelValue("内容摘要", candidate.contentDigest)
                        Button(onClick = onSummary, enabled = !state.busy && !candidate.summaryReviewed, shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (candidate.summaryReviewed) "项目文字已确认" else "确认项目文字")
                        }
                        ReleaseStatus(state, showProgress = false)
                    }
                    3 -> ReleaseScrollPanel { CandidateFileList(candidate, state.busy, onFileList) }
                    5 -> ReleaseScrollPanel {
                        SectionHeader("实际区域裁片", "逐个查看固定输出与名称；可见像素来自已复核安全图。")
                        if (scene.regions.isEmpty()) Text("这个固定版本没有区域裁片。", color = ShellColors.Muted)
                        scene.regions.forEach { region ->
                            ShellActionRow(region.name, "${scene.states.firstOrNull { it.id == region.stateId }?.title} · ${region.bbox.width} × ${region.bbox.height}",
                                if (region.id in candidate.reviewedRegionIds) "已确认" else "查看", onClick = { onReviewRegion(region.id) }, enabled = !state.busy)
                        }
                        val region = scene.regions.firstOrNull { it.id == state.reviewRegionId }
                        val bitmap = state.reviewRegionBitmap
                        if (region != null && bitmap.isUsable()) {
                            Text(region.name, style = MaterialTheme.typography.titleMedium)
                            Image(bitmap!!.asImageBitmap(), "实际固定区域裁片", modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 300.dp), contentScale = ContentScale.Fit)
                            Text("组：${region.group ?: "未分组"} · 层次 ${region.zIndex} · 锚点 ${region.anchor.x}, ${region.anchor.y}", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = onConfirmRegion, enabled = !state.busy && region.id !in candidate.reviewedRegionIds, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (region.id in candidate.reviewedRegionIds) "裁片已确认" else "确认实际裁片与区域文字") }
                        }
                    }
                    else -> ReleaseScrollPanel {
                        val visitedCount = scene.edges.count { it.id in candidate.visitedEdgeIds }
                        val videos = scene.assets.filter { it.role == ViewerScene.Asset.ROLE_TRANSITION }
                        SectionHeader("实际试走", "只记录这份固定候选中实际选过的动作。")
                        ShellLabelValue("动作覆盖", "$visitedCount / ${scene.edges.size}")
                        ShellLabelValue("从起点到结束", if (candidate.completedPath) "已完成" else "待完成")
                        OutlinedButton(onClick = onTryPath, enabled = !state.busy, shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开候选试走") }
                        val unvisited = scene.edges.filter { it.id !in candidate.visitedEdgeIds }
                        if (unvisited.isNotEmpty()) {
                            Text("尚未试走", style = MaterialTheme.typography.labelLarge, color = ShellColors.Muted)
                            unvisited.forEach { edge ->
                                ShellActionRow(edge.label, "${scene.states.firstOrNull { it.id == edge.fromStateId }?.title.orEmpty()} → ${edgeTarget(scene, edge)}")
                            }
                        }
                        ShellDivider()
                        SectionHeader("封存检查")
                        ShellLabelValue("步骤画面与文字", "$reviewedCount / ${scene.states.size} 已确认")
                        if (videos.isNotEmpty()) ShellLabelValue("整段视频", "${videos.count { it.id in candidate.reviewedTransitionAssetIds }} / ${videos.size} 已确认")
                        if (scene.regions.isNotEmpty()) ShellLabelValue("实际区域裁片", "${scene.regions.count { it.id in candidate.reviewedRegionIds }} / ${scene.regions.size} 已确认")
                        ShellLabelValue("项目文字", if (candidate.summaryReviewed) "已确认" else "待确认")
                        ShellLabelValue("实际包清单", if (candidate.fileListReviewed) "已确认" else "待确认")
                        if (currentDraftRevision != candidate.projectRevision) StatusNote(
                            if (currentDraftRevision == null) "来源项目已不在本机。仍可复核并封存这份已固定的候选。"
                            else "草稿已有后续修改。封存只包含修订 ${candidate.projectRevision} 的内容。")
                        if (!allComplete) Text("完成所有复核、动作试走与一条完整路径后可封存。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                        if (currentDraftRevision != null) TextButton(onClick = onRebuild, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("从当前草稿重新生成")
                        }
                        ReleaseStatus(state, showProgress = false)
                    }
                }
            }
            ShellDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("已完成 ${categories.count { it.complete }}/${categories.size} 项 · ${currentCategory.label}${if (currentCategory.complete) "已完成" else "待复核"}",
                    style = MaterialTheme.typography.labelMedium, color = ShellColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (allComplete || currentCategory.complete) {
                    Button(
                        onClick = {
                            // No review or traversal credit is granted here. Sealing still uses the confirmation dialog.
                            when {
                                allComplete && section == 4 -> showSealConfirmation = true
                                allComplete -> { onCloseVideo(); section = 4 }
                                nextCategory != null -> { onCloseVideo(); section = nextCategory.section }
                            }
                        },
                        enabled = !state.busy, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(when {
                            allComplete && section == 4 -> "封存修订 ${candidate.projectRevision}"
                            allComplete -> "查看封存检查"
                            else -> "下一项：${nextCategory?.label.orEmpty()}"
                        })
                    }
                }
            }
        }
    }
    if (showSealConfirmation && candidate != null) {
        AlertDialog(
            onDismissRequest = { showSealConfirmation = false },
            title = { Text("封存修订 ${candidate.projectRevision}？") },
            text = { Text(when {
                currentDraftRevision == null -> "仅封存正在复核的修订 ${candidate.projectRevision}。来源项目已不在本机，这份固定内容仍可独立保存。"
                currentDraftRevision != candidate.projectRevision -> "仅封存正在复核的修订 ${candidate.projectRevision}，草稿 $currentDraftRevision 的修改不包含。"
                else -> "封存后内容固定。之后的草稿修改需要重新生成和复核。"
            }) },
            confirmButton = { TextButton(onClick = { showSealConfirmation = false; onSeal() }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认封存") } },
            dismissButton = { TextButton(onClick = { showSealConfirmation = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续复核") } },
            shape = RoundedCornerShape(8.dp),
        )
    }
}

@Composable
private fun ColumnScope.ReleaseScrollPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun CandidateFileList(candidate: ReleaseCandidate, busy: Boolean, onConfirm: () -> Unit) {
    val files = remember(candidate.contentDigest) { runCatching { ViewerPackageCodec.fileList(candidate.scene) }.getOrNull() }
    val manifestLength = remember(candidate.contentDigest) { runCatching { ViewerPackageCodec.manifestBytes(candidate.scene).size.toLong() }.getOrNull() }
    SectionHeader("实际包清单", "纯数据、固定图片与无声过渡。逐项检查文件范围，再确认。")
    if (files == null || manifestLength == null) {
        StatusNote("包清单暂时无法读取。返回后重试，当前不能确认文件范围。")
        return
    }
    ShellLabelValue("文件总数", "${files.size + 1} 项")
    ShellLabelValue("包内文件体积", formatShellBytes(files.sumOf { it.byteLength } + manifestLength))
    Text("这是未压缩内容体积；保存后的文件大小以实际写出结果为准。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
    ShellDivider()
    ShellActionRow("manifest.json", "格式、内容摘要与文件校验清单", formatShellBytes(manifestLength))
    files.forEach { file ->
        ShellDivider()
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.path, style = MaterialTheme.typography.bodyMedium)
            Text(formatShellBytes(file.byteLength), style = MaterialTheme.typography.labelMedium, color = ShellColors.Muted)
            Text("SHA-256 · ${file.sha256}", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        }
    }
    StatusNote("包内不包含原片、原片路径和编辑历史。实际图片、视频与可见文字仍需逐项复核。")
    Button(onClick = onConfirm, enabled = !busy && !candidate.fileListReviewed, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (candidate.fileListReviewed) "包清单已确认" else "确认包清单") }
}

/** The library is populated only by successfully registered releases and imported packages. */
@Composable
fun ReleaseLibraryContent(
    state: ReleaseUiState,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onExport: (String) -> Unit,
    onDelete: (ReleaseSummary) -> Unit,
    onSettings: () -> Unit,
    onReviewCandidate: (String) -> Unit = {},
    onDiscardCandidate: (ReleaseCandidate) -> Unit = {},
    onAi: ((String) -> Unit)? = null,
    onHosting: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
        ShellTopBar("演示库", actions = {
            TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("设置") }
        })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("离线观看", style = MaterialTheme.typography.titleMedium)
                        Text("${state.releases.size} 个固定版本 · 保存在本机", style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted)
                    }
                    Button(onClick = onImport, enabled = !state.busy, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("导入包") }
                }
                ReleaseStatus(state)
                if (onHosting != null) ShellActionRow("开发托管", "明确选择本机封存版本，包括旧版。", onClick = onHosting, enabled = !state.busy)
            }
            if (state.pendingCandidates.isNotEmpty()) {
                item { SectionHeader("待复核", "已固定的候选可继续复核，草稿后续修改不改变此副本。") }
                items(state.pendingCandidates, key = { "candidate-${it.id}" }) { candidate ->
                    ShellDivider()
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ShellActionRow(candidate.scene.title,
                            "修订 ${candidate.projectRevision} · 画面已确认 ${candidate.scene.states.count { it.id in candidate.reviewedStateIds }}/${candidate.scene.states.size}",
                            "继续复核", onClick = { onReviewCandidate(candidate.id) }, enabled = !state.busy)
                        TextButton(onClick = { onDiscardCandidate(candidate) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("删除未封存副本", color = ShellColors.Muted)
                        }
                    }
                }
                item {
                    ShellDivider()
                    Text("已保存版本", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (state.releases.isEmpty()) item {
                ScreenEmpty("还没有离线演示", "导入 TapScene 观看包，或在项目中完成复核与封存。图片、讲解和点击路径一起保存在本机。")
            }
            items(state.releases, key = { it.id }) { release ->
                ShellDivider()
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShellActionRow(release.title, "${release.stepCount} 个步骤 · 包内 ${formatShellBytes(release.byteLength)}", "打开",
                        onClick = { onOpen(release.id) }, enabled = !state.busy)
                    Text(if (release.origin == "local") "本机封存 · ${formatReleaseDate(release.sealedAt)}"
                        else "导入包 · 完整性通过 · ${formatReleaseDate(release.sealedAt)}",
                        style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    Text("版本 ${release.id.take(12)}", style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted)
                    if (release.origin != "local") Text("完整性校验不代表作者身份或隐私内容已获认证。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    if (onAi != null) TextButton(onClick = { onAi(release.id) }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("配置 AI 数据包") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { onExport(release.id) }, enabled = !state.busy, shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("保存到文件") }
                        TextButton(onClick = { onDelete(release) }, enabled = !state.busy,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("删除本机副本", color = ShellColors.Muted) }
                    }
                }
            }
            item {
                ShellDivider()
                Text("离线演示与创作草稿分开保存。删除本机副本不会收回已发出的文件。",
                    modifier = Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            }
        }
    }
}

@Composable
fun OfflineImportContent(state: ReleaseUiState, onBack: () -> Unit, onChoose: () -> Unit) {
    BackHandler(enabled = !state.busy) { onBack() }
    val imported = state.releases.firstOrNull { it.id == state.lastImportedId }
    Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
        ShellTopBar("导入离线演示", { if (!state.busy) onBack() })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when {
                state.busy -> {
                    SectionHeader("正在导入", state.stage ?: "正在读取与校验所选文件")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("完整校验并写入后，才会加入演示库。", style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
                }
                imported != null -> {
                    SectionHeader("已加入演示库", imported.title)
                    ShellLabelValue("步骤", "${imported.stepCount} 个")
                    ShellLabelValue("包内文件", formatShellBytes(imported.byteLength))
                    StatusNote("文件完整性与内容结构校验通过。这不代表作者身份或图片、文字的隐私内容已获认证。")
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted) }
                    Button(onClick = onBack, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回演示库") }
                }
                state.message != null -> {
                    SectionHeader("导入未完成")
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Text("现有演示仍保留。可重新选择文件后再试。", style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
                }
                else -> ScreenEmpty("选择 TapScene 观看包", "导入图片、无声视频过渡和点击路径。完整导入后可离线观看。")
            }
            if (!state.busy) OutlinedButton(onClick = onChoose, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (imported != null) "再导入一个包" else if (state.message != null) "重新选择观看包" else "选择观看包")
            }
            ShellDivider()
            SectionHeader("导入前会检查")
            ShellLabelValue("文件", "大小、摘要与安全路径")
            ShellLabelValue("内容", "图片、无声视频、步骤与动作目标")
            Text("不执行脚本，不读取外部媒体地址。导入后作为固定演示观看。",
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        }
    }
}

/** Navigation and coverage belong to the playback reducer; the screen only emits real actions. */
@Composable
fun ReleasePlayerContent(
    state: ReleaseUiState,
    onBack: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onEdge: (String) -> Unit,
    onPrevious: () -> Unit,
    onRestart: () -> Unit,
    onDismissMatches: () -> Unit,
    onVideoCompleted: (Long) -> Unit = {},
    onVideoFailed: (Long) -> Unit = {},
    onVideoRetry: (Long) -> Unit = {},
    onVideoSkip: () -> Unit = {},
) {
    val player = state.player
    var showExplanation by rememberSaveable(player?.scene?.releaseId, player?.currentStateId) { mutableStateOf(false) }
    var showIntroduction by rememberSaveable(player?.scene?.releaseId) { mutableStateOf(false) }
    BackHandler(enabled = !state.busy || player?.video != null) {
        if (!state.busy || player?.video != null) {
            if (player?.matchingHotspotIds?.isNotEmpty() == true) onDismissMatches() else onBack()
        }
    }
    if (player == null) {
        Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
            ShellTopBar("离线播放", onBack)
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                ReleaseStatus(state)
                ScreenEmpty("尚未载入演示", "请回到演示库选择一个固定版本。", "返回演示库", onBack)
            }
        }
        return
    }
    val scene = player.scene
    val current = player.currentState
    val hotspots = scene.hotspots.filter { it.stateId == player.currentStateId }
    val outgoing = scene.edges.filter { it.fromStateId == player.currentStateId }
    val actionsEnabled = !state.busy && player.video == null && !player.ended && current != null && state.playerBitmap.isUsable()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelMaxHeight = (maxHeight * .30f).coerceIn(72.dp, 240.dp)
        val explanationMaxHeight = (maxHeight * .16f).coerceIn(56.dp, 128.dp)
        Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (player.candidateId != null) "候选试走" else scene.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (player.candidateId != null) "待封存 · 记录实际动作覆盖" else "固定版本 ${scene.releaseId.take(8)} · 离线",
                        style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted)
                }
                TextButton(onClick = { showIntroduction = true }, enabled = player.video == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("说明") }
                TextButton(onClick = onBack, enabled = !state.busy || player.video != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("退出") }
            }
            ShellDivider()
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showExplanation = !showExplanation }
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(current?.title ?: "步骤不可用", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (showExplanation) "收起讲解 ↑" else "讲解 ↓", style = MaterialTheme.typography.labelMedium, color = ShellColors.Muted)
            }
            if (showExplanation) Column(Modifier.fillMaxWidth().heightIn(max = explanationMaxHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(current?.description?.ifBlank { "此步骤没有讲解。" } ?: "当前步骤无法读取。", style = MaterialTheme.typography.bodyMedium)
                if (player.candidateId != null) Text("已试走 ${scene.edges.count { it.id in player.visitedEdgeIds }} / ${scene.edges.size} 个动作",
                    style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted)
            }
            val video = player.video
            val videoFile = video?.file
            if (video != null && videoFile != null && !video.failed) {
                LocalVideoPlayback(videoFile, video.width, video.height, video.runId,
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    onCompleted = onVideoCompleted, onError = onVideoFailed, onInterrupted = onVideoFailed, onReplay = onVideoRetry)
            } else ReleaseImageCanvas(state.playerBitmap, if (player.ended || video != null) emptyList() else hotspots,
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), enabled = actionsEnabled, busy = state.busy,
                imageDescription = current?.title ?: "离线演示画面", onTap = onTap)
            Column(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    video != null -> {
                        Text(if (video.failed) "过渡播放失败" else "正在播放过渡", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (video.failed) OutlinedButton(onClick = { onVideoRetry(video.runId) }, enabled = !state.busy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("重试") }
                            OutlinedButton(onClick = onVideoSkip, enabled = !state.busy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (video.failed) "静态前进" else "跳过过渡") }
                        }
                    }
                    current == null -> Text("当前步骤不可用。返回演示库后重新打开。", color = MaterialTheme.colorScheme.error)
                    player.ended -> {
                        Text("演示结束", style = MaterialTheme.typography.titleMedium)
                        player.endLabel?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (player.candidateId != null && player.completedFromStart) Text("已完成一次从起点到结束的试走。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    }
                    outgoing.isEmpty() -> Text("这一步没有可用动作。", style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
                    else -> {
                        outgoing.filter { it.trigger == "continue" }.forEach { edge ->
                            Button(onClick = { onEdge(edge.id) }, enabled = actionsEnabled, shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(edge.label) }
                        }
                        if (outgoing.any { it.trigger == "tap" }) Text("点击画面，或选择文字动作", style = MaterialTheme.typography.labelMedium, color = ShellColors.Muted)
                        outgoing.filter { it.trigger == "tap" }.forEach { edge ->
                            val index = hotspots.indexOfFirst { it.id == edge.hotspotId }
                            OutlinedButton(onClick = { onEdge(edge.id) }, enabled = actionsEnabled, shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    if (index >= 0) Text("${index + 1}", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelLarge)
                                    Text(edge.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text("→", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ShellColors.Accent) }
            }
            ShellDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onPrevious, enabled = (!state.busy || player.video != null) && player.canGoBack, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上一步") }
                TextButton(onClick = onRestart, enabled = !state.busy || player.video != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("重来") }
            }
        }
    }
    if (showIntroduction) AlertDialog(
        onDismissRequest = { showIntroduction = false }, title = { Text(scene.title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(scene.goal.ifBlank { "此演示未填写开场说明。" })
            Text("点击只切换演示画面，不操作原 App。上一步沿本次实际访问历史返回。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        } },
        confirmButton = { TextButton(onClick = { showIntroduction = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续观看") } },
        shape = RoundedCornerShape(8.dp),
    )
    if (player.matchingHotspotIds.isNotEmpty()) AlertDialog(
        onDismissRequest = onDismissMatches, title = { Text("选择这个区域的动作") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("热点有重叠。选定动作才会继续；关闭后留在当前画面。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            outgoing.filter { it.hotspotId in player.matchingHotspotIds }.forEach { edge ->
                OutlinedButton(onClick = { onEdge(edge.id) }, enabled = actionsEnabled, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(edge.label) }
            }
        } },
        confirmButton = { TextButton(onClick = onDismissMatches, modifier = Modifier.heightIn(min = 48.dp)) { Text("留在当前画面") } },
        shape = RoundedCornerShape(8.dp),
    )
}

/** Fitted image and overlay share bounds; gestures never include the letterboxed margins. */
@Composable
private fun ReleaseImageCanvas(
    bitmap: Bitmap?,
    hotspots: List<ViewerScene.Hotspot>,
    modifier: Modifier = Modifier,
    enabled: Boolean = false,
    busy: Boolean = false,
    imageDescription: String,
    onTap: ((Float, Float) -> Unit)? = null,
) {
    val currentTap by rememberUpdatedState(onTap)
    BoxWithConstraints(modifier.background(ShellColors.Quiet).border(1.dp, ShellColors.Divider), contentAlignment = Alignment.Center) {
        if (!bitmap.isUsable()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (busy) "正在读取画面" else "画面暂不可用", style = MaterialTheme.typography.titleSmall)
                Text(if (busy) "读取完成后可继续。" else "返回后重新打开，或重新选择此步骤。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            }
            return@BoxWithConstraints
        }
        val actualBitmap = bitmap!!
        val ratio = actualBitmap.width.toFloat() / actualBitmap.height
        val fittedWidth = minOf(maxWidth, maxHeight * ratio)
        val fittedHeight = fittedWidth / ratio
        val image = remember(actualBitmap) { actualBitmap.asImageBitmap() }
        val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.toArgb(); textAlign = Paint.Align.CENTER; isFakeBoldText = true } }
        Box(Modifier.size(fittedWidth, fittedHeight)) {
            Image(image, imageDescription, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize().pointerInput(actualBitmap, enabled) {
                if (enabled) detectTapGestures { point ->
                    if (size.width > 0 && size.height > 0) currentTap?.invoke(
                        (point.x / size.width).coerceIn(0f, 1f), (point.y / size.height).coerceIn(0f, 1f))
                }
            }.semantics { contentDescription = if (enabled) "可点击热点；相同动作也在画面下方" else "实际输出画面" }) {
                hotspots.forEachIndexed { index, hotspot ->
                    val rect = hotspot.rect
                    val position = Offset(rect.x.toFloat() * size.width, rect.y.toFloat() * size.height)
                    val dimensions = Size(rect.width.toFloat() * size.width, rect.height.toFloat() * size.height)
                    drawRect(ShellColors.Accent.copy(alpha = .10f), position, dimensions)
                    drawRect(ShellColors.Accent, position, dimensions, style = Stroke(2.dp.toPx()))
                    val radius = 10.dp.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
                    val center = Offset((position.x + radius).coerceIn(radius, size.width - radius),
                        (position.y + radius).coerceIn(radius, size.height - radius))
                    drawCircle(ShellColors.Accent, radius, center)
                    labelPaint.textSize = 11.dp.toPx()
                    drawContext.canvas.nativeCanvas.drawText("${index + 1}", center.x,
                        center.y - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
                }
            }
        }
    }
}

@Composable
private fun ReviewImageDialog(bitmap: Bitmap, title: String, onClose: () -> Unit) {
    var scale by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
            ShellTopBar(title, onClose, actions = {
                TextButton(onClick = { scale = 1f; offset = Offset.Zero }, modifier = Modifier.heightIn(min = 48.dp)) { Text("复位") }
            })
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    val width = minOf(size.width.toFloat(), size.height * bitmap.width.toFloat() / bitmap.height)
                    val height = width * bitmap.height / bitmap.width
                    val limitX = width * (scale - 1f) / 2f
                    val limitY = height * (scale - 1f) / 2f
                    val moved = offset + pan
                    offset = Offset(moved.x.coerceIn(-limitX, limitX), moved.y.coerceIn(-limitY, limitY))
                }
            }, contentAlignment = Alignment.Center) {
                Image(image, "候选实际图片，可双指缩放", Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                }, contentScale = ContentScale.Fit)
            }
            Text("双指缩放，拖动画面。这里只查看实际图片。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        }
    }
}

@Composable
private fun ReleaseStatus(state: ReleaseUiState, showProgress: Boolean = true) {
    if (state.busy && showProgress) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        state.stage?.let { Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted) }
    }
    state.message?.let { Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = ShellColors.Accent) }
}

private fun Bitmap?.isUsable(): Boolean = this != null && !isRecycled && width > 0 && height > 0

private fun edgeTarget(scene: ViewerScene, edge: ViewerScene.Edge): String =
    edge.endLabel?.let { "结束：$it" } ?: scene.states.firstOrNull { it.id == edge.toStateId }?.title ?: "目标不可用"

internal fun formatReleaseDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
