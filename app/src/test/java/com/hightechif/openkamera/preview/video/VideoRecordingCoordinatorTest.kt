/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.video

import android.media.MediaRecorder
import android.os.Build
import com.hightechif.openkamera.preview.VideoProfile
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class VideoRecordingCoordinatorTest {

    private lateinit var mockEngine: VideoRecorderEngine
    private lateinit var sessionManager: VideoSessionManager
    private lateinit var coordinator: VideoRecordingCoordinator
    private lateinit var mockListener: VideoRecordingCoordinator.VideoEventListener

    @Before
    fun setUp() {
        mockEngine = mockk(relaxed = true)
        sessionManager = VideoSessionManager()
        coordinator = VideoRecordingCoordinator(
            recorderEngine = mockEngine,
            sessionManager = sessionManager
        )
        mockListener = mockk(relaxed = true)
        coordinator.listener = mockListener
    }

    @Test
    fun `test startRecording successfully initializes engine and session`() {
        val tempFile = File.createTempFile("test_record", ".mp4")
        tempFile.deleteOnExit()
        val output = VideoSessionOutput(videoFilename = tempFile.absolutePath)
        val profile = VideoProfile()

        every { mockEngine.isRecording } returns true

        val success = coordinator.startRecording(
            profile = profile,
            output = output,
            maxFileSize = 5000000L,
            maxDurationMs = 30000L
        )

        assertTrue(success)
        assertTrue(coordinator.isRecording)
        verify { mockListener.onStartingVideo() }
        verify { mockEngine.prepare(any(), 5000000L, 30000L, any(), any(), any(), any(), any(), any(), any(), any()) }
        verify { mockEngine.start() }
        verify { mockListener.onStartedVideo() }
    }

    @Test
    fun `test pause and resume delegates to engine and session`() {
        val tempFile = File.createTempFile("test_pause", ".mp4")
        tempFile.deleteOnExit()
        val output = VideoSessionOutput(videoFilename = tempFile.absolutePath)
        val profile = VideoProfile()

        every { mockEngine.pause() } returns true
        every { mockEngine.resume() } returns true

        coordinator.startRecording(
            profile = profile,
            output = output,
            maxFileSize = 0L,
            maxDurationMs = 0L
        )

        val pauseResult = coordinator.pauseRecording()
        assertTrue(pauseResult)
        assertTrue(coordinator.isPaused)
        verify { mockEngine.pause() }

        val resumeResult = coordinator.resumeRecording()
        assertTrue(resumeResult)
        assertFalse(coordinator.isPaused)
        verify { mockEngine.resume() }
    }

    @Test
    fun `test stopRecording cleans up engine and updates session`() {
        val tempFile = File.createTempFile("test_stop", ".mp4")
        tempFile.deleteOnExit()
        val output = VideoSessionOutput(videoFilename = tempFile.absolutePath)
        val profile = VideoProfile()

        every { mockEngine.stop() } returns true

        coordinator.startRecording(
            profile = profile,
            output = output,
            maxFileSize = 0L,
            maxDurationMs = 0L
        )

        val stopped = coordinator.stopRecording(isIntentional = true)
        assertTrue(stopped)
        assertFalse(coordinator.isRecording)
        verify { mockEngine.stop() }
        verify { mockEngine.reset() }
        verify { mockEngine.release() }
        verify { mockListener.onStoppedVideo(any()) }
    }

    @Test
    fun `test handleVideoInfo for max duration triggers manual restart callback`() {
        coordinator.handleVideoInfo(
            what = MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED,
            extra = 0
        )

        verify { mockListener.onRequestManualRestart(isMaxFileSize = false) }
        verify { mockListener.onInfo(MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, 0) }
    }

    @Test
    fun `test handleVideoInfo for max file size approaching triggers seamless restart flow`() {
        val tempFile1 = File.createTempFile("vid1", ".mp4")
        val tempFile2 = File.createTempFile("vid2", ".mp4")
        tempFile1.deleteOnExit()
        tempFile2.deleteOnExit()

        val output1 = VideoSessionOutput(videoFilename = tempFile1.absolutePath)
        val output2 = VideoSessionOutput(videoFilename = tempFile2.absolutePath)
        val profile = VideoProfile().apply { fileExtension = "mp4" }

        every { mockListener.onCreateNextVideoFile("mp4") } returns output2

        coordinator.startRecording(profile, output1, 0L, 0L)

        coordinator.handleVideoInfo(
            what = MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING,
            extra = 0,
            autoRestartOnMaxFileSize = true,
            maxDurationPref = 0L
        )

        verify { mockEngine.setNextOutputFile(any<File>()) }
        assertEquals(output2, sessionManager.nextOutput)

        // Now simulate next output file started
        coordinator.handleVideoInfo(
            what = MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED,
            extra = 0,
            autoRestartOnMaxFileSize = true
        )

        verify { mockListener.onRestartedVideo(output2) }
        assertEquals(output2, sessionManager.activeOutput)
        assertNull(sessionManager.nextOutput)
    }
}
