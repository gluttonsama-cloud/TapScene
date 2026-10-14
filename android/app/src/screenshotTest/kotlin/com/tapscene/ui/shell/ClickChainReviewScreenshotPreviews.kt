package com.tapscene.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.clickplan.ClickChainActionEditor
import com.tapscene.clickplan.ClickChainActionRow
import com.tapscene.clickplan.ClickChainReviewUiState
import com.tapscene.clickplan.ClickChainFrameRow
import com.tapscene.clickplan.ClickChainStageChoice
import com.tapscene.clickplan.ClickChainStageRow
import com.tapscene.clickplan.INITIAL_SNAPSHOT_NOTICE
import com.tapscene.media.OpaqueMask

/** Layout-only synthetic inputs. These previews never connect to apps, capture or dispatch. */
@PreviewTest
@Preview(name = "click_chain_review_small", widthDp = 320, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainReviewSmallPreview() {
    ClickChainReviewPreviewSurface {
        ClickChainReviewContent(clickChainWorkbenchFixture(), ClickChainReviewCallbacks())
    }
}

@PreviewTest
@Preview(name = "click_chain_action_editor", widthDp = 360, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainActionEditorPreview() {
    val reviewed = remember { ShellPreviewFixture.bitmap(1) }
    val successor = remember { ShellPreviewFixture.bitmap(2) }
    ClickChainReviewPreviewSurface {
        ClickChainReviewContent(ClickChainReviewUiState(actionEditor = ClickChainActionEditor(
            actionId = "layout-action", label = "确认报名", rect = OpaqueMask(.1f, .78f, .9f, .89f),
            bitmap = reviewed, targetThumbnail = successor, targetTitle = "报名完成",
            point = .5f to .835f, selfLoop = false, samePageActionCount = 2,
            selectionNotice = INITIAL_SNAPSHOT_NOTICE,
        )), ClickChainReviewCallbacks())
    }
}

@PreviewTest
@Preview(name = "click_chain_review_complete", widthDp = 360, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainReviewCompletePreview() {
    ClickChainReviewPreviewSurface {
        ClickChainReviewContent(clickChainWorkbenchFixture(true), ClickChainReviewCallbacks())
    }
}

@PreviewTest
@Preview(name = "click_chain_review_missing", widthDp = 320, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainReviewMissingPreview() {
    ClickChainReviewPreviewSurface {
        val state = clickChainWorkbenchFixture()
        ClickChainReviewContent(state.copy(
            stages = listOf(state.stages.first(), ClickChainStageRow(1, "画面待补", emptyList(), null, false)),
            actions = listOf(state.actions.first().copy(status = "结果待确认", detail = "前后画面或完成结果待补")),
            issue = "动作 1 的前后画面或完成结果待补，请选择完整连续段，或仅加入画面。",
            frames = state.frames.take(1), frameCount = 1,
        ), ClickChainReviewCallbacks())
    }
}

@PreviewTest
@Preview(name = "click_chain_review_revisit", widthDp = 360, heightDp = 1100, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainReviewRevisitPreview() {
    ClickChainReviewPreviewSurface {
        val state = clickChainWorkbenchFixture(true)
        ClickChainReviewContent(state.copy(lastAction = "2", totalActions = 2, runLabel = "2 个编排动作 · 录屏保存在本机",
            stages = state.stages + state.stages.first().copy(index = 2),
            actions = state.actions + ClickChainActionRow("layout-return", 2, "手势已完成", "填写信息", "选择活动", false, true),
            canCreate = false,
        ), ClickChainReviewCallbacks())
    }
}

@PreviewTest
@Preview(name = "click_chain_review_invalid_large", widthDp = 320, heightDp = 800, fontScale = 1.5f, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainReviewInvalidLargePreview() {
    ClickChainReviewPreviewSurface {
        ClickChainReviewContent(clickChainWorkbenchFixture().copy(title = "", firstAction = "", lastAction = "99",
            stages = emptyList(), actions = emptyList(), frames = emptyList(), frameCount = 0, reviewedFrameCount = 0,
            issue = "请输入有效的动作范围。"), ClickChainReviewCallbacks())
    }
}

@PreviewTest
@Preview(name = "click_chain_review_frames_only", widthDp = 320, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainReviewFramesOnlyPreview() {
    ClickChainReviewPreviewSurface {
        val state = clickChainWorkbenchFixture()
        ClickChainReviewContent(state.copy(framesOnly = true, frames = state.frames.mapIndexed { index, frame ->
            if (index == 0) frame.copy(selected = false) else frame
        }, frameCount = 1, reviewedFrameCount = 0), ClickChainReviewCallbacks())
    }
}

@PreviewTest
@Preview(name = "click_chain_review_unknown", widthDp = 360, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainReviewUnknownPreview() {
    ClickChainReviewPreviewSurface {
        ClickChainReviewContent(clickChainWorkbenchFixture(true).copy(outcomeUnknown = true, editingLocked = true,
            issue = "生成结果尚未确认，请核对生成结果。", retryCreate = true), ClickChainReviewCallbacks())
    }
}

@PreviewTest
@Preview(name = "click_chain_review_busy", widthDp = 360, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainReviewBusyPreview() {
    ClickChainReviewPreviewSurface {
        ClickChainReviewContent(clickChainWorkbenchFixture(true).copy(busy = true, message = "正在建立独立项目"), ClickChainReviewCallbacks())
    }
}

@Composable
private fun clickChainWorkbenchFixture(complete: Boolean = false): ClickChainReviewUiState {
    val before = remember { ShellPreviewFixture.bitmap(1) }
    val after = remember { ShellPreviewFixture.bitmap(2) }
    return ClickChainReviewUiState(runId = "layout-run", title = "活动报名演示", firstAction = "1", lastAction = "1",
        runLabel = "3 个编排动作 · 录屏保存在本机", totalActions = 3,
        stages = listOf(
            ClickChainStageRow(0, "选择活动", listOf(ClickChainStageChoice("layout-before", "首次点击前的初始快照")), "layout-before", true),
            ClickChainStageRow(1, "填写信息", listOf(ClickChainStageChoice("layout-after", "最后动作后")), "layout-after", complete),
        ),
        actions = listOf(ClickChainActionRow("layout-action", 1, "手势已完成", "选择活动", "填写信息", complete, complete,
            selectionNotice = INITIAL_SNAPSHOT_NOTICE)),
        frames = listOf(ClickChainFrameRow("layout-before", "选择活动", true, thumbnail = before, selectionNotice = INITIAL_SNAPSHOT_NOTICE),
            ClickChainFrameRow("layout-after", "填写信息", complete, thumbnail = after.takeIf { complete })),
        frameCount = 2, reviewedFrameCount = if (complete) 2 else 1, confirmedActionCount = if (complete) 1 else 0,
        canCreate = complete)
}

@Composable
private fun ClickChainReviewPreviewSurface(content: @Composable () -> Unit) {
    TapSceneTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content) }
}
