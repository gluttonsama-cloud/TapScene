package com.tapscene.recording

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.WorkspaceStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bounded persistence/ownership group. Native host PNG and real fsync are not device capture tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameEvidenceStoreHostTest {
    private lateinit var root: File
    private lateinit var context: ContextWrapper
    private val runId = id()
    private val projectId = id()
    private val sessionId = id()
    private val sourceId = id()
    private val sourceSha = "a".repeat(64)

    @Before fun begin() {
        val application = RuntimeEnvironment.getApplication()
        root = File(application.noBackupFilesDir, "frame-evidence-fixture-${id()}")
        check(root.mkdirs())
        context = object : ContextWrapper(application) { override fun getNoBackupFilesDir(): File = root }
        HostFileSyncShadow.begin(root)
    }

    @After fun end() {
        try { HostFileSyncShadow.reset() } finally { root.deleteRecursively() }
    }

    @Test fun durableImmutableTicketAndValidatedPixelsSurviveRecordingTemporaryCleanup() {
        val journals = RecordingJournalStore(context)
        // A successfully read absence must also confirm directory durability before cleanup.
        assertTrue(WorkspaceStore(context, projectId).confirmSourceAbsent(sourceId))
        val legacy = RecordingJournal(sessionId, projectId, sourceId, JournalPhase.Starting, 123)
        journals.create(legacy)
        val journalFile = File(root, "recordings/$sessionId/session.json")
        val legacyBytes = journalFile.readBytes()
        val store = newSession()
        val ticket = ticket()
        store.persistTicket(ticket)
        val before = persistedTicket()
        bitmap().let { image ->
            try { store.recordPng(ticket, image); assertFalse(image.isRecycled) } finally { image.recycle() }
        }
        // The extractor's sealed-container origin may differ from the submitted encoder PTS.
        store.markEncoder(sessionId, ticket.sourceFrameId, ticket.submittedPtsUs, 1)
        store.seal(sessionId, listOf(0, 7_000, 50_000), sourceSha)
        assertMissing(store.readCandidate(sessionId, ticket.ticketId, source()), FrameMissingReason.SourceUnregistered)
        store.registered(sessionId, sourceId, sourceSha)
        val reopened = FrameEvidenceStore(context)
        val candidate = (reopened.readCandidate(sessionId, ticket.ticketId, source()) as FrameCandidateResult.Available).candidate
        assertEquals(ticket, candidate.ticket)
        assertEquals(7_000L, candidate.containerPtsUs)
        assertEquals(40_000L, candidate.encoderPtsUs)
        assertEquals(1L, candidate.muxSampleOrdinal)
        assertEquals(before.toString(), persistedTicket().toString())
        assertArrayEquals(legacyBytes, journalFile.readBytes())
        assertEquals(legacy, journals.readAll().single())
        check(journals.deleteRaw(sessionId))
        assertTrue(File(root, "frame-evidence/$sessionId/${ticket.ticketId}.png").isFile)
        assertTrue(reopened.readCandidate(sessionId, ticket.ticketId, source()) is FrameCandidateResult.Available)

        var scoped: Bitmap? = null
        assertEquals(Color.RED, reopened.withDecodedFrame(candidate, source()) {
            scoped = it
            it.getPixel(0, 0)
        })
        assertTrue(scoped!!.isRecycled)
        runBlocking {
            val entered = CompletableDeferred<Bitmap>()
            val writer = launch {
                reopened.withDecodedFrameSuspending(candidate, source()) { image ->
                    entered.complete(image)
                    awaitCancellation()
                }
            }
            val image = entered.await()
            writer.cancelAndJoin()
            assertTrue(image.isRecycled)
        }
        assertMissing(reopened.readCandidate(sessionId, ticket.ticketId, source(hash = "b".repeat(64))), FrameMissingReason.SourceMismatch)
        assertMissing(reopened.readCandidate(sessionId, ticket.ticketId, source(samples = listOf(0, 9_000))), FrameMissingReason.SampleMismatch)
        assertMissing(reopened.readCandidate(sessionId, ticket.ticketId, source(width = 8)), FrameMissingReason.GeometryUnverified)
        expectFailure { reopened.persistTicket(ticket.copy(sourceTimestampNs = ticket.sourceTimestampNs + 1)) }
        expectFailure { reopened.markEncoder(sessionId, ticket.sourceFrameId, ticket.submittedPtsUs, 2) }
        expectFailure { reopened.seal(sessionId, listOf(0, 8_000), sourceSha) }
        File(root, "frame-evidence/$sessionId/${ticket.ticketId}.png").appendBytes(byteArrayOf(1))
        assertTrue(reopened.readCandidate(sessionId, ticket.ticketId, source()) is FrameCandidateResult.Missing)
        HostFileSyncShadow.assertDirectorySyncEvidence()
    }

    @Test fun incompleteRecoveryNeverResumesPngOrRewritesLegacyJournals() {
        val journals = RecordingJournalStore(context)
        val legacy = RecordingJournal(sessionId, projectId, sourceId, JournalPhase.Interrupted, 123,
            stopReason = RecordingStopReason.ProcessInterrupted)
        journals.create(legacy)
        val legacyFile = File(root, "recordings/$sessionId/session.json")
        val legacyBytes = legacyFile.readBytes()
        val store = newSession()
        val ticket = ticket()
        store.persistTicket(ticket)
        // The first evidence revision had no misses array; it remains readable.
        manifest().writeText(JSONObject(manifest().readText()).apply { remove("misses") }.toString())
        assertEquals(ticket.ticketId, FrameEvidenceStore(context).listBoundaries(sessionId).single().ticketId)
        val missingAction = ticket.action.copy(actionId = id())
        store.persistMissing(missingAction, FrameBoundary.After, 3, FrameMissingReason.NoNewFrame)
        store.persistMissing(missingAction, FrameBoundary.After, 3, FrameMissingReason.NoNewFrame)
        assertEquals(FrameBoundaryMetadata(missingAction, FrameBoundary.After, 3, null, FrameMissingReason.NoNewFrame),
            store.listBoundaries(sessionId).last())
        expectFailure { store.persistMissing(missingAction, FrameBoundary.After, 3, FrameMissingReason.NoFrame) }
        expectFailure { store.persistMissing(ticket.action, ticket.boundary, ticket.epoch, FrameMissingReason.QueueFull) }
        val output = File(root, "frame-evidence/$sessionId/${ticket.ticketId}.png")
        output.writeBytes(byteArrayOf(1, 2, 3)) // uncertain image/manifest boundary; never adopt it
        store.recoverInterrupted()
        assertMissing(store.readCandidate(sessionId, ticket.ticketId, source()), FrameMissingReason.Interrupted)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.readBytes())
        val afterRecovery = manifest().readBytes()
        FrameEvidenceStore(context).recoverInterrupted()
        assertArrayEquals(afterRecovery, manifest().readBytes())
        bitmap().let { image -> try { expectFailure { store.recordPng(ticket, image) } } finally { image.recycle() } }
        assertArrayEquals(legacyBytes, legacyFile.readBytes())
        assertEquals(legacy, RecordingJournalStore(context).readAll().single())
        // A torn/unknown manifest is kept; neither recovery nor a deletion guess sweeps it.
        manifest().writeText("{incomplete")
        store.recoverInterrupted()
        store.deleteAfterSourceCommit(sourceId)
        assertTrue(output.exists())
        assertEquals("{incomplete", manifest().readText())
    }

    @Test fun committedDeletionRevokesLateWritersAndNeverTouchesForeignSourcesOrFiles() {
        val store = newSession()
        val ticket = ticket()
        store.persistTicket(ticket)
        bitmap().let { image -> try { store.recordPng(ticket, image) } finally { image.recycle() } }
        val secondSession = id()
        val secondSource = id()
        store.createSession(projectId, secondSession, secondSource)
        val independent = File(root, "recordings/$sessionId/sealed.mp4").apply { parentFile!!.mkdirs(); writeText("original video") }
        val repositoryCopy = File(root, "sources/$sourceId.mp4").apply { parentFile!!.mkdirs(); writeText("repository original") }
        store.deleteAfterSourceCommit(sourceId)
        assertFalse(File(root, "frame-evidence/$sessionId").exists())
        assertTrue(File(root, "frame-evidence/$secondSession/evidence.json").exists())
        assertEquals("original video", independent.readText())
        assertEquals("repository original", repositoryCopy.readText())
        expectFailure { store.persistTicket(ticket) }
        expectFailure { FrameEvidenceStore(context).createSession(projectId, sessionId, sourceId) }
        assertTrue(store.readCandidate(sessionId, ticket.ticketId, source()) is FrameCandidateResult.Missing)

        val foreign = File(root, "frame-evidence/$secondSession/unowned.bin").apply { writeText("keep") }
        expectFailure { store.deleteAfterProjectCommit(projectId) }
        assertEquals("keep", foreign.readText())
        assertTrue(File(root, "frame-evidence/$secondSession/evidence.json").exists())
        expectFailure { store.createSession(projectId, secondSession, secondSource) }
        // Retry only the exact already-authorized revocation after its transient cleanup blocker.
        check(foreign.delete())
        FrameEvidenceStore(context).recoverInterrupted()
        assertFalse(File(root, "frame-evidence/$secondSession").exists())
        expectFailure { store.createSession(projectId, secondSession, secondSource) }
        // A symbolic-link session is not an owned directory and must not be traversed/deleted.
        val outside = File(root, "unrelated").apply { mkdir() }
        val linkedSession = id()
        Files.createSymbolicLink(File(root, "frame-evidence/$linkedSession").toPath(), outside.toPath())
        expectFailure { store.createSession(projectId, linkedSession, id()) }
        store.deleteAfterSourceCommit(id())
        assertTrue(outside.isDirectory)
    }

    @Test fun actualPartialBytesAndTicketCountAreBoundedAndRegisteredOwnershipCannotBeDiscarded() {
        val store = newSession()
        val ticket = ticket()
        store.persistTicket(ticket)
        val pending = ticket(actionId = id(), sourceFrameId = 2)
        store.persistTicket(pending)
        val part = File(root, "frame-evidence/$sessionId/${pending.ticketId}.png.part")
        RandomAccessFile(part, "rw").use { it.setLength(FrameEvidenceStore.MAX_IMAGE_BYTES) }
        bitmap().let { image -> try { expectFailure { store.recordPng(ticket, image) } } finally { image.recycle() } }
        assertMissing(store.readCandidate(sessionId, ticket.ticketId, source()), FrameMissingReason.BudgetExceeded)
        assertEquals(FrameEvidenceStore.MAX_IMAGE_BYTES, part.length())
        for (index in 2 until FrameEvidenceStore.MAX_IMAGES) {
            store.persistTicket(ticket(actionId = id(), sourceFrameId = index.toLong() + 1))
        }
        expectFailure { store.persistTicket(ticket(actionId = id(), sourceFrameId = 100)) }
        expectFailure { store.deleteUnregisteredSession(sessionId, id()) }
        store.seal(sessionId, listOf(0), sourceSha)
        store.registered(sessionId, sourceId, sourceSha)
        expectFailure { store.deleteUnregisteredSession(sessionId, sourceId) }
        assertTrue(manifest().exists())
    }

    private fun newSession(): FrameEvidenceStore = FrameEvidenceStore(context).also { it.createSession(projectId, sessionId, sourceId) }
    private fun manifest() = File(root, "frame-evidence/$sessionId/evidence.json")
    private fun persistedTicket(): JSONObject = JSONObject(manifest().readText()).getJSONArray("frames").getJSONObject(0).let { frame ->
        // Asynchronous supplements are deliberately separate from immutable ticket fields.
        JSONObject().also { ticket ->
            listOf("ticketId", "action", "boundary", "epoch", "sourceFrameId", "captureSequence", "sourceTimestampNs",
                "submittedPtsUs", "geometry", "freshness").forEach { ticket.put(it, frame.get(it)) }
        }
    }
    private fun ticket(actionId: String = id(), sourceFrameId: Long = 1) = FrameTicket(id(),
        FrameAnchorAction(runId, actionId, sessionId, sourceId, 4), FrameBoundary.Before, 3,
        sourceFrameId, sourceFrameId, 900_000_000L + sourceFrameId, 40_000,
        FrameGeometry.fitCenter(8, 8, 4, 4), FrameFreshness.Fresh)
    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
    private fun source(hash: String = sourceSha, samples: List<Long> = listOf(0, 7_000, 50_000), width: Int = 4) =
        FrameSourceAccessor { _, _, _ -> FrameRegisteredSource(projectId, sessionId, sourceId, hash, width, 4, samples) }
    private fun assertMissing(result: FrameCandidateResult, reason: FrameMissingReason) {
        assertEquals(FrameCandidateResult.Missing(reason), result)
    }
    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected operation to fail closed", failed)
    }
    private fun id(): String = UUID.randomUUID().toString()
}
