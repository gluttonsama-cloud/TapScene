package com.tapscene.clickplan

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.tapscene.MainActivity
import com.tapscene.recording.RecordingClickSession
import com.tapscene.recording.RecordingCoordinator
import com.tapscene.recording.RecordingPhase
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ClickPlaybackUi(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val run: ClickRun? = null,
    val overlayVisible: Boolean = false,
)
internal data class ClickConsentRequest(val nonce: String, val plan: ClickPlan, val resume: Boolean, val viewport: ClickViewport)

/** Process-local capability owner. No boot resume, retained grant or automatic gesture retry. */
object ClickPlayback {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(ClickPlaybackUi())
    val state: StateFlow<ClickPlaybackUi> = mutable.asStateFlow()
    private var app: Context? = null
    private var service: ClickAccessibilityService? = null
    private var editorPlan: ClickPlan? = null
    private var consentRequest: ClickConsentRequest? = null
    private var engine: ClickRunEngine? = null
    private var runViewport: ClickViewport? = null
    private var guard = ClickGuard.Unknown
    private var requiredWindowAfter = Long.MAX_VALUE
    private var freshTarget = false
    private var resumeConfirmed = false
    private var arm: ClickTargetArm? = null
    private var recoveryDone = false
    private var geometryListener: DisplayManager.DisplayListener? = null
    val observing: Boolean get() = editorPlan != null || consentRequest != null || engine?.state?.isActive == true

    fun initialize(context: Context) {
        if (app != null) return
        app = context.applicationContext
        scope.launch {
            try {
                val runs = withContext(Dispatchers.IO) {
                    val store = ClickPlanStore(requireNotNull(app))
                    store.recoverInterruptedRuns()
                    store.readRuns()
                }
                if (engine == null) mutable.value = mutable.value.copy(run = runs.maxByOrNull { it.createdAtMs })
                recoveryDone = true
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(message = "点击链恢复检查未完成，请检查存储后重新打开应用。")
            }
        }
        scope.launch {
            RecordingCoordinator.state.collect { recording ->
                val current = engine ?: return@collect
                if (recording.sessionId != current.state.recordingSessionId) return@collect
                if (current.state.terminal) {
                    mutable.value = mutable.value.copy(busy = recording.isBusy, message = when (recording.phase) {
                        RecordingPhase.Completed -> "录屏已保存在本机，完整点击链与执行日志已保留；可返回录制页手动取帧校正。"
                        RecordingPhase.Failed, RecordingPhase.Interrupted -> "点击日志已保留；录屏未完成，请返回录制页检查或重试视频登记。"
                        else -> mutable.value.message
                    })
                    return@collect
                }
                when (recording.phase) {
                    RecordingPhase.Recording -> tryAdvance()
                    RecordingPhase.Stopping, RecordingPhase.Registering, RecordingPhase.Completed,
                    RecordingPhase.Interrupted, RecordingPhase.Failed -> current.stop(ClickStopReason.RecordingLost)
                    else -> Unit
                }
            }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = checkGeometry()
            override fun onDisplayRemoved(displayId: Int) = checkGeometry()
            override fun onDisplayChanged(displayId: Int) = checkGeometry()
        }
        geometryListener = listener
        requireNotNull(context.getSystemService(DisplayManager::class.java)).registerDisplayListener(listener, main)
    }

