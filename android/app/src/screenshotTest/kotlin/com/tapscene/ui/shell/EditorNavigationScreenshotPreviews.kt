package com.tapscene.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.EditorFormKind
import com.tapscene.data.EditorPendingForm
import com.tapscene.ui.DraftRecoveryStatus

/** Actual production editor and form bodies; no platform input or touch claims. */
@PreviewTest
@Preview(name = "40_editor_name_and_unsaved_small", widthDp = 320, heightDp = 640, locale = "zh-rCN", showBackground = true)
@Composable
fun EditorNameAndUnsavedSmallPreview() = EditorNavigationSurface()

@PreviewTest
@Preview(name = "41_editor_name_and_unsaved_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun EditorNameAndUnsavedLandscapePreview() = EditorNavigationSurface()

@Composable
private fun EditorNavigationSurface() {
    val image = remember { ShellPreviewFixture.bitmap(1) }
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            EditorWorkspaceContent(ShellPreviewFixture.project, ShellPreviewFixture.draft, image, false,
                EditorCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
                previewEnabled = false, regionsEnabled = false, dirtyStepCount = 2, canUndoEdit = true)
        }
    }
}

@PreviewTest
@Preview(name = "42_editor_panel_save_failure_small", widthDp = 320, heightDp = 640, locale = "zh-rCN", showBackground = true)
@Preview(name = "43_editor_panel_save_failure_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun EditorPanelSaveFailurePreview() {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            StepNameFormContent(EditorPendingForm(EditorFormKind.NAME,
                title = "核对活动日期、联系方式与参与人数，再确认下一步",
                description = ("长讲解仍保留在本机暂存中。正式保存未完成时，可以检查输入并重试。\n").repeat(10)),
                true, DraftRecoveryStatus.STAGED, {}, {}, {}, { _, _ -> },
                message = "保存步骤未完成，请检查本机存储后重试。未保存的修改仍保留", canUndoEdit = true)
        }
    }
}
