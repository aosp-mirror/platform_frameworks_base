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

import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

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

    private IBinder mBinder;
    private int mClientId = -1;
    private IWifiNanManager mService;
    private Looper mLooper;

    /**
     * {@hide}
     */
    public WifiNanManager(IWifiNanManager service) {
        mService = service;
    }

    /**
     * Re-connect to the Wi-Fi NAN service - enabling the application to execute
     * {@link WifiNanManager} APIs.
     *
     * @param looper The Looper on which to execute all callbacks related to the
     *            connection - including all sessions opened as part of this
     *            connection.
     * @param callback A callback extended from {@link WifiNanEventCallback}.
     */
    public void connect(Looper looper, WifiNanEventCallback callback) {
        if (VDBG) Log.v(TAG, "connect()");

        if (callback == null) {
            throw new IllegalArgumentException("Invalid callback - must not be null");
        }

        if (mClientId != -1) {
            Log.e(TAG, "connect(): mClientId=" + mClientId
                    + ": seems to calling connect() without disconnecting() first!");
            throw new IllegalStateException("Calling connect() without disconnecting() first!");
        }

        mLooper = looper;
        mBinder = new Binder();

        try {
            mClientId = mService.connect(mBinder, new WifiNanEventCallbackProxy(mLooper, callback));
        } catch (RemoteException e) {
            Log.w(TAG, "connect RemoteException (FYI - ignoring): " + e);
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
        if (mClientId == -1) {
            /*
             * Warning only since could be called multiple times as cleaning-up
             * (and no damage done).
             */
            Log.w(TAG, "disconnect(): called without calling connect() first - or called "
                    + "multiple times.");
            return;
        }
        try {
            if (VDBG) Log.v(TAG, "disconnect()");
            mService.disconnect(mClientId, mBinder);
            mBinder = null;
            mClientId = -1;
        } catch (RemoteException e) {
            Log.w(TAG, "disconnect RemoteException (FYI - ignoring): " + e);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (mBinder != null) {
            if (DBG) Log.d(TAG, "finalize: disconnect() not called - executing now");
            disconnect();
        }
    }

    /**
     * Requests a NAN configuration, specified by {@link ConfigRequest}. Note
     * that NAN is a shared resource and the device can only be a member of a
     * single cluster. Thus the service may merge configuration requests from
     * multiple applications and configure NAN differently from individual
     * requests.
     * <p>
     * The {@link WifiNanEventCallback#onConfigCompleted(ConfigRequest)} will be
     * called when configuration is completed (if a callback is registered for
     * this specific event).
     *
     * @param configRequest The requested NAN configuration.
     */
    public void requestConfig(ConfigRequest configRequest) {
        if (VDBG) Log.v(TAG, "requestConfig(): configRequest=" + configRequest);

        if (mClientId == -1) {
            Log.e(TAG, "requestConfig(): called without an initial connect()!");
            throw new IllegalStateException("Calling requestConfig() without a connect() first!");
        }

        try {
            mService.requestConfig(mClientId, configRequest);
        } catch (RemoteException e) {
            Log.w(TAG, "requestConfig RemoteException (FYI - ignoring): " + e);
        }
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
    public void publish(PublishConfig publishConfig, WifiNanSessionCallback callback) {
        if (VDBG) Log.v(TAG, "publish(): config=" + publishConfig);

        if (publishConfig.mPublishType == PublishConfig.PUBLISH_TYPE_UNSOLICITED
                && publishConfig.mRxFilterLength != 0) {
            throw new IllegalArgumentException("Invalid publish config: UNSOLICITED "
                    + "publishes (active) can't have an Rx filter");
        }
        if (publishConfig.mPublishType == PublishConfig.PUBLISH_TYPE_SOLICITED
                && publishConfig.mTxFilterLength != 0) {
            throw new IllegalArgumentException("Invalid publish config: SOLICITED "
                    + "publishes (passive) can't have a Tx filter");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Invalid callback - must not be null");
        }

        if (mClientId == -1) {
            Log.e(TAG, "publish(): called without an initial connect()!");
            throw new IllegalStateException("Calling publish() without a connect() first!");
        }

        try {
            mService.publish(mClientId, publishConfig,
                    new WifiNanSessionCallbackProxy(mLooper, true, callback));
        } catch (RemoteException e) {
            Log.w(TAG, "publish RemoteException: " + e);
        }
    }

    /**
     * {@hide}
     */
    public void updatePublish(int sessionId, PublishConfig publishConfig) {
        if (VDBG) Log.v(TAG, "updatePublish(): config=" + publishConfig);

        if (publishConfig.mPublishType == PublishConfig.PUBLISH_TYPE_UNSOLICITED
                && publishConfig.mRxFilterLength != 0) {
            throw new IllegalArgumentException("Invalid publish config: UNSOLICITED "
                    + "publishes (active) can't have an Rx filter");
        }
        if (publishConfig.mPublishType == PublishConfig.PUBLISH_TYPE_SOLICITED
                && publishConfig.mTxFilterLength != 0) {
            throw new IllegalArgumentException("Invalid publish config: SOLICITED "
                    + "publishes (passive) can't have a Tx filter");
        }

        try {
            mService.updatePublish(mClientId, sessionId, publishConfig);
        } catch (RemoteException e) {
            Log.w(TAG, "updatePublish RemoteException: " + e);
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
    public void subscribe(SubscribeConfig subscribeConfig, WifiNanSessionCallback callback) {
        if (VDBG) {
            Log.v(TAG, "subscribe(): config=" + subscribeConfig);
        }

        if (subscribeConfig.mSubscribeType == SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE
                && subscribeConfig.mRxFilterLength != 0) {
            throw new IllegalArgumentException(
                    "Invalid subscribe config: ACTIVE subscribes can't have an Rx filter");
        }
        if (subscribeConfig.mSubscribeType == SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE
                && subscribeConfig.mTxFilterLength != 0) {
            throw new IllegalArgumentException(
                    "Invalid subscribe config: PASSIVE subscribes can't have a Tx filter");
        }

        if (mClientId == -1) {
            Log.e(TAG, "subscribe(): called without an initial connect()!");
            throw new IllegalStateException("Calling subscribe() without a connect() first!");
        }

        try {
            mService.subscribe(mClientId, subscribeConfig,
                    new WifiNanSessionCallbackProxy(mLooper, false, callback));
        } catch (RemoteException e) {
            Log.w(TAG, "subscribe RemoteException: " + e);
        }
    }

    /**
     * {@hide}
     */
    public void updateSubscribe(int sessionId, SubscribeConfig subscribeConfig) {
        if (VDBG) {
            Log.v(TAG, "subscribe(): config=" + subscribeConfig);
        }

        if (subscribeConfig.mSubscribeType == SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE
                && subscribeConfig.mRxFilterLength != 0) {
            throw new IllegalArgumentException(
                    "Invalid subscribe config: ACTIVE subscribes can't have an Rx filter");
        }
        if (subscribeConfig.mSubscribeType == SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE
                && subscribeConfig.mTxFilterLength != 0) {
            throw new IllegalArgumentException(
                    "Invalid subscribe config: PASSIVE subscribes can't have a Tx filter");
        }

        try {
            mService.updateSubscribe(mClientId, sessionId, subscribeConfig);
        } catch (RemoteException e) {
            Log.w(TAG, "updateSubscribe RemoteException: " + e);
        }
    }

    /**
     * {@hide}
     */
    public void terminateSession(int sessionId) {
        if (DBG) Log.d(TAG, "Terminate NAN session #" + sessionId);

        try {
            mService.terminateSession(mClientId, sessionId);
        } catch (RemoteException e) {
            Log.w(TAG, "terminateSession RemoteException (FYI - ignoring): " + e);
        }
    }

    /**
     * {@hide}
     */
    public void sendMessage(int sessionId, int peerId, byte[] message, int messageLength,
            int messageId) {
        try {
            if (VDBG) {
                Log.v(TAG, "sendMessage(): sessionId=" + sessionId + ", peerId=" + peerId
                        + ", messageLength=" + messageLength + ", messageId=" + messageId);
            }
            mService.sendMessage(mClientId, sessionId, peerId, message, messageLength, messageId);
        } catch (RemoteException e) {
            Log.w(TAG, "subscribe RemoteException (FYI - ignoring): " + e);
        }
    }

    private static class WifiNanEventCallbackProxy extends IWifiNanEventCallback.Stub {
        private static final int CALLBACK_CONFIG_COMPLETED = 0;
        private static final int CALLBACK_CONFIG_FAILED = 1;
        private static final int CALLBACK_NAN_DOWN = 2;
        private static final int CALLBACK_IDENTITY_CHANGED = 3;

        private final WifiNanEventCallback mOriginalCallback;
        private final Handler mHandler;

        /**
         * Constructs a {@link WifiNanEventCallback} using the specified looper.
         * I.e. all callbacks will delivered on the thread of the specified looper.
         *
         * @param looper The looper on which to execute the callbacks.
         */
        WifiNanEventCallbackProxy(Looper looper, WifiNanEventCallback originalCallback) {
            mOriginalCallback = originalCallback;

            if (VDBG) Log.v(TAG, "WifiNanEventCallbackProxy ctor: looper=" + looper);
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    if (DBG) Log.d(TAG, "What=" + msg.what + ", msg=" + msg);
                    switch (msg.what) {
                        case CALLBACK_CONFIG_COMPLETED:
                            mOriginalCallback.onConfigCompleted((ConfigRequest) msg.obj);
                            break;
                        case CALLBACK_CONFIG_FAILED:
                            mOriginalCallback.onConfigFailed((ConfigRequest) msg.obj, msg.arg1);
                            break;
                        case CALLBACK_NAN_DOWN:
                            mOriginalCallback.onNanDown(msg.arg1);
                            break;
                        case CALLBACK_IDENTITY_CHANGED:
                            mOriginalCallback.onIdentityChanged();
                            break;
                    }
                }
            };
        }

        @Override
        public void onConfigCompleted(ConfigRequest completedConfig) {
            if (VDBG) Log.v(TAG, "onConfigCompleted: configRequest=" + completedConfig);

            Message msg = mHandler.obtainMessage(CALLBACK_CONFIG_COMPLETED);
            msg.obj = completedConfig;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onConfigFailed(ConfigRequest failedConfig, int reason) {
            if (VDBG) {
                Log.v(TAG,
                        "onConfigFailed: failedConfig=" + failedConfig + ", reason=" + reason);
            }

            Message msg = mHandler.obtainMessage(CALLBACK_CONFIG_FAILED);
            msg.arg1 = reason;
            msg.obj = failedConfig;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onNanDown(int reason) {
            if (VDBG) Log.v(TAG, "onNanDown: reason=" + reason);

            Message msg = mHandler.obtainMessage(CALLBACK_NAN_DOWN);
            msg.arg1 = reason;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onIdentityChanged() {
            if (VDBG) Log.v(TAG, "onIdentityChanged");

            Message msg = mHandler.obtainMessage(CALLBACK_IDENTITY_CHANGED);
            mHandler.sendMessage(msg);
        }
    }

    private class WifiNanSessionCallbackProxy extends IWifiNanSessionCallback.Stub {
        private static final int CALLBACK_SESSION_STARTED = 0;
        private static final int CALLBACK_SESSION_CONFIG_FAIL = 1;
        private static final int CALLBACK_SESSION_TERMINATED = 2;
        private static final int CALLBACK_MATCH = 3;
        private static final int CALLBACK_MESSAGE_SEND_SUCCESS = 4;
        private static final int CALLBACK_MESSAGE_SEND_FAIL = 5;
        private static final int CALLBACK_MESSAGE_RECEIVED = 6;

        private static final String MESSAGE_BUNDLE_KEY_PEER_ID = "peer_id";
        private static final String MESSAGE_BUNDLE_KEY_MESSAGE = "message";
        private static final String MESSAGE_BUNDLE_KEY_MESSAGE2 = "message2";

        private final boolean mIsPublish;
        private final WifiNanSessionCallback mOriginalCallback;

        private final Handler mHandler;
        private WifiNanSession mSession;

        WifiNanSessionCallbackProxy(Looper looper, boolean isPublish,
                WifiNanSessionCallback originalCallback) {
            mIsPublish = isPublish;
            mOriginalCallback = originalCallback;

            if (VDBG) Log.v(TAG, "WifiNanSessionCallbackProxy ctor: isPublish=" + isPublish);

            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    if (DBG) Log.d(TAG, "What=" + msg.what + ", msg=" + msg);
                    switch (msg.what) {
                        case CALLBACK_SESSION_STARTED:
                            onProxySessionStarted(msg.arg1);
                            break;
                        case CALLBACK_SESSION_CONFIG_FAIL:
                            onProxySessionConfigFail(msg.arg1);
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
            if (mIsPublish) {
                WifiNanPublishSession session = new WifiNanPublishSession(WifiNanManager.this,
                        sessionId, mOriginalCallback);
                mSession = session;
                mOriginalCallback.onPublishStarted(session);
            } else {
                WifiNanSubscribeSession session = new WifiNanSubscribeSession(WifiNanManager.this,
                        sessionId, mOriginalCallback);
                mSession = session;
                mOriginalCallback.onSubscribeStarted(session);
            }
        }

        public void onProxySessionConfigFail(int reason) {
            if (VDBG) Log.v(TAG, "Proxy: onSessionConfigFail: reason=" + reason);
            mOriginalCallback.onSessionConfigFail(reason);
        }

        public void onProxySessionTerminated(int reason) {
            if (VDBG) Log.v(TAG, "Proxy: onSessionTerminated: reason=" + reason);
            mOriginalCallback.onSessionTerminated(reason);
            if (mSession != null) {
                mSession.terminate();
            } else {
                Log.w(TAG, "Proxy: onSessionTerminated called but mSession is null!?");
            }
        }
    }
}
