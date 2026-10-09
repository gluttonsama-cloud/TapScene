package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.ProjectLimits
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
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class ProjectRoute { PROJECTS, STEPS, EDIT, MEDIA, PREVIEW }

data class StepEditDraft(
    val stepId: String,
    val title: String,
    val description: String,
    val isTerminal: Boolean,
    val hotspots: List<ProjectHotspot>,
    val dirty: Boolean = false,
)

data class ProjectIssue(val stepId: String?, val message: String)

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
) {
    val canGoBack: Boolean get() = history.size > 1 || endActionId != null
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
    private val drafts = mutableMapOf<Pair<String, String>, DraftRecord>()

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

    /** Creation is explicit; importing/cancelling media never implicitly creates another project. */
    fun createProject(title: String, goal: String = "") = execute("创建项目", editing = true) {
        val project = withContext(Dispatchers.IO) { store.createProject(title, goal) }
        applyProject(project)
        mutableState.update { it.copy(route = ProjectRoute.STEPS, selectedStepId = null, stepDraft = null, bitmap = null) }
    }

    fun openProject(id: String) = execute("打开项目") {
        val project = withContext(Dispatchers.IO) { store.readProject(id) }
            ?: error("项目已不存在，请刷新项目列表")
        invalidatePreview()
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

    /** The caller presents the deletion impact and gets explicit confirmation before this event. */
    fun deleteProject(id: String) = execute("删除本地项目", editing = true) {
        val result = withContext(Dispatchers.IO) { store.deleteProject(id) }
        drafts.keys.removeAll { it.first == id }
        if (state.value.project?.project?.id == id) {
            mutableState.update { it.copy(project = null, route = ProjectRoute.PROJECTS,
                selectedStepId = null, stepDraft = null, bitmap = null, preview = null, issues = emptyList(), dirtyStepIds = emptySet()) }
        }
        message(if (result.pendingAssetCleanupCount > 0) "项目已删除，部分私有图片将在下次打开时继续清理" else "本地项目已删除")
    }

    fun openStep(id: String) = execute("读取步骤画面") {
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
        invalidatePreview()
        mutableState.update { it.copy(route = ProjectRoute.MEDIA, bitmap = null) }
    }

    fun back() {
        if (state.value.busy) return
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

    fun editTitle(value: String) = editDraft { it.copy(title = value) }
    fun editDescription(value: String) = editDraft { it.copy(description = value) }

    fun editTerminal(value: Boolean) {
        if (value && state.value.stepDraft?.hotspots?.isNotEmpty() == true) {
            message("请先移除本步骤的热点，再设为终点")
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

    private fun editDraft(change: (StepEditDraft) -> StepEditDraft) {
        val current = state.value
        if (current.busy || current.loadFailed) return
        val project = current.project ?: return
        val old = current.stepDraft ?: return
        val key = project.project.id to old.stepId
        val record = drafts[key] ?: return
        val changed = change(old)
        if (changed == old) return
        val next = changed.copy(dirty = !sameEdits(changed, record.baseStep))
        drafts[key] = record.copy(draft = next)
        invalidatePreview(edited = true)
        mutableState.update { it.copy(stepDraft = next, dirtyStepIds = dirtyIds(project.project.id), message = null) }
    }

    fun saveStepDraft() {
        val project = state.value.project ?: return
        val draft = state.value.stepDraft ?: return
        if (!draft.dirty) return
        val record = drafts[project.project.id to draft.stepId] ?: return
        if (record.baseRevision != project.project.revision) {
            message("此步骤已有其他已保存修改，你的草稿仍保留。请先记录要保留的内容，再放弃草稿查看新版本")
            return
        }
        execute("保存步骤", editing = true) {
            val saved = withContext(Dispatchers.IO) {
                store.saveStepDraft(project.project.id, draft.stepId, draft.title, draft.description,
                    draft.isTerminal, draft.hotspots, expectedRevision = record.baseRevision)
            }
            saved.steps.firstOrNull { it.id == draft.stepId }?.let { step ->
                drafts[project.project.id to step.id] = DraftRecord(step.toDraft(), step, saved.project.revision)
            }
            applyProject(saved)
            message("步骤已保存")
        }
    }

    fun discardStepDraft() {
        if (state.value.busy) return
        val project = state.value.project ?: return
        val step = state.value.selectedStep ?: return
        drafts.remove(project.project.id to step.id)
        invalidatePreview(edited = true)
        mutableState.update { it.copy(stepDraft = draftFor(project, step), dirtyStepIds = dirtyIds(project.project.id), message = null) }
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

    fun deletionHotspotCount(stepId: String): Int {
        val project = state.value.project ?: return 0
        return project.steps.flatMap { step ->
            val retained = drafts[project.project.id to step.id]?.draft?.hotspots.orEmpty()
            (step.hotspots + retained).filter { step.id == stepId || it.targetStepId == stepId }
        }.map { it.id }.toSet().size
    }

    /** Incoming and outgoing hotspots are removed atomically by the store, after UI confirmation. */
    fun deleteStep(stepId: String) {
        val project = state.value.project ?: return
        execute("删除步骤与相关热点", editing = true, afterRefresh = {
            // Even a cancelled dispatcher return can hide a successful deletion transaction.
            if (state.value.project?.project?.id == project.project.id && state.value.project?.steps?.none { it.id == stepId } == true) {
                drafts.remove(project.project.id to stepId)
                pruneDeletedTargets(project.project.id, stepId)
            }
        }) {
            val result = withContext(Dispatchers.IO) { store.deleteStep(project.project.id, stepId) }
            drafts.remove(project.project.id to stepId)
            applyProject(result.snapshot)
            // Never resurrect a just-deleted target from another step's retained dirty draft.
            pruneDeletedTargets(project.project.id, stepId)
            if (state.value.selectedStepId == stepId || state.value.route == ProjectRoute.EDIT) {
                mutableState.update { it.copy(route = ProjectRoute.STEPS, selectedStepId = null, stepDraft = null, bitmap = null) }
            }
            message("步骤已删除，已移除 ${result.impact.hotspotCount} 个相关热点" +
                if (result.pendingAssetCleanupCount > 0) "；私有图片稍后继续清理" else "")
        }
    }

    /**
     * Called directly inside MediaWorkspace's operation lock. This must remain a suspending commit,
     * never a viewModelScope launch: the caller owns and may clean up the reviewed candidate.
     */
    suspend fun saveReviewedStep(projectId: String, input: ReviewedStepInput, openEditor: Boolean = true): String = withContext(Dispatchers.Main.immediate) {
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
            val title = "步骤 ${(state.value.project?.steps?.size ?: 0) + 1}"
            val saved = withContext(Dispatchers.IO) {
                store.addReviewedStep(projectId, input, title, stepId = stepId)
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
        if (preview.ended) return
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
        if (preview.ended) return
        val step = snapshot.steps.firstOrNull { it.id == preview.currentStepId } ?: return
        val hotspot = step.hotspots.firstOrNull { it.id == id } ?: return
        if (hotspot.endLabel != null && hotspot.targetStepId == null) {
            mutableState.update { it.copy(preview = preview.copy(ended = true, endLabel = hotspot.endLabel,
                endActionId = hotspot.id, matchingHotspotIds = emptyList(), visitedActionIds = preview.visitedActionIds + id)) }
            return
        }
        val target = snapshot.steps.firstOrNull { it.id == hotspot.targetStepId }
        if (target == null) {
            message("这个热点的目标已缺失，请回编辑修正")
            return
        }
        if (preview.history.size >= MAX_PREVIEW_VISITS) {
            dismissPreviewChoices()
            message("已试走 256 次，请点“重来”开启新一轮预览")
            return
        }
        execute("读取目标安全画面") {
            mutableState.update { it.copy(bitmap = null, preview = preview.copy(matchingHotspotIds = emptyList())) }
            val bitmap = decodeBitmap(snapshot, target) ?: return@execute
            // The visit and its edge become real only after the target's exact saved bytes decode.
            mutableState.update { it.copy(selectedStepId = target.id, bitmap = bitmap,
                preview = preview.copy(currentStepId = target.id, history = preview.history + target.id,
                    ended = target.isTerminal, endLabel = target.title.takeIf { _ -> target.isTerminal },
                    endActionId = null, matchingHotspotIds = emptyList(), visitedActionIds = preview.visitedActionIds + id)) }
        }
    }

    fun previousPreview() {
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
        if (state.value.busy) return
        val id = state.value.preview?.currentStepId ?: state.value.selectedStepId
        invalidatePreview()
        if (id == null) back() else openStep(id)
    }

    private fun validPreview(): ProjectSnapshot? {
        val current = state.value
        val snapshot = previewSnapshot
        if (snapshot == null || current.preview?.revision != snapshot.project.revision ||
            current.project?.project?.revision != snapshot.project.revision || previewEditRevision != current.editRevision) {
            invalidatePreview()
            message("项目已修改，请重新开始预览")
            return null
        }
        return snapshot
    }

    private fun invalidatePreview(edited: Boolean = false) {
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

    private fun execute(label: String, editing: Boolean = false, afterRefresh: (() -> Unit)? = null, block: suspend () -> Unit) {
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
                message("已取消；正在核对本地保存结果，未保存的文字和热点会保留")
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
                            afterRefresh?.invoke()
                        }
                    } catch (_: Exception) {
                        mutableState.update { it.copy(loadFailed = true, bitmap = null,
                            message = "无法读取本地项目，原记录与未保存修改均已保留。请重试读取") }
                    } finally {
                        if (locked) operationLock.unlock()
                        mutableState.update { it.copy(busy = false, stage = null) }
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
        if (loaded.selected != null) applyProject(loaded.selected)
        else if (selectedId != null) {
            drafts.keys.removeAll { it.first == selectedId }
            invalidatePreview()
            mutableState.update { it.copy(project = null, route = ProjectRoute.PROJECTS,
                selectedStepId = null, stepDraft = null, bitmap = null, issues = emptyList(), dirtyStepIds = emptySet()) }
        }
    }

    private fun applyProject(project: ProjectSnapshot) {
        val current = state.value
        if (previewSnapshot?.project?.id == project.project.id && previewSnapshot?.project?.revision != project.project.revision) {
            invalidatePreview()
            message("项目修订已变化，旧预览已关闭")
        }
        val stepsById = project.steps.associateBy { it.id }
        drafts.keys.filter { it.first == project.project.id }.forEach { key ->
            val step = stepsById[key.second]
            val existing = drafts[key] ?: return@forEach
            if (step == null) drafts.remove(key)
            else if (!existing.draft.dirty || sameSavedEdits(existing.draft, step)) {
                drafts[key] = DraftRecord(step.toDraft(), step, project.project.revision)
            } else if (sameEdits(existing.baseStep.toDraft(), step)) {
                // An unrelated reorder/add/rename may advance revision; it did not change these edits.
                drafts[key] = existing.copy(baseStep = step, baseRevision = project.project.revision)
            }
        }
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

    private fun ProjectStep.toDraft() = StepEditDraft(id, title, description, isTerminal, hotspots)
    private fun sameEdits(draft: StepEditDraft, step: ProjectStep): Boolean =
        draft.title == step.title && draft.description == step.description && draft.isTerminal == step.isTerminal &&
            draft.hotspots.sortedBy { it.id } == step.hotspots.sortedBy { it.id }

    private fun sameSavedEdits(draft: StepEditDraft, step: ProjectStep): Boolean = sameEdits(draft.copy(
        title = draft.title.trim(), description = draft.description.trim(), hotspots = draft.hotspots.map {
            it.copy(label = it.label.trim(), endLabel = it.endLabel?.trim())
        }), step)

    private fun pruneDeletedTargets(projectId: String, deletedStepId: String) {
        // Preserve unrelated typed work and explicitly rerouted drafts while removing the deleted
        // target. Rebase only when that deletion fully explains the persisted step's differences.
        drafts.keys.filter { it.first == projectId }.forEach { key ->
            val record = drafts[key] ?: return@forEach
            val actual = state.value.project?.steps?.firstOrNull { it.id == key.second } ?: return@forEach
            val filtered = record.draft.hotspots.filterNot { it.targetStepId == deletedStepId }
            val baseAfterDelete = record.baseStep.toDraft().copy(
                hotspots = record.baseStep.hotspots.filterNot { it.targetStepId == deletedStepId })
            if (sameEdits(baseAfterDelete, actual)) {
                val draft = record.draft.copy(hotspots = filtered)
                drafts[key] = DraftRecord(draft.copy(dirty = !sameEdits(draft, actual)), actual,
                    state.value.project!!.project.revision)
            }
        }
        state.value.project?.let { project ->
            mutableState.update { it.copy(dirtyStepIds = dirtyIds(projectId),
                stepDraft = it.selectedStep?.let { step -> draftFor(project, step) }) }
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
                byId[id]?.hotspots?.mapNotNull { it.targetStepId }?.filter { it in byId && it !in reached }?.forEach(queue::addLast)
            }
            snapshot.steps.forEach { step ->
                if (start != null && start in byId && step.id !in reached) result += ProjectIssue(step.id, "无法从起点到达“${step.title}”，请连接热点或调整起点")
                if (!step.isTerminal && step.hotspots.isEmpty()) result += ProjectIssue(step.id, "“${step.title}”尚无动作，请添加热点或设为终点")
                step.hotspots.filter { it.targetStepId != null && it.targetStepId !in byId }.forEach {
                    result += ProjectIssue(step.id, "热点“${it.label}”缺少目标，请重新选择目标或结束")
                }
            }
            return result
        }
    }
}
