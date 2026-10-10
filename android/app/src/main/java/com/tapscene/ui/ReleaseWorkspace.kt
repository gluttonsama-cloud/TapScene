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
import com.tapscene.sharing.OfflineShare
import com.tapscene.packageformat.RenderPlan
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
    val traversalState: ViewerTraversal? = null,
    val video: LocalVideoRun? = null,
) {
    val canGoBack: Boolean get() = traversalState?.pendingEdgeId != null || history.size > 1 || endEdgeId != null
    val currentState: ViewerScene.State? get() = scene.states.firstOrNull { it.id == currentStateId }
}

data class ReleaseVideoReview(
    val candidateId: String,
    val contentDigest: String,
    val assetId: String,
    val assetHash: String,
    val video: LocalVideoRun,
    val watchedCompletely: Boolean = false,
)

data class AiPackageConfiguration(
    val scene: ViewerScene,
    val visits: List<RenderPlan.Visit>,
    val effects: List<RenderPlan.Effect> = emptyList(),
    val width: Int = 1080,
    val height: Int = 1920,
    val fromDraft: Boolean = false,
) {
    fun resolve(): RenderPlan = RenderPlan.build(scene, width, height, visits, effects)
}

data class ReleaseUiState(
    val releases: List<ReleaseSummary> = emptyList(),
    val pendingCandidates: List<ReleaseCandidate> = emptyList(),
    val candidate: ReleaseCandidate? = null,
    val reviewStateId: String? = null,
    val reviewBitmap: Bitmap? = null,
    val reviewRegionId: String? = null,
    val reviewRegionBitmap: Bitmap? = null,
    val reviewVideo: ReleaseVideoReview? = null,
    val player: ReleasePlayback? = null,
    val playerBitmap: Bitmap? = null,
    val busy: Boolean = false,
    val stage: String? = null,
    val message: String? = null,
    val lastSealedId: String? = null,
    val lastImportedId: String? = null,
    val exportFile: File? = null,
    val pendingShare: OfflineShare? = null,
    val shareChooserOpen: Boolean = false,
    val aiConfiguration: AiPackageConfiguration? = null,
)

/**
 * Fixed candidate/release IO is independent from mutable draft state. Only validated derived
 * PNG/MP4 bytes reach the canvas. Failed media never implicitly advances the actual history.
 * No package text is interpreted as HTML, a URL, an expression or executable code.
 */