    internal fun connected(value: ClickAccessibilityService) {
        initialize(value)
        service = value
        guard = ClickGuard.Unknown
        mutable.value = mutable.value.copy(connected = true)
    }
    internal fun interrupted(value: ClickAccessibilityService) {
        if (service !== value) return
        value.hideEditor { }
        editorPlan = null; consentRequest = null; arm = null; freshTarget = false; resumeConfirmed = false
        guard = ClickGuard.Unknown
        engine?.takeIf { it.state.isActive }?.stop(ClickStopReason.ServiceDestroyed)
        mutable.value = mutable.value.copy(busy = false, message = "当前会话已中断。定位层已请求关闭，需重新核对后开始新一轮。")
    }
    internal fun permissionLost(value: ClickAccessibilityService) {
        if (service !== value) return
        value.hideEditor { }
        service = null; guard = ClickGuard.Unknown; consentRequest = null; editorPlan = null
        engine?.stop(ClickStopReason.PermissionLost)
        mutable.value = mutable.value.copy(connected = false, busy = false,
            message = "无障碍服务已断开，本轮停止。重新开启后需重新确认并授权。")
    }
    internal fun overlayChanged(visible: Boolean) { mutable.value = mutable.value.copy(overlayVisible = visible) }

    fun updatePlan(context: Context, plan: ClickPlan) {
        initialize(context)
        if (engine?.state?.isActive == true || consentRequest != null) return
        if (editorPlan?.planId == plan.planId) {
            editorPlan = plan
            runCatching { service?.showEditor(plan) }.onFailure { failEditor("定位面板无法显示，请返回编辑。") }
        }
    }

