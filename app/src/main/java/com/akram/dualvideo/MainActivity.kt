package com.akram.dualvideo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity(), SurfaceHolder.Callback {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var recButton: Button
    private lateinit var pauseButton: Button
    private lateinit var typeButton: Button
    private lateinit var presetButton: Button

    private var compositor: GlCompositor? = null
    private var camera: DualCamera? = null
    private var recorders: MultiRecorderManager? = null
    private var running = false
    private var surfaceHolder: SurfaceHolder? = null

    private var scene = Scene()
    private var recordType = RecordType.MERGED

    private val timer = object : Runnable {
        override fun run() {
            val r = recorders
            if (r != null && r.isRecording) {
                val s = r.elapsedMs() / 1000
                status.text = "%s %02d:%02d".format(if (r.isPaused) "PAUSED" else "REC", s / 60, s % 60)
                ui.postDelayed(this, 250)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (!hasPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), REQ_PERMS)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView)
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            // Preview canvas has the recording aspect (1080x1920) so preview == recorded composition.
            val w = r - l
            val h = b - t
            var pw = w
            var ph = pw * DualConfig.OUTPUT.height / DualConfig.OUTPUT.width
            if (ph > h) {
                ph = h
                pw = ph * DualConfig.OUTPUT.width / DualConfig.OUTPUT.height
            }
            val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
            if (lp.width != pw || lp.height != ph) {
                lp.width = pw
                lp.height = ph
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                surfaceView.layoutParams = lp
            }
        }
        surfaceView.setOnTouchListener(::onPreviewTouch)

        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; text = "starting" }
        presetButton = button("Split") { cyclePreset() }
        val swap = button("Swap") { updateScene(scene.copy(swapped = !scene.swapped)) }
        typeButton = button("Merged") { toggleType() }
        pauseButton = button("Pause") { togglePause() }.apply { isEnabled = false }
        recButton = button("REC") { toggleRecord() }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(presetButton, swap, typeButton, pauseButton, recButton).forEach {
            row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(16, 16, 16, 24)
            addView(status)
            addView(row)
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun hasPermissions() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        if (hasPermissions()) surfaceHolder?.let { startPipeline(it) } else status.text = "camera + microphone permission required"
    }

    // ---- surface lifecycle ----------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceHolder = holder
        if (!hasPermissions()) return
        val c = compositor
        if (running && c != null) {
            worker.execute { runCatching { c.setPreviewSurface(holder.surface) } }
        } else if (!running) {
            startPipeline(holder)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceHolder = null
        stopPipeline()
    }

    // ---- pipeline --------------------------------------------------------------------------

    private fun startPipeline(holder: SurfaceHolder) {
        if (running) return
        running = true
        val gl = GlCompositor().also { it.scene = scene }
        val cam = DualCamera(this)
        compositor = gl
        camera = cam
        recorders = MultiRecorderManager(applicationContext)
        val surface = holder.surface
        worker.execute {
            try {
                val plan = cam.plan()
                val sources = gl.start(surface, plan.specs())
                cam.open(
                    plan, sources,
                    onReady = { ui.post { status.text = "back ${plan.backSize} / front ${plan.frontSize}" } },
                    onError = { msg -> Log.e(TAG, msg); ui.post { status.text = msg } },
                )
            } catch (t: Throwable) {
                Log.e(TAG, "pipeline start failed", t)
                ui.post { status.text = "start failed: ${t.message}" }
            }
        }
    }

    private fun stopPipeline() {
        if (!running) return
        running = false
        val gl = compositor
        val cam = camera
        val rec = recorders
        compositor = null
        camera = null
        recorders = null
        ui.removeCallbacks(timer)
        setRecordingUi(false)
        worker.execute {
            if (rec != null && rec.isRecording) {
                runCatching { gl?.stopRecording() }
                rec.stop()
            }
            cam?.close()
            gl?.release()
        }
    }

    // ---- controls --------------------------------------------------------------------------

    private fun updateScene(s: Scene) {
        scene = s
        compositor?.scene = s
    }

    private fun cyclePreset() {
        val next = ScenePreset.entries[(scene.preset.ordinal + 1) % ScenePreset.entries.size]
        presetButton.text = next.name.lowercase().replaceFirstChar { it.uppercase() }
        updateScene(scene.copy(preset = next))
    }

    private fun toggleType() {
        if (recorders?.isRecording == true) return
        recordType = if (recordType == RecordType.MERGED) RecordType.STANDALONE else RecordType.MERGED
        typeButton.text = if (recordType == RecordType.MERGED) "Merged" else "Separate"
    }

    private fun toggleRecord() {
        val rec = recorders ?: return
        val gl = compositor ?: return
        recButton.isEnabled = false
        if (!rec.isRecording) {
            val type = recordType
            worker.execute {
                try {
                    val targets = rec.start(type, 0)
                    gl.startRecording(targets)
                    ui.post { setRecordingUi(true); ui.post(timer) }
                } catch (t: Throwable) {
                    Log.e(TAG, "record start failed", t)
                    ui.post { status.text = "record failed: ${t.message}"; recButton.isEnabled = true }
                }
            }
        } else {
            worker.execute {
                val ok = runCatching { gl.stopRecording() }.isSuccess && rec.stop()
                ui.post {
                    setRecordingUi(false)
                    status.text = if (ok) "saved to Movies/DualVideo" else "recording failed"
                    Toast.makeText(this, status.text, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun togglePause() {
        val rec = recorders ?: return
        val gl = compositor ?: return
        worker.execute {
            if (rec.isPaused) {
                rec.resume()
                gl.setPaused(false)
            } else {
                gl.setPaused(true)
                rec.pause()
            }
            ui.post { pauseButton.text = if (rec.isPaused) "Resume" else "Pause" }
        }
    }

    private fun setRecordingUi(recording: Boolean) {
        recButton.text = if (recording) "STOP" else "REC"
        recButton.isEnabled = true
        pauseButton.isEnabled = recording
        pauseButton.text = "Pause"
        typeButton.isEnabled = !recording
    }

    // ---- mini window drag (RegionHelper / PosData.updateMiniPos) ---------------------------

    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private fun onPreviewTouch(v: View, e: MotionEvent): Boolean {
        if (scene.preset != ScenePreset.PIP) return false
        val w = v.width
        val h = v.height
        val mw = (w * DualConfig.MINI_SCALE).toInt()
        val mh = (h * DualConfig.MINI_SCALE).toInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = SceneLayout.rectFor(LayoutType.MINI, w, h, scene.miniX, scene.miniY)
                dragging = r.contains(e.x.toInt(), e.y.toInt())
                downX = e.x
                downY = e.y
                moved = false
                return dragging
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                if (abs(e.x - downX) > 12 || abs(e.y - downY) > 12) moved = true
                if (moved) {
                    val nx = ((e.x - mw / 2f) / (w - mw)).coerceIn(0f, 1f)
                    val ny = ((e.y - mh / 2f) / (h - mh)).coerceIn(0f, 1f)
                    updateScene(scene.copy(miniX = nx, miniY = ny))
                }
                return true
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                if (!moved) updateScene(scene.copy(swapped = !scene.swapped))
                return true
            }
        }
        return dragging
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    companion object {
        private const val TAG = "DualVideo"
        private const val REQ_PERMS = 1
    }
}
