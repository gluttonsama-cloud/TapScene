package com.tapscene.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ReleaseSummary
import com.tapscene.ui.HostingPage
import com.tapscene.ui.HostingUiState
import com.tapscene.ui.HOSTED_LOCAL_ENDPOINT
import com.tapscene.hosting.HostedModels
import com.tapscene.packageformat.ViewerScene
import com.tapscene.packageformat.ViewerPackageCodec
import kotlinx.coroutines.delay
import com.tapscene.ui.ProjectIssue
import com.tapscene.ui.AiPackageConfiguration
import com.tapscene.ui.ReleaseUiState
import com.tapscene.packageformat.AiPackageCodec
import com.tapscene.packageformat.RenderPlan
import java.util.Locale

/** Screen-only projection of an existing task. Missing work is represented by null, never a demo. */
data class ShellTaskInfo(
    val title: String,
    val stage: String,
    val projectTitle: String? = null,
    val revisionLabel: String? = null,
    val completed: Int? = null,
    val total: Int? = null,
    val elapsedLabel: String? = null,
    val recoveryNote: String? = null,
)

/** The outer app owns safe areas, keyboard handling, and any global navigation. */
@Composable
private fun ServicePage(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    showTopBar: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
        if (showTopBar) ShellTopBar(title, onBack, actions)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

@Composable
private fun DetailSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title, subtitle)
        content()
    }
}

@Composable
private fun WorkflowLine(current: Int, labels: List<String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${index + 1}  $label",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (index == current) ShellColors.Accent else ShellColors.Muted,
                    fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.fillMaxWidth().height(if (index == current) 2.dp else 1.dp).background(if (index == current) ShellColors.Accent else ShellColors.Divider))
            }
        }
    }
}

@Composable
fun DemoLibraryScreen(onImport: () -> Unit, onSettings: () -> Unit) {
    ServicePage("演示库", actions = {
        TextButton(shape = RoundedCornerShape(8.dp), onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("设置") }
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("离线观看", style = MaterialTheme.typography.titleMedium)
            Text("保存在本机", style = MaterialTheme.typography.labelMedium, color = ShellColors.Muted)
        }
        ScreenEmpty(
            "还没有离线演示",
            "导入经过复核的观看包。画面、文字和点击路径会一起保存在本机，离线也能完整观看。",
            "导入演示包",
            onImport,
        )
        ShellDivider()
        DetailSection("从创作到观看") {
            ShellActionRow("项目", "录制与导入素材，校正步骤，再检查与复核。")
            ShellDivider()
            ShellActionRow("演示库", "收纳固定版本，继续上次进度或从头开始。")
            ShellDivider()
            ShellActionRow("独立保存", "观看副本与创作草稿分开；删除副本不会删除原项目。")
        }
    }
}

@Composable
fun ExternalImportScreen(onBack: () -> Unit) {
    ServicePage("导入外部包", onBack) {
        WorkflowLine(0, listOf("选择文件", "校验与差异", "确认导入"))
        DetailSection("选择 TapScene 数据包") {
            ShellLabelValue("文件", "尚未选择")
            ShellLabelValue("验证状态", "未开始")
            OutlinedButton(shape = RoundedCornerShape(8.dp), onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("选择数据包") }
        }
        DetailSection("导入用途") {
            ShellActionRow("加入演示库", "完整观看固定版本，保留独立的观看进度。")
            ShellDivider()
            ShellActionRow("复制为新项目", "确认是自己的安全包后，新建可以编辑的草稿。")
            ShellDivider()
            ShellActionRow("导入 AI 调整结果", "先查看来源版本与内容差异，再建立新草稿。")
        }
        DetailSection("确认前会检查") {
            ShellLabelValue("文件完整性", "清单、摘要与安全路径")
            ShellLabelValue("内容结构", "版本、画面与动作目标")
            ShellLabelValue("来源与差异", "通过校验后展示")
        }
        UnavailableAction("校验并查看差异", "数据包读取与校验尚未接入；现有项目不会被覆盖。")
    }
}

@Composable
fun FormalPlayerScreen(onBack: () -> Unit) {
    ServicePage("离线播放", onBack) {
        ShellLabelValue("观看版本", "尚未载入")
        ScreenEmpty("选择一个演示开始", "正式播放器只读取已导入的固定版本。请先回到演示库选择演示。", "返回演示库", onBack)
        ShellDivider()
        DetailSection("播放控制") {
            ShellActionRow("选择动作", "点击画面热点，或使用相同的文字动作。")
            ShellDivider()
            ShellActionRow("上一步与重来", "上一步沿实际访问路径返回；重来从起点开始。")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(shape = RoundedCornerShape(8.dp), onClick = {}, enabled = false, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上一步") }
            Button(shape = RoundedCornerShape(8.dp), onClick = {}, enabled = false, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("开始") }
        }
    }
}

@Composable
fun DeliveryCheckScreen(
    project: ProjectSnapshot?,
    issues: List<ProjectIssue>,
    onBack: () -> Unit,
    onIssue: (ProjectIssue) -> Unit,
    onPreview: () -> Unit,
    onReview: () -> Unit,
    onDelivery: () -> Unit,
    showTopBar: Boolean = true,
    onBuildCandidate: (() -> Unit)? = null,
    busy: Boolean = false,
    buildEnabled: Boolean = true,
) {
    var showMediaDetails by rememberSaveable(project?.project?.id) { mutableStateOf(false) }
    ServicePage("检查与交付", onBack, showTopBar = showTopBar) {
        WorkflowLine(0, listOf("检查", "成品复核", "交付"))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (project != null) {
                if (showTopBar) Text(project.project.title, style = MaterialTheme.typography.titleMedium)
                Text("草稿修订 ${project.project.revision} · ${project.steps.size} 个步骤", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            }
            val firstIssue = issues.firstOrNull()
            if (firstIssue != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${issues.size} 项待处理", style = MaterialTheme.typography.titleLarge, color = ShellColors.Accent)
                        Text("结构与路径", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    }
                    Button(shape = RoundedCornerShape(8.dp), onClick = { onIssue(firstIssue) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("去修正") }
                }
                issues.forEach { issue ->
                    val stepTitle = project?.steps?.firstOrNull { it.id == issue.stepId }?.title
                    ShellDivider()
                    ShellActionRow(
                        title = issue.message,
                        subtitle = stepTitle?.let { "步骤：$it" } ?: "项目结构",
                        onClick = { onIssue(issue) },
                    )
                }
            } else {
                SectionHeader("结构与路径", if (project == null) "未选择项目。" else "当前本机结构检查未返回问题。")
            }
            ShellDivider()
            ShellActionRow("试走点击路径", "确认每个动作到达预期画面或结束结果。", onClick = onPreview, enabled = project?.steps?.isNotEmpty() == true)
        }
        DetailSection("隐私与文字") {
            Text("生成固定修订后，逐项检查实际图片、标题、讲解与动作标签。检查实际图片、完整过渡视频及区域裁片。", style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
        }
        Column {
            ShellDivider()
            ShellActionRow(
                "媒体与体积",
                subtitle = project?.let { "当前 ${it.steps.size} 张步骤图片" },
                value = if (showMediaDetails) "收起" else "展开",
                onClick = { showMediaDetails = !showMediaDetails },
            )
            if (showMediaDetails) {
                ShellLabelValue("当前步骤图片", if (project == null) "未选择项目" else "${project.steps.size} 张 · ${formatShellBytes(project.steps.sumOf { it.asset.byteLength })}")
                ShellLabelValue("交付包大小", "尚未生成")
            }
            ShellDivider()
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    busy -> "正在生成固定候选，请稍候。"
                    !buildEnabled -> "先保存当前修改，再生成固定候选。"
                    issues.isNotEmpty() -> "先修正结构与路径问题，再生成候选。"
                    onBuildCandidate == null -> "生成入口尚不可用；已保存内容不受影响。"
                    else -> "固定当前图片与文字，完成逐项复核和实际试走后封存。"
                },
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                shape = RoundedCornerShape(8.dp),
                onClick = { onBuildCandidate?.invoke() },
                enabled = onBuildCandidate != null && !busy && buildEnabled && project?.steps?.isNotEmpty() == true && issues.isEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(if (busy) "正在生成" else "生成待复核成品") }
        }
        Column {
            ShellDivider()
            ShellActionRow("成品逐项复核", "检查真实输出，全部确认后封存版本。", onClick = onReview)
            ShellDivider()
            ShellActionRow("查看交付方式", "离线观看包、托管链接与 AI 数据包。", onClick = onDelivery)
        }
    }
}

