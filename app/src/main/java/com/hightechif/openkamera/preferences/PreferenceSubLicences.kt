/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import android.app.AlertDialog
import android.os.Bundle
import android.preference.Preference
import android.util.Log
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.utils.MyDebug
import java.io.IOException
import java.io.InputStream
import java.util.Scanner

class PreferenceSubLicences : PreferenceSubScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences_sub_licences)

        run {
            val pref = findPreference("preference_licence_open_camera")
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (pref.key == "preference_licence_open_camera") {
                    if (MyDebug.LOG) Log.d(TAG, "user clicked Open Kamera licence")
                    displayTextDialog(R.string.preference_licence_open_camera, "gpl-3.0.txt")
                    false
                } else {
                    false
                }
            }
        }

        run {
            val pref = findPreference("preference_licence_androidx")
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (pref.key == "preference_licence_androidx") {
                    if (MyDebug.LOG) Log.d(TAG, "user clicked androidx licence")
                    displayTextDialog(
                        R.string.preference_licence_androidx,
                        "androidx_LICENSE-2.0.txt"
                    )
                    false
                } else {
                    false
                }
            }
        }

        run {
            val pref = findPreference("preference_licence_google_icons")
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (pref.key == "preference_licence_google_icons") {
                    if (MyDebug.LOG) Log.d(TAG, "user clicked google material design icons licence")
                    displayTextDialog(
                        R.string.preference_licence_google_icons,
                        "google_material_design_icons_LICENSE-2.0.txt"
                    )
                    false
                } else {
                    false
                }
            }
        }

        run {
            val pref = findPreference("preference_licence_online")
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (pref.key == "preference_licence_online") {
                    if (MyDebug.LOG) Log.d(TAG, "user clicked online licences")
                    val mainActivity = this.activity as MainActivity
                    mainActivity.launchOnlineLicences()
                    false
                } else {
                    false
                }
            }
        }

        if (MyDebug.LOG) Log.d(TAG, "onCreate done")
    }

    private fun displayTextDialog(titleId: Int, file: String) {
        try {
            val inputStream: InputStream = activity.assets.open(file)
            val scanner = Scanner(inputStream).useDelimiter("\\A")
            val alertDialog = AlertDialog.Builder(this.activity)
            alertDialog.setTitle(activity.resources.getString(titleId))
            alertDialog.setMessage(if (scanner.hasNext()) scanner.next() else "")
            alertDialog.setPositiveButton(android.R.string.ok, null)
            val alert = alertDialog.create()
            alert.setOnDismissListener {
                if (MyDebug.LOG) Log.d(TAG, "text dialog dismissed")
                dialogs.remove(alert)
            }
            alert.show()
            dialogs.add(alert)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val TAG = "PreferenceSubLicences"
    }
}
