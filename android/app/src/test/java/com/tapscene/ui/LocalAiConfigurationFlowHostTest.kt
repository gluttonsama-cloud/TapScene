package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ImportedAiConfigurationHostTest.ConfigurationSyncShadow
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReleaseStore
import com.tapscene.data.ReviewedStepInput
import com.tapscene.data.StepOrigin
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.ScreenshotImporter
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real local seal, PNG, SQLite and fsync boundaries; no device picker or human-review claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ConfigurationSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class LocalAiConfigurationFlowHostTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun configureHost() {
        HostSqlite.configure()
        HostFileSyncShadow.begin(app.noBackupFilesDir)
    }

    @After fun closeHostDescriptors() {
        // Clear each real workspace and join its task before releasing the descriptor scope.
        ConfigurationSyncShadow.reset()
        try { HostFileSyncShadow.assertDirectorySyncEvidence() }
        finally { HostFileSyncShadow.reset() }
    }

    @Test fun cancellationAtRealCommitBoundariesUpdatesBaselineFromDisk() = runBlocking {
        withTimeout(120_000) {
            // The post-commit case runs first so the old-baseline regression is the first failure.
            for (afterCommit in listOf(true, false)) {
                val fixture = withContext(Dispatchers.IO) { localFixture() }
                withWorkspace { workspace ->
                    val target = openAndEdit(workspace, fixture)
                    val edited = checkNotNull(workspace.state.value.aiConfiguration)
                    val owner = workspace.state.value.aiConfigurationOwner
                    val releaseBefore = hashes(fixture.release)
                    val gate = ConfigurationSyncShadow.arm(if (afterCommit) fixture.config.parentFile!! else fixture.export.parentFile!!)
                    try {
                        withContext(Dispatchers.Main) { workspace.prepareAiExport() }
                        check(gate.reached.await(30, TimeUnit.SECONDS)) { "Save did not reach the requested real directory fsync" }
                        check(workspace.state.value.busy)
                        check(exportedPlan(fixture).contentEquals(target)) { "The verified cache must contain B at both gates" }
                        check(fixture.config.readBytes().contentEquals(if (afterCommit) target else fixture.plan.toBytes()))
                        withContext(Dispatchers.Main) { workspace.cancel() }
                        check(workspace.state.value.busy) { "Cancellation must keep the task owned while fsync is blocked" }
                    } finally { gate.release.countDown() }
                    settled(workspace)
                    val completed = workspace.state.value

                    // Keep this before new recovery-state assertions: the old implementation
                    // leaves A as the baseline even though B is already the actual saved file.
                    withContext(Dispatchers.Main) {
                        workspace.setAiHold(fixture.plan.visits.single().visitId, fixture.plan.visits.single().holdFrames)
                        check(workspace.state.value.aiConfigurationChanged == afterCommit) {
                            "Returning to A must remain dirty after B committed, and be clean before commit"
                        }
                        workspace.setAiHold(edited.visits.single().visitId, edited.visits.single().holdFrames)
                        check(workspace.state.value.aiConfigurationChanged == !afterCommit)
                    }
                    check(checkNotNull(task(workspace)).isCancelled)
                    check(completed.aiConfiguration === edited && completed.aiConfigurationOwner == owner)
                    check(completed.aiConfigurationChanged == !afterCommit && !completed.aiSaveNeedsCheck)
                    check(completed.exportFile == null && !completed.aiReplacesRetainedConfiguration)
                    check(checkNotNull(workspace.state.value.aiConfiguration).resolve().toBytes().contentEquals(target))
                    check(fixture.config.readBytes().contentEquals(if (afterCommit) target else fixture.plan.toBytes()))
                    check(hashes(fixture.release) == releaseBefore)
                    check(completed.message == if (afterCommit)
                        "已核对，本机配置与本页一致；动画包尚未另存。"
                    else "已核对，本机配置仍是保存前的内容。本页输入保留，可检查后重试。")
                    withContext(Dispatchers.Main) { check(!workspace.beginExportPicker()) }
                }
            }
        }
    }

    @Test fun failedAndChangedReadbackBlockOverwriteUntilTargetRecheckRestoresBaseline() = runBlocking {
        withTimeout(120_000) {
            val fixture = withContext(Dispatchers.IO) { localFixture() }
            withWorkspace { workspace ->
                val target = openAndEdit(workspace, fixture)
                val edited = checkNotNull(workspace.state.value.aiConfiguration)
                val owner = workspace.state.value.aiConfigurationOwner
                val metadata = File(fixture.release, "summary.json")
                val validMetadata = metadata.readBytes()
                val releaseBefore = hashes(fixture.release)
                val gate = ConfigurationSyncShadow.arm(fixture.config.parentFile!!)
                try {
                    withContext(Dispatchers.Main) { workspace.prepareAiExport() }
                    check(gate.reached.await(30, TimeUnit.SECONDS))
                    check(fixture.config.readBytes().contentEquals(target))
                    // Corrupt only the readback input after the real local configuration commit.
                    metadata.writeText("{SYNTHETIC READBACK FAILURE")
                    withContext(Dispatchers.Main) { workspace.cancel() }
                } finally { gate.release.countDown() }
                settled(workspace)
                check(workspace.state.value.aiSaveNeedsCheck && workspace.state.value.aiConfigurationChanged)
                check(workspace.state.value.aiConfiguration === edited && workspace.state.value.exportFile == null)
                check(workspace.state.value.message == "暂时无法核对本机配置的保存结果。本页输入保留，请重新核对；不要重复保存。")
                val unknownFiles = hashes(fixture.root)
                withContext(Dispatchers.Main) {
                    workspace.setAiCanvas(true)
                    workspace.setAiHold(edited.visits.single().visitId, fixture.plan.visits.single().holdFrames)
                    workspace.resetAiPath()
                    workspace.prepareAiExport()
                    workspace.openAiPackage(UUID.randomUUID().toString())
                    check(!workspace.beginExportPicker())
                }
                check(workspace.state.value.aiConfiguration === edited && workspace.state.value.aiConfigurationOwner == owner)
                check(workspace.state.value.aiSaveNeedsCheck && !workspace.state.value.busy)
                check(hashes(fixture.root) == unknownFiles)
                withContext(Dispatchers.Main) { workspace.recheckAiSave() }
                settled(workspace)
                check(workspace.state.value.aiSaveNeedsCheck && hashes(fixture.root) == unknownFiles)

                metadata.writeBytes(validMetadata)
                val configurationDirectory = fixture.config.parentFile!!
                val retainedDirectory = File(fixture.root, "synthetic-configurations-held-${UUID.randomUUID()}")
                check(configurationDirectory.renameTo(retainedDirectory))
                try {
                    val inaccessibleFiles = hashes(fixture.root)
                    withContext(Dispatchers.Main) { workspace.recheckAiSave() }
                    settled(workspace)
                    check(!configurationDirectory.exists()) { "Read-only recheck must not recreate missing storage" }
                    check(workspace.state.value.aiSaveNeedsCheck && workspace.state.value.aiConfiguration === edited)
                    check(hashes(fixture.root) == inaccessibleFiles)
                } finally { check(retainedDirectory.renameTo(configurationDirectory)) }
                val visit = fixture.plan.visits.single()
                val independent = RenderPlan.build(fixture.scene, fixture.plan.width, fixture.plan.height,
                    listOf(RenderPlan.Visit(visit.visitId, visit.stateId, visit.selectedEdgeId, 240)), emptyList())
                fixture.config.writeBytes(independent.toBytes())
                val changedFiles = hashes(fixture.root)
                withContext(Dispatchers.Main) { workspace.recheckAiSave() }
                settled(workspace)
                check(workspace.state.value.aiSaveNeedsCheck && workspace.state.value.aiConfiguration === edited)
                check(workspace.state.value.message == "版本或本机配置已变化，未继续覆盖。本页输入保留，请重新核对；返回后可重新选择配置来源。")
                withContext(Dispatchers.Main) {
                    workspace.setAiHold(visit.visitId, visit.holdFrames)
                    workspace.prepareAiExport()
                    check(!workspace.beginExportPicker())
                }
                check(workspace.state.value.aiConfiguration === edited && !workspace.state.value.busy)
                check(hashes(fixture.root) == changedFiles) { "An independent valid configuration must not be overwritten" }

                fixture.config.writeBytes(target)
                val matchingFiles = hashes(fixture.root)
                withContext(Dispatchers.Main) { workspace.recheckAiSave() }
                settled(workspace)
                val recovered = workspace.state.value
                withContext(Dispatchers.Main) {
                    workspace.setAiHold(visit.visitId, visit.holdFrames)
                    check(workspace.state.value.aiConfigurationChanged) { "Recovered B must replace A as the clean baseline" }
                    workspace.setAiHold(visit.visitId, edited.visits.single().holdFrames)
                    check(!workspace.state.value.aiConfigurationChanged)
                    check(!workspace.beginExportPicker())
                }
                check(!recovered.aiSaveNeedsCheck && !recovered.aiConfigurationChanged)
                check(recovered.aiConfiguration === edited && recovered.aiConfigurationOwner == owner)
                check(recovered.message == "已核对，本机配置与本页一致；动画包尚未另存。")
                check(recovered.exportFile == null && !recovered.aiReplacesRetainedConfiguration && hashes(fixture.root) == matchingFiles)
                check(hashes(fixture.release) == releaseBefore) { "Read-only recovery must preserve the real sealed release" }
            }
        }
    }

    private suspend fun openAndEdit(workspace: ReleaseWorkspace, fixture: Fixture): ByteArray {
        withContext(Dispatchers.Main) { workspace.openAiPackage(fixture.scene.releaseId) }
        settled(workspace)
        check(workspace.state.value.message == null) { "Open failed: ${workspace.state.value.message}" }
        val initial = checkNotNull(workspace.state.value.aiConfiguration)
        check(!initial.fromDraft && !workspace.state.value.aiConfigurationChanged)
        check(workspace.state.value.aiConfigurationChoice == null && !workspace.state.value.aiReplacesRetainedConfiguration)
        check(initial.resolve().toBytes().contentEquals(fixture.plan.toBytes()))
        return withContext(Dispatchers.Main) {
            workspace.setAiHold(initial.visits.single().visitId, 180)
            check(workspace.state.value.aiConfigurationChanged)
            checkNotNull(workspace.state.value.aiConfiguration).resolve().toBytes()
        }
    }

    private suspend fun withWorkspace(block: suspend (ReleaseWorkspace) -> Unit) {
        val owner = ViewModelStore()
        val workspace = withContext(Dispatchers.Main) {
            ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[ReleaseWorkspace::class.java]
        }
        try {
            settled(workspace)
            check(workspace.state.value.message == null)
            block(workspace)
        } finally {
            withContext(NonCancellable) {
                val pending = withContext(Dispatchers.Main) { task(workspace).also { owner.clear() } }
                pending?.join()
            }
        }
    }

    private suspend fun settled(workspace: ReleaseWorkspace) {
        checkNotNull(task(workspace)).join()
        check(!workspace.state.value.busy && workspace.state.value.stage == null)
    }

    private fun task(workspace: ReleaseWorkspace) = ReleaseWorkspace::class.java.getDeclaredField("task")
        .apply { isAccessible = true }.get(workspace) as Job?

    private fun hashes(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).invariantSeparatorsPath to ViewerPackageCodec.sha256(it)
    }

    private fun exportedPlan(fixture: Fixture) = ZipFile(fixture.export).use { zip ->
        zip.getInputStream(checkNotNull(zip.getEntry("render-plan.json"))).use { it.readBytes() }
    }

    private data class Fixture(val root: File, val scene: ViewerScene, val plan: RenderPlan) {
        val release = File(root, "releases/${scene.releaseId}")
        val config = File(root, "ai-configs/${scene.releaseId}.json")
        val export = File(root, "exports/${scene.releaseId}.tapscene-ai")
    }

    /** All prior reviews and seal calls are synthetic fixture setup, not human privacy review. */
    private suspend fun localFixture(): Fixture {
        val source = File(app.noBackupFilesDir, "local-ai-flow-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val image = File(source, "synthetic.png")
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val draft = ProjectStore.withTemporary(app) { projects ->
            runBlocking {
                var current = projects.createProject("Local configuration baseline", "Synthetic PNG only")
                ScreenshotImporter(app).importScreenshot(image).use { imported ->
                    val safe = SafeMediaWriter(app).writePng(imported.bitmap, emptyList(), File(source, "safe"))
                    current = projects.addReviewedStep(current.project.id,
                        ReviewedStepInput(safe.file, safe.sha256, safe.width, safe.height,
                            StepOrigin.ImportedImage(imported.source), emptyList()), "合成终点", "仅供本机保存边界检查")
                }
                val step = current.steps.single()
                projects.saveStepDraft(current.project.id, step.id, step.title, step.description, true,
                    emptyList(), current.project.revision)
            }
        }
        val store = ReleaseStore(app)
        var candidate = store.createCandidate(draft.project.id, draft.project.revision)
        val state = candidate.scene.states.single()
        store.candidateAssetFile(candidate.id, state.id)
        candidate = store.reviewState(candidate.id, candidate.contentDigest, state.id)
        candidate = store.reviewSummary(candidate.id, candidate.contentDigest)
        candidate = store.reviewFileList(candidate.id, candidate.contentDigest)
        candidate = store.recordTraversal(candidate.id, candidate.contentDigest, null, false)
        val summary = store.seal(candidate.id, candidate.contentDigest)
        check(summary.origin == "local" && !store.hasDraftAiPlan(summary.id))
        val scene = store.loadRelease(summary.id)
        val plan = RenderPlan.build(scene, 1080, 1920,
            listOf(RenderPlan.Visit(UUID.randomUUID().toString(), scene.startStateId, null, 120)), emptyList())
        store.exportAiRelease(summary.id, plan, expectedLocal = summary)
        val fixture = Fixture(File(app.noBackupFilesDir, "release-store"), scene, plan)
        check(!File(fixture.release, "draft-plan.json").exists())
        check(fixture.config.readBytes().contentEquals(plan.toBytes()))
        val opened = store.openAiConfiguration(summary.id)
        check(opened.summary.origin == "local" && opened.importedSnapshot == null)
        val fingerprint = checkNotNull(opened.localSnapshot)
        check(fingerprint.byteLength == fixture.config.length() &&
            fingerprint.sha256 == ViewerPackageCodec.sha256(fixture.config))
        return fixture
    }
}
