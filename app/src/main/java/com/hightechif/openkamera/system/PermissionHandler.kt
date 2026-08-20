package com.hightechif.openkamera.system

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.utils.MyDebug


/** Android 6+ permission handling:
 */
class PermissionHandler internal constructor(private val mainActivity: MainActivity) {
    private var cameraDenied = false // whether the user requested to deny a camera permission
    private var cameraDeniedTimeMs: Long = 0 // if denied, the time when this occurred
    private var storageDenied = false // whether the user requested to deny a camera permission
    private var storageDeniedTimeMs: Long = 0 // if denied, the time when this occurred
    private var audioDenied = false // whether the user requested to deny a camera permission
    private var audioDeniedTimeMs: Long = 0 // if denied, the time when this occurred
    private var locationDenied = false // whether the user requested to deny a camera permission
    private var locationDeniedTimeMs: Long = 0 // if denied, the time when this occurred

    /** Show a "rationale" to the user for needing a particular permission, then request that permission again
     * once they close the dialog.
     */
    private fun showRequestPermissionRationale(permissionCode: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "showRequestPermissionRational: $permissionCode"
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (MyDebug.LOG) Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!")
            return
        }

        var ok = true
        var permissions: Array<String?>? = null
        var messageId = 0
        when (permissionCode) {
            MY_PERMISSIONS_REQUEST_CAMERA -> {
                if (MyDebug.LOG) Log.d(TAG, "display rationale for camera permission")
                permissions = arrayOf(Manifest.permission.CAMERA)
                messageId = R.string.permission_rationale_camera
            }

            MY_PERMISSIONS_REQUEST_STORAGE -> {
                if (MyDebug.LOG) Log.d(TAG, "display rationale for storage permission")
                permissions = arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                messageId = R.string.permission_rationale_storage
            }

            MY_PERMISSIONS_REQUEST_RECORD_AUDIO -> {
                if (MyDebug.LOG) Log.d(TAG, "display rationale for record audio permission")
                permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
                messageId = R.string.permission_rationale_record_audio
            }

            MY_PERMISSIONS_REQUEST_LOCATION -> {
                if (MyDebug.LOG) Log.d(TAG, "display rationale for location permission")
                permissions = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                messageId = R.string.permission_rationale_location
            }

            else -> {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "showRequestPermissionRational unknown permission_code: $permissionCode"
                )
                ok = false
            }
        }

        if (ok) {
            val permissionsF = permissions!!
            AlertDialog.Builder(mainActivity)
                .setTitle(R.string.permission_rationale_title)
                .setMessage(messageId)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "requesting permission..."
                    )
                    ActivityCompat.requestPermissions(mainActivity, permissionsF, permissionCode)
                }.show()
        }
    }

    fun requestCameraPermission() {
        if (MyDebug.LOG) Log.d(TAG, "requestCameraPermission")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (MyDebug.LOG) Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!")
            return
        } else if (cameraDenied && System.currentTimeMillis() < cameraDeniedTimeMs + DENY_DELAY_MS) {
            if (MyDebug.LOG) Log.d(TAG, "too soon since user last denied permission")
            return
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(
                mainActivity,
                Manifest.permission.CAMERA
            )
        ) {
            // Show an explanation to the user *asynchronously* -- don't block
            // this thread waiting for the user's response! After the user
            // sees the explanation, try again to request the permission.
            showRequestPermissionRationale(MY_PERMISSIONS_REQUEST_CAMERA)
        } else {
            // Can go ahead and request the permission
            if (MyDebug.LOG) Log.d(TAG, "requesting camera permission...")
            ActivityCompat.requestPermissions(
                mainActivity,
                arrayOf(Manifest.permission.CAMERA),
                MY_PERMISSIONS_REQUEST_CAMERA
            )
        }
    }

    fun requestStoragePermission() {
        if (MyDebug.LOG) Log.d(TAG, "requestStoragePermission")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (MyDebug.LOG) Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!")
            return
        } else if (MainActivity.useScopedStorage()) {
            if (MyDebug.LOG) Log.e(TAG, "shouldn't be requesting permissions for scoped storage!")
            return
        } else if (storageDenied && System.currentTimeMillis() < storageDeniedTimeMs + DENY_DELAY_MS) {
            if (MyDebug.LOG) Log.d(TAG, "too soon since user last denied permission")
            return
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(
                mainActivity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) {
            // Show an explanation to the user *asynchronously* -- don't block
            // this thread waiting for the user's response! After the user
            // sees the explanation, try again to request the permission.
            showRequestPermissionRationale(MY_PERMISSIONS_REQUEST_STORAGE)
        } else {
            // Can go ahead and request the permission
            if (MyDebug.LOG) Log.d(TAG, "requesting storage permission...")
            ActivityCompat.requestPermissions(
                mainActivity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                MY_PERMISSIONS_REQUEST_STORAGE
            )
        }
    }

    fun requestRecordAudioPermission() {
        if (MyDebug.LOG) Log.d(TAG, "requestRecordAudioPermission")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (MyDebug.LOG) Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!")
            return
        } else if (audioDenied && System.currentTimeMillis() < audioDeniedTimeMs + DENY_DELAY_MS) {
            if (MyDebug.LOG) Log.d(TAG, "too soon since user last denied permission")
            return
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(
                mainActivity,
                Manifest.permission.RECORD_AUDIO
            )
        ) {
            // Show an explanation to the user *asynchronously* -- don't block
            // this thread waiting for the user's response! After the user
            // sees the explanation, try again to request the permission.
            showRequestPermissionRationale(MY_PERMISSIONS_REQUEST_RECORD_AUDIO)
        } else {
            // Can go ahead and request the permission
            if (MyDebug.LOG) Log.d(TAG, "requesting record audio permission...")
            ActivityCompat.requestPermissions(
                mainActivity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MY_PERMISSIONS_REQUEST_RECORD_AUDIO
            )
        }
    }

    fun requestLocationPermission() {
        if (MyDebug.LOG) Log.d(TAG, "requestLocationPermission")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (MyDebug.LOG) Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!")
            return
        } else if (locationDenied && System.currentTimeMillis() < locationDeniedTimeMs + DENY_DELAY_MS) {
            if (MyDebug.LOG) Log.d(TAG, "too soon since user last denied permission")
            return
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(
                mainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) ||
            ActivityCompat.shouldShowRequestPermissionRationale(
                mainActivity,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        ) {
            // Show an explanation to the user *asynchronously* -- don't block
            // this thread waiting for the user's response! After the user
            // sees the explanation, try again to request the permission.
            showRequestPermissionRationale(MY_PERMISSIONS_REQUEST_LOCATION)
        } else {
            // Can go ahead and request the permission
            if (MyDebug.LOG) Log.d(TAG, "requesting location permissions...")
            ActivityCompat.requestPermissions(
                mainActivity,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                MY_PERMISSIONS_REQUEST_LOCATION
            )
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onRequestPermissionsResult: requestCode $requestCode"
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (MyDebug.LOG) Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!")
            return
        }

        when (requestCode) {
            MY_PERMISSIONS_REQUEST_CAMERA -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty()
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    if (MyDebug.LOG) Log.d(TAG, "camera permission granted")
                    mainActivity.preview.retryOpenKamera()
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "camera permission denied")
                    cameraDenied = true
                    cameraDeniedTimeMs = System.currentTimeMillis()
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    // Open Kamera doesn't need to do anything: the camera will remain closed
                }
                return
            }

            MY_PERMISSIONS_REQUEST_STORAGE -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty()
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    if (MyDebug.LOG) Log.d(TAG, "storage permission granted")
                    mainActivity.preview.retryOpenKamera()
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "storage permission denied")
                    storageDenied = true
                    storageDeniedTimeMs = System.currentTimeMillis()
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    // Open Kamera doesn't need to do anything: the camera will remain closed
                }
                return
            }

            MY_PERMISSIONS_REQUEST_RECORD_AUDIO -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty()
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    if (MyDebug.LOG) Log.d(TAG, "record audio permission granted")
                    // no need to do anything
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "record audio permission denied")
                    audioDenied = true
                    audioDeniedTimeMs = System.currentTimeMillis()
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    // no need to do anything
                    // note that we don't turn off record audio option, as user may then record video not realising audio won't be recorded - best to be explicit each time
                }
                return
            }

            MY_PERMISSIONS_REQUEST_LOCATION -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.size == 2 && (grantResults[0] == PackageManager.PERMISSION_GRANTED || grantResults[1] == PackageManager.PERMISSION_GRANTED)) {
                    // On Android 12 users can choose to only grant approximation location. This means
                    // one of the permissions will be denied, but as long as one location permission
                    // is granted, we can still go ahead and use location.
                    // Otherwise, we have a problem that if user selects approximate location, we end
                    // up turning the location option back off.
                    if (MyDebug.LOG) Log.d(TAG, "location permission granted [1]")
                    mainActivity.initLocation()
                } else if (grantResults.isNotEmpty()
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    // in theory this code path is now redundant, but keep here just in case
                    if (MyDebug.LOG) Log.d(TAG, "location permission granted [2]")
                    mainActivity.initLocation()
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "location permission denied")
                    locationDenied = true
                    locationDeniedTimeMs = System.currentTimeMillis()
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    // for location, seems best to turn the option back off
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "location permission not available, so switch location off"
                    )
                    mainActivity.preview
                        .showToast(null, R.string.permission_location_not_available)
                    val settings = PreferenceManager.getDefaultSharedPreferences(mainActivity)
                    settings.edit {
                        putBoolean(PreferenceKeys.LOCATION_PREFERENCE_KEY, false)
                    }
                }
                return
            }

            else -> {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "unknown requestCode $requestCode"
                )
            }
        }
    }

    companion object {
        private const val TAG = "PermissionHandler"

        private const val MY_PERMISSIONS_REQUEST_CAMERA = 0
        private const val MY_PERMISSIONS_REQUEST_STORAGE = 1
        private const val MY_PERMISSIONS_REQUEST_RECORD_AUDIO = 2
        private const val MY_PERMISSIONS_REQUEST_LOCATION = 3

        // In some cases there can be a problem if the user denies a permission, we then get an onResume()
        // (since application goes into background when showing system UI to request permission) at which
        // point we try to request permission again! This would happen for camera and storage permissions.
        // Whilst that isn't necessarily wrong, there would also be a problem if the user says
        // "Don't ask again", we get stuck in a loop repeatedly asking the OS for permission (and it
        // repeatedly being automatically denied) causing the UI to become sluggish.
        // So instead we only try asking again if not within denyDelayMs of the user denying that
        // permission.
        // Time shouldn't be too long, as the user might restart and then not be asked again for camera
        // or storage permission.
        private const val DENY_DELAY_MS: Long = 1000
    }
}
