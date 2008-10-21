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
 */
public interface SensorListener {

    /**
     * Called when sensor values have changed.
     * The length and contents of the values array vary
     * depending on which sensor is being monitored.
     * See {@link android.hardware.SensorManager SensorManager}
     * for details on possible sensor types and values.
     *
     * @param sensor The ID of the sensor being monitored
     * @param values The new values for the sensor
     */
    public void onSensorChanged(int sensor, float[] values);

    /**
     * Called when the accuracy of a sensor has changed.
     * See {@link android.hardware.SensorManager SensorManager}
     * for details.
     *
     * @param sensor The ID of the sensor being monitored
     * @param accuracy The new accuracy of this sensor
     */
    public void onAccuracyChanged(int sensor, int accuracy);    
}
