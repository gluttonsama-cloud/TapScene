package com.tapscene.ui.shell

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.*
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.DraftAiEdits
import com.tapscene.ui.DraftAiPlanUiState
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

/** Production Compose and edits with synthetic data; no device or process-death claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DraftAiHoldInputHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() { resetApplicationViewModelFactory(); HostSqlite.configure() }
        override fun after() { resetApplicationViewModelFactory() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun id(name: String) = UUID.nameUUIDFromBytes(name.toByteArray()).toString()
    private val visitId = id("hold-visit")
    private val field = "停留帧数（1–1800）"
    private fun initial(project: String = "A"): DraftAiPlanUiState {
        val step = ProjectStep(id("step"), "报名确认", "核对信息", 0, true,
            StepAsset(id("asset"), "safe.png", "0".repeat(64), 100, 100, 100),
            StepOrigin.PackageSafeImage(id("import"), id("step"), id("asset"), "0".repeat(64), "imported"),
            emptyList(), emptyList(), id("capture"))
        val snapshot = ProjectSnapshot(ProjectSummary(id(project), "项目 $project", "", 1, 0, 1, step.id), listOf(step))
        val effects = (1..2).map { DraftAiEffect(id("effect-$it"), RenderPlan.Effect("annotation", visitId, 0, 30,
            null, null, "保留效果 $it", ViewerScene.Rect(.1, .1, .5, .2))) }
        return DraftAiPlanUiState(project = snapshot,
            config = DraftAiConfig(1, false, 1080, 1920, listOf(RenderPlan.Visit(visitId, step.id, null, 90)), effects))
    }

    @Test fun pendingHoldSurvivesCollapseAndTabsAndCannotSilentlySaveOldValue() {
        val state = mutableStateOf(initial())
        val effects = state.value.config!!.effects
        val saved = mutableListOf<Int>()
        var backs = 0
        compose.activity.setContent {
            TapSceneTheme { DraftAiPlanContent(state.value, DraftAiPlanCallbacks(
                onBack = { backs++ },
                onCanvas = { state.value = state.value.copy(config = state.value.config!!.copy(width = 1920, height = 1080), dirty = true) },
                onHold = { id, frames -> state.value = state.value.copy(config = DraftAiEdits.hold(state.value.config!!, id, frames), dirty = true) },
                onSave = { saved += state.value.config!!.visits.single().holdFrames }), initialVisitId = visitId) }
        }
        compose.onNodeWithText(field).performScrollTo().performTextReplacement("240")
        compose.onNodeWithText("访问 1：报名确认").performScrollTo().performClick()
        compose.onNodeWithText("访问 1：报名确认").performClick()
        compose.onNodeWithText(field).performScrollTo().assertTextContains("240")
        compose.onNodeWithText("检查并保存计划").assertIsNotEnabled().performClick()
        assertEquals(emptyList<Int>(), saved)
        compose.onNodeWithText("从当前草稿起点重选路径…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("横屏 1920×1080").performScrollTo().performClick()
        compose.onNodeWithText(field).performScrollTo().assertTextContains("240")
        compose.onNodeWithText("在此结束，不选择动作", substring = true).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(field).performScrollTo()
        compose.onNodeWithText(field).performTextClearance()
        compose.onNodeWithText("全部效果").performClick()
        compose.onNodeWithText("1 处停留输入未应用 · 返回检查").performClick()
        compose.onNodeWithText(field).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("应用停留，保留全部效果").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("停留帧数尚未应用").assertIsDisplayed()
        assertEquals(0, backs)
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText(field).performScrollTo().performTextReplacement("240")
        compose.onNodeWithText("应用停留，保留全部效果").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("检查并保存计划").assertIsEnabled().performClick()
        assertEquals(listOf(240), saved)
        assertEquals(effects, state.value.config!!.effects)
    }

    @Test fun pendingInputCanBeExplicitlyDiscardedAndNeverFollowsAnotherProject() {
        val state = mutableStateOf(initial())
        val effects = state.value.config!!.effects
        var backs = 0
        compose.activity.setContent {
            TapSceneTheme { DraftAiPlanContent(state.value, DraftAiPlanCallbacks(onBack = { backs++ }), initialVisitId = visitId) }
        }
        compose.onNodeWithText(field).performScrollTo().performTextReplacement("invalid")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("停留帧数尚未应用").assertIsDisplayed()
        compose.onNodeWithText("放弃输入并返回").performClick()
        assertEquals(1, backs)
        compose.onNodeWithText(field).performScrollTo().performTextReplacement("360")
        compose.runOnIdle { state.value = state.value.copy(dirty = true) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃输入并返回").performClick()
        compose.onNodeWithText("保留动画计划修改？").assertIsDisplayed()
        assertEquals(1, backs)
        assertEquals(effects, state.value.config!!.effects)
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText(field).performScrollTo().assertTextContains("90")
        compose.onNodeWithText(field).performTextReplacement("360")
        compose.runOnIdle { state.value = initial("B") }
        compose.onNodeWithText(field).performScrollTo().assertTextContains("90")
        compose.onNodeWithText("检查并保存计划").assertIsEnabled()
        assertEquals(90, state.value.config!!.visits.single().holdFrames)
    }

    private fun resetApplicationViewModelFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
