package com.tapscene.clickplan

import com.tapscene.media.ImportedSource
import com.tapscene.media.SourceMetadata
import com.tapscene.recording.*
import java.util.UUID
import org.junit.Test

/** Pure author-route policy; no recording, actual touch or human privacy judgment is simulated. */
class ClickChainReviewPolicyTest {
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
