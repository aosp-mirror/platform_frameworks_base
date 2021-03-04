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

package com.android.server.connectivity;

import static android.net.QosCallbackException.EX_TYPE_FILTER_NONE;

import android.annotation.NonNull;
import android.net.IQosCallback;
import android.net.Network;
import android.net.QosCallbackException;
import android.net.QosFilter;
import android.net.QosSession;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.data.EpsBearerQosSessionAttributes;
import android.util.Log;

import java.util.Objects;

/**
 * Wraps callback related information and sends messages between network agent and the application.
 * <p/>
 * This is a satellite class of {@link com.android.server.ConnectivityService} and not meant
 * to be used in other contexts.
 *
 * @hide
 */
class QosCallbackAgentConnection implements IBinder.DeathRecipient {
    private static final String TAG = QosCallbackAgentConnection.class.getSimpleName();
    private static final boolean DBG = false;

    private final int mAgentCallbackId;
    @NonNull private final QosCallbackTracker mQosCallbackTracker;
    @NonNull private final IQosCallback mCallback;
    @NonNull private final IBinder mBinder;
    @NonNull private final QosFilter mFilter;
    @NonNull private final NetworkAgentInfo mNetworkAgentInfo;

    private final int mUid;

    /**
     * Gets the uid
     * @return uid
     */
    int getUid() {
        return mUid;
    }

    /**
     * Gets the binder
     * @return binder
     */
    @NonNull
    IBinder getBinder() {
        return mBinder;
    }

    /**
     * Gets the callback id
     *
     * @return callback id
     */
    int getAgentCallbackId() {
        return mAgentCallbackId;
    }

    /**
     * Gets the network tied to the callback of this connection
     *
     * @return network
     */
    @NonNull
    Network getNetwork() {
        return mFilter.getNetwork();
    }

    QosCallbackAgentConnection(@NonNull final QosCallbackTracker qosCallbackTracker,
            final int agentCallbackId,
            @NonNull final IQosCallback callback,
            @NonNull final QosFilter filter,
            final int uid,
            @NonNull final NetworkAgentInfo networkAgentInfo) {
        Objects.requireNonNull(qosCallbackTracker, "qosCallbackTracker must be non-null");
        Objects.requireNonNull(callback, "callback must be non-null");
        Objects.requireNonNull(filter, "filter must be non-null");
        Objects.requireNonNull(networkAgentInfo, "networkAgentInfo must be non-null");

        mQosCallbackTracker = qosCallbackTracker;
        mAgentCallbackId = agentCallbackId;
        mCallback = callback;
        mFilter = filter;
        mUid = uid;
        mBinder = mCallback.asBinder();
        mNetworkAgentInfo = networkAgentInfo;
    }

    @Override
    public void binderDied() {
        logw("binderDied: binder died with callback id: " + mAgentCallbackId);
        mQosCallbackTracker.unregisterCallback(mCallback);
    }

    void unlinkToDeathRecipient() {
        mBinder.unlinkToDeath(this, 0);
    }

    // Returns false if the NetworkAgent was never notified.
    boolean sendCmdRegisterCallback() {
        final int exceptionType = mFilter.validate();
        if (exceptionType != EX_TYPE_FILTER_NONE) {
            try {
                if (DBG) log("sendCmdRegisterCallback: filter validation failed");
                mCallback.onError(exceptionType);
            } catch (final RemoteException e) {
                loge("sendCmdRegisterCallback:", e);
            }
            return false;
        }

        try {
            mBinder.linkToDeath(this, 0);
        } catch (final RemoteException e) {
            loge("failed linking to death recipient", e);
            return false;
        }
        mNetworkAgentInfo.onQosFilterCallbackRegistered(mAgentCallbackId, mFilter);
        return true;
    }

    void sendCmdUnregisterCallback() {
        if (DBG) log("sendCmdUnregisterCallback: unregistering");
        mNetworkAgentInfo.onQosCallbackUnregistered(mAgentCallbackId);
    }

    void sendEventQosSessionAvailable(final QosSession session,
            final EpsBearerQosSessionAttributes attributes) {
        try {
            if (DBG) log("sendEventQosSessionAvailable: sending...");
            mCallback.onQosEpsBearerSessionAvailable(session, attributes);
        } catch (final RemoteException e) {
            loge("sendEventQosSessionAvailable: remote exception", e);
        }
    }

    void sendEventQosSessionLost(@NonNull final QosSession session) {
        try {
            if (DBG) log("sendEventQosSessionLost: sending...");
            mCallback.onQosSessionLost(session);
        } catch (final RemoteException e) {
            loge("sendEventQosSessionLost: remote exception", e);
        }
    }

    void sendEventQosCallbackError(@QosCallbackException.ExceptionType final int exceptionType) {
        try {
            if (DBG) log("sendEventQosCallbackError: sending...");
            mCallback.onError(exceptionType);
        } catch (final RemoteException e) {
            loge("sendEventQosCallbackError: remote exception", e);
        }
    }

    private static void log(@NonNull final String msg) {
        Log.d(TAG, msg);
    }

    private static void logw(@NonNull final String msg) {
        Log.w(TAG, msg);
    }

    private static void loge(@NonNull final String msg, final Throwable t) {
        Log.e(TAG, msg, t);
    }
}
