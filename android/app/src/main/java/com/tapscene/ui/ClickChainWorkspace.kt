package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.clickplan.*
import com.tapscene.data.ClickChainConfirmedAction
import com.tapscene.data.ClickChainImportInput
import com.tapscene.data.ClickChainReviewedFrame
import com.tapscene.data.ProjectLimits
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReviewedStepInput
import com.tapscene.data.SourceRepository
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SafeMediaWriterValidation
import com.tapscene.recording.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The capture gate produces candidates; only explicit actual-output review creates review inputs. */
internal class ClickChainWorkspace(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val projects = ProjectStore(app)
    private val reviews = ClickChainReviewStore(app)
    private val evidence = FrameEvidenceStore(app)
    private val sourceAccessor = SourceRepository(app).frameEvidenceSourceAccessor()
    private val writer = SafeMediaWriter(app)
    private val mutable = MutableStateFlow(ClickChainReviewUiState())
    val state = mutable.asStateFlow()
    private var capture: ClickChainCapture? = null
    private var draft: ClickChainReviewDraft? = null
    private var persistedSerial = -1L
    @Volatile private var generation = 0L
    private var task: Job? = null
    private val writes = Mutex()
    private var stagingFailure = false
    private var selectedFrame: String? = null
    private var selectedAction: String? = null
    private var editorBitmap: Bitmap? = null
    private var outputBitmap: Bitmap? = null
    private var actionBitmap: Bitmap? = null
    private var targetBitmap: Bitmap? = null
    private var resultStatus: String? = null
    private var cancelRequested = false
    private var loadStopping = false

    fun activate(runId: String) {
        if (state.value.busy || state.value.loading || state.value.runId == runId && capture != null) return
        val request = ++generation
        capture = null; draft = null; persistedSerial = -1L; resultStatus = null; stagingFailure = false
        selectedFrame = null; selectedAction = null; editorBitmap = null; outputBitmap = null; actionBitmap = null; targetBitmap = null
        mutable.value = ClickChainReviewUiState(runId = runId, loading = true)
        task = viewModelScope.launch {
            try {
                val previous = withContext(Dispatchers.IO) { reviews.readExisting(runId) }
                val receipt = previous?.operationId?.let { withContext(Dispatchers.IO) { projects.readClickChainImport(it) } }
                if (receipt?.status == "committed") {
                    resultStatus = "committed"
                    if (request == generation) mutable.value = ClickChainReviewUiState(runId = runId,
                        title = previous.title, editingLocked = true, createdProjectId = receipt.projectId.takeIf { receipt.projectStillExists },
                        message = if (receipt.projectStillExists) "这段点击链已建为独立项目。" else "生成的项目已被删除；这次重试不会重新创建。")
                    return@launch
                }
                val loaded = withContext(Dispatchers.IO) { loadCapture(runId) }
                currentCoroutineContext().ensureActive()
                if (request != generation) return@launch
                val loadingJob = currentCoroutineContext()[Job]
                val saved = withContext(Dispatchers.IO) { reviews.open(runId, loaded.source.metadata.sha256, loaded.actions.size) {
                    loadingJob?.isActive == true && request == generation
                } }
                if (request != generation) return@launch
                capture = loaded; draft = saved; persistedSerial = saved.serial; stagingFailure = false
                resultStatus = receipt?.status
                selectedFrame = null; selectedAction = null; editorBitmap = null; outputBitmap = null
                mutable.value = ClickChainReviewUiState(runId = runId)
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (request == generation) mutable.update { it.copy(message = failure.message ?: "点击链暂时无法读取，输入仍保留。", issue = "请重新读取点击链。") }
            } finally { if (request == generation) mutable.update { it.copy(loading = false) } }
        }
    }

    fun retry() {
        val runId = state.value.runId ?: return
        if (state.value.busy || state.value.loading) return
        if (draft != null && (stagingFailure || state.value.outcomeUnknown)) {
            execute("正在核对已保存结果", flushFirst = false) {
                // A receipt read must not depend on a still-current author window owner.
                if (state.value.outcomeUnknown) {
                    reconcileReceipt()
                    if (resultStatus == "committed") { refresh();return@execute }
                }
                writes.withLock {
                    val local = checkNotNull(draft)
                    val disk = withContext(Dispatchers.IO) { reviews.readExisting(runId) }
                        ?: error("复核记录缺失，当前输入已保留。")
                    if (disk.owner != local.owner) {
                        stagingFailure = true
                        error("复核已在其他窗口更新。请重新读取已保存复核，或保留当前输入。")
                    }
                    val reconciled = local.copy(serial = maxOf(local.serial,disk.serial) + 1,
                        outputJobs = local.outputJobs + disk.outputJobs)
                    draft = withContext(Dispatchers.IO) { reviews.save(reconciled) }
                    persistedSerial = checkNotNull(draft).serial;stagingFailure = false
                }
                if (draft?.operationId != null) reconcileReceipt()
                refresh()
            }
            return
        }
        capture = null
        activate(runId)
    }

    private suspend fun reconcileReceipt() {
        val operation = checkNotNull(draft?.operationId) { "生成操作身份缺失，请保留本机数据。" }
        val receipt = withContext(Dispatchers.IO) { projects.readClickChainImport(operation) }
        resultStatus = receipt?.status ?: "unstarted"
        mutable.update { it.copy(outcomeUnknown = false,
            createdProjectId = receipt?.projectId?.takeIf { receipt.status == "committed" && receipt.projectStillExists },
            message = if (receipt?.status == "committed") {
                if (receipt.projectStillExists) "已确认上次生成结果。" else "生成的项目已被删除；这次重试不会重新创建。"
            } else "已核对保存结果，可以继续。") }
    }

    /** Explicit discard-and-reload escape; stale owners can never overwrite the newer draft. */
    fun reloadSaved() {
        val runId = state.value.runId ?: return
        if (!stagingFailure || state.value.busy || state.value.loading || state.value.outcomeUnknown) return
        mutable.update { it.copy(busy = true,canCreate = false,message = "正在读取已保存复核") }
        val reload = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                writes.withLock {
                    draft = null; capture = null; persistedSerial = -1L; stagingFailure = false
                }
            } finally { mutable.update { it.copy(busy = false) } }
            currentCoroutineContext().ensureActive()
            activate(runId)
        }
        task = reload
        reload.start()
    }

    private fun loadCapture(runId: String): ClickChainCapture = WorkspaceStore.withProjectCopyLock {
        val run = ClickPlanStore(app).readRuns().singleOrNull { it.runId == runId } ?: error("这次点击链记录已不存在。")
        check(run.terminal) { "请先结束点击链和录屏，再整理画面。" }
        val source = WorkspaceStore(app, run.projectId).read().singleOrNull { it.source.sourceId == run.sourceId }?.source
            ?: error("录屏尚未登记或已移除，请返回录制页检查。")
        val observed = sourceAccessor.registeredSource(run.projectId,run.recordingSessionId,run.sourceId)
            ?: error("原片缺失、已改变或尚未完成登记，请返回录制页检查。")
        val bounded = FrameSourceAccessor { project,session,id -> observed.takeIf {
            project == run.projectId && session == run.recordingSessionId && id == run.sourceId
        } }
        val boundaries = evidence.listBoundaries(run.recordingSessionId)
        val actions = run.plan.actions.mapIndexed { index, action ->
            fun candidate(boundary: FrameBoundary): Pair<FrameEvidenceCandidate?,FrameMissingReason?> {
                val records = boundaries.filter { it.action.runId == run.runId && it.action.actionId == action.actionId &&
                    it.action.generation == run.generation && it.action.sourceId == run.sourceId &&
                    it.action.sessionId == run.recordingSessionId && it.boundary == boundary }
                if (records.size != 1) return null to FrameMissingReason.EvidenceUnavailable
                val record = records.single()
                record.missingReason?.let { return null to it }
                val ticket = record.ticketId ?: return null to FrameMissingReason.EvidenceUnavailable
                return when (val result = evidence.readCandidate(run.recordingSessionId,ticket,bounded)) {
                    is FrameCandidateResult.Available -> result.candidate.takeIf {
                        it.ticket.action == record.action && it.ticket.boundary == boundary && it.ticket.epoch == record.epoch
                    }?.let { it to null } ?: (null to FrameMissingReason.EvidenceUnavailable)
                    is FrameCandidateResult.Missing -> null to result.reason
                }
            }
            val before = candidate(FrameBoundary.Before); val after = candidate(FrameBoundary.After)
            ClickChainActionEvidence(index,action,run.outcomes[index].status,before.first,after.first,before.second,after.second)
        }
        ClickChainCapture(run,source,actions)
    }

    private fun route(): ClickChainRoute {
        val current = capture ?: return ClickChainRoute(emptyList(),emptyList(),"请重新读取。")
        val saved = draft ?: return ClickChainRoute(emptyList(),emptyList(),"请重新读取。")
        return ClickChainRoutePolicy.route(current,saved.firstAction.toIntOrNull() ?: 0,saved.lastAction.toIntOrNull() ?: 0,saved.stageChoices)
    }
    private fun titleForFrame(key: String): String = draft?.frames?.get(key)?.title ?: "画面 ${(capture?.frames?.keys?.indexOf(key) ?: 0) + 1}"
    private fun frameDraft(key: String): ClickChainFrameDraft {
        val frame = capture?.frames?.get(key) ?: error("画面已改变，请重新读取。")
        return draft?.frames?.get(key)?.also { check(it.originalPngSha256 == frame.pngSha256) { "采集画面已改变，请重新复核。" } }
            ?: ClickChainFrameDraft(key,frame.pngSha256,titleForFrame(key))
    }
    private fun frameReviewed(key: String): Boolean = draft?.frames?.get(key)?.let { it.reviewed && it.output != null } == true
    private fun selectedKeys(path: ClickChainRoute = route()): List<String> {
        val saved = draft ?: return emptyList()
        if (!saved.framesOnly) return path.frameKeys
        val keys = path.actions.flatMap { listOfNotNull(it.before?.key,it.after?.key) }.distinct()
        return keys.filter { saved.selectedFrameKeys?.contains(it) != false }
    }
    private fun matchingAction(action: ClickChainActionEvidence, from: String?, to: String?): ClickChainActionDraft? {
        if (from == null || to == null) return null
        val saved = draft ?: return null
        return saved.actions[action.action.actionId]?.takeIf { it.fromKey == from && it.toKey == to &&
            it.fromOutputSha == saved.frames[from]?.output?.sha256 && it.toOutputSha == saved.frames[to]?.output?.sha256 }
    }

    private fun refresh() {
        val saved = draft ?: return
        val current = capture ?: return
        val path = route(); val keys = selectedKeys(path)
        val actions = path.actions.mapIndexed { index, action ->
            val from = path.stages.getOrNull(index)?.selectedKey; val to = path.stages.getOrNull(index + 1)?.selectedKey
            val confirmed = matchingAction(action,from,to)?.confirmed == true
            ClickChainActionRow(action.action.actionId,action.index + 1,actionStatus(action.status),
                from?.let(::titleForFrame) ?: "前图待补",to?.let(::titleForFrame) ?: "后图待补",confirmed,
                path.issue == null && from != null && to != null && frameReviewed(from) && frameReviewed(to),
                if (!action.complete) "前后画面或完成结果待补" else null)
        }
        val allFrames = path.actions.flatMap { listOfNotNull(it.before?.key,it.after?.key) }.distinct()
        val issue = when {
            stagingFailure -> "输入尚未保存，请重试读取前先重试保存。"
            state.value.outcomeUnknown -> "生成结果尚未确认，请重试读取结果。"
            keys.size > ProjectLimits.MAX_STEPS -> "所选画面超过 40 个步骤，请缩小范围或减少选择。"
            keys.isEmpty() -> "请选择至少一张实际画面。"
            !saved.framesOnly && path.issue != null -> path.issue
            !saved.framesOnly && path.stages.dropLast(1).mapNotNull { it.selectedKey }.groupingBy { it }.eachCount().any { it.value > 6 } -> "同一画面最多 6 个热点，请缩小范围。"
            saved.markLastTerminal && !saved.framesOnly && path.stages.lastOrNull()?.selectedKey in path.stages.dropLast(1).map { it.selectedKey } -> "末页还有动作，不能同时设为终点。请取消终点选项。"
            else -> null
        }
        val reviewedCount = keys.count(::frameReviewed)
        val confirmedCount = if (saved.framesOnly) 0 else actions.count { it.confirmed }
        mutable.update { previous -> previous.copy(title = saved.title,runLabel = "${current.run.plan.actions.size} 个编排动作 · 录屏保存在本机",
            firstAction = saved.firstAction,lastAction = saved.lastAction,totalActions = current.actions.size,framesOnly = saved.framesOnly,
            stages = path.stages.map { stage -> ClickChainStageRow(stage.index,stage.selectedKey?.let(::titleForFrame) ?: "画面待补",
                stage.choices,stage.selectedKey,stage.selectedKey?.let(::frameReviewed) == true) },actions = actions,
            frames = allFrames.map { ClickChainFrameRow(it,titleForFrame(it),frameReviewed(it),saved.selectedFrameKeys?.contains(it) != false) },
            frameCount = keys.size,reviewedFrameCount = reviewedCount,confirmedActionCount = confirmedCount,
            markLastTerminal = saved.markLastTerminal,issue = issue,
            editingLocked = resultStatus == "committed" || saved.operationId != null && resultStatus !in setOf("aborted","unstarted"),
            retryCreate = saved.operationId != null && resultStatus !in setOf("committed","aborted"),
            canCreate = issue == null && saved.title.isNotBlank() && reviewedCount == keys.size &&
                (saved.framesOnly || confirmedCount == actions.size) && !previous.busy && !previous.loading && resultStatus != "committed",
            frameEditor = selectedFrame?.let { key -> frameDraft(key).let { frame -> ClickChainFrameEditor(key,frame.title,editorBitmap,frame.masks,
                outputBitmap,frame.output?.sha256,frame.reviewed) } },
            actionEditor = selectedAction?.let { id ->
                val action = path.actions.firstOrNull { it.action.actionId == id }; val edit = saved.actions[id]
                val frame = edit?.fromKey?.let(current.frames::get)
                if (action == null || edit == null || frame == null) null else ClickChainActionEditor(id,edit.label,edit.rect,actionBitmap,targetBitmap,
                    titleForFrame(edit.toKey),ClickChainRoutePolicy.point(action.action,frame),edit.fromKey == edit.toKey,
                    actions.count { saved.actions[it.actionId]?.fromKey == edit.fromKey })
            }) }
    }

    private fun edit(change: (ClickChainReviewDraft) -> ClickChainReviewDraft) {
        if (state.value.busy || state.value.loading || state.value.outcomeUnknown || resultStatus == "committed") return
        val current = draft ?: return
        if (current.operationId != null && resultStatus !in setOf("aborted", "unstarted")) {
            mutable.update { it.copy(message = "请先重试确认上次生成结果，再修改这段点击链。") }; return
        }
        draft = change(current).copy(serial = current.serial + 1,operationId = null)
        refresh()
        viewModelScope.launch { try { flush() } catch (_: Exception) { stagingFailure = true;refresh() } }
    }
    private suspend fun flush() = writes.withLock {
        val current = draft ?: return@withLock
        if (current.serial <= persistedSerial) return@withLock
        val saved = withContext(Dispatchers.IO) { reviews.save(current) }
        persistedSerial = saved.serial
        if (draft?.serial == saved.serial && draft?.owner == saved.owner) draft = saved
        stagingFailure = false
    }

    /** Keep the intended author state if rename/fsync or return delivery is uncertain. */
    private suspend fun persist(next: ClickChainReviewDraft) {
        draft = next
        try {
            withContext(NonCancellable) {
                val saved = withContext(Dispatchers.IO) { reviews.save(next) }
                if (draft?.owner == next.owner && draft?.serial == next.serial) draft = saved
                persistedSerial = saved.serial
            }
        } catch (failure: Throwable) { stagingFailure = true; throw failure }
    }

    fun title(value: String) = edit { it.copy(title = value.take(120)) }
    fun range(first: String,last: String) = edit { it.copy(firstAction = first.take(8),lastAction = last.take(8)) }
    fun framesOnly(value: Boolean) = edit { it.copy(framesOnly = value) }
    fun stageChoice(index: Int,key: String) {
        if (route().stages.none { it.index == index && it.choices.any { choice -> choice.frameKey == key } }) return
        edit { it.copy(stageChoices = it.stageChoices + (index to key)) }
    }
    fun toggleFrame(key: String,value: Boolean) = edit { saved ->
        val selected = saved.selectedFrameKeys ?: route().actions.flatMap { listOfNotNull(it.before?.key,it.after?.key) }.toSet()
        saved.copy(selectedFrameKeys = if (value) selected + key else selected - key)
    }
    fun markTerminal(value: Boolean) = edit { it.copy(markLastTerminal = value) }
    fun frameTitle(value: String) { val key = selectedFrame ?: return;edit { it.copy(frames = it.frames + (key to frameDraft(key).copy(title = value.take(120)))) } }
    fun addMask(mask: OpaqueMask) { val key = selectedFrame ?: return; val frame = frameDraft(key);if (frame.masks.size >= 20) return
        outputBitmap = null;edit { it.copy(frames = it.frames + (key to frame.copy(masks = frame.masks + mask,output = null,reviewed = false))) } }
    fun undoMask() { val key = selectedFrame ?: return;val frame = frameDraft(key);if (frame.masks.isEmpty()) return
        outputBitmap = null;edit { it.copy(frames = it.frames + (key to frame.copy(masks = frame.masks.dropLast(1),output = null,reviewed = false))) } }

    fun reviewFrame(key: String) {
        if (capture?.frames?.containsKey(key) != true) return
        execute("正在准备画面") {
            selectedAction = null; selectedFrame = key;editorBitmap = null;outputBitmap = null;refresh()
            val frame = frameDraft(key)
            val base = generate(key,emptyList(),save = false)
            editorBitmap = base
            if (frame.output != null) outputBitmap = readOutput(frame.output)
            refresh()
        }
    }
    fun generateFrame() { val key = selectedFrame ?: return
        execute("正在生成实际图片") { outputBitmap = null;refresh();outputBitmap = generate(key,frameDraft(key).masks,save = true);refresh() }
    }
    private suspend fun generate(key: String,masks: List<OpaqueMask>,save: Boolean): Bitmap {
        val original = capture?.frames?.get(key) ?: error("画面已不存在。")
        val initial = checkNotNull(draft)
        var directory: File? = null
        try {
            val started = withContext(NonCancellable) {
                val result = withContext(Dispatchers.IO) { reviews.beginOutput(initial) }
                draft = result.first;persistedSerial = result.first.serial;directory = result.second
                result
            }
            val candidate = withContext(Dispatchers.IO) {
                evidence.withDecodedFrameSuspending(original,sourceAccessor) { pixels -> writer.writePng(pixels,masks,started.second) }
            }
            val pixels = withContext(Dispatchers.IO) {
                check(sha(candidate.file) == candidate.sha256)
                BitmapFactory.decodeFile(candidate.file.path) ?: error("实际图片无法读取。")
            }
            currentCoroutineContext().ensureActive()
            if (save) {
                val saved = checkNotNull(draft)
                val output = withContext(Dispatchers.IO) { reviews.output(saved,candidate.file,candidate.sha256,candidate.width,candidate.height) }
                val next = saved.copy(serial = saved.serial + 1,frames = saved.frames + (key to frameDraft(key).copy(masks = masks.toList(),output = output,reviewed = false)))
                persist(next)
            }
            return pixels
        } finally {
            directory?.let { outputDirectory -> withContext(NonCancellable + Dispatchers.IO) {
                val current = draft
                if (current != null) {
                    val finished = runCatching { reviews.finishOutput(current,outputDirectory) }
                    withContext(Dispatchers.Main.immediate) {
                        if (finished.isSuccess && draft?.owner == current.owner) {
                            draft = finished.getOrThrow();persistedSerial = checkNotNull(draft).serial
                        } else if (finished.isFailure) { stagingFailure = true }
                    }
                }
            } }
        }
    }
    fun confirmFrame(digest: String) {
        val key = selectedFrame ?: return; val frame = frameDraft(key)
        if (outputBitmap?.isRecycled != false || frame.output?.sha256 != digest || state.value.busy) return
        execute("保存画面复核") {
            val output = checkNotNull(frame.output)
            withContext(Dispatchers.IO) { validateOutput(output,frame.masks) }
            val current = checkNotNull(draft)
            persist(current.copy(serial = current.serial + 1,frames = current.frames + (key to frame.copy(reviewed = true))))
            selectedFrame = null;editorBitmap = null;outputBitmap = null;refresh()
        }
    }

    fun reviewAction(actionId: String) {
        val path = route();val index = path.actions.indexOfFirst { it.action.actionId == actionId }
        if (index < 0 || path.issue != null) return
        val from = path.stages[index].selectedKey ?: return;val to = path.stages[index + 1].selectedKey ?: return
        if (!frameReviewed(from) || !frameReviewed(to)) return
        execute("读取已复核画面") {
            val before = checkNotNull(frameDraft(from).output);val after = checkNotNull(frameDraft(to).output)
            val action = path.actions[index]
            val input = matchingAction(action,from,to) ?: ClickChainActionDraft(actionId,from,to,before.sha256,after.sha256,
                draft?.actions?.get(actionId)?.label ?: "点击 ${action.index + 1}",ClickChainRoutePolicy.initialRect(action.action,checkNotNull(capture).frames.getValue(from)))
            val current = checkNotNull(draft)
            persist(current.copy(serial = current.serial + 1,actions = current.actions + (actionId to input)))
            actionBitmap = readOutput(before)
            val target = readOutput(after)
            targetBitmap = withContext(Dispatchers.Default) {
                val factor = minOf(1f,160f / maxOf(target.width,target.height))
                Bitmap.createScaledBitmap(target,(target.width * factor).roundToInt().coerceAtLeast(1),(target.height * factor).roundToInt().coerceAtLeast(1),true)
            }
            if (targetBitmap !== target) target.recycle()
            selectedFrame = null;selectedAction = actionId;refresh()
        }
    }
    fun actionLabel(value: String) { val id = selectedAction ?: return;edit { saved -> saved.actions[id]?.let { saved.copy(actions = saved.actions + (id to it.copy(label = value.take(120),confirmed = false))) } ?: saved } }
    fun actionRect(rect: OpaqueMask) { val id = selectedAction ?: return;edit { saved -> saved.actions[id]?.let { saved.copy(actions = saved.actions + (id to it.copy(rect = rect,confirmed = false))) } ?: saved } }
    fun confirmAction() {
        val id = selectedAction ?: return;val value = draft?.actions?.get(id) ?: return
        if (value.label.isBlank() || actionBitmap?.isRecycled != false) return
        edit { it.copy(actions = it.actions + (id to value.copy(confirmed = true))) }
        selectedAction = null;actionBitmap = null;targetBitmap = null;refresh()
    }
    fun closeEditor() = execute(null) { selectedFrame = null;selectedAction = null;editorBitmap = null;outputBitmap = null;actionBitmap = null;targetBitmap = null;refresh() }
    fun leave(action: () -> Unit) = execute(null) { action() }

    fun createProject() {
        if (!state.value.canCreate && draft?.operationId == null) return
        execute("正在建立独立项目") {
            var current = checkNotNull(draft)
            if (current.operationId == null || resultStatus == "aborted") {
                current = current.copy(serial = current.serial + 1,operationId = UUID.randomUUID().toString())
                persist(current)
            }
            val operationId = checkNotNull(draft?.operationId)
            try {
                val input = importInput(operationId)
                val result = withContext(Dispatchers.IO) { projects.importReviewedClickChain(input) }
                resultStatus = result.status
            } finally {
                withContext(NonCancellable) {
                    val result = runCatching { withContext(Dispatchers.IO) {
                        if (cancelRequested) projects.cancelClickChainImport(operationId) else projects.readClickChainImport(operationId)
                    } }
                    if (result.isSuccess) {
                        val receipt = result.getOrNull();resultStatus = receipt?.status ?: "unstarted"
                        mutable.update { it.copy(outcomeUnknown = false,createdProjectId = receipt?.projectId?.takeIf { receipt.projectStillExists && receipt.status == "committed" },
                            message = when (receipt?.status) {
                                "committed" -> if (receipt.projectStillExists) "已创建独立项目，原录屏项目保持原样。" else "生成的项目已被删除；重试不会重新创建。"
                                "aborted" -> "生成已停止，复核输入仍保留；修改后可重新生成。"
                                else -> "这次生成尚未完成，可用同一次操作重试。"
                            }) }
                    } else mutable.update { it.copy(outcomeUnknown = true,message = "暂时无法确认生成结果，请保留本机数据并重试读取。") }
                }
            }
        }
    }
    private fun importInput(operationId: String): ClickChainImportInput {
        val saved = checkNotNull(draft);val current = checkNotNull(capture);val path = route();val keys = selectedKeys(path)
        check(saved.framesOnly || path.issue == null) { path.issue.orEmpty() }
        val frames = keys.map { key ->
            val frame = frameDraft(key);check(frame.reviewed);val output = checkNotNull(frame.output)
            val proof = current.frames.getValue(key)
            ClickChainReviewedFrame(proof,ReviewedStepInput(reviews.outputFile(saved.runId,output),output.sha256,output.width,output.height,
                current.source,proof.containerPtsUs,1L,frame.masks,"click-frame:${saved.runId}:${proof.ticket.sourceFrameId}:${output.sha256}"),frame.title)
        }
        val actions = if (saved.framesOnly) emptyList() else path.actions.mapIndexed { index, action ->
            val from = path.stages[index].selectedKey;val to = path.stages[index + 1].selectedKey
            val confirmed = checkNotNull(matchingAction(action,from,to));check(confirmed.confirmed)
            ClickChainConfirmedAction(action.action.actionId,confirmed.fromKey,confirmed.toKey,confirmed.label,confirmed.rect,
                checkNotNull(action.before),checkNotNull(action.after))
        }
        return ClickChainImportInput(operationId,current.run,current.source,saved.title,frames,actions,keys.first(),
            if (saved.markLastTerminal) (if (saved.framesOnly) keys.last() else path.stages.last().selectedKey) else null)
    }

    private suspend fun readOutput(output: ClickChainOutput): Bitmap = withContext(Dispatchers.IO) {
        val file = validateOutput(output,emptyList())
        BitmapFactory.decodeFile(file.path) ?: error("已生成画面无法读取，请重新生成。")
    }
    private suspend fun validateOutput(output: ClickChainOutput,masks: List<OpaqueMask>): File {
        val file = reviews.outputFile(checkNotNull(draft).runId,output)
        check(sha(file) == output.sha256) { "已生成画面改变，请重新生成并复核。" }
        SafeMediaWriterValidation.verifyPng(file,output.width,output.height,masks)
        return file
    }
    private fun execute(message: String?, flushFirst: Boolean = true, block: suspend () -> Unit) {
        if (state.value.busy || state.value.loading) return
        val request = generation
        cancelRequested = false
        mutable.update { it.copy(busy = true,message = message,canCreate = false) }
        task = viewModelScope.launch {
            try { if (flushFirst) flush();block() }
            catch (cancelled: CancellationException) { if (request == generation && !state.value.outcomeUnknown && resultStatus != "committed") mutable.update { it.copy(message = "已停止处理，已保存的复核仍保留。") };throw cancelled }
            catch (failure: Exception) { if (request == generation && !state.value.outcomeUnknown && resultStatus != "committed") mutable.update { it.copy(message = failure.message ?: "处理未完成，请重试。") } }
            finally { if (request == generation) { mutable.update { it.copy(busy = false) };refresh() } }
        }
    }
    fun cancel() {
        if (state.value.loading) {
            if (loadStopping) return
            loadStopping = true
            val request = ++generation
            val loading = task
            loading?.cancel()
            mutable.update { it.copy(message = "正在停止读取") }
            viewModelScope.launch {
                loading?.join()
                if (request == generation) {
                    capture = null;draft = null;loadStopping = false
                    mutable.update { it.copy(loading = false,message = "已停止读取，已保存的复核仍保留。",issue = "可重新读取点击链。") }
                }
            }
            return
        }
        if (state.value.busy) { cancelRequested = true;task?.cancel();return }
        val operation = draft?.operationId ?: return
        if (state.value.outcomeUnknown || resultStatus == "committed") return
        execute("正在取消这次生成", flushFirst = false) {
            val receipt = withContext(Dispatchers.IO) { projects.cancelClickChainImport(operation) }
            resultStatus = receipt?.status ?: "unstarted"
            mutable.update { it.copy(createdProjectId = receipt?.projectId?.takeIf { receipt.status == "committed" && receipt.projectStillExists },
                message = if (receipt?.status == "committed") "项目已生成，已读取实际结果。" else "已取消这次生成，复核输入仍保留，可以继续编辑。") }
            refresh()
        }
    }
    private fun sha(file: File): String { val md = MessageDigest.getInstance("SHA-256");file.inputStream().use { input -> val buffer = ByteArray(64 * 1024);while (true) { val n = input.read(buffer);if (n < 0) break;check(n > 0);md.update(buffer,0,n) } };return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) } }
    private fun actionStatus(status: ClickActionStatus): String = when (status) {
        ClickActionStatus.Completed -> "手势已完成"
        ClickActionStatus.Pending -> "未执行"
        ClickActionStatus.Rejected -> "未派发"
        ClickActionStatus.Cancelled -> "已取消"
        ClickActionStatus.TimedOut -> "结果超时"
        else -> "结果待确认"
    }
}
