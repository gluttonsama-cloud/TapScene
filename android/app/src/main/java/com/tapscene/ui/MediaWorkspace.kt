package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.data.ProjectStore
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.CandidateOcrStore
import com.tapscene.data.ReviewedStepInput
import com.tapscene.data.SafeImageBinding
import com.tapscene.data.StepOrigin
import com.tapscene.data.SourceDraft
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.DecodedFrame
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceImporter
import com.tapscene.media.VideoFrameDecoder
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** A bounded replacement session. Its masks and frame are never written to SourceDraft storage. */
data class StepImageCorrection(
    val projectId: String,
    val stepId: String,
    val expectedRevision: Long,
    val sessionId: String,
    val title: String,
    val regionCount: Int,
    val transitionCount: Int,
    /** The current formal PNG at session entry, never this step's previous image provenance. */
    val safeImageBase: SafeImageBinding? = null,
)

class StepCorrectionException(message: String) : Exception(message)

/** Only this round's masks are editable. All earlier masks are already pixels in [bitmap]. */
data class SafeImageCorrectionDraft(
    val binding: SafeImageBinding,
    val bitmap: Bitmap? = null,
    val masks: List<OpaqueMask> = emptyList(),
)

data class WorkspaceUiState(
    val drafts: List<SourceDraft> = emptyList(),
    val correction: StepImageCorrection? = null,
    val correctionDraft: SourceDraft? = null,
    val safeImageDraft: SafeImageCorrectionDraft? = null,
    val completedCorrectionId: String? = null,
    val selectedId: String? = null,
    val frame: DecodedFrame? = null,
    /** Queue item whose actual decoded frame is currently editable; never inferred from source ID. */
    val frameReviewId: String? = null,
    val candidate: SafeMediaWriter.CandidateMedia? = null,
    val candidateImage: Bitmap? = null,
    val watchedDigest: String? = null,
    val reviewedDigest: String? = null,
    val busy: Boolean = false,
    val stage: String? = null,
    val message: String? = null,
    val loadFailed: Boolean = false,
    val unsavedEdits: Boolean = false,
) {
    val selected: SourceDraft? get() = if (correction != null) correctionDraft
        else drafts.firstOrNull { it.source.sourceId == selectedId }

    /** Shared route/workspace gate. Displaying the candidate still requires explicit confirmation. */
    fun canReviewCorrection(session: StepImageCorrection, digest: String): Boolean {
        if (busy || unsavedEdits || correction != session || candidate?.mimeType != "image/png" ||
            candidate.sha256 != digest || candidateImage?.isRecycled != false) return false
        val image = safeImageDraft
        return if (image != null) image.binding == session.safeImageBase && image.bitmap?.isRecycled == false &&
            image.masks.isNotEmpty() && correctionDraft == null && frame == null
        else correctionDraft != null && frame?.bitmap?.isRecycled == false && frameReviewId == session.sessionId
    }
}

