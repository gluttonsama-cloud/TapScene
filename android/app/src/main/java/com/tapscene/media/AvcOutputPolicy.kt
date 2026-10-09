package com.tapscene.media

import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.container.ParsableNalUnitBitArray
import java.nio.ByteBuffer

/** Only for derived / viewer-package bytes, never ordinary phone-source import.
 * Inspect encoded SPS before a decoder can convert high-bit-depth/chroma input to YUV420.
 * Media3 owns SPS parsing; the small prefix read supplies its unexposed chroma_format_idc. */
@OptIn(UnstableApi::class)
internal object AvcOutputPolicy {
    fun checkConfiguration(format: MediaFormat, width: Int, height: Int) {
        val configuration = checkNotNull(format.getByteBuffer("csd-0")) { "过渡视频缺少 AVC 配置。" }
        check(inspect(configuration, configuration.remaining(), width, height) > 0) { "过渡视频缺少可验证的 SPS。" }
    }

    /** Android's extractor supplies AVC codec data and access units with Annex-B prefixes. */
    fun checkSample(buffer: ByteBuffer, size: Int, width: Int, height: Int) {
        val sample = buffer.duplicate().apply { position(0); limit(size) }
        inspect(sample, size, width, height)
    }

    private fun inspect(buffer: ByteBuffer, size: Int, width: Int, height: Int): Int {
        check(size in 4..50 * 1024 * 1024) { "过渡视频的 AVC 数据不完整。" }
        val bytes = ByteArray(size).also { buffer.duplicate().get(it) }
        var start = NalUnitUtil.findNalUnit(bytes, 0, size, BooleanArray(3))
        check(start in 0..1 && (0 until start).all { bytes[it] == 0.toByte() }) { "过渡视频的 AVC 封装不受支持。" }
        var spsCount = 0
        while (start < size) {
            val header = start + 3
            check(header < size) { "过渡视频的 AVC 单元不完整。" }
            val next = NalUnitUtil.findNalUnit(bytes, header + 1, size, BooleanArray(3))
            if (bytes[header].toInt() and 31 == 7) {
                val sps = NalUnitUtil.parseSpsNalUnit(bytes, header, next)
                check(sps.profileIdc in setOf(66, 77, 88, 100)) { "观看包只支持 AVC Baseline、Main、Extended 或 High。" }
                val prefix = ParsableNalUnitBitArray(bytes, header + 1, next)
                prefix.skipBits(24) // profile_idc, constraints, level_idc
                prefix.readUnsignedExpGolombCodedInt() // seq_parameter_set_id
                val chroma = if (sps.profileIdc == 100) prefix.readUnsignedExpGolombCodedInt() else 1
                check(chroma == 1 && sps.bitDepthLumaMinus8 == 0 && sps.bitDepthChromaMinus8 == 0) {
                    "观看包视频必须是编码后的 8 位 4:2:0 AVC。"
                }
                check(sps.width == width && sps.height == height && sps.pixelWidthHeightRatio == 1f) {
                    "过渡视频中途改变尺寸或依赖非方形像素。"
                }
                check(sps.colorTransfer !in setOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG)) {
                    "观看包视频仍标为 PQ/HLG HDR。"
                }
                spsCount++
            }
            start = next
        }
        return spsCount
    }
}
