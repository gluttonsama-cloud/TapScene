package com.tapscene.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingFrameLedgerTest {
    @Test fun sourceDeltasAndExactMuxMappingNeverGuess() {
        val ledger = RecordingFrameLedger()
        // Completion while updateTexImage is in flight already sees this reservation.
        val reserved = ledger.reserveCaptureSequence()
        val completedAt = ledger.captureSequence()
        val first = (ledger.source(9_000_000_001, reserved) as SourceTimestampResult.Accepted).frame
        assertTrue(first.sequence <= completedAt) // never reclassified as an after frame
        assertThrows(IllegalStateException::class.java) { ledger.source(9_000_000_002, reserved) }
        val second = (ledger.source(9_033_333_667) as SourceTimestampResult.Accepted).frame
        assertEquals(0, first.submittedPtsUs)
        assertEquals(33_333, second.submittedPtsUs)
        assertEquals(first, ledger.muxed(0).source)
        assertNull(ledger.muxed(33_332).source)
        assertEquals(second, ledger.muxed(33_333).source)
        assertTrue(ledger.verifyContainer(listOf(0, 33_330, 33_330)))
        assertFalse(ledger.verifyContainer(listOf(0, 33_330)))
        assertFalse(ledger.verifyContainer(listOf(0, 33_330, 30_000)))
    }
    @Test fun repeatedBackwardsAndQuantizedTimesAreExplicitSkips() {
        val ledger = RecordingFrameLedger()
        ledger.source(1_000_000)
        assertEquals(SourceTimestampSkip.RepeatedOrBackwards, (ledger.source(1_000_000) as SourceTimestampResult.Skipped).reason)
        assertEquals(SourceTimestampSkip.RepeatedOrBackwards, (ledger.source(999_999) as SourceTimestampResult.Skipped).reason)
        assertEquals(SourceTimestampSkip.QuantizationCollision, (ledger.source(1_000_999) as SourceTimestampResult.Skipped).reason)
        assertEquals(1, (ledger.source(1_001_001) as SourceTimestampResult.Accepted).frame.submittedPtsUs)
        val raced = RecordingFrameLedger()
        raced.source(1_000_000)
        val observedBeforeMuxWrite = raced.observeOutput(50_000)
        // Native write may block while GL submits a new frame with the previously unknown PTS.
        raced.source(51_000_000)
        assertNull(raced.muxed(observedBeforeMuxWrite).source)
        assertNull(raced.matchSource(2))
    }
    @Test fun duplicateAndReorderedCodecOutputsFailInsteadOfSorting() {
        val ledger = RecordingFrameLedger()
        ledger.source(1_000_000); ledger.muxed(10)
        assertThrows(IllegalStateException::class.java) { ledger.muxed(10) }
        assertThrows(IllegalStateException::class.java) { ledger.muxed(9) }
        assertEquals(1, ledger.samples().size)
    }
}
