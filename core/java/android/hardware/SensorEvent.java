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

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.compat.annotation.UnsupportedAppUsage;

/**
 * This class represents a {@link android.hardware.Sensor Sensor} event and
 * holds information such as the sensor's type, the time-stamp, accuracy and of
 * course the sensor's {@link SensorEvent#values data}.
 *
 * <p>
 * <u>Definition of the coordinate system used by the SensorEvent API.</u>
 * </p>
 *
 * <p>
 * The coordinate-system is defined relative to the screen of the phone in its
 * default orientation. The axes are not swapped when the device's screen
 * orientation changes.
 * </p>
 *
 * <p>
 * The X axis is horizontal and points to the right, the Y axis is vertical and
 * points up and the Z axis points towards the outside of the front face of the
 * screen. In this system, coordinates behind the screen have negative Z values.
 * </p>
 *
 * <p>
 * <center><img src="../../../images/axis_device.png"
 * alt="Sensors coordinate-system diagram." border="0" /></center>
 * </p>
 *
 * <p>
 * <b>Note:</b> This coordinate system is different from the one used in the
 * Android 2D APIs where the origin is in the top-left corner.
 * </p>
 *
 * @see SensorManager
 * @see SensorEvent
 * @see Sensor
 *
 */

public class SensorEvent {
    /**
     * <p>
     * The length and contents of the {@link #values values} array depends on
     * which {@link android.hardware.Sensor sensor} type is being monitored (see
     * also {@link SensorEvent} for a definition of the coordinate system used).
     * </p>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_ACCELEROMETER
     * Sensor.TYPE_ACCELEROMETER}:</h4> All values are in SI units (m/s^2)
     *
     * <ul>
     * <li> values[0]: Acceleration minus Gx on the x-axis </li>
     * <li> values[1]: Acceleration minus Gy on the y-axis </li>
     * <li> values[2]: Acceleration minus Gz on the z-axis </li>
     * </ul>
     *
     * <p>
     * A sensor of this type measures the acceleration applied to the device
     * (<b>Ad</b>). Conceptually, it does so by measuring forces applied to the
     * sensor itself (<b>Fs</b>) using the relation:
     * </p>
     *
     * <b><center>Ad = - &#8721;Fs / mass</center></b>
     *
     * <p>
     * In particular, the force of gravity is always influencing the measured
     * acceleration:
     * </p>
     *
     * <b><center>Ad = -g - &#8721;F / mass</center></b>
     *
     * <p>
     * For this reason, when the device is sitting on a table (and obviously not
     * accelerating), the accelerometer reads a magnitude of <b>g</b> = 9.81
     * m/s^2
     * </p>
     *
     * <p>
     * Similarly, when the device is in free-fall and therefore dangerously
     * accelerating towards to ground at 9.81 m/s^2, its accelerometer reads a
     * magnitude of 0 m/s^2.
     * </p>
     *
     * <p>
     * It should be apparent that in order to measure the real acceleration of
     * the device, the contribution of the force of gravity must be eliminated.
     * This can be achieved by applying a <i>high-pass</i> filter. Conversely, a
     * <i>low-pass</i> filter can be used to isolate the force of gravity.
     * </p>
     *
     * <pre class="prettyprint">
     *
     *     public void onSensorChanged(SensorEvent event)
     *     {
     *          // alpha is calculated as t / (t + dT)
     *          // with t, the low-pass filter's time-constant
     *          // and dT, the event delivery rate
     *
     *          final float alpha = 0.8;
     *
     *          gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0];
     *          gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1];
     *          gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2];
     *
     *          linear_acceleration[0] = event.values[0] - gravity[0];
     *          linear_acceleration[1] = event.values[1] - gravity[1];
     *          linear_acceleration[2] = event.values[2] - gravity[2];
     *     }
     * </pre>
     *
     * <p>
     * <u>Examples</u>:
     * <ul>
     * <li>When the device lies flat on a table and is pushed on its left side
     * toward the right, the x acceleration value is positive.</li>
     *
     * <li>When the device lies flat on a table, the acceleration value is
     * +9.81, which correspond to the acceleration of the device (0 m/s^2) minus
     * the force of gravity (-9.81 m/s^2).</li>
     *
     * <li>When the device lies flat on a table and is pushed toward the sky
     * with an acceleration of A m/s^2, the acceleration value is equal to
     * A+9.81 which correspond to the acceleration of the device (+A m/s^2)
     * minus the force of gravity (-9.81 m/s^2).</li>
     * </ul>
     *
     *
     * <h4>{@link android.hardware.Sensor#TYPE_MAGNETIC_FIELD
     * Sensor.TYPE_MAGNETIC_FIELD}:</h4>
     * All values are in micro-Tesla (uT) and measure the ambient magnetic field
     * in the X, Y and Z axis.
     *
     *
     * <h4>{@link android.hardware.Sensor#TYPE_GYROSCOPE Sensor.TYPE_GYROSCOPE}:
     * </h4> All values are in radians/second and measure the rate of rotation
     * around the device's local X, Y and Z axis. The coordinate system is the
     * same as is used for the acceleration sensor. Rotation is positive in the
     * counter-clockwise direction. That is, an observer looking from some
     * positive location on the x, y or z axis at a device positioned on the
     * origin would report positive rotation if the device appeared to be
     * rotating counter clockwise. Note that this is the standard mathematical
     * definition of positive rotation and does not agree with the definition of
     * roll given earlier.
     * <ul>
     * <li> values[0]: Angular speed around the x-axis </li>
     * <li> values[1]: Angular speed around the y-axis </li>
     * <li> values[2]: Angular speed around the z-axis </li>
     * </ul>
     * <p>
     * Typically the output of the gyroscope is integrated over time to
     * calculate a rotation describing the change of angles over the time step,
     * for example:
     * </p>
     *
     * <pre class="prettyprint">
     *     private static final float NS2S = 1.0f / 1000000000.0f;
     *     private final float[] deltaRotationVector = new float[4]();
     *     private float timestamp;
     *
     *     public void onSensorChanged(SensorEvent event) {
     *          // This time step's delta rotation to be multiplied by the current rotation
     *          // after computing it from the gyro sample data.
     *          if (timestamp != 0) {
     *              final float dT = (event.timestamp - timestamp) * NS2S;
     *              // Axis of the rotation sample, not normalized yet.
     *              float axisX = event.values[0];
     *              float axisY = event.values[1];
     *              float axisZ = event.values[2];
     *
     *              // Calculate the angular speed of the sample
     *              float omegaMagnitude = sqrt(axisX*axisX + axisY*axisY + axisZ*axisZ);
     *
     *              // Normalize the rotation vector if it's big enough to get the axis
     *              if (omegaMagnitude > EPSILON) {
     *                  axisX /= omegaMagnitude;
     *                  axisY /= omegaMagnitude;
     *                  axisZ /= omegaMagnitude;
     *              }
     *
     *              // Integrate around this axis with the angular speed by the time step
     *              // in order to get a delta rotation from this sample over the time step
     *              // We will convert this axis-angle representation of the delta rotation
     *              // into a quaternion before turning it into the rotation matrix.
     *              float thetaOverTwo = omegaMagnitude * dT / 2.0f;
     *              float sinThetaOverTwo = sin(thetaOverTwo);
     *              float cosThetaOverTwo = cos(thetaOverTwo);
     *              deltaRotationVector[0] = sinThetaOverTwo * axisX;
     *              deltaRotationVector[1] = sinThetaOverTwo * axisY;
     *              deltaRotationVector[2] = sinThetaOverTwo * axisZ;
     *              deltaRotationVector[3] = cosThetaOverTwo;
     *          }
     *          timestamp = event.timestamp;
     *          float[] deltaRotationMatrix = new float[9];
     *          SensorManager.getRotationMatrixFromVector(deltaRotationMatrix, deltaRotationVector);
     *          // User code should concatenate the delta rotation we computed with the current
     *          // rotation in order to get the updated rotation.
     *          // rotationCurrent = rotationCurrent * deltaRotationMatrix;
     *     }
     * </pre>
     * <p>
     * In practice, the gyroscope noise and offset will introduce some errors
     * which need to be compensated for. This is usually done using the
     * information from other sensors, but is beyond the scope of this document.
     * </p>
     * <h4>{@link android.hardware.Sensor#TYPE_LIGHT Sensor.TYPE_LIGHT}:</h4>
     * <ul>
     * <li>values[0]: Ambient light level in SI lux units </li>
     * </ul>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_PRESSURE Sensor.TYPE_PRESSURE}:</h4>
     * <ul>
     * <li>values[0]: Atmospheric pressure in hPa (millibar) </li>
     * </ul>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_PROXIMITY Sensor.TYPE_PROXIMITY}:
     * </h4>
     *
     * <ul>
     * <li>values[0]: Proximity sensor distance measured in centimeters </li>
     * </ul>
     *
     * <p>
     * <b>Note:</b> Some proximity sensors only support a binary <i>near</i> or
     * <i>far</i> measurement. In this case, the sensor should report its
     * {@link android.hardware.Sensor#getMaximumRange() maximum range} value in
     * the <i>far</i> state and a lesser value in the <i>near</i> state.
     * </p>
     *
     *  <h4>{@link android.hardware.Sensor#TYPE_GRAVITY Sensor.TYPE_GRAVITY}:</h4>
     *  <p>A three dimensional vector indicating the direction and magnitude of gravity.  Units
     *  are m/s^2. The coordinate system is the same as is used by the acceleration sensor.</p>
     *  <p><b>Note:</b> When the device is at rest, the output of the gravity sensor should be
     *  identical to that of the accelerometer.</p>
     *
     *  <h4>
     *  {@link android.hardware.Sensor#TYPE_LINEAR_ACCELERATION Sensor.TYPE_LINEAR_ACCELERATION}:
     *  </h4> A three dimensional vector indicating acceleration along each device axis, not
     *  including gravity. All values have units of m/s^2.  The coordinate system is the same as is
     *  used by the acceleration sensor.
     *  <p>The output of the accelerometer, gravity and  linear-acceleration sensors must obey the
     *  following relation:</p>
     *  <p><ul>acceleration = gravity + linear-acceleration</ul></p>
     *
     *  <h4>{@link android.hardware.Sensor#TYPE_ROTATION_VECTOR Sensor.TYPE_ROTATION_VECTOR}:</h4>
     *  <p>The rotation vector represents the orientation of the device as a combination of an
     *  <i>angle</i> and an <i>axis</i>, in which the device has rotated through an angle &#952
     *  around an axis &lt;x, y, z>.</p>
     *  <p>The three elements of the rotation vector are
     *  &lt;x*sin(&#952/2), y*sin(&#952/2), z*sin(&#952/2)>, such that the magnitude of the rotation
     *  vector is equal to sin(&#952/2), and the direction of the rotation vector is equal to the
     *  direction of the axis of rotation.</p>
     *  </p>The three elements of the rotation vector are equal to
     *  the last three components of a <b>unit</b> quaternion
     *  &lt;cos(&#952/2), x*sin(&#952/2), y*sin(&#952/2), z*sin(&#952/2)>.</p>
     *  <p>Elements of the rotation vector are unitless.
     *  The x,y, and z axis are defined in the same way as the acceleration
     *  sensor.</p>
     *  The reference coordinate system is defined as a direct orthonormal basis,
     *  where:
     * </p>
     *
     * <ul>
     * <li>X is defined as the vector product <b>Y.Z</b> (It is tangential to
     * the ground at the device's current location and roughly points East).</li>
     * <li>Y is tangential to the ground at the device's current location and
     * points towards magnetic north.</li>
     * <li>Z points towards the sky and is perpendicular to the ground.</li>
     * </ul>
     *
     * <p>
     * <center><img src="../../../images/axis_globe.png"
     * alt="World coordinate-system diagram." border="0" /></center>
     * </p>
     *
     * <ul>
     * <li> values[0]: x*sin(&#952/2) </li>
     * <li> values[1]: y*sin(&#952/2) </li>
     * <li> values[2]: z*sin(&#952/2) </li>
     * <li> values[3]: cos(&#952/2) </li>
     * <li> values[4]: estimated heading Accuracy (in radians) (-1 if unavailable)</li>
     * </ul>
     * <p> values[3], originally optional, will always be present from SDK Level 18 onwards.
     * values[4] is a new value that has been added in SDK Level 18.
     * </p>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_ORIENTATION
     * Sensor.TYPE_ORIENTATION}:</h4> All values are angles in degrees.
     *
     * <ul>
     * <li> values[0]: Azimuth, angle between the magnetic north direction and the
     * y-axis, around the z-axis (0 to 359). 0=North, 90=East, 180=South,
     * 270=West
     * </p>
     *
     * <p>
     * values[1]: Pitch, rotation around x-axis (-180 to 180), with positive
     * values when the z-axis moves <b>toward</b> the y-axis.
     * </p>
     *
     * <p>
     * values[2]: Roll, rotation around the y-axis (-90 to 90)
     * increasing as the device moves clockwise.
     * </p>
     * </ul>
     *
     * <p>
     * <b>Note:</b> This definition is different from <b>yaw, pitch and roll</b>
     * used in aviation where the X axis is along the long side of the plane
     * (tail to nose).
     * </p>
     *
     * <p>
     * <b>Note:</b> This sensor type exists for legacy reasons, please use
     * {@link android.hardware.Sensor#TYPE_ROTATION_VECTOR
     * rotation vector sensor type} and
     * {@link android.hardware.SensorManager#getRotationMatrix
     * getRotationMatrix()} in conjunction with
     * {@link android.hardware.SensorManager#remapCoordinateSystem
     * remapCoordinateSystem()} and
     * {@link android.hardware.SensorManager#getOrientation getOrientation()} to
     * compute these values instead.
     * </p>
     *
     * <p>
     * <b>Important note:</b> For historical reasons the roll angle is positive
     * in the clockwise direction (mathematically speaking, it should be
     * positive in the counter-clockwise direction).
     * </p>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_RELATIVE_HUMIDITY
     * Sensor.TYPE_RELATIVE_HUMIDITY}:</h4>
     * <ul>
     * <li> values[0]: Relative ambient air humidity in percent </li>
     * </ul>
     * <p>
     * When relative ambient air humidity and ambient temperature are
     * measured, the dew point and absolute humidity can be calculated.
     * </p>
     * <u>Dew Point</u>
     * <p>
     * The dew point is the temperature to which a given parcel of air must be
     * cooled, at constant barometric pressure, for water vapor to condense
     * into water.
     * </p>
     * <center><pre>
     *                    ln(RH/100%) + m&#183;t/(T<sub>n</sub>+t)
     * t<sub>d</sub>(t,RH) = T<sub>n</sub> &#183; ------------------------------
     *                 m - [ln(RH/100%) + m&#183;t/(T<sub>n</sub>+t)]
     * </pre></center>
     * <dl>
     * <dt>t<sub>d</sub></dt> <dd>dew point temperature in &deg;C</dd>
     * <dt>t</dt>             <dd>actual temperature in &deg;C</dd>
     * <dt>RH</dt>            <dd>actual relative humidity in %</dd>
     * <dt>m</dt>             <dd>17.62</dd>
     * <dt>T<sub>n</sub></dt> <dd>243.12 &deg;C</dd>
     * </dl>
     * <p>for example:</p>
     * <pre class="prettyprint">
     * h = Math.log(rh / 100.0) + (17.62 * t) / (243.12 + t);
     * td = 243.12 * h / (17.62 - h);
     * </pre>
     * <u>Absolute Humidity</u>
     * <p>
     * The absolute humidity is the mass of water vapor in a particular volume
     * of dry air. The unit is g/m<sup>3</sup>.
     * </p>
     * <center><pre>
     *                    RH/100%&#183;A&#183;exp(m&#183;t/(T<sub>n</sub>+t))
     * d<sub>v</sub>(t,RH) = 216.7 &#183; -------------------------
     *                           273.15 + t
     * </pre></center>
     * <dl>
     * <dt>d<sub>v</sub></dt> <dd>absolute humidity in g/m<sup>3</sup></dd>
     * <dt>t</dt>             <dd>actual temperature in &deg;C</dd>
     * <dt>RH</dt>            <dd>actual relative humidity in %</dd>
     * <dt>m</dt>             <dd>17.62</dd>
     * <dt>T<sub>n</sub></dt> <dd>243.12 &deg;C</dd>
     * <dt>A</dt>             <dd>6.112 hPa</dd>
     * </dl>
     * <p>for example:</p>
     * <pre class="prettyprint">
     * dv = 216.7 *
     * (rh / 100.0 * 6.112 * Math.exp(17.62 * t / (243.12 + t)) / (273.15 + t));
     * </pre>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_AMBIENT_TEMPERATURE Sensor.TYPE_AMBIENT_TEMPERATURE}:
     * </h4>
     *
     * <ul>
     * <li> values[0]: Ambient (room) temperature in degrees Celsius</li>
     * </ul>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_MAGNETIC_FIELD_UNCALIBRATED
     * Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED}:</h4>
     * Similar to {@link android.hardware.Sensor#TYPE_MAGNETIC_FIELD},
     * but the hard iron calibration is reported separately instead of being included
     * in the measurement. Factory calibration and temperature compensation will still
     * be applied to the "uncalibrated" measurement. Assumptions that the magnetic field
     * is due to the Earth's poles is avoided.
     * <p>
     * The values array is shown below:
     * <ul>
     * <li> values[0] = x_uncalib </li>
     * <li> values[1] = y_uncalib </li>
     * <li> values[2] = z_uncalib </li>
     * <li> values[3] = x_bias </li>
     * <li> values[4] = y_bias </li>
     * <li> values[5] = z_bias </li>
     * </ul>
     * </p>
     * <p>
     * x_uncalib, y_uncalib, z_uncalib are the measured magnetic field in X, Y, Z axes.
     * Soft iron and temperature calibrations are applied. But the hard iron
     * calibration is not applied. The values are in micro-Tesla (uT).
     * </p>
     * <p>
     * x_bias, y_bias, z_bias give the iron bias estimated in X, Y, Z axes.
     * Each field is a component of the estimated hard iron calibration.
     * The values are in micro-Tesla (uT).
     * </p>
     * <p> Hard iron - These distortions arise due to the magnetized iron, steel or permanent
     * magnets on the device.
     * Soft iron - These distortions arise due to the interaction with the earth's magnetic
     * field.
     * </p>
     * <h4> {@link android.hardware.Sensor#TYPE_GAME_ROTATION_VECTOR
     * Sensor.TYPE_GAME_ROTATION_VECTOR}:</h4>
     * Identical to {@link android.hardware.Sensor#TYPE_ROTATION_VECTOR} except that it
     * doesn't use the geomagnetic field. Therefore the Y axis doesn't
     * point north, but instead to some other reference, that reference is
     * allowed to drift by the same order of magnitude as the gyroscope
     * drift around the Z axis.
     * <p>
     * In the ideal case, a phone rotated and returning to the same real-world
     * orientation will report the same game rotation vector
     * (without using the earth's geomagnetic field). However, the orientation
     * may drift somewhat over time. See {@link android.hardware.Sensor#TYPE_ROTATION_VECTOR}
     * for a detailed description of the values. This sensor will not have
     * the estimated heading accuracy value.
     * </p>
     *
     * <h4> {@link android.hardware.Sensor#TYPE_GYROSCOPE_UNCALIBRATED
     * Sensor.TYPE_GYROSCOPE_UNCALIBRATED}:</h4>
     * All values are in radians/second and measure the rate of rotation
     * around the X, Y and Z axis. An estimation of the drift on each axis is
     * reported as well.
     * <p>
     * No gyro-drift compensation is performed. Factory calibration and temperature
     * compensation is still applied to the rate of rotation (angular speeds).
     * </p>
     * <p>
     * The coordinate system is the same as is used for the
     * {@link android.hardware.Sensor#TYPE_ACCELEROMETER}
     * Rotation is positive in the counter-clockwise direction (right-hand rule).
     * That is, an observer looking from some positive location on the x, y or z axis
     * at a device positioned on the origin would report positive rotation if the device
     * appeared to be rotating counter clockwise.
     * The range would at least be 17.45 rad/s (ie: ~1000 deg/s).
     * <ul>
     * <li> values[0] : angular speed (w/o drift compensation) around the X axis in rad/s </li>
     * <li> values[1] : angular speed (w/o drift compensation) around the Y axis in rad/s </li>
     * <li> values[2] : angular speed (w/o drift compensation) around the Z axis in rad/s </li>
     * <li> values[3] : estimated drift around X axis in rad/s </li>
     * <li> values[4] : estimated drift around Y axis in rad/s </li>
     * <li> values[5] : estimated drift around Z axis in rad/s </li>
     * </ul>
     * </p>
     * <p><b>Pro Tip:</b> Always use the length of the values array while performing operations
     * on it. In earlier versions, this used to be always 3 which has changed now. </p>
     *
     *   <h4>{@link android.hardware.Sensor#TYPE_POSE_6DOF
     * Sensor.TYPE_POSE_6DOF}:</h4>
     *
     * A TYPE_POSE_6DOF event consists of a rotation expressed as a quaternion and a translation
     * expressed in SI units. The event also contains a delta rotation and translation that show
     * how the device?s pose has changed since the previous sequence numbered pose.
     * The event uses the cannonical Android Sensor axes.
     *
     *
     * <ul>
     * <li> values[0]: x*sin(&#952/2) </li>
     * <li> values[1]: y*sin(&#952/2) </li>
     * <li> values[2]: z*sin(&#952/2) </li>
     * <li> values[3]: cos(&#952/2)   </li>
     *
     *
     * <li> values[4]: Translation along x axis from an arbitrary origin. </li>
     * <li> values[5]: Translation along y axis from an arbitrary origin. </li>
     * <li> values[6]: Translation along z axis from an arbitrary origin. </li>
     *
     * <li> values[7]:  Delta quaternion rotation x*sin(&#952/2) </li>
     * <li> values[8]:  Delta quaternion rotation y*sin(&#952/2) </li>
     * <li> values[9]:  Delta quaternion rotation z*sin(&#952/2) </li>
     * <li> values[10]: Delta quaternion rotation cos(&#952/2) </li>
     *
     * <li> values[11]: Delta translation along x axis. </li>
     * <li> values[12]: Delta translation along y axis. </li>
     * <li> values[13]: Delta translation along z axis. </li>
     *
     * <li> values[14]: Sequence number </li>
     *
     * </ul>
     *
     *   <h4>{@link android.hardware.Sensor#TYPE_STATIONARY_DETECT
     * Sensor.TYPE_STATIONARY_DETECT}:</h4>
     *
     * A TYPE_STATIONARY_DETECT event is produced if the device has been
     * stationary for at least 5 seconds with a maximal latency of 5
     * additional seconds. ie: it may take up anywhere from 5 to 10 seconds
     * afte the device has been at rest to trigger this event.
     *
     * The only allowed value is 1.0.
     *
     * <ul>
     *  <li> values[0]: 1.0 </li>
     * </ul>
     *
     *   <h4>{@link android.hardware.Sensor#TYPE_MOTION_DETECT
     * Sensor.TYPE_MOTION_DETECT}:</h4>
     *
     * A TYPE_MOTION_DETECT event is produced if the device has been in
     * motion  for at least 5 seconds with a maximal latency of 5
     * additional seconds. ie: it may take up anywhere from 5 to 10 seconds
     * afte the device has been at rest to trigger this event.
     *
     * The only allowed value is 1.0.
     *
     * <ul>
     *  <li> values[0]: 1.0 </li>
     * </ul>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_STEP_COUNTER Sensor.TYPE_STEP_COUNTER}:</h4>
     *
     * <ul>
     * <li>values[0]: Number of steps taken by the user since the last reboot while the sensor is
     * activated</li>
     * </ul>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_STEP_DETECTOR Sensor.TYPE_STEP_DETECTOR}:</h4>
     *
     * <ul>
     * <li>values[0]: Always set to 1.0, representing a single step detected event</li>
     * </ul>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_HEART_BEAT Sensor.TYPE_HEART_BEAT}:</h4>
     *
     * A sensor of this type returns an event everytime a heart beat peak is
     * detected.
     *
     * Peak here ideally corresponds to the positive peak in the QRS complex of
     * an ECG signal.
     *
     * <ul>
     *  <li> values[0]: confidence</li>
     * </ul>
     *
     * <p>
     * A confidence value of 0.0 indicates complete uncertainty - that a peak
     * is as likely to be at the indicated timestamp as anywhere else.
     * A confidence value of 1.0 indicates complete certainly - that a peak is
     * completely unlikely to be anywhere else on the QRS complex.
     * </p>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_LOW_LATENCY_OFFBODY_DETECT
     * Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT}:</h4>
     *
     * <p>
     * A sensor of this type returns an event every time the device transitions
     * from off-body to on-body and from on-body to off-body (e.g. a wearable
     * device being removed from the wrist would trigger an event indicating an
     * off-body transition). The event returned will contain a single value to
     * indicate off-body state:
     * </p>
     *
     * <ul>
     *  <li> values[0]: off-body state</li>
     * </ul>
     *
     * <p>
     *     Valid values for off-body state:
     * <ul>
     *  <li> 1.0 (device is on-body)</li>
     *  <li> 0.0 (device is off-body)</li>
     * </ul>
     * </p>
     *
     * <p>
     * When a sensor of this type is activated, it must deliver the initial
     * on-body or off-body event representing the current device state within
     * 5 seconds of activating the sensor.
     * </p>
     *
     * <p>
     * This sensor must be able to detect and report an on-body to off-body
     * transition within 3 seconds of the device being removed from the body,
     * and must be able to detect and report an off-body to on-body transition
     * within 5 seconds of the device being put back onto the body.
     * </p>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_ACCELEROMETER_UNCALIBRATED
     * Sensor.TYPE_ACCELEROMETER_UNCALIBRATED}:</h4> All values are in SI
     * units (m/s^2)
     *
     * Similar to {@link android.hardware.Sensor#TYPE_ACCELEROMETER},
     * Factory calibration and temperature compensation will still be applied
     * to the "uncalibrated" measurement.
     *
     * <p>
     * The values array is shown below:
     * <ul>
     * <li> values[0] = x_uncalib without bias compensation </li>
     * <li> values[1] = y_uncalib without bias compensation </li>
     * <li> values[2] = z_uncalib without bias compensation </li>
     * <li> values[3] = estimated x_bias </li>
     * <li> values[4] = estimated y_bias </li>
     * <li> values[5] = estimated z_bias </li>
     * </ul>
     * </p>
     * <p>
     * x_uncalib, y_uncalib, z_uncalib are the measured acceleration in X, Y, Z
     * axes similar to the  {@link android.hardware.Sensor#TYPE_ACCELEROMETER},
     * without any bias correction (factory bias compensation and any
     * temperature compensation is allowed).
     * x_bias, y_bias, z_bias are the estimated biases.
     * </p>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_HINGE_ANGLE Sensor.TYPE_HINGE_ANGLE}:</h4>
     *
     * A sensor of this type measures the angle, in degrees, between two integral parts of the
     * device. Movement of a hinge measured by this sensor type is expected to alter the ways in
     * which the user may interact with the device, for example by unfolding or revealing a display.
     *
     * <ul>
     *  <li> values[0]: Measured hinge angle between 0 and 360 degrees inclusive</li>
     * </ul>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_HEAD_TRACKER Sensor.TYPE_HEAD_TRACKER}:</h4>
     *
     * A sensor of this type measures the orientation of a user's head relative to an arbitrary
     * reference frame, as well as the rate of rotation.
     *
     * Events produced by this sensor follow a special head-centric coordinate frame, where:
     * <ul>
     *  <li> The X axis crosses through the user's ears, with the positive X direction extending
     *       out of the user's right ear</li>
     *  <li> The Y axis crosses from the back of the user's head through their nose, with the
     *       positive direction extending out of the nose, and the X/Y plane being nominally
     *       parallel to the ground when the user is upright and looking straight ahead</li>
     *  <li> The Z axis crosses from the neck through the top of the user's head, with the
     *       positive direction extending out from the top of the head</li>
     * </ul>
     *
     * Data is provided in Euler vector representation, which is a vector whose direction indicates
     * the axis of rotation and magnitude indicates the angle to rotate around that axis, in
     * radians.
     *
     * The first three elements provide the transform from the (arbitrary, possibly slowly drifting)
     * reference frame to the head frame. The magnitude of this vector is in range [0, &pi;]
     * radians, while the value of individual axes is in range [-&pi;, &pi;]. The next three
     * elements optionally provide the estimated rotational velocity of the user's head relative to
     * itself, in radians per second. If a given sensor does not support determining velocity, these
     * elements are set to 0.
     *
     * <ul>
     *  <li> values[0] : X component of Euler vector representing rotation</li>
     *  <li> values[1] : Y component of Euler vector representing rotation</li>
     *  <li> values[2] : Z component of Euler vector representing rotation</li>
     *  <li> values[3] : X component of Euler vector representing angular velocity (if
     *  supported, otherwise 0)</li>
     *  <li> values[4] : Y component of Euler vector representing angular velocity (if
     *  supported, otherwise 0)</li>
     *  <li> values[5] : Z component of Euler vector representing angular velocity (if
     *  supported, otherwise 0)</li>
     * </ul>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_ACCELEROMETER_LIMITED_AXES
     * Sensor.TYPE_ACCELEROMETER_LIMITED_AXES}:
     * </h4> Equivalent to TYPE_ACCELEROMETER, but supporting cases where one
     * or two axes are not supported.
     *
     * The last three values represent whether the acceleration value for a
     * given axis is supported. A value of 1.0 indicates that the axis is
     * supported, while a value of 0 means it isn't supported. The supported
     * axes should be determined at build time and these values do not change
     * during runtime.
     *
     * The acceleration values for axes that are not supported are set to 0.
     *
     * Similar to {@link android.hardware.Sensor#TYPE_ACCELEROMETER}.
     *
     * <ul>
     * <li> values[0]: Acceleration minus Gx on the x-axis (if supported)</li>
     * <li> values[1]: Acceleration minus Gy on the y-axis (if supported)</li>
     * <li> values[2]: Acceleration minus Gz on the z-axis (if supported)</li>
     * <li> values[3]: Acceleration supported for x-axis</li>
     * <li> values[4]: Acceleration supported for y-axis</li>
     * <li> values[5]: Acceleration supported for z-axis</li>
     * </ul>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_GYROSCOPE_LIMITED_AXES
     * Sensor.TYPE_GYROSCOPE_LIMITED_AXES}:
     * </h4> Equivalent to TYPE_GYROSCOPE, but supporting cases where one or two
     * axes are not supported.
     *
     * The last three values represent whether the angular speed value for a
     * given axis is supported. A value of 1.0 indicates that the axis is
     * supported, while a value of 0 means it isn't supported. The supported
     * axes should be determined at build time and these values do not change
     * during runtime.
     *
     * The angular speed values for axes that are not supported are set to 0.
     *
     * Similar to {@link android.hardware.Sensor#TYPE_GYROSCOPE}.
     *
     * <ul>
     * <li> values[0]: Angular speed around the x-axis (if supported)</li>
     * <li> values[1]: Angular speed around the y-axis (if supported)</li>
     * <li> values[2]: Angular speed around the z-axis (if supported)</li>
     * <li> values[3]: Angular speed supported for x-axis</li>
     * <li> values[4]: Angular speed supported for y-axis</li>
     * <li> values[5]: Angular speed supported for z-axis</li>
     * </ul>
     * <p>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED
     * Sensor.TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED}:
     * </h4> Equivalent to TYPE_ACCELEROMETER_UNCALIBRATED, but supporting cases
     * where one or two axes are not supported.
     *
     * The last three values represent whether the acceleration value for a
     * given axis is supported. A value of 1.0 indicates that the axis is
     * supported, while a value of 0 means it isn't supported. The supported
     * axes should be determined at build time and these values do not change
     * during runtime.
     *
     * The acceleration values and bias values for axes that are not supported
     * are set to 0.
     *
     * <ul>
     * <li> values[0]: x_uncalib without bias compensation (if supported)</li>
     * <li> values[1]: y_uncalib without bias compensation (if supported)</li>
     * <li> values[2]: z_uncalib without bias compensation (if supported)</li>
     * <li> values[3]: estimated x_bias (if supported)</li>
     * <li> values[4]: estimated y_bias (if supported)</li>
     * <li> values[5]: estimated z_bias (if supported)</li>
     * <li> values[6]: Acceleration supported for x-axis</li>
     * <li> values[7]: Acceleration supported for y-axis</li>
     * <li> values[8]: Acceleration supported for z-axis</li>
     * </ul>
     * </p>
     *
     * <h4> {@link android.hardware.Sensor#TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED
     * Sensor.TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED}:
     * </h4> Equivalent to TYPE_GYROSCOPE_UNCALIBRATED, but supporting cases
     * where one or two axes are not supported.
     *
     * The last three values represent whether the angular speed value for a
     * given axis is supported. A value of 1.0 indicates that the axis is
     * supported, while a value of 0 means it isn't supported. The supported
     * axes should be determined at build time and these values do not change
     * during runtime.
     *
     * The angular speed values and drift values for axes that are not supported
     * are set to 0.
     *
     * <ul>
     * <li> values[0]: Angular speed (w/o drift compensation) around the X axis (if supported)</li>
     * <li> values[1]: Angular speed (w/o drift compensation) around the Y axis (if supported)</li>
     * <li> values[2]: Angular speed (w/o drift compensation) around the Z axis (if supported)</li>
     * <li> values[3]: estimated drift around X axis (if supported)</li>
     * <li> values[4]: estimated drift around Y axis (if supported)</li>
     * <li> values[5]: estimated drift around Z axis (if supported)</li>
     * <li> values[6]: Angular speed supported for x-axis</li>
     * <li> values[7]: Angular speed supported for y-axis</li>
     * <li> values[8]: Angular speed supported for z-axis</li>
     * </ul>
     * </p>
     *
     * <h4>{@link android.hardware.Sensor#TYPE_HEADING Sensor.TYPE_HEADING}:</h4>
     *
     * A sensor of this type measures the direction in which the device is
     * pointing relative to true north in degrees. The value must be between
     * 0.0 (inclusive) and 360.0 (exclusive), with 0 indicating north, 90 east,
     * 180 south, and 270 west.
     *
     * Accuracy is defined at 68% confidence. In the case where the underlying
     * distribution is assumed Gaussian normal, this would be considered one
     * standard deviation. For example, if heading returns 60 degrees, and
     * accuracy returns 10 degrees, then there is a 68 percent probability of
     * the true heading being between 50 degrees and 70 degrees.
     *
     * <ul>
     *  <li> values[0]: Measured heading in degrees.</li>
     *  <li> values[1]: Heading accuracy in degrees.</li>
     * </ul>
     *
     * @see GeomagneticField
     */
    public final float[] values;

