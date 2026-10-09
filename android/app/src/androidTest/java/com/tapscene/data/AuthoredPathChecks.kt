package com.tapscene.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Real platform SQLite/PNG tests, confined to one private invocation directory. No new runner. */
object AuthoredPathChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "authored-path-checks-${id()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        var failure: Throwable? = null
        try {
            val input = fixture(isolated)
            val legacy = seedVersionOne(root, input)
            val store = ProjectStore(isolated)
            val migrated = checkNotNull(store.readProject(legacy.project.id))
            check(migrated == legacy) { "Migration changed legacy IDs, graph, text, source or asset metadata" }
            legacy.steps.forEach { check(digest(store.resolveAsset(legacy.project.id, it.id)) == input.sha256) }
            SQLiteDatabase.openDatabase(File(root, "projects.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                check(db.version == 4)
                db.rawQuery("SELECT COUNT(*) FROM next_actions", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
                db.rawQuery("SELECT COUNT(*) FROM edge_transitions", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
                db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
            }
            status("PASS authored path migration: real v1 SQLite upgrades to v4 without changing manual hotspots, stable IDs, revision or PNG bytes")
            checkGraph(store, isolated, legacy, input, status)
            checkCapacity(store, input, status)
            check(input.file.isFile && digest(input.file) == input.sha256)
            status("PASS authored paths: caller-owned input retained; checks use synthetic PNGs, not tap detection or device interaction proof")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanup = runCatching {
                check(root.canonicalFile.parentFile == parent)
                check(root.deleteRecursively() && !root.exists())
            }.exceptionOrNull()
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private suspend fun checkGraph(store: ProjectStore, context: Context, legacy: ProjectSnapshot,
        input: ReviewedStepInput, status: (String) -> Unit) {
        val p = legacy.project.id
        val a = legacy.steps[0].id
        val b = legacy.steps[1].id
        val c = store.addReviewedStep(p, input.copy(captureId = "new-c"), "C").steps.last().id
        val d = store.addReviewedStep(p, input.copy(captureId = "new-d"), "D").steps.last().id
        val other = store.createProject("Other")
        val foreign = store.addReviewedStep(other.project.id, input.copy(captureId = "other"), "Other step").steps.single()
        var current = checkNotNull(store.readProject(p))
        val manual = current.steps.associate { it.id to it.hotspots }
        current = store.connectStepsInOrder(p, listOf(a, b, c), false, current.project.revision)
        check(current.steps.single { it.id == a }.nextAction?.targetStepId == b)
        check(current.steps.single { it.id == b }.nextAction?.targetStepId == c)
        check(current.steps.single { it.id == c }.nextAction == null)
        check(current.steps.associate { it.id to it.hotspots } == manual)
        val oldA = checkNotNull(current.steps.single { it.id == a }.nextAction)
        val aStep = current.steps.single { it.id == a }
        current = store.saveStepDraft(p, a, aStep.title, aStep.description, false, aStep.hotspots,
            expectedRevision = current.project.revision, nextAction = oldA.copy(label = "继续填写"))
        val beforeOrder = current
        current = store.reorderSteps(p, listOf(c, d, b, a))
        check(current.project.startStepId == beforeOrder.project.startStepId)
        check(current.steps.all { step -> step == beforeOrder.steps.single { it.id == step.id }.copy(sortOrder = step.sortOrder) })
        check(ProjectStore(context).readProject(p) == current)
        val oldB = current.steps.single { it.id == b }.nextAction
        current = store.connectStepsInOrder(p, listOf(a, c, b), false, current.project.revision)
        check(current.steps.map { it.id } == listOf(c, d, b, a))
        check(current.steps.single { it.id == a }.nextAction == oldA.copy(label = "继续填写", targetStepId = c))
        check(current.steps.single { it.id == b }.nextAction == oldB) { "Last selected action was silently replaced" }
        check(current.steps.single { it.id == d }.nextAction == null) { "Unselected step gained an action" }
        check(current.steps.associate { it.id to it.hotspots } == manual)
        status("PASS authored path graph: explicit rebuild preserves action IDs/labels and manual hotspots; ordinary reorder preserves all links and start")

        checkForeignKeys(context, p, a, d, foreign.id)
        check(store.readProject(p) == current)
        val stable = current
        rejected { store.connectStepsInOrder(p, listOf(a, b), false, stable.project.revision - 1) }
        rejected { store.connectStepsInOrder(p, listOf(a, a), false, stable.project.revision) }
        rejected { store.connectStepsInOrder(p, listOf(a), false, stable.project.revision) }
        rejected { store.connectStepsInOrder(p, listOf(a, foreign.id), false, stable.project.revision) }
        rejected { store.connectStepsInOrder(p, listOf(a, b), true, stable.project.revision) }
        rejected { store.setTerminal(p, c, true) }
        val stableA = stable.steps.single { it.id == a }
        rejected { store.saveStepDraft(p, a, "must not save", "", true, emptyList(), stable.project.revision, stableA.nextAction) }
        rejected { store.saveStepDraft(p, a, "must not save", "", false, stableA.hotspots,
            stable.project.revision, stableA.nextAction?.copy(targetStepId = foreign.id)) }
        rejected { store.saveStepDraft(p, a, "must not save", "", false, stableA.hotspots,
            stable.project.revision, stableA.nextAction?.copy(id = id())) }
        check(store.readProject(p) == stable) { "Rejected action changed revision or any graph content" }
        current = store.setTerminal(p, d, true)
        rejected { store.connectStepsInOrder(p, listOf(a, d, b), false, current.project.revision) }
        check(store.readProject(p) == current)
        current = store.connectStepsInOrder(p, listOf(b, d), true, current.project.revision)
        check(current.steps.single { it.id == d }.isTerminal && current.steps.single { it.id == d }.nextAction == null)
        check(current.steps.single { it.id == b }.nextAction?.id == oldB?.id)
        status("PASS authored path validation: stale/duplicate/foreign/conflicting terminal edits fail atomically; explicit empty final step becomes terminal")

        // A SQLite trigger forces failure after an earlier update in the same transaction, so
        // this checks rollback beyond preflight validation, including unchanged draft_revision.
        val beforeFailure = current
        val database = File(context.noBackupFilesDir, "projects.sqlite")
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER authored_path_test_abort BEFORE UPDATE ON next_actions WHEN OLD.from_state_id='$b' BEGIN SELECT RAISE(ABORT, 'injected authored path failure'); END")
        }
        try {
            check(runCatching { store.connectStepsInOrder(p, listOf(a, b, c), false, current.project.revision) }.isFailure)
            check(store.readProject(p) == beforeFailure)
        } finally {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TRIGGER authored_path_test_abort") }
        }
        status("PASS authored path transaction: injected second-write SQLite failure rolls back first action update and revision")

        // All selected IDs and action IDs survived until the actual deletion; the incoming
        // button becomes unresolved while the deleted source's own action is removed.
        val incoming = checkNotNull(current.steps.single { it.id == a }.nextAction)
        val impact = store.stepDeletionImpact(p, c)
        check(impact.incomingNextActionCount == 1 && impact.outgoingNextActionCount == 1)
        check(impact.edgeCount == 2 && impact.hotspotCount == 0)
        current = store.deleteStep(p, c).snapshot
        check(current.steps.none { it.id == c })
        check(current.steps.single { it.id == a }.nextAction == incoming.copy(targetStepId = null))
        check(!current.steps.single { it.id == a }.isTerminal)
        val renamed = store.renameProject(p, "Renamed with broken action")
        check(renamed.steps == current.steps)
        val brokenA = renamed.steps.single { it.id == a }
        current = store.saveStepDraft(p, a, "A repaired", brokenA.description, false, brokenA.hotspots,
            renamed.project.revision, checkNotNull(brokenA.nextAction).copy(targetStepId = b))
        check(current.steps.single { it.id == a }.nextAction == incoming.copy(targetStepId = b))
        val repaired = current.steps.single { it.id == a }
        current = store.saveStepDraft(p, a, repaired.title, repaired.description, false, repaired.hotspots,
            current.project.revision, nextAction = null)
        check(current.steps.single { it.id == a }.nextAction == null)
        check(current.steps.single { it.id == a }.hotspots == repaired.hotspots)
        current = store.saveStepDraft(p, a, repaired.title, repaired.description, false, repaired.hotspots,
            current.project.revision, nextAction = repaired.nextAction)
        val projected = current.steps.sumOf { it.hotspots.size + if (it.nextAction == null) 0 else 1 }
        check(store.deleteProject(p).edgeCount == projected)
        check(store.readProject(other.project.id)?.steps?.single()?.id == foreign.id)
        status("PASS authored path deletion: target removal preserves repairable ID/label; source removal cascades; delete impact and project totals count both edge types")
    }

    private fun checkForeignKeys(context: Context, projectId: String, occupied: String, free: String, foreign: String) {
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "projects.sqlite").path,
            null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            for ((from, to) in listOf(free to foreign, foreign to free, occupied to free)) {
                val error = runCatching {
                    db.beginTransaction()
                    try {
                        db.execSQL("INSERT INTO next_actions VALUES(?,?,?,?,?)", arrayOf(projectId, id(), from, "Invalid FK", to))
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }.exceptionOrNull()
                check(error is SQLiteConstraintException) { "Platform SQLite accepted cross-project/duplicate-source next action: $error" }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
        }
    }

    private suspend fun checkCapacity(store: ProjectStore, input: ReviewedStepInput, status: (String) -> Unit) {
        val p = store.createProject("Capacity").project.id
        var current = checkNotNull(store.readProject(p))
        repeat(ProjectLimits.MAX_STEPS) { index ->
            current = store.addReviewedStep(p, input.copy(captureId = "capacity-$index"), "Step $index")
        }
        val order = current.steps.map { it.id }
        current = store.connectStepsInOrder(p, order, false, current.project.revision)
        check(current.steps.count { it.nextAction != null } == 39)
        repeat(41) { index ->
            current = store.saveHotspot(p, order[index / 6], label = "Manual $index",
                rect = OpaqueMask(0.1f, 0.1f, 0.2f, 0.2f), endLabel = "End")
        }
        check(current.steps.sumOf { it.hotspots.size + if (it.nextAction == null) 0 else 1 } == 80)
        val atLimit = current
        val last = current.steps.last()
        rejected { store.saveHotspot(p, last.id, label = "81st", rect = OpaqueMask(0f, 0f, 0.2f, 0.2f), endLabel = "End") }
        rejected { store.connectStepsInOrder(p, listOf(last.id, order.first()), false, current.project.revision) }
        rejected { store.connectStepsInOrder(p, order + order.first(), false, current.project.revision) }
        rejected { store.saveStepDraft(p, last.id, "must not save", "", false, emptyList(), current.project.revision,
            ProjectNextAction(id(), "81st", order.first())) }
        val extraHotspot = ProjectHotspot(id(), "81st", OpaqueMask(0f, 0f, 0.2f, 0.2f), order.first(), null, id())
        rejected { store.saveStepDraft(p, last.id, "must not save", "", false, listOf(extraHotspot), current.project.revision) }
        check(store.readProject(p) == atLimit)
        current = store.connectStepsInOrder(p, order.reversed().drop(1), false, current.project.revision)
        check(current.steps.sumOf { it.hotspots.size + if (it.nextAction == null) 0 else 1 } == 80)
        val first = current.steps.first()
        current = store.saveStepDraft(p, first.id, "Renamed at limit", "", false, first.hotspots,
            current.project.revision, checkNotNull(first.nextAction).copy(label = "Edited at limit"))
        check(current.steps.first().title == "Renamed at limit")
        val removed = store.deleteProject(p)
        check(removed.edgeCount == 80 && removed.hotspotCount == 41)
        status("PASS authored path limits: 40 steps/39 authored links plus 41 hotspots; every 81st edge path rejects atomically; existing link edits remain allowed")
    }

    /** Frozen production-v1 DDL fixture, intentionally independent of the current helper. */
    private fun seedVersionOne(root: File, input: ReviewedStepInput): ProjectSnapshot {
        val p = id()
        val stepIds = listOf(id(), id())
        val assets = stepIds.map { StepAsset(id(), "", input.sha256, input.file.length(), input.width, input.height) }
            .map { it.copy(privateRelativePath = "project-assets/$p/${it.id}.png") }
        assets.forEach { asset ->
            val file = File(root, asset.privateRelativePath)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            input.file.copyTo(file)
        }
        val hotspot = ProjectHotspot(id(), "Legacy manual", OpaqueMask(0.1f, 0.2f, 0.4f, 0.6f), stepIds[1], null, id())
        SQLiteDatabase.openOrCreateDatabase(File(root, "projects.sqlite"), null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.beginTransaction()
            try {
                legacyDdl.forEach(db::execSQL)
                db.execSQL("INSERT INTO projects VALUES(?,?,?,?,?,?,?)", arrayOf(p, "Legacy", "Original goal", 11L, 12L, 9L, stepIds[0]))
                val source = checkNotNull(input.source)
                val json = JSONObject().apply {
                    put("id", source.sourceId); put("path", source.privateRelativePath); put("name", source.displayName)
                    put("mime", source.metadata.mime); put("bytes", source.metadata.byteLength); put("sha256", source.metadata.sha256)
                    put("width", source.metadata.width); put("height", source.metadata.height); put("rotation", source.metadata.rotationDeg)
                    put("durationUs", source.metadata.durationUs); put("pixelRatio", source.metadata.pixelWidthHeightRatio)
                }
                db.execSQL("INSERT INTO sources VALUES(?,?,?)", arrayOf(p, source.sourceId, json.toString()))
                stepIds.forEachIndexed { index, stepId ->
                    val asset = assets[index]
                    db.execSQL("INSERT INTO local_assets VALUES(?,?,?,?,?,?,?)", arrayOf(asset.id, p, asset.privateRelativePath,
                        asset.sha256, asset.byteLength, asset.width, asset.height))
                    db.execSQL("INSERT INTO states VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(p, stepId, "legacy-$index", index,
                        "Legacy $index", "Original description", 0, source.sourceId, asset.id, 100_000L, 1_000L, "[]"))
                }
                db.execSQL("INSERT INTO hotspots VALUES(?,?,?,?,?,?,?,?)", arrayOf(p, hotspot.id, stepIds[0], hotspot.label, 0.1f, 0.2f, 0.4f, 0.6f))
                db.execSQL("INSERT INTO edges VALUES(?,?,?,?,?,?)", arrayOf(p, hotspot.edgeId, hotspot.id, stepIds[0], stepIds[1], null))
                db.version = 1
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return ProjectSnapshot(ProjectSummary(p, "Legacy", "Original goal", 9L, 12L, 2, stepIds[0]),
            stepIds.mapIndexed { index, stepId -> ProjectStep(stepId, "Legacy $index", "Original description", index, false,
                assets[index], checkNotNull(input.source), 100_000L, 1_000L, emptyList(), if (index == 0) listOf(hotspot) else emptyList(), "legacy-$index") })
    }

    private suspend fun fixture(context: Context): ReviewedStepInput {
        val sourceId = id()
        val sourceFile = File(context.noBackupFilesDir, "sources/$sourceId.mp4")
        check(sourceFile.parentFile!!.mkdir())
        sourceFile.writeText("Synthetic ownership fixture, not playable media")
        val source = ImportedSource(sourceId, "sources/$sourceId.mp4", "fixture.mp4",
            SourceMetadata("video/mp4", sourceFile.length(), digest(sourceFile), 32, 48, 0, 1_000_000L))
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        return try {
            val output = SafeMediaWriter(context).writePng(bitmap, emptyList(), File(context.noBackupFilesDir, "candidate"))
            ReviewedStepInput(output.file, output.sha256, output.width, output.height, source, 100_000L, 1_000L, emptyList())
        } finally { bitmap.recycle() }
    }

    private fun rejected(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        check(error is IllegalArgumentException || error is IllegalStateException) { "Invalid edit was not explicitly rejected: $error" }
    }
    private fun id(): String = UUID.randomUUID().toString()
    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private val legacyDdl = listOf(
        """CREATE TABLE projects (
                project_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, goal TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, draft_revision INTEGER NOT NULL CHECK(draft_revision>0),
                start_state_id TEXT,
                FOREIGN KEY(project_id,start_state_id) REFERENCES states(project_id,state_id) DEFERRABLE INITIALLY DEFERRED
            )""",
        """CREATE TABLE sources (
                project_id TEXT NOT NULL, source_id TEXT NOT NULL, source_json TEXT NOT NULL,
                PRIMARY KEY(project_id,source_id), FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )""",
        """CREATE TABLE local_assets (
                asset_id TEXT PRIMARY KEY NOT NULL, project_id TEXT NOT NULL, relative_path TEXT UNIQUE NOT NULL,
                sha256 TEXT NOT NULL, byte_length INTEGER NOT NULL CHECK(byte_length>0),
                width INTEGER NOT NULL CHECK(width>0), height INTEGER NOT NULL CHECK(height>0),
                UNIQUE(project_id,asset_id), FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )""",
        """CREATE TABLE states (
                project_id TEXT NOT NULL, state_id TEXT NOT NULL, capture_id TEXT NOT NULL,
                sort_order INTEGER NOT NULL CHECK(sort_order>=0),
                title TEXT NOT NULL, description TEXT NOT NULL, is_terminal INTEGER NOT NULL CHECK(is_terminal IN (0,1)),
                source_id TEXT NOT NULL, input_asset_id TEXT NOT NULL, frame_pts_us INTEGER NOT NULL CHECK(frame_pts_us>=0),
                time_precision_us INTEGER NOT NULL CHECK(time_precision_us>0), masks_json TEXT NOT NULL,
                PRIMARY KEY(project_id,state_id), UNIQUE(project_id,input_asset_id), UNIQUE(project_id,capture_id),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,source_id) REFERENCES sources(project_id,source_id) DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(project_id,input_asset_id) REFERENCES local_assets(project_id,asset_id) DEFERRABLE INITIALLY DEFERRED
            )""",
        """CREATE TABLE hotspots (
                project_id TEXT NOT NULL, hotspot_id TEXT NOT NULL, state_id TEXT NOT NULL, label TEXT NOT NULL,
                rect_left REAL NOT NULL CHECK(rect_left>=0 AND rect_left<1),
                rect_top REAL NOT NULL CHECK(rect_top>=0 AND rect_top<1),
                rect_right REAL NOT NULL CHECK(rect_right>rect_left AND rect_right<=1),
                rect_bottom REAL NOT NULL CHECK(rect_bottom>rect_top AND rect_bottom<=1),
                PRIMARY KEY(project_id,hotspot_id), UNIQUE(project_id,hotspot_id,state_id),
                FOREIGN KEY(project_id,state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE
            )""",
        """CREATE TABLE edges (
                project_id TEXT NOT NULL, edge_id TEXT NOT NULL, hotspot_id TEXT NOT NULL, from_state_id TEXT NOT NULL,
                to_state_id TEXT, end_label TEXT,
                PRIMARY KEY(project_id,edge_id), UNIQUE(project_id,hotspot_id),
                CHECK((to_state_id IS NOT NULL AND end_label IS NULL) OR (to_state_id IS NULL AND end_label IS NOT NULL AND length(trim(end_label))>0)),
                FOREIGN KEY(project_id,hotspot_id,from_state_id) REFERENCES hotspots(project_id,hotspot_id,state_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,from_state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,to_state_id) REFERENCES states(project_id,state_id) DEFERRABLE INITIALLY DEFERRED
            )""",
        """CREATE TABLE asset_cleanup(project_id TEXT NOT NULL, relative_path TEXT PRIMARY KEY NOT NULL)""",
        """CREATE TABLE asset_imports(project_id TEXT NOT NULL, asset_id TEXT PRIMARY KEY NOT NULL)""",
        """CREATE INDEX projects_updated ON projects(updated_at)""",
        """CREATE INDEX states_order ON states(project_id,sort_order)""",
        """CREATE INDEX states_source ON states(source_id)""",
        """CREATE INDEX hotspots_state ON hotspots(project_id,state_id)""",
        """CREATE INDEX edges_target ON edges(project_id,to_state_id)"""
    )
}
