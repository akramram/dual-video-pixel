package com.akram.dualvideo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * A rear lens expressed as a zoom ratio of the logical back camera (1.0 = main lens);
 * [physicalId] is the physical camera the HAL reports in LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID
 * when that lens is in use.
 */
data class Lens(val ratio: Float, val label: String, val physicalId: String)

/** A concurrently openable back/front pair and the stream sizes chosen for each. */
data class CameraPlan(
    val backId: String,
    val frontId: String,
    val backSize: Size,
    val frontSize: Size,
    /** Rear lenses sorted by focal length; empty if the back camera is not a multi-lens logical camera. */
    val lenses: List<Lens>,
    /** Fixed frame rates (30, 60) that both cameras offer as an exact AE range, ascending. */
    val nativeFps: List<Int>,
) {
    /** Recording rates offered: every choice some native rate can serve (24 is a 30 fps stream with dropped frames). */
    val fpsOptions: List<Int> = DualConfig.FPS_CHOICES.filter { f -> nativeFps.any { it >= f } }

    /** The stream rate used to record at [fps]: the lowest native rate that is not below it. */
    fun cameraFps(fps: Int): Int = nativeFps.first { it >= fps }

    fun specs(): List<SourceSpec> = listOf(
        SourceSpec(SourceType.MAIN, backSize, front = false),
        SourceSpec(SourceType.SUB, frontSize, front = true),
    )
}

/**
 * Xiaomi's DualVideoModuleBase keeps two independent Camera2 devices: the module's own device
 * (main, back 1X) and a second one from getSubCamera2Device() (front), each with its own capture
 * session whose only output is the compositor's SurfaceTexture.
 *
 * Deviation: Xiaomi picks vendor session operation modes (32784 / 32772 / VIDEO_BEAUTY); a Pixel
 * has no such modes, so plain regular sessions on a concurrent-camera pair are used.
 *
 * Rear zoom/lens is not in the analysed Xiaomi code path; it sets CONTROL_ZOOM_RATIO on the
 * logical back camera. On the Pixel 6 Pro with the front camera open the HAL reaches the
 * ultra-wide (physical 3) and main lens (physical 2) but never the telephoto (physical 4): ratios
 * 3x to 10x stay on the main lens as a digital crop, and binding a stream to a physical camera is
 * rejected ("Concurrent streaming 3p logical rear camera app with physical stream is
 * unsupported"). With only the back camera open the telephoto does engage at high zoom, so
 * [setTele] closes the front camera and reopens the back camera alone.
 */
