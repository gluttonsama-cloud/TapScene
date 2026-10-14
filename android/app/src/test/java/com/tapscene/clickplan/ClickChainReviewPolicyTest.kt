package com.tapscene.clickplan

import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SourceMetadata
import com.tapscene.recording.*
import java.util.UUID
import org.junit.Test

/** Pure author-route policy; no recording, actual touch or human privacy judgment is simulated. */
class ClickChainReviewPolicyTest {
    @Test fun initialSnapshotNoticeDoesNotClaimDispatchTimeOrChangeReviewRequirements() {
        val original = capture(2)
        val first = original.actions.first()
        val before = checkNotNull(first.before)
        val metadata = InitialFrameAdmissionMetadata(100,15_100,120,1,0,250)
        val admitted = before.copy(ticket = before.ticket.copy(admission = metadata))
        val capture = original.copy(actions = listOf(first.copy(before = admitted)) + original.actions.drop(1))
        val route = ClickChainRoutePolicy.route(capture,1,2,emptyMap())
        check(route.issue == null && route.stages.first().choices.single().label == "首次点击前的初始快照")
        check(admitted.selectionNotice == INITIAL_SNAPSHOT_NOTICE && before.selectionNotice == null)
        check(sameCapturedFrame(before,admitted)) // Same source pixels; temporal selection semantics stay separate.
        check(capture.frames.getValue(admitted.key).ticket.admission == metadata)
        val pending = ClickChainReviewUiState(totalActions = 2,stages = listOf(reviewStage(0,admitted.key)),
            frames = listOf(ClickChainFrameRow(admitted.key,"初始画面",false,selectionNotice = admitted.selectionNotice)),
            actions = listOf(reviewAction(first.action.actionId).copy(selectionNotice = admitted.selectionNotice)))
        check(pending.nextRequiredReview() == ClickChainReviewTarget.Frame(admitted.key) && !pending.canCreate)
        check(pending.copy(stages = pending.stages.map { it.copy(reviewed = true) }).nextRequiredReview() ==
            ClickChainReviewTarget.Action(first.action.actionId))
    }

    @Test fun boundaryDedupRetainsAdmissionNoticeAndRejectsConflictingSelectionFacts() {
        val original = capture(1)
        val action = original.actions.single()
        val before = checkNotNull(action.before)
        val admitted = before.copy(ticket = before.ticket.copy(admission = InitialFrameAdmissionMetadata(100,15_100,120,1,0,250)))
        val reused = before.copy(ticket = before.ticket.copy(ticketId = id(),boundary = FrameBoundary.After,freshness = FrameFreshness.StaticReuse))
        val capture = original.copy(actions = listOf(action.copy(before = admitted,after = reused)))
        check(capture.frames.size == 1 && capture.frames.values.single().selectionNotice == INITIAL_SNAPSHOT_NOTICE)
        // Even if another boundary using those pixels is seen first, selection provenance is retained.
        val reverse = original.copy(actions = listOf(action.copy(before = before,after = null),action.copy(before = admitted,after = reused)))
        check(reverse.frames.values.single().ticket.admission == admitted.ticket.admission)
        val changed = admitted.copy(ticket = admitted.ticket.copy(admission = admitted.ticket.admission!!.copy(consumedAtUptimeMs = 251)))
        check(runCatching { original.copy(actions = listOf(action.copy(before = admitted),action.copy(before = changed))) }.isFailure)
    }

