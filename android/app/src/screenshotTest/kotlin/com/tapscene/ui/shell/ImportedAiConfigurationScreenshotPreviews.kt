package com.tapscene.ui.shell

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.AiConfigurationOpen
import com.tapscene.data.ImportedAiConfigurationSnapshot
import com.tapscene.data.ReleaseSummary
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.ui.AiConfigurationChoice
import com.tapscene.ui.AiPackageConfiguration
import com.tapscene.ui.ReleaseUiState
import java.util.UUID

/** Synthetic production-page renders, not device, picker or persistence evidence. */
private object ImportedAiPreview {
    val scene = ReleasePreviewFixture.scene
    val config = AiPackageConfiguration(scene, scene.states.mapIndexed { index, state ->
        RenderPlan.Visit(UUID.nameUUIDFromBytes("retained-ai-$index".toByteArray()).toString(), state.id,
            scene.edges.firstOrNull { it.fromStateId == state.id && it.trigger == "continue" }?.id, 90)
    })
    private val summary = ReleaseSummary(scene.releaseId, scene.title, ViewerPackageCodec.contentDigest(scene),
        scene.createdAt, scene.states.size, 455_000L, "imported")
    fun choice(valid: Boolean) = ReleaseUiState(aiConfigurationOwner = "preview-session",
        aiReplacesRetainedConfiguration = true,
        aiConfigurationChoice = AiConfigurationChoice("preview-session", AiConfigurationOpen(summary, scene,
            config.resolve().takeIf { valid }, false, ImportedAiConfigurationSnapshot(1200, "a".repeat(64)))))
}

@PreviewTest
@Preview(name = "imported_ai_retained_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ImportedAiRetainedSmallPreview() {
    TapSceneTheme { Surface { AiPackageScreen({}, ImportedAiPreview.choice(true)) } }
}

@PreviewTest
@Preview(name = "imported_ai_invalid_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun ImportedAiInvalidLandscapePreview() {
    TapSceneTheme { Surface { AiPackageScreen({}, ImportedAiPreview.choice(false)) } }
}

@PreviewTest
@Preview(name = "imported_ai_save_unknown_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ImportedAiSaveUnknownSmallPreview() {
    TapSceneTheme { Surface { AiPackageScreen({}, ReleaseUiState(aiConfiguration = ImportedAiPreview.config,
        aiConfigurationOwner = "preview-session", aiSaveNeedsCheck = true, aiConfigurationChanged = true,
        aiReplacesRetainedConfiguration = true,
        message = "暂时无法核对本机配置的保存结果。本页输入保留，请重新核对；不要重复保存。")) } }
}
