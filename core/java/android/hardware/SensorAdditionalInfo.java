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

    /**
     * Local geo-magnetic Field.
     *
     * Additional into to sensor hardware.  Local geomagnetic field information based on
     * device geo location. This type is primarily for for magnetic field calibration and rotation
     * vector sensor fusion.
     *
     * float[3]: strength (uT), declination and inclination angle (rad).
     * @hide
     */
    public static final int TYPE_LOCAL_GEOMAGNETIC_FIELD = 0x30000;

    /**
     * Local gravity acceleration strength.
     *
     * Additional info to sensor hardware for accelerometer calibration.
     *
     * float: gravitational acceleration norm in m/s^2.
     * @hide
     */
    public static final int TYPE_LOCAL_GRAVITY = 0x30001;

    /**
     * Device dock state.
     *
     * Additional info to sensor hardware indicating dock states of device.
     *
     * int32_t: dock state following definition of {@link android.content.Intent#EXTRA_DOCK_STATE}.
     *          Undefined values are ignored.
     * @hide
     */
    public static final int TYPE_DOCK_STATE = 0x30002;

    /**
     * High performance mode.
     *
     * Additional info to sensor hardware. Device is able to use up more power and take more
     * resources to improve throughput and latency in high performance mode. One possible use case
     * is virtual reality, when sensor latency need to be carefully controlled.
     *
     * int32_t: 1 or 0, denoting device is in or out of high performance mode, respectively.
     *          Other values are ignored.
     * @hide
     */
    public static final int TYPE_HIGH_PERFORMANCE_MODE = 0x30003;

    /**
     * Magnetic field calibration hint.
     *
     * Additional info to sensor hardware. Device is notified when manually triggered magnetic field
     * calibration procedure is started or stopped. The calibration procedure is assumed timed out
     * after 1 minute from start, even if an explicit stop is not received.
     *
     * int32_t: 1 for calibration start, 0 for stop, other values are ignored.
     * @hide
     */
    public static final int TYPE_MAGNETIC_FIELD_CALIBRATION = 0x30004;

    /**
     * Custom sensor info: array of float values interpreted by sensor based on the type
     * Any type between TYPE_CUSTOM_INFO <= info_type < TYPE_DEBUG_INFO may be
     * used to send custom sensor info.
     * @hide
     */
    public static final int TYPE_CUSTOM_INFO = 0x10000000;
    /** @hide */
    public static final int TYPE_DEBUG_INFO  = 0x40000000;

    SensorAdditionalInfo(
            Sensor aSensor, int aType, int aSerial, int [] aIntValues, float [] aFloatValues) {
        sensor = aSensor;
        type = aType;
        serial = aSerial;
        intValues = aIntValues;
        floatValues = aFloatValues;
    }

    /** @hide */
    public static SensorAdditionalInfo createLocalGeomagneticField(
            float strength, float declination, float inclination) {
        if (strength < 10 || strength > 100 // much beyond extreme values on earth
                || declination < 0 || declination > Math.PI
                || inclination < -Math.PI / 2 || inclination > Math.PI / 2) {
            throw new IllegalArgumentException("Geomagnetic field info out of range");
        }

        return new SensorAdditionalInfo(
                null, TYPE_LOCAL_GEOMAGNETIC_FIELD, 0,
                null, new float[] { strength, declination, inclination});
    }
    /** @hide */
    public static SensorAdditionalInfo createCustomInfo(Sensor aSensor, int type, float [] data) {
        if (type < TYPE_CUSTOM_INFO || type >= TYPE_DEBUG_INFO || aSensor == null) {
            throw new IllegalArgumentException("invalid parameter(s): type: " + type +
                    "; sensor: " + aSensor);
        }

        return new SensorAdditionalInfo(aSensor, type, 0, null, data);
    }
}
