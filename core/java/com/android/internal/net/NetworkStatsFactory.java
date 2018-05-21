/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.net;

import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.TAG_ALL;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static com.android.server.NetworkManagementSocketTagger.kernelToTag;

import android.annotation.Nullable;
import android.net.NetworkStats;
import android.os.StrictMode;
import android.os.SystemClock;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ProcFileReader;

import libcore.io.IoUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates {@link NetworkStats} instances by parsing various {@code /proc/}
 * files as needed.
 */
public class NetworkStatsFactory {
    private static final String TAG = "NetworkStatsFactory";

    private static final boolean USE_NATIVE_PARSING = true;
    private static final boolean SANITY_CHECK_NATIVE = false;

    /** Path to {@code /proc/net/xt_qtaguid/iface_stat_all}. */
    private final File mStatsXtIfaceAll;
    /** Path to {@code /proc/net/xt_qtaguid/iface_stat_fmt}. */
    private final File mStatsXtIfaceFmt;
    /** Path to {@code /proc/net/xt_qtaguid/stats}. */
    private final File mStatsXtUid;

    private boolean mUseBpfStats;

    // TODO: only do adjustments in NetworkStatsService and remove this.
    /**
     * (Stacked interface) -> (base interface) association for all connected ifaces since boot.
     *
     * Because counters must never roll backwards, once a given interface is stacked on top of an
     * underlying interface, the stacked interface can never be stacked on top of
     * another interface. */
    private static final ConcurrentHashMap<String, String> sStackedIfaces
            = new ConcurrentHashMap<>();

    public static void noteStackedIface(String stackedIface, String baseIface) {
        if (stackedIface != null && baseIface != null) {
            sStackedIfaces.put(stackedIface, baseIface);
        }
    }

    /**
     * Get a set of interfaces containing specified ifaces and stacked interfaces.
     *
     * <p>The added stacked interfaces are ifaces stacked on top of the specified ones, or ifaces
     * on which the specified ones are stacked. Stacked interfaces are those noted with
     * {@link #noteStackedIface(String, String)}, but only interfaces noted before this method
     * is called are guaranteed to be included.
     */
    public static String[] augmentWithStackedInterfaces(@Nullable String[] requiredIfaces) {
        if (requiredIfaces == NetworkStats.INTERFACES_ALL) {
            return null;
        }

        HashSet<String> relatedIfaces = new HashSet<>(Arrays.asList(requiredIfaces));
        // ConcurrentHashMap's EntrySet iterators are "guaranteed to traverse
        // elements as they existed upon construction exactly once, and may
        // (but are not guaranteed to) reflect any modifications subsequent to construction".
        // This is enough here.
        for (Map.Entry<String, String> entry : sStackedIfaces.entrySet()) {
            if (relatedIfaces.contains(entry.getKey())) {
                relatedIfaces.add(entry.getValue());
            } else if (relatedIfaces.contains(entry.getValue())) {
                relatedIfaces.add(entry.getKey());
            }
        }

        String[] outArray = new String[relatedIfaces.size()];
        return relatedIfaces.toArray(outArray);
    }

    /**
     * Applies 464xlat adjustments with ifaces noted with {@link #noteStackedIface(String, String)}.
     * @see NetworkStats#apply464xlatAdjustments(NetworkStats, NetworkStats, Map)
     */
    public static void apply464xlatAdjustments(NetworkStats baseTraffic,
            NetworkStats stackedTraffic) {
        NetworkStats.apply464xlatAdjustments(baseTraffic, stackedTraffic, sStackedIfaces);
    }

    @VisibleForTesting
    public static void clearStackedIfaces() {
        sStackedIfaces.clear();
    }

    public NetworkStatsFactory() {
        this(new File("/proc/"), new File("/sys/fs/bpf/traffic_uid_stats_map").exists());
    }

    @VisibleForTesting
    public NetworkStatsFactory(File procRoot, boolean useBpfStats) {
        mStatsXtIfaceAll = new File(procRoot, "net/xt_qtaguid/iface_stat_all");
        mStatsXtIfaceFmt = new File(procRoot, "net/xt_qtaguid/iface_stat_fmt");
        mStatsXtUid = new File(procRoot, "net/xt_qtaguid/stats");
        mUseBpfStats = useBpfStats;
    }

