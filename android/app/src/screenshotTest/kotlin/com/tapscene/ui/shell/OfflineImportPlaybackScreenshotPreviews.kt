package com.tapscene.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.ReleaseSummary
import com.tapscene.ui.ReleaseUiState

/** Actual success content with synthetic metadata; no system picker or device evidence. */
@PreviewTest
@Preview(name = "offline_import_open_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun OfflineImportPlaybackSmallPreview() {
    val release = ReleaseSummary("d2fd5f2e-861c-44a3-b00b-f81f31141dc7", "报名流程 · 核对场次和参加者后确认提交", "0".repeat(64), 1, 3, 120_000, "imported")
    TapSceneTheme { OfflineImportContent(ReleaseUiState(releases = listOf(release), lastImportedId = release.id, message = "完整观看包已加入演示库；文件校验不代表来源或隐私认证"), {}, {}, onOpen = {}) }
}

/** A known registration with a failed library refresh; synthetic metadata, not a disk receipt. */
@PreviewTest
@Preview(name = "offline_import_registered_pending_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun OfflineImportRegisteredPendingSmallPreview() {
    val registered = ReleaseSummary("d2fd5f2e-861c-44a3-b00b-f81f31141dc7", "报名流程 · 核对场次和参加者后确认提交", "a".repeat(64), 1, 3, 120_000, "imported")
    TapSceneTheme { OfflineImportContent(ReleaseUiState(importLibraryCheck = registered, libraryReadFailed = true), {}, {}, onOpen = {}) }
}
