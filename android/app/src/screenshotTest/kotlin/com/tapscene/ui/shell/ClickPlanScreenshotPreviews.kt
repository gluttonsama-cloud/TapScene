package com.tapscene.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

/** Synthetic plan data only; previews never connect a service, query installed apps or dispatch taps. */
@PreviewTest
@Preview(name = "click_plan_populated_small", widthDp = 320, heightDp = 740, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickPlanPopulatedSmallPreview() {
    ClickPlanPreviewSurface {
        ClickPlanContent(
            ClickPlanUiState(
                targetLabel = "演示样例 App",
                targetPackage = "com.example.demo",
                width = 1080,
                height = 2400,
                revision = 3,
                points = listOf(
                    ClickPointInput("layout-point-one", "540", "1720", "80", "800"),
                    ClickPointInput("layout-point-two", "720", "960", "120", "1500"),
                ),
                connected = true,
                dirty = true,
                canLocate = true,
                canSave = true,
            ),
            ClickPlanCallbacks(),
        )
    }
}

@PreviewTest
@Preview(name = "click_plan_permission_empty", widthDp = 360, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickPlanPermissionEmptyPreview() {
    ClickPlanPreviewSurface {
        ClickPlanContent(
            ClickPlanUiState(
                width = 1080,
                height = 2400,
                validationMessage = "请选择目标 App。",
            ),
            ClickPlanCallbacks(),
        )
    }
}

@Composable
private fun ClickPlanPreviewSurface(content: @Composable () -> Unit) {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
