package com.tapscene.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tapscene.data.DraftAiEffect
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep
import com.tapscene.packageformat.AiDraftImportPolicy
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.AiDraftImportUiState
import com.tapscene.ui.DraftAiPlanUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val importSections = listOf("内容摘要", "文字与动作", "路径与效果", "图片与区域", "技术信息")

/** One file picker, one complete review page and one explicit creation action. */
@Composable
fun AiDraftImportContent(
    state: AiDraftImportUiState,
    onBack: () -> Unit,
    onChooseFile: () -> Unit,
    onBaseline: (String?) -> Unit,
    onResume: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onReadResult: () -> Unit,
    onOpenProject: (String) -> Unit,
    onNewImport: () -> Unit,
    onImage: (String) -> Unit,
    onCloseImage: () -> Unit,
    initialSection: Int = 0,
) {
    val preview = state.preview
    val completed = state.result?.status == "committed"
    var section by rememberSaveable(preview?.sessionId) { mutableIntStateOf(initialSection) }
    var chooseBaseline by rememberSaveable(preview?.sessionId) { mutableStateOf(false) }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var technicalExpanded by rememberSaveable(preview?.sessionId) { mutableStateOf(false) }
    val requestBack: () -> Unit = { if (state.busy) onCancel() else onBack() }
    BackHandler(onBack = requestBack)
    Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
        ShellTopBar(if (completed) "已导入待复核" else "导入 AI 包", requestBack)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (preview != null && !completed) AiSectionTabs(importSections, section, { section = it })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.message?.let { item { StatusNote(it) } }
            if (state.busy) item { Text(state.stage ?: "正在处理", color = ShellColors.Muted) }
            if (completed) {
                item {
                    SectionHeader(preview?.scene?.title ?: "导入结果", if (state.result?.projectExists == true) "已建立独立的新项目；原草稿和旧成品保持不变。" else "这次导入记录已保留，新项目已不在本机。")
                    if (state.result?.projectExists == true) Text("完整文字、动作、图片、区域与动画计划已放入新草稿。打开项目继续编辑，完成本机复核后才可交付。",
                        Modifier.padding(top = 12.dp), color = ShellColors.Muted)
                }
            } else if (preview == null) {
                item {
                    SectionHeader("1  选择完整的 .tapscene-ai 文件", "在本机隔离校验后核对内容，确认才建立独立新草稿。")
                    Text("支持静态 PNG、完整场景和动画计划。带视频的包不能回流；文件格式校验不代表作者或隐私认证。",
                        Modifier.padding(top = 12.dp), color = ShellColors.Muted)
                }
                if (state.pendingSessionIds.isNotEmpty()) item { SectionHeader("未完成导入", "离开页面会保留已准备的隔离副本，可继续核对。") }
                itemsIndexed(state.pendingSessionIds, key = { _, id -> "pending-$id" }) { index, id ->
                    ShellActionRow("继续第 ${index + 1} 个导入", "读取持久化会话和实际结果", onClick = { onResume(id) },
                        enabled = !state.busy && !state.outcomeUnknown)
                }
            } else when (section) {
                0 -> {
                    item {
                        SectionHeader(preview.scene.title, preview.scene.goal)
                        Text("${preview.scene.states.size} 个步骤 · ${preview.scene.edges.size} 条动作 · ${preview.scene.regions.size} 个区域",
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        Text("动画 ${preview.plan.visits.size} 次访问 · ${preview.plan.effects.size} 个效果 · ${frames(preview.plan.totalFrames)} · ${preview.plan.width} × ${preview.plan.height}",
                            Modifier.padding(top = 8.dp), color = ShellColors.Muted)
                    }
                    item { StatusNote(preview.trustNotice) }
                    if (!completed) item {
                        AiPanel {
                            SectionHeader("2  核对内容与对比版本")
                            Text(if (preview.baselineReleaseId == null) AiDraftImportPolicy.NO_BASELINE_NOTICE.replace("基线", "对比版本")
                                else "当前比较：${preview.baselineTitle.orEmpty()}。这是你明确选择的本机不可变版本，仅用于内容比较。",
                                style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { chooseBaseline = !chooseBaseline }, enabled = !state.busy && !state.outcomeUnknown,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (chooseBaseline) "收起对比版本" else "选择本机对比版本或不比较") }
                            if (chooseBaseline) {
                                AiChoice("不比较，只看完整内容", preview.baselineReleaseId == null, !state.busy) { onBaseline(null) }
                                if (state.baselines.isEmpty()) Text("没有可供比较的本机封存版本。", color = ShellColors.Muted)
                                state.baselines.forEach { baseline ->
                                    AiChoice("${baseline.title}\n${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(baseline.sealedAt))} · 本机不可变版本",
                                        preview.baselineReleaseId == baseline.id, !state.busy) { onBaseline(baseline.id) }
                                }
                            }
                        }
                    }
                    if (preview.issues.isNotEmpty()) {
                        item { SectionHeader("需要先修复 ${preview.issues.size} 项", "保留完整输入，不会删减内容或把视频静默变成静态。") }
                        itemsIndexed(preview.issues) { index, issue -> AiPanel {
                            Text("${index + 1}. ${issue.message}", color = ShellColors.Accent)
                            TechnicalDisclosure("定位信息", "${issue.code}\n对象：${issue.subjectId}")
                        } }
                    }
                    if (preview.baselineReleaseId != null) {
                        item { SectionHeader("内容差异 ${preview.differences.size} 项", "比较文字、图、区域和图片身份；动画计划独立列示，不推断计划差异。") }
                        if (preview.differences.isEmpty()) item { Text("上述内容与选定对比版本一致；仍须重新复核。", color = ShellColors.Muted) }
                        itemsIndexed(preview.differences) { index, difference -> AiPanel {
                            Text("${index + 1}. ${differenceCategory(difference.category)} · ${differenceField(difference.field)}", style = MaterialTheme.typography.titleSmall)
                            Text("对比版本：${differenceValue(preview.scene, difference.field, difference.before)}")
                            Text("导入内容：${differenceValue(preview.scene, difference.field, difference.after)}")
                            TechnicalDisclosure("差异定位", "分类：${difference.category}\n字段：${difference.field}\n对象：${difference.subjectId}\n对比版本原值：${difference.before ?: "（无）"}\n导入原值：${difference.after ?: "（无）"}")
                        } }
                    }
                }
                1 -> {
                    item { SectionHeader("完整文字与图", "所有分支都保留；下面按对象逐项展示，不只展示动画访问到的步骤。") }
                    item { AiPanel { Text("项目：${preview.scene.title}"); Text("目标：${preview.scene.goal}") } }
                    itemsIndexed(preview.scene.states, key = { _, step -> step.id }) { index, step -> AiPanel {
                        SectionHeader("${index + 1}. ${step.title}", if (step.id == preview.scene.startStateId) "起点" else null)
                        Text(step.description.ifEmpty { "（无讲解）" })
                        Text(if (step.terminal) "此步可结束" else "通过动作继续", color = ShellColors.Muted)
                        preview.scene.hotspots.filter { it.stateId == step.id }.forEach { hotspot ->
                            Text("热点：${hotspot.label}\n位置：${rect(hotspot.rect)}")
                        }
                        preview.scene.edges.filter { it.fromStateId == step.id }.forEach { edge ->
                            Text("${if (edge.trigger == "tap") "热点动作" else "下一步"}：${edge.label}\n→ ${sceneTarget(preview.scene, edge)}")
                            edge.endLabel?.let { Text("结束说明：$it") }
                        }
                        TechnicalDisclosure("步骤与动作标识", buildString {
                            append("步骤：${step.id}\n图片：${step.imageAssetId}\n外部来源声明：${step.sourceKind}")
                            preview.scene.edges.filter { it.fromStateId == step.id }.forEach { append("\n动作：${it.id} · 热点：${it.hotspotId ?: "无"} · 外部来源声明：${it.sourceKind}") }
                        })
                    } }
                }
                2 -> {
                    item { SectionHeader("完整动画计划", AiDraftImportPolicy.PATH_NOTICE.replace("基线", "对比版本")) }
                    item { Text("${preview.plan.width} × ${preview.plan.height} · 30 fps · ${preview.plan.totalFrames} 帧（${frames(preview.plan.totalFrames)}）") }
                    itemsIndexed(preview.plan.visits, key = { _, visit -> "visit-${visit.visitId}" }) { index, visit -> AiPanel {
                        Text("访问 ${index + 1}：${sceneStep(preview.scene, visit.stateId)}", style = MaterialTheme.typography.titleMedium)
                        Text("停留 ${visit.holdFrames} 帧（${frames(visit.holdFrames)}）")
                        Text("动作：${preview.scene.edges.firstOrNull { it.id == visit.selectedEdgeId }?.let { "${it.label} → ${sceneTarget(preview.scene, it)}" } ?: "无选定动作"}")
                        preview.plan.timeline.firstOrNull { it.visitId == visit.visitId }?.let {
                            Text("时间轴：起始 ${it.startFrame} 帧 · 时长 ${it.durationFrames} 帧 · 过渡 ${it.transitionFrames} 帧 · 重叠 ${it.overlapFrames} 帧", color = ShellColors.Muted)
                        }
                        TechnicalDisclosure("访问标识", "访问：${visit.visitId}\n步骤：${visit.stateId}\n所选动作：${visit.selectedEdgeId ?: "无"}")
                    } }
                    item { SectionHeader("全部效果（${preview.plan.effects.size}）", "同一访问、同一种类的多项效果分别保留。") }
                    itemsIndexed(preview.plan.effects) { index, effect -> AiPanel {
                        EffectDetails(index + 1, effect,
                            preview.plan.visits.indexOfFirst { it.visitId == effect.visitId }.takeIf { it >= 0 }?.let { "访问 ${it + 1}" } ?: "缺失访问",
                            effect.regionId?.let { id -> preview.scene.regions.firstOrNull { it.id == id }?.name },
                            effect.hotspotId?.let { id -> preview.scene.hotspots.firstOrNull { it.id == id }?.label })
                    } }
                }
                3 -> {
                    item { SectionHeader("图片与区域（按需查看）", "只读取本机隔离区内验证过的 PNG。查看图片不会自动完成隐私复核。") }
                    itemsIndexed(preview.scene.states, key = { _, step -> "image-${step.id}" }) { index, step ->
                        ShellActionRow("${index + 1}. ${step.title}", "${step.width} × ${step.height} · 查看完整画面", onClick = { onImage(step.imageAssetId) }, enabled = !state.busy)
                    }
                    itemsIndexed(preview.scene.regions, key = { _, region -> "region-${region.id}" }) { index, region -> AiPanel {
                        SectionHeader("区域 ${index + 1}：${region.name}", "所在步骤：${sceneStep(preview.scene, region.stateId)}")
                        Text("范围：${region.bbox.x}, ${region.bbox.y}, ${region.bbox.width} × ${region.bbox.height} 像素\n原图：${region.sourceWidth} × ${region.sourceHeight}\n分组：${region.group ?: "无"}\n叠放：${region.zIndex}\n局部锚点：${region.anchor.x}, ${region.anchor.y}")
                        OutlinedButton(onClick = { onImage(region.assetId) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看实际区域裁片") }
                        TechnicalDisclosure("区域标识", "区域：${region.id}\n底图：${region.baseAssetId}\n裁片：${region.assetId}")
                    } }
                }
                4 -> item {
                    AiPanel {
                        SectionHeader("技术信息", "标识、摘要和文件清单用于排查，不是来源或隐私认证。")
                        Text("解压内容 ${String.format(Locale.ROOT, "%.2f", preview.expandedByteLength / 1048576.0)} MiB")
                        TextButton(onClick = { technicalExpanded = !technicalExpanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (technicalExpanded) "收起完整技术摘要" else "展开完整技术摘要")
                        }
                        if (technicalExpanded) Text("会话：${preview.sessionId}\n核对摘要：${preview.previewDigest}\n新项目标识：${preview.newProjectId}\n\n${preview.summary}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        ShellDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                state.outcomeUnknown -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.result?.status != "committed") OutlinedButton(onClick = onCancel, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("尝试取消") }
                    Button(onClick = onReadResult, enabled = !state.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("重读结果") }
                }
                completed -> {
                    val result = state.result
                    if (result?.projectExists == true && result.projectId != null) Button(onClick = { onOpenProject(result.projectId) },
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开新草稿并复核") }
                    TextButton(onClick = onNewImport, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("导入另一个包") }
                }
                state.busy -> OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消处理并核对结果") }
                preview != null -> {
                    Text(if (preview.issues.isEmpty()) "3  确认后建立独立新草稿，全部内容待复核" else "存在 ${preview.issues.size} 个阻断项，不能建立草稿", style = MaterialTheme.typography.labelMedium, color = ShellColors.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消导入") }
                        Button(onClick = onCommit, enabled = state.canCommit, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("确认建立新草稿") }
                    }
                }
                else -> Button(onClick = onChooseFile, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("选择 .tapscene-ai 文件") }
            }
        }
    }
    if (confirmCancel) AlertDialog(onDismissRequest = { confirmCancel = false }, title = { Text("取消本次导入？") },
        text = { Text("删除这次隔离副本。原草稿和旧成品保留。只想稍后继续，可返回保留此会话。") },
        confirmButton = { TextButton(onClick = { confirmCancel = false; onCancel() }) { Text("取消导入") } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("继续核对") } })
    if (state.imageAssetId != null) AlertDialog(onDismissRequest = onCloseImage,
        title = { Text(if (preview?.scene?.regions?.any { it.assetId == state.imageAssetId } == true) "实际区域裁片" else "实际安全画面") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.imageBusy) CircularProgressIndicator()
                state.image?.let { bitmap -> Image(bitmap.asImageBitmap(), "本机隔离区实际 PNG", Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit) }
                Text("查看后仍需在新项目的成品复核中重新确认。", style = MaterialTheme.typography.bodySmall)
                if (!state.imageBusy && state.image == null) TextButton(onClick = { state.imageAssetId?.let(onImage) }) { Text("重试读取") }
            }
        }, confirmButton = { TextButton(onClick = onCloseImage, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } })
}

