/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.hightechif.openkamera.utils.MyDebug
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt


/** Handles gyro sensor.
 */
class GyroSensor internal constructor(context: Context) : SensorEventListener {
    private val mSensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val mSensorAccel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var isRecording: Boolean = false
        private set
    private var timestamp: Long = 0

    private val deltaRotationVector = FloatArray(4)
    private var hasGyroVector = false
    private val gyroVector = FloatArray(3)
    private val currentRotationMatrix = FloatArray(9)
    private val currentRotationMatrixGyroOnly = FloatArray(9)
    private val deltaRotationMatrix = FloatArray(9)
    private val tempMatrix = FloatArray(9)
    private val temp2Matrix = FloatArray(9)

    private var hasInitAccel = false
    private val initAccelVector = FloatArray(3)
    private val accelVector = FloatArray(3)

    private var hasOriginalRotationMatrix = false
    private val originalRotationMatrix = FloatArray(9)
    private var hasRotationVector = false
    private val rotationVector = FloatArray(3)

    // temporary vectors:
    private val tempVector = FloatArray(3)
    private val inVector = FloatArray(3)

    interface TargetCallback {
        /** Called when the target has been achieved.
         * @param indx Index of the target that has been achieved.
         */
        fun onAchieved(indx: Int)

        /* Called when the orientation is significantly far from the target.
         */
        fun onTooFar()
    }

    private var hasTarget = false

    //private final float [] targetVector = new float[3];
    private val targetVectors: MutableList<FloatArray> = ArrayList()
    private var targetAngle = 0f // target angle in radians
    private var uprightAngleTol = 0f // in radians
    private var targetAchieved = false
    private var tooFarAngle = 0f // in radians
    private var targetCallback: TargetCallback? = null
    private var hasLastTargetAngle = false
    private var lastTargetAngle = 0f
    var isUpright: Int =
        0 // if hasTarget==true, this stores whether the "upright" orientation of the device is close enough to the orientation when recording was started: 0 for yes, otherwise -1 for too anti-clockwise, +1 for too clockwise
        private set

    init {
        //mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        //mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        //mSensorAccel = null;
        if (MyDebug.LOG) {
            Log.d(TAG, "GyroSensor")
            if (mSensor == null) Log.d(TAG, "gyroscope not available")
            else if (mSensorAccel == null) Log.d(TAG, "accelerometer not available")
        }
        setToIdentity()
    }

    fun hasSensors(): Boolean {
        // even though the gyro sensor works if mSensorAccel is not present, for best behavior we require them both
        return mSensor != null && mSensorAccel != null
    }

    private fun setToIdentity() {
        for (i in 0..8) {
            currentRotationMatrix[i] = 0.0f
        }
        currentRotationMatrix[0] = 1.0f
        currentRotationMatrix[4] = 1.0f
        currentRotationMatrix[8] = 1.0f
        System.arraycopy(currentRotationMatrix, 0, currentRotationMatrixGyroOnly, 0, 9)

        for (i in 0..2) {
            initAccelVector[i] = 0.0f
            // don't set accelVector, rotationVector, gyroVector to 0 here, as we continually smooth the values even when not recording
        }
        hasInitAccel = false
        hasOriginalRotationMatrix = false
    }

    /** Helper method to multiply the transpose of a 3x3 matrix with a 3D vector.
     * For 3x3 rotation (orthonormal) matrices, the transpose is the inverse.
     */
    private fun transformTransposeVector(
        result: FloatArray,
        matrix: FloatArray,
        vector: FloatArray
    ) {
        // result[i] = matrix[ji] . vector[j]
        for (i in 0..2) {
            result[i] = 0.0f
            for (j in 0..2) {
                result[i] += getMatrixComponent(matrix, j, i) * vector[j]
            }
        }
    }

