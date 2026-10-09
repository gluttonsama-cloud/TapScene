package com.tapscene.ui.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.ui.ScreenshotEditorDraft
import com.tapscene.ui.WorkspaceUiState
import java.io.File

/** Synthetic pixels and in-memory states only; production content, not screenshots of a device. */
private val screenshotCallbacks = CorrectionUiCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {})

@PreviewTest
@Preview(name="34_screenshot_import_entry",widthDp=412,heightDp=915,locale="zh-rCN",showBackground=true)
@Composable
fun ScreenshotImportEntryPreview() {
    TapSceneTheme { Surface(Modifier.fillMaxSize(),color=ShellColors.Background) {
        ProjectWorkspaceFrame("报名演示",ProjectTab.SOURCES,{}, {}, {}, {}) {
            ProjectSourcesContent(emptyList(),false,false,{}, {}, {}, {}, {}, onImportScreenshot={})
        }
    } }
}

@PreviewTest
@Preview(name="35_screenshot_import_review",widthDp=412,heightDp=915,locale="zh-rCN",showBackground=true)
@Composable
fun ScreenshotImportReviewPreview() {
    val raw=remember { ShellPreviewFixture.bitmap(1) }
    val mask=OpaqueMask(.10f,.30f,.87f,.37f)
    val output=remember(raw) { requireNotNull(raw.copy(Bitmap.Config.ARGB_8888,true)).also {
        Canvas(it).drawRect(mask.left*it.width,mask.top*it.height,mask.right*it.width,mask.bottom*it.height,
            Paint().apply { color=Color.BLACK })
    } }
    val state=WorkspaceUiState(candidate=SafeMediaWriter.CandidateMedia(File("layout-only/screenshot.png"),
        "e".repeat(64),output.width,output.height,"image/png",null),candidateImage=output)
    TapSceneTheme { Surface(Modifier.fillMaxSize(),color=ShellColors.Background) {
        StepImageCorrectionContent(state,null,StepImageCorrectionMode.REVIEW,screenshotCallbacks,
            screenshot=ScreenshotEditorDraft("layout-screenshot",raw,listOf(mask),creatingProject=true))
    } }
}

@PreviewTest
@Preview(name="36_screenshot_import_failure",widthDp=412,heightDp=915,locale="zh-rCN",showBackground=true)
@Composable
fun ScreenshotImportFailurePreview() {
    TapSceneTheme { Surface(Modifier.fillMaxSize(),color=ShellColors.Background) {
        StepImageCorrectionContent(WorkspaceUiState(message="截图像素数据不完整，请重新选择完整的 PNG 或 JPEG。"),
            null,StepImageCorrectionMode.MASK,screenshotCallbacks,
            screenshot=ScreenshotEditorDraft("layout-failed-screenshot",null,creatingProject=true))
    } }
}
