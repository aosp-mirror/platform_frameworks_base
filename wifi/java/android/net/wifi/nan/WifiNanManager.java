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
import android.os.IBinder;
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
     * @param callback A callback extended from {@link WifiNanEventCallback}.
     */
    public void connect(WifiNanEventCallback callback) {
        try {
            if (VDBG) Log.v(TAG, "connect()");
            if (callback == null) {
                throw new IllegalArgumentException("Invalid callback - must not be null");
            }
            if (mClientId != -1) {
                Log.w(TAG, "connect(): mClientId=" + mClientId
                        + ": seems to calling connect() without disconnecting() first!");
            }
            if (mBinder == null) {
                mBinder = new Binder();
            }
            mClientId = mService.connect(mBinder, callback.callback);
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
     * {@link WifiNanManager#connect(WifiNanEventCallback, int)} .
     */
    public void disconnect() {
        try {
            if (VDBG) Log.v(TAG, "disconnect()");
            mService.disconnect(mClientId, mBinder);
            mBinder = null;
            mClientId = -1;
        } catch (RemoteException e) {
            Log.w(TAG, "disconnect RemoteException (FYI - ignoring): " + e);
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
        try {
            mService.requestConfig(mClientId, configRequest);
        } catch (RemoteException e) {
            Log.w(TAG, "requestConfig RemoteException (FYI - ignoring): " + e);
        }
    }

    /**
     * Request a NAN publish session. The results of the publish session
     * operation will result in callbacks to the indicated callback:
     * {@link WifiNanSessionCallback NanSessionCallback.on*}.
     *
     * @param publishConfig The {@link PublishConfig} specifying the
     *            configuration of the publish session.
     * @param callback The {@link WifiNanSessionCallback} derived objects to be
     *            used for the event callbacks specified by {@code events}.
     * @return The {@link WifiNanPublishSession} which can be used to further
     *         control the publish session.
     */
    public WifiNanPublishSession publish(PublishConfig publishConfig,
            WifiNanSessionCallback callback) {
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

        int sessionId;

        try {
            sessionId = mService.createSession(mClientId, callback.callback);
            if (DBG) Log.d(TAG, "publish: session created - sessionId=" + sessionId);
            mService.publish(mClientId, sessionId, publishConfig);
        } catch (RemoteException e) {
            Log.w(TAG, "createSession/publish RemoteException: " + e);
            return null;
        }

        return new WifiNanPublishSession(this, sessionId);
    }

    /**
     * {@hide}
     */
    public void publish(int sessionId, PublishConfig publishConfig) {
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

        try {
            mService.publish(mClientId, sessionId, publishConfig);
        } catch (RemoteException e) {
            Log.w(TAG, "publish RemoteException: " + e);
        }
    }

    /**
     * Request a NAN subscribe session. The results of the subscribe session
     * operation will result in callbacks to the indicated callback:
     * {@link WifiNanSessionCallback WifiNanSessionCallback.on*}.
     *
     * @param subscribeConfig The {@link SubscribeConfig} specifying the
     *            configuration of the subscribe session.
     * @param callback The {@link WifiNanSessionCallback} derived objects to be
     *            used for the event callbacks specified by {@code events}.
     * @return The {@link WifiNanSubscribeSession} which can be used to further
     *         control the subscribe session.
     */
    public WifiNanSubscribeSession subscribe(SubscribeConfig subscribeConfig,
            WifiNanSessionCallback callback) {
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

        int sessionId;

        try {
            sessionId = mService.createSession(mClientId, callback.callback);
            if (DBG) Log.d(TAG, "subscribe: session created - sessionId=" + sessionId);
            mService.subscribe(mClientId, sessionId, subscribeConfig);
        } catch (RemoteException e) {
            Log.w(TAG, "createSession/subscribe RemoteException: " + e);
            return null;
        }

        return new WifiNanSubscribeSession(this, sessionId);
    }

    /**
     * {@hide}
     */
    public void subscribe(int sessionId, SubscribeConfig subscribeConfig) {
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
            mService.subscribe(mClientId, sessionId, subscribeConfig);
        } catch (RemoteException e) {
            Log.w(TAG, "subscribe RemoteException: " + e);
        }
    }

    /**
     * {@hide}
     */
    public void stopSession(int sessionId) {
        if (DBG) Log.d(TAG, "Stop NAN session #" + sessionId);

        try {
            mService.stopSession(mClientId, sessionId);
        } catch (RemoteException e) {
            Log.w(TAG, "stopSession RemoteException (FYI - ignoring): " + e);
        }
    }

    /**
     * {@hide}
     */
    public void destroySession(int sessionId) {
        if (DBG) Log.d(TAG, "Destroy NAN session #" + sessionId);

        try {
            mService.destroySession(mClientId, sessionId);
        } catch (RemoteException e) {
            Log.w(TAG, "destroySession RemoteException (FYI - ignoring): " + e);
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
}
