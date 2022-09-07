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

package com.android.server.input;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.BatteryState;
import android.hardware.input.IInputDeviceBatteryListener;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.view.InputDevice;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.input.BatteryController.UEventManager.UEventListener;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing the battery state
 * of input devices.
 */
final class BatteryController implements InputManager.InputDeviceListener {
    private static final String TAG = BatteryController.class.getSimpleName();

    // To enable these logs, run:
    // 'adb shell setprop log.tag.BatteryController DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Object mLock = new Object();
    private final Context mContext;
    private final NativeInputManagerService mNative;
    private final Handler mHandler;
    private final UEventManager mUEventManager;

    // Maps a pid to the registered listener record for that process. There can only be one battery
    // listener per process.
    @GuardedBy("mLock")
    private final ArrayMap<Integer, ListenerRecord> mListenerRecords = new ArrayMap<>();

    // Maps a deviceId that is being monitored to the battery state for the device.
    // This must be kept in sync with {@link #mListenerRecords}.
    @GuardedBy("mLock")
    private final ArrayMap<Integer, MonitoredDeviceState> mMonitoredDeviceStates = new ArrayMap<>();

    BatteryController(Context context, NativeInputManagerService nativeService, Looper looper) {
        this(context, nativeService, looper, new UEventManager() {});
    }

    @VisibleForTesting
    BatteryController(Context context, NativeInputManagerService nativeService, Looper looper,
            UEventManager uEventManager) {
        mContext = context;
        mNative = nativeService;
        mHandler = new Handler(looper);
        mUEventManager = uEventManager;
    }

    void systemRunning() {
        Objects.requireNonNull(mContext.getSystemService(InputManager.class))
                .registerInputDeviceListener(this, mHandler);
    }

    /**
     * Register the battery listener for the given input device and start monitoring its battery
     * state.
     */
    @BinderThread
    void registerBatteryListener(int deviceId, @NonNull IInputDeviceBatteryListener listener,
            int pid) {
        synchronized (mLock) {
            ListenerRecord listenerRecord = mListenerRecords.get(pid);

            if (listenerRecord == null) {
                listenerRecord = new ListenerRecord(pid, listener);
                try {
                    listener.asBinder().linkToDeath(listenerRecord.mDeathRecipient, 0);
                } catch (RemoteException e) {
                    Slog.i(TAG, "Client died before battery listener could be registered.");
                    return;
                }
                mListenerRecords.put(pid, listenerRecord);
                if (DEBUG) Slog.d(TAG, "Battery listener added for pid " + pid);
            }

            if (listenerRecord.mListener.asBinder() != listener.asBinder()) {
                throw new SecurityException(
                        "Cannot register a new battery listener when there is already another "
                                + "registered listener for pid "
                                + pid);
            }
            if (!listenerRecord.mMonitoredDevices.add(deviceId)) {
                throw new IllegalArgumentException(
                        "The battery listener for pid " + pid
                                + " is already monitoring deviceId " + deviceId);
            }

            MonitoredDeviceState deviceState = mMonitoredDeviceStates.get(deviceId);
            if (deviceState == null) {
                // This is the first listener that is monitoring this device.
                deviceState = new MonitoredDeviceState(deviceId);
                mMonitoredDeviceStates.put(deviceId, deviceState);
            }

            if (DEBUG) {
                Slog.d(TAG, "Battery listener for pid " + pid
                        + " is monitoring deviceId " + deviceId);
            }

            notifyBatteryListener(listenerRecord, deviceState);
        }
    }

    private static void notifyBatteryListener(ListenerRecord listenerRecord,
            MonitoredDeviceState deviceState) {
        try {
            listenerRecord.mListener.onBatteryStateChanged(
                    deviceState.mDeviceId,
                    deviceState.mHasBattery,
                    deviceState.mBatteryStatus,
                    deviceState.mBatteryCapacity,
                    deviceState.mLastUpdateTime);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to notify listener", e);
        }
    }

    @GuardedBy("mLock")
    private void notifyAllListenersForDeviceLocked(MonitoredDeviceState deviceState) {
        mListenerRecords.forEach((pid, listenerRecord) -> {
            if (listenerRecord.mMonitoredDevices.contains(deviceState.mDeviceId)) {
                notifyBatteryListener(listenerRecord, deviceState);
            }
        });
    }

    private boolean hasBattery(int deviceId) {
        final InputDevice device =
                Objects.requireNonNull(mContext.getSystemService(InputManager.class))
                        .getInputDevice(deviceId);
        return device != null && device.hasBattery();
    }

