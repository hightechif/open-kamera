package com.hightechif.openkamera.preview

import android.media.CamcorderProfile
import com.hightechif.openkamera.cameracontroller.CameraController
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQualityUnitTest {

    private fun compareVideoQuality(
        videoQuality: MutableList<String>,
        expVideoQuality: MutableList<String>
    ) {
        assertEquals(expVideoQuality.size.toLong(), videoQuality.size.toLong())
        for (i in videoQuality.indices) {
            val quality = videoQuality[i]
            val expQuality = expVideoQuality[i]
            assertEquals(expQuality, quality)
        }
    }

    @Test
    fun testVideoResolutions1() {
        val videoQualityHandler = VideoQualityHandler()

        val videoSizes: MutableList<CameraController.Size> = ArrayList()
        videoSizes.add(CameraController.Size(1920, 1080))
        videoSizes.add(CameraController.Size(1280, 720))
        videoSizes.add(CameraController.Size(1600, 900))
        videoQualityHandler.setVideoSizes(videoSizes)
        videoQualityHandler.sortVideoSizes()

        val profiles: MutableList<Int> = ArrayList()
        val dimensions: MutableList<VideoQualityHandler.Dimension2D> = ArrayList()
        profiles.add(CamcorderProfile.QUALITY_HIGH)
        dimensions.add(VideoQualityHandler.Dimension2D(1920, 1080))
        profiles.add(CamcorderProfile.QUALITY_1080P)
        dimensions.add(VideoQualityHandler.Dimension2D(1920, 1080))
        profiles.add(CamcorderProfile.QUALITY_720P)
        dimensions.add(VideoQualityHandler.Dimension2D(1280, 720))
        profiles.add(CamcorderProfile.QUALITY_LOW)
        dimensions.add(VideoQualityHandler.Dimension2D(1280, 720))
        videoQualityHandler.initialiseVideoQualityFromProfiles(profiles, dimensions)

        val videoQuality: MutableList<String> =
            videoQualityHandler.supportedVideoQuality.toMutableList()
        val expVideoQuality: MutableList<String> = ArrayList()
        expVideoQuality.add("" + CamcorderProfile.QUALITY_HIGH)
        expVideoQuality.add(CamcorderProfile.QUALITY_720P.toString() + "_r1600x900")
        expVideoQuality.add("" + CamcorderProfile.QUALITY_720P)
        compareVideoQuality(videoQuality, expVideoQuality)
    }

    @Test
    fun testVideoResolutions2() {
        val videoQualityHandler = VideoQualityHandler()

        val videoSizes: MutableList<CameraController.Size> = ArrayList()
        videoSizes.add(CameraController.Size(1920, 1080))
        videoSizes.add(CameraController.Size(1280, 720))
        videoSizes.add(CameraController.Size(1600, 900))
        videoQualityHandler.setVideoSizes(videoSizes)
        videoQualityHandler.sortVideoSizes()

        val profiles: MutableList<Int> = ArrayList()
        val dimensions: MutableList<VideoQualityHandler.Dimension2D> = ArrayList()
        profiles.add(CamcorderProfile.QUALITY_HIGH)
        dimensions.add(VideoQualityHandler.Dimension2D(1920, 1080))
        profiles.add(CamcorderProfile.QUALITY_720P)
        dimensions.add(VideoQualityHandler.Dimension2D(1280, 720))
        profiles.add(CamcorderProfile.QUALITY_LOW)
        dimensions.add(VideoQualityHandler.Dimension2D(1280, 720))
        videoQualityHandler.initialiseVideoQualityFromProfiles(profiles, dimensions)

        val videoQuality: MutableList<String> =
            videoQualityHandler.supportedVideoQuality.toMutableList()
        val expVideoQuality: MutableList<String> = ArrayList()
        expVideoQuality.add("" + CamcorderProfile.QUALITY_HIGH)
        expVideoQuality.add(CamcorderProfile.QUALITY_720P.toString() + "_r1600x900")
        expVideoQuality.add("" + CamcorderProfile.QUALITY_720P)
        compareVideoQuality(videoQuality, expVideoQuality)
    }

    @Test
    fun testVideoResolutions3() {
        val videoQualityHandler = VideoQualityHandler()

        val videoSizes: MutableList<CameraController.Size> = ArrayList()
        videoSizes.add(CameraController.Size(1920, 1080))
        videoSizes.add(CameraController.Size(1280, 720))
        videoSizes.add(CameraController.Size(960, 720))
        videoSizes.add(CameraController.Size(800, 480))
        videoSizes.add(CameraController.Size(720, 576))
        videoSizes.add(CameraController.Size(720, 480))
        videoSizes.add(CameraController.Size(768, 576))
        videoSizes.add(CameraController.Size(640, 480))
        videoSizes.add(CameraController.Size(320, 240))
        videoSizes.add(CameraController.Size(352, 288))
        videoSizes.add(CameraController.Size(240, 160))
        videoSizes.add(CameraController.Size(176, 144))
        videoSizes.add(CameraController.Size(128, 96))
        videoQualityHandler.setVideoSizes(videoSizes)
        videoQualityHandler.sortVideoSizes()

        val profiles: MutableList<Int> = ArrayList()
        val dimensions: MutableList<VideoQualityHandler.Dimension2D> = ArrayList()
        profiles.add(CamcorderProfile.QUALITY_HIGH)
        dimensions.add(VideoQualityHandler.Dimension2D(1920, 1080))
        profiles.add(CamcorderProfile.QUALITY_1080P)
        dimensions.add(VideoQualityHandler.Dimension2D(1920, 1080))
        profiles.add(CamcorderProfile.QUALITY_720P)
        dimensions.add(VideoQualityHandler.Dimension2D(1280, 720))
        profiles.add(CamcorderProfile.QUALITY_480P)
        dimensions.add(VideoQualityHandler.Dimension2D(720, 480))
        profiles.add(CamcorderProfile.QUALITY_CIF)
        dimensions.add(VideoQualityHandler.Dimension2D(352, 288))
        profiles.add(CamcorderProfile.QUALITY_QVGA)
        dimensions.add(VideoQualityHandler.Dimension2D(320, 240))
        profiles.add(CamcorderProfile.QUALITY_LOW)
        dimensions.add(VideoQualityHandler.Dimension2D(320, 240))
        videoQualityHandler.initialiseVideoQualityFromProfiles(profiles, dimensions)

        val videoQuality: MutableList<String> =
            videoQualityHandler.supportedVideoQuality.toMutableList()
        val expVideoQuality: MutableList<String> = ArrayList()
        expVideoQuality.add("" + CamcorderProfile.QUALITY_HIGH)
        expVideoQuality.add("" + CamcorderProfile.QUALITY_720P)
        expVideoQuality.add(CamcorderProfile.QUALITY_480P.toString() + "_r960x720")
        expVideoQuality.add(CamcorderProfile.QUALITY_480P.toString() + "_r768x576")
        expVideoQuality.add(CamcorderProfile.QUALITY_480P.toString() + "_r720x576")
        expVideoQuality.add(CamcorderProfile.QUALITY_480P.toString() + "_r800x480")
        expVideoQuality.add("" + CamcorderProfile.QUALITY_480P)
        expVideoQuality.add(CamcorderProfile.QUALITY_CIF.toString() + "_r640x480")
        expVideoQuality.add("" + CamcorderProfile.QUALITY_CIF)
        expVideoQuality.add("" + CamcorderProfile.QUALITY_QVGA)
        expVideoQuality.add(CamcorderProfile.QUALITY_LOW.toString() + "_r240x160")
        expVideoQuality.add(CamcorderProfile.QUALITY_LOW.toString() + "_r176x144")
        expVideoQuality.add(CamcorderProfile.QUALITY_LOW.toString() + "_r128x96")
        compareVideoQuality(videoQuality, expVideoQuality)
    }

    @Test
    fun testVideoResolutions4() {
        val videoQualityHandler = VideoQualityHandler()

        val videoSizes: MutableList<CameraController.Size> = ArrayList()
        videoSizes.add(CameraController.Size(176, 144))
        videoSizes.add(CameraController.Size(480, 320))
        videoSizes.add(CameraController.Size(640, 480))
        videoSizes.add(CameraController.Size(864, 480))
        videoSizes.add(CameraController.Size(1280, 720))
        videoSizes.add(CameraController.Size(1920, 1080))
        videoQualityHandler.setVideoSizes(videoSizes)
        videoQualityHandler.sortVideoSizes()

        val profiles: MutableList<Int> = ArrayList()
        val dimensions: MutableList<VideoQualityHandler.Dimension2D> = ArrayList()
        profiles.add(CamcorderProfile.QUALITY_HIGH)
        dimensions.add(VideoQualityHandler.Dimension2D(1920, 1080))
        profiles.add(CamcorderProfile.QUALITY_480P)
        dimensions.add(VideoQualityHandler.Dimension2D(640, 480))
        profiles.add(CamcorderProfile.QUALITY_QCIF)
        dimensions.add(VideoQualityHandler.Dimension2D(176, 144))
        videoQualityHandler.initialiseVideoQualityFromProfiles(profiles, dimensions)

        val videoQuality: MutableList<String> =
            videoQualityHandler.supportedVideoQuality.toMutableList()
        val expVideoQuality: MutableList<String> = ArrayList()
        expVideoQuality.add("" + CamcorderProfile.QUALITY_HIGH)
        expVideoQuality.add(CamcorderProfile.QUALITY_480P.toString() + "_r1280x720")
        expVideoQuality.add(CamcorderProfile.QUALITY_480P.toString() + "_r864x480")
        expVideoQuality.add("" + CamcorderProfile.QUALITY_480P)
        expVideoQuality.add(CamcorderProfile.QUALITY_QCIF.toString() + "_r480x320")
        expVideoQuality.add("" + CamcorderProfile.QUALITY_QCIF)
        compareVideoQuality(videoQuality, expVideoQuality)
    }
}
