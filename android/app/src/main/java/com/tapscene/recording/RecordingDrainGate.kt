package com.tapscene.recording

/** Codec-worker-only protocol state. A requested stop is not proof that GL has returned. */
internal class RecordingDrainGate {
    enum class End { Unexpected, WaitForGl, FinishCodec }
    enum class Teardown { SignalEos, FinishCodec }
    var eosSeen: Boolean = false
        private set
    var glTeardownReturned: Boolean = false
        private set

    /** Call after preserving the final nonempty sample, or immediately for an unexpected EOS. */
    fun observedEnd(captureClosing: Boolean): End {
        eosSeen = true
        return when {
            !captureClosing -> End.Unexpected
            glTeardownReturned -> End.FinishCodec
            else -> End.WaitForGl
        }
    }

    /** Called on the codec worker after GL cleanup returns, even when cleanup failed. */
    fun glReturned(canDrain: Boolean): Teardown {
        check(!glTeardownReturned) { "GL teardown returned twice" }
        glTeardownReturned = true
        return if (!canDrain || eosSeen) Teardown.FinishCodec else Teardown.SignalEos
    }

    fun mayFinishAfterEnd(): Boolean = eosSeen && glTeardownReturned
}
