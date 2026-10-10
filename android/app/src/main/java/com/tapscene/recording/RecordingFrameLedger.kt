package com.tapscene.recording

/** Source timestamps are only compared within this SurfaceTexture; never with a CPU clock. */
internal data class RecordedSourceFrame(val sequence: Long, val timestampNs: Long, val submittedPtsUs: Long)
internal data class MuxedFrame(val ordinal: Long, val encoderPtsUs: Long, val source: RecordedSourceFrame?)
internal enum class SourceTimestampSkip { NonPositive, RepeatedOrBackwards, QuantizationCollision, RateLimited }
internal sealed interface SourceTimestampResult {
    data class Accepted(val frame: RecordedSourceFrame) : SourceTimestampResult
    data class Skipped(val reason: SourceTimestampSkip) : SourceTimestampResult
}

/** Pure bounded ledger. No nearest-PTS lookup, reordering, or invented +1 microsecond timestamps. */
internal class RecordingFrameLedger(private val minFrameIntervalUs: Long = 0) {
    private var originNs: Long? = null
    private var previousNs = -1L
    private var previousPtsUs = -1L
    private var sequence = 0L
    private var processedSequence = 0L
    private val submitted = linkedMapOf<Long, RecordedSourceFrame>()
    private val written = mutableListOf<MuxedFrame>()
    private val writtenPts = mutableSetOf<Long>()
    /** Reserve before entering updateTexImage; an in-flight latch belongs below completion's cutoff. */
    @Synchronized fun reserveCaptureSequence(): Long = ++sequence
    @Synchronized fun source(timestampNs: Long, captureSequence: Long = reserveCaptureSequence()): SourceTimestampResult {
        check(captureSequence > processedSequence && captureSequence <= sequence) { "Invalid capture reservation" }
        processedSequence = captureSequence
        if (timestampNs <= 0) return SourceTimestampResult.Skipped(SourceTimestampSkip.NonPositive)
        if (timestampNs <= previousNs) return SourceTimestampResult.Skipped(SourceTimestampSkip.RepeatedOrBackwards)
        previousNs = timestampNs
        val origin = originNs ?: timestampNs.also { originNs = it }
        val ptsUs = (timestampNs - origin) / 1_000
        if (ptsUs <= previousPtsUs) return SourceTimestampResult.Skipped(SourceTimestampSkip.QuantizationCollision)
        if (previousPtsUs >= 0 && ptsUs - previousPtsUs < minFrameIntervalUs) return SourceTimestampResult.Skipped(SourceTimestampSkip.RateLimited)
        check(submitted.size < 12_000) { "Source frame budget exceeded" }
        previousPtsUs = ptsUs
        return SourceTimestampResult.Accepted(RecordedSourceFrame(captureSequence, timestampNs, ptsUs).also { submitted[ptsUs] = it })
    }
    class OutputToken internal constructor(val ptsUs: Long, internal val source: RecordedSourceFrame?,
        internal val owner: RecordingFrameLedger)
    /** Freeze exact identity at the output callback, before a possibly blocking native mux write. */
    @Synchronized fun observeOutput(ptsUs: Long): OutputToken {
        require(ptsUs >= 0)
        return OutputToken(ptsUs, submitted[ptsUs], this)
    }
    /** Convenience for pure synchronous callers; production carries the pre-write token. */
    @Synchronized fun muxed(ptsUs: Long): MuxedFrame = muxed(observeOutput(ptsUs))
    /** Called only after a nonempty/nonconfig complete sample was successfully written. */
    @Synchronized fun muxed(output: OutputToken): MuxedFrame {
        check(output.owner === this) { "Output belongs to another recording" }
        val ptsUs = output.ptsUs
        check(ptsUs >= 0 && ptsUs !in writtenPts) { "Duplicate or invalid encoder sample" }
        check(written.lastOrNull()?.encoderPtsUs?.let { ptsUs > it } != false) { "Reordered encoder sample" }
        check(written.size < 12_000) { "Encoder sample budget exceeded" }
        writtenPts += ptsUs
        return MuxedFrame(written.size.toLong(), ptsUs, output.source).also(written::add)
    }
    @Synchronized fun lastWrittenPtsUs(): Long? = written.lastOrNull()?.encoderPtsUs
    @Synchronized fun captureSequence(): Long = sequence
    @Synchronized fun samples(): List<MuxedFrame> = written.toList()
    @Synchronized fun matchSource(sequence: Long): MuxedFrame? = written.firstOrNull { it.source?.sequence == sequence }
    /** Container rounding is retained rather than compared to submitted/encoder timestamps. */
    @Synchronized fun verifyContainer(samplePtsUs: List<Long>): Boolean = samplePtsUs.size == written.size &&
        samplePtsUs.isNotEmpty() && samplePtsUs.all { it >= 0 } && samplePtsUs.zipWithNext().all { (a, b) -> b >= a }
}
