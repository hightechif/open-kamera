/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import com.hightechif.openkamera.utils.MyDebug

/** Must be used as the parent class for all sub-screens.
 */
open class PreferenceSubScreen : PreferenceFragment(), OnSharedPreferenceChangeListener {
    private var edgeToEdgeMode = false

    // see note for dialogs in MyPreferenceFragment
    protected val dialogs: HashSet<AlertDialog> = HashSet()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        val bundle = arguments
        this.edgeToEdgeMode = bundle.getBoolean("edge_to_edge_mode")

        if (MyDebug.LOG) Log.d(TAG, "onCreate done")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (edgeToEdgeMode) {
            MyPreferenceFragment.handleEdgeToEdge(view)
        }
    }

    override fun onDestroy() {
        if (MyDebug.LOG) Log.d(TAG, "on_destroy")
        super.onDestroy()

        MyPreferenceFragment.dismissDialogs(fragmentManager, dialogs)
    }

    override fun onResume() {
        super.onResume()

        MyPreferenceFragment.setBackground(this)

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.activity)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    /* See comment for MyPreferenceFragment.onSharedPreferenceChanged().
     */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (MyDebug.LOG) Log.d(TAG, "onSharedPreferenceChanged: $key")

        if (key == null) {
            // On Android 11+, when targetting Android 11+, this method is called with key==null
            // if preferences are cleared. Unclear if this happens here in practice, but return
            // just in case.
            return
        }

        val pref = findPreference(key)
        MyPreferenceFragment.handleOnSharedPreferenceChanged(prefs, key, pref)
    }

    companion object {
        private const val TAG = "PreferenceSubScreen"
    }
}