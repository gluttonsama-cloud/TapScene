package com.tapscene.clickplan

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** Independent no-backup journal. It is not part of project exports, publishing or project copies. */
class ClickPlanStore(context: Context) {
    private val base = context.noBackupFilesDir.canonicalFile
    private val root = checkedChild(base, "click-plans")
    private val plans = checkedChild(root, "plans")
    private val runs = checkedChild(root, "runs")

    init {
        synchronized(lock) {
            ensureDirectory(root); syncDirectory(base)
            ensureDirectory(plans); ensureDirectory(runs); syncDirectory(root)
        }
    }

    fun getPlan(projectId: String): ClickPlan? = synchronized(lock) {
        requireClickUuid(projectId)
        readPlan(projectId)
    }

    /** New plans use expectedRevision=null and revision=1. Edits must increment exactly once. */
    fun savePlan(plan: ClickPlan, expectedRevision: Long?): ClickPlan = synchronized(lock) {
        val current = readPlan(plan.projectId)
        if (current == null) {
            check(expectedRevision == null && plan.revision == 1L) { "Click plan revision conflict" }
        } else {
            check(expectedRevision == current.revision && plan.revision == current.revision + 1 && plan.planId == current.planId) {
                "Click plan revision conflict"
            }
        }
        atomicWrite(checkedChild(plans, "${plan.projectId}.json"), encodePlan(plan).toString().toByteArray(Charsets.UTF_8))
        plan
    }

    fun beginRun(run: ClickRun) = synchronized(lock) {
        run.validate()
        check(run.phase == ClickRunPhase.Ready && run.journalRevision == 0L && run.events.isEmpty())
        check(readRun(run.runId) == null) { "Click run already exists" }
        writeRun(run)
    }

    /** The first engine transition can create the run; every later save is optimistic and append-only. */
    fun saveRun(run: ClickRun) = synchronized(lock) {
        run.validate()
        val current = readRun(run.runId)
        if (current == null) {
            check(run.journalRevision in 0L..1L && run.outcomes.all { it.status == ClickActionStatus.Pending }) {
                "Missing click run journal"
            }
        } else {
            check(current.plan == run.plan && current.recordingSessionId == run.recordingSessionId &&
                current.sourceId == run.sourceId && current.generation == run.generation && current.createdAtMs == run.createdAtMs) {
                "Click run binding changed"
            }
            // Idempotent exact retry is safe; it does not cause an engine to re-dispatch.
            if (current == run) return@synchronized
            check(!current.terminal && run.journalRevision == current.journalRevision + 1) { "Click run revision conflict" }
            check(run.events.size > current.events.size && run.events.take(current.events.size) == current.events) {
                "Click run events are append-only"
            }
            check(run.nextActionIndex >= current.nextActionIndex && current.outcomes.zip(run.outcomes).all { (before, after) ->
                before.status == after.status || when (before.status) {
                    ClickActionStatus.Pending -> after.status == ClickActionStatus.Intent
                    ClickActionStatus.Intent -> after.status in setOf(ClickActionStatus.Accepted, ClickActionStatus.Rejected, ClickActionStatus.Unknown)
                    ClickActionStatus.Accepted -> after.status in setOf(ClickActionStatus.Completed, ClickActionStatus.Cancelled, ClickActionStatus.TimedOut, ClickActionStatus.Unknown)
                    else -> false
                }
            }) { "Click action outcome cannot be replayed or rewritten" }
        }
        writeRun(run)
    }

    /** Invalid journals are retained but never interpreted as execution permission. */
    fun readRuns(projectId: String? = null): List<ClickRun> = synchronized(lock) {
        projectId?.let(::requireClickUuid)
        runIds().mapNotNull { runId -> runCatching { readRun(runId) }.getOrNull() }
            .filter { projectId == null || it.plan.projectId == projectId }
            .sortedByDescending { it.createdAtMs }
    }

