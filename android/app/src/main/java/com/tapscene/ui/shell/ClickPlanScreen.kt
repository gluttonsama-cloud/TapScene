package com.tapscene.ui.shell

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapscene.clickplan.ClickAction
import com.tapscene.clickplan.ClickActionStatus
import com.tapscene.clickplan.ClickDevice
import com.tapscene.clickplan.ClickPlan
import com.tapscene.clickplan.ClickPlanStore
import com.tapscene.clickplan.ClickPlayback
import com.tapscene.clickplan.ClickRun
import com.tapscene.clickplan.ClickRunPhase
import com.tapscene.recording.RecordingCoordinator
import com.tapscene.clickplan.ClickTargetApp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Raw text is preserved while a value is incomplete; invalid input never reaches a ClickPlan. */
data class ClickPointInput(
    val actionId: String,
    val x: String,
    val y: String,
    val pressDurationMs: String = "80",
    val waitAfterMs: String = "500",
)

private data class ClickPlanDraft(
    val planId: String = UUID.randomUUID().toString(),
    val revision: Long = 0,
    val targetPackage: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val displayId: Int = 0,
    val points: List<ClickPointInput> = emptyList(),
)

private val ClickPlanDraftSaver = listSaver<ClickPlanDraft, Any>(
    save = { draft ->
        listOf(draft.planId, draft.revision, draft.targetPackage, draft.width, draft.height,
            draft.rotation, draft.displayId) + draft.points.flatMap {
            listOf(it.actionId, it.x, it.y, it.pressDurationMs, it.waitAfterMs)
        }
    },
    restore = { values ->
        ClickPlanDraft(values[0] as String, values[1] as Long, values[2] as String,
            values[3] as Int, values[4] as Int, values[5] as Int, values[6] as Int,
            values.drop(7).chunked(5).map {
                ClickPointInput(it[0] as String, it[1] as String, it[2] as String, it[3] as String, it[4] as String)
            })
    },
)

