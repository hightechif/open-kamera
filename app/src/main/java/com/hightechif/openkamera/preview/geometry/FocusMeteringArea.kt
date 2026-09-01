/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.geometry

import android.graphics.Rect
import com.hightechif.openkamera.cameracontroller.CameraController

/**
 * Domain model representing a focus or metering area rectangle in normalized coordinates `[-1000, 1000]`.
 */
data class FocusMeteringArea(
    val bounds: Rect,
    val weight: Int = 1000
) {
    fun toCameraControllerArea(): CameraController.Area {
        return CameraController.Area(bounds, weight)
    }
}
