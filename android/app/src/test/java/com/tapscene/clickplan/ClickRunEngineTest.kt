package com.tapscene.clickplan

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

    private class Fake(actions: List<ClickAction> = listOf(tap(10), tap(20)), initial: ClickRun = run(plan(actions))) {
        var now = 1_000L
        var guard = ClickGuard.Allowed
        var recordingReady = true
        var accept = true
        var throwDispatch = false
        var synchronousCompletion = false
        var failEvent: ClickRunEventType? = null
        var onPersist: (ClickRun) -> Unit = {}
        val writes = mutableListOf<ClickRun>()
        val calls = mutableListOf<Call>()
        private val jobs = mutableListOf<Pair<Long, () -> Unit>>()
        val engine = ClickRunEngine(initial,
            persist = { state ->
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
                calls += Call(token, action, callback)
                if (throwDispatch) throw IOException("Injected platform uncertainty")
                if (synchronousCompletion) callback(token, ClickGestureResult.Completed)
                accept
            },
            schedule = { delay, callback -> jobs += now + delay to callback },
            uptimeMs = { now }, guard = { guard }, recordingReady = { recordingReady },
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