/** Store work stays off the UI thread; returning from the target never overwrites unsaved inputs. */
@Composable
fun ClickPlanRoute(projectId: String, onBack: () -> Unit, onOpenProject: (String) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val playback by ClickPlayback.state.collectAsStateWithLifecycle()
    val recording by RecordingCoordinator.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable(projectId, stateSaver = ClickPlanDraftSaver) { mutableStateOf(ClickPlanDraft()) }
    var initialized by rememberSaveable(projectId) { mutableStateOf(false) }
    var dirty by rememberSaveable(projectId) { mutableStateOf(false) }
    var loading by remember(projectId) { mutableStateOf(true) }
    var saving by remember(projectId) { mutableStateOf(false) }
    var loadFailed by remember(projectId) { mutableStateOf(false) }
    var saveFailed by remember(projectId) { mutableStateOf(false) }
    var conflict by remember(projectId) { mutableStateOf(false) }
    var message by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var recordedRuns by remember(projectId) { mutableStateOf<List<ClickRun>>(emptyList()) }
    var reviewRunId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var showRecordedRuns by rememberSaveable(projectId) { mutableStateOf(false) }
    var apps by remember(projectId) { mutableStateOf<List<ClickTargetApp>>(emptyList()) }
    var geometryMatches by remember(projectId) { mutableStateOf(true) }
    var refresh by remember(projectId) { mutableIntStateOf(0) }
    var loadGeneration by remember(projectId) { mutableIntStateOf(0) }
    var showApps by rememberSaveable(projectId) { mutableStateOf(false) }
    var showNotice by rememberSaveable(projectId) { mutableStateOf(false) }
    var showLeave by rememberSaveable(projectId) { mutableStateOf(false) }
    var showReload by rememberSaveable(projectId) { mutableStateOf(false) }
    var showResetGeometry by rememberSaveable(projectId) { mutableStateOf(false) }
    var pendingTarget by remember { mutableStateOf<ClickTargetApp?>(null) }
    var expandedActionId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    val canEdit = !loading && !loadFailed && !saving && !playback.busy && !playback.overlayVisible && !conflict
    val currentSaving by rememberUpdatedState(saving)

    DisposableEffect(lifecycle, projectId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !currentSaving) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(projectId, refresh) {
        if (saving) return@LaunchedEffect
        loading = true
        val generation = ++loadGeneration
        try {
            ClickPlayback.initialize(app)
            val geometry = ClickDevice.geometry(context)
            val result = withContext(Dispatchers.IO) { val store = ClickPlanStore(app); Triple(store.getPlan(projectId), ClickDevice.launcherApps(app), store.readRuns(projectId).filter { it.terminal }) }
            recordedRuns = result.third
            apps = result.second
            val saved = result.first
            if (!initialized || !dirty) {
                draft = saved?.toDraft() ?: if (!initialized) ClickPlanDraft(width = geometry.width,
                    height = geometry.height, rotation = geometry.rotation, displayId = geometry.displayId) else draft
                initialized = true
                conflict = false
            } else if ((saved?.revision ?: 0) != draft.revision || (saved != null && saved.planId != draft.planId)) {
                conflict = true
                message = "已保存计划已有更新。当前输入已保留，请重新载入后再编辑。"
            }
            geometryMatches = draft.width == geometry.width && draft.height == geometry.height &&
                draft.rotation == geometry.rotation && draft.displayId == geometry.displayId
            loadFailed = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            loadFailed = true
            message = "无法读取点击计划或可用 App。当前输入已保留，请重试读取。"
        } finally { if (generation == loadGeneration) loading = false }
    }

    val change: (ClickPlanDraft) -> Unit = { updated ->
        if (!loading && !saving && canEdit) { draft = updated; dirty = true; message = null }
    }
    val save: (Boolean, Boolean) -> Unit = { openEditor, leave ->
        val error = clickPlanInputError(draft.targetPackage, draft.width, draft.height, draft.displayId, draft.points)
        if (loading || saving || !canEdit || !geometryMatches || error != null) {
            message = error ?: if (!geometryMatches) "请恢复原方向及全屏，或按当前屏幕重新编排。" else "请先结束当前定位或执行会话，再保存。"
        } else {
            saving = true
            saveFailed = false
            message = null
            val input = draft
            scope.launch {
                try {
                    val plan = input.toPlan(projectId)
                    withContext(Dispatchers.IO) {
                        val store = ClickPlanStore(app)
                        store.savePlan(plan, input.revision.takeIf { it > 0 })
                        check(store.getPlan(projectId)?.digest == plan.digest)
                    }
                    draft = plan.toDraft()
                    dirty = false
                    conflict = false
                    message = if (openEditor) null else "已保存到本机 · 修订 ${plan.revision}"
                    if (openEditor) {
                        ClickPlayback.openEditor(context, plan)
                    } else if (leave) onBack()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    saveFailed = true
                    message = "保存或打开定位未完成。输入已保留，请重试；若计划已有更新，请重新载入。"
                } finally { saving = false }
            }
        }
    }
    val leave: () -> Unit = {
        if (!saving) {
            if (dirty) showLeave = true else onBack()
        }
    }
    if (reviewRunId != null) {
        ClickChainReviewRoute(requireNotNull(reviewRunId), onBack = { reviewRunId = null }, onOpenProject = onOpenProject)
        return
    }
    BackHandler(onBack = leave)
    val run = playback.run?.takeIf { it.projectId == projectId } ?: recordedRuns.firstOrNull()
    val reviewable = (listOfNotNull(run?.takeIf { it.terminal }) + recordedRuns).distinctBy { it.runId }
    val target = apps.firstOrNull { it.packageName == draft.targetPackage }
    val error = clickPlanInputError(draft.targetPackage, draft.width, draft.height, draft.displayId, draft.points)
    ClickPlanContent(
        state = ClickPlanUiState(
            targetLabel = target?.label,
            targetPackage = draft.targetPackage,
            width = draft.width,
            height = draft.height,
            rotation = draft.rotation,
            revision = draft.revision,
            points = draft.points,
            expandedActionId = expandedActionId,
            connected = playback.connected,
            loading = loading,
            saving = saving,
            editingEnabled = canEdit,
            dirty = dirty,
            geometryMatches = geometryMatches,
            canLocate = Build.VERSION.SDK_INT >= 33 && canEdit && playback.connected && geometryMatches && target != null && error == null,
            canSave = canEdit && geometryMatches && error == null,
            validationMessage = error,
            message = message ?: playback.message ?: if (Build.VERSION.SDK_INT < 33) "点击链需 Android 13 及以上核对显示器；旧系统可继续手动录屏和取帧。" else null,
            reloadAvailable = loadFailed || conflict || saveFailed,
            sessionActive = playback.busy || playback.overlayVisible,
            runLabel = run?.let { "${clickRunLabel(it.phase)} · 已完成 ${it.nextActionIndex} / ${it.plan.actions.size} 点" },
            pointStatuses = run?.takeIf { !dirty && it.plan.planId == draft.planId && it.plan.revision == draft.revision }
                ?.outcomes?.associate { it.actionId to clickActionLabel(it.status) }.orEmpty(),
            canReviewRun = reviewable.isNotEmpty() && !recording.isBusy && !playback.busy && !playback.overlayVisible && !saving,
            canPause = run?.phase == ClickRunPhase.Running,
            canResume = run?.phase == ClickRunPhase.Paused && playback.connected,
        ),
        callbacks = ClickPlanCallbacks(
            onBack = leave,
            onChooseTarget = { if (canEdit) showApps = true },
            onAddPoint = {
                if (canEdit && draft.points.size < 40 && draft.width > 0 && draft.height > 0) {
                    val point = ClickPointInput(UUID.randomUUID().toString(), (draft.width / 2).toString(), (draft.height / 2).toString())
                    change(draft.copy(points = draft.points + point))
                    expandedActionId = point.actionId
                }
            },
            onTogglePoint = { id -> expandedActionId = id.takeIf { expandedActionId != id } },
            onPointChange = { updated -> change(draft.copy(points = draft.points.map { if (it.actionId == updated.actionId) updated else it })) },
            onMovePoint = { id, delta ->
                val index = draft.points.indexOfFirst { it.actionId == id }
                val next = index + delta
                if (index >= 0 && next in draft.points.indices) change(draft.copy(points = draft.points.toMutableList().apply {
                    add(next, removeAt(index))
                }))
            },
            onRemovePoint = { id ->
                change(draft.copy(points = draft.points.filterNot { it.actionId == id }))
                if (expandedActionId == id) expandedActionId = null
            },
            onSave = { save(false, false) },
            onLocate = { showNotice = true },
            onOpenAccessibility = {
                try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: Exception) { message = "无法打开无障碍设置，请从系统设置中手动进入。" }
            },
            onReload = { if (dirty) showReload = true else { message = null; saveFailed = false; refresh++ } },
            onResetGeometry = { if (canEdit) showResetGeometry = true },
            onPause = ClickPlayback::pause,
            onResume = { ClickPlayback.confirmResume(context) },
            onStop = { ClickPlayback.stop(app) },
            onReviewRun = { if (reviewable.size == 1) reviewRunId = reviewable.single().runId else showRecordedRuns = true },
        ),
    )
    if (showRecordedRuns) AlertDialog(onDismissRequest = { showRecordedRuns = false }, title = { Text("选择已录制点击链") },
        text = { LazyColumn { itemsIndexed(reviewable) { index, item -> TextButton(onClick = {
            showRecordedRuns = false; reviewRunId = item.runId
        }) { Text("${if (index == 0) "最近一次" else "第 ${index + 1} 次记录"} · ${item.nextActionIndex}/${item.plan.actions.size} 个动作已完成") } } } },
        confirmButton = { TextButton(onClick = { showRecordedRuns = false }) { Text("返回") } })
    if (showApps) ClickTargetPicker(apps, onDismiss = { showApps = false }, onChoose = { selected ->
        showApps = false
        if (selected.packageName != draft.targetPackage && draft.points.isNotEmpty()) pendingTarget = selected
        else change(draft.copy(targetPackage = selected.packageName))
    })
    pendingTarget?.let { selected ->
        AlertDialog(onDismissRequest = { pendingTarget = null }, title = { Text("更换目标 App？") },
            text = { Text("将清空当前点位，再为「${selected.label}」重新定位。只有点击保存才会替换本机计划。") },
            confirmButton = { TextButton(onClick = {
                change(draft.copy(targetPackage = selected.packageName, points = emptyList())); pendingTarget = null
            }) { Text("更换并清空点位") } },
            dismissButton = { TextButton(onClick = { pendingTarget = null }) { Text("取消") } })
    }
    if (showNotice) AlertDialog(onDismissRequest = { showNotice = false }, title = { Text("定位前确认使用范围") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("仅编排不含敏感操作的演示。目标 App 内的支付、登录、授权页面无法可靠识别，请确认安全起始页，并避开这些流程。")
            Text("保持计划的固定方向及全屏。前往后手动切换页面、放置点位；开始执行仍须另行确认，并请求本轮系统录制授权。")
            Text("会话期间仅检查窗口包名与类名，不读取节点文字、键盘输入或通知内容。")
        } },
        confirmButton = { TextButton(onClick = { showNotice = false; save(true, false) }) { Text("确认安全并前往") } },
        dismissButton = { TextButton(onClick = { showNotice = false }) { Text("取消") } })
    if (showResetGeometry) AlertDialog(onDismissRequest = { showResetGeometry = false }, title = { Text("按当前屏幕重新编排？") },
        text = { Text("当前所有点位将清空，并使用当前屏幕的尺寸与方向。不会缩放旧坐标；保存后才替换本机计划。") },
        confirmButton = { TextButton(enabled = canEdit, onClick = {
            showResetGeometry = false
            try {
                val geometry = ClickDevice.geometry(context)
                change(draft.copy(width = geometry.width, height = geometry.height, rotation = geometry.rotation,
                    displayId = geometry.displayId, points = emptyList()))
                geometryMatches = true
            } catch (_: Exception) { message = "无法读取当前屏幕，计划尚未改变。" }
        }) { Text("清空点位并重新编排") } },
        dismissButton = { TextButton(onClick = { showResetGeometry = false }) { Text("取消") } })
    if (showLeave) AlertDialog(onDismissRequest = { showLeave = false }, title = { Text("保留当前修改？") },
        text = { Text("点击计划有尚未保存的修改。可保存后返回，或放弃本次修改。") },
        confirmButton = { TextButton(enabled = canEdit && geometryMatches && error == null, onClick = { showLeave = false; save(false, true) }) { Text("保存并返回") } },
        dismissButton = { TextButton(onClick = { showLeave = false; onBack() }) { Text("放弃修改并返回") } })
    if (showReload) AlertDialog(onDismissRequest = { showReload = false }, title = { Text("重新载入已保存计划？") },
        text = { Text("当前尚未保存的输入会被替换。") },
        confirmButton = { TextButton(onClick = { showReload = false; dirty = false; initialized = false; saveFailed = false; message = null; refresh++ }) { Text("重新载入") } },
        dismissButton = { TextButton(onClick = { showReload = false }) { Text("保留输入") } })
}

