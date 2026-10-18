package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ReleaseStore
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real fixed PNG package and player jobs; controlled store lock, no sleeps or device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class ReleasePlayerCancellationHostTest {
    @Before fun configure() {
        HostSqlite.configure()
        HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
    }
    @After fun closeDescriptors() = HostFileSyncShadow.reset()

    @Test fun cancelBlockedNextRetainsDisplayedVisitAndCannotClearNewPlayerWork() = runBlocking {
        withTimeout(120_000) {
            withPlayer { workspace, fixture ->
                advance(workspace, fixture.scene.edges[0].id)
                val displayed = workspace.state.value
                checkVisit(workspace, fixture, 1, listOf(0, 1))
                val lock = storeMutex()
                val cancelled: Job
                val replacement: Job
                lock.lock()
                try {
                    withContext(Dispatchers.Main) { workspace.chooseEdge(fixture.scene.edges[1].id) }
                    cancelled = checkNotNull(job(workspace, "playerTask"))
                    check(cancelled.isActive && workspace.state.value.busy)
                    check(workspace.state.value.stage == "读取安全画面")
                    checkSameVisit(workspace, displayed)
                    replacement = withContext(Dispatchers.Main) {
                        workspace.cancel()
                        check(cancelled.isCancelled) { "The top cancel action must cancel the player read job" }
                        check(!workspace.state.value.busy && workspace.state.value.stage == null)
                        check(workspace.state.value.message == "已取消读取，保留当前画面")
                        checkSameVisit(workspace, displayed)
                        // Start before yielding Main, so the cancelled generation cannot own this busy state.
                        workspace.previous()
                        checkNotNull(job(workspace, "playerTask")).also {
                            check(it !== cancelled && it.isActive && workspace.state.value.busy)
                            check(workspace.state.value.message == null)
                        }
                    }
                    cancelled.join()
                    check(cancelled.isCompleted && cancelled.isCancelled)
                    check(replacement.isActive && workspace.state.value.busy) {
                        "The old player's finally must not clear a newer read's busy state"
                    }
                    check(workspace.state.value.stage == "读取安全画面")
                    checkSameVisit(workspace, displayed)
                } finally { lock.unlock() }
                replacement.join()
                check(!workspace.state.value.busy && workspace.state.value.stage == null)
                checkVisit(workspace, fixture, 0, listOf(0))
                check(fixture.scene.edges[1].id !in checkNotNull(workspace.state.value.player).visitedEdgeIds)

                // The same owner remains usable after cancellation; no workspace recreation hides stale work.
                advance(workspace, fixture.scene.edges[0].id)
                advance(workspace, fixture.scene.edges[1].id)
                checkVisit(workspace, fixture, 2, listOf(0, 1, 2))
                withContext(Dispatchers.Main) { workspace.previous() }; playerSettled(workspace)
                checkVisit(workspace, fixture, 1, listOf(0, 1))
                withContext(Dispatchers.Main) { workspace.restart() }; playerSettled(workspace)
                checkVisit(workspace, fixture, 0, listOf(0))
                println("HOST_PLAYER_CANCEL real-PNG/blocked-next/current-visit/new-generation/previous/restart PASS; device NOT_RUN")
            }
        }
    }

    @Test fun cancelTransitionPreparationClearsPendingAndRejectsOldRunCallbacks() = runBlocking {
        withTimeout(120_000) {
            withPlayer { workspace, fixture ->
                advance(workspace, fixture.scene.edges[0].id)
                // Metadata-only transition: no fabricated MP4, validator result or successful video decode.
                // The actual library entry stays the imported static PNG package throughout this test.
                withContext(Dispatchers.Main) {
                    val state = mutableState(workspace)
                    state.value = state.value.copy(player = checkNotNull(state.value.player).copy(
                        scene = withTransitionMetadata(fixture.scene)))
                }
                val displayed = workspace.state.value
                val edgeId = fixture.scene.edges[1].id
                val lock = storeMutex()
                val cancelled: Job
                val secondCancelled: Job
                var oldRun = 0L
                val candidateMessage = "已取消处理，保留当前画面；请点重来后继续试走。"
                lock.lock()
                try {
                    withContext(Dispatchers.Main) { workspace.chooseEdge(edgeId) }
                    cancelled = checkNotNull(job(workspace, "playerTask"))
                    val pending = checkNotNull(workspace.state.value.player)
                    oldRun = checkNotNull(pending.traversalState).mediaRunId
                    check(cancelled.isActive && workspace.state.value.busy && oldRun > 0)
                    check(pending.traversalState?.pendingEdgeId == edgeId)
                    check(pending.video?.runId == oldRun && pending.video?.file == null)
                    checkSameVisit(workspace, displayed)
                    withContext(Dispatchers.Main) {
                        workspace.cancel()
                        check(cancelled.isCancelled) { "Cancel must stop transition preparation before file validation" }
                        checkCancelledTransition(workspace, displayed)
                        workspace.completePlayerTransition(oldRun)
                        workspace.failPlayerTransition(oldRun)
                        workspace.retryPlayerTransition(oldRun)
                        checkCancelledTransition(workspace, displayed)
                    }
                    cancelled.join()
                    checkCancelledTransition(workspace, displayed)
                    secondCancelled = withContext(Dispatchers.Main) {
                        // Candidate identity covers only the cancellation feedback branch. No candidate
                        // receipt or coverage persistence window is simulated or claimed to pass here.
                        val state = mutableState(workspace)
                        state.value = state.value.copy(player = checkNotNull(state.value.player).copy(
                            candidateId = UUID.randomUUID().toString()))
                        workspace.chooseEdge(edgeId)
                        check(workspace.state.value.message == null)
                        val next = checkNotNull(workspace.state.value.player)
                        val newRun = checkNotNull(next.traversalState).mediaRunId
                        check(newRun != oldRun && next.traversalState?.pendingEdgeId == edgeId)
                        check(next.video?.runId == newRun && next.video?.file == null)
                        workspace.failPlayerTransition(oldRun)
                        check(workspace.state.value.player == next) { "An old callback must not fail the new transition" }
                        val job = checkNotNull(job(workspace, "playerTask"))
                        workspace.cancel()
                        check(job.isCancelled)
                        checkCancelledTransition(workspace, displayed, candidateMessage)
                        state.value = state.value.copy(player = checkNotNull(state.value.player).copy(candidateId = null))
                        job
                    }
                } finally { lock.unlock() }
                joinAll(cancelled, secondCancelled)
                // Releasing the store and completing old jobs cannot restore pending video or visit the target.
                withContext(Dispatchers.Main) {
                    workspace.completePlayerTransition(oldRun)
                    workspace.failPlayerTransition(oldRun)
                    workspace.retryPlayerTransition(oldRun)
                    workspace.skipPlayerTransition()
                }
                checkCancelledTransition(workspace, displayed, candidateMessage)
                check(edgeId !in checkNotNull(workspace.state.value.player).visitedEdgeIds)
                val stored = withContext(Dispatchers.IO) { ReleaseStore(RuntimeEnvironment.getApplication()).loadRelease(fixture.scene.releaseId) }
                check(ViewerPackageCodec.contentDigest(stored) == ViewerPackageCodec.contentDigest(fixture.scene))
                check(stored.assets.all { it.role == ViewerScene.Asset.ROLE_IMAGE })
                withContext(Dispatchers.Main) { workspace.previous() }; playerSettled(workspace)
                checkVisit(workspace, fixture, 0, listOf(0))
                println("HOST_PLAYER_CANCEL transition-preparation/pending-clear/new-run/stale-callback/candidate-message PASS; candidate-coverage-window/MP4 validation/decoding/device NOT_RUN")
            }
        }
    }

    private suspend fun withPlayer(block: suspend (ReleaseWorkspace, Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val fixture = withContext(Dispatchers.IO) { fixture(app) }
        val owner = ViewModelStore()
        val workspace = withContext(Dispatchers.Main) {
            ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[ReleaseWorkspace::class.java]
        }
        try {
            job(workspace, "task")?.join()
            check(!workspace.state.value.busy)
            withContext(Dispatchers.Main) { workspace.openRelease(fixture.scene.releaseId) }
            job(workspace, "task")?.join()
            check(!workspace.state.value.busy)
            checkVisit(workspace, fixture, 0, listOf(0))
            block(workspace, fixture)
        } finally {
            val jobs = withContext(Dispatchers.Main) {
                listOfNotNull(job(workspace, "task"), job(workspace, "playerTask")).also { owner.clear() }
            }
            jobs.joinAll()
        }
    }

    private data class Fixture(val scene: ViewerScene, val colors: List<Int>)

    private suspend fun fixture(app: Application): Fixture {
        val releaseId = UUID.randomUUID().toString()
        fun id(name: String) = UUID.nameUUIDFromBytes("$releaseId/$name".toByteArray()).toString()
        val root = File(app.noBackupFilesDir, "player-cancel-$releaseId").also { check(it.mkdir()) }
        val assets = File(root, "assets").also { check(it.mkdir()) }
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
        val images = colors.mapIndexed { index, color ->
            val assetId = id("image-$index")
            val image = File(assets, "$assetId.png")
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            ViewerScene.Asset(assetId, "assets/$assetId.png", "image/png", image.length(), ViewerPackageCodec.sha256(image), 32, 48)
        }
        val states = images.mapIndexed { index, image ->
            ViewerScene.State(id("state-$index"), image.id, 32, 48, "画面 ${index + 1}", "合成测试画面", "authored", index == 2)
        }
        val edges = (0..1).map { index ->
            ViewerScene.Edge(id("edge-$index"), states[index].id, states[index + 1].id, null, null, "下一步", "continue", "authored")
        }
        val scene = ViewerScene(releaseId, "Player cancellation", "Synthetic PNG fixture", 1L,
            states[0].id, states, edges, emptyList(), images)
        val file = File(app.noBackupFilesDir, "player-cancel-$releaseId.tapscene")
        ViewerPackageCodec.writePackage(scene, root, file, {}, null)
        val imported = file.inputStream().use { ReleaseStore(app).importPackage(it) }
        check(imported.id == releaseId && imported.contentDigest == ViewerPackageCodec.contentDigest(scene))
        return Fixture(scene, colors)
    }

    private fun withTransitionMetadata(scene: ViewerScene): ViewerScene {
        val id = UUID.randomUUID().toString()
        val metadataOnly = ViewerScene.Asset(id, "assets/$id.mp4", "video/mp4", 1L,
            "0".repeat(64), 32, 48, ViewerScene.Asset.ROLE_TRANSITION, 100L)
        val edges = scene.edges.mapIndexed { index, edge ->
            ViewerScene.Edge(edge.id, edge.fromStateId, edge.toStateId, edge.endLabel, edge.hotspotId,
                edge.label, edge.trigger, edge.sourceKind, if (index == 1) id else null)
        }
        return ViewerScene(2, ViewerPackageCodec.VIDEO_POLICY_VERSION, ViewerPackageCodec.COMPILER_VERSION,
            scene.releaseId, scene.title, scene.goal, scene.createdAt, scene.startStateId,
            scene.states, edges, scene.hotspots, scene.assets + metadataOnly).also { ViewerPackageCodec.validateScene(it) }
    }

    private fun checkSameVisit(workspace: ReleaseWorkspace, displayed: ReleaseUiState) {
        val state = workspace.state.value
        val player = checkNotNull(state.player)
        val original = checkNotNull(displayed.player)
        check(player.currentStateId == original.currentStateId && player.history == original.history)
        check(player.ended == original.ended && player.visitedEdgeIds == original.visitedEdgeIds)
        check(player.completedFromStart == original.completedFromStart)
        check(state.playerBitmap === displayed.playerBitmap && state.playerBitmap?.isRecycled == false)
    }

    private fun checkCancelledTransition(workspace: ReleaseWorkspace, displayed: ReleaseUiState,
        message: String = "已取消读取，保留当前画面") {
        checkSameVisit(workspace, displayed)
        val state = workspace.state.value
        val player = checkNotNull(state.player)
        check(!state.busy && state.stage == null && state.message == message)
        check(player.video == null && player.traversalState?.pendingEdgeId == null)
        check(player.traversalState?.pendingTransitionAssetId == null && player.traversalState?.pendingToStateId == null)
        check(player.traversalState?.transitionFailed == false)
    }

    private fun checkVisit(workspace: ReleaseWorkspace, fixture: Fixture, index: Int, visits: List<Int>) {
        val state = workspace.state.value
        val player = checkNotNull(state.player)
        check(player.currentStateId == fixture.scene.states[index].id)
        check(player.history == visits.map { fixture.scene.states[it].id })
        check(player.ended == fixture.scene.states[index].terminal)
        val bitmap = checkNotNull(state.playerBitmap)
        check(bitmap.width == 32 && bitmap.height == 48 && bitmap.getPixel(16, 24) == fixture.colors[index])
    }

    private suspend fun advance(workspace: ReleaseWorkspace, edgeId: String) {
        withContext(Dispatchers.Main) { workspace.chooseEdge(edgeId) }
        playerSettled(workspace)
    }
    private suspend fun playerSettled(workspace: ReleaseWorkspace) {
        checkNotNull(job(workspace, "playerTask")).join()
        check(!workspace.state.value.busy && workspace.state.value.stage == null)
    }
    private fun job(workspace: ReleaseWorkspace, name: String) = ReleaseWorkspace::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(workspace) as Job?
    private fun storeMutex() = ReleaseStore::class.java.getDeclaredField("mutex")
        .apply { isAccessible = true }.get(null) as Mutex
    @Suppress("UNCHECKED_CAST")
    private fun mutableState(workspace: ReleaseWorkspace) = ReleaseWorkspace::class.java.getDeclaredField("mutableState")
        .apply { isAccessible = true }.get(workspace) as MutableStateFlow<ReleaseUiState>
}
