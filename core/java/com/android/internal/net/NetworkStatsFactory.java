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

import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static com.android.server.NetworkManagementSocketTagger.kernelToTag;

import android.net.NetworkStats;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.util.ProcFileReader;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;
import com.google.android.collect.Sets;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.StringTokenizer;

import libcore.io.IoUtils;

/**
 * Creates {@link NetworkStats} instances by parsing various {@code /proc/}
 * files as needed.
 */
public class NetworkStatsFactory {
    private static final String TAG = "NetworkStatsFactory";

    // TODO: consider moving parsing to native code

    /** Path to {@code /proc/net/dev}. */
    @Deprecated
    private final File mStatsIface;
    /** Path to {@code /proc/net/xt_qtaguid/iface_stat}. */
    @Deprecated
    private final File mStatsXtIface;
    /** Path to {@code /proc/net/xt_qtaguid/iface_stat_all}. */
    private final File mStatsXtIfaceAll;
    /** Path to {@code /proc/net/xt_qtaguid/stats}. */
    private final File mStatsXtUid;

    /** {@link #mStatsXtUid} and {@link #mStatsXtIfaceAll} headers. */
    private static final String KEY_IDX = "idx";
    private static final String KEY_IFACE = "iface";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_UID = "uid_tag_int";
    private static final String KEY_COUNTER_SET = "cnt_set";
    private static final String KEY_TAG_HEX = "acct_tag_hex";
    private static final String KEY_SNAP_RX_BYTES = "snap_rx_bytes";
    private static final String KEY_SNAP_RX_PACKETS = "snap_rx_packets";
    private static final String KEY_SNAP_TX_BYTES = "snap_tx_bytes";
    private static final String KEY_SNAP_TX_PACKETS = "snap_tx_packets";
    private static final String KEY_RX_BYTES = "rx_bytes";
    private static final String KEY_RX_PACKETS = "rx_packets";
    private static final String KEY_TX_BYTES = "tx_bytes";
    private static final String KEY_TX_PACKETS = "tx_packets";

    public NetworkStatsFactory() {
        this(new File("/proc/"));
    }

    // @VisibleForTesting
    public NetworkStatsFactory(File procRoot) {
        mStatsIface = new File(procRoot, "net/dev");
        mStatsXtUid = new File(procRoot, "net/xt_qtaguid/stats");
        mStatsXtIface = new File(procRoot, "net/xt_qtaguid/iface_stat");
        mStatsXtIfaceAll = new File(procRoot, "net/xt_qtaguid/iface_stat_all");
    }

    /**
     * Parse and return interface-level summary {@link NetworkStats}. Values
     * monotonically increase since device boot, and may include details about
     * inactive interfaces.
     *
     * @throws IllegalStateException when problem parsing stats.
     */
    public NetworkStats readNetworkStatsSummary() throws IllegalStateException {
        if (mStatsXtIfaceAll.exists()) {
            return readNetworkStatsSummarySingleFile();
        } else {
            return readNetworkStatsSummaryMultipleFiles();
        }
    }

    private NetworkStats readNetworkStatsSummarySingleFile() {
        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 6);
        final NetworkStats.Entry entry = new NetworkStats.Entry();

