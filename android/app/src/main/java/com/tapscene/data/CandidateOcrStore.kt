package com.tapscene.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tapscene.media.FrameCandidate
import com.tapscene.media.FrameCandidateLimits
import com.tapscene.media.ImportedSource
import com.tapscene.ocr.OfflineOcrEngine
import com.tapscene.ocr.OcrCancellation
import com.tapscene.ocr.OcrResult
import com.tapscene.ocr.OcrWord
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Raw text is private, unreviewed source data. Never include it in a release or a log. */
enum class CandidateOcrStatus { NOT_STARTED, RUNNING, COMPLETED, CANCELLED, INTERRUPTED, FAILED }

/** Opaque store identity plus the exact scope under which a snapshot was read. */
data class CandidateOcrScope(
    val storeId: String,
    val projectId: String,
    val sourceId: String,
    val sourceSha256: String,
    val deletionEpoch: Long,
)

/** Monotonic process-local mirror of durable deletion fences; contains no OCR or paths. */
data class CandidateOcrInvalidation(
    val epoch: Long = 0,
    val projectIds: Set<String> = emptySet(),
    val sourceIds: Set<String> = emptySet(),
) {
    fun affects(projectId: String?, sourceId: String?): Boolean = projectId in projectIds || sourceId in sourceIds
}

data class CandidateOcrSnapshot(
    val status: CandidateOcrStatus = CandidateOcrStatus.NOT_STARTED,
    val completed: Int = 0,
    val total: Int = 0,
    val results: Map<String, OcrResult> = emptyMap(),
    val scope: CandidateOcrScope? = null,
)

/**
 * Independent, no-backup raw OCR journal. No pixels, source paths, authored text, steps,
 * decisions or hotspots are stored here. Each frame and its progress commit atomically.
 * All instances share leases; an abandoned RUNNING row becomes retryable on next read.
 * Call on Dispatchers.IO. Deletion hooks touch only this database, with exact identifiers.
 */
class CandidateOcrStore(context: Context) {
    private val root = context.applicationContext.noBackupFilesDir.canonicalFile
    private val file = File(root, "candidate-ocr.sqlite")
    private val helper = Database(context.applicationContext, file.path)
    private val invalidation = synchronized(lock) { invalidationsByRoot.getOrPut(root.path) { InvalidationRegistry() } }
    val invalidations: StateFlow<CandidateOcrInvalidation> = invalidation.state.asStateFlow()

    /**
     * Apply a returned snapshot only while holding the same monitor as deletion. A check
     * outside this monitor would leave a check/delete/apply race. Epoch changes caused by
     * another scope are harmless; this exact scope must still have no monotonic fence.
     * No disk read is performed here, and the callback must be short and non-suspending.
     */
    fun applyIfCurrent(snapshot: CandidateOcrSnapshot, projectId: String?, source: ImportedSource?, apply: () -> Unit): Boolean = synchronized(lock) {
        val scope = snapshot.scope ?: return@synchronized false
        val current = invalidation.state.value
        if (scope.storeId != invalidation.storeId || scope.projectId != projectId || source == null ||
            scope.sourceId != source.sourceId || scope.sourceSha256 != source.metadata.sha256.lowercase() ||
            scope.deletionEpoch > current.epoch || current.affects(projectId, source.sourceId)) return@synchronized false
        apply()
        true
    }

    /** Current in-memory fence, hydrated from SQLite before any result snapshot is returned. */
    fun isInvalidated(projectId: String?, sourceId: String?): Boolean = synchronized(lock) {
        invalidation.state.value.affects(projectId, sourceId)
    }

    fun read(projectId: String, source: ImportedSource, candidates: List<FrameCandidate>): CandidateOcrSnapshot = access { db ->
        validateIdentity(projectId, source)
        validateCandidates(source, candidates)
        transaction(db) {
            val row = recover(db, projectId, source)
            snapshot(db, projectId, source, candidates, row)
        }
    }

    internal fun begin(projectId: String, source: ImportedSource, candidates: List<FrameCandidate>): CandidateOcrRun = access { db ->
        validateIdentity(projectId, source)
        validateCandidates(source, candidates)
        require(candidates.isNotEmpty()) { "请先选择需要识别的候选。" }
        val key = leaseKey(projectId, source.sourceId)
        check(key !in activeRuns) { "此素材的文字识别仍在运行。" }
        val run = CandidateOcrRun(projectId, source, candidates.toList(), UUID.randomUUID().toString())
        transaction(db) {
            check(!isDeleted(db, "project", projectId) && !isDeleted(db, "source", source.sourceId)) {
                "此项目或原片已删除，请重新选择素材。"
            }
            recover(db, projectId, source)
            val completed = countResults(db, projectId, source, candidates)
            writeJob(db, projectId, source, JobRow(CandidateOcrStatus.RUNNING, run.id, completed,
                candidates.size, encodeTargets(candidates)))
        }
        activeRuns[key] = run
        run
    }

