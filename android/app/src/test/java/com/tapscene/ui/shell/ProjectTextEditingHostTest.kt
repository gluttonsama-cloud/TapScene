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
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReleaseCandidate
import com.tapscene.data.ReleaseStore
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.ui.DeliverySelection
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.ReleaseWorkspace
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/** Real MainActivity, Compose, imported PNGs and SQLite. No device or process-death claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectTextEditingHostTest {
    private var previousRequestedSize: String? = null

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            val app = RuntimeEnvironment.getApplication()
            // Only this test's synthetic files inside Robolectric's own private sandbox.
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

    @Test fun fixedReviewEditsCurrentSourceTextByIdAndRebuildsOnlyAfterExplicitConfirmation() {
        waitFor("悬浮编排")
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        assertSame(app, projects.getApplication<android.app.Application>())
        val store = ProjectStore(app)
        val candidates = ReleaseStore(app)
        fun awaitIdle() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !releases.state.value.busy && !media.state.value.busy
        }
        awaitIdle()
        val (fixed, otherId) = runBlocking(Dispatchers.IO) {
            val imports = AiDraftImportStore(app)
            suspend fun importFixture(): String {
                val fixture = AiDraftImportFixtures.complete(app)
                val input = fixture.zip.inputStream().use { imports.prepare(it) }
                return checkNotNull(imports.commit(input.sessionId, input.previewDigest).projectId)
            }
            val sourceId = importFixture()
            val otherId = importFixture()
            AiDraftImportFixtures.reviewDraftRegions(store, sourceId)
            var candidate = candidates.createCandidate(sourceId, checkNotNull(store.readProject(sourceId)).project.revision)
            // Synthetic review/traversal preparation makes preservation and reset observable.
            // It does not stand in for human privacy review or device playback.
            candidate = candidates.reviewState(candidate.id, candidate.contentDigest, candidate.scene.states.first().id)
            candidate = candidates.reviewRegion(candidate.id, candidate.contentDigest, candidate.scene.regions.single().id)
            candidate = candidates.reviewSummary(candidate.id, candidate.contentDigest)
            candidate = candidates.reviewFileList(candidate.id, candidate.contentDigest)
            candidate = candidates.recordTraversal(candidate.id, candidate.contentDigest, null, false)
            candidate = candidates.recordTraversal(candidate.id, candidate.contentDigest,
                candidate.scene.edges.single { it.trigger == "continue" }.id, false)
            candidate to otherId
        }
        val otherBefore = checkNotNull(store.readProject(otherId))
        val otherPlanBefore = checkNotNull(store.readDraftAiConfig(otherId))
        assertNotEquals(fixed.projectId, otherId)
        assertEquals(fixed.scene.title, otherBefore.project.title)
        assertTrue(fixed.summaryReviewed && fixed.fileListReviewed && fixed.completedPath)
        assertTrue(fixed.reviewedStateIds.isNotEmpty() && fixed.reviewedRegionIds.isNotEmpty())
        assertTrue(fixed.visitedEdgeIds.isNotEmpty())

        // The source has newer text than its immutable candidate before opening the editor.
        val currentGoal = "来源 A 当前草稿的开场说明，候选里还没有这段文字。"
        val sourceBefore = store.renameProject(fixed.projectId, fixed.scene.title, currentGoal)
        compose.runOnIdle { projects.reload(); releases.reloadLibrary() }
        awaitIdle()
        compose.onNodeWithText("演示库").performClick()
        waitForEnabled("继续复核")
        compose.onNodeWithText("继续复核").assertIsEnabled().performClick()
        waitFor("复核修订 ${fixed.projectRevision}")
        awaitIdle()

        // Deliberately load same-title B while A's fixed review route remains open.
        compose.runOnIdle { projects.openProject(otherId) }
        awaitIdle()
        assertEquals(otherId, projects.state.value.project?.project?.id)
        assertEquals(fixed.id, releases.state.value.candidate?.id)
        // First lazy list is the category row; keep its selector stable as items scroll out.
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(hasText("✓ 项目文字"))
        compose.onNodeWithText("✓ 项目文字").assertIsDisplayed().performClick()
        waitForEnabled("修改来源项目文字")
        compose.onNodeWithText(fixed.scene.goal).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("修改来源项目文字").performScrollTo().assertIsEnabled().performClick()
        waitFor("开场说明（选填）")
        compose.onNodeWithText("项目名称").assertTextContains(fixed.scene.title)
        compose.onNodeWithText("开场说明（选填）").assertTextContains(currentGoal)
        compose.onNodeWithText("项目名称").performTextReplacement("取消的名称")
        compose.onNodeWithText("开场说明（选填）").performTextReplacement("取消的说明")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("开场说明（选填）").assertDoesNotExist()
        assertEquals(sourceBefore, store.readProject(fixed.projectId))
        assertEquals(otherBefore, store.readProject(otherId))
        assertFixedCandidate(fixed, runBlocking(Dispatchers.IO) { candidates.readCandidateById(fixed.id) })

        waitForEnabled("修改来源项目文字")
        compose.onNodeWithText("修改来源项目文字").performScrollTo().assertIsEnabled().performClick()
        waitFor("开场说明（选填）")
        compose.onNodeWithText("开场说明（选填）").assertTextContains(currentGoal)
        val longName = "名".repeat(121)
        val longGoal = "说明".repeat(500) + "尾"
        compose.onNodeWithText("项目名称").performTextReplacement(longName)
        compose.onNodeWithText("项目名称").assertTextContains(longName)
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("项目名称").performTextReplacement(fixed.scene.title)
        compose.onNodeWithText("开场说明（选填）").performTextReplacement(longGoal)
        compose.onNodeWithText("开场说明（选填）").assertTextContains(longGoal)
        compose.onNodeWithText("保存").assertIsNotEnabled()
        assertEquals(sourceBefore, store.readProject(fixed.projectId))

        // A concurrent source revision must fail visibly without closing or overwriting input.
        val attemptedTitle = "保留在输入框中的名称"
        val attemptedGoal = "这段未提交说明必须保留。"
        compose.onNodeWithText("项目名称").performTextReplacement(attemptedTitle)
        compose.onNodeWithText("开场说明（选填）").performTextReplacement(attemptedGoal)
        val concurrent = store.renameProject(fixed.projectId, fixed.scene.title, "另一次保存后的最新说明")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        awaitIdle()
        compose.onNode(hasText("项目已更新；当前输入仍保留，请取消后重新打开项目文字并核对。") and
            hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("项目名称").assertTextContains(attemptedTitle)
        compose.onNodeWithText("开场说明（选填）").assertTextContains(attemptedGoal)
        assertEquals(concurrent, store.readProject(fixed.projectId))
        assertEquals(otherBefore, store.readProject(otherId))
        assertFixedCandidate(fixed, runBlocking(Dispatchers.IO) { candidates.readCandidateById(fixed.id) })
        compose.onNodeWithText("取消").performClick()

        waitForEnabled("修改来源项目文字")
        compose.onNodeWithText("修改来源项目文字").performScrollTo().assertIsEnabled().performClick()
        waitFor("开场说明（选填）")
        compose.onNodeWithText("开场说明（选填）").assertTextContains(concurrent.project.goal)
        val savedTitle = "A 的新项目名称"
        compose.onNodeWithText("项目名称").performTextReplacement(savedTitle)
        compose.onNodeWithText("开场说明（选填）").performTextReplacement("")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        awaitIdle()
        compose.onNodeWithText("开场说明（选填）").assertDoesNotExist()
        val saved = checkNotNull(store.readProject(fixed.projectId))
        assertEquals(concurrent.project.revision + 1, saved.project.revision)
        assertEquals(savedTitle, saved.project.title)
        assertEquals("", saved.project.goal)
        assertEquals(concurrent.steps, saved.steps)
        assertEquals(otherId, projects.state.value.project?.project?.id)
        assertEquals(otherBefore, store.readProject(otherId))
        assertFixedPlan(otherPlanBefore, checkNotNull(store.readDraftAiConfig(otherId)))
        assertFixedCandidate(fixed, checkNotNull(releases.state.value.candidate))
        assertFixedCandidate(fixed, runBlocking(Dispatchers.IO) { candidates.readCandidateById(fixed.id) })
        compose.onNodeWithText(fixed.scene.goal).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("草稿已有修改；当前显示的仍是旧候选文字，重新生成并复核后才会进入新成品。")
            .performScrollTo().assertIsDisplayed()

        // Reconfirm this synthetic fixture's unchanged animation plan through its separate gate.
        // This preparation is not an end-to-end animation-plan UI assertion.
        runBlocking(Dispatchers.IO) {
            val plan = checkNotNull(store.readDraftAiConfig(fixed.projectId))
            check(store.draftAiIssues(fixed.projectId, plan).isEmpty())
            store.saveDraftAiConfig(fixed.projectId, saved.project.revision, plan)
        }
        compose.runOnIdle { projects.reload() }
        awaitIdle()
        compose.onNodeWithText("从当前草稿重新生成").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("保留现有候选").performClick()
        assertFixedCandidate(fixed, runBlocking(Dispatchers.IO) { candidates.readCandidateById(fixed.id) })
        compose.onNodeWithText("从当前草稿重新生成").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("重新生成") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("重新生成").performClick()
        awaitIdle()
        val rebuilt = checkNotNull(releases.state.value.candidate)
        assertNotEquals("Rebuild failed: ${releases.state.value.message}", fixed.id, rebuilt.id)
        assertEquals(fixed.projectId, rebuilt.projectId)
        assertEquals(store.readProject(fixed.projectId)!!.project.revision, rebuilt.projectRevision)
        assertNotEquals(fixed.contentDigest, rebuilt.contentDigest)
        assertEquals(savedTitle, rebuilt.scene.title)
        assertEquals("", rebuilt.scene.goal)
        assertTrue(rebuilt.reviewedStateIds.isEmpty() && rebuilt.reviewedRegionIds.isEmpty())
        assertTrue(rebuilt.reviewedTransitionAssetIds.isEmpty() && rebuilt.visitedEdgeIds.isEmpty())
        assertFalse(rebuilt.summaryReviewed || rebuilt.fileListReviewed || rebuilt.completedPath)
        assertFalse(rebuilt.traversalStarted || rebuilt.traversalEnded)
        assertTrue(rebuilt.traversalHistory.isEmpty())
        assertNull(rebuilt.traversalStateId)
        assertNull(rebuilt.traversalEndEdgeId)
        assertEquals(otherBefore, store.readProject(otherId))
        assertFixedPlan(otherPlanBefore, checkNotNull(store.readDraftAiConfig(otherId)))
        assertFixedCandidate(rebuilt, runBlocking(Dispatchers.IO) { candidates.readCandidateById(rebuilt.id) })
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(hasText("项目文字"))
        compose.onNodeWithText("项目文字").assertIsDisplayed().performClick()
        compose.onNodeWithText(savedTitle).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("未填写开场说明。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("确认项目文字").performScrollTo().assertIsEnabled()
        println("HOST_PROJECT_TEXT exact-source/current-prefill/cancel/limits/stale-save/clear/rebuild PASS; device/process-death NOT_RUN")
    }

    @Test fun selectedSealedVersionSurvivesProjectTextSaveAndComparesOnlyItsLoadedSource() {
        waitFor("悬浮编排")
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        val store = ProjectStore(app)
        val releaseStore = ReleaseStore(app)
        fun awaitIdle() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !releases.state.value.busy && !media.state.value.busy
        }
        awaitIdle()
        val sealed = runBlocking(Dispatchers.IO) {
            val fixture = AiDraftImportFixtures.complete(app)
            val imports = AiDraftImportStore(app)
            val input = fixture.zip.inputStream().use { imports.prepare(it) }
            val projectId = checkNotNull(imports.commit(input.sessionId, input.previewDigest).projectId)
            // Fixture preparation performs synthetic review, not human privacy review or UI sealing.
            AiDraftImportFixtures.reviewDraftRegions(store, projectId)
            val summary = AiDraftImportFixtures.sealFixture(app, projectId, fixture)
            releaseStore.readHostedRelease(summary.id, summary.contentDigest)
        }
        val fixed = DeliverySelection(sealed.summary.id, sealed.summary.contentDigest, sealed.projectId, sealed.projectRevision)
        val sealedRoot = File(app.noBackupFilesDir, "release-store/releases/${sealed.summary.id}")
        fun sealedFiles() = sealedRoot.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(sealedRoot).path to ViewerPackageCodec.sha256(it) }
        val sealedBytes = sealedFiles()
        assertTrue(sealedBytes.isNotEmpty())
        fun draftWarning(revision: Long) = "草稿已更新至修订 $revision，可能包含此版本未收录的后续修改。"
        fun assertDeliveryAvailable() {
            assertEquals(fixed, releases.state.value.deliverySelection)
            assertEquals(sealed.summary, releases.state.value.deliveryRelease)
            compose.onNodeWithText("版本详情").performScrollTo().performClick()
            compose.onNodeWithText("封存修订 ${sealed.projectRevision}").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("版本详情").performScrollTo().performClick()
            compose.onNodeWithText("保存到文件").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("系统分享").performScrollTo().assertIsEnabled()
        }

        compose.runOnIdle { projects.reload(); releases.reloadLibrary() }
        awaitIdle()
        compose.onNodeWithText("演示库").performClick()
        waitForEnabled("交付此版本")
        compose.onNodeWithText("交付此版本").performScrollTo().performClick()
        waitFor("播放此版本")
        awaitIdle()
        assertNull(projects.state.value.project)
        assertDeliveryAvailable()
        compose.onNodeWithText("草稿已更新至修订", substring = true).assertDoesNotExist()
        compose.onNodeWithText("返回项目检查").assertDoesNotExist()

        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("离线观看")
        compose.onNodeWithText("项目").performClick()
        waitFor("悬浮编排")
        compose.onNodeWithText(sealed.summary.title).performClick()
        waitFor("分镜")
        awaitIdle()
        assertEquals(sealed.projectId, projects.state.value.project?.project?.id)
        val before = checkNotNull(store.readProject(sealed.projectId))
        assertEquals(sealed.projectRevision, before.project.revision)
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("项目文字").performClick()
        waitFor("开场说明（选填）")
        val editedGoal = "保存后的新开场说明不属于已选封存版本。"
        compose.onNodeWithText("开场说明（选填）").performTextReplacement(editedGoal)
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        awaitIdle()
        compose.onNodeWithText("开场说明（选填）").assertDoesNotExist()
        val saved = checkNotNull(store.readProject(sealed.projectId))
        assertEquals(before.project.revision + 1, saved.project.revision)
        assertEquals(editedGoal, saved.project.goal)
        assertEquals(before.steps, saved.steps)
        compose.onNodeWithText("检查与交付").performClick()
        compose.onNodeWithText("查看交付方式").performScrollTo().performClick()
        waitFor(draftWarning(saved.project.revision))
        assertDeliveryAvailable()
        compose.onNodeWithText(draftWarning(saved.project.revision)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("返回项目检查").performScrollTo().assertIsEnabled().performClick()
        waitFor("查看交付方式")
        compose.onNodeWithText("检查与交付").assertIsSelected()
        assertEquals(ProjectRoute.STEPS, projects.state.value.route)
        assertEquals(sealed.projectId, projects.state.value.project?.project?.id)
        assertEquals(fixed, releases.state.value.deliverySelection)

        compose.onNodeWithText("查看交付方式").performScrollTo().performClick()
        waitFor(draftWarning(saved.project.revision))
        val other = runBlocking(Dispatchers.IO) {
            var snapshot = store.createProject(saved.project.title, "同名 B 的独立草稿")
            while (snapshot.project.revision <= saved.project.revision) {
                snapshot = store.renameProject(snapshot.project.id, saved.project.title,
                    "同名 B 的独立修改 ${snapshot.project.revision}")
            }
            snapshot
        }
        assertNotEquals(sealed.projectId, other.project.id)
        assertEquals(saved.project.title, other.project.title)
        // Keep A's delivery route open while the real workspace loads higher-revision, same-title B.
        compose.runOnIdle { projects.openProject(other.project.id) }
        awaitIdle()
        assertEquals(other.project.id, projects.state.value.project?.project?.id)
        assertDeliveryAvailable()
        compose.onNodeWithText("草稿已更新至修订", substring = true).assertDoesNotExist()
        compose.onNodeWithText("返回项目检查").assertDoesNotExist()
        compose.runOnIdle { projects.openProject(sealed.projectId) }
        awaitIdle()
        waitFor(draftWarning(saved.project.revision))
        compose.onNodeWithText("返回项目检查").performScrollTo().performClick()
        waitFor("查看交付方式")
        compose.onNodeWithText("检查与交付").assertIsSelected()
        assertEquals(sealed.projectId, projects.state.value.project?.project?.id)
        assertEquals(fixed, releases.state.value.deliverySelection)
        assertEquals(saved, store.readProject(sealed.projectId))
        assertEquals(other, store.readProject(other.project.id))
        assertEquals(sealedBytes, sealedFiles())
        assertArrayEquals(ViewerPackageCodec.writeScene(sealed.scene),
            runBlocking(Dispatchers.IO) { ViewerPackageCodec.writeScene(releaseStore.loadRelease(sealed.summary.id)) })
        println("HOST_SEALED_DRAFT_DIFFERENCE library/text-save/fixed-revision/same-title-isolation/return-checks/fixed-bytes PASS; system picker/share/device NOT_RUN")
    }

    private fun assertFixedCandidate(expected: ReleaseCandidate, actual: ReleaseCandidate) {
        assertArrayEquals(ViewerPackageCodec.manifestBytes(expected.scene), ViewerPackageCodec.manifestBytes(actual.scene))
        // ViewerScene has no value equals. Compare canonical bytes plus every review field.
        assertEquals(expected, actual.copy(scene = expected.scene))
    }

    private fun assertFixedPlan(expected: DraftAiConfig, actual: DraftAiConfig) {
        // Visits/effects are Java containers without value equals; compare every saved field.
        assertEquals(expected.boundRevision, actual.boundRevision)
        assertEquals(expected.needsRepair, actual.needsRepair)
        assertEquals(DraftAiConfigCodec.encode(expected), DraftAiConfigCodec.encode(actual))
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
        // Lifecycle retains this cache across Robolectric's separate Application instances.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
