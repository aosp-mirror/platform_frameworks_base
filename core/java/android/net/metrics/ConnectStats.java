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

package android.net.metrics;

import android.net.NetworkCapabilities;
import android.system.OsConstants;
import android.util.IntArray;
import android.util.SparseIntArray;

import com.android.internal.util.BitUtils;
import com.android.internal.util.TokenBucket;

/**
 * A class that aggregates connect() statistics.
 * {@hide}
 */
public class ConnectStats {
    private final static int EALREADY     = OsConstants.EALREADY;
    private final static int EINPROGRESS  = OsConstants.EINPROGRESS;

    /** Network id of the network associated with the event, or 0 if unspecified. */
    public final int netId;
    /** Transports of the network associated with the event, as defined in NetworkCapabilities. */
    public final long transports;
    /** How many events resulted in a given errno. */
    public final SparseIntArray errnos = new SparseIntArray();
    /** Latencies of successful blocking connects. TODO: add non-blocking connects latencies. */
    public final IntArray latencies = new IntArray();
    /** TokenBucket for rate limiting latency recording. */
    public final TokenBucket mLatencyTb;
    /** Maximum number of latency values recorded. */
    public final int mMaxLatencyRecords;
    /** Total count of events */
    public int eventCount = 0;
    /** Total count of successful connects. */
    public int connectCount = 0;
    /** Total count of successful connects done in blocking mode. */
    public int connectBlockingCount = 0;
    /** Total count of successful connects with IPv6 socket address. */
    public int ipv6ConnectCount = 0;

    public ConnectStats(int netId, long transports, TokenBucket tb, int maxLatencyRecords) {
        this.netId = netId;
        this.transports = transports;
        mLatencyTb = tb;
        mMaxLatencyRecords = maxLatencyRecords;
    }

    boolean addEvent(int errno, int latencyMs, String ipAddr) {
        eventCount++;
        if (isSuccess(errno)) {
            countConnect(errno, ipAddr);
            countLatency(errno, latencyMs);
            return true;
        } else {
            countError(errno);
            return false;
        }
    }

    private void countConnect(int errno, String ipAddr) {
        connectCount++;
        if (!isNonBlocking(errno)) {
            connectBlockingCount++;
        }
        if (isIPv6(ipAddr)) {
            ipv6ConnectCount++;
        }
    }

    private void countLatency(int errno, int ms) {
        if (isNonBlocking(errno)) {
            // Ignore connect() on non-blocking sockets
            return;
        }
        if (!mLatencyTb.get()) {
            // Rate limited
            return;
        }
        if (latencies.size() >= mMaxLatencyRecords) {
            // Hard limit the total number of latency measurements.
            return;
        }
        latencies.add(ms);
    }

    private void countError(int errno) {
        final int newcount = errnos.get(errno, 0) + 1;
        errnos.put(errno, newcount);
    }

    private static boolean isSuccess(int errno) {
        return (errno == 0) || isNonBlocking(errno);
    }

    static boolean isNonBlocking(int errno) {
        // On non-blocking TCP sockets, connect() immediately returns EINPROGRESS.
        // On non-blocking TCP sockets that are connecting, connect() immediately returns EALREADY.
        return (errno == EINPROGRESS) || (errno == EALREADY);
    }

    private static boolean isIPv6(String ipAddr) {
        return ipAddr.contains(":");
    }

    @Override
    public String toString() {
        StringBuilder builder =
                new StringBuilder("ConnectStats(").append("netId=").append(netId).append(", ");
        for (int t : BitUtils.unpackBits(transports)) {
            builder.append(NetworkCapabilities.transportNameOf(t)).append(", ");
        }
        builder.append(String.format("%d events, ", eventCount));
        builder.append(String.format("%d success, ", connectCount));
        builder.append(String.format("%d blocking, ", connectBlockingCount));
        builder.append(String.format("%d IPv6 dst", ipv6ConnectCount));
        for (int i = 0; i < errnos.size(); i++) {
            String errno = OsConstants.errnoName(errnos.keyAt(i));
            int count = errnos.valueAt(i);
            builder.append(String.format(", %s: %d", errno, count));
        }
        return builder.append(")").toString();
    }
}