@Composable
fun ReleaseReviewScreen(onBack: () -> Unit) {
    ServicePage("成品逐项复核", onBack) {
        WorkflowLine(1, listOf("检查", "成品复核", "交付"))
        ShellLabelValue("候选版本", "尚未生成")
        ScreenEmpty("还没有待复核成品", "先从交付检查生成固定修订的成品，再逐项检查实际输出。")
        DetailSection("复核范围") {
            ShellActionRow("图片与完整视频", "检查重新解码的输出，视频需看完整段。")
            ShellDivider()
            ShellActionRow("文字、封面与区域裁片", "检查所有会随成品交付的可见内容。")
            ShellDivider()
            ShellActionRow("包清单", "确认文件范围，不包含原片与本机私有信息。")
        }
        UnavailableAction("确认本项并继续", "候选成品与复核队列尚未接入；全部必要项通过后才可封存。")
    }
}

@Composable
fun DeliveryOptionsScreen(
    onBack: () -> Unit,
    onReview: () -> Unit,
    onAi: () -> Unit,
    onAccount: () -> Unit,
    onVersions: () -> Unit,
    sealedSummary: ReleaseSummary? = null,
    onExport: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    busy: Boolean = false,
    onHosting: (() -> Unit)? = null,
) {
    ServicePage("交付方式", onBack) {
        WorkflowLine(2, listOf("检查", "成品复核", "交付"))
        Column {
            ShellLabelValue("固定版本", sealedSummary?.title ?: "尚未生成")
            if (sealedSummary != null) {
                ShellLabelValue("版本标识", sealedSummary.id.take(12))
                ShellLabelValue("${if (sealedSummary.origin == "local") "封存" else "导入"}时间", formatReleaseDate(sealedSummary.sealedAt))
                ShellLabelValue("包内文件", "${sealedSummary.stepCount} 个步骤 · ${formatShellBytes(sealedSummary.byteLength)}")
            } else ShellLabelValue("封存时间 / 体积", "等待完成复核")
        }
        DetailSection("离线观看包", "接收方用 TapScene 导入。") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(shape = RoundedCornerShape(8.dp), onClick = { onExport?.invoke() }, enabled = sealedSummary != null && onExport != null && !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (busy) "正在准备" else "保存到文件") }
                OutlinedButton(shape = RoundedCornerShape(8.dp), onClick = { onShare?.invoke() }, enabled = sealedSummary?.origin == "local" && onShare != null && !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("系统分享") }
            }
            Text("临时分享入口 24 小时有效，已发送副本无法收回。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        }
        DetailSection("托管链接", "主动上传封存后的安全内容，由持链者观看。") {
            ShellLabelValue("有效期选项", "1 天 / 7 天 / 30 天")
            ShellActionRow("托管账号与版本", "查看账号入口与已发布版本管理。", onClick = onAccount)
            OutlinedButton(shape = RoundedCornerShape(8.dp), onClick = { onHosting?.invoke() }, enabled = onHosting != null && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (onHosting == null) "确认并发布" else "选择本机封存版（开发托管）") }
        }
        DetailSection("AI 数据包", "导出完整图与有限渲染计划，在独立环境中制作动画。") {
            ShellActionRow("查看数据包配置", "路径、画布、停留时间与可见区域。", onClick = onAi)
        }
        if (onHosting != null) StatusNote("仅连接本机合成开发服务。选择固定版本、登录合成账号并再次确认后才会上传；不会自动发布。")
        else StatusNote(if (sealedSummary == null) "先完成成品复核与封存，再保存离线观看包。托管尚未接入；AI 数据包可配置后本机导出。" else "此文件保留当前固定版本。托管尚未接入；AI 数据包可配置后本机导出。")
        Column {
            ShellActionRow("返回成品复核", onClick = onReview)
            ShellDivider()
            ShellActionRow("托管版本管理", onClick = onVersions)
        }
    }
}