    /* We should enable sensors before startRecording(), so that we can apply smoothing to the
     * sensors to reduce noise.
     * This should be limited to when we might want to use the gyro, to help battery life.
     */
    fun enableSensors() {
        if (MyDebug.LOG) Log.d(TAG, "enableSensors")
        hasRotationVector = false
        hasGyroVector = false
        for (i in 0..2) {
            accelVector[i] = 0.0f
            rotationVector[i] = 0.0f
            gyroVector[i] = 0.0f
        }

        if (mSensor != null) mSensorManager.registerListener(
            this,
            mSensor,
            SensorManager.SENSOR_DELAY_UI
        )
        if (mSensorAccel != null) mSensorManager.registerListener(
            this,
            mSensorAccel,
            SensorManager.SENSOR_DELAY_UI
        )
    }

    fun disableSensors() {
        if (MyDebug.LOG) Log.d(TAG, "disableSensors")
        mSensorManager.unregisterListener(this)
    }

    fun startRecording() {
        if (MyDebug.LOG) Log.d(TAG, "startRecording")
        isRecording = true
        timestamp = 0
        setToIdentity()
    }

    fun stopRecording() {
        if (isRecording) {
            if (MyDebug.LOG) Log.d(TAG, "stopRecording")
            isRecording = false
            timestamp = 0
        }
    }

    fun setTarget(
        targetX: Float,
        targetY: Float,
        targetZ: Float,
        targetAngle: Float,
        uprightAngleTol: Float,
        tooFarAngle: Float,
        targetCallback: TargetCallback?
    ) {
        this.hasTarget = true
        targetVectors.clear()
        addTarget(targetX, targetY, targetZ)
        this.targetAngle = targetAngle
        this.uprightAngleTol = uprightAngleTol
        this.tooFarAngle = tooFarAngle
        this.targetCallback = targetCallback
        this.hasLastTargetAngle = false
        this.lastTargetAngle = 0.0f
    }

    fun addTarget(targetX: Float, targetY: Float, targetZ: Float) {
        val vector = floatArrayOf(targetX, targetY, targetZ)
        targetVectors.add(vector)
    }

    fun clearTarget() {
        this.hasTarget = false
        targetVectors.clear()
        this.targetCallback = null
        this.hasLastTargetAngle = false
        this.lastTargetAngle = 0.0f
    }

    fun disableTargetCallback() {
        this.targetCallback = null
    }

    fun hasTarget(): Boolean {
        return this.hasTarget
    }

    fun isTargetAchieved(): Boolean {
        return this.hasTarget && this.targetAchieved
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
    }

