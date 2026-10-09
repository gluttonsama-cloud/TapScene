package com.tapscene.ui.shell

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.ReleaseCandidate
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.AiPackageConfiguration
import com.tapscene.ui.ReleaseUiState
import java.util.UUID

/** Synthetic Layoutlib fixtures only; never create release files or grant privacy approval. */
private object AiPreviewFixture {
    fun id(value: String) = UUID.nameUUIDFromBytes("ai-layout-$value".toByteArray()).toString()
    private val base = ReleasePreviewFixture.scene
    private val crop = ViewerScene.Asset(id("crop"), "assets/${id("crop")}.png", "image/png", 24000L,
        "2".repeat(64), 384, 93, ViewerScene.Asset.ROLE_REGION_CROP, null)
    val region = ViewerScene.Region(id("region"), base.states[1].id, base.states[1].imageAssetId, crop.id,
        "报名按钮 · 安全裁片", 480, 840, ViewerScene.PixelRect(48, 655, 384, 93), "主要操作", 2, ViewerScene.Anchor(.5, .5))
    val scene = ViewerScene(3, ViewerPackageCodec.REGION_POLICY_VERSION, ViewerPackageCodec.COMPILER_VERSION,
        base.releaseId, base.title, base.goal, base.createdAt, base.startStateId, base.states, base.edges, base.hotspots,
        listOf(region), base.assets + crop)
    val visits = scene.states.mapIndexed { index, state -> RenderPlan.Visit(id("visit-$index"), state.id,
        scene.edges.firstOrNull { it.fromStateId == state.id && it.trigger == "tap" }?.id, 90) }
    val config = AiPackageConfiguration(scene, visits, listOf(
        RenderPlan.Effect("focus", visits[1].visitId, 0, 90, null, region.id, null, null),
        RenderPlan.Effect("annotation", visits[0].visitId, 0, 90, null, null,
            "点击报名，确认信息后提交。", ViewerScene.Rect(.08, .08, .84, .14))))
}

@PreviewTest
@Preview(name = "19_ai_source_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun AiSourceSamplePreview() {
    AiReviewSurface { AiPackageScreen({}, ReleasePreviewFixture.libraryState) }
}

@PreviewTest
@Preview(name = "20_ai_path_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun AiPathSamplePreview() {
    AiReviewSurface { AiPackageScreen({}, ReleaseUiState(aiConfiguration = AiPreviewFixture.config)) }
}

@PreviewTest
@Preview(name = "21_fixed_region_review_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun FixedRegionReviewSamplePreview() {
    val scene = AiPreviewFixture.scene
    val bitmap = remember { ShellPreviewFixture.bitmap(1) }
    val crop = remember(bitmap) { Bitmap.createBitmap(bitmap, 48, 655, 384, 93) }
    val candidate = ReleaseCandidate(scene.releaseId, AiPreviewFixture.id("project"), 5L, scene, ViewerPackageCodec.contentDigest(scene))
    AiReviewSurface {
        ReleaseReviewContent(ReleaseUiState(candidate = candidate, reviewRegionId = AiPreviewFixture.region.id,
            reviewRegionBitmap = crop), 5L, {}, {}, {}, {}, {}, {}, {}, {}, initialSection = 5)
    }
}

@PreviewTest
@Preview(name = "22_ai_export_review_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun AiExportReviewSamplePreview() {
    AiReviewSurface {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            AiExportReviewContent(AiPreviewFixture.config, AiPreviewFixture.config.resolve())
        }
    }
}

@Composable
private fun AiReviewSurface(content: @Composable () -> Unit) {
    TapSceneTheme {
        Surface {
            Column(Modifier.fillMaxSize()) {
                Text("布局样例 · 合成内容 · 不代表设备或媒体验证", Modifier.fillMaxWidth().background(ShellColors.AccentSoft).padding(8.dp),
                    style = MaterialTheme.typography.labelSmall)
                content()
            }
        }
    }
}
