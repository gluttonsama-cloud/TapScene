package com.tapscene.ui.shell

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.PlaybackOpen
import com.tapscene.data.PlaybackSession
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerTraversal
import com.tapscene.ui.ReleaseUiState

/** Actual choice content with synthetic metadata, not device or process-recovery evidence. */
@PreviewTest
@Preview(name = "playback_resume_small", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun PlaybackResumeSmallPreview() = PlaybackResumePreview(ended = false, unavailable = false)

@PreviewTest
@Preview(name = "playback_resume_ended_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun PlaybackResumeEndedPreview() = PlaybackResumePreview(ended = true, unavailable = false)

@PreviewTest
@Preview(name = "playback_resume_unavailable", widthDp = 320, heightDp = 568, fontScale = 1.5f, locale = "zh-rCN", showBackground = true)
@Composable
fun PlaybackResumeUnavailablePreview() = PlaybackResumePreview(ended = false, unavailable = true)

@Composable
private fun PlaybackResumePreview(ended: Boolean, unavailable: Boolean) {
    val state = remember(ended, unavailable) {
        val scene = ReleasePreviewFixture.scene
        val next = ViewerTraversal.start(scene).advance(scene, scene.edges[0].id)
        val traversal = if (ended) next.advance(scene, scene.edges[2].id) else next
        ReleaseUiState(playbackResume = PlaybackOpen(scene,
            PlaybackSession(scene.releaseId, ViewerPackageCodec.contentDigest(scene), "layout-only-owner"),
            traversal.takeUnless { unavailable }, unavailable))
    }
    TapSceneTheme { Surface(color = ShellColors.Background) {
        ReleasePlayerContent(state, {}, { _, _ -> }, {}, {}, {}, {})
    } }
}
