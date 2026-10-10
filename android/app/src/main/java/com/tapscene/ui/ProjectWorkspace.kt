package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.data.EditorDraftFields
import com.tapscene.data.EditorPendingForm
import com.tapscene.data.StoredEditorDraft
import com.tapscene.data.editorFields
import com.tapscene.data.withCurrentMedia
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.ProjectLimits
import com.tapscene.data.ProjectTransition
import com.tapscene.data.ProjectNextAction
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep
import com.tapscene.data.ProjectStore
import com.tapscene.data.StepAsset
import com.tapscene.data.ProjectSummary
import com.tapscene.data.ReviewedStepInput
import com.tapscene.data.RetainedMediaWorkspace
import com.tapscene.data.WorkspaceStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class ProjectRoute { PROJECTS, STEPS, EDIT, MEDIA, PREVIEW }

enum class DraftRecoveryStatus { NONE, STAGING, STAGED, FAILED }

data class StepEditDraft(
    val stepId: String,
    val title: String,
    val description: String,
    val isTerminal: Boolean,
    val hotspots: List<ProjectHotspot>,
    val dirty: Boolean = false,
    val nextAction: ProjectNextAction? = null,
    val pendingForm: EditorPendingForm? = null,
    val recoveryStatus: DraftRecoveryStatus = DraftRecoveryStatus.NONE,
    val conflicts: Set<String> = emptySet(),
)

data class ProjectIssue(val stepId: String?, val message: String)

/** A durable receipt is acknowledged only after the production screen has shown this result. */
data class ProjectCopyNotice(val operationId: String, val message: String)

data class PreviewTransition(
    val actionId: String,
    val edgeId: String,
    val targetStepId: String?,
    val endLabel: String?,
    val transition: ProjectTransition,
    val video: LocalVideoRun,
)

/** [history] contains actual visits, including repeated visits through explicit cycles. */
data class PreviewState(
    val projectId: String,
    val revision: Long,
    val currentStepId: String,
    val history: List<String>,
    val ended: Boolean = false,
    val endLabel: String? = null,
    val matchingHotspotIds: List<String> = emptyList(),
    val endActionId: String? = null,
    val visitedActionIds: Set<String> = emptySet(),
    val pendingTransition: PreviewTransition? = null,
) {
    val canGoBack: Boolean get() = pendingTransition != null || history.size > 1 || endActionId != null
}

data class ProjectUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val retainedMediaWorkspaces: List<RetainedMediaWorkspace> = emptyList(),
    val project: ProjectSnapshot? = null,
    val route: ProjectRoute = ProjectRoute.PROJECTS,
    val selectedStepId: String? = null,
    val stepDraft: StepEditDraft? = null,
    val bitmap: Bitmap? = null,
    val busy: Boolean = false,
    val stage: String? = null,
    val message: String? = null,
    val loadFailed: Boolean = false,
    val preview: PreviewState? = null,
    val issues: List<ProjectIssue> = emptyList(),
    val dirtyStepIds: Set<String> = emptySet(),
    val editorExitIssue: String? = null,
    val canUndoEdit: Boolean = false,
    val projectCopyNotice: ProjectCopyNotice? = null,
    /** Also changes for unsaved text/geometry, invalidating an older preview immediately. */
    val editRevision: Long = 0,
) {
    val selectedStep: ProjectStep? get() = project?.steps?.firstOrNull { it.id == selectedStepId }
}

/**
 * Main-thread editor state, IO-only persistence and byte validation. Navigation never discards a
 * dirty draft. A failed or cancelled save rereads the commit point while retaining unsaved edits.
 * Published Bitmaps are not recycled here: Compose may still be drawing the previous state.
 */
class ProjectWorkspace(application: Application) : AndroidViewModel(application) {
    private val store = ProjectStore(application)
    private val mutableState = MutableStateFlow(ProjectUiState())
    val state = mutableState.asStateFlow()
    private val operationLock = Mutex()
    private var task: Job? = null
    private var previewSnapshot: ProjectSnapshot? = null
    private var previewEditRevision = -1L
    private var previewTask: Job? = null
    private var previewGeneration = 0L
    private var mediaRunSequence = 0L
    private val drafts = mutableMapOf<Pair<String, String>, DraftRecord>()
    private val draftWriteLock = Mutex()
    private val draftSessions = mutableMapOf<String, Long>()
    private val loadedDraftProjects = mutableSetOf<String>()
    private val draftGenerations = mutableMapOf<Pair<String, String>, Long>()
    private val pendingStages = linkedMapOf<Pair<String, String>, Long>()
    private var stagingTask: Job? = null
    private val savingDrafts = mutableMapOf<Pair<String, String>, StepEditDraft>()
    // Retain an ambiguous attempt across cancel/reload/retry; its ID is also the new step ID.
    private val pendingStepCopies = mutableMapOf<Pair<String, String>, SavedStepCopyAttempt>()
    private data class SavedStepCopyAttempt(val revision: Long, val operationId: String)
    // One session-local author edit only. Never retain pixels, source paths or media bindings.
    private var editorUndo: EditorUndo? = null

    private data class EditorUndo(
        val projectId: String,
        val stepId: String,
        val revision: Long,
        val fields: EditorDraftFields,
        val pendingForm: EditorPendingForm?,
        val textGroup: String?,
    )

    private fun clearEditorUndo() {
        editorUndo = null
        mutableState.update { it.copy(canUndoEdit = false) }
    }

    /** A focus change ends contiguous typing without discarding the last reversible edit. */
    fun endEditorTextEdit() { if (!state.value.busy) editorUndo = editorUndo?.copy(textGroup = null) }


    private data class DraftRecord(
        val draft: StepEditDraft,
        val baseStep: ProjectStep,
        val baseRevision: Long,
    )
    private data class LoadedProjects(
        val summaries: List<ProjectSummary>,
        val selected: ProjectSnapshot?,
        val retainedMediaWorkspaces: List<RetainedMediaWorkspace>,
    )

    init { reload() }

    fun reload() = execute("读取本地项目") { refresh() }

    /** Refresh an external media or region save without throwing away the author's typed text. */
    fun refreshAfterTransition(projectId: String, stepId: String) = execute("读取已保存编辑") {
        if (state.value.project?.project?.id != projectId) return@execute
        val fresh = withContext(Dispatchers.IO) { store.readProject(projectId) } ?: return@execute
        val step = fresh.steps.firstOrNull { it.id == stepId } ?: return@execute
        invalidatePreview()
        applyProject(fresh)
        mutableState.update { it.copy(route = ProjectRoute.EDIT, selectedStepId = step.id, stepDraft = draftFor(fresh, step)) }
        loadBitmap(fresh, step)
    }

    /** Creation is explicit; importing/cancelling media never implicitly creates another project. */
    fun createProject(title: String, goal: String = "") = execute("创建项目", editing = true) {
        val project = withContext(Dispatchers.IO) { store.createProject(title, goal) }
        applyProject(project)
        mutableState.update { it.copy(route = ProjectRoute.STEPS, selectedStepId = null, stepDraft = null, bitmap = null) }
    }

    fun openProject(id: String) = execute("打开项目") {
        clearEditorUndo()
        val project = withContext(Dispatchers.IO) { store.readProject(id) }
            ?: error("项目已不存在，请刷新项目列表")
        invalidatePreview()
        restoreDrafts(project)
        applyProject(project)
        mutableState.update { it.copy(route = ProjectRoute.STEPS, selectedStepId = null, stepDraft = null, bitmap = null) }
    }

    fun renameProject(title: String) {
        val id = state.value.project?.project?.id ?: return
        renameProject(id, title)
    }

    fun renameProject(id: String, title: String) = execute("重命名项目", editing = true) {
        val renamed = withContext(Dispatchers.IO) { store.renameProject(id, title) }
        if (state.value.project?.project?.id == id) applyProject(renamed)
    }

