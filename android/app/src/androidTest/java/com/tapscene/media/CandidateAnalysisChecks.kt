package com.tapscene.media

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.data.FrameCandidateStore
import com.tapscene.data.ProjectStep
import com.tapscene.data.StepAsset
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Platform-only checks, isolated private metadata and synthetic pixels; no extra framework. */
object CandidateAnalysisChecks {
    /** Call with an already-owned existing fixture; this method never creates/deletes sources. */
    suspend fun checkDecodedSource(context: Context, source: ImportedSource, actualSourcePtsUs: List<Long>) {
        val decoder = VideoFrameDecoder(context)
        val requests = FrameCandidateAnalyzer.sampleTimes(source.metadata.durationUs)
        val actualMillisecondPts = actualSourcePtsUs.map { it / 1_000L * 1_000L }.toSet()
        var completed = 0
        decoder.sampleFrames(source, requests) { bitmap, actualTimeUs, precisionUs ->
            check(!bitmap.isRecycled && bitmap.config == Bitmap.Config.ARGB_8888)
            check(actualTimeUs in actualMillisecondPts && precisionUs == 1_000L)
            completed++
        }
        check(completed == requests.size)
        val result = FrameCandidateAnalyzer(context).analyze(source)
        check(result.completedSamples == requests.size && result.totalSamples == requests.size)
        check(result.uniqueFrames <= result.completedSamples && result.candidates.size <= 30)
        check(result.candidates.all { it.actualTimeUs in actualMillisecondPts && it.timePrecisionUs == 1_000L })
        check(result.candidates.map { it.actualTimeUs }.distinct().size == result.candidates.size)
        // Cancel inside the borrowed-frame callback, then prove a subsequent session can acquire
        // the same process-wide Surface gate without leaked/abandoned decoder accumulation.
        coroutineScope {
            val cancelled = async {
                decoder.sampleFrames(source, requests) { _, _, _ -> throw CancellationException("synthetic sample cancellation") }
            }
            try { cancelled.await(); error("Expected sample cancellation") }
            catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
        }
        decoder.decode(source, 0).bitmap.recycle()
    }