    public NetworkStats readBpfNetworkStatsDev() throws IOException {
        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 6);
        if (nativeReadNetworkStatsDev(stats) != 0) {
            throw new IOException("Failed to parse bpf iface stats");
        }
        return stats;
    }

    /**
     * Parse and return interface-level summary {@link NetworkStats} measured
     * using {@code /proc/net/dev} style hooks, which may include non IP layer
     * traffic. Values monotonically increase since device boot, and may include
     * details about inactive interfaces.
     *
     * @throws IllegalStateException when problem parsing stats.
     */
    public NetworkStats readNetworkStatsSummaryDev() throws IOException {

        // Return xt_bpf stats if switched to bpf module.
        if (mUseBpfStats)
            return readBpfNetworkStatsDev();

        final StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskReads();

        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 6);
        final NetworkStats.Entry entry = new NetworkStats.Entry();

        ProcFileReader reader = null;
        try {
            reader = new ProcFileReader(new FileInputStream(mStatsXtIfaceAll));

            while (reader.hasMoreData()) {
                entry.iface = reader.nextString();
                entry.uid = UID_ALL;
                entry.set = SET_ALL;
                entry.tag = TAG_NONE;

                final boolean active = reader.nextInt() != 0;

                // always include snapshot values
                entry.rxBytes = reader.nextLong();
                entry.rxPackets = reader.nextLong();
                entry.txBytes = reader.nextLong();
                entry.txPackets = reader.nextLong();

                // fold in active numbers, but only when active
                if (active) {
                    entry.rxBytes += reader.nextLong();
                    entry.rxPackets += reader.nextLong();
                    entry.txBytes += reader.nextLong();
                    entry.txPackets += reader.nextLong();
                }

                stats.addValues(entry);
                reader.finishLine();
            }
        } catch (NullPointerException|NumberFormatException e) {
            throw new ProtocolException("problem parsing stats", e);
        } finally {
            IoUtils.closeQuietly(reader);
            StrictMode.setThreadPolicy(savedPolicy);
        }
        return stats;
    }

    /**
     * Parse and return interface-level summary {@link NetworkStats}. Designed
     * to return only IP layer traffic. Values monotonically increase since
     * device boot, and may include details about inactive interfaces.
     *
     * @throws IllegalStateException when problem parsing stats.
     */
    public NetworkStats readNetworkStatsSummaryXt() throws IOException {

        // Return xt_bpf stats if qtaguid  module is replaced.
        if (mUseBpfStats)
            return readBpfNetworkStatsDev();

        final StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskReads();

        // return null when kernel doesn't support
        if (!mStatsXtIfaceFmt.exists()) return null;

        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 6);
        final NetworkStats.Entry entry = new NetworkStats.Entry();

        ProcFileReader reader = null;
        try {
            // open and consume header line
            reader = new ProcFileReader(new FileInputStream(mStatsXtIfaceFmt));
            reader.finishLine();

            while (reader.hasMoreData()) {
                entry.iface = reader.nextString();
                entry.uid = UID_ALL;
                entry.set = SET_ALL;
                entry.tag = TAG_NONE;

                entry.rxBytes = reader.nextLong();
                entry.rxPackets = reader.nextLong();
                entry.txBytes = reader.nextLong();
                entry.txPackets = reader.nextLong();

                stats.addValues(entry);
                reader.finishLine();
            }
        } catch (NullPointerException|NumberFormatException e) {
            throw new ProtocolException("problem parsing stats", e);
        } finally {
            IoUtils.closeQuietly(reader);
            StrictMode.setThreadPolicy(savedPolicy);
        }
        return stats;
    }

    public NetworkStats readNetworkStatsDetail() throws IOException {
        return readNetworkStatsDetail(UID_ALL, null, TAG_ALL, null);
    }

    public NetworkStats readNetworkStatsDetail(int limitUid, String[] limitIfaces, int limitTag,
            NetworkStats lastStats) throws IOException {
        final NetworkStats stats =
              readNetworkStatsDetailInternal(limitUid, limitIfaces, limitTag, lastStats);

        // No locking here: apply464xlatAdjustments behaves fine with an add-only ConcurrentHashMap.
        // TODO: remove this and only apply adjustments in NetworkStatsService.
        stats.apply464xlatAdjustments(sStackedIfaces);

        return stats;
    }

    private NetworkStats readNetworkStatsDetailInternal(int limitUid, String[] limitIfaces,
            int limitTag, NetworkStats lastStats) throws IOException {
        if (USE_NATIVE_PARSING) {
            final NetworkStats stats;
            if (lastStats != null) {
                stats = lastStats;
                stats.setElapsedRealtime(SystemClock.elapsedRealtime());
            } else {
                stats = new NetworkStats(SystemClock.elapsedRealtime(), -1);
            }
            if (nativeReadNetworkStatsDetail(stats, mStatsXtUid.getAbsolutePath(), limitUid,
                    limitIfaces, limitTag, mUseBpfStats) != 0) {
                throw new IOException("Failed to parse network stats");
            }
            if (SANITY_CHECK_NATIVE) {
                final NetworkStats javaStats = javaReadNetworkStatsDetail(mStatsXtUid, limitUid,
                        limitIfaces, limitTag);
                assertEquals(javaStats, stats);
            }
            return stats;
        } else {
            return javaReadNetworkStatsDetail(mStatsXtUid, limitUid, limitIfaces, limitTag);
        }
    }

    /**
     * Parse and return {@link NetworkStats} with UID-level details. Values are
     * expected to monotonically increase since device boot.
     */
    @VisibleForTesting
    public static NetworkStats javaReadNetworkStatsDetail(File detailPath, int limitUid,
            String[] limitIfaces, int limitTag)
            throws IOException {
        final StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskReads();

        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 24);
        final NetworkStats.Entry entry = new NetworkStats.Entry();

        int idx = 1;
        int lastIdx = 1;

        ProcFileReader reader = null;
        try {
            // open and consume header line
            reader = new ProcFileReader(new FileInputStream(detailPath));
            reader.finishLine();

            while (reader.hasMoreData()) {
                idx = reader.nextInt();
                if (idx != lastIdx + 1) {
                    throw new ProtocolException(
                            "inconsistent idx=" + idx + " after lastIdx=" + lastIdx);
                }
                lastIdx = idx;

                entry.iface = reader.nextString();
                entry.tag = kernelToTag(reader.nextString());
                entry.uid = reader.nextInt();
                entry.set = reader.nextInt();
                entry.rxBytes = reader.nextLong();
                entry.rxPackets = reader.nextLong();
                entry.txBytes = reader.nextLong();
                entry.txPackets = reader.nextLong();

                if ((limitIfaces == null || ArrayUtils.contains(limitIfaces, entry.iface))
                        && (limitUid == UID_ALL || limitUid == entry.uid)
                        && (limitTag == TAG_ALL || limitTag == entry.tag)) {
                    stats.addValues(entry);
                }

                reader.finishLine();
            }
        } catch (NullPointerException|NumberFormatException e) {
            throw new ProtocolException("problem parsing idx " + idx, e);
        } finally {
            IoUtils.closeQuietly(reader);
            StrictMode.setThreadPolicy(savedPolicy);
        }

        return stats;
    }

    public void assertEquals(NetworkStats expected, NetworkStats actual) {
        if (expected.size() != actual.size()) {
            throw new AssertionError(
                    "Expected size " + expected.size() + ", actual size " + actual.size());
        }

        NetworkStats.Entry expectedRow = null;
        NetworkStats.Entry actualRow = null;
        for (int i = 0; i < expected.size(); i++) {
            expectedRow = expected.getValues(i, expectedRow);
            actualRow = actual.getValues(i, actualRow);
            if (!expectedRow.equals(actualRow)) {
                throw new AssertionError(
                        "Expected row " + i + ": " + expectedRow + ", actual row " + actualRow);
            }
        }
    }

    /**
     * Parse statistics from file into given {@link NetworkStats} object. Values
     * are expected to monotonically increase since device boot.
     */
    @VisibleForTesting
    public static native int nativeReadNetworkStatsDetail(NetworkStats stats, String path,
        int limitUid, String[] limitIfaces, int limitTag, boolean useBpfStats);

    @VisibleForTesting
    public static native int nativeReadNetworkStatsDev(NetworkStats stats);
}
