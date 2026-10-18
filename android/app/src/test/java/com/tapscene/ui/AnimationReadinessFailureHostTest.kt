package com.tapscene.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.DraftAiConfig
import com.tapscene.data.DraftAiConfigCodec
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostProjectFixture
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReviewedStepInput
import com.tapscene.media.ImportedSource
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
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

/** Real workspace, SQLite and synthetic PNG commits. The source bytes are only a persistence
 * fixture: no video decoding, device lifecycle, power-loss or human privacy-review claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class AnimationReadinessFailureHostTest {
    @Before fun configureSqlite() {
        val root = RuntimeEnvironment.getApplication().noBackupFilesDir.canonicalFile.toPath()
        val sandbox = RuntimeEnvironment.getTempDirectory().basePath.toRealPath()
        check(root.startsWith(sandbox) && root != sandbox)
        HostSqlite.configure()
    }

    @After fun closeHostDescriptors() = HostFileSyncShadow.reset()

    @Test fun unreadableAnimationCannotTurnCommittedAddOrReplacementIntoFailure() = runBlocking {
        check(Looper.myLooper() != Looper.getMainLooper())
        withTimeout(120_000) {
            HostProjectFixture().use { fixture ->
                HostFileSyncShadow.begin(fixture.root)
                val source = syntheticSource(fixture)
                val input = png(fixture, source, Color.BLUE)
                materializeSavedImages(fixture, input)
                val initial = fixture.snapshot()
                val savedPlan = fixture.store.saveDraftAiConfig(fixture.project, initial.project.revision,
                    DraftAiConfig(initial.project.revision, false, 1080, 1920, listOf(
                        RenderPlan.Visit(fixture.id(), fixture.a, initial.steps.single { it.id == fixture.a }.hotspots.single().edgeId, 60),
                        RenderPlan.Visit(fixture.id(), fixture.b, null, 60),
                    ), emptyList()))
                check(!savedPlan.needsRepair && fixture.store.draftAiIssues(fixture.project, savedPlan).isEmpty())
                val originalJson = DraftAiConfigCodec.encode(savedPlan)
                val owner = ViewModelStore()
                val workspace = main {
                    ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(fixture.app))[ProjectWorkspace::class.java]
                }
                try {
                    settled(workspace)
                    main { workspace.openProject(fixture.project) }
                    settled(workspace)
                    check(!workspace.state.value.loadFailed && workspace.state.value.issues.isEmpty())
                    val beforeAdd = fixture.snapshot()

                    // Leave the exact project's row corrupt throughout both applyProject and
                    // the final refresh. The image transaction itself must still commit once.
                    setPlanJson(fixture, BROKEN_JSON)
                    check(runCatching { fixture.store.readDraftAiConfig(fixture.project) }.isFailure)
                    val addedId = workspace.saveReviewedStep(fixture.project, input, title = "Committed synthetic step")
                    val added = fixture.snapshot()
                    check(added.project.revision == beforeAdd.project.revision + 1)
                    check(added.steps.size == beforeAdd.steps.size + 1)
                    val addedStep = added.steps.single { it.captureId == input.captureId }
                    check(addedId == addedStep.id && addedStep.title == "Committed synthetic step")
                    check(addedStep.asset.sha256 == input.sha256)
                    check(workspace.state.value.selectedStepId == addedId)
                    assertCommittedReadFailure(fixture, workspace, added)

                    setPlanJson(fixture, originalJson)
                    main { workspace.reload() }
                    settled(workspace)
                    assertRecovered(fixture, workspace, added)

                    // The same persistent read failure must not become a second exception
                    // after a successful replacement either. Preserve the existing action.
                    val previous = added.steps.single { it.id == fixture.a }
                    val replacement = png(fixture, source, Color.YELLOW)
                    val correction = StepImageCorrection(fixture.project, previous.id, added.project.revision,
                        fixture.id(), previous.title, previous.regions.size, 0)
                    setPlanJson(fixture, BROKEN_JSON)
                    workspace.replaceReviewedStep(correction, replacement)
                    val replaced = fixture.snapshot()
                    val replacedStep = replaced.steps.single { it.id == previous.id }
                    check(replaced.project.revision == added.project.revision + 1 && replaced.steps.size == added.steps.size)
                    check(replacedStep.captureId == replacement.captureId && replacedStep.asset.sha256 == replacement.sha256)
                    check(replacedStep.asset.id != previous.asset.id)
                    check(replacedStep.title == previous.title && replacedStep.description == previous.description)
                    check(replacedStep.hotspots == previous.hotspots && replacedStep.nextAction == previous.nextAction)
                    check(replaced.steps.filter { it.id != previous.id } == added.steps.filter { it.id != previous.id })
                    check(workspace.state.value.selectedStepId == previous.id)
                    assertCommittedReadFailure(fixture, workspace, replaced)

                    setPlanJson(fixture, originalJson)
                    main { workspace.reload() }
                    settled(workspace)
                    assertRecovered(fixture, workspace, replaced)
                    check(input.file.isFile && ViewerPackageCodec.sha256(input.file) == input.sha256)
                    check(replacement.file.isFile && ViewerPackageCodec.sha256(replacement.file) == replacement.sha256)
                    println("HOST_ANIMATION_READ_FAILURE add/replace=committed-once returned-id=SQLite snapshot=published busy=cleared unreadable-plan=blocked restore/reload=readable-needs-repair; video/device/human-review NOT_RUN")
                } finally {
                    setPlanJson(fixture, originalJson)
                    withContext(NonCancellable) {
                        val jobs = main {
                            owner.clear()
                            listOfNotNull(field(workspace, "task") as Job?, field(workspace, "stagingTask") as Job?)
                        }
                        withTimeout(10_000) { jobs.forEach { it.join() } }
                        fixture.closeStore(field(workspace, "store") as ProjectStore)
                    }
                }
            }
        }
    }

    private fun assertCommittedReadFailure(fixture: HostProjectFixture, workspace: ProjectWorkspace, saved: ProjectSnapshot) {
        val state = workspace.state.value
        check(!state.busy && state.stage == null && state.loadFailed)
        check(state.project == saved) { "A failed animation read hid the committed project snapshot" }
        check(state.issues.single { it.correction == ProjectIssueCorrection.ANIMATION }.message == READ_FAILURE)
        check(runCatching { fixture.store.readDraftAiConfig(fixture.project) }.isFailure)
        // An independent production connection establishes persisted rows, not just UI memory.
        val reopened = fixture.newStore()
        try { check(reopened.readProject(fixture.project) == saved) } finally { fixture.closeStore(reopened) }
    }

    private fun assertRecovered(fixture: HostProjectFixture, workspace: ProjectWorkspace, saved: ProjectSnapshot) {
        val state = workspace.state.value
        check(!state.busy && state.stage == null && !state.loadFailed && state.project == saved)
        check(state.issues.none { it.message == READ_FAILURE })
        check(checkNotNull(fixture.store.readDraftAiConfig(fixture.project)).needsRepair)
        check(state.issues.any { it.correction == ProjectIssueCorrection.ANIMATION &&
            it.message == "动画计划待核对，请检查路径与效果并保存。" })
    }

    private fun setPlanJson(fixture: HostProjectFixture, json: String) = fixture.database {
        check(update("draft_ai_configs", ContentValues().apply { put("config_json", json) },
            "project_id=?", arrayOf(fixture.project)) == 1)
    }

    private fun materializeSavedImages(fixture: HostProjectFixture, input: ReviewedStepInput) {
        fixture.snapshot().steps.forEach { step ->
            val file = File(fixture.root, step.asset.privateRelativePath)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            input.file.copyTo(file)
            fixture.database {
                execSQL("UPDATE local_assets SET sha256=?,byte_length=?,width=?,height=? WHERE project_id=? AND asset_id=?",
                    arrayOf<Any>(input.sha256, file.length(), input.width, input.height, fixture.project, step.asset.id))
                // Image-origin provenance must retain the formal PNG's canvas dimensions.
                check(update("states", ContentValues().apply {
                    put("base_width", input.width); put("base_height", input.height)
                }, "project_id=? AND state_id=? AND origin_kind='image'", arrayOf(fixture.project, step.id)) == 1)
            }
        }
        fixture.database {
            execSQL("UPDATE states SET is_terminal=1 WHERE project_id=? AND state_id=?", arrayOf(fixture.project, fixture.b))
        }
    }

    private fun syntheticSource(fixture: HostProjectFixture): ImportedSource {
        val id = fixture.id()
        val path = "sources/$id.mp4"
        val file = File(fixture.root, path)
        check(file.parentFile!!.mkdirs())
        file.writeText("Synthetic source persistence bytes; not decoded video")
        return ImportedSource(id, path, "Synthetic source", SourceMetadata("video/mp4", file.length(),
            ViewerPackageCodec.sha256(file), 32, 48, 0, 1_000_000))
    }

    private suspend fun png(fixture: HostProjectFixture, source: ImportedSource, color: Int): ReviewedStepInput {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try {
            val output = SafeMediaWriter(fixture.app).writePng(bitmap, emptyList(), File(fixture.root, "input-${fixture.id()}"))
            ReviewedStepInput(output.file, output.sha256, output.width, output.height, source, 200_000, 1_000,
                emptyList(), fixture.id())
        } finally { bitmap.recycle() }
    }

    private suspend fun settled(workspace: ProjectWorkspace) = withTimeout(10_000) {
        workspace.state.first { !it.busy }
        main { field(workspace, "task") as Job? }?.join()
    }

    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main.immediate) {
        check(Looper.myLooper() == Looper.getMainLooper())
        block()
    }

    private fun field(workspace: ProjectWorkspace, name: String): Any? = ProjectWorkspace::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(workspace)

    companion object {
        private const val BROKEN_JSON = "{ deliberately invalid animation JSON"
        private const val READ_FAILURE = "动画计划检查暂时无法完成，请重新读取项目。"
    }
}
