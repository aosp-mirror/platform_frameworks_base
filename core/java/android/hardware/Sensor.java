/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package android.hardware;

import android.os.Build;

/**
 * Class representing a sensor. Use {@link SensorManager#getSensorList} to get
 * the list of available Sensors.
 *
 * @see SensorManager
 * @see SensorEventListener
 * @see SensorEvent
 *
 */
public final class Sensor {

    /**
     * A constant describing an accelerometer sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_ACCELEROMETER = 1;

    /**
     * A constant describing a magnetic field sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_MAGNETIC_FIELD = 2;

    /**
     * A constant describing an orientation sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     *
     * @deprecated use {@link android.hardware.SensorManager#getOrientation
     *             SensorManager.getOrientation()} instead.
     */
    @Deprecated
    public static final int TYPE_ORIENTATION = 3;

    /** A constant describing a gyroscope sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details. */
    public static final int TYPE_GYROSCOPE = 4;

    /**
     * A constant describing a light sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_LIGHT = 5;

    /** A constant describing a pressure sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details. */
    public static final int TYPE_PRESSURE = 6;

    /**
     * A constant describing a temperature sensor type
     *
     * @deprecated use
     *             {@link android.hardware.Sensor#TYPE_AMBIENT_TEMPERATURE
     *             Sensor.TYPE_AMBIENT_TEMPERATURE} instead.
     */
    @Deprecated
    public static final int TYPE_TEMPERATURE = 7;

    /**
     * A constant describing a proximity sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_PROXIMITY = 8;

    /**
     * A constant describing a gravity sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_GRAVITY = 9;

    /**
     * A constant describing a linear acceleration sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_LINEAR_ACCELERATION = 10;

    /**
     * A constant describing a rotation vector sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_ROTATION_VECTOR = 11;

    /**
     * A constant describing a relative humidity sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_RELATIVE_HUMIDITY = 12;

    /** A constant describing an ambient temperature sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details. */
    public static final int TYPE_AMBIENT_TEMPERATURE = 13;

    /**
     * A constant describing an uncalibrated magnetic field sensor type.
     * <p>
     * Similar to {@link #TYPE_MAGNETIC_FIELD} but the hard iron calibration (device calibration
     * due to distortions that arise from magnetized iron, steel or permanent magnets on the
     * device) is not considered in the given sensor values. However, such hard iron bias values
     * are returned to you separately in the result {@link android.hardware.SensorEvent#values}
     * so you may use them for custom calibrations. 
     * <p>Also, no periodic calibration is performed
     * (i.e. there are no discontinuities in the data stream while using this sensor) and
     * assumptions that the magnetic field is due to the Earth's poles is avoided, but
     * factory calibration and temperature compensation have been performed.
     * </p>
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values} for more
     * details.
     */
    public static final int TYPE_MAGNETIC_FIELD_UNCALIBRATED = 14;

    /**
     * A constant describing an uncalibrated rotation vector sensor type.
     * <p>Identical to {@link #TYPE_ROTATION_VECTOR} except that it doesn't
     * use the geomagnetic field. Therefore the Y axis doesn't
     * point north, but instead to some other reference, that reference is
     * allowed to drift by the same order of magnitude as the gyroscope
     * drift around the Z axis.
     * <p>
     * In the ideal case, a phone rotated and returning to the same real-world
     * orientation should report the same game rotation vector
     * (without using the earth's geomagnetic field). However, the orientation
     * may drift somewhat over time.
     * </p>
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values} for more
     * details.
     */

    public static final int TYPE_GAME_ROTATION_VECTOR = 15;

    /**
     * A constant describing an uncalibrated gyroscope sensor type.
     * <p>Similar to {@link #TYPE_GYROSCOPE} but no gyro-drift compensation has been performed
     * to adjust the given sensor values. However, such gyro-drift bias values
     * are returned to you separately in the result {@link android.hardware.SensorEvent#values}
     * so you may use them for custom calibrations.
     * <p>Factory calibration and temperature compensation is still applied
     * to the rate of rotation (angular speeds).
     * </p>
     * <p> See {@link android.hardware.SensorEvent#values SensorEvent.values} for more
     * details.
     */
    public static final int TYPE_GYROSCOPE_UNCALIBRATED = 16;

    /**
     * A constant describing the significant motion trigger sensor.
     * <p>
     * It triggers when an event occurs and then automatically disables
     * itself. The sensor continues to operate while the device is asleep
     * and will automatically wake the device to notify when significant
     * motion is detected. The application does not need to hold any wake
     * locks for this sensor to trigger.
     * <p>See {@link TriggerEvent} for more details.
     */
    public static final int TYPE_SIGNIFICANT_MOTION = 17;

