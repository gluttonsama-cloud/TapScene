package com.tapscene.recording

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater
import org.json.JSONArray
import org.json.JSONObject

/** A fresh, verified repository observation. No private path escapes the source repository. */
internal data class FrameRegisteredSource(
    val projectId: String,
    val sessionId: String,
    val sourceId: String,
    val sourceSha256: String,
    val frameWidth: Int,
    val frameHeight: Int,
    /** Actual MediaExtractor sample order and PTS, not computed from fps or uptime. */
    val samplePtsUs: List<Long>,
)

/** The implementation must re-read registration/ownership and hash the actual registered MP4. */
internal fun interface FrameSourceAccessor {
    fun registeredSource(projectId: String, sessionId: String, sourceId: String): FrameRegisteredSource?
}

/** Private, UNREVIEWED technical evidence. This is not a step image or an export asset. */
internal data class FrameEvidenceCandidate(
    val ticket: FrameTicket,
    val pngSha256: String,
    val width: Int,
    val height: Int,
    val encoderPtsUs: Long,
    val muxSampleOrdinal: Long,
    val containerPtsUs: Long,
    val sourceSha256: String,
)

internal sealed interface FrameCandidateResult {
    data class Available(val candidate: FrameEvidenceCandidate) : FrameCandidateResult
    data class Missing(val reason: FrameMissingReason) : FrameCandidateResult
}

/**
 * Private per-source evidence, independent of recordings/<session>/capture.part.mp4 and sealed.mp4.
 * Every method does synchronous disk work and belongs on the dedicated evidence worker, never
 * the action runner or GL thread. Tickets are write-once; only separately recorded supplements
 * may be appended. A failed/uncertain commit preserves files and never changes a ClickRun.
 */
internal class FrameEvidenceStore(context: Context) {
    private val base = context.noBackupFilesDir.canonicalFile
    private val root = File(base, "frame-evidence")
    private val revoked = File(root, "revoked")

    init {
        requireDirectory(root, base, create = true)
        requireDirectory(revoked, root, create = true)
        syncDirectory(root)
        syncDirectory(base)
    }

    fun createSession(projectId: String, sessionId: String, sourceId: String) = synchronized(lock) {
        listOf(projectId, sessionId, sourceId).forEach(::requireFrameUuid)
        val directory = directory(sessionId)
        check(!isRevoked(sessionId)) { "Evidence session was deleted" }
        if (directory.exists()) {
            val old = read(sessionId)
            check(old.projectId == projectId && old.sourceId == sourceId) { "Evidence session ownership differs" }
            // Retry also confirms a previously uncertain directory sync.
            syncDirectory(directory)
            return@synchronized
        }
        check(directory.mkdir()) { "Cannot create frame evidence session" }
        syncDirectory(root)
        save(Session(projectId, sessionId, sourceId))
    }

    fun persistTicket(ticket: FrameTicket) = synchronized(lock) {
        val session = read(ticket.action.sessionId)
        check(session.sourceId == ticket.action.sourceId) { "Frame belongs to another source" }
        val old = session.frames.firstOrNull { it.ticket.ticketId == ticket.ticketId }
        if (old != null) {
            check(old.ticket == ticket) { "Frame ticket is immutable" }
            return@synchronized
        }
        check(!session.sealed && !session.registered) { "Frame evidence session is closed" }
        check(session.frames.size + session.misses.size < MAX_IMAGES) { "Frame evidence count exhausted" }
        check((session.frames.map { it.ticket.action } + session.misses.map { it.action }).all {
            it.runId == ticket.action.runId && it.generation == ticket.action.generation }) { "Evidence session belongs to another run" }
        check(session.frames.none { it.ticket.action == ticket.action && it.ticket.boundary == ticket.boundary } &&
            session.misses.none { it.action == ticket.action && it.boundary == ticket.boundary }) {
            "An action boundary already has a ticket"
        }
        session.frames.filter { it.ticket.sourceFrameId == ticket.sourceFrameId }.forEach { previous ->
            val frame = previous.ticket
            check(frame.captureSequence == ticket.captureSequence && frame.sourceTimestampNs == ticket.sourceTimestampNs &&
                frame.submittedPtsUs == ticket.submittedPtsUs && frame.geometry == ticket.geometry) {
                "Captured frame identity cannot be rebound"
            }
        }
        save(session.copy(frames = session.frames + FrameRecord(ticket)))
    }

    /** Immutable evidence for a boundary at which no frame could be selected; never invent a PTS. */
    fun persistMissing(action: FrameAnchorAction, boundary: FrameBoundary, epoch: Long, reason: FrameMissingReason) = synchronized(lock) {
        require(epoch >= 0)
        val session = read(action.sessionId)
        check(session.sourceId == action.sourceId) { "Missing boundary belongs to another source" }
        val missing = MissingBoundary(action, boundary, epoch, reason)
        val previous = session.misses.firstOrNull { it.action == action && it.boundary == boundary }
        if (previous != null) {
            check(previous == missing) { "Missing boundary is immutable" }
            return@synchronized
        }
        check(!session.sealed && !session.registered)
        check(session.frames.size + session.misses.size < MAX_IMAGES) { "Frame evidence boundary count exhausted" }
        check(session.frames.none { it.ticket.action == action && it.ticket.boundary == boundary }) { "Boundary already selected a ticket" }
        check((session.frames.map { it.ticket.action } + session.misses.map { it.action }).all {
            it.runId == action.runId && it.generation == action.generation }) { "Evidence session belongs to another run" }
        save(session.copy(misses = session.misses + missing))
    }

