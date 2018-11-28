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

package android.telephony.mbms;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.RemoteException;
import android.telephony.MbmsGroupCallSession;
import android.telephony.mbms.vendor.IMbmsGroupCallService;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Class used to represent a single MBMS group call. After a call has been started with
 * {@link MbmsGroupCallSession#startGroupCall},
 * this class is used to hold information about the call and control it.
 */
public class GroupCall implements AutoCloseable {
    private static final String LOG_TAG = "MbmsGroupCall";

    /**
     * The state of a group call, reported via
     * {@link GroupCallCallback#onGroupCallStateChanged(int, int)}
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "STATE_" }, value = {STATE_STOPPED, STATE_STARTED, STATE_STALLED})
    public @interface GroupCallState {}

    /**
     * Indicates that the group call is in a stopped state
     *
     * This can be reported after network action or after calling {@link #close}.
     */
    public static final int STATE_STOPPED = 1;

    /**
     * Indicates that the group call is started.
     *
     * Data can be transmitted and received in this state.
     */
    public static final int STATE_STARTED = 2;

    /**
     * Indicates that the group call is stalled.
     *
     * This may be due to a network issue or the device being temporarily out of range.
     */
    public static final int STATE_STALLED = 3;

    /**
     * The reason for a call state change, reported via
     * {@link GroupCallCallback#onGroupCallStateChanged(int, int)}
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "REASON_" },
            value = {REASON_BY_USER_REQUEST, REASON_FREQUENCY_CONFLICT,
                    REASON_OUT_OF_MEMORY, REASON_NOT_CONNECTED_TO_HOMECARRIER_LTE,
                    REASON_LEFT_MBMS_BROADCAST_AREA, REASON_NONE})
    public @interface GroupCallStateChangeReason {}

    /**
     * Indicates that the middleware does not have a reason to provide for the state change.
     */
    public static final int REASON_NONE = 0;

    /**
     * State changed due to a call to {@link #close()} or
     * {@link MbmsGroupCallSession#startGroupCall}
     */
    public static final int REASON_BY_USER_REQUEST = 1;

    // 2 is unused to match up with streaming.

    /**
     * State changed due to a frequency conflict with another requested call.
     */
    public static final int REASON_FREQUENCY_CONFLICT = 3;

    /**
     * State changed due to the middleware running out of memory
     */
    public static final int REASON_OUT_OF_MEMORY = 4;

    /**
     * State changed due to the device leaving the home carrier's LTE network.
     */
    public static final int REASON_NOT_CONNECTED_TO_HOMECARRIER_LTE = 5;

    /**
     * State changed due to the device leaving the area where this call is being broadcast.
     */
    public static final int REASON_LEFT_MBMS_BROADCAST_AREA = 6;

    private final int mSubscriptionId;
    private final long mTmgi;
    private final MbmsGroupCallSession mParentSession;
    private final InternalGroupCallCallback mCallback;
    private IMbmsGroupCallService mService;

    /**
     * @hide
     */
    public GroupCall(int subscriptionId,
            IMbmsGroupCallService service,
            MbmsGroupCallSession session,
            long tmgi,
            InternalGroupCallCallback callback) {
        mSubscriptionId = subscriptionId;
        mParentSession = session;
        mService = service;
        mTmgi = tmgi;
        mCallback = callback;
    }

    /**
     * Retrieve the TMGI (Temporary Mobile Group Identity) corresponding to this call.
     */
    public long getTmgi() {
        return mTmgi;
    }

    /**
     * Send an update to the middleware when the SAI (Service Area Identifier) list and frequency
     * information of the group call has * changed. Callers must obtain this information from the
     * wireless carrier independently.
     * @param saiList New list of SAIs that the call is available on.
     * @param frequencyList New list of frequencies that the call is available on.
     */
    public void updateGroupCall(@NonNull List<Integer> saiList,
            @NonNull List<Integer> frequencyList) {
        if (mService == null) {
            throw new IllegalStateException("No group call service attached");
        }

        try {
            mService.updateGroupCall(mSubscriptionId, mTmgi, saiList, frequencyList);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService = null;
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
        } finally {
            mParentSession.onGroupCallStopped(this);
        }
    }

    /**
     * Stop this group call. Further operations on this object will fail with an
     * {@link IllegalStateException}.
     *
     * May throw an {@link IllegalStateException}
     */
    @Override
    public void close() {
        if (mService == null) {
            throw new IllegalStateException("No group call service attached");
        }

        try {
            mService.stopGroupCall(mSubscriptionId, mTmgi);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService = null;
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
        } finally {
            mParentSession.onGroupCallStopped(this);
        }
    }

    /** @hide */
    public InternalGroupCallCallback getCallback() {
        return mCallback;
    }

    private void sendErrorToApp(int errorCode, String message) {
        mCallback.onError(errorCode, message);
    }
}

