package com.tapscene.recording

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FrameBoundaryWindowTest {
    private val action = FrameAnchorAction(id(), id(), id(), id(), 1)
    private fun window() = FrameBoundaryWindow(action.sessionId, action.sourceId)

    @Test fun missingBeforeDoesNotSuppressRealCompletionAndAfterSelection() {
        val window = window()
        assertNull(window.begin(action, FrameBoundary.Before))
        val missing = AnchorResult.Missing(FrameMissingReason.QueueFull)
        window.retain(action, FrameBoundary.Before, missing)
        window.complete(action, 10)
        window.complete(action, 20) // a duplicate callback cannot move the observed boundary
        assertEquals(10L, window.afterLowerBound(action))
        assertNull(window.begin(action, FrameBoundary.After))
        window.retain(action, FrameBoundary.After, AnchorResult.Missing(FrameMissingReason.NoNewFrame))
        window.complete(action, 30)
        assertEquals(AnchorResult.Missing(FrameMissingReason.NoNewFrame), window.begin(action, FrameBoundary.After))
        assertEquals(missing, window.begin(action, FrameBoundary.Before))
    }
    @Test fun cancelledEpochCannotReuseOldSelectionOrBackfillCompletion() {
        val window = window()
        window.begin(action, FrameBoundary.Before)
        window.retain(action, FrameBoundary.Before, AnchorResult.Missing(FrameMissingReason.NoFrame))
        window.complete(action, 4)
        window.cancel()
        assertEquals(AnchorResult.Missing(FrameMissingReason.ClosedEpoch), window.begin(action, FrameBoundary.Before))
        window.complete(action, 9)
        assertNull(window.afterLowerBound(action))
        val next = action.copy(actionId = id())
        assertNull(window.begin(next, FrameBoundary.Before))
        window.complete(next, 11)
        assertEquals(11L, window.afterLowerBound(next))
    }
    @Test fun anotherRunGenerationOrSourceCannotClaimCurrentBoundary() {
        val window = window()
        window.begin(action, FrameBoundary.Before)
        listOf(action.copy(runId = id()), action.copy(generation = 2), action.copy(sessionId = id()), action.copy(sourceId = id())).forEach {
            assertEquals(AnchorResult.Missing(FrameMissingReason.ClosedEpoch), window.begin(it, FrameBoundary.Before))
            window.complete(it, 99)
            assertNull(window.afterLowerBound(it))
        }
    }
    companion object { private fun id() = UUID.randomUUID().toString() }
}
