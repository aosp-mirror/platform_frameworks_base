/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.connectivity.tethering;

import static android.net.ConnectivityManager.TYPE_MOBILE_DUN;
import static android.net.ConnectivityManager.TYPE_MOBILE_HIPRI;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkState;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.StateMachine;

import java.util.HashMap;


/**
 * A class to centralize all the network and link properties information
 * pertaining to the current and any potential upstream network.
 *
 * Calling #start() registers two callbacks: one to track the system default
 * network and a second to specifically observe TYPE_MOBILE_DUN networks.
 *
 * The methods and data members of this class are only to be accessed and
 * modified from the tethering master state machine thread. Any other
 * access semantics would necessitate the addition of locking.
 *
 * TODO: Move upstream selection logic here.
 *
 * @hide
 */
public class UpstreamNetworkMonitor {
    private static final String TAG = UpstreamNetworkMonitor.class.getSimpleName();
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    public static final int EVENT_ON_AVAILABLE      = 1;
    public static final int EVENT_ON_CAPABILITIES   = 2;
    public static final int EVENT_ON_LINKPROPERTIES = 3;
    public static final int EVENT_ON_LOST           = 4;

    private final Context mContext;
    private final StateMachine mTarget;
    private final int mWhat;
    private final HashMap<Network, NetworkState> mNetworkMap = new HashMap<>();
    private ConnectivityManager mCM;
    private NetworkCallback mDefaultNetworkCallback;
    private NetworkCallback mDunTetheringCallback;
    private NetworkCallback mMobileNetworkCallback;
    private boolean mDunRequired;

    public UpstreamNetworkMonitor(Context ctx, StateMachine tgt, int what) {
        mContext = ctx;
        mTarget = tgt;
        mWhat = what;
    }

    @VisibleForTesting
    public UpstreamNetworkMonitor(StateMachine tgt, int what, ConnectivityManager cm) {
        this(null, tgt, what);
        mCM = cm;
    }

