package com.tapscene.recording

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Production ownership rules joined with the real presentation clock, ledger and three-slot pool. */
class InitialFrameReservationTest {
    private val action = FrameAnchorAction(id(), id(), id(), id(), 7)
    private val authorization = InitialFrameAuthorization(id(), 1_000, 16_000)

    @Test fun muxBeforeEglAndEglBeforeMuxBothRequireTheSelectedTickAndOriginalMatch() {
        for (muxFirst in listOf(true, false)) {
            val fixture = Fixture()
            val sample = fixture.publishAndPresent(1, 10_000_000)
            fixture.select(sample)
            if (muxFirst) fixture.mux(sample) else fixture.submit(sample)
            fixture.refresh()
            assertEquals(InitialFrameAdmissionStatus.Pending, fixture.reservation.status)
            if (muxFirst) fixture.submit(sample) else fixture.mux(sample)
            fixture.refresh()
            assertEquals(InitialFrameAdmissionStatus.Ready, fixture.reservation.status)
            val consumed = checkNotNull(fixture.consume())
            assertEquals(sample.observation, consumed.selection.lease.frame)
            assertEquals(sample.presentationPtsUs, consumed.selection.canonical.submittedPtsUs)
            assertEquals(0, fixture.releases)
            assertNull(fixture.consume())
            fixture.reservation.revoke(InitialFrameAdmissionStatus.Cancelled)
            assertTrue(fixture.pool.isPinned(consumed.selection.lease))
            fixture.pool.release(consumed.selection.lease) // only the PNG owner releases after consume
        }
    }

    @Test fun staticRepeatKeepsOriginalCanonicalPtsAndStillRequiresItsOwnEglSuccess() {
        val fixture = Fixture()
        val original = fixture.publishAndPresent(1, 10_000_000)
        fixture.submit(original); fixture.mux(original)
        val repeated = fixture.present(50_000_000)
        assertTrue(repeated.repeatedDisplay)
        fixture.select(repeated)
        fixture.refresh()
        assertEquals(InitialFrameAdmissionStatus.Pending, fixture.reservation.status)
        fixture.submit(repeated)
        fixture.refresh()
        assertEquals(InitialFrameAdmissionStatus.Ready, fixture.reservation.status)
        val consumed = checkNotNull(fixture.consume())
        assertEquals(original.presentationPtsUs, consumed.selection.canonical.submittedPtsUs)
        assertEquals(repeated.presentationPtsUs, consumed.metadata.selectionPresentationPtsUs)
        assertEquals(repeated.presentationSampleId, consumed.metadata.selectionPresentationSampleId)
        assertTrue(consumed.metadata.selectionPresentationPtsUs > consumed.selection.canonical.submittedPtsUs)
        assertEquals(1, fixture.acquisitions)
        fixture.pool.release(consumed.selection.lease)
    }

    @Test fun repeatedMuxCannotRepairAnUnmatchedOriginalOrAnOutputSeenBeforePlanning() {
        for (unknownOriginal in listOf(false, true)) {
            val fixture = Fixture()
            val unknown = if (unknownOriginal) fixture.ledger.outputSeen(0) else null
            val original = fixture.publishAndPresent(1, 10_000_000)
            fixture.submit(original)
            if (unknown != null) fixture.ledger.muxed(unknown)
            val repeated = fixture.present(50_000_000)
            fixture.select(repeated)
            fixture.submit(repeated); fixture.mux(repeated)
            fixture.refresh()
            assertNull(fixture.ledger.matchSource(original.observation.captureSequence))
            assertEquals(InitialFrameAdmissionStatus.Pending, fixture.reservation.status)
            assertNull(fixture.consume())
            fixture.reservation.revoke(InitialFrameAdmissionStatus.Cancelled)
            assertEquals(1, fixture.releases)
        }
    }

    @Test fun continuousNewFramesNeverReplaceTheSelectedSnapshotOrHoldAdditionalLeases() {
        val fixture = Fixture()
        val selected = fixture.publishAndPresent(1, 10_000_000)
        fixture.select(selected)
        fixture.submit(selected)
        // An independent second presentation lease on exactly this FBO is returned immediately.
        val presentation = checkNotNull(fixture.pool.pinLatest())
        assertEquals(selected.observation, presentation.frame)
        fixture.pool.release(presentation)
        for (number in 2L..40L) {
            val newer = fixture.publishAndPresent(number, number * 40_000_000)
            fixture.select(newer)
            fixture.submit(newer)
            fixture.refresh()
            assertEquals(1L, fixture.reservation.sourceSequence)
            assertEquals(InitialFrameAdmissionStatus.Pending, fixture.reservation.status)
        }
        fixture.mux(selected)
        fixture.refresh()
        val consumed = checkNotNull(fixture.consume())
        assertEquals(selected.observation, consumed.selection.lease.frame)
        assertEquals(1, fixture.acquisitions)
        assertEquals(0, fixture.releases)
        fixture.pool.release(consumed.selection.lease)
    }

