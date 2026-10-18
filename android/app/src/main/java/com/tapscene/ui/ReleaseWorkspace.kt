package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.data.ReleaseCandidate
import com.tapscene.data.ReleaseDeletionState
import com.tapscene.data.ReleaseStore
import com.tapscene.data.ReleaseSummary
import com.tapscene.data.PlaybackOpen
import com.tapscene.data.PlaybackSession
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
    val playbackSession: PlaybackSession? = null,
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

    /** Structural comparison also works for an unfinished path; resolving it would lose that guard. */
    internal fun sameAnimation(other: AiPackageConfiguration): Boolean =
        scene.releaseId == other.scene.releaseId && fromDraft == other.fromDraft &&
            width == other.width && height == other.height && visits.size == other.visits.size &&
            visits.zip(other.visits).all { (a, b) ->
                a.visitId == b.visitId && a.stateId == b.stateId &&
                    a.selectedEdgeId == b.selectedEdgeId && a.holdFrames == b.holdFrames
            } && effects.size == other.effects.size && effects.zip(other.effects).all { (a, b) ->
                a.type == b.type && a.visitId == b.visitId && a.startFrame == b.startFrame &&
                    a.durationFrames == b.durationFrames && a.hotspotId == b.hotspotId &&
                    a.regionId == b.regionId && a.text == b.text &&
                    (if (a.rect == null || b.rect == null) a.rect == null && b.rect == null else
                        a.rect.x == b.rect.x && a.rect.y == b.rect.y &&
                            a.rect.width == b.rect.width && a.rect.height == b.rect.height)
            }

    fun withAnnotation(visitId: String, text: String): AiPackageConfiguration {
        check(!fromDraft) { "此计划随草稿封存，请回项目的动画计划调整后重新封存。" }
        check(text.isNotBlank() && text.length <= 240) { "标注须为 1 至 240 字的非空白文字。" }
        val visit = visits.singleOrNull { it.visitId == visitId } ?: error("播放顺序中没有这一步。")
        val annotations = effects.filter { it.visitId == visitId && it.type == "annotation" }
        check(annotations.size <= 1) { "此访问有多条标注，无法单独调整。" }
        val existing = annotations.singleOrNull()
        if (existing != null) {
            val updated = RenderPlan.Effect(existing.type, existing.visitId, existing.startFrame, existing.durationFrames,
                existing.hotspotId, existing.regionId, text, existing.rect)
            return copy(effects = effects.map { if (it === existing) updated else it })
        }
        check(effects.size < RenderPlan.MAX_EFFECTS) { "最多 256 个效果。" }
        return copy(effects = effects + RenderPlan.Effect("annotation", visitId, 0, visit.holdFrames,
            null, null, text, ViewerScene.Rect(.08, .08, .84, .14)))
    }

    fun withoutAnnotation(visitId: String): AiPackageConfiguration {
        check(!fromDraft) { "此计划随草稿封存，请回项目的动画计划调整后重新封存。" }
        check(visits.singleOrNull { it.visitId == visitId } != null) { "播放顺序中没有这一步。" }
        val annotations = effects.filter { it.visitId == visitId && it.type == "annotation" }
        check(annotations.size <= 1) { "此访问有多条标注，无法单独调整。" }
        val existing = annotations.singleOrNull() ?: return this
        return copy(effects = effects.filterNot { it === existing })
    }
}

/** A deliberate delivery choice, independent from the last seal operation's result. */
data class DeliverySelection(val releaseId: String, val contentDigest: String, val projectId: String)

/** A failed result read never authorizes another deletion; retry only checks/finishes its commit. */
data class ReleaseDeletionCheck(val release: ReleaseSummary, val cleanupPending: Boolean = false)

