package com.tapscene.clickplan

import android.graphics.Bitmap
import com.tapscene.data.ProjectLimits
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.recording.FrameEvidenceCandidate
import com.tapscene.recording.FrameMissingReason
import kotlin.math.max
import kotlin.math.min

/** Technical evidence stays private. Selecting it is never an output/privacy review. */
internal data class ClickChainActionEvidence(val index: Int, val action: ClickAction,
    val status: ClickActionStatus, val before: FrameEvidenceCandidate?, val after: FrameEvidenceCandidate?,
    val beforeMissing: FrameMissingReason?, val afterMissing: FrameMissingReason?) {
    val complete: Boolean get() = status == ClickActionStatus.Completed && before != null && after != null &&
        before.ticket.epoch == after.ticket.epoch && before.ticket.captureSequence <= after.ticket.captureSequence &&
        before.ticket.geometry == after.ticket.geometry
}

internal data class ClickChainCapture(val run: ClickRun, val source: ImportedSource,
    val actions: List<ClickChainActionEvidence>) {
    val frames: Map<String, FrameEvidenceCandidate> = buildMap {
        actions.flatMap { listOfNotNull(it.before, it.after) }.forEach { frame ->
            val key = frame.key
            val previous = get(key)
            require(previous == null || sameCapturedFrame(previous, frame)) { "同一采集画面记录不一致，请重新读取。" }
            if (previous == null) put(key, frame)
        }
    }
}

internal val FrameEvidenceCandidate.key: String get() = "${ticket.action.sessionId}:${ticket.sourceFrameId}"
internal fun sameCapturedFrame(a: FrameEvidenceCandidate, b: FrameEvidenceCandidate): Boolean =
    a.key == b.key && a.pngSha256 == b.pngSha256 && a.width == b.width && a.height == b.height &&
        a.sourceSha256 == b.sourceSha256 && a.ticket.captureSequence == b.ticket.captureSequence && a.ticket.sourceTimestampNs == b.ticket.sourceTimestampNs &&
        a.ticket.geometry == b.ticket.geometry && a.ticket.submittedPtsUs == b.ticket.submittedPtsUs &&
        a.encoderPtsUs == b.encoderPtsUs && a.muxSampleOrdinal == b.muxSampleOrdinal && a.containerPtsUs == b.containerPtsUs

internal data class ClickChainStage(val index: Int, val choices: List<ClickChainStageChoice>, val selectedKey: String?)
internal data class ClickChainStageChoice(val frameKey: String, val label: String)
internal data class ClickChainRoute(val actions: List<ClickChainActionEvidence>, val stages: List<ClickChainStage>,
    val issue: String? = null) {
    val frameKeys: List<String> get() = stages.mapNotNull { it.selectedKey }.distinct()
}

/** A representative is explicitly chosen, never an assertion that two different frames match. */
internal object ClickChainRoutePolicy {
    fun route(capture: ClickChainCapture, first: Int, last: Int, choices: Map<Int, String>): ClickChainRoute {
        if (first !in 1..capture.actions.size || last !in first..capture.actions.size)
            return ClickChainRoute(emptyList(), emptyList(), "请输入有效的动作范围。")
        val selected = capture.actions.subList(first - 1, last)
        val stages = (0..selected.size).map { position ->
            val previous = selected.getOrNull(position - 1)
            val next = selected.getOrNull(position)
            val candidates = buildList {
                previous?.after?.let { add(ClickChainStageChoice(it.key, "上一动作后")) }
                next?.before?.let { add(ClickChainStageChoice(it.key, if (position == 0) "起始画面" else "下一动作前")) }
            }.distinctBy { it.frameKey }
            val index = first - 1 + position
            val requested = choices[index]
            ClickChainStage(index, candidates, requested?.takeIf { key -> candidates.any { it.frameKey == key } }
                ?: next?.before?.key ?: previous?.after?.key)
        }
        val incomplete = selected.firstOrNull { !it.complete }
        val gap = selected.zipWithNext().firstOrNull { (a, b) ->
            val after = a.after; val before = b.before
            after == null || before == null || after.ticket.epoch != before.ticket.epoch ||
                after.ticket.captureSequence > before.ticket.captureSequence || after.ticket.geometry != before.ticket.geometry
        }
        val issue = when {
            incomplete != null -> "动作 ${incomplete.index + 1} 的前后画面或完成结果待补，请选择完整连续段，或仅加入画面。"
            gap != null -> "动作 ${gap.first.index + 1} 与 ${gap.second.index + 1} 之间有中断，请分段生成。"
            stages.any { it.selectedKey == null } -> "这一段仍缺少画面，请先补充或缩小范围。"
            stages.mapNotNull { it.selectedKey }.distinct().size > ProjectLimits.MAX_STEPS -> "这一段超过 40 个步骤，请缩小动作范围分段生成。"
            else -> null
        }
        return ClickChainRoute(selected, stages, issue)
    }