    /**
     * A constant describing all sensor types.
     */
    public static final int TYPE_ALL = -1;

    /* Reporting mode constants for sensors. Each sensor will have exactly one
       reporting mode associated with it. */
    // Events are reported at a constant rate.
    static int REPORTING_MODE_CONTINUOUS = 1;

    // Events are reported only when the value changes.
    static int REPORTING_MODE_ON_CHANGE = 2;

    // Upon detection of an event, the sensor deactivates itself and then sends a single event.
    static int REPORTING_MODE_ONE_SHOT = 3;

    // TODO(): The following arrays are fragile and error-prone. This needs to be refactored.

    // Note: This needs to be updated, whenever a new sensor is added.
    private static int[] sSensorReportingModes = {
            REPORTING_MODE_CONTINUOUS, REPORTING_MODE_CONTINUOUS, REPORTING_MODE_CONTINUOUS,
            REPORTING_MODE_CONTINUOUS, REPORTING_MODE_ON_CHANGE, REPORTING_MODE_CONTINUOUS,
            REPORTING_MODE_ON_CHANGE, REPORTING_MODE_ON_CHANGE, REPORTING_MODE_CONTINUOUS,
            REPORTING_MODE_CONTINUOUS, REPORTING_MODE_CONTINUOUS, REPORTING_MODE_ON_CHANGE,
            REPORTING_MODE_ON_CHANGE, REPORTING_MODE_CONTINUOUS, REPORTING_MODE_CONTINUOUS,
            REPORTING_MODE_CONTINUOUS, REPORTING_MODE_ONE_SHOT };

    // Note: This needs to be updated, whenever a new sensor is added.
    // Holds the maximum length of the values array associated with {@link SensorEvent} or
    // {@link TriggerEvent} for the Sensor
    private static int[] sMaxLengthValuesArray = {
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 5, 3, 3,
            6, 4, 6, 1 };

    static int getReportingMode(Sensor sensor) {
        // mType starts from offset 1.
        return sSensorReportingModes[sensor.mType - 1];
    }

    static int getMaxLengthValuesArray(Sensor sensor, int sdkLevel) {
        // mType starts from offset 1.
        int len = sMaxLengthValuesArray[sensor.mType - 1];

        // RotationVector length has changed to 3 to 5 for API level 18
        // Set it to 3 for backward compatibility.
        if (sensor.getType() == Sensor.TYPE_ROTATION_VECTOR &&
                sdkLevel <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            len = 3;
        }
        return len;
    }

    /* Some of these fields are set only by the native bindings in
     * SensorManager.
     */
    private String  mName;
    private String  mVendor;
    private int     mVersion;
    private int     mHandle;
    private int     mType;
    private float   mMaxRange;
    private float   mResolution;
    private float   mPower;
    private int     mMinDelay;

    Sensor() {
    }

    /**
     * @return name string of the sensor.
     */
    public String getName() {
        return mName;
    }

    /**
     * @return vendor string of this sensor.
     */
    public String getVendor() {
        return mVendor;
    }

    /**
     * @return generic type of this sensor.
     */
    public int getType() {
        return mType;
    }

    /**
     * @return version of the sensor's module.
     */
    public int getVersion() {
        return mVersion;
    }

    /**
     * @return maximum range of the sensor in the sensor's unit.
     */
    public float getMaximumRange() {
        return mMaxRange;
    }

    /**
     * @return resolution of the sensor in the sensor's unit.
     */
    public float getResolution() {
        return mResolution;
    }

    /**
     * @return the power in mA used by this sensor while in use
     */
    public float getPower() {
        return mPower;
    }

    /**
     * @return the minimum delay allowed between two events in microsecond
     * or zero if this sensor only returns a value when the data it's measuring
     * changes.
     */
    public int getMinDelay() {
        return mMinDelay;
    }

    /** @hide */
    public int getHandle() {
        return mHandle;
    }

    void setRange(float max, float res) {
        mMaxRange = max;
        mResolution = res;
    }

    @Override
    public String toString() {
        return "{Sensor name=\"" + mName + "\", vendor=\"" + mVendor + "\", version=" + mVersion
                + ", type=" + mType + ", maxRange=" + mMaxRange + ", resolution=" + mResolution
                + ", power=" + mPower + ", minDelay=" + mMinDelay + "}";
    }
}
