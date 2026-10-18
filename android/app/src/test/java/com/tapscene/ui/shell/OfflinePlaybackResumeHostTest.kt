package com.tapscene.ui.shell

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.MainActivity
import com.tapscene.data.AiDraftImportFixtures
import com.tapscene.data.AiDraftImportStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReleaseStore
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.ReleasePlayback
import com.tapscene.ui.ReleaseUiState
import com.tapscene.ui.ReleaseWorkspace
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
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

/** Production Workspace/Compose/Store and real PNGs. MP4 decoding and device lifecycle NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OfflinePlaybackResumeHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            val root = RuntimeEnvironment.getApplication().noBackupFilesDir.canonicalFile
            val sandbox = RuntimeEnvironment.getTempDirectory().basePath.toRealPath()
            check(root.toPath().startsWith(sandbox) && root.toPath() != sandbox)
            root.listFiles().orEmpty().forEach { child ->
                check(child.canonicalFile.toPath().startsWith(root.toPath()))
                check(child.deleteRecursively())
            }
            HostFileSyncShadow.begin(root)
        }
        override fun after() {
            try { HostFileSyncShadow.reset() } finally { resetApplicationViewModelFactory() }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val owners = mutableListOf<Pair<ViewModelStore, ReleaseWorkspace>>()

    @After fun closeWorkspaces() {
        val jobs = owners.flatMap { (_, workspace) -> listOfNotNull(job(workspace, "task"), job(workspace, "playerTask")) }
        compose.runOnIdle { owners.forEach { it.first.clear() } }
        awaitJobs(jobs)
    }

    @Test fun newViewModelContinuesExactBranchHistoryAndEndingWhileStartAndCandidateStayIndependent() {
        val fixture = fixture("history")
        var workspace = newWorkspace()
        open(workspace, fixture)
        assertVisit(workspace, fixture, listOf(0))
        assertNull(workspace.state.value.playbackResume)
        advance(workspace, fixture, "branch")
        advance(workspace, fixture, "back-route")
        advance(workspace, fixture, "forward")
        advance(workspace, fixture, "finish")
        assertVisit(workspace, fixture, listOf(0, 2, 1, 2), ended = true)
        val expected = checkNotNull(workspace.state.value.player)
        closeAndClear(workspace)

        workspace = newWorkspace()
        open(workspace, fixture)
        assertChoice(workspace, fixture, 2)
        compose.onNodeWithText("上次已观看至结束").assertIsDisplayed()
        clickChoice("继续观看", workspace)
        assertVisit(workspace, fixture, listOf(0, 2, 1, 2), ended = true)
        val continued = checkNotNull(workspace.state.value.player)
        assertEquals(expected.endLabel, continued.endLabel)
        assertEquals(expected.endEdgeId, continued.endEdgeId)
        assertEquals(expected.visitedEdgeIds, continued.visitedEdgeIds)
        assertTrue(continued.completedFromStart)
        assertNull(continued.video)
        assertEquals(0L, checkNotNull(continued.traversalState).mediaRunId)
        compose.onNodeWithText("上一步").performClick()
        awaitIdle(workspace)
        assertVisit(workspace, fixture, listOf(0, 2, 1, 2)) // Undo the explicit end, not a visit.
        compose.onNodeWithText("上一步").performClick()
        awaitIdle(workspace)
        assertVisit(workspace, fixture, listOf(0, 2, 1))
        closeAndClear(workspace)

        workspace = newWorkspace()
        open(workspace, fixture)
        assertChoice(workspace, fixture, 1)
        clickChoice("继续观看", workspace)
        assertVisit(workspace, fixture, listOf(0, 2, 1))
        closeAndClear(workspace)
        workspace = newWorkspace()
        open(workspace, fixture)
        clickChoice("从头开始", workspace)
        assertVisit(workspace, fixture, listOf(0))
        assertTrue(checkNotNull(workspace.state.value.player).visitedEdgeIds.isEmpty())
        assertFalse(checkNotNull(workspace.state.value.player).completedFromStart)
        closeAndClear(workspace)

        // Real candidate preview keeps its separate author-review traversal and has no viewer lease.
        val candidate = runBlocking(Dispatchers.IO) {
            val app = RuntimeEnvironment.getApplication()
            val input = AiDraftImportFixtures.complete(app)
            val imports = AiDraftImportStore(app)
            val prepared = input.zip.inputStream().use { imports.prepare(it) }
            val projectId = checkNotNull(imports.commit(prepared.sessionId, prepared.previewDigest).projectId)
            AiDraftImportFixtures.reviewDraftRegions(ProjectStore(app), projectId)
            val revision = ProjectStore.withTemporary(app) { checkNotNull(it.readProject(projectId)).project.revision }
            ReleaseStore(app).createCandidate(projectId, revision)
        }
        workspace = newWorkspace()
        compose.runOnIdle { workspace.openReviewById(candidate.id) }
        awaitIdle(workspace)
        compose.runOnIdle { workspace.startCandidatePreview() }
        awaitIdle(workspace)
        assertEquals(candidate.id, workspace.state.value.player?.candidateId)
        assertNull(workspace.state.value.player?.playbackSession)
        assertNull(workspace.state.value.playbackResume)
        val edge = candidate.scene.edges.single { it.trigger == "continue" }
        compose.runOnIdle { workspace.chooseEdge(edge.id) }
        awaitIdle(workspace)
        assertTrue(checkNotNull(workspace.state.value.candidate).visitedEdgeIds.contains(edge.id))
        closeAndClear(workspace)
        workspace = newWorkspace()
        open(workspace, fixture)
        // A clean starting checkpoint may be optimized away; neither form inherits candidate history.
        if (workspace.state.value.playbackResume != null) clickChoice("继续观看", workspace)
        assertVisit(workspace, fixture, listOf(0))
        assertTrue(checkNotNull(workspace.state.value.player).visitedEdgeIds.isEmpty())
        println("HOST_PLAYBACK_RESUME recreated-ViewModel/branch/revisit/end/previous/from-start/candidate-isolation PASS; device NOT_RUN")
    }

    @Test fun switchingSameTitleVersionsRejectsOldChoiceAndCorruptRecordOnlyOffersFreshStart() {
        val a = fixture("version-a")
        val b = fixture("version-b")
        val workspace = newWorkspace()
        open(workspace, a)
        advance(workspace, a, "branch")
        compose.runOnIdle { workspace.closePlayer() }
        open(workspace, b)
        advance(workspace, b, "main")
        compose.runOnIdle { workspace.closePlayer() }
        open(workspace, a)
        assertChoice(workspace, a, 2)
        val oldContinue = choiceAction("继续观看")
        val oldStart = choiceAction("从头开始")
        val oldToken = checkNotNull(workspace.state.value.playbackResume).session.ownerToken
        open(workspace, b)
        assertChoice(workspace, b, 1)
        val bChoice = checkNotNull(workspace.state.value.playbackResume)
        assertNotEquals(oldToken, bChoice.session.ownerToken)
        compose.onNodeWithText("查看完整版本标识").performScrollTo().performClick()
        compose.onNodeWithText("版本 ${bChoice.session.releaseId}\n摘要 ${bChoice.session.contentDigest}")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { oldContinue(); oldStart() }
        assertSame(bChoice, workspace.state.value.playbackResume)
        assertNull(workspace.state.value.player)
        clickChoice("继续观看", workspace)
        assertVisit(workspace, b, listOf(0, 1))
        val bPlayer = workspace.state.value.player
        compose.runOnIdle { oldContinue(); oldStart() }
        assertSame(bPlayer, workspace.state.value.player)
        compose.runOnIdle { workspace.closePlayer() }
        open(workspace, a)
        assertChoice(workspace, a, 2)
        val abandoned = choiceAction("继续观看")
        compose.runOnIdle { workspace.closePlayer(); abandoned() }
        assertNull(workspace.state.value.player)
        assertNull(workspace.state.value.playbackResume)

        val before = runBlocking(Dispatchers.IO) { ReleaseStore(RuntimeEnvironment.getApplication()).listReleases() }
        val packageHash = ViewerPackageCodec.sha256(a.file)
        corruptCheckpoint(a)
        open(workspace, a)
        val corrupt = checkNotNull(workspace.state.value.playbackResume)
        assertTrue(corrupt.checkpointUnavailable)
        assertNull(corrupt.checkpoint)
        compose.onNodeWithText("观看记录不可用，演示包未受影响。可从头开始。").assertIsDisplayed()
        compose.onNodeWithText("继续观看").assertDoesNotExist()
        compose.runOnIdle { workspace.resumePlayback(corrupt.session.ownerToken, false) }
        assertSame(corrupt, workspace.state.value.playbackResume)
        clickChoice("从头开始", workspace)
        assertVisit(workspace, a, listOf(0))
        assertEquals(before, runBlocking(Dispatchers.IO) { ReleaseStore(RuntimeEnvironment.getApplication()).listReleases() })
        assertEquals(packageHash, ViewerPackageCodec.sha256(a.file))
        compose.runOnIdle { workspace.closePlayer() }
        open(workspace, b)
        clickChoice("继续观看", workspace)
        assertVisit(workspace, b, listOf(0, 1))

        compose.runOnIdle { workspace.closePlayer() }
        open(workspace, a)
        val replacedChoice = checkNotNull(workspace.state.value.playbackResume)
        val replacedContinue = choiceAction("继续观看")
        val replacement = runBlocking(Dispatchers.IO) {
            val scene = a.scene
            val changed = ViewerScene(scene.releaseId, "重新导入的同标识版本", scene.goal, scene.createdAt,
                scene.startStateId, scene.states, scene.edges, scene.hotspots, scene.assets)
            val file = File(a.file.parentFile, "replacement-${scene.releaseId}.tapscene")
            ViewerPackageCodec.writePackage(changed, File(a.file.parentFile, a.file.nameWithoutExtension), file, {}, null)
            val store = ReleaseStore(RuntimeEnvironment.getApplication())
            store.deleteRelease(scene.releaseId)
            file.inputStream().use { store.importPackage(it) }
            a.copy(scene = changed, file = file)
        }
        assertNotEquals(replacedChoice.session.contentDigest, ViewerPackageCodec.contentDigest(replacement.scene))
        compose.runOnIdle { replacedContinue() }
        awaitIdle(workspace)
        assertNull(workspace.state.value.player)
        assertNull(workspace.state.value.playerBitmap)
        compose.onNodeWithText("此观看会话已失效，请返回演示库重新打开。").assertIsDisplayed()
        compose.runOnIdle { workspace.closePlayer() }
        open(workspace, replacement)
        assertVisit(workspace, replacement, listOf(0))
        println("HOST_PLAYBACK_RESUME exact-version/digest/stale-choice/closed-choice/corrupt-record/replaced-release PASS; device NOT_RUN")
    }

    @Test fun closeAfterVisibleEndStillSavesAndInterruptedTransitionRestoresOnlyItsSource() {
        val fixture = fixture("close-window")
        var workspace = newWorkspace()
        open(workspace, fixture)
        advance(workspace, fixture, "branch")
        val visibleBitmap = workspace.state.value.playerBitmap
        val lock = storeMutex()
        val saving: Job
        runBlocking { lock.lock() }
        try {
            compose.runOnIdle { workspace.chooseEdge(fixture.edge("finish")) }
            compose.waitUntil(30_000) { workspace.state.value.player?.ended == true }
            saving = checkNotNull(job(workspace, "playerTask"))
            assertTrue(saving.isActive)
            assertTrue(workspace.state.value.busy)
            assertSame(visibleBitmap, workspace.state.value.playerBitmap)
            assertVisit(workspace, fixture, listOf(0, 2), ended = true)
            compose.runOnIdle { workspace.closePlayer() }
            assertTrue(saving.isCancelled)
            assertFalse(saving.isCompleted) // The already-visible visit still owns its bounded disk write.
            assertNull(workspace.state.value.player)
            assertNull(workspace.state.value.playerBitmap)
            // Reopening immediately must wait for this published visit's write, even though
            // Close has cancelled its UI job and cleared the visible player already.
            compose.runOnIdle { workspace.openRelease(fixture.scene.releaseId) }
            assertTrue(checkNotNull(job(workspace, "task")).isActive)
            assertTrue(workspace.state.value.busy)
            assertNull(workspace.state.value.player)
            assertNull(workspace.state.value.playbackResume)
        } finally { lock.unlock() }
        awaitJobs(listOf(saving))
        awaitIdle(workspace)
        assertNull(workspace.state.value.player)
        assertNull(workspace.state.value.playerBitmap)
        assertChoice(workspace, fixture, 2)
        assertTrue(checkNotNull(workspace.state.value.playbackResume?.checkpoint).ended)
        closeAndClear(workspace)
        workspace = newWorkspace()
        open(workspace, fixture)
        clickChoice("继续观看", workspace)
        assertVisit(workspace, fixture, listOf(0, 2), ended = true)
        compose.onNodeWithText("上一步").performClick()
        awaitIdle(workspace)
        assertVisit(workspace, fixture, listOf(0, 2))

        // Only transition metadata is injected. The installed scene/assets remain the real static
        // package; no MP4 bytes, video decoder success or media completion is manufactured.
        compose.runOnIdle {
            val state = mutableState(workspace)
            state.value = state.value.copy(player = checkNotNull(state.value.player).copy(
                scene = withTransitionMetadata(fixture.scene, fixture.edge("back-route"))))
        }
        val interrupted: Job
        var oldRun = 0L
        runBlocking { lock.lock() }
        try {
            compose.runOnIdle { workspace.chooseEdge(fixture.edge("back-route")) }
            interrupted = checkNotNull(job(workspace, "playerTask"))
            val pending = checkNotNull(workspace.state.value.player)
            oldRun = checkNotNull(pending.traversalState).mediaRunId
            assertTrue(oldRun > 0L)
            assertEquals(fixture.edge("back-route"), pending.traversalState?.pendingEdgeId)
            assertNull(pending.video?.file)
            assertVisit(workspace, fixture, listOf(0, 2))
            compose.runOnIdle { workspace.closePlayer() }
        } finally { lock.unlock() }
        awaitJobs(listOf(interrupted))
        closeAndClear(workspace)
        workspace = newWorkspace()
        open(workspace, fixture)
        assertChoice(workspace, fixture, 2)
        clickChoice("继续观看", workspace)
        assertVisit(workspace, fixture, listOf(0, 2))
        val restored = checkNotNull(workspace.state.value.player)
        assertNull(restored.video)
        assertNull(restored.traversalState?.pendingEdgeId)
        assertEquals(0L, checkNotNull(restored.traversalState).mediaRunId)
        assertFalse(fixture.edge("back-route") in restored.visitedEdgeIds)
        compose.runOnIdle {
            workspace.completePlayerTransition(oldRun)
            workspace.failPlayerTransition(oldRun)
            workspace.retryPlayerTransition(oldRun)
            workspace.skipPlayerTransition()
        }
        assertSame(restored, workspace.state.value.player)
        assertEquals(ViewerPackageCodec.contentDigest(fixture.scene), runBlocking(Dispatchers.IO) {
            ViewerPackageCodec.contentDigest(ReleaseStore(RuntimeEnvironment.getApplication()).loadRelease(fixture.scene.releaseId))
        })
        // Force only this fixture's atomic progress replacement to fail. The validated package
        // and current PNG stay intact; restore the original progress bytes after the assertion.
        val record = File(RuntimeEnvironment.getApplication().noBackupFilesDir,
            "release-store/releases/${fixture.scene.releaseId}/playback.json")
        val savedRecord = record.readBytes()
        check(record.delete() && record.mkdir())
        val occupied = File(record, "host-test-owned").also { it.writeText("force nonempty destination") }
        try {
            val failedSave: Job
            val displayedEnd: ReleasePlayback
            val displayedBitmap = workspace.state.value.playerBitmap
            runBlocking { lock.lock() }
            try {
                compose.runOnIdle { workspace.chooseEdge(fixture.edge("finish")) }
                compose.waitUntil(30_000) { workspace.state.value.player?.ended == true }
                failedSave = checkNotNull(job(workspace, "playerTask"))
                displayedEnd = checkNotNull(workspace.state.value.player)
                assertTrue(failedSave.isActive)
                compose.runOnIdle { workspace.cancel() }
                assertTrue(failedSave.isCancelled)
                assertFalse(failedSave.isCompleted)
                assertFalse(workspace.state.value.busy)
                assertSame(displayedEnd, workspace.state.value.player)
                assertSame(displayedBitmap, workspace.state.value.playerBitmap)
            } finally { lock.unlock() }
            awaitJobs(listOf(failedSave))
            val failure = "观看记录未能保存，当前画面保留；退出后可能需要从头开始。"
            assertEquals(failure, workspace.state.value.message)
            assertFalse(workspace.state.value.busy)
            assertNull(workspace.state.value.stage)
            assertSame(displayedEnd, workspace.state.value.player)
            assertSame(displayedBitmap, workspace.state.value.playerBitmap)
            assertVisit(workspace, fixture, listOf(0, 2), ended = true)
            compose.onNodeWithText(failure).assertIsDisplayed()
        } finally {
            check(occupied.delete() && record.delete())
            record.writeBytes(savedRecord)
        }
        println("HOST_PLAYBACK_RESUME visible-end/close-and-reopen/checkpoint-failure-after-cancel/interrupted-source/stale-media-callback PASS; MP4 decoding/device NOT_RUN")
    }

    private fun newWorkspace(): ReleaseWorkspace {
        lateinit var workspace: ReleaseWorkspace
        compose.runOnIdle {
            val owner = ViewModelStore()
            workspace = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(RuntimeEnvironment.getApplication()))[ReleaseWorkspace::class.java]
            owners += owner to workspace
            compose.activity.setContent {
                TapSceneTheme {
                    ReleasePlayerContent(workspace.state.collectAsState().value, onBack = workspace::closePlayer,
                        onTap = workspace::tapPlayer, onEdge = workspace::chooseEdge, onPrevious = workspace::previous,
                        onRestart = workspace::restart, onDismissMatches = workspace::dismissMatches,
                        onResume = workspace::resumePlayback)
                }
            }
        }
        awaitIdle(workspace)
        return workspace
    }

    private fun closeAndClear(workspace: ReleaseWorkspace) {
        val pending = listOfNotNull(job(workspace, "task"), job(workspace, "playerTask"))
        compose.runOnIdle { workspace.closePlayer(); owners.single { it.second === workspace }.first.clear() }
        awaitJobs(pending)
        assertNull(workspace.state.value.player)
        assertNull(workspace.state.value.playbackResume)
    }
    private fun open(workspace: ReleaseWorkspace, fixture: Fixture) {
        compose.runOnIdle { workspace.openRelease(fixture.scene.releaseId) }
        awaitIdle(workspace)
    }
    private fun advance(workspace: ReleaseWorkspace, fixture: Fixture, edge: String) {
        compose.runOnIdle { workspace.chooseEdge(fixture.edge(edge)) }
        awaitIdle(workspace)
    }
    private fun clickChoice(text: String, workspace: ReleaseWorkspace) {
        compose.onNodeWithText(text).performScrollTo().assertIsEnabled().performClick()
        awaitIdle(workspace)
        assertNull(workspace.state.value.playbackResume)
    }
    private fun choiceAction(text: String): () -> Boolean = checkNotNull(compose.onNodeWithText(text)
        .performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun awaitJobs(jobs: List<Job>) {
        compose.waitUntil(30_000) {
            // PAUSED Robolectric Main must execute IO-return/cancellation continuations too.
            compose.waitForIdle()
            jobs.all { it.isCompleted }
        }
    }
    private fun awaitIdle(workspace: ReleaseWorkspace) {
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            !workspace.state.value.busy && listOfNotNull(job(workspace, "task"), job(workspace, "playerTask")).none { it.isActive }
        }
    }
    private fun assertChoice(workspace: ReleaseWorkspace, fixture: Fixture, index: Int) {
        val choice = checkNotNull(workspace.state.value.playbackResume)
        assertNull(workspace.state.value.player)
        assertNull(workspace.state.value.playerBitmap)
        assertEquals(fixture.scene.releaseId, choice.session.releaseId)
        assertEquals(ViewerPackageCodec.contentDigest(fixture.scene), choice.session.contentDigest)
        assertEquals(fixture.scene.states[index].id, checkNotNull(choice.checkpoint).currentStateId)
        compose.onNodeWithText("固定版本 ${choice.session.releaseId.take(8)} · 摘要 ${choice.session.contentDigest.take(12)}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("步骤 ${index + 1} · ${fixture.scene.states[index].title}").performScrollTo().assertIsDisplayed()
    }
    private fun assertVisit(workspace: ReleaseWorkspace, fixture: Fixture, history: List<Int>, ended: Boolean = false) {
        val state = workspace.state.value
        val player = checkNotNull(state.player)
        assertEquals(fixture.scene.releaseId, player.scene.releaseId)
        assertEquals(history.map { fixture.scene.states[it].id }, player.history)
        assertEquals(fixture.scene.states[history.last()].id, player.currentStateId)
        assertEquals(ended, player.ended)
        assertNull(player.candidateId)
        val session = checkNotNull(player.playbackSession)
        assertEquals(fixture.scene.releaseId, session.releaseId)
        assertEquals(ViewerPackageCodec.contentDigest(fixture.scene), session.contentDigest)
        val bitmap = checkNotNull(state.playerBitmap)
        assertEquals(32, bitmap.width)
        assertEquals(48, bitmap.height)
        assertEquals(fixture.colors[history.last()], bitmap.getPixel(16, 24))
    }

    private data class Fixture(val scene: ViewerScene, val file: File, val colors: List<Int>, val edgeIds: Map<String, String>) {
        fun edge(name: String) = edgeIds.getValue(name)
    }
    private fun fixture(name: String): Fixture = runBlocking(Dispatchers.IO) {
        val app: Application = RuntimeEnvironment.getApplication()
        fun id(value: String) = UUID.nameUUIDFromBytes("resume/$name/$value".toByteArray()).toString()
        val root = File(app.noBackupFilesDir, "resume-$name").also { check(it.mkdir()) }
        val assets = File(root, "assets").also { check(it.mkdir()) }
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
        val images = colors.mapIndexed { index, color ->
            val imageId = id("image-$index")
            val image = File(assets, "$imageId.png")
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            ViewerScene.Asset(imageId, "assets/$imageId.png", "image/png", image.length(), ViewerPackageCodec.sha256(image), 32, 48)
        }
        val states = images.mapIndexed { index, image ->
            ViewerScene.State(id("state-$index"), image.id, 32, 48, "$name 画面 ${index + 1}", "合成安全画面", "authored", false)
        }
        val branch = ViewerScene.Hotspot(id("branch-spot"), states[0].id, "分支", ViewerScene.Rect(.1, .1, .2, .2))
        val finish = ViewerScene.Hotspot(id("finish-spot"), states[2].id, "结束", ViewerScene.Rect(.1, .1, .2, .2))
        val edges = listOf(
            ViewerScene.Edge(id("main"), states[0].id, states[1].id, null, null, "主路", "continue", "authored"),
            ViewerScene.Edge(id("branch"), states[0].id, states[2].id, null, branch.id, "选择蓝色分支", "tap", "authored"),
            ViewerScene.Edge(id("forward"), states[1].id, states[2].id, null, null, "前往蓝色画面", "continue", "authored"),
            ViewerScene.Edge(id("back-route"), states[2].id, states[1].id, null, null, "回访绿色画面", "continue", "authored"),
            ViewerScene.Edge(id("finish"), states[2].id, null, "已选择蓝色结果", finish.id, "结束演示", "tap", "authored"))
        val scene = ViewerScene(id("release"), "同名固定演示", "仅供主机检查", 1L, states[0].id,
            states, edges, listOf(branch, finish), images)
        val file = File(app.noBackupFilesDir, "resume-$name.tapscene")
        ViewerPackageCodec.writePackage(scene, root, file, {}, null)
        file.inputStream().use { ReleaseStore(app).importPackage(it) }
        Fixture(scene, file, colors, listOf("main", "branch", "forward", "back-route", "finish").associateWith(::id))
    }
    private fun withTransitionMetadata(scene: ViewerScene, edgeId: String): ViewerScene {
        val assetId = UUID.randomUUID().toString()
        val metadata = ViewerScene.Asset(assetId, "assets/$assetId.mp4", "video/mp4", 1L, "0".repeat(64),
            32, 48, ViewerScene.Asset.ROLE_TRANSITION, 100L)
        val edges = scene.edges.map { edge -> ViewerScene.Edge(edge.id, edge.fromStateId, edge.toStateId,
            edge.endLabel, edge.hotspotId, edge.label, edge.trigger, edge.sourceKind, if (edge.id == edgeId) assetId else null) }
        return ViewerScene(2, ViewerPackageCodec.VIDEO_POLICY_VERSION, ViewerPackageCodec.COMPILER_VERSION,
            scene.releaseId, scene.title, scene.goal, scene.createdAt, scene.startStateId, scene.states,
            edges, scene.hotspots, scene.assets + metadata).also { ViewerPackageCodec.validateScene(it) }
    }
    private fun corruptCheckpoint(fixture: Fixture) {
        val root = RuntimeEnvironment.getApplication().noBackupFilesDir.canonicalFile
        val record = File(root, "release-store/releases/${fixture.scene.releaseId}/playback.json")
        check(record.isFile && record.canonicalFile.toPath().startsWith(root.toPath()))
        record.writeText("{broken checkpoint")
    }
    private fun job(workspace: ReleaseWorkspace, name: String) = ReleaseWorkspace::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(workspace) as Job?
    private fun storeMutex() = ReleaseStore::class.java.getDeclaredField("mutex")
        .apply { isAccessible = true }.get(null) as Mutex
    @Suppress("UNCHECKED_CAST")
    private fun mutableState(workspace: ReleaseWorkspace) = ReleaseWorkspace::class.java.getDeclaredField("mutableState")
        .apply { isAccessible = true }.get(workspace) as MutableStateFlow<ReleaseUiState>
    private fun resetApplicationViewModelFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
