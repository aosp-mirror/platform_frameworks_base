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

import static android.os.Flags.batteryPartStatusApi;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.health.BatteryHealthData;
import android.hardware.health.HealthInfo;
import android.hardware.health.IHealth;
import android.os.BatteryManager;
import android.os.BatteryProperty;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IServiceCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.Trace;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implement {@link HealthServiceWrapper} backed by the AIDL HAL.
 *
 * @hide
 */
class HealthServiceWrapperAidl extends HealthServiceWrapper {
    private static final String TAG = "HealthServiceWrapperAidl";
    @VisibleForTesting static final String SERVICE_NAME = IHealth.DESCRIPTOR + "/default";
    private final HandlerThread mHandlerThread = new HandlerThread("HealthServiceBinder");
    private final AtomicReference<IHealth> mLastService = new AtomicReference<>();
    private final IServiceCallback mServiceCallback = new ServiceCallback();
    private final HealthRegCallbackAidl mRegCallback;

    /** Stub interface into {@link ServiceManager} for testing. */
    interface ServiceManagerStub {
        default @Nullable IHealth waitForDeclaredService(@NonNull String name) {
            return IHealth.Stub.asInterface(ServiceManager.waitForDeclaredService(name));
        }

        default void registerForNotifications(
                @NonNull String name, @NonNull IServiceCallback callback) throws RemoteException {
            ServiceManager.registerForNotifications(name, callback);
        }
    }

    HealthServiceWrapperAidl(
            @Nullable HealthRegCallbackAidl regCallback, @NonNull ServiceManagerStub serviceManager)
            throws RemoteException, NoSuchElementException {

        traceBegin("HealthInitGetServiceAidl");
        IHealth newService;
        try {
            newService = serviceManager.waitForDeclaredService(SERVICE_NAME);
        } finally {
            traceEnd();
        }
        if (newService == null) {
            throw new NoSuchElementException(
                    "IHealth service instance isn't available. Perhaps no permission?");
        }
        mLastService.set(newService);
        mRegCallback = regCallback;
        if (mRegCallback != null) {
            mRegCallback.onRegistration(null /* oldService */, newService);
        }

        traceBegin("HealthInitRegisterNotificationAidl");
        mHandlerThread.start();
        try {
            serviceManager.registerForNotifications(SERVICE_NAME, mServiceCallback);
        } finally {
            traceEnd();
        }
        Slog.i(TAG, "health: HealthServiceWrapper listening to AIDL HAL");
    }

    @Override
    @VisibleForTesting
    public HandlerThread getHandlerThread() {
        return mHandlerThread;
    }

    @Override
    public int getProperty(int id, BatteryProperty prop) throws RemoteException {
        traceBegin("HealthGetPropertyAidl");
        try {
            return getPropertyInternal(id, prop);
        } finally {
            traceEnd();
        }
    }

    private int getPropertyInternal(int id, BatteryProperty prop) throws RemoteException {
        IHealth service = mLastService.get();
        if (service == null) throw new RemoteException("no health service");
        BatteryHealthData healthData;
        try {
            switch (id) {
                case BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER:
                    prop.setLong(service.getChargeCounterUah());
                    break;
                case BatteryManager.BATTERY_PROPERTY_CURRENT_NOW:
                    prop.setLong(service.getCurrentNowMicroamps());
                    break;
                case BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE:
                    prop.setLong(service.getCurrentAverageMicroamps());
                    break;
                case BatteryManager.BATTERY_PROPERTY_CAPACITY:
                    prop.setLong(service.getCapacity());
                    break;
                case BatteryManager.BATTERY_PROPERTY_STATUS:
                    prop.setLong(service.getChargeStatus());
                    break;
                case BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER:
                    prop.setLong(service.getEnergyCounterNwh());
                    break;
                case BatteryManager.BATTERY_PROPERTY_MANUFACTURING_DATE:
                    healthData = service.getBatteryHealthData();
                    prop.setLong(healthData.batteryManufacturingDateSeconds);
                    break;
                case BatteryManager.BATTERY_PROPERTY_FIRST_USAGE_DATE:
                    healthData = service.getBatteryHealthData();
                    prop.setLong(healthData.batteryFirstUsageSeconds);
                    break;
                case BatteryManager.BATTERY_PROPERTY_CHARGING_POLICY:
                    prop.setLong(service.getChargingPolicy());
                    break;
                case BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH:
                    healthData = service.getBatteryHealthData();
                    prop.setLong(healthData.batteryStateOfHealth);
                    break;
                case BatteryManager.BATTERY_PROPERTY_SERIAL_NUMBER:
                    if (batteryPartStatusApi()) {
                        healthData = service.getBatteryHealthData();
                        prop.setString(healthData.batterySerialNumber);
                    }
                    break;
                case BatteryManager.BATTERY_PROPERTY_PART_STATUS:
                    if (batteryPartStatusApi()) {
                        healthData = service.getBatteryHealthData();
                        prop.setLong(healthData.batteryPartStatus);
                    }
                    break;
            }
        } catch (UnsupportedOperationException e) {
            // Leave prop untouched.
            return -1;
        } catch (ServiceSpecificException e) {
            // Leave prop untouched.
            return -2;
        }
        // throws RemoteException as-is. BatteryManager wraps it into a RuntimeException
        // and throw it to apps.

        // If no error, return 0.
        return 0;
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
                            } catch (RemoteException | ServiceSpecificException ex) {
                                Slog.e(TAG, "Cannot call update on health AIDL HAL", ex);
                            } finally {
                                traceEnd();
                            }
                        });
    }

    @Override
    public HealthInfo getHealthInfo() throws RemoteException {
        IHealth service = mLastService.get();
        if (service == null) return null;
        try {
            return service.getHealthInfo();
        } catch (UnsupportedOperationException | ServiceSpecificException ex) {
            return null;
        }
    }

    private static void traceBegin(String name) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, name);
    }

    private static void traceEnd() {
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    private class ServiceCallback extends IServiceCallback.Stub {
        @Override
        public void onRegistration(String name, @NonNull final IBinder newBinder)
                throws RemoteException {
            if (!SERVICE_NAME.equals(name)) return;
            // This runnable only runs on mHandlerThread and ordering is ensured, hence
            // no locking is needed inside the runnable.
            getHandlerThread()
                    .getThreadHandler()
                    .post(
                            () -> {
                                IHealth newService =
                                        IHealth.Stub.asInterface(Binder.allowBlocking(newBinder));
                                IHealth oldService = mLastService.getAndSet(newService);
                                IBinder oldBinder =
                                        oldService != null ? oldService.asBinder() : null;
                                if (Objects.equals(newBinder, oldBinder)) return;

                                Slog.i(TAG, "New health AIDL HAL service registered");
                                if (mRegCallback != null) {
                                    mRegCallback.onRegistration(oldService, newService);
                                }
                            });
        }
    }
}