    @Test fun representativesKeepContinuityWithoutMergingDifferentCapturedFrames() {
        val capture = capture(2)
        val default = ClickChainRoutePolicy.route(capture,1,2,emptyMap())
        check(default.issue == null && default.frameKeys == listOf(capture.actions[0].before!!.key,capture.actions[1].before!!.key,capture.actions[1].after!!.key))
        val alternative = capture.actions[0].after!!
        val chosen = ClickChainRoutePolicy.route(capture,1,2,mapOf(1 to alternative.key))
        check(chosen.issue == null && chosen.frameKeys.size == 3 && chosen.stages[1].selectedKey == alternative.key)
        check(default.stages[1].choices.size == 2 && chosen.stages[1].choices.size == 2)
        val action = capture.actions[1].action
        val point = ClickChainRoutePolicy.point(action,alternative)
        val geometry = alternative.ticket.geometry
        check(point.first == ((action.x * geometry.scale + geometry.offsetX) / geometry.frameWidth).toFloat())
        check(point.second == ((action.y * geometry.scale + geometry.offsetY) / geometry.frameHeight).toFloat())
        val rectangle = ClickChainRoutePolicy.initialRect(action,alternative)
        check(point.first in rectangle.left..rectangle.right && point.second in rectangle.top..rectangle.bottom)
        // Different boundary tickets can reuse one captured frame and its fixed canonical sample.
        val reused = alternative.copy(ticket = alternative.ticket.copy(ticketId = id(),freshness = FrameFreshness.StaticReuse))
        check(sameCapturedFrame(alternative,reused))
        // Video repetition retains identity but cannot rebind an anchor to another presentation.
        val rebound = reused.copy(encoderPtsUs = 20_000,muxSampleOrdinal = 17,containerPtsUs = 19_000,
            ticket = reused.ticket.copy(submittedPtsUs = 20_000))
        check(rebound.key == alternative.key && !sameCapturedFrame(alternative,rebound))
        check(!sameCapturedFrame(alternative,reused.copy(pngSha256 = "b".repeat(64))))
    }

    @Test fun missingUnknownEpochBreaksAndLimitsNeverBecomeImplicitEdgesOrTruncation() {
        val capture = capture(2)
        val missing = capture.copy(actions = capture.actions.mapIndexed { i,a -> if (i == 0) a.copy(after = null,afterMissing = FrameMissingReason.NoNewFrame) else a })
        check(ClickChainRoutePolicy.route(missing,1,2,emptyMap()).issue != null)
        val unknown = capture.copy(actions = capture.actions.mapIndexed { i,a -> if (i == 0) a.copy(status = ClickActionStatus.Unknown) else a })
        check(ClickChainRoutePolicy.route(unknown,1,2,emptyMap()).issue != null)
        val otherEpoch = capture.actions[1].let { a -> a.copy(before = a.before!!.copy(ticket = a.before.ticket.copy(epoch = 1)),
            after = a.after!!.copy(ticket = a.after.ticket.copy(epoch = 1))) }
        check(ClickChainRoutePolicy.route(capture.copy(actions = listOf(capture.actions[0],otherEpoch)),1,2,emptyMap()).issue != null)
        val long = capture(40)
        val entire = ClickChainRoutePolicy.route(long,1,40,emptyMap())
        check(entire.frameKeys.size == 41 && entire.issue != null)
        check(ClickChainRoutePolicy.route(long,1,39,emptyMap()).let { it.frameKeys.size == 40 && it.issue == null })
        check(ClickChainRoutePolicy.route(long,0,40,emptyMap()).actions.isEmpty())
    }

    @Test fun nextReviewFollowsStageVisitsBeforeActionsWithoutMergingRevisits() {
        val state = ClickChainReviewUiState(totalActions = 3,lastAction = "3",
            stages = listOf(reviewStage(0,"a",true),reviewStage(1,"b"),reviewStage(2,"a"),reviewStage(3,"c")),
            frames = listOf(ClickChainFrameRow("unused","未选代表图",false)),
            actions = listOf(reviewAction("action-1")))
        check(state.nextRequiredReview() == ClickChainReviewTarget.Frame("b"))
        val secondReviewed = state.copy(stages = state.stages.map { if (it.index == 1) it.copy(reviewed = true) else it })
        check(secondReviewed.nextRequiredReview() == ClickChainReviewTarget.Frame("a"))
        val revisitReviewed = secondReviewed.copy(stages = secondReviewed.stages.map { if (it.index == 2) it.copy(reviewed = true) else it })
        check(revisitReviewed.nextRequiredReview() == ClickChainReviewTarget.Frame("c"))
        val framesReviewed = revisitReviewed.copy(stages = revisitReviewed.stages.map { it.copy(reviewed = true) })
        check(framesReviewed.nextRequiredReview() == ClickChainReviewTarget.Action("action-1"))
    }