    private fun adjustGyroForAccel() {
        if (timestamp == 0L) {
            // don't have a gyro matrix yet
            return
        } else if (!hasInitAccel) {
            return
        }

        /*if( true )
            return;*/
        // don't use accelerometer for now

        //transformVector(tempVector, currentRotationMatrix, initAccelVector);
        // tempVector is now the initAccelVector transformed by the gyro matrix
        //transformTransposeVector(tempVector, currentRotationMatrix, initAccelVector);
        transformVector(tempVector, currentRotationMatrix, accelVector)
        // tempVector is now the accelVector transformed by the gyro matrix
        var cosAngle =
            (tempVector[0] * initAccelVector[0] + tempVector[1] * initAccelVector[1] + tempVector[2] * initAccelVector[2]).toDouble()
        /*if( MyDebug.LOG ) {
            Log.d(TAG, "adjustGyroForAccel:");
            Log.d(TAG, "### currentRotationMatrix row 0: " + currentRotationMatrix[0] + " , " + currentRotationMatrix[1] + " , " + currentRotationMatrix[2]);
            Log.d(TAG, "### currentRotationMatrix row 1: " + currentRotationMatrix[3] + " , " + currentRotationMatrix[4] + " , " + currentRotationMatrix[5]);
            Log.d(TAG, "### currentRotationMatrix row 2: " + currentRotationMatrix[6] + " , " + currentRotationMatrix[7] + " , " + currentRotationMatrix[8]);
            Log.d(TAG, "### initAccelVector: " + initAccelVector[0] + " , " + initAccelVector[1] + " , " + initAccelVector[2]);
            Log.d(TAG, "### accelVector: " + accelVector[0] + " , " + accelVector[1] + " , " + accelVector[2]);
            Log.d(TAG, "### tempVector: " + tempVector[0] + " , " + tempVector[1] + " , " + tempVector[2]);
            Log.d(TAG, "### cosAngle: " + cosAngle);
        }*/
        if (cosAngle >= 0.99999999995) {
            // gyroscope already matches accelerometer
            return
        }

        var angle = acos(cosAngle)
        angle *= 0.02 // filter
        cosAngle = cos(angle)

        /*
        // compute matrix to transform tempVector to accelVector
        // compute (tempVector X accelVector) normalized
        double aX = tempVector[1] * accelVector[2] - tempVector[2] * accelVector[1];
        double aY = tempVector[2] * accelVector[0] - tempVector[0] * accelVector[2];
        double aZ = tempVector[0] * accelVector[1] - tempVector[1] * accelVector[0];
        */
        // compute matrix to transform tempVector to initAccelVector
        // compute (tempVector X initAccelVector) normalised
        var aX =
            (tempVector[1] * initAccelVector[2] - tempVector[2] * initAccelVector[1]).toDouble()
        var aY =
            (tempVector[2] * initAccelVector[0] - tempVector[0] * initAccelVector[2]).toDouble()
        var aZ =
            (tempVector[0] * initAccelVector[1] - tempVector[1] * initAccelVector[0]).toDouble()
        val aMag = sqrt(aX * aX + aY * aY + aZ * aZ)
        if (aMag < 1.0e-5) {
            // parallel or anti-parallel case
            return
        }
        aX /= aMag
        aY /= aMag
        aZ /= aMag
        val sinAngle = sqrt(1.0 - cosAngle * cosAngle)
        // from http://immersivemath.com/forum/question/rotation-matrix-from-one-vector-to-another/
        setMatrixComponent(tempMatrix, 0, 0, (aX * aX * (1.0 - cosAngle) + cosAngle).toFloat())
        setMatrixComponent(
            tempMatrix,
            0,
            1,
            (aX * aY * (1.0 - cosAngle) - sinAngle * aZ).toFloat()
        )
        setMatrixComponent(
            tempMatrix,
            0,
            2,
            (aX * aZ * (1.0 - cosAngle) + sinAngle * aY).toFloat()
        )
        setMatrixComponent(
            tempMatrix,
            1,
            0,
            (aX * aY * (1.0 - cosAngle) + sinAngle * aZ).toFloat()
        )
        setMatrixComponent(tempMatrix, 1, 1, (aY * aY * (1.0 - cosAngle) + cosAngle).toFloat())
        setMatrixComponent(
            tempMatrix,
            1,
            2,
            (aY * aZ * (1.0 - cosAngle) - sinAngle * aX).toFloat()
        )
        setMatrixComponent(
            tempMatrix,
            2,
            0,
            (aX * aZ * (1.0 - cosAngle) - sinAngle * aY).toFloat()
        )
        setMatrixComponent(
            tempMatrix,
            2,
            1,
            (aY * aZ * (1.0 - cosAngle) + sinAngle * aX).toFloat()
        )
        setMatrixComponent(tempMatrix, 2, 2, (aZ * aZ * (1.0 - cosAngle) + cosAngle).toFloat())
        /*if( MyDebug.LOG ) {
            // test:
            System.arraycopy(tempVector, 0, inVector, 0, 3);
            transformVector(tempVector, tempMatrix, inVector);
            Log.d(TAG, "### tempMatrix row 0: " + tempMatrix[0] + " , " + tempMatrix[1] + " , " + tempMatrix[2]);
            Log.d(TAG, "### tempMatrix row 1: " + tempMatrix[3] + " , " + tempMatrix[4] + " , " + tempMatrix[5]);
            Log.d(TAG, "### tempMatrix row 2: " + tempMatrix[6] + " , " + tempMatrix[7] + " , " + tempMatrix[8]);
            Log.d(TAG, "### rotated tempVector: " + tempVector[0] + " , " + tempVector[1] + " , " + tempVector[2]);
        }*/
        // replace currentRotationMatrix with tempMatrix.currentRotationMatrix
        // since [tempMatrix.currentRotationMatrix].[initAccelVector] = tempMatrix.tempVector = accelVector
        // since [tempMatrix.currentRotationMatrix].[accelVector] = tempMatrix.tempVector = initAccelVector
        for (i in 0..2) {
            for (j in 0..2) {
                var value = 0.0f
                // temp2Matrix[ij] = tempMatrix[ik] * currentRotationMatrix[kj]
                for (k in 0..2) {
                    value += getMatrixComponent(tempMatrix, i, k) * getMatrixComponent(
                        currentRotationMatrix,
                        k,
                        j
                    )
                }
                setMatrixComponent(temp2Matrix, i, j, value)
            }
        }

        System.arraycopy(temp2Matrix, 0, currentRotationMatrix, 0, 9)

        /*if( MyDebug.LOG ) {
            // test:
            //transformVector(tempVector, temp2Matrix, initAccelVector);
            //transformTransposeVector(tempVector, currentRotationMatrix, initAccelVector);
            transformVector(tempVector, temp2Matrix, accelVector);
            Log.d(TAG, "### new currentRotationMatrix row 0: " + temp2Matrix[0] + " , " + temp2Matrix[1] + " , " + temp2Matrix[2]);
            Log.d(TAG, "### new currentRotationMatrix row 1: " + temp2Matrix[3] + " , " + temp2Matrix[4] + " , " + temp2Matrix[5]);
            Log.d(TAG, "### new currentRotationMatrix row 2: " + temp2Matrix[6] + " , " + temp2Matrix[7] + " , " + temp2Matrix[8]);
            Log.d(TAG, "### new tempVector: " + tempVector[0] + " , " + tempVector[1] + " , " + tempVector[2]);
        }*/
    }

