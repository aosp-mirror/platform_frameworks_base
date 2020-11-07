/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.devicestate;

import static android.Manifest.permission.CONTROL_DEVICE_STATE;
import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.devicestate.IDeviceStateManager;
import android.hardware.devicestate.IDeviceStateManagerCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;
import com.android.server.policy.DeviceStatePolicyImpl;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * A system service that manages the state of a device with user-configurable hardware like a
 * foldable phone.
 * <p>
 * Device state is an abstract concept that allows mapping the current state of the device to the
 * state of the system. For example, system services (like
 * {@link com.android.server.display.DisplayManagerService display manager} and
 * {@link com.android.server.wm.WindowManagerService window manager}) and system UI may have
 * different behaviors depending on the physical state of the device. This is useful for
 * variable-state devices, like foldable or rollable devices, that can be configured by users into
 * differing hardware states, which each may have a different expected use case.
 * </p>
 * <p>
 * The {@link DeviceStateManagerService} is responsible for receiving state change requests from
 * the {@link DeviceStateProvider} to modify the current device state and communicating with the
 * {@link DeviceStatePolicy policy} to ensure the system is configured to match the requested state.
 * </p>
 *
 * @see DeviceStatePolicy
 */
public final class DeviceStateManagerService extends SystemService {
    private static final String TAG = "DeviceStateManagerService";
    private static final boolean DEBUG = false;

    private final Object mLock = new Object();
    @NonNull
    private final DeviceStatePolicy mDeviceStatePolicy;
    @NonNull
    private final BinderService mBinderService;

    @GuardedBy("mLock")
    private IntArray mSupportedDeviceStates;

    // The current committed device state.
    @GuardedBy("mLock")
    private int mCommittedState = INVALID_DEVICE_STATE;
    // The device state that is currently awaiting callback from the policy to be committed.
    @GuardedBy("mLock")
    private int mPendingState = INVALID_DEVICE_STATE;
    // Whether or not the policy is currently waiting to be notified of the current pending state.
    @GuardedBy("mLock")
    private boolean mIsPolicyWaitingForState = false;
    // The device state that is currently requested and is next to be configured and committed.
    // Can be overwritten by an override state value if requested.
    @GuardedBy("mLock")
    private int mRequestedState = INVALID_DEVICE_STATE;
    // The most recently requested override state, or INVALID_DEVICE_STATE if no override is
    // requested.
    @GuardedBy("mLock")
    private int mRequestedOverrideState = INVALID_DEVICE_STATE;

    // List of registered callbacks indexed by process id.
    @GuardedBy("mLock")
    private final SparseArray<CallbackRecord> mCallbacks = new SparseArray<>();

    public DeviceStateManagerService(@NonNull Context context) {
        this(context, new DeviceStatePolicyImpl());
    }

