package com.tapscene.ui.shell

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tapscene.MainActivity
import com.tapscene.data.AiDraftImportFixtures
import com.tapscene.data.AiDraftImportStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReleaseStore
import com.tapscene.data.ReleaseSummary
import com.tapscene.data.ReviewedStepInput
import com.tapscene.data.StepOrigin
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.ScreenshotImporter
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.AiDraftWorkspace
import com.tapscene.ui.CandidateWorkspace
import com.tapscene.ui.HostingWorkspace
import com.tapscene.ui.MediaWorkspace
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.ui.RegionWorkspace
import com.tapscene.ui.ReleaseWorkspace
import com.tapscene.ui.ScreenshotWorkspace
import com.tapscene.ui.TransitionWorkspace
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Production MainActivity/shell/workspace/import stores with native PNG and SQLite. The old
 * sealed versions are fixture preconditions, not evidence of their human review. Only external
 * OpenDocument result transport is replaced; device SAF and human privacy judgment are not run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AiImportJourneyHostTest {
    private var previousRequestedSize: String? = null
    private lateinit var incoming: AiDraftImportFixtures.Fixture
    private lateinit var original: ProjectSnapshot
    private lateinit var baselines: List<ReleaseSummary>
    private lateinit var baselineScenes: Map<String, ViewerScene>
    private lateinit var retainedFiles: Map<String, String>

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            val app = RuntimeEnvironment.getApplication()
            val root = app.noBackupFilesDir.canonicalFile
            val sandbox = RuntimeEnvironment.getTempDirectory().basePath.toRealPath()
            check(root.toPath().startsWith(sandbox) && root.toPath() != sandbox)
            root.listFiles().orEmpty().forEach { child ->
                check(child.canonicalFile.toPath().startsWith(root.toPath()))
                check(child.deleteRecursively())
            }
            HostFileSyncShadow.begin(root)
            previousRequestedSize = System.setProperty("robolectric.windowManager.useRequestedSize", "true")
            runBlocking(Dispatchers.IO) { prepareExistingVersions(app) }
            // No AI import session, preview, receipt or imported project is prepared here.
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

    @After fun finishAllWorkspaceOwnersBeforeClosingHostDescriptors() {
        val provider = ViewModelProvider(compose.activity)
        val owners = listOf(provider[ProjectWorkspace::class.java], provider[MediaWorkspace::class.java],
            provider[CandidateWorkspace::class.java], provider[ReleaseWorkspace::class.java],
            provider[AiDraftWorkspace::class.java], provider[ScreenshotWorkspace::class.java],
            provider[HostingWorkspace::class.java], provider[TransitionWorkspace::class.java],
            provider[RegionWorkspace::class.java])
            .map { checkNotNull(it.viewModelScope.coroutineContext[Job]) }
        // These production cleanup scopes outlive viewModelScope. Observe their own completion;
        // never replace their jobs or reset native file descriptors while cleanup is in flight.
        val cleanups = listOf(ScreenshotWorkspace::class.java, MediaWorkspace::class.java,
            TransitionWorkspace::class.java).map {
            val scope = it.getDeclaredField("cleanupScope").apply { isAccessible = true }.get(null) as CoroutineScope
            checkNotNull(scope.coroutineContext[Job])
        }
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        compose.waitUntil(30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            owners.all { it.isCompleted } && cleanups.all { scope -> scope.children.none { !it.isCompleted } }
        }
        compose.waitForIdle()
    }

    @Test fun homeAiImportSelectsExactSameTitleVersionsAndCreatesOnlyTheReceiptProject() {
        waitFor("其他创建方式")
        val app = RuntimeEnvironment.getApplication()
        val provider = ViewModelProvider(compose.activity)
        val projects = provider[ProjectWorkspace::class.java]
        val media = provider[MediaWorkspace::class.java]
        val candidates = provider[CandidateWorkspace::class.java]
        val releases = provider[ReleaseWorkspace::class.java]
        val ai = provider[AiDraftWorkspace::class.java]
        val imports = AiDraftImportStore(app)
        assertSame(app, ai.getApplication<Application>())
        fun settled() = compose.waitUntil(30_000) {
            compose.waitForIdle()
            !projects.state.value.busy && !media.state.value.busy && !candidates.state.value.busy &&
                !releases.state.value.busy && !ai.importState.value.busy && !ai.importState.value.imageBusy
        }
        fun unchanged() {
            assertEquals(listOf(original.project.id), readProjects { it.listProjects().map { project -> project.id } })
            assertOriginalRetained()
        }
        settled()
        unchanged()
        assertNull(ai.importState.value.sessionId)
        assertTrue(runBlocking(Dispatchers.IO) { imports.pending().isEmpty() })

        var request: Int? = null
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(compose.activity, input)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                assertEquals("*/*", intent.type)
                assertArrayEquals(arrayOf("*/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
                assertNull(request)
                launches++
                request = requestCode
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    TapSceneApp(projects, media, candidates, releases)
                }
            }
        }
        fun openFromHome() {
            waitForEnabled("其他创建方式")
            compose.onNodeWithText("其他创建方式").assertIsDisplayed().performClick()
            compose.onNodeWithText("导入 AI 包").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        }
        openFromHome()
        try { compose.waitUntil(30_000) { compose.waitForIdle(); request != null } }
        catch (failure: Throwable) {
            println("AI_PICKER_DIAGNOSTIC projectsBusy=${projects.state.value.busy} route=${projects.state.value.route} mediaBusy=${media.state.value.busy} ai=${ai.importState.value}")
            println(compose.onRoot().printToString(maxDepth = 8))
            throw failure
        }
        unchanged()
        assertNull(ai.importState.value.preview)
        compose.runOnIdle {
            // A file URI is the host-owned document result. The production resolver reads the
            // real complete ZIP; no prepare/preview/commit access or UI state is substituted.
            assertTrue(registry.dispatchResult(checkNotNull(request), Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(incoming.zip))))
            request = null
        }
        waitForEnabled("确认建立新草稿")
        settled()
        assertEquals(1, launches)
        val initial = checkNotNull(ai.importState.value.preview)
        val sessionId = initial.sessionId
        assertNotEquals(original.project.id, initial.newProjectId)
        assertNull(initial.baselineReleaseId)
        assertNull(initial.baselineScene)
        assertTrue(initial.issues.isEmpty())
        assertArrayEquals(ViewerPackageCodec.writeScene(incoming.scene), ViewerPackageCodec.writeScene(initial.scene))
        assertArrayEquals(incoming.plan.toBytes(), initial.plan.toBytes())
        assertEquals(baselines.map { it.id }.toSet(), ai.importState.value.baselines.map { it.id }.toSet())
        assertEquals("ready", runBlocking(Dispatchers.IO) { imports.readResult(sessionId).status })
        unchanged()

        // Read an actual isolated PNG through the UI before choosing any comparison.
        compose.onNodeWithText("图片与区域").performScrollTo().assertIsEnabled().performClick()
        scrollTo(hasText("1. ${incoming.scene.states.first().title}") and hasClickAction())
            .assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { compose.waitForIdle(); ai.importState.value.image != null && !ai.importState.value.imageBusy }
        compose.onNodeWithContentDescription("本机隔离区实际 PNG").assertIsDisplayed()
        assertFixturePixels(checkNotNull(ai.importState.value.image))
        compose.onNodeWithText("关闭").assertIsEnabled().performClick()
        compose.onNodeWithText("内容摘要").performScrollTo().performClick()
        scrollTo(hasText("选择本机对比版本或不比较")).assertIsEnabled().performClick()

        fun select(summary: ReleaseSummary): String {
            val choice = hasClickAction() and hasText("版本 ID：${summary.id}", substring = true)
            scrollTo(choice).assertIsDisplayed().assertIsEnabled().performClick()
            waitForEnabled("确认建立新草稿")
            settled()
            val preview = checkNotNull(ai.importState.value.preview)
            assertEquals(sessionId, preview.sessionId)
            assertEquals(initial.newProjectId, preview.newProjectId)
            assertEquals(summary.id, preview.baselineReleaseId)
            val snapshot = checkNotNull(preview.baselineScene)
            assertArrayEquals(ViewerPackageCodec.writeScene(checkNotNull(baselineScenes[summary.id])),
                ViewerPackageCodec.writeScene(snapshot))
            assertEquals(summary.contentDigest, ViewerPackageCodec.contentDigest(snapshot))
            scrollTo(hasText("对比版本 ID：${summary.id}")).assertIsDisplayed()
            scrollTo(hasText("内容 SHA-256：${summary.contentDigest}")).assertIsDisplayed()
            assertEquals(preview.previewDigest, runBlocking(Dispatchers.IO) { imports.readPrepared(sessionId)?.previewDigest })
            unchanged()
            return preview.previewDigest
        }

        // Equal titles AND equal minute labels cannot identify these two immutable snapshots.
        val firstDigest = select(baselines[0])
        val secondDigest = select(baselines[1])
        assertNotEquals(firstDigest, secondDigest)
        scrollTo(hasClickAction() and hasText("不比较，只看完整内容", substring = true))
            .assertIsEnabled().performClick()
        waitForEnabled("确认建立新草稿")
        settled()
        val noComparison = checkNotNull(ai.importState.value.preview)
        assertNull(noComparison.baselineReleaseId)
        assertNull(noComparison.baselineScene)
        assertTrue(noComparison.differences.isEmpty())
        assertEquals(initial.previewDigest, noComparison.previewDigest)
        compose.onNodeWithText("对比版本 ID：${baselines[1].id}").assertDoesNotExist()
        unchanged()
        assertEquals(firstDigest, select(baselines[0]))

        // Back leaves the actual durable import pending. Re-entering from home restores the
        // exact comparison and must neither reopen the document picker nor create a project.
        compose.onNodeWithContentDescription("返回").assertIsEnabled().performClick()
        waitForEnabled("其他创建方式")
        settled()
        assertEquals(ProjectRoute.PROJECTS, projects.state.value.route)
        unchanged()
        openFromHome()
        waitForEnabled("确认建立新草稿")
        settled()
        assertEquals(1, launches)
        assertNull(request)
        assertEquals(sessionId, ai.importState.value.sessionId)
        assertEquals(firstDigest, ai.importState.value.preview?.previewDigest)
        scrollTo(hasText("对比版本 ID：${baselines[0].id}")).assertIsDisplayed()
        scrollTo(hasText("内容 SHA-256：${baselines[0].contentDigest}")).assertIsDisplayed()
        unchanged()

        compose.onNodeWithText("确认建立新草稿").assertIsDisplayed().assertIsEnabled().performClick()
        waitForEnabled("打开新草稿并复核")
        settled()
        val receipt = checkNotNull(ai.importState.value.result)
        assertEquals("committed", receipt.status)
        assertTrue(receipt.projectExists)
        assertEquals(sessionId, receipt.sessionId)
        val newId = checkNotNull(receipt.projectId)
        assertEquals(initial.newProjectId, newId)
        assertNotEquals(original.project.id, newId)
        assertEquals(receipt, runBlocking(Dispatchers.IO) { imports.readResult(sessionId) })
        assertEquals(setOf(original.project.id, newId), readProjects { it.listProjects().map { project -> project.id }.toSet() })
        assertNull(projects.state.value.project) // Completion does not silently navigate or edit the old draft.
        assertOriginalRetained()
        val imported = readProjects { checkNotNull(it.readProject(newId)) }
        assertImportedProject(imported, sessionId)

        compose.onNodeWithText("打开新草稿并复核").assertIsEnabled().performClick()
        waitFor("分镜")
        settled()
        assertEquals(newId, projects.state.value.project?.project?.id)
        assertEquals(ProjectRoute.STEPS, projects.state.value.route)
        compose.onNodeWithText("步骤").assertIsSelected()
        compose.onNodeWithText("已导入待复核；请重新检查画面、文字、动作、区域与动画计划。").assertIsDisplayed()
        assertNull(ai.importState.value.sessionId)
        compose.onNodeWithContentDescription("返回").assertIsEnabled().performClick()
        waitForEnabled("其他创建方式")
        settled()
        scrollTo(hasText(original.project.title) and hasClickAction()).assertIsDisplayed().performClick()
        waitFor("分镜")
        settled()
        assertEquals(original.project.id, projects.state.value.project?.project?.id)
        assertEquals(original, projects.state.value.project)
        compose.onNodeWithText("步骤").assertIsSelected()
        compose.onNodeWithText("1 个步骤 · 0 个出口").assertIsDisplayed()
        assertEquals(imported, readProjects { it.readProject(newId) })
        assertEquals(setOf(original.project.id, newId), readProjects { it.listProjects().map { project -> project.id }.toSet() })
        assertTrue(runBlocking(Dispatchers.IO) { imports.pending().isEmpty() })
        assertOriginalRetained()
        println("HOST_AI_IMPORT_JOURNEY home/OpenDocument/real-PNG/full-package/exact-same-title-version/no-comparison/back-resume/explicit-commit/receipt-project/original-bytes PASS; fixture-seal-human-review/device-SAF/lifecycle NOT_RUN")
    }

    private suspend fun prepareExistingVersions(app: Application) {
        incoming = AiDraftImportFixtures.complete(app)
        ProjectStore.withTemporary(app) { store ->
            runBlocking {
                var draft = store.createProject("同名本机固定版本", "保留原项目及两份既有封存版。")
                val image = File(incoming.assetRoot, incoming.scene.assets.first().path)
                ScreenshotImporter(app).importScreenshot(image).use { imported ->
                    val png = SafeMediaWriter(app).writePng(imported.bitmap, emptyList(), File(app.noBackupFilesDir, "ai-journey-safe"))
                    draft = store.addReviewedStep(draft.project.id,
                        ReviewedStepInput(png.file, png.sha256, png.width, png.height,
                            StepOrigin.ImportedImage(imported.source), emptyList()), "旧项目固定画面", "第一份版本文字")
                }
                val step = draft.steps.single()
                draft = store.saveStepDraft(draft.project.id, step.id, step.title, step.description, true,
                    emptyList(), draft.project.revision)
                val first = sealExistingFixture(app, draft)
                draft = store.saveStepDraft(draft.project.id, step.id, step.title, "第二份版本文字", true,
                    emptyList(), draft.project.revision)
                val second = sealExistingFixture(app, draft)
                original = draft
                // Fixture metadata only, before observation/UI launch: deliberately make display
                // timestamps identical without mocking import or forging review/commit state.
                val sameMinute = 1_700_000_000_000L
                for (summary in listOf(first, second)) {
                    val file = File(app.noBackupFilesDir, "release-store/releases/${summary.id}/summary.json")
                    val json = JSONObject(file.readText()).put("sealedAt", sameMinute)
                    file.writeText(json.toString())
                }
            }
        }
        val store = ReleaseStore(app)
        baselines = store.listReleases()
        assertEquals(2, baselines.size)
        assertEquals(1, baselines.map { it.title }.distinct().size)
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        assertEquals(1, baselines.map { format.format(Date(it.sealedAt)) }.distinct().size)
        assertEquals(setOf("local"), baselines.map { it.origin }.toSet())
        assertEquals(2, baselines.map { it.contentDigest }.distinct().size)
        baselineScenes = baselines.associate { it.id to store.loadRelease(it.id) }
        retainedFiles = retainedHashes()
    }

    /** Set up previously sealed local data through production APIs. This setup is not the
     * behavior under test and does not claim UI traversal or human privacy review coverage. */
    private suspend fun sealExistingFixture(app: Application, draft: ProjectSnapshot): ReleaseSummary {
        val store = ReleaseStore(app)
        var candidate = store.createCandidate(draft.project.id, draft.project.revision)
        val step = candidate.scene.states.single()
        store.candidateAssetFile(candidate.id, step.id)
        candidate = store.reviewState(candidate.id, candidate.contentDigest, step.id)
        candidate = store.reviewSummary(candidate.id, candidate.contentDigest)
        candidate = store.reviewFileList(candidate.id, candidate.contentDigest)
        candidate = store.recordTraversal(candidate.id, candidate.contentDigest, null, false)
        return store.seal(candidate.id, candidate.contentDigest)
    }

    private fun assertImportedProject(snapshot: ProjectSnapshot, sessionId: String) {
        assertEquals(incoming.scene.title, snapshot.project.title)
        assertEquals(incoming.scene.goal, snapshot.project.goal)
        assertEquals(incoming.scene.startStateId, snapshot.project.startStepId)
        assertEquals(incoming.scene.states.map { it.id }, snapshot.steps.map { it.id })
        assertEquals(incoming.scene.edges.map { it.id }.toSet(), snapshot.steps.flatMap {
            it.hotspots.map { hotspot -> hotspot.edgeId } + listOfNotNull(it.nextAction?.id)
        }.toSet())
        val importedAssets = snapshot.steps.map { it.asset } + snapshot.steps.flatMap { it.regions }.map { checkNotNull(it.asset) }
        assertEquals(importedAssets.size, importedAssets.map { it.id }.distinct().size)
        assertTrue(importedAssets.none { asset -> incoming.scene.assets.any { it.id == asset.id } || original.steps.any { it.asset.id == asset.id } })
        for ((step, expected) in snapshot.steps.zip(incoming.scene.states)) {
            assertEquals(expected.title, step.title)
            assertEquals(expected.description, step.description)
            assertEquals(expected.terminal, step.isTerminal)
            assertEquals("imported", step.evidenceKind)
            val origin = step.origin as StepOrigin.PackageSafeImage
            assertEquals(sessionId, origin.importId)
            assertEquals(expected.id, origin.sourceStateId)
            assertNull(step.videoOrigin)
            assertNull(step.frameTimeUs)
            val asset = incoming.scene.assets.single { it.id == expected.imageAssetId }
            assertEquals(asset.sha256, step.asset.sha256)
            val saved = readProjects { it.resolveAsset(snapshot.project.id, step.id) }
            assertEquals(asset.sha256, ViewerPackageCodec.sha256(saved))
            assertArrayEquals(File(incoming.assetRoot, asset.path).readBytes(), saved.readBytes())
        }
        assertEquals(incoming.scene.regions.map { it.id }, snapshot.steps.flatMap { it.regions }.map { it.id })
        assertTrue(snapshot.steps.flatMap { it.regions }.all { it.reviewedAt == null })
        val config = readProjects { checkNotNull(it.readDraftAiConfig(snapshot.project.id)) }
        assertArrayEquals(incoming.plan.toBytes(), config.resolve(incoming.scene).toBytes())
        assertTrue(runBlocking(Dispatchers.IO) { ReleaseStore(RuntimeEnvironment.getApplication()).listCandidates().isEmpty() })
    }

    private fun assertOriginalRetained() {
        assertEquals(original, readProjects { it.readProject(original.project.id) })
        assertEquals(baselines, runBlocking(Dispatchers.IO) { ReleaseStore(RuntimeEnvironment.getApplication()).listReleases() })
        assertEquals(retainedFiles, retainedHashes())
    }

    private fun retainedHashes(): Map<String, String> {
        val root = RuntimeEnvironment.getApplication().noBackupFilesDir
        val source = (original.steps.single().origin as StepOrigin.ImportedImage).source
        val directories = listOf(File(root, "project-assets/${original.project.id}"),
            File(root, source.privateRelativePath).parentFile!!) +
            baselines.map { File(root, "release-store/releases/${it.id}") }
        return (directories.flatMap { directory -> directory.walkTopDown().filter { it.isFile }.toList() } + incoming.zip)
            .associate { it.relativeTo(root).invariantSeparatorsPath to ViewerPackageCodec.sha256(it) }
    }

    private fun assertFixturePixels(bitmap: Bitmap) {
        assertEquals(32, bitmap.width)
        assertEquals(48, bitmap.height)
        for (y in 0 until 48) for (x in 0 until 32) {
            assertEquals("Pixel ($x,$y)", Color.rgb(x * 7, y * 5, (x + y) * 3), bitmap.getPixel(x, y))
        }
    }

    private fun <T> readProjects(block: (ProjectStore) -> T): T = runBlocking(Dispatchers.IO) {
        ProjectStore.withTemporary(RuntimeEnvironment.getApplication(), block)
    }

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
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
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
