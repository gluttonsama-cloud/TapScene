package com.tapscene.ui.shell

import androidx.compose.foundation.background
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

/** In-memory layout samples only. No files, privacy approvals or playback completion are produced. */
private object ReviewAiNavigationFixture {
    private fun id(value: String) = UUID.nameUUIDFromBytes("review-ai-nav-layout-$value".toByteArray()).toString()
    private val base = ReleasePreviewFixture.scene
    private val crop = ViewerScene.Asset(id("crop"), "assets/${id("crop")}.png", "image/png", 24000L,
        "2".repeat(64), 384, 93, ViewerScene.Asset.ROLE_REGION_CROP, null)
    private val region = ViewerScene.Region(id("region"), base.states[1].id, base.states[1].imageAssetId, crop.id,
        "报名按钮 · 待逐项复核的真实裁片", 480, 840, ViewerScene.PixelRect(48, 655, 384, 93), "主要操作", 2, ViewerScene.Anchor(.5, .5))
    val scene = ViewerScene(3, ViewerPackageCodec.REGION_POLICY_VERSION, ViewerPackageCodec.COMPILER_VERSION,
        base.releaseId, base.title, base.goal, base.createdAt, base.startStateId, base.states, base.edges, base.hotspots,
        listOf(region), base.assets + crop)
    val imagesDone = ReleaseCandidate(scene.releaseId, id("project"), 8L, scene, ViewerPackageCodec.contentDigest(scene),
        reviewedStateIds = scene.states.map { it.id }.toSet())
    val allDone = imagesDone.copy(reviewedRegionIds = setOf(region.id), summaryReviewed = true, fileListReviewed = true,
        completedPath = true, visitedEdgeIds = scene.edges.map { it.id }.toSet())

    private val longAssets = (0..19).map { index ->
        ViewerScene.Asset(id("image-$index"), "assets/${id("image-$index")}.png", "image/png", 150_000L,
            "0".repeat(64), 480, 840)
    }
    private val longStates = longAssets.mapIndexed { index, asset ->
        ViewerScene.State(id("state-$index"), asset.id, 480, 840,
            "第 ${index + 1} 步 · 核对活动报名信息与参与者选择", "这是一段用于检查小屏长文字换行的合成讲解。", "authored", index == 19)
    }
    private val longEdges = (0..18).map { index -> ViewerScene.Edge(id("edge-$index"), longStates[index].id,
        longStates[index + 1].id, null, null, "确认本步并继续核对下一项报名信息", "continue", "authored") }
    private val longScene = ViewerScene(id("long-release"), "活动报名流程 · 二十步长路径布局样例", "合成长路径，不代表用户实际成品。",
        base.createdAt, longStates.first().id, longStates, longEdges, emptyList(), longAssets)
    val longConfig = AiPackageConfiguration(longScene, longStates.mapIndexed { index, step ->
        RenderPlan.Visit(id("visit-$index"), step.id, longEdges.getOrNull(index)?.id, 90)
    })
    val pendingConfig = longConfig.copy(visits = longConfig.visits.take(18).mapIndexed { index, visit ->
        if (index == 17) RenderPlan.Visit(visit.visitId, visit.stateId, null, visit.holdFrames) else visit
    })
}

@PreviewTest
@Preview(name = "review_next_region_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun ReviewNextRegionSmallPreview() = ReviewAiNavigationSurface { ReviewNextRegionContent() }

@PreviewTest
@Preview(name = "review_next_region_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun ReviewNextRegionLandscapePreview() = ReviewAiNavigationSurface { ReviewNextRegionContent() }

@Composable
private fun ReviewNextRegionContent() {
    val candidate = ReviewAiNavigationFixture.imagesDone
    val bitmap = remember { ShellPreviewFixture.bitmap(1) }
    check(nextIncompleteReviewCategory(releaseReviewCategories(candidate), 0)?.section == 5)
    ReleaseReviewContent(ReleaseUiState(candidate = candidate, reviewStateId = candidate.scene.states[1].id, reviewBitmap = bitmap),
        8L, {}, {}, {}, {}, {}, {}, {}, {})
}

@PreviewTest
@Preview(name = "review_all_done_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun ReviewAllDoneSmallPreview() = ReviewAiNavigationSurface {
    ReleaseReviewContent(ReleaseUiState(candidate = ReviewAiNavigationFixture.allDone), 8L,
        {}, {}, {}, {}, {}, {}, {}, {}, initialSection = 4)
}

@PreviewTest
@Preview(name = "ai_long_path_pending_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun AiLongPathPendingSmallPreview() = ReviewAiNavigationSurface {
    check(runCatching { ReviewAiNavigationFixture.pendingConfig.resolve() }.isFailure)
    AiPackageScreen({}, ReleaseUiState(aiConfiguration = ReviewAiNavigationFixture.pendingConfig))
}

@PreviewTest
@Preview(name = "ai_long_path_complete_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun AiLongPathCompleteLandscapePreview() = ReviewAiNavigationSurface {
    val config = ReviewAiNavigationFixture.longConfig
    check(aiSelectedDurationFrames(config) == config.resolve().totalFrames)
    AiPackageScreen({}, ReleaseUiState(aiConfiguration = config))
}

@PreviewTest
@Preview(name = "ai_long_path_complete_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun AiLongPathCompleteSmallPreview() = ReviewAiNavigationSurface {
    AiPackageScreen({}, ReleaseUiState(aiConfiguration = ReviewAiNavigationFixture.longConfig))
}

@Composable
private fun ReviewAiNavigationSurface(content: @Composable () -> Unit) {
    TapSceneTheme {
        Surface {
            Column(Modifier.fillMaxSize()) {
                Text("布局样例 · 合成内容 · 非设备或触控验证", Modifier.fillMaxWidth().background(ShellColors.AccentSoft).padding(8.dp),
                    style = MaterialTheme.typography.labelSmall)
                content()
            }
        }
    }
}
