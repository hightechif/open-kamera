/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.model

enum class CameraFacing {
    BACK,
    FRONT,
    EXTERNAL
}

enum class CaptureMode {
    PHOTO,
    VIDEO,
    HDR,
    PANORAMA,
    BURST,
    SLOW_MOTION
}

enum class FlashMode(val key: String) {
    OFF("flash_off"),
    AUTO("flash_auto"),
    ON("flash_on"),
    TORCH("flash_torch"),
    RED_EYE("flash_red_eye");

    companion object {
        fun fromKey(key: String): FlashMode {
            return entries.firstOrNull { it.key == key } ?: AUTO
        }
    }
}

sealed interface FocusState {
    object Idle : FocusState
    data class Scanning(val pointX: Float? = null, val pointY: Float? = null) : FocusState
    data class Focused(val pointX: Float? = null, val pointY: Float? = null) : FocusState
    data class Failed(val pointX: Float? = null, val pointY: Float? = null) : FocusState
    object Locked : FocusState
}

enum class GridType(val key: String) {
    NONE("preference_grid_none"),
    RULE_OF_THIRDS("preference_grid_3x3"),
    PHI_GRID("preference_grid_phi_3x3"),
    GRID_4X2("preference_grid_4x2"),
    CROSSHAIR("preference_grid_crosshair"),
    GOLDEN_SPIRAL("preference_grid_golden_spiral");

    companion object {
        fun fromKey(key: String): GridType {
            return entries.firstOrNull { it.key == key } ?: NONE
        }
    }
}

data class ExposureCompensation(
    val currentStep: Int = 0,
    val minStep: Int = 0,
    val maxStep: Int = 0,
    val stepSize: Float = 0.0f
) {
    val evValue: Float
        get() = currentStep * stepSize
}
