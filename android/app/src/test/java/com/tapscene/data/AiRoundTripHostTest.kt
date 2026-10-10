package com.tapscene.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNativeBitmap

/** Host native Android PNG/SQLite execution. Synthetic confirmations are not human privacy review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AiRoundTripHostTest {
    @After fun closeHostDescriptors() = HostFileSyncShadow.reset()

    @Test fun completeStaticPackageReturnsAsReviewedRelease() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            check(Build.VERSION.SDK_INT == 35)
            val app = RuntimeEnvironment.getApplication()
            HostFileSyncShadow.begin(app.noBackupFilesDir)
            verifyHostFileSync(app.noBackupFilesDir)
            HostFileSyncShadow.beginProductionEvidence()
            HostSqlite.configure()
            // Fail closed if this ever regresses to legacy/default bitmap behavior.
            val pixels = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.BLACK, Color.WHITE, Color.rgb(37, 81, 129))
            val bitmap = Bitmap.createBitmap(pixels, 3, 2, Bitmap.Config.ARGB_8888)
            try {
                check(Shadow.extract<Any>(bitmap) is ShadowNativeBitmap)
                val bytes = ByteArrayOutputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray()
                }
                check(bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
                val decoded = checkNotNull(BitmapFactory.decodeStream(bytes.inputStream()))
                try {
                    check(Shadow.extract<Any>(decoded) is ShadowNativeBitmap)
                    check(decoded.width == 3 && decoded.height == 2)
                    val actual = IntArray(pixels.size)
                    decoded.getPixels(actual, 0, 3, 0, 0, 3, 2)
                    check(actual.contentEquals(pixels)) { "Native PNG round trip changed actual pixels" }
                } finally { decoded.recycle() }
            } finally { bitmap.recycle() }
            val output = System.getenv("TAPSCENE_AI_ROUNDTRIP_OUTPUT")?.let(::File)
                ?: File(app.cacheDir, "ai-round-trip-evidence")
            check(!output.exists() && output.mkdirs()) { "Round-trip evidence destination must be new" }
            AiDraftImportChecks.roundTrip(app, output, ::println)
            HostFileSyncShadow.assertProductionEvidence()
            println("HOST_AI_ROUNDTRIP api=35 graphics=NATIVE sqlite=NATIVE production=prepare/preview/commit/review/seal/export javaReader=PASS")
            println("HOST_AI_ROUNDTRIP_LIMIT synthetic review only; device UI, human privacy judgment and video decode NOT_RUN")
        }
    }

    private fun verifyHostFileSync(root: File) {
        val directory = File(root, "fd-probe").apply { check(mkdir()) }
        val file = File(directory, "regular").apply { writeText("Synthetic FD probe") }
        for ((path, isDirectory) in listOf(directory to true, file to false)) {
            val fd = Os.open(path.path, OsConstants.O_RDONLY, 0)
            try {
                val stat = Os.fstat(fd)
                check(OsConstants.S_ISDIR(stat.st_mode) == isDirectory)
                check(OsConstants.S_ISREG(stat.st_mode) != isDirectory)
                if (!isDirectory) {
                    check(stat.st_size == file.length())
                    check(file.delete()) // fstat must still inspect the held inode after unlink.
                    check(Os.fstat(fd).st_ino == stat.st_ino && Os.fstat(fd).st_size == stat.st_size)
                }
                Os.fsync(fd)
            } finally { Os.close(fd) }
            check(!fd.valid())
            check((runCatching { Os.fstat(fd) }.exceptionOrNull() as? ErrnoException)?.errno == OsConstants.EBADF)
            check((runCatching { Os.fsync(fd) }.exceptionOrNull() as? ErrnoException)?.errno == OsConstants.EBADF)
            check((runCatching { fd.sync() }.exceptionOrNull()) is java.io.SyncFailedException)
        }
        val missing = runCatching { Os.open(File(directory, "missing").path, OsConstants.O_RDONLY, 0) }.exceptionOrNull()
        check((missing as? ErrnoException)?.errno == OsConstants.ENOENT)
        check(directory.delete())
    }
}
