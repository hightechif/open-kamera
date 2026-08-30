/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.preference.PreferenceManager
import android.util.Log
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.utils.MyDebug
import androidx.core.content.edit


/** Handles a history of save locations.
 */
class SaveLocationHistory internal constructor(
    mainActivity: MainActivity,
    prefBase: String,
    folderName: String
) {
    private val mainActivity: MainActivity
    private val prefBase: String
    private val saveLocationHistory = ArrayList<String>()

    /** Creates a new SaveLocationHistory class. This manages a history of save folder locations.
     * @param mainActivity MainActivity.
     * @param prefBase String to use for shared preferences.
     * @param folderName The current save folder.
     */
    init {
        if (MyDebug.LOG) Log.d(
            TAG,
            "pref_base: $prefBase"
        )
        this.mainActivity = mainActivity
        this.prefBase = prefBase
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)

        // read save locations
        saveLocationHistory.clear()
        val saveLocationHistorySize = sharedPreferences.getInt(prefBase + "_size", 0)
        if (MyDebug.LOG) Log.d(
            TAG,
            "save_location_history_size: $saveLocationHistorySize"
        )
        for (i in 0..<saveLocationHistorySize) {
            val string = sharedPreferences.getString(prefBase + "_" + i, null)
            if (string != null) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "save_location_history $i: $string"
                )
                saveLocationHistory.add(string)
            }
        }
        // also update, just in case a new folder has been set
        updateFolderHistory(
            folderName,
            false
        ) // updateIcon can be false, as updateGalleryIcon() is called later in MainActivity.onResume()
        //updateFolderHistory("/sdcard/Pictures/OpenKameraTest");
    }

    /** Updates the save history with the current save location (should be called after changing the save location).
     * @param folderName The folder name to add or update in the history.
     * @param updateIcon Whether to update the gallery icon. If false, it's the caller's responsibility to call
     * MainActivity.updateGalleryIcon().
     */
    fun updateFolderHistory(folderName: String, updateIcon: Boolean) {
        updateFolderHistory(folderName)
        if (updateIcon) {
            // If the folder has changed, need to update the gallery icon.
            // Note that if using option to strip all exif tags, we won't be able to find the most recent image - so seems
            // better to stick with the current gallery thumbnail. (Also beware that we call this method when changing
            // non-trivial settings, even if the save folder wasn't actually changed.)
            if (!mainActivity.storageUtils.lastMediaScannedHasNoExifDateTime) {
                mainActivity.updateGalleryIcon()
            }
        }
    }

    /** Updates the save history with the supplied folder name
     * @param folderName The folder name to add or update in the history.
     */
    private fun updateFolderHistory(folderName: String) {
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "updateFolderHistory: $folderName"
            )
            Log.d(TAG, "save_location_history size: " + saveLocationHistory.size)
            for (i in saveLocationHistory.indices) {
                Log.d(TAG, saveLocationHistory[i])
            }
        }
        while (saveLocationHistory.remove(folderName)) {
        }
        saveLocationHistory.add(folderName)
        while (saveLocationHistory.size > 6) {
            saveLocationHistory.removeAt(0)
        }
        writeSaveLocations()
        if (MyDebug.LOG) {
            Log.d(TAG, "updateFolderHistory exit:")
            Log.d(TAG, "save_location_history size: " + saveLocationHistory.size)
            for (i in saveLocationHistory.indices) {
                Log.d(TAG, saveLocationHistory[i])
            }
        }
    }

    /** Clears the folder history, and reinitialize it with the current folder.
     * @param folderName The current folder name.
     */
    fun clearFolderHistory(folderName: String) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "clearFolderHistory: $folderName"
        )
        saveLocationHistory.clear()
        updateFolderHistory(folderName, true) // to re-add the current choice, and save
    }

    /** Writes the history to the SharedPreferences.
     */
    private fun writeSaveLocations() {
        if (MyDebug.LOG) Log.d(TAG, "writeSaveLocations")
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        sharedPreferences.edit {
            putInt(prefBase + "_size", saveLocationHistory.size)
            if (MyDebug.LOG) Log.d(TAG, "save_location_history_size = " + saveLocationHistory.size)
            for (i in saveLocationHistory.indices) {
                val string = saveLocationHistory[i]
                putString(prefBase + "_" + i, string)
            }
        }
    }

    /** Return the size of the history.
     * @return The size of the history.
     */
    fun size(): Int {
        return saveLocationHistory.size
    }

    /** Returns a save location entry.
     * @param index The index to return.
     * @return The save location at this index.
     */
    operator fun get(index: Int): String {
        return saveLocationHistory[index]
    }

    /** Removes a save location entry.
     * @param index The index to remove.
     */
    fun remove(index: Int) {
        saveLocationHistory.removeAt(index)
    }

    /** Sets a save location entry.
     * @param index The index to set.
     * @param element The new entry.
     */
    operator fun set(index: Int, element: String) {
        saveLocationHistory[index] = element
    }

    // for testing:
    /** Should be used for testing only.
     * @param value The value to search the location history for.
     * @return Whether the save location history contains the supplied value.
     */
    fun contains(value: String): Boolean {
        return saveLocationHistory.contains(value)
    }

    companion object {
        private const val TAG = "SaveLocationHistory"
    }
}
