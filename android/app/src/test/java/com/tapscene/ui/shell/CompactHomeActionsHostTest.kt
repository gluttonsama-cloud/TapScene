package com.tapscene.ui.shell

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectSummary
import com.tapscene.ui.ProjectUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Production home components and synthetic callbacks, not Store navigation or a device IME. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w640dp-h320dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CompactHomeActionsHostTest {
    private var previousRequestedSize: String? = null

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            previousRequestedSize = System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        }

        override fun after() {
            resetApplicationViewModelFactory()
            val key = "robolectric.windowManager.useRequestedSize"
            previousRequestedSize?.let { System.setProperty(key, it) } ?: System.clearProperty(key)
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun smallHomeShowsCompleteFirstProjectBeforeAnyScrolling() {
        val project = homeProject()
        val state = mutableStateOf(ProjectUiState(projects = listOf(project)))
        val calls = mutableListOf<String>()
        setHome(state, calls)
        compose.onNodeWithTag("compact-home-surface").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(568.dp)
        assertUsableViewport()

        // A title peeking above navigation is not an accessible project row. These assertions
        // run before any scroll, and compare the full clickable row and menu to the real viewport.
        val row = button(project.title)
        val more = compose.onNodeWithContentDescription("${project.title}，项目更多操作")
        assertTouchArea(row, homeViewport(), fullyVisible = true)
        assertTouchArea(more, homeViewport(), fullyVisible = true)
        compose.onNodeWithText("搜索项目").assertTextContains("报名")
        compose.onNodeWithText("5 个步骤").assertIsDisplayed()
        compose.onNodeWithText("5 个步骤 · 修订 3").assertDoesNotExist()
        compose.onNodeWithText("制作第一份可点击演示").assertDoesNotExist()
        touch(row, homeViewport(), fullyVisible = true)
        touch(more, homeViewport(), fullyVisible = true)
        compose.onNodeWithText("项目文字").performClick()
        assertEquals(listOf("open:${project.id}", "rename:${project.id}"), calls)
        println("HOST_HOME_FIRST_PROJECT 320x568/font1.3/full-row-and-menu-before-scroll PASS; device NOT_RUN")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun retainedHomeProjectsStayDisabledDuringBusyOrFailedReads() {
        val project = homeProject()
        val state = mutableStateOf(ProjectUiState(projects = listOf(project)))
        val calls = mutableListOf<String>()
        setHome(state, calls)
        val row = button(project.title)
        val more = compose.onNodeWithContentDescription("${project.title}，项目更多操作")
        for ((busy, failed) in listOf(true to false, false to true, true to true)) {
            compose.runOnIdle { state.value = state.value.copy(busy = busy, loadFailed = failed) }
            row.assertIsDisplayed().assertIsNotEnabled().performClick()
            more.assertIsDisplayed().assertIsNotEnabled().performClick()
            compose.onNodeWithText("制作第一份可点击演示").assertDoesNotExist()
            compose.onNodeWithText("搜索项目").assertTextContains("报名")
            assertEquals(emptyList<String>(), calls)
        }
        compose.runOnIdle { state.value = state.value.copy(busy = false, loadFailed = false) }
        touch(more, homeViewport(), fullyVisible = true)
        // An already-open menu must follow a later failure too, not only its opener.
        compose.runOnIdle { state.value = state.value.copy(loadFailed = true) }
        listOf("项目文字", "复制项目", "删除本机项目").forEach { label ->
            compose.onNodeWithText(label).assertIsNotEnabled().performClick()
        }
        assertEquals(emptyList<String>(), calls)
        compose.runOnIdle { state.value = state.value.copy(loadFailed = false) }
        compose.onNodeWithText("项目文字").assertIsEnabled().performClick()
        assertEquals(listOf("rename:${project.id}"), calls)

        // Search misses are not a fresh installation, even when no project row is visible.
        compose.onNodeWithText("搜索项目").performTextReplacement("不存在的合成项目")
        homeList().performScrollToNode(hasText("没有找到匹配项目"))
        compose.onNodeWithText("没有找到匹配项目").assertIsDisplayed()
        compose.onNodeWithText("制作第一份可点击演示").assertDoesNotExist()
        assertEquals(listOf(project), state.value.projects)
    }

    @Test fun loadingIntoHealthyEmptyHomeKeepsIntroductionAtTheTop() {
        val state = mutableStateOf(ProjectUiState(busy = true))
        val calls = mutableListOf<String>()
        setHome(state, calls, fontScale = 1.5f, query = "")
        compose.onNodeWithText("制作第一份可点击演示").assertDoesNotExist()
        compose.runOnIdle { state.value = ProjectUiState() }
        compose.waitForIdle()

        // The real workspace first publishes busy=true. A new keyed item inserted before the
        // existing first item can be kept above the viewport by LazyColumn's anchor retention.
        // Do not scroll the introduction back into view and then call that a first-use pass.
        val introduction = compose.onNodeWithText("制作第一份可点击演示").assertIsDisplayed()
        val bounds = introduction.getUnclippedBoundsInRoot()
        val viewport = homeViewport()
        with(compose.density) {
            assertTrue("Introduction must start inside the viewport after loading", bounds.top.toPx() >= viewport.top - 1f)
            assertTrue("Introduction title must be fully visible after loading", bounds.bottom.toPx() <= viewport.bottom + 1f)
        }
        assertEquals(emptyList<String>(), calls)
        homeList().performScrollToNode(hasText("悬浮编排") and hasClickAction())
        touch(button("悬浮编排"), homeViewport(), fullyVisible = true)
        assertEquals(listOf("plan"), calls)
    }

    @Test fun landscapeLargeTextKeepsSearchProjectsAndCreationTouchableWhenContentHeightShrinks() {
        val availableHeight = mutableStateOf(320.dp)
        val calls = mutableListOf<String>()
        val projects = List(16) { index ->
            ProjectSummary("compact-project-$index", "${if (index % 4 == 0) "地址" else "报名"}演示 $index",
                "合成项目目标 $index", 1, 120_000L, index + 1, null)
        }
        val target = projects[11]
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    TapSceneTheme {
                        Box(Modifier.fillMaxSize().testTag("compact-home-window")) {
                            Box(Modifier.fillMaxWidth().height(availableHeight.value).testTag("compact-home-surface")) {
                                ProjectHomeFrame(
                                    onRecord = { calls += "record" },
                                    onImportVideo = { calls += "video" },
                                    onSettings = { calls += "settings" },
                                    onLibrary = { calls += "library" },
                                    onImportScreenshot = { calls += "screenshot" },
                                    onImportAi = { calls += "ai" },
                                    onClickPlan = { calls += "plan" },
                                ) { creationActions ->
                                    ProjectHomeContent(
                                        state = ProjectUiState(projects = projects),
                                        onOpenProject = { calls += "open:$it" },
                                        onRenameProject = { calls += "rename:${it.id}" },
                                        onDeleteProject = { calls += "delete:${it.id}" },
                                        onCopyProject = { calls += "copy:$it" },
                                        creationActions = creationActions,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("compact-home-window").assertWidthIsEqualTo(640.dp).assertHeightIsEqualTo(320.dp)
        compose.onNodeWithTag("compact-home-surface").assertHeightIsEqualTo(320.dp)
        val fullChrome = chromeBounds()
        assertUsableViewport()

        homeList().performScrollToNode(hasSetTextAction())
        val search = compose.onNode(hasSetTextAction())
        // Check the complete field, including its editable area, rather than a visible label.
        touch(search, homeViewport(), fullyVisible = true)
        search.assertIsFocused().performTextReplacement("报名")
        search.assertTextContains("报名")

        // Explicitly synthetic IME-like occlusion: reduce available content by 96 dp while
        // the search has focus. The actual host window stays 640 x 320; no device keyboard claim.
        compose.runOnIdle { availableHeight.value = 224.dp }
        compose.waitForIdle()
        compose.onNodeWithTag("compact-home-window").assertHeightIsEqualTo(320.dp)
        compose.onNodeWithTag("compact-home-surface").assertHeightIsEqualTo(224.dp)
        val compactChrome = chromeBounds()
        assertEquals(fullChrome.first, compactChrome.first)
        assertEquals(with(compose.density) { 96.dp.toPx() }, fullChrome.second.top - compactChrome.second.top, 1f)
        assertUsableViewport()
        homeList().performScrollToNode(hasSetTextAction())
        assertTouchArea(search, homeViewport(), fullyVisible = true)
        search.assertIsFocused()
        touch(button("清除"), homeViewport(), fullyVisible = true)
        search.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("清除").assertDoesNotExist()
        search.performTextReplacement("报名")
        search.assertTextContains("报名")

        homeList().performScrollToNode(hasText(target.title) and hasClickAction())
        touch(button(target.title), homeViewport())
        assertEquals(listOf("open:${target.id}"), calls)
        assertEquals(compactChrome, chromeBounds())

        // The real row menu must dispatch this row's identity, even after filtering and scrolling.
        listOf("项目文字" to "rename", "复制项目" to "copy", "删除本机项目" to "delete").forEach { (label, action) ->
            val more = hasContentDescription("${target.title}，项目更多操作")
            homeList().performScrollToNode(more)
            touch(compose.onNode(more), homeViewport(), fullyVisible = true)
            val menuItem = button(label)
            menuItem.performScrollTo()
            touch(menuItem, rootBounds(menuItem), fullyVisible = true)
            assertEquals("$action:${target.id}", calls.last())
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        assertEquals(compactChrome, chromeBounds())

        homeList().performScrollToIndex(0)
        touch(button("悬浮编排"), homeViewport(), fullyVisible = true)
        assertEquals("plan", calls.last())
        listOf("手动录屏" to "record", "导入录屏" to "video", "导入截图" to "screenshot", "导入 AI 包" to "ai")
            .forEach { (label, action) ->
                homeList().performScrollToNode(hasText("其他创建方式") and hasClickAction())
                touch(button("其他创建方式"), homeViewport(), fullyVisible = true)
                // A short window may start the production sheet half-expanded. Use its
                // exposed accessibility expansion before scrolling to the actual option.
                val expand = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
                if (expand.fetchSemanticsNodes().isNotEmpty()) {
                    expand.onFirst().performSemanticsAction(SemanticsActions.Expand) { it() }
                    compose.waitForIdle()
                }
                val option = button(label)
                option.performScrollTo()
                touch(option, rootBounds(option), fullyVisible = true)
                assertEquals(action, calls.last())
                compose.onNodeWithText(label).assertDoesNotExist()
            }
        assertEquals(compactChrome, chromeBounds())
        touch(button("演示库"), surfaceBounds(), fullyVisible = true)
        assertEquals("library", calls.last())

        // Restoring available height must leave both pinned bars and the top action usable.
        compose.runOnIdle { availableHeight.value = 320.dp }
        compose.waitForIdle()
        assertUsableViewport()
        assertEquals(fullChrome, chromeBounds())
        homeList().performScrollToIndex(0)
        touch(button("悬浮编排"), homeViewport(), fullyVisible = true)
        touch(button("演示库"), surfaceBounds(), fullyVisible = true)
        assertEquals(listOf("open:${target.id}", "rename:${target.id}", "copy:${target.id}", "delete:${target.id}",
            "plan", "record", "video", "screenshot", "ai", "library", "plan", "library"), calls)
        println("HOST_COMPACT_HOME 640x320/font1.5/96dp-synthetic-height-loss/search-clear/project-menu/creation/library PASS; device-IME/navigation NOT_RUN")
    }

    private fun homeProject() = ProjectSummary("home-registration", "报名流程", "核对场次和参加者后确认提交",
        3, 120_000L, 5, null)

    private fun setHome(state: MutableState<ProjectUiState>, calls: MutableList<String>, fontScale: Float = 1.3f,
        query: String = "报名") {
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    TapSceneTheme {
                        val home = rememberProjectHomeState()
                        LaunchedEffect(home, query) { home.query = query }
                        Box(Modifier.fillMaxSize().testTag("compact-home-window")) {
                            Box(Modifier.fillMaxSize().testTag("compact-home-surface")) {
                                ProjectHomeFrame(
                                    onRecord = { calls += "record" }, onImportVideo = { calls += "video" },
                                    onSettings = { calls += "settings" }, onLibrary = { calls += "library" },
                                    onImportScreenshot = { calls += "screenshot" }, onImportAi = { calls += "ai" },
                                    onClickPlan = { calls += "plan" },
                                ) { creationActions ->
                                    ProjectHomeContent(state.value, { calls += "open:$it" },
                                        { calls += "rename:${it.id}" }, { calls += "delete:${it.id}" },
                                        onCopyProject = { calls += "copy:$it" }, homeState = home,
                                        creationActions = creationActions)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun homeList() = compose.onNode(hasScrollToIndexAction())
    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())
    private fun surfaceBounds() = compose.onNodeWithTag("compact-home-surface").fetchSemanticsNode().boundsInRoot
    private fun homeViewport() = homeList().fetchSemanticsNode().boundsInRoot.intersect(surfaceBounds())

    private fun chromeBounds(): Pair<Rect, Rect> {
        val settings = button("设置").fetchSemanticsNode().boundsInRoot
        val library = button("演示库").fetchSemanticsNode().boundsInRoot
        assertEquals("Bottom navigation must remain pinned to the available surface", surfaceBounds().bottom, library.bottom, 1f)
        return settings to library
    }

    private fun assertUsableViewport() {
        val viewport = homeViewport()
        val minimum = with(compose.density) { 48.dp.toPx() }
        assertTrue("Home needs a real 48 dp scrollable touch viewport, actual=$viewport",
            viewport.width >= minimum - 1f && viewport.height >= minimum - 1f)
        val chrome = chromeBounds()
        assertTrue("Scrollable body must not overlap fixed chrome", viewport.top >= chrome.first.bottom && viewport.bottom <= chrome.second.top)
    }

    private fun rootBounds(node: SemanticsNodeInteraction): Rect =
        generateSequence(node.fetchSemanticsNode()) { it.parent }.last().boundsInRoot

    private fun assertTouchArea(node: SemanticsNodeInteraction, viewport: Rect, fullyVisible: Boolean): Rect {
        node.assertIsEnabled()
        val measured = node.fetchSemanticsNode().boundsInRoot
        val visible = measured.intersect(viewport)
        val minimum = with(compose.density) { 48.dp.toPx() }
        assertTrue("A displayed sliver is not a usable touch target: measured=$measured, viewport=$viewport, visible=$visible",
            visible.width >= minimum - 1f && visible.height >= minimum - 1f)
        if (fullyVisible) {
            val unclipped = node.getUnclippedBoundsInRoot()
            with(compose.density) {
                assertEquals("Control is clipped on the left", unclipped.left.toPx(), visible.left, 1f)
                assertEquals("Control is clipped at the top", unclipped.top.toPx(), visible.top, 1f)
                assertEquals("Control is clipped on the right", unclipped.right.toPx(), visible.right, 1f)
                assertEquals("Control is clipped at the bottom", unclipped.bottom.toPx(), visible.bottom, 1f)
            }
        }
        return visible
    }

    private fun touch(node: SemanticsNodeInteraction, viewport: Rect, fullyVisible: Boolean = false) {
        val visible = assertTouchArea(node, viewport, fullyVisible)
        val measured = node.fetchSemanticsNode().boundsInRoot
        // Inject a real pointer inside the verified visible rectangle, not a semantics OnClick.
        node.performTouchInput { click(Offset(visible.center.x - measured.left, visible.center.y - measured.top)) }
        compose.waitForIdle()
    }

    private fun resetApplicationViewModelFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