    override fun onSensorChanged(event: SensorEvent) {
        /*if( MyDebug.LOG )
            Log.d(TAG, "onSensorChanged: " + event);*/
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val sensorAlpha = 0.8f // for filter
            for (i in 0..2) {
                //this.accelVector[i] = event.values[i];
                accelVector[i] =
                    sensorAlpha * accelVector[i] + (1.0f - sensorAlpha) * event.values[i]
            }

            val mag =
                sqrt((accelVector[0] * accelVector[0] + accelVector[1] * accelVector[1] + accelVector[2] * accelVector[2]).toDouble())
            if (mag > 1.0e-8) {
                accelVector[0] /= mag.toFloat()
                accelVector[1] /= mag.toFloat()
                accelVector[2] /= mag.toFloat()
            }

            if (!hasInitAccel) {
                System.arraycopy(accelVector, 0, initAccelVector, 0, 3)
                hasInitAccel = true
            }

            adjustGyroForAccel()
        } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            if (hasGyroVector) {
                val sensorAlpha = 0.5f // for filter
                for (i in 0..2) {
                    //this.gyroVector[i] = event.values[i];
                    gyroVector[i] =
                        sensorAlpha * gyroVector[i] + (1.0f - sensorAlpha) * event.values[i]
                }
            } else {
                System.arraycopy(event.values, 0, this.gyroVector, 0, 3)
                hasGyroVector = true
            }

