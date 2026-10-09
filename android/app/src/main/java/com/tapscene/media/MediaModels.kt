package com.tapscene.media

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Values describe the copied bytes, not the provider's filename, MIME or size claims. */
data class SourceMetadata(
    val mime: String,
    val byteLength: Long,
    val sha256: String,
    /** Visible content dimensions before applying [rotationDeg]. */
    val width: Int,
    val height: Int,
    val rotationDeg: Int,
    /** Video track duration read in microseconds, without a millisecond round trip. */
    val durationUs: Long,
    val pixelWidthHeightRatio: Float = 1f,
) {
    private val squarePixelWidth: Int get() = (width * pixelWidthHeightRatio).roundToInt().coerceAtLeast(1)
    val displayWidth: Int get() = if (rotationDeg == 90 || rotationDeg == 270) height else squarePixelWidth
    val displayHeight: Int get() = if (rotationDeg == 90 || rotationDeg == 270) squarePixelWidth else height
}

data class ImportedSource(
    val sourceId: String,
    /** Controlled path relative to Context.noBackupFilesDir; never an external URI. */
    val privateRelativePath: String,
    val displayName: String,
    val metadata: SourceMetadata,
)

/** The caller owns [bitmap] and must recycle it when it is no longer displayed. */
/** [presentationTimeUs] is quantized to [timePrecisionUs]; it is not a synthetic frame index. */
data class DecodedFrame(val bitmap: Bitmap, val presentationTimeUs: Long, val timePrecisionUs: Long = 1_000L)

class MediaImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
class FrameDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
class MediaExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal object MediaLimits {
    const val MAX_BYTES = 200L * 1024 * 1024
    const val MAX_DURATION_US = 180_000_000L
    const val MAX_WIDTH = 1080
    const val MAX_HEIGHT = 2400
    // MP4 timestamps may have only millisecond precision (e.g. 16/17 ms at 60 fps).
    const val TIMESTAMP_TOLERANCE_US = 1_000L
    const val SOURCE_DIRECTORY = "sources"
}
