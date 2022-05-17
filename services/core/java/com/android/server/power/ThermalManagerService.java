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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.thermal.V1_0.ThermalStatus;
import android.hardware.thermal.V1_0.ThermalStatusCode;
import android.hardware.thermal.V1_1.IThermalCallback;
import android.hardware.thermal.V2_0.IThermalChangedCallback;
import android.hardware.thermal.V2_0.TemperatureThreshold;
import android.hardware.thermal.V2_0.ThrottlingSeverity;
import android.os.Binder;
import android.os.CoolingDevice;
import android.os.Handler;
import android.os.HwBinder;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.IThermalStatusListener;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Temperature;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This is a system service that listens to HAL thermal events and dispatch those to listeners.
 * <p>The service will also trigger actions based on severity of the throttling status.</p>
 *
 * @hide
 */
public class ThermalManagerService extends SystemService {
    private static final String TAG = ThermalManagerService.class.getSimpleName();

    /** Lock to protect listen list. */
    private final Object mLock = new Object();

    /**
     * Registered observers of the thermal events. Cookie is used to store type as Integer, null
     * means no filter.
     */
    @GuardedBy("mLock")
    private final RemoteCallbackList<IThermalEventListener> mThermalEventListeners =
            new RemoteCallbackList<>();

    /** Registered observers of the thermal status. */
    @GuardedBy("mLock")
    private final RemoteCallbackList<IThermalStatusListener> mThermalStatusListeners =
            new RemoteCallbackList<>();

    /** Current thermal status */
    @GuardedBy("mLock")
    private int mStatus;

    /** If override status takes effect*/
    @GuardedBy("mLock")
    private boolean mIsStatusOverride;

    /** Current thermal map, key as name */
    @GuardedBy("mLock")
    private ArrayMap<String, Temperature> mTemperatureMap = new ArrayMap<>();

    /** HAL wrapper. */
    private ThermalHalWrapper mHalWrapper;

    /** Hal ready. */
    private final AtomicBoolean mHalReady = new AtomicBoolean();

    /** Watches temperatures to forecast when throttling will occur */
    @VisibleForTesting
    final TemperatureWatcher mTemperatureWatcher = new TemperatureWatcher();

    public ThermalManagerService(Context context) {
        this(context, null);
    }

