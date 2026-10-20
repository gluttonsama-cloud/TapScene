package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.clickplan.*
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.data.WorkspaceStore
import com.tapscene.media.ImportedSource
import com.tapscene.media.SourceMetadata
import com.tapscene.recording.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Real evidence PNGs/review JSON/SQLite with a synthetic terminal run and bounded source accessor.
 * The accessor replaces host-unavailable MP4 inspection, not ownership or original PNG checks.
 * No device capture, human review, or output creation is inferred from displaying a thumbnail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClickChainThumbnailBatchHostTest {
    private lateinit var app: Application

    @Before fun begin() {
        app = RuntimeEnvironment.getApplication()
        HostSqlite.configure()
        HostFileSyncShadow.begin(app.noBackupFilesDir)
    }

    @After fun end() = HostFileSyncShadow.reset()

    @Test fun visibleOriginalBatchSharesFreshObservationAndLeavesReviewAndProjectUntouched() = runBlocking {
        val f = fixture()
        val requests = f.capture.frames.keys.map { f.request(it) }
        val before = f.privateInputs()
        val project = f.projects.readProject(f.run.projectId)
        val state = f.workspace.state.value
        repeat(2) { index ->
            val images = f.workspace.loadThumbnails(requests + requests.first())
            assertEquals(requests.toSet(), images.keys)
            assertEquals(index + 1, f.observations)
            assertEquals(setOf(Color.RED, Color.BLUE), images.values.map { it.getPixel(0, 0) }.toSet())
            images.values.forEach {
                assertTrue(it.width.toLong() * it.height <= 220_000)
                it.recycle()
            }
            assertEquals(f.saved, f.reviews.readExisting(f.run.runId))
            assertEquals(before, f.privateInputs())
            assertEquals(project, f.projects.readProject(f.run.projectId))
            assertEquals(state, f.workspace.state.value)
            assertFalse(f.workspace.state.value.canCreate)
        }
        // Admission is not per caller convention: an oversized direct batch also does no IO.
        assertTrue(f.workspace.loadThumbnails(List(9) { index ->
            ClickChainThumbnailRequest("foreign:$index", requests.first().source)
        }).isEmpty())
        assertEquals(2, f.observations)
    }

    @Test fun sameDigestDifferentKindAndChangedOwnerOrGenerationCannotReuseOriginalImages() = runBlocking {
        val f = fixture()
        val key = f.capture.frames.keys.first()
        val original = f.request(key)
        f.review(key)
        val reviewed = f.request(key)
        assertEquals(original.source.digest, reviewed.source.digest)
        assertNotEquals(original.source, reviewed.source)
        assertTrue(f.workspace.loadThumbnails(listOf(original)).isEmpty())
        assertTrue(f.workspace.loadThumbnails(listOf(reviewed.copy(
            source = ClickChainThumbnailSource.ReviewedOutput("0".repeat(64))))).isEmpty())
        assertEquals(0, f.observations)
        f.workspace.loadThumbnails(listOf(reviewed)).values.forEach { it.recycle() }
        assertEquals(0, f.observations) // A reviewed-only request never opens the original MP4.

        val rawKey = f.capture.frames.keys.last()
        f.afterObservation = { f.reviews.open(f.run.runId, f.source.metadata.sha256, 1) }
        assertTrue(f.workspace.loadThumbnails(listOf(f.request(rawKey))).isEmpty())
        assertEquals(1, f.observations)
        assertNotEquals(f.saved.owner, f.reviews.readExisting(f.run.runId)?.owner)

        val changed = fixture()
        changed.afterObservation = { field(changed.workspace, "generation", 1L) }
        assertTrue(changed.workspace.loadThumbnails(listOf(changed.request(changed.capture.frames.keys.first()))).isEmpty())
        assertEquals(1, changed.observations)
    }

    @Test fun unavailableOriginalDoesNotHideReviewedOutputAndReviewedFailureNeverFallsBack() = runBlocking {
        val f = fixture()
        val reviewedKey = f.capture.frames.keys.first()
        f.review(reviewedKey)
        val reviewed = f.request(reviewedKey)
        val original = f.request(f.capture.frames.keys.last())
        f.sourceAvailable = false
        val images = f.workspace.loadThumbnails(listOf(original, reviewed))
        assertEquals(setOf(reviewed), images.keys)
        images.values.forEach { it.recycle() }
        assertEquals(1, f.observations)

        val output = checkNotNull(f.saved.frames[reviewedKey]?.output)
        val file = f.reviews.outputFile(f.run.runId, output)
        val bytes = file.readBytes()
        try {
            file.writeBytes(bytes.copyOf(bytes.size / 2))
            f.sourceAvailable = true
            assertTrue(f.workspace.loadThumbnails(listOf(reviewed)).isEmpty())
            assertEquals(1, f.observations)
            assertTrue(f.saved.frames.getValue(reviewedKey).reviewed)
        } finally { file.writeBytes(bytes) }
    }

    @Test fun revocationAndCommittedProjectDeletionDuringSourceObservationRejectOriginalBatch() = runBlocking {
        val revoked = fixture()
        revoked.afterObservation = { revoked.evidence.deleteAfterSourceCommit(revoked.source.sourceId) }
        assertTrue(revoked.workspace.loadThumbnails(revoked.capture.frames.keys.map { revoked.request(it) }).isEmpty())
        assertEquals(1, revoked.observations)
        assertEquals(revoked.saved, revoked.reviews.readExisting(revoked.run.runId))

        val deleted = fixture()
        deleted.afterObservation = { deleted.projects.deleteProject(deleted.run.projectId) }
        assertTrue(deleted.workspace.loadThumbnails(deleted.capture.frames.keys.map { deleted.request(it) }).isEmpty())
        assertEquals(1, deleted.observations)
        assertNull(deleted.projects.readProject(deleted.run.projectId))
        assertEquals(deleted.saved, deleted.reviews.readExisting(deleted.run.runId))
    }

    @Test fun queuedCancellationAndPromptIoCancellationRecycleOnlyUndeliveredBatchImages() = runBlocking {
        val request = ClickChainThumbnailRequest("synthetic", ClickChainThumbnailSource.OriginalUnreviewed("a".repeat(64)))
        val held = Mutex(locked = true)
        var reads = 0
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            readClickChainThumbnailBatch(held, { reads++ }, { true })
        }
        waiting.cancelAndJoin()
        held.unlock()
        assertEquals(0, reads)
        assertTrue(readClickChainThumbnailBatch(Mutex(), { reads++ }, { false }).isEmpty())
        assertEquals(0, reads)
        val queuedGate = Mutex(locked = true)
        var queuedCurrent = true
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            readClickChainThumbnailBatch(queuedGate, { reads++ }, { queuedCurrent })
        }
        queuedCurrent = false
        queuedGate.unlock()
        assertTrue(queued.await().isEmpty())
        assertEquals(0, reads)

        var cancelledImage: Bitmap? = null
        val cancelled = async {
            readClickChainThumbnailBatch(Mutex(), { images ->
                cancelledImage = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                images[request] = checkNotNull(cancelledImage)
                // Cancel after allocation, exactly before withContext can deliver its result.
                currentCoroutineContext().cancel()
            }, { true })
        }
        assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
        assertTrue(checkNotNull(cancelledImage).isRecycled)

        var staleImage: Bitmap? = null
        var current = true
        assertTrue(readClickChainThumbnailBatch(Mutex(), { images ->
            staleImage = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            images[request] = checkNotNull(staleImage)
            current = false
        }, { current }).isEmpty())
        assertTrue(checkNotNull(staleImage).isRecycled)

        var failedImage: Bitmap? = null
        assertTrue(runCatching {
            readClickChainThumbnailBatch(Mutex(), { images ->
                failedImage = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                images[request] = checkNotNull(failedImage)
                error("synthetic read failure")
            }, { true })
        }.isFailure)
        assertTrue(checkNotNull(failedImage).isRecycled)

        val delivered = readClickChainThumbnailBatch(Mutex(), { images ->
            images[request] = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        }, { true })
        assertFalse(delivered.getValue(request).isRecycled)
        delivered.getValue(request).recycle()
    }

    private fun fixture(): Fixture {
        val projects = ProjectStore(app)
        val project = projects.createProject("合成缩略图项目").project.id
        val sourceId = id()
        val raw = File(app.noBackupFilesDir, "sources/$sourceId.mp4").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(128) { it.toByte() })
        }
        val source = ImportedSource(sourceId, "sources/$sourceId.mp4", "synthetic.mp4",
            SourceMetadata("video/mp4", raw.length(), sha(raw), 32, 48, 0, 1_000_000))
        WorkspaceStore(app, project).append(source)
        val plan = ClickPlan.create(project, "com.example.synthetic", 64, 96, 0, listOf(ClickAction(x = 20, y = 30)))
        val initial = ClickRun.create(plan, id(), sourceId, 1, 1)
        val run = initial.copy(phase = ClickRunPhase.Completed, nextActionIndex = 1,
            outcomes = initial.outcomes.map { it.copy(status = ClickActionStatus.Completed) })
        val evidence = FrameEvidenceStore(app)
        evidence.createSession(project, run.recordingSessionId, sourceId)
        val geometry = FrameGeometry.fitCenter(64, 96, 32, 48)
        val tickets = FrameBoundary.entries.mapIndexed { index, boundary ->
            FrameTicket(id(), FrameAnchorAction(run.runId, plan.actions.single().actionId,
                run.recordingSessionId, sourceId, run.generation), boundary, 0,
                index.toLong(), index.toLong(), index * 1_000_000L, index * 10_000L,
                geometry, FrameFreshness.Fresh)
        }
        tickets.forEachIndexed { index, ticket ->
            evidence.persistTicket(ticket)
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (index == 0) Color.RED else Color.BLUE)
            }
            try { evidence.recordPng(ticket, bitmap) } finally { bitmap.recycle() }
            evidence.markEncoder(run.recordingSessionId, ticket.sourceFrameId, ticket.submittedPtsUs, index.toLong())
        }
        val samples = listOf(0L, 5_000L)
        evidence.seal(run.recordingSessionId, samples, source.metadata.sha256)
        evidence.registered(run.recordingSessionId, sourceId, source.metadata.sha256)
        val accessor = FrameSourceAccessor { owner, session, src ->
            if (owner != project || session != run.recordingSessionId || src != sourceId ||
                projects.readProject(project) == null || WorkspaceStore(app, project).read().singleOrNull()?.source != source ||
                !raw.isFile || sha(raw) != source.metadata.sha256) null
            else FrameRegisteredSource(project, session, src, source.metadata.sha256, 32, 48, samples)
        }
        val candidates = tickets.map {
            (evidence.readCandidate(run.recordingSessionId, it.ticketId, accessor) as FrameCandidateResult.Available).candidate
        }
        val capture = ClickChainCapture(run, source, listOf(ClickChainActionEvidence(0, plan.actions.single(),
            ClickActionStatus.Completed, candidates[0], candidates[1], null, null)))
        val reviews = ClickChainReviewStore(app)
        val saved = reviews.open(run.runId, source.metadata.sha256, 1)
        val workspace = ClickChainWorkspace(app)
        return Fixture(projects, source, raw, run, evidence, capture, reviews, saved, workspace).apply {
            field(workspace, "capture", capture)
            field(workspace, "draft", saved)
            val factory: (() -> Unit) -> FrameSourceAccessor = { checkActive ->
                FrameSourceAccessor { owner, session, src ->
                    checkActive()
                    observations++
                    val observed = if (sourceAvailable) accessor.registeredSource(owner, session, src) else null
                    afterObservation?.invoke()
                    checkActive()
                    observed
                }
            }
            field(workspace, "thumbnailSourceAccessor", factory)
        }
    }

    private inner class Fixture(val projects: ProjectStore, val source: ImportedSource, val raw: File,
        val run: ClickRun, val evidence: FrameEvidenceStore, val capture: ClickChainCapture,
        val reviews: ClickChainReviewStore, var saved: ClickChainReviewDraft, val workspace: ClickChainWorkspace) {
        var observations = 0
        var sourceAvailable = true
        var afterObservation: (() -> Unit)? = null
        fun request(key: String) = ClickChainThumbnailRequest(key, checkNotNull(capture.thumbnailSource(saved, key)))
        fun privateInputs(): Map<String, String> = listOf(
            File(app.noBackupFilesDir, "frame-evidence/${run.recordingSessionId}"),
            File(app.noBackupFilesDir, "click-chain-reviews/${run.runId}"),
        ).flatMap { it.walkTopDown().filter(File::isFile).toList() }
            .associate { it.path to sha(it) }
        fun review(key: String) {
            val candidate = capture.frames.getValue(key)
            val (working, folder) = reviews.beginOutput(saved)
            check(folder.mkdir())
            val original = File(app.noBackupFilesDir,
                "frame-evidence/${run.recordingSessionId}/${candidate.ticket.ticketId}.png")
            val outputFile = original.copyTo(File(folder, "candidate-${id()}.png"))
            val output = reviews.output(working, outputFile, sha(outputFile), candidate.width, candidate.height)
            val reviewed = reviews.save(working.copy(serial = working.serial + 1,
                frames = working.frames + (key to ClickChainFrameDraft(key, candidate.pngSha256, "合成已复核画面",
                    output = output, reviewed = true))))
            saved = reviews.finishOutput(reviewed, folder)
            field(workspace, "draft", saved)
        }
    }

    private fun field(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun id() = UUID.randomUUID().toString()
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
