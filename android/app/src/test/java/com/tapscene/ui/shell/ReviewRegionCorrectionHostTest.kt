package com.tapscene.ui.shell

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.AiDraftImportFixtures
import com.tapscene.data.AiDraftImportStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.RegionBox
import com.tapscene.data.ReleaseCandidate
import com.tapscene.data.ReleaseStore
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.RegionWorkspace
import com.tapscene.ui.ReleaseWorkspace
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Real MainActivity, Compose, SQLite and PNGs. Synthetic setup is not human privacy review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReviewRegionCorrectionHostTest {
    private var previousRequestedSize: String? = null

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            val app = RuntimeEnvironment.getApplication()
            // Delete only this test's synthetic files in Robolectric's private sandbox.
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

    private val projects get() = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
    private val releases get() = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
    private val regions get() = ViewModelProvider(compose.activity)[RegionWorkspace::class.java]
    private val media get() = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]

    @Test fun fixedRegionReviewRepairsExactSourceAndReturnsToUnchangedCandidate() {
        waitFor("悬浮编排")
        awaitIdle()
        val app = RuntimeEnvironment.getApplication()
        val store = ProjectStore(app)
        val (before, fixed) = createFixture(store)
        val target = before.steps[1]
        val chosen = target.regions.single()
        val candidateBytes = candidateBytes(fixed.id)
        openLibraryRegion(fixed)
        val stateBitmapSha = bitmapSha(checkNotNull(releases.state.value.reviewBitmap))
        val cropBitmapSha = bitmapSha(checkNotNull(releases.state.value.reviewRegionBitmap))
        assertEquals(before.steps[0].id, releases.state.value.reviewStateId)
        assertEquals(chosen.id, releases.state.value.reviewRegionId)
        assertFalse(target.id == releases.state.value.reviewStateId)

        waitForEnabled("修改来源区域")
        compose.onNodeWithText("修改来源区域").performScrollTo().assertIsDisplayed().performClick()
        waitFor("可见区域")
        awaitIdle()
        assertEquals(fixed.projectId, regions.state.value.projectId)
        assertEquals(target.id, regions.state.value.stepId)
        assertEquals(chosen.id, regions.state.value.selectedId)
        compose.onNodeWithText("步骤 02 · 区域 1").assertIsDisplayed()
        compose.onNodeWithText("1 · ${chosen.name}").assertIsSelected()
        assertEquals(before, store.readProject(fixed.projectId))
        assertCandidateUnchanged(fixed, candidateBytes)

        compose.onNodeWithText("编辑区域").performScrollTo().performClick()
        waitFor("编辑可见区域")
        val changedName = "第二步校正后的区域"
        compose.onNodeWithText("名称（必填，最多 120 字）").performTextReplacement(changedName)
        assertEquals(before, store.readProject(fixed.projectId))
        compose.onNodeWithText("保存区域定义").performScrollTo().assertIsEnabled().performClick()
        waitForEnabled("生成裁片")
        awaitIdle()
        val defined = checkNotNull(store.readProject(fixed.projectId))
        val definedRegion = defined.steps.single { it.id == target.id }.regions.single()
        assertEquals(chosen.id, definedRegion.id)
        assertEquals(changedName, definedRegion.name)
        assertEquals(chosen.bbox, definedRegion.bbox)
        assertNull(definedRegion.asset)
        assertNull(definedRegion.reviewedAt)
        assertEquals(before.project.revision + 1, defined.project.revision)
        assertEquals(before.steps.filter { it.id != target.id }, defined.steps.filter { it.id != target.id })
        assertCandidateUnchanged(fixed, candidateBytes)

        compose.onNodeWithText("生成裁片").assertIsEnabled().performClick()
        waitForEnabled("确认实际裁片已复核")
        awaitIdle()
        compose.onNodeWithContentDescription("待检查的实际区域 PNG，未叠加标记或锚点").assertIsDisplayed()
        val generated = checkNotNull(store.readProject(fixed.projectId))
        val generatedRegion = generated.steps.single { it.id == target.id }.regions.single()
        assertNull(generatedRegion.reviewedAt)
        val generatedSha = checkNotNull(generatedRegion.asset).sha256
        assertEquals(generatedSha, regions.state.value.cropSha256)
        assertEquals(generatedSha, regions.state.value.displayedSha256)
        assertEquals(generatedSha, ViewerPackageCodec.sha256(store.regionFile(fixed.projectId, chosen.id)))
        compose.onNodeWithText("确认实际裁片已复核").performClick()
        waitFor("已复核这张裁片")
        awaitIdle()
        val saved = checkNotNull(store.readProject(fixed.projectId))
        val savedTarget = saved.steps.single { it.id == target.id }
        val savedRegion = savedTarget.regions.single()
        assertEquals(before.project.revision + 3, saved.project.revision)
        assertEquals(changedName, savedRegion.name)
        assertTrue(savedRegion.reviewedAt != null)
        assertEquals(generatedRegion, savedRegion.copy(reviewedAt = null))
        assertEquals(target, savedTarget.copy(regions = target.regions))
        assertEquals(before.steps.filter { it.id != target.id }, saved.steps.filter { it.id != target.id })

        // Back first closes the actual crop, then returns to the same fixed review category.
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("可见区域")
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("实际区域裁片")
        awaitIdle()
        compose.onNodeWithText("实际区域裁片").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("实际固定区域裁片").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("草稿已有修改；当前仍是原区域裁片，重新生成并复核后才会进入新成品。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("从当前草稿重新生成").performScrollTo().assertIsEnabled()
        compose.onNodeWithText(changedName).assertDoesNotExist()
        assertEquals(ProjectRoute.PROJECTS, projects.state.value.route)
        assertEquals(before.steps[0].id, releases.state.value.reviewStateId)
        assertEquals(chosen.id, releases.state.value.reviewRegionId)
        assertEquals(stateBitmapSha, bitmapSha(checkNotNull(releases.state.value.reviewBitmap)))
        assertEquals(cropBitmapSha, bitmapSha(checkNotNull(releases.state.value.reviewRegionBitmap)))
        assertCandidateUnchanged(fixed, candidateBytes)
        assertEquals(saved, store.readProject(fixed.projectId))
        // Stop at source correction; no replacement candidate, sealing or device review is claimed.
        println("HOST_REVIEW_REGION exact-source/save/generate/crop-confirm/library-return/fixed-bytes PASS; device NOT_RUN")
    }

    @Test fun missingSourceAndPendingStepEditsCannotRetargetRegionCorrection() {
        waitFor("悬浮编排")
        awaitIdle()
        val app = RuntimeEnvironment.getApplication()
        val store = ProjectStore(app)
        val (before, fixed) = createFixture(store)
        val target = before.steps[1]
        val chosen = target.regions.single()
        val candidateBytes = candidateBytes(fixed.id)
        openLibraryRegion(fixed)
        val cropBitmapSha = bitmapSha(checkNotNull(releases.state.value.reviewRegionBitmap))
        waitForEnabled("修改来源区域")

        // Use the existing source-step route to persist a real unsaved editor draft.
        selectReviewCategory("画面逐项")
        compose.onNodeWithText("2 待复核").performScrollTo().assertIsEnabled().performClick()
        awaitIdle()
        assertEquals(target.id, releases.state.value.reviewStateId)
        waitForEnabled("修改这一步")
        clickVisibleAction("修改这一步")
        waitFor("名称与讲解")
        awaitIdle()
        assertEquals(target.id, projects.state.value.selectedStepId)
        // This terminal step has no outgoing actions; clearing terminal is a valid unsaved edit.
        compose.onNode(isToggleable()).performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("返回").performClick()
        waitFor("来源步骤有暂存修改；请先保存或放弃，再重新生成。")
        awaitIdle()
        assertEquals(setOf(target.id), store.readEditorDrafts(fixed.projectId).keys)
        assertEquals(before, store.readProject(fixed.projectId))
        selectReviewCategory("区域裁片")
        waitFor("实际区域裁片")
        compose.onNodeWithText("修改来源区域").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("来源步骤有暂存修改；请先保存或放弃，再修改区域。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("可见区域").assertDoesNotExist()
        assertNull(regions.state.value.projectId)
        assertCandidateUnchanged(fixed, candidateBytes)

        selectReviewCategory("画面逐项")
        clickVisibleAction("修改这一步")
        waitFor("名称与讲解")
        awaitIdle()
        compose.onNodeWithText("放弃本步修改").performScrollTo().performClick()
        compose.onNodeWithText("放弃修改").performClick()
        awaitIdle()
        compose.waitUntil(30_000) { compose.waitForIdle(); projects.state.value.dirtyStepIds.isEmpty() }
        compose.onNodeWithText("返回").performClick()
        waitFor("修改这一步")
        selectReviewCategory("区域裁片")
        waitForEnabled("修改来源区域")
        assertTrue(store.readEditorDrafts(fixed.projectId).isEmpty())

        // A same-name region still exists in the first step. Never substitute it for the deleted ID.
        compose.onNodeWithText("修改来源区域").performScrollTo().performClick()
        waitFor("可见区域")
        awaitIdle()
        assertEquals(chosen.id, regions.state.value.selectedId)
        compose.onNodeWithText("移除").performScrollTo().performClick()
        waitFor("移除区域？")
        compose.onNode(hasText("移除") and hasAnyAncestor(isDialog())).performClick()
        waitFor("来源区域已不可用")
        awaitIdle()
        compose.onNodeWithText("编辑区域").assertDoesNotExist()
        assertNull(regions.state.value.selectedId)
        // System Back must refresh the source just like the explicit return button.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("实际区域裁片")
        awaitIdle()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("修改来源区域").fetchSemanticsNodes().isEmpty() }
        val remaining = checkNotNull(store.readProject(fixed.projectId))
        assertEquals(1, remaining.steps.flatMap { it.regions }.count { it.name == chosen.name })
        assertEquals(chosen.id, releases.state.value.reviewRegionId)
        assertEquals(cropBitmapSha, bitmapSha(checkNotNull(releases.state.value.reviewRegionBitmap)))
        compose.onNodeWithContentDescription("实际固定区域裁片").performScrollTo().assertIsDisplayed()
        assertCandidateUnchanged(fixed, candidateBytes)

        store.deleteStep(fixed.projectId, target.id)
        compose.runOnIdle { projects.reload() }
        awaitIdle()
        compose.onNodeWithText("修改来源区域").assertDoesNotExist()
        assertEquals(chosen.id, releases.state.value.reviewRegionId)
        assertCandidateUnchanged(fixed, candidateBytes)
        store.deleteProject(fixed.projectId)
        compose.runOnIdle { projects.reload() }
        awaitIdle()
        waitFor("来源项目已移除")
        compose.onNodeWithText("修改来源区域").assertDoesNotExist()
        assertEquals(chosen.id, releases.state.value.reviewRegionId)
        assertEquals(cropBitmapSha, bitmapSha(checkNotNull(releases.state.value.reviewRegionBitmap)))
        assertCandidateUnchanged(fixed, candidateBytes)
        println("HOST_REVIEW_REGION dirty-source/deleted-region-step-project/no-name-fallback/fixed-bytes PASS; device NOT_RUN")
    }

    private fun createFixture(store: ProjectStore): Pair<ProjectSnapshot, ReleaseCandidate> = runBlocking(Dispatchers.IO) {
        val app = RuntimeEnvironment.getApplication()
        val fixture = AiDraftImportFixtures.complete(app)
        val imports = AiDraftImportStore(app)
        val input = fixture.zip.inputStream().use { imports.prepare(it) }
        val projectId = checkNotNull(imports.commit(input.sessionId, input.previewDigest).projectId)
        var snapshot = checkNotNull(store.readProject(projectId))
        val first = snapshot.steps[0]
        val second = snapshot.steps[1]
        snapshot = store.saveStepDraft(projectId, second.id, first.title, second.description,
            second.isTerminal, second.hotspots, snapshot.project.revision, second.nextAction)
        snapshot = store.saveRegion(projectId, second.id, null, first.regions.single().name, null,
            RegionBox(2, 3, 8, 9), 0, .5, .5, snapshot.project.revision)
        val secondRegion = snapshot.steps.single { it.id == second.id }.regions.single()
        snapshot = store.generateRegion(projectId, secondRegion.id, snapshot.project.revision)
        // Fixture-only confirmations establish a valid candidate and existing review records.
        // They do not assert that a person has reviewed these synthetic images.
        AiDraftImportFixtures.reviewDraftRegions(store, projectId)
        snapshot = checkNotNull(store.readProject(projectId))
        val candidates = ReleaseStore(app)
        var candidate = candidates.createCandidate(projectId, snapshot.project.revision)
        candidate = candidates.reviewRegion(candidate.id, candidate.contentDigest, first.regions.single().id)
        candidate = candidates.reviewSummary(candidate.id, candidate.contentDigest)
        assertEquals(2, candidate.scene.regions.count { it.name == first.regions.single().name })
        assertEquals(first.title, snapshot.steps[1].title)
        snapshot to candidate
    }

    private fun openLibraryRegion(fixed: ReleaseCandidate) {
        compose.runOnIdle { projects.reload(); releases.reloadLibrary() }
        awaitIdle()
        compose.onNodeWithText("演示库").performClick()
        waitForEnabled("继续复核")
        compose.onNodeWithText("继续复核").assertIsEnabled().performClick()
        waitFor("1 待复核")
        awaitIdle()
        assertEquals(fixed.id, releases.state.value.candidate?.id)
        selectReviewCategory("区域裁片")
        waitFor("实际区域裁片")
        val chosen = fixed.scene.regions.single { it.stateId == fixed.scene.states[1].id }
        val step = fixed.scene.states[1]
        compose.onNode(hasText(chosen.name) and hasText("${step.title} · ${chosen.bbox.width} × ${chosen.bbox.height}"))
            .performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            !releases.state.value.busy && releases.state.value.reviewRegionId == chosen.id &&
                releases.state.value.reviewRegionBitmap != null
        }
    }

    private fun candidateBytes(candidateId: String): Map<String, ByteArray> {
        val root = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "release-store/candidates/$candidateId")
        check(root.isDirectory)
        return root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }
    }

    private fun assertCandidateUnchanged(expected: ReleaseCandidate, bytes: Map<String, ByteArray>) {
        val restored = runBlocking(Dispatchers.IO) { ReleaseStore(RuntimeEnvironment.getApplication()).readCandidateById(expected.id) }
        assertArrayEquals(ViewerPackageCodec.manifestBytes(expected.scene), ViewerPackageCodec.manifestBytes(restored.scene))
        // ViewerScene has no value equality; compare its bytes and every candidate metadata field.
        assertEquals(expected, restored.copy(scene = expected.scene))
        val visible = checkNotNull(releases.state.value.candidate)
        assertEquals(expected, visible.copy(scene = expected.scene))
        val actual = candidateBytes(expected.id)
        assertEquals(bytes.keys, actual.keys)
        bytes.forEach { (path, content) -> assertArrayEquals("Candidate file changed: $path", content, actual.getValue(path)) }
    }

    private fun bitmapSha(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteBuffer.allocate((pixels.size + 2) * Int.SIZE_BYTES)
        bytes.putInt(bitmap.width).putInt(bitmap.height)
        pixels.forEach { bytes.putInt(it) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    private fun awaitIdle() = compose.waitUntil(30_000) {
        compose.waitForIdle()
        !projects.state.value.busy && !releases.state.value.busy && !regions.state.value.busy && !media.state.value.busy
    }

    private fun clickVisibleAction(label: String) {
        val node = compose.onNodeWithText(label)
        // Tall layouts keep source actions fixed; compact layouts scroll their entire media panel.
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun selectReviewCategory(label: String) {
        // The category row lazily removes off-screen tabs; scroll the row before locating the tab.
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun waitForEnabled(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
    }

    private fun resetApplicationViewModelFactory() {
        // Lifecycle's singleton otherwise keeps the previous Robolectric method's Application.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
