package com.tapscene.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.media.CandidateAnalysisProgress
import com.tapscene.media.CandidateAnalysisStatus
import com.tapscene.media.CandidateDecision
import com.tapscene.media.CandidateReason
import com.tapscene.media.FrameCandidate
import com.tapscene.media.FrameCandidateLimits
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import com.tapscene.ocr.OfflineOcrEngine
import com.tapscene.ocr.OcrResult
import com.tapscene.ocr.OcrWord
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Real Android SQLite checks; synthetic text/PNG only, no recognizer, user media or network. */
object CandidateOcrChecks {
    suspend fun run(context: Context, report: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "candidate-ocr-checks-${UUID.randomUUID()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        var failure: Throwable? = null
        try {
            checkStore(isolated, report)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanup = runCatching {
                check(root.canonicalFile.parentFile == parent && root.deleteRecursively() && !root.exists())
            }.exceptionOrNull()
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private suspend fun checkStore(context: Context, report: (String) -> Unit) {
        val projects = ProjectStore(context)
        val projectId = projects.createProject("人工项目名", "人工目标").project.id
        val source = syntheticSource(context)
        val raw = File(context.noBackupFilesDir, source.privateRelativePath)
        val sourceBytes = raw.readBytes()
        val a = candidate(source, 0)
        val b = candidate(source, 233_000)
        val targets = listOf(a, b)
        val step = addSyntheticReviewedStep(context, projects, projectId, source, a)
        projects.saveHotspot(projectId, step.id, label = "人工结束标签", rect = OpaqueMask(0.1f, 0.1f, 0.5f, 0.5f), endLabel = "完成")
        val frames = FrameCandidateStore(context)
        val analysis = frames.begin(projectId, source, 2)
        frames.finish(analysis, CandidateAnalysisStatus.COMPLETED, CandidateAnalysisProgress(2, 2, 2, targets))
        frames.markUsed(projectId, source, a.id, step.id)
        frames.setDecision(projectId, source, b.id, CandidateDecision.DISMISSED)
        val authoredBefore = checkNotNull(projects.readProject(projectId))
        val framesBefore = frames.read(projectId, source)

        val store = CandidateOcrStore(context)
        val second = CandidateOcrStore(context)
        checkEmpty(store.read(projectId, source, targets))
        val first = store.begin(projectId, source, targets)
        check(store.progress(first).let { it.status == CandidateOcrStatus.RUNNING && it.completed == 0 && it.total == 2 })
        val recognized = result()
        val savedA = store.saveFrame(first, a, recognized)
        check(savedA.completed == 1 && savedA.results[a.id] == recognized)
        check(second.read(projectId, source, targets).status == CandidateOcrStatus.RUNNING)
        expectFailure { second.begin(projectId, source, targets) }
        expectFailure { store.saveFrame(first, b, recognized.copy(width = 31)) }
        expectFailure { store.saveFrame(first, b, recognized.copy(engineVersion = "other-engine")) }
        expectFailure { store.saveFrame(first, b, recognized.copy(modelVersion = "other-model")) }
        expectFailure { store.saveFrame(first, b, recognized.copy(words = listOf(recognized.words.single().copy(right = 33)))) }
        expectFailure { store.saveFrame(first, b, recognized.copy(words = listOf(recognized.words.single().copy(confidence = Float.NaN)))) }
        expectFailure { store.saveFrame(first, a.copy(actualTimeUs = 1_000), recognized) }
        expectFailure { store.saveFrame(first, candidate(source, 900_000), recognized) }
        check(store.progress(first) == savedA) { "An invalid frame partially changed OCR state" }
        val failed = store.finish(first, CandidateOcrStatus.FAILED)
        check(failed.completed == 1 && failed.results == savedA.results)
        check(projects.readProject(projectId) == authoredBefore && frames.read(projectId, source) == framesBefore) {
            "OCR failure changed authored steps, titles, hotspots or candidate choices"
        }
        report("PASS OCR store: atomic valid frame, rejected mismatched results, live-owner isolation and unchanged authored data")

        val retry = second.begin(projectId, source, targets)
        check(second.progress(retry).let { it.completed == 1 && it.results[a.id] == recognized && b.id !in it.results })
        val noText = OcrResult(32, 32, emptyList(), truncated = false)
        second.saveFrame(retry, b, noText)
        val completed = second.finish(retry, CandidateOcrStatus.COMPLETED)
        check(completed.completed == 2 && completed.total == 2 && completed.results[b.id]?.words?.isEmpty() == true)
        expectFailure { store.finish(first, CandidateOcrStatus.CANCELLED) }
        check(store.read(projectId, source, targets) == completed)
        val interrupted = store.begin(projectId, source, targets)
        store.abandon(interrupted) // A lost in-process owner models persisted RUNNING after process death.
        check(second.read(projectId, source, targets).let { it.status == CandidateOcrStatus.INTERRUPTED && it.results == completed.results })
        val cached = second.begin(projectId, source, targets)
        check(second.progress(cached).completed == 2) { "Retry lost valid cached frames" }
        second.finish(cached, CandidateOcrStatus.COMPLETED)
        val cancelled = second.begin(projectId, source, targets)
        cancelled.cancellation.cancel()
        second.finish(cancelled, CandidateOcrStatus.CANCELLED)
        check(store.read(projectId, source, targets).results == completed.results)
        report("PASS OCR store: retry cache, valid empty result, cancellation retention and abandoned RUNNING recovery")

        check(store.read(UUID.randomUUID().toString(), source, targets).results.isEmpty())
        val changedSha = source.copy(metadata = source.metadata.copy(sha256 = "b".repeat(64)))
        check(store.read(projectId, changedSha, listOf(candidate(changedSha, 0))).results.isEmpty())
        val otherSourceId = UUID.randomUUID().toString()
        val changedId = source.copy(sourceId = otherSourceId, privateRelativePath = "sources/$otherSourceId.mp4")
        check(store.read(projectId, changedId, listOf(candidate(changedId, 0))).results.isEmpty())
        check(store.read(projectId, source, listOf(candidate(source, 1_000))).results.isEmpty())
        check(store.read(projectId, source, listOf(a.copy(width = 16))).results.isEmpty())
        expectFailure { store.read(projectId, source, listOf(a.copy(actualTimeUs = 1_000))) }
        expectFailure { store.read(projectId, source, listOf(a.copy(timePrecisionUs = 1))) }
        expectFailure { store.begin(projectId, source, List(31) { candidate(source, it * 1_000L) }) }
        database(context) { db ->
            db.execSQL("UPDATE results SET engine_version='previous-engine',model_version='previous-model' WHERE project_id=?", arrayOf(projectId))
            db.execSQL("UPDATE jobs SET engine_version='previous-engine',model_version='previous-model' WHERE project_id=?", arrayOf(projectId))
        }
        checkEmpty(store.read(projectId, source, targets))
        database(context) { db ->
            val args = arrayOf(OfflineOcrEngine.ENGINE_VERSION, OfflineOcrEngine.MODEL_VERSION, projectId)
            db.execSQL("UPDATE results SET engine_version=?,model_version=? WHERE project_id=?", args)
            db.execSQL("UPDATE jobs SET engine_version=?,model_version=? WHERE project_id=?", args)
        }
        check(store.read(projectId, source, targets).results == completed.results)
        check(raw.delete())
        check(store.read(projectId, source, targets).results == completed.results) { "Missing source discarded raw text" }
        raw.writeBytes(sourceBytes)
        check(projects.readProject(projectId) == authoredBefore && frames.read(projectId, source) == framesBefore)
        report("PASS OCR store: project/source SHA/PTS/precision/geometry/model binding, hard candidate limit and missing-source retention")

        val liveAtSourceDelete = store.begin(projectId, source, targets)
        coroutineScope {
            var displayed = emptyMap<String, OcrResult>()
            check(store.applyIfCurrent(completed, projectId, source) { displayed = completed.results })
            check(displayed == completed.results)
            check(!store.applyIfCurrent(completed, UUID.randomUUID().toString(), source) { error("Wrong project applied") })
            check(!store.applyIfCurrent(completed, projectId, changedSha) { error("Wrong source SHA applied") })
            val cleared = CompletableDeferred<Unit>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                store.invalidations.collect { invalidation ->
                    if (invalidation.affects(projectId, source.sourceId)) {
                        displayed = emptyMap()
                        cleared.complete(Unit)
                    }
                }
            }
            try {
                second.deleteSource(source.sourceId)
                withTimeout(5_000) { cleared.await() }
                check(displayed.isEmpty()) { "Deletion notification retained already displayed OCR" }
                // This snapshot was returned by finish before deletion, after its lease was
                // released. A late UI callback must not revive its private text.
                check(!store.applyIfCurrent(completed, projectId, source) { displayed = completed.results })
                check(!store.applyIfCurrent(savedA, projectId, source) { displayed = savedA.results })
                check(displayed.isEmpty() && second.isInvalidated(projectId, source.sourceId))
            } finally { observer.cancelAndJoin() }
        }
        checkColdDeletionFence(context, projectId, source, targets)
        check(liveAtSourceDelete.cancellation.isCancelled)
        expectFailure { store.saveFrame(liveAtSourceDelete, a, recognized) }
        expectFailure { store.finish(liveAtSourceDelete, CandidateOcrStatus.COMPLETED) }
        expectFailure { store.begin(projectId, source, targets) }
        checkEmpty(store.read(projectId, source, targets))
        check(raw.readBytes().contentEquals(sourceBytes)) { "OCR cleanup deleted source bytes" }
        check(projects.readProject(projectId) == authoredBefore && frames.read(projectId, source) == framesBefore)

        val anotherSource = syntheticSource(context)
        val anotherCandidate = candidate(anotherSource, 0)
        val otherProject = projects.createProject("独立项目").project.id
        val independent = second.begin(otherProject, anotherSource, listOf(anotherCandidate))
        second.saveFrame(independent, anotherCandidate, recognized)
        val independentSnapshot = second.finish(independent, CandidateOcrStatus.COMPLETED)
        val liveAtProjectDelete = store.begin(projectId, anotherSource, listOf(anotherCandidate))
        store.saveFrame(liveAtProjectDelete, anotherCandidate, recognized)
        val beforeProjectDelete = store.progress(liveAtProjectDelete)
        second.deleteProject(projectId)
        check(!store.applyIfCurrent(beforeProjectDelete, projectId, anotherSource) { error("Deleted project snapshot applied") })
        check(store.applyIfCurrent(independentSnapshot, otherProject, anotherSource) { /* Unrelated epoch change is safe. */ })
        check(store.invalidations.value.let { source.sourceId in it.sourceIds && projectId in it.projectIds }) {
            "Conflated deletion notifications lost an older source fence"
        }
        check(liveAtProjectDelete.cancellation.isCancelled)
        expectFailure { store.saveFrame(liveAtProjectDelete, anotherCandidate, recognized) }
        expectFailure { store.finish(liveAtProjectDelete, CandidateOcrStatus.COMPLETED) }
        expectFailure { store.begin(projectId, anotherSource, listOf(anotherCandidate)) }
        checkEmpty(store.read(projectId, anotherSource, listOf(anotherCandidate)))
        check(second.read(otherProject, anotherSource, listOf(anotherCandidate)).results[anotherCandidate.id] == recognized)
        check(projects.readProject(projectId) == authoredBefore && frames.read(projectId, source) == framesBefore)
        report("PASS OCR cleanup: exact rows, revoked runs, notified displayed text, rejected late completed snapshots and cold-start fences; other project preserved")
    }

    private fun checkEmpty(snapshot: CandidateOcrSnapshot) {
        check(snapshot.status == CandidateOcrStatus.NOT_STARTED && snapshot.completed == 0 && snapshot.total == 0 && snapshot.results.isEmpty())
        check(snapshot.scope != null) { "Store snapshots must carry their exact scope" }
    }

    private fun checkColdDeletionFence(context: Context, projectId: String, source: ImportedSource, targets: List<FrameCandidate>) {
        // Copy a quiescent DELETE-journal database into a new isolated root, giving it a
        // fresh process-local registry. Only the persisted fence can invalidate this scope.
        val coldRoot = File(context.noBackupFilesDir, "cold-store-${UUID.randomUUID()}")
        check(coldRoot.mkdir())
        File(context.noBackupFilesDir, "candidate-ocr.sqlite").copyTo(File(coldRoot, "candidate-ocr.sqlite"))
        val coldContext = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = coldRoot
        }
        val coldStore = CandidateOcrStore(coldContext)
        check(!coldStore.isInvalidated(projectId, source.sourceId))
        val recovered = coldStore.read(projectId, source, targets)
        checkEmpty(recovered)
        check(coldStore.isInvalidated(projectId, source.sourceId) && coldStore.invalidations.value.epoch > 0)
        check(!coldStore.applyIfCurrent(recovered, projectId, source) { error("Cold deleted scope applied") })
        expectFailure { coldStore.begin(projectId, source, targets) }
    }

