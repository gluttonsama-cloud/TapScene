package com.tapscene.recording

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import android.os.SystemClock
import com.tapscene.data.SourceRepository
import com.tapscene.data.WorkspaceStore
import com.tapscene.data.ProjectStore
import com.tapscene.media.MediaLimits
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns one process-local capture. The gate also spans suspendable source registration, so all
 * callback/stop/recovery paths are serialized. Only media-free journal state survives a process.
 */
object RecordingCoordinator {
    private val thread by lazy { HandlerThread("TapSceneRecording").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    private val scope by lazy { CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher()) }
    private val gate = Mutex()
    private val mutableState = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = mutableState.asStateFlow()

    @Volatile private var pending: PendingGrant? = null
    @Volatile private var owner: RecordingService? = null
    @Volatile private var stopRequest: Pair<String, RecordingStopReason>? = null
    private val clickGate = RecordingClickGate()
    private val projectionCleanup = Executors.newSingleThreadExecutor { task -> Thread(task, "TapSceneProjectionCleanup").apply { isDaemon = true } }
    @Volatile private var active: ActiveRecording? = null
    private val sealedPending = mutableSetOf<String>()

    /** Recovery never resumes capture, reuses a grant or treats an unsealed MP4 as usable. */
    fun recover(context: Context) {
        val app = context.applicationContext
        serial {
            if (!mutableState.value.isBusy && active == null && pending == null) {
                try { recoverLocked(app) } catch (_: Exception) {
                    mutableState.value = RecordingUiState(
                        phase = RecordingPhase.Failed, error = "录制恢复检查失败，请检查本机空间后重试。",
                    )
                }
            }
        }
    }

