package com.tapscene.sharing

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

/** Separate from mutable SAF exports. Installed directories are never rewritten or renewed. */
data class OfflineShare(val token: String, val releaseId: String, val uri: Uri, val expiresAt: Long)

class OfflineShareStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.cacheDir.canonicalFile, DIRECTORY)

    /** Caller holds the ReleaseStore lock and supplies its freshly verified local sealed package. */
    internal fun create(source: File, releaseId: String, contentDigest: String,
        cancel: ViewerPackageCodec.CancelCheck): OfflineShare = synchronized(lock) {
        require(UUID.fromString(releaseId).toString() == releaseId)
        require(HASH.matches(contentDigest))
        ensureRoot()
        cleanLocked(System.currentTimeMillis())
        val length = source.length()
        check(source.canonicalFile == source.absoluteFile && source.isFile && length in 1..MAX_PACKAGE_BYTES) { "分享包无效，请重新准备。" }
        val digest = ViewerPackageCodec.sha256(source)
        val entries = root.listFiles().orEmpty()
        check(entries.size < MAX_ENTRIES && entries.sumOf(::entryBytes) + length + MAX_METADATA_BYTES <= MAX_CACHE_BYTES) {
            "分享缓存已满（最多 8 份、200 MiB），请稍后重试或使用保存到文件；临时副本会在 24 小时后清理。"
        }
        val token = UUID.randomUUID().toString()
        val pending = File(root, ".pending-$token")
        check(pending.mkdir()) { "无法准备分享副本，请检查可用空间。" }
        try {
            val output = File(pending, FILE_NAME)
            FileOutputStream(output).use { sink -> source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    cancel.check()
                    val size = input.read(buffer)
                    if (size < 0) break
                    check(size > 0)
                    count += size
                    check(count <= length) { "分享包已变化，请重新准备。" }
                    sink.write(buffer, 0, size)
                }
                check(count == length) { "分享包不完整，请重新准备。" }
                sink.fd.sync()
            } }
            check(output.length() == length && ViewerPackageCodec.sha256(output) == digest) { "分享副本校验失败，请重新准备。" }
            val createdAt = System.currentTimeMillis()
            val metadata = JSONObject().put("releaseId", releaseId).put("contentDigest", contentDigest)
                .put("byteLength", length).put("sha256", digest).put("createdAt", createdAt)
                .put("expiresAt", createdAt + LIFETIME_MS).toString().toByteArray(Charsets.UTF_8)
            FileOutputStream(File(pending, METADATA)).use { it.write(metadata); it.fd.sync() }
            cancel.check()
            val installed = File(root, token)
            check(!installed.exists())
            Files.move(pending.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            // A killed process or absent cache fails closed; no durable release is affected.
            OfflineShare(token, releaseId, FileProvider.getUriForFile(app, authority(app), File(installed, FILE_NAME)), createdAt + LIFETIME_MS)
        } finally { if (pending.exists()) deleteEntry(pending) }
    }

    /** Validate and open under the same lock as expiry cleanup. Existing FDs survive unlink. */
    internal fun <T> read(uri: Uri, verifyBytes: Boolean = false, action: (File) -> T): T = synchronized(lock) {
        try {
            val token = token(uri)
            ensureRoot()
            val directory = File(root, token)
            val metadata = metadata(directory)
            val now = System.currentTimeMillis()
            check(now >= metadata.getLong("createdAt") && now < metadata.getLong("expiresAt"))
            val file = File(directory, FILE_NAME)
            check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() == metadata.getLong("byteLength"))
            if (verifyBytes) check(ViewerPackageCodec.sha256(file) == metadata.getString("sha256"))
            action(file)
        } catch (error: Exception) {
            // Do not disclose private paths, metadata or whether a guessed token exists.
            throw FileNotFoundException("分享副本不可用或已过期，请回到 TapScene 重新分享。")
        }
    }

    private fun token(uri: Uri): String {
        check(uri.scheme == "content" && uri.encodedAuthority == authority(app) && uri.query == null && uri.fragment == null)
        val segments = uri.pathSegments
        check(segments.size == 3 && segments[0] == PATH_NAME && segments[2] == FILE_NAME)
        val token = segments[1]
        check(UUID.fromString(token).toString() == token)
        check(uri.encodedPath == "/$PATH_NAME/$token/$FILE_NAME")
        return token
    }

    private fun ensureRoot() {
        check(root.canonicalFile == root.absoluteFile)
        check(root.isDirectory || root.mkdir())
    }

    private fun metadata(directory: File): JSONObject {
        check(directory.canonicalFile == directory.absoluteFile && directory.isDirectory)
        val file = File(directory, METADATA)
        check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() in 1..MAX_METADATA_BYTES)
        return JSONObject(file.readText(Charsets.UTF_8)).also {
            val created = it.getLong("createdAt")
            check(created > 0 && it.getLong("expiresAt") - created == LIFETIME_MS)
            check(it.getLong("byteLength") in 1..MAX_PACKAGE_BYTES && HASH.matches(it.getString("sha256")))
        }
    }

    private fun cleanLocked(now: Long) {
        root.listFiles().orEmpty().forEach { directory ->
            check(directory.canonicalFile == directory.absoluteFile && directory.isDirectory)
            val token = directory.name.removePrefix(".pending-")
            check(UUID.fromString(token).toString() == token)
            // No creator can be active under this process-wide lock. Incomplete entries are private.
            val info = runCatching { metadata(directory) }.getOrNull()
            if (directory.name.startsWith(".pending-") || (info != null &&
                now >= info.getLong("expiresAt"))) deleteEntry(directory)
        }
    }

    private fun entryBytes(directory: File): Long = directory.listFiles().orEmpty().sumOf { file ->
        check(file.canonicalFile == file.absoluteFile && file.isFile && file.name in setOf(FILE_NAME, METADATA))
        file.length()
    }

    private fun deleteEntry(directory: File) {
        check(directory.parentFile == root && directory.canonicalFile == directory.absoluteFile)
        directory.listFiles().orEmpty().forEach { file ->
            check(file.canonicalFile == file.absoluteFile && file.isFile && file.name in setOf(FILE_NAME, METADATA))
            check(file.delete() || !file.exists())
        }
        check(directory.delete() || !directory.exists())
    }

    companion object {
        internal const val DIRECTORY = "offline-shares"
        internal const val PATH_NAME = "offline_share"
        internal const val FILE_NAME = "TapScene-offline.tapscene"
        internal const val METADATA = "metadata.json"
        internal const val LIFETIME_MS = 24L * 60 * 60 * 1000
        private const val MAX_ENTRIES = 8
        private const val MAX_PACKAGE_BYTES = 50L * 1024 * 1024
        private const val MAX_CACHE_BYTES = 200L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 2048L
        private val HASH = Regex("[0-9a-f]{64}")
        private val lock = Any()
        fun authority(context: Context) = "${context.packageName}.offline-share"
    }
}
