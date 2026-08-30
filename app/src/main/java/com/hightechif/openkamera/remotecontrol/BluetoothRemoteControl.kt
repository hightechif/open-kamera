/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.remotecontrol

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.ui.MainUI
import com.hightechif.openkamera.utils.MyDebug
import kotlin.math.roundToInt

/** Class for handling the Bluetooth LE remote control functionality.
 */
class BluetoothRemoteControl(private val mainActivity: MainActivity) {

    private var bluetoothLeService: BluetoothLeService? = null
    private var remoteDeviceAddress: String? = null
    private var remoteDeviceType: String? = null
    private var isConnected = false

    // class to manage the Service lifecycle for remote control.
    private val mServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            if (MyDebug.LOG) Log.d(TAG, "onServiceConnected")
            if (mainActivity.isAppPaused) {
                if (MyDebug.LOG) Log.d(TAG, "but app is now paused")
                // Unclear if this could happen - possibly if app pauses immediately after starting
                // the service, but before we connect? In theory, we should then unbind the service,
                // but seems safer not to try to call initialize or connect.
                // This will mean the BluetoothLeService still thinks it's unbound (isBound will
                // be left false), but find, that just means we'll enforce not trying to connect at
                // a later stage).
                return
            }
            if (!DeviceScanner.useAndroid12BluetoothPermissions()) {
                if (MyDebug.LOG) Log.e(TAG, "bluetooth remote control requires Android 12+")
                // in theory not needed as mServiceConnection is not used if remoteEnabled() returns
                // false (which will be the case if not on Android 12+), but just to be safe
                // also needed to avoid lint warnings for BluetoothLeService requiring Android 12+
                return
            }
            bluetoothLeService = (service as BluetoothLeService.LocalBinder).service
            if (!bluetoothLeService!!.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth")
                stopRemoteControl()
            }
            // connect to the device
            bluetoothLeService!!.connect(remoteDeviceAddress)
        }

        /** Called when a connection to the Service has been lost. This typically happens when the
         * process hosting the service has crashed or been killed.
         * So in particular, note this isn't the inverse to onServiceConnected() - whilst
         * onServiceConnected is always called (after the service receives onBind()), upon normal
         * disconnection (after we call unbindService()), the service receives onUnbind(), but
         * onServiceDisconnected is not called under normal operation.
         */
        override fun onServiceDisconnected(componentName: ComponentName) {
            if (MyDebug.LOG) Log.d(TAG, "onServiceDisconnected")
            if (!DeviceScanner.useAndroid12BluetoothPermissions()) {
                if (MyDebug.LOG) Log.e(TAG, "bluetooth remote control requires Android 12+")
                // in theory not needed as mServiceConnection is not used if remoteEnabled() returns
                // false (which will be the case if not on Android 12+), but just to be safe
                return
            }
            val handler = Handler()
            handler.postDelayed({ bluetoothLeService!!.connect(remoteDeviceAddress) }, 5000)
        }
    }

    /**
     * Receives event from the remote command handler through intents
     * Handles various events fired by the Service.
     */
    private val remoteControlCommandReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!DeviceScanner.useAndroid12BluetoothPermissions()) {
                // shouldn't be here if not on Android 12+, but to fix Android lint warning
                if (MyDebug.LOG) Log.e(TAG, "bluetooth remote control requires Android 12+")
                return
            }
            val action = intent.action
            val applicationInterface: MyApplicationInterface =
                mainActivity.applicationInterface
            val mainUI: MainUI = mainActivity.mainUI
            if (BluetoothLeService.ACTION_GATT_CONNECTED == action) {
                if (MyDebug.LOG) Log.d(TAG, "Remote connected")
                // Tell the Bluetooth service what type of remote we want to use
                bluetoothLeService!!.setRemoteDeviceType(remoteDeviceType!!)
                mainActivity.setBrightnessForCamera(false)
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED == action) {
                if (MyDebug.LOG) Log.d(TAG, "Remote disconnected")
                isConnected = false
                applicationInterface.drawPreview.onExtraOSDValuesChanged("-- \u00B0C", "-- m")
                mainUI.updateRemoteConnectionIcon()
                mainActivity.setBrightnessToMinimumIfWanted()
                if (mainUI.isExposureUIOpen) mainUI.toggleExposureUI()
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED == action) {
                if (MyDebug.LOG) Log.d(TAG, "Remote services discovered")
                // We let the BluetoothLEService subscribe to what is relevant, so we
                // do nothing here, but we wait until this is done to update the UI
                // icon
                isConnected = true
                mainUI.updateRemoteConnectionIcon()
            } else if (BluetoothLeService.ACTION_SENSOR_VALUE == action) {
                val temp = intent.getDoubleExtra(BluetoothLeService.SENSOR_TEMPERATURE, -1.0)
                var depth: Double = intent.getDoubleExtra(
                    BluetoothLeService.SENSOR_DEPTH,
                    -1.0
                ) / mainActivity.waterDensity
                depth = ((depth * 10).roundToInt()) / 10.0 // Round to 1 decimal
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "Sensor values: depth: $depth - temp: $temp"
                )
                // Create two OSD lines
                val line1 = "$temp \u00B0C"
                val line2 = "$depth m"
                applicationInterface.drawPreview.onExtraOSDValuesChanged(line1, line2)
            } else if (BluetoothLeService.ACTION_REMOTE_COMMAND == action) {
                val command = intent.getIntExtra(BluetoothLeService.EXTRA_DATA, -1)
                // TODO: we could abstract this into a method provided by each remote control model
                when (command) {
                    BluetoothLeService.COMMAND_SHUTTER ->
                        mainActivity.triggerRemoteControlAction()

                    BluetoothLeService.COMMAND_MODE ->                         // "Mode" key :either toggles photo/video mode, or
                        // closes the settings screen that is currently open
                        if (mainUI.popupIsOpen()) {
                            mainUI.togglePopupSettings()
                        } else if (mainUI.isExposureUIOpen) {
                            mainUI.toggleExposureUI()
                        } else {
                            mainActivity.clickedSwitchVideo(null)
                        }

                    BluetoothLeService.COMMAND_MENU ->                         // Open the exposure UI (ISO/Exposure) or
                        // select the current line on an open UI or
                        // select the current option on a button on a selected line
                        if (!mainUI.popupIsOpen()) {
                            if (!mainUI.isExposureUIOpen) {
                                mainUI.toggleExposureUI()
                            } else {
                                mainUI.commandMenuExposure()
                            }
                        } else {
                            mainUI.commandMenuPopup()
                        }

                    BluetoothLeService.COMMAND_UP -> if (!mainUI.processRemoteUpButton()) {
                        // Default up behavior:
                        // - if we are on manual focus, then adjust focus.
                        // - if we are on autofocus, then adjust zoom.
                        if (mainActivity.preview
                                .currentFocusValue != null && mainActivity.preview
                                .currentFocusValue.equals("focus_mode_manual2")
                        ) {
                            mainActivity.changeFocusDistance(-25, false)
                        } else {
                            // Adjust zoom
                            mainActivity.zoomIn()
                        }
                    }

                    BluetoothLeService.COMMAND_DOWN -> if (!mainUI.processRemoteDownButton()) {
                        if (mainActivity.preview
                                .currentFocusValue != null && mainActivity.preview
                                .currentFocusValue.equals("focus_mode_manual2")
                        ) {
                            mainActivity.changeFocusDistance(25, false)
                        } else {
                            // Adjust zoom
                            mainActivity.zoomOut()
                        }
                    }

                    BluetoothLeService.COMMAND_AFMF ->                         // Open the camera settings popup menu (not the app settings)
                        // or selects the current line/icon in the popup menu, and finally
                        // clicks the icon
                        //if( !mainUI.popupIsOpen() ) {
                        mainUI.togglePopupSettings()

                    else -> {}
                }
            } else {
                if (MyDebug.LOG) Log.d(TAG, "Other remote event")
            }
        }
    }

    fun remoteConnected(): Boolean {
        /*if( true )
			return true; // test*/
        return isConnected
    }

    /**
     * Starts or stops the remote control layer
     */
    fun startRemoteControl() {
        if (MyDebug.LOG) Log.d(TAG, "BLE Remote control service start check...")
        if (!DeviceScanner.useAndroid12BluetoothPermissions()) {
            // bluetooth remote control requires Android 12+
        } else if (!mainActivity.isAppPaused && remoteEnabled()) {
            if (MyDebug.LOG) Log.d(TAG, "Remote enabled, starting service")
            val gattServiceIntent = Intent(mainActivity, BluetoothLeService::class.java)
            mainActivity.bindService(
                gattServiceIntent,
                mServiceConnection,
                Context.BIND_AUTO_CREATE
            )
            // For Android 14 (UPSIDE_DOWN_CAKE) onwards, a flag of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED must be specified when using
            // registerReceiver with non-system intents, otherwise a SecurityException will be thrown.
            // The if condition is for TIRAMISU as there seems no harm doing this for earlier versions too, but RECEIVER_NOT_EXPORTED
            // requires Android 13.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mainActivity.registerReceiver(
                    remoteControlCommandReceiver,
                    makeRemoteCommandIntentFilter(),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                // n.b., this gets an Android lint warning, even though this can only be fixed for TIRAMISU onwards (as
                // RECEIVER_NOT_EXPORTED not available on older versions)!
                mainActivity.registerReceiver(
                    remoteControlCommandReceiver,
                    makeRemoteCommandIntentFilter()
                )
            }
        } else {
            if (MyDebug.LOG) Log.d(TAG, "Remote disabled, stopping service")
            // Stop the service if necessary
            try {
                mainActivity.unregisterReceiver(remoteControlCommandReceiver)
                mainActivity.unbindService(mServiceConnection)
                isConnected = false // Unbinding closes the connection, of course
                mainActivity.mainUI.updateRemoteConnectionIcon()
            } catch (_: IllegalArgumentException) {
                if (MyDebug.LOG) Log.d(TAG, "Remote Service was not running, that's fine")
            }
        }
    }

    fun stopRemoteControl() {
        if (MyDebug.LOG) Log.d(TAG, "BLE Remote control service shutdown...")
        if (remoteEnabled()) {
            // Stop the service if necessary
            try {
                mainActivity.unregisterReceiver(remoteControlCommandReceiver)
                mainActivity.unbindService(mServiceConnection)
                isConnected = false // Unbinding closes the connection, of course
                mainActivity.mainUI.updateRemoteConnectionIcon()
            } catch (e: IllegalArgumentException) {
                MyDebug.logStackTrace(TAG, "Remote Service was not running, that's strange", e)
            }
        }
    }

    /**
     * Checks if remote control is enabled in the settings, and the remote control address
     * is also defined
     * @return true if this is the case
     */
    fun remoteEnabled(): Boolean {
        if (!DeviceScanner.useAndroid12BluetoothPermissions()) {
            // bluetooth remote control requires Android 12+
            return false
        }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val remoteEnabled = sharedPreferences.getBoolean(PreferenceKeys.ENABLE_REMOTE, false)
        remoteDeviceType = sharedPreferences.getString(PreferenceKeys.REMOTE_TYPE, "undefined")
        remoteDeviceAddress = sharedPreferences.getString(PreferenceKeys.REMOTE_NAME, "undefined")
        //return remoteEnabled; // test - if using this, also need to enable test code in BluetoothLeService.connect()
        return remoteEnabled && remoteDeviceAddress != "undefined"
    }

    companion object {
        private const val TAG = "BluetoothRemoteControl"

        // TODO: refactor for a filter than receives generic remote control intents
        @RequiresApi(Build.VERSION_CODES.S)
        private fun makeRemoteCommandIntentFilter(): IntentFilter {
            val intentFilter = IntentFilter()
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED)
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED)
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED)
            intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE)
            intentFilter.addAction(BluetoothLeService.ACTION_REMOTE_COMMAND)
            intentFilter.addAction(BluetoothLeService.ACTION_SENSOR_VALUE)
            return intentFilter
        }
    }
}

