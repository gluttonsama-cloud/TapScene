package com.tapscene.media

import java.nio.ByteBuffer

/** A bounded, Android-independent gate. Successful parsing never replaces full device decoding. */
internal class VideoBitstreamException(message: String) : IllegalArgumentException(message)

internal data class HevcSpsInfo(
    val profileIdc: Int,
    val chromaFormatIdc: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val codedWidth: Int,
    val codedHeight: Int,
    val width: Int,
    val height: Int,
    val colourPrimaries: Int? = null,
    val transferCharacteristics: Int? = null,
    val matrixCoefficients: Int? = null,
    val fullRange: Boolean? = null,
)

internal class VideoParameterSets internal constructor(
    val mime: String,
    val hevcSps: List<HevcSpsInfo>,
    internal val parameterSets: Map<Int, List<ByteArray>>,
)

/**
 * Single-layer HEVC Main and the existing AVC 8-bit profiles only. Syntax follows ITU-T H.265
 * 7.3.2.2, 7.3.3, 7.3.4, 7.3.7 and Annex E; cross-checked against AndroidX Media3 NalUnitUtil.
 * Android MediaCodec CSD and MediaExtractor MP4 access units use Annex-B start codes here.
 */
internal object VideoBitstreamParser {
    private const val AVC = "video/avc"
    private const val HEVC = "video/hevc"
    private const val MAX_CSD_BYTES = 65_536
    private const val MAX_CODED_SIDE = 2400 + 64
    private val avcNalTypes = setOf(1, 5, 6, 7, 8, 9, 10, 11, 12)
    private val hevcNalTypes = (0..9).toSet() + (16..21).toSet() + (32..40).toSet()

    fun isSupportedMime(mime: String?): Boolean = mime == AVC || mime == HEVC

    fun validateConfiguration(mime: String, csd: List<ByteArray>): VideoParameterSets {
        requireStream(isSupportedMime(mime), "不支持视频编码 $mime，请关闭录屏的高效编码并使用 H.264 SDR 重新录制。")
        requireStream(csd.isNotEmpty() && csd.size <= 3 &&
            csd.sumOf { it.size.toLong() } in 1..MAX_CSD_BYTES.toLong(), "视频编码参数缺失或过大（$mime）。")
        val sets = linkedMapOf<Int, MutableList<ByteArray>>()
        val sps = mutableListOf<ParsedHevcSps>()
        val vpsIds = mutableSetOf<Int>()
        val ppsReferences = mutableListOf<Pair<Int, Int>>()
        csd.forEach { bytes ->
            val buffer = ByteBuffer.wrap(bytes)
            forEachNal(buffer, bytes.size) { start, end ->
                val type = nalType(buffer, start, end, mime)
                val nal = ByteArray(end - start) { buffer.get(start + it) }
                if (mime == AVC) {
                    requireStream(type in avcNalTypes, "录屏包含暂不支持的 H.264 扩展。")
                    if (type == 7) {
                        validateAvcSps(nal)
                        sets.getOrPut(type) { mutableListOf() }.add(nal)
                    }
                } else {
                    requireStream(type in 32..34 || type == 39 || type == 40, "HEVC 初始化参数包含不支持的 NAL 单元。")
                    when (type) {
                        32 -> vpsIds.add(validateHevcVps(nal))
                        33 -> sps.add(parseHevcSpsInternal(nal))
                        34 -> {
                            val bits = Bits(rbsp(nal, 2))
                            ppsReferences.add(bits.ue(63) to bits.ue(15))
                        }
                        39, 40 -> rejectHevcHdrSei(nal)
                    }
                    if (type in 32..34) sets.getOrPut(type) { mutableListOf() }.add(nal)
                }
            }
        }
        requireStream(sets[if (mime == AVC) 7 else 33]?.isNotEmpty() == true, "无法验证录屏的编码参数（$mime）。")
        if (mime == HEVC) {
            requireStream(vpsIds.isNotEmpty() && ppsReferences.isNotEmpty(), "HEVC 缺少完整的 VPS/SPS/PPS 参数。")
            requireStream(sps.all { it.vpsId in vpsIds } &&
                ppsReferences.all { (_, spsId) -> sps.any { it.spsId == spsId } }, "HEVC 参数集引用无效。")
            // Accept repeated identical CSD, never ambiguous redefinitions of one parameter ID.
            listOf(32, 33, 34).forEach { type ->
                val byId = mutableMapOf<Int, ByteArray>()
                sets[type].orEmpty().forEach { nal ->
                    val bits = Bits(rbsp(nal, 2))
                    val id = when (type) {
                        32 -> bits.read(4)
                        33 -> parseHevcSpsInternal(nal).spsId
                        else -> bits.ue(63)
                    }
                    val previous = byId.put(id, nal)
                    requireStream(previous == null || previous.contentEquals(nal), "HEVC 初始化参数发生冲突。")
                }
            }
            requireStream(sps.map { it.info }.distinct().size == 1, "HEVC 包含不同尺寸或色彩的编码参数。")
        }
        return VideoParameterSets(mime, sps.map { it.info }, sets.mapValues { (_, value) -> value.toList() })
    }

