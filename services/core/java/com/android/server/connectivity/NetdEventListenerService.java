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

import static android.util.TimeUtils.NANOS_PER_MS;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetdEventCallback;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.metrics.ConnectStats;
import android.net.metrics.DnsEvent;
import android.net.metrics.INetdEventListener;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.NetworkMetrics;
import android.net.metrics.WakeupEvent;
import android.net.metrics.WakeupStats;
import android.os.RemoteException;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.StatsLog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.BitUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.RingBuffer;
import com.android.internal.util.TokenBucket;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityEvent;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Implementation of the INetdEventListener interface.
 */
public class NetdEventListenerService extends INetdEventListener.Stub {

    public static final String SERVICE_NAME = "netd_listener";

    private static final String TAG = NetdEventListenerService.class.getSimpleName();
    private static final boolean DBG = false;

    // Rate limit connect latency logging to 1 measurement per 15 seconds (5760 / day) with maximum
    // bursts of 5000 measurements.
    private static final int CONNECT_LATENCY_BURST_LIMIT  = 5000;
    private static final int CONNECT_LATENCY_FILL_RATE    = 15 * (int) DateUtils.SECOND_IN_MILLIS;

    private static final long METRICS_SNAPSHOT_SPAN_MS = 5 * DateUtils.MINUTE_IN_MILLIS;
    private static final int METRICS_SNAPSHOT_BUFFER_SIZE = 48; // 4 hours

    @VisibleForTesting
    static final int WAKEUP_EVENT_BUFFER_LENGTH = 1024;
    // TODO: dedup this String constant with the one used in
    // ConnectivityService#wakeupModifyInterface().
    @VisibleForTesting
    static final String WAKEUP_EVENT_IFACE_PREFIX = "iface:";

    // Array of aggregated DNS and connect events sent by netd, grouped by net id.
    @GuardedBy("this")
    private final SparseArray<NetworkMetrics> mNetworkMetrics = new SparseArray<>();

    @GuardedBy("this")
    private final RingBuffer<NetworkMetricsSnapshot> mNetworkMetricsSnapshots =
            new RingBuffer<>(NetworkMetricsSnapshot.class, METRICS_SNAPSHOT_BUFFER_SIZE);
    @GuardedBy("this")
    private long mLastSnapshot = 0;

    // Array of aggregated wakeup event stats, grouped by interface name.
    @GuardedBy("this")
    private final ArrayMap<String, WakeupStats> mWakeupStats = new ArrayMap<>();
    // Ring buffer array for storing packet wake up events sent by Netd.
    @GuardedBy("this")
    private final RingBuffer<WakeupEvent> mWakeupEvents =
            new RingBuffer<>(WakeupEvent.class, WAKEUP_EVENT_BUFFER_LENGTH);

    private final ConnectivityManager mCm;

    @GuardedBy("this")
    private final TokenBucket mConnectTb =
            new TokenBucket(CONNECT_LATENCY_FILL_RATE, CONNECT_LATENCY_BURST_LIMIT);


    /**
     * There are only 3 possible callbacks.
     *
     * mNetdEventCallbackList[CALLBACK_CALLER_CONNECTIVITY_SERVICE]
     * Callback registered/unregistered by ConnectivityService.
     *
     * mNetdEventCallbackList[CALLBACK_CALLER_DEVICE_POLICY]
     * Callback registered/unregistered when logging is being enabled/disabled in DPM
     * by the device owner. It's DevicePolicyManager's responsibility to ensure that.
     *
     * mNetdEventCallbackList[CALLBACK_CALLER_NETWORK_WATCHLIST]
     * Callback registered/unregistered by NetworkWatchlistService.
     */
    @GuardedBy("this")
    private static final int[] ALLOWED_CALLBACK_TYPES = {
        INetdEventCallback.CALLBACK_CALLER_CONNECTIVITY_SERVICE,
        INetdEventCallback.CALLBACK_CALLER_DEVICE_POLICY,
        INetdEventCallback.CALLBACK_CALLER_NETWORK_WATCHLIST
    };

    @GuardedBy("this")
    private INetdEventCallback[] mNetdEventCallbackList =
            new INetdEventCallback[ALLOWED_CALLBACK_TYPES.length];

    public synchronized boolean addNetdEventCallback(int callerType, INetdEventCallback callback) {
        if (!isValidCallerType(callerType)) {
            Log.e(TAG, "Invalid caller type: " + callerType);
            return false;
        }
        mNetdEventCallbackList[callerType] = callback;
        return true;
    }

    public synchronized boolean removeNetdEventCallback(int callerType) {
        if (!isValidCallerType(callerType)) {
            Log.e(TAG, "Invalid caller type: " + callerType);
            return false;
        }
        mNetdEventCallbackList[callerType] = null;
        return true;
    }

    private static boolean isValidCallerType(int callerType) {
        for (int i = 0; i < ALLOWED_CALLBACK_TYPES.length; i++) {
            if (callerType == ALLOWED_CALLBACK_TYPES[i]) {
                return true;
            }
        }
        return false;
    }

