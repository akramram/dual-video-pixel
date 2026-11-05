package com.akram.dualvideo

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/** A concurrently openable back/front pair and the stream sizes chosen for each. */
data class CameraPlan(
    val backId: String,
    val frontId: String,
    val backSize: Size,
    val frontSize: Size,
) {
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
 */
class DualCamera(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("dual-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var back: CameraDevice? = null
    private var front: CameraDevice? = null
    private var backSession: CameraCaptureSession? = null
    private var frontSession: CameraCaptureSession? = null

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
            )
        }
        error("no concurrent back+front camera pair on this device")
    }

    @SuppressLint("MissingPermission")
    fun open(plan: CameraPlan, surfaces: Map<SourceType, Surface>, onReady: () -> Unit, onError: (String) -> Unit) {
        var remaining = 2
        val fail = { msg: String -> onError(msg) }
        val oneReady = {
            val left = synchronized(this) { --remaining }
            if (left == 0) onReady()
        }
        openOne(plan.backId, surfaces.getValue(SourceType.MAIN), { d, s -> back = d; backSession = s }, oneReady, fail)
        openOne(plan.frontId, surfaces.getValue(SourceType.SUB), { d, s -> front = d; frontSession = s }, oneReady, fail)
    }

    @SuppressLint("MissingPermission")
    private fun openOne(
        id: String,
        surface: Surface,
        store: (CameraDevice, CameraCaptureSession?) -> Unit,
        ready: () -> Unit,
        fail: (String) -> Unit,
    ) {
        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                store(camera, null)
                val cfg = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            store(camera, session)
                            try {
                                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(surface)
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(DualConfig.FPS, DualConfig.FPS))
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                }.build()
                                session.setRepeatingRequest(req, null, handler)
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

    fun close() {
        runCatching { backSession?.close() }
        runCatching { frontSession?.close() }
        runCatching { back?.close() }
        runCatching { front?.close() }
        backSession = null; frontSession = null; back = null; front = null
        thread.quitSafely()
    }

    /** Largest 16:9 SurfaceTexture size not above [target]; falls back to the closest area. */
    private fun chooseSize(chars: CameraCharacteristics, target: Size): Size {
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(android.graphics.SurfaceTexture::class.java)
        val fits = sizes.filter { it.width * 9 == it.height * 16 && it.width <= target.width && it.height <= target.height }
        val choice = fits.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { kotlin.math.abs(it.width * it.height - target.width * target.height) }!!
        Log.d(TAG, "size for target $target -> $choice")
        return choice
    }

    companion object {
        private const val TAG = "DualCamera"
    }
}
