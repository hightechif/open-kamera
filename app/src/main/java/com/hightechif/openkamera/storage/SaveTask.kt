/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import com.hightechif.openkamera.cameracontroller.RawImage
import com.hightechif.openkamera.processing.HDRProcessor
import java.util.Date

/**
 * Sealed hierarchy defining work requests dispatched to [ImageSavePipeline].
 */
sealed interface SaveTask {
    val id: Long
    val cost: Int
    val isRealImage: Boolean

    /**
     * Standard JPEG / Processed capture task (Normal, HDR, Average, Panorama, Night).
     */
    data class SaveJpeg(
        override val id: Long = System.nanoTime(),
        val processType: ImageSaver.Request.ProcessType = ImageSaver.Request.ProcessType.NORMAL,
        val forceSuffix: Boolean = false,
        val suffixOffset: Int = 0,
        val saveBase: ImageSaver.Request.SaveBase = ImageSaver.Request.SaveBase.SAVEBASE_NONE,
        val jpegImages: MutableList<ByteArray> = mutableListOf(),
        val preshotBitmaps: MutableList<Bitmap?>? = null,
        val imageCaptureIntent: Boolean = false,
        val imageCaptureIntentUri: Uri? = null,
        val usingCamera2: Boolean = false,
        val usingCameraExtensions: Boolean = false,
        var imageFormat: ImageSaver.Request.ImageFormat = ImageSaver.Request.ImageFormat.STD,
        var imageQuality: Int = 90,
        var doAutoStabilise: Boolean = false,
        val levelAngle: Double = 0.0,
        val gyroRotationMatrix: MutableList<FloatArray?>? = null,
        val isFrontFacing: Boolean = false,
        var mirror: Boolean = false,
        val currentDate: Date? = Date(),
        val preferenceHdrTonemappingAlgorithm: HDRProcessor.TonemappingAlgorithm = HDRProcessor.defaultTonemappingAlgorithmC,
        val preferenceHdrContrastEnhancement: String? = null,
        val iso: Int = 0,
        val exposureTime: Long = 0L,
        val zoomFactor: Float = 1.0f,
        var preferenceStamp: String? = null,
        var preferenceTextstamp: String? = null,
        val fontSize: Int = 0,
        val color: Int = 0,
        val prefStyle: String? = null,
        val preferenceStampDateformat: String? = null,
        val preferenceStampTimeformat: String? = null,
        val preferenceStampGpsformat: String? = null,
        val preferenceUnitsDistance: String? = null,
        val panoramaCrop: Boolean = false,
        val removeDeviceExif: ImageSaver.Request.RemoveDeviceExif = ImageSaver.Request.RemoveDeviceExif.OFF,
        val storeLocation: Boolean = false,
        val location: Location? = null,
        val storeGeoDirection: Boolean = false,
        val geoDirection: Double = 0.0,
        val pitchAngle: Double = 0.0,
        val storeYpr: Boolean = false,
        val customTagArtist: String? = null,
        val customTagCopyright: String? = null,
        val sampleFactor: Int = 1,
        var panoramaDirLeftToRight: Boolean = false,
        var cameraViewAngleX: Float = 0f,
        var cameraViewAngleY: Float = 0f,
        override val cost: Int = computeCost(false, jpegImages.size)
    ) : SaveTask {
        override val isRealImage: Boolean get() = true

        companion object {
            fun computeCost(isRaw: Boolean, imageCount: Int): Int {
                return if (isRaw) imageCount * 70 else imageCount
            }
        }
    }

    /**
     * RAW DNG persistence task.
     */
    data class SaveRaw(
        override val id: Long = System.nanoTime(),
        val forceSuffix: Boolean = false,
        val suffixOffset: Int = 0,
        val rawImage: RawImage?,
        val currentDate: Date? = Date(),
        override val cost: Int = 70
    ) : SaveTask {
        override val isRealImage: Boolean get() = true
    }

    /**
     * Marker task used for queue synchronization testing.
     */
    data class Dummy(
        override val id: Long = System.nanoTime(),
        override val cost: Int = 1
    ) : SaveTask {
        override val isRealImage: Boolean get() = false
    }

    /**
     * Lifecycle termination marker ensuring complete drain before pipeline shutdown.
     */
    data class OnDestroy(
        override val id: Long = System.nanoTime(),
        override val cost: Int = 1
    ) : SaveTask {
        override val isRealImage: Boolean get() = false
    }
}
