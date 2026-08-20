/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.content.Context
import android.content.res.TypedArray
import android.os.Parcel
import android.os.Parcelable
import android.preference.DialogPreference
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R

/** This contains a custom preference to display a seekbar in place of a ListPreference.
 */
class ArraySeekBarPreference(context: Context?, attrs: AttributeSet) :
    DialogPreference(context, attrs) {
    //private static final String TAG = "ArraySeekBarPreference";
    private lateinit var seekbar: SeekBar
    private lateinit var textView: TextView

    private var entries: Array<CharSequence>? = null // user readable strings
    private var values: Array<CharSequence>? = null // values corresponding to each string

    private val defaultValue: String?
    private var value: String? =
        null // current saved value of this preference (note that this is intentionally not updated when the seekbar changes, as we don't save until the user clicks ok)
    private var valueSet = false

    init {
        val namespace = "http://schemas.android.com/apk/res/android"
        this.defaultValue = attrs.getAttributeValue(namespace, "defaultValue")

        val entriesId = attrs.getAttributeResourceValue(namespace, "entries", 0)
        if (entriesId > 0) this.setEntries(entriesId)
        val valuesId = attrs.getAttributeResourceValue(namespace, "entryValues", 0)
        if (valuesId > 0) this.setEntryValues(valuesId)

        dialogLayoutResource = R.layout.arrayseekbarpreference
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)

        check(!(entries == null || values == null)) { "ArraySeekBarPreference requires entries and entryValues array" }
        check(entries!!.size == values!!.size) { "ArraySeekBarPreference requires entries and entryValues arrays of same length" }

        this.seekbar = view.findViewById<SeekBar>(R.id.arrayseekbarpreference_seekbar)
        this.textView = view.findViewById<TextView>(R.id.arrayseekbarpreference_value)

        seekbar.setMax(entries!!.size - 1)
        run {
            var index = valueIndex
            if (index == -1) {
                // If we're here, it means the stored value isn't in the values array.
                // ListPreference just shows a dialog with no selected entry, but that doesn't really work for
                // a seekbar that needs to show the current position! So instead, set the position to the default.
                if (defaultValue != null && values != null) {
                    for (i in values!!.indices.reversed()) {
                        if (values!![i] == defaultValue) {
                            index = i
                            break
                        }
                    }
                }
            }
            if (index >= 0) seekbar!!.progress = index
        }
        seekbar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            private var lastHapticTime: Long = 0

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val newEntry = entries!![progress].toString()
                textView.setText(newEntry)
                if (fromUser) {
                    lastHapticTime = MainActivity.performHapticFeedback(seekBar, lastHapticTime)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
            }
        })

        val newEntry = entries!![seekbar.getProgress()].toString()
        textView.setText(newEntry)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)

        if (positiveResult && values != null) {
            val progress = seekbar!!.progress
            val newValue = values!![progress].toString()
            if (callChangeListener(newValue)) {
                setValue(newValue)
            }
        }
    }

    fun setEntries(entries: Array<CharSequence>) {
        this.entries = entries
    }

    private fun setEntries(entries: Int) {
        setEntries(context.resources.getTextArray(entries))
    }

    fun setEntryValues(values: Array<CharSequence>) {
        this.values = values
    }

    private fun setEntryValues(values: Int) {
        setEntryValues(context.resources.getTextArray(values))
    }

    override fun getSummary(): CharSequence? {
        val summary = super.getSummary()
        if (summary != null) {
            val entry = entry
            return String.format(summary.toString(), entry ?: "")
        } else return null
    }

    private val valueIndex: Int
        /** Returns the index of the current value in the values array, or -1 if not found.
         */
        get() {
            if (value != null && values != null) {
                // go backwards for compatibility with ListPreference in cases with duplicate values
                for (i in values!!.indices.reversed()) {
                    if (values!![i] == value) {
                        return i
                    }
                }
            }
            return -1
        }

    private val entry: CharSequence?
        /** Returns the human readable string of the current value.
         */
        get() {
            val index = valueIndex
            return if (index >= 0 && entries != null) entries!![index] else null
        }

    private fun setValue(value: String?) {
        val changed = !TextUtils.equals(this.value, value)
        if (changed || !valueSet) {
            this.value = value
            valueSet = true
            persistString(value)
            if (changed) {
                notifyChanged()
            }
        }
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? {
        return a.getString(index)
    }

    override fun onSetInitialValue(restoreValue: Boolean, defaultValue: Any) {
        setValue(if (restoreValue) getPersistedString(value) else defaultValue as String)
    }

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        if (isPersistent) {
            return superState
        }

        val state = SavedState(superState)
        state.value = value
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state == null || state.javaClass != SavedState::class.java) {
            super.onRestoreInstanceState(state)
            return
        }

        val myState = state as SavedState
        super.onRestoreInstanceState(myState.superState)
        setValue(myState.value)
    }

    private class SavedState : BaseSavedState {
        var value: String? = null

        constructor(source: Parcel) : super(source) {
            value = source.readString()
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)
            dest.writeString(value)
        }

        constructor(superState: Parcelable?) : super(superState)

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(`in`: Parcel): SavedState {
                    return SavedState(`in`)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }
}