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

import android.app.AppOpsManager;
import android.content.Context;
import android.hardware.contexthub.EndpointInfo;
import android.hardware.contexthub.ErrorCode;
import android.hardware.contexthub.HubEndpointInfo;
import android.hardware.contexthub.HubMessage;
import android.hardware.contexthub.IContextHubEndpoint;
import android.hardware.contexthub.IContextHubEndpointCallback;
import android.hardware.contexthub.IEndpointCommunication;
import android.hardware.contexthub.Message;
import android.hardware.contexthub.MessageDeliveryStatus;
import android.hardware.contexthub.Reason;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.IContextHubTransactionCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class that represents a broker for the endpoint registered by the client app. This class
 * manages direct IContextHubEndpoint/IContextHubEndpointCallback API/callback calls.
 *
 * @hide
 */
public class ContextHubEndpointBroker extends IContextHubEndpoint.Stub
        implements IBinder.DeathRecipient, AppOpsManager.OnOpChangedListener {
    private static final String TAG = "ContextHubEndpointBroker";

    /** Message used by noteOp when this client receives a message from an endpoint. */
    private static final String RECEIVE_MSG_NOTE = "ContextHubEndpointMessageDelivery";

    /** The duration of wakelocks acquired during HAL callbacks */
    private static final long WAKELOCK_TIMEOUT_MILLIS = 5 * 1000;

    /*
     * Internal interface used to invoke client callbacks.
     */
    interface CallbackConsumer {
        void accept(IContextHubEndpointCallback callback) throws RemoteException;
    }

    /** The context of the service. */
    private final Context mContext;

    /** The proxy to talk to the Context Hub HAL for endpoint communication. */
    private final IEndpointCommunication mHubInterface;

    /** The manager that registered this endpoint. */
    private final ContextHubEndpointManager mEndpointManager;

    /** Manager used for noting permissions usage of this broker. */
    private final AppOpsManager mAppOpsManager;

    /** Metadata about this endpoint (app-facing container). */
    private final HubEndpointInfo mEndpointInfo;

    /** Metadata about this endpoint (HAL-facing container). */
    private final EndpointInfo mHalEndpointInfo;

    /** The remote callback interface for this endpoint. */
    private final IContextHubEndpointCallback mContextHubEndpointCallback;

    /** True if this endpoint is registered with the service. */
    private AtomicBoolean mIsRegistered = new AtomicBoolean(true);

    private final Object mOpenSessionLock = new Object();

    static class SessionInfo {
        enum SessionState {
            /* The session is pending acceptance from the remote endpoint. */
            PENDING,
            /* The session is active and can transport messages. */
            ACTIVE,
        };

        private final HubEndpointInfo mRemoteEndpointInfo;

        private SessionState mSessionState = SessionState.PENDING;

        private final boolean mRemoteInitiated;

        SessionInfo(HubEndpointInfo remoteEndpointInfo, boolean remoteInitiated) {
            mRemoteEndpointInfo = remoteEndpointInfo;
            mRemoteInitiated = remoteInitiated;
        }

        public boolean isRemoteInitiated() {
            return mRemoteInitiated;
        }

        public HubEndpointInfo getRemoteEndpointInfo() {
            return mRemoteEndpointInfo;
        }

        public void setSessionState(SessionState state) {
            mSessionState = state;
        }

        public boolean isActive() {
            return mSessionState == SessionState.ACTIVE;
        }
    }

    /** A map between a session ID which maps to its current state. */
    @GuardedBy("mOpenSessionLock")
    private final SparseArray<SessionInfo> mSessionInfoMap = new SparseArray<>();

    /** The package name of the app that created the endpoint */
    private final String mPackageName;

    /** The attribution tag of the module that created the endpoint */
    private final String mAttributionTag;

    /** Transaction manager used for sending reliable messages */
    private final ContextHubTransactionManager mTransactionManager;

    /** The PID/UID of the endpoint package providing IContextHubEndpointCallback */
    private final int mPid;

    private final int mUid;

    /** Wakelock held while nanoapp message are in flight to the client */
    private final WakeLock mWakeLock;

    /* package */ ContextHubEndpointBroker(
            Context context,
            IEndpointCommunication hubInterface,
            ContextHubEndpointManager endpointManager,
            EndpointInfo halEndpointInfo,
            IContextHubEndpointCallback callback,
            String packageName,
            String attributionTag,
            ContextHubTransactionManager transactionManager) {
        mContext = context;
        mHubInterface = hubInterface;
        mEndpointManager = endpointManager;
        mEndpointInfo = new HubEndpointInfo(halEndpointInfo);
        mHalEndpointInfo = halEndpointInfo;
        mContextHubEndpointCallback = callback;
        mPackageName = packageName;
        mAttributionTag = attributionTag;
        mTransactionManager = transactionManager;

        mPid = Binder.getCallingPid();
        mUid = Binder.getCallingUid();

        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mAppOpsManager.startWatchingMode(AppOpsManager.OP_NONE, mPackageName, this);

        PowerManager powerManager = context.getSystemService(PowerManager.class);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setWorkSource(new WorkSource(mUid, mPackageName));
        mWakeLock.setReferenceCounted(true);
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
        if (!hasEndpointPermissions(destination)) {
            throw new SecurityException(
                    "Insufficient permission to open a session with endpoint: " + destination);
        }

        int sessionId = mEndpointManager.reserveSessionId();
        EndpointInfo halEndpointInfo = ContextHubServiceUtil.convertHalEndpointInfo(destination);

        synchronized (mOpenSessionLock) {
            try {
                mSessionInfoMap.put(sessionId, new SessionInfo(destination, false));
                mHubInterface.openEndpointSession(
                        sessionId,
                        halEndpointInfo.id,
                        mHalEndpointInfo.id,
                        serviceDescriptor);
            } catch (RemoteException | IllegalArgumentException | UnsupportedOperationException e) {
                Log.e(TAG, "Exception while calling HAL openEndpointSession", e);
                cleanupSessionResources(sessionId);
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
        if (!cleanupSessionResources(sessionId)) {
            throw new IllegalArgumentException(
                    "Unknown session ID in closeSession: id=" + sessionId);
        }
        halCloseEndpointSession(sessionId, ContextHubServiceUtil.toHalReason(reason));
    }

    @Override
    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void unregister() {
        super.unregister_enforcePermission();
        mIsRegistered.set(false);
        try {
            mHubInterface.unregisterEndpoint(mHalEndpointInfo);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while calling HAL unregisterEndpoint", e);
        }
        synchronized (mOpenSessionLock) {
            // Iterate in reverse since cleanupSessionResources will remove the entry
            for (int i = mSessionInfoMap.size() - 1; i >= 0; i--) {
                int id = mSessionInfoMap.keyAt(i);
                cleanupSessionResources(id);
            }
        }
        mEndpointManager.unregisterEndpoint(mEndpointInfo.getIdentifier().getEndpoint());
        releaseWakeLockOnExit();
    }

    @Override
    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void openSessionRequestComplete(int sessionId) {
        super.openSessionRequestComplete_enforcePermission();
        synchronized (mOpenSessionLock) {
            SessionInfo info = mSessionInfoMap.get(sessionId);
            if (info == null) {
                throw new IllegalArgumentException(
                        "openSessionRequestComplete for invalid session id=" + sessionId);
            }
            try {
                mHubInterface.endpointSessionOpenComplete(sessionId);
                info.setSessionState(SessionInfo.SessionState.ACTIVE);
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
        if (!isSessionActive(sessionId)) {
            throw new SecurityException(
                    "sendMessage called on inactive session (id= " + sessionId + ")");
        }

        if (callback == null) {
            try {
                mHubInterface.sendMessageToEndpoint(sessionId, halMessage);
            } catch (RemoteException e) {
                Log.w(TAG, "Exception while sending message on session " + sessionId, e);
            }
        } else {
            ContextHubServiceTransaction transaction =
                    mTransactionManager.createSessionMessageTransaction(
                            mHubInterface, sessionId, halMessage, mPackageName, callback);
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
            mHubInterface.sendMessageDeliveryStatusToEndpoint(sessionId, status);
        } catch (RemoteException e) {
            Log.w(
                    TAG,
                    "Exception while sending message delivery status on session " + sessionId,
                    e);
        }
    }

    @Override
    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void onCallbackFinished() {
        super.onCallbackFinished_enforcePermission();
        releaseWakeLock();
    }

    /** Invoked when the underlying binder of this broker has died at the client process. */
    @Override
    public void binderDied() {
        if (mIsRegistered.get()) {
            unregister();
        }
    }

    @Override
    public void onOpChanged(String op, String packageName) {
        if (!packageName.equals(mPackageName)) {
            Log.w(
                    TAG,
                    "onOpChanged called with invalid package "
                            + packageName
                            + " expected "
                            + mPackageName);
        } else {
            synchronized (mOpenSessionLock) {
                // Iterate in reverse since cleanupSessionResources will remove the entry
                for (int i = mSessionInfoMap.size() - 1; i >= 0; i--) {
                    int id = mSessionInfoMap.keyAt(i);
                    HubEndpointInfo target = mSessionInfoMap.get(id).getRemoteEndpointInfo();
                    if (!hasEndpointPermissions(target)) {
                        halCloseEndpointSessionNoThrow(id, Reason.PERMISSION_DENIED);
                        onCloseEndpointSession(id, Reason.PERMISSION_DENIED);
                        // Resource cleanup is done in onCloseEndpointSession
                    }
                }
            }
        }
    }

    /* package */ void attachDeathRecipient() throws RemoteException {
        if (mContextHubEndpointCallback != null) {
            mContextHubEndpointCallback.asBinder().linkToDeath(this, 0 /* flags */);
        }
    }

    /* package */ void onEndpointSessionOpenRequest(
            int sessionId, HubEndpointInfo initiator, String serviceDescriptor) {
        Optional<Byte> error =
                onEndpointSessionOpenRequestInternal(sessionId, initiator, serviceDescriptor);
        if (error.isPresent()) {
            halCloseEndpointSessionNoThrow(sessionId, error.get());
            onCloseEndpointSession(sessionId, error.get());
            // Resource cleanup is done in onCloseEndpointSession
        }
    }

    /* package */ void onCloseEndpointSession(int sessionId, byte reason) {
        if (!cleanupSessionResources(sessionId)) {
            Log.w(TAG, "Unknown session ID in onCloseEndpointSession: id=" + sessionId);
            return;
        }

        invokeCallback(
                (consumer) ->
                        consumer.onSessionClosed(
                                sessionId, ContextHubServiceUtil.toAppHubEndpointReason(reason)));
    }

    /* package */ void onEndpointSessionOpenComplete(int sessionId) {
        synchronized (mOpenSessionLock) {
            if (!hasSessionId(sessionId)) {
                Log.w(TAG, "Unknown session ID in onEndpointSessionOpenComplete: id=" + sessionId);
                return;
            }
            mSessionInfoMap.get(sessionId).setSessionState(SessionInfo.SessionState.ACTIVE);
        }

        invokeCallback((consumer) -> consumer.onSessionOpenComplete(sessionId));
    }

    /* package */ void onMessageReceived(int sessionId, HubMessage message) {
        byte code = onMessageReceivedInternal(sessionId, message);
        if (code != ErrorCode.OK && message.isResponseRequired()) {
            sendMessageDeliveryStatus(
                    sessionId, message.getMessageSequenceNumber(), code);
        }
    }

    /* package */ void onMessageDeliveryStatusReceived(
            int sessionId, int sequenceNumber, byte errorCode) {
        mTransactionManager.onMessageDeliveryResponse(sequenceNumber, errorCode == ErrorCode.OK);
    }

    /* package */ boolean hasSessionId(int sessionId) {
        synchronized (mOpenSessionLock) {
            return mSessionInfoMap.contains(sessionId);
        }
    }

    private Optional<Byte> onEndpointSessionOpenRequestInternal(
            int sessionId, HubEndpointInfo initiator, String serviceDescriptor) {
        if (!hasEndpointPermissions(initiator)) {
            Log.e(
                    TAG,
                    "onEndpointSessionOpenRequest: "
                            + initiator
                            + " doesn't have permission for "
                            + mEndpointInfo);
            return Optional.of(Reason.PERMISSION_DENIED);
        }

        synchronized (mOpenSessionLock) {
            if (hasSessionId(sessionId)) {
                Log.e(TAG, "Existing session in onEndpointSessionOpenRequest: id=" + sessionId);
                return Optional.of(Reason.UNSPECIFIED);
            }
            mSessionInfoMap.put(sessionId, new SessionInfo(initiator, true));
        }

        boolean success = invokeCallback(
                (consumer) ->
                        consumer.onSessionOpenRequest(sessionId, initiator, serviceDescriptor));
        return success ? Optional.empty() : Optional.of(Reason.UNSPECIFIED);
    }

    private byte onMessageReceivedInternal(int sessionId, HubMessage message) {
        HubEndpointInfo remote;
        synchronized (mOpenSessionLock) {
            if (!isSessionActive(sessionId)) {
                Log.e(
                        TAG,
                        "Dropping message for inactive session (id="
                                + sessionId
                                + ") with message: "
                                + message);
                return ErrorCode.PERMANENT_ERROR;
            }
            remote = mSessionInfoMap.get(sessionId).getRemoteEndpointInfo();
        }
        if (!ContextHubServiceUtil.notePermissions(
                mAppOpsManager,
                mUid,
                mPackageName,
                mAttributionTag,
                remote.getRequiredPermissions(),
                RECEIVE_MSG_NOTE
                        + "-0x"
                        + Long.toHexString(remote.getIdentifier().getHub())
                        + "-0x"
                        + Long.toHexString(remote.getIdentifier().getEndpoint()))) {
            Log.e(
                    TAG,
                    "Dropping message from "
                            + remote
                            + ". "
                            + mPackageName
                            + " doesn't have permission");
            return ErrorCode.PERMISSION_DENIED;
        }

        boolean success =
                invokeCallback((consumer) -> consumer.onMessageReceived(sessionId, message));
        return success ? ErrorCode.OK : ErrorCode.TRANSIENT_ERROR;
    }

    /**
     * Calls the HAL closeEndpointSession API.
     *
     * @param sessionId The session ID to close
     * @param halReason The HAL reason
     */
    private void halCloseEndpointSession(int sessionId, byte halReason) throws RemoteException {
        try {
            mHubInterface.closeEndpointSession(sessionId, halReason);
        } catch (RemoteException | IllegalArgumentException | UnsupportedOperationException e) {
            throw e;
        }
    }

    /** Same as halCloseEndpointSession but does not throw the exception */
    private void halCloseEndpointSessionNoThrow(int sessionId, byte halReason) {
        try {
            halCloseEndpointSession(sessionId, halReason);
        } catch (RemoteException | IllegalArgumentException | UnsupportedOperationException e) {
            Log.e(TAG, "Exception while calling HAL closeEndpointSession", e);
        }
    }

    /**
     * Cleans up resources related to a session with the provided ID.
     *
     * @param sessionId The session ID to clean up resources for
     * @return false if the session ID was invalid
     */
    private boolean cleanupSessionResources(int sessionId) {
        synchronized (mOpenSessionLock) {
            SessionInfo info = mSessionInfoMap.get(sessionId);
            if (info != null && !info.isRemoteInitiated()) {
                mEndpointManager.returnSessionId(sessionId);
                mSessionInfoMap.remove(sessionId);
            }
            return info != null;
        }
    }

    /**
     * @param sessionId The ID of the session to check
     * @return true if the session with the given ID is currently active
     */
    private boolean isSessionActive(int sessionId) {
        synchronized (mOpenSessionLock) {
            return hasSessionId(sessionId) && mSessionInfoMap.get(sessionId).isActive();
        }
    }

    /**
     * @param targetEndpointInfo The target endpoint to check permissions for
     * @return true if this endpoint has sufficient permission to the provided target endpoint
     */
    private boolean hasEndpointPermissions(HubEndpointInfo targetEndpointInfo) {
        Collection<String> requiredPermissions = targetEndpointInfo.getRequiredPermissions();
        return ContextHubServiceUtil.hasPermissions(mContext, mPid, mUid, requiredPermissions);
    }

    private void acquireWakeLock() {
        Binder.withCleanCallingIdentity(
                () -> {
                    if (mIsRegistered.get()) {
                        mWakeLock.acquire(WAKELOCK_TIMEOUT_MILLIS);
                    }
                });
    }

    private void releaseWakeLock() {
        Binder.withCleanCallingIdentity(
                () -> {
                    if (mWakeLock.isHeld()) {
                        try {
                            mWakeLock.release();
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Releasing the wakelock fails - ", e);
                        }
                    }
                });
    }

    private void releaseWakeLockOnExit() {
        Binder.withCleanCallingIdentity(
                () -> {
                    while (mWakeLock.isHeld()) {
                        try {
                            mWakeLock.release();
                        } catch (RuntimeException e) {
                            Log.e(
                                    TAG,
                                    "Releasing the wakelock for all acquisitions fails - ",
                                    e);
                            break;
                        }
                    }
                });
    }

    /**
     * Invokes a callback and acquires a wakelock.
     *
     * @param consumer The callback invoke
     * @return false if the callback threw a RemoteException
     */
    private boolean invokeCallback(CallbackConsumer consumer) {
        if (mContextHubEndpointCallback != null) {
            acquireWakeLock();
            try {
                consumer.accept(mContextHubEndpointCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling endpoint callback", e);
                releaseWakeLock();
                return false;
            }
        }
        return true;
    }
}