class MediaWorkspace(application: Application) : AndroidViewModel(application) {
    private val app = application
    private var store = WorkspaceStore(app)
    private val projectStore = ProjectStore(app)
    private var activeProjectId: String? = null
    private val importer = SourceImporter(app)
    private val decoder = VideoFrameDecoder(app)
    private val writer = SafeMediaWriter(app)
    private val mutableState = MutableStateFlow(WorkspaceUiState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private var savePickerPending = false
    private var cancellationNote: String? = null
    private val sessionId = UUID.randomUUID().toString()
    @Volatile private var closed = false

    init {
        synchronized(activeSessions) { activeSessions.add(sessionId) }
        reload()
    }

    /** Read-only scope identity for routing; never exposes a source path. */
    val projectId: String? get() = activeProjectId

    /** Separate source budgets/drafts per project; null retains the earlier media-only workspace. */
    fun activateProject(projectId: String?): Boolean {
        if (state.value.busy || savePickerPending || state.value.correction != null || !requireSavedEdits()) return false
        if (activeProjectId == projectId) return true
        val nextStore = WorkspaceStore(app, projectId)
        invalidateCandidate()
        activeProjectId = projectId
        store = nextStore
        mutableState.value = WorkspaceUiState()
        reload()
        return true
    }

    /** Enter from a saved step, retaining unsaved text in ProjectWorkspace rather than saving it. */
    fun openStepCorrection(project: ProjectSnapshot, stepId: String): Boolean {
        if (activeProjectId != project.project.id || state.value.busy || savePickerPending ||
            state.value.correction != null || !requireSavedEdits()) return false
        val step = project.steps.singleOrNull { it.id == stepId } ?: return false
        val transitionCount = project.steps.sumOf { from ->
            from.hotspots.count { it.transition != null && (from.id == stepId || it.targetStepId == stepId) } +
                if (from.nextAction?.let { it.transition != null && (from.id == stepId || it.targetStepId == stepId) } == true) 1 else 0
        }
        invalidateCandidate()
        val video = step.videoOrigin
        mutableState.update { it.copy(correction = StepImageCorrection(project.project.id, step.id,
            project.project.revision, UUID.randomUUID().toString(), step.title, step.regions.size, transitionCount,
            step.safeImageBinding(project.project)),
            correctionDraft = video?.let { SourceDraft(it.source, it.frameTimeUs, step.masks.toList()) },
            safeImageDraft = null,
            completedCorrectionId = null, frame = null, frameReviewId = null, message = null) }
        if (video != null) retryStepCorrection()
        return true
    }

    fun retryStepCorrection() {
        val snapshot = state.value
        val correction = snapshot.correction ?: return
        snapshot.safeImageDraft?.let { draft ->
            // A live validated base needs no reload; failure retries must not capture old pixels.
            if (draft.bitmap?.isRecycled != false) loadSafeImage(correction, draft.copy(bitmap = null))
            return
        }
        val draft = snapshot.correctionDraft ?: return
        execute("读取步骤原片") {
            verifyCorrection(correction)
            decodeCorrection(correction, draft, draft.frameTimeUs)
        }
    }

    /** Explicit opt-in: the safety-checked saved PNG becomes an irreversible pixel base. */
    fun useSafeImageBase() {
        val snapshot = state.value
        if (snapshot.busy || savePickerPending) return
        val correction = snapshot.correction ?: return
        val binding = correction.safeImageBase ?: return
        if (snapshot.safeImageDraft != null) return
        loadSafeImage(correction, SafeImageCorrectionDraft(binding))
    }

    private fun loadSafeImage(correction: StepImageCorrection, draft: SafeImageCorrectionDraft) {
        execute("读取并核对安全画面") {
            invalidateCandidate()
            releaseSafeImage()
            // Clear every prior editable source before any suspension, including failed retries.
            mutableState.update { it.copy(correctionDraft = null, safeImageDraft = draft.copy(bitmap = null),
                frame = null, frameReviewId = null) }
            verifyCorrection(correction)
            check(draft.binding == correction.safeImageBase)
            var loaded: Bitmap? = null
            try {
                withContext(Dispatchers.IO) { loaded = projectStore.readSafeImageBase(draft.binding) }
                check(state.value.correction == correction)
                mutableState.update { it.copy(safeImageDraft = draft.copy(bitmap = checkNotNull(loaded))) }
            } catch (cancelled: CancellationException) {
                loaded?.recycle()
                throw cancelled
            } catch (_: Exception) {
                loaded?.recycle()
                throw StepCorrectionException("安全画面缺失、已改变或校验未通过。无法生成新图，请返回后重新打开或重试；原步骤未替换。")
            }
        }
    }

    fun selectCorrectionSource(sourceId: String) {
        val snapshot = state.value
        val correction = snapshot.correction ?: return
        val draft = snapshot.correctionDraft
        val source = snapshot.drafts.firstOrNull { it.source.sourceId == sourceId }?.source ?: return
        if (source == draft?.source) return
        execute("更换取帧素材") {
            verifyCorrection(correction)
            // Image masks belong to already-burned safe pixels, never to an unrelated video.
            decodeCorrection(correction, SourceDraft(source, 0, draft?.masks.orEmpty().toList()), 0)
        }
    }

    fun closeStepCorrection(): Boolean {
        if (state.value.busy || savePickerPending) return false
        invalidateCandidate()
        releaseSafeImage()
        mutableState.update { it.copy(correction = null, correctionDraft = null, safeImageDraft = null, completedCorrectionId = null,
            frame = null, frameReviewId = null, message = null) }
        return true
    }

    private suspend fun verifyCorrection(correction: StepImageCorrection) {
        val current = withContext(Dispatchers.IO) { projectStore.readProject(correction.projectId) }
        if (current == null || state.value.correction != correction || current.project.revision != correction.expectedRevision ||
            current.steps.none { it.id == correction.stepId })
            throw StepCorrectionException("步骤已改变，请返回后重新打开；原画面仍保留。")
    }

    private suspend fun decodeCorrection(correction: StepImageCorrection, draft: SourceDraft, timeUs: Long) {
        invalidateCandidate()
        releaseSafeImage()
        mutableState.update { it.copy(correctionDraft = draft, safeImageDraft = null, frame = null, frameReviewId = null) }
        val exists = withContext(Dispatchers.IO) {
            val file = File(app.noBackupFilesDir, draft.source.privateRelativePath)
            draft.source.privateRelativePath == "sources/${draft.source.sourceId}.mp4" &&
                file.canonicalFile == file.absoluteFile && file.isFile
        }
        if (!exists) throw StepCorrectionException("本机原片已缺失。可在安全画面上追加遮挡；无法恢复已遮挡的像素。")
        val decoded = decoder.decode(draft.source, timeUs)
        check(state.value.correction == correction)
        mutableState.update { it.copy(correctionDraft = draft.copy(frameTimeUs = decoded.presentationTimeUs),
            frame = decoded, frameReviewId = correction.sessionId) }
    }

    /** The callback runs while this workspace's operation lock owns the reviewed candidate. */
    fun saveReviewedImage(commit: suspend (ReviewedStepInput) -> Unit) {
        val snapshot = state.value
        val candidate = snapshot.candidate
        val source = snapshot.selected
        val frame = snapshot.frame
        val correction = snapshot.correction
        val image = snapshot.safeImageDraft
        val validImage = correction != null && image?.bitmap?.isRecycled == false &&
            image.binding == correction.safeImageBase && image.masks.isNotEmpty() && source == null && frame == null
        val validVideo = image == null && source != null && frame != null &&
            (correction == null || snapshot.frameReviewId == correction.sessionId)
        if (snapshot.busy || savePickerPending || snapshot.unsavedEdits ||
            candidate == null || candidate.mimeType != "image/png" || (!validImage && !validVideo) ||
            snapshot.reviewedDigest != candidate.sha256 ||
            snapshot.candidateImage?.isRecycled != false ||
            (correction != null && !snapshot.canReviewCorrection(correction, candidate.sha256))
        ) {
            message("请先生成并复核实际图片，再保存为步骤")
            return
        }
        execute(if (correction == null) "保存为项目步骤" else "替换步骤画面") {
            check(state.value.candidate?.sha256 == candidate.sha256 &&
                state.value.reviewedDigest == candidate.sha256) { "复核已变化" }
            val input = if (validImage) {
                ReviewedStepInput(candidate.file, candidate.sha256, candidate.width, candidate.height,
                    origin = StepOrigin.Image(requireNotNull(image).binding), masks = image.masks.toList())
            } else {
                ReviewedStepInput(candidate.file, candidate.sha256, candidate.width, candidate.height,
                    requireNotNull(source).source, requireNotNull(frame).presentationTimeUs,
                    frame.timePrecisionUs, source.masks.toList())
            }
            try {
                commit(input)
            } finally {
                if (correction != null) withContext(NonCancellable) {
                    // IO can commit even when cancellation prevents its result reaching Main.
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { projectStore.readProject(correction.projectId) }.getOrNull()
                    }?.steps?.singleOrNull { it.id == correction.stepId && it.captureId == input.captureId }
                    if (saved != null) {
                        mutableState.update { it.copy(completedCorrectionId = correction.sessionId) }
                        cancellationNote = "画面已替换；已重新读取实际保存结果。"
                        invalidateCandidate()
                    }
                }
            }
            // The store owns a separately verified persistent copy, not this temporary file.
            invalidateCandidate()
            message(if (correction == null) "已保存为项目步骤" else "画面已替换")
        }
    }

