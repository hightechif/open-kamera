/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import java.io.Serial

/** Exception for CameraController classes.
 */
class CameraControllerException : Exception() {
    companion object {
        @Serial
        private const val serialVersionUID = 7904697847749213106L
    }
}