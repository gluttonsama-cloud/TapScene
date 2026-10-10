package com.tapscene.clickplan

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
) {
    var state: ClickRun = initial.copy(outcomes = frozenClickList(initial.outcomes), events = frozenClickList(initial.events))
        private set
    private var dispatching: ClickCallbackToken? = null
    /** Exists only in this process: the persisted intent is known not to have reached dispatch yet. */
    private var pendingDispatchToken: ClickCallbackToken? = null
    private var synchronousResult: Pair<ClickCallbackToken, ClickGestureResult>? = null
    private var nextReadyAtUptimeMs = 0L
    private var wakeGeneration = 0L

    init {
        state.validate()
        // A deserialized active run is recovery work, never execution permission.
        require(state.phase == ClickRunPhase.Ready || state.terminal)
    }

    fun start(): Boolean {
        if (state.phase != ClickRunPhase.Ready || state.outcomes.any { it.status != ClickActionStatus.Pending }) return false
        if (!commit(state.copy(phase = ClickRunPhase.Running), ClickRunEventType.Started)) return false
        advance()
        return !state.terminal || state.phase == ClickRunPhase.Completed
    }

    fun pause(): Boolean {
        if (state.phase != ClickRunPhase.Running && state.phase != ClickRunPhase.Ready) return false
        wakeGeneration++
        return commit(state.copy(phase = ClickRunPhase.Paused), ClickRunEventType.Paused)
    }

    /** An explicit user confirmation is mandatory, even after the target becomes visible again. */
    fun resume(confirmed: Boolean): Boolean {
        if (!confirmed || state.phase != ClickRunPhase.Paused) return false
        if (state.outcomes.any { it.status in nonReplayable }) return false
        if (!commit(state.copy(phase = ClickRunPhase.Running), ClickRunEventType.Resumed)) return false
        advance()
        return !state.terminal || state.phase == ClickRunPhase.Completed
    }

    fun stop(reason: ClickStopReason = ClickStopReason.User) {
        if (state.terminal) return
        pendingDispatchToken = null
        wakeGeneration++
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
            if (synchronousResult == null) synchronousResult = token to result
            return
        }
        if (state.outcomes[state.nextActionIndex].status != ClickActionStatus.Accepted) return
        when (result) {
            ClickGestureResult.Completed -> {
                val index = state.nextActionIndex
                nextReadyAtUptimeMs = uptimeMs() + state.plan.actions[index].waitAfterMs
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
            commit(state.copy(phase = ClickRunPhase.Completed), ClickRunEventType.Completed)
            return
        }
        when (readGuard()) {
            ClickGuard.Blocked -> { pauseForGuard(ClickRunEventType.GuardBlocked); return }
            ClickGuard.Unknown -> { pauseForGuard(ClickRunEventType.GuardUnknown); return }
            ClickGuard.Allowed -> Unit
        }
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
        // Consume the uncalled capability before entering external code. Duplicate queued
        // continuations and synchronous callbacks can never dispatch this action a second time.
        pendingDispatchToken = null
        val action = state.plan.actions[state.nextActionIndex]
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
        wakeGeneration++
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
        commit(outcome(status).copy(
            phase = if (status == ClickActionStatus.Rejected) ClickRunPhase.Failed else ClickRunPhase.Interrupted,
            stopReason = reason,
        ), event, state.outcomes[state.nextActionIndex].actionId)
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
