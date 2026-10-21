package com.tapscene.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.AiDraftImportPreview
import com.tapscene.packageformat.AiDraftImportPolicy
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.ui.AiDraftImportUiState
import java.util.UUID

/** Same comparison panel as the import page, with synthetic fixed versions only. */
private val comparisonPreview: AiDraftImportUiState get() {
    val scene = ReleasePreviewFixture.scene
    val plan = RenderPlan.build(scene, 1080, 1920, scene.states.mapIndexed { index, step ->
        RenderPlan.Visit(UUID.nameUUIDFromBytes("comparison-visit-$index".toByteArray()).toString(), step.id,
            scene.edges.firstOrNull { it.fromStateId == step.id && it.trigger == "continue" }?.id, 90)
    }, emptyList())
    val policy = AiDraftImportPolicy.review(scene, plan, scene)
    val preview = AiDraftImportPreview("comparison-session", "a".repeat(64), "comparison-project", scene, plan,
        scene.releaseId, scene.title, policy.issues, policy.differences, policy.summary, policy.trustNotice,
        scene.assets.sumOf { it.byteLength }, baselineScene = scene)
    return AiDraftImportUiState(sessionId = preview.sessionId, preview = preview,
        baselines = listOf(ReleasePreviewFixture.summary.copy(id = scene.releaseId,
            contentDigest = ViewerPackageCodec.contentDigest(scene)),
            ReleasePreviewFixture.summary.copy(id = "11111111-2222-4333-8444-555555555555", contentDigest = "b".repeat(64))))
}

@PreviewTest
@Preview(name = "ai_comparison_identity_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN")
@Composable
fun AiComparisonIdentitySmallPreview() = ComparisonSurface { AiImportComparisonPanel(comparisonPreview, false, {}, {}) }

@PreviewTest
@Preview(name = "ai_comparison_choices_landscape", widthDp = 740, heightDp = 360, fontScale = 1.3f, locale = "zh-rCN")
@Composable
fun AiComparisonChoicesLandscapePreview() = ComparisonSurface(startAtBottom = true) {
    val state = comparisonPreview
    AiImportComparisonPanel(state.copy(preview = state.preview!!.copy(baselineReleaseId = null,
        baselineTitle = null, baselineScene = null, differences = emptyList())), true, {}, {})
}

@Composable
private fun ComparisonSurface(startAtBottom: Boolean = false, content: @Composable () -> Unit) {
    TapSceneTheme {
        Column(Modifier.fillMaxSize().background(ShellColors.Background).padding(16.dp)) {
            Text("布局样例 · 合成内容 · 非设备或触控验证", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            // Show the end of the same scrolling panel so both same-title choices are visible.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState(if (startAtBottom) 100_000 else 0))) { content() }
        }
    }
}
