package com.tapscene.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** Real Android pixel checks. Host parser success never substitutes for running these on a device. */
object ScreenshotImportChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val directory = File(context.noBackupFilesDir, "screenshot-checks-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val importer = ScreenshotImporter(context)
            val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN)
            for (orientation in 1..8) {
                val file = File(directory, "orientation-$orientation.wrong-extension")
                file.writeBytes(png(3, 2, colors, orientation))
                importer.importScreenshot(file).use { lease ->
                    val source = lease.source
                    check(source.metadata.mime == "image/png" && source.metadata.orientation == orientation)
                    check(source.metadata.width == 3 && source.metadata.height == 2)
                    val expected = when (orientation) {
                        1 -> intArrayOf(0, 1, 2, 3, 4, 5)
                        2 -> intArrayOf(2, 1, 0, 5, 4, 3)
                        3 -> intArrayOf(5, 4, 3, 2, 1, 0)
                        4 -> intArrayOf(3, 4, 5, 0, 1, 2)
                        5 -> intArrayOf(0, 3, 1, 4, 2, 5)
                        6 -> intArrayOf(3, 0, 4, 1, 5, 2)
                        7 -> intArrayOf(5, 2, 4, 1, 3, 0)
                        else -> intArrayOf(2, 5, 1, 4, 0, 3)
                    }
                    val width = if (orientation >= 5) 2 else 3
                    val height = if (orientation >= 5) 3 else 2
                    check(lease.bitmap.width == width && lease.bitmap.height == height)
                    check(!lease.bitmap.hasAlpha() && lease.bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB))
                    if (Build.VERSION.SDK_INT >= 34) check(!lease.bitmap.hasGainmap())
                    for (index in expected.indices) check(lease.bitmap.getPixel(index % width, index / width) == colors[expected[index]])
                    val reread = importer.read(source)
                    try { check(reread.sameAs(lease.bitmap)) } finally { reread.recycle() }
                    // A second importer / cold-start recovery must not sweep an active session.
                    ScreenshotImporter(context)
                    check(importer.checkedSourceFile(source).isFile)
                }
            }
            status("PASS screenshot PNG: all 8 EXIF rotations/mirrors, actual pixels, mislabeled extension, private reread and active-session isolation")

            val alpha = File(directory, "alpha.png")
            alpha.writeBytes(png(3, 1, intArrayOf(0x00ff0000, 0x8000ff00.toInt(), 0xffffffff.toInt())))
            importer.importScreenshot(alpha).use {
                check(it.bitmap.getPixel(0, 0) == Color.BLACK)
                val middle = it.bitmap.getPixel(1, 0)
                check(Color.alpha(middle) == 255 && Color.red(middle) == 0 && Color.blue(middle) == 0 && abs(Color.green(middle) - 128) <= 1)
                check(it.bitmap.getPixel(2, 0) == Color.WHITE && !it.bitmap.hasAlpha())
            }
            status("PASS screenshot transparency: transparent hidden RGB becomes opaque BLACK; half-alpha composites onto BLACK")

            val jpegSource = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
            val jpegBytes = try {
                for (y in 0 until 32) for (x in 0 until 48) {
                    jpegSource.setPixel(x, y, colors[(y / 16) * 3 + x / 16])
                }
                ByteArrayOutputStream().also { check(jpegSource.compress(Bitmap.CompressFormat.JPEG, 100, it)) }.toByteArray()
            } finally { jpegSource.recycle() }
            for (orientation in 1..8) {
                val file = File(directory, "jpeg-$orientation.png")
                val exif = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff(orientation)
                file.writeBytes(jpegBytes.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xe1.toByte(), ((exif.size + 2) shr 8).toByte(), (exif.size + 2).toByte()) + exif + jpegBytes.copyOfRange(2, jpegBytes.size))
                importer.importScreenshot(file).use { lease ->
                    check(lease.source.metadata.mime == "image/jpeg")
                    val expected = when (orientation) {
                        1 -> intArrayOf(0, 1, 2, 3, 4, 5)
                        2 -> intArrayOf(2, 1, 0, 5, 4, 3)
                        3 -> intArrayOf(5, 4, 3, 2, 1, 0)
                        4 -> intArrayOf(3, 4, 5, 0, 1, 2)
                        5 -> intArrayOf(0, 3, 1, 4, 2, 5)
                        6 -> intArrayOf(3, 0, 4, 1, 5, 2)
                        7 -> intArrayOf(5, 2, 4, 1, 3, 0)
                        else -> intArrayOf(2, 5, 1, 4, 0, 3)
                    }
                    val cols = if (orientation >= 5) 2 else 3
                    for (index in expected.indices) {
                        val pixel = lease.bitmap.getPixel((index % cols) * 16 + 8, (index / cols) * 16 + 8)
                        val target = colors[expected[index]]
                        check(abs(Color.red(pixel) - Color.red(target)) <= 10 && abs(Color.green(pixel) - Color.green(target)) <= 10 && abs(Color.blue(pixel) - Color.blue(target)) <= 10)
                    }
                }
            }
            status("PASS screenshot JPEG: actual platform compression/decode with all 8 EXIF orientations")

            // Exact12MP predecode boundary, sample2 path and orientation-before-output-size policy.
            val large = File(directory, "sample2.png")
            large.writeBytes(solidPng(2160, 4800))
            importer.importScreenshot(large).use { lease ->
                check(lease.bitmap.width == 1080 && lease.bitmap.height == 2400)
                check(lease.bitmap.getPixel(500, 1000) == Color.rgb(23, 45, 67))
            }
            check(ScreenshotImporter.fitOutputSize(4000, 3000, 6) == (1080 to 1440))
            status("PASS screenshot bounded decode: 2160x4800 sampled image produces actual 1080x2400 pixels")

            val staging = File(context.noBackupFilesDir, "image-import-staging")
            fun children() = staging.list()?.toSet() ?: emptySet()
            val before = children()
            val tooLarge = File(directory, "too-large.jpg")
            java.io.RandomAccessFile(tooLarge, "rw").use { it.setLength(StrictImageInput.MAX_BYTES.toLong() + 1) }
            check(runCatching { importer.importScreenshot(tooLarge).close() }.isFailure)
            check(children() == before)
            val cancelled = Job().apply { cancel() }
            try {
                withContext(cancelled) { importer.importScreenshot(alpha).close() }
                error("Cancelled import completed")
            } catch (_: CancellationException) { }
            check(children() == before)
            val abandoned = File(staging, UUID.randomUUID().toString()).apply { check(mkdir()) }
            File(abandoned, "original.part").writeText("partial")
            ScreenshotImporter(context)
            check(!abandoned.exists())
            val persisted = importer.importScreenshot(alpha)
            val path = importer.checkedSourceFile(persisted.source)
            persisted.close()
            check(!path.exists() && !path.parentFile.exists())
            status("PASS screenshot lifecycle: over-limit/cancelled imports leave no session; abandoned exact-name recovery; close releases owned stage")
        } finally {
            check(directory.deleteRecursively())
        }
    }

    private fun tiff(orientation: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeBytes("MM"); out.writeShort(42); out.writeInt(8); out.writeShort(1)
            out.writeShort(0x112); out.writeShort(3); out.writeInt(1); out.writeShort(orientation); out.writeShort(0); out.writeInt(0)
        }
    }.toByteArray()

    private fun png(width: Int, height: Int, colors: IntArray, orientation: Int = 1): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val color = colors[y * width + x]
                raw.write(Color.red(color)); raw.write(Color.green(color)); raw.write(Color.blue(color)); raw.write(Color.alpha(color))
            }
        }
        val compressed = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { zip -> zip.write(raw.toByteArray()) } }.toByteArray()
        return pngContainer(width, height, compressed, tiff(orientation))
    }

    private fun solidPng(width: Int, height: Int): ByteArray {
        val row = ByteArray(1 + width * 4)
        for (x in 0 until width) {
            row[1 + 4 * x] = 23; row[2 + 4 * x] = 45; row[3 + 4 * x] = 67; row[4 + 4 * x] = 255.toByte()
        }
        val compressed = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { zip -> repeat(height) { zip.write(row) } } }.toByteArray()
        return pngContainer(width, height, compressed, null)
    }

    private fun pngContainer(width: Int, height: Int, compressed: ByteArray, exif: ByteArray?): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            bytes.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = ByteArrayOutputStream().also { DataOutputStream(it).use { data ->
                data.writeInt(width); data.writeInt(height); data.write(byteArrayOf(8, 6, 0, 0, 0))
            } }.toByteArray()
            chunk(bytes, "IHDR", header)
            if (exif != null) chunk(bytes, "eXIf", exif)
            chunk(bytes, "IDAT", compressed); chunk(bytes, "IEND", byteArrayOf())
        }.toByteArray()

    private fun chunk(output: ByteArrayOutputStream, type: String, payload: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val data = DataOutputStream(output)
        data.writeInt(payload.size); data.write(name); data.write(payload)
        val crc = CRC32().apply { update(name); update(payload) }
        data.writeInt(crc.value.toInt())
    }
}
