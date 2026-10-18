package com.tapscene.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.ReleaseSummary
import com.tapscene.packageformat.RenderPlan
import com.tapscene.ui.AiPackageConfiguration
import com.tapscene.ui.ReleaseUiState

/** Synthetic host layouts of production content; no picker or device evidence. */
@PreviewTest
@Preview(name = "ai_configuration_changed_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun AiConfigurationChangedSmallPreview() {
    val scene = ReleasePreviewFixture.scene
    val config = AiPackageConfiguration(scene,
        listOf(RenderPlan.Visit("70a88c38-3dbe-449a-b095-466327128ade", scene.startStateId, null, 120)))
    TapSceneTheme { Surface { AiPackageScreen({}, ReleaseUiState(aiConfiguration = config, aiConfigurationChanged = true)) } }
}

@PreviewTest
@Preview(name = "ai_configuration_exit_details_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun AiConfigurationExitDetailsLandscapePreview() {
    val target = ReleaseSummary("a98341bf-6c76-4cab-b735-3a4484628a91", "报名流程 · 核对参加者与场次后确认提交", "0".repeat(64), 3, 1, 100, "local")
    TapSceneTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("换版确认内容 · 合成布局 · 非设备弹窗", style = MaterialTheme.typography.labelSmall)
        Column(Modifier.widthIn(max = 512.dp)) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    AiConfigurationExitDetails(target, target.id)
                }
            }
        }
    } } }
}
