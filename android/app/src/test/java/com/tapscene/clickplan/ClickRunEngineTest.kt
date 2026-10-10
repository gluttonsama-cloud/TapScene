package com.tapscene.clickplan

import com.tapscene.recording.AnchorResult
import com.tapscene.recording.FrameAnchorAction
import com.tapscene.recording.FrameAnchorPort
import com.tapscene.recording.FrameMissingReason

import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ClickRunEngineTest {
    @Test fun planSnapshotIsImmutableBoundedAndOrderSensitive() {
        val input = mutableListOf(tap(10), tap(20))
        val plan = plan(input)
        val digest = plan.digest
        input.reverse()
        assertEquals(10, plan.actions.first().x)
        assertEquals(digest, plan.copy().digest)
        assertNotEquals(digest, plan.copy(actions = input).digest)
        assertNotEquals(digest, plan.copy(revision = 2).digest)
        expectFailure { (plan.actions as MutableList<ClickAction>).clear() }
        expectFailure { plan.copy(actions = listOf(plan.actions[0], plan.actions[0])) }
        expectFailure { plan.copy(actions = listOf(tap(100))) }
        expectFailure { plan.copy(actions = List(41) { tap(1) }) }
        expectFailure { plan.copy(actions = List(12) { tap(1).copy(pressDurationMs = 500, waitAfterMs = 10_000) }) }
        expectFailure { tap(1).copy(pressDurationMs = 39) }
        expectFailure { tap(1).copy(waitAfterMs = 10_001) }
        expectFailure { plan.copy(displayId = 1) }
        val draft = plan.copy(actions = emptyList())
        expectFailure { run(draft) }
    }

    @Test fun intentAndAcceptanceAreDurableBeforeCallbackAndOnlyOneGestureIsInFlight() {
        val f = Fake(listOf(tap(10, wait = 300), tap(20, wait = 0)))
        f.start()
        assertEquals(1, f.calls.size)
        assertEquals(ClickRunEventType.DispatchAccepted, f.writes.last().events.last().type)
        f.advance(100)
        assertEquals(1, f.calls.size)
        val first = f.calls.first()
        f.complete(first)
        assertEquals(1, f.calls.size)
        assertEquals(1, f.engine.state.nextActionIndex)
        f.complete(first) // duplicate must not settle the next action
        f.advance(299)
        assertEquals(1, f.calls.size)
        f.advance(1)
        assertEquals(2, f.calls.size)
        f.complete(first) // old action callback must not settle the currently accepted action
        assertEquals(ClickActionStatus.Accepted, f.engine.state.outcomes[1].status)
        f.complete(f.calls.last(), f.calls.last().token.copy(generation = 2))
        assertEquals(ClickActionStatus.Accepted, f.engine.state.outcomes[1].status)
        f.complete(f.calls.last())
        assertEquals(ClickRunPhase.Completed, f.engine.state.phase)
        assertTrue(f.engine.state.outcomes.all { it.status == ClickActionStatus.Completed &&
            it.beforeFrameId == null && it.afterFrameId == null && it.mapping == ClickMapping.Unknown })
        f.advance(10_000)
        assertEquals(2, f.calls.size)
    }

    @Test fun pauseAllowsInFlightSettlementAndRequiresExplicitResumeWithoutReplayingIt() {
        val f = Fake(listOf(tap(10), tap(20)))
        f.start()
        f.engine.pause()
        f.complete(f.calls.single())
        f.advance(10_000)
        assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
        assertEquals(1, f.engine.state.nextActionIndex)
        assertFalse(f.resume(false))
        assertEquals(1, f.calls.size)
        assertTrue(f.resume(true))
        assertEquals(2, f.calls.size)
        assertEquals(20, f.calls.last().action.x)
    }

    @Test fun pauseBeforeStartRequiresExplicitResumeAndRechecksTheGuards() {
        val f = Fake()
        f.recordingReady = false
        assertTrue(f.engine.pause())
        assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
        assertTrue(f.calls.isEmpty())
        assertTrue(f.engine.state.outcomes.all { it.status == ClickActionStatus.Pending })
        assertEquals(listOf(ClickRunEventType.Paused), f.engine.state.events.map { it.type })
        assertFalse(f.start())
        assertFalse(f.resume(false))
        f.recordingReady = true
        f.guard = ClickGuard.Unknown
        f.resume(true)
        assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
        assertTrue(f.calls.isEmpty())
        f.guard = ClickGuard.Allowed
        f.advance(10_000)
        assertTrue(f.calls.isEmpty())
        assertTrue(f.resume(true))
        assertEquals(1, f.calls.size)
        assertEquals(ClickActionStatus.Accepted, f.engine.state.outcomes.first().status)
    }

    @Test fun blockedAndUnknownWindowsDoNotDispatchAndRecordingLossIsTerminal() {
        val f = Fake()
        f.guard = ClickGuard.Unknown
        f.start()
        assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
        assertTrue(f.calls.isEmpty())
        f.guard = ClickGuard.Blocked
        f.resume(true)
        assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
        f.guard = ClickGuard.Allowed
        f.advance(10_000)
        assertTrue(f.calls.isEmpty()) // guard becoming safe is not permission to resume
        f.recordingReady = false
        f.resume(true)
        assertEquals(ClickStopReason.RecordingLost, f.engine.state.stopReason)
        f.recordingReady = true
        assertFalse(f.resume(true))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun failedIntentPersistenceClosesExecutionBeforeDispatch() {
        val f = Fake()
        f.failEvent = ClickRunEventType.DispatchIntent
        f.start()
        assertTrue(f.calls.isEmpty())
        assertEquals(ClickRunPhase.Failed, f.engine.state.phase)
        assertEquals(ClickStopReason.DurabilityFailure, f.engine.state.stopReason)
        assertFalse(f.resume(true))
        assertFalse(f.start())
    }

    @Test fun windowOrRecordingChangeDuringDurableIntentWritePreventsDispatchAndReplay() {
        for (windowChange in listOf(true, false)) {
            val f = Fake()
            f.onPersist = { state ->
                if (state.events.last().type == ClickRunEventType.DispatchIntent) {
                    if (windowChange) f.guard = ClickGuard.Blocked else f.recordingReady = false
                }
            }
            f.start()
            assertTrue(f.calls.isEmpty())
            if (windowChange) {
                assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
                assertEquals(ClickActionStatus.Intent, f.engine.state.outcomes.first().status)
                f.guard = ClickGuard.Allowed
                f.advance(10_000)
                assertTrue(f.calls.isEmpty()) // A safe window is not explicit resume permission.
            } else {
                assertTrue(f.engine.state.terminal)
                assertEquals(ClickActionStatus.Unknown, f.engine.state.outcomes.first().status)
                assertFalse(f.resume(true))
            }
        }
    }

    @Test fun persistenceFailureAfterPlatformAcceptanceNeverDispatchesTheNextAction() {
        val f = Fake()
        f.failEvent = ClickRunEventType.DispatchAccepted
        f.start()
        assertEquals(1, f.calls.size)
        assertEquals(ClickActionStatus.Unknown, f.engine.state.outcomes.first().status)
        assertEquals(ClickRunEventType.DispatchIntent, f.writes.last().events.last().type)
        f.complete(f.calls.first())
        f.advance(10_000)
        assertEquals(1, f.calls.size)
        assertEquals(ClickStopReason.DurabilityFailure, f.engine.state.stopReason)
    }

    @Test fun rejectionCancellationTimeoutAndStopAreTerminalAndLateCallbacksAreIsolated() {
        for (ending in listOf("rejected", "cancelled", "timeout", "stop", "permission", "display", "throws")) {
            val f = Fake()
            f.accept = ending != "rejected"
            f.throwDispatch = ending == "throws"
            f.start()
            when (ending) {
                "cancelled" -> f.calls.first().callback(f.calls.first().token, ClickGestureResult.Cancelled)
                "timeout" -> f.advance(5_080)
                "stop" -> f.engine.stop()
                "permission" -> f.engine.stop(ClickStopReason.PermissionLost)
                "display" -> f.engine.stop(ClickStopReason.DisplayChanged)
            }
            assertTrue("$ending is terminal", f.engine.state.terminal)
            val settled = f.engine.state
            f.complete(f.calls.first())
            f.advance(20_000)
            assertEquals(settled, f.engine.state)
            assertFalse(f.resume(true))
            assertFalse(f.start())
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun synchronousCallbacksStillPersistAcceptanceBeforeCompletion() {
        val f = Fake(listOf(tap(10, wait = 0)))
        f.synchronousCompletion = true
        f.start()
        assertEquals(ClickRunPhase.Completed, f.engine.state.phase)
        assertEquals(listOf(ClickRunEventType.Started, ClickRunEventType.DispatchIntent,
            ClickRunEventType.DispatchAccepted, ClickRunEventType.GestureCompleted, ClickRunEventType.Completed),
            f.engine.state.events.map { it.type })
    }

    @Test fun queuedWindowPauseAndStopAfterIntentAreObservedBeforeAtMostOnceDispatch() {
        for (event in listOf("window", "pause", "stop")) {
            val f = Fake()
            f.onPersist = { saved ->
                if (saved.events.last().type == ClickRunEventType.DispatchIntent) {
                    // Simulate an event queued while synchronous journal fsync blocks the owner.
                    f.queue {
                        when (event) {
                            "window" -> f.guard = ClickGuard.Blocked
                            "pause" -> f.engine.pause()
                            "stop" -> f.engine.stop()
                        }
                    }
                }
            }
            f.engine.start() // deliberately do not flush the owner's queued turn yet
            assertTrue(f.calls.isEmpty())
            assertEquals(ClickActionStatus.Intent, f.engine.state.outcomes.first().status)
            f.advance(0)
            assertTrue(f.calls.isEmpty())
            assertTrue(f.frames.calls.none { it.hook == "before" })
            if (event == "stop") {
                assertEquals(ClickRunPhase.Stopped, f.engine.state.phase)
                assertEquals(ClickActionStatus.Unknown, f.engine.state.outcomes.first().status)
                assertFalse(f.resume(true))
            } else {
                assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
                assertEquals(ClickActionStatus.Intent, f.engine.state.outcomes.first().status)
                f.guard = ClickGuard.Allowed
                assertFalse(f.resume(false))
                assertTrue(f.engine.resume(true))
                // Multiple validly queued continuations still consume the uncalled token once.
                f.engine.pause()
                assertTrue(f.engine.resume(true))
                assertTrue(f.calls.isEmpty())
                f.advance(0)
                assertEquals(1, f.calls.size)
                assertEquals(1, f.frames.calls.count { it.hook == "before" })
                assertEquals(ClickActionStatus.Accepted, f.engine.state.outcomes.first().status)
                assertEquals(1, f.engine.state.events.count { it.type == ClickRunEventType.DispatchIntent })
                f.engine.stop()
                f.advance(10_000)
                assertEquals(1, f.calls.size)
            }
        }
    }

    @Test fun persistedActiveRunCannotBeConstructedAsAnExecutableEngine() {
        val f = Fake()
        f.start()
        expectFailure { Fake(initial = f.engine.state) }
    }

    @Test fun frameHooksBracketDispatchAndEachLogicalWaitIncludingTheLast() {
        val f = Fake(listOf(tap(10, wait = 300), tap(20, wait = 150)))
        f.start()
        val first = f.calls.single()
        val firstId = first.action.actionId
        assertEquals(listOf("before"), f.frames.calls.map { it.hook })
        assertTrue(f.timeline.indexOf("write:DispatchIntent:$firstId") < f.timeline.indexOf("before:$firstId"))
        assertTrue(f.timeline.indexOf("before:$firstId") < f.timeline.indexOf("dispatch:$firstId"))
        f.complete(first)
        assertTrue(f.timeline.indexOf("completed:$firstId") < f.timeline.indexOf("write:GestureCompleted:$firstId"))
        f.advance(299)
        assertEquals(listOf("before", "completed"), f.frames.calls.map { it.hook })
        f.advance(1)
        val second = f.calls.last()
        val secondId = second.action.actionId
        assertTrue(f.timeline.indexOf("after:$firstId") < f.timeline.indexOf("write:DispatchIntent:$secondId"))
        assertEquals(listOf("before", "completed", "after", "before"), f.frames.calls.map { it.hook })
        f.complete(second)
        f.advance(149)
        assertEquals(ClickRunPhase.Running, f.engine.state.phase)
        assertEquals(1, f.frames.calls.count { it.hook == "after" })
        f.advance(1)
        assertEquals(ClickRunPhase.Completed, f.engine.state.phase)
        assertEquals(listOf("before", "completed", "after", "before", "completed", "after"), f.frames.calls.map { it.hook })
        f.frames.calls.forEach { call ->
            val action = requireNotNull(call.action)
            assertEquals(f.engine.state.runId, action.runId)
            assertEquals(f.engine.state.recordingSessionId, action.sessionId)
            assertEquals(f.engine.state.sourceId, action.sourceId)
            assertEquals(f.engine.state.generation, action.generation)
        }
        assertTrue(f.engine.state.outcomes.all { it.beforeFrameId == null && it.afterFrameId == null && it.mapping == ClickMapping.Unknown })
    }

    @Test fun missingAndThrowingFrameHooksNeverDelayOrFailHealthyGestureOutcomes() {
        for (throwing in listOf(null, "before", "completed", "after")) {
            val f = Fake(listOf(tap(10, wait = 0), tap(20, wait = 0)))
            f.frames.throwHook = throwing
            f.start()
            f.complete(f.calls.single())
            assertEquals(2, f.calls.size)
            f.complete(f.calls.last())
            assertEquals(ClickRunPhase.Completed, f.engine.state.phase)
            assertTrue(f.engine.state.outcomes.all { it.status == ClickActionStatus.Completed })
            assertEquals(1_000L, f.now)
            assertEquals(2, f.frames.calls.count { it.hook == "before" })
            assertEquals(2, f.frames.calls.count { it.hook == "completed" })
            assertEquals(2, f.frames.calls.count { it.hook == "after" })
            assertTrue(f.frames.calls.none { it.hook == "cancel" })
        }
    }

    @Test fun frameCallbacksIgnoreDuplicateLateAndWrongIdentityResults() {
        val f = Fake(listOf(tap(10), tap(20)))
        f.start()
        val first = f.calls.single()
        f.complete(first, first.token.copy(generation = first.token.generation + 1))
        f.complete(first, first.token.copy(runId = UUID.randomUUID().toString()))
        f.complete(first, first.token.copy(actionId = UUID.randomUUID().toString()))
        assertTrue(f.frames.calls.none { it.hook == "completed" })
        f.complete(first)
        f.complete(first)
        f.advance(100)
        f.complete(first)
        assertEquals(1, f.frames.calls.count { it.hook == "completed" })
        assertEquals(1, f.frames.calls.count { it.hook == "after" })
        f.engine.stop()
        val stopped = f.engine.state
        val frames = f.frames.calls.toList()
        f.complete(f.calls.last())
        f.advance(20_000)
        assertEquals(stopped, f.engine.state)
        assertEquals(frames, f.frames.calls)
        assertEquals(2, f.calls.size)
    }

    @Test fun pauseDuringGestureOrWaitClosesFrameEpochAndResumeCannotRetrofill() {
        for (pauseBeforeCompletion in listOf(true, false)) {
            val f = Fake(listOf(tap(10, wait = 100), tap(20, wait = 100)))
            f.start()
            val first = f.calls.single()
            if (pauseBeforeCompletion) f.engine.pause()
            f.complete(first)
            if (!pauseBeforeCompletion) f.engine.pause()
            f.advance(10_000)
            assertTrue(f.resume(true))
            assertEquals(2, f.calls.size)
            val second = f.calls.last()
            f.complete(first)
            f.complete(second)
            f.advance(100)
            assertEquals(ClickRunPhase.Completed, f.engine.state.phase)
            val firstFrames = f.frames.calls.filter { it.action?.actionId == first.action.actionId }
            assertEquals(if (pauseBeforeCompletion) listOf("before") else listOf("before", "completed"), firstFrames.map { it.hook })
            val secondFrames = f.frames.calls.filter { it.action?.actionId == second.action.actionId }
            assertEquals(listOf("before", "completed", "after"), secondFrames.map { it.hook })
            assertTrue(secondFrames.all { it.epoch == 1 })
            assertEquals(1, f.frames.calls.count { it.hook == "cancel" })
        }
    }

    @Test fun pauseOnLastWaitDoesNotRetrofillAfterOnExplicitResume() {
        val f = Fake(listOf(tap(10, wait = 100)))
        f.start()
        f.complete(f.calls.single())
        assertTrue(f.engine.pause())
        f.advance(1_000)
        assertTrue(f.resume(true))
        assertEquals(ClickRunPhase.Completed, f.engine.state.phase)
        assertTrue(f.frames.calls.none { it.hook == "after" })
        assertEquals(1, f.calls.size)
    }

    @Test fun unsafeNextOrFinalWindowClosesFrameEpochWithoutCapturingAfter() {
        for (lastAction in listOf(false, true)) {
            val f = Fake(if (lastAction) listOf(tap(10)) else listOf(tap(10), tap(20)))
            f.start()
            val first = f.calls.single()
            f.complete(first)
            f.guard = ClickGuard.Blocked
            f.advance(100)
            assertTrue(f.frames.calls.none { it.hook == "after" })
            assertEquals(1, f.frames.calls.count { it.hook == "cancel" })
            if (lastAction) {
                assertEquals(ClickRunPhase.Completed, f.engine.state.phase)
            } else {
                assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
                f.guard = ClickGuard.Allowed
                assertTrue(f.resume(true))
                assertTrue(f.frames.calls.none { it.hook == "after" })
                assertEquals(1, f.frames.calls.last().epoch)
                assertEquals(2, f.calls.size)
            }
        }
    }

    @Test fun stopOrRecordingLossDuringWaitCancelsAfterAndNeverDispatchesNext() {
        for (recordingLoss in listOf(false, true)) {
            val f = Fake()
            f.start()
            f.complete(f.calls.single())
            if (recordingLoss) f.recordingReady = false else f.engine.stop()
            f.advance(10_000)
            assertEquals(1, f.calls.size)
            assertTrue(f.engine.state.terminal)
            assertTrue(f.frames.calls.none { it.hook == "after" })
            assertEquals(1, f.frames.calls.count { it.hook == "cancel" })
        }
    }

    @Test fun synchronousCompletionObservesFrameBoundaryBeforeAcceptancePersistenceOnce() {
        val f = Fake(listOf(tap(10, wait = 0)))
        f.synchronousCompletion = true
        f.synchronousCallbackCount = 2
        f.start()
        val id = f.calls.single().action.actionId
        assertEquals(ClickRunPhase.Completed, f.engine.state.phase)
        assertEquals(listOf("before", "completed", "after"), f.frames.calls.map { it.hook })
        assertTrue(f.timeline.indexOf("completed:$id") < f.timeline.indexOf("write:DispatchAccepted:$id"))
        assertTrue(f.timeline.indexOf("write:DispatchAccepted:$id") < f.timeline.indexOf("write:GestureCompleted:$id"))
    }

    @Test fun failedCompletionOrAcceptancePersistenceInvalidatesProvisionalFrames() {
        for (failure in listOf(ClickRunEventType.DispatchAccepted, ClickRunEventType.GestureCompleted)) {
            val f = Fake(listOf(tap(10, wait = 0)))
            f.synchronousCompletion = true
            f.failEvent = failure
            f.start()
            assertEquals(ClickStopReason.DurabilityFailure, f.engine.state.stopReason)
            assertEquals(listOf("before", "completed", "cancel"), f.frames.calls.map { it.hook })
            val saved = f.frames.calls.toList()
            f.complete(f.calls.single())
            f.advance(10_000)
            assertEquals(saved, f.frames.calls)
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun rejectedSynchronousCompletionCannotPublishAnAfterFrame() {
        val f = Fake(listOf(tap(10, wait = 0)))
        f.synchronousCompletion = true
        f.accept = false
        f.start()
        assertEquals(ClickActionStatus.Rejected, f.engine.state.outcomes.single().status)
        assertEquals(listOf("before", "completed", "cancel"), f.frames.calls.map { it.hook })
        f.complete(f.calls.single())
        assertTrue(f.frames.calls.none { it.hook == "after" })
    }

    @Test fun frameHookReentrantPauseOrStopCannotBypassDispatchGates() {
        for (stop in listOf(false, true)) {
            val f = Fake()
            f.frames.onHook = { hook -> if (hook == "before") {
                if (stop) f.engine.stop() else f.engine.pause()
            } }
            f.start()
            assertTrue(f.calls.isEmpty())
            assertEquals(listOf("before", "cancel"), f.frames.calls.map { it.hook })
            f.frames.onHook = {}
            if (stop) {
                assertFalse(f.resume(true))
            } else {
                assertTrue(f.resume(true))
                assertEquals(1, f.calls.size)
                assertEquals(1, f.engine.state.events.count { it.type == ClickRunEventType.DispatchIntent })
                assertEquals(1, f.frames.calls.last().epoch)
            }
        }
    }

    @Test fun auxiliaryHookCannotOverwriteReentrantTerminalGestureState() {
        for (stopAt in listOf("completed", "after")) {
            val f = Fake(listOf(tap(10, wait = 0)))
            f.frames.onHook = { hook -> if (hook == stopAt) f.engine.stop() }
            f.start()
            f.complete(f.calls.single())
            assertEquals(ClickRunPhase.Stopped, f.engine.state.phase)
            assertEquals(ClickRunEventType.Stopped, f.engine.state.events.last().type)
            val stopped = f.engine.state
            f.complete(f.calls.single())
            f.advance(10_000)
            assertEquals(stopped, f.engine.state)
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun cancellationHookFailureCannotPreventPauseStopOrAtMostOnceResume() {
        val f = Fake()
        f.frames.throwHook = "cancel"
        f.start()
        assertTrue(f.engine.pause())
        f.complete(f.calls.single())
        f.advance(100)
        assertTrue(f.resume(true))
        assertEquals(2, f.calls.size)
        f.engine.stop()
        assertEquals(ClickRunPhase.Stopped, f.engine.state.phase)
        f.advance(10_000)
        assertEquals(2, f.calls.size)
        assertTrue(f.frames.calls.none { it.hook == "after" })
    }

    private class Fake(actions: List<ClickAction> = listOf(tap(10), tap(20)), initial: ClickRun = run(plan(actions))) {
        var now = 1_000L
        var guard = ClickGuard.Allowed
        var recordingReady = true
        var accept = true
        var throwDispatch = false
        var synchronousCompletion = false
        var synchronousCallbackCount = 1
        var failEvent: ClickRunEventType? = null
        var onPersist: (ClickRun) -> Unit = {}
        val timeline = mutableListOf<String>()
        val frames = FakeFrames(timeline)
        val writes = mutableListOf<ClickRun>()
        val calls = mutableListOf<Call>()
        private val jobs = mutableListOf<Pair<Long, () -> Unit>>()
        val engine = ClickRunEngine(initial,
            persist = { state ->
                timeline += "write:${state.events.last().type}:${state.events.last().actionId}"
                if (state.events.lastOrNull()?.type == failEvent) throw IOException("Injected durability failure")
                writes += state
                onPersist(state)
            },
            dispatch = { token, action, callback ->
                val durable = writes.last()
                assertTrue(durable.events.any { it.type == ClickRunEventType.DispatchIntent && it.actionId == action.actionId })
                assertEquals(ClickActionStatus.Intent, durable.outcomes[durable.nextActionIndex].status)
                assertEquals(token.runId, durable.runId)
                assertEquals(token.generation, durable.generation)
                assertEquals(token.actionId, durable.outcomes[durable.nextActionIndex].actionId)
                timeline += "dispatch:${action.actionId}"
                calls += Call(token, action, callback)
                if (throwDispatch) throw IOException("Injected platform uncertainty")
                if (synchronousCompletion) repeat(synchronousCallbackCount) { callback(token, ClickGestureResult.Completed) }
                accept
            },
            schedule = { delay, callback -> jobs += now + delay to callback },
            uptimeMs = { now }, guard = { guard }, recordingReady = { recordingReady }, frames = frames,
        )
        fun start(): Boolean = engine.start().also { advance(0) }
        fun resume(confirmed: Boolean): Boolean = engine.resume(confirmed).also { advance(0) }
        fun queue(callback: () -> Unit) { jobs += now to callback }
        fun complete(call: Call, token: ClickCallbackToken = call.token) {
            call.callback(token, ClickGestureResult.Completed)
            advance(0)
        }
        fun advance(delta: Long) {
            val until = now + delta
            while (true) {
                val next = jobs.withIndex().filter { it.value.first <= until }.minByOrNull { it.value.first } ?: break
                jobs.removeAt(next.index)
                now = next.value.first
                next.value.second()
            }
            now = until
        }
    }
    private data class FrameCall(val hook: String, val action: FrameAnchorAction?, val epoch: Int)
    private class FakeFrames(private val timeline: MutableList<String>) : FrameAnchorPort {
        val calls = mutableListOf<FrameCall>()
        var epoch = 0
        var throwHook: String? = null
        var onHook: (String) -> Unit = {}
        private fun record(hook: String, action: FrameAnchorAction? = null) {
            calls += FrameCall(hook, action, epoch)
            timeline += "$hook:${action?.actionId}"
            onHook(hook)
            if (throwHook == hook) throw IOException("Injected auxiliary frame failure")
        }
        override fun before(action: FrameAnchorAction): AnchorResult {
            record("before", action)
            return AnchorResult.Missing(FrameMissingReason.NoFrame)
        }
        override fun markGestureCompleted(action: FrameAnchorAction) { record("completed", action) }
        override fun afterWait(action: FrameAnchorAction): AnchorResult {
            record("after", action)
            return AnchorResult.Missing(FrameMissingReason.NoNewFrame)
        }
        override fun cancelEpoch(reason: FrameMissingReason) {
            record("cancel")
            epoch++
        }
    }
    private data class Call(val token: ClickCallbackToken, val action: ClickAction, val callback: (ClickCallbackToken, ClickGestureResult) -> Unit)

    companion object {
        private fun tap(x: Int, wait: Long = 100) = ClickAction(x = x, y = 10, waitAfterMs = wait)
        private fun plan(actions: List<ClickAction>) = ClickPlan.create(UUID.randomUUID().toString(), "com.example.target", 100, 200, 0, actions)
        private fun run(plan: ClickPlan) = ClickRun.create(plan, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 100)
        private fun expectFailure(block: () -> Unit) {
            var failed = false
            try { block() } catch (_: Exception) { failed = true }
            assertTrue("Expected a rejected operation", failed)
        }
    }
}
