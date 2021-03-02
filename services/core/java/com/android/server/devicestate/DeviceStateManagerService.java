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
import static android.hardware.devicestate.DeviceStateManager.MAXIMUM_DEVICE_STATE;
import static android.hardware.devicestate.DeviceStateManager.MINIMUM_DEVICE_STATE;
import static android.hardware.devicestate.DeviceStateRequest.FLAG_CANCEL_WHEN_BASE_CHANGES;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.devicestate.DeviceStateInfo;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.IDeviceStateManager;
import android.hardware.devicestate.IDeviceStateManagerCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.ArrayMap;
import android.util.ArraySet;
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
import java.util.Optional;

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
 * The service also provides the {@link DeviceStateManager} API allowing clients to listen for
 * changes in device state and submit requests to override the device state provided by the
 * {@link DeviceStateProvider}.
 *
 * @see DeviceStatePolicy
 * @see DeviceStateManager
 */
public final class DeviceStateManagerService extends SystemService {
    private static final String TAG = "DeviceStateManagerService";
    private static final boolean DEBUG = false;
    // The device state to use as a placeholder before callback from the DeviceStateProvider occurs.
    private static final DeviceState UNSPECIFIED_DEVICE_STATE =
            new DeviceState(MINIMUM_DEVICE_STATE, "UNSPECIFIED");

    private final Object mLock = new Object();
    @NonNull
    private final DeviceStatePolicy mDeviceStatePolicy;
    @NonNull
    private final BinderService mBinderService;

    // All supported device states keyed by identifier.
    @GuardedBy("mLock")
    private SparseArray<DeviceState> mDeviceStates = new SparseArray<>();

    // The current committed device state. The default of UNSPECIFIED_DEVICE_STATE will be replaced
    // by the current state after the initial callback from the DeviceStateProvider.
    @GuardedBy("mLock")
    @NonNull
    private DeviceState mCommittedState = UNSPECIFIED_DEVICE_STATE;
    // The device state that is currently awaiting callback from the policy to be committed.
    @GuardedBy("mLock")
    @NonNull
    private Optional<DeviceState> mPendingState = Optional.empty();
    // Whether or not the policy is currently waiting to be notified of the current pending state.
    @GuardedBy("mLock")
    private boolean mIsPolicyWaitingForState = false;

    // The device state that is set by the device state provider.
    @GuardedBy("mLock")
    @NonNull
    private DeviceState mBaseState = UNSPECIFIED_DEVICE_STATE;

    // List of processes registered to receive notifications about changes to device state and
    // request status indexed by process id.
    @GuardedBy("mLock")
    private final SparseArray<ProcessRecord> mProcessRecords = new SparseArray<>();
    // List of override requests with the highest precedence request at the end.
    @GuardedBy("mLock")
    private final ArrayList<OverrideRequestRecord> mRequestRecords = new ArrayList<>();
    // Set of override requests that are pending a call to notifyStatusIfNeeded() to be notified
    // of a change in status.
    @GuardedBy("mLock")
    private final ArraySet<OverrideRequestRecord> mRequestsPendingStatusChange = new ArraySet<>();

