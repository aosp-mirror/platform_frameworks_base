/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class represents a {@link android.hardware.Sensor Sensor} additional information frame,
 * which is reported through listener callback {@link
 * android.hardware.SensorEventCallback#onSensorAdditionalInfo onSensorAdditionalInfo}.
 *
 * @see SensorManager
 * @see SensorEventCallback
 * @see Sensor
 *
 */

public class SensorAdditionalInfo {

    /**
     * The sensor that generated this event. See
     * {@link android.hardware.SensorManager SensorManager} for details.
     */
    public final Sensor sensor;

    /**
     * Type of this additional info frame.
     */
    public final int type;

    /**
     * Sequence number of frame for a certain type.
     */
    public final int serial;

    /**
     * Additional info payload data represented in float values. Depending on the type of
     * information, this may be null.
     */
    public final float[] floatValues;

    /**
     * Additional info payload data represented in int values. Depending on the type of information,
     * this may be null.
     */
    public final int[] intValues;

    /**
     * Typical values of additional infomation type. The set of values is subject to extension in
     * newer versions and vendors have the freedom of define their own custom values.
     *
     * @hide
     */
    @IntDef({TYPE_FRAME_BEGIN, TYPE_FRAME_END, TYPE_UNTRACKED_DELAY, TYPE_INTERNAL_TEMPERATURE,
             TYPE_VEC3_CALIBRATION, TYPE_SENSOR_PLACEMENT, TYPE_SAMPLING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AdditionalInfoType {}

    /**
     * Mark the beginning of a set of additional info frames.
     */
    public static final int TYPE_FRAME_BEGIN = 0;

    /**
     * Mark the end of a set of additional info frames.
     */
    public static final int TYPE_FRAME_END = 1;

    /**
     * Untracked delay. Delays that are introduced by data processing, such as filtering, which is
     * not taken into account by sensor timestamps.
     *
     * Payload:
     *     floatValues[0]: delay estimation in seconds
     *     floatValues[1]: delay estimation standard deviation
     */
    public static final int TYPE_UNTRACKED_DELAY = 0x10000;

    /**
     * Internal temperature. Sensor hardware device internal temperature.
     *
     * Payload:
     *     floatValues[0]: internal temperature in Celsius.
     */
    public static final int TYPE_INTERNAL_TEMPERATURE = 0x10001;

    /**
     * Vector calibration parameter. Calibration applied to a sensor with 3 elements vector output,
     * such as accelerometer, gyro, etc.
     *
     * Payload:
     *     floatValues[0..11]: First 3 rows of a homogeneous matrix in row major order that captures
     *     any linear transformation, including rotation, scaling, shear, shift.
     */
    public static final int TYPE_VEC3_CALIBRATION = 0x10002;

    /**
     * Sensor placement. Describes location and installation angle of the sensor device.
     *
     * Payload:
     *     floatValues[0..11]: First 3 rows of homogeneous matrix in row major order that describes
     *     the location and orientation of the sensor. Origin of reference will be the mobile device
     *     geometric sensor. Reference frame is defined as the same as Android sensor frame.
     */
    public static final int TYPE_SENSOR_PLACEMENT = 0x10003;

    /**
     * Sampling parameter. Describes the raw sample period and estimated jitter of sample time in
     * terms of standard deviation.
     *
     * Payload:
     *     floatValues[0]: raw sample period in seconds.
     *     floatValues[1]: standard deviation of sampling period.
     */
    public static final int TYPE_SAMPLING = 0x10004;

    SensorAdditionalInfo(
            Sensor aSensor, int aType, int aSerial, int [] aIntValues, float [] aFloatValues) {
        sensor = aSensor;
        type = aType;
        serial = aSerial;
        intValues = aIntValues;
        floatValues = aFloatValues;
    }
}