data class ClickPlanUiState(
    val targetLabel: String? = null,
    val targetPackage: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val revision: Long = 0,
    val points: List<ClickPointInput> = emptyList(),
    val expandedActionId: String? = null,
    val connected: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val editingEnabled: Boolean = true,
    val dirty: Boolean = false,
    val geometryMatches: Boolean = true,
    val canLocate: Boolean = false,
    val canSave: Boolean = false,
    val validationMessage: String? = null,
    val message: String? = null,
    val reloadAvailable: Boolean = false,
    val sessionActive: Boolean = false,
    val runLabel: String? = null,
    val pointStatuses: Map<String, String> = emptyMap(),
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val canReviewRun: Boolean = false,
)

data class ClickPlanCallbacks(
    val onBack: () -> Unit = {},
    val onChooseTarget: () -> Unit = {},
    val onAddPoint: () -> Unit = {},
    val onTogglePoint: (String) -> Unit = {},
    val onPointChange: (ClickPointInput) -> Unit = {},
    val onMovePoint: (String, Int) -> Unit = { _, _ -> },
    val onRemovePoint: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onLocate: () -> Unit = {},
    val onOpenAccessibility: () -> Unit = {},
    val onReload: () -> Unit = {},
    val onResetGeometry: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onReviewRun: () -> Unit = {},
)

