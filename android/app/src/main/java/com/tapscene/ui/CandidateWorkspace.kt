package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.data.CandidateOcrRun
import com.tapscene.data.CandidateOcrSnapshot
import com.tapscene.data.CandidateOcrStatus
import com.tapscene.data.CandidateOcrStore
import com.tapscene.data.CandidateRun
import com.tapscene.data.FrameCandidateStore
import com.tapscene.data.ProjectStore
import com.tapscene.media.CandidateAnalysisProgress
import com.tapscene.media.CandidateAnalysisStatus
import com.tapscene.media.CandidateDecision
import com.tapscene.media.DecodedFrame
import com.tapscene.media.FrameCandidate
import com.tapscene.media.FrameCandidateAnalyzer
import com.tapscene.media.FrameCandidateSnapshot
import com.tapscene.media.ImportedSource
import com.tapscene.media.VideoFrameDecoder
import com.tapscene.ocr.OfflineOcrEngine
import com.tapscene.ocr.OcrCancellation
import com.tapscene.ocr.OcrResult
import com.tapscene.ocr.OcrException
import com.tapscene.ocr.OcrError
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CandidateWorkspaceState(
    val projectId: String? = null,
    val sourceId: String? = null,
    val sourceSha256: String? = null,
    val candidates: List<FrameCandidate> = emptyList(),
    val status: CandidateAnalysisStatus = CandidateAnalysisStatus.NOT_STARTED,
    val completedSamples: Int = 0,
    val totalSamples: Int = 0,
    val uniqueFrames: Int = 0,
    val ocrResults: Map<String, OcrResult> = emptyMap(),
    val ocrStatus: CandidateOcrStatus = CandidateOcrStatus.NOT_STARTED,
    val ocrCompleted: Int = 0,
    val ocrTotal: Int = 0,
    val ocrMessage: String? = null,
    val ocrFailedCandidateId: String? = null,
    val busy: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
)

/** No candidate is a reviewed PNG. Only the existing media review flow can add project steps. */
class CandidateWorkspace(application: Application) : AndroidViewModel(application) {
    private val store = FrameCandidateStore(application)
    private val projects = ProjectStore(application)
    private val ocrStore = CandidateOcrStore(application)
    private val ocrEngine = OfflineOcrEngine(application)
    private val privateRoot = application.noBackupFilesDir.canonicalFile
    private val analyzer = FrameCandidateAnalyzer(application)
    private val decoder = VideoFrameDecoder(application)
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(CandidateWorkspaceState())
    val state = mutableState.asStateFlow()
    private var source: ImportedSource? = null
    private var generation = 0L
    private var task: Job? = null
    private var cancellableTask: Job? = null
    private var ocrCancellation: OcrCancellation? = null

    init {
        viewModelScope.launch {
            // StateFlow carries the complete monotonic fence set, so conflated or late
            // notifications cannot lose a source/project deletion from another instance.
            ocrStore.invalidations.collect { clearInvalidatedOcr() }
        }
    }

