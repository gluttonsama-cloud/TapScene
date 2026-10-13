package com.tapscene.clickplan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only accepts the exact already-reviewed output resolved by ClickChainReviewStore. */
internal suspend fun readClickChainThumbnail(file: File, output: ClickChainOutput): Bitmap {
    require(output.width > 0 && output.height > 0 && output.width.toLong() * output.height <= 12_000_000)
    val bytes = file.length()
    require(bytes in 1..50L * 1024 * 1024)
    return file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            require(read > 0)
            count += read
            require(count <= bytes)
            digest.update(buffer, 0, read)
        }
        require(count == bytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == output.sha256)
        input.channel.position(0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, bounds)
        require(bounds.outMimeType == "image/png" && bounds.outWidth == output.width && bounds.outHeight == output.height)
        var sample = 1
        while (((output.width.toLong() + sample - 1) / sample) * ((output.height.toLong() + sample - 1) / sample) > 220_000) sample *= 2
        currentCoroutineContext().ensureActive()
        input.channel.position(0)
        val bitmap = checkNotNull(BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
            inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
        }))
        try {
            currentCoroutineContext().ensureActive()
            check(bitmap.width.toLong() * bitmap.height <= 220_000)
            bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
}
