package com.tapscene.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Imports only a URI explicitly selected by the user. No broad storage permission is needed. */
class SourceImporter(context: Context) {
    private val appContext = context.applicationContext

    /**
     * [register] runs on IO after validation, in a non-cancellable commit section. It must be a
     * synchronous, atomic local metadata write: return only after persistence succeeds, and never
     * throw after committing. Once it returns, the file belongs to the repository, even if the caller
     * is cancelled while switching back to its dispatcher. This closes the cancellation/return gap.
     */
    suspend fun importSource(uri: Uri, register: (ImportedSource) -> Unit): ImportedSource =
        withContext(Dispatchers.IO) {
            if (uri.scheme != "content") throw MediaImportException("请通过系统文件选择器选择录屏。")
            val directory = File(appContext.noBackupFilesDir, MediaLimits.SOURCE_DIRECTORY)
            if ((!directory.isDirectory && !directory.mkdirs()) ||
                directory.canonicalFile.parentFile != appContext.noBackupFilesDir.canonicalFile
            ) throw MediaImportException("无法建立本机私有素材目录。")
            val id = UUID.randomUUID().toString()
            val file = File(directory, "$id.mp4")
            // Create exclusively; cleanup can therefore never delete an existing source.
            if (!file.createNewFile()) throw MediaImportException("无法建立素材副本，请重试。")
            var registered = false
            var failure: Throwable? = null
            try {
                val ownerContext = currentCoroutineContext()
                val copied = runInterruptible { copy(uri, file, ownerContext) }
                ownerContext.ensureActive()
                val metadata = runInterruptible { inspect(file, copied.first, copied.second) }
                VideoFrameDecoder(appContext).validate(file, metadata)
                ownerContext.ensureActive()
                val source = ImportedSource(
                    sourceId = id,
                    privateRelativePath = "${MediaLimits.SOURCE_DIRECTORY}/$id.mp4",
                    displayName = displayName(uri),
                    metadata = metadata,
                )
                ownerContext.ensureActive()
                withContext(NonCancellable) {
                    register(source)
                    registered = true
                }
                source
            } catch (cancelled: CancellationException) {
                failure = cancelled
                throw cancelled
            } catch (known: MediaImportException) {
                failure = known
                throw known
            } catch (decode: FrameDecodeException) {
                val error = MediaImportException(decode.message ?: "录屏检查失败，请重新选择。", decode)
                failure = error
                throw error
            } catch (cause: Exception) {
                val error = MediaImportException("无法导入录屏，请检查文件访问权限和本机空间后重试。", cause)
                failure = error
                throw error
            } catch (fatal: Throwable) {
                failure = fatal
                throw fatal
            } finally {
                // No directory sweeps: only this import's unregistered copy is eligible.
                if (!registered) {
                    val cleanupFailure = try {
                        if (!file.delete() && file.exists()) MediaImportException("临时副本清理失败，请检查本机存储。") else null
                    } catch (cause: Exception) {
                        MediaImportException("临时副本清理失败，请检查本机存储。", cause)
                    }
                    if (cleanupFailure != null) {
                        if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure
                    }
                }
            }
        }

    private fun copy(uri: Uri, file: File, ownerContext: CoroutineContext): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ownerContext.ensureActive()
                    // Read at most one byte over the limit, even if the provider reports no length.
                    val allowed = minOf(buffer.size.toLong(), MediaLimits.MAX_BYTES - length + 1).toInt()
                    val count = input.read(buffer, 0, allowed)
                    if (count < 0) break
                    if (count == 0) throw MediaImportException("无法继续读取录屏，请重新选择文件。")
                    length += count
                    if (length > MediaLimits.MAX_BYTES) throw MediaImportException("单段录屏不能超过 200 MiB。")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                ownerContext.ensureActive()
                output.fd.sync()
            }
        } ?: throw MediaImportException("无法读取所选录屏，请重新选择文件。")
        if (length == 0L || file.length() != length) throw MediaImportException("录屏文件为空或复制不完整。")
        return length to digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun inspect(file: File, byteLength: Long, sha256: String): SourceMetadata =
        MediaInputPolicy.inspect(file, byteLength, sha256).metadata

    private fun displayName(uri: Uri): String {
        return try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.filterNot { it.isISOControl() }?.take(160) else null
            }?.takeIf { it.isNotBlank() } ?: "录屏.mp4"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "录屏.mp4"
        }
    }


}

internal fun videoTrack(extractor: MediaExtractor): Pair<Int, MediaFormat> {
    var track: Pair<Int, MediaFormat>? = null
    for (index in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(index)
        if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
            if (track != null) throw MediaImportException("暂不支持包含多个视频轨的录屏。")
            track = index to format
        }
    }
    return track ?: throw MediaImportException("文件没有可读取的视频轨。")
}

internal fun MediaFormat.intOrZero(key: String): Int = if (containsKey(key)) getInteger(key) else 0

// Literal crop keys also work on API 26; the public KEY_CROP_* constants were added in API 33.
internal fun visibleSize(format: MediaFormat, horizontal: Boolean): Int {
    val first = if (horizontal) "crop-left" else "crop-top"
    val last = if (horizontal) "crop-right" else "crop-bottom"
    val size = format.getInteger(if (horizontal) MediaFormat.KEY_WIDTH else MediaFormat.KEY_HEIGHT)
    if (format.containsKey(first) != format.containsKey(last)) throw MediaImportException("录屏裁切信息不完整。")
    if (!format.containsKey(first)) return size
    val start = format.getInteger(first)
    val end = format.getInteger(last)
    if (start < 0 || end < start || end >= size) throw MediaImportException("录屏裁切范围无效。")
    return end - start + 1
}
