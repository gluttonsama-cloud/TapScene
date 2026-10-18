package com.tapscene.ui.shell

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.HostSqlite
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.ReleasePlayback
import com.tapscene.ui.ReleaseUiState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Production player Compose with in-memory synthetic image/data; no media IO or device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w740dp-h480dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReleasePlayerFeedbackHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() { resetApplicationViewModelFactory(); HostSqlite.configure() }
        override fun after() { resetApplicationViewModelFactory() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun newCancellationFeedbackResetsScrolledLandscapeActionsAndKeepsRestartReachable() {
        val scene = scene()
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(34, 72, 96)) }
        val state = mutableStateOf(ReleaseUiState(playerBitmap = bitmap, player = ReleasePlayback(
            scene, id("candidate"), scene.startStateId, listOf(scene.startStateId))))
        var restarts = 0
        var selectedActions = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                TapSceneTheme {
                    ReleasePlayerContent(state.value, onBack = {}, onTap = { _, _ -> },
                        onEdge = { selectedActions++ }, onPrevious = {}, onRestart = { restarts++ }, onDismissMatches = {})
                }
            }
        }
        try {
            compose.waitForIdle()
            compose.onRoot().assertWidthIsEqualTo(740.dp).assertHeightIsEqualTo(480.dp)
            val actions = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            actions.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.waitForIdle()
            val before = actions.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("The action panel must really overflow", before.maxValue() > 0f)
            assertEquals(before.maxValue(), before.value(), 1f)
            compose.onNodeWithText(scene.edges.first().label).assertIsNotDisplayed()
            compose.onNodeWithText(scene.edges.last().label).assertIsDisplayed()

            val feedback = "已取消处理，保留当前画面；请点重来后继续试走。"
            compose.runOnIdle { state.value = state.value.copy(message = feedback) }
            compose.waitForIdle()
            // No scroll-to-feedback action: production must reveal the new recovery message itself.
            compose.onNodeWithText(feedback).assertIsDisplayed()
            val after = actions.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(0f, after.value(), 1f)
            compose.onNodeWithText("重来").assertIsDisplayed().assertIsEnabled().performClick()
            assertEquals(1, restarts)
            assertEquals(0, selectedActions)
            println("HOST_PLAYER_FEEDBACK landscape-740x480/scrolled-action-panel/new-message/restart PASS; device/media IO NOT_RUN")
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            bitmap.recycle()
        }
    }

    private fun scene(): ViewerScene {
        val start = id("start")
        val end = id("end")
        val image = id("image")
        val hotspots = (1..6).map { index ->
            ViewerScene.Hotspot(id("hotspot-$index"), start, "选项 $index", ViewerScene.Rect(.1, .1, .2, .2))
        }
        val edges = listOf(ViewerScene.Edge(id("continue"), start, end, null, null,
            "继续到下一步，核对当前演示的说明和实际输出", "continue", "authored")) + hotspots.mapIndexed { index, hotspot ->
            ViewerScene.Edge(id("edge-$index"), start, end, null, hotspot.id,
                "文字动作 ${index + 1}：查看这条可选路径的详细说明并核对最终演示结果", "tap", "authored")
        }
        return ViewerScene(id("release"), "取消后的恢复提示", "合成横屏播放器", 1L, start,
            listOf(ViewerScene.State(start, image, 320, 180, "选择演示路径", "合成讲解", "authored", false),
                ViewerScene.State(end, image, 320, 180, "结束", "", "authored", true)),
            edges, hotspots, listOf(ViewerScene.Asset(image, "assets/$image.png", "image/png", 100L,
                "0".repeat(64), 320, 180))).also { ViewerPackageCodec.validateScene(it) }
    }

    private fun id(name: String) = UUID.nameUUIDFromBytes("player-feedback/$name".toByteArray()).toString()
    private fun resetApplicationViewModelFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
