package com.tapscene.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.ui.PendingMaskEditsActions

/** Actual failed-save recovery actions. Synthetic state, no storage or device claims. */
@PreviewTest
@Preview(name = "mask_recovery_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Preview(name = "mask_recovery_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun MaskRecoveryActionsPreview() {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                Text("手动校正", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 24.dp))
                PendingMaskEditsActions(true, {}, {})
            }
        }
    }
}
