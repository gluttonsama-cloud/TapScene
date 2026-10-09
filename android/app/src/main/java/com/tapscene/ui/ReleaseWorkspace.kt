package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.data.ReleaseCandidate
import com.tapscene.data.ReleaseStore
import com.tapscene.data.ReleaseSummary
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.packageformat.ViewerTraversal
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A session visits explicit edges; list position is never a playback action. */
data class ReleasePlayback(
    val scene: ViewerScene,
    val candidateId: String?,
    val currentStateId: String,
    val history: List<String>,
    val ended: Boolean = false,
    val endLabel: String? = null,
    val endEdgeId: String? = null,
    val matchingHotspotIds: List<String> = emptyList(),
    val visitedEdgeIds: Set<String> = emptySet(),
    val completedFromStart: Boolean = false,
) {
    val canGoBack: Boolean get() = history.size > 1 || endEdgeId != null
    val currentState: ViewerScene.State? get() = scene.states.firstOrNull { it.id == currentStateId }
}

data class ReleaseUiState(
    val releases: List<ReleaseSummary> = emptyList(),
    val pendingCandidates: List<ReleaseCandidate> = emptyList(),
    val candidate: ReleaseCandidate? = null,
    val reviewStateId: String? = null,
    val reviewBitmap: Bitmap? = null,
    val player: ReleasePlayback? = null,
    val playerBitmap: Bitmap? = null,
    val busy: Boolean = false,
    val stage: String? = null,
    val message: String? = null,
    val lastSealedId: String? = null,
    val lastImportedId: String? = null,
    val exportFile: File? = null,
)

/**
 * Fixed candidate/release IO is independent from mutable draft state. Only exact validated PNG
 * bytes reach the canvas. A failed decode never records an edge or changes the actual history.
 * No package text is interpreted as HTML, a URL, an expression or executable code.
 */
