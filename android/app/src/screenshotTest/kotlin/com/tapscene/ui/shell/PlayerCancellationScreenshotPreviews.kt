package com.tapscene.ui.shell

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

/** Synthetic current-visit state only; no task cancellation or persistence claim. */
@PreviewTest
@Preview(name = "player_cancelled_small_large_font", widthDp = 320, heightDp = 800, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Preview(name = "player_cancelled_landscape", widthDp = 740, heightDp = 480, locale = "zh-rCN", showBackground = true)
@Composable
fun PlayerCancelledReadPreview() {
    val state = remember {
        val base = ReleasePreviewFixture.playerState
        val player = checkNotNull(base.player)
        base.copy(player = player.copy(candidateId = ReleasePreviewFixture.candidate.id,
            currentStateId = player.scene.states[1].id,
            history = player.scene.states.take(2).map { it.id }),
            playerBitmap = ShellPreviewFixture.bitmap(1),
            message = "已取消处理，保留当前画面；请点重来后继续试走。")
    }
    TapSceneTheme {
        Surface(color = ShellColors.Background) {
            ReleasePlayerContent(state, {}, { _, _ -> }, {}, {}, {}, {})
        }
    }
}
