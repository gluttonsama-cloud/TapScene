package com.tapscene.media

import android.graphics.Bitmap
import java.io.Closeable

/** Facts about the private copied file; never trusted provider MIME, size, extension or video PTS. */
data class ImageSourceMetadata(
    val mime: String,
    val byteLength: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
    /** EXIF orientation 1..8, including mirrored forms; applied before mask coordinates exist. */
    val orientation: Int,
    val outputWidth: Int,
    val outputHeight: Int,
)

data class ImportedImageSource(
    val sourceId: String,
    /** Controlled relative path below noBackupFilesDir, never a provider URI. */
    val privateRelativePath: String,
    val displayName: String,
    val metadata: ImageSourceMetadata,
)

/**
 * Owns exactly one import session and one fresh opaque sRGB bitmap. Keep this lease while reviewing;
 * close after the store has copied the private original, or on cancel. This can never delete a
 * registered image-sources file. The bitmap is unreviewed input, not a publishable safe asset.
 */
class ImportedScreenshot internal constructor(
    val source: ImportedImageSource,
    val bitmap: Bitmap,
    private val cleanup: () -> Unit,
) : Closeable {
    private var closed = false
    @Synchronized override fun close() {
        if (closed) return
        // Cleanup can be retried if filesystem removal fails; the bitmap is still always released.
        if (!bitmap.isRecycled) bitmap.recycle()
        cleanup()
        closed = true
    }
}
