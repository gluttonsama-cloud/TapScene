package com.tapscene.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.system.Os
import android.system.OsConstants
import com.tapscene.media.FrameDecodeException
import com.tapscene.media.ImportedSource
import com.tapscene.media.MediaImportException
import com.tapscene.media.MediaInputPolicy
import com.tapscene.media.MediaLimits
import com.tapscene.media.VideoFrameDecoder
import com.tapscene.recording.FrameSourceAccessor
import com.tapscene.recording.FrameRegisteredSource
import com.tapscene.recording.FrameRecordingBackend
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Advisory project totals. Registration rechecks all limits against current persisted sources. */
data class SourceBudget(val sourceCount: Int, val remainingBytes: Long, val remainingDurationUs: Long)

/** Private raw-source registration only. Nothing here creates a reviewed or shareable asset. */
class SourceRepository(context: Context) {
    private val app = context.applicationContext
    private val root = app.noBackupFilesDir.canonicalFile
    private val projects = ProjectStore(app)

    /** Synchronous local read; call from IO. Null is the existing media-only workspace. */
    fun budget(projectId: String?): SourceBudget {
        val store = WorkspaceStore(app, projectId)
        if (projectId != null) requireProject(projectId)
        val drafts = store.read()
        check(drafts.size <= MAX_SOURCE_COUNT && drafts.all {
            it.source.metadata.byteLength in 1..MediaLimits.MAX_BYTES &&
                it.source.metadata.durationUs in 1..MediaLimits.MAX_DURATION_US
        }) { "素材记录限额无效" }
        return SourceBudget(drafts.size,
            (MAX_TOTAL_BYTES - drafts.sumOf { it.source.metadata.byteLength }).coerceAtLeast(0),
            (MAX_TOTAL_DURATION_US - drafts.sumOf { it.source.metadata.durationUs }).coerceAtLeast(0))
    }