    fun run(context: Context, status: (String) -> Unit) {
        checkSamplingAndFeatures()
        status("PASS candidates: bounded timestamp sampling, actual PTS deduplication, static/changed pixels and <=30 suggestions")
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "candidate-checks-${UUID.randomUUID()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        try {
            checkStore(isolated)
            status("PASS candidates: SQLite checkpoints, live-run isolation, cancellation/retry, preserved bulk choices, source/version binding and saved-step reconciliation")
        } finally {
            // Only this synthetic invocation's directory; never scan/delete application sources.
            check(root.canonicalFile.parentFile == parent && root.deleteRecursively() && !root.exists())
        }
    }

    private fun checkSamplingAndFeatures() {
        for (duration in listOf(1L, 1_000L, 933_333L, 180_000_000L)) {
            val times = FrameCandidateAnalyzer.sampleTimes(duration)
            check(times.size in 1..FrameCandidateLimits.MAX_SAMPLES)
            check(times.first() == 0L && times.last() == duration - 1)
            check(times.zipWithNext().all { (a, b) -> a < b })
        }
        expectFailure { FrameCandidateAnalyzer.sampleTimes(0) }
        expectFailure { FrameCandidateAnalyzer.sampleTimes(180_000_001L) }
        val shortSource = source(durationUs = 933_333L)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLACK)
            val static = CandidateAccumulator(shortSource, 4)
            check(static.accept(bitmap, 0, 1_000).completedSamples == 1)
            check(static.accept(bitmap, 0, 1_000).uniqueFrames == 1)
            static.accept(bitmap, 233_000, 1_000)
            static.accept(bitmap, 900_000, 1_000)
            val result = static.finish()
            check(result.completedSamples == 4 && result.uniqueFrames == 3 && result.candidates.size == 1)
            check(result.candidates.single().actualTimeUs == 0L && result.candidates.single().timePrecisionUs == 1_000L)

            val vfr = CandidateAccumulator(shortSource, 6)
            val actualPts = listOf(0L, 66_000L, 233_000L, 466_000L, 500_000L, 900_000L)
            actualPts.forEachIndexed { index, time ->
                bitmap.eraseColor(if (index % 2 == 0) Color.BLACK else Color.WHITE)
                vfr.accept(bitmap, time, 1_000)
            }
            val changed = vfr.finish().candidates
            check(changed.size > 1 && changed.all { it.actualTimeUs in actualPts && it.timePrecisionUs == 1_000L })
            check(changed.map { it.id }.distinct().size == changed.size)

            val longSource = source(durationUs = 180_000_000)
            val bounded = CandidateAccumulator(longSource, 361)
            repeat(361) { index ->
                bitmap.eraseColor(if (index % 2 == 0) Color.BLACK else Color.WHITE)
                bounded.accept(bitmap, minOf(179_999_000L, index * 500_000L), 1_000)
            }
            val longResult = bounded.finish()
            check(longResult.completedSamples == 361 && longResult.candidates.size <= 30)
            check(longResult.candidates.map { it.actualTimeUs }.distinct().size == longResult.candidates.size)
            check(longResult.candidates.any { it.actualTimeUs > 150_000_000L }) { "Suggestions must not all be spent near the beginning" }
        } finally { bitmap.recycle() }
    }

    private fun checkStore(context: Context) {
        val project = UUID.randomUUID().toString()
        val otherProject = UUID.randomUUID().toString()
        val source = source()
        val raw = File(context.noBackupFilesDir, source.privateRelativePath)
        check(raw.parentFile!!.mkdir())
        raw.writeText("synthetic-owned-source")
        val original = raw.readBytes()
        val store = FrameCandidateStore(context)
        val secondStore = FrameCandidateStore(context)
        check(store.read(project, source).status == CandidateAnalysisStatus.NOT_STARTED)
        val a = candidate(source, 0)
        val b = candidate(source, 233_000)
        val c = candidate(source, 900_000)
        val run = store.begin(project, source, 4)
        store.checkpoint(run, CandidateAnalysisProgress(1, 4, 1, listOf(a)))
        check(secondStore.read(project, source).status == CandidateAnalysisStatus.RUNNING) { "A second store must not recover a live owner" }
        expectFailure { secondStore.begin(project, source, 4) }
        val independent = secondStore.begin(otherProject, source, 4)
        secondStore.finish(independent, CandidateAnalysisStatus.CANCELLED)
        store.checkpoint(run, CandidateAnalysisProgress(2, 4, 2, listOf(a, b)))
        store.setDecisions(project, source, listOf(a.id, b.id), CandidateDecision.KEPT)
        store.setDecision(project, source, b.id, CandidateDecision.DISMISSED)
        val beforeInvalidEdit = store.read(project, source)
        expectFailure { store.setDecisions(project, source, listOf(a.id, UUID.randomUUID().toString()), CandidateDecision.DISMISSED) }
        check(store.read(project, source) == beforeInvalidEdit) { "Bulk decision was only partially committed" }
        expectFailure { store.checkpoint(run, CandidateAnalysisProgress(1, 4, 1, listOf(a))) }
        check(store.read(project, source) == beforeInvalidEdit) { "Invalid progress mutated the checkpoint" }
        val cancelled = store.finish(run, CandidateAnalysisStatus.CANCELLED)
        check(cancelled.completedSamples == 2 && cancelled.candidates[0].decision == CandidateDecision.KEPT)
        check(secondStore.read(project, source) == cancelled)
        val retry = secondStore.begin(project, source, 4)
        val completed = secondStore.finish(retry, CandidateAnalysisStatus.COMPLETED,
            CandidateAnalysisProgress(4, 4, 3, listOf(a, b, c)))
        check(completed.candidates.map { it.id } == listOf(a.id, b.id, c.id))
        check(completed.candidates[0].decision == CandidateDecision.KEPT && completed.candidates[1].decision == CandidateDecision.DISMISSED)
        expectFailure { store.read(project, source.copy(metadata = source.metadata.copy(sha256 = "b".repeat(64)))) }
        check(store.read(project, source) == completed)

        // Model the genuine ProjectStore rows passed by CandidateWorkspace, including the
        // deterministic reviewed-capture token. This is reconciliation, not a human review test.
        val step = savedStep(source, a)
        val recovered = store.reconcileSavedSteps(project, source, listOf(step))
        check(recovered.candidates.first().usedStepId == step.id)
        check(store.markUsed(project, source, a.id, step.id) == recovered)
        expectFailure { store.markUsed(project, source, a.id, UUID.randomUUID().toString()) }
        expectFailure { store.setDecision(project, source, a.id, CandidateDecision.DISMISSED) }
        val removed = store.reconcileSavedSteps(project, source, emptyList())
        check(removed.candidates.first().usedStepId == null && removed.candidates.first().decision == CandidateDecision.KEPT)

        // Seed a persisted RUNNING journal with no live in-process owner, like process death.
        database(context) { db -> db.execSQL("UPDATE analyses SET status='RUNNING',run_id=? WHERE project_id=?",
            arrayOf(UUID.randomUUID().toString(), project)) }
        check(store.read(project, source).status == CandidateAnalysisStatus.INTERRUPTED)
        check(store.read(project, source).candidates == removed.candidates)
        val currentRun = store.begin(project, source, 40)
        expectFailure { store.finish(run, CandidateAnalysisStatus.CANCELLED) }
        check(secondStore.read(project, source).status == CandidateAnalysisStatus.RUNNING) { "Stale owner cleanup affected a newer run" }
        val many = (0 until 30).map { candidate(source, it * 1_000L) }
        val bounded = store.finish(currentRun, CandidateAnalysisStatus.COMPLETED,
            CandidateAnalysisProgress(40, 40, 30, many))
        check(bounded.candidates.size == 30)
        check(bounded.candidates.first { it.id == b.id }.decision == CandidateDecision.DISMISSED)
        database(context) { db -> db.execSQL("UPDATE analyses SET algorithm_version=2 WHERE project_id=?", arrayOf(project)) }
        expectFailure { store.read(project, source) }
        database(context) { db -> db.execSQL("UPDATE analyses SET algorithm_version=1 WHERE project_id=?", arrayOf(project)) }
        check(store.read(project, source) == bounded)
        check(raw.readBytes().contentEquals(original)) { "Candidate state changed a source file" }
    }

    private fun source(durationUs: Long = 1_000_000L): ImportedSource {
        val id = UUID.randomUUID().toString()
        return ImportedSource(id, "sources/$id.mp4", "synthetic.mp4",
            SourceMetadata("video/avc", 22, "a".repeat(64), 32, 32, 0, durationUs))
    }

    private fun candidate(source: ImportedSource, timeUs: Long) = FrameCandidate(
        FrameCandidateLimits.stableId(source, timeUs), source.sourceId, timeUs, 1_000, 32, 32, 0.5f, CandidateReason.VISUAL_CHANGE,
    )

    private fun savedStep(source: ImportedSource, candidate: FrameCandidate) = ProjectStep(
        UUID.randomUUID().toString(), "synthetic", "", 0, false,
        StepAsset(UUID.randomUUID().toString(), "synthetic.png", "c".repeat(64), 1, 32, 32), source,
        candidate.actualTimeUs, 1_000, emptyList(), emptyList(), "candidate-${candidate.id}",
    )

    private fun database(context: Context, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "frame-candidates.sqlite").path,
            null, SQLiteDatabase.OPEN_READWRITE).use(block)
    }

    private fun expectFailure(block: () -> Unit) { check(runCatching(block).isFailure) { "Expected a guarded failure" } }
}
