package com.hightechif.openkamera

/**
 * Represents the physical orientation of the device as seen by the camera subsystem.
 * Promoted from a nested enum inside [MainActivity] so that [MainActivityLegacyGlue]
 * can reference it directly without a (disallowed) nested typealias.
 */
enum class SystemOrientation {
    PORTRAIT,
    LANDSCAPE,
    REVERSE_LANDSCAPE
}

/**
 * Returns the rotation in degrees (as a multiple of 90 degrees) corresponding to the supplied
 * system orientation. LANDSCAPE = 0°, PORTRAIT = 270°, REVERSE_LANDSCAPE = 180°.
 */
fun getRotationFromSystemOrientation(systemOrientation: SystemOrientation?): Int =
    when (systemOrientation) {
        SystemOrientation.PORTRAIT -> 270
        SystemOrientation.REVERSE_LANDSCAPE -> 180
        else -> 0
    }
