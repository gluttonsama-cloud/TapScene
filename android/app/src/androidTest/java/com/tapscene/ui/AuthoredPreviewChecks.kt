package com.tapscene.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.viewModelScope
import com.tapscene.data.ProjectNextAction
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReviewedStepInput
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Real main-thread ViewModel, private SQLite and PNG checks, without a new test framework.
 * Synthetic canvases test authored buttons and saved-asset decoding, not recorded touch detection,
 * device UI hit targets, MediaProjection, or a human privacy review.
 */
object AuthoredPreviewChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "authored-preview-checks-${UUID.randomUUID()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val application = object : Application() {
            init { attachBaseContext(context.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        var workspace: ProjectWorkspace? = null
        var failure: Throwable? = null
        try {
            val active = withContext(Dispatchers.Main.immediate) { ProjectWorkspace(application) }
            workspace = active
            idle(active)
            checkPreview(application, active, status)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanup = runCatching {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main.immediate) { workspace?.viewModelScope?.cancel() }
                    workspace?.let { active -> withTimeout(10_000) { active.state.first { !it.busy } } }
                }
                check(root.canonicalFile.parentFile == parent)
                check(root.deleteRecursively() && !root.exists()) { "Authored preview check cleanup failed" }
            }.exceptionOrNull()
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private suspend fun checkPreview(application: Application, workspace: ProjectWorkspace, status: (String) -> Unit) {
        val store = ProjectStore(application)
        val projectId = store.createProject("合成作者通路").project.id
        val source = source(application.noBackupFilesDir)
        val a = store.addReviewedStep(projectId, input(application, source, Color.RED), "A").steps.last()
        val b = store.addReviewedStep(projectId, input(application, source, Color.GREEN), "B").steps.last()
        val c = store.addReviewedStep(projectId, input(application, source, Color.BLUE), "C").steps.last()
        val withHotspot = store.saveHotspot(projectId, a.id, label = "分支到 C",
            rect = OpaqueMask(0.1f, 0.1f, 0.3f, 0.3f), targetStepId = c.id)
        val branch = withHotspot.steps.single { it.id == a.id }.hotspots.single()
        command(workspace) { openProject(projectId) }
        command(workspace) { connectStepsInOrder(listOf(a.id, b.id, c.id), true, withHotspot.project.revision) }
        val connected = checkNotNull(workspace.state.value.project)
        val nextA = checkNotNull(connected.steps.single { it.id == a.id }.nextAction)
        val nextB = checkNotNull(connected.steps.single { it.id == b.id }.nextAction)
        check(nextA.targetStepId == b.id && nextB.targetStepId == c.id)
        check(connected.steps.single { it.id == c.id }.isTerminal)
        check(connected.steps.single { it.id == a.id }.hotspots.single() == branch)
        check(ProjectWorkspace.graphIssues(connected).isEmpty())

        command(workspace) { openStep(a.id) }
        command(workspace) { editDescription("尚未保存") }
        command(workspace) { connectStepsInOrder(listOf(b.id, a.id, c.id), false, connected.project.revision) }
        check(store.readProject(projectId) == connected && workspace.state.value.stepDraft?.dirty == true)
        command(workspace) { discardStepDraft() }
        command(workspace) { putNextAction(nextA.copy(label = "  继续到 B  ")) }
        check(workspace.state.value.stepDraft?.dirty == true)
        command(workspace) { editTerminal(true) }
        check(workspace.state.value.stepDraft?.isTerminal == false)
        command(workspace) { saveStepDraft() }
        check(workspace.state.value.stepDraft?.dirty == false)
        check(workspace.state.value.stepDraft?.nextAction == nextA.copy(label = "继续到 B"))
        command(workspace) { removeNextAction() }
        check(workspace.state.value.stepDraft?.dirty == true && workspace.state.value.stepDraft?.nextAction == null)
        command(workspace) { putNextAction(ProjectNextAction(UUID.randomUUID().toString(), "重新加入", b.id)) }
        check(workspace.state.value.stepDraft?.nextAction?.id == nextA.id)
        command(workspace) { saveStepDraft() }
        check(workspace.state.value.stepDraft?.dirty == false)
        command(workspace) { removeNextAction() }
        command(workspace) { discardStepDraft() }
        val beforeReorder = checkNotNull(store.readProject(projectId))
        command(workspace) { moveStep(c.id, -1) }
        val reordered = checkNotNull(workspace.state.value.project)
        check(reordered.steps.map { it.id } == listOf(a.id, c.id, b.id))
        check(reordered.steps.all { step -> step == beforeReorder.steps.single { it.id == step.id }.copy(sortOrder = step.sortOrder) })
        command(workspace) { connectStepsInOrder(listOf(a.id, b.id, c.id), false, beforeReorder.project.revision) }
        check(store.readProject(projectId) == reordered)
        status("PASS authored editor: explicit save, draft guard, trimmed labels, terminal conflict, stable next IDs and order-only reorder")

        command(workspace) { startPreview() }
        check(workspace.state.value.preview?.history == listOf(a.id))
        command(workspace) { tapPreview(0.9f, 0.9f) }
        check(workspace.state.value.preview?.history == listOf(a.id)) { "Authored next action became a canvas hotspot" }
        command(workspace) { chooseNextAction() }
        check(workspace.state.value.preview?.history == listOf(a.id, b.id))
        check(workspace.state.value.preview?.visitedActionIds == setOf(nextA.id))
        check(workspace.state.value.bitmap?.getPixel(0, 0) == Color.GREEN)
        command(workspace) { chooseNextAction() }
        check(workspace.state.value.preview?.history == listOf(a.id, b.id, c.id))
        check(workspace.state.value.preview?.ended == true)
        check(workspace.state.value.preview?.visitedActionIds == setOf(nextA.id, nextB.id))
        command(workspace) { previousPreview() }
        check(workspace.state.value.preview?.history == listOf(a.id, b.id) && workspace.state.value.preview?.ended == false)
        command(workspace) { previousPreview() }
        command(workspace) { chooseHotspot(branch.id) }
        check(workspace.state.value.preview?.history == listOf(a.id, c.id))
        command(workspace) { previousPreview() }
        check(workspace.state.value.preview?.history == listOf(a.id))
        command(workspace) { restartPreview() }
        check(workspace.state.value.preview?.visitedActionIds?.isEmpty() == true)

        val targetFile = store.resolveAsset(projectId, b.id)
        val targetBytes = targetFile.readBytes()
        targetFile.appendBytes(byteArrayOf(1))
        command(workspace) { chooseNextAction() }
        check(workspace.state.value.preview?.history == listOf(a.id))
        check(workspace.state.value.preview?.visitedActionIds?.isEmpty() == true && workspace.state.value.bitmap == null)
        targetFile.writeBytes(targetBytes)
        command(workspace) { restartPreview() }
        command(workspace) { chooseNextAction() }
        check(workspace.state.value.preview?.currentStepId == b.id && workspace.state.value.bitmap != null)
        status("PASS authored preview: independent button, actual PNG target, real branch/back history, terminal/restart and failed decode without coverage")

        command(workspace) { exitPreview() }
        command(workspace) { putNextAction(nextB.copy(targetStepId = a.id)) }
        command(workspace) { saveStepDraft() }
        command(workspace) { startPreview() }
        repeat(ProjectWorkspace.MAX_PREVIEW_VISITS - 1) { command(workspace) { chooseNextAction() } }
        val full = checkNotNull(workspace.state.value.preview)
        check(full.history.size == ProjectWorkspace.MAX_PREVIEW_VISITS && full.history.take(3) == listOf(a.id, b.id, a.id))
        command(workspace) { chooseNextAction() }
        check(workspace.state.value.preview == full) { "Explicit cycle exceeded the bounded history" }
        command(workspace) { restartPreview() }
        check(workspace.state.value.preview?.history == listOf(a.id) && workspace.state.value.preview?.visitedActionIds?.isEmpty() == true)

        command(workspace) { exitPreview() }
        command(workspace) { editTitle("保留未保存文字") }
        command(workspace) { deleteStep(b.id) }
        command(workspace) { openStep(a.id) }
        val pending = checkNotNull(workspace.state.value.stepDraft)
        check(pending.title == "保留未保存文字" && pending.dirty)
        check(pending.nextAction?.id == nextA.id && pending.nextAction?.targetStepId == null)
        command(workspace) { saveStepDraft() }
        val unresolved = checkNotNull(workspace.state.value.project)
        val issues = ProjectWorkspace.graphIssues(unresolved)
        check(issues.any { it.stepId == a.id && it.message.contains("缺少目标") })
        command(workspace) { startPreview() }
        command(workspace) { chooseNextAction() }
        check(workspace.state.value.preview?.history == listOf(a.id) && workspace.state.value.preview?.ended == false)
        check(workspace.state.value.preview?.visitedActionIds?.isEmpty() == true)
        command(workspace) { exitPreview() }
        command(workspace) { putNextAction(checkNotNull(stepDraftAction(workspace)).copy(targetStepId = a.id)) }
        command(workspace) { editDescription("显式重选的草稿也保留") }
        command(workspace) { deleteStep(c.id) }
        command(workspace) { openStep(a.id) }
        check(workspace.state.value.stepDraft?.nextAction?.targetStepId == a.id)
        check(workspace.state.value.stepDraft?.description == "显式重选的草稿也保留")
        check(workspace.state.value.stepDraft?.hotspots?.isEmpty() == true)
        command(workspace) { saveStepDraft() }
        check(workspace.state.value.stepDraft?.dirty == false)
        val saved = checkNotNull(workspace.state.value.project)
        check(ProjectWorkspace.graphIssues(saved).isEmpty())
        val conflicting = saved.copy(steps = saved.steps.map { it.copy(isTerminal = true) })
        check(ProjectWorkspace.graphIssues(conflicting).any { it.stepId == a.id && it.message.contains("仍有动作") })
        status("PASS authored recovery: explicit cycle/256-visit guard, pending-target never ends, dirty deletion rebasing and preserved explicit reroute")
    }

    private fun stepDraftAction(workspace: ProjectWorkspace): ProjectNextAction? = workspace.state.value.stepDraft?.nextAction

    private suspend fun command(workspace: ProjectWorkspace, action: ProjectWorkspace.() -> Unit) {
        withContext(Dispatchers.Main.immediate) { workspace.action() }
        idle(workspace)
    }

    private suspend fun idle(workspace: ProjectWorkspace) {
        withTimeout(10_000) { workspace.state.first { !it.busy } }
        check(!workspace.state.value.loadFailed) { "Isolated workspace failed to read its own data" }
    }

    private fun source(root: File): ImportedSource {
        val sourceId = UUID.randomUUID().toString()
        val relativePath = "sources/$sourceId.mp4"
        val file = File(root, relativePath)
        check(file.parentFile!!.mkdir())
        file.writeBytes("Synthetic ownership source; not a recording.".toByteArray())
        return ImportedSource(sourceId, relativePath, "synthetic-preview.mp4",
            SourceMetadata("video/mp4", file.length(), sha256(file), 8, 12, 0, 1_000_000L))
    }

    private suspend fun input(context: Context, source: ImportedSource, color: Int): ReviewedStepInput {
        val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try {
            val candidate = SafeMediaWriter(context).writePng(bitmap, emptyList(),
                File(context.noBackupFilesDir, "candidates/${UUID.randomUUID()}"))
            ReviewedStepInput(candidate.file, candidate.sha256, candidate.width, candidate.height,
                source, 100_000L, 1_000L, emptyList(), captureId = UUID.randomUUID().toString())
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
