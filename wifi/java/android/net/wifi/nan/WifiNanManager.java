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
import android.annotation.SystemApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import android.net.wifi.RttManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import dalvik.system.CloseGuard;

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
 * {@link #connect(Looper, WifiNanEventCallback)}.
 * <li>Create discovery sessions (publish or subscribe sessions).
 * Refer to {@link #publish(PublishConfig, WifiNanSessionCallback)} and
 * {@link #subscribe(SubscribeConfig, WifiNanSessionCallback)}.
 * <li>Create a NAN network specifier to be used with
 * {@link ConnectivityManager#requestNetwork(NetworkRequest, ConnectivityManager.NetworkCallback)}
 * to set-up a NAN connection with a peer. Refer to
 * {@link WifiNanSession#createNetworkSpecifier(int, int, byte[])} and
 * {@link #createNetworkSpecifier(int, byte[], byte[])}.
 * </ul>
 * <p>
 *     NAN may not be usable when Wi-Fi is disabled (and other conditions). To validate that
 *     the functionality is available use the {@link #isUsageEnabled()} function. To track
 *     changes in NAN usability register for the {@link #WIFI_NAN_STATE_CHANGED_ACTION} broadcast.
 *     Note that this broadcast is not sticky - you should register for it and then check the
 *     above API to avoid a race condition.
 * <p>
 *     An application must use {@link #connect(Looper, WifiNanEventCallback)} to initialize a NAN
 *     cluster - before making any other NAN operation. NAN cluster membership is a device-wide
 *     operation - the API guarantees that the device is in a cluster or joins a NAN cluster (or
 *     starts one if none can be found). Information about connection success (or failure) are
 *     returned in callbacks of {@link WifiNanEventCallback}. Proceed with NAN discovery or
 *     connection setup only after receiving confirmation that NAN connection succeeded -
 *     {@link WifiNanEventCallback#onConnectSuccess()}.
 *     When an application is finished using NAN it <b>must</b> use the {@link #disconnect()} API
 *     to indicate to the NAN service that the device may disconnect from the NAN cluster. The
 *     device will actually disconnect from the NAN cluster once the last application disconnects.
 * <p>
 *     Once a NAN connection is confirmed use the
 *     {@link #publish(PublishConfig, WifiNanSessionCallback)} or
 *     {@link #subscribe(SubscribeConfig, WifiNanSessionCallback)} to create publish or subscribe
 *     NAN discovery sessions. Events are called on the provided callback object
 *     {@link WifiNanSessionCallback}. Specifically, the
 *     {@link WifiNanSessionCallback#onPublishStarted(WifiNanPublishSession)} and
 *     {@link WifiNanSessionCallback#onSubscribeStarted(WifiNanSubscribeSession)} return
 *     {@link WifiNanPublishSession} and {@link WifiNanSubscribeSession} objects respectively on
 *     which additional session operations can be performed, e.g. updating the session
 *     {@link WifiNanPublishSession#updatePublish(PublishConfig)} and
 *     {@link WifiNanSubscribeSession#updateSubscribe(SubscribeConfig)}. Sessions can also be
 *     used to send messages using the {@link WifiNanSession#sendMessage(int, byte[], int)} APIs.
 *     When an application is finished with a discovery session it <b>must</b> terminate it using
 *     the {@link WifiNanSession#terminate()} API.
 * <p>
 *    Creating connections between NAN devices is managed by the standard
 *    {@link ConnectivityManager#requestNetwork(NetworkRequest, ConnectivityManager.NetworkCallback)}.
 *    The {@link NetworkRequest} object should be constructed with:
 *    <ul>
 *        <li>{@link NetworkRequest.Builder#addTransportType(int)} of
 *        {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_NAN}.
 *        <li>{@link NetworkRequest.Builder#setNetworkSpecifier(String)} using
 *        {@link #createNetworkSpecifier(int, byte[], byte[])} or
 *        {@link WifiNanSession#createNetworkSpecifier(int, int, byte[])}.
 *    </ul>
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanManager {
    private static final String TAG = "WifiNanManager";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int INVALID_CLIENT_ID = 0;

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
     * use the {@link #isUsageEnabled()} API after registering the broadcast to check the current
     * state of Wi-Fi NAN.
     *
     * @see #EXTRA_WIFI_STATE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_NAN_STATE_CHANGED_ACTION = "android.net.wifi.nan.STATE_CHANGED";

    /**
     * The lookup key for an int value indicating whether Wi-Fi NAN is enabled or
     * disabled. Retrieve it with
     * {@link android.content.Intent#getIntExtra(String,int)}.
     *
     * @see #WIFI_NAN_STATE_DISABLED
     * @see #WIFI_NAN_STATE_ENABLED
     */
    public static final String EXTRA_WIFI_STATE = "wifi_nan_state";

    /**
     * Wi-Fi NAN is disabled.
     *
     * @see #WIFI_NAN_STATE_CHANGED_ACTION
     */
    public static final int WIFI_NAN_STATE_DISABLED = 1;

    /**
     * Wi-Fi NAN is enabled.
     *
     * @see #WIFI_NAN_STATE_CHANGED_ACTION
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
     * @see WifiNanSession#createNetworkSpecifier(int, int, byte[])
     * @see #createNetworkSpecifier(int, byte[], byte[])
     */
    public static final int WIFI_NAN_DATA_PATH_ROLE_INITIATOR = 0;

    /**
     * Connection creation role is that of RESPONDER. Used to create a network specifier string
     * when requesting a NAN network.
     *
     * @see WifiNanSession#createNetworkSpecifier(int, int, byte[])
     * @see #createNetworkSpecifier(int, byte[], byte[])
     */
    public static final int WIFI_NAN_DATA_PATH_ROLE_RESPONDER = 1;

    private final Context mContext;
    private final IWifiNanManager mService;
    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final Object mLock = new Object(); // lock access to the following vars

    @GuardedBy("mLock")
    private final IBinder mBinder = new Binder();

    @GuardedBy("mLock")
    private int mClientId = INVALID_CLIENT_ID;

    @GuardedBy("mLock")
    private Looper mLooper;

    @GuardedBy("mLock")
    private SparseArray<RttManager.RttListener> mRangingListeners = new SparseArray<>();

    /** @hide */
    public WifiNanManager(Context context, IWifiNanManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Enable the usage of the NAN API. Doesn't actually turn on NAN cluster formation - that only
     * happens when a connection is made. {@link #WIFI_NAN_STATE_CHANGED_ACTION} broadcast will be
     * triggered.
     *
     * @hide
     */
    public void enableUsage() {
        try {
            mService.enableUsage();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Disable the usage of the NAN API. All attempts to connect() will be rejected. All open
     * connections and sessions will be terminated. {@link #WIFI_NAN_STATE_CHANGED_ACTION} broadcast
     * will be triggered.
     *
     * @hide
     */
    public void disableUsage() {
        try {
            mService.disableUsage();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current status of NAN API: whether or not usage is enabled. To track changes
     * in the state of NAN API register for the {@link #WIFI_NAN_STATE_CHANGED_ACTION} broadcast.
     *
     * @return A boolean indicating whether the app can use the NAN API (true)
     *         or not (false).
     */
    public boolean isUsageEnabled() {
        try {
            return mService.isUsageEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Connect to the Wi-Fi NAN service - enabling the application to create discovery session or
     * create connection to peers. The device will connect to an existing cluster if it can find
     * one or create a new cluster (if it is the first to enable NAN in its vicinity). Results
     * (e.g. successful connection to a cluster) are provided to the {@code callback} object.
     * An application <b>must</b> call {@link #disconnect()} when done with the Wi-Fi NAN
     * connection.
     * <p>
     * Note: a NAN cluster is a shared resource - if the device is already connected to a cluster
     * than this function will simply indicate success immediately.
     *
     * @param looper The Looper on which to execute all callbacks related to the
     *            connection - including all sessions opened as part of this
     *            connection.
     * @param callback A callback extended from {@link WifiNanEventCallback}.
     */
    public void connect(@NonNull Looper looper, @NonNull WifiNanEventCallback callback) {
        connect(looper, callback, null);
    }

    /**
     * Connect to the Wi-Fi NAN service - enabling the application to create discovery session or
     * create connection to peers. The device will connect to an existing cluster if it can find
     * one or create a new cluster (if it is the first to enable NAN in its vicinity). Results
     * (e.g. successful connection to a cluster) are provided to the {@code callback} object.
     * An application <b>must</b> call {@link #disconnect()} when done with the Wi-Fi NAN
     * connection. Allows requesting a specific configuration using {@link ConfigRequest}. If not
     * necessary (default configuration should usually work) use the
     * {@link #connect(Looper, WifiNanEventCallback)} method instead.
     * <p>
     * Note: a NAN cluster is a shared resource - if the device is already connected to a cluster
     * than this function will simply indicate success immediately.
     *
     * @param looper The Looper on which to execute all callbacks related to the
     *            connection - including all sessions opened as part of this
     *            connection.
     * @param callback A callback extended from {@link WifiNanEventCallback}.
     * @param configRequest The requested NAN configuration.
     */
    public void connect(@NonNull Looper looper, @NonNull WifiNanEventCallback callback,
            @Nullable ConfigRequest configRequest) {
        if (VDBG) {
            Log.v(TAG, "connect(): looper=" + looper + ", callback=" + callback + ", configRequest="
                    + configRequest);
        }

        synchronized (mLock) {
            mLooper = looper;

            try {
                mClientId = mService.connect(mBinder, mContext.getOpPackageName(),
                        new WifiNanEventCallbackProxy(this, looper, callback), configRequest);
            } catch (RemoteException e) {
                mClientId = INVALID_CLIENT_ID;
                mLooper = null;
                throw e.rethrowFromSystemServer();
            }
        }

        mCloseGuard.open("disconnect");
    }

    /**
     * Disconnect from the Wi-Fi NAN service and, if no other applications are connected to NAN,
     * also disconnect from the NAN cluster. This method destroys all outstanding operations -
     * i.e. all publish and subscribes are terminated, and any outstanding data-links are
     * shut-down. However, it is good practice to terminate these discovery sessions and
     * connections explicitly before a disconnect.
     * <p>
     * An application may re-connect after a disconnect using
     * {@link WifiNanManager#connect(Looper, WifiNanEventCallback)} .
     */
    public void disconnect() {
        if (VDBG) Log.v(TAG, "disconnect()");

        IBinder binder;
        int clientId;
        synchronized (mLock) {
            if (mClientId == INVALID_CLIENT_ID) {
                Log.w(TAG, "disconnect(): called with invalid client ID - not connected first?");
                return;
            }

            binder = mBinder;
            clientId = mClientId;

            mLooper = null;
            mClientId = INVALID_CLIENT_ID;
        }

        mCloseGuard.close();
        try {
            mService.disconnect(clientId, binder);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    protected void finalize() throws Throwable {
        try {
            mCloseGuard.warnIfOpen();
            disconnect();
        } finally {
            super.finalize();
        }
    }

    /**
     * Issue a request to the NAN service to create a new NAN publish discovery session, using
     * the specified {@code publishConfig} configuration. The results of the publish operation
     * are routed to the callbacks of {@link WifiNanSessionCallback}:
     * <ul>
     *     <li>{@link WifiNanSessionCallback#onPublishStarted(WifiNanPublishSession)} is called
     *     when the publish session is created and provides a handle to the session. Further
     *     operations on the publish session can be executed on that object.
     *     <li>{@link WifiNanSessionCallback#onSessionConfigFail(int)} is called if the publish
     *     operation failed.
     * </ul>
     * <p>
     * Other results of the publish session operations will also be routed to callbacks
     * on the {@code callback} object. The resulting publish session can be modified using
     * {@link WifiNanPublishSession#updatePublish(PublishConfig)}.
     * <p>
     *      An application must use the {@link WifiNanSession#terminate()} to terminate the publish
     *      discovery session once it isn't needed. This will free resources as well terminate
     *      any on-air transmissions.
     *
     * @param publishConfig The {@link PublishConfig} specifying the
     *            configuration of the requested publish session.
     * @param callback A {@link WifiNanSessionCallback} derived object to be used for session
     *                 event callbacks.
     */
    public void publish(@NonNull PublishConfig publishConfig,
            @NonNull WifiNanSessionCallback callback) {
        if (VDBG) Log.v(TAG, "publish(): config=" + publishConfig);

        int clientId;
        Looper looper;
        synchronized (mLock) {
            if (mLooper == null || mClientId == INVALID_CLIENT_ID) {
                Log.e(TAG, "publish(): called with null looper or invalid client ID - "
                        + "not connected first?");
                return;
            }

            clientId = mClientId;
            looper = mLooper;
        }
        try {
            mService.publish(clientId, publishConfig,
                    new WifiNanSessionCallbackProxy(this, looper, true, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void updatePublish(int sessionId, PublishConfig publishConfig) {
        if (VDBG) Log.v(TAG, "updatePublish(): config=" + publishConfig);

        int clientId;
        synchronized (mLock) {
            if (mClientId == INVALID_CLIENT_ID) {
                Log.e(TAG, "updatePublish(): called with invalid client ID - not connected first?");
                return;
            }

            clientId = mClientId;
        }
        try {
            mService.updatePublish(clientId, sessionId, publishConfig);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Issue a request to the NAN service to create a new NAN subscribe discovery session, using
     * the specified {@code subscribeConfig} configuration. The results of the subscribe
     * operation are routed to the callbacks of {@link WifiNanSessionCallback}:
     * <ul>
     *     <li>{@link WifiNanSessionCallback#onSubscribeStarted(WifiNanSubscribeSession)} is called
     *     when the subscribe session is created and provides a handle to the session. Further
     *     operations on the subscribe session can be executed on that object.
     *     <li>{@link WifiNanSessionCallback#onSessionConfigFail(int)} is called if the subscribe
     *     operation failed.
     * </ul>
     * <p>
     * Other results of the subscribe session operations will also be routed to callbacks
     * on the {@code callback} object. The resulting subscribe session can be modified using
     * {@link WifiNanSubscribeSession#updateSubscribe(SubscribeConfig)}.
     * <p>
     *      An application must use the {@link WifiNanSession#terminate()} to terminate the
     *      subscribe discovery session once it isn't needed. This will free resources as well
     *      terminate any on-air transmissions.
     *
     * @param subscribeConfig The {@link SubscribeConfig} specifying the
     *            configuration of the requested subscribe session.
     * @param callback A {@link WifiNanSessionCallback} derived object to be used for session
     *                 event callbacks.
     */
    public void subscribe(@NonNull SubscribeConfig subscribeConfig,
            @NonNull WifiNanSessionCallback callback) {
        if (VDBG) {
            Log.v(TAG, "subscribe(): config=" + subscribeConfig);
        }

        int clientId;
        Looper looper;
        synchronized (mLock) {
            if (mLooper == null || mClientId == INVALID_CLIENT_ID) {
                Log.e(TAG, "subscribe(): called with null looper or invalid client ID - "
                        + "not connected first?");
                return;
            }

            clientId = mClientId;
            looper = mLooper;
        }

        try {
            mService.subscribe(clientId, subscribeConfig,
                    new WifiNanSessionCallbackProxy(this, looper, false, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void updateSubscribe(int sessionId, SubscribeConfig subscribeConfig) {
        if (VDBG) {
            Log.v(TAG, "subscribe(): config=" + subscribeConfig);
        }

        int clientId;
        synchronized (mLock) {
            if (mClientId == INVALID_CLIENT_ID) {
                Log.e(TAG,
                        "updateSubscribe(): called with invalid client ID - not connected first?");
                return;
            }

            clientId = mClientId;
        }

        try {
            mService.updateSubscribe(clientId, sessionId, subscribeConfig);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void terminateSession(int sessionId) {
        if (DBG) Log.d(TAG, "Terminate NAN session #" + sessionId);

        int clientId;
        synchronized (mLock) {
            if (mClientId == INVALID_CLIENT_ID) {
                Log.e(TAG,
                        "terminateSession(): called with invalid client ID - not connected first?");
                return;
            }

            clientId = mClientId;
        }

        try {
            mService.terminateSession(clientId, sessionId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void sendMessage(int sessionId, int peerId, byte[] message, int messageId,
            int retryCount) {
        if (VDBG) {
            Log.v(TAG, "sendMessage(): sessionId=" + sessionId + ", peerId=" + peerId
                    + ", messageId=" + messageId + ", retryCount=" + retryCount);
        }

        int clientId;
        synchronized (mLock) {
            if (mClientId == INVALID_CLIENT_ID) {
                Log.e(TAG, "sendMessage(): called with invalid client ID - not connected first?");
                return;
            }

            clientId = mClientId;
        }

        try {
            mService.sendMessage(clientId, sessionId, peerId, message, messageId, retryCount);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void startRanging(int sessionId, RttManager.RttParams[] params,
                             RttManager.RttListener listener) {
        if (VDBG) {
            Log.v(TAG, "startRanging: sessionId=" + sessionId + ", " + "params="
                    + Arrays.toString(params) + ", listener=" + listener);
        }

        int clientId;
        synchronized (mLock) {
            if (mClientId == INVALID_CLIENT_ID) {
                Log.e(TAG, "startRanging(): called with invalid client ID - not connected first?");
                return;
            }

            clientId = mClientId;
        }

        int rangingKey = 0;
        try {
            rangingKey = mService.startRanging(clientId, sessionId,
                    new RttManager.ParcelableRttParams(params));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        synchronized (mLock) {
            mRangingListeners.put(rangingKey, listener);
        }
    }

    /** @hide */
    public String createNetworkSpecifier(@DataPathRole int role, int sessionId, int peerId,
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

        int clientId;
        synchronized (mLock) {
            if (mClientId == INVALID_CLIENT_ID) {
                Log.e(TAG,
                        "createNetworkSpecifier: called with invalid client ID - not connected "
                                + "first?");
                return null;
            }

            clientId = mClientId;
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

    /**
     * Create a {@link NetworkRequest.Builder#setNetworkSpecifier(String)} for a
     * WiFi NAN connection to the specified peer. The
     * {@link NetworkRequest.Builder#addTransportType(int)} should be set to
     * {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_NAN}.
     * <p>
     *     This API is targeted for applications which can obtain the peer MAC address using OOB
     *     (out-of-band) discovery. NAN discovery does not provide the MAC address of the peer -
     *     when using NAN discovery use the alternative network specifier method -
     *     {@link WifiNanSession#createNetworkSpecifier(int, int, byte[])}.
     *
     * @param role  The role of this device:
     *              {@link WifiNanManager#WIFI_NAN_DATA_PATH_ROLE_INITIATOR} or
     *              {@link WifiNanManager#WIFI_NAN_DATA_PATH_ROLE_RESPONDER}
     * @param peer  The MAC address of the peer's NAN discovery interface. On a RESPONDER this
     *              value is used to gate the acceptance of a connection request from only that
     *              peer. A RESPONDER may specified a null - indicating that it will accept
     *              connection requests from any device.
     * @param token An arbitrary token (message) to be used to match connection initiation request
     *              to a responder setup. A RESPONDER is set up with a {@code token} which must
     *              be matched by the token provided by the INITIATOR. A null token is permitted
     *              on the RESPONDER and matches any peer token. An empty ({@code ""}) token is
     *              not the same as a null token and requires the peer token to be empty as well.
     *
     * @return A string to be used to construct
     * {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(String)} to pass to {@link
     * android.net.ConnectivityManager#requestNetwork(NetworkRequest, ConnectivityManager.NetworkCallback)}
     * [or other varieties of that API].
     */
    public String createNetworkSpecifier(@DataPathRole int role, @Nullable byte[] peer,
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

        int clientId;
        synchronized (mLock) {
            if (mClientId == INVALID_CLIENT_ID) {
                Log.e(TAG,
                        "createNetworkSpecifier: called with invalid client ID - not connected "
                                + "first?");
                return null;
            }

            clientId = mClientId;
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
         * Constructs a {@link WifiNanEventCallback} using the specified looper.
         * All callbacks will delivered on the thread of the specified looper.
         *
         * @param looper The looper on which to execute the callbacks.
         */
        WifiNanEventCallbackProxy(WifiNanManager mgr, Looper looper,
                final WifiNanEventCallback originalCallback) {
            mNanManager = new WeakReference<>(mgr);

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
                            originalCallback.onConnectSuccess();
                            break;
                        case CALLBACK_CONNECT_FAIL:
                            synchronized (mgr.mLock) {
                                mgr.mLooper = null;
                                mgr.mClientId = INVALID_CLIENT_ID;
                            }
                            mNanManager.clear();
                            originalCallback.onConnectFail(msg.arg1);
                            break;
                        case CALLBACK_IDENTITY_CHANGED:
                            originalCallback.onIdentityChanged((byte[]) msg.obj);
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
        public void onConnectSuccess() {
            if (VDBG) Log.v(TAG, "onConnectSuccess");

            Message msg = mHandler.obtainMessage(CALLBACK_CONNECT_SUCCESS);
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

    private static class WifiNanSessionCallbackProxy extends IWifiNanSessionCallback.Stub {
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
        private final WifiNanSessionCallback mOriginalCallback;

        private final Handler mHandler;
        private WifiNanSession mSession;

        WifiNanSessionCallbackProxy(WifiNanManager mgr, Looper looper, boolean isPublish,
                WifiNanSessionCallback originalCallback) {
            mNanManager = new WeakReference<>(mgr);
            mIsPublish = isPublish;
            mOriginalCallback = originalCallback;

            if (VDBG) Log.v(TAG, "WifiNanSessionCallbackProxy ctor: isPublish=" + isPublish);

            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    if (DBG) Log.d(TAG, "What=" + msg.what + ", msg=" + msg);

                    if (mNanManager.get() == null) {
                        Log.w(TAG, "WifiNanSessionCallbackProxy: handleMessage post GC");
                        return;
                    }

                    switch (msg.what) {
                        case CALLBACK_SESSION_STARTED:
                            onProxySessionStarted(msg.arg1);
                            break;
                        case CALLBACK_SESSION_CONFIG_SUCCESS:
                            mOriginalCallback.onSessionConfigSuccess();
                            break;
                        case CALLBACK_SESSION_CONFIG_FAIL:
                            mOriginalCallback.onSessionConfigFail(msg.arg1);
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
                            mOriginalCallback.onMatch(
                                    msg.arg1,
                                    msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE),
                                    msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE2));
                            break;
                        case CALLBACK_MESSAGE_SEND_SUCCESS:
                            mOriginalCallback.onMessageSendSuccess(msg.arg1);
                            break;
                        case CALLBACK_MESSAGE_SEND_FAIL:
                            mOriginalCallback.onMessageSendFail(msg.arg1, msg.arg2);
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
                WifiNanPublishSession session = new WifiNanPublishSession(mgr, sessionId);
                mSession = session;
                mOriginalCallback.onPublishStarted(session);
            } else {
                WifiNanSubscribeSession session = new WifiNanSubscribeSession(mgr, sessionId);
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
