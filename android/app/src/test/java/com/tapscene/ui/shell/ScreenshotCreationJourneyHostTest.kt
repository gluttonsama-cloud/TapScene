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
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep
import com.tapscene.data.ProjectStore
import com.tapscene.data.StepOrigin
import com.tapscene.media.OpaqueMask
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.ui.CandidateWorkspace
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import com.tapscene.ui.ScreenshotWorkspace
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
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
import org.robolectric.fakes.BaseCursor

/** Production shell/importer/PNG/store with synthetic private inputs. Only document-result
 * transport and its content resolver are host seams; this is not device SAF or human review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenshotCreationJourneyHostTest {
    private var previousRequestedSize: String? = null

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
            previousRequestedSize = System.setProperty("robolectric.windowManager.useRequestedSize", "true")
            // No project, step, source registration or review is prepared here.
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

    @After fun finishWorkspaceCleanupBeforeClosingHostDescriptors() {
        val provider = ViewModelProvider(compose.activity)
        val screenshots = provider[ScreenshotWorkspace::class.java]
        val media = provider[MediaWorkspace::class.java]
        val owners = listOf(screenshots, media, provider[ProjectWorkspace::class.java],
            provider[CandidateWorkspace::class.java], provider[ReleaseWorkspace::class.java])
            .map { checkNotNull(it.viewModelScope.coroutineContext[Job]) }
        // These scopes outlive viewModelScope. Observe their completion, never replace or cancel
        // them: onCleared/close must finish their own real file and owner cleanup first.
        val cleanups = listOf(ScreenshotWorkspace::class.java, MediaWorkspace::class.java).map {
            val scope = it.getDeclaredField("cleanupScope").apply { isAccessible = true }.get(null) as CoroutineScope
            checkNotNull(scope.coroutineContext[Job])
        }
        val ownerId = ScreenshotWorkspace::class.java.getDeclaredField("ownerId")
            .apply { isAccessible = true }.get(screenshots) as String
        @Suppress("UNCHECKED_CAST")
        val active = ScreenshotWorkspace::class.java.getDeclaredField("active")
            .apply { isAccessible = true }.get(null) as MutableSet<String>
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        compose.waitUntil(30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            owners.all { it.isCompleted } && cleanups.all { scope -> scope.children.none { !it.isCompleted } } &&
                synchronized(active) { ownerId !in active }
        }
        compose.waitForIdle()
        val root = RuntimeEnvironment.getApplication().noBackupFilesDir
        assertFalse(File(root, "screenshot-outputs/$ownerId").exists())
        assertTrue(File(root, "image-import-staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun homeCancellationThenTwoReviewedScreenshotsCreateOneProjectAndKeepBothSteps() {
        waitFor("制作第一份可点击演示")
        val app = RuntimeEnvironment.getApplication()
        val provider = ViewModelProvider(compose.activity)
        val projects = provider[ProjectWorkspace::class.java]
        val media = provider[MediaWorkspace::class.java]
        val candidates = provider[CandidateWorkspace::class.java]
        val releases = provider[ReleaseWorkspace::class.java]
        val screenshots = provider[ScreenshotWorkspace::class.java]
        assertSame(app, screenshots.getApplication<Application>())
        fun settled() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !media.state.value.busy && !candidates.state.value.busy &&
                !releases.state.value.busy && !screenshots.state.value.media.busy
        }
        settled()
        assertTrue(readStore { it.listProjects().isEmpty() })

        val opens = AtomicInteger()
        val firstInput = registerInput(app, 1, Color.CYAN, opens)
        val secondInput = registerInput(app, 2, Color.YELLOW, opens)
        var pendingRequest: Int? = null
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(compose.activity, input)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                assertEquals("*/*", intent.type)
                assertArrayEquals(arrayOf("image/png", "image/jpeg"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
                assertNull(pendingRequest)
                launches++
                pendingRequest = requestCode
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    TapSceneApp(projects, media, candidates, releases)
                }
            }
        }
        fun deliver(uri: Uri?) {
            compose.waitUntil(30_000) { pendingRequest != null }
            compose.runOnIdle {
                val request = checkNotNull(pendingRequest)
                pendingRequest = null
                assertTrue(registry.dispatchResult(request,
                    if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                    uri?.let { Intent().setData(it) }))
            }
        }
        fun chooseFromHome() {
            waitFor("其他创建方式")
            compose.onNodeWithText("其他创建方式").assertIsDisplayed().performClick()
            compose.onNodeWithText("导入截图").assertIsDisplayed().performClick()
        }

        // Cancel the actual home entry before any source is read or empty project is created.
        chooseFromHome()
        assertEquals(1, launches)
        deliver(null)
        settled()
        compose.onNodeWithText("制作第一份可点击演示").assertIsDisplayed()
        assertNull(screenshots.state.value.sessionId)
        assertNull(projects.state.value.project)
        assertEquals(0, opens.get())
        assertTrue(readStore { it.listProjects().isEmpty() })

        chooseFromHome()
        deliver(firstInput.uri)
        waitForEnabled("生成并复核")
        settled()
        assertEquals(2, launches)
        assertEquals(1, opens.get())
        assertTrue(screenshots.state.value.creatingProject)
        val projectId = checkNotNull(screenshots.state.value.targetProjectId)
        val firstSession = checkNotNull(screenshots.state.value.sessionId)
        assertPixels(checkNotNull(screenshots.state.value.draft?.bitmap), Color.CYAN, false)
        compose.onNodeWithText("确认并创建项目").assertDoesNotExist()
        assertTrue(readStore { it.listProjects().isEmpty() })

        // Add a real UI mask so review must display baked-in black pixels, not an overlay.
        compose.onNodeWithText("精确添加").performScrollTo().assertIsEnabled().performClick()
        listOf("左" to "25", "上" to "25", "右" to "75", "下" to "75").forEach { (label, value) ->
            compose.onNodeWithText(label).performTextReplacement(value)
        }
        compose.onNodeWithText("添加").assertIsEnabled().performClick()
        assertEquals(listOf(OpaqueMask(.25f, .25f, .75f, .75f)), screenshots.state.value.draft?.masks)
        val firstOutput = generateAndReview(screenshots, Color.CYAN, true, "确认并创建项目")
        assertTrue(readStore { it.listProjects().isEmpty() })
        // Both visible and system back must keep real masking work until explicitly discarded.
        compose.onNodeWithText("返回").assertIsEnabled().performClick()
        compose.onNodeWithText("放弃本次截图导入？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals(firstSession, screenshots.state.value.sessionId)
        assertEquals(firstOutput.sha256, screenshots.state.value.media.candidate?.sha256)
        assertEquals(listOf(OpaqueMask(.25f, .25f, .75f, .75f)), screenshots.state.value.draft?.masks)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("放弃本次截图导入？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        assertTrue(readStore { it.listProjects().isEmpty() })
        compose.onNodeWithText("确认并创建项目").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            projects.state.value.project?.project?.id == projectId && projects.state.value.project?.steps?.size == 1 &&
                screenshots.state.value.sessionId == null && !projects.state.value.busy
        }
        waitFor("分镜")
        settled()
        compose.onNodeWithText("步骤").assertIsSelected()
        compose.onNodeWithText("1 个步骤 · 0 个出口").assertIsDisplayed()
        val firstSaved = readStore { checkNotNull(it.readProject(projectId)) }
        val firstStep = firstSaved.steps.single()
        assertEquals("截图演示", firstSaved.project.title)
        assertEquals("screenshot-$firstSession", firstStep.captureId)
        verifySaved(firstSaved, firstStep, firstInput, firstOutput, Color.CYAN, true)

        // The existing project's real storyboard -> materials entry must keep this same owner.
        compose.onNodeWithText("添加内容").assertIsEnabled().performClick()
        compose.onNodeWithText("素材").assertIsSelected()
        waitForEnabled("其他方式")
        compose.onNodeWithText("其他方式").performClick()
        compose.onNodeWithText("添加素材").assertIsDisplayed()
        compose.onNodeWithText("导入截图").assertIsEnabled().performClick()
        deliver(secondInput.uri)
        waitForEnabled("生成并复核")
        settled()
        assertEquals(3, launches)
        assertEquals(2, opens.get())
        assertEquals(projectId, screenshots.state.value.targetProjectId)
        assertFalse(screenshots.state.value.creatingProject)
        val secondSession = checkNotNull(screenshots.state.value.sessionId)
        assertNotEquals(firstSession, secondSession)
        assertEquals(firstSaved, readStore { it.readProject(projectId) })
        val secondOutput = generateAndReview(screenshots, Color.YELLOW, false, "确认并加入")
        assertNotEquals(firstOutput.sha256, secondOutput.sha256)
        assertEquals(firstSaved, readStore { it.readProject(projectId) })
        // Explicit discard removes only this unconfirmed import. Re-entry stays on the owner.
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("放弃本次截图导入？").assertIsDisplayed()
        compose.onNodeWithText("放弃导入").performClick()
        settled()
        assertNull(screenshots.state.value.sessionId)
        assertEquals(firstSaved, readStore { it.readProject(projectId) })
        waitForEnabled("其他方式")
        compose.onNodeWithText("其他方式").performClick()
        compose.onNodeWithText("导入截图").performClick()
        // A fresh host query cursor models the newly opened document provider request.
        registerInput(app, 2, Color.YELLOW, opens)
        deliver(secondInput.uri)
        waitForEnabled("生成并复核")
        val retrySession = checkNotNull(screenshots.state.value.sessionId)
        assertNotEquals(secondSession, retrySession)
        assertEquals(projectId, screenshots.state.value.targetProjectId)
        val retryOutput = generateAndReview(screenshots, Color.YELLOW, false, "确认并加入")
        assertArrayEquals(secondOutput.bytes, retryOutput.bytes)
        assertEquals(4, launches)
        assertEquals(3, opens.get())
        compose.onNodeWithText("确认并加入").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            projects.state.value.project?.project?.id == projectId && projects.state.value.project?.steps?.size == 2 &&
                screenshots.state.value.sessionId == null && !projects.state.value.busy
        }
        waitFor("分镜")
        settled()
        compose.onNodeWithText("步骤").assertIsSelected()
        compose.onNodeWithText("2 个步骤 · 0 个出口").assertIsDisplayed()
        val finalSaved = readStore { checkNotNull(it.readProject(projectId)) }
        assertEquals(listOf(projectId), readStore { it.listProjects().map { project -> project.id } })
        assertEquals(firstStep, finalSaved.steps.first())
        assertEquals(firstSaved.project.startStepId, finalSaved.project.startStepId)
        assertTrue(finalSaved.project.revision > firstSaved.project.revision)
        val secondStep = finalSaved.steps.last()
        assertNotEquals(firstStep.id, secondStep.id)
        assertNotEquals(firstStep.asset.id, secondStep.asset.id)
        assertEquals("screenshot-$retrySession", secondStep.captureId)
        verifySaved(finalSaved, firstStep, firstInput, firstOutput, Color.CYAN, true)
        verifySaved(finalSaved, secondStep, secondInput, secondOutput, Color.YELLOW, false)
        assertEquals(2, readStore { it.screenshotCount(projectId) })

        // Same default titles are deliberately resolved by the real visible ordinal, then ID.
        for ((index, step) in finalSaved.steps.withIndex()) {
            val ordinal = (index + 1).toString().padStart(2, '0')
            compose.onNode(hasScrollToKeyAction()).performScrollToKey(step.id)
            val menu = compose.onNodeWithContentDescription("步骤 $ordinal ${step.title}，更多操作")
            menu.assertIsDisplayed()
            val rowY = menu.fetchSemanticsNode().boundsInRoot.center.y
            // Layout-only Rows have no semantics node; sibling matching would confuse equal titles.
            val row = hasText(step.title) and hasClickAction() and SemanticsMatcher("row at ordinal $ordinal") {
                it.boundsInRoot.top <= rowY && it.boundsInRoot.bottom > rowY
            }
            compose.onNode(row).assertIsDisplayed().performClick()
            compose.waitUntil(30_000) {
                compose.waitForIdle()
                projects.state.value.route == ProjectRoute.EDIT && projects.state.value.selectedStepId == step.id &&
                    projects.state.value.bitmap != null && !projects.state.value.busy
            }
            assertEquals(step.id, projects.state.value.stepDraft?.stepId)
            assertPixels(checkNotNull(projects.state.value.bitmap), if (index == 0) Color.CYAN else Color.YELLOW, index == 0)
            compose.onNodeWithText("步骤 ${index + 1} · 已保存").assertIsDisplayed()
            compose.onNodeWithText("返回").assertIsEnabled().performClick()
            waitFor("分镜")
            settled()
        }
        assertEquals(ProjectRoute.STEPS, projects.state.value.route)
        assertEquals(finalSaved, readStore { it.readProject(projectId) })
        println("HOST_SCREENSHOT_CREATION_JOURNEY home/cancel/no-empty-project/content-URI/import/UI-mask/actual-PNG-review/first-project/same-project-second-step/identity-and-bytes/editor-return PASS; device-SAF/human-privacy-review/lifecycle NOT_RUN")
    }

    private data class Input(val uri: Uri, val file: File)
    private data class Output(val bytes: ByteArray, val sha256: String)

    private fun registerInput(app: Application, index: Int, color: Int, opens: AtomicInteger): Input {
        val file = File(app.noBackupFilesDir, "screenshot-journey-input-$index.png")
        val bitmap = Bitmap.createBitmap(96, 160, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val uri = Uri.parse("content://com.tapscene.host.screenshots/document/$index")
        val resolver = shadowOf(app.contentResolver)
        resolver.registerInputStreamSupplier(uri) { opens.incrementAndGet(); file.inputStream() }
        resolver.setCursor(uri, object : BaseCursor() {
            private var closed = false
            override fun moveToFirst() = true
            override fun getString(columnIndex: Int): String {
                check(!closed && columnIndex == 0)
                return file.name
            }
            override fun close() { closed = true }
            override fun isClosed() = closed
        })
        return Input(uri, file)
    }

    private fun generateAndReview(screenshots: ScreenshotWorkspace, color: Int, masked: Boolean, confirm: String): Output {
        compose.onNodeWithText("生成并复核").assertIsEnabled().performClick()
        waitForEnabled(confirm)
        compose.onNodeWithText("复核截图").assertIsDisplayed()
        compose.onNodeWithText("复核").assertIsSelected()
        compose.onNodeWithContentDescription("待复核的实际生成 PNG，未叠加编辑标记").assertIsDisplayed()
        compose.onNodeWithText("96 × 160 像素 · 实际生成图片").performScrollTo().assertIsDisplayed()
        val state = screenshots.state.value
        val candidate = checkNotNull(state.media.candidate)
        assertEquals("image/png", candidate.mimeType)
        assertEquals(candidate.sha256, ViewerPackageCodec.sha256(candidate.file))
        assertTrue(state.canConfirm(candidate.sha256))
        assertPixels(checkNotNull(state.media.candidateImage), color, masked)
        val decoded = checkNotNull(BitmapFactory.decodeFile(candidate.file.path))
        try { assertPixels(decoded, color, masked) } finally { decoded.recycle() }
        return Output(candidate.file.readBytes(), candidate.sha256)
    }

    private fun verifySaved(snapshot: ProjectSnapshot, step: ProjectStep, input: Input, output: Output, color: Int, masked: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(output.sha256, step.asset.sha256)
        assertEquals(output.bytes.size.toLong(), step.asset.byteLength)
        assertEquals("authored", step.evidenceKind)
        assertNull(step.videoOrigin)
        assertNull(step.frameTimeUs)
        assertEquals(if (masked) listOf(OpaqueMask(.25f, .25f, .75f, .75f)) else emptyList<OpaqueMask>(), step.masks)
        val source = (step.origin as StepOrigin.ImportedImage).source
        assertEquals(input.file.name, source.displayName)
        assertEquals("image-sources/${source.sourceId}/original.png", source.privateRelativePath)
        assertEquals(ViewerPackageCodec.sha256(input.file), source.metadata.sha256)
        assertArrayEquals(input.file.readBytes(), File(app.noBackupFilesDir, source.privateRelativePath).readBytes())
        val png = readStore { it.resolveAsset(snapshot.project.id, step.id) }
        assertArrayEquals(output.bytes, png.readBytes())
        assertEquals(output.sha256, ViewerPackageCodec.sha256(png))
        val decoded = checkNotNull(BitmapFactory.decodeFile(png.path))
        try { assertPixels(decoded, color, masked) } finally { decoded.recycle() }
    }

    private fun assertPixels(bitmap: Bitmap, color: Int, masked: Boolean) {
        assertEquals(96, bitmap.width)
        assertEquals(160, bitmap.height)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val expected = if (masked && x in 24 until 72 && y in 40 until 120) Color.BLACK else color
            assertEquals("Pixel ($x,$y)", expected, bitmap.getPixel(x, y))
        }
    }

    private fun <T> readStore(block: (ProjectStore) -> T): T = runBlocking(Dispatchers.IO) {
        ProjectStore.withTemporary(RuntimeEnvironment.getApplication(), block)
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
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
