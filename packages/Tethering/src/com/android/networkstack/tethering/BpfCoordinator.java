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
import static android.net.netstats.provider.NetworkStatsProvider.QUOTA_UNLIMITED;

import static com.android.networkstack.tethering.TetheringConfiguration.DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS;

import android.app.usage.NetworkStatsManager;
import android.net.INetd;
import android.net.MacAddress;
import android.net.NetworkStats;
import android.net.NetworkStats.Entry;
import android.net.TetherOffloadRuleParcel;
import android.net.TetherStatsParcel;
import android.net.ip.IpServer;
import android.net.netstats.provider.NetworkStatsProvider;
import android.net.util.SharedLog;
import android.net.util.TetheringUtils.ForwardedStats;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 *  This coordinator is responsible for providing BPF offload relevant functionality.
 *  - Get tethering stats.
 *  - Set data limit.
 *  - Set global alert.
 *  - Add/remove forwarding rules.
 *
 * @hide
 */
public class BpfCoordinator {
    private static final String TAG = BpfCoordinator.class.getSimpleName();
    private static final int DUMP_TIMEOUT_MS = 10_000;

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

    // True if BPF offload is supported, false otherwise. The BPF offload could be disabled by
    // a runtime resource overlay package or device configuration. This flag is only initialized
    // in the constructor because it is hard to unwind all existing change once device
    // configuration is changed. Especially the forwarding rules. Keep the same setting
    // to make it simpler. See also TetheringConfiguration.
    private final boolean mIsBpfEnabled;

    // Tracks whether BPF tethering is started or not. This is set by tethering before it
    // starts the first IpServer and is cleared by tethering shortly before the last IpServer
    // is stopped. Note that rule updates (especially deletions, but sometimes additions as
    // well) may arrive when this is false. If they do, they must be communicated to netd.
    // Changes in data limits may also arrive when this is false, and if they do, they must
    // also be communicated to netd.
    private boolean mPollingStarted = false;

    // Tracking remaining alert quota. Unlike limit quota is subject to interface, the alert
    // quota is interface independent and global for tether offload.
    private long mRemainingAlertQuota = QUOTA_UNLIMITED;

    // Maps upstream interface index to offloaded traffic statistics.
    // Always contains the latest total bytes/packets, since each upstream was started, received
    // from the BPF maps for each interface.
    private final SparseArray<ForwardedStats> mStats = new SparseArray<>();

    // Maps upstream interface names to interface quotas.
    // Always contains the latest value received from the framework for each interface, regardless
    // of whether offload is currently running (or is even supported) on that interface. Only
    // includes interfaces that have a quota set. Note that this map is used for storing the quota
    // which is set from the service. Because the service uses the interface name to present the
    // interface, this map uses the interface name to be the mapping index.
    private final HashMap<String, Long> mInterfaceQuotas = new HashMap<>();

    // Maps upstream interface index to interface names.
    // Store all interface name since boot. Used for lookup what interface name it is from the
    // tether stats got from netd because netd reports interface index to present an interface.
    // TODO: Remove the unused interface name.
    private final SparseArray<String> mInterfaceNames = new SparseArray<>();

    // Map of downstream rule maps. Each of these maps represents the IPv6 forwarding rules for a
    // given downstream. Each map:
    // - Is owned by the IpServer that is responsible for that downstream.
    // - Must only be modified by that IpServer.
    // - Is created when the IpServer adds its first rule, and deleted when the IpServer deletes
    //   its last rule (or clears its rules).
    // TODO: Perhaps seal the map and rule operations which communicates with netd into a class.
    // TODO: Does this need to be a LinkedHashMap or can it just be a HashMap? Also, could it be
    // a ConcurrentHashMap, in order to avoid the copies in tetherOffloadRuleClear
    // and tetherOffloadRuleUpdate?
    // TODO: Perhaps use one-dimensional map and access specific downstream rules via downstream
    // index. For doing that, IpServer must guarantee that it always has a valid IPv6 downstream
    // interface index while calling function to clear all rules. IpServer may be calling clear
    // rules function without a valid IPv6 downstream interface index even if it may have one
    // before. IpServer would need to call getInterfaceParams() in the constructor instead of when
    // startIpv6() is called, and make mInterfaceParams final.
    private final HashMap<IpServer, LinkedHashMap<Inet6Address, Ipv6ForwardingRule>>
            mIpv6ForwardingRules = new LinkedHashMap<>();

