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
 * <p>
 * This class represents a {@link android.hardware.Sensor Sensor} event and
 * holds informations such as the sensor's type, the time-stamp, accuracy and of
 * course the sensor's {@link SensorEvent#values data}.
 * </p>
 *
 * <p>
 * <u>Definition of the coordinate system used by the SensorEvent API.</u>
 * </p>
 *
 * <p>
 * The coordinate space is defined relative to the screen of the phone in its
 * default orientation. The axes are not swapped when the device's screen
 * orientation changes.
 * </p>
 *
 * <p>
 * The OpenGL ES coordinate system is used. The origin is in the lower-left
 * corner with respect to the screen, with the X axis horizontal and pointing
 * right, the Y axis vertical and pointing up and the Z axis pointing outside
 * the front face of the screen. In this system, coordinates behind the screen
 * have negative Z values.
 * </p>
 *
 * <p>
 * <b>Note:</b> This coordinate system is different from the one used in the
 * Android 2D APIs where the origin is in the top-left corner.
 * </p>
 *
 * <pre>
 *   x<0         x>0
 *                ^
 *                |
 *    +-----------+-->  y>0
 *    |           |
 *    |           |
 *    |           |
 *    |           |   / z<0
 *    |           |  /
 *    |           | /
 *    O-----------+/
 *    |[]  [ ]  []/
 *    +----------/+     y<0
 *              /
 *             /
 *           |/ z>0 (toward the sky)
 *
 *    O: Origin (x=0,y=0,z=0)
 * </pre>
 */

public class SensorEvent {
    /**
     * <p>
     * The length and contents of the values array vary depending on which
     * {@link android.hardware.Sensor sensor} type is being monitored (see also
     * {@link SensorEvent} for a definition of the coordinate system used):
     * </p>
     *
     * <h3>{@link android.hardware.Sensor#TYPE_ORIENTATION
     * Sensor.TYPE_ORIENTATION}:</h3> All values are angles in degrees.
     *
     * <ul>
     * <p>
     * values[0]: Azimuth, angle between the magnetic north direction and the Y
     * axis, around the Z axis (0 to 359). 0=North, 90=East, 180=South, 270=West
     *
     * <p>
     * values[1]: Pitch, rotation around X axis (-180 to 180), with positive
     * values when the z-axis moves <b>toward</b> the y-axis.
     *
     * <p>
     * values[2]: Roll, rotation around Y axis (-90 to 90), with positive values
     * when the x-axis moves <b>toward</b> the z-axis.
     * </ul>
     *
     * <p>
     * <b>Important note:</b> For historical reasons the roll angle is positive
     * in the clockwise direction (mathematically speaking, it should be
     * positive in the counter-clockwise direction).
     *
     * <p>
     * <b>Note:</b> This definition is different from <b>yaw, pitch and roll</b>
     * used in aviation where the X axis is along the long side of the plane
     * (tail to nose).
     *
     * <p>
     * <b>Note:</b> This sensor type exists for legacy reasons, please use
     * {@link android.hardware.SensorManager#getRotationMatrix
     * getRotationMatrix()} in conjunction with
     * {@link android.hardware.SensorManager#remapCoordinateSystem
     * remapCoordinateSystem()} and
     * {@link android.hardware.SensorManager#getOrientation getOrientation()} to
     * compute these values instead.
     *
     * <h3>{@link android.hardware.Sensor#TYPE_ACCELEROMETER
     * Sensor.TYPE_ACCELEROMETER}:</h3>
     * All values are in SI units (m/s^2) and measure the acceleration applied
     * to the phone minus the force of gravity.
     *
     * <ul>
     * <p>
     * values[0]: Acceleration minus Gx on the x-axis
     * <p>
     * values[1]: Acceleration minus Gy on the y-axis
     * <p>
     * values[2]: Acceleration minus Gz on the z-axis
     * </ul>
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
     * <h3>{@link android.hardware.Sensor#TYPE_MAGNETIC_FIELD
     * Sensor.TYPE_MAGNETIC_FIELD}:</h3>
     * All values are in micro-Tesla (uT) and measure the ambient magnetic field
     * in the X, Y and Z axis.
     *
     * <h3>{@link android.hardware.Sensor#TYPE_LIGHT Sensor.TYPE_LIGHT}:</h3>
     *
     * <ul>
     * <p>
     * values[0]: Ambient light level in SI lux units
     * </ul>
     *
     * <h3>{@link android.hardware.Sensor#TYPE_PROXIMITY Sensor.TYPE_PROXIMITY}:
     * </h3>
     *
     * <ul>
     * <p>
     * values[0]: Proximity sensor distance measured in centimeters
     * </ul>
     *
     * <p>
     * Note that some proximity sensors only support a binary "close" or "far"
     * measurement. In this case, the sensor should report its maxRange value in
     * the "far" state and a value less than maxRange in the "near" state.
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


    SensorEvent(int size) {
        values = new float[size];
    }
}
