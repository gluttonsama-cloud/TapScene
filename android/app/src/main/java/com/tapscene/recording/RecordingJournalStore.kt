package com.tapscene.recording

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

/** Only this session's exclusive, non-backed-up workspace may be removed. */
internal class RecordingJournalStore(context: Context) {
    private val base = context.noBackupFilesDir.canonicalFile
    private val root = File(base, "recordings")

    init {
        if ((!root.isDirectory && !root.mkdirs()) || root.canonicalFile != File(base, "recordings")) {
            throw IOException("Private recording storage unavailable")
        }
        // Also persist the root directory entry before the very first recording. Repeating this
        // confirms a previous attempt whose parent-directory fsync may have failed.
        syncDirectory(base)
    }

    fun create(journal: RecordingJournal) {
        val directory = directory(journal.sessionId)
        if (!directory.mkdir()) throw IOException("Session already exists or cannot be created")
        syncDirectory(root)
        save(journal)
        if (!part(journal.sessionId).createNewFile()) throw IOException("Cannot create recording")
    }

    fun save(journal: RecordingJournal) {
        requireUuid(journal.sourceId)
        val directory = directory(journal.sessionId)
        check(directory.isDirectory)
        val json = JSONObject()
            .put("version", 1)
            .put("sessionId", journal.sessionId)
            .put("projectId", journal.projectId)
            .put("sourceId", journal.sourceId)
            .put("phase", journal.phase.name)
            .put("createdAtMs", journal.createdAtMs)
            .put("elapsedMs", journal.elapsedMs)
            .put("stopReason", journal.stopReason?.name ?: JSONObject.NULL)
            .put("width", journal.width).put("height", journal.height)
            .put("fps", journal.fps).put("bitrate", journal.bitrate)
            .put("mapping", RecordingMapping.Unknown.name)
        journal.latestContentLayout?.let { layout ->
            json.put("latestContentLayout", JSONObject()
                .put("capturedWidth", layout.capturedWidth).put("capturedHeight", layout.capturedHeight)
                .put("left", layout.left).put("top", layout.top).put("right", layout.right).put("bottom", layout.bottom)
                .put("basis", "platform_fit_center_latest_observation")
                .put("videoPtsUs", JSONObject.NULL))
        }
        val atomic = AtomicFile(File(directory, "session.json"))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val output = atomic.startWrite()
        var finalized = false
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
            finalized = true
            // AtomicFile versions may only log a failed rename/backup removal. Re-open through
            // AtomicFile's recovery path and require exactly the state we intended to commit.
            if (!readBytes(atomic).contentEquals(bytes)) throw IOException("Journal commit not confirmed")
            syncDirectory(directory)
        } catch (failure: Exception) {
            // Older AtomicFile implementations remove the base in failWrite. Once finalized,
            // a directory fsync failure is uncertain durability, never permission to roll back.
            if (!finalized) atomic.failWrite(output)
            throw failure
        }
    }

    /** Invalid/incomplete journals are never interpreted as permission to register a video. */
    fun readAll(): List<RecordingJournal> = sessionDirectories().mapNotNull { directory ->
        try {
            val bytes = readBytes(AtomicFile(File(directory, "session.json")))
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            check(json.getInt("version") == 1)
            val sessionId = json.getString("sessionId")
            check(sessionId == directory.name)
            requireUuid(sessionId)
            val sourceId = json.getString("sourceId").also(::requireUuid)
            val projectId = json.getString("projectId").also(::requireUuid)
            RecordingJournal(
                sessionId, projectId, sourceId,
                JournalPhase.valueOf(json.getString("phase")),
                json.getLong("createdAtMs"), json.optLong("elapsedMs", 0).coerceAtLeast(0),
                if (json.isNull("stopReason")) null else RecordingStopReason.valueOf(json.getString("stopReason")),
                json.optInt("width"), json.optInt("height"), json.optInt("fps"), json.optInt("bitrate"),
                json.optJSONObject("latestContentLayout")?.let { layout ->
                    RecordingContentLayout.fit(json.getInt("width"), json.getInt("height"),
                        layout.getInt("capturedWidth"), layout.getInt("capturedHeight"))
                },
            )
        } catch (_: Exception) {
            null
        }
    }.sortedBy { it.createdAtMs }

    fun orphanSessionIds(known: Set<String>): List<String> =
        sessionDirectories().map { it.name }.filterNot { it in known }

    fun part(sessionId: String): File = child(sessionId, "capture.part.mp4")
    fun sealed(sessionId: String): File = child(sessionId, "sealed.mp4")

    /** Call only after the recording backend stopped successfully and released all output handles. */
    fun seal(sessionId: String): File {
        val input = part(sessionId)
        if (!input.isFile || input.length() <= 0) throw IOException("Empty recording")
        FileOutputStream(input, true).use { it.fd.sync() }
        val output = sealed(sessionId)
        if (output.exists() || !input.renameTo(output)) throw IOException("Cannot seal recording")
        syncDirectory(directory(sessionId))
        return output
    }

    /** Does not follow symlinks, sweep other sessions, or ever touch repository-owned sources. */
    fun deleteRaw(sessionId: String): Boolean {
        // Evaluate every owned file even if the first deletion fails.
        val results = listOf(part(sessionId), sealed(sessionId)).map { file -> !file.exists() || file.delete() }
        return results.all { it }
    }

    private fun child(sessionId: String, name: String): File {
        val directory = directory(sessionId)
        return File(directory, name).also {
            if (it.canonicalFile != File(directory.canonicalFile, name)) throw IOException("Invalid recording file")
        }
    }

    private fun directory(sessionId: String): File {
        requireUuid(sessionId)
        return File(root, sessionId).also {
            if (it.canonicalFile != File(root.canonicalFile, sessionId)) throw IOException("Invalid recording directory")
        }
    }

    private fun sessionDirectories(): List<File> = root.listFiles().orEmpty().filter { file ->
        runCatching {
            requireUuid(file.name)
            file.isDirectory && file.canonicalFile == File(root.canonicalFile, file.name)
        }.getOrDefault(false)
    }

    private fun readBytes(atomic: AtomicFile): ByteArray = atomic.openRead().use { input ->
        val buffer = ByteArray(32 * 1024 + 1)
        var length = 0
        while (length < buffer.size) {
            val count = input.read(buffer, length, buffer.size - length)
            if (count < 0) break
            if (count == 0) throw IOException("Incomplete journal")
            length += count
        }
        if (length > 32 * 1024) throw IOException("Journal too large")
        buffer.copyOf(length)
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    companion object {
        fun requireUuid(value: String) {
            require(UUID.fromString(value).toString() == value)
        }
    }
}
