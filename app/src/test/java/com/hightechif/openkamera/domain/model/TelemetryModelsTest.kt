/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryModelsTest {

    @Test
    fun cameraFrameMetadata_defaults_areNull() {
        val metadata = CameraFrameMetadata()
        assertNull(metadata.iso)
        assertNull(metadata.exposureTimeNs)
        assertNull(metadata.aperture)
        assertNull(metadata.focalLengthMm)
        assertEquals(0L, metadata.timestampNs)
    }

    @Test
    fun cameraFrameMetadata_customValues_areRetained() {
        val metadata = CameraFrameMetadata(
            iso = 400,
            exposureTimeNs = 20_000_000L,
            aperture = 1.8f,
            focalLengthMm = 26f,
            timestampNs = 123456789L
        )
        assertEquals(400, metadata.iso)
        assertEquals(20_000_000L, metadata.exposureTimeNs)
        assertEquals(1.8f, metadata.aperture ?: 0f, 0.001f)
        assertEquals(26f, metadata.focalLengthMm ?: 0f, 0.001f)
    }

    @Test
    fun sensorOrientation_andHorizonAngle_workCorrectly() {
        val horizon = HorizonAngle(angleDegrees = 0.2, isLevel = true)
        val orientation = SensorOrientation(
            horizonAngle = horizon,
            compassDegrees = 180f,
            pitchDegrees = 45f,
            rollDegrees = 0f
        )

        assertTrue(orientation.horizonAngle.isLevel)
        assertEquals(0.2, orientation.horizonAngle.angleDegrees, 0.001)
        assertEquals(180f, orientation.compassDegrees, 0.001f)
    }

    @Test
    fun histogramData_equality_comparesLuminanceArrays() {
        val hist1 = HistogramData(luminance = intArrayOf(10, 20, 30))
        val hist2 = HistogramData(luminance = intArrayOf(10, 20, 30))
        val hist3 = HistogramData(luminance = intArrayOf(10, 20, 40))

        assertEquals(hist1, hist2)
        assertEquals(hist1.hashCode(), hist2.hashCode())
        assertFalse(hist1 == hist3)
    }
}
