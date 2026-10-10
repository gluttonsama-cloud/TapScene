package com.tapscene.ui

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.EditorFormKind
import com.tapscene.data.EditorPendingForm
import com.tapscene.data.HostProjectFixture
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
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
