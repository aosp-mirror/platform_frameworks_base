/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.power;

import android.content.Context;
import android.hardware.thermal.V1_0.ThermalStatus;
import android.hardware.thermal.V1_0.ThermalStatusCode;
import android.hardware.thermal.V1_1.IThermalCallback;
import android.hardware.thermal.V2_0.IThermalChangedCallback;
import android.hardware.thermal.V2_0.ThrottlingSeverity;
import android.os.Binder;
import android.os.HwBinder;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * This is a system service that listens to HAL thermal events and dispatch those to listeners.
 * <p>The service will also trigger actions based on severity of the throttling status.</p>
 *
 * @hide
 */
public class ThermalManagerService extends SystemService {
    private static final String TAG = ThermalManagerService.class.getSimpleName();

    /** Registered observers of the thermal changed events. Cookie is used to store type */
    @GuardedBy("mLock")
    private final RemoteCallbackList<IThermalEventListener> mThermalEventListeners =
            new RemoteCallbackList<>();

    /** Lock to protect HAL handles and listen list. */
    private final Object mLock = new Object();

    /** Newly registered callback. */
    @GuardedBy("mLock")
    private IThermalEventListener mNewListenerCallback = null;

    /** Newly registered callback type, null means not filter type. */
    @GuardedBy("mLock")
    private Integer mNewListenerType = null;

    /** Local PMS handle. */
    private final PowerManager mPowerManager;

    /** Proxy object for the Thermal HAL 2.0 service. */
    @GuardedBy("mLock")
    private android.hardware.thermal.V2_0.IThermal mThermalHal20 = null;

    /** Proxy object for the Thermal HAL 1.1 service. */
    @GuardedBy("mLock")
    private android.hardware.thermal.V1_1.IThermal mThermalHal11 = null;

    /** Cookie for matching the right end point. */
    private static final int THERMAL_HAL_DEATH_COOKIE = 5612;

