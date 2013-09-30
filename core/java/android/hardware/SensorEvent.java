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
 * This class represents a {@link android.hardware.Sensor Sensor} event and
 * holds informations such as the sensor's type, the time-stamp, accuracy and of
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
     * calculate a rotation describing the change of angles over the timestep,
     * for example:
     * </p>
     *
     * <pre class="prettyprint">
     *     private static final float NS2S = 1.0f / 1000000000.0f;
     *     private final float[] deltaRotationVector = new float[4]();
     *     private float timestamp;
     *
     *     public void onSensorChanged(SensorEvent event) {
     *          // This timestep's delta rotation to be multiplied by the current rotation
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
     *              // Integrate around this axis with the angular speed by the timestep
     *              // in order to get a delta rotation from this sample over the timestep
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
     *          // User code should concatenate the delta rotation we computed with the current rotation
     *          // in order to get the updated rotation.
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
     *  <p><b>Note:</b> When the device is at rest, the output of the gravity sensor should be identical
     *  to that of the accelerometer.</p>
     *
     *  <h4>{@link android.hardware.Sensor#TYPE_LINEAR_ACCELERATION Sensor.TYPE_LINEAR_ACCELERATION}:</h4>
     *  A three dimensional vector indicating acceleration along each device axis, not including
     *  gravity.  All values have units of m/s^2.  The coordinate system is the same as is used by the
     *  acceleration sensor.
     *  <p>The output of the accelerometer, gravity and  linear-acceleration sensors must obey the
     *  following relation:</p>
     *   <p><ul>acceleration = gravity + linear-acceleration</ul></p>
     *
     *  <h4>{@link android.hardware.Sensor#TYPE_ROTATION_VECTOR Sensor.TYPE_ROTATION_VECTOR}:</h4>
     *  <p>The rotation vector represents the orientation of the device as a combination of an <i>angle</i>
     *  and an <i>axis</i>, in which the device has rotated through an angle &#952 around an axis
     *  &lt;x, y, z>.</p>
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
     * values[2]: Roll, rotation around the x-axis (-90 to 90)
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
     * <li> values[0]: ambient (room) temperature in degree Celsius.</li>
     * </ul>
     *
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
     * <p> Hard iron - These distortions arise due to the magnetized iron, steel or permanenet
     * magnets on the device.
     * Soft iron - These distortions arise due to the interaction with the earth's magentic
     * field.
     * </p>
     * <h4> {@link android.hardware.Sensor#TYPE_GAME_ROTATION_VECTOR}:</h4>
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
     * The time in nanosecond at which the event happened
     */
    public long timestamp;

    SensorEvent(int valueSize) {
        values = new float[valueSize];
    }
}
