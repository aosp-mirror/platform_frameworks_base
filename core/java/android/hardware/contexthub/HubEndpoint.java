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

package android.hardware.contexthub;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.content.Context;
import android.hardware.location.IContextHubService;
import android.hardware.location.IContextHubTransactionCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.GuardedBy;

import java.util.concurrent.Executor;

/**
 * An object representing an endpoint exposed to ContextHub and VendorHub. The object encapsulates
 * the lifecycle and message callbacks for an endpoint.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public class HubEndpoint {
    private static final String TAG = "HubEndpoint";

    private final Object mLock = new Object();
    private final HubEndpointInfo mPendingHubEndpointInfo;
    @Nullable private final IHubEndpointLifecycleCallback mLifecycleCallback;
    @Nullable private final IHubEndpointMessageCallback mMessageCallback;
    @NonNull private final Executor mLifecycleCallbackExecutor;
    @NonNull private final Executor mMessageCallbackExecutor;

    @GuardedBy("mLock")
    private final SparseArray<HubEndpointSession> mActiveSessions = new SparseArray<>();

    private final IContextHubEndpointCallback mServiceCallback =
            new IContextHubEndpointCallback.Stub() {
                @Override
                public void onSessionOpenRequest(int sessionId, HubEndpointInfo initiator)
                        throws RemoteException {
                    HubEndpointSession activeSession;
                    synchronized (mLock) {
                        activeSession = mActiveSessions.get(sessionId);
                        // TODO(b/378974199): Consider refactor these assertions
                        if (activeSession != null) {
                            Log.i(
                                    TAG,
                                    "onSessionOpenComplete: session already exists, id="
                                            + sessionId);
                            return;
                        }
                    }

                    if (mLifecycleCallback != null) {
                        mLifecycleCallbackExecutor.execute(
                                () ->
                                        processSessionOpenRequestResult(
                                                sessionId,
                                                initiator,
                                                mLifecycleCallback.onSessionOpenRequest(
                                                        initiator)));
                    }
                }

                private void processSessionOpenRequestResult(
                        int sessionId, HubEndpointInfo initiator, HubEndpointSessionResult result) {
                    if (result == null) {
                        throw new IllegalArgumentException(
                                "HubEndpointSessionResult shouldn't be null.");
                    }

                    if (result.isAccepted()) {
                        acceptSession(sessionId, initiator);
                    } else {
                        Log.i(
                                TAG,
                                "Session "
                                        + sessionId
                                        + " from "
                                        + initiator
                                        + " was rejected, reason="
                                        + result.getReason());
                        rejectSession(sessionId);
                    }
                }

                private void acceptSession(int sessionId, HubEndpointInfo initiator) {
                    if (mServiceToken == null || mAssignedHubEndpointInfo == null) {
                        // No longer registered?
                        return;
                    }

                    // Retrieve the active session
                    HubEndpointSession activeSession;
                    synchronized (mLock) {
                        activeSession = mActiveSessions.get(sessionId);
                        // TODO(b/378974199): Consider refactor these assertions
                        if (activeSession != null) {
                            Log.e(
                                    TAG,
                                    "onSessionOpenRequestResult: session already exists, id="
                                            + sessionId);
                            return;
                        }

                        activeSession =
                                new HubEndpointSession(
                                        sessionId,
                                        HubEndpoint.this,
                                        mAssignedHubEndpointInfo,
                                        initiator);
                        try {
                            // oneway call to notify system service that the request is completed
                            mServiceToken.openSessionRequestComplete(sessionId);
                        } catch (RemoteException e) {
                            Log.e(TAG, "onSessionOpenRequestResult: ", e);
                            return;
                        }

                        mActiveSessions.put(sessionId, activeSession);
                    }

                    // Execute the callback
                    activeSession.setOpened();
                    if (mLifecycleCallback != null) {
                        final HubEndpointSession finalActiveSession = activeSession;
                        mLifecycleCallbackExecutor.execute(
                                () -> mLifecycleCallback.onSessionOpened(finalActiveSession));
                    }
                }

                private void rejectSession(int sessionId) {
                    if (mServiceToken == null || mAssignedHubEndpointInfo == null) {
                        // No longer registered?
                        return;
                    }

                    try {
                        mServiceToken.closeSession(
                                sessionId,
                                IHubEndpointLifecycleCallback
                                        .REASON_OPEN_ENDPOINT_SESSION_REQUEST_REJECTED);
                    } catch (RemoteException e) {
                        e.rethrowFromSystemServer();
                    }
                }

                @Override
                public void onSessionOpenComplete(int sessionId) throws RemoteException {
                    final HubEndpointSession activeSession;

                    // Retrieve the active session
                    synchronized (mLock) {
                        activeSession = mActiveSessions.get(sessionId);
                    }
                    // TODO(b/378974199): Consider refactor these assertions
                    if (activeSession == null) {
                        Log.i(
                                TAG,
                                "onSessionOpenComplete: no pending session open request? id="
                                        + sessionId);
                        return;
                    }

                    // Execute the callback
                    activeSession.setOpened();
                    if (mLifecycleCallback != null) {
                        mLifecycleCallbackExecutor.execute(
                                () -> mLifecycleCallback.onSessionOpened(activeSession));
                    }
                }

                @Override
                public void onSessionClosed(int sessionId, int reason) throws RemoteException {
                    final HubEndpointSession activeSession;

                    // Retrieve the active session
                    synchronized (mLock) {
                        activeSession = mActiveSessions.get(sessionId);
                    }
                    // TODO(b/378974199): Consider refactor these assertions
                    if (activeSession == null) {
                        Log.i(TAG, "onSessionClosed: session not active, id=" + sessionId);
                        return;
                    }

                    // Execute the callback
                    if (mLifecycleCallback != null) {
                        mLifecycleCallbackExecutor.execute(
                                () -> {
                                    mLifecycleCallback.onSessionClosed(activeSession, reason);

                                    // Remove the session object first to call
                                    activeSession.setClosed();
                                    synchronized (mLock) {
                                        mActiveSessions.remove(sessionId);
                                    }
                                });
                    }
                }

                @Override
                public void onMessageReceived(int sessionId, HubMessage message)
                        throws RemoteException {
                    final HubEndpointSession activeSession;

                    // Retrieve the active session
                    synchronized (mLock) {
                        activeSession = mActiveSessions.get(sessionId);
                    }
                    if (activeSession == null) {
                        Log.i(TAG, "onMessageReceived: session not active, id=" + sessionId);
                    }

                    if (activeSession == null || mMessageCallback == null) {
                        if (message.getDeliveryParams().isResponseRequired()) {
                            try {
                                mServiceToken.sendMessageDeliveryStatus(
                                        sessionId,
                                        message.getMessageSequenceNumber(),
                                        ErrorCode.DESTINATION_NOT_FOUND);
                            } catch (RemoteException e) {
                                e.rethrowFromSystemServer();
                            }
                        }
                        return;
                    }

                    // Execute the callback
                    mMessageCallbackExecutor.execute(
                            () -> {
                                mMessageCallback.onMessageReceived(activeSession, message);
                                if (message.getDeliveryParams().isResponseRequired()) {
                                    try {
                                        mServiceToken.sendMessageDeliveryStatus(
                                                sessionId,
                                                message.getMessageSequenceNumber(),
                                                ErrorCode.OK);
                                    } catch (RemoteException e) {
                                        e.rethrowFromSystemServer();
                                    }
                                }
                            });
                }
            };

    /** Binder returned from system service, non-null while registered. */
    @Nullable private IContextHubEndpoint mServiceToken;

    /** HubEndpointInfo with the assigned endpoint id from system service. */
    @Nullable private HubEndpointInfo mAssignedHubEndpointInfo;

    private HubEndpoint(
            @NonNull HubEndpointInfo pendingEndpointInfo,
            @Nullable IHubEndpointLifecycleCallback endpointLifecycleCallback,
            @NonNull Executor lifecycleCallbackExecutor,
            @Nullable IHubEndpointMessageCallback endpointMessageCallback,
            @NonNull Executor messageCallbackExecutor) {
        mPendingHubEndpointInfo = pendingEndpointInfo;

        mLifecycleCallback = endpointLifecycleCallback;
        mLifecycleCallbackExecutor = lifecycleCallbackExecutor;
        mMessageCallback = endpointMessageCallback;
        mMessageCallbackExecutor = messageCallbackExecutor;
    }

    /** @hide */
    public void register(IContextHubService service) {
        // TODO(b/378974199): Consider refactor these assertions
        if (mServiceToken != null) {
            // Already registered
            return;
        }
        try {
            IContextHubEndpoint serviceToken =
                    service.registerEndpoint(mPendingHubEndpointInfo, mServiceCallback);
            mAssignedHubEndpointInfo = serviceToken.getAssignedHubEndpointInfo();
            mServiceToken = serviceToken;
        } catch (RemoteException e) {
            Log.e(TAG, "registerEndpoint: failed to register endpoint", e);
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void unregister() {
        IContextHubEndpoint serviceToken = mServiceToken;
        // TODO(b/378974199): Consider refactor these assertions
        if (serviceToken == null) {
            // Not yet registered
            return;
        }

        try {
            synchronized (mLock) {
                // Don't call HubEndpointSession.close() here.
                for (int i = 0; i < mActiveSessions.size(); i++) {
                    mActiveSessions.get(mActiveSessions.keyAt(i)).setClosed();
                }
                mActiveSessions.clear();
            }
            mServiceToken.unregister();
        } catch (RemoteException e) {
            Log.e(TAG, "unregisterEndpoint: failed to unregister endpoint", e);
            e.rethrowFromSystemServer();
        } finally {
            mServiceToken = null;
            mAssignedHubEndpointInfo = null;
        }
    }

    /** @hide */
    public void openSession(HubEndpointInfo destinationInfo) {
        // TODO(b/378974199): Consider refactor these assertions
        if (mServiceToken == null || mAssignedHubEndpointInfo == null) {
            // No longer registered?
            return;
        }

        HubEndpointSession newSession;
        try {
            // Request system service to assign session id.
            int sessionId = mServiceToken.openSession(destinationInfo);

            // Save the newly created session
            synchronized (mLock) {
                newSession =
                        new HubEndpointSession(
                                sessionId,
                                HubEndpoint.this,
                                destinationInfo,
                                mAssignedHubEndpointInfo);
                mActiveSessions.put(sessionId, newSession);
            }
        } catch (RemoteException e) {
            // Move this to toString
            Log.e(TAG, "openSession: failed to open session to " + destinationInfo, e);
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void closeSession(HubEndpointSession session) {
        IContextHubEndpoint serviceToken = mServiceToken;
        // TODO(b/378974199): Consider refactor these assertions
        if (serviceToken == null || mAssignedHubEndpointInfo == null) {
            // Not registered
            return;
        }

        synchronized (mLock) {
            if (!mActiveSessions.contains(session.getId())) {
                // Already closed?
                return;
            }
            session.setClosed();
            mActiveSessions.remove(session.getId());
        }

        try {
            // Oneway notification to system service
            serviceToken.closeSession(
                    session.getId(),
                    IHubEndpointLifecycleCallback.REASON_CLOSE_ENDPOINT_SESSION_REQUESTED);
        } catch (RemoteException e) {
            Log.e(TAG, "closeSession: failed to close session " + session, e);
            e.rethrowFromSystemServer();
        }
    }

    void sendMessage(
            HubEndpointSession session,
            HubMessage message,
            @Nullable IContextHubTransactionCallback transactionCallback) {
        IContextHubEndpoint serviceToken = mServiceToken;
        if (serviceToken == null) {
            // Not registered
            return;
        }

        try {
            serviceToken.sendMessage(session.getId(), message, transactionCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "sendMessage: failed to send message session=" + session, e);
            e.rethrowFromSystemServer();
        }
    }

    @Nullable
    public String getTag() {
        return mPendingHubEndpointInfo.getTag();
    }

    @Nullable
    public IHubEndpointLifecycleCallback getLifecycleCallback() {
        return mLifecycleCallback;
    }

    @Nullable
    public IHubEndpointMessageCallback getMessageCallback() {
        return mMessageCallback;
    }

    /** Builder for a {@link HubEndpoint} object. */
    public static final class Builder {
        private final String mPackageName;

        @Nullable private IHubEndpointLifecycleCallback mLifecycleCallback;

        @NonNull private Executor mLifecycleCallbackExecutor;

        @Nullable private IHubEndpointMessageCallback mMessageCallback;
        @NonNull private Executor mMessageCallbackExecutor;

        @Nullable private String mTag;

        /** Create a builder for {@link HubEndpoint} */
        public Builder(@NonNull Context context) {
            mPackageName = context.getPackageName();
            mLifecycleCallbackExecutor = context.getMainExecutor();
            mMessageCallbackExecutor = context.getMainExecutor();
        }

        /**
         * Set a tag string. The tag can be used to further identify the creator of the endpoint.
         * Endpoints created by the same package share the same name but should have different tags.
         */
        @NonNull
        public Builder setTag(@NonNull String tag) {
            mTag = tag;
            return this;
        }

        /** Attach a callback interface for lifecycle events for this Endpoint */
        @NonNull
        public Builder setLifecycleCallback(
                @NonNull IHubEndpointLifecycleCallback lifecycleCallback) {
            mLifecycleCallback = lifecycleCallback;
            return this;
        }

        /**
         * Attach a callback interface for lifecycle events for this Endpoint with a specified
         * executor
         */
        @NonNull
        public Builder setLifecycleCallback(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull IHubEndpointLifecycleCallback lifecycleCallback) {
            mLifecycleCallbackExecutor = executor;
            mLifecycleCallback = lifecycleCallback;
            return this;
        }

        /** Attach a callback interface for message events for this Endpoint */
        @NonNull
        public Builder setMessageCallback(@NonNull IHubEndpointMessageCallback messageCallback) {
            mMessageCallback = messageCallback;
            return this;
        }

        /**
         * Attach a callback interface for message events for this Endpoint with a specified
         * executor
         */
        @NonNull
        public Builder setMessageCallback(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull IHubEndpointMessageCallback messageCallback) {
            mMessageCallbackExecutor = executor;
            mMessageCallback = messageCallback;
            return this;
        }

        /** Build the {@link HubEndpoint} object. */
        @NonNull
        public HubEndpoint build() {
            return new HubEndpoint(
                    new HubEndpointInfo(mPackageName, mTag),
                    mLifecycleCallback,
                    mLifecycleCallbackExecutor,
                    mMessageCallback,
                    mMessageCallbackExecutor);
        }
    }
}
