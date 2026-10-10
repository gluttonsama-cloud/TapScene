package com.tapscene.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.clickplan.*
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.recording.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Real production SQLite, FrameEvidenceStore and native SafeMediaWriter PNGs. The bounded
 * accessor replaces unavailable host MP4 extraction only. Synthetic raw bytes prove independent
 * persistence, not device capture, MP4 decoding, human review, or power-loss durability. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClickChainImportHostTest {
    @Before fun begin() {
        HostSqlite.configure()
        HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
    }
    @After fun end() = HostFileSyncShadow.reset()

    @Test fun reviewedRouteCreatesIndependentProjectAndReceiptOutlivesBothOwners() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            f.store.addReviewedStep(f.project, f.input.frames.first().reviewed, "Original saved step")
            val before = checkNotNull(f.store.readProject(f.project))
            val step = before.steps.single()
            val session = f.store.beginEditorDraftSession(f.project)
            val draft = StoredEditorDraft(before.project.revision, step.editorFields(), step.editorFields().copy(title = "Unsaved original"))
            check(f.store.writeEditorDraft(f.project, step.id, session, draft))
            val originals = f.media()
            HostFileSyncShadow.beginProductionEvidence()
            val receipt = f.store.importReviewedClickChain(f.input, f.accessor)
            HostFileSyncShadow.assertDirectorySyncEvidence()
            check(receipt.status == "committed" && receipt.projectStillExists)
            val reopenedRun = ClickPlanStore(f.context).readRuns(f.project).single { it.runId == f.run.runId }
            check(f.store.importReviewedClickChain(f.input.copy(run = reopenedRun), f.accessor) == receipt)
            val saved = checkNotNull(f.store.readProject(receipt.projectId))
            check(saved.project.revision == 1L && saved.steps.size == 3 && saved.steps.sumOf { it.hotspots.size } == 2)
            check(saved.project.startStepId == saved.steps.first().id && saved.steps.last().isTerminal)
            saved.steps.zip(f.input.frames).forEach { (step, frame) ->
                check(step.frameTimeUs == frame.evidence.containerPtsUs && step.timePrecisionUs == 1L)
                check(step.asset.sha256 == frame.reviewed.sha256 && step.evidenceKind == "recorded")
                check(step.sourceId != f.source.sourceId && step.source?.metadata == f.source.metadata)
                check(ViewerPackageCodec.sha256(File(f.root, step.asset.privateRelativePath)) == frame.reviewed.sha256)
            }
            check(saved.steps[0].hotspots.single().targetStepId == saved.steps[1].id)
            check(saved.steps[1].hotspots.single().targetStepId == saved.steps[2].id)
            val publicJson = ViewerPackageCodec.writeScene(ReleaseCompiler.draftPlanScene(saved)).toString(Charsets.UTF_8)
            val privateValues = listOf(f.run.runId, f.run.recordingSessionId, f.source.sourceId, f.source.privateRelativePath,
                f.source.metadata.sha256, f.run.plan.targetPackage) + f.run.plan.actions.map { it.actionId } +
                f.input.frames.map { it.evidence.ticket.ticketId }
            check(privateValues.none { it in publicJson }) // Public graph projection excludes private lineage, raw bytes and run/anchor identity.
            check(f.store.readProject(f.project) == before && f.store.readEditorDrafts(f.project)[step.id] == draft)
            check(originals.all { (path, hash) -> f.media()[path] == hash })
            val newSource = WorkspaceStore(f.context, receipt.projectId).read().single().source
            check(newSource == saved.steps.first().source && newSource.privateRelativePath != f.source.privateRelativePath)
            check(File(f.root, newSource.privateRelativePath).readBytes().contentEquals(f.raw.readBytes()))
            f.assertClean()
            val copy = f.store.beginProjectCopy(receipt.projectId, saved.project.revision)
            val clone = f.store.copySavedProject(receipt.projectId, saved.project.revision, copy.operationId)
            f.db {
                fun lineage(project: String) = rawQuery("SELECT kind,historical_facts FROM click_chain_origins WHERE project_id=? ORDER BY kind,historical_facts", arrayOf(project)).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
                }
                check(lineage(receipt.projectId).size == 5 && lineage(receipt.projectId) == lineage(clone.projectId))
                rawQuery("SELECT COUNT(*) FROM click_chain_origins a JOIN click_chain_origins b ON a.local_id=b.local_id WHERE a.project_id=? AND b.project_id=?",
                    arrayOf(receipt.projectId, clone.projectId)).use { check(it.moveToFirst() && it.getInt(0) == 0) }
            }
            f.store.deleteProject(clone.projectId)
            f.store.deleteProject(f.project)
            WorkspaceStore(f.context, f.project).write(emptyList())
            check(f.raw.delete()); f.evidence.deleteAfterSourceCommit(f.source.sourceId)
            f.input.frames.forEach { check(it.reviewed.file.delete()) }
            check(f.store.readProject(receipt.projectId) == saved && File(f.root, newSource.privateRelativePath).isFile)
            check(WorkspaceStore(f.context, receipt.projectId).read() == listOf(SourceDraft(newSource)))
            check(saved.steps.all { it.source == newSource } &&
                ViewerPackageCodec.sha256(File(f.root, newSource.privateRelativePath)) == newSource.metadata.sha256)
            f.db {
                rawQuery("SELECT project_id,source_json FROM sources WHERE source_id=?", arrayOf(newSource.sourceId)).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == receipt.projectId)
                    val source = org.json.JSONObject(cursor.getString(1))
                    check(source.getString("id") == newSource.sourceId && source.getString("path") == newSource.privateRelativePath &&
                        source.getString("sha256") == newSource.metadata.sha256 && !cursor.moveToNext())
                }
            }
            check(f.store.importReviewedClickChain(f.input, f.accessor) == receipt)

            // Exercise the real saved-safe-image editing path after all original inputs vanished.
            // The MP4 is still synthetic persistence data; this does not claim video decoding.
            val first = saved.steps.first()
            val binding = first.safeImageBinding(saved.project)
            val additionalMasks = listOf(OpaqueMask(.5f, .5f, .75f, .75f))
            val base = f.store.readSafeImageBase(binding)
            val untouchedPixel = base.getPixel(16, 5)
            val output = try {
                check(base.getPixel(3, 4) == Color.BLACK) // The initial privacy mask is already burned in.
                SafeMediaWriter(f.context).writePng(base, additionalMasks, File(f.root, "independent-redaction-${id()}"))
            } finally { base.recycle() }
            val edited = f.store.replaceReviewedStep(receipt.projectId, first.id, saved.project.revision,
                ReviewedStepInput(output.file, output.sha256, output.width, output.height,
                    StepOrigin.Image(binding), additionalMasks, id()))
            val editedStep = edited.steps.single { it.id == first.id }
            check(edited.project.revision == saved.project.revision + 1 && edited.project.startStepId == saved.project.startStepId)
            check(editedStep.asset.id != first.asset.id && editedStep.asset.sha256 == output.sha256 &&
                editedStep.imageOrigin?.base == binding && editedStep.evidenceKind == "recorded" && editedStep.hotspots == first.hotspots)
            check(edited.steps.filter { it.id != first.id } == saved.steps.filter { it.id != first.id })
            val actual = f.store.readSafeImageBase(editedStep.safeImageBinding(edited.project))
            try {
                check(actual.getPixel(3, 4) == Color.BLACK && actual.getPixel(18, 28) == Color.BLACK && actual.getPixel(16, 5) == untouchedPixel)
            } finally { actual.recycle() }
            check(f.store.readProject(receipt.projectId) == edited &&
                WorkspaceStore(f.context, receipt.projectId).read() == listOf(SourceDraft(newSource)))
            check(ViewerPackageCodec.sha256(File(f.root, newSource.privateRelativePath)) == newSource.metadata.sha256)
            check(f.store.importReviewedClickChain(f.input, f.accessor) == receipt && f.store.readProject(receipt.projectId) == edited)
            f.assertClean()
            f.store.deleteProject(receipt.projectId)
            val replay = f.store.importReviewedClickChain(f.input, f.accessor)
            check(replay.status == "committed" && !replay.projectStillExists && f.store.listProjects().isEmpty())
            check(File(f.root, newSource.privateRelativePath).isFile)
            println("HOST_CLICK_CHAIN independent raw/workspace ownership and real safePNG re-redaction/save after originals deleted; representative route and drafts preserved; retry no resurrection; synthetic MP4 not decoded")
        }
    }

    @Test fun failedCommitCancellationAndRetryAreAtomic() = runBlocking(Dispatchers.IO) {
        fixture { f -> coroutineScope {
            val before = f.media()
            for (deferred in listOf(false, true)) {
                f.db {
                    execSQL(if (deferred) """CREATE TRIGGER reject_chain AFTER UPDATE OF status ON click_chain_imports
                        WHEN NEW.status='committed' BEGIN UPDATE projects SET start_state_id='${id()}' WHERE project_id=NEW.project_id; END"""
                    else "CREATE TRIGGER reject_chain BEFORE INSERT ON projects WHEN NEW.project_id='${f.input.operationId}' BEGIN SELECT RAISE(ABORT,'injected batch failure'); END")
                }
                val failure = runCatching { f.store.importReviewedClickChain(f.input, f.accessor) }.exceptionOrNull()
                check(failure != null)
                if (deferred) check(failure is SQLiteConstraintException && failure.stackTrace.any { it.methodName == "endTransaction" })
                f.db { execSQL("DROP TRIGGER reject_chain") }
                check(f.store.listProjects().map { it.id } == listOf(f.project) && f.media() == before)
                check(f.store.readClickChainImport(f.input.operationId)?.status == "preparing"); f.assertClean()
                check(WorkspaceStore.retainedWorkspaces(f.context, setOf(f.project)).none { it.projectId == f.input.operationId })
            }
            val abandoned = f.input.copy(operationId = id())
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel(); f.store.importReviewedClickChain(abandoned, f.accessor)
            }
            check(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
            check(f.store.listProjects().size == 1 && f.media() == before); f.assertClean()
            check(f.store.cancelClickChainImport(abandoned.operationId)?.status == "aborted")
            check(f.store.cancelClickChainImport(id()) == null)
            check(runCatching { f.store.importReviewedClickChain(abandoned, f.accessor) }.isFailure)
            val receipt = f.store.importReviewedClickChain(f.input, f.accessor)
            check(receipt.status == "committed" && f.store.listProjects().size == 2)
            check(f.store.cancelClickChainImport(receipt.operationId) == receipt)
            val after = f.media()
            check(f.store.importReviewedClickChain(f.input, f.accessor) == receipt && f.media() == after)
            check(runCatching { f.store.importReviewedClickChain(f.input.copy(title = "Different input"), f.accessor) }.isFailure)
            val lostDelivery = f.input.copy(operationId = id())
            val interrupted = async {
                val owner = currentCoroutineContext()[Job]!!
                var observations = 0
                val cancelDuringCommit = FrameSourceAccessor { project, session, source ->
                    val found = f.accessor.registeredSource(project, session, source)
                    if (++observations == 2) owner.cancel() // Final validation runs inside NonCancellable.
                    found
                }
                f.store.importReviewedClickChain(lostDelivery, cancelDuringCommit)
            }
            check(runCatching { interrupted.await() }.exceptionOrNull() is CancellationException)
            val committedFiles = f.media()
            f.db { execSQL("ALTER TABLE click_chain_imports RENAME TO chain_test_hidden_receipts") }
            try {
                check(runCatching { f.store.readClickChainImport(lostDelivery.operationId) }.isFailure)
                check(f.media() == committedFiles)
            } finally { f.db { execSQL("ALTER TABLE chain_test_hidden_receipts RENAME TO click_chain_imports") } }
            val recovered = f.store.importReviewedClickChain(lostDelivery, f.accessor)
            check(recovered.status == "committed" && f.store.listProjects().size == 3 && f.media() == committedFiles)
            f.assertClean()
            println("HOST_CLICK_CHAIN failure: insert/deferred-FK rollback, precommit cancel, uncertain postcommit delivery/receipt reads, exact cleanup, retry/no duplicate, immutable digest")
        } }
    }

    @Test fun tamperedMediaWrongPixelsAndUnknownActionsCannotBecomeEvidence() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            val raw = f.raw.readBytes(); f.raw.writeBytes(raw.map { (it.toInt() xor 1).toByte() }.toByteArray())
            rejected(f) { f.store.importReviewedClickChain(f.input, f.accessor) }; f.raw.writeBytes(raw)
            val ticket = f.input.frames.first().evidence.ticket
            val png = File(f.root, "frame-evidence/${f.run.recordingSessionId}/${ticket.ticketId}.png")
            val original = png.readBytes(); png.appendBytes(byteArrayOf(0))
            rejected(f) { f.store.importReviewedClickChain(f.input, f.accessor) }; png.writeBytes(original)
            val first = f.input.frames.first(); val different = f.input.frames.last().reviewed
            val wrong = first.copy(reviewed = first.reviewed.copy(file = different.file, sha256 = different.sha256))
            rejected(f) { f.store.importReviewedClickChain(f.input.copy(operationId = id(), frames = listOf(wrong) + f.input.frames.drop(1)), f.accessor) }
            val unknown = f.run.copy(outcomes = f.run.outcomes.mapIndexed { index, value -> if (index == 0) value.copy(status = ClickActionStatus.Unknown) else value })
            rejected(f) { f.store.importReviewedClickChain(f.input.copy(operationId = id(), run = unknown), f.accessor) }
            val jumped = f.input.actions.first().copy(toFrameKey = f.input.frames.last().frameKey)
            rejected(f) { f.store.importReviewedClickChain(f.input.copy(operationId = id(), actions = listOf(jumped) + f.input.actions.drop(1)), f.accessor) }
            val over = f.input.copy(operationId = id(), frames = List(41) { first })
            rejected(f) { f.store.importReviewedClickChain(over, f.accessor) }
            val frameOnly = f.input.copy(operationId = id(), actions = emptyList(), terminalFrameKey = null)
            val receipt = f.store.importReviewedClickChain(frameOnly, f.accessor)
            check(checkNotNull(f.store.readProject(receipt.projectId)).steps.all { it.hotspots.isEmpty() })
            println("HOST_CLICK_CHAIN rejection: changed original/evidence, nonmatching safe pixels, unknown outcome, invented route, whole-selection limit; frame-only creates no edges")
        }
    }

    @Test fun staticFrameSelfLoopsArePreservedAndSeventhHotspotRejectsWholeBatch() = runBlocking(Dispatchers.IO) {
        fixture(count = 2, static = true) { f ->
            val receipt = f.store.importReviewedClickChain(f.input, f.accessor)
            val step = checkNotNull(f.store.readProject(receipt.projectId)).steps.single()
            check(step.hotspots.size == 2 && step.hotspots.all { it.targetStepId == step.id } && !step.isTerminal)
        }
        fixture(count = 7, static = true) { f -> rejected(f) { f.store.importReviewedClickChain(f.input, f.accessor) } }
        println("HOST_CLICK_CHAIN identity: session+captured-frame-only dedup; two real self-loops retained; seven-hotspot batch rejected without truncation")
    }

    private suspend fun rejected(f: Fixture, block: suspend () -> Unit) {
        val before = f.media()
        check(runCatching { block() }.isFailure)
        check(f.store.listProjects().map { it.id } == listOf(f.project) && f.media() == before)
        f.assertClean()
    }

    private suspend fun fixture(count: Int = 2, static: Boolean = false, block: suspend (Fixture) -> Unit) =
        AiDraftImportFixtures.isolated(RuntimeEnvironment.getApplication(), "click-chain") { context ->
            val root = context.noBackupFilesDir
            val store = AiDraftImportFixtures.projectStore(context)
            val project = store.createProject("Original author work").project.id
            val rawId = id(); val raw = File(root, "sources/$rawId.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(128) { it.toByte() }) }
            val source = ImportedSource(rawId, "sources/$rawId.mp4", "Private capture.mp4",
                SourceMetadata("video/mp4", raw.length(), ViewerPackageCodec.sha256(raw), 32, 48, 0, 1_000_000))
            WorkspaceStore(context, project).append(source)
            val plan = ClickPlan.create(project, "com.example.target", 64, 96, 0, List(count) { ClickAction(x = 20, y = 30) })
            val runs = ClickPlanStore(context)
            var run = ClickRun.create(plan, id(), source.sourceId, 1, 1)
            runs.beginRun(run)
            fun advance(status: ClickActionStatus, index: Int, event: ClickRunEventType) {
                val completed = status == ClickActionStatus.Completed
                run = run.copy(phase = if (completed && index == count - 1) ClickRunPhase.Completed else ClickRunPhase.Running,
                    nextActionIndex = if (completed) index + 1 else index,
                    outcomes = run.outcomes.mapIndexed { n, value -> if (n == index) value.copy(status = status) else value },
                    events = run.events + ClickRunEvent(event, plan.actions[index].actionId, run.journalRevision + 1), journalRevision = run.journalRevision + 1)
                runs.saveRun(run)
            }
            repeat(count) { index ->
                advance(ClickActionStatus.Intent, index, ClickRunEventType.DispatchIntent)
                advance(ClickActionStatus.Accepted, index, ClickRunEventType.DispatchAccepted)
                advance(ClickActionStatus.Completed, index, ClickRunEventType.GestureCompleted)
            }
            val evidence = FrameEvidenceStore(context)
            evidence.createSession(project, run.recordingSessionId, source.sourceId)
            val geometry = FrameGeometry.fitCenter(64, 96, 32, 48)
            val tickets = plan.actions.flatMapIndexed { index, action -> FrameBoundary.entries.map { boundary ->
                val frame = if (static) 0L else (index * 2 + boundary.ordinal).toLong()
                FrameTicket(id(), FrameAnchorAction(run.runId, action.actionId, run.recordingSessionId, source.sourceId, run.generation),
                    boundary, 0, frame, frame, frame * 1_000_000, frame * 10_000, geometry,
                    if (static && (index > 0 || boundary == FrameBoundary.After)) FrameFreshness.StaticReuse else FrameFreshness.Fresh)
            } }
            tickets.forEach { ticket ->
                evidence.persistTicket(ticket)
                val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.rgb(40 + ticket.sourceFrameId.toInt() * 10, 80, 120))
                try { evidence.recordPng(ticket, bitmap) } finally { bitmap.recycle() }
                evidence.markEncoder(run.recordingSessionId, ticket.sourceFrameId, ticket.submittedPtsUs, ticket.sourceFrameId)
            }
            val samples = List(if (static) 1 else count * 2) { it * 5_000L }
            evidence.seal(run.recordingSessionId, samples, source.metadata.sha256)
            evidence.registered(run.recordingSessionId, source.sourceId, source.metadata.sha256)
            val accessor = FrameSourceAccessor { owner, session, src ->
                if (owner != project || session != run.recordingSessionId || src != source.sourceId || store.readProject(project) == null ||
                    WorkspaceStore(context, project).read().singleOrNull()?.source != source || !raw.isFile || raw.length() != source.metadata.byteLength ||
                    ViewerPackageCodec.sha256(raw) != source.metadata.sha256) null
                else FrameRegisteredSource(project, session, src, source.metadata.sha256, 32, 48, samples)
            }
            val candidates = tickets.map { (evidence.readCandidate(run.recordingSessionId, it.ticketId, accessor) as FrameCandidateResult.Available).candidate }
            val stages = (candidates.filterIndexed { index, _ -> index % 2 == 0 } + candidates.last()).distinctBy { it.ticket.sourceFrameId }
            val masks = listOf(OpaqueMask(.05f, .05f, .15f, .15f))
            val frames = stages.mapIndexed { index, candidate ->
                val png = evidence.withDecodedFrameSuspending(candidate, accessor) {
                    SafeMediaWriter(context).writePng(it, masks, File(root, "chain-reviewed-${id()}"))
                }
                ClickChainReviewedFrame(candidate, ReviewedStepInput(png.file, png.sha256, png.width, png.height,
                    StepOrigin.VideoFrame(source, candidate.containerPtsUs, 1), masks, id()), "Step ${index + 1}")
            }
            val actions = plan.actions.mapIndexed { index, action ->
                ClickChainConfirmedAction(action.actionId, frames[if (static) 0 else index].frameKey,
                    frames[if (static) 0 else index + 1].frameKey, "Action ${index + 1}", OpaqueMask(.2f, .2f, .4f, .4f), candidates[index * 2], candidates[index * 2 + 1])
            }
            val input = ClickChainImportInput(id(), run, source, "Reviewed independent route", frames, actions,
                frames.first().frameKey, if (static) null else frames.last().frameKey)
            block(Fixture(context, store, project, source, raw, run, evidence, accessor, input))
        }

    private class Fixture(val context: Context, val store: ProjectStore, val project: String, val source: ImportedSource,
        val raw: File, val run: ClickRun, val evidence: FrameEvidenceStore, val accessor: FrameSourceAccessor, val input: ClickChainImportInput) {
        val root: File get() = context.noBackupFilesDir
        fun db(block: SQLiteDatabase.() -> Unit) {
            val helper = ProjectStore.Database(context, File(root, "projects.sqlite").path)
            try { helper.writableDatabase.let { HostSqlite.verify(it); it.block() } } finally { helper.close() }
        }
        fun media() = root.walkTopDown().filter { it.isFile &&
            (it.relativeTo(root).path.startsWith("project-assets/") || it.relativeTo(root).path.startsWith("sources/") || it.name.startsWith("project-media-"))
        }.associate { it.relativeTo(root).path to ViewerPackageCodec.sha256(it) }
        fun assertClean() {
            check(File(root, "click-chain-staging").walkTopDown().none { it.isFile })
            db {
                rawQuery("SELECT COUNT(*) FROM click_chain_files", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
                rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
            }
        }
    }
    companion object { private fun id() = UUID.randomUUID().toString() }
}
