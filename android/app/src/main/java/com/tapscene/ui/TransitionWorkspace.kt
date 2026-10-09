package com.tapscene.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.data.ProjectLimits
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.ProjectTransition
import com.tapscene.data.ReviewedTransitionInput
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.DecodedFrame
import com.tapscene.media.FrameDecodeException
import com.tapscene.media.ImportedSource
import com.tapscene.media.MediaExportException
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.VideoFrameDecoder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class TransitionUiState(
    val projectId: String? = null,
    val stepId: String? = null,
    val edgeId: String? = null,
    val revision: Long = 0,
    val fromTitle: String = "",
    val targetTitle: String = "",
    val sources: List<ImportedSource> = emptyList(),
    val selectedSourceId: String? = null,
    val startUs: Long = 0,
    val endUs: Long = 0,
    val masks: List<OpaqueMask> = emptyList(),
    val frame: DecodedFrame? = null,
    val existing: ProjectTransition? = null,
    val otherDurationUs: Long = 0,
    val candidate: SafeMediaWriter.CandidateMedia? = null,
    val runId: Long = 0,
    val watchedRunId: Long? = null,
    val interrupted: Boolean = false,
    val busy: Boolean = false,
    val stage: String? = null,
    val message: String? = null,
    val failedToLoad: Boolean = false,
) {
    val source: ImportedSource? get() = sources.firstOrNull { it.sourceId == selectedSourceId }
    val canRender: Boolean get() = !busy && !failedToLoad && source != null && startUs >= 0 &&
        endUs > startUs && endUs <= source!!.metadata.durationUs &&
        endUs - startUs <= ProjectLimits.MAX_TRANSITION_US &&
        otherDurationUs + endUs - startUs <= 60_000_000L
    val canConfirm: Boolean get() = !busy && !failedToLoad && candidate != null && watchedRunId == runId && !interrupted
}

