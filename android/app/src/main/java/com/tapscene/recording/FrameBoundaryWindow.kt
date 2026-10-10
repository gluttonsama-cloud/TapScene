package com.tapscene.recording

/** Pure action-window rules. The owner serializes calls with its frame-publication lease lock. */
internal class FrameBoundaryWindow(private val sessionId: String, private val sourceId: String) {
    var epoch = 0L
        private set
    private var runIdentity: Pair<String, Long>? = null
    private val selections = mutableMapOf<Pair<FrameAnchorAction, FrameBoundary>, AnchorResult>()
    private val actionEpochs = mutableMapOf<FrameAnchorAction, Long>()
    private val completions = mutableMapOf<FrameAnchorAction, Pair<Long, Long>>()

    fun accepts(action: FrameAnchorAction): Boolean = action.sessionId == sessionId && action.sourceId == sourceId &&
        (runIdentity == null || runIdentity == (action.runId to action.generation))

    /** Null means this boundary may select once now; non-null is final and must never be retried. */
    fun begin(action: FrameAnchorAction, boundary: FrameBoundary): AnchorResult? {
        if (!accepts(action)) return AnchorResult.Missing(FrameMissingReason.ClosedEpoch)
        selections[action to boundary]?.let {
            return if (actionEpochs[action] == epoch) it else AnchorResult.Missing(FrameMissingReason.ClosedEpoch)
        }
        if (boundary == FrameBoundary.Before) {
            if (runIdentity == null) runIdentity = action.runId to action.generation
            actionEpochs[action] = epoch
        }
        return null
    }
    fun complete(action: FrameAnchorAction, observedSequence: Long) {
        require(observedSequence >= 0)
        if (accepts(action) && actionEpochs[action] == epoch && action !in completions) {
            completions[action] = epoch to observedSequence
        }
    }
    fun afterLowerBound(action: FrameAnchorAction): Long? = completions[action]?.takeIf { it.first == epoch }?.second
    fun retain(action: FrameAnchorAction, boundary: FrameBoundary, result: AnchorResult) {
        check(accepts(action))
        val key = action to boundary
        check(selections[key] == null || selections[key] == result) { "Boundary selection cannot change" }
        check(selections.size < 80 || key in selections)
        selections[key] = result
    }
    fun cancel() { epoch++; completions.clear() }
}
