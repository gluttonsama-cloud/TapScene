package com.tapscene.ui.shell

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.tapscene.MainActivity
import com.tapscene.clickplan.*
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import com.tapscene.recording.*
import com.tapscene.ui.ClickChainWorkspace
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Production route/callbacks, review JSON, SQLite and native masked PNGs. Only MP4 inspection
 * uses a bounded synthetic source accessor. No device capture or human privacy review is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClickChainActionPrecisionHostTest {
    private var previousRequestedSize: String? = null
    private val bitmaps = mutableListOf<Bitmap>()
    private val stores = mutableListOf<ProjectStore>()
    private val workspaces = mutableListOf<Pair<ViewModelStoreOwner, ClickChainWorkspace>>()

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetFactory()
            HostSqlite.configure()
            HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
            // Pinned Robolectric otherwise ignores WRAP_CONTENT window size/gravity. Honor
            // measured modal bounds instead of changing production layout or idle assertions.
            previousRequestedSize = System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        }
        override fun after() {
            try { HostFileSyncShadow.reset() } finally {
                resetFactory()
                val key = "robolectric.windowManager.useRequestedSize"
                previousRequestedSize?.let { System.setProperty(key, it) } ?: System.clearProperty(key)
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @After fun closeOwnedResources() = finishWorkspaces(closeStores = true)

    private fun finishWorkspaces(closeStores: Boolean = false) {
        val failures = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) {
            runCatching(block).exceptionOrNull()?.let { failures += it }
        }
        var stopped = workspaces.isEmpty() && bitmaps.isEmpty()
        if (workspaces.isNotEmpty() || bitmaps.isNotEmpty()) {
            val jobs = workspaces.map { checkNotNull(it.second.viewModelScope.coroutineContext[Job]) }
            val locks = workspaces.flatMap { (_, workspace) ->
                listOf("thumbnailReads", "writes").map { field(workspace, it) as Mutex }
            }
            var disposed = false
            attempt {
                compose.runOnUiThread {
                    val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
                    val view = content.getChildAt(0) as ComposeView
                    // A later layout may recreate this attached view's composition; keep it empty.
                    view.setContent { }
                    view.disposeComposition()
                    disposed = true
                }
            }
            // A disposal failure must not skip cancellation of the remaining test owners.
            workspaces.forEach { (owner, _) -> attempt { compose.runOnUiThread { owner.viewModelStore.clear() } } }
            jobs.forEach { it.cancel() }
            attempt {
                // Thumbnail IO belongs to the disposed composition, not the ViewModel scope.
                compose.waitUntil(30_000) {
                    shadowOf(Looper.getMainLooper()).idle()
                    jobs.all { it.isCompleted } && locks.none { it.isLocked }
                }
                runBlocking {
                    withTimeout(10_000) {
                        jobs.forEach { it.join() }
                        locks.forEach { it.withLock { } }
                    }
                }
                stopped = disposed
            }
        }
        // Never close under unfinished IO. RunAfters preserves test and cleanup failures together.
        if (closeStores && stopped) stores.forEach { store -> attempt { closeStore(store) } }
        if (closeStores && stopped) bitmaps.forEach { bitmap -> attempt { if (!bitmap.isRecycled) bitmap.recycle() } }
        MultipleFailureException.assertEmpty(failures)
    }

    @Test fun changedPrecisionApplyPersistsOnlyEditedActionUntilExplicitConfirmation() {
        val f = fixture()
        f.mount()
        assertTrue(f.workspace.state.value.canCreate)
        f.openFirstAction()
        val before = f.saved()
        val files = f.assetBytes()
        val project = f.projects.readProject(f.run.projectId)
        val id = f.run.plan.actions.first().actionId
        val rect = OpaqueMask(.125f, .25f, .625f, .75f)

        openPrecision()
        replacePercentages("12.5", "25", "62.5", "75")
        // Typing is local: even a valid candidate does not invalidate the saved confirmation.
        assertEquals(before, f.saved())
        assertEquals(before.actions.getValue(id).rect, f.workspace.state.value.actionEditor?.rect)
        compose.onNodeWithText("应用范围").assertIsEnabled().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(id, f.workspace.state.value.actionEditor?.actionId)
        assertEquals(rect, f.workspace.state.value.actionEditor?.rect)
        assertFalse(f.workspace.state.value.canCreate)
        assertEquals(1, f.workspace.state.value.confirmedActionCount)

        // The production Return callback must flush the pending edit, not confirm it.
        compose.onNodeWithText("返回整理").assertIsEnabled().performClick()
        f.settled()
        val changed = before.copy(serial = before.serial + 1, actions = before.actions +
            (id to before.actions.getValue(id).copy(rect = rect, confirmed = false)))
        assertEquals("Apply must invoke the existing rectangle mutation exactly once", changed, f.saved())
        assertEquals(files, f.assetBytes())
        assertNeedsAction(f, id)
        compose.onNodeWithText("下一项：核对动作 1 的点击区域与去向").assertIsDisplayed()
        compose.onNodeWithText("生成独立项目").assertDoesNotExist()

        f.reopen()
        val reopened = f.saved()
        assertNotEquals(changed.owner, reopened.owner)
        assertEquals(changed.copy(owner = reopened.owner), reopened)
        assertEquals(files, f.assetBytes())
        assertNeedsAction(f, id)
        compose.onNodeWithText("继续核对动作").assertIsEnabled().performClick()
        f.settled()
        assertEquals(id, f.workspace.state.value.actionEditor?.actionId)
        assertEquals(rect, f.workspace.state.value.actionEditor?.rect)
        val beforeConfirm = f.saved()
        compose.onNodeWithText("确认点击区域与后继").assertIsEnabled().performClick()
        f.awaitSaved { it.actions.getValue(id).confirmed }
        assertEquals(beforeConfirm.copy(serial = beforeConfirm.serial + 1, actions = beforeConfirm.actions +
            (id to beforeConfirm.actions.getValue(id).copy(confirmed = true))), f.saved())
        assertEquals(files, f.assetBytes())
        assertTrue(f.workspace.state.value.canCreate)
        assertEquals(3, f.workspace.state.value.reviewedFrameCount)
        assertEquals(2, f.workspace.state.value.confirmedActionCount)
        assertNull(f.workspace.state.value.nextRequiredReview())
        compose.onNodeWithText("生成独立项目").assertIsEnabled()
        assertEquals(project, f.projects.readProject(f.run.projectId))
        assertEquals(1, f.projects.listProjects().size)
    }

    @Test fun cancelModalBackAndEquivalentApplyKeepConfirmedDraftAndAllBytesUnchanged() {
        val f = fixture()
        f.mount()
        f.openFirstAction()
        val before = f.saved()
        val reviews = f.reviewBytes()
        val files = f.assetBytes()
        val editor = checkNotNull(f.workspace.state.value.actionEditor)

        fun assertUnchanged() {
            compose.onNode(isDialog()).assertDoesNotExist()
            assertEquals(editor.actionId, f.workspace.state.value.actionEditor?.actionId)
            assertEquals(editor.rect, f.workspace.state.value.actionEditor?.rect)
            assertEquals(before, f.saved())
            assertEquals(reviews, f.reviewBytes())
            assertEquals(files, f.assetBytes())
            assertEquals(2, f.workspace.state.value.confirmedActionCount)
            assertTrue(f.workspace.state.value.canCreate)
        }

        openPrecision()
        replacePercentages("12.5", "25", "62.5", "75")
        compose.onNodeWithText("取消").assertIsEnabled().performClick()
        assertUnchanged()

        openPrecision()
        assertPercentage("左边 %", 30f)
        compose.onNodeWithText("左边 %").performTextReplacement("15")
        // The modal owns Back. Activity Back would bypass it and exercise the wrong route.
        compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
            assertTrue(dialog.isShowing)
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        assertUnchanged()

        openPrecision()
        assertPercentage("左边 %", 30f)
        // Changed spelling but equal geometry is still a no-op, not a fresh confirmation.
        replacePercentages("30.0", "30.000", "50.0", "50.000")
        compose.onNodeWithText("应用范围").assertIsEnabled().performClick()
        assertUnchanged()
        compose.onNodeWithText("返回整理").performClick()
        f.settled()
        assertEquals(before, f.saved())
        assertEquals(reviews, f.reviewBytes())
        assertEquals(files, f.assetBytes())
        assertTrue(f.workspace.state.value.canCreate)
    }

    @Test fun invalidPrecisionInputCannotApplyOrMutateConfirmedRectangle() {
        val f = fixture()
        f.mount()
        f.openFirstAction()
        val before = f.saved()
        val reviews = f.reviewBytes()
        val files = f.assetBytes()
        val editor = checkNotNull(f.workspace.state.value.actionEditor)
        openPrecision()

        // Parser boundary matrices live with the geometry helper; here the real UI must gate
        // a missing value, reversed geometry and an out-of-image edge without constructing it.
        listOf("", "60", "-1").forEach { invalidLeft ->
            compose.onNodeWithText("左边 %").performTextReplacement(invalidLeft)
            compose.onNodeWithText("应用范围").assertIsNotEnabled()
            compose.onNodeWithText("取消").assertIsEnabled()
            assertEquals(editor.rect, f.workspace.state.value.actionEditor?.rect)
            assertEquals(before, f.saved())
            assertEquals(reviews, f.reviewBytes())
        }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("返回整理").performClick()
        f.settled()
        assertEquals(before, f.saved())
        assertEquals(reviews, f.reviewBytes())
        assertEquals(files, f.assetBytes())
        assertEquals(2, f.workspace.state.value.confirmedActionCount)
        assertTrue(f.workspace.state.value.canCreate)
    }

    @Test fun stagedPrecisionResetsOnIdentityReplacementAndBusyCannotDispatchChanges() {
        // Controlled late replacement is not a reachable author operation while the real
        // workspace is busy. Keep this bounded check at the same production Content instead.
        fun bitmap(color: Int) = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
            .also { bitmaps += it; it.eraseColor(color) }
        val source = bitmap(Color.BLUE)
        val replacement = bitmap(Color.GREEN)
        val target = bitmap(Color.YELLOW)
        val original = ClickChainActionEditor("action-a", "合成动作", OpaqueMask(.3f, .3f, .5f, .5f),
            source, target, "合成后继", .4f to .4f, false, 1)
        val state = mutableStateOf(ClickChainReviewUiState(runId = "run-a", actionEditor = original))
        val changed = mutableListOf<OpaqueMask>()
        var confirms = 0
        var closes = 0
        val callbacks = ClickChainReviewCallbacks(
            onActionRectChange = { rect ->
                changed += rect
                state.value = state.value.copy(actionEditor = checkNotNull(state.value.actionEditor).copy(rect = rect))
            },
            onConfirmAction = { confirms++ },
            onCloseEditor = { closes++ },
        )
        compose.runOnUiThread {
            compose.activity.setContent { TapSceneTheme { ClickChainReviewContent(state.value, callbacks) } }
        }
        openPrecision()
        compose.onNodeWithText("左边 %").performTextReplacement("17.5")
        compose.runOnIdle {
            state.value = state.value.copy(message = "合成状态刷新", actionEditor = original.copy(label = "同一动作"))
        }
        compose.onNodeWithText("左边 %").assertTextContains("17.5")
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        listOf("左边 %", "顶部 %", "右边 %", "底部 %", "应用范围").forEach {
            compose.onNodeWithText(it).assertIsNotEnabled()
        }
        compose.onNodeWithText("取消").assertIsEnabled()
        assertTrue(changed.isEmpty())
        assertEquals(0, confirms)
        assertEquals(0, closes)

        compose.runOnIdle {
            state.value = state.value.copy(actionEditor = checkNotNull(state.value.actionEditor).copy(bitmap = replacement))
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("范围精调").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busy = false) }
        openPrecision()
        assertPercentage("左边 %", 30f)
        compose.onNodeWithText("左边 %").performTextReplacement("18")

        compose.runOnIdle {
            state.value = state.value.copy(actionEditor = checkNotNull(state.value.actionEditor).copy(
                actionId = "action-b", rect = OpaqueMask(.2f, .2f, .6f, .6f)))
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        openPrecision()
        assertPercentage("左边 %", 20f)
        compose.onNodeWithText("左边 %").performTextReplacement("19")
        compose.runOnIdle { state.value = state.value.copy(runId = "run-b") }
        compose.onNode(isDialog()).assertDoesNotExist()
        openPrecision()
        assertPercentage("左边 %", 20f)
        compose.onNodeWithText("左边 %").performTextReplacement("21")

        // A freshly opened editor for the same action/image must not inherit old raw strings.
        compose.runOnIdle {
            state.value = state.value.copy(actionEditor = ClickChainActionEditor("action-b", "合成动作",
                OpaqueMask(.2f, .2f, .6f, .6f), replacement, target, "合成后继", .4f to .4f, false, 1))
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        openPrecision()
        assertPercentage("左边 %", 20f)
        replacePercentages("12.5", "25", "62.5", "75")
        compose.onNodeWithText("应用范围").assertIsEnabled().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(listOf(OpaqueMask(.125f, .25f, .625f, .75f)), changed)
        assertEquals(0, confirms)
        assertEquals(0, closes)

        compose.runOnIdle { state.value = state.value.copy(editingLocked = true) }
        compose.onNodeWithText("范围精调").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(editingLocked = false,
                actionEditor = checkNotNull(state.value.actionEditor).copy(bitmap = null))
        }
        compose.onNodeWithText("范围精调").assertIsNotEnabled()
        assertEquals(1, changed.size)
        assertEquals(0, confirms)
        assertEquals(0, closes)
    }

    private fun openPrecision() {
        compose.onNodeWithText("范围精调").performScrollTo().assertIsEnabled().performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText("应用范围").assertIsDisplayed()
    }

    private fun replacePercentages(left: String, top: String, right: String, bottom: String) {
        listOf("左边 %" to left, "顶部 %" to top, "右边 %" to right, "底部 %" to bottom).forEach { (label, value) ->
            compose.onNodeWithText(label).performTextReplacement(value)
        }
    }

    private fun assertPercentage(label: String, expected: Float) {
        val value = compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(expected, value.toFloat(), .0001f)
    }

    private fun assertNeedsAction(f: Fixture, actionId: String) {
        val state = f.workspace.state.value
        assertFalse(state.canCreate)
        assertEquals(3, state.frameCount)
        assertEquals(3, state.reviewedFrameCount)
        assertEquals(1, state.confirmedActionCount)
        assertEquals(ClickChainReviewTarget.Action(actionId), state.nextRequiredReview())
        assertEquals(listOf(actionId), state.actions.filterNot { it.confirmed }.map { it.actionId })
        assertTrue(state.frames.all { it.reviewed && it.thumbnailSource is ClickChainThumbnailSource.ReviewedOutput })
    }

    private fun fixture(): Fixture = runBlocking {
        compose.waitForIdle()
        val app: Application = RuntimeEnvironment.getApplication()
        // Own the store before setup can fail, not just after Fixture has been returned.
        val projects = ProjectStore(app).also { stores += it }
        val project = projects.createProject("合成点击范围项目").project.id
        val sourceId = id()
        val raw = File(app.noBackupFilesDir, "sources/$sourceId.mp4").apply {
            parentFile!!.mkdirs(); writeBytes(ByteArray(128) { it.toByte() })
        }
        val source = ImportedSource(sourceId, "sources/$sourceId.mp4", "synthetic.mp4",
            SourceMetadata("video/mp4", raw.length(), sha(raw), 32, 48, 0, 1_000_000))
        WorkspaceStore(app, project).append(source)
        val plan = ClickPlan.create(project, "com.example.synthetic", 64, 96, 0,
            List(2) { ClickAction(x = 20, y = 30) })
        val runs = ClickPlanStore(app)
        var run = ClickRun.create(plan, id(), sourceId, 1, 1)
        runs.beginRun(run)
        repeat(2) { index ->
            listOf(ClickActionStatus.Intent to ClickRunEventType.DispatchIntent,
                ClickActionStatus.Accepted to ClickRunEventType.DispatchAccepted,
                ClickActionStatus.Completed to ClickRunEventType.GestureCompleted).forEach { (status, event) ->
                val completed = status == ClickActionStatus.Completed
                run = run.copy(phase = if (completed && index == 1) ClickRunPhase.Completed else ClickRunPhase.Running,
                    nextActionIndex = if (completed) index + 1 else index,
                    outcomes = run.outcomes.mapIndexed { n, value -> if (n == index) value.copy(status = status) else value },
                    events = run.events + ClickRunEvent(event, plan.actions[index].actionId, run.journalRevision + 1),
                    journalRevision = run.journalRevision + 1)
                runs.saveRun(run)
            }
        }
        val evidence = FrameEvidenceStore(app)
        evidence.createSession(project, run.recordingSessionId, sourceId)
        val geometry = FrameGeometry.fitCenter(64, 96, 32, 48)
        val frameIds = listOf(0L, 1L, 2L, 3L)
        val tickets = plan.actions.flatMapIndexed { index, action -> FrameBoundary.entries.map { boundary ->
            val position = index * 2 + boundary.ordinal
            val frame = frameIds[position]
            FrameTicket(id(), FrameAnchorAction(run.runId, action.actionId, run.recordingSessionId, sourceId, run.generation),
                boundary, 0, frame, frame, frame * 1_000_000, frame * 10_000, geometry,
                if (frame in frameIds.take(position)) FrameFreshness.StaticReuse else FrameFreshness.Fresh)
        } }
        tickets.forEach { ticket ->
            evidence.persistTicket(ticket)
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(50 + ticket.sourceFrameId.toInt() * 20, 100, 150))
            }
            try { evidence.recordPng(ticket, bitmap) } finally { bitmap.recycle() }
            evidence.markEncoder(run.recordingSessionId, ticket.sourceFrameId, ticket.submittedPtsUs, ticket.sourceFrameId)
        }
        val samples = List(frameIds.distinct().size) { it * 5_000L }
        evidence.seal(run.recordingSessionId, samples, source.metadata.sha256)
        evidence.registered(run.recordingSessionId, sourceId, source.metadata.sha256)
        val accessor = FrameSourceAccessor { owner, session, src ->
            if (owner != project || session != run.recordingSessionId || src != sourceId ||
                projects.readProject(project) == null || WorkspaceStore(app, project).read().singleOrNull()?.source != source ||
                !raw.isFile || sha(raw) != source.metadata.sha256) null
            else FrameRegisteredSource(project, session, src, source.metadata.sha256, 32, 48, samples)
        }
        val candidates = tickets.map {
            (evidence.readCandidate(run.recordingSessionId, it.ticketId, accessor) as FrameCandidateResult.Available).candidate
        }
        val capture = ClickChainCapture(run, source, plan.actions.mapIndexed { index, action ->
            ClickChainActionEvidence(index, action, ClickActionStatus.Completed, candidates[index * 2],
                candidates[index * 2 + 1], null, null)
        })
        val reviews = ClickChainReviewStore(app)
        var saved = reviews.open(run.runId, source.metadata.sha256, 2)
        capture.frames.values.forEachIndexed { index, candidate ->
            val (working, folder) = reviews.beginOutput(saved)
            val masks = listOf(OpaqueMask(.05f, .05f, .25f, .25f))
            val png = evidence.withDecodedFrameSuspending(candidate, accessor) {
                SafeMediaWriter(app).writePng(it, masks, folder)
            }
            val output = reviews.output(working, png.file, png.sha256, png.width, png.height)
            saved = reviews.save(working.copy(serial = working.serial + 1, frames = working.frames +
                (candidate.key to ClickChainFrameDraft(candidate.key, candidate.pngSha256,
                    "合成画面 ${index + 1}", masks, output, reviewed = true))))
            saved = reviews.finishOutput(saved, folder)
        }
        val path = ClickChainRoutePolicy.route(capture, 1, 2, emptyMap())
        saved = reviews.save(saved.copy(serial = saved.serial + 1,
            actions = path.actions.mapIndexed { index, action ->
                val from = checkNotNull(path.stages[index].selectedKey)
                val to = checkNotNull(path.stages[index + 1].selectedKey)
                action.action.actionId to ClickChainActionDraft(action.action.actionId, from, to,
                    checkNotNull(saved.frames.getValue(from).output).sha256,
                    checkNotNull(saved.frames.getValue(to).output).sha256, "合成动作 ${index + 1}",
                    OpaqueMask(.3f, .3f, .5f, .5f), confirmed = true)
            }.toMap()))
        Fixture(app, projects, run, source, reviews, accessor)
    }

    private inner class Fixture(val app: Application, val projects: ProjectStore, val run: ClickRun,
        val source: ImportedSource, val reviews: ClickChainReviewStore, val accessor: FrameSourceAccessor) {
        lateinit var workspace: ClickChainWorkspace
        fun mount() {
            compose.runOnUiThread {
                val owner = object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                }
                workspace = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[
                    "click-chain-review", ClickChainWorkspace::class.java]
                // Retain old owners and helpers across reopen, even after workspace is replaced.
                workspaces += owner to workspace
                stores += field(workspace, "projects") as ProjectStore
                // Replace only host-unavailable MP4 inspection. Production activate reads the run,
                // ownership, evidence, saved inputs and PNGs, and all assertions use public state.
                field(workspace, "sourceAccessor", accessor)
                val thumbnails: (() -> Unit) -> FrameSourceAccessor = { checkActive ->
                    FrameSourceAccessor { owner, session, src ->
                        checkActive(); accessor.registeredSource(owner, session, src)
                    }
                }
                field(workspace, "thumbnailSourceAccessor", thumbnails)
                compose.activity.setContent {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        TapSceneTheme { ClickChainReviewRoute(run.runId, {}, {}) }
                    }
                }
            }
            compose.waitUntil(30_000) {
                compose.waitForIdle()
                workspace.state.value.runId == run.runId && !workspace.state.value.loading &&
                    !workspace.state.value.busy && workspace.state.value.frames.isNotEmpty()
            }
        }
        fun reopen() {
            finishWorkspaces()
            mount()
        }
        fun settled() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !workspace.state.value.busy && !workspace.state.value.loading
        }
        fun openFirstAction() {
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
            compose.onAllNodesWithText("查看点击区域").onFirst().assertIsEnabled().performClick()
            settled()
            assertEquals(run.plan.actions.first().actionId, workspace.state.value.actionEditor?.actionId)
        }
        fun awaitSaved(predicate: (ClickChainReviewDraft) -> Boolean) = compose.waitUntil(30_000) {
            compose.waitForIdle()
            predicate(saved())
        }
        fun saved() = checkNotNull(reviews.readExisting(run.runId))
        fun reviewBytes() = File(app.noBackupFilesDir, "click-chain-reviews/${run.runId}")
            .walkTopDown().filter(File::isFile).associate { it.path to sha(it) }
        fun assetBytes(): Map<String, String> = outputBytes(saved()) + evidenceBytes() +
            (source.privateRelativePath to sha(File(app.noBackupFilesDir, source.privateRelativePath)))
        fun outputBytes(draft: ClickChainReviewDraft) = draft.frames.mapValues { (_, frame) ->
            sha(reviews.outputFile(run.runId, checkNotNull(frame.output)))
        }
        fun evidenceBytes() = File(app.noBackupFilesDir, "frame-evidence/${run.recordingSessionId}")
            .walkTopDown().filter(File::isFile).associate { it.path to sha(it) }
    }

    private fun field(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun closeStore(store: ProjectStore) {
        val helper = field(store, "helper") as SQLiteOpenHelper
        val database = SQLiteOpenHelper::class.java.getDeclaredField("mDatabase")
            .apply { isAccessible = true }.get(helper) as SQLiteDatabase?
        helper.close()
        assertTrue("Test-owned project database is still open", database == null || !database.isOpen)
    }
    private fun resetFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
    private fun id() = UUID.randomUUID().toString()
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
