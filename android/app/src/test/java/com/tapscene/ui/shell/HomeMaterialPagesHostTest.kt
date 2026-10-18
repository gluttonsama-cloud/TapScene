package com.tapscene.ui.shell

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tapscene.MainActivity
import com.tapscene.data.HostSqlite
import com.tapscene.data.SourceDraft
import com.tapscene.media.ImportedSource
import com.tapscene.media.SourceMetadata
import com.tapscene.ui.ProjectUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real production Compose and synthetic callbacks; no system picker, capture, or device claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeMaterialPagesHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() { HostSqlite.configure() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun source(index: Int) = SourceDraft(ImportedSource("source-$index", "unused/source-$index.mp4",
        "录屏 $index.mp4", SourceMetadata("video/avc", 2_097_152L, "0".repeat(64), 480, 840, 0, 18_000_000L)))

    @Test fun projectTabsShareHeightAndKeepLabelsSelectionAndCallbacksAtLargeFontScales() {
        val fontScale = mutableStateOf(1f)
        val selected = mutableStateOf(ProjectTab.SOURCES)
        val calls = mutableListOf<String>()
        content {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                ProjectWorkspaceFrame("报名演示", selected.value,
                    { selected.value = it; calls += it.name }, { calls += "back" },
                    { calls += "preview" }, { calls += "more" }) {}
            }
        }
        for (scale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { fontScale.value = scale }
            val bounds = ProjectTab.entries.map { tab ->
                val node = compose.onNodeWithText(tab.label)
                node.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(tab.label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                // BasicText rebuilds the semantics MultiParagraph with the incoming max width,
                // which can exceed its actual measured size. Check the lines, not paragraph width.
                check(!layout.didOverflowHeight && layout.getLineEnd(layout.lineCount - 1) == tab.label.length &&
                    (0 until layout.lineCount).all { line -> !layout.isLineEllipsized(line) &&
                        layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f }) {
                    "${tab.label} clipped at fontScale=$scale: size=${layout.size}, lines=${layout.lineCount}"
                }
                node.fetchSemanticsNode().boundsInRoot
            }
            bounds.forEach {
                assertEquals(bounds.first().top, it.top, 0.5f)
                assertEquals(bounds.first().bottom, it.bottom, 0.5f)
            }
            ProjectTab.entries.forEach { tab ->
                // The added height remains part of each destination's actual touch target.
                compose.onNodeWithText(tab.label).performTouchInput { click(Offset(width / 2f, height - 2f)) }
                ProjectTab.entries.forEach { other ->
                    if (other == tab) compose.onNodeWithText(other.label).assertIsSelected()
                    else compose.onNodeWithText(other.label).assertIsNotSelected()
                }
            }
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("预览").performClick()
        compose.onNodeWithText("更多").performClick()
        assertEquals(List(3) { ProjectTab.entries.map { it.name } }.flatten() + listOf("back", "preview", "more"), calls)
    }

    @Test fun healthyHomeEmptyStateDoesNotReplaceLoadingOrFailure() {
        val state = mutableStateOf(ProjectUiState())
        content { ProjectHomeContent(state.value, {}, {}, {}) }
        compose.onNodeWithText("还没有项目").assertIsDisplayed()
        compose.onNodeWithText("创建的项目会在这里保留，方便继续编辑。").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithText("正在读取本机项目").assertIsDisplayed()
        compose.onNodeWithText("还没有项目").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(loadFailed = true) }
        compose.onNodeWithText("暂时无法读取项目").assertIsDisplayed()
        compose.onNodeWithText("还没有项目").assertDoesNotExist()
    }

    @Test fun acquisitionSheetDismissesAndKeepsEachCallbackSeparate() {
        val calls = mutableListOf<String>()
        content {
            ProjectSourcesContent(emptyList(), false, false, { calls += "record" }, { calls += "video" },
                {}, {}, {}, onImportScreenshot = { calls += "screenshot" }, onClickPlan = { calls += "plan" })
        }
        compose.onNodeWithText("还没有原录屏").assertIsDisplayed()
        compose.onNodeWithText("录屏合计上限：5 分钟 / 500 MiB").assertIsDisplayed()
        compose.onNodeWithText("还没有素材").assertDoesNotExist()
        compose.onNodeWithText("悬浮编排").performClick()
        listOf("手动录屏", "导入录屏", "导入截图").forEach { label ->
            compose.onNodeWithText("其他方式").performClick()
            compose.onNodeWithText("添加素材").assertIsDisplayed()
            compose.onNodeWithText("单段录屏最多 3 分钟、200 MiB；项目合计最多 5 分钟、500 MiB。").assertIsDisplayed()
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText("添加素材").assertDoesNotExist()
        }
        compose.onNodeWithText("其他方式").performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).onFirst()
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.onNodeWithText("添加素材").assertDoesNotExist()
        compose.onNodeWithText("其他方式").performClick()
        compose.onNodeWithText("导入截图").performClick()
        assertEquals(listOf("plan", "record", "video", "screenshot", "screenshot"), calls)
    }

    @Test fun quotaLoadFailureAndBusyKeepExistingActionGates() {
        val failed = mutableStateOf(false)
        val busy = mutableStateOf(false)
        val calls = mutableListOf<String>()
        content {
            ProjectSourcesContent(List(3, ::source), busy.value, failed.value, { calls += "record" },
                { calls += "video" }, {}, {}, {}, onImportScreenshot = { calls += "screenshot" }, onClickPlan = {})
        }
        compose.onNodeWithText("其他方式").performClick()
        compose.onNodeWithText("导入录屏").assertIsNotEnabled().performClick()
        compose.onNodeWithText("原录屏已达 3 段上限。").assertIsDisplayed()
        compose.onNodeWithText("手动录屏").assertIsEnabled()
        compose.onNodeWithText("导入截图").assertIsEnabled().performClick()
        compose.runOnIdle { failed.value = true }
        compose.onNodeWithText("悬浮编排").assertIsNotEnabled()
        compose.onNodeWithText("其他方式").assertIsEnabled().performClick()
        compose.onNodeWithText("手动录屏").assertIsNotEnabled().performClick()
        compose.onNodeWithText("导入录屏").assertIsNotEnabled()
        compose.onNodeWithText("导入截图").assertIsEnabled()
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithText("手动录屏").assertIsNotEnabled()
        compose.onNodeWithText("导入截图").assertIsNotEnabled().performClick()
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithText("导入截图").performClick()
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithText("其他方式").assertIsNotEnabled()
        assertEquals(listOf("screenshot", "screenshot"), calls)
    }

    @Test fun sourceSelectionAndGlobalCandidatesStayDistinctAcrossFailure() {
        val failed = mutableStateOf(false)
        val busy = mutableStateOf(false)
        val drafts = mutableStateOf(listOf(source(7)))
        val calls = mutableListOf<String>()
        content {
            ProjectSourcesContent(drafts.value, busy.value, failed.value, {}, {},
                { calls += "open:$it" }, { calls += "candidates" }, { calls += "analyze:$it" }, onClickPlan = {})
        }
        compose.onNodeWithText("本机原录屏").assertIsDisplayed()
        compose.onNodeWithText("整理候选").performClick()
        compose.onNodeWithText("手动校正").performClick()
        compose.onNodeWithText("打开候选步骤").performScrollTo().performClick()
        compose.runOnIdle { failed.value = true }
        compose.onNodeWithText("录屏 7.mp4").assertExists()
        compose.onNodeWithText("整理候选").assertIsNotEnabled().performClick()
        compose.onNodeWithText("手动校正").assertIsNotEnabled().performClick()
        compose.onNodeWithText("打开候选步骤").assertIsEnabled().performClick()
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithText("打开候选步骤").assertIsNotEnabled().performClick()
        compose.runOnIdle { busy.value = false; failed.value = false; drafts.value = emptyList() }
        compose.onNodeWithText("打开候选步骤").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("analyze:source-7", "open:source-7", "candidates", "candidates", "candidates"), calls)
    }

    private fun content(block: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { TapSceneTheme(content = block) } }
        compose.waitForIdle()
    }
}