    /** Copy the saved project only. Reading home-page drafts must never rebase or clear them. */
    fun copySavedProject(sourceProjectId: String, blockedReason: String? = null) {
        val current = state.value
        if (current.busy || current.loadFailed) return
        val source = current.project?.project?.takeIf { it.id == sourceProjectId }
            ?: current.projects.firstOrNull { it.id == sourceProjectId } ?: return
        fun hasLiveInput() = drafts.any { (key, record) -> key.first == sourceProjectId &&
            (record.draft.dirty || record.draft.pendingForm != null || record.draft.conflicts.isNotEmpty()) }
        val inputMessage = "请先打开原项目，保存或放弃所有步骤的修改（含面板输入），再复制"
        var operationId: String? = null
        mutableState.update { it.copy(projectCopyNotice = null) }
        execute("复制已保存项目", editing = true, afterRefresh = {
            operationId?.let { reconcileProjectCopy(it) }
        }) {
            draftWriteLock.withLock {
                // Reconcile a prior commit even if the source has since acquired new input.
                val previous = withContext(Dispatchers.IO) { store.pendingProjectCopy(sourceProjectId) }
                if (previous?.status == "committed") {
                    operationId = previous.operationId
                    return@withLock
                }
                require(blockedReason == null) { blockedReason.orEmpty() }
                require(!hasLiveInput()) { inputMessage }
                val persisted = withContext(Dispatchers.IO) { store.readEditorDrafts(sourceProjectId) }
                require(persisted.values.none { it.pendingForm != null || it.edit != it.base }) { inputMessage }
                // Once begin writes its journal, retain its identity even if IO return is cancelled.
                coroutineContext.ensureActive()
                val attempt = withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { store.beginProjectCopy(sourceProjectId, source.revision) }
                        .also { operationId = it.operationId }
                }
                coroutineContext.ensureActive()
                if (attempt.status == "preparing") withContext(Dispatchers.IO) {
                    store.copySavedProject(sourceProjectId, attempt.sourceRevision, attempt.operationId)
                }
            }
        }
    }

    private suspend fun reconcileProjectCopy(operationId: String) {
        try {
            val receipt = withContext(Dispatchers.IO) { store.readProjectCopy(operationId) }
            when (receipt?.status) {
                "committed" -> {
                    if (state.value.loadFailed) {
                        message("项目已复制，结果尚未完整读取；请重新读取后，再点“复制项目”确认结果")
                        return
                    }
                    val copied = withContext(Dispatchers.IO) { store.readProject(receipt.projectId) }
                    val result = if (copied == null) {
                        "副本已创建，但已被删除；本次重试不会重新创建" +
                            if (receipt.missingRawSourceCount > 0) "；原项目缺失 ${receipt.missingRawSourceCount} 份原素材" else ""
                    } else {
                        clearEditorUndo()
                        invalidatePreview()
                        restoreDrafts(copied)
                        applyProject(copied)
                        mutableState.update { it.copy(route = ProjectRoute.STEPS, selectedStepId = null,
                            stepDraft = null, bitmap = null) }
                        "已复制并打开副本，交付前请重新复核" +
                            if (receipt.missingRawSourceCount > 0) "；原项目缺失 ${receipt.missingRawSourceCount} 份原素材，已保存画面仍保留" else ""
                    }
                    mutableState.update { it.copy(message = result,
                        projectCopyNotice = ProjectCopyNotice(operationId, result)) }
                }
                "aborted" -> message("复制未完成，原项目保持原样；可再次复制")
                "preparing" -> message((state.value.message ?: "复制尚未完成") + "；再点“复制项目”可继续本次操作")
                else -> message("无法确认复制结果；请重新读取后，再点“复制项目”重试")
            }
        } catch (_: Exception) {
            // A read failure is not evidence of rollback. The store keeps this attempt for retry.
            message("无法确认复制结果；请重新读取后，再点“复制项目”重试")
        }
    }

    /** Called by the screen only after it has composed the authoritative copy result. */
    fun acknowledgeProjectCopyResult(operationId: String) {
        val notice = state.value.projectCopyNotice?.takeIf { it.operationId == operationId } ?: return
        viewModelScope.launch {
            operationLock.withLock {
                if (state.value.projectCopyNotice?.operationId != operationId) return@withLock
                try {
                    withContext(Dispatchers.IO) { store.acknowledgeProjectCopy(operationId) }
                    mutableState.update { if (it.projectCopyNotice?.operationId == operationId)
                        it.copy(projectCopyNotice = null) else it }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    message("${notice.message}；结果确认未完成，再点“复制项目”可核对，勿重复新建")
                }
            }
        }
    }

    /** The caller presents the deletion impact and gets explicit confirmation before this event. */
    fun deleteProject(id: String) = execute("删除本地项目", editing = true) {
        drafts.keys.filter { it.first == id }.forEach(::invalidateStaging)
        val result = draftWriteLock.withLock { withContext(Dispatchers.IO) { store.deleteProject(id) } }
        draftSessions.remove(id)
        loadedDraftProjects.remove(id)
        drafts.keys.removeAll { it.first == id }
        if (state.value.project?.project?.id == id) {
            clearEditorUndo()
            mutableState.update { it.copy(project = null, route = ProjectRoute.PROJECTS,
                selectedStepId = null, stepDraft = null, bitmap = null, preview = null, issues = emptyList(), dirtyStepIds = emptySet()) }
        }
        message(if (result.pendingAssetCleanupCount > 0) "项目已删除，部分本机图片（可能含原截图）将在下次打开时继续清理" else "本地项目已删除")
    }

    fun openStep(id: String) = execute("读取步骤画面") {
        clearEditorUndo()
        val project = state.value.project ?: return@execute
        val step = project.steps.firstOrNull { it.id == id } ?: return@execute
        invalidatePreview()
        val draft = draftFor(project, step)
        mutableState.update { it.copy(route = ProjectRoute.EDIT, selectedStepId = id, stepDraft = draft, bitmap = null) }
        loadBitmap(project, step)
    }

    /** The hosting screen must first successfully activate the same project's media workbench. */
    fun openMedia() {
        if (state.value.busy || state.value.project == null || state.value.loadFailed) return
        clearEditorUndo()
        invalidatePreview()
        mutableState.update { it.copy(route = ProjectRoute.MEDIA, bitmap = null) }
    }

    fun back() {
        if (state.value.busy) return
        clearEditorUndo()
        when (state.value.route) {
            ProjectRoute.PREVIEW -> exitPreview()
            ProjectRoute.EDIT, ProjectRoute.MEDIA -> {
                invalidatePreview()
                mutableState.update { it.copy(route = ProjectRoute.STEPS, bitmap = null) }
            }
            ProjectRoute.STEPS -> {
                invalidatePreview()
                mutableState.update { it.copy(route = ProjectRoute.PROJECTS, project = null,
                    selectedStepId = null, stepDraft = null, bitmap = null, issues = emptyList(), dirtyStepIds = emptySet()) }
            }
            ProjectRoute.PROJECTS -> Unit
        }
    }

    fun editTitle(value: String) = editDraft(textGroup = "title") { it.copy(title = value) }
    fun editDescription(value: String) = editDraft(textGroup = "description") { it.copy(description = value) }

    fun editTerminal(value: Boolean) {
        val draft = state.value.stepDraft ?: return
        if (value && (draft.hotspots.isNotEmpty() || draft.nextAction != null)) {
            message("请先移除本步骤的热点和下一步动作，再设为终点")
            return
        }
        editDraft { it.copy(isTerminal = value) }
    }

    fun putHotspot(hotspot: ProjectHotspot) {
        val draft = state.value.stepDraft ?: return
        if (draft.isTerminal) {
            message("请先取消终点标记，再添加热点")
            return
        }
        if (draft.hotspots.none { it.id == hotspot.id } && draft.hotspots.size >= ProjectLimits.MAX_HOTSPOTS_PER_STEP) {
            message("每个步骤最多 6 个热点")
            return
        }
        editDraft { old -> old.copy(hotspots = if (old.hotspots.any { it.id == hotspot.id })
            old.hotspots.map { if (it.id == hotspot.id) hotspot else it } else old.hotspots + hotspot) }
    }

    fun removeHotspot(id: String) = editDraft { it.copy(hotspots = it.hotspots.filterNot { hotspot -> hotspot.id == id }) }

    /** An authored button outside the canvas; it never creates a touch region. */
    fun putNextAction(action: ProjectNextAction) {
        val draft = state.value.stepDraft ?: return
        if (draft.isTerminal) {
            message("请先取消终点标记，再添加下一步动作")
            return
        }
        if (action.targetStepId != null && state.value.project?.steps?.none { it.id == action.targetStepId } == true) {
            message("下一步目标已不存在，请重新选择当前项目中的步骤")
            return
        }
        // Removing and re-adding before saving is still an edit of the persisted button.
        val base = state.value.project?.project?.id?.let { drafts[it to draft.stepId]?.baseStep?.nextAction }
        val stableId = draft.nextAction?.id ?: base?.id ?: action.id
        editDraft { it.copy(nextAction = action.copy(id = stableId)) }
    }

    fun removeNextAction() = editDraft { it.copy(nextAction = null) }

    private fun editDraft(textGroup: String? = null, recordUndo: Boolean = true, change: (StepEditDraft) -> StepEditDraft) {
        val current = state.value
        if (current.busy || current.loadFailed) return
        val project = current.project ?: return
        val old = current.stepDraft ?: return
        if (old.conflicts.isNotEmpty()) return
        val key = project.project.id to old.stepId
        val record = drafts[key] ?: return
        val changed = change(old)
        if (changed == old) return
        if (recordUndo) {
            val previous = editorUndo
            if (previous == null || textGroup == null || previous.textGroup != textGroup || previous.projectId != project.project.id ||
                previous.stepId != old.stepId || previous.revision != project.project.revision) {
                editorUndo = EditorUndo(project.project.id, old.stepId, project.project.revision,
                    old.fields(), old.pendingForm, textGroup)
            }
            editorUndo?.let { if (changed.fields() == it.fields && changed.pendingForm == it.pendingForm) editorUndo = null }
        }
        val next = changed.copy(dirty = true,
            recoveryStatus = DraftRecoveryStatus.STAGING)
        drafts[key] = record.copy(draft = next)
        invalidatePreview(edited = true)
        mutableState.update { it.copy(stepDraft = next, dirtyStepIds = dirtyIds(project.project.id), canUndoEdit = editorUndo != null, message = null) }
        queueStage(key)
    }

    /** Re-read before applying: a stale local history must never undo a newer official graph. */
    fun undoEditorEdit() {
        val current = state.value
        if (current.busy || current.loadFailed || current.route != ProjectRoute.EDIT) return
        val undo = editorUndo ?: return
        val project = current.project ?: return
        val draft = current.stepDraft ?: return
        if (draft.conflicts.isNotEmpty() || undo.projectId != project.project.id || undo.stepId != draft.stepId) return
        execute("撤销本次编辑", editing = true) {
            val fresh = withContext(Dispatchers.IO) { store.readProject(undo.projectId) }
                ?: error("项目已不存在，请刷新")
            applyProject(fresh)
            if (fresh.project.revision != undo.revision || editorUndo !== undo) {
                clearEditorUndo()
                message("已保存内容有更新，旧编辑不能撤销；当前输入仍保留")
                return@execute
            }
            val step = fresh.steps.firstOrNull { it.id == undo.stepId } ?: return@execute
            val key = undo.projectId to undo.stepId
            val record = drafts[key] ?: return@execute
            val fields = EditorDraftReconciliation.prune(undo.fields, fresh.steps.map { it.id }.toSet())
            val pending = EditorDraftReconciliation.prune(undo.pendingForm, fresh.steps.map { it.id }.toSet())
            // withCurrentMedia inside toDraft can only attach bindings from this official step.
            drafts[key] = record.copy(draft = fields.toDraft(step, pending).copy(recoveryStatus = DraftRecoveryStatus.STAGING))
            clearEditorUndo() // Consume before queued persistence; a repeated tap is never redo.
            invalidatePreview(edited = true)
            publishDraftState(fresh)
            queueStage(key)
            message("已撤销本次编辑")
        }
    }

    fun saveStepDraft() = saveStepDraftInternal(null)

    /** Validate and commit this panel and this step once; raw panel input survives any failure. */
    fun savePendingStepForm() = saveStepDraftInternal(null, includePending = true)

    fun savePendingStepForm(projectId: String, stepId: String) {
        if (state.value.project?.project?.id == projectId && state.value.selectedStepId == stepId) savePendingStepForm()
    }

    fun saveBeforeTransition(onSaved: () -> Unit) = saveStepDraftInternal(onSaved)

    private fun saveStepDraftInternal(onSaved: (() -> Unit)?, includePending: Boolean = false) {
        if (state.value.busy || state.value.loadFailed) return
        val project = state.value.project ?: return
        val draft = state.value.stepDraft ?: return
        if (draft.pendingForm != null && !includePending) { message("请在面板中保存本步，或取消面板输入"); return }
        if (!draft.dirty) { onSaved?.invoke(); return }
        val key = project.project.id to draft.stepId
        val record = drafts[key] ?: return
        if (draft.conflicts.isNotEmpty() || record.baseRevision != project.project.revision) {
            message("此步骤有新的已保存内容，请先处理冲突。你的修改仍保留")
            return
        }
        val submitted = try { if (includePending) draft.withSubmittedForm(project.steps) else draft }
        catch (failure: IllegalArgumentException) { message(failure.message ?: "请检查面板输入"); return }
        invalidateStaging(key)
        savingDrafts[key] = submitted
        execute("保存步骤", editing = true, afterRefresh = {
            savingDrafts.remove(key)
            if (drafts[key]?.draft?.dirty == true) queueStage(key)
        }) {
            draftWriteLock.withLock {
                // Cancellation may stop waiting for the lock. Once SQL starts, retain its
                // committed snapshot even if cancellation or the subsequent refresh fails.
                withContext(NonCancellable) {
                    val session = sessionFor(project.project.id)
                    val saved = withContext(Dispatchers.IO) {
                        store.saveStepDraft(project.project.id, draft.stepId, submitted.title, submitted.description,
                            submitted.isTerminal, submitted.hotspots, expectedRevision = record.baseRevision,
                            nextAction = submitted.nextAction, editorDraftSession = session)
                    }
                    clearEditorUndo()
                    saved.steps.firstOrNull { it.id == draft.stepId }?.let { step ->
                        drafts[key] = DraftRecord(step.toDraft(), step, saved.project.revision)
                    }
                    applyProject(saved)
                    message("步骤已保存")
                }
            }
            coroutineContext.ensureActive()
            onSaved?.invoke()
        }
    }

    fun discardStepDraft() = discardStepDraftInternal(leave = false)

    fun discardStepDraftAndLeave() = discardStepDraftInternal(leave = true)

    private fun discardStepDraftInternal(leave: Boolean) {
        if (state.value.busy) return
        val project = state.value.project ?: return
        val step = state.value.selectedStep ?: return
        val key = project.project.id to step.id
        invalidateStaging(key)
        execute("放弃本步修改", editing = true, afterRefresh = {
            if (drafts[key]?.draft?.dirty == true) queueStage(key)
            else if (leave && !state.value.loadFailed) leaveEditorRoute()
        }) {
            draftWriteLock.withLock {
                // Once clearing starts, keep its committed result and in-memory state together.
                // Cancellation while waiting for the lock still leaves the author's input intact.
                withContext(NonCancellable) {
                    val session = sessionFor(project.project.id)
                    check(withContext(Dispatchers.IO) { store.clearEditorDraft(project.project.id, step.id, session) })
                    clearEditorUndo()
                    drafts[key] = DraftRecord(step.toDraft(), step, project.project.revision)
                    applyProject(project)
                }
            }
        }
    }

    /** Return only after this exact draft has crossed its local persistence boundary. */
    fun leaveEditor() {
        val current = state.value
        if (current.busy || current.loadFailed) return
        val project = current.project ?: return
        val draft = current.stepDraft ?: return
        if (draft.conflicts.isNotEmpty()) {
            mutableState.update { it.copy(editorExitIssue = "本步有尚未处理的内容冲突。请继续编辑核对，或明确放弃本步修改。") }
            return
        }
        if (!draft.dirty || draft.recoveryStatus == DraftRecoveryStatus.STAGED) {
            leaveEditorRoute()
            return
        }
        val key = project.project.id to draft.stepId
        invalidateStaging(key)
        execute("暂存本步并返回", afterRefresh = {
            val latest = drafts[key]?.draft
            if (!state.value.loadFailed && latest != null && latest.conflicts.isEmpty() &&
                (!latest.dirty || latest.recoveryStatus == DraftRecoveryStatus.STAGED)) leaveEditorRoute()
            else mutableState.update { it.copy(editorExitIssue = if (latest?.conflicts?.isNotEmpty() == true)
                "本步有尚未处理的内容冲突。请继续编辑核对，或明确放弃本步修改。"
                else "本步修改尚未确认落盘，关闭应用可能丢失。输入仍在，请重试暂存或继续编辑。") }
        }) {
            var committed = false
            try {
                draftWriteLock.withLock {
                    // Keep a committed write and its status together, including cancellation.
                    withContext(NonCancellable) {
                        val latest = drafts.getValue(key)
                        val staged = latest.toStoredDraft()
                        val session = sessionFor(project.project.id)
                        check(withContext(Dispatchers.IO) { store.writeEditorDraft(project.project.id, draft.stepId, session, staged) })
                        drafts[key] = latest.copy(draft = latest.draft.copy(dirty = staged != null,
                            recoveryStatus = if (staged == null) DraftRecoveryStatus.NONE else DraftRecoveryStatus.STAGED))
                        publishDraftState(project)
                        committed = true
                    }
                }
            } catch (failure: Exception) {
                if (!committed) drafts[key]?.let { latest -> drafts[key] = latest.copy(draft = latest.draft.copy(recoveryStatus = DraftRecoveryStatus.FAILED)) }
                publishDraftState(project)
                throw failure
            }
        }
    }

    fun dismissEditorExitIssue() { mutableState.update { it.copy(editorExitIssue = null) } }

    private fun leaveEditorRoute() {
        clearEditorUndo()
        invalidatePreview()
        mutableState.update { it.copy(route = ProjectRoute.STEPS, bitmap = null, editorExitIssue = null) }
    }

    fun editPendingForm(projectId: String, stepId: String, form: EditorPendingForm?) {
        val current = state.value
        if (current.busy || current.loadFailed || current.project?.project?.id != projectId || current.selectedStepId != stepId) return
        val old = current.stepDraft?.pendingForm
        if (old == form) return
        val samePanel = old != null && form != null && old.kind == form.kind &&
            old.objectId == form.objectId && old.edgeId == form.edgeId
        if (!samePanel) clearEditorUndo() // Opening is not an edit; cancellation never reopens old input.
        editDraft(textGroup = if (samePanel) pendingTextGroup(old!!, form!!) else null, recordUndo = samePanel) {
            it.copy(pendingForm = form)
        }
    }

    private fun pendingTextGroup(old: EditorPendingForm, next: EditorPendingForm): String? {
        val changes = listOf("title" to (old.title != next.title), "description" to (old.description != next.description),
            "label" to (old.label != next.label), "left" to (old.left != next.left), "top" to (old.top != next.top),
            "right" to (old.right != next.right), "bottom" to (old.bottom != next.bottom),
            "endLabel" to (old.endLabel != next.endLabel)).filter { it.second }
        return changes.singleOrNull()?.first?.takeIf {
            old.targetStepId == next.targetStepId && old.endsDemo == next.endsDemo
        }?.let { "panel:${next.kind}:${next.objectId}:$it" }
    }

    fun retryDraftStaging() {
        val projectId = state.value.project?.project?.id ?: return
        drafts.keys.filter { it.first == projectId && drafts[it]?.draft?.recoveryStatus == DraftRecoveryStatus.FAILED }
            .forEach(::queueStage)
    }

    fun resolveDraftConflict(keepMine: Boolean) {
        if (state.value.busy || state.value.loadFailed) return
        clearEditorUndo()
        val project = state.value.project ?: return
        val step = state.value.selectedStep ?: return
        val key = project.project.id to step.id
        val record = drafts[key] ?: return
        val fields = if (keepMine) record.draft.fields() else EditorDraftReconciliation.merge(
            record.baseStep.editorFields(), record.draft.fields(), step.editorFields(), useSavedConflicts = true).fields
        val pending = if (keepMine) record.draft.pendingForm else EditorDraftReconciliation.mergePending(
            record.draft.pendingForm, record.baseStep.editorFields(), step.editorFields(), useSavedConflicts = true).first
        val draft = fields.toDraft(step, pending).copy(recoveryStatus = DraftRecoveryStatus.STAGING)
        drafts[key] = DraftRecord(draft, step, project.project.revision)
        invalidatePreview(edited = true)
        publishDraftState(project)
        queueStage(key)
    }

    fun setStart(stepId: String) {
        val id = state.value.project?.project?.id ?: return
        execute("设置起点", editing = true) {
            applyProject(withContext(Dispatchers.IO) { store.setStartStep(id, stepId) })
        }
    }

    fun moveStep(stepId: String, delta: Int) {
        val project = state.value.project ?: return
        val ids = project.steps.map { it.id }.toMutableList()
        val from = ids.indexOf(stepId)
        if (from < 0 || delta == 0) return
        val to = (from + delta.coerceIn(-1, 1)).coerceIn(ids.indices)
        if (from == to) return
        ids.add(to, ids.removeAt(from))
        execute("保存步骤顺序", editing = true) {
            applyProject(withContext(Dispatchers.IO) { store.reorderSteps(project.project.id, ids) })
        }
    }

    /** Copy only the formal step, after the source's own editor input has been handled. */
    fun copySavedStep(stepId: String) {
        val current = state.value
        if (current.busy || current.loadFailed) return
        val project = current.project ?: return
        if (project.steps.none { it.id == stepId }) return
        val key = project.project.id to stepId
        val previous = pendingStepCopies[key]
        if (previous != null && project.steps.any { it.id == previous.operationId &&
                it.captureId == "copy:$stepId:${previous.revision}:${previous.operationId}" }) {
            pendingStepCopies.remove(key)
            message("步骤已复制，请接入路线；过渡需重新设置")
            return
        }
        fun hasSourceInput(): Boolean = drafts[key]?.draft?.let {
            it.dirty || it.pendingForm != null || it.conflicts.isNotEmpty()
        } == true
        if (hasSourceInput()) {
            message("请先打开这一步，保存或放弃本步修改（含面板输入），再复制")
            return
        }
        val attempt = pendingStepCopies.getOrPut(key) { SavedStepCopyAttempt(project.project.revision, UUID.randomUUID().toString()) }
        val operationId = attempt.operationId
        var committed = false
        execute("复制已保存步骤", editing = true, afterRefresh = {
            val found = state.value.project?.takeIf { it.project.id == project.project.id }
                ?.steps?.any { it.id == operationId && it.captureId == "copy:$stepId:${attempt.revision}:$operationId" } == true
            if (committed || found) {
                pendingStepCopies.remove(key)
                message(if (state.value.loadFailed) "步骤已复制；请先重读项目，再接入路线并重设过渡"
                    else "步骤已复制，请接入路线；过渡需重新设置")
            } else if (state.value.loadFailed) {
                message("无法确认复制结果，请先重读项目，再点“复制步骤”重试")
            } else {
                // A completed authoritative reread establishes that this attempt did not commit.
                pendingStepCopies.remove(key)
            }
        }) {
            draftWriteLock.withLock {
                require(state.value.project?.project?.id == project.project.id) { "项目已变化，请重新选择要复制的步骤" }
                require(!hasSourceInput()) { "请先保存或放弃本步修改（含面板输入），再复制" }
                val saved = withContext(Dispatchers.IO) {
                    store.copySavedStep(project.project.id, stepId, attempt.revision, operationId)
                }
                committed = true
                // Rebase other steps' drafts while holding their writer barrier. Never submit them.
                applyProject(saved)
            }
        }
    }

    /** The caller first confirms this exact order and whether its final step becomes terminal. */
    fun connectStepsInOrder(stepIds: List<String>, markLastTerminal: Boolean, expectedRevision: Long) {
        val current = state.value
        if (current.busy || current.loadFailed) return
        val project = current.project ?: return
        if (dirtyIds(project.project.id).isNotEmpty()) {
            message("还有未保存的步骤修改，请先保存或放弃修改后生成通路")
            return
        }
        val order = stepIds.toList()
        if (project.project.revision != expectedRevision || order.size < 2 ||
            order.toSet().size != order.size || order.any { id -> project.steps.none { it.id == id } }) {
            message("项目或步骤已变化，请重新查看顺序并确认通路")
            return
        }
        execute("保存作者确认的顺序通路", editing = true) {
            require(state.value.project?.project?.id == project.project.id) { "当前项目已变化，请重新确认通路" }
            require(dirtyIds(project.project.id).isEmpty()) { "请先保存或放弃步骤修改后生成通路" }
            val saved = withContext(Dispatchers.IO) {
                store.connectStepsInOrder(project.project.id, order, markLastTerminal, expectedRevision)
            }
            applyProject(saved)
            message("顺序通路已保存；画面热点保持原样")
        }
    }

    fun deletionHotspotCount(stepId: String): Int {
        val project = state.value.project ?: return 0
        return project.steps.flatMap { step ->
            val retained = drafts[project.project.id to step.id]?.draft?.hotspots.orEmpty()
            (step.hotspots + retained).filter { step.id == stepId || it.targetStepId == stepId }
        }.map { it.id }.toSet().size
    }

    fun deletionNextActionCount(stepId: String): Int {
        val project = state.value.project ?: return 0
        return project.steps.flatMap { step ->
            listOfNotNull(step.nextAction, drafts[project.project.id to step.id]?.draft?.nextAction)
                .filter { step.id == stepId || it.targetStepId == stepId }
        }.map { it.id }.toSet().size
    }

    /** Hotspots are removed; incoming next buttons become unresolved, after UI confirmation. */
    fun deleteStep(stepId: String) {
        val project = state.value.project ?: return
        execute("删除步骤并更新相关动作", editing = true, afterRefresh = {
            // Even a cancelled dispatcher return can hide a successful deletion transaction.
            if (state.value.project?.project?.id == project.project.id && state.value.project?.steps?.none { it.id == stepId } == true) {
                drafts.remove(project.project.id to stepId)
                pruneDeletedTargets(project.project.id, stepId)
            }
        }) {
            invalidateStaging(project.project.id to stepId)
            val result = draftWriteLock.withLock { withContext(Dispatchers.IO) { store.deleteStep(project.project.id, stepId) } }
            drafts.remove(project.project.id to stepId)
            applyProject(result.snapshot)
            // Never resurrect a just-deleted target from another step's retained dirty draft.
            pruneDeletedTargets(project.project.id, stepId)
            if (state.value.selectedStepId == stepId || state.value.route == ProjectRoute.EDIT) {
                mutableState.update { it.copy(route = ProjectRoute.STEPS, selectedStepId = null, stepDraft = null, bitmap = null) }
            }
            message("步骤已删除，已移除 ${result.impact.hotspotCount} 个相关热点" +
                (if (result.impact.incomingNextActionCount > 0) "；${result.impact.incomingNextActionCount} 个下一步动作需重选目标" else "") +
                (if (result.pendingAssetCleanupCount > 0) "；本机图片（可能含原截图）稍后继续清理" else ""))
        }
    }

    /**
     * Called directly inside MediaWorkspace's operation lock. This must remain a suspending commit,
     * never a viewModelScope launch: the caller owns and may clean up the reviewed candidate.
     */
    suspend fun saveReviewedStep(projectId: String, input: ReviewedStepInput, openEditor: Boolean = true,
        title: String? = null): String = withContext(Dispatchers.Main.immediate) {
        check(!state.value.busy) { "项目正在保存，请稍后重试" }
        check(state.value.project?.project?.id == projectId) { "当前项目已变化，请重新打开素材工作台" }
        check(!state.value.loadFailed) { "请先重新读取本地项目" }
        var stepId = UUID.randomUUID().toString()
        mutableState.update { it.copy(busy = true, stage = "保存已复核步骤", message = null) }
        var locked = false
        var committed: ProjectSnapshot? = null
        try {
            operationLock.lock()
            locked = true
            invalidatePreview(edited = true)
            // A title is author-approved input for a NEW step only. addReviewedStep's capture
            // token reconciliation returns the original step on retry, without changing it.
            val newTitle = title?.trim()?.takeIf { it.isNotEmpty() }
                ?: "步骤 ${(state.value.project?.steps?.size ?: 0) + 1}"
            val saved = withContext(Dispatchers.IO) {
                store.addReviewedStep(projectId, input, newTitle, stepId = stepId)
            }
            committed = saved
            // A retry may return the existing step for this candidate's capture token.
            stepId = saved.steps.first { it.captureId == input.captureId }.id
            // No fallible refresh/decode after the commit is allowed to turn it into a failed add.
            applyProject(saved)
            if (openEditor) selectCommittedStep(stepId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            message(if (failure is IllegalArgumentException) failure.message ?: "请检查步骤输入后重试"
                else "步骤未能保存，请检查本机存储后重试。复核成品仍保留")
            throw failure
        } finally {
            withContext(NonCancellable) {
                try {
                    refresh()
                    // Cancellation can arrive immediately after the store's atomic commit.
                    val saved = state.value.project?.steps?.firstOrNull { it.captureId == input.captureId }
                    if (saved != null) {
                        stepId = saved.id
                        if (openEditor) selectCommittedStep(stepId)
                        if (openEditor) {
                            try { loadBitmap(state.value.project!!, saved) }
                            catch (_: Exception) { clearFailedBitmap() }
                        }
                        message("已复核图片已加入步骤")
                    } else if (committed != null) {
                        // A reread failure retains the already-returned committed snapshot.
                        if (openEditor) selectCommittedStep(stepId)
                    }
                } catch (_: Exception) {
                    mutableState.update { it.copy(loadFailed = true, bitmap = null,
                        message = if (committed != null) "步骤已保存，但暂时无法重读；请刷新项目" else "无法确认保存结果，请刷新项目后再添加") }
                } finally {
                    if (locked) operationLock.unlock()
                    mutableState.update { it.copy(busy = false, stage = null) }
                }
            }
        }
        stepId
    }

    /** Runs under MediaWorkspace's candidate lock; never auto-saves typed text or actions. */
    suspend fun replaceReviewedStep(correction: StepImageCorrection, input: ReviewedStepInput) =
        withContext(Dispatchers.Main.immediate) {
            check(!state.value.busy) { "项目正在保存，请稍后重试" }
            if (state.value.project?.project?.id != correction.projectId || state.value.loadFailed)
                throw StepCorrectionException("项目已改变，请返回后重新打开这一步。")
            mutableState.update { it.copy(busy = true, stage = "替换已复核画面", message = null) }
            var locked = false
            var committed: ProjectSnapshot? = null
            try {
                operationLock.lock()
                locked = true
                invalidatePreview(edited = true)
                val saved = withContext(Dispatchers.IO) {
                    store.replaceReviewedStep(correction.projectId, correction.stepId, correction.expectedRevision, input)
                }
                committed = saved
                reconcileReplacementDrafts(saved, correction.stepId)
                applyProject(saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val current = withContext(Dispatchers.IO) { store.readProject(correction.projectId) }
                throw StepCorrectionException(if (current?.project?.revision != correction.expectedRevision)
                    "步骤已改变，未覆盖当前画面。请返回后重新打开。"
                    else "画面暂未替换，请检查本机存储后重试；原步骤仍保留。")
            } finally {
                withContext(NonCancellable) {
                    try {
                        val fresh = withContext(Dispatchers.IO) { store.readProject(correction.projectId) }
                        val saved = fresh?.steps?.singleOrNull { it.id == correction.stepId && it.captureId == input.captureId }
                        if (fresh != null) {
                            if (saved != null) reconcileReplacementDrafts(fresh, correction.stepId)
                            applyProject(fresh)
                            selectCommittedStep(correction.stepId)
                            fresh.steps.firstOrNull { it.id == correction.stepId }?.let { step ->
                                try { loadBitmap(fresh, step) } catch (_: Exception) { clearFailedBitmap() }
                            }
                            if (saved != null) message("画面已替换；文字和动作保持原样" +
                                if (correction.regionCount + correction.transitionCount > 0) "，区域和相关过渡需重新处理" else "")
                        }
                    } catch (_: Exception) {
                        // The returned commit remains authoritative if a subsequent reread fails.
                        committed?.let { reconcileReplacementDrafts(it, correction.stepId); applyProject(it) }
                        mutableState.update { it.copy(loadFailed = true, bitmap = null,
                            message = if (committed != null) "画面已保存，但暂时无法重读；请刷新项目。"
                                else "暂时无法确认保存结果；请返回后刷新项目。") }
                    } finally {
                        if (locked) operationLock.unlock()
                        mutableState.update { it.copy(busy = false, stage = null) }
                    }
                }
            }
        }

    /** Rebinding always reads current official media, including every incoming edge. */
    private fun reconcileReplacementDrafts(fresh: ProjectSnapshot, @Suppress("UNUSED_PARAMETER") replacedStepId: String) {
        reconcileDrafts(fresh)
    }

    private fun selectCommittedStep(stepId: String) {
        val project = state.value.project ?: return
        val step = project.steps.firstOrNull { it.id == stepId } ?: return
        mutableState.update { it.copy(route = ProjectRoute.EDIT, selectedStepId = stepId,
            stepDraft = draftFor(project, step), bitmap = null) }
    }

    fun startPreview(fromCurrent: Boolean = false) {
        val project = state.value.project ?: return
        if (drafts.any { it.key.first == project.project.id && it.value.draft.dirty }) {
            message("还有未保存的步骤修改，请先保存或放弃修改后预览")
            return
        }
        val requestedId = if (fromCurrent) state.value.selectedStepId else project.project.startStepId
        if (requestedId == null) {
            message(if (project.steps.isEmpty()) "先添加一个已生成并复核的步骤" else "请先设置项目起点")
            return
        }
        execute("读取预览安全画面") {
            val fresh = withContext(Dispatchers.IO) { store.readProject(project.project.id) }
                ?: error("项目已不存在")
            applyProject(fresh)
            require(dirtyIds(project.project.id).isEmpty()) { "请先保存或放弃恢复的修改，再预览" }
            val start = fresh.steps.firstOrNull { it.id == requestedId }
                ?: error("预览步骤已不存在，请重新选择")
            mutableState.update { it.copy(bitmap = null) }
            val bitmap = decodeBitmap(fresh, start) ?: return@execute
            previewSnapshot = fresh
            previewEditRevision = state.value.editRevision
            mutableState.update { it.copy(route = ProjectRoute.PREVIEW, selectedStepId = start.id, bitmap = bitmap,
                preview = PreviewState(fresh.project.id, fresh.project.revision, start.id, listOf(start.id),
                    ended = start.isTerminal, endLabel = start.title.takeIf { _ -> start.isTerminal })) }
        }
    }

    fun tapPreview(x: Float, y: Float) {
        if (state.value.busy || state.value.bitmap == null || !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return
        val snapshot = validPreview() ?: return
        val preview = state.value.preview ?: return
        if (preview.ended || preview.pendingTransition != null) return
        val step = snapshot.steps.firstOrNull { it.id == preview.currentStepId } ?: return
        val matches = step.hotspots.filter { x >= it.rect.left && x <= it.rect.right && y >= it.rect.top && y <= it.rect.bottom }
        when (matches.size) {
            0 -> message("此处没有热点，可使用下方文字动作")
            1 -> chooseHotspot(matches.single().id)
            else -> mutableState.update { it.copy(preview = preview.copy(matchingHotspotIds = matches.map { match -> match.id })) }
        }
    }

    fun dismissPreviewChoices() {
        mutableState.update { it.copy(preview = it.preview?.copy(matchingHotspotIds = emptyList())) }
    }

    fun chooseHotspot(id: String) {
        if (state.value.busy || state.value.bitmap == null) return
        val snapshot = validPreview() ?: return
        val preview = state.value.preview ?: return
        if (preview.ended || preview.pendingTransition != null) return
        val step = snapshot.steps.firstOrNull { it.id == preview.currentStepId } ?: return
        val hotspot = step.hotspots.firstOrNull { it.id == id } ?: return
        selectPreviewAction(snapshot, preview, hotspot.id, hotspot.targetStepId, hotspot.endLabel,
            hotspot.transition, "这个热点的目标已缺失，请回编辑修正", edgeId = hotspot.edgeId)
    }

    /** Invoked only by the authored button, never by canvas hit testing or list position. */
    fun chooseNextAction() {
        if (state.value.busy || state.value.bitmap == null) return
        val snapshot = validPreview() ?: return
        val preview = state.value.preview ?: return
        if (preview.ended || preview.pendingTransition != null) return
        val action = snapshot.steps.firstOrNull { it.id == preview.currentStepId }?.nextAction ?: return
        selectPreviewAction(snapshot, preview, action.id, action.targetStepId, null, action.transition,
            "下一步目标已缺失，请回编辑重新选择；此处不是结束")
    }

    private fun selectPreviewAction(snapshot: ProjectSnapshot, preview: PreviewState, actionId: String,
        targetStepId: String?, endLabel: String?, transition: ProjectTransition?, missingTargetMessage: String,
        edgeId: String = actionId) {
        if (targetStepId == null && endLabel == null || targetStepId != null && snapshot.steps.none { it.id == targetStepId }) {
            message(missingTargetMessage)
            return
        }
        if (targetStepId != null && preview.history.size >= MAX_PREVIEW_VISITS) {
            dismissPreviewChoices()
            message("已试走 256 次，请点“重来”开启新一轮预览")
            return
        }
        if (transition != null) {
            val pending = PreviewTransition(actionId, edgeId, targetStepId, endLabel, transition,
                LocalVideoRun(null, transition.asset.width, transition.asset.height, ++mediaRunSequence))
            mutableState.update { it.copy(preview = preview.copy(matchingHotspotIds = emptyList(), pendingTransition = pending)) }
            loadPreviewTransition(snapshot, pending)
        } else commitPreviewAction(snapshot, preview, actionId, targetStepId, endLabel)
    }

    private fun loadPreviewTransition(snapshot: ProjectSnapshot, pending: PreviewTransition) = launchPreviewWork {
        try {
            val file = withContext(Dispatchers.IO) {
                val resolved = store.resolveTransition(snapshot.project.id, pending.edgeId)
                val expected = pending.transition.asset
                require(resolved.length() == expected.byteLength) { "过渡已变化" }
                val digest = MessageDigest.getInstance("SHA-256")
                resolved.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == expected.sha256) { "过渡已变化" }
                resolved
            }
            val active = state.value.preview?.pendingTransition
            if (active?.video?.runId == pending.video.runId) mutableState.update {
                it.copy(preview = it.preview?.copy(pendingTransition = active.copy(video = active.video.copy(file = file))))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failPreviewTransition(pending.video.runId) }
    }

    fun completePreviewTransition(runId: Long) {
        val snapshot = validPreview() ?: return
        val preview = state.value.preview ?: return
        val pending = preview.pendingTransition ?: return
        if (state.value.busy || pending.video.runId != runId || pending.video.failed || pending.video.file == null) return
        commitPreviewAction(snapshot, preview, pending.actionId, pending.targetStepId, pending.endLabel)
    }

    fun failPreviewTransition(runId: Long) {
        val preview = state.value.preview ?: return
        val pending = preview.pendingTransition ?: return
        if (pending.video.runId != runId) return
        mutableState.update { it.copy(preview = preview.copy(pendingTransition = pending.copy(video = pending.video.copy(file = null, failed = true)))) }
    }

    fun retryPreviewTransition(runId: Long) {
        if (state.value.busy) return
        val snapshot = validPreview() ?: return
        val preview = state.value.preview ?: return
        val old = preview.pendingTransition ?: return
        if (old.video.runId != runId) return
        val pending = old.copy(video = old.video.copy(file = null, failed = false, runId = ++mediaRunSequence))
        mutableState.update { it.copy(preview = preview.copy(pendingTransition = pending)) }
        loadPreviewTransition(snapshot, pending)
    }

    fun skipPreviewTransition() {
        if (state.value.busy) return
        val snapshot = validPreview() ?: return
        val preview = state.value.preview ?: return
        val pending = preview.pendingTransition ?: return
        commitPreviewAction(snapshot, preview, pending.actionId, pending.targetStepId, pending.endLabel)
    }

    private fun commitPreviewAction(snapshot: ProjectSnapshot, preview: PreviewState, actionId: String,
        targetStepId: String?, endLabel: String?) = launchPreviewWork {
        val target = snapshot.steps.firstOrNull { it.id == targetStepId }
        val bitmap = if (target != null) decodeBitmap(snapshot, target) else state.value.bitmap
        if (bitmap == null) {
            preview.pendingTransition?.let { failPreviewTransition(it.video.runId) }
            return@launchPreviewWork
        }
        coroutineContext.ensureActive()
        mutableState.update { it.copy(selectedStepId = target?.id ?: preview.currentStepId, bitmap = bitmap,
            preview = preview.copy(currentStepId = target?.id ?: preview.currentStepId,
                history = if (target == null) preview.history else preview.history + target.id,
                ended = target?.isTerminal ?: true,
                endLabel = if (target == null) endLabel else target.title.takeIf { target.isTerminal },
                endActionId = if (target == null) actionId else null, matchingHotspotIds = emptyList(),
                pendingTransition = null, visitedActionIds = preview.visitedActionIds + actionId)) }
    }

    private fun launchPreviewWork(block: suspend () -> Unit) {
        previewTask?.cancel()
        val generation = ++previewGeneration
        mutableState.update { it.copy(busy = true, stage = "读取安全画面") }
        previewTask = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("播放未完成，请重试或静态前进") }
            finally {
                if (generation == previewGeneration) mutableState.update { it.copy(busy = false, stage = null) }
            }
        }
    }

    private fun cancelPreviewWork() {
        previewGeneration++
        val active = previewTask?.isActive == true
        previewTask?.cancel()
        previewTask = null
        if (active) mutableState.update { it.copy(busy = false, stage = null) }
    }

    fun previousPreview() {
        if (state.value.preview?.pendingTransition != null) {
            cancelPreviewWork()
            mutableState.update { it.copy(preview = it.preview?.copy(pendingTransition = null, matchingHotspotIds = emptyList())) }
            return
        }
        if (state.value.busy) return
        val snapshot = validPreview() ?: return
        val preview = state.value.preview ?: return
        if (preview.endActionId != null) {
            mutableState.update { it.copy(preview = preview.copy(ended = false, endLabel = null,
                endActionId = null, matchingHotspotIds = emptyList())) }
            return
        }
        if (preview.history.size <= 1) return
        val history = preview.history.dropLast(1)
        val previous = snapshot.steps.firstOrNull { it.id == history.last() } ?: return
        execute("返回实际访问的上一步") {
            mutableState.update { it.copy(bitmap = null) }
            val bitmap = decodeBitmap(snapshot, previous) ?: return@execute
            mutableState.update { it.copy(selectedStepId = previous.id, bitmap = bitmap,
                preview = preview.copy(currentStepId = previous.id, history = history, ended = previous.isTerminal,
                    endLabel = previous.title.takeIf { _ -> previous.isTerminal }, endActionId = null, matchingHotspotIds = emptyList())) }
        }
    }

    fun restartPreview() {
        if (state.value.preview?.pendingTransition != null) {
            cancelPreviewWork()
            mutableState.update { it.copy(preview = it.preview?.copy(pendingTransition = null)) }
        }
        if (state.value.busy) return
        val snapshot = validPreview() ?: return
        val old = state.value.preview ?: return
        val start = snapshot.steps.firstOrNull { it.id == old.history.firstOrNull() } ?: return
        execute("重新开始预览") {
            mutableState.update { it.copy(bitmap = null) }
            val bitmap = decodeBitmap(snapshot, start) ?: return@execute
            mutableState.update { it.copy(selectedStepId = start.id, bitmap = bitmap,
                preview = PreviewState(snapshot.project.id, snapshot.project.revision, start.id, listOf(start.id),
                    ended = start.isTerminal, endLabel = start.title.takeIf { _ -> start.isTerminal })) }
        }
    }

    fun exitPreview() {
        if (state.value.preview?.pendingTransition != null) cancelPreviewWork()
        if (state.value.busy) return
        val id = state.value.preview?.currentStepId ?: state.value.selectedStepId
        invalidatePreview()
        if (id == null) back() else openStep(id)
    }

    private fun validPreview(): ProjectSnapshot? {
        val current = state.value
        val snapshot = previewSnapshot
        if (snapshot == null || current.preview?.projectId != snapshot.project.id ||
            current.project?.project?.id != snapshot.project.id || current.preview?.revision != snapshot.project.revision ||
            current.project?.project?.revision != snapshot.project.revision || previewEditRevision != current.editRevision) {
            invalidatePreview()
            message("项目已修改，请重新开始预览")
            return null
        }
        return snapshot
    }

    private fun invalidatePreview(edited: Boolean = false) {
        cancelPreviewWork()
        previewSnapshot = null
        previewEditRevision = -1
        mutableState.update { it.copy(preview = null,
            route = if (it.route == ProjectRoute.PREVIEW) ProjectRoute.STEPS else it.route,
            bitmap = if (it.route == ProjectRoute.PREVIEW) null else it.bitmap,
            editRevision = it.editRevision + if (edited) 1 else 0) }
    }

    fun clearMessage() { mutableState.update { it.copy(message = null) } }
    fun message(value: String) { mutableState.update { it.copy(message = value) } }
    fun cancel() { task?.cancel() }

    private fun execute(label: String, editing: Boolean = false, afterRefresh: (suspend () -> Unit)? = null, block: suspend () -> Unit) {
        if (state.value.busy || (editing && state.value.loadFailed)) return
        mutableState.update { it.copy(busy = true, stage = label, message = null) }
        task = viewModelScope.launch {
            var locked = false
            try {
                operationLock.lock()
                locked = true
                if (editing) invalidatePreview(edited = true)
                block()
            } catch (cancelled: CancellationException) {
                message("已取消；正在核对本地保存结果，未保存的文字和动作会保留")
                throw cancelled
            } catch (failure: Exception) {
                message(when (failure) {
                    is IllegalArgumentException -> failure.message ?: "$label 未完成，请检查输入后重试"
                    else -> "$label 未完成，请检查本机存储后重试。未保存的修改仍保留"
                })
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (locked) {
                            refresh()
                        }
                    } catch (_: Exception) {
                        mutableState.update { it.copy(loadFailed = true, bitmap = null,
                            message = "无法读取本地项目，原记录与未保存修改均已保留。请重试读取") }
                    } finally {
                        try { if (locked) afterRefresh?.invoke() }
                        finally {
                            if (locked) operationLock.unlock()
                            mutableState.update { it.copy(busy = false, stage = null) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun refresh() {
        val selectedId = state.value.project?.project?.id
        val loaded = withContext(Dispatchers.IO) {
            val summaries = store.listProjects()
            LoadedProjects(summaries, selectedId?.let { store.readProject(it) },
                WorkspaceStore.retainedWorkspaces(getApplication<Application>(), summaries.map { it.id }.toSet()))
        }
        mutableState.update { it.copy(projects = loaded.summaries,
            retainedMediaWorkspaces = loaded.retainedMediaWorkspaces, loadFailed = false) }
        if (loaded.selected != null) { restoreDrafts(loaded.selected); applyProject(loaded.selected) }
        else if (selectedId != null) {
            clearEditorUndo()
            drafts.keys.removeAll { it.first == selectedId }
            invalidatePreview()
            mutableState.update { it.copy(project = null, route = ProjectRoute.PROJECTS,
                selectedStepId = null, stepDraft = null, bitmap = null, issues = emptyList(), dirtyStepIds = emptySet()) }
        }
    }

    private fun applyProject(project: ProjectSnapshot) {
        editorUndo?.let { if (it.projectId != project.project.id || it.revision != project.project.revision ||
            project.steps.none { step -> step.id == it.stepId }) clearEditorUndo() }
        val current = state.value
        if (previewSnapshot?.project?.id == project.project.id && previewSnapshot?.project?.revision != project.project.revision) {
            invalidatePreview()
            message("项目修订已变化，旧预览已关闭")
        }
        val stepsById = project.steps.associateBy { it.id }
        reconcileDrafts(project)
        val selectedId = current.selectedStepId?.takeIf { it in stepsById }
        val selectedDraft = selectedId?.let { draftFor(project, stepsById.getValue(it)) }
        mutableState.update { old -> old.copy(project = project, selectedStepId = selectedId,
            route = if (old.route == ProjectRoute.EDIT && selectedId == null) ProjectRoute.STEPS else old.route,
            stepDraft = selectedDraft, issues = graphIssues(project), dirtyStepIds = dirtyIds(project.project.id),
            bitmap = if (current.project?.project?.id != project.project.id || current.selectedStepId != selectedId) null else old.bitmap) }
    }

    private fun dirtyIds(projectId: String): Set<String> =
        drafts.filter { it.key.first == projectId && it.value.draft.dirty }.keys.map { it.second }.toSet()

    private fun draftFor(project: ProjectSnapshot, step: ProjectStep): StepEditDraft =
        drafts.getOrPut(project.project.id to step.id) { DraftRecord(step.toDraft(), step, project.project.revision) }.draft

    private fun ProjectStep.toDraft() = StepEditDraft(id, title, description, isTerminal, hotspots, nextAction = nextAction)
    private fun sameEdits(draft: StepEditDraft, step: ProjectStep): Boolean = draft.fields() == step.editorFields()

    private fun sameSavedEdits(draft: StepEditDraft, step: ProjectStep): Boolean = sameEdits(draft.copy(
        title = draft.title.trim(), description = draft.description.trim(), hotspots = draft.hotspots.map {
            it.copy(label = it.label.trim(), endLabel = it.endLabel?.trim())
        }, nextAction = draft.nextAction?.let { it.copy(label = it.label.trim()) }), step)

    private fun pruneDeletedTargets(projectId: String, @Suppress("UNUSED_PARAMETER") deletedStepId: String) {
        state.value.project?.takeIf { it.project.id == projectId }?.let { project ->
            reconcileDrafts(project)
            publishDraftState(project)
        }
    }

    private fun StepEditDraft.fields() = EditorDraftFields(title, description, isTerminal,
        hotspots.map { it.copy(transition = null) }.sortedBy { it.id }, nextAction?.copy(transition = null))

    private fun ProjectStep.withFields(fields: EditorDraftFields): ProjectStep = copy(title = fields.title,
        description = fields.description, isTerminal = fields.isTerminal,
        hotspots = fields.withCurrentMedia(this).hotspots, nextAction = fields.withCurrentMedia(this).nextAction)

    private fun EditorDraftFields.toDraft(step: ProjectStep, pending: EditorPendingForm? = null,
        conflicts: Set<String> = emptySet()): StepEditDraft {
        val bound = withCurrentMedia(step)
        return StepEditDraft(step.id, title, description, isTerminal, bound.hotspots,
            dirty = pending != null || conflicts.isNotEmpty() || this != step.editorFields(),
            nextAction = bound.nextAction, pendingForm = pending, conflicts = conflicts)
    }

    private fun publishDraftState(project: ProjectSnapshot) {
        mutableState.update { current -> current.copy(dirtyStepIds = dirtyIds(project.project.id),
            stepDraft = current.selectedStepId?.let { drafts[project.project.id to it]?.draft }) }
    }

    /** The generation is read and changed only on Main; writes and destructive boundaries share a mutex. */
    private fun invalidateStaging(key: Pair<String, String>) {
        draftGenerations[key] = (draftGenerations[key] ?: 0L) + 1
        pendingStages.remove(key)
    }

    private suspend fun sessionFor(projectId: String): Long = draftSessions[projectId] ?: withContext(Dispatchers.IO) {
        store.beginEditorDraftSession(projectId)
    }.also { draftSessions[projectId] = it }

    private fun queueStage(key: Pair<String, String>) {
        val record = drafts[key] ?: return
        invalidateStaging(key)
        pendingStages[key] = draftGenerations.getValue(key)
        drafts[key] = record.copy(draft = record.draft.copy(recoveryStatus = DraftRecoveryStatus.STAGING,
            dirty = true))
        state.value.project?.takeIf { it.project.id == key.first }?.let(::publishDraftState)
        if (stagingTask?.isActive == true) return
        stagingTask = viewModelScope.launch {
            while (pendingStages.isNotEmpty()) {
                val (nextKey, generation) = pendingStages.entries.first().let { it.key to it.value }
                pendingStages.remove(nextKey)
                var staged: StoredEditorDraft? = null
                try {
                    val accepted = draftWriteLock.withLock {
                        if (draftGenerations[nextKey] != generation) return@withLock false
                        val latest = drafts[nextKey] ?: return@withLock false
                        staged = latest.toStoredDraft()
                        val session = sessionFor(nextKey.first)
                        withContext(Dispatchers.IO) { store.writeEditorDraft(nextKey.first, nextKey.second, session, staged) }
                    }
                    if (draftGenerations[nextKey] == generation && drafts[nextKey] != null) {
                        check(accepted) { "编辑暂存会话已改变，请重新读取项目" }
                        val latest = drafts.getValue(nextKey)
                        drafts[nextKey] = latest.copy(draft = latest.draft.copy(dirty = staged != null,
                            recoveryStatus = if (staged == null) DraftRecoveryStatus.NONE else DraftRecoveryStatus.STAGED))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (draftGenerations[nextKey] == generation) drafts[nextKey]?.let { latest ->
                        drafts[nextKey] = latest.copy(draft = latest.draft.copy(dirty = true,
                            recoveryStatus = DraftRecoveryStatus.FAILED))
                    }
                }
                state.value.project?.takeIf { it.project.id == nextKey.first }?.let(::publishDraftState)
            }
        }
    }

    private fun DraftRecord.toStoredDraft(): StoredEditorDraft? = takeIf {
        it.draft.pendingForm != null || it.draft.conflicts.isNotEmpty() || !sameEdits(it.draft, it.baseStep)
    }?.let { StoredEditorDraft(it.baseRevision, it.baseStep.editorFields(), it.draft.fields(), it.draft.pendingForm) }

    /** All rows are loaded before applyProject computes any dirty gates. */
    private suspend fun restoreDrafts(project: ProjectSnapshot) {
        if (project.project.id in loadedDraftProjects) return
        val restored = draftWriteLock.withLock {
            sessionFor(project.project.id)
            withContext(Dispatchers.IO) { store.readEditorDrafts(project.project.id) }
        }
        restored.forEach { (stepId, saved) ->
            val step = project.steps.firstOrNull { it.id == stepId } ?: return@forEach
            val base = step.withFields(saved.base)
            drafts.putIfAbsent(project.project.id to stepId, DraftRecord(saved.edit.toDraft(step, saved.pendingForm)
                .copy(recoveryStatus = DraftRecoveryStatus.STAGED), base, saved.baseRevision))
        }
        loadedDraftProjects += project.project.id
        if (restored.isNotEmpty()) message("已恢复上次未保存的编辑，可以继续修改")
    }

    private fun reconcileDrafts(project: ProjectSnapshot) {
        val steps = project.steps.associateBy { it.id }
        drafts.keys.filter { it.first == project.project.id }.forEach { key ->
            val record = drafts[key] ?: return@forEach
            val step = steps[key.second]
            if (step == null) {
                invalidateStaging(key)
                drafts.remove(key)
                return@forEach
            }
            val submitted = savingDrafts[key]
            if (submitted != null && project.project.revision != record.baseRevision && sameSavedEdits(submitted, step)) {
                invalidateStaging(key)
                drafts[key] = DraftRecord(step.toDraft(), step, project.project.revision)
                return@forEach
            }
            val base = EditorDraftReconciliation.prune(record.baseStep.editorFields(), steps.keys)
            val edit = EditorDraftReconciliation.prune(record.draft.fields(), steps.keys)
            val saved = step.editorFields()
            val merged = EditorDraftReconciliation.merge(base, edit, saved)
            val pending = EditorDraftReconciliation.prune(record.draft.pendingForm, steps.keys)
            val pendingMerged = EditorDraftReconciliation.mergePending(pending, base, saved)
            val conflicts = merged.conflicts + pendingMerged.second
            val nextBase = if (conflicts.isEmpty()) step else step.withFields(
                EditorDraftReconciliation.advanceBase(base, edit, saved, pending))
            val next = DraftRecord(merged.fields.toDraft(step, pendingMerged.first, conflicts)
                .copy(recoveryStatus = record.draft.recoveryStatus), nextBase,
                if (conflicts.isEmpty()) project.project.revision else record.baseRevision)
            drafts[key] = next
            if (next != record && (record.draft.dirty || next.draft.dirty)) queueStage(key)
        }
    }

    private suspend fun loadBitmap(project: ProjectSnapshot, step: ProjectStep) {
        mutableState.update { it.copy(bitmap = null) }
        val bitmap = decodeBitmap(project, step)
        mutableState.update { it.copy(bitmap = bitmap) }
    }

    /** Bounded thumbnail decoding shares this workspace's repository, never opens a raw source. */
    suspend fun loadReviewedThumbnail(projectId: String, stepId: String, asset: StepAsset): Bitmap? = withContext(Dispatchers.IO) {
        try {
            require(asset.byteLength in 1..MAX_PNG_BYTES && asset.width > 0 && asset.height > 0 &&
                asset.width.toLong() * asset.height <= MAX_PNG_PIXELS)
            val file = store.resolveAsset(projectId, stepId)
            require(file.length() == asset.byteLength)
            file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= asset.byteLength)
                    digest.update(buffer, 0, read)
                }
                require(count == asset.byteLength && digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256)
                input.channel.position(0)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, bounds)
                require(bounds.outMimeType == "image/png" && bounds.outWidth == asset.width && bounds.outHeight == asset.height)
                var sample = 1
                while (asset.width.toLong() / sample * (asset.height / sample) > 220_000) sample *= 2
                input.channel.position(0)
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private suspend fun decodeBitmap(project: ProjectSnapshot, step: ProjectStep): Bitmap? {
        try {
            return withContext(Dispatchers.IO) {
                val asset = step.asset
                require(asset.byteLength in 1..MAX_PNG_BYTES && asset.width > 0 && asset.height > 0 &&
                    asset.width.toLong() * asset.height <= MAX_PNG_PIXELS) { "安全图片尺寸超限" }
                val file = store.resolveAsset(project.project.id, step.id)
                val bytes = file.inputStream().use { input ->
                    val result = ByteArray(asset.byteLength.toInt())
                    var offset = 0
                    while (offset < result.size) {
                        coroutineContext.ensureActive()
                        val count = input.read(result, offset, minOf(64 * 1024, result.size - offset))
                        require(count > 0) { "安全图片不完整" }
                        offset += count
                    }
                    require(input.read() == -1) { "安全图片字节数已变化" }
                    result
                }
                require(bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) {
                    "安全图片格式已变化"
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(digest == asset.sha256) { "安全图片校验不一致" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outMimeType == "image/png" && bounds.outWidth == asset.width && bounds.outHeight == asset.height) {
                    "安全图片尺寸校验失败"
                }
                // Keep a large, valid reviewed canvas within a bounded display allocation.
                var sampleSize = 1
                while ((asset.width.toLong() / sampleSize) * (asset.height / sampleSize) > MAX_DISPLAY_PIXELS) sampleSize *= 2
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inScaled = false
                    inSampleSize = sampleSize
                }) ?: error("安全图片无法解码")
                require(decoded.width > 0 && decoded.height > 0 &&
                    decoded.width.toLong() * decoded.height <= MAX_DISPLAY_PIXELS + asset.width + asset.height) {
                    "安全图片解码尺寸不一致"
                }
                decoded
            }
        } catch (cancelled: CancellationException) {
            clearFailedBitmap()
            throw cancelled
        } catch (_: Exception) {
            clearFailedBitmap()
            return null
        }
    }

    private fun clearFailedBitmap() {
        mutableState.update { it.copy(bitmap = null,
            message = "安全画面缺失或校验失败，请重新生成并复核后添加步骤") }
    }

    companion object {
        const val MAX_PREVIEW_VISITS = 256
        private const val MAX_PNG_BYTES = 50L * 1024 * 1024
        private const val MAX_PNG_PIXELS = 12_000_000L
        private const val MAX_DISPLAY_PIXELS = 1080L * 2400
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        fun graphIssues(snapshot: ProjectSnapshot): List<ProjectIssue> {
            if (snapshot.steps.isEmpty()) return listOf(ProjectIssue(null, "项目还没有步骤，请添加已生成并复核的图片"))
            val byId = snapshot.steps.associateBy { it.id }
            val result = mutableListOf<ProjectIssue>()
            val start = snapshot.project.startStepId
            if (start == null || start !in byId) result += ProjectIssue(null, "尚未设置有效起点")
            val reached = mutableSetOf<String>()
            val queue = ArrayDeque<String>()
            if (start != null && start in byId) queue.addLast(start)
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!reached.add(id)) continue
                val step = byId[id] ?: continue
                (step.hotspots.mapNotNull { it.targetStepId } + listOfNotNull(step.nextAction?.targetStepId))
                    .filter { it in byId && it !in reached }.forEach(queue::addLast)
            }
            snapshot.steps.forEach { step ->
                if (start != null && start in byId && step.id !in reached) result += ProjectIssue(step.id, "无法从起点到达“${step.title}”，请连接动作或调整起点")
                if (!step.isTerminal && step.hotspots.isEmpty() && step.nextAction == null) {
                    result += ProjectIssue(step.id, "“${step.title}”尚无动作，请添加下一步、热点或设为终点")
                }
                if (step.isTerminal && (step.hotspots.isNotEmpty() || step.nextAction != null)) {
                    result += ProjectIssue(step.id, "终点“${step.title}”仍有动作，请移除动作或取消终点标记")
                }
                step.nextAction?.let { action ->
                    if (action.targetStepId == null || action.targetStepId !in byId) {
                        result += ProjectIssue(step.id, "下一步“${action.label}”缺少目标，请重新选择；缺目标不代表结束")
                    }
                }
                step.hotspots.filter { it.targetStepId != null && it.targetStepId !in byId }.forEach {
                    result += ProjectIssue(step.id, "热点“${it.label}”缺少目标，请重新选择目标或结束")
                }
            }
            return result
        }
    }
}
