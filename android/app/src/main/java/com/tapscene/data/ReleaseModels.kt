package com.tapscene.data

import com.tapscene.packageformat.ViewerScene

/** A private, immutable output snapshot. Reviews refer to these bytes, not the current draft. */
data class ReleaseCandidate(
    val id: String,
    val projectId: String,
    val projectRevision: Long,
    val scene: ViewerScene,
    val contentDigest: String,
    val reviewedStateIds: Set<String> = emptySet(),
    val summaryReviewed: Boolean = false,
    val fileListReviewed: Boolean = false,
    val visitedEdgeIds: Set<String> = emptySet(),
    val completedPath: Boolean = false,
    val traversalStateId: String? = null,
    val traversalStarted: Boolean = false,
    val traversalEnded: Boolean = false,
    val traversalHistory: List<String> = emptyList(),
    val traversalEndEdgeId: String? = null,
    val reviewedTransitionAssetIds: Set<String> = emptySet(),
    val reviewedRegionIds: Set<String> = emptySet(),
)

data class ReleaseSummary(
    val id: String,
    val title: String,
    val contentDigest: String,
    val sealedAt: Long,
    val stepCount: Int,
    val byteLength: Long,
    val origin: String,
)

/** Sealed local provenance read from the retained review, never inferred from an open draft. */
data class HostedReleaseBinding(
    val summary: ReleaseSummary,
    val projectId: String,
    val projectRevision: Long,
    val scene: ViewerScene,
    val fileListDigest: String,
) {
    val uploadByteLength: Long get() = com.tapscene.packageformat.ViewerPackageCodec.writeScene(scene).size.toLong() +
        scene.assets.sumOf { it.byteLength }
}
