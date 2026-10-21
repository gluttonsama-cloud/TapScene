package com.tapscene.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.ReleaseSummary
import com.tapscene.ui.ReleaseDeletionCheck
import com.tapscene.ui.ReleaseUiState

private val deletionSample = ReleaseSummary("a98341bf-6c76-4cab-b735-3a4484628a91",
    "报名流程 · 核对参加者与场次后确认提交", "01".repeat(32), 3, 4, 2048, "local")

/** Actual scrollable confirmation body; host layout is not an Android dialog-window check. */
@PreviewTest
@Preview(name = "release_deletion_details_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ReleaseDeletionDetailsSmallPreview() = ReleaseDeletionDetailsPreview(deletionSample, true)

@PreviewTest
@Preview(name = "release_deletion_details_landscape", widthDp = 740, heightDp = 360, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ReleaseDeletionDetailsLandscapePreview() = ReleaseDeletionDetailsPreview(deletionSample.copy(origin = "imported"), false)

@Composable
private fun ReleaseDeletionDetailsPreview(release: ReleaseSummary, current: Boolean) {
    TapSceneTheme { Surface(color = ShellColors.Background) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("删除确认内容 · 合成布局 · 非设备弹窗", style = MaterialTheme.typography.labelSmall)
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { ReleaseDeleteDetails(release, current) }
        }
    } }
}

@PreviewTest
@Preview(name = "release_deletion_result_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ReleaseDeletionResultSmallPreview() {
    val state = ReleaseUiState(releases = listOf(deletionSample), libraryReadFailed = true,
        deletionCheck = ReleaseDeletionCheck(deletionSample),
        message = "暂时无法核对删除结果。请重新核对，确认前不要重复删除或导入同一版本。")
    TapSceneTheme { Surface(color = ShellColors.Background) {
        ReleaseLibraryContent(state, {}, {}, {}, {}, {})
    } }
}
