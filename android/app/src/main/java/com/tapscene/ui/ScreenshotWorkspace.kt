package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.data.ProjectLimits
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReviewedStepInput
import com.tapscene.data.StepOrigin
import com.tapscene.media.ImportedScreenshot
import com.tapscene.media.MediaImportException
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.ScreenshotImporter
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
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** No video, safe-base lease or source timestamp is synthesized for an external screenshot. */
data class ScreenshotEditorDraft(val sessionId: String, val bitmap: Bitmap?,
    val masks: List<OpaqueMask> = emptyList(), val creatingProject: Boolean = false,
    val reviewLocked: Boolean = false)

data class ScreenshotUiState(
    val sessionId: String? = null,
    val targetProjectId: String? = null,
    val creatingProject: Boolean = false,
    val draft: ScreenshotEditorDraft? = null,
    val media: WorkspaceUiState = WorkspaceUiState(),
    val completed: ProjectSnapshot? = null,
    val saveOutcomeUnknown: Boolean = false,
) {
    fun canConfirm(digest: String): Boolean = sessionId != null && !media.busy && completed == null &&
        draft?.bitmap?.isRecycled == false && media.candidate?.mimeType == "image/png" &&
        media.candidate.sha256 == digest && media.candidateImage?.isRecycled == false
}

