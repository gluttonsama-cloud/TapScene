package com.tapscene.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Real platform SQLite and PNG checks, with no additional test framework. All mutable data is
 * confined to this invocation's private UUID directory, including a separate projects.sqlite.
 * Synthetic PNG inspection exercises the reviewed-input contract; it is not a human privacy
 * review. The source placeholder tests ownership/references, not video import or decoding.
 */
object ProjectEditingChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "project-checks-${UUID.randomUUID()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        var failure: Throwable? = null
        try {
            checkGraphAndAssets(isolated, status)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Never sweep the real projects, shared sources, or another invocation's files.
            val cleanup = runCatching {
                check(root.canonicalFile.parentFile == parent)
                check(root.deleteRecursively() && !root.exists()) { "Project check cleanup failed" }
            }.exceptionOrNull()
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private suspend fun checkGraphAndAssets(context: Context, status: (String) -> Unit) {
        val root = context.noBackupFilesDir
        val store = ProjectStore(context)
        check(store.listProjects().isEmpty()) { "Project checks must use an isolated database" }
        val empty = store.createProject("  编辑检查  ", "本机合成素材")
        val id = empty.project.id
        checkUuid(id)
        check(empty.steps.isEmpty() && empty.project.stepCount == 0 && empty.project.startStepId == null)
        check(empty.project.title == "编辑检查" && empty.project.revision == 1L)
        val renamed = store.renameProject(id, "  重命名  ", "  更新目标  ")
        check(renamed.project.title == "重命名" && renamed.project.goal == "更新目标")
        check(renamed.project.revision == empty.project.revision + 1)
        check(ProjectStore(context).readProject(id) == renamed) { "Empty project did not persist" }
        val otherId = store.createProject("另一个项目").project.id
        checkUuid(otherId)
        check(id != otherId && store.listProjects().size == 2)
        status("PASS projects: isolated SQLite, explicit empty project, rename and independent-store reload")

        val source = syntheticSource(root)
        val sourceFile = File(root, source.privateRelativePath)
        val originalDigest = sha256(sourceFile)
        val retainedDraft = SourceDraft(source, frameTimeUs = 100_000L)
        WorkspaceStore(context, id).write(listOf(retainedDraft))
        val beforeDeletionWorkspaces = WorkspaceStore.retainedWorkspaces(context, setOf(id, otherId))
        check(beforeDeletionWorkspaces.none { it.projectId == id })
        check(beforeDeletionWorkspaces.single { it.projectId == null }.sourceCount == 0)
        val plain = generatedInput(context, source, emptyList())
        val masks = listOf(OpaqueMask(0.25f, 0.25f, 0.75f, 0.75f))
        val masked = generatedInput(context, source, masks)
        val anotherPlain = generatedInput(context, source, emptyList())
        val a = store.addReviewedStep(id, plain, "A", "起始说明").steps.single()
        val b = store.addReviewedStep(id, masked, "B").steps.last()
        val c = store.addReviewedStep(id, anotherPlain, "C").steps.last()
        val d = store.addReviewedStep(otherId, masked, "D").steps.single()
        val steps = listOf(a, b, c, d)
        (steps.map { it.id } + steps.map { it.asset.id }).also { ids ->
            check(ids.toSet().size == ids.size)
            ids.forEach(::checkUuid)
        }
        check(store.isSourceReferenced(source.sourceId))
        check(b.source == source && b.masks == masks && a.masks.isEmpty())
        check(b.frameTimeUs == masked.frameTimeUs && b.timePrecisionUs == masked.timePrecisionUs)
        check(b.asset.sha256 == masked.sha256 && b.asset.width == 48 && b.asset.height == 64)
        steps.forEach { step ->
            val projectId = if (step.id == d.id) otherId else id
            val saved = store.resolveAsset(projectId, step.id)
            check(saved.canonicalFile != plain.file.canonicalFile && saved.canonicalFile != masked.file.canonicalFile)
            check(saved.canonicalFile != sourceFile.canonicalFile && saved.extension == "png")
            check(saved.relativeTo(root).invariantSeparatorsPath == step.asset.privateRelativePath)
            check(saved.length() == step.asset.byteLength && sha256(saved) == step.asset.sha256)
            checkPixels(saved, step.masks)
        }
        checkUnchangedInputs(sourceFile, originalDigest, plain, masked, anotherPlain)
        status("PASS project PNGs: real SafeMediaWriter output, empty/burned masks, copied assets and retained source/candidates")
        checkSeededImportRecovery(context, store, id, a, plain)
        checkUnchangedInputs(sourceFile, originalDigest, plain, masked, anotherPlain)
        status("PASS project recovery: seeded orphan journal removes only owned staging/uncommitted PNG; committed PNG and graph remain intact")

        val rect = OpaqueMask(0.10f, 0.20f, 0.60f, 0.70f)
        var snapshot = store.saveHotspot(id, a.id, label = "前往 B", rect = rect, targetStepId = b.id)
        val first = snapshot.steps.first { it.id == a.id }.hotspots.single()
        checkUuid(first.id)
        checkUuid(first.edgeId)
        val editedRect = OpaqueMask(0.15f, 0.25f, 0.55f, 0.65f)
        snapshot = store.saveHotspot(id, a.id, first.id, "修改标签", editedRect, targetStepId = b.id)
        val updated = snapshot.steps.first { it.id == a.id }.hotspots.single()
        check(updated.id == first.id && updated.edgeId == first.edgeId && updated.rect == editedRect)
        check(updated.label == "修改标签" && updated.targetStepId == b.id && updated.endLabel == null)
        store.saveHotspot(id, a.id, label = "结束", rect = rect, endLabel = "完成")
        store.saveHotspot(id, b.id, label = "前往 C", rect = rect, targetStepId = c.id)
        store.saveHotspot(id, b.id, label = "再次 B", rect = rect, targetStepId = b.id)
        store.saveHotspot(id, c.id, label = "回到 A", rect = rect, targetStepId = a.id)
        val draftBase = checkNotNull(store.readProject(id))
        val draftHotspots = draftBase.steps.single { it.id == a.id }.hotspots
        store.saveStepDraft(id, a.id, "新标题", "第一行\n第二行 <纯文本>", false,
            draftHotspots, expectedRevision = draftBase.project.revision)
        val beforeReorder = checkNotNull(store.readProject(id))
        val reordered = store.reorderSteps(id, listOf(c.id, a.id, b.id))
        check(reordered.steps.map { it.id } == listOf(c.id, a.id, b.id))
        check(reordered.steps.map { it.sortOrder } == listOf(0, 1, 2))
        check(reordered.project.startStepId == a.id)
        check(reordered.steps.all { step ->
            val previous = beforeReorder.steps.single { it.id == step.id }
            step == previous.copy(sortOrder = step.sortOrder)
        }) { "Reordering changed stable graph content" }
        check(ProjectStore(context).readProject(id) == reordered) { "Graph reload changed values" }
        check(reordered.steps.single { it.id == a.id }.description == "第一行\n第二行 <纯文本>")

        val unchanged = checkNotNull(store.readProject(id))
        rejected { store.saveHotspot(id, a.id, label = "跨项目", rect = rect, targetStepId = d.id) }
        rejected { store.saveHotspot(id, d.id, label = "跨项目来源", rect = rect, targetStepId = a.id) }
        rejected { store.saveHotspot(id, b.id, first.id, "别步热点", rect, targetStepId = a.id) }
        rejected { store.saveHotspot(id, a.id, label = "无目标", rect = rect) }
        rejected { store.saveHotspot(id, a.id, label = "双目标", rect = rect, targetStepId = b.id, endLabel = "结束") }
        rejected { store.saveHotspot(id, a.id, label = "  ", rect = rect, endLabel = "结束") }
        rejected { store.saveHotspot(id, a.id, label = "越界", rect = OpaqueMask(-0.1f, 0f, 1f, 1f), endLabel = "结束") }
        rejected { OpaqueMask(0f, 0f, Float.NaN, 1f) }
        rejected { OpaqueMask(0f, 0f, Float.POSITIVE_INFINITY, 1f) }
        rejected { OpaqueMask(0.5f, 0f, 0.5f, 1f) }
        rejected { store.setStartStep(id, d.id) }
        rejected { store.setTerminal(id, a.id, true) }
        rejected { store.reorderSteps(id, listOf(a.id, a.id, c.id)) }
        rejected { store.saveStepDraft(id, a.id, "不能覆盖", "旧草稿", false,
            draftHotspots, expectedRevision = unchanged.project.revision - 1) }
        rejected { store.saveStepDraft(id, a.id, "不能部分保存", "跨项目目标", false,
            draftHotspots.map { if (it.targetStepId != null) it.copy(targetStepId = d.id) else it },
            expectedRevision = unchanged.project.revision) }
        check(store.readProject(id) == unchanged) { "Rejected edit changed the database or revision" }
        store.setTerminal(otherId, d.id, true)
        rejected { store.saveHotspot(otherId, d.id, label = "终点出口", rect = rect, endLabel = "结束") }
        check(checkNotNull(store.readProject(otherId)).steps.single().isTerminal)
        status("PASS project graph: stable UUID/order/hotspots, self-loop/end action, persistence and rejected cross-project/invalid edits")

        val savedFiles = assetFiles(root)
        val wrongDigest = (if (plain.sha256.first() == '0') "1" else "0") + plain.sha256.drop(1)
        rejectedSuspend { store.addReviewedStep(id,
            plain.copy(sha256 = wrongDigest, captureId = UUID.randomUUID().toString()), "错误摘要") }
        check(store.readProject(id) == unchanged && assetFiles(root) == savedFiles)
        checkNoStaging(root)
        checkUnchangedInputs(sourceFile, originalDigest, plain, masked)
        val cancellationInput = generatedInput(context, source, emptyList())
        checkCancellationBoundaries(store, id, cancellationInput, root)
        checkUnchangedInputs(sourceFile, originalDigest, plain, masked, anotherPlain, cancellationInput)
        status("PASS project save boundaries: wrong SHA leaves no DB/staging/asset residue; pre-cancel rejects, post-return cancel retains commit, same-capture retry is idempotent")
        status("NOT_COVERED project save: dispatcher-return cancellation race and actual process-kill timing require separate fault injection/device checks")

        // A corrupted derived file must fail closed, never substitute the original source.
        val derived = store.resolveAsset(otherId, d.id)
        val bytes = derived.readBytes()
        derived.appendBytes(byteArrayOf(1))
        rejected { store.resolveAsset(otherId, d.id) }
        check(sourceFile.isFile && sha256(sourceFile) == originalDigest)
        derived.writeBytes(bytes)
        check(store.resolveAsset(otherId, d.id) == derived)

        store.setStartStep(id, b.id)
        val impact = store.stepDeletionImpact(id, b.id)
        check(impact == StepDeletionImpact(2, 2, 3, 3, true)) { "Self-loop deletion impact double-counted" }
        val bFile = store.resolveAsset(id, b.id)
        val deletion = store.deleteStep(id, b.id)
        check(deletion.impact == impact && deletion.pendingAssetCleanupCount == 0)
        check(!bFile.exists() && deletion.snapshot.project.startStepId == c.id)
        check(deletion.snapshot.steps.map { it.id } == listOf(c.id, a.id))
        check(deletion.snapshot.steps.map { it.sortOrder } == listOf(0, 1))
        check(deletion.snapshot.steps.flatMap { it.hotspots }.size == 2)
        check(deletion.snapshot.steps.flatMap { it.hotspots }.none { it.targetStepId == b.id })
        check(deletion.snapshot.steps.single { it.id == a.id }.hotspots.single().endLabel == "完成")
        check(deletion.snapshot.steps.single { it.id == c.id }.hotspots.single().targetStepId == a.id)
        check(store.deleteStep(id, c.id).snapshot.project.startStepId == a.id)
        val emptied = store.deleteStep(id, a.id).snapshot
        check(emptied.steps.isEmpty() && emptied.project.startStepId == null && emptied.project.stepCount == 0)
        check(store.isSourceReferenced(source.sourceId)) { "Other project's source reference was removed" }
        check(store.deleteProject(id).stepCount == 0 && store.readProject(id) == null)
        val retained = WorkspaceStore.retainedWorkspaces(context, setOf(otherId)).single { it.projectId == id }
        check(retained.sourceCount == 1 && retained.label == source.displayName)
        check(WorkspaceStore(context, retained.projectId).read() == listOf(retainedDraft)) {
            "Deleted project's retained media is no longer reachable"
        }
        check(WorkspaceStore(context).read().isEmpty()) { "Retained media changed the legacy workspace" }
        val otherDeletion = store.deleteProject(otherId)
        check(otherDeletion.stepCount == 1 && otherDeletion.pendingAssetCleanupCount == 0)
        check(store.readProject(otherId) == null && store.listProjects().isEmpty())
        check(!store.isSourceReferenced(source.sourceId) && assetFiles(root).isEmpty())
        checkUnchangedInputs(sourceFile, originalDigest, plain, masked, anotherPlain, cancellationInput)
        checkDatabaseEmptyAndConsistent(root)
        status("PASS project deletion: incoming/outgoing/self-loop cleanup, start repair, derived-only failure and reachable retained media")
    }

    /** Seed durable crash leftovers; this is recovery-path coverage, not a process-kill test. */
    private fun checkSeededImportRecovery(context: Context, store: ProjectStore, projectId: String,
        committed: ProjectStep, input: ReviewedStepInput) {
        val root = context.noBackupFilesDir
        val before = checkNotNull(store.readProject(projectId))
        val beforeFiles = assetFiles(root)
        val committedFile = store.resolveAsset(projectId, committed.id)
        val orphanId = UUID.randomUUID().toString()
        val ids = listOf(orphanId, committed.asset.id)
        val database = File(root, "projects.sqlite")
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            ids.forEach { assetId ->
                db.execSQL("INSERT INTO asset_imports(project_id,asset_id) VALUES(?,?)", arrayOf(projectId, assetId))
            }
        }
        val staging = ids.map { assetId ->
            File(root, "project-staging/$assetId").also { directory ->
                check(directory.mkdir())
                input.file.copyTo(File(directory, "candidate.part"))
            }
        }
        val orphan = File(root, "project-assets/$projectId/$orphanId.png")
        input.file.copyTo(orphan)
        check(ProjectStore(context).readProject(projectId) == before)
        check(staging.none { it.exists() } && !orphan.exists()) { "Recovery left owned import files behind" }
        check(committedFile.isFile && sha256(committedFile) == committed.asset.sha256) {
            "Recovery removed or changed a committed asset"
        }
        check(assetFiles(root) == beforeFiles)
        checkNoStaging(root)
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM asset_imports", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
        }
    }

    private suspend fun checkCancellationBoundaries(store: ProjectStore, id: String, input: ReviewedStepInput, root: File) = coroutineScope {
        val before = checkNotNull(store.readProject(id))
        val files = assetFiles(root)
        val preCancelled = async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            store.addReviewedStep(id, input, "已取消")
        }
        check(runCatching { preCancelled.await() }.exceptionOrNull() is CancellationException)
        check(store.readProject(id) == before && assetFiles(root) == files)
        checkNoStaging(root)

        var committedId: String? = null
        val afterCommit = async(start = CoroutineStart.UNDISPATCHED) {
            val saved = store.addReviewedStep(id, input, "提交后取消")
            committedId = saved.steps.single { step -> before.steps.none { it.id == step.id } }.id
            // The successful return is an observable commit boundary, not a synthetic race hook.
            currentCoroutineContext().cancel()
            currentCoroutineContext().ensureActive()
        }
        check(runCatching { afterCommit.await() }.exceptionOrNull() is CancellationException)
        val savedId = checkNotNull(committedId)
        val retained = checkNotNull(store.readProject(id))
        check(retained.steps.size == before.steps.size + 1 && retained.steps.any { it.id == savedId })
        check(store.resolveAsset(id, savedId).isFile)
        val committedFiles = assetFiles(root)
        check(store.addReviewedStep(id, input, "同一次候选重试") == retained) {
            "Retry duplicated a committed capture or changed its revision"
        }
        check(assetFiles(root) == committedFiles)
        checkNoStaging(root)
        // Remove only this check's extra committed step, restoring the intended deletion graph.
        store.deleteStep(id, savedId)
        check(assetFiles(root) == files)
    }

    private fun syntheticSource(root: File): ImportedSource {
        val id = UUID.randomUUID().toString()
        val relative = "sources/$id.mp4"
        val file = File(root, relative)
        check(file.parentFile!!.mkdir() && file.createNewFile())
        file.writeBytes("Synthetic source ownership fixture; not a playable video.".toByteArray())
        return ImportedSource(id, relative, "synthetic-ownership.mp4",
            SourceMetadata("video/mp4", file.length(), sha256(file), 48, 64, 0, 1_000_000L))
    }

    private suspend fun generatedInput(context: Context, source: ImportedSource, masks: List<OpaqueMask>): ReviewedStepInput {
        val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        try {
            val candidate = SafeMediaWriter(context).writePng(bitmap, masks,
                File(context.noBackupFilesDir, "candidates/${UUID.randomUUID()}"))
            check(!bitmap.isRecycled && candidate.mimeType == "image/png" && candidate.durationUs == null)
            check(sha256(candidate.file) == candidate.sha256)
            checkPixels(candidate.file, masks)
            return ReviewedStepInput(candidate.file, candidate.sha256, candidate.width, candidate.height,
                source, 100_000L, 1_000L, masks)
        } finally {
            bitmap.recycle()
        }
    }

    private fun checkPixels(file: File, masks: List<OpaqueMask>) {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            check(bitmap.width == 48 && bitmap.height == 64)
            val rects = masks.map { it.toPixelRect(bitmap.width, bitmap.height) }
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                check(bitmap.getPixel(x, y) == if (rects.any { it.contains(x, y) }) Color.BLACK else Color.CYAN) {
                    "Actual PNG pixels differ from the synthetic expected output"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun checkUnchangedInputs(source: File, sourceDigest: String, vararg inputs: ReviewedStepInput) {
        check(source.isFile && sha256(source) == sourceDigest) { "Original source was changed or removed" }
        inputs.forEach { check(it.file.isFile && sha256(it.file) == it.sha256) { "Caller-owned candidate was changed or removed" } }
    }

    private fun checkNoStaging(root: File) {
        check(File(root, "project-staging").listFiles().orEmpty().isEmpty()) { "Save leaked a staging directory" }
    }

    private fun assetFiles(root: File): Set<String> = File(root, "project-assets").walkTopDown()
        .filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()

    private fun checkDatabaseEmptyAndConsistent(root: File) {
        SQLiteDatabase.openDatabase(File(root, "projects.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
            db.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
            for (table in listOf("projects", "states", "sources", "local_assets", "hotspots", "edges", "asset_cleanup", "asset_imports")) {
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
            }
        }
    }

    private fun checkUuid(id: String) { check(UUID.fromString(id).toString() == id) }

    private fun rejected(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        check(error is IllegalArgumentException || error is IllegalStateException) { "Invalid edit was not explicitly rejected: $error" }
    }

    private suspend fun rejectedSuspend(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        check(error is IllegalArgumentException || error is IllegalStateException) { "Invalid save was not explicitly rejected: $error" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0)
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