/** Production content shared by the route and layout-only screenshot previews. */
@Composable
fun ClickPlanContent(state: ClickPlanUiState, callbacks: ClickPlanCallbacks) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ShellTopBar("编排点击链", callbacks.onBack)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("target") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(state.targetLabel ?: if (state.targetPackage.isBlank()) "先选择目标 App" else "目标 App 不可用",
                                style = MaterialTheme.typography.titleMedium)
                            Text("固定方向 · 主屏幕全屏", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                        }
                        TextButton(onClick = callbacks.onChooseTarget, enabled = state.editingEnabled,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.targetPackage.isBlank()) "选择 App" else "更换") }
                    }
                    TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (showDetails) "收起设备与使用说明" else "设备与使用说明")
                    }
                    if (showDetails) {
                        Text("${state.width} × ${state.height} px · 方向 ${state.rotation * 90}° · 修订 ${state.revision}",
                            style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                        if (state.targetPackage.isNotBlank()) Text(state.targetPackage,
                            style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                        Text("定位会避开系统提供的状态栏、导航栏和屏幕缺口区域；无法判断 App 内的所有敏感操作。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                        TextButton(onClick = callbacks.onAddPoint, enabled = state.editingEnabled && state.points.size < 40 && state.width > 0,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("手动添加坐标点") }
                    }
                }
            }
            if (state.loading || state.saving) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.connected) item("permission") { ClickAccessibilityNotice(callbacks.onOpenAccessibility) }
            else item("connected") {
                Text("无障碍服务已连接 · 仅在定位或执行会话中工作", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            }
            if (state.message != null || state.reloadAvailable) item("message") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.message?.let { StatusNote(it) }
                    if (state.reloadAvailable) TextButton(onClick = callbacks.onReload, enabled = !state.saving,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("重新载入已保存计划") }
                }
            }
            if (!state.geometryMatches) item("geometry_changed") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusNote("当前屏幕与计划尺寸或方向不一致。请恢复原方向及全屏后再定位。")
                    TextButton(onClick = callbacks.onResetGeometry, enabled = state.editingEnabled,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("按当前屏幕重新编排") }
                }
            }
            if (state.canReviewRun) item("review_recorded_chain") {
                Button(onClick = callbacks.onReviewRun, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("整理已录制点击链") }
            }
            if (state.runLabel != null || state.sessionActive) item("runtime") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.runLabel?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    Text("手势完成不代表业务成功。录屏结束后可核对画面和点击区域，生成步骤路线。",
                        style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.canPause) OutlinedButton(onClick = callbacks.onPause, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("暂停") }
                        if (state.canResume) OutlinedButton(onClick = callbacks.onResume, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("核对当前页后继续") }
                        if (state.sessionActive) OutlinedButton(onClick = callbacks.onStop, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("停止并关闭浮层") }
                    }
                    if (state.sessionActive) Text("请先停止并关闭浮层，再修改页面中的计划。",
                        style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                }
            }
            item("points_heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("按顺序执行 · ${state.points.size} / 40 点", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (state.points.isEmpty()) item("empty") {
                Text("还没有点位。前往目标 App 添加，浏览到下一页后继续定位；播放前手动回到起始页。",
                    style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
            }
            itemsIndexed(state.points, key = { _, point -> point.actionId }) { index, point ->
                ClickPointCard(point, index, state.points.size, state.width, state.height, state.editingEnabled,
                    state.expandedActionId == point.actionId, state.pointStatuses[point.actionId], callbacks)
            }
            item("limits") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val duration = state.points.map { point ->
                        val press = point.pressDurationMs.toLongOrNull()?.takeIf { it in 40L..500L }
                        val wait = point.waitAfterMs.toLongOrNull()?.takeIf { it in 0L..10_000L }
                        if (press != null && wait != null) press + wait else null
                    }.takeIf { values -> values.all { it != null } }?.filterNotNull()?.sum()
                    Text("按下 40–500 ms · 等待 0–10000 ms · 计划总时长 ≤120 秒",
                        style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    if (state.points.isNotEmpty() && duration != null) Text("当前计划 ${duration / 1000.0} 秒（含每点等待）",
                        style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    Text("仅编排不含敏感操作的演示。无法可靠识别同一 App 内的支付、登录或授权页，请自行避开，并在开始前确认安全起始页。",
                        style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                    if (state.connected) Text("仅在会话中检查窗口包名与类名；不读取节点文字、键盘输入或通知内容。可随时在系统设置中关闭服务。",
                        style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                }
            }
        }
        ShellDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.validationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = callbacks.onSave, enabled = state.canSave,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (state.saving) "保存中…" else "保存计划") }
                if (state.dirty) Text("尚未保存", modifier = Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            }
            Button(onClick = callbacks.onLocate, enabled = state.canLocate,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("前往目标 App 定位") }
        }
    }
}

