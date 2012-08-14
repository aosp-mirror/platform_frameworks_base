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
     * A constant describing an accelerometer sensor type. See
     * {@link android.hardware.SensorEvent#values SensorEvent.values} for more
     * details.
     */
    public static final int TYPE_ACCELEROMETER = 1;

    /**
     * A constant describing a magnetic field sensor type. See
     * {@link android.hardware.SensorEvent#values SensorEvent.values} for more
     * details.
     */
    public static final int TYPE_MAGNETIC_FIELD = 2;

    /**
     * A constant describing an orientation sensor type. See
     * {@link android.hardware.SensorEvent#values SensorEvent.values} for more
     * details.
     *
     * @deprecated use {@link android.hardware.SensorManager#getOrientation
     *             SensorManager.getOrientation()} instead.
     */
    @Deprecated
    public static final int TYPE_ORIENTATION = 3;

    /** A constant describing a gyroscope sensor type */
    public static final int TYPE_GYROSCOPE = 4;

    /**
     * A constant describing a light sensor type. See
     * {@link android.hardware.SensorEvent#values SensorEvent.values} for more
     * details.
     */
    public static final int TYPE_LIGHT = 5;

    /** A constant describing a pressure sensor type */
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
     * A constant describing a proximity sensor type. See
     * {@link android.hardware.SensorEvent#values SensorEvent.values} for more
     * details.
     */
    public static final int TYPE_PROXIMITY = 8;

    /**
     * A constant describing a gravity sensor type.
     * See {@link android.hardware.SensorEvent SensorEvent}
     * for more details.
     */
    public static final int TYPE_GRAVITY = 9;

    /**
     * A constant describing a linear acceleration sensor type.
     * See {@link android.hardware.SensorEvent SensorEvent}
     * for more details.
     */
    public static final int TYPE_LINEAR_ACCELERATION = 10;

    /**
     * A constant describing a rotation vector sensor type.
     * See {@link android.hardware.SensorEvent SensorEvent}
     * for more details.
     */
    public static final int TYPE_ROTATION_VECTOR = 11;

    /**
     * A constant describing a relative humidity sensor type.
     * See {@link android.hardware.SensorEvent SensorEvent}
     * for more details.
     */
    public static final int TYPE_RELATIVE_HUMIDITY = 12;

    /** A constant describing an ambient temperature sensor type */
    public static final int TYPE_AMBIENT_TEMPERATURE = 13;

    /** 
     * A constant describing all sensor types.
     */
    public static final int TYPE_ALL = -1;

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

    int getHandle() {
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
