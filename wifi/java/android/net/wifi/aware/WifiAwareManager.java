/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.aware;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import libcore.util.HexEncoding;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.nio.BufferOverflowException;
import java.util.List;

/**
 * This class provides the primary API for managing Wi-Fi Aware operations:
 * discovery and peer-to-peer data connections.
 * <p>
 * The class provides access to:
 * <ul>
 * <li>Initialize a Aware cluster (peer-to-peer synchronization). Refer to
 * {@link #attach(AttachCallback, Handler)}.
 * <li>Create discovery sessions (publish or subscribe sessions). Refer to
 * {@link WifiAwareSession#publish(PublishConfig, DiscoverySessionCallback, Handler)} and
 * {@link WifiAwareSession#subscribe(SubscribeConfig, DiscoverySessionCallback, Handler)}.
 * <li>Create a Aware network specifier to be used with
 * {@link ConnectivityManager#requestNetwork(NetworkRequest, ConnectivityManager.NetworkCallback)}
 * to set-up a Aware connection with a peer. Refer to {@link NetworkSpecifierBuilder}.
 * </ul>
 * <p>
 *     Aware may not be usable when Wi-Fi is disabled (and other conditions). To validate that
 *     the functionality is available use the {@link #isAvailable()} function. To track
 *     changes in Aware usability register for the {@link #ACTION_WIFI_AWARE_STATE_CHANGED}
 *     broadcast. Note that this broadcast is not sticky - you should register for it and then
 *     check the above API to avoid a race condition.
 * <p>
 *     An application must use {@link #attach(AttachCallback, Handler)} to initialize a
 *     Aware cluster - before making any other Aware operation. Aware cluster membership is a
 *     device-wide operation - the API guarantees that the device is in a cluster or joins a
 *     Aware cluster (or starts one if none can be found). Information about attach success (or
 *     failure) are returned in callbacks of {@link AttachCallback}. Proceed with Aware
 *     discovery or connection setup only after receiving confirmation that Aware attach
 *     succeeded - {@link AttachCallback#onAttached(WifiAwareSession)}. When an
 *     application is finished using Aware it <b>must</b> use the
 *     {@link WifiAwareSession#close()} API to indicate to the Aware service that the device
 *     may detach from the Aware cluster. The device will actually disable Aware once the last
 *     application detaches.
 * <p>
 *     Once a Aware attach is confirmed use the
 *     {@link WifiAwareSession#publish(PublishConfig, DiscoverySessionCallback, Handler)}
 *     or
 *     {@link WifiAwareSession#subscribe(SubscribeConfig, DiscoverySessionCallback,
 *     Handler)} to create publish or subscribe Aware discovery sessions. Events are called on the
 *     provided callback object {@link DiscoverySessionCallback}. Specifically, the
 *     {@link DiscoverySessionCallback#onPublishStarted(PublishDiscoverySession)}
 *     and
 *     {@link DiscoverySessionCallback#onSubscribeStarted(
 *SubscribeDiscoverySession)}
 *     return {@link PublishDiscoverySession} and
 *     {@link SubscribeDiscoverySession}
 *     objects respectively on which additional session operations can be performed, e.g. updating
 *     the session {@link PublishDiscoverySession#updatePublish(PublishConfig)} and
 *     {@link SubscribeDiscoverySession#updateSubscribe(SubscribeConfig)}. Sessions can
 *     also be used to send messages using the
 *     {@link DiscoverySession#sendMessage(PeerHandle, int, byte[])} APIs. When an
 *     application is finished with a discovery session it <b>must</b> terminate it using the
 *     {@link DiscoverySession#close()} API.
 * <p>
 *    Creating connections between Aware devices is managed by the standard
 *    {@link ConnectivityManager#requestNetwork(NetworkRequest,
 *    ConnectivityManager.NetworkCallback)}.
 *    The {@link NetworkRequest} object should be constructed with:
 *    <ul>
 *        <li>{@link NetworkRequest.Builder#addTransportType(int)} of
 *        {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_AWARE}.
 *        <li>{@link NetworkRequest.Builder#setNetworkSpecifier(String)} using
 *        {@link NetworkSpecifierBuilder}.
 *    </ul>
 */
