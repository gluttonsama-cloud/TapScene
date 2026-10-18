package com.tapscene.ui.shell

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.system.Os
import android.system.StructStat
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.MainActivity
import com.tapscene.data.AiDraftImportFixtures
import com.tapscene.data.AiDraftImportStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReleaseDeletionState
import com.tapscene.data.ReleaseStore
import com.tapscene.data.ReleaseSummary
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.packageformat.ViewerTraversal
import com.tapscene.ui.ReleaseUiState
import com.tapscene.ui.ReleaseWorkspace
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real confirmation/Workspace/Store, native PNGs and host fsync. Device/power-loss behavior NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi", shadows = [ReleaseDeletionFlowHostTest.DeletionBoundaryShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReleaseDeletionFlowHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetFactory()
            HostSqlite.configure()
            val root = app.noBackupFilesDir.canonicalFile
            val sandbox = RuntimeEnvironment.getTempDirectory().basePath.toRealPath()
            check(root.toPath().startsWith(sandbox) && root.toPath() != sandbox)
            root.listFiles().orEmpty().forEach { child ->
                check(child.canonicalFile.toPath().startsWith(root.toPath()))
                check(child.deleteRecursively())
            }
            HostFileSyncShadow.begin(root)
        }
        override fun after() {
            DeletionBoundaryShadow.releasePending()
            try { HostFileSyncShadow.reset() } finally { resetFactory() }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val owners = mutableListOf<Pair<ViewModelStore, ReleaseWorkspace>>()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val storeRoot: File get() = File(app.noBackupFilesDir, "release-store")

    @After fun closeWorkspaces() {
        DeletionBoundaryShadow.releasePending()
        val jobs = owners.flatMap { (_, workspace) -> jobs(workspace) }
        compose.runOnIdle { owners.forEach { it.first.clear() } }
        awaitJobs(jobs)
        HostFileSyncShadow.assertDirectorySyncEvidence()
    }

    @Test fun actualConfirmationDistinguishesSameTitlesAndStoreRejectsChangedDigest() {
        val fixtures = listOf(importedFixture("identity-a", retain = false), importedFixture("identity-b", retain = false))
        assertEquals(fixtures[0].summary.title, fixtures[1].summary.title)
        assertNotEquals(fixtures[0].summary.id, fixtures[1].summary.id)
        val workspace = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        awaitIdle(workspace)
        compose.runOnIdle { workspace.reloadLibrary() }
        awaitIdle(workspace)
        compose.onNodeWithText("演示库").performClick()
        val summaries = workspace.state.value.releases
        val target = fixtures.single { it.summary.id == summaries.first().id }
        val other = fixtures.single { it.summary.id != target.summary.id }
        val before = hashes(storeRoot)
        fun openConfirmation() {
            compose.onAllNodesWithText("删除本机副本")[0].performScrollTo().performClick()
            compose.onNodeWithText("删除本机副本？").assertIsDisplayed()
            compose.onNodeWithText("版本 ${target.summary.id}\n摘要 ${target.summary.contentDigest}")
                .performScrollTo().assertIsDisplayed()
        }
        fun confirm() = compose.onNode(hasText("删除本机副本") and hasAnyAncestor(isDialog()))
        openConfirmation()
        confirm().assertIsEnabled()
        compose.onNodeWithText("将删除本机观看资产、观看记录、已保存的 AI 配置及应用内生成的导出缓存，无法撤销。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("创作项目、原素材和已保存到应用外的文件保留；已发送的文件不会被收回。")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { mutableState(workspace).value = workspace.state.value.copy(releases = listOf(other.summary)) }
        confirm().assertIsNotEnabled() // An identical title does not make another ID the confirmed target.
        compose.runOnIdle { mutableState(workspace).value = workspace.state.value.copy(
            releases = listOf(target.summary.copy(contentDigest = "0".repeat(64)), other.summary)) }
        confirm().assertIsNotEnabled()
        compose.runOnIdle { mutableState(workspace).value = workspace.state.value.copy(releases = summaries) }
        confirm().assertIsEnabled()
        compose.onNodeWithText("保留").performClick()
        compose.onNodeWithText("删除本机副本？").assertDoesNotExist()
        assertEquals(before, hashes(storeRoot))

        openConfirmation()
        // The real store changes after the dialog was rendered; the UI's old list is not authority.
        val replacement = replaceWithChangedContent(target)
        val replacementBefore = hashes(storeRoot)
        confirm().assertIsEnabled().performClick()
        awaitIdle(workspace)
        assertEquals(replacementBefore, hashes(storeRoot))
        assertEquals(ReleaseDeletionState.REPLACED, inspect(target))
        assertEquals(ReleaseDeletionState.PRESENT, inspect(replacement))
        assertEquals(ReleaseDeletionState.PRESENT, inspect(other))
        assertNull(workspace.state.value.deletionCheck)
        assertTrue(workspace.state.value.releases.contains(replacement.summary))
        assertTrue(checkNotNull(workspace.state.value.message).contains("未删除当前副本"))
        compose.onNodeWithText("确认的版本已变化，未删除当前副本。请重新打开后核对。").assertIsDisplayed()
    }

    @Test fun cancellationBeforeRenamePreservesEveryVersionCheckpointPlanAndExternalCopy() {
        val target = importedFixture("before-target")
        val other = importedFixture("before-other")
        val workspace = newWorkspace()
        openReferences(workspace, target)
        val beforeState = workspace.state.value
        val before = hashes(storeRoot)
        val external = listOf(target, other).associate { it.external.path to ViewerPackageCodec.sha256(it.external) }
        val gate = DeletionBoundaryShadow.arm(File(storeRoot, "staging"))
        try {
            compose.runOnIdle { workspace.deleteRelease(target.summary) }
            assertTrue("Deletion never reached the real pre-rename directory fsync", gate.reached.await(30, TimeUnit.SECONDS))
            assertTrue(releaseDirectory(target).isDirectory)
            assertTrue(workspace.state.value.busy)
            compose.runOnIdle { workspace.cancel() }
            assertTrue("Cancellation must wait for the blocked syscall to finish", workspace.state.value.busy)
        } finally { gate.release.countDown() }
        awaitIdle(workspace)
        assertEquals(before, hashes(storeRoot))
        assertEquals(ReleaseDeletionState.PRESENT, inspect(target))
        assertEquals(ReleaseDeletionState.PRESENT, inspect(other))
        assertSame(beforeState.player, workspace.state.value.player)
        assertSame(beforeState.playerBitmap, workspace.state.value.playerBitmap)
        assertSame(beforeState.aiConfiguration, workspace.state.value.aiConfiguration)
        assertNull(workspace.state.value.deletionCheck)
        external.forEach { (path, hash) -> assertEquals(hash, ViewerPackageCodec.sha256(File(path))) }
        compose.onNodeWithText("已取消删除；本机副本、观看记录和 AI 配置保留。").assertIsDisplayed()
    }

    @Test fun cancellationAfterRenameClearsExactReferencesEvenWhenAnotherVersionBreaksLibraryRefresh() {
        val target = sealedFixture("after-target")
        val other = importedFixture("after-other")
        val projectBefore = ProjectStore.withTemporary(app) { it.readProject(checkNotNull(target.projectId)) }
        val workspace = newWorkspace()
        openReferences(workspace, target)
        compose.runOnIdle { assertTrue(workspace.selectDeliveryRelease(target.summary.id)) }
        awaitIdle(workspace)
        assertEquals(target.summary.id, workspace.state.value.deliveryRelease?.id)
        // Keep the genuinely read resume choice as a stale route reference beside the active player.
        // This seeds only UI state; deletion/inspection and every byte of storage remain production IO.
        val resume = runBlocking(Dispatchers.IO) { ReleaseStore(app).openPlayback(target.summary.id) }
        compose.runOnIdle { mutableState(workspace).value = workspace.state.value.copy(
            playbackResume = resume, lastSealedId = target.summary.id, lastImportedId = other.summary.id) }
        val beforeState = workspace.state.value
        assertNotNull(beforeState.player)
        assertNotNull(beforeState.aiConfiguration)
        assertNotNull(beforeState.playbackResume?.checkpoint)
        val external = listOf(target, other).associate { it.external.path to ViewerPackageCodec.sha256(it.external) }
        val otherMetadata = File(releaseDirectory(other), "summary.json")
        val validOtherMetadata = otherMetadata.readBytes()
        otherMetadata.writeText("{broken unrelated synthetic summary")
        val otherBefore = retainedFiles(other)
        assertTrue(runBlocking(Dispatchers.IO) { runCatching { ReleaseStore(app).listReleases() }.isFailure })
        val gate = DeletionBoundaryShadow.arm(File(storeRoot, "releases"))
        try {
            compose.runOnIdle { workspace.deleteRelease(target.summary) }
            assertTrue("Deletion never reached the real post-rename directory fsync", gate.reached.await(30, TimeUnit.SECONDS))
            assertFalse("Atomic rename must already have removed the public version", releaseDirectory(target).exists())
            assertTrue(File(storeRoot, "staging").listFiles().orEmpty().any { File(it, "deleted-release/summary.json").isFile })
            compose.runOnIdle { workspace.cancel() }
        } finally { gate.release.countDown() }
        awaitIdle(workspace)
        val after = workspace.state.value
        assertEquals(ReleaseDeletionState.DELETED, inspect(target))
        assertFalse(releaseDirectory(target).exists())
        assertTrue(retainedFiles(target).isEmpty())
        assertFalse(after.releases.any { it.id == target.summary.id })
        assertTrue(after.releases.contains(other.summary))
        assertNull(after.deliverySelection)
        assertNull(after.deliveryRelease)
        assertNull(after.aiConfiguration)
        assertFalse(after.aiConfigurationChanged)
        assertNull(after.player)
        assertNull(after.playerBitmap)
        assertNull(after.playbackResume)
        assertNull(after.lastSealedId)
        assertEquals(other.summary.id, after.lastImportedId)
        assertNull(after.deletionCheck)
        assertTrue(after.libraryReadFailed)
        assertFalse(after.busy)
        assertNull(after.stage)
        assertEquals(otherBefore, retainedFiles(other))
        assertTrue(runBlocking(Dispatchers.IO) { runCatching { ReleaseStore(app).listReleases() }.isFailure })
        assertEquals(projectBefore, ProjectStore.withTemporary(app) { it.readProject(checkNotNull(target.projectId)) })
        external.forEach { (path, hash) -> assertEquals(hash, ViewerPackageCodec.sha256(File(path))) }
        compose.onNodeWithText("本机副本、观看记录和 AI 配置已删除。", substring = true).assertIsDisplayed()
        otherMetadata.writeBytes(validOtherMetadata)
        compose.onNodeWithText("刷新").performClick()
        awaitIdle(workspace)
        assertEquals(listOf(other.summary), workspace.state.value.releases)
        assertFalse(workspace.state.value.libraryReadFailed)
    }

    @Test fun unknownResultRequiresExplicitReadOnlyRecheckAndNeverDeletesOnRecovery() {
        val target = importedFixture("unknown-target")
        val other = importedFixture("unknown-other")
        val workspace = newWorkspace()
        val before = hashes(storeRoot)
        val metadata = File(releaseDirectory(target), "summary.json")
        val valid = metadata.readBytes()
        metadata.writeText("{broken target synthetic summary")
        compose.runOnIdle { workspace.deleteRelease(target.summary) }
        awaitIdle(workspace)
        val pending = checkNotNull(workspace.state.value.deletionCheck)
        assertEquals(target.summary, pending.release)
        assertFalse(pending.cleanupPending)
        assertTrue(workspace.state.value.libraryReadFailed)
        assertTrue(releaseDirectory(target).isDirectory)
        compose.onNodeWithText("重新核对").assertIsEnabled()
        compose.onNodeWithText("继续清理").assertDoesNotExist()
        compose.onNodeWithText("导入包").assertIsNotEnabled()
        val failedState = hashes(storeRoot)
        compose.runOnIdle { workspace.deleteRelease(other.summary) }
        assertSame(pending, workspace.state.value.deletionCheck)
        assertEquals(failedState, hashes(storeRoot))
        metadata.writeBytes(valid)
        compose.waitForIdle()
        assertSame(pending, workspace.state.value.deletionCheck)
        assertEquals(before, hashes(storeRoot))
        compose.onNodeWithText("重新核对").performClick()
        awaitIdle(workspace)
        assertNull(workspace.state.value.deletionCheck)
        assertEquals(before, hashes(storeRoot))
        assertEquals(ReleaseDeletionState.PRESENT, inspect(target))
        assertEquals(ReleaseDeletionState.PRESENT, inspect(other))
        compose.onNodeWithText("已核对：本机副本仍保留，没有完成删除。").assertIsDisplayed()
        compose.onNodeWithText("重新核对").assertDoesNotExist()
        assertFalse(workspace.state.value.libraryReadFailed)
        compose.onNodeWithText("导入包").assertIsEnabled()
    }

    private fun newWorkspace(): ReleaseWorkspace {
        lateinit var workspace: ReleaseWorkspace
        compose.runOnIdle {
            val owner = ViewModelStore()
            workspace = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[ReleaseWorkspace::class.java]
            owners += owner to workspace
            compose.activity.setContent {
                TapSceneTheme {
                    ReleaseLibraryContent(workspace.state.collectAsState().value, onImport = {},
                        onOpen = workspace::openRelease, onExport = workspace::prepareExport,
                        onDelete = {}, onSettings = {}, onRetryDeletion = workspace::retryReleaseDeletion,
                        onReload = workspace::reloadLibrary)
                }
            }
        }
        awaitIdle(workspace)
        return workspace
    }

    private fun openReferences(workspace: ReleaseWorkspace, fixture: Fixture) {
        compose.runOnIdle { workspace.openAiPackage(fixture.summary.id) }
        awaitIdle(workspace)
        compose.runOnIdle { workspace.openRelease(fixture.summary.id) }
        awaitIdle(workspace)
        val resume = checkNotNull(workspace.state.value.playbackResume)
        compose.runOnIdle { workspace.resumePlayback(resume.session.ownerToken, false) }
        awaitIdle(workspace)
        assertEquals(fixture.summary.id, workspace.state.value.player?.scene?.releaseId)
        assertNotNull(workspace.state.value.playerBitmap)
        assertEquals(fixture.summary.id, workspace.state.value.aiConfiguration?.scene?.releaseId)
    }

    private data class Fixture(val summary: ReleaseSummary, val scene: ViewerScene, val source: File,
        val external: File, val projectId: String? = null)

    private fun importedFixture(name: String, retain: Boolean = true): Fixture = runBlocking(Dispatchers.IO) {
        fun id(value: String) = UUID.nameUUIDFromBytes("deletion/$name/$value".toByteArray()).toString()
        val source = File(app.noBackupFilesDir, "deletion-$name").also { check(it.mkdir()) }
        val assets = File(source, "assets").also { check(it.mkdir()) }
        val images = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
            val imageId = id("image-$index")
            val file = File(assets, "$imageId.png")
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            ViewerScene.Asset(imageId, "assets/$imageId.png", "image/png", file.length(), ViewerPackageCodec.sha256(file), 32, 48)
        }
        val states = images.mapIndexed { index, image -> ViewerScene.State(id("state-$index"), image.id,
            32, 48, "合成画面 ${index + 1}", "仅供删除回归", "authored", index == 1) }
        val edge = ViewerScene.Edge(id("next"), states[0].id, states[1].id, null, null, "下一步", "continue", "authored")
        val scene = ViewerScene(id("release"), "同名固定演示", "合成删除检查", 1L,
            states[0].id, states, listOf(edge), emptyList(), images)
        // Outside release-store, representing an already saved copy without using a real provider.
        val external = File(app.noBackupFilesDir, "synthetic-external-$name.tapscene")
        ViewerPackageCodec.writePackage(scene, source, external, {}, null)
        val summary = external.inputStream().use { ReleaseStore(app).importPackage(it) }
        if (retain) {
            val plan = RenderPlan.build(scene, 1080, 1920, listOf(
                RenderPlan.Visit(id("visit-start"), states[0].id, edge.id, 90),
                RenderPlan.Visit(id("visit-end"), states[1].id, null, 60)), emptyList())
            retainSidecars(summary, scene, plan)
        }
        Fixture(summary, scene, source, external)
    }

    private fun sealedFixture(name: String): Fixture = runBlocking(Dispatchers.IO) {
        val input = AiDraftImportFixtures.complete(app)
        val imports = AiDraftImportStore(app)
        val prepared = input.zip.inputStream().use { imports.prepare(it) }
        val projectId = checkNotNull(imports.commit(prepared.sessionId, prepared.previewDigest).projectId)
        ProjectStore.withTemporary(app) { projects ->
            runBlocking { AiDraftImportFixtures.reviewDraftRegions(projects, projectId) }
        }
        val summary = AiDraftImportFixtures.sealFixture(app, projectId, input)
        val store = ReleaseStore(app)
        val scene = store.loadRelease(summary.id)
        retainSidecars(summary, scene, checkNotNull(store.readAiPlan(summary.id)))
        val external = File(app.noBackupFilesDir, "synthetic-external-$name.tapscene")
        check(store.exportRelease(summary.id).copyTo(external).isFile)
        Fixture(summary, scene, input.assetRoot, external, projectId)
    }

    private suspend fun retainSidecars(summary: ReleaseSummary, scene: ViewerScene, plan: RenderPlan) {
        val store = ReleaseStore(app)
        val opened = store.openPlayback(summary.id)
        val next = scene.edges.single { it.trigger == "continue" }
        check(store.savePlayback(opened.session, ViewerTraversal.start(scene).advance(scene, next.id), 1))
        check(store.exportRelease(summary.id).isFile)
        check(store.exportAiRelease(summary.id, plan).isFile)
        check(File(storeRoot, "ai-configs/${summary.id}.json").isFile)
    }

    private fun replaceWithChangedContent(original: Fixture): Fixture = runBlocking(Dispatchers.IO) {
        val old = original.scene
        val changed = ViewerScene(old.releaseId, old.title, "相同标识的新合成内容", old.createdAt,
            old.startStateId, old.states, old.edges, old.hotspots, old.assets)
        val input = File(app.noBackupFilesDir, "replacement-${old.releaseId}.tapscene")
        ViewerPackageCodec.writePackage(changed, original.source, input, {}, null)
        val store = ReleaseStore(app)
        store.deleteRelease(original.summary)
        val summary = input.inputStream().use { store.importPackage(it) }
        assertNotEquals(original.summary.contentDigest, summary.contentDigest)
        original.copy(summary = summary, scene = changed)
    }

    private fun releaseDirectory(fixture: Fixture) = File(storeRoot, "releases/${fixture.summary.id}")
    private fun inspect(fixture: Fixture) = runBlocking(Dispatchers.IO) { ReleaseStore(app).inspectDeletion(fixture.summary) }
    private fun hashes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(app.noBackupFilesDir).path to ViewerPackageCodec.sha256(it) }
    private fun retainedFiles(fixture: Fixture): Map<String, String> {
        val id = fixture.summary.id
        return hashes(storeRoot).filterKeys { it.startsWith("release-store/releases/$id/") ||
            it == "release-store/ai-configs/$id.json" || it == "release-store/exports/$id.tapscene" ||
            it == "release-store/exports/$id.tapscene-ai" }
    }
    private fun jobs(workspace: ReleaseWorkspace) = listOfNotNull(job(workspace, "task"), job(workspace, "playerTask"))
    private fun job(workspace: ReleaseWorkspace, name: String) = ReleaseWorkspace::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(workspace) as Job?
    private fun awaitJobs(jobs: List<Job>) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        jobs.all { it.isCompleted }
    }
    private fun awaitIdle(workspace: ReleaseWorkspace) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        !workspace.state.value.busy && jobs(workspace).none { it.isActive }
    }
    @Suppress("UNCHECKED_CAST")
    private fun mutableState(workspace: ReleaseWorkspace) = ReleaseWorkspace::class.java.getDeclaredField("mutableState")
        .apply { isAccessible = true }.get(workspace) as MutableStateFlow<ReleaseUiState>
    private fun resetFactory() = ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
        .apply { isAccessible = true }.set(null, null)

    class SyncGate(val directory: File) {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val claimed = AtomicBoolean(false)
    }

    /** Every syscall is real. Only the return of the chosen successful directory fsync is delayed. */
    @Implements(Os::class)
    class DeletionBoundaryShadow {
        companion object {
            private val paths = Collections.synchronizedMap(IdentityHashMap<FileDescriptor, File>())
            private val pending = AtomicReference<SyncGate?>()
            fun arm(directory: File): SyncGate = SyncGate(directory.canonicalFile).also {
                check(pending.compareAndSet(null, it))
            }
            fun releasePending() { pending.getAndSet(null)?.release?.countDown() }

            @JvmStatic @Implementation
            fun open(path: String, flags: Int, mode: Int): FileDescriptor {
                val fd = delegate("open", arrayOf(String::class.java, Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!), path, flags, mode) as FileDescriptor
                paths[fd] = File(path).canonicalFile
                return fd
            }
            @JvmStatic @Implementation
            fun fstat(fd: FileDescriptor): StructStat = delegate("fstat", arrayOf(FileDescriptor::class.java), fd) as StructStat
            @JvmStatic @Implementation
            fun close(fd: FileDescriptor) {
                try { delegate("close", arrayOf(FileDescriptor::class.java), fd) } finally { paths.remove(fd) }
            }
            @JvmStatic @Implementation
            fun fsync(fd: FileDescriptor) {
                delegate("fsync", arrayOf(FileDescriptor::class.java), fd)
                val gate = pending.get() ?: return
                if (paths[fd] != gate.directory || !gate.claimed.compareAndSet(false, true)) return
                gate.reached.countDown()
                check(gate.release.await(30, TimeUnit.SECONDS)) { "Deletion directory fsync was not released" }
                pending.compareAndSet(gate, null)
            }
            private fun delegate(name: String, types: Array<Class<*>>, vararg arguments: Any): Any? = try {
                HostFileSyncShadow::class.java.getDeclaredMethod(name, *types)
                    .apply { isAccessible = true }.invoke(null, *arguments)
            } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
}
