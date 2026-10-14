package com.tapscene.recording

/** One existing explicit confirmation; this value does not create or extend its authority. */
data class InitialFrameAuthorization(
    val confirmationId: String,
    val confirmedAtUptimeMs: Long,
    val deadlineUptimeMs: Long,
) {
    init {
        requireFrameUuid(confirmationId)
        require(confirmedAtUptimeMs >= 0 && deadlineUptimeMs > confirmedAtUptimeMs)
        require(deadlineUptimeMs - confirmedAtUptimeMs <= 15_000)
    }
    fun validAt(nowUptimeMs: Long): Boolean = nowUptimeMs in confirmedAtUptimeMs..deadlineUptimeMs
}

/** Times are diagnostics, never media timestamps. The selected tick may be a static repeat. */
data class InitialFrameAdmissionMetadata(
    val confirmedAtUptimeMs: Long,
    val deadlineUptimeMs: Long,
    val selectedAtUptimeMs: Long,
    val selectionPresentationSampleId: Long,
    val selectionPresentationPtsUs: Long,
    val consumedAtUptimeMs: Long,
) {
    init {
        require(confirmedAtUptimeMs >= 0 && deadlineUptimeMs > confirmedAtUptimeMs)
        require(deadlineUptimeMs - confirmedAtUptimeMs <= 15_000)
        require(selectedAtUptimeMs in confirmedAtUptimeMs..deadlineUptimeMs)
        require(consumedAtUptimeMs in selectedAtUptimeMs..deadlineUptimeMs)
        require(selectionPresentationSampleId > 0 && selectionPresentationPtsUs >= 0)
    }
}

enum class InitialFrameAdmissionStatus { Pending, Ready, Expired, Cancelled, Unavailable, Consumed }

/** Backend-owned, process-local capability. Cancellation and consumption are idempotence guarded. */
interface InitialFrameAdmissionHandle {
    val status: InitialFrameAdmissionStatus
    /** Finalizes exactly the reserved snapshot, or fails closed. Never substitute another frame. */
    fun consume(): AnchorResult
    fun cancel()
}

/** First gesture only; all methods return promptly and never await GL/codec/PNG/disk work. */
interface InitialFrameAdmissionPort {
    /** onChanged is a hint from any thread; the owner must enqueue and revalidate its full identity. */
    fun begin(action: FrameAnchorAction, authorization: InitialFrameAuthorization,
        onChanged: () -> Unit): InitialFrameAdmissionHandle

    object Unavailable : InitialFrameAdmissionPort {
        override fun begin(action: FrameAnchorAction, authorization: InitialFrameAuthorization,
            onChanged: () -> Unit): InitialFrameAdmissionHandle = object : InitialFrameAdmissionHandle {
            override val status = InitialFrameAdmissionStatus.Unavailable
            override fun consume() = AnchorResult.Missing(FrameMissingReason.CaptureStopped)
            override fun cancel() = Unit
        }
    }
}
