package com.akram.dualvideo

import android.graphics.Rect
import android.util.Size

/**
 * Constants and layout math reproduced from the Xiaomi camera 4.5.002570.0
 * `com.android.camera.dualvideo` package (see docs/REA-FINDINGS.md for evidence).
 */
object DualConfig {
    /** DualVideoModuleBase.updateFpsRange: Range.create(30, 30) (24 on some platforms). */
    const val FPS = 30

    /** DualVideoModuleBase.updatePictureAndPreviewSize: back 1920x1080, front 1280x720. */
    val BACK_PREVIEW = Size(1920, 1080)
    val FRONT_PREVIEW = Size(1280, 720)

    /**
     * Composite/recording canvas. MiRecorder uses CamcorderProfile quality 1080P and calls
     * setVideoSize(height, width), i.e. portrait 1080x1920.
     */
    val OUTPUT = Size(1080, 1920)

    /** PosData.MINI_SCALE_X / MINI_SCALE_Y. */
    const val MINI_SCALE = 0.3333f

    /** RegionHelper.getRenderAreaFor: 5 px gap around the UP / DOWN tile seam. */
    const val SEAM_PX = 5

    /** Design choice (not observed): the front image is not mirrored in preview or recording. */
    const val MIRROR_FRONT = false
}

/** RenderSourceType: MAIN_SOURCE is the back camera, SUB_SOURCE the front camera. */
enum class SourceType { MAIN, SUB }

/** Subset of render.LayoutType used by a two-camera scene. */
enum class LayoutType { FULL, UP_FULL, DOWN_FULL, UP, DOWN, MINI }

/** RecordType: one composited file, or one file per camera. */
enum class RecordType { MERGED, STANDALONE }

/**
 * Which layout each source gets. `SPLIT` = two full-width halves, `STACK` = two centred
 * half-width tiles, `PIP` = one full frame with a 1/3 mini window.
 */
enum class ScenePreset(val main: LayoutType, val sub: LayoutType) {
    SPLIT(LayoutType.UP_FULL, LayoutType.DOWN_FULL),
    STACK(LayoutType.UP, LayoutType.DOWN),
    PIP(LayoutType.FULL, LayoutType.MINI),
}

/** Immutable scene description handed to the render thread. */
data class Scene(
    val preset: ScenePreset = ScenePreset.SPLIT,
    /** CameraItemManager.switchTopBottom: exchanges the layouts of the two sources. */
    val swapped: Boolean = false,
    /** Mini window top-left as a fraction of the free travel range (PosData.mMiniMarginLeft/Top). */
    val miniX: Float = 1f,
    val miniY: Float = 0f,
) {
    fun layoutOf(source: SourceType): LayoutType {
        val first = if (swapped) preset.sub else preset.main
        val second = if (swapped) preset.main else preset.sub
        return if (source == SourceType.MAIN) first else second
    }
}

object SceneLayout {
    /** RegionHelper.getRenderAreaFor, applied to a canvas of [w] x [h] pixels. */
    fun rectFor(type: LayoutType, w: Int, h: Int, miniX: Float, miniY: Float): Rect {
        val seam = DualConfig.SEAM_PX
        return when (type) {
            LayoutType.UP -> Rect(w / 4, 0, w * 3 / 4, h / 2 - seam)
            LayoutType.DOWN -> Rect(w / 4, h / 2 + seam, w * 3 / 4, h)
            LayoutType.UP_FULL -> Rect(0, 0, w, h / 2)
            LayoutType.DOWN_FULL -> Rect(0, h / 2, w, h)
            LayoutType.MINI -> {
                val mw = (w * DualConfig.MINI_SCALE).toInt()
                val mh = (h * DualConfig.MINI_SCALE).toInt()
                val x = (miniX.coerceIn(0f, 1f) * (w - mw)).toInt()
                val y = (miniY.coerceIn(0f, 1f) * (h - mh)).toInt()
                Rect(x, y, x + mw, y + mh)
            }
            LayoutType.FULL -> Rect(0, 0, w, h)
        }
    }

    /** Draw order: full-frame content first so the mini window always lands on top. */
    fun drawOrder(scene: Scene): List<SourceType> =
        SourceType.entries.sortedBy { if (scene.layoutOf(it) == LayoutType.MINI) 1 else 0 }
}
