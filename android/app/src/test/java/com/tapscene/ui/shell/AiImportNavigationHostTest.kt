package com.tapscene.ui.shell

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.AiDraftImportPreview
import com.tapscene.data.AiDraftImportResult
import com.tapscene.data.HostSqlite
import com.tapscene.packageformat.AiDraftImportPolicy
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.AiDraftImportUiState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Production Compose/back dispatch with synthetic callbacks; storage cancellation is tested separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AiImportNavigationHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
        }
        override fun after() { resetApplicationViewModelFactory() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun busyBackPreservesImportAndOnlyExplicitCancelReachesDestructiveCallback() {
        val ready = readyImport()
        val state = mutableStateOf(ready.copy(busy = true, stage = "恢复导入记录"))
        var backs = 0
        var cancellations = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                TapSceneTheme {
                    AiDraftImportContent(state.value, onBack = { backs++ }, onChooseFile = {},
                        onBaseline = {}, onResume = {}, onCommit = {}, onCancel = { cancellations++ },
                        onReadResult = {}, onOpenProject = {}, onNewImport = {}, onImage = {}, onCloseImage = {})
                }
            }
        }
        compose.waitForIdle()

        // Reopening a ready session is a read. Neither kind of Back may discard that session.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(0, backs)
        assertEquals(0, cancellations)
        compose.onNodeWithText("处理完成后可返回，已准备的导入会保留。要停止本次处理，请使用下方取消按钮。")
            .performScrollTo().assertIsDisplayed()

        // Explicit cancellation during work keeps its existing transaction-result reconciliation path.
        compose.onNodeWithText("取消处理并核对结果").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, cancellations)
        assertEquals(0, backs)

        compose.runOnIdle { state.value = ready }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(2, backs)
        assertEquals(1, cancellations)

        // Idle cancellation still asks explicitly; dismissing that question must retain the import.
        compose.onNodeWithText("取消导入").performClick()
        compose.onNodeWithText("取消本次导入？").assertIsDisplayed()
        assertEquals(1, cancellations)
        compose.onNodeWithText("继续核对").performClick()
        compose.onNodeWithText("取消本次导入？").assertDoesNotExist()
        assertEquals(1, cancellations)

        compose.onNodeWithText("取消导入").performClick()
        compose.onNode(hasText("取消导入") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("取消本次导入？").assertDoesNotExist()
        assertEquals(2, cancellations)
        assertEquals(2, backs)

        // Retrying an uncertain read must not hide the only explicit stop action behind disabled buttons.
        compose.runOnIdle { state.value = ready.copy(busy = true, outcomeUnknown = true, stage = "重读导入结果") }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("取消处理并核对结果").assertIsEnabled().performClick()
        assertEquals(3, cancellations)
        assertEquals(2, backs)
        compose.runOnIdle { state.value = state.value.copy(result = ready.result!!.copy(status = "committed")) }
        compose.onNodeWithText("取消处理并核对结果").assertDoesNotExist()
        compose.onNodeWithText("尝试取消").assertDoesNotExist()
        assertEquals(3, cancellations)
    }

    private fun readyImport(): AiDraftImportUiState {
        fun id(name: String) = UUID.nameUUIDFromBytes("ai-import-navigation-$name".toByteArray()).toString()
        val asset = ViewerScene.Asset(id("image"), "assets/${id("image")}.png", "image/png", 100L,
            "0".repeat(64), 32, 48)
        val step = ViewerScene.State(id("step"), asset.id, 32, 48, "核对报名", "", "imported", true)
        val scene = ViewerScene(id("release"), "待导入演示", "", 1, step.id,
            listOf(step), emptyList(), emptyList(), listOf(asset))
        val plan = RenderPlan.build(scene, 1080, 1920,
            listOf(RenderPlan.Visit(id("visit"), step.id, null, 90)), emptyList())
        val preview = AiDraftImportPreview(id("session"), "a".repeat(64), id("project"), scene, plan,
            null, null, emptyList(), emptyList(), "完整合成包", AiDraftImportPolicy.TRUST_NOTICE, 100L)
        return AiDraftImportUiState(sessionId = preview.sessionId, preview = preview,
            result = AiDraftImportResult(preview.sessionId, "ready", null, false))
    }

    private fun resetApplicationViewModelFactory() {
        // Robolectric changes Application between tests while Lifecycle retains its first factory.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
