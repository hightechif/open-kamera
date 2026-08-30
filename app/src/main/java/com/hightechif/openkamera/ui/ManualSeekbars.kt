/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.util.Log
import android.widget.SeekBar
import com.hightechif.openkamera.utils.MyDebug
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** This contains functionality related to the seekbars for manual controls.
 */
class ManualSeekbars {
    /*public static long exponentialScaling(double frac, double min, double max) {
		// We use S(frac) = A * e^(s * frac)
		// We want S(0) = min, S(1) = max
		// So A = min
		// and Ae^s = max
		// => s = ln(max/min)
		double s = Math.log(max / min);
		return (long)(min * Math.exp(s * frac) + 0.5f); // add 0.5f so we round to nearest
	}

    private static double exponentialScalingInverse(double value, double min, double max) {
		double s = Math.log(max / min);
		return Math.log(value / min) / s;
	}

	public void setProgressSeekbarExponential(SeekBar seekBar, double minValue, double maxValue, double value) {
		seekBar.setMax(manualN);
		double frac = exponentialScalingInverse(value, minValue, maxValue);
		int newValue = (int)(frac*manualN + 0.5); // add 0.5 for rounding
		if( newValue < 0 )
			newValue = 0;
		else if( newValue > manualN )
			newValue = manualN;
		seekBar.setProgress(newValue);
	}*/
    private var seekbarValuesWhiteBalance: MutableList<Long> = mutableListOf()
    private var seekbarValuesIso: MutableList<Long> = mutableListOf()
    private var seekbarValuesShutterSpeed: MutableList<Long> = mutableListOf()

    fun getWhiteBalanceTemperature(progress: Int): Int {
        return seekbarValuesWhiteBalance[progress].toInt()
    }

    fun getISO(progress: Int): Int {
        return seekbarValuesIso[progress].toInt()
    }

    fun getExposureTime(progress: Int): Long {
        return seekbarValuesShutterSpeed[progress]
    }

    fun setISOProgressBarToClosest(seekBar: SeekBar, currentIso: Long) {
        setProgressBarToClosest(seekBar, seekbarValuesIso, currentIso)
    }

    fun setProgressSeekbarWhiteBalance(
        seekBar: SeekBar,
        minWhiteBalance: Long,
        maxWhiteBalance: Long,
        currentWhiteBalance: Long
    ) {
        if (MyDebug.LOG) Log.d(TAG, "setProgressSeekbarWhiteBalance")
        val seekbarValues: MutableList<Long> = seekbarValuesWhiteBalance

        // min to max, per 100
        var i = minWhiteBalance
        while (i < maxWhiteBalance) {
            seekbarValues.add(i)
            i += 100
        }

        seekbarValues.add(maxWhiteBalance)

        seekBar.max = seekbarValues.size - 1

        setProgressBarToClosest(seekBar, seekbarValues, currentWhiteBalance)
    }

    fun setProgressSeekbarISO(seekBar: SeekBar, minIso: Long, maxIso: Long, currentIso: Long) {
        if (MyDebug.LOG) Log.d(TAG, "setProgressSeekbarISO")
        seekbarValuesIso = ArrayList()
        val seekbarValues: MutableList<Long> = seekbarValuesIso

        seekbarValues.add(minIso)

        // 1 to 99, per 1
        for (i in 1..99) {
            if (i in (minIso + 1)..<maxIso) seekbarValues.add(i.toLong())
        }

        // 100 to 500, per 5
        run {
            var i: Long = 100
            while (i < 500) {
                if (i in (minIso + 1)..<maxIso) seekbarValues.add(i)
                i += 5
            }
        }

        // 500 to 1000, per 10
        run {
            var i: Long = 500
            while (i < 1000) {
                if (i in (minIso + 1)..<maxIso) seekbarValues.add(i)
                i += 10
            }
        }

        // 1000 to 5000, per 50
        run {
            var i: Long = 1000
            while (i < 5000) {
                if (i in (minIso + 1)..<maxIso) seekbarValues.add(i)
                i += 50
            }
        }

        // 5000 to 10000, per 100
        var i: Long = 5000
        while (i < 10000) {
            if (i in (minIso + 1)..<maxIso) seekbarValues.add(i)
            i += 100
        }

        seekbarValues.add(maxIso)

        seekBar.max = seekbarValues.size - 1

        setProgressBarToClosest(seekBar, seekbarValues, currentIso)
    }

