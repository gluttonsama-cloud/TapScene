package com.tapscene.ui

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.SourceDraft
import com.tapscene.data.StepOrigin
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.OpaqueMask
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Runs from the existing actual-media instrumentation harness, not a synthetic UI preview. */
internal object SafeImageCorrectionWorkspaceChecks {
    suspend fun run(application: Application, projects: ProjectWorkspace, media: MediaWorkspace,
        before: ProjectSnapshot, stepId: String, shared: List<SourceDraft>, status: (String) -> Unit) {
        val store = ProjectStore(application)
        val projectId = before.project.id
        val savedStep = before.steps.single { it.id == stepId }
        val source = checkNotNull(savedStep.videoOrigin).source
        val original = File(application.noBackupFilesDir, source.privateRelativePath)
        val parked = File(original.parentFile, "${original.name}.safe-image-check")
        val extra = OpaqueMask(.05f, .05f, .15f, .15f)
        withContext(Dispatchers.Main.immediate) { projects.openStep(stepId) }
        idle(projects)
        withContext(Dispatchers.Main.immediate) {
            projects.editTitle("Unsaved safe-image title")
            projects.editDescription("Unsaved safe-image description")
        }
        idle(projects)
        check(original.renameTo(parked))
        var after = before
        try {
            withContext(Dispatchers.Main.immediate) { check(media.openStepCorrection(before, stepId)) }
            idle(media)
            check(media.state.value.frame == null && media.state.value.safeImageDraft == null)
            withContext(Dispatchers.Main.immediate) { media.useSafeImageBase() }
            idle(media)
            val image = checkNotNull(media.state.value.safeImageDraft)
            check(image.binding == savedStep.safeImageBinding(before.project) && image.masks.isEmpty())
            check(media.state.value.frame == null && media.state.value.frameReviewId == null && media.state.value.selected == null)
            val base = checkNotNull(image.bitmap)
            check(base.getPixel(base.width / 2, base.height / 2) == Color.BLACK)
            withContext(Dispatchers.Main.immediate) { media.makeImage() }
            check(media.state.value.candidate == null && media.state.value.message?.contains("至少一处遮挡") == true)
            withContext(Dispatchers.Main.immediate) { media.undoMask(); media.addMask(extra); media.makeImage() }
            idle(media)
            val generated = checkNotNull(media.state.value.candidateImage)
            check(generated.getPixel(generated.width / 2, generated.height / 2) == Color.BLACK)
            check(generated.getPixel(generated.width / 10, generated.height / 10) == Color.BLACK)
            val generatedState = media.state.value
            check(generatedState.canReviewCorrection(checkNotNull(generatedState.correction),
                checkNotNull(generatedState.candidate).sha256))
            check(!generatedState.canReviewCorrection(checkNotNull(generatedState.correction), "0".repeat(64)))
            withContext(Dispatchers.Main.immediate) { media.retryStepCorrection() }
            check(media.state.value.safeImageDraft?.bitmap === base && media.state.value.candidate == generatedState.candidate)
            var unreviewedSaved = false
            withContext(Dispatchers.Main.immediate) { media.saveReviewedImage { unreviewedSaved = true } }
            check(!unreviewedSaved && store.readProject(projectId) == before)
            withContext(Dispatchers.Main.immediate) { media.review(true); media.undoMask() }
            check(media.state.value.safeImageDraft?.masks?.isEmpty() == true)
            check(media.state.value.candidate == null && media.state.value.reviewedDigest == null)
            check(base.getPixel(base.width / 2, base.height / 2) == Color.BLACK)
            withContext(Dispatchers.Main.immediate) { check(media.closeStepCorrection()) }
            check(store.readProject(projectId) == before && WorkspaceStore(application, projectId).read() == shared)

            withContext(Dispatchers.Main.immediate) { check(media.openStepCorrection(before, stepId)) }
            idle(media)
            // Cancelling while the operation lock is held must not save or replace the source mode.
            MediaWorkspace.operationLock.lock()
            try {
                withContext(Dispatchers.Main.immediate) { media.useSafeImageBase(); media.cancel() }
            } finally { MediaWorkspace.operationLock.unlock() }
            idle(media)
            check(store.readProject(projectId) == before && media.state.value.candidate == null)
            withContext(Dispatchers.Main.immediate) { media.useSafeImageBase() }
            idle(media)
            withContext(Dispatchers.Main.immediate) { media.addMask(extra); media.makeImage() }
            idle(media)
            val correction = checkNotNull(media.state.value.correction)
            val digest = checkNotNull(media.state.value.candidate).sha256
            withContext(Dispatchers.Main.immediate) {
                media.review(true)
                media.saveReviewedImage { projects.replaceReviewedStep(correction, it) }
            }
            idle(media); idle(projects)
            after = checkNotNull(store.readProject(projectId))
            val step = after.steps.single { it.id == stepId }
            check(after.project.revision == before.project.revision + 1 && after.steps.size == before.steps.size)
            check(step.origin == StepOrigin.Image(image.binding) && step.asset.sha256 == digest)
            check(step.source == null && step.frameTimeUs == null && step.timePrecisionUs == null)
            check(step.masks == listOf(extra) && step.title == savedStep.title)
            check(media.state.value.completedCorrectionId == correction.sessionId)
            check(projects.state.value.stepDraft?.title == "Unsaved safe-image title")
            check(projects.state.value.stepDraft?.description == "Unsaved safe-image description")
            check(stepId in projects.state.value.dirtyStepIds)
            withContext(Dispatchers.Main.immediate) { check(media.closeStepCorrection()) }

            // A later round always reads this latest output, never the now-historical origin base.
            withContext(Dispatchers.Main.immediate) { check(media.openStepCorrection(after, stepId)) }
            check(media.state.value.safeImageDraft == null && media.state.value.correctionDraft == null && media.state.value.frame == null)
            withContext(Dispatchers.Main.immediate) { media.useSafeImageBase() }
            idle(media)
            val next = checkNotNull(media.state.value.safeImageDraft)
            check(next.binding == step.safeImageBinding(after.project) && next.binding != image.binding)
            check(next.masks.isEmpty())
            val nextBase = checkNotNull(next.bitmap)
            check(nextBase.getPixel(nextBase.width / 10, nextBase.height / 10) == Color.BLACK)
            val second = OpaqueMask(.8f, .8f, .95f, .95f)
            withContext(Dispatchers.Main.immediate) { media.addMask(second); media.makeImage() }
            idle(media)
            val nextCorrection = checkNotNull(media.state.value.correction)
            withContext(Dispatchers.Main.immediate) {
                media.review(true)
                media.saveReviewedImage {
                    projects.replaceReviewedStep(nextCorrection, it)
                    media.cancel() // The atomic commit already completed; reread must report it.
                }
            }
            idle(media); idle(projects)
            after = checkNotNull(store.readProject(projectId))
            val twice = after.steps.single { it.id == stepId }
            check(twice.origin == StepOrigin.Image(next.binding) && twice.masks == listOf(second))
            check(media.state.value.completedCorrectionId == nextCorrection.sessionId)
            check(projects.state.value.stepDraft?.title == "Unsaved safe-image title")
            withContext(Dispatchers.Main.immediate) { check(media.closeStepCorrection()) }
        } finally { check(parked.renameTo(original)) }
        check(WorkspaceStore(application, projectId).read() == shared)
        status("PASS safe-image workspace: missing-video explicit opt-in; real PNG generation and review; undo only new masks; close and pre/post-commit cancellation; latest-safe-base second round; stable step ID and buffered text preserved")

        // Selecting a real video from an image step must neither synthesize a frame nor inherit image masks.
        withContext(Dispatchers.Main.immediate) { check(media.openStepCorrection(after, stepId)); media.selectCorrectionSource(source.sourceId) }
        idle(media)
        check(media.state.value.safeImageDraft == null && media.state.value.frame != null)
        check(media.state.value.selected?.source == source && media.state.value.selected?.masks?.isEmpty() == true)
        withContext(Dispatchers.Main.immediate) { check(media.closeStepCorrection()) }
        val owner = ViewModelStore()
        try {
            val transitions = withContext(Dispatchers.Main.immediate) {
                ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(application))[TransitionWorkspace::class.java]
            }
            val edge = checkNotNull(after.steps.single { it.id == stepId }.nextAction).id
            withContext(Dispatchers.Main.immediate) { transitions.open(projectId, stepId, edge) }
            withTimeout(10_000) { transitions.state.first { !it.busy } }
            check(!transitions.state.value.failedToLoad && transitions.state.value.source == source)
            check(transitions.state.value.masks.isEmpty() && transitions.state.value.frame != null)
        } finally { withContext(NonCancellable + Dispatchers.Main.immediate) { owner.clear() } }

        // Revision drift must discard edit capability and block output, even when the old PNG exists.
        withContext(Dispatchers.Main.immediate) { check(media.openStepCorrection(after, stepId)) }
        val changed = store.renameProject(projectId, "Changed during safe-image edit")
        withContext(Dispatchers.Main.immediate) { media.useSafeImageBase() }
        idle(media)
        check(media.state.value.safeImageDraft?.bitmap == null && media.state.value.frame == null)
        var staleSaved = false
        withContext(Dispatchers.Main.immediate) { media.makeImage(); media.saveReviewedImage { staleSaved = true } }
        check(!staleSaved && media.state.value.candidate == null && store.readProject(projectId) == changed)
        withContext(Dispatchers.Main.immediate) { check(media.closeStepCorrection()) }
        status("PASS safe-image isolation: real-video correction and transition selection never inherit image masks; stale revision refuses base load/output/save")
    }

    private suspend fun idle(workspace: MediaWorkspace) {
        withTimeout(10_000) { workspace.state.first { !it.busy } }
        check(!workspace.state.value.loadFailed)
    }
    private suspend fun idle(workspace: ProjectWorkspace) {
        withTimeout(10_000) { workspace.state.first { !it.busy } }
        check(!workspace.state.value.loadFailed)
    }
}
