/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.widget.ImageButton
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hightechif.openkamera.domain.repository.ILocationRepository
import com.hightechif.openkamera.domain.repository.IMediaRepository
import com.hightechif.openkamera.domain.repository.ISensorRepository
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import com.hightechif.openkamera.ui.CameraUiEffect
import com.hightechif.openkamera.ui.CameraViewModel
import com.hightechif.openkamera.ui.SettingsViewModel
import com.hightechif.openkamera.utils.MyDebug
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The main Activity for Open Kamera.
 * Clean, modern MVVM lifecycle host coordinating ViewModels and user interaction flows.
 */
@AndroidEntryPoint
class MainActivity : MainActivityLegacyGlue() {


    override val cameraViewModel: CameraViewModel by viewModels()
    override val settingsViewModel: SettingsViewModel by viewModels()

    @Inject
    lateinit var injectedSettingsRepository: ISettingsRepository

    @Inject
    lateinit var injectedMediaRepository: IMediaRepository

    @Inject
    lateinit var injectedLocationRepository: ILocationRepository

    @Inject
    lateinit var injectedSensorRepository: ISensorRepository

    @Inject
    lateinit var injectedRemoteInputManager: com.hightechif.openkamera.domain.engine.IRemoteInputManager

    @Inject
    lateinit var injectedAudioController: com.hightechif.openkamera.domain.engine.IAudioController

    @Inject
    lateinit var injectedCameraPreferencesRepository: com.hightechif.openkamera.domain.repository.preferences.CameraPreferencesRepository

    @Inject
    lateinit var injectedPhotoPreferencesRepository: com.hightechif.openkamera.domain.repository.preferences.PhotoPreferencesRepository

    @Inject
    lateinit var injectedVideoPreferencesRepository: com.hightechif.openkamera.domain.repository.preferences.VideoPreferencesRepository

    @Inject
    lateinit var injectedUiHudPreferencesRepository: com.hightechif.openkamera.domain.repository.preferences.UiHudPreferencesRepository

    @Inject
    lateinit var injectedLocationPreferencesRepository: com.hightechif.openkamera.domain.repository.preferences.LocationPreferencesRepository

    override val settingsRepository: ISettingsRepository get() = injectedSettingsRepository
    override val mediaRepository: IMediaRepository get() = injectedMediaRepository
    override val locationRepository: ILocationRepository get() = injectedLocationRepository
    override val sensorRepository: ISensorRepository get() = injectedSensorRepository
    override val remoteInputManager: com.hightechif.openkamera.domain.engine.IRemoteInputManager get() = injectedRemoteInputManager
    override val audioController: com.hightechif.openkamera.domain.engine.IAudioController get() = injectedAudioController
    override val cameraPreferencesRepository: com.hightechif.openkamera.domain.repository.preferences.CameraPreferencesRepository get() = injectedCameraPreferencesRepository
    override val photoPreferencesRepository: com.hightechif.openkamera.domain.repository.preferences.PhotoPreferencesRepository get() = injectedPhotoPreferencesRepository
    override val videoPreferencesRepository: com.hightechif.openkamera.domain.repository.preferences.VideoPreferencesRepository get() = injectedVideoPreferencesRepository
    override val uiHudPreferencesRepository: com.hightechif.openkamera.domain.repository.preferences.UiHudPreferencesRepository get() = injectedUiHudPreferencesRepository
    override val locationPreferencesRepository: com.hightechif.openkamera.domain.repository.preferences.LocationPreferencesRepository get() = injectedLocationPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "onCreate: $this")
            debugTime = System.currentTimeMillis()
        }
        activityCount++
        if (MyDebug.LOG) Log.d(TAG, "activity_count: $activityCount")
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        initLegacy(savedInstanceState, debugTime)

        observeCameraViewModel()

        if (MyDebug.LOG) {
            Log.d(TAG, "onCreate: total time for Activity startup: " + (System.currentTimeMillis() - debugTime))
        }
    }

    private fun observeCameraViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    cameraViewModel.uiState.collect { state ->
                        mainUI.applyUiState(state)

                        state.latestThumbnailUri?.let { uri ->
                            try {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    val bitmap = BitmapFactory.decodeStream(stream)
                                    if (bitmap != null) {
                                        val galleryButton = findViewById<ImageButton>(R.id.gallery)
                                        galleryButton?.setImageBitmap(bitmap)
                                        galleryBitmap = bitmap
                                    }
                                }
                            } catch (_: Exception) {
                                // Fallback silently
                            }
                        }

                        applicationInterface.drawPreview.updateSettings()
                    }
                }
                launch {
                    cameraViewModel.captureState.collect { progress ->
                        val takePhotoButton = findViewById<ImageButton>(R.id.take_photo)
                        when (progress) {
                            is com.hightechif.openkamera.domain.engine.CaptureProgress.Starting,
                            is com.hightechif.openkamera.domain.engine.CaptureProgress.CapturingBurst,
                            is com.hightechif.openkamera.domain.engine.CaptureProgress.Processing -> {
                                takePhotoButton?.animate()?.scaleX(0.88f)?.scaleY(0.88f)
                                    ?.setDuration(70)?.start()
                            }

                            is com.hightechif.openkamera.domain.engine.CaptureProgress.Idle,
                            is com.hightechif.openkamera.domain.engine.CaptureProgress.Completed,
                            is com.hightechif.openkamera.domain.engine.CaptureProgress.Failed -> {
                                takePhotoButton?.animate()?.scaleX(1.0f)?.scaleY(1.0f)
                                    ?.setDuration(100)?.start()
                            }
                        }
                    }
                }
                launch {
                    cameraViewModel.uiEffect.collect { effect ->
                        when (effect) {
                            is CameraUiEffect.ShowToast -> {
                                preview.showToast(null, effect.message)
                            }

                            is CameraUiEffect.Vibrate -> {
                                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }

                            is CameraUiEffect.NavigateToGallery -> {
                                clickedGallery(null)
                            }

                            is CameraUiEffect.OpenSettings -> {
                                clickedSettings(null)
                            }

                            is CameraUiEffect.ShowErrorDialog -> {
                                preview.showToast(null, "${effect.title}: ${effect.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onResume() {
        super.onResume()
        performLegacyResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        cameraLifecycleCoordinator.onWindowFocusChanged(hasFocus)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onPause() {
        super.onPause()
        performLegacyPause()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStop() {
        super.onStop()
        performLegacyStop()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onDestroy() {
        performLegacyDestroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        performLegacyConfigurationChanged(newConfig)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        performLegacySaveInstanceState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyEventHandler.onKeyDown(keyCode, event)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyEventHandler.onKeyUp(keyCode, event)) true else super.onKeyUp(keyCode, event)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionManager.onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        performLegacyActivityResult(requestCode, resultCode, resultData)
    }

    companion object {
        @JvmStatic
        fun useScopedStorage(): Boolean = com.hightechif.openkamera.useScopedStorage()
    }
}
