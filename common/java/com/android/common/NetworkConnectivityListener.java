/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.common;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;

/**
 * A wrapper for a broadcast receiver that provides network connectivity
 * state information, independent of network type (mobile, Wi-Fi, etc.).
 * @deprecated Code tempted to use this class should simply listen for connectivity intents
 * (or poll ConnectivityManager) directly.
 * {@hide}
 */
public class NetworkConnectivityListener {
    private static final String TAG = "NetworkConnectivityListener";
    private static final boolean DBG = false;

    private Context mContext;
    private HashMap<Handler, Integer> mHandlers = new HashMap<Handler, Integer>();
    private State mState;
    private boolean mListening;
    private String mReason;
    private boolean mIsFailover;

    /** Network connectivity information */
    private NetworkInfo mNetworkInfo;

    /**
     * In case of a Disconnect, the connectivity manager may have
     * already established, or may be attempting to establish, connectivity
     * with another network. If so, {@code mOtherNetworkInfo} will be non-null.
     */
    private NetworkInfo mOtherNetworkInfo;

    private ConnectivityBroadcastReceiver mReceiver;

    private class ConnectivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION) ||
                mListening == false) {
                Log.w(TAG, "onReceived() called with " + mState.toString() + " and " + intent);
                return;
            }

            boolean noConnectivity =
                intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);

            if (noConnectivity) {
                mState = State.NOT_CONNECTED;
            } else {
                mState = State.CONNECTED;
            }

            mNetworkInfo = (NetworkInfo)
                intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            mOtherNetworkInfo = (NetworkInfo)
                intent.getParcelableExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO);

            mReason = intent.getStringExtra(ConnectivityManager.EXTRA_REASON);
            mIsFailover =
                intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_FAILOVER, false);

            if (DBG) {
                Log.d(TAG, "onReceive(): mNetworkInfo=" + mNetworkInfo +  " mOtherNetworkInfo = "
                        + (mOtherNetworkInfo == null ? "[none]" : mOtherNetworkInfo +
                        " noConn=" + noConnectivity) + " mState=" + mState.toString());
            }

            // Notifiy any handlers.
            Iterator<Handler> it = mHandlers.keySet().iterator();
            while (it.hasNext()) {
                Handler target = it.next();
                Message message = Message.obtain(target, mHandlers.get(target));
                target.sendMessage(message);
            }
        }
    };

    public enum State {
        UNKNOWN,

        /** This state is returned if there is connectivity to any network **/
        CONNECTED,
        /**
         * This state is returned if there is no connectivity to any network. This is set
         * to true under two circumstances:
         * <ul>
         * <li>When connectivity is lost to one network, and there is no other available
         * network to attempt to switch to.</li>
         * <li>When connectivity is lost to one network, and the attempt to switch to
         * another network fails.</li>
         */
        NOT_CONNECTED
    }

    /**
     * Create a new NetworkConnectivityListener.
     */
    public NetworkConnectivityListener() {
        mState = State.UNKNOWN;
        mReceiver = new ConnectivityBroadcastReceiver();
    }

    /**
     * This method starts listening for network connectivity state changes.
     * @param context
     */
    public synchronized void startListening(Context context) {
        if (!mListening) {
            mContext = context;

            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            context.registerReceiver(mReceiver, filter);
            mListening = true;
        }
    }

    /**
     * This method stops this class from listening for network changes.
     */
    public synchronized void stopListening() {
        if (mListening) {
            mContext.unregisterReceiver(mReceiver);
            mContext = null;
            mNetworkInfo = null;
            mOtherNetworkInfo = null;
            mIsFailover = false;
            mReason = null;
            mListening = false;
        }
    }

    /**
     * This methods registers a Handler to be called back onto with the specified what code when
     * the network connectivity state changes.
     *
     * @param target The target handler.
     * @param what The what code to be used when posting a message to the handler.
     */
    public void registerHandler(Handler target, int what) {
        mHandlers.put(target, what);
    }

    /**
     * This methods unregisters the specified Handler.
     * @param target
     */
    public void unregisterHandler(Handler target) {
        mHandlers.remove(target);
    }

    public State getState() {
        return mState;
    }

    /**
     * Return the NetworkInfo associated with the most recent connectivity event.
     * @return {@code NetworkInfo} for the network that had the most recent connectivity event.
     */
    public NetworkInfo getNetworkInfo() {
        return mNetworkInfo;
    }

    /**
     * If the most recent connectivity event was a DISCONNECT, return
     * any information supplied in the broadcast about an alternate
     * network that might be available. If this returns a non-null
     * value, then another broadcast should follow shortly indicating
     * whether connection to the other network succeeded.
     *
     * @return NetworkInfo
     */
    public NetworkInfo getOtherNetworkInfo() {
        return mOtherNetworkInfo;
    }

    /**
     * Returns true if the most recent event was for an attempt to switch over to
     * a new network following loss of connectivity on another network.
     * @return {@code true} if this was a failover attempt, {@code false} otherwise.
     */
    public boolean isFailover() {
        return mIsFailover;
    }

    /**
     * An optional reason for the connectivity state change may have been supplied.
     * This returns it.
     * @return the reason for the state change, if available, or {@code null}
     * otherwise.
     */
    public String getReason() {
        return mReason;
    }
}
