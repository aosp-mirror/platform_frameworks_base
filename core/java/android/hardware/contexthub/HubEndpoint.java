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
import android.annotation.IntDef;
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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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

    /**
     * Constants describing the outcome of operations through HubEndpoints (like opening/closing of
     * sessions or stopping of endpoints).
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"REASON_"},
            value = {
                REASON_FAILURE,
                REASON_OPEN_ENDPOINT_SESSION_REQUEST_REJECTED,
                REASON_CLOSE_ENDPOINT_SESSION_REQUESTED,
                REASON_ENDPOINT_INVALID,
                REASON_ENDPOINT_STOPPED,
                REASON_PERMISSION_DENIED,
            })
    public @interface Reason {}

    /** Unclassified failure */
    public static final int REASON_FAILURE = 0;

    // The values 1-2 are reserved at the Context Hub HAL but not exposed to apps.

    /** The peer rejected the request to open this endpoint session. */
    public static final int REASON_OPEN_ENDPOINT_SESSION_REQUEST_REJECTED = 3;

    /** The peer closed this endpoint session. */
    public static final int REASON_CLOSE_ENDPOINT_SESSION_REQUESTED = 4;

    /** The peer endpoint is invalid. */
    public static final int REASON_ENDPOINT_INVALID = 5;

    // The values 6-8 are reserved at the Context Hub HAL but not exposed to apps.

    /** The endpoint did not have the required permissions. */
    public static final int REASON_PERMISSION_DENIED = 9;

    /**
     * The endpoint is now stopped. The app should retrieve the endpoint info using {@link
     * android.hardware.location.ContextHubManager#findEndpoints} or register updates through
     * {@link android.hardware.location.ContextHubManager#registerEndpointDiscoveryCallback}
     * to get notified if the endpoint restarts.
     */
    public static final int REASON_ENDPOINT_STOPPED = 6;

    private final Object mLock = new Object();
    private final HubEndpointInfo mPendingHubEndpointInfo;
    @Nullable private final HubEndpointLifecycleCallback mLifecycleCallback;
    @Nullable private final HubEndpointMessageCallback mMessageCallback;
    @NonNull private final Executor mLifecycleCallbackExecutor;
    @NonNull private final Executor mMessageCallbackExecutor;

    @GuardedBy("mLock")
    private final SparseArray<HubEndpointSession> mActiveSessions = new SparseArray<>();

    /*
     * Internal interface used to invoke IContextHubEndpoint calls.
     */
    interface EndpointConsumer {
        void accept(IContextHubEndpoint endpoint) throws RemoteException;
    }

    private final IContextHubEndpointCallback mServiceCallback =
            new IContextHubEndpointCallback.Stub() {
                @Override
                public void onSessionOpenRequest(
                        int sessionId,
                        HubEndpointInfo initiator,
                        @Nullable String serviceDescriptor)
                        throws RemoteException {
                    boolean sessionExists = getActiveSession(sessionId) != null;
                    if (sessionExists) {
                        Log.w(
                                TAG,
                                "onSessionOpenComplete: session already exists, id=" + sessionId);
                    }

                    if (!sessionExists && mLifecycleCallback != null) {
                        mLifecycleCallbackExecutor.execute(
                                () ->
                                        processSessionOpenRequestResult(
                                                sessionId,
                                                initiator,
                                                serviceDescriptor,
                                                mLifecycleCallback.onSessionOpenRequest(
                                                        initiator, serviceDescriptor)));
                    } else {
                        invokeCallbackFinished();
                    }
                }

                @Override
                public void onSessionOpenComplete(int sessionId) throws RemoteException {
                    final HubEndpointSession activeSession = getActiveSession(sessionId);
                    if (activeSession == null) {
                        Log.w(
                                TAG,
                                "onSessionOpenComplete: no pending session open request? id="
                                        + sessionId);
                    } else {
                        activeSession.setOpened();
                    }

                    if (activeSession != null && mLifecycleCallback != null) {
                        mLifecycleCallbackExecutor.execute(
                                () -> {
                                    mLifecycleCallback.onSessionOpened(activeSession);
                                    invokeCallbackFinished();
                                });
                    } else {
                        invokeCallbackFinished();
                    }
                }

                @Override
                public void onSessionClosed(int sessionId, int reason) throws RemoteException {
                    final HubEndpointSession activeSession = getActiveSession(sessionId);
                    if (activeSession == null) {
                        Log.w(TAG, "onSessionClosed: session not active, id=" + sessionId);
                    }

                    // Execute the callback
                    if (activeSession != null && mLifecycleCallback != null) {
                        mLifecycleCallbackExecutor.execute(
                                () -> {
                                    mLifecycleCallback.onSessionClosed(activeSession, reason);

                                    // Remove the session object first to call
                                    activeSession.setClosed();
                                    synchronized (mLock) {
                                        mActiveSessions.remove(sessionId);
                                    }
                                    invokeCallbackFinished();
                                });
                    } else {
                        invokeCallbackFinished();
                    }
                }

                @Override
                public void onMessageReceived(int sessionId, HubMessage message)
                        throws RemoteException {
                    final HubEndpointSession activeSession = getActiveSession(sessionId);
                    if (activeSession == null) {
                        Log.w(TAG, "onMessageReceived: session not active, id=" + sessionId);
                    }

                    if (activeSession == null || mMessageCallback == null) {
                        sendMessageDeliveryStatus(
                                sessionId, message, ErrorCode.DESTINATION_NOT_FOUND);
                    } else {
                        mMessageCallbackExecutor.execute(
                                () -> {
                                    mMessageCallback.onMessageReceived(activeSession, message);
                                    sendMessageDeliveryStatus(sessionId, message, ErrorCode.OK);
                                });
                    }
                }

                private HubEndpointSession getActiveSession(int sessionId) {
                    synchronized (mLock) {
                        return mActiveSessions.get(sessionId);
                    }
                }

                private void sendMessageDeliveryStatus(
                        int sessionId, HubMessage message, byte errorCode) {
                    if (message.isResponseRequired()) {
                        invokeCallback(
                                (callback) ->
                                        callback.sendMessageDeliveryStatus(
                                                sessionId,
                                                message.getMessageSequenceNumber(),
                                                errorCode));
                    }
                    invokeCallbackFinished();
                }

                private void processSessionOpenRequestResult(
                        int sessionId,
                        HubEndpointInfo initiator,
                        @Nullable String serviceDescriptor,
                        HubEndpointSessionResult result) {
                    if (result == null) {
                        throw new IllegalArgumentException(
                                "HubEndpointSessionResult shouldn't be null.");
                    }

                    if (result.isAccepted()) {
                        acceptSession(sessionId, initiator, serviceDescriptor);
                    } else {
                        Log.e(
                                TAG,
                                "Session "
                                        + sessionId
                                        + " from "
                                        + initiator
                                        + " was rejected, reason="
                                        + result.getReason());
                        rejectSession(sessionId);
                    }

                    invokeCallbackFinished();
                }

                private void acceptSession(
                        int sessionId,
                        HubEndpointInfo initiator,
                        @Nullable String serviceDescriptor) {
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
                                        initiator,
                                        serviceDescriptor);

                        invokeCallback(
                                (callback) -> callback.openSessionRequestComplete(sessionId));
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
                    invokeCallback(
                            (callback) ->
                                    callback.closeSession(
                                            sessionId,
                                            REASON_OPEN_ENDPOINT_SESSION_REQUEST_REJECTED));
                }

                private void invokeCallbackFinished() {
                    invokeCallback((callback) -> callback.onCallbackFinished());
                }

                private void invokeCallback(EndpointConsumer consumer) {
                    try {
                        consumer.accept(mServiceToken);
                    } catch (IllegalStateException e) {
                        // It's possible to hit this exception if the endpoint was unregistered
                        // while processing the callback. It's not a fatal error so we just log
                        // a warning.
                        Log.w(TAG, "IllegalStateException while calling callback", e);
                    } catch (RemoteException e) {
                        e.rethrowFromSystemServer();
                    }
                }
            };

    /** Binder returned from system service, non-null while registered. */
    @Nullable private IContextHubEndpoint mServiceToken;

    /** HubEndpointInfo with the assigned endpoint id from system service. */
    @Nullable private HubEndpointInfo mAssignedHubEndpointInfo;

    private HubEndpoint(
            @NonNull HubEndpointInfo pendingEndpointInfo,
            @Nullable HubEndpointLifecycleCallback endpointLifecycleCallback,
            @NonNull Executor lifecycleCallbackExecutor,
            @Nullable HubEndpointMessageCallback endpointMessageCallback,
            @NonNull Executor messageCallbackExecutor) {
        mPendingHubEndpointInfo = pendingEndpointInfo;
        mLifecycleCallback = endpointLifecycleCallback;
        mLifecycleCallbackExecutor = lifecycleCallbackExecutor;
        mMessageCallback = endpointMessageCallback;
        mMessageCallbackExecutor = messageCallbackExecutor;
    }

    /** @hide */
    public void register(IContextHubService service) {
        try {
            IContextHubEndpoint serviceToken =
                    service.registerEndpoint(
                            mPendingHubEndpointInfo,
                            mServiceCallback,
                            mPendingHubEndpointInfo.getName(),
                            mPendingHubEndpointInfo.getTag());
            mAssignedHubEndpointInfo = serviceToken.getAssignedHubEndpointInfo();
            mServiceToken = serviceToken;
        } catch (RemoteException e) {
            Log.e(TAG, "registerEndpoint: failed to register endpoint", e);
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void unregister() {
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
        }
    }

    /** @hide */
    public void openSession(HubEndpointInfo destinationInfo, @Nullable String serviceDescriptor) {
        HubEndpointSession newSession;
        try {
            synchronized (mLock) {
                // Request system service to assign session id.
                int sessionId = mServiceToken.openSession(destinationInfo, serviceDescriptor);

                // Save the newly created session
                newSession =
                        new HubEndpointSession(
                                sessionId,
                                HubEndpoint.this,
                                destinationInfo,
                                mAssignedHubEndpointInfo,
                                serviceDescriptor);
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
        synchronized (mLock) {
            if (!mActiveSessions.contains(session.getId())) {
                // Already closed?
                return;
            }
            session.setClosed();
            mActiveSessions.remove(session.getId());
        }

        try {
            mServiceToken.closeSession(session.getId(), REASON_CLOSE_ENDPOINT_SESSION_REQUESTED);
        } catch (RemoteException e) {
            Log.e(TAG, "closeSession: failed to close session " + session, e);
            e.rethrowFromSystemServer();
        }
    }

    void sendMessage(
            HubEndpointSession session,
            HubMessage message,
            @Nullable IContextHubTransactionCallback transactionCallback) {
        try {
            mServiceToken.sendMessage(session.getId(), message, transactionCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "sendMessage: failed to send message session=" + session, e);
            e.rethrowFromSystemServer();
        }
    }

    public int getVersion() {
        return mPendingHubEndpointInfo.getVersion();
    }

    @Nullable
    public String getTag() {
        return mPendingHubEndpointInfo.getTag();
    }

    @NonNull
    public Collection<HubServiceInfo> getServiceInfoCollection() {
        return mPendingHubEndpointInfo.getServiceInfoCollection();
    }

    @Nullable
    public HubEndpointLifecycleCallback getLifecycleCallback() {
        return mLifecycleCallback;
    }

    @Nullable
    public HubEndpointMessageCallback getMessageCallback() {
        return mMessageCallback;
    }

    /** Builder for a {@link HubEndpoint} object. */
    public static final class Builder {
        private final String mPackageName;

        @Nullable private HubEndpointLifecycleCallback mLifecycleCallback;
        @Nullable private Executor mLifecycleCallbackExecutor;

        @Nullable private HubEndpointMessageCallback mMessageCallback;
        @Nullable private Executor mMessageCallbackExecutor;

        @NonNull private final Executor mMainExecutor;

        private int mVersion;
        @Nullable private String mTag;

        private List<HubServiceInfo> mServiceInfos = Collections.emptyList();

        /** Create a builder for {@link HubEndpoint} */
        public Builder(@NonNull Context context) {
            mPackageName = context.getPackageName();
            mTag = context.getAttributionTag();
            mVersion = (int) context.getApplicationInfo().longVersionCode;
            mMainExecutor = context.getMainExecutor();
        }

        /**
         * Set the version for the endpoint. Default is 0.
         *
         * @hide
         */
        @NonNull
        public Builder setVersion(int version) {
            mVersion = version;
            return this;
        }

        /**
         * Set a tag string. The tag can be used to further identify the creator of the endpoint.
         * Endpoints created by the same package share the same name but should have different tags.
         * The default value of the tag is retrieved from {@link Context#getAttributionTag()}.
         */
        @NonNull
        public Builder setTag(@NonNull String tag) {
            mTag = tag;
            return this;
        }

        /**
         * Attach a callback interface for lifecycle events for this Endpoint. Callback will be
         * posted to the main thread.
         */
        @NonNull
        public Builder setLifecycleCallback(
                @NonNull HubEndpointLifecycleCallback lifecycleCallback) {
            mLifecycleCallbackExecutor = null;
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
                @NonNull HubEndpointLifecycleCallback lifecycleCallback) {
            mLifecycleCallbackExecutor = executor;
            mLifecycleCallback = lifecycleCallback;
            return this;
        }

        /**
         * Attach a callback interface for message events for this Endpoint. Callback will be posted
         * to the main thread.
         */
        @NonNull
        public Builder setMessageCallback(@NonNull HubEndpointMessageCallback messageCallback) {
            mMessageCallbackExecutor = null;
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
                @NonNull HubEndpointMessageCallback messageCallback) {
            mMessageCallbackExecutor = executor;
            mMessageCallback = messageCallback;
            return this;
        }

        /**
         * Add a service to the available services from this endpoint. The {@link HubServiceInfo}
         * object can be built with {@link HubServiceInfo.Builder}.
         */
        @NonNull
        public Builder setServiceInfoCollection(
                @NonNull Collection<HubServiceInfo> hubServiceInfos) {
            // Make a copy first
            mServiceInfos = new ArrayList<>(hubServiceInfos);
            return this;
        }

        /** Build the {@link HubEndpoint} object. */
        @NonNull
        public HubEndpoint build() {
            return new HubEndpoint(
                    new HubEndpointInfo(mPackageName, mVersion, mTag, mServiceInfos),
                    mLifecycleCallback,
                    mLifecycleCallbackExecutor != null ? mLifecycleCallbackExecutor : mMainExecutor,
                    mMessageCallback,
                    mMessageCallbackExecutor != null ? mMessageCallbackExecutor : mMainExecutor);
        }
    }
}
