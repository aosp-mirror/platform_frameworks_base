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

package com.android.server.connectivity;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetdEventCallback;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.metrics.DnsEvent;
import android.net.metrics.INetdEventListener;
import android.net.metrics.IpConnectivityLog;
import android.os.RemoteException;
import android.text.format.DateUtils;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.TokenBucket;
import com.android.server.connectivity.metrics.IpConnectivityLogClass.ConnectStatistics;
import com.android.server.connectivity.metrics.IpConnectivityLogClass.IpConnectivityEvent;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Implementation of the INetdEventListener interface.
 */
public class NetdEventListenerService extends INetdEventListener.Stub {

    public static final String SERVICE_NAME = "netd_listener";

    private static final String TAG = NetdEventListenerService.class.getSimpleName();
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    // TODO: read this constant from system property
    private static final int MAX_LOOKUPS_PER_DNS_EVENT = 100;

    // Rate limit connect latency logging to 1 measurement per 15 seconds (5760 / day) with maximum
    // bursts of 5000 measurements.
    private static final int CONNECT_LATENCY_BURST_LIMIT  = 5000;
    private static final int CONNECT_LATENCY_FILL_RATE    = 15 * (int) DateUtils.SECOND_IN_MILLIS;
    private static final int CONNECT_LATENCY_MAXIMUM_RECORDS = 20000;

    // Stores the results of a number of consecutive DNS lookups on the same network.
    // This class is not thread-safe and it is the responsibility of the service to call its methods
    // on one thread at a time.
    private class DnsEventBatch {
        private final int mNetId;

        private final byte[] mEventTypes = new byte[MAX_LOOKUPS_PER_DNS_EVENT];
        private final byte[] mReturnCodes = new byte[MAX_LOOKUPS_PER_DNS_EVENT];
        private final int[] mLatenciesMs = new int[MAX_LOOKUPS_PER_DNS_EVENT];
        private int mEventCount;

        public DnsEventBatch(int netId) {
            mNetId = netId;
        }

        public void addResult(byte eventType, byte returnCode, int latencyMs) {
            mEventTypes[mEventCount] = eventType;
            mReturnCodes[mEventCount] = returnCode;
            mLatenciesMs[mEventCount] = latencyMs;
            mEventCount++;
            if (mEventCount == MAX_LOOKUPS_PER_DNS_EVENT) {
                logAndClear();
            }
        }

        public void logAndClear() {
            // Did we lose a race with addResult?
            if (mEventCount == 0) {
                return;
            }

            // Only log as many events as we actually have.
            byte[] eventTypes = Arrays.copyOf(mEventTypes, mEventCount);
            byte[] returnCodes = Arrays.copyOf(mReturnCodes, mEventCount);
            int[] latenciesMs = Arrays.copyOf(mLatenciesMs, mEventCount);
            mMetricsLog.log(new DnsEvent(mNetId, eventTypes, returnCodes, latenciesMs));
            maybeLog("Logging %d results for netId %d", mEventCount, mNetId);
            mEventCount = 0;
        }

        // For debugging and unit tests only.
        public String toString() {
            return String.format("%s %d %d", getClass().getSimpleName(), mNetId, mEventCount);
        }
    }

    // Only sorted for ease of debugging. Because we only typically have a handful of networks up
    // at any given time, performance is not a concern.
    @GuardedBy("this")
    private final SortedMap<Integer, DnsEventBatch> mEventBatches = new TreeMap<>();

