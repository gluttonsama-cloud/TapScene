package com.tapscene.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class ProjectTab(val label: String) { STEPS("步骤"), SOURCES("素材"), CHECKS("检查与交付") }

/** Shared production chrome, also used by the host-side layout previews. */
@Composable
fun ProjectHomeFrame(
    onRecord: () -> Unit,
    onImportVideo: () -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
    onImportScreenshot: () -> Unit = {},
    onImportAi: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("TapScene") {
            TextButton(onClick = onSettings) { Text("设置") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRecord, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("开始录制") }
            OutlinedButton(onClick = onImportVideo, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("导入录屏") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onImportScreenshot, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("导入截图") }
            TextButton(onClick = onImportAi, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("导入 AI 包") }
        }
        Box(Modifier.weight(1f)) { content() }
        GlobalNavigation(false, {}, onLibrary)
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
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar(title, onBack) {
            TextButton(onClick = onPreview) { Text("预览") }
            TextButton(onClick = onMore) { Text("更多") }
        }
        Row(Modifier.fillMaxWidth()) {
            ProjectTab.entries.forEach { tab ->
                Column(Modifier.weight(1f)) {
                    TextButton(onClick = { onTab(tab) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { selected = tab == selectedTab; role = Role.Tab }) {
                        Text(tab.label, color = if (tab == selectedTab) ShellColors.Accent else ShellColors.Muted)
                    }
                    if (tab == selectedTab) HorizontalDivider(thickness = 2.dp, color = ShellColors.Accent) else ShellDivider()
                }
            }
        }
        Box(Modifier.weight(1f)) { content() }
    }
}
