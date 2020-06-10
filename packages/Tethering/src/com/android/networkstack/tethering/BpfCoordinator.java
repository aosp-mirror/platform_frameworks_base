/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkStats.UID_TETHERING;

import android.app.usage.NetworkStatsManager;
import android.net.INetd;
import android.net.MacAddress;
import android.net.NetworkStats;
import android.net.NetworkStats.Entry;
import android.net.TetherOffloadRuleParcel;
import android.net.TetherStatsParcel;
import android.net.netstats.provider.NetworkStatsProvider;
import android.net.util.SharedLog;
import android.net.util.TetheringUtils.ForwardedStats;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.net.Inet6Address;

/**
 *  This coordinator is responsible for providing BPF offload relevant functionality.
 *  - Get tethering stats.
 *
 * @hide
 */
public class BpfCoordinator {
    private static final String TAG = BpfCoordinator.class.getSimpleName();
    @VisibleForTesting
    static final int DEFAULT_PERFORM_POLL_INTERVAL_MS = 5000; // TODO: Make it customizable.

    @VisibleForTesting
    enum StatsType {
        STATS_PER_IFACE,
        STATS_PER_UID,
    }

    @NonNull
    private final Handler mHandler;
    @NonNull
    private final INetd mNetd;
    @NonNull
    private final SharedLog mLog;
    @NonNull
    private final Dependencies mDeps;
    @Nullable
    private final BpfTetherStatsProvider mStatsProvider;
    private boolean mStarted = false;

    // Maps upstream interface index to offloaded traffic statistics.
    // Always contains the latest total bytes/packets, since each upstream was started, received
    // from the BPF maps for each interface.
    private SparseArray<ForwardedStats> mStats = new SparseArray<>();

    // Maps upstream interface index to interface names.
    // Store all interface name since boot. Used for lookup what interface name it is from the
    // tether stats got from netd because netd reports interface index to present an interface.
    // TODO: Remove the unused interface name.
    private SparseArray<String> mInterfaceNames = new SparseArray<>();

    // Runnable that used by scheduling next polling of stats.
    private final Runnable mScheduledPollingTask = () -> {
        updateForwardedStatsFromNetd();
        maybeSchedulePollingStats();
    };

    @VisibleForTesting
    static class Dependencies {
        int getPerformPollInterval() {
            // TODO: Consider make this configurable.
            return DEFAULT_PERFORM_POLL_INTERVAL_MS;
        }
    }

    BpfCoordinator(@NonNull Handler handler, @NonNull INetd netd,
            @NonNull NetworkStatsManager nsm, @NonNull SharedLog log, @NonNull Dependencies deps) {
        mHandler = handler;
        mNetd = netd;
        mLog = log.forSubComponent(TAG);
        BpfTetherStatsProvider provider = new BpfTetherStatsProvider();
        try {
            nsm.registerNetworkStatsProvider(getClass().getSimpleName(), provider);
        } catch (RuntimeException e) {
            // TODO: Perhaps not allow to use BPF offload because the reregistration failure
            // implied that no data limit could be applies on a metered upstream if any.
            Log.wtf(TAG, "Cannot register offload stats provider: " + e);
            provider = null;
        }
        mStatsProvider = provider;
        mDeps = deps;
    }

    /**
     * Start BPF tethering offload stats polling when the first upstream is started.
     * Note that this can be only called on handler thread.
     * TODO: Perhaps check BPF support before starting.
     * TODO: Start the stats polling only if there is any client on the downstream.
     */
    public void start() {
        if (mStarted) return;

        mStarted = true;
        maybeSchedulePollingStats();

        mLog.i("BPF tethering coordinator started");
    }

    /**
     * Stop BPF tethering offload stats polling and cleanup upstream parameters.
     * Note that this can be only called on handler thread.
     */
    public void stop() {
        if (!mStarted) return;

        // Stop scheduled polling tasks and poll the latest stats from BPF maps.
        if (mHandler.hasCallbacks(mScheduledPollingTask)) {
            mHandler.removeCallbacks(mScheduledPollingTask);
        }
        updateForwardedStatsFromNetd();

        mStarted = false;

        mLog.i("BPF tethering coordinator stopped");
    }

    /**
     * Add upstream name to lookup table. The lookup table is used for tether stats interface name
     * lookup because the netd only reports interface index in BPF tether stats but the service
     * expects the interface name in NetworkStats object.
     * Note that this can be only called on handler thread.
     */
    public void addUpstreamNameToLookupTable(int upstreamIfindex, String upstreamIface) {
        if (upstreamIfindex == 0) return;

        // The same interface index to name mapping may be added by different IpServer objects or
        // re-added by reconnection on the same upstream interface. Ignore the duplicate one.
        final String iface = mInterfaceNames.get(upstreamIfindex);
        if (iface == null) {
            mInterfaceNames.put(upstreamIfindex, upstreamIface);
        } else if (iface != upstreamIface) {
            Log.wtf(TAG, "The upstream interface name " + upstreamIface
                    + " is different from the existing interface name "
                    + iface + " for index " + upstreamIfindex);
        }
    }

    /** IPv6 forwarding rule class. */
    public static class Ipv6ForwardingRule {
        public final int upstreamIfindex;
        public final int downstreamIfindex;
        public final Inet6Address address;
        public final MacAddress srcMac;
        public final MacAddress dstMac;

        public Ipv6ForwardingRule(int upstreamIfindex, int downstreamIfIndex, Inet6Address address,
                MacAddress srcMac, MacAddress dstMac) {
            this.upstreamIfindex = upstreamIfindex;
            this.downstreamIfindex = downstreamIfIndex;
            this.address = address;
            this.srcMac = srcMac;
            this.dstMac = dstMac;
        }

