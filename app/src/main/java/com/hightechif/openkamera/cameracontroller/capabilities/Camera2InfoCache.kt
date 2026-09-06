/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.capabilities

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import com.hightechif.openkamera.cameracontroller.CameraController

/**
 * Caches static CameraCharacteristics, sensor active array size, zoom limits,
 * ISO/exposure ranges, physical camera IDs, and hardware support level flags for rapid access.
 */
class Camera2InfoCache(
    val characteristics: CameraCharacteristics?
) {

    val hardwareLevel: Int? = try {
        characteristics?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
    } catch (_: Throwable) {
        null
    }

    val isHardwareLevelSupported: (Int) -> Boolean = { requiredLevel ->
        Camera2CapabilitiesResolver.isHardwareLevelSupported(characteristics, requiredLevel)
    }

    val facing: CameraController.Facing = Camera2CapabilitiesResolver.getFacing(characteristics)

    val sensorOrientation: Int = try {
        characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    } catch (_: Throwable) {
        0
    }

    val activeArraySize: Rect? = try {
        characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    } catch (_: Throwable) {
        null
    }

    val pixelArraySize: Size? = try {
        characteristics?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    } catch (_: Throwable) {
        null
    }

    val physicalSize: SizeF? = try {
        characteristics?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    } catch (_: Throwable) {
        null
    }

    val viewAngles: SizeF = characteristics?.let {
        Camera2CapabilitiesResolver.computeViewAngles(it)
    } ?: SizeF(55.0f, 43.0f)

    val isFlashAvailable: Boolean = try {
        characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    } catch (_: Throwable) {
        false
    }

    val minFocusDistance: Float? = try {
        characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    } catch (_: Throwable) {
        null
    }

    val isoRange: Range<Int>? = try {
        characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    } catch (_: Throwable) {
        null
    }

    val exposureTimeRange: Range<Long>? = try {
        characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    } catch (_: Throwable) {
        null
    }

    val aeCompensationRange: Range<Int>? = try {
        characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    } catch (_: Throwable) {
        null
    }

    val aeCompensationStep: Float = try {
        when (val step = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)) {
            is Rational -> step.toFloat()
            is Number -> step.toFloat()
            else -> 0.0f
        }
    } catch (_: Throwable) {
        0.0f
    }

    val availableApertures: FloatArray? = try {
        characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
    } catch (_: Throwable) {
        null
    }

    val availableFocalLengths: FloatArray? = try {
        characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
    } catch (_: Throwable) {
        null
    }

    val capabilities: IntArray? = try {
        characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
    } catch (_: Throwable) {
        null
    }

    val supportsRaw: Boolean = try {
        capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
    } catch (_: Throwable) {
        false
    }

    val supportsLogicalMultiCamera: Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    val physicalCameraIds: Set<String> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            characteristics?.physicalCameraIds ?: emptySet()
        } else {
            emptySet()
        }
    } catch (_: Throwable) {
        emptySet()
    }
}
