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
import android.hardware.input.IInputDeviceBatteryState;
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
 *
 * Interactions with BatteryController can happen on several threads, including Binder threads, the
 * {@link UEventObserver}'s thread, or its own Handler thread, among others. All public methods, and
 * private methods prefixed with "handle-" (e.g. {@link #handleListeningProcessDied(int)}),
 * serve as entry points for these threads.
 */
final class BatteryController {
    private static final String TAG = BatteryController.class.getSimpleName();

    // To enable these logs, run:
    // 'adb shell setprop log.tag.BatteryController DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @VisibleForTesting
    static final long POLLING_PERIOD_MILLIS = 10_000; // 10 seconds

    private final Object mLock = new Object();
    private final Context mContext;
    private final NativeInputManagerService mNative;
    private final Handler mHandler;
    private final UEventManager mUEventManager;

    // Maps a pid to the registered listener record for that process. There can only be one battery
    // listener per process.
    @GuardedBy("mLock")
    private final ArrayMap<Integer, ListenerRecord> mListenerRecords = new ArrayMap<>();

    // Maps a deviceId that is being monitored to the monitor for the battery state of the device.
    @GuardedBy("mLock")
    private final ArrayMap<Integer, DeviceMonitor> mDeviceMonitors = new ArrayMap<>();

    @GuardedBy("mLock")
    private boolean mIsPolling = false;
    @GuardedBy("mLock")
    private boolean mIsInteractive = true;

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

    public void systemRunning() {
        Objects.requireNonNull(mContext.getSystemService(InputManager.class))
                .registerInputDeviceListener(mInputDeviceListener, mHandler);
    }

    /**
     * Register the battery listener for the given input device and start monitoring its battery
     * state.
     */
    @BinderThread
    public void registerBatteryListener(int deviceId, @NonNull IInputDeviceBatteryListener listener,
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

            DeviceMonitor monitor = mDeviceMonitors.get(deviceId);
            if (monitor == null) {
                // This is the first listener that is monitoring this device.
                monitor = new DeviceMonitor(deviceId);
                mDeviceMonitors.put(deviceId, monitor);
            }

            if (DEBUG) {
                Slog.d(TAG, "Battery listener for pid " + pid
                        + " is monitoring deviceId " + deviceId);
            }

            updatePollingLocked(true /*delayStart*/);
            notifyBatteryListener(listenerRecord, monitor.getBatteryStateForReporting());
        }
    }

    private static void notifyBatteryListener(ListenerRecord listenerRecord, State state) {
        try {
            listenerRecord.mListener.onBatteryStateChanged(state);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to notify listener", e);
        }
        if (DEBUG) {
            Slog.d(TAG, "Notified battery listener from pid " + listenerRecord.mPid
                    + " of state of deviceId " + state.deviceId);
        }
    }

    @GuardedBy("mLock")
    private void notifyAllListenersForDeviceLocked(State state) {
        if (DEBUG) Slog.d(TAG, "Notifying all listeners of battery state: " + state);
        mListenerRecords.forEach((pid, listenerRecord) -> {
            if (listenerRecord.mMonitoredDevices.contains(state.deviceId)) {
                notifyBatteryListener(listenerRecord, state);
            }
        });
    }

    @GuardedBy("mLock")
    private void updatePollingLocked(boolean delayStart) {
        if (mDeviceMonitors.isEmpty() || !mIsInteractive) {
            // Stop polling.
            mIsPolling = false;
            mHandler.removeCallbacks(this::handlePollEvent);
            return;
        }

        if (mIsPolling) {
            return;
        }
        // Start polling.
        mIsPolling = true;
        mHandler.postDelayed(this::handlePollEvent, delayStart ? POLLING_PERIOD_MILLIS : 0);
    }

    private boolean hasBattery(int deviceId) {
        final InputDevice device =
                Objects.requireNonNull(mContext.getSystemService(InputManager.class))
                        .getInputDevice(deviceId);
        return device != null && device.hasBattery();
    }

    @GuardedBy("mLock")
    private DeviceMonitor getDeviceMonitorOrThrowLocked(int deviceId) {
        return Objects.requireNonNull(mDeviceMonitors.get(deviceId),
                "Maps are out of sync: Cannot find device state for deviceId " + deviceId);
    }

    /**
     * Unregister the battery listener for the given input device and stop monitoring its battery
     * state. If there are no other input devices that this listener is monitoring, the listener is
     * removed.
     */
    @BinderThread
    public void unregisterBatteryListener(int deviceId,
            @NonNull IInputDeviceBatteryListener listener, int pid) {
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
            final DeviceMonitor monitor = getDeviceMonitorOrThrowLocked(deviceId);
            monitor.stopMonitoring();
            mDeviceMonitors.remove(deviceId);
        }

        if (listenerRecord.mMonitoredDevices.isEmpty()) {
            // There are no more devices being monitored by this listener.
            listenerRecord.mListener.asBinder().unlinkToDeath(listenerRecord.mDeathRecipient, 0);
            mListenerRecords.remove(pid);
            if (DEBUG) Slog.d(TAG, "Battery listener removed for pid " + pid);
        }

        updatePollingLocked(false /*delayStart*/);
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

    private void handleUEventNotification(int deviceId, long eventTime) {
        synchronized (mLock) {
            final DeviceMonitor monitor = mDeviceMonitors.get(deviceId);
            if (monitor == null) {
                return;
            }
            if (monitor.updateBatteryState(eventTime)) {
                notifyAllListenersForDeviceLocked(monitor.getBatteryStateForReporting());
            }
        }
    }

    private void handlePollEvent() {
        synchronized (mLock) {
            if (!mIsPolling) {
                return;
            }
            final long eventTime = SystemClock.uptimeMillis();
            mDeviceMonitors.forEach((deviceId, monitor) -> {
                // Re-acquire lock in the lambda to silence error-prone build warnings.
                synchronized (mLock) {
                    if (monitor.updateBatteryState(eventTime)) {
                        notifyAllListenersForDeviceLocked(monitor.getBatteryStateForReporting());
                    }
                }
            });
            mHandler.postDelayed(this::handlePollEvent, POLLING_PERIOD_MILLIS);
        }
    }

    /** Gets the current battery state of an input device. */
    public IInputDeviceBatteryState getBatteryState(int deviceId) {
        synchronized (mLock) {
            final long updateTime = SystemClock.uptimeMillis();
            final DeviceMonitor monitor = mDeviceMonitors.get(deviceId);
            if (monitor == null) {
                // The input device's battery is not being monitored by any listener.
                return queryBatteryStateFromNative(deviceId, updateTime);
            }
            // Force the battery state to update, and notify listeners if necessary.
            final boolean stateChanged = monitor.updateBatteryState(updateTime);
            final State state = monitor.getBatteryStateForReporting();
            if (stateChanged) {
                notifyAllListenersForDeviceLocked(state);
            }
            return state;
        }
    }

    public void onInteractiveChanged(boolean interactive) {
        synchronized (mLock) {
            mIsInteractive = interactive;
            updatePollingLocked(false /*delayStart*/);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            final String indent = prefix + "  ";
            final String indent2 = indent + "  ";

            pw.println(prefix + TAG + ":");
            pw.println(indent + "State: Polling = " + mIsPolling
                    + ", Interactive = " + mIsInteractive);

            pw.println(indent + "Listeners: " + mListenerRecords.size() + " battery listeners");
            for (int i = 0; i < mListenerRecords.size(); i++) {
                pw.println(indent2 + i + ": " + mListenerRecords.valueAt(i));
            }

            pw.println(indent + "Device Monitors: " + mDeviceMonitors.size() + " monitors");
            for (int i = 0; i < mDeviceMonitors.size(); i++) {
                pw.println(indent2 + i + ": " + mDeviceMonitors.valueAt(i));
            }
        }
    }

    @SuppressWarnings("all")
    public void monitor() {
        synchronized (mLock) {
            return;
        }
    }

    private final InputManager.InputDeviceListener mInputDeviceListener =
            new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {}

        @Override
        public void onInputDeviceRemoved(int deviceId) {}

        @Override
        public void onInputDeviceChanged(int deviceId) {
            synchronized (mLock) {
                final DeviceMonitor monitor = mDeviceMonitors.get(deviceId);
                if (monitor == null) {
                    return;
                }
                final long eventTime = SystemClock.uptimeMillis();
                if (monitor.updateBatteryState(eventTime)) {
                    notifyAllListenersForDeviceLocked(monitor.getBatteryStateForReporting());
                }
            }
        }
    };

    // A record of a registered battery listener from one process.
    private class ListenerRecord {
        public final int mPid;
        public final IInputDeviceBatteryListener mListener;
        public final IBinder.DeathRecipient mDeathRecipient;
        // The set of deviceIds that are currently being monitored by this listener.
        public final Set<Integer> mMonitoredDevices;

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

    // Queries the battery state of an input device from native code.
    private State queryBatteryStateFromNative(int deviceId, long updateTime) {
        final boolean isPresent = hasBattery(deviceId);
        return new State(
                deviceId,
                updateTime,
                isPresent,
                isPresent ? mNative.getBatteryStatus(deviceId) : BatteryState.STATUS_UNKNOWN,
                isPresent ? mNative.getBatteryCapacity(deviceId) / 100.f : Float.NaN);
    }

    // Holds the state of an InputDevice for which battery changes are currently being monitored.
    private class DeviceMonitor {
        @NonNull
        private State mState;

        @Nullable
        private UEventListener mUEventListener;

        DeviceMonitor(int deviceId) {
            mState = new State(deviceId);

            // Load the initial battery state and start monitoring.
            final long eventTime = SystemClock.uptimeMillis();
            updateBatteryState(eventTime);
        }

        // Returns true if the battery state changed since the last time it was updated.
        public boolean updateBatteryState(long updateTime) {
            mState.updateTime = updateTime;

            final State updatedState = queryBatteryStateFromNative(mState.deviceId, updateTime);
            if (mState.equals(updatedState)) {
                return false;
            }
            if (mState.isPresent != updatedState.isPresent) {
                if (updatedState.isPresent) {
                    startMonitoring();
                } else {
                    stopMonitoring();
                }
            }
            mState = updatedState;
            return true;
        }

        private void startMonitoring() {
            final String batteryPath = mNative.getBatteryDevicePath(mState.deviceId);
            if (batteryPath == null) {
                return;
            }
            final int deviceId = mState.deviceId;
            mUEventListener = new UEventListener() {
                @Override
                public void onUEvent(long eventTime) {
                    handleUEventNotification(deviceId, eventTime);
                }
            };
            mUEventManager.addListener(mUEventListener, "DEVPATH=" + batteryPath);
        }

        // This must be called when the device is no longer being monitored.
        public void stopMonitoring() {
            if (mUEventListener != null) {
                mUEventManager.removeListener(mUEventListener);
                mUEventListener = null;
            }
        }

        // Returns the current battery state that can be used to notify listeners BatteryController.
        public State getBatteryStateForReporting() {
            return new State(mState);
        }

        @Override
        public String toString() {
            return "state=" + mState
                    + ", uEventListener=" + (mUEventListener != null ? "added" : "none");
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

            public abstract void onUEvent(long eventTime);
        }

        default void addListener(UEventListener listener, String match) {
            listener.mObserver.startObserving(match);
        }

        default void removeListener(UEventListener listener) {
            listener.mObserver.stopObserving();
        }
    }

    // Helper class that adds copying and printing functionality to IInputDeviceBatteryState.
    private static class State extends IInputDeviceBatteryState {

        State(int deviceId) {
            initialize(deviceId, 0 /*updateTime*/, false /*isPresent*/, BatteryState.STATUS_UNKNOWN,
                    Float.NaN /*capacity*/);
        }

        State(IInputDeviceBatteryState s) {
            initialize(s.deviceId, s.updateTime, s.isPresent, s.status, s.capacity);
        }

        State(int deviceId, long updateTime, boolean isPresent, int status, float capacity) {
            initialize(deviceId, updateTime, isPresent, status, capacity);
        }

        private void initialize(int deviceId, long updateTime, boolean isPresent, int status,
                float capacity) {
            this.deviceId = deviceId;
            this.updateTime = updateTime;
            this.isPresent = isPresent;
            this.status = status;
            this.capacity = capacity;
        }

        @Override
        public String toString() {
            return "BatteryState{deviceId=" + deviceId + ", updateTime=" + updateTime
                    + ", isPresent=" + isPresent + ", status=" + status + ", capacity=" + capacity
                    + " }";
        }
    }
}
