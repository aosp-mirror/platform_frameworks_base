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
import android.hardware.SensorDirectChannel;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Helper class for writing sensor events to the relevant configured direct channels.
 *
 * <p>The virtual device owner can forward the {@link VirtualSensorDirectChannelCallback}
 * invocations to a {@link VirtualSensorDirectChannelWriter} instance and use that writer to
 * write the events from the relevant sensors directly to the shared memory regions of the
 * corresponding {@link SensorDirectChannel} instances.
 *
 * <p>Example:
 * <p>During sensor and virtual device creation:
 * <pre>
 * VirtualSensorDirectChannelWriter writer = new VirtualSensorDirectChannelWriter();
 * VirtualSensorDirectChannelCallback callback = new VirtualSensorDirectChannelCallback() {
 *     {@literal @}Override
 *     public void onDirectChannelCreated(int channelHandle, SharedMemory sharedMemory) {
 *         writer.addChannel(channelHandle, sharedMemory);
 *     }
 *     {@literal @}Override
 *     public void onDirectChannelDestroyed(int channelHandle);
 *         writer.removeChannel(channelHandle);
 *     }
 *     {@literal @}Override
 *     public void onDirectChannelConfigured(int channelHandle, VirtualSensor sensor, int rateLevel,
 *             int reportToken)
 *         if (!writer.configureChannel(channelHandle, sensor, rateLevel, reportToken)) {
 *              // handle error
 *         }
 *     }
 * }
 * </pre>
 * <p>During the virtual device lifetime:
 * <pre>
 * VirtualSensor sensor = ...
 * while (shouldInjectEvents(sensor)) {
 *     if (!writer.writeSensorEvent(sensor, event)) {
 *         // handle error
 *     }
 * }
 * writer.close();
 * </pre>
 * <p>Note that the virtual device owner should take the currently configured rate level into
 * account when deciding whether and how often to inject events for a particular sensor.
 *
 * @see android.hardware.SensorDirectChannel#configure
 * @see VirtualSensorDirectChannelCallback
 *
 * @hide
 */
@SystemApi
public final class VirtualSensorDirectChannelWriter implements AutoCloseable {

    private static final String TAG = "VirtualSensorWriter";

    private static final long UINT32_MAX = 4294967295L;

    // Mapping from channel handle to channel shared memory region.
    @GuardedBy("mChannelsLock")
    private final SparseArray<SharedMemoryWrapper> mChannels = new SparseArray<>();
    private final Object mChannelsLock = new Object();

    // Mapping from sensor handle to channel handle to direct sensor configuration.
    @GuardedBy("mChannelsLock")
    private final SparseArray<SparseArray<DirectChannelConfiguration>> mConfiguredChannels =
            new SparseArray<>();

    @Override
    public void close() {
        synchronized (mChannelsLock) {
            for (int i = 0; i < mChannels.size(); ++i) {
                mChannels.valueAt(i).close();
            }
            mChannels.clear();
            mConfiguredChannels.clear();
        }
    }

    /**
     * Adds a sensor direct channel handle and the relevant shared memory region.
     *
     * @throws ErrnoException if the mapping of the shared memory region failed.
     *
     * @see VirtualSensorDirectChannelCallback#onDirectChannelCreated
     */
    public void addChannel(@IntRange(from = 1) int channelHandle,
            @NonNull SharedMemory sharedMemory) throws ErrnoException {
        synchronized (mChannelsLock) {
            if (mChannels.contains(channelHandle)) {
                Log.w(TAG, "Channel with handle " + channelHandle + " already added.");
            } else {
                mChannels.put(channelHandle,
                        new SharedMemoryWrapper(Objects.requireNonNull(sharedMemory)));
            }
        }
    }

    /**
     * Removes a sensor direct channel indicated by the handle and closes the relevant shared memory
     * region.
     *
     * @see VirtualSensorDirectChannelCallback#onDirectChannelDestroyed
     */
    public void removeChannel(@IntRange(from = 1) int channelHandle) {
        synchronized (mChannelsLock) {
            SharedMemoryWrapper sharedMemoryWrapper = mChannels.removeReturnOld(channelHandle);
            if (sharedMemoryWrapper != null) {
                sharedMemoryWrapper.close();
            }
            for (int i = 0; i < mConfiguredChannels.size(); ++i) {
                mConfiguredChannels.valueAt(i).remove(channelHandle);
            }
        }
    }

