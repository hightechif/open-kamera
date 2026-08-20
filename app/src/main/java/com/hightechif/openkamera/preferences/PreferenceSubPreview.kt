package com.hightechif.openkamera.preferences

import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceGroup
import android.util.Log
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.ui.ArraySeekBarPreference
import com.hightechif.openkamera.utils.MyDebug

class PreferenceSubPreview : PreferenceSubScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences_sub_preview)

        val bundle = arguments

        val usingAndroidL = bundle.getBoolean("using_android_l")
        if (MyDebug.LOG) Log.d(TAG, "using_android_l: $usingAndroidL")

        val isMultiCam = bundle.getBoolean("is_multi_cam")
        if (MyDebug.LOG) Log.d(TAG, "is_multi_cam: $isMultiCam")

        val supportsPreviewBitmaps = bundle.getBoolean("supports_preview_bitmaps")
        if (MyDebug.LOG) Log.d(TAG, "supports_preview_bitmaps: $supportsPreviewBitmaps")

        run {
            val pref = findPreference("preference_ghost_image") as ListPreference
            pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                if (MyDebug.LOG) Log.d(TAG, "clicked ghost image: $newValue")
                if (newValue == "preference_ghost_image_selected") {
                    val mainActivity = this.activity as MainActivity
                    mainActivity.openGhostImageChooserDialogSAF(true)
                }
                true
            }
        }

        run {
            val maxGhostImageAlpha = 80 // limit max to 80% for privacy reasons
            val ghostImageAlphaStep = 5
            val nGhostImageAlpha = maxGhostImageAlpha / ghostImageAlphaStep
            val entries = Array<CharSequence>(nGhostImageAlpha) { "" }
            val values = Array<CharSequence>(nGhostImageAlpha) { "" }
            for (i in 0 until nGhostImageAlpha) {
                val alpha = ghostImageAlphaStep * (i + 1)
                entries[i] = "$alpha%"
                values[i] = alpha.toString()
            }
            val sp = findPreference("ghost_image_alpha") as ArraySeekBarPreference
            sp.setEntries(entries)
            sp.setEntryValues(values)
        }

        if (!usingAndroidL) {
            val pref = findPreference("preference_focus_assist")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!isMultiCam) {
            val pref = findPreference("preference_show_camera_id")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!usingAndroidL) {
            val pref = findPreference("preference_show_iso")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        if (!supportsPreviewBitmaps) {
            val pg = findPreference("preferences_root") as PreferenceGroup

            var pref = findPreference("preference_histogram")
            pg.removePreference(pref)

            pref = findPreference("preference_zebra_stripes")
            pg.removePreference(pref)

            pref = findPreference("preference_zebra_stripes_foreground_color")
            pg.removePreference(pref)

            pref = findPreference("preference_zebra_stripes_background_color")
            pg.removePreference(pref)

            pref = findPreference("preference_focus_peaking")
            pg.removePreference(pref)

            pref = findPreference("preference_focus_peaking_color")
            pg.removePreference(pref)
        }

        if (MyDebug.LOG) Log.d(TAG, "onCreate done")
    }

    companion object {
        private const val TAG = "PreferenceSubPreview"
    }
}
