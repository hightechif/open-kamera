/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hightechif.openkamera.domain.engine.CaptureProgress
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.GridType
import com.hightechif.openkamera.domain.repository.IMediaRepository
import com.hightechif.openkamera.domain.repository.ISensorRepository
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import com.hightechif.openkamera.domain.usecase.AdjustExposureUseCase
import com.hightechif.openkamera.domain.usecase.CapturePhotoUseCase
import com.hightechif.openkamera.domain.usecase.GetCameraCapabilitiesUseCase
import com.hightechif.openkamera.domain.usecase.RecordVideoUseCase
import com.hightechif.openkamera.domain.usecase.SetZoomUseCase
import com.hightechif.openkamera.domain.usecase.SwitchCameraFacingUseCase
import com.hightechif.openkamera.domain.usecase.TapToFocusUseCase
import com.hightechif.openkamera.domain.usecase.ToggleFlashUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val capturePhotoUseCase: CapturePhotoUseCase,
    private val recordVideoUseCase: RecordVideoUseCase,
    private val adjustExposureUseCase: AdjustExposureUseCase,
    private val toggleFlashUseCase: ToggleFlashUseCase,
    private val setZoomUseCase: SetZoomUseCase,
    private val tapToFocusUseCase: TapToFocusUseCase,
    private val switchCameraFacingUseCase: SwitchCameraFacingUseCase,
    private val getCameraCapabilitiesUseCase: GetCameraCapabilitiesUseCase,
    private val settingsRepository: ISettingsRepository,
    private val mediaRepository: IMediaRepository,
    private val sensorRepository: ISensorRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<CameraUiEffect>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEffect: SharedFlow<CameraUiEffect> = _uiEffect.asSharedFlow()

    init {
        observeRepositories()
    }

    private fun observeRepositories() {
        viewModelScope.launch {
            mediaRepository.latestMediaThumbnailFlow.collectLatest { uri ->
                _uiState.update { it.copy(latestThumbnailUri = uri) }
            }
        }

        viewModelScope.launch {
            sensorRepository.sensorOrientationFlow.collectLatest { orientation ->
                _uiState.update {
                    it.copy(
                        horizonAngle = orientation.horizonAngle,
                        compassDegrees = orientation.compassDegrees
                    )
                }
            }
        }

        viewModelScope.launch {
            adjustExposureUseCase.exposureCompensationFlow.collectLatest { exposure ->
                _uiState.update { it.copy(exposureCompensation = exposure) }
            }
        }

        viewModelScope.launch {
            setZoomUseCase.currentZoomRatio.collectLatest { zoom ->
                _uiState.update { it.copy(zoomRatio = zoom) }
            }
        }

        viewModelScope.launch {
            setZoomUseCase.maxZoomRatio.collectLatest { maxZoom ->
                _uiState.update { it.copy(maxZoomRatio = maxZoom) }
            }
        }

        viewModelScope.launch {
            settingsRepository.flashModeFlow.collectLatest { flash ->
                _uiState.update { it.copy(flashMode = flash) }
            }
        }

        viewModelScope.launch {
            settingsRepository.gridTypeFlow.collectLatest { grid ->
                _uiState.update { it.copy(gridType = grid) }
            }
        }

        viewModelScope.launch {
            settingsRepository.captureModeFlow.collectLatest { mode ->
                _uiState.update { it.copy(captureMode = mode) }
            }
        }

        viewModelScope.launch {
            settingsRepository.isRawEnabledFlow.collectLatest { isRaw ->
                _uiState.update { it.copy(isRawEnabled = isRaw) }
            }
        }
    }

    fun onEvent(event: CameraUiEvent) {
        when (event) {
            is CameraUiEvent.OnShutterClicked -> handleShutterClicked()
            is CameraUiEvent.OnRecordVideoClicked -> handleRecordVideoClicked()
            is CameraUiEvent.OnSwitchCameraClicked -> handleSwitchCameraClicked()
            is CameraUiEvent.OnFlashModeToggleClicked -> handleFlashToggleClicked()
            is CameraUiEvent.OnZoomChanged -> handleZoomChanged(event.ratio)
            is CameraUiEvent.OnTapToFocus -> handleTapToFocus(event)
            is CameraUiEvent.OnExposureStepChanged -> handleExposureStepChanged(event.step)
            is CameraUiEvent.OnCaptureModeSelected -> handleCaptureModeSelected(event.mode)
            is CameraUiEvent.OnGridTypeChanged -> handleGridTypeChanged(event.gridType)
            is CameraUiEvent.OnRawToggled -> handleRawToggled(event.enabled)
            is CameraUiEvent.OnGalleryThumbnailClicked -> handleGalleryThumbnailClicked()
            is CameraUiEvent.OnSettingsClicked -> {
                _uiEffect.tryEmit(CameraUiEffect.OpenSettings)
            }
        }
    }

    private fun handleShutterClicked() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true) }
            val config = CaptureConfig(
                captureMode = _uiState.value.captureMode,
                flashMode = _uiState.value.flashMode,
                enableRaw = _uiState.value.isRawEnabled
            )

            capturePhotoUseCase(config).collect { progress ->
                when (progress) {
                    is CaptureProgress.Completed -> {
                        _uiState.update { it.copy(isCapturing = false) }
                        _uiEffect.tryEmit(CameraUiEffect.Vibrate(50))
                    }

                    is CaptureProgress.Failed -> {
                        _uiState.update { it.copy(isCapturing = false) }
                        _uiEffect.tryEmit(CameraUiEffect.ShowToast("Capture failed: ${progress.cause.message}"))
                    }

                    else -> {
                        // Intermediate progress
                    }
                }
            }
        }
    }

    private fun handleRecordVideoClicked() {
        viewModelScope.launch {
            if (_uiState.value.isRecording) {
                val stopResult = recordVideoUseCase.stopRecording()
                _uiState.update { it.copy(isRecording = false, recordingDurationSeconds = 0L) }
                if (stopResult.isSuccess) {
                    _uiEffect.tryEmit(CameraUiEffect.ShowToast("Video saved"))
                } else {
                    _uiEffect.tryEmit(CameraUiEffect.ShowToast("Failed to save video"))
                }
            } else {
                val startResult = recordVideoUseCase.startRecording()
                if (startResult.isSuccess) {
                    _uiState.update { it.copy(isRecording = true) }
                    _uiEffect.tryEmit(CameraUiEffect.Vibrate(100))
                } else {
                    _uiEffect.tryEmit(CameraUiEffect.ShowToast("Failed to start recording"))
                }
            }
        }
    }

    private fun handleSwitchCameraClicked() {
        viewModelScope.launch {
            val result = switchCameraFacingUseCase()
            if (result.isSuccess) {
                val newFacing = result.getOrThrow()
                _uiState.update { it.copy(facing = newFacing) }
            } else {
                _uiEffect.tryEmit(CameraUiEffect.ShowToast("Failed to switch camera"))
            }
        }
    }

    private fun handleFlashToggleClicked() {
        viewModelScope.launch {
            val nextMode = toggleFlashUseCase()
            _uiState.update { it.copy(flashMode = nextMode) }
        }
    }

    private fun handleZoomChanged(ratio: Float) {
        viewModelScope.launch {
            setZoomUseCase(ratio)
        }
    }

    private fun handleTapToFocus(event: CameraUiEvent.OnTapToFocus) {
        viewModelScope.launch {
            tapToFocusUseCase.focusAtPoint(event.point)
        }
    }

    private fun handleExposureStepChanged(step: Int) {
        viewModelScope.launch {
            adjustExposureUseCase(step)
        }
    }

    private fun handleCaptureModeSelected(mode: CaptureMode) {
        viewModelScope.launch {
            settingsRepository.setCaptureMode(mode)
            _uiState.update { it.copy(captureMode = mode) }
        }
    }

    private fun handleGridTypeChanged(gridType: GridType) {
        viewModelScope.launch {
            settingsRepository.setGridType(gridType)
            _uiState.update { it.copy(gridType = gridType) }
        }
    }

    private fun handleRawToggled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRawEnabled(enabled)
            _uiState.update { it.copy(isRawEnabled = enabled) }
        }
    }

    private fun handleGalleryThumbnailClicked() {
        val uri = _uiState.value.latestThumbnailUri
        if (uri != null) {
            _uiEffect.tryEmit(CameraUiEffect.NavigateToGallery(uri))
        } else {
            _uiEffect.tryEmit(CameraUiEffect.ShowToast("No photos or videos yet"))
        }
    }
}
