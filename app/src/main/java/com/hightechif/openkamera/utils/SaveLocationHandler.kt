package com.hightechif.openkamera.utils

import android.app.AlertDialog
import android.net.Uri
import android.preference.PreferenceManager
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.storage.SaveLocationHistory
import com.hightechif.openkamera.storage.StorageUtils

/** Functionality related to the save location. */
class SaveLocationHandler(private val main_activity: MainActivity) {

    val saveLocationHistory: SaveLocationHistory
    var saveLocationHistorySAF: SaveLocationHistory? = null

    init {
        saveLocationHistory = SaveLocationHistory(
            main_activity,
            PreferenceKeys.SAVE_LOCATION_HISTORY_BASE_PREFERENCE_KEY,
            main_activity.storageUtils.saveLocation
        )
        checkSaveLocations()
        if (main_activity.storageUtils.isUsingSAF) {
            if (MyDebug.LOG) Log.d(TAG, "create new SaveLocationHistory for SAF")
            saveLocationHistorySAF = SaveLocationHistory(
                main_activity,
                PreferenceKeys.SAVE_LOCATION_HISTORY_SAF_BASE_PREFERENCE_KEY,
                main_activity.storageUtils.saveLocationSAF
            )
        }
    }

    /**
     * Handles users updating to a version with scoped storage (this could be Android 10 users upgrading
     * to the version of Open Kamera with scoped storage; or users who later upgrade to Android 10).
     * With scoped storage, we no longer support saving outside of DCIM/ when not using SAF.
     * This updates if necessary both the current save location, and the save folder history.
     */
    private fun checkSaveLocations() {
        if (MyDebug.LOG) Log.d(TAG, "checkSaveLocations")
        if (MainActivity.useScopedStorage()) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity)
            var any_changes = false
            val save_location = main_activity.storageUtils.saveLocation
            var res = checkSaveLocation(save_location)

