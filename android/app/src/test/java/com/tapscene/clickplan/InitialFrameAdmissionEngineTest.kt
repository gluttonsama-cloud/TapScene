package com.tapscene.clickplan

import com.tapscene.recording.AnchorResult
import com.tapscene.recording.FrameAnchorAction
import com.tapscene.recording.FrameAnchorPort
import com.tapscene.recording.FrameBoundary
import com.tapscene.recording.FrameFreshness
import com.tapscene.recording.FrameGeometry
import com.tapscene.recording.FrameMissingReason
import com.tapscene.recording.FrameTicket
import com.tapscene.recording.InitialFrameAdmissionMetadata
import com.tapscene.recording.InitialFrameAdmissionHandle
import com.tapscene.recording.InitialFrameAdmissionPort
import com.tapscene.recording.InitialFrameAdmissionStatus
import com.tapscene.recording.InitialFrameAuthorization
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class InitialFrameAdmissionEngineTest {
    @Test fun pendingLeavesReadyWithoutStartedIntentOrGestureAndNeverBeginsTwice() {
        val f = Fixture()
        assertTrue(f.engine.start())
        f.drain()
        assertFalse(f.engine.start())
        assertEquals(1, f.requests.size)
        assertEquals(ClickRunPhase.Ready, f.engine.state.phase)
        assertTrue(f.writes.isEmpty())
        assertTrue(f.calls.isEmpty())
        assertEquals(1, f.preparing)
        f.requests.single().signal()
        f.drain()
        assertTrue(f.writes.isEmpty())
        assertTrue(f.calls.isEmpty())
    }

    @Test fun preparationPredicatesAndBeginCannotReopenAReentrantlyCancelledRun() {
        for (boundary in listOf("guard", "recording", "begin")) {
            for (stop in listOf(false, true)) {
                val f = Fixture()
                val cancel = { if (stop) f.engine.stop() else { f.engine.pause(); Unit } }
                when (boundary) {
                    "guard" -> f.onReadGuard = cancel
                    "recording" -> f.onReadRecording = cancel
                    else -> f.onBegin = cancel
                }
                assertFalse(f.engine.start())
                f.drain()
                assertEquals(if (stop) ClickRunPhase.Stopped else ClickRunPhase.Paused, f.engine.state.phase)
                assertTrue(f.calls.isEmpty())
                assertEquals(0, f.count(ClickRunEventType.Started))
                if (boundary == "begin") assertEquals(1, f.requests.single().cancelCount)
                else assertTrue(f.requests.isEmpty())
            }
        }
    }

    @Test fun synchronousForeignThreadAndDuplicateNotificationsOnlyEnqueueOneStartAndOneCall() {
        val f = Fixture()
        f.beginStatus = InitialFrameAdmissionStatus.Ready
        f.notifyInsideBegin = true
        assertTrue(f.engine.start())
        assertTrue(f.writes.isEmpty()) // A synchronous begin callback must not enter the engine.
        val request = f.requests.single()
        val worker = Thread { repeat(20) { request.signal() } }
        worker.start(); worker.join()
        assertTrue(f.writes.isEmpty())
        f.drain()
        repeat(20) { request.signal() }
        f.drain()
        assertEquals(1, f.count(ClickRunEventType.Started))
        assertEquals(1, f.count(ClickRunEventType.DispatchIntent))
        assertEquals(1, request.consumeCount)
        assertEquals(1, f.calls.size)
        assertEquals(listOf("Started", "DispatchIntent", "consume", "dispatch", "DispatchAccepted"), f.trace)
        assertNotNull(f.armAtDispatch)
    }

    @Test fun intentFsyncStillYieldsForUnsafeWindowStopGeometryAndRecordingEvents() {
        for (ending in listOf("unknown", "blocked", "stop", "geometry", "recording")) {
            val f = Fixture()
            f.onPersist = { run ->
                if (run.events.last().type == ClickRunEventType.DispatchIntent) f.queue {
                    when (ending) {
                        "unknown" -> { f.guard = ClickGuard.Unknown; f.engine.pause() }
                        "blocked" -> { f.guard = ClickGuard.Blocked; f.engine.pause() }
                        "stop" -> f.engine.stop()
                        "geometry" -> f.engine.stop(ClickStopReason.DisplayChanged)
                        else -> { f.recording = false; f.engine.stop(ClickStopReason.RecordingLost) }
                    }
                }
            }
            f.startReady()
            assertTrue(ending, f.calls.isEmpty())
            assertEquals(0, f.requests.single().consumeCount)
            assertEquals(1, f.count(ClickRunEventType.Started))
            assertEquals(1, f.count(ClickRunEventType.DispatchIntent))
            assertEquals(1, f.requests.single().cancelCount)
        }
    }

    @Test fun timeoutDoesNotRenewAuthorizationAndExplicitResumeRequiresANewArmAndEpoch() {
        val f = Fixture()
        f.engine.start(); f.drain()
        val expired = f.requests.single()
        f.advance(15_000)
        assertEquals(ClickRunPhase.Ready, f.engine.state.phase) // Deadline itself is inclusive.
        f.advance(1)
        assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
        assertTrue(f.calls.isEmpty())
        assertEquals(1, expired.cancelCount)
        assertFalse(f.engine.resume(false))
        assertFalse(f.engine.resume(true)) // The old confirmation has no remaining authority.
        f.renewArm()
        assertTrue(f.engine.resume(true))
        val fresh = f.requests.last()
        assertNotEquals(expired.authorization.confirmationId, fresh.authorization.confirmationId)
        assertTrue(fresh.epoch > expired.epoch)
        expired.ready(); f.drain()
        assertTrue(f.calls.isEmpty())
        fresh.ready(); f.drain()
        assertEquals(1, f.calls.size)
        assertEquals(1, f.count(ClickRunEventType.Started))
        assertEquals(1, f.count(ClickRunEventType.DispatchIntent))
    }

    @Test fun pauseAfterIntentKeepsTheSameUncalledTokenAndResumeDoesNotRewriteStartedOrIntent() {
        val f = Fixture()
        f.onPersist = { run ->
            if (run.events.last().type == ClickRunEventType.DispatchIntent) f.queue { f.engine.pause() }
        }
        f.startReady()
        val old = f.requests.single()
        val firstIntent = f.engine.state.events.single { it.type == ClickRunEventType.DispatchIntent }
        assertEquals(ClickActionStatus.Intent, f.engine.state.outcomes.first().status)
        assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
        f.onPersist = {}
        f.renewArm()
        assertTrue(f.engine.resume(true))
        old.ready(); f.drain()
        assertTrue(f.calls.isEmpty())
        assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
        f.requests.last().ready(); f.drain()
        assertEquals(1, f.count(ClickRunEventType.Started))
        assertEquals(1, f.count(ClickRunEventType.Resumed))
        assertEquals(1, f.count(ClickRunEventType.DispatchIntent))
        assertEquals(firstIntent, f.engine.state.events.single { it.type == ClickRunEventType.DispatchIntent })
        assertEquals(ClickCallbackToken(f.engine.state.runId, firstIntent.actionId!!, f.engine.state.generation), f.calls.single().token)
        assertEquals(1, f.requests.last().consumeCount)
    }

    @Test fun waitingAgainAfterIntentKeepsOneIntentUntilTheSameHandleBecomesReady() {
        val f = Fixture()
        f.onPersist = { run ->
            if (run.events.last().type == ClickRunEventType.DispatchIntent) f.requests.single().current = InitialFrameAdmissionStatus.Pending
        }
        f.startReady()
        assertTrue(f.calls.isEmpty())
        assertEquals(ClickRunPhase.Running, f.engine.state.phase)
        assertEquals(ClickActionStatus.Intent, f.engine.state.outcomes.first().status)
        repeat(10) { f.requests.single().signal() }; f.drain()
        assertEquals(1, f.requests.size)
        assertEquals(1, f.count(ClickRunEventType.DispatchIntent))
        f.requests.single().ready(); f.drain()
        assertEquals(1, f.calls.size)
        assertEquals(1, f.count(ClickRunEventType.Started))
        assertEquals(1, f.count(ClickRunEventType.DispatchIntent))
    }

    @Test fun pendingGuardFailureStopAndRecordingLossCancelAndIgnoreLateReady() {
        for (ending in listOf("unknown", "blocked", "stop", "recording")) {
            val f = Fixture()
            f.engine.start(); f.drain()
            val request = f.requests.single()
            when (ending) {
                "unknown" -> f.guard = ClickGuard.Unknown
                "blocked" -> f.guard = ClickGuard.Blocked
                "stop" -> f.engine.stop()
                else -> f.recording = false
            }
            request.ready(); f.drain()
            assertTrue(ending, f.calls.isEmpty())
            assertEquals(0, request.consumeCount)
            assertEquals(1, request.cancelCount)
            assertEquals(0, f.count(ClickRunEventType.Started))
            assertEquals(0, f.count(ClickRunEventType.DispatchIntent))
            repeat(4) { request.signal() }; f.drain()
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun changedConfirmationAndExpiredFsyncCannotConsumeAnOtherwiseReadyHandle() {
        for (change in listOf("identity", "deadline")) {
            val f = Fixture()
            f.onPersist = { run ->
                if (run.events.last().type == ClickRunEventType.DispatchIntent) {
                    if (change == "identity") f.renewArm() else f.now += 15_001
                }
            }
            f.startReady()
            assertTrue(change, f.calls.isEmpty())
            assertEquals(0, f.requests.single().consumeCount)
            assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
        }
    }

    @Test fun consumeReentrantCancellationOrLastMomentDeadlineEndsRunAndCannotReopenBoundary() {
        for (ending in listOf("pause", "stop", "guard", "deadline", "recording", "confirmation")) {
            val f = Fixture()
            f.onConsume = {
                when (ending) {
                    "pause" -> f.engine.pause()
                    "stop" -> f.engine.stop()
                    "guard" -> f.guard = ClickGuard.Unknown
                    "deadline" -> f.now += 15_001
                    "recording" -> f.recording = false
                    else -> f.renewArm()
                }
            }
            f.startReady()
            assertTrue(ending, f.calls.isEmpty())
            assertTrue(ending, f.engine.state.terminal)
            assertEquals(1, f.requests.single().consumeCount)
            f.renewArm()
            assertFalse(f.engine.resume(true))
            f.requests.single().signal(); f.drain()
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun consumeMissingThrowOrWrongFullTicketIdentityCannotReachPlatform() {
        for (failure in listOf("missing", "throw", "run", "action", "session", "source", "generation", "after")) {
            val f = Fixture()
            f.consumeResult = { request ->
                if (failure == "throw") throw IOException("consume")
                if (failure == "missing") AnchorResult.Missing(FrameMissingReason.PngFailed)
                else {
                    val action = when (failure) {
                        "run" -> request.action.copy(runId = id())
                        "action" -> request.action.copy(actionId = id())
                        "session" -> request.action.copy(sessionId = id())
                        "source" -> request.action.copy(sourceId = id())
                        "generation" -> request.action.copy(generation = request.action.generation + 1)
                        else -> request.action
                    }
                    AnchorResult.Ticket(ticket(action, if (failure == "after") FrameBoundary.After else FrameBoundary.Before,
                        if (failure == "after") null else request.metadata()))
                }
            }
            f.startReady()
            assertTrue(failure, f.calls.isEmpty())
            assertTrue(f.engine.state.terminal)
            assertFalse(f.engine.resume(true))
        }
    }

    @Test fun absentOrDifferentConfirmationMetadataCannotAuthorizeTheFirstGesture() {
        for (failure in listOf("absent", "confirmedAt", "deadline")) {
            val f = Fixture()
            f.consumeResult = { request ->
                val metadata = request.metadata()
                val changed = when (failure) {
                    "absent" -> null
                    "confirmedAt" -> metadata.copy(confirmedAtUptimeMs = metadata.confirmedAtUptimeMs + 1,
                        selectedAtUptimeMs = metadata.selectedAtUptimeMs + 1, consumedAtUptimeMs = metadata.consumedAtUptimeMs + 1)
                    else -> metadata.copy(deadlineUptimeMs = metadata.deadlineUptimeMs - 1)
                }
                AnchorResult.Ticket(ticket(request.action, admission = changed))
            }
            f.startReady()
            assertTrue(failure, f.calls.isEmpty())
            assertTrue(f.engine.state.terminal)
            assertEquals(1, f.requests.single().consumeCount)
            assertFalse(f.engine.resume(true))
        }
    }

    @Test fun unavailableAdmissionIsNotAnOptionalFramePortFallback() {
        val f = Fixture()
        f.beginStatus = InitialFrameAdmissionStatus.Unavailable
        f.engine.start(); f.drain()
        assertEquals(ClickRunPhase.Paused, f.engine.state.phase)
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.beforeCount)
        assertEquals(0, f.count(ClickRunEventType.DispatchIntent))
    }

    @Test fun synchronousGestureCompletionAndAllLaterBeforeAfterAndFinalWaitAreUnchanged() {
        val f = Fixture(listOf(tap(10, 700), tap(20, 900)))
        f.synchronousCompletion = true
        f.engine.start(); f.advance(2_000)
        f.requests.single().ready(); f.drain()
        assertEquals(1, f.calls.size)
        assertEquals(0, f.beforeCount) // First boundary is the consumed admission snapshot.
        assertEquals(1, f.completedCount)
        f.advance(699)
        assertEquals(1, f.calls.size)
        f.advance(1)
        assertEquals(2, f.calls.size)
        assertEquals(1, f.beforeCount)
        assertEquals(2, f.completedCount)
        assertEquals(1, f.afterCount)
        assertEquals(1, f.requests.size)
        f.advance(899)
        assertEquals(ClickRunPhase.Running, f.engine.state.phase)
        f.advance(1)
        assertEquals(ClickRunPhase.Completed, f.engine.state.phase)
        assertEquals(2, f.afterCount)
        assertEquals(1, f.count(ClickRunEventType.Started))
        assertEquals(2, f.count(ClickRunEventType.DispatchIntent))
        assertTrue(f.trace.indexOf("DispatchAccepted") < f.trace.indexOf("GestureCompleted"))
    }

    private class Fixture(actions: List<ClickAction> = listOf(tap(10, 300), tap(20, 400))) {
        var now = 1_000L
        var arm = ClickTargetArm(now)
        var armAtDispatch: InitialFrameAuthorization? = null
        var guard = ClickGuard.Allowed
        var recording = true
        var beginStatus = InitialFrameAdmissionStatus.Pending
        var notifyInsideBegin = false
        var synchronousCompletion = false
        var preparing = 0
        var epoch = 0
        var beforeCount = 0
        var afterCount = 0
        var completedCount = 0
        var onPersist: (ClickRun) -> Unit = {}
        var onConsume: () -> Unit = {}
        var onReadGuard: () -> Unit = {}
        var onReadRecording: () -> Unit = {}
        var onBegin: () -> Unit = {}
        var consumeResult: (Request) -> AnchorResult = { AnchorResult.Ticket(ticket(it.action, admission = it.metadata())) }
        val requests = mutableListOf<Request>()
        val writes = mutableListOf<ClickRun>()
        val calls = mutableListOf<Call>()
        val trace = mutableListOf<String>()
        private val jobs = mutableListOf<Pair<Long, () -> Unit>>()
        private val initial = ClickRun.create(ClickPlan.create(id(), "com.example.target", 100, 200, 0, actions), id(), id(), 1, 100)
        val engine = ClickRunEngine(initial,
            persist = { writes += it; trace += it.events.last().type.name; onPersist(it) },
            dispatch = { token, action, callback ->
                assertEquals(ClickActionStatus.Intent, writes.last().outcomes[writes.last().nextActionIndex].status)
                armAtDispatch = arm.authorizationAt(now)
                trace += "dispatch"
                calls += Call(token, action)
                if (synchronousCompletion) repeat(2) { callback(token, ClickGestureResult.Completed) }
                true
            },
            schedule = { delay, callback -> synchronized(jobs) { jobs += now + delay to callback } },
            uptimeMs = { now }, guard = { onReadGuard(); guard }, recordingReady = { onReadRecording(); recording },
            frames = object : FrameAnchorPort {
                override fun before(action: FrameAnchorAction): AnchorResult { beforeCount++; return AnchorResult.Missing(FrameMissingReason.NoFrame) }
                override fun markGestureCompleted(action: FrameAnchorAction) { completedCount++ }
                override fun afterWait(action: FrameAnchorAction): AnchorResult { afterCount++; return AnchorResult.Missing(FrameMissingReason.NoNewFrame) }
                override fun cancelEpoch(reason: FrameMissingReason) { epoch++ }
            },
            initialFrames = object : InitialFrameAdmissionPort {
                override fun begin(action: FrameAnchorAction, authorization: InitialFrameAuthorization, onChanged: () -> Unit): InitialFrameAdmissionHandle {
                    val request = Request(action, authorization, epoch, onChanged, this@Fixture)
                    requests += request
                    onBegin()
                    if (notifyInsideBegin) repeat(3) { onChanged() }
                    return request
                }
            },
            initialAuthorization = { arm.authorizationAt(now) }, onPreparingInitialFrame = { preparing++ },
        )
        fun count(event: ClickRunEventType) = engine.state.events.count { it.type == event }
        fun renewArm() { arm = ClickTargetArm(now) }
        fun startReady() { engine.start(); requests.single().ready(); drain() }
        fun queue(callback: () -> Unit) { synchronized(jobs) { jobs += now to callback } }
        fun drain() = advance(0)
        fun advance(delta: Long) {
            val until = now + delta
            while (true) {
                val next = synchronized(jobs) {
                    val index = jobs.withIndex().filter { it.value.first <= maxOf(until, now) }.minByOrNull { it.value.first }?.index
                    index?.let { jobs.removeAt(it) }
                } ?: break
                now = maxOf(now, next.first) // Persistence/consume may advance the monotonic clock.
                next.second()
            }
            now = maxOf(now, until)
        }
    }

    private class Request(val action: FrameAnchorAction, val authorization: InitialFrameAuthorization, val epoch: Int,
        private val changed: () -> Unit, private val fixture: Fixture) : InitialFrameAdmissionHandle {
        var current = fixture.beginStatus
        private val selectedAtUptimeMs = fixture.now
        fun metadata() = InitialFrameAdmissionMetadata(authorization.confirmedAtUptimeMs, authorization.deadlineUptimeMs,
            selectedAtUptimeMs, 1, 1, fixture.now)
        var consumeCount = 0
        var cancelCount = 0
        override val status get() = current
        fun signal() = changed()
        fun ready() { current = InitialFrameAdmissionStatus.Ready; signal() }
        override fun consume(): AnchorResult {
            check(current == InitialFrameAdmissionStatus.Ready)
            consumeCount++
            current = InitialFrameAdmissionStatus.Consumed
            fixture.trace += "consume"
            fixture.onConsume()
            return fixture.consumeResult(this)
        }
        override fun cancel() { cancelCount++; if (current != InitialFrameAdmissionStatus.Consumed) current = InitialFrameAdmissionStatus.Cancelled }
    }
    private data class Call(val token: ClickCallbackToken, val action: ClickAction)

    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun tap(x: Int, wait: Long) = ClickAction(x = x, y = 10, waitAfterMs = wait)
        private fun ticket(action: FrameAnchorAction, boundary: FrameBoundary = FrameBoundary.Before,
            admission: InitialFrameAdmissionMetadata? = null) = FrameTicket(
            id(), action, boundary, 0, 1, 1, 1, 1, FrameGeometry.fitCenter(100, 200, 100, 200), FrameFreshness.Fresh, admission)
    }
}