    @GuardedBy("mLock")
    private MonitoredDeviceState getDeviceStateOrThrowLocked(int deviceId) {
        return Objects.requireNonNull(mMonitoredDeviceStates.get(deviceId),
                "Maps are out of sync: Cannot find device state for deviceId " + deviceId);
    }

    /**
     * Unregister the battery listener for the given input device and stop monitoring its battery
     * state. If there are no other input devices that this listener is monitoring, the listener is
     * removed.
     */
    @BinderThread
    void unregisterBatteryListener(int deviceId, @NonNull IInputDeviceBatteryListener listener,
            int pid) {
        synchronized (mLock) {
            final ListenerRecord listenerRecord = mListenerRecords.get(pid);
            if (listenerRecord == null) {
                throw new IllegalArgumentException(
                        "Cannot unregister battery callback: No listener registered for pid "
                                + pid);
            }

            if (listenerRecord.mListener.asBinder() != listener.asBinder()) {
                throw new IllegalArgumentException(
                        "Cannot unregister battery callback: The listener is not the one that "
                                + "is registered for pid "
                                + pid);
            }

            if (!listenerRecord.mMonitoredDevices.contains(deviceId)) {
                throw new IllegalArgumentException(
                        "Cannot unregister battery callback: The device is not being "
                                + "monitored for deviceId " + deviceId);
            }

            unregisterRecordLocked(listenerRecord, deviceId);
        }
    }

    @GuardedBy("mLock")
    private void unregisterRecordLocked(ListenerRecord listenerRecord, int deviceId) {
        final int pid = listenerRecord.mPid;

        if (!listenerRecord.mMonitoredDevices.remove(deviceId)) {
            throw new IllegalStateException("Cannot unregister battery callback: The deviceId "
                    + deviceId
                    + " is not being monitored by pid "
                    + pid);
        }

        if (!hasRegisteredListenerForDeviceLocked(deviceId)) {
            // There are no more listeners monitoring this device.
            final MonitoredDeviceState deviceState = getDeviceStateOrThrowLocked(deviceId);
            deviceState.stopMonitoring();
            mMonitoredDeviceStates.remove(deviceId);
        }

        if (listenerRecord.mMonitoredDevices.isEmpty()) {
            // There are no more devices being monitored by this listener.
            listenerRecord.mListener.asBinder().unlinkToDeath(listenerRecord.mDeathRecipient, 0);
            mListenerRecords.remove(pid);
            if (DEBUG) Slog.d(TAG, "Battery listener removed for pid " + pid);
        }
    }

    @GuardedBy("mLock")
    private boolean hasRegisteredListenerForDeviceLocked(int deviceId) {
        for (int i = 0; i < mListenerRecords.size(); i++) {
            if (mListenerRecords.valueAt(i).mMonitoredDevices.contains(deviceId)) {
                return true;
            }
        }
        return false;
    }

    private void handleListeningProcessDied(int pid) {
        synchronized (mLock) {
            final ListenerRecord listenerRecord = mListenerRecords.get(pid);
            if (listenerRecord == null) {
                return;
            }
            if (DEBUG) {
                Slog.d(TAG,
                        "Removing battery listener for pid " + pid + " because the process died");
            }
            for (final int deviceId : listenerRecord.mMonitoredDevices) {
                unregisterRecordLocked(listenerRecord, deviceId);
            }
        }
    }

    // Query the battery state for the device and notify all listeners if there is a change.
    private void handleBatteryChangeNotification(int deviceId, long eventTime) {
        synchronized (mLock) {
            final MonitoredDeviceState deviceState = mMonitoredDeviceStates.get(deviceId);
            if (deviceState == null) {
                return;
            }
            if (deviceState.updateBatteryState(eventTime)) {
                notifyAllListenersForDeviceLocked(deviceState);
            }
        }
    }

