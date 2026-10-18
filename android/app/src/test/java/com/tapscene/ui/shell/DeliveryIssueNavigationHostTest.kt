package com.tapscene.ui.shell

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.AiDraftImportFixtures
import com.tapscene.data.AiDraftImportStore
import com.tapscene.data.DraftAiConfigCodec
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectNextAction
import com.tapscene.data.ProjectStore
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectIssueCorrection
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
        // Editor drafts canonicalize hotspot order by stable ID; every old hotspot field must survive.
        assertEquals(source.hotspots.associateBy { it.id }, savedSource.hotspots.filter { it.id != added.id }.associateBy { it.id })
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

    @Test fun reachableClosedLoopRequiresExplicitEndingAndRepairsExactSameTitleStep() {
        waitFor("悬浮编排")
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        val store = ProjectStore(app)
        fun awaitIdle() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !releases.state.value.busy && !media.state.value.busy
        }
        fun endingIssue(title: String) = "从“$title”无法到达结束，请连接终点或添加结束热点"
        awaitIdle()
        val before = runBlocking(Dispatchers.IO) {
            val fixture = AiDraftImportFixtures.complete(app)
            val imports = AiDraftImportStore(app)
            val input = fixture.zip.inputStream().use { imports.prepare(it) }
            val projectId = checkNotNull(imports.commit(input.sessionId, input.previewDigest).projectId)
            AiDraftImportFixtures.reviewDraftRegions(store, projectId)
            val imported = checkNotNull(store.readProject(projectId))
            val start = imported.steps.single { it.id == imported.project.startStepId }
            val branch = imported.steps.single { it.id == fixture.branchId }
            // Pure graph counterexamples use the existing API, so the regression also compiles
            // against the old implementation. A cycle with an available terminal remains valid.
            assertTrue(ProjectWorkspace.graphIssues(imported).isEmpty())
            val selfLoop = imported.copy(steps = listOf(start.copy(
                hotspots = start.hotspots.filter { it.targetStepId == start.id }, nextAction = null)))
            assertEquals(listOf(endingIssue(start.title)), ProjectWorkspace.graphIssues(selfLoop).map { it.message })
            val trappedBranch = branch.copy(isTerminal = false, nextAction = ProjectNextAction(
                UUID.randomUUID().toString(), "留在分支", branch.id))
            val branchIssues = ProjectWorkspace.graphIssues(imported.copy(steps = imported.steps.map {
                if (it.id == branch.id) trappedBranch else it
            }))
            assertEquals(listOf(branch.id), branchIssues.map { it.stepId })
            assertEquals(listOf(endingIssue(branch.title)), branchIssues.map { it.message })
            val noAction = selfLoop.copy(steps = listOf(start.copy(hotspots = emptyList(), nextAction = null)))
            assertEquals(1, ProjectWorkspace.graphIssues(noAction).size)
            assertTrue(ProjectWorkspace.graphIssues(noAction).single().message.contains("尚无动作"))
            val missingTarget = selfLoop.copy(steps = listOf(selfLoop.steps.single().copy(
                nextAction = checkNotNull(start.nextAction).copy(targetStepId = null))))
            assertEquals(1, ProjectWorkspace.graphIssues(missingTarget).size)
            assertTrue(ProjectWorkspace.graphIssues(missingTarget).single().message.contains("缺少目标"))

            // Real store writes establish a reachable, closed graph: the imported self-loop,
            // next action and branch remain, and both former terminals return to the start.
            // Giving the second step the first one's title exercises ID-based correction.
            var snapshot = imported
            imported.steps.filter { it.isTerminal }.forEach { step ->
                snapshot = store.saveStepDraft(projectId, step.id,
                    title = if (step.id == fixture.endId) start.title else step.title,
                    description = step.description, isTerminal = false, hotspots = step.hotspots,
                    expectedRevision = snapshot.project.revision,
                    nextAction = ProjectNextAction(UUID.randomUUID().toString(), "返回起点", start.id))
            }
            assertEquals(start, snapshot.steps.single { it.id == start.id })
            snapshot
        }
        val projectId = before.project.id
        val source = before.steps.single { it.id == before.project.startStepId }
        val target = before.steps.single { it.id != source.id && it.title == source.title }
        val issues = ProjectWorkspace.graphIssues(before)
        assertEquals(before.steps.map { it.id }, issues.map { it.stepId })
        assertTrue(issues.all { it.correction == ProjectIssueCorrection.STEP_EDITOR })
        assertEquals(before.steps.map { endingIssue(it.title) }, issues.map { it.message })
        assertEquals(2, before.steps.count { it.title == target.title })
        assertFalse(before.steps.any { it.isTerminal || it.hotspots.any { spot -> spot.endLabel != null } })
        val planBefore = checkNotNull(store.readDraftAiConfig(projectId))

        compose.runOnIdle { projects.reload() }
        awaitIdle()
        compose.onNodeWithText(before.project.title).performClick()
        waitFor("分镜")
        awaitIdle()
        compose.onNodeWithText("检查与交付").performClick()
        waitFor(endingIssue(target.title))
        compose.onAllNodesWithText(endingIssue(target.title)).assertCountEquals(2)
        compose.onNodeWithText("去修正").assertIsEnabled()
        compose.onNodeWithText("生成待复核成品").performScrollTo().assertIsNotEnabled()
        // Choose the second same-title issue, not the first matching title or the start step.
        compose.onAllNodesWithText(endingIssue(target.title))[1].performScrollTo().assertIsDisplayed().performClick()
        waitFor("名称与讲解")
        awaitIdle()
        assertEquals(ProjectRoute.EDIT, projects.state.value.route)
        assertEquals(target.id, projects.state.value.selectedStepId)
        assertEquals(before, store.readProject(projectId))
        assertTrue(projects.state.value.dirtyStepIds.isEmpty())

        compose.onNodeWithText("热点").performClick()
        waitForEnabled("按比例添加（无需拖动）")
        compose.onNodeWithText("按比例添加（无需拖动）").performScrollTo().performClick()
        waitFor("动作名称")
        compose.onNodeWithText("保存本步").assertIsNotEnabled()
        val label = "结束本轮演示"
        val endLabel = "已完成循环演示"
        compose.onNodeWithText("动作名称").performTextReplacement(label)
        compose.onNodeWithText("结束演示").performScrollTo().performClick()
        waitFor("结束说明")
        compose.onNodeWithText("结束说明").performScrollTo().performTextReplacement(endLabel)
        waitForEnabled("保存本步")
        assertTrue(checkNotNull(projects.state.value.stepDraft?.pendingForm).endsDemo)
        // Merely opening correction and choosing an ending cannot alter the persisted graph.
        assertEquals(before, store.readProject(projectId))
        assertEquals(issues, ProjectWorkspace.graphIssues(checkNotNull(store.readProject(projectId))))
        compose.onNodeWithText("保存本步").performClick()
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && projects.state.value.stepDraft?.pendingForm == null &&
                projects.state.value.dirtyStepIds.isEmpty()
        }
        val saved = checkNotNull(store.readProject(projectId))
        val savedTarget = saved.steps.single { it.id == target.id }
        val added = savedTarget.hotspots.single { it.label == label }
        assertEquals(before.project.revision + 1, saved.project.revision)
        assertEquals(before.project.startStepId, saved.project.startStepId)
        assertNull(added.targetStepId)
        assertEquals(endLabel, added.endLabel)
        assertFalse(savedTarget.isTerminal)
        assertEquals(target.hotspots.associateBy { it.id }, savedTarget.hotspots.filter { it.id != added.id }.associateBy { it.id })
        assertEquals(target.nextAction, savedTarget.nextAction)
        assertEquals(target, savedTarget.copy(hotspots = target.hotspots))
        assertEquals(before.steps.filter { it.id != target.id }, saved.steps.filter { it.id != target.id })
        assertTrue(ProjectWorkspace.graphIssues(saved).isEmpty())

        // The editor returns to the original checks tab after leaving Hotspots mode.
        compose.onNodeWithText("返回").performClick()
        waitFor("名称与讲解")
        compose.onNodeWithText("返回").performClick()
        waitFor("当前本机结构检查未返回问题。")
        compose.onNodeWithText("检查与交付").assertIsSelected()
        compose.onNodeWithText(endingIssue(target.title)).assertDoesNotExist()
        compose.onNodeWithText("去修正").assertDoesNotExist()
        assertTrue(projects.state.value.issues.isEmpty())
        assertEquals(saved, store.readProject(projectId))
        val planAfter = checkNotNull(store.readDraftAiConfig(projectId))
        // Java Visit/Effect values use identity equality; compare every persisted plan field.
        assertEquals(planBefore.boundRevision, planAfter.boundRevision)
        assertEquals(planBefore.needsRepair, planAfter.needsRepair)
        assertEquals(DraftAiConfigCodec.encode(planBefore), DraftAiConfigCodec.encode(planAfter))
        assertTrue(planAfter.needsRepair)
        assertTrue(store.draftAiIssues(projectId, planAfter).isNotEmpty())
        // The imported plan still stops at a former terminal. Repairing this graph does not
        // silently rewrite that finite plan or establish candidate-generation readiness.
        println("HOST_DELIVERY_ENDING closed-loop/same-title-ID/explicit-end-save/old-actions/return-checks PASS; imported-plan repair required; candidate NOT_RUN; device NOT_RUN")
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
