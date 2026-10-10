package com.tapscene.runtime

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.view.MotionEvent
import com.tapscene.clickplan.*
import com.tapscene.data.ProjectStore
import com.tapscene.data.SourceRepository
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.ImportedSource
import com.tapscene.recording.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Opt-in, disposable-emulator lane. Does not inherit or invoke the broad media instrumentation.
 * Target input comes exclusively from production dispatchGesture. UI automation operates setup
 * and consent controls only. No injection, fake window events or weakened production deadlines.
 */
class ClickRuntimeInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    private lateinit var output: File
    private var outputOwned = false
    private var ui: RuntimeSystemUi? = null
    private var projectId: String? = null
    private var fixtureNonce: String? = null
    private lateinit var fixtureGeometry: JSONObject
    private val report = JSONObject().put("status", "RUNNING")
    private val assertions = JSONArray()
    private var stage = "preflight"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        output = File(targetContext.filesDir, "runtime-smoke")
        val result = Bundle()
        var passed = false
        try {
            check(Looper.myLooper() != Looper.getMainLooper())
            check(!output.exists() && output.mkdir()) { "Artifacts already exist; use a fresh disposable emulator" }
            outputOwned = true
            persist()
            runBlocking(Dispatchers.IO) { withTimeout(420_000) { runScenario() } }
            report.put("status", "PASS")
            passed = true
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failedStage", stage).put("reason", failure.toString())
                .put("stack", failure.stackTraceToString())
            runCatching { write("failure-ui.txt", ui?.snapshotTree().orEmpty()) }
        } finally {
            // Only this invocation's capability is stopped. Driver timeout is NOT release proof.
            runCatching {
                if (projectId != null && (ClickPlayback.state.value.run?.projectId == projectId || ClickPlayback.state.value.overlayVisible)) {
                    runOnMainSync { ClickPlayback.stop(targetContext) }
                    runBlocking { withTimeout(10_000) { RecordingCoordinator.awaitCommandBoundary() } }
                }
                val recording = RecordingCoordinator.state.value
                report.put("cleanup", JSONObject().put("recordingPhase", recording.phase.name)
                    .put("busy", recording.isBusy).put("overlayVisible", ClickPlayback.state.value.overlayVisible)
                    .put("driverReleaseProvedByCompletedRegistration", recording.phase == RecordingPhase.Completed)
                    .put("scope", "Stop requested for test-owned capabilities; artifacts and exact UUID records remain for extraction, then host removes the disposable AVD. A timeout never proves driver release."))
                if (recording.isBusy || ClickPlayback.state.value.overlayVisible) {
                    passed = false
                    report.put("status", "FAIL").put("cleanupBlocked", true)
                }
            }.onFailure {
                passed = false
                report.put("status", "FAIL").put("cleanupFailure", it.toString())
            }
            runCatching { fixtureNonce?.let { write("events.json", snapshot(it).getString("events") ?: "[]") } }
            runCatching { ClickPlayback.state.value.run?.takeIf { it.projectId == projectId }?.let { write("chain.json", chain(it).toString(2)) } }
            runCatching { persist() }
        }
        result.putString("stream", if (passed) "TAPSCENE_RUNTIME_SMOKE_OK\n" else "TAPSCENE_RUNTIME_SMOKE_FAILED: $stage\n")
        result.putString("artifacts", "files/runtime-smoke")
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private suspend fun runScenario() {
        require(arguments.getString("syntheticOnly") == "true") { "Explicit -e syntheticOnly true is required" }
        require(Build.VERSION.SDK_INT >= 33)
        require(Build.FINGERPRINT.contains("generic") || Build.HARDWARE in setOf("ranchu", "goldfish")) { "This lane is restricted to disposable Android emulators" }
        require(ProjectStore(targetContext).listProjects().isEmpty()) { "Refusing an existing user's project database" }
        require(targetContext.packageManager.checkSignatures(targetContext.packageName, RuntimeSystemUi.TARGET_PACKAGE) == android.content.pm.PackageManager.SIGNATURE_MATCH)
        report.put("api", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
            .put("package", targetContext.packageName).put("productionArmMs", 15_000)
            .put("productionEosMs", 5_000).put("productionReleaseMs", 7_000)
        mark("isolated_synthetic_preflight")

        stage = "egl_codec_probe"; persist()
        report.put("probe", RuntimeCodecProbe.run(targetContext, File(output, "probe.mp4")))
        mark("production_oes_egl_codec_single_buffer_static_probe")

        stage = "normal_accessibility_ui"; persist()
        runOnMainSync { ClickPlayback.initialize(targetContext) }
        ui = RuntimeSystemUi(this)
        requireNotNull(ui).enableService()
        mark("service_connected_through_settings_ui")

        stage = "synthetic_target_and_plan"; persist()
        val nonce = UUID.randomUUID().toString().also { fixtureNonce = it }
        targetContext.startActivity(Intent().setComponent(ComponentName(RuntimeSystemUi.TARGET_PACKAGE,
            "${RuntimeSystemUi.TARGET_PACKAGE}.TargetActivity"))
            .putExtra("sessionId", nonce).putExtra("static", false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await(10_000, "Synthetic target did not report visible geometry") { runCatching { snapshot(nonce).getBoolean("ready") }.getOrDefault(false) }
        val fixture = snapshot(nonce)
        write("geometry.json", fixture.getString("geometry") ?: "{}")
        fixtureGeometry = JSONObject(checkNotNull(fixture.getString("geometry")))
        check(fixture.getString("ioError").isNullOrEmpty()) { "Synthetic target evidence IO failed" }
        val geometry = ClickDevice.geometry(targetContext)
        require(fixture.getInt("width") == geometry.width && fixture.getInt("height") == geometry.height)
        val project = ProjectStore(targetContext).createProject("Synthetic runtime smoke")
        projectId = project.project.id
        val actions = listOf(
            ClickAction(x = fixture.getInt("button1X"), y = fixture.getInt("button1Y"), waitAfterMs = 8_000),
            ClickAction(x = fixture.getInt("button2X"), y = fixture.getInt("button2Y"), waitAfterMs = 4_000),
            ClickAction(x = fixture.getInt("button1X"), y = fixture.getInt("button1Y"), waitAfterMs = 8_000),
        )
        val plan = ClickPlan.create(requireNotNull(projectId), RuntimeSystemUi.TARGET_PACKAGE, geometry.width,
            geometry.height, geometry.rotation, actions)
        ClickPlanStore(targetContext).savePlan(plan, null)
        // Enter through the app so openEditor observes an actual app-to-target window transition.
        // Re-launching a target that was already focused may legitimately emit no new window event.
        startActivitySync(Intent(targetContext, com.tapscene.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForIdleSync()
        runOnMainSync { ClickPlayback.openEditor(targetContext, plan) }
        await(10_000, "Production locator was not shown: ${ClickPlayback.state.value.message}") { ClickPlayback.state.value.overlayVisible }
        delay(600) // Let actual overlay drawing establish its viewport, not a fake frame/guard.
        val screenshot = checkNotNull(requireNotNull(ui).automation.takeScreenshot())
        try {
            File(output, "overlay-before.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val point = actions.first()
            check(greenPixels(screenshot, point.x, point.y) >= 12) { "Visible production locator mark was not present at the planned point" }
        } finally { screenshot.recycle() }
        mark("visible_locator_pixels_at_configured_point")

        stage = "fresh_projection_consent_ui"; persist()
        runOnMainSync { ClickPlayback.prepareConsent(targetContext) }
        await(5_000, "Production locator did not detach before consent") { !ClickPlayback.state.value.overlayVisible }
        requireNotNull(ui).approveFreshCapture()
        mark("fresh_system_capture_consent_and_overlay_detachment")

        stage = "production_click_and_record"; persist()
        await(16_000, "Production 15-second arm did not reach Running; no rearm or relaxed timeout") {
            val current = ClickPlayback.state.value.run
            current != null && current.projectId == projectId && (current.phase == ClickRunPhase.Running || current.terminal)
        }
        val started = requireNotNull(ClickPlayback.state.value.run)
        require(started.projectId == projectId && started.plan.digest == plan.digest)
        require(started.phase == ClickRunPhase.Running) { "Production click run did not start: ${started.phase}/${started.stopReason}" }
        await(45_000, "Finite production click chain did not reach a terminal state") {
            ClickPlayback.state.value.run?.takeIf { it.runId == started.runId }?.terminal == true
        }
        val run = requireNotNull(ClickPlayback.state.value.run)
        write("chain.json", chain(run).toString(2))
        val finalFixture = snapshot(nonce)
        check(finalFixture.getString("ioError").isNullOrEmpty()) { "Synthetic target evidence IO failed" }
        val events = JSONArray(finalFixture.getString("events"))
        write("events.json", events.toString(2))
        verifyGestures(run, events)
        check(finalFixture.getInt("renderedCounter") == 3)
        mark("actual_target_down_up_coordinates_order_and_waits")
        await(30_000, "Production recording did not finish registration") {
            val current = RecordingCoordinator.state.value
            current.sessionId == run.recordingSessionId && !current.isBusy
        }
        val recording = RecordingCoordinator.state.value
        require(recording.phase == RecordingPhase.Completed && !recording.canRetry) { "Recording not durably completed: $recording" }
        val source = WorkspaceStore(targetContext, run.projectId).read().single { it.source.sourceId == run.sourceId }.source
        val video = File(targetContext.noBackupFilesDir, source.privateRelativePath)
        video.copyTo(File(output, "capture.mp4"), overwrite = false)
        report.put("capture", JSONObject().put("durationUs", source.metadata.durationUs).put("sourceSha256", source.metadata.sha256)
            .put("sessionId", run.recordingSessionId).put("sourceId", run.sourceId).put("recordingElapsedMsDiagnosticOnly", recording.elapsedMs))
        mark("production_video_seal_duration_gate_and_registration")

        stage = "container_and_every_decoded_frame"; persist()
        verifyVideo(source, video, run)
        mark("every_sample_decoded_and_checked_for_locator_ring_with_static_target_intervals_preserved")
        stage = "validated_chain_anchors"; persist()
        verifyAnchors(run, source)
        mark("registered_source_real_container_ordinals_and_chain_route")
    }

    private fun verifyGestures(run: ClickRun, events: JSONArray) {
        check(run.phase == ClickRunPhase.Completed && run.outcomes.all { it.status == ClickActionStatus.Completed })
        check(events.length() == run.plan.actions.size * 2) { "Unexpected target touch count: ${events.length()}" }
        for ((i, action) in run.plan.actions.withIndex()) {
            val down = events.getJSONObject(i * 2); val up = events.getJSONObject(i * 2 + 1)
            check(down.getInt("sequence") == i * 2 + 1 && up.getInt("sequence") == i * 2 + 2)
            check(up.getBoolean("stateChanged") && up.getInt("counter") == i + 1 && !down.getBoolean("stateChanged"))
            check(down.getInt("action") == MotionEvent.ACTION_DOWN && up.getInt("action") == MotionEvent.ACTION_UP)
            check(down.getInt("button") == listOf(1, 2, 1)[i] && up.getInt("button") == down.getInt("button"))
            for (touch in listOf(down, up)) {
                check(abs(touch.getDouble("rawX") - action.x) <= 2 && abs(touch.getDouble("rawY") - action.y) <= 2)
                check(touch.getLong("receivedUptimeMs") >= touch.getLong("eventTimeMs"))
            }
            check(up.getLong("downTimeMs") == down.getLong("downTimeMs"))
            val press = up.getLong("eventTimeMs") - down.getLong("eventTimeMs")
            check(abs(press - action.pressDurationMs) <= 32) { "Actual press duration differs: $press ms" }
            val intent = run.events.single { it.type == ClickRunEventType.DispatchIntent && it.actionId == action.actionId }
            val completion = run.events.single { it.type == ClickRunEventType.GestureCompleted && it.actionId == action.actionId }
            check(down.getLong("eventTimeMs") >= intent.diagnosticUptimeMs)
            check(completion.diagnosticUptimeMs >= up.getLong("eventTimeMs"))
            if (i > 0) {
                val previous = run.plan.actions[i - 1]
                val previousCompletion = run.events.single { it.type == ClickRunEventType.GestureCompleted && it.actionId == previous.actionId }
                check(down.getLong("eventTimeMs") >= previousCompletion.diagnosticUptimeMs + previous.waitAfterMs) { "Actual target touch preceded configured wait" }
            }
        }
        val finalCompletion = run.events.single { it.type == ClickRunEventType.GestureCompleted && it.actionId == run.plan.actions.last().actionId }
        val end = run.events.single { it.type == ClickRunEventType.Completed }
        check(end.diagnosticUptimeMs >= finalCompletion.diagnosticUptimeMs + run.plan.actions.last().waitAfterMs)
    }

    private suspend fun verifyVideo(source: ImportedSource, file: File, run: ClickRun) {
        val samples = FrameRecordingBackend.readContainerSamples(file, source.metadata.width, source.metadata.height)
        val firstIntent = run.events.first { it.type == ClickRunEventType.DispatchIntent }.diagnosticUptimeMs
        val end = run.events.single { it.type == ClickRunEventType.Completed }.diagnosticUptimeMs
        check(source.metadata.durationUs >= (end - firstIntent) * 1_000 - 1_000) { "MP4 duration lost the static chain interval" }
        check(source.metadata.durationUs <= (end - firstIntent + 15_000) * 1_000) { "MP4 duration unexpectedly extended beyond bounded startup" }
        val geometry = FrameGeometry.fitCenter(run.plan.width, run.plan.height, source.metadata.width, source.metadata.height)
        var frames = 0
        var targetFrames = 0
        var maximumGreenRingSamples = 0
        val states = linkedMapOf<Int, Pair<Long, Long>>()
        var lastState = 0
        // The production generated-transition decoder deliberately caps clips at ten seconds.
        // This test-only bounded decoder inspects the twenty-second raw capture without changing that policy.
        val decoded = RuntimeVideoDecode.decode(file, source.metadata) { image, ptsUs ->
            // Inspect the locator's distinctive green circular feature even in initial/transition
            // frames. Target recognition is NEVER a reason to skip this all-frame assertion.
            for (point in run.plan.actions.distinctBy { it.x to it.y }) {
                val green = greenRingSamples(image, (point.x * geometry.scale + geometry.offsetX).roundToInt(),
                    (point.y * geometry.scale + geometry.offsetY).roundToInt(),
                    14.0 * fixtureGeometry.getDouble("density") * geometry.scale)
                maximumGreenRingSamples = maxOf(maximumGreenRingSamples, green)
                check(green < 8) { "Locator-like green ring in actual MP4 sample PTS=$ptsUs at (${point.x},${point.y}): $green/24 samples" }
            }
            val visual = RuntimeTargetPixels(fixtureGeometry, geometry) { x, y -> yuv(image, x, y, 0) }
            val state = visual.recognizedState()
            if (state != null) {
                check(state in 0..3 && state >= lastState) { "Decoded synthetic state changed out of order: $lastState -> $state" }
                lastState = state
                states[state] = (states[state]?.first ?: ptsUs) to ptsUs
                targetFrames++
                for (point in run.plan.actions.distinctBy { it.x to it.y }) {
                    assertNeutralRoi(image, (point.x * geometry.scale + geometry.offsetX).roundToInt(),
                        (point.y * geometry.scale + geometry.offsetY).roundToInt())
                }
            }
            frames++
        }
        check(samples == decoded && frames == samples.size && frames > 20)
        check(states.keys.toList() == listOf(0, 1, 2, 3)) { "Actual MP4 did not show each target state in order: ${states.keys}" }
        for ((state, minimumUs) in listOf(1 to 7_000_000L, 2 to 3_000_000L, 3 to 7_000_000L)) {
            val span = checkNotNull(states[state])
            check(span.second - span.first >= minimumUs) { "Actual MP4 lost static state $state interval" }
        }
        report.getJSONObject("capture").put("samples", samples.size).put("allFramesDecoded", frames)
            .put("recognizedTargetFrames", targetFrames).put("otherTransitionFrames", frames - targetFrames)
            .put("overlayInspectionScope", "Every decoded frame: green locator-ring features at all configured points. Every recognized target frame: additional neutral center ROI.")
            .put("maximumGreenRingSamplesOf24", maximumGreenRingSamples)
            .put("visibleStates", JSONArray(states.map { (state, span) -> JSONObject().put("state", state).put("firstPtsUs", span.first).put("lastPtsUs", span.second) }))
            .put("firstPtsUs", samples.first()).put("lastPtsUs", samples.last())
            .put("diagnosticChainSpanMs", end - firstIntent)
            .put("containerSamplePtsUs", JSONArray(samples))
    }

    private suspend fun verifyAnchors(run: ClickRun, source: ImportedSource) {
        val evidence = FrameEvidenceStore(targetContext)
        val accessor = SourceRepository(targetContext).frameEvidenceSourceAccessor()
        val rows = evidence.listBoundaries(run.recordingSessionId)
        // Registration's auxiliary marker is queued asynchronously; observe it without changing capture timing.
        await(5_000, "Frame evidence registration marker did not settle") {
            rows.mapNotNull { it.ticketId }.all {
                val value = evidence.readCandidate(run.recordingSessionId, it, accessor)
                value !is FrameCandidateResult.Missing || value.reason != FrameMissingReason.SourceUnregistered
            }
        }
        check(rows.size == run.plan.actions.size * 2) { "Expected one before/after boundary per actual action" }
        val resultRows = JSONArray()
        val actions = run.plan.actions.mapIndexed { index, action ->
            fun candidate(boundary: FrameBoundary): FrameEvidenceCandidate? {
                val row = rows.single { it.action.actionId == action.actionId && it.boundary == boundary }
                var resolved: FrameCandidateResult = FrameCandidateResult.Missing(row.missingReason ?: FrameMissingReason.EvidenceUnavailable)
                row.ticketId?.let { ticket -> resolved = evidence.readCandidate(run.recordingSessionId, ticket, accessor) }
                val candidate = (resolved as? FrameCandidateResult.Available)?.candidate
                val json = JSONObject().put("actionId", action.actionId).put("boundary", boundary.name).put("epoch", row.epoch)
                if (candidate == null) json.put("missing", (resolved as FrameCandidateResult.Missing).reason.name)
                else {
                    json.put("sourceFrameId", candidate.ticket.sourceFrameId).put("captureSequence", candidate.ticket.captureSequence)
                        .put("sourceTimestampNs", candidate.ticket.sourceTimestampNs).put("submittedPtsUs", candidate.ticket.submittedPtsUs)
                        .put("encoderPtsUs", candidate.encoderPtsUs).put("muxSampleOrdinal", candidate.muxSampleOrdinal)
                        .put("containerPtsUs", candidate.containerPtsUs).put("freshness", candidate.ticket.freshness.name)
                        .put("pngSha256", candidate.pngSha256)
                }
                resultRows.put(json)
                return candidate
            }
            val before = candidate(FrameBoundary.Before); val after = candidate(FrameBoundary.After)
            ClickChainActionEvidence(index, action, run.outcomes[index].status, before, after,
                if (before == null) FrameMissingReason.EvidenceUnavailable else null,
                if (after == null) FrameMissingReason.EvidenceUnavailable else null)
        }
        write("anchors.json", resultRows.toString(2))
        val capture = ClickChainCapture(run, source, actions)
        val route = ClickChainRoutePolicy.route(capture, 1, actions.size, emptyMap())
        report.put("chainRoute", JSONObject().put("issue", route.issue ?: JSONObject.NULL)
            .put("stages", route.stages.size).put("distinctSourceFrames", capture.frames.size))
        check(route.issue == null) { "Real dynamic target did not yield a complete reviewable route: ${route.issue}; Missing is never synthesized" }
        for (frame in capture.frames.values) {
            evidence.withDecodedFrameSuspending(frame, accessor) { bitmap ->
                for (point in run.plan.actions.distinctBy { it.x to it.y }) {
                    val x = (point.x * frame.ticket.geometry.scale + frame.ticket.geometry.offsetX).roundToInt()
                    val y = (point.y * frame.ticket.geometry.scale + frame.ticket.geometry.offsetY).roundToInt()
                    check(greenPixels(bitmap, x, y) == 0) { "Locator pixels in shared-FBO evidence" }
                }
            }
        }
        // Read the actual same-FBO PNG pixels, not the fixture's counter or route metadata.
        for ((index, action) in actions.withIndex()) {
            for ((frame, expected) in listOf(checkNotNull(action.before) to index, checkNotNull(action.after) to index + 1)) {
                evidence.withDecodedFrameSuspending(frame, accessor) { bitmap ->
                    val observed = RuntimeTargetPixels(fixtureGeometry, frame.ticket.geometry) { x, y ->
                        val color = bitmap.getPixel(x, y)
                        (Color.red(color) * 0.2126 + Color.green(color) * 0.7152 + Color.blue(color) * 0.0722).roundToInt()
                    }.recognizedState()
                    check(observed == expected) { "Actual anchor pixels for action ${index + 1} differ: expected=$expected, observed=$observed" }
                }
            }
        }
        report.getJSONObject("chainRoute").put("actualPngStatesVerified", listOf(0, 1, 2, 3))
    }

    private fun snapshot(nonce: String): Bundle = checkNotNull(targetContext.contentResolver.call(
        Uri.parse("content://${RuntimeSystemUi.TARGET_PACKAGE}.control"), "snapshot", nonce, null))

    private suspend fun await(timeoutMs: Long, message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < deadline) { "$message; click=${ClickPlayback.state.value}; recording=${RecordingCoordinator.state.value}" }
            delay(50)
        }
    }

    private fun mark(name: String) {
        assertions.put(JSONObject().put("name", name).put("status", "PASS").put("uptimeMs", SystemClock.uptimeMillis()))
        sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
        persist()
    }

    private fun persist() {
        report.put("stage", stage).put("assertions", assertions)
        write("result.json", report.toString(2))
    }
    private fun write(name: String, value: String) {
        check(outputOwned) { "No test-owned artifact directory" }
        File(output, name).writeText(value)
    }

    private fun chain(run: ClickRun): JSONObject = JSONObject().put("runId", run.runId).put("projectId", run.projectId)
        .put("phase", run.phase.name).put("stopReason", run.stopReason?.name ?: JSONObject.NULL)
        .put("recordingSessionId", run.recordingSessionId).put("sourceId", run.sourceId).put("planDigest", run.plan.digest)
        .put("plannedDurationMs", run.plan.plannedDurationMs).put("mapping", run.mapping.name)
        .put("actions", JSONArray(run.plan.actions.map { action -> JSONObject().put("actionId", action.actionId)
            .put("x", action.x).put("y", action.y).put("pressDurationMs", action.pressDurationMs).put("waitAfterMs", action.waitAfterMs)
            .put("outcome", run.outcomes.single { it.actionId == action.actionId }.status.name) }))
        .put("events", JSONArray(run.events.map { event -> JSONObject().put("type", event.type.name)
            .put("actionId", event.actionId ?: JSONObject.NULL).put("diagnosticUptimeMs", event.diagnosticUptimeMs) }))

    private fun greenPixels(bitmap: Bitmap, x: Int, y: Int): Int {
        var count = 0
        for (dy in -10..10) for (dx in -10..10) {
            val px = x + dx; val py = y + dy
            if (px !in 0 until bitmap.width || py !in 0 until bitmap.height) continue
            val c = bitmap.getPixel(px, py)
            if (Color.green(c) > Color.red(c) + 35 && Color.green(c) > Color.blue(c) + 8) count++
        }
        return count
    }

    /** Target centers are fixed #F7F7F7; inspect actual decoded YUV, never a screenshot substitute. */
    private fun assertNeutralRoi(image: Image, x: Int, y: Int) {
        val crop = image.cropRect
        for (dy in -6..6 step 2) for (dx in -6..6 step 2) {
            val px = crop.left + x + dx; val py = crop.top + y + dy
            check(crop.contains(px, py))
            val yv = yuv(image, x + dx, y + dy, 0); val u = yuv(image, x + dx, y + dy, 1); val v = yuv(image, x + dx, y + dy, 2)
            // Neutral pixels may be dimmed during a legitimate window transition. Chroma detects
            // the green locator independently; the lower luminance bound permits that dimming.
            check(yv >= 120 && abs(u - 128) <= 18 && abs(v - 128) <= 18) {
                "Non-neutral locator/overlay pixels at real video ROI ($x,$y): YUV=$yv,$u,$v"
            }
        }
    }

    private fun yuv(image: Image, x: Int, y: Int, plane: Int): Int {
        val p = image.planes[plane]; val divisor = if (plane == 0) 1 else 2
        val buffer = p.buffer.duplicate()
        return buffer.get(buffer.position() + (image.cropRect.top + y) / divisor * p.rowStride +
            (image.cropRect.left + x) / divisor * p.pixelStride).toInt() and 255
    }

    private fun greenRingSamples(image: Image, x: Int, y: Int, radius: Double): Int {
        var green = 0
        for (index in 0 until 24) {
            val angle = index * Math.PI * 2 / 24
            val px = (x + cos(angle) * radius).roundToInt()
            val py = (y + sin(angle) * radius).roundToInt()
            if (px !in 0 until image.cropRect.width() || py !in 0 until image.cropRect.height()) continue
            val yy = 1.164 * (yuv(image, px, py, 0) - 16)
            val u = yuv(image, px, py, 1) - 128
            val v = yuv(image, px, py, 2) - 128
            // Production encodes limited-range BT.709. Luma cancels in channel differences.
            val red = yy + 1.793 * v
            val g = yy - 0.213 * u - 0.533 * v
            val blue = yy + 2.112 * u
            if (g > red + 30 && g > blue + 6) green++
        }
        return green
    }
}
