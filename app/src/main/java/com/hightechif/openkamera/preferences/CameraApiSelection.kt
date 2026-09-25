/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

/**
 * Decides which camera HAL OpenKamera uses: Camera2 (default) or the Camera1 fallback.
 *
 * The rule, in order:
 * 1. No camera with at least `INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED` ⇒ Camera1 (a LEGACY-level Camera2 driver
 *    is only a wrapper over Camera1, so we use Camera1 directly). The preference is ignored.
 * 2. The user chose an API in settings ⇒ that API.
 * 3. No stored choice ⇒ Camera2 only if **every** camera is LIMITED or better; otherwise Camera1, because running a
 *    LEGACY camera on Camera2 is the unsupported configuration this fallback avoids. Such users can opt in to Camera2.
 *
 * 📖 **Learn more:** [Choosing Camera1 or Camera2](../../../../../../../../docs/module-01-foundations/01-camera-api-overview.md)
 */
object CameraApiSelection {

    enum class Reason(val logName: String) {
        DEFAULT("default"),
        NO_LIMITED_CAMERA("no-LIMITED-camera"),
        LEGACY_CAMERA_PRESENT("legacy-camera-present"),
        USER_PREFERENCE("user-preference")
    }

    data class Choice(val useCamera2: Boolean, val reason: Reason)

    /**
     * @param supportsCamera2 true if at least one camera has LIMITED or better Camera2 support.
     * @param allCamerasSupportCamera2 true if every camera has LIMITED or better Camera2 support.
     * @param preference the stored value of [PreferenceKeys.CAMERA_API_PREFERENCE_KEY], or null if nothing is stored.
     */
    fun choose(supportsCamera2: Boolean, allCamerasSupportCamera2: Boolean, preference: String?): Choice = when {
        !supportsCamera2 -> Choice(false, Reason.NO_LIMITED_CAMERA)
        preference == PreferenceKeys.CAMERA_API_PREFERENCE_OLD -> Choice(false, Reason.USER_PREFERENCE)
        preference == PreferenceKeys.CAMERA_API_PREFERENCE_CAMERA2 -> Choice(true, Reason.USER_PREFERENCE)
        allCamerasSupportCamera2 -> Choice(true, Reason.DEFAULT)
        else -> Choice(false, Reason.LEGACY_CAMERA_PRESENT)
    }

    /** The line logged once at startup, e.g. `API=Camera1 reason=no-LIMITED-camera`. */
    fun describe(choice: Choice): String =
        "API=${if (choice.useCamera2) "Camera2" else "Camera1"} reason=${choice.reason.logName}"

    /** The one-time "reduced features" notice is shown on Camera1, until it has been shown once. */
    fun shouldShowCamera1Notice(choice: Choice, alreadyShown: Boolean): Boolean =
        !choice.useCamera2 && !alreadyShown
}
