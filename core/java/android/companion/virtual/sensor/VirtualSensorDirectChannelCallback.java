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


import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.Sensor;
import android.hardware.SensorDirectChannel;
import android.os.MemoryFile;
import android.os.SharedMemory;

/**
 * Interface for notifying the virtual device owner about any {@link SensorDirectChannel} events.
 *
 * <p>This callback can be used for controlling the sensor event injection to direct channels. A
 * typical order of callback invocations is:
 * <ul>
 *     <li>{@code onDirectChannelCreated} - the channel handle and the associated shared memory
 *     should be stored by the virtual device</li>
 *     <li>{@code onDirectChannelConfigured} with a positive {@code rateLevel} - the virtual
 *     device should start writing to the shared memory for the associated channel with the
 *     requested parameters.</li>
 *     <li>{@code onDirectChannelConfigured} with a {@code rateLevel = RATE_STOP} - the virtual
 *     device should stop writing to the shared memory for the associated channel.</li>
 *     <li>{@code onDirectChannelDestroyed} - the shared memory associated with the channel
 *     handle should be closed.</li>
 * </ul>
 *
 * <p>The callback is tied to the VirtualDevice's lifetime as the virtual sensors are created when
 * the device is created and destroyed when the device is destroyed.
 *
 * @see VirtualSensorDirectChannelWriter
 *
 * @hide
 */
@SystemApi
public interface VirtualSensorDirectChannelCallback {
    /**
     * Called when a {@link android.hardware.SensorDirectChannel} is created.
     *
     * <p>The {@link android.hardware.SensorManager} instance used to create the direct channel must
     * be associated with the virtual device.
     *
     * @param channelHandle Identifier of the newly created channel.
     * @param sharedMemory writable shared memory region.
     *
     * @see android.hardware.SensorManager#createDirectChannel(MemoryFile)
     * @see #onDirectChannelConfigured
     * @see #onDirectChannelDestroyed
     */
    void onDirectChannelCreated(@IntRange(from = 1) int channelHandle,
            @NonNull SharedMemory sharedMemory);

    /**
     * Called when a {@link android.hardware.SensorDirectChannel} is destroyed.
     *
     * <p>The virtual device must perform any clean-up and close the shared memory that was
     * received with the {@link #onDirectChannelCreated} callback and the corresponding
     * {@code channelHandle}.
     *
     * @param channelHandle Identifier of the channel that was destroyed.
     *
     * @see SensorDirectChannel#close()
     */
    void onDirectChannelDestroyed(@IntRange(from = 1) int channelHandle);

    /**
     * Called when a {@link android.hardware.SensorDirectChannel} is configured.
     *
     * <p>Sensor events for the corresponding sensor should be written at the indicated rate to the
     * shared memory region that was received with the {@link #onDirectChannelCreated} callback and
     * the corresponding {@code channelHandle}. The events should be written in the correct format
     * and with the provided {@code reportToken} until the channel is reconfigured with
     * {@link SensorDirectChannel#RATE_STOP}.
     *
     * <p>The sensor must support direct channel in order for this callback to be invoked. Only
     * {@link MemoryFile} sensor direct channels are supported for virtual sensors.
     *
     * @param channelHandle Identifier of the channel that was configured.
     * @param sensor The sensor, for which the channel was configured.
     * @param rateLevel The rate level used to configure the direct sensor channel.
     * @param reportToken A positive sensor report token, used to differentiate between events from
     *   different sensors within the same channel.
     *
     * @see VirtualSensorConfig.Builder#setHighestDirectReportRateLevel(int)
     * @see VirtualSensorConfig.Builder#setDirectChannelTypesSupported(int)
     * @see android.hardware.SensorManager#createDirectChannel(MemoryFile)
     * @see #onDirectChannelCreated
     * @see SensorDirectChannel#configure(Sensor, int)
     */
    void onDirectChannelConfigured(@IntRange(from = 1) int channelHandle,
            @NonNull VirtualSensor sensor, @SensorDirectChannel.RateLevel int rateLevel,
            @IntRange(from = 1) int reportToken);
}
