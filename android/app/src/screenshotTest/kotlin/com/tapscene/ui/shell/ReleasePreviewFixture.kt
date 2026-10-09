package com.tapscene.ui.shell

import com.tapscene.data.ReleaseCandidate
import com.tapscene.data.ReleaseSummary
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.LocalVideoRun
import com.tapscene.ui.ReleaseVideoReview
import com.tapscene.ui.ReleasePlayback
import com.tapscene.ui.ReleaseUiState
import java.util.UUID

/** Synthetic in-memory content used ONLY by Layoutlib screenshot sources, never product storage. */
internal object ReleasePreviewFixture {
    private fun id(value: String) = UUID.nameUUIDFromBytes("layout-only-$value".toByteArray()).toString()
    private val stateIds = (0..2).map { id("step-$it") }
    private val assets = (0..2).map { index ->
        val assetId = id("asset-$index")
        ViewerScene.Asset(assetId, "assets/$assetId.png", "image/png", 150_000L,
            "0".repeat(64), 480, 840)
    }
    private val spots = (0..1).map { index -> ViewerScene.Hotspot(id("hotspot-$index"), stateIds[index],
        if (index == 0) "选择活动" else "确认报名", ViewerScene.Rect(.1, .78, .8, .11)) }
    val scene = ViewerScene(id("release"), "活动报名 · 布局样例", "演示选择活动、核对信息和完成报名。", 1_791_504_000_000L,
        stateIds.first(), ShellPreviewFixture.project.steps.mapIndexed { index, step ->
            ViewerScene.State(stateIds[index], assets[index].id, 480, 840,
                step.title, step.description, "recorded", index == 2)
        }, (0..1).flatMap { index -> listOf(
            ViewerScene.Edge(id("tap-$index"), stateIds[index], stateIds[index + 1], null, spots[index].id,
                spots[index].label, "tap", "authored"),
            ViewerScene.Edge(id("continue-$index"), stateIds[index], stateIds[index + 1], null, null,
                "下一步", "continue", "authored"),
        ) }, spots, assets)
    val candidate = ReleaseCandidate(scene.releaseId, id("project"), 3L, scene,
        ViewerPackageCodec.contentDigest(scene), reviewedStateIds = setOf(stateIds.first()), summaryReviewed = true,
        visitedEdgeIds = setOf(id("tap-0")))
    val summary = ReleaseSummary(id("sealed-release"), "活动报名 · 布局样例", "0".repeat(64),
        1_791_504_000_000L, 3, 455_000L, "local")
    val reviewState get() = ReleaseUiState(candidate = candidate,
        reviewStateId = stateIds[1], reviewBitmap = ShellPreviewFixture.bitmap(1))
    val libraryState = ReleaseUiState(releases = listOf(summary), pendingCandidates = listOf(candidate))
    val playerState get() = ReleaseUiState(player = ReleasePlayback(scene, null, stateIds.first(), listOf(stateIds.first())),
        playerBitmap = ShellPreviewFixture.bitmap(0))

    // A failed attempt only: no file, EOS, privacy review, or decoder success is fabricated.
    private val videoAsset = ViewerScene.Asset(id("video-asset"), "assets/${id("video-asset")}.mp4",
        "video/mp4", 400_000L, "1".repeat(64), 480, 840, ViewerScene.Asset.ROLE_TRANSITION, 2_000L)
    private val videoScene = ViewerScene(2, ViewerPackageCodec.VIDEO_POLICY_VERSION, ViewerPackageCodec.COMPILER_VERSION,
        id("video-release"), "短片复核 · 布局样例", scene.goal, scene.createdAt, scene.startStateId,
        scene.states, scene.edges.mapIndexed { index, edge ->
            ViewerScene.Edge(edge.id, edge.fromStateId, edge.toStateId, edge.endLabel, edge.hotspotId,
                edge.label, edge.trigger, edge.sourceKind, videoAsset.id.takeIf { index == 0 })
        }, scene.hotspots, scene.assets + videoAsset)
    private val videoCandidate = ReleaseCandidate(videoScene.releaseId, id("project"), 4L, videoScene,
        ViewerPackageCodec.contentDigest(videoScene))
    val videoReviewFailureState get() = ReleaseUiState(candidate = videoCandidate,
        reviewVideo = ReleaseVideoReview(videoCandidate.id, videoCandidate.contentDigest,
            videoAsset.id, videoAsset.sha256, LocalVideoRun(null, 480, 840, 1L, failed = true)))

}