    public NetdEventListenerService(Context context) {
        this(context.getSystemService(ConnectivityManager.class));
    }

    @VisibleForTesting
    public NetdEventListenerService(ConnectivityManager cm) {
        // We are started when boot is complete, so ConnectivityService should already be running.
        mCm = cm;
    }

    private static long projectSnapshotTime(long timeMs) {
        return (timeMs / METRICS_SNAPSHOT_SPAN_MS) * METRICS_SNAPSHOT_SPAN_MS;
    }

    private NetworkMetrics getMetricsForNetwork(long timeMs, int netId) {
        collectPendingMetricsSnapshot(timeMs);
        NetworkMetrics metrics = mNetworkMetrics.get(netId);
        if (metrics == null) {
            // TODO: allow to change transport for a given netid.
            metrics = new NetworkMetrics(netId, getTransports(netId), mConnectTb);
            mNetworkMetrics.put(netId, metrics);
        }
        return metrics;
    }

    private NetworkMetricsSnapshot[] getNetworkMetricsSnapshots() {
        collectPendingMetricsSnapshot(System.currentTimeMillis());
        return mNetworkMetricsSnapshots.toArray();
    }

    private void collectPendingMetricsSnapshot(long timeMs) {
        // Detects time differences larger than the snapshot collection period.
        // This is robust against clock jumps and long inactivity periods.
        if (Math.abs(timeMs - mLastSnapshot) <= METRICS_SNAPSHOT_SPAN_MS) {
            return;
        }
        mLastSnapshot = projectSnapshotTime(timeMs);
        NetworkMetricsSnapshot snapshot =
                NetworkMetricsSnapshot.collect(mLastSnapshot, mNetworkMetrics);
        if (snapshot.stats.isEmpty()) {
            return;
        }
        mNetworkMetricsSnapshots.append(snapshot);
    }

    @Override
    // Called concurrently by multiple binder threads.
    // This method must not block or perform long-running operations.
    public synchronized void onDnsEvent(int netId, int eventType, int returnCode, int latencyMs,
            String hostname, String[] ipAddresses, int ipAddressesCount, int uid)
            throws RemoteException {
        long timestamp = System.currentTimeMillis();
        getMetricsForNetwork(timestamp, netId).addDnsResult(eventType, returnCode, latencyMs);

        for (INetdEventCallback callback : mNetdEventCallbackList) {
            if (callback != null) {
                callback.onDnsEvent(netId, eventType, returnCode, hostname, ipAddresses,
                        ipAddressesCount, timestamp, uid);
            }
        }
    }

    @Override
    // Called concurrently by multiple binder threads.
    // This method must not block or perform long-running operations.
    public synchronized void onNat64PrefixEvent(int netId,
            boolean added, String prefixString, int prefixLength)
            throws RemoteException {
        for (INetdEventCallback callback : mNetdEventCallbackList) {
            if (callback != null) {
                callback.onNat64PrefixEvent(netId, added, prefixString, prefixLength);
            }
        }
    }

    @Override
    // Called concurrently by multiple binder threads.
    // This method must not block or perform long-running operations.
    public synchronized void onPrivateDnsValidationEvent(int netId,
            String ipAddress, String hostname, boolean validated)
            throws RemoteException {
        for (INetdEventCallback callback : mNetdEventCallbackList) {
            if (callback != null) {
                callback.onPrivateDnsValidationEvent(netId, ipAddress, hostname, validated);
            }
        }
    }

    @Override
    // Called concurrently by multiple binder threads.
    // This method must not block or perform long-running operations.
    public synchronized void onConnectEvent(int netId, int error, int latencyMs, String ipAddr,
            int port, int uid) throws RemoteException {
        long timestamp = System.currentTimeMillis();
        getMetricsForNetwork(timestamp, netId).addConnectResult(error, latencyMs, ipAddr);

        for (INetdEventCallback callback : mNetdEventCallbackList) {
            if (callback != null) {
                callback.onConnectEvent(ipAddr, port, timestamp, uid);
            }
        }
    }

    @Override
    public synchronized void onWakeupEvent(String prefix, int uid, int ethertype, int ipNextHeader,
            byte[] dstHw, String srcIp, String dstIp, int srcPort, int dstPort, long timestampNs) {
        String iface = prefix.replaceFirst(WAKEUP_EVENT_IFACE_PREFIX, "");
        final long timestampMs;
        if (timestampNs > 0) {
            timestampMs = timestampNs / NANOS_PER_MS;
        } else {
            timestampMs = System.currentTimeMillis();
        }

        WakeupEvent event = new WakeupEvent();
        event.iface = iface;
        event.timestampMs = timestampMs;
        event.uid = uid;
        event.ethertype = ethertype;
        event.dstHwAddr = MacAddress.fromBytes(dstHw);
        event.srcIp = srcIp;
        event.dstIp = dstIp;
        event.ipNextHeader = ipNextHeader;
        event.srcPort = srcPort;
        event.dstPort = dstPort;
        addWakeupEvent(event);

        String dstMac = event.dstHwAddr.toString();
        StatsLog.write(StatsLog.PACKET_WAKEUP_OCCURRED,
                uid, iface, ethertype, dstMac, srcIp, dstIp, ipNextHeader, srcPort, dstPort);
    }

