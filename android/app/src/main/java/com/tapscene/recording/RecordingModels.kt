package com.tapscene.recording

/** Session state only. It never contains a path, projection token or captured text. */
data class RecordingUiState(
    val phase: RecordingPhase = RecordingPhase.Idle,
    val sessionId: String? = null,
    val projectId: String? = null,
    val sourceId: String? = null,
    /** Monotonic user-interface elapsed time, NEVER a video PTS or evidence timestamp. */
    val elapsedMs: Long = 0,
    val stopReason: RecordingStopReason? = null,
    val error: String? = null,
    val canRetry: Boolean = false,
    val mapping: RecordingMapping = RecordingMapping.Unknown,
) {
    val isBusy: Boolean get() = phase in setOf(
        RecordingPhase.Starting, RecordingPhase.Recording,
        RecordingPhase.Stopping, RecordingPhase.Registering,
    )
}

enum class RecordingPhase { Idle, Starting, Recording, Stopping, Registering, Completed, Interrupted, Failed }

enum class RecordingStopReason {
    User, SystemStopped, ScreenLocked, DisplayChanged, DurationLimit, SizeLimit,
    LowStorage, RecorderError, ServiceDestroyed, ProcessInterrupted,
}

/** App sharing, letterboxing and scaling mean display coordinates are not evidence coordinates. */
enum class RecordingMapping { Unknown }

internal enum class JournalPhase { Starting, Recording, Stopping, Sealed, Registered, Interrupted, Failed, DiscardPending, Discarded }

internal data class RecordingJournal(
    val sessionId: String,
    val projectId: String,
    val sourceId: String,
    val phase: JournalPhase,
    /** Ordering/recovery only; no conversion into source PTS. */
    val createdAtMs: Long,
    val elapsedMs: Long = 0,
    val stopReason: RecordingStopReason? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrate: Int = 0,
    val latestContentLayout: RecordingContentLayout? = null,
)

/**
 * Latest API34 captured-region observation and API32+ fit/center calculation. This is NOT
 * a video timeline mapping: no callback timestamp is represented as a frame PTS. The author
 * still reviews actual decoded frames; hotspot/crop coordinates remain Unknown.
 */
internal data class RecordingContentLayout(
    val capturedWidth: Int,
    val capturedHeight: Int,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    companion object {
        fun fit(canvasWidth: Int, canvasHeight: Int, capturedWidth: Int, capturedHeight: Int): RecordingContentLayout {
            require(canvasWidth > 0 && canvasHeight > 0 && capturedWidth > 0 && capturedHeight > 0)
            val scale = minOf(canvasWidth.toDouble() / capturedWidth, canvasHeight.toDouble() / capturedHeight)
            val width = capturedWidth * scale
            val height = capturedHeight * scale
            val left = (canvasWidth - width) / 2
            val top = (canvasHeight - height) / 2
            return RecordingContentLayout(capturedWidth, capturedHeight, left, top, left + width, top + height)
        }
    }
}
