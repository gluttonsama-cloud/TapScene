package com.tapscene.ui.shell

import android.graphics.Bitmap
import com.tapscene.BuildConfig
import com.tapscene.sharing.offlineShareChooser
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tapscene.data.*
import com.tapscene.ui.*
import com.tapscene.media.CandidateAnalysisStatus
import com.tapscene.recording.RecordingCoordinator
import com.tapscene.recording.RecordingPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The shell routes existing capabilities; unavailable services never manufacture project data. */
@Composable
fun TapSceneApp(projects: ProjectWorkspace, media: MediaWorkspace, candidates: CandidateWorkspace, releases: ReleaseWorkspace) {
    val hosting: HostingWorkspace = viewModel()
    val hostingState by hosting.state.collectAsStateWithLifecycle()
    val transitions: TransitionWorkspace = viewModel()
    val regions: RegionWorkspace = viewModel()
    val screenshots: ScreenshotWorkspace = viewModel()
    val aiDrafts: AiDraftWorkspace = viewModel()
    val aiImportState by aiDrafts.importState.collectAsStateWithLifecycle()
    val draftAiState by aiDrafts.planState.collectAsStateWithLifecycle()
    val screenshotState by screenshots.state.collectAsStateWithLifecycle()
    val state by projects.state.collectAsStateWithLifecycle()
    val mediaState by media.state.collectAsStateWithLifecycle()
    val candidateState by candidates.state.collectAsStateWithLifecycle()
    val releaseState by releases.state.collectAsStateWithLifecycle()
    val recording by RecordingCoordinator.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    var library by rememberSaveable { mutableStateOf(false) }
    val projectHomeState = rememberProjectHomeState()
    var tab by rememberSaveable { mutableStateOf(ProjectTab.STEPS) }
    val pages = rememberSaveable(saver = listSaver(
        save = { it.toList() }, restore = { it.toMutableStateList() },
    )) { mutableStateListOf<String>() }
    val page = pages.lastOrNull()
    // A task banner may temporarily cover AI configuration. Retain its unapplied input only
    // while that route remains on the stack, and never restore it into another editor owner.
    val aiRouteState = rememberSaveableStateHolder()
    val aiStateKey = "ai:${(releaseState.aiConfigurationOwner ?: releaseState.aiConfiguration?.scene?.releaseId).orEmpty()}"
    val aiInStack = "ai" in pages
    var retainedAiStateKey by rememberSaveable { mutableStateOf<String?>(null) }
    var aiPendingInput by rememberSaveable(aiStateKey) { mutableStateOf(false) }
    LaunchedEffect(aiInStack, aiStateKey) {
        if (!aiInStack || retainedAiStateKey != aiStateKey) retainedAiStateKey?.let(aiRouteState::removeState)
        retainedAiStateKey = if (aiInStack) aiStateKey else null
        if (!aiInStack) aiPendingInput = false
    }
    LaunchedEffect(aiInStack) { if (!aiInStack) releases.leaveAiPage() }
    val push: (String) -> Unit = { if (pages.lastOrNull() != it) pages.add(it) }
    val pop: () -> Unit = { if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) }
    // Temporary task/recording pages may cover AI. Refuse only navigation that would destroy
    // its edited route, never stop/cancel another owner or replay the navigation afterwards.
    val retainAiChanges: (String) -> Boolean = { note ->
        val current = releases.state.value
        val retain = "ai" in pages && (current.aiSaveNeedsCheck ||
            (current.aiConfiguration?.fromDraft == false && (current.aiConfigurationChanged || aiPendingInput)))
        if (retain) {
            while (pages.lastOrNull() != "ai") pages.removeAt(pages.lastIndex)
            releases.message(note)
        }
        retain
    }
    val aiNavigationNote = if (releaseState.aiSaveNeedsCheck)
        "请先核对 AI 配置保存结果，再回到项目；也可从 AI 页返回并明确确认离开。"
        else "请先处理 AI 配置修改，再回到项目；可检查并保存，或返回时明确放弃未保存修改。"
    val aiRecordingNote = if (releaseState.aiSaveNeedsCheck)
        "录屏已保存在本机。请先核对 AI 配置保存结果，再从所属项目的素材页整理录屏。"
        else "录屏已保存在本机。请先处理 AI 配置修改，再从所属项目的素材页整理录屏。"
    val openCreatedProject: (String) -> Unit = { id ->
        if (!retainAiChanges("已有结果保存在本机。$aiNavigationNote")) {
            pages.clear(); library = false; tab = ProjectTab.STEPS; projects.openProject(id)
        }
    }
    val hostingVisit = BuildConfig.HOSTED_ENABLED && page in setOf("hosted", "account", "versions")
    LaunchedEffect(page) {
        if (hostingVisit) hosting.open(when (page) {
            "account" -> HostingPage.ACCOUNT
            "versions" -> HostingPage.PROJECTS
            else -> HostingPage.SOURCE
        })
    }
    DisposableEffect(hostingVisit) {
        onDispose { if (hostingVisit) hosting.leave() }
    }
    val closeHosting: () -> Unit = { if (!hosting.back()) pop() }
    val hostingActions = HostingCallbacks(
        onBack = closeHosting, onSources = hosting::showSources, onRelease = hosting::selectRelease,
        onAccount = hosting::showAccount, onEmail = hosting::editEmail, onCode = hosting::editCode,
        onRequestCode = hosting::requestCode, onVerifyCode = hosting::verifyCode,
        onExpiry = hosting::setExpiry, onPublish = hosting::confirmPublication,
        onTasks = hosting::showTasks, onTask = hosting::selectTask, onResume = hosting::resumeTask,
        onStopObservation = hosting::stopObservation, onCancel = hosting::cancelTask,
        onProjects = hosting::showProjects, onMoreProjects = hosting::moreProjects,
        onProject = hosting::selectProject, onRefreshVersions = hosting::refreshVersions,
        onMoreVersions = hosting::moreVersions, onRevoke = hosting::revokePublication,
        onCheckRevocation = hosting::checkRevocation, onLogout = hosting::logout, onRetrySessions = hosting::retryPendingSessions,
        onOpenLink = { url ->
            if (isLocalHostedShareUrl(url)) try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: android.content.ActivityNotFoundException) { hosting.message("本机没有可用浏览器；可以复制开发链接。") }
        },
        onCopyLink = { url ->
            if (isLocalHostedShareUrl(url)) {
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("TapScene 本机开发链接", url))
                hosting.message("已复制本机开发链接；它不是公网分享地址。")
            }
        },
    )
    var newProject by rememberSaveable { mutableStateOf(false) }
    var importAfterCreate by rememberSaveable { mutableStateOf(false) }
    var awaitingCreatedProject by rememberSaveable { mutableStateOf(false) }
    var importRequested by rememberSaveable { mutableStateOf(false) }
    var pickerProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var screenshotPickerPending by rememberSaveable { mutableStateOf(false) }
    var aiPickerPending by rememberSaveable { mutableStateOf(false) }
    var aiPickerRequested by rememberSaveable { mutableStateOf(false) }
    var screenshotPickerProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deletingProject by remember { mutableStateOf<ProjectSummary?>(null) }
    var deletingStep by remember { mutableStateOf<Pair<ProjectSnapshot, String>?>(null) }
    var showUnsavedSteps by rememberSaveable { mutableStateOf(false) }
    var projectMore by rememberSaveable { mutableStateOf(false) }
    var transitionEdgeId by rememberSaveable { mutableStateOf<String?>(null) }
    var correctionStartsWithMask by rememberSaveable { mutableStateOf(false) }
    var retainedMedia by remember { mutableStateOf(false) }
    var pendingCandidateProject by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCandidateSource by rememberSaveable { mutableStateOf<String?>(null) }
    var autoAnalyzeSource by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewQueue by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var reviewedStepIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    // Only text explicitly adopted or typed by the author is retained here, never raw OCR.
    var reviewTitles by rememberSaveable { mutableStateOf(hashMapOf<String, String>()) }
    var pathStepIds by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var pathMarkLastTerminal by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var pendingPathProject by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPathIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pendingPathRevision by rememberSaveable { mutableStateOf(0L) }
    var pendingPathTerminal by rememberSaveable { mutableStateOf(false) }
    var reviewIndex by rememberSaveable { mutableStateOf(0) }
    var preparedCandidate by remember { mutableStateOf<String?>(null) }
    var reviewCandidateId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewSourceStepId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewSourceCandidateId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewSourceOpened by rememberSaveable { mutableStateOf(false) }
    var reviewSourceFromLibrary by rememberSaveable { mutableStateOf(false) }
    var pendingSealId by rememberSaveable { mutableStateOf<String?>(null) }
    var replacingCandidate by remember { mutableStateOf<ReleaseCandidate?>(null) }
    var deletingRelease by remember { mutableStateOf<ReleaseSummary?>(null) }
    var discardingCandidate by remember { mutableStateOf<ReleaseCandidate?>(null) }
    var exportPickerPending by rememberSaveable { mutableStateOf(false) }
    val projectId = state.project?.project?.id
    var connectionRepairStepId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var regionCorrectionStepId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var regionCorrectionId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var regionCorrectionPending by rememberSaveable(projectId) { mutableStateOf(false) }
    var draftAiPendingHold by remember(projectId) { mutableStateOf(false) }
    LaunchedEffect(page) { if (page != "draft-ai") draftAiPendingHold = false }
    val draftAiTaskBlocked = page == "draft-ai" && (draftAiState.busy || draftAiState.dirty ||
        draftAiState.saveOutcomeUnknown || draftAiPendingHold)
    val unavailable = state.busy || state.loadFailed || candidateState.busy || releaseState.busy || screenshotPickerPending || aiPickerPending || aiImportState.busy || draftAiState.busy
    val copyProject: (String) -> Unit = { id ->
        if (!unavailable && !mediaState.busy) {
            val blockedReason = when {
                draftAiState.project?.project?.id == id && (draftAiState.dirty || draftAiState.pendingPath != null || draftAiState.saveOutcomeUnknown) ->
                    "请先处理原项目未保存的动画计划，再复制"
                screenshotState.targetProjectId == id && screenshotState.sessionId != null && screenshotState.completed == null ->
                    "请先保存或放弃原项目正在编辑的截图，再复制"
                media.projectId == id && (mediaState.unsavedEdits || mediaState.correction != null) ->
                    "请先处理原项目未保存的画面修改，再复制"
                recording.projectId == id && (recording.isBusy || recording.canRetry) ->
                    "请先停止或处理原项目的未完成录制，再复制"
                else -> null
            }
            tab = ProjectTab.STEPS
            projects.copySavedProject(id, blockedReason)
        }
    }
    val openReleaseReview: () -> Unit = {
        if (!releaseState.busy) { reviewCandidateId = null; push("review") }
    }
    val openPackageImport: () -> Unit = {
        if (!releaseState.busy) { releases.clearMessage(); releases.clearImportResult(); push("import") }
    }
    val buildRelease: () -> Unit = {
        val snapshot = state.project
        if (snapshot != null && !unavailable && !mediaState.busy) {
            if (state.dirtyStepIds.isNotEmpty()) projects.message("先保存或放弃步骤修改，再生成固定成品。")
            else {
                val existing = releaseState.pendingCandidates.firstOrNull { it.projectId == snapshot.project.id }
                if (existing != null) replacingCandidate = existing
                else {
                    reviewCandidateId = null
                    releases.createCandidate(snapshot.project.id, snapshot.project.revision)
                    push("review")
                }
            }
        }
    }
    LaunchedEffect(page, projectId, reviewCandidateId) {
        if (page == "review" && reviewSourceStepId == null && !releaseState.busy) {
            if (reviewCandidateId != null && releaseState.candidate?.id != reviewCandidateId)
                releases.openReviewById(requireNotNull(reviewCandidateId))
            else if (reviewCandidateId == null && projectId != null && releaseState.candidate?.projectId != projectId)
                releases.openReview(requireNotNull(projectId))
        }
    }
    // Resolve the immutable candidate's own source, even when a different project is open.
    val reviewSourceInfo by produceState<Pair<ProjectSnapshot, Boolean>?>(null, releaseState.candidate?.id, state.project, state.projects, state.dirtyStepIds) {
        value = null
        val sourceId = releaseState.candidate?.projectId
        if (sourceId != null) value = withContext(Dispatchers.IO) {
            runCatching { ProjectStore.withTemporary(context) { store ->
                store.readProject(sourceId)?.let { it to store.readEditorDrafts(sourceId).isNotEmpty() }
            } }.getOrNull()
        }
    }
    val reviewSource = reviewSourceInfo?.first
    LaunchedEffect(page, releaseState.candidate?.id, reviewSourceStepId, reviewSourceCandidateId, state.busy,
        state.route, state.selectedStepId, projectId, mediaState.busy) {
        val stepId = reviewSourceStepId ?: return@LaunchedEffect
        val candidate = releaseState.candidate
        if (candidate == null || candidate.id != reviewSourceCandidateId) {
            reviewSourceStepId = null; reviewSourceCandidateId = null; reviewSourceOpened = false
        } else if (page == "review" && !state.busy) {
            if (state.loadFailed || projectId != candidate.projectId || state.project?.steps?.none { it.id == stepId } != false) {
                reviewSourceStepId = null; reviewSourceCandidateId = null; reviewSourceOpened = false
                releases.message("来源项目或步骤已不可用，固定候选仍保留。")
            } else if (state.route == ProjectRoute.EDIT && state.selectedStepId == stepId) {
                reviewSourceOpened = true
            } else if (reviewSourceOpened) {
                // leaveEditor has already applied its conflict/staging barrier. Keep this exact review selection.
                reviewSourceStepId = null; reviewSourceCandidateId = null; reviewSourceOpened = false
                if (stepId in state.dirtyStepIds) releases.message("本步修改已暂存。保存本步并重新生成后，才会进入新成品。")
            } else if (!mediaState.busy) {
                if (media.projectId == candidate.projectId) projects.openStep(stepId)
                else if (!media.activateProject(candidate.projectId)) {
                    reviewSourceStepId = null; reviewSourceCandidateId = null; reviewSourceOpened = false
                    releases.message("请先处理尚未保存的画面修改，再打开来源步骤。")
                }
            }
        }
    }
    LaunchedEffect(reviewSourceFromLibrary, reviewSourceStepId, state.busy, state.route) {
        if (reviewSourceFromLibrary && reviewSourceStepId == null && !state.busy) {
            reviewSourceFromLibrary = false
            if (state.route == ProjectRoute.STEPS) projects.back()
        }
    }
    LaunchedEffect(pendingSealId, releaseState.lastSealedId, releaseState.busy) {
        val expected = pendingSealId
        if (expected != null && !releaseState.busy) {
            pendingSealId = null
            if (releaseState.lastSealedId == expected && releaseState.candidate == null) {
                if (page == "review") { pop(); push("delivery") }
            }
        }
    }
    val aiPackagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = aiPickerPending
        aiPickerPending = false
        if (pending && uri != null && page == "ai-import") aiDrafts.importPackage(uri)
    }
    val chooseAiPackage: () -> Unit = {
        if (!aiImportState.busy && !aiImportState.outcomeUnknown && !aiPickerPending) {
            aiPickerPending = true
            try { aiPackagePicker.launch(arrayOf("*/*")) }
            catch (_: android.content.ActivityNotFoundException) {
                aiPickerPending = false
                aiDrafts.importMessage("系统没有可用的文件选择工具。")
            }
        }
    }
    val openAiImport: () -> Unit = {
        if (!unavailable && !mediaState.busy) {
            aiPickerRequested = aiImportState.sessionId == null
            push("ai-import")
            aiDrafts.openImport()
        }
    }
    // A restored route reopens the durable session. No package URI needs to survive process death.
    LaunchedEffect(page) {
        if (page == "ai-import" && !aiDrafts.importState.value.busy) aiDrafts.openImport()
    }
    LaunchedEffect(page, aiImportState.busy, aiPickerRequested) {
        if (page == "ai-import" && aiPickerRequested && !aiImportState.busy) {
            aiPickerRequested = false
            if (aiImportState.sessionId == null && aiImportState.pendingSessionIds.isEmpty()) chooseAiPackage()
        }
    }
    val openDraftAi: () -> Unit = {
        val id = projects.state.value.project?.project?.id
        if (id != null && !unavailable && !mediaState.busy) {
            if (projects.state.value.dirtyStepIds.isNotEmpty()) projects.message("先保存或放弃步骤修改，再编辑动画计划。")
            else { aiDrafts.openPlan(id); push("draft-ai") }
        }
    }
    LaunchedEffect(page, projectId) {
        if (page == "draft-ai" && projectId != null && draftAiState.project?.project?.id != projectId && !draftAiState.busy)
            aiDrafts.openPlan(requireNotNull(projectId))
    }
    val closeDraftAi: () -> Unit = {
        if (aiDrafts.closePlan()) { pop(); projects.reload() }
    }
    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) releases.importPackage(uri)
    }
    val packageSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        exportPickerPending = false
        if (uri != null) releases.saveExport(uri) else releases.cancelExportPicker()
    }
    LaunchedEffect(releaseState.exportFile, releaseState.busy, page) {
        val ready = releaseState.exportFile
        if (ready != null && !releaseState.busy && !exportPickerPending && releases.beginExportPicker(deliveryPageVisible = page == "delivery")) {
            exportPickerPending = true
            try { packageSaver.launch("TapScene-${ready.name}") }
            catch (_: android.content.ActivityNotFoundException) {
                exportPickerPending = false; releases.cancelExportPicker()
                releases.message("系统没有可用的文件保存工具。")
            }
        }
    }
    LaunchedEffect(page) { if (page != "delivery") releases.leaveDeliveryPage() }
    val packageSharer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        releases.finishShareChooser()
    }
    LaunchedEffect(releaseState.pendingShare, releaseState.busy, page, releaseState.deliverySelection) {
        if (releaseState.pendingShare != null && !releaseState.busy) {
            val share = releases.beginShareChooser(releaseState.deliveryRelease?.id.takeIf { page == "delivery" })
            if (share != null) {
                try {
                    packageSharer.launch(offlineShareChooser(share))
                    releases.shareChooserLaunched()
                } catch (_: android.content.ActivityNotFoundException) {
                    releases.finishShareChooser(failed = true)
                } catch (_: SecurityException) {
                    releases.finishShareChooser(failed = true)
                }
            }
        }
    }
    val closeReleasePlayer: () -> Unit = {
        releases.closePlayer(); pop()
    }
    LaunchedEffect(page, projectId, state.busy) {
        if ((page == "transition" || page == "regions" || page == "check-region" || page == "path") && projectId == null && !state.busy) pop()
    }
    val scopeReady = projectId != null && media.projectId == projectId && !retainedMedia
    val manualMediaProject = media.projectId.takeIf {
        mediaState.correction == null && (retainedMedia || (scopeReady && state.route == ProjectRoute.MEDIA))
    }
    val manualMediaSource = mediaState.selected.takeIf { manualMediaProject != null }
    // Keep just raw time inputs while this same workbench is covered by another task.
    // A real exit, source change, or change of entry disposes this state rather than caching it.
    val manualTimeInputs = key(manualMediaProject, manualMediaSource?.source?.sourceId, retainedMedia) {
        manualMediaSource?.let { source ->
            rememberSaveable(saver = ManualMediaTimeInputs.Saver) {
                ManualMediaTimeInputs.create(requireNotNull(manualMediaProject), source.source.sourceId,
                    source.frameTimeUs, source.source.metadata.durationUs)
            }
        }
    }
    val openStepCorrection: (Boolean) -> Unit = { masks ->
        val snapshot = projects.state.value.project
        val step = projects.state.value.selectedStepId
        if (!unavailable && scopeReady && snapshot != null && step != null && media.openStepCorrection(snapshot, step)) {
            correctionStartsWithMask = masks
            push("step-correction")
        }
    }
    val closeStepCorrection: () -> Unit = {
        val correction = media.state.value.correction
        val completed = media.state.value.completedCorrectionId != null
        if (!projects.state.value.busy && media.closeStepCorrection()) {
            // A failed/stale preparation may have discovered a newer persisted revision.
            // Refresh without saving or discarding the author's retained text and actions.
            if (correction != null && !completed) projects.refreshAfterTransition(correction.projectId, correction.stepId)
            pop()
        }
    }
    LaunchedEffect(page, mediaState.completedCorrectionId, mediaState.busy, state.busy) {
        if (page == "step-correction" && mediaState.completedCorrectionId != null && !mediaState.busy && !state.busy)
            closeStepCorrection()
    }
    val openRecordedCandidates: (String, String, Boolean) -> Unit = { id, source, analyze ->
        if (retainAiChanges(aiRecordingNote)) {
            pendingCandidateProject = null; pendingCandidateSource = null; autoAnalyzeSource = null
        } else if (media.state.value.unsavedEdits) {
            // Recording has completed before this callback. Preserve its durable source and
            // the earlier correction owner; do not refresh or replace that pending input.
            pendingCandidateProject = null; pendingCandidateSource = null; autoAnalyzeSource = null
            media.message("录屏已保存在本机。请先重试保存当前遮挡，或明确重新读取已保存记录，再从项目素材整理新录屏。")
            if (page == "record") pop()
        } else {
            pendingCandidateProject = id
            pendingCandidateSource = source
            autoAnalyzeSource = source.takeIf { analyze }
            if (projectId != id) projects.openProject(id)
            else if (media.projectId == id && !mediaState.busy) media.reload()
        }
    }
    LaunchedEffect(pendingCandidateProject, projectId, state.busy, scopeReady, mediaState.busy) {
        val expectedProject = pendingCandidateProject
        val expectedSource = pendingCandidateSource
        if (expectedProject != null && !state.busy && projectId == expectedProject && scopeReady && !mediaState.busy) {
            pendingCandidateProject = null
            pendingCandidateSource = null
            if (retainAiChanges(aiRecordingNote)) autoAnalyzeSource = null
            else if (mediaState.drafts.any { it.source.sourceId == expectedSource }) {
                media.selectSource(requireNotNull(expectedSource))
                pages.clear(); pages.add("candidates")
            } else { autoAnalyzeSource = null; projects.message("录屏尚未读入，请从项目素材重新打开。") }
        } else if (expectedProject != null && !state.busy && projectId != expectedProject) {
            pendingCandidateProject = null; pendingCandidateSource = null; autoAnalyzeSource = null
        }
    }
    LaunchedEffect(projectId, scopeReady, mediaState.selectedId, page, mediaState.busy) {
        if ((page == "candidates" || page == "candidate-review") && scopeReady && !mediaState.busy) {
            val source = mediaState.selected?.source
            if (source != null) candidates.activate(projectId, source)
        } else if (projectId != candidateState.projectId && !candidateState.loading) candidates.activate(null, null)
    }
    LaunchedEffect(autoAnalyzeSource, candidateState.sourceId, candidateState.loading, candidateState.busy) {
        if (autoAnalyzeSource != null && autoAnalyzeSource == candidateState.sourceId && !candidateState.loading && !candidateState.busy) {
            autoAnalyzeSource = null
            if (candidateState.status == CandidateAnalysisStatus.NOT_STARTED) candidates.analyze()
        }
    }
    val reviewSelected: (List<String>) -> Unit = { ids ->
        val eligible = candidateState.candidates.filter { it.id in ids && it.usedStepId == null }.sortedBy { it.actualTimeUs }
        if (!unavailable && scopeReady && !mediaState.busy && eligible.isNotEmpty()) {
            if (eligible.size > ProjectLimits.MAX_STEPS - (state.project?.steps?.size ?: 0)) projects.message("所选画面超过项目剩余步骤数量。")
            else {
                // Returning to another queue must not discard the author's earlier title drafts.
                reviewQueue = ArrayList(eligible.map { it.id }); reviewedStepIds = arrayListOf(); reviewIndex = 0; preparedCandidate = null
                push("candidate-review")
            }
        }
    }
    val queuedCandidate = candidateState.candidates.firstOrNull { it.id == reviewQueue.getOrNull(reviewIndex) }
    LaunchedEffect(page, reviewIndex, candidateState.loading, mediaState.busy, state.busy, scopeReady) {
        if (page == "candidate-review" && !candidateState.loading && !mediaState.busy && !state.busy && scopeReady) {
            if (reviewIndex >= reviewQueue.size) {
                pop(); candidates.refresh()
                val savedIds = reviewedStepIds.distinct()
                if (savedIds.size >= 2) {
                    pathStepIds = ArrayList(savedIds); pathMarkLastTerminal = null; push("path")
                } else projects.message("所选画面已处理，可以继续编辑步骤。")
            } else if (queuedCandidate?.usedStepId != null) {
                val savedId = requireNotNull(queuedCandidate.usedStepId)
                if (savedId !in reviewedStepIds) reviewedStepIds = ArrayList(reviewedStepIds + savedId)
                reviewIndex++; preparedCandidate = null
            }
            else if (queuedCandidate != null && preparedCandidate != queuedCandidate.id) {
                preparedCandidate = queuedCandidate.id
                media.prepareCandidateImage(queuedCandidate.sourceId, queuedCandidate.actualTimeUs, queuedCandidate.id)
            }
        }
    }
    LaunchedEffect(page, projectId, state.busy, pendingCandidateProject) {
        if ((page == "candidate-review" || page == "candidates") && projectId == null && !state.busy && pendingCandidateProject == null) {
            reviewQueue = arrayListOf(); if (!retainAiChanges(aiNavigationNote)) pages.clear()
        }
    }
    LaunchedEffect(pendingPathProject, projectId, state.busy, state.project?.project?.revision) {
        val expected = pendingPathProject
        if (expected != null && !state.busy) {
            pendingPathProject = null
            val current = state.project
            val committed = !state.loadFailed && current?.project?.id == expected &&
                current.project.revision > pendingPathRevision && pendingPathIds.size >= 2 &&
                pendingPathIds.zipWithNext().all { (from, to) -> current.steps.firstOrNull { it.id == from }?.nextAction?.targetStepId == to } &&
                (!pendingPathTerminal || current.steps.firstOrNull { it.id == pendingPathIds.last() }?.isTerminal == true)
            if (committed && page == "path") {
                if (!retainAiChanges("通路已保存。$aiNavigationNote")) { pages.clear(); tab = ProjectTab.STEPS }
                pathStepIds = null; pathMarkLastTerminal = null
            }
        }
    }
    val openPath: () -> Unit = {
        if (!unavailable && !mediaState.busy && state.project != null) {
            pathStepIds = null; pathMarkLastTerminal = null; push("path")
        }
    }
    val closeEditor: () -> Unit = {
        if (!state.busy) projects.leaveEditor()
    }
    val importVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val expected = pickerProjectId
        pickerProjectId = null
        if (uri != null) {
            if (expected != null && projects.state.value.project?.project?.id == expected && media.projectId == expected) {
                media.importVideo(uri)
            } else projects.message("项目已切换，请在当前项目重新选择录屏。")
        }
    }
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = screenshotPickerPending
        val expected = screenshotPickerProjectId
        screenshotPickerPending = false; screenshotPickerProjectId = null
        if (pending && uri != null) {
            val current = projects.state.value.project?.project?.id
            if (current == expected && !projects.state.value.busy && screenshots.importScreenshot(uri, expected)) push("screenshot")
            else projects.message("项目已切换，请重新选择截图。")
        }
    }
    val requestScreenshot: () -> Unit = {
        if (!unavailable && !mediaState.busy && !screenshotState.media.busy && !recording.isBusy &&
            screenshots.state.value.sessionId == null) {
            screenshotPickerProjectId = projectId
            screenshotPickerPending = true
            try { screenshotPicker.launch(arrayOf("image/png", "image/jpeg")) }
            catch (_: android.content.ActivityNotFoundException) {
                screenshotPickerPending = false; screenshotPickerProjectId = null
                projects.message("系统没有可用的文件选择工具。")
            }
        }
    }
    val closeScreenshot: () -> Unit = {
        val needsCheck = screenshots.state.value.saveOutcomeUnknown
        if (screenshots.close()) {
            pop()
            if (needsCheck) projects.reload()
        }
    }
    LaunchedEffect(screenshotState.completed, screenshotState.media.busy) {
        val saved = screenshotState.completed
        if (saved != null && !screenshotState.media.busy && !state.busy) {
            projects.openProject(saved.project.id)
            screenshots.close(); pages.clear(); tab = ProjectTab.STEPS
        }
    }
    // Scope activation clears old sources synchronously before its asynchronous reload starts.
    LaunchedEffect(projectId, state.route, mediaState.busy, retainedMedia) {
        if (projectId != null && state.route == ProjectRoute.STEPS && !retainedMedia && !mediaState.busy && media.projectId != projectId) {
            media.activateProject(projectId)
        }
    }
    LaunchedEffect(state.route, state.busy, projectId, awaitingCreatedProject) {
        if (awaitingCreatedProject && !state.busy) {
            awaitingCreatedProject = false
            if (state.route == ProjectRoute.STEPS && projectId != null) {
                tab = if (importAfterCreate) ProjectTab.SOURCES else ProjectTab.STEPS
                importRequested = importAfterCreate
            }
            importAfterCreate = false
        }
    }
    LaunchedEffect(importRequested, scopeReady, mediaState.busy, mediaState.loadFailed) {
        if (importRequested && scopeReady && !mediaState.busy) {
            importRequested = false
            if (!mediaState.loadFailed && !mediaState.unsavedEdits) {
                pickerProjectId = projectId
                try { importVideo.launch(arrayOf("video/mp4")) }
                catch (_: android.content.ActivityNotFoundException) {
                    pickerProjectId = null
                    projects.message("系统没有可用的文件选择工具。")
                }
            } else projects.message("素材暂时无法读取，请先恢复已保存记录。")
        }
    }
    val requestImport: () -> Unit = {
        if (!unavailable && !mediaState.busy && !retainAiChanges(aiNavigationNote)) {
            if (projectId == null) {
                importAfterCreate = true
                newProject = true
            } else {
                pages.clear()
                tab = ProjectTab.SOURCES
                importRequested = true
            }
        }
    }
    BackHandler(enabled = page != null && !retainedMedia) { if (page == "delivery") releases.leaveDeliveryPage(); pop() }
    BackHandler(enabled = page in setOf("review", "import") && releaseState.busy) { releases.cancel() }
    BackHandler(enabled = page == "review" && reviewSourceStepId != null && state.route != ProjectRoute.EDIT) {
        projects.cancel(); reviewSourceStepId = null; reviewSourceCandidateId = null; reviewSourceOpened = false
    }
    BackHandler(enabled = hostingVisit) { closeHosting() }
    BackHandler(enabled = page == "player") { closeReleasePlayer() }
    BackHandler(enabled = page == "step-correction") { closeStepCorrection() }
    BackHandler(enabled = page == "screenshot") { if (!screenshotState.media.busy) closeScreenshot() }
    BackHandler(enabled = page == "path" && state.route != ProjectRoute.EDIT) { if (!state.busy) pop() }
    BackHandler(enabled = page == null && !retainedMedia && state.route == ProjectRoute.STEPS) {
        if (!state.busy && !mediaState.busy) projects.back()
    }
    BackHandler(enabled = page == null && state.route == ProjectRoute.PROJECTS && library) { library = false }

    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = ShellColors.Background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                if (releaseState.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(releaseState.stage.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = releases::cancel) { Text("取消") }
                    }
                }
                releaseState.message?.takeIf { page !in setOf("review", "player", "import") && (!library || page == "delivery") }?.let { text ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = ShellColors.Accent)
                        TextButton(onClick = releases::clearMessage) { Text("关闭") }
                    }
                }
                if (recording.isBusy && page != "record") Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (recording.projectId != null && state.project?.project?.id != null && recording.projectId != state.project?.project?.id) "其他项目 · " else ""}${if (recording.phase == RecordingPhase.Recording) "录制中" else "录屏处理中"} · ${recordingOwnerTitle(state, recording)}",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = ShellColors.Accent,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    TextButton(onClick = { push("record") }, enabled = !draftAiTaskBlocked && mediaState.correction == null && screenshotState.sessionId == null) { Text("查看") }
                    if (recording.phase == RecordingPhase.Recording || recording.phase == RecordingPhase.Starting) TextButton(onClick = { RecordingCoordinator.stop(context) }) { Text("停止") }
                }
                if (candidateState.busy && page != "candidates") Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (candidateState.ocrStatus == CandidateOcrStatus.RUNNING)
                        "本机识别文字 ${candidateState.ocrCompleted}/${candidateState.ocrTotal}"
                        else "正在整理候选 ${candidateState.completedSamples}/${candidateState.totalSamples}",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { push("candidates") }, enabled = !draftAiTaskBlocked) { Text("查看") }
                    TextButton(onClick = candidates::cancel) { Text("取消") }
                }
                if (!retainedMedia && state.route != ProjectRoute.MEDIA && page != "candidate-review" && page != "step-correction" && page != "screenshot") {
                    if (state.busy || (scopeReady && mediaState.busy)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.busy) state.stage.orEmpty() else mediaState.stage.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { push("task") }, enabled = !draftAiTaskBlocked) { Text("详情") }
                            TextButton(onClick = {
                                if (state.busy) projects.cancel() else media.cancel()
                                if (page == "review" && reviewSourceStepId != null && !reviewSourceOpened) {
                                    reviewSourceStepId = null; reviewSourceCandidateId = null
                                }
                            }) { Text("取消") }
                        }
                    }
                    val message = state.message ?: mediaState.message.takeIf { scopeReady && tab == ProjectTab.SOURCES }
                    message?.let {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = ShellColors.Accent)
                            TextButton(onClick = { projects.clearMessage(); media.clearMessage() }) { Text("关闭") }
                        }
                        state.projectCopyNotice?.takeIf { notice -> notice.message == it }?.let { notice ->
                            LaunchedEffect(notice.operationId, state.busy) {
                                if (!state.busy) {
                                    if (!retainAiChanges("项目副本已保存。$aiNavigationNote")) {
                                        pages.clear(); library = false
                                    }
                                    projects.acknowledgeProjectCopyResult(notice.operationId)
                                }
                            }
                        }
                    }
                    if (state.loadFailed) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("项目暂时无法读取，已保存内容仍保留。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = projects::reload, enabled = !state.busy) { Text("重试") }
                    }
                }
                if (draftAiTaskBlocked && (recording.isBusy || candidateState.busy || state.busy || (scopeReady && mediaState.busy)))
                    DraftAiTaskNavigationNote(draftAiState, draftAiPendingHold)
                Box(Modifier.weight(1f)) {
                    when {
                        retainedMedia -> MediaScreen(media, onBack = { retainedMedia = false; projects.reload() }, manualTimeInputs = manualTimeInputs)
                        page != null -> when (page) {
                            "screenshot" -> {
                                val draft = screenshotState.draft
                                if (draft != null) StepImageCorrectionContent(screenshotState.media, null,
                                    StepImageCorrectionMode.MASK,
                                    CorrectionUiCallbacks(closeScreenshot, screenshots::cancel, {}, screenshots::addMask,
                                        screenshots::undoMask, screenshots::makeImage, screenshots::confirm,
                                        onRetry = { if (screenshots.close()) { pop(); requestScreenshot() } }, onSelectSource = {}),
                                    screenshot = draft)
                                else ScreenEmpty("截图编辑已关闭", "已确认的步骤保存在项目中；未确认的图片需重新选择。", "返回", closeScreenshot)
                            }
                            "step-correction" -> {
                                val correction = mediaState.correction
                                if (correction != null && correction.projectId == projectId && correction.stepId == state.selectedStepId) {
                                    StepImageCorrectionContent(mediaState, state.bitmap,
                                        if (correctionStartsWithMask) StepImageCorrectionMode.MASK else StepImageCorrectionMode.FRAME,
                                        CorrectionUiCallbacks(closeStepCorrection, media::cancel, media::takeFrame,
                                            media::addMask, media::undoMask, media::makeImage,
                                            onConfirm = { digest ->
                                                val current = media.state.value
                                                if (!projects.state.value.busy && !projects.state.value.loadFailed &&
                                                    current.canReviewCorrection(correction, digest)) {
                                                    media.review(true)
                                                    media.saveReviewedImage { input -> projects.replaceReviewedStep(correction, input) }
                                                }
                                            }, onRetry = media::retryStepCorrection, onSelectSource = media::selectCorrectionSource,
                                            onUseSafeImage = media::useSafeImageBase))
                                } else ScreenEmpty("已返回保存的画面", "未确认的新画面没有替换原步骤。", "返回步骤", closeStepCorrection)
                            }
                            "record" -> RecordingCaptureRoute(projects, pop, requestImport, openRecordedCandidates, openCreatedProject)
                            "click-plan" -> ClickPlanEntryRoute(projects, pop, onManualRecording = { pop(); push("record") },
                                onOpenProject = openCreatedProject)
                            "settings" -> {
                                val storage by produceState<Pair<Int, Long>?>(null, state.projects, state.retainedMediaWorkspaces, mediaState.drafts) {
                                    value = withContext(Dispatchers.IO) {
                                        try {
                                            val scopes = (state.projects.map { it.id } + state.retainedMediaWorkspaces.map { it.projectId }).distinct()
                                            val sources = scopes.flatMap { WorkspaceStore(context, it).read() }.distinctBy { it.source.sourceId }
                                            sources.size to sources.sumOf { it.source.metadata.byteLength }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { null }
                                    }
                                }
                                Column {
                                    if (storage == null) StatusNote("素材占用正在读取；读取失败时不显示完整统计。", Modifier.padding(horizontal = 16.dp))
                                    SettingsStorageScreen(storage?.first, storage?.second, pop,
                                        { push("account") }, { push("versions") }, { projects.reload(); push("sources") })
                                }
                            }
                            "sources" -> {
                                val projectSources by produceState<List<ProjectSourceUsage>?>(null, state.projects, mediaState.drafts) {
                                    value = null
                                    value = withContext(Dispatchers.IO) {
                                        state.projects.mapNotNull { project ->
                                            try {
                                                val sources = WorkspaceStore(context, project.id).read().distinctBy { it.source.sourceId }
                                                if (sources.isEmpty()) null else ProjectSourceUsage(project.id, project.title,
                                                    sources.size, sources.sumOf { it.source.metadata.byteLength })
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { ProjectSourceUsage(project.id, project.title, null, null) }
                                        }
                                    }
                                }
                                RetainedSourcesScreen(state, projectSources, mediaState.busy, onBack = pop,
                                    onOpenProject = { id ->
                                        val current = projects.state.value
                                        if (pages.lastOrNull() == "sources" && !current.busy && !current.loadFailed &&
                                            current.projects.any { it.id == id } && !retainAiChanges(aiNavigationNote)) {
                                            // Check that the current workbench can leave without switching owners yet.
                                            // The existing project effect activates the target only after its read succeeds.
                                            if (media.activateProject(media.projectId)) {
                                                pages.clear(); library = false; tab = ProjectTab.SOURCES
                                                projects.openProject(id)
                                            } else projects.message(media.state.value.message ?: "请先完成当前素材处理。")
                                        }
                                    }, onOpen = { id ->
                                        if (media.activateProject(id)) retainedMedia = true
                                        else projects.message(media.state.value.message ?: "请先完成当前素材处理。")
                                    })
                            }
                            "candidates" -> CandidateSelectionScreen(candidateState,
                                if (scopeReady) mediaState.drafts else emptyList(), candidates,
                                scopeReady && !mediaState.busy && !state.busy && !mediaState.loadFailed,
                                ProjectLimits.MAX_STEPS - (state.project?.steps?.size ?: 0),
                                { if (!candidateState.busy) media.selectSource(it) }, reviewSelected,
                                { pop() }, requestImport)
                            "candidate-review" -> {
                                val item = queuedCandidate
                                val targetProject = projectId
                                if (item != null && scopeReady && targetProject != null) Column(Modifier.fillMaxSize()) {
                                    val suggestionSource = candidateState.sourceSha256
                                    val suggestionResult = candidateState.ocrResults[item.id]?.takeIf {
                                        candidateState.projectId == targetProject && candidateState.sourceId == item.sourceId &&
                                            suggestionSource != null && it.width == item.width && it.height == item.height
                                    }
                                    val titleKey = "$targetProject:${item.sourceId}:${item.id}"
                                    val textSuggestions = if (suggestionResult != null && suggestionSource != null)
                                        CandidateTextSuggestions(targetProject, item.sourceId, suggestionSource, item.id,
                                            item.actualTimeUs, item.timePrecisionUs, suggestionResult) else null
                                    if (!mediaState.busy && mediaState.candidate == null && preparedCandidate == item.id) {
                                        TextButton(onClick = { media.prepareCandidateImage(item.sourceId, item.actualTimeUs, item.id) }) { Text("重试准备画面") }
                                    }
                                    Box(Modifier.weight(1f)) {
                                        MediaScreen(media, onBack = { media.invalidateCandidate(); reviewQueue = arrayListOf(); preparedCandidate = null; pop(); candidates.refresh() },
                                            headerTitle = "校正 ${reviewIndex + 1}/${reviewQueue.size}",
                                            confirmLabel = if (reviewIndex == reviewQueue.lastIndex) "确认画面并完成" else "确认画面并下一张",
                                            batchReview = true, reviewItemId = item.id, onSkip = { media.invalidateCandidate(); reviewIndex++; preparedCandidate = null },
                                            textSuggestions = textSuggestions, stepTitle = reviewTitles[titleKey].orEmpty(),
                                            onStepTitleChange = { title ->
                                                if (reviewQueue.getOrNull(reviewIndex) == item.id && !media.state.value.busy) {
                                                    reviewTitles = HashMap(reviewTitles).apply { put(titleKey, title) }
                                                }
                                            },
                                            onUseSuggestedTitle = { title ->
                                                if (reviewQueue.getOrNull(reviewIndex) == item.id && !media.state.value.busy && reviewTitles[titleKey].isNullOrBlank()) {
                                                    reviewTitles = HashMap(reviewTitles).apply { put(titleKey, title) }
                                                }
                                            },
                                            onReviewedImage = { input ->
                                                check(reviewQueue.getOrNull(reviewIndex) == item.id && preparedCandidate == item.id &&
                                                    media.state.value.frameReviewId == item.id && input.source?.sourceId == item.sourceId &&
                                                    input.frameTimeUs == media.state.value.frame?.presentationTimeUs) {
                                                    "候选画面已变化，请返回重新选择。"
                                                }
                                                val stepId = projects.saveReviewedStep(targetProject, input.copy(captureId = "candidate-${item.id}"),
                                                    openEditor = false, title = reviewTitles[titleKey])
                                                if (stepId !in reviewedStepIds) reviewedStepIds = ArrayList(reviewedStepIds + stepId)
                                                candidates.markUsed(item.id, stepId)
                                                reviewTitles = HashMap(reviewTitles).apply { remove(titleKey) }
                                                reviewIndex++; preparedCandidate = null
                                            })
                                    }
                                } else ScreenEmpty("候选尚未就绪", "已保存步骤仍在项目中。", "返回候选", { pop(); candidates.refresh() })
                            }
                            "path" -> state.project?.let { snapshot ->
                                if (state.route == ProjectRoute.EDIT && state.stepDraft != null) {
                                    val editorDraft = requireNotNull(state.stepDraft)
                                    EditorWorkspaceContent(snapshot, editorDraft, state.bitmap, unavailable, EditorCallbacks(
                                        closeEditor, projects::editTitle, projects::editDescription, projects::editTerminal,
                                        projects::putHotspot, projects::removeHotspot, projects::saveStepDraft, projects::discardStepDraft,
                                        {}, { id -> projects.saveBeforeTransition { transitionEdgeId = id; push("transition") } }, projects::putNextAction, projects::removeNextAction,
                                        onOpenRegions = { if (state.dirtyStepIds.isEmpty()) push("regions") }, onCorrectImage = openStepCorrection,
                                        onPendingFormChange = { form -> projects.editPendingForm(snapshot.project.id, editorDraft.stepId, form) }, onRetryStaging = projects::retryDraftStaging,
                                        onResolveConflict = projects::resolveDraftConflict,
                                        onSavePendingForm = { projects.savePendingStepForm(snapshot.project.id, editorDraft.stepId) }, onResolveUnsavedSteps = { showUnsavedSteps = true },
                                        onUndo = projects::undoEditorEdit, onTextEditEnd = projects::endEditorTextEdit,
                                        onRecognizeTextRegions = projects::recognizeTextRegions, onCancelTextRegions = projects::cancelTextRegions,
                                        onSelectTextRegion = projects::selectTextRegion),
                                        previewEnabled = false, regionsEnabled = state.dirtyStepIds.isEmpty(), correctionEnabled = scopeReady && !mediaState.busy,
                                dirtyStepCount = state.dirtyStepIds.size, formMessage = state.message, canUndoEdit = state.canUndoEdit, textRegions = state.textRegions)
                                } else AuthoredPathScreen(snapshot, pathStepIds, unavailable || mediaState.busy,
                                    { if (!state.busy) pop() },
                                    { ids, terminal, revision ->
                                        if (projects.state.value.dirtyStepIds.isNotEmpty()) {
                                            projects.message("先保存或放弃步骤修改，再生成通路。")
                                        } else {
                                            pendingPathProject = snapshot.project.id; pendingPathIds = ArrayList(ids)
                                            pendingPathTerminal = terminal; pendingPathRevision = revision
                                            projects.connectStepsInOrder(ids, terminal, revision)
                                        }
                                    }, projects::openStep,
                                    initialMarkLastTerminal = pathMarkLastTerminal,
                                    onDraftChanged = { ids, terminal -> pathStepIds = ArrayList(ids); pathMarkLastTerminal = terminal })
                            }
                            "review" -> {
                                val sourceStep = reviewSourceStepId
                                val snapshot = state.project
                                val draft = state.stepDraft
                                if (sourceStep != null) {
                                    if (snapshot != null && draft != null && state.route == ProjectRoute.EDIT && state.selectedStepId == sourceStep &&
                                        snapshot.project.id == releaseState.candidate?.projectId && draft.stepId == sourceStep) {
                                        EditorWorkspaceContent(snapshot, draft, state.bitmap, unavailable, EditorCallbacks(
                                            closeEditor, projects::editTitle, projects::editDescription, projects::editTerminal,
                                            projects::putHotspot, projects::removeHotspot, projects::saveStepDraft, projects::discardStepDraft,
                                            {}, { id -> projects.saveBeforeTransition { transitionEdgeId = id; push("transition") } },
                                            projects::putNextAction, projects::removeNextAction,
                                            onOpenRegions = { if (state.dirtyStepIds.isEmpty()) push("regions") }, onCorrectImage = openStepCorrection,
                                                    onPendingFormChange = { form -> projects.editPendingForm(snapshot.project.id, draft.stepId, form) }, onRetryStaging = projects::retryDraftStaging,
                                                    onResolveConflict = projects::resolveDraftConflict,
                                                    onSavePendingForm = { projects.savePendingStepForm(snapshot.project.id, draft.stepId) }, onResolveUnsavedSteps = { showUnsavedSteps = true },
                                                    onUndo = projects::undoEditorEdit, onTextEditEnd = projects::endEditorTextEdit,
                                                    onRecognizeTextRegions = projects::recognizeTextRegions, onCancelTextRegions = projects::cancelTextRegions,
                                                    onSelectTextRegion = projects::selectTextRegion),
                                            previewEnabled = false, regionsEnabled = state.dirtyStepIds.isEmpty(),
                                            correctionEnabled = scopeReady && !mediaState.busy,
                                            dirtyStepCount = state.dirtyStepIds.size, formMessage = state.message, canUndoEdit = state.canUndoEdit, textRegions = state.textRegions)
                                    } else Column(Modifier.fillMaxSize()) {
                                        ShellTopBar("打开来源步骤", { projects.cancel(); reviewSourceStepId = null; reviewSourceCandidateId = null; reviewSourceOpened = false })
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                } else ReleaseReviewContent(releaseState,
                                releaseState.candidate?.projectId?.let { id ->
                                    state.project?.project?.takeIf { it.id == id }?.revision
                                        ?: state.projects.firstOrNull { it.id == id }?.revision
                                },
                                pop, releases::selectReviewState, releases::confirmReviewState,
                                releases::confirmSummary, releases::confirmFileList,
                                { releases.startCandidatePreview(); push("player") },
                                { pendingSealId = releaseState.candidate?.scene?.releaseId; releases.seal() },
                                { replacingCandidate = releaseState.candidate },
                                onReviewVideo = releases::selectReviewTransition, onVideoCompleted = releases::completeReviewTransition,
                                onVideoInterrupted = releases::interruptReviewTransition, onVideoReplay = releases::replayReviewTransition,
                                onConfirmVideo = releases::confirmReviewTransition, onCloseVideo = releases::closeReviewVideo,
                                onReviewRegion = releases::selectReviewRegion, onConfirmRegion = releases::confirmReviewRegion,
                                editableSourceStepIds = reviewSource?.steps?.map { it.id }?.toSet().orEmpty(),
                                sourceHasPendingEdits = reviewSourceInfo?.second == true,
                                onEditSourceProjectText = reviewSource?.project?.takeIf { it.id == releaseState.candidate?.projectId }?.let { source -> ({
                                    if (!unavailable && !mediaState.busy && releases.state.value.candidate?.projectId == source.id) {
                                        projects.clearMessage(); renaming = source
                                    }
                                }) },
                                onEditSourceStep = { stepId ->
                                    val candidate = releases.state.value.candidate
                                    if (candidate != null && !unavailable && !mediaState.busy &&
                                        reviewSource?.project?.id == candidate.projectId && reviewSource?.steps?.any { it.id == stepId } == true) {
                                        reviewCandidateId = candidate.id
                                        reviewSourceFromLibrary = library && state.route == ProjectRoute.PROJECTS
                                        reviewSourceCandidateId = candidate.id; reviewSourceStepId = stepId; reviewSourceOpened = false
                                        releases.closeReviewVideo(); projects.openProject(candidate.projectId)
                                    }
                                },
                                onBackToChecks = if (state.route == ProjectRoute.STEPS) ({ pages.clear(); library = false; tab = ProjectTab.CHECKS }) else null)
                            }
                            "delivery" -> {
                                val sealed = releaseState.deliveryRelease
                                val selection = releaseState.deliverySelection?.takeIf {
                                    sealed != null && it.releaseId == sealed.id && it.contentDigest == sealed.contentDigest
                                }
                                // Only compare an actually loaded source project; another project or
                                // a library-only visit must not manufacture a draft comparison.
                                val sourceProject = state.project?.takeIf {
                                    !state.busy && !state.loadFailed && state.route == ProjectRoute.STEPS &&
                                        it.project.id == selection?.projectId
                                }
                                DeliveryOptionsScreen({ releases.leaveDeliveryPage(); pop() }, openReleaseReview, { if (sealed != null) releases.openAiPackage(sealed.id) else releases.clearAiConfiguration(); push("ai") }, { push("account") }, { push("versions") },
                                    sealedSummary = sealed, onExport = sealed?.let { { releases.prepareDeliveryExport() } },
                                    onShare = sealed?.takeIf { it.origin == "local" }?.let { item -> { releases.prepareOfflineShare(item.id) } },
                                    busy = releaseState.busy || releaseState.shareChooserOpen || releaseState.pendingShare != null || releaseState.exportFile != null || exportPickerPending,
                                    onHosting = if (BuildConfig.HOSTED_ENABLED) ({ push("hosted") }) else null,
                                    onOpenSealed = sealed?.let { item -> { releases.openRelease(item.id); push("player") } },
                                    onChooseVersion = {
                                        releases.leaveDeliveryPage(); pages.clear(); library = true
                                        if (state.route == ProjectRoute.STEPS) projects.back()
                                        releases.reloadLibrary()
                                    }, sealedRevision = selection?.projectRevision,
                                    currentDraftRevision = sourceProject?.project?.revision,
                                    onBackToChecks = sourceProject?.let { source -> ({
                                        val current = projects.state.value
                                        val delivery = releases.state.value
                                        if (!current.busy && !current.loadFailed && !delivery.busy &&
                                            !delivery.shareChooserOpen && delivery.pendingShare == null &&
                                            delivery.exportFile == null && !exportPickerPending &&
                                            !media.state.value.busy && current.route == ProjectRoute.STEPS &&
                                            current.project?.project?.id == source.project.id &&
                                            delivery.deliverySelection == selection) {
                                            releases.leaveDeliveryPage(); pages.clear(); library = false; tab = ProjectTab.CHECKS
                                        }
                                    }) })
                            }
                            "ai-import" -> AiDraftImportContent(aiImportState,
                                onBack = { if (!aiImportState.busy) { aiPickerRequested = false; pop(); projects.reload() } },
                                onChooseFile = chooseAiPackage, onBaseline = aiDrafts::selectBaseline,
                                onResume = aiDrafts::resumeImport, onCommit = aiDrafts::confirmImport,
                                onCancel = aiDrafts::cancelImport, onReadResult = aiDrafts::readImportResult,
                                onOpenProject = { id ->
                                    if (!aiImportState.busy && !state.busy) {
                                        aiDrafts.clearCompletedImport(); pages.clear(); library = false
                                        tab = ProjectTab.STEPS; projects.openProject(id)
                                        projects.message("已导入待复核；请重新检查画面、文字、动作、区域与动画计划。")
                                    }
                                }, onNewImport = { aiDrafts.clearCompletedImport(); chooseAiPackage() },
                                onImage = aiDrafts::showImportImage, onCloseImage = aiDrafts::clearImage)
                            "draft-ai" -> DraftAiPlanContent(draftAiState, DraftAiPlanCallbacks(
                                onBack = closeDraftAi, onCanvas = aiDrafts::setCanvas, onHold = aiDrafts::setHold,
                                onChooseEdge = aiDrafts::choosePlanEdge, onRestart = aiDrafts::restartPlan,
                                onConfirmPath = aiDrafts::confirmPathChange, onDismissPath = aiDrafts::dismissPathChange,
                                onChangeEffect = aiDrafts::changeEffect, onDeleteEffect = aiDrafts::deleteEffect,
                                onSave = aiDrafts::savePlan, onReadResult = aiDrafts::reconcilePlan,
                                onReload = { projectId?.let(aiDrafts::openPlan) }),
                                onPendingHoldChange = { draftAiPendingHold = it })
                            "ai" -> aiRouteState.SaveableStateProvider(aiStateKey) {
                                AiPackageScreen({ releases.clearAiConfiguration(); pop() }, releaseState, releases::openAiPackage, releases::chooseAiEdge,
                                    releases::previousAiVisit, releases::resetAiPath, releases::setAiCanvas, releases::setAiHold,
                                    releases::toggleAiEffect, releases::prepareAiExport,
                                    onApplyAnnotation = releases::applyAiAnnotation, onRemoveAnnotation = releases::removeAiAnnotation,
                                    onPendingInputChange = { aiPendingInput = it },
                                    onChooseConfiguration = releases::chooseAiConfiguration,
                                    onRecheckSave = releases::recheckAiSave)
                            }
                            "hosted" -> if (BuildConfig.HOSTED_ENABLED) LocalHostingContent(hostingState, hostingActions) else HostingAccountScreen(pop, { push("versions") })
                            "account" -> if (BuildConfig.HOSTED_ENABLED) LocalHostingContent(hostingState, hostingActions) else HostingAccountScreen(pop, { push("versions") })
                            "versions" -> if (BuildConfig.HOSTED_ENABLED) LocalHostingContent(hostingState, hostingActions) else HostedVersionsScreen(pop, { push("account") })
                            "import" -> OfflineImportContent(releaseState, pop, onChoose = {
                                if (!releaseState.busy) {
                                    releases.clearImportResult(); releases.clearMessage()
                                    try { packagePicker.launch(arrayOf("*/*")) }
                                    catch (_: android.content.ActivityNotFoundException) { releases.message("系统没有可用的文件选择工具。") }
                                }
                            }, onOpen = { id ->
                                val current = releases.state.value
                                if (pages.lastOrNull() == "import" && !current.busy && !exportPickerPending &&
                                    current.exportFile == null && current.pendingShare == null && !current.shareChooserOpen &&
                                    current.lastImportedId == id && current.releases.any { it.id == id }) {
                                    releases.openRelease(id)
                                    // Player exit returns to the library, including a failed read.
                                    pages[pages.lastIndex] = "player"
                                }
                            })
                            "player" -> ReleasePlayerContent(releaseState, closeReleasePlayer,
                                releases::tapPlayer, releases::chooseEdge, releases::previous, releases::restart, releases::dismissMatches,
                                onVideoCompleted = releases::completePlayerTransition, onVideoFailed = releases::failPlayerTransition,
                                onVideoRetry = releases::retryPlayerTransition, onVideoSkip = releases::skipPlayerTransition,
                                onResume = releases::resumePlayback)
                            "task" -> TaskDetailsScreen(
                                if (state.busy || mediaState.busy) ShellTaskInfo("本机处理", if (state.busy) state.stage.orEmpty() else mediaState.stage.orEmpty(), state.project?.project?.title) else null,
                                pop, { if (!retainAiChanges(aiNavigationNote)) pages.clear() }, { push("settings") })
                            "regions", "check-region" -> state.project?.let { snapshot ->
                                val fromChecks = page == "check-region"
                                val requestedStep = if (fromChecks) regionCorrectionStepId else state.selectedStepId
                                val requestedRegion = regionCorrectionId.takeIf { fromChecks }
                                requestedStep?.let { stepId ->
                                    val regionState by regions.state.collectAsStateWithLifecycle()
                                    val regionReady = regionState.projectId == snapshot.project.id && regionState.stepId == stepId
                                    LaunchedEffect(snapshot.project.id, stepId, requestedRegion, regionState.projectId, regionState.stepId, regionState.busy, regionCorrectionPending) {
                                        if (!regionState.busy && (!regionReady || fromChecks && regionCorrectionPending)) {
                                            val opened = regions.open(snapshot.project.id, stepId, requestedRegion,
                                                freshEntry = fromChecks && regionCorrectionPending)
                                            if (opened && fromChecks) regionCorrectionPending = false
                                        }
                                    }
                                    if (regionReady && !(fromChecks && regionCorrectionPending)) RegionEditorScreen(regions) {
                                        if (fromChecks) {
                                            regionCorrectionStepId = null; regionCorrectionId = null; regionCorrectionPending = false
                                            projects.reload()
                                        } else projects.refreshAfterTransition(snapshot.project.id, stepId)
                                        pop()
                                    } else Column(Modifier.fillMaxSize()) {
                                        ShellTopBar("读取区域", {
                                            if (fromChecks) { regionCorrectionStepId = null; regionCorrectionId = null; regionCorrectionPending = false }
                                            pop()
                                        })
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                        Text(if (regionState.busy) "正在等待之前的区域处理完成，随后读取当前区域。" else "正在读取当前区域。", Modifier.padding(16.dp),
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            "transition" -> state.project?.let { snapshot -> state.selectedStepId?.let { stepId ->
                                val edgeId = transitionEdgeId
                                if (edgeId != null) {
                                    LaunchedEffect(snapshot.project.id, stepId, edgeId) { transitions.open(snapshot.project.id, stepId, edgeId) }
                                    TransitionEditorScreen(transitions, pop) { savedProject, savedStep ->
                                        projects.refreshAfterTransition(savedProject, savedStep); pop()
                                    }
                                } else ScreenEmpty("动作已改变", "返回步骤重新选择。", "返回", pop)
                            } }
                        }
                        state.route == ProjectRoute.MEDIA -> {
                            val targetId = projectId
                            if (scopeReady) MediaScreen(media, projects::back, manualTimeInputs = manualTimeInputs) { input ->
                                projects.saveReviewedStep(targetId, input)
                            } else ScreenEmpty("素材未就绪", "请返回项目重新打开素材。", "返回", projects::back)
                        }
                        state.route == ProjectRoute.EDIT -> state.project?.let { snapshot -> state.stepDraft?.let { draft ->
                            EditorWorkspaceContent(snapshot, draft, state.bitmap, unavailable, EditorCallbacks(
                                closeEditor, projects::editTitle, projects::editDescription, projects::editTerminal,
                                projects::putHotspot, projects::removeHotspot, projects::saveStepDraft, projects::discardStepDraft,
                                { projects.startPreview(true) }, { id -> projects.saveBeforeTransition { transitionEdgeId = id; push("transition") } },
                                projects::putNextAction, projects::removeNextAction,
                                onOpenRegions = { if (state.dirtyStepIds.isEmpty()) push("regions") }, onCorrectImage = openStepCorrection,
                                        onPendingFormChange = { form -> projects.editPendingForm(snapshot.project.id, draft.stepId, form) }, onRetryStaging = projects::retryDraftStaging,
                                        onResolveConflict = projects::resolveDraftConflict,
                                        onSavePendingForm = { projects.savePendingStepForm(snapshot.project.id, draft.stepId) }, onResolveUnsavedSteps = { showUnsavedSteps = true },
                                        onUndo = projects::undoEditorEdit, onTextEditEnd = projects::endEditorTextEdit,
                                        onRecognizeTextRegions = projects::recognizeTextRegions, onCancelTextRegions = projects::cancelTextRegions,
                                        onSelectTextRegion = projects::selectTextRegion),
                                previewEnabled = state.dirtyStepIds.isEmpty(), regionsEnabled = state.dirtyStepIds.isEmpty(),
                                correctionEnabled = scopeReady && !mediaState.busy,
                                dirtyStepCount = state.dirtyStepIds.size, formMessage = state.message, canUndoEdit = state.canUndoEdit, textRegions = state.textRegions)
                        } }
                        state.route == ProjectRoute.PREVIEW -> state.project?.let { snapshot -> state.preview?.let { preview ->
                            DraftPreviewContent(snapshot, preview, state.bitmap, unavailable, projects::chooseHotspot,
                                projects::tapPreview, projects::previousPreview, projects::restartPreview, projects::exitPreview,
                                onNextAction = projects::chooseNextAction,
                                onVideoCompleted = projects::completePreviewTransition, onVideoFailed = projects::failPreviewTransition,
                                onVideoRetry = projects::retryPreviewTransition, onVideoSkip = projects::skipPreviewTransition)
                        } }
                        state.route == ProjectRoute.STEPS -> ProjectWorkspaceFrame(
                            state.project?.project?.title.orEmpty(), tab, { if (!state.busy && !mediaState.busy) { connectionRepairStepId = null; tab = it } },
                            { if (!state.busy && !mediaState.busy) projects.back() }, { if (!unavailable) projects.startPreview() },
                            { projectMore = true }, tabsEnabled = !state.busy && !mediaState.busy,
                        ) {
                            when (tab) {
                                ProjectTab.STEPS -> StoryboardContent(state, projects::openStep, { tab = ProjectTab.SOURCES },
                                    projects::setStart, projects::moveStep, { step -> state.project?.let { deletingStep = it to step.id } },
                                    stepThumbnail = { asset -> ReviewedThumbnail(projects, state.project, asset) }, onBuildPath = openPath, onAiPlan = openDraftAi,
                                    onCopyStep = projects::copySavedStep, connectionRepairStepId = connectionRepairStepId,
                                    onReturnToChecks = { connectionRepairStepId = null; tab = ProjectTab.CHECKS })
                                ProjectTab.SOURCES -> Column(Modifier.fillMaxSize()) {
                                    if (scopeReady && mediaState.loadFailed) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("素材暂时无法读取。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = media::reload, enabled = !mediaState.busy) { Text("重新读取") }
                                    }
                                    ProjectSourcesContent(
                                    if (scopeReady) mediaState.drafts else emptyList(), unavailable || mediaState.busy || !scopeReady,
                                    mediaState.loadFailed, { push("record") }, requestImport, { id ->
                                        if (scopeReady && !mediaState.busy && !mediaState.unsavedEdits) {
                                            media.selectSource(id); projects.openMedia()
                                        }
                                    }, { push("candidates") }, { id ->
                                        if (scopeReady && !unavailable && !mediaState.busy && !mediaState.unsavedEdits) {
                                            media.selectSource(id); autoAnalyzeSource = id; push("candidates")
                                        }
                                    }, onImportScreenshot = requestScreenshot, onClickPlan = { push("click-plan") })
                                }
                                ProjectTab.CHECKS -> DeliveryCheckScreen(state.project, state.issues, { tab = ProjectTab.STEPS },
                                    { issue -> if (!unavailable && !mediaState.busy) {
                                        when (issue.correction) {
                                            ProjectIssueCorrection.REGION -> if (state.dirtyStepIds.isNotEmpty()) {
                                                projects.message("先保存或放弃步骤修改，再处理区域裁片。")
                                            } else if (issue.stepId != null && issue.regionId != null) {
                                                regionCorrectionStepId = issue.stepId; regionCorrectionId = issue.regionId
                                                regionCorrectionPending = true; push("check-region")
                                            }
                                            ProjectIssueCorrection.ANIMATION -> openDraftAi()
                                            ProjectIssueCorrection.CONNECTION -> {
                                                connectionRepairStepId = issue.stepId; tab = ProjectTab.STEPS
                                            }
                                            ProjectIssueCorrection.STEP_EDITOR -> issue.stepId?.let(projects::openStep) ?: run { tab = ProjectTab.STEPS }
                                        }
                                    } },
                                    { projects.startPreview() }, openReleaseReview, { projectId?.let(releases::openProjectDelivery); push("delivery") }, showTopBar = false,
                                    onBuildCandidate = buildRelease, busy = unavailable || mediaState.busy,
                                    buildEnabled = state.dirtyStepIds.isEmpty(), loadFailed = state.loadFailed)
                            }
                        }
                        library -> Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) { ReleaseLibraryContent(releaseState, openPackageImport,
                                { id -> releases.openRelease(id); push("player") }, releases::prepareExport,
                                { deletingRelease = it }, { push("settings") },
                                onReviewCandidate = { id -> reviewCandidateId = id; push("review") },
                                onDiscardCandidate = { discardingCandidate = it },
                                onAi = { id -> releases.openAiPackage(id); push("ai") },
                                onDelivery = { id -> if (releases.selectDeliveryRelease(id)) push("delivery") },
                                onRetryDeletion = releases::retryReleaseDeletion, onReload = releases::reloadLibrary,
                                onHosting = if (BuildConfig.HOSTED_ENABLED) ({ push("hosted") }) else null) }
                            GlobalNavigation(true, { library = false }, {})
                        }
                        else -> ProjectHomeFrame({ push("record") }, requestImport, { push("settings") }, { library = true; releases.reloadLibrary() }, onImportScreenshot = requestScreenshot, onImportAi = openAiImport, onClickPlan = { push("click-plan") }) { creationActions ->
                            ProjectHomeContent(state, { tab = ProjectTab.STEPS; projects.openProject(it) }, { projects.clearMessage(); renaming = it },
                                { if (!unavailable && !mediaState.busy) deletingProject = it }, onCopyProject = copyProject,
                                homeState = projectHomeState, creationActions = creationActions)
                        }
                    }
                }
            }
            if (newProject) ProjectTitleDialog("新建项目", "", "", true, !state.busy, { newProject = false; importAfterCreate = false }) { title, goal ->
                newProject = false
                if (!retainAiChanges(aiNavigationNote)) {
                    pages.clear(); awaitingCreatedProject = true; projects.createProject(title, goal)
                } else importAfterCreate = false
            }
            renaming?.let { item -> key(item.id) {
                ProjectTextDialog(item.title, item.goal, !state.busy && !state.loadFailed,
                    state.message, { if (!state.busy) renaming = null }, canDismiss = !state.busy) { title, goal ->
                    projects.renameProject(item.id, title, goal, item.revision) {
                        if (renaming?.id == item.id) renaming = null
                        if (page == "review") releases.message("来源草稿已更新；固定候选仍保留原文字。请重新生成并复核；如有动画计划，先检查并保存计划。")
                    }
                }
            } }
            deletingProject?.let { item -> ProjectDeleteDialog(item, state.matchesProjectDeletion(item),
                !unavailable && !mediaState.busy,
                { deletingProject = null }, { if (!projects.state.value.matchesProjectDeletion(item)) {
                    projects.message("项目内容已变化，请取消后重新打开并核对。")
                } else if (recording.projectId == item.id && (recording.isBusy || recording.canRetry)) {
                    deletingProject = null; projects.message("请先停止或处理这个项目的未完成录制。")
                } else if (candidateState.projectId == item.id && candidateState.busy) {
                    deletingProject = null; projects.message("请先取消当前分析。")
                } else { deletingProject = null; reviewTitles = HashMap(reviewTitles.filterKeys { !it.startsWith("${item.id}:") }); projects.deleteProject(item.id) } }) }
            deletingStep?.let { (snapshot, stepId) ->
                val current = state.project?.project?.let { it.id == snapshot.project.id && it.revision == snapshot.project.revision } == true
                StepDeleteDialog(snapshot, stepId, projects.deletionHotspotCount(stepId), projects.deletionNextActionCount(stepId),
                    current, !state.busy && !state.loadFailed, { deletingStep = null }, {
                        deletingStep = null
                        projects.deleteStep(stepId, snapshot.project.id, snapshot.project.revision)
                    })
            }
            deletingRelease?.let { item ->
                val current = releaseState.releases.any { it.id == item.id && it.contentDigest == item.contentDigest &&
                    it.origin == item.origin && it.sealedAt == item.sealedAt }
                ReleaseDeleteDialog(item, current, !releaseState.busy && releaseState.deletionCheck == null,
                    { deletingRelease = null }, { deletingRelease = null; releases.deleteRelease(item) })
            }
            discardingCandidate?.let { item -> ConfirmDelete("删除未封存副本？",
                "“${item.scene.title}”修订 ${item.projectRevision} 的固定图片和复核记录将删除。创作项目与封存版本保留。", !releaseState.busy,
                { discardingCandidate = null }, { discardingCandidate = null; releases.discardCandidate(item.id) }) }
            replacingCandidate?.let { item ->
                val latest = state.project?.project?.takeIf { it.id == item.projectId }
                    ?: state.projects.firstOrNull { it.id == item.projectId }
                val sourceReady = item.id != releaseState.candidate?.id ||
                    (reviewSource?.project?.id == item.projectId && reviewSourceInfo?.second == false)
                AlertDialog(onDismissRequest = { replacingCandidate = null }, title = { Text("重新生成固定成品？") },
                    text = { Text(if (latest == null) "原项目已不可用；现有固定候选仍可继续复核。"
                        else if (!sourceReady && reviewSourceInfo?.second == true) "请先保存或放弃来源步骤的暂存修改，再重新生成。固定候选仍保留。"
                        else if (!sourceReady) "来源修改状态尚未确认，请稍后重试；固定候选仍保留。"
                        else "用草稿修订 ${latest.revision} 替换未封存的修订 ${item.projectRevision}，需要重新复核。已封存版本保持不变。") },
                    confirmButton = { TextButton(enabled = latest != null && sourceReady && !releaseState.busy && state.dirtyStepIds.isEmpty(), onClick = {
                        latest?.let { source ->
                            replacingCandidate = null; reviewCandidateId = null
                            releases.createCandidate(source.id, source.revision, replaceExisting = true); push("review")
                        }
                    }) { Text("重新生成") } },
                    dismissButton = { TextButton(onClick = { replacingCandidate = null }) { Text("保留现有候选") } })
            }
            state.editorExitIssue?.let { issue ->
                AlertDialog(onDismissRequest = projects::dismissEditorExitIssue, title = { Text("修改尚未安全保留") },
                    text = { Text(issue) },
                    confirmButton = {
                        TextButton(onClick = projects::leaveEditor, enabled = !state.busy && !state.loadFailed && state.stepDraft?.conflicts?.isEmpty() == true) {
                            Text("重试暂存并返回")
                        }
                    },
                    dismissButton = {
                        Column {
                            TextButton(onClick = projects::dismissEditorExitIssue, enabled = !state.busy) { Text("继续编辑") }
                            TextButton(onClick = projects::discardStepDraftAndLeave, enabled = !state.busy && !state.loadFailed) { Text("放弃本步修改并返回") }
                        }
                    })
            }
            if (showUnsavedSteps) AlertDialog(onDismissRequest = { showUnsavedSteps = false },
                title = { Text("${state.dirtyStepIds.size} 步待保存") },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text("逐步核对后保存。未完成的输入与冲突不会自动提交。", style = MaterialTheme.typography.bodySmall)
                        state.project?.steps?.forEachIndexed { index, step ->
                            if (step.id in state.dirtyStepIds) ShellActionRow("${index + 1}  ${step.title}", "打开并处理", enabled = !state.busy,
                                onClick = {
                                    showUnsavedSteps = false
                                    if (reviewSourceStepId != null) reviewSourceStepId = step.id
                                    projects.openStep(step.id)
                                })
                        }
                    }
                }, confirmButton = { TextButton(onClick = { showUnsavedSteps = false }) { Text("关闭") } })
            if (projectMore) AlertDialog(onDismissRequest = { projectMore = false }, title = { Text("项目") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ShellActionRow("项目文字", "名称与随包交付的开场说明", enabled = !unavailable && !mediaState.busy,
                        onClick = { projectMore = false; projects.clearMessage(); renaming = state.project?.project })
                    ShellActionRow("复制项目", "复制已保存内容，独立编辑", enabled = !unavailable && !mediaState.busy,
                        onClick = { projectMore = false; state.project?.project?.id?.let(copyProject) })
                    ShellActionRow("动画计划", "保留并编辑完整访问与效果", onClick = { projectMore = false; openDraftAi() })
                    ShellActionRow("任务详情", onClick = { projectMore = false; push("task") })
                    if (BuildConfig.HOSTED_ENABLED) ShellActionRow("托管版本", onClick = { projectMore = false; push("versions") })
                    ShellActionRow("删除项目", enabled = !unavailable && !mediaState.busy,
                        onClick = { projectMore = false; deletingProject = state.project?.project })
                }
            }, confirmButton = { TextButton(onClick = { projectMore = false }) { Text("关闭") } })
        }
    }
}

