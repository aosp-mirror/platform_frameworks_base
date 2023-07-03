/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.companion.virtual.sensor;


import android.annotation.NonNull;
import android.annotation.SystemApi;

import java.time.Duration;

/**
 * Interface for notifying the virtual device owner about whether and how sensor events should be
 * injected.
 *
 * <p>This callback can be used for controlling the sensor event injection - e.g. if the sensor is
 * not enabled, then no events should be injected. Similarly, the rate and delay of the injected
 * events that the registered listeners expect are specified here.
 *
 * <p>The callback is tied to the VirtualDevice's lifetime as the virtual sensors are created when
 * the device is created and destroyed when the device is destroyed.
 *
 * @hide
 */
@SystemApi
public interface VirtualSensorCallback {
    /**
     * Called when the requested sensor event injection parameters have changed.
     *
     * <p>This is effectively called when the registered listeners to a virtual sensor have changed.
     * The events for the corresponding sensor should be sent via {@link VirtualSensor#sendEvent}.
     *
     * @param sensor The sensor whose requested injection parameters have changed.
     * @param enabled Whether the sensor is enabled. True if any listeners are currently registered,
     *   and false otherwise.
     * @param samplingPeriod The requested sampling period of the sensor.
     * @param batchReportLatency The requested maximum time interval between the delivery of two
     *   batches of sensor events.
     */
    void onConfigurationChanged(@NonNull VirtualSensor sensor, boolean enabled,
            @NonNull Duration samplingPeriod, @NonNull Duration batchReportLatency);
}
