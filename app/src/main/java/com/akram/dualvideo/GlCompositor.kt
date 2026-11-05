package com.akram.dualvideo

import android.graphics.Rect
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
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class SourceSpec(
    val type: SourceType,
    val bufferSize: Size,
    val front: Boolean,
)

/** A recorder input surface and what to draw into it: a composite (null) or one full-frame source. */
data class RecordTarget(val surface: Surface, val source: SourceType?)

/**
 * GL compositor reproducing Xiaomi's RenderManager pipeline: every camera streams into its own
 * SurfaceTexture (RenderSource / ExtTexture), the main source's frame drives a render pass, and the
 * pass is drawn once for the preview and once per recorder surface (RenderManager.drawForRecording:
 * one recorder gets the composed item list, two recorders each get their source full-frame).
 *
 * Deviation: Xiaomi gives each recorder its own RenderHandler thread and shared EGL context; this
 * uses one GL thread and one context with several window surfaces.
 */
class GlCompositor {
    private class Source(val spec: SourceSpec) {
        var texId = 0
        lateinit var texture: SurfaceTexture
        lateinit var surface: Surface
        val matrix = FloatArray(16)
        var hasFrame = false
        var timestampNs = 0L
    }

    private class RecordSurface(val egl: EGLSurface, val source: SourceType?)

    private val thread = HandlerThread("dual-gl").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile var scene: Scene = Scene()

    /**
     * False while the front camera is off (telephoto mode): the composite shows the back camera
     * full-frame. A separate front recorder (2 files) keeps receiving the front's last frame.
     */
    @Volatile var frontEnabled = true

    /** Extra centre crop (>= 1) applied to the back camera; used for telephoto zoom when the HAL has no per-physical zoom key. */
    @Volatile var mainCrop = 1f
    @Volatile private var recording = false
    @Volatile private var paused = false

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewW = 0
    private var previewH = 0
    private var current: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private var uTexture = 0

    private val sources = HashMap<SourceType, Source>()
    private var recordSurfaces: List<RecordSurface> = emptyList()

    /**
     * Camera frame timestamps are CLOCK_BOOTTIME (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) but a
     * MediaRecorder surface input expects CLOCK_MONOTONIC; this offset converts between them.
     */
    @Volatile private var bootToMonotonicNs = 0L

    private val quadPos: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTex: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val texMatrix = FloatArray(16)

    /**
     * Creates the EGL context, one SurfaceTexture per source and the preview window surface.
     * Returns the camera-facing Surface of each source.
     */
    fun start(preview: Surface, specs: List<SourceSpec>): Map<SourceType, Surface> = call {
        initEgl()
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        for (spec in specs) {
            val s = Source(spec)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            s.texId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            s.texture = SurfaceTexture(s.texId).apply {
                setDefaultBufferSize(spec.bufferSize.width, spec.bufferSize.height)
                setOnFrameAvailableListener({ onFrame(s) }, handler)
            }
            s.surface = Surface(s.texture)
            sources[spec.type] = s
        }
        attachPreview(preview)
        sources.mapValues { it.value.surface }
    }

    fun setPreviewSurface(preview: Surface?) = call {
        if (previewEgl != EGL14.EGL_NO_SURFACE) {
            destroySurface(previewEgl)
            previewEgl = EGL14.EGL_NO_SURFACE
        }
        if (preview != null) attachPreview(preview)
    }

    fun startRecording(targets: List<RecordTarget>) = call {
        recordSurfaces = targets.map { RecordSurface(createWindowSurface(it.surface), it.source) }
        bootToMonotonicNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        paused = false
        recording = true
    }

    fun setPaused(value: Boolean) {
        paused = value
    }

    fun stopRecording() = call {
        recording = false
        makeCurrent(pbuffer)
        recordSurfaces.forEach { destroySurface(it.egl) }
        recordSurfaces = emptyList()
    }

    fun release() {
        try {
            call {
                recording = false
                makeCurrent(pbuffer)
                recordSurfaces.forEach { destroySurface(it.egl) }
                recordSurfaces = emptyList()
                if (previewEgl != EGL14.EGL_NO_SURFACE) destroySurface(previewEgl)
                previewEgl = EGL14.EGL_NO_SURFACE
                for (s in sources.values) {
                    s.texture.setOnFrameAvailableListener(null)
                    s.surface.release()
                    s.texture.release()
                    GLES20.glDeleteTextures(1, intArrayOf(s.texId), 0)
                }
                sources.clear()
                if (program != 0) GLES20.glDeleteProgram(program)
                program = 0
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
                context = EGL14.EGL_NO_CONTEXT
            }
        } finally {
            thread.quitSafely()
        }
    }

    private fun onFrame(s: Source) {
        if (display == EGL14.EGL_NO_DISPLAY) return
        try {
            if (current == EGL14.EGL_NO_SURFACE) makeCurrent(pbuffer)
            s.texture.updateTexImage()
            s.texture.getTransformMatrix(s.matrix)
            s.timestampNs = s.texture.timestamp
            s.hasFrame = true
            if (s.spec.type == SourceType.MAIN) render()
        } catch (e: RuntimeException) {
            Log.w(TAG, "frame dropped", e)
        }
    }

