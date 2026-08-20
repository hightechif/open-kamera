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
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.utils.MyDebug

class PreferenceSubProcessing : PreferenceSubScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences_sub_processing)

        val bundle = arguments
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.activity)

        val cameraOpen = bundle.getBoolean("camera_open")
        if (MyDebug.LOG) Log.d(TAG, "camera_open: $cameraOpen")

        var hasAntibanding = false
        val antibandingValues = bundle.getStringArray("antibanding")
        if (antibandingValues != null && antibandingValues.isNotEmpty()) {
            val antibandingEntries = bundle.getStringArray("antibanding_entries")
            if (antibandingEntries != null && antibandingEntries.size == antibandingValues.size) {
                MyPreferenceFragment.readFromBundle(
                    this,
                    antibandingValues,
                    antibandingEntries,
                    PreferenceKeys.ANTI_BANDING_PREFERENCE_KEY,
                    CameraController.ANTIBANDING_DEFAULT,
                    "preferences_root"
                )
                hasAntibanding = true
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "has_antibanding?: $hasAntibanding")
        if (!hasAntibanding && (cameraOpen || sharedPreferences.getString(
                PreferenceKeys.ANTI_BANDING_PREFERENCE_KEY,
                CameraController.ANTIBANDING_DEFAULT
            ) == CameraController.ANTIBANDING_DEFAULT)
        ) {
            val pref = findPreference(PreferenceKeys.ANTI_BANDING_PREFERENCE_KEY)
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        var hasEdgeMode = false
        val edgeModeValues = bundle.getStringArray("edge_modes")
        if (edgeModeValues != null && edgeModeValues.isNotEmpty()) {
            val edgeModeEntries = bundle.getStringArray("edge_modes_entries")
            if (edgeModeEntries != null && edgeModeEntries.size == edgeModeValues.size) {
                MyPreferenceFragment.readFromBundle(
                    this,
                    edgeModeValues,
                    edgeModeEntries,
                    PreferenceKeys.EDGE_MODE_PREFERENCE_KEY,
                    CameraController.EDGE_MODE_DEFAULT,
                    "preferences_root"
                )
                hasEdgeMode = true
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "has_edge_mode?: $hasEdgeMode")
        if (!hasEdgeMode && (cameraOpen || sharedPreferences.getString(
                PreferenceKeys.EDGE_MODE_PREFERENCE_KEY,
                CameraController.EDGE_MODE_DEFAULT
            ) == CameraController.EDGE_MODE_DEFAULT)
        ) {
            val pref = findPreference(PreferenceKeys.EDGE_MODE_PREFERENCE_KEY)
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        var hasNoiseReductionMode = false
        val noiseReductionModeValues = bundle.getStringArray("noise_reduction_modes")
        if (noiseReductionModeValues != null && noiseReductionModeValues.isNotEmpty()) {
            val noiseReductionModeEntries = bundle.getStringArray("noise_reduction_modes_entries")
            if (noiseReductionModeEntries != null && noiseReductionModeEntries.size == noiseReductionModeValues.size) {
                MyPreferenceFragment.readFromBundle(
                    this,
                    noiseReductionModeValues,
                    noiseReductionModeEntries,
                    PreferenceKeys.CAMERA_NOISE_REDUCTION_MODE_PREFERENCE_KEY,
                    CameraController.NOISE_REDUCTION_MODE_DEFAULT,
                    "preferences_root"
                )
                hasNoiseReductionMode = true
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "has_noise_reduction_mode?: $hasNoiseReductionMode")
        if (!hasNoiseReductionMode && (cameraOpen || sharedPreferences.getString(
                PreferenceKeys.CAMERA_NOISE_REDUCTION_MODE_PREFERENCE_KEY,
                CameraController.NOISE_REDUCTION_MODE_DEFAULT
            ) == CameraController.NOISE_REDUCTION_MODE_DEFAULT)
        ) {
            val pref = findPreference(PreferenceKeys.CAMERA_NOISE_REDUCTION_MODE_PREFERENCE_KEY)
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (MyDebug.LOG) Log.d(TAG, "onCreate done")
    }

    companion object {
        private const val TAG = "PreferenceSubProcessing"
    }
}