    // Runnable that used by scheduling next polling of stats.
    private final Runnable mScheduledPollingTask = () -> {
        updateForwardedStatsFromNetd();
        maybeSchedulePollingStats();
    };

    @VisibleForTesting
    public abstract static class Dependencies {
        /** Get handler. */
        @NonNull public abstract Handler getHandler();

        /** Get netd. */
        @NonNull public abstract INetd getNetd();

        /** Get network stats manager. */
        @NonNull public abstract NetworkStatsManager getNetworkStatsManager();

        /** Get shared log. */
        @NonNull public abstract SharedLog getSharedLog();

        /** Get tethering configuration. */
        @Nullable public abstract TetheringConfiguration getTetherConfig();
    }

    @VisibleForTesting
    public BpfCoordinator(@NonNull Dependencies deps) {
        mDeps = deps;
        mHandler = mDeps.getHandler();
        mNetd = mDeps.getNetd();
        mLog = mDeps.getSharedLog().forSubComponent(TAG);
        mIsBpfEnabled = isBpfEnabled();
        BpfTetherStatsProvider provider = new BpfTetherStatsProvider();
        try {
            mDeps.getNetworkStatsManager().registerNetworkStatsProvider(
                    getClass().getSimpleName(), provider);
        } catch (RuntimeException e) {
            // TODO: Perhaps not allow to use BPF offload because the reregistration failure
            // implied that no data limit could be applies on a metered upstream if any.
            Log.wtf(TAG, "Cannot register offload stats provider: " + e);
            provider = null;
        }
        mStatsProvider = provider;
    }

    /**
     * Start BPF tethering offload stats polling when the first upstream is started.
     * Note that this can be only called on handler thread.
     * TODO: Perhaps check BPF support before starting.
     * TODO: Start the stats polling only if there is any client on the downstream.
     */
    public void startPolling() {
        if (mPollingStarted) return;

        if (!mIsBpfEnabled) {
            mLog.i("Offload disabled");
            return;
        }

        mPollingStarted = true;
        maybeSchedulePollingStats();

        mLog.i("Polling started");
    }

    /**
     * Stop BPF tethering offload stats polling.
     * The data limit cleanup and the tether stats maps cleanup are not implemented here.
     * These cleanups rely on all IpServers calling #tetherOffloadRuleRemove. After the
     * last rule is removed from the upstream, #tetherOffloadRuleRemove does the cleanup
     * functionality.
     * Note that this can be only called on handler thread.
     */
    public void stopPolling() {
        if (!mPollingStarted) return;

        // Stop scheduled polling tasks and poll the latest stats from BPF maps.
        if (mHandler.hasCallbacks(mScheduledPollingTask)) {
            mHandler.removeCallbacks(mScheduledPollingTask);
        }
        updateForwardedStatsFromNetd();
        mPollingStarted = false;

        mLog.i("Polling stopped");
    }

    /**
     * Add forwarding rule. After adding the first rule on a given upstream, must add the data
     * limit on the given upstream.
     * Note that this can be only called on handler thread.
     */
    public void tetherOffloadRuleAdd(
            @NonNull final IpServer ipServer, @NonNull final Ipv6ForwardingRule rule) {
        if (!mIsBpfEnabled) return;

        try {
            // TODO: Perhaps avoid to add a duplicate rule.
            mNetd.tetherOffloadRuleAdd(rule.toTetherOffloadRuleParcel());
        } catch (RemoteException | ServiceSpecificException e) {
            mLog.e("Could not add IPv6 forwarding rule: ", e);
            return;
        }

        if (!mIpv6ForwardingRules.containsKey(ipServer)) {
            mIpv6ForwardingRules.put(ipServer, new LinkedHashMap<Inet6Address,
                    Ipv6ForwardingRule>());
        }
        LinkedHashMap<Inet6Address, Ipv6ForwardingRule> rules = mIpv6ForwardingRules.get(ipServer);

        // Setup the data limit on the given upstream if the first rule is added.
        final int upstreamIfindex = rule.upstreamIfindex;
        if (!isAnyRuleOnUpstream(upstreamIfindex)) {
            // If failed to set a data limit, probably should not use this upstream, because
            // the upstream may not want to blow through the data limit that was told to apply.
            // TODO: Perhaps stop the coordinator.
            boolean success = updateDataLimit(upstreamIfindex);
            if (!success) {
                final String iface = mInterfaceNames.get(upstreamIfindex);
                mLog.e("Setting data limit for " + iface + " failed.");
            }
        }

        // Must update the adding rule after calling #isAnyRuleOnUpstream because it needs to
        // check if it is about adding a first rule for a given upstream.
        rules.put(rule.address, rule);
    }

