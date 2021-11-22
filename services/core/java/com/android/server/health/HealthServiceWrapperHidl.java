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

package com.android.server.health;

import static android.hardware.health.Translate.h2aTranslate;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.health.HealthInfo;
import android.hardware.health.V2_0.IHealth;
import android.hardware.health.V2_0.Result;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.BatteryManager;
import android.os.BatteryProperty;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.Trace;
import android.util.MutableInt;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implement {@link HealthServiceWrapper} backed by the HIDL HAL.
 *
 * @hide
 */
final class HealthServiceWrapperHidl extends HealthServiceWrapper {
    private static final String TAG = "HealthServiceWrapperHidl";
    public static final String INSTANCE_VENDOR = "default";

    private final IServiceNotification mNotification = new Notification();
    private final HandlerThread mHandlerThread = new HandlerThread("HealthServiceHwbinder");
    // These variables are fixed after init.
    private Callback mCallback;
    private IHealthSupplier mHealthSupplier;
    private String mInstanceName;

    // Last IHealth service received.
    private final AtomicReference<IHealth> mLastService = new AtomicReference<>();

    private static void traceBegin(String name) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, name);
    }

    private static void traceEnd() {
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    @Override
    public int getProperty(int id, final BatteryProperty prop) throws RemoteException {
        traceBegin("HealthGetProperty");
        try {
            IHealth service = mLastService.get();
            if (service == null) throw new RemoteException("no health service");
            final MutableInt outResult = new MutableInt(Result.NOT_SUPPORTED);
            switch (id) {
                case BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER:
                    service.getChargeCounter(
                            (int result, int value) -> {
                                outResult.value = result;
                                if (result == Result.SUCCESS) prop.setLong(value);
                            });
                    break;
                case BatteryManager.BATTERY_PROPERTY_CURRENT_NOW:
                    service.getCurrentNow(
                            (int result, int value) -> {
                                outResult.value = result;
                                if (result == Result.SUCCESS) prop.setLong(value);
                            });
                    break;
                case BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE:
                    service.getCurrentAverage(
                            (int result, int value) -> {
                                outResult.value = result;
                                if (result == Result.SUCCESS) prop.setLong(value);
                            });
                    break;
                case BatteryManager.BATTERY_PROPERTY_CAPACITY:
                    service.getCapacity(
                            (int result, int value) -> {
                                outResult.value = result;
                                if (result == Result.SUCCESS) prop.setLong(value);
                            });
                    break;
                case BatteryManager.BATTERY_PROPERTY_STATUS:
                    service.getChargeStatus(
                            (int result, int value) -> {
                                outResult.value = result;
                                if (result == Result.SUCCESS) prop.setLong(value);
                            });
                    break;
                case BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER:
                    service.getEnergyCounter(
                            (int result, long value) -> {
                                outResult.value = result;
                                if (result == Result.SUCCESS) prop.setLong(value);
                            });
                    break;
            }
            return outResult.value;
        } finally {
            traceEnd();
        }
    }

    @Override
    public void scheduleUpdate() throws RemoteException {
        getHandlerThread()
                .getThreadHandler()
                .post(
                        () -> {
                            traceBegin("HealthScheduleUpdate");
                            try {
                                IHealth service = mLastService.get();
                                if (service == null) {
                                    Slog.e(TAG, "no health service");
                                    return;
                                }
                                service.update();
                            } catch (RemoteException ex) {
                                Slog.e(TAG, "Cannot call update on health HAL", ex);
                            } finally {
                                traceEnd();
                            }
                        });
    }

    private static class Mutable<T> {
        public T value;
    }

    @Override
    public HealthInfo getHealthInfo() throws RemoteException {
        IHealth service = mLastService.get();
        if (service == null) return null;
        final Mutable<HealthInfo> ret = new Mutable<>();
        service.getHealthInfo(
                (result, value) -> {
                    if (result == Result.SUCCESS) {
                        ret.value = h2aTranslate(value.legacy);
                    }
                });
        return ret.value;
    }

    /**
     * Start monitoring registration of new IHealth services. Only instance {@link #INSTANCE_VENDOR}
     * and in device / framework manifest are used. This function should only be called once.
     *
     * <p>mCallback.onRegistration() is called synchronously (aka in init thread) before this method
     * returns if callback is not null.
     *
     * @throws RemoteException transaction error when talking to IServiceManager
     * @throws NoSuchElementException if one of the following cases: - No service manager; - {@link
     *     #INSTANCE_VENDOR} is not in manifests (i.e. not available on this device), or none of
     *     these instances are available to current process.
     * @throws NullPointerException when supplier is null
     */
    @VisibleForTesting
    HealthServiceWrapperHidl(
            @Nullable Callback callback,
            @NonNull IServiceManagerSupplier managerSupplier,
            @NonNull IHealthSupplier healthSupplier)
            throws RemoteException, NoSuchElementException, NullPointerException {
        if (managerSupplier == null || healthSupplier == null) {
            throw new NullPointerException();
        }
        mHealthSupplier = healthSupplier;

        // Initialize mLastService and call callback for the first time (in init thread)
        IHealth newService = null;
        traceBegin("HealthInitGetService_" + INSTANCE_VENDOR);
        try {
            newService = healthSupplier.get(INSTANCE_VENDOR);
        } catch (NoSuchElementException ex) {
            /* ignored, handled below */
        } finally {
            traceEnd();
        }
        if (newService != null) {
            mInstanceName = INSTANCE_VENDOR;
            mLastService.set(newService);
        }

        if (mInstanceName == null || newService == null) {
            throw new NoSuchElementException(
                    String.format(
                            "IHealth service instance %s isn't available. Perhaps no permission?",
                            INSTANCE_VENDOR));
        }

        if (callback != null) {
            mCallback = callback;
            mCallback.onRegistration(null, newService, mInstanceName);
        }

        // Register for future service registrations
        traceBegin("HealthInitRegisterNotification");
        mHandlerThread.start();
        try {
            managerSupplier
                    .get()
                    .registerForNotifications(IHealth.kInterfaceName, mInstanceName, mNotification);
        } finally {
            traceEnd();
        }
        Slog.i(TAG, "health: HealthServiceWrapper listening to instance " + mInstanceName);
    }

    @VisibleForTesting
    public HandlerThread getHandlerThread() {
        return mHandlerThread;
    }

    /** Service registration callback. */
    interface Callback {
        /**
         * This function is invoked asynchronously when a new and related IServiceNotification is
         * received.
         *
         * @param service the recently retrieved service from IServiceManager. Can be a dead service
         *     before service notification of a new service is delivered. Implementation must handle
         *     cases for {@link RemoteException}s when calling into service.
         * @param instance instance name.
         */
        void onRegistration(IHealth oldService, IHealth newService, String instance);
    }

    /**
     * Supplier of services. Must not return null; throw {@link NoSuchElementException} if a service
     * is not available.
     */
    interface IServiceManagerSupplier {
        default IServiceManager get() throws NoSuchElementException, RemoteException {
            return IServiceManager.getService();
        }
    }

    /**
     * Supplier of services. Must not return null; throw {@link NoSuchElementException} if a service
     * is not available.
     */
    interface IHealthSupplier {
        default IHealth get(String name) throws NoSuchElementException, RemoteException {
            return IHealth.getService(name, true /* retry */);
        }
    }

    private class Notification extends IServiceNotification.Stub {
        @Override
        public final void onRegistration(
                String interfaceName, String instanceName, boolean preexisting) {
            if (!IHealth.kInterfaceName.equals(interfaceName)) return;
            if (!mInstanceName.equals(instanceName)) return;

            // This runnable only runs on mHandlerThread and ordering is ensured, hence
            // no locking is needed inside the runnable.
            mHandlerThread
                    .getThreadHandler()
                    .post(
                            new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        IHealth newService = mHealthSupplier.get(mInstanceName);
                                        IHealth oldService = mLastService.getAndSet(newService);

                                        // preexisting may be inaccurate (race). Check for equality
                                        // here.
                                        if (Objects.equals(newService, oldService)) return;

                                        Slog.i(
                                                TAG,
                                                "health: new instance registered " + mInstanceName);
                                        // #init() may be called with null callback. Skip null
                                        // callbacks.
                                        if (mCallback == null) return;
                                        mCallback.onRegistration(
                                                oldService, newService, mInstanceName);
                                    } catch (NoSuchElementException | RemoteException ex) {
                                        Slog.e(
                                                TAG,
                                                "health: Cannot get instance '"
                                                        + mInstanceName
                                                        + "': "
                                                        + ex.getMessage()
                                                        + ". Perhaps no permission?");
                                    }
                                }
                            });
        }
    }
}
