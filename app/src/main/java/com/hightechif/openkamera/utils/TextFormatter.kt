package com.hightechif.openkamera.utils

import android.content.Context
import android.location.Location
import android.util.Log
import com.hightechif.openkamera.R
import com.hightechif.openkamera.sensors.LocationSupplier
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt


/** Handles various text formatting options, used for photo stamp and video subtitles.
 */
class TextFormatter internal constructor(private val context: Context) {
    private val decimalFormat = DecimalFormat("#0.0")

    private fun getDistanceString(distance: Double, preferenceUnitsDistance: String?): String {
        var convertedDistance = distance
        var units = context.resources.getString(R.string.metres_abbreviation)
        if (preferenceUnitsDistance == "preference_units_distance_ft") {
            convertedDistance = 3.28084 * distance
            units = context.resources.getString(R.string.feet_abbreviation)
        }
        return decimalFormat.format(convertedDistance) + units
    }

    /** Formats the GPS information according to the user preferenceStampGpsformat preferenceStampTimeformat.
     * Returns "" if preferenceStampGpsformat is "preference_stamp_gpsformat_none", or both storeLocation and
     * storeGeoDirection are false.
     */
    fun getGPSString(
        preferenceStampGpsformat: String?,
        preferenceUnitsDistance: String?,
        storeLocation: Boolean,
        location: Location?,
        storeGeoDirection: Boolean,
        geoDirection: Double
    ): String {
        var gpsStamp = ""
        if (preferenceStampGpsformat != "preference_stamp_gpsformat_none") {
            if (storeLocation) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "location: $location"
                )
                gpsStamp += if (preferenceStampGpsformat == "preference_stamp_gpsformat_dms") LocationSupplier.locationToDMS(
                    location!!.latitude
                ) + ", " + LocationSupplier.locationToDMS(location.longitude)
                else Location.convert(
                    location!!.latitude,
                    Location.FORMAT_DEGREES
                ) + ", " + Location.convert(location.longitude, Location.FORMAT_DEGREES)
                if (location.hasAltitude()) {
                    gpsStamp += ", " + getDistanceString(
                        location.altitude,
                        preferenceUnitsDistance
                    )
                }
            }
            if (storeGeoDirection) {
                var geoAngle = Math.toDegrees(geoDirection).toFloat()
                if (geoAngle < 0.0f) {
                    geoAngle += 360.0f
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "geo_angle: $geoAngle"
                )
                if (gpsStamp.isNotEmpty()) gpsStamp += ", "
                gpsStamp += geoAngle.roundToInt().toString() + 0x00B0.toChar()
            }
        }
        // don't log gpsStamp, in case of privacy!
        return gpsStamp
    }

    companion object {
        private const val TAG = "TextFormatter"

        /** Formats the date according to the user preference preferenceStampDateformat.
         * Returns "" if preferenceStampDateformat is "preference_stamp_dateformat_none".
         */
        fun getDateString(preferenceStampDateformat: String?, date: Date?): String {
            var dateStamp = ""
            if (date == null) return dateStamp
            if (preferenceStampDateformat != "preference_stamp_dateformat_none") {
                dateStamp = when (preferenceStampDateformat) {
                    "preference_stamp_dateformat_yyyymmdd" ->                     // use dashes instead of slashes - this should follow https://en.wikipedia.org/wiki/ISO_8601
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)

                    "preference_stamp_dateformat_ddmmyyyy" -> SimpleDateFormat(
                        "dd/MM/yyyy",
                        Locale.getDefault()
                    ).format(date)

                    "preference_stamp_dateformat_mmddyyyy" -> SimpleDateFormat(
                        "MM/dd/yyyy",
                        Locale.getDefault()
                    ).format(date)

                    else -> DateFormat.getDateInstance().format(date)
                }
            }
            return dateStamp
        }

        /** Formats the time according to the user preference preferenceStampTimeformat.
         * Returns "" if preferenceStampTimeformat is "preference_stamp_timeformat_none".
         */
        fun getTimeString(preferenceStampTimeformat: String?, date: Date?): String {
            var timeStamp = ""
            if (date == null) return timeStamp
            if (preferenceStampTimeformat != "preference_stamp_timeformat_none") {
                timeStamp =
                    when (preferenceStampTimeformat) {
                        "preference_stamp_timeformat_12hour" -> SimpleDateFormat(
                            "hh:mm:ss a",
                            Locale.getDefault()
                        ).format(date)

                        "preference_stamp_timeformat_24hour" -> SimpleDateFormat(
                            "HH:mm:ss",
                            Locale.getDefault()
                        ).format(date)

                        else -> DateFormat.getTimeInstance().format(date)
                    }
            }
            return timeStamp
        }

        fun formatTimeMS(timeMs: Long): String {
            val ms = (timeMs).toInt() % 1000
            val seconds = (timeMs / 1000).toInt() % 60
            val minutes = ((timeMs / (1000 * 60)) % 60).toInt()
            val hours = ((timeMs / (1000 * 60 * 60))).toInt()
            return String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d,%03d",
                hours,
                minutes,
                seconds,
                ms
            )
        }
    }
}
