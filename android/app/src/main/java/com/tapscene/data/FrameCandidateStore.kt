package com.tapscene.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tapscene.media.CandidateAnalysisProgress
import com.tapscene.media.CandidateAnalysisStatus
import com.tapscene.media.CandidateDecision
import com.tapscene.media.CandidateReason
import com.tapscene.media.FrameCandidate
import com.tapscene.media.FrameCandidateLimits
import com.tapscene.media.FrameCandidateSnapshot
import com.tapscene.media.ImportedSource
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-private metadata only. There are no thumbnails, StepAssets, source-file mutations or
 * directory sweeps here. SQLite journals atomically save progress and choices together.
 * A process-local run lease prevents recovery from touching another live analysis session.
 * After process death, RUNNING becomes INTERRUPTED; retry resamples while preserving choices.
 * Call on Dispatchers.IO. All instances for this app share the same operation lock.
 */
class FrameCandidateStore(context: Context) {
    private val root = context.applicationContext.noBackupFilesDir.canonicalFile
    private val file = File(root, "frame-candidates.sqlite")
    private val helper = Database(context.applicationContext, file.path)

    fun read(projectId: String, source: ImportedSource): FrameCandidateSnapshot = access { db ->
        validateIdentity(projectId, source)
        readRow(db, projectId, source)?.let { recover(db, it, source).snapshot }
            ?: empty(projectId, source)
    }

    internal fun begin(projectId: String, source: ImportedSource, totalSamples: Int): CandidateRun = access { db ->
        validateIdentity(projectId, source)
        require(totalSamples in 1..FrameCandidateLimits.MAX_SAMPLES)
        val key = key(projectId, source.sourceId)
        check(key !in activeRuns) { "此素材仍在分析，请先取消或等待完成。" }
        val run = CandidateRun(projectId, source, UUID.randomUUID().toString())
        transaction(db) {
            val previous = readRow(db, projectId, source)?.snapshot ?: empty(projectId, source)
            write(db, previous.copy(status = CandidateAnalysisStatus.RUNNING, completedSamples = 0,
                totalSamples = totalSamples, uniqueFrames = 0), source.metadata.durationUs, run.id)
        }
        activeRuns[key] = run.id
        run
    }

    internal fun checkpoint(run: CandidateRun, progress: CandidateAnalysisProgress): FrameCandidateSnapshot = access { db ->
        transaction(db) { checkpointLocked(db, run, progress) }
    }

    internal fun finish(
        run: CandidateRun,
        status: CandidateAnalysisStatus,
        progress: CandidateAnalysisProgress? = null,
    ): FrameCandidateSnapshot = access { db ->
        require(status in setOf(CandidateAnalysisStatus.COMPLETED, CandidateAnalysisStatus.CANCELLED, CandidateAnalysisStatus.FAILED))
        try {
            transaction(db) {
                val current = if (progress == null) requireRun(db, run).snapshot else checkpointLocked(db, run, progress)
                if (status == CandidateAnalysisStatus.COMPLETED) check(current.completedSamples == current.totalSamples)
                current.copy(status = status).also { write(db, it, run.source.metadata.durationUs, null) }
            }
        } finally {
            // Failed disk commits retain a RUNNING journal row for next-open recovery.
            val key = key(run.projectId, run.source.sourceId)
            if (activeRuns[key] == run.id) activeRuns.remove(key)
        }
    }

    fun setDecision(
        projectId: String,
        source: ImportedSource,
        candidateId: String,
        decision: CandidateDecision,
    ): FrameCandidateSnapshot = setDecisions(projectId, source, listOf(candidateId), decision)

    fun setDecisions(
        projectId: String,
        source: ImportedSource,
        candidateIds: List<String>,
        decision: CandidateDecision,
    ): FrameCandidateSnapshot = edit(projectId, source, candidateIds) { candidate ->
        check(candidate.usedStepId == null || decision == CandidateDecision.KEPT) { "此候选已加入步骤，请在步骤中管理。" }
        candidate.copy(decision = decision)
    }

