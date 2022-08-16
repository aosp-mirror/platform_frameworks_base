/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.util.sensors;

/**
 * Wrapper class for a dual ProximitySensor which supports a secondary sensor gated upon the
 * primary).
 */
public interface ProximitySensor extends ThresholdSensor {
    /**
     * Returns true if we are registered with the SensorManager.
     */
    boolean isRegistered();

    /**
     * Whether the proximity sensor reports near. Can return null if no information has been
     * received yet.
     */
    Boolean isNear();

    /** Update all listeners with the last value this class received from the sensor. */
    void alertListeners();

    /**
     * Sets that it is safe to leave the secondary sensor on indefinitely.
     *
     * The secondary sensor will be turned on if there are any registered listeners, regardless
     * of what is reported by the primary sensor.
     */
    void setSecondarySafe(boolean safe);

    /**
     * Called when the proximity sensor is no longer needed. All listeners should
     * be unregistered and cleaned up.
     */
    void destroy();
}
