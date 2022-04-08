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

package android.net.metrics;

import android.net.NetworkCapabilities;

import com.android.internal.util.BitUtils;
import com.android.internal.util.TokenBucket;

import java.util.StringJoiner;

/**
 * A class accumulating network metrics received from Netd regarding dns queries and
 * connect() calls on a given network.
 *
 * This class also accumulates running sums of dns and connect latency stats and
 * error counts for bug report logging.
 *
 * @hide
 */
public class NetworkMetrics {

    private static final int INITIAL_DNS_BATCH_SIZE = 100;
    private static final int CONNECT_LATENCY_MAXIMUM_RECORDS = 20000;

    // The network id of the Android Network.
    public final int netId;
    // The transport types bitmap of the Android Network, as defined in NetworkCapabilities.java.
    public final long transports;
    // Accumulated metrics for connect events.
    public final ConnectStats connectMetrics;
    // Accumulated metrics for dns events.
    public final DnsEvent dnsMetrics;
    // Running sums of latencies and error counts for connect and dns events.
    public final Summary summary;
    // Running sums of the most recent latencies and error counts for connect and dns events.
    // Starts null until some events are accumulated.
    // Allows to collect periodic snapshot of the running summaries for a given network.
    public Summary pendingSummary;

    public NetworkMetrics(int netId, long transports, TokenBucket tb) {
        this.netId = netId;
        this.transports = transports;
        this.connectMetrics =
                new ConnectStats(netId, transports, tb, CONNECT_LATENCY_MAXIMUM_RECORDS);
        this.dnsMetrics = new DnsEvent(netId, transports, INITIAL_DNS_BATCH_SIZE);
        this.summary = new Summary(netId, transports);
    }

    /**
     * Get currently pending Summary statistics, if any, for this NetworkMetrics, merge them
     * into the long running Summary statistics of this NetworkMetrics, and also clear them.
     */
    public Summary getPendingStats() {
        Summary s = pendingSummary;
        pendingSummary = null;
        if (s != null) {
            summary.merge(s);
        }
        return s;
    }

    /** Accumulate a dns query result reported by netd. */
    public void addDnsResult(int eventType, int returnCode, int latencyMs) {
        if (pendingSummary == null) {
            pendingSummary = new Summary(netId, transports);
        }
        boolean isSuccess = dnsMetrics.addResult((byte) eventType, (byte) returnCode, latencyMs);
        pendingSummary.dnsLatencies.count(latencyMs);
        pendingSummary.dnsErrorRate.count(isSuccess ? 0 : 1);
    }

    /** Accumulate a connect query result reported by netd. */
    public void addConnectResult(int error, int latencyMs, String ipAddr) {
        if (pendingSummary == null) {
            pendingSummary = new Summary(netId, transports);
        }
        boolean isSuccess = connectMetrics.addEvent(error, latencyMs, ipAddr);
        pendingSummary.connectErrorRate.count(isSuccess ? 0 : 1);
        if (ConnectStats.isNonBlocking(error)) {
            pendingSummary.connectLatencies.count(latencyMs);
        }
    }

    /** Accumulate a single netd sock_diag poll result reported by netd. */
    public void addTcpStatsResult(int sent, int lost, int rttUs, int sentAckDiffMs) {
        if (pendingSummary == null) {
            pendingSummary = new Summary(netId, transports);
        }
        pendingSummary.tcpLossRate.count(lost, sent);
        pendingSummary.roundTripTimeUs.count(rttUs);
        pendingSummary.sentAckTimeDiffenceMs.count(sentAckDiffMs);
    }

    /** Represents running sums for dns and connect average error counts and average latencies. */
    public static class Summary {

        public final int netId;
        public final long transports;
        // DNS latencies measured in milliseconds.
        public final Metrics dnsLatencies = new Metrics();
        // DNS error rate measured in percentage points.
        public final Metrics dnsErrorRate = new Metrics();
        // Blocking connect latencies measured in milliseconds.
        public final Metrics connectLatencies = new Metrics();
        // Blocking and non blocking connect error rate measured in percentage points.
        public final Metrics connectErrorRate = new Metrics();
        // TCP socket packet loss stats collected from Netlink sock_diag.
        public final Metrics tcpLossRate = new Metrics();
        // TCP averaged microsecond round-trip-time stats collected from Netlink sock_diag.
        public final Metrics roundTripTimeUs = new Metrics();
        // TCP stats collected from Netlink sock_diag that averages millisecond per-socket
        // differences between last packet sent timestamp and last ack received timestamp.
        public final Metrics sentAckTimeDiffenceMs = new Metrics();

        public Summary(int netId, long transports) {
            this.netId = netId;
            this.transports = transports;
        }

        void merge(Summary that) {
            dnsLatencies.merge(that.dnsLatencies);
            dnsErrorRate.merge(that.dnsErrorRate);
            connectLatencies.merge(that.connectLatencies);
            connectErrorRate.merge(that.connectErrorRate);
            tcpLossRate.merge(that.tcpLossRate);
        }

        @Override
        public String toString() {
            StringJoiner j = new StringJoiner(", ", "{", "}");
            j.add("netId=" + netId);
            for (int t : BitUtils.unpackBits(transports)) {
                j.add(NetworkCapabilities.transportNameOf(t));
            }
            j.add(String.format("dns avg=%dms max=%dms err=%.1f%% tot=%d",
                    (int) dnsLatencies.average(), (int) dnsLatencies.max,
                    100 * dnsErrorRate.average(), dnsErrorRate.count));
            j.add(String.format("connect avg=%dms max=%dms err=%.1f%% tot=%d",
                    (int) connectLatencies.average(), (int) connectLatencies.max,
                    100 * connectErrorRate.average(), connectErrorRate.count));
            j.add(String.format("tcp avg_loss=%.1f%% total_sent=%d total_lost=%d",
                    100 * tcpLossRate.average(), tcpLossRate.count, (int) tcpLossRate.sum));
            j.add(String.format("tcp rtt=%dms", (int) (roundTripTimeUs.average() / 1000)));
            j.add(String.format("tcp sent-ack_diff=%dms", (int) sentAckTimeDiffenceMs.average()));
            return j.toString();
        }
    }

    /** Tracks a running sum and returns the average of a metric. */
    static class Metrics {
        public double sum;
        public double max = Double.MIN_VALUE;
        public int count;

        void merge(Metrics that) {
            this.count += that.count;
            this.sum += that.sum;
            this.max = Math.max(this.max, that.max);
        }

        void count(double value) {
            count(value, 1);
        }

        void count(double value, int subcount) {
            count += subcount;
            sum += value;
            max = Math.max(max, value);
        }

        double average() {
            double a = sum / (double) count;
            if (Double.isNaN(a)) {
                a = 0;
            }
            return a;
        }
    }
}