class DualCamera(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("dual-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var plan: CameraPlan? = null
    private var surfaces: Map<SourceType, Surface> = emptyMap()
    private var onError: (String) -> Unit = {}
    @Volatile private var fps = 30

    private var back: CameraDevice? = null
    private var front: CameraDevice? = null
    private var backSession: CameraCaptureSession? = null
    private var frontSession: CameraCaptureSession? = null
    private var backBuilder: CaptureRequest.Builder? = null
    @Volatile private var backZoom = 1f

    /** Physical camera the back stream is bound to in telephoto mode; null = logical camera (HAL picks the lens). */
    @Volatile private var teleId: String? = null
    private val zoomPosted = AtomicBoolean(false)
    private var lastPhysicalId: String? = null

    /** Called (on the camera thread) when the rear camera's active physical lens changes. */
    @Volatile var onActivePhysical: ((String?) -> Unit)? = null

    private val backCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val id = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            if (id != lastPhysicalId) {
                lastPhysicalId = id
                Log.d(TAG, "active rear physical camera: $id")
                onActivePhysical?.invoke(id)
            }
        }
    }

    fun plan(): CameraPlan {
        for (set in manager.concurrentCameraIds) {
            val chars = set.associateWith { manager.getCameraCharacteristics(it) }
            val backId = chars.filter { it.value.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                .keys.minOrNull()
            val frontId = chars.filter { it.value.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
                .keys.minOrNull()
            if (backId == null || frontId == null) continue
            val b = chars.getValue(backId)
            val f = chars.getValue(frontId)
            check(
                listOf(b, f).all {
                    it.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                },
            ) { "camera timestamps are not REALTIME; recording clock rebase unsupported" }
            return CameraPlan(
                backId, frontId,
                chooseSize(b, DualConfig.BACK_PREVIEW),
                chooseSize(f, DualConfig.FRONT_PREVIEW),
                lensesOf(b),
                listOf(30, 60).filter { hasFixedFps(b, it) && hasFixedFps(f, it) },
            )
        }
        error("no concurrent back+front camera pair on this device")
    }

    fun open(plan: CameraPlan, surfaces: Map<SourceType, Surface>, fps: Int, onReady: () -> Unit, onError: (String) -> Unit) {
        this.plan = plan
        this.surfaces = surfaces
        this.fps = fps
        this.onError = onError
        openDevices(withFront = true, onReady)
    }

    /**
     * Switches between the dual setup (back + front) and telephoto mode (back only, at [zoom]).
     * Both cameras are closed and the needed ones reopened onto the same surfaces, so the stream
     * pauses for about a second; [onDone] runs once every opened camera is streaming again.
     */
    fun setTele(tele: Boolean, zoom: Float, onDone: () -> Unit) {
        handler.post {
            teleId = if (tele) plan?.lenses?.lastOrNull()?.physicalId else null
            Log.d(TAG, "tele=$tele teleId=$teleId")
            backZoom = zoom
            closeDevices()
            openDevices(withFront = !tele, onDone)
        }
    }

    /** Zooms the rear camera to [ratio]; takes effect on the next frame, no session restart. Safe to call at touch rate. */
    fun setBackZoom(ratio: Float) {
        backZoom = ratio
        if (zoomPosted.getAndSet(true)) return
        handler.post {
            zoomPosted.set(false)
            val b = backBuilder ?: return@post
            val s = backSession ?: return@post
            runCatching {
                applyZoom(b)
                s.setRepeatingRequest(b.build(), backCallback, handler)
            }.onFailure { Log.w(TAG, "zoom failed", it) }
        }
    }

    fun close() {
        closeDevices()
        thread.quitSafely()
    }

    private fun closeDevices() {
        runCatching { backSession?.close() }
        runCatching { frontSession?.close() }
        runCatching { back?.close() }
        runCatching { front?.close() }
        backSession = null; frontSession = null; back = null; front = null; backBuilder = null
        lastPhysicalId = null
    }

    @SuppressLint("MissingPermission")
    private fun openDevices(withFront: Boolean, onReady: () -> Unit) {
        val plan = plan ?: return
        var remaining = if (withFront) 2 else 1
        val oneReady = {
            val left = synchronized(this) { --remaining }
            if (left == 0) onReady()
        }
        val fail = { msg: String -> onError(msg) }
        openOne(
            plan.backId, surfaces.getValue(SourceType.MAIN), teleId, { d, s -> back = d; backSession = s },
            { backBuilder = it; applyZoom(it) }, backCallback, oneReady, fail,
        )
        if (withFront) {
            openOne(plan.frontId, surfaces.getValue(SourceType.SUB), null, { d, s -> front = d; frontSession = s }, {}, null, oneReady, fail)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openOne(
        id: String,
        surface: Surface,
        physicalId: String?,
        store: (CameraDevice, CameraCaptureSession?) -> Unit,
        tune: (CaptureRequest.Builder) -> Unit,
        callback: CameraCaptureSession.CaptureCallback?,
        ready: () -> Unit,
        fail: (String) -> Unit,
    ) {
        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                store(camera, null)
                createSession(camera, id, surface, physicalId, tune, { store(camera, it) }, callback, ready, fail)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                fail("camera $id disconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                fail("camera $id error $error")
            }
        })
    }

    /** Creates a session streaming [surface]; [tune] adjusts the repeating request. */
    private fun createSession(
        camera: CameraDevice,
        id: String,
        surface: Surface,
        physicalId: String?,
        tune: (CaptureRequest.Builder) -> Unit,
        store: (CameraCaptureSession) -> Unit,
        callback: CameraCaptureSession.CaptureCallback?,
        ready: () -> Unit,
        fail: (String) -> Unit,
    ) {
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface).apply { if (physicalId != null) setPhysicalCameraId(physicalId) }),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    store(session)
                    try {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            tune(this)
                        }
                        session.setRepeatingRequest(builder.build(), callback, handler)
                        ready()
                    } catch (e: Exception) {
                        fail("camera $id request failed: $e")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) = fail("camera $id session configure failed")
            },
        )
        try {
            camera.createCaptureSession(cfg)
        } catch (e: Exception) {
            fail("camera $id createCaptureSession failed: $e")
        }
    }

    /**
     * Zoom for the back stream via the logical camera's CONTROL_ZOOM_RATIO. In dual mode the HAL picks
     * the lens and never reaches the telephoto. In telephoto mode the stream is bound to the tele
     * physical camera so it cannot fall back to the main lens; the HAL applies the logical ratio on top
     * of that lens's native view, so the ratio is divided by the tele lens's own ratio (1.0 = native).
     */
    private fun applyZoom(b: CaptureRequest.Builder) {
        val tele = teleId != null
        val native = if (tele) plan?.lenses?.lastOrNull()?.ratio ?: 1f else 1f
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, backZoom / native)
    }

    /**
     * Lens ratio = 35mm-equivalent focal length (focal length / sensor width) over the middle lens
     * (the main lens on a three-lens camera; the shorter one on two), clamped to the zoom range.
     */
    private fun lensesOf(c: CameraCharacteristics): List<Lens> {
        val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return emptyList()
        val lenses = c.physicalCameraIds.mapNotNull { id ->
            runCatching {
                val pc = manager.getCameraCharacteristics(id)
                val focal = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val width = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
                if (focal != null && width != null) id to focal / width else null
            }.getOrNull()
        }.sortedBy { it.second }
        if (lenses.size < 2) return emptyList()
        val main = lenses[(lenses.size - 1) / 2].second
        return lenses.map { (id, equiv) ->
            val ratio = (equiv / main).coerceIn(range.lower, range.upper)
            Lens(ratio, lensLabel(ratio), id)
        }
    }

    private fun lensLabel(r: Float): String {
        val shown = if (r < 1f) Math.round(r * 10) / 10f else if (r < 2f) Math.round(r * 2) / 2f else Math.round(r).toFloat()
        return if (shown == shown.toInt().toFloat()) "${shown.toInt()}×" else "$shown×"
    }

    private fun hasFixedFps(chars: CameraCharacteristics, fps: Int) =
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.any { it.lower == fps && it.upper == fps } == true

    /** Largest 16:9 SurfaceTexture size not above [target]; falls back to the closest area. */
    private fun chooseSize(chars: CameraCharacteristics, target: Size): Size {
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(SurfaceTexture::class.java)
        val fits = sizes.filter { it.width * 9 == it.height * 16 && it.width <= target.width && it.height <= target.height }
        val choice = fits.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { abs(it.width * it.height - target.width * target.height) }!!
        Log.d(TAG, "size for target $target -> $choice")
        return choice
    }

    companion object {
        private const val TAG = "DualCamera"
    }
}
