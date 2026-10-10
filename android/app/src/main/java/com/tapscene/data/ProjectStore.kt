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
import com.tapscene.media.ImportedImageSource
import com.tapscene.media.ImageSourceMetadata
import com.tapscene.media.ScreenshotImporter
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
    private var databaseCloseFailure: Throwable? = null

    fun readDraftAiConfig(projectId: String): DraftAiConfig? = access { db ->
        val project = requireSnapshot(db, projectId)
        readDraftAiConfig(db, projectId)?.let { it.copy(needsRepair = it.needsRepair || it.boundRevision != project.project.revision) }
    }

    fun draftAiIssues(projectId: String, config: DraftAiConfig): List<String> = access { db ->
        DraftAiConfigCodec.encode(config)
        DraftPlanProjection.issues(requireSnapshot(db, projectId), config)
    }

    /** Saving a broken plan is allowed, but it remains visibly blocked. No reference is pruned. */
    fun saveDraftAiConfig(projectId: String, expectedRevision: Long, config: DraftAiConfig): DraftAiConfig = access { db -> transaction(db) {
        val snapshot = requireSnapshot(db, projectId)
        check(snapshot.project.revision == expectedRevision) { "草稿已改变；动画输入已保留，请刷新后核对。" }
        DraftAiConfigCodec.encode(config)
        check(expectedRevision < Long.MAX_VALUE) { "项目修订已达上限。" }
        val issues = DraftPlanProjection.issues(snapshot, config)
        // A fixed candidate includes a plan snapshot. Advancing revision prevents a changed plan
        // from accidentally reusing an older candidate with the same graph revision.
        bump(db, projectId)
        val saved = config.copy(boundRevision = expectedRevision + 1, needsRepair = issues.isNotEmpty())
        writeDraftAiConfig(db, projectId, saved)
        saved
    } }

    private fun readDraftAiConfig(db: SQLiteDatabase, projectId: String): DraftAiConfig? =
        db.rawQuery("SELECT bound_revision,needs_repair,config_json FROM draft_ai_configs WHERE project_id=?", arrayOf(projectId)).use {
            if (!it.moveToFirst()) null else DraftAiConfigCodec.decode(it.getString(2), it.getLong(0), it.getInt(1) == 1)
        }

    private fun writeDraftAiConfig(db: SQLiteDatabase, projectId: String, config: DraftAiConfig) {
        check(db.insertWithOnConflict("draft_ai_configs", null, ContentValues().apply {
            put("project_id", projectId); put("bound_revision", config.boundRevision)
            put("needs_repair", if (config.needsRepair) 1 else 0); put("config_json", DraftAiConfigCodec.encode(config))
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "动画配置未保存，请重试。" }
    }

    internal fun beginAiImport(sessionId: String, projectId: String): AiImportSession = access { db -> transaction(db) {
        validId(sessionId); validId(projectId)
        db.insertOrThrow("ai_import_sessions", null, ContentValues().apply {
            put("session_id", sessionId); put("state", "preparing"); put("project_id", projectId)
            put("created_at", System.currentTimeMillis())
        })
        requireNotNull(readAiImport(db, sessionId))
    } }

    internal fun readAiImport(sessionId: String): AiImportSession? = access { db -> validId(sessionId); readAiImport(db, sessionId) }
    internal fun pendingAiImports(): List<AiImportSession> = access { db ->
        db.rawQuery("SELECT * FROM ai_import_sessions WHERE state IN ('preparing','ready') ORDER BY created_at DESC", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(aiImport(cursor)) }
        }
    }
    internal fun finishedAiImports(): List<AiImportSession> = access { db ->
        db.rawQuery("SELECT * FROM ai_import_sessions WHERE state IN ('committed','cancelled','failed')", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(aiImport(cursor)) }
        }
    }
    private fun readAiImport(db: SQLiteDatabase, id: String): AiImportSession? =
        db.rawQuery("SELECT * FROM ai_import_sessions WHERE session_id=?", arrayOf(id)).use { if (it.moveToFirst()) aiImport(it) else null }
    private fun aiImport(cursor: Cursor) = AiImportSession(cursor.string("session_id"), cursor.string("state"), cursor.string("project_id"),
        cursor.nullableString("input_sha"), cursor.nullableString("preview_digest"), cursor.nullableString("prepared_json"))

    internal fun readyAiImport(id: String, inputSha: String, previewDigest: String, prepared: String) = access { db -> transaction(db) {
        require(SHA.matches(inputSha) && SHA.matches(previewDigest) && prepared.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
        val current = readAiImport(db, id) ?: error("导入会话已不存在。")
        check(current.status == "preparing" || current.status == "ready") { "此导入已经结束，请重新打开结果。" }
        db.update("ai_import_sessions", ContentValues().apply {
            put("state", "ready"); put("input_sha", inputSha); put("preview_digest", previewDigest); put("prepared_json", prepared)
        }, "session_id=?", arrayOf(id))
    } }

    internal fun stopAiImport(id: String, status: String): AiDraftImportResult = access { db -> transaction(db) {
        require(status == "cancelled" || status == "failed")
        val current = readAiImport(db, id) ?: error("导入会话已不存在。")
        if (current.status !in setOf("committed", "cancelled")) db.update("ai_import_sessions", ContentValues().apply { put("state", status) }, "session_id=?", arrayOf(id))
        aiResult(db, requireNotNull(readAiImport(db, id)))
    } }
    internal fun aiImportResult(id: String): AiDraftImportResult = access { db -> aiResult(db, readAiImport(db, id) ?: error("导入会话已不存在。")) }
    private fun aiResult(db: SQLiteDatabase, session: AiImportSession) = AiDraftImportResult(session.id, session.status,
        session.projectId.takeIf { session.status == "committed" }, count(db, "projects", "project_id=?", session.projectId) == 1)

    /** All imported bytes and the complete graph/config/receipt commit together. No review is inherited. */
    internal suspend fun installAiDraft(sessionId: String, expectedDigest: String, scene: com.tapscene.packageformat.ViewerScene,
        plan: com.tapscene.packageformat.RenderPlan, copies: List<AiImportAssetCopy>, packageRoot: File): AiDraftImportResult {
        check(com.tapscene.packageformat.AiDraftImportPolicy.inspect(scene).isEmpty()) { "包内容无法无损编辑，请先处理导入问题。" }
        com.tapscene.packageformat.RenderPlan.parse(scene, plan.toBytes())
        val session = readAiImport(sessionId) ?: error("导入会话已不存在。")
        if (session.status == "committed") return aiImportResult(sessionId)
        check(session.status == "ready" && session.previewDigest == expectedDigest) { "待导入内容已变化，请重新核对。" }
        val projectId = session.projectId
        val assets = scene.assets.associateBy { it.id }
        check(copies.size == scene.states.size + scene.regions.size && copies.map { it.assetId }.distinct().size == copies.size)
        scene.states.forEach { state -> check(copies.single { !it.region && it.ownerId == state.id }.sourceAssetId == state.imageAssetId) }
        scene.regions.forEach { region -> check(copies.single { it.region && it.ownerId == region.id }.sourceAssetId == region.assetId) }
        val expanded = copies.sumOf { requireNotNull(assets[it.sourceAssetId]).byteLength }
        require(expanded <= MAX_PNG_BYTES) { "独立展开后的安全图片超过 50 MiB，无法按当前限制交付。" }
        require(root.usableSpace > expanded + 8L * 1024 * 1024) { "本机空间不足，需为独立图片副本保留更多空间。" }
        access { db -> transaction(db) {
            val current = readAiImport(db, sessionId) ?: error("导入会话已不存在。")
            check(current.status == "ready" && current.previewDigest == expectedDigest)
            check(snapshot(db, projectId) == null) { "新项目身份已被占用。" }
            copies.forEach { copy ->
                validId(copy.assetId); check(importKey(copy.assetId) !in activeImports) { "此导入仍在保存，请稍候。" }
                db.insertOrThrow("asset_imports", null, ContentValues().apply { put("project_id", projectId); put("asset_id", copy.assetId) })
            }
        }; copies.forEach { activeImports.add(importKey(it.assetId)) } }
        var committed = false
        var failure: Throwable? = null
        try {
            val stagingRoot = privateDirectory(File(root, "project-staging"), root)
            copies.forEach { copy ->
                currentCoroutineContext().ensureActive()
                val asset = requireNotNull(assets[copy.sourceAssetId])
                val source = File(packageRoot, asset.path)
                check(source.canonicalFile == source.absoluteFile && source.isFile && source.length() == asset.byteLength && sha256(source) == asset.sha256) { "待导入图片已改变。" }
                val operation = File(stagingRoot, copy.assetId)
                check(operation.mkdir()) { "无法建立导入图片暂存。" }
                val output = File(operation, "candidate.part")
                source.inputStream().use { input -> FileOutputStream(output).use { sink ->
                    var total = 0L; val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        check(n > 0); total += n; check(total <= asset.byteLength) { "待导入图片在复制时改变。" }; sink.write(buffer, 0, n)
                    }
                    check(total == asset.byteLength); sink.fd.sync()
                } }
                SafeMediaWriterValidation.verifyPng(output, asset.width, asset.height, emptyList())
                check(sha256(output) == asset.sha256) { "导入图片副本摘要不一致。" }
            }
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) { synchronized(lock) {
                val db = database()
                val result = transaction(db) {
                    val current = readAiImport(db, sessionId) ?: error("导入会话已不存在。")
                    if (current.status == "committed") return@transaction aiResult(db, current)
                    check(current.status == "ready" && current.previewDigest == expectedDigest) { "导入已取消或待确认内容已改变。" }
                    val now = System.currentTimeMillis()
                    db.insertOrThrow("projects", null, ContentValues().apply {
                        put("project_id", projectId); put("title", scene.title); put("goal", scene.goal)
                        put("created_at", now); put("updated_at", now); put("draft_revision", 1L); put("start_state_id", scene.startStateId)
                    })
                    val destination = projectAssetDirectory(projectId)
                    copies.forEach { copy ->
                        val asset = requireNotNull(assets[copy.sourceAssetId]); val source = File(root, "project-staging/${copy.assetId}/candidate.part")
                        check(sha256(source) == asset.sha256) { "图片在提交前改变。" }
                        val target = File(destination, "${copy.assetId}.png"); check(!target.exists())
                        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        db.insertOrThrow("local_assets", null, ContentValues().apply {
                            put("project_id", projectId); put("asset_id", copy.assetId); put("relative_path", assetPath(projectId, copy.assetId))
                            put("sha256", asset.sha256); put("byte_length", asset.byteLength); put("width", asset.width); put("height", asset.height)
                        })
                    }
                    syncDirectory(destination)
                    scene.states.forEachIndexed { index, state ->
                        val copy = copies.single { !it.region && it.ownerId == state.id }; val asset = requireNotNull(assets[state.imageAssetId])
                        db.insertOrThrow("package_step_origins", null, ContentValues().apply {
                            put("project_id", projectId); put("state_id", state.id); put("import_id", sessionId)
                            put("source_state_id", state.id); put("source_asset_id", asset.id); put("source_sha256", asset.sha256); put("declared_kind", state.sourceKind)
                        })
                        db.insertOrThrow("states", null, ContentValues().apply {
                            put("project_id", projectId); put("state_id", state.id); put("capture_id", "$sessionId:${state.id}")
                            put("sort_order", index); put("title", state.title); put("description", state.description)
                            put("is_terminal", if (state.terminal) 1 else 0); put("input_asset_id", copy.assetId); put("masks_json", "[]")
                            putOrigin(StepOrigin.PackageSafeImage(sessionId, state.id, asset.id, asset.sha256, state.sourceKind)); put("evidence_kind", "imported")
                        })
                    }
                    scene.hotspots.forEach { hotspot -> db.insertOrThrow("hotspots", null, ContentValues().apply {
                        put("project_id", projectId); put("hotspot_id", hotspot.id); put("state_id", hotspot.stateId); put("label", hotspot.label)
                        put("rect_left", hotspot.rect.x.toFloat()); put("rect_top", hotspot.rect.y.toFloat())
                        put("rect_right", (hotspot.rect.x + hotspot.rect.width).toFloat()); put("rect_bottom", (hotspot.rect.y + hotspot.rect.height).toFloat())
                    }) }
                    scene.edges.forEach { edge ->
                        if (edge.trigger == "continue") db.insertOrThrow("next_actions", null, ContentValues().apply {
                            put("project_id", projectId); put("action_id", edge.id); put("from_state_id", edge.fromStateId); put("label", edge.label); put("to_state_id", edge.toStateId)
                        }) else db.insertOrThrow("edges", null, ContentValues().apply {
                            put("project_id", projectId); put("edge_id", edge.id); put("hotspot_id", edge.hotspotId); put("from_state_id", edge.fromStateId)
                            put("to_state_id", edge.toStateId); put("end_label", edge.endLabel)
                        })
                    }
                    scene.regions.forEach { region ->
                        val base = copies.single { !it.region && it.ownerId == region.stateId }
                        val crop = copies.single { it.region && it.ownerId == region.id }
                        db.insertOrThrow("regions", null, ContentValues().apply {
                            put("project_id", projectId); put("region_id", region.id); put("state_id", region.stateId)
                            put("base_asset_id", base.assetId); put("base_sha256", requireNotNull(assets[base.sourceAssetId]).sha256)
                            put("name", region.name); put("group_name", region.group); put("x_px", region.bbox.x); put("y_px", region.bbox.y)
                            put("width_px", region.bbox.width); put("height_px", region.bbox.height); put("source_width", region.sourceWidth); put("source_height", region.sourceHeight)
                            put("z_index", region.zIndex); put("anchor_x", region.anchor.x); put("anchor_y", region.anchor.y); put("asset_id", crop.assetId); putNull("reviewed_at")
                        })
                    }
                    val config = DraftAiConfig.imported(plan)
                    check(DraftPlanProjection.issues(requireSnapshot(db, projectId), config).isEmpty()) { "导入动画计划与新草稿不一致。" }
                    writeDraftAiConfig(db, projectId, config)
                    db.update("ai_import_sessions", ContentValues().apply { put("state", "committed") }, "session_id=?", arrayOf(sessionId))
                    aiResult(db, requireNotNull(readAiImport(db, sessionId)))
                }
                committed = true; result
            } }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = synchronized(lock) {
                copies.forEach { activeImports.remove(importKey(it.assetId)) }
                runCatching { copies.forEach { cleanupImport(database(), projectId, it.assetId) } }.exceptionOrNull()
            }
            if (cleanup != null && !committed) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    /** A pending result is returned before inspecting the source: a retry is not a new copy. */
    fun beginProjectCopy(sourceProjectId: String, expectedRevision: Long): ProjectCopyReceipt = access { db -> transaction(db) {
        validId(sourceProjectId)
        pendingProjectCopy(db, sourceProjectId)?.let { return@transaction it }
        requireProjectCopySource(db, sourceProjectId, expectedRevision)
        val id = newId()
        db.insertOrThrow("project_copy_operations", null, ContentValues().apply {
            put("operation_id", id); put("source_project_id", sourceProjectId); put("source_revision", expectedRevision)
            put("project_id", id); put("status", "preparing"); put("missing_raw_count", 0)
            put("acknowledged", 0); put("created_at", System.currentTimeMillis())
        })
        requireNotNull(readProjectCopy(db, id))
    } }

    fun pendingProjectCopy(sourceProjectId: String): ProjectCopyReceipt? = access { db ->
        validId(sourceProjectId); pendingProjectCopy(db, sourceProjectId)
    }

    private fun pendingProjectCopy(db: SQLiteDatabase, sourceProjectId: String): ProjectCopyReceipt? =
        db.rawQuery("SELECT * FROM project_copy_operations WHERE source_project_id=? AND acknowledged=0 AND status!='aborted' ORDER BY created_at,operation_id LIMIT 1",
            arrayOf(sourceProjectId)).use { if (it.moveToFirst()) projectCopyReceipt(it) else null }

    fun readProjectCopy(operationId: String): ProjectCopyReceipt? = access { db ->
        validId(operationId); readProjectCopy(db, operationId)
    }

    /** Only acknowledge after the result was presented. A failed acknowledgment is safely retried. */
    fun acknowledgeProjectCopy(operationId: String) = access { db -> transaction(db) {
        val receipt = readProjectCopy(db, operationId) ?: error("项目复制记录已不存在。")
        check(receipt.status == "committed") { "项目复制结果尚未确定，请重试。" }
        db.update("project_copy_operations", ContentValues().apply { put("acknowledged", 1) }, "operation_id=?", arrayOf(operationId))
        Unit
    } }

    internal fun uncommittedCopyProjectIds(): Set<String> = access { db ->
        db.rawQuery("SELECT project_id FROM project_copy_operations WHERE status!='committed'", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun projectCopyReceipt(cursor: Cursor) = ProjectCopyReceipt(cursor.string("operation_id"),
        cursor.string("source_project_id"), cursor.long("source_revision"), cursor.string("project_id"),
        cursor.string("status"), cursor.int("missing_raw_count")).also {
            validId(it.operationId); validId(it.sourceProjectId); validId(it.projectId)
            check(it.projectId == it.operationId && it.sourceProjectId != it.projectId) { "项目复制归属记录不一致，请保留本机数据。" }
        }

    private fun readProjectCopy(db: SQLiteDatabase, id: String): ProjectCopyReceipt? =
        db.rawQuery("SELECT * FROM project_copy_operations WHERE operation_id=?", arrayOf(id)).use {
            if (it.moveToFirst()) projectCopyReceipt(it) else null
        }

    private fun requireProjectCopySource(db: SQLiteDatabase, id: String, revision: Long): ProjectSnapshot {
        val saved = requireSnapshot(db, id)
        check(saved.project.revision == revision) { "原项目已改变，请重新读取后复制。" }
        db.rawQuery("SELECT draft_json FROM editor_drafts WHERE project_id=?", arrayOf(id)).use { rows ->
            while (rows.moveToNext()) {
                val draft = EditorDraftCodec.decode(rows.getString(0))
                check(draft.base == draft.edit && draft.pendingForm == null) { "请先保存或放弃项目内所有步骤修改（含面板输入），再复制。" }
            }
        }
        require(saved.steps.size <= ProjectLimits.MAX_STEPS && edgeCount(saved) <= ProjectLimits.MAX_EDGES &&
            saved.steps.all { it.hotspots.size <= ProjectLimits.MAX_HOTSPOTS_PER_STEP && it.regions.size <= ProjectLimits.MAX_REGIONS_PER_STEP } &&
            saved.steps.sumOf { it.regions.size } <= ProjectLimits.MAX_REGIONS) { "原项目的步骤、连线或区域超过复制限额。" }
        require(transitions(saved).values.sumOf { it.asset.durationUs } <= ProjectLimits.MAX_TOTAL_TRANSITION_US) { "项目过渡累计超过 60 秒。" }
        check(saved.steps.filter { it.evidenceKind == "imported" }.all { step ->
            count(db, "package_step_origins", "project_id=? AND state_id=?", id, step.id) == 1
        }) { "项目的外部包来源记录不完整。" }
        return saved
    }

    private data class ProjectCopyCapture(val snapshot: ProjectSnapshot, val rows: Map<String, List<ContentValues>>,
        val workspace: List<SourceDraft>, val videos: List<ImportedSource>, val images: List<ImportedImageSource>,
        val configJson: String?, val configRevision: Long?, val configNeedsRepair: Boolean)
    private data class ProjectCopyFile(val id: String, val kind: String, val source: File, val path: String,
        val sha: String, val bytes: Long, val width: Int = 0, val height: Int = 0,
        val masks: List<OpaqueMask> = emptyList(), val missing: Boolean = false)

    private fun copyRows(db: SQLiteDatabase, table: String, projectId: String): List<ContentValues> {
        check(table in PROJECT_COPY_TABLES)
        return db.rawQuery("SELECT * FROM $table WHERE project_id=? ORDER BY rowid", arrayOf(projectId)).use { rows ->
            buildList { while (rows.moveToNext()) add(ContentValues().apply {
                rows.columnNames.forEachIndexed { index, name -> when (rows.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> putNull(name)
                    Cursor.FIELD_TYPE_INTEGER -> put(name, rows.getLong(index))
                    Cursor.FIELD_TYPE_FLOAT -> put(name, rows.getDouble(index))
                    Cursor.FIELD_TYPE_STRING -> put(name, rows.getString(index))
                    else -> error("项目包含无法复制的字段。")
                } }
            }) }
        }
    }

    /** Caller holds WorkspaceStore's lock before ProjectStore's lock (recording uses this order too). */
    private fun captureProjectCopy(db: SQLiteDatabase, sourceId: String, revision: Long): ProjectCopyCapture {
        val snapshot = requireProjectCopySource(db, sourceId, revision)
        val rows = PROJECT_COPY_TABLES.associateWith { copyRows(db, it, sourceId) }
        val workspace = WorkspaceStore(app, sourceId).read()
        val videos = (rows.getValue("sources").map { parseSource(JSONObject(it.getAsString("source_json"))) } + workspace.map { it.source })
            .groupBy { it.sourceId }.map { (_, values) -> check(values.distinct().size == 1) { "原录屏来源记录不一致。" }; values.first() }
        val images = rows.getValue("image_sources").map { parseImageSource(JSONObject(it.getAsString("source_json"))) }
        require(videos.size <= 3 && videos.all { it.metadata.byteLength in 1..200L * 1024 * 1024 &&
            it.metadata.durationUs in 1..180_000_000L } && videos.sumOf { it.metadata.byteLength } <= 500L * 1024 * 1024 &&
            videos.sumOf { it.metadata.durationUs } <= 300_000_000L && images.size <= ProjectLimits.MAX_SCREENSHOTS &&
            images.all { it.metadata.byteLength in 1..10L * 1024 * 1024 }) { "原素材超过项目容量限制。" }
        check(rows.getValue("states").size == snapshot.steps.size && rows.getValue("hotspots").size == snapshot.steps.sumOf { it.hotspots.size } &&
            rows.getValue("edges").size == snapshot.steps.sumOf { it.hotspots.size } &&
            rows.getValue("edge_transitions").size == transitions(snapshot).size) { "项目图或过渡记录不完整。" }
        val config = readDraftAiConfig(db, sourceId)
        return ProjectCopyCapture(snapshot, rows, workspace, videos, images, config?.let(DraftAiConfigCodec::encode), config?.boundRevision, config?.needsRepair ?: false)
    }

    /** Every graph/media identity is local to this operation, including unresolved historical IDs. */
    private fun copiedId(operationId: String, domain: String, original: String): String =
        UUID.nameUUIDFromBytes("tapscene-project-copy:$operationId:$domain:$original".toByteArray(Charsets.UTF_8)).toString()

    /** Saved graph, media and COMMITTED receipt become visible together; files are prepared first.
     * The exact file journal is durable before any new private bytes are created. */
    suspend fun copySavedProject(sourceProjectId: String, expectedRevision: Long, operationId: String): ProjectCopyReceipt {
        validId(sourceProjectId); validId(operationId)
        val captured = WorkspaceStore.withProjectCopyLock { access { db ->
            val receipt = readProjectCopy(db, operationId) ?: error("请重新开始项目复制。")
            check(receipt.sourceProjectId == sourceProjectId && receipt.sourceRevision == expectedRevision) { "复制操作与原项目不一致。" }
            if (receipt.status == "committed") return@access null
            check(receipt.status == "preparing") { "这次复制已结束，请重新开始。" }
            check(importKey(operationId) !in activeProjectCopies) { "项目正在复制，请稍候。" }
            val value = try { captureProjectCopy(db, sourceProjectId, expectedRevision) }
            catch (failure: Throwable) {
                // A known source change invalidates this identity. Unreadable SQL is not evidence
                // that an earlier commit failed; access/recovery must establish that first.
                if (failure is IllegalStateException || failure is IllegalArgumentException) transaction(db) {
                    db.update("project_copy_operations", ContentValues().apply { put("status", "aborted") }, "operation_id=? AND status='preparing'", arrayOf(operationId))
                }
                throw failure
            }
            activeProjectCopies.add(importKey(operationId)); value
        } }
        if (captured == null) return requireNotNull(readProjectCopy(operationId))
        var failure: Throwable? = null
        try {
            fun id(domain: String, old: String) = copiedId(operationId, domain, old)
            val snapshot = captured.snapshot
            val stepsByAsset = snapshot.steps.associateBy { it.asset.id }
            val files = captured.rows.getValue("local_assets").map { asset ->
                val oldId = asset.getAsString("asset_id"); val newId = id("asset", oldId)
                val originalPath = asset.getAsString("relative_path")
                val extension = originalPath.substringAfterLast('.')
                check(extension in setOf("png", "mp4"))
                val bytes = asset.getAsLong("byte_length")
                require(bytes in 1..MAX_PNG_BYTES && asset.getAsInteger("width") > 0 && asset.getAsInteger("height") > 0 &&
                    asset.getAsInteger("width").toLong() * asset.getAsInteger("height") <= MAX_IMAGE_PIXELS) { "已保存媒体尺寸或容量无效。" }
                ProjectCopyFile(newId, "asset_$extension", checkedAssetFile(sourceProjectId, originalPath), assetPath(operationId, newId, extension),
                    asset.getAsString("sha256"), bytes, asset.getAsInteger("width"), asset.getAsInteger("height"), stepsByAsset[oldId]?.masks.orEmpty())
            } + captured.videos.map { source ->
                validId(source.sourceId); check(source.privateRelativePath == "sources/${source.sourceId}.mp4") { "原录屏路径无效。" }
                val newId = id("video", source.sourceId); val original = checkedProjectCopyInput(source.privateRelativePath)
                ProjectCopyFile(newId, "video", original, "sources/$newId.mp4", source.metadata.sha256, source.metadata.byteLength, missing = !original.exists())
            } + captured.images.map { source ->
                check(source.privateRelativePath == imageSourcePath(source.sourceId, source.metadata.mime)) { "原截图路径无效。" }
                val newId = id("image", source.sourceId); val original = checkedProjectCopyInput(source.privateRelativePath)
                ProjectCopyFile(newId, if (source.metadata.mime == "image/png") "image_png" else "image_jpeg", original,
                    imageSourcePath(newId, source.metadata.mime), source.metadata.sha256, source.metadata.byteLength, missing = !original.exists())
            }
            check(files.map { it.path }.distinct().size == files.size)
            require(root.usableSpace > files.filterNot { it.missing }.sumOf { it.bytes } + 8L * 1024 * 1024) { "本机空间不足，无法建立完整独立副本。" }
            access { db -> transaction(db) {
                check(count(db, "projects", "project_id=?", operationId) == 0) { "新项目身份已被占用。" }
                check(count(db, "project_copy_files", "operation_id=?", operationId) == 0) { "上次复制清理未完成，请重试。" }
                files.forEach { file ->
                    check(!File(root, file.path).exists()) { "副本文件身份冲突，请保留本机数据。" }
                    db.insertOrThrow("project_copy_files", null, ContentValues().apply {
                        put("operation_id", operationId); put("file_id", file.id); put("kind", file.kind)
                    })
                }
                check(listOf("", ".bak", ".new").none { File(root, "project-media-$operationId.json$it").exists() }) { "副本素材记录身份冲突。" }
                db.update("project_copy_operations", ContentValues().apply { put("workspace_owned", 1) }, "operation_id=?", arrayOf(operationId))
            } }
            val stagingRoot = privateDirectory(File(root, "project-copy-staging"), root)
            val staging = privateDirectory(File(stagingRoot, operationId), stagingRoot)
            files.filterNot { it.missing }.forEach { file ->
                currentCoroutineContext().ensureActive()
                check(file.source.isFile && file.source.length() == file.bytes) { "已保存媒体缺失或改变；复制未完成，不会改用原片。" }
                val output = File(staging, "${file.id}.part")
                check(!output.exists()) { "复制暂存尚未清理，请重试。" }
                var bytes = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                file.source.inputStream().use { input -> FileOutputStream(output).use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        check(n > 0); bytes += n; check(bytes <= file.bytes) { "原文件在复制期间改变。" }
                        digest.update(buffer, 0, n); sink.write(buffer, 0, n)
                    }
                    check(bytes == file.bytes && hex(digest.digest()) == file.sha) { "媒体副本摘要不一致，原项目保持不变。" }
                    sink.fd.sync()
                } }
                if (file.kind == "asset_png") SafeMediaWriterValidation.verifyPng(output, file.width, file.height, file.masks)
            }
            val copiedVideos = captured.videos.associate { source -> source.sourceId to source.copy(
                sourceId = id("video", source.sourceId), privateRelativePath = "sources/${id("video", source.sourceId)}.mp4") }
            val copiedWorkspace = captured.workspace.map { it.copy(source = copiedVideos.getValue(it.source.sourceId)) } +
                captured.videos.filter { source -> captured.workspace.none { it.source.sourceId == source.sourceId } }.map { SourceDraft(copiedVideos.getValue(it.sourceId)) }
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) { WorkspaceStore.withProjectCopyLock { synchronized(lock) {
                val db = database()
                val receipt = requireNotNull(readProjectCopy(db, operationId))
                if (receipt.status == "committed") return@synchronized receipt
                check(receipt.status == "preparing") { "复制已取消。" }
                check(captureProjectCopy(db, sourceProjectId, expectedRevision) == captured) { "原项目或素材已改变，请重新读取后复制。" }
                check(count(db, "projects", "project_id=?", operationId) == 0) { "新项目身份已被占用。" }
                files.filterNot { it.missing }.forEach { file ->
                    val temporary = File(staging, "${file.id}.part")
                    check(temporary.isFile && temporary.length() == file.bytes && sha256(temporary) == file.sha) { "复制暂存已改变，请重试。" }
                    val target = projectCopyOutput(operationId, file.id, file.kind, createParent = true)
                    check(!target.exists()) { "副本媒体身份冲突。" }
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    syncDirectory(requireNotNull(target.parentFile))
                }
                // Workspace lock stays held through SQLite COMMIT: recording/deletion cannot
                // change the source between our exact snapshot check and commit.
                WorkspaceStore(app, operationId).write(copiedWorkspace)
                transaction(db) {
                    check(captureProjectCopy(db, sourceProjectId, expectedRevision) == captured) { "原项目已改变，复制未提交。" }
                    installProjectCopy(db, captured, operationId)
                    db.update("project_copy_operations", ContentValues().apply {
                        put("status", "committed"); put("missing_raw_count", files.count { it.missing })
                    }, "operation_id=? AND status='preparing'", arrayOf(operationId))
                    requireNotNull(readProjectCopy(db, operationId))
                }
            } } }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = synchronized(lock) {
                activeProjectCopies.remove(importKey(operationId))
                // Open a healthy connection and read the durable receipt first. Unknown commit
                // outcome or close failure must preserve every file and its ownership journal.
                runCatching { cleanupProjectCopy(database(), operationId) }.exceptionOrNull()
            }
            if (cleanup != null) failure?.addSuppressed(cleanup)
        }
    }

    private fun installProjectCopy(db: SQLiteDatabase, captured: ProjectCopyCapture, operationId: String) {
        fun id(domain: String, old: String) = copiedId(operationId, domain, old)
        val sourceId = captured.snapshot.project.id
        val now = System.currentTimeMillis()
        db.insertOrThrow("projects", null, ContentValues().apply {
            put("project_id", operationId); put("title", (captured.snapshot.project.title.take(117) + " 副本"))
            put("goal", captured.snapshot.project.goal); put("created_at", now); put("updated_at", now); put("draft_revision", 1L)
            put("start_state_id", captured.snapshot.project.startStepId?.let { id("state", it) })
        })
        captured.videos.forEach { source -> db.insertOrThrow("sources", null, ContentValues().apply {
            val newId = id("video", source.sourceId)
            put("project_id", operationId); put("source_id", newId)
            put("source_json", sourceJson(source.copy(sourceId = newId, privateRelativePath = "sources/$newId.mp4")).toString())
        }) }
        captured.images.forEach { source -> db.insertOrThrow("image_sources", null, ContentValues().apply {
            val newId = id("image", source.sourceId)
            put("project_id", operationId); put("source_id", newId); put("mime", source.metadata.mime)
            put("source_json", imageSourceJson(source.copy(sourceId = newId, privateRelativePath = imageSourcePath(newId, source.metadata.mime))).toString())
        }) }
        for (table in PROJECT_COPY_TABLES.filterNot { it in setOf("sources", "image_sources") }) {
            captured.rows.getValue(table).forEach { original ->
                val row = ContentValues(original)
                fun remap(column: String, domain: String) { row.getAsString(column)?.let { row.put(column, id(domain, it)) } }
                row.put("project_id", operationId)
                when (table) {
                    "local_assets" -> { remap("asset_id", "asset"); row.put("relative_path", assetPath(operationId, row.getAsString("asset_id"), original.getAsString("relative_path").substringAfterLast('.'))) }
                    "states" -> {
                        remap("state_id", "state"); remap("input_asset_id", "asset"); remap("source_id", "video"); remap("image_source_id", "image")
                        remap("base_asset_id", "asset"); row.put("capture_id", "project-copy:$operationId:${row.getAsString("state_id")}")
                        if (row.getAsString("base_asset_id") != null) row.put("base_revision", 1L)
                    }
                    "package_step_origins" -> remap("state_id", "state") // External identity/digest/import stay unchanged.
                    "hotspots" -> { remap("hotspot_id", "hotspot"); remap("state_id", "state") }
                    "edges" -> { remap("edge_id", "edge"); remap("hotspot_id", "hotspot"); remap("from_state_id", "state"); remap("to_state_id", "state") }
                    "next_actions" -> { remap("action_id", "edge"); remap("from_state_id", "state"); remap("to_state_id", "state") }
                    "edge_transitions" -> { remap("edge_id", "edge"); remap("asset_id", "asset"); remap("source_id", "video"); row.put("review_id", "project-copy:$operationId:${row.getAsString("edge_id")}") }
                    "regions" -> { remap("region_id", "region"); remap("state_id", "state"); remap("base_asset_id", "asset"); remap("asset_id", "asset"); row.putNull("reviewed_at") }
                }
                db.insertOrThrow(table, null, row)
            }
        }
        captured.configJson?.let { encoded ->
            val old = DraftAiConfigCodec.decode(encoded, requireNotNull(captured.configRevision), captured.configNeedsRepair)
            // The same domain map handles valid and dangling IDs. Dangling references cannot
            // accidentally become references to a different existing object and are not dropped.
            val config = old.copy(boundRevision = 1L, needsRepair = true,
                visits = old.visits.map { visit -> com.tapscene.packageformat.RenderPlan.Visit(id("visit", visit.visitId), id("state", visit.stateId), visit.selectedEdgeId?.let { id("edge", it) }, visit.holdFrames) },
                effects = old.effects.map { entry -> val effect = entry.value
                    DraftAiEffect(id("effect", entry.id), com.tapscene.packageformat.RenderPlan.Effect(effect.type, id("visit", effect.visitId), effect.startFrame,
                        effect.durationFrames, effect.hotspotId?.let { id("hotspot", it) }, effect.regionId?.let { id("region", it) }, effect.text, effect.rect))
                })
            writeDraftAiConfig(db, operationId, config)
        }
        val actual = requireSnapshot(db, operationId)
        check(actual.steps.size == captured.snapshot.steps.size && actual.project.startStepId == captured.snapshot.project.startStepId?.let { id("state", it) })
        check(count(db, "editor_drafts", "project_id=?", operationId) == 0)
        check(requireSnapshot(db, sourceId) == captured.snapshot) { "原项目不能因复制改变。" }
    }

    private fun checkedProjectCopyInput(path: String): File = File(root, path).also { file ->
        check(file.canonicalFile == file.absoluteFile && !Files.isSymbolicLink(file.toPath())) { "原素材路径无效。" }
    }

    private fun projectCopyOutput(operationId: String, id: String, kind: String, createParent: Boolean = false): File {
        validId(operationId); validId(id)
        val path = when (kind) {
            "asset_png" -> assetPath(operationId, id)
            "asset_mp4" -> assetPath(operationId, id, "mp4")
            "video" -> "sources/$id.mp4"
            "image_png" -> imageSourcePath(id, "image/png")
            "image_jpeg" -> imageSourcePath(id, "image/jpeg")
            else -> error("复制文件日志种类无效。")
        }
        val file = File(root, path)
        check(file.canonicalFile == file.absoluteFile) { "复制文件路径无效。" }
        if (createParent) when {
            kind.startsWith("asset_") -> projectAssetDirectory(operationId)
            kind == "video" -> privateDirectory(requireNotNull(file.parentFile), root)
            else -> privateImageDirectory(id)
        }
        return file
    }

    /** Exact manifest only. No directory sweep and no cleanup based on an uncertain result. */
    private fun cleanupProjectCopy(db: SQLiteDatabase, operationId: String) {
        validId(operationId)
        check(importKey(operationId) !in activeProjectCopies)
        val receipt = readProjectCopy(db, operationId) ?: error("复制记录缺失，保留文件等待恢复。")
        val committed = receipt.status == "committed"
        if (!committed) check(count(db, "projects", "project_id=?", receipt.projectId) == 0) { "副本提交结果不一致，保留文件。" }
        val files = db.rawQuery("SELECT file_id,kind FROM project_copy_files WHERE operation_id=?", arrayOf(operationId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }
        val staging = File(root, "project-copy-staging/$operationId")
        check(staging.canonicalFile == staging.absoluteFile)
        files.forEach { (id, kind) ->
            val output = projectCopyOutput(operationId, id, kind)
            val part = File(staging, "$id.part"); check(part.canonicalFile == part.absoluteFile)
            deleteImportFile(part)
            if (!committed) {
                check(count(db, "local_assets", "relative_path=?", output.relativeTo(root).path) == 0)
                if (kind.startsWith("image_")) check(count(db, "image_sources", "source_id=?", id) == 0)
                if (kind == "video") check(count(db, "sources", "source_id=?", id) == 0)
                deleteImportFile(output)
                if (kind.startsWith("image_") && output.parentFile?.isDirectory == true) {
                    check(output.parentFile!!.delete()) { "副本原图目录清理未完成。" }
                    syncDirectory(requireNotNull(output.parentFile!!.parentFile))
                }
            }
        }
        if (staging.exists()) { check(staging.isDirectory && staging.delete()); syncDirectory(requireNotNull(staging.parentFile)) }
        val ownsWorkspace = db.rawQuery("SELECT workspace_owned FROM project_copy_operations WHERE operation_id=?", arrayOf(operationId)).use {
            check(it.moveToFirst()); it.getInt(0) == 1
        }
        if (!committed && ownsWorkspace) {
            // This reserved project was never exposed. These are its only possible AtomicFile names.
            for (suffix in listOf("", ".bak", ".new")) {
                val workspace = File(root, "project-media-$operationId.json$suffix")
                check(workspace.canonicalFile == workspace.absoluteFile); deleteImportFile(workspace)
            }
        }
        transaction(db) {
            db.delete("project_copy_files", "operation_id=?", arrayOf(operationId))
            db.update("project_copy_operations", ContentValues().apply { put("workspace_owned", 0) }, "operation_id=?", arrayOf(operationId))
        }
    }

    fun listProjects(): List<ProjectSummary> = access { db ->
        db.rawQuery("$SUMMARY_SQL ORDER BY p.updated_at DESC, p.project_id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(summary(cursor)) }
        }
    }

    fun readProject(projectId: String): ProjectSnapshot? = access { db -> snapshot(db, projectId) }

    fun screenshotCount(projectId: String): Int = access { db ->
        validId(projectId)
        count(db, "image_sources", "project_id=?", projectId)
    }

    /** The new project and first reviewed screenshot become visible in the same transaction. */
    suspend fun addReviewedScreenshot(projectId: String, input: ReviewedStepInput,
        newProjectTitle: String? = null): ProjectSnapshot {
        require(input.origin is StepOrigin.ImportedImage) { "请选择实际 PNG 或 JPEG 截图。" }
        val title = newProjectTitle?.let { text(it, "项目名称", 120) }
        return importReviewedStep(projectId, input, "截图", "", newId(), newProjectTitle = title)
    }

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

    /** Video sources remain workspace-owned. Unreferenced private screenshot sources are journal-cleaned. */
    fun deleteProject(projectId: String): ProjectDeletionResult = access { db ->
        // OCR is derived private source data, not a saved step or a sealed release. Revoke
        // live writers first so a cancelled analysis cannot recreate it after deletion.
        requireSnapshot(db, projectId)
        CandidateOcrStore(app).deleteProject(projectId)
        val result = transaction(db) {
            val current = requireSnapshot(db, projectId)
            val hotspots = current.steps.sumOf { it.hotspots.size }
            val imageSourceCount = count(db, "image_sources", "project_id=?", projectId)
            queueProjectAssets(db, projectId)
            db.execSQL("INSERT OR IGNORE INTO image_source_cleanup(project_id,source_id,mime) SELECT project_id,source_id,mime FROM image_sources WHERE project_id=?", arrayOf(projectId))
            db.update("projects", ContentValues().apply { putNull("start_state_id") },
                "project_id=?", arrayOf(projectId))
            db.delete("projects", "project_id=?", arrayOf(projectId))
            ProjectDeletionResult(current.steps.size, hotspots, edgeCount(current), current.steps.size + transitions(current).size + current.steps.sumOf { step -> step.regions.count { it.asset != null } } + imageSourceCount)
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

    /** Copy only a saved step's current safe pixels and authored definitions within this project.
     * operationId is the new state ID and must survive an uncertain result. Retrying it cannot
     * create another step, even if the source or project revision changed after the commit. */
    suspend fun copySavedStep(projectId: String, stepId: String, expectedRevision: Long,
        operationId: String): ProjectSnapshot {
        validId(projectId); validId(stepId); validId(operationId)
        require(operationId != stepId && expectedRevision > 0 && expectedRevision < Long.MAX_VALUE)
        val captureId = "copy:$stepId:$expectedRevision:$operationId"
        val (before, input) = access { db ->
            val current = requireSnapshot(db, projectId)
            if (hasSavedCopy(current, operationId, captureId)) current to null else {
                requireCopySource(db, current, stepId, expectedRevision)
                current to requireCurrentImageBase(db, current.steps.single { it.id == stepId }.safeImageBinding(current.project))
            }
        }
        if (input == null) return before
        val source = before.steps.single { it.id == stepId }
        val binding = source.safeImageBinding(before.project)
        require(source.asset.byteLength in 1..MAX_PNG_BYTES && source.asset.width > 0 && source.asset.height > 0 &&
            source.asset.width.toLong() * source.asset.height <= MAX_IMAGE_PIXELS) { "已保存画面尺寸或容量无效。" }
        require(root.usableSpace > source.asset.byteLength + 8L * 1024 * 1024) { "本机空间不足，无法复制步骤画面。" }
        val assetId = newId()
        access { db ->
            transaction(db) { db.insertOrThrow("asset_imports", null, ContentValues().apply {
                put("project_id", projectId); put("asset_id", assetId)
            }) }
            activeImports.add(importKey(assetId))
        }
        var committed = false
        var failure: Throwable? = null
        try {
            val staging = privateDirectory(File(root, "project-staging"), root)
            val operation = File(staging, assetId)
            check(operation.mkdir()) { "无法建立步骤复制暂存，请重试。" }
            val temporary = File(operation, "candidate.part")
            val owner = currentCoroutineContext()
            input.inputStream().use { from -> FileOutputStream(temporary).use { to ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    owner.ensureActive()
                    val size = from.read(buffer); if (size < 0) break
                    check(size > 0); copied += size
                    check(copied <= source.asset.byteLength) { "已保存画面在复制时改变。" }
                    to.write(buffer, 0, size)
                }
                check(copied == source.asset.byteLength) { "已保存画面不完整。" }
                to.fd.sync()
            } }
            SafeMediaWriterValidation.verifyPng(temporary, source.asset.width, source.asset.height, source.masks)
            check(sha256(temporary) == source.asset.sha256) { "安全画面副本校验失败，未添加步骤。" }
            owner.ensureActive()
            return withContext(NonCancellable) { synchronized(lock) {
                val db = database()
                val result = transaction(db) {
                    val current = requireSnapshot(db, projectId)
                    if (hasSavedCopy(current, operationId, captureId)) return@transaction current
                    requireCopySource(db, current, stepId, expectedRevision)
                    requireCurrentImageBase(db, binding)
                    check(temporary.length() == source.asset.byteLength && sha256(temporary) == source.asset.sha256) {
                        "安全画面副本在提交前改变。"
                    }
                    val directory = projectAssetDirectory(projectId)
                    val destination = File(directory, "$assetId.png")
                    check(!destination.exists()) { "步骤文件身份冲突，请重试。" }
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    syncDirectory(directory)
                    db.insertOrThrow("local_assets", null, ContentValues().apply {
                        put("project_id", projectId); put("asset_id", assetId); put("relative_path", assetPath(projectId, assetId))
                        put("sha256", source.asset.sha256); put("byte_length", source.asset.byteLength)
                        put("width", source.asset.width); put("height", source.asset.height)
                    })
                    // Also retain provenance hidden by a later safe-image redaction. Only the
                    // local owner changes; external package IDs and the original digest do not.
                    db.execSQL("""INSERT INTO package_step_origins
                        (project_id,state_id,import_id,source_state_id,source_asset_id,source_sha256,declared_kind)
                        SELECT project_id,?,import_id,source_state_id,source_asset_id,source_sha256,declared_kind
                        FROM package_step_origins WHERE project_id=? AND state_id=?""".trimIndent(),
                        arrayOf(operationId, projectId, stepId))
                    val origin = when (val saved = source.origin) {
                        is StepOrigin.Image -> StepOrigin.Image(SafeImageBinding(projectId, operationId, expectedRevision + 1,
                            assetId, source.asset.sha256, source.asset.width, source.asset.height))
                        is StepOrigin.PackageSafeImage -> saved.copy(localStepId = operationId)
                        else -> saved // Same-project video/image source reference, never a new raw import.
                    }
                    val sourceIndex = current.steps.indexOfFirst { it.id == stepId }
                    db.insertOrThrow("states", null, ContentValues().apply {
                        put("project_id", projectId); put("state_id", operationId); put("capture_id", captureId)
                        put("sort_order", sourceIndex + 1); put("title", source.title); put("description", source.description)
                        put("is_terminal", if (source.isTerminal) 1 else 0); put("input_asset_id", assetId)
                        putOrigin(origin); put("evidence_kind", source.evidenceKind); put("masks_json", masksJson(source.masks).toString())
                    })
                    current.steps.forEachIndexed { index, step ->
                        db.update("states", ContentValues().apply { put("sort_order", if (index > sourceIndex) index + 1 else index) },
                            "project_id=? AND state_id=?", arrayOf(projectId, step.id))
                    }
                    fun target(id: String?): String? = if (id == stepId) operationId else id
                    source.hotspots.forEach { hotspot ->
                        val id = newId()
                        db.insertOrThrow("hotspots", null, ContentValues().apply {
                            put("project_id", projectId); put("state_id", operationId); put("hotspot_id", id); put("label", hotspot.label)
                            put("rect_left", hotspot.rect.left); put("rect_top", hotspot.rect.top)
                            put("rect_right", hotspot.rect.right); put("rect_bottom", hotspot.rect.bottom)
                        })
                        db.insertOrThrow("edges", null, ContentValues().apply {
                            put("project_id", projectId); put("from_state_id", operationId); put("hotspot_id", id); put("edge_id", newId())
                            put("to_state_id", target(hotspot.targetStepId)); put("end_label", hotspot.endLabel)
                        })
                    }
                    source.nextAction?.let { insertNextAction(db, projectId, operationId,
                        it.copy(id = newId(), targetStepId = target(it.targetStepId), transition = null)) }
                    source.regions.forEach { region ->
                        val matches = region.matchesBase(source.asset)
                        db.insertOrThrow("regions", null, ContentValues().apply {
                            put("project_id", projectId); put("state_id", operationId); put("region_id", newId())
                            put("base_asset_id", if (matches) assetId else region.baseAssetId); put("base_sha256", region.baseSha256)
                            put("name", region.name); put("group_name", region.group)
                            put("x_px", region.bbox.x); put("y_px", region.bbox.y)
                            put("width_px", region.bbox.width); put("height_px", region.bbox.height)
                            put("source_width", region.sourceWidth); put("source_height", region.sourceHeight)
                            put("z_index", region.zIndex); put("anchor_x", region.anchorX); put("anchor_y", region.anchorY)
                            putNull("asset_id"); putNull("reviewed_at")
                        })
                    }
                    // No transition or review rows, incoming links, draft rows or AI edits.
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
                runCatching { cleanupImport(database(), projectId, assetId) }.exceptionOrNull()
            }
            // Cleanup consults durable metadata after a failed/uncertain commit, never the flag.
            if (cleanup != null && !committed) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun hasSavedCopy(current: ProjectSnapshot, operationId: String, captureId: String): Boolean {
        val saved = current.steps.singleOrNull { it.id == operationId } ?: return false
        check(saved.captureId == captureId) { "复制操作身份已被占用，请刷新后重新复制。" }
        val file = checkedAssetFile(current.project.id, saved.asset.privateRelativePath)
        check(file.isFile && file.length() == saved.asset.byteLength && sha256(file) == saved.asset.sha256) {
            "已复制的画面缺失或改变，请先处理该步骤。"
        }
        return true
    }

    private fun requireCopySource(db: SQLiteDatabase, current: ProjectSnapshot, stepId: String, expectedRevision: Long) {
        check(current.project.revision == expectedRevision) { "草稿已改变，请刷新后重新复制。" }
        val source = current.steps.singleOrNull { it.id == stepId } ?: error("待复制步骤已不存在。")
        if (source.evidenceKind == "imported") check(count(db, "package_step_origins",
            "project_id=? AND state_id=?", current.project.id, stepId) == 1) { "包画面来源记录不完整，无法复制。" }
        check(count(db, "editor_drafts", "project_id=? AND state_id=?", current.project.id, stepId) == 0) {
            "请先保存或放弃本步未保存的输入，再复制步骤。"
        }
        require(current.steps.size < ProjectLimits.MAX_STEPS) { "每个项目最多 40 个步骤。" }
        require(current.steps.all { it.hotspots.size <= ProjectLimits.MAX_HOTSPOTS_PER_STEP } &&
            edgeCount(current) + source.hotspots.size + (if (source.nextAction == null) 0 else 1) <= ProjectLimits.MAX_EDGES) {
            "复制后会超过每步 6 个热点或项目 80 条连线的限制。"
        }
        require(current.steps.all { it.regions.size <= ProjectLimits.MAX_REGIONS_PER_STEP } &&
            current.steps.sumOf { it.regions.size } + source.regions.size <= ProjectLimits.MAX_REGIONS) {
            "复制后会超过每步 12 个区域或项目 80 个区域的限制。"
        }
    }

    /** Replace only the reviewed safe output. Regions retain their bounds but lose crops/review;
     * sealed releases have separate copies and remain unchanged. Existing actions stay authored. */
    suspend fun replaceReviewedStep(projectId: String, stepId: String, expectedRevision: Long,
        input: ReviewedStepInput): ProjectSnapshot {
        val step = readProject(projectId)?.steps?.singleOrNull { it.id == stepId } ?: error("步骤已不存在。")
        return importReviewedStep(projectId, input, step.title, step.description, stepId, true, expectedRevision)
    }

    private suspend fun importReviewedStep(projectId: String, input: ReviewedStepInput, title: String,
        description: String, stepId: String, replacing: Boolean = false, expectedRevision: Long? = null,
        newProjectTitle: String? = null): ProjectSnapshot {
        validId(projectId)
        validId(stepId)
        require(input.origin !is StepOrigin.PackageSafeImage) { "包画面须经独立导入会话保存。" }
        val cleanTitle = text(title, "步骤标题", 120)
        val cleanDescription = text(description, "步骤说明", 4_000, allowEmpty = true)
        val originalImage = (input.origin as? StepOrigin.ImportedImage)?.source
        val imageSource = originalImage?.let { it.copy(privateRelativePath = imageSourcePath(it.sourceId, it.metadata.mime)) }
        val frozen = input.copy(masks = input.masks.toList(),
            origin = imageSource?.let { StepOrigin.ImportedImage(it) } ?: input.origin)
        require(newProjectTitle == null || imageSource != null && !replacing) { "新项目须包含已复核截图。" }
        require(imageSource == null || !replacing) { "截图导入只会添加新的步骤。" }
        require(frozen.captureId.isNotBlank() && frozen.captureId.length <= 160 &&
            frozen.captureId.none { it.isISOControl() }) { "步骤保存令牌无效。" }
        val alreadySaved = access { db ->
            val project = snapshot(db, projectId)
            if (project == null) {
                require(newProjectTitle != null) { "项目已不存在。" }
                return@access null
            }
            if (newProjectTitle != null) require(project.steps.any { it.captureId == frozen.captureId }) { "项目身份已被占用。" }
            if (imageSource != null) require(count(db, "image_sources", "project_id=?", projectId) < ProjectLimits.MAX_SCREENSHOTS ||
                project.steps.any { it.captureId == frozen.captureId }) { "每个项目最多 20 张原截图，请先删除不再需要的截图步骤。" }
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
        validateInput(input.copy(masks = frozen.masks))
        val imageBase = (frozen.origin as? StepOrigin.Image)?.base
        require(imageBase == null || replacing && imageBase.projectId == projectId && imageBase.stepId == stepId &&
            imageBase.revision == expectedRevision) { "安全画面只可追加遮挡并替换所绑定的当前步骤。" }
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
                if (imageSource != null) db.insertOrThrow("image_source_imports", null, ContentValues().apply {
                    put("asset_id", assetId); put("source_id", imageSource.sourceId); put("mime", imageSource.metadata.mime)
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
            if (imageBase != null) {
                // Retain only this operation's verified copy until commit. The historical ID is
                // provenance, not a readable dependency after the superseded asset is cleaned.
                val baseFile = access { db -> requireCurrentImageBase(db, imageBase) }
                val baseCopy = File(operationDirectory, "base.png")
                baseFile.copyTo(baseCopy)
                check(sha256(baseCopy) == imageBase.sha256) { "安全底图已改变，请重新打开。" }
                SafeMediaWriterValidation.verifyPngRedaction(temporary, baseCopy, frozen.width, frozen.height, frozen.masks)
                check(baseCopy.delete()) { "安全底图暂存清理失败，请重试。" }
            }
            if (imageSource != null && originalImage != null) {
                val importer = ScreenshotImporter(app)
                val sourceFile = importer.checkedSourceFile(originalImage)
                val imageDirectory = privateImageDirectory(imageSource.sourceId)
                val sourceCopy = File(imageDirectory, if (imageSource.metadata.mime == "image/png") "original.png" else "original.jpg")
                check(!sourceCopy.exists()) { "截图来源身份冲突，请重新选择。" }
                copyImageSource(sourceFile, sourceCopy, imageSource)
                val normalized = importer.read(imageSource)
                try { verifyScreenshotPixels(temporary, normalized, frozen.masks) }
                finally { normalized.recycle() }
            }
            // Verify the actual stored bytes again, rather than trusting candidate metadata.
            check(sha256(temporary) == frozen.sha256.lowercase()) { "步骤画面校验失败，请重新复核。" }
            owner.ensureActive()
            return withContext(NonCancellable) {
                synchronized(lock) {
                    val db = database()
                    val saved = transaction(db) {
                        if (newProjectTitle != null && snapshot(db, projectId) == null) {
                            val now = System.currentTimeMillis()
                            db.insertOrThrow("projects", null, ContentValues().apply {
                                put("project_id", projectId); put("title", newProjectTitle); put("goal", "")
                                put("created_at", now); put("updated_at", now); put("draft_revision", 1L); putNull("start_state_id")
                            })
                        }
                        val current = requireSnapshot(db, projectId)
                        require(!replacing || current.steps.none { it.captureId == frozen.captureId && it.id != stepId }) {
                            "复核令牌属于另一个步骤。"
                        }
                        if (hasMatchingCapture(current, frozen)) return@transaction current
                        val previous = if (replacing) current.steps.singleOrNull { it.id == stepId } ?: error("待替换步骤已不存在。") else null
                        check(!replacing || current.project.revision == expectedRevision) { "草稿已改变，底图未替换。" }
                        require(replacing || current.steps.none { it.id == stepId }) { "这个步骤已经保存，请刷新后继续。" }
                        require(replacing || current.steps.size < ProjectLimits.MAX_STEPS) { "每个项目最多 40 个步骤。" }
                        if (imageBase != null) requireCurrentImageBase(db, imageBase)
                        if (imageSource != null) {
                            require(count(db, "image_sources", "project_id=?", projectId) < ProjectLimits.MAX_SCREENSHOTS) {
                                "每个项目最多 20 张原截图，请先删除不再需要的截图步骤。"
                            }
                            check(sha256(ScreenshotImporter(app).checkedSourceFile(imageSource)) == imageSource.metadata.sha256) {
                                "截图原图已改变，请重新选择。"
                            }
                            db.insertOrThrow("image_sources", null, ContentValues().apply {
                                put("project_id", projectId); put("source_id", imageSource.sourceId)
                                put("mime", imageSource.metadata.mime); put("source_json", imageSourceJson(imageSource).toString())
                            })
                        }
                        frozen.videoOrigin?.source?.let { source ->
                            val existingSource = readSource(db, projectId, source.sourceId)
                            check(existingSource == null || existingSource == source) {
                                "素材来源记录已改变，请重新取帧。"
                            }
                            if (existingSource == null) db.insertOrThrow("sources", null, ContentValues().apply {
                                put("project_id", projectId); put("source_id", source.sourceId)
                                put("source_json", sourceJson(source).toString())
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
                            put("input_asset_id", assetId)
                            putOrigin(frozen.origin)
                            put("evidence_kind", when (frozen.origin) {
                                is StepOrigin.ImportedImage -> "authored"
                                is StepOrigin.Image -> requireNotNull(previous).evidenceKind
                                is StepOrigin.VideoFrame -> "recorded"
                                is StepOrigin.PackageSafeImage -> error("包画面须经独立导入会话保存。")
                            })
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
                runCatching { cleanupImport(database(), projectId, assetId) }.exceptionOrNull()
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
                    val db = database()
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
                    cleanupImport(database(), projectId, assetId, "mp4")
                    cleanupPending(database())
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
            StepDeletionResult(requireSnapshot(db, projectId), impact, if (step.origin is StepOrigin.ImportedImage) 2 else 1)
        }
        cleanupPending(db)
        result.copy(pendingAssetCleanupCount = pendingCleanupCount(db, projectId, result.pendingAssetCleanupCount))
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
    fun copyReleaseInputs(projectId: String, expectedRevision: Long, destination: File, onPlanSnapshot: ((DraftAiConfig?) -> Unit)? = null): ProjectSnapshot = access { db ->
        val current = requireSnapshot(db, projectId)
        check(current.project.revision == expectedRevision) { "草稿修订已改变，请重新检查后生成。" }
        readDraftAiConfig(db, projectId)?.let { config ->
            check(!config.needsRepair && config.boundRevision == expectedRevision && DraftPlanProjection.issues(current, config).isEmpty()) {
                "动画计划仍待核对或修复，请先在项目的动画计划中检查并保存。"
            }
        }
        onPlanSnapshot?.invoke(readDraftAiConfig(db, projectId))
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
                val db = database()
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
                runCatching { cleanupImport(database(), projectId, assetId); cleanupPending(database()) }.exceptionOrNull()
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

    /** All callers hold lock, including import cleanup after a failed commit. */
    private fun database(): SQLiteDatabase {
        databaseCloseFailure?.let { throw IllegalStateException("数据库未能安全关闭，请保留本机数据并重新打开应用。", it) }
        return helper.writableDatabase
    }

    private fun <T> access(block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        val db = database()
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
        db.execSQL("UPDATE draft_ai_configs SET needs_repair=1 WHERE project_id=?", arrayOf(projectId))
        db.execSQL("UPDATE projects SET draft_revision=draft_revision+1, updated_at=? WHERE project_id=?",
            arrayOf(System.currentTimeMillis(), projectId))
    }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        var failure: Throwable? = null
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { db.endTransaction() }
            catch (endFailure: Throwable) {
                // Android can pop its transaction stack before a deferred-FK COMMIT fails,
                // leaving the native transaction open in the reusable pool. Do not let later
                // reads or file cleanup treat those uncommitted rows as durable. No raw SQL
                // ROLLBACK: SQLiteSession would reinterpret it against the already empty stack.
                databaseCloseFailure = endFailure
                try {
                    helper.close()
                    databaseCloseFailure = null // A later access opens a fresh connection.
                } catch (closeFailure: Throwable) { endFailure.addSuppressed(closeFailure) }
                failure?.addSuppressed(endFailure) ?: throw endFailure
            }
        }
    }

    private fun snapshot(db: SQLiteDatabase, projectId: String): ProjectSnapshot? {
        validId(projectId)
        val project = db.rawQuery("$SUMMARY_SQL WHERE p.project_id=?", arrayOf(projectId)).use {
            if (it.moveToFirst()) summary(it) else null
        } ?: return null
        val steps = db.rawQuery("""
            SELECT s.*, a.relative_path, a.sha256, a.byte_length, a.width, a.height, src.source_json, img.source_json AS image_source_json, pkg.source_state_id AS package_state_id, pkg.source_asset_id AS package_asset_id, pkg.source_sha256 AS package_sha256, pkg.declared_kind AS package_declared_kind
            FROM states s JOIN local_assets a ON a.project_id=s.project_id AND a.asset_id=s.input_asset_id
            LEFT JOIN sources src ON src.project_id=s.project_id AND src.source_id=s.source_id
            LEFT JOIN image_sources img ON img.project_id=s.project_id AND img.source_id=s.image_source_id
            LEFT JOIN package_step_origins pkg ON pkg.project_id=s.project_id AND pkg.state_id=s.state_id AND pkg.import_id=s.package_import_id
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
                        origin = readOrigin(cursor), evidenceKind = cursor.string("evidence_kind"),
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
        db.execSQL("""INSERT OR IGNORE INTO image_source_cleanup(project_id,source_id,mime)
            SELECT project_id,source_id,mime FROM image_sources WHERE project_id=?
            AND NOT EXISTS(SELECT 1 FROM states s WHERE s.project_id=image_sources.project_id AND s.image_source_id=image_sources.source_id)
        """.trimIndent(), arrayOf(projectId))
        db.execSQL("""DELETE FROM image_sources WHERE project_id=?
            AND NOT EXISTS(SELECT 1 FROM states s WHERE s.project_id=image_sources.project_id AND s.image_source_id=image_sources.source_id)
        """.trimIndent(), arrayOf(projectId))
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
        val copies = db.rawQuery("SELECT operation_id FROM project_copy_operations WHERE workspace_owned=1 OR EXISTS(SELECT 1 FROM project_copy_files f WHERE f.operation_id=project_copy_operations.operation_id)", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        copies.filterNot { importKey(it) in activeProjectCopies }.forEach { runCatching { cleanupProjectCopy(db, it) } }
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
        val baseCopy = File(directory, "base.png")
        check(baseCopy.canonicalFile == baseCopy.absoluteFile) { "安全底图暂存路径不受支持。" }
        deleteImportFile(baseCopy)
        if (directory.exists()) {
            check(directory.isDirectory && directory.delete()) { "步骤暂存目录清理失败。" }
            syncDirectory(stagingRoot)
        }
        val path = assetPath(projectId, assetId, extension)
        if (count(db, "local_assets", "relative_path=?", path) == 0) {
            deleteImportFile(checkedAssetFile(projectId, path))
        }
        if (extension == "png") {
            val image = db.rawQuery("SELECT source_id,mime FROM image_source_imports WHERE asset_id=?", arrayOf(assetId)).use {
                if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
            }
            if (image != null) {
                if (count(db, "image_sources", "source_id=?", image.first) == 0) deletePrivateImage(image.first, image.second)
                db.delete("image_source_imports", "asset_id=?", arrayOf(assetId))
            }
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
        runCatching {
            val images = db.rawQuery("SELECT project_id,source_id,mime FROM image_source_cleanup", null).use {
                buildList { while (it.moveToNext()) add(Triple(it.getString(0), it.getString(1), it.getString(2))) }
            }
            images.forEach { (projectId, sourceId, mime) -> runCatching {
                if (count(db, "image_sources", "source_id=?", sourceId) == 0 &&
                    count(db, "image_source_imports", "source_id=?", sourceId) == 0) {
                    deletePrivateImage(sourceId, mime)
                    db.delete("image_source_cleanup", "project_id=? AND source_id=?", arrayOf(projectId, sourceId))
                }
            } }
        }
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
        runCatching { count(db, "asset_cleanup", "project_id=?", projectId) +
            count(db, "image_source_cleanup", "project_id=?", projectId) }.getOrDefault(fallback)

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
            step.asset.height == input.height && step.origin == input.origin && step.masks == input.masks) {
            "此保存令牌对应的画面已改变，请重新生成并复核。"
        }
        val file = checkedAssetFile(snapshot.project.id, step.asset.privateRelativePath)
        check(file.isFile && file.length() == step.asset.byteLength && sha256(file) == step.asset.sha256) {
            "已保存画面缺失或改变，请重新生成并复核。"
        }
        return true
    }

    private fun validateInput(input: ReviewedStepInput) {
        require(SHA.matches(input.sha256)) { "已复核画面的摘要无效。" }
        require(input.width > 0 && input.height > 0 && input.width.toLong() * input.height <= MAX_IMAGE_PIXELS) {
            "步骤画面尺寸无效或超过 1200 万像素。"
        }
        require(input.masks.size <= 20) { "每个步骤最多 20 块遮挡。" }
        val sourceFile = when (val origin = input.origin) {
            is StepOrigin.PackageSafeImage -> error("包画面不能冒充已复核候选。")
            is StepOrigin.VideoFrame -> {
                val source = origin.source
                require(origin.timePrecisionUs > 0 && origin.frameTimeUs in 0..source.metadata.durationUs) {
                    "实际取帧时间或时间精度无效。"
                }
                validId(source.sourceId)
                val file = File(root, source.privateRelativePath)
                require(source.privateRelativePath == "sources/${source.sourceId}.mp4" &&
                    file.canonicalFile == file.absoluteFile && file.isFile) { "本机原素材已缺失，请重新导入。" }
                val metadata = source.metadata
                require(metadata.byteLength > 0 && file.length() == metadata.byteLength && SHA.matches(metadata.sha256) &&
                    metadata.width > 0 && metadata.height > 0 && metadata.durationUs > 0 &&
                    metadata.rotationDeg in setOf(0, 90, 180, 270) &&
                    metadata.pixelWidthHeightRatio.isFinite() && metadata.pixelWidthHeightRatio > 0f) { "原素材记录无效。" }
                file
            }
            is StepOrigin.ImportedImage -> {
                val image = origin.source
                require(input.width == image.metadata.outputWidth && input.height == image.metadata.outputHeight &&
                    minOf(input.width, input.height) <= 1080 && maxOf(input.width, input.height) <= 2400) { "截图输出尺寸无效。" }
                ScreenshotImporter(app).checkedSourceFile(image)
            }
            is StepOrigin.Image -> {
                require(input.width == origin.base.width && input.height == origin.base.height && input.masks.isNotEmpty()) {
                    "安全画面追加遮挡须保持底图尺寸并至少添加一块遮挡。"
                }
                access { db -> requireCurrentImageBase(db, origin.base) }
            }
        }
        val candidate = input.file.canonicalFile
        require(candidate.isFile && candidate.path.startsWith(root.path + File.separator) &&
            candidate != sourceFile.canonicalFile && candidate.length() in 1..MAX_PNG_BYTES) {
            "只能保存本机生成并已复核的 PNG 候选。"
        }
    }

    /** Caller owns this bitmap. Reading a latest safe base does not assert author review. */
    suspend fun readSafeImageBase(binding: SafeImageBinding): Bitmap {
        val file = access { db -> requireCurrentImageBase(db, binding) }
        SafeMediaWriterValidation.verifyPng(file, binding.width, binding.height, emptyList())
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
        }) ?: error("已保存安全画面无法读取。")
        try {
            check(bitmap.width == binding.width && bitmap.height == binding.height) { "安全底图尺寸已改变。" }
            access { db -> requireCurrentImageBase(db, binding) }
            currentCoroutineContext().ensureActive()
            return bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    private fun requireCurrentImageBase(db: SQLiteDatabase, binding: SafeImageBinding): File {
        val project = requireSnapshot(db, binding.projectId)
        val step = project.steps.singleOrNull { it.id == binding.stepId } ?: error("步骤已不存在。")
        check(binding.matches(project.project, step)) { "安全底图或草稿已改变，请返回后重新打开。" }
        val file = checkedAssetFile(binding.projectId, step.asset.privateRelativePath)
        check(file.isFile && file.length() == step.asset.byteLength && sha256(file) == binding.sha256) {
            "已保存安全画面缺失或改变，原步骤未替换。"
        }
        return file
    }

    private fun ContentValues.putOrigin(origin: StepOrigin) {
        listOf("source_id", "frame_pts_us", "time_precision_us", "base_asset_id", "base_sha256",
            "base_revision", "base_width", "base_height", "image_source_id", "package_import_id").forEach { putNull(it) }
        when (origin) {
            is StepOrigin.PackageSafeImage -> { put("origin_kind", "packageImage"); put("package_import_id", origin.importId) }
            is StepOrigin.VideoFrame -> {
                put("origin_kind", "videoFrame"); put("source_id", origin.source.sourceId)
                put("frame_pts_us", origin.frameTimeUs); put("time_precision_us", origin.timePrecisionUs)
            }
            is StepOrigin.ImportedImage -> {
                put("origin_kind", "image"); put("image_source_id", origin.source.sourceId)
            }
            is StepOrigin.Image -> {
                val base = origin.base
                put("origin_kind", "image"); put("base_asset_id", base.assetId); put("base_sha256", base.sha256)
                put("base_revision", base.revision); put("base_width", base.width); put("base_height", base.height)
            }
        }
    }

    private fun readOrigin(cursor: Cursor): StepOrigin = when (cursor.string("origin_kind")) {
        "packageImage" -> StepOrigin.PackageSafeImage(cursor.string("package_import_id"), cursor.string("package_state_id"),
            cursor.string("package_asset_id"), cursor.string("package_sha256"), cursor.string("package_declared_kind"),
            localStepId = cursor.string("state_id"))
        "videoFrame" -> StepOrigin.VideoFrame(parseSource(JSONObject(cursor.string("source_json"))),
            cursor.long("frame_pts_us"), cursor.long("time_precision_us"))
        "image" -> if (!cursor.isNull(cursor.getColumnIndexOrThrow("image_source_id")))
            StepOrigin.ImportedImage(parseImageSource(JSONObject(cursor.string("image_source_json"))))
        else StepOrigin.Image(SafeImageBinding(cursor.string("project_id"), cursor.string("state_id"),
            cursor.long("base_revision"), cursor.string("base_asset_id"), cursor.string("base_sha256"),
            cursor.int("base_width"), cursor.int("base_height")))
        else -> error("步骤来源种类无效，请保留本机数据。")
    }

    private fun imageSourceJson(source: ImportedImageSource) = JSONObject().apply {
        put("id", source.sourceId); put("path", source.privateRelativePath); put("name", source.displayName)
        put("mime", source.metadata.mime); put("bytes", source.metadata.byteLength); put("sha256", source.metadata.sha256)
        put("width", source.metadata.width); put("height", source.metadata.height); put("orientation", source.metadata.orientation)
        put("outputWidth", source.metadata.outputWidth); put("outputHeight", source.metadata.outputHeight)
    }

    private fun parseImageSource(json: JSONObject) = ImportedImageSource(json.getString("id"), json.getString("path"),
        json.getString("name"), ImageSourceMetadata(json.getString("mime"), json.getLong("bytes"), json.getString("sha256"),
            json.getInt("width"), json.getInt("height"), json.getInt("orientation"),
            json.getInt("outputWidth"), json.getInt("outputHeight")))

    private fun imageSourcePath(sourceId: String, mime: String): String {
        validId(sourceId)
        require(mime == "image/png" || mime == "image/jpeg") { "截图格式无效。" }
        return "image-sources/$sourceId/original.${if (mime == "image/png") "png" else "jpg"}"
    }

    private fun privateImageDirectory(sourceId: String): File {
        validId(sourceId)
        val images = privateDirectory(File(root, "image-sources"), root)
        return privateDirectory(File(images, sourceId), images)
    }

    private fun deletePrivateImage(sourceId: String, mime: String) {
        val file = File(root, imageSourcePath(sourceId, mime))
        val directory = requireNotNull(file.parentFile)
        check(file.canonicalFile == file.absoluteFile && directory.canonicalFile == directory.absoluteFile &&
            directory.parentFile?.canonicalFile == File(root, "image-sources").absoluteFile) { "原截图清理路径无效。" }
        deleteImportFile(file)
        deleteImportFile(File(directory, "original.part"))
        if (directory.exists()) check(directory.isDirectory && directory.delete()) { "原截图暂存清理失败，将重试。" }
    }

    private suspend fun copyImageSource(input: File, output: File, source: ImportedImageSource) {
        val part = File(output.parentFile, "original.part")
        check(part.canonicalFile == part.absoluteFile && !part.exists()) { "原截图暂存冲突。" }
        val owner = currentCoroutineContext()
        var bytes = 0L
        input.inputStream().use { from -> FileOutputStream(part).use { to ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                owner.ensureActive()
                val size = from.read(buffer, 0, minOf(buffer.size.toLong(), 10L * 1024 * 1024 - bytes + 1).toInt())
                if (size < 0) break
                check(size > 0) { "截图复制中断。" }; bytes += size
                require(bytes <= 10L * 1024 * 1024) { "截图超过 10 MiB。" }
                to.write(buffer, 0, size)
            }
            to.fd.sync()
        } }
        check(bytes == source.metadata.byteLength && sha256(part) == source.metadata.sha256) { "原截图已改变，请重新选择。" }
        owner.ensureActive()
        Files.move(part.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(requireNotNull(output.parentFile))
    }

    /** Bind the reviewed PNG to normalized source pixels, never to compressed input bytes. */
    private suspend fun verifyScreenshotPixels(file: File, source: Bitmap, masks: List<OpaqueMask>) {
        val output = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
        }) ?: error("截图输出无法读取。")
        try {
            check(output.width == source.width && output.height == source.height) { "截图输出尺寸改变。" }
            val original = IntArray(source.width); val actual = IntArray(source.width)
            val rectangles = masks.map { it.toPixelRect(source.width, source.height) }
            for (y in 0 until source.height) {
                currentCoroutineContext().ensureActive()
                source.getPixels(original, 0, source.width, 0, y, source.width, 1)
                output.getPixels(actual, 0, source.width, 0, y, source.width, 1)
                val rowMasks = rectangles.filter { y in it.top until it.bottom }
                for (x in original.indices) check(actual[x] == if (rowMasks.any { x in it.left until it.right }) android.graphics.Color.BLACK else original[x]) {
                    "截图输出与本次原图不一致，请重新生成并复核。"
                }
            }
        } finally { output.recycle() }
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

    internal class Database(
        context: Context, path: String,
        private val migrationCheckpoint: ((String) -> Unit)? = null,
    ) : SQLiteOpenHelper(context, path, null, 9) {
        override fun onConfigure(db: SQLiteDatabase) {
            // SQLiteOpenHelper calls this BEFORE its upgrade transaction. Changing a PRAGMA
            // inside onUpgrade is ineffective and dropping states would cascade child rows.
            db.setForeignKeyConstraintsEnabled(db.version !in 1..7)
        }
        override fun onOpen(db: SQLiteDatabase) {
            // Runs after successful upgrade commit, but before the helper exposes the handle.
            // Failure here rejects opening; migration validation itself must happen pre-commit.
            db.setForeignKeyConstraintsEnabled(true)
            check(foreignKeys(db) == 1) { "项目关系保护未能启用，请保留本机数据。" }
        }
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
            createImageSources(db)
            createStates(db, "states")
            createAiImportTables(db)
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
            createProjectCopies(db)
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
            check((oldVersion in 1..7 && newVersion in 8..9 && foreignKeys(db) == 0) ||
                (oldVersion == 8 && newVersion == 9 && foreignKeys(db) == 1)) {
                "项目数据库需要安全迁移；请保留现有本机数据。"
            }
            if (oldVersion < 2) createNextActions(db)
            if (oldVersion < 3) createTransitions(db)
            if (oldVersion < 4) createRegions(db)
            if (oldVersion < 5) createEditorDrafts(db)
            if (oldVersion < 7) createImageSources(db)
            if (oldVersion < 8) {
                createAiImportTables(db)
                migrateOrigins(db, oldVersion)
            }
            if (newVersion >= 9) createProjectCopies(db)
        }

        private fun createProjectCopies(db: SQLiteDatabase) {
            // No project FK: receipts must survive deletion and prevent retry resurrection.
            db.execSQL("""CREATE TABLE project_copy_operations (
                operation_id TEXT PRIMARY KEY NOT NULL, source_project_id TEXT NOT NULL,
                source_revision INTEGER NOT NULL CHECK(source_revision>0), project_id TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL CHECK(status IN ('preparing','committed','aborted')),
                missing_raw_count INTEGER NOT NULL DEFAULT 0 CHECK(missing_raw_count>=0),
                acknowledged INTEGER NOT NULL DEFAULT 0 CHECK(acknowledged IN (0,1)), created_at INTEGER NOT NULL,
                workspace_owned INTEGER NOT NULL DEFAULT 0 CHECK(workspace_owned IN (0,1)),
                CHECK(acknowledged=0 OR status='committed'), CHECK(project_id=operation_id AND source_project_id!=project_id)
            )""")
            db.execSQL("""CREATE TABLE project_copy_files (
                operation_id TEXT NOT NULL, file_id TEXT NOT NULL,
                kind TEXT NOT NULL CHECK(kind IN ('asset_png','asset_mp4','video','image_png','image_jpeg')),
                PRIMARY KEY(operation_id,file_id), UNIQUE(file_id),
                FOREIGN KEY(operation_id) REFERENCES project_copy_operations(operation_id)
            )""")
        }

        private fun createImageSources(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE image_sources (
                project_id TEXT NOT NULL, source_id TEXT NOT NULL UNIQUE, mime TEXT NOT NULL CHECK(mime IN ('image/png','image/jpeg')),
                source_json TEXT NOT NULL, PRIMARY KEY(project_id,source_id),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )""")
            db.execSQL("CREATE TABLE image_source_imports(asset_id TEXT PRIMARY KEY NOT NULL, source_id TEXT UNIQUE NOT NULL, mime TEXT NOT NULL)")
            db.execSQL("CREATE TABLE image_source_cleanup(project_id TEXT NOT NULL, source_id TEXT PRIMARY KEY NOT NULL, mime TEXT NOT NULL)")
        }

        private fun createAiImportTables(db: SQLiteDatabase) {
            // The deferred circular relationship makes a package state and its exact import
            // provenance atomic. Provenance remains after a later safe-image replacement.
            db.execSQL("""CREATE TABLE package_step_origins (
                project_id TEXT NOT NULL, state_id TEXT NOT NULL, import_id TEXT NOT NULL CHECK(length(import_id)>0),
                source_state_id TEXT NOT NULL CHECK(length(source_state_id)>0),
                source_asset_id TEXT NOT NULL CHECK(length(source_asset_id)>0),
                source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND length(CAST(source_sha256 AS BLOB))=64 AND source_sha256 NOT GLOB '*[^0-9a-f]*'),
                declared_kind TEXT NOT NULL CHECK(declared_kind IN ('recorded','authored','imported')),
                PRIMARY KEY(project_id,state_id), UNIQUE(project_id,state_id,import_id),
                FOREIGN KEY(project_id,state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
            )""")
            // The receipt outlives project deletion, preventing retry from recreating it.
            db.execSQL("""CREATE TABLE ai_import_sessions (
                session_id TEXT PRIMARY KEY NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('preparing','ready','committed','cancelled','failed')),
                project_id TEXT NOT NULL UNIQUE,
                input_sha TEXT CHECK(input_sha IS NULL OR (length(input_sha)=64 AND length(CAST(input_sha AS BLOB))=64 AND input_sha NOT GLOB '*[^0-9a-f]*')),
                preview_digest TEXT CHECK(preview_digest IS NULL OR (length(preview_digest)=64 AND length(CAST(preview_digest AS BLOB))=64 AND preview_digest NOT GLOB '*[^0-9a-f]*')),
                prepared_json TEXT CHECK(prepared_json IS NULL OR length(CAST(prepared_json AS BLOB)) BETWEEN 1 AND 2097152),
                created_at INTEGER NOT NULL,
                CHECK(state NOT IN ('ready','committed') OR (input_sha IS NOT NULL AND preview_digest IS NOT NULL AND prepared_json IS NOT NULL))
            )""")
            db.execSQL("""CREATE TABLE draft_ai_configs (
                project_id TEXT PRIMARY KEY NOT NULL, bound_revision INTEGER NOT NULL CHECK(bound_revision>0),
                needs_repair INTEGER NOT NULL CHECK(needs_repair IN (0,1)),
                config_json TEXT NOT NULL CHECK(length(CAST(config_json AS BLOB)) BETWEEN 1 AND 524288),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )""")
        }

        private fun foreignKeys(db: SQLiteDatabase): Int = db.rawQuery("PRAGMA foreign_keys", null).use {
            check(it.moveToFirst()); it.getInt(0)
        }

        private fun createStates(db: SQLiteDatabase, table: String) {
            check(table == "states" || table == "states_v8")
            db.execSQL(STATES_SQL.replace("CREATE TABLE states (", "CREATE TABLE $table ("))
        }

        private fun migrateOrigins(db: SQLiteDatabase, oldVersion: Int) {
            check(db.inTransaction() && foreignKeys(db) == 0) { "项目迁移保护未就绪。" }
            // Do not silently lose user-installed schema objects on a table rebuild.
            db.rawQuery("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('index','trigger','view') AND sql IS NOT NULL", null).use { rows ->
                while (rows.moveToNext()) {
                    check(rows.getString(0) == "index" && (rows.getString(2) != "states" ||
                        rows.getString(3) in setOf("CREATE INDEX states_order ON states(project_id,sort_order)",
                            "CREATE INDEX states_source ON states(source_id)"))) {
                        "项目包含额外数据库对象，无法安全迁移；请保留本机数据。"
                    }
                }
            }
            val names = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }
            }
            val unchanged = names.filter { it != "states" }.associateWith { fingerprint(db, it) }
            val schema = unaffectedSchema(db)
            val preservedColumns = when {
                oldVersion < 6 -> LEGACY_STATE_COLUMNS
                oldVersion < 7 -> V6_STATE_COLUMNS
                else -> V7_STATE_COLUMNS
            }
            val states = fingerprint(db, "states", preservedColumns)
            createStates(db, "states_v8")
            if (oldVersion < 6) db.execSQL("INSERT INTO states_v8 ($LEGACY_STATE_COLUMNS,origin_kind) SELECT $LEGACY_STATE_COLUMNS,'videoFrame' FROM states")
            else db.execSQL("INSERT INTO states_v8 ($preservedColumns) SELECT $preservedColumns FROM states")
            migrationCheckpoint?.invoke("copy")
            check(fingerprint(db, "states_v8", preservedColumns) == states) { "步骤迁移核对失败。" }
            db.execSQL("DROP TABLE states")
            migrationCheckpoint?.invoke("drop")
            db.execSQL("ALTER TABLE states_v8 RENAME TO states")
            migrationCheckpoint?.invoke("rename")
            db.execSQL("CREATE INDEX states_order ON states(project_id,sort_order)")
            db.execSQL("CREATE INDEX states_source ON states(source_id)")
            migrationCheckpoint?.invoke("indexes")
            check(fingerprint(db, "states", preservedColumns) == states &&
                unchanged.all { (table, before) -> fingerprint(db, table) == before } && unaffectedSchema(db) == schema) {
                "项目关系迁移核对失败，原数据将保留。"
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "项目关系无效，原数据将保留。" } }
            db.rawQuery("PRAGMA integrity_check", null).use { rows ->
                check(rows.moveToFirst() && rows.getString(0) == "ok" && !rows.moveToNext()) { "项目完整性核对失败。" }
            }
        }

        private fun unaffectedSchema(db: SQLiteDatabase): List<List<String?>> = db.rawQuery(
            "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name!='states' AND name NOT LIKE 'sqlite_%' ORDER BY type,name", null,
        ).use { rows -> buildList {
            while (rows.moveToNext()) add((0..3).map { if (rows.isNull(it)) null else rows.getString(it) })
        } }

        /** Stream all exact typed values, ordered by every projected column. Includes drafts,
         * self-links, NULL targets, assets and journals; empty FK checks alone miss cascaded rows. */
        private fun fingerprint(db: SQLiteDatabase, table: String, columns: String = "*"): String {
            check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            val names = db.rawQuery("SELECT $columns FROM \"$table\" LIMIT 0", null).use { it.columnNames }
            val order = names.joinToString(",") { "\"$it\"" }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            db.rawQuery("SELECT $columns FROM \"$table\" ORDER BY $order", null).use { rows ->
                while (rows.moveToNext()) {
                    count++
                    for (index in 0 until rows.columnCount) {
                        val type = rows.getType(index)
                        digest.update(type.toByte())
                        val bytes = when (type) {
                            Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                            Cursor.FIELD_TYPE_BLOB -> rows.getBlob(index)
                            Cursor.FIELD_TYPE_FLOAT -> java.lang.Double.toHexString(rows.getDouble(index)).toByteArray(Charsets.UTF_8)
                            else -> rows.getString(index).toByteArray(Charsets.UTF_8)
                        }
                        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                        digest.update(bytes)
                    }
                }
            }
            return "$count:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        companion object {
            private const val LEGACY_STATE_COLUMNS = "project_id,state_id,capture_id,sort_order,title,description,is_terminal,source_id,input_asset_id,frame_pts_us,time_precision_us,masks_json"
            private const val V6_STATE_COLUMNS = "$LEGACY_STATE_COLUMNS,origin_kind,base_asset_id,base_sha256,base_revision,base_width,base_height"
            private const val V7_STATE_COLUMNS = "$V6_STATE_COLUMNS,image_source_id,evidence_kind"
            internal val STATES_SQL = """CREATE TABLE states (
                project_id TEXT NOT NULL, state_id TEXT NOT NULL, capture_id TEXT NOT NULL,
                sort_order INTEGER NOT NULL CHECK(sort_order>=0),
                title TEXT NOT NULL, description TEXT NOT NULL, is_terminal INTEGER NOT NULL CHECK(is_terminal IN (0,1)),
                source_id TEXT, input_asset_id TEXT NOT NULL, frame_pts_us INTEGER CHECK(frame_pts_us>=0),
                time_precision_us INTEGER CHECK(time_precision_us>0), masks_json TEXT NOT NULL,
                origin_kind TEXT NOT NULL CHECK(origin_kind IN ('videoFrame','image','packageImage')),
                base_asset_id TEXT, base_sha256 TEXT, base_revision INTEGER, base_width INTEGER, base_height INTEGER,
                image_source_id TEXT, evidence_kind TEXT NOT NULL DEFAULT 'recorded' CHECK(evidence_kind IN ('recorded','authored','imported')),
                package_import_id TEXT,
                CHECK((origin_kind='videoFrame' AND package_import_id IS NULL AND image_source_id IS NULL AND evidence_kind='recorded' AND source_id IS NOT NULL AND frame_pts_us IS NOT NULL AND time_precision_us IS NOT NULL
                    AND base_asset_id IS NULL AND base_sha256 IS NULL AND base_revision IS NULL AND base_width IS NULL AND base_height IS NULL)
                    OR (origin_kind='image' AND package_import_id IS NULL AND image_source_id IS NULL AND source_id IS NULL AND frame_pts_us IS NULL AND time_precision_us IS NULL
                    AND base_asset_id IS NOT NULL AND length(base_asset_id)>0 AND base_sha256 IS NOT NULL
                    AND length(base_sha256)=64 AND base_sha256 NOT GLOB '*[^0-9a-f]*'
                    AND base_revision IS NOT NULL AND base_revision>0 AND base_width IS NOT NULL AND base_width>0
                    AND base_height IS NOT NULL AND base_height>0 AND base_width*base_height<=12000000)
                    OR (origin_kind='image' AND package_import_id IS NULL AND image_source_id IS NOT NULL AND length(image_source_id)>0 AND evidence_kind='authored'
                    AND source_id IS NULL AND frame_pts_us IS NULL AND time_precision_us IS NULL
                    AND base_asset_id IS NULL AND base_sha256 IS NULL AND base_revision IS NULL AND base_width IS NULL AND base_height IS NULL)
                    OR (origin_kind='packageImage' AND package_import_id IS NOT NULL AND length(package_import_id)>0 AND evidence_kind='imported'
                    AND source_id IS NULL AND frame_pts_us IS NULL AND time_precision_us IS NULL AND image_source_id IS NULL
                    AND base_asset_id IS NULL AND base_sha256 IS NULL AND base_revision IS NULL AND base_width IS NULL AND base_height IS NULL)),
                PRIMARY KEY(project_id,state_id), UNIQUE(project_id,input_asset_id), UNIQUE(project_id,capture_id),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,source_id) REFERENCES sources(project_id,source_id) DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(project_id,input_asset_id) REFERENCES local_assets(project_id,asset_id) DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(project_id,image_source_id) REFERENCES image_sources(project_id,source_id) DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(project_id,state_id,package_import_id) REFERENCES package_step_origins(project_id,state_id,import_id) DEFERRABLE INITIALLY DEFERRED
            )"""
        }

    }

    companion object {
        /** API 26 SQLiteOpenHelper is not AutoCloseable. Preserve the operation's original error.
         * The synchronous action must not retain the store/cursors or start work that outlives it. */
        internal fun <T> withTemporary(context: Context, action: (ProjectStore) -> T): T {
            val store = ProjectStore(context)
            var failure: Throwable? = null
            try {
                return action(store)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try { synchronized(lock) { store.helper.close() } }
                catch (closeFailure: Throwable) {
                    if (failure == null) throw closeFailure
                    if (failure !== closeFailure) failure.addSuppressed(closeFailure)
                }
            }
        }

        private val lock = Any()
        /** Guarded by lock; shared across every store using the same app-private root. */
        private val activeImports = mutableSetOf<String>()
        private val activeProjectCopies = mutableSetOf<String>()
        private val PROJECT_COPY_TABLES = listOf("sources", "image_sources", "local_assets", "package_step_origins", "states", "hotspots", "edges", "next_actions", "edge_transitions", "regions")
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
