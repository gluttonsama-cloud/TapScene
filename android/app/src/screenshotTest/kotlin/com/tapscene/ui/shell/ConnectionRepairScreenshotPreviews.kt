package com.tapscene.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

/** Actual repair notice and source-selector body; synthetic data, not platform sheet evidence. */
@PreviewTest
@Preview(name = "connection_repair_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun ConnectionRepairSmallPreview() {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                Text("步骤", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 24.dp))
                ConnectionRepairNotice("报名完成 · 复制后的终点", 4, true, true, {}, {})
            }
        }
    }
}

@PreviewTest
@Preview(name = "connection_sources_landscape", widthDp = 740, heightDp = 480, locale = "zh-rCN", showBackground = true)
@Composable
fun ConnectionSourcesLandscapePreview() {
    val original = ShellPreviewFixture.project
    val target = original.steps.last().copy(id = "unreachable-copy", sortOrder = 3)
    val project = original.copy(project = original.project.copy(stepCount = 4), steps = original.steps + target)
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ConnectionSourceChoices(project, target, setOf(target.id), true, {})
        }
    }
}