    /** Call only from an Activity result containing this new session's explicit user consent. */
    fun start(context: Context, projectId: String, resultCode: Int, consentIntent: Intent, clickSession: RecordingClickSession? = null) {
        clickSession?.let { clickGate.issue(it.sessionId) }
        val app = context.applicationContext
        serial {
            if (clickSession != null && !clickGate.permits(clickSession.sessionId)) return@serial
            if (mutableState.value.isBusy || active != null || pending != null) return@serial
            if (resultCode != Activity.RESULT_OK) {
                mutableState.value = RecordingUiState(phase = RecordingPhase.Failed, error = "未获得本次录制授权。")
                return@serial
            }
            try {
                RecordingJournalStore.requireUuid(projectId)
                recoverLocked(app)
                // UI recovery is asynchronous. Recheck persisted pending work here so a tap
                // from a stale Idle screen cannot hide it behind a second recording session.
                if (sealedPending.isNotEmpty() || mutableState.value.canRetry) return@serial
                clickSession?.let {
                    require(Build.VERSION.SDK_INT >= 33) { "点击链需要 Android 13 及以上" }
                    RecordingJournalStore.requireUuid(it.sessionId)
                    RecordingJournalStore.requireUuid(it.sourceId)
                    require(it.width > 0 && it.height > 0)
                }
                val grant = PendingGrant(clickSession?.sessionId ?: UUID.randomUUID().toString(), projectId,
                    clickSession?.sourceId ?: UUID.randomUUID().toString(), resultCode, consentIntent, clickSession)
                stopRequest = null
                pending = grant
                mutableState.value = RecordingUiState(
                    phase = RecordingPhase.Starting, sessionId = grant.sessionId,
                    projectId = grant.projectId, sourceId = grant.sourceId,
                )
                // The start Intent carries only an ID. The grant is never serialized to disk or
                // service redelivery extras; process death deliberately loses the authorization.
                app.startForegroundService(Intent(app, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_SESSION_ID, grant.sessionId))
            } catch (_: Exception) {
                pending = null
                mutableState.value = mutableState.value.copy(
                    phase = RecordingPhase.Failed, error = "无法开始录制，请返回页面重新授权。", canRetry = sealedPending.isNotEmpty(),
                )
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun stop(context: Context) {
        // Mark synchronously so a stop arriving during prepare() prevents the queued start.
        mutableState.value.sessionId?.let { stopRequest = it to RecordingStopReason.User }
        active?.backend?.stopAcceptingFrames()
        serial {
            val recording = active
            if (recording != null) stopLocked(recording, RecordingStopReason.User)
            else if (pending != null) {
                pending = null
                mutableState.value = mutableState.value.copy(
                    phase = RecordingPhase.Interrupted, stopReason = RecordingStopReason.User,
                    error = "录制准备已取消。", canRetry = sealedPending.isNotEmpty(),
                )
                owner?.finishSession()
                owner = null
            }
        }
    }

    /** Read immediately before each gesture. A queued Stop fences execution synchronously. */
    fun canDispatchClicks(sessionId: String, projectId: String, sourceId: String): Boolean {
        val value = mutableState.value
        return value.sessionId == sessionId && value.projectId == projectId && value.sourceId == sourceId && value.phase == RecordingPhase.Recording &&
            value.clickCaptureReady && stopRequest?.first != sessionId && clickGate.permits(sessionId)
    }

    /** Stable session delegate; it never retains a backend from an earlier capture. */
    fun frameAnchorPort(sessionId: String): FrameAnchorPort = object : FrameAnchorPort {
        private fun current(): FrameAnchorPort = active?.takeIf {
            it.journal.sessionId == sessionId && !it.stopping && stopRequest?.first != sessionId
        }?.backend?.anchors ?: FrameAnchorPort.None
        override fun before(action: FrameAnchorAction): AnchorResult = current().before(action)
        override fun markGestureCompleted(action: FrameAnchorAction) = current().markGestureCompleted(action)
        override fun afterWait(action: FrameAnchorAction): AnchorResult = current().afterWait(action)
        override fun cancelEpoch(reason: FrameMissingReason) = current().cancelEpoch(reason)
    }

    /** Covers cancellation before the asynchronous start command has published its session ID. */
    fun stopClickSession(context: Context, sessionId: String) {
        clickGate.cancel(sessionId)
        if (mutableState.value.sessionId == sessionId) stop(context)
    }

    /** Revalidates and registers only durably sealed videos, always with their original source ID. */
    fun retrySealed(context: Context) {
        val app = context.applicationContext
        serial {
            if (mutableState.value.isBusy || active != null || pending != null) return@serial
            try {
                recoverLocked(app)
                val store = RecordingJournalStore(app)
                for (journal in store.readAll().filter { it.phase == JournalPhase.Sealed }) {
                    registerLocked(app, store, journal)
                }
                // If an older retry failed, do not hide it behind a newer successful registration.
                if (sealedPending.isNotEmpty()) recoverLocked(app)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    phase = RecordingPhase.Failed, error = "封口片段暂时无法登记，请检查本机空间后重试。",
                    canRetry = sealedPending.isNotEmpty(),
                )
            }
        }
    }

    /** Call after the author explicitly confirms discarding this displayed pending session. */
    fun discardSealed(context: Context) {
        val app = context.applicationContext
        val requestedSession = mutableState.value.sessionId ?: return
        serial {
            if (mutableState.value.isBusy || active != null || pending != null) return@serial
            try {
                val store = RecordingJournalStore(app)
                val journal = store.readAll().firstOrNull { it.sessionId == requestedSession } ?: return@serial
                if (journal.phase !in setOf(JournalPhase.Sealed, JournalPhase.DiscardPending)) return@serial
                // Persist explicit discard intent before deleting. Recovery can safely finish it;
                // it must never subsequently recreate a source from this discarded raw video.
                val discarded = journal.copy(phase = JournalPhase.DiscardPending)
                store.save(discarded)
                if (!store.deleteRaw(journal.sessionId)) throw IllegalStateException("Cleanup incomplete")
                cleanupUnregisteredEvidence(app, journal)
                store.save(discarded.copy(phase = JournalPhase.Discarded))
                sealedPending -= journal.sessionId
                mutableState.value = discarded.ui(RecordingPhase.Interrupted).copy(
                    error = "未完成录制的临时文件已清理。", canRetry = sealedPending.isNotEmpty(),
                )
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(phase = RecordingPhase.Interrupted,
                    error = "临时文件尚未清理完，可再次清理；已登记素材不会被删除。", canRetry = true)
            }
        }
    }

    /** Instrumentation barrier for commands already enqueued; it never starts or simulates capture. */
    internal suspend fun awaitCommandBoundary() {
        withContext(handler.asCoroutineDispatcher()) { gate.withLock { } }
    }

    internal fun isClickSession(sessionId: String?): Boolean = pending?.takeIf { it.sessionId == sessionId }?.clickSession != null || active?.takeIf { it.journal.sessionId == sessionId }?.clickSession != null

    internal fun isPending(sessionId: String): Boolean = pending?.sessionId == sessionId
    internal fun isOwnedBy(service: RecordingService): Boolean = owner === service

    internal fun startFromService(service: RecordingService, sessionId: String) {
        serial {
            val grant = pending?.takeIf { it.sessionId == sessionId }
            if (grant == null || active != null) {
                if (owner !== service && pending == null) service.finishSession()
                return@serial
            }
            if (stopRequest?.first == sessionId || (grant.clickSession != null && !clickGate.permits(sessionId))) {
                pending = null
                mutableState.value = mutableState.value.copy(
                    phase = RecordingPhase.Interrupted, stopReason = stopRequest?.second,
                    error = "录制准备已取消。", canRetry = sealedPending.isNotEmpty(),
                )
                service.finishSession()
                return@serial
            }
            pending = null // Consume exactly once; never retain the grant after this call.
            owner = service
            var recording: ActiveRecording? = null
            try {
                val app = service.applicationContext
                val store = RecordingJournalStore(app)
                val budget = SourceRepository(app).budget(grant.projectId)
                if (budget.sourceCount >= 3) throw RecordingStartException("此项目已达到 3 段录屏上限。")
                val maxDurationMs = (minOf(MediaLimits.MAX_DURATION_US, budget.remainingDurationUs) / 1_000 - 1_000).toInt()
                if (maxDurationMs < 1_000) throw RecordingStartException("此项目剩余录屏时长不足，请先整理素材。")
                // Reserve room for muxing, journal writes and the independent source-store copy.
                val available = StatFs(app.noBackupFilesDir.absolutePath).availableBytes
                val maxBytes = minOf(MediaLimits.MAX_BYTES, budget.remainingBytes, (available - STORAGE_RESERVE_BYTES) / 2) - MUX_RESERVE_BYTES
                if (maxBytes < MIN_RECORDING_BYTES) throw RecordingStartException("本机空间或项目素材额度不足，暂时无法录制。")
                if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                    throw RecordingStartException("请解锁屏幕后重新授权录制。")
                }
                val journal = RecordingJournal(
                    grant.sessionId, grant.projectId, grant.sourceId,
                    JournalPhase.Starting, System.currentTimeMillis(),
                )
                store.create(journal)
                val canvas = RecordingEncoder.canvas(service)
                grant.clickSession?.let {
                    if (canvas.width != it.width || canvas.height != it.height) {
                        throw RecordingStartException("屏幕尺寸已改变，请重新编排点位。")
                    }
                }
                recording = ActiveRecording(service, store, journal, canvas, maxDurationMs, maxBytes, grant.clickSession)
                active = recording
                val current = recording
                val backend = if (grant.clickSession != null) {
                    FrameRecordingBackend.prepare(service, grant.projectId, grant.clickSession, current.canvas,
                        store.part(grant.sessionId), maxDurationMs, maxBytes,
                        onReady = { encodedSampleReady(grant.sessionId) },
                        onError = { requestStop(grant.sessionId, RecordingStopReason.RecorderError) },
                        onOwned = { current.backend = it })
                } else RecorderRecordingBackend(RecordingEncoder.prepare(service, current.canvas,
                    store.part(grant.sessionId), maxDurationMs, maxBytes,
                    onInfo = { what ->
                        when (what) {
                            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> requestStop(grant.sessionId, RecordingStopReason.DurationLimit)
                            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING,
                            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> requestStop(grant.sessionId, RecordingStopReason.SizeLimit)
                        }
                    },
                    onError = { requestStop(grant.sessionId, RecordingStopReason.RecorderError) }))
                current.backend = backend
                current.journal = journal.copy(
                    width = backend.encoding.width, height = backend.encoding.height,
                    fps = backend.encoding.fps, bitrate = backend.encoding.bitrate,
                )
                store.save(current.journal)
                checkStartNotCancelled(grant.sessionId)
                val projection = service.getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(grant.resultCode, grant.consentIntent)
                    ?: throw RecordingStartException("系统未建立本次录制会话，请重新授权。")
                current.projection = projection
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        val reason = if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                            RecordingStopReason.ScreenLocked
                        } else RecordingStopReason.SystemStopped
                        requestStop(grant.sessionId, reason)
                    }
                    override fun onCapturedContentResize(width: Int, height: Int) {
                        captureResized(grant.sessionId, width, height)
                    }
                }
                current.callback = callback
                projection.registerCallback(callback, handler)
                val surface = backend.captureSurface
                checkStartNotCancelled(grant.sessionId)
                // One grant, one display. No secure/own-content bypass flags, audio or OCR.
                current.display = projection.createVirtualDisplay(
                    "TapScene local recording", backend.captureWidth, backend.captureHeight,
                    current.canvas.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, handler,
                ) ?: throw RecordingStartException("系统未建立录制画布，请重新授权。")
                checkStartNotCancelled(grant.sessionId)
                backend.start()
                current.started = true
                current.startedAtElapsedMs = SystemClock.elapsedRealtime()
                current.journal = current.journal.copy(phase = JournalPhase.Recording)
                store.save(current.journal)
                mutableState.value = current.journal.ui(RecordingPhase.Recording).copy(clickCaptureReady = clickReady(current))
                scheduleTick(current.journal.sessionId)
            } catch (error: Exception) {
                val failedRecording = recording
                if (failedRecording != null) {
                    // Even an error after start must immediately stop collection. Failed starts
                    // are never promoted to sealed or silently registered.
                    val released = release(failedRecording, stopRecorder = true)
                    if (released?.released != true) {
                        quarantine(failedRecording)
                        return@serial
                    }
                    (failedRecording.backend as? FrameRecordingBackend)?.discardEvidence()
                    val failed = failedRecording.journal.copy(phase = JournalPhase.Failed,
                        stopReason = stopRequest?.takeIf { it.first == sessionId }?.second ?: RecordingStopReason.RecorderError)
                    runCatching { failedRecording.store.save(failed) }
                    runCatching { failedRecording.store.deleteRaw(failed.sessionId) }
                }
                active = null
                owner = null
                service.finishSession()
                mutableState.value = mutableState.value.copy(
                    phase = if (error is StartCancelledException) RecordingPhase.Interrupted else RecordingPhase.Failed,
                    stopReason = stopRequest?.takeIf { it.first == sessionId }?.second,
                    error = if (error is StartCancelledException) "录制准备已取消。" else (error as? RecordingStartException)?.message ?: "录制启动失败，请检查本机空间后重新授权。",
                    canRetry = sealedPending.isNotEmpty(),
                )
            }
        }
    }

    internal fun failServiceStart(sessionId: String) {
        serial {
            if (pending?.sessionId == sessionId) {
                pending = null
                mutableState.value = mutableState.value.copy(
                    phase = RecordingPhase.Failed, error = "无法显示录制前台提示，请返回页面重新授权。",
                    canRetry = sealedPending.isNotEmpty(),
                )
            }
        }
    }

    internal fun stopFromService(service: RecordingService, reason: RecordingStopReason) {
        if (owner === service || (pending != null && pending?.sessionId == service.sessionId)) {
            mutableState.value.sessionId?.let { stopRequest = it to reason }
            active?.backend?.stopAcceptingFrames()
        }
        serial {
            val current = active
            if (current != null && current.service === service) stopLocked(current, reason)
            else if (pending != null && pending?.sessionId == service.sessionId && stopRequest?.first == pending?.sessionId) {
                pending = null
                mutableState.value = mutableState.value.copy(phase = RecordingPhase.Interrupted,
                    stopReason = reason, error = "录制准备已取消。", canRetry = sealedPending.isNotEmpty())
                service.finishSession()
            } else if (owner == null && pending == null) service.finishSession()
        }
    }

    internal fun configurationChanged(service: RecordingService) {
        serial {
            val current = active?.takeIf { it.service === service && !it.stopping } ?: return@serial
            try {
                val newCanvas = RecordingEncoder.canvas(service)
                if (newCanvas.width == current.canvas.width && newCanvas.height == current.canvas.height) return@serial
                if (Build.VERSION.SDK_INT < 32 || current.clickSession != null) stopLocked(current, RecordingStopReason.DisplayChanged)
                // API32+ uniformly fits and centers changed content into the unchanged output.
                // API34+ has authoritative capture-region callbacks; earlier metrics are NOT
                // treated as the exact captured bounds or converted into hotspot coordinates.
            } catch (_: Exception) {
                stopLocked(current, RecordingStopReason.DisplayChanged)
            }
        }
    }

    private fun captureResized(sessionId: String, width: Int, height: Int) {
        // Fence the main-thread dispatcher before this observation waits for the recording gate.
        active?.takeIf { it.journal.sessionId == sessionId }?.clickSession?.let {
            if (width != it.width || height != it.height) {
                stopRequest = sessionId to RecordingStopReason.DisplayChanged
                active?.backend?.stopAcceptingFrames()
            }
        }
        serial {
            val current = active?.takeIf { it.journal.sessionId == sessionId && !it.stopping } ?: return@serial
            if (width <= 0 || height <= 0) {
                stopLocked(current, RecordingStopReason.DisplayChanged)
                return@serial
            }
            current.clickSession?.let {
                if (width != it.width || height != it.height) {
                    stopLocked(current, RecordingStopReason.DisplayChanged)
                    return@serial
                }
                current.captureGeometryConfirmed = true
                if (mutableState.value.phase == RecordingPhase.Recording) {
                    mutableState.value = mutableState.value.copy(clickCaptureReady = clickReady(current))
                }
            }
            // Both the encoder Surface and VirtualDisplay retain the SAME dimensions. Changing
            // only one introduces another transform. API32+ documents fit/center letterboxing;
            // the API34 callback arrives for the initial region as well as later resize events.
            val layout = RecordingContentLayout.fit(current.journal.width, current.journal.height, width, height)
            if (layout != current.journal.latestContentLayout) {
                current.journal = current.journal.copy(latestContentLayout = layout)
                try { current.store.save(current.journal) } catch (_: Exception) {
                    stopLocked(current, RecordingStopReason.LowStorage)
                }
            }
        }
    }

    private fun clickReady(current: ActiveRecording): Boolean = current.clickSession != null &&
        current.backend?.hasEncodedSample == true && (Build.VERSION.SDK_INT < 34 || current.captureGeometryConfirmed)

    private fun encodedSampleReady(sessionId: String) {
        serial {
            val current = active?.takeIf { it.journal.sessionId == sessionId && !it.stopping } ?: return@serial
            if (mutableState.value.phase == RecordingPhase.Recording) {
                mutableState.value = mutableState.value.copy(clickCaptureReady = clickReady(current))
            }
        }
    }

    private fun requestStop(sessionId: String, reason: RecordingStopReason) {
        if (active?.journal?.sessionId != sessionId && pending?.sessionId != sessionId) return
        stopRequest = sessionId to reason
        active?.takeIf { it.journal.sessionId == sessionId }?.backend?.stopAcceptingFrames()
        serial { active?.takeIf { it.journal.sessionId == sessionId }?.let { stopLocked(it, reason) } }
    }

    private suspend fun stopLocked(current: ActiveRecording, reason: RecordingStopReason) {
        if (active !== current || current.stopping) return
        stopRequest = current.journal.sessionId to reason
        current.stopping = true
        current.backend?.stopAcceptingFrames()
        current.journal = current.journal.copy(
            phase = JournalPhase.Stopping, elapsedMs = current.elapsedMs(), stopReason = reason,
        )
        mutableState.value = current.journal.ui(RecordingPhase.Stopping)
        // Detach/finish is launched before any fsync. If the process dies meanwhile, the older
        // Starting/Recording journal remains unsealed and cannot grant recovery registration.
        val result = release(current, stopRecorder = true)
        if (result?.released != true) { quarantine(current); return }
        runCatching { current.store.save(current.journal) }
        active = null
        owner = null
        current.service.finishSession()
        if (!result.sealable) {
            failUnsealed(current, "录制未能完整封口，可能停止过快；本段不会加入素材。")
            return
        }
        try {
            val sealed = current.store.seal(current.journal.sessionId)
            // Optional evidence never turns a healthy MP4 into a screenshot-only capture.
            runCatching { (current.backend as? FrameRecordingBackend)?.validateEvidence(sealed) }
            current.journal = current.journal.copy(phase = JournalPhase.Sealed)
            current.store.save(current.journal)
        } catch (_: Exception) {
            failUnsealed(current, "录制文件未能安全封口，请检查本机空间后重新录制。")
            return
        }
        sealedPending += current.journal.sessionId
        registerLocked(current.service.applicationContext, current.store, current.journal, current.backend as? FrameRecordingBackend)
    }

    /** Detach first; a driver timeout retains ownership and cannot license file deletion/restart. */
    private suspend fun release(current: ActiveRecording, stopRecorder: Boolean): RecordingBackendResult? {
        current.backend?.stopAcceptingFrames()
        val released = current.releaseResult ?: CompletableDeferred<RecordingBackendResult>().also { result ->
            current.releaseResult = result
            projectionCleanup.execute {
                var detached = true
                runCatching { current.display?.surface = null }.onFailure { detached = false }
                runCatching { current.display?.release() }.onFailure { detached = false }
                val projection = current.projection
                current.callback?.let { callback -> runCatching { projection?.unregisterCallback(callback) }.onFailure { detached = false } }
                runCatching { projection?.stop() }.onFailure { detached = false }
                if (detached) { current.display = null; current.projection = null; current.callback = null }
                val backend = current.backend
                if (backend == null) result.complete(RecordingBackendResult(false, detached))
                else {
                    val backendDone = backend.finish(stopRecorder && current.started)
                    val detachConfirmed = detached
                    scope.launch {
                        val outcome = runCatching { backendDone.await() }.getOrNull()
                        result.complete(RecordingBackendResult(outcome?.sealable == true && detachConfirmed,
                            outcome?.released == true && detachConfirmed))
                    }
                }
            }
        }
        return withTimeoutOrNull(7_000) { released.await() }
    }

    private fun quarantine(current: ActiveRecording) {
        current.stopping = true
        mutableState.value = current.journal.ui(RecordingPhase.Failed).copy(clickCaptureReady = false,
            error = "停止请求已发送，但录制驱动尚未确认释放。暂不能开始新录制；请检查系统录屏指示。")
        // Exactly one outstanding cleanup owns these handles. Never create replacement workers.
        scope.launch {
            val released = current.releaseResult?.await() ?: return@launch
            gate.withLock {
                if (active !== current || !released.released) return@withLock
                active = null; owner = null; current.service.finishSession()
                failUnsealed(current, "录制停止超过等待期限，本段未登记；需要重新授权录制。")
            }
        }
    }

    private fun failUnsealed(current: ActiveRecording, error: String) {
        (current.backend as? FrameRecordingBackend)?.discardEvidence()
        val failed = current.journal.copy(phase = JournalPhase.Failed)
        runCatching { current.store.save(failed) }
        val cleaned = runCatching { current.store.deleteRaw(failed.sessionId) }.getOrDefault(false)
        mutableState.value = failed.ui(RecordingPhase.Failed).copy(
            error = if (cleaned) error else "$error 临时文件将在下次打开时再次清理。",
            canRetry = sealedPending.isNotEmpty(),
        )
    }

    private suspend fun registerLocked(context: Context, store: RecordingJournalStore, journal: RecordingJournal, frameBackend: FrameRecordingBackend? = null) {
        check(journal.phase == JournalPhase.Sealed)
        mutableState.value = journal.ui(RecordingPhase.Registering)
        try {
            val source = SourceRepository(context).registerRecording(journal.projectId, journal.sourceId, store.sealed(journal.sessionId), "本机无声录制.mp4")
            // This sidecar cannot roll back a durably registered source. Recovery can finish its marker.
            if (frameBackend != null) frameBackend.markRegistered(source.metadata.sha256)
            else withContext(Dispatchers.IO) { runCatching {
                FrameEvidenceStore(context).registered(journal.sessionId, journal.sourceId, source.metadata.sha256)
            } }
        } catch (_: Exception) {
            frameBackend?.closeEvidenceWriter()
            sealedPending += journal.sessionId
            mutableState.value = journal.ui(RecordingPhase.Failed).copy(
                error = "片段已封口，但校验或登记尚未成功。可重试登记，录制不会自动继续。", canRetry = true,
            )
            return
        }
        // The repository owns its independent source now. Nothing below can roll that back.
        try {
            store.save(journal.copy(phase = JournalPhase.Registered))
            sealedPending -= journal.sessionId
        } catch (_: Exception) {
            sealedPending += journal.sessionId
            mutableState.value = journal.ui(RecordingPhase.Completed).copy(
                error = "素材已登记；恢复记录尚未写完，可安全重试登记。", canRetry = true,
            )
            return // Keep sealed raw for idempotent retry, never delete repository sources.
        }
        val cleaned = runCatching { store.deleteRaw(journal.sessionId) }.getOrDefault(false)
        mutableState.value = journal.ui(RecordingPhase.Completed).copy(
            error = if (cleaned) null else "素材已登记；临时副本将在下次打开时清理。",
            canRetry = sealedPending.isNotEmpty(),
        )
    }

    private fun recoverLocked(context: Context) {
        val store = RecordingJournalStore(context)
        runCatching { FrameEvidenceStore(context).recoverInterrupted() }
        val journals = store.readAll()
        sealedPending.clear()
        var latest: RecordingUiState? = null
        var pendingState: RecordingUiState? = null
        for (journal in journals) {
            runCatching {
                if (ProjectStore(context).readProject(journal.projectId) == null) {
                    FrameEvidenceStore(context).deleteAfterProjectCommit(journal.projectId)
                }
            }
            when (journal.phase) {
                JournalPhase.Sealed -> {
                    sealedPending += journal.sessionId
                    pendingState = journal.ui(RecordingPhase.Interrupted).copy(
                        error = "发现已封口片段，可重试校验登记；录制不会自动继续。", canRetry = true,
                    )
                }
                JournalPhase.DiscardPending, JournalPhase.Discarded -> {
                    val cleaned = store.deleteRaw(journal.sessionId)
                    cleanupUnregisteredEvidence(context, journal)
                    if (cleaned && journal.phase != JournalPhase.Discarded) {
                        runCatching { store.save(journal.copy(phase = JournalPhase.Discarded)) }
                    }
                    latest = journal.ui(RecordingPhase.Interrupted).copy(
                        error = if (cleaned) "未完成录制的临时文件已清理。" else "临时文件尚未清理完，可再次清理。",
                        canRetry = !cleaned,
                    )
                    if (!cleaned) pendingState = latest
                }
                JournalPhase.Registered -> {
                    val cleaned = store.deleteRaw(journal.sessionId)
                    runCatching {
                        val evidence = FrameEvidenceStore(context)
                        if (WorkspaceStore(context, journal.projectId).confirmSourceAbsent(journal.sourceId)) {
                            // A valid Registered journal plus an authoritative absent record means
                            // the previously committed source was deleted, not merely unreadable.
                            evidence.deleteAfterSourceCommit(journal.sourceId)
                        } else if (ProjectStore(context).readProject(journal.projectId) == null) {
                            evidence.deleteAfterProjectCommit(journal.projectId)
                        } else {
                            val source = SourceRepository(context).frameEvidenceSourceAccessor()
                                .registeredSource(journal.projectId, journal.sessionId, journal.sourceId)
                            if (source != null) evidence.registered(journal.sessionId, journal.sourceId, source.sourceSha256)
                        }
                    }
                    // Registered is historical. The author may since have deleted that source;
                    // recovery must not advertise it as currently available or recreate it.
                    latest = RecordingUiState(
                        error = if (cleaned) null else "部分录制临时副本尚未清理，将在下次打开时重试。",
                    )
                }
                else -> {
                    val interrupted = if (journal.phase in setOf(JournalPhase.Starting, JournalPhase.Recording, JournalPhase.Stopping)) {
                        journal.copy(phase = JournalPhase.Interrupted, stopReason = RecordingStopReason.ProcessInterrupted)
                    } else journal
                    runCatching { store.save(interrupted) }
                    val cleaned = store.deleteRaw(journal.sessionId)
                    cleanupUnregisteredEvidence(context, journal)
                    latest = interrupted.ui(RecordingPhase.Interrupted).copy(
                        error = if (cleaned) "上次录制未完整封口，未加入素材；重新录制需要新的系统授权。"
                        else "上次录制未完整封口，临时文件清理未完成，请检查本机空间。",
                    )
                }
            }
        }
        for (id in store.orphanSessionIds(journals.map { it.sessionId }.toSet())) {
            // A corrupt journal provides no proof that stop/fsync/registration happened.
            // Only the two exact session-owned raw names are eligible; sources are untouched.
            if (!store.deleteRaw(id)) {
                latest = RecordingUiState(phase = RecordingPhase.Interrupted, error = "发现未完成录制，部分临时文件尚未清理。")
            }
        }
        mutableState.value = pendingState ?: latest ?: RecordingUiState()
    }

    /** A null/unreadable source accessor is insufficient proof of deletion. Read metadata explicitly. */
    private fun cleanupUnregisteredEvidence(context: Context, journal: RecordingJournal) {
        runCatching {
            val evidence = FrameEvidenceStore(context)
            if (WorkspaceStore(context, journal.projectId).confirmSourceAbsent(journal.sourceId)) {
                evidence.deleteUnregisteredSession(journal.sessionId, journal.sourceId)
            } else {
                // Registration may have committed before its journal/sidecar marker did. Discard
                // removes only the temporary MP4; complete the marker for that verified source.
                val source = SourceRepository(context).frameEvidenceSourceAccessor()
                    .registeredSource(journal.projectId, journal.sessionId, journal.sourceId)
                if (source != null) evidence.registered(journal.sessionId, journal.sourceId, source.sourceSha256)
            }
        }
    }

    private fun scheduleTick(sessionId: String) {
        scope.launch {
            delay(250)
            gate.withLock {
                val current = active?.takeIf { it.journal.sessionId == sessionId && !it.stopping } ?: return@withLock
                val elapsed = current.elapsedMs()
                mutableState.value = mutableState.value.copy(elapsedMs = elapsed)
                val reason = try {
                    when {
                        elapsed >= current.maxDurationMs - 750 -> RecordingStopReason.DurationLimit
                        current.store.part(sessionId).length() >= current.maxBytes - MUX_RESERVE_BYTES -> RecordingStopReason.SizeLimit
                        StatFs(current.service.noBackupFilesDir.absolutePath).availableBytes <
                            STORAGE_RESERVE_BYTES + current.store.part(sessionId).length() -> RecordingStopReason.LowStorage
                        else -> null
                    }
                } catch (_: Exception) { RecordingStopReason.LowStorage }
                if (reason != null) stopLocked(current, reason)
                else {
                    if (elapsed - current.lastJournalTickMs >= 5_000) {
                        current.journal = current.journal.copy(elapsedMs = elapsed)
                        try {
                            current.store.save(current.journal)
                            current.lastJournalTickMs = elapsed
                        } catch (_: Exception) {
                            stopLocked(current, RecordingStopReason.LowStorage)
                        }
                    }
                    if (active === current) scheduleTick(sessionId)
                }
            }
        }
    }

    private fun checkStartNotCancelled(sessionId: String) {
        if (stopRequest?.first == sessionId || (active?.clickSession?.sessionId == sessionId && !clickGate.permits(sessionId))) throw StartCancelledException()
    }

    private class StartCancelledException : Exception()

    private fun serial(block: suspend () -> Unit) {
        scope.launch { gate.withLock { block() } }
    }

    private fun RecordingJournal.ui(phase: RecordingPhase) = RecordingUiState(
        phase, sessionId, projectId, sourceId, elapsedMs, stopReason,
    )

    private data class PendingGrant(
        val sessionId: String, val projectId: String, val sourceId: String,
        val resultCode: Int, val consentIntent: Intent,
        val clickSession: RecordingClickSession? = null,
    )

    private class ActiveRecording(
        val service: RecordingService, val store: RecordingJournalStore,
        var journal: RecordingJournal, val canvas: RecordingCanvas,
        val maxDurationMs: Int, val maxBytes: Long,
        val clickSession: RecordingClickSession? = null,
    ) {
        var captureGeometryConfirmed = false
        var backend: RecordingBackend? = null
        var releaseResult: CompletableDeferred<RecordingBackendResult>? = null
        var projection: MediaProjection? = null
        var callback: MediaProjection.Callback? = null
        var display: VirtualDisplay? = null
        var started = false
        var stopping = false
        var startedAtElapsedMs = 0L
        var lastJournalTickMs = 0L
        fun elapsedMs(): Long = if (startedAtElapsedMs == 0L) 0 else (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0)
    }

    private const val STORAGE_RESERVE_BYTES = 64L * 1024 * 1024
    private const val MUX_RESERVE_BYTES = 1024L * 1024
    private const val MIN_RECORDING_BYTES = 2L * 1024 * 1024
}
