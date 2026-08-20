package com.hightechif.openkamera.ui

import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.os.Bundle
import android.os.Environment
import android.text.InputFilter
import android.text.Spanned
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import com.hightechif.openkamera.R
import com.hightechif.openkamera.storage.StorageUtils
import com.hightechif.openkamera.utils.MyDebug
import java.io.File
import java.util.Collections
import java.util.Locale

/** Dialog to pick a folder or file. Also allows creating new folders. Used when not
 * using the Storage Access Framework.
 */
open class FolderChooserDialog : DialogFragment() {
    private var showNewFolderButton = true // whether to show a button for creating a new folder
    private var showDcimShortcut = true // whether to show a shortcut to the DCIM/ folder
    private var modeFolder =
        true // if true, the dialog is for selecting a folder; if false, the dialog is for selecting a file
    private var extension: String? =
        null // if non-null, and modeFolder==false, only show files matching this file extension

    private var startFolder = File("")

    // for testing:
    var currentFolder: File? = null
        private set
    private var maxParent: File? =
        null // if non-null, don't show the Parent option if viewing this folder (so the user can't go above that folder)
    private lateinit var folderDialog: AlertDialog
    private var list: ListView? = null

    /** Returns the folder selected by the user (or the folder containing the selected folder if
     * modeFolder==false). Returns null if the dialog was cancelled.
     */
    var chosenFolder: String? = null
        private set

    /** Returns the file selected by the user, if modeFolder==false. Returns null if the dialog was
     * cancelled or modeFolder==true.
     */
    var chosenFile: String? = null // only set if modeFolder==false
        private set

