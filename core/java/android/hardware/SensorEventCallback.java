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

import android.annotation.NonNull;

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

    /**
     * Called when the next {@link android.hardware.SensorEvent SensorEvent} to be delivered via the
     * {@link #onSensorChanged(SensorEvent) onSensorChanged} method represents the first event after
     * a discontinuity.
     *
     * The exact meaning of discontinuity depends on the sensor type. For {@link
     * android.hardware.Sensor#TYPE_HEAD_TRACKER Sensor.TYPE_HEAD_TRACKER}, this means that the
     * reference frame has suddenly and significantly changed.
     *
     * Note that this concept is either not relevant to or not supported by most sensor types,
     * {@link android.hardware.Sensor#TYPE_HEAD_TRACKER Sensor.TYPE_HEAD_TRACKER} being the notable
     * exception.
     *
     * @param sensor The {@link android.hardware.Sensor Sensor} which experienced the discontinuity.
     */
    public void onSensorDiscontinuity(@NonNull Sensor sensor) {}
}
