/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.video

import com.hightechif.openkamera.preview.ApplicationInterface.VideoMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoSessionManagerUnitTest {

    private lateinit var manager: VideoSessionManager

    @Before
    fun setUp() {
        manager = VideoSessionManager()
    }

    @Test
    fun testStartSession_InitializesRecordingState() {
        val output = VideoSessionOutput(
            videoMethod = VideoMethod.FILE,
            videoFilename = "/storage/emulated/0/DCIM/Camera/VID_001.mp4"
        )

        manager.startSession(output, startTimeMs = 1000L)

        assertTrue(manager.isRecording)
        assertFalse(manager.isPaused)
        assertEquals(output, manager.activeOutput)
        assertEquals(1000L, manager.videoStartTime)
        assertEquals(0L, manager.videoAccumulatedTime)

        val duration = manager.calculateCurrentDurationMs(currentTimeMs = 4000L)
        assertEquals(3000L, duration)
    }

    @Test
    fun testPauseResume_DurationAccumulation() {
        val output = VideoSessionOutput(
            videoMethod = VideoMethod.FILE,
            videoFilename = "/storage/emulated/0/DCIM/Camera/VID_001.mp4"
        )

        manager.startSession(output, startTimeMs = 1000L)

        // Pause after 3 seconds (at t=4000L)
        manager.pauseSession(pauseTimeMs = 4000L)
        assertTrue(manager.isPaused)
        assertEquals(3000L, manager.videoAccumulatedTime)
        assertEquals(0L, manager.videoStartTime)

        // While paused, duration stays constant
        val pausedDuration = manager.calculateCurrentDurationMs(currentTimeMs = 8000L)
        assertEquals(3000L, pausedDuration)

        // Resume at t=10000L
        manager.resumeSession(resumeTimeMs = 10000L)
        assertFalse(manager.isPaused)
        assertEquals(10000L, manager.videoStartTime)

        // Record for another 2 seconds (at t=12000L)
        val finalDuration = manager.calculateCurrentDurationMs(currentTimeMs = 12000L)
        assertEquals(5000L, finalDuration)
    }

    @Test
    fun testSeamlessRestart_PrepareAndCommit() {
        val output1 = VideoSessionOutput(videoFilename = "file1.mp4")
        val output2 = VideoSessionOutput(videoFilename = "file2.mp4")

        manager.startSession(output1, startTimeMs = 1000L)
        assertEquals(output1, manager.activeOutput)
        assertNull(manager.nextOutput)

        // Max filesize approaching -> prepare next file
        manager.prepareSeamlessRestart(output2)
        assertEquals(output2, manager.nextOutput)
        assertTrue(manager.sessionState.value is VideoSessionState.RestartingMaxFileSize)

        // MediaRecorder seamlessly switches
        val committed = manager.commitSeamlessRestart()
        assertEquals(output2, committed)
        assertEquals(output2, manager.activeOutput)
        assertNull(manager.nextOutput)
    }

    @Test
    fun testStopSession_ResetsState() {
        val output = VideoSessionOutput(videoFilename = "file1.mp4")
        manager.startSession(output, startTimeMs = 1000L)

        manager.stopSession(cleanupOutputs = true)

        assertFalse(manager.isRecording)
        assertFalse(manager.isPaused)
        assertNull(manager.activeOutput)
        assertNull(manager.nextOutput)
        assertEquals(0L, manager.videoStartTime)
        assertEquals(0L, manager.videoAccumulatedTime)
        assertEquals(VideoSessionState.Stopped, manager.sessionState.value)
    }
}