    /** [size] bytes from absolute buffer offset zero; does not change position or limit. */
    fun verifySample(buffer: ByteBuffer, size: Int, configuration: VideoParameterSets) {
        forEachNal(buffer, size) { start, end ->
            val type = nalType(buffer, start, end, configuration.mime)
            val hevc = configuration.mime == HEVC
            requireStream(type in (if (hevc) hevcNalTypes else avcNalTypes), "录屏包含不支持的视频编码扩展（${configuration.mime}）。")
            if ((!hevc && type == 7) || (hevc && type in 32..34)) {
                requireStream(configuration.parameterSets[type].orEmpty().any { nal ->
                    nal.size == end - start && nal.indices.all { buffer.get(start + it) == nal[it] }
                }, "录屏中途改变了编码参数（${configuration.mime}），请重新录制固定格式的视频。")
            }
            if (hevc && (type == 39 || type == 40)) {
                requireStream(end - start <= MAX_CSD_BYTES, "HEVC SEI 元数据过大。")
                rejectHevcHdrSei(ByteArray(end - start) { buffer.get(start + it) })
            }
        }
    }

    /** [nal] includes the two-byte HEVC NAL header, without an Annex-B start code. */
    fun parseHevcSps(nal: ByteArray): HevcSpsInfo = parseHevcSpsInternal(nal).info

    private data class ParsedHevcSps(val spsId: Int, val vpsId: Int, val info: HevcSpsInfo)

    private fun parseHevcSpsInternal(nal: ByteArray): ParsedHevcSps {
        requireStream(nal.size in 3..MAX_CSD_BYTES && nalType(ByteBuffer.wrap(nal), 0, nal.size, HEVC) == 33,
            "HEVC SPS 参数无效。")
        val bits = Bits(rbsp(nal, 2))
        val vpsId = bits.read(4)
        val subLayers = bits.read(3)
        requireStream(subLayers <= 6, "HEVC 时域层数无效。")
        val temporalNesting = bits.flag()
        requireStream(subLayers != 0 || temporalNesting, "HEVC 时域参数无效。")
        val profile = readProfileTierLevel(bits, subLayers)
        val spsId = bits.ue(15)
        val chroma = bits.ue(3)
        requireStream(chroma == 1, "只支持 8 位、4:2:0 的 H.265/HEVC 录屏。")
        val codedWidth = bits.ue(MAX_CODED_SIDE)
        val codedHeight = bits.ue(MAX_CODED_SIDE)
        requireStream(codedWidth > 0 && codedHeight > 0, "HEVC 编码尺寸无效。")
        var cropX = 0
        var cropY = 0
        if (bits.flag()) {
            cropX = 2 * (bits.ue(31) + bits.ue(31))
            cropY = 2 * (bits.ue(31) + bits.ue(31))
        }
        val width = codedWidth - cropX
        val height = codedHeight - cropY
        requireStream(cropX < 64 && cropY < 64 && width > 0 && height > 0 &&
            maxOf(width, height) <= 2400 && minOf(width, height) <= 1080,
            "HEVC 编码尺寸或裁切超出支持范围。")
        val lumaDepth = bits.ue(8) + 8
        val chromaDepth = bits.ue(8) + 8
        requireStream(lumaDepth == 8 && chromaDepth == 8, "只支持 8 位、4:2:0 的 H.265/HEVC 录屏，请关闭 HDR 后重新录制。")
        val pocBits = bits.ue(12) + 4
        val allOrdering = bits.flag()
        for (layer in (if (allOrdering) 0 else subLayers)..subLayers) {
            val dpb = bits.ue(15)
            requireStream(bits.ue(15) <= dpb, "HEVC 参考帧参数无效。")
            bits.ue() // sps_max_latency_increase_plus1
        }
        val minCb = bits.ue(3) + 3
        val diffCb = bits.ue(3)
        requireStream(minCb + diffCb <= 6, "HEVC 编码块尺寸无效。")
        val minTb = bits.ue(3) + 2
        val diffTb = bits.ue(3)
        requireStream(minTb <= minCb && minTb + diffTb <= minOf(minCb + diffCb, 5), "HEVC 变换块尺寸无效。")
        bits.ue(4) // max_transform_hierarchy_depth_inter
        bits.ue(4) // max_transform_hierarchy_depth_intra
        if (bits.flag() && bits.flag()) skipScalingList(bits)
        bits.skip(2) // amp_enabled_flag, sample_adaptive_offset_enabled_flag
        if (bits.flag()) {
            requireStream(bits.read(4) <= 7 && bits.read(4) <= 7, "HEVC PCM 位深超出 8 位。")
            bits.ue(2)
            bits.ue(3)
            bits.skip(1)
        }
        skipShortTermReferenceSets(bits)
        if (bits.flag()) repeat(bits.ue(32)) { bits.skip(pocBits + 1) }
        bits.skip(2) // temporal MVP, strong intra smoothing
        val colour = if (bits.flag()) readVui(bits, subLayers) else Vui()
        if (bits.flag()) { // sps_extension_present_flag: all extensions deliberately out of scope
            requireStream(bits.read(8) == 0, "暂不支持 HEVC 扩展编码。")
        }
        bits.requireTrailingBits()
        return ParsedHevcSps(spsId, vpsId, HevcSpsInfo(profile, chroma, lumaDepth, chromaDepth,
            codedWidth, codedHeight, width, height, colour.primaries, colour.transfer, colour.matrix, colour.fullRange))
    }