    fun reload() {
        if (state.value.correction != null) return
        execute("读取已保存素材") {
            invalidateCandidate()
            val drafts = withContext(Dispatchers.IO) {
                cleanInactiveSessions()
                store.read()
            }
            mutableState.update { it.copy(drafts = drafts, selectedId = drafts.lastOrNull()?.source?.sourceId,
                frame = null, frameReviewId = null, loadFailed = false) }
        }
    }

    fun importVideo(uri: Uri) {
        if (state.value.correction != null) return
        if (!requireSavedEdits()) return
        if (state.value.loadFailed) return
        if (state.value.drafts.size >= 3) {
            message("最多保留 3 段录屏，请先删除不再需要的素材")
            return
        }
        execute("复制并检查录屏") {
            val source = importer.importSource(uri) { imported ->
                store.append(imported)
            }
            val drafts = withContext(Dispatchers.IO) { store.read() }
            mutableState.update {
                it.copy(drafts = drafts, selectedId = source.sourceId, frame = null, frameReviewId = null,
                    candidate = null, candidateImage = null, reviewedDigest = null, watchedDigest = null)
            }
            message("录屏已保存到本机")
        }
    }

    fun selectSource(id: String) {
        if (state.value.correction != null) return
        if (!requireSavedEdits()) return
        if (state.value.busy || savePickerPending || state.value.selectedId == id) return
        invalidateCandidate()
        mutableState.update { it.copy(selectedId = id, frame = null, frameReviewId = null, candidate = null,
            candidateImage = null, watchedDigest = null, reviewedDigest = null, message = null) }
    }

