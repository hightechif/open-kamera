/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.Preview

/**
 * Renders real-time camera visual effects (zebra stripes, focus peaking) and ghost image overlays.
 */
class EffectOverlayRenderer : OverlayRenderer {

    private val p = Paint()
    private val lastImageSrcRect = RectF()
    private val lastImageDstRect = RectF()
    private val lastImageMatrix = Matrix()

    private var focusPeakingColorPref: Int = Color.WHITE
    private var ghostImagePref: String = "preference_ghost_image_off"
    private var ghostImageAlpha: Int = 127
    private var ghostSelectedImageBitmap: Bitmap? = null
    private var lastThumbnail: Bitmap? = null
    private var showLastImage: Boolean = false
    private var allowGhostImage: Boolean = false

    fun setFocusPeakingColor(color: Int) {
        this.focusPeakingColorPref = color
    }

    fun setGhostImagePref(pref: String) {
        this.ghostImagePref = pref
    }

    fun setGhostImageAlpha(alpha: Int) {
        this.ghostImageAlpha = alpha
    }

    fun setGhostSelectedImageBitmap(bitmap: Bitmap?) {
        this.ghostSelectedImageBitmap = bitmap
    }

    fun setLastThumbnail(thumbnail: Bitmap?) {
        this.lastThumbnail = thumbnail
    }

    fun showLastImage() {
        this.showLastImage = true
    }

    fun clearLastImage() {
        this.showLastImage = false
    }

    val isShowingLastImage: Boolean
        get() = showLastImage

    val isGhostImageAllowed: Boolean
        get() = allowGhostImage

    val currentGhostImagePref: String
        get() = ghostImagePref

    val isGhostLastImageActive: Boolean
        get() = allowGhostImage && ghostImagePref == "preference_ghost_image_last"

    val isGhostSelectedImageActive: Boolean
        get() = ghostImagePref == "preference_ghost_image_selected" && ghostSelectedImageBitmap != null

    val shouldRenderThumbnailOverlay: Boolean
        get() = lastThumbnail != null && (showLastImage || isGhostLastImageActive)

    fun allowGhostImage() {
        this.allowGhostImage = true
    }

    fun clearGhostImage() {
        this.allowGhostImage = false
    }

    override fun updateSettings() {
        // Updated via DrawPreview on settings refresh
    }

    private fun setLastImageMatrix(
        canvas: Canvas,
        bitmap: Bitmap,
        thisUiRotation: Int,
        flipFront: Boolean,
        context: DrawPreviewContext
    ) {
        val preview: Preview = context.preview
        val cameraController: CameraController? = preview.cameraController

        lastImageSrcRect.left = 0f
        lastImageSrcRect.top = 0f
        lastImageSrcRect.right = bitmap.width.toFloat()
        lastImageSrcRect.bottom = bitmap.height.toFloat()

        if (thisUiRotation == 90 || thisUiRotation == 270) {
            lastImageSrcRect.right = bitmap.height.toFloat()
            lastImageSrcRect.bottom = bitmap.width.toFloat()
        }

        lastImageDstRect.left = 0f
        lastImageDstRect.top = 0f
        lastImageDstRect.right = canvas.width.toFloat()
        lastImageDstRect.bottom = canvas.height.toFloat()

        lastImageMatrix.setRectToRect(
            lastImageSrcRect,
            lastImageDstRect,
            Matrix.ScaleToFit.CENTER
        )

        if (thisUiRotation == 90 || thisUiRotation == 270) {
            val diff = (bitmap.height - bitmap.width).toFloat()
            lastImageMatrix.preTranslate(diff / 2.0f, -diff / 2.0f)
        }

        lastImageMatrix.preRotate(
            thisUiRotation.toFloat(),
            bitmap.width / 2.0f,
            bitmap.height / 2.0f
        )

        if (flipFront) {
            val isFrontFacing = cameraController != null && cameraController.facing === CameraController.Facing.FACING_FRONT
            if (isFrontFacing && context.sharedPreferences.getString(
                    PreferenceKeys.FRONT_CAMERA_MIRROR_KEY,
                    "preference_front_camera_mirror_no"
                ) != "preference_front_camera_mirror_photo"
            ) {
                lastImageMatrix.preScale(-1.0f, 1.0f, bitmap.width / 2.0f, 0.0f)
            }
        }
    }

    override fun draw(canvas: Canvas, context: DrawPreviewContext, timeMs: Long) {
        val preview: Preview = context.preview
        val cameraController = preview.cameraController
        val uiRotation = preview.uIRotation

        p.reset()

        // Ghost image or last photo playback
        val isGhostLastImage = allowGhostImage && ghostImagePref == "preference_ghost_image_last"
        val isGhostSelectedImage = ghostImagePref == "preference_ghost_image_selected" && ghostSelectedImageBitmap != null

        if (cameraController != null && lastThumbnail != null && (showLastImage || isGhostLastImage)) {
            setLastImageMatrix(canvas, lastThumbnail!!, uiRotation, !showLastImage, context)
            if (!showLastImage) p.alpha = ghostImageAlpha
            canvas.drawBitmap(lastThumbnail!!, lastImageMatrix, p)
            if (!showLastImage) p.alpha = 255
        } else if (cameraController != null && isGhostSelectedImage && ghostSelectedImageBitmap != null) {
            setLastImageMatrix(canvas, ghostSelectedImageBitmap!!, uiRotation, true, context)
            p.alpha = ghostImageAlpha
            canvas.drawBitmap(ghostSelectedImageBitmap!!, lastImageMatrix, p)
            p.alpha = 255
        }

        // Real-time zebra stripes and focus peaking
        if (preview.isPreviewBitmapEnabled && !showLastImage) {
            val zebraStripesBitmap = preview.zebraStripesBitmap
            if (zebraStripesBitmap != null) {
                setLastImageMatrix(canvas, zebraStripesBitmap, 0, false, context)
                p.alpha = 255
                canvas.drawBitmap(zebraStripesBitmap, lastImageMatrix, p)
            }

            val focusPeakingBitmap = preview.focusPeakingBitmap
            if (focusPeakingBitmap != null) {
                setLastImageMatrix(canvas, focusPeakingBitmap, 0, false, context)
                p.alpha = 127
                if (focusPeakingColorPref != Color.WHITE) {
                    p.colorFilter = PorterDuffColorFilter(focusPeakingColorPref, PorterDuff.Mode.SRC_IN)
                }
                canvas.drawBitmap(focusPeakingBitmap, lastImageMatrix, p)
                if (focusPeakingColorPref != Color.WHITE) {
                    p.colorFilter = null
                }
                p.alpha = 255
            }
        }
    }
}
