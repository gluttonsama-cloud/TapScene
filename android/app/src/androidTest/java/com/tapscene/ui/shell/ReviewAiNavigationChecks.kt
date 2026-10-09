package com.tapscene.ui.shell

import com.tapscene.data.ReleaseCandidate
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.AiPackageConfiguration
import java.util.UUID

/** Pure derived-state checks. These do not prove rendering, touch, media review or file output. */
object ReviewAiNavigationChecks {
    fun run(status: (String) -> Unit) {
        val scene = scene(withVideo = true)
        val original = ReleaseCandidate(scene.releaseId, id("project"), 1, scene, ViewerPackageCodec.contentDigest(scene))
        val empty = releaseReviewCategories(original)
        check(empty.none { it.complete })
        check(empty.map { it.section } == listOf(0, 1, 5, 2, 3, 4))
        val images = original.copy(reviewedStateIds = scene.states.map { it.id }.toSet())
        check(nextIncompleteReviewCategory(releaseReviewCategories(images), 0)?.section == 1)
        val video = images.copy(reviewedTransitionAssetIds = setOf(id("video")))
        check(nextIncompleteReviewCategory(releaseReviewCategories(video), 1)?.section == 5)
        val crops = video.copy(reviewedRegionIds = scene.regions.map { it.id }.toSet())
        check(nextIncompleteReviewCategory(releaseReviewCategories(crops), 5)?.section == 2)
        val summary = crops.copy(summaryReviewed = true)
        check(nextIncompleteReviewCategory(releaseReviewCategories(summary), 2)?.section == 3)
        val files = summary.copy(fileListReviewed = true)
        check(nextIncompleteReviewCategory(releaseReviewCategories(files), 3)?.section == 4)
        check(!releaseReviewCategories(files.copy(visitedEdgeIds = scene.edges.map { it.id }.toSet())).last().complete)
        check(!releaseReviewCategories(files.copy(completedPath = true)).last().complete)
        val complete = files.copy(visitedEdgeIds = scene.edges.map { it.id }.toSet(), completedPath = true)
        check(releaseReviewCategories(complete).all { it.complete })
        check(nextIncompleteReviewCategory(releaseReviewCategories(complete), 4) == null)
        check(nextIncompleteReviewCategory(emptyList(), 0) == null)
        val unfinishedImages = complete.copy(reviewedStateIds = emptySet())
        check(nextIncompleteReviewCategory(releaseReviewCategories(unfinishedImages), 4)?.section == 0)
        // Strict identity equality, matching the store's sealing gate, rejects stale extra IDs too.
        check(!releaseReviewCategories(complete.copy(reviewedStateIds = complete.reviewedStateIds + id("stale"))).first().complete)
        check(!releaseReviewCategories(complete.copy(reviewedTransitionAssetIds = complete.reviewedTransitionAssetIds + id("stale")))[1].complete)
        check(!releaseReviewCategories(complete.copy(reviewedRegionIds = complete.reviewedRegionIds + id("stale")))[2].complete)
        check(!releaseReviewCategories(complete.copy(visitedEdgeIds = complete.visitedEdgeIds + id("stale"))).last().complete)
        check(original.reviewedStateIds.isEmpty() && !original.summaryReviewed && !original.fileListReviewed && !original.completedPath)
        check(original.reviewedTransitionAssetIds.isEmpty() && original.reviewedRegionIds.isEmpty() && original.visitedEdgeIds.isEmpty())
        val noOptionalScene = scene(withVideo = false).let {
            ViewerScene(it.schemaVersion, it.policyVersion, it.compilerVersion, it.releaseId, it.title, it.goal, it.createdAt,
                it.startStateId, it.states, it.edges, it.hotspots, emptyList(), it.assets.filter { asset -> asset.role == ViewerScene.Asset.ROLE_IMAGE })
        }
        val noOptional = images.copy(scene = noOptionalScene)
        check(nextIncompleteReviewCategory(releaseReviewCategories(noOptional), 0)?.section == 2)
        check(releaseReviewCategories(noOptional).count { it.complete } == 3)
        status("PASS review navigation: all six persisted gates, pending-region routing, wraparound and stale IDs; navigation grants no review credit")

        val visits = listOf(RenderPlan.Visit(id("visit-a"), id("a"), id("edge"), 90),
            RenderPlan.Visit(id("visit-b"), id("b"), null, 90))
        val videoConfig = AiPackageConfiguration(scene, visits)
        check(aiSelectedDurationFrames(videoConfig) == 225)
        check(aiSelectedDurationFrames(videoConfig) == videoConfig.resolve().totalFrames)
        val partial = videoConfig.copy(visits = listOf(RenderPlan.Visit(id("visit-a"), id("a"), null, 90)))
        check(aiSelectedDurationFrames(partial) == 90)
        check(runCatching { partial.resolve() }.isFailure) { "Duration display must not make a partial path exportable" }
        val staticConfig = AiPackageConfiguration(scene(withVideo = false), visits, listOf(
            RenderPlan.Effect("transition", visits.first().visitId, 60, 30, null, null, null, null)))
        check(aiSelectedDurationFrames(staticConfig) == 150)
        check(aiSelectedDurationFrames(staticConfig) == staticConfig.resolve().totalFrames)
        status("PASS AI duration: explicit visit holds, rounded video frames and crossfade overlap match the complete render plan; incomplete paths stay blocked")
    }

    private fun id(value: String) = UUID.nameUUIDFromBytes("review-nav-check-$value".toByteArray()).toString()

    private fun scene(withVideo: Boolean): ViewerScene {
        val images = listOf("image-a", "image-b").map { key ->
            ViewerScene.Asset(id(key), "assets/${id(key)}.png", "image/png", 100L, "0".repeat(64), 480, 840)
        }
        val crop = ViewerScene.Asset(id("crop"), "assets/${id("crop")}.png", "image/png", 100L,
            "1".repeat(64), 100, 80, ViewerScene.Asset.ROLE_REGION_CROP, null)
        val video = ViewerScene.Asset(id("video"), "assets/${id("video")}.mp4", "video/mp4", 1000L,
            "2".repeat(64), 480, 840, ViewerScene.Asset.ROLE_TRANSITION, 1500L)
        return ViewerScene(3, ViewerPackageCodec.REGION_POLICY_VERSION, ViewerPackageCodec.COMPILER_VERSION,
            id("release"), "Navigation fixture", "Synthetic only", 1, id("a"), listOf(
                ViewerScene.State(id("a"), images[0].id, 480, 840, "A", "", "authored", false),
                ViewerScene.State(id("b"), images[1].id, 480, 840, "B", "", "authored", true)),
            listOf(ViewerScene.Edge(id("edge"), id("a"), id("b"), null, null, "Next", "continue", "authored",
                video.id.takeIf { withVideo })), emptyList(),
            listOf(ViewerScene.Region(id("region"), id("a"), images[0].id, crop.id, "Region", 480, 840,
                ViewerScene.PixelRect(0, 0, 100, 80), null, 0, ViewerScene.Anchor(.5, .5))),
            images + crop + if (withVideo) listOf(video) else emptyList())
    }
}