    private fun validateHevcVps(nal: ByteArray): Int {
        val bits = Bits(rbsp(nal, 2))
        val id = bits.read(4)
        requireStream(bits.read(2) == 3 && bits.read(6) == 0, "暂不支持多层 HEVC 录屏。")
        val subLayers = bits.read(3)
        requireStream(subLayers <= 6, "HEVC 时域层数无效。")
        val nesting = bits.flag()
        requireStream((subLayers != 0 || nesting) && bits.read(16) == 65535, "HEVC VPS 参数无效。")
        readProfileTierLevel(bits, subLayers)
        return id
    }

    private fun readProfileTierLevel(bits: Bits, subLayers: Int): Int {
        val profileSpace = bits.read(2)
        bits.skip(1) // tier
        val profile = bits.read(5)
        requireStream(profileSpace == 0 && profile == 1, "只支持 HEVC Main 8 位录屏，请关闭高效编码或 HDR 后重新录制。")
        bits.skip(32) // Compatibility flags do not prove bit depth; SPS values are checked separately.
        bits.skip(48) // constraint flags
        bits.skip(8) // level_idc; the chosen device decoder must support the actual format/level.
        val profilePresent = BooleanArray(subLayers)
        val levelPresent = BooleanArray(subLayers)
        repeat(subLayers) { profilePresent[it] = bits.flag(); levelPresent[it] = bits.flag() }
        if (subLayers > 0) repeat(8 - subLayers) {
            requireStream(bits.read(2) == 0, "HEVC 保留参数无效。")
        }
        repeat(subLayers) { index ->
            if (profilePresent[index]) {
                requireStream(bits.read(2) == 0, "HEVC 子层 profile 无效。")
                bits.skip(1)
                requireStream(bits.read(5) == 1, "暂不支持 HEVC 非 Main 子层。")
                bits.skip(80)
            }
            if (levelPresent[index]) bits.skip(8)
        }
        return profile
    }

    private data class Vui(val primaries: Int? = null, val transfer: Int? = null,
        val matrix: Int? = null, val fullRange: Boolean? = null)

