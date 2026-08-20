/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import android.os.Bundle
import android.preference.PreferenceGroup
import android.preference.PreferenceManager
import android.util.Log
import com.hightechif.openkamera.R
import com.hightechif.openkamera.utils.MyDebug

class PreferenceSubGUI : PreferenceSubScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences_sub_gui)

        val bundle = arguments
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.activity)

        val cameraOpen = bundle.getBoolean("camera_open")
        if (MyDebug.LOG) Log.d(TAG, "camera_open: $cameraOpen")

        val supportsFaceDetection = bundle.getBoolean("supports_face_detection")
        if (MyDebug.LOG) Log.d(TAG, "supports_face_detection: $supportsFaceDetection")

        val supportsFlash = bundle.getBoolean("supports_flash")
        if (MyDebug.LOG) Log.d(TAG, "supports_flash: $supportsFlash")

        val supportsPreviewBitmaps = bundle.getBoolean("supports_preview_bitmaps")
        if (MyDebug.LOG) Log.d(TAG, "supports_preview_bitmaps: $supportsPreviewBitmaps")

        val supportsAutoStabilise = bundle.getBoolean("supports_auto_stabilise")
        if (MyDebug.LOG) Log.d(TAG, "supports_auto_stabilise: $supportsAutoStabilise")

        val supportsRaw = bundle.getBoolean("supports_raw")
        if (MyDebug.LOG) Log.d(TAG, "supports_raw: $supportsRaw")

        val supportsWhiteBalanceLock = bundle.getBoolean("supports_white_balance_lock")
        if (MyDebug.LOG) Log.d(TAG, "supports_white_balance_lock: $supportsWhiteBalanceLock")

        val supportsExposureLock = bundle.getBoolean("supports_exposure_lock")
        if (MyDebug.LOG) Log.d(TAG, "supports_exposure_lock: $supportsExposureLock")

        val isMultiCam = bundle.getBoolean("is_multi_cam")
        if (MyDebug.LOG) Log.d(TAG, "is_multi_cam: $isMultiCam")

        val hasPhysicalCameras = bundle.getBoolean("has_physical_cameras")
        if (MyDebug.LOG) Log.d(TAG, "has_physical_cameras: $hasPhysicalCameras")

        if (!supportsFaceDetection && (cameraOpen || !sharedPreferences.getBoolean(
                PreferenceKeys.FACE_DETECTION_PREFERENCE_KEY,
                false
            ))
        ) {
            val pref = findPreference("preference_show_face_detection")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!supportsFlash) {
            val pref = findPreference("preference_show_cycle_flash")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!supportsPreviewBitmaps) {
            val pref = findPreference("preference_show_focus_peaking")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!supportsAutoStabilise) {
            val pref = findPreference("preference_show_auto_level")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!supportsRaw) {
            val pref = findPreference("preference_show_cycle_raw")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!supportsWhiteBalanceLock) {
            val pref = findPreference("preference_show_white_balance_lock")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!supportsExposureLock) {
            val pref = findPreference("preference_show_exposure_lock")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!isMultiCam && !hasPhysicalCameras) {
            val pref = findPreference("preference_multi_cam_button")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (MyDebug.LOG) Log.d(TAG, "onCreate done")
    }

    companion object {
        private const val TAG = "PreferenceSubGUI"
    }
}
