package com.tapscene.ui

import com.tapscene.data.EditorFormKind
import com.tapscene.data.EditorDraftFields
import com.tapscene.data.EditorPendingForm
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.ProjectNextAction

/** Three-way author-field merge. Media is deliberately absent; callers reattach current bindings. */
internal object EditorDraftReconciliation {
    data class Result(val fields: EditorDraftFields, val conflicts: Set<String>)

    fun merge(base: EditorDraftFields, edit: EditorDraftFields, saved: EditorDraftFields,
        useSavedConflicts: Boolean = false, baseline: Boolean = false): Result {
        val conflicts = mutableSetOf<String>()
        fun <T> field(label: String, old: T, local: T, actual: T): T = when {
            local == old -> actual
            actual == old || local == actual -> if (baseline) actual else local
            else -> { conflicts += label; if (baseline) old else if (useSavedConflicts) actual else local }
        }
        fun hotspot(old: ProjectHotspot?, local: ProjectHotspot?, actual: ProjectHotspot?): ProjectHotspot? {
            if (old == null || local == null || actual == null) return field("热点", old, local, actual)
            return local.copy(
                label = field("热点", old.label, local.label, actual.label),
                rect = field("热点", old.rect, local.rect, actual.rect),
                edgeId = field("热点", old.edgeId, local.edgeId, actual.edgeId),
                targetStepId = field("热点", old.targetStepId to old.endLabel,
                    local.targetStepId to local.endLabel, actual.targetStepId to actual.endLabel).first,
                endLabel = field("热点", old.targetStepId to old.endLabel,
                    local.targetStepId to local.endLabel, actual.targetStepId to actual.endLabel).second,
                transition = null,
            )
        }
        fun next(old: ProjectNextAction?, local: ProjectNextAction?, actual: ProjectNextAction?): ProjectNextAction? {
            if (old == null || local == null || actual == null || old.id != local.id || old.id != actual.id)
                return field("下一步", old, local, actual)
            return local.copy(label = field("下一步", old.label, local.label, actual.label),
                targetStepId = field("下一步", old.targetStepId, local.targetStepId, actual.targetStepId), transition = null)
        }
        val before = base.hotspots.associateBy { it.id }
        val local = edit.hotspots.associateBy { it.id }
        val actual = saved.hotspots.associateBy { it.id }
        val spots = (edit.hotspots.map { it.id } + saved.hotspots.map { it.id } + base.hotspots.map { it.id })
            .distinct().mapNotNull { hotspot(before[it], local[it], actual[it]) }.sortedBy { it.id }
        return Result(EditorDraftFields(
            title = field("标题", base.title, edit.title, saved.title),
            description = field("讲解", base.description, edit.description, saved.description),
            isTerminal = field("终点", base.isTerminal, edit.isTerminal, saved.isTerminal),
            hotspots = spots, nextAction = next(base.nextAction, edit.nextAction, saved.nextAction)), conflicts)
    }

    /** Pending panels participate in the same merge without parsing or dropping incomplete input. */
    fun mergePending(form: EditorPendingForm?, base: EditorDraftFields, saved: EditorDraftFields,
        useSavedConflicts: Boolean = false): Pair<EditorPendingForm?, Set<String>> {
        if (form == null) return null to emptySet()
        val conflicts = mutableSetOf<String>()
        fun <T> field(label: String, old: T, local: T, actual: T): T = when {
            local == old -> actual
            actual == old || local == actual -> local
            else -> { conflicts += label; if (useSavedConflicts) actual else local }
        }
        fun percent(value: Float) = (value * 100f).toString().removeSuffix(".0")
        val result = when (form.kind) {
            EditorFormKind.NAME -> form.copy(
                title = field("标题", base.title, form.title, saved.title),
                description = field("讲解", base.description, form.description, saved.description))
            EditorFormKind.HOTSPOT -> {
                val old = base.hotspots.firstOrNull { it.id == form.objectId }
                val actual = saved.hotspots.firstOrNull { it.id == form.objectId }
                if (old != null && actual == null) {
                    conflicts += "热点"
                    if (useSavedConflicts) null else form
                } else if (old == null || actual == null) form
                else {
                    val destination = field("热点", old.targetStepId to old.endLabel,
                        (if (form.endsDemo) null else form.targetStepId) to (if (form.endsDemo) form.endLabel else null),
                        actual.targetStepId to actual.endLabel)
                    // A rectangle is one semantic field; never combine incompatible bounds from two edits.
                    val rectangle = field("热点",
                        listOf(old.rect.left, old.rect.top, old.rect.right, old.rect.bottom).map(::percent),
                        listOf(form.left, form.top, form.right, form.bottom),
                        listOf(actual.rect.left, actual.rect.top, actual.rect.right, actual.rect.bottom).map(::percent))
                    form.copy(
                    label = field("热点", old.label, form.label, actual.label),
                    left = rectangle[0], top = rectangle[1], right = rectangle[2], bottom = rectangle[3],
                    targetStepId = destination.first,
                    endsDemo = destination.second != null,
                    endLabel = destination.second ?: form.endLabel)
                }
            }
            EditorFormKind.NEXT_ACTION -> {
                // There is one next-action slot per step, even if a concurrent writer used a different ID.
                val old = base.nextAction
                val actual = saved.nextAction
                if ((old == null && actual != null) || (old != null && actual?.id != old.id)) {
                    conflicts += "下一步"
                    if (useSavedConflicts) null else form
                } else if (old == null || actual == null) form
                else form.copy(label = field("下一步", old.label, form.label, actual.label),
                    targetStepId = field("下一步", old.targetStepId, form.targetStepId, actual.targetStepId))
            }
        }
        return result to conflicts
    }