    fun openEditor(context: Context, plan: ClickPlan) {
        initialize(context)
        val connected = service
        if (Build.VERSION.SDK_INT < 33) {
            mutable.value = mutable.value.copy(message = "点击链需要 Android 13 及以上以核对窗口所在显示器；本机仍可手动录屏和取帧。")
            return
        }
        if (!recoveryDone || connected == null || mutable.value.busy || RecordingCoordinator.state.value.isBusy || RecordingCoordinator.state.value.canRetry) {
            mutable.value = mutable.value.copy(message = "请先启用定位服务，并处理未完成录制。")
            return
        }
        if (!ClickDevice.shortPressesAllowed(plan)) {
            mutable.value = mutable.value.copy(message = "此设备的短按时长上限为 ${ClickDevice.maximumShortPressMs()} 毫秒，请缩短计划中的按压时长。")
            return
        }
        if (!ClickDevice.matches(context, plan)) {
            mutable.value = mutable.value.copy(message = "屏幕方向或尺寸已变，请按当前屏幕重新编排。")
            return
        }
        try {
            val saved = ClickPlanStore(context).getPlan(plan.projectId)
            check(saved?.digest == plan.digest)
            check(!ClickWindowPolicy.protectedPackage(plan.targetPackage))
            val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(plan.targetPackage))
            editorPlan = plan; guard = ClickGuard.Unknown
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            connected.showEditor(plan)
            mutable.value = mutable.value.copy(message = "可手动切换目标页面；添加点位只记录坐标。准备好后回到起始页。")
        } catch (_: Exception) { failEditor("无法打开目标 App 或定位点，请返回编辑重试。") }
    }

    internal fun addPoint(x: Int, y: Int) {
        val plan = editorPlan ?: return
        if (mutable.value.busy) return
        try {
            val context = requireNotNull(app)
            check(ClickDevice.matches(context, plan) && guard == ClickGuard.Allowed)
            val updated = plan.copy(revision = plan.revision + 1, actions = plan.actions + ClickAction(x = x, y = y))
            require(service?.currentViewport()?.contains(updated) == true)
            ClickPlanStore(context).savePlan(updated, plan.revision)
            editorPlan = updated
            service?.showEditor(updated)
        } catch (_: Exception) {
            val message = "点位未保存：请在 App 内容区定位，避开系统栏；最多 40 点且总计划不超过 120 秒。"
            mutable.value = mutable.value.copy(message = message)
            service?.notice(message)
            runCatching { service?.showEditor(plan) }
        }
    }

    internal fun prepareConsent(context: Context) {
        val plan = editorPlan ?: return
        if (mutable.value.busy) return
        try {
            val viewport = checkNotNull(service?.currentViewport())
            check(service?.canPreparePlayback == true)
            check(plan.actions.isNotEmpty() && ClickDevice.matches(context, plan) && viewport.contains(plan) && ClickDevice.shortPressesAllowed(plan))
            check(guard == ClickGuard.Allowed)
            check(ClickPlanStore(context).getPlan(plan.projectId)?.digest == plan.digest)
            service?.hideEditor { detached ->
                if (!detached) { failEditor("定位窗口尚未完全关闭，请返回应用停止并重试。"); return@hideEditor }
                val request = ClickConsentRequest(UUID.randomUUID().toString(), plan, false, viewport)
                consentRequest = request; editorPlan = null
                mutable.value = mutable.value.copy(busy = true, message = "点位已关闭，请确认目标起始页并授权整屏录制。")
                launchConfirmation(context, request)
            } ?: failEditor("定位服务已断开，请重新开启。")
        } catch (_: Exception) {
            val message = "请先确认目标 App 为全屏、安全起始页，点位和方向未改变。"
            mutable.value = mutable.value.copy(message = message)
            service?.notice(message)
        }
    }

    fun confirmResume(context: Context) {
        if (engine?.state?.phase != ClickRunPhase.Paused) return
        if (consentRequest != null) return
        val request = resumeRequest() ?: return
        launchConfirmation(context, request)
    }
    private fun launchConfirmation(context: Context, request: ClickConsentRequest) {
        try {
            context.startActivity(Intent(context, ClickConsentActivity::class.java)
                .putExtra("request", request.nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { cancelConsent(request.nonce) }
    }
    internal fun resumeRequest(): ClickConsentRequest? {
        val run = engine?.state?.takeIf { it.phase == ClickRunPhase.Paused } ?: return null
        val viewport = runViewport ?: return null
        if (service?.currentViewport() != viewport) {
            engine?.stop(ClickStopReason.DisplayChanged)
            return null
        }
        return ClickConsentRequest(UUID.randomUUID().toString(), run.plan, true, viewport).also { consentRequest = it }
    }
    internal fun request(nonce: String?): ClickConsentRequest? = consentRequest?.takeIf { it.nonce == nonce }
    internal fun cancelConsent(nonce: String) {
        if (consentRequest?.nonce != nonce) return
        consentRequest = null
        mutable.value = mutable.value.copy(busy = engine?.state?.isActive == true, message = "已取消，点击链没有继续。")
    }

    internal fun confirmed(nonce: String, resultCode: Int? = null, grant: Intent? = null): Boolean {
        val request = request(nonce) ?: return false
        val context = app ?: return false
        try {
            check(service != null && !mutable.value.overlayVisible && ClickDevice.matches(context, request.plan))
            check(service?.currentViewport() == request.viewport && request.viewport.contains(request.plan) && ClickDevice.shortPressesAllowed(request.plan))
            guard = ClickGuard.Unknown; freshTarget = false; requiredWindowAfter = SystemClock.uptimeMillis()
            val nextArm = ClickTargetArm(requiredWindowAfter)
            arm = nextArm
            check(main.postDelayed({
                if (arm === nextArm) invalidateArm()
            }, nextArm.lifetimeMs + 1))
            if (request.resume) {
                check(engine?.state?.phase == ClickRunPhase.Paused)
                check(recordingReady(requireNotNull(engine).state))
                resumeConfirmed = true
            } else {
                check(resultCode == Activity.RESULT_OK && grant != null && !RecordingCoordinator.state.value.isBusy && !RecordingCoordinator.state.value.canRetry)
                val run = ClickRun.create(request.plan, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    SystemClock.uptimeMillis(), System.currentTimeMillis())
                val store = ClickPlanStore(context)
                store.beginRun(run)
                runViewport = request.viewport
                engine = ClickRunEngine(run, persist = store::saveRun,
                    dispatch = { token, action, callback -> service?.dispatch(token, action, callback) ?: false },
                    schedule = { delay, action -> check(main.postDelayed({ action() }, delay)) },
                    uptimeMs = SystemClock::uptimeMillis,
                    guard = { guardFor(it) },
                    recordingReady = { recordingReady(it) },
                    onState = ::onRun)
                mutable.value = mutable.value.copy(run = run)
                RecordingCoordinator.start(context, run.projectId, requireNotNull(resultCode), requireNotNull(grant),
                    RecordingClickSession(run.recordingSessionId, run.sourceId, run.plan.width, run.plan.height))
                scope.launch {
                    RecordingCoordinator.awaitCommandBoundary()
                    if (engine?.state?.runId == run.runId && engine?.state?.isActive == true &&
                        RecordingCoordinator.state.value.sessionId != run.recordingSessionId) {
                        engine?.stop(ClickStopReason.RecordingLost)
                    }
                }
            }
            consentRequest = null
            mutable.value = mutable.value.copy(busy = true, message = "等待目标 App 全屏窗口与录屏就绪；尚未派发下一点。可从通知停止。")
            return true
        } catch (_: Exception) {
            consentRequest = null; resumeConfirmed = false
            engine?.stop(ClickStopReason.DurabilityFailure)
            mutable.value = mutable.value.copy(busy = false, message = "本轮未能开始或恢复，请检查权限、方向和存储后重新确认。")
            return false
        }
    }

    internal fun windowChanged(pkg: String?, cls: String?, fullscreen: Boolean, activityKnown: Boolean, displayId: Int, at: Long) {
        val plan = engine?.state?.takeIf { it.isActive }?.plan ?: consentRequest?.plan ?: editorPlan ?: return
        val observed = if (displayId != plan.displayId) ClickGuard.Blocked else ClickWindowPolicy.classify(plan.targetPackage, pkg, cls, fullscreen, activityKnown)
        if (engine?.state?.isActive == true) {
            val authorization = arm
            if (authorization != null) {
                when (authorization.observe(at, SystemClock.uptimeMillis(),
                    pkg == app?.packageName && cls == ClickConsentActivity::class.java.name,
                    observed == ClickGuard.Allowed)) {
                    ClickArmDecision.IgnoreOld -> return
                    ClickArmDecision.AwaitTarget -> { guard = ClickGuard.Unknown; freshTarget = false; return }
                    ClickArmDecision.Invalidated -> { guard = observed; invalidateArm(); return }
                    ClickArmDecision.TargetReady -> { guard = observed; freshTarget = true }
                }
            } else {
                guard = observed
                if (guard != ClickGuard.Allowed) {
                    freshTarget = false; resumeConfirmed = false
                    engine?.pause()
                } else if (at >= requiredWindowAfter) freshTarget = true
            }
            tryAdvance()
        } else guard = observed
    }
    private fun invalidateArm() {
        arm = null; resumeConfirmed = false; freshTarget = false
        engine?.pause()
        mutable.value = mutable.value.copy(message = "确认后出现其他或未知窗口，或未及时返回目标。后续点击已暂停，请重新核对当前页。")
    }
    private fun guardFor(plan: ClickPlan): ClickGuard {
        val context = app ?: return ClickGuard.Unknown
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return ClickGuard.Unknown
        if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel("tapscene_recording")?.importance == NotificationManager.IMPORTANCE_NONE) {
            main.post { engine?.takeIf { it.state.isActive }?.stop(ClickStopReason.PermissionLost) }
            return ClickGuard.Blocked
        }
        if (service == null || mutable.value.overlayVisible || !ClickDevice.matches(context, plan)) return ClickGuard.Blocked
        val viewport = service?.currentViewport() ?: return ClickGuard.Unknown
        if (viewport != runViewport || !viewport.contains(plan)) {
            val current = engine
            main.post { if (engine === current) current?.takeIf { it.state.isActive }?.stop(ClickStopReason.DisplayChanged) }
            return ClickGuard.Blocked
        }
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) return ClickGuard.Blocked
        return if (freshTarget) guard else ClickGuard.Unknown
    }
    internal fun dispatchAllowed(): Boolean {
        val run = engine?.state ?: return false
        return run.phase == ClickRunPhase.Running && guardFor(run.plan) == ClickGuard.Allowed &&
            recordingReady(run)
    }
    private fun recordingReady(run: ClickRun): Boolean = RecordingCoordinator.canDispatchClicks(
        run.recordingSessionId, run.projectId, run.sourceId)

    private fun tryAdvance() {
        val current = engine ?: return
        if (current.state.terminal || guardFor(current.state.plan) != ClickGuard.Allowed ||
            !recordingReady(current.state)) return
        if (arm?.expired(SystemClock.uptimeMillis()) == true) { invalidateArm(); return }
        if (current.state.phase == ClickRunPhase.Ready && arm != null) { arm = null; current.start() }
        else if (current.state.phase == ClickRunPhase.Paused && resumeConfirmed) {
            resumeConfirmed = false; arm = null; current.resume(true)
        }
    }
    private fun onRun(run: ClickRun) {
        mutable.value = mutable.value.copy(run = run, busy = !run.terminal || (RecordingCoordinator.state.value.sessionId == run.recordingSessionId && RecordingCoordinator.state.value.isBusy), message = when (run.phase) {
            ClickRunPhase.Ready -> "等待目标窗口与录屏就绪"
            ClickRunPhase.Running -> "依次播放中，系统回调只表示手势完成"
            ClickRunPhase.Paused -> "后续点击已暂停；已派发的短按可能完成。请核对当前页面后明确恢复。"
            ClickRunPhase.Completed -> "点击链已播放一次，正在保存真实录屏。可手动取帧校正。"
            else -> "本轮已中断或停止；未知点击不会自动重试，日志保存在本机。"
        })
        if (run.terminal) {
            guard = ClickGuard.Unknown; freshTarget = false; resumeConfirmed = false; consentRequest = null; arm = null
            val stopVideo = {
                if (engine?.state?.runId == run.runId) app?.let { RecordingCoordinator.stopClickSession(it, run.recordingSessionId) }
                Unit
            }
            // MediaRecorder may have no sealable sample after an extremely short chain. Keep a
            // minimum one-second collection window; this is NOT a frame/PTS synchronization claim.
            val recording = RecordingCoordinator.state.value
            val tail = if (run.phase == ClickRunPhase.Completed && recording.sessionId == run.recordingSessionId)
                (1_000L - recording.elapsedMs).coerceAtLeast(0) else 0
            if (tail == 0L) stopVideo() else if (!main.postDelayed(stopVideo, tail)) stopVideo()
        }
    }
    fun pause() { arm = null; resumeConfirmed = false; engine?.pause() }
    fun stop(context: Context) {
        consentRequest = null; resumeConfirmed = false; freshTarget = false; guard = ClickGuard.Unknown; arm = null
        engine?.stop(ClickStopReason.User)
        service?.hideEditor { }
        editorPlan = null
        RecordingCoordinator.stop(context)
        mutable.value = mutable.value.copy(busy = false, message = "已停止，本轮不会自动续播。")
    }
    internal fun closeEditor(context: Context) {
        service?.hideEditor { }
        editorPlan = null; guard = ClickGuard.Unknown
        runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    }
    private fun checkGeometry() {
        val context = app ?: return
        val run = engine?.state?.takeIf { it.isActive }
        val plan = run?.plan ?: editorPlan ?: consentRequest?.plan ?: return
        if (!ClickDevice.matches(context, plan)) {
            guard = ClickGuard.Blocked; consentRequest = null; editorPlan = null
            service?.hideEditor { }
            engine?.stop(ClickStopReason.DisplayChanged)
            RecordingCoordinator.stop(context)
            mutable.value = mutable.value.copy(busy = false, message = "屏幕尺寸或方向改变，本轮已结束；旧点位不会自动缩放。")
        }
    }
    private fun failEditor(message: String) {
        service?.hideEditor { }
        editorPlan = null; consentRequest = null
        mutable.value = mutable.value.copy(busy = false, message = message)
    }
}