    fun takeFrame(timeUs: Long) {
        if (!requireSavedEdits()) return
        val selected = state.value.selected ?: return
        val correction = state.value.correction
        if (correction != null) {
            execute("解码步骤实际帧") {
                verifyCorrection(correction)
                decodeCorrection(correction, selected, timeUs)
            }
            return
        }
        execute("解码实际帧") {
            invalidateCandidate()
            val decoded = decoder.decode(selected.source, timeUs)
            val drafts = withContext(Dispatchers.IO) {
                store.updateSource(selected.source.sourceId) { it.copy(frameTimeUs = decoded.presentationTimeUs) }
            }
            mutableState.update { it.copy(drafts = drafts, frame = decoded, frameReviewId = null, candidate = null,
                candidateImage = null, reviewedDigest = null, watchedDigest = null) }
        }
    }

    /** Prepare one queue item through the same actual-frame/privacy pipeline as manual editing. */
    fun prepareCandidateImage(sourceId: String, timeUs: Long, reviewId: String) {
        if (state.value.correction != null) return
        if (state.value.busy || savePickerPending || !requireSavedEdits() || state.value.loadFailed) return
        // Close the previous output and its frame BEFORE the first suspension. Cancellation
        // or a failed metadata read must never leave item A approvable as queue item B.
        invalidateCandidate()
        mutableState.update { it.copy(frame = null, frameReviewId = null) }
        execute("准备候选图片") {
            val current = withContext(Dispatchers.IO) { store.read() }
            val selected = current.firstOrNull { it.source.sourceId == sourceId }
                ?: error("素材已移除，请重新读取")
            invalidateCandidate()
            mutableState.update { it.copy(drafts = current, selectedId = sourceId, frame = null, frameReviewId = null) }
            val decoded = decoder.decode(selected.source, timeUs)
            val drafts = withContext(Dispatchers.IO) {
                store.updateSource(sourceId) { it.copy(frameTimeUs = decoded.presentationTimeUs) }
            }
            val saved = drafts.first { it.source.sourceId == sourceId }
            mutableState.update { it.copy(drafts = drafts, frame = decoded, frameReviewId = reviewId, candidate = null,
                candidateImage = null, reviewedDigest = null, watchedDigest = null) }
            val candidate = writer.writePng(decoded.bitmap, saved.masks, outputDirectory(), ::onStage)
            val image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(candidate.file.path) }
                ?: error("无法重读生成的图片")
            // Preparation is never a privacy review. Only the existing explicit review action
            // can bind reviewedDigest to this exact output before it can become a project step.
            mutableState.update { it.copy(candidate = candidate, candidateImage = image,
                reviewedDigest = null, watchedDigest = null) }
        }
    }

    fun addMask(mask: OpaqueMask) {
        state.value.safeImageDraft?.let { draft ->
            if (draft.bitmap?.isRecycled != false || draft.masks.size >= 20) return
            updateMasks(draft.masks + mask)
            return
        }
        val selected = state.value.selected ?: return
        if (state.value.frame == null || selected.masks.size >= 20) return
        updateMasks(selected.masks + mask)
    }

    fun undoMask() {
        state.value.safeImageDraft?.let { draft ->
            if (draft.bitmap?.isRecycled == false) updateMasks(draft.masks.dropLast(1))
            return
        }
        val selected = state.value.selected ?: return
        updateMasks(selected.masks.dropLast(1))
    }

    private fun updateMasks(masks: List<OpaqueMask>) {
        if (state.value.busy || savePickerPending) return
        state.value.safeImageDraft?.let { draft ->
            if (state.value.correction == null) return
            invalidateCandidate()
            mutableState.update { it.copy(safeImageDraft = draft.copy(masks = masks.toList())) }
            return
        }
        val correctionDraft = state.value.correctionDraft
        if (state.value.correction != null && correctionDraft != null) {
            invalidateCandidate()
            mutableState.update { it.copy(correctionDraft = correctionDraft.copy(masks = masks.toList())) }
            return
        }
        val selectedId = state.value.selectedId ?: return
        execute("保存遮挡") {
            invalidateCandidate()
            val pendingDrafts = state.value.drafts.map {
                if (it.source.sourceId == selectedId) it.copy(masks = masks) else it
            }
            mutableState.update { it.copy(drafts = pendingDrafts, unsavedEdits = true) }
            val drafts = withContext(Dispatchers.IO) { store.updateSource(selectedId) { it.copy(masks = masks) } }
            mutableState.update { it.copy(drafts = drafts, candidate = null, candidateImage = null,
                watchedDigest = null, reviewedDigest = null, unsavedEdits = false) }
        }
    }

    fun retryEdits() {
        val selected = state.value.selected ?: return
        execute("重试保存遮挡") {
            val drafts = withContext(Dispatchers.IO) {
                store.updateSource(selected.source.sourceId) { it.copy(masks = selected.masks) }
            }
            mutableState.update { it.copy(drafts = drafts, unsavedEdits = false) }
        }
    }

    private fun requireSavedEdits(): Boolean {
        if (!state.value.unsavedEdits) return true
        message("遮挡尚未保存，请先重试保存")
        return false
    }

    fun makeImage() {
        if (!requireSavedEdits()) return
        val snapshot = state.value
        val image = snapshot.safeImageDraft
        if (image != null && image.masks.isEmpty()) {
            message("请先追加至少一处遮挡，再生成并复核。")
            return
        }
        val bitmap = if (image != null) image.bitmap else snapshot.frame?.bitmap
        if (bitmap == null || bitmap.isRecycled) return
        val masks = image?.masks ?: snapshot.selected?.masks ?: return
        execute("生成遮挡图片") {
            invalidateCandidate()
            snapshot.correction?.let { verifyCorrection(it) }
            val candidate = writer.writePng(bitmap, masks, outputDirectory(), ::onStage)
            val image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(candidate.file.path) }
                ?: error("无法重读生成的图片")
            mutableState.update { it.copy(candidate = candidate, candidateImage = image) }
        }
    }

    fun makeVideo(startUs: Long, endUs: Long) {
        if (state.value.correction != null) return
        if (!requireSavedEdits()) return
        val selected = state.value.selected ?: return
        execute("生成遮挡视频") {
            require(startUs >= 0 && endUs > startUs && endUs <= selected.source.metadata.durationUs) {
                "请填写有效的起止时间"
            }
            require(endUs - startUs <= 10_000_000L) { "单段视频不能超过 10 秒" }
            invalidateCandidate()
            writer.writeVideo(File(app.noBackupFilesDir, selected.source.privateRelativePath),
                startUs, endUs, selected.masks, outputDirectory(), ::onStage).also { candidate ->
                mutableState.update { it.copy(candidate = candidate) }
            }
        }
    }

    fun invalidateCandidate() {
        if (savePickerPending) return
        val previous = state.value.candidate?.file
        mutableState.update { it.copy(candidate = null, candidateImage = null,
            watchedDigest = null, reviewedDigest = null) }
        if (previous != null) viewModelScope.launch(Dispatchers.IO) { runCatching { removeCandidate(previous) } }
    }

    private fun onStage(stage: SafeMediaWriter.Stage) {
        val label = when (stage) {
            SafeMediaWriter.Stage.PREPARING -> "准备固定输入"
            SafeMediaWriter.Stage.RENDERING -> "烧录遮挡并重新编码"
            SafeMediaWriter.Stage.VERIFYING -> "重读并检查实际输出"
            SafeMediaWriter.Stage.FINALIZING -> "保存待复核文件"
        }
        mutableState.update { it.copy(stage = label) }
    }

    fun videoWatched(digest: String) {
        if (state.value.candidate?.sha256 == digest) mutableState.update { it.copy(watchedDigest = digest) }
    }

    fun review(checked: Boolean) {
        val candidate = state.value.candidate ?: return
        if (candidate.mimeType.startsWith("video/") && state.value.watchedDigest != candidate.sha256) return
        mutableState.update { it.copy(reviewedDigest = if (checked) candidate.sha256 else null) }
    }

    fun saveCandidate(uri: Uri, expectedDigest: String) {
        savePickerPending = false
        val snapshot = state.value
        val candidate = snapshot.candidate
        if (snapshot.busy || candidate == null || candidate.sha256 != expectedDigest || snapshot.reviewedDigest != expectedDigest) {
            discardCreatedDocument(uri, "成品或复核已变化，请重新复核后保存")
            return
        }
        execute("写入选定文件") {
            try {
                withContext(Dispatchers.IO) {
                    require(digest(candidate.file) == expectedDigest) { "生成文件已变化，请重新生成和复核" }
                    val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开保存位置")
                    output.use { target ->
                        candidate.file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val size = input.read(buffer)
                                if (size < 0) break
                                target.write(buffer, 0, size)
                            }
                        }
                        target.flush()
                    }
                    val saved = app.contentResolver.openInputStream(uri) ?: error("无法重读已保存文件")
                    val actualDigest = saved.use { input ->
                        val hash = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            hash.update(buffer, 0, size)
                        }
                        hash.digest().joinToString("") { "%02x".format(it) }
                    }
                    check(actualDigest == expectedDigest) { "保存文件校验不一致，请重新保存" }
                }
            } catch (error: Exception) {
                    // Includes cancellation before or after dispatching IO, not just a failed write.
                    val removed = withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }.getOrDefault(false)
                    }
                    if (!removed && error is CancellationException) {
                        cancellationNote = "已取消保存。所选位置可能仍有未完成文件，请手动删除"
                    } else if (!removed) {
                        throw SaveDocumentException("保存未完成。所选位置可能仍有未完成文件，请手动删除", error)
                    }
                    throw error
            }
            message("已完整保存，文件校验一致")
        }
    }

    fun beginSave(digest: String): Boolean {
        val snapshot = state.value
        if (savePickerPending || snapshot.busy || snapshot.candidate?.sha256 != digest ||
            snapshot.reviewedDigest != digest || snapshot.unsavedEdits) return false
        savePickerPending = true
        return true
    }

    fun cancelSavePicker() { savePickerPending = false }

    private fun discardCreatedDocument(uri: Uri, reason: String) {
        viewModelScope.launch {
            val removed = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }.getOrDefault(false)
            }
            message(if (removed) reason else "$reason。所选位置可能仍有未完成文件，请手动删除")
        }
    }

    private class SaveDocumentException(message: String, cause: Throwable) : Exception(message, cause)

    fun deleteSelected() {
        if (state.value.correction != null) return
        if (!requireSavedEdits()) return
        val selected = state.value.selected ?: return
        execute("删除选定素材") {
            if (withContext(Dispatchers.IO) { projectStore.isSourceReferenced(selected.source.sourceId) }) {
                message("项目步骤仍引用这段录屏，请先删除相关步骤或项目；原片已保留")
                return@execute
            }
            invalidateCandidate()
            mutableState.update { it.copy(frame = null, frameReviewId = null) }
            val drafts = withContext(Dispatchers.IO) {
                store.update { current ->
                    val latest = current.firstOrNull { it.source.sourceId == selected.source.sourceId }
                    if (latest != null) {
                        // Delete only this source's derived text and revoke its active OCR
                        // lease before source removal. A late result must never restore it.
                        CandidateOcrStore(app).deleteSource(latest.source.sourceId)
                        val sourceFile = File(app.noBackupFilesDir, latest.source.privateRelativePath)
                        require(sourceFile.canonicalFile.parentFile == File(app.noBackupFilesDir, "sources").canonicalFile)
                        check(!sourceFile.exists() || sourceFile.delete()) { "素材文件清理失败" }
                    }
                    // Retain the record on a filesystem failure, so the user can retry. If this
                    // metadata write fails, its now-missing source record is also safe to retry.
                    current.filterNot { it.source.sourceId == selected.source.sourceId }
                }
            }
            mutableState.update { it.copy(drafts = drafts, selectedId = drafts.lastOrNull()?.source?.sourceId,
                frame = null, frameReviewId = null, candidate = null, candidateImage = null, reviewedDigest = null, watchedDigest = null) }
        }
    }

    fun cancel() { task?.cancel() }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    fun message(text: String) { mutableState.update { it.copy(message = text) } }

    private fun outputDirectory() = File(app.noBackupFilesDir, "candidates/$sessionId")

    private fun execute(label: String, block: suspend () -> Unit) {
        if (state.value.busy || savePickerPending) return
        cancellationNote = null
        mutableState.update { it.copy(busy = true, stage = label, message = null) }
        task = viewModelScope.launch {
            var locked = false
            try {
                operationLock.lock()
                locked = true
                block()
            } catch (cancelled: CancellationException) {
                message(cancellationNote ?: "已取消当前处理")
                throw cancelled
            } catch (error: Exception) {
                // Only known media/validation errors have safe, user-facing messages.
                val safe = when (error) {
                    is com.tapscene.media.MediaImportException -> error.message
                    is com.tapscene.media.FrameDecodeException -> error.message
                    is com.tapscene.media.MediaExportException -> error.message
                    is SaveDocumentException -> error.message
                    is StepCorrectionException -> error.message
                    else -> null
                }
                message(safe ?: "${state.value.stage ?: label}未完成。请检查文件和可用空间后重试")
                if (label == "读取已保存素材") mutableState.update { it.copy(loadFailed = true) }
            } finally {
                // Import's final registration is atomic even if cancellation arrives at that boundary.
                try {
                    if (locked) withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { store.read() }.getOrNull()?.takeUnless { state.value.unsavedEdits || state.value.correction != null }?.let { drafts ->
                            mutableState.update { old ->
                                val selectedId = old.selectedId?.takeIf { id -> drafts.any { it.source.sourceId == id } }
                                    ?: drafts.lastOrNull()?.source?.sourceId
                                val selected = drafts.firstOrNull { it.source.sourceId == selectedId }
                                // A write may commit just before cancellation. Never pair newly loaded
                                // edits or a different source with an old frame or old privacy review.
                                if (old.selected != selected) old.copy(drafts = drafts, selectedId = selectedId,
                                    frame = null, frameReviewId = null, candidate = null, candidateImage = null,
                                    watchedDigest = null, reviewedDigest = null)
                                else old.copy(drafts = drafts, selectedId = selectedId)
                            }
                        }
                        runCatching {
                            val keep = if (closed) null else state.value.candidate?.file?.canonicalPath
                            outputDirectory().listFiles()?.forEach { file ->
                                if (file.canonicalPath != keep) removeCandidate(file)
                            }
                        }.onFailure {
                            mutableState.update { old -> old.copy(message = listOfNotNull(old.message,
                                "临时成品清理失败，请检查本机存储空间").joinToString("。")) }
                        }
                    }
                } finally {
                    if (locked) operationLock.unlock()
                    mutableState.update { it.copy(busy = false, stage = null) }
                }
            }
        }
    }

    override fun onCleared() {
        closed = true
        task?.cancel()
        releaseSafeImage()
        cleanupScope.launch {
            operationLock.withLock {
                runCatching { outputDirectory().listFiles()?.forEach(::removeCandidate); outputDirectory().delete() }
                synchronized(activeSessions) { activeSessions.remove(sessionId) }
            }
        }
        super.onCleared()
    }

    /** Published pixels can still be referenced by Compose/render IO; GC owns their final release. */
    private fun releaseSafeImage() {
        mutableState.update { it.copy(safeImageDraft = null) }
    }

    private fun cleanInactiveSessions() {
        val active = synchronized(activeSessions) { activeSessions.toSet() }
        val root = File(app.noBackupFilesDir, "candidates").canonicalFile
        root.listFiles()?.forEach { directory ->
            if (directory.isDirectory && directory.canonicalFile.parentFile == root &&
                directory.name.matches(Regex("[0-9a-f-]{36}")) && directory.name !in active) {
                directory.listFiles()?.forEach { file ->
                    if (file.canonicalFile.parentFile == directory.canonicalFile && file.isFile &&
                        (file.name.startsWith("candidate-") || file.name.startsWith(".candidate-"))) {
                        check(file.delete() || !file.exists()) { "临时成品清理失败" }
                    }
                }
                directory.delete()
            }
        }
    }

    companion object {
        internal val operationLock = Mutex()
        private val activeSessions = mutableSetOf<String>()
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun removeCandidate(file: File) {
        val directory = outputDirectory().canonicalFile
        if (file.canonicalFile.parentFile == directory &&
            (file.name.startsWith("candidate-") || file.name.startsWith(".candidate-")) && file.isFile) {
            check(file.delete() || !file.exists()) { "临时成品清理失败" }
        }
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
