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
import android.net.INetdEventCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.metrics.ConnectStats;
import android.net.metrics.DnsEvent;
import android.net.metrics.INetdEventListener;
import android.net.metrics.IpConnectivityLog;
import android.os.RemoteException;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.BitUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.TokenBucket;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityEvent;
import java.io.PrintWriter;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Implementation of the INetdEventListener interface.
 */
public class NetdEventListenerService extends INetdEventListener.Stub {

    public static final String SERVICE_NAME = "netd_listener";

    private static final String TAG = NetdEventListenerService.class.getSimpleName();
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    private static final int INITIAL_DNS_BATCH_SIZE = 100;

    // Rate limit connect latency logging to 1 measurement per 15 seconds (5760 / day) with maximum
    // bursts of 5000 measurements.
    private static final int CONNECT_LATENCY_BURST_LIMIT  = 5000;
    private static final int CONNECT_LATENCY_FILL_RATE    = 15 * (int) DateUtils.SECOND_IN_MILLIS;
    private static final int CONNECT_LATENCY_MAXIMUM_RECORDS = 20000;

    // Sparse arrays of DNS and connect events, grouped by net id.
    @GuardedBy("this")
    private final SparseArray<DnsEvent> mDnsEvents = new SparseArray<>();
    @GuardedBy("this")
    private final SparseArray<ConnectStats> mConnectEvents = new SparseArray<>();

    private final ConnectivityManager mCm;

    @GuardedBy("this")
    private final TokenBucket mConnectTb =
            new TokenBucket(CONNECT_LATENCY_FILL_RATE, CONNECT_LATENCY_BURST_LIMIT);
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
        this(context.getSystemService(ConnectivityManager.class));
    }

    @VisibleForTesting
    public NetdEventListenerService(ConnectivityManager cm) {
        // We are started when boot is complete, so ConnectivityService should already be running.
        mCm = cm;
    }

    @Override
    // Called concurrently by multiple binder threads.
    // This method must not block or perform long-running operations.
    public synchronized void onDnsEvent(int netId, int eventType, int returnCode, int latencyMs,
            String hostname, String[] ipAddresses, int ipAddressesCount, int uid)
            throws RemoteException {
        maybeVerboseLog("onDnsEvent(%d, %d, %d, %dms)", netId, eventType, returnCode, latencyMs);

        DnsEvent dnsEvent = mDnsEvents.get(netId);
        if (dnsEvent == null) {
            dnsEvent = makeDnsEvent(netId);
            mDnsEvents.put(netId, dnsEvent);
        }
        dnsEvent.addResult((byte) eventType, (byte) returnCode, latencyMs);

        if (mNetdEventCallback != null) {
            long timestamp = System.currentTimeMillis();
            mNetdEventCallback.onDnsEvent(hostname, ipAddresses, ipAddressesCount, timestamp, uid);
        }
    }

    @Override
    // Called concurrently by multiple binder threads.
    // This method must not block or perform long-running operations.
    public synchronized void onConnectEvent(int netId, int error, int latencyMs, String ipAddr,
            int port, int uid) throws RemoteException {
        maybeVerboseLog("onConnectEvent(%d, %d, %dms)", netId, error, latencyMs);

        ConnectStats connectStats = mConnectEvents.get(netId);
        if (connectStats == null) {
            connectStats = makeConnectStats(netId);
            mConnectEvents.put(netId, connectStats);
        }
        connectStats.addEvent(error, latencyMs, ipAddr);

        if (mNetdEventCallback != null) {
            mNetdEventCallback.onConnectEvent(ipAddr, port, System.currentTimeMillis(), uid);
        }
    }

    @Override
    public synchronized void onWakeupEvent(String prefix, int uid, int gid, long timestampNs) {
    }

    public synchronized void flushStatistics(List<IpConnectivityEvent> events) {
        flushProtos(events, mConnectEvents, IpConnectivityEventBuilder::toProto);
        flushProtos(events, mDnsEvents, IpConnectivityEventBuilder::toProto);
    }

    public synchronized void dump(PrintWriter writer) {
        IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        pw.println(TAG + ":");
        pw.increaseIndent();
        list(pw);
        pw.decreaseIndent();
    }

    public synchronized void list(PrintWriter pw) {
        listEvents(pw, mConnectEvents, (x) -> x);
        listEvents(pw, mDnsEvents, (x) -> x);
    }

    public synchronized void listAsProtos(PrintWriter pw) {
        listEvents(pw, mConnectEvents, IpConnectivityEventBuilder::toProto);
        listEvents(pw, mDnsEvents, IpConnectivityEventBuilder::toProto);
    }

    private static <T> void flushProtos(List<IpConnectivityEvent> out, SparseArray<T> in,
            Function<T, IpConnectivityEvent> mapper) {
        for (int i = 0; i < in.size(); i++) {
            out.add(mapper.apply(in.valueAt(i)));
        }
        in.clear();
    }

    public static <T> void listEvents(
            PrintWriter pw, SparseArray<T> events, Function<T, Object> mapper) {
        for (int i = 0; i < events.size(); i++) {
            pw.println(mapper.apply(events.valueAt(i)).toString());
        }
    }

    private ConnectStats makeConnectStats(int netId) {
        long transports = getTransports(netId);
        return new ConnectStats(netId, transports, mConnectTb, CONNECT_LATENCY_MAXIMUM_RECORDS);
    }

    private DnsEvent makeDnsEvent(int netId) {
        long transports = getTransports(netId);
        return new DnsEvent(netId, transports, INITIAL_DNS_BATCH_SIZE);
    }

    private long getTransports(int netId) {
        // TODO: directly query ConnectivityService instead of going through Binder interface.
        NetworkCapabilities nc = mCm.getNetworkCapabilities(new Network(netId));
        if (nc == null) {
            return 0;
        }
        return BitUtils.packBits(nc.getTransportTypes());
    }

    private static void maybeLog(String s, Object... args) {
        if (DBG) Log.d(TAG, String.format(s, args));
    }

    private static void maybeVerboseLog(String s, Object... args) {
        if (VDBG) Log.d(TAG, String.format(s, args));
    }
}
