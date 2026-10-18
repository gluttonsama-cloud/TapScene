package com.tapscene.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "ai_annotation_pending_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun AiAnnotationPendingSmallPreview() = AnnotationSurface {
    AiAnnotationEditor("请先核对报名场次与参加者信息，再确认提交。这个修改尚未应用，原标注的时段和位置保持不变。",
        true, true, true, {}, {}, {}, {})
}

@PreviewTest
@Preview(name = "ai_annotation_empty_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun AiAnnotationEmptyLandscapePreview() = AnnotationSurface {
    AiAnnotationEditor("", true, true, true, {}, {}, {}, {})
}

@Composable
private fun AnnotationSurface(content: @Composable () -> Unit) {
    TapSceneTheme {
        Column(Modifier.fillMaxSize().background(ShellColors.Background).verticalScroll(rememberScrollState())) {
            Text("布局样例 · 合成内容 · 非设备或触控验证", Modifier.fillMaxWidth().background(ShellColors.AccentSoft).padding(8.dp),
                style = MaterialTheme.typography.labelSmall)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("画面标注", style = MaterialTheme.typography.titleLarge)
                content()
            }
        }
    }
}
