package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ReleaseStore
import com.tapscene.packageformat.AiPackageCodec
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import java.io.File
import java.util.UUID
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

/** Real PNG import, workspace edits and AI export; system picker UI and device lifecycle are not run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class AiConfigurationRetentionHostTest {
    @Before fun configure() {
        HostSqlite.configure()
        HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
    }
    @After fun closeDescriptors() = HostFileSyncShadow.reset()

    @Test fun unfinishedConfigurationTracksFieldChangesAndRestoringValuesClearsChanges() = runBlocking {
        withTimeout(120_000) {
            val scene = withContext(Dispatchers.IO) { importFixture(RuntimeEnvironment.getApplication()) }
            withAiWorkspace(scene) { workspace ->
                withContext(Dispatchers.Main) {
                    val original = checkNotNull(workspace.state.value.aiConfiguration)
                    val visit = original.visits.single()
                    check(!original.fromDraft && !workspace.state.value.aiConfigurationChanged)
                    check(runCatching { original.resolve() }.isFailure) { "The initial nonterminal path must be unfinished" }

                    workspace.setAiCanvas(true)
                    check(workspace.state.value.aiConfigurationChanged)
                    check(workspace.state.value.aiConfiguration?.width == 1920)
                    workspace.setAiCanvas(false)
                    check(!workspace.state.value.aiConfigurationChanged)

                    workspace.setAiHold(visit.visitId, 120)
                    check(workspace.state.value.aiConfigurationChanged)
                    check(workspace.state.value.aiConfiguration?.visits?.single()?.holdFrames == 120)
                    workspace.setAiHold(visit.visitId, visit.holdFrames)
                    check(workspace.state.value.aiConfiguration?.visits?.single() !== visit)
                    check(!workspace.state.value.aiConfigurationChanged) { "Restored Visit fields must compare by value" }

                    workspace.applyAiAnnotation(visit.visitId, "Synthetic annotation")
                    check(workspace.state.value.aiConfigurationChanged)
                    check(workspace.state.value.aiConfiguration?.effects?.single()?.text == "Synthetic annotation")
                    workspace.removeAiAnnotation(visit.visitId)
                    check(!workspace.state.value.aiConfigurationChanged)

                    workspace.chooseAiEdge(scene.edges[0].id)
                    val partial = checkNotNull(workspace.state.value.aiConfiguration)
                    check(partial.visits.map { it.stateId } == scene.states.take(2).map { it.id })
                    check(partial.visits.first().selectedEdgeId == scene.edges[0].id)
                    check(partial.visits.last().selectedEdgeId == null)
                    check(runCatching { partial.resolve() }.isFailure) { "Dirty tracking must not require a complete render plan" }
                    check(workspace.state.value.aiConfigurationChanged)
                    check(workspace.state.value.message == null)

                    workspace.previousAiVisit()
                    val restored = checkNotNull(workspace.state.value.aiConfiguration)
                    check(restored.visits.single().visitId == visit.visitId)
                    check(restored.visits.single().stateId == visit.stateId && restored.visits.single().selectedEdgeId == null)
                    check(restored.visits.single().holdFrames == visit.holdFrames && restored.effects.isEmpty())
                    check(!workspace.state.value.aiConfigurationChanged)
                }
                check(withContext(Dispatchers.IO) { ReleaseStore(RuntimeEnvironment.getApplication()).readAiPlan(scene.releaseId) } == null)
            }
        }
    }

    @Test fun successfulExportBecomesCleanAndPickerCancellationPreservesExactPlanOnReopen() = runBlocking {
        withTimeout(120_000) {
            val app = RuntimeEnvironment.getApplication()
            val scene = withContext(Dispatchers.IO) { importFixture(app) }
            val expected = withAiWorkspace(scene) { workspace ->
                val planBytes = withContext(Dispatchers.Main) {
                    workspace.chooseAiEdge(scene.edges[0].id)
                    workspace.chooseAiEdge(scene.edges[1].id)
                    val first = checkNotNull(workspace.state.value.aiConfiguration).visits.first()
                    workspace.setAiCanvas(true)
                    workspace.setAiHold(first.visitId, 150)
                    workspace.applyAiAnnotation(first.visitId, "Retained synthetic annotation")
                    check(workspace.state.value.aiConfigurationChanged)
                    checkNotNull(workspace.state.value.aiConfiguration).resolve().toBytes().also { workspace.prepareAiExport() }
                }
                settled(workspace, allowSaveMessage = true)
                check(!workspace.state.value.aiSaveNeedsCheck)
                check(workspace.state.value.message == "已核对，本机配置与本页一致；接下来选择保存位置。")
                check(!workspace.state.value.aiConfigurationChanged) { "The persisted plan must be clean before opening the system picker" }
                val exported = checkNotNull(workspace.state.value.exportFile)
                withContext(Dispatchers.Main) { workspace.leaveDeliveryPage() }
                check(workspace.state.value.exportFile === exported && !workspace.state.value.aiConfigurationChanged) {
                    "Leaving delivery must not consume an AI export or its confirmed configuration"
                }
                check(exported.isFile && exported.length() > 0 && exported.name.endsWith(".tapscene-ai"))
                withContext(Dispatchers.IO) {
                    val verified = File(app.noBackupFilesDir, "ai-retention-readback-${UUID.randomUUID()}").also { check(it.mkdir()) }
                    val loaded = AiPackageCodec.readPackage(exported, verified, {}, null)
                    check(loaded.renderPlan.toBytes().contentEquals(planBytes))
                    check(loaded.contentDigest == ViewerPackageCodec.contentDigest(scene))
                    check(checkNotNull(ReleaseStore(app).readAiPlan(scene.releaseId)).toBytes().contentEquals(planBytes))
                }
                withContext(Dispatchers.Main) {
                    check(workspace.beginExportPicker())
                    check(workspace.state.value.exportFile == null && !workspace.state.value.aiConfigurationChanged)
                    val pendingFile = ReleaseWorkspace::class.java.getDeclaredField("pendingExportFile").apply { isAccessible = true }
                    val pickerPending = ReleaseWorkspace::class.java.getDeclaredField("savePickerPending").apply { isAccessible = true }
                    val completedTask = task(workspace)
                    check(pendingFile.get(workspace) === exported && pickerPending.getBoolean(workspace))
                    workspace.leaveAiPage()
                    check(workspace.state.value.aiConfiguration == null && workspace.state.value.aiConfigurationOwner == null)
                    check(workspace.state.value.aiConfigurationChoice == null && !workspace.state.value.aiConfigurationChanged)
                    check(!workspace.state.value.aiSaveNeedsCheck && !workspace.state.value.aiReplacesRetainedConfiguration)
                    check(task(workspace) === completedTask)
                    check(pendingFile.get(workspace) === exported && pickerPending.getBoolean(workspace)) {
                        "Route departure must preserve the exact export already owned by the pending picker"
                    }
                    check(exported.isFile && !workspace.beginExportPicker())
                    // Exercise the existing picker cancellation callback, not an external SAF provider.
                    workspace.cancelExportPicker()
                    check(pendingFile.get(workspace) == null && !pickerPending.getBoolean(workspace))
                    check(workspace.state.value.aiConfiguration == null && !workspace.state.value.aiConfigurationChanged)
                }
                withContext(Dispatchers.IO) {
                    check(checkNotNull(ReleaseStore(app).readAiPlan(scene.releaseId)).toBytes().contentEquals(planBytes))
                }
                planBytes
            }

            // A new ViewModelStore forces the baseline and plan to be loaded from actual persisted metadata.
            withAiWorkspace(scene) { reopened ->
                withContext(Dispatchers.Main) {
                    val saved = checkNotNull(reopened.state.value.aiConfiguration)
                    check(!saved.fromDraft && !reopened.state.value.aiConfigurationChanged)
                    check(saved.resolve().toBytes().contentEquals(expected))
                    val annotation = saved.effects.single()
                    reopened.applyAiAnnotation(annotation.visitId, "Changed synthetic annotation")
                    check(reopened.state.value.aiConfigurationChanged)
                    reopened.applyAiAnnotation(annotation.visitId, annotation.text)
                    check(!reopened.state.value.aiConfigurationChanged) { "An equivalent new Java Effect must be clean" }
                    reopened.removeAiAnnotation(annotation.visitId)
                    check(reopened.state.value.aiConfigurationChanged)
                    reopened.applyAiAnnotation(annotation.visitId, annotation.text)
                    val restored = checkNotNull(reopened.state.value.aiConfiguration)
                    check(restored.effects.single() !== annotation && restored.effects.single().rect !== annotation.rect)
                    check(!reopened.state.value.aiConfigurationChanged) { "An equivalent new rectangle must compare by its fields" }
                    check(restored.resolve().toBytes().contentEquals(expected))
                }
            }
            withContext(Dispatchers.IO) {
                val store = ReleaseStore(app)
                check(checkNotNull(store.readAiPlan(scene.releaseId)).toBytes().contentEquals(expected))
                check(ViewerPackageCodec.contentDigest(store.loadRelease(scene.releaseId)) == ViewerPackageCodec.contentDigest(scene))
            }
        }
    }

    private suspend fun <T> withAiWorkspace(scene: ViewerScene, block: suspend (ReleaseWorkspace) -> T): T {
        val app = RuntimeEnvironment.getApplication()
        val owner = ViewModelStore()
        val workspace = withContext(Dispatchers.Main) {
            ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[ReleaseWorkspace::class.java]
        }
        try {
            settled(workspace)
            check(workspace.state.value.aiConfiguration == null && !workspace.state.value.aiConfigurationChanged)
            withContext(Dispatchers.Main) { workspace.openAiPackage(scene.releaseId) }
            settled(workspace)
            workspace.state.value.aiConfigurationChoice?.let { choice ->
                check(workspace.state.value.aiConfiguration == null && choice.source.plan != null)
                withContext(Dispatchers.Main) { workspace.chooseAiConfiguration(choice.owner, true) }
            }
            check(workspace.state.value.aiConfiguration?.scene?.releaseId == scene.releaseId)
            return block(workspace)
        } finally {
            withContext(NonCancellable) {
                val pending = withContext(Dispatchers.Main) { task(workspace).also { owner.clear() } }
                pending?.join()
            }
        }
    }

    private suspend fun settled(workspace: ReleaseWorkspace, allowSaveMessage: Boolean = false) {
        checkNotNull(task(workspace)).join()
        check(!workspace.state.value.busy && workspace.state.value.stage == null)
        if (!allowSaveMessage) check(workspace.state.value.message == null) {
            "Workspace operation failed: ${workspace.state.value.message}"
        }
    }

    private fun task(workspace: ReleaseWorkspace) = ReleaseWorkspace::class.java.getDeclaredField("task")
        .apply { isAccessible = true }.get(workspace) as Job?

    private suspend fun importFixture(app: Application): ViewerScene {
        val releaseId = UUID.randomUUID().toString()
        fun id(name: String) = UUID.nameUUIDFromBytes("$releaseId/$name".toByteArray()).toString()
        val root = File(app.noBackupFilesDir, "ai-retention-$releaseId").also { check(it.mkdir()) }
        val assets = File(root, "assets").also { check(it.mkdir()) }
        val images = listOf(Color.RED, Color.GREEN, Color.BLUE).mapIndexed { index, color ->
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
            ViewerScene.State(id("state-$index"), image.id, 32, 48, "画面 ${index + 1}", "合成配置测试", "authored", index == 2)
        }
        val edges = (0..1).map { index ->
            ViewerScene.Edge(id("edge-$index"), states[index].id, states[index + 1].id, null, null, "下一步", "continue", "authored")
        }
        val scene = ViewerScene(releaseId, "AI configuration retention", "Synthetic PNG fixture", 1L,
            states[0].id, states, edges, emptyList(), images)
        val file = File(app.noBackupFilesDir, "ai-retention-$releaseId.tapscene")
        ViewerPackageCodec.writePackage(scene, root, file, {}, null)
        val imported = file.inputStream().use { ReleaseStore(app).importPackage(it) }
        check(imported.id == releaseId && imported.contentDigest == ViewerPackageCodec.contentDigest(scene))
        return scene
    }
}
