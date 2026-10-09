package com.tapscene.ui.shell

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.ProjectRegion
import com.tapscene.data.RegionBox
import com.tapscene.data.StepAsset
import com.tapscene.ui.RegionUiState

/** Synthetic layout examples only; no persistent project, source, crop or review is created. */
private object RegionPreviewFixture {
    val box = RegionBox(48, 655, 384, 93)
    val step = ShellPreviewFixture.selectedStep
    val region = ProjectRegion(id = "layout-region", stateId = step.id, baseAssetId = step.asset.id,
        baseSha256 = step.asset.sha256, name = "报名按钮", group = "主要操作", bbox = box,
        sourceWidth = step.asset.width, sourceHeight = step.asset.height, zIndex = 2, anchorX = 0.5, anchorY = 0.5)
    val callbacks = RegionEditorCallbacks({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {})

    fun state(bitmap: Bitmap, crop: Bitmap? = null, outdated: Boolean = false): RegionUiState {
        val currentRegion = region.copy(baseSha256 = if (outdated) "1".repeat(64) else step.asset.sha256,
            asset = if (crop != null) StepAsset("layout-crop", "screenshot-only/crop.png", "2".repeat(64), 0L,
                box.width, box.height) else null)
        val project = ShellPreviewFixture.project.copy(steps = ShellPreviewFixture.project.steps.map {
            if (it.id == step.id) it.copy(regions = listOf(currentRegion)) else it
        })
        return RegionUiState(projectId = project.project.id, stepId = step.id, snapshot = project, bitmap = bitmap,
            selectedId = region.id, cropBitmap = crop, cropSha256 = currentRegion.asset?.sha256,
            displayedSha256 = currentRegion.asset?.sha256)
    }
}

@PreviewTest
@Preview(name = "16_region_editor_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun RegionEditorSamplePreview() {
    val bitmap = remember { ShellPreviewFixture.bitmap(1) }
    RegionReviewSurface { RegionEditorContent(RegionPreviewFixture.state(bitmap), RegionPreviewFixture.callbacks) }
}

@PreviewTest
@Preview(name = "17_region_actual_crop_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun RegionActualCropSamplePreview() {
    val bitmap = remember { ShellPreviewFixture.bitmap(1) }
    val crop = remember(bitmap) { RegionPreviewFixture.box.let { Bitmap.createBitmap(bitmap, it.x, it.y, it.width, it.height) } }
    RegionReviewSurface { RegionEditorContent(RegionPreviewFixture.state(bitmap, crop), RegionPreviewFixture.callbacks) }
}

@PreviewTest
@Preview(name = "18_region_stale_landscape_sample", widthDp = 915, heightDp = 412, locale = "zh-rCN", showBackground = true)
@Composable
fun RegionStaleLandscapeSamplePreview() {
    val bitmap = remember { ShellPreviewFixture.bitmap(1) }
    RegionReviewSurface { RegionEditorContent(RegionPreviewFixture.state(bitmap, outdated = true), RegionPreviewFixture.callbacks) }
}

@Composable
private fun RegionReviewSurface(content: @Composable () -> Unit) {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                Text("布局样例 · 合成内容，仅供界面检查", Modifier.fillMaxWidth().background(ShellColors.AccentSoft)
                    .padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = ShellColors.Accent)
            }
        }
    }
}
