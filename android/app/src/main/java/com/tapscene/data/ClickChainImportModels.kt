package com.tapscene.data

import com.tapscene.clickplan.ClickRun
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.recording.FrameEvidenceCandidate

/** Construct only after actual safe PNG review. Selection never conveys privacy approval. */
internal data class ClickChainReviewedFrame(
    val evidence: FrameEvidenceCandidate,
    val reviewed: ReviewedStepInput,
    val title: String,
) {
    val frameKey: String get() = "${evidence.ticket.action.sessionId}:${evidence.ticket.sourceFrameId}"
}

/** The author explicitly confirms this rectangle on the chosen FROM image and this route. */
internal data class ClickChainConfirmedAction(
    val actionId: String,
    val fromFrameKey: String,
    val toFrameKey: String,
    val label: String,
    val rect: OpaqueMask,
    val before: FrameEvidenceCandidate,
    val after: FrameEvidenceCandidate,
)

/** New project only. A durable operation ID is retained across cancellation and uncertain delivery. */
internal data class ClickChainImportInput(
    val operationId: String,
    val run: ClickRun,
    val source: ImportedSource,
    val title: String,
    val frames: List<ClickChainReviewedFrame>,
    val actions: List<ClickChainConfirmedAction>,
    val startFrameKey: String,
    val terminalFrameKey: String? = null,
)

internal data class ClickChainImportReceipt(
    val operationId: String,
    val projectId: String,
    val status: String,
    val projectStillExists: Boolean,
)
