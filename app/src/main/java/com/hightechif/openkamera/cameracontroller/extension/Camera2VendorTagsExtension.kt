/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.extension

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.cameracontroller.CameraController.CameraFeaturesCache
import com.hightechif.openkamera.utils.MyDebug
import java.util.Hashtable

/**
 * Handles vendor camera extension discovery, capability caching, and extension resolution
 * (HDR, Night, Bokeh, Face Retouch, Auto).
 */
object Camera2VendorTagsExtension {

    private const val TAG = "Camera2VendorExtension"

    /**
     * Resolves vendor extensions from cache or directly queries the CameraExtensionCharacteristics.
     */
    fun resolveVendorExtensions(
        extensionCharacteristics: CameraExtensionCharacteristics?,
        cameraFeatures: CameraController.CameraFeatures,
        cameraFeaturesCache: CameraFeaturesCache?,
        useCache: Boolean = true
    ): CameraFeaturesCache? {
        if (extensionCharacteristics == null) {
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (useCache && cameraFeaturesCache != null) {
                readFromCache(cameraFeatures, cameraFeaturesCache)
                return cameraFeaturesCache
            } else {
                return queryAndCacheExtensions(extensionCharacteristics, cameraFeatures)
            }
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun readFromCache(
        cameraFeatures: CameraController.CameraFeatures,
        cameraFeaturesCache: CameraFeaturesCache
    ) {
        if (MyDebug.LOG) Log.d(TAG, "read vendor extensions info from cache")
        if (cameraFeaturesCache.supportedExtensions != null) {
            cameraFeatures.supportedExtensions = ArrayList(cameraFeaturesCache.supportedExtensions!!)
        }
        if (cameraFeaturesCache.supportedExtensionsZoom != null) {
            cameraFeatures.supportedExtensionsZoom = ArrayList(cameraFeaturesCache.supportedExtensionsZoom!!)
        }

        if (cameraFeatures.supportedExtensions != null) {
            val iter = cameraFeatures.supportedExtensions!!.iterator()
            while (iter.hasNext()) {
                val extension = iter.next()
                val extensionPictureSizes = cameraFeaturesCache.extensionPictureSizesMap[extension] ?: emptyList()
                val extensionPreviewSizes = cameraFeaturesCache.extensionPreviewSizesMap[extension] ?: emptyList()

                val hasPictureResolution = updatePictureSizesForExtension(
                    cameraFeatures.pictureSizes, extensionPictureSizes, extension
                )
                val hasPreviewResolution = updatePreviewSizesForExtension(
                    cameraFeatures.previewSizes, extensionPreviewSizes, extension
                )

                if (!hasPictureResolution || !hasPreviewResolution) {
                    if (MyDebug.LOG) Log.e(TAG, "cached extension not actually supported: $extension")
                    iter.remove()
                    cameraFeatures.supportedExtensionsZoom?.remove(extension)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun queryAndCacheExtensions(
        extensionCharacteristics: CameraExtensionCharacteristics,
        cameraFeatures: CameraController.CameraFeatures
    ): CameraFeaturesCache {
        if (MyDebug.LOG) Log.d(TAG, "check for vendor extensions")
        val extensionPictureSizesMap: MutableMap<Int, List<android.util.Size>> = Hashtable()
        val extensionPreviewSizesMap: MutableMap<Int, List<android.util.Size>> = Hashtable()

        var extensions: List<Int>? = null
        try {
            extensions = extensionCharacteristics.supportedExtensions
        } catch (e: Exception) {
            if (MyDebug.LOG) Log.e(TAG, "exception from getSupportedExtensions", e)
        }

        if (extensions != null) {
            val supportedExts = ArrayList<Int>()
            val supportedZoomExts = ArrayList<Int>()

            for (extension in extensions) {
                try {
                    val extensionPictureSizes = extensionCharacteristics.getExtensionSupportedSizes(
                        extension,
                        ImageFormat.JPEG
                    )
                    val hasPictureResolution = updatePictureSizesForExtension(
                        cameraFeatures.pictureSizes, extensionPictureSizes, extension
                    )

                    val extensionPreviewSizes = extensionCharacteristics.getExtensionSupportedSizes(
                        extension,
                        SurfaceTexture::class.java
                    )
                    val hasPreviewResolution = updatePreviewSizesForExtension(
                        cameraFeatures.previewSizes, extensionPreviewSizes, extension
                    )

                    if (hasPictureResolution && hasPreviewResolution) {
                        supportedExts.add(extension)
                        extensionPictureSizesMap[extension] = extensionPictureSizes
                        extensionPreviewSizesMap[extension] = extensionPreviewSizes

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val requestKeys = extensionCharacteristics.getAvailableCaptureRequestKeys(extension)
                            if (requestKeys.contains(CaptureRequest.CONTROL_ZOOM_RATIO)) {
                                supportedZoomExts.add(extension)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (MyDebug.LOG) Log.e(TAG, "exception trying to query extension: $extension", e)
                }
            }
            cameraFeatures.supportedExtensions = supportedExts
            cameraFeatures.supportedExtensionsZoom = supportedZoomExts
        }

        return CameraFeaturesCache(
            cameraFeatures,
            extensionPictureSizesMap,
            extensionPreviewSizesMap
        )
    }

    /**
     * Updates pictureSizes recording if each resolution supports the given vendor extension.
     */
    fun updatePictureSizesForExtension(
        pictureSizes: List<CameraController.Size>,
        extensionPictureSizes: List<android.util.Size>,
        extension: Int
    ): Boolean {
        var hasPictureResolution = false
        for (size in pictureSizes) {
            if (extensionPictureSizes.contains(android.util.Size(size.width, size.height))) {
                hasPictureResolution = true
                if (size.supportedExtensions == null) {
                    size.supportedExtensions = ArrayList()
                }
                size.supportedExtensions?.add(extension)
            }
        }
        return hasPictureResolution
    }

    /**
     * Updates previewSizes recording if each resolution supports the given vendor extension.
     */
    fun updatePreviewSizesForExtension(
        previewSizes: List<CameraController.Size>,
        extensionPreviewSizes: List<android.util.Size>,
        extension: Int
    ): Boolean {
        var hasPreviewResolution = false
        for (size in previewSizes) {
            if (extensionPreviewSizes.contains(android.util.Size(size.width, size.height))) {
                hasPreviewResolution = true
                if (size.supportedExtensions == null) {
                    size.supportedExtensions = ArrayList()
                }
                size.supportedExtensions?.add(extension)
            }
        }
        return hasPreviewResolution
    }
}