    /**
     * The recording journal must stop/release the recorder, fsync and atomically rename its raw
     * file to recordings/<UUID session>/sealed.mp4 BEFORE calling this method. [sourceId] is a
     * stable UUID saved in that journal. This API never accepts a URI, and never owns/deletes
     * [sealedFile]; after successful registration the journal may remove its original.
     *
     * A separately validated private copy is atomically registered with a fresh workspace read.
     * Cancellation at the return boundary can hide a successful commit: retry with the SAME IDs.
     * An existing registration returns without needing the journal's original file to still exist.
     * Only this exact operation's staging/orphan copy is recovered; there are no directory sweeps.
     */
    suspend fun registerRecording(
        projectId: String,
        sourceId: String,
        sealedFile: File,
        displayName: String,
    ): ImportedSource = withContext(Dispatchers.IO) {
        registrationLock.withLock {
            require(projectId.matches(UUID_PATTERN) && sourceId.matches(UUID_PATTERN)) { "录制标识无效" }
            val input = sealedRecordingPath(sealedFile)
            val store = WorkspaceStore(app, projectId)
            val sessionId = input.parentFile!!.name
            val basename = "recording-$projectId-$sourceId-$sessionId"
            // Preserve the exact existing decoder/importer path contract.
            val relativePath = "${MediaLimits.SOURCE_DIRECTORY}/$sourceId.mp4"
            val destination = File(root, relativePath)
            val guard = File(destination.parentFile, ".recording-$sourceId.json")
            val savedOwnership = readOwnership(guard)
            val owner = currentCoroutineContext()
            val existing = store.read().firstOrNull { it.source.sourceId == sourceId }?.source
            if (existing != null) {
                check(existing.privateRelativePath == relativePath && savedOwnership == RecordingOwnership(
                    projectId, sourceId, sessionId, existing.metadata.byteLength, existing.metadata.sha256)) {
                    "素材标识已用于其他内容"
                }
                requirePrivateSource(destination)
                val actual = runInterruptible { fingerprint(destination, owner) }
                check(actual.first == existing.metadata.byteLength && actual.second == existing.metadata.sha256) {
                    "已登记录屏缺失或改变，请保留录制记录后重试"
                }
                // A previous call may have renamed metadata but failed its directory fsync.
                // Do not let the journal discard its original until that durability is confirmed.
                return@withLock try {
                    store.confirmRegistration(existing)
                } catch (uncertain: WorkspaceDurabilityException) {
                    throw MediaImportException("录制素材已写入，但持久化确认未完成，请保留原片并重试登记。", uncertain)
                }
            }
            requireProject(projectId)
            require(Files.isRegularFile(input.toPath(), LinkOption.NOFOLLOW_LINKS)) { "录制尚未封口或文件缺失" }
            val directory = File(root, MediaLimits.SOURCE_DIRECTORY)
            check((directory.isDirectory || directory.mkdirs()) && directory.canonicalFile == directory) {
                "无法建立本机私有素材目录"
            }
            val temporary = File(directory, ".$basename.part.mp4")
            requirePrivateSource(temporary, mustExist = false)
            requirePrivateSource(destination, mustExist = false)
            // An unknown existing file may belong to a manual import or a different project.
            // A UUID-shaped filename alone never authorizes adopting, replacing or deleting it.
            check(!destination.exists() || savedOwnership != null) { "录制素材文件已存在且归属未知" }
            var registered = false
            var ownsDestination = false
            try {
                val copied = runInterruptible { copySealed(input, temporary, owner) }
                val ownership = RecordingOwnership(projectId, sourceId, sessionId, copied.first, copied.second)
                check(savedOwnership == null || savedOwnership == ownership) { "录制恢复归属不一致" }
                owner.ensureActive()
                // A crash after file rename but before metadata commit leaves only this exact
                // deterministic copy. Never replace a different file or guess from its name.
                if (destination.exists()) {
                    check(savedOwnership != null) { "录制素材文件已存在且归属未知" }
                    val previous = runInterruptible { fingerprint(destination, owner) }
                    check(previous == copied) { "录制恢复副本不一致，请保留原片后重试" }
                    ownsDestination = true
                }
                val metadata = runInterruptible {
                    requireSilentSingleVideoTrack(temporary)
                    MediaInputPolicy.inspect(temporary, copied.first, copied.second).metadata
                }
                VideoFrameDecoder(app).validate(temporary, metadata)
                owner.ensureActive()
                val source = ImportedSource(sourceId, relativePath,
                    displayName.filterNot { it.isISOControl() }.take(160).ifBlank { "本机录制.mp4" }, metadata)
                withContext(NonCancellable) {
                    try {
                        store.update { current ->
                            requireProject(projectId)
                            check(current.none { it.source.sourceId == sourceId }) { "素材标识已用于其他内容" }
                            if (current.size >= MAX_SOURCE_COUNT) throw MediaImportException("最多保留 3 段录屏。")
                            if (current.sumOf { it.source.metadata.byteLength } + metadata.byteLength > MAX_TOTAL_BYTES) {
                                throw MediaImportException("全部录屏不能超过 500 MiB。")
                            }
                            if (current.sumOf { it.source.metadata.durationUs } + metadata.durationUs > MAX_TOTAL_DURATION_US) {
                                throw MediaImportException("全部录屏不能超过 5 分钟。")
                            }
                            // Persist exact ownership BEFORE the final file can exist. The immutable
                            // guard distinguishes our crash residue from every other source UUID.
                            if (savedOwnership == null) writeOwnership(guard, ownership)
                            if (!ownsDestination) {
                                // No replacement: another source can never be overwritten.
                                check(!destination.exists()) { "录制素材文件已存在" }
                                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                                ownsDestination = true
                            }
                            syncDirectory(directory)
                            current + SourceDraft(source)
                        }
                    } catch (uncertain: WorkspaceDurabilityException) {
                        // The JSON already names this file. Preserve it even if cancellation
                        // replaces the exception while returning across dispatcher boundaries.
                        registered = true
                        throw uncertain
                    }
                    // No logging, journal writes or other fallible work after this commit point.
                    registered = true
                }
                source
            } catch (uncertain: WorkspaceDurabilityException) {
                throw MediaImportException("录制素材已写入，但持久化确认未完成，请保留原片并重试登记。", uncertain)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (known: MediaImportException) {
                throw known
            } catch (decode: FrameDecodeException) {
                throw MediaImportException(decode.message ?: "录制检查失败，请保留原片后重试。", decode)
            } catch (cause: Exception) {
                throw MediaImportException("录制登记未完成，请保留原片并检查项目限额与本机空间后重试。", cause)
            } finally {
                // Best effort only: cleanup must never turn a committed registration into failure.
                runCatching { temporary.delete() }
                if (!registered && ownsDestination) runCatching { destination.delete() }
            }
        }
    }

    /** Private bridge for frame evidence. Each read checks current ownership and actual MP4 bytes. */
    internal fun frameEvidenceSourceAccessor(): FrameSourceAccessor = FrameSourceAccessor { projectId, sessionId, sourceId ->
        try {
            require(projectId.matches(UUID_PATTERN) && sourceId.matches(UUID_PATTERN) && sessionId.matches(UUID_PATTERN))
            requireProject(projectId)
            val source = WorkspaceStore(app, projectId).read().singleOrNull { it.source.sourceId == sourceId }?.source
            if (source == null) null else {
                val path = "${MediaLimits.SOURCE_DIRECTORY}/$sourceId.mp4"
                check(source.privateRelativePath == path)
                val file = File(root, path)
                requirePrivateSource(file)
                val ownership = readOwnership(File(file.parentFile, ".recording-$sourceId.json"))
                check(ownership == RecordingOwnership(projectId, sourceId, sessionId,
                    source.metadata.byteLength, source.metadata.sha256))
                check(file.length() == source.metadata.byteLength && FrameRecordingBackend.sha256(file) == source.metadata.sha256)
                val samples = FrameRecordingBackend.readContainerSamples(file, source.metadata.width, source.metadata.height)
                FrameRegisteredSource(projectId, sessionId, sourceId, source.metadata.sha256,
                    source.metadata.width, source.metadata.height, samples)
            }
        } catch (_: Exception) { null }
    }

    private fun requireProject(projectId: String) {
        if (projects.readProject(projectId) == null) throw MediaImportException("项目已不存在，请先选择项目。")
    }

    private fun sealedRecordingPath(file: File): File {
        val recordings = File(root, "recordings")
        val session = file.absoluteFile.parentFile ?: error("录制路径无效")
        require(recordings.canonicalFile == recordings && session.name.matches(UUID_PATTERN) &&
            session.canonicalFile == File(recordings, session.name) && file.name == "sealed.mp4" &&
            file.canonicalFile == File(session.canonicalFile, "sealed.mp4") &&
            !Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(session.toPath())) {
            "只允许登记本机已封口的录制文件"
        }
        return file.canonicalFile
    }

    private fun requirePrivateSource(file: File, mustExist: Boolean = true) {
        require(file.canonicalFile == file && file.parentFile == File(root, MediaLimits.SOURCE_DIRECTORY) &&
            !Files.isSymbolicLink(file.toPath()) &&
            (!file.exists() || Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) &&
            (!mustExist || file.isFile)) { "录制素材副本路径无效或文件缺失" }
    }

    private data class RecordingOwnership(
        val projectId: String,
        val sourceId: String,
        val sessionId: String,
        val byteLength: Long,
        val sha256: String,
    )

    private fun readOwnership(file: File): RecordingOwnership? {
        requirePrivateSource(file, mustExist = false)
        if (!file.exists()) return null
        require(file.length() in 1..2_048L) { "录制归属记录无效" }
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(2_049)
            var length = 0
            while (length < buffer.size) {
                val count = input.read(buffer, length, buffer.size - length)
                if (count < 0) break
                check(count > 0) { "录制归属记录不完整" }
                length += count
            }
            require(length <= 2_048) { "录制归属记录超限" }
            buffer.copyOf(length)
        }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getInt("version") == 1) { "录制归属记录版本无效" }
        return RecordingOwnership(json.getString("projectId"), json.getString("sourceId"),
            json.getString("sessionId"), json.getLong("bytes"), json.getString("sha256"))
    }

    private fun writeOwnership(file: File, ownership: RecordingOwnership) {
        requirePrivateSource(file, mustExist = false)
        check(!file.exists()) { "录制归属记录已存在" }
        val temporary = File(file.parentFile, "${file.name}.part")
        requirePrivateSource(temporary, mustExist = false)
        val bytes = JSONObject().put("version", 1).put("projectId", ownership.projectId)
            .put("sourceId", ownership.sourceId).put("sessionId", ownership.sessionId)
            .put("bytes", ownership.byteLength).put("sha256", ownership.sha256)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(file.parentFile!!)
        } finally {
            runCatching { temporary.delete() }
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun copySealed(input: File, output: File, owner: CoroutineContext): Pair<Long, String> {
        val expectedLength = input.length()
        if (expectedLength !in 1..MediaLimits.MAX_BYTES) throw MediaImportException("单段录屏不能为空或超过 200 MiB。")
        val copied = input.inputStream().use { stream ->
            FileOutputStream(output).use { target ->
                val result = readBytes(stream, owner) { bytes, count -> target.write(bytes, 0, count) }
                owner.ensureActive()
                target.fd.sync()
                result
            }
        }
        check(copied.first == expectedLength && input.length() == expectedLength && output.length() == expectedLength) {
            "录制文件仍在变化或复制不完整"
        }
        return copied
    }

    private fun fingerprint(file: File, owner: CoroutineContext): Pair<Long, String> =
        file.inputStream().use { readBytes(it, owner) { _, _ -> } }

    private fun readBytes(
        input: java.io.InputStream,
        owner: CoroutineContext,
        consume: (ByteArray, Int) -> Unit,
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var length = 0L
        while (true) {
            owner.ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), MediaLimits.MAX_BYTES - length + 1).toInt())
            if (count < 0) break
            check(count > 0) { "录制文件读取中断" }
            length += count
            if (length > MediaLimits.MAX_BYTES) throw MediaImportException("单段录屏不能超过 200 MiB。")
            digest.update(buffer, 0, count)
            consume(buffer, count)
        }
        check(length > 0) { "录制文件为空" }
        return length to digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun requireSilentSingleVideoTrack(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount != 1 ||
                extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true) {
                throw MediaImportException("本机录制必须仅包含一个视频轨，不能包含音轨。")
            }
        } finally {
            extractor.release()
        }
    }

    companion object {
        private val registrationLock = Mutex()
        private val UUID_PATTERN = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private const val MAX_SOURCE_COUNT = 3
        private const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
        private const val MAX_TOTAL_DURATION_US = 300_000_000L
    }
}
