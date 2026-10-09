package com.tapscene.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.metrics.LogSessionId
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
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
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
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
    private val appContext = PrivateMediaContext(context)

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
     * Re-encodes [startUs, endUs) of a local AVC/HEVC MP4 through Surface decoding to H.264,
     * never passthrough/remux trimming. Explicit PQ/HLG is tone-mapped to SDR by Media3.
     * Codec bit depth alone is not an HDR signal. Source pixels and metadata are never rewritten.
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
        if (startUs < 0 || endUs <= startUs || endUs - startUs > MAX_VIDEO_DURATION_US) {
            throw MediaExportException("裁剪准备：请选择大于 0 且不超过 10 秒的视频区间。")
        }
        val frozenMasks = masks.toList()
        var inputDiagnostic: InputVideoInfo? = null
        var failureStage = "读取私有源视频"
        try {
            return createCandidate(outputDirectory, "mp4", { stage ->
                if (stage == Stage.FINALIZING) failureStage = "候选文件原子保存"
                onStage(stage)
            }) { temporary ->
                require(source.isFile && source.canRead()) { "本机源视频不可读，请重新导入。" }
                require(source.canonicalFile.toPath().startsWith(appContext.noBackupFilesDir.canonicalFile.toPath())) {
                    "请先将源视频导入应用私有目录。"
                }
                val sourceInfo = runInterruptible { SafeMediaWriterValidation.verifyVideoInput(source, endUs) }
                inputDiagnostic = sourceInfo
                if (sourceInfo.isHdr && Build.VERSION.SDK_INT < 29) {
                    throw MediaExportException(
                        "${sourceInfo.diagnostic("HDR 色调映射准备")}；真实 HDR 转 SDR 需要 Android 10 或更新系统，原片已保留。",
                    )
                }
                failureStage = "H.264 编码尺寸协商"
                val plan = negotiateEncoder(sourceInfo)
                val canonicalSize = MediaInputPolicy.fitOutputSize(
                    sourceInfo.metadata.displayWidth, sourceInfo.metadata.displayHeight,
                )
                val normalization = CanvasNormalization(
                    plan.width, plan.height, canonicalSize.first, canonicalSize.second, frozenMasks,
                )
                val overlay = FixedOpaqueOverlay(normalization)
                currentCoroutineContext().ensureActive()
                onStage(Stage.RENDERING)
                failureStage = "Surface 解码与视频处理"
                val result = try {
                    transformVideo(source, startUs, endUs, temporary, sourceInfo, plan, normalization, overlay)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: ExportException) {
                    throw MediaExportException(exportFailureMessage(sourceInfo, plan, failure), failure)
                }

                failureStage = "重编码与遮挡画布核对"
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
                check(overlay.frameWidth.get() == plan.width && overlay.frameHeight.get() == plan.height) {
                    "遮挡画布与协商的编码尺寸不一致，已丢弃。"
                }
                check(temporary.length() in 1..MAX_OUTPUT_BYTES) { "单个候选文件不能超过 50 MiB。" }
                currentCoroutineContext().ensureActive()
                onStage(Stage.VERIFYING)
                failureStage = "实际输出全帧复核"
                val info = SafeMediaWriterValidation.verifyVideoOutput(
                    appContext, temporary, endUs - startUs, normalization.outputMasks(),
                    expectedWidth = plan.width, expectedHeight = plan.height,
                    expectedFrameCount = result.videoFrameCount,
                )
                // The explicitly negotiated landscape/portrait canvas must survive encoding exactly.
                // Silent encoder scaling after the mask would invalidate outward-rounded boundaries.
                check(info.width == overlay.frameWidth.get() && info.height == overlay.frameHeight.get()) {
                    "编码后画面尺寸发生变化，请重新生成。"
                }
                VerifiedMedia(info.width, info.height, "video/mp4", info.durationUs)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: MediaExportException) {
            throw known
        } catch (failure: Exception) {
            val diagnostic = inputDiagnostic?.diagnostic(failureStage) ?: "阶段=$failureStage"
            val recovery = when (failureStage) {
                "实际输出全帧复核" -> "候选文件未通过实际输出检查，已丢弃；可扩大遮挡、缩短片段后重试。"
                "H.264 编码尺寸协商" -> "本机未能协商支持的 H.264 输出画布，可换设备重试。"
                "候选文件原子保存" -> "候选文件未保存，请检查本机可用空间后重试。"
                else -> "候选文件未生成，请检查片段与设备支持后重试。"
            }
            // Never expose a nested platform exception's arbitrary message, URI, or source path.
            throw MediaExportException("$diagnostic；$recovery 原片已保留。", failure)
        }
    }

    private suspend fun transformVideo(
        source: File,
        startUs: Long,
        endUs: Long,
        output: File,
        sourceInfo: InputVideoInfo,
        plan: EncoderPlan,
        normalization: CanvasNormalization,
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
            .setEffects(Effects(emptyList(), listOf(
                // Preserve actual VFR timestamps, including the final change before EOS.
                // Media3 1.9.4's default frame dropper discards its last cached frame at EOS,
                // which can erase a late state change in a sparse screen recording. Do not use
                // that effect or EditedMediaItem.setFrameRate as a video frame-rate conversion.
                CanonicalPresentation(sourceInfo.metadata),
                normalization,
                OverlayEffect(listOf(overlay)),
            )))
            .build()
        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItem(edited)
            .build()
        val composition = Composition.Builder(sequence)
            .setTransmuxAudio(false)
            .setTransmuxVideo(false)
            // SDR inputs are unchanged by this mode. If Media3 discovers an explicit HDR
            // transfer not exposed by the platform probe, it must still really tone-map to SDR.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        val delegate = DefaultEncoderFactory.Builder(appContext)
            .setEnableFallback(false)
            .setVideoEncoderSelector { mime ->
                if (mime == MimeTypes.VIDEO_H264) ImmutableList.of(plan.codec)
                else ImmutableList.of()
            }
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder()
                .setBitrate(plan.bitrate)
                .setBitrateMode(plan.bitrateMode)
                .setMaxBFrames(0)
                .build())
            .build()
        val forceEncode = object : Codec.EncoderFactory by delegate {
            override fun videoNeedsEncoding(): Boolean = true

            @SuppressLint("NewApi") // The optional API-31 type belongs to Media3's interface.
            override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
                check(format.width == plan.width && format.height == plan.height && format.rotationDegrees == 0) {
                    "编码准备：实际画布与协商结果不一致，已停止。"
                }
                // Codec configuration hint only: this never retimes, synthesizes, or drops
                // video frames. Actual VFR PTS and the final source state are retained.
                return delegate.createForVideoEncoding(
                    format.buildUpon().setFrameRate(ENCODER_FRAME_RATE_HINT).build(), logSessionId,
                )
            }
        }
        val completion = CompletableDeferred<ExportResult>()
        val transformer = Transformer.Builder(appContext)
            .setLooper(Looper.getMainLooper())
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAssetLoaderFactory(MediaColorNormalization.assetLoaderFactory(appContext))
            .setEncoderFactory(forceEncode)
            // Clear source metadata (including location) before writing the new container.
            // Source crop/SAR/rotation are applied by the Surface/GL path before normalization.
            // Preserve the negotiated orientation instead of adding container rotation metadata.
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

    /** The encoder is selected before rendering; no post-mask size fallback is permitted. */
    private data class EncoderPlan(
        val codec: MediaCodecInfo,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val bitrateMode: Int,
    )

    private suspend fun negotiateEncoder(input: InputVideoInfo): EncoderPlan {
        val (budgetWidth, budgetHeight) = MediaInputPolicy.fitOutputSize(
            input.metadata.displayWidth, input.metadata.displayHeight,
        )
        var best: EncoderPlan? = null
        val failures = mutableListOf<Exception>()
        for (codec in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            currentCoroutineContext().ensureActive()
            if (!codec.isEncoder || !codec.supportedTypes.any { it.equals(MimeTypes.VIDEO_H264, true) }) continue
            try {
                val capabilities = codec.getCapabilitiesForType(MimeTypes.VIDEO_H264)
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in capabilities.colorFormats) continue
                val video = capabilities.videoCapabilities ?: continue
                val encoder = capabilities.encoderCapabilities ?: continue
                val bitrateMode = when {
                    encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    else -> continue
                }
                // H.264 4:2:0 also needs even dimensions, including encoders reporting alignment 1.
                val alignWidth = video.widthAlignment.let { if (it % 2 == 0) it else it * 2 }
                val alignHeight = video.heightAlignment.let { if (it % 2 == 0) it else it * 2 }
                val landscape = budgetWidth >= budgetHeight
                val longBudget = if (landscape) budgetWidth else budgetHeight
                val longAlignment = if (landscape) alignWidth else alignHeight
                val firstLong = longBudget / longAlignment * longAlignment
                // Bounded by the 2400-pixel output budget, never by the untrusted source size.
                for (longSide in firstLong downTo longAlignment step longAlignment) {
                    currentCoroutineContext().ensureActive()
                    val width = if (landscape) longSide else
                        (budgetWidth.toLong() * longSide / longBudget).toInt() / alignWidth * alignWidth
                    val height = if (!landscape) longSide else
                        (budgetHeight.toLong() * longSide / longBudget).toInt() / alignHeight * alignHeight
                    if (width <= 0 || height <= 0) continue
                    if (best != null && width.toLong() * height <= best.width.toLong() * best.height) break
                    if (!video.isSizeSupported(width, height) ||
                        !video.areSizeAndRateSupported(width, height, ENCODER_FRAME_RATE_HINT.toDouble())
                    ) continue
                    val targetBitrate = (width.toLong() * height * ENCODER_FRAME_RATE_HINT * 0.1).toLong()
                        .coerceIn(500_000L, 20_000_000L).toInt()
                    best = EncoderPlan(codec, width, height, video.bitrateRange.clamp(targetBitrate), bitrateMode)
                    break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A broken capability report does not stop trying other independently listed codecs.
                failures += failure
            }
        }
        return best ?: throw IOException(
            "${input.diagnostic("H.264 编码尺寸协商")}；本机没有支持预算内画布的 Surface 编码器，原片已保留。",
        ).also { failure -> failures.forEach(failure::addSuppressed) }
    }

    /** Exactly the same pre-mask canvas as the frame editor's bounded Surface extraction. */
    private class CanonicalPresentation(private val metadata: SourceMetadata) : MatrixTransformation {
        private var presentation: Presentation? = null
        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            // Match Media3's square-pixel expansion, including SAR < 1 (height expands).
            val ratio = metadata.pixelWidthHeightRatio
            val squareWidth = if (ratio > 1f) (metadata.width * ratio).toInt() else metadata.width
            val squareHeight = if (ratio < 1f) (metadata.height / ratio).toInt() else metadata.height
            val rotated = metadata.rotationDeg == 90 || metadata.rotationDeg == 270
            val expectedWidth = if (rotated) squareHeight else squareWidth
            val expectedHeight = if (rotated) squareWidth else squareHeight
            check(abs(inputWidth - expectedWidth) <= 1 && abs(inputHeight - expectedHeight) <= 1) {
                "显示几何核对：Surface 的 crop、SAR 或旋转结果与取帧画布不一致。"
            }
            val (width, height) = MediaInputPolicy.fitOutputSize(metadata.displayWidth, metadata.displayHeight)
            return Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
                .also { presentation = it }.configure(inputWidth, inputHeight)
        }
        override fun getMatrix(presentationTimeUs: Long): Matrix =
            checkNotNull(presentation).getMatrix(presentationTimeUs)
        override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
    }

    /**
     * Fit the entire editor canvas without stretching/cropping, including its subpixel-rounding
     * bars. Map masks using the identical transform before drawing onto the encoder canvas.
     */
    private class CanvasNormalization(
        val width: Int,
        val height: Int,
        private val canonicalWidth: Int,
        private val canonicalHeight: Int,
        private val masks: List<OpaqueMask>,
    ) : MatrixTransformation {
        private val presentation = Presentation.createForWidthAndHeight(
            width, height, Presentation.LAYOUT_SCALE_TO_FIT,
        )
        @Volatile private var configuredMasks: List<OpaqueMask>? = null
        private var inputWidth = 0
        private var inputHeight = 0

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            check(inputWidth == canonicalWidth && inputHeight == canonicalHeight) {
                "画布规范化：遮挡前画布与编辑器画布不一致。"
            }
            check(this.inputWidth == 0 || (this.inputWidth == inputWidth && this.inputHeight == inputHeight)) {
                "画布规范化：片段中途改变显示尺寸，请拆分后重试。"
            }
            this.inputWidth = inputWidth
            this.inputHeight = inputHeight
            val result = presentation.configure(inputWidth, inputHeight)
            check(result.width == width && result.height == height) { "画布规范化：输出尺寸与协商结果不一致。" }
            val scale = minOf(width.toDouble() / inputWidth, height.toDouble() / inputHeight)
            val contentWidth = inputWidth * scale
            val contentHeight = inputHeight * scale
            val xOffset = (width - contentWidth) / 2
            val yOffset = (height - contentHeight) / 2
            configuredMasks = masks.map { mask ->
                // Preserve the editor's outward-rounded pixel coverage. A one-output-pixel
                // guard also covers resampling footprints and floating-point edge rounding.
                val rect = mask.toPixelRect(inputWidth, inputHeight)
                OpaqueMask(
                    ((xOffset + rect.left * scale - 1) / width).toFloat().coerceIn(0f, 1f),
                    ((yOffset + rect.top * scale - 1) / height).toFloat().coerceIn(0f, 1f),
                    ((xOffset + rect.right * scale + 1) / width).toFloat().coerceIn(0f, 1f),
                    ((yOffset + rect.bottom * scale + 1) / height).toFloat().coerceIn(0f, 1f),
                )
            }
            return result
        }

        override fun getMatrix(presentationTimeUs: Long): Matrix = presentation.getMatrix(presentationTimeUs)

        // Force an actual processing stage even when the source already has exactly this size.
        override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

        fun outputMasks(): List<OpaqueMask> = checkNotNull(configuredMasks) { "遮挡前尚未完成画布规范化。" }
    }

    private fun exportFailureMessage(input: InputVideoInfo, plan: EncoderPlan, failure: ExportException): String {
        val stage = when (failure.errorCode) {
            ExportException.ERROR_CODE_DECODER_INIT_FAILED -> "Surface 解码器初始化"
            ExportException.ERROR_CODE_DECODING_FAILED -> "Surface 视频解码"
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Surface 解码格式支持"
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED -> "H.264 编码器初始化"
            ExportException.ERROR_CODE_ENCODING_FAILED -> "H.264 视频编码"
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> "H.264 编码格式支持"
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ->
                if (input.isHdr) "HDR 色调映射／画布与遮挡处理" else "画布与遮挡处理"
            ExportException.ERROR_CODE_MUXING_FAILED, ExportException.ERROR_CODE_MUXING_TIMEOUT -> "MP4 封装"
            else -> "视频导出（${failure.errorCodeName}）"
        }
        val recovery = if (input.isHdr) {
            "本机可能不支持此 HDR 解码或 OpenGL 色调映射；可使用支持该格式的设备，或关闭 HDR 重新录制。"
        } else {
            "可重试、缩短片段，或使用设备兼容的录屏设置。"
        }
        return "${input.diagnostic(stage)}；输出 ${plan.width}×${plan.height}；$recovery 原片已保留。"
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

    private class FixedOpaqueOverlay(private val normalization: CanvasNormalization) : CanvasOverlay(true) {
        val framesDrawn = AtomicInteger()
        val frameWidth = AtomicInteger()
        val frameHeight = AtomicInteger()
        private val paint = opaqueBlackPaint()

        override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
            frameWidth.set(canvas.width)
            frameHeight.set(canvas.height)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            // This runs for every frame, including first/last and non-keyframe trim boundaries.
            // Its input is already the negotiated output canvas, including any alignment bars.
            check(canvas.width == normalization.width && canvas.height == normalization.height) {
                "遮挡处理：实际画布尺寸改变。"
            }
            for (mask in normalization.outputMasks()) {
                canvas.drawRect(mask.toPixelRect(canvas.width, canvas.height), paint)
            }
            framesDrawn.incrementAndGet()
        }
    }

    companion object {
        private const val MAX_IMAGE_PIXELS = 12_000_000L
        private const val MAX_VIDEO_DURATION_US = 10_000_000L
        private const val MAX_OUTPUT_BYTES = 50L * 1024 * 1024
        private const val ENCODER_FRAME_RATE_HINT = 60f

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