    /**
     * Configures a sensor direct channel indicated by the handle and prepares it for sensor event
     * writes for the given sensor.
     *
     * @return Whether the configuration was successful.
     *
     * @see VirtualSensorDirectChannelCallback#onDirectChannelConfigured
     */
    public boolean configureChannel(@IntRange(from = 1) int channelHandle,
            @NonNull VirtualSensor sensor, @SensorDirectChannel.RateLevel int rateLevel,
            @IntRange(from = 1) int reportToken) {
        synchronized (mChannelsLock) {
            SparseArray<DirectChannelConfiguration> configs = mConfiguredChannels.get(
                    Objects.requireNonNull(sensor).getHandle());
            if (rateLevel == SensorDirectChannel.RATE_STOP) {
                if (configs == null || configs.removeReturnOld(channelHandle) == null) {
                    Log.w(TAG, "Channel configuration failed - channel with handle "
                            + channelHandle + " not found");
                    return false;
                }
                return true;
            }

            if (configs == null) {
                configs = new SparseArray<>();
                mConfiguredChannels.put(sensor.getHandle(), configs);
            }

            SharedMemoryWrapper sharedMemoryWrapper = mChannels.get(channelHandle);
            if (sharedMemoryWrapper == null) {
                Log.w(TAG, "Channel configuration failed - channel with handle "
                        + channelHandle + " not found");
                return false;
            }
            configs.put(channelHandle, new DirectChannelConfiguration(
                    reportToken, sensor.getType(), sharedMemoryWrapper));
            return true;
        }
    }

    /**
     * Writes a sensor event for the given sensor to all configured sensor direct channels for that
     * sensor.
     *
     * @return Whether the write was successful.
     *
     */
    public boolean writeSensorEvent(@NonNull VirtualSensor sensor,
            @NonNull VirtualSensorEvent event) {
        Objects.requireNonNull(event);
        synchronized (mChannelsLock) {
            SparseArray<DirectChannelConfiguration> configs = mConfiguredChannels.get(
                    Objects.requireNonNull(sensor).getHandle());
            if (configs == null || configs.size() == 0) {
                Log.w(TAG, "Sensor event write failed - no direct sensor channels configured for "
                        + "sensor " + sensor.getName());
                return false;
            }

            for (int i = 0; i < configs.size(); ++i) {
                configs.valueAt(i).write(Objects.requireNonNull(event));
            }
        }
        return true;
    }

    private static final class SharedMemoryWrapper {

        private static final int SENSOR_EVENT_SIZE = 104;

        // The limit of number of values for a single sensor event.
        private static final int MAXIMUM_NUMBER_OF_SENSOR_VALUES = 16;

        @GuardedBy("mWriteLock")
        private final SharedMemory mSharedMemory;
        @GuardedBy("mWriteLock")
        private int mWriteOffset = 0;
        @GuardedBy("mWriteLock")
        private final ByteBuffer mEventBuffer = ByteBuffer.allocate(SENSOR_EVENT_SIZE);
        @GuardedBy("mWriteLock")
        private final ByteBuffer mMemoryMapping;
        private final Object mWriteLock = new Object();

        SharedMemoryWrapper(SharedMemory sharedMemory) throws ErrnoException {
            mSharedMemory = sharedMemory;
            mMemoryMapping = mSharedMemory.mapReadWrite();
            mEventBuffer.order(ByteOrder.nativeOrder());
        }

        void close() {
            synchronized (mWriteLock) {
                mSharedMemory.close();
            }
        }

        void write(int reportToken, int sensorType, long eventCounter, VirtualSensorEvent event) {
            synchronized (mWriteLock) {
                mEventBuffer.position(0);
                mEventBuffer.putInt(SENSOR_EVENT_SIZE);
                mEventBuffer.putInt(reportToken);
                mEventBuffer.putInt(sensorType);
                mEventBuffer.putInt((int) (eventCounter & UINT32_MAX));
                mEventBuffer.putLong(event.getTimestampNanos());

                for (int i = 0; i < MAXIMUM_NUMBER_OF_SENSOR_VALUES; ++i) {
                    if (i < event.getValues().length) {
                        mEventBuffer.putFloat(event.getValues()[i]);
                    } else {
                        mEventBuffer.putFloat(0f);
                    }
                }
                mEventBuffer.putInt(0);

                mMemoryMapping.position(mWriteOffset);
                mMemoryMapping.put(mEventBuffer.array(), 0, SENSOR_EVENT_SIZE);

                mWriteOffset += SENSOR_EVENT_SIZE;
                if (mWriteOffset + SENSOR_EVENT_SIZE >= mSharedMemory.getSize()) {
                    mWriteOffset = 0;
                }
            }
        }
    }

    private static final class DirectChannelConfiguration {
        private final int mReportToken;
        private final int mSensorType;
        private final AtomicLong mEventCounter;
        private final SharedMemoryWrapper mSharedMemoryWrapper;

        DirectChannelConfiguration(int reportToken, int sensorType,
                SharedMemoryWrapper sharedMemoryWrapper) {
            mReportToken = reportToken;
            mSensorType = sensorType;
            mEventCounter = new AtomicLong(1);
            mSharedMemoryWrapper = sharedMemoryWrapper;
        }

        void write(VirtualSensorEvent event) {
            long currentCounter = mEventCounter.getAcquire();
            mSharedMemoryWrapper.write(mReportToken, mSensorType, currentCounter++, event);
            if (currentCounter == UINT32_MAX + 1) {
                currentCounter = 1;
            }
            mEventCounter.setRelease(currentCounter);
        }
    }
}
