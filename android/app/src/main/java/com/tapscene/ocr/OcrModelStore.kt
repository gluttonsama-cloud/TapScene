package com.tapscene.ocr

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Only exact APK-bundled model bytes are copied; no external model import or network. */
internal class OcrModelStore(context: Context) {
    private val app = context.applicationContext
    private data class Model(val name: String, val bytes: Long, val digest: String)

    fun ensureReady(cancellation: OcrCancellation): File {
        try {
            val root = app.noBackupFilesDir.canonicalFile
            val directory = File(root, "ocr-models/ppocrv6-tiny-2ba1506c-2612ab37")
            if (!directory.isDirectory && !directory.mkdirs()) throw OcrException(OcrError.MODEL_UNAVAILABLE)
            if (directory.canonicalFile.path != directory.absolutePath || !directory.canonicalFile.toPath().startsWith(root.toPath())) {
                throw OcrException(OcrError.MODEL_UNAVAILABLE)
            }
            for (model in MODELS) {
                checkCancellation(cancellation)
                val target = File(directory, model.name)
                if (target.canonicalFile.parentFile != directory) throw OcrException(OcrError.MODEL_UNAVAILABLE)
                val temporary = File(directory, model.name + ".part")
                if (temporary.canonicalFile != temporary.absoluteFile) throw OcrException(OcrError.MODEL_UNAVAILABLE)
                // One exact owned partial per model; a killed process cannot accumulate copies.
                if (temporary.exists() && !temporary.delete()) throw OcrException(OcrError.MODEL_UNAVAILABLE)
                if (verified(target, model, cancellation)) continue
                try {
                    app.assets.open("ocr/${model.name}").use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var count = 0L
                            while (true) {
                                checkCancellation(cancellation)
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                if (count > model.bytes) throw OcrException(OcrError.MODEL_UNAVAILABLE)
                                output.write(buffer, 0, read)
                            }
                            output.fd.sync()
                        }
                    }
                    if (!verified(temporary, model, cancellation)) throw OcrException(OcrError.MODEL_UNAVAILABLE)
                    checkCancellation(cancellation)
                    Os.rename(temporary.absolutePath, target.absolutePath)
                } finally {
                    temporary.delete()
                }
                if (!verified(target, model, cancellation)) throw OcrException(OcrError.MODEL_UNAVAILABLE)
            }
            return directory
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: OcrException) {
            throw error
        } catch (_: Exception) {
            throw OcrException(OcrError.MODEL_UNAVAILABLE)
        }
    }

    private fun verified(file: File, model: Model, cancellation: OcrCancellation): Boolean {
        if (!file.isFile || file.length() != model.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkCancellation(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == model.digest
    }

    private fun checkCancellation(cancellation: OcrCancellation) {
        if (cancellation.isCancelled) throw CancellationException("OCR_CANCELLED")
    }

    private companion object {
        val MODELS = listOf(
            Model("det.onnx", 1780590, "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8"),
            Model("rec.onnx", 4462639, "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6"),
            Model("characters.txt", 27158, "d95c0dcd7abe0d9ee9dbaeec5af4cb11711de2c4d37b3145cba529043d749898"),
        )
    }
}