data class DraftAiPlanCallbacks(
    val onBack: () -> Unit = {},
    val onCanvas: (Boolean) -> Unit = {},
    val onHold: (String, Int) -> Unit = { _, _ -> },
    val onChooseEdge: (String, String?) -> Unit = { _, _ -> },
    val onRestart: () -> Unit = {},
    val onConfirmPath: () -> Unit = {},
    val onDismissPath: () -> Unit = {},
    val onChangeEffect: (String, RenderPlan.Effect) -> Unit = { _, _ -> },
    val onDeleteEffect: (String) -> Unit = {},
    val onSave: (Boolean) -> Unit = {},
    val onReadResult: () -> Unit = {},
    val onReload: () -> Unit = {},
)

@Composable
fun DraftAiPlanContent(state: DraftAiPlanUiState, callbacks: DraftAiPlanCallbacks, initialSection: Int = 0,
    initialVisitId: String? = null, initialEffectId: String? = null) {
    val config = state.config
    val project = state.project
    var section by rememberSaveable(project?.project?.id) { mutableIntStateOf(initialSection) }
    var visitDetails by rememberSaveable(project?.project?.id) { mutableStateOf(initialVisitId) }
    var effectDetails by rememberSaveable(project?.project?.id) { mutableStateOf(initialEffectId) }
    var editingEffect by rememberSaveable(project?.project?.id) { mutableStateOf<String?>(null) }
    var deletingEffect by rememberSaveable(project?.project?.id) { mutableStateOf<String?>(null) }
    var exitRequested by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.busy && !state.saveOutcomeUnknown
    val requestBack: () -> Unit = {
        if (!state.busy) {
            if (state.dirty || state.saveOutcomeUnknown) exitRequested = true else callbacks.onBack()
        }
    }
    BackHandler(onBack = requestBack)
    LaunchedEffect(state.canClose) { if (state.canClose) callbacks.onBack() }
    Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
        ShellTopBar("动画计划", requestBack)
        if (state.busy || state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        AiSectionTabs(listOf("访问路径", "全部效果", "草稿内容"), section, { section = it })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionHeader(project?.project?.title ?: "读取草稿", "计划属于当前草稿。修改不会自动复核图片或生成可交付版本。") }
            state.message?.let { item { StatusNote(it) } }
            if (state.issues.isNotEmpty()) item { AiPanel {
                SectionHeader("待修复 ${state.issues.size} 项", "失效引用和效果参数仍完整保留。可保存修复中的计划，校验通过前不能导出。")
                state.issues.forEach { Text("• $it", color = ShellColors.Accent) }
            } }
            if (config == null || project == null) item {
                Text(if (state.busy) "正在读取…" else "当前没有可编辑的动画计划。", color = ShellColors.Muted)
                TextButton(onClick = callbacks.onReload, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取") }
            } else when (section) {
                0 -> {
                    item { AiPanel {
                        SectionHeader("画布与路径", "30 fps；每次访问独立停留，回访不会复制成新步骤。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = config.width == 1080, onClick = { callbacks.onCanvas(false) }, enabled = enabled,
                                label = { Text("竖屏 1080×1920") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                            FilterChip(selected = config.width == 1920, onClick = { callbacks.onCanvas(true) }, enabled = enabled,
                                label = { Text("横屏 1920×1080") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                        }
                        TextButton(onClick = callbacks.onRestart, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("从当前草稿起点重选路径…") }
                    } }
                    itemsIndexed(config.visits, key = { _, visit -> "visit-${visit.visitId}" }) { index, visit ->
                        val step = project.steps.firstOrNull { it.id == visit.stateId }
                        AiPanel {
                            ShellActionRow("访问 ${index + 1}：${step?.title ?: "步骤已删除 · 待修复"}",
                                "${visit.holdFrames} 帧（${frames(visit.holdFrames)}） · ${draftAction(project, visit.selectedEdgeId)}",
                                onClick = { visitDetails = visit.visitId.takeUnless { it == visitDetails } })
                            if (visitDetails == visit.visitId) {
                                var hold by rememberSaveable(visit.visitId, visit.holdFrames) { mutableStateOf(visit.holdFrames.toString()) }
                                OutlinedTextField(hold, { hold = it }, label = { Text("停留帧数（1–1800）") }, enabled = enabled,
                                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                                val value = hold.toIntOrNull()
                                OutlinedButton(onClick = { value?.let { callbacks.onHold(visit.visitId, it) } }, enabled = enabled && value != null && value in 1..1800,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("应用停留，保留全部效果") }
                                Text("缩短停留可能使效果超界；效果会保留并列为待修复。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                                if (step != null) {
                                    SectionHeader("重新选择本次访问的动作", "后续访问若受影响，会先列出删除的访问与效果，再由你确认。")
                                    AiChoice(if (step.isTerminal) "在此结束，不选择动作" else "先在此截断，路径待补全", visit.selectedEdgeId == null, enabled) {
                                        callbacks.onChooseEdge(visit.visitId, null)
                                    }
                                    step.hotspots.forEach { hotspot ->
                                        AiChoice("${hotspot.label} → ${hotspot.targetStepId?.let { draftStep(project, it) } ?: "结束：${hotspot.endLabel.orEmpty()}"}",
                                            visit.selectedEdgeId == hotspot.edgeId, enabled) { callbacks.onChooseEdge(visit.visitId, hotspot.edgeId) }
                                    }
                                    step.nextAction?.let { next ->
                                        AiChoice("${next.label} → ${next.targetStepId?.let { draftStep(project, it) } ?: "待补目标"}", visit.selectedEdgeId == next.id,
                                            enabled && next.targetStepId != null) { callbacks.onChooseEdge(visit.visitId, next.id) }
                                    }
                                } else Text("从前面的有效访问重选路径，或从起点重选。旧参数保留到你明确确认。", color = ShellColors.Accent)
                                TechnicalDisclosure("访问标识", "访问：${visit.visitId}\n步骤：${visit.stateId}\n动作：${visit.selectedEdgeId ?: "无"}")
                            }
                        }
                    }
                }
                1 -> {
                    item { SectionHeader("全部 ${config.effects.size} 项效果", "每项有独立标识，可单独修改或删除。同种效果不会合并。") }
                    if (config.effects.isEmpty()) item { Text("此计划没有效果。", color = ShellColors.Muted) }
                    itemsIndexed(config.effects, key = { _, effect -> "effect-${effect.id}" }) { index, entry ->
                        val effect = entry.value
                        val visitIndex = config.visits.indexOfFirst { it.visitId == effect.visitId }
                        AiPanel {
                            ShellActionRow("效果 ${index + 1} · ${effectName(effect.type)}",
                                "${if (visitIndex >= 0) "访问 ${visitIndex + 1}" else "访问已删除"} · 起始 ${effect.startFrame} 帧 · 持续 ${effect.durationFrames} 帧",
                                onClick = { effectDetails = entry.id.takeUnless { it == effectDetails } })
                            if (effectDetails == entry.id) {
                                EffectDetails(index + 1, effect, if (visitIndex >= 0) "访问 ${visitIndex + 1}" else "访问已删除 · 待修复",
                                    project.steps.flatMap { it.regions }.firstOrNull { it.id == effect.regionId }?.name,
                                    project.steps.flatMap { it.hotspots }.firstOrNull { it.id == effect.hotspotId }?.label)
                                TechnicalDisclosure("本项稳定编辑标识", entry.id)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { editingEffect = entry.id }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("编辑此项") }
                                    TextButton(onClick = { deletingEffect = entry.id }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除此项…") }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    item { SectionHeader("当前草稿的完整内容", "名称、动作与区域读取当前项目。未访问分支也显示在这里。") }
                    itemsIndexed(project.steps, key = { _, step -> "step-${step.id}" }) { index, step -> AiPanel {
                        SectionHeader("${index + 1}. ${step.title}", if (step.id == project.project.startStepId) "起点" else null)
                        Text(step.description.ifEmpty { "（无讲解）" })
                        Text(if (step.isTerminal) "可结束" else "非结束步骤", color = ShellColors.Muted)
                        step.hotspots.forEach { Text("热点：${it.label} → ${it.targetStepId?.let { target -> draftStep(project, target) } ?: "结束：${it.endLabel.orEmpty()}"}") }
                        step.nextAction?.let { Text("下一步：${it.label} → ${it.targetStepId?.let { target -> draftStep(project, target) } ?: "待补目标"}") }
                        step.regions.forEach { Text("区域：${it.name} · ${it.bbox.x}, ${it.bbox.y}, ${it.bbox.width} × ${it.bbox.height} · ${if (it.stale) "画面待修复" else "已有裁片"}") }
                        TechnicalDisclosure("步骤标识", step.id)
                    } }
                }
            }
        }
        ShellDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (config == null) "先完成步骤与起点" else "${config.visits.size} 次访问 · ${config.effects.size} 项效果 · ${if (state.dirty) "有未保存修改" else "已读取保存内容"}",
                style = MaterialTheme.typography.labelMedium, color = ShellColors.Muted)
            if (state.saveOutcomeUnknown) Button(onClick = callbacks.onReadResult, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重读保存结果") }
            else Button(onClick = { callbacks.onSave(false) }, enabled = enabled && config != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.issues.isEmpty()) "检查并保存计划" else "保存待修复计划") }
        }
    }
    if (exitRequested) AlertDialog(onDismissRequest = { exitRequested = false }, title = { Text("保留动画计划修改？") },
        text = { Text(if (state.saveOutcomeUnknown) "保存结果尚未确定。先重读结果，避免覆盖已保存内容。" else "未保存的访问与效果修改还在本页。") },
        confirmButton = { TextButton(onClick = { exitRequested = false; if (state.saveOutcomeUnknown) callbacks.onReadResult() else callbacks.onSave(true) }, enabled = !state.busy) {
            Text(if (state.saveOutcomeUnknown) "重读结果" else "保存并返回")
        } }, dismissButton = { Column {
            TextButton(onClick = { exitRequested = false }) { Text("继续编辑") }
            if (!state.saveOutcomeUnknown) TextButton(onClick = { exitRequested = false; callbacks.onBack() }) { Text("放弃未保存修改并返回") }
        } })
    state.pendingPath?.let { change -> AlertDialog(onDismissRequest = callbacks.onDismissPath,
        title = { Text("确认更改访问路径？") }, text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将移除 ${change.removedVisits.size} 次后续访问及其 ${change.removedEffects.size} 项效果。当前访问中不再适用的效果会保留为待修复。草稿步骤与动作不删除。")
                change.removedVisits.forEach { visit -> Text("移除访问：${project?.let { draftStep(it, visit.stateId) } ?: visit.stateId}（${visit.holdFrames} 帧）") }
                change.removedEffects.forEach { effect ->
                    val index = change.before.effects.indexOfFirst { it.id == effect.id }
                    Text("移除效果 ${index + 1}：${effectName(effect.value.type)} · ${effect.value.startFrame} 起 / ${effect.value.durationFrames} 帧")
                }
            }
        }, confirmButton = { TextButton(onClick = callbacks.onConfirmPath, enabled = enabled) { Text("确认更改") } },
        dismissButton = { TextButton(onClick = callbacks.onDismissPath) { Text("保留原路径") } }) }
    config?.effects?.firstOrNull { it.id == deletingEffect }?.let { entry ->
        AlertDialog(onDismissRequest = { deletingEffect = null }, title = { Text("删除这一项效果？") },
            text = { Text("仅移除 ${effectName(entry.value.type)}，起始 ${entry.value.startFrame} 帧、持续 ${entry.value.durationFrames} 帧。其他同类效果保留。保存计划后生效。") },
            confirmButton = { TextButton(onClick = { callbacks.onDeleteEffect(entry.id); deletingEffect = null }, enabled = enabled) { Text("删除此项") } },
            dismissButton = { TextButton(onClick = { deletingEffect = null }) { Text("保留") } })
    }
    if (project != null && config != null) config.effects.firstOrNull { it.id == editingEffect }?.let { entry ->
        DraftAiEffectDialog(entry, project, config.visits, enabled, { editingEffect = null }) { effect ->
            callbacks.onChangeEffect(entry.id, effect); editingEffect = null
        }
    }
}

/** Existing white-list effect parameters are edited one stable item at a time, never by type. */
@Composable
private fun DraftAiEffectDialog(entry: DraftAiEffect, project: ProjectSnapshot, visits: List<RenderPlan.Visit>, enabled: Boolean,
    onDismiss: () -> Unit, onApply: (RenderPlan.Effect) -> Unit) {
    val original = entry.value
    var visitId by rememberSaveable(entry.id) { mutableStateOf(original.visitId) }
    var start by rememberSaveable(entry.id) { mutableStateOf(original.startFrame.toString()) }
    var duration by rememberSaveable(entry.id) { mutableStateOf(original.durationFrames.toString()) }
    var text by rememberSaveable(entry.id) { mutableStateOf(original.text.orEmpty()) }
    var hotspotId by rememberSaveable(entry.id) { mutableStateOf(original.hotspotId) }
    var regionId by rememberSaveable(entry.id) { mutableStateOf(original.regionId) }
    var rectangle by rememberSaveable(entry.id) { mutableStateOf(original.rect != null) }
    var x by rememberSaveable(entry.id) { mutableStateOf(original.rect?.x?.toString() ?: "0.08") }
    var y by rememberSaveable(entry.id) { mutableStateOf(original.rect?.y?.toString() ?: "0.08") }
    var width by rememberSaveable(entry.id) { mutableStateOf(original.rect?.width?.toString() ?: "0.84") }
    var height by rememberSaveable(entry.id) { mutableStateOf(original.rect?.height?.toString() ?: "0.14") }
    var chooseVisit by rememberSaveable(entry.id) { mutableStateOf(false) }
    val visit = visits.firstOrNull { it.visitId == visitId }
    val step = project.steps.firstOrNull { it.id == visit?.stateId }
    val startValue = start.toIntOrNull()
    val durationValue = duration.toIntOrNull()
    val coordinates = listOf(x, y, width, height).map { it.toDoubleOrNull()?.takeIf { number -> number.isFinite() && number in 0.0..1.0 } }
    val needsRect = original.type == "annotation" || (original.type in setOf("focus", "highlight") && rectangle)
    val valid = startValue != null && startValue in 0..1800 && durationValue != null && durationValue in 1..1800 &&
        (!needsRect || coordinates.all { it != null }) && text.length <= 240
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑${effectName(original.type)}") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("参数逐项保存；超出停留或失效引用会保留为待修复，不会自动删除。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { chooseVisit = !chooseVisit }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    val index = visits.indexOfFirst { it.visitId == visitId }
                    Text(if (index >= 0) "绑定访问 ${index + 1}：${step?.title ?: "步骤已删除"}" else "绑定访问已删除 · 重新选择")
                }
                if (chooseVisit) visits.forEachIndexed { index, item ->
                    AiChoice("访问 ${index + 1}：${draftStep(project, item.stateId)}", item.visitId == visitId, enabled) { visitId = item.visitId; chooseVisit = false }
                }
                OutlinedTextField(start, { start = it }, label = { Text("相对访问起始帧（0–1800）") }, enabled = enabled,
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(duration, { duration = it }, label = { Text("持续帧数（1–1800）") }, enabled = enabled,
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                if (original.type == "annotation") OutlinedTextField(text, { text = it }, label = { Text("标注文字（最多 240 字）") }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (original.type == "click") {
                    Text("热点：${step?.hotspots?.firstOrNull { it.id == hotspotId }?.label ?: "引用已失效"}")
                    step?.hotspots?.forEach { hotspot -> AiChoice(hotspot.label, hotspot.id == hotspotId, enabled) { hotspotId = hotspot.id } }
                    Text("须与本次访问的所选热点动作一致，否则保留为待修复。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                }
                if (original.type in setOf("focus", "highlight")) {
                    AiChoice("使用矩形范围", rectangle, enabled) { rectangle = true }
                    AiChoice("使用当前步骤的区域", !rectangle, enabled) { rectangle = false }
                    if (!rectangle) {
                        Text("区域：${step?.regions?.firstOrNull { it.id == regionId }?.name ?: "引用已失效或未选择"}")
                        step?.regions?.forEach { region -> AiChoice(region.name, region.id == regionId, enabled) { regionId = region.id } }
                    }
                }
                if (needsRect) {
                    Text("归一化矩形（0–1）", style = MaterialTheme.typography.titleSmall)
                    DecimalField("左 x", x, enabled) { x = it }
                    DecimalField("上 y", y, enabled) { y = it }
                    DecimalField("宽 width", width, enabled) { width = it }
                    DecimalField("高 height", height, enabled) { height = it }
                }
                if (original.type == "transition") Text("静态叠化须贴合本次停留末尾，并在相邻访问保留独立画面帧。", color = ShellColors.Muted)
            }
        }, confirmButton = { TextButton(onClick = {
            if (valid) onApply(RenderPlan.Effect(original.type, visitId, requireNotNull(startValue), requireNotNull(durationValue),
                hotspotId.takeIf { original.type == "click" }, regionId.takeIf { original.type in setOf("focus", "highlight") && !rectangle },
                text.takeIf { original.type == "annotation" }, if (needsRect) ViewerScene.Rect(requireNotNull(coordinates[0]), requireNotNull(coordinates[1]), requireNotNull(coordinates[2]), requireNotNull(coordinates[3])) else null))
        }, enabled = enabled && valid, modifier = Modifier.heightIn(min = 48.dp)) { Text("应用到此项") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun DecimalField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())

@Composable
private fun AiPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = ShellColors.Surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable
private fun AiSectionTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label -> FilterChip(selected = selected == index, onClick = { onSelect(index) },
            label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp)) }
    }
}
@Composable
private fun AiChoice(text: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    OutlinedButton(onClick = onSelect, enabled = enabled, shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(12.dp)) {
        Text(if (selected) "●  $text" else "○  $text", Modifier.fillMaxWidth(), color = if (selected) ShellColors.Accent else ShellColors.Ink)
    }
}
@Composable
private fun TechnicalDisclosure(label: String, text: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "收起$label" else "展开$label") }
    if (expanded) Text(text, style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
}
@Composable
private fun EffectDetails(number: Int, effect: RenderPlan.Effect, visit: String, region: String?, hotspot: String?) {
    Text("效果 $number · ${effectName(effect.type)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    Text("$visit\n相对起始：${effect.startFrame} 帧\n持续：${effect.durationFrames} 帧")
    effect.text?.let { Text("完整标注：$it") }
    effect.rect?.let { Text("矩形：${rect(it)}") }
    effect.regionId?.let { Text("区域：${region ?: "引用不存在 · 待修复"}") }
    effect.hotspotId?.let { Text("热点：${hotspot ?: "引用不存在 · 待修复"}") }
    TechnicalDisclosure("效果引用与类型", "类型：${effect.type}\n访问：${effect.visitId}\n热点：${effect.hotspotId ?: "无"}\n区域：${effect.regionId ?: "无"}")
}
private fun effectName(type: String): String = when (type) {
    "click" -> "点击提示"; "focus" -> "聚焦"; "highlight" -> "高亮"; "annotation" -> "文字标注"; "transition" -> "静态叠化"; else -> type
}
private fun frames(count: Int): String = "${String.format(Locale.ROOT, "%.2f", count / 30.0)} 秒"
private fun rect(rect: ViewerScene.Rect): String = "x=${rect.x}, y=${rect.y}, width=${rect.width}, height=${rect.height}（0–1）"
private fun sceneStep(scene: ViewerScene, stateId: String) = scene.states.firstOrNull { it.id == stateId }?.title ?: "步骤不存在 · $stateId"
private fun sceneTarget(scene: ViewerScene, edge: ViewerScene.Edge): String = edge.toStateId?.let { sceneStep(scene, it) } ?: "结束：${edge.endLabel.orEmpty()}"
private fun draftStep(project: ProjectSnapshot, stateId: String) = project.steps.firstOrNull { it.id == stateId }?.title ?: "步骤已删除 · 待修复"
private fun draftAction(project: ProjectSnapshot, edgeId: String?): String {
    if (edgeId == null) return "无选定动作"
    project.steps.forEach { step ->
        step.hotspots.firstOrNull { it.edgeId == edgeId }?.let { return "${it.label} → ${it.targetStepId?.let { target -> draftStep(project, target) } ?: "结束"}" }
        step.nextAction?.takeIf { it.id == edgeId }?.let { return "${it.label} → ${it.targetStepId?.let { target -> draftStep(project, target) } ?: "待补目标"}" }
    }
    return "动作已删除 · 待修复"
}

private fun differenceCategory(value: String): String = when (value) {
    "text" -> "文字"; "state" -> "步骤"; "image" -> "画面"; "hotspot" -> "热点"; "edge" -> "动作"; "region" -> "区域"; else -> "内容"
}
private fun differenceField(value: String): String = when (value) {
    "title", "name" -> "名称"; "goal" -> "演示目标"; "description" -> "讲解"; "label" -> "标签"; "endLabel" -> "结束说明"
    "startStateId" -> "起点"; "order" -> "步骤顺序"; "terminal" -> "结束状态"; "sourceKindDeclaration" -> "外部来源声明"
    "image" -> "图片内容与尺寸"; "stateId" -> "所在步骤"; "rect" -> "矩形范围"; "fromStateId" -> "出发步骤"; "toStateId" -> "目标步骤"
    "hotspotId" -> "绑定热点"; "trigger" -> "触发方式"; "transition" -> "过渡"; "group" -> "分组"; "baseImage" -> "区域底图"
    "cropImage" -> "区域裁片"; "sourceDimensions" -> "原图尺寸"; "bbox" -> "像素范围"; "zIndex" -> "叠放次序"; "anchor" -> "局部锚点"
    else -> "参数"
}
private fun differenceValue(scene: ViewerScene, field: String, value: String?): String {
    if (value == null) return "（无）"
    return when (field) {
        "image", "baseImage", "cropImage", "transition" -> "已记录图片内容与尺寸，精确摘要可展开查看"
        "startStateId", "stateId", "fromStateId", "toStateId" -> scene.states.firstOrNull { it.id == value }?.title ?: "导入内容中不存在的步骤，标识可展开查看"
        "hotspotId" -> scene.hotspots.firstOrNull { it.id == value }?.label ?: "导入内容中不存在的热点，标识可展开查看"
        "order" -> value.split(',').mapIndexed { index, id -> "${index + 1}. ${scene.states.firstOrNull { it.id == id }?.title ?: "未纳入导入内容的步骤"}" }.joinToString("\n")
        "terminal" -> if (value == "true") "可结束" else "非结束步骤"
        "trigger" -> when (value) { "tap" -> "点击画面热点"; "continue" -> "画布外下一步按钮"; else -> "其他方式，原值可展开查看" }
        "sourceKindDeclaration" -> when (value) { "recorded" -> "声称来自录制"; "authored" -> "声称由作者编排"; "imported" -> "声称来自导入"; else -> "其他声明，原值可展开查看" }
        else -> value
    }
}
