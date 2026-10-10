package com.tapscene.recording

/**
 * Joins three separate facts: planned presentation, successful EGL submission, and successful
 * nonempty/nonconfig muxer write. The latter two may arrive in either order, but a matching plan
 * must already exist when the codec output is first seen, before native buffer/muxer work begins.
 * An unknown output can never acquire a future plan's identity while that native work is blocked.
 * Producer timestamps identify observations only; they never participate in time arithmetic.
 *
 * Each observation permanently owns its first planned presentation. Later displays of the same
 * pixels contribute video samples/ordinals, but cannot create or repair that observation's evidence
 * binding. All returned values are immutable snapshots; query again after a submission/write race.
 */
internal class RecordingPresentationLedger {
    /** Immutable, ledger-owned callback identity. Creating a copy cannot create a valid token. */
    internal class OutputToken internal constructor(val encoderPtsUs: Long)

    private data class SourceBinding(
        val observation: VideoSourceObservation,
        val firstSampleId: Long,
        val frame: RecordedSourceFrame,
    )

    private data class PlannedSample(val sample: VideoPresentationSample, val source: SourceBinding)
    private data class SeenOutput(val token: OutputToken, val plan: PlannedSample?)

    private val sources = linkedMapOf<Long, SourceBinding>()
    private val plansById = linkedMapOf<Long, PlannedSample>()
    private val plansByPts = mutableMapOf<Long, PlannedSample>()
    private val submittedIds = mutableSetOf<Long>()
    private val seenOutputs = mutableMapOf<Long, SeenOutput>()
    private val written = mutableListOf<MuxedFrame>()
    private val writtenByPts = mutableMapOf<Long, MuxedFrame>()
    private val writtenPlansByPts = mutableMapOf<Long, PlannedSample>()
    private var lastPlan: VideoPresentationSample? = null
    private var lastSeenPtsUs: Long? = null
    private var canonicalMatched = false

    /** Reserve an intent before EGL; the returned source binding is identical across repeats. */
    @Synchronized fun planned(sample: VideoPresentationSample): RecordedSourceFrame {
        check(sample.presentationSampleId > 0 && sample.presentationPtsUs >= 0) { "Invalid presentation identity" }
        check(plansById.size < MAX_SAMPLES) { "Presentation intent budget exceeded" }
        val previous = lastPlan
        check(previous == null || sample.presentationSampleId > previous.presentationSampleId) {
            "Duplicate or reordered presentation identity"
        }
        check(previous == null || sample.presentationPtsUs > previous.presentationPtsUs) {
            "Duplicate or reordered presentation timestamp"
        }

        val observation = sample.observation
        val existing = sources[observation.captureSequence]
        val binding = if (existing != null) {
            check(observation == existing.observation) { "Source identity changed on repeat" }
            check(previous?.observation == observation && sample.repeatedDisplay) {
                "Only the current observed frame can be repeated"
            }
            existing
        } else {
            check(!sample.repeatedDisplay) { "A repeated display cannot invent an observation" }
            previous?.observation?.let { last ->
                check(observation.sourceFrameId > last.sourceFrameId &&
                    observation.captureSequence > last.captureSequence &&
                    observation.sourceTimestampNs > last.sourceTimestampNs) {
                    "Source observations must retain real monotonic identities"
                }
            }
            SourceBinding(observation, sample.presentationSampleId,
                RecordedSourceFrame(observation.captureSequence, observation.sourceTimestampNs, sample.presentationPtsUs))
        }

        // Validate completely before changing state, so a rejected intent cannot move any boundary.
        val planned = PlannedSample(sample, binding)
        sources[observation.captureSequence] = binding
        plansById[sample.presentationSampleId] = planned
        plansByPts[sample.presentationPtsUs] = planned
        lastPlan = sample
        return binding.frame
    }

    /** Call only after eglSwapBuffers has returned success for this exact planned sample. */
    @Synchronized fun submitted(presentationSampleId: Long) {
        val planned = checkNotNull(plansById[presentationSampleId]) { "Unplanned presentation submission" }
        check(submittedIds.add(presentationSampleId)) { "Duplicate presentation submission" }
        if (presentationSampleId == planned.source.firstSampleId &&
            writtenPlansByPts[planned.sample.presentationPtsUs]?.sample?.presentationSampleId == presentationSampleId) {
            canonicalMatched = true
        }
    }

