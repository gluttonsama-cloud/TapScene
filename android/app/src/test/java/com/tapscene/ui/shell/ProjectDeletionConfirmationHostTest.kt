package com.tapscene.ui.shell

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tapscene.MainActivity
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.ProjectSummary
import com.tapscene.data.ReviewedStepInput
import com.tapscene.data.StepOrigin
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.ScreenshotImporter
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.ui.*
import java.io.File
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

/** Real shell, SQLite and synthetic saved PNGs. No device, import-review or storage-CAS claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectDeletionConfirmationHostTest {
    private var previousRequestedSize: String? = null
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val projects: ProjectWorkspace get() = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetFactory()
            HostSqlite.configure()
            val root = app.noBackupFilesDir.canonicalFile
            val sandbox = RuntimeEnvironment.getTempDirectory().basePath.toRealPath()
            check(root.toPath().startsWith(sandbox) && root.toPath() != sandbox)
            // Only this test's synthetic files in Robolectric's private temporary directory.
            root.listFiles().orEmpty().forEach { child ->
                check(child.canonicalFile.toPath().startsWith(root.toPath()))
                check(child.deleteRecursively())
            }
            HostFileSyncShadow.begin(root)
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

    @After fun finishActivityOwnersBeforeClosingHostDescriptors() {
        val provider = ViewModelProvider(compose.activity)
        val owners = listOf(provider[ProjectWorkspace::class.java], provider[MediaWorkspace::class.java],
            provider[CandidateWorkspace::class.java], provider[ReleaseWorkspace::class.java],
            provider[ScreenshotWorkspace::class.java], provider[TransitionWorkspace::class.java],
            provider[RegionWorkspace::class.java], provider[AiDraftWorkspace::class.java],
            provider[HostingWorkspace::class.java]).map { checkNotNull(it.viewModelScope.coroutineContext[Job]) }
        // These production cleanup scopes outlive their owners. Observe, never cancel or replace.
        val cleanups = listOf(ScreenshotWorkspace::class.java, MediaWorkspace::class.java,
            TransitionWorkspace::class.java).map {
            val scope = it.getDeclaredField("cleanupScope").apply { isAccessible = true }.get(null) as CoroutineScope
            checkNotNull(scope.coroutineContext[Job])
        }
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        compose.waitUntil(30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            owners.all { it.isCompleted } && cleanups.all { scope -> scope.children.all { it.isCompleted } }
        }
        compose.waitForIdle()
    }

    @Test fun actualHomeMenuIdentifiesSameTitleProjectsAndDeletesOnlyTheConfirmedOne() {
        waitFor("悬浮编排")
        awaitIdle()
        val fixtures = listOf(savedFixture(Color.CYAN), savedFixture(Color.YELLOW))
        assertEquals(fixtures[0].project.title, fixtures[1].project.title)
        assertNotEquals(fixtures[0].project.id, fixtures[1].project.id)
        compose.runOnIdle { projects.reload() }
        awaitIdle()
        val target = fixtures.single { it.project.id == projects.state.value.projects.first().id }
        val other = fixtures.single { it.project.id != target.project.id }
        val targetFiles = assetHashes(target.project.id)
        val otherFiles = assetHashes(other.project.id)
        assertTrue(targetFiles.isNotEmpty() && otherFiles.isNotEmpty())

        openHomeDeletion(target.project)
        compose.onNodeWithText("项目 ID：${other.project.id}").assertDoesNotExist()
        compose.onNode(hasText("${target.project.stepCount} 个步骤 · 修订 ${target.project.revision}") and
            hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        compose.onNodeWithText("删除本机项目？").assertDoesNotExist()
        assertEquals(target, readProject(target.project.id))
        assertEquals(other, readProject(other.project.id))
        assertEquals(targetFiles, assetHashes(target.project.id))
        assertEquals(otherFiles, assetHashes(other.project.id))

        openHomeDeletion(target.project)
        compose.onNodeWithText("删除").assertIsDisplayed().assertIsEnabled().performClick()
        awaitIdle()
        compose.onNodeWithText("删除本机项目？").assertDoesNotExist()
        assertNull(readProject(target.project.id))
        assertTrue(assetHashes(target.project.id).isEmpty())
        assertEquals(other, readProject(other.project.id))
        assertEquals(otherFiles, assetHashes(other.project.id))
        assertEquals(listOf(other.project.id), projects.state.value.projects.map { it.id })
    }

    @Test fun externallyChangedSummaryInvalidatesTheOpenConfirmationUntilCancelledAndReopened() {
        waitFor("悬浮编排")
        awaitIdle()
        val original = savedFixture(Color.MAGENTA)
        compose.runOnIdle { projects.reload() }
        awaitIdle()
        openHomeDeletion(original.project)
        val files = assetHashes(original.project.id)
        // A deliberately controlled persisted change plus reload checks the visible-summary gate.
        // It does not reproduce a racing UI edit or promise a storage-level compare-and-delete.
        val changed = ProjectStore.withTemporary(app) {
            it.renameProject(original.project.id, original.project.title, "重新打开后才可核对的说明")
        }
        compose.runOnIdle { projects.reload() }
        awaitIdle()
        compose.onNodeWithText("项目内容已变化，请取消后重新打开并核对。").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsDisplayed().assertIsNotEnabled().performClick()
        assertEquals(changed, readProject(original.project.id))
        assertEquals(files, assetHashes(original.project.id))
        compose.onNodeWithText("取消").performClick()
        openHomeDeletion(changed.project)
        compose.onNodeWithText("项目内容已变化，请取消后重新打开并核对。").assertDoesNotExist()
        compose.onNodeWithText("开场说明摘要：${changed.project.goal}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsEnabled()
        compose.onNodeWithText("取消").performClick()
        assertEquals(changed, readProject(original.project.id))
    }

    @Test fun sharedContentKeepsLongDetailsScrollableAndBlocksStaleOrBusyConfirmation() {
        waitFor("悬浮编排")
        awaitIdle()
        val item = ProjectSummary("12345678-1234-5678-9abc-123456789abc", "同名长项目".repeat(24),
            "这是需要明确核对的很长开场说明。".repeat(50), 42, 0, 40, null)
        val current = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        var confirmations = 0
        var dismissals = 0
        compose.runOnIdle {
            compose.activity.setContent {
                TapSceneTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                        Box(Modifier.size(width = 280.dp, height = 520.dp)) {
                            ProjectDeleteConfirmationContent(item, current.value, enabled.value,
                                { dismissals++ }, { confirmations++ })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("删除本机项目？").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("取消").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        val actionBounds = compose.onNodeWithText("删除").fetchSemanticsNode().boundsInRoot
        val idText = "项目 ID：${item.id}"
        compose.onNodeWithText(idText).performScrollTo().assertIsDisplayed()
        val idLayout = textLayout(idText)
        assertTrue("The full project ID must wrap naturally", idLayout.lineCount > 1)
        assertEquals(idText.length, idLayout.getLineEnd(idLayout.lineCount - 1))
        assertFalse(idLayout.didOverflowHeight)
        assertTrue((0 until idLayout.lineCount).none { idLayout.isLineEllipsized(it) })
        val goalText = "开场说明摘要：${item.goal}"
        compose.onNodeWithText(goalText).performScrollTo().assertIsDisplayed()
        assertTrue("Opening text is a bounded summary", textLayout(goalText).lineCount <= 2)
        compose.onNodeWithText("此项目的步骤、热点与下一步、区域、动画计划和暂存修改；项目内图片、过渡短片、原截图副本及文字建议。无法撤销。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("演示库中的固定候选与封存版本（含独立图片），需要在演示库分别管理。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("原录屏、外部原文件和已导出文件保留。原录屏可在“设置 → 本机保留素材”中管理。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsDisplayed()
        assertEquals("Actions stay fixed while details scroll", actionBounds,
            compose.onNodeWithText("删除").fetchSemanticsNode().boundsInRoot)

        compose.runOnIdle { current.value = false }
        compose.onNodeWithText("项目内容已变化，请取消后重新打开并核对。").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsDisplayed().assertIsNotEnabled().performClick()
        assertEquals(0, confirmations)
        compose.runOnIdle { current.value = true; enabled.value = false }
        compose.onNodeWithText("当前暂不可删除，可取消后重新核对。").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsDisplayed().assertIsNotEnabled().performClick()
        assertEquals(0, confirmations)
        compose.onNodeWithText("取消").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, dismissals)
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("删除").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, confirmations)
    }

    private fun savedFixture(color: Int): ProjectSnapshot = ProjectStore.withTemporary(app) { store ->
        runBlocking(Dispatchers.IO) {
            val project = store.createProject("同名本机项目", "合成删除确认检查")
            val input = File(app.noBackupFilesDir, "delete-input-${project.project.id}.png")
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                input.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            // Prepare saved synthetic pixels directly; screenshot acquisition/review is not under test.
            ScreenshotImporter(app).importScreenshot(input).use { imported ->
                val png = SafeMediaWriter(app).writePng(imported.bitmap, emptyList(),
                    File(app.noBackupFilesDir, "delete-safe-${project.project.id}"))
                store.addReviewedStep(project.project.id,
                    ReviewedStepInput(png.file, png.sha256, png.width, png.height,
                        StepOrigin.ImportedImage(imported.source), emptyList()), "合成步骤")
            }
        }
    }

    private fun openHomeDeletion(item: ProjectSummary) {
        compose.onAllNodesWithContentDescription("${item.title}，项目更多操作")[0]
            .performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("删除本机项目").assertIsEnabled().performClick()
        compose.onNodeWithText("删除本机项目？").assertIsDisplayed()
        compose.onNodeWithText("项目 ID：${item.id}").performScrollTo().assertIsDisplayed()
    }
    private fun readProject(id: String) = ProjectStore.withTemporary(app) { it.readProject(id) }
    private fun assetHashes(id: String) = File(app.noBackupFilesDir, "project-assets/$id").walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(app.noBackupFilesDir).path to ViewerPackageCodec.sha256(it) }
    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
    private fun awaitIdle() {
        val provider = ViewModelProvider(compose.activity)
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !provider[MediaWorkspace::class.java].state.value.busy &&
                !provider[ReleaseWorkspace::class.java].state.value.busy
        }
        compose.waitForIdle()
    }
    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun resetFactory() = ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
        .apply { isAccessible = true }.set(null, null)
}
