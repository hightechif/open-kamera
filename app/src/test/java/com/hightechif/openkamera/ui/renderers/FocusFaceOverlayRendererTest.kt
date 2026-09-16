/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import android.graphics.Rect
import com.hightechif.openkamera.cameracontroller.CameraController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusFaceOverlayRendererTest {

    @Test
    fun testFaceRectBoundaries() {
        val faceRect = Rect(-500, -500, 500, 500)
        val face = CameraController.Face(50, faceRect)
        assertEquals(50, face.score)
        assertEquals(1000, face.rect.width())
        assertEquals(1000, face.rect.height())
    }
}
