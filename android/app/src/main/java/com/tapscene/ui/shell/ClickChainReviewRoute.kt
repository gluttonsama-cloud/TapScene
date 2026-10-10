package com.tapscene.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tapscene.ui.ClickChainWorkspace

/** Keeps the author's original click-plan inputs mounted while this focused workbench is open. */
@Composable
internal fun ClickChainReviewRoute(runId: String, onBack: () -> Unit, onOpenProject: (String) -> Unit) {
    val workspace: ClickChainWorkspace = viewModel(key = "click-chain-review")
    val state by workspace.state.collectAsStateWithLifecycle()
    LaunchedEffect(runId) { workspace.activate(runId) }
    ClickChainReviewContent(state, ClickChainReviewCallbacks(
        onBack = { workspace.leave(onBack) },onTitleChange = workspace::title,onRangeChange = workspace::range,
        onFramesOnly = workspace::framesOnly,onStageChoice = workspace::stageChoice,onToggleFrame = workspace::toggleFrame,
        onReviewFrame = workspace::reviewFrame,onReviewAction = workspace::reviewAction,onMarkLastTerminal = workspace::markTerminal,
        onCreate = workspace::createProject,onOpenProject = { projectId -> workspace.leave { onOpenProject(projectId) } },
        onRetry = workspace::retry,onReloadSaved = workspace::reloadSaved,onCancel = workspace::cancel,onCloseEditor = workspace::closeEditor,
        onFrameTitleChange = workspace::frameTitle,onAddMask = workspace::addMask,onUndoMask = workspace::undoMask,
        onGenerateFrame = workspace::generateFrame,onConfirmFrame = workspace::confirmFrame,
        onActionLabelChange = workspace::actionLabel,onActionRectChange = workspace::actionRect,onConfirmAction = workspace::confirmAction,
    ))
}