    /** An observed FBO may become a ticket only after its original presentation succeeds. */
    @Synchronized fun canonicalForSource(captureSequence: Long): RecordedSourceFrame? {
        val binding = sources[captureSequence] ?: return null
        return binding.frame.takeIf { binding.firstSampleId in submittedIds }
    }

    /**
     * Call when a nonempty/nonconfig complete codec output arrives, before getOutputBuffer or
     * writeSampleData. Freeze its association now, then release the ledger lock for native work.
     * This does not allocate a video ordinal or prove that any bytes have been written.
     */
    @Synchronized fun outputSeen(ptsUs: Long): OutputToken {
        check(ptsUs >= 0 && ptsUs !in seenOutputs) { "Duplicate or invalid encoder output" }
        check(lastSeenPtsUs?.let { ptsUs > it } != false) { "Reordered encoder output" }
        check(seenOutputs.size < MAX_SAMPLES) { "Encoder output budget exceeded" }
        val token = OutputToken(ptsUs)
        seenOutputs[ptsUs] = SeenOutput(token, plansByPts[ptsUs])
        lastSeenPtsUs = ptsUs
        return token
    }

    /** Call only after this exact token's sample was successfully written to the muxer. */
    @Synchronized fun muxed(token: OutputToken): MuxedFrame {
        val ptsUs = token.encoderPtsUs
        val seen = checkNotNull(seenOutputs[ptsUs]) { "Unobserved encoder output" }
        check(seen.token === token) { "Encoder output token belongs to another observation or ledger" }
        check(ptsUs >= 0 && ptsUs !in writtenByPts) { "Duplicate or invalid encoder sample" }
        check(written.lastOrNull()?.encoderPtsUs?.let { ptsUs > it } != false) { "Reordered encoder sample" }
        check(written.size < MAX_SAMPLES) { "Encoder sample budget exceeded" }
        val sample = MuxedFrame(written.size.toLong(), ptsUs, null)
        written += sample
        writtenByPts[ptsUs] = sample
        // Only the association frozen at callback entry is eligible, never a subsequently added
        // plan, even if EGL has successfully submitted it while writeSampleData was blocked.
        seen.plan?.let { writtenPlansByPts[ptsUs] = it }
        return resolved(sample).also { if (it.source != null) canonicalMatched = true }
    }

    @Synchronized fun matchSource(captureSequence: Long): MuxedFrame? {
        val binding = sources[captureSequence] ?: return null
        if (binding.firstSampleId !in submittedIds) return null
        if (writtenPlansByPts[binding.frame.submittedPtsUs]?.sample?.presentationSampleId != binding.firstSampleId) return null
        val writtenSample = writtenByPts[binding.frame.submittedPtsUs] ?: return null
        return writtenSample.copy(source = binding.frame)
    }

    /** Readiness must use the join, including when EGL submission is the last fact to arrive. */
    @Synchronized fun hasCanonicalMatch(): Boolean = canonicalMatched

    @Synchronized fun lastWrittenPtsUs(): Long? = written.lastOrNull()?.encoderPtsUs

    /** Includes repeats and unknown outputs in actual successful-write order. */
    @Synchronized fun samples(): List<MuxedFrame> = written.map(::resolved)

    /** Container rounding is retained; count/chronology include every actual video sample. */
    @Synchronized fun verifyContainer(samplePtsUs: List<Long>): Boolean =
        samplePtsUs.isNotEmpty() && samplePtsUs.size == written.size &&
            samplePtsUs.all { it >= 0 } && samplePtsUs.zipWithNext().all { (a, b) -> b >= a }

    private fun resolved(writtenSample: MuxedFrame): MuxedFrame {
        val planned = writtenPlansByPts[writtenSample.encoderPtsUs] ?: return writtenSample
        // A repeat is real video, but never a substitute canonical evidence association.
        if (planned.sample.presentationSampleId != planned.source.firstSampleId ||
            planned.sample.presentationSampleId !in submittedIds) return writtenSample
        return writtenSample.copy(source = planned.source.frame)
    }

    private companion object {
        const val MAX_SAMPLES = 12_000
    }
}
