package com.tapscene.ui.shell

import android.graphics.Bitmap
import android.system.Os
import android.system.StructStat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.data.SourceDraft
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.DecodedFrame
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SourceMetadata
import com.tapscene.recording.RecordingCoordinator
import com.tapscene.recording.RecordingPhase
import com.tapscene.recording.RecordingUiState
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import com.tapscene.ui.WorkspaceUiState
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real MainActivity, storage, PNG and recovery UI; decoder pixels and recording state are synthetic.
 * Host scheduling/IO checks do not claim device decoding, power-loss durability or human review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi", shadows = [ManualMaskRecoveryHostTest.CommitBoundaryShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManualMaskRecoveryHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() { resetFactory(); HostSqlite.configure() }
        override fun after() { resetFactory() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun failedSaveAndRereadKeepMasksUntilConfirmedRecoveryThenBackWorks() = withWorkspace { fixture ->
        with(fixture) {
            val frame = injectFrameAndReviewPng()
            val originalBytes = metadata.readBytes()
            // Real updateSource read-before-write failure, not a simulated disk-full/write result.
            // Only this test's synthetic metadata is damaged; no file permissions are changed.
            metadata.writeText("{broken fixture metadata")
            compose.runOnIdle { media.addMask(extraMask) }
            settled()
            assertPending(frame)
            assertNull(media.state.value.candidate)
            assertNull(media.state.value.candidateImage)
            assertNull(media.state.value.reviewedDigest)
            compose.onNodeWithText("生成画面并复核").performScrollTo().assertIsNotEnabled()

            requestReread()
            compose.onNodeWithText("保留当前修改").performClick()
            assertPending(frame)
            compose.onNodeWithText("重新读取遮挡？").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").performClick()
            assertDialog()
            compose.onNodeWithText("保留当前修改").performClick()
            assertPending(frame)

            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertDialog()
            compose.onNodeWithText("重新读取").performClick()
            settled()
            assertPending(frame)
            assertEquals("{broken fixture metadata", metadata.readText())

            // A reused source ID must not authorize loading a different source identity.
            val changedIdentity = JSONObject(originalBytes.toString(Charsets.UTF_8)).apply {
                getJSONArray("sources").getJSONObject(0).put("name", "另一个来源.mp4")
            }.toString()
            metadata.writeText(changedIdentity)
            requestReread()
            compose.onNodeWithText("重新读取").performClick()
            settled()
            assertPending(frame)
            assertEquals(changedIdentity, metadata.readText())

            metadata.writeBytes(originalBytes)
            // A recording refresh must not replace pending masks or select the last source.
            compose.runOnIdle { media.reload() }
            settled()
            assertPending(frame)
            val savedDrafts = completedRecordingHandoff(frame)
            val savedBytes = metadata.readBytes()
            val inode = Files.getAttribute(metadata.toPath(), "unix:ino")
            requestReread()
            compose.onNodeWithText("重新读取").performClick()
            settled()
            assertRecovered(savedDrafts, savedBytes, inode)
            // Recovery itself stays on this screen. Only a subsequent Back leaves it.
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(ProjectRoute.STEPS, projects.state.value.route)
            assertOtherDataUnchanged()
        }
    }

    @Test fun cancellationAfterRealDirectorySyncRereadsCommittedMasksWithoutRollback() = withWorkspace { fixture ->
        with(fixture) {
            val frame = injectFrameAndReviewPng()
            val boundary = CommitBoundaryShadow.arm(metadata, metadata.readBytes())
            try {
                compose.runOnIdle { media.addMask(extraMask) }
                compose.waitUntil(30_000) {
                    compose.waitForIdle()
                    boundary.reached.count == 0L
                }
                // WorkspaceStore still holds its lock. Inspect actual committed bytes directly,
                // not through read(), which correctly waits until the writer releases the lock.
                val committedBytes = metadata.readBytes()
                assertEquals(2, JSONObject(committedBytes.toString(Charsets.UTF_8))
                    .getJSONArray("sources").getJSONObject(0).getJSONArray("masks").length())
                val inode = Files.getAttribute(metadata.toPath(), "unix:ino")
                compose.runOnIdle {
                    assertTrue(media.state.value.busy)
                    media.cancel()
                }
                boundary.release.countDown()
                settled()
                assertPending(frame)
                assertArrayEquals(committedBytes, metadata.readBytes())
                assertEquals(originalDrafts[0].masks + extraMask,
                    WorkspaceStore(app, project.project.id).read().first().masks)

                requestReread()
                compose.onNodeWithText("重新读取").performClick()
                settled()
                val committed = originalDrafts.map {
                    if (it.source.sourceId == selectedId) it.copy(masks = it.masks + extraMask) else it
                }
                assertRecovered(committed, committedBytes, inode)
                compose.onNodeWithContentDescription("返回").performClick()
                assertEquals(ProjectRoute.STEPS, projects.state.value.route)
                assertOtherDataUnchanged()
            } finally { boundary.release.countDown() }
        }
    }

    private fun withWorkspace(checks: (Fixture) -> Unit) {
        compose.waitForIdle()
        val app = RuntimeEnvironment.getApplication()
        HostFileSyncShadow.begin(app.noBackupFilesDir)
        var fixture: Fixture? = null
        try {
            fixture = Fixture()
            checks(fixture)
            // PNG/workspace files use direct FileDescriptor.sync(), outside this Os bridge.
            // Assert only its actual directory fsync calls and zero leaked descriptors.
            HostFileSyncShadow.assertDirectorySyncEvidence()
        } finally {
            CommitBoundaryShadow.releasePending()
            try {
                fixture?.let { current ->
                    compose.runOnIdle { current.media.cancel() }
                    current.settled()
                    compose.runOnIdle { current.state.value = current.state.value.copy(frame = null, candidateImage = null) }
                    compose.waitForIdle()
                    current.bitmaps.forEach { if (!it.isRecycled) it.recycle() }
                }
            } finally { HostFileSyncShadow.reset() }
        }
    }

    private inner class Fixture {
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        private val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        private val projectStore = ProjectStore(app)
        @Suppress("UNCHECKED_CAST") val state = MediaWorkspace::class.java.getDeclaredField("mutableState")
            .apply { isAccessible = true }.get(media) as MutableStateFlow<WorkspaceUiState>
        val bitmaps = mutableListOf<Bitmap>()
        val extraMask = OpaqueMask(.5f, .5f, .9f, .9f)
        val originalDrafts = listOf(source("A"), source("B"))
        val selectedId = originalDrafts.first().source.sourceId
        val otherDraft = source("other-workspace")
        val project = runBlocking(Dispatchers.IO) { projectStore.createProject("遮挡恢复项目").also {
            WorkspaceStore(app, it.project.id).write(originalDrafts)
        } }
        private val otherProject = runBlocking(Dispatchers.IO) { projectStore.createProject("其他素材项目").also {
            WorkspaceStore(app, it.project.id).write(listOf(otherDraft))
        } }
        val metadata = File(app.noBackupFilesDir, "project-media-${project.project.id}.json")
        private val otherMetadata = File(app.noBackupFilesDir, "project-media-${otherProject.project.id}.json")
        private val otherBytes = otherMetadata.readBytes()

        init {
            settled()
            compose.runOnIdle { projects.reload() }
            settled()
            compose.onNodeWithText(project.project.title).performScrollTo().performClick()
            compose.waitUntil(30_000) {
                compose.waitForIdle()
                !projects.state.value.busy && !media.state.value.busy && media.projectId == project.project.id
            }
            compose.onNodeWithText("素材").performClick()
            compose.onAllNodesWithText("手动校正").onFirst().performScrollTo().performClick()
            assertEquals(selectedId, media.state.value.selectedId)
        }

        fun settled() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !media.state.value.busy && !releases.state.value.busy
        }

        fun injectFrameAndReviewPng(): DecodedFrame {
            val frame = DecodedFrame(Bitmap.createBitmap(24, 40, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE); bitmaps += this
            }, originalDrafts.first().frameTimeUs)
            compose.runOnIdle { state.value = state.value.copy(frame = frame, frameReviewId = "synthetic-frame-review") }
            compose.onNodeWithText("生成画面并复核").performScrollTo().assertIsEnabled().performClick()
            settled()
            val candidate = checkNotNull(media.state.value.candidate) { media.state.value.message.orEmpty() }
            assertEquals("image/png", candidate.mimeType)
            assertTrue(candidate.file.isFile)
            bitmaps += checkNotNull(media.state.value.candidateImage)
            // Test acknowledgement of this real generated PNG, not a person's privacy review.
            compose.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()
            assertEquals(candidate.sha256, media.state.value.reviewedDigest)
            return frame
        }

        fun assertPending(frame: DecodedFrame) {
            val current = media.state.value
            assertEquals(ProjectRoute.MEDIA, projects.state.value.route)
            assertEquals(project.project.id, media.projectId)
            assertTrue(current.unsavedEdits)
            assertFalse(current.busy)
            assertEquals(selectedId, current.selectedId)
            assertEquals(originalDrafts.first().copy(masks = originalDrafts.first().masks + extraMask), current.selected)
            assertEquals(originalDrafts[1], current.drafts[1])
            assertSame(frame, current.frame)
            assertEquals("synthetic-frame-review", current.frameReviewId)
        }

        fun completedRecordingHandoff(frame: DecodedFrame): List<SourceDraft> {
            @Suppress("UNCHECKED_CAST") val recordingState = RecordingCoordinator::class.java.getDeclaredField("mutableState")
                .apply { isAccessible = true }.get(null) as MutableStateFlow<RecordingUiState>
            val gate = RecordingCoordinator::class.java.getDeclaredField("gate")
                .apply { isAccessible = true }.get(null) as Mutex
            val stopRequest = RecordingCoordinator::class.java.getDeclaredField("stopRequest").apply { isAccessible = true }
            val owner = RecordingCoordinator::class.java.getDeclaredField("owner").apply { isAccessible = true }
            val previousStop = stopRequest.get(null)
            val bytes = ByteArray(1_024) { (it and 0xff).toByte() }
            val synthetic = source("new-recording").source
            val recorded = SourceDraft(synthetic.copy(metadata = synthetic.metadata.copy(
                sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })))
            val recording = RecordingUiState(phase = RecordingPhase.Recording,
                sessionId = UUID.nameUUIDFromBytes("mask-recovery-recording".toByteArray()).toString(),
                projectId = project.project.id, sourceId = recorded.source.sourceId, elapsedMs = 1_000)
            // Serialize with startup recovery. No service, grant, capture or Stop callback is used.
            val previous = runBlocking { gate.withLock { recordingState.value.also { recordingState.value = recording } } }
            try {
                compose.onNodeWithText("查看").assertIsEnabled().performClick()
                compose.onNodeWithText("录制操作").assertIsDisplayed()
                assertPending(frame)
                // This is a synthetic registration and phase transition, not a real recording
                // or stop test. The non-decodable sentinel must never be opened by this handoff.
                val recordedFile = File(app.noBackupFilesDir, recorded.source.privateRelativePath)
                recordedFile.parentFile!!.mkdirs()
                recordedFile.writeBytes(bytes)
                runBlocking(Dispatchers.IO) { WorkspaceStore(app, project.project.id).append(recorded.source) }
                val savedBytes = metadata.readBytes()
                val completed = recording.copy(phase = RecordingPhase.Completed)
                runBlocking { gate.withLock { recordingState.value = completed } }
                compose.onNodeWithText("整理候选步骤").performScrollTo().assertIsEnabled().performClick()
                settled()
                compose.onNodeWithText("录制操作").assertDoesNotExist()
                compose.onNodeWithText("手动校正").assertIsDisplayed()
                compose.onNodeWithText("录屏已保存在本机。请先重试保存当前遮挡", substring = true)
                    .performScrollTo().assertIsDisplayed()
                assertPending(frame)
                assertEquals(completed, RecordingCoordinator.state.value)
                assertEquals(previousStop, stopRequest.get(null))
                assertNull(owner.get(null))
                assertNull(shadowOf(compose.activity).nextStartedActivity)
                assertNull(shadowOf(app).nextStartedService)
                assertArrayEquals(bytes, recordedFile.readBytes())
                assertArrayEquals(savedBytes, metadata.readBytes())
                assertEquals(originalDrafts + recorded, WorkspaceStore(app, project.project.id).read())
                assertOtherDataUnchanged()
                return originalDrafts + recorded
            } finally {
                runBlocking { gate.withLock { recordingState.value = previous; stopRequest.set(null, previousStop) } }
            }
        }

        fun requestReread() {
            compose.onNodeWithText("重新读取已保存遮挡").performScrollTo().assertIsEnabled().performClick()
            assertDialog()
        }

        fun assertDialog() {
            compose.onNodeWithText("重新读取遮挡？").assertIsDisplayed()
            compose.onNodeWithText("上次保存可能已写入", substring = true).assertIsDisplayed()
            compose.onNodeWithText("保留当前修改").assertIsEnabled()
        }

        fun assertRecovered(expected: List<SourceDraft>, expectedBytes: ByteArray, expectedInode: Any) {
            val current = media.state.value
            assertEquals(ProjectRoute.MEDIA, projects.state.value.route)
            compose.onNodeWithText("手动校正").assertIsDisplayed()
            assertEquals(selectedId, current.selectedId)
            assertEquals(expected, current.drafts)
            assertFalse(current.unsavedEdits)
            assertFalse(current.loadFailed)
            assertNull(current.frame)
            assertNull(current.frameReviewId)
            assertNull(current.candidate)
            assertNull(current.candidateImage)
            assertNull(current.reviewedDigest)
            assertNull(current.watchedDigest)
            assertEquals(expected, WorkspaceStore(app, project.project.id).read())
            assertArrayEquals(expectedBytes, metadata.readBytes())
            assertEquals(expectedInode, Files.getAttribute(metadata.toPath(), "unix:ino"))
            compose.onNodeWithText("重新读取已保存遮挡").assertDoesNotExist()
        }

        fun assertOtherDataUnchanged() {
            assertArrayEquals(otherBytes, otherMetadata.readBytes())
            assertEquals(listOf(otherDraft), WorkspaceStore(app, otherProject.project.id).read())
            assertEquals(project, projectStore.readProject(project.project.id))
            assertEquals(otherProject, projectStore.readProject(otherProject.project.id))
        }
    }

    class CommitGate(val metadata: File, val before: ByteArray) {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    /** Delegates every syscall to the real host bridge, then pauses at a committed store fsync. */
    @Implements(Os::class)
    class CommitBoundaryShadow {
        companion object {
            private val pending = AtomicReference<CommitGate?>()
            fun arm(metadata: File, before: ByteArray): CommitGate = CommitGate(metadata, before).also {
                check(pending.compareAndSet(null, it))
            }
            fun releasePending() { pending.getAndSet(null)?.release?.countDown() }

            @JvmStatic @Implementation
            fun open(path: String, flags: Int, mode: Int): FileDescriptor = delegate("open",
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                path, flags, mode) as FileDescriptor

            @JvmStatic @Implementation
            fun fstat(fd: FileDescriptor): StructStat = delegate("fstat", arrayOf(FileDescriptor::class.java), fd) as StructStat

            @JvmStatic @Implementation
            fun close(fd: FileDescriptor) { delegate("close", arrayOf(FileDescriptor::class.java), fd) }

            @JvmStatic @Implementation
            fun fsync(fd: FileDescriptor) {
                delegate("fsync", arrayOf(FileDescriptor::class.java), fd)
                val gate = pending.get() ?: return
                if (Thread.currentThread().stackTrace.none {
                        it.className == WorkspaceStore::class.java.name && it.methodName == "confirmDirectoryDurability"
                    } || gate.before.contentEquals(gate.metadata.readBytes())) return
                // The helper already performed actual descriptor.sync(). This only delays its
                // successful return; it neither simulates success nor calls into production hooks.
                gate.reached.countDown()
                check(gate.release.await(30, TimeUnit.SECONDS)) { "Committed workspace fsync was not released" }
                pending.compareAndSet(gate, null)
            }

            private fun delegate(name: String, types: Array<Class<*>>, vararg arguments: Any): Any? = try {
                HostFileSyncShadow::class.java.getDeclaredMethod(name, *types)
                    .apply { isAccessible = true }.invoke(null, *arguments)
            } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }

    private fun source(name: String): SourceDraft {
        val id = UUID.nameUUIDFromBytes("mask-recovery-$name".toByteArray()).toString()
        return SourceDraft(ImportedSource(id, "sources/$id.mp4", "素材 $name.mp4",
            SourceMetadata("video/avc", 1_024, "0".repeat(64), 24, 40, 0, 12_000_000)),
            750_000, listOf(OpaqueMask(.05f, .05f, .25f, .25f)))
    }

    private fun resetFactory() { ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
        .apply { isAccessible = true }.set(null, null) }
}
