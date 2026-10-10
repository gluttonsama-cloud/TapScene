package com.tapscene.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

/** Real production menu body. Host rendering cannot establish platform popup/touch behavior. */
@PreviewTest
@Preview(name = "50_copy_step_menu_small", widthDp = 320, heightDp = 640, locale = "zh-rCN", showBackground = true)
@Preview(name = "51_copy_step_menu_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun SavedStepCopyMenuPreview() {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("步骤菜单 · 界面检查", style = MaterialTheme.typography.labelSmall)
                    Surface(Modifier.width(240.dp), shape = RoundedCornerShape(4.dp), tonalElevation = 3.dp) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            SavedStepActionsMenuContent(true, true, false, true, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
        }
    }
}
