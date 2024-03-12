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

package com.android.server.devicestate;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateRequest;
import android.os.IBinder;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Manages the lifecycle of override requests.
 * <p>
 * New requests are added with {@link #addRequest(OverrideRequest)} and are kept active until
 * either:
 * <ul>
 *     <li>A new request is added with {@link #addRequest(OverrideRequest)}, in which case the
 *     request will become suspended.</li>
 *     <li>The request is cancelled with {@link #cancelRequest} or as a side effect
 *     of other methods calls, such as {@link #handleProcessDied(int)}.</li>
 * </ul>
 */
final class OverrideRequestController {
    private static final String TAG = "OverrideRequestController";

    static final int STATUS_UNKNOWN = 0;
    /**
     * The request is the top-most request.
     */
    static final int STATUS_ACTIVE = 1;
    /**
     * The request is not longer valid.
     */
    static final int STATUS_CANCELED = 2;

    @IntDef(prefix = {"STATUS_"}, value = {
            STATUS_UNKNOWN,
            STATUS_ACTIVE,
            STATUS_CANCELED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface RequestStatus {}

    /**
     * A flag indicating that the status change was triggered by thermal critical status.
     */
    static final int FLAG_THERMAL_CRITICAL = 1 << 0;

    /**
     * A flag indicating that the status change was triggered by power save mode.
     */
    static final int FLAG_POWER_SAVE_ENABLED = 1 << 1;

    @IntDef(flag = true, prefix = {"FLAG_"}, value = {
            FLAG_THERMAL_CRITICAL,
            FLAG_POWER_SAVE_ENABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface StatusChangedFlag {}

    static String statusToString(@RequestStatus int status) {
        switch (status) {
            case STATUS_ACTIVE:
                return "ACTIVE";
            case STATUS_CANCELED:
                return "CANCELED";
            case STATUS_UNKNOWN:
                return "UNKNOWN";
        }
        throw new IllegalArgumentException("Unknown status: " + status);
    }

    private final StatusChangeListener mListener;

    // Handle to the current override request, null if none.
    private OverrideRequest mRequest;
    // Handle to the current base state override request, null if none.
    private OverrideRequest mBaseStateRequest;

    private boolean mStickyRequestsAllowed;
    // The current request has outlived their process.
    private boolean mStickyRequest;

    OverrideRequestController(@NonNull StatusChangeListener listener) {
        mListener = listener;
    }

    /**
     * Sets sticky requests as either allowed or disallowed. When sticky requests are allowed a call
     * to {@link #handleProcessDied(int)} will not result in the request being cancelled
     * immediately. Instead, the request will be marked sticky and must be cancelled with a call
     * to {@link #cancelStickyRequest()}.
     */
    void setStickyRequestsAllowed(boolean stickyRequestsAllowed) {
        mStickyRequestsAllowed = stickyRequestsAllowed;
        if (!mStickyRequestsAllowed) {
            cancelStickyRequest();
        }
    }

    /**
     * Sets the new request as active and cancels the previous override request, notifies the
     * listener of all changes to request status as a result of this operation.
     */
    void addRequest(@NonNull OverrideRequest request) {
        OverrideRequest previousRequest = mRequest;
        mRequest = request;
        mListener.onStatusChanged(request, STATUS_ACTIVE, 0 /* flags */);

        if (previousRequest != null) {
            cancelRequestLocked(previousRequest);
        }
    }

    void addBaseStateRequest(@NonNull OverrideRequest request) {
        OverrideRequest previousRequest = mBaseStateRequest;
        mBaseStateRequest = request;
        mListener.onStatusChanged(request, STATUS_ACTIVE, 0 /* flags */);

        if (previousRequest != null) {
            cancelRequestLocked(previousRequest);
        }
    }

    /**
     * Cancels the request with the specified {@code token} and notifies the listener of all changes
     * to request status as a result of this operation.
     */
    void cancelRequest(@NonNull OverrideRequest request) {
        // Either don't have a current request or attempting to cancel an already cancelled request
        if (!hasRequest(request.getToken(), request.getRequestType())) {
            return;
        }
        cancelCurrentRequestLocked();
    }

    /**
     * Cancels a request that is currently marked sticky and notifies the listener of all
     * changes to request status as a result of this operation.
     *
     * @see #setStickyRequestsAllowed(boolean)
     */
    void cancelStickyRequest() {
        if (mStickyRequest) {
            cancelCurrentRequestLocked();
        }
    }

    /**
     * Cancels the current override request, this could be due to the device being put
     * into a hardware state that declares the flag "FLAG_CANCEL_OVERRIDE_REQUESTS"
     */
    void cancelOverrideRequest() {
        cancelCurrentRequestLocked();
    }

    /**
     * Cancels the current base state override request, this could be due to the physical
     * configuration of the device changing.
     */
    void cancelBaseStateOverrideRequest() {
        cancelCurrentBaseStateRequestLocked();
    }

    /**
     * Returns {@code true} if this controller is current managing a request with the specified
     * {@code token}, {@code false} otherwise.
     */
    boolean hasRequest(@NonNull IBinder token,
            @OverrideRequest.OverrideRequestType int requestType) {
        if (requestType == OverrideRequest.OVERRIDE_REQUEST_TYPE_BASE_STATE) {
            return mBaseStateRequest != null && token == mBaseStateRequest.getToken();
        } else {
            return mRequest != null && token == mRequest.getToken();
        }
    }

    /**
     * Notifies the controller that the process with the specified {@code pid} has died. The
     * controller will notify the listener of all changes to request status as a result of this
     * operation.
     */
    void handleProcessDied(int pid) {
        if (mBaseStateRequest != null && mBaseStateRequest.getPid() == pid) {
            cancelCurrentBaseStateRequestLocked();
        }

        if (mRequest != null && mRequest.getPid() == pid) {
            if (mRequest.getRequestedDeviceState().hasProperty(
                    DeviceState.PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP)) {
                cancelCurrentRequestLocked();
                return;
            }

            if (mStickyRequestsAllowed) {
                // Do not cancel the requests now because sticky requests are allowed. These
                // requests will be cancelled on a call to cancelStickyRequests().
                mStickyRequest = true;
                return;
            }
            cancelCurrentRequestLocked();
        }
    }

    /**
     * Notifies the controller that the base state has changed. The controller will notify the
     * listener of all changes to request status as a result of this change.
     */
    void handleBaseStateChanged(int state) {
        if (mBaseStateRequest != null && state != mBaseStateRequest.getRequestedStateIdentifier()) {
            cancelBaseStateOverrideRequest();
        }
        if (mRequest == null) {
            return;
        }

        if ((mRequest.getFlags()
                & DeviceStateRequest.FLAG_CANCEL_WHEN_BASE_CHANGES) != 0) {
            cancelCurrentRequestLocked();
        }
    }

    /**
     * Notifies the controller that the set of supported states has changed. The controller will
     * notify the listener of all changes to request status as a result of this change.
     */
    void handleNewSupportedStates(int[] newSupportedStates,
            @DeviceStateProvider.SupportedStatesUpdatedReason int reason) {
        boolean isThermalCritical =
                reason == DeviceStateProvider.SUPPORTED_DEVICE_STATES_CHANGED_THERMAL_CRITICAL;
        boolean isPowerSaveEnabled =
                reason == DeviceStateProvider.SUPPORTED_DEVICE_STATES_CHANGED_POWER_SAVE_ENABLED;
        @StatusChangedFlag int flags = 0;
        flags |= isThermalCritical ? FLAG_THERMAL_CRITICAL : 0;
        flags |= isPowerSaveEnabled ? FLAG_POWER_SAVE_ENABLED : 0;
        if (mBaseStateRequest != null && !contains(newSupportedStates,
                mBaseStateRequest.getRequestedStateIdentifier())) {
            cancelCurrentBaseStateRequestLocked(flags);
        }

        if (mRequest != null && !contains(newSupportedStates,
                mRequest.getRequestedStateIdentifier())) {
            cancelCurrentRequestLocked(flags);
        }
    }

    void dumpInternal(PrintWriter pw) {
        OverrideRequest overrideRequest = mRequest;
        final boolean requestActive = overrideRequest != null;
        pw.println();
        pw.println("Override Request active: " + requestActive);
        if (requestActive) {
            pw.println("Request: mPid=" + overrideRequest.getPid()
                    + ", mRequestedState=" + overrideRequest.getRequestedStateIdentifier()
                    + ", mFlags=" + overrideRequest.getFlags()
                    + ", mStatus=" + statusToString(STATUS_ACTIVE));
        }
    }

    private void cancelRequestLocked(@NonNull OverrideRequest requestToCancel) {
        cancelRequestLocked(requestToCancel, 0 /* flags */);
    }

    private void cancelRequestLocked(@NonNull OverrideRequest requestToCancel,
            @StatusChangedFlag int flags) {
        mListener.onStatusChanged(requestToCancel, STATUS_CANCELED, flags);
    }

    /**
     * Handles cancelling {@code mRequest}.
     * Notifies the listener of the canceled status as well.
     */
    private void cancelCurrentRequestLocked() {
        cancelCurrentRequestLocked(0 /* flags */);
    }

    private void cancelCurrentRequestLocked(@StatusChangedFlag int flags) {
        if (mRequest == null) {
            Slog.w(TAG, "Attempted to cancel a null OverrideRequest");
            return;
        }
        mStickyRequest = false;
        cancelRequestLocked(mRequest, flags);
        mRequest = null;
    }

    /**
     * Handles cancelling {@code mBaseStateRequest}.
     * Notifies the listener of the canceled status as well.
     */
    private void cancelCurrentBaseStateRequestLocked() {
        cancelCurrentBaseStateRequestLocked(0 /* flags */);
    }

    private void cancelCurrentBaseStateRequestLocked(@StatusChangedFlag int flags) {
        if (mBaseStateRequest == null) {
            Slog.w(TAG, "Attempted to cancel a null OverrideRequest");
            return;
        }
        cancelRequestLocked(mBaseStateRequest, flags);
        mBaseStateRequest = null;
    }

    private static boolean contains(int[] array, int value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return true;
            }
        }
        return false;
    }

    public interface StatusChangeListener {

        /**
         * Notifies the listener of a change in request status. If a change within the controller
         * causes one request to become active and one to become either suspended or cancelled, this
         * method is guaranteed to be called with the active request first before the suspended or
         * cancelled request.
         */
        void onStatusChanged(@NonNull OverrideRequest request, @RequestStatus int newStatus,
                @StatusChangedFlag int flags);
    }
}