@Composable
internal fun ProjectTextDialog(initialTitle: String, initialGoal: String, enabled: Boolean,
    message: String? = null, onDismiss: () -> Unit, canDismiss: Boolean = true, onConfirm: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialTitle) }
    var goal by rememberSaveable { mutableStateOf(initialGoal) }
    val nameLength = name.trim().length
    val goalLength = goal.trim().length
    AlertDialog(onDismissRequest = onDismiss, title = { Text("项目文字") }, text = {
        ProjectTextFields(name, goal, { name = it }, { goal = it }, enabled, message)
    }, confirmButton = { TextButton(onClick = { onConfirm(name.trim(), goal.trim()) },
        enabled = enabled && nameLength in 1..120 && goalLength <= 1_000) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = canDismiss) { Text("取消") } })
}

/** The actual dialog body is also rendered directly by Layoutlib, which omits popup windows. */
@Composable
internal fun ProjectTextFields(name: String, goal: String, onName: (String) -> Unit, onGoal: (String) -> Unit,
    enabled: Boolean, message: String? = null) {
    val nameLength = name.trim().length
    val goalLength = goal.trim().length
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, onName, label = { Text("项目名称") }, enabled = enabled, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = nameLength > 120,
                supportingText = { Text("$nameLength / 120") })
            OutlinedTextField(goal, onGoal, label = { Text("开场说明（选填）") }, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, isError = goalLength > 1_000,
                supportingText = { Text("$goalLength / 1000 · 观看者会看到这段文字") })
            Text("保存只更新草稿。固定候选及已封存版本保留原文字，须重新生成并复核。",
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ProjectTitleDialog(title: String, initialTitle: String, initialGoal: String, showGoal: Boolean,
    enabled: Boolean, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    var goal by rememberSaveable(initialGoal) { mutableStateOf(initialGoal) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("项目名称") }, enabled = enabled, singleLine = true)
            if (showGoal) OutlinedTextField(goal, { goal = it }, label = { Text("演示目标（选填）") }, enabled = enabled)
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(name.trim(), goal.trim()) }, enabled = enabled && name.isNotBlank()) { Text(if (showGoal) "创建" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** Confirmation uses a frozen project revision; the store rechecks it before deleting. */
@Composable
internal fun StepDeleteDialog(snapshot: ProjectSnapshot, stepId: String, hotspotCount: Int, nextActionCount: Int,
    current: Boolean, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val step = snapshot.steps.firstOrNull { it.id == stepId } ?: return
    fun label(item: ProjectStep) = "步骤 ${(snapshot.steps.indexOf(item) + 1).toString().padStart(2, '0')} · ${item.title}"
    val startImpact = if (snapshot.project.startStepId != stepId) "" else {
        snapshot.steps.firstOrNull { it.id != stepId }?.let { "删除后，当前${label(it)}将成为起点。" }
            ?: "删除后项目将没有步骤和起点。"
    }
    val message = if (!current) "项目内容已变化，请取消后重新检查删除影响。"
        else "步骤、图片及 $hotspotCount 个关联热点将删除，包括未保存草稿中指向它的热点。$nextActionCount 个下一步动作受影响；指向此步的下一步保留为待补目标。原录屏保留。$startImpact"
    AlertDialog(onDismissRequest = onDismiss, title = { Text("删除${label(step)}？") },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled && current) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ConfirmDelete(title: String, message: String, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal data class ProjectSourceUsage(val projectId: String, val title: String, val sourceCount: Int?, val byteLength: Long?)

@Composable
internal fun RetainedSourcesScreen(state: ProjectUiState, projectSources: List<ProjectSourceUsage>?, mediaBusy: Boolean,
    onBack: () -> Unit, onOpenProject: (String) -> Unit, onOpen: (String?) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("本机保留素材", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("项目中的原录屏", "打开项目素材页，进入“手动校正”后，可从“素材”菜单管理原片。")
            when {
                projectSources == null -> StatusNote("正在读取项目素材占用。")
                projectSources.isEmpty() -> Text("项目中没有保留原录屏。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
                else -> projectSources.forEach { item ->
                    val usage = if (item.sourceCount != null && item.byteLength != null)
                        "${item.sourceCount} 段原录屏 · ${formatShellBytes(item.byteLength)}" else "占用暂时无法读取"
                    ShellActionRow(item.title, "$usage\n项目 ${item.projectId.take(12)}", onClick = { onOpenProject(item.projectId) },
                        enabled = !state.busy && !state.loadFailed && !mediaBusy)
                    ShellDivider()
                }
            }
            SectionHeader("其他保留原录屏", "旧工作台及已删除项目的素材，仍保存在这台设备。")
            if (state.retainedMediaWorkspaces.isEmpty()) Text("没有其他保留素材。", style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            state.retainedMediaWorkspaces.forEach { item ->
                ShellActionRow(item.label, "${item.sourceCount} 段原录屏", onClick = { onOpen(item.projectId) }, enabled = !state.busy && !state.loadFailed && !mediaBusy)
                ShellDivider()
            }
        }
    }
}

/** Only reviewed, checksum-verified project PNGs are eligible for a thumbnail. */
@Composable
private fun ReviewedThumbnail(projects: ProjectWorkspace, snapshot: ProjectSnapshot?, asset: StepAsset): Bitmap? {
    val projectId = snapshot?.project?.id
    val stepId = snapshot?.steps?.firstOrNull { it.asset.id == asset.id }?.id
    val bitmap by produceState<Bitmap?>(null, projectId, stepId, asset.sha256) {
        value = if (projectId != null && stepId != null) projects.loadReviewedThumbnail(projectId, stepId, asset) else null
    }
    return bitmap
}

/** Only the fixed loopback service's generated share route may leave the app. */
internal fun isLocalHostedShareUrl(url: String): Boolean = runCatching {
    com.tapscene.hosting.HostedApi.validateShareUrl(url) == url
}.getOrDefault(false)
