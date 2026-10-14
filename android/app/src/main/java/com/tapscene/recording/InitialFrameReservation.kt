package com.tapscene.recording

/**
 * One admission-time snapshot. The backend serializes every call with its FBO publication lock.
 * Waiting owns only a lease; a successful consume transfers that lease to the normal PNG pipeline.
 * Neither a repeat nor a newer observation may replace the first selection or its canonical sample.
 */
internal class InitialFrameReservation<T : Any>(
    val action: FrameAnchorAction,
    val authorization: InitialFrameAuthorization,
    val epoch: Long,
    private val release: (T) -> Unit,
) {
    internal data class Selection<T>(
        val lease: T,
        val observation: VideoSourceObservation,
        val canonical: RecordedSourceFrame,
        val sampleId: Long,
        val samplePtsUs: Long,
        val selectedAtUptimeMs: Long,
    )
    internal data class Consumed<T>(val selection: Selection<T>, val metadata: InitialFrameAdmissionMetadata)

    var status = InitialFrameAdmissionStatus.Pending
        private set
    private var selection: Selection<T>? = null
    private var selectionSubmitted = false
    val sourceSequence: Long? get() = selection?.observation?.captureSequence
    val awaitingSelection: Boolean get() = status == InitialFrameAdmissionStatus.Pending && selection == null

    /** Must run before selecting, querying readiness or consuming; clocks are never media PTS. */
    fun validate(nowUptimeMs: Long, currentEpoch: Long, available: Boolean): Boolean {
        if (status !in setOf(InitialFrameAdmissionStatus.Pending, InitialFrameAdmissionStatus.Ready)) return false
        if (!available || currentEpoch != epoch) revoke(InitialFrameAdmissionStatus.Unavailable)
        else if (!authorization.validAt(nowUptimeMs) || selection?.let { nowUptimeMs < it.selectedAtUptimeMs } == true) {
            revoke(InitialFrameAdmissionStatus.Expired)
        }
        return status == InitialFrameAdmissionStatus.Pending || status == InitialFrameAdmissionStatus.Ready
    }

    /** The lease supplier is called at most once, only for a normal, already-planned Submit tick. */
    fun select(sample: VideoPresentationSample, canonical: RecordedSourceFrame, nowUptimeMs: Long,
        currentEpoch: Long, available: Boolean, acquire: () -> T) {
        if (!validate(nowUptimeMs, currentEpoch, available) || !awaitingSelection) return
        check(canonical.sequence == sample.observation.captureSequence &&
            canonical.timestampNs == sample.observation.sourceTimestampNs && canonical.submittedPtsUs <= sample.presentationPtsUs)
        selection = Selection(acquire(), sample.observation, canonical, sample.presentationSampleId,
            sample.presentationPtsUs, nowUptimeMs)
    }

    /** The selected normal tick itself must succeed, including when it repeats an older source. */
    fun submitted(sampleId: Long) {
        if (selection?.sampleId == sampleId) selectionSubmitted = true
    }

    fun refresh(nowUptimeMs: Long, currentEpoch: Long, available: Boolean, match: MuxedFrame?) {
        if (!validate(nowUptimeMs, currentEpoch, available)) return
        val selected = selection ?: return
        if (selectionSubmitted && match?.source == selected.canonical &&
            match.encoderPtsUs == selected.canonical.submittedPtsUs) status = InitialFrameAdmissionStatus.Ready
    }

    /** The owner has already revalidated readiness and acquired a bounded PNG permit. */
    fun consume(nowUptimeMs: Long, currentEpoch: Long, available: Boolean, match: MuxedFrame?): Consumed<T>? {
        refresh(nowUptimeMs, currentEpoch, available, match)
        if (status != InitialFrameAdmissionStatus.Ready) return null
        val selected = checkNotNull(selection)
        // A Ready hint is not permission to transfer without this exact proof at consumption.
        if (match == null || match.source != selected.canonical || match.encoderPtsUs != selected.canonical.submittedPtsUs) {
            revoke(InitialFrameAdmissionStatus.Unavailable)
            return null
        }
        val metadata = InitialFrameAdmissionMetadata(authorization.confirmedAtUptimeMs, authorization.deadlineUptimeMs,
            selected.selectedAtUptimeMs, selected.sampleId, selected.samplePtsUs, nowUptimeMs)
        selection = null
        status = InitialFrameAdmissionStatus.Consumed
        return Consumed(selected, metadata)
    }

    fun revoke(reason: InitialFrameAdmissionStatus) {
        require(reason in setOf(InitialFrameAdmissionStatus.Cancelled, InitialFrameAdmissionStatus.Unavailable,
            InitialFrameAdmissionStatus.Expired))
        if (status !in setOf(InitialFrameAdmissionStatus.Pending, InitialFrameAdmissionStatus.Ready)) return
        val owned = selection
        selection = null
        status = reason
        owned?.let { release(it.lease) }
    }
}
