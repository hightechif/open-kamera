/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.setup

import android.util.Log
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.utils.MyDebug

/**
 * Helper responsible for camera parameter negotiation, extension mode constraints,
 * picture size negotiation, and aspect ratio / zoom index lookups during camera setup.
 */
object PreviewCameraSetupHelper {

    private const val TAG = "PreviewCameraSetup"

    /**
     * Finds the 0-based index in the zoom ratios list that corresponds to 1x zoom (100).
     */
    fun find1xZoom(zoomRatios: List<Int>?): Int {
        if (zoomRatios == null) return 0
        for (i in zoomRatios.indices) {
            if (zoomRatios[i] == 100) {
                return i
            }
        }
        return 0
    }

    /**
     * Filters supported flash values for camera extension mode sessions.
     * Extension sessions only permit flash_off and flash_frontscreen_torch.
     */
    fun filterExtensionFlashModes(supportedFlashValues: List<String>?): MutableList<String>? {
        if (supportedFlashValues == null) return null
        val newSupportedFlashValues: MutableList<String> = ArrayList()
        for (supportedFlashValue in supportedFlashValues) {
            when (supportedFlashValue) {
                "flash_off", "flash_frontscreen_torch" -> newSupportedFlashValues.add(supportedFlashValue)
            }
        }
        return newSupportedFlashValues
    }

    /**
     * Finds the best matching picture size index that satisfies burst and/or extension requirements.
     * If the current size satisfies the requirements, its index is returned.
     * Otherwise, searches for the largest size smaller than or equal to current size that supports
     * requirements, falling back to the largest overall supporting size.
     */
    fun findBestSupportingPictureSizeIndex(
        photoSizes: List<CameraController.Size>?,
        currentSize: CameraController.Size?,
        isBurst: Boolean,
        isExtension: Boolean,
        extension: Int
    ): Int? {
        if (photoSizes == null || photoSizes.isEmpty() || currentSize == null) {
            return null
        }

        if (currentSize.supportsRequirements(isBurst, isExtension, extension)) {
            return null
        }

        if (MyDebug.LOG) {
            Log.d(TAG, "current picture size doesn't support required burst and/or extension")
        }

        var bestIndex: Int? = null
        var newSize: CameraController.Size? = null

        for (i in photoSizes.indices) {
            val size = photoSizes[i]
            if (size.supportsRequirements(isBurst, isExtension, extension) &&
                size.width * size.height <= currentSize.width * currentSize.height
            ) {
                if (newSize == null || size.width * size.height > newSize.width * newSize.height) {
                    bestIndex = i
                    newSize = size
                }
            }
        }

        if (newSize == null) {
            if (MyDebug.LOG) {
                Log.e(TAG, "can't find supporting picture size smaller than the current picture size")
            }
            for (i in photoSizes.indices) {
                val size = photoSizes[i]
                if (size.supportsRequirements(isBurst, isExtension, extension)) {
                    if (newSize == null || size.width * size.height > newSize.width * newSize.height) {
                        bestIndex = i
                        newSize = size
                    }
                }
            }
        }

        return bestIndex
    }
}