class ReleaseWorkspace(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = ReleaseStore(application)
    private val mutableState = MutableStateFlow(ReleaseUiState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private var exportDigest: String? = null
    private var exportLength: Long? = null
    private var pendingExportFile: File? = null
    private var savePickerPending = false
    private var cancellationNote: String? = null

    init { reloadLibrary() }

    fun reloadLibrary() = execute("读取演示库") {
        val items = withContext(Dispatchers.IO) { store.listReleases() }
        val candidates = withContext(Dispatchers.IO) { store.listCandidates() }
        mutableState.update { it.copy(releases = items, pendingCandidates = candidates) }
    }

    fun openReview(projectId: String) = execute("读取待复核成品") {
        mutableState.update { it.copy(candidate = null, reviewStateId = null, reviewBitmap = null) }
        val candidate = withContext(Dispatchers.IO) { store.readCandidate(projectId) }
        showCandidate(candidate)
    }

    fun openReviewById(candidateId: String) = execute("恢复固定成品复核") {
        mutableState.update { it.copy(candidate = null, reviewStateId = null, reviewBitmap = null) }
        showCandidate(withContext(Dispatchers.IO) { store.readCandidateById(candidateId) })
    }

    fun discardCandidate(candidateId: String) = execute("删除未封存副本") {
        withContext(Dispatchers.IO) { store.discardCandidate(candidateId) }
        if (state.value.candidate?.id == candidateId) mutableState.update {
            it.copy(candidate = null, reviewStateId = null, reviewBitmap = null, player = null, playerBitmap = null)
        }
    }

    fun createCandidate(projectId: String, revision: Long, replaceExisting: Boolean = false) = execute("固定修订并检查实际图片") {
        val candidate = withContext(Dispatchers.IO) { store.createCandidate(projectId, revision, replaceExisting) }
        mutableState.update { it.copy(player = null, playerBitmap = null, lastSealedId = null) }
        showCandidate(candidate)
    }

    private suspend fun showCandidate(candidate: ReleaseCandidate?) {
        mutableState.update { it.copy(candidate = candidate, reviewStateId = null, reviewBitmap = null) }
        val first = candidate?.scene?.states?.firstOrNull { it.id !in candidate.reviewedStateIds }
            ?: candidate?.scene?.states?.firstOrNull()
        if (candidate != null && first != null) {
            val bitmap = decode(candidate.scene, first.id, candidate.id)
            mutableState.update { it.copy(reviewStateId = first.id, reviewBitmap = bitmap) }
        }
    }

    fun selectReviewState(id: String) {
        val candidate = state.value.candidate ?: return
        if (candidate.scene.states.none { it.id == id }) return
        execute("读取待复核画面") {
            mutableState.update { it.copy(reviewStateId = id, reviewBitmap = null) }
            val bitmap = decode(candidate.scene, id, candidate.id)
            mutableState.update { it.copy(reviewBitmap = bitmap) }
        }
    }

    fun confirmReviewState() {
        val current = state.value
        val candidate = current.candidate ?: return
        val id = current.reviewStateId ?: return
        if (current.reviewBitmap == null) return
        execute("保存实际画面与文字复核") {
            val updated = withContext(Dispatchers.IO) { store.reviewState(candidate.id, candidate.contentDigest, id) }
            mutableState.update { it.copy(candidate = updated) }
            val next = updated.scene.states.firstOrNull { it.id !in updated.reviewedStateIds }
            if (next != null) {
                mutableState.update { it.copy(reviewStateId = next.id, reviewBitmap = null) }
                val bitmap = decode(updated.scene, next.id, updated.id)
                mutableState.update { it.copy(reviewBitmap = bitmap) }
            } else message("所有步骤的画面与文字已确认")
        }
    }

    fun confirmSummary() {
        val candidate = state.value.candidate ?: return
        execute("保存项目文字复核") {
            val updated = withContext(Dispatchers.IO) { store.reviewSummary(candidate.id, candidate.contentDigest) }
            mutableState.update { it.copy(candidate = updated) }
        }
    }

    fun confirmFileList() {
        val candidate = state.value.candidate ?: return
        execute("核对交付文件范围") {
            val updated = withContext(Dispatchers.IO) { store.reviewFileList(candidate.id, candidate.contentDigest) }
            mutableState.update { it.copy(candidate = updated) }
        }
    }

    fun seal() {
        val candidate = state.value.candidate ?: return
        execute("重检并封存固定版本") {
            val sealed = withContext(Dispatchers.IO) { store.seal(candidate.id, candidate.contentDigest) }
            val items = withContext(Dispatchers.IO) { store.listReleases() }
            mutableState.update { it.copy(releases = items, lastSealedId = sealed.id, candidate = null,
                reviewStateId = null, reviewBitmap = null, player = null, playerBitmap = null) }
            message("版本已封存，可保存离线观看包")
        }
    }

    fun startCandidatePreview() {
        val candidate = state.value.candidate ?: return
        execute("读取固定成品起点") {
            mutableState.update { it.copy(player = null, playerBitmap = null) }
            val start = candidate.scene.states.first { it.id == candidate.scene.startStateId }
            val bitmap = decode(candidate.scene, start.id, candidate.id)
            val updated = withContext(Dispatchers.IO) { store.recordTraversal(candidate.id, candidate.contentDigest, null, false) }
            mutableState.update { it.copy(candidate = updated, playerBitmap = bitmap,
                player = initialPlayback(candidate.scene, candidate.id, updated.visitedEdgeIds, updated.completedPath)) }
        }
    }

    fun openRelease(id: String) = execute("读取离线演示") {
        mutableState.update { it.copy(player = null, playerBitmap = null) }
        val scene = withContext(Dispatchers.IO) { store.loadRelease(id) }
        val bitmap = decode(scene, scene.startStateId, null)
        mutableState.update { it.copy(playerBitmap = bitmap, player = initialPlayback(scene, null)) }
    }

    fun tapPlayer(x: Float, y: Float) {
        val current = state.value
        val player = current.player ?: return
        if (current.busy || current.playerBitmap == null || player.ended ||
            !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return
        val matches = player.scene.hotspots.filter { spot ->
            spot.stateId == player.currentStateId && x >= spot.rect.x && y >= spot.rect.y &&
                x <= spot.rect.x + spot.rect.width && y <= spot.rect.y + spot.rect.height
        }
        when (matches.size) {
            0 -> message("此处没有热点，可使用画面下方的动作")
            1 -> player.scene.edges.firstOrNull { it.hotspotId == matches.single().id }?.let { chooseEdge(it.id) }
            else -> mutableState.update { it.copy(player = player.copy(matchingHotspotIds = matches.map { spot -> spot.id })) }
        }
    }

    fun dismissMatches() { mutableState.update { it.copy(player = it.player?.copy(matchingHotspotIds = emptyList())) } }

    fun chooseEdge(id: String) {
        val current = state.value
        val player = current.player ?: return
        if (current.busy || current.playerBitmap == null || player.ended) return
        val edge = player.scene.edges.firstOrNull { it.id == id && it.fromStateId == player.currentStateId } ?: return
        if (player.history.size >= MAX_VISITS && edge.toStateId != null) {
            message("已观看 256 次，请点重来开始新一轮")
            return
        }
        execute("读取动作目标") {
            val next = player.traversal().advance(player.scene, id)
            val bitmap = if (edge.toStateId != null) decode(player.scene, next.currentStateId, player.candidateId) else current.playerBitmap
            val candidate = player.candidateId?.let { candidateId ->
                withContext(Dispatchers.IO) { store.recordTraversal(candidateId, ViewerPackageCodec.contentDigest(player.scene), id, false) }
            }
            mutableState.update { it.copy(candidate = candidate ?: it.candidate, playerBitmap = bitmap,
                player = player.applyTraversal(next).copy(visitedEdgeIds = candidate?.visitedEdgeIds ?: next.visitedEdgeIds,
                    completedFromStart = candidate?.completedPath ?: next.completedFromStart)) }
        }
    }

    fun previous() {
        val current = state.value
        val player = current.player ?: return
        if (current.busy || !player.canGoBack) return
        execute("返回实际访问的上一步") {
            val previous = player.traversal().previous(player.scene)
            val bitmap = decode(player.scene, previous.currentStateId, player.candidateId)
            val candidate = player.candidateId?.let { id -> withContext(Dispatchers.IO) {
                store.rewindTraversal(id, ViewerPackageCodec.contentDigest(player.scene))
            } }
            mutableState.update { it.copy(candidate = candidate ?: it.candidate, playerBitmap = bitmap,
                player = player.applyTraversal(previous)) }
        }
    }

    fun restart() {
        val player = state.value.player ?: return
        execute("从起点重来") {
            val restarted = player.traversal().restart(player.scene)
            val bitmap = decode(player.scene, restarted.currentStateId, player.candidateId)
            val candidate = player.candidateId?.let { id -> withContext(Dispatchers.IO) {
                store.recordTraversal(id, ViewerPackageCodec.contentDigest(player.scene), null, false)
            } }
            mutableState.update { it.copy(candidate = candidate ?: it.candidate, playerBitmap = bitmap,
                player = player.applyTraversal(restarted).copy(visitedEdgeIds = candidate?.visitedEdgeIds ?: restarted.visitedEdgeIds,
                    completedFromStart = candidate?.completedPath ?: restarted.completedFromStart)) }
        }
    }

    fun closePlayer() {
        if (state.value.busy) { cancel(); return }
        mutableState.update { it.copy(player = null, playerBitmap = null) }
    }

    fun importPackage(uri: Uri) = execute("隔离验证离线观看包") {
        mutableState.update { it.copy(lastImportedId = null) }
        val imported = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { store.importPackage(it) }
                ?: error("无法读取选定文件")
        }
        val items = withContext(Dispatchers.IO) { store.listReleases() }
        mutableState.update { it.copy(releases = items, lastImportedId = imported.id) }
        message("完整观看包已加入演示库；文件校验不代表来源或隐私认证")
    }

    fun clearImportResult() { mutableState.update { it.copy(lastImportedId = null) } }

    fun deleteRelease(id: String) = execute("删除本机观看副本") {
        withContext(Dispatchers.IO) { store.deleteRelease(id) }
        val items = withContext(Dispatchers.IO) { store.listReleases() }
        mutableState.update { it.copy(releases = items,
            player = it.player?.takeUnless { player -> player.candidateId == null && player.scene.releaseId == id },
            playerBitmap = it.playerBitmap.takeUnless { _ -> it.player?.scene?.releaseId == id },
            lastSealedId = it.lastSealedId?.takeUnless { selected -> selected == id }) }
    }

    fun prepareExport(id: String) = execute("生成并验证离线观看包") {
        exportDigest = null; exportLength = null; pendingExportFile = null
        mutableState.update { it.copy(exportFile = null) }
        val file = withContext(Dispatchers.IO) { store.exportRelease(id) }
        val digest = withContext(Dispatchers.IO) { ViewerPackageCodec.sha256(file) }
        exportDigest = digest; exportLength = file.length()
        mutableState.update { it.copy(exportFile = file) }
    }

    fun beginExportPicker(): Boolean {
        if (state.value.busy || savePickerPending || state.value.exportFile == null || exportDigest == null) return false
        savePickerPending = true
        pendingExportFile = state.value.exportFile
        mutableState.update { it.copy(exportFile = null) }
        return true
    }

    fun cancelExportPicker() {
        savePickerPending = false; exportDigest = null; exportLength = null; pendingExportFile = null
        mutableState.update { it.copy(exportFile = null) }
    }

    /** Only called for a document just created by this screen's CreateDocument contract. */
    fun saveExport(uri: Uri) {
        savePickerPending = false
        val file = pendingExportFile
        val digest = exportDigest
        val length = exportLength
        if (state.value.busy || file == null || digest == null || length == null) {
            viewModelScope.launch { discardCreatedDocument(uri, "导出文件已变化，请重新选择保存") }
            return
        }
        execute("完整写入所选文件") {
            try {
                withContext(Dispatchers.IO) {
                    check(file.length() == length && ViewerPackageCodec.sha256(file) == digest) { "待保存的包已变化，请重新导出" }
                    val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开保存位置")
                    output.use { target -> file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            target.write(buffer, 0, size)
                        }
                        target.flush()
                    } }
                    val hash = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    val saved = app.contentResolver.openInputStream(uri) ?: error("无法重读所选文件")
                    saved.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            count += size
                            check(count <= length) { "已保存文件长度不一致" }
                            hash.update(buffer, 0, size)
                        }
                    }
                    check(count == length && hash.digest().joinToString("") { "%02x".format(it) } == digest) { "已保存文件摘要不一致" }
                }
                cancelExportPicker()
                message("离线观看包已完整保存，回读校验一致")
            } catch (error: Exception) {
                val removed = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }.getOrDefault(false)
                }
                if (!removed) cancellationNote = "保存未完成，所选位置可能仍有半包，请手动删除"
                cancelExportPicker()
                if (error is CancellationException) throw error
                throw DocumentSaveException(cancellationNote ?: "保存未完成，请重新选择位置")
            }
        }
    }

    private suspend fun discardCreatedDocument(uri: Uri, reason: String) {
        val removed = withContext(NonCancellable + Dispatchers.IO) {
            runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }.getOrDefault(false)
        }
        message(if (removed) reason else "$reason。所选位置可能仍有空文件，请手动删除")
    }

    private suspend fun decode(scene: ViewerScene, stateId: String, candidateId: String?): Bitmap = withContext(Dispatchers.IO) {
        val item = scene.states.firstOrNull { it.id == stateId } ?: error("观看画面缺失")
        val file = if (candidateId != null) store.candidateAssetFile(candidateId, stateId) else store.releaseAssetFile(scene.releaseId, stateId)
        currentCoroutineContext().ensureActive()
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
        }) ?: error("实际图片无法解码，未切换画面")
        if (bitmap.width != item.width || bitmap.height != item.height) {
            bitmap.recycle()
            error("实际画面尺寸不一致，未切换画面")
        }
        currentCoroutineContext().ensureActive()
        bitmap
    }

    private fun initialPlayback(scene: ViewerScene, candidateId: String?, visited: Set<String> = emptySet(), completed: Boolean = false): ReleasePlayback {
        val start = ViewerTraversal.start(scene)
        return ReleasePlayback(scene, candidateId, start.currentStateId, start.history, start.ended,
            start.endLabel, start.endEdgeId, visitedEdgeIds = visited,
            completedFromStart = completed || start.completedFromStart)
    }

    private fun ReleasePlayback.traversal() = ViewerTraversal(currentStateId, history, ended, endLabel,
        endEdgeId, visitedEdgeIds, completedFromStart)

    private fun ReleasePlayback.applyTraversal(next: ViewerTraversal) = copy(currentStateId = next.currentStateId,
        history = next.history, ended = next.ended, endLabel = next.endLabel, endEdgeId = next.endEdgeId,
        matchingHotspotIds = emptyList(), visitedEdgeIds = next.visitedEdgeIds, completedFromStart = next.completedFromStart)

    fun cancel() { task?.cancel() }
    fun message(text: String) { mutableState.update { it.copy(message = text) } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    private fun execute(label: String, block: suspend () -> Unit) {
        if (state.value.busy || savePickerPending) return
        cancellationNote = null
        mutableState.update { it.copy(busy = true, stage = label, message = null) }
        task = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) {
                mutableState.update { it.copy(player = null, playerBitmap = null) }
                message(cancellationNote ?: "已取消当前处理；完整保存的版本保留")
                throw cancelled
            } catch (error: Exception) {
                val detail = when (error) {
                    is DocumentSaveException -> error.message
                    is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf { it.length <= 240 && '/' !in it && '\\' !in it }
                    is IOException -> null
                    else -> null
                }
                message(detail ?: "$label 未完成，请检查文件和可用空间后重试")
            } finally {
                // A cancellation can arrive after an atomic commit. Re-read library truth without
                // deleting the successfully published version or manufacturing a success message.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { store.listReleases() }.getOrNull()?.let { items -> mutableState.update { it.copy(releases = items) } }
                    runCatching { store.listCandidates() }.getOrNull()?.let { candidates -> mutableState.update { it.copy(pendingCandidates = candidates) } }
                }
                mutableState.update { it.copy(busy = false, stage = null) }
            }
        }
    }

    private class DocumentSaveException(message: String) : Exception(message)
    private companion object { const val MAX_VISITS = 256 }
}
