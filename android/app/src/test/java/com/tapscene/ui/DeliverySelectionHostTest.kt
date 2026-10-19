package com.tapscene.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.AiDraftImportFixtures
import com.tapscene.data.AiDraftImportStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReleaseStore
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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

/** Actual sealed files, retained provenance and workspace; no system picker or device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class DeliverySelectionHostTest {
    @Before fun configure() {
        HostSqlite.configure()
        HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
    }
    @After fun closeDescriptors() = HostFileSyncShadow.reset()

    @Test fun explicitOldVersionSurvivesWorkspaceReopenAndRejectsCrossProjectAndLateExport() = runBlocking {
        withTimeout(120_000) {
            val app = RuntimeEnvironment.getApplication()
            val projects = ProjectStore(app)
            val store = ReleaseStore(app)
            val fixture = withContext(Dispatchers.IO) { AiDraftImportFixtures.complete(app) }
            val projectId = withContext(Dispatchers.IO) {
                val imports = AiDraftImportStore(app)
                val input = fixture.zip.inputStream().use { imports.prepare(it) }
                checkNotNull(imports.commit(input.sessionId, input.previewDigest).projectId)
            }
            val old = withContext(Dispatchers.IO) {
                AiDraftImportFixtures.reviewDraftRegions(projects, projectId)
                AiDraftImportFixtures.sealFixture(app, projectId, fixture)
            }
            val oldBinding = withContext(Dispatchers.IO) { store.readHostedRelease(old.id, old.contentDigest) }
            val oldRoot = File(app.noBackupFilesDir, "release-store/releases/${old.id}")
            fun oldFiles() = oldRoot.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(oldRoot).path to ViewerPackageCodec.sha256(it) }
            val oldBytes = oldFiles()
            check(oldBytes.isNotEmpty() && oldBinding.projectId == projectId)
            val latest = withContext(Dispatchers.IO) {
                val edited = projects.renameProject(projectId, old.title, "Only the later draft contains this opening.")
                val plan = checkNotNull(projects.readDraftAiConfig(projectId))
                check(!projects.saveDraftAiConfig(projectId, edited.project.revision, plan).needsRepair)
                AiDraftImportFixtures.sealFixture(app, projectId, fixture)
            }
            check(old.id != latest.id)
            val latestBinding = withContext(Dispatchers.IO) { store.readHostedRelease(latest.id, latest.contentDigest) }
            check(latestBinding.projectRevision > oldBinding.projectRevision)
            check(latestBinding.scene.goal != oldBinding.scene.goal && oldFiles() == oldBytes)
            var owner = ViewModelStore()
            suspend fun openWorkspace(): ReleaseWorkspace {
                val result = withContext(Dispatchers.Main) {
                    ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[ReleaseWorkspace::class.java]
                }
                settled(result)
                return result
            }
            var workspace = openWorkspace()
            try {
                check(workspace.state.value.lastSealedId == null && workspace.state.value.deliverySelection == null)
                withContext(Dispatchers.Main) { workspace.selectDeliveryRelease(old.id) }; settled(workspace)
                val selection = DeliverySelection(old.id, old.contentDigest, projectId, oldBinding.projectRevision)
                check(workspace.state.value.deliverySelection == selection && workspace.state.value.deliveryRelease == old)
                check(workspace.state.value.deliverySelection?.projectRevision != latestBinding.projectRevision)
                check(workspace.state.value.lastSealedId == null) // Selecting an old version is never a new seal.
                withContext(Dispatchers.Main) { workspace.openProjectDelivery(projectId) }
                check(workspace.state.value.deliverySelection == selection)
                withContext(Dispatchers.Main) { workspace.openProjectDelivery(UUID.randomUUID().toString()) }
                check(workspace.state.value.deliverySelection == null && workspace.state.value.deliveryRelease == null)
                withContext(Dispatchers.Main) { workspace.selectDeliveryRelease(old.id) }; settled(workspace)
                withContext(Dispatchers.Main) { workspace.prepareDeliveryExport() }; settled(workspace)
                check(workspace.state.value.exportFile?.isFile == true)
                withContext(Dispatchers.IO) {
                    val checked = File(app.noBackupFilesDir, "old-delivery-export").apply { check(mkdir()) }
                    val exported = ViewerPackageCodec.readPackage(checkNotNull(workspace.state.value.exportFile), checked, {}, null)
                    check(exported.contentDigest == old.contentDigest)
                    check(ViewerPackageCodec.writeScene(exported.scene).contentEquals(ViewerPackageCodec.writeScene(oldBinding.scene)))
                    check(oldFiles() == oldBytes)
                }
                withContext(Dispatchers.Main) { workspace.cancelExportPicker() }

                withContext(Dispatchers.IO) { projects.deleteProject(projectId) }
                withContext(Dispatchers.Main) { owner.clear() }
                owner = ViewModelStore(); workspace = openWorkspace()
                check(workspace.state.value.deliverySelection == null && workspace.state.value.lastSealedId == null)
                withContext(Dispatchers.Main) { workspace.selectDeliveryRelease(old.id) }; settled(workspace)
                check(workspace.state.value.deliverySelection == selection) // Retained review, no live project lookup.
                check(withContext(Dispatchers.IO) { store.readHostedRelease(old.id, old.contentDigest).projectRevision } == oldBinding.projectRevision)
                check(oldFiles() == oldBytes)

                val lock = ReleaseStore::class.java.getDeclaredField("mutex").apply { isAccessible = true }.get(null) as Mutex
                lock.lock()
                val pending: Job
                try {
                    withContext(Dispatchers.Main) { workspace.prepareDeliveryExport() }
                    check(workspace.state.value.busy)
                    pending = job(workspace)!!
                    withContext(Dispatchers.Main) { workspace.openProjectDelivery(UUID.randomUUID().toString()) }
                } finally { lock.unlock() }
                pending.join()
                check(!workspace.state.value.busy && workspace.state.value.stage == null)
                check(workspace.state.value.exportFile == null && workspace.state.value.deliverySelection == null)
                withContext(Dispatchers.Main) { check(!workspace.beginExportPicker()) }
                check(withContext(Dispatchers.IO) { store.loadRelease(old.id).releaseId } == old.id)

                // Leaving delivery must not cancel the library's independent save-to-file operation.
                withContext(Dispatchers.Main) { workspace.prepareExport(latest.id) }; settled(workspace)
                val independent = workspace.state.value.exportFile
                withContext(Dispatchers.Main) { workspace.leaveDeliveryPage() }
                check(independent != null && workspace.state.value.exportFile == independent)
                withContext(Dispatchers.Main) { workspace.cancelExportPicker() }
                val external = withContext(Dispatchers.IO) {
                    val file = File(app.noBackupFilesDir, "external-selection.tapscene")
                    ViewerPackageCodec.writePackage(fixture.scene, fixture.assetRoot, file, {}, null)
                    file.inputStream().use { store.importPackage(it) }
                }
                withContext(Dispatchers.Main) { workspace.reloadLibrary() }; settled(workspace)
                withContext(Dispatchers.Main) { workspace.selectDeliveryRelease(external.id) }; settled(workspace)
                check(external.origin == "imported" && workspace.state.value.deliveryRelease == null)
                withContext(Dispatchers.Main) { workspace.prepareExport(external.id) }; settled(workspace)
                check(workspace.state.value.exportFile?.isFile == true)
                withContext(Dispatchers.Main) { workspace.cancelExportPicker(); workspace.selectDeliveryRelease(old.id) }; settled(workspace)
                withContext(Dispatchers.Main) { workspace.deleteRelease(old) }; settled(workspace)
                check(workspace.state.value.deliverySelection == null && workspace.state.value.deliveryRelease == null)
                println("HOST_DELIVERY_SELECTION old-version/revision/digest/fixed-bytes/project-isolation/reopen/source-delete/late-export/import-boundary PASS; system picker/device NOT_RUN")
            } finally { withContext(Dispatchers.Main) { owner.clear() } }
        }
    }

    private fun job(workspace: ReleaseWorkspace) = ReleaseWorkspace::class.java.getDeclaredField("task")
        .apply { isAccessible = true }.get(workspace) as Job?
    private suspend fun settled(workspace: ReleaseWorkspace) { job(workspace)?.join(); check(!workspace.state.value.busy) }
}
