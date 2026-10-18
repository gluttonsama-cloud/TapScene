package com.tapscene.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.RetainedMediaWorkspace
import com.tapscene.ui.ProjectUiState

/** Production management content with synthetic project identities and byte counts. */
@PreviewTest
@Preview(name = "project_sources_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectSourceStorageSmallPreview() = SourceStoragePreviewSurface {
    RetainedSourcesScreen(ProjectUiState(retainedMediaWorkspaces = listOf(RetainedMediaWorkspace(null, "原素材工作台", 0))),
        listOf(ProjectSourceUsage("51211cfa-79e5-42bf-9309-1e246230492e", "报名流程 · 核对活动和参加者信息", 2, 42_000_000),
            ProjectSourceUsage("862b3803-3854-4e6a-b65b-918000b55563", "报名流程 · 核对活动和参加者信息", 1, 7_000_000)),
        false, {}, {}, {})
}

@PreviewTest
@Preview(name = "project_sources_unknown_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectSourceStorageUnknownLandscapePreview() = SourceStoragePreviewSurface {
    RetainedSourcesScreen(ProjectUiState(retainedMediaWorkspaces = emptyList()),
        listOf(ProjectSourceUsage("51211cfa-79e5-42bf-9309-1e246230492e", "报名流程", null, null)),
        false, {}, {}, {})
}

@Composable
private fun SourceStoragePreviewSurface(content: @Composable () -> Unit) {
    // Match the production shell's surrounding Surface.
    TapSceneTheme { Surface(Modifier.fillMaxSize(), color = ShellColors.Background, content = content) }
}
