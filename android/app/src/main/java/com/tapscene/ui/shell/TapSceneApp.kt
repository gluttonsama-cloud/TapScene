package com.tapscene.ui.shell

import android.graphics.Bitmap
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapscene.data.*
import com.tapscene.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The shell routes existing capabilities; unavailable services never manufacture project data. */
@Composable
fun TapSceneApp(projects: ProjectWorkspace, media: MediaWorkspace) {
    val state by projects.state.collectAsStateWithLifecycle()
    val mediaState by media.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    var library by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(ProjectTab.STEPS) }
    val pages = rememberSaveable(saver = listSaver(
        save = { it.toList() }, restore = { it.toMutableStateList() },
    )) { mutableStateListOf<String>() }
    val page = pages.lastOrNull()
    val push: (String) -> Unit = { if (pages.lastOrNull() != it) pages.add(it) }
    val pop: () -> Unit = { if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) }
    var newProject by rememberSaveable { mutableStateOf(false) }
    var importAfterCreate by rememberSaveable { mutableStateOf(false) }
    var awaitingCreatedProject by rememberSaveable { mutableStateOf(false) }
    var importRequested by rememberSaveable { mutableStateOf(false) }
    var pickerProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deletingProject by remember { mutableStateOf<ProjectSummary?>(null) }
    var deletingStep by remember { mutableStateOf<ProjectStep?>(null) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var projectMore by rememberSaveable { mutableStateOf(false) }
    var transitionHotspot by rememberSaveable { mutableStateOf<String?>(null) }
    var retainedMedia by remember { mutableStateOf(false) }
    val projectId = state.project?.project?.id
    val unavailable = state.busy || state.loadFailed
    LaunchedEffect(page, projectId, state.busy) {
        if (page == "transition" && projectId == null && !state.busy) pop()
    }
    val scopeReady = projectId != null && media.projectId == projectId && !retainedMedia
    val closeEditor: () -> Unit = {
        if (!state.busy) {
            if (state.stepDraft?.dirty == true) confirmLeave = true else projects.back()
        }
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
        if (!unavailable && !mediaState.busy) {
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
    BackHandler(enabled = page != null && !retainedMedia) { pop() }
    BackHandler(enabled = page == null && !retainedMedia && state.route == ProjectRoute.STEPS) {
        if (!state.busy && !mediaState.busy) projects.back()
    }
    BackHandler(enabled = page == null && state.route == ProjectRoute.PROJECTS && library) { library = false }

    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = ShellColors.Background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                if (!retainedMedia && state.route != ProjectRoute.MEDIA) {
                    if (state.busy || (scopeReady && mediaState.busy)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.busy) state.stage.orEmpty() else mediaState.stage.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { push("task") }) { Text("详情") }
                            TextButton(onClick = { if (state.busy) projects.cancel() else media.cancel() }) { Text("取消") }
                        }
                    }
                    val message = state.message ?: mediaState.message.takeIf { scopeReady && tab == ProjectTab.SOURCES }
                    message?.let {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = ShellColors.Accent)
                            TextButton(onClick = { projects.clearMessage(); media.clearMessage() }) { Text("关闭") }
                        }
                    }
                    if (state.loadFailed) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("项目暂时无法读取，已保存内容仍保留。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = projects::reload, enabled = !state.busy) { Text("重试") }
                    }
                }
                Box(Modifier.weight(1f)) {
                    when {
                        retainedMedia -> MediaScreen(media, onBack = { retainedMedia = false; projects.reload() })
                        page != null -> when (page) {
                            "record" -> RecordingSetupScreen(pop, requestImport)
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
                            "sources" -> RetainedSourcesScreen(state, onBack = pop) { id ->
                                if (media.activateProject(id)) retainedMedia = true
                                else projects.message(media.state.value.message ?: "请先完成当前素材处理。")
                            }
                            "candidates" -> Column(Modifier.fillMaxSize()) {
                                ShellTopBar("候选步骤", pop)
                                CandidatesContent(if (scopeReady) mediaState.drafts else emptyList(), { pop(); tab = ProjectTab.SOURCES })
                            }
                            "review" -> ReleaseReviewScreen(pop)
                            "delivery" -> DeliveryOptionsScreen(pop, { push("review") }, { push("ai") }, { push("account") }, { push("versions") })
                            "ai" -> AiPackageScreen(pop)
                            "account" -> HostingAccountScreen(pop, { push("versions") })
                            "versions" -> HostedVersionsScreen(pop, { push("account") })
                            "import" -> ExternalImportScreen(pop)
                            "player" -> FormalPlayerScreen(pop)
                            "task" -> TaskDetailsScreen(
                                if (state.busy || mediaState.busy) ShellTaskInfo("本机处理", if (state.busy) state.stage.orEmpty() else mediaState.stage.orEmpty(), state.project?.project?.title) else null,
                                pop, { pages.clear() }, { push("settings") })
                            "transition" -> state.project?.let { snapshot -> state.selectedStepId?.let { stepId ->
                                TransitionWorkspaceContent(snapshot, stepId, transitionHotspot, state.bitmap, unavailable, pop, draft = state.stepDraft)
                            } }
                        }
                        state.route == ProjectRoute.MEDIA -> {
                            val targetId = projectId
                            if (scopeReady && targetId != null) MediaScreen(media, projects::back) { input ->
                                projects.saveReviewedStep(targetId, input)
                            } else ScreenEmpty("素材未就绪", "请返回项目重新打开素材。", "返回", projects::back)
                        }
                        state.route == ProjectRoute.EDIT -> state.project?.let { snapshot -> state.stepDraft?.let { draft ->
                            EditorWorkspaceContent(snapshot, draft, state.bitmap, unavailable, EditorCallbacks(
                                closeEditor, projects::editTitle, projects::editDescription, projects::editTerminal,
                                projects::putHotspot, projects::removeHotspot, projects::saveStepDraft, projects::discardStepDraft,
                                { projects.startPreview(true) }, { transitionHotspot = it; push("transition") }),
                                previewEnabled = state.dirtyStepIds.isEmpty())
                        } }
                        state.route == ProjectRoute.PREVIEW -> state.project?.let { snapshot -> state.preview?.let { preview ->
                            DraftPreviewContent(snapshot, preview, state.bitmap, unavailable, projects::chooseHotspot,
                                projects::tapPreview, projects::previousPreview, projects::restartPreview, projects::exitPreview)
                        } }
                        state.route == ProjectRoute.STEPS -> ProjectWorkspaceFrame(
                            state.project?.project?.title.orEmpty(), tab, { if (!state.busy && !mediaState.busy) tab = it },
                            { if (!state.busy && !mediaState.busy) projects.back() }, { if (!unavailable) projects.startPreview() },
                            { projectMore = true },
                        ) {
                            when (tab) {
                                ProjectTab.STEPS -> StoryboardContent(state, projects::openStep, { tab = ProjectTab.SOURCES },
                                    projects::setStart, projects::moveStep, { deletingStep = it },
                                    stepThumbnail = { asset -> ReviewedThumbnail(projects, state.project, asset) })
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
                                    }, { push("candidates") })
                                }
                                ProjectTab.CHECKS -> DeliveryCheckScreen(state.project, state.issues, { tab = ProjectTab.STEPS },
                                    { issue -> issue.stepId?.let(projects::openStep) ?: run { tab = ProjectTab.STEPS } },
                                    { projects.startPreview() }, { push("review") }, { push("delivery") }, showTopBar = false)
                            }
                        }
                        library -> Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) { DemoLibraryScreen({ push("import") }, { push("settings") }) }
                            GlobalNavigation(true, { library = false }, {})
                        }
                        else -> ProjectHomeFrame({ if (!unavailable) { importAfterCreate = false; newProject = true } },
                            { push("record") }, requestImport, { push("settings") }, { library = true }) {
                            ProjectHomeContent(state, { tab = ProjectTab.STEPS; projects.openProject(it) }, { renaming = it }, { deletingProject = it })
                        }
                    }
                }
            }
            if (newProject) ProjectTitleDialog("新建项目", "", "", true, !state.busy, { newProject = false; importAfterCreate = false }) { title, goal ->
                newProject = false; pages.clear(); awaitingCreatedProject = true; projects.createProject(title, goal)
            }
            renaming?.let { item -> ProjectTitleDialog("项目名称", item.title, item.goal, false, !state.busy, { renaming = null }) { title, _ ->
                renaming = null; projects.renameProject(item.id, title)
            } }
            deletingProject?.let { item -> ConfirmDelete("删除“${item.title}”？",
                "项目、${item.stepCount} 个步骤、热点和项目图片将删除，无法撤销。原录屏与导出的文件保留；原录屏可在设置的本机保留素材中管理。", !state.busy,
                { deletingProject = null }, { deletingProject = null; projects.deleteProject(item.id) }) }
            deletingStep?.let { step -> ConfirmDelete("删除“${step.title}”？",
                "步骤、图片及 ${projects.deletionHotspotCount(step.id)} 个关联热点将删除，包括未保存草稿中指向它的热点。原录屏保留。${if (state.project?.project?.startStepId == step.id) "删除后需要重新设置起点。" else ""}", !state.busy,
                { deletingStep = null }, { deletingStep = null; projects.deleteStep(step.id) }) }
            if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("保留这次修改？") },
                text = { Text("返回后可继续编辑。尚未保存的修改只在本次应用运行中保留，关闭应用后可能丢失。") },
                confirmButton = { TextButton(onClick = { confirmLeave = false; projects.back() }) { Text("保留并返回") } },
                dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } })
            if (projectMore) AlertDialog(onDismissRequest = { projectMore = false }, title = { Text("项目") }, text = {
                Column {
                    ShellActionRow("重命名", onClick = { projectMore = false; renaming = state.project?.project })
                    ShellActionRow("任务详情", onClick = { projectMore = false; push("task") })
                    ShellActionRow("托管版本", onClick = { projectMore = false; push("versions") })
                    ShellActionRow("删除项目", onClick = { projectMore = false; deletingProject = state.project?.project })
                }
            }, confirmButton = { TextButton(onClick = { projectMore = false }) { Text("关闭") } })
        }
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

@Composable
private fun ConfirmDelete(title: String, message: String, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun RetainedSourcesScreen(state: ProjectUiState, onBack: () -> Unit, onOpen: (String?) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("本机保留素材", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("项目中的素材从项目页管理。这里保留旧工作台及已删除项目的原录屏。", style = MaterialTheme.typography.bodySmall)
            if (state.retainedMediaWorkspaces.isEmpty()) ScreenEmpty("没有保留素材", "项目仍在使用的素材不会列在这里。")
            state.retainedMediaWorkspaces.forEach { item ->
                ShellActionRow(item.label, "${item.sourceCount} 段原录屏", onClick = { onOpen(item.projectId) }, enabled = !state.busy && !state.loadFailed)
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
