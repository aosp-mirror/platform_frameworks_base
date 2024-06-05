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

package com.android.server.companion.virtual;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.sensor.IVirtualSensorCallback;
import android.companion.virtual.sensor.VirtualSensor;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.companion.virtual.sensor.VirtualSensorEvent;
import android.content.AttributionSource;
import android.hardware.SensorDirectChannel;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.expresslog.Counter;
import com.android.server.LocalServices;
import com.android.server.sensors.SensorManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Controls virtual sensors, including their lifecycle and sensor event dispatch. */
public class SensorController {

    private static final String TAG = "SensorController";

    // See system/core/libutils/include/utils/Errors.h
    private static final int OK = 0;
    private static final int UNKNOWN_ERROR = (-2147483647 - 1); // INT32_MIN value
    private static final int BAD_VALUE = -22;

    private static AtomicInteger sNextDirectChannelHandle = new AtomicInteger(1);

    private final Object mLock = new Object();
    private final int mVirtualDeviceId;

    @GuardedBy("mLock")
    private final ArrayMap<IBinder, SensorDescriptor> mSensorDescriptors = new ArrayMap<>();

    // This device's sensors, keyed by sensor handle.
    @GuardedBy("mLock")
    private SparseArray<VirtualSensor> mVirtualSensors = new SparseArray<>();
    @GuardedBy("mLock")
    private List<VirtualSensor> mVirtualSensorList = null;

    @NonNull
    private final AttributionSource mAttributionSource;
    @NonNull
    private final SensorManagerInternal.RuntimeSensorCallback mRuntimeSensorCallback;
    private final SensorManagerInternal mSensorManagerInternal;
    private final VirtualDeviceManagerInternal mVdmInternal;

    public SensorController(@NonNull IVirtualDevice virtualDevice, int virtualDeviceId,
            @NonNull AttributionSource attributionSource,
            @Nullable IVirtualSensorCallback virtualSensorCallback,
            @NonNull List<VirtualSensorConfig> sensors) {
        mVirtualDeviceId = virtualDeviceId;
        mAttributionSource = attributionSource;
        mRuntimeSensorCallback = new RuntimeSensorCallbackWrapper(virtualSensorCallback);
        mSensorManagerInternal = LocalServices.getService(SensorManagerInternal.class);
        mVdmInternal = LocalServices.getService(VirtualDeviceManagerInternal.class);
        createSensors(virtualDevice, sensors);
    }

    void close() {
        synchronized (mLock) {
            mSensorDescriptors.values().forEach(
                    descriptor -> mSensorManagerInternal.removeRuntimeSensor(descriptor.mHandle));
            mSensorDescriptors.clear();
            mVirtualSensors.clear();
            mVirtualSensorList = null;
        }
    }

