package com.tapscene.ui.shell

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.ui.ProjectWorkspace

/** Actual delivery preflight for a reachable loop that has no route to an ending. */
@PreviewTest
@Preview(name = "ending_route_small", widthDp = 320, heightDp = 640, locale = "zh-rCN", showBackground = true)
@Preview(name = "ending_route_landscape", widthDp = 740, heightDp = 480, locale = "zh-rCN", showBackground = true)
@Composable
fun DeliveryEndingRoutePreview() {
    val original = ShellPreviewFixture.authoredProject
    val first = original.steps[0].copy(hotspots = emptyList())
    val second = original.steps[1].copy(nextAction = null,
        hotspots = original.steps[1].hotspots.map { it.copy(targetStepId = first.id) })
    val snapshot = original.copy(project = original.project.copy(stepCount = 2), steps = listOf(first, second))
    TapSceneTheme {
        Surface(color = ShellColors.Background) {
            DeliveryCheckScreen(snapshot, ProjectWorkspace.graphIssues(snapshot), {}, {}, {}, {}, {},
                onBuildCandidate = {})
        }
    }
}
