package com.tapscene.ui.shell

import android.app.Application
import androidx.activity.ComponentDialog
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.tapscene.BuildConfig
import com.tapscene.MainActivity
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.ImportedSource
import com.tapscene.media.SourceMetadata
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real MainActivity/Compose/Store with synthetic source bytes; recording/decoding/device NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectStorageNavigationHostTest {
    private var previousRequestedSize: String? = null

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            // MainActivity must use its real Application root, not HostProjectFixture's
            // separate Application. Every file below belongs to Robolectric's sandbox.
            val root = RuntimeEnvironment.getApplication().noBackupFilesDir.canonicalFile
            val sandbox = RuntimeEnvironment.getTempDirectory().basePath.toRealPath()
            check(root.toPath().startsWith(sandbox) && root.toPath() != sandbox)
            root.listFiles().orEmpty().forEach { child ->
                check(child.canonicalFile.toPath().startsWith(root.toPath()))
                check(child.deleteRecursively())
            }
            HostFileSyncShadow.begin(root)
            previousRequestedSize = System.setProperty("robolectric.windowManager.useRequestedSize", "true")
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

    @Test fun projectMenuOnlyOffersHostingInTheExplicitDevelopmentVariant() = checkProjectMenu()

    @Test
    @Config(qualifiers = "w740dp-h360dp-xhdpi")
    fun landscapeProjectMenuKeepsEveryLocalActionReachable() = checkProjectMenu()

    private fun checkProjectMenu() {
        val host = host()
        val project = runBlocking(Dispatchers.IO) {
            ProjectStore.withTemporary(host.app) { it.createProject("本机项目菜单", "只显示当前版本可用的操作") }
        }
        reload(host)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(project.project.title))
        compose.onNodeWithText(project.project.title).performClick()
        awaitIdle(host)
        assertEquals(project.project.id, host.projects.state.value.project?.project?.id)
        compose.onNodeWithText("更多").assertIsEnabled().performClick()
        val inMenu = hasAnyAncestor(isDialog())
        val closeBounds = compose.onNodeWithText("关闭").fetchSemanticsNode().boundsInRoot
        fun checkReachable(label: String) {
            val row = compose.onNode(hasText(label) and inMenu)
            row.performScrollTo().assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            assertTrue("$label must retain a complete 48 dp visible touch target",
                row.fetchSemanticsNode().boundsInRoot.height >= with(compose.density) { 48.dp.toPx() })
            assertEquals("Closing stays outside the scrolling menu", closeBounds,
                compose.onNodeWithText("关闭").fetchSemanticsNode().boundsInRoot)
        }
        listOf("项目文字", "复制项目", "动画计划", "任务详情", "删除项目").forEach {
            checkReachable(it)
        }
        val hosting = compose.onNode(hasText("托管版本") and inMenu)
        if (BuildConfig.HOSTED_ENABLED) checkReachable("托管版本")
        else hosting.assertDoesNotExist()
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("更多").performClick()
        if (BuildConfig.HOSTED_ENABLED) checkReachable("托管版本") else hosting.assertDoesNotExist()
        // A modal owns its own Back dispatcher; dispatching to the Activity would bypass it.
        compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
            assertTrue(dialog.isShowing)
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("关闭").assertDoesNotExist()
        compose.onNodeWithText("分镜").assertIsDisplayed()
        assertEquals(ProjectRoute.STEPS, host.projects.state.value.route)
        assertEquals(project, ProjectStore.withTemporary(host.app) { it.readProject(project.project.id) })
        println("HOST_PROJECT_MENU hosted=${BuildConfig.HOSTED_ENABLED}; no hosting connection requested")
    }

    @Test fun sameTitleProjectsOpenTheirOwnSourcesAndLateCallbacksDoNotReopenOrStackNavigation() {
        val host = host()
        val store = ProjectStore(host.app)
        val a = runBlocking(Dispatchers.IO) { store.createProject("同名素材项目") }
        val b = runBlocking(Dispatchers.IO) { store.createProject("同名素材项目") }
        val empty = runBlocking(Dispatchers.IO) { store.createProject("没有录屏的项目") }
        val sourceA = source(host.app, a.project.id, "A", 1_024)
        val sourceB = source(host.app, b.project.id, "B", 2_048)
        // A workspace without a project models retained data, without deleting any project.
        val orphanId = UUID.randomUUID().toString()
        val orphan = source(host.app, orphanId, "已删除项目", 3_072)
        val legacy = source(host.app, null, "旧工作台", 4_096)
        val savedProjects = listOf(a, b, empty)
        val savedSources = listOf(sourceA, sourceB, orphan, legacy)
        val savedFiles = savedSources.flatMap { listOf(it.file, it.record) }
            .associateWith { it.readBytes() }
        assertNotEquals(a.project.id, b.project.id)
        assertNotEquals(sourceA.source.sourceId, sourceB.source.sourceId)
        reload(host)

        openSettings()
        waitFor("4 项")
        compose.onNodeWithText("10.0 KiB").assertIsDisplayed()
        openManagement(host)
        compose.onNodeWithText("项目中的原录屏").assertExists()
        compose.onNodeWithText(empty.project.title).assertDoesNotExist()
        compose.onAllNodesWithText(a.project.title).assertCountEquals(2)
        compose.onNodeWithText("其他保留原录屏").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(orphan.source.displayName).performScrollTo().assertIsEnabled()
        compose.onNodeWithText("原素材工作台").performScrollTo().assertIsEnabled()
        val rowA = projectRow(a, "1.0 KiB")
        val lateA = clickAction(rowA)
        rowA.performClick()
        assertProjectSources(host, a, sourceA, sourceB)
        val openedA = host.projects.state.value.project
        compose.runOnIdle { lateA(); lateA() }
        assertProjectSources(host, a, sourceA, sourceB)
        assertSame(openedA, host.projects.state.value.project)
        backToHome(host, toolbar = true)
        compose.runOnIdle { lateA() }
        assertHome(host)

        // Re-enter settings from the library; B must clear that destination on the way home.
        compose.onNodeWithText("演示库").performClick()
        awaitIdle(host)
        waitFor("离线观看")
        openSettings()
        waitFor("4 项")
        compose.onNodeWithText("10.0 KiB").assertIsDisplayed()
        openManagement(host)
        val rowB = projectRow(b, "2.0 KiB")
        val lateB = clickAction(rowB)
        rowB.performClick()
        assertProjectSources(host, b, sourceB, sourceA)
        val openedB = host.projects.state.value.project
        compose.runOnIdle { lateA(); lateB() }
        assertProjectSources(host, b, sourceB, sourceA)
        assertSame(openedB, host.projects.state.value.project)
        backToHome(host, toolbar = false)
        compose.runOnIdle { lateA(); lateB() }
        assertHome(host)

        savedProjects.forEach { expected ->
            assertEquals(expected, runBlocking(Dispatchers.IO) { store.readProject(expected.project.id) })
        }
        assertNull(runBlocking(Dispatchers.IO) { store.readProject(orphanId) })
        savedSources.forEach { expected ->
            assertEquals(expected.source, WorkspaceStore(host.app, expected.projectId).read().single().source)
        }
        savedFiles.forEach { (file, bytes) -> assertArrayEquals(file.path, bytes, file.readBytes()) }
        HostFileSyncShadow.assertDirectorySyncEvidence()
        println("HOST_PROJECT_STORAGE exact-id/same-title/empty/retained/late-callback/back PASS; recording/decoding/device NOT_RUN")
    }

    @Test fun unreadableUsageStillOpensOwningProjectSourcesAndExplicitReloadRecoversOriginalRecord() {
        val host = host()
        val store = ProjectStore(host.app)
        val project = runBlocking(Dispatchers.IO) { store.createProject("待恢复素材项目") }
        val saved = source(host.app, project.project.id, "待读取", 1_024)
        val recordBytes = saved.record.readBytes()
        val sourceBytes = saved.file.readBytes()
        // Corrupt only the test-owned metadata; the synthetic raw file stays untouched.
        saved.record.writeText("{\"version\":999}")
        try {
            reload(host)
            openSettings()
            openManagement(host)
            val subtitle = "占用暂时无法读取\n项目 ${project.project.id.take(12)}"
            waitFor(subtitle)
            compose.onNode(hasText(project.project.title) and hasText(subtitle))
                .performScrollTo().assertIsEnabled().performClick()
            awaitIdle(host)
            assertEquals(project.project.id, host.projects.state.value.project?.project?.id)
            assertEquals(ProjectRoute.STEPS, host.projects.state.value.route)
            assertEquals(project.project.id, host.media.projectId)
            compose.onNodeWithText("素材").assertIsSelected()
            compose.onNodeWithText("素材暂时无法读取。").assertIsDisplayed()
            compose.onNodeWithText("暂时无法读取素材").performScrollTo().assertIsDisplayed()
            assertTrue(host.media.state.value.loadFailed)
            assertTrue(host.media.state.value.drafts.isEmpty())
            assertArrayEquals(sourceBytes, saved.file.readBytes())

            saved.record.writeBytes(recordBytes)
            compose.onNodeWithText("重新读取").assertIsEnabled().performClick()
            awaitIdle(host)
            assertEquals(listOf(saved.source), host.media.state.value.drafts.map { it.source })
            assertFalse(host.media.state.value.loadFailed)
            compose.onNodeWithText(saved.source.displayName).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("素材").assertIsSelected()
            compose.onNodeWithText("重新读取").assertDoesNotExist()
            backToHome(host, toolbar = false)
            assertEquals(project, runBlocking(Dispatchers.IO) { store.readProject(project.project.id) })
            assertArrayEquals(recordBytes, saved.record.readBytes())
            assertArrayEquals(sourceBytes, saved.file.readBytes())
        } finally {
            saved.record.writeBytes(recordBytes)
        }
        println("HOST_PROJECT_STORAGE unknown-usage/owning-project/explicit-reload PASS; recording/decoding/device NOT_RUN")
    }

    private data class Host(val app: Application, val projects: ProjectWorkspace,
        val media: MediaWorkspace, val releases: ReleaseWorkspace)

    private fun host(): Host {
        waitFor("设置")
        val provider = ViewModelProvider(compose.activity)
        return Host(RuntimeEnvironment.getApplication(), provider[ProjectWorkspace::class.java],
            provider[MediaWorkspace::class.java], provider[ReleaseWorkspace::class.java]).also { host ->
            assertSame(host.app, host.projects.getApplication<Application>())
            assertSame(host.app, host.media.getApplication<Application>())
            awaitIdle(host)
        }
    }

    private fun reload(host: Host) {
        compose.runOnIdle { host.projects.reload() }
        awaitIdle(host)
    }

    private fun openSettings() {
        compose.onNodeWithText("设置").assertIsEnabled().performClick()
        waitFor("设置与存储")
    }

    private fun openManagement(host: Host) {
        compose.onNodeWithText("管理本机保留素材").performScrollTo().assertIsEnabled().performClick()
        awaitIdle(host)
        waitFor("本机保留素材")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("正在读取项目素材占用。").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun projectRow(project: ProjectSnapshot, size: String): SemanticsNodeInteraction {
        val subtitle = "1 段原录屏 · $size\n项目 ${project.project.id.take(12)}"
        waitFor(subtitle)
        return compose.onNode(hasText(project.project.title) and hasText(subtitle))
            .performScrollTo().assertIsEnabled()
    }

    private fun clickAction(row: SemanticsNodeInteraction): () -> Boolean =
        checkNotNull(row.fetchSemanticsNode().config[SemanticsActions.OnClick].action)

    private fun assertProjectSources(host: Host, project: ProjectSnapshot, source: SavedSource, other: SavedSource) {
        awaitIdle(host)
        assertEquals(project.project.id, host.projects.state.value.project?.project?.id)
        assertEquals(ProjectRoute.STEPS, host.projects.state.value.route)
        assertEquals(project.project.id, host.media.projectId)
        assertEquals(listOf(source.source), host.media.state.value.drafts.map { it.source })
        assertEquals(source.source.sourceId, host.media.state.value.selectedId)
        assertNull(host.media.state.value.frame)
        assertNull(host.media.state.value.candidate)
        compose.onNodeWithText("素材").assertIsSelected()
        compose.onNodeWithText("本机原录屏").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(source.source.displayName).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(other.source.displayName).assertDoesNotExist()
        compose.onNodeWithText("本机保留素材").assertDoesNotExist()
    }

    private fun backToHome(host: Host, toolbar: Boolean) {
        if (toolbar) compose.onNodeWithContentDescription("返回").performClick()
        else compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertHome(host)
    }

    private fun assertHome(host: Host) {
        awaitIdle(host)
        waitFor("设置")
        assertEquals(ProjectRoute.PROJECTS, host.projects.state.value.route)
        assertNull(host.projects.state.value.project)
        compose.onNodeWithText("项目").assertIsSelected()
        compose.onNodeWithText("离线观看").assertDoesNotExist()
        compose.onNodeWithText("本机保留素材").assertDoesNotExist()
        compose.onNodeWithText("设置与存储").assertDoesNotExist()
        compose.onNodeWithText("素材").assertDoesNotExist()
    }

    private fun awaitIdle(host: Host) {
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            !host.projects.state.value.busy && !host.media.state.value.busy && !host.releases.state.value.busy
        }
        compose.waitForIdle()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private data class SavedSource(val projectId: String?, val source: ImportedSource, val file: File, val record: File)

    private fun source(app: Application, projectId: String?, name: String, byteCount: Int): SavedSource {
        val id = UUID.randomUUID().toString()
        val relativePath = "sources/$id.mp4"
        val file = File(app.noBackupFilesDir, relativePath)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        check(!file.exists())
        file.writeBytes(ByteArray(byteCount) { (it % 251).toByte() })
        val source = ImportedSource(id, relativePath, "合成原录屏 $name.mp4",
            SourceMetadata("video/avc", byteCount.toLong(), ViewerPackageCodec.sha256(file), 32, 48, 0, 1_000_000))
        runBlocking(Dispatchers.IO) { WorkspaceStore(app, projectId).append(source) }
        val record = File(app.noBackupFilesDir,
            if (projectId == null) "media-workspace.json" else "project-media-$projectId.json")
        return SavedSource(projectId, source, file, record)
    }

    private fun resetApplicationViewModelFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
