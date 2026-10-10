package com.tapscene.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.EditorFormKind
import com.tapscene.data.EditorPendingForm
import com.tapscene.data.TextRegionSourceBinding
import com.tapscene.ocr.OcrResult
import com.tapscene.ocr.OcrWord
import com.tapscene.ocr.TextRegionPolicy
import com.tapscene.ui.DraftRecoveryStatus
import com.tapscene.ui.StepEditDraft
import com.tapscene.ui.TextRegionSuggestions

/** Production views with synthetic layout-only OCR input; no claim of device recognition. */
private object TextRegionLayout {
    private fun id(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
    val project = ShellPreviewFixture.project.let { sample -> sample.copy(
        project = sample.project.copy(id = id(1), startStepId = id(2)),
        steps = sample.steps.mapIndexed { index, step -> step.copy(id = id(index + 2),
            asset = step.asset.copy(id = id(index + 10)), hotspots = emptyList()) }) }
    val step = project.steps[1]
    val binding = step.safeImageBinding(project.project)
    val source = TextRegionSourceBinding(binding.projectId, binding.stepId, binding.assetId,
        binding.sha256, binding.width, binding.height)
    val candidates = TextRegionPolicy.suggestions(OcrResult(480, 840, listOf(
        OcrWord("SAT / 14:00", 60, 315, 196, 336, 96f, 0, 0),
        OcrWord("Continue", 173, 687, 285, 714, 96f, 1, 0)), false))
    val draft = StepEditDraft(step.id, step.title, step.description, false, emptyList())
    val form = EditorPendingForm(EditorFormKind.HOTSPOT, id(20), id(21), label = "确认报名",
        left = "36.041668", top = "81.78571", right = "59.375", bottom = "85",
        endsDemo = false, targetStepId = null, textRegionSource = source)
}

@PreviewTest
@Preview(name = "text_region_suggestions_small", widthDp = 360, heightDp = 740, locale = "zh-rCN", showBackground = true)
@Composable
fun TextRegionSuggestionsSmallPreview() {
    val image = remember { ShellPreviewFixture.bitmap(1) }
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            EditorWorkspaceContent(TextRegionLayout.project, TextRegionLayout.draft, image, false,
                EditorCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
                textRegions = TextRegionSuggestions(TextRegionLayout.binding, TextRegionLayout.candidates),
                initialMode = EditorMode.HOTSPOTS)
        }
    }
}

@PreviewTest
@Preview(name = "text_region_requires_target_small", widthDp = 320, heightDp = 640, locale = "zh-rCN", showBackground = true)
@Composable
fun TextRegionRequiresTargetSmallPreview() = TextRegionFormSurface(false)

@PreviewTest
@Preview(name = "text_region_stale_source_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun TextRegionStaleSourceLandscapePreview() = TextRegionFormSurface(true)

@Composable
private fun TextRegionFormSurface(stale: Boolean) {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize().padding(top = 20.dp), color = MaterialTheme.colorScheme.background) {
            HotspotFormContent(TextRegionLayout.form.copy(targetStepId = if (stale) TextRegionLayout.project.steps.last().id else null),
                null, TextRegionLayout.project.steps, true, true, DraftRecoveryStatus.STAGED, null,
                {}, {}, {}, !stale, true, {}, {}, {})
        }
    }
}