@SystemService(Context.WIFI_AWARE_SERVICE)
public class WifiAwareManager {
    private static final String TAG = "WifiAwareManager";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    /**
     * Broadcast intent action to indicate that the state of Wi-Fi Aware availability has changed.
     * Use the {@link #isAvailable()} to query the current status.
     * This broadcast is <b>not</b> sticky, use the {@link #isAvailable()} API after registering
     * the broadcast to check the current state of Wi-Fi Aware.
     * <p>Note: The broadcast is only delivered to registered receivers - no manifest registered
     * components will be launched.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_WIFI_AWARE_STATE_CHANGED =
            "android.net.wifi.aware.action.WIFI_AWARE_STATE_CHANGED";

    /** @hide */
    @IntDef({
            WIFI_AWARE_DATA_PATH_ROLE_INITIATOR, WIFI_AWARE_DATA_PATH_ROLE_RESPONDER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DataPathRole {
    }

    /**
     * Connection creation role is that of INITIATOR. Used to create a network specifier string
     * when requesting a Aware network.
     *
     * @see WifiAwareSession#createNetworkSpecifierOpen(int, byte[])
     * @see WifiAwareSession#createNetworkSpecifierPassphrase(int, byte[], String)
     */
    public static final int WIFI_AWARE_DATA_PATH_ROLE_INITIATOR = 0;

    /**
     * Connection creation role is that of RESPONDER. Used to create a network specifier string
     * when requesting a Aware network.
     *
     * @see WifiAwareSession#createNetworkSpecifierOpen(int, byte[])
     * @see WifiAwareSession#createNetworkSpecifierPassphrase(int, byte[], String)
     */
    public static final int WIFI_AWARE_DATA_PATH_ROLE_RESPONDER = 1;

    private final Context mContext;
    private final IWifiAwareManager mService;

    private final Object mLock = new Object(); // lock access to the following vars

    /** @hide */
    public WifiAwareManager(Context context, IWifiAwareManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns the current status of Aware API: whether or not Aware is available. To track
     * changes in the state of Aware API register for the
     * {@link #ACTION_WIFI_AWARE_STATE_CHANGED} broadcast.
     *
     * @return A boolean indicating whether the app can use the Aware API at this time (true) or
     * not (false).
     */
    public boolean isAvailable() {
        try {
            return mService.isUsageEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the characteristics of the Wi-Fi Aware interface: a set of parameters which specify
     * limitations on configurations, e.g. the maximum service name length.
     *
     * @return An object specifying configuration limitations of Aware.
     */
    public Characteristics getCharacteristics() {
        try {
            return mService.getCharacteristics();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Attach to the Wi-Fi Aware service - enabling the application to create discovery sessions or
     * create connections to peers. The device will attach to an existing cluster if it can find
     * one or create a new cluster (if it is the first to enable Aware in its vicinity). Results
     * (e.g. successful attach to a cluster) are provided to the {@code attachCallback} object.
     * An application <b>must</b> call {@link WifiAwareSession#close()} when done with the
     * Wi-Fi Aware object.
     * <p>
     * Note: a Aware cluster is a shared resource - if the device is already attached to a cluster
     * then this function will simply indicate success immediately using the same {@code
     * attachCallback}.
     *
     * @param attachCallback A callback for attach events, extended from
     * {@link AttachCallback}.
     * @param handler The Handler on whose thread to execute the callbacks of the {@code
     * attachCallback} object. If a null is provided then the application's main thread will be
     *                used.
     */
    public void attach(@NonNull AttachCallback attachCallback, @Nullable Handler handler) {
        attach(handler, null, attachCallback, null);
    }

    /**
     * Attach to the Wi-Fi Aware service - enabling the application to create discovery sessions or
     * create connections to peers. The device will attach to an existing cluster if it can find
     * one or create a new cluster (if it is the first to enable Aware in its vicinity). Results
     * (e.g. successful attach to a cluster) are provided to the {@code attachCallback} object.
     * An application <b>must</b> call {@link WifiAwareSession#close()} when done with the
     * Wi-Fi Aware object.
     * <p>
     * Note: a Aware cluster is a shared resource - if the device is already attached to a cluster
     * then this function will simply indicate success immediately using the same {@code
     * attachCallback}.
     * <p>
     * This version of the API attaches a listener to receive the MAC address of the Aware interface
     * on startup and whenever it is updated (it is randomized at regular intervals for privacy).
     * The application must have the {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission to execute this attach request. Otherwise, use the
     * {@link #attach(AttachCallback, Handler)} version. Note that aside from permission
     * requirements this listener will wake up the host at regular intervals causing higher power
     * consumption, do not use it unless the information is necessary (e.g. for OOB discovery).
     *
     * @param attachCallback A callback for attach events, extended from
     * {@link AttachCallback}.
     * @param identityChangedListener A listener for changed identity, extended from
     * {@link IdentityChangedListener}.
     * @param handler The Handler on whose thread to execute the callbacks of the {@code
     * attachCallback} and {@code identityChangedListener} objects. If a null is provided then the
     *                application's main thread will be used.
     */
    public void attach(@NonNull AttachCallback attachCallback,
            @NonNull IdentityChangedListener identityChangedListener,
            @Nullable Handler handler) {
        attach(handler, null, attachCallback, identityChangedListener);
    }

    /** @hide */
    public void attach(Handler handler, ConfigRequest configRequest,
            AttachCallback attachCallback,
            IdentityChangedListener identityChangedListener) {
        if (VDBG) {
            Log.v(TAG, "attach(): handler=" + handler + ", callback=" + attachCallback
                    + ", configRequest=" + configRequest + ", identityChangedListener="
                    + identityChangedListener);
        }

        if (attachCallback == null) {
            throw new IllegalArgumentException("Null callback provided");
        }

        synchronized (mLock) {
            Looper looper = (handler == null) ? Looper.getMainLooper() : handler.getLooper();

            try {
                Binder binder = new Binder();
                mService.connect(binder, mContext.getOpPackageName(),
                        new WifiAwareEventCallbackProxy(this, looper, binder, attachCallback,
                                identityChangedListener), configRequest,
                        identityChangedListener != null);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /** @hide */
    public void disconnect(int clientId, Binder binder) {
        if (VDBG) Log.v(TAG, "disconnect()");

        try {
            mService.disconnect(clientId, binder);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void publish(int clientId, Looper looper, PublishConfig publishConfig,
            DiscoverySessionCallback callback) {
        if (VDBG) Log.v(TAG, "publish(): clientId=" + clientId + ", config=" + publishConfig);

        if (callback == null) {
            throw new IllegalArgumentException("Null callback provided");
        }

        try {
            mService.publish(mContext.getOpPackageName(), clientId, publishConfig,
                    new WifiAwareDiscoverySessionCallbackProxy(this, looper, true, callback,
                            clientId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void updatePublish(int clientId, int sessionId, PublishConfig publishConfig) {
        if (VDBG) {
            Log.v(TAG, "updatePublish(): clientId=" + clientId + ",sessionId=" + sessionId
                    + ", config=" + publishConfig);
        }

        try {
            mService.updatePublish(clientId, sessionId, publishConfig);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void subscribe(int clientId, Looper looper, SubscribeConfig subscribeConfig,
            DiscoverySessionCallback callback) {
        if (VDBG) {
            if (VDBG) {
                Log.v(TAG,
                        "subscribe(): clientId=" + clientId + ", config=" + subscribeConfig);
            }
        }

        if (callback == null) {
            throw new IllegalArgumentException("Null callback provided");
        }

        try {
            mService.subscribe(mContext.getOpPackageName(), clientId, subscribeConfig,
                    new WifiAwareDiscoverySessionCallbackProxy(this, looper, false, callback,
                            clientId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void updateSubscribe(int clientId, int sessionId, SubscribeConfig subscribeConfig) {
        if (VDBG) {
            Log.v(TAG, "updateSubscribe(): clientId=" + clientId + ",sessionId=" + sessionId
                    + ", config=" + subscribeConfig);
        }

        try {
            mService.updateSubscribe(clientId, sessionId, subscribeConfig);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void terminateSession(int clientId, int sessionId) {
        if (VDBG) {
            Log.d(TAG,
                    "terminateSession(): clientId=" + clientId + ", sessionId=" + sessionId);
        }

        try {
            mService.terminateSession(clientId, sessionId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void sendMessage(int clientId, int sessionId, PeerHandle peerHandle, byte[] message,
            int messageId, int retryCount) {
        if (peerHandle == null) {
            throw new IllegalArgumentException(
                    "sendMessage: invalid peerHandle - must be non-null");
        }

        if (VDBG) {
            Log.v(TAG, "sendMessage(): clientId=" + clientId + ", sessionId=" + sessionId
                    + ", peerHandle=" + peerHandle.peerId + ", messageId="
                    + messageId + ", retryCount=" + retryCount);
        }

        try {
            mService.sendMessage(clientId, sessionId, peerHandle.peerId, message, messageId,
                    retryCount);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public NetworkSpecifier createNetworkSpecifier(int clientId, int role, int sessionId,
            @NonNull PeerHandle peerHandle, @Nullable byte[] pmk, @Nullable String passphrase) {
        if (VDBG) {
            Log.v(TAG, "createNetworkSpecifier: role=" + role + ", sessionId=" + sessionId
                    + ", peerHandle=" + ((peerHandle == null) ? peerHandle : peerHandle.peerId)
                    + ", pmk=" + ((pmk == null) ? "null" : "non-null")
                    + ", passphrase=" + ((passphrase == null) ? "null" : "non-null"));
        }

        if (!WifiAwareUtils.isLegacyVersion(mContext, Build.VERSION_CODES.Q)) {
            throw new UnsupportedOperationException(
                    "API not deprecated - use WifiAwareManager.NetworkSpecifierBuilder");
        }

        if (role != WIFI_AWARE_DATA_PATH_ROLE_INITIATOR
                && role != WIFI_AWARE_DATA_PATH_ROLE_RESPONDER) {
            throw new IllegalArgumentException(
                    "createNetworkSpecifier: Invalid 'role' argument when creating a network "
                            + "specifier");
        }
        if (role == WIFI_AWARE_DATA_PATH_ROLE_INITIATOR || !WifiAwareUtils.isLegacyVersion(mContext,
                Build.VERSION_CODES.P)) {
            if (peerHandle == null) {
                throw new IllegalArgumentException(
                        "createNetworkSpecifier: Invalid peer handle - cannot be null");
            }
        }

        return new WifiAwareNetworkSpecifier(
                (peerHandle == null) ? WifiAwareNetworkSpecifier.NETWORK_SPECIFIER_TYPE_IB_ANY_PEER
                        : WifiAwareNetworkSpecifier.NETWORK_SPECIFIER_TYPE_IB,
                role,
                clientId,
                sessionId,
                peerHandle != null ? peerHandle.peerId : 0, // 0 is an invalid peer ID
                null, // peerMac (not used in this method)
                pmk,
                passphrase,
                0, // no port info for deprecated IB APIs
                -1, // no transport info for deprecated IB APIs
                Process.myUid());
    }

    /** @hide */
    public NetworkSpecifier createNetworkSpecifier(int clientId, @DataPathRole int role,
            @NonNull byte[] peer, @Nullable byte[] pmk, @Nullable String passphrase) {
        if (VDBG) {
            Log.v(TAG, "createNetworkSpecifier: role=" + role
                    + ", pmk=" + ((pmk == null) ? "null" : "non-null")
                    + ", passphrase=" + ((passphrase == null) ? "null" : "non-null"));
        }

        if (role != WIFI_AWARE_DATA_PATH_ROLE_INITIATOR
                && role != WIFI_AWARE_DATA_PATH_ROLE_RESPONDER) {
            throw new IllegalArgumentException(
                    "createNetworkSpecifier: Invalid 'role' argument when creating a network "
                            + "specifier");
        }
        if (role == WIFI_AWARE_DATA_PATH_ROLE_INITIATOR || !WifiAwareUtils.isLegacyVersion(mContext,
                Build.VERSION_CODES.P)) {
            if (peer == null) {
                throw new IllegalArgumentException(
                        "createNetworkSpecifier: Invalid peer MAC - cannot be null");
            }
        }
        if (peer != null && peer.length != 6) {
            throw new IllegalArgumentException("createNetworkSpecifier: Invalid peer MAC address");
        }

        return new WifiAwareNetworkSpecifier(
                (peer == null) ? WifiAwareNetworkSpecifier.NETWORK_SPECIFIER_TYPE_OOB_ANY_PEER
                        : WifiAwareNetworkSpecifier.NETWORK_SPECIFIER_TYPE_OOB,
                role,
                clientId,
                0, // 0 is an invalid session ID
                0, // 0 is an invalid peer ID
                peer,
                pmk,
                passphrase,
                0, // no port info for OOB APIs
                -1, // no transport protocol info for OOB APIs
                Process.myUid());
    }

    private static class WifiAwareEventCallbackProxy extends IWifiAwareEventCallback.Stub {
        private static final int CALLBACK_CONNECT_SUCCESS = 0;
        private static final int CALLBACK_CONNECT_FAIL = 1;
        private static final int CALLBACK_IDENTITY_CHANGED = 2;

        private final Handler mHandler;
        private final WeakReference<WifiAwareManager> mAwareManager;
        private final Binder mBinder;
        private final Looper mLooper;

        /**
         * Constructs a {@link AttachCallback} using the specified looper.
         * All callbacks will delivered on the thread of the specified looper.
         *
         * @param looper The looper on which to execute the callbacks.
         */
        WifiAwareEventCallbackProxy(WifiAwareManager mgr, Looper looper, Binder binder,
                final AttachCallback attachCallback,
                final IdentityChangedListener identityChangedListener) {
            mAwareManager = new WeakReference<>(mgr);
            mLooper = looper;
            mBinder = binder;

            if (VDBG) Log.v(TAG, "WifiAwareEventCallbackProxy ctor: looper=" + looper);
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    if (DBG) {
                        Log.d(TAG, "WifiAwareEventCallbackProxy: What=" + msg.what + ", msg="
                                + msg);
                    }

                    WifiAwareManager mgr = mAwareManager.get();
                    if (mgr == null) {
                        Log.w(TAG, "WifiAwareEventCallbackProxy: handleMessage post GC");
                        return;
                    }

                    switch (msg.what) {
                        case CALLBACK_CONNECT_SUCCESS:
                            attachCallback.onAttached(
                                    new WifiAwareSession(mgr, mBinder, msg.arg1));
                            break;
                        case CALLBACK_CONNECT_FAIL:
                            mAwareManager.clear();
                            attachCallback.onAttachFailed();
                            break;
                        case CALLBACK_IDENTITY_CHANGED:
                            if (identityChangedListener == null) {
                                Log.e(TAG, "CALLBACK_IDENTITY_CHANGED: null listener.");
                            } else {
                                identityChangedListener.onIdentityChanged((byte[]) msg.obj);
                            }
                            break;
                    }
                }
            };
        }

        @Override
        public void onConnectSuccess(int clientId) {
            if (VDBG) Log.v(TAG, "onConnectSuccess");

            Message msg = mHandler.obtainMessage(CALLBACK_CONNECT_SUCCESS);
            msg.arg1 = clientId;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onConnectFail(int reason) {
            if (VDBG) Log.v(TAG, "onConnectFail: reason=" + reason);

            Message msg = mHandler.obtainMessage(CALLBACK_CONNECT_FAIL);
            msg.arg1 = reason;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onIdentityChanged(byte[] mac) {
            if (VDBG) Log.v(TAG, "onIdentityChanged: mac=" + new String(HexEncoding.encode(mac)));

            Message msg = mHandler.obtainMessage(CALLBACK_IDENTITY_CHANGED);
            msg.obj = mac;
            mHandler.sendMessage(msg);
        }
    }

    private static class WifiAwareDiscoverySessionCallbackProxy extends
            IWifiAwareDiscoverySessionCallback.Stub {
        private static final int CALLBACK_SESSION_STARTED = 0;
        private static final int CALLBACK_SESSION_CONFIG_SUCCESS = 1;
        private static final int CALLBACK_SESSION_CONFIG_FAIL = 2;
        private static final int CALLBACK_SESSION_TERMINATED = 3;
        private static final int CALLBACK_MATCH = 4;
        private static final int CALLBACK_MESSAGE_SEND_SUCCESS = 5;
        private static final int CALLBACK_MESSAGE_SEND_FAIL = 6;
        private static final int CALLBACK_MESSAGE_RECEIVED = 7;
        private static final int CALLBACK_MATCH_WITH_DISTANCE = 8;

        private static final String MESSAGE_BUNDLE_KEY_MESSAGE = "message";
        private static final String MESSAGE_BUNDLE_KEY_MESSAGE2 = "message2";

        private final WeakReference<WifiAwareManager> mAwareManager;
        private final boolean mIsPublish;
        private final DiscoverySessionCallback mOriginalCallback;
        private final int mClientId;

        private final Handler mHandler;
        private DiscoverySession mSession;

        WifiAwareDiscoverySessionCallbackProxy(WifiAwareManager mgr, Looper looper,
                boolean isPublish, DiscoverySessionCallback originalCallback,
                int clientId) {
            mAwareManager = new WeakReference<>(mgr);
            mIsPublish = isPublish;
            mOriginalCallback = originalCallback;
            mClientId = clientId;

            if (VDBG) {
                Log.v(TAG, "WifiAwareDiscoverySessionCallbackProxy ctor: isPublish=" + isPublish);
            }

            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    if (DBG) Log.d(TAG, "What=" + msg.what + ", msg=" + msg);

                    if (mAwareManager.get() == null) {
                        Log.w(TAG, "WifiAwareDiscoverySessionCallbackProxy: handleMessage post GC");
                        return;
                    }

                    switch (msg.what) {
                        case CALLBACK_SESSION_STARTED:
                            onProxySessionStarted(msg.arg1);
                            break;
                        case CALLBACK_SESSION_CONFIG_SUCCESS:
                            mOriginalCallback.onSessionConfigUpdated();
                            break;
                        case CALLBACK_SESSION_CONFIG_FAIL:
                            mOriginalCallback.onSessionConfigFailed();
                            if (mSession == null) {
                                /*
                                 * creation failed (as opposed to update
                                 * failing)
                                 */
                                mAwareManager.clear();
                            }
                            break;
                        case CALLBACK_SESSION_TERMINATED:
                            onProxySessionTerminated(msg.arg1);
                            break;
                        case CALLBACK_MATCH:
                        case CALLBACK_MATCH_WITH_DISTANCE:
                            {
                            List<byte[]> matchFilter = null;
                            byte[] arg = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE2);
                            try {
                                matchFilter = new TlvBufferUtils.TlvIterable(0, 1, arg).toList();
                            } catch (BufferOverflowException e) {
                                matchFilter = null;
                                Log.e(TAG, "onServiceDiscovered: invalid match filter byte array '"
                                        + new String(HexEncoding.encode(arg))
                                        + "' - cannot be parsed: e=" + e);
                            }
                            if (msg.what == CALLBACK_MATCH) {
                                mOriginalCallback.onServiceDiscovered(new PeerHandle(msg.arg1),
                                        msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE),
                                        matchFilter);
                            } else {
                                mOriginalCallback.onServiceDiscoveredWithinRange(
                                        new PeerHandle(msg.arg1),
                                        msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE),
                                        matchFilter, msg.arg2);
                            }
                            break;
                        }
                        case CALLBACK_MESSAGE_SEND_SUCCESS:
                            mOriginalCallback.onMessageSendSucceeded(msg.arg1);
                            break;
                        case CALLBACK_MESSAGE_SEND_FAIL:
                            mOriginalCallback.onMessageSendFailed(msg.arg1);
                            break;
                        case CALLBACK_MESSAGE_RECEIVED:
                            mOriginalCallback.onMessageReceived(new PeerHandle(msg.arg1),
                                    (byte[]) msg.obj);
                            break;
                    }
                }
            };
        }

        @Override
        public void onSessionStarted(int sessionId) {
            if (VDBG) Log.v(TAG, "onSessionStarted: sessionId=" + sessionId);

            Message msg = mHandler.obtainMessage(CALLBACK_SESSION_STARTED);
            msg.arg1 = sessionId;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onSessionConfigSuccess() {
            if (VDBG) Log.v(TAG, "onSessionConfigSuccess");

            Message msg = mHandler.obtainMessage(CALLBACK_SESSION_CONFIG_SUCCESS);
            mHandler.sendMessage(msg);
        }

        @Override
        public void onSessionConfigFail(int reason) {
            if (VDBG) Log.v(TAG, "onSessionConfigFail: reason=" + reason);

            Message msg = mHandler.obtainMessage(CALLBACK_SESSION_CONFIG_FAIL);
            msg.arg1 = reason;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onSessionTerminated(int reason) {
            if (VDBG) Log.v(TAG, "onSessionTerminated: reason=" + reason);

            Message msg = mHandler.obtainMessage(CALLBACK_SESSION_TERMINATED);
            msg.arg1 = reason;
            mHandler.sendMessage(msg);
        }

        private void onMatchCommon(int messageType, int peerId, byte[] serviceSpecificInfo,
                byte[] matchFilter, int distanceMm) {
            Bundle data = new Bundle();
            data.putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE, serviceSpecificInfo);
            data.putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE2, matchFilter);

            Message msg = mHandler.obtainMessage(messageType);
            msg.arg1 = peerId;
            msg.arg2 = distanceMm;
            msg.setData(data);
            mHandler.sendMessage(msg);
        }

        @Override
        public void onMatch(int peerId, byte[] serviceSpecificInfo, byte[] matchFilter) {
            if (VDBG) Log.v(TAG, "onMatch: peerId=" + peerId);

            onMatchCommon(CALLBACK_MATCH, peerId, serviceSpecificInfo, matchFilter, 0);
        }

        @Override
        public void onMatchWithDistance(int peerId, byte[] serviceSpecificInfo, byte[] matchFilter,
                int distanceMm) {
            if (VDBG) {
                Log.v(TAG, "onMatchWithDistance: peerId=" + peerId + ", distanceMm=" + distanceMm);
            }

            onMatchCommon(CALLBACK_MATCH_WITH_DISTANCE, peerId, serviceSpecificInfo, matchFilter,
                    distanceMm);
        }

        @Override
        public void onMessageSendSuccess(int messageId) {
            if (VDBG) Log.v(TAG, "onMessageSendSuccess");

            Message msg = mHandler.obtainMessage(CALLBACK_MESSAGE_SEND_SUCCESS);
            msg.arg1 = messageId;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onMessageSendFail(int messageId, int reason) {
            if (VDBG) Log.v(TAG, "onMessageSendFail: reason=" + reason);

            Message msg = mHandler.obtainMessage(CALLBACK_MESSAGE_SEND_FAIL);
            msg.arg1 = messageId;
            msg.arg2 = reason;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onMessageReceived(int peerId, byte[] message) {
            if (VDBG) {
                Log.v(TAG, "onMessageReceived: peerId=" + peerId);
            }

            Message msg = mHandler.obtainMessage(CALLBACK_MESSAGE_RECEIVED);
            msg.arg1 = peerId;
            msg.obj = message;
            mHandler.sendMessage(msg);
        }

        /*
         * Proxied methods
         */
        public void onProxySessionStarted(int sessionId) {
            if (VDBG) Log.v(TAG, "Proxy: onSessionStarted: sessionId=" + sessionId);
            if (mSession != null) {
                Log.e(TAG,
                        "onSessionStarted: sessionId=" + sessionId + ": session already created!?");
                throw new IllegalStateException(
                        "onSessionStarted: sessionId=" + sessionId + ": session already created!?");
            }

            WifiAwareManager mgr = mAwareManager.get();
            if (mgr == null) {
                Log.w(TAG, "onProxySessionStarted: mgr GC'd");
                return;
            }

            if (mIsPublish) {
                PublishDiscoverySession session = new PublishDiscoverySession(mgr,
                        mClientId, sessionId);
                mSession = session;
                mOriginalCallback.onPublishStarted(session);
            } else {
                SubscribeDiscoverySession
                        session = new SubscribeDiscoverySession(mgr, mClientId, sessionId);
                mSession = session;
                mOriginalCallback.onSubscribeStarted(session);
            }
        }

        public void onProxySessionTerminated(int reason) {
            if (VDBG) Log.v(TAG, "Proxy: onSessionTerminated: reason=" + reason);
            if (mSession != null) {
                mSession.setTerminated();
                mSession = null;
            } else {
                Log.w(TAG, "Proxy: onSessionTerminated called but mSession is null!?");
            }
            mAwareManager.clear();
            mOriginalCallback.onSessionTerminated();
        }
    }

    /**
     * A builder class for a Wi-Fi Aware network specifier to set up an Aware connection with a
     * peer.
     * <p>
     * Note that all Wi-Fi Aware connection specifier objects must call the
     * {@link NetworkSpecifierBuilder#setDiscoverySession(DiscoverySession)} to specify the context
     * within which the connection is created, and
     * {@link NetworkSpecifierBuilder#setPeerHandle(PeerHandle)} to specify the peer to which the
     * connection is created.
     */
    public static final class NetworkSpecifierBuilder {
        private DiscoverySession mDiscoverySession;
        private PeerHandle mPeerHandle;
        private String mPskPassphrase;
        private byte[] mPmk;
        private int mPort = 0; // invalid value
        private int mTransportProtocol = -1; // invalid value

        /**
         * Configure the {@link PublishDiscoverySession} or {@link SubscribeDiscoverySession}
         * discovery session in whose context the connection is created.
         * <p>
         * Note: this method must be called for any connection request!
         *
         * @param discoverySession A Wi-Fi Aware discovery session.
         * @return the current {@link NetworkSpecifierBuilder} builder, enabling chaining of builder
         *         methods.
         */
        public @NonNull NetworkSpecifierBuilder setDiscoverySession(
                @NonNull DiscoverySession discoverySession) {
            if (discoverySession == null) {
                throw new IllegalArgumentException("Non-null discoverySession required");
            }
            mDiscoverySession = discoverySession;
            return this;
        }

        /**
         * Configure the {@link PeerHandle} of the peer to which the Wi-Fi Aware connection is
         * requested. The peer is discovered through Wi-Fi Aware discovery,
         * <p>
         * Note: this method must be called for any connection request!
         *
         * @param peerHandle The peer's handle obtained through
         * {@link DiscoverySessionCallback#onServiceDiscovered(PeerHandle, byte[], java.util.List)}
         *                   or
         *                   {@link DiscoverySessionCallback#onMessageReceived(PeerHandle, byte[])}.
         * @return the current {@link NetworkSpecifierBuilder} builder, enabling chaining of builder
         *         methods.
         */
        public @NonNull NetworkSpecifierBuilder setPeerHandle(@NonNull PeerHandle peerHandle) {
            if (peerHandle == null) {
                throw new IllegalArgumentException("Non-null peerHandle required");
            }
            mPeerHandle = peerHandle;
            return this;
        }

        /**
         * Configure the PSK Passphrase for the Wi-Fi Aware connection being requested. This method
         * is optional - if not called, then an Open (unencrypted) connection will be created.
         *
         * @param pskPassphrase The (optional) passphrase to be used to encrypt the link.
         * @return the current {@link NetworkSpecifierBuilder} builder, enabling chaining of builder
         *         methods.
         */
        public @NonNull NetworkSpecifierBuilder setPskPassphrase(@NonNull String pskPassphrase) {
            if (!WifiAwareUtils.validatePassphrase(pskPassphrase)) {
                throw new IllegalArgumentException("Passphrase must meet length requirements");
            }
            mPskPassphrase = pskPassphrase;
            return this;
        }

        /**
         * Configure the PMK for the Wi-Fi Aware connection being requested. This method
         * is optional - if not called, then an Open (unencrypted) connection will be created.
         *
         * @param pmk A PMK (pairwise master key, see IEEE 802.11i) specifying the key to use for
         *            encrypting the data-path. Use the {@link #setPskPassphrase(String)} to
         *            specify a Passphrase.
         * @return the current {@link NetworkSpecifierBuilder} builder, enabling chaining of builder
         *         methods.
         * @hide
         */
        @SystemApi
        public @NonNull NetworkSpecifierBuilder setPmk(@NonNull byte[] pmk) {
            if (!WifiAwareUtils.validatePmk(pmk)) {
                throw new IllegalArgumentException("PMK must 32 bytes");
            }
            mPmk = pmk;
            return this;
        }

        /**
         * Configure the port number which will be used to create a connection over this link. This
         * configuration should only be done on the server device, e.g. the device creating the
         * {@link java.net.ServerSocket}.
         * <p>Notes:
         * <ul>
         *     <li>The server device must be the Publisher device!
         *     <li>The port information can only be specified on secure links, specified using
         *     {@link #setPskPassphrase(String)}.
         * </ul>
         *
         * @param port A positive integer indicating the port to be used for communication.
         * @return the current {@link NetworkSpecifierBuilder} builder, enabling chaining of builder
         *         methods.
         */
        public @NonNull NetworkSpecifierBuilder setPort(int port) {
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("The port must be a positive value (0, 65535]");
            }
            mPort = port;
            return this;
        }

        /**
         * Configure the transport protocol which will be used to create a connection over this
         * link. This configuration should only be done on the server device, e.g. the device
         * creating the {@link java.net.ServerSocket} for TCP.
         * <p>Notes:
         * <ul>
         *     <li>The server device must be the Publisher device!
         *     <li>The transport protocol information can only be specified on secure links,
         *     specified using {@link #setPskPassphrase(String)}.
         * </ul>
         * The transport protocol number is assigned by the Internet Assigned Numbers Authority
         * (IANA) https://www.iana.org/assignments/protocol-numbers/protocol-numbers.xhtml.
         *
         * @param transportProtocol The transport protocol to be used for communication.
         * @return the current {@link NetworkSpecifierBuilder} builder, enabling chaining of builder
         *         methods.
         */
        public @NonNull NetworkSpecifierBuilder setTransportProtocol(int transportProtocol) {
            if (transportProtocol < 0 || transportProtocol > 255) {
                throw new IllegalArgumentException(
                        "The transport protocol must be in range [0, 255]");
            }
            mTransportProtocol = transportProtocol;
            return this;
        }

        /**
         * Create a {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)}
         * for a WiFi Aware connection (link) to the specified peer. The
         * {@link android.net.NetworkRequest.Builder#addTransportType(int)} should be set to
         * {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_AWARE}.
         * <p> The default builder constructor will initialize a NetworkSpecifier which requests an
         * open (non-encrypted) link. To request an encrypted link use the
         * {@link #setPskPassphrase(String)} builder method.
         *
         * @return A {@link NetworkSpecifier} to be used to construct
         * {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} to pass
         * to {@link android.net.ConnectivityManager#requestNetwork(android.net.NetworkRequest,
         * android.net.ConnectivityManager.NetworkCallback)}
         * [or other varieties of that API].
         */
        public @NonNull NetworkSpecifier build() {
            if (mDiscoverySession == null) {
                throw new IllegalStateException("Null discovery session!?");
            }
            if (mPskPassphrase != null & mPmk != null) {
                throw new IllegalStateException(
                        "Can only specify a Passphrase or a PMK - not both!");
            }

            int role = mDiscoverySession instanceof SubscribeDiscoverySession
                    ? WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_INITIATOR
                    : WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER;

            if (mPort != 0 || mTransportProtocol != -1) {
                if (role != WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER) {
                    throw new IllegalStateException(
                            "Port and transport protocol information can only "
                                    + "be specified on the Publisher device (which is the server");
                }
                if (TextUtils.isEmpty(mPskPassphrase) && mPmk == null) {
                    throw new IllegalStateException("Port and transport protocol information can "
                            + "only be specified on a secure link");
                }
            }

            if (role == WIFI_AWARE_DATA_PATH_ROLE_INITIATOR && mPeerHandle == null) {
                throw new IllegalStateException("Null peerHandle!?");
            }

            return new WifiAwareNetworkSpecifier(
                    WifiAwareNetworkSpecifier.NETWORK_SPECIFIER_TYPE_IB, role,
                    mDiscoverySession.mClientId, mDiscoverySession.mSessionId, mPeerHandle.peerId,
                    null, mPmk, mPskPassphrase, mPort, mTransportProtocol, Process.myUid());
        }
    }
}
