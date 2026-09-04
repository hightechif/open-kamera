/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveTaskTest {

    @Test
    fun testJpegSaveTaskCostCalculation() {
        val singleJpeg = SaveTask.SaveJpeg(
            jpegImages = mutableListOf(ByteArray(1024))
        )
        assertTrue(singleJpeg.isRealImage)
        assertEquals(1, singleJpeg.cost)

        val burstJpeg = SaveTask.SaveJpeg(
            jpegImages = mutableListOf(ByteArray(100), ByteArray(100), ByteArray(100))
        )
        assertTrue(burstJpeg.isRealImage)
        assertEquals(3, burstJpeg.cost)
    }

    @Test
    fun testRawSaveTaskCostCalculation() {
        val rawTask = SaveTask.SaveRaw(rawImage = null)
        assertTrue(rawTask.isRealImage)
        assertEquals(70, rawTask.cost)
    }

    @Test
    fun testDummyAndOnDestroyTasksAreNotRealImages() {
        val dummy = SaveTask.Dummy()
        assertFalse(dummy.isRealImage)
        assertEquals(1, dummy.cost)

        val onDestroy = SaveTask.OnDestroy()
        assertFalse(onDestroy.isRealImage)
        assertEquals(1, onDestroy.cost)
    }

    @Test
    fun testQueueStateInvariants() {
        val state = ImageSaveQueueState(
            pendingCount = 5,
            realImageCount = 3,
            isProcessing = true,
            isBlocked = false,
            queueCapacity = 20
        )
        assertFalse(state.isIdle)
        assertEquals(5, state.pendingCount)
        assertEquals(3, state.realImageCount)

        val idleState = ImageSaveQueueState(
            pendingCount = 0,
            realImageCount = 0,
            isProcessing = false,
            isBlocked = false,
            queueCapacity = 20
        )
        assertTrue(idleState.isIdle)
    }
}
