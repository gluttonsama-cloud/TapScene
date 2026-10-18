package com.tapscene.ui.shell

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.AiDraftImportFixtures
import com.tapscene.data.AiDraftImportStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

/** Real MainActivity, Compose, imported PNGs and SQLite; no device or privacy-review claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DeliveryIssueNavigationHostTest {
    private var previousRequestedSize: String? = null

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            val app = RuntimeEnvironment.getApplication()
            // Only this test's synthetic files inside Robolectric's private sandbox.
            val root = app.noBackupFilesDir.canonicalFile
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
                val key = "robolectric.windowManager.useRequestedSize"
                previousRequestedSize?.let { System.setProperty(key, it) } ?: System.clearProperty(key)
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun unreachableCopiedTerminalRepairsIncomingHotspotOnlyAfterExplicitSave() {
        waitFor("悬浮编排")
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        assertSame(app, projects.getApplication<android.app.Application>())
        val store = ProjectStore(app)
        fun awaitIdle() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !releases.state.value.busy && !media.state.value.busy
        }
        awaitIdle()
        val copyId = UUID.randomUUID().toString()
        val before = runBlocking(Dispatchers.IO) {
            val fixture = AiDraftImportFixtures.complete(app)
            val imports = AiDraftImportStore(app)
            val input = fixture.zip.inputStream().use { imports.prepare(it) }
            val projectId = checkNotNull(imports.commit(input.sessionId, input.previewDigest).projectId)
            // Synthetic region/plan preparation and the real saved-step copier establish the
            // starting graph. These calls are not UI or human review assertions.
            AiDraftImportFixtures.reviewDraftRegions(store, projectId)
            val imported = checkNotNull(store.readProject(projectId))
            assertTrue(ProjectWorkspace.graphIssues(imported).isEmpty())
            store.copySavedStep(projectId, fixture.endId, imported.project.revision, copyId)
        }
        val projectId = before.project.id
        val source = before.steps.single { it.id == before.project.startStepId }
        val copy = before.steps.single { it.id == copyId }
        val copyOrdinal = before.steps.indexOf(copy) + 1
        val sourceOrdinal = before.steps.indexOf(source) + 1
        val issue = ProjectWorkspace.graphIssues(before).single()
        assertEquals(copyId, issue.stepId)
        assertEquals(3, copyOrdinal)
        assertTrue(copy.isTerminal)
        // The duplicate deliberately retains its source's title: navigation must use the ID.
        assertEquals(2, before.steps.count { it.title == copy.title })
        assertEquals(2, source.hotspots.size)
        assertTrue(source.nextAction != null)
        assertFalse(source.hotspots.any { it.targetStepId == copyId })
        assertFalse(source.nextAction?.targetStepId == copyId)

        compose.runOnIdle { projects.reload() }
        awaitIdle()
        compose.onNodeWithText(before.project.title).performClick()
        waitFor("分镜")
        awaitIdle()
        compose.onNodeWithText("检查与交付").performClick()
        waitFor(issue.message)
        compose.onNodeWithText(issue.message).assertIsDisplayed()
        compose.onNodeWithText("去修正").assertIsEnabled().performClick()

        val targetHint = "接入步骤 ${copyOrdinal.toString().padStart(2, '0')} · ${copy.title}"
        waitFor(targetHint)
        // No test scroll here: the real correction route must reveal the exact copied row.
        compose.onNodeWithText(targetHint).assertIsDisplayed()
        compose.onNodeWithText("步骤").assertIsSelected()
        compose.onNodeWithText("名称与讲解").assertDoesNotExist()
        assertEquals(ProjectRoute.STEPS, projects.state.value.route)
        assertNull(projects.state.value.selectedStepId)
        assertEquals(before, store.readProject(projectId))

        compose.onNodeWithText("选择接入步骤").assertIsDisplayed().performClick()
        waitFor("从哪一步接入")
        val inSheet = hasAnyAncestor(isDialog())
        before.steps.filter { it.id != copyId }.forEach { step ->
            val ordinal = (before.steps.indexOf(step) + 1).toString().padStart(2, '0')
            compose.onNode(hasText("$ordinal ${step.title}") and inSheet)
                .performScrollTo().assertIsDisplayed()
        }
        compose.onNode(hasText("${copyOrdinal.toString().padStart(2, '0')} ${copy.title}") and inSheet)
            .assertDoesNotExist()
        assertEquals(before, store.readProject(projectId))
        compose.onNode(hasText("${sourceOrdinal.toString().padStart(2, '0')} ${source.title}") and inSheet)
            .performScrollTo().performClick()
        waitFor("名称与讲解")
        awaitIdle()
        assertEquals(ProjectRoute.EDIT, projects.state.value.route)
        assertEquals(source.id, projects.state.value.selectedStepId)
        assertEquals(before, store.readProject(projectId))
        assertTrue(projects.state.value.dirtyStepIds.isEmpty())
        compose.onNodeWithText("从哪一步接入").assertDoesNotExist()

        compose.onNodeWithText("热点").performClick()
        waitForEnabled("按比例添加（无需拖动）")
        compose.onNodeWithText("按比例添加（无需拖动）").performScrollTo().performClick()
        waitFor("动作名称")
        compose.onNodeWithText("保存本步").assertIsNotEnabled()
        val label = "打开复制终点"
        compose.onNodeWithText("动作名称").performTextReplacement(label)
        compose.onNodeWithText("$copyOrdinal  ${copy.title}").performScrollTo().performClick()
        waitForEnabled("保存本步")
        assertEquals(copyId, projects.state.value.stepDraft?.pendingForm?.targetStepId)
        // Selecting a source, filling a panel and choosing its target cannot mutate the saved graph.
        assertEquals(before, store.readProject(projectId))
        compose.onNodeWithText("保存本步").performClick()
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && projects.state.value.stepDraft?.pendingForm == null &&
                projects.state.value.dirtyStepIds.isEmpty()
        }
        val saved = checkNotNull(store.readProject(projectId))
        val savedSource = saved.steps.single { it.id == source.id }
        val added = savedSource.hotspots.single { it.label == label }
        assertEquals(before.project.revision + 1, saved.project.revision)
        assertEquals(before.project.startStepId, saved.project.startStepId)
        assertEquals(copyId, added.targetStepId)
        assertNull(added.endLabel)
        assertEquals(source.hotspots, savedSource.hotspots.filter { it.id != added.id })
        assertEquals(source.nextAction, savedSource.nextAction)
        assertEquals(source, savedSource.copy(hotspots = source.hotspots))
        assertEquals(before.steps.filter { it.id != source.id }, saved.steps.filter { it.id != source.id })
        assertTrue(ProjectWorkspace.graphIssues(saved).isEmpty())

        // First return leaves Hotspots mode; the second leaves the saved source editor.
        compose.onNodeWithText("返回").performClick()
        waitFor("名称与讲解")
        compose.onNodeWithText("返回").performClick()
        waitFor(targetHint)
        compose.onNodeWithText(targetHint).assertIsDisplayed()
        compose.onNodeWithText("此步已接入当前起点路径。").assertIsDisplayed()
        compose.onNodeWithText("选择接入步骤").assertDoesNotExist()
        compose.onNodeWithText("返回交付检查").assertIsDisplayed().performClick()
        waitFor("当前本机结构检查未返回问题。")
        compose.onNodeWithText("检查与交付").assertIsSelected()
        compose.onNodeWithText(issue.message).assertDoesNotExist()
        compose.onNodeWithText("去修正").assertDoesNotExist()
        assertTrue(projects.state.value.issues.isEmpty())
        assertEquals(saved, store.readProject(projectId))
        // Stop at graph repair. Imported animation-plan revalidation and candidate creation
        // have separate gates and are intentionally not claimed as part of this UI check.
        println("HOST_DELIVERY_ISSUE exact-copy/source-selection/no-auto-write/hotspot-save/old-exits/return-checks PASS; device NOT_RUN")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun waitForEnabled(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.waitForIdle()
    }

    private fun resetApplicationViewModelFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