/** One selected image and one explicit actual-output confirmation. Never creates an empty project. */
class ScreenshotWorkspace(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = ProjectStore(app)
    private val importer = ScreenshotImporter(app)
    private val writer = SafeMediaWriter(app)
    private val mutableState = MutableStateFlow(ScreenshotUiState())
    val state = mutableState.asStateFlow()
    private var lease: ImportedScreenshot? = null
    private var task: Job? = null
    private val ownerId = UUID.randomUUID().toString()
    private val directory = File(app.noBackupFilesDir, "screenshot-outputs/$ownerId")

    init {
        synchronized(active) { active.add(ownerId) }
        viewModelScope.launch(Dispatchers.IO) { cleanupInactive() }
    }

    fun importScreenshot(uri: Uri, projectId: String?): Boolean = startImport(projectId) { importer.importScreenshot(uri) }

    /** Keeps the selected-input reader inside one owned operation, including interrupted reads. */
    internal fun startImport(projectId: String?, readSelected: suspend () -> ImportedScreenshot): Boolean {
        if (state.value.media.busy || state.value.sessionId != null) return false
        val id = UUID.randomUUID().toString()
        mutableState.value = ScreenshotUiState(id, projectId ?: UUID.randomUUID().toString(), projectId == null,
            ScreenshotEditorDraft(id, null, creatingProject = projectId == null))
        execute("读取截图") {
            if (projectId != null) withContext(Dispatchers.IO) {
                val project = store.readProject(projectId) ?: error("项目已不存在。")
                require(project.steps.size < ProjectLimits.MAX_STEPS) { "每个项目最多 40 个步骤。" }
                require(store.screenshotCount(projectId) < ProjectLimits.MAX_SCREENSHOTS) {
                    "每个项目最多 20 张原截图，请先删除不再需要的截图步骤。"
                }
            }
            val imported = readSelected()
            // Return-boundary cancellation is handled by importer; only this session publishes it.
            lease = imported
            mutableState.update { it.copy(draft = it.draft?.copy(bitmap = imported.bitmap)) }
        }
        return true
    }

    fun addMask(mask: OpaqueMask) {
        val draft = state.value.draft ?: return
        if (state.value.media.busy || state.value.saveOutcomeUnknown || draft.bitmap == null || draft.masks.size >= 20) return
        invalidateCandidate()
        mutableState.update { it.copy(draft = draft.copy(masks = draft.masks + mask)) }
    }

    fun undoMask() {
        val draft = state.value.draft ?: return
        if (state.value.media.busy || state.value.saveOutcomeUnknown || draft.masks.isEmpty()) return
        invalidateCandidate()
        mutableState.update { it.copy(draft = draft.copy(masks = draft.masks.dropLast(1))) }
    }

    fun makeImage() {
        val snapshot = state.value
        if (snapshot.saveOutcomeUnknown) return
        val bitmap = snapshot.draft?.bitmap?.takeUnless { it.isRecycled } ?: return
        val masks = snapshot.draft.masks.toList()
        execute("生成实际图片") {
            invalidateCandidate()
            var generated: SafeMediaWriter.CandidateMedia? = null
            var decoded: Bitmap? = null
            try {
                val result = writer.writePng(bitmap, masks, directory) { stage ->
                    mutableState.update { it.copy(media = it.media.copy(stage = when (stage) {
                        SafeMediaWriter.Stage.PREPARING -> "准备截图"
                        SafeMediaWriter.Stage.RENDERING -> "生成安全图片"
                        SafeMediaWriter.Stage.VERIFYING -> "检查实际输出"
                        SafeMediaWriter.Stage.FINALIZING -> "准备复核"
                    })) }
                }
                generated = result
                withContext(Dispatchers.IO) {
                    decoded = BitmapFactory.decodeFile(result.file.path, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
                    }) ?: error("实际图片无法读取。")
                    coroutineContext.ensureActive()
                }
                mutableState.update { it.copy(media = it.media.copy(candidate = result, candidateImage = decoded)) }
                generated = null; decoded = null // Published bitmaps are GC-owned, including during Compose disposal.
            } finally {
                decoded?.recycle()
                generated?.file?.let { file -> withContext(NonCancellable + Dispatchers.IO) { deleteOutput(file) } }
            }
        }
    }

    fun confirm(digest: String) {
        val snapshot = state.value
        if (!snapshot.canConfirm(digest)) return
        val source = lease?.source ?: return
        val output = snapshot.media.candidate ?: return
        val input = ReviewedStepInput(output.file, output.sha256, output.width, output.height,
            StepOrigin.ImportedImage(source), snapshot.draft!!.masks.toList(), "screenshot-${snapshot.sessionId}")
        val projectId = requireNotNull(snapshot.targetProjectId)
        execute("加入已复核截图") {
            var committed: ProjectSnapshot? = null
            try {
                committed = withContext(Dispatchers.IO) {
                    store.addReviewedScreenshot(projectId, input, if (snapshot.creatingProject) "截图演示" else null)
                }
            } finally {
                // Cancellation at a database boundary must report the actual persisted result.
                withContext(NonCancellable + Dispatchers.IO) {
                    val reread = runCatching { store.readProject(projectId) }
                    val saved = reread.getOrNull()?.takeIf { project -> project.steps.any { matchesScreenshotCommit(it, input) } } ?: committed
                    if (saved != null) mutableState.update { it.copy(completed = saved, saveOutcomeUnknown = false,
                        media = it.media.copy(message = "截图已加入步骤")) }
                    else if (reread.isFailure || reread.getOrNull()?.steps?.any { it.captureId == input.captureId } == true)
                        mutableState.update { it.copy(saveOutcomeUnknown = true, draft = it.draft?.copy(reviewLocked = true),
                            media = it.media.copy(message = "暂时无法确认本次输出的保存结果；请原样重试确认，或返回项目检查。")) }
                }
            }
        }
    }

    /** A failed import is reselected through the system picker; never retain external URI access. */
    fun close(): Boolean {
        if (state.value.media.busy) return false
        val previous = lease
        val oldOutput = state.value.media.candidate?.file
        lease = null
        mutableState.value = ScreenshotUiState()
        cleanupScope.launch {
            runCatching { previous?.source?.let(importer::discard) }
            runCatching { oldOutput?.let(::deleteOutput) }
            directory.delete() // Only an empty old directory can be removed; a newer session is safe.
        }
        return true
    }

    fun cancel() { task?.cancel() }

    private fun invalidateCandidate() {
        val previous = state.value.media.candidate?.file
        mutableState.update { it.copy(media = it.media.copy(candidate = null, candidateImage = null)) }
        if (previous != null) cleanupScope.launch { runCatching { deleteOutput(previous) } }
    }

    private fun execute(label: String, block: suspend () -> Unit) {
        if (state.value.media.busy || state.value.sessionId == null) return
        mutableState.update { it.copy(media = it.media.copy(busy = true, stage = label, message = null)) }
        task = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) {
                if (state.value.completed == null && !state.value.saveOutcomeUnknown) mutableState.update { it.copy(media = it.media.copy(message = "已取消处理，尚未加入步骤。")) }
                throw cancelled
            } catch (failure: Exception) {
                val message = if (failure is MediaImportException || failure is IllegalArgumentException) failure.message else null
                if (!state.value.saveOutcomeUnknown) mutableState.update {
                    it.copy(media = it.media.copy(message = message ?: "处理未完成，请重试或重新选择截图。"))
                }
            } finally {
                mutableState.update { it.copy(media = it.media.copy(busy = false, stage = null)) }
            }
        }
    }

    private fun deleteOutput(file: File) {
        check(file.canonicalFile == file.absoluteFile && file.parentFile == directory &&
            (file.name.matches(Regex("candidate-[0-9a-f-]{36}\\.png")) || file.name.matches(Regex("\\.candidate-[0-9]+\\.png\\.part"))))
        check(!file.exists() || file.isFile && file.delete())
    }

    private fun cleanupOutputs() { directory.listFiles()?.forEach { runCatching { deleteOutput(it) } }; directory.delete() }
    private fun cleanupInactive() {
        val parent = directory.parentFile ?: return
        synchronized(active) {
            parent.listFiles()?.filter { it.name !in active && isId(it.name) && it.canonicalFile == it.absoluteFile && it.isDirectory }
                ?.forEach { folder ->
                    folder.listFiles()?.filter { it.canonicalFile == it.absoluteFile && it.isFile &&
                        (it.name.matches(Regex("candidate-[0-9a-f-]{36}\\.png")) || it.name.matches(Regex("\\.candidate-[0-9]+\\.png\\.part"))) }
                        ?.forEach { it.delete() }
                    folder.delete()
                }
        }
    }
    private fun releaseFiles() {
        runCatching { lease?.source?.let(importer::discard) }; lease = null
        cleanupOutputs()
        synchronized(active) { active.remove(ownerId) }
    }
    override fun onCleared() {
        val running = task
        running?.cancel()
        cleanupScope.launch { running?.join(); releaseFiles() }
        super.onCleared()
    }
    companion object {
        internal fun matchesScreenshotCommit(step: com.tapscene.data.ProjectStep, input: ReviewedStepInput): Boolean {
            val expected = (input.origin as? StepOrigin.ImportedImage)?.source ?: return false
            val actual = (step.origin as? StepOrigin.ImportedImage)?.source ?: return false
            val extension = if (expected.metadata.mime == "image/png") "png" else "jpg"
            return step.captureId == input.captureId && step.asset.sha256 == input.sha256 &&
                step.asset.width == input.width && step.asset.height == input.height && step.masks == input.masks &&
                step.evidenceKind == "authored" && actual.privateRelativePath == "image-sources/${expected.sourceId}/original.$extension" &&
                actual.copy(privateRelativePath = expected.privateRelativePath) == expected
        }
        private val active = mutableSetOf<String>()
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private fun isId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
