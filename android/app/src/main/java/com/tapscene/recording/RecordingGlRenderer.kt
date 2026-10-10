package com.tapscene.recording

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** All GL calls run on the recording worker. The slot lock is never held during a GL/driver call. */
internal class RecordingGlRenderer(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val codecSurface: Surface,
    private val handler: Handler,
    private val onFrame: () -> Unit,
) {
    internal class Slot(val texture: Int, val framebuffer: Int)
    internal class PinnedSlot(val slot: Slot, val lease: FrameLeasePool.Read<VideoSourceObservation>) {
        val frame: VideoSourceObservation get() = lease.frame
    }
    private val pool = FrameLeasePool<VideoSourceObservation>()
    val slotLock: Any get() = pool.lock
    private val slots = ArrayList<Slot>()
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesTexture = 0
    private var oesProgram = 0
    private var flatProgram = 0
    private var sourceTexture: SurfaceTexture? = null
    lateinit var captureSurface: Surface
        private set
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val sourceTransform = FloatArray(16)
    private val vertices = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    // GLSL clip-space output is fit/center, while SurfaceTexture's matrix normalizes sampling.
    private val fitMatrix = identity.copyOf().also {
        val scale = minOf(frameWidth.toDouble() / displayWidth, frameHeight.toDouble() / displayHeight)
        it[0] = (displayWidth * scale / frameWidth).toFloat()
        it[5] = (displayHeight * scale / frameHeight).toFloat()
    }

    fun prepare() {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY)
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            ), 0, configs, 0, 1, IntArray(1), 0))
            val config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreateWindowSurface(display, config, codecSurface, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            val maxSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
            check(maxOf(displayWidth, displayHeight, frameWidth, frameHeight) <= maxSize[0]) { "Capture exceeds GL texture limit" }
            oesTexture = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            sourceTexture = SurfaceTexture(oesTexture).also { source ->
                source.setDefaultBufferSize(displayWidth, displayHeight)
                source.setOnFrameAvailableListener({ onFrame() }, handler)
                captureSurface = Surface(source)
            }
            oesProgram = program("#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv; uniform samplerExternalOES uTexture; void main(){gl_FragColor=texture2D(uTexture,vUv);}")
            flatProgram = program("precision mediump float; varying vec2 vUv; uniform sampler2D uTexture; void main(){gl_FragColor=texture2D(uTexture,vUv);}")
            repeat(3) {
                val texture = texture(GLES20.GL_TEXTURE_2D)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameWidth, frameHeight,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                val id = IntArray(1)
                GLES20.glGenFramebuffers(1, id, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, texture, 0)
                check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
                slots += Slot(texture, id[0])
            }
            checkGl()
        } catch (failure: Throwable) {
            runCatching { release() }
            throw failure
        }
    }

    fun acquireSourceTimestamp(): Long {
        checkNotNull(sourceTexture).updateTexImage()
        checkNotNull(sourceTexture).getTransformMatrix(sourceTransform)
        check(sourceTransform.all { it.isFinite() }) { "Invalid source texture matrix" }
        return checkNotNull(sourceTexture).timestamp
    }

    fun drawObservation(frame: VideoSourceObservation) {
        val write = checkNotNull(pool.claim()) { "Frame leases exhausted" }
        val slot = slots[write.index]
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.framebuffer)
            GLES20.glViewport(0, 0, frameWidth, frameHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            draw(oesProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, sourceTransform, fitMatrix)
            checkGl()
            pool.publish(write, frame)
        } catch (failure: Throwable) {
            pool.abandon(write)
            throw failure
        }
    }

    /** Repeats present the exact normalized FBO, without latching or inventing a new observation. */
    fun submitPinned(slot: PinnedSlot, presentationPtsUs: Long) {
        check(pool.isPinned(slot.lease))
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, frameWidth, frameHeight)
        draw(flatProgram, GLES20.GL_TEXTURE_2D, slot.slot.texture, identity, identity)
        checkGl()
        check(EGLExt.eglPresentationTimeANDROID(display, surface, Math.multiplyExact(presentationPtsUs, 1_000L)))
        check(EGL14.eglSwapBuffers(display, surface)) { "Encoder surface submission failed" }
    }

    /** Caller may hold slotLock, so pinning remains atomic with the action boundary. */
    fun pinLatest(): PinnedSlot? = pool.pinLatest()?.let { PinnedSlot(slots[it.index], it) }
    fun releasePin(slot: PinnedSlot) = pool.release(slot.lease)

    /** Reads precisely the pinned normalized FBO. PNG uses top-left rows, never a later OES image. */
    fun readPinned(slot: PinnedSlot): Bitmap {
        check(pool.isPinned(slot.lease))
        val bytes = ByteBuffer.allocateDirect(Math.multiplyExact(frameWidth * frameHeight, 4)).order(ByteOrder.nativeOrder())
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.slot.framebuffer)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, frameWidth, frameHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        checkGl()
        val colors = IntArray(frameWidth * frameHeight)
        // Explicit channel and vertical normalization avoids host-endian RGBA/ARGB assumptions.
        for (y in 0 until frameHeight) for (x in 0 until frameWidth) {
            val offset = ((frameHeight - y - 1) * frameWidth + x) * 4
            val red = bytes.get(offset).toInt() and 255
            val green = bytes.get(offset + 1).toInt() and 255
            val blue = bytes.get(offset + 2).toInt() and 255
            colors[y * frameWidth + x] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(colors, frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
    }

    fun stopListening() { sourceTexture?.setOnFrameAvailableListener(null) }

    fun release() {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        attempt { stopListening() }
        attempt { if (::captureSurface.isInitialized) captureSurface.release() }
        attempt { sourceTexture?.release(); sourceTexture = null }
        if (context != EGL14.EGL_NO_CONTEXT) {
            attempt {
                slots.forEach { GLES20.glDeleteFramebuffers(1, intArrayOf(it.framebuffer), 0); GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0) }
                GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
                GLES20.glDeleteProgram(oesProgram); GLES20.glDeleteProgram(flatProgram)
            }
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            attempt { check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) }
            attempt { if (surface != EGL14.EGL_NO_SURFACE) check(EGL14.eglDestroySurface(display, surface)) }
            attempt { if (context != EGL14.EGL_NO_CONTEXT) check(EGL14.eglDestroyContext(display, context)) }
            attempt { check(EGL14.eglReleaseThread()) }
            attempt { check(EGL14.eglTerminate(display)) }
        }
        if (failure != null) throw checkNotNull(failure)
        surface = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT; display = EGL14.EGL_NO_DISPLAY
    }

    private fun texture(target: Int): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(target, id[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }
    private fun draw(program: Int, target: Int, texture: Int, matrix: FloatArray, fit: FloatArray) {
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aUv")
        vertices.position(0); uv.position(0)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, matrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uFit"), 1, false, fit, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(texCoord)
    }
    private fun program(fragment: String): Int {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aPosition; attribute vec4 aUv; uniform mat4 uTransform; uniform mat4 uFit; varying vec2 vUv; void main(){gl_Position=uFit*aPosition;vUv=(uTransform*aUv).xy;}")
        val frag = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        try {
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, frag); GLES20.glLinkProgram(program)
            val status = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) { GLES20.glDeleteProgram(program); error("GL program linking failed") }
            return program
        } finally { GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(frag) }
    }
    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
        val status = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) { GLES20.glDeleteShader(shader); error("GL shader compilation failed") }
        return shader
    }
    private fun checkGl() { check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GL capture failed" } }
    private fun floats(vararg values: Float): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    companion object { private const val EGL_RECORDABLE_ANDROID = 0x3142 }
}
