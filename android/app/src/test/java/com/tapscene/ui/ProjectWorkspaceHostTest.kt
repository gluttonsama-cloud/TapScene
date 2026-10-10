package com.tapscene.ui

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.EditorDraftFields
import com.tapscene.data.EditorFormKind
import com.tapscene.data.EditorPendingForm
import com.tapscene.data.HostProjectFixture
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectNextAction
import com.tapscene.data.ProjectStore
import com.tapscene.data.ProjectTransition
import com.tapscene.data.TransitionAsset
import com.tapscene.media.ImportedSource
import com.tapscene.media.SourceMetadata
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class ProjectWorkspaceHostTest {
    @Before fun configureSqlite() = HostSqlite.configure()

    @Test fun singleEditorUndoCoalescesAndHonorsSaveNavigationAndRevisionBoundaries() = runBlocking {
        check(Looper.myLooper() != Looper.getMainLooper())
        withTimeout(120_000) {
            HostProjectFixture().use { fixture ->
                var owner = ViewModelStore()
                var workspace = newWorkspace(fixture, owner)
                try {
                    val p = fixture.project
                    val a = fixture.a
                    val b = fixture.b
                    val original = fixture.snapshot()
                    val step = original.steps.single { it.id == a }
                    val hotspot = step.hotspots.single()
                    command(workspace) { openProject(p) }
                    command(workspace) { openStep(b) }
                    main { workspace.editDescription("Other step remains pending") }
                    staged(workspace)
                    val otherDraft = fixture.store.readEditorDrafts(p).getValue(b)
                    command(workspace) { openStep(a) }
                    check(!workspace.state.value.canUndoEdit)

                    main { workspace.removeHotspot(hotspot.id) }
                    staged(workspace)
                    check(workspace.state.value.canUndoEdit && workspace.state.value.stepDraft!!.hotspots.isEmpty())
                    val operationLock = field(workspace, "operationLock") as Mutex
                    operationLock.lock()
                    try {
                        main { workspace.undoEditorEdit(); cancelRunningCommand(workspace) }
                    } finally { operationLock.unlock() }
                    settled(workspace)
                    check(workspace.state.value.canUndoEdit && workspace.state.value.stepDraft!!.hotspots.isEmpty()) {
                        "Cancelling before undo execution consumed history or changed the draft"
                    }
                    command(workspace) { undoEditorEdit(); undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.hotspots.single() == hotspot) {
                        "Undo changed the hotspot's stable identity, rectangle, edge or target"
                    }
                    check(!workspace.state.value.canUndoEdit)
                    check(fixture.snapshot() == original)
                    check(fixture.store.readEditorDrafts(p) == mapOf(b to otherDraft))

                    // Contiguous typing is one action; changing fields replaces the one-level history.
                    main { workspace.editTitle("Typing"); workspace.editTitle("Typing a title") }
                    staged(workspace)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.title == step.title)
                    main {
                        workspace.editTitle("Keep this title")
                        workspace.editDescription("Typing")
                        workspace.editDescription("Typing a description")
                    }
                    staged(workspace)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.let { it.title == "Keep this title" && it.description == step.description })
                    check(!workspace.state.value.canUndoEdit)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.title == "Keep this title")
                    command(workspace) { discardStepDraft() }
                    main {
                        workspace.editTitle("First focus session")
                        workspace.endEditorTextEdit()
                        workspace.editTitle("Second focus session")
                    }
                    staged(workspace)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.title == "First focus session")
                    command(workspace) { discardStepDraft() }

                    // Synthetic model-only binding: no media files/decoder or formal rows are created.
                    val staleTransition = ProjectTransition(
                        TransitionAsset(fixture.id(), "transitions/stale.mp4", "a".repeat(64), 1, 1, 1, 1_000_000),
                        ImportedSource(fixture.id(), "sources/stale.mp4", "Stale private source",
                            SourceMetadata("video/avc", 1, "b".repeat(64), 1, 1, 0, 1_000_000)),
                        0, 1_000_000, emptyList(), fixture.id())
                    main { workspace.putHotspot(hotspot.copy(transition = staleTransition)) }
                    settled(workspace)
                    main { workspace.removeHotspot(hotspot.id) }
                    staged(workspace)
                    val undo = checkNotNull(field(workspace, "editorUndo"))
                    val undoFields = undo.javaClass.getDeclaredField("fields").apply { isAccessible = true }.get(undo) as EditorDraftFields
                    check(undoFields.hotspots.all { it.transition == null } && undoFields.nextAction?.transition == null)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.hotspots.single() == hotspot) { "Undo restored a stale media binding" }

                    // Authored-object operations do not coalesce like text input.
                    val firstHotspot = hotspot.copy(label = "First hotspot edit")
                    main {
                        workspace.putHotspot(firstHotspot)
                        workspace.putHotspot(hotspot.copy(label = "Second hotspot edit"))
                    }
                    staged(workspace)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.hotspots.single() == firstHotspot)
                    val next = ProjectNextAction(fixture.id(), "First next action", b)
                    main { workspace.putNextAction(next); workspace.putNextAction(next.copy(label = "Second next action")) }
                    staged(workspace)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.nextAction == next)
                    main { workspace.removeNextAction() }
                    staged(workspace)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.nextAction == next)
                    command(workspace) { discardStepDraft() }
                    main { workspace.removeHotspot(hotspot.id); workspace.editTerminal(true) }
                    staged(workspace)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.let { !it.isTerminal && it.hotspots.isEmpty() })
                    command(workspace) { discardStepDraft() }
                    check(!workspace.state.value.canUndoEdit)
                    println("HOST_EDITOR_UNDO stable-hotspot-and-discrete-actions: stable IDs/targets, one-level text coalescing, independent object edits")

                    // Keep the raw, intentionally invalid baseline byte-for-byte through undo/staging.
                    val panel = EditorPendingForm(EditorFormKind.HOTSPOT, objectId = hotspot.id,
                        edgeId = hotspot.edgeId, label = hotspot.label, left = "  -", top = "20",
                        right = "60", bottom = "80", targetStepId = b)
                    main { workspace.editPendingForm(p, a, panel) }
                    staged(workspace)
                    check(!workspace.state.value.canUndoEdit) { "Opening a panel created undo history" }
                    main {
                        workspace.editPendingForm(p, a, panel.copy(left = "-."))
                        workspace.editPendingForm(p, a, panel.copy(left = "not a number  "))
                    }
                    staged(workspace)
                    command(workspace) { savePendingStepForm() }
                    check(workspace.state.value.canUndoEdit)
                    check(workspace.state.value.stepDraft!!.pendingForm?.left == "not a number  ")
                    check(fixture.snapshot() == original)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.pendingForm == panel)
                    check(fixture.store.readEditorDrafts(p).getValue(a).pendingForm == panel)
                    check(!workspace.state.value.canUndoEdit)
                    main { workspace.editPendingForm(p, a, panel.copy(label = "Changed panel label")) }
                    staged(workspace)
                    check(workspace.state.value.canUndoEdit)
                    val namePanel = EditorPendingForm(EditorFormKind.NAME, title = step.title, description = step.description)
                    main { workspace.editPendingForm(p, a, namePanel) }
                    staged(workspace)
                    check(!workspace.state.value.canUndoEdit) { "Switching panel kind retained stale history" }
                    main { workspace.editPendingForm(p, a, namePanel.copy(title = "Cancelled panel input")) }
                    staged(workspace)
                    check(workspace.state.value.canUndoEdit)
                    main { workspace.editPendingForm(p, a, null) }
                    settled(workspace)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.pendingForm == null && !workspace.state.value.canUndoEdit)
                    check(a !in fixture.store.readEditorDrafts(p)) { "Cancelling then undoing revived a panel" }
                    println("HOST_EDITOR_UNDO raw-panel-and-cancel: invalid input restored exactly, validation failure retains history, closed panel cannot revive")

                    main { workspace.editDescription("Failed save input") }
                    staged(workspace)
                    val failedDrafts = fixture.store.readEditorDrafts(p)
                    fixture.database { execSQL("""CREATE TRIGGER reject_undo_test_save BEFORE UPDATE OF draft_revision ON projects
                        BEGIN SELECT RAISE(ABORT, 'injected undo save failure'); END""") }
                    try { command(workspace) { saveStepDraft() } }
                    finally { fixture.database { execSQL("DROP TRIGGER reject_undo_test_save") } }
                    check(fixture.snapshot() == original)
                    check(fixture.store.readEditorDrafts(p) == failedDrafts)
                    check(workspace.state.value.canUndoEdit && workspace.state.value.stepDraft!!.description == "Failed save input")
                    fixture.database { execSQL("""CREATE TRIGGER reject_undo_test_stage BEFORE DELETE ON editor_drafts
                        WHEN OLD.project_id='$p' AND OLD.state_id='$a'
                        BEGIN SELECT RAISE(ABORT, 'injected undo staging failure'); END""") }
                    try {
                        command(workspace) { undoEditorEdit() }
                        check(workspace.state.value.stepDraft!!.let {
                            it.description == step.description && it.recoveryStatus == DraftRecoveryStatus.FAILED
                        }) { "A failed undo write lost the restored input or reported it persisted" }
                        check(!workspace.state.value.canUndoEdit)
                        check(fixture.store.readEditorDrafts(p) == failedDrafts)
                    } finally { fixture.database { execSQL("DROP TRIGGER reject_undo_test_stage") } }
                    main { workspace.retryDraftStaging() }
                    settled(workspace)
                    check(workspace.state.value.stepDraft!!.let {
                        it.description == step.description && it.recoveryStatus == DraftRecoveryStatus.NONE
                    })
                    check(fixture.store.readEditorDrafts(p) == mapOf(b to otherDraft))

                    main { workspace.editTitle("Formally saved title") }
                    staged(workspace)
                    command(workspace) { saveStepDraft() }
                    val saved = fixture.snapshot()
                    check(saved.project.revision == original.project.revision + 1)
                    check(saved.steps.single { it.id == a }.title == "Formally saved title")
                    check(!workspace.state.value.canUndoEdit)
                    command(workspace) { undoEditorEdit() }
                    check(fixture.snapshot() == saved)
                    check(fixture.store.readEditorDrafts(p) == mapOf(b to otherDraft.copy(baseRevision = saved.project.revision)))
                    println("HOST_EDITOR_UNDO save-boundary: SQL failure retains undo, successful save clears history without changing another step's edits")

                    main { workspace.editTitle("Unsaved title survives navigation") }
                    staged(workspace)
                    command(workspace) { leaveEditor() }
                    check(workspace.state.value.route == ProjectRoute.STEPS && !workspace.state.value.canUndoEdit)
                    command(workspace) { openStep(a) }
                    check(workspace.state.value.stepDraft!!.title == "Unsaved title survives navigation" && !workspace.state.value.canUndoEdit)
                    main { workspace.editTitle("Unsaved title after Back") }
                    staged(workspace)
                    command(workspace) { back() }
                    check(!workspace.state.value.canUndoEdit)
                    command(workspace) { openStep(a) }
                    main { workspace.editTitle("Unsaved title after changing steps") }
                    staged(workspace)
                    command(workspace) { openStep(b) }
                    check(!workspace.state.value.canUndoEdit)
                    command(workspace) { openStep(a) }
                    check(!workspace.state.value.canUndoEdit)
                    clearWorkspace(fixture, owner, workspace)
                    owner = ViewModelStore()
                    workspace = newWorkspace(fixture, owner)
                    command(workspace) { openProject(p) }
                    command(workspace) { openStep(a) }
                    check(workspace.state.value.stepDraft!!.title == "Unsaved title after changing steps")
                    check(!workspace.state.value.canUndoEdit) { "Recreating the owner restored in-memory history" }
                    check(fixture.snapshot() == saved)
                    command(workspace) { discardStepDraft() }
                    check(!workspace.state.value.canUndoEdit && a !in fixture.store.readEditorDrafts(p))
                    check(fixture.store.readEditorDrafts(p) == mapOf(b to otherDraft.copy(baseRevision = saved.project.revision)))
                    println("HOST_EDITOR_UNDO navigation-and-recreation: draft persists, history clears after leave/Back/step change/discard/reopen")

                    main { workspace.editDescription("Local draft survives external changes") }
                    staged(workspace)
                    check(workspace.state.value.canUndoEdit)
                    val external = fixture.store.renameProject(p, "Externally renamed project")
                    command(workspace) { undoEditorEdit() }
                    check(!workspace.state.value.canUndoEdit)
                    check(fixture.snapshot() == external) { "Undo wrote over a newer formal revision" }
                    check(workspace.state.value.stepDraft!!.description == "Local draft survives external changes")
                    check(fixture.store.readEditorDrafts(p).getValue(a).edit.description == "Local draft survives external changes")
                    check(fixture.store.readEditorDrafts(p).getValue(b) == otherDraft.copy(baseRevision = external.project.revision))
                    main { workspace.removeHotspot(hotspot.id) }
                    staged(workspace)
                    check(workspace.state.value.canUndoEdit)
                    val deleted = fixture.store.deleteStep(p, b).snapshot
                    command(workspace) { undoEditorEdit() }
                    check(!workspace.state.value.canUndoEdit)
                    check(fixture.snapshot() == deleted)
                    check(workspace.state.value.stepDraft!!.let { it.hotspots.isEmpty() && it.description == "Local draft survives external changes" })
                    check(fixture.store.readEditorDrafts(p).getValue(a).edit.let {
                        it.hotspots.isEmpty() && it.description == "Local draft survives external changes"
                    }) { "A stale undo revived a deleted target or lost surviving draft text" }
                    println("HOST_EDITOR_UNDO external-revision-and-deleted-target: stale undo blocked, surviving draft retained, no target resurrection")
                } finally { clearWorkspace(fixture, owner, workspace) }
            }
        }
    }

    @Test fun cancellationAndDiscardDoNotReviveCommittedForms() = runBlocking {
        // INSTRUMENTATION_TEST keeps this blocking test thread separate from Android Main.
        check(Looper.myLooper() != Looper.getMainLooper())
        withTimeout(60_000) {
            HostProjectFixture().use { fixture ->
                var owner = ViewModelStore()
                var workspace = newWorkspace(fixture, owner)
                try {
                    val p = fixture.project
                    val a = fixture.a
                    val b = fixture.b
                    command(workspace) { openProject(p) }
                    command(workspace) { openStep(a) }
                    main { workspace.editDescription("Other step remains pending") }
                    staged(workspace)
                    val otherDraft = fixture.store.readEditorDrafts(p).getValue(a)
                    command(workspace) { openStep(b) }
                    val pending = EditorPendingForm(EditorFormKind.NAME, title = "Boundary panel", description = "Raw\n  multiline input  ")
                    main { workspace.editPendingForm(p, b, pending) }
                    staged(workspace)
                    val before = fixture.snapshot()
                    val beforeDrafts = fixture.store.readEditorDrafts(p)
                    val lock = field(workspace, "draftWriteLock") as Mutex

                    lock.lock()
                    try {
                        main {
                            workspace.savePendingStepForm()
                            cancelRunningCommand(workspace)
                        }
                    } finally { lock.unlock() }
                    settled(workspace)
                    check(fixture.snapshot() == before)
                    check(fixture.store.readEditorDrafts(p) == beforeDrafts)
                    check(workspace.state.value.stepDraft?.pendingForm == pending)
                    println("HOST_CANCEL save-before-commit: cancelled task, unchanged graph and raw form")

                    cancelAfterCommit(workspace, { savePendingStepForm() }) {
                        fixture.store.readProject(p)?.steps?.single { it.id == b }?.title == pending.title
                    }
                    val saved = fixture.snapshot()
                    check(saved.project.revision == before.project.revision + 1)
                    check(saved.steps.single { it.id == b }.description == pending.description.trim())
                    check(workspace.state.value.stepDraft?.pendingForm == null)
                    check(workspace.state.value.stepDraft?.title == pending.title)
                    check(fixture.store.readEditorDrafts(p) == mapOf(a to otherDraft.copy(baseRevision = saved.project.revision)))
                    println("HOST_CANCEL save-after-commit: cancelled task, committed snapshot recognized, no form revival")

                    val discard = pending.copy(title = "This must be discarded")
                    main { workspace.editPendingForm(p, b, discard) }
                    staged(workspace)
                    val discardDrafts = fixture.store.readEditorDrafts(p)
                    lock.lock()
                    try {
                        main {
                            workspace.discardStepDraft()
                            cancelRunningCommand(workspace)
                        }
                    } finally { lock.unlock() }
                    settled(workspace)
                    check(fixture.store.readEditorDrafts(p) == discardDrafts)
                    check(workspace.state.value.stepDraft?.pendingForm == discard)

                    // After both queued staging and discard drain, no abandoned form may remain.
                    lock.lock()
                    try {
                        main {
                            workspace.editPendingForm(p, b, discard.copy(title = "Queued stale input"))
                            workspace.discardStepDraft()
                        }
                    } finally { lock.unlock() }
                    settled(workspace)
                    check(b !in fixture.store.readEditorDrafts(p))
                    check(workspace.state.value.stepDraft?.pendingForm == null)
                    main { workspace.editPendingForm(p, b, discard) }
                    staged(workspace)
                    cancelAfterCommit(workspace, { discardStepDraft() }) { b !in fixture.store.readEditorDrafts(p) }
                    check(workspace.state.value.stepDraft?.pendingForm == null)
                    check(fixture.snapshot() == saved) { "Discard changed formal graph/revision" }
                    check(a in fixture.store.readEditorDrafts(p) && b !in fixture.store.readEditorDrafts(p))
                    println("HOST_CANCEL discard-before/after-commit and queued stale write: exact input retained or removed at the real SQL boundary")

                    clearWorkspace(fixture, owner, workspace)
                    owner = ViewModelStore()
                    workspace = newWorkspace(fixture, owner)
                    command(workspace) { openProject(p) }
                    command(workspace) { openStep(b) }
                    check(workspace.state.value.stepDraft?.pendingForm == null)
                    check(workspace.state.value.stepDraft?.title == pending.title)
                    check(workspace.state.value.dirtyStepIds == setOf(a))
                    check(fixture.newStore().readProject(p) == saved)
                    check(b !in fixture.store.readEditorDrafts(p))
                    println("HOST_CANCEL owner/store recreation: saved form and discarded input stay cleared; other step retained")
                } finally { clearWorkspace(fixture, owner, workspace) }
            }
        }
    }

    private suspend fun newWorkspace(fixture: HostProjectFixture, owner: ViewModelStore): ProjectWorkspace {
        val workspace = main {
            ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(fixture.app))[ProjectWorkspace::class.java]
        }
        settled(workspace)
        return workspace
    }

    private suspend fun command(workspace: ProjectWorkspace, action: ProjectWorkspace.() -> Unit) {
        main { workspace.action() }
        settled(workspace)
        check(!workspace.state.value.loadFailed)
    }

    private suspend fun staged(workspace: ProjectWorkspace) {
        withTimeout(10_000) { workspace.state.first { it.stepDraft?.recoveryStatus == DraftRecoveryStatus.STAGED } }
        settled(workspace)
    }

    private suspend fun settled(workspace: ProjectWorkspace) {
        withTimeout(10_000) {
            workspace.state.first { !it.busy && it.stepDraft?.recoveryStatus != DraftRecoveryStatus.STAGING }
            val jobs = main { listOfNotNull(field(workspace, "task") as Job?, field(workspace, "stagingTask") as Job?) }
            jobs.forEach { it.join() }
        }
    }

    private fun cancelRunningCommand(workspace: ProjectWorkspace) {
        val job = checkNotNull(field(workspace, "task") as Job?)
        check(job.isActive) { "Cancellation did not target a running production command" }
        workspace.cancel()
        check(job.isCancelled) { "Production command was not cancelled" }
    }

    private suspend fun cancelAfterCommit(workspace: ProjectWorkspace, action: ProjectWorkspace.() -> Unit,
        committed: () -> Boolean,
    ) {
        check(!committed()) { "Commit observer was already satisfied before the command" }
        coroutineScope {
            val latch = CountDownLatch(1)
            val observer = async(Dispatchers.IO) {
                withTimeout(10_000) { while (!committed()) delay(1) }
                latch.countDown()
            }
            main {
                workspace.action()
                // Freeze Main only after launching the production IO work. The independent
                // connection observes a committed row before Main can accept its continuation.
                check(latch.await(10, TimeUnit.SECONDS)) { "Production command did not reach its SQL commit boundary" }
                cancelRunningCommand(workspace)
            }
            observer.await()
        }
        settled(workspace)
    }

    private suspend fun clearWorkspace(fixture: HostProjectFixture, owner: ViewModelStore, workspace: ProjectWorkspace) {
        withContext(NonCancellable) {
            val jobs = main {
                owner.clear()
                listOfNotNull(field(workspace, "task") as Job?, field(workspace, "stagingTask") as Job?)
            }
            withTimeout(10_000) { jobs.forEach { it.join() } }
            fixture.closeStore(field(workspace, "store") as ProjectStore)
        }
    }

    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main.immediate) {
        check(Looper.myLooper() == Looper.getMainLooper())
        block()
    }

    private fun field(workspace: ProjectWorkspace, name: String): Any? = ProjectWorkspace::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(workspace)
}
