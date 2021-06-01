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

package com.android.server.sensors;

import android.annotation.NonNull;

import java.util.concurrent.Executor;

/**
 * Local system service interface for sensors.
 *
 * @hide Only for use within system server.
 */
public abstract class SensorManagerInternal {
    /**
     * Adds a listener for changes in proximity sensor state.
     * @param executor The {@link Executor} to {@link Executor#execute invoke} the listener on.
     * @param listener The listener to add.
     *
     * @throws IllegalArgumentException when adding a listener that is already listening
     */
    public abstract void addProximityActiveListener(@NonNull Executor executor,
            @NonNull ProximityActiveListener listener);

    /**
     * Removes a previously registered listener of proximity sensor state changes.
     * @param listener The listener to remove.
     */
    public abstract void removeProximityActiveListener(@NonNull ProximityActiveListener listener);

    /**
     * Listener for proximity sensor state changes.
     */
    public interface ProximityActiveListener {
        /**
         * Callback invoked when the proximity sensor state changes
         * @param isActive whether the sensor is being enabled or disabled.
         */
        void onProximityActive(boolean isActive);
    }
}
