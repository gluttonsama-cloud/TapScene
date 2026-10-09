package com.tapscene.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.ui.PreviewState
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
            state = ShellPreviewFixture.candidateState.copy(
                ocrStatus = com.tapscene.data.CandidateOcrStatus.COMPLETED,
                ocrCompleted = 2, ocrTotal = 2,
                ocrResults = mapOf(ShellPreviewFixture.candidateState.candidates.first().id to
                    com.tapscene.ocr.OcrResult(480, 840, listOf(
                        com.tapscene.ocr.OcrWord("Choose an activity", 36, 80, 340, 115, 96f, 0, 0),
                    ), false)),
            ),
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

@PreviewTest
@Preview(name = "07_authored_path_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun AuthoredPathSamplePreview() {
    ReviewSurface(sample = true) {
        AuthoredPathScreen(ShellPreviewFixture.authoredProject, null, false, {}, { _, _, _ -> }, {})
    }
}

@PreviewTest
@Preview(name = "08_authored_preview_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun AuthoredTraversalSamplePreview() {
    val bitmap = remember { ShellPreviewFixture.bitmap(0) }
    val project = ShellPreviewFixture.authoredProject
    ReviewSurface(sample = true) {
        DraftPreviewContent(project,
            PreviewState(project.project.id, project.project.revision, project.steps.first().id, listOf(project.steps.first().id)),
            bitmap, false, {}, { _, _ -> }, {}, {}, {}, onNextAction = {})
    }
}

@PreviewTest
@Preview(name = "09_release_review_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun ReleaseReviewSamplePreview() {
    val state = remember { ReleasePreviewFixture.reviewState }
    ReviewSurface(sample = true) {
        ReleaseReviewContent(state, 3L, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@PreviewTest
@Preview(name = "10_offline_library_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun OfflineLibrarySamplePreview() {
    ReviewSurface(sample = true) {
        ReleaseLibraryContent(ReleasePreviewFixture.libraryState, {}, {}, {}, {}, {})
    }
}

@PreviewTest
@Preview(name = "11_offline_player_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun OfflinePlayerSamplePreview() {
    val state = remember { ReleasePreviewFixture.playerState }
    ReviewSurface(sample = true) {
        ReleasePlayerContent(state, {}, { _, _ -> }, {}, {}, {}, {})
    }
}

@PreviewTest
@Preview(name = "12_offline_import_failure_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun OfflineImportFailureSamplePreview() {
    ReviewSurface(sample = true) {
        OfflineImportContent(com.tapscene.ui.ReleaseUiState(message = "布局样例：观看包缺少一张图片，未加入演示库。"), {}, {})
    }
}

/** Production text-controls component with in-memory layout data; no inference claim. */
@PreviewTest
@Preview(name = "13_ocr_controls_sample", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun OcrControlsSamplePreview() {
    val bitmap = remember { ShellPreviewFixture.bitmap(0) }
    val text = remember {
        com.tapscene.ocr.OcrResult(480, 840, listOf(
            com.tapscene.ocr.OcrWord("Choose an activity", 36, 80, 340, 115, 96f, 0, 0),
            com.tapscene.ocr.OcrWord("Weekend workshop", 60, 226, 340, 258, 91f, 1, 1),
        ), false)
    }
    ReviewSurface(sample = true) {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("文字建议", {})
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Image(bitmap.asImageBitmap(), "组件布局合成画面", Modifier.fillMaxWidth().height(290.dp), contentScale = ContentScale.Fit)
                OcrSuggestions(text, "", true, true, {}, {}, initiallyExpanded = true)
            }
        }
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
