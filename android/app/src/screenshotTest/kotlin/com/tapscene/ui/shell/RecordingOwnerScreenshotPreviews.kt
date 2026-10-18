package com.tapscene.ui.shell

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.recording.RecordingPhase
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectUiState

/** Actual recording content with an A-owned session while B is selected. Synthetic UI only. */
@PreviewTest
@Preview(name = "recording_other_owner_small", widthDp = 320, heightDp = 800, locale = "zh-rCN", showBackground = true)
@Composable
fun RecordingOtherOwnerSmallPreview() = RecordingOtherOwnerPreview(completed = false)

@PreviewTest
@Preview(name = "recording_other_owner_landscape", widthDp = 740, heightDp = 480, locale = "zh-rCN", showBackground = true)
@Composable
fun RecordingOtherOwnerLandscapePreview() = RecordingOtherOwnerPreview(completed = true)

@Composable
private fun RecordingOtherOwnerPreview(completed: Boolean) {
    val owner = ShellPreviewFixture.project.project.copy(title = "活动报名 · 原录屏项目")
    val current = ShellPreviewFixture.project.copy(project = owner.copy(id = "layout-current-project", title = "预约流程 · 当前项目"))
    TapSceneTheme {
        Surface(color = ShellColors.Background) {
            RecordingCaptureContent(
                projectState = ProjectUiState(project = current, projects = listOf(owner, current.project), route = ProjectRoute.STEPS),
                recording = ShellPreviewFixture.recording.copy(
                    phase = if (completed) RecordingPhase.Completed else RecordingPhase.Interrupted,
                    canRetry = !completed, error = null,
                    stopReason = if (completed) com.tapscene.recording.RecordingStopReason.User else ShellPreviewFixture.recording.stopReason),
                localMessage = null, preparingConsent = false,
                callbacks = RecordingCaptureCallbacks(
                    onBack = {}, onNewProject = {}, onStart = {}, onStopAndOrganize = {},
                    onRetry = {}, onDiscard = {}, onCandidates = {}, onImportVideo = {}, onClickPlan = {},
                ),
            )
        }
    }
}
