package com.kurulabs.dualvideopixel

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Camera-style screen: full-bleed 9:16 viewfinder, a bottom panel with the layout chips above a
 * thumbnail / shutter / swap row (as in Google Camera and Xiaomi Camera), and a small top overlay
 * that holds the "1 file / 2 file" switch while idle and the timer while recording.
 */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var surfaceView: SurfaceView
    private lateinit var topBar: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var typeToggle: SegmentedToggle
    private lateinit var timerPill: LinearLayout
    private lateinit var timerDot: View
    private lateinit var timerText: TextView
    private lateinit var messageBox: LinearLayout
    private lateinit var messageText: TextView
    private lateinit var messageAction: TextView
    private lateinit var chips: List<GlyphChip>
    private lateinit var shutter: ShutterView
    private lateinit var thumbnail: ThumbnailView
    private lateinit var pauseButton: GlyphButton
    private lateinit var swapButton: GlyphButton
    private lateinit var lensHolder: FrameLayout
    private var zoomSlider: ZoomSlider? = null
    private var teleChip: GlyphChip? = null
    private var teleStatus: TextView? = null
    private var teleMode = false
    private var teleSwitching = false
    private var mainMin = 0.7f
    private var mainMax = 4f
    private var teleMin = 4f
    private var mainZoom = 1f
    private var teleZoom = 4f
    private var teleLensId: String? = null
    private var activeId: String? = null

    private var compositor: GlCompositor? = null
    private var camera: DualCamera? = null
    private var recorders: MultiRecorderManager? = null
    private var running = false
    private var cameraReady = false
    private var busy = false
    private var surfaceHolder: SurfaceHolder? = null

    private var scene = Scene()
    private var recordType = RecordType.MERGED
    private var lastVideo: Uri? = null

    private lateinit var settingsChip: TextView
    private var settings = VideoSettings()
    private var activeSettings = VideoSettings()
    private var fpsOptions = listOf(30)

    private val timer = object : Runnable {
        override fun run() {
            val r = recorders ?: return
            if (!r.isRecording) return
            val s = r.elapsedMs() / 1000
            timerText.text = "%02d:%02d%s".format(s / 60, s % 60, if (r.isPaused) "  ·  Dijeda" else "")
            (timerDot.background as android.graphics.drawable.GradientDrawable).setColor(if (r.isPaused) Palette.WHITE else Palette.RED)
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        settings = loadSettings()
        activeSettings = settings
        buildUi()
        if (!hasPermissions()) requestPerms()
    }

    override fun onResume() {
        super.onResume()
        refreshThumbnail()
    }

    // ---- UI construction ----------------------------------------------------------------------

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener(::onPreviewTouch)
        root.addView(surfaceView)
        lensHolder = FrameLayout(this)
        root.addView(lensHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(100f), Gravity.TOP))

        // Top overlay: 1 file / 2 file switch when idle, timer while recording.
        typeToggle = SegmentedToggle(
            this, listOf(getString(R.string.record_one_file), getString(R.string.record_two_files)),
            listOf(getString(R.string.record_one_file_desc), getString(R.string.record_two_files_desc)),
        ) { recordType = if (it == 0) RecordType.MERGED else RecordType.STANDALONE }
        timerDot = View(this).apply { background = pill(Palette.RED, 5f, this@MainActivity) }
        timerText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD; text = "00:00" }
        timerPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(Palette.SCRIM, 22f, this@MainActivity)
            setPadding(dpi(14f), 0, dpi(16f), 0)
            visibility = View.INVISIBLE
            addView(timerDot, LinearLayout.LayoutParams(dpi(10f), dpi(10f)).apply { rightMargin = dpi(8f) })
            addView(timerText)
        }
        settingsChip = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = pill(Palette.SCRIM, 24f, this@MainActivity)
            setPadding(dpi(16f), 0, dpi(16f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { showSettingsDialog() }
        }
        topBar = FrameLayout(this).apply {
            addView(typeToggle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            addView(timerPill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(44f), Gravity.CENTER_HORIZONTAL))
            addView(settingsChip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(48f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dpi(16f) })
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Centre message: opening camera, errors, missing permission.
        messageText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            background = pill(Palette.SCRIM, 16f, this@MainActivity)
            setPadding(dpi(20f), dpi(12f), dpi(20f), dpi(12f))
        }
        messageAction = TextView(this).apply {
            setTextColor(Color.BLACK); textSize = 15f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = pill(Palette.WHITE, 24f, this@MainActivity)
            minHeight = dpi(48f)
            setPadding(dpi(24f), 0, dpi(24f), 0)
            isClickable = true
            isFocusable = true
        }
        messageBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(messageText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(messageAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })
        }
        root.addView(messageBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dpi(24f); rightMargin = dpi(24f)
        })

        // Bottom panel: layout chips, then thumbnail / shutter / swap.
        val presets = listOf(
            Triple(ScenePreset.SPLIT, Glyph.SPLIT, getString(R.string.layout_split)),
            Triple(ScenePreset.STACK, Glyph.STACK, getString(R.string.layout_stack)),
            Triple(ScenePreset.PIP, Glyph.PIP, getString(R.string.layout_pip)),
        )
        chips = presets.map { (preset, glyph, label) ->
            GlyphChip(this, glyph, label).also { chip ->
                chip.setOnClickListener { selectPreset(preset) }
            }
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            chips.forEachIndexed { i, c ->
                addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dpi(8f) })
            }
        }

        shutter = ShutterView(this).apply { isEnabled = false; setOnClickListener { toggleRecord() } }
        thumbnail = ThumbnailView(this).apply { setOnClickListener { openLastVideo() } }
        pauseButton = GlyphButton(this, Glyph.PAUSE, getString(R.string.pause)).apply { visibility = View.GONE; setOnClickListener { togglePause() } }
        swapButton = GlyphButton(this, Glyph.SWAP, getString(R.string.swap_cameras)).apply { setOnClickListener { updateScene(scene.copy(swapped = !scene.swapped)) } }

        fun slot(child: View) = FrameLayout(this).apply {
            addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val leftSlot = FrameLayout(this).apply {
            addView(thumbnail, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(pauseButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val shutterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(leftSlot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(shutter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(slot(swapButton), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(chipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(shutterRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
        }
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionLens() }

        chips[ScenePreset.entries.indexOf(scene.preset)].isSelected = true

        // The viewfinder has the recording aspect (1080x1920) so preview == recorded composition.
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            var pw = w
            var ph = pw * settings.resolution.output.height / settings.resolution.output.width
            if (ph > h) {
                ph = h
                pw = ph * settings.resolution.output.width / settings.resolution.output.height
            }
            val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
            if (lp.width != pw || lp.height != ph) {
                lp.width = pw
                lp.height = ph
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                positionLens()
                surfaceView.layoutParams = lp
            }
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            topBar.setPadding(dpi(16f), bars.top + dpi(8f), dpi(16f), 0)
            panel.setPadding(dpi(24f), dpi(12f), dpi(24f), bars.bottom + dpi(12f))
            insets
        }
        setContentView(root)
    }

    // ---- messages & permissions -----------------------------------------------------------------

    /** Keeps the lens bar 12dp above whichever ends first: the viewfinder or the top of the bottom panel. */
    private fun positionLens() {
        val bottom = minOf(surfaceView.layoutParams.height, panel.top.takeIf { it > 0 } ?: Int.MAX_VALUE)
        val lp = lensHolder.layoutParams as FrameLayout.LayoutParams
        val top = bottom - dpi(112f)
        if (lp.topMargin != top) {
            lp.topMargin = top
            lensHolder.layoutParams = lp
        }
    }

    /**
     * Rear zoom controls over the bottom of the viewfinder. The slider always drives the main
     * (ultra-wide to main) camera. The "Tele" button turns the front camera off and gives the
     * telephoto its own slider; pressing it again brings the front camera back.
     */
    private fun showLenses(lenses: List<Lens>, cam: DualCamera) {
        lensHolder.removeAllViews()
        teleMode = false
        teleSwitching = false
        compositor?.frontEnabled = true
        cam.onActivePhysical = null
        teleChip = null
        teleStatus = null
        zoomSlider = null
        if (lenses.size < 2) return
        val hasTele = lenses.size >= 3
        val tele = lenses.last()
        mainMin = lenses.first().ratio
        mainMax = tele.ratio
        teleMin = tele.ratio
        mainZoom = 1f
        teleZoom = tele.ratio
        teleLensId = tele.physicalId

        val slider = ZoomSlider(this) { z ->
            // In tele mode the stream is the tele lens itself and the ratio is the logical zoom (min = tele native).
            if (teleMode) teleZoom = z else mainZoom = z
            cam.setBackZoom(z)
        }
        zoomSlider = slider
        applyZoomUi()

        val status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            background = pill(Palette.SCRIM, 14f, this@MainActivity)
            setPadding(dpi(12f), dpi(6f), dpi(12f), dpi(6f))
            visibility = View.GONE
        }
        teleStatus = status

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(slider)
            if (hasTele) {
                val chip = GlyphChip(this@MainActivity, Glyph.TELE, getString(R.string.tele)).apply {
                    contentDescription = getString(R.string.tele_desc)
                    setOnClickListener { toggleTele(cam) }
                }
                teleChip = chip
                addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(8f) })
            }
        }
        if (hasTele) {
            cam.onActivePhysical = { id -> ui.post { activeId = id; refreshTeleStatus() } }
        }
        lensHolder.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        lensHolder.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(56f) })
    }

    private fun applyZoomUi() {
        val s = zoomSlider ?: return
        if (teleMode) s.configure(teleMin, TELE_MAX, teleZoom, listOf(6f, 8f), getString(R.string.zoom_tele))
        else s.configure(mainMin, mainMax, mainZoom, listOf(1f, 2f), getString(R.string.zoom_main))
    }

    /** Shows whether the telephoto is really in use; below the HAL's switch point (or in low light) the picture is a crop of the main lens. */
    private fun refreshTeleStatus() {
        val status = teleStatus ?: return
        if (!teleMode || teleSwitching) return
        status.text = if (activeId == teleLensId) getString(R.string.tele_active) else getString(R.string.tele_inactive, activeId)
        status.visibility = View.VISIBLE
    }

    private fun toggleTele(cam: DualCamera) {
        if (teleSwitching) return
        val slider = zoomSlider ?: return
        val chip = teleChip ?: return
        val status = teleStatus ?: return
        val enter = !teleMode
        teleSwitching = true
        chip.isClickable = false
        slider.isEnabled = false
        status.text = getString(if (enter) R.string.front_turning_off else R.string.front_turning_on)
        status.visibility = View.VISIBLE
        if (enter) {
            compositor?.frontEnabled = false
        }
        cam.setTele(enter, if (enter) teleZoom else mainZoom) {
            ui.post {
                teleMode = enter
                teleSwitching = false
                if (!enter) {
                    compositor?.frontEnabled = true
                }
                chip.isSelected = enter
                chip.isClickable = true
                slider.isEnabled = true
                applyZoomUi()
                if (enter) refreshTeleStatus() else status.visibility = View.GONE
            }
        }
    }

    private fun showMessage(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        messageText.text = text
        if (actionLabel != null && action != null) {
            messageAction.text = actionLabel
            messageAction.setOnClickListener { action() }
            messageAction.visibility = View.VISIBLE
        } else {
            messageAction.visibility = View.GONE
        }
        messageBox.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        messageBox.visibility = View.GONE
    }

    private fun hasPermissions() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), REQ_PERMS)
    }

    private fun showPermissionNeeded() {
        val canAsk = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        showMessage(
            getString(R.string.permission_needed),
            getString(if (canAsk) R.string.grant_permission else R.string.open_settings),
        ) {
            if (canAsk) requestPerms()
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        if (hasPermissions()) surfaceHolder?.let { startPipeline(it) } else showPermissionNeeded()
    }

    // ---- surface lifecycle ------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceHolder = holder
        if (!hasPermissions()) {
            if (!running) showPermissionNeeded()
            return
        }
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

    // ---- pipeline ------------------------------------------------------------------------------------

    private fun startPipeline(holder: SurfaceHolder) {
        if (running) return
        running = true
        cameraReady = false
        shutter.isEnabled = false
        showMessage(getString(R.string.opening_camera))
        val gl = GlCompositor(settings.resolution.output).also { it.scene = scene }
        val cam = DualCamera(this)
        compositor = gl
        camera = cam
        recorders = null
        val surface = holder.surface
        worker.execute {
            try {
                val plan = cam.plan()
                // The saved fps may not exist on this device pair; fall back to 30, then to what is offered.
                val fps = plan.fpsOptions.firstOrNull { it == settings.fps } ?: plan.fpsOptions.firstOrNull { it == 30 } ?: plan.fpsOptions.first()
                ui.post {
                    if (camera !== cam) return@post
                    fpsOptions = plan.fpsOptions
                    activeSettings = settings.copy(fps = fps)
                    recorders = MultiRecorderManager(applicationContext, activeSettings)
                    refreshSettingsChip()
                    showLenses(plan.lenses, cam)
                }
                gl.recordFps = fps
                gl.sourceFps = plan.cameraFps(fps)
                val sources = gl.start(surface, plan.specs())
                cam.open(
                    plan, sources, plan.cameraFps(fps),
                    onReady = { ui.post { cameraReady = true; shutter.isEnabled = !busy; hideMessage() } },
                    onError = { msg -> fail(msg) },
                )
            } catch (t: Throwable) {
                Log.e(TAG, "pipeline start failed", t)
                fail(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun fail(reason: String) {
        Log.e(TAG, reason)
        ui.post {
            cameraReady = false
            shutter.isEnabled = false
            showMessage(getString(R.string.camera_failed, reason), getString(R.string.retry)) {
                val h = surfaceHolder
                if (h != null) {
                    stopPipeline()
                    startPipeline(h)
                }
            }
        }
    }

    private fun stopPipeline() {
        if (!running) return
        running = false
        cameraReady = false
        val gl = compositor
        val cam = camera
        val rec = recorders
        compositor = null
        camera = null
        recorders = null
        ui.removeCallbacks(timer)
        busy = false
        showRecordingUi(false)
        worker.execute {
            if (rec != null && rec.isRecording) {
                runCatching { gl?.stopRecording() }
                rec.stop()
            }
            cam?.close()
            gl?.release()
        }
    }

    // ---- controls ---------------------------------------------------------------------------------------

    private fun updateScene(s: Scene) {
        scene = s
        compositor?.scene = s
    }

    private fun selectPreset(preset: ScenePreset) {
        chips.forEachIndexed { i, c -> c.isSelected = ScenePreset.entries[i] == preset }
        updateScene(scene.copy(preset = preset))
    }

    private fun toggleRecord() {
        val rec = recorders ?: return
        val gl = compositor ?: return
        if (busy) return
        busy = true
        shutter.isEnabled = false
        shutter.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (!rec.isRecording) {
            val type = recordType
            worker.execute {
                try {
                    val targets = rec.start(type, 0)
                    gl.startRecording(targets)
                    ui.post { busy = false; showRecordingUi(true); ui.post(timer) }
                } catch (t: Throwable) {
                    Log.e(TAG, "record start failed", t)
                    ui.post {
                        busy = false
                        shutter.isEnabled = cameraReady
                        Toast.makeText(this, getString(R.string.record_start_failed, t.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            worker.execute {
                val stopped = runCatching { gl.stopRecording() }.isSuccess
                val result = rec.stop()
                ui.post {
                    busy = false
                    showRecordingUi(false)
                    ui.removeCallbacks(timer)
                    if (stopped && result.allOk) {
                        result.uris.firstOrNull()?.let { setThumbnail(it) }
                    } else {
                        Toast.makeText(this, R.string.record_save_failed, Toast.LENGTH_LONG).show()
                    }
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
            ui.post {
                pauseButton.glyph = if (rec.isPaused) Glyph.PLAY else Glyph.PAUSE
                pauseButton.contentDescription = getString(if (rec.isPaused) R.string.resume else R.string.pause)
            }
        }
    }

    private fun showRecordingUi(recording: Boolean) {
        shutter.recording = recording
        shutter.isEnabled = cameraReady && !busy
        typeToggle.visibility = if (recording) View.INVISIBLE else View.VISIBLE
        settingsChip.visibility = if (recording) View.INVISIBLE else View.VISIBLE
        timerPill.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        pauseButton.visibility = if (recording) View.VISIBLE else View.GONE
        thumbnail.visibility = if (recording) View.GONE else View.VISIBLE
        pauseButton.glyph = Glyph.PAUSE
        pauseButton.contentDescription = getString(R.string.pause)
        if (recording) timerText.text = "00:00"
    }

    // ---- last video thumbnail -------------------------------------------------------------------------

    private fun refreshThumbnail() {
        worker.execute {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("Movies/DualVideo/"))
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
            }
            val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = contentResolver.query(base, arrayOf(MediaStore.Video.Media._ID), args, null)?.use {
                if (it.moveToFirst()) ContentUris.withAppendedId(base, it.getLong(0)) else null
            }
            if (uri != null) setThumbnail(uri)
        }
    }

    // ---- video settings -------------------------------------------------------------------------------

    private fun loadSettings(): VideoSettings {
        val p = getSharedPreferences("video", MODE_PRIVATE)
        val res = Resolution.entries.firstOrNull { it.name == p.getString("resolution", null) } ?: Resolution.FHD
        return VideoSettings(res, p.getInt("fps", 30))
    }

    private fun refreshSettingsChip() {
        val s = activeSettings
        settingsChip.text = "${s.resolution.label} · ${s.fps}"
        settingsChip.contentDescription = getString(R.string.video_quality_desc, s.resolution.label, s.fps)
    }

    /** Resolution and frame-rate picker; a change is saved and restarts the camera when the dialog closes. */
    private fun showSettingsDialog() {
        if (recorders?.isRecording == true || busy) return
        var res = activeSettings.resolution
        var fps = activeSettings.fps
        fun label(text: String) = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; text.also { this.text = it } }
        val resToggle = SegmentedToggle(
            this, Resolution.entries.map { it.label },
            Resolution.entries.map { getString(R.string.resolution_option_desc, it.label) },
        ) { res = Resolution.entries[it] }
        resToggle.select(Resolution.entries.indexOf(res), false)
        val fpsToggle = SegmentedToggle(
            this, fpsOptions.map { "$it" },
            fpsOptions.map { getString(R.string.fps_option_desc, it) },
        ) { fps = fpsOptions[it] }
        fpsToggle.select(fpsOptions.indexOf(fps).coerceAtLeast(0), false)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(0xFF1E1E1E.toInt(), 20f, this@MainActivity)
            setPadding(dpi(24f), dpi(20f), dpi(24f), dpi(24f))
            addView(label(getString(R.string.resolution)))
            addView(resToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
            addView(label(getString(R.string.frame_rate)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(20f) })
            addView(fpsToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
        }
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener {
            if (res != activeSettings.resolution || fps != activeSettings.fps) {
                settings = VideoSettings(res, fps)
                getSharedPreferences("video", MODE_PRIVATE).edit().putString("resolution", res.name).putInt("fps", fps).apply()
                val h = surfaceHolder
                if (h != null) {
                    stopPipeline()
                    startPipeline(h)
                }
            }
        }
        dialog.show()
    }

    private fun setThumbnail(uri: Uri) {
        worker.execute {
            val bmp: Bitmap? = try {
                contentResolver.loadThumbnail(uri, Size(256, 256), null)
            } catch (e: IOException) {
                Log.w(TAG, "no thumbnail for $uri", e)
                null
            }
            ui.post {
                lastVideo = uri
                if (bmp != null) thumbnail.setImageBitmap(bmp)
            }
        }
    }

    private fun openLastVideo() {
        val uri = lastVideo
        if (uri == null) {
            Toast.makeText(this, R.string.no_video_yet, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_video, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- mini window drag (RegionHelper / PosData.updateMiniPos) ----------------------------------

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
        /** Upper end of the telephoto slider (the logical camera allows up to 20x; beyond this the image is mostly digital). */
        private const val TELE_MAX = 10f
    }
}
