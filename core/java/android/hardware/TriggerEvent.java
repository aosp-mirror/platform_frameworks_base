/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * This class represents a Trigger Event - the event
 * associated with a Trigger Sensor. When the sensor detects a trigger
 * event condition, such as significant motion in the case of the
 * {@link Sensor#TYPE_SIGNIFICANT_MOTION}, the {@link TriggerEventListener}
 * is called with the TriggerEvent. The sensor is automatically canceled
 * after the trigger.
 * <p>
 * This class holds information such as the value of the sensor
 * when the trigger happened, the timestamp along with detailed
 * information regarding the Sensor itself.
 * </p>
 * @see android.hardware.SensorManager
 * @see android.hardware.TriggerEvent
 * @see android.hardware.Sensor
 */
public final class TriggerEvent {
    /**
     * <p>
     * The length and contents of the {@link #values values} array depends on
     * which {@link android.hardware.Sensor sensor} type is being monitored (see
     * also {@link SensorEvent} for a definition of the coordinate system used).
     * </p>
     * <h4> {@link Sensor#TYPE_SIGNIFICANT_MOTION} </h4>
     * The value field is of length 1. value[0] = 1.0 when the sensor triggers.
     * 1.0 is the only allowed value.
     */
    public final float[] values;

    /**
     * The sensor that generated this event. See
     * {@link android.hardware.SensorManager SensorManager} for details.
     */
    public Sensor sensor;

    /**
     * The time in nanosecond at which the event happened
     */
    public long timestamp;

    TriggerEvent(int size) {
        values = new float[size];
    }
}
