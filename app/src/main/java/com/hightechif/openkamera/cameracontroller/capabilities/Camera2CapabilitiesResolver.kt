/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.capabilities

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Size
import android.util.SizeF
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.utils.MyDebug
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.pow

/**
 * Pure resolver for CameraCharacteristics, hardware levels, physical camera metadata,
 * zoom ratios, and sensor geometry.
 */
object Camera2CapabilitiesResolver {

    private const val TAG = "Camera2CapResolver"

    /**
     * Returns true if the device supports the required hardware level, or better.
     */
    fun isHardwareLevelSupported(c: CameraCharacteristics?, requiredLevel: Int): Boolean {
        if (c == null) return false
        var targetLevel = requiredLevel
        var deviceLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: return false

        // Legacy is a special case
        if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return targetLevel == deviceLevel
        }

        if (deviceLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) {
            deviceLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
        }
        if (targetLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) {
            targetLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
        }

        return targetLevel <= deviceLevel
    }

    /**
     * Resolves human-readable description for a supported hardware level.
     */
    fun getHardwareLevelDescription(level: Int?): String {
        return when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "Unknown: $level"
        }
    }

    /**
     * Resolves the CameraController.Facing direction from characteristics.
     */
    fun getFacing(characteristics: CameraCharacteristics?): CameraController.Facing {
        if (characteristics == null) return CameraController.Facing.FACING_UNKNOWN
        return when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> CameraController.Facing.FACING_FRONT
            CameraCharacteristics.LENS_FACING_BACK -> CameraController.Facing.FACING_BACK
            CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraController.Facing.FACING_EXTERNAL
            else -> CameraController.Facing.FACING_UNKNOWN
        }
    }

    /**
     * Computes horizontal and vertical view angles from CameraCharacteristics.
     */
    fun computeViewAngles(characteristics: CameraCharacteristics): SizeF {
        val activeSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

        if (activeSize == null || physicalSize == null || pixelSize == null || focalLengths == null || focalLengths.isEmpty()) {
            return SizeF(55.0f, 43.0f)
        }

        val fracX = activeSize.width().toFloat() / pixelSize.width.toFloat()
        val fracY = activeSize.height().toFloat() / pixelSize.height.toFloat()
        val viewAngleX = Math.toDegrees(
            2.0 * atan2((physicalSize.width * fracX).toDouble(), (2.0 * focalLengths[0]))
        ).toFloat()
        val viewAngleY = Math.toDegrees(
            2.0 * atan2((physicalSize.height * fracY).toDouble(), (2.0 * focalLengths[0]))
        ).toFloat()

        return SizeF(viewAngleX, viewAngleY)
    }

    /**
     * Resolves supported min and max zoom range for the camera.
     */
    fun resolveZoomRange(
        characteristics: CameraCharacteristics?,
        isPhysicalCamera: Boolean
    ): Pair<Float, Float> {
        if (characteristics == null || isPhysicalCamera) {
            return Pair(0.0f, 0.0f)
        }

        var minZoom = 0.0f
        var maxZoom = 0.0f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val zoomRatioRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRatioRange != null) {
                    minZoom = zoomRatioRange.lower
                    maxZoom = zoomRatioRange.upper
                }
            } catch (e: Throwable) {
                if (MyDebug.LOG) Log.e(TAG, "failed to get CONTROL_ZOOM_RATIO_RANGE", e)
            }
        }

        if (minZoom == 0.0f || maxZoom == 0.0f) {
            minZoom = 1.0f
            maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 0.0f
        }

        return Pair(minZoom, maxZoom)
    }

    /**
     * Computes the list of zoom ratios to use, and returns the 0-based index corresponding to 1x zoom.
     */
    fun computeZoomRatios(ratios: MutableList<Int>, minZoom: Float, maxZoom: Float): Int {
        val zoomValue1x: Int
        val scaleFactorC = 1.0174796921026863936352862847966
        val zoomRatiosAboveOne: MutableList<Int> = ArrayList()
        var zoom = scaleFactorC
        while (zoom < maxZoom - 1.0e-5f) {
            val zoomRatio = (zoom * 100 + 1.0e-5).toInt()
            zoomRatiosAboveOne.add(zoomRatio)
            zoom *= scaleFactorC
        }
        val maxZoomRatio = (maxZoom * 100).toInt()
        if (zoomRatiosAboveOne.isEmpty() || zoomRatiosAboveOne[zoomRatiosAboveOne.size - 1] != maxZoomRatio) {
            zoomRatiosAboveOne.add(maxZoomRatio)
        }
        val nStepsAboveOne = zoomRatiosAboveOne.size

        ratios.add((minZoom * 100).toInt())
        if (ratios[0] / 100.0f < minZoom) {
            ratios[0] = ratios[0] + 1
        }

        if (ratios[0] < 100) {
            val nStepsBelowOne = max(1.0, (nStepsAboveOne / 5).toDouble()).toInt()
            val nStepsOne = max(1.0, (nStepsAboveOne / 10).toDouble()).toInt()

            zoom = minZoom.toDouble()
            val scaleFactor = (1.0f / minZoom).toDouble().pow(1.0 / nStepsBelowOne.toDouble())
            for (i in 0 until nStepsBelowOne - 1) {
                zoom *= scaleFactor
                val zoomRatio = (zoom * 100).toInt()
                if (zoomRatio > ratios[0]) {
                    ratios.add(zoomRatio)
                }
            }

            zoomValue1x = ratios.size
            for (i in 0 until nStepsOne) ratios.add(100)
        } else {
            zoomValue1x = 0
        }

        val nStepsPowerTwo = max(1.0, (0.5f + nStepsAboveOne / 15.0f).toInt().toDouble()).toInt()
        for (zoomRatio in zoomRatiosAboveOne) {
            ratios.add(zoomRatio)
            if (zoomRatio != zoomRatiosAboveOne[zoomRatiosAboveOne.size - 1] && zoomRatio % 100 == 0) {
                val zoomRatioInt = zoomRatio / 100
                if (zoomRatioInt != 0 && (zoomRatioInt and (zoomRatioInt - 1)) == 0) {
                    for (i in 0 until nStepsPowerTwo - 1) ratios.add(zoomRatio)
                }
            }
        }

        return zoomValue1x
    }

    /**
     * Returns true if every entry in cameraWidths/cameraHeights is also a member of altCameraWidths/altCameraHeights.
     */
    fun sizeSubset(
        cameraWidths: IntArray?,
        cameraHeights: IntArray?,
        altCameraWidths: IntArray?,
        altCameraHeights: IntArray?
    ): Boolean {
        if (cameraWidths == null && cameraHeights == null) return true
        if (altCameraWidths == null && altCameraHeights == null) return false
        for (i in cameraWidths!!.indices) {
            var found = false
            for (j in altCameraWidths!!.indices) {
                if (cameraWidths[i] == altCameraWidths[j] && cameraHeights!![i] == altCameraHeights!![j]) {
                    found = true
                    break
                }
            }
            if (!found) return false
        }
        return true
    }

    /**
     * Returns true if every entry in cameraSizes is also a member of altCameraSizes.
     */
    fun sizeSubset(
        cameraSizes: Array<Size>?,
        altCameraSizes: Array<Size>?
    ): Boolean {
        var cameraWidths: IntArray? = null
        var cameraHeights: IntArray? = null
        var altCameraWidths: IntArray? = null
        var altCameraHeights: IntArray? = null
        if (cameraSizes != null) {
            cameraWidths = IntArray(cameraSizes.size)
            cameraHeights = IntArray(cameraSizes.size)
            for (i in cameraSizes.indices) {
                cameraWidths[i] = cameraSizes[i].width
                cameraHeights[i] = cameraSizes[i].height
            }
        }
        if (altCameraSizes != null) {
            altCameraWidths = IntArray(altCameraSizes.size)
            altCameraHeights = IntArray(altCameraSizes.size)
            for (i in altCameraSizes.indices) {
                altCameraWidths[i] = altCameraSizes[i].width
                altCameraHeights[i] = altCameraSizes[i].height
            }
        }
        return sizeSubset(cameraWidths, cameraHeights, altCameraWidths, altCameraHeights)
    }

    /**
     * Resolves physical camera IDs if the device supports logical multi-camera.
     */
    fun getPhysicalCameraIds(characteristics: CameraCharacteristics?): Set<String> {
        if (characteristics == null) return emptySet()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (capabilities != null && capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                return characteristics.physicalCameraIds
            }
        }
        return emptySet()
    }
}
