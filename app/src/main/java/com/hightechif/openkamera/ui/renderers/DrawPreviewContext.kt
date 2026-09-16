/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import android.content.SharedPreferences
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.preview.Preview

import com.hightechif.openkamera.ui.HudOverlayState

/**
 * Lightweight per-frame state context provided to [OverlayRenderer] implementations.
 */
data class DrawPreviewContext(
    val mainActivity: MainActivity,
    val applicationInterface: MyApplicationInterface,
    val sharedPreferences: SharedPreferences,
    var scaleDp: Float = 1.0f,
    var scaleFont: Float = 1.0f,
    var strokeWidth: Float = 1.0f,
    var deviceUiRotation: Int = 0,
    var hasLevelAngle: Boolean = false,
    var levelAngle: Double = 0.0,
    var naturalLevelAngle: Double = 0.0,
    var hasPitchAngle: Boolean = false,
    var pitchAngle: Double = 0.0,
    var hasGeoDirection: Boolean = false,
    var geoDirection: Double = 0.0,
    var cameraInactiveTimeMs: Long = -1L,
    var hasAutoStabiliseCrop: Boolean = false,
    val autoStabiliseCrop: IntArray = IntArray(2),
    var previewSizeWysiwygPref: Boolean = false,
    var hudOverlayState: HudOverlayState = HudOverlayState()
) {
    val preview: Preview
        get() = mainActivity.preview

    fun dpToPx(dp: Float): Float = dp * scaleDp

    fun spToPx(sp: Float): Float = sp * scaleFont
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DrawPreviewContext

        if (scaleDp != other.scaleDp) return false
        if (scaleFont != other.scaleFont) return false
        if (strokeWidth != other.strokeWidth) return false
        if (deviceUiRotation != other.deviceUiRotation) return false
        if (hasLevelAngle != other.hasLevelAngle) return false
        if (levelAngle != other.levelAngle) return false
        if (naturalLevelAngle != other.naturalLevelAngle) return false
        if (hasPitchAngle != other.hasPitchAngle) return false
        if (pitchAngle != other.pitchAngle) return false
        if (hasGeoDirection != other.hasGeoDirection) return false
        if (geoDirection != other.geoDirection) return false
        if (cameraInactiveTimeMs != other.cameraInactiveTimeMs) return false
        if (hasAutoStabiliseCrop != other.hasAutoStabiliseCrop) return false
        if (previewSizeWysiwygPref != other.previewSizeWysiwygPref) return false
        if (hudOverlayState != other.hudOverlayState) return false
        if (mainActivity != other.mainActivity) return false
        if (applicationInterface != other.applicationInterface) return false
        if (sharedPreferences != other.sharedPreferences) return false
        if (!autoStabiliseCrop.contentEquals(other.autoStabiliseCrop)) return false
        if (preview != other.preview) return false

        return true
    }

    override fun hashCode(): Int {
        var result = scaleDp.hashCode()
        result = 31 * result + scaleFont.hashCode()
        result = 31 * result + strokeWidth.hashCode()
        result = 31 * result + deviceUiRotation
        result = 31 * result + hasLevelAngle.hashCode()
        result = 31 * result + levelAngle.hashCode()
        result = 31 * result + naturalLevelAngle.hashCode()
        result = 31 * result + hasPitchAngle.hashCode()
        result = 31 * result + pitchAngle.hashCode()
        result = 31 * result + hasGeoDirection.hashCode()
        result = 31 * result + geoDirection.hashCode()
        result = 31 * result + cameraInactiveTimeMs.hashCode()
        result = 31 * result + hasAutoStabiliseCrop.hashCode()
        result = 31 * result + previewSizeWysiwygPref.hashCode()
        result = 31 * result + hudOverlayState.hashCode()
        result = 31 * result + mainActivity.hashCode()
        result = 31 * result + applicationInterface.hashCode()
        result = 31 * result + sharedPreferences.hashCode()
        result = 31 * result + autoStabiliseCrop.contentHashCode()
        result = 31 * result + preview.hashCode()
        return result
    }
}
