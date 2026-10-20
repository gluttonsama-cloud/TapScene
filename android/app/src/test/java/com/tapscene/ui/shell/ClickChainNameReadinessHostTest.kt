package com.tapscene.ui.shell

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
class ClickChainNameReadinessHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetFactory()
            HostSqlite.configure()
            HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
        }
        override fun after() { HostFileSyncShadow.reset(); resetFactory() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun chainNameRepairSurvivesReturnAndReopenWithoutLosingSafeOutputOrActions() {
        val f = fixture()
        f.mount()
        // The blank previous-after alternative is not the selected representative.
        assertTrue(f.workspace.state.value.canCreate)
        assertNull(f.workspace.state.value.nextRequiredReview())
        exerciseRepair(f, f.keys[2], "", ordinal = 2, expectedFrames = 3)
    }

    @Test fun framesOnlyWhitespaceRepairIgnoresDeselectedNamesAndKeepsReviewedPixels() {
        val f = fixture(framesOnly = true)
        f.mount()
        assertTrue(f.workspace.state.value.canCreate)
        assertFalse(f.workspace.state.value.frames.single { it.key == f.keys[1] }.selected)
        exerciseRepair(f, f.keys[0], "   ", ordinal = 1, expectedFrames = 2)
    }

    @Test fun sharedFrameNameRepairUpdatesEveryVisitWithoutDuplicatingReviewOrResettingActions() {
        val f = fixture(shared = true)
        f.mount()
        assertTrue(f.workspace.state.value.canCreate)
        assertEquals(2, f.workspace.state.value.stages.count { it.selectedKey == f.keys[1] })
        exerciseRepair(f, f.keys[1], " \t ", ordinal = 2, expectedFrames = 2)
        assertEquals(2, f.workspace.state.value.stages.count {
            it.selectedKey == f.keys[1] && it.title == "恢复后的画面名称" && it.reviewed
        })
    }

    @Test fun directFrameConfirmationRejectsBlankNameWithoutMarkingOutputReviewed() {
        val f = fixture(unreviewedFirst = true)
        f.mount()
        val key = f.keys.first()
        compose.runOnIdle { f.workspace.reviewFrame(key) }
        f.settled()
        val before = f.saved().frames.getValue(key)
        compose.onNodeWithText("画面标题").performTextReplacement("")
        compose.runOnIdle { f.workspace.confirmFrame(checkNotNull(before.output).sha256) }
        f.settled()
        assertEquals(key, f.workspace.state.value.frameEditor?.key)
        assertFalse(checkNotNull(f.workspace.state.value.frameEditor).reviewed)
        compose.onNodeWithText("确认这张画面").assertIsNotEnabled()
        compose.onNodeWithText("返回整理").performClick()
        f.settled()
        val after = f.saved().frames.getValue(key)
        assertEquals(before.copy(title = ""), after)
        assertFalse(f.workspace.state.value.canCreate)
    }

    private fun exerciseRepair(f: Fixture, key: String, blank: String, ordinal: Int, expectedFrames: Int) {
        val original = f.saved()
        val bytes = f.outputBytes(original)
        val sourceFiles = f.evidenceBytes()
        val project = f.projects.readProject(f.run.projectId)
        compose.runOnIdle { f.workspace.reviewFrame(key) }
        f.settled()
        assertEquals(key, f.workspace.state.value.frameEditor?.key)
        assertNotNull(f.workspace.state.value.frameEditor?.output)
        compose.onNodeWithText("画面标题").performTextReplacement(blank)
        // Return is the real production flush/close callback, not a test-side draft rewrite.
        compose.onNodeWithText("返回整理").assertIsEnabled().performClick()
        f.settled()
        assertBlocked(f, key, expectedFrames)
        assertPreserved(f, original, bytes, sourceFiles, key, blank)
        compose.onNodeWithText("下一项：画面 $ordinal · 补全名称").assertIsDisplayed()
        compose.onNodeWithText("补全画面名称").assertIsEnabled()
        compose.onNodeWithText("生成独立项目").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(if (f.framesOnly) 2 else 4)
        compose.onAllNodesWithText("未填写名称").onFirst().assertIsDisplayed()
        compose.onNodeWithText("待补画面").assertDoesNotExist()
        compose.onNodeWithText("缺少画面证据").assertDoesNotExist()

        val owner = f.saved().owner
        f.reopen()
        assertNotEquals(owner, f.saved().owner)
        assertBlocked(f, key, expectedFrames)
        assertPreserved(f, original, bytes, sourceFiles, key, blank)
        // The named footer CTA targets the selected frame, including a deduplicated revisit.
        compose.onNodeWithText("补全画面名称").assertIsEnabled().performClick()
        f.settled()
        assertEquals(key, f.workspace.state.value.frameEditor?.key)
        assertTrue(checkNotNull(f.workspace.state.value.frameEditor).reviewed)
        compose.onNodeWithText("画面标题").performTextReplacement("恢复后的画面名称")
        compose.onNodeWithText("返回整理").performClick()
        f.settled()
        val state = f.workspace.state.value
        assertTrue(state.canCreate)
        assertNull(state.issue)
        assertNull(state.nextRequiredReview())
        assertEquals(expectedFrames, state.reviewedFrameCount)
        assertEquals(if (f.framesOnly) 0 else 2, state.confirmedActionCount)
        assertPreserved(f, original, bytes, sourceFiles, key, "恢复后的画面名称")
        compose.onNodeWithText("生成独立项目").assertIsEnabled()
        assertEquals(project, f.projects.readProject(f.run.projectId))
        assertEquals(1, f.projects.listProjects().size)
    }

    private fun assertBlocked(f: Fixture, key: String, expectedFrames: Int) {
        val state = f.workspace.state.value
        assertFalse(state.canCreate)
        assertEquals("请补全所选画面的名称。已有画面复核与遮挡会保留。", state.issue)
        assertEquals(ClickChainReviewTarget.Frame(key), state.nextRequiredReview())
        assertEquals(expectedFrames, state.frameCount)
        assertEquals(expectedFrames, state.reviewedFrameCount)
        assertEquals(if (f.framesOnly) 0 else 2, state.confirmedActionCount)
        assertTrue(state.actions.all { it.confirmed })
    }

    private fun assertPreserved(f: Fixture, original: ClickChainReviewDraft, bytes: Map<String, String>,
        sourceFiles: Map<String, String>, key: String, title: String) {
        val saved = f.saved()
        assertEquals(original.frames + (key to original.frames.getValue(key).copy(title = title)), saved.frames)
        assertEquals(original.actions, saved.actions)
        assertEquals(bytes, f.outputBytes(saved))
        assertEquals(sourceFiles, f.evidenceBytes())
        assertEquals(original.outputJobs, saved.outputJobs)
        val output = checkNotNull(saved.frames.getValue(key).output)
        val row = f.workspace.state.value.frames.single { it.key == key }
        assertTrue(row.reviewed)
        assertEquals(ClickChainThumbnailSource.ReviewedOutput(output.sha256), row.thumbnailSource)
        val request = ClickChainThumbnailRequest(key, checkNotNull(row.thumbnailSource))
        val images = runBlocking { f.workspace.loadThumbnails(listOf(request)) }
        try {
            assertEquals(setOf(request), images.keys)
            assertEquals(Color.BLACK, images.getValue(request).getPixel(3, 4))
            assertNotEquals(Color.BLACK, images.getValue(request).getPixel(25, 40))
        } finally { images.values.forEach(Bitmap::recycle) }
    }

    private fun fixture(framesOnly: Boolean = false, shared: Boolean = false,
        unreviewedFirst: Boolean = false): Fixture = runBlocking {
        compose.waitForIdle()
        val app: Application = RuntimeEnvironment.getApplication()
        val projects = ProjectStore(app)
        val project = projects.createProject("合成名称修正项目").project.id
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
        val frameIds = if (shared) listOf(0L, 1L, 1L, 1L) else listOf(0L, 1L, 2L, 3L)
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
                    if (!shared && index == 1) " " else "合成画面 ${index + 1}", masks, output,
                    reviewed = !unreviewedFirst || index != 0))))
            saved = reviews.finishOutput(saved, folder)
        }
        val path = ClickChainRoutePolicy.route(capture, 1, 2, emptyMap())
        saved = reviews.save(saved.copy(serial = saved.serial + 1, framesOnly = framesOnly,
            selectedFrameKeys = if (framesOnly) setOf(candidates.first().key, candidates.last().key) else null,
            actions = path.actions.mapIndexed { index, action ->
                val from = checkNotNull(path.stages[index].selectedKey)
                val to = checkNotNull(path.stages[index + 1].selectedKey)
                action.action.actionId to ClickChainActionDraft(action.action.actionId, from, to,
                    checkNotNull(saved.frames.getValue(from).output).sha256,
                    checkNotNull(saved.frames.getValue(to).output).sha256, "合成动作 ${index + 1}",
                    OpaqueMask(.3f, .3f, .5f, .5f), confirmed = true)
            }.toMap()))
        Fixture(app, projects, run, source, reviews, accessor, candidates.map { it.key }, framesOnly)
    }

    private inner class Fixture(val app: Application, val projects: ProjectStore, val run: ClickRun,
        val source: ImportedSource, val reviews: ClickChainReviewStore, val accessor: FrameSourceAccessor,
        val keys: List<String>, val framesOnly: Boolean) {
        lateinit var workspace: ClickChainWorkspace
        fun mount() {
            compose.runOnUiThread {
                workspace = ViewModelProvider(compose.activity)["click-chain-review", ClickChainWorkspace::class.java]
                // Replace only host-unavailable MP4 inspection. Production activate reads the run,
                // ownership, evidence, saved inputs and PNGs, and all assertions use public state.
                field(workspace, "sourceAccessor", accessor)
                val thumbnails: (() -> Unit) -> FrameSourceAccessor = { checkActive ->
                    FrameSourceAccessor { owner, session, src ->
                        checkActive(); accessor.registeredSource(owner, session, src)
                    }
                }
                field(workspace, "thumbnailSourceAccessor", thumbnails)
                compose.activity.setContent { TapSceneTheme { ClickChainReviewRoute(run.runId, {}, {}) } }
            }
            compose.waitUntil(30_000) {
                compose.waitForIdle()
                workspace.state.value.runId == run.runId && !workspace.state.value.loading &&
                    !workspace.state.value.busy && workspace.state.value.frames.isNotEmpty()
            }
        }
        fun reopen() {
            compose.runOnUiThread { compose.activity.setContent { } }
            compose.waitForIdle()
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            mount()
        }
        fun settled() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !workspace.state.value.busy && !workspace.state.value.loading
        }
        fun saved() = checkNotNull(reviews.readExisting(run.runId))
        fun outputBytes(draft: ClickChainReviewDraft) = draft.frames.mapValues { (_, frame) ->
            sha(reviews.outputFile(run.runId, checkNotNull(frame.output)))
        }
        fun evidenceBytes() = File(app.noBackupFilesDir, "frame-evidence/${run.recordingSessionId}")
            .walkTopDown().filter(File::isFile).associate { it.path to sha(it) }
    }

    private fun field(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun resetFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
    private fun id() = UUID.randomUUID().toString()
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
