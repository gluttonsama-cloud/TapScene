package com.tapscene.ui.shell

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.ProjectNextAction
import com.tapscene.data.ProjectTransition
import com.tapscene.data.TransitionAsset

/** Synthetic metadata only: no clip is decoded or described as reviewed by this layout check. */
@PreviewTest
@Preview(name = "path_transition_impact_small_large_font", widthDp = 320, heightDp = 800, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Preview(name = "path_transition_impact_landscape", widthDp = 740, heightDp = 480, locale = "zh-rCN", showBackground = true)
@Composable
fun AuthoredPathTransitionImpactPreview() {
    val base = ShellPreviewFixture.project
    val transition = ProjectTransition(
        TransitionAsset("layout-transition", "screenshot-only/not-a-real-transition.mp4", "0".repeat(64),
            0L, 480, 840, 1_000_000L),
        base.steps.first().source!!, 0L, 1_000_000L, emptyList(), "layout-review-metadata",
    )
    val namedSteps = base.steps.mapIndexed { index, step ->
        step.copy(title = "核对报名信息与参与人数", isTerminal = false,
            nextAction = if (index == 0) ProjectNextAction("layout-next", "继续", base.steps[1].id, transition) else null)
    }
    val project = base.copy(project = base.project.copy(startStepId = namedSteps[0].id),
        steps = listOf(namedSteps[1], namedSteps[0], namedSteps[2]).mapIndexed { index, step -> step.copy(sortOrder = index) })
    TapSceneTheme {
        Surface(color = ShellColors.Background) {
            AuthoredPathScreen(project, project.steps.map { it.id },
                false, {}, { _, _, _ -> }, {}, initialMarkLastTerminal = false)
        }
    }
}
