package com.hightechif.openkamera.preferences

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.storage.StorageUtils
import com.hightechif.openkamera.ui.FolderChooserDialog
import com.hightechif.openkamera.utils.MyDebug
import java.io.IOException
import java.util.Date

class PreferenceSubSettingsManager : PreferenceSubScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        addPreferencesFromResource(R.xml.preferences_sub_settings_manager)

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.activity)

        run {
            val pref = findPreference("preference_save_settings")
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (pref.key == "preference_save_settings") {
                    if (MyDebug.LOG) Log.d(TAG, "user clicked save settings")

                    val alertDialog = AlertDialog.Builder(this.activity)
                    alertDialog.setTitle(R.string.preference_save_settings_filename)

                    val dialogView =
                        LayoutInflater.from(activity).inflate(R.layout.alertdialog_edittext, null)
                    val editText = dialogView.findViewById<EditText>(R.id.edit_text)

                    editText.setSingleLine()
                    editText.hint = resources.getString(R.string.preference_save_settings_filename)

                    alertDialog.setView(dialogView)

                    val mainActivity = this.activity as MainActivity
                    try {
                        var mediaFilename = mainActivity.storageUtils.createOutputMediaFile(
                            mainActivity.storageUtils.settingsFolder,
                            StorageUtils.MEDIA_TYPE_PREFS, "", "xml", Date()
                        ).name
                        if (MyDebug.LOG) Log.d(TAG, "mediaFilename: $mediaFilename")
                        val index = mediaFilename.lastIndexOf('.')
                        if (index != -1) {
                            mediaFilename = mediaFilename.substring(0, index)
                        }
                        editText.setText(mediaFilename)
                        editText.setSelection(mediaFilename.length)
                    } catch (e: IOException) {
                        Log.e(TAG, "failed to obtain a filename")
                        e.printStackTrace()
                    }

                    alertDialog.setPositiveButton(android.R.string.ok) { _, _ ->
                        if (MyDebug.LOG) Log.d(TAG, "save settings clicked okay")
                        val filename = editText.text.toString() + ".xml"
                        mainActivity.settingsManager.saveSettings(filename)
                    }
                    alertDialog.setNegativeButton(android.R.string.cancel, null)
                    val alert = alertDialog.create()
                    alert.setOnDismissListener {
                        if (MyDebug.LOG) Log.d(TAG, "save settings dialog dismissed")
                        dialogs.remove(alert)
                    }
                    alert.show()
                    dialogs.add(alert)
                }
                false
            }
        }

        run {
            val pref = findPreference("preference_restore_settings")
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (pref.key == "preference_restore_settings") {
                    if (MyDebug.LOG) Log.d(TAG, "user clicked restore settings")
                    loadSettings()
                }
                false
            }
        }

        run {
            val pref = findPreference("preference_reset")
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (pref.key == "preference_reset") {
                    if (MyDebug.LOG) Log.d(TAG, "user clicked reset settings")
                    val alertDialog = AlertDialog.Builder(this.activity)
                    alertDialog.setIcon(android.R.drawable.ic_dialog_alert)
                    alertDialog.setTitle(R.string.preference_reset)
                    alertDialog.setMessage(R.string.preference_reset_question)
                    alertDialog.setPositiveButton(android.R.string.yes) { _, _ ->
                        if (MyDebug.LOG) Log.d(TAG, "user confirmed reset")
                        val editor = sharedPreferences.edit()
                        editor.clear()
                        editor.putBoolean(PreferenceKeys.FIRST_TIME_PREFERENCE_KEY, true)
                        try {
                            val pInfo = this.activity.packageManager.getPackageInfo(
                                this.activity.packageName,
                                0
                            )
                            val versionCode = pInfo.versionCode
                            editor.putInt(PreferenceKeys.LATEST_VERSION_PREFERENCE_KEY, versionCode)
                        } catch (e: PackageManager.NameNotFoundException) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "NameNotFoundException exception trying to get version number"
                            )
                            e.printStackTrace()
                        }
                        editor.apply()
                        val mainActivity = this.activity as MainActivity
                        mainActivity.setDeviceDefaults()
                        if (MyDebug.LOG) Log.d(TAG, "user clicked reset - need to restart")
                        mainActivity.restartOpenKamera()
                    }
                    alertDialog.setNegativeButton(android.R.string.no, null)
                    val alert = alertDialog.create()
                    alert.setOnDismissListener {
                        if (MyDebug.LOG) Log.d(TAG, "reset dialog dismissed")
                        dialogs.remove(alert)
                    }
                    alert.show()
                    dialogs.add(alert)
                }
                false
            }
        }

        if (MyDebug.LOG) Log.d(TAG, "onCreate done")
    }

    private fun loadSettings() {
        if (MyDebug.LOG) Log.d(TAG, "loadSettings")
        val alertDialog = AlertDialog.Builder(this.activity)
        alertDialog.setIcon(android.R.drawable.ic_dialog_alert)
        alertDialog.setTitle(R.string.preference_restore_settings)
        alertDialog.setMessage(R.string.preference_restore_settings_question)
        alertDialog.setPositiveButton(android.R.string.yes) { _, _ ->
            if (MyDebug.LOG) Log.d(TAG, "user confirmed to restore settings")
            val mainActivity = this.activity as MainActivity
            val fragment = LoadSettingsFileChooserDialog()
            fragment.setShowDCIMShortcut(false)
            fragment.setShowNewFolderButton(false)
            fragment.setModeFolder(false)
            fragment.setExtension(".xml")
            fragment.setStartFolder(mainActivity.storageUtils.settingsFolder)
            if (MainActivity.useScopedStorage()) {
                val externalFilesDir = mainActivity.getExternalFilesDir(null)
                if (externalFilesDir != null) {
                    fragment.setMaxParent(externalFilesDir)
                }
            }
            fragment.show(fragmentManager, "FOLDER_FRAGMENT")
        }
        alertDialog.setNegativeButton(android.R.string.no, null)
        val alert = alertDialog.create()
        alert.setOnDismissListener {
            if (MyDebug.LOG) Log.d(TAG, "reset dialog dismissed")
            dialogs.remove(alert)
        }
        alert.show()
        dialogs.add(alert)
    }

    class LoadSettingsFileChooserDialog : FolderChooserDialog() {
        override fun onDismiss(dialog: DialogInterface) {
            if (MyDebug.LOG) Log.d(TAG, "FolderChooserDialog dismissed")
            val mainActivity = this.activity as? MainActivity
            if (mainActivity != null) {
                val settingsFile = this.chosenFile
                if (MyDebug.LOG) Log.d(TAG, "settings_file: $settingsFile")
                if (settingsFile != null) {
                    mainActivity.settingsManager.loadSettings(settingsFile)
                }
            }
            super.onDismiss(dialog)
        }
    }

    companion object {
        private const val TAG = "PrefSubSettingsManager"
    }
}
