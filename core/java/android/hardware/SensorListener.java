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
 * Used for receiving notifications from the SensorManager when
 * sensor values have changed.
 * 
 * @deprecated Use 
 * {@link android.hardware.SensorEventListener SensorEventListener} instead.
 */
@Deprecated
public interface SensorListener {

    /**
     * <p>Called when sensor values have changed.
     * The length and contents of the values array vary
     * depending on which sensor is being monitored.
     * See {@link android.hardware.SensorManager SensorManager}
     * for details on possible sensor types.
     *
     * <p><u>Definition of the coordinate system used below.</u><p>
     * <p>The X axis refers to the screen's horizontal axis
     * (the small edge in portrait mode, the long edge in landscape mode) and
     * points to the right. 
     * <p>The Y axis refers to the screen's vertical axis and points towards
     * the top of the screen (the origin is in the lower-left corner).
     * <p>The Z axis points toward the sky when the device is lying on its back
     * on a table.
     * <p> <b>IMPORTANT NOTE:</b> The axis <b><u>are swapped</u></b> when the
     * device's screen orientation changes. To access the unswapped values,
     * use indices 3, 4 and 5 in values[].
     * 
     * <p>{@link android.hardware.SensorManager#SENSOR_ORIENTATION SENSOR_ORIENTATION},
     * {@link android.hardware.SensorManager#SENSOR_ORIENTATION_RAW SENSOR_ORIENTATION_RAW}:<p>
     *  All values are angles in degrees.
     * 
     * <p>values[0]: Azimuth, rotation around the Z axis (0<=azimuth<360).
     * 0 = North, 90 = East, 180 = South, 270 = West
     * 
     * <p>values[1]: Pitch, rotation around X axis (-180<=pitch<=180), with positive
     * values when the z-axis moves toward the y-axis.
     *
     * <p>values[2]: Roll, rotation around Y axis (-90<=roll<=90), with positive values 
     * when the z-axis moves toward the x-axis.
     *
     * <p>Note that this definition of yaw, pitch and roll is different from the
     * traditional definition used in aviation where the X axis is along the long
     * side of the plane (tail to nose).
     *
     * <p>{@link android.hardware.SensorManager#SENSOR_ACCELEROMETER SENSOR_ACCELEROMETER}:<p>
     *  All values are in SI units (m/s^2) and measure contact forces.
     *  
     *  <p>values[0]: force applied by the device on the x-axis 
     *  <p>values[1]: force applied by the device on the y-axis 
     *  <p>values[2]: force applied by the device on the z-axis
     *  
     *  <p><u>Examples</u>:
     *    <li>When the device is pushed on its left side toward the right, the
     *    x acceleration value is negative (the device applies a reaction force
     *    to the push toward the left)</li>
     *    
     *    <li>When the device lies flat on a table, the acceleration value is 
     *    {@link android.hardware.SensorManager#STANDARD_GRAVITY -STANDARD_GRAVITY},
     *    which correspond to the force the device applies on the table in reaction
     *    to gravity.</li>
     *
     * <p>{@link android.hardware.SensorManager#SENSOR_MAGNETIC_FIELD SENSOR_MAGNETIC_FIELD}:<p>
     *  All values are in micro-Tesla (uT) and measure the ambient magnetic
     *  field in the X, Y and -Z axis.
     *  <p><b><u>Note:</u></b> the magnetic field's Z axis is inverted.
     *  
     * @param sensor The ID of the sensor being monitored
     * @param values The new values for the sensor.
     */
    public void onSensorChanged(int sensor, float[] values);

    /**
     * Called when the accuracy of a sensor has changed.
     * See {@link android.hardware.SensorManager SensorManager}
     * for details.
     *
     * @param sensor The ID of the sensor being monitored
     * @param accuracy The new accuracy of this sensor.
     */
    public void onAccuracyChanged(int sensor, int accuracy);    
}
