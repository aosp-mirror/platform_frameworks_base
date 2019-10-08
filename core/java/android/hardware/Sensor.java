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

import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.os.Build;

/**
 * Class representing a sensor. Use {@link SensorManager#getSensorList} to get
 * the list of available sensors. For more information about Android sensors,
 * read the
 * <a href="/guide/topics/sensors/sensors_motion.html">Motion Sensors guide</a>.</p>
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
     * A constant string describing an accelerometer sensor type.
     *
     * @see #TYPE_ACCELEROMETER
     */
    public static final String STRING_TYPE_ACCELEROMETER = "android.sensor.accelerometer";

    /**
     * A constant describing a magnetic field sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_MAGNETIC_FIELD = 2;

    /**
     * A constant string describing a magnetic field sensor type.
     *
     * @see #TYPE_MAGNETIC_FIELD
     */
    public static final String STRING_TYPE_MAGNETIC_FIELD = "android.sensor.magnetic_field";

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

    /**
     * A constant string describing an orientation sensor type.
     *
     * @see #TYPE_ORIENTATION
     * @deprecated use {@link android.hardware.SensorManager#getOrientation
     *             SensorManager.getOrientation()} instead.
     */
    @Deprecated
    public static final String STRING_TYPE_ORIENTATION = "android.sensor.orientation";

    /**
     * A constant describing a gyroscope sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details. */
    public static final int TYPE_GYROSCOPE = 4;

    /**
     * A constant string describing a gyroscope sensor type.
     *
     * @see #TYPE_GYROSCOPE
     */
    public static final String STRING_TYPE_GYROSCOPE = "android.sensor.gyroscope";

    /**
     * A constant describing a light sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_LIGHT = 5;

    /**
     * A constant string describing a light sensor type.
     *
     * @see #TYPE_LIGHT
     */
    public static final String STRING_TYPE_LIGHT = "android.sensor.light";

    /**
     * A constant describing a pressure sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_PRESSURE = 6;

    /**
     * A constant string describing a pressure sensor type.
     *
     * @see #TYPE_PRESSURE
     */
    public static final String STRING_TYPE_PRESSURE = "android.sensor.pressure";

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
     * A constant string describing a temperature sensor type
     *
     * @see #TYPE_TEMPERATURE
     * @deprecated use
     *             {@link android.hardware.Sensor#STRING_TYPE_AMBIENT_TEMPERATURE
     *             Sensor.STRING_TYPE_AMBIENT_TEMPERATURE} instead.
     */
    @Deprecated
    public static final String STRING_TYPE_TEMPERATURE = "android.sensor.temperature";

    /**
     * A constant describing a proximity sensor type. This is a wake up sensor.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     * @see #isWakeUpSensor()
     */
    public static final int TYPE_PROXIMITY = 8;

    /**
     * A constant string describing a proximity sensor type.
     *
     * @see #TYPE_PROXIMITY
     */
    public static final String STRING_TYPE_PROXIMITY = "android.sensor.proximity";

    /**
     * A constant describing a gravity sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_GRAVITY = 9;

    /**
     * A constant string describing a gravity sensor type.
     *
     * @see #TYPE_GRAVITY
     */
    public static final String STRING_TYPE_GRAVITY = "android.sensor.gravity";

    /**
     * A constant describing a linear acceleration sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_LINEAR_ACCELERATION = 10;

    /**
     * A constant string describing a linear acceleration sensor type.
     *
     * @see #TYPE_LINEAR_ACCELERATION
     */
    public static final String STRING_TYPE_LINEAR_ACCELERATION =
            "android.sensor.linear_acceleration";

    /**
     * A constant describing a rotation vector sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_ROTATION_VECTOR = 11;

    /**
     * A constant string describing a rotation vector sensor type.
     *
     * @see #TYPE_ROTATION_VECTOR
     */
    public static final String STRING_TYPE_ROTATION_VECTOR = "android.sensor.rotation_vector";

    /**
     * A constant describing a relative humidity sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_RELATIVE_HUMIDITY = 12;

    /**
     * A constant string describing a relative humidity sensor type
     *
     * @see #TYPE_RELATIVE_HUMIDITY
     */
    public static final String STRING_TYPE_RELATIVE_HUMIDITY = "android.sensor.relative_humidity";

    /**
     * A constant describing an ambient temperature sensor type.
     * <p>See {@link android.hardware.SensorEvent#values SensorEvent.values}
     * for more details.
     */
    public static final int TYPE_AMBIENT_TEMPERATURE = 13;

    /**
     * A constant string describing an ambient temperature sensor type.
     *
     * @see #TYPE_AMBIENT_TEMPERATURE
     */
    public static final String STRING_TYPE_AMBIENT_TEMPERATURE =
            "android.sensor.ambient_temperature";

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
     * A constant string describing an uncalibrated magnetic field sensor type.
     *
     * @see #TYPE_MAGNETIC_FIELD_UNCALIBRATED
     */
    public static final String STRING_TYPE_MAGNETIC_FIELD_UNCALIBRATED =
            "android.sensor.magnetic_field_uncalibrated";

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
     * A constant string describing an uncalibrated rotation vector sensor type.
     *
     * @see #TYPE_GAME_ROTATION_VECTOR
     */
    public static final String STRING_TYPE_GAME_ROTATION_VECTOR =
            "android.sensor.game_rotation_vector";

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
     * A constant string describing an uncalibrated gyroscope sensor type.
     *
     * @see #TYPE_GYROSCOPE_UNCALIBRATED
     */
    public static final String STRING_TYPE_GYROSCOPE_UNCALIBRATED =
            "android.sensor.gyroscope_uncalibrated";

    /**
     * A constant describing a significant motion trigger sensor.
     * <p>
     * It triggers when an event occurs and then automatically disables
     * itself. The sensor continues to operate while the device is asleep
     * and will automatically wake the device to notify when significant
     * motion is detected. The application does not need to hold any wake
     * locks for this sensor to trigger. This is a wake up sensor.
     * <p>See {@link TriggerEvent} for more details.
     *
     * @see #isWakeUpSensor()
     */
    public static final int TYPE_SIGNIFICANT_MOTION = 17;

    /**
     * A constant string describing a significant motion trigger sensor.
     *
     * @see #TYPE_SIGNIFICANT_MOTION
     */
    public static final String STRING_TYPE_SIGNIFICANT_MOTION =
            "android.sensor.significant_motion";

    /**
     * A constant describing a step detector sensor.
     * <p>
     * A sensor of this type triggers an event each time a step is taken by the user. The only
     * allowed value to return is 1.0 and an event is generated for each step. Like with any other
     * event, the timestamp indicates when the event (here the step) occurred, this corresponds to
     * when the foot hit the ground, generating a high variation in acceleration. This sensor is
     * only for detecting every individual step as soon as it is taken, for example to perform dead
     * reckoning. If you only need aggregate number of steps taken over a period of time, register
     * for {@link #TYPE_STEP_COUNTER} instead. It is defined as a
     * {@link Sensor#REPORTING_MODE_SPECIAL_TRIGGER} sensor.
     * <p>
     * See {@link android.hardware.SensorEvent#values SensorEvent.values} for more details.
     */
    public static final int TYPE_STEP_DETECTOR = 18;

    /**
     * A constant string describing a step detector sensor.
     *
     * @see #TYPE_STEP_DETECTOR
     */
    public static final String STRING_TYPE_STEP_DETECTOR = "android.sensor.step_detector";

    /**
     * A constant describing a step counter sensor.
     * <p>
     * A sensor of this type returns the number of steps taken by the user since the last reboot
     * while activated. The value is returned as a float (with the fractional part set to zero) and
     * is reset to zero only on a system reboot. The timestamp of the event is set to the time when
     * the last step for that event was taken. This sensor is implemented in hardware and is
     * expected to be low power. If you want to continuously track the number of steps over a long
     * period of time, do NOT unregister for this sensor, so that it keeps counting steps in the
     * background even when the AP is in suspend mode and report the aggregate count when the AP
     * is awake. Application needs to stay registered for this sensor because step counter does not
     * count steps if it is not activated. This sensor is ideal for fitness tracking applications.
     * It is defined as an {@link Sensor#REPORTING_MODE_ON_CHANGE} sensor.
     * <p>
     * This sensor requires permission {@code android.permission.ACTIVITY_RECOGNITION}.
     * <p>
     * See {@link android.hardware.SensorEvent#values SensorEvent.values} for more details.
     */
    public static final int TYPE_STEP_COUNTER = 19;

    /**
     * A constant string describing a step counter sensor.
     *
     * @see #TYPE_STEP_COUNTER
     */
    public static final String STRING_TYPE_STEP_COUNTER = "android.sensor.step_counter";

    /**
     * A constant describing a geo-magnetic rotation vector.
     * <p>
     * Similar to {@link #TYPE_ROTATION_VECTOR}, but using a magnetometer instead of using a
     * gyroscope. This sensor uses lower power than the other rotation vectors, because it doesn't
     * use the gyroscope. However, it is more noisy and will work best outdoors.
     * <p>
     * This sensor requires permission {@code android.permission.ACTIVITY_RECOGNITION}.
     * <p>
     * See {@link android.hardware.SensorEvent#values SensorEvent.values} for more details.
     */
    public static final int TYPE_GEOMAGNETIC_ROTATION_VECTOR = 20;

    /**
     * A constant string describing a geo-magnetic rotation vector.
     *
     * @see #TYPE_GEOMAGNETIC_ROTATION_VECTOR
     */
    public static final String STRING_TYPE_GEOMAGNETIC_ROTATION_VECTOR =
            "android.sensor.geomagnetic_rotation_vector";

    /**
     * A constant describing a heart rate monitor.
     * <p>
     * The reported value is the heart rate in beats per minute.
     * <p>
     * The reported accuracy represents the status of the monitor during the reading. See the
     * {@code SENSOR_STATUS_*} constants in {@link android.hardware.SensorManager SensorManager}
     * for more details on accuracy/status values. In particular, when the accuracy is
     * {@code SENSOR_STATUS_UNRELIABLE} or {@code SENSOR_STATUS_NO_CONTACT}, the heart rate
     * value should be discarded.
     * <p>
     * This sensor requires permission {@code android.permission.BODY_SENSORS}.
     * It will not be returned by {@code SensorManager.getSensorsList} nor
     * {@code SensorManager.getDefaultSensor} if the application doesn't have this permission.
     */
    public static final int TYPE_HEART_RATE = 21;

    /**
     * A constant string describing a heart rate monitor.
     *
     * @see #TYPE_HEART_RATE
     */
    public static final String STRING_TYPE_HEART_RATE = "android.sensor.heart_rate";

    /**
     * A sensor of this type generates an event each time a tilt event is detected. A tilt event
     * is generated if the direction of the 2-seconds window average gravity changed by at
     * least 35 degrees since the activation of the sensor. It is a wake up sensor.
     *
     * @hide
     * @see #isWakeUpSensor()
     */
    public static final int TYPE_TILT_DETECTOR = 22;

    /**
     * A constant string describing a wake up tilt detector sensor type.
     *
     * @hide
     * @see #TYPE_TILT_DETECTOR
     */
    public static final String SENSOR_STRING_TYPE_TILT_DETECTOR =
            "android.sensor.tilt_detector";

    /**
     * A constant describing a wake gesture sensor.
     * <p>
     * Wake gesture sensors enable waking up the device based on a device specific motion.
     * <p>
     * When this sensor triggers, the device behaves as if the power button was pressed, turning the
     * screen on. This behavior (turning on the screen when this sensor triggers) might be
     * deactivated by the user in the device settings. Changes in settings do not impact the
     * behavior of the sensor: only whether the framework turns the screen on when it triggers.
     * <p>
     * The actual gesture to be detected is not specified, and can be chosen by the manufacturer of
     * the device. This sensor must be low power, as it is likely to be activated 24/7.
     * Values of events created by this sensors should not be used.
     *
     * @see #isWakeUpSensor()
     * @hide This sensor is expected to only be used by the system ui
     */
    public static final int TYPE_WAKE_GESTURE = 23;

    /**
     * A constant string describing a wake gesture sensor.
     *
     * @hide This sensor is expected to only be used by the system ui
     * @see #TYPE_WAKE_GESTURE
     */
    public static final String STRING_TYPE_WAKE_GESTURE = "android.sensor.wake_gesture";

    /**
     * A constant describing a wake gesture sensor.
     * <p>
     * A sensor enabling briefly turning the screen on to enable the user to
     * glance content on screen based on a specific motion.  The device should
     * turn the screen off after a few moments.
     * <p>
     * When this sensor triggers, the device turns the screen on momentarily
     * to allow the user to glance notifications or other content while the
     * device remains locked in a non-interactive state (dozing). This behavior
     * (briefly turning on the screen when this sensor triggers) might be deactivated
     * by the user in the device settings. Changes in settings do not impact the
     * behavior of the sensor: only whether the framework briefly turns the screen on
     * when it triggers.
     * <p>
     * The actual gesture to be detected is not specified, and can be chosen by the manufacturer of
     * the device. This sensor must be low power, as it is likely to be activated 24/7.
     * Values of events created by this sensors should not be used.
     *
     * @see #isWakeUpSensor()
     * @hide This sensor is expected to only be used by the system ui
     */
    public static final int TYPE_GLANCE_GESTURE = 24;

    /**
     * A constant string describing a wake gesture sensor.
     *
     * @hide This sensor is expected to only be used by the system ui
     * @see #TYPE_GLANCE_GESTURE
     */
    public static final String STRING_TYPE_GLANCE_GESTURE = "android.sensor.glance_gesture";

    /**
     * A constant describing a pick up sensor.
     *
     * A sensor of this type triggers when the device is picked up regardless of wherever it was
     * before (desk, pocket, bag). The only allowed return value is 1.0. This sensor deactivates
     * itself immediately after it triggers.
     *
     * @hide Expected to be used internally for always on display.
     */
    @UnsupportedAppUsage
    public static final int TYPE_PICK_UP_GESTURE = 25;

    /**
     * A constant string describing a pick up sensor.
     *
     * @hide This sensor is expected to be used internally for always on display.
     * @see #TYPE_PICK_UP_GESTURE
     */
    public static final String STRING_TYPE_PICK_UP_GESTURE = "android.sensor.pick_up_gesture";

    /**
     * A constant describing a wrist tilt gesture sensor.
     *
     * A sensor of this type triggers when the device face is tilted towards the user.
     * The only allowed return value is 1.0.
     * This sensor remains active until disabled.
     *
     * @hide This sensor is expected to only be used by the system ui
     */
    @SystemApi
    public static final int TYPE_WRIST_TILT_GESTURE = 26;

    /**
     * A constant string describing a wrist tilt gesture sensor.
     *
     * @hide This sensor is expected to only be used by the system ui
     * @see #TYPE_WRIST_TILT_GESTURE
     */
    @SystemApi
    public static final String STRING_TYPE_WRIST_TILT_GESTURE = "android.sensor.wrist_tilt_gesture";

    /**
     * The current orientation of the device.
     * <p>
     * See {@link android.hardware.SensorEvent#values SensorEvent.values} for more details.
     *
     * @hide Expected to be used internally for auto-rotate and speaker rotation.
     *
     */
    @UnsupportedAppUsage
    public static final int TYPE_DEVICE_ORIENTATION = 27;

    /**
     * A constant string describing a device orientation sensor type.
     *
     * @hide
     * @see #TYPE_DEVICE_ORIENTATION
     */
    public static final String STRING_TYPE_DEVICE_ORIENTATION = "android.sensor.device_orientation";

    /**
     * A constant describing a pose sensor with 6 degrees of freedom.
     *
     * Similar to {@link #TYPE_ROTATION_VECTOR}, with additional delta
     * translation from an arbitrary reference point.
     *
     * See {@link android.hardware.SensorEvent#values SensorEvent.values} for more details.
     *
     * Can use camera, depth sensor etc to compute output value.
     *
     * This is expected to be a high power sensor and expected only to be
     * used when the screen is on.
     *
     * Expected to be more accurate than the rotation vector alone.
     *
     */
    public static final int TYPE_POSE_6DOF = 28;

    /**
     * A constant string describing a pose sensor with 6 degrees of freedom.
     *
     * @see #TYPE_POSE_6DOF
     */
    public static final String STRING_TYPE_POSE_6DOF = "android.sensor.pose_6dof";

    /**
     * A constant describing a stationary detect sensor.
     *
     * See {@link android.hardware.SensorEvent#values SensorEvent.values} for more details.
     *
     */
    public static final int TYPE_STATIONARY_DETECT = 29;

    /**
     * A constant string describing a stationary detection sensor.
     *
     * @see #TYPE_STATIONARY_DETECT
     */
    public static final String STRING_TYPE_STATIONARY_DETECT = "android.sensor.stationary_detect";

    /**
     * A constant describing a motion detect sensor.
     *
     * See {@link android.hardware.SensorEvent#values SensorEvent.values} for more details.
     *
     */
    public static final int TYPE_MOTION_DETECT = 30;

    /**
     * A constant string describing a motion detection sensor.
     *
     * @see #TYPE_MOTION_DETECT
     */
    public static final String STRING_TYPE_MOTION_DETECT = "android.sensor.motion_detect";

    /**
     * A constant describing a motion detect sensor.
     *
     * See {@link android.hardware.SensorEvent#values SensorEvent.values} for more details.
     *
     */
    public static final int TYPE_HEART_BEAT = 31;

    /**
     * A constant string describing a heart beat sensor.
     *
     * @see #TYPE_HEART_BEAT
     */

    public static final String STRING_TYPE_HEART_BEAT = "android.sensor.heart_beat";
    /**
     * A constant describing a dynamic sensor meta event sensor.
     *
     * A sensor event of this type is received when a dynamic sensor is added to or removed from
     * the system. This sensor type should always use special trigger report mode ({@code
     * SensorManager.REPORTING_MODE_SPECIAL_TRIGGER}).
     *
     * @hide This sensor is expected to be used only by system services.
     */
    @SystemApi
    public static final int TYPE_DYNAMIC_SENSOR_META = 32;

    /**
     * A constant string describing a dynamic sensor meta event sensor.
     *
     * @see #TYPE_DYNAMIC_SENSOR_META
     *
     * @hide This sensor is expected to only be used by the system service
     */
    @SystemApi
    public static final String STRING_TYPE_DYNAMIC_SENSOR_META =
            "android.sensor.dynamic_sensor_meta";

    /* TYPE_ADDITIONAL_INFO - defined as type 33 in the HAL is not exposed to
     * applications. There are parts of the framework that require the sensors
     * to be in the same order as the HAL. Skipping this sensor
     */

    /**
     * A constant describing a low latency off-body detect sensor.
     *
     * See {@link android.hardware.SensorEvent#values SensorEvent.values} for more details.
     *
     */
    public static final int TYPE_LOW_LATENCY_OFFBODY_DETECT = 34;


    /**
     * A constant string describing a low-latency offbody detector sensor.
     *
     * @see #TYPE_LOW_LATENCY_OFFBODY_DETECT
     */
    public static final String STRING_TYPE_LOW_LATENCY_OFFBODY_DETECT =
            "android.sensor.low_latency_offbody_detect";

    /**
     * A constant describing an uncalibrated accelerometer sensor.
     *
     * See {@link android.hardware.SensorEvent#values SensorEvent.values} for more details.
     *
     */
    public static final int TYPE_ACCELEROMETER_UNCALIBRATED = 35;

    /**
     * A constant string describing an uncalibrated accelerometer sensor.
     *
     * @see #TYPE_ACCELEROMETER_UNCALIBRATED
     *
     */
    public static final String STRING_TYPE_ACCELEROMETER_UNCALIBRATED =
            "android.sensor.accelerometer_uncalibrated";

    /**
     * A constant describing all sensor types.
     */

    public static final int TYPE_ALL = -1;

    /**
     * The lowest sensor type vendor defined sensors can use.
     *
     * All vendor sensor types are greater than or equal to this constant.
     *
     */
    public static final int TYPE_DEVICE_PRIVATE_BASE = 0x10000;

    // If this flag is set, the sensor defined as a wake up sensor. This field and REPORTING_MODE_*
    // constants are defined as flags in sensors.h. Modify at both places if needed.
    private static final int SENSOR_FLAG_WAKE_UP_SENSOR = 1;

    /**
     * Events are reported at a constant rate which is set by the rate parameter of
     * {@link SensorManager#registerListener(SensorEventListener, Sensor, int)}. Note: If other
     * applications are requesting a higher rate, the sensor data might be delivered at faster rates
     * than requested.
     */
    public static final int REPORTING_MODE_CONTINUOUS = 0;

    /**
     * Events are reported only when the value changes. Event delivery rate can be limited by
     * setting appropriate value for rate parameter of
     * {@link SensorManager#registerListener(SensorEventListener, Sensor, int)} Note: If other
     * applications are requesting a higher rate, the sensor data might be delivered at faster rates
     * than requested.
     */
    public static final int REPORTING_MODE_ON_CHANGE = 1;

    /**
     * Events are reported in one-shot mode. Upon detection of an event, the sensor deactivates
     * itself and then sends a single event. Sensors of this reporting mode must be registered to
     * using {@link SensorManager#requestTriggerSensor(TriggerEventListener, Sensor)}.
     */
    public static final int REPORTING_MODE_ONE_SHOT = 2;

    /**
     * Events are reported as described in the description of the sensor. The rate passed to
     * registerListener might not have an impact on the rate of event delivery. See the sensor
     * definition for more information on when and how frequently the events are reported. For
     * example, step detectors report events when a step is detected.
     *
     * @see SensorManager#registerListener(SensorEventListener, Sensor, int, int)
     */
    public static final int REPORTING_MODE_SPECIAL_TRIGGER = 3;

    // Mask for the LSB 2nd, 3rd and fourth bits.
    private static final int REPORTING_MODE_MASK = 0xE;
    private static final int REPORTING_MODE_SHIFT = 1;

    // MASK for LSB fifth bit. Used to know whether the sensor supports data injection or not.
    private static final int DATA_INJECTION_MASK = 0x10;
    private static final int DATA_INJECTION_SHIFT = 4;

    // MASK for dynamic sensor (sensor that added during runtime), bit 5.
    private static final int DYNAMIC_SENSOR_MASK = 0x20;
    private static final int DYNAMIC_SENSOR_SHIFT = 5;

    // MASK for indication bit of sensor additional information support, bit 6.
    private static final int ADDITIONAL_INFO_MASK = 0x40;
    private static final int ADDITIONAL_INFO_SHIFT = 6;

    // Mask for direct mode highest rate level, bit 7, 8, 9.
    private static final int DIRECT_REPORT_MASK = 0x380;
    private static final int DIRECT_REPORT_SHIFT = 7;

    // Mask for supported direct channel, bit 10, 11
    private static final int DIRECT_CHANNEL_MASK = 0xC00;
    private static final int DIRECT_CHANNEL_SHIFT = 10;

    // TODO(): The following arrays are fragile and error-prone. This needs to be refactored.

    // Note: This needs to be updated, whenever a new sensor is added.
    // Holds the reporting mode and maximum length of the values array
    // associated with
    // {@link SensorEvent} or {@link TriggerEvent} for the Sensor
    private static final int[] sSensorReportingModes = {
            0, // padding because sensor types start at 1
            3, // SENSOR_TYPE_ACCELEROMETER
            3, // SENSOR_TYPE_GEOMAGNETIC_FIELD
            3, // SENSOR_TYPE_ORIENTATION
            3, // SENSOR_TYPE_GYROSCOPE
            1, // SENSOR_TYPE_LIGHT
            1, // SENSOR_TYPE_PRESSURE
            1, // SENSOR_TYPE_TEMPERATURE
            1, // SENSOR_TYPE_PROXIMITY
            3, // SENSOR_TYPE_GRAVITY
            3, // SENSOR_TYPE_LINEAR_ACCELERATION
            5, // SENSOR_TYPE_ROTATION_VECTOR
            1, // SENSOR_TYPE_RELATIVE_HUMIDITY
            1, // SENSOR_TYPE_AMBIENT_TEMPERATURE
            6, // SENSOR_TYPE_MAGNETIC_FIELD_UNCALIBRATED
            4, // SENSOR_TYPE_GAME_ROTATION_VECTOR
            6, // SENSOR_TYPE_GYROSCOPE_UNCALIBRATED
            1, // SENSOR_TYPE_SIGNIFICANT_MOTION
            1, // SENSOR_TYPE_STEP_DETECTOR
            1, // SENSOR_TYPE_STEP_COUNTER
            5, // SENSOR_TYPE_GEOMAGNETIC_ROTATION_VECTOR
            1, // SENSOR_TYPE_HEART_RATE_MONITOR
            1, // SENSOR_TYPE_WAKE_UP_TILT_DETECTOR
            1, // SENSOR_TYPE_WAKE_GESTURE
            1, // SENSOR_TYPE_GLANCE_GESTURE
            1, // SENSOR_TYPE_PICK_UP_GESTURE
            1, // SENSOR_TYPE_WRIST_TILT_GESTURE
            1, // SENSOR_TYPE_DEVICE_ORIENTATION
            16, // SENSOR_TYPE_POSE_6DOF
            1, // SENSOR_TYPE_STATIONARY_DETECT
            1, // SENSOR_TYPE_MOTION_DETECT
            1, // SENSOR_TYPE_HEART_BEAT
            2, // SENSOR_TYPE_DYNAMIC_SENSOR_META
            16, // skip over additional sensor info type
            1, // SENSOR_TYPE_LOW_LATENCY_OFFBODY_DETECT
            6, // SENSOR_TYPE_ACCELEROMETER_UNCALIBRATED
    };

    /**
     * Each sensor has exactly one reporting mode associated with it. This method returns the
     * reporting mode constant for this sensor type.
     *
     * @return Reporting mode for the input sensor, one of REPORTING_MODE_* constants.
     * @see #REPORTING_MODE_CONTINUOUS
     * @see #REPORTING_MODE_ON_CHANGE
     * @see #REPORTING_MODE_ONE_SHOT
     * @see #REPORTING_MODE_SPECIAL_TRIGGER
     */
    public int getReportingMode() {
        return ((mFlags & REPORTING_MODE_MASK) >> REPORTING_MODE_SHIFT);
    }

    /**
     * Get the highest supported direct report mode rate level of the sensor.
     *
     * @return Highest direct report rate level of this sensor. If the sensor does not support
     * direct report mode, this returns {@link SensorDirectChannel#RATE_STOP}.
     * @see SensorDirectChannel#RATE_STOP
     * @see SensorDirectChannel#RATE_NORMAL
     * @see SensorDirectChannel#RATE_FAST
     * @see SensorDirectChannel#RATE_VERY_FAST
     */
    @SensorDirectChannel.RateLevel
    public int getHighestDirectReportRateLevel() {
        int rateLevel = ((mFlags & DIRECT_REPORT_MASK) >> DIRECT_REPORT_SHIFT);
        return rateLevel <= SensorDirectChannel.RATE_VERY_FAST
                ? rateLevel : SensorDirectChannel.RATE_VERY_FAST;
    }

    /**
     * Test if a sensor supports a specified direct channel type.
     *
     * @param sharedMemType type of shared memory used by direct channel.
     * @return <code>true</code> if the specified shared memory type is supported.
     * @see SensorDirectChannel#TYPE_MEMORY_FILE
     * @see SensorDirectChannel#TYPE_HARDWARE_BUFFER
     */
    public boolean isDirectChannelTypeSupported(@SensorDirectChannel.MemoryType int sharedMemType) {
        switch (sharedMemType) {
            case SensorDirectChannel.TYPE_MEMORY_FILE:
                return (mFlags & (1 << DIRECT_CHANNEL_SHIFT)) > 0;
            case SensorDirectChannel.TYPE_HARDWARE_BUFFER:
                return (mFlags & (1 << DIRECT_CHANNEL_SHIFT + 1)) > 0;
            default:
                return false;
        }
    }

    static int getMaxLengthValuesArray(Sensor sensor, int sdkLevel) {
        // RotationVector length has changed to 3 to 5 for API level 18
        // Set it to 3 for backward compatibility.
        if (sensor.mType == Sensor.TYPE_ROTATION_VECTOR
                && sdkLevel <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return 3;
        }
        int offset = sensor.mType;
        if (offset >= sSensorReportingModes.length) {
            // we don't know about this sensor, so this is probably a vendor-defined sensor, in that
            // case, we don't know how many value it has so we return the maximum and assume the app
            // will know.
            // FIXME: sensor HAL should advertise how much data is returned per sensor
            return 16;
        }
        return sSensorReportingModes[offset];
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
    private int     mFifoReservedEventCount;
    private int     mFifoMaxEventCount;
    private String  mStringType;
    private String  mRequiredPermission;
    private int     mMaxDelay;
    @UnsupportedAppUsage
    private int     mFlags;
    private int     mId;

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

    /**
     * @return Number of events reserved for this sensor in the batch mode FIFO. This gives a
     * guarantee on the minimum number of events that can be batched.
     */
    public int getFifoReservedEventCount() {
        return mFifoReservedEventCount;
    }

    /**
     * @return Maximum number of events of this sensor that could be batched. If this value is zero
     * it indicates that batch mode is not supported for this sensor. If other applications
     * registered to batched sensors, the actual number of events that can be batched might be
     * smaller because the hardware FiFo will be partially used to batch the other sensors.
     */
    public int getFifoMaxEventCount() {
        return mFifoMaxEventCount;
    }

    /**
     * @return The type of this sensor as a string.
     */
    public String getStringType() {
        return mStringType;
    }

    /**
     * Do not use.
     *
     * This method throws an UnsupportedOperationException.
     *
     * Use getId() if you want a unique ID.
     *
     * @see getId
     *
     * @hide
     */
    @SystemApi
    public java.util.UUID getUuid() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return The sensor id that will be unique for the same app unless the device is factory
     * reset. Return value of 0 means this sensor does not support this function; return value of -1
     * means this sensor can be uniquely identified in system by combination of its type and name.
     */
    public int getId() {
        return mId;
    }

    /**
     * @hide
     * @return The permission required to access this sensor. If empty, no permission is required.
     */
    public String getRequiredPermission() {
        return mRequiredPermission;
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getHandle() {
        return mHandle;
    }

    /**
     * This value is defined only for continuous and on-change sensors. It is the delay between two
     * sensor events corresponding to the lowest frequency that this sensor supports. When lower
     * frequencies are requested through registerListener() the events will be generated at this
     * frequency instead. It can be used to estimate when the batch FIFO may be full. Older devices
     * may set this value to zero. Ignore this value in case it is negative or zero.
     *
     * @return The max delay for this sensor in microseconds.
     */
    public int getMaxDelay() {
        return mMaxDelay;
    }

    /**
     * Returns true if the sensor is a wake-up sensor.
     * <p>
     * <b>Application Processor Power modes</b> <p>
     * Application Processor(AP), is the processor on which applications run.  When no wake lock is
     * held and the user is not interacting with the device, this processor can enter a “Suspend”
     * mode, reducing the power consumption by 10 times or more.
     * </p>
     * <p>
     * <b>Non-wake-up sensors</b> <p>
     * Non-wake-up sensors are sensors that do not wake the AP out of suspend to report data. While
     * the AP is in suspend mode, the sensors continue to function and generate events, which are
     * put in a hardware FIFO. The events in the FIFO are delivered to the application when the AP
     * wakes up. If the FIFO was too small to store all events generated while the AP was in
     * suspend mode, the older events are lost: the oldest data is dropped to accommodate the newer
     * data. In the extreme case where the FIFO is non-existent {@code maxFifoEventCount() == 0},
     * all events generated while the AP was in suspend mode are lost. Applications using
     * non-wake-up sensors should usually:
     * <ul>
     * <li>Either unregister from the sensors when they do not need them, usually in the activity’s
     * {@code onPause} method. This is the most common case.
     * <li>Or realize that the sensors are consuming some power while the AP is in suspend mode and
     * that even then, some events might be lost.
     * </ul>
     * </p>
     * <p>
     * <b>Wake-up sensors</b> <p>
     * In opposition to non-wake-up sensors, wake-up sensors ensure that their data is delivered
     * independently of the state of the AP. While the AP is awake, the wake-up sensors behave
     * like non-wake-up-sensors. When the AP is asleep, wake-up sensors wake up the AP to deliver
     * events. That is, the AP will wake up and the sensor will deliver the events before the
     * maximum reporting latency is elapsed or the hardware FIFO gets full. See {@link
     * SensorManager#registerListener(SensorEventListener, Sensor, int, int)} for more details.
     * </p>
     *
     * @return <code>true</code> if this is a wake-up sensor, <code>false</code> otherwise.
     */
    public boolean isWakeUpSensor() {
        return (mFlags & SENSOR_FLAG_WAKE_UP_SENSOR) != 0;
    }

    /**
     * Returns true if the sensor is a dynamic sensor.
     *
     * @return <code>true</code> if the sensor is a dynamic sensor (sensor added at runtime).
     * @see SensorManager.DynamicSensorCallback
     */
    public boolean isDynamicSensor() {
        return (mFlags & DYNAMIC_SENSOR_MASK) != 0;
    }

    /**
     * Returns true if the sensor supports sensor additional information API
     *
     * @return <code>true</code> if the sensor supports sensor additional information API
     * @see SensorAdditionalInfo
     */
    public boolean isAdditionalInfoSupported() {
        return (mFlags & ADDITIONAL_INFO_MASK) != 0;
    }

    /**
     * Returns true if the sensor supports data injection when the
     * HAL is set to data injection mode.
     *
     * @return <code>true</code> if the sensor supports data
     *         injection when the HAL is set in injection mode,
     *         false otherwise.
     * @hide
     */
    @SystemApi
    public boolean isDataInjectionSupported() {
        return (((mFlags & DATA_INJECTION_MASK) >> DATA_INJECTION_SHIFT)) != 0;
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

    /**
     * Sets the Type associated with the sensor.
     * NOTE: to be used only by native bindings in SensorManager.
     *
     * This allows interned static strings to be used across all representations of the Sensor. If
     * a sensor type is not referenced here, it will still be interned by the native SensorManager.
     *
     * @return {@code true} if the StringType was successfully set, {@code false} otherwise.
     */
    private boolean setType(int value) {
        mType = value;
        switch (mType) {
            case TYPE_ACCELEROMETER:
                mStringType = STRING_TYPE_ACCELEROMETER;
                return true;
            case TYPE_AMBIENT_TEMPERATURE:
                mStringType = STRING_TYPE_AMBIENT_TEMPERATURE;
                return true;
            case TYPE_GAME_ROTATION_VECTOR:
                mStringType = STRING_TYPE_GAME_ROTATION_VECTOR;
                return true;
            case TYPE_GEOMAGNETIC_ROTATION_VECTOR:
                mStringType = STRING_TYPE_GEOMAGNETIC_ROTATION_VECTOR;
                return true;
            case TYPE_GLANCE_GESTURE:
                mStringType = STRING_TYPE_GLANCE_GESTURE;
                return true;
            case TYPE_GRAVITY:
                mStringType = STRING_TYPE_GRAVITY;
                return true;
            case TYPE_GYROSCOPE:
                mStringType = STRING_TYPE_GYROSCOPE;
                return true;
            case TYPE_GYROSCOPE_UNCALIBRATED:
                mStringType = STRING_TYPE_GYROSCOPE_UNCALIBRATED;
                return true;
            case TYPE_HEART_RATE:
                mStringType = STRING_TYPE_HEART_RATE;
                return true;
            case TYPE_LIGHT:
                mStringType = STRING_TYPE_LIGHT;
                return true;
            case TYPE_LINEAR_ACCELERATION:
                mStringType = STRING_TYPE_LINEAR_ACCELERATION;
                return true;
            case TYPE_MAGNETIC_FIELD:
                mStringType = STRING_TYPE_MAGNETIC_FIELD;
                return true;
            case TYPE_MAGNETIC_FIELD_UNCALIBRATED:
                mStringType = STRING_TYPE_MAGNETIC_FIELD_UNCALIBRATED;
                return true;
            case TYPE_PICK_UP_GESTURE:
                mStringType = STRING_TYPE_PICK_UP_GESTURE;
                return true;
            case TYPE_PRESSURE:
                mStringType = STRING_TYPE_PRESSURE;
                return true;
            case TYPE_PROXIMITY:
                mStringType = STRING_TYPE_PROXIMITY;
                return true;
            case TYPE_RELATIVE_HUMIDITY:
                mStringType = STRING_TYPE_RELATIVE_HUMIDITY;
                return true;
            case TYPE_ROTATION_VECTOR:
                mStringType = STRING_TYPE_ROTATION_VECTOR;
                return true;
            case TYPE_SIGNIFICANT_MOTION:
                mStringType = STRING_TYPE_SIGNIFICANT_MOTION;
                return true;
            case TYPE_STEP_COUNTER:
                mStringType = STRING_TYPE_STEP_COUNTER;
                return true;
            case TYPE_STEP_DETECTOR:
                mStringType = STRING_TYPE_STEP_DETECTOR;
                return true;
            case TYPE_TILT_DETECTOR:
                mStringType = SENSOR_STRING_TYPE_TILT_DETECTOR;
                return true;
            case TYPE_WAKE_GESTURE:
                mStringType = STRING_TYPE_WAKE_GESTURE;
                return true;
            case TYPE_ORIENTATION:
                mStringType = STRING_TYPE_ORIENTATION;
                return true;
            case TYPE_TEMPERATURE:
                mStringType = STRING_TYPE_TEMPERATURE;
                return true;
            case TYPE_DEVICE_ORIENTATION:
                mStringType = STRING_TYPE_DEVICE_ORIENTATION;
                return true;
            case TYPE_DYNAMIC_SENSOR_META:
                mStringType = STRING_TYPE_DYNAMIC_SENSOR_META;
                return true;
            case TYPE_LOW_LATENCY_OFFBODY_DETECT:
                mStringType = STRING_TYPE_LOW_LATENCY_OFFBODY_DETECT;
                return true;
            case TYPE_ACCELEROMETER_UNCALIBRATED:
                mStringType = STRING_TYPE_ACCELEROMETER_UNCALIBRATED;
                return true;
            default:
                return false;
        }
    }

    /**
     * Sets the ID associated with the sensor.
     *
     * The method name is misleading; while this ID is based on the UUID,
     * we do not pass in the actual UUID.
     *
     * NOTE: to be used only by native bindings in SensorManager.
     *
     * @see #getId
     */
    private void setUuid(long msb, long lsb) {
        // TODO(b/29547335): Rename this method to setId.
        mId = (int) msb;
    }
}
