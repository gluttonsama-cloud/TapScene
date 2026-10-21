package com.tapscene.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class ProjectTab(val label: String) { STEPS("步骤"), SOURCES("素材"), CHECKS("检查与交付") }

/** Shared production chrome, also used by the host-side layout previews. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ProjectHomeFrame(
    onRecord: () -> Unit,
    onImportVideo: () -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
    onImportScreenshot: () -> Unit = {},
    onImportAi: () -> Unit = {},
    onClickPlan: () -> Unit,
    content: @Composable (@Composable () -> Unit) -> Unit,
) {
    var showCreateOptions by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("TapScene") {
            TextButton(onClick = onSettings) { Text("设置") }
        }
        // A single scrolling body keeps projects reachable on short screens and with IME insets.
        Box(Modifier.weight(1f)) {
            content {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onClickPlan, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("悬浮编排") }
                    Text("在目标 App 上放置点位，再按顺序播放并录屏。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    OutlinedButton(onClick = { showCreateOptions = true }, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("其他创建方式") }
                }
            }
        }
        GlobalNavigation(false, {}, onLibrary)
    }
    if (showCreateOptions) ModalBottomSheet(onDismissRequest = { showCreateOptions = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("其他创建方式", style = MaterialTheme.typography.titleMedium)
            listOf("手动录屏" to onRecord, "导入录屏" to onImportVideo,
                "导入截图" to onImportScreenshot, "导入 AI 包" to onImportAi).forEach { (label, action) ->
                TextButton(onClick = { showCreateOptions = false; action() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
            }
        }
    }
}

@Composable
fun GlobalNavigation(library: Boolean, onProjects: () -> Unit, onLibrary: () -> Unit) {
    ShellDivider()
    // Text destinations remain explicit instead of introducing unfamiliar icon-only navigation.
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        TextButton(onClick = onProjects, modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { selected = !library; role = Role.Tab }) {
            Text("项目", color = if (!library) ShellColors.Accent else ShellColors.Muted)
        }
        TextButton(onClick = onLibrary, modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { selected = library; role = Role.Tab }) {
            Text("演示库", color = if (library) ShellColors.Accent else ShellColors.Muted)
        }
    }
}

@Composable
fun ProjectWorkspaceFrame(
    title: String,
    selectedTab: ProjectTab,
    onTab: (ProjectTab) -> Unit,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    onMore: () -> Unit,
    tabsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar(title, onBack) {
            TextButton(onClick = onPreview) { Text("预览") }
            TextButton(onClick = onMore) { Text("更多") }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ProjectTab.entries.forEach { tab ->
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TextButton(onClick = { onTab(tab) }, enabled = tabsEnabled, modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 48.dp).semantics { selected = tab == selectedTab; role = Role.Tab }) {
                        Text(tab.label, textAlign = TextAlign.Center, color = if (!tabsEnabled) ShellColors.Muted.copy(alpha = 0.5f)
                            else if (tab == selectedTab) ShellColors.Accent else ShellColors.Muted)
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp), contentAlignment = Alignment.BottomCenter) {
                        if (tab == selectedTab) HorizontalDivider(thickness = 2.dp, color = ShellColors.Accent) else ShellDivider()
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) { content() }
    }
}
