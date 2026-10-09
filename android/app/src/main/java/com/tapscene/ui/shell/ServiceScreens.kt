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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tapscene.data.ProjectSnapshot
import com.tapscene.ui.ProjectIssue
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
            Text("生成实际成品后，逐项复核图片、完整过渡、标题、讲解与裁片。", style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
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
            Text("完整交付检查与版本生成尚未接入，本机结构检查不代表已可交付。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            OutlinedButton(shape = RoundedCornerShape(8.dp), onClick = {}, enabled = false, modifier = Modifier.heightIn(min = 48.dp)) { Text("生成待复核成品") }
        }
        Column {
            ShellDivider()
            ShellActionRow("成品逐项复核", "检查真实输出，全部确认后封存版本。", onClick = onReview)
            ShellDivider()
            ShellActionRow("查看交付方式", "离线观看包、托管链接与 AI 工程包。", onClick = onDelivery)
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
) {
    ServicePage("交付方式", onBack) {
        WorkflowLine(2, listOf("检查", "成品复核", "交付"))
        Column {
            ShellLabelValue("封存版本", "尚未生成")
            ShellLabelValue("封存时间 / 体积", "等待完成复核")
        }
        DetailSection("离线观看包", "保存为文件，交给另一台设备离线观看。") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(shape = RoundedCornerShape(8.dp), onClick = {}, enabled = false, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("保存到文件") }
                OutlinedButton(shape = RoundedCornerShape(8.dp), onClick = {}, enabled = false, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("系统分享") }
            }
        }
        DetailSection("托管链接", "主动上传封存后的安全内容，由持链者观看。") {
            ShellLabelValue("有效期选项", "1 天 / 7 天 / 30 天")
            ShellActionRow("托管账号与版本", "查看账号入口与已发布版本管理。", onClick = onAccount)
            OutlinedButton(shape = RoundedCornerShape(8.dp), onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("确认并发布") }
        }
        DetailSection("AI 工程包", "导出完整图与有限渲染计划，在独立环境中制作动画。") {
            ShellActionRow("查看工程包配置", "路径、画布、停留时间与可见区域。", onClick = onAi)
        }
        StatusNote("封存、导出与托管尚未接入。交付需先完成真实成品复核。")
        Column {
            ShellActionRow("返回成品复核", onClick = onReview)
            ShellDivider()
            ShellActionRow("托管版本管理", onClick = onVersions)
        }
    }
}

@Composable
fun AiPackageScreen(onBack: () -> Unit) {
    ServicePage("AI 工程包配置", onBack) {
        DetailSection("来源与路径") {
            ShellLabelValue("来源版本", "尚未选择封存版本")
            ShellDivider()
            ShellActionRow("有限访问路径", "逐步选择出口，并明确结束位置。", value = "待配置")
            ShellDivider()
            ShellActionRow("回访步骤", "重复经过同一步时，作为独立访问记录。")
        }
        DetailSection("动画画布") {
            ShellLabelValue("竖屏", "1080 × 1920")
            ShellLabelValue("横屏", "1920 × 1080")
            ShellLabelValue("输出帧率", "30 fps")
        }
        DetailSection("画面编排") {
            ShellActionRow("停留与效果", "按每次访问设置停留时间和转场。", value = "待配置")
            ShellDivider()
            ShellActionRow("可见区域", "使用来源版本中的安全区域与裁片。", value = "待载入")
        }
        DetailSection("导出摘要") {
            ShellLabelValue("结构与安全媒体", "随来源版本固定")
            ShellLabelValue("渲染计划", "独立 render-plan 文件")
            Text("修改区域、媒体或文案需回到草稿，并封存新版本后再切换来源。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        }
        UnavailableAction("检查并导出", "AI 工程包配置与导出尚未接入。")
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
                Text("原素材和草稿不随账号同步，也不进入系统云备份。卸载应用或设备丢失可能造成丢失。目前可单独保存实际复核后的 PNG 图片或短片；观看包与托管尚未接入。", style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
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
