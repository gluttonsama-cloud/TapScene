package com.tapscene.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReviewedStepInput
import com.tapscene.data.ReviewedTransitionInput
import com.tapscene.data.SourceDraft
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Real ViewModels, PNG/SQLite and decoded source frames. This does not exercise Compose gestures
 * or establish a human privacy review; the review checkbox is explicitly driven by this fixture. */
object StepCorrectionWorkspaceChecks {
    suspend fun run(context: Context, source: ImportedSource, output: SafeMediaWriter.CandidateMedia,
        startUs: Long, endUs: Long, masks: List<OpaqueMask>, status: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "step-correction-workspace-checks-${id()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val application = object : Application() {
            init { attachBaseContext(context.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val owner = ViewModelStore()
        var failure: Throwable? = null
        try {
            val local = source.copy(sourceId = id()).let { it.copy(privateRelativePath = "sources/${it.sourceId}.mp4") }
            val sourceFile = File(root, local.privateRelativePath)
            check(sourceFile.parentFile!!.mkdir())
            File(parent, source.privateRelativePath).copyTo(sourceFile)
            val clip = output.file.copyTo(File(root, "reviewed.mp4"))
            val transition = ReviewedTransitionInput(clip, output.sha256, output.width, output.height,
                checkNotNull(output.durationUs), local, startUs, endUs, masks, "workspace-transition")
            val provider = withContext(Dispatchers.Main.immediate) {
                ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(application))
            }
            val projects = withContext(Dispatchers.Main.immediate) { provider[ProjectWorkspace::class.java] }
            val media = withContext(Dispatchers.Main.immediate) { provider[MediaWorkspace::class.java] }
            idle(projects); idle(media)
            checkCorrection(application, projects, media, local, transition, status)
            check(ViewerPackageCodec.sha256(sourceFile) == source.metadata.sha256)
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                withContext(NonCancellable + Dispatchers.Main.immediate) { owner.clear() }
                check(root.canonicalFile.parentFile == parent)
                check(root.deleteRecursively() && !root.exists())
            }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private suspend fun checkCorrection(application: Application, projects: ProjectWorkspace, media: MediaWorkspace,
        source: ImportedSource, transition: ReviewedTransitionInput, status: (String) -> Unit) {
        val store = ProjectStore(application)
        val p = store.createProject("Step correction workspace").project.id
        val stepMasks = listOf(OpaqueMask(.25f, .25f, .75f, .75f))
        val input = png(application, source, emptyList())
        val a = store.addReviewedStep(p, input, "Saved A").steps.single().id
        val b = store.addReviewedStep(p, png(application, source, stepMasks), "Saved B").steps.last().id
        val c = store.addReviewedStep(p, input.copy(captureId = id()), "Saved C").steps.last().id
        var before = checkNotNull(store.readProject(p))
        before = store.connectStepsInOrder(p, listOf(a, b, c), true, before.project.revision)
        for ((from, target) in listOf(a to b, b to c)) {
            before = store.saveHotspot(p, from, label = "Saved branch", rect = OpaqueMask(.1f, .1f, .4f, .4f), targetStepId = target)
        }
        for (step in before.steps.filter { it.id != c }) {
            for (edge in listOf(checkNotNull(step.nextAction).id, step.hotspots.single().edgeId)) {
                before = store.bindReviewedTransition(p, edge, before.project.revision, transition.copy(reviewId = edge))
            }
        }
        val shared = listOf(SourceDraft(source, 500_000, listOf(OpaqueMask(.05f, .05f, .2f, .2f))))
        val workbench = WorkspaceStore(application, p)
        workbench.write(shared)
        command(projects) { openProject(p) }
        withContext(Dispatchers.Main.immediate) { check(media.activateProject(p)) }
        idle(media)
        for ((stepId, suffix) in listOf(a to "A", b to "B")) {
            command(projects) { openStep(stepId) }
            command(projects) {
                val draft = checkNotNull(state.value.stepDraft)
                editTitle("Dirty $suffix")
                editDescription("Unsaved $suffix description")
                putNextAction(checkNotNull(draft.nextAction).copy(label = "Dirty $suffix next"))
                putHotspot(draft.hotspots.single().copy(label = "Dirty $suffix branch"))
            }
        }
        check(projects.state.value.dirtyStepIds == setOf(a, b))
        withContext(Dispatchers.Main.immediate) { check(media.openStepCorrection(before, b)) }
        idle(media)
        val opened = media.state.value
        check(opened.frame != null && opened.frameReviewId == opened.correction?.sessionId)
        check(opened.selected?.masks == stepMasks && opened.drafts == shared && opened.correction?.transitionCount == 4)
        withContext(Dispatchers.Main.immediate) { media.makeImage() }
        idle(media)
        check(media.state.value.candidate != null && media.state.value.reviewedDigest == null)
        var unreviewedSaved = false
        withContext(Dispatchers.Main.immediate) { media.saveReviewedImage { unreviewedSaved = true } }
        idle(media)
        check(!unreviewedSaved && store.readProject(p) == before)
        withContext(Dispatchers.Main.immediate) { media.review(true); media.undoMask() }
        check(media.state.value.candidate == null && media.state.value.reviewedDigest == null)
        check(media.state.value.selected?.masks?.isEmpty() == true)
        withContext(Dispatchers.Main.immediate) { check(media.closeStepCorrection()) }
        check(workbench.read() == shared && store.readProject(p) == before)

        // A cancelled attempt waiting for the media lock cannot overwrite a shared source draft.
        MediaWorkspace.operationLock.lock()
        try {
            withContext(Dispatchers.Main.immediate) {
                check(media.openStepCorrection(before, b))
                check(media.state.value.selected?.masks == stepMasks)
                media.cancel()
            }
        } finally { MediaWorkspace.operationLock.unlock() }
        idle(media)
        withContext(Dispatchers.Main.immediate) { check(media.closeStepCorrection()) }
        check(workbench.read() == shared && store.readProject(p) == before)
        status("PASS correction workspace isolation: saved-step masks override shared source masks; actual frame decode, review gating, mask-change invalidation, close and waiting-lock cancellation preserve all saved work")

        withContext(Dispatchers.Main.immediate) { check(media.openStepCorrection(before, b)) }
        idle(media)
        val correction = checkNotNull(media.state.value.correction)
        withContext(Dispatchers.Main.immediate) { media.makeImage() }
        idle(media)
        val reviewedSha = checkNotNull(media.state.value.candidate).sha256
        withContext(Dispatchers.Main.immediate) {
            media.review(true)
            media.saveReviewedImage { projects.replaceReviewedStep(correction, it) }
        }
        idle(media); idle(projects)
        val replaced = checkNotNull(store.readProject(p))
        check(replaced.project.revision == before.project.revision + 1 && replaced.steps.size == 3)
        check(replaced.steps.single { it.id == b }.asset.sha256 == reviewedSha)
        check(replaced.steps.single { it.id == a }.asset == before.steps.single { it.id == a }.asset)
        check(replaced.steps.single { it.id == c } == before.steps.single { it.id == c })
        check(replaced.steps.single { it.id == b }.title == "Saved B")
        check(media.state.value.completedCorrectionId == correction.sessionId && media.state.value.candidate == null)
        check(projects.state.value.dirtyStepIds == setOf(a, b) && projects.state.value.selectedStepId == b)
        for ((stepId, suffix) in listOf(b to "B", a to "A")) {
            command(projects) { openStep(stepId) }
            val draft = checkNotNull(projects.state.value.stepDraft)
            val previous = before.steps.single { it.id == stepId }
            check(draft.dirty && draft.title == "Dirty $suffix" && draft.description == "Unsaved $suffix description")
            check(draft.nextAction == checkNotNull(previous.nextAction).copy(label = "Dirty $suffix next", transition = null))
            check(draft.hotspots.single() == previous.hotspots.single().copy(label = "Dirty $suffix branch", transition = null))
            command(projects) { saveStepDraft() }
            check(projects.state.value.stepDraft?.dirty == false)
            check(store.readProject(p)?.steps?.single { it.id == stepId }?.title == "Dirty $suffix")
        }
        check(projects.state.value.dirtyStepIds.isEmpty() && workbench.read() == shared)
        withContext(Dispatchers.Main.immediate) { check(media.closeStepCorrection()) }
        val saved = checkNotNull(store.readProject(p))
        checkMissingSource(application, media, saved, b, shared)
        status("PASS correction workspace save: actual reviewed PNG replaces B without appending; dirty A/B text/action labels survive; incoming/outgoing clips clear in every retained draft and both later saves succeed; missing source blocks regeneration/save but preserves the saved PNG")
        checkSourceRecovery(application, media, saved, b, shared)
        status("PASS correction source recovery: missing selected source clears frame/output/review; returning to original decodes with session masks intact; corrupt shared metadata does not block stored-step decode/output or get silently overwritten")
    }

    private suspend fun checkMissingSource(application: Application, media: MediaWorkspace, before: ProjectSnapshot,
        stepId: String, shared: List<SourceDraft>) {
        val step = before.steps.single { it.id == stepId }
        val original = File(application.noBackupFilesDir, step.source.privateRelativePath)
        val parked = File(original.parentFile, "${original.name}.held")
        check(original.renameTo(parked))
        try {
            withContext(Dispatchers.Main.immediate) { check(media.openStepCorrection(before, stepId)) }
            idle(media)
            check(media.state.value.frame == null && media.state.value.selected?.masks == step.masks)
            check(media.state.value.message?.contains("原片已缺失") == true)
            var saved = false
            withContext(Dispatchers.Main.immediate) { media.undoMask(); media.makeImage(); media.saveReviewedImage { saved = true } }
            idle(media)
            check(!saved && media.state.value.candidate == null)
            withContext(Dispatchers.Main.immediate) { check(media.closeStepCorrection()) }
            check(WorkspaceStore(application, before.project.id).read() == shared)
            check(ProjectStore(application).readProject(before.project.id) == before)
            check(ProjectStore(application).resolveAsset(before.project.id, stepId).isFile)
        } finally { check(parked.renameTo(original)) }
    }

    private suspend fun checkSourceRecovery(application: Application, media: MediaWorkspace, before: ProjectSnapshot,
        stepId: String, shared: List<SourceDraft>) {
        val step = before.steps.single { it.id == stepId }
        val workbench = WorkspaceStore(application, before.project.id)
        val missing = step.source.copy(sourceId = id()).let { it.copy(privateRelativePath = "sources/${it.sourceId}.mp4") }
        val withMissing = shared + SourceDraft(missing, masks = listOf(OpaqueMask(.1f, .1f, .2f, .2f)))
        workbench.write(withMissing)
        withContext(Dispatchers.Main.immediate) { media.reload() }
        idle(media)
        withContext(Dispatchers.Main.immediate) { check(media.openStepCorrection(before, stepId)) }
        idle(media)
        val session = checkNotNull(media.state.value.correction).sessionId
        check(media.state.value.frame != null)
        withContext(Dispatchers.Main.immediate) { media.makeImage() }
        idle(media)
        check(media.state.value.candidate != null)
        withContext(Dispatchers.Main.immediate) { media.review(true); media.selectCorrectionSource(missing.sourceId) }
        idle(media)
        check(media.state.value.selected?.source == missing && media.state.value.selected?.masks == step.masks)
        check(media.state.value.frame == null && media.state.value.frameReviewId == null)
        check(media.state.value.candidate == null && media.state.value.reviewedDigest == null)
        withContext(Dispatchers.Main.immediate) { media.selectCorrectionSource(step.sourceId) }
        idle(media)
        check(media.state.value.frame != null && media.state.value.frameReviewId == session)
        check(media.state.value.selected?.source == step.source && media.state.value.selected?.masks == step.masks)
        check(media.state.value.candidate == null && media.state.value.reviewedDigest == null)
        withContext(Dispatchers.Main.immediate) { check(media.closeStepCorrection()) }
        check(workbench.read() == withMissing)
        workbench.write(shared)
        withContext(Dispatchers.Main.immediate) { media.reload() }
        idle(media)

        val metadata = File(application.noBackupFilesDir, "project-media-${before.project.id}.json")
        val intact = metadata.readBytes()
        val corrupt = "Deliberately invalid shared-workbench JSON".toByteArray()
        metadata.writeBytes(corrupt)
        try {
            withContext(Dispatchers.Main.immediate) { media.reload() }
            idle(media, allowLoadFailure = true)
            check(media.state.value.loadFailed)
            withContext(Dispatchers.Main.immediate) { check(media.openStepCorrection(before, stepId)) }
            idle(media, allowLoadFailure = true)
            check(media.state.value.frame != null && media.state.value.selected?.masks == step.masks)
            withContext(Dispatchers.Main.immediate) { media.makeImage() }
            idle(media, allowLoadFailure = true)
            check(media.state.value.candidate != null && media.state.value.reviewedDigest == null)
            withContext(Dispatchers.Main.immediate) { check(media.closeStepCorrection()) }
            check(media.state.value.loadFailed && metadata.readBytes().contentEquals(corrupt))
            check(ProjectStore(application).readProject(before.project.id) == before)
        } finally { metadata.writeBytes(intact) }
        withContext(Dispatchers.Main.immediate) { media.reload() }
        idle(media)
        check(workbench.read() == shared && media.state.value.drafts == shared)
    }

    private suspend fun command(workspace: ProjectWorkspace, action: ProjectWorkspace.() -> Unit) {
        withContext(Dispatchers.Main.immediate) { workspace.action() }
        idle(workspace)
    }
    private suspend fun idle(workspace: ProjectWorkspace) {
        withTimeout(10_000) { workspace.state.first { !it.busy } }
        check(!workspace.state.value.loadFailed)
    }
    private suspend fun idle(workspace: MediaWorkspace, allowLoadFailure: Boolean = false) {
        withTimeout(10_000) { workspace.state.first { !it.busy } }
        if (!allowLoadFailure) check(!workspace.state.value.loadFailed)
    }
    private suspend fun png(context: Context, source: ImportedSource, masks: List<OpaqueMask>): ReviewedStepInput {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        return try {
            val output = SafeMediaWriter(context).writePng(bitmap, masks, File(context.noBackupFilesDir, "input-${id()}"))
            ReviewedStepInput(output.file, output.sha256, output.width, output.height, source, 200_000, 1_000, masks, id())
        } finally { bitmap.recycle() }
    }
    private fun id() = UUID.randomUUID().toString()
}
