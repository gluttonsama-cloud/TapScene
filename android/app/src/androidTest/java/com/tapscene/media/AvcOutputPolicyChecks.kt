package com.tapscene.media

import android.media.MediaFormat
import java.nio.ByteBuffer

/** Exact SPS bytes from the host's synthetic 64x64 FFmpeg corpus; no decoder conversion. */
object AvcOutputPolicyChecks {
    fun run(status: (String) -> Unit) {
        val valid = hex("000000016742c00ada109b016a02020280000003008000000407891350")
        val high10 = hex("00000001676e000aa6cb4213602d4040405000000300100000030080f1226a")
        val chroma422 = hex("00000001677a000abcb4213602d4040405000003000100000300080f1226a0")
        fun format(sps: ByteArray) = MediaFormat.createVideoFormat("video/avc", 64, 64).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        }
        AvcOutputPolicy.checkConfiguration(format(valid), 64, 64)
        for (invalid in listOf(high10, chroma422, valid.copyOf(6))) {
            check(runCatching { AvcOutputPolicy.checkConfiguration(format(invalid), 64, 64) }.isFailure)
        }
        val changed = valid + high10
        check(runCatching { AvcOutputPolicy.checkSample(ByteBuffer.wrap(changed), changed.size, 64, 64) }.isFailure)
        status("PASS encoded AVC policy: synthetic 8-bit 420 accepted; High10, 422, truncated and in-band format change rejected before decode")
    }
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
