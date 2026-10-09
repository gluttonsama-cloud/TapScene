package com.tapscene.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import com.tapscene.media.MediaInputPolicy
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriterValidation
import com.tapscene.media.SourceMetadata
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private offline draft graph. Call from Dispatchers.IO; no method does network or shares media.
 * All instances in this process share one lock. SQLite transactions and foreign keys protect
 * graph edits; an asset is copied, synced, technically checked, then atomically renamed before
 * its metadata commits. Reviewed step/transition inputs are actual outputs, never raw frames or clips.
 */
class ProjectStore(context: Context) {
    private val app = context.applicationContext
    private val root = context.applicationContext.noBackupFilesDir.canonicalFile
    private val helper = Database(context.applicationContext, File(root, "projects.sqlite").path)

    fun listProjects(): List<ProjectSummary> = access { db ->
        db.rawQuery("$SUMMARY_SQL ORDER BY p.updated_at DESC, p.project_id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(summary(cursor)) }
        }
    }

    fun readProject(projectId: String): ProjectSnapshot? = access { db -> snapshot(db, projectId) }

    /** Start before reading/resuming drafts. Persisted generations reject writers from old editors,
     * even when they use another ProjectStore instance. This never changes the formal revision. */
    fun beginEditorDraftSession(projectId: String): Long = access { db -> transaction(db) {
        validId(projectId)
        require(count(db, "projects", "project_id=?", projectId) == 1) { "项目已不存在。" }
        val previous = currentEditorDraftSession(db, projectId) ?: 0L
        check(previous < Long.MAX_VALUE) { "编辑暂存会话已达上限，请保留本机数据。" }
        val next = previous + 1
        check(db.insertWithOnConflict("editor_draft_sessions", null, ContentValues().apply {
            put("project_id", projectId); put("generation", next)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "无法开始编辑暂存，请重试。" }
        next
    } }

    /** All-or-error read: an unreadable record is retained and reported, never silently discarded. */
    fun readEditorDrafts(projectId: String): Map<String, StoredEditorDraft> = access { db ->
        validId(projectId)
        db.rawQuery("SELECT state_id,draft_json FROM editor_drafts WHERE project_id=? ORDER BY state_id",
            arrayOf(projectId)).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val stepId = cursor.getString(0)
                    val draft = try { EditorDraftCodec.decode(cursor.getString(1)) }
                    catch (failure: Exception) { throw IllegalStateException("无法读取步骤编辑暂存；原记录已保留，请重试。", failure) }
                    put(stepId, draft)
                }
            }
        }
    }

    /** The workspace serializes one writer with save/discard and per-step event generations.
     * This session guard additionally prevents old project sessions and deleted targets writing.
     * Null clears only this row. A failed size/codec/SQLite check leaves the last good row intact. */
    fun writeEditorDraft(projectId: String, stepId: String, session: Long, draft: StoredEditorDraft?): Boolean = access { db ->
        validId(projectId); validId(stepId)
        transaction(db) {
            if (currentEditorDraftSession(db, projectId) != session ||
                count(db, "states", "project_id=? AND state_id=?", projectId, stepId) != 1) return@transaction false
            if (draft == null) db.delete("editor_drafts", "project_id=? AND state_id=?", arrayOf(projectId, stepId))
            else {
                val encoded = EditorDraftCodec.encode(draft)
                check(db.insertWithOnConflict("editor_drafts", null, ContentValues().apply {
                    put("project_id", projectId); put("state_id", stepId); put("draft_json", encoded)
                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "步骤编辑暂存失败，请重试。" }
            }
            true
        }
    }

    fun clearEditorDraft(projectId: String, stepId: String, session: Long): Boolean =
        writeEditorDraft(projectId, stepId, session, null)

    private fun currentEditorDraftSession(db: SQLiteDatabase, projectId: String): Long? =
        db.rawQuery("SELECT generation FROM editor_draft_sessions WHERE project_id=?", arrayOf(projectId)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

    fun createProject(title: String, goal: String = ""): ProjectSnapshot {
        val cleanTitle = text(title, "项目名称", 120)
        val cleanGoal = text(goal, "项目目标", 1_000, allowEmpty = true)
        return access { db -> transaction(db) {
            val id = newId()
            val now = System.currentTimeMillis()
            db.insertOrThrow("projects", null, ContentValues().apply {
                put("project_id", id); put("title", cleanTitle); put("goal", cleanGoal)
                put("created_at", now); put("updated_at", now); put("draft_revision", 1L)
                putNull("start_state_id")
            })
            requireSnapshot(db, id)
        } }
    }

    fun renameProject(projectId: String, title: String, goal: String? = null): ProjectSnapshot {
        val cleanTitle = text(title, "项目名称", 120)
        val cleanGoal = goal?.let { text(it, "项目目标", 1_000, allowEmpty = true) }
        return edit(projectId) { db ->
            db.update("projects", ContentValues().apply {
                put("title", cleanTitle)
                if (cleanGoal != null) put("goal", cleanGoal)
            }, "project_id=?", arrayOf(projectId))
        }
    }

    /** Source files are workspace-owned and are NEVER deleted by this store. */
    fun deleteProject(projectId: String): ProjectDeletionResult = access { db ->
        // OCR is derived private source data, not a saved step or a sealed release. Revoke
        // live writers first so a cancelled analysis cannot recreate it after deletion.
        requireSnapshot(db, projectId)
        CandidateOcrStore(app).deleteProject(projectId)
        val result = transaction(db) {
            val current = requireSnapshot(db, projectId)
            val hotspots = current.steps.sumOf { it.hotspots.size }
            queueProjectAssets(db, projectId)
            db.update("projects", ContentValues().apply { putNull("start_state_id") },
                "project_id=?", arrayOf(projectId))
            db.delete("projects", "project_id=?", arrayOf(projectId))
            ProjectDeletionResult(current.steps.size, hotspots, edgeCount(current), current.steps.size + transitions(current).size + current.steps.sumOf { step -> step.regions.count { it.asset != null } })
        }
        // Cleanup cannot turn a committed deletion into a reported failure. A journal row stays
        // until its exact, no-longer-referenced asset is gone, including across process restart.
        cleanupPending(db)
        result.copy(pendingAssetCleanupCount = pendingCleanupCount(db, projectId, result.pendingAssetCleanupCount))
    }

    /**
     * Caller must have reviewed input.file itself. Cancellation before the commit removes only
     * this invocation's copies. After commit, cancellation leaves the saved step intact, even if
     * the dispatcher cannot deliver the return value; reload the project to establish the result.
     * Caller also serializes workspace source deletion with this whole operation.
     */
    suspend fun addReviewedStep(
        projectId: String,
        input: ReviewedStepInput,
        title: String,
        description: String = "",
        stepId: String = UUID.randomUUID().toString(),
    ): ProjectSnapshot = importReviewedStep(projectId, input, title, description, stepId)

    /** Replace only the reviewed safe output. Regions retain their bounds but lose crops/review;
     * sealed releases have separate copies and remain unchanged. Existing actions stay authored. */
    suspend fun replaceReviewedStep(projectId: String, stepId: String, expectedRevision: Long,
        input: ReviewedStepInput): ProjectSnapshot {
        val step = readProject(projectId)?.steps?.singleOrNull { it.id == stepId } ?: error("步骤已不存在。")
        return importReviewedStep(projectId, input, step.title, step.description, stepId, true, expectedRevision)
    }

    private suspend fun importReviewedStep(projectId: String, input: ReviewedStepInput, title: String,
        description: String, stepId: String, replacing: Boolean = false, expectedRevision: Long? = null): ProjectSnapshot {
        validId(projectId)
        validId(stepId)
        val cleanTitle = text(title, "步骤标题", 120)
        val cleanDescription = text(description, "步骤说明", 4_000, allowEmpty = true)
        val frozen = input.copy(masks = input.masks.toList())
        require(frozen.captureId.isNotBlank() && frozen.captureId.length <= 160 &&
            frozen.captureId.none { it.isISOControl() }) { "步骤保存令牌无效。" }
        val alreadySaved = access { db ->
            val project = requireSnapshot(db, projectId)
            if (replacing) {
                require(project.steps.any { it.id == stepId }) { "待替换步骤已不存在。" }
                require(project.steps.none { it.captureId == frozen.captureId && it.id != stepId }) { "复核令牌属于另一个步骤。" }
            }
            if (hasMatchingCapture(project, frozen)) project else {
                check(!replacing || project.project.revision == expectedRevision) { "草稿已改变，底图未替换。" }
                require(replacing || project.steps.size < ProjectLimits.MAX_STEPS) { "每个项目最多 40 个步骤。" }
                null
            }
        }
        if (alreadySaved != null) return alreadySaved
        validateInput(frozen)
        val assetId = newId()
        val relativePath = assetPath(projectId, assetId)
        // Register before creating any owned file. A killed process therefore leaves an exact
        // recovery record, not an untracked partial image. Active IDs protect concurrent stores
        // in this process; a fresh process starts with no active IDs and can recover the journal.
        access { db ->
            transaction(db) {
                db.insertOrThrow("asset_imports", null, ContentValues().apply {
                    put("project_id", projectId); put("asset_id", assetId)
                })
            }
            activeImports.add(importKey(assetId))
        }
        val stagingRoot = File(root, "project-staging")
        val operationDirectory = File(stagingRoot, assetId)
        val temporary = File(operationDirectory, "candidate.part")
        var committed = false
        var failure: Throwable? = null
        try {
            privateDirectory(stagingRoot, root)
            check(operationDirectory.mkdir()) { "无法建立步骤暂存目录，请重试。" }
            val owner = currentCoroutineContext()
            val digest = MessageDigest.getInstance("SHA-256")
            var byteLength = 0L
            frozen.file.inputStream().use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        owner.ensureActive()
                        val count = source.read(buffer, 0,
                            minOf(buffer.size.toLong(), MAX_PNG_BYTES - byteLength + 1).toInt())
                        if (count < 0) break
                        check(count > 0) { "无法继续读取已复核画面。" }
                        byteLength += count
                        require(byteLength <= MAX_PNG_BYTES) { "步骤 PNG 超过 50 MiB。" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(byteLength > 0 && temporary.length() == byteLength) { "步骤 PNG 为空或复制不完整。" }
            check(hex(digest.digest()) == frozen.sha256.lowercase()) { "画面已改变，请重新生成并复核。" }
            SafeMediaWriterValidation.verifyPng(temporary, frozen.width, frozen.height, frozen.masks)
            // Verify the actual stored bytes again, rather than trusting candidate metadata.
            check(sha256(temporary) == frozen.sha256.lowercase()) { "步骤画面校验失败，请重新复核。" }
            owner.ensureActive()
            return withContext(NonCancellable) {
                synchronized(lock) {
                    val db = helper.writableDatabase
                    val saved = transaction(db) {
                        val current = requireSnapshot(db, projectId)
                        require(!replacing || current.steps.none { it.captureId == frozen.captureId && it.id != stepId }) {
                            "复核令牌属于另一个步骤。"
                        }
                        if (hasMatchingCapture(current, frozen)) return@transaction current
                        val previous = if (replacing) current.steps.singleOrNull { it.id == stepId } ?: error("待替换步骤已不存在。") else null
                        check(!replacing || current.project.revision == expectedRevision) { "草稿已改变，底图未替换。" }
                        require(replacing || current.steps.none { it.id == stepId }) { "这个步骤已经保存，请刷新后继续。" }
                        require(replacing || current.steps.size < ProjectLimits.MAX_STEPS) { "每个项目最多 40 个步骤。" }
                        val existingSource = readSource(db, projectId, frozen.source.sourceId)
                        check(existingSource == null || existingSource == frozen.source) {
                            "素材来源记录已改变，请重新取帧。"
                        }
                        if (existingSource == null) {
                            db.insertOrThrow("sources", null, ContentValues().apply {
                                put("project_id", projectId); put("source_id", frozen.source.sourceId)
                                put("source_json", sourceJson(frozen.source).toString())
                            })
                        }
                        val directory = projectAssetDirectory(projectId)
                        val destination = File(directory, "$assetId.png")
                        check(!destination.exists()) { "步骤文件名称冲突，请重试。" }
                        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        syncDirectory(directory)
                        db.insertOrThrow("local_assets", null, ContentValues().apply {
                            put("asset_id", assetId); put("project_id", projectId)
                            put("relative_path", relativePath); put("sha256", frozen.sha256.lowercase())
                            put("byte_length", byteLength); put("width", frozen.width); put("height", frozen.height)
                        })
                        val stateValues = ContentValues().apply {
                            put("project_id", projectId); put("state_id", stepId); put("capture_id", frozen.captureId)
                            put("sort_order", previous?.sortOrder ?: current.steps.size); put("title", cleanTitle)
                            put("description", cleanDescription); put("is_terminal", if (previous?.isTerminal == true) 1 else 0)
                            put("source_id", frozen.source.sourceId); put("input_asset_id", assetId)
                            put("frame_pts_us", frozen.frameTimeUs); put("time_precision_us", frozen.timePrecisionUs)
                            put("masks_json", masksJson(frozen.masks).toString())
                        }
                        if (previous == null) db.insertOrThrow("states", null, stateValues)
                        else {
                            previous.regions.forEach { clearRegionAsset(db, projectId, it) }
                            db.update("states", stateValues, "project_id=? AND state_id=?", arrayOf(projectId, stepId))
                            queueAsset(db, projectId, previous.asset.privateRelativePath)
                            db.delete("local_assets", "project_id=? AND asset_id=?", arrayOf(projectId, previous.asset.id))
                            // A transition reviewed against an old endpoint must be re-established.
                            current.steps.forEach { from ->
                                from.hotspots.filter { from.id == stepId || it.targetStepId == stepId }.forEach {
                                    removeTransitionRow(db, projectId, it.edgeId)
                                }
                                from.nextAction?.takeIf { from.id == stepId || it.targetStepId == stepId }?.let {
                                    removeTransitionRow(db, projectId, it.id)
                                }
                            }
                            pruneSources(db, projectId)
                        }
                        if (current.project.startStepId == null) {
                            db.update("projects", ContentValues().apply { put("start_state_id", stepId) },
                                "project_id=?", arrayOf(projectId))
                        }
                        bump(db, projectId)
                        requireSnapshot(db, projectId)
                    }
                    // This flag is set only AFTER endTransaction successfully committed, and
                    // before returning across a cancellable dispatcher boundary.
                    committed = true
                    saved
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanup = synchronized(lock) {
                activeImports.remove(importKey(assetId))
                // Consult the durable reference, including when endTransaction's result was
                // uncertain. Cleanup errors retain the journal for the next store operation.
                runCatching { cleanupImport(helper.writableDatabase, projectId, assetId) }.exceptionOrNull()
            }
            // Never falsely report that the graph save failed after its commit point.
            if (cleanup != null && !committed) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    /**
     * Copies a human-reviewed complete clip, validates every saved frame, then atomically binds
     * it to one unchanged edge. Cancellation before commit or a failed/stale save never replaces the old clip.
     * If cancellation races the committed return, callers must reload the actual binding.
     * Source deletion must be serialized with this operation, just like addReviewedStep.
     */
    suspend fun bindReviewedTransition(
        projectId: String,
        edgeId: String,
        expectedRevision: Long,
        input: ReviewedTransitionInput,
    ): ProjectSnapshot {
        validId(projectId)
        validId(edgeId)
        val frozen = input.copy(masks = input.masks.toList())
        val alreadySaved = access { db ->
            val current = requireSnapshot(db, projectId)
            requireEdge(current, edgeId)
            if (hasMatchingTransition(current, edgeId, frozen)) current else {
                check(current.project.revision == expectedRevision) { "草稿已改变，请重新打开过渡编辑。" }
                null
            }
        }
        if (alreadySaved != null) return alreadySaved
        validateTransitionInput(frozen)
        val assetId = newId()
        val relativePath = assetPath(projectId, assetId, "mp4")
        access { db ->
            transaction(db) {
                db.insertOrThrow("transition_imports", null, ContentValues().apply {
                    put("project_id", projectId); put("asset_id", assetId)
                })
            }
            activeImports.add(importKey(assetId))
        }
        val stagingRoot = File(root, "project-staging")
        val operationDirectory = File(stagingRoot, assetId)
        val temporary = File(operationDirectory, "candidate.part")
        var committed = false
        var failure: Throwable? = null
        try {
            privateDirectory(stagingRoot, root)
            check(operationDirectory.mkdir()) { "无法建立过渡暂存目录。" }
            val owner = currentCoroutineContext()
            val digest = MessageDigest.getInstance("SHA-256")
            var byteLength = 0L
            frozen.file.inputStream().use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        owner.ensureActive()
                        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), MAX_PNG_BYTES - byteLength + 1).toInt())
                        if (count < 0) break
                        check(count > 0) { "无法继续读取已复核过渡。" }
                        byteLength += count
                        require(byteLength <= MAX_PNG_BYTES) { "过渡 MP4 超过 50 MiB。" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(byteLength > 0 && temporary.length() == byteLength && hex(digest.digest()) == frozen.sha256.lowercase()) {
                "过渡实际文件已改变，请重新生成并完整复核。"
            }
            val canvas = MediaInputPolicy.fitOutputSize(frozen.source.metadata.displayWidth, frozen.source.metadata.displayHeight)
            val outputMasks = SafeMediaWriterValidation.normalizeMasks(canvas.first, canvas.second,
                frozen.width, frozen.height, frozen.masks)
            val actual = SafeMediaWriterValidation.verifyStoredVideoOutput(app, temporary, outputMasks,
                frozen.width, frozen.height)
            check(actual.durationUs == frozen.durationUs && sha256(temporary) == frozen.sha256.lowercase()) {
                "过渡实际时长或内容改变，请重新完整复核。"
            }
            owner.ensureActive()
            return withContext(NonCancellable) {
                synchronized(lock) {
                    val db = helper.writableDatabase
                    val result = transaction(db) {
                        val current = requireSnapshot(db, projectId)
                        requireEdge(current, edgeId)
                        if (hasMatchingTransition(current, edgeId, frozen)) return@transaction current
                        check(current.project.revision == expectedRevision) { "草稿已改变，原过渡保持不变，请重新编辑。" }
                        val totalUs = transitions(current).filterKeys { it != edgeId }.values.sumOf { it.asset.durationUs } + actual.durationUs
                        require(totalUs <= ProjectLimits.MAX_TOTAL_TRANSITION_US) { "全部边绑定的视频累计不能超过 60 秒。" }
                        val source = readSource(db, projectId, frozen.source.sourceId)
                        check(source == null || source == frozen.source) { "来源记录已改变，请重新生成过渡。" }
                        if (source == null) db.insertOrThrow("sources", null, ContentValues().apply {
                            put("project_id", projectId); put("source_id", frozen.source.sourceId)
                            put("source_json", sourceJson(frozen.source).toString())
                        })
                        // Remove only inside this transaction. Rollback restores the old binding and
                        // its cleanup journal; the new file is still covered by transition_imports.
                        removeTransitionRow(db, projectId, edgeId)
                        val directory = projectAssetDirectory(projectId)
                        val destination = File(directory, "$assetId.mp4")
                        check(!destination.exists()) { "过渡文件名称冲突，请重试。" }
                        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        syncDirectory(directory)
                        db.insertOrThrow("local_assets", null, ContentValues().apply {
                            put("asset_id", assetId); put("project_id", projectId); put("relative_path", relativePath)
                            put("sha256", frozen.sha256.lowercase()); put("byte_length", byteLength)
                            put("width", actual.width); put("height", actual.height)
                        })
                        db.insertOrThrow("edge_transitions", null, ContentValues().apply {
                            put("project_id", projectId); put("edge_id", edgeId); put("asset_id", assetId)
                            put("source_id", frozen.source.sourceId); put("start_us", frozen.startUs); put("end_us", frozen.endUs)
                            put("duration_us", actual.durationUs); put("masks_json", masksJson(frozen.masks).toString())
                            put("review_id", frozen.reviewId)
                        })
                        pruneSources(db, projectId)
                        bump(db, projectId)
                        requireSnapshot(db, projectId)
                    }
                    committed = true
                    result
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanup = synchronized(lock) {
                activeImports.remove(importKey(assetId))
                runCatching {
                    cleanupImport(helper.writableDatabase, projectId, assetId, "mp4")
                    cleanupPending(helper.writableDatabase)
                }.exceptionOrNull()
            }
            if (cleanup != null && !committed) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    fun removeTransition(projectId: String, edgeId: String, expectedRevision: Long): ProjectSnapshot = access { db ->
        val result = transaction(db) {
            val current = requireSnapshot(db, projectId)
            requireEdge(current, edgeId)
            if (edgeId !in transitions(current)) return@transaction current
            check(current.project.revision == expectedRevision) { "草稿已改变，请重新打开过渡编辑。" }
            removeTransitionRow(db, projectId, edgeId)
            pruneSources(db, projectId)
            bump(db, projectId)
            requireSnapshot(db, projectId)
        }
        cleanupPending(db)
        result
    }

    /** Hash-checked persisted output only; raw-source fallback is forbidden. */
    fun resolveTransition(projectId: String, edgeId: String): File = access { db ->
        val transition = transitions(requireSnapshot(db, projectId))[edgeId] ?: error("这条边没有已保存过渡。")
        val asset = transition.asset
        checkedAssetFile(projectId, asset.privateRelativePath).also { file ->
            check(file.isFile && file.length() == asset.byteLength && sha256(file) == asset.sha256) {
                "已保存过渡缺失或内容改变，请返回编辑；不会改用原片。"
            }
        }
    }

    fun updateStep(projectId: String, stepId: String, title: String, description: String): ProjectSnapshot {
        val cleanTitle = text(title, "步骤标题", 120)
        val cleanDescription = text(description, "步骤说明", 4_000, allowEmpty = true)
        return edit(projectId) { db ->
            requireStep(db, projectId, stepId)
            db.update("states", ContentValues().apply {
                put("title", cleanTitle); put("description", cleanDescription)
            }, "project_id=? AND state_id=?", arrayOf(projectId, stepId))
        }
    }

    /**
     * Atomically save the whole editor form; a stale draft never overwrites a newer revision.
     * nextAction is the complete edited value: null explicitly removes the saved button.
     * A supplied editor session must still be current. Success clears its recovery row in this
     * transaction; callers serialize queued same-session writes with this save before resuming.
     */
    fun saveStepDraft(
        projectId: String,
        stepId: String,
        title: String,
        description: String,
        isTerminal: Boolean,
        hotspots: List<ProjectHotspot>,
        expectedRevision: Long? = null,
        nextAction: ProjectNextAction? = null,
        editorDraftSession: Long? = null,
    ): ProjectSnapshot {
        val cleanTitle = text(title, "步骤标题", 120)
        val cleanDescription = text(description, "步骤说明", 4_000, allowEmpty = true)
        val frozen = hotspots.map { hotspot ->
            validId(hotspot.id)
            validId(hotspot.edgeId)
            require((hotspot.targetStepId == null) != (hotspot.endLabel == null)) {
                "每个热点必须选择一个目标步骤或结束。"
            }
            hotspot.copy(label = text(hotspot.label, "热点标签", 120),
                endLabel = hotspot.endLabel?.let { text(it, "结束说明", 300) })
        }
        require(frozen.size <= ProjectLimits.MAX_HOTSPOTS_PER_STEP) { "每个步骤最多 6 个热点。" }
        require(frozen.map { it.id }.toSet().size == frozen.size && frozen.map { it.edgeId }.toSet().size == frozen.size) {
            "热点或连线标识重复，请重新编辑。"
        }
        val frozenNext = nextAction?.let {
            validId(it.id)
            it.targetStepId?.let(::validId)
            it.copy(label = text(it.label, "下一步动作标签", 120))
        }
        require(!isTerminal || (frozen.isEmpty() && frozenNext == null)) {
            "终点不能保留热点或下一步动作，请先处理这些动作。"
        }
        return edit(projectId) { db ->
            check(editorDraftSession == null || currentEditorDraftSession(db, projectId) == editorDraftSession) {
                "编辑会话已改变，当前修改尚未保存；请重新载入后编辑。"
            }
            val current = requireSnapshot(db, projectId)
            check(expectedRevision == null || current.project.revision == expectedRevision) {
                "项目已发生其他修改，当前草稿尚未保存；请重新载入后编辑。"
            }
            val step = current.steps.firstOrNull { it.id == stepId } ?: error("步骤已不存在，请刷新。")
            val otherSteps = current.steps.filter { it.id != stepId }
            val otherHotspots = otherSteps.flatMap { it.hotspots }
            val otherNextActions = otherSteps.mapNotNull { it.nextAction }
            require(otherHotspots.size + otherNextActions.size + frozen.size + (if (frozenNext == null) 0 else 1) <= ProjectLimits.MAX_EDGES) { "每个项目最多 80 条连线。" }
            val otherIds = otherHotspots.map { it.id }.toSet()
            val otherEdges = (otherHotspots.map { it.edgeId } + otherNextActions.map { it.id }).toSet()
            val oldById = step.hotspots.associateBy { it.id }
            val oldEdges = step.hotspots.associateBy { it.edgeId }
            frozenNext?.let { action ->
                require(step.nextAction == null || step.nextAction.id == action.id) { "已保存下一步动作的标识不能改变。" }
                require(action.id !in otherEdges && frozen.none { it.edgeId == action.id }) { "下一步动作标识与已有连线冲突。" }
                action.targetStepId?.let { requireStep(db, projectId, it) }
            }
            frozen.forEach { hotspot ->
                require(hotspot.id !in otherIds && hotspot.edgeId !in otherEdges) { "热点或连线属于其他步骤。" }
                require(oldById[hotspot.id]?.edgeId?.let { it == hotspot.edgeId } != false &&
                    oldEdges[hotspot.edgeId]?.id?.let { it == hotspot.id } != false) { "已保存热点的连线标识不能改变。" }
                hotspot.targetStepId?.let { requireStep(db, projectId, it) }
            }
            // Delete and reinsert only this step's paired rows in the same transaction. Their
            // stable IDs remain unchanged; failure restores every original row and the text.
            db.delete("hotspots", "project_id=? AND state_id=?", arrayOf(projectId, stepId))
            frozen.forEach { hotspot ->
                db.insertOrThrow("hotspots", null, ContentValues().apply {
                    put("project_id", projectId); put("hotspot_id", hotspot.id); put("state_id", stepId)
                    put("label", hotspot.label); put("rect_left", hotspot.rect.left); put("rect_top", hotspot.rect.top)
                    put("rect_right", hotspot.rect.right); put("rect_bottom", hotspot.rect.bottom)
                })
                db.insertOrThrow("edges", null, ContentValues().apply {
                    put("project_id", projectId); put("edge_id", hotspot.edgeId)
                    put("hotspot_id", hotspot.id); put("from_state_id", stepId)
                    if (hotspot.targetStepId == null) putNull("to_state_id") else put("to_state_id", hotspot.targetStepId)
                    if (hotspot.endLabel == null) putNull("end_label") else put("end_label", hotspot.endLabel)
                })
            }
            db.delete("next_actions", "project_id=? AND from_state_id=?", arrayOf(projectId, stepId))
            frozenNext?.let { insertNextAction(db, projectId, stepId, it) }
            db.update("states", ContentValues().apply {
                put("title", cleanTitle); put("description", cleanDescription); put("is_terminal", if (isTerminal) 1 else 0)
            }, "project_id=? AND state_id=?", arrayOf(projectId, stepId))
            // The surrounding edit transaction also bumps revision/reconciles media. Failure in
            // any later step rolls this clear back alongside the graph and its original draft.
            db.delete("editor_drafts", "project_id=? AND state_id=?", arrayOf(projectId, stepId))
        }
    }

    /**
     * Explicit author confirmation creates/rebuilds only these adjacent next actions, in one
     * revision. List order, start, manual hotspots, unselected steps and the last step's action
     * stay unchanged. A terminal in the middle or an occupied requested end is never erased.
     */
    fun connectStepsInOrder(
        projectId: String,
        stepIds: List<String>,
        markLastTerminal: Boolean,
        expectedRevision: Long,
    ): ProjectSnapshot {
        val order = stepIds.toList()
        require(order.size in 2..ProjectLimits.MAX_STEPS && order.toSet().size == order.size) {
            "生成通路需要 2–40 个不重复的步骤。"
        }
        order.forEach(::validId)
        return edit(projectId) { db ->
            val current = requireSnapshot(db, projectId)
            check(current.project.revision == expectedRevision) {
                "项目已发生其他修改，通路尚未生成；请重新载入后确认。"
            }
            val byId = current.steps.associateBy { it.id }
            val selected = order.map { id -> byId[id] ?: error("通路中的步骤不属于当前项目或已删除，请重新选择。") }
            selected.dropLast(1).forEach { step ->
                require(!step.isTerminal) { "“${step.title}”已设为终点，请先取消该终点设置再生成通路。" }
            }
            val last = selected.last()
            if (markLastTerminal) {
                require(last.hotspots.isEmpty() && last.nextAction == null) {
                    "“${last.title}”仍有热点或下一步动作，请先处理后再设为终点。"
                }
            }
            val added = selected.dropLast(1).count { it.nextAction == null }
            require(edgeCount(current) + added <= ProjectLimits.MAX_EDGES) { "每个项目最多 80 条连线（包括热点和下一步动作）。" }
            selected.zipWithNext().forEach { (from, to) ->
                val action = from.nextAction?.copy(targetStepId = to.id)
                    ?: ProjectNextAction(newId(), "下一步", to.id)
                if (from.nextAction == null) insertNextAction(db, projectId, from.id, action)
                else db.update("next_actions", ContentValues().apply { put("to_state_id", to.id) },
                    "project_id=? AND from_state_id=?", arrayOf(projectId, from.id))
            }
            if (markLastTerminal) {
                db.update("states", ContentValues().apply { put("is_terminal", 1) },
                    "project_id=? AND state_id=?", arrayOf(projectId, last.id))
            }
        }
    }

    /** Reordering changes only list position; IDs, start, hotspots and targets stay untouched. */
    fun reorderSteps(projectId: String, orderedStepIds: List<String>): ProjectSnapshot {
        val order = orderedStepIds.toList()
        return edit(projectId) { db ->
            val current = requireSnapshot(db, projectId).steps.map { it.id }
            require(order.size == current.size && order.toSet() == current.toSet()) { "步骤顺序与已保存内容不一致，请刷新。" }
            order.forEachIndexed { index, id ->
                db.update("states", ContentValues().apply { put("sort_order", index) },
                    "project_id=? AND state_id=?", arrayOf(projectId, id))
            }
        }
    }

    fun setStartStep(projectId: String, stepId: String): ProjectSnapshot = edit(projectId) { db ->
        requireStep(db, projectId, stepId)
        db.update("projects", ContentValues().apply { put("start_state_id", stepId) },
            "project_id=?", arrayOf(projectId))
    }

    fun setTerminal(projectId: String, stepId: String, isTerminal: Boolean): ProjectSnapshot = edit(projectId) { db ->
        requireStep(db, projectId, stepId)
        require(!isTerminal || (count(db, "hotspots", "project_id=? AND state_id=?", projectId, stepId) == 0 &&
            count(db, "next_actions", "project_id=? AND from_state_id=?", projectId, stepId) == 0)) {
            "此步骤仍有热点或下一步动作，请先处理这些动作再设为终点。"
        }
        db.update("states", ContentValues().apply { put("is_terminal", if (isTerminal) 1 else 0) },
            "project_id=? AND state_id=?", arrayOf(projectId, stepId))
    }

    fun stepDeletionImpact(projectId: String, stepId: String): StepDeletionImpact = access { db ->
        deletionImpact(requireSnapshot(db, projectId), stepId)
    }

    fun deleteStep(projectId: String, stepId: String): StepDeletionResult = access { db ->
        val result = transaction(db) {
            val current = requireSnapshot(db, projectId)
            val step = current.steps.firstOrNull { it.id == stepId } ?: error("步骤已不存在，请刷新。")
            val impact = deletionImpact(current, stepId)
            val affected = current.steps.flatMap { from -> from.hotspots.filter {
                from.id == stepId || it.targetStepId == stepId
            } }
            affected.forEach { hotspot ->
                db.delete("hotspots", "project_id=? AND hotspot_id=?", arrayOf(projectId, hotspot.id))
            }
            val remaining = current.steps.filter { it.id != stepId }
            if (impact.wasStart) {
                db.update("projects", ContentValues().apply {
                    val next = remaining.firstOrNull()?.id
                    if (next == null) putNull("start_state_id") else put("start_state_id", next)
                }, "project_id=?", arrayOf(projectId))
            }
            // Keep incoming authored buttons, but mark their missing destination for repair.
            // ON DELETE SET NULL cannot be used on the composite FK: it would null project_id.
            db.update("next_actions", ContentValues().apply { putNull("to_state_id") },
                "project_id=? AND to_state_id=?", arrayOf(projectId, stepId))
            step.regions.forEach { clearRegionAsset(db, projectId, it) }
            db.delete("states", "project_id=? AND state_id=?", arrayOf(projectId, stepId))
            queueAsset(db, projectId, step.asset.privateRelativePath)
            db.delete("local_assets", "project_id=? AND asset_id=?", arrayOf(projectId, step.asset.id))
            clearChangedTransitions(db, current, requireSnapshot(db, projectId))
            remaining.forEachIndexed { index, item ->
                db.update("states", ContentValues().apply { put("sort_order", index) },
                    "project_id=? AND state_id=?", arrayOf(projectId, item.id))
            }
            bump(db, projectId)
            StepDeletionResult(requireSnapshot(db, projectId), impact, 1)
        }
        cleanupPending(db)
        result.copy(pendingAssetCleanupCount = pendingCleanupCount(db, projectId, 1))
    }

    fun saveHotspot(
        projectId: String,
        stepId: String,
        hotspotId: String? = null,
        label: String,
        rect: OpaqueMask,
        targetStepId: String? = null,
        endLabel: String? = null,
    ): ProjectSnapshot {
        val cleanLabel = text(label, "热点标签", 120)
        val cleanEndLabel = endLabel?.let { text(it, "结束说明", 300) }
        require((targetStepId == null) != (cleanEndLabel == null)) { "热点必须选择一个目标步骤或结束。" }
        return edit(projectId) { db ->
            val current = requireSnapshot(db, projectId)
            val step = current.steps.firstOrNull { it.id == stepId } ?: error("步骤已不存在，请刷新。")
            require(!step.isTerminal) { "终点不能添加热点，请先取消终点设置。" }
            if (targetStepId != null) requireStep(db, projectId, targetStepId)
            val previous = hotspotId?.let { id ->
                step.hotspots.firstOrNull { it.id == id } ?: error("热点已不存在，请刷新。")
            }
            if (previous == null) {
                require(step.hotspots.size < ProjectLimits.MAX_HOTSPOTS_PER_STEP) { "每个步骤最多 6 个热点。" }
                require(edgeCount(current) < ProjectLimits.MAX_EDGES) { "每个项目最多 80 条连线。" }
            }
            val id = previous?.id ?: newId()
            val hotspotValues = ContentValues().apply {
                put("project_id", projectId); put("hotspot_id", id); put("state_id", stepId)
                put("label", cleanLabel); put("rect_left", rect.left); put("rect_top", rect.top)
                put("rect_right", rect.right); put("rect_bottom", rect.bottom)
            }
            if (previous == null) db.insertOrThrow("hotspots", null, hotspotValues)
            else db.update("hotspots", hotspotValues, "project_id=? AND hotspot_id=?", arrayOf(projectId, id))
            val edgeValues = ContentValues().apply {
                put("project_id", projectId); put("edge_id", previous?.edgeId ?: newId())
                put("hotspot_id", id); put("from_state_id", stepId)
                if (targetStepId == null) putNull("to_state_id") else put("to_state_id", targetStepId)
                if (cleanEndLabel == null) putNull("end_label") else put("end_label", cleanEndLabel)
            }
            if (previous == null) db.insertOrThrow("edges", null, edgeValues)
            else db.update("edges", edgeValues, "project_id=? AND hotspot_id=?", arrayOf(projectId, id))
        }
    }

    fun deleteHotspot(projectId: String, stepId: String, hotspotId: String): ProjectSnapshot = edit(projectId) { db ->
        requireStep(db, projectId, stepId)
        check(db.delete("hotspots", "project_id=? AND state_id=? AND hotspot_id=?",
            arrayOf(projectId, stepId, hotspotId)) == 1) { "热点已不存在，请刷新。" }
    }

    /** Always resolves from a current persisted step, never an arbitrary caller-provided path. */
    fun resolveAsset(projectId: String, stepId: String): File = access { db ->
        val step = requireSnapshot(db, projectId).steps.firstOrNull { it.id == stepId }
            ?: error("步骤已不存在，请返回编辑。")
        val file = checkedAssetFile(projectId, step.asset.privateRelativePath)
        check(file.isFile && file.length() == step.asset.byteLength && sha256(file) == step.asset.sha256) {
            "已保存步骤画面缺失或改变，请返回编辑；不会改用原片。"
        }
        val signature = ByteArray(8)
        file.inputStream().use { input ->
            check(input.read(signature) == signature.size && signature.contentEquals(PNG_SIGNATURE)) {
                "已保存步骤不是 PNG，请返回编辑。"
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outMimeType == "image/png" && bounds.outWidth == step.asset.width && bounds.outHeight == step.asset.height) {
            "已保存步骤画面尺寸改变，请返回编辑。"
        }
        file
    }

    /**
     * Copy only persisted, reviewed PNG and MP4 inputs while holding the SAME process lock as edits and
     * deletion. The returned revision and every copied byte therefore describe one snapshot.
     * Destination is an existing, empty app-private assets directory owned by ReleaseStore.
     * Caller owns partial output on failure; this method never deletes or moves draft/source data.
     */
    fun copyReleaseInputs(projectId: String, expectedRevision: Long, destination: File): ProjectSnapshot = access { db ->
        val current = requireSnapshot(db, projectId)
        check(current.project.revision == expectedRevision) { "草稿修订已改变，请重新检查后生成。" }
        val target = destination.absoluteFile
        check(target.canonicalFile == target && target.isDirectory &&
            target.path.startsWith(root.path + File.separator) &&
            target.listFiles()?.isEmpty() == true) { "成品复制目标必须是空的本机私有目录。" }
        var total = 0L
        val regionAssets = current.steps.flatMap { step -> step.regions.map { region ->
            check(region.matchesBase(step.asset) && region.reviewedAt != null) { "区域底图已改变或裁片尚未复核，请重新生成并确认。" }
            requireNotNull(region.asset) { "区域裁片失效，请重新生成。" }
        } }
        val allAssets = current.steps.map { it.asset } + regionAssets + transitions(current).values.map { it.asset }.map {
            StepAsset(it.id, it.privateRelativePath, it.sha256, it.byteLength, it.width, it.height)
        }
        check(allAssets.map { it.id }.toSet().size == allAssets.size) { "成品资产标识重复。" }
        check(transitions(current).values.sumOf { it.asset.durationUs } <= ProjectLimits.MAX_TOTAL_TRANSITION_US) {
            "全部边绑定视频累计超过 60 秒。"
        }
        allAssets.forEach { asset ->
            validId(asset.id)
            val source = checkedAssetFile(projectId, asset.privateRelativePath)
            check(source.isFile && source.length() == asset.byteLength && sha256(source) == asset.sha256) {
                "步骤画面缺失或改变，请返回编辑。"
            }
            total += asset.byteLength
            check(total <= MAX_PNG_BYTES) { "成品图片和视频合计超过 50 MiB。" }
            val extension = asset.privateRelativePath.substringAfterLast('.')
            val output = File(target, "${asset.id}.$extension")
            check(!output.exists() && output.canonicalFile == output.absoluteFile) { "成品图片标识重复。" }
            source.inputStream().use { input ->
                FileOutputStream(output).use { sink ->
                    var copied = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(count > 0) { "步骤画面无法继续读取。" }
                        copied += count
                        check(copied <= asset.byteLength) { "步骤画面在复制期间改变。" }
                        sink.write(buffer, 0, count)
                    }
                    check(copied == asset.byteLength) { "步骤画面复制不完整。" }
                    sink.fd.sync()
                }
            }
            check(output.length() == asset.byteLength && sha256(output) == asset.sha256) {
                "成品实际图片摘要不匹配。"
            }
        }
        syncDirectory(target)
        current
    }

    /** Saves only a visible-region definition; output and human review must be regenerated. */
    fun saveRegion(projectId: String, stepId: String, regionId: String?, name: String, group: String?,
        bbox: RegionBox, zIndex: Int, anchorX: Double, anchorY: Double, expectedRevision: Long): ProjectSnapshot {
        val cleanName = text(name, "区域名称", 120)
        val cleanGroup = group?.trim()?.takeIf { it.isNotEmpty() }?.let { text(it, "区域分组", 120) }
        require(zIndex in -10000..10000 && anchorX.isFinite() && anchorY.isFinite() &&
            anchorX in 0.0..1.0 && anchorY in 0.0..1.0) { "层级或裁片局部锚点无效。" }
        regionId?.let(::validId)
        return edit(projectId) { db ->
            val current = requireSnapshot(db, projectId)
            check(current.project.revision == expectedRevision) { "草稿已改变，请重新打开区域编辑。" }
            val step = current.steps.singleOrNull { it.id == stepId } ?: error("步骤已不存在。")
            resolveAsset(projectId, stepId) // Actual hash and dimensions; never use the source file.
            require(bbox.fits(step.asset.width, step.asset.height)) { "区域超出安全底图。" }
            val old = regionId?.let { id -> step.regions.singleOrNull { it.id == id } ?: error("区域不属于当前步骤。") }
            if (old == null) require(step.regions.size < ProjectLimits.MAX_REGIONS_PER_STEP &&
                current.steps.sumOf { it.regions.size } < ProjectLimits.MAX_REGIONS) { "每步最多 12 个区域，每项目最多 80 个。" }
            old?.let { clearRegionAsset(db, projectId, it) }
            val values = ContentValues().apply {
                put("project_id", projectId); put("region_id", old?.id ?: newId()); put("state_id", stepId)
                put("base_asset_id", step.asset.id); put("base_sha256", step.asset.sha256)
                put("name", cleanName); if (cleanGroup == null) putNull("group_name") else put("group_name", cleanGroup)
                put("x_px", bbox.x); put("y_px", bbox.y); put("width_px", bbox.width); put("height_px", bbox.height)
                put("source_width", step.asset.width); put("source_height", step.asset.height)
                put("z_index", zIndex); put("anchor_x", anchorX); put("anchor_y", anchorY)
                putNull("asset_id"); putNull("reviewed_at")
            }
            if (old == null) db.insertOrThrow("regions", null, values)
            else db.update("regions", values, "project_id=? AND region_id=?", arrayOf(projectId, old.id))
        }
    }

    /** Real PNG crop from persisted reviewed image; generation does not imply human review. */
    suspend fun generateRegion(projectId: String, regionId: String, expectedRevision: Long): ProjectSnapshot {
        val (step, region) = access { db ->
            val current = requireSnapshot(db, projectId)
            check(current.project.revision == expectedRevision) { "草稿已改变，请重新打开区域编辑。" }
            val step = current.steps.singleOrNull { it.regions.any { r -> r.id == regionId } } ?: error("区域已不存在。")
            step to step.regions.single { it.id == regionId }
        }
        require(region.bbox.fits(step.asset.width, step.asset.height)) { "底图尺寸改变，请先调整区域边界。" }
        val assetId = newId()
        access { db ->
            db.insertOrThrow("asset_imports", null, ContentValues().apply { put("project_id", projectId); put("asset_id", assetId) })
            activeImports.add(importKey(assetId))
        }
        var committed = false
        var failure: Throwable? = null
        try {
            val staging = privateDirectory(File(root, "project-staging"), root)
            val operation = File(staging, assetId)
            check(operation.mkdir()) { "无法建立裁片暂存目录。" }
            val output = File(operation, "candidate.part")
            val baseFile = resolveAsset(projectId, step.id)
            check(sha256(baseFile) == step.asset.sha256) { "底图已改变，请刷新。" }
            SafeMediaWriterValidation.verifyPng(baseFile, step.asset.width, step.asset.height, emptyList())
            currentCoroutineContext().ensureActive()
            val base = BitmapFactory.decodeFile(baseFile.path, BitmapFactory.Options().apply {
                inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("无法解码已复核安全底图。")
            try {
                check(base.width == step.asset.width && base.height == step.asset.height) { "底图实际尺寸改变。" }
                val crop = Bitmap.createBitmap(base, region.bbox.x, region.bbox.y, region.bbox.width, region.bbox.height)
                try {
                    FileOutputStream(output).use { sink ->
                        check(crop.compress(Bitmap.CompressFormat.PNG, 100, sink)) { "区域 PNG 生成失败。" }
                        sink.fd.sync()
                    }
                } finally { if (crop !== base) crop.recycle() }
            } finally { base.recycle() }
            check(sha256(baseFile) == step.asset.sha256) { "底图在裁片生成期间改变。" }
            SafeMediaWriterValidation.verifyPng(output, region.bbox.width, region.bbox.height, emptyList())
            val digest = sha256(output)
            val length = output.length()
            require(length in 1..MAX_PNG_BYTES) { "裁片为空或超过限制。" }
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) { synchronized(lock) {
                val db = helper.writableDatabase
                val result = transaction(db) {
                    val current = requireSnapshot(db, projectId)
                    check(current.project.revision == expectedRevision) { "草稿已改变，裁片未替换，请重新生成。" }
                    val now = current.steps.singleOrNull { it.id == step.id } ?: error("步骤已不存在。")
                    check(now.asset == step.asset && now.regions.singleOrNull { it.id == region.id } == region) { "区域或底图已改变。" }
                    check(sha256(resolveAsset(projectId, step.id)) == step.asset.sha256) { "底图已改变。" }
                    val directory = projectAssetDirectory(projectId)
                    val destination = File(directory, "$assetId.png")
                    check(!destination.exists()) { "裁片资产标识冲突。" }
                    Files.move(output.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    syncDirectory(directory)
                    db.insertOrThrow("local_assets", null, ContentValues().apply {
                        put("asset_id", assetId); put("project_id", projectId); put("relative_path", assetPath(projectId, assetId))
                        put("sha256", digest); put("byte_length", length); put("width", region.bbox.width); put("height", region.bbox.height)
                    })
                    clearRegionAsset(db, projectId, region)
                    db.update("regions", ContentValues().apply {
                        put("asset_id", assetId); putNull("reviewed_at")
                        put("base_asset_id", step.asset.id); put("base_sha256", step.asset.sha256)
                        put("source_width", step.asset.width); put("source_height", step.asset.height)
                    }, "project_id=? AND region_id=?", arrayOf(projectId, regionId))
                    bump(db, projectId)
                    requireSnapshot(db, projectId)
                }
                committed = true
                result
            } }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = synchronized(lock) {
                activeImports.remove(importKey(assetId))
                runCatching { cleanupImport(helper.writableDatabase, projectId, assetId); cleanupPending(helper.writableDatabase) }.exceptionOrNull()
            }
            if (cleanup != null && !committed) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    /** Call only after the author has viewed this exact crop PNG and explicitly confirmed it. */
    fun reviewRegion(projectId: String, regionId: String, expectedRevision: Long, expectedSha256: String): ProjectSnapshot = edit(projectId) { db ->
        val current = requireSnapshot(db, projectId)
        check(current.project.revision == expectedRevision) { "草稿已改变，请重新查看实际裁片。" }
        val region = current.steps.flatMap { it.regions }.singleOrNull { it.id == regionId } ?: error("区域已不存在。")
        val asset = requireNotNull(region.asset) { "裁片失效，请重新生成。" }
        check(asset.sha256 == expectedSha256 && sha256(regionFile(projectId, regionId)) == expectedSha256) { "裁片已改变，请重新复核。" }
        db.update("regions", ContentValues().apply { put("reviewed_at", System.currentTimeMillis()) },
            "project_id=? AND region_id=?", arrayOf(projectId, regionId))
    }

    fun deleteRegion(projectId: String, regionId: String, expectedRevision: Long): ProjectSnapshot = edit(projectId) { db ->
        val current = requireSnapshot(db, projectId)
        check(current.project.revision == expectedRevision) { "草稿已改变，请刷新。" }
        val region = current.steps.flatMap { it.regions }.singleOrNull { it.id == regionId } ?: error("区域已不存在。")
        clearRegionAsset(db, projectId, region)
        db.delete("regions", "project_id=? AND region_id=?", arrayOf(projectId, regionId))
    }

    fun regionFile(projectId: String, regionId: String): File = access { db ->
        val step = requireSnapshot(db, projectId).steps.singleOrNull { it.regions.any { r -> r.id == regionId } } ?: error("区域已不存在。")
        val region = step.regions.single { it.id == regionId }
        check(region.matchesBase(step.asset)) { "安全底图已替换，请重新生成裁片。" }
        resolveAsset(projectId, step.id)
        val asset = requireNotNull(region.asset) { "裁片尚未生成或已失效。" }
        checkedAssetFile(projectId, asset.privateRelativePath).also { file ->
            check(file.isFile && file.length() == asset.byteLength && sha256(file) == asset.sha256) { "裁片实际内容缺失或改变。" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            check(bounds.outMimeType == "image/png" && bounds.outWidth == region.bbox.width && bounds.outHeight == region.bbox.height &&
                asset.width == region.bbox.width && asset.height == region.bbox.height) { "裁片实际尺寸改变。" }
        }
    }

    private fun clearRegionAsset(db: SQLiteDatabase, projectId: String, region: ProjectRegion) {
        db.update("regions", ContentValues().apply { putNull("asset_id"); putNull("reviewed_at") },
            "project_id=? AND region_id=?", arrayOf(projectId, region.id))
        region.asset?.let { asset ->
            queueAsset(db, projectId, asset.privateRelativePath)
            db.delete("local_assets", "project_id=? AND asset_id=?", arrayOf(projectId, asset.id))
        }
    }

    private fun readRegions(db: SQLiteDatabase, projectId: String, stepId: String): List<ProjectRegion> =
        db.rawQuery("""SELECT r.*, a.relative_path,a.sha256,a.byte_length,a.width,a.height FROM regions r
            LEFT JOIN local_assets a ON a.project_id=r.project_id AND a.asset_id=r.asset_id
            WHERE r.project_id=? AND r.state_id=? ORDER BY r.z_index,r.region_id""", arrayOf(projectId, stepId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ProjectRegion(
                cursor.string("region_id"), stepId, cursor.string("base_asset_id"), cursor.string("base_sha256"),
                cursor.string("name"), cursor.nullableString("group_name"),
                RegionBox(cursor.int("x_px"), cursor.int("y_px"), cursor.int("width_px"), cursor.int("height_px")),
                cursor.int("source_width"), cursor.int("source_height"), cursor.int("z_index"),
                cursor.getDouble(cursor.getColumnIndexOrThrow("anchor_x")), cursor.getDouble(cursor.getColumnIndexOrThrow("anchor_y")),
                cursor.nullableString("asset_id")?.let { StepAsset(it, cursor.string("relative_path"), cursor.string("sha256"),
                    cursor.long("byte_length"), cursor.int("width"), cursor.int("height")) },
                cursor.getColumnIndexOrThrow("reviewed_at").let { if (cursor.isNull(it)) null else cursor.getLong(it) },
            )) }
        }

    fun isSourceReferenced(sourceId: String): Boolean = access { db ->
        count(db, "states", "source_id=?", sourceId) > 0 ||
            count(db, "edge_transitions", "source_id=?", sourceId) > 0
    }

    private fun <T> access(block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        val db = helper.writableDatabase
        recoverImports(db)
        cleanupPending(db)
        block(db)
    }

    private fun edit(projectId: String, change: (SQLiteDatabase) -> Unit): ProjectSnapshot = access { db ->
        transaction(db) {
            val before = requireSnapshot(db, projectId)
            change(db)
            clearChangedTransitions(db, before, requireSnapshot(db, projectId))
            require(count(db, "edges", "project_id=?", projectId) +
                count(db, "next_actions", "project_id=?", projectId) <= ProjectLimits.MAX_EDGES) {
                "每个项目最多 80 条连线（包括热点和下一步动作）。"
            }
            bump(db, projectId)
            requireSnapshot(db, projectId)
        }
    }

    private fun bump(db: SQLiteDatabase, projectId: String) {
        db.execSQL("UPDATE projects SET draft_revision=draft_revision+1, updated_at=? WHERE project_id=?",
            arrayOf(System.currentTimeMillis(), projectId))
    }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun snapshot(db: SQLiteDatabase, projectId: String): ProjectSnapshot? {
        validId(projectId)
        val project = db.rawQuery("$SUMMARY_SQL WHERE p.project_id=?", arrayOf(projectId)).use {
            if (it.moveToFirst()) summary(it) else null
        } ?: return null
        val steps = db.rawQuery("""
            SELECT s.*, a.relative_path, a.sha256, a.byte_length, a.width, a.height, src.source_json
            FROM states s JOIN local_assets a ON a.project_id=s.project_id AND a.asset_id=s.input_asset_id
            JOIN sources src ON src.project_id=s.project_id AND src.source_id=s.source_id
            WHERE s.project_id=? ORDER BY s.sort_order, s.state_id
        """.trimIndent(), arrayOf(projectId)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.string("state_id")
                    add(ProjectStep(
                        id = id, title = cursor.string("title"), description = cursor.string("description"),
                        sortOrder = cursor.int("sort_order"), isTerminal = cursor.int("is_terminal") == 1,
                        asset = StepAsset(cursor.string("input_asset_id"), cursor.string("relative_path"),
                            cursor.string("sha256"), cursor.long("byte_length"), cursor.int("width"), cursor.int("height")),
                        source = parseSource(JSONObject(cursor.string("source_json"))),
                        frameTimeUs = cursor.long("frame_pts_us"), timePrecisionUs = cursor.long("time_precision_us"),
                        masks = parseMasks(JSONArray(cursor.string("masks_json"))), hotspots = readHotspots(db, projectId, id),
                        captureId = cursor.string("capture_id"), nextAction = readNextAction(db, projectId, id),
                        regions = readRegions(db, projectId, id),
                    ))
                }
            }
        }
        check(steps.size == project.stepCount) { "项目步骤记录不完整，请保留本机数据。" }
        return ProjectSnapshot(project, steps)
    }

    private fun requireSnapshot(db: SQLiteDatabase, id: String): ProjectSnapshot =
        snapshot(db, id) ?: error("项目已不存在，请返回项目列表。")

    private fun requireStep(db: SQLiteDatabase, projectId: String, stepId: String) {
        validId(stepId)
        require(count(db, "states", "project_id=? AND state_id=?", projectId, stepId) == 1) {
            "目标步骤不属于当前项目或已删除。"
        }
    }

    private fun summary(cursor: Cursor) = ProjectSummary(
        cursor.string("project_id"), cursor.string("title"), cursor.string("goal"),
        cursor.long("draft_revision"), cursor.long("updated_at"), cursor.int("step_count"),
        cursor.nullableString("start_state_id"),
    )

    private fun readHotspots(db: SQLiteDatabase, projectId: String, stepId: String): List<ProjectHotspot> =
        db.rawQuery("""
            SELECT h.*, e.edge_id, e.to_state_id, e.end_label FROM hotspots h
            JOIN edges e ON e.project_id=h.project_id AND e.hotspot_id=h.hotspot_id
            WHERE h.project_id=? AND h.state_id=? ORDER BY h.rowid
        """.trimIndent(), arrayOf(projectId, stepId)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ProjectHotspot(
                    cursor.string("hotspot_id"), cursor.string("label"),
                    OpaqueMask(cursor.float("rect_left"), cursor.float("rect_top"),
                        cursor.float("rect_right"), cursor.float("rect_bottom")),
                    cursor.nullableString("to_state_id"), cursor.nullableString("end_label"), cursor.string("edge_id"),
                    readTransition(db, projectId, cursor.string("edge_id")),
                ))
            }
        }

    private fun readNextAction(db: SQLiteDatabase, projectId: String, stepId: String): ProjectNextAction? =
        db.rawQuery("SELECT action_id,label,to_state_id FROM next_actions WHERE project_id=? AND from_state_id=?",
            arrayOf(projectId, stepId)).use { cursor ->
            if (cursor.moveToFirst()) ProjectNextAction(cursor.getString(0), cursor.getString(1), cursor.nullableString("to_state_id"),
                readTransition(db, projectId, cursor.getString(0)))
            else null
        }

    private fun insertNextAction(db: SQLiteDatabase, projectId: String, stepId: String, action: ProjectNextAction) {
        db.insertOrThrow("next_actions", null, ContentValues().apply {
            put("project_id", projectId); put("action_id", action.id); put("from_state_id", stepId); put("label", action.label)
            if (action.targetStepId == null) putNull("to_state_id") else put("to_state_id", action.targetStepId)
        })
    }

    private fun transitions(snapshot: ProjectSnapshot): Map<String, ProjectTransition> = buildMap {
        snapshot.steps.forEach { step ->
            step.hotspots.forEach { hotspot -> hotspot.transition?.let { put(hotspot.edgeId, it) } }
            step.nextAction?.let { next -> next.transition?.let { put(next.id, it) } }
        }
    }

    private fun requireEdge(snapshot: ProjectSnapshot, edgeId: String) {
        val hotspot = snapshot.steps.flatMap { it.hotspots }.firstOrNull { it.edgeId == edgeId }
        val next = snapshot.steps.mapNotNull { it.nextAction }.firstOrNull { it.id == edgeId }
        require(hotspot != null || next != null) { "这条边不属于当前项目或已删除。" }
        require(next == null || next.targetStepId != null) { "请先修复下一步目标，再添加过渡。" }
    }

    /** Compare source/destination/end semantics. Labels, hotspot geometry and list order retain the clip. */
    private fun edgeSignatures(snapshot: ProjectSnapshot): Map<String, List<Any?>> = buildMap {
        snapshot.steps.forEach { step ->
            step.hotspots.forEach { h -> put(h.edgeId, listOf("tap", step.id, h.targetStepId, h.endLabel)) }
            step.nextAction?.let { n -> put(n.id, listOf("continue", step.id, n.targetStepId)) }
        }
    }

    private fun clearChangedTransitions(db: SQLiteDatabase, before: ProjectSnapshot, after: ProjectSnapshot) {
        val old = edgeSignatures(before)
        val next = edgeSignatures(after)
        transitions(before).keys.filter { old[it] != next[it] }.forEach {
            removeTransitionRow(db, before.project.id, it)
        }
        pruneSources(db, before.project.id)
    }

    private fun readTransition(db: SQLiteDatabase, projectId: String, edgeId: String): ProjectTransition? =
        db.rawQuery("""
            SELECT t.*, a.relative_path, a.sha256, a.byte_length, a.width, a.height, s.source_json
            FROM edge_transitions t
            JOIN local_assets a ON a.project_id=t.project_id AND a.asset_id=t.asset_id
            JOIN sources s ON s.project_id=t.project_id AND s.source_id=t.source_id
            WHERE t.project_id=? AND t.edge_id=?
        """.trimIndent(), arrayOf(projectId, edgeId)).use { cursor ->
            if (!cursor.moveToFirst()) null else ProjectTransition(
                TransitionAsset(cursor.string("asset_id"), cursor.string("relative_path"), cursor.string("sha256"),
                    cursor.long("byte_length"), cursor.int("width"), cursor.int("height"), cursor.long("duration_us")),
                parseSource(JSONObject(cursor.string("source_json"))), cursor.long("start_us"), cursor.long("end_us"),
                parseMasks(JSONArray(cursor.string("masks_json"))), cursor.string("review_id"))
        }

    private fun removeTransitionRow(db: SQLiteDatabase, projectId: String, edgeId: String) {
        val transition = readTransition(db, projectId, edgeId) ?: return
        queueAsset(db, projectId, transition.asset.privateRelativePath)
        db.delete("edge_transitions", "project_id=? AND edge_id=?", arrayOf(projectId, edgeId))
        db.delete("local_assets", "project_id=? AND asset_id=?", arrayOf(projectId, transition.asset.id))
    }

    private fun pruneSources(db: SQLiteDatabase, projectId: String) {
        db.execSQL("""DELETE FROM sources WHERE project_id=?
            AND NOT EXISTS(SELECT 1 FROM states s WHERE s.project_id=sources.project_id AND s.source_id=sources.source_id)
            AND NOT EXISTS(SELECT 1 FROM edge_transitions t WHERE t.project_id=sources.project_id AND t.source_id=sources.source_id)
        """.trimIndent(), arrayOf(projectId))
    }

    private fun hasMatchingTransition(snapshot: ProjectSnapshot, edgeId: String, input: ReviewedTransitionInput): Boolean {
        val existing = transitions(snapshot).entries.firstOrNull { it.value.reviewId == input.reviewId } ?: return false
        val value = existing.value
        check(existing.key == edgeId && value.asset.sha256 == input.sha256.lowercase() &&
            value.asset.width == input.width && value.asset.height == input.height && value.asset.durationUs == input.durationUs &&
            value.source == input.source && value.startUs == input.startUs && value.endUs == input.endUs && value.masks == input.masks) {
            "此复核令牌对应的过渡或边已改变，请重新完整复核。"
        }
        val file = checkedAssetFile(snapshot.project.id, value.asset.privateRelativePath)
        check(file.isFile && file.length() == value.asset.byteLength && sha256(file) == value.asset.sha256) {
            "已保存过渡缺失或改变，请重新生成并完整复核。"
        }
        return true
    }

    private fun validateTransitionInput(input: ReviewedTransitionInput) {
        require(input.reviewId.isNotBlank() && input.reviewId.length <= 160 && input.reviewId.none { it.isISOControl() }) {
            "过渡复核令牌无效。"
        }
        require(SHA.matches(input.sha256) && input.width > 0 && input.height > 0 &&
            minOf(input.width, input.height) <= 1080 && maxOf(input.width, input.height) <= 2400) { "过渡摘要或画布尺寸无效。" }
        require(input.durationUs in 1..ProjectLimits.MAX_TRANSITION_US && input.startUs >= 0 &&
            input.endUs > input.startUs && input.endUs <= input.source.metadata.durationUs &&
            input.endUs - input.startUs <= ProjectLimits.MAX_TRANSITION_US &&
            input.durationUs <= input.endUs - input.startUs + 1_000L) { "过渡裁剪或实际时长无效，单段最多 10 秒。" }
        require(input.masks.size <= 20) { "每个过渡最多 20 块固定遮挡；无敏感画面可不遮挡。" }
        validId(input.source.sourceId)
        val sourceFile = File(root, input.source.privateRelativePath)
        val metadata = input.source.metadata
        require(input.source.privateRelativePath == "sources/${input.source.sourceId}.mp4" &&
            sourceFile.canonicalFile == sourceFile.absoluteFile && sourceFile.isFile &&
            metadata.byteLength > 0 && sourceFile.length() == metadata.byteLength && SHA.matches(metadata.sha256) &&
            metadata.width > 0 && metadata.height > 0 && metadata.durationUs > 0 &&
            metadata.rotationDeg in setOf(0, 90, 180, 270) && metadata.pixelWidthHeightRatio.isFinite() &&
            metadata.pixelWidthHeightRatio > 0f) { "本机原素材已缺失或来源记录无效。" }
        val candidate = input.file.canonicalFile
        require(candidate.isFile && candidate.path.startsWith(root.path + File.separator) &&
            candidate != sourceFile.canonicalFile && candidate.length() in 1..MAX_PNG_BYTES) {
            "只能保存本机生成并完整复核的 MP4 候选。"
        }
    }

    private fun edgeCount(snapshot: ProjectSnapshot): Int =
        snapshot.steps.sumOf { it.hotspots.size + (if (it.nextAction == null) 0 else 1) }

    private fun readSource(db: SQLiteDatabase, projectId: String, sourceId: String): ImportedSource? =
        db.rawQuery("SELECT source_json FROM sources WHERE project_id=? AND source_id=?", arrayOf(projectId, sourceId)).use {
            if (it.moveToFirst()) parseSource(JSONObject(it.getString(0))) else null
        }

    private fun deletionImpact(snapshot: ProjectSnapshot, stepId: String): StepDeletionImpact {
        val step = snapshot.steps.firstOrNull { it.id == stepId } ?: error("步骤已不存在，请刷新。")
        val incoming = snapshot.steps.flatMap { it.hotspots }.filter { it.targetStepId == stepId }
        val total = (step.hotspots.map { it.id } + incoming.map { it.id }).toSet().size
        val incomingNext = snapshot.steps.mapNotNull { it.nextAction }.filter { it.targetStepId == stepId }
        val outgoingNext = listOfNotNull(step.nextAction)
        val nextTotal = (incomingNext.map { it.id } + outgoingNext.map { it.id }).toSet().size
        return StepDeletionImpact(incoming.size, step.hotspots.size, total, total + nextTotal,
            snapshot.project.startStepId == stepId, incomingNext.size, outgoingNext.size)
    }

    private fun count(db: SQLiteDatabase, table: String, where: String, vararg args: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args).use { it.moveToFirst(); it.getInt(0) }

    private fun queueProjectAssets(db: SQLiteDatabase, projectId: String) {
        db.execSQL("INSERT INTO asset_cleanup(project_id,relative_path) SELECT project_id,relative_path FROM local_assets WHERE project_id=?",
            arrayOf(projectId))
    }

    private fun queueAsset(db: SQLiteDatabase, projectId: String, path: String) {
        db.insertOrThrow("asset_cleanup", null, ContentValues().apply {
            put("project_id", projectId); put("relative_path", path)
        })
    }

    private fun importKey(assetId: String): String = "${root.path}/$assetId"

    /** Called only under the process-shared lock. Recovery is journal-driven, never a sweep. */
    private fun recoverImports(db: SQLiteDatabase) {
        runCatching {
            val imports = db.rawQuery("SELECT project_id,asset_id,'png' FROM asset_imports UNION ALL SELECT project_id,asset_id,'mp4' FROM transition_imports", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
            }
            imports.forEach { (projectId, assetId, extension) ->
                if (importKey(assetId) !in activeImports) runCatching { cleanupImport(db, projectId, assetId, extension) }
            }
        }
    }

    /** A failed deletion keeps its journal row. Committed final assets are always retained. */
    private fun cleanupImport(db: SQLiteDatabase, projectId: String, assetId: String, extension: String = "png") {
        validId(projectId)
        validId(assetId)
        check(importKey(assetId) !in activeImports) { "步骤保存仍在进行，不能清理。" }
        val stagingRoot = File(root, "project-staging")
        val directory = File(stagingRoot, assetId)
        val temporary = File(directory, "candidate.part")
        check(stagingRoot.canonicalFile == stagingRoot.absoluteFile &&
            directory.canonicalFile == directory.absoluteFile &&
            temporary.canonicalFile == temporary.absoluteFile) { "步骤暂存路径不受支持。" }
        deleteImportFile(temporary)
        if (directory.exists()) {
            check(directory.isDirectory && directory.delete()) { "步骤暂存目录清理失败。" }
            syncDirectory(stagingRoot)
        }
        val path = assetPath(projectId, assetId, extension)
        if (count(db, "local_assets", "relative_path=?", path) == 0) {
            deleteImportFile(checkedAssetFile(projectId, path))
        }
        // Do not tie this row to a project FK: deleting a project during an active copy must
        // leave the recovery record until that copy's private files have actually been removed.
        db.delete(if (extension == "mp4") "transition_imports" else "asset_imports",
            "project_id=? AND asset_id=?", arrayOf(projectId, assetId))
    }

    private fun deleteImportFile(file: File) {
        if (file.exists()) {
            check(file.isFile && file.delete()) { "步骤文件清理失败，将在下次打开时重试。" }
            file.parentFile?.let(::syncDirectory)
        }
    }

    private fun cleanupPending(db: SQLiteDatabase) {
        // Best effort after commit. Never delete any directory recursively or the shared sources.
        runCatching {
            val pending = db.rawQuery("SELECT project_id,relative_path FROM asset_cleanup", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
            }
            pending.forEach { (projectId, path) ->
                runCatching {
                    if (count(db, "local_assets", "relative_path=?", path) == 0) {
                        val file = checkedAssetFile(projectId, path)
                        if (file.delete() || !file.exists()) {
                            db.delete("asset_cleanup", "project_id=? AND relative_path=?", arrayOf(projectId, path))
                            file.parentFile?.delete() // Only succeeds if the exact project directory is empty.
                        }
                    }
                }
            }
        }
    }

    private fun pendingCleanupCount(db: SQLiteDatabase, projectId: String, fallback: Int): Int =
        runCatching { count(db, "asset_cleanup", "project_id=?", projectId) }.getOrDefault(fallback)

    private fun projectAssetDirectory(projectId: String): File {
        validId(projectId)
        val assets = privateDirectory(File(root, "project-assets"), root)
        return privateDirectory(File(assets, projectId), assets)
    }

    private fun privateDirectory(directory: File, parent: File): File {
        val created = !directory.isDirectory && directory.mkdir()
        check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile &&
            directory.canonicalFile.parentFile == parent.canonicalFile) { "无法建立本机私有项目目录。" }
        // Persist a newly created path before any database row can reference a file within it.
        if (created) syncDirectory(parent)
        return directory
    }

    private fun checkedAssetFile(projectId: String, path: String): File {
        validId(projectId)
        val parts = path.split('/')
        check(parts.size == 3 && parts[0] == "project-assets" && parts[1] == projectId && (parts[2].endsWith(".png") || parts[2].endsWith(".mp4"))) {
            "步骤资产路径不受支持。"
        }
        validId(parts[2].substringBeforeLast('.'))
        val directory = File(root, "project-assets/$projectId")
        val file = File(root, path)
        check(directory.canonicalFile == directory.absoluteFile &&
            file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == directory) {
            "步骤资产必须来自本机私有项目目录。"
        }
        return file
    }

    private fun hasMatchingCapture(snapshot: ProjectSnapshot, input: ReviewedStepInput): Boolean {
        val step = snapshot.steps.firstOrNull { it.captureId == input.captureId } ?: return false
        check(step.asset.sha256 == input.sha256.lowercase() && step.asset.width == input.width &&
            step.asset.height == input.height && step.source == input.source && step.frameTimeUs == input.frameTimeUs &&
            step.timePrecisionUs == input.timePrecisionUs && step.masks == input.masks) {
            "此保存令牌对应的画面已改变，请重新生成并复核。"
        }
        return true
    }

    private fun validateInput(input: ReviewedStepInput) {
        require(SHA.matches(input.sha256)) { "已复核画面的摘要无效。" }
        require(input.width > 0 && input.height > 0 && input.width.toLong() * input.height <= MAX_IMAGE_PIXELS) {
            "步骤画面尺寸无效或超过 1200 万像素。"
        }
        require(input.masks.size <= 20) { "每个步骤最多 20 块遮挡。" }
        require(input.timePrecisionUs > 0 && input.frameTimeUs in 0..input.source.metadata.durationUs) {
            "实际取帧时间或时间精度无效。"
        }
        validId(input.source.sourceId)
        val sourceFile = File(root, input.source.privateRelativePath)
        require(input.source.privateRelativePath == "sources/${input.source.sourceId}.mp4" &&
            sourceFile.canonicalFile == sourceFile.absoluteFile && sourceFile.isFile) { "本机原素材已缺失，请重新导入。" }
        val metadata = input.source.metadata
        require(metadata.byteLength > 0 && sourceFile.length() == metadata.byteLength && SHA.matches(metadata.sha256) &&
            metadata.width > 0 && metadata.height > 0 && metadata.durationUs > 0 &&
            metadata.rotationDeg in setOf(0, 90, 180, 270) &&
            metadata.pixelWidthHeightRatio.isFinite() && metadata.pixelWidthHeightRatio > 0f) { "原素材记录无效。" }
        val candidate = input.file.canonicalFile
        require(candidate.isFile && candidate.path.startsWith(root.path + File.separator) &&
            candidate != sourceFile.canonicalFile && candidate.length() in 1..MAX_PNG_BYTES) {
            "只能保存本机生成并已复核的 PNG 候选。"
        }
    }

    private fun sourceJson(source: ImportedSource) = JSONObject().apply {
        put("id", source.sourceId); put("path", source.privateRelativePath); put("name", source.displayName)
        put("mime", source.metadata.mime); put("bytes", source.metadata.byteLength); put("sha256", source.metadata.sha256)
        put("width", source.metadata.width); put("height", source.metadata.height); put("rotation", source.metadata.rotationDeg)
        put("durationUs", source.metadata.durationUs); put("pixelRatio", source.metadata.pixelWidthHeightRatio)
    }

    private fun parseSource(json: JSONObject) = ImportedSource(
        json.getString("id"), json.getString("path"), json.getString("name"),
        SourceMetadata(json.getString("mime"), json.getLong("bytes"), json.getString("sha256"),
            json.getInt("width"), json.getInt("height"), json.getInt("rotation"), json.getLong("durationUs"),
            json.getDouble("pixelRatio").toFloat()),
    )

    private fun masksJson(masks: List<OpaqueMask>) = JSONArray().apply {
        masks.forEach { put(JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
    }

    private fun parseMasks(json: JSONArray): List<OpaqueMask> {
        require(json.length() <= 20) { "遮挡记录超限。" }
        return List(json.length()) { index -> json.getJSONArray(index).let {
            OpaqueMask(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
        } }
    }

    private class Database(context: Context, path: String) : SQLiteOpenHelper(context, path, null, 5) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE projects (
                project_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, goal TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, draft_revision INTEGER NOT NULL CHECK(draft_revision>0),
                start_state_id TEXT,
                FOREIGN KEY(project_id,start_state_id) REFERENCES states(project_id,state_id) DEFERRABLE INITIALLY DEFERRED
            )""")
            db.execSQL("""CREATE TABLE sources (
                project_id TEXT NOT NULL, source_id TEXT NOT NULL, source_json TEXT NOT NULL,
                PRIMARY KEY(project_id,source_id), FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )""")
            db.execSQL("""CREATE TABLE local_assets (
                asset_id TEXT PRIMARY KEY NOT NULL, project_id TEXT NOT NULL, relative_path TEXT UNIQUE NOT NULL,
                sha256 TEXT NOT NULL, byte_length INTEGER NOT NULL CHECK(byte_length>0),
                width INTEGER NOT NULL CHECK(width>0), height INTEGER NOT NULL CHECK(height>0),
                UNIQUE(project_id,asset_id), FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )""")
            db.execSQL("""CREATE TABLE states (
                project_id TEXT NOT NULL, state_id TEXT NOT NULL, capture_id TEXT NOT NULL,
                sort_order INTEGER NOT NULL CHECK(sort_order>=0),
                title TEXT NOT NULL, description TEXT NOT NULL, is_terminal INTEGER NOT NULL CHECK(is_terminal IN (0,1)),
                source_id TEXT NOT NULL, input_asset_id TEXT NOT NULL, frame_pts_us INTEGER NOT NULL CHECK(frame_pts_us>=0),
                time_precision_us INTEGER NOT NULL CHECK(time_precision_us>0), masks_json TEXT NOT NULL,
                PRIMARY KEY(project_id,state_id), UNIQUE(project_id,input_asset_id), UNIQUE(project_id,capture_id),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,source_id) REFERENCES sources(project_id,source_id) DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(project_id,input_asset_id) REFERENCES local_assets(project_id,asset_id) DEFERRABLE INITIALLY DEFERRED
            )""")
            db.execSQL("""CREATE TABLE hotspots (
                project_id TEXT NOT NULL, hotspot_id TEXT NOT NULL, state_id TEXT NOT NULL, label TEXT NOT NULL,
                rect_left REAL NOT NULL CHECK(rect_left>=0 AND rect_left<1),
                rect_top REAL NOT NULL CHECK(rect_top>=0 AND rect_top<1),
                rect_right REAL NOT NULL CHECK(rect_right>rect_left AND rect_right<=1),
                rect_bottom REAL NOT NULL CHECK(rect_bottom>rect_top AND rect_bottom<=1),
                PRIMARY KEY(project_id,hotspot_id), UNIQUE(project_id,hotspot_id,state_id),
                FOREIGN KEY(project_id,state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE
            )""")
            db.execSQL("""CREATE TABLE edges (
                project_id TEXT NOT NULL, edge_id TEXT NOT NULL, hotspot_id TEXT NOT NULL, from_state_id TEXT NOT NULL,
                to_state_id TEXT, end_label TEXT,
                PRIMARY KEY(project_id,edge_id), UNIQUE(project_id,hotspot_id),
                CHECK((to_state_id IS NOT NULL AND end_label IS NULL) OR (to_state_id IS NULL AND end_label IS NOT NULL AND length(trim(end_label))>0)),
                FOREIGN KEY(project_id,hotspot_id,from_state_id) REFERENCES hotspots(project_id,hotspot_id,state_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,from_state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,to_state_id) REFERENCES states(project_id,state_id) DEFERRABLE INITIALLY DEFERRED
            )""")
            db.execSQL("CREATE TABLE asset_cleanup(project_id TEXT NOT NULL, relative_path TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE asset_imports(project_id TEXT NOT NULL, asset_id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE INDEX projects_updated ON projects(updated_at)")
            db.execSQL("CREATE INDEX states_order ON states(project_id,sort_order)")
            db.execSQL("CREATE INDEX states_source ON states(source_id)")
            db.execSQL("CREATE INDEX hotspots_state ON hotspots(project_id,state_id)")
            db.execSQL("CREATE INDEX edges_target ON edges(project_id,to_state_id)")
            createNextActions(db)
            createTransitions(db)
            createRegions(db)
            createEditorDrafts(db)
        }

        private fun createNextActions(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE next_actions (
                project_id TEXT NOT NULL, action_id TEXT NOT NULL, from_state_id TEXT NOT NULL,
                label TEXT NOT NULL CHECK(length(trim(label))>0), to_state_id TEXT,
                PRIMARY KEY(project_id,action_id), UNIQUE(project_id,from_state_id),
                FOREIGN KEY(project_id,from_state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,to_state_id) REFERENCES states(project_id,state_id) DEFERRABLE INITIALLY DEFERRED
            )""")
            db.execSQL("CREATE INDEX next_actions_target ON next_actions(project_id,to_state_id)")
        }

        private fun createTransitions(db: SQLiteDatabase) {
            // No edge FK: saveStepDraft replaces edge rows atomically while preserving stable
            // IDs. The transaction reconciles semantic edge changes and queues old asset removal.
            db.execSQL("""CREATE TABLE edge_transitions (
                project_id TEXT NOT NULL, edge_id TEXT NOT NULL, asset_id TEXT NOT NULL,
                source_id TEXT NOT NULL, start_us INTEGER NOT NULL CHECK(start_us>=0),
                end_us INTEGER NOT NULL CHECK(end_us>start_us AND end_us-start_us<=10000000),
                duration_us INTEGER NOT NULL CHECK(duration_us>0 AND duration_us<=10000000),
                masks_json TEXT NOT NULL, review_id TEXT NOT NULL CHECK(length(review_id)>0),
                PRIMARY KEY(project_id,edge_id), UNIQUE(project_id,asset_id), UNIQUE(project_id,review_id),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,asset_id) REFERENCES local_assets(project_id,asset_id) DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(project_id,source_id) REFERENCES sources(project_id,source_id) DEFERRABLE INITIALLY DEFERRED
            )""")
            db.execSQL("CREATE INDEX edge_transitions_source ON edge_transitions(source_id)")
            // Recovery rows deliberately outlive project deletion, exactly like image imports.
            db.execSQL("CREATE TABLE transition_imports(project_id TEXT NOT NULL, asset_id TEXT PRIMARY KEY NOT NULL)")
        }


        private fun createRegions(db: SQLiteDatabase) {
            // base_asset_id deliberately keeps the old identity after replacement so invalidation
            // is visible. Only the nullable crop asset is a live FK; it is never a source image.
            db.execSQL("""CREATE TABLE regions (
                project_id TEXT NOT NULL, region_id TEXT NOT NULL, state_id TEXT NOT NULL,
                base_asset_id TEXT NOT NULL, base_sha256 TEXT NOT NULL, name TEXT NOT NULL, group_name TEXT,
                x_px INTEGER NOT NULL CHECK(x_px>=0), y_px INTEGER NOT NULL CHECK(y_px>=0),
                width_px INTEGER NOT NULL CHECK(width_px>0), height_px INTEGER NOT NULL CHECK(height_px>0),
                source_width INTEGER NOT NULL CHECK(source_width>0), source_height INTEGER NOT NULL CHECK(source_height>0),
                z_index INTEGER NOT NULL CHECK(z_index BETWEEN -10000 AND 10000),
                anchor_x REAL NOT NULL CHECK(anchor_x BETWEEN 0 AND 1), anchor_y REAL NOT NULL CHECK(anchor_y BETWEEN 0 AND 1),
                asset_id TEXT, reviewed_at INTEGER,
                PRIMARY KEY(project_id,region_id), UNIQUE(project_id,asset_id),
                CHECK(x_px+width_px<=source_width AND y_px+height_px<=source_height),
                CHECK(reviewed_at IS NULL OR asset_id IS NOT NULL),
                FOREIGN KEY(project_id,state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,asset_id) REFERENCES local_assets(project_id,asset_id) DEFERRABLE INITIALLY DEFERRED
            )""")
            db.execSQL("CREATE INDEX regions_state ON regions(project_id,state_id)")
        }

        private fun createEditorDrafts(db: SQLiteDatabase) {
            // No media/source/path columns: this is private editor recovery, never official graph.
            db.execSQL("""CREATE TABLE editor_drafts (
                project_id TEXT NOT NULL, state_id TEXT NOT NULL, draft_json TEXT NOT NULL,
                PRIMARY KEY(project_id,state_id),
                CHECK(length(CAST(draft_json AS BLOB)) BETWEEN 1 AND 262144),
                FOREIGN KEY(project_id,state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE
            )""")
            db.execSQL("""CREATE TABLE editor_draft_sessions (
                project_id TEXT PRIMARY KEY NOT NULL, generation INTEGER NOT NULL CHECK(generation>0),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )""")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..4 && newVersion == 5) { "项目数据库需要安全迁移；请保留现有本机数据。" }
            // SQLiteOpenHelper commits both additive migrations and user_version together. Never
            // rebuild old tables or drop pending image cleanup/import records during an upgrade.
            if (oldVersion < 2) createNextActions(db)
            if (oldVersion < 3) createTransitions(db)
            if (oldVersion < 4) createRegions(db)
            if (oldVersion < 5) createEditorDrafts(db)
        }
    }

    companion object {
        private val lock = Any()
        /** Guarded by lock; shared across every store using the same app-private root. */
        private val activeImports = mutableSetOf<String>()
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private val SHA = Regex("[0-9a-fA-F]{64}")
        private const val MAX_PNG_BYTES = 50L * 1024 * 1024
        private const val MAX_IMAGE_PIXELS = 12_000_000L
        private const val SUMMARY_SQL = "SELECT p.*, (SELECT COUNT(*) FROM states s WHERE s.project_id=p.project_id) AS step_count FROM projects p"
        private fun syncDirectory(directory: File) {
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "步骤资产目录无效。" }
                Os.fsync(descriptor)
            } finally { Os.close(descriptor) }
        }
        private fun newId() = UUID.randomUUID().toString()
        private fun validId(id: String) { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "项目对象标识无效。" } }
        private fun assetPath(projectId: String, assetId: String, extension: String = "png") = "project-assets/$projectId/$assetId.$extension"
        private fun text(value: String, label: String, limit: Int, allowEmpty: Boolean = false): String {
            val result = value.trim()
            require((allowEmpty || result.isNotBlank()) && result.length <= limit &&
                result.none { it.isISOControl() && it != '\n' && it != '\t' }) { "$label 不能为空、含控制字符或超过 $limit 字。" }
            return result
        }
        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count > 0) { "步骤资产无法继续读取。" }
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
        private fun Cursor.nullableString(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
        private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
        private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
        private fun Cursor.float(name: String) = getFloat(getColumnIndexOrThrow(name))
    }
}