@Composable
private fun ClickAccessibilityNotice(onSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = ShellColors.Quiet)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("手动开启点击服务", style = MaterialTheme.typography.titleMedium)
            Text("定位与执行需要无障碍服务。仅在会话期间检查窗口包名与类名，不读取节点文字、键盘输入或通知内容。",
                style = MaterialTheme.typography.bodySmall)
            Text("请在系统设置中自行开启 TapScene 点击服务；回来后会检查连接。未开启也可编辑和保存计划。",
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开系统无障碍设置") }
        }
    }
}

@Composable
private fun ClickPointCard(point: ClickPointInput, index: Int, count: Int, width: Int, height: Int,
    enabled: Boolean, expanded: Boolean, status: String?, callbacks: ClickPlanCallbacks) {
    var advanced by rememberSaveable(point.actionId) { mutableStateOf(false) }
    val xValid = point.x.toIntOrNull()?.let { it in 0 until width } == true
    val yValid = point.y.toIntOrNull()?.let { it in 0 until height } == true
    val pressValid = point.pressDurationMs.toLongOrNull()?.let { it in 40L..500L } == true
    val waitValid = point.waitAfterMs.toLongOrNull()?.let { it in 0L..10_000L } == true
    val waitText = point.waitAfterMs.toLongOrNull()?.takeIf { waitValid }?.let { "${it / 1000.0} 秒" } ?: "待填写"
    Card(colors = CardDefaults.cardColors(containerColor = ShellColors.Surface)) {
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { callbacks.onTogglePoint(point.actionId) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index + 1}", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f)) {
                    Text("点击 · 等待 $waitText", style = MaterialTheme.typography.titleSmall)
                    Text(if (xValid && yValid && pressValid && waitValid) "位置 ${point.x}, ${point.y}" else "参数待补全",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (xValid && yValid && pressValid && waitValid) ShellColors.Muted else MaterialTheme.colorScheme.error)
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted) }
                }
                Text(if (expanded) "收起" else "编辑", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                ClickNumberField(point.waitAfterMs, "点击后等待 / ms", enabled, waitValid, Modifier.fillMaxWidth()) {
                    callbacks.onPointChange(point.copy(waitAfterMs = it))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { callbacks.onMovePoint(point.actionId, -1) }, enabled = enabled && index > 0,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "上移点 ${index + 1}" }) { Text("上移") }
                    TextButton(onClick = { callbacks.onMovePoint(point.actionId, 1) }, enabled = enabled && index < count - 1,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "下移点 ${index + 1}" }) { Text("下移") }
                    TextButton(onClick = { callbacks.onRemovePoint(point.actionId) }, enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "移除点 ${index + 1}" }) { Text("移除") }
                }
                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (advanced) "收起高级参数" else "高级参数 · 坐标与按压时长")
                }
                if (advanced) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClickNumberField(point.x, "X / px", enabled, xValid, Modifier.weight(1f)) { callbacks.onPointChange(point.copy(x = it)) }
                        ClickNumberField(point.y, "Y / px", enabled, yValid, Modifier.weight(1f)) { callbacks.onPointChange(point.copy(y = it)) }
                    }
                    ClickNumberField(point.pressDurationMs, "按压时长 / ms", enabled, pressValid,
                        Modifier.fillMaxWidth().padding(bottom = 12.dp)) { callbacks.onPointChange(point.copy(pressDurationMs = it)) }
                }
            }
        }
    }
}

