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
import android.os.Handler;
import android.os.Looper;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkState;
import android.net.util.SharedLog;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.StateMachine;

import java.util.HashMap;


/**
 * A class to centralize all the network and link properties information
 * pertaining to the current and any potential upstream network.
 *
 * Calling #start() registers two callbacks: one to track the system default
 * network and a second to observe all networks.  The latter is necessary
 * while the expression of preferred upstreams remains a list of legacy
 * connectivity types.  In future, this can be revisited.
 *
 * The methods and data members of this class are only to be accessed and
 * modified from the tethering master state machine thread. Any other
 * access semantics would necessitate the addition of locking.
 *
 * TODO: Move upstream selection logic here.
 *
 * All callback methods are run on the same thread as the specified target
 * state machine.  This class does not require locking when accessed from this
 * thread.  Access from other threads is not advised.
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

    private static final int CALLBACK_LISTEN_ALL = 1;
    private static final int CALLBACK_TRACK_DEFAULT = 2;
    private static final int CALLBACK_MOBILE_REQUEST = 3;

    private final Context mContext;
    private final SharedLog mLog;
    private final StateMachine mTarget;
    private final Handler mHandler;
    private final int mWhat;
    private final HashMap<Network, NetworkState> mNetworkMap = new HashMap<>();
    private ConnectivityManager mCM;
    private NetworkCallback mListenAllCallback;
    private NetworkCallback mDefaultNetworkCallback;
    private NetworkCallback mMobileNetworkCallback;
    private boolean mDunRequired;
    private Network mCurrentDefault;

    public UpstreamNetworkMonitor(Context ctx, StateMachine tgt, int what, SharedLog log) {
        mContext = ctx;
        mTarget = tgt;
        mHandler = mTarget.getHandler();
        mWhat = what;
        mLog = log.forSubComponent(TAG);
    }

    @VisibleForTesting
    public UpstreamNetworkMonitor(
            StateMachine tgt, int what, ConnectivityManager cm, SharedLog log) {
        this(null, tgt, what, log);
        mCM = cm;
    }

    public void start() {
        stop();

        final NetworkRequest listenAllRequest = new NetworkRequest.Builder()
                .clearCapabilities().build();
        mListenAllCallback = new UpstreamNetworkCallback(CALLBACK_LISTEN_ALL);
        cm().registerNetworkCallback(listenAllRequest, mListenAllCallback, mHandler);

        mDefaultNetworkCallback = new UpstreamNetworkCallback(CALLBACK_TRACK_DEFAULT);
        cm().registerDefaultNetworkCallback(mDefaultNetworkCallback, mHandler);
    }

    public void stop() {
        releaseMobileNetworkRequest();

        releaseCallback(mDefaultNetworkCallback);
        mDefaultNetworkCallback = null;

        releaseCallback(mListenAllCallback);
        mListenAllCallback = null;

        mNetworkMap.clear();
    }

    public void updateMobileRequiresDun(boolean dunRequired) {
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
        if (mMobileNetworkCallback != null) {
            mLog.e("registerMobileNetworkRequest() already registered");
            return;
        }

        // The following use of the legacy type system cannot be removed until
        // after upstream selection no longer finds networks by legacy type.
        // See also http://b/34364553 .
        final int legacyType = mDunRequired ? TYPE_MOBILE_DUN : TYPE_MOBILE_HIPRI;

        final NetworkRequest mobileUpstreamRequest = new NetworkRequest.Builder()
                .setCapabilities(ConnectivityManager.networkCapabilitiesForType(legacyType))
                .build();

        // The existing default network and DUN callbacks will be notified.
        // Therefore, to avoid duplicate notifications, we only register a no-op.
        mMobileNetworkCallback = new UpstreamNetworkCallback(CALLBACK_MOBILE_REQUEST);

        // TODO: Change the timeout from 0 (no onUnavailable callback) to some
        // moderate callback timeout. This might be useful for updating some UI.
        // Additionally, we log a message to aid in any subsequent debugging.
        mLog.i("requesting mobile upstream network: " + mobileUpstreamRequest);

        cm().requestNetwork(mobileUpstreamRequest, mMobileNetworkCallback, 0, legacyType, mHandler);
    }

    public void releaseMobileNetworkRequest() {
        if (mMobileNetworkCallback == null) return;

        cm().unregisterNetworkCallback(mMobileNetworkCallback);
        mMobileNetworkCallback = null;
    }

    public NetworkState lookup(Network network) {
        return (network != null) ? mNetworkMap.get(network) : null;
    }

    private void handleAvailable(int callbackType, Network network) {
        if (VDBG) Log.d(TAG, "EVENT_ON_AVAILABLE for " + network);

        if (!mNetworkMap.containsKey(network)) {
            mNetworkMap.put(network,
                    new NetworkState(null, null, null, network, null, null));
        }

        // Always request whatever extra information we can, in case this
        // was already up when start() was called, in which case we would
        // not have been notified of any information that had not changed.
        switch (callbackType) {
            case CALLBACK_LISTEN_ALL:
                break;

            case CALLBACK_TRACK_DEFAULT:
                if (mDefaultNetworkCallback == null) {
                    // The callback was unregistered in the interval between
                    // ConnectivityService enqueueing onAvailable() and our
                    // handling of it here on the mHandler thread.
                    //
                    // Clean-up of this network entry is deferred to the
                    // handling of onLost() by other callbacks.
                    //
                    // These request*() calls can be deleted post oag/339444.
                    return;
                }
                mCurrentDefault = network;
                break;

            case CALLBACK_MOBILE_REQUEST:
                if (mMobileNetworkCallback == null) {
                    // The callback was unregistered in the interval between
                    // ConnectivityService enqueueing onAvailable() and our
                    // handling of it here on the mHandler thread.
                    //
                    // Clean-up of this network entry is deferred to the
                    // handling of onLost() by other callbacks.
                    return;
                }
                break;
        }

        // Requesting updates for mListenAllCallback is not currently possible
        // because it's a "listen". Two possible solutions to getting updates
        // about networks without waiting for a change (which might never come)
        // are:
        //
        //     [1] extend request{NetworkCapabilities,LinkProperties}() to
        //         take a Network argument and have ConnectivityService do
        //         what's required (if the network satisfies the request)
        //
        //     [2] explicitly file a NetworkRequest for each connectivity type
        //         listed as a preferred upstream and wait for these callbacks
        //         to be notified (requires tracking many more callbacks).
        //
        // Until this is addressed, networks that exist prior to the "listen"
        // registration and which do not subsequently change will not cause
        // us to learn their NetworkCapabilities nor their LinkProperties.

        // TODO: If sufficient information is available to select a more
        // preferable upstream, do so now and notify the target.
        notifyTarget(EVENT_ON_AVAILABLE, network);
    }

    private void handleNetCap(Network network, NetworkCapabilities newNc) {
        final NetworkState prev = mNetworkMap.get(network);
        if (prev == null || newNc.equals(prev.networkCapabilities)) {
            // Ignore notifications about networks for which we have not yet
            // received onAvailable() (should never happen) and any duplicate
            // notifications (e.g. matching more than one of our callbacks).
            return;
        }

        if (VDBG) {
            Log.d(TAG, String.format("EVENT_ON_CAPABILITIES for %s: %s",
                    network, newNc));
        }

        mNetworkMap.put(network, new NetworkState(
                null, prev.linkProperties, newNc, network, null, null));
        // TODO: If sufficient information is available to select a more
        // preferable upstream, do so now and notify the target.
        notifyTarget(EVENT_ON_CAPABILITIES, network);
    }

    private void handleLinkProp(Network network, LinkProperties newLp) {
        final NetworkState prev = mNetworkMap.get(network);
        if (prev == null || newLp.equals(prev.linkProperties)) {
            // Ignore notifications about networks for which we have not yet
            // received onAvailable() (should never happen) and any duplicate
            // notifications (e.g. matching more than one of our callbacks).
            return;
        }

        if (VDBG) {
            Log.d(TAG, String.format("EVENT_ON_LINKPROPERTIES for %s: %s",
                    network, newLp));
        }

        mNetworkMap.put(network, new NetworkState(
                null, newLp, prev.networkCapabilities, network, null, null));
        // TODO: If sufficient information is available to select a more
        // preferable upstream, do so now and notify the target.
        notifyTarget(EVENT_ON_LINKPROPERTIES, network);
    }

    private void handleLost(int callbackType, Network network) {
        if (callbackType == CALLBACK_TRACK_DEFAULT) {
            mCurrentDefault = null;
            // Receiving onLost() for a default network does not necessarily
            // mean the network is gone.  We wait for a separate notification
            // on either the LISTEN_ALL or MOBILE_REQUEST callbacks before
            // clearing all state.
            return;
        }

        if (!mNetworkMap.containsKey(network)) {
            // Ignore loss of networks about which we had not previously
            // learned any information or for which we have already processed
            // an onLost() notification.
            return;
        }

        if (VDBG) Log.d(TAG, "EVENT_ON_LOST for " + network);

        // TODO: If sufficient information is available to select a more
        // preferable upstream, do so now and notify the target.  Likewise,
        // if the current upstream network is gone, notify the target of the
        // fact that we now have no upstream at all.
        notifyTarget(EVENT_ON_LOST, mNetworkMap.remove(network));
    }

    // Fetch (and cache) a ConnectivityManager only if and when we need one.
    private ConnectivityManager cm() {
        if (mCM == null) {
            // MUST call the String variant to be able to write unittests.
            mCM = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        }
        return mCM;
    }

    /**
     * A NetworkCallback class that handles information of interest directly
     * in the thread on which it is invoked. To avoid locking, this MUST be
     * run on the same thread as the target state machine's handler.
     */
    private class UpstreamNetworkCallback extends NetworkCallback {
        private final int mCallbackType;

        UpstreamNetworkCallback(int callbackType) {
            mCallbackType = callbackType;
        }

        @Override
        public void onAvailable(Network network) {
            checkExpectedThread();
            handleAvailable(mCallbackType, network);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities newNc) {
            checkExpectedThread();
            handleNetCap(network, newNc);
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties newLp) {
            checkExpectedThread();
            handleLinkProp(network, newLp);
        }

        // TODO: Handle onNetworkSuspended();
        // TODO: Handle onNetworkResumed();

        @Override
        public void onLost(Network network) {
            checkExpectedThread();
            handleLost(mCallbackType, network);
        }

        private void checkExpectedThread() {
            if (Looper.myLooper() != mHandler.getLooper()) {
                Log.wtf(TAG, "Handling callback in unexpected thread.");
            }
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
