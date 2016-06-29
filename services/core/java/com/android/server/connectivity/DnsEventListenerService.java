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
import android.net.Network;
import android.net.NetworkRequest;
import android.net.metrics.DnsEvent;
import android.net.metrics.IDnsEventListener;
import android.net.metrics.IpConnectivityLog;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.SortedMap;
import java.util.TreeMap;


/**
 * Implementation of the IDnsEventListener interface.
 */
public class DnsEventListenerService extends IDnsEventListener.Stub {

    public static final String SERVICE_NAME = "dns_listener";

    private static final String TAG = DnsEventListenerService.class.getSimpleName();
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    // TODO: read this constant from system property
    private static final int MAX_LOOKUPS_PER_DNS_EVENT = 100;

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
            maybeLog(String.format("Logging %d results for netId %d", mEventCount, mNetId));
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
            synchronized (DnsEventListenerService.this) {
                DnsEventBatch batch = mEventBatches.remove(network.netId);
                if (batch != null) {
                    batch.logAndClear();
                }
            }
        }
    };

    public DnsEventListenerService(Context context) {
        this(context.getSystemService(ConnectivityManager.class), new IpConnectivityLog());
    }

    @VisibleForTesting
    public DnsEventListenerService(ConnectivityManager cm, IpConnectivityLog log) {
        // We are started when boot is complete, so ConnectivityService should already be running.
        mCm = cm;
        mMetricsLog = log;
        final NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        mCm.registerNetworkCallback(request, mNetworkCallback);
    }

    @Override
    // Called concurrently by multiple binder threads.
    public synchronized void onDnsEvent(int netId, int eventType, int returnCode, int latencyMs) {
        maybeVerboseLog(String.format("onDnsEvent(%d, %d, %d, %d)",
                netId, eventType, returnCode, latencyMs));

        DnsEventBatch batch = mEventBatches.get(netId);
        if (batch == null) {
            batch = new DnsEventBatch(netId);
            mEventBatches.put(netId, batch);
        }
        batch.addResult((byte) eventType, (byte) returnCode, latencyMs);
    }

    public synchronized void dump(PrintWriter writer) {
        IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        pw.println(TAG + ":");
        pw.increaseIndent();
        for (DnsEventBatch batch : mEventBatches.values()) {
            pw.println(batch.toString());
        }
        pw.decreaseIndent();
    }

    private static void maybeLog(String s) {
        if (DBG) Log.d(TAG, s);
    }

    private static void maybeVerboseLog(String s) {
        if (VDBG) Log.d(TAG, s);
    }
}
