package com.tapscene.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNativeBitmap

/** Real SQLite and native PNG checks. Source/transition rows below are persistence fixtures,
 * not a claim that host tests decoded a video or performed human privacy review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SavedStepCopyHostTest {
    @Before fun configureHost() {
        HostSqlite.configure()
        HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
    }
    @After fun closeHostDescriptors() = HostFileSyncShadow.reset()

    @Test fun savedGraphCopiesIndependentlyWithoutRetargetingOrInheritingReview() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) { fixture { f ->
            AiDraftImportFixtures.reviewDraftRegions(f.store, f.project)
            val sealed = AiDraftImportFixtures.sealFixture(f.context, f.project, f.input)
            val releaseFiles = tree(File(f.root, "release-store/releases/${sealed.id}"))
            check(!checkNotNull(f.store.readDraftAiConfig(f.project)).needsRepair)
            val sourceId = f.input.startId
            f.store.setTerminal(f.project, f.input.branchId, false)
            f.next(f.input.branchId, sourceId) // Incoming authored action must still target the original.
            f.store.saveHotspot(f.project, sourceId, label = "Finish here", rect = OpaqueMask(.2f, .2f, .4f, .4f), endLabel = "Done")
            var current = f.snapshot()
            current = f.store.saveRegion(f.project, sourceId, null, "Stale region", null,
                RegionBox(1, 2, 3, 4), 5, .1, .9, current.project.revision)
            val staleId = current.steps.first().regions.single { it.name == "Stale region" }.id
            f.database { execSQL("UPDATE regions SET base_asset_id=? WHERE project_id=? AND region_id=?", arrayOf(id(), f.project, staleId)) }
            // A transition marker is never decoded: only retention/omission of its stored binding is under test.
            f.addTransitionRows(listOf(f.input.loopEdgeId, f.input.nextEdgeId, checkNotNull(f.snapshot().steps.last().nextAction).id))
            val before = f.snapshot()
            val source = before.steps.single { it.id == sourceId }
            val configBefore = checkNotNull(f.store.readDraftAiConfig(f.project))
            val session = f.store.beginEditorDraftSession(f.project)
            val pending = StoredEditorDraft(before.project.revision, source.editorFields(),
                source.editorFields().copy(title = "UNSAVED title", hotspots = emptyList()),
                EditorPendingForm(EditorFormKind.HOTSPOT, label = "Partial", left = "-."))
            check(f.store.writeEditorDraft(f.project, sourceId, session, pending))
            val other = before.steps.last()
            check(f.store.writeEditorDraft(f.project, other.id, session,
                StoredEditorDraft(before.project.revision, other.editorFields(), other.editorFields().copy(description = "Other pending"))))
            val pendingRows = f.rows("editor_drafts")
            val operation = id()
            rejected { f.store.copySavedStep(f.project, sourceId, before.project.revision, operation) }
            check(f.snapshot() == before && f.rows("editor_drafts") == pendingRows)
            check(f.store.clearEditorDraft(f.project, sourceId, session))
            val draftRows = f.rows("editor_drafts")
            val originalFiles = f.assets()
            // Exclude import/seal/setup calls. The copier uses real FileDescriptor.sync()
            // for PNG bytes, while this shadow observes its Os directory fsync calls.
            HostFileSyncShadow.beginProductionEvidence()
            val after = f.store.copySavedStep(f.project, sourceId, before.project.revision, operation)
            HostFileSyncShadow.assertDirectorySyncEvidence()
            val copy = after.steps.single { it.id == operation }
            check(after.project.revision == before.project.revision + 1)
            check(after.project.startStepId == before.project.startStepId)
            check(after.steps.map { it.id } == listOf(sourceId, operation, f.input.endId, f.input.branchId))
            check(after.steps.map { it.sortOrder } == after.steps.indices.toList())
            check(after.steps.filter { it.id != operation }.mapIndexed { index, step -> step.copy(sortOrder = index) } == before.steps)
            check(copy.title == source.title && copy.description == source.description && copy.isTerminal == source.isTerminal)
            check(copy.captureId != source.captureId && copy.asset.id != source.asset.id && copy.asset.privateRelativePath != source.asset.privateRelativePath)
            check(copy.masks == source.masks && copy.evidenceKind == source.evidenceKind)
            val origin = copy.origin as StepOrigin.PackageSafeImage
            val originalOrigin = source.origin as StepOrigin.PackageSafeImage
            check(origin.localStepId == operation && origin.copy(localStepId = sourceId) == originalOrigin)
            check(f.originValues(operation) == f.originValues(sourceId))
            check(copy.hotspots.size == source.hotspots.size)
            source.hotspots.zip(copy.hotspots).forEach { (old, new) ->
                check(new.id != old.id && new.edgeId != old.edgeId && new.transition == null)
                check(new.copy(id = old.id, edgeId = old.edgeId,
                    targetStepId = old.targetStepId, transition = old.transition) == old)
                check(new.targetStepId == if (old.targetStepId == sourceId) operation else old.targetStepId)
            }
            val oldNext = checkNotNull(source.nextAction)
            val newNext = checkNotNull(copy.nextAction)
            check(newNext.id != oldNext.id && newNext.targetStepId == oldNext.targetStepId && newNext.label == oldNext.label && newNext.transition == null)
            check(copy.regions.size == source.regions.size)
            copy.regions.forEach { new ->
                val old = source.regions.single { it.name == new.name }
                check(new.id != old.id && new.stateId == operation && new.asset == null && new.reviewedAt == null)
                check(new.copy(id = old.id, stateId = old.stateId, baseAssetId = old.baseAssetId,
                    baseSha256 = old.baseSha256, asset = old.asset, reviewedAt = old.reviewedAt) == old)
                check(new.matchesBase(copy.asset) == old.matchesBase(source.asset))
                check(new.baseAssetId == if (old.matchesBase(source.asset)) copy.asset.id else old.baseAssetId)
            }
            val oldIds = objectIds(before.steps)
            check(objectIds(listOf(copy)).none { it in oldIds })
            check(f.rows("editor_drafts") == draftRows && operation !in f.store.readEditorDrafts(f.project))
            val configAfter = checkNotNull(f.store.readDraftAiConfig(f.project))
            check(configAfter.needsRepair && configAfter.boundRevision == configBefore.boundRevision)
            check(DraftAiConfigCodec.encode(configAfter) == DraftAiConfigCodec.encode(configBefore))
            check(originalFiles.all { (path, hash) -> f.assets()[path] == hash })
            check(f.assets().keys - originalFiles.keys == setOf(copy.asset.privateRelativePath))
            checkPixels(f.store.resolveAsset(f.project, operation), f.store.resolveAsset(f.project, sourceId))
            check(tree(File(f.root, "release-store/releases/${sealed.id}")) == releaseFiles)
            check(f.store.copySavedStep(f.project, sourceId, before.project.revision, operation) == after)
            check(AiDraftImportFixtures.projectStore(f.context).readProject(f.project) == after)
            f.clean()
            println("HOST_SAVED_STEP_COPY graph: independent PNG/IDs, adjacent ordering, unchanged incoming/start/actions/releases/drafts/AI, self-loop rebound, regions unreviewed")
        } }
    }

    @Test fun originsRemainLocalAndMissingRawSourcesAreNeverRequired() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) { fixture { f ->
            val imageStep = f.input.startId
            val binding = f.snapshot().steps.first().safeImageBinding(f.snapshot().project)
            val bitmap = f.store.readSafeImageBase(binding)
            val masks = listOf(OpaqueMask(.2f, .2f, .5f, .5f))
            val output = try { SafeMediaWriter(f.context).writePng(bitmap, masks, File(f.root, "redacted-${id()}")) }
                finally { bitmap.recycle() }
            val redacted = f.store.replaceReviewedStep(f.project, imageStep, binding.revision,
                ReviewedStepInput(output.file, output.sha256, output.width, output.height, StepOrigin.Image(binding), masks, id()))
            val hiddenOrigin = f.originValues(imageStep)
            check(hiddenOrigin.isNotEmpty())
            val imageCopyId = id()
            var current = f.store.copySavedStep(f.project, imageStep, redacted.project.revision, imageCopyId)
            val imageCopy = current.steps.single { it.id == imageCopyId }
            check((imageCopy.origin as StepOrigin.Image).base.matches(current.project, imageCopy))
            check(f.originValues(imageCopyId) == hiddenOrigin && imageCopy.evidenceKind == "imported")
            checkPixels(f.store.resolveAsset(f.project, imageCopyId), f.store.resolveAsset(f.project, imageStep))

            val screenshotStep = f.input.endId
            val videoStep = f.input.branchId
            val sourceId = f.attachMissingRawSources(screenshotStep, videoStep)
            // Keep 20 distinct original screenshot records, all referenced by saved steps.
            repeat(19) { index ->
                val step = f.addPlainStep("Screenshot quota $index")
                f.attachScreenshot(step, id())
            }
            current = f.snapshot()
            check(f.store.screenshotCount(f.project) == 20)
            val sourceRows = f.rows("sources")
            val imageRows = f.rows("image_sources")
            for (stepId in listOf(screenshotStep, videoStep)) {
                val old = current.steps.single { it.id == stepId }
                val copyId = id()
                current = f.store.copySavedStep(f.project, stepId, current.project.revision, copyId)
                val copy = current.steps.single { it.id == copyId }
                check(copy.origin == old.origin && copy.isTerminal == old.isTerminal)
                check(copy.evidenceKind == old.evidenceKind && copy.captureId != old.captureId)
                checkPixels(f.store.resolveAsset(f.project, copyId), f.store.resolveAsset(f.project, stepId))
            }
            check(f.rows("sources") == sourceRows && f.rows("image_sources") == imageRows && f.store.screenshotCount(f.project) == 20)
            check(!File(f.root, "sources/$sourceId.mp4").exists())
            check(File(f.root, "image-sources").walkTopDown().none { it.isFile })
            f.store.deleteStep(f.project, imageStep)
            check(f.store.resolveAsset(f.project, imageCopyId).isFile)
            check(f.originValues(imageCopyId) == hiddenOrigin)
            // Re-redaction hides package provenance from StepOrigin, but must not erase its
            // required history. A missing hidden row must fail closed before copying pixels.
            f.database { execSQL("DELETE FROM package_step_origins WHERE project_id=? AND state_id=?", arrayOf(f.project, imageCopyId)) }
            val missingHistory = f.snapshot()
            val retainedFiles = f.assets()
            rejected { f.store.copySavedStep(f.project, imageCopyId, missingHistory.project.revision, id()) }
            check(f.snapshot() == missingHistory && f.assets() == retainedFiles)
            f.clean()
            println("HOST_SAVED_STEP_COPY origins: re-redacted package provenance retained, image binding independent, missing video/screenshot sources reused at screenshot quota")
        } }
    }

    @Test fun rejectsUnsafePixelsAndFailuresLeaveNoPartialCopy() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) { fixture { f ->
            val sourceId = f.input.startId
            val original = f.snapshot()
            val asset = original.steps.first().asset
            val file = f.store.resolveAsset(f.project, sourceId)
            val bytes = file.readBytes()
            val operation = id()
            rejected { f.store.copySavedStep(f.project, sourceId, original.project.revision - 1, operation) }
            rejected { f.store.copySavedStep(f.project, id(), original.project.revision, operation) }
            rejected { f.store.copySavedStep(f.project, sourceId, original.project.revision, f.input.endId) }
            suspend fun rejectUnchanged() {
                val before = f.snapshot()
                val assets = f.assets()
                rejected { f.store.copySavedStep(f.project, sourceId, before.project.revision, operation) }
                check(f.snapshot() == before && f.assets() == assets)
                f.clean()
            }
            check(file.delete())
            rejectUnchanged() // No fallback to package, raw source or another step's identical PNG.
            file.writeBytes(bytes + byteArrayOf(1))
            rejectUnchanged() // SHA/length mismatch.
            file.writeBytes(bytes)
            f.database { execSQL("UPDATE local_assets SET width=width+1 WHERE asset_id=?", arrayOf(asset.id)) }
            rejectUnchanged() // Metadata cannot override the actual decoded dimensions.
            f.database { execSQL("UPDATE local_assets SET width=? WHERE asset_id=?", arrayOf<Any>(asset.width, asset.id)) }
            val transparent = Bitmap.createBitmap(asset.width, asset.height, Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { check(transparent.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { transparent.recycle() }
            f.rehashAsset(sourceId)
            rejectUnchanged() // Structurally valid PNG + correct SHA still rejects transparent pixels.
            file.writeBytes(bytes.copyOf(24))
            f.rehashAsset(sourceId)
            rejectUnchanged() // Correct SHA for truncated bytes is not a safe image.
            file.writeBytes(bytes)
            f.rehashAsset(sourceId)
            check(f.snapshot() == original)

            for (commitFailure in listOf(false, true)) {
                val beforeFiles = f.assets()
                val trigger = if (commitFailure) """CREATE TRIGGER reject_copy AFTER UPDATE OF draft_revision ON projects
                    BEGIN UPDATE projects SET start_state_id='${id()}' WHERE project_id=NEW.project_id; END"""
                    else """CREATE TRIGGER reject_copy BEFORE UPDATE OF draft_revision ON projects
                    BEGIN SELECT RAISE(ABORT, 'injected copy failure'); END"""
                f.database { execSQL(trigger) }
                val failure = runCatching { f.store.copySavedStep(f.project, sourceId, original.project.revision, operation) }.exceptionOrNull()
                check(failure != null)
                if (commitFailure) check(failure is SQLiteConstraintException && failure.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
                // Reuse the failing store: an uncommitted pooled connection must not fool cleanup.
                check(f.snapshot() == original && f.assets() == beforeFiles)
                f.database { execSQL("DROP TRIGGER reject_copy") }
                f.clean()
            }
            val after = f.store.copySavedStep(f.project, sourceId, original.project.revision, operation)
            check(after.project.revision == original.project.revision + 1 && after.steps.count { it.id == operation } == 1)
            f.clean()
            println("HOST_SAVED_STEP_COPY atomicity: missing/SHA/size/alpha/truncated PNG, stale/conflicting IDs, statement and deferred-commit failures reject without graph/file residue")
        } }
    }

    @Test fun retriesRacesAndCancellationResolveFromDurableState() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) { fixture { f -> coroutineScope {
            val before = f.snapshot()
            val sourceId = f.input.startId
            val operation = id()
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                f.store.copySavedStep(f.project, sourceId, before.project.revision, operation)
            }
            check(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
            check(f.snapshot() == before)
            f.clean()
            val second = AiDraftImportFixtures.projectStore(f.context)
            val start = CompletableDeferred<Unit>()
            val raced = listOf(f.store, second).map { owner -> async(Dispatchers.IO) {
                start.await(); owner.copySavedStep(f.project, sourceId, before.project.revision, operation)
            } }
            start.complete(Unit)
            val first = raced[0].await()
            check(raced[1].await() == first && first.steps.count { it.id == operation } == 1)
            check(first.project.revision == before.project.revision + 1)
            val files = f.assets()
            check(second.copySavedStep(f.project, sourceId, before.project.revision, operation) == first && f.assets() == files)
            // Different operations with the same revision cannot both append a step.
            val gate = CompletableDeferred<Unit>()
            val different = listOf(f.store, second).map { owner -> async(Dispatchers.IO) {
                gate.await(); runCatching { owner.copySavedStep(f.project, sourceId, first.project.revision, id()) }
            } }
            gate.complete(Unit)
            val results = different.map { it.await() }
            check(results.count { it.isSuccess } == 1 && results.count { it.isFailure } == 1)
            val stable = f.snapshot()
            check(stable.project.revision == first.project.revision + 1 && stable.steps.size == first.steps.size + 1)

            // Hold the caller's dispatcher after IO commits but before its result can be delivered.
            val terminalId = f.input.endId
            val finalOperation = id()
            val committed = CountDownLatch(1)
            var returned = false
            val observer = async(Dispatchers.IO) {
                withTimeout(10_000) {
                    while (f.snapshot().steps.none { it.id == finalOperation }) delay(1)
                }
                committed.countDown()
            }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
                val saving = withContext(caller) {
                    val pending = async(start = CoroutineStart.UNDISPATCHED) {
                        withContext(Dispatchers.IO) { f.store.copySavedStep(f.project, terminalId, stable.project.revision, finalOperation) }
                        returned = true
                    }
                    try { check(committed.await(10, TimeUnit.SECONDS)) { "Copy did not reach the durable commit boundary" } }
                    finally { pending.cancel() }
                    pending
                }
                observer.await()
                check(runCatching { saving.await() }.exceptionOrNull() is CancellationException && !returned)
            }
            val saved = f.snapshot()
            check(saved.project.revision == stable.project.revision + 1 && saved.steps.single { it.id == finalOperation }.isTerminal)
            check(second.copySavedStep(f.project, terminalId, stable.project.revision, finalOperation) == saved)
            // Copying an unresolved authored next action must retain a repairable null target.
            f.next(sourceId, null)
            val unresolved = f.snapshot()
            val unresolvedId = id()
            val copied = f.store.copySavedStep(f.project, sourceId, unresolved.project.revision, unresolvedId).steps.single { it.id == unresolvedId }
            check(checkNotNull(copied.nextAction).targetStepId == null && copied.nextAction?.id != unresolved.steps.first().nextAction?.id)
            f.clean()
            println("HOST_SAVED_STEP_COPY concurrency: old-revision retry and two-store race exactly once, competing operations stale, cancellation before/after commit, terminal and unresolved next retained")
        } } }
    }

    @Test fun allGraphLimitsAreCheckedBeforeAddingTheCompleteCopy() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            for (scenario in listOf("steps", "edges", "regions", "other-step-regions", "boundary")) fixture { f ->
                val sourceId = f.input.startId
                when (scenario) {
                    "steps" -> repeat(37) { f.addPlainStep("Capacity $it") }
                    "edges" -> {
                        repeat(11) { f.addPlainStep("Edges $it") }
                        val steps = f.snapshot().steps
                        f.database {
                            var count = 3 // The fixture starts with two tap edges and one next action.
                            for (step in steps) repeat(6 - step.hotspots.size) {
                                if (count < 79) { f.insertHotspot(this, step.id); count++ }
                            }
                            check(count == 79)
                        }
                    }
                    "regions" -> {
                        repeat(4) { f.addPlainStep("Regions $it") }
                        val steps = f.snapshot().steps
                        f.database {
                            var count = 1
                            for (step in steps) repeat(12 - step.regions.size) {
                                if (count < 80) { f.insertRegion(this, step); count++ }
                            }
                            check(count == 80)
                        }
                    }
                    "other-step-regions" -> {
                        val other = f.snapshot().steps.last()
                        f.database { repeat(13) { f.insertRegion(this, other) } }
                    }
                    "boundary" -> f.database { repeat(11) { f.insertRegion(this, f.snapshot().steps.first()) } }
                }
                val before = f.snapshot()
                val assets = f.assets()
                val copyId = id()
                val result = runCatching { f.store.copySavedStep(f.project, sourceId, before.project.revision, copyId) }
                if (scenario == "boundary") {
                    val after = result.getOrThrow()
                    check(after.steps.single { it.id == copyId }.regions.size == 12)
                    check(after.steps.sumOf { it.regions.size } == 24)
                } else {
                    check(result.isFailure) { "Copy silently exceeded $scenario capacity" }
                    check(f.snapshot() == before && f.assets() == assets)
                }
                f.clean()
            }
            println("HOST_SAVED_STEP_COPY limits: 40 steps, combined 80 edges, project 80 regions and every step's 12 regions checked; exact 12-region copy retained")
        }
    }

    private suspend fun fixture(block: suspend (CopyFixture) -> Unit) =
        AiDraftImportFixtures.isolated(RuntimeEnvironment.getApplication(), "saved-step-copy") { context ->
            val input = AiDraftImportFixtures.complete(context)
            val imports = AiDraftImportFixtures.importStore(context)
            val prepared = input.zip.inputStream().use { imports.prepare(it) }
            val project = checkNotNull(imports.commit(prepared.sessionId, prepared.previewDigest).projectId)
            val fixture = CopyFixture(context, AiDraftImportFixtures.projectStore(context), project, input)
            checkPixels(fixture.store.resolveAsset(project, input.startId), fixture.store.resolveAsset(project, input.startId))
            block(fixture)
        }

    private class CopyFixture(val context: Context, val store: ProjectStore, val project: String,
        val input: AiDraftImportFixtures.Fixture) {
        val root: File get() = context.noBackupFilesDir
        fun snapshot() = checkNotNull(store.readProject(project))
        fun assets() = tree(File(root, "project-assets"), root)
        fun database(block: SQLiteDatabase.() -> Unit) {
            val helper = ProjectStore.Database(context, File(root, "projects.sqlite").path)
            try { helper.writableDatabase.let { HostSqlite.verify(it); it.block() } } finally { helper.close() }
        }
        fun rows(table: String): List<List<String?>> {
            var result: List<List<String?>> = emptyList()
            database { rawQuery("SELECT * FROM $table WHERE project_id=? ORDER BY rowid", arrayOf(project)).use { cursor ->
                result = buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
            } }
            return result
        }
        fun originValues(step: String): List<String> {
            var result = emptyList<String>()
            database { rawQuery("SELECT import_id,source_state_id,source_asset_id,source_sha256,declared_kind FROM package_step_origins WHERE project_id=? AND state_id=?",
                arrayOf(project, step)).use { if (it.moveToFirst()) result = (0..4).map(it::getString) } }
            return result
        }
        fun next(stepId: String, target: String?) {
            val before = snapshot(); val step = before.steps.single { it.id == stepId }
            store.saveStepDraft(project, stepId, step.title, step.description, step.isTerminal, step.hotspots,
                before.project.revision, ProjectNextAction(step.nextAction?.id ?: id(), "Authored next", target))
        }
        fun rehashAsset(step: String) {
            val asset = snapshot().steps.single { it.id == step }.asset
            val file = File(root, asset.privateRelativePath)
            val sha = ViewerPackageCodec.sha256(file)
            database {
                execSQL("UPDATE local_assets SET sha256=?,byte_length=? WHERE asset_id=?", arrayOf(sha, file.length(), asset.id))
                execSQL("UPDATE package_step_origins SET source_sha256=? WHERE project_id=? AND state_id=?", arrayOf(sha, project, step))
            }
        }
        fun clean() {
            check(File(root, "project-staging").listFiles().orEmpty().isEmpty())
            database {
                rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
                for (table in listOf("asset_imports", "asset_cleanup", "transition_imports", "image_source_imports", "image_source_cleanup")) {
                    rawQuery("SELECT COUNT(*) FROM $table", null).use { check(it.moveToFirst() && it.getInt(0) == 0) { "$table retained incomplete copy work" } }
                }
            }
        }
        fun addPlainStep(title: String): String {
            val current = snapshot(); val template = current.steps.first().asset
            val step = id(); val asset = id(); val path = "project-assets/$project/$asset.png"
            File(root, template.privateRelativePath).copyTo(File(root, path))
            database {
                beginTransaction()
                try {
                    execSQL("INSERT INTO local_assets VALUES(?,?,?,?,?,?,?)", arrayOf<Any>(asset, project, path, template.sha256, template.byteLength, template.width, template.height))
                    execSQL("""INSERT INTO states(project_id,state_id,capture_id,sort_order,title,description,is_terminal,input_asset_id,
                        masks_json,origin_kind,evidence_kind,base_asset_id,base_sha256,base_revision,base_width,base_height)
                        VALUES(?,?,?,?,?,'Capacity fixture',0,?,'[]','image','authored',?,?,?,?,?)""",
                        arrayOf<Any>(project, step, id(), current.steps.size, title, asset, asset, template.sha256,
                            current.project.revision, template.width, template.height))
                    setTransactionSuccessful()
                } finally { endTransaction() }
            }
            return step
        }
        fun insertRegion(db: SQLiteDatabase, step: ProjectStep) {
            db.execSQL("""INSERT INTO regions VALUES(?,?,?,?,?,'Capacity region',NULL,1,2,3,4,?,?,0,.5,.5,NULL,NULL)""",
                arrayOf<Any>(project, id(), step.id, step.asset.id, step.asset.sha256, step.asset.width, step.asset.height))
        }
        fun insertHotspot(db: SQLiteDatabase, step: String) {
            val hotspot = id()
            db.execSQL("INSERT INTO hotspots VALUES(?,?,?,'Capacity action',.1,.2,.4,.6)", arrayOf(project, hotspot, step))
            db.execSQL("INSERT INTO edges VALUES(?,?,?,?,NULL,'Done')", arrayOf(project, id(), hotspot, step))
        }
        fun attachScreenshot(step: String, source: String) {
            val asset = snapshot().steps.single { it.id == step }.asset
            val json = sourceJson(source, "image-sources/$source/original.png", "image/png")
                .put("orientation", 1).put("outputWidth", asset.width).put("outputHeight", asset.height)
            database {
                beginTransaction()
                try {
                    execSQL("INSERT INTO image_sources VALUES(?,?,'image/png',?)", arrayOf(project, source, json.toString()))
                    execSQL("""UPDATE states SET origin_kind='image',evidence_kind='authored',package_import_id=NULL,image_source_id=?,
                        base_asset_id=NULL,base_sha256=NULL,base_revision=NULL,base_width=NULL,base_height=NULL WHERE project_id=? AND state_id=?""",
                        arrayOf(source, project, step))
                    setTransactionSuccessful()
                } finally { endTransaction() }
            }
        }
        fun attachMissingRawSources(screenshot: String, video: String): String {
            attachScreenshot(screenshot, id())
            val source = id()
            database {
                beginTransaction()
                try {
                    execSQL("INSERT INTO sources VALUES(?,?,?)", arrayOf(project, source,
                        sourceJson(source, "sources/$source.mp4", "video/mp4").put("rotation", 0).put("durationUs", 1_000_000).put("pixelRatio", 1).toString()))
                    execSQL("""UPDATE states SET origin_kind='videoFrame',evidence_kind='recorded',package_import_id=NULL,
                        source_id=?,frame_pts_us=123000,time_precision_us=1000 WHERE project_id=? AND state_id=?""", arrayOf(source, project, video))
                    setTransactionSuccessful()
                } finally { endTransaction() }
            }
            return source
        }
        fun addTransitionRows(edges: List<String>) {
            val source = id()
            database {
                beginTransaction()
                try {
                    execSQL("INSERT INTO sources VALUES(?,?,?)", arrayOf(project, source,
                        sourceJson(source, "sources/$source.mp4", "video/mp4").put("rotation", 0).put("durationUs", 1_000_000).put("pixelRatio", 1).toString()))
                    for (edge in edges) {
                        val asset = id(); val path = "project-assets/$project/$asset.mp4"
                        val file = File(root, path).apply { writeText("Persistence-only transition marker") }
                        execSQL("INSERT INTO local_assets VALUES(?,?,?,?,?,32,48)", arrayOf(asset, project, path, ViewerPackageCodec.sha256(file), file.length()))
                        execSQL("INSERT INTO edge_transitions VALUES(?,?,?,?,0,1000000,1000000,'[]',?)", arrayOf(project, edge, asset, source, id()))
                    }
                    setTransactionSuccessful()
                } finally { endTransaction() }
            }
        }
        private fun sourceJson(source: String, path: String, mime: String) = JSONObject()
            .put("id", source).put("path", path).put("name", "Missing private source")
            .put("mime", mime).put("bytes", 100).put("sha256", "a".repeat(64)).put("width", 32).put("height", 48)
    }

    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun tree(directory: File, relativeTo: File = directory): Map<String, String> = directory.walkTopDown()
            .filter { it.isFile }.associate { it.relativeTo(relativeTo).path to ViewerPackageCodec.sha256(it) }
        private fun objectIds(steps: List<ProjectStep>) = steps.flatMap { step ->
            listOf(step.id, step.captureId, step.asset.id) + step.hotspots.flatMap { listOf(it.id, it.edgeId) } +
                listOfNotNull(step.nextAction?.id) + step.regions.map { it.id }
        }.toSet()
        private fun checkPixels(actual: File, expected: File) {
            check(actual.readBytes().contentEquals(expected.readBytes()))
            val decoded = checkNotNull(BitmapFactory.decodeFile(actual.path))
            try {
                check(Shadow.extract<Any>(decoded) is ShadowNativeBitmap)
                check(decoded.width == 32 && decoded.height == 48)
                val row = IntArray(decoded.width)
                for (y in 0 until decoded.height) {
                    decoded.getPixels(row, 0, row.size, 0, y, row.size, 1)
                    check(row.all { Color.alpha(it) == 255 })
                }
            } finally { decoded.recycle() }
        }
        private suspend fun rejected(block: suspend () -> Unit) {
            check(runCatching { block() }.exceptionOrNull() != null) { "Unsafe or stale copy unexpectedly succeeded" }
        }
    }
}
