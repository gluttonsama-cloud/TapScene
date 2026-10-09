package com.tapscene.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExoPlayerAssetLoader

/** Fill only absent fields BEFORE decoder setup; never turn a known PQ/HLG transfer into SDR. */
@OptIn(UnstableApi::class)
internal object MediaColorNormalization {
    fun assetLoaderFactory(context: Context): ExoPlayerAssetLoader.Factory {
        val delegate = DefaultExtractorsFactory()
        val extractors = ExtractorsFactory {
            delegate.createExtractors().map { extractor ->
                object : ForwardingExtractor(extractor) {
                    override fun init(output: ExtractorOutput) {
                        super.init(object : ForwardingExtractorOutput(output) {
                            override fun track(id: Int, type: Int): TrackOutput {
                                val track = super.track(id, type)
                                if (type != C.TRACK_TYPE_VIDEO) return track
                                return object : ForwardingTrackOutput(track) {
                                    override fun format(format: Format) {
                                        super.format(format.buildUpon().setColorInfo(complete(format.colorInfo)).build())
                                    }
                                }
                            }
                        })
                    }
                }
            }.toTypedArray()
        }
        return ExoPlayerAssetLoader.Factory(
            context,
            DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build(),
            Clock.DEFAULT,
            DefaultMediaSourceFactory(context, extractors),
        )
    }

    // This is an interpretation for unspecified fields, not proof of the source's original color.
    // Match Media3's ordinary SDR default, retain every declared value, and use BT.2020/limited
    // for missing PQ/HLG companions so the real HDR -> SDR shader remains selected.
    fun complete(color: ColorInfo?): ColorInfo {
        if (color == null) return ColorInfo.SDR_BT709_LIMITED
        val hdr = ColorInfo.isTransferHdr(color)
        return color.buildUpon()
            .setColorSpace(if (color.colorSpace == Format.NO_VALUE) {
                if (hdr) C.COLOR_SPACE_BT2020 else C.COLOR_SPACE_BT709
            } else color.colorSpace)
            .setColorRange(if (color.colorRange == Format.NO_VALUE) C.COLOR_RANGE_LIMITED else color.colorRange)
            .setColorTransfer(if (color.colorTransfer == Format.NO_VALUE) C.COLOR_TRANSFER_SDR else color.colorTransfer)
            .build()
    }
}
