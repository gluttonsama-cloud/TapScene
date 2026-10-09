package com.tapscene.data

import com.tapscene.media.ImportedImageSource
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

/** Private media origin; never serialized into the public scene evidence-kind field. */
sealed interface StepOrigin {
    data class VideoFrame(val source: ImportedSource, val frameTimeUs: Long,
        val timePrecisionUs: Long) : StepOrigin {
        init {
            require(frameTimeUs >= 0 && frameTimeUs <= source.metadata.durationUs && timePrecisionUs > 0) {
                "实际取帧时间或时间精度无效。"
            }
        }
    }
    /** Historical identity only. The superseded asset may be cleaned up; there is no file handle. */
    data class Image(val base: SafeImageBinding) : StepOrigin
    /** A selected external PNG/JPEG held privately; never a previously reviewed safe base. */
    data class ImportedImage(val source: ImportedImageSource) : StepOrigin
}

/** An edit lease on one exact current safe PNG, never an imported or historical readable source. */
data class SafeImageBinding(val projectId: String, val stepId: String, val revision: Long,
    val assetId: String, val sha256: String, val width: Int, val height: Int) {
    init {
        require(listOf(projectId, stepId, assetId).all { id ->
            runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false)
        } && revision > 0 && sha256.matches(Regex("[0-9a-f]{64}")) && width > 0 && height > 0 &&
            width.toLong() * height <= 12_000_000) { "安全底图绑定无效。" }
    }
    fun matches(project: ProjectSummary, step: ProjectStep): Boolean = project.id == projectId &&
        project.revision == revision && step.id == stepId && step.asset.id == assetId &&
        step.asset.sha256 == sha256 && step.asset.width == width && step.asset.height == height
}

data class ProjectStep(
    val id: String,
    val title: String,
    val description: String,
    val sortOrder: Int,
    val isTerminal: Boolean,
    val asset: StepAsset,
    val origin: StepOrigin,
    val masks: List<OpaqueMask>,
    val hotspots: List<ProjectHotspot>,
    val captureId: String,
    val nextAction: ProjectNextAction? = null,
    val regions: List<ProjectRegion> = emptyList(),
    /** Public evidence kind is retained through subsequent safe-image redactions. */
    val evidenceKind: String = if (origin is StepOrigin.ImportedImage) "authored" else "recorded",
) {
    init {
        require(evidenceKind in setOf("recorded", "authored", "imported")) { "步骤证据种类无效。" }
        if (origin is StepOrigin.ImportedImage) require(evidenceKind == "authored" &&
            asset.width == origin.source.metadata.outputWidth && asset.height == origin.source.metadata.outputHeight)
        if (origin is StepOrigin.Image) require(origin.base.stepId == id &&
            origin.base.width == asset.width && origin.base.height == asset.height) { "安全图片步骤与底图身份或尺寸不一致。" }
    }
    constructor(id: String, title: String, description: String, sortOrder: Int, isTerminal: Boolean,
        asset: StepAsset, source: ImportedSource, frameTimeUs: Long, timePrecisionUs: Long,
        masks: List<OpaqueMask>, hotspots: List<ProjectHotspot>, captureId: String,
        nextAction: ProjectNextAction? = null, regions: List<ProjectRegion> = emptyList()) :
        this(id, title, description, sortOrder, isTerminal, asset,
            StepOrigin.VideoFrame(source, frameTimeUs, timePrecisionUs), masks, hotspots, captureId, nextAction, regions)
    val videoOrigin: StepOrigin.VideoFrame? get() = origin as? StepOrigin.VideoFrame
    val imageOrigin: StepOrigin.Image? get() = origin as? StepOrigin.Image
    val source: ImportedSource? get() = videoOrigin?.source
    val sourceId: String? get() = source?.sourceId
    val frameTimeUs: Long? get() = videoOrigin?.frameTimeUs
    val timePrecisionUs: Long? get() = videoOrigin?.timePrecisionUs
    val originLabel: String get() = source?.displayName ?: if (evidenceKind == "authored") "截图" else "已保存安全画面"
    fun safeImageBinding(project: ProjectSummary) = SafeImageBinding(project.id, id, project.revision,
        asset.id, asset.sha256, asset.width, asset.height)
}

/** Integer bounds in the actual reviewed base image. No inferred native components. */
data class RegionBox(val x: Int, val y: Int, val width: Int, val height: Int) {
    init { require(x >= 0 && y >= 0 && width > 0 && height > 0) { "区域须为正面积像素矩形。" } }
    fun fits(width: Int, height: Int): Boolean = x.toLong() + this.width <= width && y.toLong() + this.height <= height
}

data class ProjectRegion(
    val id: String,
    val stateId: String,
    val baseAssetId: String,
    val baseSha256: String,
    val name: String,
    val group: String?,
    val bbox: RegionBox,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val zIndex: Int,
    val anchorX: Double,
    val anchorY: Double,
    val asset: StepAsset? = null,
    val reviewedAt: Long? = null,
) {
    val stale: Boolean get() = asset == null
    fun matchesBase(base: StepAsset): Boolean = baseAssetId == base.id && baseSha256 == base.sha256 &&
        sourceWidth == base.width && sourceHeight == base.height
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
    val origin: StepOrigin,
    val masks: List<OpaqueMask>,
    /** Stable token for this particular reviewed candidate; retries cannot duplicate the step. */
    val captureId: String = file.name,
) {
    constructor(file: File, sha256: String, width: Int, height: Int, source: ImportedSource,
        frameTimeUs: Long, timePrecisionUs: Long, masks: List<OpaqueMask>, captureId: String = file.name) :
        this(file, sha256, width, height, StepOrigin.VideoFrame(source, frameTimeUs, timePrecisionUs), masks, captureId)
    val videoOrigin: StepOrigin.VideoFrame? get() = origin as? StepOrigin.VideoFrame
    val source: ImportedSource? get() = videoOrigin?.source
    val frameTimeUs: Long? get() = videoOrigin?.frameTimeUs
    val timePrecisionUs: Long? get() = videoOrigin?.timePrecisionUs
}

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
    const val MAX_REGIONS = 80
    const val MAX_REGIONS_PER_STEP = 12
    const val MAX_SCREENSHOTS = 20
    const val MAX_STEPS = 40
    const val MAX_EDGES = 80
    const val MAX_HOTSPOTS_PER_STEP = 6
}
