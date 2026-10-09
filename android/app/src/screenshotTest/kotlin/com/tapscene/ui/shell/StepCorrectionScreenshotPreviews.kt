package com.tapscene.ui.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.tapscene.data.SafeImageBinding
import com.tapscene.data.SourceDraft
import com.tapscene.media.DecodedFrame
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.ui.SafeImageCorrectionDraft
import com.tapscene.ui.StepImageCorrection
import com.tapscene.ui.WorkspaceUiState
import java.io.File

/** In-memory layout fixtures only. No source file, persisted replacement, or review is created. */
private object StepCorrectionPreviewFixture {
    val callbacks = CorrectionUiCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {})
    private val step = ShellPreviewFixture.selectedStep
    private val masks = listOf(OpaqueMask(.11f, .31f, .77f, .37f), OpaqueMask(.11f, .47f, .89f, .53f))
    private val draft = SourceDraft(requireNotNull(step.videoOrigin).source, frameTimeUs = 6_233_000L, masks = masks)
    private val binding = SafeImageBinding("00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000002", 1L,
        "00000000-0000-0000-0000-000000000003", "a".repeat(64), step.asset.width, step.asset.height)
    private val correction = StepImageCorrection(
        projectId = binding.projectId,
        stepId = binding.stepId,
        expectedRevision = 1L,
        sessionId = "layout-correction-session",
        title = "填写报名信息",
        regionCount = 2,
        transitionCount = 1,
        safeImageBase = binding,
    )

    fun output(raw: Bitmap): Bitmap = requireNotNull(raw.copy(Bitmap.Config.ARGB_8888, true)).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.BLACK }
        masks.forEach { mask ->
            canvas.drawRect(mask.left * bitmap.width, mask.top * bitmap.height,
                mask.right * bitmap.width, mask.bottom * bitmap.height, paint)
        }
    }

    private val newMask = OpaqueMask(.11f, .63f, .89f, .69f)

    fun augmentedOutput(base: Bitmap): Bitmap = requireNotNull(base.copy(Bitmap.Config.ARGB_8888, true)).also {
        Canvas(it).drawRect(newMask.left * it.width, newMask.top * it.height,
            newMask.right * it.width, newMask.bottom * it.height, Paint().apply { color = Color.BLACK })
    }

    fun safeState(base: Bitmap, output: Bitmap? = null) = WorkspaceUiState(
        drafts = emptyList(), correction = correction,
        safeImageDraft = SafeImageCorrectionDraft(binding, base, listOf(newMask)),
        candidate = output?.let { SafeMediaWriter.CandidateMedia(File("screenshot-only/safe-correction.png"),
            "d".repeat(64), it.width, it.height, "image/png", null) },
        candidateImage = output,
    )

    fun state(raw: Bitmap, output: Bitmap? = null, dense: Boolean = false, missing: Boolean = false, busy: Boolean = false): WorkspaceUiState {
        return WorkspaceUiState(
            drafts = if (missing) listOf(draft.copy(source = draft.source.copy(
                sourceId = "layout-alternate-source", displayName = "补录报名流程 · 布局样例"))) else listOf(draft),
            correction = if (dense) correction.copy(title = "填写报名信息与联系方式，再核对参与日期及人数", regionCount = 12, transitionCount = 8)
                else correction,
            correctionDraft = draft,
            selectedId = draft.source.sourceId,
            frame = if (missing) null else DecodedFrame(raw, draft.frameTimeUs, 1_000L),
            candidate = output?.let { SafeMediaWriter.CandidateMedia(File("screenshot-only/correction.png"),
                "c".repeat(64), it.width, it.height, "image/png", null) },
            candidateImage = output,
            // The workspace record remains readable even when this particular source is gone.
            loadFailed = false,
            busy = busy,
            stage = if (busy) "解码实际帧" else null,
            message = if (missing) "本机原片不可用，可在当前安全画面上追加遮挡。" else null,
        )
    }
}