    @Test fun nextReviewUsesChosenRepresentativeAndNeverFabricatesMissingOrStaleKeys() {
        val choices = listOf(ClickChainStageChoice("previous-after","上一动作后"),ClickChainStageChoice("next-before","下一动作前"))
        val missing = ClickChainStageRow(0,"画面待补",choices,null,false)
        val stale = ClickChainStageRow(1,"失效代表图",choices,"outside-choices",false)
        val selected = ClickChainStageRow(2,"所选代表图",choices,"next-before",false)
        val state = ClickChainReviewUiState(totalActions = 2,lastAction = "2",stages = listOf(missing,stale,selected))
        check(state.nextRequiredReview() == ClickChainReviewTarget.Frame("next-before"))
        check(state.copy(stages = listOf(missing,stale)).nextRequiredReview() == null)
        check(state.copy(stages = listOf(selected.copy(selectedKey = "previous-after"))).nextRequiredReview() ==
            ClickChainReviewTarget.Frame("previous-after"))
    }

    @Test fun nextReviewAfterRangeNarrowingUsesOnlyCurrentRouteFramesAndActions() {
        val capture = capture(3)
        fun stateFor(first: Int,last: Int): ClickChainReviewUiState {
            val path = ClickChainRoutePolicy.route(capture,first,last,emptyMap())
            return ClickChainReviewUiState(firstAction = first.toString(),lastAction = last.toString(),totalActions = 3,
                stages = path.stages.map { ClickChainStageRow(it.index,"阶段 ${it.index}",it.choices,it.selectedKey,false) },
                actions = path.actions.map { reviewAction(it.action.actionId,it.index + 1) })
        }
        val full = stateFor(1,3)
        check(full.nextRequiredReview() == ClickChainReviewTarget.Frame(capture.actions[0].before!!.key))
        val narrowed = stateFor(2,2)
        check(narrowed.validationIssue() == null)
        check(narrowed.nextRequiredReview() == ClickChainReviewTarget.Frame(capture.actions[1].before!!.key))
        val firstReviewed = narrowed.copy(stages = narrowed.stages.mapIndexed { index,stage -> stage.copy(reviewed = index == 0) })
        check(firstReviewed.nextRequiredReview() == ClickChainReviewTarget.Frame(capture.actions[1].after!!.key))
        val framesReviewed = narrowed.copy(stages = narrowed.stages.map { it.copy(reviewed = true) })
        check(framesReviewed.nextRequiredReview() == ClickChainReviewTarget.Action(capture.actions[1].action.actionId))
        check(framesReviewed.copy(actions = framesReviewed.actions.map { it.copy(confirmed = true) }).nextRequiredReview() == null)
    }

    @Test fun framesOnlyReviewSkipsDeselectedFramesAndNeverNavigatesToActions() {
        val state = ClickChainReviewUiState(totalActions = 3,lastAction = "3",framesOnly = true,
            stages = listOf(reviewStage(0,"chain-only")),actions = listOf(reviewAction("action-1")),
            frames = listOf(ClickChainFrameRow("excluded","未选画面",false,selected = false),
                ClickChainFrameRow("done","已复核画面",true),ClickChainFrameRow("first","待复核一",false),
                ClickChainFrameRow("second","待复核二",false)))
        check(state.nextRequiredReview() == ClickChainReviewTarget.Frame("first"))
        val deselected = state.copy(frames = state.frames.map { if (it.key == "first") it.copy(selected = false) else it })
        check(deselected.nextRequiredReview() == ClickChainReviewTarget.Frame("second"))
        val reviewed = deselected.copy(frames = deselected.frames.map { if (it.key == "second") it.copy(reviewed = true) else it })
        check(reviewed.nextRequiredReview() == null)
        check(reviewed.copy(frames = reviewed.frames.map { if (it.key == "first") it.copy(selected = true) else it }).nextRequiredReview() ==
            ClickChainReviewTarget.Frame("first"))
        check(state.copy(frames = state.frames.map { it.copy(selected = false) }).nextRequiredReview() == null)
        check(state.copy(frames = emptyList()).nextRequiredReview() == null)
    }

