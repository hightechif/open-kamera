/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.lifecycle

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.hightechif.openkamera.utils.MyDebug
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Sealed states representing the lifecycle of a CameraDevice.
 */
sealed class CameraDeviceState {
    object Closed : CameraDeviceState()
    data class Opening(val cameraId: String) : CameraDeviceState()
    data class Opened(val cameraDevice: CameraDevice) : CameraDeviceState()
    data class Disconnected(val cameraDevice: CameraDevice) : CameraDeviceState()
    data class Error(
        val cameraDevice: CameraDevice?,
        val errorCode: Int,
        val message: String
    ) : CameraDeviceState()
}

/**
 * Sealed states representing the lifecycle of a CameraCaptureSession.
 */
sealed class CaptureSessionState {
    object Closed : CaptureSessionState()
    object Configuring : CaptureSessionState()
    data class Configured(val session: CameraCaptureSession) : CaptureSessionState()
    data class ConfigureFailed(val session: CameraCaptureSession) : CaptureSessionState()
    data class Ready(val session: CameraCaptureSession) : CaptureSessionState()
    data class Active(val session: CameraCaptureSession) : CaptureSessionState()
}

/**
 * Manages CameraDevice lifecycle, CameraCaptureSession / CameraExtensionSession lifecycle,
 * and state transitions.
 */
class Camera2SessionManager {

    companion object {
        private const val TAG = "Camera2SessionMgr"
    }

    private val sessionLock = Any()

    @Volatile
    var cameraDevice: CameraDevice? = null
        private set

    @Volatile
    var captureSession: CameraCaptureSession? = null
        private set

    @Volatile
    var extensionSession: Any? = null // Holds CameraExtensionSession on API 31+
        private set

    @Volatile
    var deviceState: CameraDeviceState = CameraDeviceState.Closed
        private set

    @Volatile
    var sessionState: CaptureSessionState = CaptureSessionState.Closed
        private set

    /**
     * Attaches an opened CameraDevice.
     */
    fun onCameraOpened(device: CameraDevice) {
        synchronized(sessionLock) {
            this.cameraDevice = device
            this.deviceState = CameraDeviceState.Opened(device)
        }
    }

    /**
     * Attaches a configured CameraCaptureSession.
     */
    fun onSessionConfigured(session: CameraCaptureSession) {
        synchronized(sessionLock) {
            this.captureSession = session
            this.sessionState = CaptureSessionState.Configured(session)
        }
    }

    /**
     * Attaches an extension session on Android S+.
     */
    fun onExtensionSessionConfigured(session: Any) {
        synchronized(sessionLock) {
            this.extensionSession = session
        }
    }

    /**
     * Safely closes active capture and extension sessions.
     */
    fun closeCaptureSession() {
        synchronized(sessionLock) {
            if (captureSession != null) {
                if (MyDebug.LOG) Log.d(TAG, "close capture session")
                try {
                    captureSession?.close()
                } catch (e: Throwable) {
                    if (MyDebug.LOG) Log.e(TAG, "failed to close capture session", e)
                }
                captureSession = null
            }
            if (extensionSession != null) {
                if (MyDebug.LOG) Log.d(TAG, "close extension session")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        (extensionSession as? CameraExtensionSession)?.close()
                    } catch (e: Throwable) {
                        if (MyDebug.LOG) Log.e(TAG, "failed to close extension session", e)
                    }
                }
                extensionSession = null
            }
            sessionState = CaptureSessionState.Closed
        }
    }

    /**
     * Safely closes the active CameraDevice.
     */
    fun closeCamera() {
        synchronized(sessionLock) {
            closeCaptureSession()
            if (cameraDevice != null) {
                if (MyDebug.LOG) Log.d(TAG, "close camera device")
                try {
                    cameraDevice?.close()
                } catch (e: Throwable) {
                    if (MyDebug.LOG) Log.e(TAG, "failed to close camera device", e)
                }
                cameraDevice = null
            }
            deviceState = CameraDeviceState.Closed
        }
    }

    /**
     * Creates a list of OutputConfiguration for given surfaces with optional physicalCameraId.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun createOutputConfigurations(
        surfaces: List<Surface>,
        physicalCameraId: String?
    ): List<OutputConfiguration> {
        val outputs = ArrayList<OutputConfiguration>(surfaces.size)
        for (surface in surfaces) {
            val config = OutputConfiguration(surface)
            if (physicalCameraId != null) {
                config.setPhysicalCameraId(physicalCameraId)
            }
            outputs.add(config)
        }
        return outputs
    }

    /**
     * Wraps camera opening into a suspendable Coroutine.
     */
    suspend fun openCameraAsync(
        manager: CameraManager,
        cameraId: String,
        handler: Handler?
    ): Result<CameraDevice> = suspendCancellableCoroutine { continuation ->
        try {
            deviceState = CameraDeviceState.Opening(cameraId)
            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    onCameraOpened(camera)
                    if (continuation.isActive) {
                        continuation.resume(Result.success(camera))
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    synchronized(sessionLock) {
                        cameraDevice = null
                        deviceState = CameraDeviceState.Disconnected(camera)
                    }
                    camera.close()
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(IllegalStateException("Camera $cameraId disconnected")))
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val message = "Camera open error code: $error"
                    synchronized(sessionLock) {
                        deviceState = CameraDeviceState.Error(camera, error, message)
                    }
                    camera.close()
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(IllegalStateException(message)))
                    }
                }
            }

            manager.openCamera(cameraId, callback, handler)
        } catch (e: SecurityException) {
            continuation.resume(Result.failure(e))
        } catch (e: CameraAccessException) {
            continuation.resume(Result.failure(e))
        } catch (e: Throwable) {
            continuation.resume(Result.failure(e))
        }
    }
}
