/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import android.os.Build
import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceGroup
import android.preference.PreferenceManager
import android.util.Log
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.R
import com.hightechif.openkamera.utils.MyDebug

class PreferenceSubVideo : PreferenceSubScreen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences_sub_video)

        val bundle: Bundle = arguments

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.activity)

        val cameraId = bundle.getInt("cameraId")
        if (MyDebug.LOG) Log.d(TAG, "cameraId: $cameraId")
        val cameraIdSPhysical = bundle.getString("cameraIdSPhysical")
        if (MyDebug.LOG) Log.d(TAG, "cameraIdSPhysical: $cameraIdSPhysical")

        val cameraOpen = bundle.getBoolean("camera_open")
        if (MyDebug.LOG) Log.d(TAG, "camera_open: $cameraOpen")

        val videoQuality = bundle.getStringArray("video_quality")
        val videoQualityString = bundle.getStringArray("video_quality_string")

        val videoFps = bundle.getIntArray("video_fps")
        val videoFpsHighSpeed = bundle.getBooleanArray("video_fps_high_speed")

        val fpsPreferenceKey: String? =
            PreferenceKeys.getVideoFPSPreferenceKey(cameraId, cameraIdSPhysical)
        if (MyDebug.LOG) Log.d(TAG, "fpsPreferenceKey: $fpsPreferenceKey")
        val fpsValue: String = sharedPreferences.getString(fpsPreferenceKey, "default")!!
        if (MyDebug.LOG) Log.d(TAG, "fpsValue: $fpsValue")

        val supportsTonemapCurve = bundle.getBoolean("supports_tonemap_curve")
        if (MyDebug.LOG) Log.d(TAG, "supportsTonemapCurve: $supportsTonemapCurve")

        val supportsVideoStabilization = bundle.getBoolean("supports_video_stabilization")
        if (MyDebug.LOG) Log.d(TAG, "supportsVideoStabilization: $supportsVideoStabilization")

        val supportsForceVideo4k = bundle.getBoolean("supports_force_video_4k")
        if (MyDebug.LOG) Log.d(TAG, "supportsForceVideo4k: $supportsForceVideo4k")

        /* Set up video resolutions.
		   Note that this will be the resolutions for either standard or high speed frame rate (where
		   the latter may also include being in slow motion mode), depending on the current setting when
		   this settings fragment is launched. A limitation is that if the user changes the fps value
		   within the settings, this list won't update until the user exits and re-enters the settings.
		   This could be fixed by setting a setOnPreferenceChangeListener for the preferenceVideoFps
		   ListPreference and updating, but we must not assume that the preview will be non-null (since
		   if the application is being recreated, MyPreferenceFragment.onCreate() is called via
		   MainActivity.onCreate()->super.onCreate() before the preview is created! So we still need to
		   read the info via a bundle, and only update when fps changes if the preview is non-null.
		 */
        if (videoQuality != null && videoQualityString != null) {
            val entries = arrayOfNulls<CharSequence>(videoQuality.size)
            val values = arrayOfNulls<CharSequence>(videoQuality.size)
            for (i in videoQuality.indices) {
                entries[i] = videoQualityString[i]
                values[i] = videoQuality[i]
            }
            val lp = findPreference("preference_video_quality") as ListPreference
            lp.entries = entries
            lp.entryValues = values
            val videoQualityPreferenceKey = bundle.getString("video_quality_preference_key")
            if (MyDebug.LOG) Log.d(
                TAG,
                "video_quality_preference_key: $videoQualityPreferenceKey"
            )
            val videoQualityValue: String =
                sharedPreferences.getString(videoQualityPreferenceKey, "")!!
            if (MyDebug.LOG) Log.d(TAG, "video_quality_value: $videoQualityValue")
            // set the key, so we save for the correct cameraId and high-speed setting
            // this must be done before setting the value (otherwise the video resolutions preference won't be
            // updated correctly when this is called from the callback when the user switches between
            // normal and high speed frame rates
            lp.key = videoQualityPreferenceKey
            lp.value = videoQualityValue

            val isHighSpeed = bundle.getBoolean("video_is_high_speed")
            val title: String? =
                if (isHighSpeed) resources.getString(R.string.video_quality) + " [" + resources.getString(
                    R.string.high_speed
                ) + "]" else resources.getString(R.string.video_quality)
            lp.title = title
            lp.dialogTitle = title
        } else {
            val pref: Preference? = findPreference("preference_video_quality")
            //PreferenceGroup pg = (PreferenceGroup)this.findPreference("preference_screen_video_settings");
            val pg = this.findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (videoFps != null) {
            // build video fps settings
            val entries = arrayOfNulls<CharSequence>(videoFps.size + 1)
            val values = arrayOfNulls<CharSequence>(videoFps.size + 1)
            var i = 0
            // default:
            entries[i] = resources.getString(R.string.preference_video_fps_default)
            values[i] = "default"
            i++
            val highSpeedAppend = " [" + resources.getString(R.string.high_speed) + "]"
            for (k in videoFps.indices) {
                val fps = videoFps[k]
                if (videoFpsHighSpeed != null && videoFpsHighSpeed[k]) {
                    entries[i] = fps.toString() + highSpeedAppend
                } else {
                    entries[i] = fps.toString()
                }
                values[i] = fps.toString()
                i++
            }

            val lp = findPreference("preference_video_fps") as ListPreference
            lp.entries = entries
            lp.entryValues = values
            lp.value = fpsValue
            // now set the key, so we save for the correct cameraId
            lp.key = fpsPreferenceKey
        }

        if (!supportsTonemapCurve && (cameraOpen || sharedPreferences.getString(
                PreferenceKeys.VIDEO_LOG_PREFERENCE_KEY,
                "off"
            ) == "off")
        ) {
            // if camera not open, we'll think this setting isn't supported - but should only remove
            // this preference if it's set to the default (otherwise if user sets to a non-default
            // value that causes camera to not open, user won't be able to put it back to the
            // default!)
            // (needed for Pixel 6 Pro where setting to sRGB causes camera to fail to open when in video mode)
            var pref: Preference? = findPreference(PreferenceKeys.VIDEO_LOG_PREFERENCE_KEY)
            //PreferenceGroup pg = (PreferenceGroup)this.findPreference("preference_screen_video_settings");
            var pg = this.findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)

            pref = findPreference(PreferenceKeys.VIDEO_PROFILE_GAMMA_PREFERENCE_KEY)
            //pg = (PreferenceGroup)this.findPreference("preference_screen_video_settings");
            pg = this.findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!supportsVideoStabilization) {
            val pref: Preference? = findPreference("preference_video_stabilization")
            //PreferenceGroup pg = (PreferenceGroup)this.findPreference("preference_screen_video_settings");
            val pg = this.findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!supportsForceVideo4k || videoQuality == null) {
            val pref: Preference? = findPreference("preference_force_video_4k")
            val pg = this.findPreference("preference_category_video_debugging") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            MyPreferenceFragment.filterArrayEntry(
                findPreference("preference_video_output_format") as ListPreference,
                "preference_video_output_format_mpeg4_hevc"
            )
        }

        run {
            val pref = findPreference("preference_record_audio_src") as ListPreference
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                // some values require at least Android 7
                pref.setEntries(R.array.preference_record_audio_src_entries_preandroid7)
                pref.setEntryValues(R.array.preference_record_audio_src_values_preandroid7)
            }
        }

        setupDependencies()

        if (MyDebug.LOG) Log.d(TAG, "onCreate done")
    }

    /** Programmatically set up dependencies for preference types (e.g., ListPreference) that don't
     * support this in XML (such as SwitchPreference and CheckBoxPreference), or where this depends
     * on the device (e.g., Android version).
     */
    private fun setupDependencies() {
        // set up dependency for preferenceVideoProfileGamma on preferenceVideoLog
        var pref = findPreference("preference_video_log") as ListPreference?
        if (pref != null) { // may be null if preference not supported
            pref.setOnPreferenceChangeListener { arg0, newValue ->
                val value = newValue.toString()
                setVideoProfileGammaDependency(value)
                true
            }
            setVideoProfileGammaDependency(pref.value) // ensure dependency is enabled/disabled as required for initial value
        }

        if (!MyApplicationInterface.mediastoreSupportsVideoSubtitles()) {
            // video subtitles only supported with SAF on Android 11+
            // since these preferences are entirely in separate sub-screens (and one isn't the parent of the other), we don't need
            // a dependency (and indeed can't use one, as the preferenceUsingSaf won't exist here as a Preference)
            pref = findPreference("preference_video_subtitle") as ListPreference?
            if (pref != null) {
                var usingSaf = false
                // n.b., not safe to call main_activity.applicationInterface.storageUtils.isUsingSAF() if fragment
                // is being recreated
                run {
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(this.activity)
                    if (sharedPreferences.getBoolean(
                            PreferenceKeys.USING_SAF_PREFERENCE_KEY,
                            false
                        )
                    ) {
                        usingSaf = true
                    }
                }
                if (MyDebug.LOG) Log.d(TAG, "using_saf: $usingSaf")

                //pref.setDependency("preference_using_saf");
                if (usingSaf) {
                    pref.isEnabled = true
                } else {
                    pref.isEnabled = false
                }
            }
        }
    }

    private fun setVideoProfileGammaDependency(newValue: String?) {
        val dependent: Preference? = findPreference("preference_video_profile_gamma")
        if (dependent != null) { // just in case
            val enableDependent = "gamma" == newValue
            if (MyDebug.LOG) Log.d(
                TAG,
                "clicked video log: $newValue enable_dependent: $enableDependent"
            )
            dependent.isEnabled = enableDependent
        }
    }

    companion object {
        private const val TAG = "PreferenceSubVideo"
    }
}