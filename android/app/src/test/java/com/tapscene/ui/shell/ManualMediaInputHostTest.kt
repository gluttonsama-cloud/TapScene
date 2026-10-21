package com.tapscene.ui.shell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tapscene.MainActivity
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.SourceDraft
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.DecodedFrame
import com.tapscene.media.ImportedSource
import com.tapscene.media.SourceMetadata
import com.tapscene.recording.RecordingCoordinator
import com.tapscene.recording.RecordingPhase
import com.tapscene.recording.RecordingUiState
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.CandidateWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import com.tapscene.ui.WorkspaceUiState
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
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

/** Real shell/storage/PNG output with synthetic source frames; no capture, video decoding or process-death claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManualMediaInputHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetFactory()
            HostSqlite.configure()
            // MainActivity reads the real library before the test body runs.
            HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
        }
        override fun after() { try { HostFileSyncShadow.reset() } finally { resetFactory() } }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @After fun finishReleaseWorkBeforeClosingHostDescriptors() {
        val owner = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
            .viewModelScope.coroutineContext[Job]!!
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        // Cancellation still leaves the production NonCancellable IO reconciliation to finish.
        compose.waitUntil(30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            owner.isCompleted
        }
        compose.waitForIdle()
    }

    @Test fun missingVideoPickerPreservesManualWorkbenchForMenuAndEmptyEntrances() {
        compose.waitForIdle()
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        val candidates = ViewModelProvider(compose.activity)[CandidateWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val projectStore = ProjectStore(app)
        @Suppress("UNCHECKED_CAST") val mediaState = MediaWorkspace::class.java.getDeclaredField("mutableState")
            .apply { isAccessible = true }.get(media) as MutableStateFlow<WorkspaceUiState>
        var missingPicker = true
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(compose.activity, input)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                assertArrayEquals(arrayOf("video/mp4"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
                launches++
                if (missingPicker) throw ActivityNotFoundException("Synthetic missing document picker")
                // A real OpenDocument contract parses a cancelled result to null.
                assertTrue(dispatchResult(requestCode, Activity.RESULT_CANCELED, null))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var frame: Bitmap? = null
        fun settled() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !media.state.value.busy && !releases.state.value.busy
        }
        fun source(name: String): SourceDraft {
            val sourceId = id("picker-$name")
            return SourceDraft(ImportedSource(sourceId, "sources/$sourceId.mp4", "合成 $name.mp4",
                SourceMetadata("video/avc", 1_024, "0".repeat(64), 24, 40, 0, 12_000_000)), 750_000)
        }
        fun openWorkbench(project: ProjectSnapshot) {
            compose.onNodeWithText(project.project.title).performScrollTo().performClick()
            compose.waitUntil(30_000) {
                compose.waitForIdle()
                !projects.state.value.busy && !media.state.value.busy && media.projectId == project.project.id
            }
            compose.onNodeWithText("素材").performClick()
            compose.onNodeWithText("手动校正").performScrollTo().performClick()
            assertEquals(ProjectRoute.MEDIA, projects.state.value.route)
            compose.onNodeWithText("手动校正").assertIsDisplayed()
        }
        fun assertStateUnchanged(before: WorkspaceUiState) {
            compose.runOnIdle {
                assertEquals(before, media.state.value.copy(message = before.message))
                assertNull(shadowOf(compose.activity).nextStartedActivity)
                assertFalse(compose.activity.isFinishing)
            }
        }
        fun assertMissingPicker() {
            compose.onNodeWithText("系统没有可用的文件选择工具。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("导入录屏…").assertDoesNotExist()
        }
        fun importFromMenu() {
            compose.onNodeWithText("素材 ▾").assertIsEnabled().performClick()
            compose.onNodeWithText("导入录屏…").assertIsEnabled().performClick()
        }
        fun capture(name: String) {
            // Actual Compose host view, for review only; not a device capture or golden baseline.
            val root = compose.onRoot().fetchSemanticsNode().root as ViewRootForTest
            compose.runOnIdle {
                val view = root.view
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    val file = File("build/reports/manual-media-picker/$name.png")
                    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        }
        try {
            settled()
            assertFalse(releases.state.value.libraryReadFailed)
            assertNull(releases.state.value.message)
            // Retain the production shell and view models; replace only its external picker transport.
            compose.runOnUiThread {
                compose.activity.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        TapSceneApp(projects, media, candidates, releases)
                    }
                }
            }
            val retainedSource = source("保留")
            val emptyProject = runBlocking(Dispatchers.IO) {
                projectStore.createProject("选择器空态合成项目").also {
                    WorkspaceStore(app, it.project.id).write(listOf(source("仅供移除")))
                }
            }
            val retainedProject = runBlocking(Dispatchers.IO) {
                projectStore.createProject("选择器保留合成项目").also {
                    WorkspaceStore(app, it.project.id).write(listOf(retainedSource))
                }
            }
            compose.runOnIdle { projects.reload() }
            settled()

            // This is the actual empty-workbench route: explicitly remove its last unreferenced,
            // test-owned source. No source file, imported user media or decoder is involved.
            openWorkbench(emptyProject)
            compose.onNodeWithText("素材 ▾").performClick()
            compose.onNodeWithText("删除 App 中的素材副本").performClick()
            compose.onNodeWithText("删除 App 副本").performClick()
            settled()
            val emptyBefore = media.state.value
            assertTrue(emptyBefore.drafts.isEmpty())
            assertNull(emptyBefore.selected)
            assertNull(emptyBefore.candidate)
            compose.onNodeWithText("导入已有录屏").performScrollTo().assertIsEnabled().performClick()
            assertEquals(1, launches)
            assertMissingPicker()
            assertStateUnchanged(emptyBefore)
            capture("empty-picker-unavailable")
            missingPicker = false
            compose.onNodeWithText("导入已有录屏").performScrollTo().assertIsEnabled().performClick()
            assertEquals(2, launches)
            assertStateUnchanged(emptyBefore)
            assertTrue(runBlocking(Dispatchers.IO) { WorkspaceStore(app, emptyProject.project.id).read() }.isEmpty())
            assertEquals(emptyProject, runBlocking(Dispatchers.IO) { projectStore.readProject(emptyProject.project.id) })
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(ProjectRoute.STEPS, projects.state.value.route)
            compose.onNodeWithText("还没有原录屏").assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(ProjectRoute.PROJECTS, projects.state.value.route)

            openWorkbench(retainedProject)
            compose.runOnIdle {
                frame = Bitmap.createBitmap(24, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
                mediaState.value = mediaState.value.copy(frame = DecodedFrame(checkNotNull(frame), 750_000))
            }
            compose.onNodeWithContentDescription("录屏取帧时间").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(6.25f)) }
            compose.onNodeWithText("短片").performClick()
            compose.onNodeWithText("起点（秒）").performScrollTo().performTextReplacement("001.250")
            compose.onNodeWithText("终点（秒）").performScrollTo().performTextReplacement("4.7500")
            compose.onNodeWithText("画面").performClick()
            compose.onNodeWithText("生成画面并复核").performScrollTo().assertIsEnabled().performClick()
            settled()
            compose.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()
            val before = media.state.value
            val output = checkNotNull(before.candidate) { before.message.orEmpty() }
            val outputBytes = output.file.readBytes()
            assertEquals("image/png", output.mimeType)
            assertEquals(output.sha256, before.reviewedDigest)
            assertNotNull(before.candidateImage)
            missingPicker = true
            importFromMenu()
            assertEquals(3, launches)
            assertMissingPicker()
            assertStateUnchanged(before)
            capture("reviewed-output-picker-unavailable")
            missingPicker = false
            importFromMenu()
            assertEquals(4, launches)
            assertStateUnchanged(before)
            compose.onNodeWithText("画面").performClick()
            assertEquals(6.25f, compose.onNodeWithContentDescription("录屏取帧时间").performScrollTo()
                .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .0001f)
            compose.onNodeWithText("短片").performClick()
            compose.onNodeWithText("起点（秒）").performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("001.250")))
            compose.onNodeWithText("终点（秒）").performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("4.7500")))
            assertArrayEquals(outputBytes, output.file.readBytes())
            assertEquals(output.sha256, MessageDigest.getInstance("SHA-256").digest(output.file.readBytes())
                .joinToString("") { "%02x".format(it) })
            assertEquals(listOf(retainedSource), runBlocking(Dispatchers.IO) { WorkspaceStore(app, retainedProject.project.id).read() })
            assertEquals(retainedProject, runBlocking(Dispatchers.IO) { projectStore.readProject(retainedProject.project.id) })
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(ProjectRoute.STEPS, projects.state.value.route)
            compose.onNodeWithText("本机原录屏").assertIsDisplayed()
            assertFalse(compose.activity.isFinishing)
        } finally {
            compose.runOnIdle { mediaState.value = mediaState.value.copy(frame = null) }
            compose.waitForIdle()
            frame?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    @Test fun shellRecordingCoverRetainsRawTimesButSourceSwitchAndFullExitClearThem() {
        // Keep MainActivity's production TapSceneApp, route stack and MediaScreen. No source
        // video is provided: only frame pixels and recording status are synthetic injections.
        compose.waitForIdle()
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val projectStore = ProjectStore(app)
        @Suppress("UNCHECKED_CAST") val mediaState = MediaWorkspace::class.java.getDeclaredField("mutableState")
            .apply { isAccessible = true }.get(media) as MutableStateFlow<WorkspaceUiState>
        @Suppress("UNCHECKED_CAST") val recordingState = RecordingCoordinator::class.java.getDeclaredField("mutableState")
            .apply { isAccessible = true }.get(null) as MutableStateFlow<RecordingUiState>
        val recordingGate = RecordingCoordinator::class.java.getDeclaredField("gate")
            .apply { isAccessible = true }.get(null) as Mutex
        val stopRequest = RecordingCoordinator::class.java.getDeclaredField("stopRequest").apply { isAccessible = true }
        val serviceOwner = RecordingCoordinator::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val originalStopRequest = stopRequest.get(null)
        var originalRecording: RecordingUiState? = null
        val frameBitmaps = mutableListOf<Bitmap>()
        val startField = "起点（秒）"
        val endField = "终点（秒）"
        fun settled() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !media.state.value.busy && !releases.state.value.busy
        }
        fun source(name: String, frameTimeUs: Long, durationUs: Long): SourceDraft {
            val sourceId = id("source-$name")
            return SourceDraft(ImportedSource(sourceId, "sources/$sourceId.mp4", "素材 $name.mp4",
                SourceMetadata("video/avc", 1_024, "0".repeat(64), 24, 40, 0, durationUs)), frameTimeUs)
        }
        try {
            settled()
            assertFalse(releases.state.value.libraryReadFailed)
            assertNull(releases.state.value.message)
            val sourceA = source("A", 750_000, 12_000_000)
            val sourceB = source("B", 500_000, 2_000_000)
            val drafts = listOf(sourceA, sourceB)
            val project = runBlocking(Dispatchers.IO) {
                projectStore.createProject("手动时间输入项目").also {
                    WorkspaceStore(app, it.project.id).write(drafts)
                }
            }
            val projectId = project.project.id
            val owner = RecordingUiState(phase = RecordingPhase.Recording, sessionId = id("recording-session"),
                projectId = projectId, sourceId = id("recording-source"), elapsedMs = 1_000)
            // Serialize with startup recovery. No capture owner or stop callback is installed.
            originalRecording = runBlocking { recordingGate.withLock {
                recordingState.value.also { recordingState.value = owner }
            } }
            fun assertRecordingUnchanged() = compose.runOnIdle {
                assertEquals(owner, RecordingCoordinator.state.value)
                assertEquals(originalStopRequest, stopRequest.get(null))
                assertNull(serviceOwner.get(null))
                assertNull(shadowOf(compose.activity).nextStartedActivity)
                assertNull(shadowOf(app).nextStartedService)
            }
            fun injectFrame() = compose.runOnIdle {
                check(!mediaState.value.busy)
                val selected = checkNotNull(mediaState.value.selected)
                val bitmap = Bitmap.createBitmap(24, 40, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(android.graphics.Color.BLUE) }
                frameBitmaps += bitmap
                mediaState.value = mediaState.value.copy(frame = DecodedFrame(bitmap, selected.frameTimeUs), frameReviewId = null)
            }
            fun assertSlider(seconds: Float) {
                compose.onNodeWithText("画面").performClick()
                val progress = compose.onNodeWithContentDescription("录屏取帧时间").performScrollTo()
                    .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
                assertEquals(seconds, progress.current, .0001f)
            }
            fun setSlider(seconds: Float) {
                compose.onNodeWithText("画面").performClick()
                compose.onNodeWithContentDescription("录屏取帧时间").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(seconds)) }
            }
            fun replace(field: String, value: String) {
                compose.onNodeWithText("短片").performClick()
                val input = compose.onNodeWithText(field).performScrollTo()
                if (value.isEmpty()) input.performTextClearance() else input.performTextReplacement(value)
            }
            fun assertInputs(seconds: Float, start: String, end: String, valid: Boolean) {
                assertSlider(seconds)
                compose.onNodeWithText("短片").assertIsEnabled().performClick()
                compose.onNodeWithText(startField).performScrollTo()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(start)))
                compose.onNodeWithText(endField).performScrollTo()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(end)))
                val generate = compose.onNodeWithText("生成短片并复核").performScrollTo()
                if (valid) generate.assertIsEnabled() else generate.assertIsNotEnabled()
            }
            fun assertCandidateInvalidated() = compose.runOnIdle {
                assertNull(media.state.value.candidate)
                assertNull(media.state.value.candidateImage)
                assertNull(media.state.value.reviewedDigest)
                assertNull(media.state.value.watchedDigest)
            }
            fun generateReviewedPng() {
                compose.onNodeWithText("画面").performClick()
                compose.onNodeWithText("生成画面并复核").performScrollTo().assertIsEnabled().performClick()
                settled()
                val output = checkNotNull(media.state.value.candidate) { media.state.value.message.orEmpty() }
                assertEquals("image/png", output.mimeType)
                assertTrue(output.file.isFile)
                assertNotNull(media.state.value.candidateImage)
                assertNull(media.state.value.reviewedDigest)
                // Synthetic test acknowledgement only; this is not a person's privacy review.
                compose.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()
                assertEquals(output.sha256, media.state.value.reviewedDigest)
            }
            fun visitRecordingAndReturn() {
                val before = media.state.value
                val outputBytes = before.candidate?.file?.readBytes()
                compose.onNodeWithText("查看").assertIsEnabled().performClick()
                compose.onNodeWithText("录制操作").assertIsDisplayed()
                compose.onNodeWithText("手动校正").assertDoesNotExist()
                assertEquals(ProjectRoute.MEDIA, projects.state.value.route)
                assertEquals(projectId, media.projectId)
                assertEquals(before, media.state.value)
                assertRecordingUnchanged()
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText("录制操作").assertDoesNotExist()
                compose.onNodeWithText("手动校正").assertIsDisplayed()
                assertEquals(before, media.state.value)
                outputBytes?.let { assertArrayEquals(it, checkNotNull(media.state.value.candidate).file.readBytes()) }
                assertRecordingUnchanged()
            }
            fun switchSource(draft: SourceDraft, index: Int) {
                compose.onNodeWithText("素材 ▾").performClick()
                compose.onNodeWithText("$index. ${draft.source.displayName}").performClick()
                assertEquals(draft.source.sourceId, media.state.value.selectedId)
                assertNull(media.state.value.frame)
                assertNull(media.state.value.frameReviewId)
                assertCandidateInvalidated()
                injectFrame()
            }

            compose.runOnIdle { projects.reload() }
            settled()
            compose.onNodeWithText(project.project.title).performScrollTo().performClick()
            compose.waitUntil(30_000) {
                compose.waitForIdle()
                !projects.state.value.busy && !media.state.value.busy && media.projectId == projectId
            }
            compose.onNodeWithText("素材").performClick()
            compose.onAllNodesWithText("手动校正").onFirst().performScrollTo().performClick()
            assertEquals(sourceA.source.sourceId, media.state.value.selectedId)
            injectFrame()
            setSlider(6.25f)
            replace(startField, "001.250")
            replace(endField, "4.7500")
            assertInputs(6.25f, "001.250", "4.7500", valid = true)
            generateReviewedPng()
            visitRecordingAndReturn()
            assertInputs(6.25f, "001.250", "4.7500", valid = true)
            assertNotNull(media.state.value.candidate)

            // The raw strings must survive even when they cannot be parsed. Editing either
            // field still invalidates an existing output and its explicit review.
            replace(startField, "invalid")
            assertCandidateInvalidated()
            replace(endField, "")
            setSlider(7.125f)
            visitRecordingAndReturn()
            assertInputs(7.125f, "invalid", "", valid = false)
            assertCandidateInvalidated()
            replace(startField, "2.000")
            replace(endField, "5.000")
            generateReviewedPng()
            replace(endField, "not-a-time")
            assertCandidateInvalidated()
            replace(startField, "")
            setSlider(8.5f)
            visitRecordingAndReturn()
            assertInputs(8.5f, "", "not-a-time", valid = false)

            switchSource(sourceB, 2)
            assertInputs(.5f, "0.000", "2.000", valid = true)
            setSlider(.875f)
            replace(startField, "0.250")
            replace(endField, "1.250")
            visitRecordingAndReturn()
            assertInputs(.875f, "0.250", "1.250", valid = true)
            switchSource(sourceA, 1)
            assertInputs(.75f, "0.000", "3.000", valid = true)

            setSlider(10.25f)
            replace(startField, "")
            replace(endField, "discard on exit")
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(ProjectRoute.STEPS, projects.state.value.route)
            compose.onAllNodesWithText("手动校正").onFirst().performScrollTo().performClick()
            assertEquals(ProjectRoute.MEDIA, projects.state.value.route)
            assertInputs(.75f, "0.000", "3.000", valid = true)
            assertCandidateInvalidated()
            assertRecordingUnchanged()
            assertEquals(drafts, media.state.value.drafts)
            assertFalse(media.state.value.unsavedEdits)
            assertEquals(drafts, runBlocking(Dispatchers.IO) { WorkspaceStore(app, projectId).read() })
            assertEquals(project, runBlocking(Dispatchers.IO) { projectStore.readProject(projectId) })
        } finally {
            originalRecording?.let { previous -> runBlocking { recordingGate.withLock {
                recordingState.value = previous
                stopRequest.set(null, originalStopRequest)
            } } }
            compose.runOnIdle { mediaState.value = mediaState.value.copy(frame = null) }
            compose.waitForIdle()
            frameBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun id(name: String) = UUID.nameUUIDFromBytes("manual-media-$name".toByteArray()).toString()
    private fun resetFactory() { ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply { isAccessible = true }.set(null, null) }
}
