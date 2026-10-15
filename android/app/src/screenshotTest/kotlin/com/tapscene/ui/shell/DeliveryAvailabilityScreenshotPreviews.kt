package com.tapscene.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

/** Actual delivery/settings composables; synthetic state only, no storage or hosting calls. */
@PreviewTest
@Preview(name = "delivery_without_sealed_version", widthDp = 320, heightDp = 1050, locale = "zh-rCN", fontScale = 1.2f, showBackground = true)
@Composable
fun DeliveryWithoutSealedVersionPreview() = AvailabilityPreviewSurface {
    DeliveryOptionsScreen({}, {}, {}, {}, {})
}

@PreviewTest
@Preview(name = "delivery_local_hosting_available", widthDp = 412, heightDp = 1200, locale = "zh-rCN", showBackground = true)
@Composable
fun DeliveryLocalHostingAvailablePreview() = AvailabilityPreviewSurface {
    DeliveryOptionsScreen({}, {}, {}, {}, {}, sealedSummary = ReleasePreviewFixture.summary,
        onExport = {}, onShare = {}, onOpenSealed = {}, onHosting = {})
}

@PreviewTest
@Preview(name = "settings_supported_formats", widthDp = 320, heightDp = 1050, locale = "zh-rCN", fontScale = 1.2f, showBackground = true)
@Composable
fun SettingsSupportedFormatsPreview() = AvailabilityPreviewSurface {
    SettingsStorageScreen(2, 42_000_000L, {}, {}, {}, {}, hostingAvailable = false,
        initiallyShowFormats = true)
}

@Composable
private fun AvailabilityPreviewSurface(content: @Composable () -> Unit) {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