    /** HWbinder callback for Thermal HAL 2.0. */
    private final IThermalChangedCallback.Stub mThermalCallback20 =
            new IThermalChangedCallback.Stub() {
                @Override
                public void notifyThrottling(
                        android.hardware.thermal.V2_0.Temperature temperature) {
                    android.os.Temperature thermalSvcTemp = new android.os.Temperature(
                            temperature.value, temperature.type, temperature.name,
                            temperature.throttlingStatus);
                    final long token = Binder.clearCallingIdentity();
                    try {
                        notifyThrottlingImpl(thermalSvcTemp);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            };

    /** HWbinder callback for Thermal HAL 1.1. */
    private final IThermalCallback.Stub mThermalCallback11 =
            new IThermalCallback.Stub() {
                @Override
                public void notifyThrottling(boolean isThrottling,
                        android.hardware.thermal.V1_0.Temperature temperature) {
                    android.os.Temperature thermalSvcTemp = new android.os.Temperature(
                            temperature.currentValue, temperature.type, temperature.name,
                            isThrottling ? ThrottlingSeverity.SEVERE : ThrottlingSeverity.NONE);
                    final long token = Binder.clearCallingIdentity();
                    try {
                        notifyThrottlingImpl(thermalSvcTemp);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            };

    public ThermalManagerService(Context context) {
        super(context);
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    private void setNewListener(IThermalEventListener listener, Integer type) {
        synchronized (mLock) {
            mNewListenerCallback = listener;
            mNewListenerType = type;
        }
    }

    private void clearNewListener() {
        synchronized (mLock) {
            mNewListenerCallback = null;
            mNewListenerType = null;
        }
    }

    private final IThermalService.Stub mService = new IThermalService.Stub() {
        @Override
        public void registerThermalEventListener(IThermalEventListener listener) {
            synchronized (mLock) {
                mThermalEventListeners.register(listener, null);
                // Notify its callback after new client registered.
                setNewListener(listener, null);
                long token = Binder.clearCallingIdentity();
                try {
                    notifyCurrentTemperaturesLocked();
                } finally {
                    Binder.restoreCallingIdentity(token);
                    clearNewListener();
                }
            }
        }

        @Override
        public void registerThermalEventListenerWithType(IThermalEventListener listener, int type) {
            synchronized (mLock) {
                mThermalEventListeners.register(listener, new Integer(type));
                setNewListener(listener, new Integer(type));
                // Notify its callback after new client registered.
                long token = Binder.clearCallingIdentity();
                try {
                    notifyCurrentTemperaturesLocked();
                } finally {
                    Binder.restoreCallingIdentity(token);
                    clearNewListener();
                }
            }
        }

        @Override
        public void unregisterThermalEventListener(IThermalEventListener listener) {
            synchronized (mLock) {
                long token = Binder.clearCallingIdentity();
                try {
                    mThermalEventListeners.unregister(listener);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public List<android.os.Temperature> getCurrentTemperatures() {
            List<android.os.Temperature> ret;
            long token = Binder.clearCallingIdentity();
            try {
                ret = getCurrentTemperaturesInternal(false, 0 /* not used */);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return ret;
        }

        @Override
        public List<android.os.Temperature> getCurrentTemperaturesWithType(int type) {
            List<android.os.Temperature> ret;
            long token = Binder.clearCallingIdentity();
            try {
                ret = getCurrentTemperaturesInternal(true, type);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return ret;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;
            pw.println("ThermalEventListeners dump:");
            synchronized (mLock) {
                mThermalEventListeners.dump(pw, "\t");
                pw.println("ThermalHAL 1.1 connected: " + (mThermalHal11 != null ? "yes" : "no"));
                pw.println("ThermalHAL 2.0 connected: " + (mThermalHal20 != null ? "yes" : "no"));
            }
        }
    };

    private List<android.os.Temperature> getCurrentTemperaturesInternal(boolean shouldFilter,
            int type) {
        List<android.os.Temperature> ret = new ArrayList<>();
        synchronized (mLock) {
            if (mThermalHal20 == null) {
                return ret;
            }
            try {
                mThermalHal20.getCurrentTemperatures(shouldFilter, type,
                        (ThermalStatus status,
                                ArrayList<android.hardware.thermal.V2_0.Temperature>
                                        temperatures) -> {
                            if (ThermalStatusCode.SUCCESS == status.code) {
                                for (android.hardware.thermal.V2_0.Temperature
                                        temperature : temperatures) {
                                    ret.add(new android.os.Temperature(
                                            temperature.value, temperature.type, temperature.name,
                                            temperature.throttlingStatus));
                                }
                            } else {
                                Slog.e(TAG,
                                        "Couldn't get temperatures because of HAL error: "
                                                + status.debugMessage);
                            }

                        });
            } catch (RemoteException e) {
                Slog.e(TAG, "Couldn't getCurrentTemperatures, reconnecting...", e);
                connectToHalLocked();
                // Post to listeners after reconnect to HAL.
                notifyCurrentTemperaturesLocked();
            }
        }
        return ret;
    }

    private void notifyListener(android.os.Temperature temperature, IThermalEventListener listener,
            Integer type) {
        // Skip if listener registered with a different type
        if (type != null && type != temperature.getType()) {
            return;
        }
        final boolean thermalCallbackQueued = FgThread.getHandler().post(() -> {
            try {
                listener.notifyThrottling(temperature);
            } catch (RemoteException | RuntimeException e) {
                Slog.e(TAG, "Thermal callback failed to call", e);
            }
        });
        if (!thermalCallbackQueued) {
            Slog.e(TAG, "Thermal callback failed to queue");
        }
    }

    private void notifyThrottlingImpl(android.os.Temperature temperature) {
        synchronized (mLock) {
            // Thermal Shutdown for Skin temperature
            if (temperature.getStatus() == android.os.Temperature.THROTTLING_SHUTDOWN
                    && temperature.getType() == android.os.Temperature.TYPE_SKIN) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mPowerManager.shutdown(false, PowerManager.SHUTDOWN_THERMAL_STATE, false);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }

            if (mNewListenerCallback != null) {
                // Only notify current newly added callback.
                notifyListener(temperature, mNewListenerCallback, mNewListenerType);
            } else {
                final int length = mThermalEventListeners.beginBroadcast();
                try {
                    for (int i = 0; i < length; i++) {
                        final IThermalEventListener listener =
                                mThermalEventListeners.getBroadcastItem(i);
                        final Integer type = (Integer) mThermalEventListeners.getBroadcastCookie(i);
                        notifyListener(temperature, listener, type);
                    }
                } finally {
                    mThermalEventListeners.finishBroadcast();
                }
            }
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.THERMAL_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
            onActivityManagerReady();
        }
    }

    private void notifyCurrentTemperaturesCallbackLocked(ThermalStatus status,
            ArrayList<android.hardware.thermal.V2_0.Temperature> temperatures) {
        if (ThermalStatusCode.SUCCESS != status.code) {
            Slog.e(TAG, "Couldn't get temperatures because of HAL error: "
                    + status.debugMessage);
            return;
        }
        for (android.hardware.thermal.V2_0.Temperature temperature : temperatures) {
            android.os.Temperature thermal_svc_temp =
                    new android.os.Temperature(
                            temperature.value, temperature.type,
                            temperature.name,
                            temperature.throttlingStatus);
            notifyThrottlingImpl(thermal_svc_temp);
        }
    }

    private void notifyCurrentTemperaturesLocked() {
        if (mThermalHal20 == null) {
            return;
        }
        try {
            mThermalHal20.getCurrentTemperatures(false, 0,
                    this::notifyCurrentTemperaturesCallbackLocked);
        } catch (RemoteException e) {
            Slog.e(TAG, "Couldn't get temperatures, reconnecting...", e);
            connectToHalLocked();
        }
    }

    private void onActivityManagerReady() {
        synchronized (mLock) {
            connectToHalLocked();
            // Post to listeners after connect to HAL.
            notifyCurrentTemperaturesLocked();
        }
    }

    final class DeathRecipient implements HwBinder.DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
            if (cookie == THERMAL_HAL_DEATH_COOKIE) {
                Slog.e(TAG, "Thermal HAL service died cookie: " + cookie);
                synchronized (mLock) {
                    connectToHalLocked();
                    // Post to listeners after reconnect to HAL.
                    notifyCurrentTemperaturesLocked();
                }
            }
        }
    }

    private void connectToHalLocked() {
        try {
            mThermalHal20 = android.hardware.thermal.V2_0.IThermal.getService();
            mThermalHal20.linkToDeath(new DeathRecipient(), THERMAL_HAL_DEATH_COOKIE);
            mThermalHal20.registerThermalChangedCallback(mThermalCallback20, false,
                    0 /* not used */);
        } catch (NoSuchElementException | RemoteException e) {
            Slog.e(TAG, "Thermal HAL 2.0 service not connected, trying 1.1.");
            mThermalHal20 = null;
            try {
                mThermalHal11 = android.hardware.thermal.V1_1.IThermal.getService();
                mThermalHal11.linkToDeath(new DeathRecipient(), THERMAL_HAL_DEATH_COOKIE);
                mThermalHal11.registerThermalCallback(mThermalCallback11);
            } catch (NoSuchElementException | RemoteException e2) {
                Slog.e(TAG,
                        "Thermal HAL 1.1 service not connected, no thermal call back "
                                + "will be called.");
                mThermalHal11 = null;
            }
        }
    }

}