    /** Call once during owner recovery, before creating any new engine. Recovery NEVER dispatches. */
    fun recoverInterruptedRuns(diagnosticUptimeMs: Long = SystemClock.uptimeMillis()): List<ClickRun> = synchronized(lock) {
        require(diagnosticUptimeMs >= 0)
        readRuns().map { run ->
            if (run.terminal) run else {
                val recovered = run.copy(
                    phase = ClickRunPhase.Interrupted,
                    stopReason = ClickStopReason.ProcessInterrupted,
                    outcomes = frozenClickList(run.outcomes.map { outcome ->
                        if (outcome.status == ClickActionStatus.Intent || outcome.status == ClickActionStatus.Accepted)
                            outcome.copy(status = ClickActionStatus.Unknown) else outcome
                    }),
                    journalRevision = run.journalRevision + 1,
                    events = frozenClickList(run.events + ClickRunEvent(ClickRunEventType.Interrupted, diagnosticUptimeMs = diagnosticUptimeMs)),
                )
                saveRun(recovered)
                recovered
            }
        }
    }

    private fun readPlan(projectId: String): ClickPlan? {
        val bytes = readOptional(checkedChild(plans, "$projectId.json")) ?: return null
        return decodePlan(JSONObject(String(bytes, Charsets.UTF_8))).also { check(it.projectId == projectId) }
    }

    private fun readRun(runId: String): ClickRun? {
        requireClickUuid(runId)
        val bytes = readOptional(checkedChild(runs, "$runId.json")) ?: return null
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        check(json.getInt("version") == 1)
        check(json.getString("mapping") == ClickMapping.Unknown.name)
        val outcomesJson = json.getJSONArray("outcomes")
        val outcomes = (0 until outcomesJson.length()).map { index ->
            val item = outcomesJson.getJSONObject(index)
            check(item.has("beforeFrameId") && item.isNull("beforeFrameId") && item.has("afterFrameId") && item.isNull("afterFrameId"))
            check(item.getString("mapping") == ClickMapping.Unknown.name)
            ClickActionOutcome(item.getString("actionId"), ClickActionStatus.valueOf(item.getString("status")))
        }
        val eventsJson = json.getJSONArray("events")
        val events = (0 until eventsJson.length()).map { index ->
            val item = eventsJson.getJSONObject(index)
            ClickRunEvent(ClickRunEventType.valueOf(item.getString("type")),
                if (item.isNull("actionId")) null else item.getString("actionId"), item.getLong("diagnosticUptimeMs"))
        }
        return ClickRun(
            runId = json.getString("runId"), plan = decodePlan(json.getJSONObject("plan")),
            recordingSessionId = json.getString("recordingSessionId"), sourceId = json.getString("sourceId"),
            generation = json.getLong("generation"), createdAtMs = json.getLong("createdAtMs"),
            phase = ClickRunPhase.valueOf(json.getString("phase")), nextActionIndex = json.getInt("nextActionIndex"),
            outcomes = frozenClickList(outcomes), events = frozenClickList(events),
            stopReason = if (json.isNull("stopReason")) null else ClickStopReason.valueOf(json.getString("stopReason")),
            journalRevision = json.getLong("journalRevision"),
        ).also { check(it.runId == runId); it.validate() }
    }

