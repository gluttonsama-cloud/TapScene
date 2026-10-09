package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tapscene.data.AiDraftImportPreview
import com.tapscene.data.AiDraftImportResult
import com.tapscene.data.AiDraftImportStore
import com.tapscene.data.DraftAiConfig
import com.tapscene.data.DraftAiEffect
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReleaseStore
import com.tapscene.data.ReleaseSummary
import com.tapscene.packageformat.RenderPlan
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Import results are receipts, never a claim that external pixels or text were reviewed. */
data class AiDraftImportUiState(
    val sessionId: String? = null,
    val preview: AiDraftImportPreview? = null,
    val pendingSessionIds: List<String> = emptyList(),
    val baselines: List<ReleaseSummary> = emptyList(),
    val result: AiDraftImportResult? = null,
    val busy: Boolean = false,
    val stage: String? = null,
    val message: String? = null,
    val outcomeUnknown: Boolean = false,
    val imageAssetId: String? = null,
    val image: Bitmap? = null,
    val imageBusy: Boolean = false,
) {
    val canCommit: Boolean get() = !busy && !outcomeUnknown && result?.status != "committed" &&
        result?.status != "cancelled" && preview != null && preview.sessionId == sessionId && preview.issues.isEmpty()
}

data class DraftAiPathChange(val before: DraftAiConfig, val after: DraftAiConfig,
    val removedVisits: List<RenderPlan.Visit>, val removedEffects: List<DraftAiEffect>)

data class DraftAiPlanUiState(
    val project: ProjectSnapshot? = null,
    val config: DraftAiConfig? = null,
    val issues: List<String> = emptyList(),
    val busy: Boolean = false,
    val checking: Boolean = false,
    val dirty: Boolean = false,
    val message: String? = null,
    val pendingPath: DraftAiPathChange? = null,
    val closeReady: Boolean = false,
    val saveOutcomeUnknown: Boolean = false,
) {
    // The UI observes this combined transition, so an early closeReady cannot be consumed
    // while the save is still busy and then lost when busy clears in the next emission.
    val canClose: Boolean get() = closeReady && !busy
}

/** Pure edits make every loss explicit. A shorter hold retains all effects, including stale ones. */
object DraftAiEdits {
    fun hold(config: DraftAiConfig, visitId: String, frames: Int): DraftAiConfig {
        require(frames in 1..RenderPlan.MAX_HOLD_FRAMES) { "每次停留须为 1–1800 帧。" }
        require(config.visits.any { it.visitId == visitId }) { "此访问已不存在，请重新读取计划。" }
        return config.copy(visits = config.visits.map {
            if (it.visitId == visitId) RenderPlan.Visit(it.visitId, it.stateId, it.selectedEdgeId, frames) else it
        })
    }
    fun effect(config: DraftAiConfig, id: String, value: RenderPlan.Effect): DraftAiConfig {
        require(config.effects.any { it.id == id }) { "此效果已不存在，请重新读取计划。" }
        return config.copy(effects = config.effects.map { if (it.id == id) it.copy(value = value) else it })
    }
    fun removeEffect(config: DraftAiConfig, id: String): DraftAiConfig =
        config.copy(effects = config.effects.filterNot { it.id == id })