    @VisibleForTesting
    ThermalManagerService(Context context, @Nullable ThermalHalWrapper halWrapper) {
        super(context);
        mHalWrapper = halWrapper;
        mStatus = Temperature.THROTTLING_NONE;
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

    private void onActivityManagerReady() {
        synchronized (mLock) {
            // Connect to HAL and post to listeners.
            boolean halConnected = (mHalWrapper != null);
            if (!halConnected) {
                mHalWrapper = new ThermalHal20Wrapper();
                halConnected = mHalWrapper.connectToHal();
            }
            if (!halConnected) {
                mHalWrapper = new ThermalHal11Wrapper();
                halConnected = mHalWrapper.connectToHal();
            }
            if (!halConnected) {
                mHalWrapper = new ThermalHal10Wrapper();
                halConnected = mHalWrapper.connectToHal();
            }
            mHalWrapper.setCallback(this::onTemperatureChangedCallback);
            if (!halConnected) {
                Slog.w(TAG, "No Thermal HAL service on this device");
                return;
            }
            List<Temperature> temperatures = mHalWrapper.getCurrentTemperatures(false,
                    0);
            final int count = temperatures.size();
            if (count == 0) {
                Slog.w(TAG, "Thermal HAL reported invalid data, abort connection");
            }
            for (int i = 0; i < count; i++) {
                onTemperatureChanged(temperatures.get(i), false);
            }
            onTemperatureMapChangedLocked();
            mTemperatureWatcher.updateSevereThresholds();
            mHalReady.set(true);
        }
    }

    private void postStatusListener(IThermalStatusListener listener) {
        final boolean thermalCallbackQueued = FgThread.getHandler().post(() -> {
            try {
                listener.onStatusChange(mStatus);
            } catch (RemoteException | RuntimeException e) {
                Slog.e(TAG, "Thermal callback failed to call", e);
            }
        });
        if (!thermalCallbackQueued) {
            Slog.e(TAG, "Thermal callback failed to queue");
        }
    }

    private void notifyStatusListenersLocked() {
        final int length = mThermalStatusListeners.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                final IThermalStatusListener listener =
                        mThermalStatusListeners.getBroadcastItem(i);
                postStatusListener(listener);
            }
        } finally {
            mThermalStatusListeners.finishBroadcast();
        }
    }

    private void onTemperatureMapChangedLocked() {
        int newStatus = Temperature.THROTTLING_NONE;
        final int count = mTemperatureMap.size();
        for (int i = 0; i < count; i++) {
            Temperature t = mTemperatureMap.valueAt(i);
            if (t.getType() == Temperature.TYPE_SKIN && t.getStatus() >= newStatus) {
                newStatus = t.getStatus();
            }
        }
        // Do not update if override from shell
        if (!mIsStatusOverride) {
            setStatusLocked(newStatus);
        }
    }

    private void setStatusLocked(int newStatus) {
        if (newStatus != mStatus) {
            mStatus = newStatus;
            notifyStatusListenersLocked();
        }
    }

    private void postEventListenerCurrentTemperatures(IThermalEventListener listener,
            @Nullable Integer type) {
        synchronized (mLock) {
            final int count = mTemperatureMap.size();
            for (int i = 0; i < count; i++) {
                postEventListener(mTemperatureMap.valueAt(i), listener,
                        type);
            }
        }
    }

    private void postEventListener(Temperature temperature,
            IThermalEventListener listener,
            @Nullable Integer type) {
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

    private void notifyEventListenersLocked(Temperature temperature) {
        final int length = mThermalEventListeners.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                final IThermalEventListener listener =
                        mThermalEventListeners.getBroadcastItem(i);
                final Integer type =
                        (Integer) mThermalEventListeners.getBroadcastCookie(i);
                postEventListener(temperature, listener, type);
            }
        } finally {
            mThermalEventListeners.finishBroadcast();
        }
        EventLog.writeEvent(EventLogTags.THERMAL_CHANGED, temperature.getName(),
                temperature.getType(), temperature.getValue(), temperature.getStatus(), mStatus);
    }

    private void shutdownIfNeeded(Temperature temperature) {
        if (temperature.getStatus() != Temperature.THROTTLING_SHUTDOWN) {
            return;
        }
        final PowerManager powerManager = getContext().getSystemService(PowerManager.class);
        switch (temperature.getType()) {
            case Temperature.TYPE_CPU:
                // Fall through
            case Temperature.TYPE_GPU:
                // Fall through
            case Temperature.TYPE_NPU:
                // Fall through
            case Temperature.TYPE_SKIN:
                powerManager.shutdown(false, PowerManager.SHUTDOWN_THERMAL_STATE, false);
                break;
            case Temperature.TYPE_BATTERY:
                powerManager.shutdown(false, PowerManager.SHUTDOWN_BATTERY_THERMAL_STATE, false);
                break;
        }
    }

    private void onTemperatureChanged(Temperature temperature, boolean sendStatus) {
        shutdownIfNeeded(temperature);
        synchronized (mLock) {
            Temperature old = mTemperatureMap.put(temperature.getName(), temperature);
            if (old == null || old.getStatus() != temperature.getStatus()) {
                notifyEventListenersLocked(temperature);
            }
            if (sendStatus) {
                onTemperatureMapChangedLocked();
            }
        }
    }

    /* HwBinder callback **/
    private void onTemperatureChangedCallback(Temperature temperature) {
        final long token = Binder.clearCallingIdentity();
        try {
            onTemperatureChanged(temperature, true);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @VisibleForTesting
    final IThermalService.Stub mService = new IThermalService.Stub() {
        @Override
        public boolean registerThermalEventListener(IThermalEventListener listener) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);
            synchronized (mLock) {
                final long token = Binder.clearCallingIdentity();
                try {
                    if (!mThermalEventListeners.register(listener, null)) {
                        return false;
                    }
                    // Notify its callback after new client registered.
                    postEventListenerCurrentTemperatures(listener, null);
                    return true;
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public boolean registerThermalEventListenerWithType(IThermalEventListener listener,
                int type) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);
            synchronized (mLock) {
                final long token = Binder.clearCallingIdentity();
                try {
                    if (!mThermalEventListeners.register(listener, new Integer(type))) {
                        return false;
                    }
                    // Notify its callback after new client registered.
                    postEventListenerCurrentTemperatures(listener, new Integer(type));
                    return true;
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public boolean unregisterThermalEventListener(IThermalEventListener listener) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);
            synchronized (mLock) {
                final long token = Binder.clearCallingIdentity();
                try {
                    return mThermalEventListeners.unregister(listener);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public Temperature[] getCurrentTemperatures() {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);
            final long token = Binder.clearCallingIdentity();
            try {
                if (!mHalReady.get()) {
                    return new Temperature[0];
                }
                final List<Temperature> curr = mHalWrapper.getCurrentTemperatures(
                        false, 0 /* not used */);
                return curr.toArray(new Temperature[curr.size()]);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public Temperature[] getCurrentTemperaturesWithType(int type) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);
            final long token = Binder.clearCallingIdentity();
            try {
                if (!mHalReady.get()) {
                    return new Temperature[0];
                }
                final List<Temperature> curr = mHalWrapper.getCurrentTemperatures(true, type);
                return curr.toArray(new Temperature[curr.size()]);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean registerThermalStatusListener(IThermalStatusListener listener) {
            synchronized (mLock) {
                // Notify its callback after new client registered.
                final long token = Binder.clearCallingIdentity();
                try {
                    if (!mThermalStatusListeners.register(listener)) {
                        return false;
                    }
                    // Notify its callback after new client registered.
                    postStatusListener(listener);
                    return true;
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public boolean unregisterThermalStatusListener(IThermalStatusListener listener) {
            synchronized (mLock) {
                final long token = Binder.clearCallingIdentity();
                try {
                    return mThermalStatusListeners.unregister(listener);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public int getCurrentThermalStatus() {
            synchronized (mLock) {
                final long token = Binder.clearCallingIdentity();
                try {
                    return mStatus;
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public CoolingDevice[] getCurrentCoolingDevices() {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);
            final long token = Binder.clearCallingIdentity();
            try {
                if (!mHalReady.get()) {
                    return new CoolingDevice[0];
                }
                final List<CoolingDevice> devList = mHalWrapper.getCurrentCoolingDevices(
                        false, 0);
                return devList.toArray(new CoolingDevice[devList.size()]);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public CoolingDevice[] getCurrentCoolingDevicesWithType(int type) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);
            final long token = Binder.clearCallingIdentity();
            try {
                if (!mHalReady.get()) {
                    return new CoolingDevice[0];
                }
                final List<CoolingDevice> devList = mHalWrapper.getCurrentCoolingDevices(
                        true, type);
                return devList.toArray(new CoolingDevice[devList.size()]);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public float getThermalHeadroom(int forecastSeconds) {
            if (!mHalReady.get()) {
                return Float.NaN;
            }

            return mTemperatureWatcher.getForecast(forecastSeconds);
        }

        private void dumpItemsLocked(PrintWriter pw, String prefix,
                Collection<?> items) {
            for (Iterator iterator = items.iterator(); iterator.hasNext();) {
                pw.println(prefix + iterator.next().toString());
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    pw.println("IsStatusOverride: " + mIsStatusOverride);
                    pw.println("ThermalEventListeners:");
                    mThermalEventListeners.dump(pw, "\t");
                    pw.println("ThermalStatusListeners:");
                    mThermalStatusListeners.dump(pw, "\t");
                    pw.println("Thermal Status: " + mStatus);
                    pw.println("Cached temperatures:");
                    dumpItemsLocked(pw, "\t", mTemperatureMap.values());
                    pw.println("HAL Ready: " + mHalReady.get());
                    if (mHalReady.get()) {
                        pw.println("HAL connection:");
                        mHalWrapper.dump(pw, "\t");
                        pw.println("Current temperatures from HAL:");
                        dumpItemsLocked(pw, "\t",
                                mHalWrapper.getCurrentTemperatures(false, 0));
                        pw.println("Current cooling devices from HAL:");
                        dumpItemsLocked(pw, "\t",
                                mHalWrapper.getCurrentCoolingDevices(false, 0));
                        pw.println("Temperature static thresholds from HAL:");
                        dumpItemsLocked(pw, "\t",
                                mHalWrapper.getTemperatureThresholds(false, 0));
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private boolean isCallerShell() {
            final int callingUid = Binder.getCallingUid();
            return callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID;
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            if (!isCallerShell()) {
                Slog.w(TAG, "Only shell is allowed to call thermalservice shell commands");
                return;
            }
            (new ThermalShellCommand()).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }

    };

    class ThermalShellCommand extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            switch(cmd != null ? cmd : "") {
                case "override-status":
                    return runOverrideStatus();
                case "reset":
                    return runReset();
                default:
                    return handleDefaultCommands(cmd);
            }
        }

        private int runReset() {
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    mIsStatusOverride = false;
                    onTemperatureMapChangedLocked();
                    return 0;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private int runOverrideStatus() {
            final long token = Binder.clearCallingIdentity();
            try {
                final PrintWriter pw = getOutPrintWriter();
                int status;
                try {
                    status = Integer.parseInt(getNextArgRequired());
                } catch (RuntimeException ex) {
                    pw.println("Error: " + ex.toString());
                    return -1;
                }
                if (!Temperature.isValidStatus(status)) {
                    pw.println("Invalid status: " + status);
                    return -1;
                }
                synchronized (mLock) {
                    mIsStatusOverride = true;
                    setStatusLocked(status);
                }
                return 0;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("Thermal service (thermalservice) commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("");
            pw.println("  override-status STATUS");
            pw.println("    sets and locks the thermal status of the device to STATUS.");
            pw.println("    status code is defined in android.os.Temperature.");
            pw.println("  reset");
            pw.println("    unlocks the thermal status of the device.");
            pw.println();
        }
    }

    abstract static class ThermalHalWrapper {
        protected static final String TAG = ThermalHalWrapper.class.getSimpleName();

        /** Lock to protect HAL handle. */
        protected final Object mHalLock = new Object();

        @FunctionalInterface
        interface TemperatureChangedCallback {
            void onValues(Temperature temperature);
        }

        /** Temperature callback. */
        protected TemperatureChangedCallback mCallback;

        /** Cookie for matching the right end point. */
        protected static final int THERMAL_HAL_DEATH_COOKIE = 5612;

        @VisibleForTesting
        protected void setCallback(TemperatureChangedCallback cb) {
            mCallback = cb;
        }

        protected abstract List<Temperature> getCurrentTemperatures(boolean shouldFilter,
                int type);

        protected abstract List<CoolingDevice> getCurrentCoolingDevices(boolean shouldFilter,
                int type);

        @NonNull
        protected abstract List<TemperatureThreshold> getTemperatureThresholds(boolean shouldFilter,
                int type);

        protected abstract boolean connectToHal();

        protected abstract void dump(PrintWriter pw, String prefix);

        protected void resendCurrentTemperatures() {
            synchronized (mHalLock) {
                List<Temperature> temperatures = getCurrentTemperatures(false, 0);
                final int count = temperatures.size();
                for (int i = 0; i < count; i++) {
                    mCallback.onValues(temperatures.get(i));
                }
            }
        }

        final class DeathRecipient implements HwBinder.DeathRecipient {
            @Override
            public void serviceDied(long cookie) {
                if (cookie == THERMAL_HAL_DEATH_COOKIE) {
                    Slog.e(TAG, "Thermal HAL service died cookie: " + cookie);
                    synchronized (mHalLock) {
                        connectToHal();
                        // Post to listeners after reconnect to HAL.
                        resendCurrentTemperatures();
                    }
                }
            }
        }
    }


    static class ThermalHal10Wrapper extends ThermalHalWrapper {
        /** Proxy object for the Thermal HAL 1.0 service. */
        @GuardedBy("mHalLock")
        private android.hardware.thermal.V1_0.IThermal mThermalHal10 = null;

        @Override
        protected List<Temperature> getCurrentTemperatures(boolean shouldFilter,
                int type) {
            synchronized (mHalLock) {
                List<Temperature> ret = new ArrayList<>();
                if (mThermalHal10 == null) {
                    return ret;
                }
                try {
                    mThermalHal10.getTemperatures(
                            (ThermalStatus status,
                                    ArrayList<android.hardware.thermal.V1_0.Temperature>
                                            temperatures) -> {
                                if (ThermalStatusCode.SUCCESS == status.code) {
                                    for (android.hardware.thermal.V1_0.Temperature
                                            temperature : temperatures) {
                                        if (shouldFilter && type != temperature.type) {
                                            continue;
                                        }
                                        // Thermal HAL 1.0 doesn't report current throttling status
                                        ret.add(new Temperature(
                                                temperature.currentValue, temperature.type,
                                                temperature.name,
                                                Temperature.THROTTLING_NONE));
                                    }
                                } else {
                                    Slog.e(TAG,
                                            "Couldn't get temperatures because of HAL error: "
                                                    + status.debugMessage);
                                }

                            });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentTemperatures, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        @Override
        protected List<CoolingDevice> getCurrentCoolingDevices(boolean shouldFilter,
                int type) {
            synchronized (mHalLock) {
                List<CoolingDevice> ret = new ArrayList<>();
                if (mThermalHal10 == null) {
                    return ret;
                }
                try {
                    mThermalHal10.getCoolingDevices((status, coolingDevices) -> {
                        if (ThermalStatusCode.SUCCESS == status.code) {
                            for (android.hardware.thermal.V1_0.CoolingDevice
                                    coolingDevice : coolingDevices) {
                                if (shouldFilter && type != coolingDevice.type) {
                                    continue;
                                }
                                ret.add(new CoolingDevice(
                                        (long) coolingDevice.currentValue,
                                        coolingDevice.type,
                                        coolingDevice.name));
                            }
                        } else {
                            Slog.e(TAG,
                                    "Couldn't get cooling device because of HAL error: "
                                            + status.debugMessage);
                        }

                    });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentCoolingDevices, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        @Override
        protected List<TemperatureThreshold> getTemperatureThresholds(boolean shouldFilter,
                int type) {
            return new ArrayList<>();
        }

        @Override
        protected boolean connectToHal() {
            synchronized (mHalLock) {
                try {
                    mThermalHal10 = android.hardware.thermal.V1_0.IThermal.getService(true);
                    mThermalHal10.linkToDeath(new DeathRecipient(),
                            THERMAL_HAL_DEATH_COOKIE);
                    Slog.i(TAG,
                            "Thermal HAL 1.0 service connected, no thermal call back will be "
                                    + "called due to legacy API.");
                } catch (NoSuchElementException | RemoteException e) {
                    Slog.e(TAG,
                            "Thermal HAL 1.0 service not connected.");
                    mThermalHal10 = null;
                }
                return (mThermalHal10 != null);
            }
        }

        @Override
        protected void dump(PrintWriter pw, String prefix) {
            synchronized (mHalLock) {
                pw.print(prefix);
                pw.println("ThermalHAL 1.0 connected: " + (mThermalHal10 != null ? "yes"
                        : "no"));
            }
        }
    }

    static class ThermalHal11Wrapper extends ThermalHalWrapper {
        /** Proxy object for the Thermal HAL 1.1 service. */
        @GuardedBy("mHalLock")
        private android.hardware.thermal.V1_1.IThermal mThermalHal11 = null;

        /** HWbinder callback for Thermal HAL 1.1. */
        private final IThermalCallback.Stub mThermalCallback11 =
                new IThermalCallback.Stub() {
                    @Override
                    public void notifyThrottling(boolean isThrottling,
                            android.hardware.thermal.V1_0.Temperature temperature) {
                        Temperature thermalSvcTemp = new Temperature(
                                temperature.currentValue, temperature.type, temperature.name,
                                isThrottling ? ThrottlingSeverity.SEVERE
                                        : ThrottlingSeverity.NONE);
                        final long token = Binder.clearCallingIdentity();
                        try {
                            mCallback.onValues(thermalSvcTemp);
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                };

        @Override
        protected List<Temperature> getCurrentTemperatures(boolean shouldFilter,
                int type) {
            synchronized (mHalLock) {
                List<Temperature> ret = new ArrayList<>();
                if (mThermalHal11 == null) {
                    return ret;
                }
                try {
                    mThermalHal11.getTemperatures(
                            (ThermalStatus status,
                                    ArrayList<android.hardware.thermal.V1_0.Temperature>
                                            temperatures) -> {
                                if (ThermalStatusCode.SUCCESS == status.code) {
                                    for (android.hardware.thermal.V1_0.Temperature
                                            temperature : temperatures) {
                                        if (shouldFilter && type != temperature.type) {
                                            continue;
                                        }
                                        // Thermal HAL 1.1 doesn't report current throttling status
                                        ret.add(new Temperature(
                                                temperature.currentValue, temperature.type,
                                                temperature.name,
                                                Temperature.THROTTLING_NONE));
                                    }
                                } else {
                                    Slog.e(TAG,
                                            "Couldn't get temperatures because of HAL error: "
                                                    + status.debugMessage);
                                }

                            });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentTemperatures, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        @Override
        protected List<CoolingDevice> getCurrentCoolingDevices(boolean shouldFilter,
                int type) {
            synchronized (mHalLock) {
                List<CoolingDevice> ret = new ArrayList<>();
                if (mThermalHal11 == null) {
                    return ret;
                }
                try {
                    mThermalHal11.getCoolingDevices((status, coolingDevices) -> {
                        if (ThermalStatusCode.SUCCESS == status.code) {
                            for (android.hardware.thermal.V1_0.CoolingDevice
                                    coolingDevice : coolingDevices) {
                                if (shouldFilter && type != coolingDevice.type) {
                                    continue;
                                }
                                ret.add(new CoolingDevice(
                                        (long) coolingDevice.currentValue,
                                        coolingDevice.type,
                                        coolingDevice.name));
                            }
                        } else {
                            Slog.e(TAG,
                                    "Couldn't get cooling device because of HAL error: "
                                            + status.debugMessage);
                        }

                    });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentCoolingDevices, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        @Override
        protected List<TemperatureThreshold> getTemperatureThresholds(boolean shouldFilter,
                int type) {
            return new ArrayList<>();
        }

        @Override
        protected boolean connectToHal() {
            synchronized (mHalLock) {
                try {
                    mThermalHal11 = android.hardware.thermal.V1_1.IThermal.getService(true);
                    mThermalHal11.linkToDeath(new DeathRecipient(),
                            THERMAL_HAL_DEATH_COOKIE);
                    mThermalHal11.registerThermalCallback(mThermalCallback11);
                    Slog.i(TAG, "Thermal HAL 1.1 service connected, limited thermal functions "
                            + "due to legacy API.");
                } catch (NoSuchElementException | RemoteException e) {
                    Slog.e(TAG, "Thermal HAL 1.1 service not connected.");
                    mThermalHal11 = null;
                }
                return (mThermalHal11 != null);
            }
        }

        @Override
        protected void dump(PrintWriter pw, String prefix) {
            synchronized (mHalLock) {
                pw.print(prefix);
                pw.println("ThermalHAL 1.1 connected: " + (mThermalHal11 != null ? "yes"
                        : "no"));
            }
        }
    }

    static class ThermalHal20Wrapper extends ThermalHalWrapper {
        /** Proxy object for the Thermal HAL 2.0 service. */
        @GuardedBy("mHalLock")
        private android.hardware.thermal.V2_0.IThermal mThermalHal20 = null;

        /** HWbinder callback for Thermal HAL 2.0. */
        private final IThermalChangedCallback.Stub mThermalCallback20 =
                new IThermalChangedCallback.Stub() {
                    @Override
                    public void notifyThrottling(
                            android.hardware.thermal.V2_0.Temperature temperature) {
                        Temperature thermalSvcTemp = new Temperature(
                                temperature.value, temperature.type, temperature.name,
                                temperature.throttlingStatus);
                        final long token = Binder.clearCallingIdentity();
                        try {
                            mCallback.onValues(thermalSvcTemp);
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                };

        @Override
        protected List<Temperature> getCurrentTemperatures(boolean shouldFilter,
                int type) {
            synchronized (mHalLock) {
                List<Temperature> ret = new ArrayList<>();
                if (mThermalHal20 == null) {
                    return ret;
                }
                try {
                    mThermalHal20.getCurrentTemperatures(shouldFilter, type,
                            (status, temperatures) -> {
                                if (ThermalStatusCode.SUCCESS == status.code) {
                                    for (android.hardware.thermal.V2_0.Temperature
                                            temperature : temperatures) {
                                        if (!Temperature.isValidStatus(
                                                temperature.throttlingStatus)) {
                                            Slog.e(TAG, "Invalid status data from HAL");
                                            temperature.throttlingStatus =
                                                Temperature.THROTTLING_NONE;
                                        }
                                        ret.add(new Temperature(
                                                temperature.value, temperature.type,
                                                temperature.name,
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
                    connectToHal();
                }
                return ret;
            }
        }

        @Override
        protected List<CoolingDevice> getCurrentCoolingDevices(boolean shouldFilter,
                int type) {
            synchronized (mHalLock) {
                List<CoolingDevice> ret = new ArrayList<>();
                if (mThermalHal20 == null) {
                    return ret;
                }
                try {
                    mThermalHal20.getCurrentCoolingDevices(shouldFilter, type,
                            (status, coolingDevices) -> {
                                if (ThermalStatusCode.SUCCESS == status.code) {
                                    for (android.hardware.thermal.V2_0.CoolingDevice
                                            coolingDevice : coolingDevices) {
                                        ret.add(new CoolingDevice(
                                                coolingDevice.value, coolingDevice.type,
                                                coolingDevice.name));
                                    }
                                } else {
                                    Slog.e(TAG,
                                            "Couldn't get cooling device because of HAL error: "
                                                    + status.debugMessage);
                                }

                            });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentCoolingDevices, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        @Override
        protected List<TemperatureThreshold> getTemperatureThresholds(boolean shouldFilter,
                int type) {
            synchronized (mHalLock) {
                List<TemperatureThreshold> ret = new ArrayList<>();
                if (mThermalHal20 == null) {
                    return ret;
                }
                try {
                    mThermalHal20.getTemperatureThresholds(shouldFilter, type,
                            (status, thresholds) -> {
                                if (ThermalStatusCode.SUCCESS == status.code) {
                                    ret.addAll(thresholds);
                                } else {
                                    Slog.e(TAG,
                                            "Couldn't get temperature thresholds because of HAL "
                                                    + "error: " + status.debugMessage);
                                }
                            });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getTemperatureThresholds, reconnecting...", e);
                }
                return ret;
            }
        }

        @Override
        protected boolean connectToHal() {
            synchronized (mHalLock) {
                try {
                    mThermalHal20 = android.hardware.thermal.V2_0.IThermal.getService(true);
                    mThermalHal20.linkToDeath(new DeathRecipient(), THERMAL_HAL_DEATH_COOKIE);
                    mThermalHal20.registerThermalChangedCallback(mThermalCallback20, false,
                            0 /* not used */);
                    Slog.i(TAG, "Thermal HAL 2.0 service connected.");
                } catch (NoSuchElementException | RemoteException e) {
                    Slog.e(TAG, "Thermal HAL 2.0 service not connected.");
                    mThermalHal20 = null;
                }
                return (mThermalHal20 != null);
            }
        }

        @Override
        protected void dump(PrintWriter pw, String prefix) {
            synchronized (mHalLock) {
                pw.print(prefix);
                pw.println("ThermalHAL 2.0 connected: " + (mThermalHal20 != null ? "yes"
                        : "no"));
            }
        }
    }

    @VisibleForTesting
    class TemperatureWatcher {
        private final Handler mHandler = BackgroundThread.getHandler();

        /** Map of skin temperature sensor name to a corresponding list of samples */
        @GuardedBy("mSamples")
        @VisibleForTesting
        final ArrayMap<String, ArrayList<Sample>> mSamples = new ArrayMap<>();

        /** Map of skin temperature sensor name to the corresponding SEVERE temperature threshold */
        @GuardedBy("mSamples")
        @VisibleForTesting
        ArrayMap<String, Float> mSevereThresholds = new ArrayMap<>();

        @GuardedBy("mSamples")
        private long mLastForecastCallTimeMillis = 0;

        private static final int INACTIVITY_THRESHOLD_MILLIS = 10000;
        @VisibleForTesting
        long mInactivityThresholdMillis = INACTIVITY_THRESHOLD_MILLIS;

        void updateSevereThresholds() {
            synchronized (mSamples) {
                List<TemperatureThreshold> thresholds =
                        mHalWrapper.getTemperatureThresholds(true, Temperature.TYPE_SKIN);
                for (int t = 0; t < thresholds.size(); ++t) {
                    TemperatureThreshold threshold = thresholds.get(t);
                    if (threshold.hotThrottlingThresholds.length <= ThrottlingSeverity.SEVERE) {
                        continue;
                    }
                    float temperature =
                            threshold.hotThrottlingThresholds[ThrottlingSeverity.SEVERE];
                    if (!Float.isNaN(temperature)) {
                        mSevereThresholds.put(threshold.name,
                                threshold.hotThrottlingThresholds[ThrottlingSeverity.SEVERE]);
                    }
                }
            }
        }

        private static final int RING_BUFFER_SIZE = 30;

        private void updateTemperature() {
            synchronized (mSamples) {
                if (SystemClock.elapsedRealtime() - mLastForecastCallTimeMillis
                        < mInactivityThresholdMillis) {
                    // Trigger this again after a second as long as forecast has been called more
                    // recently than the inactivity timeout
                    mHandler.postDelayed(this::updateTemperature, 1000);
                } else {
                    // Otherwise, we've been idle for at least 10 seconds, so we should
                    // shut down
                    mSamples.clear();
                    return;
                }

                long now = SystemClock.elapsedRealtime();
                List<Temperature> temperatures = mHalWrapper.getCurrentTemperatures(true,
                        Temperature.TYPE_SKIN);

                for (int t = 0; t < temperatures.size(); ++t) {
                    Temperature temperature = temperatures.get(t);

                    // Filter out invalid temperatures. If this results in no values being stored at
                    // all, the mSamples.empty() check in getForecast() will catch it.
                    if (Float.isNaN(temperature.getValue())) {
                        continue;
                    }

                    ArrayList<Sample> samples = mSamples.computeIfAbsent(temperature.getName(),
                            k -> new ArrayList<>(RING_BUFFER_SIZE));
                    if (samples.size() == RING_BUFFER_SIZE) {
                        samples.remove(0);
                    }
                    samples.add(new Sample(now, temperature.getValue()));
                }
            }
        }

        /**
         * Calculates the trend using a linear regression. As the samples are degrees Celsius with
         * associated timestamps in milliseconds, the slope is in degrees Celsius per millisecond.
         */
        @VisibleForTesting
        float getSlopeOf(List<Sample> samples) {
            long sumTimes = 0L;
            float sumTemperatures = 0.0f;
            for (int s = 0; s < samples.size(); ++s) {
                Sample sample = samples.get(s);
                sumTimes += sample.time;
                sumTemperatures += sample.temperature;
            }
            long meanTime = sumTimes / samples.size();
            float meanTemperature = sumTemperatures / samples.size();

            long sampleVariance = 0L;
            float sampleCovariance = 0.0f;
            for (int s = 0; s < samples.size(); ++s) {
                Sample sample = samples.get(s);
                long timeDelta = sample.time - meanTime;
                float temperatureDelta = sample.temperature - meanTemperature;
                sampleVariance += timeDelta * timeDelta;
                sampleCovariance += timeDelta * temperatureDelta;
            }

            return sampleCovariance / sampleVariance;
        }

        /**
         * Used to determine the temperature corresponding to 0.0. Given that 1.0 is pinned at the
         * temperature corresponding to the SEVERE threshold, we set 0.0 to be that temperature
         * minus DEGREES_BETWEEN_ZERO_AND_ONE.
         */
        private static final float DEGREES_BETWEEN_ZERO_AND_ONE = 30.0f;

        @VisibleForTesting
        float normalizeTemperature(float temperature, float severeThreshold) {
            synchronized (mSamples) {
                float zeroNormalized = severeThreshold - DEGREES_BETWEEN_ZERO_AND_ONE;
                if (temperature <= zeroNormalized) {
                    return 0.0f;
                }
                float delta = temperature - zeroNormalized;
                return delta / DEGREES_BETWEEN_ZERO_AND_ONE;
            }
        }

        private static final int MINIMUM_SAMPLE_COUNT = 3;

        float getForecast(int forecastSeconds) {
            synchronized (mSamples) {
                mLastForecastCallTimeMillis = SystemClock.elapsedRealtime();
                if (mSamples.isEmpty()) {
                    updateTemperature();
                }

                // If somehow things take much longer than expected or there are no temperatures
                // to sample, return early
                if (mSamples.isEmpty()) {
                    Slog.e(TAG, "No temperature samples found");
                    return Float.NaN;
                }

                // If we don't have any thresholds, we can't normalize the temperatures,
                // so return early
                if (mSevereThresholds.isEmpty()) {
                    Slog.e(TAG, "No temperature thresholds found");
                    return Float.NaN;
                }

                float maxNormalized = Float.NaN;
                for (Map.Entry<String, ArrayList<Sample>> entry : mSamples.entrySet()) {
                    String name = entry.getKey();
                    ArrayList<Sample> samples = entry.getValue();

                    Float threshold = mSevereThresholds.get(name);
                    if (threshold == null) {
                        Slog.e(TAG, "No threshold found for " + name);
                        continue;
                    }

                    float currentTemperature = samples.get(0).temperature;

                    if (samples.size() < MINIMUM_SAMPLE_COUNT) {
                        // Don't try to forecast, just use the latest one we have
                        float normalized = normalizeTemperature(currentTemperature, threshold);
                        if (Float.isNaN(maxNormalized) || normalized > maxNormalized) {
                            maxNormalized = normalized;
                        }
                        continue;
                    }

                    float slope = getSlopeOf(samples);
                    float normalized = normalizeTemperature(
                            currentTemperature + slope * forecastSeconds * 1000, threshold);
                    if (Float.isNaN(maxNormalized) || normalized > maxNormalized) {
                        maxNormalized = normalized;
                    }
                }

                return maxNormalized;
            }
        }

        @VisibleForTesting
        // Since Sample is inside an inner class, we can't make it static
        // This allows test code to create Sample objects via ThermalManagerService
        Sample createSampleForTesting(long time, float temperature) {
            return new Sample(time, temperature);
        }

        @VisibleForTesting
        class Sample {
            public long time;
            public float temperature;

            Sample(long time, float temperature) {
                this.time = time;
                this.temperature = temperature;
            }
        }
    }
}