    /** No paths or pixels. These entries are discovery hints, not validated candidates or privacy reviews. */
    fun listBoundaries(sessionId: String): List<FrameBoundaryMetadata> = synchronized(lock) {
        val session = read(sessionId)
        session.frames.map { FrameBoundaryMetadata(it.ticket.action, it.ticket.boundary, it.ticket.epoch,
            it.ticket.ticketId, it.missingReason) } + session.misses.map {
            FrameBoundaryMetadata(it.action, it.boundary, it.epoch, null, it.reason)
        }
    }

    /** Caller retains its bitmap and must not mutate/recycle it until this worker call returns. */
    fun recordPng(ticket: FrameTicket, bitmap: Bitmap) {
        check(!bitmap.isRecycled && bitmap.width == ticket.geometry.frameWidth && bitmap.height == ticket.geometry.frameHeight)
        val stream = LimitedBytes(MAX_PNG_BYTES)
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Cannot encode frame PNG" }
        recordPng(ticket, stream.toByteArray())
    }

    fun recordPng(ticket: FrameTicket, pngBytes: ByteArray) = synchronized(lock) {
        // Freeze before validating and writing. The capture producer owns the original buffer.
        require(pngBytes.size in 1..MAX_PNG_BYTES)
        val bytes = pngBytes.copyOf()
        val session = read(ticket.action.sessionId)
        val frame = requireTicket(session, ticket)
        check(frame.missingReason == null) { "Missing frame evidence cannot be resumed" }
        val geometry = ticket.geometry
        decodePng(bytes, geometry.frameWidth, geometry.frameHeight).recycle()
        val digest = sha256(bytes)
        val png = Png(digest, bytes.size.toLong(), geometry.frameWidth, geometry.frameHeight)
        if (frame.png != null) {
            check(frame.png == png && readPng(session, frame).contentEquals(bytes)) { "PNG supplement is immutable" }
            return@synchronized
        }
        val output = child(session.sessionId, "${ticket.ticketId}.png")
        val temporary = child(session.sessionId, "${ticket.ticketId}.png.part")
        // Count actual files too: a prior uncertain metadata commit may have left a private PNG.
        val ownedFiles = directory(session.sessionId).listFiles().orEmpty().filter {
            it.name.endsWith(".png") || it.name.endsWith(".png.part")
        }
        check(ownedFiles.size <= MAX_IMAGES * 2 && ownedFiles.all(::isPrivateRegularFile)) { "Invalid evidence image files" }
        val retained = ownedFiles.filter { it != output && it != temporary }.sumOf { it.length() }
        if (retained + bytes.size > MAX_IMAGE_BYTES ||
            ownedFiles.count { it.name.endsWith(".png") && it != output } >= MAX_IMAGES) {
            save(session.replacing(frame.copy(missingReason = FrameMissingReason.BudgetExceeded)))
            throw IOException("Frame evidence image budget exhausted")
        }
        if (output.exists()) {
            check(readBounded(output, MAX_PNG_BYTES).contentEquals(bytes)) { "Unknown existing frame PNG" }
        } else {
            checkPngFreeSpace(session.sessionId, bytes.size.toLong())
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            check(readBounded(temporary, MAX_PNG_BYTES).contentEquals(bytes)) { "Incomplete frame PNG write" }
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        // Never delete the output if this sync or the metadata commit has an unknown result.
        syncDirectory(directory(session.sessionId))
        check(readBounded(output, MAX_PNG_BYTES).contentEquals(bytes)) { "Frame PNG commit not confirmed" }
        save(session.replacing(frame.copy(png = png)))
    }

    /** Called only for a successfully muxed, real codec output sample. Ordinal is zero based. */
    fun markEncoder(sessionId: String, sourceFrameId: Long, encoderPtsUs: Long, muxSampleOrdinal: Long) = synchronized(lock) {
        require(sourceFrameId >= 0 && encoderPtsUs >= 0 && muxSampleOrdinal in 0 until MAX_SAMPLES.toLong())
        val session = read(sessionId)
        val matched = session.frames.filter { it.ticket.sourceFrameId == sourceFrameId }
        check(matched.isNotEmpty()) { "Unknown capture frame" }
        val changed = session.frames.map { frame ->
            if (frame.ticket.sourceFrameId != sourceFrameId) frame else {
                check(frame.ticket.submittedPtsUs == encoderPtsUs) { "Encoder PTS is not the submitted frame" }
                check(frame.encoderPtsUs == null || (frame.encoderPtsUs == encoderPtsUs && frame.muxSampleOrdinal == muxSampleOrdinal)) {
                    "Encoder supplement is immutable"
                }
                frame.copy(encoderPtsUs = encoderPtsUs, muxSampleOrdinal = muxSampleOrdinal)
            }
        }
        // Two different rendered frame identities cannot claim one encoded sample.
        check(changed.filter { it.muxSampleOrdinal == muxSampleOrdinal }.all { it.ticket.sourceFrameId == sourceFrameId })
        save(session.copy(frames = changed))
    }

    /** Bind each persisted encoder ordinal to its actual sealed-container sample, never nearest PTS. */
    fun seal(sessionId: String, samplePtsUs: List<Long>, sourceSha256: String) = synchronized(lock) {
        requireHash(sourceSha256)
        require(samplePtsUs.isNotEmpty() && samplePtsUs.size <= MAX_SAMPLES && samplePtsUs.all { it >= 0 })
        val session = read(sessionId)
        check(session.sourceSha256 == null || session.sourceSha256 == sourceSha256) { "Sealed source identity changed" }
        val samples = samplePtsUs.toList()
        val frames = session.frames.map { frame ->
            val ordinal = frame.muxSampleOrdinal
            val actual = if (ordinal != null && ordinal < samples.size) samples[ordinal.toInt()] else null
            check(frame.containerPtsUs == null || frame.containerPtsUs == actual) { "Container sample identity changed" }
            frame.copy(containerPtsUs = actual, sourceSha256 = sourceSha256,
                missingReason = frame.missingReason ?: when {
                    frame.png == null -> FrameMissingReason.PngFailed
                    actual == null -> FrameMissingReason.EncoderUnmatched
                    else -> null
                })
        }
        save(session.copy(sealed = true, sourceSha256 = sourceSha256, frames = frames))
    }

    /** Call only after SourceRepository confirms the registration and the actual source SHA. */
    fun registered(sessionId: String, sourceId: String, sourceSha256: String) = synchronized(lock) {
        requireFrameUuid(sourceId)
        requireHash(sourceSha256)
        val session = read(sessionId)
        check(session.sourceId == sourceId && session.sealed && session.sourceSha256 == sourceSha256) {
            "Unsealed or changed source cannot be registered as evidence"
        }
        save(session.copy(registered = true))
    }

    fun missing(ticket: FrameTicket, reason: FrameMissingReason) = synchronized(lock) {
        val session = read(ticket.action.sessionId)
        val frame = requireTicket(session, ticket)
        // Late failures do not replace an earlier, more direct failure reason.
        if (frame.missingReason == null) save(session.replacing(frame.copy(missingReason = reason)))
    }

    /** Never restart capture, PNG work, projection, gestures, or a run. Keep incomplete bytes private. */
    fun recoverInterrupted() = synchronized(lock) {
        directories().forEach { directory ->
            val session = runCatching { read(directory.name, allowRevoked = true) }.getOrNull() ?: return@forEach
            if (isRevoked(session.sessionId)) {
                // A valid committed tombstone names the exact known-deleted owner. An incomplete
                // marker or a malformed manifest is not deletion authority and stays untouched.
                if (hasCommittedRevocation(session)) runCatching { deleteSession(session) }
                return@forEach
            }
            val frames = session.frames.map { frame ->
                if (frame.missingReason == null && (frame.png == null || frame.encoderPtsUs == null ||
                        frame.containerPtsUs == null || frame.sourceSha256 == null)) {
                    frame.copy(missingReason = FrameMissingReason.Interrupted)
                } else frame
            }
            if (frames != session.frames) save(session.copy(frames = frames))
        }
    }

    /**
     * Read-only gate for the later redaction pipeline. It requires a fresh repository observation,
     * sealed/registered binding, exact ordinal/PTS, actual PNG hash and a complete pixel decode.
     * No path, original pixels, source URI or export-ready media is returned.
     */
    fun readCandidate(sessionId: String, ticketId: String, sources: FrameSourceAccessor): FrameCandidateResult {
        try {
            // No repository callback while holding the evidence lock: deletion takes repository
            // locks first and must never deadlock with this read-only consumer.
            val observed = synchronized(lock) { read(sessionId) }
            requireFrameUuid(ticketId)
            val source = if (observed.sealed && observed.registered) {
                sources.registeredSource(observed.projectId, observed.sessionId, observed.sourceId)
            } else null
            return synchronized(lock) {
                val session = read(sessionId)
                check(session == observed) { "Evidence changed during source validation" }
                val frame = session.frames.firstOrNull { it.ticket.ticketId == ticketId }
                    ?: return@synchronized FrameCandidateResult.Missing(FrameMissingReason.EvidenceUnavailable)
                frame.missingReason?.let { return@synchronized FrameCandidateResult.Missing(it) }
                if (!session.sealed) return@synchronized FrameCandidateResult.Missing(FrameMissingReason.SourceUnsealed)
                if (!session.registered || source == null) {
                    return@synchronized FrameCandidateResult.Missing(FrameMissingReason.SourceUnregistered)
                }
                if (source.projectId != session.projectId || source.sessionId != session.sessionId ||
                    source.sourceId != session.sourceId || source.sourceSha256 != session.sourceSha256 ||
                    source.sourceSha256 != frame.sourceSha256) {
                    return@synchronized FrameCandidateResult.Missing(FrameMissingReason.SourceMismatch)
                }
                val geometry = frame.ticket.geometry
                if (source.frameWidth != geometry.frameWidth || source.frameHeight != geometry.frameHeight) {
                    return@synchronized FrameCandidateResult.Missing(FrameMissingReason.GeometryUnverified)
                }
                val ordinal = frame.muxSampleOrdinal
                if (ordinal == null || frame.encoderPtsUs != frame.ticket.submittedPtsUs ||
                    source.samplePtsUs.size !in 1..MAX_SAMPLES || ordinal < 0 || ordinal >= source.samplePtsUs.size ||
                    frame.containerPtsUs == null || source.samplePtsUs[ordinal.toInt()] != frame.containerPtsUs) {
                    return@synchronized FrameCandidateResult.Missing(FrameMissingReason.SampleMismatch)
                }
                val png = frame.png ?: return@synchronized FrameCandidateResult.Missing(FrameMissingReason.PngFailed)
                val bytes = readPng(session, frame)
                decodePng(bytes, png.width, png.height).recycle()
                FrameCandidateResult.Available(FrameEvidenceCandidate(frame.ticket, png.sha256, png.width, png.height,
                    checkNotNull(frame.encoderPtsUs), ordinal, frame.containerPtsUs, source.sourceSha256))
            }
        } catch (_: Exception) {
            return FrameCandidateResult.Missing(FrameMissingReason.EvidenceUnavailable)
        }
    }

    /**
     * Scoped, private redaction input only. The callback must not retain/recycle the bitmap; it is
     * recycled before return. The future consumer must use SafeMediaWriter and actual-output review
     * before a new PNG becomes a step. Merely obtaining this bitmap conveys no review authority.
     */
    fun <T> withDecodedFrame(candidate: FrameEvidenceCandidate, sources: FrameSourceAccessor, consume: (Bitmap) -> T): T {
        val bitmap = decodeCandidate(candidate, sources)
        try { return consume(bitmap) } finally { bitmap.recycle() }
    }

    /** Suspending SafeMediaWriter bridge. No evidence/repository lock is held across suspension. */
    suspend fun <T> withDecodedFrameSuspending(candidate: FrameEvidenceCandidate, sources: FrameSourceAccessor,
        consume: suspend (Bitmap) -> T): T {
        val bitmap = decodeCandidate(candidate, sources)
        try { return consume(bitmap) } finally { bitmap.recycle() }
    }

    private fun decodeCandidate(candidate: FrameEvidenceCandidate, sources: FrameSourceAccessor): Bitmap {
        val current = readCandidate(candidate.ticket.action.sessionId, candidate.ticket.ticketId, sources)
        check(current is FrameCandidateResult.Available && current.candidate == candidate) { "Frame evidence is no longer valid" }
        return synchronized(lock) {
            val session = read(candidate.ticket.action.sessionId)
            val frame = requireTicket(session, candidate.ticket)
            decodePng(readPng(session, frame), candidate.width, candidate.height)
        }
    }

    /** Call only after source deletion is known committed. An uncertain commit must not call this. */
    fun deleteAfterSourceCommit(sourceId: String) = synchronized(lock) {
        requireFrameUuid(sourceId)
        deleteOwned { it.sourceId == sourceId }
    }

    /** Call only after project deletion is known committed, including its source ownership. */
    fun deleteAfterProjectCommit(projectId: String) = synchronized(lock) {
        requireFrameUuid(projectId)
        deleteOwned { it.projectId == projectId }
    }

    /** Caller first confirms this session has no registered source; unknown registration preserves it. */
    fun deleteUnregisteredSession(sessionId: String, sourceId: String) = synchronized(lock) {
        requireFrameUuid(sourceId)
        val session = read(sessionId, allowRevoked = true)
        check(session.sourceId == sourceId && !session.registered) { "Registered/foreign evidence cannot be discarded" }
        deleteSession(session)
    }

    private fun deleteOwned(matches: (Session) -> Boolean) {
        directories().forEach { directory ->
            // Invalid ownership is never permission to remove files.
            val session = runCatching { read(directory.name, allowRevoked = true) }.getOrNull() ?: return@forEach
            if (matches(session)) deleteSession(session)
        }
    }

    private fun deleteSession(session: Session) {
        // First durably revoke the exact identity. Late queued work and createSession retries
        // cannot resurrect originals even after process restart or a partial cleanup failure.
        revoke(session)
        val directory = directory(session.sessionId)
        val names = session.frames.flatMap { listOf("${it.ticket.ticketId}.png", "${it.ticket.ticketId}.png.part") }
        // Retain manifest until all exact, manifest-owned PNGs were removed. Unknown files remain.
        names.forEach { name ->
            val file = child(session.sessionId, name)
            check(!file.exists() || (isPrivateRegularFile(file) && file.delete())) { "Evidence deletion incomplete" }
        }
        syncDirectory(directory)
        val allowedMetadata = setOf("evidence.json", "evidence.json.bak", "evidence.json.new")
        check(directory.listFiles().orEmpty().all { it.name in allowedMetadata && isPrivateRegularFile(it) }) {
            "Unknown evidence files retained"
        }
        allowedMetadata.forEach { name -> val file = child(session.sessionId, name); check(!file.exists() || file.delete()) }
        check(directory.delete()) { "Evidence session deletion incomplete" }
        syncDirectory(root)
    }

    private data class Png(val sha256: String, val bytes: Long, val width: Int, val height: Int)
    private data class FrameRecord(
        val ticket: FrameTicket,
        val png: Png? = null,
        val encoderPtsUs: Long? = null,
        val muxSampleOrdinal: Long? = null,
        val containerPtsUs: Long? = null,
        val sourceSha256: String? = null,
        val missingReason: FrameMissingReason? = null,
    )
    private data class MissingBoundary(val action: FrameAnchorAction, val boundary: FrameBoundary, val epoch: Long,
        val reason: FrameMissingReason)
    private data class Session(
        val projectId: String,
        val sessionId: String,
        val sourceId: String,
        val sealed: Boolean = false,
        val registered: Boolean = false,
        val sourceSha256: String? = null,
        val frames: List<FrameRecord> = emptyList(),
        val misses: List<MissingBoundary> = emptyList(),
    ) {
        fun replacing(frame: FrameRecord) = copy(frames = frames.map { if (it.ticket.ticketId == frame.ticket.ticketId) frame else it })
    }

    private fun requireTicket(session: Session, ticket: FrameTicket): FrameRecord {
        check(session.sourceId == ticket.action.sourceId && session.sessionId == ticket.action.sessionId)
        return session.frames.firstOrNull { it.ticket.ticketId == ticket.ticketId }.also {
            check(it != null && it.ticket == ticket) { "Unknown or changed immutable ticket" }
        }!!
    }

    private fun readPng(session: Session, frame: FrameRecord): ByteArray {
        val png = checkNotNull(frame.png)
        val bytes = readBounded(child(session.sessionId, "${frame.ticket.ticketId}.png"), MAX_PNG_BYTES)
        check(bytes.size.toLong() == png.bytes && sha256(bytes) == png.sha256) { "Private frame PNG changed" }
        check(png.width == frame.ticket.geometry.frameWidth && png.height == frame.ticket.geometry.frameHeight)
        return bytes
    }

    private fun save(session: Session) {
        val directory = directory(session.sessionId)
        check(directory.isDirectory)
        val json = JSONObject().put("version", 1).put("projectId", session.projectId)
            .put("sessionId", session.sessionId).put("sourceId", session.sourceId)
            .put("sealed", session.sealed).put("registered", session.registered)
            .put("sourceSha256", session.sourceSha256 ?: JSONObject.NULL)
            .put("frames", JSONArray(session.frames.map { frameJson(it, session.registered) }))
            .put("misses", JSONArray(session.misses.map { missing -> JSONObject()
                .put("action", actionJson(missing.action)).put("boundary", missing.boundary.name)
                .put("epoch", missing.epoch).put("reason", missing.reason.name) }))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_JSON_BYTES)
        val atomic = atomic(session.sessionId)
        val output = atomic.startWrite()
        var finished = false
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
            finished = true
            check(readAtomic(atomic).contentEquals(bytes)) { "Frame evidence commit not confirmed" }
            syncDirectory(directory)
        } catch (failure: Exception) {
            if (!finished) atomic.failWrite(output)
            throw failure
        }
    }

    private fun read(sessionId: String, allowRevoked: Boolean = false): Session {
        check(allowRevoked || !isRevoked(sessionId)) { "Evidence session was deleted" }
        val json = JSONObject(String(readAtomic(atomic(sessionId)), Charsets.UTF_8))
        check(json.getInt("version") == 1)
        val projectId = json.getString("projectId").also(::requireFrameUuid)
        val sourceId = json.getString("sourceId").also(::requireFrameUuid)
        check(json.getString("sessionId") == sessionId)
        val hash = json.nullableString("sourceSha256")?.also(::requireHash)
        val array = json.getJSONArray("frames")
        check(array.length() <= MAX_IMAGES)
        val frames = (0 until array.length()).map { readFrame(array.getJSONObject(it)) }
        val missingArray = json.optJSONArray("misses") ?: JSONArray()
        check(array.length() + missingArray.length() <= MAX_IMAGES)
        val misses = (0 until missingArray.length()).map { index ->
            val value = missingArray.getJSONObject(index)
            MissingBoundary(readAction(value.getJSONObject("action")), FrameBoundary.valueOf(value.getString("boundary")),
                value.getLong("epoch"), FrameMissingReason.valueOf(value.getString("reason"))).also { check(it.epoch >= 0) }
        }
        val allActions = frames.map { it.ticket.action } + misses.map { it.action }
        check(allActions.all { it.sessionId == sessionId && it.sourceId == sourceId })
        check(allActions.map { it.runId to it.generation }.distinct().size <= 1)
        val allBoundaries = frames.map { it.ticket.action to it.ticket.boundary } + misses.map { it.action to it.boundary }
        check(allBoundaries.distinct().size == allBoundaries.size)
        check(frames.map { it.ticket.ticketId }.distinct().size == frames.size)
        check(frames.map { it.ticket.action to it.ticket.boundary }.distinct().size == frames.size)
        check(frames.map { it.ticket.action.runId to it.ticket.action.generation }.distinct().size <= 1)
        check(frames.all { it.ticket.action.sessionId == sessionId && it.ticket.action.sourceId == sourceId })
        check(frames.sumOf { it.png?.bytes ?: 0L } <= MAX_IMAGE_BYTES)
        frames.groupBy { it.ticket.sourceFrameId }.values.forEach { sameSource ->
            val first = sameSource.first().ticket
            check(sameSource.all { it.ticket.captureSequence == first.captureSequence &&
                it.ticket.sourceTimestampNs == first.sourceTimestampNs && it.ticket.submittedPtsUs == first.submittedPtsUs &&
                it.ticket.geometry == first.geometry }) { "Inconsistent captured frame identity" }
        }
        frames.filter { it.muxSampleOrdinal != null }.groupBy { it.muxSampleOrdinal }.values.forEach { sameSample ->
            check(sameSample.map { it.ticket.sourceFrameId }.distinct().size == 1) { "Ambiguous encoded sample" }
        }
        val sealed = json.getBoolean("sealed")
        val registered = json.getBoolean("registered")
        check((!sealed || hash != null) && (!registered || sealed))
        check(frames.all { if (sealed) it.sourceSha256 == hash else it.sourceSha256 == null })
        frames.forEachIndexed { index, frame ->
            check(array.getJSONObject(index).getString("status") == frameJson(frame, registered).getString("status"))
        }
        return Session(projectId, sessionId, sourceId, sealed, registered, hash, frames, misses)
    }

    private fun actionJson(action: FrameAnchorAction): JSONObject = JSONObject().put("runId", action.runId)
        .put("actionId", action.actionId).put("sessionId", action.sessionId).put("sourceId", action.sourceId)
        .put("generation", action.generation)

    private fun readAction(value: JSONObject): FrameAnchorAction = FrameAnchorAction(value.getString("runId"),
        value.getString("actionId"), value.getString("sessionId"), value.getString("sourceId"), value.getLong("generation"))

    private fun frameJson(frame: FrameRecord, registered: Boolean): JSONObject {
        val ticket = frame.ticket
        val action = ticket.action
        val geometry = ticket.geometry
        return JSONObject().put("ticketId", ticket.ticketId)
            .put("action", actionJson(action))
            .put("boundary", ticket.boundary.name).put("epoch", ticket.epoch)
            .put("sourceFrameId", ticket.sourceFrameId).put("captureSequence", ticket.captureSequence)
            .put("sourceTimestampNs", ticket.sourceTimestampNs).put("submittedPtsUs", ticket.submittedPtsUs)
            .put("freshness", ticket.freshness.name)
            .put("geometry", JSONObject().put("geometryId", geometry.geometryId)
                .put("displayWidth", geometry.displayWidth).put("displayHeight", geometry.displayHeight)
                .put("frameWidth", geometry.frameWidth).put("frameHeight", geometry.frameHeight)
                .put("scale", geometry.scale).put("offsetX", geometry.offsetX).put("offsetY", geometry.offsetY))
            .put("png", frame.png?.let { JSONObject().put("sha256", it.sha256).put("bytes", it.bytes)
                .put("width", it.width).put("height", it.height) } ?: JSONObject.NULL)
            .put("encoderPtsUs", frame.encoderPtsUs ?: JSONObject.NULL)
            .put("muxSampleOrdinal", frame.muxSampleOrdinal ?: JSONObject.NULL)
            .put("containerPtsUs", frame.containerPtsUs ?: JSONObject.NULL)
            .put("sourceSha256", frame.sourceSha256 ?: JSONObject.NULL)
            .put("status", if (frame.missingReason != null) "Missing" else if (registered && frame.png != null &&
                frame.encoderPtsUs != null && frame.containerPtsUs != null && frame.sourceSha256 != null) "Ready" else "Pending")
            .put("missingReason", frame.missingReason?.name ?: JSONObject.NULL)
    }

    private fun readFrame(json: JSONObject): FrameRecord {
        val action = json.getJSONObject("action")
        val geometry = json.getJSONObject("geometry")
        val ticket = FrameTicket(json.getString("ticketId"),
            readAction(action),
            FrameBoundary.valueOf(json.getString("boundary")), json.getLong("epoch"), json.getLong("sourceFrameId"),
            json.getLong("captureSequence"), json.getLong("sourceTimestampNs"), json.getLong("submittedPtsUs"),
            FrameGeometry(geometry.getString("geometryId"), geometry.getInt("displayWidth"), geometry.getInt("displayHeight"),
                geometry.getInt("frameWidth"), geometry.getInt("frameHeight"), geometry.getDouble("scale"),
                geometry.getDouble("offsetX"), geometry.getDouble("offsetY")), FrameFreshness.valueOf(json.getString("freshness")))
        val png = json.optJSONObject("png")?.let {
            Png(it.getString("sha256").also(::requireHash), it.getLong("bytes"), it.getInt("width"), it.getInt("height")).also { value ->
                check(value.bytes in 1..MAX_PNG_BYTES.toLong() && value.width == ticket.geometry.frameWidth && value.height == ticket.geometry.frameHeight)
            }
        }
        val encoder = json.nullableLong("encoderPtsUs")
        val ordinal = json.nullableLong("muxSampleOrdinal")
        val container = json.nullableLong("containerPtsUs")
        check((encoder == null) == (ordinal == null))
        check(encoder == null || (encoder == ticket.submittedPtsUs && ordinal!! in 0 until MAX_SAMPLES.toLong()))
        check(container == null || (container >= 0 && encoder != null))
        val hash = json.nullableString("sourceSha256")?.also(::requireHash)
        val missing = json.nullableString("missingReason")?.let(FrameMissingReason::valueOf)
        check(json.getString("status") in setOf("Pending", "Ready", "Missing"))
        return FrameRecord(ticket, png, encoder, ordinal, container, hash, missing)
    }

    private fun isRevoked(sessionId: String): Boolean {
        requireFrameUuid(sessionId)
        requireDirectory(revoked, root)
        // Even an incomplete/invalid revocation fails closed; it never permits recreation.
        return listOf("$sessionId.json", "$sessionId.json.new", "$sessionId.json.bak").any {
            Files.exists(File(revoked, it).toPath(), LinkOption.NOFOLLOW_LINKS)
        }
    }

    private fun hasCommittedRevocation(session: Session): Boolean = runCatching {
        val file = File(revoked, "${session.sessionId}.json")
        listOf(file, File(revoked, "${file.name}.new"), File(revoked, "${file.name}.bak")).forEach {
            check(it.canonicalFile == it && !Files.isSymbolicLink(it.toPath()) && (!it.exists() || isPrivateRegularFile(it)))
        }
        val json = JSONObject(String(readAtomic(AtomicFile(file)), Charsets.UTF_8))
        json.getInt("version") == 1 && json.getString("sessionId") == session.sessionId &&
            json.getString("projectId") == session.projectId && json.getString("sourceId") == session.sourceId
    }.getOrDefault(false)

    private fun revoke(session: Session) {
        requireDirectory(revoked, root)
        val file = File(revoked, "${session.sessionId}.json")
        listOf(file, File(revoked, "${file.name}.new"), File(revoked, "${file.name}.bak")).forEach {
            check(it.canonicalFile == it && !Files.isSymbolicLink(it.toPath()) && (!it.exists() || isPrivateRegularFile(it)))
        }
        val bytes = JSONObject().put("version", 1).put("sessionId", session.sessionId)
            .put("projectId", session.projectId).put("sourceId", session.sourceId).toString().toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file)
        if (file.exists()) {
            check(readAtomic(atomic).contentEquals(bytes)) { "Evidence revocation ownership differs" }
            syncDirectory(revoked)
            return
        }
        val output = atomic.startWrite()
        var finished = false
        try {
            output.write(bytes); output.fd.sync(); atomic.finishWrite(output); finished = true
            check(readAtomic(atomic).contentEquals(bytes)) { "Evidence revocation not confirmed" }
            syncDirectory(revoked)
        } catch (failure: Exception) {
            if (!finished) atomic.failWrite(output)
            throw failure
        }
    }

    private fun atomic(sessionId: String): AtomicFile {
        listOf("evidence.json", "evidence.json.bak", "evidence.json.new").forEach { child(sessionId, it) }
        return AtomicFile(child(sessionId, "evidence.json"))
    }

    private fun readAtomic(atomic: AtomicFile): ByteArray = atomic.openRead().use { input ->
        val bytes = input.readBytesBounded(MAX_JSON_BYTES)
        check(bytes.isNotEmpty())
        bytes
    }

    private fun directory(sessionId: String): File {
        requireFrameUuid(sessionId)
        requireDirectory(root, base)
        return File(root, sessionId).also { requireDirectory(it, root) }
    }

    private fun child(sessionId: String, name: String): File {
        val parent = directory(sessionId)
        return File(parent, name).also {
            check(it.canonicalFile == File(parent, name) && !Files.isSymbolicLink(it.toPath()) &&
                (!it.exists() || isPrivateRegularFile(it))) { "Invalid private frame evidence file" }
        }
    }

    private fun directories(): List<File> = root.listFiles().orEmpty().filter {
        runCatching { requireFrameUuid(it.name); requireDirectory(it, root); it.isDirectory }.getOrDefault(false)
    }

    private fun requireDirectory(file: File, parent: File, create: Boolean = false) {
        check(file.canonicalFile == File(parent, file.name) && !Files.isSymbolicLink(file.toPath())) { "Invalid evidence directory" }
        if (create && !file.exists()) check(file.mkdir()) { "Cannot create private evidence directory" }
        check(!file.exists() || file.isDirectory) { "Invalid evidence directory type" }
    }

    private fun isPrivateRegularFile(file: File): Boolean =
        file.canonicalFile == file && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun readBounded(file: File, limit: Int): ByteArray {
        check(isPrivateRegularFile(file) && file.length() in 1..limit.toLong()) { "Private frame bytes missing or too large" }
        return file.inputStream().use { it.readBytesBounded(limit) }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = LimitedBytes(limit)
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            check(count > 0) { "Incomplete evidence read" }
            output.write(buffer, 0, count)
        }
    }

    private class LimitedBytes(private val limit: Int) : ByteArrayOutputStream() {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length >= 0 && count.toLong() + length <= limit) { "Frame evidence byte limit exceeded" }
            super.write(bytes, offset, length)
        }
        override fun write(value: Int) { check(count < limit); super.write(value) }
    }

    private fun checkPngFreeSpace(sessionId: String, incomingBytes: Long) {
        val recordings = File(base, "recordings")
        val session = File(recordings, sessionId)
        requireDirectory(recordings, base)
        requireDirectory(session, recordings)
        val videoBytes = listOf("capture.part.mp4", "sealed.mp4").sumOf { name ->
            val file = File(session, name)
            check(file.canonicalFile == file && !Files.isSymbolicLink(file.toPath())) { "Invalid recording reserve path" }
            if (!file.exists()) 0L else {
                check(isPrivateRegularFile(file)) { "Invalid recording reserve file" }
                file.length().also { check(it >= 0) }
            }
        }
        check(videoBytes <= Long.MAX_VALUE - incomingBytes - MIN_FREE_BYTES &&
            root.usableSpace >= MIN_FREE_BYTES + videoBytes + incomingBytes) { "Insufficient private frame storage reserve" }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    companion object {
        const val MAX_IMAGES = 80
        const val MAX_IMAGE_BYTES = 100L * 1024 * 1024
        const val MAX_PNG_BYTES = 12 * 1024 * 1024
        private const val MIN_FREE_BYTES = 64L * 1024 * 1024
        private const val MAX_JSON_BYTES = 512 * 1024
        private const val MAX_SAMPLES = 30_000
        private val lock = Any()
        private fun requireHash(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)
        private fun JSONObject.nullableLong(name: String): Long? = if (isNull(name)) null else getLong(name)

        /** Strict finite PNG, CRC, declared dimensions and every decoded alpha are checked. */
        private fun decodePng(bytes: ByteArray, width: Int, height: Int): Bitmap {
            require(bytes.size in 1..MAX_PNG_BYTES && width > 0 && height > 0 && width.toLong() * height <= 12_000_000)
            DataInputStream(bytes.inputStream()).use { input ->
                val signature = ByteArray(8).also(input::readFully)
                check(signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
                var header = false
                var pixels = false
                var ended = false
                var channels = 0
                val compressed = LimitedBytes(MAX_PNG_BYTES)
                while (!ended) {
                    check(input.available() >= 12)
                    val length = input.readInt()
                    check(length >= 0 && length <= input.available() - 8)
                    val typeBytes = ByteArray(4).also(input::readFully)
                    val type = String(typeBytes, Charsets.US_ASCII)
                    check(type in setOf("IHDR", "IDAT", "IEND", "sRGB", "gAMA", "cHRM", "sBIT"))
                    check(header || type == "IHDR")
                    val payload = ByteArray(length).also(input::readFully)
                    val crc = CRC32().apply { update(typeBytes); update(payload) }
                    check((input.readInt().toLong() and 0xffffffffL) == crc.value)
                    when (type) {
                        "IHDR" -> {
                            check(!header && length == 13)
                            DataInputStream(payload.inputStream()).use {
                                check(it.readInt() == width && it.readInt() == height && it.readUnsignedByte() == 8)
                                channels = when (it.readUnsignedByte()) { 2 -> 3; 6 -> 4; else -> error("Unexpected PNG color type") }
                                check(it.readUnsignedByte() == 0 && it.readUnsignedByte() == 0 && it.readUnsignedByte() == 0)
                            }
                            header = true
                        }
                        "IDAT" -> { pixels = true; compressed.write(payload) }
                        "IEND" -> { check(pixels && length == 0); ended = true }
                    }
                }
                check(input.available() == 0)
                val inflater = Inflater()
                try {
                    inflater.setInput(compressed.toByteArray())
                    val rowLength = width.toLong() * channels + 1
                    val expected = rowLength * height
                    val buffer = ByteArray(32 * 1024)
                    var decodedBytes = 0L
                    while (!inflater.finished()) {
                        val count = inflater.inflate(buffer)
                        if (count == 0) { check(inflater.finished()) { "Incomplete PNG pixel stream" }; break }
                        check(decodedBytes + count <= expected) { "Incomplete PNG pixel stream" }
                        for (index in 0 until count) {
                            if ((decodedBytes + index) % rowLength == 0L) check(buffer[index].toInt() in 0..4)
                        }
                        decodedBytes += count
                    }
                    check(decodedBytes == expected && inflater.remaining == 0) { "PNG pixel length differs" }
                } finally { inflater.end() }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(bounds.outMimeType == "image/png" && bounds.outWidth == width && bounds.outHeight == height)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
            }) ?: throw IOException("Frame PNG does not fully decode")
            try {
                check(bitmap.width == width && bitmap.height == height)
                val row = IntArray(width)
                for (y in 0 until height) {
                    bitmap.getPixels(row, 0, width, 0, y, width, 1)
                    check(row.all { Color.alpha(it) == 255 }) { "Frame PNG contains transparent pixels" }
                }
                return bitmap
            } catch (failure: Throwable) { bitmap.recycle(); throw failure }
        }
    }
}
