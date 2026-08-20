package com.hightechif.openkamera.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationProvider
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.utils.MyDebug
import kotlin.concurrent.Volatile
import kotlin.math.abs


/** Handles listening for GPS location (both coarse and fine).
 */
class LocationSupplier internal constructor(private val context: Context) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var locationListeners: Array<MyLocationListener?>? = null

    @Volatile
    private var testForceNoLocation =
        false // if true, always return null location; must be volatile for test project setting the state

    private var _cachedLocation: Location? = null
    private var cachedLocationMs: Long = 0

    private val cachedLocation: Location?
        get() {
            if (_cachedLocation != null) {
                val timeMs = System.currentTimeMillis()
                if (timeMs <= cachedLocationMs + 20000) {
                    return _cachedLocation
                } else {
                    _cachedLocation = null
                }
            }
            return null
        }

    /** Cache the current best location. Note that we intentionally call getLocation() from this
     * method rather than passing it a location from onLocationChanged(), as we don't want a
     * coarse location overriding a better fine location.
     */
    private fun cacheLocation() {
        if (MyDebug.LOG) Log.d(TAG, "cacheLocation")
        val location = location
        if (location == null) {
            // this isn't an error as it can happen that we receive a call to onLocationChanged() after
            // having freed the location listener (possibly because LocationManager had already queued
            // a call to onLocationChanged?
            // we should not set cachedLocation to null in such cases
            Log.d(TAG, "### asked to cache location when location not available")
        } else {
            _cachedLocation = Location(location)
            cachedLocationMs = System.currentTimeMillis()
        }
    }

    class LocationInfo {
        var locationWasCached: Boolean = false

        fun LocationWasCached(): Boolean {
            return locationWasCached
        }
    }

    val location: Location?
        /** If adding extra calls to this, consider whether explicit user permission is required, and whether
         * privacy policy or data privacy section needs updating.
         * @return Returns null if location not available.
         */
        get() = getLocation(null)

    /** If adding extra calls to this, consider whether explicit user permission is required, and whether
     * privacy policy or data privacy section needs updating.
     * @param locationInfo Optional class to return additional information about the location.
     * @return Returns null if location not available.
     */
    fun getLocation(locationInfo: LocationInfo?): Location? {
        if (locationInfo != null) locationInfo.locationWasCached = false // init


        if (locationListeners == null) {
            // if we have disabled location listening, then don't return a cached location anyway -
            // in theory, callers should have already checked for user permission/setting before calling
            // getLocation(), but just in case we didn't, don't want to return a cached location
            return null
        }
        if (testForceNoLocation) return null
        // location listeners should be stored in order best to worst
        for (locationListener in locationListeners!!) {
            if (locationListener != null) {
                val location = locationListener.location
                if (location != null) return location
            }
        }
        val location = cachedLocation
        if (location != null && locationInfo != null) locationInfo.locationWasCached = true
        return location
    }

    private inner class MyLocationListener : LocationListener {
        var location: Location? = null
            private set

        @Volatile
        var testHasReceivedLocation: Boolean =
            false // must be volatile for test project reading the state

        override fun onLocationChanged(location: Location) {
            if (MyDebug.LOG) Log.d(TAG, "onLocationChanged")
            this.testHasReceivedLocation = true
            // Android camera source claims we need to check lat/long != 0.0d
            // also check for not being null just in case - had a nullpointerexception on Google Play!
            if (location != null && (location.latitude != 0.0 || location.longitude != 0.0)) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "received location")
                    // don't log location, in case of privacy!
                }
                this.location = location
                cacheLocation()
            }
        }

        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
            when (status) {
                LocationProvider.OUT_OF_SERVICE, LocationProvider.TEMPORARILY_UNAVAILABLE -> {
                    if (MyDebug.LOG) {
                        if (status == LocationProvider.OUT_OF_SERVICE) Log.d(
                            TAG,
                            "location provider out of service"
                        )
                        else if (status == LocationProvider.TEMPORARILY_UNAVAILABLE) Log.d(
                            TAG, "location provider temporarily unavailable"
                        )
                    }
                    this.location = null
                    this.testHasReceivedLocation = false
                    _cachedLocation = null
                }

                else -> {}
            }
        }

        override fun onProviderEnabled(provider: String) {
        }

        override fun onProviderDisabled(provider: String) {
            if (MyDebug.LOG) Log.d(TAG, "onProviderDisabled")
            this.location = null
            this.testHasReceivedLocation = false
            _cachedLocation = null
        }
    }

    /** Best to only call this from MainActivity.initLocation().
     * @return Returns false if location permission not available for either coarse or fine.
     * Important to only return false if we actually want/need to ask the user for location
     * permission!
     */
    fun setupLocationListener(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "setupLocationListener")
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        // Define a listener that responds to location updates
        // we only set it up if storeLocation is true, important for privacy and unnecessary battery use
        val storeLocation =
            sharedPreferences.getBoolean(PreferenceKeys.LOCATION_PREFERENCE_KEY, false)
        if (storeLocation && locationListeners == null) {
            // Note, ContextCompat.checkSelfPermission is meant to handle being called on any Android version, i.e., pre
            // Android Marshmallow it should return true as permissions are set an installation, and can't be switched off by
            // the user. However on Galaxy Nexus Android 4.3 and Nexus 7 (2013) Android 5.1.1, ACCESS_COARSE_LOCATION returns
            // PERMISSION_DENIED! So we keep the checks to Android Marshmallow or later (where we need them), and avoid
            // checking behaviour for earlier devices.
            val hasCoarseLocationPermission: Boolean
            val hasFineLocationPermission: Boolean
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (MyDebug.LOG) Log.d(TAG, "check for location permissions")
                hasCoarseLocationPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                hasFineLocationPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "has_coarse_location_permission? $hasCoarseLocationPermission"
                    )
                    Log.d(
                        TAG,
                        "has_fine_location_permission? $hasFineLocationPermission"
                    )
                }
                //hasCoarseLocationPermission = false; // test
                //hasFineLocationPermission = false; // test
                // require at least one permission to be present
                // will be important for Android 12+ where user can grant only coarse permission - we still
                // want to support geotagging in such cases
                if (!hasCoarseLocationPermission && !hasFineLocationPermission) {
                    if (MyDebug.LOG) Log.d(TAG, "location permission not available")
                    // return false, which tells caller to request permission - we'll call this function again if permission is granted
                    return false
                }
            } else {
                // permissions always available pre-Android 6
                hasCoarseLocationPermission = true
                hasFineLocationPermission = true
            }

            locationListeners = arrayOfNulls(2)
            locationListeners!![0] = MyLocationListener()
            locationListeners!![1] = MyLocationListener()

            // location listeners should be stored in order best to worst
            // also see https://sourceforge.net/p/OpenKamera/tickets/1/ - need to check provider is available
            // now also need to check for permissions - need to support devices that might have one but not both of fine and coarse permissions supplied
            if (hasCoarseLocationPermission && locationManager.allProviders.contains(
                    LocationManager.NETWORK_PROVIDER
                )
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1000, 0f,
                    locationListeners!![1]!!
                )
                if (MyDebug.LOG) Log.d(TAG, "created coarse (network) location listener")
            } else {
                if (MyDebug.LOG) Log.d(TAG, "don't have a NETWORK_PROVIDER")
            }
            if (hasFineLocationPermission && locationManager.allProviders.contains(
                    LocationManager.GPS_PROVIDER
                )
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000, 0f,
                    locationListeners!![0]!!
                )
                if (MyDebug.LOG) Log.d(TAG, "created fine (gps) location listener")
            } else {
                if (MyDebug.LOG) Log.d(TAG, "don't have a GPS_PROVIDER")
            }
        } else if (!storeLocation) {
            freeLocationListeners()
        }
        // important to return true even if we didn't set up decide the location listeners - as
        // returning false indicates to ask user for location permission (which we don't want to
        // do if PreferenceKeys.LOCATION_PREFERENCE_KEY preference isn't true)
        return true
    }

    fun freeLocationListeners() {
        if (MyDebug.LOG) Log.d(TAG, "freeLocationListeners")
        if (locationListeners != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android Lint claims we need location permission for LocationManager.removeUpdates().
                // also see http://stackoverflow.com/questions/32715189/location-manager-remove-updates-permission
                if (MyDebug.LOG) Log.d(TAG, "check for location permissions")
                val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val hasFineLocationPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "has_coarse_location_permission? $hasCoarseLocationPermission"
                    )
                    Log.d(
                        TAG,
                        "has_fine_location_permission? $hasFineLocationPermission"
                    )
                }
                // require at least one permission to be present
                if (!hasCoarseLocationPermission && !hasFineLocationPermission) {
                    if (MyDebug.LOG) Log.d(TAG, "location permission not available")
                    return
                }
            }
            for (i in locationListeners!!.indices) {
                locationManager.removeUpdates(locationListeners!![i]!!)
                locationListeners!![i] = null
            }
            locationListeners = null
            if (MyDebug.LOG) Log.d(TAG, "location listeners now freed")
        }
    }

    // for testing:
    fun testHasReceivedLocation(): Boolean {
        if (locationListeners == null) return false
        for (locationListener in locationListeners!!) {
            if (locationListener != null) {
                if (locationListener.testHasReceivedLocation) return true
            }
        }
        return false
    }

    fun setForceNoLocation(testForceNoLocation: Boolean) {
        this.testForceNoLocation = testForceNoLocation
    }

    /** Use this when we want to test (assert) that location listeners are turned on.
     * If we want to assert that they are turned off, then use noLocationListeners.
     */
    fun hasLocationListeners(): Boolean {
        if (this.locationListeners == null) return false
        if (locationListeners!!.size != 2) return false
        for (locationListener in locationListeners!!) {
            if (locationListener == null) return false
        }
        return true
    }

    /** Use this when we want to test (assert) that location listeners are turned on. Note that this
     * is NOT an inverse of hasLocationListeners. For example this means that if
     * locationListeners.length==1, hasLocationListeners would return false (so we'd flag up that
     * we've not set them up correctly), but noLocationListeners would also return false (to flag
     * up that we did set some location listeners up).
     */
    fun noLocationListeners(): Boolean {
        if (this.locationListeners == null) return true
        return false
    }

    companion object {
        private const val TAG = "LocationSupplier"

        fun locationToDMS(coord: Double): String {
            var coord = coord
            var sign = if (coord < 0.0) "-" else ""
            coord = abs(coord)
            var intPart = coord.toInt()
            var isZero = (intPart == 0)
            val degrees = intPart.toString()
            var mod = coord - intPart

            coord = mod * 60
            intPart = coord.toInt()
            isZero = isZero && (intPart == 0)
            mod = coord - intPart
            val minutes = intPart.toString()

            coord = mod * 60
            intPart = coord.toInt()
            isZero = isZero && (intPart == 0)
            val seconds = intPart.toString()

            if (isZero) {
                // so we don't show -ve for coord that is -ve but smaller than 1"
                sign = ""
            }

            // use unicode rather than degrees symbol, due to Android Studio warning - see https://sourceforge.net/p/OpenKamera/tickets/107/
            return "$sign$degrees\u00b0$minutes'$seconds\""
        }
    }
}
