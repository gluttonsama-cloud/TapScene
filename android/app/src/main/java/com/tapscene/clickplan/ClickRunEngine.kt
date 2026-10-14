package com.tapscene.clickplan

import com.tapscene.recording.AnchorResult
import com.tapscene.recording.FrameBoundary
import com.tapscene.recording.InitialFrameAdmissionHandle
import com.tapscene.recording.InitialFrameAdmissionPort
import com.tapscene.recording.InitialFrameAdmissionStatus
import com.tapscene.recording.InitialFrameAuthorization
import java.util.concurrent.atomic.AtomicBoolean
import com.tapscene.recording.FrameAnchorAction
import com.tapscene.recording.FrameAnchorPort
import com.tapscene.recording.FrameMissingReason

/**
 * Pure single-threaded executor. The Android owner serializes every entry point on its main Looper.
 * All transitions are persisted before publication, and intent is durable BEFORE dispatch. A platform
 * completion means only that the gesture finished; it does not establish a business outcome.
 * No existing run is automatically restarted, and no failed/uncertain action can be replayed.
 */
class ClickRunEngine(
    initial: ClickRun,
    private val persist: (ClickRun) -> Unit,
    private val dispatch: (ClickCallbackToken, ClickAction, (ClickCallbackToken, ClickGestureResult) -> Unit) -> Boolean,
    /** Always enqueue, including delay=0; inline execution would skip the safety yield. */
    private val schedule: (Long, () -> Unit) -> Unit,
    private val uptimeMs: () -> Long,
    private val guard: (ClickPlan) -> ClickGuard,
    /** Must match this run's project, recording session and source, in the Recording phase. */
    private val recordingReady: (ClickRun) -> Boolean,
    private val onState: (ClickRun) -> Unit = {},
    /** Optional evidence only. Every call must select/enqueue without waiting for pixels or IO. */
    private val frames: FrameAnchorPort = FrameAnchorPort.None,
    /** Null is for generic/nonproduction runners only. The Android owner always supplies this port. */
    private val initialFrames: InitialFrameAdmissionPort? = null,
    private val initialAuthorization: () -> InitialFrameAuthorization? = { null },
    private val onPreparingInitialFrame: () -> Unit = {},
) {
    var state: ClickRun = initial.copy(outcomes = frozenClickList(initial.outcomes), events = frozenClickList(initial.events))
        private set
    private var dispatching: ClickCallbackToken? = null
    /** Exists only in this process: the persisted intent is known not to have reached dispatch yet. */
    private var pendingDispatchToken: ClickCallbackToken? = null
    private var synchronousResult: Pair<ClickCallbackToken, ClickGestureResult>? = null
    private var nextReadyAtUptimeMs = 0L
    private var wakeGeneration = 0L
    private var inFlightFrameAction: FrameAnchorAction? = null
    private var afterWaitFrameAction: FrameAnchorAction? = null
    private var firstDispatchAttempted = false
    private var initialConsumeAttempted = false
    private var initialRequest: InitialRequest? = null
    private class InitialRequest(val action: FrameAnchorAction, val authorization: InitialFrameAuthorization,
        val transition: ClickRunEventType) {
        var handle: InitialFrameAdmissionHandle? = null
        val notificationQueued = AtomicBoolean(false)
    }

    init {
        state.validate()
        // A deserialized active run is recovery work, never execution permission.
        require(state.phase == ClickRunPhase.Ready || state.terminal)
    }

    fun start(): Boolean {
        if (state.phase != ClickRunPhase.Ready || state.outcomes.any { it.status != ClickActionStatus.Pending }) return false
        if (initialFrames != null) return prepareInitialFrame()
        if (!commit(state.copy(phase = ClickRunPhase.Running), ClickRunEventType.Started)) return false
        advance()
        return !state.terminal || state.phase == ClickRunPhase.Completed
    }

    fun pause(): Boolean {
        if (state.phase != ClickRunPhase.Running && state.phase != ClickRunPhase.Ready &&
            !(state.phase == ClickRunPhase.Paused && initialRequest != null)) return false
        if (initialConsumeAttempted && !firstDispatchAttempted) { stop(ClickStopReason.DispatchUnknown); return true }
        wakeGeneration++
        cancelFrameEpoch(FrameMissingReason.Interrupted)
        return commit(state.copy(phase = ClickRunPhase.Paused), ClickRunEventType.Paused)
    }

    /** An explicit user confirmation is mandatory, even after the target becomes visible again. */
    fun resume(confirmed: Boolean): Boolean {
        if (!confirmed || state.phase != ClickRunPhase.Paused) return false
        if (state.outcomes.any { it.status in nonReplayable }) return false
        if (initialFrames != null && !firstDispatchAttempted) return prepareInitialFrame()
        if (!commit(state.copy(phase = ClickRunPhase.Running), ClickRunEventType.Resumed)) return false
        advance()
        return !state.terminal || state.phase == ClickRunPhase.Completed
    }

    fun stop(reason: ClickStopReason = ClickStopReason.User) {
        if (state.terminal) return
        pendingDispatchToken = null
        wakeGeneration++
        cancelFrameEpoch(if (reason == ClickStopReason.User) FrameMissingReason.CaptureStopped else FrameMissingReason.Interrupted)
        val interrupted = reason != ClickStopReason.User
        val outcomes = state.outcomes.map {
            if (it.status == ClickActionStatus.Intent || it.status == ClickActionStatus.Accepted) it.copy(status = ClickActionStatus.Unknown) else it
        }
        commit(state.copy(
            phase = if (interrupted) ClickRunPhase.Interrupted else ClickRunPhase.Stopped,
            outcomes = frozenClickList(outcomes), stopReason = reason,
        ), if (interrupted) ClickRunEventType.Interrupted else ClickRunEventType.Stopped)
    }

    fun onGestureResult(token: ClickCallbackToken, result: ClickGestureResult) {
        if (!matchesInFlight(token)) return
        if (dispatching == token) {
            // Fake dispatchers and platform wrappers may call back before returning acceptance.
            if (synchronousResult == null) {
                // Observe the sequence boundary at callback arrival, before acceptance fsync.
                // This mark is provisional until dispatch returns true; rejection closes its epoch.
                if (result == ClickGestureResult.Completed) markFrameGestureCompleted(token)
                synchronousResult = token to result
            }
            return
        }
        if (state.outcomes[state.nextActionIndex].status != ClickActionStatus.Accepted) return
        when (result) {
            ClickGestureResult.Completed -> {
                val index = state.nextActionIndex
                nextReadyAtUptimeMs = uptimeMs() + state.plan.actions[index].waitAfterMs
                // Capture the lower bound before completion fsync; diagnostic time is never PTS.
                markFrameGestureCompleted(token)
                if (!matchesInFlight(token)) return
                val next = index + 1
                val updated = outcome(ClickActionStatus.Completed).copy(nextActionIndex = next)
                if (!commit(updated, ClickRunEventType.GestureCompleted, token.actionId)) return
                if (next == state.plan.actions.size) {
                    // Honor the last declared wait too; it remains part of the recorded plan duration.
                    if (state.phase == ClickRunPhase.Running) advance()
                } else if (state.phase == ClickRunPhase.Running) advance()
            }
            ClickGestureResult.Cancelled -> failAction(ClickActionStatus.Cancelled, ClickStopReason.GestureCancelled, ClickRunEventType.GestureCancelled)
        }
    }

    private fun firstFrameAction(): FrameAnchorAction? = state.plan.actions.getOrNull(state.nextActionIndex)?.let {
        FrameAnchorAction(state.runId, it.actionId, state.recordingSessionId, state.sourceId, state.generation)
    }

    private fun readAuthorization(): InitialFrameAuthorization? = try { initialAuthorization() } catch (_: Exception) { null }

    private fun matchesInitial(request: InitialRequest): Boolean = initialRequest === request &&
        !state.terminal && !firstDispatchAttempted && request.action == firstFrameAction() &&
        request.authorization == readAuthorization() && request.authorization.validAt(uptimeMs())

    private fun prepareInitialFrame(): Boolean {
        val expectedPhase = state.phase
        if (initialRequest != null || initialConsumeAttempted) return false
        if (!isRecordingReady()) { stop(ClickStopReason.RecordingLost); return false }
        when (readGuard()) {
            ClickGuard.Blocked -> { pauseForGuard(ClickRunEventType.GuardBlocked); return false }
            ClickGuard.Unknown -> { pauseForGuard(ClickRunEventType.GuardUnknown); return false }
            ClickGuard.Allowed -> Unit
        }
        val authorization = readAuthorization()
        // Owner predicates are external code too; they cannot reopen a reentrantly paused/stopped run.
        if (state.phase != expectedPhase || state.terminal || firstDispatchAttempted || initialRequest != null) return false
        if (authorization == null || !authorization.validAt(uptimeMs())) {
            pauseForGuard(ClickRunEventType.GuardUnknown); return false
        }
        val request = InitialRequest(firstFrameAction() ?: return false, authorization,
            if (state.events.any { it.type == ClickRunEventType.Started }) ClickRunEventType.Resumed else ClickRunEventType.Started)
        initialRequest = request
        val handle = try {
            requireNotNull(initialFrames).begin(request.action, authorization) { enqueueInitialChange(request) }
        } catch (_: Exception) {
            if (initialRequest === request) pauseForGuard(ClickRunEventType.GuardUnknown)
            return false
        }
        request.handle = handle
        if (!matchesInitial(request)) {
            if (initialRequest === request) pauseForGuard(ClickRunEventType.GuardUnknown)
            else frameSafely { handle.cancel() }
            return false
        }
        onPreparingInitialFrame()
        // The original confirmation deadline bounds the entire preparation, including fsync/yield.
        scheduleSafely((authorization.deadlineUptimeMs - uptimeMs() + 1).coerceAtLeast(0)) {
            if (initialRequest === request && !request.authorization.validAt(uptimeMs())) pauseForGuard(ClickRunEventType.GuardUnknown)
        }
        if (!enqueueInitialChange(request)) { stop(ClickStopReason.ServiceDestroyed); return false }
        return !state.terminal
    }

    /** May be called by GL/codec threads, including synchronously inside begin. Never enter the engine inline. */
    private fun enqueueInitialChange(request: InitialRequest): Boolean {
        if (!request.notificationQueued.compareAndSet(false, true)) return true
        return try {
            schedule(0) {
                request.notificationQueued.set(false)
                initialChanged(request)
            }
            true
        } catch (_: Exception) {
            // No engine mutation on a foreign thread. The already scheduled deadline still closes it.
            request.notificationQueued.set(false)
            false
        }
    }

    private fun initialChanged(request: InitialRequest) {
        if (initialRequest !== request || state.terminal || firstDispatchAttempted) return
        if (!matchesInitial(request)) { pauseForGuard(ClickRunEventType.GuardUnknown); return }
        if (!isRecordingReady()) { stop(ClickStopReason.RecordingLost); return }
        when (readGuard()) {
            ClickGuard.Blocked -> { pauseForGuard(ClickRunEventType.GuardBlocked); return }
            ClickGuard.Unknown -> { pauseForGuard(ClickRunEventType.GuardUnknown); return }
            ClickGuard.Allowed -> Unit
        }
        if (!matchesInitial(request)) return
        when (initialStatus(request)) {
            InitialFrameAdmissionStatus.Pending -> Unit
            InitialFrameAdmissionStatus.Ready -> {
                if (state.phase == ClickRunPhase.Ready || state.phase == ClickRunPhase.Paused) {
                    if (!commit(state.copy(phase = ClickRunPhase.Running), request.transition)) return
                }
                if (matchesInitial(request)) advance()
            }
            else -> pauseForGuard(ClickRunEventType.GuardUnknown)
        }
    }

    private fun initialStatus(request: InitialRequest): InitialFrameAdmissionStatus =
        try { request.handle?.status ?: InitialFrameAdmissionStatus.Unavailable }
        catch (_: Exception) { InitialFrameAdmissionStatus.Unavailable }

    private fun consumeInitialFrame(frameAction: FrameAnchorAction): Boolean {
        val request = initialRequest
        if (request == null || !matchesInitial(request) || request.action != frameAction) {
            pauseForGuard(ClickRunEventType.GuardUnknown); return false
        }
        when (initialStatus(request)) {
            InitialFrameAdmissionStatus.Pending -> return false // Keep the one durable, uncalled token.
            InitialFrameAdmissionStatus.Ready -> Unit
            else -> { pauseForGuard(ClickRunEventType.GuardUnknown); return false }
        }
        // From this point cancellation is terminal, even when consume reenters pause before returning.
        initialConsumeAttempted = true
        val result = try { requireNotNull(request.handle).consume() } catch (_: Exception) { null }
        val admission = (result as? AnchorResult.Ticket)?.ticket?.admission
        if (result !is AnchorResult.Ticket || result.ticket.action != frameAction ||
            result.ticket.boundary != FrameBoundary.Before || admission == null ||
            admission.confirmedAtUptimeMs != request.authorization.confirmedAtUptimeMs ||
            admission.deadlineUptimeMs != request.authorization.deadlineUptimeMs ||
            initialStatus(request) != InitialFrameAdmissionStatus.Consumed) {
            stop(ClickStopReason.DispatchUnknown); return false
        }
        if (!matchesInitial(request)) { stop(ClickStopReason.DispatchUnknown); return false }
        if (!isRecordingReady()) { stop(ClickStopReason.RecordingLost); return false }
        if (readGuard() != ClickGuard.Allowed || !matchesInitial(request)) { stop(ClickStopReason.DispatchUnknown); return false }
        return true
    }

    private fun advance() {
        if (state.phase != ClickRunPhase.Running) return
        if (pendingDispatchToken != null) { queuePendingDispatch(); return }
        if (state.outcomes.any { it.status == ClickActionStatus.Intent || it.status == ClickActionStatus.Accepted }) return
        if (!isRecordingReady()) { stop(ClickStopReason.RecordingLost); return }
        val wait = nextReadyAtUptimeMs - uptimeMs()
        if (wait > 0) {
            val expectedWake = ++wakeGeneration
            scheduleSafely(wait) {
                if (expectedWake == wakeGeneration && state.phase == ClickRunPhase.Running) advance()
            }
            return
        }
        if (state.nextActionIndex == state.plan.actions.size) {
            // Preserve completion semantics at the final wait, but never anchor an unsafe window.
            if (readGuard() == ClickGuard.Allowed) finishFrameWait()
            else cancelFrameEpoch(FrameMissingReason.Interrupted)
            if (state.phase == ClickRunPhase.Running) commit(state.copy(phase = ClickRunPhase.Completed), ClickRunEventType.Completed)
            return
        }
        when (readGuard()) {
            ClickGuard.Blocked -> { pauseForGuard(ClickRunEventType.GuardBlocked); return }
            ClickGuard.Unknown -> { pauseForGuard(ClickRunEventType.GuardUnknown); return }
            ClickGuard.Allowed -> Unit
        }
        finishFrameWait()
        if (state.phase != ClickRunPhase.Running) return
        val action = state.plan.actions[state.nextActionIndex]
        if (state.outcomes[state.nextActionIndex].status != ClickActionStatus.Pending) { stop(ClickStopReason.DispatchUnknown); return }
        val token = ClickCallbackToken(state.runId, action.actionId, state.generation)
        if (!commit(outcome(ClickActionStatus.Intent), ClickRunEventType.DispatchIntent, action.actionId)) return
        if (state.terminal) return
        pendingDispatchToken = token
        queuePendingDispatch()
    }

    private fun queuePendingDispatch() {
        val token = pendingDispatchToken ?: return
        if (state.phase != ClickRunPhase.Running) return
        // fsync ran on the owner thread. Yield so window/stop events queued during that write are
        // processed before using cached window metadata. The scheduler MUST enqueue even delay=0.
        scheduleSafely(0) { dispatchPending(token) }
    }

    private fun dispatchPending(token: ClickCallbackToken) {
        if (pendingDispatchToken != token || state.phase != ClickRunPhase.Running) return
        if (!matchesInFlight(token)) { pendingDispatchToken = null; return }
        if (!isRecordingReady()) { stop(ClickStopReason.RecordingLost); return }
        when (readGuard()) {
            ClickGuard.Blocked -> { pauseForGuard(ClickRunEventType.GuardBlocked); return }
            ClickGuard.Unknown -> { pauseForGuard(ClickRunEventType.GuardUnknown); return }
            ClickGuard.Allowed -> Unit
        }
        if (pendingDispatchToken != token || state.phase != ClickRunPhase.Running || !matchesInFlight(token)) return
        val action = state.plan.actions[state.nextActionIndex]
        val frameAction = FrameAnchorAction(state.runId, action.actionId, state.recordingSessionId, state.sourceId, state.generation)
        inFlightFrameAction = frameAction
        if (initialFrames != null && !firstDispatchAttempted) {
            if (!consumeInitialFrame(frameAction)) return
        } else frameSafely { frames.before(frameAction) }
        // A reentrant owner pause/stop still wins. A pause retains the uncalled dispatch capability
        // for explicit resume; its old frame epoch has already been closed.
        if (pendingDispatchToken != token || state.phase != ClickRunPhase.Running || !matchesInFlight(token)) return
        // Consume the uncalled capability before entering platform code. Duplicate queued
        // continuations and synchronous callbacks can never dispatch this action a second time.
        pendingDispatchToken = null
        firstDispatchAttempted = true
        initialRequest = null
        dispatching = token
        synchronousResult = null
        val accepted = try {
            dispatch(token, action, ::onGestureResult)
        } catch (_: Exception) {
            dispatching = null
            synchronousResult = null
            failAction(ClickActionStatus.Unknown, ClickStopReason.DispatchUnknown, ClickRunEventType.DispatchUnknown)
            return
        }
        dispatching = null
        // A reentrant owner stop must not be overwritten when dispatch returns.
        if (!matchesInFlight(token)) { synchronousResult = null; return }
        if (!accepted) {
            synchronousResult = null
            failAction(ClickActionStatus.Rejected, ClickStopReason.DispatchRejected, ClickRunEventType.DispatchRejected)
            return
        }
        if (!commit(outcome(ClickActionStatus.Accepted), ClickRunEventType.DispatchAccepted, action.actionId)) { synchronousResult = null; return }
        val buffered = synchronousResult
        synchronousResult = null
        if (buffered != null) onGestureResult(buffered.first, buffered.second)
        if (matchesInFlight(token)) {
            scheduleSafely(action.pressDurationMs + CALLBACK_GRACE_MS) {
                if (matchesInFlight(token)) failAction(ClickActionStatus.TimedOut, ClickStopReason.GestureTimedOut, ClickRunEventType.GestureTimedOut)
            }
        }
    }

    private fun readGuard(): ClickGuard = try { guard(state.plan) } catch (_: Exception) { ClickGuard.Unknown }
    private fun isRecordingReady(): Boolean = try { recordingReady(state) } catch (_: Exception) { false }

    private fun pauseForGuard(type: ClickRunEventType) {
        if (state.terminal) return
        if (initialConsumeAttempted && !firstDispatchAttempted) { stop(ClickStopReason.DispatchUnknown); return }
        wakeGeneration++
        cancelFrameEpoch(FrameMissingReason.Interrupted)
        commit(state.copy(phase = ClickRunPhase.Paused), type)
    }

    private fun matchesInFlight(token: ClickCallbackToken): Boolean = !state.terminal &&
        token.runId == state.runId && token.generation == state.generation &&
        state.nextActionIndex < state.outcomes.size && state.outcomes[state.nextActionIndex].actionId == token.actionId &&
        state.outcomes[state.nextActionIndex].status in setOf(ClickActionStatus.Intent, ClickActionStatus.Accepted)

    private fun outcome(status: ClickActionStatus): ClickRun = state.copy(outcomes = frozenClickList(
        state.outcomes.mapIndexed { index, item -> if (index == state.nextActionIndex) item.copy(status = status) else item },
    ))

    private fun failAction(status: ClickActionStatus, reason: ClickStopReason, event: ClickRunEventType) {
        if (state.terminal || state.nextActionIndex >= state.outcomes.size) return
        wakeGeneration++
        cancelFrameEpoch(FrameMissingReason.Interrupted)
        commit(outcome(status).copy(
            phase = if (status == ClickActionStatus.Rejected) ClickRunPhase.Failed else ClickRunPhase.Interrupted,
            stopReason = reason,
        ), event, state.outcomes[state.nextActionIndex].actionId)
    }

    private fun markFrameGestureCompleted(token: ClickCallbackToken) {
        val action = inFlightFrameAction?.takeIf {
            it.runId == token.runId && it.actionId == token.actionId && it.generation == token.generation
        } ?: return
        // Consume before external code, including the buffered synchronous-completion path.
        inFlightFrameAction = null
        afterWaitFrameAction = action
        frameSafely { frames.markGestureCompleted(action) }
    }

    private fun finishFrameWait() {
        val action = afterWaitFrameAction ?: return
        afterWaitFrameAction = null
        frameSafely { frames.afterWait(action) }
    }

    private fun cancelFrameEpoch(reason: FrameMissingReason) {
        val request = initialRequest
        initialRequest = null // Fence late and reentrant notifications before returning the lease.
        frameSafely { request?.handle?.cancel() }
        inFlightFrameAction = null
        afterWaitFrameAction = null
        frameSafely { frames.cancelEpoch(reason) }
    }

    private inline fun frameSafely(block: () -> Unit) {
        // Auxiliary evidence failure must never retry a gesture or rewrite its durable outcome.
        try { block() } catch (_: Exception) { }
    }

    private fun scheduleSafely(delayMs: Long, callback: () -> Unit) {
        try { schedule(delayMs, callback) } catch (_: Exception) { stop(ClickStopReason.ServiceDestroyed) }
    }

    private fun commit(updated: ClickRun, event: ClickRunEventType, actionId: String? = null): Boolean {
        // Leave room for a terminal record, including recovery after a crash at the log limit.
        if (!updated.terminal && state.events.size >= 2_046) {
            stop(ClickStopReason.DurabilityFailure)
            return false
        }
        val candidate = updated.copy(
            journalRevision = state.journalRevision + 1,
            outcomes = frozenClickList(updated.outcomes),
            events = frozenClickList(state.events + ClickRunEvent(event, actionId, uptimeMs())),
        )
        try {
            candidate.validate()
            persist(candidate)
        } catch (_: Exception) {
            // The disk may contain either revision. Never roll it back or dispatch another gesture.
            wakeGeneration++
            pendingDispatchToken = null
            cancelFrameEpoch(FrameMissingReason.Interrupted)
            state = state.copy(phase = ClickRunPhase.Failed, stopReason = ClickStopReason.DurabilityFailure,
                outcomes = frozenClickList(state.outcomes.map {
                    if (it.status == ClickActionStatus.Intent || it.status == ClickActionStatus.Accepted) it.copy(status = ClickActionStatus.Unknown) else it
                }))
            onState(state)
            return false
        }
        state = candidate
        onState(state)
        return true
    }

    companion object {
        private const val CALLBACK_GRACE_MS = 5_000L
        private val nonReplayable = setOf(ClickActionStatus.Rejected, ClickActionStatus.Cancelled, ClickActionStatus.TimedOut, ClickActionStatus.Unknown)
    }
}