    fun setProgressSeekbarShutterSpeed(
        seekBar: SeekBar,
        minExposureTime: Long,
        maxExposureTime: Long,
        currentExposureTime: Long
    ) {
        if (MyDebug.LOG) Log.d(TAG, "setProgressSeekbarShutterSpeed")
        seekbarValuesShutterSpeed = ArrayList()
        val seekbarValues: MutableList<Long> = seekbarValuesShutterSpeed

        seekbarValues.add(minExposureTime)

        // 1/10,000 to 1/1,000
        for (i in 10 downTo 1) {
            val exposure = 1000000000L / (i * 1000L)
            if (exposure in (minExposureTime + 1)..<maxExposureTime) seekbarValues.add(
                exposure
            )
        }

        // 1/900 to 1/100
        for (i in 9 downTo 1) {
            val exposure = 1000000000L / (i * 100L)
            if (exposure in (minExposureTime + 1)..<maxExposureTime) seekbarValues.add(
                exposure
            )
        }

        // 1/90 to 1/60 (steps of 10)
        for (i in 9 downTo 6) {
            val exposure = 1000000000L / (i * 10L)
            if (exposure in (minExposureTime + 1)..<maxExposureTime) seekbarValues.add(
                exposure
            )
        }

        // 1/50 to 1/15 (steps of 5)
        run {
            var i = 50
            while (i >= 15) {
                val exposure = 1000000000L / i
                if (exposure in (minExposureTime + 1)..<maxExposureTime) seekbarValues.add(
                    exposure
                )
                i -= 5
            }
        }

        // 0.1 to 1.9, per 1.0s
        for (i in 1..19) {
            val exposure = (1000000000L / 10) * i
            if (exposure in (minExposureTime + 1)..<maxExposureTime) seekbarValues.add(
                exposure
            )
        }

        // 2 to 19, per 1s
        for (i in 2..19) {
            val exposure = 1000000000L * i
            if (exposure in (minExposureTime + 1)..<maxExposureTime) seekbarValues.add(
                exposure
            )
        }

        // 20 to 60, per 5s
        run {
            var i = 20
            while (i < 60) {
                val exposure = 1000000000L * i
                if (exposure in (minExposureTime + 1)..<maxExposureTime) seekbarValues.add(
                    exposure
                )
                i += 5
            }
        }

        // n.b., very long exposure times are not widely supported, but requested at https://sourceforge.net/p/OpenKamera/code/merge-requests/49/

        // 60 to 180, per 15s
        run {
            var i = 60
            while (i < 180) {
                val exposure = 1000000000L * i
                if (exposure in (minExposureTime + 1)..<maxExposureTime) seekbarValues.add(
                    exposure
                )
                i += 15
            }
        }

        // 180 to 600, per 60s
        run {
            var i = 180
            while (i < 600) {
                val exposure = 1000000000L * i
                if (exposure in (minExposureTime + 1)..<maxExposureTime) seekbarValues.add(
                    exposure
                )
                i += 60
            }
        }

        // 600 to 1200, per 120s
        var i = 600
        while (i <= 1200) {
            val exposure = 1000000000L * i
            if (exposure in (minExposureTime + 1)..<maxExposureTime) seekbarValues.add(
                exposure
            )
            i += 120
        }

        seekbarValues.add(maxExposureTime)

        seekBar.max = seekbarValues.size - 1

        setProgressBarToClosest(seekBar, seekbarValues, currentExposureTime)
    }

    companion object {
        private const val TAG = "ManualSeekbars"

        // the number of values on the seekbar used for manual focus distance
        private const val MANUAL_N = 1000

        fun seekbarScaling(frac: Double): Double {
            // For various seekbars, we want to use a non-linear scaling, so user has more control over smaller values
            return (100.0.pow(frac) - 1.0) / 99.0
        }

        private fun seekbarScalingInverse(scaling: Double): Double {
            return ln(99.0 * scaling + 1.0) / ln(100.0)
        }

        fun setProgressSeekbarScaled(
            seekBar: SeekBar,
            minValue: Double,
            maxValue: Double,
            value: Double
        ) {
            seekBar.max = MANUAL_N
            val scaling = (value - minValue) / (maxValue - minValue)
            val frac = seekbarScalingInverse(scaling)
            var newValue = (frac * MANUAL_N + 0.5).toInt() // add 0.5 for rounding
            if (newValue < 0) newValue = 0
            else if (newValue > MANUAL_N) newValue = MANUAL_N
            seekBar.progress = newValue
        }

        private fun setProgressBarToClosest(
            seekBar: SeekBar,
            seekbarValues: List<Long>,
            currentValue: Long
        ) {
            if (MyDebug.LOG) Log.d(TAG, "setProgressBarToClosest")
            var closestIndx = -1
            var minDist: Long = 0
            for (i in seekbarValues.indices) {
                val dist = abs((seekbarValues[i] - currentValue).toDouble()).toLong()
                /*if( MyDebug.LOG ) {
                Log.d(TAG, "seekbarValues[" + i + "]: " + seekbar_values.get(i));
                Log.d(TAG, "    dist: " + dist);
            }*/
                if (closestIndx == -1 || dist < minDist) {
                    closestIndx = i
                    minDist = dist
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "closest_indx: $closestIndx"
            )
            if (closestIndx != -1) seekBar.progress = closestIndx
        }
    }
}
