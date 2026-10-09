package com.tapscene.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
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
 * its metadata commits. Only addReviewedStep accepts reviewed output, never a raw video frame.
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
            ProjectDeletionResult(current.steps.size, hotspots, edgeCount(current), current.steps.size)
        }
        // Cleanup cannot turn a committed deletion into a reported failure. A journal row stays
        // until its exact, no-longer-referenced asset is gone, including across process restart.
        cleanupPending(db)
        result.copy(pendingAssetCleanupCount = pendingCleanupCount(db, projectId, result.stepCount))
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
    ): ProjectSnapshot {
        validId(projectId)
        validId(stepId)
        val cleanTitle = text(title, "步骤标题", 120)
        val cleanDescription = text(description, "步骤说明", 4_000, allowEmpty = true)
        val frozen = input.copy(masks = input.masks.toList())
        require(frozen.captureId.isNotBlank() && frozen.captureId.length <= 160 &&
            frozen.captureId.none { it.isISOControl() }) { "步骤保存令牌无效。" }
        val alreadySaved = access { db ->
            val project = requireSnapshot(db, projectId)
            if (hasMatchingCapture(project, frozen)) project else {
                require(project.steps.size < ProjectLimits.MAX_STEPS) { "每个项目最多 40 个步骤。" }
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
                        if (hasMatchingCapture(current, frozen)) return@transaction current
                        require(current.steps.none { it.id == stepId }) { "这个步骤已经保存，请刷新后继续。" }
                        require(current.steps.size < ProjectLimits.MAX_STEPS) { "每个项目最多 40 个步骤。" }
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
                        db.insertOrThrow("states", null, ContentValues().apply {
                            put("project_id", projectId); put("state_id", stepId); put("capture_id", frozen.captureId)
                            put("sort_order", current.steps.size); put("title", cleanTitle)
                            put("description", cleanDescription); put("is_terminal", 0)
                            put("source_id", frozen.source.sourceId); put("input_asset_id", assetId)
                            put("frame_pts_us", frozen.frameTimeUs); put("time_precision_us", frozen.timePrecisionUs)
                            put("masks_json", masksJson(frozen.masks).toString())
                        })
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
            db.delete("states", "project_id=? AND state_id=?", arrayOf(projectId, stepId))
            queueAsset(db, projectId, step.asset.privateRelativePath)
            db.delete("local_assets", "project_id=? AND asset_id=?", arrayOf(projectId, step.asset.id))
            if (count(db, "states", "project_id=? AND source_id=?", projectId, step.sourceId) == 0) {
                db.delete("sources", "project_id=? AND source_id=?", arrayOf(projectId, step.sourceId))
            }
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
     * Copy only persisted, reviewed PNG inputs while holding the SAME process lock as edits and
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
        current.steps.forEach { step ->
            val asset = step.asset
            validId(asset.id)
            val source = checkedAssetFile(projectId, asset.privateRelativePath)
            check(source.isFile && source.length() == asset.byteLength && sha256(source) == asset.sha256) {
                "步骤画面缺失或改变，请返回编辑。"
            }
            total += asset.byteLength
            check(total <= MAX_PNG_BYTES) { "成品图片合计超过 50 MiB。" }
            val output = File(target, "${asset.id}.png")
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

    fun isSourceReferenced(sourceId: String): Boolean = access { db ->
        count(db, "states", "source_id=?", sourceId) > 0
    }

    private fun <T> access(block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        val db = helper.writableDatabase
        recoverImports(db)
        cleanupPending(db)
        block(db)
    }

    private fun edit(projectId: String, change: (SQLiteDatabase) -> Unit): ProjectSnapshot = access { db ->
        transaction(db) {
            requireSnapshot(db, projectId)
            change(db)
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
                ))
            }
        }

    private fun readNextAction(db: SQLiteDatabase, projectId: String, stepId: String): ProjectNextAction? =
        db.rawQuery("SELECT action_id,label,to_state_id FROM next_actions WHERE project_id=? AND from_state_id=?",
            arrayOf(projectId, stepId)).use { cursor ->
            if (cursor.moveToFirst()) ProjectNextAction(cursor.getString(0), cursor.getString(1), cursor.nullableString("to_state_id"))
            else null
        }

    private fun insertNextAction(db: SQLiteDatabase, projectId: String, stepId: String, action: ProjectNextAction) {
        db.insertOrThrow("next_actions", null, ContentValues().apply {
            put("project_id", projectId); put("action_id", action.id); put("from_state_id", stepId); put("label", action.label)
            if (action.targetStepId == null) putNull("to_state_id") else put("to_state_id", action.targetStepId)
        })
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
            val imports = db.rawQuery("SELECT project_id,asset_id FROM asset_imports", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
            }
            imports.forEach { (projectId, assetId) ->
                if (importKey(assetId) !in activeImports) runCatching { cleanupImport(db, projectId, assetId) }
            }
        }
    }

    /** A failed deletion keeps its journal row. Committed final assets are always retained. */
    private fun cleanupImport(db: SQLiteDatabase, projectId: String, assetId: String) {
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
        val path = assetPath(projectId, assetId)
        if (count(db, "local_assets", "relative_path=?", path) == 0) {
            deleteImportFile(checkedAssetFile(projectId, path))
        }
        // Do not tie this row to a project FK: deleting a project during an active copy must
        // leave the recovery record until that copy's private files have actually been removed.
        db.delete("asset_imports", "project_id=? AND asset_id=?", arrayOf(projectId, assetId))
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
        check(parts.size == 3 && parts[0] == "project-assets" && parts[1] == projectId && parts[2].endsWith(".png")) {
            "步骤资产路径不受支持。"
        }
        validId(parts[2].removeSuffix(".png"))
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

    private class Database(context: Context, path: String) : SQLiteOpenHelper(context, path, null, 2) {
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

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion == 1 && newVersion == 2) { "项目数据库需要安全迁移；请保留现有本机数据。" }
            // SQLiteOpenHelper wraps this additive migration and user_version in one transaction.
            // Existing hotspot tables, graph IDs, source records and asset paths stay untouched.
            createNextActions(db)
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
        private fun assetPath(projectId: String, assetId: String) = "project-assets/$projectId/$assetId.png"
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
