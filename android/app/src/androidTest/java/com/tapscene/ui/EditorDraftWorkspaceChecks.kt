package com.tapscene.ui

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.EditorDraftFields
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.EditorFormKind
import com.tapscene.data.EditorPendingForm
import com.tapscene.data.ProjectNextAction
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReviewedStepInput
import com.tapscene.media.OpaqueMask
import com.tapscene.media.ImportedSource
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Actual ViewModels + private SQLite; owner recreation is not an Android process-kill test. */
object EditorDraftWorkspaceChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        checkPendingMerge()
        status("PASS pending three-way merge: atomic hotspot destinations, concurrent next-action slot, canonical untouched geometry and deleted latent target")
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "editor-workspace-${id()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val app = object : Application() {
            init { attachBaseContext(context.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        var owner = ViewModelStore()
        var failure: Throwable? = null
        try {
            val store = ProjectStore(app)
            val sourceId = id()
            val sourceFile = File(root, "sources/$sourceId.mp4")
            check(sourceFile.parentFile!!.mkdir())
            sourceFile.writeText("Ownership-only synthetic source, not playable media")
            val source = ImportedSource(sourceId, "sources/$sourceId.mp4", "local.mp4",
                SourceMetadata("video/mp4", sourceFile.length(), ViewerPackageCodec.sha256(sourceFile), 48, 64, 0, 1_000_000))
            val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            val candidate = try { SafeMediaWriter(app).writePng(bitmap, emptyList(), File(root, "candidate")) }
                finally { bitmap.recycle() }
            val input = ReviewedStepInput(candidate.file, candidate.sha256, candidate.width, candidate.height,
                source, 0, 1000, emptyList())
            val p = store.createProject("Recovery").project.id
            val a = store.addReviewedStep(p, input.copy(captureId = id()), "A").steps.single().id
            val b = store.addReviewedStep(p, input.copy(captureId = id()), "B").steps.last().id
            store.setStartStep(p, a)
            var workspace = newWorkspace(app, owner)
            command(workspace) { openProject(p) }; command(workspace) { openStep(a) }
            withContext(Dispatchers.Main.immediate) {
                workspace.editTitle("Local A")
                workspace.putNextAction(ProjectNextAction(id(), "Branch input", b))
            }
            staged(workspace)
            command(workspace) { openStep(b) }
            val pending = EditorPendingForm(EditorFormKind.NAME, title = "Not yet applied", description = "Raw\n  multiline input  ")
            withContext(Dispatchers.Main.immediate) { workspace.editPendingForm(p, b, pending) }
            staged(workspace)
            val beforeRestart = checkNotNull(store.readProject(p))
            check(beforeRestart.steps.single { it.id == b }.title == "B")
            withContext(Dispatchers.Main.immediate) { owner.clear() }
            owner = ViewModelStore(); workspace = newWorkspace(app, owner)
            command(workspace) { openProject(p) }
            check(workspace.state.value.dirtyStepIds == setOf(a, b)) { "Dirty gate ignored a restored non-selected step" }
            command(workspace) { startPreview() }
            check(workspace.state.value.route != ProjectRoute.PREVIEW)
            command(workspace) { openStep(b) }
            check(workspace.state.value.stepDraft?.pendingForm == pending)
            check(store.readProject(p) == beforeRestart) { "Staging changed formal content or revision" }
            status("PASS editor owner recreation: all step drafts and unapplied raw panel text recover before dirty gates; no formal revision or review change")

            // Cancel removes only the panel, after the current write boundary. No stale event revives it.
            val writeLock = writerLock(workspace)
            writeLock.lock()
            withContext(Dispatchers.Main.immediate) {
                workspace.editPendingForm(p, b, pending.copy(title = "Queued old input"))
                workspace.editPendingForm(p, b, null)
            }
            writeLock.unlock()
            settled(workspace)
            check(workspace.state.value.stepDraft?.pendingForm == null && b !in workspace.state.value.dirtyStepIds)
            check(b !in store.readEditorDrafts(p))
            command(workspace) { openStep(a) }
            withContext(Dispatchers.Main.immediate) { workspace.editDescription("Local description") }
            staged(workspace)
            store.updateStep(p, a, "Other saved title", "")
            command(workspace) { reload() }
            check(workspace.state.value.stepDraft?.title == "Local A")
            check(workspace.state.value.stepDraft?.conflicts == setOf("标题"))
            check(store.readProject(p)?.steps?.single { it.id == a }?.title == "Other saved title")
            withContext(Dispatchers.Main.immediate) { workspace.resolveDraftConflict(false) }
            staged(workspace)
            check(workspace.state.value.stepDraft?.title == "Other saved title")
            check(workspace.state.value.stepDraft?.description == "Local description")
            command(workspace) { saveStepDraft() }
            settled(workspace)
            check(a !in store.readEditorDrafts(p))
            check(store.readProject(p)?.steps?.single { it.id == a }?.description == "Local description")
            status("PASS editor conflict and cancel: keep both saved/local text until explicit choice; retain non-conflicting edits; cancel and save cannot resurrect queued panel data")

            command(workspace) { openStep(b) }
            db(root) { execSQL("CREATE TRIGGER editor_fail BEFORE INSERT ON editor_drafts BEGIN SELECT RAISE(FAIL,'injected'); END") }
            val invalid = EditorPendingForm(EditorFormKind.HOTSPOT, objectId = id(), edgeId = id(), label = "Typed action",
                left = "-", top = "12.", right = "", bottom = "8x", targetStepId = a)
            withContext(Dispatchers.Main.immediate) { workspace.editPendingForm(p, b, invalid) }
            withTimeout(10_000) { workspace.state.first { it.stepDraft?.recoveryStatus == DraftRecoveryStatus.FAILED } }
            check(workspace.state.value.stepDraft?.pendingForm == invalid)
            db(root) { execSQL("DROP TRIGGER editor_fail") }
            withContext(Dispatchers.Main.immediate) { workspace.retryDraftStaging() }
            staged(workspace)
            check(store.readEditorDrafts(p).getValue(b).pendingForm == invalid)
            // A stale callback from the previously displayed step must not touch this form.
            withContext(Dispatchers.Main.immediate) { workspace.editPendingForm(p, a, null) }
            check(workspace.state.value.stepDraft?.pendingForm == invalid)
            writeLock.lock()
            withContext(Dispatchers.Main.immediate) {
                workspace.editPendingForm(p, b, invalid.copy(label = "Old queued write"))
                workspace.discardStepDraft()
            }
            writeLock.unlock()
            idle(workspace); settled(workspace)
            check(b !in store.readEditorDrafts(p) && workspace.state.value.stepDraft?.pendingForm == null)
            // Hold Main at the IO return boundary, observe the committed row deletion, then
            // cancel before Main can receive it. A completed discard must never be restaged.
            withContext(Dispatchers.Main.immediate) { workspace.editPendingForm(p, b, invalid) }
            staged(workspace)
            coroutineScope {
                val cleared = CountDownLatch(1)
                val watcher = async(Dispatchers.IO) {
                    withTimeout(10_000) { while (b in store.readEditorDrafts(p)) delay(1) }
                    cleared.countDown()
                }
                withContext(Dispatchers.Main.immediate) {
                    workspace.discardStepDraft()
                    check(cleared.await(10, TimeUnit.SECONDS)) { "Discard did not reach its commit boundary" }
                    workspace.cancel()
                }
                watcher.await()
            }
            idle(workspace); settled(workspace)
            check(b !in store.readEditorDrafts(p) && workspace.state.value.stepDraft?.pendingForm == null)
            status("PASS editor failed write and discard commit cancellation: exact input survives failure/retry; cancel after SQL clear cannot resurrect the discarded form")

            command(workspace) { openStep(a) }
            withContext(Dispatchers.Main.immediate) { workspace.editTitle("Keep this text") }
            staged(workspace)
            store.deleteStep(p, b)
            command(workspace) { reload() }; settled(workspace)
            check(workspace.state.value.stepDraft?.title == "Keep this text")
            check(workspace.state.value.stepDraft?.nextAction?.targetStepId == null)
            check(workspace.state.value.stepDraft?.conflicts?.isEmpty() == true)
            command(workspace) { deleteProject(p) }
            check(store.readEditorDrafts(p).isEmpty())
            status("PASS editor deletion reconciliation: deleted targets stay unresolved while unrelated text survives; project deletion clears staged rows")
            status("NOT_COVERED editor recovery: actual process kill, filesystem power loss, IME and device lifecycle remain device checks")
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                withContext(NonCancellable + Dispatchers.Main.immediate) { owner.clear() }
                check(root.canonicalFile.parentFile == parent && root.deleteRecursively())
            }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun checkPendingMerge() {
        val original = EditorDraftFields("A", "X", false, emptyList())
        val localText = original.copy(title = "B")
        val saved1 = original.copy(title = "C", description = "Y")
        val merged1 = EditorDraftReconciliation.merge(original, localText, saved1)
        val base1 = EditorDraftReconciliation.advanceBase(original, localText, saved1, null)
        val merged2 = EditorDraftReconciliation.merge(base1, merged1.fields, saved1.copy(description = "Z"))
        check(merged2.conflicts == setOf("标题") && merged2.fields.description == "Z")
        val a = id(); val b = id()
        val spot = ProjectHotspot(id(), "Tap", OpaqueMask(.25f, .1f, .5f, .4f), a, null, id())
        val base = EditorDraftFields("Title", "", false, listOf(spot))
        val pending = EditorPendingForm(EditorFormKind.HOTSPOT, objectId = spot.id, edgeId = spot.edgeId,
            label = "Tap", left = "25", top = "10", right = "50", bottom = "40", targetStepId = b)
        val ending = base.copy(hotspots = listOf(spot.copy(targetStepId = null, endLabel = "Official end")))
        val conflict = EditorDraftReconciliation.mergePending(pending, base, ending)
        check(conflict.second == setOf("热点") && conflict.first?.targetStepId == b && conflict.first?.endsDemo == false)
        val chooseSaved = EditorDraftReconciliation.mergePending(pending, base, ending, true)
        check(chooseSaved.first?.endsDemo == true && chooseSaved.first?.targetStepId == null)
        val moved = base.copy(hotspots = listOf(spot.copy(rect = OpaqueMask(.3f, .1f, .5f, .4f))))
        val untouched = EditorDraftReconciliation.mergePending(pending.copy(targetStepId = a), base, moved)
        check(untouched.second.isEmpty() && untouched.first?.left == (.3f * 100f).toString().removeSuffix(".0"))
        val empty = base.copy(hotspots = emptyList())
        val newButton = EditorPendingForm(EditorFormKind.NEXT_ACTION, objectId = id(), label = "Local", targetStepId = a)
        val occupied = empty.copy(nextAction = ProjectNextAction(id(), "Other saved", b))
        check(EditorDraftReconciliation.mergePending(newButton, empty, occupied).second == setOf("下一步"))
        check(EditorDraftReconciliation.mergePending(newButton, empty, occupied, true).first == null)
        val explicitEnd = pending.copy(targetStepId = a, endsDemo = true, endLabel = "My ending")
        val pruned = EditorDraftReconciliation.prune(explicitEnd, setOf(b))
        check(pruned?.endsDemo == true && pruned.targetStepId == null && pruned.endLabel == "My ending")
    }

    private suspend fun newWorkspace(app: Application, owner: ViewModelStore): ProjectWorkspace {
        val result = withContext(Dispatchers.Main.immediate) {
            ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[ProjectWorkspace::class.java]
        }
        idle(result)
        return result
    }
    private suspend fun command(workspace: ProjectWorkspace, command: ProjectWorkspace.() -> Unit) {
        withContext(Dispatchers.Main.immediate) { workspace.command() }
        idle(workspace)
    }
    private suspend fun idle(workspace: ProjectWorkspace) {
        withTimeout(10_000) { workspace.state.first { !it.busy } }
    }
    private suspend fun staged(workspace: ProjectWorkspace) {
        withTimeout(10_000) { workspace.state.first { it.stepDraft?.recoveryStatus == DraftRecoveryStatus.STAGED } }
    }
    private suspend fun settled(workspace: ProjectWorkspace) {
        withTimeout(10_000) { workspace.state.first { !it.busy && it.stepDraft?.recoveryStatus != DraftRecoveryStatus.STAGING } }
    }
    private fun writerLock(workspace: ProjectWorkspace): Mutex = ProjectWorkspace::class.java
        .getDeclaredField("draftWriteLock").apply { isAccessible = true }.get(workspace) as Mutex
    private fun db(root: File, block: SQLiteDatabase.() -> Unit) {
        SQLiteDatabase.openDatabase(File(root, "projects.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { it.block() }
    }
    private fun id() = UUID.randomUUID().toString()
}
