package com.tapscene.recording

/** An actual latched/normalized source observation, never an encoder-generated repeat. */
internal data class VideoSourceObservation(
    val sourceFrameId: Long,
    val captureSequence: Long,
    val sourceTimestampNs: Long,
) {
    init { require(sourceFrameId > 0 && captureSequence > 0 && sourceTimestampNs > 0) }
}

/**
 * A deliberate encoder presentation of an already observed frame. Its clock is independently
 * selected by the recorder; presentationPtsUs NEVER claims to be a producer capture timestamp.
 */
internal data class VideoPresentationSample(
    val presentationSampleId: Long,
    val observation: VideoSourceObservation,
    val presentationPtsUs: Long,
    val repeatedDisplay: Boolean,
)

internal enum class PresentationSkip { NoObservedFrame, Closed, ClockBackwards, QuantizationCollision, RateLimited }
internal sealed interface PresentationDecision {
    data class Submit(val sample: VideoPresentationSample) : PresentationDecision
    data class Skip(val reason: PresentationSkip) : PresentationDecision
}

/**
 * Pure explicit presentation clock. Production integration must supply a single monotonic clock,
 * hold the exact observation's FBO lease, and commit source/output/container identities separately.
 * The clock starts on first deliberate presentation, not projection authorization or an app clock
 * inferred from SurfaceTexture.timestamp. No GPU, codec, delay, disk, or wall time is accessed here.
 */
internal class RecordingPresentationClock(private val maxFramesPerSecond: Int = 30) {
    private val minimumIntervalUs: Long
    private var latest: VideoSourceObservation? = null
    private var lastPresented: VideoPresentationSample? = null
    private var originNs: Long? = null
    private var lastClockNs: Long? = null
    private var sampleSequence = 0L
    private var closed = false
    init {
        require(maxFramesPerSecond in 1..60)
        // Ceiling does not invent timestamps; it only decides when another real clock value is due.
        minimumIntervalUs = (1_000_000L + maxFramesPerSecond - 1) / maxFramesPerSecond
    }

    fun observe(frame: VideoSourceObservation) {
        check(!closed) { "Recording presentation is closed" }
        latest?.let { previous ->
            check(frame.sourceFrameId > previous.sourceFrameId && frame.captureSequence > previous.captureSequence &&
                frame.sourceTimestampNs > previous.sourceTimestampNs) { "Source observations must retain real monotonic identities" }
        }
        latest = frame
    }

    /** The action-completion boundary reads this, never presentationSampleId or presentation PTS. */
    fun observedSequence(): Long = latest?.captureSequence ?: 0

    fun newestObservationAfter(captureSequence: Long): VideoSourceObservation? =
        latest?.takeIf { it.captureSequence > captureSequence }

    fun present(monotonicNowNs: Long): PresentationDecision {
        require(monotonicNowNs >= 0)
        if (closed) return PresentationDecision.Skip(PresentationSkip.Closed)
        val frame = latest ?: return PresentationDecision.Skip(PresentationSkip.NoObservedFrame)
        val lastClock = lastClockNs
        if (lastClock != null && monotonicNowNs < lastClock) return PresentationDecision.Skip(PresentationSkip.ClockBackwards)
        lastClockNs = monotonicNowNs
        val origin = originNs ?: monotonicNowNs.also { originNs = it }
        val ptsUs = (monotonicNowNs - origin) / 1_000
        val previous = lastPresented
        if (previous != null) {
            if (ptsUs <= previous.presentationPtsUs) return PresentationDecision.Skip(PresentationSkip.QuantizationCollision)
            if (ptsUs - previous.presentationPtsUs < minimumIntervalUs) return PresentationDecision.Skip(PresentationSkip.RateLimited)
        }
        val sample = VideoPresentationSample(++sampleSequence, frame, ptsUs,
            previous?.observation?.sourceFrameId == frame.sourceFrameId)
        lastPresented = sample
        return PresentationDecision.Submit(sample)
    }

    /** Remaining real-clock time to the next deadline; no fixed 33 ms polling or catch-up loop. */
    fun delayUntilNextPresentationNs(monotonicNowNs: Long): Long? {
        require(monotonicNowNs >= 0)
        if (closed || latest == null) return null
        val origin = originNs ?: return 0L
        if (monotonicNowNs < (lastClockNs ?: origin)) return null
        val previous = lastPresented ?: return 0L
        val elapsedNs = monotonicNowNs - origin
        val elapsedSincePtsUs = elapsedNs / 1_000 - previous.presentationPtsUs
        if (elapsedSincePtsUs >= minimumIntervalUs) return 0L
        return ((minimumIntervalUs - elapsedSincePtsUs) * 1_000 - elapsedNs % 1_000).coerceAtLeast(0)
    }

    /**
     * Final duration metadata in the SAME explicit presentation clock. The caller may use this
     * only as a zero-size muxer EOS duration marker after actual samples, never as an observed frame
     * or a sample ordinal. Null means no trustworthy positive last-sample duration is available.
     */
    fun close(monotonicNowNs: Long): Long? {
        require(monotonicNowNs >= 0)
        if (closed) return null
        closed = true
        val origin = originNs ?: return null
        val previous = lastPresented ?: return null
        if (monotonicNowNs < (lastClockNs ?: origin)) return null
        val ptsUs = (monotonicNowNs - origin) / 1_000
        return ptsUs.takeIf { it > previous.presentationPtsUs }
    }
}
