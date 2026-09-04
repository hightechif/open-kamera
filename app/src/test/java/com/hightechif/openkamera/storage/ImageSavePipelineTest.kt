/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ImageSavePipelineTest {

    @Test
    fun testPipelineQueueSizeComputation() {
        assertEquals(8, ImageSavePipeline.computeQueueSize(64))
        assertEquals(16, ImageSavePipeline.computeQueueSize(128))
        assertEquals(32, ImageSavePipeline.computeQueueSize(256))
    }

    @Test
    fun testPipelineTaskSubmissionAndDraining() = runTest {
        val executedCount = AtomicInteger(0)
        val pipeline = ImageSavePipeline(
            queueCapacity = 10,
            maxConcurrentWorkers = 2,
            defaultDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            taskExecutor = {
                executedCount.incrementAndGet()
                true
            }
        )

        val task1 = SaveTask.Dummy()
        val task2 = SaveTask.Dummy()

        assertTrue(pipeline.submit(task1))
        assertTrue(pipeline.submit(task2))

        assertEquals(2, pipeline.currentPendingCount)
        advanceUntilIdle()

        assertEquals(2, executedCount.get())
        assertEquals(0, pipeline.currentPendingCount)

        pipeline.destroy()
    }

    @Test
    fun testPipelineQueueWouldBlock() {
        val pipeline = ImageSavePipeline(queueCapacity = 5)
        assertFalse(pipeline.queueWouldBlock(1))

        // Submit tasks up to capacity
        repeat(5) {
            pipeline.submit(SaveTask.Dummy())
        }

        assertTrue(pipeline.queueWouldBlock(2))
        pipeline.destroy()
    }
}
