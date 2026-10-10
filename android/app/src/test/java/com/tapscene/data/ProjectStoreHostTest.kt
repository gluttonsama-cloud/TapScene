package com.tapscene.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import com.tapscene.media.OpaqueMask
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowSQLiteConnection

/** Executes production Android helpers and native SQLite. This is not device/power-loss proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProjectStoreHostTest {
    @Before fun configureSqlite() = HostSqlite.configure()

    @Test
    @Config(sdk = [26, 35])
    fun historicalMigrationsAndRollback() {
        AiImportMigrationChecks.migrationsAndRollback(RuntimeEnvironment.getApplication(), ::println, HostSqlite::verify)
    }

    @Test fun rejectsInvalidLegacySchemasAndRelations() {
        AiImportMigrationChecks.rejectInvalidSchemasAndRelations(RuntimeEnvironment.getApplication(), ::println, HostSqlite::verify)
    }

    @Test fun rawPendingFormReopensAndStaleSessionIsRejected() = runBlocking {
        withTimeout(30_000) {
            HostProjectFixture().use { fixture ->
                val store = fixture.store
                val before = fixture.snapshot()
                val a = fixture.draft(fixture.a, "Unsaved A").copy(pendingForm = EditorPendingForm(
                    EditorFormKind.HOTSPOT, objectId = fixture.id(), edgeId = fixture.id(),
                    label = "  未完成\n标签  ", left = "-", top = "not a number", right = "1..2", bottom = "",
                    targetStepId = fixture.b,
                ))
                val b = fixture.draft(fixture.b, "Unsaved B")
                val firstSession = store.beginEditorDraftSession(fixture.project)
                check(store.writeEditorDraft(fixture.project, fixture.a, firstSession, a))
                check(store.writeEditorDraft(fixture.project, fixture.b, firstSession, b))
                check(fixture.snapshot() == before)
                // Close every first-generation handle before reopening the on-disk database.
                fixture.closeStore(store)
                val reopened = fixture.newStore()
                check(reopened.readEditorDrafts(fixture.project) == mapOf(fixture.a to a, fixture.b to b))
                val oldWriter = fixture.newStore()
                val releaseOldWriter = CompletableDeferred<Unit>()
                val lateWrite = async(Dispatchers.IO) {
                    releaseOldWriter.await()
                    oldWriter.writeEditorDraft(fixture.project, fixture.a, firstSession,
                        a.copy(edit = a.edit.copy(title = "Late old write")))
                }
                val session = reopened.beginEditorDraftSession(fixture.project)
                check(session > firstSession)
                releaseOldWriter.complete(Unit)
                check(!lateWrite.await())
                check(!oldWriter.clearEditorDraft(fixture.project, fixture.a, firstSession))
                val staleSave = runCatching { fixture.save(oldWriter, fixture.a, a, firstSession) }.exceptionOrNull()
                check(staleSave is IllegalStateException && staleSave.message.orEmpty().contains("编辑会话已改变"))
                check(!reopened.writeEditorDraft(fixture.project, fixture.id(), session, a))
                check(reopened.readProject(fixture.project) == before)
                check(reopened.readEditorDrafts(fixture.project) == mapOf(fixture.a to a, fixture.b to b))
            }
        }
    }

    @Test fun saveIsAtomicAndPreservesTheOtherStep() {
        HostProjectFixture().use { fixture ->
            val store = fixture.store
            val before = fixture.snapshot()
            val a = fixture.draft(fixture.a, "Saved A").let { draft -> draft.copy(edit = draft.edit.copy(
                description = "Changed description",
                hotspots = draft.edit.hotspots.map { it.copy(label = "Changed action", rect = OpaqueMask(.2f, .2f, .7f, .8f)) },
                nextAction = ProjectNextAction(fixture.id(), "Next B", fixture.b),
            )) }
            val b = fixture.draft(fixture.b, "Other step remains pending")
            val session = store.beginEditorDraftSession(fixture.project)
            check(store.writeEditorDraft(fixture.project, fixture.a, session, a))
            check(store.writeEditorDraft(fixture.project, fixture.b, session, b))
            // saveStepDraft has already changed the graph and deleted its recovery row here.
            fixture.database { execSQL("""CREATE TRIGGER reject_editor_save BEFORE UPDATE OF draft_revision ON projects
                BEGIN SELECT RAISE(ABORT, 'injected editor save failure'); END""") }
            try {
                val failure = runCatching { fixture.save(store, fixture.a, a, session) }.exceptionOrNull()
                check(failure != null && failure.message.orEmpty().contains("injected editor save failure"))
            } finally { fixture.database { execSQL("DROP TRIGGER reject_editor_save") } }
            check(fixture.snapshot() == before) { "Failed save changed formal graph/revision" }
            check(store.readEditorDrafts(fixture.project) == mapOf(fixture.a to a, fixture.b to b))
            val saved = fixture.save(store, fixture.a, a, session)
            check(saved.project.revision == before.project.revision + 1)
            check(saved.steps.single { it.id == fixture.a }.editorFields() == a.edit)
            check(saved.steps.single { it.id == fixture.b } == before.steps.single { it.id == fixture.b })
            check(store.readEditorDrafts(fixture.project) == mapOf(fixture.b to b))
            fixture.closeStore(store)
            val reopened = fixture.newStore()
            check(reopened.readProject(fixture.project) == saved)
            check(reopened.readEditorDrafts(fixture.project) == mapOf(fixture.b to b))
        }
    }
}

internal object HostSqlite {
    fun configure() {
        check(ShadowSQLiteConnection.sqliteMode() == SQLiteMode.Mode.NATIVE)
        // Set before the first open, rather than silently accepting MEMORY/OFF defaults.
        ShadowSQLiteConnection.setDefaultJournalMode("TRUNCATE")
        ShadowSQLiteConnection.setDefaultSyncMode("FULL")
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.noBackupFilesDir, "sqlite-probe-${UUID.randomUUID()}.sqlite")
        try {
            ProjectStore.Database(app, file.path).use { helper ->
                val db = helper.writableDatabase
                verify(db)
                println("HOST_SQLITE api=${Build.VERSION.SDK_INT} sqlite=${value(db, "SELECT sqlite_version()")} " +
                    "mode=NATIVE journal=${value(db, "PRAGMA journal_mode")} sync=${value(db, "PRAGMA synchronous")}")
            }
        } finally { if (file.exists()) check(SQLiteDatabase.deleteDatabase(file)) }
    }

    fun verify(db: SQLiteDatabase) {
        check(value(db, "PRAGMA journal_mode").equals("truncate", ignoreCase = true))
        check(value(db, "PRAGMA synchronous") == "2")
    }

    private fun value(db: SQLiteDatabase, sql: String): String = db.rawQuery(sql, null).use {
        check(it.moveToFirst()); it.getString(0)
    }
}

/** Data-only fixture: actual production schema, no PNG writer/decoder or fake database. */
internal class HostProjectFixture : Closeable {
    val root = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "host-data-${id()}").apply { check(mkdir()) }
    val app = object : Application() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
    }
    private val stores = mutableListOf<ProjectStore>()
    val store = newStore()
    val project = store.createProject("Host data checks").project.id
    val a = id()
    val b = id()

    init {
        database {
            beginTransaction()
            try {
                for ((position, step) in listOf(a, b).withIndex()) {
                    val asset = id()
                    execSQL("INSERT INTO local_assets VALUES(?,?,?,?,1,1,1)",
                        arrayOf(asset, project, "project-assets/$project/$asset.png", "a".repeat(64)))
                    // Missing pixels deliberately keep this suite outside media decoding. The
                    // graph and origin are valid; all assertions concern text/relations/recovery.
                    execSQL("""INSERT INTO states(project_id,state_id,capture_id,sort_order,title,description,is_terminal,
                        input_asset_id,masks_json,origin_kind,evidence_kind,base_asset_id,base_sha256,base_revision,base_width,base_height)
                        VALUES(?,?,?,?,?,'Official text',0,?,'[]','image','authored',?,?,1,1,1)""",
                        arrayOf(project, step, id(), position, if (step == a) "A" else "B", asset, id(), "a".repeat(64)))
                }
                val hotspot = id()
                execSQL("INSERT INTO hotspots VALUES(?,?,?,'Original action',.1,.2,.6,.8)", arrayOf(project, hotspot, a))
                execSQL("INSERT INTO edges VALUES(?,?,?,?,?,NULL)", arrayOf(project, id(), hotspot, a, b))
                execSQL("UPDATE projects SET start_state_id=? WHERE project_id=?", arrayOf(a, project))
                setTransactionSuccessful()
            } finally { endTransaction() }
        }
        check(snapshot().steps.size == 2)
    }

    fun id(): String = UUID.randomUUID().toString()
    fun newStore(): ProjectStore = ProjectStore(app).also { stores += it }
    fun snapshot(): ProjectSnapshot = checkNotNull(store.readProject(project))
    fun draft(stepId: String, title: String): StoredEditorDraft {
        val before = snapshot()
        val fields = before.steps.single { it.id == stepId }.editorFields()
        return StoredEditorDraft(before.project.revision, fields, fields.copy(title = title))
    }
    fun save(store: ProjectStore, stepId: String, draft: StoredEditorDraft, session: Long): ProjectSnapshot =
        store.saveStepDraft(project, stepId, draft.edit.title, draft.edit.description, draft.edit.isTerminal,
            draft.edit.hotspots, expectedRevision = draft.baseRevision, nextAction = draft.edit.nextAction, editorDraftSession = session)
    fun database(block: SQLiteDatabase.() -> Unit) {
        ProjectStore.Database(app, File(root, "projects.sqlite").path).use {
            val db = it.writableDatabase
            HostSqlite.verify(db)
            db.block()
        }
    }
    fun closeStore(store: ProjectStore) {
        (ProjectStore::class.java.getDeclaredField("helper").apply { isAccessible = true }.get(store) as SQLiteOpenHelper).close()
        stores.remove(store)
    }
    override fun close() {
        stores.toList().forEach(::closeStore)
        check(root.deleteRecursively())
    }
}
