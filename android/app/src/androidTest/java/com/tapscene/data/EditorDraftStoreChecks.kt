package com.tapscene.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/** Real platform SQLite/JSON/PNG checks. Fixtures are private and are not human privacy review. */
object EditorDraftStoreChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "editor-draft-checks-${id()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        var failure: Throwable? = null
        try {
            checkStore(isolated(context, File(root, "store")), status)
            for (version in 1..4) checkMigration(isolated(context, File(root, "v$version")), version)
            status("PASS editor SQLite v1/v2/v3/v4-to-v5 upgrades: additive tables, unchanged official project IDs/content/revision and empty recovery")
            status("NOT_COVERED editor recovery: actual process kill, IME typing and Compose lifecycle gestures require separate device/UI checks")
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                check(root.canonicalFile.parentFile == parent)
                check(root.deleteRecursively() && !root.exists())
            }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private suspend fun checkStore(context: Context, status: (String) -> Unit) = coroutineScope {
        val store = ProjectStore(context)
        val source = source(context)
        val input = png(context, source)
        val p = store.createProject("Recovery").project.id
        val a = store.addReviewedStep(p, input.copy(captureId = "a"), "A", "Official A").steps.single().id
        val b = store.addReviewedStep(p, input.copy(captureId = "b"), "B").steps.last().id
        val other = store.createProject("Independent").project.id
        val c = store.addReviewedStep(other, input.copy(captureId = "c"), "C").steps.single().id
        val before = checkNotNull(store.readProject(p))
        val step = before.steps.single { it.id == a }
        checkCodec(step, source)
        val pending = EditorPendingForm(EditorFormKind.HOTSPOT, objectId = id(), edgeId = id(),
            label = "  未完成\n标签  ", left = "-", top = "not a number", right = "1..2", bottom = "", targetStepId = b)
        val draft = StoredEditorDraft(before.project.revision, step.editorFields(),
            step.editorFields().copy(title = "Unsaved A", description = "  preserve spacing\n\t  "), pending)
        val bDraft = StoredEditorDraft(before.project.revision, before.steps.single { it.id == b }.editorFields(),
            before.steps.single { it.id == b }.editorFields().copy(description = "Independent unsaved B"))
        val firstSession = store.beginEditorDraftSession(p)
        check(firstSession > 0)
        check(store.writeEditorDraft(p, a, firstSession, draft))
        check(store.writeEditorDraft(p, b, firstSession, bDraft))
        check(store.readProject(p) == before) { "Transient recovery changed official content, revision or updatedAt" }
        val reopened = ProjectStore(context)
        check(reopened.readEditorDrafts(p) == mapOf(a to draft, b to bDraft)) { "Reopening lost raw pending form input" }

        // A queued old session is released only after a different store installs its generation.
        val releaseOldWriter = CompletableDeferred<Unit>()
        val lateWrite = async(Dispatchers.IO) {
            releaseOldWriter.await()
            store.writeEditorDraft(p, a, firstSession, draft.copy(edit = draft.edit.copy(title = "Late old write")))
        }
        val session = reopened.beginEditorDraftSession(p)
        check(session > firstSession)
        releaseOldWriter.complete(Unit)
        check(!lateWrite.await())
        check(!store.clearEditorDraft(p, a, firstSession))
        check(!store.writeEditorDraft(p, id(), session, draft))
        check(!store.writeEditorDraft(p, c, session, draft))
        check(reopened.readEditorDrafts(p) == mapOf(a to draft, b to bDraft))
        rejected { save(store, p, a, draft, firstSession) }
        rejected { save(store, p, a, draft.copy(baseRevision = before.project.revision - 1), session) }
        check(store.readProject(p) == before && store.readEditorDrafts(p) == mapOf(a to draft, b to bDraft))
        status("PASS editor sessions: cross-store persisted generation rejects delayed old writer/clear/save and deleted/foreign targets; no formal revision changes")

        val oversized = draft.copy(pendingForm = pending.copy(label = "界".repeat(90_000)))
        rejected { store.writeEditorDraft(p, a, session, oversized) }
        check(store.readEditorDrafts(p)[a] == draft) { "Oversize write lost last good draft" }
        database(context) { db ->
            db.execSQL("UPDATE editor_drafts SET draft_json=? WHERE project_id=? AND state_id=?", arrayOf("{broken", p, a))
        }
        rejected { store.readEditorDrafts(p) }
        database(context) { db ->
            db.rawQuery("SELECT draft_json FROM editor_drafts WHERE project_id=? AND state_id=?", arrayOf(p, a)).use {
                check(it.moveToFirst() && it.getString(0) == "{broken")
            }
        }
        check(store.writeEditorDraft(p, a, session, draft))
        status("PASS editor persistence: raw invalid numeric/text survives reopen, UTF-8 bound fails without truncation, corrupt row reports error and remains intact")

        // Inject failure after the save has already deleted its recovery row, at revision bump.
        database(context) { db -> db.execSQL("""CREATE TRIGGER reject_editor_save BEFORE UPDATE OF draft_revision ON projects
            BEGIN SELECT RAISE(ABORT, 'injected editor save failure'); END""") }
        try { rejected { save(store, p, a, draft, session) } }
        finally { database(context) { it.execSQL("DROP TRIGGER reject_editor_save") } }
        check(store.readProject(p) == before && store.readEditorDrafts(p) == mapOf(a to draft, b to bDraft)) {
            "Failed graph transaction did not restore recovery row and original revision/content"
        }
        val saved = save(store, p, a, draft, session)
        check(saved.project.revision == before.project.revision + 1)
        check(saved.steps.single { it.id == a }.title == "Unsaved A")
        check(store.readEditorDrafts(p) == mapOf(b to bDraft)) { "Saving one step cleared another step's recovery" }
        // Same-session ordering is the workspace's writer-mutex responsibility; token stays valid.
        check(store.writeEditorDraft(p, a, session, draft.copy(baseRevision = saved.project.revision)))
        check(store.clearEditorDraft(p, a, session))
        check(store.readProject(p) == saved)
        status("PASS editor atomic save: injected post-clear failure restores graph/recovery; success increments once and clears only saved step; same session remains usable")

        val otherSession = store.beginEditorDraftSession(other)
        val otherStep = checkNotNull(store.readProject(other)).steps.single()
        val otherDraft = StoredEditorDraft(2, otherStep.editorFields(), otherStep.editorFields().copy(title = "Other draft"))
        check(store.writeEditorDraft(other, c, otherSession, otherDraft))
        store.deleteStep(p, b)
        check(store.readEditorDrafts(p).isEmpty())
        check(!store.writeEditorDraft(p, b, session, bDraft))
        check(store.writeEditorDraft(p, a, session, draft))
        store.deleteProject(p)
        check(store.readEditorDrafts(p).isEmpty())
        check(!store.writeEditorDraft(p, a, session, draft))
        rejected { store.beginEditorDraftSession(p) }
        check(store.readEditorDrafts(other) == mapOf(c to otherDraft))
        database(context) { db ->
            db.rawQuery("SELECT COUNT(*) FROM editor_draft_sessions WHERE project_id=?", arrayOf(p)).use { check(it.moveToFirst() && it.getInt(0) == 0) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
            db.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
        }
        status("PASS editor deletion: step/project cascades and missing-target barriers preserve other project's draft/session")
    }

    private fun checkCodec(step: ProjectStep, source: ImportedSource) {
        val media = ProjectTransition(TransitionAsset(id(), "private-transition.mp4", "a".repeat(64), 30, 32, 48, 1_000),
            source, 0, 1_000, emptyList(), "private-review")
        val hotspot = ProjectHotspot(id(), "Branch", OpaqueMask(.1f, .2f, .8f, .9f), step.id, null, id(), media)
        val next = ProjectNextAction(id(), "Continue", step.id, media)
        val official = step.copy(hotspots = listOf(hotspot), nextAction = next)
        val fields = official.editorFields()
        check(fields.hotspots.single().transition == null && fields.nextAction?.transition == null)
        check(fields.withCurrentMedia(official).hotspots.single().transition == media)
        check(fields.withCurrentMedia(official).nextAction?.transition == media)
        val changed = fields.copy(hotspots = listOf(hotspot.copy(label = "Changed")), nextAction = next.copy(targetStepId = null))
        check(changed.withCurrentMedia(official).hotspots.single().transition == media)
        check(changed.copy(hotspots = listOf(hotspot.copy(targetStepId = null, endLabel = "End")))
            .withCurrentMedia(official).hotspots.single().transition == null)
        check(changed.withCurrentMedia(official).nextAction?.transition == null)
        val removed = official.copy(hotspots = emptyList(), nextAction = null)
        check(fields.withCurrentMedia(removed).hotspots.single().transition == null)
        check(fields.withCurrentMedia(removed).nextAction?.transition == null)
        val stripped = StoredEditorDraft(1, fields, fields, EditorPendingForm(EditorFormKind.NAME, title = " ", description = "\n\t"))
        // Even supplied media-bearing objects are explicitly stripped by the codec whitelist.
        val encoded = EditorDraftCodec.encode(stripped.copy(base = fields.copy(hotspots = listOf(hotspot), nextAction = next)))
        for (forbidden in listOf("\"transition\"", "\"source\"", "\"asset\"", "\"path\"", "private-transition.mp4", source.privateRelativePath)) {
            check(!encoded.contains(forbidden)) { "Recovery serialized private media/provenance" }
        }
        check(EditorDraftCodec.decode(encoded) == stripped)
        for (kind in EditorFormKind.entries) {
            val raw = stripped.copy(pendingForm = EditorPendingForm(kind, title = "", label = "  ", left = "NaN", top = "-", right = "1e", bottom = ""))
            check(EditorDraftCodec.decode(EditorDraftCodec.encode(raw)) == raw)
        }
        rejected { EditorDraftCodec.decode(JSONObject(encoded).put("version", 2).toString()) }
        rejected { EditorDraftCodec.decode(JSONObject(encoded).put("baseRevision", "1").toString()) }
        rejected { EditorDraftCodec.decode(JSONObject(encoded).put("source", source.privateRelativePath).toString()) }
        rejected { EditorDraftCodec.decode(JSONObject(encoded).apply { getJSONObject("edit").put("transition", JSONObject()) }.toString()) }
        rejected { EditorDraftCodec.decode(encoded + " trailing") }
        rejected { EditorDraftCodec.decode(JSONObject(encoded).apply { getJSONObject("pendingForm").put("kind", "UNKNOWN") }.toString()) }
    }

    private suspend fun checkMigration(context: Context, previous: Int) {
        val store = ProjectStore(context)
        val project = store.createProject("Migration $previous").project.id
        val before = store.addReviewedStep(project, png(context, source(context)), "Kept", "Preserve text")
        // Prepare the exact old table set from an empty additive v5 baseline, then reopen through
        // production SQLiteOpenHelper. Host tests independently exercise the extracted old DDL.
        database(context) { db ->
            db.execSQL("DROP TABLE editor_drafts"); db.execSQL("DROP TABLE editor_draft_sessions")
            if (previous < 4) db.execSQL("DROP TABLE regions")
            if (previous < 3) { db.execSQL("DROP TABLE edge_transitions"); db.execSQL("DROP TABLE transition_imports") }
            if (previous < 2) db.execSQL("DROP TABLE next_actions")
            db.version = previous
        }
        val reopened = ProjectStore(context)
        check(reopened.readProject(project) == before)
        check(reopened.readEditorDrafts(project).isEmpty())
        check(reopened.beginEditorDraftSession(project) == 1L)
        database(context) { db ->
            check(db.version == 5)
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
        }
    }

    private fun save(store: ProjectStore, projectId: String, stepId: String, draft: StoredEditorDraft, session: Long): ProjectSnapshot =
        store.saveStepDraft(projectId, stepId, draft.edit.title, draft.edit.description, draft.edit.isTerminal,
            draft.edit.hotspots, draft.baseRevision, draft.edit.nextAction, session)

    private fun isolated(context: Context, root: File): Context {
        check(root.mkdir())
        return object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
    }
    private fun database(context: Context, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "projects.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    }
    private fun source(context: Context): ImportedSource {
        val sourceId = id()
        val relative = "sources/$sourceId.mp4"
        val file = File(context.noBackupFilesDir, relative)
        check(file.parentFile!!.mkdir())
        file.writeText("Synthetic editor draft source ownership fixture; not playable video.")
        return ImportedSource(sourceId, relative, "Fixture.mp4", SourceMetadata("video/mp4", file.length(),
            ViewerPackageCodec.sha256(file), 32, 48, 0, 1_000_000))
    }
    private suspend fun png(context: Context, source: ImportedSource): ReviewedStepInput {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        return try {
            val output = SafeMediaWriter(context).writePng(bitmap, emptyList(), File(context.noBackupFilesDir, "candidate-${id()}"))
            ReviewedStepInput(output.file, output.sha256, output.width, output.height, source, 0, 1_000, emptyList())
        } finally { bitmap.recycle() }
    }
    private fun rejected(block: () -> Unit) { check(runCatching(block).isFailure) { "Invalid editor recovery action was accepted" } }
    private fun id() = UUID.randomUUID().toString()
}
