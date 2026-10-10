package com.tapscene.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Real production SQLite/PNG execution. Synthetic raw/transition bytes test persistence only;
 * no video decoding, human review, device lifecycle or power-loss claim is made here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SavedProjectCopyHostTest {
    @Before fun configureHost() {
        HostSqlite.configure()
        HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
    }
    @After fun closeHostDescriptors() = HostFileSyncShadow.reset()

    @Test fun completeSavedGraphMediaAndAiRemainIndependent() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) { fixture { f ->
            AiDraftImportFixtures.reviewDraftRegions(f.store, f.project)
            val release = AiDraftImportFixtures.sealFixture(f.context, f.project, f.input)
            val sealedFiles = tree(File(f.root, "release-store/releases/${release.id}"))
            check(sealedFiles.isNotEmpty())
            f.addMixedOriginsAndMedia()
            var before = f.snapshot()
            val originalConfig = checkNotNull(f.store.readDraftAiConfig(f.project))
            val missingState = id(); val missingEdge = id(); val missingVisit = id(); val missingRegion = id(); val missingHotspot = id()
            val broken = originalConfig.copy(
                visits = originalConfig.visits + RenderPlan.Visit(id(), missingState, missingEdge, 111),
                effects = originalConfig.effects + DraftAiEffect(id(), RenderPlan.Effect("click", missingVisit, 2, 8, missingHotspot, missingRegion, null, null)),
            )
            f.store.saveDraftAiConfig(f.project, before.project.revision, broken)
            before = f.snapshot()
            val config = checkNotNull(f.store.readDraftAiConfig(f.project))
            check(config.needsRepair)
            val sourceWorkspace = WorkspaceStore(f.context, f.project).read()
            val originalRows = f.projectRows(f.project)
            val originalFiles = f.mediaFiles()
            val operation = f.store.beginProjectCopy(f.project, before.project.revision)
            HostFileSyncShadow.beginProductionEvidence()
            val receipt = f.store.copySavedProject(f.project, before.project.revision, operation.operationId)
            HostFileSyncShadow.assertDirectorySyncEvidence()
            check(receipt.status == "committed" && receipt.projectId == operation.operationId)
            check(receipt.sourceProjectId == f.project && receipt.sourceRevision == before.project.revision)
            check(receipt.missingRawSourceCount == 1)
            val copied = checkNotNull(f.store.readProject(receipt.projectId))
            check(copied.project.revision == 1L && copied.project.goal == before.project.goal)
            check(copied.steps.size == before.steps.size && copied.steps.map { it.sortOrder } == before.steps.map { it.sortOrder })
            val ids = before.steps.zip(copied.steps).associate { (old, new) -> old.id to new.id }
            check(copied.project.startStepId == ids[before.project.startStepId])
            check(objectIds(before.steps).intersect(objectIds(copied.steps)).isEmpty())
            val edgeIds = mutableMapOf<String, String>(); val hotspotIds = mutableMapOf<String, String>(); val regionIds = mutableMapOf<String, String>()
            before.steps.zip(copied.steps).forEach { (old, new) ->
                check(new.title == old.title && new.description == old.description && new.isTerminal == old.isTerminal)
                check(new.evidenceKind == old.evidenceKind && new.masks == old.masks)
                independentFile(f.root, old.asset.privateRelativePath, new.asset.privateRelativePath)
                check(new.asset.copy(id = old.asset.id, privateRelativePath = old.asset.privateRelativePath) == old.asset)
                check(f.origins(f.project, old.id) == f.origins(receipt.projectId, new.id))
                when (val origin = old.origin) {
                    is StepOrigin.PackageSafeImage -> check((new.origin as StepOrigin.PackageSafeImage).copy(localStepId = old.id) == origin)
                    is StepOrigin.Image -> {
                        val base = (new.origin as StepOrigin.Image).base
                        check(base.projectId == receipt.projectId && base.stepId == new.id && base.assetId != origin.base.assetId)
                        check(base.sha256 == origin.base.sha256 && base.width == origin.base.width && base.height == origin.base.height)
                    }
                    is StepOrigin.ImportedImage -> {
                        val image = (new.origin as StepOrigin.ImportedImage).source
                        check(image.sourceId != origin.source.sourceId && image.metadata == origin.source.metadata)
                        independentFile(f.root, origin.source.privateRelativePath, image.privateRelativePath)
                    }
                    is StepOrigin.VideoFrame -> {
                        val video = new.origin as StepOrigin.VideoFrame
                        check(video.frameTimeUs == origin.frameTimeUs && video.timePrecisionUs == origin.timePrecisionUs)
                        check(video.source.sourceId != origin.source.sourceId && video.source.metadata == origin.source.metadata)
                        independentFile(f.root, origin.source.privateRelativePath, video.source.privateRelativePath)
                    }
                }
                check(new.hotspots.size == old.hotspots.size)
                old.hotspots.forEach { a ->
                    val b = new.hotspots.single { it.label == a.label }
                    edgeIds[a.edgeId] = b.edgeId; hotspotIds[a.id] = b.id
                    check(b.targetStepId == a.targetStepId?.let(ids::get) && b.endLabel == a.endLabel && b.label == a.label && b.rect == a.rect)
                    compareTransition(f.root, a.transition, b.transition)
                }
                check((old.nextAction == null) == (new.nextAction == null))
                old.nextAction?.let { a -> val b = checkNotNull(new.nextAction)
                    edgeIds[a.id] = b.id
                    check(b.label == a.label && b.targetStepId == a.targetStepId?.let(ids::get))
                    compareTransition(f.root, a.transition, b.transition)
                }
                check(new.regions.size == old.regions.size)
                old.regions.forEach { a ->
                    val b = new.regions.single { it.name == a.name }
                    regionIds[a.id] = b.id
                    check(b.stateId == new.id && b.reviewedAt == null && b.baseAssetId != a.baseAssetId)
                    check(b.matchesBase(new.asset) == a.matchesBase(old.asset))
                    check(b.copy(id = a.id, stateId = a.stateId, baseAssetId = a.baseAssetId,
                        asset = a.asset, reviewedAt = a.reviewedAt) == a)
                    check((a.asset == null) == (b.asset == null))
                    if (a.asset != null) independentFile(f.root, a.asset.privateRelativePath, checkNotNull(b.asset).privateRelativePath)
                }
            }
            val newConfig = checkNotNull(f.store.readDraftAiConfig(receipt.projectId))
            check(newConfig.needsRepair && newConfig.boundRevision == copied.project.revision)
            check(newConfig.width == config.width && newConfig.height == config.height)
            check(newConfig.visits.size == config.visits.size && newConfig.effects.size == config.effects.size)
            val visitIds = config.visits.zip(newConfig.visits).associate { (a, b) -> a.visitId to b.visitId }
            config.visits.zip(newConfig.visits).forEach { (a, b) ->
                check(b.visitId != a.visitId && b.holdFrames == a.holdFrames && b.stateId != a.stateId)
                if (a.stateId in ids) check(b.stateId == ids[a.stateId]) else check(b.stateId !in ids.values)
                if (a.selectedEdgeId in edgeIds) check(b.selectedEdgeId == edgeIds[a.selectedEdgeId])
                else if (a.selectedEdgeId != null) check(b.selectedEdgeId != a.selectedEdgeId && b.selectedEdgeId !in edgeIds.values)
                else check(b.selectedEdgeId == null)
            }
            config.effects.zip(newConfig.effects).forEach { (a, b) ->
                check(b.id != a.id)
                val av = a.value; val bv = b.value
                check(bv.type == av.type && bv.startFrame == av.startFrame && bv.durationFrames == av.durationFrames && bv.text == av.text)
                check((bv.rect == null) == (av.rect == null))
                if (av.rect != null) check(listOf(bv.rect.x, bv.rect.y, bv.rect.width, bv.rect.height) ==
                    listOf(av.rect.x, av.rect.y, av.rect.width, av.rect.height))
                if (av.visitId in visitIds) check(bv.visitId == visitIds[av.visitId]) else check(bv.visitId != av.visitId && bv.visitId !in visitIds.values)
                if (av.hotspotId in hotspotIds) check(bv.hotspotId == hotspotIds[av.hotspotId])
                else if (av.hotspotId != null) check(bv.hotspotId != av.hotspotId && bv.hotspotId !in hotspotIds.values)
                if (av.regionId in regionIds) check(bv.regionId == regionIds[av.regionId])
                else if (av.regionId != null) check(bv.regionId != av.regionId && bv.regionId !in regionIds.values)
            }
            check(f.store.draftAiIssues(receipt.projectId, newConfig).isNotEmpty())
            val copiedWorkspace = WorkspaceStore(f.context, receipt.projectId).read()
            check(copiedWorkspace.size == 3)
            sourceWorkspace.forEach { old ->
                val new = copiedWorkspace.single { it.source.displayName == old.source.displayName }
                check(new.frameTimeUs == old.frameTimeUs && new.masks == old.masks && new.source.metadata == old.source.metadata)
                independentFile(f.root, old.source.privateRelativePath, new.source.privateRelativePath)
            }
            val missing = copiedWorkspace.single { it.source.displayName == "Missing transition original" }
            check(!File(f.root, missing.source.privateRelativePath).exists())
            check(f.store.readEditorDrafts(receipt.projectId).isEmpty())
            check(f.snapshot() == before && f.projectRows(f.project) == originalRows)
            originalFiles.forEach { (path, sha) -> check(f.mediaFiles()[path] == sha) }
            check(tree(File(f.root, "release-store/releases/${release.id}")) == sealedFiles)

            // Delete one destination first; then delete the source while a second copy survives.
            f.store.acknowledgeProjectCopy(operation.operationId)
            val secondOp = f.store.beginProjectCopy(f.project, before.project.revision)
            val secondReceipt = f.store.copySavedProject(f.project, before.project.revision, secondOp.operationId)
            val survivor = checkNotNull(f.store.readProject(secondReceipt.projectId))
            val survivorFiles = f.projectMedia(survivor)
            f.store.deleteProject(receipt.projectId)
            check(f.snapshot() == before && f.projectRows(f.project) == originalRows)
            f.store.deleteProject(f.project)
            check(f.store.readProject(secondReceipt.projectId) == survivor && f.projectMedia(survivor) == survivorFiles)
            check(f.store.readDraftAiConfig(secondReceipt.projectId) != null)
            check(f.store.copySavedProject(f.project, before.project.revision, operation.operationId) == receipt)
            check(f.store.readProject(receipt.projectId) == null) // A consumed receipt never recreates deleted work.
            f.store.acknowledgeProjectCopy(operation.operationId)
            check(f.store.readProjectCopy(operation.operationId)?.status == "committed")
            f.assertForeignKeys()
            println("HOST_SAVED_PROJECT_COPY graph: complete branches/self-loop/start/origins/media/AI remapped; stale references retained; review reset; source and destination deletion independent; sealed bytes unchanged")
        } }
    }

    @Test fun failuresRetriesAndUncertainDeliveryDoNotDuplicateOrLoseFiles() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) { fixture { f -> coroutineScope {
            val before = f.snapshot()
            val file = f.store.resolveAsset(f.project, f.input.startId)
            val bytes = file.readBytes()
            val session = f.store.beginEditorDraftSession(f.project)
            val first = before.steps.first()
            val dirty = StoredEditorDraft(before.project.revision, first.editorFields(), first.editorFields(),
                EditorPendingForm(EditorFormKind.HOTSPOT, label = "Unsaved panel", left = "-."))
            check(f.store.writeEditorDraft(f.project, first.id, session, dirty))
            check(runCatching { f.store.beginProjectCopy(f.project, before.project.revision) }.isFailure)
            check(f.store.readEditorDrafts(f.project) == mapOf(first.id to dirty) && f.snapshot() == before)
            check(f.store.clearEditorDraft(f.project, first.id, session))
            val invalidated = f.store.beginProjectCopy(f.project, before.project.revision)
            check(f.store.writeEditorDraft(f.project, first.id, session, dirty))
            check(runCatching { f.store.copySavedProject(f.project, before.project.revision, invalidated.operationId) }.isFailure)
            check(f.store.readEditorDrafts(f.project) == mapOf(first.id to dirty) && f.snapshot() == before)
            check(f.store.readProjectCopy(invalidated.operationId)?.status == "aborted")
            check(f.store.clearEditorDraft(f.project, first.id, session))
            val operation = f.store.beginProjectCopy(f.project, before.project.revision)
            suspend fun rejectedUnchanged() {
                val media = f.mediaFiles()
                check(runCatching { f.store.copySavedProject(f.project, before.project.revision, operation.operationId) }.isFailure)
                check(f.snapshot() == before && f.store.listProjects().size == 1 && f.mediaFiles() == media)
                check(f.store.readProjectCopy(operation.operationId)?.status != "committed")
                f.assertForeignKeys(); f.assertNoCopyResidue()
            }
            check(file.delete()); rejectedUnchanged()
            file.writeBytes(bytes + byteArrayOf(1)); rejectedUnchanged()
            file.writeBytes(bytes)
            for (commitFailure in listOf(false, true)) {
                f.database {
                    // Corrupt the deferred FK only after graph validation and receipt update.
                    // An INSERT-project trigger fires before installProjectCopy's explicit
                    // start-state assertion and therefore does not exercise COMMIT failure.
                    execSQL(if (commitFailure) """CREATE TRIGGER reject_project_copy AFTER UPDATE OF status ON project_copy_operations
                        WHEN NEW.operation_id='${operation.operationId}' AND OLD.status='preparing' AND NEW.status='committed' BEGIN
                        UPDATE projects SET start_state_id='${id()}' WHERE project_id=NEW.project_id; END"""
                        else """CREATE TRIGGER reject_project_copy BEFORE INSERT ON projects
                        WHEN NEW.project_id='${operation.projectId}' BEGIN SELECT RAISE(ABORT,'injected project copy failure'); END""")
                }
                val media = f.mediaFiles()
                val failure = runCatching { f.store.copySavedProject(f.project, before.project.revision, operation.operationId) }.exceptionOrNull()
                check(failure != null)
                if (commitFailure) {
                    check(failure is SQLiteConstraintException && failure.message.orEmpty().contains("FOREIGN KEY", true)) {
                        "Expected deferred-FK COMMIT failure after receipt update, got ${failure.javaClass.name}: ${failure.message}"
                    }
                    check(failure.stackTrace.any { it.methodName == "endTransaction" }) {
                        "Foreign-key rejection did not reach the production COMMIT boundary"
                    }
                }
                check(f.snapshot() == before && f.store.listProjects().size == 1 && f.mediaFiles() == media)
                f.database { execSQL("DROP TRIGGER reject_project_copy") }
                f.assertNoCopyResidue()
                if (commitFailure) println("HOST_SAVED_PROJECT_COPY deferred_commit: receipt-update injection reached endTransaction; source, project count and bytes unchanged; exact journal cleaned")
            }
            val cancelledOp = f.store.beginProjectCopy(f.project, before.project.revision)
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                f.store.copySavedProject(f.project, before.project.revision, cancelledOp.operationId)
            }
            check(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
            check(f.store.listProjects().size == 1)
            val secondStore = AiDraftImportFixtures.projectStore(f.context)
            val start = CompletableDeferred<Unit>()
            val racers = listOf(f.store, secondStore).map { store -> async(Dispatchers.IO) {
                start.await(); runCatching { store.copySavedProject(f.project, before.project.revision, operation.operationId) }
            } }
            start.complete(Unit)
            val outcomes = racers.map { it.await() }
            check(outcomes.any { it.isSuccess })
            val receipt = secondStore.copySavedProject(f.project, before.project.revision, operation.operationId)
            outcomes.filter { it.isSuccess }.forEach { check(it.getOrThrow() == receipt) }
            check(receipt.status == "committed")
            val media = f.mediaFiles()
            check(secondStore.copySavedProject(f.project, before.project.revision, operation.operationId) == receipt)
            check(f.mediaFiles() == media && f.store.listProjects().size == 2)

            // Block result delivery after a separate store has observed the durable commit.
            f.store.acknowledgeProjectCopy(operation.operationId)
            val uncertainOp = f.store.beginProjectCopy(f.project, before.project.revision)
            val committed = CountDownLatch(1)
            var returned = false
            val observer = async(Dispatchers.IO) {
                withTimeout(10_000) { while (secondStore.readProject(uncertainOp.projectId) == null) delay(1) }
                committed.countDown()
            }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
                val copying = withContext(caller) {
                    val pending = async(start = CoroutineStart.UNDISPATCHED) {
                        withContext(Dispatchers.IO) { f.store.copySavedProject(f.project, before.project.revision, uncertainOp.operationId) }
                        returned = true
                    }
                    try { check(committed.await(10, TimeUnit.SECONDS)) } finally { pending.cancel() }
                    pending
                }
                observer.await()
                check(runCatching { copying.await() }.exceptionOrNull() is CancellationException && !returned)
            }
            val committedFiles = f.mediaFiles()
            // A reversible schema fault makes receipt reconciliation genuinely unavailable.
            f.database { execSQL("ALTER TABLE project_copy_operations RENAME TO copy_test_hidden_receipts") }
            try {
                check(runCatching { f.store.readProjectCopy(uncertainOp.operationId) }.isFailure)
                check(f.mediaFiles() == committedFiles)
            } finally { f.database { execSQL("ALTER TABLE copy_test_hidden_receipts RENAME TO project_copy_operations") } }
            val recovered = secondStore.copySavedProject(f.project, before.project.revision, uncertainOp.operationId)
            check(recovered.status == "committed" && f.store.listProjects().size == 3 && f.mediaFiles() == committedFiles)
            f.store.acknowledgeProjectCopy(uncertainOp.operationId)
            check(f.store.readProjectCopy(uncertainOp.operationId)?.status == "committed")

            // Simulate only the durable state of an interrupted preparation. Recovery may
            // delete these exact journal-owned paths, never a neighbouring unowned raw file.
            val empty = f.store.createProject("Empty source", "No invented sample graph")
            val abandoned = f.store.beginProjectCopy(empty.project.id, empty.project.revision)
            val ownedId = id()
            val ownedOutput = File(f.root, "project-assets/${abandoned.projectId}/$ownedId.png")
            val ownedPart = File(f.root, "project-copy-staging/${abandoned.operationId}/$ownedId.part")
            val untouched = File(f.root, "sources/${id()}.mp4").apply { parentFile!!.mkdirs(); writeText("Unowned sentinel") }
            f.database {
                execSQL("INSERT INTO project_copy_files(operation_id,file_id,kind) VALUES(?,?,'asset_png')", arrayOf(abandoned.operationId, ownedId))
                execSQL("UPDATE project_copy_operations SET workspace_owned=1 WHERE operation_id=?", arrayOf(abandoned.operationId))
            }
            ownedOutput.apply { parentFile!!.mkdirs(); writeText("Owned interrupted final bytes") }
            ownedPart.apply { parentFile!!.mkdirs(); writeText("Owned interrupted temporary bytes") }
            WorkspaceStore(f.context, abandoned.projectId).write(emptyList())
            check(secondStore.readProjectCopy(abandoned.operationId)?.status == "preparing")
            check(!ownedOutput.exists() && !ownedPart.exists() && untouched.readText() == "Unowned sentinel")
            check(!File(f.root, "project-media-${abandoned.projectId}.json").exists())
            check(WorkspaceStore.retainedWorkspaces(f.context, f.store.listProjects().map { it.id }.toSet()).none { it.projectId == abandoned.projectId })
            val emptyCopy = secondStore.copySavedProject(empty.project.id, empty.project.revision, abandoned.operationId)
            check(emptyCopy.status == "committed" && checkNotNull(f.store.readProject(emptyCopy.projectId)).steps.isEmpty())
            check(WorkspaceStore(f.context, emptyCopy.projectId).read().isEmpty())
            f.assertForeignKeys()
            println("HOST_SAVED_PROJECT_COPY recovery: missing/changed safe PNG rejected; statement/deferred-commit rollback; same-operation race/retry exactly once; cancellation before/after commit; unavailable receipt read preserves committed bytes")
        } } }
    }

    @Test fun frozenVersionEightMigratesWithoutChangingExistingData() = runBlocking(Dispatchers.IO) {
        AiDraftImportFixtures.isolated(RuntimeEnvironment.getApplication(), "project-copy-migration") { context ->
            val path = File(context.noBackupFilesDir, "frozen-v8.sqlite")
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                HostSqlite.verify(db); db.setForeignKeyConstraintsEnabled(true)
                transaction(db) {
                    frozenV8Schema().forEach(db::execSQL)
                    for (project in listOf("first", "second")) {
                        db.execSQL("INSERT INTO projects VALUES(?,?,?,11,12,9,'a')", arrayOf(project, "Saved title", "Saved goal"))
                        db.execSQL("INSERT INTO sources VALUES(?,'video','exact raw metadata')", arrayOf(project))
                        db.execSQL("INSERT INTO image_sources VALUES(?,?,'image/png','exact screenshot metadata')", arrayOf(project, "$project-image"))
                        for (asset in listOf("a", "b", "clip", "crop")) db.execSQL("INSERT INTO local_assets VALUES(?,?,?,?,42,32,48)",
                            arrayOf(project + asset, project, "$project/$asset.png", "a".repeat(64)))
                        db.execSQL("INSERT INTO package_step_origins VALUES(?,'a','import','external-state','external-asset',?,'recorded')", arrayOf(project, "a".repeat(64)))
                        db.execSQL("""INSERT INTO states(project_id,state_id,capture_id,sort_order,title,description,is_terminal,input_asset_id,masks_json,origin_kind,evidence_kind,package_import_id)
                            VALUES(?,'a','capture-a',0,'Saved A','  exact\ntext  ',0,?,'[]','packageImage','imported','import')""", arrayOf(project, project + "a"))
                        db.execSQL("""INSERT INTO states(project_id,state_id,capture_id,sort_order,title,description,is_terminal,input_asset_id,masks_json,origin_kind,evidence_kind,image_source_id)
                            VALUES(?,'b','capture-b',1,'Saved B','',1,?,'[]','image','authored',?)""", arrayOf(project, project + "b", "$project-image"))
                        db.execSQL("INSERT INTO hotspots VALUES(?,'hotspot','a','Again',.1,.2,.6,.8)", arrayOf(project))
                        db.execSQL("INSERT INTO edges VALUES(?,'edge','hotspot','a','a',NULL)", arrayOf(project))
                        db.execSQL("INSERT INTO next_actions VALUES(?,'next','a','Continue','b')", arrayOf(project))
                        db.execSQL("INSERT INTO edge_transitions VALUES(?,'edge',?,'video',0,1000,1000,'[]','review')", arrayOf(project, project + "clip"))
                        db.execSQL("INSERT INTO regions VALUES(?,'region','a',?,?,'Crop','Group',1,2,3,4,32,48,-2,.25,.75,?,123)",
                            arrayOf(project, project + "a", "a".repeat(64), project + "crop"))
                        db.execSQL("INSERT INTO editor_drafts VALUES(?,'a','{\"raw\":\"-.\"}')", arrayOf(project))
                        db.execSQL("INSERT INTO editor_draft_sessions VALUES(?,7)", arrayOf(project))
                        db.execSQL("INSERT INTO draft_ai_configs VALUES(?,9,1,'{\"unresolved\":true}')", arrayOf(project))
                    }
                    db.execSQL("INSERT INTO ai_import_sessions VALUES('receipt','committed','first',?,?,'{}',123)", arrayOf("a".repeat(64), "b".repeat(64)))
                    db.execSQL("INSERT INTO asset_imports VALUES('first','pending')")
                    db.execSQL("INSERT INTO transition_imports VALUES('first','pending-clip')")
                    db.execSQL("INSERT INTO image_source_imports VALUES('pending','pending-source','image/png')")
                    db.execSQL("INSERT INTO asset_cleanup VALUES('deleted','queued.png')")
                    db.execSQL("INSERT INTO image_source_cleanup VALUES('deleted','queued-source','image/jpeg')")
                    db.version = 8
                }
            }
            val before = SQLiteDatabase.openOrCreateDatabase(path, null).use(::databaseRows)
            val schema = SQLiteDatabase.openOrCreateDatabase(path, null).use(::databaseSchema)
            val helper = ProjectStore.Database(context, path.path)
            try {
                val db = helper.writableDatabase
                HostSqlite.verify(db); check(db.version == 9)
                val after = databaseRows(db)
                before.forEach { (table, rows) -> check(after[table] == rows) { "v8 migration changed $table" } }
                val newSchema = databaseSchema(db)
                schema.forEach { (name, sql) -> check(newSchema[name] == sql) { "v8 migration rewrote $name" } }
                check(after.keys - before.keys == setOf("project_copy_operations", "project_copy_files"))
                check(after["project_copy_operations"].orEmpty().isEmpty() && after["project_copy_files"].orEmpty().isEmpty())
                db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
            } finally { helper.close() }
            println("HOST_SAVED_PROJECT_COPY migration: frozen shipped v8 DDL to v9; exact graph/origins/AI/review/editor/journal values and original schema preserved; only two additive empty tables")
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) =
        AiDraftImportFixtures.isolated(RuntimeEnvironment.getApplication(), "saved-project-copy") { context ->
            val input = AiDraftImportFixtures.complete(context)
            val imports = AiDraftImportFixtures.importStore(context)
            val prepared = input.zip.inputStream().use { imports.prepare(it) }
            val project = checkNotNull(imports.commit(prepared.sessionId, prepared.previewDigest).projectId)
            block(Fixture(context, AiDraftImportFixtures.projectStore(context), project, input))
        }

    private class Fixture(val context: Context, val store: ProjectStore, val project: String,
        val input: AiDraftImportFixtures.Fixture) {
        val root: File get() = context.noBackupFilesDir
        fun snapshot() = checkNotNull(store.readProject(project))
        fun database(block: SQLiteDatabase.() -> Unit) {
            val helper = ProjectStore.Database(context, File(root, "projects.sqlite").path)
            try { helper.writableDatabase.let { HostSqlite.verify(it); it.block() } } finally { helper.close() }
        }
        fun projectRows(projectId: String): Map<String, List<List<String>>> {
            var result = emptyMap<String, List<List<String>>>()
            database { result = listOf("projects", "states", "sources", "image_sources", "local_assets", "hotspots", "edges", "next_actions",
                "edge_transitions", "regions", "package_step_origins", "draft_ai_configs", "editor_drafts", "editor_draft_sessions").associateWith { table ->
                rawQuery("SELECT * FROM $table WHERE project_id=? ORDER BY rowid", arrayOf(projectId)).use(::typedRows)
            } }
            return result
        }
        fun origins(owner: String, step: String): List<List<String>> {
            var result = emptyList<List<String>>()
            database { result = rawQuery("SELECT import_id,source_state_id,source_asset_id,source_sha256,declared_kind FROM package_step_origins WHERE project_id=? AND state_id=?",
                arrayOf(owner, step)).use(::typedRows) }
            return result
        }
        fun mediaFiles() = root.walkTopDown().filter { it.isFile &&
            (it.relativeTo(root).path.startsWith("project-assets/") || it.relativeTo(root).path.startsWith("sources/") ||
                it.relativeTo(root).path.startsWith("image-sources/") || it.name.startsWith("project-media-"))
        }.associate { it.relativeTo(root).path to ViewerPackageCodec.sha256(it) }
        fun projectMedia(snapshot: ProjectSnapshot): Map<String, String> {
            val paths = snapshot.steps.flatMap { step ->
                listOf(step.asset.privateRelativePath) + step.regions.mapNotNull { it.asset?.privateRelativePath } +
                    (step.hotspots.mapNotNull { it.transition } + listOfNotNull(step.nextAction?.transition)).map { it.asset.privateRelativePath } +
                    listOfNotNull((step.origin as? StepOrigin.ImportedImage)?.source?.privateRelativePath, step.source?.privateRelativePath)
            } + WorkspaceStore(context, snapshot.project.id).read().map { it.source.privateRelativePath }
            return paths.distinct().associateWith { path -> File(root, path).let { if (it.exists()) ViewerPackageCodec.sha256(it) else "absent" } }
        }
        fun assertForeignKeys() = database { rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) } }
        fun assertNoCopyResidue() {
            check(File(root, "project-copy-staging").walkTopDown().none { it.isFile })
            database { rawQuery("SELECT COUNT(*) FROM project_copy_files", null).use { check(it.moveToFirst() && it.getInt(0) == 0) } }
        }
        suspend fun addMixedOriginsAndMedia() {
            val safeStep = id()
            store.copySavedStep(project, input.startId, snapshot().project.revision, safeStep)
            val saved = snapshot(); val step = saved.steps.single { it.id == safeStep }; val binding = step.safeImageBinding(saved.project)
            val bitmap = store.readSafeImageBase(binding); val masks = listOf(OpaqueMask(.2f, .2f, .5f, .5f))
            val png = try { SafeMediaWriter(context).writePng(bitmap, masks, File(root, "redacted-${id()}")) } finally { bitmap.recycle() }
            store.replaceReviewedStep(project, safeStep, saved.project.revision,
                ReviewedStepInput(png.file, png.sha256, png.width, png.height, StepOrigin.Image(binding), masks, id()))
            val video = raw("Graph recording", present = true)
            val workspaceOnly = raw("Unused recording", present = true)
            val missing = raw("Missing transition original", present = false)
            WorkspaceStore(context, project).write(listOf(SourceDraft(video, 123_000, masks), SourceDraft(workspaceOnly, 456_000)))
            val imageId = id(); val imagePath = "image-sources/$imageId/original.png"
            val imageFile = File(root, imagePath).apply { check(parentFile!!.mkdirs()) }
            store.resolveAsset(project, input.endId).copyTo(imageFile)
            val imageJson = JSONObject().put("id", imageId).put("path", imagePath).put("name", "Original screenshot")
                .put("mime", "image/png").put("bytes", imageFile.length()).put("sha256", ViewerPackageCodec.sha256(imageFile))
                .put("width", 32).put("height", 48).put("orientation", 1).put("outputWidth", 32).put("outputHeight", 48)
            database { transaction(this) {
                execSQL("INSERT INTO image_sources VALUES(?,?,'image/png',?)", arrayOf(project, imageId, imageJson.toString()))
                execSQL("""UPDATE states SET origin_kind='image',evidence_kind='authored',package_import_id=NULL,image_source_id=?
                    WHERE project_id=? AND state_id=?""", arrayOf(imageId, project, input.endId))
                for (source in listOf(video, missing)) execSQL("INSERT INTO sources VALUES(?,?,?)", arrayOf(project, source.sourceId, sourceJson(source).toString()))
                execSQL("""UPDATE states SET origin_kind='videoFrame',evidence_kind='recorded',package_import_id=NULL,source_id=?,frame_pts_us=123000,time_precision_us=1000
                    WHERE project_id=? AND state_id=?""", arrayOf(video.sourceId, project, input.branchId))
                val transitionId = id(); val path = "project-assets/$project/$transitionId.mp4"
                val clip = File(root, path).apply { writeText("Synthetic transition persistence bytes") }
                execSQL("INSERT INTO local_assets VALUES(?,?,?,?,?,32,48)", arrayOf(transitionId, project, path, ViewerPackageCodec.sha256(clip), clip.length()))
                execSQL("INSERT INTO edge_transitions VALUES(?,?,?,?,100000,900000,800000,'[]',?)", arrayOf(project, input.loopEdgeId, transitionId, missing.sourceId, id()))
            } }
        }
        private fun raw(name: String, present: Boolean): ImportedSource {
            val source = id(); val path = "sources/$source.mp4"
            val file = File(root, path).apply { parentFile!!.mkdirs() }
            val bytes = "Synthetic private source: $name".toByteArray()
            file.writeBytes(bytes); val sha = ViewerPackageCodec.sha256(file)
            if (!present) check(file.delete())
            return ImportedSource(source, path, name, SourceMetadata("video/mp4", bytes.size.toLong(), sha, 32, 48, 90, 1_000_000, 1.25f))
        }
    }

    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun tree(directory: File) = directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).path to ViewerPackageCodec.sha256(it) }
        private fun independentFile(root: File, original: String, copied: String) {
            check(original != copied)
            check(File(root, original).readBytes().contentEquals(File(root, copied).readBytes()))
        }
        private fun compareTransition(root: File, original: ProjectTransition?, copied: ProjectTransition?) {
            check((original == null) == (copied == null))
            if (original == null) return
            val new = checkNotNull(copied)
            check(new.asset.id != original.asset.id && new.reviewId != original.reviewId && new.source.sourceId != original.source.sourceId)
            check(new.source.privateRelativePath != original.source.privateRelativePath && new.source.metadata == original.source.metadata)
            check(new.startUs == original.startUs && new.endUs == original.endUs && new.masks == original.masks)
            check(new.asset.copy(id = original.asset.id, privateRelativePath = original.asset.privateRelativePath) == original.asset)
            independentFile(root, original.asset.privateRelativePath, new.asset.privateRelativePath)
        }
        private fun objectIds(steps: List<ProjectStep>) = steps.flatMap { step ->
            listOf(step.id, step.captureId, step.asset.id) + step.hotspots.flatMap { listOf(it.id, it.edgeId) } +
                listOfNotNull(step.nextAction?.id) + step.regions.flatMap { listOfNotNull(it.id, it.asset?.id) } +
                (step.hotspots.mapNotNull { it.transition } + listOfNotNull(step.nextAction?.transition)).flatMap { listOf(it.asset.id, it.reviewId, it.source.sourceId) } +
                listOfNotNull(step.source?.sourceId, (step.origin as? StepOrigin.ImportedImage)?.source?.sourceId)
        }.toSet()
        private fun sourceJson(source: ImportedSource) = JSONObject().put("id", source.sourceId).put("path", source.privateRelativePath).put("name", source.displayName)
            .put("mime", source.metadata.mime).put("bytes", source.metadata.byteLength).put("sha256", source.metadata.sha256)
            .put("width", source.metadata.width).put("height", source.metadata.height).put("rotation", source.metadata.rotationDeg)
            .put("durationUs", source.metadata.durationUs).put("pixelRatio", source.metadata.pixelWidthHeightRatio)
        private fun transaction(db: SQLiteDatabase, block: () -> Unit) {
            db.beginTransaction()
            try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
        private fun typedRows(cursor: Cursor): List<List<String>> = buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                val value = when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> ""
                    Cursor.FIELD_TYPE_FLOAT -> java.lang.Double.toHexString(cursor.getDouble(index))
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { "%02x".format(it.toInt() and 255) }
                    else -> cursor.getString(index)
                }
                "${cursor.getType(index)}:${value.length}:$value"
            })
        }
        private fun databaseRows(db: SQLiteDatabase): Map<String, List<List<String>>> =
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor ->
                buildMap { while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    put(name, db.rawQuery("SELECT * FROM \"$name\" ORDER BY rowid", null).use(::typedRows))
                } }
            }
        private fun databaseSchema(db: SQLiteDatabase): Map<String, String?> =
            db.rawQuery("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1)) }
            }
    }
    // Frozen shipped v8 DDL from 33b372f. Never downgrade a newer database to fake migration.
    private fun frozenV8Schema(): List<String> = LegacyProjectSchema.statements(7).map {
        if (it.startsWith("CREATE TABLE states (")) frozenV8States else it
    } + frozenV8AiTables

    private val frozenV8States = """CREATE TABLE states (
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
    private val frozenV8AiTables = listOf(
        """CREATE TABLE package_step_origins (
                project_id TEXT NOT NULL, state_id TEXT NOT NULL, import_id TEXT NOT NULL CHECK(length(import_id)>0),
                source_state_id TEXT NOT NULL CHECK(length(source_state_id)>0),
                source_asset_id TEXT NOT NULL CHECK(length(source_asset_id)>0),
                source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND length(CAST(source_sha256 AS BLOB))=64 AND source_sha256 NOT GLOB '*[^0-9a-f]*'),
                declared_kind TEXT NOT NULL CHECK(declared_kind IN ('recorded','authored','imported')),
                PRIMARY KEY(project_id,state_id), UNIQUE(project_id,state_id,import_id),
                FOREIGN KEY(project_id,state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
            )""",
        """CREATE TABLE ai_import_sessions (
                session_id TEXT PRIMARY KEY NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('preparing','ready','committed','cancelled','failed')),
                project_id TEXT NOT NULL UNIQUE,
                input_sha TEXT CHECK(input_sha IS NULL OR (length(input_sha)=64 AND length(CAST(input_sha AS BLOB))=64 AND input_sha NOT GLOB '*[^0-9a-f]*')),
                preview_digest TEXT CHECK(preview_digest IS NULL OR (length(preview_digest)=64 AND length(CAST(preview_digest AS BLOB))=64 AND preview_digest NOT GLOB '*[^0-9a-f]*')),
                prepared_json TEXT CHECK(prepared_json IS NULL OR length(CAST(prepared_json AS BLOB)) BETWEEN 1 AND 2097152),
                created_at INTEGER NOT NULL,
                CHECK(state NOT IN ('ready','committed') OR (input_sha IS NOT NULL AND preview_digest IS NOT NULL AND prepared_json IS NOT NULL))
            )""",
        """CREATE TABLE draft_ai_configs (
                project_id TEXT PRIMARY KEY NOT NULL, bound_revision INTEGER NOT NULL CHECK(bound_revision>0),
                needs_repair INTEGER NOT NULL CHECK(needs_repair IN (0,1)),
                config_json TEXT NOT NULL CHECK(length(CAST(config_json AS BLOB)) BETWEEN 1 AND 524288),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )"""
    )
}
