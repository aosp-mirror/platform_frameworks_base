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

import android.net.NetworkStats;
import android.os.StrictMode;
import android.os.SystemClock;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ProcFileReader;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Objects;

/**
 * Creates {@link NetworkStats} instances by parsing various {@code /proc/}
 * files as needed.
 */
public class NetworkStatsFactory {
    private static final String TAG = "NetworkStatsFactory";

    private static final boolean USE_NATIVE_PARSING = true;
    private static final boolean SANITY_CHECK_NATIVE = false;

    private static final String CLATD_INTERFACE_PREFIX = "v4-";
    // Delta between IPv4 header (20b) and IPv6 header (40b).
    // Used for correct stats accounting on clatd interfaces.
    private static final int IPV4V6_HEADER_DELTA = 20;

    /** Path to {@code /proc/net/xt_qtaguid/iface_stat_all}. */
    private final File mStatsXtIfaceAll;
    /** Path to {@code /proc/net/xt_qtaguid/iface_stat_fmt}. */
    private final File mStatsXtIfaceFmt;
    /** Path to {@code /proc/net/xt_qtaguid/stats}. */
    private final File mStatsXtUid;

    // TODO: to improve testability and avoid global state, do not use a static variable.
    @GuardedBy("sStackedIfaces")
    private static final ArrayMap<String, String> sStackedIfaces = new ArrayMap<>();

    public static void noteStackedIface(String stackedIface, String baseIface) {
        synchronized (sStackedIfaces) {
            if (baseIface != null) {
                sStackedIfaces.put(stackedIface, baseIface);
            } else {
                sStackedIfaces.remove(stackedIface);
            }
        }
    }

    public NetworkStatsFactory() {
        this(new File("/proc/"));
    }

    @VisibleForTesting
    public NetworkStatsFactory(File procRoot) {
        mStatsXtIfaceAll = new File(procRoot, "net/xt_qtaguid/iface_stat_all");
        mStatsXtIfaceFmt = new File(procRoot, "net/xt_qtaguid/iface_stat_fmt");
        mStatsXtUid = new File(procRoot, "net/xt_qtaguid/stats");
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
        final ArrayMap<String, String> stackedIfaces;
        synchronized (sStackedIfaces) {
            stackedIfaces = new ArrayMap<>(sStackedIfaces);
        }
        // Total 464xlat traffic to subtract from uid 0 on all base interfaces.
        final NetworkStats adjustments = new NetworkStats(0, stackedIfaces.size());

        NetworkStats.Entry entry = null; // For recycling

        // For 464xlat traffic, xt_qtaguid sees every IPv4 packet twice, once as a native IPv4
        // packet on the stacked interface, and once as translated to an IPv6 packet on the
        // base interface. For correct stats accounting on the base interface, every 464xlat
        // packet needs to be subtracted from the root UID on the base interface both for tx
        // and rx traffic (http://b/12249687, http:/b/33681750).
        for (int i = 0; i < stats.size(); i++) {
            entry = stats.getValues(i, entry);
            if (entry.iface == null || !entry.iface.startsWith(CLATD_INTERFACE_PREFIX)) {
                continue;
            }
            final String baseIface = stackedIfaces.get(entry.iface);
            if (baseIface == null) {
                continue;
            }

            NetworkStats.Entry adjust =
                    new NetworkStats.Entry(baseIface, 0, 0, 0, 0L, 0L, 0L, 0L, 0L);
            // Subtract any 464lat traffic seen for the root UID on the current base interface.
            adjust.rxBytes -= (entry.rxBytes + entry.rxPackets * IPV4V6_HEADER_DELTA);
            adjust.txBytes -= (entry.txBytes + entry.txPackets * IPV4V6_HEADER_DELTA);
            adjust.rxPackets -= entry.rxPackets;
            adjust.txPackets -= entry.txPackets;
            adjustments.combineValues(adjust);

            // For 464xlat traffic, xt_qtaguid only counts the bytes of the native IPv4 packet sent
            // on the stacked interface with prefix "v4-" and drops the IPv6 header size after
            // unwrapping. To account correctly for on-the-wire traffic, add the 20 additional bytes
            // difference for all packets (http://b/12249687, http:/b/33681750).
            entry.rxBytes = entry.rxPackets * IPV4V6_HEADER_DELTA;
            entry.txBytes = entry.txPackets * IPV4V6_HEADER_DELTA;
            entry.rxPackets = 0;
            entry.txPackets = 0;
            stats.combineValues(entry);
        }

        stats.combineAllValues(adjustments);

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
                    limitIfaces, limitTag) != 0) {
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
    public static native int nativeReadNetworkStatsDetail(
            NetworkStats stats, String path, int limitUid, String[] limitIfaces, int limitTag);
}