    void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.println(prefix + TAG + ": " + mListenerRecords.size()
                    + " battery listeners");
            for (int i = 0; i < mListenerRecords.size(); i++) {
                pw.println(prefix + "  " + i + ": " + mListenerRecords.valueAt(i));
            }
        }
    }

    @SuppressWarnings("all")
    void monitor() {
        synchronized (mLock) {
            return;
        }
    }

    @VisibleForTesting
    @Override
    public void onInputDeviceAdded(int deviceId) {}

    @VisibleForTesting
    @Override
    public void onInputDeviceRemoved(int deviceId) {}

    @VisibleForTesting
    @Override
    public void onInputDeviceChanged(int deviceId) {
        synchronized (mLock) {
            final MonitoredDeviceState deviceState = mMonitoredDeviceStates.get(deviceId);
            if (deviceState == null) {
                return;
            }
            final long eventTime = SystemClock.uptimeMillis();
            if (deviceState.updateBatteryState(eventTime)) {
                notifyAllListenersForDeviceLocked(deviceState);
            }
        }
    }

    // A record of a registered battery listener from one process.
    private class ListenerRecord {
        final int mPid;
        final IInputDeviceBatteryListener mListener;
        final IBinder.DeathRecipient mDeathRecipient;
        // The set of deviceIds that are currently being monitored by this listener.
        final Set<Integer> mMonitoredDevices;

        ListenerRecord(int pid, IInputDeviceBatteryListener listener) {
            mPid = pid;
            mListener = listener;
            mMonitoredDevices = new ArraySet<>();
            mDeathRecipient = () -> handleListeningProcessDied(pid);
        }

        @Override
        public String toString() {
            return "pid=" + mPid
                    + ", monitored devices=" + Arrays.toString(mMonitoredDevices.toArray());
        }
    }

    // Holds the state of an InputDevice for which battery changes are currently being monitored.
    private class MonitoredDeviceState {
        private final int mDeviceId;

        private long mLastUpdateTime = 0;
        private boolean mHasBattery = false;
        @BatteryState.BatteryStatus
        private int mBatteryStatus = BatteryState.STATUS_UNKNOWN;
        private float mBatteryCapacity = Float.NaN;

        @Nullable
        private UEventListener mUEventListener;

        MonitoredDeviceState(int deviceId) {
            mDeviceId = deviceId;

            // Load the initial battery state and start monitoring.
            final long eventTime = SystemClock.uptimeMillis();
            updateBatteryState(eventTime);
        }

        // Returns true if the battery state changed since the last time it was updated.
        boolean updateBatteryState(long eventTime) {
            mLastUpdateTime = eventTime;

            final boolean batteryPresenceChanged = mHasBattery != hasBattery(mDeviceId);
            if (batteryPresenceChanged) {
                mHasBattery = !mHasBattery;
                if (mHasBattery) {
                    startMonitoring();
                } else {
                    stopMonitoring();
                }
            }

            final int oldStatus = mBatteryStatus;
            final float oldCapacity = mBatteryCapacity;

            if (mHasBattery) {
                mBatteryStatus = mNative.getBatteryStatus(mDeviceId);
                mBatteryCapacity = mNative.getBatteryCapacity(mDeviceId) / 100.f;
            } else {
                mBatteryStatus = BatteryState.STATUS_UNKNOWN;
                mBatteryCapacity = Float.NaN;
            }

            return batteryPresenceChanged
                    || mBatteryStatus != oldStatus
                    || mBatteryCapacity != oldCapacity;
        }

        private void startMonitoring() {
            final String batteryPath = mNative.getBatteryDevicePath(mDeviceId);
            if (batteryPath == null) {
                return;
            }
            mUEventListener = new UEventListener() {
                @Override
                void onUEvent(long eventTime) {
                    handleBatteryChangeNotification(mDeviceId, eventTime);
                }
            };
            mUEventManager.addListener(mUEventListener, "DEVPATH=" + batteryPath);
        }

        // This must be called when the device is no longer being monitored.
        void stopMonitoring() {
            if (mUEventListener != null) {
                mUEventManager.removeListener(mUEventListener);
                mUEventListener = null;
            }
        }
    }

    // An interface used to change the API of UEventObserver to a more test-friendly format.
    @VisibleForTesting
    interface UEventManager {

        @VisibleForTesting
        abstract class UEventListener {
            private final UEventObserver mObserver = new UEventObserver() {
                @Override
                public void onUEvent(UEvent event) {
                    final long eventTime = SystemClock.uptimeMillis();
                    if (DEBUG) {
                        Slog.d(TAG,
                                "UEventListener: Received UEvent: "
                                        + event + " eventTime: " + eventTime);
                    }
                    UEventListener.this.onUEvent(eventTime);
                }
            };

            abstract void onUEvent(long eventTime);
        }

        default void addListener(UEventListener listener, String match) {
            listener.mObserver.startObserving(match);
        }

        default void removeListener(UEventListener listener) {
            listener.mObserver.stopObserving();
        }
    }
}
