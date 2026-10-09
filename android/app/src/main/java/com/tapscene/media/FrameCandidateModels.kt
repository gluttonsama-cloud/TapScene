package com.tapscene.media

import java.util.UUID

/** A choice about a suggestion only. KEPT never means privacy-reviewed or a ProjectStep. */
enum class CandidateDecision { SUGGESTED, KEPT, DISMISSED }
enum class CandidateReason { FIRST_FRAME, VISUAL_CHANGE, LAST_FRAME }
enum class CandidateAnalysisStatus { NOT_STARTED, RUNNING, COMPLETED, CANCELLED, INTERRUPTED, FAILED }

data class FrameCandidate(
    val id: String,
    val sourceId: String,
    /** Real FrameExtractor PTS, not a requested timestamp or a frame-index estimate. */
    val actualTimeUs: Long,
    val timePrecisionUs: Long,
    val width: Int,
    val height: Int,
    val changeScore: Float,
    val reason: CandidateReason,
    val decision: CandidateDecision = CandidateDecision.SUGGESTED,
    /** Existing project step linked only after its actual PNG was reviewed and saved. */
    val usedStepId: String? = null,
)

data class CandidateAnalysisProgress(
    val completedSamples: Int,
    val totalSamples: Int,
    val uniqueFrames: Int,
    val candidates: List<FrameCandidate>,
)

data class FrameCandidateSnapshot(
    val projectId: String,
    val sourceId: String,
    val sourceSha256: String,
    val algorithmVersion: Int,
    val status: CandidateAnalysisStatus = CandidateAnalysisStatus.NOT_STARTED,
    val completedSamples: Int = 0,
    val totalSamples: Int = 0,
    val uniqueFrames: Int = 0,
    val candidates: List<FrameCandidate> = emptyList(),
)

object FrameCandidateLimits {
    const val ALGORITHM_VERSION = 1
    const val MAX_CANDIDATES = 30 // ProjectLimits.MAX_STEPS is 40; these do not occupy step slots.
    const val MAX_SAMPLES = 361 // At most two requests/second over a three-minute source, plus end.
    const val TARGET_INTERVAL_US = 500_000L
    const val FEATURE_EDGE = 32

    internal fun stableId(source: ImportedSource, actualTimeUs: Long): String = UUID.nameUUIDFromBytes(
        "frame-candidate:$ALGORITHM_VERSION:${source.sourceId}:${source.metadata.sha256.lowercase()}:$actualTimeUs"
            .toByteArray(Charsets.UTF_8),
    ).toString()
}
