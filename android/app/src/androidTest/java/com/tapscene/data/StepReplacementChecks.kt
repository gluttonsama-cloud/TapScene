package com.tapscene.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Incremental replacement checks using real SQLite/PNG and one already-validated AVC fixture.
 * Synthetic review records exercise persistence, not human privacy or full-playback review. */
object StepReplacementChecks {
    suspend fun run(context: Context, source: ImportedSource, output: SafeMediaWriter.CandidateMedia,
        startUs: Long, endUs: Long, masks: List<OpaqueMask>, status: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "step-replacement-checks-${id()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        var failure: Throwable? = null
        try {
            val localSource = source.copy(sourceId = id()).let { it.copy(privateRelativePath = "sources/${it.sourceId}.mp4") }
            val original = File(parent, source.privateRelativePath)
            val copied = File(root, localSource.privateRelativePath)
            check(copied.parentFile!!.mkdir()); original.copyTo(copied)
            val clip = output.file.copyTo(File(root, "reviewed-transition.mp4"))
            val transition = ReviewedTransitionInput(clip, output.sha256, output.width, output.height,
                checkNotNull(output.durationUs), localSource, startUs, endUs, masks, "replacement-transition")
            checkGraph(isolated, localSource, transition, status)
            checkCommitBoundary(isolated, localSource, status)
            check(ViewerPackageCodec.sha256(copied) == source.metadata.sha256)
            check(ViewerPackageCodec.sha256(clip) == output.sha256)
            checkClean(isolated)
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                check(root.canonicalFile.parentFile == parent)
                check(root.deleteRecursively() && !root.exists())
            }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private suspend fun checkGraph(context: Context, source: ImportedSource,
        transition: ReviewedTransitionInput, status: (String) -> Unit) {
        val store = ProjectStore(context)
        val p = store.createProject("Replace existing step", "Keep authored graph").project.id
        val input = png(context, source, 0)
        val a = store.addReviewedStep(p, input.copy(captureId = "a"), "Authored A", "Keep this description").steps.single().id
        val b = store.addReviewedStep(p, input.copy(captureId = "b"), "B").steps.last().id
        val c = store.addReviewedStep(p, input.copy(captureId = "c"), "C").steps.last().id
        val d = store.addReviewedStep(p, input.copy(captureId = "d"), "Terminal D").steps.last().id
        store.setTerminal(p, d, true)
        for ((from, target) in listOf(a to b, b to a, c to d)) {
            val fresh = checkNotNull(store.readProject(p))
            val step = fresh.steps.single { it.id == from }
            store.saveStepDraft(p, from, step.title, step.description, false, step.hotspots,
                fresh.project.revision, ProjectNextAction(id(), "Continue $from", target))
        }
        val rect = OpaqueMask(.1f, .2f, .6f, .7f)
        for ((from, target) in listOf(a to a, b to a, c to d)) {
            store.saveHotspot(p, from, label = "Branch $from", rect = rect, targetStepId = target)
        }
        store.saveHotspot(p, a, label = "Finish", rect = rect, endLabel = "Done")
        var draft = store.reorderSteps(p, listOf(c, a, b, d))
        val edges = draft.steps.flatMap { step ->
            step.hotspots.filter { it.targetStepId != null }.map { it.edgeId } + listOfNotNull(step.nextAction?.id)
        }
        for (edge in edges) draft = store.bindReviewedTransition(p, edge, draft.project.revision,
            transition.copy(reviewId = "replacement-$edge"))
        for (stepId in listOf(a, c)) {
            draft = store.saveRegion(p, stepId, null, "Keep region", "Group", RegionBox(2, 3, 9, 11),
                4, .25, .75, draft.project.revision)
            val regionId = draft.steps.single { it.id == stepId }.regions.single().id
            draft = store.generateRegion(p, regionId, draft.project.revision)
            val crop = checkNotNull(draft.steps.single { it.id == stepId }.regions.single().asset)
            draft = store.reviewRegion(p, regionId, draft.project.revision, crop.sha256)
        }
        val before = draft
        val originalFiles = assetDigests(context)
        val incoming = before.steps.single { it.id == b }
        val unaffected = before.steps.single { it.id == c }
        check(incoming.nextAction?.transition != null && incoming.hotspots.single().transition != null)
        check(unaffected.nextAction?.transition != null && unaffected.hotspots.single().transition != null)
        val replacement = png(context, source, 1)
        rejected { store.replaceReviewedStep(p, a, before.project.revision - 1, replacement) }
        rejected { store.replaceReviewedStep(p, a, before.project.revision, replacement.copy(sha256 = "0".repeat(64))) }
        rejected { store.replaceReviewedStep(p, a, before.project.revision, input.copy(captureId = "b")) }
        coroutineScope {
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                store.replaceReviewedStep(p, a, before.project.revision, replacement)
            }
            check(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
        }
        check(store.readProject(p) == before && assetDigests(context) == originalFiles)
        checkClean(context)
        status("PASS step replacement rejection: stale revision, mismatched SHA, another step's capture token and pre-commit cancellation preserve graph and every derived file")

        val oldBase = store.resolveAsset(p, a)
        val oldRegion = store.regionFile(p, before.steps.single { it.id == a }.regions.single().id)
        val oldClips = edges.associateWith { store.resolveTransition(p, it) }
        val after = store.replaceReviewedStep(p, a, before.project.revision, replacement)
        check(after.project.revision == before.project.revision + 1)
        check(after.project == before.project.copy(revision = after.project.revision, updatedAt = after.project.updatedAt))
        val changed = after.steps.single { it.id == a }
        check(changed.asset.id != before.steps.single { it.id == a }.asset.id && changed.asset.sha256 == replacement.sha256)
        check(after.steps == before.steps.map { step ->
            val expected = step.copy(
                hotspots = step.hotspots.map { spot -> if (step.id == a || spot.targetStepId == a) spot.copy(transition = null) else spot },
                nextAction = step.nextAction?.let { action -> if (step.id == a || action.targetStepId == a) action.copy(transition = null) else action },
                regions = if (step.id == a) step.regions.map { it.copy(asset = null, reviewedAt = null) } else step.regions,
            )
            if (step.id == a) expected.copy(asset = changed.asset, origin = replacement.origin,
                masks = replacement.masks, captureId = replacement.captureId) else expected
        }) { "Replacement changed authored IDs, text, order, targets, terminal flags or unrelated outputs" }
        val staleRegion = changed.regions.single()
        check(staleRegion.stale && !staleRegion.matchesBase(changed.asset))
        rejected { store.regionFile(p, staleRegion.id) }
        check(ProjectStore(context).readProject(p) == after)
        check(!oldBase.exists() && !oldRegion.exists())
        for ((edge, file) in oldClips) {
            val kept = edge == unaffected.nextAction?.id || edge == unaffected.hotspots.single().edgeId
            check(file.exists() == kept)
            if (kept) check(store.resolveTransition(p, edge) == file) else rejected { store.resolveTransition(p, edge) }
        }
        for (step in after.steps.filter { it.id != a }) {
            check(ViewerPackageCodec.sha256(store.resolveAsset(p, step.id)) == step.asset.sha256)
        }
        val retainedRegion = unaffected.regions.single()
        check(ViewerPackageCodec.sha256(store.regionFile(p, retainedRegion.id)) == checkNotNull(retainedRegion.asset).sha256)
        val committedFiles = assetDigests(context)
        check(store.replaceReviewedStep(p, a, before.project.revision, replacement) == after)
        rejected { store.replaceReviewedStep(p, a, after.project.revision,
            replacement.copy(origin = checkNotNull(replacement.videoOrigin).let { it.copy(frameTimeUs = it.frameTimeUs + 1_000) })) }
        check(store.readProject(p) == after && assetDigests(context) == committedFiles)
        check(ViewerPackageCodec.sha256(input.file) == input.sha256 && ViewerPackageCodec.sha256(replacement.file) == replacement.sha256)
        status("PASS step replacement graph: stable step/action/hotspot IDs, text, order, start/terminal and targets; incoming/outgoing/self-loop clips and own crop invalidated; unrelated outputs retained; same-capture retry is idempotent")
    }

    /** A real committed SQLite change is observed while Main cannot dispatch its IO result. */
    private suspend fun checkCommitBoundary(context: Context, source: ImportedSource, status: (String) -> Unit) = coroutineScope {
        val store = ProjectStore(context)
        val p = store.createProject("Terminal replacement").project.id
        val original = png(context, source, 2)
        val stepId = store.addReviewedStep(p, original, "Finish", "Terminal text").steps.single().id
        val before = store.setTerminal(p, stepId, true)
        val releases = ReleaseStore(context)
        var candidate = releases.createCandidate(p, before.project.revision)
        candidate = releases.reviewState(candidate.id, candidate.contentDigest, stepId)
        candidate = releases.reviewSummary(candidate.id, candidate.contentDigest)
        candidate = releases.reviewFileList(candidate.id, candidate.contentDigest)
        candidate = releases.recordTraversal(candidate.id, candidate.contentDigest, null, false)
        val sealed = releases.seal(candidate.id, candidate.contentDigest)
        val sealedScene = ViewerPackageCodec.writeScene(candidate.scene)
        val replacement = png(context, source, 3)
        var returned = false
        val committed = CountDownLatch(1)
        val operation = async(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            withContext(Dispatchers.IO) { store.replaceReviewedStep(p, stepId, before.project.revision, replacement) }
            returned = true
        }
        val observer = async(Dispatchers.IO) {
            withTimeout(5_000) {
                while (checkNotNull(store.readProject(p)).project.revision == before.project.revision) delay(1)
            }
            committed.countDown()
        }
        withContext(Dispatchers.Main.immediate) {
            operation.start()
            try { check(committed.await(5, TimeUnit.SECONDS)) { "Replacement did not reach its commit point" } }
            finally { operation.cancel() }
        }
        observer.await()
        check(runCatching { operation.await() }.exceptionOrNull() is CancellationException && !returned)
        val actual = checkNotNull(ProjectStore(context).readProject(p))
        val terminal = actual.steps.single()
        check(actual.project.revision == before.project.revision + 1 && actual.project.startStepId == stepId)
        check(terminal == before.steps.single().copy(asset = terminal.asset, origin = replacement.origin,
            masks = replacement.masks, captureId = replacement.captureId))
        check(terminal.asset.sha256 == replacement.sha256 && terminal.isTerminal)
        check(store.replaceReviewedStep(p, stepId, before.project.revision, replacement) == actual)
        check(ViewerPackageCodec.writeScene(releases.loadRelease(sealed.id)).contentEquals(sealedScene))
        check(ViewerPackageCodec.sha256(releases.releaseAssetFile(sealed.id, stepId)) == original.sha256)
        status("PASS step replacement commit boundary: cancellation before Main result delivery keeps committed terminal/start step; durable reread and old-revision retry reconcile once; sealed scene and original PNG remain unchanged")
        status("NOT_COVERED step replacement: actual process-kill timing, Compose gestures and human privacy review require separate device/UI checks")
    }

    private suspend fun png(context: Context, source: ImportedSource, offset: Int): ReviewedStepInput {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        for (y in 0 until 48) for (x in 0 until 32) bitmap.setPixel(x, y, Color.rgb(x * 7, y * 5, offset * 60))
        val masks = if (offset % 2 == 0) emptyList() else listOf(OpaqueMask(.25f, .25f, .75f, .75f))
        return try {
            val output = SafeMediaWriter(context).writePng(bitmap, masks, File(context.noBackupFilesDir, "candidate-${id()}"))
            ReviewedStepInput(output.file, output.sha256, output.width, output.height, source, offset * 1_000L,
                1_000, masks, "replacement-${id()}")
        } finally { bitmap.recycle() }
    }

    private fun assetDigests(context: Context): Map<String, String> = File(context.noBackupFilesDir, "project-assets")
        .walkTopDown().filter { it.isFile }.associate { it.relativeTo(context.noBackupFilesDir).path to ViewerPackageCodec.sha256(it) }

    private fun checkClean(context: Context) {
        check(File(context.noBackupFilesDir, "project-staging").listFiles().orEmpty().isEmpty())
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "projects.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
            for (table in listOf("asset_imports", "transition_imports", "asset_cleanup")) {
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
            }
        }
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        check(error is IllegalArgumentException || error is IllegalStateException) { "Replacement did not fail closed: $error" }
    }
    private fun id() = UUID.randomUUID().toString()
}
