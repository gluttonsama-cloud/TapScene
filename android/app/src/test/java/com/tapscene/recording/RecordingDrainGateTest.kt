package com.tapscene.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingDrainGateTest {
    @Test fun unsolicitedEndIsFaultEvenBeforeFirstReadinessOrWithFinalData() {
        // The backend checks this before looking up/writing data or updating readiness.
        listOf(false, true).forEach { hasData ->
            val gate = RecordingDrainGate()
            assertEquals("hasData=$hasData", RecordingDrainGate.End.Unexpected, gate.observedEnd(false))
            assertFalse(gate.mayFinishAfterEnd())
        }
    }
    @Test fun captureClosureAndStopRequestDoNotReleaseTheStillOwnedGlSurface() {
        val gate = RecordingDrainGate()
        assertEquals(RecordingDrainGate.End.WaitForGl, gate.observedEnd(true))
        assertFalse(gate.glTeardownReturned)
        assertFalse(gate.mayFinishAfterEnd())
        assertEquals(RecordingDrainGate.Teardown.FinishCodec, gate.glReturned(true))
        assertTrue(gate.mayFinishAfterEnd())
    }
    @Test fun failedGlCleanupStillPermitsCodecCleanupWithoutPretendingAllResourcesReleased() {
        val gate = RecordingDrainGate()
        assertEquals(RecordingDrainGate.Teardown.FinishCodec, gate.glReturned(false))
        assertTrue(gate.glTeardownReturned)
        assertFalse(gate.eosSeen) // resource release and successful EOS remain separate facts
        assertThrows(IllegalStateException::class.java) { gate.glReturned(false) }
    }
    @Test fun normalLateEosCanFinishOnlyAfterTeardownAndSignal() {
        val gate = RecordingDrainGate()
        assertEquals(RecordingDrainGate.Teardown.SignalEos, gate.glReturned(true))
        assertFalse(gate.mayFinishAfterEnd())
        assertEquals(RecordingDrainGate.End.FinishCodec, gate.observedEnd(true))
        assertTrue(gate.mayFinishAfterEnd())
    }
}
