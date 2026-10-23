package com.tapscene.ui.shell

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.ProjectSummary
import com.tapscene.ui.ProjectUiState

/** Production filtered home with synthetic metadata; navigation is checked by the host test. */
@PreviewTest
@Preview(name = "project_home_search_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Preview(name = "project_home_search_landscape", widthDp = 640, heightDp = 320, fontScale = 1.5f, locale = "zh-rCN", showBackground = true)
@Preview(name = "project_home_search_largest_text", widthDp = 320, heightDp = 480, fontScale = 2f, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectHomeSearchSmallPreview() {
    val home = rememberProjectHomeState()
    remember(home) { home.query = "报名" }
    val projects = listOf(
        ProjectSummary("sample-registration", "报名流程", "核对场次和参加者后确认提交", 3, 120_000L, 5, null),
        ProjectSummary("sample-unrelated", "修改地址", "确认收件信息", 1, 120_000L, 2, null),
    )
    TapSceneTheme {
        Surface(color = ShellColors.Background) {
            ProjectHomeFrame({}, {}, {}, {}, onClickPlan = {}) { creationActions ->
                ProjectHomeContent(ProjectUiState(projects = projects), {}, {}, {}, homeState = home,
                    creationActions = creationActions)
            }
        }
    }
}

@PreviewTest
@Preview(name = "project_home_introduction_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectHomeIntroductionSmallPreview() {
    TapSceneTheme {
        Surface(color = ShellColors.Background) {
            ProjectHomeFrame({}, {}, {}, {}, onClickPlan = {}) { creationActions ->
                ProjectHomeContent(ProjectUiState(), {}, {}, {}, creationActions = creationActions)
            }
        }
    }
}

@PreviewTest
@Preview(name = "project_home_existing", widthDp = 360, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectHomeExistingPreview() {
    val projects = listOf(
        ProjectSummary("sample-registration", "报名流程", "核对场次和参加者后确认提交", 3, 1_761_120_000_000L, 5, null),
        ProjectSummary("sample-address", "修改地址", "确认收件信息", 1, 1_761_116_400_000L, 2, null),
    )
    TapSceneTheme {
        Surface(color = ShellColors.Background) {
            ProjectHomeFrame({}, {}, {}, {}, onClickPlan = {}) { creationActions ->
                ProjectHomeContent(ProjectUiState(projects = projects), {}, {}, {}, creationActions = creationActions)
            }
        }
    }
}

@PreviewTest
@Preview(name = "project_home_long_and_empty_cards", widthDp = 360, heightDp = 800, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectHomeLongAndEmptyCardsPreview() {
    val projects = listOf(
        ProjectSummary("sample-registration", "活动报名 Registration · 确认场次与参加者",
            "选择适合的场次，核对参加者信息，再确认报名结果。", 3, 1_761_120_000_000L, 5, null),
        ProjectSummary("sample-empty", "产品介绍", "", 1, 1_761_116_400_000L, 0, null),
    )
    TapSceneTheme {
        Surface(color = ShellColors.Background) {
            ProjectHomeFrame({}, {}, {}, {}, onClickPlan = {}) { creationActions ->
                ProjectHomeContent(ProjectUiState(projects = projects), {}, {}, {}, creationActions = creationActions)
            }
        }
    }
}
