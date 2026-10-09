package com.tapscene.data

import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import java.io.File

/** All dates are UTC epoch milliseconds. IDs stay stable when the list order changes. */
data class ProjectSummary(
    val id: String,
    val title: String,
    val goal: String,
    val revision: Long,
    val updatedAt: Long,
    val stepCount: Int,
    val startStepId: String?,
)

data class ProjectSnapshot(val project: ProjectSummary, val steps: List<ProjectStep>)

data class StepAsset(
    val id: String,
    val privateRelativePath: String,
    val sha256: String,
    val byteLength: Long,
    val width: Int,
    val height: Int,
)

data class ProjectStep(
    val id: String,
    val title: String,
    val description: String,
    val sortOrder: Int,
    val isTerminal: Boolean,
    val asset: StepAsset,
    val source: ImportedSource,
    val frameTimeUs: Long,
    val timePrecisionUs: Long,
    val masks: List<OpaqueMask>,
    val hotspots: List<ProjectHotspot>,
    val captureId: String,
) {
    val sourceId: String get() = source.sourceId
}

/** Exactly one of targetStepId and endLabel is set. Every hotspot owns one stable edge. */
data class ProjectHotspot(
    val id: String,
    val label: String,
    val rect: OpaqueMask,
    val targetStepId: String?,
    val endLabel: String?,
    val edgeId: String,
)

/**
 * Only construct after the author reviewed these actual PNG bytes and explicitly chose to add
 * them. Technical verification does not establish human review. The candidate remains caller-owned:
 * the store copies it, validates that private copy and never deletes or modifies the input file.
 */
data class ReviewedStepInput(
    val file: File,
    val sha256: String,
    val width: Int,
    val height: Int,
    val source: ImportedSource,
    val frameTimeUs: Long,
    val timePrecisionUs: Long,
    val masks: List<OpaqueMask>,
    /** Stable token for this particular reviewed candidate; retries cannot duplicate the step. */
    val captureId: String = file.name,
)

/** Self-links count once in totals, and in both incoming and outgoing counts. */
data class StepDeletionImpact(
    val incomingHotspotCount: Int,
    val outgoingHotspotCount: Int,
    val hotspotCount: Int,
    val edgeCount: Int,
    val wasStart: Boolean,
)

data class StepDeletionResult(
    val snapshot: ProjectSnapshot,
    val impact: StepDeletionImpact,
    /** Deleted from the graph; private file removal will be retried on the next store operation. */
    val pendingAssetCleanupCount: Int,
)

data class ProjectDeletionResult(
    val stepCount: Int,
    val hotspotCount: Int,
    val edgeCount: Int,
    val pendingAssetCleanupCount: Int,
)

object ProjectLimits {
    const val MAX_STEPS = 40
    const val MAX_EDGES = 80
    const val MAX_HOTSPOTS_PER_STEP = 6
}
