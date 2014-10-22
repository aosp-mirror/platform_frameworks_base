/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A data tracker responsible for bringing up and tearing down the system proxy server.
 *
 * {@hide}
 */
public class ProxyDataTracker extends BaseNetworkStateTracker {
    private static final String TAG = "ProxyDataTracker";
    private static final String NETWORK_TYPE = "PROXY";

    // TODO: investigate how to get these DNS addresses from the system.
    private static final String DNS1 = "8.8.8.8";
    private static final String DNS2 = "8.8.4.4";
    private static final String INTERFACE_NAME = "ifb0";
    private static final String REASON_ENABLED = "enabled";
    private static final String REASON_DISABLED = "disabled";
    private static final String REASON_PROXY_DOWN = "proxy_down";

    private static final int MSG_TEAR_DOWN_REQUEST = 1;
    private static final int MSG_SETUP_REQUEST = 2;

    private static final String PERMISSION_PROXY_STATUS_SENDER =
            "android.permission.ACCESS_NETWORK_CONDITIONS";
    private static final String ACTION_PROXY_STATUS_CHANGE =
            "com.android.net.PROXY_STATUS_CHANGE";
    private static final String KEY_IS_PROXY_AVAILABLE = "is_proxy_available";
    private static final String KEY_REPLY_TO_MESSENGER_BINDER = "reply_to_messenger_binder";
    private static final String KEY_REPLY_TO_MESSENGER_BINDER_BUNDLE =
            "reply_to_messenger_binder_bundle";

    private Handler mTarget;
    private Messenger mProxyStatusService;
    private AtomicBoolean mReconnectRequested = new AtomicBoolean(false);
    private AtomicBoolean mIsProxyAvailable = new AtomicBoolean(false);
    private final AtomicInteger mDefaultGatewayAddr = new AtomicInteger(0);

    private final BroadcastReceiver mProxyStatusServiceListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_PROXY_STATUS_CHANGE)) {
                mIsProxyAvailable.set(intent.getBooleanExtra(KEY_IS_PROXY_AVAILABLE, false));
                if (mIsProxyAvailable.get()) {
                    Bundle bundle = intent.getBundleExtra(KEY_REPLY_TO_MESSENGER_BINDER_BUNDLE);
                    if (bundle == null || bundle.getBinder(KEY_REPLY_TO_MESSENGER_BINDER) == null) {
                        Log.e(TAG, "no messenger binder in the intent to send future requests");
                        mIsProxyAvailable.set(false);
                        return;
                    }
                    mProxyStatusService =
                            new Messenger(bundle.getBinder(KEY_REPLY_TO_MESSENGER_BINDER));
                    // If there is a pending reconnect request, do it now.
                    if (mReconnectRequested.get()) {
                        reconnect();
                    }
                } else {
                    setDetailedState(NetworkInfo.DetailedState.DISCONNECTED,
                            REASON_PROXY_DOWN, null);
                }
            } else {
                Log.d(TAG, "Unrecognized broadcast intent");
            }
        }
    };

    /**
     * Create a new ProxyDataTracker
     */
    public ProxyDataTracker() {
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_PROXY, 0, NETWORK_TYPE, "");
        mLinkProperties = new LinkProperties();
        mNetworkCapabilities = new NetworkCapabilities();
        mNetworkInfo.setIsAvailable(true);
        try {
            mLinkProperties.addDnsServer(InetAddress.getByName(DNS1));
            mLinkProperties.addDnsServer(InetAddress.getByName(DNS2));
            mLinkProperties.setInterfaceName(INTERFACE_NAME);
        } catch (UnknownHostException e) {
            Log.e(TAG, "Could not add DNS address", e);
        }
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    @Override
    public void startMonitoring(Context context, Handler target) {
        mContext = context;
        mTarget = target;
        mContext.registerReceiver(mProxyStatusServiceListener,
                new IntentFilter(ACTION_PROXY_STATUS_CHANGE),
                PERMISSION_PROXY_STATUS_SENDER,
                null);
    }

    /**
     * Disable connectivity to the network.
     */
    public boolean teardown() {
        setTeardownRequested(true);
        mReconnectRequested.set(false);
        try {
            if (mIsProxyAvailable.get() && mProxyStatusService != null) {
                mProxyStatusService.send(Message.obtain(null, MSG_TEAR_DOWN_REQUEST));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to connect to proxy status service", e);
            return false;
        }
        setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, REASON_DISABLED, null);
        return true;
    }

    /**
     * Re-enable proxy data connectivity after a {@link #teardown()}.
     */
    public boolean reconnect() {
        mReconnectRequested.set(true);
        setTeardownRequested(false);
        if (!mIsProxyAvailable.get()) {
            Log.w(TAG, "Reconnect requested even though proxy service is not up. Bailing.");
            return false;
        }
        setDetailedState(NetworkInfo.DetailedState.CONNECTING, REASON_ENABLED, null);

        try {
            mProxyStatusService.send(Message.obtain(null, MSG_SETUP_REQUEST));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to connect to proxy status service", e);
            setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, REASON_PROXY_DOWN, null);
            return false;
        }
        // We'll assume proxy is set up successfully. If not, a status change broadcast will be
        // received afterwards to indicate any failure.
        setDetailedState(NetworkInfo.DetailedState.CONNECTED, REASON_ENABLED, null);
        return true;
    }

    /**
     * Fetch default gateway address for the network
     */
    public int getDefaultGatewayAddr() {
        return mDefaultGatewayAddr.get();
    }

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName() {
        return "net.tcp.buffersize.wifi";
    }

    /**
     * Record the detailed state of a network, and if it is a
     * change from the previous state, send a notification to
     * any listeners.
     * @param state the new @{code DetailedState}
     * @param reason a {@code String} indicating a reason for the state change,
     * if one was supplied. May be {@code null}.
     * @param extraInfo optional {@code String} providing extra information about the state change
     */
    private void setDetailedState(NetworkInfo.DetailedState state, String reason,
            String extraInfo) {
        mNetworkInfo.setDetailedState(state, reason, extraInfo);
        Message msg = mTarget.obtainMessage(EVENT_STATE_CHANGED, mNetworkInfo);
        msg.sendToTarget();
    }
}