@PreviewTest
@Preview(name = "22_step_correction_frame", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun StepCorrectionFramePreview() {
    val raw = remember { ShellPreviewFixture.bitmap(1) }
    val safe = remember(raw) { StepCorrectionPreviewFixture.output(raw) }
    StepCorrectionSurface {
        StepImageCorrectionContent(StepCorrectionPreviewFixture.state(raw), safe,
            StepImageCorrectionMode.FRAME, StepCorrectionPreviewFixture.callbacks)
    }
}

@PreviewTest
@Preview(name = "23_step_correction_mask", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun StepCorrectionMaskPreview() {
    val raw = remember { ShellPreviewFixture.bitmap(1) }
    val safe = remember(raw) { StepCorrectionPreviewFixture.output(raw) }
    StepCorrectionSurface {
        StepImageCorrectionContent(StepCorrectionPreviewFixture.state(raw), safe,
            StepImageCorrectionMode.MASK, StepCorrectionPreviewFixture.callbacks)
    }
}

@PreviewTest
@Preview(name = "24_step_correction_actual_review", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun StepCorrectionReviewPreview() {
    val raw = remember { ShellPreviewFixture.bitmap(1) }
    val safe = remember(raw) { StepCorrectionPreviewFixture.output(raw) }
    StepCorrectionSurface {
        StepImageCorrectionContent(StepCorrectionPreviewFixture.state(raw, safe), safe,
            StepImageCorrectionMode.REVIEW, StepCorrectionPreviewFixture.callbacks)
    }
}

@PreviewTest
@Preview(name = "25_step_correction_dense_landscape", widthDp = 915, heightDp = 412, locale = "zh-rCN", showBackground = true)
@Composable
fun StepCorrectionDenseLandscapePreview() {
    val raw = remember { ShellPreviewFixture.bitmap(1) }
    val safe = remember(raw) { StepCorrectionPreviewFixture.output(raw) }
    StepCorrectionSurface {
        StepImageCorrectionContent(StepCorrectionPreviewFixture.state(raw, safe, dense = true), safe,
            StepImageCorrectionMode.REVIEW, StepCorrectionPreviewFixture.callbacks)
    }
}

@PreviewTest
@Preview(name = "26_step_correction_missing_source", widthDp = 915, heightDp = 412, locale = "zh-rCN", showBackground = true)
@Composable
fun StepCorrectionMissingSourcePreview() {
    val raw = remember { ShellPreviewFixture.bitmap(1) }
    val safe = remember(raw) { StepCorrectionPreviewFixture.output(raw) }
    StepCorrectionSurface {
        StepImageCorrectionContent(StepCorrectionPreviewFixture.state(raw, missing = true), safe,
            StepImageCorrectionMode.MASK, StepCorrectionPreviewFixture.callbacks)
    }
}

@PreviewTest
@Preview(name = "27_step_correction_processing", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun StepCorrectionProcessingPreview() {
    val raw = remember { ShellPreviewFixture.bitmap(1) }
    val safe = remember(raw) { StepCorrectionPreviewFixture.output(raw) }
    StepCorrectionSurface {
        StepImageCorrectionContent(StepCorrectionPreviewFixture.state(raw, busy = true), safe,
            StepImageCorrectionMode.FRAME, StepCorrectionPreviewFixture.callbacks)
    }
}

@Composable
private fun StepCorrectionSurface(content: @Composable () -> Unit) {
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

@PreviewTest
@Preview(name = "28_step_correction_narrow", widthDp = 320, heightDp = 640, locale = "zh-rCN", showBackground = true)
@Composable
fun StepCorrectionNarrowPreview() {
    val raw = remember { ShellPreviewFixture.bitmap(1) }
    val safe = remember(raw) { StepCorrectionPreviewFixture.output(raw) }
    StepCorrectionSurface {
        StepImageCorrectionContent(StepCorrectionPreviewFixture.state(raw, dense = true), safe,
            StepImageCorrectionMode.MASK, StepCorrectionPreviewFixture.callbacks)
    }
}


@PreviewTest
@Preview(name = "32_safe_image_append_masks", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun SafeImageAppendMasksPreview() {
    val raw = remember { ShellPreviewFixture.bitmap(1) }
    val base = remember(raw) { StepCorrectionPreviewFixture.output(raw) }
    StepCorrectionSurface {
        StepImageCorrectionContent(StepCorrectionPreviewFixture.safeState(base), base,
            StepImageCorrectionMode.MASK, StepCorrectionPreviewFixture.callbacks)
    }
}

@PreviewTest
@Preview(name = "33_safe_image_actual_review", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun SafeImageActualReviewPreview() {
    val raw = remember { ShellPreviewFixture.bitmap(1) }
    val base = remember(raw) { StepCorrectionPreviewFixture.output(raw) }
    val output = remember(base) { StepCorrectionPreviewFixture.augmentedOutput(base) }
    StepCorrectionSurface {
        StepImageCorrectionContent(StepCorrectionPreviewFixture.safeState(base, output), base,
            StepImageCorrectionMode.REVIEW, StepCorrectionPreviewFixture.callbacks)
    }
}
