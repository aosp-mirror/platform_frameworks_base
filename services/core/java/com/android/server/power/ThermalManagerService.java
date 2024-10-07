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

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.internal.util.FrameworkStatsLog.THERMAL_HEADROOM_CALLED__API_STATUS__NO_TEMPERATURE_THRESHOLD;
import static com.android.internal.util.FrameworkStatsLog.THERMAL_HEADROOM_THRESHOLDS_CALLED__API_STATUS__FEATURE_NOT_SUPPORTED;
import static com.android.internal.util.FrameworkStatsLog.THERMAL_HEADROOM_THRESHOLDS_CALLED__API_STATUS__HAL_NOT_READY;
import static com.android.internal.util.FrameworkStatsLog.THERMAL_HEADROOM_THRESHOLDS_CALLED__API_STATUS__SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.THERMAL_STATUS_CALLED__API_STATUS__HAL_NOT_READY;
import static com.android.internal.util.FrameworkStatsLog.THERMAL_STATUS_CALLED__API_STATUS__SUCCESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.StatsManager;
import android.content.Context;
import android.hardware.thermal.IThermal;
import android.hardware.thermal.IThermalChangedCallback;
import android.hardware.thermal.TemperatureThreshold;
import android.hardware.thermal.ThrottlingSeverity;
import android.hardware.thermal.V1_0.ThermalStatus;
import android.hardware.thermal.V1_0.ThermalStatusCode;
import android.hardware.thermal.V1_1.IThermalCallback;
import android.os.Binder;
import android.os.CoolingDevice;
import android.os.Flags;
import android.os.Handler;
import android.os.HwBinder;
import android.os.IBinder;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.IThermalStatusListener;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Temperature;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Slog;
import android.util.StatsEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * This is a system service that listens to HAL thermal events and dispatch those to listeners.
 * <p>The service will also trigger actions based on severity of the throttling status.</p>
 *
 * @hide
 */
public class ThermalManagerService extends SystemService {
    private static final String TAG = ThermalManagerService.class.getSimpleName();

    private static final boolean DEBUG = false;

    /** Input range limits for getThermalHeadroom API */
    public static final int MIN_FORECAST_SEC = 0;
    public static final int MAX_FORECAST_SEC = 60;

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

    /** If override status takes effect */
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

    private final Context mContext;

    public ThermalManagerService(Context context) {
        this(context, null);
    }