    /**
     * The sensor that generated this event. See
     * {@link android.hardware.SensorManager SensorManager} for details.
     */
    public Sensor sensor;

    /**
     * The accuracy of this event. See {@link android.hardware.SensorManager
     * SensorManager} for details.
     */
    public int accuracy;

    /**
     * The time in nanoseconds at which the event happened. For a given sensor,
     * each new sensor event should be monotonically increasing using the same
     * time base as {@link android.os.SystemClock#elapsedRealtimeNanos()}.
     */
    public long timestamp;

    /**
     * Set to true when this is the first sensor event after a discontinuity.
     *
     * The exact meaning of discontinuity depends on the sensor type. For
     * {@link android.hardware.Sensor#TYPE_HEAD_TRACKER Sensor.TYPE_HEAD_TRACKER}, this means that
     * the reference frame has suddenly and significantly changed, for example if the head tracking
     * device was removed then put back.
     *
     * Note that this concept is either not relevant to or not supported by most sensor types,
     * {@link android.hardware.Sensor#TYPE_HEAD_TRACKER Sensor.TYPE_HEAD_TRACKER} being the notable
     * exception.
     */
    @SuppressLint("MutableBareField")
    public boolean firstEventAfterDiscontinuity;

    @UnsupportedAppUsage
    SensorEvent(int valueSize) {
        values = new float[valueSize];
    }

    /**
     * Construct a sensor event object by sensor object, accuracy, timestamp and values.
     * This is only used for constructing an input device sensor event object.
     * @hide
     */
    public SensorEvent(@NonNull Sensor sensor, int accuracy, long timestamp, float[] values) {
        this.sensor = sensor;
        this.accuracy = accuracy;
        this.timestamp = timestamp;
        this.values = values;
    }
}
