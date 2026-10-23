package com.tapscene.ui.shell

import android.graphics.Bitmap

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.clickplan.ClickChainActionEditor
import com.tapscene.clickplan.ClickChainActionRow
import com.tapscene.clickplan.ClickChainFrameEditor
import com.tapscene.clickplan.ClickChainFrameRow
import com.tapscene.clickplan.ClickChainReviewUiState
import com.tapscene.clickplan.ClickChainStageChoice
import com.tapscene.clickplan.ClickChainStageRow
import com.tapscene.clickplan.ClickChainThumbnailSource
import com.tapscene.clickplan.INITIAL_SNAPSHOT_NOTICE
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

/** Real production Compose, synthetic rows only. Does not infer privacy review from a thumbnail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClickChainWorkbenchHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() { resetFactory(); HostSqlite.configure() }
        override fun after() { resetFactory() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun framesOnlyModeRequiresOpeningSettingsAndNeverConfirmsContent() {
        val state = mutableStateOf(fixture())
        val modes = mutableListOf<Boolean>()
        var confirmations = 0
        var creates = 0
        show(state, ClickChainReviewCallbacks(onFramesOnly = { modes += it; state.value = state.value.copy(framesOnly = it) },
            onConfirmAction = { confirmations++ }, onConfirmFrame = { confirmations++ }, onCreate = { creates++ }))
        compose.onNodeWithText("按点击链").assertDoesNotExist()
        compose.onNodeWithText("仅加入画面").assertDoesNotExist()
        compose.onNodeWithTag("click_chain_settings").performClick()
        compose.onNodeWithText("按点击链").assertIsDisplayed()
        compose.onNodeWithText("仅加入画面").assertIsEnabled().performClick()
        compose.onNodeWithText("仅加入已选画面，不生成点击连线；画面仍须逐张复核。").assertIsDisplayed()
        compose.onNodeWithTag("click_chain_settings").performClick()
        compose.onNodeWithText("仅加入画面").assertDoesNotExist()
        compose.onNodeWithText("画面 0/2 · 仅加入画面").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(true), modes); assertEquals(0, confirmations); assertEquals(0, creates) }
    }

    @Test fun invalidProjectInputKeepsSettingsVisibleAndCreationBlocked() {
        val state = mutableStateOf(fixture().copy(title = ""))
        show(state)
        compose.onNodeWithText("新项目名称").assertIsDisplayed()
        compose.onNodeWithText("请填写新项目名称。").assertIsDisplayed()
        compose.onNodeWithText("仅加入画面").assertIsDisplayed()
        compose.onNodeWithTag("click_chain_settings").performClick()
        compose.onNodeWithText("新项目名称").assertIsDisplayed()
        compose.onNodeWithText("生成独立项目").assertDoesNotExist()
    }

    @Test fun initialSnapshotWarningAppearsOnceAndPendingActionStaysDisabled() {
        val state = mutableStateOf(fixture())
        val frames = mutableListOf<String>()
        var actionOpens = 0
        var creates = 0
        show(state, ClickChainReviewCallbacks(onReviewFrame = { frames += it }, onReviewAction = { actionOpens++ },
            onCreate = { creates++ }))
        compose.onAllNodesWithText(INITIAL_SNAPSHOT_NOTICE).assertCountEquals(1)
        compose.onNodeWithTag("click_chain_workbench").performScrollToKey("action:action-0")
        compose.onNodeWithText("核对点击区域").assertIsNotEnabled()
        compose.onNodeWithText("先复核前后画面").assertIsDisplayed()
        compose.onNodeWithText("继续复核画面").performClick()
        compose.runOnIdle {
            assertEquals(listOf("frame-0"), frames)
            assertEquals(0, actionOpens)
            assertEquals(0, creates)
        }
    }

    @Test fun reviewedRowsBecomeCompactAndDetailsExpansionNeverChangesReview() {
        val state = mutableStateOf(fixture())
        var writes = 0
        show(state, ClickChainReviewCallbacks(onConfirmFrame = { writes++ }, onConfirmAction = { writes++ }, onCreate = { writes++ }))
        val pendingHeight = compose.onNodeWithTag("click_chain_stage_0").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { state.value = fixture(complete = true) }
        val completeHeight = compose.onNodeWithTag("click_chain_stage_0").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Completed frame rows must become visibly smaller", completeHeight < pendingHeight)
        compose.onNodeWithText(INITIAL_SNAPSHOT_NOTICE).assertDoesNotExist()
        compose.onNodeWithContentDescription("展开画面 1 的详情与初始快照提示").performClick()
        compose.onAllNodesWithText(INITIAL_SNAPSHOT_NOTICE).assertCountEquals(1)
        compose.onNodeWithContentDescription("收起画面 1 的详情与初始快照提示").performClick()
        compose.onNodeWithTag("click_chain_workbench").performScrollToKey("action:action-0")
        val collapsedActionHeight = compose.onNodeWithTag("click_chain_action_action-0").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("展开动作 1 的详情").performClick()
        assertTrue("Completed action details must stay folded until requested",
            compose.onNodeWithTag("click_chain_action_action-0").fetchSemanticsNode().boundsInRoot.height > collapsedActionHeight)
        compose.onNodeWithText("手势已完成").assertIsDisplayed()
        compose.onNodeWithText("点击区域与去向已确认").assertIsDisplayed()
        compose.onNodeWithText(INITIAL_SNAPSHOT_NOTICE).assertDoesNotExist()
        compose.onNodeWithText("查看点击区域").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, writes); assertTrue(state.value.canCreate) }
    }

    @Test fun newOrDifferentRiskIsNotHiddenByCompletedFrameOrActionDetails() {
        val state = mutableStateOf(fixture(complete = true).let { ready -> ready.copy(
            frames = ready.frames.mapIndexed { index, frame -> if (index == 0) frame.copy(selectionNotice = "合成新风险：请重新核对来源。") else frame },
            actions = ready.actions.map { it.copy(detail = "前后画面或完成结果待补", enabled = false) }, canCreate = false) })
        show(state)
        compose.onNodeWithText("合成新风险：请重新核对来源。").assertIsDisplayed()
        compose.onNodeWithTag("click_chain_workbench").performScrollToKey("action:action-0")
        compose.onNodeWithText("前后画面或完成结果待补").assertIsDisplayed()
        // The selected frame has a different notice, so the action's initial-snapshot warning remains.
        compose.onNodeWithText(INITIAL_SNAPSHOT_NOTICE).assertIsDisplayed()
        compose.onNodeWithText("查看点击区域").assertIsNotEnabled()
    }

    @Test fun committedResultSeparatesOpenFromExplicitNewSegment() {
        val state = mutableStateOf(fixture(complete = true).copy(createdProjectId = "project-result", editingLocked = true,
            canCreate = false, canStartAnotherSegment = true))
        val opened = mutableListOf<String>()
        var newSegments = 0
        var oldRetries = 0
        show(state, ClickChainReviewCallbacks(onOpenProject = { opened += it }, onStartAnotherSegment = { newSegments++ },
            onCreate = { oldRetries++ }, onRetry = { oldRetries++ }))
        compose.onNodeWithText("新项目已生成").assertIsDisplayed()
        compose.onNodeWithTag("click_chain_settings").assertDoesNotExist()
        compose.onNodeWithText("生成独立项目").assertDoesNotExist()
        compose.onNodeWithText("打开新项目").assertIsEnabled().performClick()
        compose.onNodeWithText("再整理一段").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("project-result"), opened); assertEquals(1, newSegments); assertEquals(0, oldRetries) }
    }

    @Test fun deletedResultOffersNewSegmentButNeverRecreatesOldOperation() {
        val state = mutableStateOf(fixture(complete = true).copy(editingLocked = true, canCreate = false,
            canStartAnotherSegment = true))
        var newSegments = 0
        var oldOperations = 0
        show(state, ClickChainReviewCallbacks(onStartAnotherSegment = { newSegments++ }, onCreate = { oldOperations++ },
            onRetry = { oldOperations++ }, onOpenProject = { oldOperations++ }))
        compose.onNodeWithText("生成的项目已被删除。重试原操作不会重新创建旧项目。").assertIsDisplayed()
        compose.onNodeWithText("打开新项目").assertDoesNotExist()
        compose.onNodeWithText("重试生成同一项目").assertDoesNotExist()
        compose.onNodeWithText("再整理一段").performClick()
        compose.runOnIdle { assertEquals(1, newSegments); assertEquals(0, oldOperations) }
    }

    @Test fun busyLoadingAndUnknownOutcomesCannotStartAnotherSegment() {
        val ready = fixture(complete = true).copy(createdProjectId = "project-result", editingLocked = true,
            canCreate = false, canStartAnotherSegment = true)
        val state = mutableStateOf(ready.copy(busy = true))
        var newSegments = 0
        var retries = 0
        var cancels = 0
        var opensOrWrites = 0
        show(state, ClickChainReviewCallbacks(onStartAnotherSegment = { newSegments++ }, onRetry = { retries++ },
            onCancel = { cancels++ }, onOpenProject = { opensOrWrites++ }, onCreate = { opensOrWrites++ },
            onConfirmFrame = { opensOrWrites++ }, onConfirmAction = { opensOrWrites++ }))
        compose.onNodeWithText("再整理一段").assertDoesNotExist()
        compose.onNodeWithText("取消处理").assertIsEnabled().performClick()
        compose.runOnIdle { state.value = ready.copy(loading = true) }
        compose.onNodeWithText("再整理一段").assertDoesNotExist()
        compose.onNodeWithText("取消处理").assertIsEnabled().performClick()
        compose.runOnIdle { state.value = ready.copy(outcomeUnknown = true) }
        compose.onNodeWithTag("click_chain_result").assertDoesNotExist()
        compose.onNodeWithText("打开新项目").assertDoesNotExist()
        compose.onNodeWithText("再整理一段").assertDoesNotExist()
        compose.onNodeWithText("取消处理").assertDoesNotExist()
        compose.onNodeWithText("核对生成结果").performClick()
        compose.runOnIdle {
            assertEquals(0, newSegments); assertEquals(1, retries)
            assertEquals(2, cancels); assertEquals(0, opensOrWrites)
        }
    }

    @Test fun bothBusyEditorsKeepCancelDistinctFromIdleReturnAndNeverConfirm() {
        val frame = ClickChainFrameEditor("frame-0", "合成画面", null, emptyList(), null, null, false)
        val action = ClickChainActionEditor("action-0", "合成动作", OpaqueMask(.1f, .1f, .3f, .2f),
            null, null, "合成后继", .2f to .15f, false, 1)
        val editors = listOf(fixture().copy(frameEditor = frame), fixture().copy(actionEditor = action))
        val state = mutableStateOf(editors.first().copy(busy = true))
        var cancels = 0
        var returns = 0
        var writes = 0
        show(state, ClickChainReviewCallbacks(onCancel = { cancels++ }, onCloseEditor = { returns++ },
            onGenerateFrame = { writes++ }, onConfirmFrame = { writes++ }, onConfirmAction = { writes++ },
            onFrameTitleChange = { writes++ }, onActionLabelChange = { writes++ }, onCreate = { writes++ }))
        editors.forEachIndexed { index, editorState ->
            compose.runOnIdle { state.value = editorState.copy(busy = true) }
            compose.onNodeWithText("返回整理").assertDoesNotExist()
            // Disabled text fields intentionally expose no SetText action; find the merged field by its label.
            compose.onNodeWithText(if (index == 0) "画面标题" else "动作名称").assertIsNotEnabled()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.runOnIdle { assertEquals(index, returns) }
            compose.onNodeWithText("取消处理").assertIsDisplayed().assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(index + 1, cancels); assertEquals(0, writes); state.value = editorState }
            compose.onNodeWithText("取消处理").assertDoesNotExist()
            compose.onNodeWithText("返回整理").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(index + 1, returns); assertEquals(index + 1, cancels); assertEquals(0, writes) }
        }
    }

    @Test fun blankRouteLabelsStayVisibleWithoutRepairingOrConfirmingTheDraft() {
        val ready = fixture(complete = true)
        fun withNames(from: String, to: String) = ready.copy(canCreate = false,
            frames = ready.frames.mapIndexed { index, frame -> frame.copy(title = if (index == 0) from else to) },
            stages = ready.stages.mapIndexed { index, stage -> stage.copy(title = if (index == 0) from else to) },
            actions = ready.actions.map { it.copy(fromTitle = from, toTitle = to) })
        val state = mutableStateOf(withNames("", "合成后继"))
        val opened = mutableListOf<String>()
        var writes = 0
        show(state, ClickChainReviewCallbacks(onReviewFrame = { opened += it }, onFrameTitleChange = { writes++ },
            onConfirmFrame = { writes++ }, onConfirmAction = { writes++ }, onCreate = { writes++ }))
        listOf("" to "合成后继", "合成起点" to " \t", "" to "").forEach { (from, to) ->
            compose.runOnIdle { state.value = withNames(from, to) }
            compose.onNodeWithTag("click_chain_workbench").performScrollToKey("action:action-0")
            compose.onNodeWithText("${from.ifBlank { "未填写名称" }} → ${to.ifBlank { "未填写名称" }}").assertIsDisplayed()
            compose.onNodeWithText("生成独立项目").assertDoesNotExist()
            compose.onNodeWithText("补全画面名称").assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(from, state.value.actions.single().fromTitle)
                assertEquals(to, state.value.actions.single().toTitle)
                assertTrue(!state.value.canCreate); assertEquals(0, writes)
            }
        }
        compose.runOnIdle { assertEquals(listOf("frame-0", "frame-1", "frame-0"), opened) }
    }

    @Test
    @Config(qualifiers = "w800dp-h800dp-mdpi")
    fun shortParentInsideTallConfigurationUsesActualAvailableHeight() {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val ready = fixture(complete = true)
        val state = ready.copy(
            frames = ready.frames.mapIndexed { index, frame -> if (index == 0) frame.copy(thumbnail = bitmap)
                else frame.copy(reviewed = false, thumbnailSource = ClickChainThumbnailSource.OriginalUnreviewed("pending")) },
            stages = ready.stages.mapIndexed { index, stage -> stage.copy(reviewed = index == 0) },
            actions = ready.actions.map { it.copy(confirmed = false, enabled = false) },
            reviewedFrameCount = 1, confirmedActionCount = 0, canCreate = false)
        val paneHeight = mutableStateOf(800.dp)
        var confirms = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                TapSceneTheme {
                    Box(Modifier.fillMaxWidth().height(paneHeight.value)) {
                        ClickChainReviewContent(state, ClickChainReviewCallbacks(onConfirmFrame = { confirms++ }, onConfirmAction = { confirms++ }))
                    }
                }
            }
        }
        compose.onNodeWithText("合成运行").assertIsDisplayed()
        compose.runOnIdle { paneHeight.value = 360.dp }
        assertTrue("The configuration must remain tall while the available pane shrinks",
            compose.activity.resources.configuration.screenHeightDp >= 800)
        val picture = compose.onNodeWithContentDescription("合成画面 0，已复核画面缩略图")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithText("画面 1/2 · 动作 0/1").fetchSemanticsNode().boundsInRoot
        assertEquals("The entire picture must fit in the constrained pane", 84f, picture.height, .5f)
        assertTrue(picture.bottom <= progress.top)
        compose.onNodeWithText("合成运行").assertDoesNotExist()
        compose.onNodeWithText("继续复核画面").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("click_chain_settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("合成运行").assertIsDisplayed()
        compose.onNodeWithTag("click_chain_settings").performClick()
        compose.runOnIdle { paneHeight.value = 800.dp }
        compose.onNodeWithText("合成运行").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, confirms) }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun shortLandscapeShowsTheFirstPictureBeforeScrollingAndKeepsSettingsReachable() {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val ready = fixture(complete = true)
        val state = mutableStateOf(ready.copy(
            frames = ready.frames.mapIndexed { index, frame -> if (index == 0) frame.copy(thumbnail = bitmap)
                else frame.copy(reviewed = false, thumbnailSource = ClickChainThumbnailSource.OriginalUnreviewed("pending")) },
            stages = ready.stages.mapIndexed { index, stage -> stage.copy(reviewed = index == 0) },
            actions = ready.actions.map { it.copy(confirmed = false, enabled = false) },
            reviewedFrameCount = 1, confirmedActionCount = 0, canCreate = false))
        var confirms = 0
        show(state, ClickChainReviewCallbacks(onConfirmFrame = { confirms++ }, onConfirmAction = { confirms++ }))
        val picture = compose.onNodeWithContentDescription("合成画面 0，已复核画面缩略图")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithText("画面 1/2 · 动作 0/1").fetchSemanticsNode().boundsInRoot
        assertEquals("The entire 84dp picture must be visible on first entry", 84f, picture.height, .5f)
        assertTrue(picture.bottom <= progress.top)
        compose.onNodeWithText("继续复核画面").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("合成运行").assertDoesNotExist()
        compose.onNodeWithTag("click_chain_settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("合成运行").assertIsDisplayed()
        compose.onNodeWithTag("click_chain_settings").performClick()
        compose.onNodeWithContentDescription("合成画面 0，已复核画面缩略图").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, confirms) }
    }

    @Test fun editorReturnPreservesExpandedReadingPositionAndNewSessionResetsIt() {
        val rows = List(25) { index -> ClickChainFrameRow("frame-$index", "合成画面 $index", true) }
        val state = mutableStateOf(fixture(complete = true).copy(framesOnly = true, frames = rows,
            frameCount = rows.size, reviewedFrameCount = rows.size))
        show(state, ClickChainReviewCallbacks(onReviewFrame = { key -> state.value = state.value.copy(
            frameEditor = ClickChainFrameEditor(key, "合成画面", null, emptyList(), null, null, true)) },
            onCloseEditor = { state.value = state.value.copy(frameEditor = null) }))
        val list = compose.onNodeWithTag("click_chain_workbench")
        list.performScrollToKey("frame:frame-14")
        compose.onNodeWithContentDescription("展开合成画面 14 的详情").performClick()
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 17f) }
        val before = compose.onNodeWithText("合成画面 14").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNode(hasText("查看画面") and hasAnyAncestor(hasTestTag("click_chain_frame_frame-14"))).performClick()
        compose.onNodeWithText("返回整理").performClick()
        compose.onNodeWithContentDescription("收起合成画面 14 的详情").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("合成画面 14").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state.value = state.value.copy(reviewSessionId = "session-b") }
        assertEquals(0f, compose.onNodeWithTag("click_chain_workbench").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), 0f)
        compose.onNodeWithText("合成运行").assertIsDisplayed()
        compose.onNodeWithTag("click_chain_workbench").performScrollToKey("frame:frame-14")
        compose.onNodeWithContentDescription("展开合成画面 14 的详情").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起合成画面 14 的详情").assertDoesNotExist()
    }

    private fun fixture(complete: Boolean = false): ClickChainReviewUiState {
        val rows = List(2) { index -> ClickChainFrameRow("frame-$index", "合成画面 $index", complete,
            thumbnailSource = if (complete) ClickChainThumbnailSource.ReviewedOutput("output-$index")
                else ClickChainThumbnailSource.OriginalUnreviewed("original-$index"),
            selectionNotice = INITIAL_SNAPSHOT_NOTICE.takeIf { index == 0 }) }
        return ClickChainReviewUiState(runId = "run-a", reviewSessionId = "session-a", title = "合成演示", runLabel = "合成运行",
            totalActions = 1, frames = rows, frameCount = 2, reviewedFrameCount = if (complete) 2 else 0,
            confirmedActionCount = if (complete) 1 else 0, canCreate = complete,
            stages = rows.mapIndexed { index, frame -> ClickChainStageRow(index, frame.title,
                listOf(ClickChainStageChoice(frame.key, "代表画面")), frame.key, complete) },
            actions = listOf(ClickChainActionRow("action-0", 1, "手势已完成", rows[0].title, rows[1].title,
                complete, complete, selectionNotice = INITIAL_SNAPSHOT_NOTICE)))
    }

    private fun show(state: MutableState<ClickChainReviewUiState>, callbacks: ClickChainReviewCallbacks = ClickChainReviewCallbacks()) {
        compose.runOnUiThread { compose.activity.setContent { TapSceneTheme { ClickChainReviewContent(state.value, callbacks) } } }
        compose.waitForIdle()
    }

    private fun resetFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
