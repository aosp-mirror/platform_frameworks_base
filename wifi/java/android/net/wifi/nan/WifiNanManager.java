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

package android.net.wifi.nan;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import android.net.wifi.RttManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import libcore.util.HexEncoding;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * This class provides the primary API for managing Wi-Fi NAN operations:
 * discovery and peer-to-peer data connections. Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(String)
 * Context.getSystemService(Context.WIFI_NAN_SERVICE)}.
 * <p>
 * The class provides access to:
 * <ul>
 * <li>Initialize a NAN cluster (peer-to-peer synchronization). Refer to
 * {@link #attach(Handler, WifiNanAttachCallback)}.
 * <li>Create discovery sessions (publish or subscribe sessions). Refer to
 * {@link WifiNanSession#publish(PublishConfig, WifiNanDiscoverySessionCallback)} and
 * {@link WifiNanSession#subscribe(SubscribeConfig, WifiNanDiscoverySessionCallback)}.
 * <li>Create a NAN network specifier to be used with
 * {@link ConnectivityManager#requestNetwork(NetworkRequest, ConnectivityManager.NetworkCallback)}
 * to set-up a NAN connection with a peer. Refer to
 * {@link WifiNanDiscoveryBaseSession#createNetworkSpecifier(int, int, byte[])} and
 * {@link WifiNanSession#createNetworkSpecifier(int, byte[], byte[])}.
 * </ul>
 * <p>
 *     NAN may not be usable when Wi-Fi is disabled (and other conditions). To validate that
 *     the functionality is available use the {@link #isAvailable()} function. To track
 *     changes in NAN usability register for the {@link #ACTION_WIFI_NAN_STATE_CHANGED} broadcast.
 *     Note that this broadcast is not sticky - you should register for it and then check the
 *     above API to avoid a race condition.
 * <p>
 *     An application must use {@link #attach(Handler, WifiNanAttachCallback)} to initialize a NAN
 *     cluster - before making any other NAN operation. NAN cluster membership is a device-wide
 *     operation - the API guarantees that the device is in a cluster or joins a NAN cluster (or
 *     starts one if none can be found). Information about attach success (or failure) are
 *     returned in callbacks of {@link WifiNanAttachCallback}. Proceed with NAN discovery or
 *     connection setup only after receiving confirmation that NAN attach succeeded -
 *     {@link WifiNanAttachCallback#onAttached(WifiNanSession)}. When an application is
 *     finished using NAN it <b>must</b> use the {@link WifiNanSession#destroy()} API
 *     to indicate to the NAN service that the device may detach from the NAN cluster. The
 *     device will actually disable NAN once the last application detaches.
 * <p>
 *     Once a NAN attach is confirmed use the
 *     {@link WifiNanSession#publish(PublishConfig, WifiNanDiscoverySessionCallback)} or
 *     {@link WifiNanSession#subscribe(SubscribeConfig, WifiNanDiscoverySessionCallback)} to
 *     create publish or subscribe NAN discovery sessions. Events are called on the provided
 *     callback object {@link WifiNanDiscoverySessionCallback}. Specifically, the
 *     {@link WifiNanDiscoverySessionCallback#onPublishStarted(WifiNanPublishDiscoverySession)}
 *     and
 *     {@link WifiNanDiscoverySessionCallback#onSubscribeStarted(WifiNanSubscribeDiscoverySession)}
 *     return {@link WifiNanPublishDiscoverySession} and {@link WifiNanSubscribeDiscoverySession}
 *     objects respectively on which additional session operations can be performed, e.g. updating
 *     the session {@link WifiNanPublishDiscoverySession#updatePublish(PublishConfig)} and
 *     {@link WifiNanSubscribeDiscoverySession#updateSubscribe(SubscribeConfig)}. Sessions can also
 *     be used to send messages using the
 *     {@link WifiNanDiscoveryBaseSession#sendMessage(int, byte[], int)} APIs. When an application
 *     is finished with a discovery session it <b>must</b> terminate it using the
 *     {@link WifiNanDiscoveryBaseSession#destroy()} API.
 * <p>
 *    Creating connections between NAN devices is managed by the standard
 *    {@link ConnectivityManager#requestNetwork(NetworkRequest, ConnectivityManager.NetworkCallback)}.
 *    The {@link NetworkRequest} object should be constructed with:
 *    <ul>
 *        <li>{@link NetworkRequest.Builder#addTransportType(int)} of
 *        {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_NAN}.
 *        <li>{@link NetworkRequest.Builder#setNetworkSpecifier(String)} using
 *        {@link WifiNanSession#createNetworkSpecifier(int, byte[], byte[])} or
 *        {@link WifiNanDiscoveryBaseSession#createNetworkSpecifier(int, int, byte[])}.
 *    </ul>
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanManager {
    private static final String TAG = "WifiNanManager";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    /**
     * Keys used to generate a Network Specifier for the NAN network request. The network specifier
     * is formatted as a JSON string.
     */

    /**
     * TYPE_1A: role, client_id, session_id, peer_id, token
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_1A = 0;

    /**
     * TYPE_1B: role, client_id, session_id, peer_id [only permitted for RESPONDER]
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_1B = 1;

    /**
     * TYPE_1C: role, client_id, session_id, token [only permitted for RESPONDER]
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_1C = 2;

    /**
     * TYPE_1C: role, client_id, session_id [only permitted for RESPONDER]
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_1D = 3;

    /**
     * TYPE_2A: role, client_id, peer_mac, token
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_2A = 4;

    /**
     * TYPE_2B: role, client_id, peer_mac [only permitted for RESPONDER]
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_2B = 5;

    /**
     * TYPE_2C: role, client_id, token [only permitted for RESPONDER]
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_2C = 6;

    /**
     * TYPE_2D: role, client_id [only permitted for RESPONDER]
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_2D = 7;

    /** @hide */
    public static final int NETWORK_SPECIFIER_TYPE_MAX_VALID = NETWORK_SPECIFIER_TYPE_2D;

    /** @hide */
    public static final String NETWORK_SPECIFIER_KEY_TYPE = "type";

    /** @hide */
    public static final String NETWORK_SPECIFIER_KEY_ROLE = "role";

    /** @hide */
    public static final String NETWORK_SPECIFIER_KEY_CLIENT_ID = "client_id";

    /** @hide */
    public static final String NETWORK_SPECIFIER_KEY_SESSION_ID = "session_id";

    /** @hide */
    public static final String NETWORK_SPECIFIER_KEY_PEER_ID = "peer_id";

    /** @hide */
    public static final String NETWORK_SPECIFIER_KEY_PEER_MAC = "peer_mac";

    /** @hide */
    public static final String NETWORK_SPECIFIER_KEY_TOKEN = "token";

    /**
     * Broadcast intent action to indicate whether Wi-Fi NAN is enabled or
     * disabled. An extra {@link #EXTRA_WIFI_STATE} provides the state
     * information as int using {@link #WIFI_NAN_STATE_DISABLED} and
     * {@link #WIFI_NAN_STATE_ENABLED} constants. This broadcast is <b>not</b> sticky,
     * use the {@link #isAvailable()} API after registering the broadcast to check the current
     * state of Wi-Fi NAN.
     *
     * @see #EXTRA_WIFI_STATE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_WIFI_NAN_STATE_CHANGED =
            "android.net.wifi.nan.action.WIFI_NAN_STATE_CHANGED";

    /**
     * The lookup key for an int value indicating whether Wi-Fi NAN is enabled or
     * disabled. Retrieve it with
     * {@link android.content.Intent#getIntExtra(String,int)}.
     *
     * @see #WIFI_NAN_STATE_DISABLED
     * @see #WIFI_NAN_STATE_ENABLED
     */
    public static final String EXTRA_WIFI_STATE = "android.net.wifi.nan.extra.WIFI_STATE";

    /**
     * Wi-Fi NAN is disabled.
     *
     * @see #ACTION_WIFI_NAN_STATE_CHANGED
     */
    public static final int WIFI_NAN_STATE_DISABLED = 1;

    /**
     * Wi-Fi NAN is enabled.
     *
     * @see #ACTION_WIFI_NAN_STATE_CHANGED
     */
    public static final int WIFI_NAN_STATE_ENABLED = 2;

    /** @hide */
    @IntDef({
            WIFI_NAN_DATA_PATH_ROLE_INITIATOR, WIFI_NAN_DATA_PATH_ROLE_RESPONDER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DataPathRole {
    }

    /**
     * Connection creation role is that of INITIATOR. Used to create a network specifier string
     * when requesting a NAN network.
     *
     * @see WifiNanDiscoveryBaseSession#createNetworkSpecifier(int, int, byte[])
     * @see WifiNanSession#createNetworkSpecifier(int, byte[], byte[])
     */
    public static final int WIFI_NAN_DATA_PATH_ROLE_INITIATOR = 0;

    /**
     * Connection creation role is that of RESPONDER. Used to create a network specifier string
     * when requesting a NAN network.
     *
     * @see WifiNanDiscoveryBaseSession#createNetworkSpecifier(int, int, byte[])
     * @see WifiNanSession#createNetworkSpecifier(int, byte[], byte[])
     */
    public static final int WIFI_NAN_DATA_PATH_ROLE_RESPONDER = 1;

    private final Context mContext;
    private final IWifiNanManager mService;

    private final Object mLock = new Object(); // lock access to the following vars

    @GuardedBy("mLock")
    private SparseArray<RttManager.RttListener> mRangingListeners = new SparseArray<>();

    /** @hide */
    public WifiNanManager(Context context, IWifiNanManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Enable the usage of the NAN API. Doesn't actually turn on NAN cluster formation - that
     * only happens when an attach is attempted. {@link #ACTION_WIFI_NAN_STATE_CHANGED} broadcast
     * will be triggered.
     *
     * @hide
     */
    public void enableUsage() {
        try {
            mService.enableUsage();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Disable the usage of the NAN API. All attempts to attach() will be rejected. All open
     * connections and sessions will be terminated. {@link #ACTION_WIFI_NAN_STATE_CHANGED} broadcast
     * will be triggered.
     *
     * @hide
     */
    public void disableUsage() {
        try {
            mService.disableUsage();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Returns the current status of NAN API: whether or not NAN is available. To track changes
     * in the state of NAN API register for the {@link #ACTION_WIFI_NAN_STATE_CHANGED} broadcast.
     *
     * @return A boolean indicating whether the app can use the NAN API at this time (true) or
     * not (false).
     */
    public boolean isAvailable() {
        try {
            return mService.isUsageEnabled();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }

        return false;
    }

    /**
     * Attach to the Wi-Fi NAN service - enabling the application to create discovery sessions or
     * create connections to peers. The device will attach to an existing cluster if it can find
     * one or create a new cluster (if it is the first to enable NAN in its vicinity). Results
     * (e.g. successful attach to a cluster) are provided to the {@code attachCallback} object.
     * An application <b>must</b> call {@link WifiNanSession#destroy()} when done with the
     * Wi-Fi NAN object.
     * <p>
     * Note: a NAN cluster is a shared resource - if the device is already attached to a cluster
     * then this function will simply indicate success immediately using the same {@code
     * attachCallback}.
     *
     * @param handler The Handler on whose thread to execute all callbacks related to the
     *            attach request - including all sessions opened as part of this
     *            attach. If a null is provided then the application's main thread will be used.
     * @param attachCallback A callback for attach events, extended from
     * {@link WifiNanAttachCallback}.
     */
    public void attach(@Nullable Handler handler, @NonNull WifiNanAttachCallback attachCallback) {
        attach(handler, null, attachCallback, null);
    }

    /**
     * Attach to the Wi-Fi NAN service - enabling the application to create discovery sessions or
     * create connections to peers. The device will attach to an existing cluster if it can find
     * one or create a new cluster (if it is the first to enable NAN in its vicinity). Results
     * (e.g. successful attach to a cluster) are provided to the {@code attachCallback} object.
     * An application <b>must</b> call {@link WifiNanSession#destroy()} when done with the
     * Wi-Fi NAN object.
     * <p>
     * Note: a NAN cluster is a shared resource - if the device is already attached to a cluster
     * then this function will simply indicate success immediately using the same {@code
     * attachCallback}.
     * <p>
     * This version of the API attaches a listener to receive the MAC address of the NAN interface
     * on startup and whenever it is updated (it is randomized at regular intervals for privacy).
     * The application must have the {@link android.Manifest.permission#ACCESS_COARSE_LOCATION}
     * permission to execute this attach request. Otherwise, use the
     * {@link #attach(Handler, WifiNanAttachCallback)} version. Note that aside from permission
     * requirements this listener will wake up the host at regular intervals causing higher power
     * consumption, do not use it unless the information is necessary (e.g. for OOB discovery).
     *
     * @param handler The Handler on whose thread to execute all callbacks related to the
     *            attach request - including all sessions opened as part of this
     *            attach. If a null is provided then the application's main thread will be used.
     * @param attachCallback A callback for attach events, extended from
     * {@link WifiNanAttachCallback}.
     * @param identityChangedListener A listener for changed identity.
     */
    public void attach(@Nullable Handler handler, @NonNull WifiNanAttachCallback attachCallback,
            @NonNull WifiNanIdentityChangedListener identityChangedListener) {
        attach(handler, null, attachCallback, identityChangedListener);
    }

    /** @hide */
    public void attach(Handler handler, ConfigRequest configRequest,
            WifiNanAttachCallback attachCallback,
            WifiNanIdentityChangedListener identityChangedListener) {
        if (VDBG) {
            Log.v(TAG, "attach(): handler=" + handler + ", callback=" + attachCallback
                    + ", configRequest=" + configRequest + ", identityChangedListener="
                    + identityChangedListener);
        }

        synchronized (mLock) {
            Looper looper = (handler == null) ? Looper.getMainLooper() : handler.getLooper();

            try {
                Binder binder = new Binder();
                mService.connect(binder, mContext.getOpPackageName(),
                        new WifiNanEventCallbackProxy(this, looper, binder, attachCallback,
                                identityChangedListener), configRequest,
                        identityChangedListener != null);
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }
    }

    /** @hide */
    public void disconnect(int clientId, Binder binder) {
        if (VDBG) Log.v(TAG, "disconnect()");

        try {
            mService.disconnect(clientId, binder);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /** @hide */
    public void publish(int clientId, Looper looper, PublishConfig publishConfig,
            WifiNanDiscoverySessionCallback callback) {
        if (VDBG) Log.v(TAG, "publish(): clientId=" + clientId + ", config=" + publishConfig);

        try {
            mService.publish(clientId, publishConfig,
                    new WifiNanDiscoverySessionCallbackProxy(this, looper, true, callback,
                            clientId));
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
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
            e.rethrowAsRuntimeException();
        }
    }

    /** @hide */
    public void subscribe(int clientId, Looper looper, SubscribeConfig subscribeConfig,
            WifiNanDiscoverySessionCallback callback) {
        if (VDBG) {
            if (VDBG) {
                Log.v(TAG,
                        "subscribe(): clientId=" + clientId + ", config=" + subscribeConfig);
            }
        }

        try {
            mService.subscribe(clientId, subscribeConfig,
                    new WifiNanDiscoverySessionCallbackProxy(this, looper, false, callback,
                            clientId));
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
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
            e.rethrowAsRuntimeException();
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
            e.rethrowAsRuntimeException();
        }
    }

    /** @hide */
    public void sendMessage(int clientId, int sessionId, int peerId, byte[] message, int messageId,
            int retryCount) {
        if (VDBG) {
            Log.v(TAG,
                    "sendMessage(): clientId=" + clientId + ", sessionId=" + sessionId + ", peerId="
                            + peerId + ", messageId=" + messageId + ", retryCount=" + retryCount);
        }

        try {
            mService.sendMessage(clientId, sessionId, peerId, message, messageId, retryCount);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /** @hide */
    public void startRanging(int clientId, int sessionId, RttManager.RttParams[] params,
                             RttManager.RttListener listener) {
        if (VDBG) {
            Log.v(TAG, "startRanging: clientId=" + clientId + ", sessionId=" + sessionId + ", "
                    + "params=" + Arrays.toString(params) + ", listener=" + listener);
        }

        int rangingKey = 0;
        try {
            rangingKey = mService.startRanging(clientId, sessionId,
                    new RttManager.ParcelableRttParams(params));
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }

        synchronized (mLock) {
            mRangingListeners.put(rangingKey, listener);
        }
    }

    /** @hide */
    public String createNetworkSpecifier(int clientId, int role, int sessionId, int peerId,
            byte[] token) {
        if (VDBG) {
            Log.v(TAG, "createNetworkSpecifier: role=" + role + ", sessionId=" + sessionId
                    + ", peerId=" + peerId + ", token=" + token);
        }

        int type;
        if (token != null && peerId != 0) {
            type = NETWORK_SPECIFIER_TYPE_1A;
        } else if (token == null && peerId != 0) {
            type = NETWORK_SPECIFIER_TYPE_1B;
        } else if (token != null && peerId == 0) {
            type = NETWORK_SPECIFIER_TYPE_1C;
        } else {
            type = NETWORK_SPECIFIER_TYPE_1D;
        }

        if (role != WIFI_NAN_DATA_PATH_ROLE_INITIATOR
                && role != WIFI_NAN_DATA_PATH_ROLE_RESPONDER) {
            throw new IllegalArgumentException(
                    "createNetworkSpecifier: Invalid 'role' argument when creating a network "
                            + "specifier");
        }
        if (role == WIFI_NAN_DATA_PATH_ROLE_INITIATOR) {
            if (token == null) {
                throw new IllegalArgumentException(
                        "createNetworkSpecifier: Invalid null token - not permitted on INITIATOR");
            }
            if (peerId == 0) {
                throw new IllegalArgumentException(
                        "createNetworkSpecifier: Invalid peer ID (value of 0) - not permitted on "
                                + "INITIATOR");
            }
        }

        JSONObject json;
        try {
            json = new JSONObject();
            json.put(NETWORK_SPECIFIER_KEY_TYPE, type);
            json.put(NETWORK_SPECIFIER_KEY_ROLE, role);
            json.put(NETWORK_SPECIFIER_KEY_CLIENT_ID, clientId);
            json.put(NETWORK_SPECIFIER_KEY_SESSION_ID, sessionId);
            if (peerId != 0) {
                json.put(NETWORK_SPECIFIER_KEY_PEER_ID, peerId);
            }
            if (token != null) {
                json.put(NETWORK_SPECIFIER_KEY_TOKEN,
                        Base64.encodeToString(token, 0, token.length, Base64.DEFAULT));
            }
        } catch (JSONException e) {
            return "";
        }

        return json.toString();
    }

    /** @hide */
    public String createNetworkSpecifier(int clientId, @DataPathRole int role, @Nullable byte[] peer,
            @Nullable byte[] token) {
        if (VDBG) {
            Log.v(TAG, "createNetworkSpecifier: role=" + role + ", token=" + token);
        }

        int type;
        if (token != null && peer != null) {
            type = NETWORK_SPECIFIER_TYPE_2A;
        } else if (token == null && peer != null) {
            type = NETWORK_SPECIFIER_TYPE_2B;
        } else if (token != null && peer == null) {
            type = NETWORK_SPECIFIER_TYPE_2C;
        } else { // both are null
            type = NETWORK_SPECIFIER_TYPE_2D;
        }

        if (role != WIFI_NAN_DATA_PATH_ROLE_INITIATOR
                && role != WIFI_NAN_DATA_PATH_ROLE_RESPONDER) {
            throw new IllegalArgumentException(
                    "createNetworkSpecifier: Invalid 'role' argument when creating a network "
                            + "specifier");
        }
        if (role == WIFI_NAN_DATA_PATH_ROLE_INITIATOR) {
            if (peer == null || peer.length != 6) {
                throw new IllegalArgumentException(
                        "createNetworkSpecifier: Invalid peer MAC address");
            }
            if (token == null) {
                throw new IllegalArgumentException(
                        "createNetworkSpecifier: Invalid null token - not permitted on INITIATOR");
            }
        } else {
            if (peer != null && peer.length != 6) {
                throw new IllegalArgumentException(
                        "createNetworkSpecifier: Invalid peer MAC address");
            }
        }

        JSONObject json;
        try {
            json = new JSONObject();
            json.put(NETWORK_SPECIFIER_KEY_TYPE, type);
            json.put(NETWORK_SPECIFIER_KEY_ROLE, role);
            json.put(NETWORK_SPECIFIER_KEY_CLIENT_ID, clientId);
            if (peer != null) {
                json.put(NETWORK_SPECIFIER_KEY_PEER_MAC, new String(HexEncoding.encode(peer)));
            }
            if (token != null) {
                json.put(NETWORK_SPECIFIER_KEY_TOKEN,
                        Base64.encodeToString(token, 0, token.length, Base64.DEFAULT));
            }
        } catch (JSONException e) {
            return "";
        }

        return json.toString();
    }

    private static class WifiNanEventCallbackProxy extends IWifiNanEventCallback.Stub {
        private static final int CALLBACK_CONNECT_SUCCESS = 0;
        private static final int CALLBACK_CONNECT_FAIL = 1;
        private static final int CALLBACK_IDENTITY_CHANGED = 2;
        private static final int CALLBACK_RANGING_SUCCESS = 3;
        private static final int CALLBACK_RANGING_FAILURE = 4;
        private static final int CALLBACK_RANGING_ABORTED = 5;

        private final Handler mHandler;
        private final WeakReference<WifiNanManager> mNanManager;
        private final Binder mBinder;
        private final Looper mLooper;

        RttManager.RttListener getAndRemoveRangingListener(int rangingId) {
            WifiNanManager mgr = mNanManager.get();
            if (mgr == null) {
                Log.w(TAG, "getAndRemoveRangingListener: called post GC");
                return null;
            }

            synchronized (mgr.mLock) {
                RttManager.RttListener listener = mgr.mRangingListeners.get(rangingId);
                mgr.mRangingListeners.delete(rangingId);
                return listener;
            }
        }

        /**
         * Constructs a {@link WifiNanAttachCallback} using the specified looper.
         * All callbacks will delivered on the thread of the specified looper.
         *
         * @param looper The looper on which to execute the callbacks.
         */
        WifiNanEventCallbackProxy(WifiNanManager mgr, Looper looper, Binder binder,
                final WifiNanAttachCallback attachCallback,
                final WifiNanIdentityChangedListener identityChangedListener) {
            mNanManager = new WeakReference<>(mgr);
            mLooper = looper;
            mBinder = binder;

            if (VDBG) Log.v(TAG, "WifiNanEventCallbackProxy ctor: looper=" + looper);
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    if (DBG) {
                        Log.d(TAG, "WifiNanEventCallbackProxy: What=" + msg.what + ", msg=" + msg);
                    }

                    WifiNanManager mgr = mNanManager.get();
                    if (mgr == null) {
                        Log.w(TAG, "WifiNanEventCallbackProxy: handleMessage post GC");
                        return;
                    }

                    switch (msg.what) {
                        case CALLBACK_CONNECT_SUCCESS:
                            attachCallback.onAttached(
                                    new WifiNanSession(mgr, mBinder, mLooper, msg.arg1));
                            break;
                        case CALLBACK_CONNECT_FAIL:
                            mNanManager.clear();
                            attachCallback.onAttachFailed(msg.arg1);
                            break;
                        case CALLBACK_IDENTITY_CHANGED:
                            identityChangedListener.onIdentityChanged((byte[]) msg.obj);
                            break;
                        case CALLBACK_RANGING_SUCCESS: {
                            RttManager.RttListener listener = getAndRemoveRangingListener(msg.arg1);
                            if (listener == null) {
                                Log.e(TAG, "CALLBACK_RANGING_SUCCESS rangingId=" + msg.arg1
                                        + ": no listener registered (anymore)");
                            } else {
                                listener.onSuccess(
                                        ((RttManager.ParcelableRttResults) msg.obj).mResults);
                            }
                            break;
                        }
                        case CALLBACK_RANGING_FAILURE: {
                            RttManager.RttListener listener = getAndRemoveRangingListener(msg.arg1);
                            if (listener == null) {
                                Log.e(TAG, "CALLBACK_RANGING_SUCCESS rangingId=" + msg.arg1
                                        + ": no listener registered (anymore)");
                            } else {
                                listener.onFailure(msg.arg2, (String) msg.obj);
                            }
                            break;
                        }
                        case CALLBACK_RANGING_ABORTED: {
                            RttManager.RttListener listener = getAndRemoveRangingListener(msg.arg1);
                            if (listener == null) {
                                Log.e(TAG, "CALLBACK_RANGING_SUCCESS rangingId=" + msg.arg1
                                        + ": no listener registered (anymore)");
                            } else {
                                listener.onAborted();
                            }
                            break;
                        }
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
            if (VDBG) Log.v(TAG, "onConfigFailed: reason=" + reason);

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

        @Override
        public void onRangingSuccess(int rangingId, RttManager.ParcelableRttResults results) {
            if (VDBG) {
                Log.v(TAG, "onRangingSuccess: rangingId=" + rangingId + ", results=" + results);
            }

            Message msg = mHandler.obtainMessage(CALLBACK_RANGING_SUCCESS);
            msg.arg1 = rangingId;
            msg.obj = results;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onRangingFailure(int rangingId, int reason, String description) {
            if (VDBG) {
                Log.v(TAG, "onRangingSuccess: rangingId=" + rangingId + ", reason=" + reason
                        + ", description=" + description);
            }

            Message msg = mHandler.obtainMessage(CALLBACK_RANGING_FAILURE);
            msg.arg1 = rangingId;
            msg.arg2 = reason;
            msg.obj = description;
            mHandler.sendMessage(msg);

        }

        @Override
        public void onRangingAborted(int rangingId) {
            if (VDBG) Log.v(TAG, "onRangingAborted: rangingId=" + rangingId);

            Message msg = mHandler.obtainMessage(CALLBACK_RANGING_ABORTED);
            msg.arg1 = rangingId;
            mHandler.sendMessage(msg);

        }
    }

    private static class WifiNanDiscoverySessionCallbackProxy extends
            IWifiNanDiscoverySessionCallback.Stub {
        private static final int CALLBACK_SESSION_STARTED = 0;
        private static final int CALLBACK_SESSION_CONFIG_SUCCESS = 1;
        private static final int CALLBACK_SESSION_CONFIG_FAIL = 2;
        private static final int CALLBACK_SESSION_TERMINATED = 3;
        private static final int CALLBACK_MATCH = 4;
        private static final int CALLBACK_MESSAGE_SEND_SUCCESS = 5;
        private static final int CALLBACK_MESSAGE_SEND_FAIL = 6;
        private static final int CALLBACK_MESSAGE_RECEIVED = 7;

        private static final String MESSAGE_BUNDLE_KEY_MESSAGE = "message";
        private static final String MESSAGE_BUNDLE_KEY_MESSAGE2 = "message2";

        private final WeakReference<WifiNanManager> mNanManager;
        private final boolean mIsPublish;
        private final WifiNanDiscoverySessionCallback mOriginalCallback;
        private final int mClientId;

        private final Handler mHandler;
        private WifiNanDiscoveryBaseSession mSession;

        WifiNanDiscoverySessionCallbackProxy(WifiNanManager mgr, Looper looper, boolean isPublish,
                WifiNanDiscoverySessionCallback originalCallback, int clientId) {
            mNanManager = new WeakReference<>(mgr);
            mIsPublish = isPublish;
            mOriginalCallback = originalCallback;
            mClientId = clientId;

            if (VDBG) {
                Log.v(TAG, "WifiNanDiscoverySessionCallbackProxy ctor: isPublish=" + isPublish);
            }

            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    if (DBG) Log.d(TAG, "What=" + msg.what + ", msg=" + msg);

                    if (mNanManager.get() == null) {
                        Log.w(TAG, "WifiNanDiscoverySessionCallbackProxy: handleMessage post GC");
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
                            mOriginalCallback.onSessionConfigFailed(msg.arg1);
                            if (mSession == null) {
                                /*
                                 * creation failed (as opposed to update
                                 * failing)
                                 */
                                mNanManager.clear();
                            }
                            break;
                        case CALLBACK_SESSION_TERMINATED:
                            onProxySessionTerminated(msg.arg1);
                            break;
                        case CALLBACK_MATCH:
                            mOriginalCallback.onServiceDiscovered(
                                    msg.arg1,
                                    msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE),
                                    msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE2));
                            break;
                        case CALLBACK_MESSAGE_SEND_SUCCESS:
                            mOriginalCallback.onMessageSent(msg.arg1);
                            break;
                        case CALLBACK_MESSAGE_SEND_FAIL:
                            mOriginalCallback.onMessageSendFailed(msg.arg1, msg.arg2);
                            break;
                        case CALLBACK_MESSAGE_RECEIVED:
                            mOriginalCallback.onMessageReceived(msg.arg1, (byte[]) msg.obj);
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

        @Override
        public void onMatch(int peerId, byte[] serviceSpecificInfo, byte[] matchFilter) {
            if (VDBG) Log.v(TAG, "onMatch: peerId=" + peerId);

            Bundle data = new Bundle();
            data.putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE, serviceSpecificInfo);
            data.putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE2, matchFilter);

            Message msg = mHandler.obtainMessage(CALLBACK_MATCH);
            msg.arg1 = peerId;
            msg.setData(data);
            mHandler.sendMessage(msg);
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
                Log.v(TAG, "onMessageReceived: peerId='" + peerId);
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

            WifiNanManager mgr = mNanManager.get();
            if (mgr == null) {
                Log.w(TAG, "onProxySessionStarted: mgr GC'd");
                return;
            }

            if (mIsPublish) {
                WifiNanPublishDiscoverySession session = new WifiNanPublishDiscoverySession(mgr,
                        mClientId, sessionId);
                mSession = session;
                mOriginalCallback.onPublishStarted(session);
            } else {
                WifiNanSubscribeDiscoverySession
                        session = new WifiNanSubscribeDiscoverySession(mgr, mClientId, sessionId);
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
            mNanManager.clear();
            mOriginalCallback.onSessionTerminated(reason);
        }
    }
}