    private void createSensors(@NonNull IVirtualDevice virtualDevice,
            @NonNull List<VirtualSensorConfig> configs) {
        Objects.requireNonNull(virtualDevice);
        final long token = Binder.clearCallingIdentity();
        try {
            for (VirtualSensorConfig config : configs) {
                createSensorInternal(virtualDevice, config);
            }
        } catch (SensorCreationException e) {
            throw new RuntimeException("Failed to create virtual sensor", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void createSensorInternal(@NonNull IVirtualDevice virtualDevice,
            @NonNull VirtualSensorConfig config)
            throws SensorCreationException {
        Objects.requireNonNull(config);
        if (config.getType() <= 0) {
            throw new SensorCreationException(
                    "Received an invalid virtual sensor type (config name '" + config.getName()
                            + "').");
        }
        final int handle = mSensorManagerInternal.createRuntimeSensor(mVirtualDeviceId,
                config.getType(), config.getName(),
                config.getVendor() == null ? "" : config.getVendor(), config.getMaximumRange(),
                config.getResolution(), config.getPower(), config.getMinDelay(),
                config.getMaxDelay(), config.getFlags(), mRuntimeSensorCallback);
        if (handle <= 0) {
            throw new SensorCreationException(
                    "Received an invalid virtual sensor handle '" + config.getName() + "'.");
        }

        SensorDescriptor sensorDescriptor = new SensorDescriptor(
                handle, config.getType(), config.getName());
        final IBinder sensorToken =
                new Binder("android.hardware.sensor.VirtualSensor:" + config.getName());
        VirtualSensor sensor = new VirtualSensor(handle, config.getType(), config.getName(),
                virtualDevice, sensorToken);
        synchronized (mLock) {
            mSensorDescriptors.put(sensorToken, sensorDescriptor);
            mVirtualSensors.put(handle, sensor);
        }
        if (android.companion.virtualdevice.flags.Flags.metricsCollection()) {
            Counter.logIncrementWithUid(
                    "virtual_devices.value_virtual_sensors_created_count",
                    mAttributionSource.getUid());
        }
    }

    boolean sendSensorEvent(@NonNull IBinder token, @NonNull VirtualSensorEvent event) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(event);
        synchronized (mLock) {
            final SensorDescriptor sensorDescriptor = mSensorDescriptors.get(token);
            if (sensorDescriptor == null) {
                throw new IllegalArgumentException("Could not send sensor event for given token");
            }
            return mSensorManagerInternal.sendSensorEvent(
                    sensorDescriptor.getHandle(), sensorDescriptor.getType(),
                    event.getTimestampNanos(), event.getValues());
        }
    }

    @Nullable
    VirtualSensor getSensorByHandle(int handle) {
        synchronized (mLock) {
            return mVirtualSensors.get(handle);
        }
    }

    List<VirtualSensor> getSensorList() {
        synchronized (mLock) {
            if (mVirtualSensorList == null) {
                mVirtualSensorList = new ArrayList<>(mVirtualSensors.size());
                for (int i = 0; i < mVirtualSensors.size(); ++i) {
                    mVirtualSensorList.add(mVirtualSensors.valueAt(i));
                }
                mVirtualSensorList = Collections.unmodifiableList(mVirtualSensorList);
            }
            return mVirtualSensorList;
        }
    }

    void dump(@NonNull PrintWriter fout) {
        fout.println("    SensorController: ");
        synchronized (mLock) {
            fout.println("      Active descriptors: ");
            for (SensorDescriptor sensorDescriptor : mSensorDescriptors.values()) {
                fout.println("        handle: " + sensorDescriptor.getHandle());
                fout.println("          type: " + sensorDescriptor.getType());
                fout.println("          name: " + sensorDescriptor.getName());
            }
        }
    }

    @VisibleForTesting
    void addSensorForTesting(IBinder deviceToken, int handle, int type, String name) {
        synchronized (mLock) {
            mSensorDescriptors.put(deviceToken,
                    new SensorDescriptor(handle, type, name));
        }
    }

    @VisibleForTesting
    Map<IBinder, SensorDescriptor> getSensorDescriptors() {
        synchronized (mLock) {
            return new ArrayMap<>(mSensorDescriptors);
        }
    }

    private final class RuntimeSensorCallbackWrapper
            implements SensorManagerInternal.RuntimeSensorCallback {
        @Nullable
        private IVirtualSensorCallback mCallback;

        RuntimeSensorCallbackWrapper(@Nullable IVirtualSensorCallback callback) {
            mCallback = callback;
        }

        @Override
        public int onConfigurationChanged(int handle, boolean enabled, int samplingPeriodMicros,
                int batchReportLatencyMicros) {
            if (mCallback == null) {
                Slog.e(TAG, "No sensor callback configured for sensor handle " + handle);
                return BAD_VALUE;
            }
            VirtualSensor sensor = mVdmInternal.getVirtualSensor(mVirtualDeviceId, handle);
            if (sensor == null) {
                Slog.e(TAG, "No sensor found for deviceId=" + mVirtualDeviceId
                        + " and sensor handle=" + handle);
                return BAD_VALUE;
            }
            try {
                mCallback.onConfigurationChanged(sensor, enabled, samplingPeriodMicros,
                        batchReportLatencyMicros);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to call sensor callback: " + e);
                return UNKNOWN_ERROR;
            }
            return OK;
        }

        @Override
        public int onDirectChannelCreated(ParcelFileDescriptor fd) {
            if (mCallback == null) {
                Slog.e(TAG, "No sensor callback for virtual deviceId " + mVirtualDeviceId);
                return BAD_VALUE;
            } else if (fd == null) {
                Slog.e(TAG, "Received invalid ParcelFileDescriptor");
                return BAD_VALUE;
            }
            final int channelHandle = sNextDirectChannelHandle.getAndIncrement();
            SharedMemory sharedMemory = SharedMemory.fromFileDescriptor(fd);
            try {
                mCallback.onDirectChannelCreated(channelHandle, sharedMemory);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to call sensor callback: " + e);
                return UNKNOWN_ERROR;
            }
            return channelHandle;
        }

        @Override
        public void onDirectChannelDestroyed(int channelHandle) {
            if (mCallback == null) {
                Slog.e(TAG, "No sensor callback for virtual deviceId " + mVirtualDeviceId);
                return;
            }
            try {
                mCallback.onDirectChannelDestroyed(channelHandle);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to call sensor callback: " + e);
            }
        }

        @Override
        public int onDirectChannelConfigured(int channelHandle, int sensorHandle,
                @SensorDirectChannel.RateLevel int rateLevel) {
            if (mCallback == null) {
                Slog.e(TAG, "No runtime sensor callback configured.");
                return BAD_VALUE;
            }
            VirtualSensor sensor = mVdmInternal.getVirtualSensor(mVirtualDeviceId, sensorHandle);
            if (sensor == null) {
                Slog.e(TAG, "No sensor found for deviceId=" + mVirtualDeviceId
                        + " and sensor handle=" + sensorHandle);
                return BAD_VALUE;
            }
            try {
                mCallback.onDirectChannelConfigured(channelHandle, sensor, rateLevel, sensorHandle);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to call sensor callback: " + e);
                return UNKNOWN_ERROR;
            }
            if (rateLevel == SensorDirectChannel.RATE_STOP) {
                return OK;
            } else {
                // Use the sensor handle as a report token, i.e. a unique identifier of the sensor.
                return sensorHandle;
            }
        }
    }

    @VisibleForTesting
    static final class SensorDescriptor {

        private final int mHandle;
        private final int mType;
        private final String mName;

        SensorDescriptor(int handle, int type, String name) {
            mHandle = handle;
            mType = type;
            mName = name;
        }
        public int getHandle() {
            return mHandle;
        }
        public int getType() {
            return mType;
        }
        public String getName() {
            return mName;
        }
    }

    /** An internal exception that is thrown to indicate an error when opening a virtual sensor. */
    private static class SensorCreationException extends Exception {
        SensorCreationException(String message) {
            super(message);
        }
    }
}
