package com.tapscene.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.ProjectSummary

private val deletionProject = ProjectSummary("3d00c7d8-5b08-4eca-bb69-87062f944d1c", "截图演示",
    "介绍预约时间与提交结果，请先核对不含真实个人信息。", 7, 0L, 2, null)

@PreviewTest
@Preview(name = "project_delete_identity_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectDeleteIdentityPreview() = ProjectDeletePreview(deletionProject, true, true)

@PreviewTest
@Preview(name = "project_delete_long_landscape", widthDp = 740, heightDp = 360, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectDeleteLongPreview() = ProjectDeletePreview(deletionProject.copy(
    title = "同名项目需核对完整身份与开场说明".repeat(5), goal = "这是一段很长的说明，仅展示摘要，不撑开确认框。".repeat(40)), true, false)

@PreviewTest
@Preview(name = "project_delete_changed_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectDeleteChangedPreview() = ProjectDeletePreview(deletionProject, false, true)

@Composable
private fun ProjectDeletePreview(item: ProjectSummary, current: Boolean, enabled: Boolean) {
    TapSceneTheme { Surface(Modifier.fillMaxSize(), color = ShellColors.Background) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("删除确认内容 · 合成布局 · 非设备弹窗", style = MaterialTheme.typography.labelSmall)
            Surface(Modifier.widthIn(max = 560.dp), shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                ProjectDeleteConfirmationContent(item, current, enabled, {}, {})
            }
        }
    } }
}
