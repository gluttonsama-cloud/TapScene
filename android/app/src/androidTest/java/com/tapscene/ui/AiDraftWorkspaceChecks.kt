package com.tapscene.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.AiDraftImportPreview
import com.tapscene.data.AiDraftImportResult
import com.tapscene.data.DraftAiConfig
import com.tapscene.data.DraftAiEffect
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.ProjectNextAction
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep
import com.tapscene.data.ProjectSummary
import com.tapscene.data.ReleaseSummary
import com.tapscene.data.SafeImageBinding
import com.tapscene.data.StepAsset
import com.tapscene.data.StepOrigin
import com.tapscene.media.OpaqueMask
import com.tapscene.packageformat.AiDraftImportPolicy
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerScene
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Production coordinator with durable-receipt fakes. Does not claim picker/touch/process-kill coverage. */
object AiDraftWorkspaceChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val saving = DraftAiPlanUiState(busy = true)
        check(!saving.canClose)
        val recorded = saving.copy(closeReady = true)
        check(!recorded.canClose)
        check(recorded.copy(busy = false).canClose)
        check(!recorded.copy(busy = false, closeReady = false).canClose)
        status("PASS draft AI close signal waits for busy to clear after the saved receipt; split state emissions cannot lose the close transition")
        val root = File(context.noBackupFilesDir, "ai-draft-workspace-${UUID.randomUUID()}")
        check(root.mkdir())
        val app = object : Application() {
            init { attachBaseContext(context.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val owners = mutableListOf<ViewModelStore>()
        suspend fun workspace(access: Fake, handle: SavedStateHandle = SavedStateHandle()): AiDraftWorkspace = withContext(Dispatchers.Main.immediate) {
            val owner = ViewModelStore().also(owners::add)
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AiDraftWorkspace(app, handle).also { it.importAccess = access } as T
                }
            }
            ViewModelProvider(owner, factory)[AiDraftWorkspace::class.java]
        }
        try {
            val fake = Fake()
            val handle = SavedStateHandle()
            val model = workspace(fake, handle)
            // Construction and a cancelled system picker never prepare or create a project.
            check(fake.prepareCalls == 0 && !model.importState.value.canCommit)
            fake.prepareGate = CompletableDeferred()
            withContext(Dispatchers.Main.immediate) {
                model.startImport { ByteArrayInputStream(byteArrayOf(1)) }
                model.startImport { error("Duplicate reader must never open") }
                model.confirmImport()
            }
            fake.prepareEntered.await()
            check(fake.prepareCalls == 1 && fake.commitCalls == 0)
            fake.prepareGate!!.complete(Unit)
            idle(model)
            check(model.importState.value.canCommit && handle.get<String>(AiDraftWorkspace.SESSION) == fake.id)
            // The explicit baseline is local-only; imported library copies are never selectable.
            withContext(Dispatchers.Main.immediate) { model.selectBaseline(id("external-baseline")) }
            check(fake.previewCalls == 0)
            withContext(Dispatchers.Main.immediate) { model.selectBaseline(id("local-baseline")); model.confirmImport() }
            idle(model)
            check(fake.previewCalls == 1 && fake.commitCalls == 0)
            check(model.importState.value.preview?.previewDigest == "b".repeat(64))

            // Cancellation exactly after durable commit must read the receipt, not roll it back.
            fake.suspendAfterCommit = true
            withContext(Dispatchers.Main.immediate) { model.confirmImport(); model.confirmImport() }
            fake.commitEntered.await()
            withContext(Dispatchers.Main.immediate) { model.cancelImport() }
            idle(model)
            check(fake.commitCalls == 1 && fake.cancelCalls == 0 && fake.resultCalls > 0)
            check(model.importState.value.result?.status == "committed" && model.importState.value.result?.projectExists == true)
            check(!model.importState.value.canCommit)
            withContext(Dispatchers.Main.immediate) { model.confirmImport() }
            check(fake.commitCalls == 1)
            status("PASS AI import coordinator: cancelled picker creates nothing; duplicate preparation/commit blocked; explicit local baseline updates preview digest; cancellation at durable commit reads receipt and never creates twice")

            // A receipt read failure freezes commit/new input/cancel cleanup until explicit reread.
            val unknown = Fake().apply { suspendAfterCommit = true }
            val unknownModel = workspace(unknown)
            withContext(Dispatchers.Main.immediate) { unknownModel.startImport { ByteArrayInputStream(byteArrayOf(1)) } }
            idle(unknownModel)
            withContext(Dispatchers.Main.immediate) { unknownModel.confirmImport() }
            unknown.commitEntered.await()
            unknown.failResult = true
            withContext(Dispatchers.Main.immediate) { unknownModel.cancelImport() }
            idle(unknownModel)
            check(unknownModel.importState.value.outcomeUnknown && !unknownModel.importState.value.canCommit)
            withContext(Dispatchers.Main.immediate) {
                unknownModel.confirmImport(); unknownModel.clearCompletedImport()
                unknownModel.startImport { error("Unknown result must not open another file") }
            }
            check(unknown.commitCalls == 1 && unknown.prepareCalls == 1)
            unknown.failResult = false
            withContext(Dispatchers.Main.immediate) { unknownModel.readImportResult() }
            idle(unknownModel)
            check(!unknownModel.importState.value.outcomeUnknown && unknownModel.importState.value.result?.status == "committed")
            check(unknown.commitCalls == 1)
            unknown.receipt = unknown.receipt.copy(projectExists = false)
            withContext(Dispatchers.Main.immediate) { unknownModel.readImportResult(); unknownModel.confirmImport() }
            idle(unknownModel)
            check(unknownModel.importState.value.result?.projectExists == false && !unknownModel.importState.value.canCommit)
            status("PASS AI import unknown outcome: repeat commit and file replacement freeze; explicit reread resolves success; deleted imported project is reported without resurrection")

            val recover = Fake()
            val ready = workspace(recover)
            withContext(Dispatchers.Main.immediate) { ready.startImport { ByteArrayInputStream(byteArrayOf(1)) } }
            idle(ready)
            val restored = workspace(recover, SavedStateHandle(mapOf(AiDraftWorkspace.SESSION to recover.id)))
            recover.failResult = true
            withContext(Dispatchers.Main.immediate) { restored.openImport() }
            idle(restored)
            check(restored.importState.value.outcomeUnknown && !restored.importState.value.canCommit)
            recover.failResult = false
            withContext(Dispatchers.Main.immediate) { restored.readImportResult() }
            idle(restored)
            check(restored.importState.value.preview?.sessionId == recover.id && recover.prepareCalls == 1 && recover.commitCalls == 0)
            withContext(Dispatchers.Main.immediate) { restored.cancelImport(); restored.confirmImport() }
            idle(restored)
            check(restored.importState.value.result?.status == "cancelled" && recover.cancelCalls == 1 && recover.commitCalls == 0)
            check(restored.importState.value.preview == null && !restored.importState.value.canCommit)

            // A return-boundary interruption can leave only pending() as recovery evidence.
            val boundary = Fake().apply { interruptPrepareReturn = true }
            val boundaryModel = workspace(boundary)
            withContext(Dispatchers.Main.immediate) { boundaryModel.startImport { ByteArrayInputStream(byteArrayOf(1)) } }
            idle(boundaryModel)
            check(boundaryModel.importState.value.sessionId == null && boundaryModel.importState.value.pendingSessionIds == listOf(boundary.id))
            withContext(Dispatchers.Main.immediate) { boundaryModel.resumeImport(boundary.id) }
            idle(boundaryModel)
            check(boundaryModel.importState.value.canCommit && boundary.prepareCalls == 1)
            status("PASS AI import recovery: SavedStateHandle restores ready session; pending-only return interruption resumes without rereading external file; cancellation of ready session creates no project")

            editChecks(status)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { owners.forEach { it.clear() } }
            check(root.deleteRecursively())
        }
    }

    private fun editChecks(status: (String) -> Unit) {
        val project = project()
        val visits = listOf(RenderPlan.Visit(id("v1"), id("s1"), id("next"), 90), RenderPlan.Visit(id("v2"), id("s2"), null, 90))
        fun annotation(key: String, visit: String, start: Int) = DraftAiEffect(id(key), RenderPlan.Effect("annotation", visit, start, 30,
            null, null, key, ViewerScene.Rect(.1, .1, .8, .1)))
        val effects = listOf(annotation("a1", visits[0].visitId, 0), annotation("a2", visits[0].visitId, 30),
            annotation("a3", visits[1].visitId, 0), annotation("orphan", id("missing-visit"), 0))
        val config = DraftAiConfig(1, true, 1080, 1920, visits, effects)
        val shorter = DraftAiEdits.hold(config, visits[0].visitId, 10)
        check(shorter.effects == effects && shorter.visits[0].holdFrames == 10 && shorter.visits[0].visitId == visits[0].visitId)
        check(runCatching { DraftAiEdits.hold(config, id("missing"), 50) }.isFailure)
        check(runCatching { DraftAiEdits.hold(config, visits[0].visitId, 0) }.isFailure)
        val changed = DraftAiEdits.effect(config, effects[1].id, RenderPlan.Effect("annotation", visits[0].visitId, 4, 50,
            null, null, "new", ViewerScene.Rect(.2, .2, .6, .2)))
        check(changed.effects[0] == effects[0] && changed.effects[1].id == effects[1].id && changed.effects[1].value.text == "new" && changed.effects[2] == effects[2])
        val removed = DraftAiEdits.removeEffect(config, effects[0].id)
        check(removed.effects == effects.drop(1))
        val unchanged = DraftAiEdits.selectEdge(config, project, visits[0].visitId, id("next"))
        check(unchanged.after === config && unchanged.removedVisits.isEmpty() && unchanged.removedEffects.isEmpty())
        check(unchanged.after.visits === config.visits && unchanged.after.effects === config.effects)
        val replacement = DraftAiEdits.selectEdge(config, project, visits[0].visitId, null)
        check(replacement.before === config && config.visits.size == 2 && config.effects.size == 4)
        check(replacement.removedVisits.map { it.visitId } == listOf(visits[1].visitId))
        check(replacement.removedEffects == listOf(effects[2]))
        check(replacement.after.effects == listOf(effects[0], effects[1], effects[3])) // Broken references are preserved, never swept.
        check(replacement.after.visits[0].visitId == visits[0].visitId)
        check(runCatching { DraftAiEdits.selectEdge(config, project.copy(steps = project.steps.drop(1)), visits[0].visitId, id("next")) }.isFailure)
        val rebound = config.copy(boundRevision = 7, needsRepair = false)
        check(AiDraftWorkspace.samePlan(config, rebound))
        check(!AiDraftWorkspace.samePlan(config, changed))
        check(!AiDraftWorkspace.samePlan(config, removed))
        status("PASS AI draft edits: shortening hold preserves every effect; stable-ID edit/delete affects only one duplicate-kind effect; path loss is described before application; orphan references survive; stale visit/edge safely rejected; exact save comparison includes all parameters")
    }

    private suspend fun idle(model: AiDraftWorkspace) = withTimeout(5_000) { model.importState.first { !it.busy } }
    private fun id(value: String): String = UUID.nameUUIDFromBytes("ai-workspace-$value".toByteArray()).toString()
    private fun project(): ProjectSnapshot {
        val projectId = id("project")
        val steps = (1..2).map { index ->
            val stepId = id("s$index")
            val asset = StepAsset(id("image$index"), "test/image$index.png", "0".repeat(64), 100, 32, 48)
            ProjectStep(stepId, "Step $index", "description", index - 1, index == 2, asset,
                StepOrigin.Image(SafeImageBinding(projectId, stepId, 1, asset.id, asset.sha256, 32, 48)), emptyList(), emptyList(), id("capture$index"),
                nextAction = if (index == 1) ProjectNextAction(id("next"), "Next", id("s2")) else null, evidenceKind = "imported")
        }
        return ProjectSnapshot(ProjectSummary(projectId, "Fixture", "", 1, 1, 2, steps.first().id), steps)
    }
    private class Fake : AiDraftImportAccess {
        val id = id("session-${UUID.randomUUID()}")
        private val images = (1..2).map { index -> ViewerScene.Asset(id("asset$index"), "assets/${id("asset$index")}.png", "image/png", 100L, "0".repeat(64), 32, 48) }
        private val scene = ViewerScene(id("release"), "Fixture", "", 1, id("s1"),
            images.mapIndexed { index, image -> ViewerScene.State(id("s${index + 1}"), image.id, 32, 48, "Step ${index + 1}", "", "imported", index == 1) },
            listOf(ViewerScene.Edge(id("next"), id("s1"), id("s2"), null, null, "Next", "continue", "authored")), emptyList(), images)
        private val plan = RenderPlan.build(scene, 1080, 1920, listOf(RenderPlan.Visit(id("v1"), id("s1"), id("next"), 90), RenderPlan.Visit(id("v2"), id("s2"), null, 90)), emptyList())
        private var prepared = AiDraftImportPreview(id, "a".repeat(64), id("new-project"), scene, plan, null, null, emptyList(), emptyList(), "Full fixture", AiDraftImportPolicy.TRUST_NOTICE, 200)
        var receipt = AiDraftImportResult(id, "preparing", null, false)
        var prepareCalls = 0
        var previewCalls = 0
        var commitCalls = 0
        var cancelCalls = 0
        var resultCalls = 0
        val prepareEntered = CompletableDeferred<Unit>()
        val commitEntered = CompletableDeferred<Unit>()
        var prepareGate: CompletableDeferred<Unit>? = null
        var suspendAfterCommit = false
        var failResult = false
        var interruptPrepareReturn = false
        override suspend fun prepare(input: InputStream): AiDraftImportPreview {
            prepareCalls++; prepareEntered.complete(Unit); prepareGate?.await()
            receipt = receipt.copy(status = "ready")
            if (interruptPrepareReturn) throw CancellationException("Prepared return interrupted")
            return prepared
        }
        override suspend fun preview(sessionId: String, baselineReleaseId: String?): AiDraftImportPreview {
            check(sessionId == id); previewCalls++
            prepared = prepared.copy(previewDigest = "b".repeat(64), baselineReleaseId = baselineReleaseId, baselineTitle = "Local baseline")
            return prepared
        }
        override suspend fun readPrepared(sessionId: String): AiDraftImportPreview? { check(sessionId == id); return prepared.takeIf { receipt.status == "ready" } }
        override suspend fun pending() = listOf(id).takeIf { receipt.status == "ready" } ?: emptyList()
        override suspend fun commit(sessionId: String, digest: String): AiDraftImportResult {
            check(sessionId == id && digest == prepared.previewDigest); commitCalls++
            receipt = receipt.copy(status = "committed", projectId = prepared.newProjectId, projectExists = true)
            commitEntered.complete(Unit)
            if (suspendAfterCommit) awaitCancellation()
            return receipt
        }
        override suspend fun cancel(sessionId: String): AiDraftImportResult {
            check(sessionId == id); cancelCalls++
            if (receipt.status != "committed") receipt = receipt.copy(status = "cancelled")
            return receipt
        }
        override suspend fun result(sessionId: String): AiDraftImportResult {
            check(sessionId == id); resultCalls++
            if (failResult) throw IOException("Read unavailable")
            return receipt
        }
        override suspend fun image(sessionId: String, assetId: String): File = error("Image not requested in coordinator test")
        override suspend fun baselines() = listOf(
            ReleaseSummary(id("local-baseline"), "Local", "0".repeat(64), 1, 2, 200, "local"),
            ReleaseSummary(id("external-baseline"), "External", "0".repeat(64), 1, 2, 200, "imported"))
    }
}
