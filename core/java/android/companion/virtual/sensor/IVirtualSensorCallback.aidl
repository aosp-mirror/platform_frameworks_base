/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.companion.virtual.sensor.VirtualSensor;
import android.os.SharedMemory;

/**
 * Interface for notifying the sensor owner about whether and how sensor events should be injected.
 *
 * @hide
 */
oneway interface IVirtualSensorCallback {

    /**
     * Called when the requested sensor event injection parameters have changed.
     *
     * @param sensor The sensor whose requested injection parameters have changed.
     * @param enabled Whether the sensor is enabled.
     * @param samplingPeriodMicros The requested sensor's sampling period in microseconds.
     * @param batchReportingLatencyMicros The requested maximum time interval in microseconds
     *   between the delivery of two batches of sensor events.
     */
    void onConfigurationChanged(in VirtualSensor sensor, boolean enabled, int samplingPeriodMicros,
            int batchReportLatencyMicros);

    /**
     * Called when a sensor direct channel is created.
     *
     * @param channelHandle Identifier of the channel that was created.
     * @param sharedMemory The shared memory region for the direct sensor channel.
     */
    void onDirectChannelCreated(int channelHandle, in SharedMemory sharedMemory);

    /**
     * Called when a sensor direct channel is destroyed.
     *
     * @param channelHandle Identifier of the channel that was destroyed.
     */
    void onDirectChannelDestroyed(int channelHandle);

    /**
     * Called when a sensor direct channel is configured.
     *
     * @param channelHandle Identifier of the channel that was configured.
     * @param sensor The sensor, for which the channel was configured.
     * @param rateLevel The rate level used to configure the direct sensor channel.
     * @param reportToken A positive sensor report token, used to differentiate between events from
     *   different sensors within the same channel.
     */
    void onDirectChannelConfigured(int channelHandle, in VirtualSensor sensor, int rateLevel,
            int reportToken);
}
