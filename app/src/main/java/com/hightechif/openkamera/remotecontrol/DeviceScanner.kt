/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.remotecontrol

//import android.app.ListActivity;
import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.LeScanCallback
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView.OnItemClickListener
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.utils.MyDebug

//public class DeviceScanner extends ListActivity {
//public class DeviceScanner extends Activity {
open class DeviceScanner : AppCompatActivity() {
    private var leDeviceListAdapter: LeDeviceListAdapter? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false
    private var bluetoothHandler: Handler? = null
    private lateinit var mSharedPreferences: SharedPreferences

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_select)
        bluetoothHandler = Handler()

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show()
            finish()
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.bluetooth_not_supported, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val startScanningButton = findViewById<Button>(R.id.StartScanButton)
        startScanningButton.setOnClickListener { startScanning() }

        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.applicationContext)
        val preferenceRemoteDeviceName: String = PreferenceKeys.REMOTE_NAME
        val remoteName = mSharedPreferences.getString(preferenceRemoteDeviceName, "none")!!
        if (MyDebug.LOG) Log.d(
            TAG,
            "preference_remote_device_name: $remoteName"
        )

        val currentRemote = findViewById<TextView>(R.id.currentRemote)
        val text = resources.getString(R.string.bluetooth_current_remote) + " " + remoteName
        currentRemote.text = text
    }

    override fun onContentChanged() {
        if (MyDebug.LOG) Log.d(TAG, "onContentChanged")

        super.onContentChanged()

        val list = findViewById<ListView>(R.id.list)
        list.onItemClickListener =
            OnItemClickListener { parent, v, position, id ->
                onListItemClick(
                    parent as ListView,
                    v,
                    position,
                    id
                )
            }
    }

    private fun checkBluetoothEnabled() {
        if (MyDebug.LOG) Log.d(TAG, "checkBluetoothEnabled")
        // BLUETOOTH_CONNECT permission is needed for BluetoothAdapter.ACTION_REQUEST_ENABLE.
        // Callers should have already checked for bluetooth permission, but we have this check
        // just in case - and also to avoid the Android lint error that we'd get.
        if (useAndroid12BluetoothPermissions()) {
            if (MyDebug.LOG) Log.d(TAG, "check for bluetooth connect permission")
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "bluetooth connect permission not granted!")
                return
            }
        }
        if (!bluetoothAdapter!!.isEnabled) {
            // fire an intent to display a dialog asking the user to grant permission to enable Bluetooth
            // n.b., on Android 12 need BLUETOOTH_CONNECT permission for this
            if (MyDebug.LOG) Log.d(TAG, "request to enable bluetooth")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }
    }

    private fun startScanning() {
        if (MyDebug.LOG) Log.d(TAG, "Start scanning")

        // In real life most of bluetooth LE devices associated with location, so without this
        // permission the sample shows nothing in most cases
        // Also see https://stackoverflow.com/questions/33045581/location-needs-to-be-enabled-for-bluetooth-low-energy-scanning-on-android-6-0
        // Update: on Android 10+, ACCESS_FINE_LOCATION is needed: https://developer.android.com/about/versions/10/privacy/changes#location-telephony-bluetooth-wifi
        // Update: on Android 12+, we use the new bluetooth permissions instead of location permissions.
        var hasPermission = false
        if (useAndroid12BluetoothPermissions()) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
                &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                hasPermission = true
            }
        } else {
            val permissionNeeded =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACCESS_FINE_LOCATION else Manifest.permission.ACCESS_COARSE_LOCATION

            val permissionCoarse = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ContextCompat
                .checkSelfPermission(this, permissionNeeded) else PackageManager.PERMISSION_GRANTED

            if (permissionCoarse == PackageManager.PERMISSION_GRANTED) {
                hasPermission = true
            }
        }

        if (hasPermission) {
            checkBluetoothEnabled()
        }

        leDeviceListAdapter = LeDeviceListAdapter()
        //setListAdapter(leDeviceListAdapter);
        val list = findViewById<ListView>(R.id.list)
        list.adapter = leDeviceListAdapter

        if (hasPermission) {
            scanLeDevice(true)
        } else {
            askForDeviceScannerPermission()
        }
    }

    /** Request permissions needed for bluetooth (BLUETOOTH_SCAN and BLUETOOTH_CONNECT on Android
     * 12+, else location permission).
     */
    private fun askForDeviceScannerPermission() {
        if (MyDebug.LOG) Log.d(TAG, "askForDeviceScannerPermission")
        // n.b., we only need ACCESS_COARSE_LOCATION, but it's simpler to request both to be consistent with Open Kamera's
        // location permission requests in PermissionHandler. If we only request ACCESS_COARSE_LOCATION here, and later the
        // user enables something that needs ACCESS_FINE_LOCATION, Android ends up showing the "rationale" dialog - and once
        // that's dismissed, the permission seems to be granted without showing the permission request dialog (so it works,
        // but is confusing for the user)
        // Also note that if we did want to only request ACCESS_COARSE_LOCATION here, we'd need to declare that permission
        // explicitly in the AndroidManifest.xml, otherwise the dialog to request permission is never shown (and the permission
        // is denied automatically).
        // Update: on Android 10+, ACCESS_FINE_LOCATION is needed anyway: https://developer.android.com/about/versions/10/privacy/changes#location-telephony-bluetooth-wifi
        // Update: on Android 12+, we use the new bluetooth permissions instead of location permissions.
        if (useAndroid12BluetoothPermissions()) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) ||
                ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            ) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
                showRequestBluetoothScanConnectPermissionRationale()
            } else {
                // Can go ahead and request the permission
                if (MyDebug.LOG) Log.d(TAG, "requesting bluetooth scan/connect permissions...")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ),
                    REQUEST_BLUETOOTHSCANCONNECT_PERMISSIONS
                )
            }
        } else {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) ||
                ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            ) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
                showRequestLocationPermissionRationale()
            } else {
                // Can go ahead and request the permission
                if (MyDebug.LOG) Log.d(TAG, "requesting location permissions...")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    REQUEST_LOCATION_PERMISSIONS
                )
            }
        }
    }

    private fun showRequestBluetoothScanConnectPermissionRationale() {
        if (MyDebug.LOG) Log.d(TAG, "showRequestBluetoothScanConnectPermissionRationale")
        if (!useAndroid12BluetoothPermissions()) {
            // just in case!
            Log.e(TAG, "shouldn't be requesting bluetooth scan/connect permissions!")
            return
        }

        val permissions =
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val messageId: Int = R.string.permission_rationale_bluetooth_scan_connect

        AlertDialog.Builder(this)
            .setTitle(R.string.permission_rationale_title)
            .setMessage(messageId)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                if (MyDebug.LOG) Log.d(TAG, "requesting permission...")
                ActivityCompat.requestPermissions(
                    this@DeviceScanner,
                    permissions,
                    REQUEST_BLUETOOTHSCANCONNECT_PERMISSIONS
                )
            }.show()
    }

    private fun showRequestLocationPermissionRationale() {
        if (MyDebug.LOG) Log.d(TAG, "showRequestLocationPermissionRationale")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (MyDebug.LOG) Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!")
            return
        }

        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val messageId: Int = R.string.permission_rationale_location

        AlertDialog.Builder(this)
            .setTitle(R.string.permission_rationale_title)
            .setMessage(messageId)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                if (MyDebug.LOG) Log.d(TAG, "requesting permission...")
                ActivityCompat.requestPermissions(
                    this@DeviceScanner,
                    permissions,
                    REQUEST_LOCATION_PERMISSIONS
                )
            }.show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onRequestPermissionsResult: requestCode $requestCode"
        )

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_LOCATION_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (MyDebug.LOG) Log.d(TAG, "location permission granted")
                    checkBluetoothEnabled()
                    scanLeDevice(true)
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "location permission denied")
                }
            }

            REQUEST_BLUETOOTHSCANCONNECT_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (MyDebug.LOG) Log.d(TAG, "bluetooth scan/connect permission granted")
                    checkBluetoothEnabled()
                    scanLeDevice(true)
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "bluetooth scan/connect permission denied")
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (MyDebug.LOG) Log.d(TAG, "onActivityResult")
        // user decided to cancel the enabling of Bluetooth, so exit
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_CANCELED) {
            finish()
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onPause() {
        if (MyDebug.LOG) Log.d(TAG, "onPause")
        super.onPause()
        if (isScanning) {
            scanLeDevice(false)
            leDeviceListAdapter!!.clear()
        }
    }

    override fun onStop() {
        if (MyDebug.LOG) Log.d(TAG, "onStop")
        super.onStop()

        // we do this in onPause, but done here again just to be certain!
        if (isScanning) {
            scanLeDevice(false)
            leDeviceListAdapter!!.clear()
        }
    }

    override fun onDestroy() {
        if (MyDebug.LOG) Log.d(TAG, "on_destroy")

        // we do this in onPause, but done here again just to be certain!
        if (isScanning) {
            scanLeDevice(false)
            leDeviceListAdapter!!.clear()
        }

        super.onDestroy()
    }

    //@Override
    protected fun onListItemClick(l: ListView?, v: View?, position: Int, id: Long) {
        val device = leDeviceListAdapter?.getDevice(position) ?: return
        if (MyDebug.LOG) {
            Log.d(TAG, "onListItemClick")
            Log.d(TAG, device.address)
        }
        val preferenceRemoteDeviceName: String = PreferenceKeys.REMOTE_NAME
        mSharedPreferences.edit {
            putString(preferenceRemoteDeviceName, device.address)
        }
        scanLeDevice(false)
        finish()
    }

    private fun scanLeDevice(enable: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "scanLeDevice: $enable")

        // BLUETOOTH_SCAN permission is needed for bluetoothAdapter.startLeScan and
        // bluetoothAdapter.stopLeScan. Callers should have already checked for bluetooth
        // permission, but we have this check just in case - and also to avoid the Android lint
        // error that we'd get.
        if (useAndroid12BluetoothPermissions()) {
            if (MyDebug.LOG) Log.d(TAG, "check for bluetooth scan permission")
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "bluetooth scan permission not granted!")
                return
            }
        }

        if (enable) {
            // stop scanning after certain time
            bluetoothHandler!!.postDelayed({
                if (MyDebug.LOG) Log.d(TAG, "stop scanning after delay")
                /*isScanning = false;
                            bluetoothAdapter.stopLeScan(mLeScanCallback);
                            invalidateOptionsMenu();*/
                scanLeDevice(false)
            }, 10000)

            isScanning = true
            bluetoothAdapter!!.startLeScan(mLeScanCallback)
        } else {
            isScanning = false
            bluetoothAdapter!!.stopLeScan(mLeScanCallback)
        }
        invalidateOptionsMenu()
    }

    private inner class LeDeviceListAdapter : BaseAdapter() {
        private val mLeDevices = ArrayList<BluetoothDevice>()
        private val mInflator = this@DeviceScanner.layoutInflater

        fun addDevice(device: BluetoothDevice) {
            if (!mLeDevices.contains(device)) {
                mLeDevices.add(device)
            }
        }

        fun getDevice(position: Int): BluetoothDevice {
            return mLeDevices[position]
        }

        fun clear() {
            mLeDevices.clear()
        }

        override fun getCount(): Int {
            return mLeDevices.size
        }

        override fun getItem(i: Int): Any {
            return mLeDevices[i]
        }

        override fun getItemId(i: Int): Long {
            return i.toLong()
        }

        override fun getView(i: Int, view: View?, viewGroup: ViewGroup): View {
            var view = view
            val viewHolder: ViewHolder
            if (view == null) {
                view = mInflator.inflate(R.layout.listitem_device, null)
                viewHolder = ViewHolder(
                    view!!.findViewById(R.id.device_address),
                    view.findViewById(R.id.device_name)
                )
                view.tag = viewHolder
            } else {
                viewHolder = view.tag as ViewHolder
            }

            // BLUETOOTH_CONNECT permission is needed for device.getName. In theory we shouldn't
            // have added to this list if bluetooth permission not available, but we have this
            // check just in case - and also to avoid the Android lint error that we'd get.
            var hasBluetoothScanPermission = true
            if (useAndroid12BluetoothPermissions()) {
                if (MyDebug.LOG) Log.d(TAG, "check for bluetooth connect permission")
                if (ContextCompat.checkSelfPermission(
                        this@DeviceScanner,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    hasBluetoothScanPermission = false
                }
            }

            val device = mLeDevices[i]

            if (!hasBluetoothScanPermission) {
                Log.e(TAG, "bluetooth connect permission not granted!")
                viewHolder.deviceName.setText(R.string.unknown_device_no_permission)
            } else {
                val deviceName = device.name
                if (deviceName != null && deviceName.isNotEmpty()) viewHolder.deviceName.text =
                    deviceName
                else viewHolder.deviceName.setText(R.string.unknown_device)
            }

            viewHolder.deviceAddress.text = device.address

            return view
        }
    }

    private val mLeScanCallback =
        LeScanCallback { device, _, _ -> // device, rssi, scanRecord
            runOnUiThread {
                leDeviceListAdapter!!.addDevice(device)
                leDeviceListAdapter!!.notifyDataSetChanged()
            }
        }

    internal data class ViewHolder(
        var deviceName: TextView,
        var deviceAddress: TextView
    )

    companion object {
        private const val TAG = "OC-BLEScanner"
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_LOCATION_PERMISSIONS = 2
        private const val REQUEST_BLUETOOTHSCANCONNECT_PERMISSIONS = 3

        /** Returns whether we can use the new Android 12 permissions for bluetooth (BLUETOOTH_SCAN,
         * BLUETOOTH_CONNECT) - if so, we should use these and NOT location permissions.
         * See https://developer.android.com/guide/topics/connectivity/bluetooth/permissions .
         */
        fun useAndroid12BluetoothPermissions(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }
    }
}