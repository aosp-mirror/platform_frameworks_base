/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.wifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import static android.net.NetworkInfo.DetailedState.CONNECTED;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.os.Handler;
import android.os.Message;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.android.internal.util.AsyncChannel;

/* Polls for traffic stats and notifies the clients */
final class WifiTrafficPoller {
    /**
     * Interval in milliseconds between polling for traffic
     * statistics
     */
    private static final int POLL_TRAFFIC_STATS_INTERVAL_MSECS = 1000;

    private static final int ENABLE_TRAFFIC_STATS_POLL  = 1;
    private static final int TRAFFIC_STATS_POLL         = 2;
    private static final int ADD_CLIENT                 = 3;
    private static final int REMOVE_CLIENT              = 4;

    private boolean mEnableTrafficStatsPoll = false;
    private int mTrafficStatsPollToken = 0;
    private long mTxPkts;
    private long mRxPkts;
    /* Tracks last reported data activity */
    private int mDataActivity;

    private final List<Messenger> mClients = new ArrayList<Messenger>();
    // err on the side of updating at boot since screen on broadcast may be missed
    // the first time
    private AtomicBoolean mScreenOn = new AtomicBoolean(true);
    private final TrafficHandler mTrafficHandler;
    private NetworkInfo mNetworkInfo;
    private final String mInterface;

    WifiTrafficPoller(Context context, String iface) {
        mInterface = iface;
        mTrafficHandler = new TrafficHandler();

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);

        context.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent.getAction().equals(
                                WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                            mNetworkInfo = (NetworkInfo) intent.getParcelableExtra(
                                    WifiManager.EXTRA_NETWORK_INFO);
                        } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                            mScreenOn.set(false);
                        } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                            mScreenOn.set(true);
                        }
                        evaluateTrafficStatsPolling();
                    }
                }, filter);
    }

    void addClient(Messenger client) {
        Message.obtain(mTrafficHandler, ADD_CLIENT, client).sendToTarget();
    }

    void removeClient(Messenger client) {
        Message.obtain(mTrafficHandler, REMOVE_CLIENT, client).sendToTarget();
    }


    private class TrafficHandler extends Handler {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ENABLE_TRAFFIC_STATS_POLL:
                    mEnableTrafficStatsPoll = (msg.arg1 == 1);
                    mTrafficStatsPollToken++;
                    if (mEnableTrafficStatsPoll) {
                        notifyOnDataActivity();
                        sendMessageDelayed(Message.obtain(this, TRAFFIC_STATS_POLL,
                                mTrafficStatsPollToken, 0), POLL_TRAFFIC_STATS_INTERVAL_MSECS);
                    }
                    break;
                case TRAFFIC_STATS_POLL:
                    if (msg.arg1 == mTrafficStatsPollToken) {
                        notifyOnDataActivity();
                        sendMessageDelayed(Message.obtain(this, TRAFFIC_STATS_POLL,
                                mTrafficStatsPollToken, 0), POLL_TRAFFIC_STATS_INTERVAL_MSECS);
                    }
                    break;
                case ADD_CLIENT:
                    mClients.add((Messenger) msg.obj);
                    break;
                case REMOVE_CLIENT:
                    mClients.remove(msg.obj);
                    break;
            }

        }
    }

    private void evaluateTrafficStatsPolling() {
        Message msg;
        if (mNetworkInfo == null) return;
        if (mNetworkInfo.getDetailedState() == CONNECTED && mScreenOn.get()) {
            msg = Message.obtain(mTrafficHandler,
                    ENABLE_TRAFFIC_STATS_POLL, 1, 0);
        } else {
            msg = Message.obtain(mTrafficHandler,
                    ENABLE_TRAFFIC_STATS_POLL, 0, 0);
        }
        msg.sendToTarget();
    }

    private void notifyOnDataActivity() {
        long sent, received;
        long preTxPkts = mTxPkts, preRxPkts = mRxPkts;
        int dataActivity = WifiManager.DATA_ACTIVITY_NONE;

        mTxPkts = TrafficStats.getTxPackets(mInterface);
        mRxPkts = TrafficStats.getRxPackets(mInterface);

        if (preTxPkts > 0 || preRxPkts > 0) {
            sent = mTxPkts - preTxPkts;
            received = mRxPkts - preRxPkts;
            if (sent > 0) {
                dataActivity |= WifiManager.DATA_ACTIVITY_OUT;
            }
            if (received > 0) {
                dataActivity |= WifiManager.DATA_ACTIVITY_IN;
            }

            if (dataActivity != mDataActivity && mScreenOn.get()) {
                mDataActivity = dataActivity;
                for (Messenger client : mClients) {
                    Message msg = Message.obtain();
                    msg.what = WifiManager.DATA_ACTIVITY_NOTIFICATION;
                    msg.arg1 = mDataActivity;
                    try {
                        client.send(msg);
                    } catch (RemoteException e) {
                        // Failed to reach, skip
                        // Client removal is handled in WifiService
                    }
                }
            }
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mEnableTrafficStatsPoll " + mEnableTrafficStatsPoll);
        pw.println("mTrafficStatsPollToken " + mTrafficStatsPollToken);
        pw.println("mTxPkts " + mTxPkts);
        pw.println("mRxPkts " + mRxPkts);
        pw.println("mDataActivity " + mDataActivity);
    }

}
