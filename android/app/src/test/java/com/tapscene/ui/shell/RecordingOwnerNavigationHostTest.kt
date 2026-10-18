package com.tapscene.ui.shell

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.recording.RecordingCoordinator
import com.tapscene.recording.RecordingPhase
import com.tapscene.recording.RecordingUiState
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
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

/** Real MainActivity/navigation/SQLite with synthetic recording phases; no capture or consent claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RecordingOwnerNavigationHostTest {
    private var previousRequestedSize: String? = null
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetFactory()
            HostSqlite.configure()
            HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
            previousRequestedSize = System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        }
        override fun after() {
            try { HostFileSyncShadow.reset() } finally {
                resetFactory()
                val key = "robolectric.windowManager.useRequestedSize"
                previousRequestedSize?.let { System.setProperty(key, it) } ?: System.clearProperty(key)
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recordingOwnerSurvivesProjectSwitchRecoveryCancelAndUnavailableProject() {
        compose.waitForIdle()
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
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
            !projects.state.value.busy && !media.state.value.busy && !releases.state.value.busy
        }
        fun back() {
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            settled()
        }
        fun enterFromMaterials() {
            compose.onNodeWithText("素材").performClick()
            compose.onNodeWithText("其他方式").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("手动录屏").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("录制操作").assertIsDisplayed()
        }
        fun inject(recording: RecordingUiState) {
            // Serialize with startup recovery; no projection, capture owner or stop callback.
            runBlocking { recordingGate.withLock { recordingState.value = recording } }
            compose.waitForIdle()
        }
        fun assertNoCapture(recording: RecordingUiState) = compose.runOnIdle {
            assertEquals(recording, RecordingCoordinator.state.value)
            assertEquals(originalStopRequest, stopRequest.get(null))
            assertNull(serviceOwner.get(null))
            assertNull(shadowOf(compose.activity).nextStartedActivity)
            assertNull(shadowOf(app).nextStartedService)
        }

        try {
            settled()
            val owner = runBlocking(Dispatchers.IO) { store.createProject("录屏来源 A") }
            val current = runBlocking(Dispatchers.IO) { store.createProject("当前项目 B") }
            val ownerId = owner.project.id
            val currentId = current.project.id
            val recording = RecordingUiState(phase = RecordingPhase.Recording,
                sessionId = UUID.nameUUIDFromBytes("recording-owner-session".toByteArray()).toString(),
                projectId = ownerId,
                sourceId = UUID.nameUUIDFromBytes("recording-owner-source".toByteArray()).toString(), elapsedMs = 1_000)
            originalRecording = runBlocking { recordingGate.withLock { recordingState.value } }
            compose.runOnIdle { projects.reload() }
            settled()
            fun openProject(title: String, id: String) {
                compose.onNodeWithText(title).performScrollTo().performClick()
                compose.waitUntil(30_000) {
                    compose.waitForIdle()
                    !projects.state.value.busy && !media.state.value.busy && media.projectId == id
                }
                assertEquals(id, projects.state.value.project?.project?.id)
                assertEquals(ProjectRoute.STEPS, projects.state.value.route)
            }
            fun assertCurrentB() {
                assertEquals(currentId, projects.state.value.project?.project?.id)
                assertEquals(ProjectRoute.STEPS, projects.state.value.route)
                assertEquals(currentId, media.projectId)
            }
            fun assertOwner(title: String) {
                compose.onNodeWithText("本次录制所属项目").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText("本次录制来自其他项目，仍保留原所属关系。").assertIsDisplayed()
                // B appears only in the separately labelled current-project action group.
                compose.onNodeWithText(current.project.title).assertDoesNotExist()
                assertCurrentB()
            }
            fun cancelDiscard(title: String, phase: RecordingUiState) {
                val beforeProject = projects.state.value
                val beforeMedia = media.state.value
                compose.onNodeWithText("删除未完成录制").performScrollTo().assertIsEnabled().performClick()
                compose.onNodeWithText("删除未完成录制？").assertIsDisplayed()
                compose.onNodeWithText("所属项目：$title。", substring = true).assertIsDisplayed()
                compose.onNodeWithText("所属项目：${current.project.title}。", substring = true).assertDoesNotExist()
                compose.onNodeWithText("删除此副本").assertIsEnabled()
                compose.onNodeWithText("保留").performClick()
                compose.onNodeWithText("删除未完成录制？").assertDoesNotExist()
                assertEquals(beforeProject, projects.state.value)
                assertEquals(beforeMedia, media.state.value)
                assertNoCapture(phase)
            }

            // Start in A's real recording page, then leave it twice to reach the project home.
            openProject(owner.project.title, ownerId)
            enterFromMaterials()
            inject(recording)
            compose.onNodeWithText("本次录制所属项目").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(owner.project.title).assertIsDisplayed()
            compose.onNodeWithText("本次录制来自其他项目，仍保留原所属关系。").assertDoesNotExist()
            compose.onNodeWithText("停止并整理").performScrollTo().assertIsEnabled()
            back()
            assertEquals(ownerId, projects.state.value.project?.project?.id)
            back()
            assertEquals(ProjectRoute.PROJECTS, projects.state.value.route)
            compose.onNodeWithText("录制中 · ${owner.project.title}").assertIsDisplayed()
            openProject(current.project.title, currentId)
            compose.onNodeWithText("其他项目 · 录制中 · ${owner.project.title}").assertIsDisplayed()
            compose.onNodeWithText("查看").assertIsEnabled().performClick()
            assertOwner(owner.project.title)
            compose.onNodeWithText("停止并整理").performScrollTo().assertIsEnabled()
            assertNoCapture(recording)
            back()
            assertCurrentB()
            compose.onNodeWithText(current.project.title).assertIsDisplayed()

            compose.onNodeWithText("查看").performClick()
            val completed = recording.copy(phase = RecordingPhase.Completed)
            inject(completed)
            assertOwner(owner.project.title)
            compose.onNodeWithText("整理候选步骤").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("以下操作使用当前项目：${current.project.title}").performScrollTo().assertIsDisplayed()
            listOf("悬浮编排", "手动操作并录屏", "导入已有录屏").forEach {
                compose.onNodeWithText(it).performScrollTo().assertIsEnabled()
            }
            assertNoCapture(completed)
            back()
            assertCurrentB()

            // Recovery is reached through B's ordinary material entry, without any global banner.
            for (phase in listOf(RecordingPhase.Failed, RecordingPhase.Interrupted)) {
                val recoverable = recording.copy(phase = phase, canRetry = true)
                inject(recoverable)
                compose.onNodeWithText("查看").assertDoesNotExist()
                enterFromMaterials()
                assertOwner(owner.project.title)
                compose.onNodeWithText("继续检查已录内容").performScrollTo().assertIsEnabled()
                compose.onNodeWithText("以下操作使用当前项目：${current.project.title}").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("手动操作并录屏").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithText("悬浮编排").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithText("导入已有录屏").performScrollTo().assertIsEnabled()
                cancelDiscard(owner.project.title, recoverable)
                back()
                assertCurrentB()
            }
            assertEquals(owner, runBlocking(Dispatchers.IO) { store.readProject(ownerId) })
            assertEquals(current, runBlocking(Dispatchers.IO) { store.readProject(currentId) })

            // Fixture-only deletion makes A genuinely absent from the refreshed project catalog.
            // No recording discard, stop, retry, grant or candidate analysis is invoked.
            runBlocking(Dispatchers.IO) { store.deleteProject(ownerId) }
            compose.runOnIdle { projects.reload() }
            settled()
            assertFalse(projects.state.value.projects.any { it.id == ownerId })
            enterFromMaterials()
            assertOwner("原项目暂不可用")
            compose.onNodeWithText(owner.project.title).assertDoesNotExist()
            cancelDiscard("原项目暂不可用", recording.copy(phase = RecordingPhase.Interrupted, canRetry = true))
            back()
            assertCurrentB()
            assertNull(runBlocking(Dispatchers.IO) { store.readProject(ownerId) })
            assertEquals(current, runBlocking(Dispatchers.IO) { store.readProject(currentId) })
        } finally {
            originalRecording?.let { previous -> runBlocking { recordingGate.withLock {
                recordingState.value = previous
                stopRequest.set(null, originalStopRequest)
            } } }
        }
    }

    private fun resetFactory() { ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply { isAccessible = true }.set(null, null) }
}