    @Test fun readinessForADifferentCanonicalFrameDoesNotAdmitTheSelectedSource() {
        val fixture = Fixture()
        val selected = fixture.publishAndPresent(1, 10_000_000)
        fixture.select(selected); fixture.submit(selected)
        val newer = fixture.publishAndPresent(2, 50_000_000)
        fixture.submit(newer); fixture.mux(newer)
        assertTrue(fixture.ledger.hasCanonicalMatch())
        fixture.reservation.refresh(1_200, 0, true, fixture.ledger.matchSource(2))
        assertEquals(InitialFrameAdmissionStatus.Pending, fixture.reservation.status)
        fixture.reservation.revoke(InitialFrameAdmissionStatus.Cancelled)
        assertEquals(1, fixture.releases)
    }

    @Test fun consumptionRechecksTheExactCanonicalMatchAfterReadiness() {
        for (invalid in listOf("null", "source", "timestamp")) {
            val fixture = Fixture()
            val sample = fixture.publishAndPresent(1, 10_000_000)
            fixture.select(sample); fixture.submit(sample); fixture.mux(sample); fixture.refresh()
            assertEquals(InitialFrameAdmissionStatus.Ready, fixture.reservation.status)
            val original = checkNotNull(fixture.ledger.matchSource(1))
            val changed = when (invalid) {
                "null" -> null
                "source" -> original.copy(source = original.source!!.copy(sequence = 2))
                else -> original.copy(encoderPtsUs = original.encoderPtsUs + 1)
            }
            assertNull(fixture.reservation.consume(1_300, 0, true, changed))
            assertEquals(InitialFrameAdmissionStatus.Unavailable, fixture.reservation.status)
            assertEquals(1, fixture.releases)
            assertNull(fixture.consume())
            fixture.reservation.revoke(InitialFrameAdmissionStatus.Cancelled)
            assertEquals(1, fixture.releases)
        }
    }

    @Test fun cancelExpiryStopAndEpochInvalidationReleaseExactlyOnceBeforeOrAfterReadiness() {
        for (ready in listOf(false, true)) for (reason in listOf("cancel", "expire", "stop", "epoch")) {
            val fixture = Fixture()
            val sample = fixture.publishAndPresent(1, 10_000_000)
            fixture.select(sample)
            val inFlightPresentation = checkNotNull(fixture.pool.pinLatest())
            if (ready) { fixture.submit(sample); fixture.mux(sample); fixture.refresh() }
            when (reason) {
                "cancel" -> fixture.reservation.revoke(InitialFrameAdmissionStatus.Cancelled)
                "expire" -> fixture.refresh(16_001)
                "stop" -> fixture.reservation.refresh(1_200, 0, false, fixture.ledger.matchSource(1))
                "epoch" -> fixture.reservation.refresh(1_200, 1, true, fixture.ledger.matchSource(1))
            }
            // Cancellation revokes only the admission's second lease, never the in-flight EGL pin.
            assertTrue(fixture.pool.isPinned(inFlightPresentation))
            fixture.pool.release(inFlightPresentation)
            val terminal = fixture.reservation.status
            assertTrue(terminal in setOf(InitialFrameAdmissionStatus.Cancelled, InitialFrameAdmissionStatus.Expired,
                InitialFrameAdmissionStatus.Unavailable))
            fixture.reservation.revoke(InitialFrameAdmissionStatus.Cancelled)
            fixture.refresh()
            fixture.reservation.submitted(sample.presentationSampleId)
            val newer = fixture.publishAndPresent(2, 50_000_000)
            fixture.select(newer)
            assertEquals(terminal, fixture.reservation.status)
            assertNull(fixture.consume())
            assertEquals(1, fixture.acquisitions)
            assertEquals(1, fixture.releases)
            assertNull(fixture.reservation.sourceSequence)
        }
    }

