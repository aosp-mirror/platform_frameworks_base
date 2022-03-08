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
import android.annotation.Nullable;
import android.hardware.devicestate.DeviceStateRequest;
import android.os.IBinder;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the lifecycle of override requests.
 * <p>
 * New requests are added with {@link #addRequest(OverrideRequest)} and are kept active until
 * either:
 * <ul>
 *     <li>A new request is added with {@link #addRequest(OverrideRequest)}, in which case the
 *     request will become suspended.</li>
 *     <li>The request is cancelled with {@link #cancelRequest(IBinder)} or as a side effect
 *     of other methods calls, such as {@link #handleProcessDied(int)}.</li>
 * </ul>
 */
final class OverrideRequestController {
    static final int STATUS_UNKNOWN = 0;
    /**
     * The request is the top-most request.
     */
    static final int STATUS_ACTIVE = 1;
    /**
     * The request is still present but is being superseded by another request.
     */
    static final int STATUS_SUSPENDED = 2;
    /**
     * The request is not longer valid.
     */
    static final int STATUS_CANCELED = 3;

    @IntDef(prefix = {"STATUS_"}, value = {
            STATUS_UNKNOWN,
            STATUS_ACTIVE,
            STATUS_SUSPENDED,
            STATUS_CANCELED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface RequestStatus {}

    static String statusToString(@RequestStatus int status) {
        switch (status) {
            case STATUS_ACTIVE:
                return "ACTIVE";
            case STATUS_SUSPENDED:
                return "SUSPENDED";
            case STATUS_CANCELED:
                return "CANCELED";
            case STATUS_UNKNOWN:
                return "UNKNOWN";
        }
        throw new IllegalArgumentException("Unknown status: " + status);
    }

    private final StatusChangeListener mListener;
    private final List<OverrideRequest> mTmpRequestsToCancel = new ArrayList<>();

    // List of override requests with the most recent override request at the end.
    private final ArrayList<OverrideRequest> mRequests = new ArrayList<>();

    private boolean mStickyRequestsAllowed;
    // List of override requests that have outlived their process and will only be cancelled through
    // a call to cancelStickyRequests().
    private final ArrayList<OverrideRequest> mStickyRequests = new ArrayList<>();

    OverrideRequestController(@NonNull StatusChangeListener listener) {
        mListener = listener;
    }

    /**
     * Sets sticky requests as either allowed or disallowed. When sticky requests are allowed a call
     * to {@link #handleProcessDied(int)} will not result in the request being cancelled
     * immediately. Instead, the request will be marked sticky and must be cancelled with a call
     * to {@link #cancelStickyRequests()}.
     */
    void setStickyRequestsAllowed(boolean stickyRequestsAllowed) {
        mStickyRequestsAllowed = stickyRequestsAllowed;
        if (!mStickyRequestsAllowed) {
            cancelStickyRequests();
        }
    }

    /**
     * Adds a request to the top of the stack and notifies the listener of all changes to request
     * status as a result of this operation.
     */
    void addRequest(@NonNull OverrideRequest request) {
        mRequests.add(request);
        mListener.onStatusChanged(request, STATUS_ACTIVE);

        if (mRequests.size() > 1) {
            OverrideRequest prevRequest = mRequests.get(mRequests.size() - 2);
            mListener.onStatusChanged(prevRequest, STATUS_SUSPENDED);
        }
    }

    /**
     * Cancels the request with the specified {@code token} and notifies the listener of all changes
     * to request status as a result of this operation.
     */
    void cancelRequest(@NonNull IBinder token) {
        int index = getRequestIndex(token);
        if (index == -1) {
            return;
        }

        OverrideRequest request = mRequests.remove(index);
        if (index == mRequests.size() && mRequests.size() > 0) {
            // We removed the current active request so we need to set the new active request
            // before cancelling this request.
            OverrideRequest newTop = getLast(mRequests);
            mListener.onStatusChanged(newTop, STATUS_ACTIVE);
        }
        mListener.onStatusChanged(request, STATUS_CANCELED);
    }

    /**
     * Cancels all requests that are currently marked sticky and notifies the listener of all
     * changes to request status as a result of this operation.
     *
     * @see #setStickyRequestsAllowed(boolean)
     */
    void cancelStickyRequests() {
        mTmpRequestsToCancel.clear();
        mTmpRequestsToCancel.addAll(mStickyRequests);
        cancelRequestsLocked(mTmpRequestsToCancel);
    }

    /**
     * Returns {@code true} if this controller is current managing a request with the specified
     * {@code token}, {@code false} otherwise.
     */
    boolean hasRequest(@NonNull IBinder token) {
        return getRequestIndex(token) != -1;
    }

    /**
     * Notifies the controller that the process with the specified {@code pid} has died. The
     * controller will notify the listener of all changes to request status as a result of this
     * operation.
     */
    void handleProcessDied(int pid) {
        if (mRequests.isEmpty()) {
            return;
        }

        mTmpRequestsToCancel.clear();
        OverrideRequest prevActiveRequest = getLast(mRequests);
        for (OverrideRequest request : mRequests) {
            if (request.getPid() == pid) {
                mTmpRequestsToCancel.add(request);
            }
        }

        if (mStickyRequestsAllowed) {
            // Do not cancel the requests now because sticky requests are allowed. These
            // requests will be cancelled on a call to cancelStickyRequests().
            mStickyRequests.addAll(mTmpRequestsToCancel);
            return;
        }

        cancelRequestsLocked(mTmpRequestsToCancel);
    }

    /**
     * Notifies the controller that the base state has changed. The controller will notify the
     * listener of all changes to request status as a result of this change.
     *
     * @return {@code true} if calling this method has lead to a new active request, {@code false}
     * otherwise.
     */
    boolean handleBaseStateChanged() {
        if (mRequests.isEmpty()) {
            return false;
        }

        mTmpRequestsToCancel.clear();
        OverrideRequest prevActiveRequest = getLast(mRequests);
        for (int i = 0; i < mRequests.size(); i++) {
            OverrideRequest request = mRequests.get(i);
            if ((request.getFlags() & DeviceStateRequest.FLAG_CANCEL_WHEN_BASE_CHANGES) != 0) {
                mTmpRequestsToCancel.add(request);
            }
        }

        final boolean newActiveRequest = cancelRequestsLocked(mTmpRequestsToCancel);
        return newActiveRequest;
    }

    /**
     * Notifies the controller that the set of supported states has changed. The controller will
     * notify the listener of all changes to request status as a result of this change.
     *
     * @return {@code true} if calling this method has lead to a new active request, {@code false}
     * otherwise.
     */
    boolean handleNewSupportedStates(int[] newSupportedStates) {
        if (mRequests.isEmpty()) {
            return false;
        }

        mTmpRequestsToCancel.clear();
        for (int i = 0; i < mRequests.size(); i++) {
            OverrideRequest request = mRequests.get(i);
            if (!contains(newSupportedStates, request.getRequestedState())) {
                mTmpRequestsToCancel.add(request);
            }
        }

        final boolean newActiveRequest = cancelRequestsLocked(mTmpRequestsToCancel);
        return newActiveRequest;
    }

    void dumpInternal(PrintWriter pw) {
        final int requestCount = mRequests.size();
        pw.println();
        pw.println("Override requests: size=" + requestCount);
        for (int i = 0; i < requestCount; i++) {
            OverrideRequest overrideRequest = mRequests.get(i);
            int status = (i == requestCount - 1) ? STATUS_ACTIVE : STATUS_SUSPENDED;
            pw.println("  " + i + ": mPid=" + overrideRequest.getPid()
                    + ", mRequestedState=" + overrideRequest.getRequestedState()
                    + ", mFlags=" + overrideRequest.getFlags()
                    + ", mStatus=" + statusToString(status));
        }
    }

    /**
     * Handles cancelling a set of requests. If the set of requests to cancel will lead to a new
     * request becoming active this request will also be notified of its change in state.
     *
     * @return {@code true} if calling this method has lead to a new active request, {@code false}
     * otherwise.
     */
    private boolean cancelRequestsLocked(List<OverrideRequest> requestsToCancel) {
        if (requestsToCancel.isEmpty()) {
            return false;
        }

        OverrideRequest prevActiveRequest = getLast(mRequests);
        boolean causedNewRequestToBecomeActive = false;
        mRequests.removeAll(requestsToCancel);
        mStickyRequests.removeAll(requestsToCancel);
        if (!mRequests.isEmpty()) {
            OverrideRequest newActiveRequest = getLast(mRequests);
            if (newActiveRequest != prevActiveRequest) {
                mListener.onStatusChanged(newActiveRequest, STATUS_ACTIVE);
                causedNewRequestToBecomeActive = true;
            }
        }

        for (int i = 0; i < requestsToCancel.size(); i++) {
            mListener.onStatusChanged(requestsToCancel.get(i), STATUS_CANCELED);
        }
        return causedNewRequestToBecomeActive;
    }

    private int getRequestIndex(@NonNull IBinder token) {
        final int numberOfRequests = mRequests.size();
        if (numberOfRequests == 0) {
            return -1;
        }

        for (int i = 0; i < numberOfRequests; i++) {
            OverrideRequest request = mRequests.get(i);
            if (request.getToken() == token) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private static <T> T getLast(List<T> list) {
        return list.size() > 0 ? list.get(list.size() - 1) : null;
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
        void onStatusChanged(@NonNull OverrideRequest request, @RequestStatus int newStatus);
    }
}
