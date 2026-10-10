package com.tapscene.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingClickGateTest {
    @Test fun cancellingNewerRequestNeverRevivesQueuedOlderStart() {
        val gate = RecordingClickGate()
        gate.issue("A"); gate.cancel("A")
        gate.issue("B"); gate.cancel("B")
        assertFalse(gate.permits("A")); assertFalse(gate.permits("B"))
        gate.issue("C"); gate.cancel("A")
        assertTrue(gate.permits("C")); assertFalse(gate.permits("A"))
        gate.issue("D")
        assertFalse(gate.permits("C")); assertTrue(gate.permits("D"))
    }
}