@Composable
fun AiPackageScreen(
    onBack: () -> Unit,
    state: ReleaseUiState = ReleaseUiState(),
    onChooseRelease: (String) -> Unit = {},
    onChooseEdge: (String) -> Unit = {},
    onPrevious: () -> Unit = {},
    onReset: () -> Unit = {},
    onCanvas: (Boolean) -> Unit = {},
    onHold: (String, Int) -> Unit = { _, _ -> },
    onEffect: (String, String, String?, String?) -> Unit = { _, _, _, _ -> },
    onExport: () -> Unit = {},
) {
    val config = state.aiConfiguration
    val resolved = remember(config) { config?.let { runCatching { it.resolve() } } }
    val plan = resolved?.getOrNull()
    var confirmExport by remember(config) { mutableStateOf(false) }
    var chooseSource by rememberSaveable { mutableStateOf(false) }
    var chooseNext by remember(config) { mutableStateOf(false) }
    val last = config?.visits?.lastOrNull()
    val lastStep = config?.scene?.states?.firstOrNull { it.id == last?.stateId }
    val needsNext = last != null && last.selectedEdgeId == null && lastStep?.terminal == false
    val selectedFrames = remember(config) { config?.let { runCatching { aiSelectedDurationFrames(it) }.getOrNull() } }
    Column(Modifier.fillMaxSize().background(ShellColors.Background)) {
        ShellTopBar("AI 动画配置", onBack)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            state.message?.let { Text(it, color = ShellColors.Accent) }
            DetailSection("演示版本") {
                if (config == null || chooseSource) {
                    if (state.releases.isEmpty()) Text("先完成成品复核与封存，再选择固定版本。", color = ShellColors.Muted)
                    state.releases.forEach { item ->
                        ShellActionRow(item.title, "${item.stepCount} 个步骤 · ${formatReleaseDate(item.sealedAt)} · ${if (item.origin == "local") "本机成品" else "导入版本"}",
                            onClick = { chooseSource = false; onChooseRelease(item.id) }, enabled = !state.busy)
                    }
                } else {
                    ShellLabelValue("版本", config.scene.title)
                    Text("${config.scene.states.size} 个步骤 · ${config.scene.regions.size} 个可用区域", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    TextButton(onClick = { chooseSource = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("换一个版本") }
                }
            }
            if (config != null && !chooseSource) {
                if (config.fromDraft) Text("此计划随草稿封存。需要调整时，请回项目的动画计划编辑并重新封存。", color = ShellColors.Muted)
                DetailSection("画面方向", "选择生成视频的横竖方向。") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { onCanvas(false) }, enabled = !state.busy && !config.fromDraft, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("${if (config.width == 1080) "✓ " else ""}竖屏 9:16") }
                        OutlinedButton(onClick = { onCanvas(true) }, enabled = !state.busy && !config.fromDraft, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("${if (config.width == 1920) "✓ " else ""}横屏 16:9") }
                    }
                }
                DetailSection("播放顺序", "逐步选择接下来播放的内容，也可以返回之前的步骤。视频最长 10 分钟。") {
                    config.visits.forEachIndexed { index, visit ->
                        val step = config.scene.states.single { it.id == visit.stateId }
                        val edge = config.scene.edges.firstOrNull { it.id == visit.selectedEdgeId }
                        val effects = config.effects.filter { it.visitId == visit.visitId }
                        var showEffects by rememberSaveable(visit.visitId) { mutableStateOf(false) }
                        var annotation by rememberSaveable(visit.visitId) { mutableStateOf(effects.firstOrNull { it.type == "annotation" }?.text.orEmpty()) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${index + 1}. ${step.title}", style = MaterialTheme.typography.titleSmall)
                            Text(edge?.let { "接着：${it.label}" } ?: if (step.terminal) "播放到这里结束" else "请选择下一步", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onHold(visit.visitId, maxOf(1, visit.holdFrames - 30)) }, enabled = !state.busy && !config.fromDraft && visit.holdFrames > 1, modifier = Modifier.heightIn(min = 48.dp)) { Text("− 1 秒") }
                                Text("停留 ${aiSeconds(visit.holdFrames)} 秒", modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = { onHold(visit.visitId, minOf(RenderPlan.MAX_HOLD_FRAMES, visit.holdFrames + 30)) }, enabled = !state.busy && !config.fromDraft && visit.holdFrames < RenderPlan.MAX_HOLD_FRAMES, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ 1 秒") }
                            }
                            effects.forEach { Text(aiEffectLabel(config, it), style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted) }
                            TextButton(onClick = { showEffects = !showEffects }, enabled = !config.fromDraft, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showEffects) "收起效果" else if (effects.isEmpty()) "添加效果" else "调整效果") }
                            if (showEffects) {
                                if (edge?.hotspotId != null) TextButton(onClick = { onEffect(visit.visitId, "click", null, null) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("${if (effects.any { it.type == "click" }) "✓ " else ""}点击提示") }
                                if (edge?.toStateId != null && edge.transitionAssetId == null) TextButton(onClick = { onEffect(visit.visitId, "transition", null, null) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("${if (effects.any { it.type == "transition" }) "✓ " else ""}平滑切换到下一步") }
                                config.scene.regions.filter { it.stateId == visit.stateId }.forEach { region ->
                                    Text("区域：${region.name}", style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("focus" to "聚焦", "highlight" to "高亮").forEach { (type, label) ->
                                            OutlinedButton(onClick = { onEffect(visit.visitId, type, region.id, null) }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("${if (effects.any { it.type == type && it.regionId == region.id }) "✓ " else ""}$label") }
                                        }
                                    }
                                }
                                OutlinedTextField(value = annotation, onValueChange = { if (it.length <= 240) annotation = it }, enabled = !state.busy,
                                    label = { Text("画面标注（最多 240 字）") }, modifier = Modifier.fillMaxWidth())
                                TextButton(onClick = { onEffect(visit.visitId, "annotation", null, annotation) }, enabled = !state.busy && (annotation.isNotBlank() || effects.any { it.type == "annotation" }), modifier = Modifier.heightIn(min = 48.dp)) { Text(if (effects.any { it.type == "annotation" }) "移除已有标注" else "加入标注") }
                            }
                        }
                        ShellDivider()
                    }
                    if (!needsNext) Text("已选好播放顺序", color = ShellColors.Accent)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onPrevious, enabled = !state.busy && !config.fromDraft && (config.visits.size > 1 || last?.selectedEdgeId != null), modifier = Modifier.heightIn(min = 48.dp)) { Text("撤回最后一步") }
                        TextButton(onClick = onReset, enabled = !state.busy && !config.fromDraft, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新选择") }
                    }
                }
                DetailSection("保存前检查") {
                    ShellLabelValue("视频时长", plan?.let { "约 ${aiSeconds(it.totalFrames)} 秒 · ${it.visits.size} 步" } ?: "请先选好播放顺序并检查效果")
                    Text("包含这个版本的全部已复核画面、裁片和过渡视频，也保留未选分支。原始录屏不会打包。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    Text("改停留时间会保留效果；超出新停留范围时会阻止保存，请调整后再检查。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    Text("手机保存动画数据包，再交给电脑上的配套渲染工具生成 MP4。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    if (plan != null) AiTechnicalDetails(config, plan)
                }
            }
        }
        if (config != null && !chooseSource) {
            ShellDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${config.visits.size} 步 / ${if (plan != null) "总时长" else "当前时长"} ${
                    (plan?.totalFrames ?: selectedFrames)?.let { "约 ${aiSeconds(it)} 秒" } ?: "待检查"
                }", style = MaterialTheme.typography.labelMedium, color = ShellColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Button(onClick = { if (needsNext) chooseNext = true else confirmExport = true },
                    enabled = !state.busy && (needsNext || plan != null), shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (needsNext) "接着播放" else "检查并保存")
                }
            }
        }
    }
    if (chooseNext && config != null && last != null && needsNext) AlertDialog(
        onDismissRequest = { chooseNext = false }, title = { Text("接着播放") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(lastStep?.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                config.scene.edges.filter { it.fromStateId == last.stateId }.forEach { edge ->
                    val target = edge.toStateId?.let { targetId -> config.scene.states.single { it.id == targetId }.title } ?: "结束：${edge.endLabel}"
                    OutlinedButton(onClick = { chooseNext = false; onChooseEdge(edge.id) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("${edge.label} → $target") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { chooseNext = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回调整") } },
    )
    if (confirmExport && plan != null && config != null) AlertDialog(
        onDismissRequest = { confirmExport = false }, title = { Text("保存 AI 动画包？") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { AiExportReviewContent(config, plan) } },
        confirmButton = { TextButton(onClick = { confirmExport = false; onExport() }) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("返回调整") } },
    )
}

/** Informational duration of the explicit visits only; it never makes an unfinished plan exportable. */
internal fun aiSelectedDurationFrames(config: AiPackageConfiguration): Int = config.visits.sumOf { visit ->
    val edge = config.scene.edges.firstOrNull { it.id == visit.selectedEdgeId }
    val transition = edge?.transitionAssetId?.let { id ->
        RenderPlan.millisecondsToFrames(requireNotNull(config.scene.assets.firstOrNull { it.id == id }?.durationMs))
    } ?: 0
    val overlap = config.effects.filter { it.visitId == visit.visitId && it.type == "transition" }.sumOf { it.durationFrames }
    visit.holdFrames + transition - overlap
}

private fun aiSeconds(frames: Int): String = String.format(Locale.ROOT, "%.2f", frames / 30.0)

private fun aiEffectLabel(config: AiPackageConfiguration, effect: RenderPlan.Effect): String {
    val region = config.scene.regions.firstOrNull { it.id == effect.regionId }?.name ?: "所选范围"
    return when (effect.type) {
        "focus" -> "聚焦：$region"
        "highlight" -> "高亮：$region"
        "annotation" -> "标注：${effect.text.orEmpty()}"
        "click" -> "点击提示：${config.scene.hotspots.firstOrNull { it.id == effect.hotspotId }?.label ?: "所选热点"}"
        "transition" -> "平滑切换到下一步"
        else -> "效果"
    }
}

@Composable
internal fun AiExportReviewContent(config: AiPackageConfiguration, plan: RenderPlan) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(config.scene.title, style = MaterialTheme.typography.titleSmall)
        Text("${if (plan.width < plan.height) "竖屏" else "横屏"} · 约 ${aiSeconds(plan.totalFrames)} 秒 · ${plan.visits.size} 步")
        plan.visits.forEachIndexed { index, visit ->
            val step = config.scene.states.single { it.id == visit.stateId }
            val edge = config.scene.edges.firstOrNull { it.id == visit.selectedEdgeId }
            Text("${index + 1}. ${step.title} · 停留 ${aiSeconds(visit.holdFrames)} 秒", style = MaterialTheme.typography.titleSmall)
            if (edge != null) Text(if (edge.toStateId == null) "结束：${edge.endLabel ?: edge.label}" else "接着：${edge.label}",
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            if (edge?.transitionAssetId != null) Text("播放已复核过渡视频", style = MaterialTheme.typography.bodySmall)
            plan.effects.filter { it.visitId == visit.visitId }.forEach {
                Text(aiEffectLabel(config, it), style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            }
        }
        Text("包含全部已复核画面、裁片和过渡视频，保留未选分支。请确认文字标注和区域名称。", style = MaterialTheme.typography.bodySmall)
        AiTechnicalDetails(config, plan)
    }
}

@Composable
private fun AiTechnicalDetails(config: AiPackageConfiguration, plan: RenderPlan) {
    var expanded by remember(plan.contentDigest, config) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (expanded) "收起技术详情" else "展开技术详情与文件清单")
    }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${plan.width} × ${plan.height} · 30 fps · ${plan.totalFrames} 帧\n版本 ${plan.releaseId}\n内容 SHA-256 ${plan.contentDigest}", style = MaterialTheme.typography.bodySmall)
        plan.visits.forEachIndexed { index, visit ->
            Text("${index + 1}. visit ${visit.visitId} · state ${visit.stateId}\nedge ${visit.selectedEdgeId ?: "结束"} · ${visit.holdFrames} 停留帧", style = MaterialTheme.typography.bodySmall)
        }
        plan.effects.forEachIndexed { index, effect ->
            Text("效果 ${index + 1}：${effect.type} · 起始 ${effect.startFrame} 帧 · 持续 ${effect.durationFrames} 帧\n访问 ${effect.visitId}\n热点 ${effect.hotspotId ?: "无"} · 区域 ${effect.regionId ?: "无"}" +
                (effect.rect?.let { "\n范围 ${it.x}, ${it.y}, ${it.width}, ${it.height}" } ?: "") +
                (effect.text?.let { "\n标注：$it" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
        Text("除 manifest 自身外的完整文件清单：", style = MaterialTheme.typography.titleSmall)
        AiPackageCodec.fileList(config.scene, plan).forEach { file ->
            Text("${file.path} · ${formatShellBytes(file.byteLength)}\n${file.sha256}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun HostingAccountScreen(onBack: () -> Unit, onVersions: () -> Unit) {
    ServicePage("托管账号", onBack) {
        DetailSection("管理你的托管链接", "本机创作与离线观看无需登录。账号用于发布、到期与撤销管理。") {
            OutlinedTextField(
                value = "", onValueChange = {}, enabled = false,
                label = { Text("邮箱") }, placeholder = { Text("你的邮箱地址") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = "", onValueChange = {}, enabled = false,
                label = { Text("邮箱验证码") }, placeholder = { Text("输入验证码") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { Text("发送验证码", style = MaterialTheme.typography.labelMedium, color = ShellColors.Muted, modifier = Modifier.padding(end = 12.dp)) },
            )
        }
        UnavailableAction("验证并继续", "托管账号服务尚未接入，没有建立登录会话。")
        Column {
            ShellDivider()
            ShellActionRow("托管版本管理", "查看版本管理范围与发布状态。", onClick = onVersions)
        }
        DetailSection("账号与本机内容") {
            Text("账号恢复发布管理。原素材和草稿保存在这台设备，不会随账号同步。发布前会再次确认版本、期限与上传范围。", style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
        }
    }
}

@Composable
fun HostedVersionsScreen(onBack: () -> Unit, onAccount: () -> Unit) {
    ServicePage("托管版本", onBack) {
        ShellActionRow("托管账号", "服务尚未接入，无法读取账号与发布记录。", onClick = onAccount)
        ShellDivider()
        ScreenEmpty("暂无可读取的托管记录", "版本管理按账号展示发布版本。每个版本会列出创建时间、到期时间与服务端状态。")
        DetailSection("版本管理范围") {
            ShellActionRow("查看与分享", "打开或复制已发布版本的观看链接。")
            ShellDivider()
            ShellActionRow("到期与撤销", "按版本管理；以服务端确认为准。")
            ShellDivider()
            ShellActionRow("本机原项目", "草稿后续修改不改变已经发布的固定版本。")
        }
        OutlinedButton(shape = RoundedCornerShape(8.dp), onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("刷新版本") }
        Text("已下载的离线副本无法通过撤销链接收回。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
    }
}

@Composable
fun TaskDetailsScreen(
    task: ShellTaskInfo?,
    onBack: () -> Unit,
    onProject: () -> Unit,
    onStorage: () -> Unit,
) {
    ServicePage("任务详情", onBack) {
        if (task == null) {
            ScreenEmpty("当前没有可显示的任务", "执行分析、生成、导出或发布后，这里会展示真实阶段与恢复入口。")
            DetailSection("任务记录") {
                ShellLabelValue("关联项目 / 修订", "无任务")
                ShellLabelValue("阶段与完成量", "无任务")
                ShellLabelValue("耗时与恢复状态", "无任务")
            }
        } else {
            DetailSection(task.title) {
                task.projectTitle?.let { ShellLabelValue("项目", it) }
                task.revisionLabel?.let { ShellLabelValue("固定输入", it) }
                ShellLabelValue("当前阶段", task.stage)
                val completed = task.completed
                val total = task.total
                if (completed != null && total != null && completed >= 0 && total > 0 && completed <= total) {
                    LinearProgressIndicator(progress = { completed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    ShellLabelValue("完成量", "$completed / $total")
                } else if (completed != null && completed >= 0) {
                    ShellLabelValue("已完成", "$completed 项")
                }
                task.elapsedLabel?.let { ShellLabelValue("耗时", it) }
                task.recoveryNote?.let { StatusNote(it) }
            }
        }
        Column {
            ShellDivider()
            ShellActionRow("回到项目", "已保存内容保留在项目中。", onClick = onProject)
            ShellDivider()
            ShellActionRow("设置与存储", "查看本机素材与存储占用。", onClick = onStorage)
        }
    }
}

@Composable
fun SettingsStorageScreen(
    sourceCount: Int?,
    sourceBytes: Long?,
    onBack: () -> Unit,
    onAccount: () -> Unit,
    onVersions: () -> Unit,
    onManageSources: () -> Unit,
) {
    var showFormats by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    ServicePage("设置与存储", onBack) {
        DetailSection("账号与托管") {
            ShellActionRow("托管账号", "仅用于主动发布与管理链接。", onClick = onAccount)
            ShellDivider()
            ShellActionRow("托管版本", "查看到期、撤销与已发布版本。", onClick = onVersions)
        }
        DetailSection("本机存储") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sourceBytes?.let(::formatShellBytes) ?: "—", style = MaterialTheme.typography.titleLarge)
                    Text("保留的原素材", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sourceCount?.let { "$it 项" } ?: "—", style = MaterialTheme.typography.titleLarge)
                    Text("素材条目", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                }
            }
            Text("以上仅统计当前读取到的原素材，不代表应用全部占用。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            ShellActionRow("管理本机保留素材", "查看现有素材；删除前会单独确认。", onClick = onManageSources)
            ShellDivider()
            ShellActionRow("临时缓存", "完整占用统计与安全清理尚未接入。", value = "待统计", enabled = false)
        }
        DetailSection("使用与隐私") {
            ShellActionRow("支持格式与导入说明", value = if (showFormats) "收起" else "展开", onClick = { showFormats = !showFormats })
            if (showFormats) {
                Text("目前可导入 H.264 / H.265 编码的 MP4 录屏；截图导入尚未接入。视频解码能力取决于本机，导入时会校验格式、大小与实际画面。原素材只保存在应用私有空间。", style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
            }
            ShellDivider()
            ShellActionRow("本机内容与设备丢失", value = if (showPrivacy) "收起" else "展开", onClick = { showPrivacy = !showPrivacy })
            if (showPrivacy) {
                Text("原素材和草稿不随账号同步，也不进入系统云备份。卸载应用或设备丢失可能造成丢失。可保存已封存的离线观看包和 AI 数据包，也可单独保存复核后的 PNG 或短片。观看包不包含原片或可继续编辑的草稿；托管尚未接入。", style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
            }
        }
        DetailSection("关于 TapScene") {
            ShellLabelValue("应用", "TapScene 点演 · 开发预览")
            ShellLabelValue("源码许可", "Apache-2.0")
            ShellLabelValue("交付协议", "生成数据包时记录版本")
            ShellLabelValue("服务条款与隐私政策", "托管服务接入时提供")
        }
    }
}

fun formatShellBytes(bytes: Long): String = when {
    bytes < 0L -> "待统计"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/** Production hosting UI; callbacks are empty only in isolated screenshot fixtures. */
data class HostingCallbacks(
    val onBack: () -> Unit = {},
    val onSources: () -> Unit = {},
    val onRelease: (String) -> Unit = {},
    val onAccount: () -> Unit = {},
    val onEmail: (String) -> Unit = {},
    val onCode: (String) -> Unit = {},
    val onRequestCode: () -> Unit = {},
    val onVerifyCode: () -> Unit = {},
    val onExpiry: (Int) -> Unit = {},
    val onPublish: () -> Unit = {},
    val onTasks: () -> Unit = {},
    val onTask: (String) -> Unit = {},
    val onResume: () -> Unit = {},
    val onStopObservation: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onProjects: () -> Unit = {},
    val onMoreProjects: () -> Unit = {},
    val onProject: (String) -> Unit = {},
    val onRefreshVersions: () -> Unit = {},
    val onMoreVersions: () -> Unit = {},
    val onRevoke: (String) -> Unit = {},
    val onCheckRevocation: (String) -> Unit = {},
    val onLogout: () -> Unit = {},
    val onRetrySessions: () -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    val onCopyLink: (String) -> Unit = {},
)

@Composable
fun LocalHostingContent(state: HostingUiState, actions: HostingCallbacks) {
    key(state.page, state.account?.accountId) {
        val title = when (state.page) {
            HostingPage.SOURCE -> "选择封存版"
            HostingPage.ACCOUNT -> "本地托管账号"
            HostingPage.CONFIRM -> "确认开发发布"
            HostingPage.TASKS -> "发布与恢复"
            HostingPage.PROJECTS -> "选择托管项目"
            HostingPage.VERSIONS -> "托管版本"
        }
        ServicePage(title, actions.onBack) {
            Text("本机合成开发 · 非公网服务", color = ShellColors.Accent, style = MaterialTheme.typography.labelLarge)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { StatusNote(it) }
            when (state.page) {
                HostingPage.SOURCE -> HostingSourceBody(state, actions)
                HostingPage.ACCOUNT -> HostingAccountBody(state, actions)
                HostingPage.CONFIRM -> HostingConfirmationBody(state, actions)
                HostingPage.TASKS -> HostingTasksBody(state, actions)
                HostingPage.PROJECTS -> HostingProjectsBody(state, actions)
                HostingPage.VERSIONS -> HostingVersionsBody(state, actions)
            }
        }
    }
}

@Composable
private fun HostingSourceBody(state: HostingUiState, actions: HostingCallbacks) {
    DetailSection("明确选择一个本机版本", "包括此前封存的旧版。草稿修订不会改变这些内容，导入包不能作为发布来源。") {
        if (state.releases.isEmpty() && !state.busy) Text("没有可选择的本机封存版。请先在项目中完成实际内容复核与封存。", color = ShellColors.Muted)
        state.releases.forEach { item ->
            ShellDivider()
            ShellActionRow(item.title, "${formatReleaseDate(item.sealedAt)} · ${item.stepCount} 步\n版本 ${item.id}\n内容 ${item.contentDigest}",
                "选择", onClick = { actions.onRelease(item.id) }, enabled = !state.busy)
        }
    }
    OutlinedButton(onClick = actions.onSources, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("刷新本机封存版") }
    ShellActionRow("账号与已有任务", "重新登录原账号可恢复固定快照任务。", onClick = actions.onAccount, enabled = !state.busy)
}

@Composable
private fun HostingAccountBody(state: HostingUiState, actions: HostingCallbacks) {
    var logoutConfirm by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.resendAt) {
        now = System.currentTimeMillis()
        while (now < state.resendAt) { delay(1000); now = System.currentTimeMillis() }
    }
    val wait = ((state.resendAt - now + 999L) / 1000L).coerceAtLeast(0L)
    DetailSection("本地开发服务") {
        Text(HOSTED_LOCAL_ENDPOINT, style = MaterialTheme.typography.bodyMedium)
        Text("电脑启动本地服务后，使用 adb reverse tcp:4173 tcp:4173。仅用合成素材和 @example.test 账号。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
    }
    if (state.pendingSessionRevocations > 0) {
        StatusNote("${state.pendingSessionRevocations} 个已知会话的撤销尚待确认。凭据已加密保留，恢复服务后可重试。")
        OutlinedButton(onClick = actions.onRetrySessions, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重试待撤销会话") }
    }
    if (state.account == null) {
        DetailSection("合成邮箱登录", "验证码仅写入电脑的本机 synthetic inbox，不会发送真实邮件。请自行查看并手动输入；Android 不读取收件箱。") {
            OutlinedTextField(state.email, actions.onEmail, enabled = !state.busy, singleLine = true,
                label = { Text("开发邮箱") }, placeholder = { Text("author@example.test") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = actions.onRequestCode, enabled = !state.busy && wait == 0L && state.email.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (wait > 0) "$wait 秒后重新获取" else if (state.challengeReady) "重新生成本机验证码" else "生成本机验证码") }
            OutlinedTextField(state.code, actions.onCode, enabled = !state.busy && state.challengeReady, singleLine = true,
                label = { Text("手动输入 6 位验证码") }, modifier = Modifier.fillMaxWidth())
            state.challengeExpiresAt?.let { Text("验证码到期：${hostingDate(it)}", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted) }
            Button(onClick = actions.onVerifyCode, enabled = !state.busy && state.challengeReady && state.code.length == 6,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("验证并继续") }
        }
        Text("取消或返回会清空验证码。若会话创建响应完全丢失，无法撤销未知令牌；开发会话最长一天后到期。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
    } else {
        DetailSection("当前账号") {
            ShellLabelValue("邮箱", state.account.email)
            state.sessionExpiresAt?.let { ShellLabelValue("会话到期", hostingDate(it)) }
            ShellActionRow("选择封存版发布", "每次发布都要确认固定版本、上传范围与期限。", onClick = actions.onSources, enabled = !state.busy)
            ShellDivider()
            ShellActionRow("发布与恢复", "查看当前账号在这台设备上的固定快照任务。", onClick = actions.onTasks, enabled = !state.busy)
            ShellDivider()
            ShellActionRow("托管项目与版本", "独立选择服务端项目，逐版查看与撤销。", onClick = actions.onProjects, enabled = !state.busy)
            OutlinedButton(onClick = { logoutConfirm = true }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("退出开发账号") }
        }
        Text("登出需要服务端确认。已发布链接仍有效，须在版本管理中另行撤销。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
    }
    if (logoutConfirm) AlertDialog(onDismissRequest = { logoutConfirm = false }, title = { Text("退出开发账号？") },
        text = { Text("先请求服务端撤销当前会话，确认成功后才清除本机凭据。已发布链接仍按原期限有效；上传快照保留，恢复需重新登录原账号。") },
        confirmButton = { TextButton(onClick = { logoutConfirm = false; actions.onLogout() }, enabled = !state.busy) { Text("确认登出") } },
        dismissButton = { TextButton(onClick = { logoutConfirm = false }) { Text("保留登录") } })
}

@Composable
private fun HostingConfirmationBody(state: HostingUiState, actions: HostingCallbacks) {
    val binding = state.selected
    if (binding == null) { ScreenEmpty("尚未选择固定版本", "请重新明确选择一个本机封存版。", "选择版本", actions.onSources); return }
    val scene = binding.scene
    DetailSection(binding.summary.title, "固定修订 ${binding.projectRevision} · ${scene.states.size} 个步骤") {
        ShellLabelValue("开发账号", state.account?.email ?: "未登录")
        ShellLabelValue("本地地址", HOSTED_LOCAL_ENDPOINT)
        Text("版本 ${binding.summary.id}\n内容 SHA-256 ${binding.summary.contentDigest}\n文件清单 SHA-256 ${binding.fileListDigest}", style = MaterialTheme.typography.bodySmall)
    }
    DetailSection("实际上传范围", "仅 canonical scene 与声明的已复核安全资产。") {
        fun assets(role: String) = scene.assets.filter { it.role == role }
        listOf(ViewerScene.Asset.ROLE_IMAGE to "安全 PNG", ViewerScene.Asset.ROLE_REGION_CROP to "区域 PNG 裁片", ViewerScene.Asset.ROLE_TRANSITION to "无声 MP4").forEach { (role, label) ->
            val files = assets(role)
            ShellLabelValue(label, "${files.size} 个 · ${formatShellBytes(files.sumOf { it.byteLength })}")
        }
        ShellLabelValue("canonical scene", formatShellBytes(ViewerPackageCodec.writeScene(scene).size.toLong()))
        ShellLabelValue("总计", "${scene.assets.size} 个资产 · ${formatShellBytes(binding.uploadByteLength)}")
        Text("不会上传 ZIP、原录屏、原截图、OCR、复核记录或 AI 动画计划。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
    }
    DetailSection("链接有效期", "从服务端正式发布时开始计算。持链者可观看，旧链接内容不随草稿改变。") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 7, 30).forEach { days ->
                if (state.expiryDays == days) Button(onClick = { actions.onExpiry(days) }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("$days 天 ✓") }
                else OutlinedButton(onClick = { actions.onExpiry(days) }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("$days 天") }
            }
        }
    }
    Button(onClick = actions.onPublish, enabled = !state.busy && state.account != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("确认范围并开始发布") }
    Text("点击确认后才建立独立快照并上传。返回或登录成功都不会自动发布。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
    OutlinedButton(onClick = actions.onSources, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新选择版本") }
}

@Composable
private fun HostingTasksBody(state: HostingUiState, actions: HostingCallbacks) {
    var cancelConfirm by remember(state.task?.taskId) { mutableStateOf(false) }
    val task = state.task
    ShellLabelValue("任务账号", state.account?.email ?: "请登录原账号")
    if (task == null) {
        if (state.tasks.isEmpty() && !state.busy) ScreenEmpty("没有此账号的本机任务", "发布确认后才会创建快照。其他账号的任务不会出现在这里。")
        state.tasks.forEach { item ->
            ShellDivider()
            ShellActionRow(item.title, "修订 ${item.projectRevision} · ${hostingTaskLabel(item)}\n版本 ${item.releaseId}", "查看", onClick = { actions.onTask(item.taskId) }, enabled = !state.busy)
        }
        OutlinedButton(onClick = actions.onTasks, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新读取本机任务") }
    } else {
        DetailSection(task.title, hostingTaskLabel(task)) {
            ShellLabelValue("固定内容", "修订 ${task.projectRevision} · ${task.assetCount} 个资产 · ${formatShellBytes(task.byteLength)}")
            ShellLabelValue("有效期", "${task.expiryDays} 天（正式发布起）")
            Text("版本 ${task.releaseId}\n内容 ${task.contentDigest}", style = MaterialTheme.typography.bodySmall)
            Text("保留原账号、幂等身份与独立快照。原项目修改或删除不改变此任务。缺失资产按整个文件重传，不是字节断点续传。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            task.errorCode?.let { Text("最近状态：$it", style = MaterialTheme.typography.bodySmall, color = ShellColors.Accent) }
            if (task.cancelRequested && task.publication == null && task.state != "cancelled") StatusNote("取消意图已保留，服务端结果尚待确认。查询不会改成继续发布。")
        }
        val publication = task.publication
        if (publication != null) {
            StatusNote("已收到正式发布回执：版本 ${publication.versionOrdinal}。${when (publication.status) {
                "revoked" -> "此回执记录已撤销。"
                "expired" -> "此回执记录已到期。"
                else -> if (task.cancelRequested) "取消时已发布，若需收回访问，请在版本管理中另行确认撤销。" else "当前访问状态请在版本管理中刷新确认。"
            }}")
            HostingLinkActions(publication, actions)
            ShellActionRow("管理已发布版本", "选择对应的服务端项目后逐版撤销。", onClick = actions.onProjects, enabled = !state.busy)
        } else {
            if (task.state == "validating") StatusNote("校验中（202），还没有正式发布回执。请继续查询；暂不提供观看链接。")
            if (state.observing) OutlinedButton(onClick = actions.onStopObservation, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("停止本页观察") }
            else if (task.state !in setOf("cancelled", "expired", "failed")) Button(onClick = actions.onResume, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (task.cancelRequested) "重新确认取消结果" else "查询并继续原任务") }
            if (task.state !in setOf("cancelled", "expired", "failed")) OutlinedButton(onClick = { cancelConfirm = true }, enabled = !state.busy || state.observing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消上传与发布") }
        }
        ShellActionRow("返回任务列表", "离开观察不会向服务端取消。", onClick = actions.onTasks)
    }
    ShellActionRow("选择其他封存版", onClick = actions.onSources, enabled = !state.busy)
    if (cancelConfirm) AlertDialog(onDismissRequest = { cancelConfirm = false }, title = { Text("确认取消此发布任务？") },
        text = { Text("将记录取消意图并请求服务端取消。若提交已经完成，会显示正式发布回执；已发布链接须另外确认撤销。仅离开本页不会取消。") },
        confirmButton = { TextButton(onClick = { cancelConfirm = false; actions.onCancel() }) { Text("确认取消任务") } },
        dismissButton = { TextButton(onClick = { cancelConfirm = false }) { Text("继续保留") } })
}

@Composable
private fun HostingProjectsBody(state: HostingUiState, actions: HostingCallbacks) {
    ShellLabelValue("开发账号", state.account?.email ?: "未登录")
    Text("请选择一个服务端托管项目。不会根据当前本机项目猜测归属；原项目删除后，已发布版本仍可独立管理。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
    if (state.projects.isEmpty() && !state.busy) ScreenEmpty("没有已读取的托管项目", "可以刷新重试，或先选择本机封存版发布。")
    state.projects.forEach { project ->
        ShellDivider()
        ShellActionRow(project.title, "${project.activePublicationCount} 个有效版本 · 最新编号 ${project.latestVersionOrdinal}\n服务端 ${project.serverProjectId}",
            "查看版本", onClick = { actions.onProject(project.serverProjectId) }, enabled = !state.busy)
    }
    if (state.projectsCursor != null) OutlinedButton(onClick = actions.onMoreProjects, enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("加载更多项目") }
    OutlinedButton(onClick = actions.onProjects, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("刷新项目") }
    ShellActionRow("选择封存版发布", onClick = actions.onSources, enabled = !state.busy)
}

@Composable
private fun HostingVersionsBody(state: HostingUiState, actions: HostingCallbacks) {
    var revoke by remember { mutableStateOf<HostedModels.Publication?>(null) }
    DetailSection(state.project?.title ?: "未选择项目") {
        ShellLabelValue("账号", state.account?.email ?: "未登录")
        Text(if (state.versionsFresh) "已收到服务端版本列表" else "列表尚未刷新确认；缓存状态不能证明链接已失效。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        Text("服务端项目 ${state.project?.serverProjectId.orEmpty()}", style = MaterialTheme.typography.bodySmall)
    }
    if (state.publications.isEmpty() && !state.busy) Text("此页没有已读取的发布版本。", color = ShellColors.Muted)
    state.publications.forEach { publication ->
        ShellDivider()
        DetailSection("版本 ${publication.versionOrdinal} · ${publication.title}") {
            ShellLabelValue("服务端状态", if (publication.publicationId in state.pendingRevocations) "撤销待确认" else when (publication.status) { "active" -> "有效"; "revoked" -> "已撤销"; "expired" -> "已到期"; else -> "未知，需刷新" })
            ShellLabelValue("创建", hostingDate(publication.createdAt))
            ShellLabelValue("到期", hostingDate(publication.expiresAt))
            Text("封存版本 ${publication.releaseId}\n内容 ${publication.contentDigest}", style = MaterialTheme.typography.bodySmall)
            if (publication.publicationId in state.pendingRevocations) {
                Text("尚未取得撤销确认，链接可能仍有效。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Accent)
                OutlinedButton(onClick = { actions.onCheckRevocation(publication.publicationId) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新查询此版本") }
            }
            HostingLinkActions(publication, actions)
            if (publication.status == "active") OutlinedButton(onClick = { revoke = publication }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("撤销此版本…") }
        }
    }
    if (state.publicationsCursor != null) OutlinedButton(onClick = actions.onMoreVersions, enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("加载更多版本") }
    OutlinedButton(onClick = actions.onRefreshVersions, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("刷新版本") }
    ShellActionRow("重新选择托管项目", onClick = actions.onProjects, enabled = !state.busy)
    Text("撤销须服务端确认。已授权传输与已获取的离线副本无法远程收回。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
    revoke?.let { publication -> AlertDialog(onDismissRequest = { revoke = null }, title = { Text("撤销版本 ${publication.versionOrdinal}？") },
        text = { Text("${publication.title}\n${publication.releaseId}\n确认后请求服务端停止此版本的后续访问，不会影响其他版本。已获取的副本无法收回。") },
        confirmButton = { TextButton(onClick = { revoke = null; actions.onRevoke(publication.publicationId) }, enabled = !state.busy) { Text("确认撤销此版本") } },
        dismissButton = { TextButton(onClick = { revoke = null }) { Text("保持有效") } }) }
}

@Composable
private fun HostingLinkActions(publication: HostedModels.Publication, actions: HostingCallbacks) {
    if (publication.status != "active") return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { actions.onOpenLink(publication.shareUrl) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("打开本机链接") }
        OutlinedButton(onClick = { actions.onCopyLink(publication.shareUrl) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("复制链接") }
    }
    Text("这是 loopback 开发链接，不能作为公网分享地址。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
}

internal fun hostingTaskLabel(task: HostedModels.Task): String = when {
    task.publication != null -> when (task.publication.status) { "revoked" -> "已发布，现已撤销"; "expired" -> "已发布，现已到期"; else -> "已正式发布（回执）" }
    task.cancelRequested && task.state != "cancelled" -> "取消待确认"
    task.state == "prepared" -> "快照已固定，待上传"
    task.state == "receiving" -> "逐资产上传中 / 可恢复"
    task.state == "validating" -> "服务端校验中，尚未发布"
    task.state == "cancelled" -> if (task.uploadId == null) "未发起上传，已取消本机任务" else "服务端已确认取消"
    task.state == "expired" -> "上传预留已到期"
    task.state == "failed" -> "服务端校验失败"
    else -> "结果未知，需查询确认"
}

private fun hostingDate(value: String): String = runCatching {
    java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrElse { value }
