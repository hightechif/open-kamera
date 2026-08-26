/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.graphics.Color
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.FocusState
import com.hightechif.openkamera.domain.model.GridType
import com.hightechif.openkamera.domain.model.HistogramData

/**
 * Immutable HUD overlay state model consumed by [DrawPreview] to render on-screen viewfinder elements.
 */
data class HudOverlayState(
    val gridType: GridType = GridType.NONE,
    val isImmersiveMode: Boolean = false,

    // Horizon, Pitch & Heading
    val showAngle: Boolean = false,
    val showAngleLine: Boolean = false,
    val showPitchLines: Boolean = false,
    val showGeoDirection: Boolean = false,
    val showGeoDirectionLines: Boolean = false,
    val horizonAngle: Double = 0.0,
    val pitchAngle: Double = 0.0,
    val compassDegrees: Double = 0.0,
    val isLevel: Boolean = false,
    val angleHighlightColor: Int = Color.GREEN,
    val calibratedLevelAngle: Double = 0.0,

    // Telemetry & Info
    val showIso: Boolean = false,
    val iso: Int = 0,
    val exposureTimeNs: Long = 0L,
    val showBattery: Boolean = false,
    val batteryFraction: Float = 1.0f,
    val showFreeMemory: Boolean = false,
    val freeMemoryGb: Float = -1.0f,
    val showTime: Boolean = false,
    val showCameraId: Boolean = false,
    val cameraIdString: String = "",

    // Audio & Video
    val isRecordingVideo: Boolean = false,
    val showVideoMaxAmp: Boolean = false,
    val videoMaxAmp: Int = 0,
    val videoMaxAmpPeak: Int = 0,

    // Histogram
    val showHistogram: Boolean = false,
    val histogramData: HistogramData? = null,

    // Badges & Indicators
    val flashMode: FlashMode = FlashMode.AUTO,
    val isRawEnabled: Boolean = false,
    val isRawOnly: Boolean = false,
    val focusState: FocusState = FocusState.Idle,
    val timerCountdownSeconds: Int = 0
)