    @Test fun nextActionReviewSkipsConfirmedAndDisabledUnknownActionsInListOrder() {
        // The workspace makes unknown outcomes disabled; status text is a display label, not a second policy.
        val unknown = reviewAction("unknown",1,enabled = false).copy(status = "结果待确认")
        val disabled = reviewAction("missing-frame",2,enabled = false)
        val confirmed = reviewAction("confirmed",3,confirmed = true)
        val first = reviewAction("first",4)
        val second = reviewAction("second",5)
        val state = ClickChainReviewUiState(totalActions = 5,lastAction = "5",
            stages = listOf(reviewStage(0,"a",true)),actions = listOf(unknown,disabled,confirmed,first,second))
        check(state.nextRequiredReview() == ClickChainReviewTarget.Action("first"))
        val firstConfirmed = state.copy(actions = state.actions.map { if (it.actionId == "first") it.copy(confirmed = true) else it })
        check(firstConfirmed.nextRequiredReview() == ClickChainReviewTarget.Action("second"))
        check(state.copy(actions = listOf(unknown,disabled,confirmed)).nextRequiredReview() == null)
        check(state.copy(actions = state.actions.map { it.copy(confirmed = true) }).nextRequiredReview() == null)
    }

    @Test fun nextReviewIsSuppressedByEveryLockAndEitherOpenEditor() {
        val pendingFrame = ClickChainReviewUiState(totalActions = 1,stages = listOf(reviewStage(0,"a")),
            actions = listOf(reviewAction("action-1")))
        val pendingAction = pendingFrame.copy(stages = pendingFrame.stages.map { it.copy(reviewed = true) })
        val framesOnly = pendingFrame.copy(framesOnly = true,frames = listOf(ClickChainFrameRow("a","画面",false)))
        val frameEditor = ClickChainFrameEditor("a","画面",null,emptyList(),null,null,false)
        val actionEditor = ClickChainActionEditor("action-1","继续",OpaqueMask(.1f,.1f,.2f,.2f),
            null,null,"下一画面",.15f to .15f,false,1)
        listOf(pendingFrame,pendingAction,framesOnly).forEach { state ->
            check(state.nextRequiredReview() != null)
            val blocked = listOf(state.copy(loading = true),state.copy(busy = true),state.copy(editingLocked = true),
                state.copy(createdProjectId = "created-project"),state.copy(outcomeUnknown = true),
                state.copy(frameEditor = frameEditor),state.copy(actionEditor = actionEditor))
            blocked.forEach { check(it.nextRequiredReview() == null) { "Review navigation escaped a lock: $it" } }
        }
    }

    @Test fun nextReviewIsPureAndDoesNotUseOrInferCreationPermission() {
        val pending = ClickChainReviewUiState(totalActions = 1,stages = listOf(reviewStage(0,"a")),
            actions = listOf(reviewAction("action-1")),canCreate = false)
        val snapshot = pending.copy(stages = pending.stages.map { it.copy(choices = it.choices.toList()) },
            actions = pending.actions.map { it.copy() })
        repeat(2) { check(pending.nextRequiredReview() == ClickChainReviewTarget.Frame("a")) }
        check(pending == snapshot && !pending.canCreate)
        check(pending.copy(canCreate = true).nextRequiredReview() == ClickChainReviewTarget.Frame("a"))
        val finished = pending.copy(stages = pending.stages.map { it.copy(reviewed = true) },
            actions = pending.actions.map { it.copy(confirmed = true) })
        check(finished.nextRequiredReview() == null && !finished.canCreate)
        check(finished.copy(canCreate = true).nextRequiredReview() == null)
        check(ClickChainReviewUiState().nextRequiredReview() == null)
    }

