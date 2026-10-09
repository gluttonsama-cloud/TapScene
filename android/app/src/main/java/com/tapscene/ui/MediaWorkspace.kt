package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.data.SourceDraft
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.DecodedFrame
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceImporter
import com.tapscene.media.VideoFrameDecoder
import java.io.File
import java.security.MessageDigest
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

data class WorkspaceUiState(
    val drafts: List<SourceDraft> = emptyList(),
    val selectedId: String? = null,
    val frame: DecodedFrame? = null,
    val candidate: SafeMediaWriter.CandidateMedia? = null,
    val candidateImage: Bitmap? = null,
    val watchedDigest: String? = null,
    val reviewedDigest: String? = null,
    val busy: Boolean = false,
    val stage: String? = null,
    val message: String? = null,
    val loadFailed: Boolean = false,
) {
    val selected: SourceDraft? get() = drafts.firstOrNull { it.source.sourceId == selectedId }
}

class MediaWorkspace(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = WorkspaceStore(app)
    private val importer = SourceImporter(app)
    private val decoder = VideoFrameDecoder(app)
    private val writer = SafeMediaWriter(app)
    private val mutableState = MutableStateFlow(WorkspaceUiState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private var savePickerPending = false
    private var cancellationNote: String? = null

    init { reload() }

    fun reload() = execute("读取已保存素材") {
        invalidateCandidate()
        val drafts = withContext(Dispatchers.IO) { store.read() }
        mutableState.update { it.copy(drafts = drafts, selectedId = drafts.lastOrNull()?.source?.sourceId,
            frame = null, loadFailed = false) }
    }

    fun importVideo(uri: Uri) {
        if (state.value.loadFailed) return
        if (state.value.drafts.size >= 3) {
            message("最多保留 3 段录屏，请先删除不再需要的素材")
            return
        }
        execute("复制并检查录屏") {
            val previous = state.value.drafts
            val source = importer.importSource(uri) { imported ->
                store.write(previous + SourceDraft(imported))
            }
            val drafts = withContext(Dispatchers.IO) { store.read() }
            mutableState.update {
                it.copy(drafts = drafts, selectedId = source.sourceId, frame = null,
                    candidate = null, candidateImage = null, reviewedDigest = null, watchedDigest = null)
            }
            message("录屏已保存到本机")
        }
    }

    fun selectSource(id: String) {
        if (state.value.busy || savePickerPending || state.value.selectedId == id) return
        invalidateCandidate()
        mutableState.update { it.copy(selectedId = id, frame = null, candidate = null,
            candidateImage = null, watchedDigest = null, reviewedDigest = null, message = null) }
    }

    fun takeFrame(timeUs: Long) {
        val selected = state.value.selected ?: return
        execute("解码实际帧") {
            invalidateCandidate()
            val decoded = decoder.decode(selected.source, timeUs)
            val drafts = state.value.drafts.map {
                if (it.source.sourceId == selected.source.sourceId) it.copy(frameTimeUs = decoded.presentationTimeUs) else it
            }
            withContext(Dispatchers.IO) { store.write(drafts) }
            mutableState.update { it.copy(drafts = drafts, frame = decoded, candidate = null,
                candidateImage = null, reviewedDigest = null, watchedDigest = null) }
        }
    }

    fun addMask(mask: OpaqueMask) {
        val selected = state.value.selected ?: return
        if (state.value.frame == null || selected.masks.size >= 20) return
        updateMasks(selected.masks + mask)
    }

    fun undoMask() {
        val selected = state.value.selected ?: return
        updateMasks(selected.masks.dropLast(1))
    }

    private fun updateMasks(masks: List<OpaqueMask>) {
        val selectedId = state.value.selectedId ?: return
        execute("保存遮挡") {
            invalidateCandidate()
            val drafts = state.value.drafts.map {
                if (it.source.sourceId == selectedId) it.copy(masks = masks) else it
            }
            withContext(Dispatchers.IO) { store.write(drafts) }
            mutableState.update { it.copy(drafts = drafts, candidate = null, candidateImage = null,
                watchedDigest = null, reviewedDigest = null) }
        }
    }

    fun makeImage() {
        val snapshot = state.value
        val frame = snapshot.frame ?: return
        val selected = snapshot.selected ?: return
        execute("生成遮挡图片") {
            invalidateCandidate()
            val candidate = writer.writePng(frame.bitmap, selected.masks, outputDirectory(), ::onStage)
            val image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(candidate.file.path) }
                ?: error("无法重读生成的图片")
            mutableState.update { it.copy(candidate = candidate, candidateImage = image) }
        }
    }

    fun makeVideo(startUs: Long, endUs: Long) {
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
            snapshot.reviewedDigest != digest) return false
        savePickerPending = true
        return true
    }

    fun cancelSavePicker() { savePickerPending = false }

    private fun discardCreatedDocument(uri: Uri, reason: String) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }.getOrDefault(false)
            }
            message(if (removed) reason else "$reason。所选位置可能仍有未完成文件，请手动删除")
        }
    }

    private class SaveDocumentException(message: String, cause: Throwable) : Exception(message, cause)

    fun deleteSelected() {
        val selected = state.value.selected ?: return
        execute("删除选定素材") {
            invalidateCandidate()
            mutableState.update { it.copy(frame = null) }
            val drafts = state.value.drafts.filterNot { it.source.sourceId == selected.source.sourceId }
            withContext(Dispatchers.IO) {
                val sourceFile = File(app.noBackupFilesDir, selected.source.privateRelativePath)
                require(sourceFile.canonicalFile.parentFile == File(app.noBackupFilesDir, "sources").canonicalFile)
                check(!sourceFile.exists() || sourceFile.delete()) { "素材文件清理失败" }
                // Retain the record on a filesystem failure, so the user can retry. If this
                // metadata write fails, its now-missing source record is also safe to retry.
                store.write(drafts)
            }
            mutableState.update { it.copy(drafts = drafts, selectedId = drafts.lastOrNull()?.source?.sourceId,
                frame = null, candidate = null, candidateImage = null, reviewedDigest = null, watchedDigest = null) }
        }
    }

    fun cancel() { task?.cancel() }
    fun message(text: String) { mutableState.update { it.copy(message = text) } }

    private fun outputDirectory() = File(app.noBackupFilesDir, "candidates")

    private fun execute(label: String, block: suspend () -> Unit) {
        if (state.value.busy || savePickerPending) return
        cancellationNote = null
        mutableState.update { it.copy(busy = true, stage = label, message = null) }
        task = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                message(cancellationNote ?: "已取消当前处理")
                throw cancelled
            } catch (error: Exception) {
                // Only known media/validation errors have safe, user-facing messages.
                val safe = when (error) {
                    is com.tapscene.media.MediaImportException -> error.message
                    is com.tapscene.media.FrameDecodeException -> error.message
                    is SaveDocumentException -> error.message
                    else -> null
                }
                message(safe ?: "${state.value.stage ?: label}未完成。请检查文件和可用空间后重试")
                if (label == "读取已保存素材") mutableState.update { it.copy(loadFailed = true) }
            } finally {
                // Import's final registration is atomic even if cancellation arrives at that boundary.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { store.read() }.getOrNull()?.let { drafts ->
                        mutableState.update { old ->
                            val selectedId = old.selectedId?.takeIf { id -> drafts.any { it.source.sourceId == id } }
                                ?: drafts.lastOrNull()?.source?.sourceId
                            val selected = drafts.firstOrNull { it.source.sourceId == selectedId }
                            // A write may commit just before cancellation. Never pair newly loaded
                            // edits or a different source with an old frame or old privacy review.
                            if (old.selected != selected) old.copy(drafts = drafts, selectedId = selectedId,
                                frame = null, candidate = null, candidateImage = null,
                                watchedDigest = null, reviewedDigest = null)
                            else old.copy(drafts = drafts, selectedId = selectedId)
                        }
                    }
                    runCatching {
                        val keep = state.value.candidate?.file?.canonicalPath
                        outputDirectory().listFiles()?.forEach { file ->
                            if (file.canonicalPath != keep) removeCandidate(file)
                        }
                    }.onFailure { message("处理已结束，临时成品清理失败。请检查本机存储空间") }
                }
                mutableState.update { it.copy(busy = false, stage = null) }
            }
        }
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
