package com.tapscene.ui.shell

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.clickplan.ClickChainActionEditor
import com.tapscene.clickplan.ClickChainActionRow
import com.tapscene.clickplan.ClickChainFrameEditor
import com.tapscene.clickplan.ClickChainFrameRow
import com.tapscene.clickplan.ClickChainReviewUiState
import com.tapscene.clickplan.ClickChainStageChoice
import com.tapscene.clickplan.ClickChainStageRow
import com.tapscene.data.HostSqlite
import com.tapscene.media.OpaqueMask
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

/** Production Compose navigation with synthetic rows; no recording, decoding or review persistence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClickChainReviewNavigationHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() { resetFactory(); HostSqlite.configure() }
        override fun after() { resetFactory() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun frameEditorReturnsPreserveMiddlePositionAndDifferentRunStartsAtTop() {
        exerciseReturns(framesOnly = true, button = "复核画面", editorTitle = "复核画面")
    }

    @Test fun actionEditorReturnsPreserveMiddlePositionAndDifferentRunStartsAtTop() {
        exerciseReturns(framesOnly = false, button = "查看点击区域", editorTitle = "确认点击区域")
    }

    private fun exerciseReturns(framesOnly: Boolean, button: String, editorTitle: String) {
        val state = mutableStateOf(fixture("run-a", framesOnly))
        var creates = 0
        var opens = 0
        val callbacks = ClickChainReviewCallbacks(
            onReviewFrame = { key ->
                opens++
                state.value = state.value.copy(frameEditor = ClickChainFrameEditor(
                    key, "合成画面", null, emptyList(), null, null, false))
            },
            onReviewAction = { id ->
                opens++
                state.value = state.value.copy(actionEditor = ClickChainActionEditor(
                    id, "合成点击", OpaqueMask(.1f, .1f, .3f, .3f), null, null,
                    "合成后继", .2f to .2f, false, 1))
            },
            onCloseEditor = { state.value = state.value.copy(frameEditor = null, actionEditor = null) },
            onCreate = { creates++ },
        )
        compose.runOnUiThread {
            compose.activity.setContent { TapSceneTheme { ClickChainReviewContent(state.value, callbacks) } }
        }
        compose.waitForIdle()
        workbench().performScrollToIndex(16)
        workbench().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 29f) }
        compose.waitForIdle()
        val before = scrollPosition()
        assertTrue("The test must start in the middle of the route", before > 10f)
        assertTrue("The starting item must include a pixel offset", before != before.toInt().toFloat())

        repeat(2) {
            val buttons = compose.onAllNodesWithText(button)
            val visible = (0 until buttons.fetchSemanticsNodes().size).first { buttons[it].isDisplayed() }
            buttons[visible].assertIsEnabled().performClick()
            compose.onNodeWithText(editorTitle).assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.waitForIdle()
            // No test-side scroll on return: index and fractional item offset must survive.
            assertEquals(before, scrollPosition(), 0f)
            assertEquals(it + 1, opens)
            assertEquals(0, creates)
        }

        // The same Content remains mounted while run identity changes.
        compose.runOnIdle { state.value = fixture("run-b", framesOnly) }
        compose.waitForIdle()
        assertEquals(0f, scrollPosition(), 0f)
        compose.onNodeWithText("合成运行 run-b").assertIsDisplayed()
        assertEquals(2, opens)
        assertEquals(0, creates)
    }

    private fun fixture(runId: String, framesOnly: Boolean): ClickChainReviewUiState {
        val frames = List(25) { index -> ClickChainFrameRow("$runId:$index", "合成画面 $index", !framesOnly) }
        return ClickChainReviewUiState(runId = runId, runLabel = "合成运行 $runId", totalActions = 24,
            firstAction = "1", lastAction = "24", framesOnly = framesOnly,
            frames = frames, frameCount = frames.size,
            stages = frames.mapIndexed { index, frame -> ClickChainStageRow(index, frame.title,
                listOf(ClickChainStageChoice(frame.key, "代表画面")), frame.key, true) },
            actions = List(24) { index -> ClickChainActionRow("$runId:action:$index", index + 1,
                "手势已完成", frames[index].title, frames[index + 1].title, true, true) })
    }

    private fun workbench() = compose.onNode(hasScrollToIndexAction())
    private fun scrollPosition(): Float = workbench().fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun resetFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
