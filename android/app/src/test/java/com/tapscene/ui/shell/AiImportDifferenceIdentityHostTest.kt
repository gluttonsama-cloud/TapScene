package com.tapscene.ui.shell

import androidx.activity.compose.setContent
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.AiDraftImportPreview
import com.tapscene.data.AiDraftImportResult
import com.tapscene.data.HostSqlite
import com.tapscene.data.ReleaseSummary
import com.tapscene.packageformat.AiDraftImportPolicy
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.AiDraftImportUiState
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

/** Real comparison policy and production review screen; no import is committed by review actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AiImportDifferenceIdentityHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
        }
        override fun after() { resetApplicationViewModelFactory() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun comparisonSelectionDistinguishesSameTitleVersionsAndKeepsTheFixedSnapshotIdentity() {
        val fixture = fixture()
        val baseline = fixture.baseline
        val digest = ViewerPackageCodec.contentDigest(baseline)
        val first = ReleaseSummary(baseline.releaseId, baseline.title, digest, 1_700_000_000_000L,
            baseline.states.size, 100L, "local")
        val second = first.copy(id = id("another-baseline"), contentDigest = "b".repeat(64))
        val noComparison = fixture.preview.copy(baselineReleaseId = null, baselineTitle = null,
            baselineScene = null, differences = emptyList())
        val state = mutableStateOf(ready(noComparison).copy(baselines = listOf(first, second)))
        val actions = Actions()
        showImport(state, actions)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("选择本机对比版本或不比较"))
        compose.onNodeWithText("选择本机对比版本或不比较").performClick()
        fun choice(id: String) = compose.onNode(hasClickAction() and hasText("版本 ID：$id", substring = true))
        choice(first.id).performScrollTo().assertIsDisplayed().assertIsEnabled()
        choice(second.id).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf(second.id), actions.baselines)
        assertEquals(0, actions.commits)
        compose.runOnIdle { state.value = state.value.copy(outcomeUnknown = true) }
        choice(second.id).assertIsNotEnabled()
        choice(first.id).performScrollTo().assertIsNotEnabled()
        assertEquals(listOf(second.id), actions.baselines)

        // A later list refresh is not the selected comparison snapshot. Its digest must not
        // replace the scene that was already saved inside this import preview's review lease.
        compose.runOnIdle {
            state.value = ready(fixture.preview).copy(baselines = listOf(first.copy(contentDigest = "c".repeat(64))))
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("对比版本 ID：${first.id}"))
        compose.onNodeWithText("对比版本 ID：${first.id}").assertIsDisplayed()
        compose.onNodeWithText("内容 SHA-256：$digest").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("内容 SHA-256：${"c".repeat(64)}").assertDoesNotExist()
        compose.onNodeWithText("确认建立新草稿").assertIsEnabled()
        assertEquals(0, actions.commits)

        compose.runOnIdle { state.value = ready(fixture.preview.copy(baselineScene = fixture.incoming)) }
        compose.onNodeWithText("对比版本快照不可用，无法核对内容摘要。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("内容 SHA-256：${ViewerPackageCodec.contentDigest(fixture.incoming)}").assertDoesNotExist()
    }

    @Test fun reviewUsesEachVersionsNamesOrderAndDeletedHotspotWithoutCommitting() {
        val fixture = fixture()
        val state = mutableStateOf(ready(fixture.preview))
        val actions = Actions()
        showImport(state, actions)
        compose.onNodeWithText("确认建立新草稿").assertIsEnabled()

        // Both lists are from the policy, not hand-authored Difference objects. Each side numbers
        // its own frozen scene, even when the same IDs have new names and a different order.
        assertDifference(fixture.preview, "state", "project", "order", "步骤 · 步骤顺序",
            "步骤 1 · 旧入口\n步骤 2 · 旧完成", "步骤 1 · 新完成\n步骤 2 · 新入口")
        assertDifference(fixture.preview, "text", id("entry"), "title", "文字 · 名称",
            "旧入口", "新入口")
        assertDifference(fixture.preview, "edge", id("primary-edge"), "toStateId", "动作 · 目标步骤",
            "步骤 2 · 旧完成", "步骤 2 · 新入口")
        assertDifference(fixture.preview, "hotspot", id("old-hotspot"), "stateId", "热点 · 所在步骤",
            "步骤 1 · 旧入口", "（无）")
        assertTrue(fixture.incoming.hotspots.none { it.id == id("old-hotspot") })
        assertDifference(fixture.preview, "edge", id("primary-edge"), "hotspotId", "动作 · 绑定热点",
            "步骤 1 · 旧入口 / 热点 2 · 旧提交", "步骤 2 · 新入口 / 热点 1 · 新提交")

        // The exact old/new IDs remain inspectable, but disclosure itself is a read-only action.
        val binding = differencePanel(fixture.preview, "edge", id("primary-edge"), "hotspotId", "动作 · 绑定热点")
        val technical = "分类：edge\n字段：hotspotId\n对象：${id("primary-edge")}\n" +
            "对比版本原值：${id("old-hotspot")}\n导入原值：${id("new-hotspot")}"
        compose.onNodeWithText(technical).assertDoesNotExist()
        binding.onChildren().filterToOne(hasText("展开差异定位")).performScrollTo().performClick()
        compose.onNodeWithText(technical).performScrollTo().assertIsDisplayed()
        assertEquals(0, actions.commits)
        binding.onChildren().filterToOne(hasText("收起差异定位")).performScrollTo().performClick()
        compose.onNodeWithText(technical).assertDoesNotExist()

        compose.onNodeWithText("文字与动作").performClick()
        compose.onNodeWithText("内容摘要").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("选择本机对比版本或不比较"))
        compose.onNodeWithText("选择本机对比版本或不比较").performClick()
        compose.onNodeWithText("○  不比较，只看完整内容").performScrollTo().performClick()
        assertEquals(listOf<String?>(null), actions.baselines)
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, actions.backs)
        assertEquals(0, actions.commits)
    }

    @Test fun missingMismatchedAndIncompleteSnapshotsNeverBorrowTheOtherSidesIdentity() {
        val fixture = fixture()
        val state = mutableStateOf(ready(fixture.preview.copy(baselineScene = null)))
        val actions = Actions()
        showImport(state, actions)
        val unavailable = "对比版本对象信息不可用，标识可展开查看"

        // The incoming release contains the same step IDs with new names. It is deliberately
        // also supplied as a mismatched baseline, so accepting any non-null scene would fail.
        for (baseline in listOf(null, fixture.incoming)) {
            val preview = fixture.preview.copy(baselineScene = baseline)
            compose.runOnIdle { state.value = ready(preview) }
            assertDifference(preview, "edge", id("primary-edge"), "toStateId", "动作 · 目标步骤",
                unavailable, "步骤 2 · 新入口")
            assertDifference(preview, "edge", id("primary-edge"), "hotspotId", "动作 · 绑定热点",
                unavailable, "步骤 2 · 新入口 / 热点 1 · 新提交")
            assertDifference(preview, "state", "project", "order", "步骤 · 步骤顺序",
                unavailable, "步骤 1 · 新完成\n步骤 2 · 新入口")
            // Raw scalar differences do not need identity resolution and must remain visible.
            assertDifference(preview, "text", id("entry"), "title", "文字 · 名称", "旧入口", "新入口")
        }

        // Simulate unavailable referenced objects while retaining the real policy's saved diff.
        // These reduced scenes still validate; neither side may fill its gaps from the other.
        val incomplete = fixture.preview.copy(
            baselineScene = onlyTerminalStep(fixture.baseline, id("entry")),
            scene = onlyTerminalStep(fixture.incoming, id("ending")),
        )
        compose.runOnIdle { state.value = ready(incomplete) }
        assertDifference(incomplete, "edge", id("primary-edge"), "toStateId", "动作 · 目标步骤",
            "对比版本中未找到此步骤，标识可展开查看", "导入内容中未找到此步骤，标识可展开查看")
        assertDifference(incomplete, "edge", id("primary-edge"), "hotspotId", "动作 · 绑定热点",
            "对比版本中未找到此热点，标识可展开查看", "导入内容中未找到此热点，标识可展开查看")
        assertEquals(0, actions.commits)
        assertTrue(actions.baselines.isEmpty())
    }

    private fun assertDifference(preview: AiDraftImportPreview, category: String, subject: String,
        field: String, label: String, before: String, after: String) {
        val panel = differencePanel(preview, category, subject, field, label)
        panel.onChildren().filterToOne(hasText("对比版本：$before")).performScrollTo().assertIsDisplayed()
        panel.onChildren().filterToOne(hasText("导入内容：$after")).performScrollTo().assertIsDisplayed()
    }

    private fun differencePanel(preview: AiDraftImportPreview, category: String, subject: String,
        field: String, label: String): SemanticsNodeInteraction {
        val index = preview.differences.indexOfFirst {
            it.category == category && it.subjectId == subject && it.field == field
        }
        assertTrue("Production compare must emit $category/$subject/$field", index >= 0)
        val heading = "${index + 1}. $label"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(heading))
        return compose.onNodeWithText(heading).assertIsDisplayed().onParent()
    }

    private fun showImport(state: State<AiDraftImportUiState>, actions: Actions) {
        compose.runOnUiThread {
            compose.activity.setContent {
                TapSceneTheme {
                    AiDraftImportContent(state.value, onBack = { actions.backs++ }, onChooseFile = {},
                        onBaseline = { actions.baselines += it }, onResume = {}, onCommit = { actions.commits++ },
                        onCancel = {}, onReadResult = {}, onOpenProject = {}, onNewImport = {},
                        onImage = {}, onCloseImage = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun ready(preview: AiDraftImportPreview) = AiDraftImportUiState(
        sessionId = preview.sessionId, preview = preview,
        result = AiDraftImportResult(preview.sessionId, "ready", null, false),
    )

    private fun fixture(): Fixture {
        val asset = ViewerScene.Asset(id("image"), "assets/${id("image")}.png", "image/png", 100L,
            "0".repeat(64), 32, 48)
        fun step(name: String, title: String, terminal: Boolean) =
            ViewerScene.State(id(name), asset.id, 32, 48, title, "", "imported", terminal)
        val retained = ViewerScene.Hotspot(id("retained-hotspot"), id("entry"), "保留出口",
            ViewerScene.Rect(0.1, 0.1, 0.2, 0.2))
        val old = ViewerScene.Hotspot(id("old-hotspot"), id("entry"), "旧提交",
            ViewerScene.Rect(0.5, 0.1, 0.2, 0.2))
        val replacement = ViewerScene.Hotspot(id("new-hotspot"), id("entry"), "新提交", old.rect)
        val retainedEdge = ViewerScene.Edge(id("retained-edge"), id("entry"), id("ending"), null,
            retained.id, retained.label, "tap", "authored")
        fun primary(hotspot: ViewerScene.Hotspot, destination: String) =
            ViewerScene.Edge(id("primary-edge"), id("entry"), id(destination), null,
                hotspot.id, hotspot.label, "tap", "authored")
        val baseline = ViewerScene(id("baseline-release"), "本机旧版本", "核对旧内容", 1L, id("entry"),
            listOf(step("entry", "旧入口", false), step("ending", "旧完成", true)),
            listOf(retainedEdge, primary(old, "ending")), listOf(retained, old), listOf(asset))
        val incoming = ViewerScene(id("incoming-release"), "外部新版本", "核对新内容", 2L, id("entry"),
            listOf(step("ending", "新完成", true), step("entry", "新入口", false)),
            listOf(retainedEdge, primary(replacement, "entry")), listOf(replacement, retained), listOf(asset))
        ViewerPackageCodec.validateScene(baseline)
        ViewerPackageCodec.validateScene(incoming)
        val plan = RenderPlan.build(incoming, 1080, 1920, listOf(
            RenderPlan.Visit(id("entry-visit"), id("entry"), retainedEdge.id, 90),
            RenderPlan.Visit(id("ending-visit"), id("ending"), null, 90)), emptyList())
        val issues = AiDraftImportPolicy.inspect(incoming)
        assertTrue("Synthetic input must be import-compatible", issues.isEmpty())
        val preview = AiDraftImportPreview(id("session"), "a".repeat(64), id("project"), incoming, plan,
            baseline.releaseId, baseline.title, issues, AiDraftImportPolicy.compare(incoming, baseline),
            AiDraftImportPolicy.summarize(incoming, plan, baseline), AiDraftImportPolicy.TRUST_NOTICE, 100L,
            baselineScene = baseline)
        return Fixture(baseline, incoming, preview)
    }

    private fun onlyTerminalStep(scene: ViewerScene, stepId: String): ViewerScene {
        val source = scene.states.single { it.id == stepId }
        val step = ViewerScene.State(source.id, source.imageAssetId, source.width, source.height,
            source.title, source.description, source.sourceKind, true)
        return ViewerScene(scene.releaseId, scene.title, scene.goal, scene.createdAt, step.id,
            listOf(step), emptyList(), emptyList(), scene.assets.filter { it.id == step.imageAssetId })
            .also { ViewerPackageCodec.validateScene(it) }
    }

    private data class Fixture(val baseline: ViewerScene, val incoming: ViewerScene, val preview: AiDraftImportPreview)
    private class Actions {
        var commits = 0
        var backs = 0
        val baselines = mutableListOf<String?>()
    }
    private fun id(name: String) = UUID.nameUUIDFromBytes("ai-import-difference-$name".toByteArray()).toString()

    private fun resetApplicationViewModelFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