    private fun writeRun(run: ClickRun) {
        val outcomes = JSONArray()
        run.outcomes.forEach { item -> outcomes.put(JSONObject().put("actionId", item.actionId).put("status", item.status.name)
            .put("beforeFrameId", JSONObject.NULL).put("afterFrameId", JSONObject.NULL).put("mapping", ClickMapping.Unknown.name)) }
        val events = JSONArray()
        run.events.forEach { item -> events.put(JSONObject().put("type", item.type.name)
            .put("actionId", item.actionId ?: JSONObject.NULL).put("diagnosticUptimeMs", item.diagnosticUptimeMs)) }
        val json = JSONObject().put("version", 1).put("runId", run.runId).put("plan", encodePlan(run.plan))
            .put("recordingSessionId", run.recordingSessionId).put("sourceId", run.sourceId).put("generation", run.generation)
            .put("createdAtMs", run.createdAtMs).put("phase", run.phase.name).put("nextActionIndex", run.nextActionIndex)
            .put("journalRevision", run.journalRevision).put("mapping", ClickMapping.Unknown.name)
            .put("stopReason", run.stopReason?.name ?: JSONObject.NULL).put("outcomes", outcomes).put("events", events)
        atomicWrite(checkedChild(runs, "${run.runId}.json"), json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun encodePlan(plan: ClickPlan): JSONObject {
        val actions = JSONArray()
        plan.actions.forEach { action -> actions.put(JSONObject().put("actionId", action.actionId)
            .put("x", action.x).put("y", action.y).put("pressDurationMs", action.pressDurationMs).put("waitAfterMs", action.waitAfterMs)) }
        return JSONObject().put("version", 1).put("planId", plan.planId).put("projectId", plan.projectId)
            .put("revision", plan.revision).put("targetPackage", plan.targetPackage).put("displayId", plan.displayId)
            .put("width", plan.width).put("height", plan.height).put("rotation", plan.rotation)
            .put("actions", actions).put("digest", plan.digest)
    }

    private fun decodePlan(json: JSONObject): ClickPlan {
        check(json.getInt("version") == 1)
        val actions = json.getJSONArray("actions")
        check(actions.length() <= 40)
        val plan = ClickPlan.create(
            projectId = json.getString("projectId"), targetPackage = json.getString("targetPackage"),
            width = json.getInt("width"), height = json.getInt("height"), rotation = json.getInt("rotation"),
            displayId = json.getInt("displayId"), planId = json.getString("planId"), revision = json.getLong("revision"),
            actions = (0 until actions.length()).map { index -> actions.getJSONObject(index).let { action ->
                ClickAction(action.getString("actionId"), action.getInt("x"), action.getInt("y"),
                    action.getLong("pressDurationMs"), action.getLong("waitAfterMs"))
            } },
        )
        check(plan.digest == json.getString("digest")) { "Click plan digest mismatch" }
        return plan
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        check(bytes.size <= MAX_BYTES)
        checkAtomicPaths(file)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        var finalized = false
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
            finalized = true
            // AtomicFile may log rather than throw on a failed rename. Require the exact bytes.
            if (!readBytes(atomic).contentEquals(bytes)) throw IOException("Click journal commit not confirmed")
            syncDirectory(file.parentFile ?: throw IOException("Invalid click journal directory"))
        } catch (failure: Exception) {
            // A post-rename directory fsync failure is uncertain durability, never a rollback signal.
            if (!finalized) atomic.failWrite(output)
            throw failure
        }
    }

    private fun readOptional(file: File): ByteArray? = try {
        checkAtomicPaths(file)
        readBytes(AtomicFile(file))
    } catch (failure: FileNotFoundException) {
        if (file.exists() || File(file.path + ".bak").exists()) throw failure
        null
    }

    private fun checkAtomicPaths(file: File) {
        val parent = file.parentFile ?: throw IOException("Invalid click journal directory")
        checkedChild(parent, file.name)
        checkedChild(parent, file.name + ".bak")
        checkedChild(parent, file.name + ".new")
    }

    private fun readBytes(atomic: AtomicFile): ByteArray = atomic.openRead().use { input ->
        val bytes = ByteArray(MAX_BYTES + 1)
        var length = 0
        while (length < bytes.size) {
            val count = input.read(bytes, length, bytes.size - length)
            if (count < 0) break
            if (count == 0) throw IOException("Incomplete click journal")
            length += count
        }
        if (length > MAX_BYTES) throw IOException("Click journal too large")
        bytes.copyOf(length)
    }

    private fun runIds(): List<String> = runs.listFiles().orEmpty().mapNotNull { file ->
        val name = file.name.removeSuffix(".bak").removeSuffix(".new")
        if (!name.endsWith(".json")) return@mapNotNull null
        val id = name.removeSuffix(".json")
        runCatching { requireClickUuid(id); checkedChild(runs, "$id.json"); id }.getOrNull()
    }.distinct()

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Private click storage unavailable")
        if (directory.canonicalFile != directory.absoluteFile) throw IOException("Invalid click directory")
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun checkedChild(parent: File, name: String): File = File(parent, name).also { file ->
        if (parent.canonicalFile != parent.absoluteFile || file.canonicalFile != File(parent.canonicalFile, name)) {
            throw IOException("Invalid click storage path")
        }
    }

    companion object {
        private val lock = Any()
        private const val MAX_BYTES = 1024 * 1024
    }
}