    internal fun progress(run: CandidateOcrRun): CandidateOcrSnapshot = access { db ->
        val row = requireRun(db, run)
        snapshot(db, run.projectId, run.source, run.candidates, row)
    }

    /** Validation and one whole frame insert are in the same transaction as progress. */
    internal fun saveFrame(run: CandidateOcrRun, candidate: FrameCandidate, result: OcrResult): CandidateOcrSnapshot = access { db ->
        transaction(db) {
            val row = requireRun(db, run)
            check(run.candidates.any { binding(it) == binding(candidate) }) { "识别候选已改变，请刷新。" }
            validateResult(candidate, result)
            val encoded = encodeResult(result)
            val values = identityValues(run.projectId, run.source).apply {
                put("candidate_id", candidate.id); put("actual_pts_us", candidate.actualTimeUs)
                put("precision_us", candidate.timePrecisionUs); put("width", candidate.width); put("height", candidate.height)
                put("result_json", encoded)
            }
            check(db.insertWithOnConflict("results", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                "文字识别结果保存失败，请重试。"
            }
            val next = row.copy(completed = countResults(db, run.projectId, run.source, run.candidates))
            writeJob(db, run.projectId, run.source, next)
            snapshot(db, run.projectId, run.source, run.candidates, next)
        }
    }

    internal fun finish(run: CandidateOcrRun, status: CandidateOcrStatus): CandidateOcrSnapshot = access { db ->
        require(status in setOf(CandidateOcrStatus.COMPLETED, CandidateOcrStatus.CANCELLED, CandidateOcrStatus.FAILED))
        try {
            transaction(db) {
                val row = requireRun(db, run, allowCancelled = true)
                if (status == CandidateOcrStatus.COMPLETED) check(row.completed == row.total)
                val next = row.copy(status = status, runId = null)
                writeJob(db, run.projectId, run.source, next)
                snapshot(db, run.projectId, run.source, run.candidates, next)
            }
        } finally { abandon(run) }
    }

    internal fun abandon(run: CandidateOcrRun) = synchronized(lock) {
        val key = leaseKey(run.projectId, run.source.sourceId)
        if (activeRuns[key]?.id == run.id) activeRuns.remove(key)
    }

    /** Call BEFORE deleting a source. Revokes live work so late results cannot recreate rows. */
    fun deleteSource(sourceId: String) = access { db ->
        validId(sourceId)
        revoke { it.source.sourceId == sourceId }
        transaction(db) {
            markDeleted(db, "source", sourceId)
            db.delete("results", "source_id=?", arrayOf(sourceId))
            db.delete("jobs", "source_id=?", arrayOf(sourceId))
        }
        publishFences(sources = setOf(sourceId))
        Unit
    }

    /** Call BEFORE deleting a project. This does not delete source files or other projects. */
    fun deleteProject(projectId: String) = access { db ->
        validId(projectId)
        revoke { it.projectId == projectId }
        transaction(db) {
            markDeleted(db, "project", projectId)
            db.delete("results", "project_id=?", arrayOf(projectId))
            db.delete("jobs", "project_id=?", arrayOf(projectId))
        }
        publishFences(projects = setOf(projectId))
        Unit
    }

    private fun isDeleted(db: SQLiteDatabase, kind: String, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM deletion_fences WHERE kind=? AND object_id=?", arrayOf(kind, id)).use { it.moveToFirst() }

    private fun markDeleted(db: SQLiteDatabase, kind: String, id: String) {
        check(db.insertWithOnConflict("deletion_fences", null, ContentValues().apply {
            put("kind", kind); put("object_id", id)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L || isDeleted(db, kind, id)) { "文字识别清理未完成，请重试。" }
    }

    private fun revoke(matches: (CandidateOcrRun) -> Boolean) {
        val keys = activeRuns.filter { (key, run) -> key.startsWith("${root.path}/") && matches(run) }.keys.toList()
        keys.forEach { activeRuns.remove(it)?.cancellation?.cancel() }
    }

    private fun requireRun(db: SQLiteDatabase, run: CandidateOcrRun, allowCancelled: Boolean = false): JobRow {
        check(activeRuns[leaseKey(run.projectId, run.source.sourceId)]?.id == run.id &&
            (allowCancelled || !run.cancellation.isCancelled)) { "此次文字识别已停止。" }
        val row = readJob(db, run.projectId, run.source) ?: error("文字识别任务已移除。")
        check(row.status == CandidateOcrStatus.RUNNING && row.runId == run.id && row.targets == encodeTargets(run.candidates)) {
            "文字识别任务已改变。"
        }
        return row
    }

    private fun recover(db: SQLiteDatabase, projectId: String, source: ImportedSource): JobRow? {
        val row = readJob(db, projectId, source) ?: return null
        if (row.status != CandidateOcrStatus.RUNNING || activeRuns[leaseKey(projectId, source.sourceId)]?.id == row.runId) return row
        return row.copy(status = CandidateOcrStatus.INTERRUPTED, runId = null).also { writeJob(db, projectId, source, it) }
    }

    private fun snapshot(db: SQLiteDatabase, projectId: String, source: ImportedSource,
        candidates: List<FrameCandidate>, row: JobRow?): CandidateOcrSnapshot = CandidateOcrSnapshot(
        status = row?.status ?: CandidateOcrStatus.NOT_STARTED,
        completed = row?.completed ?: 0,
        total = row?.total ?: 0,
        results = candidates.mapNotNull { candidate -> readResult(db, projectId, source, candidate)?.let { candidate.id to it } }.toMap(),
        scope = CandidateOcrScope(invalidation.storeId, projectId, source.sourceId, source.metadata.sha256.lowercase(),
            invalidation.state.value.epoch),
    )

    private fun countResults(db: SQLiteDatabase, projectId: String, source: ImportedSource, candidates: List<FrameCandidate>): Int =
        candidates.count { readResult(db, projectId, source, it) != null }

    private fun readResult(db: SQLiteDatabase, projectId: String, source: ImportedSource, candidate: FrameCandidate): OcrResult? =
        db.rawQuery("SELECT result_json FROM results WHERE $IDENTITY_WHERE AND candidate_id=? AND actual_pts_us=? AND precision_us=? AND width=? AND height=?",
            identityArgs(projectId, source) + arrayOf(candidate.id, candidate.actualTimeUs.toString(), candidate.timePrecisionUs.toString(),
                candidate.width.toString(), candidate.height.toString())).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val encoded = cursor.getString(0)
            check(encoded.length <= MAX_JSON_CHARS) { "文字识别记录超出大小限制。" }
            val json = JSONObject(encoded)
            val words = json.getJSONArray("words")
            check(words.length() <= MAX_WORDS) { "文字识别记录超出数量限制。" }
            OcrResult(candidate.width, candidate.height, List(words.length()) { index ->
                val word = words.getJSONObject(index)
                OcrWord(text = word.getString("text"), left = word.getInt("left"), top = word.getInt("top"),
                    right = word.getInt("right"), bottom = word.getInt("bottom"), confidence = word.getDouble("confidence").toFloat(),
                    blockIndex = word.getInt("block"), lineIndex = word.getInt("line"))
            }, json.getBoolean("truncated"), OfflineOcrEngine.ENGINE_VERSION, OfflineOcrEngine.MODEL_VERSION)
                .also { validateResult(candidate, it) }
        }

    private fun readJob(db: SQLiteDatabase, projectId: String, source: ImportedSource): JobRow? =
        db.rawQuery("SELECT status,run_id,completed,total,targets_json FROM jobs WHERE $IDENTITY_WHERE", identityArgs(projectId, source)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val status = CandidateOcrStatus.valueOf(cursor.getString(0))
            val runId = if (cursor.isNull(1)) null else cursor.getString(1).also(::validId)
            val completed = cursor.getInt(2)
            val total = cursor.getInt(3)
            val targets = cursor.getString(4)
            check(total in 1..FrameCandidateLimits.MAX_CANDIDATES && completed in 0..total && targets.length <= 16_384)
            check((status == CandidateOcrStatus.RUNNING) == (runId != null))
            check(status != CandidateOcrStatus.COMPLETED || completed == total)
            val ids = JSONArray(targets)
            check(ids.length() == total)
            val seen = HashSet<String>()
            repeat(ids.length()) { index ->
                val target = ids.getJSONObject(index)
                val id = target.getString("id").also(::validId)
                check(seen.add(id))
                validateBinding(source, id, target.getLong("pts"), target.getLong("precision"), target.getInt("width"), target.getInt("height"))
            }
            JobRow(status, runId, completed, total, targets)
        }

    private fun writeJob(db: SQLiteDatabase, projectId: String, source: ImportedSource, row: JobRow) {
        check(db.insertWithOnConflict("jobs", null, identityValues(projectId, source).apply {
            put("status", row.status.name); if (row.runId == null) putNull("run_id") else put("run_id", row.runId)
            put("completed", row.completed); put("total", row.total); put("targets_json", row.targets)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "文字识别进度保存失败，请重试。" }
    }

    private fun identityValues(projectId: String, source: ImportedSource) = ContentValues().apply {
        put("project_id", projectId); put("source_id", source.sourceId); put("source_sha", source.metadata.sha256.lowercase())
        put("engine_version", OfflineOcrEngine.ENGINE_VERSION); put("model_version", OfflineOcrEngine.MODEL_VERSION)
    }

    private fun identityArgs(projectId: String, source: ImportedSource) = arrayOf(projectId, source.sourceId,
        source.metadata.sha256.lowercase(), OfflineOcrEngine.ENGINE_VERSION, OfflineOcrEngine.MODEL_VERSION)

    private fun validateIdentity(projectId: String, source: ImportedSource) {
        validId(projectId); validId(source.sourceId)
        require(source.metadata.sha256.matches(Regex("[0-9a-fA-F]{64}")) && source.metadata.durationUs in 1..180_000_000L)
        require(source.privateRelativePath == "sources/${source.sourceId}.mp4") { "素材归属无效，请重新选择。" }
    }

    private fun validateCandidates(source: ImportedSource, candidates: List<FrameCandidate>) {
        require(candidates.size <= FrameCandidateLimits.MAX_CANDIDATES && candidates.map { it.id }.distinct().size == candidates.size)
        candidates.forEach { candidate ->
            validId(candidate.id)
            require(candidate.sourceId == source.sourceId)
            validateBinding(source, candidate.id, candidate.actualTimeUs, candidate.timePrecisionUs, candidate.width, candidate.height)
        }
    }

    private fun validateBinding(source: ImportedSource, id: String, pts: Long, precision: Long, width: Int, height: Int) {
        require(id == FrameCandidateLimits.stableId(source, pts) && pts in 0..source.metadata.durationUs && precision == 1_000L && pts % precision == 0L)
        require(width in 1..2400 && height in 1..2400 && width.toLong() * height <= 1080L * 2400)
    }

    private fun validateResult(candidate: FrameCandidate, result: OcrResult) {
        require(result.width == candidate.width && result.height == candidate.height &&
            result.engineVersion == OfflineOcrEngine.ENGINE_VERSION && result.modelVersion == OfflineOcrEngine.MODEL_VERSION) {
            "识别结果与候选画面不一致，请重试。"
        }
        require(result.words.size <= MAX_WORDS && result.words.sumOf { it.text.length.toLong() } <= MAX_TEXT_CHARS)
        result.words.forEach { word ->
            require(word.text.isNotBlank() && word.text.length <= MAX_WORD_CHARS && word.text.none { it == '\u0000' })
            require(word.left >= 0 && word.top >= 0 && word.right > word.left && word.bottom > word.top &&
                word.right <= result.width && word.bottom <= result.height)
            require(word.confidence.isFinite() && word.confidence in 0f..100f && word.blockIndex in 0..65_535 && word.lineIndex in 0..65_535)
        }
    }

    private fun encodeResult(result: OcrResult): String = JSONObject().apply {
        put("truncated", result.truncated)
        put("words", JSONArray().apply { result.words.forEach { word -> put(JSONObject().apply {
            put("text", word.text); put("left", word.left); put("top", word.top); put("right", word.right); put("bottom", word.bottom)
            put("confidence", word.confidence.toDouble()); put("block", word.blockIndex); put("line", word.lineIndex)
        }) } })
    }.toString().also { require(it.length <= MAX_JSON_CHARS) { "文字识别记录超出大小限制。" } }

    private fun encodeTargets(candidates: List<FrameCandidate>): String = JSONArray().apply { candidates.forEach { candidate ->
        put(JSONObject().apply { put("id", candidate.id); put("pts", candidate.actualTimeUs); put("precision", candidate.timePrecisionUs)
            put("width", candidate.width); put("height", candidate.height) })
    } }.toString()

    private fun binding(candidate: FrameCandidate) = listOf(candidate.id, candidate.sourceId, candidate.actualTimeUs,
        candidate.timePrecisionUs, candidate.width, candidate.height)
    private fun leaseKey(projectId: String, sourceId: String) = "${root.path}/$projectId/$sourceId"

    private fun <T> access(block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        check(file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == root) { "文字识别记录必须保存在本机私有目录。" }
        val db = helper.writableDatabase
        if (!invalidation.hydrated) {
            val projects = mutableSetOf<String>()
            val sources = mutableSetOf<String>()
            db.rawQuery("SELECT kind,object_id FROM deletion_fences", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(1).also(::validId)
                    when (cursor.getString(0)) {
                        "project" -> projects.add(id)
                        "source" -> sources.add(id)
                        else -> error("文字识别清理记录无效。")
                    }
                }
            }
            publishFences(projects, sources)
            invalidation.hydrated = true
        }
        block(db)
    }

    private fun publishFences(projects: Set<String> = emptySet(), sources: Set<String> = emptySet()) {
        val current = invalidation.state.value
        val nextProjects = current.projectIds + projects
        val nextSources = current.sourceIds + sources
        if (nextProjects != current.projectIds || nextSources != current.sourceIds) {
            invalidation.state.value = CandidateOcrInvalidation(current.epoch + 1, nextProjects, nextSources)
        }
    }

    private class InvalidationRegistry {
        val storeId: String = UUID.randomUUID().toString()
        val state = MutableStateFlow(CandidateOcrInvalidation())
        var hydrated = false
    }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        return try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    private data class JobRow(val status: CandidateOcrStatus, val runId: String?, val completed: Int, val total: Int, val targets: String)

    private class Database(context: Context, path: String) : SQLiteOpenHelper(context, path, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            // Deleted raw text should not be left in reusable SQLite pages. Journals remain
            // inside the same no-backup private directory; no export or pixel cache is created.
            db.disableWriteAheadLogging()
            db.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 1) { "本机文字识别清理设置失败。" }
            }
        }
        override fun onCreate(db: SQLiteDatabase) {
            // Only opaque identities remain after deletion. They fence a new instance that
            // races source-file removal; a newly imported source/project has a new UUID.
            db.execSQL("""CREATE TABLE deletion_fences (
                kind TEXT NOT NULL CHECK(kind IN ('project','source')), object_id TEXT NOT NULL,
                PRIMARY KEY(kind,object_id)
            )""")
            db.execSQL("""CREATE TABLE results (
                project_id TEXT NOT NULL, source_id TEXT NOT NULL, source_sha TEXT NOT NULL,
                engine_version TEXT NOT NULL, model_version TEXT NOT NULL, candidate_id TEXT NOT NULL,
                actual_pts_us INTEGER NOT NULL CHECK(actual_pts_us>=0), precision_us INTEGER NOT NULL CHECK(precision_us=1000),
                width INTEGER NOT NULL CHECK(width>0 AND width<=2400), height INTEGER NOT NULL CHECK(height>0 AND height<=2400),
                result_json TEXT NOT NULL CHECK(length(result_json)<=524288),
                PRIMARY KEY(project_id,source_id,source_sha,engine_version,model_version,candidate_id,actual_pts_us,precision_us,width,height),
                CHECK(width*height<=2592000), CHECK(actual_pts_us%precision_us=0)
            )""")
            db.execSQL("""CREATE TABLE jobs (
                project_id TEXT NOT NULL, source_id TEXT NOT NULL, source_sha TEXT NOT NULL,
                engine_version TEXT NOT NULL, model_version TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('RUNNING','COMPLETED','CANCELLED','INTERRUPTED','FAILED')),
                run_id TEXT, completed INTEGER NOT NULL CHECK(completed>=0), total INTEGER NOT NULL CHECK(total>0 AND total<=30),
                targets_json TEXT NOT NULL CHECK(length(targets_json)<=16384),
                PRIMARY KEY(project_id,source_id,source_sha,engine_version,model_version),
                CHECK(completed<=total), CHECK((status='RUNNING')=(run_id IS NOT NULL)),
                CHECK(status!='COMPLETED' OR completed=total)
            )""")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("文字识别数据库需要安全迁移，已保留本机数据。")
        }
    }

    private companion object {
        val lock = Any()
        val activeRuns = mutableMapOf<String, CandidateOcrRun>()
        val invalidationsByRoot = mutableMapOf<String, InvalidationRegistry>()
        const val IDENTITY_WHERE = "project_id=? AND source_id=? AND source_sha=? AND engine_version=? AND model_version=?"
        const val MAX_WORDS = 256
        const val MAX_WORD_CHARS = 512
        const val MAX_TEXT_CHARS = 8_192L
        const val MAX_JSON_CHARS = 524_288
        fun validId(id: String) { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "文字识别对象标识无效。" } }
    }
}

internal data class CandidateOcrRun(
    val projectId: String,
    val source: ImportedSource,
    val candidates: List<FrameCandidate>,
    val id: String,
    val cancellation: OcrCancellation = OcrCancellation(),
)
