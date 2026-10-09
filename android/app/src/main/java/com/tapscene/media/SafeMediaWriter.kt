package com.tapscene.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Creates PRIVATE, UNREVIEWED candidates. This class never saves to MediaStore, shares a URI,
 * confirms a review, or publishes. The caller must review the actual returned image / entire video,
 * bind that review to the returned digest and its draft revision, and invalidate it on edits.
 *
 * All output, including temporary files, must stay under [Context.getNoBackupFilesDir]. The caller
 * owns the input Bitmap and must not mutate or recycle it until [writePng] completes. Mask lists
 * are snapshotted. Cancel the calling coroutine to stop work; only this invocation's files are
 * removed. Stage callbacks are synchronous on the work thread, so UI consumers must dispatch them.
 */
@OptIn(UnstableApi::class)
class SafeMediaWriter(context: Context) {
    private val appContext = context.applicationContext

    enum class Stage { PREPARING, RENDERING, VERIFYING, FINALIZING }

    /** Technical verification is not the author's privacy review. There is no "reviewed" flag. */
    data class CandidateMedia(
        val file: File,
        val sha256: String,
        val width: Int,
        val height: Int,
        val mimeType: String,
        val durationUs: Long?,
    )

    suspend fun writePng(
        source: Bitmap,
        masks: List<OpaqueMask>,
        outputDirectory: File,
        onStage: (Stage) -> Unit = {},
    ): CandidateMedia {
        val frozenMasks = masks.toList()
        return createCandidate(outputDirectory, "png", onStage) { temporary ->
            require(!source.isRecycled) { "原图已经释放，请重新取帧。" }
            val width = source.width
            val height = source.height
            require(width > 0 && height > 0 && width.toLong() * height <= MAX_IMAGE_PIXELS) {
                "图片尺寸无效或超过 1200 万像素。"
            }
            currentCoroutineContext().ensureActive()
            onStage(Stage.RENDERING)

            // Always allocate a new sRGB pixel plane: never retain source EXIF, ICC, gainmap,
            // comments, original compressed bytes, or a hidden original-image layer.
            val pixels = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888, false,
                ColorSpace.get(ColorSpace.Named.SRGB),
            )
            try {
                val softwareSource = if (source.config == Bitmap.Config.HARDWARE) {
                    source.copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw IOException("无法读取原图像素。")
                } else {
                    source
                }
                try {
                    val canvas = Canvas(pixels)
                    canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
                    canvas.drawBitmap(softwareSource, 0f, 0f, Paint().apply {
                        isAntiAlias = false
                        isFilterBitmap = false
                    })
                    val paint = opaqueBlackPaint()
                    for (mask in frozenMasks) {
                        currentCoroutineContext().ensureActive()
                        canvas.drawRect(mask.toPixelRect(width, height), paint)
                    }
                } finally {
                    if (softwareSource !== source) softwareSource.recycle()
                }
                FileOutputStream(temporary).use { stream ->
                    check(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        "PNG 编码失败。"
                    }
                    stream.flush()
                    stream.fd.sync()
                }
            } finally {
                pixels.recycle()
            }
            currentCoroutineContext().ensureActive()
            onStage(Stage.VERIFYING)
            SafeMediaWriterValidation.verifyPng(temporary, width, height, frozenMasks)
            VerifiedMedia(width, height, "image/png", null)
        }
    }

    /**
     * Re-encodes [startUs, endUs) of a local H.264/SDR MP4, never passthrough/remux trimming.
     * Every selected frame receives the same fixed opaque masks; audio and other tracks are absent.
     * A mask-free request is still fully transcoded and still needs the author's complete review.
     */
    suspend fun writeVideo(
        source: File,
        startUs: Long,
        endUs: Long,
        masks: List<OpaqueMask>,
        outputDirectory: File,
        onStage: (Stage) -> Unit = {},
    ): CandidateMedia {
        require(startUs >= 0 && endUs > startUs && endUs - startUs <= MAX_VIDEO_DURATION_US) {
            "请选择大于 0 且不超过 10 秒的视频区间。"
        }
        val frozenMasks = masks.toList()
        return createCandidate(outputDirectory, "mp4", onStage) { temporary ->
            require(source.isFile && source.canRead()) { "本机源视频不可读，请重新导入。" }
            val sourceInfo = SafeMediaWriterValidation.verifyVideoInput(source, endUs)
            currentCoroutineContext().ensureActive()
            onStage(Stage.RENDERING)
            val overlay = FixedOpaqueOverlay(frozenMasks)
            val result = transformVideo(source, startUs, endUs, temporary, overlay)
            check(result.videoConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSCODED) {
                "输出未确认完整重编码，已丢弃。"
            }
            check(result.videoEncoderName != null && result.videoFrameCount > 0) {
                "没有得到实际编码的视频帧，已丢弃。"
            }
            check(result.audioMimeType == null && result.videoMimeType == MimeTypes.VIDEO_H264) {
                "输出轨道不符合无声 H.264 要求，已丢弃。"
            }
            check(overlay.framesDrawn.get() > 0) { "遮挡未经过实际像素处理，已丢弃。" }
            check(overlay.frameWidth.get() == sourceInfo.width && overlay.frameHeight.get() == sourceInfo.height) {
                "遮挡画布与源视频的显示尺寸不一致，已丢弃。"
            }
            check(temporary.length() in 1..MAX_OUTPUT_BYTES) { "单个候选文件不能超过 50 MiB。" }
            currentCoroutineContext().ensureActive()
            onStage(Stage.VERIFYING)
            val info = SafeMediaWriterValidation.verifyVideoOutput(
                appContext, temporary, endUs - startUs, frozenMasks,
            )
            // Portrait encoding is requested and fallback is disabled; a changed frame geometry
            // could invalidate rounded mask boundaries, so fail rather than silently rescale.
            check(info.width == overlay.frameWidth.get() && info.height == overlay.frameHeight.get()) {
                "编码后画面尺寸发生变化，请重新生成。"
            }
            VerifiedMedia(info.width, info.height, "video/mp4", info.durationUs)
        }
    }

    private suspend fun transformVideo(
        source: File,
        startUs: Long,
        endUs: Long,
        output: File,
        overlay: FixedOpaqueOverlay,
    ): ExportResult = withContext(Dispatchers.Main.immediate) {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(source))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(startUs)
                    .setEndPositionUs(endUs)
                    .setStartsAtKeyFrame(false)
                    .build(),
            ).build()
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
            .build()
        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItem(edited)
            .build()
        val composition = Composition.Builder(sequence)
            .setTransmuxAudio(false)
            .setTransmuxVideo(false)
            .build()
        val delegate = DefaultEncoderFactory.Builder(appContext)
            .setEnableFallback(false)
            .build()
        val forceEncode = object : Codec.EncoderFactory by delegate {
            override fun videoNeedsEncoding(): Boolean = true
        }
        val completion = CompletableDeferred<ExportResult>()
        val transformer = Transformer.Builder(appContext)
            .setLooper(Looper.getMainLooper())
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(forceEncode)
            // Clear source metadata (including location) before writing the new container.
            // Rotation is baked into pixels by portrait encoding, not inherited from the source.
            .setMuxerFactory(
                InAppMp4Muxer.Factory { metadata -> metadata.clear() }
                    .setVideoDurationUs(endUs - startUs),
            )
            .setPortraitEncodingEnabled(true)
            .experimentalSetTrimOptimizationEnabled(false)
            .experimentalSetMp4EditListTrimEnabled(false)
            .setUsePlatformDiagnostics(false)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    completion.complete(exportResult)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    // Preserve the cause for local handling; do not log source paths or media.
                    completion.completeExceptionally(exportException)
                }
            })
            .build()
        try {
            currentCoroutineContext().ensureActive()
            transformer.start(composition, output.absolutePath)
            completion.await()
        } finally {
            // Runs on Transformer's application looper even on coroutine cancellation. Returning
            // from here waits for cancel(), before createCandidate removes this invocation's file.
            try {
                transformer.cancel()
            } finally {
                transformer.removeAllListeners()
                completion.cancel()
            }
        }
    }

    private data class VerifiedMedia(
        val width: Int,
        val height: Int,
        val mimeType: String,
        val durationUs: Long?,
    )

    private suspend fun createCandidate(
        outputDirectory: File,
        extension: String,
        onStage: (Stage) -> Unit,
        generateAndVerify: suspend (File) -> VerifiedMedia,
    ): CandidateMedia {
        var temporary: File? = null
        var destination: File? = null
        try {
            return withContext(Dispatchers.IO) {
                onStage(Stage.PREPARING)
                val root = appContext.noBackupFilesDir.canonicalFile
                val directory = outputDirectory.canonicalFile
                require(directory.toPath().startsWith(root.toPath())) {
                    "候选文件只能写入不参与备份的应用私有目录。"
                }
                check(directory.isDirectory || directory.mkdirs()) { "无法创建私有输出目录。" }
                check(directory.canonicalFile.toPath().startsWith(root.toPath())) {
                    "私有输出目录已改变。"
                }
                currentCoroutineContext().ensureActive()
                val temp = File.createTempFile(".candidate-", ".$extension.part", directory)
                temporary = temp
                val verified = generateAndVerify(temp)
                currentCoroutineContext().ensureActive()
                check(temp.isFile && temp.length() in 1..MAX_OUTPUT_BYTES) {
                    "输出文件为空或超过 50 MiB。"
                }
                onStage(Stage.FINALIZING)
                // Video muxing is asynchronous; reaching this point means its file is closed.
                RandomAccessFile(temp, "rw").use { it.fd.sync() }
                val digest = sha256(temp)
                currentCoroutineContext().ensureActive()
                val finalFile = File(directory, "candidate-${UUID.randomUUID()}.$extension")
                check(!finalFile.exists()) { "候选文件名冲突，请重试。" }
                destination = finalFile
                // Same-directory atomic rename only. Never replace an older completed asset, and
                // never expose a partial file by falling back to a non-atomic copy.
                Files.move(temp.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                currentCoroutineContext().ensureActive()
                CandidateMedia(
                    finalFile, digest, verified.width, verified.height,
                    verified.mimeType, verified.durationUs,
                )
            }
        } catch (failure: Throwable) {
            // Includes cancellation while withContext is dispatching the completed result back.
            // No directory sweep: other candidates and already saved content are untouched.
            withContext(NonCancellable + Dispatchers.IO) {
                for (file in listOfNotNull(temporary, destination)) {
                    try {
                        Files.deleteIfExists(file.toPath())
                    } catch (cleanupFailure: Exception) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
            }
            throw failure
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private class FixedOpaqueOverlay(private val masks: List<OpaqueMask>) : CanvasOverlay(true) {
        val framesDrawn = AtomicInteger()
        val frameWidth = AtomicInteger()
        val frameHeight = AtomicInteger()
        private val paint = opaqueBlackPaint()

        override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
            frameWidth.set(canvas.width)
            frameHeight.set(canvas.height)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            // This runs for every frame, including first/last and non-keyframe trim boundaries.
            // CanvasOverlay uses the displayed input frame size; its default overlay is 1:1.
            for (mask in masks) {
                canvas.drawRect(mask.toPixelRect(canvas.width, canvas.height), paint)
            }
            framesDrawn.incrementAndGet()
        }
    }

    companion object {
        private const val MAX_IMAGE_PIXELS = 12_000_000L
        private const val MAX_VIDEO_DURATION_US = 10_000_000L
        private const val MAX_OUTPUT_BYTES = 50L * 1024 * 1024

        private fun opaqueBlackPaint() = Paint().apply {
            color = Color.BLACK
            alpha = 255
            style = Paint.Style.FILL
            isAntiAlias = false
            isDither = false
            isFilterBitmap = false
        }
    }
}