    fun selectEdge(config: DraftAiConfig, snapshot: ProjectSnapshot, visitId: String, edgeId: String?): DraftAiPathChange {
        val index = config.visits.indexOfFirst { it.visitId == visitId }
        require(index >= 0) { "此访问已不存在，请重新读取计划。" }
        val visit = config.visits[index]
        val step = snapshot.steps.firstOrNull { it.id == visit.stateId }
            ?: error("此访问的步骤已删除，请从前面的有效访问重选路径，或从起点重选。")
        val hotspot = step.hotspots.firstOrNull { it.edgeId == edgeId }
        val next = step.nextAction?.takeIf { it.id == edgeId }
        require(edgeId == null || hotspot != null || next != null) { "动作不属于当前步骤。" }
        if (next != null) require(next.targetStepId != null) { "此下一步动作缺少目标，请先在步骤中修复。" }
        val target = hotspot?.targetStepId ?: next?.targetStepId
        require(target == null || snapshot.steps.any { it.id == target }) { "动作目标已删除，请先修复动作。" }
        if (visit.selectedEdgeId == edgeId && suffixMatches(config, snapshot, index))
            return DraftAiPathChange(config, config, emptyList(), emptyList())
        val retained = config.visits.take(index) + RenderPlan.Visit(visit.visitId, visit.stateId, edgeId, visit.holdFrames)
        val visits = if (target == null) retained else retained + RenderPlan.Visit(UUID.randomUUID().toString(), target, null, 90)
        require(visits.size <= RenderPlan.MAX_VISITS) { "最多 256 次访问。" }
        return replacement(config, visits)
    }
    fun restart(config: DraftAiConfig, snapshot: ProjectSnapshot): DraftAiPathChange {
        val start = snapshot.project.startStepId?.takeIf { id -> snapshot.steps.any { it.id == id } }
            ?: error("请先给项目设置有效起点。")
        return replacement(config, listOf(RenderPlan.Visit(UUID.randomUUID().toString(), start, null, 90)))
    }
    private fun suffixMatches(config: DraftAiConfig, snapshot: ProjectSnapshot, start: Int): Boolean {
        for (index in start..config.visits.lastIndex) {
            val visit = config.visits[index]
            val step = snapshot.steps.firstOrNull { it.id == visit.stateId } ?: return false
            val edgeId = visit.selectedEdgeId
            if (edgeId == null) return index == config.visits.lastIndex // A pending last visit is preserved too.
            val hotspot = step.hotspots.firstOrNull { it.edgeId == edgeId }
            val next = step.nextAction?.takeIf { it.id == edgeId }
            if (hotspot == null && next == null) return false
            if (next != null && next.targetStepId == null) return false
            val target = hotspot?.targetStepId ?: next?.targetStepId
            if (target == null) return index == config.visits.lastIndex
            if (config.visits.getOrNull(index + 1)?.stateId != target) return false
        }
        return false
    }
    private fun replacement(config: DraftAiConfig, visits: List<RenderPlan.Visit>): DraftAiPathChange {
        val keptIds = visits.map { it.visitId }.toSet()
        val removedVisits = config.visits.filterNot { it.visitId in keptIds }
        // Also preserve previously broken references. Only effects of explicitly removed visits go.
        val removedIds = removedVisits.map { it.visitId }.toSet()
        val removedEffects = config.effects.filter { it.value.visitId in removedIds }
        return DraftAiPathChange(config, config.copy(visits = visits,
            effects = config.effects.filterNot { it.value.visitId in removedIds }), removedVisits, removedEffects)
    }
}

/** The narrow port allows interruption/replay tests to exercise the same production coordinator. */
internal interface AiDraftImportAccess {
    suspend fun prepare(input: InputStream): AiDraftImportPreview
    suspend fun preview(sessionId: String, baselineReleaseId: String?): AiDraftImportPreview
    suspend fun readPrepared(sessionId: String): AiDraftImportPreview?
    suspend fun pending(): List<String>
    suspend fun commit(sessionId: String, digest: String): AiDraftImportResult
    suspend fun cancel(sessionId: String): AiDraftImportResult
    suspend fun result(sessionId: String): AiDraftImportResult
    suspend fun image(sessionId: String, assetId: String): File
    suspend fun baselines(): List<ReleaseSummary>
}

