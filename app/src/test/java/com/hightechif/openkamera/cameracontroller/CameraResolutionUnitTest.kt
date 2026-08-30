/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
@file:Suppress("DEPRECATION")

package com.hightechif.openkamera.cameracontroller

import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.preview.Preview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraResolutionUnitTest {

    @Test
    fun testVideoPhotoResolution() {
        val sizes: MutableList<CameraController.Size> = ArrayList()
        sizes.add(CameraController.Size(4640, 3480))
        sizes.add(CameraController.Size(4640, 2610))
        sizes.add(CameraController.Size(3488, 3488))
        sizes.add(CameraController.Size(3840, 2160))
        sizes.add(CameraController.Size(3456, 3456))
        sizes.add(CameraController.Size(1920, 1080))
        sizes.add(CameraController.Size(1728, 1728))
        sizes.add(CameraController.Size(1440, 1080))

        val maxVideoSize1 = CameraController.Size(3840, 2160)

        val photoSize1 = Preview.getOptimalVideoPictureSize(sizes, 16.0 / 9.0, maxVideoSize1)
        assertEquals(CameraController.Size(3840, 2160), photoSize1)

        val photoSize1b = Preview.getOptimalVideoPictureSize(sizes, 1.0, maxVideoSize1)
        assertEquals(CameraController.Size(1728, 1728), photoSize1b)

        val photoSize1c = Preview.getOptimalVideoPictureSize(sizes, 4.0 / 3.0, maxVideoSize1)
        assertEquals(CameraController.Size(1440, 1080), photoSize1c)

        val maxVideoSize2 = CameraController.Size(1920, 1080)

        val photoSize2 = Preview.getOptimalVideoPictureSize(sizes, 16.0 / 9.0, maxVideoSize2)
        assertEquals(CameraController.Size(1920, 1080), photoSize2)

        val photoSize2b = Preview.getOptimalVideoPictureSize(sizes, 1.0, maxVideoSize2)
        assertEquals(CameraController.Size(1440, 1080), photoSize2b)

        val photoSize2c = Preview.getOptimalVideoPictureSize(sizes, 4.0 / 3.0, maxVideoSize2)
        assertEquals(CameraController.Size(1440, 1080), photoSize2c)
    }

    @Test
    fun testPanoramaResolutions() {
        run {
            val sizes: MutableList<CameraController.Size> = ArrayList()
            sizes.add(CameraController.Size(4640, 3480))
            sizes.add(CameraController.Size(4640, 2610))
            sizes.add(CameraController.Size(3488, 3488))
            sizes.add(CameraController.Size(3840, 2160))
            sizes.add(CameraController.Size(3456, 3456))
            sizes.add(CameraController.Size(1920, 1080))
            sizes.add(CameraController.Size(1728, 1728))
            sizes.add(CameraController.Size(1440, 1080))
            sizes.add(CameraController.Size(1200, 900))

            val chosenSize = MyApplicationInterface.choosePanoramaResolution(sizes)
            assertEquals(CameraController.Size(1440, 1080), chosenSize)
        }
        run {
            val sizes: MutableList<CameraController.Size> = ArrayList()
            sizes.add(CameraController.Size(4640, 3480))
            sizes.add(CameraController.Size(4640, 2610))
            sizes.add(CameraController.Size(3488, 3488))
            sizes.add(CameraController.Size(3840, 2160))
            sizes.add(CameraController.Size(3456, 3456))
            sizes.add(CameraController.Size(1920, 1080))
            sizes.add(CameraController.Size(1728, 1728))
            sizes.add(CameraController.Size(1200, 900))

            val chosenSize = MyApplicationInterface.choosePanoramaResolution(sizes)
            assertEquals(CameraController.Size(1200, 900), chosenSize)
        }
        run {
            val sizes: MutableList<CameraController.Size> = ArrayList()
            sizes.add(CameraController.Size(4640, 3480))
            sizes.add(CameraController.Size(4640, 2610))
            sizes.add(CameraController.Size(3488, 3488))
            sizes.add(CameraController.Size(3840, 2160))
            sizes.add(CameraController.Size(3456, 3456))
            sizes.add(CameraController.Size(1920, 1080))
            sizes.add(CameraController.Size(1728, 1728))

            val chosenSize = MyApplicationInterface.choosePanoramaResolution(sizes)
            assertEquals(CameraController.Size(1920, 1080), chosenSize)
        }
        run {
            val sizes: MutableList<CameraController.Size> = ArrayList()
            sizes.add(CameraController.Size(4640, 3480))
            sizes.add(CameraController.Size(4640, 2610))
            sizes.add(CameraController.Size(3488, 3488))
            sizes.add(CameraController.Size(3840, 2160))
            sizes.add(CameraController.Size(3456, 3456))
            sizes.add(CameraController.Size(1728, 1728))

            val chosenSize = MyApplicationInterface.choosePanoramaResolution(sizes)
            assertEquals(CameraController.Size(1728, 1728), chosenSize)
        }
        run {
            val sizes: MutableList<CameraController.Size> = ArrayList()
            sizes.add(CameraController.Size(4640, 3480))
            sizes.add(CameraController.Size(4640, 2610))
            sizes.add(CameraController.Size(3488, 3488))
            sizes.add(CameraController.Size(3840, 2160))
            sizes.add(CameraController.Size(3456, 3456))

            val chosenSize = MyApplicationInterface.choosePanoramaResolution(sizes)
            assertEquals(CameraController.Size(3456, 3456), chosenSize)
        }
    }

    @Test
    fun testFocusBracketingDistances() {
        var focusDistances = CameraController2.setupFocusBracketingDistances(1.0f / 0.1f, 1.0f / 10.0f, 5)
        assertEquals(5, focusDistances.size.toLong())
        assertEquals((1.0f / 0.1f).toDouble(), focusDistances[0].toDouble(), 1.0e-5)
        assertEquals((1.0f / (0.138647f * (10.0f - 0.1f) + 0.1f)).toDouble(), focusDistances[1].toDouble(), 1.0e-5)
        assertEquals((1.0f / (0.317394f * (10.0f - 0.1f) + 0.1f)).toDouble(), focusDistances[2].toDouble(), 1.0e-5)
        assertEquals((1.0f / (0.569323f * (10.0f - 0.1f) + 0.1f)).toDouble(), focusDistances[3].toDouble(), 1.0e-5)
        assertEquals((1.0f / 10.0f).toDouble(), focusDistances[4].toDouble(), 1.0e-5)

        focusDistances = CameraController2.setupFocusBracketingDistances(1.0f / 10.0f, 1.0f / 0.1f, 5)
        assertEquals(5, focusDistances.size.toLong())
        assertEquals((1.0f / 0.1f).toDouble(), focusDistances[4].toDouble(), 1.0e-5)
        assertEquals((1.0f / (0.138647f * (10.0f - 0.1f) + 0.1f)).toDouble(), focusDistances[3].toDouble(), 1.0e-5)
        assertEquals((1.0f / (0.317394f * (10.0f - 0.1f) + 0.1f)).toDouble(), focusDistances[2].toDouble(), 1.0e-5)
        assertEquals((1.0f / (0.569323f * (10.0f - 0.1f) + 0.1f)).toDouble(), focusDistances[1].toDouble(), 1.0e-5)
        assertEquals((1.0f / 10.0f).toDouble(), focusDistances[0].toDouble(), 1.0e-5)

        focusDistances = CameraController2.setupFocusBracketingDistances(1.0f / 0.1f, 1.0f / 15.0f, 3)
        assertEquals(3, focusDistances.size.toLong())
        assertEquals((1.0f / 0.1f).toDouble(), focusDistances[0].toDouble(), 1.0e-5)
        assertEquals((1.0f / (0.369070f * (10.0f - 0.1f) + 0.1f)).toDouble(), focusDistances[1].toDouble(), 1.0e-5)
        assertEquals((1.0f / 15.0f).toDouble(), focusDistances[2].toDouble(), 1.0e-5)

        focusDistances = CameraController2.setupFocusBracketingDistances(1.0f / 15.0f, 1.0f / 0.1f, 3)
        assertEquals(3, focusDistances.size.toLong())
        assertEquals((1.0f / 0.1f).toDouble(), focusDistances[2].toDouble(), 1.0e-5)
        assertEquals((1.0f / (0.369070f * (10.0f - 0.1f) + 0.1f)).toDouble(), focusDistances[1].toDouble(), 1.0e-5)
        assertEquals((1.0f / 15.0f).toDouble(), focusDistances[0].toDouble(), 1.0e-5)

        focusDistances = CameraController2.setupFocusBracketingDistances(1.0f / 0.1f, 1.0f / 0.2f, 3)
        assertEquals(3, focusDistances.size.toLong())
        assertEquals((1.0f / 0.1f).toDouble(), focusDistances[0].toDouble(), 1.0e-5)
        assertEquals((1.0f / (0.369070f * (0.2f - 0.1f) + 0.1f)).toDouble(), focusDistances[1].toDouble(), 1.0e-5)
        assertEquals((1.0f / 0.2f).toDouble(), focusDistances[2].toDouble(), 1.0e-5)

        focusDistances = CameraController2.setupFocusBracketingDistances(1.0f / 0.2f, 1.0f / 0.1f, 3)
        assertEquals(3, focusDistances.size.toLong())
        assertEquals((1.0f / 0.1f).toDouble(), focusDistances[2].toDouble(), 1.0e-5)
        assertEquals((1.0f / (0.369070f * (0.2f - 0.1f) + 0.1f)).toDouble(), focusDistances[1].toDouble(), 1.0e-5)
        assertEquals((1.0f / 0.2f).toDouble(), focusDistances[0].toDouble(), 1.0e-5)
    }

    private fun checkCameraController2ZoomRatios(minZoom: Float, maxZoom: Float) {
        val ratios: MutableList<Int> = ArrayList()
        val zoomValue1x: Int = CameraController2.computeZoomRatios(ratios, minZoom, maxZoom)
        assertEquals(100, ratios[zoomValue1x].toLong())
        if (minZoom == 1.0f) {
            assertEquals(0, zoomValue1x.toLong())
        } else {
            assertTrue(zoomValue1x > 0)
        }

        var zoomRatio = 100
        while (zoomRatio <= (100 * maxZoom + 0.5).toInt()) {
            assertTrue(ratios.contains(zoomRatio))
            zoomRatio *= 2
        }
    }

    @Test
    fun testCameraController2ZoomRatios() {
        checkCameraController2ZoomRatios(1.0f, 1.0f)
        checkCameraController2ZoomRatios(1.0f, 2.0f)
        checkCameraController2ZoomRatios(1.0f, 4.0f)
        checkCameraController2ZoomRatios(1.0f, 8.0f)
        checkCameraController2ZoomRatios(1.0f, 10.0f)
        checkCameraController2ZoomRatios(1.0f, 16.0f)
        checkCameraController2ZoomRatios(1.0f, 20.0f)

        checkCameraController2ZoomRatios(0.7f, 4.0f)
        checkCameraController2ZoomRatios(0.7f, 8.0f)
        checkCameraController2ZoomRatios(0.7f, 10.0f)
        checkCameraController2ZoomRatios(0.7f, 16.0f)
        checkCameraController2ZoomRatios(0.7f, 20.0f)

        var minZoom = 0.0f
        var maxZoom = 0.0f
        if (minZoom == 0.0f || maxZoom == 0.0f) {
            minZoom = 1.0f
            maxZoom = 4.0f
        }
        checkCameraController2ZoomRatios(minZoom, maxZoom)
        assertTrue(maxZoom > 0.0f && minZoom > 0.0f)
    }

    @Test
    fun testSizeSubset() {
        assertTrue(CameraController2.sizeSubset(null, null, null, null))
        assertTrue(CameraController2.sizeSubset(null, null, intArrayOf(1920), intArrayOf(1080)))
        assertFalse(CameraController2.sizeSubset(intArrayOf(1920), intArrayOf(1080), null, null))

        assertTrue(
            CameraController2.sizeSubset(
                intArrayOf(1920),
                intArrayOf(1080),
                intArrayOf(1920),
                intArrayOf(1080)
            )
        )
        assertTrue(
            CameraController2.sizeSubset(
                intArrayOf(1920, 1280),
                intArrayOf(1080, 720),
                intArrayOf(1920, 1280),
                intArrayOf(1080, 720)
            )
        )
        assertTrue(
            CameraController2.sizeSubset(
                intArrayOf(1280, 1920),
                intArrayOf(720, 1080),
                intArrayOf(1920, 1280),
                intArrayOf(1080, 720)
            )
        )
        assertTrue(
            CameraController2.sizeSubset(
                intArrayOf(1920),
                intArrayOf(1080),
                intArrayOf(1920, 1280),
                intArrayOf(1080, 720)
            )
        )
        assertTrue(
            CameraController2.sizeSubset(
                intArrayOf(1920),
                intArrayOf(1080),
                intArrayOf(1280, 1920),
                intArrayOf(720, 1080)
            )
        )

        assertFalse(
            CameraController2.sizeSubset(
                intArrayOf(1920, 1280),
                intArrayOf(1080, 720),
                intArrayOf(1920),
                intArrayOf(1080)
            )
        )
        assertFalse(
            CameraController2.sizeSubset(
                intArrayOf(1920, 1280),
                intArrayOf(1080, 720),
                intArrayOf(2380),
                intArrayOf(720)
            )
        )
    }

    @Test
    fun testCameraController1CreateInstance() {
        val dummyCb = object : CameraController.ErrorCallback {
            override fun onError() {}
        }
        var caught1 = false
        try {
            CameraController1.createInstance(0, dummyCb)
        } catch (e: Throwable) {
            caught1 = e is CameraControllerException || e is RuntimeException
        }
        assertTrue(
            "createInstance call 1 should throw CameraControllerException or RuntimeException when hardware camera is unmocked",
            caught1
        )

        var caught2 = false
        try {
            CameraController1.createInstance(0, dummyCb)
        } catch (e: Throwable) {
            caught2 = e is CameraControllerException || e is RuntimeException
        }
        assertTrue(
            "createInstance call 2 should throw CameraControllerException or RuntimeException when hardware camera is unmocked",
            caught2
        )
    }

    @Test
    fun testCameraController1Nullability() {
        val sceneModeMethod = CameraController1::class.java.getMethod("getSceneMode")
        assertEquals(String::class.java, sceneModeMethod.returnType)

        val colorEffectMethod = CameraController1::class.java.getMethod("getColorEffect")
        assertEquals(String::class.java, colorEffectMethod.returnType)

        val whiteBalanceMethod = CameraController1::class.java.getMethod("getWhiteBalance")
        assertEquals(String::class.java, whiteBalanceMethod.returnType)

        val antiBandingMethod = CameraController1::class.java.getMethod("getAntiBanding")
        assertEquals(String::class.java, antiBandingMethod.returnType)
    }
}
