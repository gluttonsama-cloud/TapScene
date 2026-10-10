package com.tapscene.recording

/** Process-only capability: a newer request or cancellation permanently invalidates older starts. */
internal class RecordingClickGate {
    private var owner: String? = null
    @Synchronized fun issue(sessionId: String) { owner = sessionId }
    @Synchronized fun cancel(sessionId: String) { if (owner == sessionId) owner = null }
    @Synchronized fun permits(sessionId: String): Boolean = owner == sessionId
}