    @VisibleForTesting
    DeviceStateManagerService(@NonNull Context context, @NonNull DeviceStatePolicy policy) {
        super(context);
        mDeviceStatePolicy = policy;
        mDeviceStatePolicy.getDeviceStateProvider().setListener(new DeviceStateProviderListener());
        mBinderService = new BinderService();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.DEVICE_STATE_SERVICE, mBinderService);
    }

    /**
     * Returns the current state the system is in. Note that the system may be in the process of
     * configuring a different state.
     *
     * @see #getPendingState()
     */
    int getCommittedState() {
        synchronized (mLock) {
            return mCommittedState;
        }
    }

    /**
     * Returns the state the system is currently configuring, or {@link #INVALID_DEVICE_STATE} if
     * the system is not in the process of configuring a state.
     */
    @VisibleForTesting
    int getPendingState() {
        synchronized (mLock) {
            return mPendingState;
        }
    }

    /**
     * Returns the requested state. The service will configure the device to match the requested
     * state when possible.
     */
    int getRequestedState() {
        synchronized (mLock) {
            return mRequestedState;
        }
    }

    /**
     * Overrides the current device state with the provided state.
     *
     * @return {@code true} if the override state is valid and supported, {@code false} otherwise.
     */
    boolean setOverrideState(int overrideState) {
        if (getContext().checkCallingOrSelfPermission(CONTROL_DEVICE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + CONTROL_DEVICE_STATE);
        }

        synchronized (mLock) {
            if (overrideState != INVALID_DEVICE_STATE && !isSupportedStateLocked(overrideState)) {
                return false;
            }

            mRequestedOverrideState = overrideState;
            updatePendingStateLocked();
        }

        notifyPolicyIfNeeded();
        return true;
    }

    /**
     * Clears an override state set with {@link #setOverrideState(int)}.
     */
    void clearOverrideState() {
        setOverrideState(INVALID_DEVICE_STATE);
    }

    /**
     * Returns the current requested override state, or {@link #INVALID_DEVICE_STATE} is no override
     * state is requested.
     */
    int getOverrideState() {
        synchronized (mLock) {
            return mRequestedOverrideState;
        }
    }

    /** Returns the list of currently supported device states. */
    int[] getSupportedStates() {
        synchronized (mLock) {
            // Copy array to prevent external modification of internal state.
            return Arrays.copyOf(mSupportedDeviceStates.toArray(), mSupportedDeviceStates.size());
        }
    }

    @VisibleForTesting
    IDeviceStateManager getBinderService() {
        return mBinderService;
    }

    private void updateSupportedStates(int[] supportedDeviceStates) {
        // Must ensure sorted as isSupportedStateLocked() impl uses binary search.
        Arrays.sort(supportedDeviceStates, 0, supportedDeviceStates.length);
        synchronized (mLock) {
            mSupportedDeviceStates = IntArray.wrap(supportedDeviceStates);

            if (mRequestedState != INVALID_DEVICE_STATE
                    && !isSupportedStateLocked(mRequestedState)) {
                // The current requested state is no longer valid. We'll clear it here, though
                // we won't actually update the current state until a callback comes from the
                // provider with the most recent state.
                mRequestedState = INVALID_DEVICE_STATE;
            }
            if (mRequestedOverrideState != INVALID_DEVICE_STATE
                    && !isSupportedStateLocked(mRequestedOverrideState)) {
                // The current override state is no longer valid. We'll clear it here and update
                // the committed state if necessary.
                mRequestedOverrideState = INVALID_DEVICE_STATE;
            }
            updatePendingStateLocked();
        }

        notifyPolicyIfNeeded();
    }

    /**
     * Returns {@code true} if the provided state is supported. Requires that
     * {@link #mSupportedDeviceStates} is sorted prior to calling.
     */
    private boolean isSupportedStateLocked(int state) {
        return mSupportedDeviceStates.binarySearch(state) >= 0;
    }

    /**
     * Requests that the system enter the provided {@code state}. The request may not be honored
     * under certain conditions, for example if the provided state is not supported.
     *
     * @see #isSupportedStateLocked(int)
     */
    private void requestState(int state) {
        synchronized (mLock) {
            if (isSupportedStateLocked(state)) {
                mRequestedState = state;
            }
            updatePendingStateLocked();
        }

        notifyPolicyIfNeeded();
    }

    /**
     * Tries to update the current pending state with the current requested state. Must call
     * {@link #notifyPolicyIfNeeded()} to actually notify the policy that the state is being
     * changed.
     */
    private void updatePendingStateLocked() {
        if (mPendingState != INVALID_DEVICE_STATE) {
            // Have pending state, can not configure a new state until the state is committed.
            return;
        }

        int stateToConfigure;
        if (mRequestedOverrideState != INVALID_DEVICE_STATE) {
            stateToConfigure = mRequestedOverrideState;
        } else {
            stateToConfigure = mRequestedState;
        }

        if (stateToConfigure == INVALID_DEVICE_STATE) {
            // No currently requested state.
            return;
        }

        if (stateToConfigure == mCommittedState) {
            // The state requesting to be committed already matches the current committed state.
            return;
        }

        mPendingState = stateToConfigure;
        mIsPolicyWaitingForState = true;
    }

    /**
     * Notifies the policy to configure the supplied state. Should not be called with {@link #mLock}
     * held.
     */
    private void notifyPolicyIfNeeded() {
        if (Thread.holdsLock(mLock)) {
            Throwable error = new Throwable("Attempting to notify DeviceStatePolicy with service"
                    + " lock held");
            error.fillInStackTrace();
            Slog.w(TAG, error);
        }
        int state;
        synchronized (mLock) {
            if (!mIsPolicyWaitingForState) {
                return;
            }
            mIsPolicyWaitingForState = false;
            state = mPendingState;
        }

        if (DEBUG) {
            Slog.d(TAG, "Notifying policy to configure state: " + state);
        }
        mDeviceStatePolicy.configureDeviceForState(state, this::commitPendingState);
    }

    /**
     * Commits the current pending state after a callback from the {@link DeviceStatePolicy}.
     *
     * <pre>
     *              -------------    -----------              -------------
     * Provider ->  | Requested | -> | Pending | -> Policy -> | Committed |
     *              -------------    -----------              -------------
     * </pre>
     * <p>
     * When a new state is requested it immediately enters the requested state. Once the policy is
     * available to accept a new state, which could also be immediately if there is no current
     * pending state at the point of request, the policy is notified and a callback is provided to
     * trigger the state to be committed.
     * </p>
     */
    private void commitPendingState() {
        // Update the current state.
        int newState;
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Committing state: " + mPendingState);
            }
            mCommittedState = mPendingState;
            newState = mCommittedState;
            mPendingState = INVALID_DEVICE_STATE;
            updatePendingStateLocked();
        }

        // Notify callbacks of a change.
        notifyDeviceStateChanged(newState);

        // Try to configure the next state if needed.
        notifyPolicyIfNeeded();
    }

    private void notifyDeviceStateChanged(int deviceState) {
        if (Thread.holdsLock(mLock)) {
            throw new IllegalStateException(
                    "Attempting to notify callbacks with service lock held.");
        }

        // Grab the lock and copy the callbacks.
        ArrayList<CallbackRecord> callbacks;
        synchronized (mLock) {
            if (mCallbacks.size() == 0) {
                return;
            }

            callbacks = new ArrayList<>();
            for (int i = 0; i < mCallbacks.size(); i++) {
                callbacks.add(mCallbacks.valueAt(i));
            }
        }

        // After releasing the lock, send the notifications out.
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).notifyDeviceStateAsync(deviceState);
        }
    }

    private void registerCallbackInternal(IDeviceStateManagerCallback callback, int callingPid) {
        int currentState;
        CallbackRecord record;
        // Grab the lock to register the callback and get the current state.
        synchronized (mLock) {
            if (mCallbacks.contains(callingPid)) {
                throw new SecurityException("The calling process has already registered an"
                        + " IDeviceStateManagerCallback.");
            }

            record = new CallbackRecord(callback, callingPid);
            try {
                callback.asBinder().linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }

            mCallbacks.put(callingPid, record);
            currentState = mCommittedState;
        }

        // Notify the callback of the state at registration.
        record.notifyDeviceStateAsync(currentState);
    }

    private void unregisterCallbackInternal(CallbackRecord record) {
        synchronized (mLock) {
            mCallbacks.remove(record.mPid);
        }
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("DEVICE STATE MANAGER (dumpsys device_state)");

        synchronized (mLock) {
            pw.println("  mCommittedState=" + toString(mCommittedState));
            pw.println("  mPendingState=" + toString(mPendingState));
            pw.println("  mRequestedState=" + toString(mRequestedState));
            pw.println("  mRequestedOverrideState=" + toString(mRequestedOverrideState));

            final int callbackCount = mCallbacks.size();
            pw.println();
            pw.println("Callbacks: size=" + callbackCount);
            for (int i = 0; i < callbackCount; i++) {
                CallbackRecord callback = mCallbacks.valueAt(i);
                pw.println("  " + i + ": mPid=" + callback.mPid);
            }
        }
    }

    private String toString(int state) {
        return state == INVALID_DEVICE_STATE ? "(none)" : String.valueOf(state);
    }

    private final class DeviceStateProviderListener implements DeviceStateProvider.Listener {
        @Override
        public void onSupportedDeviceStatesChanged(int[] newDeviceStates) {
            for (int i = 0; i < newDeviceStates.length; i++) {
                if (newDeviceStates[i] < 0) {
                    throw new IllegalArgumentException("Supported device states includes invalid"
                            + " value: " + newDeviceStates[i]);
                }
            }

            updateSupportedStates(newDeviceStates);
        }

        @Override
        public void onStateChanged(int state) {
            if (state < 0) {
                throw new IllegalArgumentException("Invalid state: " + state);
            }

            requestState(state);
        }
    }

    private final class CallbackRecord implements IBinder.DeathRecipient {
        private final IDeviceStateManagerCallback mCallback;
        private final int mPid;

        CallbackRecord(IDeviceStateManagerCallback callback, int pid) {
            mCallback = callback;
            mPid = pid;
        }

        @Override
        public void binderDied() {
            unregisterCallbackInternal(this);
        }

        public void notifyDeviceStateAsync(int devicestate) {
            try {
                mCallback.onDeviceStateChanged(devicestate);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid + " that device state changed.",
                        ex);
            }
        }
    }

    /** Implementation of {@link IDeviceStateManager} published as a binder service. */
    private final class BinderService extends IDeviceStateManager.Stub {
        @Override // Binder call
        public void registerCallback(IDeviceStateManagerCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("Device state callback must not be null.");
            }

            final int callingPid = Binder.getCallingPid();
            final long token = Binder.clearCallingIdentity();
            try {
                registerCallbackInternal(callback, callingPid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver result) {
            new DeviceStateManagerShellCommand(DeviceStateManagerService.this)
                    .exec(this, in, out, err, args, callback, result);
        }

        @Override // Binder call
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

            final long token = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