@Composable
private fun ClickNumberField(value: String, label: String, enabled: Boolean, valid: Boolean,
    modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        enabled = enabled, isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}

@Composable
private fun ClickTargetPicker(apps: List<ClickTargetApp>, onDismiss: () -> Unit, onChoose: (ClickTargetApp) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matching = apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择目标 App") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("搜索已安装 App") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                if (matching.isEmpty()) item { Text("没有可打开的匹配 App。", style = MaterialTheme.typography.bodyMedium) }
                items(matching, key = { it.packageName }) { target ->
                    ShellActionRow(target.label, target.packageName, onClick = { onChoose(target) })
                    ShellDivider()
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun ClickPlan.toDraft() = ClickPlanDraft(planId, revision, targetPackage, width, height, rotation, displayId,
    actions.map { ClickPointInput(it.actionId, it.x.toString(), it.y.toString(), it.pressDurationMs.toString(), it.waitAfterMs.toString()) })

private fun ClickPlanDraft.toPlan(projectId: String) = ClickPlan.create(projectId, targetPackage, width, height, rotation,
    points.map { ClickAction(it.actionId, it.x.toInt(), it.y.toInt(), it.pressDurationMs.toLong(), it.waitAfterMs.toLong()) },
    planId = planId, revision = revision + 1, displayId = displayId)

private fun clickPlanInputError(targetPackage: String, width: Int, height: Int, displayId: Int, points: List<ClickPointInput>): String? {
    if (targetPackage.isBlank()) return "请选择目标 App。"
    if (width <= 0 || height <= 0 || displayId != 0) return "仅支持主屏幕全屏定位，请返回主屏幕后重试。"
    if (points.size > 40) return "最多保存 40 个点。"
    var total = 0L
    points.forEachIndexed { index, point ->
        val x = point.x.toIntOrNull()
        val y = point.y.toIntOrNull()
        val press = point.pressDurationMs.toLongOrNull()
        val wait = point.waitAfterMs.toLongOrNull()
        if (x == null || y == null || x !in 0 until width || y !in 0 until height)
            return "点 ${index + 1} 的坐标须在 0–${width - 1}、0–${height - 1} 内。"
        if (press == null || press !in 40L..500L) return "点 ${index + 1} 的按下时长须为 40–500 ms。"
        if (wait == null || wait !in 0L..10_000L) return "点 ${index + 1} 的等待时长须为 0–10000 ms。"
        total += press + wait
    }
    return if (total > 120_000L) "计划总时长超过 120 秒，请减少点位或等待。" else null
}

private fun clickRunLabel(phase: ClickRunPhase): String = when (phase) {
    ClickRunPhase.Ready -> "等待开始"
    ClickRunPhase.Running -> "执行中"
    ClickRunPhase.Paused -> "已暂停"
    ClickRunPhase.Completed -> "执行已结束"
    ClickRunPhase.Stopped -> "已停止"
    ClickRunPhase.Interrupted -> "执行已中断，不会自动重放"
    ClickRunPhase.Failed -> "执行未完成"
}

private fun clickActionLabel(status: ClickActionStatus): String = when (status) {
    ClickActionStatus.Pending -> "等待执行"
    ClickActionStatus.Intent -> "已记录派发意图"
    ClickActionStatus.Accepted -> "系统已接收手势"
    ClickActionStatus.Completed -> "系统报告手势完成"
    ClickActionStatus.Rejected -> "系统拒绝手势"
    ClickActionStatus.Cancelled -> "手势已取消"
    ClickActionStatus.TimedOut -> "等待回调超时"
    ClickActionStatus.Unknown -> "结果未知，不会自动重试"
}
