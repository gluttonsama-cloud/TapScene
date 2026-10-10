package com.tapscene.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

/** Production delivery page, synthetic release only. The platform chooser is not rendered here. */
@PreviewTest
@Preview(name = "delivery_share_phone", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun DeliverySharePhonePreview() = DeliveryShareSurface()

@PreviewTest
@Preview(name = "delivery_share_small", widthDp = 320, heightDp = 640, locale = "zh-rCN", fontScale = 1.2f, showBackground = true)
@Composable
fun DeliveryShareSmallPreview() = DeliveryShareSurface()

@Composable
private fun DeliveryShareSurface() {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            DeliveryOptionsScreen({}, {}, {}, {}, {},
                sealedSummary = ReleasePreviewFixture.summary.copy(title = "活动报名：选择活动并核对报名结果"),
                onExport = {}, onShare = {})
        }
    }
}
