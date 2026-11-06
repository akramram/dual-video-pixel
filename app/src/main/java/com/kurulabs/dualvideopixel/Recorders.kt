package com.kurulabs.dualvideopixel

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * Reproduction of dualvideo.recorder.MiRecorder: a MediaRecorder fed by a persistent input surface
 * (MediaCodec.createPersistentInputSurface + VIDEO_SOURCE_SURFACE), configured from the 1080p
 * CamcorderProfile with swapped width/height, microphone audio, and an output registered in
 * MediaStore. Output goes to Movies/DualVideo so the file shows up in the gallery.
 */
class MiRecorder(
    private val context: Context,
    val tag: String,
    val source: SourceType?,
    private val orientationHint: Int,
    private val settings: VideoSettings,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val surface: Surface = MediaCodec.createPersistentInputSurface()
    private var recorder: MediaRecorder? = null
    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    var isRecording = false
        private set
    var isPaused = false
        private set

    val inputSurface: Surface get() = surface

    fun prepare(startStamp: String) {
        val profile = CamcorderProfile.get(settings.resolution.quality)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_${startStamp}_$tag.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DualVideo")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        uri = u
        pfd = resolver.openFileDescriptor(u, "rw")
        val r = MediaRecorder(context)
        recorder = r
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(profile.fileFormat)
        r.setVideoEncoder(profile.videoCodec)
        r.setVideoSize(settings.resolution.output.width, settings.resolution.output.height)
        r.setVideoFrameRate(settings.fps)
        r.setVideoEncodingBitRate((profile.videoBitRate.toLong() * settings.fps / profile.videoFrameRate).toInt())
        r.setAudioEncodingBitRate(profile.audioBitRate)
        r.setAudioChannels(profile.audioChannels)
        r.setAudioSamplingRate(profile.audioSampleRate)
        r.setAudioEncoder(profile.audioCodec)
        r.setOrientationHint(orientationHint)
        r.setOutputFile(pfd!!.fileDescriptor)
        r.setInputSurface(surface)
        r.prepare()
    }

    fun start() {
        recorder!!.start()
        isRecording = true
    }

    fun pause() {
        recorder?.pause()
        isPaused = true
    }

    fun resume() {
        recorder?.resume()
        isPaused = false
    }

    /** Stops and finalises the file. Returns its Uri, or null if MediaRecorder reported an error (file deleted). */
    fun stop(): Uri? {
        var ok = true
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "stop failed for $tag", e)
            ok = false
        }
        release()
        val u = uri
        if (u != null) {
            if (ok) {
                resolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } else {
                resolver.delete(u, null, null)
            }
        }
        isRecording = false
        return if (ok) uri else null
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { pfd?.close() }
        pfd = null
        surface.release()
    }

    override fun toString() = "MiRecorder($tag)"

    companion object {
        private const val TAG = "MiRecorder"
    }
}
/**
 * Reproduction of MultiRecorderManager: one recorder for RecordType.MERGED, two (main + sub) for
 * RecordType.STANDALONE. Start/stop act on every recorder; stop runs them in parallel.
 */
class MultiRecorderManager(private val context: Context, private val settings: VideoSettings) {
    private var recorders: List<MiRecorder> = emptyList()
    var isRecording = false
        private set
    var isPaused = false
        private set
    private var startedAtMs = 0L
    private var pausedAtMs = 0L
    private var pausedTotalMs = 0L

    /** Prepares and starts the recorders; returns the targets the compositor must draw into. */
    fun start(type: RecordType, orientationHint: Int): List<RecordTarget> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        recorders = when (type) {
            RecordType.MERGED -> listOf(MiRecorder(context, "merged", null, orientationHint, settings))
            RecordType.STANDALONE -> listOf(
                MiRecorder(context, "back", SourceType.MAIN, orientationHint, settings),
                MiRecorder(context, "front", SourceType.SUB, orientationHint, settings),
            )
        }
        try {
            recorders.forEach { it.prepare(stamp) }
            recorders.forEach { it.start() }
        } catch (e: Exception) {
            recorders.forEach { runCatching { it.stop() } }
            recorders = emptyList()
            throw e
        }
        isRecording = true
        isPaused = false
        startedAtMs = System.currentTimeMillis()
        pausedTotalMs = 0
        return recorders.map { RecordTarget(it.inputSurface, it.source) }
    }

    fun pause() {
        if (!isRecording || isPaused) return
        recorders.forEach { it.pause() }
        isPaused = true
        pausedAtMs = System.currentTimeMillis()
    }

    fun resume() {
        if (!isRecording || !isPaused) return
        recorders.forEach { it.resume() }
        isPaused = false
        pausedTotalMs += System.currentTimeMillis() - pausedAtMs
    }

    fun elapsedMs(): Long {
        if (!isRecording) return 0
        val now = if (isPaused) pausedAtMs else System.currentTimeMillis()
        return now - startedAtMs - pausedTotalMs
    }

    /** Stops every recorder in parallel; returns the Uris of the files that were finalised. */
    fun stop(): StopResult {
        if (!isRecording) return StopResult(emptyList(), true)
        isRecording = false
        val list = recorders
        recorders = emptyList()
        val futures = list.map { r -> CompletableFuture.supplyAsync { r.stop() } }
        val uris = futures.mapNotNull { it.get() }
        return StopResult(uris, uris.size == list.size)
    }
}

/** [uris] are the finalised files; [allOk] is false if any recorder failed. */
data class StopResult(val uris: List<Uri>, val allOk: Boolean)
