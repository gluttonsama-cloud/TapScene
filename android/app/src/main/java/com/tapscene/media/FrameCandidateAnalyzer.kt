package com.tapscene.media

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Offline visual-change suggestions, not clicks, OCR, hotspots or a claim of full coverage.
 * One decoder session, one borrowed output, and a few 32x32 RGB features stay live at a time.
 * Time buckets reserve suggestions across the recording instead of spending all slots on its
 * first animation. Sub-second/low-contrast changes can be missed; manual correction is required.
 */
class FrameCandidateAnalyzer(context: Context) {
    private val decoder = VideoFrameDecoder(context)

    suspend fun analyze(
        source: ImportedSource,
        onProgress: suspend (CandidateAnalysisProgress) -> Unit = {},
    ): CandidateAnalysisProgress = withTimeout(5 * 60_000L) {
        val times = sampleTimes(source.metadata.durationUs)
        val accumulator = CandidateAccumulator(source, times.size)
        decoder.sampleFrames(source, times) { bitmap, actualTimeUs, precisionUs ->
            val progress = withContext(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                accumulator.accept(bitmap, actualTimeUs, precisionUs)
            }
            onProgress(progress)
        }
        accumulator.finish().also { onProgress(it) }
    }

    companion object {
        /** Timestamp spacing only selects seek requests; decoded PTS always determines candidates. */
        fun sampleTimes(durationUs: Long): List<Long> {
            require(durationUs in 1..MediaLimits.MAX_DURATION_US) { "候选分析只支持三分钟内的录屏。" }
            val last = durationUs - 1
            val intervals = ((last + FrameCandidateLimits.TARGET_INTERVAL_US - 1) /
                FrameCandidateLimits.TARGET_INTERVAL_US).toInt().coerceIn(1, FrameCandidateLimits.MAX_SAMPLES - 1)
            return (0..intervals).map { last * it / intervals }.distinct()
        }
    }
}

/** Kept internal for platform-only synthetic checks; retains no full-size bitmap. */
internal class CandidateAccumulator(private val source: ImportedSource, private val total: Int) {
    private val candidates = mutableListOf<FrameCandidate>()
    private val seenPts = HashSet<Long>() // Bounded by MAX_SAMPLES, not video frame count.
    private var previous: IntArray? = null
    private var lastAccepted: IntArray? = null
    private var lastFeature: IntArray? = null
    private var lastFrame: FrameCandidate? = null
    private var bucketBest: FrameCandidate? = null
    private var bucketFeature: IntArray? = null
    private var bucket = -1
    private var completed = 0

    init { require(total in 1..FrameCandidateLimits.MAX_SAMPLES) }

    fun accept(bitmap: Bitmap, actualTimeUs: Long, precisionUs: Long): CandidateAnalysisProgress {
        check(completed < total) { "采样完成量超出预算。" }
        require(precisionUs == 1_000L && actualTimeUs in 0..source.metadata.durationUs && actualTimeUs % precisionUs == 0L)
        completed++
        if (!seenPts.add(actualTimeUs)) return progress()
        val feature = features(bitmap)
        val score = previous?.let { difference(it, feature) } ?: 0f
        val frame = FrameCandidate(FrameCandidateLimits.stableId(source, actualTimeUs), source.sourceId,
            actualTimeUs, precisionUs, bitmap.width, bitmap.height, score, CandidateReason.VISUAL_CHANGE)
        if (candidates.isEmpty()) {
            candidates += frame.copy(reason = CandidateReason.FIRST_FRAME)
            lastAccepted = feature
        } else {
            val nextBucket = ((actualTimeUs * CHANGE_BUCKETS) / source.metadata.durationUs)
                .toInt().coerceIn(0, CHANGE_BUCKETS - 1)
            if (nextBucket != bucket) {
                flushBucket()
                bucket = nextBucket
            }
            // Compare adjacent samples and accumulated change since the last emitted suggestion.
            val accumulated = lastAccepted?.let { difference(it, feature) } ?: score
            val change = max(score, accumulated)
            if (change >= CHANGE_THRESHOLD && change > (bucketBest?.changeScore ?: -1f)) {
                bucketBest = frame.copy(changeScore = change)
                bucketFeature = feature
            }
        }
        previous = feature
        lastFeature = feature
        lastFrame = frame
        return progress()
    }

    fun finish(): CandidateAnalysisProgress {
        check(completed == total) { "画面采样尚未完成。" }
        flushBucket()
        val last = lastFrame
        val finalFeature = lastFeature
        val emittedFeature = lastAccepted
        // An identical last image or a duplicate returned PTS adds no suggestion.
        if (last != null && finalFeature != null && emittedFeature != null &&
            candidates.none { it.actualTimeUs == last.actualTimeUs } &&
            difference(emittedFeature, finalFeature) >= LAST_FRAME_THRESHOLD
        ) candidates += last.copy(reason = CandidateReason.LAST_FRAME,
            changeScore = difference(emittedFeature, finalFeature))
        check(candidates.size <= FrameCandidateLimits.MAX_CANDIDATES)
        return progress()
    }

    private fun flushBucket() {
        val best = bucketBest
        if (best != null && candidates.size < FrameCandidateLimits.MAX_CANDIDATES - 1 &&
            candidates.none { it.actualTimeUs == best.actualTimeUs }
        ) {
            candidates += best
            lastAccepted = bucketFeature
        }
        bucketBest = null
        bucketFeature = null
    }

    private fun progress() = CandidateAnalysisProgress(completed, total, seenPts.size,
        candidates.sortedBy { it.actualTimeUs })

    private fun features(bitmap: Bitmap): IntArray {
        val edge = FrameCandidateLimits.FEATURE_EDGE
        val small = Bitmap.createScaledBitmap(bitmap, edge, edge, true)
        return try { IntArray(edge * edge).also { small.getPixels(it, 0, edge, 0, 0, edge, edge) } }
        finally { if (small !== bitmap) small.recycle() }
    }

    private fun difference(a: IntArray, b: IntArray): Float {
        val tileSums = IntArray(16)
        var sum = 0
        val edge = FrameCandidateLimits.FEATURE_EDGE
        for (index in a.indices) {
            val difference = abs((a[index] shr 16 and 255) - (b[index] shr 16 and 255)) +
                abs((a[index] shr 8 and 255) - (b[index] shr 8 and 255)) +
                abs((a[index] and 255) - (b[index] and 255))
            sum += difference
            tileSums[(index / edge / 8) * 4 + index % edge / 8] += difference
        }
        // Local changes (dialogs, keyboard regions) should not disappear in a global average.
        return (0.65f * sum / (a.size * 765f) + 0.35f * tileSums.max() / (64 * 765f)).coerceIn(0f, 1f)
    }

    private companion object {
        const val CHANGE_BUCKETS = FrameCandidateLimits.MAX_CANDIDATES - 2
        const val CHANGE_THRESHOLD = 0.07f
        const val LAST_FRAME_THRESHOLD = 0.025f
    }
}