    private class FileWrapper(
        val file: File, // if non-null, use this as the display name instead
        private val overrideName: String?, // items are sorted first by sortOrder, then alphabetically
        private val sortOrder: Int
    ) : Comparable<FileWrapper?> {

        override fun toString(): String {
            if (overrideName != null) return overrideName
            if (file.isDirectory) return file.name + File.separator
            return file.name
        }

        override fun compareTo(other: FileWrapper?): Int {
            if (other == null) return -1
            if (this.sortOrder < other.sortOrder) return -1
            else if (this.sortOrder > other.sortOrder) return 1
            return file.name.lowercase().compareTo(other.file.name.lowercase())
        }

        override fun equals(other: Any?): Boolean {
            // important to override equals(), since we're overriding compareTo()
            if (other !is FileWrapper) return false
            if (this.sortOrder != other.sortOrder) return false
            return file.name.lowercase() == other.file.name.lowercase()
        }

        override fun hashCode(): Int {
            // must override this, as we override equals()
            return file.name.lowercase().hashCode()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle): Dialog {
        if (MyDebug.LOG) Log.d(TAG, "onCreateDialog")
        if (MyDebug.LOG) Log.d(
            TAG,
            "start in folder: $startFolder"
        )

        list = ListView(activity)
        list!!.onItemClickListener = object : OnItemClickListener {
            override fun onItemClick(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "onItemClick: $position"
                )
                val fileWrapper = parent.getItemAtPosition(position) as FileWrapper
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "clicked: $fileWrapper"
                )
                val file = fileWrapper.file
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "file: $file"
                )
                if (file.isDirectory) {
                    refreshList(file)
                } else if (!modeFolder && file.isFile) {
                    this@FolderChooserDialog.chosenFile = file.absolutePath
                    folderDialog.dismiss()
                }
            }
        }
        // good to use as short a text as possible for the icons, to reduce chance that the three buttons will have to appear on top of each other rather than in a row, in portrait mode
        val folderDialogBuilder = AlertDialog.Builder(
            activity
        ) //.setIcon(R.drawable.alert_dialog_icon)
            .setView(list)
        if (modeFolder) {
            folderDialogBuilder.setPositiveButton(
                android.R.string.ok,
                null
            ) // we set the listener in onShowListener, so we can prevent the dialog from closing (if chosen folder isn't writable)
        }
        if (showNewFolderButton) {
            folderDialogBuilder.setNeutralButton(
                R.string.new_folder,
                null
            ) // we set the listener in onShowListener, so we can prevent the dialog from closing
        }
        folderDialogBuilder.setNegativeButton(android.R.string.cancel, null)
        folderDialog = folderDialogBuilder.create()

        folderDialog.setOnShowListener {
            if (modeFolder) {
                val bPositive = folderDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                bPositive.setOnClickListener {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "choose folder: " + currentFolder.toString()
                    )
                    if (useFolder()) {
                        folderDialog.dismiss()
                    }
                }
            }
            if (showNewFolderButton) {
                val bNeutral = folderDialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                bNeutral.setOnClickListener {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "new folder in: " + currentFolder.toString()
                    )
                    newFolder()
                }
            }
        }

        if (!startFolder.exists()) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "create new folder$startFolder"
            )
            if (!startFolder.mkdirs()) {
                if (MyDebug.LOG) Log.d(TAG, "failed to create new folder")
                // don't do anything yet, this is handled below
            }
        }
        refreshList(startFolder)
        if (!canWrite()) {
            // see testFolderChooserInvalid()
            if (MyDebug.LOG) Log.d(TAG, "failed to read folder")

            if (showDcimShortcut) {
                if (MyDebug.LOG) Log.d(TAG, "fall back to DCIM")
                // note that we reset to DCIM rather than DCIM/OpenKamera, just to increase likelihood of getting back to a valid state
                refreshList(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
                if (currentFolder == null) {
                    if (MyDebug.LOG) Log.d(TAG, "can't even read DCIM?!")
                    refreshList(File("/"))
                }
            }
        }
        return folderDialog
    }

    fun setStartFolder(startFolder: File) {
        this.startFolder = startFolder
    }

    fun setMaxParent(maxParent: File) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setMaxParent: $maxParent"
        )
        this.maxParent = maxParent
    }

    fun setShowNewFolderButton(showNewFolderButton: Boolean) {
        this.showNewFolderButton = showNewFolderButton
    }

    fun setShowDCIMShortcut(showDcimShortcut: Boolean) {
        this.showDcimShortcut = showDcimShortcut
    }

    fun setModeFolder(modeFolder: Boolean) {
        this.modeFolder = modeFolder
    }

    fun setExtension(extension: String) {
        this.extension = extension.lowercase(Locale.getDefault())
    }

    private fun refreshList(newFolder: File?) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "refreshList: $newFolder"
        )
        if (newFolder == null) {
            if (MyDebug.LOG) Log.d(TAG, "refreshList: null folder")
            return
        }
        var files: Array<File>? = null
        // try/catch just in case?
        try {
            files = newFolder.listFiles()
        } catch (e: Exception) {
            if (MyDebug.LOG) Log.d(TAG, "exception reading folder")
            e.printStackTrace()
        }
        // n.b., files may be null if no files could be found in the folder (or we can't read) - but should still allow the user
        // to view this folder (so the user can go to parent folders which might be readable again)
        val listedFiles: MutableList<FileWrapper> = ArrayList()
        if (newFolder.parentFile != null) {
            if (maxParent != null && maxParent == newFolder) {
                // don't show parent option
            } else {
                listedFiles.add(
                    FileWrapper(
                        newFolder.parentFile!!,
                        resources.getString(R.string.parent_folder),
                        0
                    )
                )
            }
        }
        if (showDcimShortcut) {
            val defaultFolder =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            if (defaultFolder != newFolder && defaultFolder != newFolder.parentFile) listedFiles.add(
                FileWrapper(defaultFolder, null, 1)
            )
        }
        if (files != null) {
            for (file in files) {
                var accept = false
                if (file.isDirectory) accept = true
                else if (!modeFolder && file.isFile) {
                    accept = true
                    if (extension != null) {
                        val name = file.name
                        val index = name.lastIndexOf('.')
                        if (index != -1) {
                            val ext = name.substring(index).lowercase(Locale.getDefault())
                            if (ext != extension) {
                                accept = false
                            }
                        }
                    }
                }

                if (accept) {
                    val sortOrder = if (file.isDirectory) 2 else 3
                    listedFiles.add(FileWrapper(file, null, sortOrder))
                }
            }
        }
        Collections.sort(listedFiles)

        val adapter = ArrayAdapter(this.activity, android.R.layout.simple_list_item_1, listedFiles)
        list!!.adapter = adapter

        this.currentFolder = newFolder
        //dialog.setTitle(current_folder.getName());
        folderDialog.setTitle(currentFolder!!.absolutePath)
    }

    private fun canWrite(): Boolean {
        try {
            if (this.currentFolder != null && currentFolder!!.canWrite()) return true
        } catch (e: Exception) {
            if (MyDebug.LOG) Log.d(TAG, "exception in canWrite()")
        }
        return false
    }

    private fun useFolder(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "useFolder")
        if (currentFolder == null) return false
        if (canWrite()) {
            var newSaveLocation = currentFolder!!.absolutePath
            if (this.showDcimShortcut) {
                val baseFolder: File = StorageUtils.baseFolder
                if (currentFolder!!.parentFile != null && currentFolder!!.parentFile == baseFolder) {
                    if (MyDebug.LOG) Log.d(TAG, "parent folder is base folder")
                    newSaveLocation = currentFolder!!.name
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "new_save_location: $newSaveLocation"
            )
            chosenFolder = newSaveLocation
            return true
        } else {
            Toast.makeText(activity, R.string.cant_write_folder, Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private class NewFolderInputFilter : InputFilter {
        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: Spanned,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            for (i in start..<end) {
                if (disallowed.indexOf(source[i]) != -1) {
                    return ""
                }
            }
            return null
        }

        companion object {
            // whilst Android seems to allow any characters on internal memory, SD cards are typically formatted with FAT32
            private const val disallowed = "|\\?*<\":>"
        }
    }

    private fun newFolder() {
        if (MyDebug.LOG) Log.d(TAG, "newFolder")
        if (currentFolder == null) return
        if (canWrite()) {
            val dialogView: View =
                LayoutInflater.from(activity).inflate(R.layout.alertdialog_edittext, null)
            val editText = dialogView.findViewById<EditText>(R.id.edit_text)

            editText.setSingleLine()
            editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20.0f)
            // set hint instead of content description for EditText, see https://support.google.com/accessibility/android/answer/6378120
            //edit_text.setContentDescription(getResources().getString(R.string.enter_new_folder));
            editText.hint = resources.getString(R.string.enter_new_folder)
            val filter: InputFilter = NewFolderInputFilter()
            editText.filters = arrayOf(filter)

            val dialog: Dialog =
                AlertDialog.Builder(activity) //.setIcon(R.drawable.alert_dialog_icon)
                    .setTitle(R.string.enter_new_folder)
                    .setView(dialogView)
                    .setPositiveButton(android.R.string.ok) { dialog, which ->
                        if (editText.text.isEmpty()) {
                            // do nothing
                        } else {
                            try {
                                val newFolderName =
                                    currentFolder!!.absolutePath + File.separator + editText.text.toString()
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "create new folder: $newFolderName"
                                )
                                val newFolder = File(newFolderName)
                                if (newFolder.exists()) {
                                    if (MyDebug.LOG) Log.d(TAG, "folder already exists")
                                    Toast.makeText(
                                        activity,
                                        R.string.folder_exists,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else if (newFolder.mkdirs()) {
                                    if (MyDebug.LOG) Log.d(TAG, "created new folder")
                                    refreshList(this@FolderChooserDialog.currentFolder)
                                } else {
                                    if (MyDebug.LOG) Log.d(TAG, "failed to create new folder")
                                    Toast.makeText(
                                        activity,
                                        R.string.failed_create_folder,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "exception trying to create new folder"
                                )
                                e.printStackTrace()
                                Toast.makeText(
                                    activity,
                                    R.string.failed_create_folder,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
            dialog.show()
        } else {
            Toast.makeText(activity, R.string.cant_write_folder, Toast.LENGTH_SHORT).show()
        }
    }


    override fun onResume() {
        super.onResume()
        // refresh in case files have changed
        refreshList(currentFolder)
    }

    companion object {
        private const val TAG = "FolderChooserFragment"
    }
}