    /** Caller must supply a real saved step. This does not create or privacy-review a step. */
    fun markUsed(projectId: String, source: ImportedSource, candidateId: String, stepId: String): FrameCandidateSnapshot {
        validId(stepId)
        return edit(projectId, source, listOf(candidateId)) { candidate ->
            check(candidate.usedStepId == null || candidate.usedStepId == stepId) { "此候选已经关联另一个步骤。" }
            candidate.copy(decision = CandidateDecision.KEPT, usedStepId = stepId)
        }
    }

    /** Close the crash window between the real project commit and its candidate association. */
    fun reconcileSavedSteps(projectId: String, source: ImportedSource, steps: List<ProjectStep>): FrameCandidateSnapshot = access { db ->
        validateIdentity(projectId, source)
        transaction(db) {
            val row = readRow(db, projectId, source)?.let { recover(db, it, source) }
                ?: return@transaction empty(projectId, source)
            val reconciled = row.snapshot.candidates.map { candidate ->
                val actual = steps.firstOrNull { it.captureId == "candidate-${candidate.id}" && it.source == source }
                when {
                    actual != null -> candidate.copy(decision = CandidateDecision.KEPT, usedStepId = actual.id)
                    candidate.usedStepId != null && steps.none { it.id == candidate.usedStepId } ->
                        candidate.copy(decision = CandidateDecision.KEPT, usedStepId = null)
                    else -> candidate
                }
            }
            row.snapshot.copy(candidates = reconciled).also {
                if (reconciled != row.snapshot.candidates) write(db, it, source.metadata.durationUs, row.runId)
            }
        }
    }

    private fun edit(
        projectId: String,
        source: ImportedSource,
        candidateIds: List<String>,
        change: (FrameCandidate) -> FrameCandidate,
    ): FrameCandidateSnapshot = access { db ->
        validateIdentity(projectId, source)
        require(candidateIds.isNotEmpty() && candidateIds.size <= FrameCandidateLimits.MAX_CANDIDATES)
        candidateIds.forEach(::validId)
        val ids = candidateIds.toSet()
        transaction(db) {
            val row = readRow(db, projectId, source)?.let { recover(db, it, source) }
                ?: error("候选尚未保存，请先整理候选。")
            check(ids.all { id -> row.snapshot.candidates.any { it.id == id } }) { "候选已不存在，请刷新。" }
            row.snapshot.copy(candidates = row.snapshot.candidates.map { if (it.id in ids) change(it) else it })
                .also { write(db, it, source.metadata.durationUs, row.runId) }
        }
    }

    private fun checkpointLocked(db: SQLiteDatabase, run: CandidateRun, progress: CandidateAnalysisProgress): FrameCandidateSnapshot {
        val current = requireRun(db, run).snapshot
        require(progress.totalSamples == current.totalSamples && progress.completedSamples in current.completedSamples..current.totalSamples &&
            progress.uniqueFrames in current.uniqueFrames..progress.completedSamples) { "候选分析进度无效。" }
        require(progress.candidates.size <= minOf(FrameCandidateLimits.MAX_CANDIDATES, progress.uniqueFrames))
        require(progress.candidates.map { it.id }.distinct().size == progress.candidates.size &&
            progress.candidates.map { it.actualTimeUs }.distinct().size == progress.candidates.size)
        progress.candidates.forEach { validateCandidate(it, run.source) }
        check(progress.candidates.all { it.decision == CandidateDecision.SUGGESTED && it.usedStepId == null })
        // Append-only suggestions across retries. No analyzer output may overwrite a user's
        // decision, saved-step association, or previously saved suggestion; the cap stays hard.
        val merged = current.candidates.toMutableList()
        val ids = merged.mapTo(HashSet()) { it.id }
        progress.candidates.forEach { candidate ->
            if (merged.size < FrameCandidateLimits.MAX_CANDIDATES && ids.add(candidate.id)) merged += candidate
        }
        return current.copy(completedSamples = progress.completedSamples, uniqueFrames = progress.uniqueFrames,
            candidates = merged.sortedBy { it.actualTimeUs }).also {
            write(db, it, run.source.metadata.durationUs, run.id)
        }
    }

