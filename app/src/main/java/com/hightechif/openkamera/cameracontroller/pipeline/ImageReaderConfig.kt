package com.hightechif.openkamera.cameracontroller.pipeline

import android.util.Size

/**
 * Configuration payload for initializing Camera2 ImageReader pipelines.
 */
data class ImageReaderConfig(
    val pictureWidth: Int,
    val pictureHeight: Int,
    val isJpegR: Boolean = false,
    val wantRaw: Boolean = false,
    val rawSize: Size? = null,
    val maxRawImages: Int = 2,
    val isVideoMode: Boolean = false
)
