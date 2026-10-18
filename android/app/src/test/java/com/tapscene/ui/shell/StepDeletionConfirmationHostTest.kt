package com.tapscene.ui.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.AiDraftImportFixtures
import com.tapscene.data.AiDraftImportStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real MainActivity, SQLite and imported PNGs; deletion is limited to this synthetic fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class StepDeletionConfirmationHostTest {
    private var previousRequestedSize: String? = null
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            RuntimeEnvironment.setFontScale(1.3f)
            val root = RuntimeEnvironment.getApplication().noBackupFilesDir.canonicalFile
            val sandbox = RuntimeEnvironment.getTempDirectory().basePath.toRealPath()
            check(root.toPath().startsWith(sandbox) && root.toPath() != sandbox)
            root.listFiles().orEmpty().forEach { child ->
                check(child.canonicalFile.toPath().startsWith(root.toPath()))
                check(child.deleteRecursively())
            }
            HostFileSyncShadow.begin(root)
            previousRequestedSize = System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        }
        override fun after() {
            try { HostFileSyncShadow.reset() } finally {
                resetApplicationViewModelFactory()
                RuntimeEnvironment.setFontScale(1f)
                val key = "robolectric.windowManager.useRequestedSize"
                previousRequestedSize?.let { System.setProperty(key, it) } ?: System.clearProperty(key)
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun exactStepAndStartImpactStayFrozenUntilExplicitCurrentConfirmation() {
        waitFor("悬浮编排")
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val store = ProjectStore(app)
        fun awaitIdle() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !media.state.value.busy && !releases.state.value.busy
        }
        awaitIdle()
        val before = runBlocking(Dispatchers.IO) {
            val fixture = AiDraftImportFixtures.complete(app)
            val imports = AiDraftImportStore(app)
            val input = fixture.zip.inputStream().use { imports.prepare(it) }
            val id = checkNotNull(imports.commit(input.sessionId, input.previewDigest).projectId)
            var snapshot = checkNotNull(store.readProject(id))
            for (step in snapshot.steps) snapshot = store.saveStepDraft(id, step.id, "同名",
                step.description, step.isTerminal, step.hotspots, snapshot.project.revision, step.nextAction)
            store.setStartStep(id, snapshot.steps[1].id)
        }
        val projectId = before.project.id
        val first = before.steps[0]
        val target = before.steps[1]
        val retained = before.steps[2]
        assertEquals(target.id, before.project.startStepId)
        assertEquals(3, before.steps.count { it.title == "同名" })
        fun savedFiles() = File(app.noBackupFilesDir, "project-assets/$projectId").walkTopDown()
            .filter { it.isFile }.associate { it.relativeTo(app.noBackupFilesDir).path to ViewerPackageCodec.sha256(it) }
        val filesBefore = savedFiles()
        compose.runOnIdle { projects.reload() }
        awaitIdle()
        compose.onNodeWithText(before.project.title).performClick()
        waitFor("分镜")
        awaitIdle()
        // Seed a genuine retained editor draft through the production workspace, without saving it.
        compose.runOnIdle { projects.openStep(retained.id) }
        awaitIdle()
        compose.runOnIdle { projects.editDescription("保留的未保存讲解") }
        compose.onNodeWithText("返回").performClick()
        waitFor("分镜")
        awaitIdle()
        val draftBefore = store.readEditorDrafts(projectId)
        assertEquals("保留的未保存讲解", draftBefore.getValue(retained.id).edit.description)
        fun openDelete(ordinal: String) {
            val more = hasContentDescription("步骤 $ordinal 同名，更多操作")
            compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(more)
            compose.onNode(more).assertIsDisplayed().performClick()
            compose.onNodeWithText("删除步骤").performClick()
            waitFor("删除")
            compose.onNodeWithText("删除步骤 $ordinal · 同名？").assertIsDisplayed()
        }
        openDelete("02")
        compose.onNodeWithText("删除后，当前步骤 01 · 同名将成为起点。", substring = true).assertIsDisplayed()
        compose.onNodeWithText("删除后需要重新设置起点。", substring = true).assertDoesNotExist()
        captureDialog("same-title-start-small-large-font")
        compose.onNodeWithText("取消").performClick()
        assertEquals(before, store.readProject(projectId))
        assertEquals(filesBefore, savedFiles())
        assertEquals(draftBefore, store.readEditorDrafts(projectId))

        openDelete("02")
        val changed = runBlocking(Dispatchers.IO) {
            store.reorderSteps(projectId, listOf(first.id, retained.id, target.id))
        }
        // The dialog owns the old identity/revision even after a genuine external persisted reorder.
        compose.runOnIdle { projects.reload() }
        awaitIdle()
        compose.waitUntil(30_000) {
            store.readEditorDrafts(projectId)[retained.id]?.baseRevision == changed.project.revision
        }
        val changedDraft = store.readEditorDrafts(projectId)
        compose.onNodeWithText("删除步骤 02 · 同名？").assertIsDisplayed()
        compose.onNodeWithText("项目内容已变化，请取消后重新检查删除影响。", substring = true).assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsNotEnabled().performClick()
        captureDialog("changed-revision-disabled")
        assertEquals(changed, store.readProject(projectId))
        assertEquals(filesBefore, savedFiles())
        assertEquals(changedDraft, store.readEditorDrafts(projectId))
        val rejection = assertThrows(IllegalArgumentException::class.java) {
            store.deleteStep(projectId, target.id, expectedRevision = before.project.revision)
        }
        assertEquals("项目内容已变化，请重新检查删除影响。", rejection.message)
        assertEquals(changed, store.readProject(projectId))
        assertEquals(filesBefore, savedFiles())
        assertEquals(changedDraft, store.readEditorDrafts(projectId))
        compose.runOnIdle { projects.deleteStep(target.id, projectId, before.project.revision) }
        awaitIdle()
        assertEquals("项目内容已变化，请重新检查删除影响。", projects.state.value.message)
        assertEquals(changed, store.readProject(projectId))
        assertEquals(filesBefore, savedFiles())
        assertEquals(changedDraft, store.readEditorDrafts(projectId))
        compose.onNodeWithText("取消").performClick()
        openDelete("03")
        compose.onNodeWithText("删除后，当前步骤 01 · 同名将成为起点。", substring = true).assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsEnabled().performClick()
        awaitIdle()
        val deleted = checkNotNull(store.readProject(projectId))
        assertEquals(changed.project.revision + 1, deleted.project.revision)
        assertEquals(listOf(first.id, retained.id), deleted.steps.map { it.id })
        assertEquals(first.id, deleted.project.startStepId)
        assertEquals(retained.description, deleted.steps.single { it.id == retained.id }.description)
        assertTrue(projects.state.value.message.orEmpty().contains("；起点已改为步骤 01 · 同名"))
        assertEquals(setOf(retained.id), projects.state.value.dirtyStepIds)
        val retainedAsset = store.resolveAsset(projectId, retained.id)
        assertEquals(retained.asset.sha256, ViewerPackageCodec.sha256(retainedAsset))
        compose.runOnIdle { projects.openStep(retained.id) }
        awaitIdle()
        assertEquals("保留的未保存讲解", projects.state.value.stepDraft?.description)
        compose.onNodeWithText("返回").performClick()
        waitFor("分镜")
        awaitIdle()
        assertEquals(ProjectRoute.STEPS, projects.state.value.route)
        assertEquals(deleted, store.readProject(projectId))
        assertEquals("保留的未保存讲解", store.readEditorDrafts(projectId).getValue(retained.id).edit.description)

        openDelete("02")
        compose.onNodeWithText("将成为起点。", substring = true).assertDoesNotExist()
        compose.onNodeWithText("删除").performClick()
        awaitIdle()
        assertEquals(first.id, store.readProject(projectId)?.project?.startStepId)
        assertFalse(projects.state.value.message.orEmpty().contains("起点已改为"))
        openDelete("01")
        compose.onNodeWithText("删除后项目将没有步骤和起点。", substring = true).assertIsDisplayed()
        compose.onNodeWithText("删除").performClick()
        awaitIdle()
        val empty = checkNotNull(store.readProject(projectId))
        assertTrue(empty.steps.isEmpty())
        assertNull(empty.project.startStepId)
        assertTrue(projects.state.value.message.orEmpty().contains("；项目已没有步骤和起点"))
        assertTrue(store.readEditorDrafts(projectId).isEmpty())
        println("HOST_STEP_DELETE exact-ID/ordinal/cancel/stale-confirmation/stale-store-and-workspace/first-remaining-start/retained-draft/non-start/last-step PASS; synthetic fixture only; device NOT_RUN")
    }

    private fun captureDialog(name: String) {
        // Draw the actual dialog owner, not the Activity below it; native host graphics, not a device.
        val root = compose.onNode(isDialog()).fetchSemanticsNode().root as ViewRootForTest
        compose.runOnIdle {
            val view = root.view
            check(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val destination = File("build/reports/step-delete-layouts/$name.png")
                check(requireNotNull(destination.parentFile).mkdirs() || destination.parentFile!!.isDirectory)
                destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun resetApplicationViewModelFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