    private fun requireRun(db: SQLiteDatabase, run: CandidateRun): Row {
        check(activeRuns[key(run.projectId, run.source.sourceId)] == run.id) { "此分析会话已结束。" }
        val row = readRow(db, run.projectId, run.source) ?: error("分析记录缺失。")
        check(row.runId == run.id && row.snapshot.status == CandidateAnalysisStatus.RUNNING) { "分析会话已改变。" }
        return row
    }

    /** Release only this owner's lease if even a failure/cancellation checkpoint could not save. */
    internal fun abandon(run: CandidateRun) = synchronized(lock) {
        val key = key(run.projectId, run.source.sourceId)
        if (activeRuns[key] == run.id) activeRuns.remove(key)
    }

    private fun recover(db: SQLiteDatabase, row: Row, source: ImportedSource): Row {
        if (row.snapshot.status != CandidateAnalysisStatus.RUNNING ||
            activeRuns[key(row.snapshot.projectId, source.sourceId)] == row.runId) return row
        return Row(row.snapshot.copy(status = CandidateAnalysisStatus.INTERRUPTED), null).also {
            write(db, it.snapshot, source.metadata.durationUs, null)
        }
    }

    private fun readRow(db: SQLiteDatabase, projectId: String, source: ImportedSource): Row? = db.rawQuery(
        "SELECT source_sha,algorithm_version,duration_us,status,run_id,completed_samples,total_samples,unique_frames,candidates_json FROM analyses WHERE project_id=? AND source_id=?",
        arrayOf(projectId, source.sourceId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        check(cursor.getString(0) == source.metadata.sha256.lowercase() && cursor.getLong(2) == source.metadata.durationUs) {
            "候选与当前素材不匹配，已保留原有选择；请重新选择原素材。"
        }
        check(cursor.getInt(1) == FrameCandidateLimits.ALGORITHM_VERSION) { "候选分析版本需要安全迁移，已保留原有选择。" }
        val completed = cursor.getInt(5)
        val total = cursor.getInt(6)
        val unique = cursor.getInt(7)
        check(total in 1..FrameCandidateLimits.MAX_SAMPLES && completed in 0..total && unique in 0..completed)
        val json = cursor.getString(8)
        check(json.length <= 64 * 1024) { "候选记录大小超限。" }
        val items = JSONArray(json)
        check(items.length() <= FrameCandidateLimits.MAX_CANDIDATES)
        val candidates = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            FrameCandidate(item.getString("id"), source.sourceId, item.getLong("actualTimeUs"), item.getLong("timePrecisionUs"),
                item.getInt("width"), item.getInt("height"), item.getDouble("changeScore").toFloat(),
                CandidateReason.valueOf(item.getString("reason")), CandidateDecision.valueOf(item.getString("decision")),
                if (item.isNull("usedStepId")) null else item.getString("usedStepId"))
                .also { validateCandidate(it, source) }
        }
        check(candidates.map { it.id }.distinct().size == candidates.size && candidates.map { it.actualTimeUs }.distinct().size == candidates.size)
        val status = CandidateAnalysisStatus.valueOf(cursor.getString(3))
        check(status != CandidateAnalysisStatus.COMPLETED || completed == total)
        val runId = if (cursor.isNull(4)) null else cursor.getString(4).also(::validId)
        check((status == CandidateAnalysisStatus.RUNNING) == (runId != null))
        Row(FrameCandidateSnapshot(projectId, source.sourceId, cursor.getString(0), cursor.getInt(1), status,
            completed, total, unique, candidates.sortedBy { it.actualTimeUs }), runId)
    }

