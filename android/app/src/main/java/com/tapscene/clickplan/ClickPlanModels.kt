package com.tapscene.clickplan

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** A deliberate screen-coordinate tap, not an observed user touch or an evidence hotspot. */
data class ClickAction(
    val actionId: String = UUID.randomUUID().toString(),
    val x: Int,
    val y: Int,
    val pressDurationMs: Long = 80,
    val waitAfterMs: Long = 500,
) {
    init {
        requireClickUuid(actionId)
        require(x >= 0 && y >= 0)
        require(pressDurationMs in 40L..500L)
        require(waitAfterMs in 0L..10_000L)
    }
}

/** Immutable, ordered, independently hashed snapshot. An empty saved draft cannot start a run. */
class ClickPlan private constructor(
    val planId: String,
    val projectId: String,
    val revision: Long,
    val targetPackage: String,
    val width: Int,
    val height: Int,
    /** Android Surface rotation constant (0..3), not degrees. */
    val rotation: Int,
    val displayId: Int,
    actions: List<ClickAction>,
) {
    val actions: List<ClickAction> = frozenClickList(actions)
    val plannedDurationMs: Long = this.actions.sumOf { it.pressDurationMs + it.waitAfterMs }
    val digest: String

    init {
        requireClickUuid(planId)
        requireClickUuid(projectId)
        require(revision >= 1)
        require(targetPackage.length in 1..255 && targetPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        require(width > 0 && height > 0 && rotation in 0..3 && displayId == 0)
        require(this.actions.size <= 40 && this.actions.map { it.actionId }.distinct().size == this.actions.size)
        require(this.actions.all { it.x < width && it.y < height })
        require(plannedDurationMs <= 120_000)
        digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Versioned binary canonical encoding: big-endian integers + UTF-8 byte-length-prefixed text. */
    fun canonicalBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            fun text(value: String) {
                val utf8 = value.toByteArray(Charsets.UTF_8)
                out.writeInt(utf8.size)
                out.write(utf8)
            }
            text("tapscene-click-plan-v1")
            text(planId); text(projectId); out.writeLong(revision); text(targetPackage)
            out.writeInt(displayId); out.writeInt(width); out.writeInt(height); out.writeInt(rotation)
            out.writeInt(actions.size)
            actions.forEach { action ->
                text(action.actionId); out.writeInt(action.x); out.writeInt(action.y)
                out.writeLong(action.pressDurationMs); out.writeLong(action.waitAfterMs)
            }
        }
    }.toByteArray()

    fun copy(
        planId: String = this.planId,
        projectId: String = this.projectId,
        revision: Long = this.revision,
        targetPackage: String = this.targetPackage,
        width: Int = this.width,
        height: Int = this.height,
        rotation: Int = this.rotation,
        actions: List<ClickAction> = this.actions,
        displayId: Int = this.displayId,
    ): ClickPlan = create(projectId, targetPackage, width, height, rotation, actions, planId, revision, displayId)

    override fun equals(other: Any?): Boolean = other is ClickPlan && digest == other.digest
    override fun hashCode(): Int = digest.hashCode()

    companion object {
        fun create(
            projectId: String,
            targetPackage: String,
            width: Int,
            height: Int,
            rotation: Int,
            actions: List<ClickAction>,
            planId: String = UUID.randomUUID().toString(),
            revision: Long = 1,
            displayId: Int = 0,
        ): ClickPlan = ClickPlan(planId, projectId, revision, targetPackage, width, height, rotation, displayId, actions)
    }
}

enum class ClickMapping { Unknown }
enum class ClickRunPhase { Ready, Running, Paused, Completed, Stopped, Interrupted, Failed }
enum class ClickActionStatus { Pending, Intent, Accepted, Completed, Rejected, Cancelled, TimedOut, Unknown }
enum class ClickGestureResult { Completed, Cancelled }
enum class ClickGuard { Allowed, Blocked, Unknown }
enum class ClickStopReason {
    User, ProcessInterrupted, PermissionLost, RecordingLost, DisplayChanged, ScreenLocked,
    ServiceDestroyed, DispatchRejected, GestureCancelled, GestureTimedOut, DispatchUnknown, DurabilityFailure,
}
enum class ClickRunEventType {
    Started, DispatchIntent, DispatchAccepted, DispatchRejected, GestureCompleted, GestureCancelled,
    GestureTimedOut, DispatchUnknown, Paused, Resumed, GuardBlocked, GuardUnknown, Completed, Stopped, Interrupted,
}

