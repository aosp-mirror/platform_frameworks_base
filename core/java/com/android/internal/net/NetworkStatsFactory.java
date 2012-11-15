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

import libcore.io.IoUtils;

/**
 * Creates {@link NetworkStats} instances by parsing various {@code /proc/}
 * files as needed.
 */
public class NetworkStatsFactory {
    private static final String TAG = "NetworkStatsFactory";

    // TODO: consider moving parsing to native code

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
    public NetworkStats readNetworkStatsSummaryDev() throws IllegalStateException {
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
            throw new IllegalStateException("problem parsing stats: " + e);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("problem parsing stats: " + e);
        } catch (IOException e) {
            throw new IllegalStateException("problem parsing stats: " + e);
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
    public NetworkStats readNetworkStatsSummaryXt() throws IllegalStateException {
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
            throw new IllegalStateException("problem parsing stats: " + e);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("problem parsing stats: " + e);
        } catch (IOException e) {
            throw new IllegalStateException("problem parsing stats: " + e);
        } finally {
            IoUtils.closeQuietly(reader);
            StrictMode.setThreadPolicy(savedPolicy);
        }
        return stats;
    }

    public NetworkStats readNetworkStatsDetail() {
        return readNetworkStatsDetail(UID_ALL);
    }

    /**
     * Parse and return {@link NetworkStats} with UID-level details. Values
     * monotonically increase since device boot.
     *
     * @throws IllegalStateException when problem parsing stats.
     */
    public NetworkStats readNetworkStatsDetail(int limitUid) throws IllegalStateException {
        final StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskReads();

        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 24);
        final NetworkStats.Entry entry = new NetworkStats.Entry();

        int idx = 1;
        int lastIdx = 1;

        ProcFileReader reader = null;
        try {
            // open and consume header line
            reader = new ProcFileReader(new FileInputStream(mStatsXtUid));
            reader.finishLine();

            while (reader.hasMoreData()) {
                idx = reader.nextInt();
                if (idx != lastIdx + 1) {
                    throw new IllegalStateException(
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
            throw new IllegalStateException("problem parsing idx " + idx, e);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("problem parsing idx " + idx, e);
        } catch (IOException e) {
            throw new IllegalStateException("problem parsing idx " + idx, e);
        } finally {
            IoUtils.closeQuietly(reader);
            StrictMode.setThreadPolicy(savedPolicy);
        }

        return stats;
    }
}
