package com.tapscene.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingPresentationLedgerTest {
    private val observation = VideoSourceObservation(17, 41, 9_000_000_001)

    private fun presentation(id: Long, ptsUs: Long, source: VideoSourceObservation = observation,
        repeated: Boolean = id != 1L) = VideoPresentationSample(id, source, ptsUs, repeated)

    private fun RecordingPresentationLedger.write(ptsUs: Long): MuxedFrame = muxed(outputSeen(ptsUs))

    @Test fun evidenceRequiresPlanSuccessfulSubmissionAndExactSuccessfulWrite() {
        val ledger = RecordingPresentationLedger()
        val frame = ledger.planned(presentation(1, 500))
        assertEquals(41L, frame.sequence)
        assertEquals(9_000_000_001, frame.timestampNs)
        assertEquals(500L, frame.submittedPtsUs)
        assertNull(ledger.canonicalForSource(41))
        assertNull(ledger.matchSource(41))
        assertFalse(ledger.hasCanonicalMatch())
        ledger.submitted(1)
        assertSame(frame, ledger.canonicalForSource(41))
        assertNull(ledger.canonicalForSource(42))
        assertNull(ledger.matchSource(41))
        assertFalse(ledger.hasCanonicalMatch())
        assertNull(ledger.write(499).source) // No nearest sample or source-timestamp conversion.
        assertNull(ledger.matchSource(41))
        val written = ledger.write(500)
        assertEquals(MuxedFrame(1, 500, frame), written)
        assertEquals(written, ledger.matchSource(41))
        assertTrue(ledger.hasCanonicalMatch())
        assertNull(ledger.matchSource(observation.sourceFrameId))
    }

    @Test fun codecWriteBeforeEglReturnIsJoinedWhenSubmissionArrives() {
        val ledger = RecordingPresentationLedger()
        val frame = ledger.planned(presentation(1, 0))
        val output = ledger.outputSeen(0)
        assertEquals(0L, output.encoderPtsUs)
        assertTrue(ledger.samples().isEmpty())
        val earlySnapshot = ledger.muxed(output)
        assertNull(earlySnapshot.source)
        assertNull(ledger.matchSource(41))
        assertFalse(ledger.hasCanonicalMatch())
        ledger.submitted(1)
        assertEquals(MuxedFrame(0, 0, frame), ledger.matchSource(41))
        assertEquals(frame, ledger.samples().single().source)
        assertTrue(ledger.hasCanonicalMatch())
        assertNull(earlySnapshot.source) // Values already handed to callers remain snapshots.
    }

    @Test fun writtenBeforeAnyPlanRemainsPermanentlyUnknown() {
        val ledger = RecordingPresentationLedger()
        assertNull(ledger.write(700).source)
        val frame = ledger.planned(presentation(1, 700))
        assertNull(ledger.matchSource(41))
        ledger.submitted(1)
        assertSame(frame, ledger.canonicalForSource(41))
        assertNull(ledger.matchSource(41))
        assertNull(ledger.samples().single().source)
        assertFalse(ledger.hasCanonicalMatch())
        assertTrue(ledger.verifyContainer(listOf(700)))
    }

    @Test fun rewrittenFuturePtsCannotBeClaimedByALaterObservedFrame() {
        val ledger = RecordingPresentationLedger()
        ledger.planned(presentation(1, 0))
        ledger.submitted(1)
        assertNull(ledger.write(40_000).source) // Output for the first input has an unknown PTS.
        val next = observation.copy(sourceFrameId = 18, captureSequence = 42, sourceTimestampNs = 9_000_000_002)
        val nextFrame = ledger.planned(presentation(2, 40_000, next, repeated = false))
        ledger.submitted(2)
        assertSame(nextFrame, ledger.canonicalForSource(42))
        assertNull(ledger.matchSource(41))
        assertNull(ledger.matchSource(42))
        assertFalse(ledger.hasCanonicalMatch())
        assertEquals(listOf(MuxedFrame(0, 40_000, null)), ledger.samples())
        // A later successful repeat still cannot replace the second observation's canonical.
        assertSame(nextFrame, ledger.planned(presentation(3, 80_000, next)))
        ledger.submitted(3)
        assertNull(ledger.write(80_000).source)
        assertNull(ledger.matchSource(42))
        assertFalse(ledger.hasCanonicalMatch())
        assertEquals(listOf(0L, 1L), ledger.samples().map { it.ordinal })
        assertTrue(ledger.verifyContainer(listOf(40_000, 80_000)))
    }

    @Test fun planAddedDuringNativeMuxWriteCannotClaimPreviouslyUnknownOutput() {
        val ledger = RecordingPresentationLedger()
        ledger.planned(presentation(1, 0))
        ledger.submitted(1)
        val output = ledger.outputSeen(40_000)
        assertTrue(ledger.samples().isEmpty())
        assertNull(ledger.lastWrittenPtsUs())
        // Native write is now in progress; the GL thread presents another real observation.
        val next = observation.copy(sourceFrameId = 18, captureSequence = 42, sourceTimestampNs = 9_000_000_002)
        ledger.planned(presentation(2, 40_000, next, repeated = false))
        ledger.submitted(2)
        assertFalse(ledger.hasCanonicalMatch())
        assertEquals(MuxedFrame(0, 40_000, null), ledger.muxed(output))
        assertNull(ledger.matchSource(41))
        assertNull(ledger.matchSource(42))
        assertNull(ledger.samples().single().source)
        assertFalse(ledger.hasCanonicalMatch())
    }

    @Test fun seenOutputWithoutSuccessfulWriteCannotCreateOrdinalOrReadiness() {
        val ledger = RecordingPresentationLedger()
        ledger.planned(presentation(1, 0))
        ledger.submitted(1)
        ledger.outputSeen(0) // getOutputBuffer/writeSampleData fails; never call muxed(token).
        assertTrue(ledger.samples().isEmpty())
        assertNull(ledger.lastWrittenPtsUs())
        assertNull(ledger.matchSource(41))
        assertFalse(ledger.hasCanonicalMatch())
        assertFalse(ledger.verifyContainer(listOf(0)))
    }

    @Test fun foreignForgedReusedAndReorderedOutputTokensAreRejected() {
        val ledger = RecordingPresentationLedger()
        val other = RecordingPresentationLedger()
        val first = ledger.outputSeen(10)
        val later = ledger.outputSeen(11)
        val foreign = other.outputSeen(10)
        assertThrows(IllegalStateException::class.java) { ledger.muxed(foreign) }
        assertThrows(IllegalStateException::class.java) { ledger.muxed(RecordingPresentationLedger.OutputToken(10)) }
        assertEquals(MuxedFrame(0, 11, null), ledger.muxed(later))
        assertThrows(IllegalStateException::class.java) { ledger.muxed(first) }
        assertThrows(IllegalStateException::class.java) { ledger.muxed(later) }
        assertEquals(listOf(MuxedFrame(0, 11, null)), ledger.samples())
    }

    @Test fun canonicalUnwrittenSampleIsNeverReplacedBySuccessfulRepeats() {
        val ledger = RecordingPresentationLedger()
        val first = ledger.planned(presentation(1, 0))
        ledger.submitted(1)
        for (id in 2L..5L) {
            assertSame(first, ledger.planned(presentation(id, (id - 1) * 40_000)))
            ledger.submitted(id)
            assertNull(ledger.write((id - 1) * 40_000).source)
        }
        assertEquals(0L, first.submittedPtsUs)
        assertNull(ledger.matchSource(41))
        assertFalse(ledger.hasCanonicalMatch())
        assertEquals(listOf(0L, 1L, 2L, 3L), ledger.samples().map { it.ordinal })
        assertTrue(ledger.verifyContainer(listOf(40_000, 80_000, 120_000, 160_000)))
    }

    @Test fun canonicalFailedSubmissionCannotBeRescuedByWrittenRepeats() {
        val ledger = RecordingPresentationLedger()
        val first = ledger.planned(presentation(1, 0))
        ledger.write(0)
        assertSame(first, ledger.planned(presentation(2, 40_000)))
        ledger.submitted(2)
        assertNull(ledger.write(40_000).source)
        assertNull(ledger.canonicalForSource(41))
        assertNull(ledger.matchSource(41))
        assertFalse(ledger.hasCanonicalMatch())
        // Only the original successful EGL return can establish that original association.
        ledger.submitted(1)
        assertSame(first, ledger.canonicalForSource(41))
        assertEquals(MuxedFrame(0, 0, first), ledger.matchSource(41))
        assertNull(ledger.samples()[1].source)
    }

    @Test fun repeatsCountAsVideoSamplesButKeepSourceIdentityAndCanonicalOrdinal() {
        val ledger = RecordingPresentationLedger()
        val first = ledger.planned(presentation(1, 0))
        ledger.submitted(1)
        val canonical = ledger.write(0)
        for (id in 2L..4L) {
            assertSame(first, ledger.planned(presentation(id, (id - 1) * 40_000)))
            ledger.submitted(id)
            ledger.write((id - 1) * 40_000)
        }
        val next = observation.copy(sourceFrameId = 18, captureSequence = 43, sourceTimestampNs = 9_000_000_100)
        val nextFrame = ledger.planned(presentation(5, 160_000, next, repeated = false))
        ledger.submitted(5)
        assertEquals(MuxedFrame(4, 160_000, nextFrame), ledger.write(160_000))
        assertEquals(canonical, ledger.matchSource(41))
        assertEquals(listOf(41L, 43L), ledger.samples().mapNotNull { it.source?.sequence })
        assertEquals(160_000L, ledger.lastWrittenPtsUs())
        assertTrue(ledger.verifyContainer(listOf(0, 39_999, 80_001, 120_000, 160_002)))
        assertTrue(ledger.verifyContainer(listOf(0, 0, 80_001, 120_000, 160_002)))
        assertFalse(ledger.verifyContainer(listOf(0, 160_000)))
        assertFalse(ledger.verifyContainer(listOf(0, 40_000, 39_999, 120_000, 160_000)))
        assertFalse(ledger.verifyContainer(listOf(-1, 40_000, 80_000, 120_000, 160_000)))
        assertFalse(RecordingPresentationLedger().verifyContainer(emptyList()))
    }

    @Test fun observationFieldsMustAllStayIdenticalOnRepeat() {
        val ledger = RecordingPresentationLedger()
        val frame = ledger.planned(presentation(1, 0))
        val changed = listOf(
            observation.copy(sourceFrameId = 18),
            observation.copy(captureSequence = 42),
            observation.copy(sourceTimestampNs = 9_000_000_002),
        )
        for (source in changed) {
            assertThrows(IllegalStateException::class.java) { ledger.planned(presentation(2, 40_000, source)) }
        }
        assertSame(frame, ledger.planned(presentation(2, 40_000)))
    }

    @Test fun repeatFlagCannotInventObservationOrRevisitStaleSource() {
        val ledger = RecordingPresentationLedger()
        assertThrows(IllegalStateException::class.java) { ledger.planned(presentation(1, 0, repeated = true)) }
        ledger.planned(presentation(1, 0))
        assertThrows(IllegalStateException::class.java) { ledger.planned(presentation(2, 40_000, repeated = false)) }
        val next = observation.copy(sourceFrameId = 18, captureSequence = 42, sourceTimestampNs = 9_000_000_002)
        ledger.planned(presentation(2, 40_000, next, repeated = false))
        assertThrows(IllegalStateException::class.java) { ledger.planned(presentation(3, 80_000)) }
    }

    @Test fun duplicateReorderedAndInvalidOutputsFailWithoutChangingOrdinals() {
        val ledger = RecordingPresentationLedger()
        assertNull(ledger.lastWrittenPtsUs())
        assertThrows(IllegalStateException::class.java) { ledger.write(-1) }
        ledger.write(10)
        assertThrows(IllegalStateException::class.java) { ledger.write(10) }
        assertThrows(IllegalStateException::class.java) { ledger.write(9) }
        assertEquals(MuxedFrame(1, 11, null), ledger.write(11))
        assertEquals(2, ledger.samples().size)
    }

    @Test fun duplicateOrUnplannedSubmissionAndReorderedPlansAreRejected() {
        val ledger = RecordingPresentationLedger()
        assertThrows(IllegalStateException::class.java) { ledger.submitted(1) }
        assertThrows(IllegalStateException::class.java) { ledger.planned(presentation(0, 0, repeated = false)) }
        assertThrows(IllegalStateException::class.java) { ledger.planned(presentation(1, -1)) }
        ledger.planned(presentation(1, 10))
        assertThrows(IllegalStateException::class.java) { ledger.planned(presentation(1, 11, repeated = true)) }
        assertThrows(IllegalStateException::class.java) { ledger.planned(presentation(2, 10)) }
        assertThrows(IllegalStateException::class.java) { ledger.planned(presentation(2, 9)) }
        ledger.planned(presentation(2, 11))
        ledger.submitted(1)
        assertThrows(IllegalStateException::class.java) { ledger.submitted(1) }
        ledger.submitted(2)
        assertEquals(0L, ledger.write(10).ordinal)
    }

    @Test fun plannedIntentsAreBoundedEvenWithoutAnyCodecOutput() {
        val ledger = RecordingPresentationLedger()
        val first = ledger.planned(presentation(1, 0))
        for (id in 2L..12_000L) assertSame(first, ledger.planned(presentation(id, id - 1)))
        assertThrows(IllegalStateException::class.java) { ledger.planned(presentation(12_001, 12_000)) }
        assertTrue(ledger.samples().isEmpty())
        ledger.submitted(1)
        assertEquals(first, ledger.write(0).source)
    }

    @Test fun actualWrittenSamplesHaveIndependentBoundIncludingUnknownOutputs() {
        val ledger = RecordingPresentationLedger()
        for (pts in 0L until 12_000L) assertEquals(pts, ledger.write(pts).ordinal)
        assertThrows(IllegalStateException::class.java) { ledger.write(12_000) }
        assertEquals(12_000, ledger.samples().size)
        assertEquals(11_999L, ledger.lastWrittenPtsUs())
        assertFalse(ledger.hasCanonicalMatch())
    }

    @Test fun outputTokensAreBoundedEvenIfNativeWritesNeverFinish() {
        val ledger = RecordingPresentationLedger()
        val first = ledger.outputSeen(0)
        for (pts in 1L until 12_000L) ledger.outputSeen(pts)
        assertThrows(IllegalStateException::class.java) { ledger.outputSeen(12_000) }
        assertTrue(ledger.samples().isEmpty())
        assertNull(ledger.lastWrittenPtsUs())
        assertEquals(MuxedFrame(0, 0, null), ledger.muxed(first))
    }
}
