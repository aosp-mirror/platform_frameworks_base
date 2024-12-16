/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.location.contexthub;

import android.content.Context;
import android.hardware.contexthub.EndpointInfo;
import android.hardware.contexthub.ErrorCode;
import android.hardware.contexthub.HubEndpointInfo;
import android.hardware.contexthub.HubMessage;
import android.hardware.contexthub.IContextHubEndpoint;
import android.hardware.contexthub.IContextHubEndpointCallback;
import android.hardware.contexthub.Message;
import android.hardware.contexthub.MessageDeliveryStatus;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.IContextHubTransactionCallback;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class that represents a broker for the endpoint registered by the client app. This class
 * manages direct IContextHubEndpoint/IContextHubEndpointCallback API/callback calls.
 *
 * @hide
 */
public class ContextHubEndpointBroker extends IContextHubEndpoint.Stub
        implements IBinder.DeathRecipient {
    private static final String TAG = "ContextHubEndpointBroker";

    /** The context of the service. */
    private final Context mContext;

    /** The proxy to talk to the Context Hub HAL. */
    private final IContextHubWrapper mContextHubProxy;

    /** The manager that registered this endpoint. */
    private final ContextHubEndpointManager mEndpointManager;

    /** Metadata about this endpoint (app-facing container). */
    private final HubEndpointInfo mEndpointInfo;

    /** Metadata about this endpoint (HAL-facing container). */
    private final EndpointInfo mHalEndpointInfo;

    /** The remote callback interface for this endpoint. */
    private final IContextHubEndpointCallback mContextHubEndpointCallback;

    /** True if this endpoint is registered with the service. */
    private AtomicBoolean mIsRegistered = new AtomicBoolean(true);

    private final Object mOpenSessionLock = new Object();

    /** The set of session IDs that are pending remote acceptance */
    @GuardedBy("mOpenSessionLock")
    private final Set<Integer> mPendingSessionIds = new HashSet<>();

    /** The set of session IDs that are actively enabled by this endpoint */
    @GuardedBy("mOpenSessionLock")
    private final Set<Integer> mActiveSessionIds = new HashSet<>();

    /** The set of session IDs that are actively enabled by the remote endpoint */
    @GuardedBy("mOpenSessionLock")
    private final Set<Integer> mActiveRemoteSessionIds = new HashSet<>();

    /** The package name of the app that created the endpoint */
    private final String mPackageName;

    /* Transaction manager used for sending reliable messages */
    private final ContextHubTransactionManager mTransactionManager;

    /* package */ ContextHubEndpointBroker(
            Context context,
            IContextHubWrapper contextHubProxy,
            ContextHubEndpointManager endpointManager,
            EndpointInfo halEndpointInfo,
            IContextHubEndpointCallback callback,
            String packageName,
            ContextHubTransactionManager transactionManager) {
        mContext = context;
        mContextHubProxy = contextHubProxy;
        mEndpointManager = endpointManager;
        mEndpointInfo = new HubEndpointInfo(halEndpointInfo);
        mHalEndpointInfo = halEndpointInfo;
        mContextHubEndpointCallback = callback;
        mPackageName = packageName;
        mTransactionManager = transactionManager;
    }

    @Override
    public HubEndpointInfo getAssignedHubEndpointInfo() {
        return mEndpointInfo;
    }

    @Override
    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public int openSession(HubEndpointInfo destination, String serviceDescriptor)
            throws RemoteException {
        super.openSession_enforcePermission();
        if (!mIsRegistered.get()) throw new IllegalStateException("Endpoint is not registered");
        int sessionId = mEndpointManager.reserveSessionId();
        EndpointInfo halEndpointInfo = ContextHubServiceUtil.convertHalEndpointInfo(destination);

        synchronized (mOpenSessionLock) {
            try {
                mPendingSessionIds.add(sessionId);
                mContextHubProxy.openEndpointSession(
                        sessionId,
                        halEndpointInfo.id,
                        mHalEndpointInfo.id,
                        serviceDescriptor);
            } catch (RemoteException | IllegalArgumentException | UnsupportedOperationException e) {
                Log.e(TAG, "Exception while calling HAL openEndpointSession", e);
                mPendingSessionIds.remove(sessionId);
                mEndpointManager.returnSessionId(sessionId);
                throw e;
            }

            return sessionId;
        }
    }

    @Override
    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void closeSession(int sessionId, int reason) throws RemoteException {
        super.closeSession_enforcePermission();
        if (!mIsRegistered.get()) throw new IllegalStateException("Endpoint is not registered");
        try {
            mContextHubProxy.closeEndpointSession(
                    sessionId, ContextHubServiceUtil.toHalReason(reason));
        } catch (RemoteException | IllegalArgumentException | UnsupportedOperationException e) {
            Log.e(TAG, "Exception while calling HAL closeEndpointSession", e);
            throw e;
        }
    }

    @Override
    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void unregister() {
        super.unregister_enforcePermission();
        mIsRegistered.set(false);
        try {
            mContextHubProxy.unregisterEndpoint(mHalEndpointInfo);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while calling HAL unregisterEndpoint", e);
        }
        synchronized (mOpenSessionLock) {
            for (int id : mPendingSessionIds) {
                mEndpointManager.returnSessionId(id);
            }
            for (int id : mActiveSessionIds) {
                mEndpointManager.returnSessionId(id);
            }
            mPendingSessionIds.clear();
            mActiveSessionIds.clear();
            mActiveRemoteSessionIds.clear();
        }
        mEndpointManager.unregisterEndpoint(mEndpointInfo.getIdentifier().getEndpoint());
    }

    @Override
    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void openSessionRequestComplete(int sessionId) {
        super.openSessionRequestComplete_enforcePermission();
        synchronized (mOpenSessionLock) {
            try {
                mContextHubProxy.endpointSessionOpenComplete(sessionId);
                mActiveRemoteSessionIds.add(sessionId);
            } catch (RemoteException | IllegalArgumentException | UnsupportedOperationException e) {
                Log.e(TAG, "Exception while calling endpointSessionOpenComplete", e);
            }
        }
    }

    @Override
    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void sendMessage(
            int sessionId, HubMessage message, IContextHubTransactionCallback callback) {
        super.sendMessage_enforcePermission();
        Message halMessage = ContextHubServiceUtil.createHalMessage(message);
        synchronized (mOpenSessionLock) {
            if (!mActiveSessionIds.contains(sessionId)
                    && !mActiveRemoteSessionIds.contains(sessionId)) {
                throw new SecurityException(
                        "sendMessage called on inactive session (id= " + sessionId + ")");
            }
        }

        // TODO(b/381102453): Handle permissions
        if (callback == null) {
            try {
                mContextHubProxy.sendMessageToEndpoint(sessionId, halMessage);
            } catch (RemoteException e) {
                Log.w(TAG, "Exception while sending message on session " + sessionId, e);
            }
        } else {
            ContextHubServiceTransaction transaction =
                    mTransactionManager.createSessionMessageTransaction(
                            sessionId, halMessage, mPackageName, callback);
            try {
                mTransactionManager.addTransaction(transaction);
            } catch (IllegalStateException e) {
                Log.e(
                        TAG,
                        "Unable to add a transaction in sendMessageToEndpoint "
                                + "(session ID = "
                                + sessionId
                                + ")",
                        e);
                transaction.onTransactionComplete(
                        ContextHubTransaction.RESULT_FAILED_SERVICE_INTERNAL_FAILURE);
            }
        }
    }

    @Override
    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void sendMessageDeliveryStatus(int sessionId, int messageSeqNumber, byte errorCode) {
        super.sendMessageDeliveryStatus_enforcePermission();
        MessageDeliveryStatus status = new MessageDeliveryStatus();
        status.messageSequenceNumber = messageSeqNumber;
        status.errorCode = errorCode;
        try {
            mContextHubProxy.sendMessageDeliveryStatusToEndpoint(sessionId, status);
        } catch (RemoteException e) {
            Log.w(
                    TAG,
                    "Exception while sending message delivery status on session " + sessionId,
                    e);
        }
    }

    /** Invoked when the underlying binder of this broker has died at the client process. */
    @Override
    public void binderDied() {
        if (mIsRegistered.get()) {
            unregister();
        }
    }

    /* package */ void attachDeathRecipient() throws RemoteException {
        if (mContextHubEndpointCallback != null) {
            mContextHubEndpointCallback.asBinder().linkToDeath(this, 0 /* flags */);
        }
    }

    /* package */ void onEndpointSessionOpenRequest(
            int sessionId, HubEndpointInfo initiator, String serviceDescriptor) {
        if (mContextHubEndpointCallback != null) {
            try {
                mContextHubEndpointCallback.onSessionOpenRequest(
                        sessionId, initiator, serviceDescriptor);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onSessionOpenRequest", e);
            }
        }
    }

    /* package */ void onCloseEndpointSession(int sessionId, byte reason) {
        synchronized (mOpenSessionLock) {
            mPendingSessionIds.remove(sessionId);
            mActiveSessionIds.remove(sessionId);
            mActiveRemoteSessionIds.remove(sessionId);
        }
        if (mContextHubEndpointCallback != null) {
            try {
                mContextHubEndpointCallback.onSessionClosed(
                        sessionId, ContextHubServiceUtil.toAppHubEndpointReason(reason));
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onSessionClosed", e);
            }
        }
    }

    /* package */ void onEndpointSessionOpenComplete(int sessionId) {
        synchronized (mOpenSessionLock) {
            mPendingSessionIds.remove(sessionId);
            mActiveSessionIds.add(sessionId);
        }
        if (mContextHubEndpointCallback != null) {
            try {
                mContextHubEndpointCallback.onSessionOpenComplete(sessionId);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onSessionClosed", e);
            }
        }
    }

    /* package */ void onMessageReceived(int sessionId, HubMessage message) {
        if (mContextHubEndpointCallback != null) {
            try {
                mContextHubEndpointCallback.onMessageReceived(sessionId, message);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onMessageReceived", e);
            }
        }
    }

    /* package */ void onMessageDeliveryStatusReceived(
            int sessionId, int sequenceNumber, byte errorCode) {
        mTransactionManager.onMessageDeliveryResponse(sequenceNumber, errorCode == ErrorCode.OK);
    }

    /* package */ boolean hasSessionId(int sessionId) {
        synchronized (mOpenSessionLock) {
            return mPendingSessionIds.contains(sessionId)
                    || mActiveSessionIds.contains(sessionId)
                    || mActiveRemoteSessionIds.contains(sessionId);
        }
    }
}
