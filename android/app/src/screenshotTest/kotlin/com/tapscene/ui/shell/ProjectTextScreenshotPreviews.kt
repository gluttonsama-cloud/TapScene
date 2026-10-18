package com.tapscene.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

/** Synthetic layout inputs only; these render the actual editor and immutable review UI. */
@PreviewTest
@Preview(name = "project_text_editor_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectTextEditorSmallPreview() {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(24.dp)) {
                Text("项目文字", Modifier.padding(bottom = 20.dp), style = MaterialTheme.typography.headlineSmall)
                ProjectTextFields("活动报名 · 布局样例", "演示选择活动、核对信息和完成报名。", {}, {}, true)
            }
        }
    }
}

@PreviewTest
@Preview(name = "project_text_review_landscape", widthDp = 740, heightDp = 480, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectTextReviewLandscapePreview() {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ReleaseReviewContent(ReleasePreviewFixture.reviewState, 4L,
                {}, {}, {}, {}, {}, {}, {}, {}, initialSection = 2, onEditSourceProjectText = {})
        }
    }
}
