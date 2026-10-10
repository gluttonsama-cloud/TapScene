package com.tapscene.recording

import java.util.UUID
import kotlin.math.abs

/** Complete action identity. A late callback may never be rebound to another run or generation. */
data class FrameAnchorAction(
    val runId: String,
    val actionId: String,
    val sessionId: String,
    val sourceId: String,
    val generation: Long,
) {
    init {
        listOf(runId, actionId, sessionId, sourceId).forEach(::requireFrameUuid)
        require(generation >= 0)
    }
}

enum class FrameBoundary { Before, After }

/** Freshness compares captured frame identities, never elapsed time or the UI clock. */
enum class FrameFreshness { Fresh, StaticReuse }

/** Geometry of the actual shared render target, including its letterbox. */
data class FrameGeometry(
    val geometryId: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
) {
    init {
        requireFrameUuid(geometryId)
        require(displayWidth > 0 && displayHeight > 0 && frameWidth > 0 && frameHeight > 0)
        require(frameWidth.toLong() * frameHeight <= 12_000_000)
        val expected = minOf(frameWidth.toDouble() / displayWidth, frameHeight.toDouble() / displayHeight)
        require(scale.isFinite() && offsetX.isFinite() && offsetY.isFinite())
        require(abs(scale - expected) < 0.000001 &&
            abs(offsetX - (frameWidth - displayWidth * expected) / 2) < 0.000001 &&
            abs(offsetY - (frameHeight - displayHeight * expected) / 2) < 0.000001)
    }

    companion object {
        fun fitCenter(displayWidth: Int, displayHeight: Int, frameWidth: Int, frameHeight: Int,
            geometryId: String = UUID.randomUUID().toString()): FrameGeometry {
            require(displayWidth > 0 && displayHeight > 0 && frameWidth > 0 && frameHeight > 0)
            val scale = minOf(frameWidth.toDouble() / displayWidth, frameHeight.toDouble() / displayHeight)
            return FrameGeometry(geometryId, displayWidth, displayHeight, frameWidth, frameHeight, scale,
                (frameWidth - displayWidth * scale) / 2, (frameHeight - displayHeight * scale) / 2)
        }
    }
}

/**
 * Immutable selection made at the action boundary. sourceTimestampNs comes from SurfaceTexture;
 * submittedPtsUs is the exact presentation timestamp submitted to the encoder. Neither is uptime.
 * sourceFrameId is session scoped; captureSequence is monotonically increasing in that session.
 */
data class FrameTicket(
    val ticketId: String,
    val action: FrameAnchorAction,
    val boundary: FrameBoundary,
    val epoch: Long,
    val sourceFrameId: Long,
    val captureSequence: Long,
    val sourceTimestampNs: Long,
    val submittedPtsUs: Long,
    val geometry: FrameGeometry,
    val freshness: FrameFreshness,
) {
    init {
        requireFrameUuid(ticketId)
        require(epoch >= 0 && sourceFrameId >= 0 && captureSequence >= 0)
        require(sourceTimestampNs >= 0 && submittedPtsUs >= 0)
    }
}

enum class FrameMissingReason {
    NoFrame, NoNewFrame, ClosedEpoch, GeometryUnverified, QueueFull, BudgetExceeded,
    PngFailed, EncoderUnmatched, CaptureStopped, Interrupted, SourceUnsealed,
    SourceUnregistered, SourceMismatch, SampleMismatch, EvidenceUnavailable,
}

sealed interface AnchorResult {
    data class Ticket(val ticket: FrameTicket) : AnchorResult
    data class Missing(val reason: FrameMissingReason) : AnchorResult
}

/**
 * Action-runner boundary only: implementations must not await GL, codec, PNG or disk work here.
 * The runner's durable gesture outcomes are independent of these optional capture results.
 */
interface FrameAnchorPort {
    fun before(action: FrameAnchorAction): AnchorResult
    fun markGestureCompleted(action: FrameAnchorAction)
    fun afterWait(action: FrameAnchorAction): AnchorResult
    fun cancelEpoch(reason: FrameMissingReason)

    object None : FrameAnchorPort {
        override fun before(action: FrameAnchorAction) = AnchorResult.Missing(FrameMissingReason.NoFrame)
        override fun markGestureCompleted(action: FrameAnchorAction) = Unit
        override fun afterWait(action: FrameAnchorAction) = AnchorResult.Missing(FrameMissingReason.NoFrame)
        override fun cancelEpoch(reason: FrameMissingReason) = Unit
    }
}

/** Discovery metadata only; every selected ticket still must pass the validated consumer gate. */
internal data class FrameBoundaryMetadata(
    val action: FrameAnchorAction,
    val boundary: FrameBoundary,
    val epoch: Long,
    val ticketId: String?,
    val missingReason: FrameMissingReason?,
)

internal fun requireFrameUuid(value: String) {
    require(UUID.fromString(value).toString() == value) { "Invalid frame evidence identity" }
}