            // This timestep's delta rotation to be multiplied by the current rotation
            // after computing it from the gyro sample data.
            if (timestamp != 0L) {
                val dT = (event.timestamp - timestamp) * NS2S
                // Axis of the rotation sample, not normalized yet.
                var axisX = gyroVector[0]
                var axisY = gyroVector[1]
                var axisZ = gyroVector[2]

                // Calculate the angular speed of the sample
                val omegaMagnitude =
                    sqrt((axisX * axisX + axisY * axisY + axisZ * axisZ).toDouble())

                // Normalize the rotation vector if it's big enough to get the axis
                // (that is, EPSILON should represent your maximum allowable margin of error)
                if (omegaMagnitude > 1.0e-5) {
                    axisX /= omegaMagnitude.toFloat()
                    axisY /= omegaMagnitude.toFloat()
                    axisZ /= omegaMagnitude.toFloat()
                }

                // Integrate around this axis with the angular speed by the timestep
                // in order to get a delta rotation from this sample over the timestep
                // We will convert this axis-angle representation of the delta rotation
                // into a quaternion before turning it into the rotation matrix.
                val thetaOverTwo = omegaMagnitude * dT / 2.0f
                val sinThetaOverTwo = sin(thetaOverTwo).toFloat()
                val cosThetaOverTwo = cos(thetaOverTwo).toFloat()
                deltaRotationVector[0] = sinThetaOverTwo * axisX
                deltaRotationVector[1] = sinThetaOverTwo * axisY
                deltaRotationVector[2] = sinThetaOverTwo * axisZ
                deltaRotationVector[3] = cosThetaOverTwo

                /*if( MyDebug.LOG ) {
                    Log.d(TAG, "### values: " + event.values[0] + " , " + event.values[1] + " , " + event.values[2]);
                    Log.d(TAG, "smoothed values: " + gyroVector[0] + " , " + gyroVector[1] + " , " + gyroVector[2]);
                }*/
                SensorManager.getRotationMatrixFromVector(deltaRotationMatrix, deltaRotationVector)
                // User code should concatenate the delta rotation we computed with the current rotation
                // in order to get the updated rotation.
                // currentRotationMatrix = currentRotationMatrix * deltaRotationMatrix;
                for (i in 0..2) {
                    for (j in 0..2) {
                        var value = 0.0f
                        // tempMatrix[ij] = currentRotationMatrix[ik] * deltaRotationMatrix[kj]
                        for (k in 0..2) {
                            value += getMatrixComponent(
                                currentRotationMatrix,
                                i,
                                k
                            ) * getMatrixComponent(deltaRotationMatrix, k, j)
                        }
                        setMatrixComponent(tempMatrix, i, j, value)
                    }
                }

                System.arraycopy(tempMatrix, 0, currentRotationMatrix, 0, 9)

                for (i in 0..2) {
                    for (j in 0..2) {
                        var value = 0.0f
                        // tempMatrix[ij] = currentRotationMatrixGyroOnly[ik] * deltaRotationMatrix[kj]
                        for (k in 0..2) {
                            value += getMatrixComponent(
                                currentRotationMatrixGyroOnly,
                                i,
                                k
                            ) * getMatrixComponent(deltaRotationMatrix, k, j)
                        }
                        setMatrixComponent(tempMatrix, i, j, value)
                    }
                }
                System.arraycopy(tempMatrix, 0, currentRotationMatrixGyroOnly, 0, 9)


                /*if( MyDebug.LOG ) {
                    setVector(inVector, 0.0f, 0.0f, -1.0f); // vector pointing behind the device's screen
                    transformVector(tempVector, currentRotationMatrix, inVector);
                    //transformTransposeVector(tempVector, currentRotationMatrix, inVector);
                    Log.d(TAG, "### gyro vector: " + tempVector[0] + " , " + tempVector[1] + " , " + tempVector[2]);
                }*/
                adjustGyroForAccel()
            }

