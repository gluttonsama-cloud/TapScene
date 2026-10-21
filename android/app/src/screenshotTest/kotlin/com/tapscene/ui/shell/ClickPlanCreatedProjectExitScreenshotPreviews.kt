package com.tapscene.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "click_plan_created_project_exit_failure_small", widthDp = 320, heightDp = 568,
    fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickPlanCreatedProjectExitFailureSmallPreview() = ClickPlanCreatedProjectExitPreview()

@PreviewTest
@Preview(name = "click_plan_created_project_exit_failure_landscape", widthDp = 740, heightDp = 360,
    fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickPlanCreatedProjectExitFailureLandscapePreview() = ClickPlanCreatedProjectExitPreview(geometryMatches = false)

/** Synthetic bounded production content; conservative insets are not an exact device dialog capture. */
@Composable
private fun ClickPlanCreatedProjectExitPreview(geometryMatches: Boolean = true) {
    TapSceneTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("打开新项目确认 · 合成布局 · 非设备弹窗", style = MaterialTheme.typography.labelSmall)
            Surface(Modifier.widthIn(max = 560.dp), shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                ClickPlanCreatedProjectExitContent(error = null, geometryMatches = geometryMatches,
                    message = "保存或打开定位未完成。输入已保留，请重试；若计划已有更新，请重新载入。",
                    saving = false, saveEnabled = geometryMatches, onDismiss = {}, onSave = {}, onDiscard = {})
            }
        }
    } }
}
