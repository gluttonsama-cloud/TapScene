package com.tapscene.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.ui.ProjectIssue
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectUiState

@PreviewTest
@Preview(name = "01_projects_empty", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun ProjectsEmptyPreview() {
    ReviewSurface(sample = false) {
        ProjectHomeFrame(
            onRecord = {}, onImportVideo = {}, onSettings = {}, onLibrary = {},
        ) {
            ProjectHomeContent(ProjectUiState(), onOpenProject = {}, onRenameProject = {}, onDeleteProject = {})
        }
    }
}

@PreviewTest
@Preview(name = "02_storyboard_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun StoryboardSamplePreview() {
    val images = remember { List(3, ShellPreviewFixture::bitmap) }
    ReviewSurface(sample = true) {
        SampleProjectFrame(ProjectTab.STEPS) {
            StoryboardContent(
                state = ProjectUiState(project = ShellPreviewFixture.project, route = ProjectRoute.STEPS),
                onOpenStep = {}, onAddSource = {}, onSetStart = {}, onMoveStep = { _, _ -> }, onDeleteStep = {},
                stepThumbnail = { asset ->
                    images[ShellPreviewFixture.project.steps.indexOfFirst { it.asset.id == asset.id }]
                },
            )
        }
    }
}

@PreviewTest
@Preview(name = "03_editor_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun EditorSamplePreview() {
    val bitmap = remember { ShellPreviewFixture.bitmap(1) }
    ReviewSurface(sample = true) {
        EditorWorkspaceContent(
            project = ShellPreviewFixture.project,
            draft = ShellPreviewFixture.draft,
            bitmap = bitmap,
            busy = false,
            callbacks = EditorCallbacks(
                onBack = {}, onTitleChange = {}, onDescriptionChange = {}, onTerminalChange = {},
                onPutHotspot = {}, onRemoveHotspot = {}, onSave = {}, onDiscard = {},
                onPreview = {}, onOpenTransition = {},
            ),
        )
    }
}

@PreviewTest
@Preview(name = "04_delivery_check_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun DeliveryCheckSamplePreview() {
    ReviewSurface(sample = true) {
        SampleProjectFrame(ProjectTab.CHECKS) {
            DeliveryCheckScreen(
                project = ShellPreviewFixture.project,
                issues = listOf(ProjectIssue(ShellPreviewFixture.selectedStep.id, "布局样例：此步骤的一个动作尚未试走。")),
                onBack = {}, onIssue = {}, onPreview = {}, onReview = {}, onDelivery = {},
                showTopBar = false,
            )
        }
    }
}

@PreviewTest
@Preview(name = "05_recording_recovery_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun RecordingRecoverySamplePreview() {
    ReviewSurface(sample = true) {
        RecordingCaptureContent(
            projectState = ProjectUiState(project = ShellPreviewFixture.project, route = ProjectRoute.STEPS),
            recording = ShellPreviewFixture.recording,
            localMessage = null,
            preparingConsent = false,
            callbacks = RecordingCaptureCallbacks(
                onBack = {}, onNewProject = {}, onStart = {}, onStopAndOrganize = {},
                onRetry = {}, onDiscard = {}, onCandidates = {}, onImportVideo = {},
            ),
        )
    }
}

@PreviewTest
@Preview(name = "06_candidates_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun CandidateSelectionSamplePreview() {
    val images = remember { List(3, ShellPreviewFixture::bitmap) }
    ReviewSurface(sample = true) {
        CandidateSelectionContent(
            state = ShellPreviewFixture.candidateState,
            sources = ShellPreviewFixture.sources,
            sourceReady = true,
            remainingSteps = 37,
            callbacks = CandidateSelectionCallbacks(
                onSource = {}, onReview = {}, onBack = {}, onImport = {},
                onAnalyze = {}, onCancel = {}, onSetDecision = { _, _ -> }, onSetDecisions = { _, _ -> },
            ),
            candidateThumbnail = { candidate ->
                images[ShellPreviewFixture.candidateState.candidates.indexOfFirst { it.id == candidate.id }]
            },
        )
    }
}

@Composable
private fun SampleProjectFrame(tab: ProjectTab, content: @Composable () -> Unit) {
    ProjectWorkspaceFrame(
        title = ShellPreviewFixture.project.project.title,
        selectedTab = tab,
        onTab = {}, onBack = {}, onPreview = {}, onMore = {},
        content = content,
    )
}

/** The test-only label is outside the unchanged production frame and page content. */
@Composable
private fun ReviewSurface(sample: Boolean, content: @Composable () -> Unit) {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                if (sample) {
                    Text(
                        "布局样例 · 合成内容，仅供界面检查",
                        modifier = Modifier.fillMaxWidth().background(ShellColors.AccentSoft)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = ShellColors.Accent,
                    )
                }
            }
        }
    }
}
