/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.remotecontrol

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.hightechif.openkamera.utils.MyDebug
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

class BluetoothLeService : Service() {
    private var isBound = false // whether service is bound
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var deviceAddress: String? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var remoteDeviceType: String? = null
    private val bluetoothHandler = Handler()
    private val subscribedCharacteristics = HashMap<String, BluetoothGattCharacteristic>()
    private val charsToSubscribe: MutableList<BluetoothGattCharacteristic> = ArrayList()

    private var currentTemp = -1.0
    private var currentDepth = -1.0

    /* This forces a gratuitous BLE scan to help the device
     * connect to the remote faster. This is due to limitations of the
     * Android BLE stack and API (just knowing the MAC is not enough on
     * many phones).*/
    private fun triggerScan() {
        if (MyDebug.LOG) Log.d(TAG, "triggerScan")

        if (!isBound) {
            // Don't allow calls to startLeScan() (which requires location permission) when service
            // not bound, as application may be in background!
            // In theory this shouldn't be needed here, as we also check isBound in connect(), but
            // have it here too just to be safe.
            Log.e(TAG, "triggerScan shouldn't be called when service not bound")
            return
        }

        // Check for Android 12 Bluetooth permission just in case (and for Android lint error)
        if (DeviceScanner.useAndroid12BluetoothPermissions()) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "bluetooth scan permission not granted!")
                return
            }
        }

        // Stops scanning after a pre-defined scan period.
        bluetoothHandler.postDelayed(Runnable { // Check for Android 12 Bluetooth permission just in case (and for Android lint error)
            if (DeviceScanner.useAndroid12BluetoothPermissions()) {
                if (ContextCompat.checkSelfPermission(
                        this@BluetoothLeService,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "bluetooth scan permission not granted!")
                    return@Runnable
                }
            }
            bluetoothAdapter!!.stopLeScan(null)
        }, 10000)
        bluetoothAdapter!!.startLeScan(null)
    }

    fun setRemoteDeviceType(remoteDeviceType: String) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "Setting remote type: $remoteDeviceType"
        )
        this.remoteDeviceType = remoteDeviceType
    }

    private val mGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val intentAction: String
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED
                broadcastUpdate(intentAction)
                if (MyDebug.LOG) {
                    Log.d(TAG, "Connected to GATT server, call discoverServices()")
                }

                // Check for Android 12 Bluetooth permission just in case (and for Android lint error)
                var hasBluetoothPermission = true
                if (DeviceScanner.useAndroid12BluetoothPermissions()) {
                    if (ContextCompat.checkSelfPermission(
                            this@BluetoothLeService,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.e(TAG, "bluetooth scan permission not granted!")
                        hasBluetoothPermission = false
                    }
                }

                if (hasBluetoothPermission) {
                    bluetoothGatt!!.discoverServices()
                }

                currentDepth = -1.0
                currentTemp = -1.0
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "Disconnected from GATT server, reattempting every 5 seconds."
                )
                broadcastUpdate(intentAction)
                attemptReconnect()
            }
        }

        fun attemptReconnect() {
            if (!isBound) {
                // We check isBound in connect() itself, but seems pointless to even try if we
                // know the service is unbound (and if it's later bound again, we'll try connecting
                // again anyway without needing this).
                Log.e(TAG, "don't attempt to reconnect when service not bound")
            }

            val timer = Timer()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (MyDebug.LOG) Log.d(TAG, "Attempting to reconnect to remote.")
                    connect(deviceAddress)
                }
            }, 5000)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
                subscribeToServices()
            } else {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "onServicesDiscovered received: $status"
                )
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (MyDebug.LOG) Log.d(TAG, "Got notification")
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // We need to wait for this callback before enabling the next notification in case we
            // have several in our list
            if (!charsToSubscribe.isEmpty()) {
                setCharacteristicNotification(charsToSubscribe.removeAt(0), true)
            }
        }
    }

    /**
     * Subscribe to the services/characteristics we need depending
     * on the remote device model
     *
     */
    private fun subscribeToServices() {
        val gattServices =
            supportedGattServices ?: return

        val mCharacteristicsWanted = when (remoteDeviceType) {
            "preference_remote_type_kraken" -> KrakenGattAttributes.desiredCharacteristics
            else -> listOf<UUID>(UUID.fromString("0000"))
        }

        for (gattService in gattServices) {
            val gattCharacteristics =
                gattService.characteristics
            for (gattCharacteristic in gattCharacteristics) {
                val uuid = gattCharacteristic.uuid
                if (mCharacteristicsWanted.contains(uuid)) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "Found characteristic to subscribe to: $uuid"
                    )
                    charsToSubscribe.add(gattCharacteristic)
                }
            }
        }
        setCharacteristicNotification(charsToSubscribe.removeAt(0), true)
    }

    private fun broadcastUpdate(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }

    private fun broadcastUpdate(action: String, characteristic: BluetoothGattCharacteristic) {
        val uuid = characteristic.uuid
        val formatUint8 = BluetoothGattCharacteristic.FORMAT_UINT8
        val formatUint16 = BluetoothGattCharacteristic.FORMAT_UINT16
        var remoteCommand = -1

        if (KrakenGattAttributes.KRAKEN_BUTTONS_CHARACTERISTIC.equals(uuid)) {
            if (MyDebug.LOG) Log.d(TAG, "Got Kraken button press")
            val buttonCode = characteristic.getIntValue(formatUint8, 0)
            if (MyDebug.LOG) Log.d(TAG, String.format("Received Button press: %d", buttonCode))
            // Note: we stay at a fairly generic level here and will manage variants
            // on the various button actions in MainActivity, because those will change depending
            // on the current state of the app, and we don't want to know anything about that state
            // from the Bluetooth LE service
            // TODO: update to remove all those tests and just forward buttonCode since value is identical
            //       but this is more readable if we want to implement other drivers
            if (buttonCode == 32) {
                // Shutter press
                remoteCommand = COMMAND_SHUTTER
            } else if (buttonCode == 16) {
                // "Mode" button: either "back" action or "Photo/Camera" switch
                remoteCommand = COMMAND_MODE
            } else if (buttonCode == 48) {
                // "Menu" button
                remoteCommand = COMMAND_MENU
            } else if (buttonCode == 97) {
                // AF/MF button
                remoteCommand = COMMAND_AFMF
            } else if (buttonCode == 96) {
                // Long press on MF/AF button.
                // Note: the camera issues button code 97 first, then
                // 96 after one second of continuous press
            } else if (buttonCode == 64) {
                // Up button
                remoteCommand = COMMAND_UP
            } else if (buttonCode == 80) {
                // Down button
                remoteCommand = COMMAND_DOWN
            }
            // Only send forward if we have something to say
            if (remoteCommand > -1) {
                val intent = Intent(ACTION_REMOTE_COMMAND)
                intent.putExtra(EXTRA_DATA, remoteCommand)
                sendBroadcast(intent)
            }
        } else if (KrakenGattAttributes.KRAKEN_SENSORS_CHARACTERISTIC.equals(uuid)) {
            // The housing returns four bytes.
            // Byte 0-1: depth = (Byte 0 + Byte 1 << 8) / 10 / density
            // Byte 2-3: temperature = (Byte 2 + Byte 3 << 8) / 10
            //
            // Depth is valid for fresh water by default ( makes you wonder whether the sensor
            // is really designed for saltwater at all), and the value has to be divided by the density
            // of saltwater. A commonly accepted value is 1030 kg/m3 (1.03 density)

            val temperature = characteristic.getIntValue(formatUint16, 2) / 10.0
            val depth = characteristic.getIntValue(formatUint16, 0) / 10.0

            if (temperature == currentTemp && depth == currentDepth) return

            currentDepth = depth
            currentTemp = temperature

            if (MyDebug.LOG) Log.d(
                TAG,
                "Got new Kraken sensor reading. Temperature: $temperature Depth:$depth"
            )

            val intent = Intent(ACTION_SENSOR_VALUE)
            intent.putExtra(SENSOR_TEMPERATURE, temperature)
            intent.putExtra(SENSOR_DEPTH, depth)
            sendBroadcast(intent)
        }
    }

    inner class LocalBinder : Binder() {
        val service: BluetoothLeService
            get() = this@BluetoothLeService
    }

    private val mBinder: IBinder = LocalBinder()

    override fun onBind(intent: Intent): IBinder? {
        if (MyDebug.LOG) Log.d(TAG, "onBind")
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onUnbind")
        this.isBound = false
        close()
        return super.onUnbind(intent)
    }

    /** Only call this after service is bound (from ServiceConnection.onServiceConnected())!
     */
    fun initialize(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "initialize")

        // in theory we'd put this in onBind(), to be more symmetric with onUnbind() where we
        // set to false - but unclear whether onBind() is always called before
        // ServiceConnection.onServiceConnected().
        this.isBound = true

        if (bluetoothManager == null) {
            bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            if (bluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.")
                return false
            }
        }

        bluetoothAdapter = bluetoothManager!!.adapter
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.")
            return false
        }

        return true
    }

    fun connect(address: String?): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "connect: $address")
        if (bluetoothAdapter == null) {
            if (MyDebug.LOG) Log.d(TAG, "bluetoothAdapter is null")
            return false
        } else if (address == null) {
            if (MyDebug.LOG) Log.d(TAG, "address is null")
            return false
        } else if (!isBound) {
            // Don't allow calls to startLeScan() via triggerScan() (which requires location
            // permission) when service not bound, as application may be in background!
            // And it doesn't seem sensible to even allow connecting if service not bound.
            // Under normal operation this isn't needed, but there are calls to connect() that can
            // happen from postDelayed() or TimerTask in this class, so a risk that they call
            // connect() after the service is unbound!
            Log.e(TAG, "connect shouldn't be called when service not bound")
            return false
        }

        // Check for Android 12 Bluetooth permission just in case (and for Android lint error)
        if (DeviceScanner.useAndroid12BluetoothPermissions()) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "bluetooth scan permission not granted!")
                return false
            }
        }

        // test code for infinite looping, seeing if this runs in background:
        /*if( address.equals("undefined") ) {
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                public void run() {
                    if( MyDebug.LOG )
                        Log.d(TAG, "trying connect again from postdelayed");
                    connect(address);
                }
            }, 1000);
        }

        if( address.equals("undefined") ) {
            // test - only needed if we've hacked BluetoothRemoteControl.remoteEnabled() to not check for being undefined
            if( MyDebug.LOG )
                Log.d(TAG, "address is undefined");
            return false;
        }*/
        if (address == deviceAddress && bluetoothGatt != null) {
            bluetoothGatt!!.disconnect()
            bluetoothGatt!!.close()
            bluetoothGatt = null
        }

        val device = bluetoothAdapter!!.getRemoteDevice(address)
        if (device == null) {
            if (MyDebug.LOG) Log.d(TAG, "device not found")
            val handler = Handler()
            handler.postDelayed({
                if (MyDebug.LOG) Log.d(TAG, "attempt to connect to remote")
                connect(address)
            }, 5000)
            return false
        }

        // It looks like Android won't connect to BLE devices properly without scanning
        // for them first, even when connecting by explicit MAC address. Since we're using
        // BLE for underwater housings and we want rock solid connectivity, we trigger
        // a scan for 10 seconds
        triggerScan()

        bluetoothGatt = device.connectGatt(this, true, mGattCallback)
        deviceAddress = address
        return true
    }

    private fun close() {
        if (bluetoothGatt == null) {
            return
        }

        // Check for Android 12 Bluetooth permission just in case (and for Android lint error)
        if (DeviceScanner.useAndroid12BluetoothPermissions()) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "bluetooth scan permission not granted!")
                return
            }
        }

        bluetoothGatt!!.close()
        bluetoothGatt = null
    }

    private fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean
    ) {
        if (bluetoothAdapter == null) {
            if (MyDebug.LOG) Log.d(TAG, "bluetoothAdapter is null")
            return
        } else if (bluetoothGatt == null) {
            if (MyDebug.LOG) Log.d(TAG, "bluetoothGatt is null")
            return
        }

        // Check for Android 12 Bluetooth permission just in case (and for Android lint error)
        if (DeviceScanner.useAndroid12BluetoothPermissions()) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "bluetooth scan permission not granted!")
                return
            }
        }

        val uuid = characteristic.uuid.toString()
        bluetoothGatt!!.setCharacteristicNotification(characteristic, enabled)
        if (enabled) {
            subscribedCharacteristics[uuid] = characteristic
        } else {
            subscribedCharacteristics.remove(uuid)
        }

        val descriptor =
            characteristic.getDescriptor(KrakenGattAttributes.CLIENT_CHARACTERISTIC_CONFIG)
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        bluetoothGatt!!.writeDescriptor(descriptor)
    }

    private val supportedGattServices: List<BluetoothGattService>?
        get() {
            if (bluetoothGatt == null) return null

            return bluetoothGatt!!.services
        }

    companion object {
        private const val TAG = "BluetoothLeService"

        /*private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;*/
        const val ACTION_GATT_CONNECTED: String =
            "com.hightechif.openkamera.Remotecontrol.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED: String =
            "com.hightechif.openkamera.Remotecontrol.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED: String =
            "com.hightechif.openkamera.Remotecontrol.ACTION_GATT_SERVICES_DISCOVERED"
        const val ACTION_DATA_AVAILABLE: String =
            "com.hightechif.openkamera.Remotecontrol.ACTION_DATA_AVAILABLE"
        const val ACTION_REMOTE_COMMAND: String = "com.hightechif.openkamera.Remotecontrol.COMMAND"
        const val ACTION_SENSOR_VALUE: String = "com.hightechif.openkamera.Remotecontrol.SENSOR"
        const val SENSOR_TEMPERATURE: String =
            "com.hightechif.openkamera.Remotecontrol.TEMPERATURE"
        const val SENSOR_DEPTH: String = "com.hightechif.openkamera.Remotecontrol.DEPTH"
        const val EXTRA_DATA: String = "com.hightechif.openkamera.Remotecontrol.EXTRA_DATA"
        const val COMMAND_SHUTTER: Int = 32
        const val COMMAND_MODE: Int = 16
        const val COMMAND_MENU: Int = 48
        const val COMMAND_AFMF: Int = 97
        const val COMMAND_UP: Int = 64
        const val COMMAND_DOWN: Int = 80
    }
}