    @Test fun originalDeadlineIsCheckedAtSelectionAndConsumptionWithoutExtendingAuthorization() {
        val expired = Fixture()
        expired.select(expired.publishAndPresent(1, 10_000_000), 16_001)
        assertEquals(InitialFrameAdmissionStatus.Expired, expired.reservation.status)
        assertEquals(0, expired.acquisitions)
        assertEquals(0, expired.releases)

        val fixture = Fixture()
        val sample = fixture.publishAndPresent(1, 10_000_000)
        fixture.select(sample, 15_999)
        fixture.submit(sample); fixture.mux(sample); fixture.refresh(16_000)
        assertEquals(InitialFrameAdmissionStatus.Ready, fixture.reservation.status)
        assertNull(fixture.consume(16_001))
        assertEquals(InitialFrameAdmissionStatus.Expired, fixture.reservation.status)
        assertEquals(1, fixture.releases)

        val inclusive = Fixture()
        val last = inclusive.publishAndPresent(1, 10_000_000)
        inclusive.select(last, 16_000); inclusive.submit(last); inclusive.mux(last)
        val consumed = checkNotNull(inclusive.consume(16_000))
        assertEquals(16_000L, consumed.metadata.consumedAtUptimeMs)
        inclusive.pool.release(consumed.selection.lease)
    }

    @Test fun cancelledBeforeSelectionDoesNotAcquireAndConsumedPngFailureCannotReopen() {
        val cancelled = Fixture()
        cancelled.reservation.revoke(InitialFrameAdmissionStatus.Cancelled)
        cancelled.select(cancelled.publishAndPresent(1, 10_000_000))
        assertEquals(0, cancelled.acquisitions)

        val fixture = Fixture()
        val sample = fixture.publishAndPresent(1, 10_000_000)
        fixture.select(sample); fixture.submit(sample); fixture.mux(sample)
        val consumed = checkNotNull(fixture.consume())
        fixture.pool.release(consumed.selection.lease) // simulate PNG success, failure, or post rejection
        fixture.reservation.revoke(InitialFrameAdmissionStatus.Unavailable)
        fixture.reservation.revoke(InitialFrameAdmissionStatus.Cancelled)
        assertNull(fixture.consume())
        assertEquals(InitialFrameAdmissionStatus.Consumed, fixture.reservation.status)
        assertEquals(0, fixture.releases)
        fixture.select(fixture.publishAndPresent(2, 50_000_000))
        assertEquals(1, fixture.acquisitions)
    }

    @Test fun admissionMetadataRequiresABeforeWithAnOriginalCanonicalTimestamp() {
        val metadata = InitialFrameAdmissionMetadata(1_000, 16_000, 1_100, 3, 80_000, 1_200)
        val ticket = FrameTicket(id(), action, FrameBoundary.Before, 0, 1, 1, 900_000, 0,
            FrameGeometry.fitCenter(4, 4, 4, 4), FrameFreshness.Fresh, metadata)
        assertEquals(metadata, ticket.admission)
        assertThrows(IllegalArgumentException::class.java) { ticket.copy(boundary = FrameBoundary.After) }
        assertThrows(IllegalArgumentException::class.java) { ticket.copy(submittedPtsUs = 80_001) }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(consumedAtUptimeMs = 16_001) }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(selectedAtUptimeMs = 999) }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(selectionPresentationSampleId = 0) }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(deadlineUptimeMs = 16_001) }
    }

    private inner class Fixture {
        val clock = RecordingPresentationClock()
        val ledger = RecordingPresentationLedger()
        val pool = FrameLeasePool<VideoSourceObservation>()
        val canonical = mutableMapOf<Long, RecordedSourceFrame>()
        var releases = 0
        var acquisitions = 0
        val reservation = InitialFrameReservation<FrameLeasePool.Read<VideoSourceObservation>>(action, authorization, 0) {
            pool.release(it); releases++
        }
        fun publishAndPresent(sequence: Long, nowNs: Long): VideoPresentationSample {
            val frame = VideoSourceObservation(sequence, sequence, sequence * 100_000)
            pool.publish(checkNotNull(pool.claim()), frame)
            clock.observe(frame)
            return present(nowNs)
        }
        fun present(nowNs: Long): VideoPresentationSample = (clock.present(nowNs) as PresentationDecision.Submit).sample.also {
            canonical[it.observation.captureSequence] = ledger.planned(it)
        }
        fun select(sample: VideoPresentationSample, now: Long = 1_100) {
            val frame = checkNotNull(canonical[sample.observation.captureSequence])
            reservation.select(sample, frame, now, 0, true) { acquisitions++; checkNotNull(pool.pinLatest()) }
        }
        fun submit(sample: VideoPresentationSample) { ledger.submitted(sample.presentationSampleId); reservation.submitted(sample.presentationSampleId) }
        fun mux(sample: VideoPresentationSample) { ledger.muxed(ledger.outputSeen(sample.presentationPtsUs)) }
        fun refresh(now: Long = 1_200) { reservation.refresh(now, 0, true, reservation.sourceSequence?.let(ledger::matchSource)) }
        fun consume(now: Long = 1_300) = reservation.consume(now, 0, true, reservation.sourceSequence?.let(ledger::matchSource))
    }

    private fun id() = UUID.randomUUID().toString()
}
