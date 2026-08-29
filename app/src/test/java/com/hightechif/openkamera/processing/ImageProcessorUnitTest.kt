/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.processing

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageProcessorUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockContext = mockk<Context>(relaxed = true)
    private lateinit var imageProcessor: ImageProcessorImpl

    @Before
    fun setUp() {
        imageProcessor = ImageProcessorImpl(
            context = mockContext,
            defaultDispatcher = testDispatcher
        )
    }

    @Test
    fun processHdr_emptyFrames_returnsFailure() = runTest(testDispatcher) {
        val result = imageProcessor.processHdr(emptyList())
        assertTrue(result.isFailure)
    }

    @Test
    fun processHdr_singleFrame_returnsDirectBytes() = runTest(testDispatcher) {
        val sample = byteArrayOf(1, 2, 3, 4)
        val result = imageProcessor.processHdr(listOf(sample))
        assertTrue(result.isSuccess)
        assertArrayEquals(sample, result.getOrNull())
    }

    @Test
    fun processPanorama_emptyFrames_returnsFailure() = runTest(testDispatcher) {
        val result = imageProcessor.processPanorama(emptyList())
        assertTrue(result.isFailure)
    }

    @Test
    fun processPanorama_singleFrame_returnsDirectBytes() = runTest(testDispatcher) {
        val sample = byteArrayOf(5, 6, 7, 8)
        val result = imageProcessor.processPanorama(listOf(sample))
        assertTrue(result.isSuccess)
        assertArrayEquals(sample, result.getOrNull())
    }

    @Test
    fun processNoiseReduction_invalidBytes_returnsFailure() = runTest(testDispatcher) {
        val invalidSample = byteArrayOf(0, 0, 0)
        val result = imageProcessor.processNoiseReduction(invalidSample)
        assertTrue(result.isFailure)
    }
}