    private fun syntheticSource(context: Context): ImportedSource {
        val id = UUID.randomUUID().toString()
        val relative = "sources/$id.mp4"
        val file = File(context.noBackupFilesDir, relative)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdir())
        check(file.createNewFile())
        file.writeText("Synthetic OCR ownership fixture, not a playable video.")
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return ImportedSource(id, relative, "synthetic-ocr.mp4", SourceMetadata("video/avc", file.length(), hash, 32, 32, 0, 1_000_000L))
    }

    private fun candidate(source: ImportedSource, timeUs: Long) = FrameCandidate(
        FrameCandidateLimits.stableId(source, timeUs), source.sourceId, timeUs, 1_000, 32, 32, 0.5f, CandidateReason.VISUAL_CHANGE,
    )

    private fun result() = OcrResult(32, 32, listOf(OcrWord("合成文字", 2, 3, 28, 18, 90f, 0, 0)), false)

    private suspend fun addSyntheticReviewedStep(context: Context, projects: ProjectStore, projectId: String,
        source: ImportedSource, candidate: FrameCandidate): ProjectStep {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        val output = try {
            SafeMediaWriter(context).writePng(bitmap, emptyList(), File(context.noBackupFilesDir, "candidates/${UUID.randomUUID()}"))
        } finally { bitmap.recycle() }
        // Technical synthetic setup only. This does not claim an actual human privacy review.
        val input = ReviewedStepInput(output.file, output.sha256, 32, 32, source,
            candidate.actualTimeUs, candidate.timePrecisionUs, emptyList(), "candidate-${candidate.id}")
        return projects.addReviewedStep(projectId, input, "人工标题保留", "人工说明保留").steps.single()
    }

    private fun database(context: Context, block: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "candidate-ocr.sqlite").path,
            null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun expectFailure(block: () -> Unit) { check(runCatching(block).isFailure) { "Expected a guarded OCR failure" } }
}