class ReleaseWorkspace(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = ReleaseStore(application)
    private val mutableState = MutableStateFlow(ReleaseUiState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private var playerTask: Job? = null
    private var playerGeneration = 0L
    private var reviewVideoTask: Job? = null
    private var reviewRunSequence = 0L
    private var exportDigest: String? = null
    private var exportLength: Long? = null
    private var pendingExportFile: File? = null
    private var savePickerPending = false
    private var sharePreparationActive = false
    private var cancellationNote: String? = null

    init { reloadLibrary() }

    fun reloadLibrary() = execute("读取演示库") {
        val items = withContext(Dispatchers.IO) { store.listReleases() }
        val candidates = withContext(Dispatchers.IO) { store.listCandidates() }
        mutableState.update { it.copy(releases = items, pendingCandidates = candidates) }
    }

    fun openReview(projectId: String) = execute("读取待复核成品") {
        closeReviewVideo()
        mutableState.update { it.copy(candidate = null, reviewStateId = null, reviewBitmap = null, reviewRegionId = null, reviewRegionBitmap = null) }
        val candidate = withContext(Dispatchers.IO) { store.readCandidate(projectId) }
        showCandidate(candidate)
    }

    fun openReviewById(candidateId: String) = execute("恢复固定成品复核") {
        closeReviewVideo()
        mutableState.update { it.copy(candidate = null, reviewStateId = null, reviewBitmap = null, reviewRegionId = null, reviewRegionBitmap = null) }
        showCandidate(withContext(Dispatchers.IO) { store.readCandidateById(candidateId) })
    }

    fun discardCandidate(candidateId: String) = execute("删除未封存副本") {
        withContext(Dispatchers.IO) { store.discardCandidate(candidateId) }
        if (state.value.candidate?.id == candidateId) {
            closeReviewVideo()
            mutableState.update { it.copy(candidate = null, reviewStateId = null, reviewBitmap = null, reviewRegionId = null, reviewRegionBitmap = null, player = null, playerBitmap = null) }
        }
    }

    fun createCandidate(projectId: String, revision: Long, replaceExisting: Boolean = false) = execute("固定修订并检查实际图片") {
        val candidate = withContext(Dispatchers.IO) { store.createCandidate(projectId, revision, replaceExisting) }
        mutableState.update { it.copy(player = null, playerBitmap = null, lastSealedId = null) }
        showCandidate(candidate)
    }

    private suspend fun showCandidate(candidate: ReleaseCandidate?) {
        closeReviewVideo()
        mutableState.update { it.copy(candidate = candidate, reviewStateId = null, reviewBitmap = null, reviewRegionId = null, reviewRegionBitmap = null) }
        val first = candidate?.scene?.states?.firstOrNull { it.id !in candidate.reviewedStateIds }
            ?: candidate?.scene?.states?.firstOrNull()
        if (candidate != null && first != null) {
            val bitmap = decode(candidate.scene, first.id, candidate.id)
            mutableState.update { it.copy(reviewStateId = first.id, reviewBitmap = bitmap) }
        }
    }

    fun selectReviewState(id: String) {
        if (state.value.busy) return
        closeReviewVideo()
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

    fun selectReviewRegion(regionId: String) {
        val candidate = state.value.candidate ?: return
        if (state.value.busy) return
        closeReviewVideo()
        val region = candidate.scene.regions.firstOrNull { it.id == regionId } ?: return
        execute("读取实际安全裁片") {
            mutableState.update { it.copy(reviewRegionId = regionId, reviewRegionBitmap = null) }
            val bitmap = withContext(Dispatchers.IO) {
                val file = store.candidateRegionFile(candidate.id, regionId)
                val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }) ?: error("区域裁片无法解码。")
                if (decoded.width != region.bbox.width || decoded.height != region.bbox.height) { decoded.recycle(); error("实际裁片尺寸不一致。") }
                decoded
            }
            mutableState.update { it.copy(reviewRegionBitmap = bitmap) }
        }
    }
    fun confirmReviewRegion() {
        val current = state.value
        val candidate = current.candidate ?: return
        val regionId = current.reviewRegionId ?: return
        if (current.reviewRegionBitmap == null) return
        execute("保存固定区域复核") {
            val updated = withContext(Dispatchers.IO) { store.reviewRegion(candidate.id, candidate.contentDigest, regionId) }
            mutableState.update { it.copy(candidate = updated) }
        }
    }

    fun selectReviewTransition(assetId: String) {
        if (state.value.busy) return
        val candidate = state.value.candidate ?: return
        val asset = candidate.scene.assets.firstOrNull { it.id == assetId && it.role == ViewerScene.Asset.ROLE_TRANSITION } ?: return
        closeReviewVideo()
        val review = ReleaseVideoReview(candidate.id, candidate.contentDigest, asset.id, asset.sha256,
            LocalVideoRun(null, asset.width, asset.height, ++reviewRunSequence))
        mutableState.update { it.copy(reviewVideo = review) }
        reviewVideoTask = viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { store.candidateTransitionFile(candidate.id, asset.id) }
                if (state.value.reviewVideo?.video?.runId == review.video.runId) mutableState.update {
                    it.copy(reviewVideo = review.copy(video = review.video.copy(file = file)))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { interruptReviewTransition(review.video.runId) }
        }
    }

    fun completeReviewTransition(runId: Long) {
        val review = state.value.reviewVideo ?: return
        val candidate = state.value.candidate ?: return
        if (review.video.runId != runId || review.video.failed || review.video.file == null ||
            candidate.id != review.candidateId || candidate.contentDigest != review.contentDigest) return
        mutableState.update { it.copy(reviewVideo = review.copy(watchedCompletely = true)) }
    }

    fun interruptReviewTransition(runId: Long) {
        val review = state.value.reviewVideo ?: return
        if (review.video.runId != runId) return
        mutableState.update { it.copy(reviewVideo = review.copy(watchedCompletely = false,
            video = review.video.copy(file = null, failed = true))) }
    }

    fun replayReviewTransition(runId: Long) {
        val review = state.value.reviewVideo ?: return
        if (review.video.runId == runId) selectReviewTransition(review.assetId)
    }

    fun closeReviewVideo() {
        reviewVideoTask?.cancel()
        reviewVideoTask = null
        mutableState.update { it.copy(reviewVideo = null) }
    }

    fun confirmReviewTransition() {
        val review = state.value.reviewVideo ?: return
        val candidate = state.value.candidate ?: return
        if (!review.watchedCompletely || review.video.failed || review.video.file == null ||
            candidate.id != review.candidateId || candidate.contentDigest != review.contentDigest) return
        execute("保存整段视频复核") {
            val updated = withContext(Dispatchers.IO) {
                store.reviewTransition(candidate.id, review.contentDigest, review.assetId, review.assetHash)
            }
            mutableState.update { it.copy(candidate = updated) }
            message("本段视频已确认")
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
        closeReviewVideo()
        val candidate = state.value.candidate ?: return
        execute("重检并封存固定版本") {
            val sealed = withContext(Dispatchers.IO) { store.seal(candidate.id, candidate.contentDigest) }
            val items = withContext(Dispatchers.IO) { store.listReleases() }
            mutableState.update { it.copy(releases = items, lastSealedId = sealed.id, candidate = null,
                reviewStateId = null, reviewBitmap = null, reviewRegionId = null, reviewRegionBitmap = null, player = null, playerBitmap = null) }
            message("版本已封存，可保存离线观看包")
        }
    }

    fun startCandidatePreview() {
        closeReviewVideo()
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
        if (current.busy || current.playerBitmap == null || player.ended || player.traversal().pendingEdgeId != null ||
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
        if (current.busy || current.playerBitmap == null || player.ended || player.traversal().pendingEdgeId != null) return
        val edge = player.scene.edges.firstOrNull { it.id == id && it.fromStateId == player.currentStateId } ?: return
        if (player.history.size >= MAX_VISITS && edge.toStateId != null) {
            message("已观看 256 次，请点重来开始新一轮")
            return
        }
        val next = player.traversal().advance(player.scene, id)
        if (next.pendingEdgeId != null) {
            val asset = player.scene.assets.first { it.id == next.pendingTransitionAssetId }
            val pending = player.applyTraversal(next).copy(video = LocalVideoRun(null, asset.width, asset.height, next.mediaRunId))
            mutableState.update { it.copy(player = pending) }
            loadPlayerTransition(pending)
        } else commitPlayerAction(player, next, id)
    }

    private fun loadPlayerTransition(player: ReleasePlayback) = launchPlayerWork {
        val next = player.traversal()
        try {
            val file = withContext(Dispatchers.IO) {
                if (player.candidateId != null) store.candidateTransitionFile(player.candidateId, next.pendingTransitionAssetId)
                else store.releaseTransitionFile(player.scene.releaseId, next.pendingTransitionAssetId)
            }
            if (state.value.player?.traversal()?.mediaRunId == next.mediaRunId && state.value.player?.traversal()?.pendingEdgeId != null) {
                mutableState.update { it.copy(player = it.player?.copy(video = player.video?.copy(file = file))) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failPlayerTransition(next.mediaRunId) }
    }

    fun completePlayerTransition(runId: Long) {
        if (state.value.busy) return
        val player = state.value.player ?: return
        val old = player.traversal()
        if (old.pendingEdgeId == null || old.mediaRunId != runId || player.video?.file == null) return
        val next = old.completeTransition(player.scene, runId)
        if (next === old) return
        commitPlayerAction(player, next, old.pendingEdgeId)
    }

    fun failPlayerTransition(runId: Long) {
        val player = state.value.player ?: return
        val old = player.traversal()
        val next = old.failTransition(player.scene, runId)
        if (next === old) return
        mutableState.update { it.copy(player = player.applyTraversal(next).copy(video = player.video?.copy(file = null, failed = true))) }
    }

    fun retryPlayerTransition(runId: Long) {
        if (state.value.busy) return
        val player = state.value.player ?: return
        val old = player.traversal()
        if (old.pendingEdgeId == null || old.mediaRunId != runId) return
        val next = old.failTransition(player.scene, runId).retryTransition(player.scene)
        val pending = player.applyTraversal(next).copy(video = player.video?.copy(file = null, runId = next.mediaRunId, failed = false))
        mutableState.update { it.copy(player = pending) }
        loadPlayerTransition(pending)
    }

    fun skipPlayerTransition() {
        if (state.value.busy) return
        val player = state.value.player ?: return
        val old = player.traversal()
        val edgeId = old.pendingEdgeId ?: return
        commitPlayerAction(player, old.skipTransition(player.scene, old.mediaRunId), edgeId)
    }

    private fun commitPlayerAction(player: ReleasePlayback, next: ViewerTraversal, edgeId: String) = launchPlayerWork {
        var displayed = false
        try {
            val bitmap = if (next.currentStateId != player.currentStateId) decode(player.scene, next.currentStateId, player.candidateId)
                else state.value.playerBitmap
            currentCoroutineContext().ensureActive()
            check(bitmap != null) { "目标画面不可用" }
            // Publish the real visit before writing coverage. A close during disk IO must not
            // record an unseen destination or restore this player from an old completion.
            mutableState.update { it.copy(playerBitmap = bitmap, player = player.applyTraversal(next).copy(video = null)) }
            displayed = true
            val candidate = player.candidateId?.let { candidateId -> withContext(Dispatchers.IO) {
                store.recordTraversal(candidateId, ViewerPackageCodec.contentDigest(player.scene), edgeId, false)
            } }
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(candidate = candidate ?: it.candidate,
                player = it.player?.copy(visitedEdgeIds = candidate?.visitedEdgeIds ?: next.visitedEdgeIds,
                    completedFromStart = candidate?.completedPath ?: next.completedFromStart)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (player.traversal().pendingEdgeId != null) failPlayerTransition(player.traversal().mediaRunId)
            message(if (displayed) "试走记录未能保存，请点重来" else "目标画面未能读取，可重试或返回")
        }
    }

    fun previous() {
        val player = state.value.player ?: return
        if (player.traversal().pendingEdgeId != null) {
            cancelPlayerWork()
            mutableState.update { it.copy(player = player.applyTraversal(player.traversal().cancelTransition(player.scene)).copy(video = null)) }
            return
        }
        if (state.value.busy || !player.canGoBack) return
        launchPlayerWork {
            val previous = player.traversal().previous(player.scene)
            val bitmap = decode(player.scene, previous.currentStateId, player.candidateId)
            val candidate = player.candidateId?.let { id -> withContext(Dispatchers.IO) {
                store.rewindTraversal(id, ViewerPackageCodec.contentDigest(player.scene))
            } }
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(candidate = candidate ?: it.candidate, playerBitmap = bitmap,
                player = player.applyTraversal(previous).copy(video = null)) }
        }
    }

    fun restart() {
        val player = state.value.player ?: return
        if (player.traversal().pendingEdgeId != null) {
            cancelPlayerWork()
            mutableState.update { it.copy(player = player.applyTraversal(player.traversal().cancelTransition(player.scene)).copy(video = null)) }
        }
        if (state.value.busy) return
        launchPlayerWork {
            val restarted = player.traversal().restart(player.scene)
            val bitmap = decode(player.scene, restarted.currentStateId, player.candidateId)
            val candidate = player.candidateId?.let { id -> withContext(Dispatchers.IO) {
                store.recordTraversal(id, ViewerPackageCodec.contentDigest(player.scene), null, false)
            } }
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(candidate = candidate ?: it.candidate, playerBitmap = bitmap,
                player = player.applyTraversal(restarted).copy(video = null,
                    visitedEdgeIds = candidate?.visitedEdgeIds ?: restarted.visitedEdgeIds,
                    completedFromStart = candidate?.completedPath ?: restarted.completedFromStart)) }
        }
    }

    fun closePlayer() {
        cancelPlayerWork()
        if (state.value.busy) cancel()
        mutableState.update { it.copy(player = null, playerBitmap = null) }
    }

    private fun launchPlayerWork(block: suspend () -> Unit) {
        playerTask?.cancel()
        val generation = ++playerGeneration
        mutableState.update { it.copy(busy = true, stage = "读取安全画面") }
        playerTask = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("播放未完成，请重试") }
            finally { if (generation == playerGeneration) mutableState.update { it.copy(busy = false, stage = null) } }
        }
    }

    private fun cancelPlayerWork() {
        playerGeneration++
        val active = playerTask?.isActive == true
        playerTask?.cancel()
        playerTask = null
        if (active) mutableState.update { it.copy(busy = false, stage = null) }
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
            lastSealedId = it.lastSealedId?.takeUnless { selected -> selected == id },
            aiConfiguration = it.aiConfiguration?.takeUnless { config -> config.scene.releaseId == id }) }
    }

    fun prepareExport(id: String) = execute("生成并验证离线观看包") {
        exportDigest = null; exportLength = null; pendingExportFile = null
        mutableState.update { it.copy(exportFile = null) }
        val file = withContext(Dispatchers.IO) { store.exportRelease(id) }
        val digest = withContext(Dispatchers.IO) { ViewerPackageCodec.sha256(file) }
        exportDigest = digest; exportLength = file.length()
        mutableState.update { it.copy(exportFile = file) }
    }

    fun prepareOfflineShare(id: String) {
        val selected = state.value.releases.singleOrNull { it.id == id && it.origin == "local" }
        if (selected == null || state.value.lastSealedId != id) {
            message("请选择当前已复核封存的本机版本。")
            return
        }
        execute("准备离线观看包分享") {
            sharePreparationActive = true
            try {
                mutableState.update { it.copy(pendingShare = null) }
                val share = withContext(Dispatchers.IO) { store.prepareOfflineShare(id, selected.contentDigest) }
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(pendingShare = share) }
            } finally { sharePreparationActive = false }
        }
    }

    /** Consumed synchronously before launching. Recomposition/rotation must never launch twice. */
    fun beginShareChooser(visibleReleaseId: String?): OfflineShare? {
        val current = state.value
        if (current.busy || current.shareChooserOpen || savePickerPending) return null
        val share = current.pendingShare ?: return null
        mutableState.update { it.copy(pendingShare = null) }
        if (visibleReleaseId != share.releaseId || current.lastSealedId != share.releaseId ||
            current.releases.none { it.id == share.releaseId && it.origin == "local" }) return null
        if (System.currentTimeMillis() >= share.expiresAt) {
            message("分享副本已过期，请重新点击系统分享。")
            return null
        }
        mutableState.update { it.copy(shareChooserOpen = true) }
        return share
    }

    fun leaveSharePage() {
        if (sharePreparationActive) task?.cancel()
        mutableState.update { it.copy(pendingShare = null) }
    }

    fun shareChooserLaunched() {
        message("已打开系统分享，是否发送以所选应用为准；返回不代表已发送。")
    }

    fun finishShareChooser(failed: Boolean = false) {
        mutableState.update { it.copy(shareChooserOpen = false, pendingShare = null) }
        if (failed) message("无法打开系统分享，请重试或使用保存到文件。")
        // Keep the snapshot for receivers that open asynchronously. There is no delivery receipt.
    }

    fun openAiPackage(id: String) = execute("读取固定版本与动画配置") {
        mutableState.update { it.copy(aiConfiguration = null) }
        val scene = withContext(Dispatchers.IO) { store.loadRelease(id) }
        val saved = withContext(Dispatchers.IO) { store.readAiPlan(id) }
        val fromDraft = withContext(Dispatchers.IO) { store.hasDraftAiPlan(id) }
        val config = if (saved == null) AiPackageConfiguration(scene, listOf(RenderPlan.Visit(java.util.UUID.randomUUID().toString(), scene.startStateId, null, 90)))
            else AiPackageConfiguration(scene, saved.visits, saved.effects, saved.width, saved.height, fromDraft)
        mutableState.update { it.copy(aiConfiguration = config) }
    }

    fun clearAiConfiguration() { if (!state.value.busy && !savePickerPending) mutableState.update { it.copy(aiConfiguration = null) } }

    fun chooseAiEdge(edgeId: String) = editAi { config ->
        val current = config.visits.last()
        check(current.selectedEdgeId == null) { "路径已明确结束，请先退回一次访问。" }
        val edge = config.scene.edges.single { it.id == edgeId && it.fromStateId == current.stateId }
        check(config.visits.size < RenderPlan.MAX_VISITS || edge.toStateId == null) { "最多 256 次访问。" }
        val updated = config.visits.dropLast(1) + RenderPlan.Visit(current.visitId, current.stateId, edge.id, current.holdFrames)
        config.copy(visits = if (edge.toStateId == null) updated else updated + RenderPlan.Visit(java.util.UUID.randomUUID().toString(), edge.toStateId, null, 90))
    }

    fun previousAiVisit() = editAi { config ->
        val current = config.visits.last()
        val remaining = if (current.selectedEdgeId != null) config.visits else config.visits.dropLast(1)
        if (remaining.isEmpty()) config else {
            val last = remaining.last()
            val visits = remaining.dropLast(1) + RenderPlan.Visit(last.visitId, last.stateId, null, last.holdFrames)
            config.copy(visits = visits, effects = config.effects.filter { effect -> visits.any { it.visitId == effect.visitId } && !(effect.visitId == last.visitId && effect.type in setOf("click", "transition")) })
        }
    }

    fun resetAiPath() = editAi { config -> config.copy(visits = listOf(RenderPlan.Visit(java.util.UUID.randomUUID().toString(), config.scene.startStateId, null, 90)), effects = emptyList()) }
    fun setAiCanvas(landscape: Boolean) = editAi { it.copy(width = if (landscape) 1920 else 1080, height = if (landscape) 1080 else 1920) }
    fun setAiHold(visitId: String, frames: Int) = editAi { config ->
        check(frames in 1..RenderPlan.MAX_HOLD_FRAMES) { "每步停留须在 1/30 秒到 60 秒之间。" }
        val index = config.visits.indexOfFirst { it.visitId == visitId }
        check(index >= 0) { "播放顺序中没有这一步。" }
        config.copy(visits = config.visits.map { if (it.visitId == visitId) RenderPlan.Visit(it.visitId, it.stateId, it.selectedEdgeId, frames) else it })
    }
    fun toggleAiEffect(visitId: String, type: String, regionId: String? = null, text: String? = null) = editAi { config ->
        val visit = config.visits.single { it.visitId == visitId }
        val existing = config.effects.any { it.visitId == visitId && it.type == type && it.regionId == regionId }
        if (existing) config.copy(effects = config.effects.filterNot { it.visitId == visitId && it.type == type && it.regionId == regionId })
        else {
            val edge = config.scene.edges.firstOrNull { it.id == visit.selectedEdgeId }
            val duration = minOf(30, visit.holdFrames)
            val effect = when (type) {
                "click" -> { check(edge?.hotspotId != null) { "此访问没有选择画面热点。" }; RenderPlan.Effect(type, visitId, maxOf(0, visit.holdFrames - duration), duration, edge.hotspotId, null, null, null) }
                "focus", "highlight" -> { check(config.scene.regions.any { it.id == regionId && it.stateId == visit.stateId }) { "请选择此步骤的安全区域。" }; RenderPlan.Effect(type, visitId, 0, visit.holdFrames, null, regionId, null, null) }
                "annotation" -> RenderPlan.Effect(type, visitId, 0, visit.holdFrames, null, null, text, ViewerScene.Rect(.08, .08, .84, .14))
                "transition" -> {
                    val index = config.visits.indexOf(visit)
                    check(edge?.toStateId != null && edge.transitionAssetId == null && index + 1 < config.visits.size) { "只有静态跳转可以添加叠化。" }
                    val overlap = minOf(12, visit.holdFrames - 1, config.visits[index + 1].holdFrames - 1)
                    check(overlap > 0) { "先增加停留帧数。" }
                    RenderPlan.Effect(type, visitId, visit.holdFrames - overlap, overlap, null, null, null, null)
                }
                else -> error("不支持此效果。")
            }
            check(config.effects.size < RenderPlan.MAX_EFFECTS) { "最多 256 个效果。" }
            config.copy(effects = config.effects + effect)
        }
    }
    private fun editAi(change: (AiPackageConfiguration) -> AiPackageConfiguration) {
        if (state.value.busy || savePickerPending) return
        val config = state.value.aiConfiguration ?: return
        if (config.fromDraft) { message("此计划随草稿封存，请回项目的动画计划调整后重新封存。"); return }
        try { mutableState.update { it.copy(aiConfiguration = change(config), exportFile = null, message = null) } }
        catch (error: IllegalStateException) { message(error.message ?: "动画配置无效。") }
        catch (error: IllegalArgumentException) { message(error.message ?: "动画配置无效。") }
    }
    fun prepareAiExport() = execute("生成并回读验证 AI 数据包") {
        val config = state.value.aiConfiguration ?: error("请先选择固定版本。")
        val plan = config.resolve()
        exportDigest = null; exportLength = null; pendingExportFile = null
        mutableState.update { it.copy(exportFile = null) }
        val file = withContext(Dispatchers.IO) { store.exportAiRelease(config.scene.releaseId, plan) }
        exportDigest = withContext(Dispatchers.IO) { ViewerPackageCodec.sha256(file) }; exportLength = file.length()
        mutableState.update { it.copy(exportFile = file) }
    }

    fun beginExportPicker(): Boolean {
        if (state.value.busy || savePickerPending || state.value.shareChooserOpen || state.value.pendingShare != null || state.value.exportFile == null || exportDigest == null) return false
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
                message(if (file.name.endsWith(".tapscene-ai")) "AI 数据包已完整保存，回读校验一致；动画由独立模板渲染。" else "离线观看包已完整保存，回读校验一致")
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

    private fun ReleasePlayback.traversal() = traversalState ?: ViewerTraversal(currentStateId, history, ended, endLabel,
        endEdgeId, visitedEdgeIds, completedFromStart)

    private fun ReleasePlayback.applyTraversal(next: ViewerTraversal) = copy(traversalState = next, currentStateId = next.currentStateId,
        history = next.history, ended = next.ended, endLabel = next.endLabel, endEdgeId = next.endEdgeId,
        matchingHotspotIds = emptyList(), visitedEdgeIds = next.visitedEdgeIds, completedFromStart = next.completedFromStart)

    fun cancel() { task?.cancel(); mutableState.update { it.copy(pendingShare = null) } }
    fun message(text: String) { mutableState.update { it.copy(message = text) } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    private fun execute(label: String, block: suspend () -> Unit) {
        if (state.value.busy || savePickerPending || state.value.shareChooserOpen || state.value.pendingShare != null || state.value.exportFile != null) return
        cancellationNote = null
        mutableState.update { it.copy(busy = true, stage = label, message = null) }
        task = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) {
                mutableState.update { it.copy(player = null, playerBitmap = null, pendingShare = null) }
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