            timestamp = event.timestamp
        } else if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR || event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            if (hasRotationVector) {
                //final float sensorAlpha = 0.7f; // for filter
                val sensorAlpha = 0.8f // for filter
                for (i in 0..2) {
                    //this.rotationVector[i] = event.values[i];
                    rotationVector[i] =
                        sensorAlpha * rotationVector[i] + (1.0f - sensorAlpha) * event.values[i]
                }
            } else {
                System.arraycopy(event.values, 0, this.rotationVector, 0, 3)
                hasRotationVector = true
            }

            SensorManager.getRotationMatrixFromVector(tempMatrix, rotationVector)

            if (!hasOriginalRotationMatrix) {
                System.arraycopy(tempMatrix, 0, originalRotationMatrix, 0, 9)
                hasOriginalRotationMatrix = event.values[3].toDouble() != 1.0
            }

            // current = originalT.new
            for (i in 0..2) {
                for (j in 0..2) {
                    var value = 0.0f
                    // currentRotationMatrix[ij] = originalRotationMatrix[ki] * tempMatrix[kj]
                    for (k in 0..2) {
                        value += getMatrixComponent(
                            originalRotationMatrix,
                            k,
                            i
                        ) * getMatrixComponent(tempMatrix, k, j)
                    }
                    setMatrixComponent(currentRotationMatrix, i, j, value)
                }
            }

            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "### values: " + event.values[0] + " , " + event.values[1] + " , " + event.values[2] + " , " + event.values[3]
                )
                Log.d(
                    TAG,
                    "    " + currentRotationMatrix[0] + " , " + currentRotationMatrix[1] + " , " + currentRotationMatrix[2]
                )
                Log.d(
                    TAG,
                    "    " + currentRotationMatrix[3] + " , " + currentRotationMatrix[4] + " , " + currentRotationMatrix[5]
                )
                Log.d(
                    TAG,
                    "    " + currentRotationMatrix[6] + " , " + currentRotationMatrix[7] + " , " + currentRotationMatrix[8]
                )
            }
        }

        if (hasTarget) {
            var nTooFar = 0
            targetAchieved = false
            for (indx in targetVectors.indices) {
                val targetVector = targetVectors[indx]
                // first check if we are still "upright"
                setVector(inVector, 0.0f, 1.0f, 0.0f) // vector pointing in "up" direction
                transformVector(tempVector, currentRotationMatrix, inVector)
                /*if( MyDebug.LOG ) {
                    Log.d(TAG, "### transformed vector up: " + tempVector[0] + " , " + tempVector[1] + " , " + tempVector[2]);
                }*/
                /*float sinAngleUp = tempVector[0];
                if( Math.abs(sinAngleUp) <= 0.017452406437f ) {  // 1 degree
                    isUpright = 0;
                }
                else
                    isUpright = (sinAngleUp > 0) ? 1 : -1;*/
                // store up vector
                isUpright = 0

                val ux = tempVector[0]
                val uy = tempVector[1]
                val uz = tempVector[2]

                // project up vector into plane perpendicular to targetVector
                // v' = v - (v.n)n
                val uDotN = ux * targetVector[0] + uy * targetVector[1] + uz * targetVector[2]
                var pUx = ux - uDotN * targetVector[0]
                val pUy = uy - uDotN * targetVector[1]
                var pUz = uz - uDotN * targetVector[2]
                /*if( MyDebug.LOG ) {
                    Log.d(TAG, "    u: " + ux + " , " + uy + " , " + uz);
                    Log.d(TAG, "    pU: " + pUx + " , " + pUy + " , " + pUz);
                }*/
                val pUMag = sqrt((pUx * pUx + pUy * pUy + pUz * pUz).toDouble())
                if (pUMag > 1.0e-5) {
                    /*if( MyDebug.LOG ) {
                        Log.d(TAG, "    pU norm: " + pUx/pUMag + " , " + pUy/pUMag + " , " + pUz/pUMag);
                    }*/
                    // normalise pU
                    pUx /= pUMag.toFloat()
                    //pUy /= pUMag; // commented out as not needed
                    pUz /= pUMag.toFloat()

                    // compute pU X (0 1 0)
                    val cx = -pUz
                    val cy = 0.0f
                    val cz = pUx
                    /*if( MyDebug.LOG ) {
                        Log.d(TAG, "    c: " + cx + " , " + cy + " , " + cz);
                    }*/
                    val sinAngleUp = sqrt((cx * cx + cy * cy + cz * cz).toDouble()).toFloat()
                    val angleUp = asin(sinAngleUp.toDouble()).toFloat()

                    setVector(
                        inVector,
                        0.0f,
                        0.0f,
                        -1.0f
                    ) // vector pointing behind the device's screen
                    transformVector(tempVector, currentRotationMatrix, inVector)

                    if (abs(angleUp.toDouble()) > this.uprightAngleTol) {
                        val dot = cx * tempVector[0] + cy * tempVector[1] + cz * tempVector[2]
                        isUpright = if (dot < 0) 1 else -1
                    }
                }

                val cosAngle =
                    tempVector[0] * targetVector[0] + tempVector[1] * targetVector[1] + tempVector[2] * targetVector[2]
                val angle = acos(cosAngle.toDouble()).toFloat()
                if (isUpright == 0) {
                    /*if( MyDebug.LOG )
                        Log.d(TAG, "gyro vector angle with target: " + Math.toDegrees(angle) + " degrees");*/
                    if (angle <= targetAngle) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "    ### achieved target angle: " + Math.toDegrees(angle.toDouble()) + " degrees"
                        )
                        targetAchieved = true
                        if (targetCallback != null) {
                            //targetCallback.onAchieved(indx);
                            if (hasLastTargetAngle) {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "        last target angle: " + Math.toDegrees(lastTargetAngle.toDouble()) + " degrees"
                                )
                                if (angle > lastTargetAngle) {
                                    // started to get worse, so call callback
                                    targetCallback!!.onAchieved(indx)
                                }
                                // else, don't call callback yet, as we may get closer to the target
                            }
                        }
                        // only bother setting the lastTargetAngle if within the target angle - otherwise we'll have problems if there is more than one target set
                        hasLastTargetAngle = true
                        lastTargetAngle = angle
                    }
                }

                if (angle > tooFarAngle) {
                    nTooFar++
                }
                /*if( MyDebug.LOG )
                Log.d(TAG, "targetAchieved? " + targetAchieved);*/
            }
            if (nTooFar > 0 && nTooFar == targetVectors.size) {
                if (targetCallback != null) {
                    targetCallback!!.onTooFar()
                }
            }
        }
    }

    /*  This returns a 3D vector, that represents the current direction that the device is pointing (looking towards the screen),
     *  relative to when startRecording() was called.
     *  That is, the coordinate system is defined by the device's initial orientation when startRecording() was called:
     *      X: -ve to +ve is left to right
     *      Y: -ve to +ve is down to up
     *      Z: -ve to +ve is out of the screen to behind the screen
     *  So if the device hasn't changed orientation, this will return (0, 0, -1).
     *  (1, 0, 0) means the device has rotated 90 degrees so it's now pointing to the right.
     * @param result An array of length 3 to store the returned vector.
     */
    /*void getRelativeVector(float [] result) {
        setVector(inVector, 0.0f, 0.0f, -1.0f); // vector pointing behind the device's screen
        transformVector(result, currentRotationMatrix, inVector);
    }*/
    /*void getRelativeInverseVector(float [] result) {
        setVector(inVector, 0.0f, 0.0f, -1.0f); // vector pointing behind the device's screen
        transformTransposeVector(result, currentRotationMatrix, inVector);
    }*/
    fun getRelativeInverseVector(out: FloatArray, `in`: FloatArray) {
        transformTransposeVector(out, currentRotationMatrix, `in`)
    }

    fun getRelativeInverseVectorGyroOnly(out: FloatArray, `in`: FloatArray) {
        transformTransposeVector(out, currentRotationMatrixGyroOnly, `in`)
    }

    fun getRotationMatrix(out: FloatArray) {
        System.arraycopy(currentRotationMatrix, 0, out, 0, 9)
    }

    // for testing
    fun testForceTargetAchieved(indx: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "testForceTargetAchieved: $indx"
        )
        if (targetCallback != null) {
            targetCallback!!.onAchieved(indx)
        }
    }

    companion object {
        private const val TAG = "GyroSensor"

        private const val NS2S = 1.0f / 1000000000.0f

        /** Helper method to set a 3D vector.
         */
        fun setVector(vector: FloatArray, x: Float, y: Float, z: Float) {
            vector[0] = x
            vector[1] = y
            vector[2] = z
        }

        /** Helper method to access the (i, j)th component of a 3x3 matrix.
         */
        private fun getMatrixComponent(matrix: FloatArray, row: Int, col: Int): Float {
            return matrix[row * 3 + col]
        }

        /** Helper method to set the (i, j)th component of a 3x3 matrix.
         */
        private fun setMatrixComponent(matrix: FloatArray, row: Int, col: Int, value: Float) {
            matrix[row * 3 + col] = value
        }

        /** Helper method to multiply 3x3 matrix with a 3D vector.
         */
        fun transformVector(result: FloatArray, matrix: FloatArray, vector: FloatArray) {
            // result[i] = matrix[ij] . vector[j]
            for (i in 0..2) {
                result[i] = 0.0f
                for (j in 0..2) {
                    result[i] += getMatrixComponent(matrix, i, j) * vector[j]
                }
            }
        }
    }
}
