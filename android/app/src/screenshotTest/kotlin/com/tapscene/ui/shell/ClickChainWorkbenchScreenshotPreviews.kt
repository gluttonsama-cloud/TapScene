package com.tapscene.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.clickplan.ClickChainActionRow
import com.tapscene.clickplan.ClickChainFrameRow
import com.tapscene.clickplan.ClickChainReviewUiState
import com.tapscene.clickplan.ClickChainStageChoice
import com.tapscene.clickplan.ClickChainStageRow
import com.tapscene.clickplan.ClickChainThumbnailSource
import com.tapscene.clickplan.INITIAL_SNAPSHOT_NOTICE

/** Synthetic layout fixtures calling the production workbench, not a separately drawn mock screen. */
@PreviewTest
@Preview(name = "click_chain_workbench_compact", widthDp = 360, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainWorkbenchCompactPreview() {
    WorkbenchPreviewSurface { ClickChainReviewContent(workbenchFixture(complete = true), ClickChainReviewCallbacks()) }
}

@PreviewTest
@Preview(name = "click_chain_workbench_mixed_large", widthDp = 320, heightDp = 800, fontScale = 1.5f, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainWorkbenchMixedLargePreview() {
    WorkbenchPreviewSurface { ClickChainReviewContent(workbenchFixture(), ClickChainReviewCallbacks()) }
}

@PreviewTest
@Preview(name = "click_chain_workbench_landscape", widthDp = 800, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainWorkbenchLandscapePreview() {
    WorkbenchPreviewSurface { ClickChainReviewContent(workbenchFixture(), ClickChainReviewCallbacks()) }
}

@PreviewTest
@Preview(name = "click_chain_workbench_committed", widthDp = 360, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainWorkbenchCommittedPreview() {
    WorkbenchPreviewSurface {
        ClickChainReviewContent(workbenchFixture(complete = true).copy(createdProjectId = "synthetic-result",
            editingLocked = true, canCreate = false, canStartAnotherSegment = true), ClickChainReviewCallbacks())
    }
}

@PreviewTest
@Preview(name = "click_chain_workbench_deleted_result", widthDp = 320, heightDp = 640, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainWorkbenchDeletedResultPreview() {
    WorkbenchPreviewSurface {
        // Reopening a committed receipt does not require loading the recording or pretend to know its frame counts.
        ClickChainReviewContent(ClickChainReviewUiState(runId = "synthetic-run", reviewSessionId = "synthetic-review",
            title = "活动报名演示", editingLocked = true, canStartAnotherSegment = true), ClickChainReviewCallbacks())
    }
}

@Composable
private fun workbenchFixture(complete: Boolean = false): ClickChainReviewUiState {
    val pictures = listOf(remember { ShellPreviewFixture.bitmap(0) }, remember { ShellPreviewFixture.bitmap(1) },
        remember { ShellPreviewFixture.bitmap(2) })
    val titles = listOf("选择活动", "填写信息", "报名完成")
    val frames = titles.mapIndexed { index, title ->
        val reviewed = complete || index < 2
        ClickChainFrameRow("synthetic-frame-$index", title, reviewed, thumbnail = pictures[index],
            thumbnailSource = if (reviewed) ClickChainThumbnailSource.ReviewedOutput("synthetic-output-$index")
                else ClickChainThumbnailSource.OriginalUnreviewed("synthetic-original-$index"),
            selectionNotice = INITIAL_SNAPSHOT_NOTICE.takeIf { index == 0 })
    }
    return ClickChainReviewUiState(runId = "synthetic-run", reviewSessionId = "synthetic-review", title = "活动报名演示",
        runLabel = "3 个编排动作 · 录屏保存在本机", totalActions = 3, firstAction = "1", lastAction = "2",
        frames = frames, frameCount = frames.size, reviewedFrameCount = frames.count { it.reviewed },
        stages = frames.mapIndexed { index, frame -> ClickChainStageRow(index, frame.title,
            listOf(ClickChainStageChoice(frame.key, if (index == 0) "首次点击前的初始快照" else "上一动作后")), frame.key, frame.reviewed) },
        actions = listOf(
            ClickChainActionRow("synthetic-action-0", 1, "手势已完成", titles[0], titles[1], true, true,
                selectionNotice = INITIAL_SNAPSHOT_NOTICE),
            ClickChainActionRow("synthetic-action-1", 2, "手势已完成", titles[1], titles[2], complete, complete),
        ), confirmedActionCount = if (complete) 2 else 1, canCreate = complete)
}

@Composable
private fun WorkbenchPreviewSurface(content: @Composable () -> Unit) {
    TapSceneTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content) }
}
