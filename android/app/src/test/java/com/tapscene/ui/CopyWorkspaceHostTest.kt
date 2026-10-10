package com.tapscene.ui

import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.EditorFormKind
import com.tapscene.data.EditorPendingForm
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostProjectFixture
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Production workspace, PNG copier and SQLite; not device touch or process-death proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class CopyWorkspaceHostTest {
    @Before fun configureSqlite() = HostSqlite.configure()
    @After fun closeHostDescriptors() = HostFileSyncShadow.reset()

    @Test fun copyHandlesSourceInputCancellationAndOtherStepRecovery() = runBlocking {
        check(Looper.myLooper() != Looper.getMainLooper())
        withTimeout(120_000) {
            HostProjectFixture().use { fixture ->
                HostFileSyncShadow.begin(fixture.root)
                materializeSafeImages(fixture)
                var owner = ViewModelStore()
                var workspace = newWorkspace(fixture, owner)
                try {
                    val p = fixture.project
                    val a = fixture.a
                    val b = fixture.b
                    val original = fixture.snapshot()
                    command(workspace) { openProject(p) }
                    command(workspace) { openStep(b) }
                    main { workspace.editDescription("Keep this other step's unsaved text") }
                    settled(workspace)
                    val otherDraft = fixture.store.readEditorDrafts(p).getValue(b)
                    command(workspace) { openStep(a) }
                    main { workspace.editTitle("Do not copy this unsaved title") }
                    settled(workspace)
                    command(workspace) { copySavedStep(a) }
                    check(fixture.snapshot() == original)
                    check(workspace.state.value.message.orEmpty().contains("先打开这一步"))
                    check(workspace.state.value.stepDraft!!.title == "Do not copy this unsaved title")
                    command(workspace) { discardStepDraft() }

                    // Raw restored panel input must gate copying even from the storyboard.
                    val pending = EditorPendingForm(EditorFormKind.HOTSPOT, objectId = fixture.id(), edgeId = fixture.id(),
                        label = "Unapplied raw input", left = "-", top = "not a number", right = "", bottom = "",
                        targetStepId = b)
                    main { workspace.editPendingForm(p, a, pending) }
                    settled(workspace)
                    clearWorkspace(fixture, owner, workspace)
                    owner = ViewModelStore()
                    workspace = newWorkspace(fixture, owner)
                    command(workspace) { openProject(p) }
                    command(workspace) { copySavedStep(a) }
                    check(fixture.snapshot() == original)
                    check(workspace.state.value.dirtyStepIds == setOf(a, b))
                    check(fixture.store.readEditorDrafts(p).getValue(a).pendingForm == pending)
                    command(workspace) { openStep(a) }
                    check(workspace.state.value.stepDraft!!.pendingForm == pending)
                    command(workspace) { discardStepDraft() }
                    command(workspace) { leaveEditor() }

                    // Cancellation while waiting for the existing draft-write barrier writes no copy.
                    val writer = field(workspace, "draftWriteLock") as Mutex
                    writer.lock()
                    try { main { workspace.copySavedStep(a); cancelRunning(workspace) } }
                    finally { writer.unlock() }
                    settled(workspace)
                    check(fixture.snapshot() == original)
                    check(fixture.store.readEditorDrafts(p) == mapOf(b to otherDraft))

                    // Keep Main blocked until another real connection sees SQL commit, then cancel
                    // before IO can return its snapshot. Reconciliation must recognize exactly one copy.
                    cancelAfterCommit(workspace, { copySavedStep(a); copySavedStep(a) }) {
                        fixture.store.readProject(p)?.steps?.size == original.steps.size + 1
                    }
                    val copied = fixture.snapshot()
                    val copy = copied.steps.single { it.id !in original.steps.map { source -> source.id } }
                    check(copied.steps.map { it.id } == listOf(a, copy.id, b))
                    check(copy.title == original.steps.first().title)
                    check(workspace.state.value.project == copied && !workspace.state.value.busy)
                    check(workspace.state.value.message.orEmpty().contains("步骤已复制"))
                    check(workspace.state.value.message.orEmpty().contains("接入路线"))
                    check(workspace.state.value.message.orEmpty().contains("过渡"))
                    check(fixture.store.readEditorDrafts(p) == mapOf(b to otherDraft.copy(baseRevision = copied.project.revision)))
                    check(workspace.state.value.dirtyStepIds == setOf(b))
                    check((field(workspace, "pendingStepCopies") as Map<*, *>).isEmpty())
                    val count = copied.steps.size
                    command(workspace) { reload() }
                    check(fixture.snapshot().steps.size == count)
                    command(workspace) { openStep(b) }
                    check(workspace.state.value.stepDraft!!.description == otherDraft.edit.description)
                    clearWorkspace(fixture, owner, workspace)
                    owner = ViewModelStore()
                    workspace = newWorkspace(fixture, owner)
                    command(workspace) { openProject(p) }
                    check(workspace.state.value.project == copied && workspace.state.value.dirtyStepIds == setOf(b))
                    command(workspace) { openStep(b) }
                    check(workspace.state.value.stepDraft!!.description == otherDraft.edit.description)
                    println("HOST_STEP_COPY_WORKSPACE source-text/restored-panel=blocked before-commit=unchanged after-commit=one-copy repeated-tap=ignored other-draft/reopen=preserved")

                    // An isolated, reversible schema fault makes the production refresh really
                    // fail after commit. No production test hook or fake returned snapshot.
                    command(workspace) { leaveEditor() }
                    var sortColumnHidden = false
                    try {
                        cancelAfterCommit(workspace, { copySavedStep(a) }, afterCommit = {
                            // Rename a column referenced explicitly by the production ORDER BY.
                            // SELECT p.* alone can retain cached column metadata after a rename.
                            fixture.database { execSQL("ALTER TABLE projects RENAME COLUMN updated_at TO copy_test_updated_at") }
                            sortColumnHidden = true
                            val failure = runCatching { fixture.store.listProjects() }.exceptionOrNull()
                            check(failure is SQLiteException && failure.message.orEmpty().contains("updated_at")) {
                                "Read-failure injection did not reach production SQL: $failure"
                            }
                        }) { fixture.store.readProject(p)?.steps?.size == copied.steps.size + 1 }
                        val unreadable = workspace.state.value
                        check(unreadable.loadFailed) { "Commit reread unexpectedly succeeded: ${unreadable.message}" }
                        check(unreadable.message.orEmpty().contains("无法确认复制结果")) {
                            "Unknown commit did not retain its recovery guidance: ${unreadable.message}"
                        }
                        check((field(workspace, "pendingStepCopies") as Map<*, *>).size == 1)
                    } finally {
                        if (sortColumnHidden) fixture.database { execSQL("ALTER TABLE projects RENAME COLUMN copy_test_updated_at TO updated_at") }
                    }
                    val uncertain = fixture.snapshot()
                    check(uncertain.steps.size == copied.steps.size + 1)
                    command(workspace) { reload() }
                    command(workspace) { copySavedStep(a) }
                    check(fixture.snapshot() == uncertain) { "Retry after unreadable commit created a duplicate" }
                    check(workspace.state.value.message.orEmpty().contains("步骤已复制"))
                    check((field(workspace, "pendingStepCopies") as Map<*, *>).isEmpty())
                    check(fixture.store.readEditorDrafts(p) == mapOf(b to otherDraft.copy(baseRevision = uncertain.project.revision)))
                    println("HOST_STEP_COPY_WORKSPACE committed-refresh-failure=uncertain recovered-reload/retry=exactly-one-copy original-operation-reconciled")
                } finally { clearWorkspace(fixture, owner, workspace) }
            }
        }
    }

    private fun materializeSafeImages(fixture: HostProjectFixture) {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = try { ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
        } } finally { bitmap.recycle() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fixture.snapshot().steps.forEach { step ->
            val file = File(fixture.root, step.asset.privateRelativePath)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            file.writeBytes(bytes)
            fixture.database { execSQL("UPDATE local_assets SET sha256=?,byte_length=? WHERE project_id=? AND asset_id=?",
                arrayOf<Any>(sha, bytes.size, fixture.project, step.asset.id)) }
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

    private suspend fun settled(workspace: ProjectWorkspace) {
        withTimeout(10_000) {
            workspace.state.first { !it.busy && it.stepDraft?.recoveryStatus != DraftRecoveryStatus.STAGING }
            val jobs = main { listOfNotNull(field(workspace, "task") as Job?, field(workspace, "stagingTask") as Job?) }
            jobs.forEach { it.join() }
        }
    }

    private fun cancelRunning(workspace: ProjectWorkspace) {
        val task = checkNotNull(field(workspace, "task") as Job?)
        check(task.isActive)
        workspace.cancel()
        check(task.isCancelled)
    }

    private suspend fun cancelAfterCommit(workspace: ProjectWorkspace, action: ProjectWorkspace.() -> Unit,
        afterCommit: () -> Unit = {}, committed: () -> Boolean,
    ) = coroutineScope {
        check(!committed())
        val latch = CountDownLatch(1)
        val observer = async(Dispatchers.IO) {
            withTimeout(10_000) { while (!committed()) delay(1) }
            afterCommit()
            latch.countDown()
        }
        main {
            workspace.action()
            check(latch.await(10, TimeUnit.SECONDS)) { "Copy did not reach its SQL commit boundary" }
            cancelRunning(workspace)
        }
        observer.await()
        settled(workspace)
    }

    private suspend fun clearWorkspace(fixture: HostProjectFixture, owner: ViewModelStore, workspace: ProjectWorkspace) {
        withContext(NonCancellable) {
            val jobs = main { owner.clear(); listOfNotNull(field(workspace, "task") as Job?, field(workspace, "stagingTask") as Job?) }
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