    public DeviceStateManagerService(@NonNull Context context) {
        this(context, new DeviceStatePolicyImpl(context));
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
    @NonNull
    DeviceState getCommittedState() {
        synchronized (mLock) {
            return mCommittedState;
        }
    }

    /**
     * Returns the state the system is currently configuring, or {@link Optional#empty()} if the
     * system is not in the process of configuring a state.
     */
    @VisibleForTesting
    @NonNull
    Optional<DeviceState> getPendingState() {
        synchronized (mLock) {
            return mPendingState;
        }
    }

    /**
     * Returns the base state. The service will configure the device to match the base state when
     * there is no active request to override the base state.
     *
     * @see #getOverrideState()
     */
    @NonNull
    DeviceState getBaseState() {
        synchronized (mLock) {
            return mBaseState;
        }
    }

    /**
     * Returns the current override state, or {@link Optional#empty()} if no override state is
     * requested. If an override states is present, the returned state will take precedence over
     * the base state returned from {@link #getBaseState()}.
     */
    @NonNull
    Optional<DeviceState> getOverrideState() {
        synchronized (mLock) {
            if (mRequestRecords.isEmpty()) {
                return Optional.empty();
            }

            OverrideRequestRecord topRequest = mRequestRecords.get(mRequestRecords.size() - 1);
            return Optional.of(topRequest.mRequestedState);
        }
    }

    /** Returns the list of currently supported device states. */
    DeviceState[] getSupportedStates() {
        synchronized (mLock) {
            DeviceState[] supportedStates = new DeviceState[mDeviceStates.size()];
            for (int i = 0; i < supportedStates.length; i++) {
                supportedStates[i] = mDeviceStates.valueAt(i);
            }
            return supportedStates;
        }
    }

    /** Returns the list of currently supported device state identifiers. */
    private int[] getSupportedStateIdentifiers() {
        synchronized (mLock) {
            return getSupportedStateIdentifiersLocked();
        }
    }

    /** Returns the list of currently supported device state identifiers. */
    private int[] getSupportedStateIdentifiersLocked() {
        int[] supportedStates = new int[mDeviceStates.size()];
        for (int i = 0; i < supportedStates.length; i++) {
            supportedStates[i] = mDeviceStates.valueAt(i).getIdentifier();
        }
        return supportedStates;
    }

    @NonNull
    private DeviceStateInfo getDeviceStateInfoLocked() {
        final int[] supportedStates = getSupportedStateIdentifiersLocked();
        final int baseState = mBaseState.getIdentifier();
        final int currentState = mCommittedState.getIdentifier();

        return new DeviceStateInfo(supportedStates, baseState, currentState);
    }

    @VisibleForTesting
    IDeviceStateManager getBinderService() {
        return mBinderService;
    }

    private void updateSupportedStates(DeviceState[] supportedDeviceStates) {
        boolean updatedPendingState;
        synchronized (mLock) {
            final int[] oldStateIdentifiers = getSupportedStateIdentifiersLocked();

            mDeviceStates.clear();
            for (int i = 0; i < supportedDeviceStates.length; i++) {
                DeviceState state = supportedDeviceStates[i];
                mDeviceStates.put(state.getIdentifier(), state);
            }

            final int[] newStateIdentifiers = getSupportedStateIdentifiersLocked();
            if (Arrays.equals(oldStateIdentifiers, newStateIdentifiers)) {
                return;
            }

            final int requestSize = mRequestRecords.size();
            for (int i = 0; i < requestSize; i++) {
                OverrideRequestRecord request = mRequestRecords.get(i);
                if (!isSupportedStateLocked(request.mRequestedState.getIdentifier())) {
                    request.setStatusLocked(OverrideRequestRecord.STATUS_CANCELED);
                }
            }

            updatedPendingState = updatePendingStateLocked();
        }

        if (!updatedPendingState) {
            // If the change in the supported states didn't result in a change of the pending state
            // commitPendingState() will never be called and the callbacks will never be notified
            // of the change.
            notifyDeviceStateInfoChanged();
        }

        notifyRequestsOfStatusChangeIfNeeded();
        notifyPolicyIfNeeded();
    }

    /**
     * Returns {@code true} if the provided state is supported. Requires that
     * {@link #mDeviceStates} is sorted prior to calling.
     */
    private boolean isSupportedStateLocked(int identifier) {
        return mDeviceStates.contains(identifier);
    }

    /**
     * Returns the {@link DeviceState} with the supplied {@code identifier}, or {@code null} if
     * there is no device state with the identifier.
     */
    @Nullable
    private Optional<DeviceState> getStateLocked(int identifier) {
        return Optional.ofNullable(mDeviceStates.get(identifier));
    }

    /**
     * Sets the base state.
     *
     * @throws IllegalArgumentException if the {@code identifier} is not a supported state.
     *
     * @see #isSupportedStateLocked(int)
     */
    private void setBaseState(int identifier) {
        boolean updatedPendingState;
        synchronized (mLock) {
            final Optional<DeviceState> baseStateOptional = getStateLocked(identifier);
            if (!baseStateOptional.isPresent()) {
                throw new IllegalArgumentException("Base state is not supported");
            }

            final DeviceState baseState = baseStateOptional.get();
            if (mBaseState.equals(baseState)) {
                // Base state hasn't changed. Nothing to do.
                return;
            }
            mBaseState = baseState;

            final int requestSize = mRequestRecords.size();
            for (int i = 0; i < requestSize; i++) {
                OverrideRequestRecord request = mRequestRecords.get(i);
                if ((request.mFlags & FLAG_CANCEL_WHEN_BASE_CHANGES) > 0) {
                    request.setStatusLocked(OverrideRequestRecord.STATUS_CANCELED);
                }
            }

            updatedPendingState = updatePendingStateLocked();
        }

        if (!updatedPendingState) {
            // If the change in base state didn't result in a change of the pending state
            // commitPendingState() will never be called and the callbacks will never be notified
            // of the change.
            notifyDeviceStateInfoChanged();
        }

        notifyRequestsOfStatusChangeIfNeeded();
        notifyPolicyIfNeeded();
    }

    /**
     * Tries to update the current pending state with the current requested state. Must call
     * {@link #notifyPolicyIfNeeded()} to actually notify the policy that the state is being
     * changed.
     *
     * @return {@code true} if the pending state has changed as a result of this call, {@code false}
     * otherwise.
     */
    private boolean updatePendingStateLocked() {
        if (mPendingState.isPresent()) {
            // Have pending state, can not configure a new state until the state is committed.
            return false;
        }

        final DeviceState stateToConfigure;
        if (!mRequestRecords.isEmpty()) {
            stateToConfigure = mRequestRecords.get(mRequestRecords.size() - 1).mRequestedState;
        } else if (isSupportedStateLocked(mBaseState.getIdentifier())) {
            // Base state could have recently become unsupported after a change in supported states.
            stateToConfigure = mBaseState;
        } else {
            stateToConfigure = null;
        }

        if (stateToConfigure == null) {
            // No currently requested state.
            return false;
        }

        if (stateToConfigure.equals(mCommittedState)) {
            // The state requesting to be committed already matches the current committed state.
            return false;
        }

        mPendingState = Optional.of(stateToConfigure);
        mIsPolicyWaitingForState = true;
        return true;
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
            state = mPendingState.get().getIdentifier();
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
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Committing state: " + mPendingState);
            }
            mCommittedState = mPendingState.get();

            if (!mRequestRecords.isEmpty()) {
                final OverrideRequestRecord topRequest =
                        mRequestRecords.get(mRequestRecords.size() - 1);
                topRequest.setStatusLocked(OverrideRequestRecord.STATUS_ACTIVE);
            }

            mPendingState = Optional.empty();
            updatePendingStateLocked();
        }

