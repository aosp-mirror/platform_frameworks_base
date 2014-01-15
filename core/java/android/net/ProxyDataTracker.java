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

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A data tracker responsible for bringing up and tearing down the system proxy server.
 *
 * {@hide}
 */
public class ProxyDataTracker extends BaseNetworkStateTracker {
    private static final String NETWORK_TYPE = "PROXY";
    private static final String TAG = "ProxyDataTracker";

    // TODO: investigate how to get these DNS addresses from the system.
    private static final String DNS1 = "8.8.8.8";
    private static final String DNS2 = "8.8.4.4";
    private static final String REASON_ENABLED = "enabled";

    private final AtomicInteger mDefaultGatewayAddr = new AtomicInteger(0);
    private final AtomicInteger mReconnectGeneration = new AtomicInteger(0);

    /**
     * Create a new ProxyDataTracker
     */
    public ProxyDataTracker() {
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_PROXY, 0, NETWORK_TYPE, "");
        // TODO: update available state according to proxy state.
        mNetworkInfo.setIsAvailable(true);
        mLinkProperties = new LinkProperties();
        mLinkCapabilities = new LinkCapabilities();

        try {
          mLinkProperties.addDns(InetAddress.getByName(DNS1));
          mLinkProperties.addDns(InetAddress.getByName(DNS2));
        } catch (UnknownHostException e) {
          Log.e(TAG, "Could not add DNS address", e);
        }
    }

    public Object Clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    /**
     * Disable connectivity to the network.
     */
    public boolean teardown() {
        // TODO: tell relevant service to tear down proxy.
        return true;
    }

    /**
     * Re-enable proxy data connectivity after a {@link #teardown()}.
     */
    public boolean reconnect() {
        if (!isAvailable()) {
            Log.w(TAG, "Reconnect requested even though network is disabled. Bailing.");
            return false;
        }
        setTeardownRequested(false);
        mReconnectGeneration.incrementAndGet();
        // TODO: tell relevant service to setup proxy. Set state to connected only if setup
        // succeeds.
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
        Message msg = getTargetHandler().obtainMessage(EVENT_STATE_CHANGED, mNetworkInfo);
        msg.sendToTarget();
    }
}