/** Edits one stable edge. Originals remain private; only full-reviewed writer output is bound. */
class TransitionWorkspace(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = ProjectStore(app)
    private val writer = SafeMediaWriter(app)
    private val decoder = VideoFrameDecoder(app)
    private val mutableState = MutableStateFlow(TransitionUiState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private var closed = false
    private val session = UUID.randomUUID().toString()
    private val directory = File(app.noBackupFilesDir, "transition-candidates/$session")
    private var sequence = 0L
    private var generation = 0L

    init { synchronized(activeSessions) { activeSessions.add(session) } }

    fun open(projectId: String, stepId: String, edgeId: String) {
        if (state.value.busy || task?.isActive == true) return
        if (state.value.projectId == projectId && state.value.stepId == stepId && state.value.edgeId == edgeId) return
        generation++
        clearOwnedOutput()
        mutableState.value = TransitionUiState(projectId = projectId, stepId = stepId, edgeId = edgeId)
        execute("读取过渡") {
            val (snapshot, sources) = withContext(Dispatchers.IO) {
                cleanInactiveSessions()
                val project = store.readProject(projectId) ?: error("项目已移除")
                val all = WorkspaceStore(app, projectId).read().map { it.source } +
                    project.steps.map { it.source } + project.steps.flatMap { step ->
                        listOfNotNull(step.nextAction?.transition?.source) + step.hotspots.mapNotNull { it.transition?.source }
                    }
                project to all.distinctBy { it.sourceId }
            }
            val step = snapshot.steps.firstOrNull { it.id == stepId } ?: error("步骤已移除")
            val action = step.nextAction?.takeIf { it.id == edgeId }
            val hotspot = step.hotspots.firstOrNull { it.edgeId == edgeId }
            check(action != null || hotspot != null) { "动作已改变，请返回步骤重新选择" }
            val targetId = action?.targetStepId ?: hotspot?.targetStepId
            val target = snapshot.steps.firstOrNull { it.id == targetId }
            check(target != null || hotspot?.endLabel != null) { "先为动作选择有效目标" }
            val existing = action?.transition ?: hotspot?.transition
            val source = sources.firstOrNull { it.sourceId == (existing?.source?.sourceId ?: step.sourceId) }
                ?: sources.firstOrNull()
            val start = existing?.startUs ?: step.frameTimeUs.takeIf { source?.sourceId == step.sourceId } ?: 0L
            val duration = source?.metadata?.durationUs ?: 0L
            val boundedStart = start.coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
            val end = existing?.endUs ?: (boundedStart + 3_000_000L).coerceAtMost(duration)
            mutableState.update { it.copy(revision = snapshot.project.revision, fromTitle = step.title,
                targetTitle = target?.title ?: "结束 · ${hotspot?.endLabel}", sources = sources,
                selectedSourceId = source?.sourceId, startUs = boundedStart, endUs = end,
                masks = existing?.masks ?: step.masks.takeIf { source?.sourceId == step.sourceId }.orEmpty(),
                existing = existing, otherDurationUs = transitionDuration(snapshot) - (existing?.asset?.durationUs ?: 0L)) }
            if (source != null) {
                try { loadFrame(source, boundedStart) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: FrameDecodeException) {
                    message(failure.message ?: "原素材画面暂不可读，可重选素材或改用静态切换。")
                }
            }
        }
    }

    fun selectSource(id: String) {
        val current = state.value
        if (current.busy || current.selectedSourceId == id) return
        val source = current.sources.firstOrNull { it.sourceId == id } ?: return
        clearOwnedOutput()
        mutableState.update { it.copy(selectedSourceId = id, startUs = 0,
            endUs = minOf(3_000_000L, source.metadata.durationUs), masks = emptyList(), message = null) }
        takeFrame(0)
    }

    fun setRange(startUs: Long, endUs: Long) {
        val current = state.value
        val source = current.source ?: return
        if (current.busy) return
        if (startUs < 0 || endUs <= startUs || endUs > source.metadata.durationUs ||
            endUs - startUs > ProjectLimits.MAX_TRANSITION_US || current.otherDurationUs + endUs - startUs > 60_000_000L) {
            message("选取 0–10 秒的区间；所有过渡合计不超过 60 秒。")
            return
        }
        clearCandidate()
        mutableState.update { it.copy(startUs = startUs, endUs = endUs, message = null) }
        takeFrame(startUs)
    }

    fun takeFrame(timeUs: Long) {
        val current = state.value
        val source = current.source ?: return
        if (current.busy || current.candidate != null) return
        execute("查看实际画面") { loadFrame(source, timeUs.coerceIn(current.startUs, (current.endUs - 1).coerceAtLeast(current.startUs))) }
    }

    fun addMask(mask: OpaqueMask) {
        if (state.value.busy || state.value.masks.size >= 20 || state.value.candidate != null) return
        clearCandidate()
        mutableState.update { it.copy(masks = it.masks + mask, message = null) }
    }

    fun undoMask() {
        if (state.value.busy || state.value.candidate != null) return
        clearCandidate()
        mutableState.update { it.copy(masks = it.masks.dropLast(1), message = null) }
    }

    fun generate() {
        val snapshot = state.value
        val source = snapshot.source ?: return
        if (!snapshot.canRender) return
        clearCandidate()
        execute("生成无声过渡") {
            val file = withContext(Dispatchers.IO) {
                File(app.noBackupFilesDir, source.privateRelativePath).also {
                    check(it.canonicalFile.parentFile == File(app.noBackupFilesDir, "sources").canonicalFile && it.isFile) {
                        "这段本机素材已不可用，请重新导入后选择"
                    }
                }
            }
            var output: SafeMediaWriter.CandidateMedia? = null
            var retained = false
            try {
                output = writer.writeVideo(file, snapshot.startUs, snapshot.endUs, snapshot.masks, directory) { stage ->
                    mutableState.update { it.copy(stage = when (stage) {
                        SafeMediaWriter.Stage.PREPARING -> "准备生成"
                        SafeMediaWriter.Stage.RENDERING -> "重编码并遮挡"
                        SafeMediaWriter.Stage.VERIFYING -> "检查全部输出帧"
                        SafeMediaWriter.Stage.FINALIZING -> "保存待复核短片"
                    }) }
                }
                val actualDuration = requireNotNull(output.durationUs)
                check(actualDuration in 1..ProjectLimits.MAX_TRANSITION_US &&
                    snapshot.otherDurationUs + actualDuration <= 60_000_000L) {
                    "实际输出超过时长限额，请略微缩短区间后重新生成。"
                }
                coroutineContext.ensureActive()
                mutableState.update { it.copy(candidate = output, runId = ++sequence, watchedRunId = null,
                    interrupted = false, message = null) }
                retained = true
            } finally {
                if (!retained && output != null) withContext(NonCancellable + Dispatchers.IO) { removeOwned(output.file) }
            }
        }
    }

    fun watched(runId: Long) {
        if (state.value.runId == runId && state.value.candidate != null && !state.value.interrupted)
            mutableState.update { it.copy(watchedRunId = runId) }
    }

    fun interrupted(runId: Long) {
        if (state.value.runId == runId && state.value.candidate != null)
            mutableState.update { it.copy(watchedRunId = null, interrupted = true) }
    }

    fun replay(runId: Long) {
        if (state.value.runId == runId && state.value.candidate != null && !state.value.busy)
            mutableState.update { it.copy(runId = ++sequence, watchedRunId = null, interrupted = false) }
    }

    fun editAgain() { if (!state.value.busy) clearCandidate() }

    /** Explicit author confirmation is the only caller of this method. */
    fun confirmAndBind(onSaved: (String, String) -> Unit) {
        val snapshot = state.value
        val candidate = snapshot.candidate ?: return
        val source = snapshot.source ?: return
        if (!snapshot.canConfirm) return
        execute("保存过渡", reloadAfterCancellation = true) {
            val projectId = requireNotNull(snapshot.projectId)
            val stepId = requireNotNull(snapshot.stepId)
            check(state.value.candidate?.sha256 == candidate.sha256 && state.value.watchedRunId == snapshot.runId &&
                !state.value.interrupted) { "完整播放状态已改变，请重新播放" }
            withContext(Dispatchers.IO) {
                store.bindReviewedTransition(projectId, requireNotNull(snapshot.edgeId), snapshot.revision,
                    ReviewedTransitionInput(candidate.file, candidate.sha256, candidate.width, candidate.height,
                        requireNotNull(candidate.durationUs), source, snapshot.startUs, snapshot.endUs,
                        snapshot.masks.toList(), reviewId = candidate.file.name))
            }
            clearOwnedOutput()
            mutableState.value = TransitionUiState(busy = true)
            onSaved(projectId, stepId)
        }
    }

    fun useStatic(onSaved: (String, String) -> Unit) {
        val snapshot = state.value
        if (snapshot.busy || snapshot.failedToLoad) return
        execute("切换为静态", reloadAfterCancellation = true) {
            val projectId = requireNotNull(snapshot.projectId)
            val stepId = requireNotNull(snapshot.stepId)
            withContext(Dispatchers.IO) { store.removeTransition(projectId, requireNotNull(snapshot.edgeId), snapshot.revision) }
            clearOwnedOutput()
            mutableState.value = TransitionUiState(busy = true)
            onSaved(projectId, stepId)
        }
    }

    fun leave() {
        generation++
        task?.cancel()
        clearOwnedOutput()
        mutableState.value = TransitionUiState()
    }

    fun cancel() { task?.cancel() }
    fun message(text: String) { mutableState.update { it.copy(message = text) } }

    private suspend fun loadFrame(source: ImportedSource, timeUs: Long) {
        val decoded = decoder.decode(source, timeUs)
        try { coroutineContext.ensureActive() } catch (cancelled: CancellationException) { decoded.bitmap.recycle(); throw cancelled }
        val old = state.value.frame
        mutableState.update { it.copy(frame = decoded) }
        old?.bitmap?.recycle()
    }

    private fun clearCandidate() {
        val file = state.value.candidate?.file
        mutableState.update { it.copy(candidate = null, runId = ++sequence, watchedRunId = null, interrupted = false) }
        if (file != null) cleanupScope.launch { MediaWorkspace.operationLock.withLock { removeOwned(file) } }
    }

    private fun clearOwnedOutput() {
        clearCandidate()
        val old = state.value.frame
        mutableState.update { it.copy(frame = null) }
        old?.bitmap?.recycle()
    }

    private fun execute(stage: String, reloadAfterCancellation: Boolean = false, block: suspend () -> Unit) {
        if (closed || state.value.busy) return
        val operationGeneration = generation
        val operationState = state.value
        mutableState.update { it.copy(busy = true, stage = stage, message = null) }
        task = viewModelScope.launch {
            try { MediaWorkspace.operationLock.withLock { block() } }
            catch (cancelled: CancellationException) {
                if (reloadAfterCancellation) reloadCancelledBinding(operationState, operationGeneration)
                else if (operationGeneration == generation) message("已取消处理")
                throw cancelled
            }
            catch (failure: Exception) {
                val safe = when (failure) {
                    is FrameDecodeException, is MediaExportException, is IllegalArgumentException, is IllegalStateException -> failure.message
                    else -> null
                }
                if (operationGeneration == generation) {
                    message(safe?.takeIf { it.length <= 240 && '/' !in it && '\\' !in it }
                        ?: "处理未完成，请检查本机素材和空间后重试。")
                    if (stage == "读取过渡") mutableState.update { it.copy(failedToLoad = true) }
                }
            } finally {
                if (operationGeneration == generation) mutableState.update { it.copy(busy = false, stage = null) }
            }
        }
    }

    private suspend fun reloadCancelledBinding(before: TransitionUiState, operationGeneration: Long) {
        if (operationGeneration != generation) return
        // A store transaction may have committed before cancellation wins the IO -> Main return.
        // Re-read persisted state instead of inferring rollback from CancellationException.
        withContext(NonCancellable) {
            mutableState.update { it.copy(stage = "核对保存结果") }
            try {
                val saved = withContext(Dispatchers.IO) {
                    MediaWorkspace.operationLock.withLock {
                        store.readProject(requireNotNull(before.projectId)) ?: error("项目已移除")
                    }
                }
                val step = saved.steps.firstOrNull { it.id == before.stepId } ?: error("步骤已移除")
                val action = step.nextAction?.takeIf { it.id == before.edgeId }
                val hotspot = step.hotspots.firstOrNull { it.edgeId == before.edgeId }
                check(action != null || hotspot != null) { "动作已改变" }
                val existing = action?.transition ?: hotspot?.transition
                if (operationGeneration == generation) mutableState.update { it.copy(
                    revision = saved.project.revision, existing = existing,
                    otherDurationUs = transitionDuration(saved) - (existing?.asset?.durationUs ?: 0L),
                    failedToLoad = false,
                    message = if (existing == null) "处理已停止，当前保存的是静态切换。"
                        else "处理已停止，已重新读取当前保存的视频过渡。",
                ) }
            } catch (_: Exception) {
                if (operationGeneration == generation) mutableState.update { it.copy(failedToLoad = true,
                    message = "处理已停止，但无法确认保存结果，请返回后重新打开过渡编辑。") }
            }
        }
    }

    private fun removeOwned(file: File) {
        if (file.canonicalFile.parentFile == directory.canonicalFile && file.isFile &&
            (file.name.startsWith("candidate-") || file.name.startsWith(".candidate-"))) {
            check(file.delete() || !file.exists()) { "临时短片清理失败" }
        }
    }

    private fun cleanInactiveSessions() {
        val active = synchronized(activeSessions) { activeSessions.toSet() }
        val root = File(app.noBackupFilesDir, "transition-candidates").canonicalFile
        root.listFiles().orEmpty().forEach { dir ->
            if (dir.isDirectory && dir.canonicalFile.parentFile == root &&
                dir.name.matches(Regex("[0-9a-f-]{36}")) && dir.name !in active) {
                dir.listFiles().orEmpty().forEach { file ->
                    if (file.isFile && file.canonicalFile.parentFile == dir.canonicalFile &&
                        (file.name.startsWith("candidate-") || file.name.startsWith(".candidate-"))) {
                        check(file.delete() || !file.exists()) { "临时短片清理失败" }
                    }
                }
                dir.delete()
            }
        }
    }

    override fun onCleared() {
        closed = true
        generation++
        task?.cancel()
        val frame = state.value.frame
        mutableState.value = TransitionUiState()
        frame?.bitmap?.recycle()
        cleanupScope.launch {
            MediaWorkspace.operationLock.withLock {
                try { directory.listFiles().orEmpty().forEach(::removeOwned); directory.delete() }
                finally { synchronized(activeSessions) { activeSessions.remove(session) } }
            }
        }
        super.onCleared()
    }

    companion object {
        private val activeSessions = mutableSetOf<String>()
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private fun transitionDuration(snapshot: ProjectSnapshot): Long = snapshot.steps.sumOf { step ->
            (step.nextAction?.transition?.asset?.durationUs ?: 0L) + step.hotspots.sumOf { it.transition?.asset?.durationUs ?: 0L }
        }
    }
}
