package com.tapscene.ui.shell

import android.graphics.BitmapFactory
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
import com.tapscene.data.RegionBox
import com.tapscene.data.ReviewedStepInput
import com.tapscene.data.StepOrigin
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectIssueCorrection
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.RegionWorkspace
import com.tapscene.ui.ReleaseWorkspace
import java.io.File
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
        waitFor("当前检查未返回待处理问题。")
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
        waitFor("当前检查未返回待处理问题。")
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

    @Test fun regionPreflightTargetsExactCropAndRequiresExplicitGenerationAndReview() {
        waitFor("悬浮编排")
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val releases = ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        val regions = ViewModelProvider(compose.activity)[RegionWorkspace::class.java]
        val store = ProjectStore(app)
        fun awaitIdle() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !releases.state.value.busy &&
                !media.state.value.busy && !regions.state.value.busy
        }
        awaitIdle()
        val (before, targetStepId, staleStepId) = runBlocking(Dispatchers.IO) {
            val fixture = AiDraftImportFixtures.complete(app)
            val imports = AiDraftImportStore(app)
            val input = fixture.zip.inputStream().use { imports.prepare(it) }
            val projectId = checkNotNull(imports.commit(input.sessionId, input.previewDigest).projectId)
            var snapshot = checkNotNull(store.readProject(projectId))
            val start = snapshot.steps.single { it.id == snapshot.project.startStepId }
            val regionName = start.regions.single().name
            // Keep the imported crop unreviewed. Only fixture setup writes below establish
            // the other readiness states; these calls are not UI or human review claims.
            assertNull(start.regions.single().reviewedAt)
            for (stepId in listOf(fixture.endId, fixture.branchId)) {
                val step = snapshot.steps.single { it.id == stepId }
                snapshot = store.saveStepDraft(projectId, stepId, start.title, step.description,
                    step.isTerminal, step.hotspots, snapshot.project.revision, step.nextAction)
            }
            // Two same-name regions in one of the same-title steps prove exact region targeting.
            for (order in 0..1) snapshot = store.saveRegion(projectId, fixture.endId, null,
                regionName, null, RegionBox(2 + order, 3 + order, 8, 9), order, .5, .5,
                snapshot.project.revision)
            snapshot = store.saveRegion(projectId, fixture.branchId, null, regionName, null,
                RegionBox(3, 4, 10, 12), 0, .5, .5, snapshot.project.revision)
            val staleId = snapshot.steps.single { it.id == fixture.branchId }.regions.single().id
            snapshot = store.generateRegion(projectId, staleId, snapshot.project.revision)
            val oldCrop = checkNotNull(snapshot.steps.single { it.id == fixture.branchId }.regions.single().asset)
            snapshot = store.reviewRegion(projectId, staleId, snapshot.project.revision, oldCrop.sha256)
            val binding = snapshot.steps.single { it.id == fixture.branchId }.safeImageBinding(snapshot.project)
            val bitmap = store.readSafeImageBase(binding)
            val masks = listOf(OpaqueMask(.2f, .2f, .5f, .5f))
            val output = try {
                SafeMediaWriter(app).writePng(bitmap, masks, File(app.noBackupFilesDir, "region-base-${UUID.randomUUID()}"))
            } finally { bitmap.recycle() }
            snapshot = store.replaceReviewedStep(projectId, fixture.branchId, binding.revision,
                ReviewedStepInput(output.file, output.sha256, output.width, output.height,
                    StepOrigin.Image(binding), masks, UUID.randomUUID().toString()))
            Triple(snapshot, fixture.endId, fixture.branchId)
        }
        val projectId = before.project.id
        val imported = before.steps.single { it.id == before.project.startStepId }
        val target = before.steps.single { it.id == targetStepId }
        val stale = before.steps.single { it.id == staleStepId }
        val chosen = target.regions[1]
        val importedRegion = imported.regions.single()
        assertEquals(3, before.steps.count { it.title == target.title })
        assertEquals(2, target.regions.count { it.name == chosen.name })
        assertTrue(ProjectWorkspace.graphIssues(before).isEmpty())
        assertTrue(importedRegion.asset != null && importedRegion.reviewedAt == null && importedRegion.matchesBase(imported.asset))
        assertTrue(target.regions.all { it.asset == null && it.reviewedAt == null && it.matchesBase(target.asset) })
        assertFalse(stale.regions.single().matchesBase(stale.asset))
        assertNull(stale.regions.single().asset)
        val prefix = "步骤“${target.title}”的区域“${chosen.name}”"
        val unreviewedMessage = "${prefix}尚未复核裁片，请查看并确认。"
        val missingMessage = "${prefix}尚未生成裁片，请生成并复核。"
        val staleMessage = "${prefix}底图已改变，请校正范围后重新生成并复核。"
        fun savedFiles() = File(app.noBackupFilesDir, "project-assets/$projectId").walkTopDown()
            .filter { it.isFile }.associate { it.name to ViewerPackageCodec.sha256(it) }
        // Settle the fixture replacement's normal journal cleanup before freezing files.
        assertEquals(before, store.readProject(projectId))
        val filesBefore = savedFiles()
        val planBefore = checkNotNull(store.readDraftAiConfig(projectId))

        compose.runOnIdle { projects.reload() }
        awaitIdle()
        compose.onNodeWithText(before.project.title).performClick()
        waitFor("分镜")
        awaitIdle()
        compose.onNodeWithText("检查与交付").performClick()
        compose.waitForIdle()
        assertEquals(listOf(unreviewedMessage, missingMessage, missingMessage, staleMessage),
            projects.state.value.issues.map { it.message })
        compose.onNodeWithText(unreviewedMessage).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(missingMessage).assertCountEquals(2)
        compose.onNodeWithText(staleMessage).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("生成待复核成品").performScrollTo().assertIsNotEnabled()

        // Synthetic owner/busy boundary only; all project data and image files remain real.
        @Suppress("UNCHECKED_CAST")
        val regionState = RegionWorkspace::class.java.getDeclaredField("mutableState")
            .apply { isAccessible = true }.get(regions) as
            kotlinx.coroutines.flow.MutableStateFlow<com.tapscene.ui.RegionUiState>
        val previousOwner = com.tapscene.ui.RegionUiState(projectId = projectId, stepId = stale.id,
            snapshot = before, selectedId = stale.regions.single().id, busy = true,
            stage = "Synthetic previous-owner boundary")
        compose.runOnIdle { regionState.value = previousOwner }
        // Opening correction waits at the injected busy boundary and preserves that owner state.
        compose.onNodeWithText(unreviewedMessage).performScrollTo().performClick()
        waitFor("读取区域")
        compose.onNodeWithText("正在等待之前的区域处理完成，随后读取当前区域。").assertIsDisplayed()
        compose.onNodeWithText("可见区域").assertDoesNotExist()
        compose.onNodeWithText("复核实际裁片").assertDoesNotExist()
        assertEquals(previousOwner, regions.state.value)
        assertEquals(before, store.readProject(projectId))
        assertEquals(filesBefore, savedFiles())
        compose.runOnIdle { regionState.value = previousOwner.copy(busy = false, stage = null) }
        waitFor("可见区域")
        awaitIdle()
        assertEquals(ProjectRoute.STEPS, projects.state.value.route)
        assertEquals(projectId, regions.state.value.projectId)
        assertEquals(imported.id, regions.state.value.stepId)
        assertEquals(importedRegion.id, regions.state.value.selectedId)
        assertNull(regions.state.value.cropBitmap)
        compose.onNodeWithText("查看并复核").assertIsEnabled()
        assertEquals(before, store.readProject(projectId))
        assertEquals(filesBefore, savedFiles())
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor(unreviewedMessage)
        awaitIdle()
        compose.onNodeWithText("检查与交付").assertIsSelected()

        compose.onNodeWithText(staleMessage).performScrollTo().performClick()
        waitFor("可见区域")
        awaitIdle()
        assertEquals(projectId, regions.state.value.projectId)
        assertEquals(stale.id, regions.state.value.stepId)
        assertEquals(stale.regions.single().id, regions.state.value.selectedId)
        compose.onNodeWithText("底图已改变：先校正范围并保存，再重新生成。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("生成裁片").assertIsNotEnabled()
        assertEquals(before, store.readProject(projectId))
        assertEquals(filesBefore, savedFiles())
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor(missingMessage)
        awaitIdle()

        // A previous covered visit can retain this same step with a different selected region.
        // A fresh issue click must apply its own ID rather than reuse that selection.
        compose.runOnIdle { regionState.value = com.tapscene.ui.RegionUiState(
            projectId = projectId, stepId = target.id, snapshot = before, selectedId = target.regions[0].id) }
        // Choose the second identical issue, not the first matching title or region name.
        compose.onAllNodesWithText(missingMessage)[1].performScrollTo().performClick()
        waitFor("可见区域")
        awaitIdle()
        assertEquals(ProjectRoute.STEPS, projects.state.value.route)
        assertEquals(projectId, regions.state.value.projectId)
        assertEquals(target.id, regions.state.value.stepId)
        assertEquals(chosen.id, regions.state.value.selectedId)
        assertNull(regions.state.value.cropBitmap)
        assertEquals(before, store.readProject(projectId))
        assertEquals(filesBefore, savedFiles())
        compose.onNodeWithText("生成裁片").assertIsEnabled().performClick()
        waitForEnabled("确认实际裁片已复核")
        awaitIdle()
        compose.onNodeWithContentDescription("待检查的实际区域 PNG，未叠加标记或锚点").assertIsDisplayed()
        val generated = checkNotNull(store.readProject(projectId))
        val generatedRegion = generated.steps.single { it.id == target.id }.regions.single { it.id == chosen.id }
        val cropAsset = checkNotNull(generatedRegion.asset)
        assertEquals(before.project.revision + 1, generated.project.revision)
        assertNull(generatedRegion.reviewedAt)
        assertEquals(cropAsset.sha256, regions.state.value.cropSha256)
        assertEquals(cropAsset.sha256, regions.state.value.displayedSha256)
        assertEquals(cropAsset.sha256, ViewerPackageCodec.sha256(store.regionFile(projectId, chosen.id)))
        val crop = checkNotNull(regions.state.value.cropBitmap)
        val base = checkNotNull(BitmapFactory.decodeFile(store.resolveAsset(projectId, target.id).path))
        try {
            assertEquals(chosen.bbox.width, crop.width)
            assertEquals(chosen.bbox.height, crop.height)
            for (y in 0 until crop.height) for (x in 0 until crop.width)
                assertEquals(base.getPixel(chosen.bbox.x + x, chosen.bbox.y + y), crop.getPixel(x, y))
        } finally { base.recycle() }
        // Existing same-owner reentry must preserve the exact displayed crop and selection.
        val displayedState = regions.state.value
        compose.runOnIdle { regions.open(projectId, target.id) }
        compose.waitForIdle()
        assertSame(displayedState, regions.state.value)
        assertSame(crop, regions.state.value.cropBitmap)
        compose.onNodeWithContentDescription("待检查的实际区域 PNG，未叠加标记或锚点").assertIsDisplayed()
        compose.onNodeWithText("确认实际裁片已复核").assertIsEnabled()
        assertEquals(generated, store.readProject(projectId))
        compose.onNodeWithText("确认实际裁片已复核").performClick()
        waitFor("已复核这张裁片")
        awaitIdle()
        val saved = checkNotNull(store.readProject(projectId))
        val savedTarget = saved.steps.single { it.id == target.id }
        val reviewed = savedTarget.regions.single { it.id == chosen.id }
        assertEquals(before.project.revision + 2, saved.project.revision)
        assertTrue(reviewed.reviewedAt != null)
        assertEquals(generatedRegion, reviewed.copy(reviewedAt = null))
        assertEquals(target, savedTarget.copy(regions = target.regions))
        assertEquals(target.regions.filter { it.id != chosen.id }, savedTarget.regions.filter { it.id != chosen.id })
        assertEquals(before.steps.filter { it.id != target.id }, saved.steps.filter { it.id != target.id })

        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("可见区域")
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("3 项待处理")
        awaitIdle()
        compose.onNodeWithText("检查与交付").assertIsSelected()
        assertEquals(ProjectRoute.STEPS, projects.state.value.route)
        assertEquals(projectId, projects.state.value.project?.project?.id)
        assertEquals(listOf(unreviewedMessage, missingMessage, staleMessage), projects.state.value.issues.map { it.message })
        compose.onAllNodesWithText(missingMessage).assertCountEquals(1)
        compose.onNodeWithText(unreviewedMessage).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(staleMessage).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("生成待复核成品").performScrollTo().assertIsNotEnabled()
        assertEquals(saved, store.readProject(projectId))
        val planAfter = checkNotNull(store.readDraftAiConfig(projectId))
        assertEquals(DraftAiConfigCodec.encode(planBefore), DraftAiConfigCodec.encode(planAfter))
        assertEquals(planBefore.boundRevision, planAfter.boundRevision)
        assertTrue(planAfter.needsRepair)
        assertTrue(store.draftAiIssues(projectId, planAfter).isNotEmpty())
        assertNull(releases.state.value.candidate)
        // Stop with the remaining region and imported-plan gates intact; no candidate is created.
        println("HOST_DELIVERY_REGION missing/unreviewed/changed-base/exact-IDs/previous-owner-wait/no-auto-write/actual-PNG/same-owner-reentry/explicit-review/return-checks PASS; candidate NOT_RUN; device NOT_RUN")
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
