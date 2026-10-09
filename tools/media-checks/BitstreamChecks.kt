package com.tapscene.media

import java.io.File
import java.nio.ByteBuffer

/** Executed with the runner's Kotlin/JDK, without Android stubs or another test framework. */
fun main(args: Array<String>) {
    val directory = File(args.single())
    fun fixture(name: String) = File(directory, "$name.csd").readBytes()
    fun rejected(name: String, block: () -> Unit) {
        try {
            block()
        } catch (_: VideoBitstreamException) {
            println("PASS reject $name")
            return
        }
        error("Accepted unsupported or corrupt input: $name")
    }
    val hevc = fixture("hevc-sdr")
    val hevcConfig = VideoBitstreamParser.validateConfiguration("video/hevc", listOf(hevc))
    check(hevcConfig.hevcSps.isNotEmpty())
    check(hevcConfig.hevcSps.all {
        it.bitDepthLuma == 8 && it.bitDepthChroma == 8 && it.chromaFormatIdc == 1 &&
            it.width == 160 && it.height == 288
    })
    VideoBitstreamParser.verifySample(ByteBuffer.wrap(hevc), hevc.size, hevcConfig)
    println("PASS real HEVC Main8 SDR parameter sets")
    val unspecified = VideoBitstreamParser.validateConfiguration("video/hevc", listOf(fixture("hevc-unknown-colour")))
    check(unspecified.hevcSps.all { it.colourPrimaries == null && it.transferCharacteristics == null && it.matrixCoefficients == null })
    println("PASS unspecified HEVC colour remains unspecified; device gate must reject it")
    val avc = fixture("avc-sdr")
    val avcConfig = VideoBitstreamParser.validateConfiguration("video/avc", listOf(avc))
    VideoBitstreamParser.verifySample(ByteBuffer.wrap(avc), avc.size, avcConfig)
    println("PASS AVC baseline regression")
    rejected("HEVC Main10 SDR") {
        VideoBitstreamParser.validateConfiguration("video/hevc", listOf(fixture("hevc-10bit-sdr")))
    }
    rejected("HEVC Main8 with PQ / BT2020") {
        VideoBitstreamParser.validateConfiguration("video/hevc", listOf(fixture("hevc-hdr")))
    }
    rejected("unknown codec") { VideoBitstreamParser.validateConfiguration("video/av01", listOf(hevc)) }
    rejected("empty configuration") { VideoBitstreamParser.validateConfiguration("video/hevc", emptyList()) }
    rejected("truncated configuration") {
        VideoBitstreamParser.validateConfiguration("video/hevc", listOf(hevc.copyOf(8)))
    }
    rejected("in-band Main10 replacement") {
        val replacement = fixture("hevc-10bit-sdr")
        VideoBitstreamParser.verifySample(ByteBuffer.wrap(replacement), replacement.size, hevcConfig)
    }
    rejected("in-band HDR replacement") {
        val replacement = fixture("hevc-hdr")
        VideoBitstreamParser.verifySample(ByteBuffer.wrap(replacement), replacement.size, hevcConfig)
    }
    rejected("missing Annex-B delimiter") {
        VideoBitstreamParser.verifySample(ByteBuffer.wrap(byteArrayOf(2, 1, 1)), 3, hevcConfig)
    }
    for ((label, header) in listOf("forbidden bit" to 0xC0, "nonzero layer" to 0x40)) {
        val invalid = hevc.clone()
        invalid[4] = header.toByte()
        if (label == "nonzero layer") invalid[5] = (invalid[5].toInt() or 8).toByte()
        rejected(label) { VideoBitstreamParser.validateConfiguration("video/hevc", listOf(invalid)) }
    }
    rejected("zero temporal id") {
        val invalid = hevc.clone().apply { this[5] = (this[5].toInt() and 0xF8).toByte() }
        VideoBitstreamParser.validateConfiguration("video/hevc", listOf(invalid))
    }
    for (seiType in listOf(4, 137, 142, 144, 147)) {
        rejected("HDR/dynamic SEI $seiType") {
            val sei = byteArrayOf(0, 0, 0, 1, 0x4E, 1, seiType.toByte(), 0, 0x80.toByte())
            VideoBitstreamParser.verifySample(ByteBuffer.wrap(sei), sei.size, hevcConfig)
        }
    }
    rejected("oversized CSD") {
        VideoBitstreamParser.validateConfiguration("video/hevc", listOf(ByteArray(65_537)))
    }
    rejected("Main10 bit depth hidden behind Main profile") {
        val disguised = fixture("hevc-10bit-sdr")
        for (i in 0 until disguised.size - 10) {
            if (disguised[i] == 0.toByte() && disguised[i + 1] == 0.toByte() &&
                disguised[i + 2] == 0.toByte() && disguised[i + 3] == 1.toByte()
            ) {
                val nalStart = i + 4
                val type = (disguised[nalStart].toInt() ushr 1) and 63
                val profileOffset = when (type) { 32 -> nalStart + 6; 33 -> nalStart + 3; else -> continue }
                disguised[profileOffset] = ((disguised[profileOffset].toInt() and 0xE0) or 1).toByte()
            }
        }
        VideoBitstreamParser.validateConfiguration("video/hevc", listOf(disguised))
    }
    println("TAPSCENE_BITSTREAM_CHECKS_OK")
}