            if (!res.res) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "save_location not valid with scoped storage: $save_location"
                )
                val new_folder = if (res.alt == null) {
                    "OpenKamera"
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "alternative: ${res.alt}")
                    res.alt
                }
                val editor = sharedPreferences.edit()
                editor.putString(PreferenceKeys.SAVE_LOCATION_PREFERENCE_KEY, new_folder)
                editor.apply()
                any_changes = true
            }

            // now check history
            // go backwards so we can remove easily
            for (i in saveLocationHistory.size() - 1 downTo 0) {
                val this_location = saveLocationHistory[i]
                res = checkSaveLocation(this_location)
                if (!res.res) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "save_location in history $i not valid with scoped storage: $this_location"
                    )
                    if (res.alt == null) {
                        saveLocationHistory.remove(i)
                    } else {
                        if (MyDebug.LOG) Log.d(TAG, "alternative: ${res.alt}")
                        saveLocationHistory[i] = res.alt
                    }
                    any_changes = true
                }
            }

            if (any_changes) {
                saveLocationHistory.updateFolderHistory(
                    main_activity.storageUtils.saveLocation,
                    false
                )
            }
        }
    }

    /**
     * Result from checkSaveLocation. Ideally we'd just use android.util.Pair, but that's not mocked
     * for use in unit tests.
     * See checkSaveLocation() for documentation.
     */
    data class CheckSaveLocationResult @JvmOverloads constructor(
        @JvmField val res: Boolean,
        @JvmField val alt: String? = null
    ) {
        override fun toString(): String {
            return "CheckSaveLocationResult{$res , $alt}"
        }
    }

    /**
     * Call when the SAF save history has been updated.
     * This is only public so we can call from testing.
     * @param save_folder The new SAF save folder Uri.
     */
    fun updateFolderHistorySAF(save_folder: String) {
        if (MyDebug.LOG) Log.d(TAG, "updateSaveHistorySAF")
        if (saveLocationHistorySAF == null) {
            saveLocationHistorySAF =
                SaveLocationHistory(
                    main_activity,
                    PreferenceKeys.SAVE_LOCATION_HISTORY_SAF_BASE_PREFERENCE_KEY,
                    save_folder
                )
        }
        saveLocationHistorySAF?.updateFolderHistory(save_folder, true)
    }

    /** Update the save folder (for non-SAF methods). */
    fun updateSaveFolder(new_save_location: String?) {
        if (MyDebug.LOG) Log.d(TAG, "updateSaveFolder: $new_save_location")
        if (new_save_location != null) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity)
            val orig_save_location = main_activity.storageUtils.saveLocation

            if (orig_save_location != new_save_location) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "changed save_folder to: ${main_activity.storageUtils.saveLocation}"
                )
                val editor = sharedPreferences.edit()
                editor.putString(PreferenceKeys.SAVE_LOCATION_PREFERENCE_KEY, new_save_location)
                editor.apply()

                saveLocationHistory.updateFolderHistory(
                    main_activity.storageUtils.saveLocation,
                    true
                )
                val save_folder_name =
                    getHumanReadableSaveFolder(main_activity.storageUtils.saveLocation)
                main_activity.preview.showToast(
                    null,
                    main_activity.resources.getString(R.string.changed_save_location) + "\n" + save_folder_name
                )
            }
        }
    }

    /**
     * Creates a dialog builder for specifying a save folder dialog (used when not using SAF,
     * and on scoped storage, as an alternative to using FolderChooserDialog).
     */
    fun createSaveFolderDialog(): AlertDialog.Builder {
        val alertDialog = AlertDialog.Builder(main_activity)
        alertDialog.setTitle(R.string.preference_save_location)

        val dialog_view =
            LayoutInflater.from(main_activity).inflate(R.layout.alertdialog_edittext, null)
        val editText = dialog_view.findViewById<EditText>(R.id.edit_text)

        // set hint instead of content description for EditText, see https://support.google.com/accessibility/android/answer/6378120
        editText.hint = main_activity.resources.getString(R.string.preference_save_location)
        editText.inputType = InputType.TYPE_CLASS_TEXT
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity)
        editText.setText(
            sharedPreferences.getString(
                PreferenceKeys.SAVE_LOCATION_PREFERENCE_KEY,
                "OpenKamera"
            )
        )
        val filter = InputFilter { source, start, end, _, dstart, _ ->
            // whilst Android seems to allow any characters on internal memory, SD cards are typically formatted with FAT32
            val disallowed = "|\\?*<\":>"
            for (i in start until end) {
                if (disallowed.indexOf(source[i]) != -1) {
                    return@InputFilter ""
                }
            }
            // also check for '/', not allowed at start
            if (dstart == 0 && start < source.length && source[start] == '/') {
                return@InputFilter ""
            }
            null
        }
        editText.filters = arrayOf(filter)

        alertDialog.setView(dialog_view)

        alertDialog.setPositiveButton(android.R.string.ok) { _, _ ->
            if (MyDebug.LOG) Log.d(TAG, "save location clicked okay")

            var folder = editText.text.toString()
            folder = processUserSaveLocation(folder)

            updateSaveFolder(folder)
        }
        alertDialog.setNegativeButton(android.R.string.cancel, null)

        return alertDialog
    }

    /** Returns a human readable string for the save_folder (as stored in the preferences). */
    fun getHumanReadableSaveFolder(save_folder: String): String {
        var folder = save_folder
        if (main_activity.storageUtils.isUsingSAF) {
            // try to get human readable form if possible
            val file_name =
                main_activity.storageUtils.getFilePathFromDocumentUriSAF(Uri.parse(folder), true)
            if (file_name != null) {
                folder = file_name
            }
        } else {
            // The strings can either be a sub-folder of DCIM, or (pre-scoped-storage) a full path, so normally either can be displayed.
            // But with scoped storage, an empty string is used to mean DCIM, so seems clearer to say that instead of displaying a blank line!
            if (MainActivity.useScopedStorage() && folder.isEmpty()) {
                folder = "DCIM"
            }
        }
        return folder
    }

    /** Clears the non-SAF folder history. */
    fun clearFolderHistory() {
        if (MyDebug.LOG) Log.d(TAG, "clearFolderHistory")
        saveLocationHistory.clearFolderHistory(main_activity.storageUtils.saveLocation)
    }

    /** Clears the SAF folder history. */
    fun clearFolderHistorySAF() {
        if (MyDebug.LOG) Log.d(TAG, "clearFolderHistorySAF")
        saveLocationHistorySAF?.clearFolderHistory(main_activity.storageUtils.saveLocationSAF)
    }

    fun usedFolderPicker() {
        if (main_activity.storageUtils.isUsingSAF) {
            saveLocationHistorySAF?.updateFolderHistory(
                main_activity.storageUtils.saveLocationSAF,
                true
            )
        } else {
            saveLocationHistory.updateFolderHistory(main_activity.storageUtils.saveLocation, true)
        }
    }

    companion object {
        private const val TAG = "SaveLocationHandler"

        /**
         * Checks to see if the supplied folder (in the format as used by our preferences) is supported
         * with scoped storage.
         * @return The Boolean is always non-null, and returns whether the save location is valid.
         *         If the return is false, then if the String is non-null, this stores an alternative
         *         form that is valid. If null, there is no valid alternative.
         * @param base_folder This should normally be null, but can be used to specify manually the
         *                    folder instead of using StorageUtils.getBaseFolder() - needed for unit
         *                    tests as Environment class (for Environment.getExternalStoragePublicDirectory())
         *                    is not mocked.
         */
        @JvmStatic
        @JvmOverloads
        fun checkSaveLocation(
            folder: String,
            base_folder: String? = null
        ): CheckSaveLocationResult {
            /*if( MyDebug.LOG )
                Log.d(TAG, "DCIM path: " + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath());*/
            if (StorageUtils.saveFolderIsFull(folder)) {
                if (MyDebug.LOG) Log.d(TAG, "checkSaveLocation for full path: $folder")
                // But still check to see if the full path is part of DCIM. Since when using the
                // file dialog method with non-scoped storage, if the user specifies multiple subfolders
                // e.g. DCIM/blah_a/blah_b, we don't spot that in FolderChooserDialog.useFolder(), and
                // instead still store that as the full path.

                var actualBaseFolder = base_folder
                if (actualBaseFolder == null) {
                    actualBaseFolder = StorageUtils.baseFolder?.absolutePath ?: ""
                }
                // strip '/' as last character - makes it easier to also spot cases where the folder is the
                // DCIM folder, but doesn't have a '/' last character
                if (actualBaseFolder.isNotEmpty() && actualBaseFolder.last() == '/') {
                    actualBaseFolder = actualBaseFolder.substring(0, actualBaseFolder.length - 1)
                }
                if (MyDebug.LOG) Log.d(TAG, "    compare to base_folder: $actualBaseFolder")
                var alt_folder: String? = null
                if (folder.startsWith(actualBaseFolder)) {
                    alt_folder = folder.substring(actualBaseFolder.length)
                    // also need to strip the first '/' if it exists
                    if (alt_folder.isNotEmpty() && alt_folder.first() == '/') {
                        alt_folder = alt_folder.substring(1)
                    }
                }

                return CheckSaveLocationResult(false, alt_folder)
            } else {
                // already in expected format (indicates a sub-folder of DCIM)
                return CheckSaveLocationResult(true, null)
            }
        }

        /**
         * Processes a user specified save folder. This should be used with the non-SAF scoped storage
         * method, where the user types a folder directly.
         */
        @JvmStatic
        fun processUserSaveLocation(folder: String): String {
            var f = folder
            // filter repeated '/', e.g., replace // with /:
            val strip = "//"
            while (f.isNotEmpty() && f.contains(strip)) {
                f = f.replace(strip, "/")
            }

            if (f.isNotEmpty() && f.first() == '/') {
                // strip '/' as first character - as absolute paths not allowed with scoped storage
                // whilst we do block entering a '/' as first character in the InputFilter, users could
                // get around this (e.g., put a '/' as second character, then delete the first character)
                f = f.substring(1)
            }

            if (f.isNotEmpty() && f.last() == '/') {
                // strip '/' as last character - MediaStore will ignore it, but seems cleaner to strip it out anyway
                // (we still need to allow '/' as last character in the InputFilter, otherwise users won't be able to type it whilst writing a subfolder)
                f = f.substring(0, f.length - 1)
            }

            return f
        }
    }
}
