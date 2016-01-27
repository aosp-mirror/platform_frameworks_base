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

/**
 * Used for receiving sensor additional information frames.
 */
public abstract class SensorEventCallback implements SensorEventListener2 {

    /**
     * Called when sensor values have changed.
     *
     * @see android.hardware.SensorEventListener#onSensorChanged(SensorEvent)
     */
    @Override
    public void onSensorChanged(SensorEvent event) {}

    /**
     * Called when the accuracy of the registered sensor has changed.
     *
     * @see android.hardware.SensorEventListener#onAccuracyChanged(Sensor, int)
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * Called after flush() is completed.
     *
     * @see android.hardware.SensorEventListener2#onFlushCompleted(Sensor)
     */
    @Override
    public void onFlushCompleted(Sensor sensor) {}

    /**
     * Called when a sensor additional information frame is available.
     *
     * @param info A {@link android.hardware.SensorAdditionalInfo SensorAdditionalInfo} frame
     * reported from sensor hardware.
     */
    public void onSensorAdditionalInfo(SensorAdditionalInfo info) {}
}
