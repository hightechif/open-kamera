/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.system.PermissionHandler

/**
 * High-level manager coordinating runtime permission validation, SAF authorization,
 * and permission dispatch.
 */
class PermissionManager(private val mainActivity: MainActivity) {

    val permissionHandler: PermissionHandler = PermissionHandler(mainActivity)

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            mainActivity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            mainActivity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            mainActivity,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    mainActivity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || MainActivity.useScopedStorage()) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            mainActivity,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestCameraPermission() {
        permissionHandler.requestCameraPermission()
    }

    fun requestStoragePermission() {
        permissionHandler.requestStoragePermission()
    }

    fun requestRecordAudioPermission() {
        permissionHandler.requestRecordAudioPermission()
    }

    fun requestLocationPermission() {
        permissionHandler.requestLocationPermission()
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        permissionHandler.onRequestPermissionsResult(requestCode, grantResults)
    }
}
