package com.tapscene.ui.shell

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapscene.data.SourceRepository
import com.tapscene.recording.RecordingCoordinator
import com.tapscene.recording.RecordingPhase
import com.tapscene.recording.RecordingStopReason
import com.tapscene.recording.RecordingUiState
import com.tapscene.ui.ProjectRoute
import com.tapscene.ui.ProjectUiState
import com.tapscene.ui.ProjectWorkspace
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Consent is requested only by a Start button. The grant is never saved in Compose or disk state. */
@Composable
fun RecordingCaptureRoute(
    projects: ProjectWorkspace,
    onBack: () -> Unit,
    onImportVideo: () -> Unit,
    onCandidates: (projectId: String, sourceId: String, analyze: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val projectState by projects.state.collectAsStateWithLifecycle()
    val recording by RecordingCoordinator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val preferences = remember(app) { app.getSharedPreferences("recording-ui", Context.MODE_PRIVATE) }
    var showNotice by rememberSaveable { mutableStateOf(false) }
    var waitingProject by rememberSaveable { mutableStateOf(false) }
    var pendingProject by rememberSaveable { mutableStateOf<String?>(null) }
    var checkingBudget by remember { mutableStateOf(false) }
    var budgetJob by remember { mutableStateOf<Job?>(null) }
    var localMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var organizeAfterStop by rememberSaveable { mutableStateOf(false) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingProject
        pendingProject = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null && id != null && projects.state.value.project?.project?.id == id) {
            localMessage = null
            RecordingCoordinator.start(app, id, result.resultCode, requireNotNull(result.data))
        } else localMessage = "已取消录制授权。"
    }
    val launchConsent: () -> Unit = {
        try {
            val manager = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            consent.launch(manager.createScreenCaptureIntent())
        } catch (_: Exception) {
            pendingProject = null
            localMessage = "此设备无法打开系统录制授权，请稍后重试。"
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification permission is helpful, not a prerequisite imposed on MediaProjection.
        if (pendingProject != null) launchConsent()
    }
    val requestConsent: (String) -> Unit = { id ->
        if (!recording.isBusy && !recording.canRetry && !checkingBudget && pendingProject == null) {
            checkingBudget = true
            budgetJob = scope.launch {
                try {
                    val budget = withContext(Dispatchers.IO) { SourceRepository(app).budget(id) }
                    if (projects.state.value.project?.project?.id != id) {
                        localMessage = "项目已切换，请重新开始录制授权。"
                    } else if (budget.sourceCount >= 3 || budget.remainingBytes <= 0 || budget.remainingDurationUs <= 0) {
                        localMessage = "此项目的素材已满，请管理素材或录到新项目。"
                    } else {
                        pendingProject = id
                        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else launchConsent()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { localMessage = "无法读取素材额度，请返回项目重试。" }
                finally { checkingBudget = false }
            }
        }
    }
    val start: () -> Unit = {
        localMessage = null
        val id = projectState.project?.project?.id
        if (recording.canRetry) localMessage = "请先继续检查或删除未完成录制，再开始新录制。"
        else if (id != null) requestConsent(id)
        else if (!projectState.busy) {
            waitingProject = true
            projects.createProject(LocalDateTime.now().format(DateTimeFormatter.ofPattern("'录制' MM-dd HH:mm")))
        }
    }
    LaunchedEffect(projectState.busy, projectState.project?.project?.id, waitingProject) {
        if (waitingProject && !projectState.busy) {
            waitingProject = false
            val id = projectState.project?.project?.id
            if (id != null) requestConsent(id) else localMessage = "项目未创建，请重试。"
        }
    }
    LaunchedEffect(recording.phase, recording.sourceId, organizeAfterStop) {
        if (organizeAfterStop && recording.phase == RecordingPhase.Completed) {
            organizeAfterStop = false
            val id = recording.projectId
            val source = recording.sourceId
            if (id != null && source != null) onCandidates(id, source, true)
        }
        if (recording.phase == RecordingPhase.Failed || recording.phase == RecordingPhase.Interrupted) organizeAfterStop = false
    }
    val leave: () -> Unit = {
        budgetJob?.cancel()
        pendingProject = null
        waitingProject = false
        organizeAfterStop = false
        onBack()
    }
    BackHandler(onBack = leave)
    RecordingCaptureContent(
        projectState = projectState,
        recording = recording,
        localMessage = localMessage,
        preparingConsent = checkingBudget || pendingProject != null || waitingProject,
        callbacks = RecordingCaptureCallbacks(
            onBack = leave,
            onNewProject = projects::back,
            onStart = { if (preferences.getBoolean("notice-v1", false)) start() else showNotice = true },
            onStopAndOrganize = { organizeAfterStop = true; RecordingCoordinator.stop(app) },
            onRetry = { RecordingCoordinator.retrySealed(app) },
            onDiscard = { RecordingCoordinator.discardSealed(app) },
            onCandidates = {
                val id = recording.projectId
                val source = recording.sourceId
                if (id != null && source != null) onCandidates(id, source, true)
            },
            onImportVideo = onImportVideo,
        ),
    )
    if (showNotice) AlertDialog(onDismissRequest = { showNotice = false }, title = { Text("开始前请留意") },
        text = { Text("在系统授权中选择目标 App。录屏仅存本机，不会上传；从通知或返回这里都能停止。\n\n画面可能包含敏感输入，整理步骤时仍需复核。通知授权用于在应用外快速停止，拒绝也可回到这里停止。") },
        confirmButton = { TextButton(onClick = { showNotice = false; preferences.edit().putBoolean("notice-v1", true).apply(); start() }) { Text("继续并授权") } },
        dismissButton = { TextButton(onClick = { showNotice = false }) { Text("取消") } })
}

data class RecordingCaptureCallbacks(
    val onBack: () -> Unit,
    val onNewProject: () -> Unit,
    val onStart: () -> Unit,
    val onStopAndOrganize: () -> Unit,
    val onRetry: () -> Unit,
    val onDiscard: () -> Unit,
    val onCandidates: () -> Unit,
    val onImportVideo: () -> Unit,
)

/** Real page content; Android consent, persistence and coordinator calls stay in the route. */
@Composable
fun RecordingCaptureContent(
    projectState: ProjectUiState,
    recording: RecordingUiState,
    localMessage: String?,
    preparingConsent: Boolean,
    callbacks: RecordingCaptureCallbacks,
) {
    var showDiscard by rememberSaveable(recording.sessionId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("录制操作", callbacks.onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(projectState.project?.project?.title ?: "新项目", style = MaterialTheme.typography.titleMedium)
                    Text("原片只保存在本机", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                }
                if (!recording.isBusy && projectState.route == ProjectRoute.STEPS) TextButton(onClick = callbacks.onNewProject, enabled = !projectState.busy && !preparingConsent && !recording.canRetry) { Text("录到新项目") }
            }
            ShellDivider()
            Text(when (recording.phase) {
                RecordingPhase.Starting -> "正在准备录制"
                RecordingPhase.Recording -> "录制中"
                RecordingPhase.Stopping -> "正在结束录制"
                RecordingPhase.Registering -> "正在检查录屏"
                RecordingPhase.Completed -> "录屏已保存在本机"
                RecordingPhase.Interrupted -> "上次录制已中断"
                RecordingPhase.Failed -> "录制未完成"
                RecordingPhase.Idle -> "录下想演示的一段操作"
            }, style = MaterialTheme.typography.headlineSmall)
            if (recording.isBusy) {
                if (recording.phase == RecordingPhase.Recording) {
                    Text(recordingElapsed(recording.elapsedMs), style = MaterialTheme.typography.headlineLarge)
                    Text("在目标 App 中正常操作。完成后从通知或回到这里停止。", style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            } else Text("录完按画面变化提出候选步骤，再由你选择和校正。", style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
            recording.stopReason?.takeIf { it != RecordingStopReason.User }?.let {
                Text(recordingStopLabel(it), style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            }
            (localMessage ?: recording.error)?.let { StatusNote(it) }
            when {
                recording.phase == RecordingPhase.Recording || recording.phase == RecordingPhase.Starting -> {
                    Button(onClick = callbacks.onStopAndOrganize, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("停止并整理") }
                }
                recording.isBusy -> Text("检查完成后可整理候选。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                else -> {
                    if (recording.phase == RecordingPhase.Completed && recording.projectId != null && recording.sourceId != null) {
                        Button(onClick = callbacks.onCandidates,
                            shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("整理候选步骤") }
                    }
                    if (recording.canRetry) {
                        Button(onClick = callbacks.onRetry,
                            shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("继续检查已录内容") }
                        TextButton(onClick = { showDiscard = true }, colors = ButtonDefaults.textButtonColors(contentColor = ShellColors.Muted), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("删除未完成录制")
                        }
                        Text("请先继续检查或删除未完成录制，再开始新录制。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    }
                    Button(onClick = callbacks.onStart,
                        enabled = !recording.canRetry && !projectState.busy && !projectState.loadFailed && !preparingConsent,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (preparingConsent) "准备授权…" else if (recording.phase == RecordingPhase.Idle) "开始录制" else "录制下一段")
                    }
                    OutlinedButton(onClick = callbacks.onImportVideo, enabled = !projectState.busy && !preparingConsent,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("导入已有录屏") }
                }
            }
            ShellDivider()
            Text("系统每次都会请求录制授权。可以选择单个 App 时，优先只录目标 App；本次不录声音，也不读取键盘或点击事件。",
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            Text("可见输入仍可能进入录屏，加入步骤前请检查敏感画面。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        }
    }
    if (showDiscard && recording.canRetry && !recording.isBusy) AlertDialog(
        onDismissRequest = { showDiscard = false },
        title = { Text("删除未完成录制？") },
        text = { Text("只删除此次未完成录制在 TapScene 内的本地副本，无法撤销。已经登记的素材和已加入的步骤不会被删除。") },
        confirmButton = { TextButton(onClick = { showDiscard = false; callbacks.onDiscard() }) { Text("删除此副本") } },
        dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("保留") } },
    )
}

private fun recordingElapsed(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}

private fun recordingStopLabel(reason: RecordingStopReason): String = when (reason) {
    RecordingStopReason.User -> "已停止"
    RecordingStopReason.SystemStopped -> "系统已结束此次录制。"
    RecordingStopReason.ScreenLocked -> "锁屏后已停止录制。"
    RecordingStopReason.DisplayChanged -> "此系统的画布尺寸发生变化，已结束本段。可重新授权继续录制。"
    RecordingStopReason.DurationLimit -> "已达到本段时长额度。"
    RecordingStopReason.SizeLimit -> "已达到本段文件额度。"
    RecordingStopReason.LowStorage -> "可用空间不足，已停止录制。"
    RecordingStopReason.RecorderError -> "系统编码器未能继续录制。"
    RecordingStopReason.ServiceDestroyed -> "录制服务已结束。"
    RecordingStopReason.ProcessInterrupted -> "应用进程中断，录制不会自动恢复。"
}
