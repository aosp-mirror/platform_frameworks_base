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

import android.system.OsConstants;
import android.util.IntArray;
import android.util.SparseIntArray;
import com.android.internal.util.TokenBucket;
import com.android.server.connectivity.metrics.IpConnectivityLogClass.ConnectStatistics;
import com.android.server.connectivity.metrics.IpConnectivityLogClass.Pair;

/**
 * A class that aggregates connect() statistics and helps build
 * IpConnectivityLogClass.ConnectStatistics instances.
 *
 * {@hide}
 */
public class ConnectStats {
    private final static int EALREADY     = OsConstants.EALREADY;
    private final static int EINPROGRESS  = OsConstants.EINPROGRESS;

    /** How many events resulted in a given errno. */
    private final SparseIntArray mErrnos = new SparseIntArray();
    /** Latencies of blocking connects. TODO: add non-blocking connects latencies. */
    private final IntArray mLatencies = new IntArray();
    /** TokenBucket for rate limiting latency recording. */
    private final TokenBucket mLatencyTb;
    /** Maximum number of latency values recorded. */
    private final int mMaxLatencyRecords;
    /** Total count of successful connects. */
    private int mConnectCount = 0;
    /** Total count of successful connects with IPv6 socket address. */
    private int mIpv6ConnectCount = 0;

    public ConnectStats(TokenBucket tb, int maxLatencyRecords) {
        mLatencyTb = tb;
        mMaxLatencyRecords = maxLatencyRecords;
    }

    public ConnectStatistics toProto() {
        ConnectStatistics stats = new ConnectStatistics();
        stats.connectCount = mConnectCount;
        stats.ipv6AddrCount = mIpv6ConnectCount;
        stats.latenciesMs = mLatencies.toArray();
        stats.errnosCounters = toPairArrays(mErrnos);
        return stats;
    }

    public void addEvent(int errno, int latencyMs, String ipAddr) {
        if (isSuccess(errno)) {
            countConnect(ipAddr);
            countLatency(errno, latencyMs);
        } else {
            countError(errno);
        }
    }

    private void countConnect(String ipAddr) {
        mConnectCount++;
        if (isIPv6(ipAddr)) mIpv6ConnectCount++;
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
        if (mLatencies.size() >= mMaxLatencyRecords) {
            // Hard limit the total number of latency measurements.
            return;
        }
        mLatencies.add(ms);
    }

    private void countError(int errno) {
        final int newcount = mErrnos.get(errno, 0) + 1;
        mErrnos.put(errno, newcount);
    }

    private static boolean isSuccess(int errno) {
        return (errno == 0) || isNonBlocking(errno);
    }

    private static boolean isNonBlocking(int errno) {
        // On non-blocking TCP sockets, connect() immediately returns EINPROGRESS.
        // On non-blocking TCP sockets that are connecting, connect() immediately returns EALREADY.
        return (errno == EINPROGRESS) || (errno == EALREADY);
    }

    private static boolean isIPv6(String ipAddr) {
        return ipAddr.contains(":");
    }

    private static Pair[] toPairArrays(SparseIntArray counts) {
        final int s = counts.size();
        Pair[] pairs = new Pair[s];
        for (int i = 0; i < s; i++) {
            Pair p = new Pair();
            p.key = counts.keyAt(i);
            p.value = counts.valueAt(i);
            pairs[i] = p;
        }
        return pairs;
    }
}