    fun point(action: ClickAction, frame: FrameEvidenceCandidate): Pair<Float, Float> {
        val geometry = frame.ticket.geometry
        require(action.x < geometry.displayWidth && action.y < geometry.displayHeight)
        return ((action.x * geometry.scale + geometry.offsetX) / geometry.frameWidth).toFloat() to
            ((action.y * geometry.scale + geometry.offsetY) / geometry.frameHeight).toFloat()
    }

    fun initialRect(action: ClickAction, frame: FrameEvidenceCandidate): OpaqueMask {
        val (x, y) = point(action, frame)
        val rx = max(2.0, 14.0 * frame.ticket.geometry.scale).toFloat() / frame.width
        val ry = max(2.0, 14.0 * frame.ticket.geometry.scale).toFloat() / frame.height
        return OpaqueMask(max(0f, x - rx), max(0f, y - ry), min(1f, x + rx), min(1f, y + ry))
    }
}

internal data class ClickChainFrameRow(val key: String, val title: String, val reviewed: Boolean,
    val selected: Boolean = true, val thumbnail: Bitmap? = null)
internal data class ClickChainStageRow(val index: Int, val title: String, val choices: List<ClickChainStageChoice>,
    val selectedKey: String?, val reviewed: Boolean)
internal data class ClickChainActionRow(val actionId: String, val number: Int, val status: String,
    val fromTitle: String, val toTitle: String, val confirmed: Boolean, val enabled: Boolean,
    val detail: String? = null)
internal data class ClickChainFrameEditor(val key: String, val title: String, val bitmap: Bitmap?,
    val masks: List<OpaqueMask>, val output: Bitmap?, val outputDigest: String?, val reviewed: Boolean)
internal data class ClickChainActionEditor(val actionId: String, val label: String, val rect: OpaqueMask,
    val bitmap: Bitmap?, val targetThumbnail: Bitmap?, val targetTitle: String,
    val point: Pair<Float, Float>, val selfLoop: Boolean, val samePageActionCount: Int)
internal data class ClickChainReviewUiState(val runId: String? = null, val title: String = "点击链演示",
    val runLabel: String = "", val firstAction: String = "1", val lastAction: String = "1",
    val totalActions: Int = 0, val framesOnly: Boolean = false,
    val stages: List<ClickChainStageRow> = emptyList(), val actions: List<ClickChainActionRow> = emptyList(),
    val frames: List<ClickChainFrameRow> = emptyList(), val frameCount: Int = 0,
    val reviewedFrameCount: Int = 0, val confirmedActionCount: Int = 0,
    val markLastTerminal: Boolean = false, val issue: String? = null,
    val loading: Boolean = false, val busy: Boolean = false, val message: String? = null,
    val frameEditor: ClickChainFrameEditor? = null, val actionEditor: ClickChainActionEditor? = null,
    val createdProjectId: String? = null, val outcomeUnknown: Boolean = false,
    val canCreate: Boolean = false, val editingLocked: Boolean = false, val retryCreate: Boolean = false)
