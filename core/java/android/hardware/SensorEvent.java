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
 * This class represents a sensor event and holds informations such as the
 * sensor type (eg: accelerometer, orientation, etc...), the time-stamp, 
 * accuracy and of course the sensor's {@link SensorEvent#values data}.
 *
 * <p><u>Definition of the coordinate system used by the SensorEvent API.</u><p>
 * 
 * <pre>
 * The coordinate space is defined relative to the screen of the phone 
 * in its default orientation. The axes are not swapped when the device's
 * screen orientation changes.
 * 
 * The OpenGL ES coordinate system is used. The origin is in the
 * lower-left corner  with respect to the screen, with the X axis horizontal
 * and pointing  right, the Y axis vertical and pointing up and the Z axis
 * pointing outside the front face of the screen. In this system, coordinates
 * behind the screen have negative Z values.
 * 
 * <b>Note:</b> This coordinate system is different from the one used in the
 * Android 2D APIs where the origin is in the top-left corner. 
 *
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
     * The length and contents of the values array vary depending on which
     * sensor type is being monitored (see also {@link SensorEvent} for a 
     * definition of the coordinate system used):
     * 
     * <p>{@link android.hardware.Sensor#TYPE_ORIENTATION Sensor.TYPE_ORIENTATION}:<p>
     *  All values are angles in degrees.
     * 
     * <p>values[0]: Azimuth, angle between the magnetic north direction and
     * the Y axis, around the Z axis (0 to 359).
     * 0=North, 90=East, 180=South, 270=West
     *
     * <p>values[1]: Pitch, rotation around X axis (-180 to 180), 
     * with positive values when the z-axis moves <b>toward</b> the y-axis.
     *
     * <p>values[2]: Roll, rotation around Y axis (-90 to 90), with 
     * positive values  when the x-axis moves <b>toward</b> the z-axis.
     * 
     * <p><b>Important note:</b> For historical reasons the roll angle is
     * positive in the clockwise direction (mathematically speaking, it
     * should be positive in the counter-clockwise direction).
     *
     * <p><b>Note:</b> This definition is different from <b>yaw, pitch and 
     * roll</b> used in aviation where the X axis is along the long side of
     * the plane (tail to nose).
     *
     * <p><b>Note:</b> This sensor type exists for legacy reasons, please use
     * {@link android.hardware.SensorManager#getRotationMatrix 
     *      getRotationMatrix()} in conjunction with
     * {@link android.hardware.SensorManager#remapCoordinateSystem 
     *      remapCoordinateSystem()} and
     * {@link android.hardware.SensorManager#getOrientation getOrientation()}
     * to compute these values instead.
     *
     * <p>{@link android.hardware.Sensor#TYPE_ACCELEROMETER Sensor.TYPE_ACCELEROMETER}:<p>
     *  All values are in SI units (m/s^2) and measure the acceleration applied
     *  to the phone minus the force of gravity.
     *  
     *  <p>values[0]: Acceleration minus Gx on the x-axis 
     *  <p>values[1]: Acceleration minus Gy on the y-axis 
     *  <p>values[2]: Acceleration minus Gz on the z-axis
     *  
     *  <p><u>Examples</u>:
     *    <li>When the device lies flat on a table and is pushed on its left 
     *    side toward the right, the x acceleration value is positive.</li>
     *    
     *    <li>When the device lies flat on a table, the acceleration value is 
     *    +9.81, which correspond to the acceleration of the device (0 m/s^2) 
     *    minus the force of gravity (-9.81 m/s^2).</li>
     *    
     *    <li>When the device lies flat on a table and is pushed toward the sky
     *    with an acceleration of A m/s^2, the acceleration value is equal to 
     *    A+9.81 which correspond to the acceleration of the 
     *    device (+A m/s^2) minus the force of gravity (-9.81 m/s^2).</li>
     * 
     * 
     * <p>{@link android.hardware.Sensor#TYPE_MAGNETIC_FIELD Sensor.TYPE_MAGNETIC_FIELD}:<p>
     *  All values are in micro-Tesla (uT) and measure the ambient magnetic
     *  field in the X, Y and Z axis.
     *
     * <p>{@link android.hardware.Sensor#TYPE_GYROSCOPE Sensor.TYPE_GYROSCOPE}:<p>
     *  All values are in radians/second and measure the rate of rotation
     *  around the X, Y and Z axis. The coordinate system is the same as is
     *  used for the acceleration sensor.  Rotation is positive in the counter-clockwise
     *  direction.  That is, an observer looking from some positive location on the x, y.
     *  or z axis at a device positioned on the origin would report positive rotation
     *  if the device appeared to be rotating counter clockwise.  Note that this is the
     *  standard mathematical definition of positive rotation and does not agree with the
     *  definition of roll given earlier.
     *
     * <p>{@link android.hardware.Sensor#TYPE_LIGHT Sensor.TYPE_LIGHT}:<p>
     *
     *  <p>values[0]: Ambient light level in SI lux units
     *
     * <p>{@link android.hardware.Sensor#TYPE_PROXIMITY Sensor.TYPE_PROXIMITY}:<p>
     *
     *  <p>values[0]: Proximity sensor distance measured in centimeters
     *
     *  <p> Note that some proximity sensors only support a binary "close" or "far" measurement.
     *   In this case, the sensor should report its maxRange value in the "far" state and a value
     *   less than maxRange in the "near" state.
     *
     *  <p>{@link android.hardware.Sensor#TYPE_GRAVITY Sensor.TYPE_GRAVITY}:<p>
     *  A three dimensional vector indicating the direction and magnitude of gravity.  Units
     *  are m/s^2.  The coordinate system is the same as is used by the acceleration sensor.
     *
     *  <p>{@link android.hardware.Sensor#TYPE_LINEAR_ACCELERATION Sensor.TYPE_LINEAR_ACCELERATION}:<p>
     *  A three dimensional vector indicating acceleration along each device axis, not including
     *  gravity.  All values have units of m/s^2.  The coordinate system is the same as is used by the
     * acceleration sensor.
     *
     *  <p>{@link android.hardware.Sensor#TYPE_ROTATION_VECTOR Sensor.TYPE_ROTATION_VECTOR}:<p>
     *  The rotation vector represents the orientation of the device as a combination of an angle
     *  and an axis, in which the device has rotated through an angle theta around an axis
     *  <x, y, z>. The three elements of the rotation vector are
     *  <x*sin(theta/2), y*sin(theta/2), z*sin(theta/2)>, such that the magnitude of the rotation
     *  vector is equal to sin(theta/2), and the direction of the rotation vector is equal to the
     *  direction of the axis of rotation. The three elements of the rotation vector are equal to
     *  the last three components of a unit quaternion
     *  <cos(theta/2), x*sin(theta/2), y*sin(theta/2), z*sin(theta/2)>.  Elements of the rotation
     *  vector are unitless.  The x,y, and z axis are defined in the same way as the acceleration
     *  sensor.
     */

    public final float[] values;

    /**
     * The sensor that generated this event.
     * See {@link android.hardware.SensorManager SensorManager}
     * for details.
     */
    public Sensor sensor;
    
    /**
     * The accuracy of this event.
     * See {@link android.hardware.SensorManager SensorManager}
     * for details.
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
