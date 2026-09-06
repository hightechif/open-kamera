/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.activity

import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.view.LayoutInflater
import android.widget.EditText
import androidx.core.content.edit
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.utils.MyDebug

/**
 * Coordinates on-screen alert dialogs, first-time help, save folder inputs,
 * and confirmation prompts.
 */
class DialogCoordinator(private val mainActivity: MainActivity) {

    fun showFirstTimeHelpDialog(isTest: Boolean, onOnlineHelpClicked: () -> Unit) {
        if (!isTest) {
            val alertDialog = AlertDialog.Builder(mainActivity)
            alertDialog.setTitle(R.string.app_name)
            alertDialog.setMessage(R.string.intro_text)
            alertDialog.setPositiveButton(android.R.string.ok, null)
            alertDialog.setNegativeButton(R.string.preference_online_help) { _, _ ->
                onOnlineHelpClicked()
            }
            alertDialog.show()
        }
    }

    fun checkAndShowWhatsNewDialog(
        sharedPreferences: SharedPreferences,
        hasDoneFirstTime: Boolean
    ) {
        var versionCode = -1
        try {
            val pInfo = mainActivity.packageManager.getPackageInfo(mainActivity.packageName, 0)
            versionCode = pInfo.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            MyDebug.logStackTrace(TAG, "NameNotFoundException trying to get version number", e)
        }

        if (versionCode != -1) {
            val latestVersion =
                sharedPreferences.getInt(PreferenceKeys.LATEST_VERSION_PREFERENCE_KEY, 0)
            val whatsNewVersion = 94.coerceAtMost(versionCode)
            val allowShowWhatsNew = sharedPreferences.getBoolean(
                PreferenceKeys.SHOW_WHATS_NEW_PREFERENCE_KEY,
                true
            )

            if (hasDoneFirstTime && allowShowWhatsNew && whatsNewVersion > latestVersion) {
                val alertDialog = AlertDialog.Builder(mainActivity)
                alertDialog.setTitle(R.string.whats_new)
                alertDialog.setMessage(R.string.whats_new_text)
                alertDialog.setPositiveButton(android.R.string.ok, null)
                alertDialog.show()
            }

            sharedPreferences.edit {
                putInt(PreferenceKeys.LATEST_VERSION_PREFERENCE_KEY, versionCode)
            }
        }
    }

    fun createSaveFolderDialog(
        initialFolder: String,
        onFolderConfirmed: (String) -> Unit
    ): AlertDialog.Builder {
        val alertDialog = AlertDialog.Builder(mainActivity)
        alertDialog.setTitle(R.string.preference_save_location)

        val dialogView =
            LayoutInflater.from(mainActivity).inflate(R.layout.alertdialog_edittext, null)
        val editText = dialogView.findViewById<EditText>(R.id.edit_text)

        editText.hint = mainActivity.resources.getString(R.string.preference_save_location)
        editText.inputType = InputType.TYPE_CLASS_TEXT
        editText.setText(initialFolder)

        val filter = object : InputFilter {
            val disallowed: String = "|\\?*<\":>"
            override fun filter(
                source: CharSequence,
                start: Int,
                end: Int,
                dest: Spanned,
                dstart: Int,
                dend: Int
            ): CharSequence? {
                for (i in start until end) {
                    if (disallowed.indexOf(source[i]) != -1) {
                        return ""
                    }
                }
                if (dstart == 0 && start < source.length && source[start] == '/') {
                    return ""
                }
                return null
            }
        }
        editText.filters = arrayOf(filter)

        alertDialog.setView(dialogView)
        alertDialog.setPositiveButton(android.R.string.ok) { _, _ ->
            val folder = editText.text.toString()
            onFolderConfirmed(folder)
        }
        alertDialog.setNegativeButton(android.R.string.cancel, null)

        return alertDialog
    }

    fun showClearFolderHistoryConfirmationDialog(
        onConfirmed: () -> Unit,
        onDismissOrCancel: () -> Unit = {}
    ) {
        AlertDialog.Builder(mainActivity)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(R.string.clear_folder_history)
            .setMessage(R.string.clear_folder_history_question)
            .setPositiveButton(android.R.string.yes) { _, _ ->
                onConfirmed()
            }
            .setNegativeButton(android.R.string.no) { _, _ ->
                onDismissOrCancel()
            }
            .setOnCancelListener {
                onDismissOrCancel()
            }
            .show()
    }

    companion object {
        private const val TAG = "DialogCoordinator"
    }
}
