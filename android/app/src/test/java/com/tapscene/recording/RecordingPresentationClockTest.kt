package com.tapscene.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingPresentationClockTest {
    @Test fun static120SecondDisplayHasNewPresentationsButOnlyOneObservation() {
        val clock = RecordingPresentationClock()
        val source = VideoSourceObservation(1, 1, 8_000_000_000_000)
        clock.observe(source)
        val origin = 300_000_000_000L // unrelated producer and presentation clock origins
        val first = (clock.present(origin) as PresentationDecision.Submit).sample
        assertEquals(0L, first.presentationPtsUs)
        assertFalse(first.repeatedDisplay)
        val completedAtSequence = clock.observedSequence()
        var last = first
        for (tick in 1..3_599) {
            val decision = clock.present(origin + tick * 33_334_000L)
            last = (decision as PresentationDecision.Submit).sample
            assertEquals(source, last.observation)
            assertTrue(last.repeatedDisplay)
        }
        assertEquals(3_600L, last.presentationSampleId)
        assertEquals(1L, clock.observedSequence())
        assertNull(clock.newestObservationAfter(completedAtSequence))
        assertEquals(120_000_000L, clock.close(origin + 120_000_000_000L))
        assertEquals(PresentationDecision.Skip(PresentationSkip.Closed), clock.present(origin + 121_000_000_000L))
        assertThrows(IllegalStateException::class.java) { clock.observe(VideoSourceObservation(2, 2, source.sourceTimestampNs + 1)) }
    }

    @Test fun aNewObservationIsDistinctFromItsLaterRepeatedDisplaySamples() {
        val clock = RecordingPresentationClock()
        val before = VideoSourceObservation(5, 7, 2_000_000_000)
        clock.observe(before)
        clock.present(100_000_000_000)
        val cutoff = clock.observedSequence()
        clock.present(101_000_000_000)
        assertNull(clock.newestObservationAfter(cutoff))
        val after = VideoSourceObservation(6, 9, 2_000_033_333)
        clock.observe(after)
        assertEquals(after, clock.newestObservationAfter(cutoff))
        val presentation = (clock.present(102_000_000_000) as PresentationDecision.Submit).sample
        assertEquals(after, presentation.observation)
        assertFalse(presentation.repeatedDisplay)
        assertEquals(2_000_000L, presentation.presentationPtsUs)
        // Nothing converts the producer's 33,333 ns delta into this independent 2-second PTS.
        assertEquals(2_000_033_333L, presentation.observation.sourceTimestampNs)
        assertEquals(9L, clock.observedSequence())
    }

    @Test fun collisionsBackwardsAndLateWakeNeverInventPlusOneOrCatchupFrames() {
        val clock = RecordingPresentationClock()
        assertEquals(PresentationDecision.Skip(PresentationSkip.NoObservedFrame), clock.present(0))
        clock.observe(VideoSourceObservation(1, 1, 1))
        clock.present(1_000_000)
        assertEquals(PresentationDecision.Skip(PresentationSkip.QuantizationCollision), clock.present(1_000_999))
        assertEquals(PresentationDecision.Skip(PresentationSkip.ClockBackwards), clock.present(1_000_998))
        assertEquals(PresentationDecision.Skip(PresentationSkip.RateLimited), clock.present(2_000_000))
        val late = (clock.present(9_001_000_000) as PresentationDecision.Submit).sample
        assertEquals(2L, late.presentationSampleId)
        assertEquals(9_000_000L, late.presentationPtsUs)
        assertTrue(late.repeatedDisplay)
        assertNull(clock.close(9_000_000_000)) // final clock went backwards; no guessed EOS
    }

    @Test fun deadlinesAvoidThirtyHzQuantizationPollingAndDoNotIncludeCleanupTime() {
        val clock = RecordingPresentationClock()
        val origin = Long.MAX_VALUE - 200_000_000_000L
        clock.observe(VideoSourceObservation(1, 1, 5))
        assertEquals(0L, clock.delayUntilNextPresentationNs(origin))
        clock.present(origin)
        var now = origin
        repeat(3_599) {
            val delay = checkNotNull(clock.delayUntilNextPresentationNs(now))
            assertEquals(33_334_000L, delay)
            now += delay
            val sample = (clock.present(now) as PresentationDecision.Submit).sample
            assertEquals((it + 2).toLong(), sample.presentationSampleId)
        }
        val gateTime = origin + 120_000_000_000L
        assertEquals(120_000_000L, clock.close(gateTime))
        assertNull(clock.close(gateTime + 7_000_000_000L))
        assertNull(clock.delayUntilNextPresentationNs(gateTime + 7_000_000_000L))
        assertEquals(1L, clock.observedSequence())
    }

    @Test fun closingBeforeFirstFrameOrWithoutPositiveFinalDurationIsExplicitlyUnavailable() {
        assertNull(RecordingPresentationClock().close(5))
        val clock = RecordingPresentationClock()
        clock.observe(VideoSourceObservation(1, 1, 1))
        clock.present(5_000_000)
        assertNull(clock.close(5_000_999))
        assertNull(clock.close(6_000_000)) // close is terminal even when the first request was unavailable
    }
}
