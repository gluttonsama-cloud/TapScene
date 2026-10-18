package com.tapscene.ui.shell

import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.HostSqlite
import com.tapscene.data.ReleaseSummary
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerScene
import com.tapscene.recording.RecordingCoordinator
import com.tapscene.recording.RecordingPhase
import com.tapscene.recording.RecordingUiState
import com.tapscene.ui.AiPackageConfiguration
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ProjectUiState
import com.tapscene.ui.ReleaseUiState
import com.tapscene.ui.ReleaseWorkspace
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Actual Compose with production plan edits and synthetic content; not device/picker evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AiAnnotationInputHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() { resetFactory(); HostSqlite.configure() }
        override fun after() { resetFactory() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val field = "画面标注（最多 240 字）"
    private val visit = id("visit")

    @Test fun pendingTextSurvivesCollapseAndBothBacksButNeverLeaksToAnotherVersion() {
        val state = mutableStateOf(ReleaseUiState(aiConfiguration = configuration(false)))
        var backs = 0
        var exports = 0
        compose.activity.setContent { TapSceneTheme { AiPackageScreen(onBack = { backs++ }, state = state.value, onExport = { exports++ }) } }
        compose.onNodeWithText("调整效果").performScrollTo().performClick()
        compose.onNodeWithText(field).performScrollTo().performTextReplacement("尚未加入的文字")
        compose.onNodeWithText("检查并保存").assertIsNotEnabled().performClick()
        assertEquals(0, exports)
        compose.onNodeWithText("收起效果").performScrollTo().performClick()
        compose.onNodeWithText("1 处标注输入未应用 · 返回检查").performClick()
        compose.onNodeWithText(field).assertIsDisplayed().assertTextContains("尚未加入的文字")
        compose.onNodeWithText("换一个版本").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("标注文字尚未应用").assertIsDisplayed()
        assertEquals(0, backs)
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("标注文字尚未应用").assertIsDisplayed()
        compose.onNodeWithText("放弃标注输入并返回").performClick()
        assertEquals(1, backs)
        compose.onNodeWithText(field).performScrollTo().performTextReplacement("只属于版本 A")
        compose.runOnIdle { state.value = ReleaseUiState(aiConfiguration = configuration(false, "B")) }
        compose.onNodeWithText("调整效果").performScrollTo().performClick()
        compose.onNodeWithText(field).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("检查并保存").assertIsEnabled()
        assertEquals(0, exports)
    }

    @Test fun editingExistingTextRequiresApplyAndEmptyInputNeverMeansDelete() {
        val state = mutableStateOf(ReleaseUiState(aiConfiguration = configuration(true)))
        val original = state.value.aiConfiguration!!.effects.last()
        val neighbor = state.value.aiConfiguration!!.effects.first()
        var exports = 0
        compose.activity.setContent { TapSceneTheme { AiPackageScreen(onBack = {}, state = state.value,
            onApplyAnnotation = { id, text -> state.value = state.value.copy(aiConfiguration = state.value.aiConfiguration!!.withAnnotation(id, text)) },
            onRemoveAnnotation = { id -> state.value = state.value.copy(aiConfiguration = state.value.aiConfiguration!!.withoutAnnotation(id)) },
            onExport = { exports++ }) } }
        compose.onNodeWithText("调整效果").performScrollTo().performClick()
        compose.onNodeWithText(field).performScrollTo().performTextReplacement("更新后的标注")
        compose.onNodeWithText("检查并保存").assertIsNotEnabled()
        compose.onNodeWithText("应用标注文字").performScrollTo().assertIsEnabled().performClick()
        val applied = state.value.aiConfiguration!!.effects.last()
        assertEquals("更新后的标注", applied.text)
        assertEquals(original.startFrame, applied.startFrame)
        assertEquals(original.durationFrames, applied.durationFrames)
        assertEquals(original.rect, applied.rect)
        assertEquals(neighbor, state.value.aiConfiguration!!.effects.first())
        compose.onNodeWithText("检查并保存").assertIsEnabled().performClick()
        compose.onNode(hasText("更新后的标注", substring = true) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("选择保存位置").performClick()
        assertEquals(1, exports)
        compose.onNodeWithText(field).performScrollTo().performTextClearance()
        compose.onNodeWithText("应用标注文字").performScrollTo().assertIsNotEnabled()
        assertEquals("更新后的标注", state.value.aiConfiguration!!.effects.last().text)
        compose.onNodeWithText("还原文字输入").performScrollTo().performClick()
        compose.onNodeWithText(field).performScrollTo().assertTextContains("更新后的标注")
        compose.onNodeWithText(field).performTextClearance()
        compose.onNodeWithText("移除标注").performScrollTo().performClick()
        assertEquals(listOf(neighbor), state.value.aiConfiguration!!.effects)
        compose.onNodeWithText(field).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("检查并保存").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(aiConfiguration = configuration(true).copy(fromDraft = true)) }
        compose.onNodeWithText(field).assertDoesNotExist()
        compose.onNodeWithText("应用标注文字").assertDoesNotExist()
        compose.runOnIdle { val config = configuration(true); state.value = state.value.copy(aiConfiguration = config.copy(effects = config.effects + config.effects.last())) }
        compose.onNodeWithText("此访问包含多条标注，请回草稿逐项编辑后重新封存。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(field).assertDoesNotExist()
        compose.onNodeWithText("移除标注").assertDoesNotExist()
    }

    @Test fun appliedBackAndVersionSelectionPreservePendingText() {
        val versionA = configuration(true)
        val versionB = configuration(false, "B")
        fun summary(config: AiPackageConfiguration) = ReleaseSummary(config.scene.releaseId, config.scene.title,
            "0".repeat(64), 1, 1, 100, "local")
        val state = mutableStateOf(ReleaseUiState(releases = listOf(summary(versionA), summary(versionB)), aiConfiguration = versionA))
        val selectedVersions = mutableListOf<String>()
        var backs = 0
        compose.activity.setContent { TapSceneTheme {
            AiPackageScreen(onBack = { backs++ }, state = state.value,
                onCanvas = { landscape -> state.value = state.value.copy(
                    aiConfiguration = state.value.aiConfiguration!!.copy(
                        width = if (landscape) 1920 else 1080, height = if (landscape) 1080 else 1920),
                    aiConfigurationChanged = true) },
                onChooseRelease = { id ->
                    selectedVersions += id
                    state.value = state.value.copy(aiConfiguration = if (id == versionA.scene.releaseId) versionA else versionB,
                        aiConfigurationChanged = false)
                })
        } }
        compose.onNodeWithText("横屏 16:9").performScrollTo().performClick()
        val applied = state.value.aiConfiguration
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(0, backs)
        compose.onNodeWithText("动画配置尚未确认保存").assertIsDisplayed()
        compose.onNodeWithText("放弃未保存修改并返回").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals(applied, state.value.aiConfiguration)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("动画配置尚未确认保存").assertIsDisplayed()
        compose.onNodeWithText("放弃未保存修改并返回").performClick()
        assertEquals(1, backs)

        compose.onNodeWithText("调整效果").performScrollTo().performClick()
        compose.onNodeWithText(field).performScrollTo().performTextReplacement("版本 A 尚未应用的新文字")
        compose.onNodeWithText("换一个版本").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("版本 A").performScrollTo().performClick()
        assertEquals(emptyList<String>(), selectedVersions)
        assertEquals(applied, state.value.aiConfiguration)
        compose.onNodeWithText(field).performScrollTo().assertTextContains("版本 A 尚未应用的新文字")
        compose.onNodeWithText("检查并保存").assertIsNotEnabled()

        compose.onNodeWithText("换一个版本").performScrollTo().performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(field).performScrollTo().assertTextContains("版本 A 尚未应用的新文字")
        assertEquals(1, backs)
        assertEquals(emptyList<String>(), selectedVersions)
        compose.onNodeWithText("换一个版本").performScrollTo().performClick()
        compose.onNodeWithText("版本 B").performScrollTo().performClick()
        compose.onNodeWithText("动画配置尚未确认保存").assertIsDisplayed()
        compose.onNodeWithText("将打开：版本 B\n版本 ID：${versionB.scene.releaseId}").assertIsDisplayed()
        assertEquals(emptyList<String>(), selectedVersions)
        compose.runOnIdle { state.value = state.value.copy(releases = listOf(summary(versionA))) }
        compose.onNodeWithText("该版本已不可用，请返回重新选择。\n版本 ID：${versionB.scene.releaseId}").assertIsDisplayed()
        compose.onNodeWithText("放弃未保存修改并换版本").assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), selectedVersions)
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals(applied, state.value.aiConfiguration)
        compose.onNodeWithText(field).performScrollTo().assertTextContains("版本 A 尚未应用的新文字")
        compose.runOnIdle { state.value = state.value.copy(releases = listOf(summary(versionA), summary(versionB))) }
        compose.onNodeWithText("换一个版本").performScrollTo().performClick()
        compose.onNodeWithText("版本 B").performScrollTo().performClick()
        compose.onNodeWithText("放弃未保存修改并换版本").performClick()
        assertEquals(listOf(versionB.scene.releaseId), selectedVersions)
        assertEquals(versionB, state.value.aiConfiguration)
        compose.onNodeWithText("调整效果").performScrollTo().performClick()
        compose.onNodeWithText(field).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("检查并保存").assertIsEnabled()

        // Raw-only input can also browse versions; choosing another version uses the same
        // explicit destination confirmation, while leaving the editor keeps its raw-only label.
        compose.onNodeWithText(field).performTextReplacement("版本 B 仍待应用")
        compose.onNodeWithText("换一个版本").performScrollTo().performClick()
        compose.onNodeWithText("版本 A").performScrollTo().performClick()
        compose.onNodeWithText("动画配置尚未确认保存").assertIsDisplayed()
        compose.onNodeWithText("将打开：版本 A\n版本 ID：${versionA.scene.releaseId}").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals(listOf(versionB.scene.releaseId), selectedVersions)
        assertEquals(versionB, state.value.aiConfiguration)
        compose.onNodeWithText(field).performScrollTo().assertTextContains("版本 B 仍待应用")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("标注文字尚未应用").assertIsDisplayed()
        compose.onNode(hasText("下次打开以本机已保留配置为准", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("放弃标注输入并返回").performClick()
        assertEquals(2, backs)
    }

    @Test fun busyBackNeverLeavesWhileCleanAndDraftBoundConfigurationsCanReturn() {
        val config = configuration(true)
        val state = mutableStateOf(ReleaseUiState(aiConfiguration = config, aiConfigurationChanged = true,
            busy = true, stage = "准备动画包"))
        var backs = 0
        compose.activity.setContent { TapSceneTheme {
            // The shell's ordinary Back is available only when the AI page has no guard.
            BackHandler { backs++ }
            AiPackageScreen(onBack = { backs++ }, state = state.value)
        } }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("正在处理动画包，请等待完成；如需中止，请使用任务的取消。").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(0, backs)
        assertEquals(true, state.value.busy)
        assertEquals("准备动画包", state.value.stage)
        assertEquals(config, state.value.aiConfiguration)
        compose.onNodeWithText("动画配置尚未确认保存").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(busy = false, stage = null, aiConfigurationChanged = false) }
        compose.onNodeWithText("正在处理动画包，请等待完成；如需中止，请使用任务的取消。").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, backs)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(2, backs)
        compose.runOnIdle { state.value = state.value.copy(aiConfiguration = config.copy(fromDraft = true), aiConfigurationChanged = true) }
        compose.onNodeWithText("横屏 16:9").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(4, backs)
        compose.onNodeWithText("动画配置尚未确认保存").assertDoesNotExist()
        compose.onNodeWithText("标注文字尚未应用").assertDoesNotExist()
    }

    @Test fun shellRecordingCoverRetainsInputButVersionReplacementAndFullExitClearIt() {
        // Keep MainActivity's real TapSceneApp. Only its data and recording status are synthetic;
        // no MediaProjection consent, recording service, captured media or device behavior is tested.
        compose.waitForIdle()
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        @Suppress("UNCHECKED_CAST") val releaseState = ReleaseWorkspace::class.java.getDeclaredField("mutableState")
            .apply { isAccessible = true }.get(releases) as MutableStateFlow<ReleaseUiState>
        @Suppress("UNCHECKED_CAST") val projectState = ProjectWorkspace::class.java.getDeclaredField("mutableState")
            .apply { isAccessible = true }.get(projects) as MutableStateFlow<ProjectUiState>
        @Suppress("UNCHECKED_CAST") val recordingState = RecordingCoordinator::class.java.getDeclaredField("mutableState")
            .apply { isAccessible = true }.get(null) as MutableStateFlow<RecordingUiState>
        val recordingGate = RecordingCoordinator::class.java.getDeclaredField("gate")
            .apply { isAccessible = true }.get(null) as Mutex
        val stopRequest = RecordingCoordinator::class.java.getDeclaredField("stopRequest").apply { isAccessible = true }
        val serviceOwner = RecordingCoordinator::class.java.getDeclaredField("owner").apply { isAccessible = true }
        fun settled() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !releases.state.value.busy && !projects.state.value.busy
        }
        settled()
        val originalReleaseState = releaseState.value
        val originalProjectState = projectState.value
        val originalStopRequest = stopRequest.get(null)
        val owner = RecordingUiState(phase = RecordingPhase.Recording, sessionId = id("recording-session"),
            projectId = id("recording-project"), sourceId = id("recording-source"), elapsedMs = 1_000)
        // Serialize with startup recovery so a delayed recovery cannot overwrite this fixture.
        val originalRecording = runBlocking { recordingGate.withLock {
            recordingState.value.also { recordingState.value = owner }
        } }
        fun assertRecordingUnchanged(expected: RecordingUiState = owner) {
            compose.runOnIdle {
                assertEquals(expected.phase, RecordingCoordinator.state.value.phase)
                assertEquals(owner.sessionId, RecordingCoordinator.state.value.sessionId)
                assertEquals(owner.projectId, RecordingCoordinator.state.value.projectId)
                assertEquals(owner.sourceId, RecordingCoordinator.state.value.sourceId)
                assertEquals(expected, RecordingCoordinator.state.value)
                assertEquals(originalStopRequest, stopRequest.get(null))
                assertNull(serviceOwner.get(null))
                assertNull(shadowOf(compose.activity).nextStartedActivity)
            }
        }
        fun summary(config: AiPackageConfiguration) = ReleaseSummary(config.scene.releaseId, config.scene.title,
            "0".repeat(64), 1, 1, 100, "local")
        fun openFromLibrary(config: AiPackageConfiguration) {
            settled()
            compose.runOnIdle { releaseState.value = releaseState.value.copy(releases = listOf(summary(config)), message = null) }
            compose.onNodeWithText("配置 AI 数据包").performScrollTo().performClick()
            compose.onNodeWithText("AI 动画配置").assertIsDisplayed()
            // The real button starts openAiPackage. Let its missing-fixture read finish before
            // injecting the synthetic plan; never replace a configuration while loading is busy.
            settled()
            compose.runOnIdle {
                check(!releaseState.value.busy)
                releaseState.value = releaseState.value.copy(releases = listOf(summary(config)), aiConfiguration = config,
                    aiConfigurationChanged = false, message = null)
            }
        }
        fun returnFromTaskWithoutDiscardingAi() {
            // The task status is synthetic; navigation and its “回到项目” callback are real.
            val before = projectState.value
            compose.runOnIdle { projectState.value = before.copy(busy = true, stage = "导航保护测试中的本机处理") }
            compose.onNodeWithText("详情").performClick()
            compose.onNodeWithText("任务详情").assertIsDisplayed()
            compose.onNodeWithText("AI 动画配置").assertDoesNotExist()
            compose.onNodeWithText("停止").assertIsEnabled()
            compose.onNodeWithText("回到项目").performScrollTo().performClick()
            compose.onNodeWithText("AI 动画配置").assertIsDisplayed()
            compose.onNodeWithText("任务详情").assertDoesNotExist()
            assertEquals("请先处理 AI 配置修改，再回到项目；可检查并保存，或返回时明确放弃未保存修改。", releaseState.value.message)
            assertEquals(true, projectState.value.busy)
            assertEquals("导航保护测试中的本机处理", projectState.value.stage)
            assertRecordingUnchanged()
            compose.runOnIdle { projectState.value = before }
        }
        try {
            compose.onNodeWithText("演示库").performClick()
            settled()
            val versionA = configuration(false)
            val versionB = configuration(false, "B")
            assertEquals(versionA.visits.single().visitId, versionB.visits.single().visitId)
            openFromLibrary(versionA)
            compose.onNodeWithText("调整效果").performScrollTo().performClick()
            compose.onNodeWithText(field).performScrollTo().performTextReplacement("版本 A 尚未应用")
            compose.onNodeWithText("检查并保存").assertIsNotEnabled()

            compose.onNodeWithText("查看").performClick()
            compose.onNodeWithText("录制操作").assertIsDisplayed()
            compose.onNodeWithText("AI 动画配置").assertDoesNotExist()
            assertRecordingUnchanged()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("AI 动画配置").assertIsDisplayed()
            compose.onNodeWithText(field).performScrollTo().assertTextContains("版本 A 尚未应用")
            compose.onNodeWithText("检查并保存").assertIsNotEnabled()
            assertRecordingUnchanged()

            // A raw-only guard must survive a covering task even with no applied change flag.
            assertEquals(false, releaseState.value.aiConfigurationChanged)
            returnFromTaskWithoutDiscardingAi()
            compose.onNodeWithText(field).performScrollTo().assertTextContains("版本 A 尚未应用")
            assertEquals(versionA, releaseState.value.aiConfiguration)
            compose.onNodeWithText("检查并保存").assertIsNotEnabled()

            // Exercise the independent applied-change guard through the production canvas action.
            compose.onNodeWithText("还原文字输入").performScrollTo().performClick()
            compose.onNodeWithText("横屏 16:9").performScrollTo().performClick()
            assertEquals(true, releaseState.value.aiConfigurationChanged)
            val editedA = releaseState.value.aiConfiguration
            returnFromTaskWithoutDiscardingAi()
            assertEquals(editedA, releaseState.value.aiConfiguration)
            compose.onNodeWithText(field).performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithText("检查并保存").assertIsEnabled()
            compose.onNodeWithText(field).performTextReplacement("版本 A 尚未应用")

            compose.onNodeWithText("查看").performClick()
            compose.onNodeWithText("录制操作").assertIsDisplayed()
            compose.onNodeWithText("停止并整理").performScrollTo().assertIsEnabled()
            assertRecordingUnchanged()
            // Model an already completed session only to reach the actual ready-to-organize
            // callback. No stop, service, captured file, system consent or device is exercised.
            val completedOwner = owner.copy(phase = RecordingPhase.Completed)
            runBlocking { recordingGate.withLock { recordingState.value = completedOwner } }
            compose.onNodeWithText("整理候选步骤").performScrollTo().performClick()
            compose.onNodeWithText("AI 动画配置").assertIsDisplayed()
            compose.onNodeWithText("录制操作").assertDoesNotExist()
            assertEquals("录屏已保存在本机。请先处理 AI 配置修改，再从所属项目的素材页整理录屏。", releaseState.value.message)
            assertEquals(editedA, releaseState.value.aiConfiguration)
            compose.onNodeWithText(field).performScrollTo().assertTextContains("版本 A 尚未应用")
            compose.onNodeWithText("检查并保存").assertIsNotEnabled()
            assertEquals(originalProjectState.project, projectState.value.project)
            assertRecordingUnchanged(completedOwner)
            runBlocking { recordingGate.withLock { recordingState.value = owner } }

            compose.onNodeWithText("查看").performClick()
            compose.onNodeWithText("录制操作").assertIsDisplayed()
            compose.runOnIdle {
                check(!releaseState.value.busy)
                releaseState.value = releaseState.value.copy(releases = listOf(summary(versionB)), aiConfiguration = versionB,
                    aiConfigurationChanged = false, message = null)
            }
            compose.onNodeWithText("录制操作").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("AI 动画配置").assertIsDisplayed()
            compose.onNodeWithText("版本 B").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("调整效果").performScrollTo().performClick()
            compose.onNodeWithText(field).performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithText("检查并保存").assertIsEnabled()
            assertRecordingUnchanged()

            compose.onNodeWithText(field).performTextReplacement("版本 B 离开时放弃")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("标注文字尚未应用").assertIsDisplayed()
            compose.onNodeWithText("放弃标注输入并返回").performClick()
            compose.onNodeWithText("AI 动画配置").assertDoesNotExist()
            compose.onNodeWithText("配置 AI 数据包").performScrollTo().assertIsDisplayed()
            openFromLibrary(versionB)
            compose.onNodeWithText("调整效果").performScrollTo().performClick()
            compose.onNodeWithText(field).performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithText("检查并保存").assertIsEnabled()
            compose.onNodeWithText("1 处标注输入未应用 · 返回检查").assertDoesNotExist()
            assertRecordingUnchanged()
        } finally {
            try { compose.runOnIdle { releaseState.value = originalReleaseState; projectState.value = originalProjectState } }
            finally { runBlocking { recordingGate.withLock {
                recordingState.value = originalRecording
                stopRequest.set(null, originalStopRequest)
            } } }
        }
    }

    private fun configuration(annotation: Boolean, release: String = "A"): AiPackageConfiguration {
        val asset = ViewerScene.Asset(id("asset"), "assets/${id("asset")}.png", "image/png", 100, "0".repeat(64), 32, 48)
        val step = ViewerScene.State(id("step"), asset.id, 32, 48, "核对信息", "", "imported", true)
        val scene = ViewerScene(id(release), "版本 $release", "", 1, step.id, listOf(step), emptyList(), emptyList(), listOf(asset))
        val focus = RenderPlan.Effect("focus", visit, 0, 120, null, null, null, ViewerScene.Rect(.2, .2, .5, .5))
        val note = RenderPlan.Effect("annotation", visit, 10, 60, null, null, "原标注", ViewerScene.Rect(.12, .65, .7, .2))
        return AiPackageConfiguration(scene, listOf(RenderPlan.Visit(visit, step.id, null, 120)), if (annotation) listOf(focus, note) else listOf(focus))
    }
    private fun id(name: String) = UUID.nameUUIDFromBytes("annotation-$name".toByteArray()).toString()
    private fun resetFactory() { ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply { isAccessible = true }.set(null, null) }
}