    private fun readVui(bits: Bits, subLayers: Int): Vui {
        if (bits.flag()) {
            when (bits.read(8)) {
                0, 1 -> Unit // Unspecified SAR does not override the container's square-pixel check.
                255 -> { val w = bits.read(16); val h = bits.read(16)
                    requireStream(w > 0 && w == h, "暂不支持非方形像素的 HEVC 录屏。") }
                else -> throw VideoBitstreamException("暂不支持非方形像素的 HEVC 录屏。")
            }
        }
        if (bits.flag()) bits.skip(1) // overscan
        var colour = Vui()
        if (bits.flag()) {
            bits.skip(3) // video_format
            colour = colour.copy(fullRange = bits.flag())
            if (bits.flag()) {
                val primaries = bits.read(8)
                val transfer = bits.read(8)
                val matrix = bits.read(8)
                requireStream(primaries in setOf(1, 5, 6) && matrix == primaries && transfer in setOf(1, 6),
                    "暂不支持 HDR 或未知的 HEVC 色彩格式，请使用 SDR 录屏。")
                colour = colour.copy(primaries = primaries, transfer = transfer, matrix = matrix)
            }
        }
        if (bits.flag()) { bits.ue(5); bits.ue(5) }
        bits.skip(1) // neutral_chroma_indication_flag
        requireStream(!bits.flag(), "暂不支持隔行 HEVC 录屏。")
        bits.skip(1) // frame_field_info_present_flag
        if (bits.flag()) { // default_display_window_flag; avoid unmodelled display-window coordinates.
            repeat(4) { requireStream(bits.ue() == 0, "暂不支持额外显示裁切的 HEVC 录屏。") }
        }
        if (bits.flag()) {
            bits.skip(64) // num_units_in_tick, time_scale; rate is checked against actual decoded PTS.
            if (bits.flag()) bits.ue()
            if (bits.flag()) skipHrd(bits, subLayers)
        }
        if (bits.flag()) {
            bits.skip(3)
            bits.ue(4095)
            bits.ue(16)
            bits.ue(16)
            bits.ue(15)
            bits.ue(15)
        }
        return colour
    }

    private fun skipHrd(bits: Bits, subLayers: Int) {
        val nalHrd = bits.flag()
        val vclHrd = bits.flag()
        var subPic = false
        if (nalHrd || vclHrd) {
            subPic = bits.flag()
            if (subPic) bits.skip(19)
            bits.skip(8)
            if (subPic) bits.skip(4)
            bits.skip(15)
        }
        repeat(subLayers + 1) {
            val fixedGeneral = bits.flag()
            val fixedWithin = fixedGeneral || bits.flag()
            val lowDelay = if (fixedWithin) { bits.ue(); false } else bits.flag()
            val cpbCount = if (lowDelay) 1 else bits.ue(31) + 1
            repeat((if (nalHrd) 1 else 0) + (if (vclHrd) 1 else 0)) {
                repeat(cpbCount) {
                    bits.ue(); bits.ue()
                    if (subPic) { bits.ue(); bits.ue() }
                    bits.skip(1)
                }
            }
        }
    }

    private fun skipScalingList(bits: Bits) {
        for (size in 0..3) {
            for (matrix in 0..5 step (if (size == 3) 3 else 1)) {
                if (!bits.flag()) bits.ue(matrix)
                else {
                    if (size > 1) bits.ue(494) // signed Exp-Golomb has the same length.
                    repeat(minOf(64, 1 shl (4 + 2 * size))) { bits.ue(256) }
                }
            }
        }
    }

    private fun skipShortTermReferenceSets(bits: Bits) {
        var previous = emptyList<Int>()
        repeat(bits.ue(64)) { index ->
            if (index > 0 && bits.flag()) {
                val negative = bits.flag()
                val magnitude = bits.ue(32767) + 1
                val delta = if (negative) -magnitude else magnitude
                val candidates = previous + 0
                val current = mutableListOf<Int>()
                candidates.forEach { poc ->
                    val used = bits.flag()
                    val useDelta = used || bits.flag()
                    val next = poc + delta
                    if (useDelta && next != 0) current.add(next)
                }
                requireStream(current.size <= 16, "HEVC 参考帧过多。")
                previous = current.filter { it < 0 }.sortedDescending() + current.filter { it > 0 }.sorted()
            } else {
                val negatives = bits.ue(16)
                val positives = bits.ue(16)
                requireStream(negatives + positives <= 16, "HEVC 参考帧过多。")
                val current = mutableListOf<Int>()
                var poc = 0
                repeat(negatives) { poc -= bits.ue(32767) + 1; current.add(poc); bits.skip(1) }
                poc = 0
                repeat(positives) { poc += bits.ue(32767) + 1; current.add(poc); bits.skip(1) }
                previous = current
            }
        }
    }

    private fun validateAvcSps(nal: ByteArray) {
        val bits = Bits(rbsp(nal, 1))
        val profile = bits.read(8)
        bits.skip(16) // constraint flags, level_idc
        bits.ue(31)
        requireStream(profile in setOf(66, 77, 88, 100), "只支持 8 位 H.264 SDR 录屏。")
        if (profile == 100) requireStream(bits.ue() == 1 && bits.ue() == 0 && bits.ue() == 0,
            "只支持 8 位、4:2:0 的 H.264 录屏。")
    }

