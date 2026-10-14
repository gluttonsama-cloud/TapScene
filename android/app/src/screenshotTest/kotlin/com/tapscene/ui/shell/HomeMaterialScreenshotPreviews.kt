package com.tapscene.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

/** Production source UI with in-memory metadata only; no device or media decoding evidence. */
@PreviewTest
@Preview(name = "sources_narrow_large_text", widthDp = 320, heightDp = 915, fontScale = 1.5f, locale = "zh-rCN", showBackground = true)
@Composable
fun SourcesNarrowLargeTextPreview() = HomeMaterialSurface {
    ProjectWorkspaceFrame("报名演示", ProjectTab.SOURCES, {}, {}, {}, {}) {
        val draft = ShellPreviewFixture.sources.single().let {
            it.copy(source = it.source.copy(displayName = "活动报名录屏：选择活动并确认报名结果.mp4",
                metadata = it.source.metadata.copy(byteLength = 12_345_678L)))
        }
        ProjectSourcesContent(listOf(draft), false, false, {}, {}, {}, {}, {}, onClickPlan = {})
    }
}

@PreviewTest
@Preview(name = "screenshot_project_without_recordings", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun ScreenshotProjectWithoutRecordingsPreview() = HomeMaterialSurface {
    ProjectWorkspaceFrame("截图项目", ProjectTab.SOURCES, {}, {}, {}, {}) {
        ProjectSourcesContent(emptyList(), false, false, {}, {}, {}, {}, {}, onClickPlan = {})
    }
}

@PreviewTest
@Preview(name = "source_options_at_video_limit", widthDp = 320, heightDp = 480, fontScale = 1.5f, locale = "zh-rCN", showBackground = true)
@Composable
fun SourceOptionsAtVideoLimitPreview() = HomeMaterialSurface {
    SourceAddOptionsContent(false, false, 3, {}, {}, {})
}

@Composable
private fun HomeMaterialSurface(content: @Composable () -> Unit) {
    TapSceneTheme { Surface(Modifier.fillMaxSize(), color = ShellColors.Background, content = content) }
}
