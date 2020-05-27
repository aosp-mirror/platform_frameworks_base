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

package com.android.server.net;

import static android.net.NetworkStats.INTERFACES_ALL;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.TAG_ALL;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;

import static com.android.server.NetworkManagementSocketTagger.kernelToTag;

import android.annotation.Nullable;
import android.net.INetd;
import android.net.NetworkStats;
import android.net.util.NetdService;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemClock;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.net.VpnInfo;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ProcFileReader;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates {@link NetworkStats} instances by parsing various {@code /proc/}
 * files as needed.
 *
 * @hide
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

    private final boolean mUseBpfStats;

    private INetd mNetdService;

    /**
     * Guards persistent data access in this class
     *
     * <p>In order to prevent deadlocks, critical sections protected by this lock SHALL NOT call out
     * to other code that will acquire other locks within the system server. See b/134244752.
     */
    private final Object mPersistentDataLock = new Object();

    /** Set containing info about active VPNs and their underlying networks. */
    private volatile VpnInfo[] mVpnInfos = new VpnInfo[0];

    // A persistent snapshot of cumulative stats since device start
    @GuardedBy("mPersistentDataLock")
    private NetworkStats mPersistSnapshot;

    // The persistent snapshot of tun and 464xlat adjusted stats since device start
    @GuardedBy("mPersistentDataLock")
    private NetworkStats mTunAnd464xlatAdjustedStats;

    /**
     * (Stacked interface) -> (base interface) association for all connected ifaces since boot.
     *
     * Because counters must never roll backwards, once a given interface is stacked on top of an
     * underlying interface, the stacked interface can never be stacked on top of
     * another interface. */
    private final ConcurrentHashMap<String, String> mStackedIfaces
            = new ConcurrentHashMap<>();

    /** Informs the factory of a new stacked interface. */
    public void noteStackedIface(String stackedIface, String baseIface) {
        if (stackedIface != null && baseIface != null) {
            mStackedIfaces.put(stackedIface, baseIface);
        }
    }

    /**
     * Set active VPN information for data usage migration purposes
     *
     * <p>Traffic on TUN-based VPNs inherently all appear to be originated from the VPN providing
     * app's UID. This method is used to support migration of VPN data usage, ensuring data is
     * accurately billed to the real owner of the traffic.
     *
     * @param vpnArray The snapshot of the currently-running VPNs.
     */
    public void updateVpnInfos(VpnInfo[] vpnArray) {
        mVpnInfos = vpnArray.clone();
    }

    /**
     * Get a set of interfaces containing specified ifaces and stacked interfaces.
     *
     * <p>The added stacked interfaces are ifaces stacked on top of the specified ones, or ifaces
     * on which the specified ones are stacked. Stacked interfaces are those noted with
     * {@link #noteStackedIface(String, String)}, but only interfaces noted before this method
     * is called are guaranteed to be included.
     */
    public String[] augmentWithStackedInterfaces(@Nullable String[] requiredIfaces) {
        if (requiredIfaces == NetworkStats.INTERFACES_ALL) {
            return null;
        }

        HashSet<String> relatedIfaces = new HashSet<>(Arrays.asList(requiredIfaces));
        // ConcurrentHashMap's EntrySet iterators are "guaranteed to traverse
        // elements as they existed upon construction exactly once, and may
        // (but are not guaranteed to) reflect any modifications subsequent to construction".
        // This is enough here.
        for (Map.Entry<String, String> entry : mStackedIfaces.entrySet()) {
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
     * @see NetworkStats#apply464xlatAdjustments(NetworkStats, NetworkStats, Map, boolean)
     */
    public void apply464xlatAdjustments(NetworkStats baseTraffic,
            NetworkStats stackedTraffic, boolean useBpfStats) {
        NetworkStats.apply464xlatAdjustments(baseTraffic, stackedTraffic, mStackedIfaces,
                useBpfStats);
    }

    public NetworkStatsFactory() {
        this(new File("/proc/"), new File("/sys/fs/bpf/map_netd_app_uid_stats_map").exists());
    }

    @VisibleForTesting
    public NetworkStatsFactory(File procRoot, boolean useBpfStats) {
        mStatsXtIfaceAll = new File(procRoot, "net/xt_qtaguid/iface_stat_all");
        mStatsXtIfaceFmt = new File(procRoot, "net/xt_qtaguid/iface_stat_fmt");
        mStatsXtUid = new File(procRoot, "net/xt_qtaguid/stats");
        mUseBpfStats = useBpfStats;
        synchronized (mPersistentDataLock) {
            mPersistSnapshot = new NetworkStats(SystemClock.elapsedRealtime(), -1);
            mTunAnd464xlatAdjustedStats = new NetworkStats(SystemClock.elapsedRealtime(), -1);
        }
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

                stats.insertEntry(entry);
                reader.finishLine();
            }
        } catch (NullPointerException|NumberFormatException e) {
            throw protocolExceptionWithCause("problem parsing stats", e);
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

                stats.insertEntry(entry);
                reader.finishLine();
            }
        } catch (NullPointerException|NumberFormatException e) {
            throw protocolExceptionWithCause("problem parsing stats", e);
        } finally {
            IoUtils.closeQuietly(reader);
            StrictMode.setThreadPolicy(savedPolicy);
        }
        return stats;
    }

    public NetworkStats readNetworkStatsDetail() throws IOException {
        return readNetworkStatsDetail(UID_ALL, INTERFACES_ALL, TAG_ALL);
    }

    @GuardedBy("mPersistentDataLock")
    private void requestSwapActiveStatsMapLocked() throws RemoteException {
        // Ask netd to do a active map stats swap. When the binder call successfully returns,
        // the system server should be able to safely read and clean the inactive map
        // without race problem.
        if (mNetdService == null) {
            mNetdService = NetdService.getInstance();
        }
        mNetdService.trafficSwapActiveStatsMap();
    }

    /**
     * Reads the detailed UID stats based on the provided parameters
     *
     * @param limitUid the UID to limit this query to
     * @param limitIfaces the interfaces to limit this query to. Use {@link
     *     NetworkStats.INTERFACES_ALL} to select all interfaces
     * @param limitTag the tags to limit this query to
     * @return the NetworkStats instance containing network statistics at the present time.
     */
    public NetworkStats readNetworkStatsDetail(
            int limitUid, String[] limitIfaces, int limitTag) throws IOException {
        // In order to prevent deadlocks, anything protected by this lock MUST NOT call out to other
        // code that will acquire other locks within the system server. See b/134244752.
        synchronized (mPersistentDataLock) {
            // Take a reference. If this gets swapped out, we still have the old reference.
            final VpnInfo[] vpnArray = mVpnInfos;
            // Take a defensive copy. mPersistSnapshot is mutated in some cases below
            final NetworkStats prev = mPersistSnapshot.clone();

            if (USE_NATIVE_PARSING) {
                final NetworkStats stats =
                        new NetworkStats(SystemClock.elapsedRealtime(), 0 /* initialSize */);
                if (mUseBpfStats) {
                    try {
                        requestSwapActiveStatsMapLocked();
                    } catch (RemoteException e) {
                        throw new IOException(e);
                    }
                    // Stats are always read from the inactive map, so they must be read after the
                    // swap
                    if (nativeReadNetworkStatsDetail(stats, mStatsXtUid.getAbsolutePath(), UID_ALL,
                            INTERFACES_ALL, TAG_ALL, mUseBpfStats) != 0) {
                        throw new IOException("Failed to parse network stats");
                    }

                    // BPF stats are incremental; fold into mPersistSnapshot.
                    mPersistSnapshot.setElapsedRealtime(stats.getElapsedRealtime());
                    mPersistSnapshot.combineAllValues(stats);
                } else {
                    if (nativeReadNetworkStatsDetail(stats, mStatsXtUid.getAbsolutePath(), UID_ALL,
                            INTERFACES_ALL, TAG_ALL, mUseBpfStats) != 0) {
                        throw new IOException("Failed to parse network stats");
                    }
                    if (SANITY_CHECK_NATIVE) {
                        final NetworkStats javaStats = javaReadNetworkStatsDetail(mStatsXtUid,
                                UID_ALL, INTERFACES_ALL, TAG_ALL);
                        assertEquals(javaStats, stats);
                    }

                    mPersistSnapshot = stats;
                }
            } else {
                mPersistSnapshot = javaReadNetworkStatsDetail(mStatsXtUid, UID_ALL, INTERFACES_ALL,
                        TAG_ALL);
            }

            NetworkStats adjustedStats = adjustForTunAnd464Xlat(mPersistSnapshot, prev, vpnArray);

            // Filter return values
            adjustedStats.filter(limitUid, limitIfaces, limitTag);
            return adjustedStats;
        }
    }

    @GuardedBy("mPersistentDataLock")
    private NetworkStats adjustForTunAnd464Xlat(
            NetworkStats uidDetailStats, NetworkStats previousStats, VpnInfo[] vpnArray) {
        // Calculate delta from last snapshot
        final NetworkStats delta = uidDetailStats.subtract(previousStats);

        // Apply 464xlat adjustments before VPN adjustments. If VPNs are using v4 on a v6 only
        // network, the overhead is their fault.
        // No locking here: apply464xlatAdjustments behaves fine with an add-only
        // ConcurrentHashMap.
        delta.apply464xlatAdjustments(mStackedIfaces, mUseBpfStats);

        // Migrate data usage over a VPN to the TUN network.
        for (VpnInfo info : vpnArray) {
            delta.migrateTun(info.ownerUid, info.vpnIface, info.underlyingIfaces);
            // Filter out debug entries as that may lead to over counting.
            delta.filterDebugEntries();
        }

        // Update mTunAnd464xlatAdjustedStats with migrated delta.
        mTunAnd464xlatAdjustedStats.combineAllValues(delta);
        mTunAnd464xlatAdjustedStats.setElapsedRealtime(uidDetailStats.getElapsedRealtime());

        return mTunAnd464xlatAdjustedStats.clone();
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
                    stats.insertEntry(entry);
                }

                reader.finishLine();
            }
        } catch (NullPointerException|NumberFormatException e) {
            throw protocolExceptionWithCause("problem parsing idx " + idx, e);
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

    private static ProtocolException protocolExceptionWithCause(String message, Throwable cause) {
        ProtocolException pe = new ProtocolException(message);
        pe.initCause(cause);
        return pe;
    }
}