    public void start() {
        stop();

        mDefaultNetworkCallback = new UpstreamNetworkCallback();
        cm().registerDefaultNetworkCallback(mDefaultNetworkCallback);

        final NetworkRequest dunTetheringRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_DUN)
                .build();
        mDunTetheringCallback = new UpstreamNetworkCallback();
        cm().registerNetworkCallback(dunTetheringRequest, mDunTetheringCallback);
    }

    public void stop() {
        releaseMobileNetworkRequest();

        releaseCallback(mDefaultNetworkCallback);
        mDefaultNetworkCallback = null;

        releaseCallback(mDunTetheringCallback);
        mDunTetheringCallback = null;

        mNetworkMap.clear();
    }

    public void mobileUpstreamRequiresDun(boolean dunRequired) {
        final boolean valueChanged = (mDunRequired != dunRequired);
        mDunRequired = dunRequired;
        if (valueChanged && mobileNetworkRequested()) {
            releaseMobileNetworkRequest();
            registerMobileNetworkRequest();
        }
    }

    public boolean mobileNetworkRequested() {
        return (mMobileNetworkCallback != null);
    }

    public void registerMobileNetworkRequest() {
        if (mMobileNetworkCallback != null) return;

        final NetworkRequest.Builder builder = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        if (mDunRequired) {
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                   .addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
        } else {
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        final NetworkRequest mobileUpstreamRequest = builder.build();

        // The existing default network and DUN callbacks will be notified.
        // Therefore, to avoid duplicate notifications, we only register a no-op.
        mMobileNetworkCallback = new NetworkCallback();

        // TODO: Change the timeout from 0 (no onUnavailable callback) to use some
        // moderate callback time (once timeout callbacks are implemented). This might
        // be useful for updating some UI. Additionally, we should definitely log a
        // message to aid in any subsequent debugging
        if (DBG) Log.d(TAG, "requesting mobile upstream network: " + mobileUpstreamRequest);

        // The following use of the legacy type system cannot be removed until
        // after upstream selection no longer finds networks by legacy type.
        // See also b/34364553.
        final int apnType = mDunRequired ? TYPE_MOBILE_DUN : TYPE_MOBILE_HIPRI;
        cm().requestNetwork(mobileUpstreamRequest, mMobileNetworkCallback, 0, apnType);
    }

    public void releaseMobileNetworkRequest() {
        if (mMobileNetworkCallback == null) return;

        cm().unregisterNetworkCallback(mMobileNetworkCallback);
        mMobileNetworkCallback = null;
    }

    public NetworkState lookup(Network network) {
        return (network != null) ? mNetworkMap.get(network) : null;
    }

    private void handleAvailable(Network network) {
        if (VDBG) {
            Log.d(TAG, "EVENT_ON_AVAILABLE for " + network);
        }
        if (!mNetworkMap.containsKey(network)) {
            mNetworkMap.put(network,
                    new NetworkState(null, null, null, network, null, null));
        }

        final ConnectivityManager cm = cm();

        if (mDefaultNetworkCallback != null) {
            cm.requestNetworkCapabilities(mDefaultNetworkCallback);
            cm.requestLinkProperties(mDefaultNetworkCallback);
        }

        // Requesting updates for mDunTetheringCallback is not
        // necessary. Because it's a listen, it will already have
        // heard all NetworkCapabilities and LinkProperties updates
        // since UpstreamNetworkMonitor was started. Because we
        // start UpstreamNetworkMonitor before chooseUpstreamType()
        // is ever invoked (it can register a DUN request) this is
        // mostly safe. However, if a DUN network is already up for
        // some reason (unlikely, because DUN is restricted and,
        // unless the DUN network is shared with another APN, only
        // the system can request it and this is the only part of
        // the system that requests it) we won't know its
        // LinkProperties or NetworkCapabilities.

        notifyTarget(EVENT_ON_AVAILABLE, network);
    }

    private void handleNetCap(Network network, NetworkCapabilities newNc) {
        if (!mNetworkMap.containsKey(network)) {
            // Ignore updates for networks for which we have not yet
            // received onAvailable() - which should never happen -
            // or for which we have already received onLost().
            return;
        }
        if (VDBG) {
            Log.d(TAG, String.format("EVENT_ON_CAPABILITIES for %s: %s",
                    network, newNc));
        }

        final NetworkState prev = mNetworkMap.get(network);
        mNetworkMap.put(network,
                new NetworkState(null, prev.linkProperties, newNc,
                                 network, null, null));
        notifyTarget(EVENT_ON_CAPABILITIES, network);
    }

    private void handleLinkProp(Network network, LinkProperties newLp) {
        if (!mNetworkMap.containsKey(network)) {
            // Ignore updates for networks for which we have not yet
            // received onAvailable() - which should never happen -
            // or for which we have already received onLost().
            return;
        }
        if (VDBG) {
            Log.d(TAG, String.format("EVENT_ON_LINKPROPERTIES for %s: %s",
                    network, newLp));
        }

        final NetworkState prev = mNetworkMap.get(network);
        mNetworkMap.put(network,
                new NetworkState(null, newLp, prev.networkCapabilities,
                                 network, null, null));
        notifyTarget(EVENT_ON_LINKPROPERTIES, network);
    }

    private void handleLost(Network network) {
        if (!mNetworkMap.containsKey(network)) {
            // Ignore updates for networks for which we have not yet
            // received onAvailable() - which should never happen -
            // or for which we have already received onLost().
            return;
        }
        if (VDBG) {
            Log.d(TAG, "EVENT_ON_LOST for " + network);
        }
        notifyTarget(EVENT_ON_LOST, mNetworkMap.remove(network));
    }

    // Fetch (and cache) a ConnectivityManager only if and when we need one.
    private ConnectivityManager cm() {
        if (mCM == null) {
            mCM = mContext.getSystemService(ConnectivityManager.class);
        }
        return mCM;
    }

    /**
     * A NetworkCallback class that relays information of interest to the
     * tethering master state machine thread for subsequent processing.
     */
    private class UpstreamNetworkCallback extends NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            mTarget.getHandler().post(() -> handleAvailable(network));
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities newNc) {
            mTarget.getHandler().post(() -> handleNetCap(network, newNc));
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties newLp) {
            mTarget.getHandler().post(() -> handleLinkProp(network, newLp));
        }

        @Override
        public void onLost(Network network) {
            mTarget.getHandler().post(() -> handleLost(network));
        }
    }

    private void releaseCallback(NetworkCallback cb) {
        if (cb != null) cm().unregisterNetworkCallback(cb);
    }

    private void notifyTarget(int which, Network network) {
        notifyTarget(which, mNetworkMap.get(network));
    }

    private void notifyTarget(int which, NetworkState netstate) {
        mTarget.sendMessage(mWhat, which, 0, netstate);
    }
}
