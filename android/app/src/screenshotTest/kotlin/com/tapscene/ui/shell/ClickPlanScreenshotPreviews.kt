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
                points = List(40) { index -> ClickPointInput("layout-point-$index", "540", "1720", "80", "800") },
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
@Preview(name = "click_plan_single_expanded", widthDp = 360, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickPlanSingleExpandedPreview() {
    ClickPlanPreviewSurface {
        ClickPlanContent(
            ClickPlanUiState(
                targetLabel = "演示样例 App",
                targetPackage = "com.example.demo",
                width = 1080,
                height = 2400,
                points = listOf(
                    ClickPointInput("layout-point-one", "540", "1720", "80", "800"),
                    ClickPointInput("layout-point-two", "720", "960", "120", "1500"),
                ),
                expandedActionId = "layout-point-one",
                connected = true,
                canLocate = true,
                canSave = true,
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
