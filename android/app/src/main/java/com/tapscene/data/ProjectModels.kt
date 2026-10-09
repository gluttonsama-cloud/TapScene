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
    val nextAction: ProjectNextAction? = null,
) {
    val sourceId: String get() = source.sourceId
}

/** Author-arranged button outside the image, never a detected tap or implicit list-order link.
 * A null target is an unresolved action that needs repair, not an end action.
 */
data class ProjectNextAction(
    val id: String,
    val label: String,
    val targetStepId: String?,
    val transition: ProjectTransition? = null,
)

/** Exactly one of targetStepId and endLabel is set. Every hotspot owns one stable edge. */
data class ProjectHotspot(
    val id: String,
    val label: String,
    val rect: OpaqueMask,
    val targetStepId: String?,
    val endLabel: String?,
    val edgeId: String,
    val transition: ProjectTransition? = null,
)

/** A controlled, fully decoded, silent H.264 SDR output, never the private original. */
data class TransitionAsset(
    val id: String,
    val privateRelativePath: String,
    val sha256: String,
    val byteLength: Long,
    val width: Int,
    val height: Int,
    val durationUs: Long,
)

/** Source provenance and fixed masks stay private and are never projected into a viewer package. */
data class ProjectTransition(
    val asset: TransitionAsset,
    val source: ImportedSource,
    val startUs: Long,
    val endUs: Long,
    val masks: List<OpaqueMask>,
    val reviewId: String,
)

/**
 * Construct ONLY after playing these exact output bytes continuously from the beginning to EOS
 * and the author explicitly confirms the whole clip. A seek, skip, decoder check or source preview
 * is not that review. The store copies and revalidates these bytes; input remains caller-owned.
 * reviewId identifies this reviewed output, making an interrupted-save retry idempotent.
 */
data class ReviewedTransitionInput(
    val file: File,
    val sha256: String,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val source: ImportedSource,
    val startUs: Long,
    val endUs: Long,
    val masks: List<OpaqueMask>,
    val reviewId: String = file.name,
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

/**
 * Self-links count once in totals, and in both incoming and outgoing counts. edgeCount includes
 * affected next actions: incoming buttons survive with a null target; outgoing buttons are deleted.
 */
data class StepDeletionImpact(
    val incomingHotspotCount: Int,
    val outgoingHotspotCount: Int,
    val hotspotCount: Int,
    val edgeCount: Int,
    val wasStart: Boolean,
    val incomingNextActionCount: Int = 0,
    val outgoingNextActionCount: Int = 0,
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
    const val MAX_TRANSITION_US = 10_000_000L
    const val MAX_TOTAL_TRANSITION_US = 60_000_000L
    const val MAX_STEPS = 40
    const val MAX_EDGES = 80
    const val MAX_HOTSPOTS_PER_STEP = 6
}
