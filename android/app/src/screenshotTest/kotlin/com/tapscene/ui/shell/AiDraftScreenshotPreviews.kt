package com.tapscene.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.AiDraftImportPreview
import com.tapscene.data.DraftAiConfig
import com.tapscene.data.DraftAiEffect
import com.tapscene.data.ProjectNextAction
import com.tapscene.packageformat.AiDraftImportPolicy
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerScene
import com.tapscene.ui.AiDraftImportUiState
import com.tapscene.ui.DraftAiEdits
import com.tapscene.ui.DraftAiPlanUiState
import java.util.UUID

/** Synthetic in-memory states render the production screens. No storage, review or device claims. */
private object AiDraftPreviewFixture {
    fun id(value: String) = UUID.nameUUIDFromBytes("ai-draft-preview-$value".toByteArray()).toString()
    val scene = ReleasePreviewFixture.scene
    private val visits = scene.states.mapIndexed { index, step -> RenderPlan.Visit(id("visit-$index"), step.id,
        scene.edges.firstOrNull { it.fromStateId == step.id && it.trigger == "continue" }?.id, 120) }
    private val effects = listOf(
        RenderPlan.Effect("annotation", visits.first().visitId, 0, 60, null, null,
            "先核对活动名称、场次、时间与参加者。这里保留外部 AI 返回的完整长标注，不会因手机显示宽度截断或合并另一项标注。",
            ViewerScene.Rect(.08, .08, .84, .14)),
        RenderPlan.Effect("annotation", visits.first().visitId, 60, 60, null, null,
            "同种类型的第二项标注独立保留。", ViewerScene.Rect(.08, .7, .84, .14)),
        RenderPlan.Effect("focus", visits[1].visitId, 0, 90, null, null, null, ViewerScene.Rect(.1, .65, .8, .2)),
    )
    val plan = RenderPlan.build(scene, 1080, 1920, visits, effects)
    private val policy = AiDraftImportPolicy.review(scene, plan, null)
    val preview = AiDraftImportPreview(id("session"), "b".repeat(64), id("new-project"), scene, plan,
        null, null, policy.issues, policy.differences, policy.summary, policy.trustNotice, scene.assets.sumOf { it.byteLength })
    val importing = AiDraftImportUiState(sessionId = preview.sessionId, preview = preview, baselines = listOf(ReleasePreviewFixture.summary))
    val longProject = ShellPreviewFixture.project.let { base ->
        val steps = (0..19).map { index -> base.steps[index % base.steps.size].copy(id = "preview-step-$index", sortOrder = index,
            title = "第 ${index + 1} 步：核对报名资料与参加者的完整信息", description = "这是一段用于检查长文本换行和访问引用的合成讲解。",
            hotspots = emptyList(), isTerminal = index == 19, nextAction = if (index < 19) ProjectNextAction("preview-edge-$index", "确认当前填写的信息并继续下一步", "preview-step-${index + 1}") else null) }
        base.copy(project = base.project.copy(stepCount = 20, startStepId = steps.first().id, title = "活动报名 · 二十次访问与独立效果"), steps = steps)
    }
    val draftVisits = longProject.steps.mapIndexed { index, step -> RenderPlan.Visit(id("draft-visit-$index"), step.id, step.nextAction?.id, 120) }
    val draftEffects = (0..5).map { index -> DraftAiEffect(id("effect-$index"), RenderPlan.Effect("annotation", draftVisits.first().visitId,
        if (index % 2 == 0) 0 else 60, 60, null, null,
        if (index == 0) effects.first().text else "独立标注 ${index + 1}，保存后仍是单独的一项。", ViewerScene.Rect(.08, .08, .84, .14))) }
    val config = DraftAiConfig(1, false, 1080, 1920, draftVisits, draftEffects)
    val draft = DraftAiPlanUiState(project = longProject, config = config, dirty = true)
    val stale = draft.copy(config = config.copy(needsRepair = true, visits = listOf(RenderPlan.Visit(draftVisits.first().visitId,
        "deleted-step", "deleted-edge", 20)) + draftVisits.drop(1)), issues = listOf("第 1 次访问的步骤已删除，请重新选择或明确移除。", "效果 1 超出当前停留时间；参数已保留，请调整。"))
}

@PreviewTest
@Preview(name = "ai_import_review_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun AiImportReviewSmallPreview() = AiDraftSurface { ImportFixture(AiDraftPreviewFixture.importing) }

@PreviewTest
@Preview(name = "ai_import_text_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun AiImportTextLandscapePreview() = AiDraftSurface { ImportFixture(AiDraftPreviewFixture.importing, 1) }

@PreviewTest
@Preview(name = "ai_import_plan_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun AiImportPlanSmallPreview() = AiDraftSurface { ImportFixture(AiDraftPreviewFixture.importing, 2) }

@PreviewTest
@Preview(name = "ai_import_unknown_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun AiImportUnknownSmallPreview() = AiDraftSurface {
    ImportFixture(AiDraftPreviewFixture.importing.copy(outcomeUnknown = true,
        message = "暂时无法确认本次导入结果。请点“重读结果”；确认前不能重复创建或重新选择。"))
}

@PreviewTest
@Preview(name = "draft_ai_long_path_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun DraftAiLongPathLandscapePreview() = AiDraftSurface {
    DraftAiPlanContent(AiDraftPreviewFixture.draft, DraftAiPlanCallbacks(), initialVisitId = AiDraftPreviewFixture.draftVisits.first().visitId)
}

@PreviewTest
@Preview(name = "draft_ai_effect_detail_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun DraftAiEffectDetailSmallPreview() = AiDraftSurface {
    DraftAiPlanContent(AiDraftPreviewFixture.draft, DraftAiPlanCallbacks(), initialSection = 1,
        initialEffectId = AiDraftPreviewFixture.draftEffects.first().id)
}

@PreviewTest
@Preview(name = "draft_ai_stale_reference_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun DraftAiStaleReferenceSmallPreview() = AiDraftSurface {
    DraftAiPlanContent(AiDraftPreviewFixture.stale, DraftAiPlanCallbacks(), initialVisitId = AiDraftPreviewFixture.draftVisits.first().visitId)
}

@PreviewTest
@Preview(name = "draft_ai_path_loss_confirmation_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun DraftAiPathLossConfirmationSmallPreview() = AiDraftSurface {
    val fixture = AiDraftPreviewFixture
    val change = DraftAiEdits.restart(fixture.config, fixture.longProject)
    DraftAiPlanContent(fixture.draft.copy(pendingPath = change), DraftAiPlanCallbacks())
}

@Composable
private fun ImportFixture(state: AiDraftImportUiState, section: Int = 0) {
    AiDraftImportContent(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, initialSection = section)
}

@Composable
private fun AiDraftSurface(content: @Composable () -> Unit) {
    TapSceneTheme {
        Surface {
            Column(Modifier.fillMaxSize()) {
                Text("布局样例 · 合成内容 · 非设备或触控验证", Modifier.fillMaxWidth().background(ShellColors.AccentSoft).padding(8.dp),
                    style = MaterialTheme.typography.labelSmall)
                content()
            }
        }
    }
}