    // We register a NetworkCallback to ensure that when a network disconnects, we flush the DNS
    // queries we've logged on that network. Because we do not do this periodically, we might lose
    // up to MAX_LOOKUPS_PER_DNS_EVENT lookup stats on each network when the system is shutting
    // down. We believe this to be sufficient for now.
    private final ConnectivityManager mCm;
    private final IpConnectivityLog mMetricsLog;
    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        @Override
        public void onLost(Network network) {
            synchronized (NetdEventListenerService.this) {
                DnsEventBatch batch = mEventBatches.remove(network.netId);
                if (batch != null) {
                    batch.logAndClear();
                }
            }
        }
    };

    @GuardedBy("this")
    private final TokenBucket mConnectTb =
            new TokenBucket(CONNECT_LATENCY_FILL_RATE, CONNECT_LATENCY_BURST_LIMIT);
    @GuardedBy("this")
    private ConnectStats mConnectStats = makeConnectStats();

    // Callback should only be registered/unregistered when logging is being enabled/disabled in DPM
    // by the device owner. It's DevicePolicyManager's responsibility to ensure that.
    @GuardedBy("this")
    private INetdEventCallback mNetdEventCallback;

    public synchronized boolean registerNetdEventCallback(INetdEventCallback callback) {
        mNetdEventCallback = callback;
        return true;
    }

    public synchronized boolean unregisterNetdEventCallback() {
        mNetdEventCallback = null;
        return true;
    }

    public NetdEventListenerService(Context context) {
        this(context.getSystemService(ConnectivityManager.class), new IpConnectivityLog());
    }

    @VisibleForTesting
    public NetdEventListenerService(ConnectivityManager cm, IpConnectivityLog log) {
        // We are started when boot is complete, so ConnectivityService should already be running.
        mCm = cm;
        mMetricsLog = log;
        final NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        mCm.registerNetworkCallback(request, mNetworkCallback);
    }

    @Override
    // Called concurrently by multiple binder threads.
    // This method must not block or perform long-running operations.
    public synchronized void onDnsEvent(int netId, int eventType, int returnCode, int latencyMs,
            String hostname, String[] ipAddresses, int ipAddressesCount, int uid)
            throws RemoteException {
        maybeVerboseLog("onDnsEvent(%d, %d, %d, %dms)", netId, eventType, returnCode, latencyMs);

        DnsEventBatch batch = mEventBatches.get(netId);
        if (batch == null) {
            batch = new DnsEventBatch(netId);
            mEventBatches.put(netId, batch);
        }
        batch.addResult((byte) eventType, (byte) returnCode, latencyMs);

        if (mNetdEventCallback != null) {
            mNetdEventCallback.onDnsEvent(hostname, ipAddresses, ipAddressesCount,
                    System.currentTimeMillis(), uid);
        }
    }

    @Override
    // Called concurrently by multiple binder threads.
    // This method must not block or perform long-running operations.
    public synchronized void onConnectEvent(int netId, int error, int latencyMs, String ipAddr, int port,
            int uid) throws RemoteException {
        maybeVerboseLog("onConnectEvent(%d, %d)", netId, latencyMs);

        mConnectStats.addEvent(error, latencyMs, ipAddr);

        if (mNetdEventCallback != null) {
            mNetdEventCallback.onConnectEvent(ipAddr, port, System.currentTimeMillis(), uid);
        }
    }

    public synchronized void flushStatistics(List<IpConnectivityEvent> events) {
        events.add(flushConnectStats());
        // TODO: migrate DnsEventBatch to IpConnectivityLogClass.DNSLatencies
    }

    private IpConnectivityEvent flushConnectStats() {
        IpConnectivityEvent ev = new IpConnectivityEvent();
        ev.connectStatistics = mConnectStats.toProto();
        // TODO: add transport information
        mConnectStats = makeConnectStats();
        return ev;
    }

    public synchronized void dump(PrintWriter writer) {
        IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        pw.println(TAG + ":");
        pw.increaseIndent();
        for (DnsEventBatch batch : mEventBatches.values()) {
            pw.println(batch.toString());
        }
        // TODO: also dump ConnectStats
        pw.decreaseIndent();
    }

    private ConnectStats makeConnectStats() {
        return new ConnectStats(mConnectTb, CONNECT_LATENCY_MAXIMUM_RECORDS);
    }

    private static void maybeLog(String s, Object... args) {
        if (DBG) Log.d(TAG, String.format(s, args));
    }

    private static void maybeVerboseLog(String s, Object... args) {
        if (VDBG) Log.d(TAG, String.format(s, args));
    }
}
