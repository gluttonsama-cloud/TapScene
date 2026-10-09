package com.tapscene.ocr

/** Minimal private JNI surface. No filenames, encoded images or custom config are accepted. */
internal object OcrNative {
    val available: Boolean = try {
        System.loadLibrary("tapscene_ocr")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    external fun createSignal(): Long
    external fun cancelSignal(signal: Long)
    external fun releaseSignal(signal: Long)
    external fun recognizeRgb(pixels: ByteArray, width: Int, height: Int, modelDir: String, signal: Long): ByteArray
}