    /**
     * Remove forwarding rule. After removing the last rule on a given upstream, must clear
     * data limit, update the last tether stats and remove the tether stats in the BPF maps.
     * Note that this can be only called on handler thread.
     */
    public void tetherOffloadRuleRemove(
            @NonNull final IpServer ipServer, @NonNull final Ipv6ForwardingRule rule) {
        if (!mIsBpfEnabled) return;

        try {
            // TODO: Perhaps avoid to remove a non-existent rule.
            mNetd.tetherOffloadRuleRemove(rule.toTetherOffloadRuleParcel());
        } catch (RemoteException | ServiceSpecificException e) {
            mLog.e("Could not remove IPv6 forwarding rule: ", e);
            return;
        }

        LinkedHashMap<Inet6Address, Ipv6ForwardingRule> rules = mIpv6ForwardingRules.get(ipServer);
        if (rules == null) return;

        // Must remove rules before calling #isAnyRuleOnUpstream because it needs to check if
        // the last rule is removed for a given upstream. If no rule is removed, return early.
        // Avoid unnecessary work on a non-existent rule which may have never been added or
        // removed already.
        if (rules.remove(rule.address) == null) return;

        // Remove the downstream entry if it has no more rule.
        if (rules.isEmpty()) {
            mIpv6ForwardingRules.remove(ipServer);
        }

        // Do cleanup functionality if there is no more rule on the given upstream.
        final int upstreamIfindex = rule.upstreamIfindex;
        if (!isAnyRuleOnUpstream(upstreamIfindex)) {
            try {
                final TetherStatsParcel stats =
                        mNetd.tetherOffloadGetAndClearStats(upstreamIfindex);
                // Update the last stats delta and delete the local cache for a given upstream.
                updateQuotaAndStatsFromSnapshot(new TetherStatsParcel[] {stats});
                mStats.remove(upstreamIfindex);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.wtf(TAG, "Exception when cleanup tether stats for upstream index "
                        + upstreamIfindex + ": ", e);
            }
        }
    }

    /**
     * Clear all forwarding rules for a given downstream.
     * Note that this can be only called on handler thread.
     */
    public void tetherOffloadRuleClear(@NonNull final IpServer ipServer) {
        if (!mIsBpfEnabled) return;

        final LinkedHashMap<Inet6Address, Ipv6ForwardingRule> rules = mIpv6ForwardingRules.get(
                ipServer);
        if (rules == null) return;

        // Need to build a rule list because the rule map may be changed in the iteration.
        for (final Ipv6ForwardingRule rule : new ArrayList<Ipv6ForwardingRule>(rules.values())) {
            tetherOffloadRuleRemove(ipServer, rule);
        }
    }

    /**
     * Update existing forwarding rules to new upstream for a given downstream.
     * Note that this can be only called on handler thread.
     */
    public void tetherOffloadRuleUpdate(@NonNull final IpServer ipServer, int newUpstreamIfindex) {
        if (!mIsBpfEnabled) return;

        final LinkedHashMap<Inet6Address, Ipv6ForwardingRule> rules = mIpv6ForwardingRules.get(
                ipServer);
        if (rules == null) return;

        // Need to build a rule list because the rule map may be changed in the iteration.
        for (final Ipv6ForwardingRule rule : new ArrayList<Ipv6ForwardingRule>(rules.values())) {
            // Remove the old rule before adding the new one because the map uses the same key for
            // both rules. Reversing the processing order causes that the new rule is removed as
            // unexpected.
            // TODO: Add new rule first to reduce the latency which has no rule.
            tetherOffloadRuleRemove(ipServer, rule);
            tetherOffloadRuleAdd(ipServer, rule.onNewUpstream(newUpstreamIfindex));
        }
    }

