package com.tapscene.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.TransitionUiState
import com.tapscene.ui.TransitionWorkspace
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Reuses one actual smoke-test output. This is technical lifecycle coverage, not human review. */
object TransitionStoreChecks {
    suspend fun run(context: Context, source: ImportedSource, output: SafeMediaWriter.CandidateMedia,
        startUs: Long, endUs: Long, masks: List<OpaqueMask>, status: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "transition-checks-${id()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        var failure: Throwable? = null
        try {
            checkLifecycle(isolated, File(parent, source.privateRelativePath), source, output, startUs, endUs, masks, status)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanup = runCatching {
                check(root.canonicalFile.parentFile == parent)
                check(root.deleteRecursively() && !root.exists())
            }.exceptionOrNull()
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private suspend fun checkLifecycle(context: Context, original: File, source: ImportedSource,
        output: SafeMediaWriter.CandidateMedia, startUs: Long, endUs: Long, masks: List<OpaqueMask>, status: (String) -> Unit) {
        val root = context.noBackupFilesDir
        check(File(root, "sources").mkdir())
        fun copySource(): ImportedSource {
            val sourceId = id()
            val path = "sources/$sourceId.mp4"
            original.copyTo(File(root, path))
            return source.copy(sourceId = sourceId, privateRelativePath = path)
        }
        val stepSource = copySource()
        val transitionSource = copySource()
        val video = output.file.copyTo(File(root, "reviewed.mp4"))
        val input = ReviewedTransitionInput(video, output.sha256, output.width, output.height,
            checkNotNull(output.durationUs), transitionSource, startUs, endUs, masks, "transition-fixture")
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = try { SafeMediaWriter(context).writePng(bitmap, emptyList(), File(root, "png-candidate")) }
            finally { bitmap.recycle() }
        val stepInput = ReviewedStepInput(png.file, png.sha256, png.width, png.height, stepSource, 0, 1_000, emptyList())
        val projects = ProjectStore(context)
        val p = projects.createProject("Video lifecycle").project.id
        val a = projects.addReviewedStep(p, stepInput.copy(captureId = "a"), "A").steps.single().id
        val b = projects.addReviewedStep(p, stepInput.copy(captureId = "b"), "B").steps.last().id
        var draft = checkNotNull(projects.readProject(p))
        draft = projects.connectStepsInOrder(p, listOf(a, b), true, draft.project.revision)
        val edgeId = checkNotNull(draft.steps.first { it.id == a }.nextAction).id
        val prior = draft
        rejected { projects.bindReviewedTransition(p, edgeId, draft.project.revision, input.copy(durationUs = 10_000_001)) }
        rejected { projects.bindReviewedTransition(p, edgeId, draft.project.revision, input.copy(sha256 = "0".repeat(64))) }
        check(projects.readProject(p) == prior && !projects.isSourceReferenced(transitionSource.sourceId))
        draft = projects.bindReviewedTransition(p, edgeId, draft.project.revision, input)
        val saved = checkNotNull(draft.steps.first { it.id == a }.nextAction?.transition)
        val savedFile = projects.resolveTransition(p, edgeId)
        check(savedFile != video && saved.asset.durationUs == output.durationUs)
        check(projects.isSourceReferenced(transitionSource.sourceId))
        check(projects.bindReviewedTransition(p, edgeId, prior.project.revision, input) == draft)
        val beforeOrder = saved.asset
        draft = projects.reorderSteps(p, listOf(b, a))
        check(draft.steps.first { it.id == a }.nextAction?.transition?.asset == beforeOrder)
        val renamedActionStep = draft.steps.first { it.id == a }
        draft = projects.saveStepDraft(p, a, renamedActionStep.title, renamedActionStep.description, false,
            renamedActionStep.hotspots, draft.project.revision, checkNotNull(renamedActionStep.nextAction).copy(label = "Continue"))
        check(draft.steps.first { it.id == a }.nextAction?.transition?.asset == beforeOrder)
        seedRecovery(context, projects, p, saved, video)
        status("PASS transition persistence: actual MP4 copy/full decode, strict duration/hash failure, idempotent retry, reorder/label retention and seeded journal recovery")

        val releases = ReleaseStore(context)
        var candidate = releases.createCandidate(p, draft.project.revision)
        check(candidate.scene.schemaVersion == 2 && candidate.reviewedTransitionAssetIds.isEmpty())
        val videoAsset = candidate.scene.assets.single { it.role == ViewerScene.Asset.ROLE_TRANSITION }
        val fixed = releases.candidateTransitionFile(candidate.id, videoAsset.id)
        check(fixed != savedFile && ViewerPackageCodec.sha256(fixed) == saved.asset.sha256)
        val sceneText = ViewerPackageCodec.writeScene(candidate.scene).toString(Charsets.UTF_8)
        check(!sceneText.contains(transitionSource.sourceId) && !sceneText.contains("masks") && !sceneText.contains("startUs"))
        for (state in candidate.scene.states) candidate = releases.reviewState(candidate.id, candidate.contentDigest, state.id)
        candidate = releases.reviewSummary(candidate.id, candidate.contentDigest)
        candidate = releases.reviewFileList(candidate.id, candidate.contentDigest)
        candidate = releases.recordTraversal(candidate.id, candidate.contentDigest, null, false)
        candidate = releases.recordTraversal(candidate.id, candidate.contentDigest, edgeId, false)
        rejected { releases.seal(candidate.id, candidate.contentDigest) }
        rejected { releases.reviewTransition(candidate.id, "0".repeat(64), videoAsset.id, videoAsset.sha256) }
        rejected { releases.reviewTransition(candidate.id, candidate.contentDigest, videoAsset.id, "0".repeat(64)) }
        candidate = releases.reviewTransition(candidate.id, candidate.contentDigest, videoAsset.id, videoAsset.sha256)
        check(ReleaseStore(context).readCandidateById(candidate.id).reviewedTransitionAssetIds == setOf(videoAsset.id))
        val sealed = releases.seal(candidate.id, candidate.contentDigest)
        val sealedFile = releases.releaseTransitionFile(sealed.id, videoAsset.id)
        val step = draft.steps.first { it.id == a }
        draft = projects.saveStepDraft(p, a, step.title, step.description, false, step.hotspots,
            draft.project.revision, checkNotNull(step.nextAction).copy(targetStepId = a))
        check(draft.steps.first { it.id == a }.nextAction?.transition == null)
        check(!projects.isSourceReferenced(transitionSource.sourceId))
        projects.readProject(p) // Retry exact cleanup journal after the committed graph edit.
        check(!savedFile.exists() && video.isFile && File(root, transitionSource.privateRelativePath).isFile)
        check(ViewerPackageCodec.sha256(sealedFile) == videoAsset.sha256)
        check(releases.loadRelease(sealed.id).edges.single().transitionAssetId == videoAsset.id)
        draft = projects.bindReviewedTransition(p, edgeId, draft.project.revision, input.copy(reviewId = "second-review"))
        val second = projects.resolveTransition(p, edgeId)
        draft = projects.removeTransition(p, edgeId, draft.project.revision)
        check(draft.steps.first { it.id == a }.nextAction?.transition == null && !second.exists())
        checkCancelledWorkspace(context, projects, p, a, edgeId, input, status)
        projects.deleteProject(p)
        check(ViewerPackageCodec.sha256(releases.releaseTransitionFile(sealed.id, videoAsset.id)) == videoAsset.sha256)
        status("PASS transition release: private provenance excluded, fixed MP4 copy, independent digest/hash review required to seal; retarget/remove/project delete preserve sealed bytes")
        status("NOT_COVERED transition lifecycle: actual process-kill timing and human full-playback gating require device/UI checks")
    }

    /** Hold Main only for the synchronous static transaction, never for video decoding. */
    private suspend fun checkCancelledWorkspace(context: Context, store: ProjectStore, projectId: String,
        stepId: String, edgeId: String, input: ReviewedTransitionInput, status: (String) -> Unit) = coroutineScope {
        val before = store.bindReviewedTransition(projectId, edgeId,
            checkNotNull(store.readProject(projectId)).project.revision, input.copy(reviewId = "cancel-check"))
        val application = object : Application() {
            init { attachBaseContext(context) }
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = context.noBackupFilesDir
        }
        val owner = ViewModelStore()
        val workspace = withContext(Dispatchers.Main.immediate) {
            ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(application))[TransitionWorkspace::class.java]
        }
        var callback = false
        try {
            withContext(Dispatchers.Main.immediate) { workspace.open(projectId, stepId, edgeId) }
            withTimeout(10_000) { workspace.state.first { !it.busy } }
            check(!workspace.state.value.failedToLoad && workspace.state.value.existing != null)

            // Cancellation while waiting for the shared lock must leave the real binding intact.
            MediaWorkspace.operationLock.lock()
            try {
                withContext(Dispatchers.Main.immediate) {
                    workspace.useStatic { _, _ -> callback = true }
                    workspace.cancel()
                }
            } finally { MediaWorkspace.operationLock.unlock() }
            withTimeout(10_000) { workspace.state.first { !it.busy } }
            check(workspace.state.value.revision == before.project.revision)
            check(workspace.state.value.existing == before.steps.first { it.id == stepId }.nextAction?.transition)
            check(!workspace.state.value.failedToLoad && !callback)

            // Observe the committed SQLite revision from IO while Main cannot deliver its result.
            // Cancel before releasing Main: withContext must throw even though removal committed.
            val committed = CountDownLatch(1)
            val observer = async(Dispatchers.IO) {
                withTimeout(5_000) {
                    while (checkNotNull(store.readProject(projectId)).project.revision == before.project.revision) delay(1)
                }
                committed.countDown()
            }
            withContext(Dispatchers.Main.immediate) {
                workspace.useStatic { _, _ -> callback = true }
                try { check(committed.await(5, TimeUnit.SECONDS)) { "Static transaction did not commit" } }
                finally { workspace.cancel() }
            }
            observer.await()
            withTimeout(10_000) { workspace.state.first { !it.busy } }
            val actual = checkNotNull(store.readProject(projectId))
            val recovered = workspace.state.value
            check(actual.project.revision > before.project.revision && recovered.revision == actual.project.revision)
            check(recovered.existing == null && recovered.otherDurationUs == 0L)
            check(!recovered.failedToLoad && !callback && recovered.message?.contains("原过渡保持不变") != true)

            // Leave after recovery starts but before its IO read can finish. Its result and finally
            // must not overwrite the newer generation, including its message and busy state.
            MediaWorkspace.operationLock.lock()
            val oldJobs: List<Job>
            try {
                withContext(Dispatchers.Main.immediate) {
                    workspace.useStatic { _, _ -> callback = true }
                    workspace.cancel()
                }
                withTimeout(10_000) { workspace.state.first { it.stage == "核对保存结果" } }
                oldJobs = withContext(Dispatchers.Main.immediate) {
                    checkNotNull(workspace.viewModelScope.coroutineContext[Job]).children.toList().also {
                        workspace.leave()
                        workspace.message("new editor generation")
                    }
                }
            } finally { MediaWorkspace.operationLock.unlock() }
            withTimeout(10_000) { oldJobs.forEach { it.join() } }
            check(workspace.state.value == TransitionUiState(message = "new editor generation") && !callback)
            status("PASS transition cancellation: waiting-lock cancellation, committed static removal before Main return, persisted revision/binding/budget reconciliation, and stale recovery after leaving")
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { owner.clear() }
        }
    }

    private fun seedRecovery(context: Context, store: ProjectStore, projectId: String,
        saved: ProjectTransition, candidate: File) {
        val root = context.noBackupFilesDir
        val orphan = id()
        val ids = listOf(orphan, saved.asset.id)
        SQLiteDatabase.openDatabase(File(root, "projects.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            ids.forEach { db.execSQL("INSERT INTO transition_imports VALUES(?,?)", arrayOf(projectId, it)) }
        }
        ids.forEach { assetId ->
            val directory = File(root, "project-staging/$assetId")
            check(directory.mkdir())
            candidate.copyTo(File(directory, "candidate.part"))
        }
        val orphanFile = candidate.copyTo(File(root, "project-assets/$projectId/$orphan.mp4"))
        store.readProject(projectId)
        check(!orphanFile.exists() && ids.none { File(root, "project-staging/$it").exists() })
        check(File(root, saved.asset.privateRelativePath).isFile)
        SQLiteDatabase.openDatabase(File(root, "projects.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM transition_imports", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
        }
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        check(error is IllegalArgumentException || error is IllegalStateException) { "Expected explicit transition rejection: $error" }
    }

    private fun id() = UUID.randomUUID().toString()
}
