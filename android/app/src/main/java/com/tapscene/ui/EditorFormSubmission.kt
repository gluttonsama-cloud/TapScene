package com.tapscene.ui

import com.tapscene.data.EditorFormKind
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.ProjectLimits
import com.tapscene.data.ProjectNextAction
import com.tapscene.data.ProjectStep
import com.tapscene.media.OpaqueMask

/** Build a submission, never mutate the recoverable panel before its transaction succeeds. */
internal fun StepEditDraft.withSubmittedForm(steps: List<ProjectStep>): StepEditDraft {
    val form = requireNotNull(pendingForm) { "面板已关闭，请重新打开后保存" }
    val submitted = when (form.kind) {
        EditorFormKind.NAME -> {
            require(form.title.isNotBlank()) { "请填写步骤名称" }
            copy(title = form.title.trim(), description = form.description.trim())
        }
        EditorFormKind.HOTSPOT -> {
            require(!isTerminal) { "请先取消终点标记，再保存热点" }
            require(form.label.isNotBlank()) { "请填写动作名称" }
            require(if (form.endsDemo) form.endLabel.isNotBlank() else steps.any { it.id == form.targetStepId }) {
                "请选择有效目标步骤或填写结束说明"
            }
            val objectId = requireNotNull(form.objectId) { "热点已变化，请重新打开" }
            val edgeId = requireNotNull(form.edgeId) { "热点动作已变化，请重新打开" }
            val original = hotspots.firstOrNull { it.id == objectId }
            require(original != null || hotspots.size < ProjectLimits.MAX_HOTSPOTS_PER_STEP) { "每步最多 6 个热点" }
            val values = listOf(form.left, form.top, form.right, form.bottom)
            // Label-only editing preserves the exact original coordinates, without rounding.
            val rect = if (original != null && values == listOf(original.rect.left, original.rect.top,
                original.rect.right, original.rect.bottom).map { (it * 100f).toString().removeSuffix(".0") }) original.rect
            else {
                val numbers = values.map { it.toFloatOrNull() }
                require(numbers.all { it != null && it.isFinite() && it in 0f..100f }) { "范围须在 0–100% 内" }
                val (left, top, right, bottom) = numbers.map { requireNotNull(it) / 100f }
                require(right > left && bottom > top) { "右边须大于左边，底部须大于顶部" }
                OpaqueMask(left, top, right, bottom)
            }
            val hotspot = ProjectHotspot(objectId, form.label.trim(), rect,
                if (form.endsDemo) null else form.targetStepId,
                if (form.endsDemo) form.endLabel.trim() else null, edgeId, original?.transition)
            copy(hotspots = if (original == null) hotspots + hotspot else hotspots.map { if (it.id == objectId) hotspot else it })
        }
        EditorFormKind.NEXT_ACTION -> {
            require(!isTerminal) { "请先取消终点标记，再保存下一步" }
            require(form.label.isNotBlank()) { "请填写按钮文字" }
            require(steps.any { it.id == form.targetStepId }) { "请选择有效的目标步骤" }
            val objectId = requireNotNull(form.objectId) { "下一步动作已变化，请重新打开" }
            val stableId = nextAction?.id ?: steps.firstOrNull { it.id == stepId }?.nextAction?.id ?: objectId
            copy(nextAction = ProjectNextAction(stableId,
                form.label.trim(), form.targetStepId, nextAction?.transition))
        }
    }
    return submitted.copy(pendingForm = null)
}