    private fun rejectHevcHdrSei(nal: ByteArray) {
        val data = rbsp(nal, 2)
        var offset = 0
        fun extendedByteValue(): Int {
            var value = 0
            while (true) {
                requireStream(offset < data.size, "HEVC SEI 参数不完整。")
                val part = data[offset++].toInt() and 255
                value += part
                if (part != 255) return value
            }
        }
        while (offset < data.size - 1) {
            val type = extendedByteValue()
            val length = extendedByteValue()
            requireStream(length <= data.size - offset - 1, "HEVC SEI 参数不完整。")
            requireStream(type !in setOf(4, 23, 137, 141, 142, 144, 147, 148),
                "暂不支持 HDR、动态色彩或未验证的 HEVC 色彩元数据，请使用 SDR 录屏。")
            offset += length
        }
        requireStream(offset == data.size - 1 && data[offset].toInt() and 255 == 128, "HEVC SEI 尾部无效。")
    }

    private fun nalType(buffer: ByteBuffer, start: Int, end: Int, mime: String): Int {
        requireStream(end > start, "视频 NAL 单元不完整。")
        val first = buffer.get(start).toInt() and 255
        requireStream(first and 128 == 0, "视频 NAL header 无效。")
        if (mime == AVC) return first and 31
        requireStream(end - start >= 3, "HEVC NAL 单元不完整。")
        val second = buffer.get(start + 1).toInt() and 255
        requireStream((first and 1) == 0 && (second ushr 3) == 0 && (second and 7) != 0,
            "暂不支持多层或无效的 HEVC NAL 单元。")
        val type = (first ushr 1) and 63
        if (type in 32..34) requireStream(second and 7 == 1, "HEVC 参数集时域标记无效。")
        return type
    }

    private inline fun forEachNal(buffer: ByteBuffer, size: Int, block: (Int, Int) -> Unit) {
        requireStream(size > 0 && size <= buffer.limit(), "视频样本长度无效。")
        var start = -1
        var zeros = 0
        for (i in 0 until size) {
            val value = buffer.get(i).toInt() and 255
            if (value == 1 && zeros >= 2) {
                if (start >= 0) {
                    requireStream(i - zeros > start, "视频 NAL 单元不完整。")
                    block(start, i - zeros)
                } else requireStream(i == zeros, "视频样本须采用 Annex-B 格式。")
                start = i + 1
            }
            zeros = if (value == 0) zeros + 1 else 0
        }
        requireStream(start >= 0 && size - zeros > start, "视频样本须采用完整的 Annex-B 格式。")
        block(start, size - zeros)
    }

    private fun rbsp(nal: ByteArray, headerBytes: Int): ByteArray {
        requireStream(nal.size > headerBytes, "视频参数不完整。")
        val data = ByteArray(nal.size - headerBytes)
        var count = 0
        var zeros = 0
        for (i in headerBytes until nal.size) {
            val value = nal[i].toInt() and 255
            if (zeros >= 2 && value == 3) {
                requireStream(i + 1 < nal.size && (nal[i + 1].toInt() and 255) <= 3, "视频转义字节无效。")
                zeros = 0
                continue
            }
            requireStream(zeros < 2 || value > 2, "视频参数缺少转义字节。")
            data[count++] = nal[i]
            zeros = if (value == 0) zeros + 1 else 0
        }
        return data.copyOf(count)
    }

    private class Bits(private val data: ByteArray) {
        private var offset = 0
        fun read(count: Int): Int {
            requireStream(count in 0..30 && count <= data.size * 8 - offset, "视频编码参数已截断。")
            var value = 0
            repeat(count) { value = (value shl 1) or ((data[offset / 8].toInt() ushr (7 - offset % 8)) and 1); offset++ }
            return value
        }
        fun flag(): Boolean = read(1) == 1
        fun skip(count: Int) {
            requireStream(count >= 0 && count <= data.size * 8 - offset, "视频编码参数已截断。")
            offset += count
        }
        fun ue(maximum: Int = 1_048_575): Int {
            var zeros = 0
            while (!flag()) { requireStream(++zeros <= 20, "视频编码参数过大。") }
            val value = ((1 shl zeros) - 1) + read(zeros)
            requireStream(value <= maximum, "视频编码参数超出支持范围。")
            return value
        }
        fun requireTrailingBits() {
            requireStream(flag(), "HEVC SPS 尾部无效。")
            while (offset % 8 != 0) requireStream(!flag(), "HEVC SPS 尾部无效。")
            requireStream(offset == data.size * 8, "HEVC SPS 含未验证的尾部数据。")
        }
    }

    private fun requireStream(condition: Boolean, message: String) {
        if (!condition) throw VideoBitstreamException(message)
    }
}