    @Override
    public synchronized void onTcpSocketStatsEvent(int[] networkIds,
            int[] sentPackets, int[] lostPackets, int[] rttsUs, int[] sentAckDiffsMs) {
        if (networkIds.length != sentPackets.length
                || networkIds.length != lostPackets.length
                || networkIds.length != rttsUs.length
                || networkIds.length != sentAckDiffsMs.length) {
            Log.e(TAG, "Mismatched lengths of TCP socket stats data arrays");
            return;
        }

        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < networkIds.length; i++) {
            int netId = networkIds[i];
            int sent = sentPackets[i];
            int lost = lostPackets[i];
            int rttUs = rttsUs[i];
            int sentAckDiffMs = sentAckDiffsMs[i];
            getMetricsForNetwork(timestamp, netId)
                    .addTcpStatsResult(sent, lost, rttUs, sentAckDiffMs);
        }
    }

    private void addWakeupEvent(WakeupEvent event) {
        String iface = event.iface;
        mWakeupEvents.append(event);
        WakeupStats stats = mWakeupStats.get(iface);
        if (stats == null) {
            stats = new WakeupStats(iface);
            mWakeupStats.put(iface, stats);
        }
        stats.countEvent(event);
    }

    public synchronized void flushStatistics(List<IpConnectivityEvent> events) {
        for (int i = 0; i < mNetworkMetrics.size(); i++) {
            ConnectStats stats = mNetworkMetrics.valueAt(i).connectMetrics;
            if (stats.eventCount == 0) {
                continue;
            }
            events.add(IpConnectivityEventBuilder.toProto(stats));
        }
        for (int i = 0; i < mNetworkMetrics.size(); i++) {
            DnsEvent ev = mNetworkMetrics.valueAt(i).dnsMetrics;
            if (ev.eventCount == 0) {
                continue;
            }
            events.add(IpConnectivityEventBuilder.toProto(ev));
        }
        for (int i = 0; i < mWakeupStats.size(); i++) {
            events.add(IpConnectivityEventBuilder.toProto(mWakeupStats.valueAt(i)));
        }
        mNetworkMetrics.clear();
        mWakeupStats.clear();
    }

    public synchronized void list(PrintWriter pw) {
        pw.println("dns/connect events:");
        for (int i = 0; i < mNetworkMetrics.size(); i++) {
            pw.println(mNetworkMetrics.valueAt(i).connectMetrics);
        }
        for (int i = 0; i < mNetworkMetrics.size(); i++) {
            pw.println(mNetworkMetrics.valueAt(i).dnsMetrics);
        }
        pw.println("");
        pw.println("network statistics:");
        for (NetworkMetricsSnapshot s : getNetworkMetricsSnapshots()) {
            pw.println(s);
        }
        pw.println("");
        pw.println("packet wakeup events:");
        for (int i = 0; i < mWakeupStats.size(); i++) {
            pw.println(mWakeupStats.valueAt(i));
        }
        for (WakeupEvent wakeup : mWakeupEvents.toArray()) {
            pw.println(wakeup);
        }
    }

    public synchronized void listAsProtos(PrintWriter pw) {
        for (int i = 0; i < mNetworkMetrics.size(); i++) {
            pw.print(IpConnectivityEventBuilder.toProto(mNetworkMetrics.valueAt(i).connectMetrics));
        }
        for (int i = 0; i < mNetworkMetrics.size(); i++) {
            pw.print(IpConnectivityEventBuilder.toProto(mNetworkMetrics.valueAt(i).dnsMetrics));
        }
        for (int i = 0; i < mWakeupStats.size(); i++) {
            pw.print(IpConnectivityEventBuilder.toProto(mWakeupStats.valueAt(i)));
        }
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

    /** Helper class for buffering summaries of NetworkMetrics at regular time intervals */
    static class NetworkMetricsSnapshot {

        public long timeMs;
        public List<NetworkMetrics.Summary> stats = new ArrayList<>();

        static NetworkMetricsSnapshot collect(long timeMs, SparseArray<NetworkMetrics> networkMetrics) {
            NetworkMetricsSnapshot snapshot = new NetworkMetricsSnapshot();
            snapshot.timeMs = timeMs;
            for (int i = 0; i < networkMetrics.size(); i++) {
                NetworkMetrics.Summary s = networkMetrics.valueAt(i).getPendingStats();
                if (s != null) {
                    snapshot.stats.add(s);
                }
            }
            return snapshot;
        }

        @Override
        public String toString() {
            StringJoiner j = new StringJoiner(", ");
            for (NetworkMetrics.Summary s : stats) {
                j.add(s.toString());
            }
            return String.format("%tT.%tL: %s", timeMs, timeMs, j.toString());
        }
    }
}
