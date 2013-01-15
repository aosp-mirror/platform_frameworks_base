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
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static com.android.server.NetworkManagementSocketTagger.kernelToTag;

import android.net.NetworkStats;
import android.os.StrictMode;
import android.os.SystemClock;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ProcFileReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ProtocolException;

import libcore.io.IoUtils;

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
        } catch (NullPointerException e) {
            throw new ProtocolException("problem parsing stats", e);
        } catch (NumberFormatException e) {
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
        } catch (NullPointerException e) {
            throw new ProtocolException("problem parsing stats", e);
        } catch (NumberFormatException e) {
            throw new ProtocolException("problem parsing stats", e);
        } finally {
            IoUtils.closeQuietly(reader);
            StrictMode.setThreadPolicy(savedPolicy);
        }
        return stats;
    }

    public NetworkStats readNetworkStatsDetail() throws IOException {
        return readNetworkStatsDetail(UID_ALL);
    }

    public NetworkStats readNetworkStatsDetail(int limitUid) throws IOException {
        if (USE_NATIVE_PARSING) {
            final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 0);
            if (nativeReadNetworkStatsDetail(stats, mStatsXtUid.getAbsolutePath(), limitUid) != 0) {
                throw new IOException("Failed to parse network stats");
            }
            if (SANITY_CHECK_NATIVE) {
                final NetworkStats javaStats = javaReadNetworkStatsDetail(mStatsXtUid, limitUid);
                assertEquals(javaStats, stats);
            }
            return stats;
        } else {
            return javaReadNetworkStatsDetail(mStatsXtUid, limitUid);
        }
    }

    /**
     * Parse and return {@link NetworkStats} with UID-level details. Values are
     * expected to monotonically increase since device boot.
     */
    @VisibleForTesting
    public static NetworkStats javaReadNetworkStatsDetail(File detailPath, int limitUid)
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

                if (limitUid == UID_ALL || limitUid == entry.uid) {
                    stats.addValues(entry);
                }

                reader.finishLine();
            }
        } catch (NullPointerException e) {
            throw new ProtocolException("problem parsing idx " + idx, e);
        } catch (NumberFormatException e) {
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
            NetworkStats stats, String path, int limitUid);
}