    /** Switching scopes cancels the old job, then waits for its owned checkpoint cleanup. */
    fun activate(projectId: String?, source: ImportedSource?) {
        require((projectId == null) == (source == null)) { "请选择项目和素材后整理候选。" }
        if (state.value.projectId == projectId && this.source == source) return
        val previousTask = task
        ocrCancellation?.cancel()
        previousTask?.cancel()
        val request = ++generation
        this.source = source
        mutableState.value = CandidateWorkspaceState(projectId = projectId, sourceId = source?.sourceId,
            sourceSha256 = source?.metadata?.sha256?.lowercase(), loading = projectId != null)
        task = viewModelScope.launch {
            previousTask?.join()
            if (projectId == null || source == null) return@launch
            operation.withLock {
                try {
                    val (saved, text) = withContext(Dispatchers.IO) { readWorkspace(projectId, source) }
                    if (request == generation) {
                        apply(saved)
                        applyOcr(text, replace = true)
                        if (saved.status == CandidateAnalysisStatus.INTERRUPTED) message("上次整理中断，已保存的候选和选择已恢复；可重试。")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (request == generation) message("候选记录暂时无法读取，已保留本机数据；可重试。") }
                finally { if (request == generation) mutableState.update { it.copy(loading = false) } }
            }
        }
    }

    fun analyze() {
        val source = source ?: return
        val projectId = state.value.projectId ?: return
        if (state.value.busy || state.value.loading || clearInvalidatedOcr()) return
        val request = generation
        mutableState.update { it.copy(busy = true, message = null) }
        task = viewModelScope.launch {
            operation.withLock {
                var run: CandidateRun? = null
                var latest: CandidateAnalysisProgress? = null
                var persistedSamples = 0
                var persistedCandidateCount = 0
                var ownsWork = false
                var ownsPixels = false
                try {
                    if (!workGate.tryLock()) {
                        if (request == generation) message("另一个候选整理或文字识别任务仍在运行，请稍后重试。")
                        return@withLock
                    }
                    ownsWork = true
                    pixelsGate.lock()
                    ownsPixels = true
                    currentCoroutineContext().ensureActive()
                    val total = FrameCandidateAnalyzer.sampleTimes(source.metadata.durationUs).size
                    withContext(Dispatchers.IO) {
                        readReconciled(projectId, source)
                        currentCoroutineContext().ensureActive()
                        // Assign inside this dispatcher so prompt cancellation cannot lose the lease.
                        run = store.begin(projectId, source, total)
                    }
                    mutableState.update { it.copy(status = CandidateAnalysisStatus.RUNNING,
                        completedSamples = 0, totalSamples = total, uniqueFrames = 0) }
                    val result = analyzer.analyze(source) { progress ->
                        latest = progress
                        val checkpoint = progress.completedSamples - persistedSamples >= 8 ||
                            progress.candidates.size != persistedCandidateCount || progress.completedSamples == total
                        if (checkpoint) {
                            val saved = withContext(Dispatchers.IO) { store.checkpoint(checkNotNull(run), progress) }
                            persistedSamples = progress.completedSamples
                            persistedCandidateCount = progress.candidates.size
                            if (request == generation) apply(saved)
                        } else if (request == generation) mutableState.update { it.copy(
                            completedSamples = progress.completedSamples, totalSamples = progress.totalSamples,
                            uniqueFrames = progress.uniqueFrames) }
                    }
                    currentCoroutineContext().ensureActive()
                    val saved = withContext(NonCancellable + Dispatchers.IO) {
                        store.finish(checkNotNull(run), CandidateAnalysisStatus.COMPLETED, result).also { run = null }
                    }
                    if (request == generation) {
                        apply(saved)
                        message("已整理 ${saved.candidates.size} 个候选画面。")
                    }
                } catch (_: TimeoutCancellationException) {
                    finishInterrupted(run, latest, CandidateAnalysisStatus.FAILED, request,
                        "采样超过五分钟，已保留已保存的候选和选择；可重试或手动取帧。")
                } catch (cancelled: CancellationException) {
                    finishInterrupted(run, latest, CandidateAnalysisStatus.CANCELLED, request,
                        "已取消整理，已保存的候选和选择仍保留。")
                    throw cancelled
                } catch (_: Exception) {
                    finishInterrupted(run, latest, CandidateAnalysisStatus.FAILED, request,
                        "候选整理失败，已保存的选择仍保留；可重试或手动取帧。")
                } finally {
                    run?.let(store::abandon)
                    if (ownsPixels) pixelsGate.unlock()
                    if (ownsWork) workGate.unlock()
                    if (request == generation) mutableState.update { it.copy(busy = false) }
                }
            }
        }
        cancellableTask = task
    }

    /** Explicit user action only. Existing valid frame results are reused on retry. */
    fun recognizeText(candidateIds: List<String>) {
        val source = source ?: return
        val projectId = state.value.projectId ?: return
        if (state.value.busy || state.value.loading || clearInvalidatedOcr()) return
        val ids = candidateIds.distinct()
        if (ids.isEmpty() || candidateIds.size > 30 || ids.any { id -> state.value.candidates.none { it.id == id } }) {
            mutableState.update { it.copy(ocrMessage = "请选择一至三十个已有候选画面。") }
            return
        }
        val request = generation
        mutableState.update { it.copy(busy = true, ocrMessage = null, ocrFailedCandidateId = null, message = null) }
        task = viewModelScope.launch {
            operation.withLock {
                var run: CandidateOcrRun? = null
                var ownedCancellation: OcrCancellation? = null
                var currentCandidateId: String? = null
                var ownsWork = false
                var ownsPixels = false
                try {
                    if (!workGate.tryLock()) {
                        if (request == generation) mutableState.update { it.copy(
                            ocrMessage = "另一个候选整理或文字识别任务仍在运行，请稍后重试。") }
                        return@withLock
                    }
                    ownsWork = true
                    pixelsGate.lock()
                    ownsPixels = true
                    currentCoroutineContext().ensureActive()
                    val saved = withContext(Dispatchers.IO) {
                        // Even a fully cached retry must not claim that a missing source was
                        // decoded successfully. Existing raw text remains readable separately.
                        requireSourceAvailable(source)
                        check(projects.readProject(projectId) != null) { "项目已不存在。" }
                        val candidates = store.read(projectId, source)
                        val previous = ocrStore.read(projectId, source, candidates.candidates)
                        val selected = ids.map { id -> candidates.candidates.firstOrNull { it.id == id }
                            ?: error("候选已改变，请刷新后重试。") }
                        currentCoroutineContext().ensureActive()
                        run = ocrStore.begin(projectId, source, selected).also {
                            ownedCancellation = it.cancellation
                            ocrCancellation = it.cancellation
                        }
                        candidates to previous
                    }
                    if (request == generation) {
                        apply(saved.first)
                        applyOcr(saved.second, replace = true)
                    }
                    val ownedRun = checkNotNull(run)
                    var progress = withContext(Dispatchers.IO) { ocrStore.progress(ownedRun) }
                    if (request == generation) applyOcr(progress)
                    for (candidate in ownedRun.candidates) {
                        currentCoroutineContext().ensureActive()
                        if (ownedRun.cancellation.isCancelled) throw CancellationException("文字识别已取消。")
                        if (progress.results.containsKey(candidate.id)) continue
                        currentCandidateId = candidate.id
                        var frame: DecodedFrame? = null
                        try {
                            val decoded = decoder.decode(source, candidate.actualTimeUs).also { frame = it }
                            check(decoded.presentationTimeUs == candidate.actualTimeUs &&
                                decoded.timePrecisionUs == candidate.timePrecisionUs &&
                                decoded.bitmap.width == candidate.width && decoded.bitmap.height == candidate.height) {
                                "实际画面与候选不一致，请重新整理候选。"
                            }
                            currentCoroutineContext().ensureActive()
                            if (ownedRun.cancellation.isCancelled) throw CancellationException("文字识别已取消。")
                            val result = ocrEngine.recognize(decoded.bitmap, ownedRun.cancellation)
                            currentCoroutineContext().ensureActive()
                            if (ownedRun.cancellation.isCancelled) throw CancellationException("文字识别已取消。")
                            progress = withContext(Dispatchers.IO) { ocrStore.saveFrame(ownedRun, candidate, result) }
                            if (request == generation) applyOcr(progress)
                        } finally { frame?.bitmap?.recycle() }
                        currentCandidateId = null
                    }
                    currentCoroutineContext().ensureActive()
                    val completed = withContext(NonCancellable + Dispatchers.IO) {
                        ocrStore.finish(ownedRun, CandidateOcrStatus.COMPLETED).also { run = null }
                    }
                    if (request == generation) {
                        if (applyOcr(completed)) mutableState.update {
                            it.copy(ocrMessage = "文字识别已完成，结果仅保存在本机；请检查遗漏和错误。")
                        }
                    }
                } catch (cancelled: CancellationException) {
                    finishOcr(run, CandidateOcrStatus.CANCELLED, request,
                        "已取消文字识别，已完成结果仍保留；可重试。")
                    throw cancelled
                } catch (failure: Exception) {
                    finishOcr(run, CandidateOcrStatus.FAILED, request, ocrFailureMessage(failure))
                    if (request == generation && !clearInvalidatedOcr()) mutableState.update { it.copy(ocrFailedCandidateId = currentCandidateId) }
                } catch (_: OutOfMemoryError) {
                    finishOcr(run, CandidateOcrStatus.FAILED, request,
                        "本机可用内存不足，文字识别已停止；已有结果仍保留。")
                    if (request == generation && !clearInvalidatedOcr()) mutableState.update { it.copy(ocrFailedCandidateId = currentCandidateId) }
                } finally {
                    run?.let(ocrStore::abandon)
                    if (ocrCancellation === ownedCancellation) ocrCancellation = null
                    if (ownsPixels) pixelsGate.unlock()
                    if (ownsWork) workGate.unlock()
                    if (request == generation) mutableState.update { it.copy(busy = false) }
                }
            }
        }
        cancellableTask = task
    }

    private suspend fun finishOcr(run: CandidateOcrRun?, status: CandidateOcrStatus, request: Long, text: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            val saved = run?.let { runCatching { ocrStore.finish(it, status) }.getOrNull() }
            // A deletion hook may already have revoked the lease and removed its rows.
            // Reload just this scope rather than retaining stale text or resurrecting a job.
            val scopeCandidates = if (request == generation) state.value.candidates else run?.candidates.orEmpty()
            val restored = if (saved == null && run != null)
                runCatching { ocrStore.read(run.projectId, run.source, scopeCandidates) }.getOrNull() else null
            withContext(Dispatchers.Main.immediate) {
                if (request == generation && !clearInvalidatedOcr()) {
                    if (saved != null) applyOcr(saved)
                    else if (restored != null) applyOcr(restored, replace = true)
                    if (!clearInvalidatedOcr()) mutableState.update { it.copy(ocrStatus = status, ocrMessage = text,
                        ocrCompleted = saved?.completed ?: restored?.completed ?: it.ocrCompleted,
                        ocrTotal = saved?.total ?: restored?.total ?: it.ocrTotal) }
                }
            }
        }

    private fun ocrFailureMessage(failure: Exception): String = when ((failure as? OcrException)?.code) {
        OcrError.MODEL_UNAVAILABLE -> "离线文字模型暂时无法读取，请检查本机空间后重试；已有结果仍保留。"
        OcrError.ENGINE_UNAVAILABLE -> "当前设备暂时无法启动离线文字识别；已有结果仍保留。"
        OcrError.ENGINE_BUSY -> "另一项文字识别仍在运行，请稍后重试；已有结果仍保留。"
        OcrError.RESOURCE_LIMIT -> "本机可用内存不足，文字识别已停止；已有结果仍保留。"
        OcrError.TIMEOUT -> "本张文字识别超时，已完成结果仍保留；可重试。"
        OcrError.INPUT_TOO_LARGE, OcrError.INVALID_INPUT -> "候选画面无法用于文字识别，请重新整理候选；已有结果仍保留。"
        else -> "文字识别未完成，请检查原片和本机空间后重试；已有结果仍保留。"
    }

    private fun requireSourceAvailable(source: ImportedSource) {
        check(source.privateRelativePath == "sources/${source.sourceId}.mp4") { "本机素材归属无效。" }
        val sources = File(privateRoot, "sources")
        val file = File(privateRoot, source.privateRelativePath)
        check(sources.canonicalFile == sources && file.canonicalFile == file && file.parentFile == sources &&
            file.isFile && file.length() == source.metadata.byteLength) { "本机原片缺失或已改变。" }
    }

    fun retry() = analyze()
    fun cancel() {
        // Choice writes and metadata reloads are short atomic operations, not analysis jobs.
        if (state.value.busy) {
            ocrCancellation?.cancel()
            cancellableTask?.cancel()
        }
    }

    /** Reconcile after step deletion or returning from the manual-review queue. */
    fun refresh() {
        val source = source ?: return
        val projectId = state.value.projectId ?: return
        if (state.value.busy || state.value.loading) return
        val request = generation
        mutableState.update { it.copy(loading = true, message = null) }
        task = viewModelScope.launch {
            operation.withLock {
                try {
                    val (saved, text) = withContext(Dispatchers.IO) { readWorkspace(projectId, source) }
                    if (request == generation) { apply(saved); applyOcr(text, replace = true) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (request == generation) message("候选记录暂时无法读取，已保留本机数据；可重试。") }
                finally { if (request == generation) mutableState.update { it.copy(loading = false) } }
            }
        }
    }

    fun setDecision(candidateId: String, decision: CandidateDecision) = setDecisions(listOf(candidateId), decision)

    /** Bulk selection is one durable edit; it cannot drop later items behind a busy flag. */
    fun setDecisions(candidateIds: List<String>, decision: CandidateDecision) {
        val source = source ?: return
        val projectId = state.value.projectId ?: return
        if (state.value.busy || state.value.loading || candidateIds.isEmpty()) return
        val request = generation
        mutableState.update { it.copy(busy = true, message = null) }
        task = viewModelScope.launch {
            operation.withLock {
                try {
                    val saved = withContext(Dispatchers.IO) { store.setDecisions(projectId, source, candidateIds, decision) }
                    if (request == generation) apply(saved)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (request == generation) message("选择保存失败，请重试；尚未保存的选择没有生效。") }
                finally { if (request == generation) mutableState.update { it.copy(busy = false) } }
            }
        }
    }

    /** Returns only after durable association. Retry with the same saved step is idempotent. */
    suspend fun markUsed(candidateId: String, stepId: String) {
        val source = checkNotNull(source) { "未选择候选素材。" }
        val projectId = checkNotNull(state.value.projectId) { "未选择候选项目。" }
        val request = generation
        operation.withLock {
            val saved = withContext(Dispatchers.IO) {
                val step = projects.readProject(projectId)?.steps?.firstOrNull { it.id == stepId }
                    ?: error("实际步骤尚未保存，不能将候选标记为已加入。")
                check(step.source == source) { "实际步骤与此候选素材不一致。" }
                check(step.captureId == "candidate-$candidateId") { "实际步骤的保存标识与此候选不一致。" }
                store.markUsed(projectId, source, candidateId, stepId)
            }
            if (request == generation) apply(saved)
        }
    }

    /**
     * Sensitive source pixels, for an explicitly local candidate preview only. No files/cache
     * are created. The caller owns this <=160px bitmap and should retain only visible rows.
     * Full-size temporary decoding is released here. Analysis has priority over thumbnails.
     */
    suspend fun loadThumbnail(candidateId: String): Bitmap = pixelsGate.withLock {
        val source = checkNotNull(source) { "未选择素材。" }
        val request = generation
        check(!workGate.isLocked && !state.value.busy && !state.value.loading && state.value.status != CandidateAnalysisStatus.RUNNING) {
            "整理完成后才能预览候选。"
        }
        val candidate = state.value.candidates.firstOrNull { it.id == candidateId } ?: error("候选已不存在。")
        var frame: DecodedFrame? = null
        var result: Bitmap? = null
        try {
            val decoded = decoder.decode(source, candidate.actualTimeUs).also { frame = it }
            check(decoded.presentationTimeUs == candidate.actualTimeUs && decoded.timePrecisionUs == candidate.timePrecisionUs &&
                decoded.bitmap.width == candidate.width && decoded.bitmap.height == candidate.height) {
                "实际画面与候选时间不一致，请手动取帧。"
            }
            withContext(Dispatchers.Default) {
                val scale = minOf(1f, 160f / max(decoded.bitmap.width, decoded.bitmap.height))
                val scaled = Bitmap.createScaledBitmap(decoded.bitmap,
                    (decoded.bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (decoded.bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
                result = if (scaled === decoded.bitmap) scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
            }
            currentCoroutineContext().ensureActive()
            check(request == generation && !state.value.busy) { "候选预览已失效。" }
            checkNotNull(result).also { result = null }
        } finally {
            frame?.bitmap?.recycle()
            result?.recycle()
        }
    }

    private suspend fun finishInterrupted(
        run: CandidateRun?,
        latest: CandidateAnalysisProgress?,
        status: CandidateAnalysisStatus,
        request: Long,
        text: String,
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val saved = run?.let { runCatching { store.finish(it, status, latest) }.getOrNull() }
        withContext(Dispatchers.Main.immediate) {
            if (request == generation) {
                if (saved != null) apply(saved)
                else mutableState.update { it.copy(status = status) }
                message(if (run != null && saved == null) "$text 最近进度未能落盘，重新打开后将从上次保存处恢复。" else text)
            }
        }
    }

    private fun apply(snapshot: FrameCandidateSnapshot) {
        mutableState.update { it.copy(candidates = snapshot.candidates, status = snapshot.status,
            completedSamples = snapshot.completedSamples, totalSamples = snapshot.totalSamples,
            uniqueFrames = snapshot.uniqueFrames) }
    }

    private fun readReconciled(projectId: String, source: ImportedSource): FrameCandidateSnapshot {
        store.read(projectId, source)
        val project = projects.readProject(projectId) ?: error("项目已不存在。")
        return store.reconcileSavedSteps(projectId, source, project.steps)
    }

    private fun readWorkspace(projectId: String, source: ImportedSource): Pair<FrameCandidateSnapshot, CandidateOcrSnapshot> {
        val candidates = readReconciled(projectId, source)
        return candidates to ocrStore.read(projectId, source, candidates.candidates)
    }

    private fun applyOcr(snapshot: CandidateOcrSnapshot, replace: Boolean = false): Boolean {
        val applied = ocrStore.applyIfCurrent(snapshot, state.value.projectId, source) {
            mutableState.update { it.copy(
                ocrResults = if (replace) snapshot.results else it.ocrResults + snapshot.results,
                ocrStatus = snapshot.status, ocrCompleted = snapshot.completed, ocrTotal = snapshot.total,
                ocrMessage = if (snapshot.status == CandidateOcrStatus.INTERRUPTED)
                    "上次文字识别中断，已完成结果仍保留；可重试。" else it.ocrMessage,
            ) }
        }
        if (!applied) clearInvalidatedOcr()
        return applied
    }

    /** Called both by notifications and synchronously before applying any late IO result. */
    private fun clearInvalidatedOcr(): Boolean {
        val current = state.value
        if (!ocrStore.isInvalidated(current.projectId, current.sourceId)) return false
        ocrCancellation?.cancel()
        cancellableTask?.cancel()
        mutableState.update { it.copy(ocrResults = emptyMap(), ocrStatus = CandidateOcrStatus.NOT_STARTED,
            ocrCompleted = 0, ocrTotal = 0, ocrFailedCandidateId = null,
            ocrMessage = "关联项目或原片已删除，文字识别结果已清除。") }
        return true
    }

    private fun message(text: String) { mutableState.update { it.copy(message = text) } }

    override fun onCleared() {
        ocrCancellation?.cancel()
        super.onCleared()
    }

    private companion object {
        // Shared by every workspace instance: only one analysis/OCR run and one decoded
        // full-size frame may be owned here. Thumbnails never overlap with either batch.
        val workGate = Mutex()
        val pixelsGate = Mutex()
    }
}
