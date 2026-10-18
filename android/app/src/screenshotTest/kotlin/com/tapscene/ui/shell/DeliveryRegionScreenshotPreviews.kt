package com.tapscene.ui.shell

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.ProjectRegion
import com.tapscene.data.RegionBox
import com.tapscene.ui.ProjectWorkspace

/** Actual delivery check content; synthetic region metadata, no crop or device verification. */
@PreviewTest
@Preview(name = "region_readiness_small", widthDp = 320, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Preview(name = "region_readiness_landscape", widthDp = 740, heightDp = 480, locale = "zh-rCN", showBackground = true)
@Composable
fun DeliveryRegionReadinessPreview() {
    val original = ShellPreviewFixture.project
    val first = original.steps.first()
    val base = first.asset
    val missing = ProjectRegion("layout-region-missing", first.id, base.id, base.sha256,
        "标题区", null, RegionBox(12, 24, 120, 60), base.width, base.height, 0, .5, .5)
    val unreviewed = missing.copy(id = "layout-region-unreviewed", name = missing.name, zIndex = 1,
        asset = base.copy(id = "layout-crop", width = 120, height = 60))
    val stale = missing.copy(id = "layout-region-stale", name = missing.name, zIndex = 2, baseAssetId = "previous-base")
    val snapshot = original.copy(steps = original.steps.map {
        if (it.id == first.id) it.copy(regions = listOf(missing, unreviewed, stale)) else it
    })
    TapSceneTheme {
        Surface(color = ShellColors.Background) {
            DeliveryCheckScreen(snapshot, ProjectWorkspace.deliveryIssues(snapshot), {}, {}, {}, {}, {},
                onBuildCandidate = {})
        }
    }
}