    @Test fun validationRejectsMalformedReversedAndOutOfBoundsActionRanges() {
        val valid = ClickChainReviewUiState(title = "演示",firstAction = "1",lastAction = "3",totalActions = 3)
        check(valid.validationIssue() == null)
        val invalidRanges = listOf("" to "3","x" to "3","1.5" to "3","2147483648" to "3",
            "0" to "3","-1" to "3","4" to "4","1" to "","1" to "x","1" to "2.5",
            "1" to "2147483648","1" to "0","1" to "-1","1" to "4","3" to "2")
        invalidRanges.forEach { (first,last) ->
            check(valid.copy(firstAction = first,lastAction = last).validationIssue() != null) { "Accepted range $first..$last" }
        }
        check(valid.copy(firstAction = "2",lastAction = "2").validationIssue() == null)
        check(valid.copy(firstAction = "3",lastAction = "3").validationIssue() == null)
        check(valid.copy(lastAction = "1").validationIssue() == null)
    }

    @Test fun validationRequiresTitleWithoutInventingRangeErrorsDuringLoadingOrEmptyCapture() {
        val valid = ClickChainReviewUiState(title = "演示",firstAction = "1",lastAction = "1",totalActions = 1)
        listOf(""," ","\t\n").forEach { check(valid.copy(title = it).validationIssue() != null) }
        check(valid.copy(title = "  演示  ").validationIssue() == null)
        check(valid.copy(firstAction = "",lastAction = "",loading = true).validationIssue() == null)
        check(valid.copy(totalActions = 0).validationIssue() == null)
        check(valid.copy(totalActions = 0,firstAction = "",lastAction = "").validationIssue() == null)
        check(ClickChainReviewUiState(loading = true).validationIssue() == null)
        check(valid.copy(canCreate = false).validationIssue() == null)
    }

    private fun reviewStage(index: Int,key: String,reviewed: Boolean = false) =
        ClickChainStageRow(index,"阶段 $index",listOf(ClickChainStageChoice(key,"代表画面")),key,reviewed)

    private fun reviewAction(id: String,number: Int = 1,confirmed: Boolean = false,enabled: Boolean = true) =
        ClickChainActionRow(id,number,"手势已完成","起始画面","后继画面",confirmed,enabled)

    private fun capture(count: Int): ClickChainCapture {
        val project = id(); val session = id();val source = id();val runId = id()
        val actions = List(count) { ClickAction(x = 400 + it,y = 900 + it) }
        val plan = ClickPlan.create(project,"com.example.demo",1080,1920,0,actions)
        val run = ClickRun.create(plan,session,source,1,1,runId).copy(phase = ClickRunPhase.Completed,nextActionIndex = count,
            outcomes = actions.map { ClickActionOutcome(it.actionId,ClickActionStatus.Completed) })
        val imported = ImportedSource(source,"sources/$source.mp4","Synthetic.mp4",SourceMetadata("video/avc",1234,"a".repeat(64),540,960,0,1_000_000))
        val geometry = FrameGeometry.fitCenter(1080,1920,540,960)
        val evidence = actions.mapIndexed { index,action ->
            fun frame(boundary: FrameBoundary,number: Long): FrameEvidenceCandidate {
                val pts = number * 1000
                return FrameEvidenceCandidate(FrameTicket(id(),FrameAnchorAction(runId,action.actionId,session,source,1),boundary,0,
                    number,number,number * 1_000_000,pts,geometry,FrameFreshness.Fresh),"c".repeat(64),540,960,pts,number,pts,"a".repeat(64))
            }
            ClickChainActionEvidence(index,action,ClickActionStatus.Completed,frame(FrameBoundary.Before,index * 2L),frame(FrameBoundary.After,index * 2L + 1),null,null)
        }
        return ClickChainCapture(run,imported,evidence)
    }
    private fun id() = UUID.randomUUID().toString()
}
