package com.tapscene.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID

/** Real SQLiteOpenHelper migrations from frozen historical DDL, never a downgraded new DB. */
internal object AiImportMigrationChecks {
    private val sha = "a".repeat(64)

    fun run(context: Context, status: (String) -> Unit) {
        migrationsAndRollback(context, status)
        rejectInvalidSchemasAndRelations(context, status)
    }

    fun migrationsAndRollback(context: Context, status: (String) -> Unit,
        verifyDatabase: (SQLiteDatabase) -> Unit = {},
    ) = withRoot(context) { root ->
        for (version in 1..7) {
            for (stage in listOf("copy", "drop", "rename", "indexes", "version")) {
                val file = File(root, "v$version-$stage.sqlite")
                seed(file, version, verifyDatabase)
                val before = readSnapshot(file)
                // The wrapper delegates every production callback. Only the last injected
                // failure happens after setting user_version, within the framework transaction.
                var injected = false
                val production = ProjectStore.Database(context, file.path) { point ->
                    if (point == stage) {
                        injected = true
                        error("Injected migration failure after $point")
                    }
                }
                val helper = object : SQLiteOpenHelper(context, file.path, null, 8) {
                    override fun onConfigure(db: SQLiteDatabase) {
                        verifyDatabase(db)
                        production.onConfigure(db)
                    }
                    override fun onCreate(db: SQLiteDatabase) = production.onCreate(db)
                    override fun onOpen(db: SQLiteDatabase) = production.onOpen(db)
                    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        production.onUpgrade(db, oldVersion, newVersion)
                        db.version = newVersion
                        if (stage == "version") {
                            injected = true
                            error("Injected migration failure after version")
                        }
                    }
                }
                try { check(runCatching { helper.writableDatabase }.isFailure) }
                finally { helper.close(); production.close() }
                check(injected) { "v$version failed before the intended $stage checkpoint" }
                check(readSnapshot(file) == before) { "v$version rollback lost schema/data/version after $stage" }
                verifyMigration(context, file, before, verifyDatabase)
            }
        }
        status("PASS Android SQLite v1–v7-to-v8 exact typed fields, graph, image sources, drafts and journals; all five migration rollback points and successful retry")
    }

    fun rejectInvalidSchemasAndRelations(context: Context, status: (String) -> Unit,
        verifyDatabase: (SQLiteDatabase) -> Unit = {},
    ) = withRoot(context) { root ->
        for (objectKind in listOf("index", "changed-index", "trigger", "view", "orphan")) {
            val file = File(root, "reject-$objectKind.sqlite")
            seed(file, 7, verifyDatabase)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                when (objectKind) {
                    "index" -> db.execSQL("CREATE INDEX custom_states ON states(title)")
                    "changed-index" -> {
                        db.execSQL("DROP INDEX states_source")
                        db.execSQL("CREATE INDEX states_source ON states(title)")
                    }
                    "trigger" -> db.execSQL("CREATE TRIGGER custom_states AFTER UPDATE ON states BEGIN SELECT 1; END")
                    "view" -> db.execSQL("CREATE VIEW custom_states AS SELECT * FROM states")
                    else -> {
                        db.setForeignKeyConstraintsEnabled(false)
                        db.execSQL("UPDATE states SET source_id='missing' WHERE state_id='a'")
                    }
                }
            }
            val before = readSnapshot(file)
            val helper = ProjectStore.Database(context, file.path)
            try { check(runCatching { helper.writableDatabase }.isFailure) }
            finally { helper.close() }
            check(readSnapshot(file) == before) { "Rejected $objectKind migration changed historical DB" }
        }
        status("PASS Android v8 unknown indexes/triggers/views and orphan relationships reject without altering schema, rows or version")
        for (reverse in listOf(false, true)) checkPackageSchema(context, File(root, "cycle-$reverse.sqlite"), reverse, verifyDatabase)
        status("PASS Android v8 circular deferred provenance, insertion order, mismatch rejection, safe-image history, durable receipts and byte-bounded draft config")
    }

    private fun withRoot(context: Context, block: (File) -> Unit) {
        val root = File(context.noBackupFilesDir, "ai-migration-checks-${UUID.randomUUID()}")
        check(root.mkdir())
        try { block(root) } finally { check(root.deleteRecursively()) }
    }

    private data class Table(val columns: List<String>, val rows: List<String>)
    private data class Snapshot(val version: Int, val tables: Map<String, Table>, val schema: List<String>)
    private fun readSnapshot(file: File): Snapshot = SQLiteDatabase.openOrCreateDatabase(file, null).use(::snapshot)
    private fun snapshot(db: SQLiteDatabase): Snapshot {
        val tables = linkedMapOf<String, Table>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { names ->
            while (names.moveToNext()) {
                val name = names.getString(0)
                val columns = db.rawQuery("SELECT * FROM \"$name\" LIMIT 0", null).use { it.columnNames.toList() }
                tables[name] = Table(columns, rows(db, name, columns))
            }
        }
        val schema = db.rawQuery("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name", null).use(::typedRows)
        return Snapshot(db.version, tables, schema)
    }
    private fun rows(db: SQLiteDatabase, table: String, columns: List<String>): List<String> =
        db.rawQuery("SELECT ${columns.joinToString(",") { "\"$it\"" }} FROM \"$table\"", null).use(::typedRows)
    private fun typedRows(cursor: Cursor): List<String> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { i ->
            val value = when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> ""
                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i).joinToString("") { "%02x".format(it.toInt() and 255) }
                Cursor.FIELD_TYPE_FLOAT -> java.lang.Double.toHexString(cursor.getDouble(i))
                else -> cursor.getString(i)
            }
            "${cursor.getType(i)}:${value.length}:$value"
        })
    }.sorted()

    private fun seed(file: File, version: Int, verifyDatabase: (SQLiteDatabase) -> Unit) {
        check(!file.exists()) { "Historical v$version fixture must start from a new database file" }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            verifyDatabase(db)
            check(db.version == 0)
            db.setForeignKeyConstraintsEnabled(true)
            transaction(db) {
                LegacyProjectSchema.statements(version).forEach(db::execSQL)
                for (project in listOf("p", "other")) {
                    db.execSQL("INSERT INTO projects VALUES(?,?,?,11,12,9,'a')", arrayOf(project, "Kept $version", "Goal"))
                    db.execSQL("INSERT INTO sources VALUES(?, 'source', 'missing private original retained')", arrayOf(project))
                    for (asset in listOf("a", "b", "c", "clip", "crop")) db.execSQL(
                        "INSERT INTO local_assets VALUES(?,?,?,?,42,20,30)", arrayOf(project + asset, project, "$project/$asset.png", sha))
                    for (state in listOf("a", "b", "c")) {
                        val columns = "project_id,state_id,capture_id,sort_order,title,description,is_terminal,source_id,input_asset_id,frame_pts_us,time_precision_us,masks_json"
                        val fields = arrayOf<Any>(project, state, "capture-$state", 0, "Kept", "  text\n\t  ", 0, "source", project + state, 123, 1000, "[]")
                        db.execSQL(if (version < 6) "INSERT INTO states ($columns) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
                            else "INSERT INTO states ($columns,origin_kind) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'videoFrame')", fields)
                    }
                    if (version >= 6) db.execSQL("UPDATE states SET origin_kind='image',source_id=NULL,frame_pts_us=NULL,time_precision_us=NULL,base_asset_id='old-base',base_sha256=?,base_revision=8,base_width=20,base_height=30 WHERE project_id=? AND state_id='b'", arrayOf(sha, project))
                    if (version == 7) {
                        db.execSQL("INSERT INTO image_sources VALUES(?,?,'image/png','original private image metadata')", arrayOf(project, "$project-raw"))
                        db.execSQL("UPDATE states SET origin_kind='image',source_id=NULL,frame_pts_us=NULL,time_precision_us=NULL,image_source_id=?,evidence_kind='authored' WHERE project_id=? AND state_id='c'", arrayOf("$project-raw", project))
                        db.execSQL("UPDATE states SET evidence_kind='imported' WHERE project_id=? AND state_id='b'", arrayOf(project))
                    }
                    db.execSQL("INSERT INTO hotspots VALUES(?,'h','a','Manual',.1,.2,.6,.8)", arrayOf(project))
                    db.execSQL("INSERT INTO edges VALUES(?,'e','h','a','b',NULL)", arrayOf(project))
                    if (version >= 2) db.execSQL("INSERT INTO next_actions VALUES(?,'n','b','Again','a')", arrayOf(project))
                    if (version >= 3) {
                        db.execSQL("INSERT INTO edge_transitions VALUES(?,'e',?,'source',0,1000,1000,'[]','review')", arrayOf(project, project + "clip"))
                        db.execSQL("INSERT INTO transition_imports VALUES(?,?)", arrayOf(project, "$project-pending-video"))
                    }
                    if (version >= 4) db.execSQL("INSERT INTO regions VALUES(?,'r','a',?,?,'Region',NULL,1,2,3,4,20,30,0,.5,.5,?,123)", arrayOf(project, project + "a", sha, project + "crop"))
                    if (version >= 5) {
                        db.execSQL("INSERT INTO editor_drafts VALUES(?,'a',?)", arrayOf(project, "{\"pendingForm\":{\"left\":\"invalid\",\"label\":\"  unfinished  \"}}"))
                        db.execSQL("INSERT INTO editor_draft_sessions VALUES(?,7)", arrayOf(project))
                    }
                }
                db.execSQL("INSERT INTO asset_cleanup VALUES('deleted','queued.png')")
                db.execSQL("INSERT INTO asset_imports VALUES('p','pending-image')")
                if (version == 7) {
                    db.execSQL("INSERT INTO image_source_imports VALUES('pending-image','pending-source','image/png')")
                    db.execSQL("INSERT INTO image_source_cleanup VALUES('deleted','old-image-source','image/jpeg')")
                }
                db.version = version
            }
        }
    }

    private fun verifyMigration(context: Context, file: File, before: Snapshot, verifyDatabase: (SQLiteDatabase) -> Unit) {
        // Close explicitly: SQLiteOpenHelper is not AutoCloseable on API 26.
        val helper = ProjectStore.Database(context, file.path)
        try {
            val db = helper.writableDatabase
            verifyDatabase(db)
            check(db.version == 8)
            for ((table, expected) in before.tables) check(rows(db, table, expected.columns) == expected.rows) { "Migration altered $table" }
            check(scalar(db, "PRAGMA foreign_keys") == 1L)
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
            db.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) }
            check(scalar(db, "SELECT COUNT(*) FROM states WHERE package_import_id IS NOT NULL") == 0L)
        } finally { helper.close() }
    }

    private fun checkPackageSchema(context: Context, file: File, reverse: Boolean, verifyDatabase: (SQLiteDatabase) -> Unit) {
        // Close explicitly: SQLiteOpenHelper is not AutoCloseable on API 26.
        val helper = ProjectStore.Database(context, file.path)
        try {
            val db = helper.writableDatabase
            verifyDatabase(db)
            transaction(db) {
                for (p in listOf("p", "other")) {
                    db.execSQL("INSERT INTO projects VALUES(?,?,?,1,1,1,NULL)", arrayOf(p,p,"Goal"))
                    db.execSQL("INSERT INTO local_assets VALUES(?,?,?,?,4,1,1)", arrayOf("$p-asset",p,"$p/asset.png",sha))
                }
            }
            rejected(db) { packageState(db) }
            rejected(db) { provenance(db) }
            rejected(db) { packageState(db); provenance(db, importId = "mismatch") }
            rejected(db) { packageState(db); provenance(db, project = "other") }
            transaction(db) {
                if (reverse) { provenance(db); packageState(db) } else { packageState(db); provenance(db) }
            }
            for (change in listOf("package_import_id=NULL", "package_import_id='wrong'", "evidence_kind='recorded'", "source_id='video'",
                "frame_pts_us=0", "time_precision_us=1", "image_source_id='raw'", "base_asset_id='base'", "base_revision=1", "base_width=1", "base_height=1")) {
                rejected(db) { db.execSQL("UPDATE states SET $change WHERE project_id='p'") }
            }
            rejected(db) { db.execSQL("UPDATE package_step_origins SET source_sha256=?", arrayOf(sha + "\u0000suffix")) }
            rejected(db) { db.execSQL("DELETE FROM package_step_origins") }
            for (change in listOf("source_sha256='INVALID'", "declared_kind='videoFrame'", "import_id='wrong'", "source_state_id=''", "source_asset_id=''")) {
                rejected(db) { db.execSQL("UPDATE package_step_origins SET $change") }
            }
            transaction(db) {
                packageState(db, "other"); provenance(db, "other")
                db.execSQL("UPDATE states SET origin_kind='image',package_import_id=NULL,base_asset_id='safe-history',base_sha256=?,base_revision=1,base_width=1,base_height=1 WHERE project_id='p'", arrayOf(sha))
                db.execSQL("INSERT INTO ai_import_sessions VALUES('waiting','preparing','future',NULL,NULL,NULL,1)")
                db.execSQL("INSERT INTO ai_import_sessions VALUES('session','committed','p',?,?,'{}',1)", arrayOf(sha,sha))
                db.execSQL("INSERT INTO draft_ai_configs VALUES('p',1,0,'{}')")
            }
            check(scalar(db, "SELECT COUNT(*) FROM package_step_origins") == 2L)
            check(scalar(db, "SELECT COUNT(*) FROM states WHERE evidence_kind='imported'") == 2L)
            rejected(db) { db.execSQL("UPDATE states SET package_import_id='import' WHERE project_id='p'") }
            rejected(db) { db.execSQL("INSERT INTO ai_import_sessions VALUES('duplicate','preparing','p',NULL,NULL,NULL,1)") }
            for (state in listOf("ready", "committed")) rejected(db) { db.execSQL("UPDATE ai_import_sessions SET state=? WHERE session_id='waiting'", arrayOf(state)) }
            for (field in listOf("input_sha", "preview_digest", "prepared_json")) rejected(db) { db.execSQL("UPDATE ai_import_sessions SET $field=NULL WHERE session_id='session'") }
            for (text in listOf("x".repeat(2097153), "界".repeat(699051))) rejected(db) { db.execSQL("UPDATE ai_import_sessions SET prepared_json=? WHERE session_id='session'", arrayOf(text)) }
            for (change in listOf("bound_revision=0", "needs_repair=2", "config_json=''", "project_id='missing'")) rejected(db) { db.execSQL("UPDATE draft_ai_configs SET $change") }
            for (text in listOf("x".repeat(524289), "界".repeat(174763))) rejected(db) { db.execSQL("UPDATE draft_ai_configs SET config_json=?", arrayOf(text)) }
            transaction(db) { db.execSQL("DELETE FROM projects WHERE project_id='p'") }
            check(scalar(db, "SELECT COUNT(*) FROM package_step_origins WHERE project_id='p'") == 0L)
            check(scalar(db, "SELECT COUNT(*) FROM package_step_origins WHERE project_id='other'") == 1L)
            check(scalar(db, "SELECT COUNT(*) FROM draft_ai_configs") == 0L)
            check(scalar(db, "SELECT COUNT(*) FROM ai_import_sessions WHERE state='committed' AND project_id='p'") == 1L)
            transaction(db) { db.execSQL("DELETE FROM states WHERE project_id='other'") }
            check(scalar(db, "SELECT COUNT(*) FROM package_step_origins") == 0L)
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
        } finally { helper.close() }
    }

    private fun packageState(db: SQLiteDatabase, project: String = "p") = db.execSQL(
        "INSERT INTO states (project_id,state_id,capture_id,sort_order,title,description,is_terminal,input_asset_id,masks_json,origin_kind,evidence_kind,package_import_id) VALUES(?,'s','capture',0,'Imported','Text',1,?,'[]','packageImage','imported','import')", arrayOf(project,"$project-asset"))
    private fun provenance(db: SQLiteDatabase, project: String = "p", importId: String = "import") = db.execSQL(
        "INSERT INTO package_step_origins VALUES(?,'s',?,'external-state','external-asset',?,'recorded')", arrayOf(project,importId,sha))
    private fun scalar(db: SQLiteDatabase, query: String): Long = db.rawQuery(query, null).use { check(it.moveToFirst()); it.getLong(0) }
    private fun transaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun rejected(db: SQLiteDatabase, block: () -> Unit) {
        val before = snapshot(db)
        check(runCatching { transaction(db, block) }.isFailure) { "Invalid v8 SQL accepted" }
        check(snapshot(db) == before) { "Rejected v8 SQL changed persisted data" }
    }
}
