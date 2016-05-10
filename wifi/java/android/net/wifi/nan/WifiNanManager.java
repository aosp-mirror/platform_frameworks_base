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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.net.wifi.RttManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * This class provides the primary API for managing Wi-Fi NAN operation:
 * including discovery and data-links. Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(String)
 * Context.getSystemService(Context.WIFI_NAN_SERVICE)}.
 * <p>
 * The class provides access to:
 * <ul>
 * <li>Configure a NAN connection and register for events.
 * <li>Create publish and subscribe sessions.
 * <li>Create NAN network specifier to be used to create a NAN network.
 * </ul>
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanManager {
    private static final String TAG = "WifiNanManager";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int INVALID_CLIENT_ID = 0;

    /**
     * Broadcast intent action to indicate whether Wi-Fi NAN is enabled or
     * disabled. An extra {@link #EXTRA_WIFI_STATE} provides the state
     * information as int.
     *
     * @see #EXTRA_WIFI_STATE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_NAN_STATE_CHANGED_ACTION = "android.net.wifi.nan.STATE_CHANGED";

    /**
     * The lookup key for an int that indicates whether Wi-Fi NAN is enabled or
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

    private final IWifiNanManager mService;

    private final Object mLock = new Object(); // lock access to the following vars

    @GuardedBy("mLock")
    private final IBinder mBinder = new Binder();

    @GuardedBy("mLock")
    private int mClientId = INVALID_CLIENT_ID;

    @GuardedBy("mLock")
    private Looper mLooper;

    @GuardedBy("mLock")
    private SparseArray<RttManager.RttListener> mRangingListeners = new SparseArray<>();

    /**
     * {@hide}
     */
    public WifiNanManager(IWifiNanManager service) {
        mService = service;
    }

    /**
     * Enable the usage of the NAN API. Doesn't actually turn on NAN cluster formation - that only
     * happens when a connection is made. {@link #WIFI_NAN_STATE_CHANGED_ACTION} broadcast will be
     * triggered.
     *
     * @hide PROPOSED_NAN_SYSTEM_API
     */
    public void enableUsage() {
        try {
            mService.enableUsage();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Disable the usage of the NAN API. All attempts to connect() will be rejected. All open
     * connections and sessions will be terminated. {@link #WIFI_NAN_STATE_CHANGED_ACTION} broadcast
     * will be triggered.
     *
     * @hide PROPOSED_NAN_SYSTEM_API
     */
    public void disableUsage() {
        try {
            mService.disableUsage();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Returns the current status of NAN API: whether or not usage is enabled.
     *
     * @return A boolean indicating whether the app can use the NAN API (true)
     *         or not (false).
     */
    public boolean isUsageEnabled() {
        try {
            return mService.isUsageEnabled();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }

        return false;
    }

    /**
     * Connect to the Wi-Fi NAN service - enabling the application to execute
     * {@link WifiNanManager} APIs.
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
     * Connect to the Wi-Fi NAN service - enabling the application to execute
     * {@link WifiNanManager} APIs. Allows requesting a specific configuration
     * using {@link ConfigRequest} structure. Limited to privileged access.
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
                mClientId = mService.connect(mBinder,
                        new WifiNanEventCallbackProxy(this, looper, callback), configRequest);
            } catch (RemoteException e) {
                mClientId = INVALID_CLIENT_ID;
                mLooper = null;
                e.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Disconnect from the Wi-Fi NAN service and destroy all outstanding
     * operations - i.e. all publish and subscribes are terminated, any
     * outstanding data-link is shut-down, and all requested NAN configurations
     * are cancelled.
     * <p>
     * An application may then re-connect using
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

        try {
            mService.disconnect(clientId, binder);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        disconnect();
        super.finalize();
    }

    /**
     * Request a NAN publish session. The actual publish session is provided by
     * the
     * {@link WifiNanSessionCallback#onPublishStarted(WifiNanPublishSession)}
     * callback. Other results of the publish session operation will result in
     * callbacks to the indicated callback: {@link WifiNanSessionCallback
     * NanSessionCallback.on*}.
     *
     * @param publishConfig The {@link PublishConfig} specifying the
     *            configuration of the publish session.
     * @param callback The {@link WifiNanSessionCallback} derived objects to be
     *            used for the event callbacks specified by {@code events}.
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
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * {@hide}
     */
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
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Request a NAN subscribe session. The actual subscribe session is provided
     * by the
     * {@link WifiNanSessionCallback#onSubscribeStarted(WifiNanSubscribeSession)}
     * callback. Other results of the subscribe session operation will result in
     * callbacks to the indicated callback: {@link WifiNanSessionCallback
     * NanSessionCallback.on*}
     *
     * @param subscribeConfig The {@link SubscribeConfig} specifying the
     *            configuration of the subscribe session.
     * @param callback The {@link WifiNanSessionCallback} derived objects to be
     *            used for the event callbacks specified by {@code events}.
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
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * {@hide}
     */
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
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * {@hide}
     */
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
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * {@hide}
     */
    public void sendMessage(int sessionId, int peerId, byte[] message, int messageLength,
            int messageId, int retryCount) {
        if (VDBG) {
            Log.v(TAG, "sendMessage(): sessionId=" + sessionId + ", peerId=" + peerId
                    + ", messageLength=" + messageLength + ", messageId=" + messageId
                    + ", retryCount=" + retryCount);
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
            mService.sendMessage(clientId, sessionId, peerId, message, messageLength, messageId,
                    retryCount);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * {@hide}
     */
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
            e.rethrowAsRuntimeException();
        }

        synchronized (mLock) {
            mRangingListeners.put(rangingKey, listener);
        }
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
         * I.e. all callbacks will delivered on the thread of the specified looper.
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
                            originalCallback.onIdentityChanged();
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
        public void onIdentityChanged() {
            if (VDBG) Log.v(TAG, "onIdentityChanged");

            Message msg = mHandler.obtainMessage(CALLBACK_IDENTITY_CHANGED);
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

        private static final String MESSAGE_BUNDLE_KEY_PEER_ID = "peer_id";
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
                                    msg.getData().getInt(MESSAGE_BUNDLE_KEY_PEER_ID),
                                    msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE),
                                    msg.arg1,
                                    msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE2),
                                    msg.arg2);
                            break;
                        case CALLBACK_MESSAGE_SEND_SUCCESS:
                            mOriginalCallback.onMessageSendSuccess(msg.arg1);
                            break;
                        case CALLBACK_MESSAGE_SEND_FAIL:
                            mOriginalCallback.onMessageSendFail(msg.arg1, msg.arg2);
                            break;
                        case CALLBACK_MESSAGE_RECEIVED:
                            mOriginalCallback.onMessageReceived(msg.arg2,
                                    msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE),
                                    msg.arg1);
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
        public void onMatch(int peerId, byte[] serviceSpecificInfo,
                int serviceSpecificInfoLength, byte[] matchFilter, int matchFilterLength) {
            if (VDBG) Log.v(TAG, "onMatch: peerId=" + peerId);

            Bundle data = new Bundle();
            data.putInt(MESSAGE_BUNDLE_KEY_PEER_ID, peerId);
            data.putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE, serviceSpecificInfo);
            data.putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE2, matchFilter);

            Message msg = mHandler.obtainMessage(CALLBACK_MATCH);
            msg.arg1 = serviceSpecificInfoLength;
            msg.arg2 = matchFilterLength;
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
        public void onMessageReceived(int peerId, byte[] message, int messageLength) {
            if (VDBG) {
                Log.v(TAG, "onMessageReceived: peerId='" + peerId + "', messageLength="
                        + messageLength);
            }

            Bundle data = new Bundle();
            data.putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE, message);

            Message msg = mHandler.obtainMessage(CALLBACK_MESSAGE_RECEIVED);
            msg.arg1 = messageLength;
            msg.arg2 = peerId;
            msg.setData(data);
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