        // Notify callbacks of a change.
        notifyDeviceStateInfoChanged();

        // Notify the top request that it's active.
        notifyRequestsOfStatusChangeIfNeeded();

        // Try to configure the next state if needed.
        notifyPolicyIfNeeded();
    }

    private void notifyDeviceStateInfoChanged() {
        if (Thread.holdsLock(mLock)) {
            throw new IllegalStateException(
                    "Attempting to notify callbacks with service lock held.");
        }

        // Grab the lock and copy the process records and the current info.
        ArrayList<ProcessRecord> registeredProcesses;
        DeviceStateInfo info;
        synchronized (mLock) {
            if (mProcessRecords.size() == 0) {
                return;
            }

            registeredProcesses = new ArrayList<>();
            for (int i = 0; i < mProcessRecords.size(); i++) {
                registeredProcesses.add(mProcessRecords.valueAt(i));
            }

            info = getDeviceStateInfoLocked();
        }

        // After releasing the lock, send the notifications out.
        for (int i = 0; i < registeredProcesses.size(); i++) {
            registeredProcesses.get(i).notifyDeviceStateInfoAsync(info);
        }
    }

    /**
     * Notifies all dirty requests (requests that have a change in status, but have not yet been
     * notified) that their status has changed.
     */
    private void notifyRequestsOfStatusChangeIfNeeded() {
        if (Thread.holdsLock(mLock)) {
            throw new IllegalStateException(
                    "Attempting to notify requests with service lock held.");
        }

        ArraySet<OverrideRequestRecord> dirtyRequests;
        synchronized (mLock) {
            if (mRequestsPendingStatusChange.isEmpty()) {
                return;
            }

            dirtyRequests = new ArraySet<>(mRequestsPendingStatusChange);
            mRequestsPendingStatusChange.clear();
        }

        // After releasing the lock, send the notifications out.
        for (int i = 0; i < dirtyRequests.size(); i++) {
            dirtyRequests.valueAt(i).notifyStatusIfNeeded();
        }
    }

    private void registerProcess(int pid, IDeviceStateManagerCallback callback) {
        synchronized (mLock) {
            if (mProcessRecords.contains(pid)) {
                throw new SecurityException("The calling process has already registered an"
                        + " IDeviceStateManagerCallback.");
            }

            ProcessRecord record = new ProcessRecord(callback, pid);
            try {
                callback.asBinder().linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            mProcessRecords.put(pid, record);
        }
    }

    private void handleProcessDied(ProcessRecord processRecord) {
        synchronized (mLock) {
            // Cancel all requests from this process.
            final int requestCount = processRecord.mRequestRecords.size();
            for (int i = 0; i < requestCount; i++) {
                final OverrideRequestRecord request = processRecord.mRequestRecords.valueAt(i);
                // Cancel the request but don't mark it as dirty since there's no need to send
                // notifications if the process has died.
                request.setStatusLocked(OverrideRequestRecord.STATUS_CANCELED,
                        false /* markDirty */);
            }

            mProcessRecords.remove(processRecord.mPid);

            updatePendingStateLocked();
        }

        notifyPolicyIfNeeded();
    }

    private void requestStateInternal(int state, int flags, int callingPid,
            @NonNull IBinder token) {
        synchronized (mLock) {
            final ProcessRecord processRecord = mProcessRecords.get(callingPid);
            if (processRecord == null) {
                throw new IllegalStateException("Process " + callingPid
                        + " has no registered callback.");
            }

            if (processRecord.mRequestRecords.get(token) != null) {
                throw new IllegalStateException("Request has already been made for the supplied"
                        + " token: " + token);
            }

            final Optional<DeviceState> deviceState = getStateLocked(state);
            if (!deviceState.isPresent()) {
                throw new IllegalArgumentException("Requested state: " + state
                        + " is not supported.");
            }

            OverrideRequestRecord topRecord = mRequestRecords.isEmpty()
                    ? null : mRequestRecords.get(mRequestRecords.size() - 1);
            if (topRecord != null) {
                topRecord.setStatusLocked(OverrideRequestRecord.STATUS_SUSPENDED);
            }

            final OverrideRequestRecord request =
                    new OverrideRequestRecord(processRecord, token, deviceState.get(), flags);
            mRequestRecords.add(request);
            processRecord.mRequestRecords.put(request.mToken, request);
            // We don't set the status of the new request to ACTIVE here as it will be set in
            // commitPendingState().

            updatePendingStateLocked();
        }

        notifyRequestsOfStatusChangeIfNeeded();
        notifyPolicyIfNeeded();
    }

    private void cancelRequestInternal(int callingPid, @NonNull IBinder token) {
        synchronized (mLock) {
            final ProcessRecord processRecord = mProcessRecords.get(callingPid);
            if (processRecord == null) {
                throw new IllegalStateException("Process " + callingPid
                        + " has no registered callback.");
            }

            OverrideRequestRecord request = processRecord.mRequestRecords.get(token);
            if (request == null) {
                throw new IllegalStateException("No known request for the given token");
            }

            request.setStatusLocked(OverrideRequestRecord.STATUS_CANCELED);

            updatePendingStateLocked();
        }

        notifyRequestsOfStatusChangeIfNeeded();
        notifyPolicyIfNeeded();
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("DEVICE STATE MANAGER (dumpsys device_state)");

        synchronized (mLock) {
            pw.println("  mCommittedState=" + mCommittedState);
            pw.println("  mPendingState=" + mPendingState);
            pw.println("  mBaseState=" + mBaseState);
            pw.println("  mOverrideState=" + getOverrideState());

            final int processCount = mProcessRecords.size();
            pw.println();
            pw.println("Registered processes: size=" + processCount);
            for (int i = 0; i < processCount; i++) {
                ProcessRecord processRecord = mProcessRecords.valueAt(i);
                pw.println("  " + i + ": mPid=" + processRecord.mPid);
            }

            final int requestCount = mRequestRecords.size();
            pw.println();
            pw.println("Override requests: size=" + requestCount);
            for (int i = 0; i < requestCount; i++) {
                OverrideRequestRecord requestRecord = mRequestRecords.get(i);
                pw.println("  " + i + ": mPid=" + requestRecord.mProcessRecord.mPid
                        + ", mRequestedState=" + requestRecord.mRequestedState
                        + ", mFlags=" + requestRecord.mFlags
                        + ", mStatus=" + requestRecord.statusToString(requestRecord.mStatus));
            }
        }
    }

    private final class DeviceStateProviderListener implements DeviceStateProvider.Listener {
        @Override
        public void onSupportedDeviceStatesChanged(DeviceState[] newDeviceStates) {
            if (newDeviceStates.length == 0) {
                throw new IllegalArgumentException("Supported device states must not be empty");
            }
            updateSupportedStates(newDeviceStates);
        }

        @Override
        public void onStateChanged(
                @IntRange(from = MINIMUM_DEVICE_STATE, to = MAXIMUM_DEVICE_STATE) int identifier) {
            if (identifier < MINIMUM_DEVICE_STATE || identifier > MAXIMUM_DEVICE_STATE) {
                throw new IllegalArgumentException("Invalid identifier: " + identifier);
            }

            setBaseState(identifier);
        }
    }

    private final class ProcessRecord implements IBinder.DeathRecipient {
        private final IDeviceStateManagerCallback mCallback;
        private final int mPid;

        private final ArrayMap<IBinder, OverrideRequestRecord> mRequestRecords = new ArrayMap<>();

        ProcessRecord(IDeviceStateManagerCallback callback, int pid) {
            mCallback = callback;
            mPid = pid;
        }

        @Override
        public void binderDied() {
            handleProcessDied(this);
        }

        public void notifyDeviceStateInfoAsync(@NonNull DeviceStateInfo info) {
            try {
                mCallback.onDeviceStateInfoChanged(info);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid + " that device state changed.",
                        ex);
            }
        }

        public void notifyRequestActiveAsync(OverrideRequestRecord request) {
            try {
                mCallback.onRequestActive(request.mToken);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid + " that request state changed.",
                        ex);
            }
        }

        public void notifyRequestSuspendedAsync(OverrideRequestRecord request) {
            try {
                mCallback.onRequestSuspended(request.mToken);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid + " that request state changed.",
                        ex);
            }
        }

        public void notifyRequestCanceledAsync(OverrideRequestRecord request) {
            try {
                mCallback.onRequestCanceled(request.mToken);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid + " that request state changed.",
                        ex);
            }
        }
    }

    /** A record describing a request to override the state of the device. */
    private final class OverrideRequestRecord {
        public static final int STATUS_UNKNOWN = 0;
        public static final int STATUS_ACTIVE = 1;
        public static final int STATUS_SUSPENDED = 2;
        public static final int STATUS_CANCELED = 3;

        @Nullable
        public String statusToString(int status) {
            switch (status) {
                case STATUS_ACTIVE:
                    return "ACTIVE";
                case STATUS_SUSPENDED:
                    return "SUSPENDED";
                case STATUS_CANCELED:
                    return "CANCELED";
                case STATUS_UNKNOWN:
                    return "UNKNOWN";
                default:
                    return null;
            }
        }

        private final ProcessRecord mProcessRecord;
        @NonNull
        private final IBinder mToken;
        @NonNull
        private final DeviceState mRequestedState;
        private final int mFlags;

        private int mStatus = STATUS_UNKNOWN;
        private int mLastNotifiedStatus = STATUS_UNKNOWN;

        OverrideRequestRecord(@NonNull ProcessRecord processRecord, @NonNull IBinder token,
                @NonNull DeviceState requestedState, int flags) {
            mProcessRecord = processRecord;
            mToken = token;
            mRequestedState = requestedState;
            mFlags = flags;
        }

        public void setStatusLocked(int status) {
            setStatusLocked(status, true /* markDirty */);
        }

        public void setStatusLocked(int status, boolean markDirty) {
            if (mStatus != status) {
                if (mStatus == STATUS_CANCELED) {
                    throw new IllegalStateException(
                            "Can not alter the status of a request after set to CANCELED.");
                }

                mStatus = status;

                if (mStatus == STATUS_CANCELED) {
                    mRequestRecords.remove(this);
                    mProcessRecord.mRequestRecords.remove(mToken);
                }

                if (markDirty) {
                    mRequestsPendingStatusChange.add(this);
                }
            }
        }

        public void notifyStatusIfNeeded() {
            int stateToReport;
            synchronized (mLock) {
                if (mLastNotifiedStatus == mStatus) {
                    return;
                }

                stateToReport = mStatus;
                mLastNotifiedStatus = mStatus;
            }

            if (stateToReport == STATUS_ACTIVE) {
                mProcessRecord.notifyRequestActiveAsync(this);
            } else if (stateToReport == STATUS_SUSPENDED) {
                mProcessRecord.notifyRequestSuspendedAsync(this);
            } else if (stateToReport == STATUS_CANCELED) {
                mProcessRecord.notifyRequestCanceledAsync(this);
            }
        }
    }

    /** Implementation of {@link IDeviceStateManager} published as a binder service. */
    private final class BinderService extends IDeviceStateManager.Stub {
        @Override // Binder call
        public DeviceStateInfo getDeviceStateInfo() {
            synchronized (mLock) {
                return getDeviceStateInfoLocked();
            }
        }

        @Override // Binder call
        public void registerCallback(IDeviceStateManagerCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("Device state callback must not be null.");
            }

            final int callingPid = Binder.getCallingPid();
            final long token = Binder.clearCallingIdentity();
            try {
                registerProcess(callingPid, callback);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void requestState(IBinder token, int state, int flags) {
            getContext().enforceCallingOrSelfPermission(CONTROL_DEVICE_STATE,
                    "Permission required to request device state.");

            if (token == null) {
                throw new IllegalArgumentException("Request token must not be null.");
            }

            final int callingPid = Binder.getCallingPid();
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                requestStateInternal(state, flags, callingPid, token);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override // Binder call
        public void cancelRequest(IBinder token) {
            getContext().enforceCallingOrSelfPermission(CONTROL_DEVICE_STATE,
                    "Permission required to clear requested device state.");

            if (token == null) {
                throw new IllegalArgumentException("Request token must not be null.");
            }

            final int callingPid = Binder.getCallingPid();
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                cancelRequestInternal(callingPid, token);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
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
