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
import com.tapscene.recording.RecordingCoordinator
import com.tapscene.recording.RecordingPhase
import com.tapscene.recording.RecordingUiState
import com.tapscene.ui.AiDraftWorkspace
import com.tapscene.ui.DraftAiEdits
import com.tapscene.ui.DraftAiPlanUiState
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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

    @Config(shadows = [HostFileSyncShadow::class])
    @Test fun shellTaskViewProtectsRawAndDirtyPlansButAllowsCleanDiscardedAndSavedPlans() {
        // Real MainActivity, project storage and plan save. The recording banner is synthetic:
        // this never requests capture consent, starts a service or validates OS stop/organize.
        compose.waitForIdle()
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val drafts = ViewModelProvider(compose.activity)[AiDraftWorkspace::class.java]
        val store = ProjectStore(app)
        @Suppress("UNCHECKED_CAST") val recordingState = RecordingCoordinator::class.java.getDeclaredField("mutableState")
            .apply { isAccessible = true }.get(null) as MutableStateFlow<RecordingUiState>
        val recordingGate = RecordingCoordinator::class.java.getDeclaredField("gate")
            .apply { isAccessible = true }.get(null) as Mutex
        val stopRequest = RecordingCoordinator::class.java.getDeclaredField("stopRequest").apply { isAccessible = true }
        val serviceOwner = RecordingCoordinator::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val originalStopRequest = stopRequest.get(null)
        var originalRecording: RecordingUiState? = null
        fun settled() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !media.state.value.busy && !releases.state.value.busy &&
                !drafts.planState.value.busy && !drafts.planState.value.checking
        }
        HostFileSyncShadow.begin(app.noBackupFilesDir)
        try {
            val snapshot = runBlocking(Dispatchers.IO) {
                val fixture = AiDraftImportFixtures.complete(app)
                val imports = AiDraftImportStore(app)
                val prepared = fixture.zip.inputStream().use { imports.prepare(it) }
                val projectId = checkNotNull(imports.commit(prepared.sessionId, prepared.previewDigest).projectId)
                // Synthetic region review makes this stored plan valid; it is not human review.
                AiDraftImportFixtures.reviewDraftRegions(store, projectId)
                checkNotNull(store.readProject(projectId))
            }
            val projectId = snapshot.project.id
            val saved = runBlocking(Dispatchers.IO) { checkNotNull(store.readDraftAiConfig(projectId)) }
            val firstVisit = saved.visits.first()
            val visitLabel = "访问 1：${snapshot.steps.single { it.id == firstVisit.stateId }.title}"
            val owner = RecordingUiState(phase = RecordingPhase.Recording, sessionId = id("draft-recording-session"),
                projectId = projectId, sourceId = id("draft-recording-source"), elapsedMs = 1_000)
            // Serialize the fixture with startup recovery; no real capture owner is installed.
            originalRecording = runBlocking { recordingGate.withLock {
                recordingState.value.also { recordingState.value = owner }
            } }
            fun assertRecordingUnchanged() = compose.runOnIdle {
                assertEquals(RecordingPhase.Recording, RecordingCoordinator.state.value.phase)
                assertEquals(owner.sessionId, RecordingCoordinator.state.value.sessionId)
                assertEquals(owner, RecordingCoordinator.state.value)
                assertEquals(originalStopRequest, stopRequest.get(null))
                assertNull(serviceOwner.get(null))
                assertNull(shadowOf(compose.activity).nextStartedActivity)
            }
            fun visitRecordingAndReturn() {
                compose.onNodeWithText("查看").assertIsEnabled().performClick()
                compose.onNodeWithText("录制操作").assertIsDisplayed()
                assertRecordingUnchanged()
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText("录制操作").assertDoesNotExist()
                assertRecordingUnchanged()
            }
            fun openPlan() {
                settled()
                compose.onNodeWithText("动画计划").performScrollTo().performClick()
                settled()
                compose.onNodeWithText("访问路径").assertIsDisplayed()
                assertEquals(projectId, drafts.planState.value.project?.project?.id)
                assertFalse(drafts.planState.value.dirty)
                assertTrue(drafts.planState.value.issues.isEmpty())
            }
            fun assertProtected(text: String, expected: DraftAiConfig) {
                compose.onNodeWithText("查看").assertIsNotEnabled().performClick()
                compose.onNodeWithText("停止").assertIsEnabled()
                compose.onNodeWithText("录制操作").assertDoesNotExist()
                compose.onNodeWithText("访问路径").assertIsDisplayed()
                compose.onNodeWithText(field).performScrollTo().assertTextContains(text)
                assertTrue(AiDraftWorkspace.samePlan(expected, checkNotNull(drafts.planState.value.config)))
                assertRecordingUnchanged()
            }

            settled()
            compose.runOnIdle { projects.reload() }
            settled()
            compose.onNodeWithText(snapshot.project.title).performScrollTo().performClick()
            compose.waitUntil(30_000) {
                compose.waitForIdle()
                !projects.state.value.busy && !media.state.value.busy && media.projectId == projectId
            }
            openPlan()
            visitRecordingAndReturn()
            compose.onNodeWithText("访问路径").assertIsDisplayed()
            assertTrue(AiDraftWorkspace.samePlan(saved, checkNotNull(drafts.planState.value.config)))

            compose.onNodeWithText(visitLabel).performScrollTo().performClick()
            compose.onNodeWithText(field).performScrollTo().performTextReplacement("invalid")
            assertProtected("invalid", saved)
            assertFalse(drafts.planState.value.dirty)
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("停留帧数尚未应用").assertIsDisplayed()
            compose.onNodeWithText("放弃输入并返回").performClick()
            settled()
            assertNull(drafts.planState.value.config)
            visitRecordingAndReturn()

            openPlan()
            compose.onNodeWithText(visitLabel).performScrollTo().performClick()
            compose.onNodeWithText(field).performScrollTo().performTextReplacement("150")
            compose.onNodeWithText("应用停留，保留全部效果").performScrollTo().performClick()
            settled()
            assertTrue(drafts.planState.value.dirty)
            assertProtected("150", DraftAiEdits.hold(saved, firstVisit.visitId, 150))
            val beforeDiscard = runBlocking(Dispatchers.IO) { checkNotNull(store.readDraftAiConfig(projectId)) }
            assertTrue(AiDraftWorkspace.samePlan(saved, beforeDiscard))
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("保留动画计划修改？").assertIsDisplayed()
            compose.onNodeWithText("放弃未保存修改并返回").performClick()
            settled()
            assertNull(drafts.planState.value.config)
            visitRecordingAndReturn()

            openPlan()
            assertTrue(AiDraftWorkspace.samePlan(saved, checkNotNull(drafts.planState.value.config)))
            compose.onNodeWithText(visitLabel).performScrollTo().performClick()
            compose.onNodeWithText(field).performScrollTo().assertTextContains(firstVisit.holdFrames.toString())
            compose.onNodeWithText(field).performTextReplacement("180")
            compose.onNodeWithText("应用停留，保留全部效果").performScrollTo().performClick()
            settled()
            assertProtected("180", DraftAiEdits.hold(saved, firstVisit.visitId, 180))
            compose.onNodeWithText("检查并保存计划").assertIsEnabled().performClick()
            settled()
            assertFalse(drafts.planState.value.dirty)
            assertFalse(drafts.planState.value.saveOutcomeUnknown)
            val persisted = runBlocking(Dispatchers.IO) { checkNotNull(store.readDraftAiConfig(projectId)) }
            assertTrue(AiDraftWorkspace.samePlan(DraftAiEdits.hold(saved, firstVisit.visitId, 180), persisted))
            visitRecordingAndReturn()
            compose.onNodeWithText("访问路径").assertIsDisplayed()
            assertTrue(AiDraftWorkspace.samePlan(persisted, checkNotNull(drafts.planState.value.config)))
        } finally {
            try { originalRecording?.let { previous -> runBlocking { recordingGate.withLock {
                recordingState.value = previous
                stopRequest.set(null, originalStopRequest)
            } } } }
            finally { HostFileSyncShadow.reset() }
        }
    }

    private fun resetApplicationViewModelFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