    private fun write(db: SQLiteDatabase, snapshot: FrameCandidateSnapshot, durationUs: Long, runId: String?) {
        val json = JSONArray().apply { snapshot.candidates.forEach { candidate -> put(JSONObject().apply {
            put("id", candidate.id); put("actualTimeUs", candidate.actualTimeUs); put("timePrecisionUs", candidate.timePrecisionUs)
            put("width", candidate.width); put("height", candidate.height); put("changeScore", candidate.changeScore.toDouble())
            put("reason", candidate.reason.name); put("decision", candidate.decision.name)
            put("usedStepId", candidate.usedStepId ?: JSONObject.NULL)
        }) } }
        db.insertWithOnConflict("analyses", null, ContentValues().apply {
            put("project_id", snapshot.projectId); put("source_id", snapshot.sourceId); put("source_sha", snapshot.sourceSha256)
            put("algorithm_version", snapshot.algorithmVersion); put("duration_us", durationUs); put("status", snapshot.status.name)
            if (runId == null) putNull("run_id") else put("run_id", runId)
            put("completed_samples", snapshot.completedSamples); put("total_samples", snapshot.totalSamples)
            put("unique_frames", snapshot.uniqueFrames); put("candidates_json", json.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "候选记录保存失败。" } }
    }

    private fun validateCandidate(candidate: FrameCandidate, source: ImportedSource) {
        validId(candidate.id)
        require(candidate.sourceId == source.sourceId && candidate.id == FrameCandidateLimits.stableId(source, candidate.actualTimeUs))
        require(candidate.actualTimeUs in 0..source.metadata.durationUs && candidate.timePrecisionUs == 1_000L &&
            candidate.actualTimeUs % candidate.timePrecisionUs == 0L)
        require(candidate.width in 1..2400 && candidate.height in 1..2400 && candidate.width.toLong() * candidate.height <= 1080L * 2400)
        require(candidate.changeScore.isFinite() && candidate.changeScore in 0f..1f)
        candidate.usedStepId?.let { validId(it); check(candidate.decision == CandidateDecision.KEPT) }
    }

    private fun validateIdentity(projectId: String, source: ImportedSource) {
        validId(projectId); validId(source.sourceId)
        require(source.metadata.sha256.matches(Regex("[0-9a-fA-F]{64}")) && source.metadata.durationUs in 1..180_000_000L)
        require(source.privateRelativePath == "sources/${source.sourceId}.mp4")
    }

    private fun empty(projectId: String, source: ImportedSource) = FrameCandidateSnapshot(projectId, source.sourceId,
        source.metadata.sha256.lowercase(), FrameCandidateLimits.ALGORITHM_VERSION)

    private fun key(projectId: String, sourceId: String) = "${root.path}/$projectId/$sourceId"

    private fun <T> access(block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        check(file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == root) { "候选记录必须留在本机私有目录。" }
        block(helper.writableDatabase)
    }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        return try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    private data class Row(val snapshot: FrameCandidateSnapshot, val runId: String?)

    private class Database(context: Context, path: String) : SQLiteOpenHelper(context, path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE analyses (
                project_id TEXT NOT NULL, source_id TEXT NOT NULL, source_sha TEXT NOT NULL,
                algorithm_version INTEGER NOT NULL, duration_us INTEGER NOT NULL CHECK(duration_us>0),
                status TEXT NOT NULL, run_id TEXT, completed_samples INTEGER NOT NULL CHECK(completed_samples>=0),
                total_samples INTEGER NOT NULL CHECK(total_samples>0 AND total_samples<=361),
                unique_frames INTEGER NOT NULL CHECK(unique_frames>=0 AND unique_frames<=completed_samples),
                candidates_json TEXT NOT NULL, PRIMARY KEY(project_id,source_id),
                CHECK(completed_samples<=total_samples), CHECK((status='RUNNING')=(run_id IS NOT NULL))
            )""")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("候选数据库需要安全迁移，请保留本机数据。")
        }
    }

    private companion object {
        val lock = Any()
        val activeRuns = mutableMapOf<String, String>()
        fun validId(id: String) { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "候选对象标识无效。" } }
    }
}

internal data class CandidateRun(val projectId: String, val source: ImportedSource, val id: String)
