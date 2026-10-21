package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ImportedAiConfigurationHostTest.ConfigurationSyncShadow
import com.tapscene.data.ReleaseStore
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
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

/** Real imported PNGs and Workspace/Store calls; no picker, device lifecycle or power-loss claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ConfigurationSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class ImportedAiConfigurationFlowHostTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun configureHost() {
        HostSqlite.configure()
        HostFileSyncShadow.begin(app.noBackupFilesDir)
    }

    @After fun closeHostDescriptors() {
        // Every workspace owner is cleared and its task joined before this scope is reset.
        ConfigurationSyncShadow.reset()
        try { HostFileSyncShadow.assertDirectorySyncEvidence() }
        finally { HostFileSyncShadow.reset() }
    }

    @Test fun explicitSourceChoicesPreserveRawBytesUntilConfirmedReplacement() = runBlocking {
        withTimeout(120_000) {
            val fixture = withContext(Dispatchers.IO) { importFixture() }
            withWorkspace { workspace ->
                open(workspace, fixture)
                assertDefaults(workspace, fixture)
                check(!workspace.state.value.aiConfigurationChanged && !workspace.state.value.aiReplacesRetainedConfiguration)
                check(workspace.state.value.aiConfigurationChoice == null && !fixture.config.exists())

                val retained = ("\n" + fixture.plan.toBytes().toString(Charsets.UTF_8)).toByteArray()
                fixture.config.writeBytes(retained)
                val untouched = hashes(fixture.root)
                open(workspace, fixture)
                val choice = checkNotNull(workspace.state.value.aiConfigurationChoice)
                check(workspace.state.value.aiConfiguration == null)
                check(choice.source.summary.id == fixture.scene.releaseId && choice.owner == workspace.state.value.aiConfigurationOwner)
                check(choice.source.importedSnapshot?.byteLength == retained.size.toLong())
                check(choice.source.importedSnapshot?.sha256 == ViewerPackageCodec.sha256(fixture.config))
                withContext(Dispatchers.Main) { workspace.chooseAiConfiguration(choice.owner, true) }
                check(checkNotNull(workspace.state.value.aiConfiguration).resolve().toBytes().contentEquals(fixture.plan.toBytes()))
                check(!workspace.state.value.aiConfigurationChanged && workspace.state.value.aiReplacesRetainedConfiguration)
                check(hashes(fixture.root) == untouched)

                open(workspace, fixture)
                withContext(Dispatchers.Main) {
                    workspace.chooseAiConfiguration(checkNotNull(workspace.state.value.aiConfigurationChoice).owner, false)
                }
                assertDefaults(workspace, fixture)
                check(workspace.state.value.aiConfigurationChanged && workspace.state.value.aiReplacesRetainedConfiguration)
                check(hashes(fixture.root) == untouched) { "Choosing defaults must not replace retained bytes" }
                withContext(Dispatchers.Main) { workspace.leaveAiPage(); workspace.clearAiConfiguration() }
                check(hashes(fixture.root) == untouched) { "Abandoning defaults must not write a configuration" }

                val invalid = "{SYNTHETIC INVALID RETAINED CONFIGURATION".toByteArray()
                fixture.config.writeBytes(invalid)
                open(workspace, fixture)
                val invalidChoice = checkNotNull(workspace.state.value.aiConfigurationChoice)
                check(invalidChoice.source.plan == null && workspace.state.value.aiConfiguration == null)
                withContext(Dispatchers.Main) { workspace.chooseAiConfiguration(invalidChoice.owner, true) }
                check(workspace.state.value.aiConfigurationChoice === invalidChoice && workspace.state.value.aiConfiguration == null)
                withContext(Dispatchers.Main) { workspace.chooseAiConfiguration(invalidChoice.owner, false) }
                assertDefaults(workspace, fixture)
                check(workspace.state.value.aiConfigurationChanged && fixture.config.readBytes().contentEquals(invalid))
                val releaseBefore = hashes(fixture.release)
                val confirmed = completeDefaultPath(workspace, fixture)
                check(fixture.config.readBytes().contentEquals(invalid))
                withContext(Dispatchers.Main) { workspace.prepareAiExport() }
                settled(workspace)
                check(!workspace.state.value.aiSaveNeedsCheck && !workspace.state.value.aiConfigurationChanged)
                check(workspace.state.value.exportFile?.isFile == true)
                check(workspace.state.value.message == "已核对，本机配置与本页一致；接下来选择保存位置。")
                check(fixture.config.readBytes().contentEquals(confirmed))
                check(hashes(fixture.release) == releaseBefore)
            }
        }
    }

    @Test fun failedOpenPreservesEditorBaselineAndStaleChoicesCannotReplaceAnotherOwner() = runBlocking {
        withTimeout(120_000) {
            val first = withContext(Dispatchers.IO) { importFixture().also { it.config.writeBytes(it.plan.toBytes()) } }
            val second = withContext(Dispatchers.IO) { importFixture().also { it.config.writeBytes(it.plan.toBytes()) } }
            withWorkspace { workspace ->
                open(workspace, first)
                withContext(Dispatchers.Main) {
                    workspace.chooseAiConfiguration(checkNotNull(workspace.state.value.aiConfigurationChoice).owner, true)
                    val visit = checkNotNull(workspace.state.value.aiConfiguration).visits.first()
                    workspace.setAiHold(visit.visitId, visit.holdFrames + 1)
                }
                val beforeFailure = workspace.state.value
                val filesBefore = hashes(first.root)
                check(beforeFailure.aiConfigurationChanged)
                withContext(Dispatchers.Main) { workspace.openAiPackage(UUID.randomUUID().toString()) }
                settled(workspace)
                check(workspace.state.value.message != null)
                check(workspace.state.value.aiConfiguration === beforeFailure.aiConfiguration)
                check(workspace.state.value.aiConfigurationOwner == beforeFailure.aiConfigurationOwner)
                check(workspace.state.value.aiConfigurationChanged && workspace.state.value.aiReplacesRetainedConfiguration)
                withContext(Dispatchers.Main) {
                    val visit = first.plan.visits.first()
                    workspace.setAiHold(visit.visitId, visit.holdFrames)
                }
                check(!workspace.state.value.aiConfigurationChanged) { "A failed read must preserve the original baseline" }

                // Actual route departure ends this editor's owner even when its retained plan is clean.
                withContext(Dispatchers.Main) { workspace.leaveAiPage() }
                check(workspace.state.value.aiConfiguration == null && workspace.state.value.aiConfigurationOwner == null)
                check(workspace.state.value.aiConfigurationChoice == null && !workspace.state.value.aiConfigurationChanged)
                check(!workspace.state.value.aiReplacesRetainedConfiguration && !workspace.state.value.aiSaveNeedsCheck)
                withContext(Dispatchers.Main) { workspace.openAiPackage(UUID.randomUUID().toString()) }
                settled(workspace)
                check(workspace.state.value.message != null)
                check(workspace.state.value.aiConfiguration == null && workspace.state.value.aiConfigurationOwner == null) {
                    "Failure after leaving must not restore the prior route's configuration or owner"
                }
                check(workspace.state.value.aiConfigurationChoice == null && !workspace.state.value.aiConfigurationChanged)
                check(!workspace.state.value.aiReplacesRetainedConfiguration && !workspace.state.value.aiSaveNeedsCheck)

                open(workspace, first)
                val oldChoice = checkNotNull(workspace.state.value.aiConfigurationChoice)
                open(workspace, second)
                val currentChoice = checkNotNull(workspace.state.value.aiConfigurationChoice)
                check(oldChoice.owner != currentChoice.owner)
                withContext(Dispatchers.Main) { workspace.chooseAiConfiguration(oldChoice.owner, true) }
                check(workspace.state.value.aiConfiguration == null && workspace.state.value.aiConfigurationChoice === currentChoice)
                withContext(Dispatchers.Main) {
                    workspace.leaveAiPage()
                    workspace.chooseAiConfiguration(currentChoice.owner, false)
                }
                check(workspace.state.value.aiConfigurationChoice == null && workspace.state.value.aiConfiguration == null)
                open(workspace, second)
                val reopened = checkNotNull(workspace.state.value.aiConfigurationChoice)
                check(reopened.owner != currentChoice.owner)
                withContext(Dispatchers.Main) { workspace.chooseAiConfiguration(currentChoice.owner, true) }
                check(workspace.state.value.aiConfigurationChoice === reopened && workspace.state.value.aiConfiguration == null)
                check(hashes(first.root) == filesBefore)
            }
        }
    }

    @Test fun cancellationAtRealCommitBoundariesReportsDiskTruthRatherThanCacheSuccess() = runBlocking {
        withTimeout(120_000) {
            for (afterCommit in listOf(false, true)) {
                val fixture = withContext(Dispatchers.IO) { importFixture().also { it.config.writeBytes(it.plan.toBytes()) } }
                withWorkspace { workspace ->
                    val target = openDefaultsForSave(workspace, fixture)
                    val original = fixture.config.readBytes()
                    val gate = ConfigurationSyncShadow.arm(if (afterCommit) fixture.config.parentFile!! else fixture.export.parentFile!!)
                    try {
                        withContext(Dispatchers.Main) { workspace.prepareAiExport() }
                        check(gate.reached.await(30, TimeUnit.SECONDS)) { "Save did not reach the requested real directory fsync" }
                        check(workspace.state.value.busy && workspace.state.value.aiSaveNeedsCheck)
                        check(fixture.export.isFile) { "Both gates follow verified package-cache installation" }
                        check(fixture.config.readBytes().contentEquals(if (afterCommit) target else original))
                        withContext(Dispatchers.Main) { workspace.cancel() }
                        check(workspace.state.value.busy) { "Cancellation must retain the owner while the syscall is blocked" }
                    } finally { gate.release.countDown() }
                    settled(workspace)
                    check(checkNotNull(task(workspace)).isCancelled)
                    check(!workspace.state.value.aiSaveNeedsCheck && workspace.state.value.exportFile == null)
                    check(workspace.state.value.aiConfigurationChanged == !afterCommit)
                    check(checkNotNull(workspace.state.value.aiConfiguration).resolve().toBytes().contentEquals(target))
                    check(fixture.config.readBytes().contentEquals(if (afterCommit) target else original))
                    check(workspace.state.value.message == if (afterCommit)
                        "已核对，本机配置与本页一致；动画包尚未另存。"
                    else "已核对，本机配置仍是保存前的内容。本页输入保留，可检查后重试。")
                    withContext(Dispatchers.Main) { check(!workspace.beginExportPicker()) }
                }
            }
        }
    }

    @Test fun unknownAndChangedReadbackBlockWritesUntilReadOnlyRecheckFindsTarget() = runBlocking {
        withTimeout(120_000) {
            val fixture = withContext(Dispatchers.IO) { importFixture().also { it.config.writeBytes(it.plan.toBytes()) } }
            withWorkspace { workspace ->
                val target = openDefaultsForSave(workspace, fixture)
                val edited = checkNotNull(workspace.state.value.aiConfiguration)
                val owner = workspace.state.value.aiConfigurationOwner
                val metadata = File(fixture.release, "summary.json")
                val validMetadata = metadata.readBytes()
                val gate = ConfigurationSyncShadow.arm(fixture.config.parentFile!!)
                try {
                    withContext(Dispatchers.Main) { workspace.prepareAiExport() }
                    check(gate.reached.await(30, TimeUnit.SECONDS))
                    check(fixture.config.readBytes().contentEquals(target))
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
                    workspace.setAiHold(edited.visits.first().visitId, 120)
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
                fixture.config.writeText("SYNTHETIC INDEPENDENT CONFIGURATION CHANGE")
                val changedFiles = hashes(fixture.root)
                withContext(Dispatchers.Main) { workspace.recheckAiSave() }
                settled(workspace)
                check(workspace.state.value.aiSaveNeedsCheck && workspace.state.value.aiConfiguration === edited)
                check(workspace.state.value.message == "版本或本机配置已变化，未继续覆盖。本页输入保留，请重新核对；返回后可重新选择配置来源。")
                check(hashes(fixture.root) == changedFiles)

                fixture.config.writeBytes(target)
                val matchingFiles = hashes(fixture.root)
                withContext(Dispatchers.Main) { workspace.recheckAiSave() }
                settled(workspace)
                check(!workspace.state.value.aiSaveNeedsCheck && !workspace.state.value.aiConfigurationChanged)
                check(workspace.state.value.aiConfiguration === edited && workspace.state.value.aiConfigurationOwner == owner)
                check(workspace.state.value.message == "已核对，本机配置与本页一致；动画包尚未另存。")
                check(workspace.state.value.exportFile == null && hashes(fixture.root) == matchingFiles)
                withContext(Dispatchers.Main) {
                    workspace.setAiHold(edited.visits.first().visitId, 120)
                    check(workspace.state.value.aiConfigurationChanged)
                    workspace.setAiHold(edited.visits.first().visitId, edited.visits.first().holdFrames)
                    check(!workspace.state.value.aiConfigurationChanged)
                }
                check(hashes(fixture.root) == matchingFiles) { "Recovery must only read; it must never repeat the save" }
            }
        }
    }

    private suspend fun open(workspace: ReleaseWorkspace, fixture: Fixture) {
        withContext(Dispatchers.Main) { workspace.openAiPackage(fixture.scene.releaseId) }
        settled(workspace)
        check(workspace.state.value.message == null) { "Open failed: ${workspace.state.value.message}" }
    }

    private fun assertDefaults(workspace: ReleaseWorkspace, fixture: Fixture) {
        val config = checkNotNull(workspace.state.value.aiConfiguration)
        check(config.scene.releaseId == fixture.scene.releaseId && !config.fromDraft)
        check(config.width == 1080 && config.height == 1920 && config.effects.isEmpty())
        check(config.visits.single().let { it.stateId == fixture.scene.startStateId && it.selectedEdgeId == null && it.holdFrames == 90 })
    }

    private suspend fun completeDefaultPath(workspace: ReleaseWorkspace, fixture: Fixture): ByteArray =
        withContext(Dispatchers.Main) {
            workspace.chooseAiEdge(fixture.scene.edges.single().id)
            checkNotNull(workspace.state.value.aiConfiguration).resolve().toBytes()
        }

    private suspend fun openDefaultsForSave(workspace: ReleaseWorkspace, fixture: Fixture): ByteArray {
        open(workspace, fixture)
        withContext(Dispatchers.Main) {
            workspace.chooseAiConfiguration(checkNotNull(workspace.state.value.aiConfigurationChoice).owner, false)
        }
        return completeDefaultPath(workspace, fixture)
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

    private data class Fixture(val root: File, val scene: ViewerScene, val plan: RenderPlan) {
        val release = File(root, "releases/${scene.releaseId}")
        val config = File(root, "ai-configs/${scene.releaseId}.json")
        val export = File(root, "exports/${scene.releaseId}.tapscene-ai")
    }

    private suspend fun importFixture(): Fixture {
        val releaseId = UUID.randomUUID().toString()
        fun id(name: String) = UUID.nameUUIDFromBytes("$releaseId/$name".toByteArray()).toString()
        val source = File(app.noBackupFilesDir, "imported-ai-flow-$releaseId").also { check(it.mkdir()) }
        val assets = File(source, "assets").also { check(it.mkdir()) }
        val images = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
            val assetId = id("image-$index")
            val image = File(assets, "$assetId.png")
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            ViewerScene.Asset(assetId, "assets/$assetId.png", "image/png", image.length(), ViewerPackageCodec.sha256(image), 32, 48)
        }
        val states = images.mapIndexed { index, image ->
            ViewerScene.State(id("state-$index"), image.id, 32, 48, "合成画面 ${index + 1}", "仅供导入配置检查", "authored", index == 1)
        }
        val edge = ViewerScene.Edge(id("edge"), states[0].id, states[1].id, null, null, "下一步", "continue", "authored")
        val scene = ViewerScene(releaseId, "Imported configuration choices", "Synthetic PNG fixture", 1L,
            states[0].id, states, listOf(edge), emptyList(), images)
        val zip = File(app.noBackupFilesDir, "imported-ai-flow-$releaseId.tapscene")
        ViewerPackageCodec.writePackage(scene, source, zip, {}, null)
        val summary = zip.inputStream().use { ReleaseStore(app).importPackage(it) }
        check(summary.id == releaseId && summary.origin == "imported")
        val firstVisit = RenderPlan.Visit(id("visit-start"), states[0].id, edge.id, 120)
        val plan = RenderPlan.build(scene, 1920, 1080,
            listOf(firstVisit, RenderPlan.Visit(id("visit-end"), states[1].id, null, 60)),
            listOf(RenderPlan.Effect("annotation", firstVisit.visitId, 0, firstVisit.holdFrames,
                null, null, "Retained synthetic note", ViewerScene.Rect(.08, .08, .84, .14))))
        return Fixture(File(app.noBackupFilesDir, "release-store"), scene, plan)
    }
}