    private fun render() {
        val scene = scene
        if (recording && !paused) {
            val ts = (sources[SourceType.MAIN]?.timestampNs ?: 0L) - bootToMonotonicNs
            val w = DualConfig.OUTPUT.width
            val h = DualConfig.OUTPUT.height
            for (rs in recordSurfaces) {
                makeCurrent(rs.egl)
                val entries = if (rs.source == null) composite(scene, w, h)
                else listOfNotNull(sources[rs.source]?.takeIf { it.hasFrame }?.let { it to Rect(0, 0, w, h) })
                drawScene(w, h, entries)
                EGLExt.eglPresentationTimeANDROID(display, rs.egl, ts)
                EGL14.eglSwapBuffers(display, rs.egl)
            }
        }
        if (previewEgl != EGL14.EGL_NO_SURFACE) {
            makeCurrent(previewEgl)
            drawScene(previewW, previewH, composite(scene, previewW, previewH))
            EGL14.eglSwapBuffers(display, previewEgl)
        }
    }

    private fun composite(scene: Scene, w: Int, h: Int): List<Pair<Source, Rect>> {
        if (!frontEnabled) {
            val main = sources[SourceType.MAIN]?.takeIf { it.hasFrame } ?: return emptyList()
            return listOf(main to Rect(0, 0, w, h))
        }
        return compositeDual(scene, w, h)
    }

    private fun compositeDual(scene: Scene, w: Int, h: Int): List<Pair<Source, Rect>> =
        SceneLayout.drawOrder(scene).mapNotNull { type ->
            val s = sources[type]?.takeIf { it.hasFrame } ?: return@mapNotNull null
            s to SceneLayout.rectFor(scene.layoutOf(type), w, h, scene.miniX, scene.miniY)
        }

    private fun drawScene(w: Int, h: Int, entries: List<Pair<Source, Rect>>) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        for ((s, r) in entries) drawSource(s, r, h)
    }

    private fun drawSource(s: Source, r: Rect, canvasH: Int) {
        GLES20.glViewport(r.left, canvasH - r.bottom, r.width(), r.height())
        computeTexMatrix(s, r.width().toFloat() / r.height())
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.texId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    /**
     * texCoord = ST * T(.5) * S(aspect crop) * T(-.5) * uv, where ST is the SurfaceTexture
     * transform. On this device ST already carries the sensor rotation (verified on a Pixel 6 Pro:
     * no extra rotation is needed), so the buffer is treated as upright and centre-cropped to fill
     * the destination rectangle.
     */
    private fun computeTexMatrix(s: Source, destAspect: Float) {
        val buf = s.spec.bufferSize
        val rotated = abs(s.matrix[1]) > abs(s.matrix[0])
        val upW = if (rotated) buf.height else buf.width
        val upH = if (rotated) buf.width else buf.height
        val srcAspect = upW.toFloat() / upH
        var cx = 1f
        var cy = 1f
        if (destAspect > srcAspect) cy = srcAspect / destAspect else cx = destAspect / srcAspect
        if (DualConfig.MIRROR_FRONT && s.spec.front) cx = -cx
        if (s.spec.type == SourceType.MAIN) {
            cx /= mainCrop
            cy /= mainCrop
        }
        System.arraycopy(s.matrix, 0, texMatrix, 0, 16)
        Matrix.translateM(texMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.scaleM(texMatrix, 0, cx, cy, 1f)
        Matrix.translateM(texMatrix, 0, -0.5f, -0.5f, 0f)
    }

    private fun attachPreview(preview: Surface) {
        previewEgl = createWindowSurface(preview)
        val out = IntArray(1)
        EGL14.eglQuerySurface(display, previewEgl, EGL14.EGL_WIDTH, out, 0)
        previewW = out[0]
        EGL14.eglQuerySurface(display, previewEgl, EGL14.EGL_HEIGHT, out, 0)
        previewH = out[0]
    }

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        makeCurrent(pbuffer)
    }

    private fun createWindowSurface(surface: Surface): EGLSurface {
        val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(egl != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        return egl
    }

    private fun destroySurface(egl: EGLSurface) {
        if (current == egl) makeCurrent(pbuffer)
        EGL14.eglDestroySurface(display, egl)
    }

    private fun makeCurrent(surface: EGLSurface) {
        if (current == surface) return
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
        current = surface
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "compile failed: ${GLES20.glGetShaderInfoLog(sh)}" }
        return sh
    }

    private fun <T> call(block: () -> T): T {
        val f = CompletableFuture<T>()
        handler.post {
            try {
                f.complete(block())
            } catch (t: Throwable) {
                f.completeExceptionally(t)
            }
        }
        try {
            return f.get(10, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun floatBuffer(vararg v: Float): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(v)
            position(0)
        }

    companion object {
        private const val TAG = "GlCompositor"

        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTex);
            }
        """
    }
}