data class ClickCallbackToken(val runId: String, val actionId: String, val generation: Long)

data class ClickActionOutcome(
    val actionId: String,
    val status: ClickActionStatus = ClickActionStatus.Pending,
    /** Reserved until a separately verified media-clock and capture-coordinate mapping exists. */
    val beforeFrameId: String? = null,
    val afterFrameId: String? = null,
    val mapping: ClickMapping = ClickMapping.Unknown,
) {
    init { requireClickUuid(actionId); require(beforeFrameId == null && afterFrameId == null) }
}

data class ClickRunEvent(
    val type: ClickRunEventType,
    val actionId: String? = null,
    /** Diagnostic monotonic time ONLY. It is never source PTS, a frame ID or evidence time. */
    val diagnosticUptimeMs: Long,
)

/** Stored separately from project/package data. No recording token, pixels, nodes or text is stored. */
@ConsistentCopyVisibility
data class ClickRun internal constructor(
    val runId: String,
    val plan: ClickPlan,
    val recordingSessionId: String,
    val sourceId: String,
    val generation: Long,
    val createdAtMs: Long,
    val phase: ClickRunPhase = ClickRunPhase.Ready,
    val nextActionIndex: Int = 0,
    val outcomes: List<ClickActionOutcome>,
    val events: List<ClickRunEvent> = emptyList(),
    val stopReason: ClickStopReason? = null,
    val journalRevision: Long = 0,
    val mapping: ClickMapping = ClickMapping.Unknown,
) {
    val terminal: Boolean get() = phase in setOf(ClickRunPhase.Completed, ClickRunPhase.Stopped, ClickRunPhase.Interrupted, ClickRunPhase.Failed)
    val isActive: Boolean get() = !terminal
    val projectId: String get() = plan.projectId

    internal fun validate() {
        requireClickUuid(runId); requireClickUuid(recordingSessionId); requireClickUuid(sourceId)
        require(generation >= 0 && createdAtMs >= 0 && journalRevision >= 0)
        require(plan.actions.isNotEmpty() && nextActionIndex in 0..plan.actions.size)
        require(outcomes.map { it.actionId } == plan.actions.map { it.actionId })
        require(outcomes.count { it.status == ClickActionStatus.Intent || it.status == ClickActionStatus.Accepted } <= 1)
        require(events.size <= 2_048 && events.all { it.diagnosticUptimeMs >= 0 &&
            (it.actionId == null || plan.actions.any { action -> action.actionId == it.actionId }) })
        require(outcomes.take(nextActionIndex).all { it.status == ClickActionStatus.Completed })
        require(outcomes.drop(nextActionIndex + 1).all { it.status == ClickActionStatus.Pending })
        if (phase == ClickRunPhase.Completed) require(nextActionIndex == outcomes.size && outcomes.all { it.status == ClickActionStatus.Completed })
        if (phase == ClickRunPhase.Ready) require(nextActionIndex == 0 && outcomes.all { it.status == ClickActionStatus.Pending })
    }

    companion object {
        fun create(
            plan: ClickPlan,
            recordingSessionId: String,
            sourceId: String,
            generation: Long,
            createdAtMs: Long,
            runId: String = UUID.randomUUID().toString(),
        ): ClickRun = ClickRun(runId, plan, recordingSessionId, sourceId, generation, createdAtMs,
            outcomes = frozenClickList(plan.actions.map { ClickActionOutcome(it.actionId) })).also { it.validate() }
    }
}

internal fun requireClickUuid(value: String) { require(UUID.fromString(value).toString() == value) }
internal fun <T> frozenClickList(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
