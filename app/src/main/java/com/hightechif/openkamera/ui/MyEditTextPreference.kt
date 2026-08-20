package com.hightechif.openkamera.ui

import android.content.Context
import android.content.res.TypedArray
import android.os.Parcel
import android.os.Parcelable
import android.preference.DialogPreference
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import com.hightechif.openkamera.R

/** This contains a custom preference for an EditTextPreference. We do all this to fix the problem
 * that Android's EditTextPreference doesn't satisfy Google's own emoji policy, due to the
 * programmatically allocated EditText (which means AppCompat can't update it to support emoji
 * properly). This is fixed with AndroidX (androidx.preference.*), but switching to that is a major
 * change.
 * Once we have switched to AndroidX's preference libraries, we can switch back to
 * EditTextPreference (but check that the emoji strings still work on Android 10 or earlier!)
 */
class MyEditTextPreference(context: Context, attrs: AttributeSet) :
    DialogPreference(context, attrs) {
    //private static final String TAG = "MyEditTextPreference";
    private lateinit var edittext: EditText

    private var dialogMessage = ""
    private val inputType: Int

    // current saved value of this preference (note that this is intentionally not updated when the seekbar changes, as we don't save until the user clicks ok)
    var text: String? = null
        private set
    private var valueSet = false

    init {
        val namespace = "http://schemas.android.com/apk/res/android"

        // can't get both strings and resources to work - only support resources
        val id = attrs.getAttributeResourceValue(namespace, "dialogMessage", 0)
        if (id > 0) this.dialogMessage = context.getString(id)

        this.inputType = attrs.getAttributeIntValue(namespace, "inputType", EditorInfo.TYPE_NULL)

        dialogLayoutResource = R.layout.myedittextpreference
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)

        this.edittext = view.findViewById(R.id.myedittextpreference_edittext)
        edittext.setInputType(inputType)

        val textView = view.findViewById<TextView>(R.id.myedittextpreference_summary)
        textView.text = dialogMessage

        if (text != null) {
            edittext.setText(text)
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)

        if (positiveResult) {
            val newValue = edittext!!.text.toString()
            if (callChangeListener(newValue)) {
                setValue(newValue)
            }
        }
    }

    private fun setValue(value: String?) {
        val changed = !TextUtils.equals(this.text, value)
        if (changed || !valueSet) {
            this.text = value
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
        setValue(if (restoreValue) getPersistedString(text) else defaultValue as String)
    }

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        if (isPersistent) {
            return superState
        }

        val state = SavedState(superState)
        state.value = text
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