/** Owns one durable import session. Unknown commit outcomes freeze retries until the receipt is read. */
class AiDraftWorkspace(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application
    private val projectStore = ProjectStore(app)
    internal var importAccess: AiDraftImportAccess = object : AiDraftImportAccess {
        private val store = AiDraftImportStore(app)
        private val releases = ReleaseStore(app)
        override suspend fun prepare(input: InputStream) = store.prepare(input)
        override suspend fun preview(sessionId: String, baselineReleaseId: String?) = store.preview(sessionId, baselineReleaseId)
        override suspend fun readPrepared(sessionId: String) = store.readPrepared(sessionId)
        override suspend fun pending() = store.pending()
        override suspend fun commit(sessionId: String, digest: String) = store.commit(sessionId, digest)
        override suspend fun cancel(sessionId: String) = store.cancel(sessionId)
        override suspend fun result(sessionId: String) = store.readResult(sessionId)
        override suspend fun image(sessionId: String, assetId: String) = store.imageFile(sessionId, assetId)
        override suspend fun baselines() = releases.listReleases().filter { it.origin == "local" }
    }
    private val mutableImport = MutableStateFlow(AiDraftImportUiState(sessionId = savedState[SESSION]))
    val importState = mutableImport.asStateFlow()
    private val mutablePlan = MutableStateFlow(DraftAiPlanUiState())
    val planState = mutablePlan.asStateFlow()
    private var importTask: Job? = null
    private var imageTask: Job? = null
    private var imageGeneration = 0L
    private var cancelRequested = false
    private var planTask: Job? = null
    private var checkTask: Job? = null
    private var editGeneration = 0L

    fun openImport() = importOperation("恢复导入记录") {
        val id = importState.value.sessionId
        if (id != null) reconcile(id, loadPreview = true)
        refreshImportLists()
    }

    fun importPackage(uri: Uri) = startImport {
        app.contentResolver.openInputStream(uri) ?: error("无法读取选定文件。")
    }

    internal fun startImport(read: () -> InputStream) {
        val current = importState.value
        if (current.busy || current.outcomeUnknown || current.preview != null ||
            (current.sessionId != null && current.result?.status !in setOf("committed", "cancelled", "failed"))) return
        clearImage()
        importOperation("隔离检查完整 AI 包") {
            mutableImport.update { it.copy(sessionId = null, preview = null, result = null, message = null) }
            savedState[SESSION] = null
            val preview = withContext(Dispatchers.IO) {
                read().use { input ->
                    importAccess.prepare(input).also { prepared ->
                        // Remember before crossing the cancellable dispatcher return boundary.
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            savedState[SESSION] = prepared.sessionId
                            mutableImport.update { it.copy(sessionId = prepared.sessionId) }
                        }
                    }
                }
            }
            mutableImport.update { it.copy(preview = preview, result = null) }
            refreshImportLists()
        }
    }

    fun resumeImport(id: String) {
        if (importState.value.busy || importState.value.outcomeUnknown) return
        clearImage()
        savedState[SESSION] = id
        mutableImport.update { it.copy(sessionId = id, preview = null, result = null, message = null) }
        importOperation("读取未完成导入") { reconcile(id, loadPreview = true); refreshImportLists() }
    }

    fun selectBaseline(releaseId: String?) {
        val current = importState.value
        val id = current.sessionId ?: return
        if (current.busy || current.outcomeUnknown || current.preview == null || current.result?.status == "committed") return
        if (releaseId != null && current.baselines.none { it.id == releaseId && it.origin == "local" }) return
        importOperation("与所选本机版本比较") {
            val preview = withContext(Dispatchers.IO) { importAccess.preview(id, releaseId) }
            mutableImport.update { it.copy(preview = preview) }
        }
    }

    fun confirmImport() {
        val current = importState.value
        if (!current.canCommit) return
        val preview = current.preview ?: return
        importOperation("建立独立新草稿", mayCommit = true) {
            val result = withContext(Dispatchers.IO) { importAccess.commit(preview.sessionId, preview.previewDigest) }
            acceptResult(result)
        }
    }

    fun readImportResult() {
        val id = importState.value.sessionId ?: return
        importOperation("核对实际导入结果") { reconcile(id, loadPreview = true); refreshImportLists() }
    }

    /** Cancel is resolved after the running operation joins its transaction boundary. */
    fun cancelImport() {
        if (importState.value.result?.status == "committed") return
        cancelRequested = true
        if (importState.value.busy) { importTask?.cancel(); return }
        importOperation("取消本次导入") { }
    }

    fun clearCompletedImport() {
        val current = importState.value
        if (current.busy || current.outcomeUnknown || current.result?.status !in setOf("committed", "cancelled", "failed")) return
        savedState[SESSION] = null
        clearImage()
        mutableImport.value = AiDraftImportUiState(baselines = current.baselines, pendingSessionIds = current.pendingSessionIds)
    }

    fun importMessage(message: String) { mutableImport.update { it.copy(message = message) } }

    fun showImportImage(assetId: String) {
        val current = importState.value
        val preview = current.preview ?: return
        if (current.busy || preview.scene.assets.none { it.id == assetId && it.mime == "image/png" }) return
        imageTask?.cancel()
        val generation = ++imageGeneration
        mutableImport.update { it.copy(imageAssetId = assetId, image = null, imageBusy = true) }
        imageTask = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val file = importAccess.image(preview.sessionId, assetId)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法读取。" }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                        inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
                    }) ?: error("图片无法读取。")
                }
                coroutineContext.ensureActive()
                if (generation == imageGeneration) mutableImport.update { it.copy(image = bitmap) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == imageGeneration) importMessage("实际安全图片暂时无法读取，请重试。") }
            finally { if (generation == imageGeneration) mutableImport.update { it.copy(imageBusy = false) } }
        }
    }
    fun clearImage() {
        imageGeneration++; imageTask?.cancel(); imageTask = null
        mutableImport.update { it.copy(imageAssetId = null, image = null, imageBusy = false) }
    }

    private suspend fun refreshImportLists() {
        val pending = withContext(Dispatchers.IO) { importAccess.pending() }
        val baselines = withContext(Dispatchers.IO) { importAccess.baselines() }
        mutableImport.update { it.copy(pendingSessionIds = pending, baselines = baselines.filter { item -> item.origin == "local" }) }
    }
    private suspend fun reconcile(id: String, loadPreview: Boolean) {
        try {
            val result = withContext(Dispatchers.IO) { importAccess.result(id) }
            acceptResult(result)
            if (loadPreview && result.status == "ready") {
                val preview = withContext(Dispatchers.IO) { importAccess.readPrepared(id) }
                check(preview != null) { "隔离副本已不可用，请取消本次导入后重新选择完整包。" }
                mutableImport.update { it.copy(preview = preview) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableImport.update { it.copy(outcomeUnknown = true,
                message = "暂时无法读取本次导入记录或隔离副本。请重读结果；也可尝试取消，取消前会核对实际提交状态。") }
        }
    }
    private fun acceptResult(result: AiDraftImportResult) {
        if (result.status in setOf("committed", "cancelled", "failed")) clearImage()
        mutableImport.update { it.copy(result = result, outcomeUnknown = false,
            preview = it.preview.takeUnless { _ -> result.status in setOf("cancelled", "failed") },
            message = when (result.status) {
                "committed" -> if (result.projectExists) "已导入待复核。请在新项目中检查并重新复核全部内容。" else "这次导入曾成功，但新项目已被删除；不会重复创建。"
                "cancelled" -> "已取消导入，未创建新项目。"
                "failed" -> "本次导入没有完成，请重新选择完整包。"
                else -> it.message
            }) }
    }
    private fun importOperation(label: String, mayCommit: Boolean = false, block: suspend () -> Unit) {
        if (importState.value.busy) return
        mutableImport.update { it.copy(busy = true, stage = label, message = null) }
        importTask = viewModelScope.launch {
            try { block() }
            catch (_: CancellationException) { /* The receipt below decides what cancellation means. */ }
            catch (error: Exception) { importMessage(safeError(error, "$label 未完成，请重试。")) }
            finally {
                withContext(NonCancellable) {
                    val id = importState.value.sessionId
                    if (id != null && (mayCommit || cancelRequested)) {
                        try {
                            // Always read first: cancel at commit must not erase or recreate a project.
                            val actual = withContext(Dispatchers.IO) { importAccess.result(id) }
                            acceptResult(actual)
                            if (cancelRequested && actual.status != "committed") {
                                withContext(Dispatchers.IO) { importAccess.cancel(id) }
                                acceptResult(withContext(Dispatchers.IO) { importAccess.result(id) })
                            }
                        } catch (_: Exception) {
                            mutableImport.update { it.copy(outcomeUnknown = true,
                                message = "暂时无法确认本次导入结果。请点“重读结果”；确认前不能重复创建或重新选择。") }
                        }
                    }
                    // prepare may have persisted a session before cancellation returned its ID.
                    runCatching { refreshImportLists() }
                    if (id == null && cancelRequested) importMessage("已停止读取。若已有隔离副本，可从未完成导入继续核对或取消。")
                    cancelRequested = false
                    mutableImport.update { it.copy(busy = false, stage = null) }
                }
            }
        }
    }

    fun openPlan(projectId: String) {
        if (planState.value.busy) return
        checkTask?.cancel(); editGeneration++
        mutablePlan.value = DraftAiPlanUiState(busy = true)
        planTask = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val snapshot = projectStore.readProject(projectId) ?: error("项目已不存在。")
                    val saved = projectStore.readDraftAiConfig(projectId)
                    val start = snapshot.project.startStepId ?: snapshot.steps.firstOrNull()?.id
                    val config = saved ?: start?.let { DraftAiConfig(snapshot.project.revision, true, 1080, 1920,
                        listOf(RenderPlan.Visit(UUID.randomUUID().toString(), it, null, 90)), emptyList()) }
                    Triple(snapshot, config, config?.let { projectStore.draftAiIssues(projectId, it) } ?: listOf("先加入步骤并设置起点，再编辑动画计划。")) to (saved == null && config != null)
                }
                mutablePlan.value = DraftAiPlanUiState(loaded.first.first, loaded.first.second, loaded.first.third, dirty = loaded.second)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutablePlan.update { it.copy(message = safeError(error, "动画计划暂时无法读取。")) } }
            finally { mutablePlan.update { it.copy(busy = false) } }
        }
    }

    fun setCanvas(landscape: Boolean) = editPlan { it.copy(width = if (landscape) 1920 else 1080, height = if (landscape) 1080 else 1920) }
    fun setHold(visitId: String, frames: Int) = editPlan { DraftAiEdits.hold(it, visitId, frames) }
    fun changeEffect(id: String, value: RenderPlan.Effect) = editPlan { DraftAiEdits.effect(it, id, value) }
    fun deleteEffect(id: String) = editPlan { DraftAiEdits.removeEffect(it, id) }
    fun choosePlanEdge(visitId: String, edgeId: String?) {
        val current = planState.value
        val config = current.config ?: return
        val snapshot = current.project ?: return
        if (current.busy || current.saveOutcomeUnknown) return
        try {
            val change = DraftAiEdits.selectEdge(config, snapshot, visitId, edgeId)
            mutablePlan.update { it.copy(pendingPath = change.takeUnless { path -> path.after === path.before }) }
        }
        catch (error: Exception) { planMessage(safeError(error, "无法更改路径。")) }
    }
    fun restartPlan() {
        val current = planState.value
        val config = current.config ?: return
        val snapshot = current.project ?: return
        if (current.busy || current.saveOutcomeUnknown) return
        try { mutablePlan.update { it.copy(pendingPath = DraftAiEdits.restart(config, snapshot)) } }
        catch (error: Exception) { planMessage(safeError(error, "无法重选路径。")) }
    }
    fun dismissPathChange() { mutablePlan.update { it.copy(pendingPath = null) } }
    fun confirmPathChange() {
        val pending = planState.value.pendingPath ?: return
        if (planState.value.config !== pending.before) { dismissPathChange(); return }
        editPlan { pending.after }
    }
    fun planMessage(message: String) { mutablePlan.update { it.copy(message = message) } }
    private fun editPlan(change: (DraftAiConfig) -> DraftAiConfig) {
        val current = planState.value
        if (current.busy || current.saveOutcomeUnknown) return
        val config = current.config ?: return
        try {
            val edited = change(config)
            mutablePlan.update { it.copy(config = edited, dirty = true, message = null, pendingPath = null, checking = true) }
            val projectId = current.project?.project?.id ?: return
            val generation = ++editGeneration
            checkTask?.cancel()
            checkTask = viewModelScope.launch {
                try {
                    val issues = withContext(Dispatchers.IO) { projectStore.draftAiIssues(projectId, edited) }
                    if (generation == editGeneration) mutablePlan.update { it.copy(issues = issues) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (generation == editGeneration) mutablePlan.update { it.copy(issues = listOf("校验暂时未完成；配置可保留，不能用于导出。")) } }
                finally { if (generation == editGeneration) mutablePlan.update { it.copy(checking = false) } }
            }
        } catch (error: Exception) { planMessage(safeError(error, "修改未应用，请检查输入。")) }
    }

    fun savePlan(closeAfterSave: Boolean = false) {
        val current = planState.value
        val project = current.project?.project ?: return
        val config = current.config ?: return
        if (current.busy || current.saveOutcomeUnknown) return
        checkTask?.cancel(); editGeneration++
        mutablePlan.update { it.copy(busy = true, checking = false, message = null) }
        planTask = viewModelScope.launch {
            var saved: DraftAiConfig? = null
            try {
                saved = withContext(Dispatchers.IO) { projectStore.saveDraftAiConfig(project.id, project.revision, config) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { planMessage(safeError(error, "计划未保存，输入已保留。")) }
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        val actual = projectStore.readDraftAiConfig(project.id)
                        val snapshot = projectStore.readProject(project.id)
                        val committed = actual?.takeIf { samePlan(it, config) } ?: saved
                        if (committed != null && snapshot != null) mutablePlan.update { it.copy(project = snapshot,
                            config = committed, dirty = false, issues = projectStore.draftAiIssues(project.id, committed),
                            saveOutcomeUnknown = false, closeReady = closeAfterSave,
                            message = if (committed.needsRepair) "计划已保存，仍有待修复项；原参数已保留。" else "动画计划已保存；交付仍需固定版本并完成复核。") }
                    } catch (_: Exception) { mutablePlan.update { it.copy(saveOutcomeUnknown = true,
                        message = "暂时无法确认保存结果，输入已保留。请重新读取结果后继续。") } }
                }
                mutablePlan.update { it.copy(busy = false) }
            }
        }
    }

    fun reconcilePlan() {
        val current = planState.value
        val projectId = current.project?.project?.id ?: return
        val expected = current.config ?: return
        if (current.busy) return
        mutablePlan.update { it.copy(busy = true) }
        planTask = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { projectStore.readProject(projectId) } ?: error("项目已不存在。")
                val saved = withContext(Dispatchers.IO) { projectStore.readDraftAiConfig(projectId) }
                val committed = saved != null && samePlan(saved, expected)
                val config = if (committed) requireNotNull(saved) else expected
                val issues = withContext(Dispatchers.IO) { projectStore.draftAiIssues(projectId, config) }
                mutablePlan.update { it.copy(project = snapshot, config = config, issues = issues, dirty = !committed,
                    saveOutcomeUnknown = false, message = if (committed) "已核对：计划已保存。" else "已核对：当前输入尚未保存，请重新保存。") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { planMessage("仍无法读取保存结果，请稍后重试。") }
            finally { mutablePlan.update { it.copy(busy = false) } }
        }
    }

    fun closePlan(): Boolean {
        if (planState.value.busy) return false
        checkTask?.cancel(); editGeneration++
        mutablePlan.value = DraftAiPlanUiState()
        return true
    }
    companion object {
        internal const val SESSION = "aiDraftImportSession"
        internal fun samePlan(a: DraftAiConfig, b: DraftAiConfig): Boolean = a.width == b.width && a.height == b.height &&
            a.visits.map { listOf(it.visitId, it.stateId, it.selectedEdgeId, it.holdFrames) } ==
            b.visits.map { listOf(it.visitId, it.stateId, it.selectedEdgeId, it.holdFrames) } &&
            a.effects.map { listOf(it.id, it.value.type, it.value.visitId, it.value.startFrame, it.value.durationFrames,
                it.value.hotspotId, it.value.regionId, it.value.text, it.value.rect?.let { r -> listOf(r.x, r.y, r.width, r.height) }) } ==
            b.effects.map { listOf(it.id, it.value.type, it.value.visitId, it.value.startFrame, it.value.durationFrames,
                it.value.hotspotId, it.value.regionId, it.value.text, it.value.rect?.let { r -> listOf(r.x, r.y, r.width, r.height) }) }
        private fun safeError(error: Exception, fallback: String): String =
            if (error is IllegalArgumentException || error is IllegalStateException) error.message?.takeIf {
                it.length <= 300 && '/' !in it && '\\' !in it
            } ?: fallback else fallback
    }
}
