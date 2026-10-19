package com.tapscene.ui.shell

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import kotlinx.coroutines.Dispatchers
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

/** Real MainActivity, home navigation and SQLite. No device, rotation or process-death claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectHomeReturnContextHostTest {
    private var previousRequestedSize: String? = null

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            // Only this test's synthetic files inside Robolectric's own private sandbox.
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

    @Test fun openingFilteredProjectAndReturningKeepsSearchAndActualRowPosition() {
        val home = savedHome()
        val anchor = searchAndScroll(home)
        compose.onNodeWithText(anchor.project.project.title).assertIsDisplayed().performClick()
        awaitIdle(home)
        assertEquals(ProjectRoute.STEPS, home.projects.state.value.route)
        assertEquals(anchor.project.project.id, home.projects.state.value.project?.project?.id)
        compose.onNodeWithText("步骤").assertIsSelected()
        compose.onNodeWithText("悬浮编排").assertDoesNotExist()

        compose.onNodeWithContentDescription("返回").assertIsEnabled().performClick()
        assertReturnedContext(home, anchor)
        assertEquals(home.saved, runBlocking(Dispatchers.IO) {
            home.store.listProjects().map { checkNotNull(home.store.readProject(it.id)) }
        })
        println("HOST_HOME_RETURN open/toolbar-back/search/actual-row-position PASS; device/rotation/process-death NOT_RUN")
    }

    @Test fun copyingFromFilteredHomeAndReturningKeepsContextAndAddsOnlyIndependentCopy() {
        val home = savedHome()
        val anchor = searchAndScroll(home)
        val source = anchor.project
        compose.onNodeWithContentDescription("${source.project.title}，项目更多操作")
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("复制项目").assertIsDisplayed().assertIsEnabled().performClick()
        awaitIdle(home)
        assertEquals(ProjectRoute.STEPS, home.projects.state.value.route)
        val copied = checkNotNull(home.projects.state.value.project)
        assertNotEquals(source.project.id, copied.project.id)
        assertEquals("${source.project.title} 副本", copied.project.title)
        assertEquals(source.project.goal, copied.project.goal)
        assertEquals(source.steps, copied.steps)
        compose.onNodeWithText(copied.project.title).assertIsDisplayed()
        compose.onNodeWithText("步骤").assertIsSelected()
        compose.onNodeWithText("已复制并打开副本，交付前请重新复核").assertIsDisplayed()
        val savedAfterCopy = runBlocking(Dispatchers.IO) {
            home.store.listProjects().map { checkNotNull(home.store.readProject(it.id)) }
        }
        assertEquals(home.saved.size + 1, savedAfterCopy.size)
        assertEquals(listOf(copied), savedAfterCopy.filter { actual ->
            home.saved.none { it.project.id == actual.project.id }
        })
        home.saved.forEach { expected ->
            assertEquals(expected, savedAfterCopy.single { it.project.id == expected.project.id })
        }

        // Exercise the Activity's real Back handler as well as the toolbar in the other test.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertReturnedContext(home, anchor)
        homeList().performScrollToNode(hasText(copied.project.title))
        compose.onNodeWithText(copied.project.title).assertIsDisplayed()
        assertControlsAbsent(home)
        assertEquals(source, runBlocking(Dispatchers.IO) { home.store.readProject(source.project.id) })
        println("HOST_HOME_COPY_RETURN menu/copy/system-back/search/actual-row-position/source-unchanged PASS; device/rotation/process-death NOT_RUN")
    }

    private data class Home(
        val projects: ProjectWorkspace,
        val media: MediaWorkspace,
        val releases: ReleaseWorkspace,
        val store: ProjectStore,
        val saved: List<ProjectSnapshot>,
    )

    private data class Anchor(val project: ProjectSnapshot, val bounds: Rect, val viewport: Rect)

    private fun savedHome(): Home {
        waitFor("悬浮编排")
        val app = RuntimeEnvironment.getApplication()
        val provider = ViewModelProvider(compose.activity)
        val store = ProjectStore(app)
        val home = Home(provider[ProjectWorkspace::class.java], provider[MediaWorkspace::class.java],
            provider[ReleaseWorkspace::class.java], store, emptyList())
        assertSame(app, home.projects.getApplication<Application>())
        awaitIdle(home)
        val saved = runBlocking(Dispatchers.IO) {
            // Enough persisted rows to put the search field and the first matches off screen.
            // These empty projects keep this navigation regression independent of media fixtures.
            repeat(24) { index -> store.createProject("回程 ${index + 1}", "合成目标 ${index + 1}") }
            repeat(3) { index -> store.createProject("对照 ${index + 1}", "不属于本次筛选") }
            store.listProjects().map { checkNotNull(store.readProject(it.id)) }
        }
        compose.runOnIdle { home.projects.reload() }
        awaitIdle(home)
        waitFor("搜索项目")
        return home.copy(saved = saved)
    }

    private fun searchAndScroll(home: Home): Anchor {
        // Establish that a real excluded project is reachable before typing the filter.
        val control = home.saved.first { !it.project.title.contains(QUERY) }
        homeList().performScrollToNode(hasText(control.project.title))
        compose.onNodeWithText(control.project.title).assertIsDisplayed()
        homeList().performScrollToIndex(0)
        compose.onNodeWithText("搜索项目").performTextReplacement(QUERY)
        compose.waitForIdle()
        compose.onNodeWithText("搜索项目").assertTextContains(QUERY)
        assertControlsAbsent(home)

        val matches = home.saved.filter { it.project.title.contains(QUERY) }
        // Search and summary precede the project rows. Then add a nonzero pixel offset;
        // checking only a restored item index would miss this part of the return context.
        homeList().performScrollToIndex(2 + 8)
        val offset = with(compose.density) { 28.dp.toPx() }
        homeList().performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, offset)
        }
        compose.waitForIdle()
        compose.onNodeWithText("搜索项目").assertIsNotDisplayed()
        val anchor = matches[9]
        val row = compose.onNodeWithText(anchor.project.title).assertIsDisplayed()
        val bounds = row.fetchSemanticsNode().boundsInRoot
        val viewport = homeList().fetchSemanticsNode().boundsInRoot
        assertTrue("The anchor must be a fully visible middle row", bounds.top > viewport.top && bounds.bottom < viewport.bottom)
        assertControlsAbsent(home)
        return Anchor(anchor, bounds, viewport)
    }

    private fun assertReturnedContext(home: Home, anchor: Anchor) {
        awaitIdle(home)
        waitFor("悬浮编排")
        assertEquals(ProjectRoute.PROJECTS, home.projects.state.value.route)
        assertNull(home.projects.state.value.project)
        compose.onNodeWithText("项目").assertIsSelected()
        // Observe the returned viewport BEFORE scrolling to inspect the search field.
        // Keep a missing anchor as null so the unfixed production code fails at the query
        // assertion first; this never scrolls a lost anchor back into place for the check.
        val returnedBounds = compose.onAllNodesWithText(anchor.project.project.title)
            .fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        val returnedViewport = homeList().fetchSemanticsNode().boundsInRoot
        homeList().performScrollToIndex(0)
        compose.onNodeWithText("搜索项目").assertIsDisplayed().assertTextContains(QUERY)
        assertControlsAbsent(home)
        assertNotNull("The same project row must already be visible immediately after returning", returnedBounds)
        val actual = checkNotNull(returnedBounds)
        assertTrue("The returned anchor must still be fully visible in its viewport",
            actual.top > returnedViewport.top && actual.bottom < returnedViewport.bottom)
        // A copy leaves a global success-message row above the home frame. Compare the
        // same project's measured position inside each actual viewport, not screen Y.
        // A new item shifting the project by a whole row still changes both offsets.
        println("HOST_HOME_VIEWPORT rowTop=${anchor.bounds.top}->${actual.top}; " +
            "viewportTop=${anchor.viewport.top}->${returnedViewport.top}")
        assertEquals("Returning must retain the original row's top offset inside the viewport",
            anchor.bounds.top - anchor.viewport.top, actual.top - returnedViewport.top, 1f)
        assertEquals("Returning must retain the original row's bottom offset inside the viewport",
            anchor.bounds.bottom - anchor.viewport.top, actual.bottom - returnedViewport.top, 1f)
    }

    private fun assertControlsAbsent(home: Home) {
        home.saved.filterNot { it.project.title.contains(QUERY) }.forEach {
            compose.onNodeWithText(it.project.title).assertDoesNotExist()
        }
    }

    private fun homeList() = compose.onNode(hasScrollToIndexAction())

    private fun awaitIdle(home: Home) {
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            !home.projects.state.value.busy && !home.media.state.value.busy && !home.releases.state.value.busy
        }
        compose.waitForIdle()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun resetApplicationViewModelFactory() {
        // Lifecycle retains this cache across Robolectric's separate Application instances.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }

    private companion object { const val QUERY = "回程" }
}
