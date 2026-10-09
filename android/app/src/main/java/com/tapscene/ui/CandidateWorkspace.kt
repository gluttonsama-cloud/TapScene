package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.tapscene.media.FrameDecodeException
import com.tapscene.media.ImportedSource
import com.tapscene.media.VideoFrameDecoder
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CandidateWorkspaceState(
    val projectId: String? = null,
    val sourceId: String? = null,
    val candidates: List<FrameCandidate> = emptyList(),
    val status: CandidateAnalysisStatus = CandidateAnalysisStatus.NOT_STARTED,
    val completedSamples: Int = 0,
    val totalSamples: Int = 0,
    val uniqueFrames: Int = 0,
    val busy: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
)

/** No candidate is a reviewed PNG. Only the existing media review flow can add project steps. */
class CandidateWorkspace(application: Application) : AndroidViewModel(application) {
    private val store = FrameCandidateStore(application)
    private val projects = ProjectStore(application)
    private val analyzer = FrameCandidateAnalyzer(application)
    private val decoder = VideoFrameDecoder(application)
    private val operation = Mutex()
    private val thumbnailGate = Mutex()
    private val mutableState = MutableStateFlow(CandidateWorkspaceState())
    val state = mutableState.asStateFlow()
    private var source: ImportedSource? = null
    private var generation = 0L
    private var task: Job? = null

    /** Switching scopes cancels the old job, then waits for its owned checkpoint cleanup. */
    fun activate(projectId: String?, source: ImportedSource?) {
        require((projectId == null) == (source == null)) { "请选择项目和素材后整理候选。" }
        if (state.value.projectId == projectId && this.source == source) return
        val previousTask = task
        previousTask?.cancel()
        val request = ++generation
        this.source = source
        mutableState.value = CandidateWorkspaceState(projectId = projectId, sourceId = source?.sourceId,
            loading = projectId != null)
        task = viewModelScope.launch {
            previousTask?.join()
            if (projectId == null || source == null) return@launch
            operation.withLock {
                try {
                    val saved = withContext(Dispatchers.IO) { readReconciled(projectId, source) }
                    if (request == generation) {
                        apply(saved)
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
        if (state.value.busy || state.value.loading) return
        val request = generation
        mutableState.update { it.copy(busy = true, message = null) }
        task = viewModelScope.launch {
            operation.withLock {
                var run: CandidateRun? = null
                var latest: CandidateAnalysisProgress? = null
                var persistedSamples = 0
                var persistedCandidateCount = 0
                try {
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
                } catch (failure: Exception) {
                    finishInterrupted(run, latest, CandidateAnalysisStatus.FAILED, request,
                        if (failure is FrameDecodeException) failure.message ?: "候选解码失败，可重试。"
                        else "候选整理失败，已保存的选择仍保留；可重试或手动取帧。")
                } finally {
                    run?.let(store::abandon)
                    if (request == generation) mutableState.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun retry() = analyze()
    fun cancel() {
        // Choice writes and metadata reloads are short atomic operations, not analysis jobs.
        if (state.value.busy && state.value.status == CandidateAnalysisStatus.RUNNING) task?.cancel()
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
                    val saved = withContext(Dispatchers.IO) { readReconciled(projectId, source) }
                    if (request == generation) apply(saved)
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
    suspend fun loadThumbnail(candidateId: String): Bitmap = thumbnailGate.withLock {
        val source = checkNotNull(source) { "未选择素材。" }
        val request = generation
        check(!state.value.busy && !state.value.loading && state.value.status != CandidateAnalysisStatus.RUNNING) {
            "整理完成后才能预览候选。"
        }
        val candidate = state.value.candidates.firstOrNull { it.id == candidateId } ?: error("候选已不存在。")
        var frame: DecodedFrame? = null
        var result: Bitmap? = null
        try {
            val decoded = decoder.decode(source, candidate.actualTimeUs).also { frame = it }
            check(decoded.presentationTimeUs == candidate.actualTimeUs && decoded.timePrecisionUs == candidate.timePrecisionUs) {
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

    private fun message(text: String) { mutableState.update { it.copy(message = text) } }
}
