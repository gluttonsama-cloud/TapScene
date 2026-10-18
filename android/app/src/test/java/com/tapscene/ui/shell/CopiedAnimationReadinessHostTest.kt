package com.tapscene.ui.shell

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.AiDraftImportFixtures
import com.tapscene.data.AiDraftImportStore
import com.tapscene.data.DraftAiConfig
import com.tapscene.data.DraftAiConfigCodec
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReleaseStore
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.ui.AiDraftWorkspace
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

/** Real MainActivity, SQLite and synthetic PNGs; no device or human-review claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CopiedAnimationReadinessHostTest {
    private var previousRequestedSize: String? = null

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            val root = RuntimeEnvironment.getApplication().noBackupFilesDir.canonicalFile
            val sandbox = RuntimeEnvironment.getTempDirectory().basePath.toRealPath()
            // Never touch data outside this Robolectric test's private synthetic sandbox.
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

    @Test fun copiedPlanBlocksDeliveryUntilExplicitSaveOnExactOwnerAndPreservesOriginalCandidate() {
        waitFor("悬浮编排")
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val drafts = ViewModelProvider(compose.activity)[AiDraftWorkspace::class.java]
        val store = ProjectStore(app)
        val releaseStore = ReleaseStore(app)
        fun awaitIdle() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !media.state.value.busy && !releases.state.value.busy &&
                !drafts.planState.value.busy && !drafts.planState.value.checking
        }
        awaitIdle()

        suspend fun readyProject(title: String, goal: String): ProjectSnapshot {
            val fixture = AiDraftImportFixtures.complete(app)
            val imports = AiDraftImportStore(app)
            val input = fixture.zip.inputStream().use { imports.prepare(it) }
            val projectId = checkNotNull(imports.commit(input.sessionId, input.previewDigest).projectId)
            var snapshot = checkNotNull(store.readProject(projectId))
            // Fixture preparation removes the sole region and its focus effect. Thus copying
            // cannot hide the animation gate behind an unrelated unreviewed-region issue.
            val region = snapshot.steps.flatMap { it.regions }.single()
            snapshot = store.deleteRegion(projectId, region.id, snapshot.project.revision)
            snapshot = store.renameProject(projectId, title, goal, snapshot.project.revision)
            val imported = checkNotNull(store.readDraftAiConfig(projectId))
            assertEquals(1, imported.effects.count { it.value.regionId == region.id })
            val saved = store.saveDraftAiConfig(projectId, snapshot.project.revision,
                imported.copy(effects = imported.effects.filterNot { it.value.regionId == region.id }))
            assertFalse(saved.needsRepair)
            assertTrue(store.draftAiIssues(projectId, saved).isEmpty())
            return checkNotNull(store.readProject(projectId)).also {
                assertTrue(it.steps.all { step -> step.regions.isEmpty() })
                assertTrue(ProjectWorkspace.graphIssues(it).isEmpty())
            }
        }
        val sourceTitle = "动画交付源"
        val sourceGoal = "明确复制这一项目的已保存内容"
        val (original, other) = runBlocking(Dispatchers.IO) {
            readyProject(sourceTitle, sourceGoal) to
                readyProject("$sourceTitle 副本", "同名项目，动画计划应保持原样")
        }
        val originalId = original.project.id
        val otherId = other.project.id
        val originalPlan = runBlocking(Dispatchers.IO) { checkNotNull(store.readDraftAiConfig(originalId)) }
        val otherPlan = runBlocking(Dispatchers.IO) { checkNotNull(store.readDraftAiConfig(otherId)) }
        // The checklist mirrors the final persisted-plan gate, including independent stale
        // revision and bad-reference cases whose needsRepair flag alone is false.
        val animationWarning = "动画计划待核对，请检查路径与效果并保存。"
        assertTrue(ProjectWorkspace.deliveryIssues(original, null).isEmpty())
        assertTrue(ProjectWorkspace.deliveryIssues(original, originalPlan).isEmpty())
        fun assertBlocked(config: DraftAiConfig) = assertEquals(listOf(animationWarning),
            ProjectWorkspace.deliveryIssues(original, config).map { it.message })
        assertBlocked(originalPlan.copy(needsRepair = true))
        assertBlocked(originalPlan.copy(boundRevision = original.project.revision + 1))
        val first = originalPlan.visits.first()
        assertBlocked(originalPlan.copy(visits = listOf(RenderPlan.Visit(first.visitId,
            "00000000-0000-4000-8000-000000000000", first.selectedEdgeId, first.holdFrames)) + originalPlan.visits.drop(1)))
        val originalCandidate = runBlocking(Dispatchers.IO) {
            releaseStore.createCandidate(originalId, original.project.revision)
        }
        val candidateRoot = File(app.noBackupFilesDir, "release-store/candidates/${originalCandidate.id}")
        val candidateFiles = filesUnder(candidateRoot)
        val originalFiles = filesUnder(File(app.noBackupFilesDir, "project-assets/$originalId"))
        val otherFiles = filesUnder(File(app.noBackupFilesDir, "project-assets/$otherId"))
        assertTrue(candidateFiles.keys.contains("draft-plan.json"))
        assertTrue(originalFiles.isNotEmpty() && otherFiles.isNotEmpty())

        // Prime the real plan workspace with a different owner's saved plan. Its title will
        // equal the new copy's title, so correction must resolve by project ID, not text.
        compose.runOnIdle { projects.reload(); drafts.openPlan(otherId) }
        awaitIdle()
        assertEquals(otherId, drafts.planState.value.project?.project?.id)
        compose.onNodeWithText(sourceGoal).performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !media.state.value.busy &&
                projects.state.value.project?.project?.id == originalId && media.projectId == originalId
        }
        assertTrue(projects.state.value.issues.isEmpty())
        compose.onNodeWithText("更多").performClick()
        waitForEnabled("复制项目")
        compose.onNodeWithText("复制项目").performClick()
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            val id = projects.state.value.project?.project?.id
            !projects.state.value.busy && !media.state.value.busy &&
                id != null && id !in setOf(originalId, otherId) && media.projectId == id
        }
        awaitIdle()
        assertFalse(projects.state.value.loadFailed)
        val copied = checkNotNull(projects.state.value.project)
        val copiedId = copied.project.id
        val copiedPlan = runBlocking(Dispatchers.IO) { checkNotNull(store.readDraftAiConfig(copiedId)) }
        assertEquals(other.project.title, copied.project.title)
        assertEquals(2, store.listProjects().count { it.title == copied.project.title })
        assertNotEquals(otherId, copiedId)
        assertTrue(copied.steps.all { it.regions.isEmpty() })
        assertTrue(ProjectWorkspace.graphIssues(copied).isEmpty())
        assertTrue(copiedPlan.needsRepair)
        assertEquals(copied.project.revision, copiedPlan.boundRevision)
        assertTrue(store.draftAiIssues(copiedId, copiedPlan).isEmpty())
        assertEquals(originalPlan.visits.size, copiedPlan.visits.size)
        assertEquals(originalPlan.effects.size, copiedPlan.effects.size)
        assertTrue(copiedPlan.effects.isNotEmpty())
        assertEquals(copied.project.startStepId, copiedPlan.visits.first().stateId)
        assertTrue(copiedPlan.visits.all { visit -> copied.steps.any { it.id == visit.stateId } })
        assertTrue(copiedPlan.visits.none { visit -> original.steps.any { it.id == visit.stateId } })

        val issue = "动画计划待核对，请检查路径与效果并保存。"
        compose.onNodeWithText("检查与交付").performClick()
        // Old production reaches this point with a complete graph and an incorrectly empty
        // checklist. The regression must fail on the missing real UI warning, not a new API.
        waitFor(issue)
        compose.onNodeWithText(issue).performScrollTo().assertIsDisplayed()
        assertEquals(listOf(issue), projects.state.value.issues.map { it.message })
        compose.onNodeWithText("当前检查未返回待处理问题。").assertDoesNotExist()
        compose.onNodeWithText("生成待复核成品").performScrollTo().assertIsNotEnabled()
        assertNull(runBlocking(Dispatchers.IO) { releaseStore.readCandidate(copiedId) })
        compose.onNodeWithText("去修正").performScrollTo().assertIsEnabled().performClick()
        waitForEnabled("检查并保存计划")
        awaitIdle()
        assertEquals(copiedId, drafts.planState.value.project?.project?.id)
        assertFalse(drafts.planState.value.dirty)
        assertTrue(drafts.planState.value.issues.isEmpty())
        assertPlanUnchanged(copiedPlan, checkNotNull(drafts.planState.value.config))
        compose.onNodeWithText("访问路径").assertIsDisplayed()
        val firstVisit = copiedPlan.visits.first()
        val firstTitle = copied.steps.single { it.id == firstVisit.stateId }.title
        compose.onNodeWithText("访问 1：$firstTitle").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("全部效果").performClick()
        compose.onNodeWithText("全部 ${copiedPlan.effects.size} 项效果").performScrollTo().assertIsDisplayed()
        assertEquals(copied, store.readProject(copiedId))
        assertPlanUnchanged(copiedPlan, checkNotNull(store.readDraftAiConfig(copiedId)))

        // Merely opening, inspecting and returning must not clear the copied-plan gate.
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor(issue)
        awaitIdle()
        compose.onNodeWithText("检查与交付").assertIsSelected()
        compose.onNodeWithText("生成待复核成品").performScrollTo().assertIsNotEnabled()
        assertPlanUnchanged(copiedPlan, checkNotNull(store.readDraftAiConfig(copiedId)))
        compose.onNodeWithText(issue).performScrollTo().performClick()
        waitForEnabled("检查并保存计划")
        assertEquals(copiedId, drafts.planState.value.project?.project?.id)
        compose.onNodeWithText("检查并保存计划").assertIsEnabled().performClick()
        waitFor("动画计划已保存；交付仍需固定版本并完成复核。")
        awaitIdle()
        val saved = checkNotNull(store.readProject(copiedId))
        val savedPlan = checkNotNull(store.readDraftAiConfig(copiedId))
        assertEquals(copied.project.revision + 1, saved.project.revision)
        assertEquals(copied.steps, saved.steps)
        assertEquals(copied.project.startStepId, saved.project.startStepId)
        assertFalse(savedPlan.needsRepair)
        assertEquals(saved.project.revision, savedPlan.boundRevision)
        assertEquals(DraftAiConfigCodec.encode(copiedPlan), DraftAiConfigCodec.encode(savedPlan))
        assertFalse(drafts.planState.value.dirty)
        assertFalse(drafts.planState.value.saveOutcomeUnknown)
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("当前检查未返回待处理问题。")
        waitForEnabled("生成待复核成品")
        compose.onNodeWithText("检查与交付").assertIsSelected()
        compose.onNodeWithText(issue).assertDoesNotExist()
        compose.onNodeWithText("去修正").assertDoesNotExist()
        compose.onNodeWithText("生成待复核成品").performScrollTo().assertIsEnabled()
        assertEquals(ProjectRoute.STEPS, projects.state.value.route)
        assertEquals(copiedId, projects.state.value.project?.project?.id)
        assertTrue(projects.state.value.issues.isEmpty())

        assertEquals(original, store.readProject(originalId))
        assertEquals(other, store.readProject(otherId))
        assertPlanUnchanged(originalPlan, checkNotNull(store.readDraftAiConfig(originalId)))
        assertPlanUnchanged(otherPlan, checkNotNull(store.readDraftAiConfig(otherId)))
        assertEquals(originalFiles, filesUnder(File(app.noBackupFilesDir, "project-assets/$originalId")))
        assertEquals(otherFiles, filesUnder(File(app.noBackupFilesDir, "project-assets/$otherId")))
        assertEquals(candidateFiles, filesUnder(candidateRoot))
        val candidates = runBlocking(Dispatchers.IO) { releaseStore.listCandidates() }
        assertEquals(listOf(originalCandidate.id), candidates.map { it.id })
        assertEquals(originalId, candidates.single().projectId)
        assertEquals(originalCandidate.projectRevision, candidates.single().projectRevision)
        assertEquals(originalCandidate.contentDigest, candidates.single().contentDigest)
        assertNull(releases.state.value.candidate)
        // Stop at restored eligibility. No copied candidate, seal or human review is implied.
        println("HOST_COPIED_ANIMATION copy/check-gate/same-title-owner/no-auto-save/explicit-save/return-refresh/original-plan-and-candidate-unchanged PASS; device NOT_RUN")
    }

    private fun assertPlanUnchanged(expected: DraftAiConfig, actual: DraftAiConfig) {
        assertEquals(expected.boundRevision, actual.boundRevision)
        assertEquals(expected.needsRepair, actual.needsRepair)
        assertEquals(DraftAiConfigCodec.encode(expected), DraftAiConfigCodec.encode(actual))
    }

    private fun filesUnder(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to ViewerPackageCodec.sha256(it) }

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
