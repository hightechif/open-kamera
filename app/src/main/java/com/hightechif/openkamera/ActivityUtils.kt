@file:Suppress("DEPRECATION")
package com.hightechif.openkamera

import android.os.Build
import android.preference.PreferenceManager
import android.view.HapticFeedbackConstants
import android.widget.SeekBar
import com.hightechif.openkamera.preferences.PreferenceKeys

/**
 * Top-level utility constants and functions that were previously static members of the
 * MainActivity companion object. Promoting them here removes the artificial dependency
 * on an Activity class for pure utility logic.
 */

/** Whether to lock to landscape orientation, or allow switching between portrait and landscape. */
const val LOCK_TO_LANDSCAPE: Boolean = false

/** Whether to use codepaths that are compatible with scoped storage. */
fun useScopedStorage(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

/** Performs haptic tick feedback on a SeekBar, throttled to one event per 16ms. */
fun performHapticFeedback(seekBar: SeekBar, lastHapticTime: Long): Long {
    var lastTime = lastHapticTime
    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(seekBar.context)
    if (sharedPreferences.getBoolean(PreferenceKeys.ALLOW_HAPTIC_FEEDBACK_PREFERENCE_KEY, true)) {
        val timeMs = System.currentTimeMillis()
        if (timeMs > lastTime + 16) {
            lastTime = timeMs
            seekBar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    return lastTime
}
