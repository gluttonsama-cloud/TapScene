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
import com.tapscene.clickplan.ClickChainStageChoice
import com.tapscene.clickplan.ClickChainStageRow
import com.tapscene.media.OpaqueMask

/** Layout-only synthetic inputs. These previews never connect to apps, capture or dispatch. */
@PreviewTest
@Preview(name = "click_chain_review_small", widthDp = 320, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainReviewSmallPreview() {
    ClickChainReviewPreviewSurface {
        ClickChainReviewContent(ClickChainReviewUiState(
            runId = "layout-run", title = "活动报名演示", firstAction = "1", lastAction = "1", totalActions = 3,
            stages = listOf(
                ClickChainStageRow(0, "选择活动", listOf(ClickChainStageChoice("layout-before", "起始画面")), "layout-before", true),
                ClickChainStageRow(1, "填写信息", listOf(ClickChainStageChoice("layout-after", "最后动作后")), "layout-after", false),
            ),
            actions = listOf(ClickChainActionRow("layout-action", 1, "已完成", "选择活动", "填写信息", false, false,
                "先复核前后画面")),
            frameCount = 2, reviewedFrameCount = 1, canCreate = false,
        ), ClickChainReviewCallbacks())
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
        )), ClickChainReviewCallbacks())
    }
}

@Composable
private fun ClickChainReviewPreviewSurface(content: @Composable () -> Unit) {
    TapSceneTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content) }
}
