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