    /** Advance each non-conflicting baseline field even while another field awaits a choice. */
    fun advanceBase(base: EditorDraftFields, edit: EditorDraftFields, saved: EditorDraftFields,
        pending: EditorPendingForm?): EditorDraftFields {
        var next = merge(base, edit, saved, baseline = true).fields
        if (pending == null) return next
        fun <T> conflict(old: T, local: T, actual: T) = local != old && actual != old && local != actual
        fun percent(value: Float) = (value * 100f).toString().removeSuffix(".0")
        when (pending.kind) {
            EditorFormKind.NAME -> next = next.copy(
                title = if (conflict(base.title, pending.title, saved.title)) base.title else next.title,
                description = if (conflict(base.description, pending.description, saved.description)) base.description else next.description)
            EditorFormKind.HOTSPOT -> {
                val old = base.hotspots.firstOrNull { it.id == pending.objectId }
                val actual = saved.hotspots.firstOrNull { it.id == pending.objectId }
                if (old != null && actual == null) next = next.copy(hotspots =
                    (next.hotspots.filterNot { it.id == old.id } + old).sortedBy { it.id })
                else if (old != null && actual != null) next = next.copy(hotspots = next.hotspots.map { spot ->
                    if (spot.id != old.id) spot else {
                        val rectConflict = conflict(
                            listOf(old.rect.left, old.rect.top, old.rect.right, old.rect.bottom).map(::percent),
                            listOf(pending.left, pending.top, pending.right, pending.bottom),
                            listOf(actual.rect.left, actual.rect.top, actual.rect.right, actual.rect.bottom).map(::percent))
                        val destinationConflict = conflict(old.targetStepId to old.endLabel,
                            (if (pending.endsDemo) null else pending.targetStepId) to (if (pending.endsDemo) pending.endLabel else null),
                            actual.targetStepId to actual.endLabel)
                        spot.copy(label = if (conflict(old.label, pending.label, actual.label)) old.label else spot.label,
                            rect = if (rectConflict) old.rect else spot.rect,
                            targetStepId = if (destinationConflict) old.targetStepId else spot.targetStepId,
                            endLabel = if (destinationConflict) old.endLabel else spot.endLabel)
                    }
                })
            }
            EditorFormKind.NEXT_ACTION -> {
                val old = base.nextAction
                val actual = saved.nextAction
                if ((old == null && actual != null) || (old != null && actual?.id != old.id)) next = next.copy(nextAction = old)
                else if (old != null && actual != null) next = next.copy(nextAction = next.nextAction?.let {
                    it.copy(label = if (conflict(old.label, pending.label, actual.label)) old.label else it.label,
                        targetStepId = if (conflict(old.targetStepId, pending.targetStepId, actual.targetStepId)) old.targetStepId else it.targetStepId)
                })
            }
        }
        return next
    }

    /** Deleted destinations cannot be resurrected, including from a not-yet-applied panel. */
    fun prune(fields: EditorDraftFields, stepIds: Set<String>) = fields.copy(
        hotspots = fields.hotspots.filter { it.targetStepId == null || it.targetStepId in stepIds },
        nextAction = fields.nextAction?.let { if (it.targetStepId != null && it.targetStepId !in stepIds)
            it.copy(targetStepId = null, transition = null) else it },
    )

    fun prune(form: EditorPendingForm?, stepIds: Set<String>): EditorPendingForm? = form?.let {
        if (it.targetStepId != null && it.targetStepId !in stepIds) it.copy(targetStepId = null) else it
    }
}