        // TODO: transition to ProcFileReader
        // TODO: read directly from proc once headers are added
        final ArrayList<String> keys = Lists.newArrayList(KEY_IFACE, KEY_ACTIVE, KEY_SNAP_RX_BYTES,
                KEY_SNAP_RX_PACKETS, KEY_SNAP_TX_BYTES, KEY_SNAP_TX_PACKETS, KEY_RX_BYTES,
                KEY_RX_PACKETS, KEY_TX_BYTES, KEY_TX_PACKETS);
        final ArrayList<String> values = Lists.newArrayList();
        final HashMap<String, String> parsed = Maps.newHashMap();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(mStatsXtIfaceAll));

            String line;
            while ((line = reader.readLine()) != null) {
                splitLine(line, values);
                parseLine(keys, values, parsed);

                entry.iface = parsed.get(KEY_IFACE);
                entry.uid = UID_ALL;
                entry.set = SET_DEFAULT;
                entry.tag = TAG_NONE;

                // always include snapshot values
                entry.rxBytes = getParsedLong(parsed, KEY_SNAP_RX_BYTES);
                entry.rxPackets = getParsedLong(parsed, KEY_SNAP_RX_PACKETS);
                entry.txBytes = getParsedLong(parsed, KEY_SNAP_TX_BYTES);
                entry.txPackets = getParsedLong(parsed, KEY_SNAP_TX_PACKETS);

                // fold in active numbers, but only when active
                final boolean active = getParsedInt(parsed, KEY_ACTIVE) != 0;
                if (active) {
                    entry.rxBytes += getParsedLong(parsed, KEY_RX_BYTES);
                    entry.rxPackets += getParsedLong(parsed, KEY_RX_PACKETS);
                    entry.txBytes += getParsedLong(parsed, KEY_TX_BYTES);
                    entry.txPackets += getParsedLong(parsed, KEY_TX_PACKETS);
                }

                stats.addValues(entry);
            }
        } catch (NullPointerException e) {
            throw new IllegalStateException("problem parsing stats: " + e);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("problem parsing stats: " + e);
        } catch (IOException e) {
            throw new IllegalStateException("problem parsing stats: " + e);
        } finally {
            IoUtils.closeQuietly(reader);
        }
        return stats;
    }

    /**
     * @deprecated remove once {@code iface_stat_all} is merged to all kernels.
     */
    @Deprecated
    private NetworkStats readNetworkStatsSummaryMultipleFiles() {
        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 6);
        final NetworkStats.Entry entry = new NetworkStats.Entry();

        final HashSet<String> knownIfaces = Sets.newHashSet();
        final HashSet<String> activeIfaces = Sets.newHashSet();

        // collect any historical stats and active state
        for (String iface : fileListWithoutNull(mStatsXtIface)) {
            final File ifacePath = new File(mStatsXtIface, iface);

            final long active = readSingleLongFromFile(new File(ifacePath, "active"));
            if (active == 1) {
                knownIfaces.add(iface);
                activeIfaces.add(iface);
            } else if (active == 0) {
                knownIfaces.add(iface);
            } else {
                continue;
            }

            entry.iface = iface;
            entry.uid = UID_ALL;
            entry.set = SET_DEFAULT;
            entry.tag = TAG_NONE;
            entry.rxBytes = readSingleLongFromFile(new File(ifacePath, "rx_bytes"));
            entry.rxPackets = readSingleLongFromFile(new File(ifacePath, "rx_packets"));
            entry.txBytes = readSingleLongFromFile(new File(ifacePath, "tx_bytes"));
            entry.txPackets = readSingleLongFromFile(new File(ifacePath, "tx_packets"));

            stats.addValues(entry);
        }

        final ArrayList<String> values = Lists.newArrayList();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(mStatsIface));

            // skip first two header lines
            reader.readLine();
            reader.readLine();

            // parse remaining lines
            String line;
            while ((line = reader.readLine()) != null) {
                splitLine(line, values);

                try {
                    entry.iface = values.get(0);
                    entry.uid = UID_ALL;
                    entry.set = SET_DEFAULT;
                    entry.tag = TAG_NONE;
                    entry.rxBytes = Long.parseLong(values.get(1));
                    entry.rxPackets = Long.parseLong(values.get(2));
                    entry.txBytes = Long.parseLong(values.get(9));
                    entry.txPackets = Long.parseLong(values.get(10));

                    if (activeIfaces.contains(entry.iface)) {
                        // combine stats when iface is active
                        stats.combineValues(entry);
                    } else if (!knownIfaces.contains(entry.iface)) {
                        // add stats when iface is unknown
                        stats.addValues(entry);
                    }
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "problem parsing stats row '" + line + "': " + e);
                }
            }
        } catch (NullPointerException e) {
            throw new IllegalStateException("problem parsing stats: " + e);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("problem parsing stats: " + e);
        } catch (IOException e) {
            throw new IllegalStateException("problem parsing stats: " + e);
        } finally {
            IoUtils.closeQuietly(reader);
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
        }

        return stats;
    }

    @Deprecated
    private static int getParsedInt(HashMap<String, String> parsed, String key) {
        final String value = parsed.get(key);
        return value != null ? Integer.parseInt(value) : 0;
    }

    @Deprecated
    private static long getParsedLong(HashMap<String, String> parsed, String key) {
        final String value = parsed.get(key);
        return value != null ? Long.parseLong(value) : 0;
    }

    /**
     * Split given line into {@link ArrayList}.
     */
    @Deprecated
    private static void splitLine(String line, ArrayList<String> outSplit) {
        outSplit.clear();

        final StringTokenizer t = new StringTokenizer(line, " \t\n\r\f:");
        while (t.hasMoreTokens()) {
            outSplit.add(t.nextToken());
        }
    }

    /**
     * Zip the two given {@link ArrayList} as key and value pairs into
     * {@link HashMap}.
     */
    @Deprecated
    private static void parseLine(
            ArrayList<String> keys, ArrayList<String> values, HashMap<String, String> outParsed) {
        outParsed.clear();

        final int size = Math.min(keys.size(), values.size());
        for (int i = 0; i < size; i++) {
            outParsed.put(keys.get(i), values.get(i));
        }
    }

    /**
     * Utility method to read a single plain-text {@link Long} from the given
     * {@link File}, usually from a {@code /proc/} filesystem.
     */
    private static long readSingleLongFromFile(File file) {
        try {
            final byte[] buffer = IoUtils.readFileAsByteArray(file.toString());
            return Long.parseLong(new String(buffer).trim());
        } catch (NumberFormatException e) {
            return -1;
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Wrapper for {@link File#list()} that returns empty array instead of
     * {@code null}.
     */
    private static String[] fileListWithoutNull(File file) {
        final String[] list = file.list();
        return list != null ? list : new String[0];
    }
}
