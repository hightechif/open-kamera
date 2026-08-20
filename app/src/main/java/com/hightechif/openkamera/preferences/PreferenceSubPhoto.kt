/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceGroup
import android.preference.PreferenceManager
import android.util.Log
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.ui.ArraySeekBarPreference
import com.hightechif.openkamera.utils.MyDebug

class PreferenceSubPhoto : PreferenceSubScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences_sub_photo)

        val bundle = arguments
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.activity)

        val cameraId = bundle.getInt("cameraId")
        if (MyDebug.LOG) Log.d(TAG, "cameraId: $cameraId")
        val cameraIdSPhysical = bundle.getString("cameraIdSPhysical")
        if (MyDebug.LOG) Log.d(TAG, "cameraIdSPhysical: $cameraIdSPhysical")

        val usingAndroidL = bundle.getBoolean("using_android_l")
        if (MyDebug.LOG) Log.d(TAG, "using_android_l: $usingAndroidL")

        val widths = bundle.getIntArray("resolution_widths")
        val heights = bundle.getIntArray("resolution_heights")
        val supportsBurst = bundle.getBooleanArray("resolution_supports_burst")

        val supportsJpegR = bundle.getBoolean("supports_jpeg_r")
        if (MyDebug.LOG) Log.d(TAG, "supports_jpeg_r: $supportsJpegR")

        val supportsRaw = bundle.getBoolean("supports_raw")
        if (MyDebug.LOG) Log.d(TAG, "supports_raw: $supportsRaw")
        val supportsBurstRaw = bundle.getBoolean("supports_burst_raw")
        if (MyDebug.LOG) Log.d(TAG, "supports_burst_raw: $supportsBurstRaw")

        val supportsOptimiseFocusLatency = bundle.getBoolean("supports_optimise_focus_latency")
        if (MyDebug.LOG) Log.d(
            TAG,
            "supports_optimise_focus_latency: $supportsOptimiseFocusLatency"
        )

        val supportsPreshots = bundle.getBoolean("supports_preshots")
        if (MyDebug.LOG) Log.d(TAG, "supports_preshots: $supportsPreshots")

        val supportsNr = bundle.getBoolean("supports_nr")
        if (MyDebug.LOG) Log.d(TAG, "supports_nr: $supportsNr")

        val supportsHdr = bundle.getBoolean("supports_hdr")
        if (MyDebug.LOG) Log.d(TAG, "supports_hdr: $supportsHdr")

        val supportsExpoBracketing = bundle.getBoolean("supports_expo_bracketing")
        if (MyDebug.LOG) Log.d(TAG, "supports_expo_bracketing: $supportsExpoBracketing")

        val maxExpoBracketingNImages = bundle.getInt("max_expo_bracketing_n_images")
        if (MyDebug.LOG) Log.d(TAG, "max_expo_bracketing_n_images: $maxExpoBracketingNImages")

        val supportsPanorama = bundle.getBoolean("supports_panorama")
        if (MyDebug.LOG) Log.d(TAG, "supports_panorama: $supportsPanorama")

        val supportsPhotoVideoRecording = bundle.getBoolean("supports_photo_video_recording")
        if (MyDebug.LOG) Log.d(TAG, "supports_photo_video_recording: $supportsPhotoVideoRecording")

        if (widths != null && heights != null && supportsBurst != null) {
            val entries = Array<CharSequence>(widths.size) { "" }
            val values = Array<CharSequence>(widths.size) { "" }
            for (i in widths.indices) {
                entries[i] = "${widths[i]} x ${heights[i]} " + Preview.getAspectRatioMPString(
                    resources,
                    widths[i],
                    heights[i],
                    supportsBurst[i]
                )
                values[i] = "${widths[i]} ${heights[i]}"
            }
            val lp = findPreference("preference_resolution") as ListPreference
            lp.entries = entries
            lp.entryValues = values
            val resolutionPreferenceKey =
                PreferenceKeys.getResolutionPreferenceKey(cameraId, cameraIdSPhysical)
            val resolutionValue = sharedPreferences.getString(resolutionPreferenceKey, "")
            if (MyDebug.LOG) Log.d(TAG, "resolution_value: $resolutionValue")
            lp.value = resolutionValue
            lp.key = resolutionPreferenceKey
        } else {
            val pref = findPreference("preference_resolution")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        run {
            val nQuality = 100
            val entries = Array<CharSequence>(nQuality) { "" }
            val values = Array<CharSequence>(nQuality) { "" }
            for (i in 0 until nQuality) {
                entries[i] = "${i + 1}%"
                values[i] = (i + 1).toString()
            }
            val sp = findPreference("preference_quality") as ArraySeekBarPreference
            sp.setEntries(entries)
            sp.setEntryValues(values)
        }

        if (!supportsJpegR) {
            val pref = findPreference("preference_image_format") as ListPreference
            pref.setEntries(R.array.preference_image_format_entries_nojpegr)
            pref.setEntryValues(R.array.preference_image_format_values_nojpegr)
        }

        if (!supportsRaw) {
            val pref = findPreference("preference_raw")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        } else {
            val pref = findPreference("preference_raw") as ListPreference
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                pref.setEntries(R.array.preference_raw_entries_preandroid7)
                pref.setEntryValues(R.array.preference_raw_values_preandroid7)
            }
            pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                if (MyDebug.LOG) Log.d(TAG, "clicked raw: $newValue")
                if (newValue == "preference_raw_yes" || newValue == "preference_raw_only") {
                    val doneRawInfo =
                        sharedPreferences.contains(PreferenceKeys.RAW_INFO_PREFERENCE_KEY)
                    if (!doneRawInfo) {
                        val alertDialog = AlertDialog.Builder(this.activity)
                        alertDialog.setTitle(R.string.preference_raw)
                        alertDialog.setMessage(R.string.raw_info)
                        alertDialog.setPositiveButton(android.R.string.ok, null)
                        alertDialog.setNegativeButton(R.string.dont_show_again) { _, _ ->
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "user clicked dont_show_again for raw info dialog"
                            )
                            val editor = sharedPreferences.edit()
                            editor.putBoolean(PreferenceKeys.RAW_INFO_PREFERENCE_KEY, true)
                            editor.apply()
                        }
                        val alert = alertDialog.create()
                        alert.setOnDismissListener {
                            if (MyDebug.LOG) Log.d(TAG, "raw dialog dismissed")
                            dialogs.remove(alert)
                        }
                        alert.show()
                        dialogs.add(alert)
                    }
                }
                true
            }
        }

        if (!(supportsRaw && supportsBurstRaw)) {
            val pg = findPreference("preferences_root") as PreferenceGroup
            var pref = findPreference("preference_raw_expo_bracketing")
            pg.removePreference(pref)
            pref = findPreference("preference_raw_focus_bracketing")
            pg.removePreference(pref)
        }

        if (!supportsOptimiseFocusLatency) {
            val pg = findPreference("preferences_root") as PreferenceGroup
            val pref = findPreference("preference_photo_optimise_focus")
            pg.removePreference(pref)
        }

        if (!supportsPreshots) {
            val pg = findPreference("preferences_root") as PreferenceGroup
            val pref = findPreference("preference_save_preshots")
            pg.removePreference(pref)
        }

        if (!supportsNr) {
            val pref = findPreference("preference_nr_save")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!supportsHdr) {
            val pg = findPreference("preferences_root") as PreferenceGroup
            var pref = findPreference("preference_hdr_save_expo")
            pg.removePreference(pref)
            pref = findPreference("preference_hdr_tonemapping")
            pg.removePreference(pref)
            pref = findPreference("preference_hdr_contrast_enhancement")
            pg.removePreference(pref)
        }

        if (!supportsExpoBracketing || maxExpoBracketingNImages <= 3) {
            val pref = findPreference("preference_expo_bracketing_n_images")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }
        if (!supportsExpoBracketing) {
            val pref = findPreference("preference_expo_bracketing_stops")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!supportsPanorama) {
            val pg = findPreference("preferences_root") as PreferenceGroup
            var pref = findPreference("preference_panorama_crop")
            pg.removePreference(pref)
            pref = findPreference("preference_panorama_save")
            pg.removePreference(pref)
        }

        if (!usingAndroidL) {
            val pg = findPreference("preference_category_photo_debugging") as PreferenceGroup
            var pref = findPreference("preference_camera2_fake_flash")
            pg.removePreference(pref)
            pref = findPreference("preference_camera2_dummy_capture_hack")
            pg.removePreference(pref)
            pref = findPreference("preference_camera2_fast_burst")
            pg.removePreference(pref)
            pref = findPreference("preference_camera2_photo_video_recording")
            pg.removePreference(pref)
        } else {
            if (!supportsPhotoVideoRecording) {
                val pref = findPreference("preference_camera2_photo_video_recording")
                val pg = findPreference("preference_category_photo_debugging") as PreferenceGroup
                pg.removePreference(pref)
            }
        }

        run {
            val pg = findPreference("preference_category_photo_debugging") as PreferenceGroup
            if (MyDebug.LOG) Log.d(
                TAG,
                "preference_category_photo_debugging children: ${pg.preferenceCount}"
            )
            if (pg.preferenceCount == 0) {
                val parent = findPreference("preferences_root") as PreferenceGroup
                parent.removePreference(pg)
            }
        }

        MyPreferenceFragment.setSummary(findPreference("preference_exif_artist"))
        MyPreferenceFragment.setSummary(findPreference("preference_exif_copyright"))
        MyPreferenceFragment.setSummary(findPreference("preference_textstamp"))

        if (MyDebug.LOG) Log.d(TAG, "onCreate done")
    }

    companion object {
        private const val TAG = "PreferenceSubPhoto"
    }
}