    /**
     * Add upstream name to lookup table. The lookup table is used for tether stats interface name
     * lookup because the netd only reports interface index in BPF tether stats but the service
     * expects the interface name in NetworkStats object.
     * Note that this can be only called on handler thread.
     */
    public void addUpstreamNameToLookupTable(int upstreamIfindex, @NonNull String upstreamIface) {
        if (!mIsBpfEnabled) return;

        if (upstreamIfindex == 0 || TextUtils.isEmpty(upstreamIface)) return;

        // The same interface index to name mapping may be added by different IpServer objects or
        // re-added by reconnection on the same upstream interface. Ignore the duplicate one.
        final String iface = mInterfaceNames.get(upstreamIfindex);
        if (iface == null) {
            mInterfaceNames.put(upstreamIfindex, upstreamIface);
        } else if (!TextUtils.equals(iface, upstreamIface)) {
            Log.wtf(TAG, "The upstream interface name " + upstreamIface
                    + " is different from the existing interface name "
                    + iface + " for index " + upstreamIfindex);
        }
    }

    /**
     * Dump information.
     * Block the function until all the data are dumped on the handler thread or timed-out. The
     * reason is that dumpsys invokes this function on the thread of caller and the data may only
     * be allowed to be accessed on the handler thread.
     */
    public void dump(@NonNull IndentingPrintWriter pw) {
        final ConditionVariable dumpDone = new ConditionVariable();
        mHandler.post(() -> {
            pw.println("mIsBpfEnabled: " + mIsBpfEnabled);
            pw.println("Polling " + (mPollingStarted ? "started" : "not started"));
            pw.println("Stats provider " + (mStatsProvider != null
                    ? "registered" : "not registered"));
            pw.println("Upstream quota: " + mInterfaceQuotas.toString());
            pw.println("Polling interval: " + getPollingInterval() + " ms");

            pw.println("Forwarding stats:");
            pw.increaseIndent();
            if (mStats.size() == 0) {
                pw.println("<empty>");
            } else {
                dumpStats(pw);
            }
            pw.decreaseIndent();

            pw.println("Forwarding rules:");
            pw.increaseIndent();
            if (mIpv6ForwardingRules.size() == 0) {
                pw.println("<empty>");
            } else {
                dumpIpv6ForwardingRules(pw);
            }
            pw.decreaseIndent();

            dumpDone.open();
        });
        if (!dumpDone.block(DUMP_TIMEOUT_MS)) {
            pw.println("... dump timed-out after " + DUMP_TIMEOUT_MS + "ms");
        }
    }

    private void dumpStats(@NonNull IndentingPrintWriter pw) {
        for (int i = 0; i < mStats.size(); i++) {
            final int upstreamIfindex = mStats.keyAt(i);
            final ForwardedStats stats = mStats.get(upstreamIfindex);
            pw.println(String.format("%d(%s) - %s", upstreamIfindex, mInterfaceNames.get(
                    upstreamIfindex), stats.toString()));
        }
    }

    private void dumpIpv6ForwardingRules(@NonNull IndentingPrintWriter pw) {
        for (Map.Entry<IpServer, LinkedHashMap<Inet6Address, Ipv6ForwardingRule>> entry :
                mIpv6ForwardingRules.entrySet()) {
            IpServer ipServer = entry.getKey();
            // The rule downstream interface index is paired with the interface name from
            // IpServer#interfaceName. See #startIPv6, #updateIpv6ForwardingRules in IpServer.
            final String downstreamIface = ipServer.interfaceName();
            pw.println("[" + downstreamIface + "]: iif(iface) oif(iface) v6addr srcmac dstmac");

            pw.increaseIndent();
            LinkedHashMap<Inet6Address, Ipv6ForwardingRule> rules = entry.getValue();
            for (Ipv6ForwardingRule rule : rules.values()) {
                final int upstreamIfindex = rule.upstreamIfindex;
                pw.println(String.format("%d(%s) %d(%s) %s %s %s", upstreamIfindex,
                        mInterfaceNames.get(upstreamIfindex), rule.downstreamIfindex,
                        downstreamIface, rule.address, rule.srcMac, rule.dstMac));
            }
            pw.decreaseIndent();
        }
    }

    /** IPv6 forwarding rule class. */
    public static class Ipv6ForwardingRule {
        public final int upstreamIfindex;
        public final int downstreamIfindex;

        @NonNull
        public final Inet6Address address;
        @NonNull
        public final MacAddress srcMac;
        @NonNull
        public final MacAddress dstMac;