    @VisibleForTesting
    ThermalManagerService(Context context, @Nullable ThermalHalWrapper halWrapper) {
        super(context);
        mContext = context;
        mHalWrapper = halWrapper;
        if (halWrapper != null) {
            halWrapper.setCallback(this::onTemperatureChangedCallback);
        }
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
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            registerStatsCallbacks();
        }
    }

    private void onActivityManagerReady() {
        synchronized (mLock) {
            // Connect to HAL and post to listeners.
            boolean halConnected = (mHalWrapper != null);
            if (!halConnected) {
                mHalWrapper = new ThermalHalAidlWrapper(this::onTemperatureChangedCallback);
                halConnected = mHalWrapper.connectToHal();
            }
            if (!halConnected) {
                mHalWrapper = new ThermalHal20Wrapper(this::onTemperatureChangedCallback);
                halConnected = mHalWrapper.connectToHal();
            }
            if (!halConnected) {
                mHalWrapper = new ThermalHal11Wrapper(this::onTemperatureChangedCallback);
                halConnected = mHalWrapper.connectToHal();
            }
            if (!halConnected) {
                mHalWrapper = new ThermalHal10Wrapper(this::onTemperatureChangedCallback);
                halConnected = mHalWrapper.connectToHal();
            }
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
            mTemperatureWatcher.updateThresholds();
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
            Trace.traceCounter(Trace.TRACE_TAG_POWER, "ThermalManagerService.status", newStatus);
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

    private void registerStatsCallbacks() {
        final StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        if (statsManager != null) {
            statsManager.setPullAtomCallback(
                    FrameworkStatsLog.THERMAL_HEADROOM_THRESHOLDS,
                    null, // use default PullAtomMetadata values
                    DIRECT_EXECUTOR,
                    this::onPullAtom);
        }
    }

    private int onPullAtom(int atomTag, @NonNull List<StatsEvent> data) {
        if (atomTag == FrameworkStatsLog.THERMAL_HEADROOM_THRESHOLDS) {
            final float[] thresholds;
            synchronized (mTemperatureWatcher.mSamples) {
                thresholds = Arrays.copyOf(mTemperatureWatcher.mHeadroomThresholds,
                        mTemperatureWatcher.mHeadroomThresholds.length);
            }
            data.add(
                    FrameworkStatsLog.buildStatsEvent(FrameworkStatsLog.THERMAL_HEADROOM_THRESHOLDS,
                            thresholds));
        }
        return android.app.StatsManager.PULL_SUCCESS;
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
                    FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_STATUS_CALLED,
                            Binder.getCallingUid(),
                            mHalReady.get()
                                    ? THERMAL_STATUS_CALLED__API_STATUS__SUCCESS
                                    : THERMAL_STATUS_CALLED__API_STATUS__HAL_NOT_READY,
                            thermalSeverityToStatsdStatus(mStatus));
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
                FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_HEADROOM_CALLED, getCallingUid(),
                            FrameworkStatsLog.THERMAL_HEADROOM_CALLED__API_STATUS__HAL_NOT_READY,
                            Float.NaN, forecastSeconds);
                return Float.NaN;
            }

            if (forecastSeconds < MIN_FORECAST_SEC || forecastSeconds > MAX_FORECAST_SEC) {
                if (DEBUG) {
                    Slog.d(TAG, "Invalid forecastSeconds: " + forecastSeconds);
                }
                FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_HEADROOM_CALLED, getCallingUid(),
                            FrameworkStatsLog.THERMAL_HEADROOM_CALLED__API_STATUS__INVALID_ARGUMENT,
                            Float.NaN, forecastSeconds);
                return Float.NaN;
            }

            return mTemperatureWatcher.getForecast(forecastSeconds);
        }

        @Override
        public float[] getThermalHeadroomThresholds() {
            if (!mHalReady.get()) {
                FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_HEADROOM_THRESHOLDS_CALLED,
                        Binder.getCallingUid(),
                        THERMAL_HEADROOM_THRESHOLDS_CALLED__API_STATUS__HAL_NOT_READY);
                throw new IllegalStateException("Thermal HAL connection is not initialized");
            }
            if (!Flags.allowThermalHeadroomThresholds()) {
                FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_HEADROOM_THRESHOLDS_CALLED,
                        Binder.getCallingUid(),
                        THERMAL_HEADROOM_THRESHOLDS_CALLED__API_STATUS__FEATURE_NOT_SUPPORTED);
                throw new UnsupportedOperationException("Thermal headroom thresholds not enabled");
            }
            synchronized (mTemperatureWatcher.mSamples) {
                FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_HEADROOM_THRESHOLDS_CALLED,
                        Binder.getCallingUid(),
                        THERMAL_HEADROOM_THRESHOLDS_CALLED__API_STATUS__SUCCESS);
                return Arrays.copyOf(mTemperatureWatcher.mHeadroomThresholds,
                        mTemperatureWatcher.mHeadroomThresholds.length);
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            dumpInternal(fd, pw, args);
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

    private static int thermalSeverityToStatsdStatus(int severity) {
        switch (severity) {
            case PowerManager.THERMAL_STATUS_NONE:
                return FrameworkStatsLog.THERMAL_STATUS_CALLED__STATUS__NONE;
            case PowerManager.THERMAL_STATUS_LIGHT:
                return FrameworkStatsLog.THERMAL_STATUS_CALLED__STATUS__LIGHT;
            case PowerManager.THERMAL_STATUS_MODERATE:
                return FrameworkStatsLog.THERMAL_STATUS_CALLED__STATUS__MODERATE;
            case PowerManager.THERMAL_STATUS_SEVERE:
                return FrameworkStatsLog.THERMAL_STATUS_CALLED__STATUS__SEVERE;
            case PowerManager.THERMAL_STATUS_CRITICAL:
                return FrameworkStatsLog.THERMAL_STATUS_CALLED__STATUS__CRITICAL;
            case PowerManager.THERMAL_STATUS_EMERGENCY:
                return FrameworkStatsLog.THERMAL_STATUS_CALLED__STATUS__EMERGENCY;
            case PowerManager.THERMAL_STATUS_SHUTDOWN:
                return FrameworkStatsLog.THERMAL_STATUS_CALLED__STATUS__SHUTDOWN;
            default:
                return FrameworkStatsLog.THERMAL_STATUS_CALLED__STATUS__NONE;
        }
    }

    private static void dumpItemsLocked(PrintWriter pw, String prefix,
            Collection<?> items) {
        for (Iterator iterator = items.iterator(); iterator.hasNext();) {
            pw.println(prefix + iterator.next().toString());
        }
    }

    private static void dumpTemperatureThresholds(PrintWriter pw, String prefix,
            List<TemperatureThreshold> thresholds) {
        for (TemperatureThreshold threshold : thresholds) {
            pw.println(prefix + "TemperatureThreshold{mType=" + threshold.type
                    + ", mName=" + threshold.name
                    + ", mHotThrottlingThresholds=" + Arrays.toString(
                    threshold.hotThrottlingThresholds)
                    + ", mColdThrottlingThresholds=" + Arrays.toString(
                    threshold.coldThrottlingThresholds)
                    + "}");
        }
    }

    @VisibleForTesting
    void dumpInternal(FileDescriptor fd, PrintWriter pw, String[] args) {
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
                    dumpTemperatureThresholds(pw, "\t",
                            mHalWrapper.getTemperatureThresholds(false, 0));
                }
            }
            if (Flags.allowThermalHeadroomThresholds()) {
                synchronized (mTemperatureWatcher.mSamples) {
                    pw.println("Temperature headroom thresholds:");
                    pw.println(Arrays.toString(mTemperatureWatcher.mHeadroomThresholds));
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    class ThermalShellCommand extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            switch(cmd != null ? cmd : "") {
                case "inject-temperature":
                    return runInjectTemperature();
                case "override-status":
                    return runOverrideStatus();
                case "reset":
                    return runReset();
                case "headroom":
                    return runHeadroom();
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


        private int runInjectTemperature() {
            final long token = Binder.clearCallingIdentity();
            try {
                final PrintWriter pw = getOutPrintWriter();
                int type;
                String typeName = getNextArgRequired();
                switch (typeName.toUpperCase()) {
                    case "UNKNOWN":
                        type = Temperature.TYPE_UNKNOWN;
                        break;
                    case "CPU":
                        type = Temperature.TYPE_CPU;
                        break;
                    case "GPU":
                        type = Temperature.TYPE_GPU;
                        break;
                    case "BATTERY":
                        type = Temperature.TYPE_BATTERY;
                        break;
                    case "SKIN":
                        type = Temperature.TYPE_SKIN;
                        break;
                    case "USB_PORT":
                        type = Temperature.TYPE_USB_PORT;
                        break;
                    case "POWER_AMPLIFIER":
                        type = Temperature.TYPE_POWER_AMPLIFIER;
                        break;
                    case "BCL_VOLTAGE":
                        type = Temperature.TYPE_BCL_VOLTAGE;
                        break;
                    case "BCL_CURRENT":
                        type = Temperature.TYPE_BCL_CURRENT;
                        break;
                    case "BCL_PERCENTAGE":
                        type = Temperature.TYPE_BCL_PERCENTAGE;
                        break;
                    case "NPU":
                        type = Temperature.TYPE_NPU;
                        break;
                    case "TPU":
                        type = Temperature.TYPE_TPU;
                        break;
                    case "DISPLAY":
                        type = Temperature.TYPE_DISPLAY;
                        break;
                    case "MODEM":
                        type = Temperature.TYPE_MODEM;
                        break;
                    case "SOC":
                        type = Temperature.TYPE_SOC;
                        break;
                    case "WIFI":
                        type = Temperature.TYPE_WIFI;
                        break;
                    case "CAMERA":
                        type = Temperature.TYPE_CAMERA;
                        break;
                    case "FLASHLIGHT":
                        type = Temperature.TYPE_FLASHLIGHT;
                        break;
                    case "SPEAKER":
                        type = Temperature.TYPE_SPEAKER;
                        break;
                    case "AMBIENT":
                        type = Temperature.TYPE_AMBIENT;
                        break;
                    case "POGO":
                        type = Temperature.TYPE_POGO;
                        break;
                    default:
                        pw.println("Invalid temperature type: " + typeName);
                        return -1;
                }
                int throttle;
                String throttleName = getNextArgRequired();
                switch (throttleName.toUpperCase()) {
                    case "NONE":
                        throttle = Temperature.THROTTLING_NONE;
                        break;
                    case "LIGHT":
                        throttle = Temperature.THROTTLING_LIGHT;
                        break;
                    case "MODERATE":
                        throttle = Temperature.THROTTLING_MODERATE;
                        break;
                    case "SEVERE":
                        throttle = Temperature.THROTTLING_SEVERE;
                        break;
                    case "CRITICAL":
                        throttle = Temperature.THROTTLING_CRITICAL;
                        break;
                    case "EMERGENCY":
                        throttle = Temperature.THROTTLING_EMERGENCY;
                        break;
                    case "SHUTDOWN":
                        throttle = Temperature.THROTTLING_SHUTDOWN;
                        break;
                    default:
                        pw.println("Invalid throttle status: " + throttleName);
                        return -1;
                }
                String name = getNextArgRequired();
                float value = 28.0f;
                try {
                    String valueStr = getNextArg();
                    if (valueStr != null) value = Float.parseFloat(valueStr);
                } catch (RuntimeException ex) {
                    pw.println("Error: " + ex.toString());
                    return -1;
                }
                onTemperatureChanged(new Temperature(value, type, name, throttle), true);
                return 0;
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

        private int runHeadroom() {
            final long token = Binder.clearCallingIdentity();
            try {
                final PrintWriter pw = getOutPrintWriter();
                int forecastSecs;
                try {
                    forecastSecs = Integer.parseInt(getNextArgRequired());
                } catch (RuntimeException ex) {
                    pw.println("Error: " + ex);
                    return -1;
                }
                if (!mHalReady.get()) {
                    pw.println("Error: thermal HAL is not ready");
                    return -1;
                }

                if (forecastSecs < MIN_FORECAST_SEC || forecastSecs > MAX_FORECAST_SEC) {
                    pw.println(
                            "Error: forecast second input should be in range [" + MIN_FORECAST_SEC
                                    + "," + MAX_FORECAST_SEC + "]");
                    return -1;
                }
                float headroom = mTemperatureWatcher.getForecast(forecastSecs);
                pw.println("Headroom in " + forecastSecs + " seconds: " + headroom);
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
            pw.println("  inject-temperature TYPE STATUS NAME [VALUE]");
            pw.println("    injects a new temperature sample for the specified device.");
            pw.println("    type and status strings follow the names in android.os.Temperature.");
            pw.println("  override-status STATUS");
            pw.println("    sets and locks the thermal status of the device to STATUS.");
            pw.println("    status code is defined in android.os.Temperature.");
            pw.println("  reset");
            pw.println("    unlocks the thermal status of the device.");
            pw.println("  headroom FORECAST_SECONDS");
            pw.println("    gets the thermal headroom forecast in specified seconds, from ["
                    + MIN_FORECAST_SEC + "," + MAX_FORECAST_SEC + "].");
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

    @VisibleForTesting
    static class ThermalHalAidlWrapper extends ThermalHalWrapper implements IBinder.DeathRecipient {
        /* Proxy object for the Thermal HAL AIDL service. */
        private IThermal mInstance = null;

        /** Callback for Thermal HAL AIDL. */
        private final IThermalChangedCallback mThermalChangedCallback =
                new IThermalChangedCallback.Stub() {
                    @Override public void notifyThrottling(
                            android.hardware.thermal.Temperature temperature)
                            throws RemoteException {
                        Temperature svcTemperature = new Temperature(temperature.value,
                                temperature.type, temperature.name, temperature.throttlingStatus);
                        final long token = Binder.clearCallingIdentity();
                        try {
                            mCallback.onValues(svcTemperature);
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }

            @Override public int getInterfaceVersion() throws RemoteException {
                return this.VERSION;
            }

            @Override public String getInterfaceHash() throws RemoteException {
                return this.HASH;
            }
        };

        ThermalHalAidlWrapper(TemperatureChangedCallback callback) {
            mCallback = callback;
        }

        @Override
        protected List<Temperature> getCurrentTemperatures(boolean shouldFilter,
                int type) {
            synchronized (mHalLock) {
                final List<Temperature> ret = new ArrayList<>();
                if (mInstance == null) {
                    return ret;
                }
                try {
                    final android.hardware.thermal.Temperature[] halRet =
                            shouldFilter ? mInstance.getTemperaturesWithType(type)
                                    : mInstance.getTemperatures();
                    if (halRet == null) {
                        return ret;
                    }
                    for (android.hardware.thermal.Temperature t : halRet) {
                        if (!Temperature.isValidStatus(t.throttlingStatus)) {
                            Slog.e(TAG, "Invalid temperature status " + t.throttlingStatus
                                    + " received from AIDL HAL");
                            t.throttlingStatus = Temperature.THROTTLING_NONE;
                        }
                        if (shouldFilter && t.type != type) {
                            continue;
                        }
                        ret.add(new Temperature(t.value, t.type, t.name, t.throttlingStatus));
                    }
                } catch (IllegalArgumentException | IllegalStateException e) {
                    Slog.e(TAG, "Couldn't getCurrentCoolingDevices due to invalid status", e);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentTemperatures, reconnecting", e);
                    connectToHal();
                }
                return ret;
            }
        }

        @Override
        protected List<CoolingDevice> getCurrentCoolingDevices(boolean shouldFilter,
                int type) {
            synchronized (mHalLock) {
                final List<CoolingDevice> ret = new ArrayList<>();
                if (mInstance == null) {
                    return ret;
                }
                try {
                    final android.hardware.thermal.CoolingDevice[] halRet = shouldFilter
                            ? mInstance.getCoolingDevicesWithType(type)
                            : mInstance.getCoolingDevices();
                    if (halRet == null) {
                        return ret;
                    }
                    for (android.hardware.thermal.CoolingDevice t : halRet) {
                        if (!CoolingDevice.isValidType(t.type)) {
                            Slog.e(TAG, "Invalid cooling device type " + t.type + " from AIDL HAL");
                            continue;
                        }
                        if (shouldFilter && t.type != type) {
                            continue;
                        }
                        ret.add(new CoolingDevice(t.value, t.type, t.name));
                    }
                } catch (IllegalArgumentException | IllegalStateException e) {
                    Slog.e(TAG, "Couldn't getCurrentCoolingDevices due to invalid status", e);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentCoolingDevices, reconnecting", e);
                    connectToHal();
                }
                return ret;
            }
        }

        @Override
        @NonNull protected List<TemperatureThreshold> getTemperatureThresholds(
                boolean shouldFilter, int type) {
            synchronized (mHalLock) {
                final List<TemperatureThreshold> ret = new ArrayList<>();
                if (mInstance == null) {
                    return ret;
                }
                try {
                    final TemperatureThreshold[] halRet =
                            shouldFilter ? mInstance.getTemperatureThresholdsWithType(type)
                                    : mInstance.getTemperatureThresholds();
                    if (halRet == null) {
                        return ret;
                    }
                    if (shouldFilter) {
                        return Arrays.stream(halRet).filter(t -> t.type == type).collect(
                                Collectors.toList());
                    }
                    return Arrays.asList(halRet);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    Slog.e(TAG, "Couldn't getTemperatureThresholds due to invalid status", e);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getTemperatureThresholds, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        @Override
        protected boolean connectToHal() {
            synchronized (mHalLock) {
                IBinder binder = Binder.allowBlocking(ServiceManager.waitForDeclaredService(
                        IThermal.DESCRIPTOR + "/default"));
                initProxyAndRegisterCallback(binder);
            }
            return mInstance != null;
        }

        @VisibleForTesting
        void initProxyAndRegisterCallback(IBinder binder) {
            synchronized (mHalLock) {
                if (binder != null) {
                    mInstance = IThermal.Stub.asInterface(binder);
                    try {
                        binder.linkToDeath(this, 0);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to connect IThermal AIDL instance", e);
                        connectToHal();
                    }
                    if (mInstance != null) {
                        try {
                            Slog.i(TAG, "Thermal HAL AIDL service connected with version "
                                    + mInstance.getInterfaceVersion());
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Unable to read interface version from Thermal HAL", e);
                            connectToHal();
                            return;
                        }
                        registerThermalChangedCallback();
                    }
                }
            }
        }

        @VisibleForTesting
        void registerThermalChangedCallback() {
            try {
                mInstance.registerThermalChangedCallback(mThermalChangedCallback);
            } catch (IllegalArgumentException | IllegalStateException e) {
                Slog.e(TAG, "Couldn't registerThermalChangedCallback due to invalid status",
                        e);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to connect IThermal AIDL instance", e);
                connectToHal();
            }
        }

        @Override
        protected void dump(PrintWriter pw, String prefix) {
            synchronized (mHalLock) {
                pw.print(prefix);
                pw.println(
                        "ThermalHAL AIDL " + IThermal.VERSION + "  connected: " + (mInstance != null
                                ? "yes" : "no"));
            }
        }

        @Override
        public synchronized void binderDied() {
            Slog.w(TAG, "Thermal AIDL HAL died, reconnecting...");
            connectToHal();
        }
    }

    static class ThermalHal10Wrapper extends ThermalHalWrapper {
        /** Proxy object for the Thermal HAL 1.0 service. */
        @GuardedBy("mHalLock")
        private android.hardware.thermal.V1_0.IThermal mThermalHal10 = null;

        ThermalHal10Wrapper(TemperatureChangedCallback callback) {
            mCallback = callback;
        }

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
                                isThrottling ? Temperature.THROTTLING_SEVERE
                                        : Temperature.THROTTLING_NONE);
                        final long token = Binder.clearCallingIdentity();
                        try {
                            mCallback.onValues(thermalSvcTemp);
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                };

        ThermalHal11Wrapper(TemperatureChangedCallback callback) {
            mCallback = callback;
        }

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
        private final android.hardware.thermal.V2_0.IThermalChangedCallback.Stub
                mThermalCallback20 =
                new android.hardware.thermal.V2_0.IThermalChangedCallback.Stub() {
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

        ThermalHal20Wrapper(TemperatureChangedCallback callback) {
            mCallback = callback;
        }

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
                                    ret.addAll(thresholds.stream().map(
                                            this::convertToAidlTemperatureThreshold).collect(
                                            Collectors.toList()));
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

        private TemperatureThreshold convertToAidlTemperatureThreshold(
                android.hardware.thermal.V2_0.TemperatureThreshold threshold) {
            final TemperatureThreshold ret = new TemperatureThreshold();
            ret.name = threshold.name;
            ret.type = threshold.type;
            ret.coldThrottlingThresholds = threshold.coldThrottlingThresholds;
            ret.hotThrottlingThresholds = threshold.hotThrottlingThresholds;
            return ret;
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
        float[] mHeadroomThresholds = new float[ThrottlingSeverity.SHUTDOWN + 1];
        @GuardedBy("mSamples")
        private long mLastForecastCallTimeMillis = 0;

        private static final int INACTIVITY_THRESHOLD_MILLIS = 10000;
        @VisibleForTesting
        long mInactivityThresholdMillis = INACTIVITY_THRESHOLD_MILLIS;

        void updateThresholds() {
            List<TemperatureThreshold> thresholds =
                        mHalWrapper.getTemperatureThresholds(true, Temperature.TYPE_SKIN);
            synchronized (mSamples) {
                if (Flags.allowThermalHeadroomThresholds()) {
                    Arrays.fill(mHeadroomThresholds, Float.NaN);
                }
                for (int t = 0; t < thresholds.size(); ++t) {
                    TemperatureThreshold threshold = thresholds.get(t);
                    if (threshold.hotThrottlingThresholds.length <= ThrottlingSeverity.SEVERE) {
                        continue;
                    }
                    float severeThreshold =
                            threshold.hotThrottlingThresholds[ThrottlingSeverity.SEVERE];
                    if (!Float.isNaN(severeThreshold)) {
                        mSevereThresholds.put(threshold.name, severeThreshold);
                        if (Flags.allowThermalHeadroomThresholds()) {
                            for (int severity = ThrottlingSeverity.LIGHT;
                                    severity <= ThrottlingSeverity.SHUTDOWN; severity++) {
                                if (threshold.hotThrottlingThresholds.length > severity) {
                                    updateHeadroomThreshold(severity,
                                            threshold.hotThrottlingThresholds[severity],
                                            severeThreshold);
                                }
                            }
                        }
                    }
                }
            }
        }

        // For an older device with multiple SKIN sensors, we will set a severity's headroom
        // threshold based on the minimum value of all as a workaround.
        void updateHeadroomThreshold(int severity, float threshold, float severeThreshold) {
            if (!Float.isNaN(threshold)) {
                synchronized (mSamples) {
                    if (severity == ThrottlingSeverity.SEVERE) {
                        mHeadroomThresholds[severity] = 1.0f;
                        return;
                    }
                    float headroom = normalizeTemperature(threshold, severeThreshold);
                    if (Float.isNaN(mHeadroomThresholds[severity])) {
                        mHeadroomThresholds[severity] = headroom;
                    } else {
                        float lastHeadroom = mHeadroomThresholds[severity];
                        mHeadroomThresholds[severity] = Math.min(lastHeadroom, headroom);
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
                        samples.removeFirst();
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
        static float normalizeTemperature(float temperature, float severeThreshold) {
            float zeroNormalized = severeThreshold - DEGREES_BETWEEN_ZERO_AND_ONE;
            if (temperature <= zeroNormalized) {
                return 0.0f;
            }
            float delta = temperature - zeroNormalized;
            return delta / DEGREES_BETWEEN_ZERO_AND_ONE;
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
                    FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_HEADROOM_CALLED,
                            Binder.getCallingUid(),
                            FrameworkStatsLog.THERMAL_HEADROOM_CALLED__API_STATUS__NO_TEMPERATURE,
                            Float.NaN, forecastSeconds);
                    return Float.NaN;
                }

                // If we don't have any thresholds, we can't normalize the temperatures,
                // so return early
                if (mSevereThresholds.isEmpty()) {
                    Slog.e(TAG, "No temperature thresholds found");
                    FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_HEADROOM_CALLED,
                            Binder.getCallingUid(),
                            THERMAL_HEADROOM_CALLED__API_STATUS__NO_TEMPERATURE_THRESHOLD,
                            Float.NaN, forecastSeconds);
                    return Float.NaN;
                }

                float maxNormalized = Float.NaN;
                int noThresholdSampleCount = 0;
                for (Map.Entry<String, ArrayList<Sample>> entry : mSamples.entrySet()) {
                    String name = entry.getKey();
                    ArrayList<Sample> samples = entry.getValue();

                    Float threshold = mSevereThresholds.get(name);
                    if (threshold == null) {
                        noThresholdSampleCount++;
                        Slog.e(TAG, "No threshold found for " + name);
                        continue;
                    }

                    float currentTemperature = samples.getLast().temperature;

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
                if (noThresholdSampleCount == mSamples.size()) {
                    FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_HEADROOM_CALLED,
                            Binder.getCallingUid(),
                            THERMAL_HEADROOM_CALLED__API_STATUS__NO_TEMPERATURE_THRESHOLD,
                            Float.NaN, forecastSeconds);
                } else {
                    FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_HEADROOM_CALLED,
                            Binder.getCallingUid(),
                            FrameworkStatsLog.THERMAL_HEADROOM_CALLED__API_STATUS__SUCCESS,
                            maxNormalized, forecastSeconds);
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
