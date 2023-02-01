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
import android.companion.virtual.sensor.IVirtualSensorStateChangeCallback;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.companion.virtual.sensor.VirtualSensorEvent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.sensors.SensorManagerInternal;

import java.io.PrintWriter;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/** Controls virtual sensors, including their lifecycle and sensor event dispatch. */
public class SensorController {

    private static final String TAG = "SensorController";

    private final Object mLock;
    private final int mVirtualDeviceId;
    @GuardedBy("mLock")
    private final Map<IBinder, SensorDescriptor> mSensorDescriptors = new ArrayMap<>();

    private final SensorManagerInternal mSensorManagerInternal;

    public SensorController(@NonNull Object lock, int virtualDeviceId) {
        mLock = lock;
        mVirtualDeviceId = virtualDeviceId;
        mSensorManagerInternal = LocalServices.getService(SensorManagerInternal.class);
    }

    void close() {
        synchronized (mLock) {
            final Iterator<Map.Entry<IBinder, SensorDescriptor>> iterator =
                    mSensorDescriptors.entrySet().iterator();
            if (iterator.hasNext()) {
                final Map.Entry<IBinder, SensorDescriptor> entry = iterator.next();
                final IBinder token = entry.getKey();
                final SensorDescriptor sensorDescriptor = entry.getValue();
                iterator.remove();
                closeSensorDescriptorLocked(token, sensorDescriptor);
            }
        }
    }

    void createSensor(@NonNull IBinder deviceToken, @NonNull VirtualSensorConfig config) {
        Objects.requireNonNull(deviceToken);
        Objects.requireNonNull(config);
        try {
            createSensorInternal(deviceToken, config);
        } catch (SensorCreationException e) {
            throw new RuntimeException(
                    "Failed to create virtual sensor '" + config.getName() + "'.", e);
        }
    }

    private void createSensorInternal(IBinder deviceToken, VirtualSensorConfig config)
            throws SensorCreationException {
        final SensorManagerInternal.RuntimeSensorStateChangeCallback runtimeSensorCallback =
                (enabled, samplingPeriodMicros, batchReportLatencyMicros) -> {
                    IVirtualSensorStateChangeCallback callback = config.getStateChangeCallback();
                    if (callback != null) {
                        try {
                            callback.onStateChanged(
                                    enabled, samplingPeriodMicros, batchReportLatencyMicros);
                        } catch (RemoteException e) {
                            throw new RuntimeException("Failed to call sensor callback.", e);
                        }
                    }
                };

        final int handle = mSensorManagerInternal.createRuntimeSensor(mVirtualDeviceId,
                config.getType(), config.getName(),
                config.getVendor() == null ? "" : config.getVendor(),
                runtimeSensorCallback);
        if (handle <= 0) {
            throw new SensorCreationException("Received an invalid virtual sensor handle.");
        }

        // The handle is valid from here, so ensure that all failures clean it up.
        final BinderDeathRecipient binderDeathRecipient;
        try {
            binderDeathRecipient = new BinderDeathRecipient(deviceToken);
            deviceToken.linkToDeath(binderDeathRecipient, /* flags= */ 0);
        } catch (RemoteException e) {
            mSensorManagerInternal.removeRuntimeSensor(handle);
            throw new SensorCreationException("Client died before sensor could be created.", e);
        }

        synchronized (mLock) {
            SensorDescriptor sensorDescriptor = new SensorDescriptor(
                    handle, config.getType(), config.getName(), binderDeathRecipient);
            mSensorDescriptors.put(deviceToken, sensorDescriptor);
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

    void unregisterSensor(@NonNull IBinder token) {
        Objects.requireNonNull(token);
        synchronized (mLock) {
            final SensorDescriptor sensorDescriptor = mSensorDescriptors.remove(token);
            if (sensorDescriptor == null) {
                throw new IllegalArgumentException("Could not unregister sensor for given token");
            }
            closeSensorDescriptorLocked(token, sensorDescriptor);
        }
    }

    @GuardedBy("mLock")
    private void closeSensorDescriptorLocked(IBinder token, SensorDescriptor sensorDescriptor) {
        token.unlinkToDeath(sensorDescriptor.getDeathRecipient(), /* flags= */ 0);
        final int handle = sensorDescriptor.getHandle();
        mSensorManagerInternal.removeRuntimeSensor(handle);
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
                    new SensorDescriptor(handle, type, name, () -> {}));
        }
    }

    @VisibleForTesting
    Map<IBinder, SensorDescriptor> getSensorDescriptors() {
        synchronized (mLock) {
            return mSensorDescriptors;
        }
    }

    @VisibleForTesting
    static final class SensorDescriptor {

        private final int mHandle;
        private final IBinder.DeathRecipient mDeathRecipient;
        private final int mType;
        private final String mName;

        SensorDescriptor(int handle, int type, String name, IBinder.DeathRecipient deathRecipient) {
            mHandle = handle;
            mDeathRecipient = deathRecipient;
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
        public IBinder.DeathRecipient getDeathRecipient() {
            return mDeathRecipient;
        }
    }

    private final class BinderDeathRecipient implements IBinder.DeathRecipient {
        private final IBinder mDeviceToken;

        BinderDeathRecipient(IBinder deviceToken) {
            mDeviceToken = deviceToken;
        }

        @Override
        public void binderDied() {
            // All callers are expected to call {@link VirtualDevice#unregisterSensor} before
            // quitting, which removes this death recipient. If this is invoked, the remote end
            // died, or they disposed of the object without properly unregistering.
            Slog.e(TAG, "Virtual sensor controller binder died");
            unregisterSensor(mDeviceToken);
        }
    }

    /** An internal exception that is thrown to indicate an error when opening a virtual sensor. */
    private static class SensorCreationException extends Exception {
        SensorCreationException(String message) {
            super(message);
        }
        SensorCreationException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