        public Ipv6ForwardingRule(int upstreamIfindex, int downstreamIfIndex,
                @NonNull Inet6Address address, @NonNull MacAddress srcMac,
                @NonNull MacAddress dstMac) {
            this.upstreamIfindex = upstreamIfindex;
            this.downstreamIfindex = downstreamIfIndex;
            this.address = address;
            this.srcMac = srcMac;
            this.dstMac = dstMac;
        }

        /** Return a new rule object which updates with new upstream index. */
        @NonNull
        public Ipv6ForwardingRule onNewUpstream(int newUpstreamIfindex) {
            return new Ipv6ForwardingRule(newUpstreamIfindex, downstreamIfindex, address, srcMac,
                    dstMac);
        }

        /**
         * Don't manipulate TetherOffloadRuleParcel directly because implementing onNewUpstream()
         * would be error-prone due to generated stable AIDL classes not having a copy constructor.
         */
        @NonNull
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

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Ipv6ForwardingRule)) return false;
            Ipv6ForwardingRule that = (Ipv6ForwardingRule) o;
            return this.upstreamIfindex == that.upstreamIfindex
                    && this.downstreamIfindex == that.downstreamIfindex
                    && Objects.equals(this.address, that.address)
                    && Objects.equals(this.srcMac, that.srcMac)
                    && Objects.equals(this.dstMac, that.dstMac);
        }

        @Override
        public int hashCode() {
            // TODO: if this is ever used in production code, don't pass ifindices
            // to Objects.hash() to avoid autoboxing overhead.
            return Objects.hash(upstreamIfindex, downstreamIfindex, address, srcMac, dstMac);
        }
    }

    /**
     * A BPF tethering stats provider to provide network statistics to the system.
     * Note that this class' data may only be accessed on the handler thread.
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
            mHandler.post(() -> updateAlertQuota(quotaBytes));
        }

        @Override
        public void onSetLimit(@NonNull String iface, long quotaBytes) {
            if (quotaBytes < QUOTA_UNLIMITED) {
                throw new IllegalArgumentException("invalid quota value " + quotaBytes);
            }

            mHandler.post(() -> {
                final Long curIfaceQuota = mInterfaceQuotas.get(iface);

                if (null == curIfaceQuota && QUOTA_UNLIMITED == quotaBytes) return;

                if (quotaBytes == QUOTA_UNLIMITED) {
                    mInterfaceQuotas.remove(iface);
                } else {
                    mInterfaceQuotas.put(iface, quotaBytes);
                }
                maybeUpdateDataLimit(iface);
            });
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

    private boolean isBpfEnabled() {
        final TetheringConfiguration config = mDeps.getTetherConfig();
        return (config != null) ? config.isBpfOffloadEnabled() : true /* default value */;
    }

    private int getInterfaceIndexFromRules(@NonNull String ifName) {
        for (LinkedHashMap<Inet6Address, Ipv6ForwardingRule> rules : mIpv6ForwardingRules
                .values()) {
            for (Ipv6ForwardingRule rule : rules.values()) {
                final int upstreamIfindex = rule.upstreamIfindex;
                if (TextUtils.equals(ifName, mInterfaceNames.get(upstreamIfindex))) {
                    return upstreamIfindex;
                }
            }
        }
        return 0;
    }

    private long getQuotaBytes(@NonNull String iface) {
        final Long limit = mInterfaceQuotas.get(iface);
        final long quotaBytes = (limit != null) ? limit : QUOTA_UNLIMITED;

        return quotaBytes;
    }

    private boolean sendDataLimitToNetd(int ifIndex, long quotaBytes) {
        if (ifIndex == 0) {
            Log.wtf(TAG, "Invalid interface index.");
            return false;
        }

        try {
            mNetd.tetherOffloadSetInterfaceQuota(ifIndex, quotaBytes);
        } catch (RemoteException | ServiceSpecificException e) {
            mLog.e("Exception when updating quota " + quotaBytes + ": ", e);
            return false;
        }

        return true;
    }

    // Handle the data limit update from the service which is the stats provider registered for.
    private void maybeUpdateDataLimit(@NonNull String iface) {
        // Set data limit only on a given upstream which has at least one rule. If we can't get
        // an interface index for a given interface name, it means either there is no rule for
        // a given upstream or the interface name is not an upstream which is monitored by the
        // coordinator.
        final int ifIndex = getInterfaceIndexFromRules(iface);
        if (ifIndex == 0) return;

        final long quotaBytes = getQuotaBytes(iface);
        sendDataLimitToNetd(ifIndex, quotaBytes);
    }

    // Handle the data limit update while adding forwarding rules.
    private boolean updateDataLimit(int ifIndex) {
        final String iface = mInterfaceNames.get(ifIndex);
        if (iface == null) {
            mLog.e("Fail to get the interface name for index " + ifIndex);
            return false;
        }
        final long quotaBytes = getQuotaBytes(iface);
        return sendDataLimitToNetd(ifIndex, quotaBytes);
    }

    private boolean isAnyRuleOnUpstream(int upstreamIfindex) {
        for (LinkedHashMap<Inet6Address, Ipv6ForwardingRule> rules : mIpv6ForwardingRules
                .values()) {
            for (Ipv6ForwardingRule rule : rules.values()) {
                if (upstreamIfindex == rule.upstreamIfindex) return true;
            }
        }
        return false;
    }

    @NonNull
    private NetworkStats buildNetworkStats(@NonNull StatsType type, int ifIndex,
            @NonNull final ForwardedStats diff) {
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

    private void updateAlertQuota(long newQuota) {
        if (newQuota < QUOTA_UNLIMITED) {
            throw new IllegalArgumentException("invalid quota value " + newQuota);
        }
        if (mRemainingAlertQuota == newQuota) return;

        mRemainingAlertQuota = newQuota;
        if (mRemainingAlertQuota == 0) {
            mLog.i("onAlertReached");
            if (mStatsProvider != null) mStatsProvider.notifyAlertReached();
        }
    }

    private void updateQuotaAndStatsFromSnapshot(
            @NonNull final TetherStatsParcel[] tetherStatsList) {
        long usedAlertQuota = 0;
        for (TetherStatsParcel tetherStats : tetherStatsList) {
            final Integer ifIndex = tetherStats.ifIndex;
            final ForwardedStats curr = new ForwardedStats(tetherStats);
            final ForwardedStats base = mStats.get(ifIndex);
            final ForwardedStats diff = (base != null) ? curr.subtract(base) : curr;
            usedAlertQuota += diff.rxBytes + diff.txBytes;

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
                    Log.wtf(TAG, "Fail to update the accumulated stats delta for interface index "
                            + ifIndex + " : ", e);
                }
            }
        }

        if (mRemainingAlertQuota > 0 && usedAlertQuota > 0) {
            // Trim to zero if overshoot.
            final long newQuota = Math.max(mRemainingAlertQuota - usedAlertQuota, 0);
            updateAlertQuota(newQuota);
        }

        // TODO: Count the used limit quota for notifying data limit reached.
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
        updateQuotaAndStatsFromSnapshot(tetherStatsList);
    }

    @VisibleForTesting
    int getPollingInterval() {
        // The valid range of interval is DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS..max_long.
        // Ignore the config value is less than the minimum polling interval. Note that the
        // minimum interval definition is invoked as OffloadController#isPollingStatsNeeded does.
        // TODO: Perhaps define a minimum polling interval constant.
        final TetheringConfiguration config = mDeps.getTetherConfig();
        final int configInterval = (config != null) ? config.getOffloadPollInterval() : 0;
        return Math.max(DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS, configInterval);
    }

    private void maybeSchedulePollingStats() {
        if (!mPollingStarted) return;

        if (mHandler.hasCallbacks(mScheduledPollingTask)) {
            mHandler.removeCallbacks(mScheduledPollingTask);
        }

        mHandler.postDelayed(mScheduledPollingTask, getPollingInterval());
    }

    // Return forwarding rule map. This is used for testing only.
    // Note that this can be only called on handler thread.
    @NonNull
    @VisibleForTesting
    final HashMap<IpServer, LinkedHashMap<Inet6Address, Ipv6ForwardingRule>>
            getForwardingRulesForTesting() {
        return mIpv6ForwardingRules;
    }

    // Return upstream interface name map. This is used for testing only.
    // Note that this can be only called on handler thread.
    @NonNull
    @VisibleForTesting
    final SparseArray<String> getInterfaceNamesForTesting() {
        return mInterfaceNames;
    }
}
