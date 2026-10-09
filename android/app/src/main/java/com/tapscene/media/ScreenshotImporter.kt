package com.tapscene.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** User-selected screenshots stay private until separately rendered and reviewed by SafeMediaWriter. */
class ScreenshotImporter(context: Context) {
    private val appContext = context.applicationContext
    private val root = appContext.noBackupFilesDir.canonicalFile

    init { recoverAbandonedStaging() }

    suspend fun importScreenshot(uri: Uri): ImportedScreenshot {
        if (uri.scheme != "content") throw MediaImportException("请通过系统文件选择器选择截图。")
        return importOwned({ appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选截图。") }, { displayName(uri) })
    }

    /** For an explicitly selected local input or synthetic platform tests; it is copied first. */
    suspend fun importScreenshot(file: File, displayName: String = file.name): ImportedScreenshot =
        importOwned({ FileInputStream(file) }, { displayName })

    private suspend fun importOwned(open: () -> InputStream, name: () -> String): ImportedScreenshot {
        var returned: ImportedScreenshot? = null
        try {
            return withContext(Dispatchers.IO) {
                val owner = currentCoroutineContext()
                val id = UUID.randomUUID().toString()
                val directory = reserveStaging(id)
                var ownedBitmap: Bitmap? = null
                var transferred = false
                var failure: Throwable? = null
                try {
                    val partial = File(directory, "original.part")
                    check(partial.createNewFile()) { "无法建立截图私有副本。" }
                    val copied = runInterruptible { copy(open, partial, owner) }
                    owner.ensureActive()
                    val inspected = runInterruptible { inspectFile(partial, owner) }
                    val extension = if (inspected.mime == "image/png") "png" else "jpg"
                    val original = File(directory, "original.$extension")
                    check(!original.exists() && partial.renameTo(original)) { "截图私有副本保存失败。" }
                    owner.ensureActive()
                    val size = fitOutputSize(inspected.width, inspected.height, inspected.orientation)
                    val source = ImportedImageSource(
                        id, "$STAGING/$id/original.$extension", cleanName(name(), extension),
                        ImageSourceMetadata(inspected.mime, copied.first, copied.second, inspected.width,
                            inspected.height, inspected.orientation, size.first, size.second),
                    )
                    ownedBitmap = normalize(inspected, source.metadata, owner)
                    owner.ensureActive()
                    val lease = ImportedScreenshot(source, ownedBitmap) { discard(source) }
                    returned = lease
                    transferred = true
                    lease
                } catch (cancelled: CancellationException) {
                    failure = cancelled; throw cancelled
                } catch (error: Throwable) {
                    failure = error
                    if (error is Error && error !is OutOfMemoryError) throw error
                    throw MediaImportException(error.message ?: "截图导入失败，请检查文件与本机空间。", error)
                } finally {
                    if (!transferred) {
                        ownedBitmap?.recycle()
                        try { releaseStaging(directory) } catch (cleanup: Throwable) {
                            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            // withContext may cancel at its return boundary after a bitmap has been allocated.
            try { returned?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    /** Strictly rechecks bytes, digest, orientation and dimensions before decoding private source. */
    suspend fun read(source: ImportedImageSource): Bitmap {
        var returned: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                val owner = currentCoroutineContext()
                val file = checkedSourceFile(source)
                val bytes = runInterruptible { readBounded(file, owner) }
                val digest = sha256(bytes)
                if (source.metadata.byteLength != bytes.size.toLong() || source.metadata.sha256 != digest) {
                    throw MediaImportException("截图原始副本与登记摘要不符，请重新导入。")
                }
                val inspected = runInterruptible { StrictImageInput.inspect(bytes) { owner.ensureActive() } }
                val size = fitOutputSize(inspected.width, inspected.height, inspected.orientation)
                val expected = source.metadata
                if (inspected.mime != expected.mime || inspected.width != expected.width ||
                    inspected.height != expected.height || inspected.orientation != expected.orientation ||
                    size.first != expected.outputWidth || size.second != expected.outputHeight) {
                    throw MediaImportException("截图原始副本的尺寸或方向与登记信息不符。")
                }
                normalize(inspected, expected, owner).also { returned = it }
            }
        } catch (error: Throwable) {
            returned?.recycle()
            if (error is CancellationException || error is MediaImportException) throw error
            if (error is Error && error !is OutOfMemoryError) throw error
            throw MediaImportException(error.message ?: "无法读取私有截图。", error)
        }
    }

    /** Only the two literal private layouts qualify; no path traversal, links or URI indirection. */
    fun checkedSourceFile(source: ImportedImageSource): File {
        require(ID.matches(source.sourceId)) { "截图来源标识无效。" }
        val extension = when (source.metadata.mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> throw MediaImportException("截图来源格式无效。")
        }
        val allowed = listOf(STAGING, SOURCES).map { "$it/${source.sourceId}/original.$extension" }
        require(source.privateRelativePath in allowed) { "截图来源路径无效。" }
        val file = File(root, source.privateRelativePath)
        require(file.canonicalFile == file.absoluteFile && !Files.isSymbolicLink(file.toPath()) &&
            !Files.isSymbolicLink(file.parentFile.toPath()) &&
            !Files.isSymbolicLink(file.parentFile.parentFile.toPath())) { "截图来源路径不能包含链接。" }
        require(file.isFile && file.canRead() && file.length() in 1..StrictImageInput.MAX_BYTES.toLong()) {
            "截图原始副本缺失、为空或超过大小限制。"
        }
        return file
    }

    /** Drops only this staged session; persisted image-sources are exclusively owned by ProjectStore. */
    fun discard(source: ImportedImageSource) {
        require(ID.matches(source.sourceId)) { "截图来源标识无效。" }
        require(source.privateRelativePath == "$STAGING/${source.sourceId}/original.png" ||
            source.privateRelativePath == "$STAGING/${source.sourceId}/original.jpg") {
            "只能清理尚未登记的截图导入会话。"
        }
        releaseStaging(File(File(root, STAGING), source.sourceId))
    }

    /** Cold-start reclamation is bounded and serialized with reservations across importer instances. */
    fun recoverAbandonedStaging(): Int = synchronized(ACTIVE) {
        val staging = File(root, STAGING)
        if (!staging.exists()) return@synchronized 0
        if (!safeDirectory(staging, root)) return@synchronized 0
        var removed = 0
        Files.newDirectoryStream(staging.toPath()).use { stream ->
            var inspected = 0
            for (path in stream) {
                if (++inspected > 128) break
                val directory = path.toFile()
                if (!ID.matches(directory.name) || directory.absolutePath in ACTIVE ||
                    !safeDirectory(directory, staging)) continue
                if (deleteOwnedDirectory(directory, failOnUnknown = false)) removed++
            }
        }
        removed
    }

    private fun reserveStaging(id: String): File = synchronized(ACTIVE) {
        val staging = File(root, STAGING)
        if ((!staging.isDirectory && !staging.mkdir()) || !safeDirectory(staging, root)) {
            throw IOException("无法建立截图私有暂存目录。")
        }
        val directory = File(staging, id)
        if (!directory.mkdir() || !safeDirectory(directory, staging)) throw IOException("无法建立截图导入会话。")
        ACTIVE.add(directory.absolutePath)
        directory
    }

    private fun releaseStaging(directory: File) = synchronized(ACTIVE) {
        val staging = File(root, STAGING)
        require(directory.parentFile == staging && ID.matches(directory.name)) { "暂存目录无效。" }
        if (directory.exists()) {
            require(safeDirectory(staging, root) && safeDirectory(directory, staging)) { "暂存目录不能包含链接。" }
            check(deleteOwnedDirectory(directory, failOnUnknown = true)) { "截图暂存目录清理失败。" }
        }
        ACTIVE.remove(directory.absolutePath)
        Unit
    }

    private fun deleteOwnedDirectory(directory: File, failOnUnknown: Boolean): Boolean {
        val children = mutableListOf<File>()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for (path in stream) {
                if (children.size >= ORIGINAL_NAMES.size) {
                    if (failOnUnknown) throw IOException("截图暂存目录包含过多文件，已停止清理。")
                    return false
                }
                children += path.toFile()
            }
        }
        if (children.any { it.name !in ORIGINAL_NAMES || !it.isFile || Files.isSymbolicLink(it.toPath()) || it.canonicalFile != it.absoluteFile }) {
            if (failOnUnknown) throw IOException("截图暂存目录包含未知文件，已停止清理。")
            return false
        }
        for (file in children) if (!file.delete() && file.exists()) throw IOException("截图私有暂存文件清理失败。")
        if (!directory.delete() && directory.exists()) throw IOException("截图私有暂存目录清理失败。")
        return true
    }

    private fun safeDirectory(directory: File, parent: File): Boolean =
        directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
            directory.canonicalFile == directory.absoluteFile && directory.canonicalFile.parentFile == parent

    private fun copy(open: () -> InputStream, file: File, owner: CoroutineContext): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        open().use { input -> FileOutputStream(file).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                owner.ensureActive()
                val allowed = minOf(buffer.size.toLong(), StrictImageInput.MAX_BYTES - length + 1).toInt()
                val count = input.read(buffer, 0, allowed)
                if (count < 0) break
                if (count == 0) throw IOException("无法继续读取截图。")
                length += count
                if (length > StrictImageInput.MAX_BYTES) throw IOException("单张截图不能超过 10 MiB。")
                digest.update(buffer, 0, count); output.write(buffer, 0, count)
            }
            owner.ensureActive(); output.fd.sync()
        } }
        if (length == 0L || file.length() != length) throw IOException("截图为空或复制不完整。")
        return length to digest.digest().toHex()
    }

    private fun inspectFile(file: File, owner: CoroutineContext): StrictImageInput.Result =
        StrictImageInput.inspect(readBounded(file, owner)) { owner.ensureActive() }

    private fun readBounded(file: File, owner: CoroutineContext): ByteArray {
        val length = file.length()
        if (length !in 1..StrictImageInput.MAX_BYTES.toLong()) throw IOException("截图为空或超过 10 MiB。")
        StrictImageInput.requireMemoryHeadroom(length + 16L * 1024 * 1024)
        val bytes = ByteArray(length.toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                owner.ensureActive()
                val count = input.read(bytes, offset, minOf(64 * 1024, bytes.size - offset))
                if (count <= 0) throw IOException("截图副本读取不完整。")
                offset += count
            }
            if (input.read() != -1) throw IOException("截图副本在读取时发生变化。")
        }
        owner.ensureActive(); return bytes
    }

    private fun normalize(input: StrictImageInput.Result, metadata: ImageSourceMetadata, owner: CoroutineContext): Bitmap {
        owner.ensureActive()
        val swapped = input.orientation >= 5
        val targetRawWidth = if (swapped) metadata.outputHeight else metadata.outputWidth
        val targetRawHeight = if (swapped) metadata.outputWidth else metadata.outputHeight
        var sample = 1
        while (input.width / (sample * 2) >= targetRawWidth && input.height / (sample * 2) >= targetRawHeight) sample *= 2
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inScaled = false
            inJustDecodeBounds = true
        }
        // outWidth/outHeight describe the sampled output, not always the encoded source.
        // Ask the same platform decoder for both sizes, and constrain rounding to floor/ceil.
        BitmapFactory.decodeByteArray(input.decodeBytes, 0, input.decodeBytes.size, options)
        if (options.outWidth != input.width || options.outHeight != input.height) throw IOException("截图解码尺寸与文件头不符。")
        options.inSampleSize = sample
        BitmapFactory.decodeByteArray(input.decodeBytes, 0, input.decodeBytes.size, options)
        val decodeWidth = options.outWidth
        val decodeHeight = options.outHeight
        if (decodeWidth !in maxOf(1, input.width / sample)..((input.width + sample - 1) / sample) ||
            decodeHeight !in maxOf(1, input.height / sample)..((input.height + sample - 1) / sample)) {
            throw IOException("截图采样尺寸不符合内存限制。")
        }
        // Include a second temporary sample plane for platform fine scaling / color conversion.
        val workingBytes = 8L * decodeWidth * decodeHeight + 4L * metadata.outputWidth * metadata.outputHeight
        if (workingBytes > 112L * 1024 * 1024) throw IOException("截图解码工作区过大。")
        StrictImageInput.requireMemoryHeadroom(workingBytes + 8L * 1024 * 1024)
        options.inJustDecodeBounds = false
        val decoded = BitmapFactory.decodeByteArray(input.decodeBytes, 0, input.decodeBytes.size, options)
            ?: throw IOException("系统无法完整解码此截图。")
        var output: Bitmap? = null
        try {
            owner.ensureActive()
            if (decoded.width <= 0 || decoded.height <= 0 || decoded.width.toLong() * decoded.height > StrictImageInput.MAX_PIXELS ||
                decoded.width != decodeWidth || decoded.height != decodeHeight ||
                options.outWidth != decodeWidth || options.outHeight != decodeHeight) throw IOException("实际解码的截图尺寸与采样头不符。")
            decoded.density = Bitmap.DENSITY_NONE
            output = Bitmap.createBitmap(metadata.outputWidth, metadata.outputHeight, Bitmap.Config.ARGB_8888,
                false, ColorSpace.get(ColorSpace.Named.SRGB))
            output.density = Bitmap.DENSITY_NONE
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
            canvas.drawBitmap(decoded, orientationMatrix(input.orientation, decoded.width, decoded.height,
                metadata.outputWidth, metadata.outputHeight), Paint().apply { isAntiAlias = false; isFilterBitmap = true })
            owner.ensureActive()
            return output
        } catch (error: Throwable) {
            output?.recycle(); throw error
        } finally { decoded.recycle() }
    }

    private fun displayName(uri: Uri): String = try {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "截图"
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { "截图" }

    companion object {
        private const val STAGING = "image-import-staging"
        private const val SOURCES = "image-sources"
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val ORIGINAL_NAMES = setOf("original.part", "original.png", "original.jpg")
        private val ACTIVE = mutableSetOf<String>()

        internal fun fitOutputSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> {
            require(width > 0 && height > 0 && width.toLong() * height <= StrictImageInput.MAX_PIXELS && orientation in 1..8)
            val w = if (orientation >= 5) height else width
            val h = if (orientation >= 5) width else height
            val scale = minOf(1.0, 1080.0 / minOf(w, h), 2400.0 / maxOf(w, h))
            return maxOf(1, floor(w * scale).toInt()) to maxOf(1, floor(h * scale).toInt())
        }

        internal fun orientationMatrix(orientation: Int, width: Int, height: Int, outputWidth: Int, outputHeight: Int): Matrix {
            val w = width.toFloat(); val h = height.toFloat()
            val values = when (orientation) {
                1 -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
                3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
                4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
                5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
                7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
                8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
                else -> throw IllegalArgumentException("截图方向无效。")
            }
            val sx = outputWidth.toFloat() / if (orientation >= 5) height else width
            val sy = outputHeight.toFloat() / if (orientation >= 5) width else height
            for (index in 0..2) values[index] *= sx
            for (index in 3..5) values[index] *= sy
            return Matrix().apply { setValues(values) }
        }
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun cleanName(name: String, extension: String): String =
            name.asSequence().filterNot { it.isISOControl() }.take(160).joinToString("").ifBlank { "截图.$extension" }
    }
}