data class ReleaseUiState(
    val releases: List<ReleaseSummary> = emptyList(),
    val pendingCandidates: List<ReleaseCandidate> = emptyList(),
    val deletionCheck: ReleaseDeletionCheck? = null,
    val libraryReadFailed: Boolean = false,
    val candidate: ReleaseCandidate? = null,
    val reviewStateId: String? = null,
    val reviewBitmap: Bitmap? = null,
    val reviewRegionId: String? = null,
    val reviewRegionBitmap: Bitmap? = null,
    val reviewVideo: ReleaseVideoReview? = null,
    val player: ReleasePlayback? = null,
    val playerBitmap: Bitmap? = null,
    val playbackResume: PlaybackOpen? = null,
    val busy: Boolean = false,
    val stage: String? = null,
    val message: String? = null,
    val lastSealedId: String? = null,
    val deliverySelection: DeliverySelection? = null,
    val lastImportedId: String? = null,
    val exportFile: File? = null,
    val pendingShare: OfflineShare? = null,
    val shareChooserOpen: Boolean = false,
    val aiConfiguration: AiPackageConfiguration? = null,
    /** Changes since the last confirmed load/save; cancellation may leave disk outcome unknown. */
    val aiConfigurationChanged: Boolean = false,
) {
    val deliveryRelease: ReleaseSummary? get() = deliverySelection?.let { selected ->
        releases.singleOrNull { it.id == selected.releaseId && it.contentDigest == selected.contentDigest && it.origin == "local" }
    }
}

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
    private var playbackWriteSequence = 0L
    private var lastPlaybackWrite: Job? = null
    private var reviewVideoTask: Job? = null
    private var reviewRunSequence = 0L
    private var aiConfigurationBaseline: AiPackageConfiguration? = null
    private var exportDigest: String? = null
    private var exportLength: Long? = null
    private var pendingExportFile: File? = null
    private var savePickerPending = false
    private var sharePreparationActive = false
    private var deliverySelectionActive = false
    private var deliveryExportActive = false
    private var exportDeliverySelection: DeliverySelection? = null
    private var cancellationNote: String? = null

    init { reloadLibrary() }

    fun reloadLibrary() = execute("读取演示库") {
        val items = withContext(Dispatchers.IO) { store.listReleases() }
        val candidates = withContext(Dispatchers.IO) { store.listCandidates() }
        mutableState.update { current -> current.copy(releases = items, pendingCandidates = candidates, libraryReadFailed = false,
            deliverySelection = current.deliverySelection?.takeIf { selected -> items.any {
                it.id == selected.releaseId && it.contentDigest == selected.contentDigest && it.origin == "local"
            } }) }
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
            mutableState.update { it.copy(releases = items, lastSealedId = sealed.id,
                deliverySelection = DeliverySelection(sealed.id, sealed.contentDigest, candidate.projectId), candidate = null,
                reviewStateId = null, reviewBitmap = null, reviewRegionId = null, reviewRegionBitmap = null, player = null, playerBitmap = null) }
            message("版本已封存，可保存离线观看包")
        }
    }

    fun startCandidatePreview() {
        closeReviewVideo()
        val candidate = state.value.candidate ?: return
        execute("读取固定成品起点") {
            mutableState.update { it.copy(player = null, playerBitmap = null, playbackResume = null) }
            val start = candidate.scene.states.first { it.id == candidate.scene.startStateId }
            val bitmap = decode(candidate.scene, start.id, candidate.id)
            val updated = withContext(Dispatchers.IO) { store.recordTraversal(candidate.id, candidate.contentDigest, null, false) }
            mutableState.update { it.copy(candidate = updated, playerBitmap = bitmap,
                player = initialPlayback(candidate.scene, candidate.id, updated.visitedEdgeIds, updated.completedPath)) }
        }
    }

    fun openRelease(id: String) = execute("读取离线演示") {
        mutableState.update { it.copy(player = null, playerBitmap = null, playbackResume = null) }
        // An immediate reopen after Close waits for the already displayed frame's bounded
        // checkpoint write before taking a new store session. Exit itself stays responsive.
        lastPlaybackWrite?.takeUnless { it === currentCoroutineContext()[Job] }?.join()
        val opened = withContext(Dispatchers.IO) { store.openPlayback(id) }
        if (opened.checkpoint != null || opened.checkpointUnavailable) {
            mutableState.update { it.copy(playbackResume = opened) }
        } else showPlayback(opened, fromStart = true, save = false)
    }

    /** A late button from another version/choice cannot select or reset the current one. */
    fun resumePlayback(ownerToken: String, fromStart: Boolean) {
        val opened = state.value.playbackResume ?: return
        if (opened.session.ownerToken != ownerToken || (!fromStart && opened.checkpoint == null)) return
        execute(if (fromStart) "从头读取演示" else "读取上次画面") {
            if (state.value.playbackResume?.session != opened.session) return@execute
            showPlayback(opened, fromStart, save = true)
        }
    }

    private suspend fun showPlayback(opened: PlaybackOpen, fromStart: Boolean, save: Boolean) {
        val traversal = if (fromStart) ViewerTraversal.start(opened.scene) else checkNotNull(opened.checkpoint)
        val bitmap = decode(opened.scene, traversal.currentStateId, null, opened.session)
        currentCoroutineContext().ensureActive()
        val player = initialPlayback(opened.scene, null).applyTraversal(traversal).copy(playbackSession = opened.session)
        mutableState.update { it.copy(playerBitmap = bitmap, player = player, playbackResume = null) }
        if (save) persistPlayback(player)
    }

    /** Save only a frame already published to the player; close cannot abandon that small write.
     * Store session ownership prevents this completion from overwriting a newer open/reimport. */
    private suspend fun persistPlayback(player: ReleasePlayback) {
        if (player.candidateId != null) return
        val session = player.playbackSession ?: return
        val sequence = ++playbackWriteSequence
        lastPlaybackWrite = currentCoroutineContext()[Job]
        withContext(NonCancellable + Dispatchers.IO) {
            val saved = runCatching { store.savePlayback(session, player.traversal(), sequence) }.getOrDefault(false)
            if (!saved) withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (sequence == playbackWriteSequence && state.value.player?.playbackSession == session) {
                    message("观看记录未能保存，当前画面保留；退出后可能需要从头开始。")
                }
            }
        }
        currentCoroutineContext().ensureActive()
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
            val bitmap = if (next.currentStateId != player.currentStateId) decode(player.scene, next.currentStateId, player.candidateId, player.playbackSession)
                else state.value.playerBitmap
            currentCoroutineContext().ensureActive()
            check(bitmap != null) { "目标画面不可用" }
            // Publish the real visit before writing coverage. A close during disk IO must not
            // record an unseen destination or restore this player from an old completion.
            val displayedPlayer = player.applyTraversal(next).copy(video = null)
            mutableState.update { it.copy(playerBitmap = bitmap, player = displayedPlayer) }
            displayed = true
            persistPlayback(displayedPlayer)
            val candidate = player.candidateId?.let { candidateId -> withContext(Dispatchers.IO) {
                store.recordTraversal(candidateId, ViewerPackageCodec.contentDigest(player.scene), edgeId, false)
            } }
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(candidate = candidate ?: it.candidate,
                player = it.player?.copy(visitedEdgeIds = candidate?.visitedEdgeIds ?: next.visitedEdgeIds,
                    completedFromStart = candidate?.completedPath ?: next.completedFromStart)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (player.traversal().pendingEdgeId != null) failPlayerTransition(player.traversal().mediaRunId)
            message(if (error is PlaybackSessionExpiredException) error.message.orEmpty()
                else if (displayed) "试走记录未能保存，请点重来" else "目标画面未能读取，可重试或返回")
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
            val bitmap = decode(player.scene, previous.currentStateId, player.candidateId, player.playbackSession)
            val candidate = player.candidateId?.let { id -> withContext(Dispatchers.IO) {
                store.rewindTraversal(id, ViewerPackageCodec.contentDigest(player.scene))
            } }
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(candidate = candidate ?: it.candidate, playerBitmap = bitmap,
                player = player.applyTraversal(previous).copy(video = null)) }
            persistPlayback(player.applyTraversal(previous).copy(video = null))
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
            val bitmap = decode(player.scene, restarted.currentStateId, player.candidateId, player.playbackSession)
            val candidate = player.candidateId?.let { id -> withContext(Dispatchers.IO) {
                store.recordTraversal(id, ViewerPackageCodec.contentDigest(player.scene), null, false)
            } }
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(candidate = candidate ?: it.candidate, playerBitmap = bitmap,
                player = player.applyTraversal(restarted).copy(video = null,
                    visitedEdgeIds = candidate?.visitedEdgeIds ?: restarted.visitedEdgeIds,
                    completedFromStart = candidate?.completedPath ?: restarted.completedFromStart)) }
            persistPlayback(player.applyTraversal(restarted).copy(video = null))
        }
    }

    fun closePlayer() {
        cancelPlayerWork()
        if (state.value.busy) cancel()
        mutableState.update { it.copy(player = null, playerBitmap = null, playbackResume = null) }
    }

    private fun launchPlayerWork(block: suspend () -> Unit) {
        playerTask?.cancel()
        val generation = ++playerGeneration
        mutableState.update { it.copy(busy = true, stage = "读取安全画面", message = null) }
        playerTask = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(if (error is PlaybackSessionExpiredException) error.message.orEmpty() else "播放未完成，请重试") }
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

    fun deleteRelease(expected: ReleaseSummary) {
        if (state.value.deletionCheck != null) {
            message("请先核对上次删除结果，再删除其他版本。")
            return
        }
        execute("删除本机观看副本", settle = { failure -> settleReleaseDeletion(expected, failure) }) {
            withContext(Dispatchers.IO) { store.deleteRelease(expected) }
        }
    }

    fun retryReleaseDeletion() {
        val pending = state.value.deletionCheck ?: return
        execute(if (pending.cleanupPending) "继续清理本机副本" else "核对删除结果",
            settle = { failure -> settleReleaseDeletion(pending.release, failure) }) {
            if (pending.cleanupPending) withContext(Dispatchers.IO) { store.retryDeletionCleanup(pending.release) }
        }
    }

    /** Run after the normal refresh, even if that refresh failed or the task was cancelled. */
    private suspend fun settleReleaseDeletion(expected: ReleaseSummary, failure: Throwable?) {
        val result = withContext(Dispatchers.IO) { runCatching { store.inspectDeletion(expected) }.getOrNull() }
        withContext(Dispatchers.Main) {
            if (result in setOf(ReleaseDeletionState.DELETED, ReleaseDeletionState.CLEANUP_PENDING)) {
                if (aiConfigurationBaseline?.scene?.matches(expected) == true) aiConfigurationBaseline = null
                mutableState.update { current ->
                    val removePlayer = current.player?.let { it.candidateId == null && it.scene.matches(expected) } == true
                    val removeAi = current.aiConfiguration?.scene?.matches(expected) == true
                    current.copy(
                        releases = current.releases.filterNot { it.sameBinding(expected) },
                        player = current.player.takeUnless { removePlayer },
                        playerBitmap = current.playerBitmap.takeUnless { removePlayer },
                        playbackResume = current.playbackResume?.takeUnless { it.scene.matches(expected) },
                        lastSealedId = current.lastSealedId?.takeUnless { it == expected.id },
                        lastImportedId = current.lastImportedId?.takeUnless { it == expected.id },
                        deliverySelection = current.deliverySelection?.takeUnless {
                            it.releaseId == expected.id && it.contentDigest == expected.contentDigest
                        },
                        aiConfiguration = current.aiConfiguration.takeUnless { removeAi },
                        aiConfigurationChanged = current.aiConfigurationChanged && !removeAi,
                    )
                }
            }
            if (result == ReleaseDeletionState.REPLACED) mutableState.update { current ->
                // A same-ID replacement may already own new edits; never clear that owner's data.
                current.copy(releases = current.releases.filterNot { it.sameBinding(expected) })
            }
            val pending = when (result) {
                null -> ReleaseDeletionCheck(expected)
                ReleaseDeletionState.CLEANUP_PENDING -> ReleaseDeletionCheck(expected, cleanupPending = true)
                else -> null
            }
            val note = when (result) {
                ReleaseDeletionState.PRESENT -> if (failure is CancellationException)
                    "已取消删除；本机副本、观看记录和 AI 配置保留。"
                    else "已核对：本机副本仍保留，没有完成删除。"
                ReleaseDeletionState.REPLACED -> "确认的版本已变化，未删除当前副本。请重新打开后核对。"
                ReleaseDeletionState.DELETED -> "本机副本、观看记录和 AI 配置已删除。创作项目及保存到应用外的文件保留。"
                ReleaseDeletionState.CLEANUP_PENDING -> "本机副本已删除；剩余本机文件尚未清理完成。请继续清理，保存到应用外的文件不受影响。"
                null -> "暂时无法核对删除结果。请重新核对，确认前不要重复删除或导入同一版本。"
            }
            mutableState.update { it.copy(deletionCheck = pending, message = note) }
        }
    }

    private fun ViewerScene.matches(expected: ReleaseSummary) = releaseId == expected.id &&
        ViewerPackageCodec.contentDigest(this) == expected.contentDigest

    private fun ReleaseSummary.sameBinding(other: ReleaseSummary) = id == other.id &&
        contentDigest == other.contentDigest && origin == other.origin && sealedAt == other.sealedAt

    /** Project entry must not inherit another project's previous delivery choice. */
    fun openProjectDelivery(projectId: String) {
        leaveDeliveryPage()
        mutableState.update { it.copy(deliverySelection = it.deliverySelection?.takeIf { selected -> selected.projectId == projectId }) }
    }

    /** Read retained sealed provenance; source projects may have been renamed or deleted. */
    fun selectDeliveryRelease(id: String): Boolean {
        if (state.value.busy || savePickerPending || state.value.shareChooserOpen || state.value.pendingShare != null || state.value.exportFile != null) return false
        mutableState.update { it.copy(deliverySelection = null) }
        execute("读取封存版本") {
            deliverySelectionActive = true
            try {
                val summary = state.value.releases.singleOrNull { it.id == id && it.origin == "local" }
                    ?: error("请选择已复核封存的本机版本。")
                val binding = withContext(Dispatchers.IO) { store.readHostedRelease(id, summary.contentDigest) }
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(deliverySelection = DeliverySelection(id, summary.contentDigest, binding.projectId)) }
            } finally { deliverySelectionActive = false }
        }
        return true
    }

    fun prepareDeliveryExport() {
        val selection = state.value.deliverySelection ?: return
        if (state.value.deliveryRelease == null) return
        prepareExport(selection.releaseId, selection)
    }

    fun prepareExport(id: String) = prepareExport(id, null)

    private fun prepareExport(id: String, selection: DeliverySelection?) = execute("生成并验证离线观看包") {
        exportDigest = null; exportLength = null; pendingExportFile = null
        exportDeliverySelection = selection
        deliveryExportActive = selection != null
        mutableState.update { it.copy(exportFile = null) }
        try {
            if (selection != null) withContext(Dispatchers.IO) { store.readHostedRelease(id, selection.contentDigest) }
            val file = withContext(Dispatchers.IO) { store.exportRelease(id) }
            val digest = withContext(Dispatchers.IO) { ViewerPackageCodec.sha256(file) }
            currentCoroutineContext().ensureActive()
            check(selection == null || (state.value.deliverySelection == selection && state.value.deliveryRelease != null)) {
                "交付版本已改变，请重新选择保存。"
            }
            exportDigest = digest; exportLength = file.length()
            mutableState.update { it.copy(exportFile = file) }
        } finally { deliveryExportActive = false }
    }

    fun prepareOfflineShare(id: String) {
        val selected = state.value.releases.singleOrNull { it.id == id && it.origin == "local" }
        if (selected == null || state.value.deliveryRelease != selected) {
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
        if (visibleReleaseId != share.releaseId || current.deliveryRelease?.id != share.releaseId) return null
        if (System.currentTimeMillis() >= share.expiresAt) {
            message("分享副本已过期，请重新点击系统分享。")
            return null
        }
        mutableState.update { it.copy(shareChooserOpen = true) }
        return share
    }

    /** Cancel only this page's preparation, never library/AI exports or a file already being saved. */
    fun leaveDeliveryPage() {
        if (deliverySelectionActive || deliveryExportActive) task?.cancel()
        if (deliveryExportActive || (exportDeliverySelection != null && state.value.exportFile != null)) cancelExportPicker()
        leaveSharePage()
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
        aiConfigurationBaseline = null
        mutableState.update { it.copy(aiConfiguration = null, aiConfigurationChanged = false) }
        val scene = withContext(Dispatchers.IO) { store.loadRelease(id) }
        val saved = withContext(Dispatchers.IO) { store.readAiPlan(id) }
        val fromDraft = withContext(Dispatchers.IO) { store.hasDraftAiPlan(id) }
        val config = if (saved == null) AiPackageConfiguration(scene, listOf(RenderPlan.Visit(java.util.UUID.randomUUID().toString(), scene.startStateId, null, 90)))
            else AiPackageConfiguration(scene, saved.visits, saved.effects, saved.width, saved.height, fromDraft)
        aiConfigurationBaseline = config
        mutableState.update { it.copy(aiConfiguration = config, aiConfigurationChanged = false) }
    }

    fun clearAiConfiguration() {
        if (!state.value.busy && !savePickerPending) {
            aiConfigurationBaseline = null
            mutableState.update { it.copy(aiConfiguration = null, aiConfigurationChanged = false) }
        }
    }

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
    fun applyAiAnnotation(visitId: String, text: String) = editAi { it.withAnnotation(visitId, text) }
    fun removeAiAnnotation(visitId: String) = editAi { it.withoutAnnotation(visitId) }
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
        try {
            val changed = change(config)
            val differs = aiConfigurationBaseline?.let { !changed.sameAnimation(it) } ?: true
            mutableState.update { it.copy(aiConfiguration = changed, aiConfigurationChanged = differs, exportFile = null, message = null) }
        }
        catch (error: IllegalStateException) { message(error.message ?: "动画配置无效。") }
        catch (error: IllegalArgumentException) { message(error.message ?: "动画配置无效。") }
    }
    fun prepareAiExport() = execute("生成并回读验证 AI 数据包") {
        val config = state.value.aiConfiguration ?: error("请先选择固定版本。")
        val plan = config.resolve()
        exportDigest = null; exportLength = null; pendingExportFile = null; exportDeliverySelection = null
        mutableState.update { it.copy(exportFile = null) }
        val file = withContext(Dispatchers.IO) { store.exportAiRelease(config.scene.releaseId, plan) }
        // The store has confirmed its local configuration write, before hashing or the system picker.
        aiConfigurationBaseline = config
        mutableState.update { it.copy(aiConfigurationChanged = false) }
        exportDigest = withContext(Dispatchers.IO) { ViewerPackageCodec.sha256(file) }; exportLength = file.length()
        mutableState.update { it.copy(exportFile = file) }
    }

    fun beginExportPicker(deliveryPageVisible: Boolean = true): Boolean {
        val selected = exportDeliverySelection
        if (selected != null && (!deliveryPageVisible || state.value.deliverySelection != selected || state.value.deliveryRelease == null)) {
            cancelExportPicker()
            return false
        }
        if (state.value.busy || savePickerPending || state.value.shareChooserOpen || state.value.pendingShare != null || state.value.exportFile == null || exportDigest == null) return false
        savePickerPending = true
        pendingExportFile = state.value.exportFile
        mutableState.update { it.copy(exportFile = null) }
        return true
    }

    fun cancelExportPicker() {
        savePickerPending = false; exportDigest = null; exportLength = null; pendingExportFile = null; exportDeliverySelection = null
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

    private suspend fun decode(scene: ViewerScene, stateId: String, candidateId: String?, session: PlaybackSession? = null): Bitmap = withContext(Dispatchers.IO) {
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
        // The file path may have been deleted/reimported while decoding. A post-read binding
        // check prevents replacement pixels from being published under the previous graph.
        if (session != null && !store.isPlaybackCurrent(session)) {
            bitmap.recycle()
            throw PlaybackSessionExpiredException()
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

    fun cancel() {
        if (playerTask?.isActive == true) {
            // The visible busy action belongs to the player, not the independent package task.
            // Keep an already displayed visit; only abandon a transition that has not advanced.
            cancelPlayerWork()
            mutableState.update { current ->
                val player = current.player
                current.copy(player = if (player?.traversal()?.pendingEdgeId != null)
                    player.applyTraversal(player.traversal().cancelTransition(player.scene)).copy(video = null)
                    else player,
                    message = if (player?.candidateId != null)
                        "已取消处理，保留当前画面；请点重来后继续试走。"
                    else "已取消读取，保留当前画面")
            }
            return
        }
        if (deliveryExportActive || (exportDeliverySelection != null && state.value.exportFile != null)) cancelExportPicker()
        task?.cancel()
        mutableState.update { it.copy(pendingShare = null) }
    }
    fun message(text: String) { mutableState.update { it.copy(message = text) } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    private fun execute(label: String, settle: (suspend (Throwable?) -> Unit)? = null, block: suspend () -> Unit) {
        if (state.value.busy || savePickerPending || state.value.shareChooserOpen || state.value.pendingShare != null || state.value.exportFile != null) return
        cancellationNote = null
        mutableState.update { it.copy(busy = true, stage = label, message = null) }
        task = viewModelScope.launch {
            var failure: Throwable? = null
            try { block() }
            catch (cancelled: CancellationException) {
                failure = cancelled
                if (settle == null) {
                    mutableState.update { it.copy(player = null, playerBitmap = null, playbackResume = null, pendingShare = null) }
                    message(cancellationNote ?: "已取消当前处理；完整保存的版本保留")
                }
                throw cancelled
            } catch (error: Exception) {
                failure = error
                if (error is PlaybackSessionExpiredException) mutableState.update { it.copy(playbackResume = null) }
                val detail = when (error) {
                    is DocumentSaveException -> error.message
                    is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf { it.length <= 240 && '/' !in it && '\\' !in it }
                    is IOException -> null
                    else -> null
                }
                if (settle == null) message(detail ?: "$label 未完成，请检查文件和可用空间后重试")
            } finally {
                // A cancellation can arrive after an atomic commit. Re-read library truth without
                // deleting the successfully published version or manufacturing a success message.
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        runCatching { store.listReleases() }.fold(
                            onSuccess = { items -> mutableState.update { it.copy(releases = items, libraryReadFailed = false) } },
                            onFailure = { mutableState.update { it.copy(libraryReadFailed = true) } },
                        )
                        runCatching { store.listCandidates() }.getOrNull()?.let { candidates -> mutableState.update { it.copy(pendingCandidates = candidates) } }
                    } finally {
                        // Returning to a cancelled dispatcher can throw again. Clear inside this
                        // barrier, last: busy prevents another operation from starting before here.
                        try { settle?.invoke(failure) }
                        finally { mutableState.update { it.copy(busy = false, stage = null) } }
                    }
                }
            }
        }
    }

    private class DocumentSaveException(message: String) : Exception(message)
    private class PlaybackSessionExpiredException : IllegalStateException("此观看会话已失效，请返回演示库重新打开。")
    private companion object { const val MAX_VISITS = 256 }
}
