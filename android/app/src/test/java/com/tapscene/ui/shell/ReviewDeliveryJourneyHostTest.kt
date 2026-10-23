package com.tapscene.ui.shell

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tapscene.MainActivity
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReleaseStore
import com.tapscene.data.ReviewedStepInput
import com.tapscene.data.StepOrigin
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.ScreenshotImporter
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.ui.CandidateWorkspace
import com.tapscene.ui.DeliverySelection
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real production shell, native host SQLite/PNG and saved bytes. Synthetic clicks are not
 * human privacy review; the document-result transport is not device SAF or lifecycle evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReviewDeliveryJourneyHostTest {
    private var previousRequestedSize: String? = null
    private lateinit var fixture: ProjectSnapshot

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            val app = RuntimeEnvironment.getApplication()
            val root = app.noBackupFilesDir.canonicalFile
            val sandbox = RuntimeEnvironment.getTempDirectory().basePath.toRealPath()
            check(root.toPath().startsWith(sandbox) && root.toPath() != sandbox)
            root.listFiles().orEmpty().forEach { child ->
                check(child.canonicalFile.toPath().startsWith(root.toPath()))
                check(child.deleteRecursively())
            }
            HostFileSyncShadow.begin(root)
            previousRequestedSize = System.setProperty("robolectric.windowManager.useRequestedSize", "true")
            // Only the saved source draft is prepared. No fixed candidate, review, traversal or seal.
            fixture = ProjectStore.withTemporary(app) { store ->
                runBlocking(Dispatchers.IO) { createDraft(app, store) }
            }
        }

        override fun after() {
            try { HostFileSyncShadow.reset() } finally {
                resetApplicationViewModelFactory()
                val key = "robolectric.windowManager.useRequestedSize"
                previousRequestedSize?.let { System.setProperty(key, it) } ?: System.clearProperty(key)
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @After fun finishReleaseWorkBeforeClosingHostDescriptors() {
        val owner = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
            .viewModelScope.coroutineContext[Job]!!
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        compose.waitUntil(30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            owner.isCompleted
        }
        compose.waitForIdle()
    }

    @Test fun projectChecksReviewTraversalSealAndSaveUseTheSameFixedPackage() {
        waitFor("悬浮编排")
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        val candidates = ViewModelProvider(compose.activity)[CandidateWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val store = ReleaseStore(app)
        assertSame(app, projects.getApplication<Application>())
        assertSame(app, releases.getApplication<Application>())
        fun settled() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !media.state.value.busy && !candidates.state.value.busy && !releases.state.value.busy
        }
        settled()
        runBlocking(Dispatchers.IO) {
            assertTrue(store.listCandidates().isEmpty())
            assertTrue(store.listReleases().isEmpty())
        }

        var saveRequest: Int? = null
        var saveIntent: Intent? = null
        var saveLaunches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(compose.activity, input)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals("application/zip", intent.type)
                // AndroidX CreateDocument 1.11 sets action, MIME and title, not OPENABLE.
                saveLaunches++
                assertEquals(1, saveLaunches)
                saveRequest = requestCode
                saveIntent = intent
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        // Keep production TapSceneApp and its real workspaces; replace only external picker transport.
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    TapSceneApp(projects, media, candidates, releases)
                }
            }
        }
        waitFor(fixture.project.title)
        compose.onNodeWithText(fixture.project.title).assertIsDisplayed().performClick()
        waitFor("分镜")
        settled()
        assertEquals(fixture.project.id, projects.state.value.project?.project?.id)
        compose.onNodeWithText("检查与交付").assertIsEnabled().performClick()
        waitForEnabled("生成待复核成品")
        assertTrue(projects.state.value.issues.isEmpty())
        compose.onNodeWithText("生成待复核成品").performScrollTo().assertIsDisplayed().performClick()
        waitForEnabled("确认画面、文字与动作")
        settled()
        val fixed = checkNotNull(releases.state.value.candidate)
        assertEquals(fixture.project.id, fixed.projectId)
        assertEquals(fixture.project.revision, fixed.projectRevision)
        assertEquals(fixture.steps.map { it.id }, fixed.scene.states.map { it.id })
        assertEquals(fixture.steps.map { it.asset.sha256 }, fixed.scene.assets.map { it.sha256 })
        assertTrue(fixed.reviewedStateIds.isEmpty() && fixed.visitedEdgeIds.isEmpty())
        assertFalse(fixed.summaryReviewed || fixed.fileListReviewed || fixed.completedPath || fixed.traversalStarted)
        compose.onNodeWithText("封存修订 ${fixed.projectRevision}").assertDoesNotExist()

        // Each actual decoded picture, title, explanation and action is visited before confirmation.
        for ((index, step) in fixture.steps.withIndex()) {
            waitForEnabled("确认画面、文字与动作")
            settled()
            assertEquals(step.id, releases.state.value.reviewStateId)
            val bitmap = checkNotNull(releases.state.value.reviewBitmap)
            assertEquals(step.asset.width, bitmap.width)
            assertEquals(step.asset.height, bitmap.height)
            assertEquals(if (index == 0) Color.CYAN else Color.YELLOW, bitmap.getPixel(0, 0))
            compose.onNodeWithContentDescription("候选实际输出：${step.title}").assertIsDisplayed()
            compose.onNodeWithText(step.title).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(step.description).performScrollTo().assertIsDisplayed()
            val action = step.nextAction?.label ?: step.hotspots.single().label
            compose.onNodeWithText(action).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("确认画面、文字与动作").assertIsDisplayed().performClick()
            settled()
            assertEquals(fixture.steps.take(index + 1).map { it.id }.toSet(), releases.state.value.candidate?.reviewedStateIds)
        }
        waitForEnabled("下一项：项目文字")
        compose.onNodeWithText("下一项：项目文字").assertIsDisplayed().performClick()
        compose.onNodeWithText(fixture.project.title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(fixture.project.goal).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("确认项目文字").performScrollTo().assertIsEnabled().performClick()
        settled()
        assertTrue(checkNotNull(releases.state.value.candidate).summaryReviewed)
        waitForEnabled("下一项：包清单")
        compose.onNodeWithText("下一项：包清单").performClick()
        compose.onNodeWithText("manifest.json").performScrollTo().assertIsDisplayed()
        for (file in ViewerPackageCodec.fileList(fixed.scene)) {
            compose.onNodeWithText(file.path).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("SHA-256 · ${file.sha256}").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("确认包清单").performScrollTo().assertIsEnabled().performClick()
        settled()
        assertTrue(checkNotNull(releases.state.value.candidate).fileListReviewed)
        assertFalse(checkNotNull(releases.state.value.candidate).completedPath)
        waitForEnabled("下一项：实际试走")
        compose.onNodeWithText("下一项：实际试走").performClick()
        compose.onNodeWithText("打开候选试走").performScrollTo().assertIsEnabled().performClick()
        waitForEnabled("下一步")
        settled()
        assertEquals(fixed.id, releases.state.value.player?.candidateId)
        assertEquals(fixture.steps.first().id, releases.state.value.player?.currentStateId)
        assertTrue(checkNotNull(releases.state.value.candidate).visitedEdgeIds.isEmpty())
        compose.onNodeWithText("下一步").assertIsDisplayed().performClick()
        waitForEnabled("完成演示")
        settled()
        val nextEdge = fixed.scene.edges.single { it.trigger == "continue" }
        val endEdge = fixed.scene.edges.single { it.trigger == "tap" }
        assertEquals(fixture.steps.last().id, releases.state.value.player?.currentStateId)
        assertEquals(setOf(nextEdge.id), releases.state.value.candidate?.visitedEdgeIds)
        // Tap the actual fitted image; the synthetic ending hotspot covers its center.
        compose.onNodeWithContentDescription("可点击热点；相同动作也在画面下方")
            .assertIsDisplayed().performTouchInput { click(center) }
        waitFor("演示结束")
        settled()
        assertTrue(checkNotNull(releases.state.value.player).ended)
        assertEquals(endEdge.id, releases.state.value.player?.endEdgeId)
        val reviewed = runBlocking(Dispatchers.IO) { store.readCandidateById(fixed.id) }
        assertEquals(fixed.contentDigest, reviewed.contentDigest)
        assertEquals(fixed.scene.states.map { it.id }.toSet(), reviewed.reviewedStateIds)
        assertTrue(reviewed.summaryReviewed && reviewed.fileListReviewed && reviewed.completedPath && reviewed.traversalEnded)
        assertEquals(fixed.scene.edges.map { it.id }.toSet(), reviewed.visitedEdgeIds)
        assertEquals(fixture.steps.map { it.id }, reviewed.traversalHistory)
        compose.onNodeWithText("退出").assertIsEnabled().performClick()
        waitForEnabled("查看封存检查")
        compose.onNodeWithText("查看封存检查").performClick()
        waitForEnabled("封存修订 ${fixed.projectRevision}")
        compose.onNodeWithText("封存修订 ${fixed.projectRevision}").performClick()
        compose.onNodeWithText("封存修订 ${fixed.projectRevision}？").assertIsDisplayed()
        assertTrue(runBlocking(Dispatchers.IO) { store.listReleases().isEmpty() })
        compose.onNodeWithText("确认封存").assertIsEnabled().performClick()
        waitForEnabled("保存到文件")
        settled()
        compose.onNodeWithText("交付方式").assertIsDisplayed()
        val selection = DeliverySelection(fixed.id, fixed.contentDigest, fixed.projectId, fixed.projectRevision)
        assertEquals(selection, releases.state.value.deliverySelection)
        assertEquals(fixed.id, releases.state.value.deliveryRelease?.id)
        assertNull(releases.state.value.candidate)

        val savedFile = File(app.noBackupFilesDir, "journey-saved.tapscene")
        assertFalse(savedFile.exists())
        compose.onNodeWithText("保存到文件").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(30_000) { compose.waitForIdle(); saveRequest != null && !releases.state.value.busy }
        assertEquals("TapScene-${fixed.id}.tapscene", checkNotNull(saveIntent).getStringExtra(Intent.EXTRA_TITLE))
        assertFalse(savedFile.exists())
        assertEquals(selection, releases.state.value.deliverySelection)
        compose.runOnIdle {
            // Host-only selected destination. Production saveExport must write and read back these bytes.
            assertTrue(registry.dispatchResult(checkNotNull(saveRequest), Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(savedFile))))
        }
        waitFor("离线观看包已完整保存，回读校验一致")
        settled()
        assertEquals(1, saveLaunches)
        assertTrue(savedFile.isFile && savedFile.length() > 0)
        runBlocking(Dispatchers.IO) {
            val directory = File(app.noBackupFilesDir, "journey-readback").apply { check(mkdir()) }
            val actual = ViewerPackageCodec.readPackage(savedFile, directory, {}, null)
            assertEquals(fixed.id, actual.scene.releaseId)
            assertEquals(fixed.contentDigest, actual.contentDigest)
            assertArrayEquals(ViewerPackageCodec.writeScene(fixed.scene), ViewerPackageCodec.writeScene(actual.scene))
            assertEquals(setOf("manifest.json", "scene.json") + fixed.scene.assets.map { it.path },
                directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).invariantSeparatorsPath }.toSet())
            for ((index, step) in fixture.steps.withIndex()) {
                val state = actual.scene.states.single { it.id == step.id }
                assertEquals(step.asset.id, state.imageAssetId)
                val asset = actual.scene.assets.single { it.id == state.imageAssetId }
                val png = File(directory, asset.path)
                assertEquals(step.asset.sha256, ViewerPackageCodec.sha256(png))
                assertEquals(step.asset.byteLength, png.length())
                val bitmap = checkNotNull(BitmapFactory.decodeFile(png.path))
                try {
                    assertEquals(step.asset.width, bitmap.width)
                    assertEquals(step.asset.height, bitmap.height)
                    assertEquals(if (index == 0) Color.CYAN else Color.YELLOW, bitmap.getPixel(0, 0))
                } finally { bitmap.recycle() }
            }
            val retained = store.readHostedRelease(fixed.id, fixed.contentDigest)
            assertEquals(fixed.projectId, retained.projectId)
            assertEquals(fixed.projectRevision, retained.projectRevision)
            assertArrayEquals(ViewerPackageCodec.writeScene(actual.scene), ViewerPackageCodec.writeScene(retained.scene))
        }
        compose.onNodeWithText("播放此版本").performScrollTo().assertIsEnabled().performClick()
        waitForEnabled("下一步")
        settled()
        assertEquals(fixed.id, releases.state.value.player?.scene?.releaseId)
        assertNull(releases.state.value.player?.candidateId)
        compose.onNodeWithText("退出").assertIsEnabled().performClick()
        waitForEnabled("保存到文件")
        compose.onNodeWithText("交付方式").assertIsDisplayed()
        assertEquals(selection, releases.state.value.deliverySelection)
        assertNull(releases.state.value.player)
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("查看交付方式")
        compose.onNodeWithText("检查与交付").assertIsSelected()
        assertEquals(ProjectRoute.STEPS, projects.state.value.route)
        assertEquals(fixture.project.id, projects.state.value.project?.project?.id)
        println("HOST_REVIEW_DELIVERY_JOURNEY project/checks/fixed-PNG-review/text/files/continue/image-tap/seal/saved-package/readback/player-return PASS; device-SAF/human-privacy-review/lifecycle NOT_RUN")
    }

    private suspend fun createDraft(app: Application, store: ProjectStore): ProjectSnapshot {
        var snapshot = store.createProject("两步复核与交付合成项目", "核对两张合成图片，依次前进并完成演示。")
        for ((index, color) in listOf(Color.CYAN, Color.YELLOW).withIndex()) {
            val input = File(app.noBackupFilesDir, "journey-input-$index.png")
            val bitmap = Bitmap.createBitmap(96, 160, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                input.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            ScreenshotImporter(app).importScreenshot(input).use { imported ->
                val png = SafeMediaWriter(app).writePng(imported.bitmap, emptyList(),
                    File(app.noBackupFilesDir, "journey-safe-$index"))
                snapshot = store.addReviewedStep(snapshot.project.id,
                    ReviewedStepInput(png.file, png.sha256, png.width, png.height,
                        StepOrigin.ImportedImage(imported.source), emptyList()),
                    if (index == 0) "第一步：查看示例" else "第二步：核对结果",
                    if (index == 0) "点击下一步查看第二张合成图片。" else "点击黄色画面中央的热点完成演示。")
            }
        }
        snapshot = store.connectStepsInOrder(snapshot.project.id, snapshot.steps.map { it.id }, false, snapshot.project.revision)
        val last = snapshot.steps.last()
        return store.saveStepDraft(snapshot.project.id, last.id, last.title, last.description, false,
            listOf(ProjectHotspot(UUID.randomUUID().toString(), "完成演示", OpaqueMask(.2f, .2f, .8f, .8f),
                null, "已核对合成结果", UUID.randomUUID().toString())), snapshot.project.revision)
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun waitForEnabled(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
    }

    private fun resetApplicationViewModelFactory() {
        // Lifecycle retains its first Application across separate Robolectric methods/classes.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
