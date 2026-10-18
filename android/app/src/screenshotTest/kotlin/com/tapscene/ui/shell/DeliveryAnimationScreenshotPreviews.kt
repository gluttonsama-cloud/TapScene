package com.tapscene.ui.shell

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.DraftAiConfig
import com.tapscene.packageformat.RenderPlan
import com.tapscene.ui.ProjectWorkspace

/** Production checklist with a copied plan awaiting explicit confirmation; synthetic metadata. */
@PreviewTest
@Preview(name = "animation_readiness_small_large_font", widthDp = 320, heightDp = 800, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Preview(name = "animation_readiness_landscape", widthDp = 740, heightDp = 480, locale = "zh-rCN", showBackground = true)
@Composable
fun DeliveryAnimationReadinessPreview() {
    val snapshot = ShellPreviewFixture.project
    val plan = DraftAiConfig(snapshot.project.revision, true, 1080, 1920,
        listOf(RenderPlan.Visit("00000000-0000-4000-8000-000000000001", snapshot.steps.first().id, null, 90)), emptyList())
    TapSceneTheme {
        Surface(color = ShellColors.Background) {
            DeliveryCheckScreen(snapshot, ProjectWorkspace.deliveryIssues(snapshot, plan), {}, {}, {}, {}, {},
                onBuildCandidate = {})
        }
    }
}