        /** Return a new rule object which updates with new upstream index. */
        public Ipv6ForwardingRule onNewUpstream(int newUpstreamIfindex) {
            return new Ipv6ForwardingRule(newUpstreamIfindex, downstreamIfindex, address, srcMac,
                    dstMac);
        }

        /**
         * Don't manipulate TetherOffloadRuleParcel directly because implementing onNewUpstream()
         * would be error-prone due to generated stable AIDL classes not having a copy constructor.
         */
        public TetherOffloadRuleParcel toTetherOffloadRuleParcel() {
            final TetherOffloadRuleParcel parcel = new TetherOffloadRuleParcel();
            parcel.inputInterfaceIndex = upstreamIfindex;
            parcel.outputInterfaceIndex = downstreamIfindex;
            parcel.destination = address.getAddress();
            parcel.prefixLength = 128;
            parcel.srcL2Address = srcMac.toByteArray();
            parcel.dstL2Address = dstMac.toByteArray();
            return parcel;
        }
    }

    /**
     * A BPF tethering stats provider to provide network statistics to the system.
     * Note that this class's data may only be accessed on the handler thread.
     */
    @VisibleForTesting
    class BpfTetherStatsProvider extends NetworkStatsProvider {
        // The offloaded traffic statistics per interface that has not been reported since the
        // last call to pushTetherStats. Only the interfaces that were ever tethering upstreams
        // and has pending tether stats delta are included in this NetworkStats object.
        private NetworkStats mIfaceStats = new NetworkStats(0L, 0);

        // The same stats as above, but counts network stats per uid.
        private NetworkStats mUidStats = new NetworkStats(0L, 0);

        @Override
        public void onRequestStatsUpdate(int token) {
            mHandler.post(() -> pushTetherStats());
        }

        @Override
        public void onSetAlert(long quotaBytes) {
            // no-op
        }

        @Override
        public void onSetLimit(@NonNull String iface, long quotaBytes) {
            // no-op
        }

        @VisibleForTesting
        void pushTetherStats() {
            try {
                // The token is not used for now. See b/153606961.
                notifyStatsUpdated(0 /* token */, mIfaceStats, mUidStats);

                // Clear the accumulated tether stats delta after reported. Note that create a new
                // empty object because NetworkStats#clear is @hide.
                mIfaceStats = new NetworkStats(0L, 0);
                mUidStats = new NetworkStats(0L, 0);
            } catch (RuntimeException e) {
                mLog.e("Cannot report network stats: ", e);
            }
        }

        private void accumulateDiff(@NonNull NetworkStats ifaceDiff,
                @NonNull NetworkStats uidDiff) {
            mIfaceStats = mIfaceStats.add(ifaceDiff);
            mUidStats = mUidStats.add(uidDiff);
        }
    }

    @NonNull
    private NetworkStats buildNetworkStats(@NonNull StatsType type, int ifIndex,
            @NonNull ForwardedStats diff) {
        NetworkStats stats = new NetworkStats(0L, 0);
        final String iface = mInterfaceNames.get(ifIndex);
        if (iface == null) {
            // TODO: Use Log.wtf once the coordinator owns full control of tether stats from netd.
            // For now, netd may add the empty stats for the upstream which is not monitored by
            // the coordinator. Silently ignore it.
            return stats;
        }
        final int uid = (type == StatsType.STATS_PER_UID) ? UID_TETHERING : UID_ALL;
        // Note that the argument 'metered', 'roaming' and 'defaultNetwork' are not recorded for
        // network stats snapshot. See NetworkStatsRecorder#recordSnapshotLocked.
        return stats.addEntry(new Entry(iface, uid, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, diff.rxBytes, diff.rxPackets,
                diff.txBytes, diff.txPackets, 0L /* operations */));
    }

    private void updateForwardedStatsFromNetd() {
        final TetherStatsParcel[] tetherStatsList;
        try {
            // The reported tether stats are total data usage for all currently-active upstream
            // interfaces since tethering start.
            tetherStatsList = mNetd.tetherOffloadGetStats();
        } catch (RemoteException | ServiceSpecificException e) {
            mLog.e("Problem fetching tethering stats: ", e);
            return;
        }

        for (TetherStatsParcel tetherStats : tetherStatsList) {
            final Integer ifIndex = tetherStats.ifIndex;
            final ForwardedStats curr = new ForwardedStats(tetherStats);
            final ForwardedStats base = mStats.get(ifIndex);
            final ForwardedStats diff = (base != null) ? curr.subtract(base) : curr;

            // Update the local cache for counting tether stats delta.
            mStats.put(ifIndex, curr);

            // Update the accumulated tether stats delta to the stats provider for the service
            // querying.
            if (mStatsProvider != null) {
                try {
                    mStatsProvider.accumulateDiff(
                            buildNetworkStats(StatsType.STATS_PER_IFACE, ifIndex, diff),
                            buildNetworkStats(StatsType.STATS_PER_UID, ifIndex, diff));
                } catch (ArrayIndexOutOfBoundsException e) {
                    Log.wtf("Fail to update the accumulated stats delta for interface index "
                            + ifIndex + " : ", e);
                }
            }
        }
    }

    private void maybeSchedulePollingStats() {
        if (!mStarted) return;

        if (mHandler.hasCallbacks(mScheduledPollingTask)) {
            mHandler.removeCallbacks(mScheduledPollingTask);
        }

        mHandler.postDelayed(mScheduledPollingTask, mDeps.getPerformPollInterval());
    }
}
