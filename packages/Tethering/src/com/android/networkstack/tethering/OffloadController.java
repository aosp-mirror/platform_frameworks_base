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

package com.android.networkstack.tethering;

import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkStats.UID_TETHERING;
import static android.net.netstats.provider.NetworkStatsProvider.QUOTA_UNLIMITED;
import static android.provider.Settings.Global.TETHER_OFFLOAD_DISABLED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.usage.NetworkStatsManager;
import android.content.ContentResolver;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkStats;
import android.net.NetworkStats.Entry;
import android.net.RouteInfo;
import android.net.netlink.ConntrackMessage;
import android.net.netlink.NetlinkConstants;
import android.net.netlink.NetlinkSocket;
import android.net.netstats.provider.NetworkStatsProvider;
import android.net.util.SharedLog;
import android.os.Handler;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.networkstack.tethering.OffloadHardwareInterface.ForwardedStats;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A class to encapsulate the business logic of programming the tethering
 * hardware offload interface.
 *
 * @hide
 */
public class OffloadController {
    private static final String TAG = OffloadController.class.getSimpleName();
    private static final boolean DBG = false;
    private static final String ANYIP = "0.0.0.0";
    private static final ForwardedStats EMPTY_STATS = new ForwardedStats();
    private static final int DEFAULT_PERFORM_POLL_INTERVAL_MS = 5000;

    @VisibleForTesting
    enum StatsType {
        STATS_PER_IFACE,
        STATS_PER_UID,
    }

    private enum UpdateType { IF_NEEDED, FORCE };

    private final Handler mHandler;
    private final OffloadHardwareInterface mHwInterface;
    private final ContentResolver mContentResolver;
    @Nullable
    private final OffloadTetheringStatsProvider mStatsProvider;
    private final SharedLog mLog;
    private final HashMap<String, LinkProperties> mDownstreams;
    private boolean mConfigInitialized;
    private boolean mControlInitialized;
    private LinkProperties mUpstreamLinkProperties;
    // The complete set of offload-exempt prefixes passed in via Tethering from
    // all upstream and downstream sources.
    private Set<IpPrefix> mExemptPrefixes;
    // A strictly "smaller" set of prefixes, wherein offload-approved prefixes
    // (e.g. downstream on-link prefixes) have been removed and replaced with
    // prefixes representing only the locally-assigned IP addresses.
    private Set<String> mLastLocalPrefixStrs;

    // Maps upstream interface names to offloaded traffic statistics.
    // Always contains the latest value received from the hardware for each interface, regardless of
    // whether offload is currently running on that interface.
    private ConcurrentHashMap<String, ForwardedStats> mForwardedStats =
            new ConcurrentHashMap<>(16, 0.75F, 1);

    // Maps upstream interface names to interface quotas.
    // Always contains the latest value received from the framework for each interface, regardless
    // of whether offload is currently running (or is even supported) on that interface. Only
    // includes upstream interfaces that have a quota set.
    private HashMap<String, Long> mInterfaceQuotas = new HashMap<>();

    // Tracking remaining alert quota. Unlike limit quota is subject to interface, the alert
    // quota is interface independent and global for tether offload. Note that this is only
    // accessed on the handler thread and in the constructor.
    private long mRemainingAlertQuota = QUOTA_UNLIMITED;
    // Runnable that used to schedule the next stats poll.
    private final Runnable mScheduledPollingTask = () -> {
        updateStatsForCurrentUpstream();
        maybeSchedulePollingStats();
    };

    private int mNatUpdateCallbacksReceived;
    private int mNatUpdateNetlinkErrors;

    @NonNull
    private final Dependencies mDeps;

    // TODO: Put more parameters in constructor into dependency object.
    static class Dependencies {
        int getPerformPollInterval() {
            // TODO: Consider make this configurable.
            return DEFAULT_PERFORM_POLL_INTERVAL_MS;
        }
    }

    public OffloadController(Handler h, OffloadHardwareInterface hwi,
            ContentResolver contentResolver, NetworkStatsManager nsm, SharedLog log,
            @NonNull Dependencies deps) {
        mHandler = h;
        mHwInterface = hwi;
        mContentResolver = contentResolver;
        mLog = log.forSubComponent(TAG);
        mDownstreams = new HashMap<>();
        mExemptPrefixes = new HashSet<>();
        mLastLocalPrefixStrs = new HashSet<>();
        OffloadTetheringStatsProvider provider = new OffloadTetheringStatsProvider();
        try {
            nsm.registerNetworkStatsProvider(getClass().getSimpleName(), provider);
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Cannot register offload stats provider: " + e);
            provider = null;
        }
        mStatsProvider = provider;
        mDeps = deps;
    }

    /** Start hardware offload. */
    public boolean start() {
        if (started()) return true;

        if (isOffloadDisabled()) {
            mLog.i("tethering offload disabled");
            return false;
        }

        if (!mConfigInitialized) {
            mConfigInitialized = mHwInterface.initOffloadConfig();
            if (!mConfigInitialized) {
                mLog.i("tethering offload config not supported");
                stop();
                return false;
            }
        }

        mControlInitialized = mHwInterface.initOffloadControl(
                // OffloadHardwareInterface guarantees that these callback
                // methods are called on the handler passed to it, which is the
                // same as mHandler, as coordinated by the setup in Tethering.
                new OffloadHardwareInterface.ControlCallback() {
                    @Override
                    public void onStarted() {
                        if (!started()) return;
                        mLog.log("onStarted");
                    }

                    @Override
                    public void onStoppedError() {
                        if (!started()) return;
                        mLog.log("onStoppedError");
                    }

                    @Override
                    public void onStoppedUnsupported() {
                        if (!started()) return;
                        mLog.log("onStoppedUnsupported");
                        // Poll for statistics and trigger a sweep of tethering
                        // stats by observers. This might not succeed, but it's
                        // worth trying anyway. We need to do this because from
                        // this point on we continue with software forwarding,
                        // and we need to synchronize stats and limits between
                        // software and hardware forwarding.
                        updateStatsForAllUpstreams();
                        if (mStatsProvider != null) mStatsProvider.pushTetherStats();
                    }

                    @Override
                    public void onSupportAvailable() {
                        if (!started()) return;
                        mLog.log("onSupportAvailable");

                        // [1] Poll for statistics and trigger a sweep of stats
                        // by observers. We need to do this to ensure that any
                        // limits set take into account any software tethering
                        // traffic that has been happening in the meantime.
                        updateStatsForAllUpstreams();
                        if (mStatsProvider != null) mStatsProvider.pushTetherStats();
                        // [2] (Re)Push all state.
                        computeAndPushLocalPrefixes(UpdateType.FORCE);
                        pushAllDownstreamState();
                        pushUpstreamParameters(null);
                    }

                    @Override
                    public void onStoppedLimitReached() {
                        if (!started()) return;
                        mLog.log("onStoppedLimitReached");

                        // We cannot reliably determine on which interface the limit was reached,
                        // because the HAL interface does not specify it. We cannot just use the
                        // current upstream, because that might have changed since the time that
                        // the HAL queued the callback.
                        // TODO: rev the HAL so that it provides an interface name.

                        updateStatsForCurrentUpstream();
                        if (mStatsProvider != null) {
                            mStatsProvider.pushTetherStats();
                            // Push stats to service does not cause the service react to it
                            // immediately. Inform the service about limit reached.
                            mStatsProvider.notifyLimitReached();
                        }
                    }

                    @Override
                    public void onNatTimeoutUpdate(int proto,
                                                   String srcAddr, int srcPort,
                                                   String dstAddr, int dstPort) {
                        if (!started()) return;
                        updateNatTimeout(proto, srcAddr, srcPort, dstAddr, dstPort);
                    }
                });

        final boolean isStarted = started();
        if (!isStarted) {
            mLog.i("tethering offload control not supported");
            stop();
        } else {
            mLog.log("tethering offload started");
            mNatUpdateCallbacksReceived = 0;
            mNatUpdateNetlinkErrors = 0;
            maybeSchedulePollingStats();
        }
        return isStarted;
    }

    /** Stop hardware offload. */
    public void stop() {
        // Completely stops tethering offload. After this method is called, it is no longer safe to
        // call any HAL method, no callbacks from the hardware will be delivered, and any in-flight
        // callbacks must be ignored. Offload may be started again by calling start().
        final boolean wasStarted = started();
        updateStatsForCurrentUpstream();
        mUpstreamLinkProperties = null;
        mHwInterface.stopOffloadControl();
        mControlInitialized = false;
        mConfigInitialized = false;
        if (mHandler.hasCallbacks(mScheduledPollingTask)) {
            mHandler.removeCallbacks(mScheduledPollingTask);
        }
        if (wasStarted) mLog.log("tethering offload stopped");
    }

    private boolean started() {
        return mConfigInitialized && mControlInitialized;
    }

    @VisibleForTesting
    class OffloadTetheringStatsProvider extends NetworkStatsProvider {
        // These stats must only ever be touched on the handler thread.
        @NonNull
        private NetworkStats mIfaceStats = new NetworkStats(0L, 0);
        @NonNull
        private NetworkStats mUidStats = new NetworkStats(0L, 0);

        /**
         * A helper function that collect tether stats from local hashmap. Note that this does not
         * invoke binder call.
         */
        @VisibleForTesting
        @NonNull
        NetworkStats getTetherStats(@NonNull StatsType how) {
            NetworkStats stats = new NetworkStats(0L, 0);
            final int uid = (how == StatsType.STATS_PER_UID) ? UID_TETHERING : UID_ALL;

            for (final Map.Entry<String, ForwardedStats> kv : mForwardedStats.entrySet()) {
                final ForwardedStats value = kv.getValue();
                final Entry entry = new Entry(kv.getKey(), uid, SET_DEFAULT, TAG_NONE, METERED_NO,
                        ROAMING_NO, DEFAULT_NETWORK_NO, value.rxBytes, 0L, value.txBytes, 0L, 0L);
                stats = stats.addEntry(entry);
            }

            return stats;
        }

        @Override
        public void onSetLimit(String iface, long quotaBytes) {
            // Listen for all iface is necessary since upstream might be changed after limit
            // is set.
            mHandler.post(() -> {
                final Long curIfaceQuota = mInterfaceQuotas.get(iface);

                // If the quota is set to unlimited, the value set to HAL is Long.MAX_VALUE,
                // which is ~8.4 x 10^6 TiB, no one can actually reach it. Thus, it is not
                // useful to set it multiple times.
                // Otherwise, the quota needs to be updated to tell HAL to re-count from now even
                // if the quota is the same as the existing one.
                if (null == curIfaceQuota && QUOTA_UNLIMITED == quotaBytes) return;

                if (quotaBytes == QUOTA_UNLIMITED) {
                    mInterfaceQuotas.remove(iface);
                } else {
                    mInterfaceQuotas.put(iface, quotaBytes);
                }
                maybeUpdateDataLimit(iface);
            });
        }

        /**
         * Push stats to service, but does not cause a force polling. Note that this can only be
         * called on the handler thread.
         */
        public void pushTetherStats() {
            // TODO: remove the accumulated stats and report the diff from HAL directly.
            final NetworkStats ifaceDiff =
                    getTetherStats(StatsType.STATS_PER_IFACE).subtract(mIfaceStats);
            final NetworkStats uidDiff =
                    getTetherStats(StatsType.STATS_PER_UID).subtract(mUidStats);
            try {
                notifyStatsUpdated(0 /* token */, ifaceDiff, uidDiff);
                mIfaceStats = mIfaceStats.add(ifaceDiff);
                mUidStats = mUidStats.add(uidDiff);
            } catch (RuntimeException e) {
                mLog.e("Cannot report network stats: ", e);
            }
        }

        @Override
        public void onRequestStatsUpdate(int token) {
            // Do not attempt to update stats by querying the offload HAL
            // synchronously from a different thread than the Handler thread. http://b/64771555.
            mHandler.post(() -> {
                updateStatsForCurrentUpstream();
                pushTetherStats();
            });
        }

        @Override
        public void onSetAlert(long quotaBytes) {
            // TODO: Ask offload HAL to notify alert without stopping traffic.
            // Post it to handler thread since it access remaining quota bytes.
            mHandler.post(() -> {
                updateAlertQuota(quotaBytes);
                maybeSchedulePollingStats();
            });
        }
    }

    private String currentUpstreamInterface() {
        return (mUpstreamLinkProperties != null)
                ? mUpstreamLinkProperties.getInterfaceName() : null;
    }

    private void maybeUpdateStats(String iface) {
        if (TextUtils.isEmpty(iface)) {
            return;
        }

        // Always called on the handler thread.
        //
        // Use get()/put() instead of updating ForwardedStats in place because we can be called
        // concurrently with getTetherStats. In combination with the guarantees provided by
        // ConcurrentHashMap, this ensures that getTetherStats always gets the most recent copy of
        // the stats for each interface, and does not observe partial writes where rxBytes is
        // updated and txBytes is not.
        ForwardedStats diff = mHwInterface.getForwardedStats(iface);
        final long usedAlertQuota = diff.rxBytes + diff.txBytes;
        ForwardedStats base = mForwardedStats.get(iface);
        if (base != null) {
            diff.add(base);
        }

        // Update remaining alert quota if it is still positive.
        if (mRemainingAlertQuota > 0 && usedAlertQuota > 0) {
            // Trim to zero if overshoot.
            final long newQuota = Math.max(mRemainingAlertQuota - usedAlertQuota, 0);
            updateAlertQuota(newQuota);
        }

        mForwardedStats.put(iface, diff);
        // diff is a new object, just created by getForwardedStats(). Therefore, anyone reading from
        // mForwardedStats (i.e., any caller of getTetherStats) will see the new stats immediately.
    }

    /**
     * Update remaining alert quota, fire the {@link NetworkStatsProvider#notifyAlertReached()}
     * callback when it reaches zero. This can be invoked either from service setting the alert, or
     * {@code maybeUpdateStats} when updating stats. Note that this can be only called on
     * handler thread.
     *
     * @param newQuota non-negative value to indicate the new quota, or
     *                 {@link NetworkStatsProvider#QUOTA_UNLIMITED} to indicate there is no
     *                 quota.
     */
    private void updateAlertQuota(long newQuota) {
        if (newQuota < QUOTA_UNLIMITED) {
            throw new IllegalArgumentException("invalid quota value " + newQuota);
        }
        if (mRemainingAlertQuota == newQuota) return;

        mRemainingAlertQuota = newQuota;
        if (mRemainingAlertQuota == 0) {
            mLog.i("notifyAlertReached");
            if (mStatsProvider != null) mStatsProvider.notifyAlertReached();
        }
    }

    /**
     * Schedule polling if needed, this will be stopped if offload has been
     * stopped or remaining quota reaches zero or upstream is empty.
     * Note that this can be only called on handler thread.
     */
    private void maybeSchedulePollingStats() {
        if (!isPollingStatsNeeded()) return;

        if (mHandler.hasCallbacks(mScheduledPollingTask)) {
            mHandler.removeCallbacks(mScheduledPollingTask);
        }
        mHandler.postDelayed(mScheduledPollingTask, mDeps.getPerformPollInterval());
    }

    private boolean isPollingStatsNeeded() {
        return started() && mRemainingAlertQuota > 0
                && !TextUtils.isEmpty(currentUpstreamInterface());
    }

    private boolean maybeUpdateDataLimit(String iface) {
        // setDataLimit may only be called while offload is occurring on this upstream.
        if (!started() || !TextUtils.equals(iface, currentUpstreamInterface())) {
            return true;
        }

        Long limit = mInterfaceQuotas.get(iface);
        if (limit == null) {
            limit = Long.MAX_VALUE;
        }

        return mHwInterface.setDataLimit(iface, limit);
    }

    private void updateStatsForCurrentUpstream() {
        maybeUpdateStats(currentUpstreamInterface());
    }

    private void updateStatsForAllUpstreams() {
        // In practice, there should only ever be a single digit number of
        // upstream interfaces over the lifetime of an active tethering session.
        // Roughly speaking, imagine a very ambitious one or two of each of the
        // following interface types: [ "rmnet_data", "wlan", "eth", "rndis" ].
        for (Map.Entry<String, ForwardedStats> kv : mForwardedStats.entrySet()) {
            maybeUpdateStats(kv.getKey());
        }
    }

    /** Set current tethering upstream LinkProperties. */
    public void setUpstreamLinkProperties(LinkProperties lp) {
        if (!started() || Objects.equals(mUpstreamLinkProperties, lp)) return;

        final String prevUpstream = currentUpstreamInterface();

        mUpstreamLinkProperties = (lp != null) ? new LinkProperties(lp) : null;
        // Make sure we record this interface in the ForwardedStats map.
        final String iface = currentUpstreamInterface();
        if (!TextUtils.isEmpty(iface)) mForwardedStats.putIfAbsent(iface, EMPTY_STATS);

        maybeSchedulePollingStats();

        // TODO: examine return code and decide what to do if programming
        // upstream parameters fails (probably just wait for a subsequent
        // onOffloadEvent() callback to tell us offload is available again and
        // then reapply all state).
        computeAndPushLocalPrefixes(UpdateType.IF_NEEDED);
        pushUpstreamParameters(prevUpstream);
    }

    /** Set local prefixes. */
    public void setLocalPrefixes(Set<IpPrefix> localPrefixes) {
        mExemptPrefixes = localPrefixes;

        if (!started()) return;
        computeAndPushLocalPrefixes(UpdateType.IF_NEEDED);
    }

    /** Update current downstream LinkProperties. */
    public void notifyDownstreamLinkProperties(LinkProperties lp) {
        final String ifname = lp.getInterfaceName();
        final LinkProperties oldLp = mDownstreams.put(ifname, new LinkProperties(lp));
        if (Objects.equals(oldLp, lp)) return;

        if (!started()) return;
        pushDownstreamState(oldLp, lp);
    }

    private void pushDownstreamState(LinkProperties oldLp, LinkProperties newLp) {
        final String ifname = newLp.getInterfaceName();
        final List<RouteInfo> oldRoutes =
                (oldLp != null) ? oldLp.getRoutes() : Collections.EMPTY_LIST;
        final List<RouteInfo> newRoutes = newLp.getRoutes();

        // For each old route, if not in new routes: remove.
        for (RouteInfo ri : oldRoutes) {
            if (shouldIgnoreDownstreamRoute(ri)) continue;
            if (!newRoutes.contains(ri)) {
                mHwInterface.removeDownstreamPrefix(ifname, ri.getDestination().toString());
            }
        }

        // For each new route, if not in old routes: add.
        for (RouteInfo ri : newRoutes) {
            if (shouldIgnoreDownstreamRoute(ri)) continue;
            if (!oldRoutes.contains(ri)) {
                mHwInterface.addDownstreamPrefix(ifname, ri.getDestination().toString());
            }
        }
    }

    private void pushAllDownstreamState() {
        for (LinkProperties lp : mDownstreams.values()) {
            pushDownstreamState(null, lp);
        }
    }

    /** Remove downstream interface from offload hardware. */
    public void removeDownstreamInterface(String ifname) {
        final LinkProperties lp = mDownstreams.remove(ifname);
        if (lp == null) return;

        if (!started()) return;

        for (RouteInfo route : lp.getRoutes()) {
            if (shouldIgnoreDownstreamRoute(route)) continue;
            mHwInterface.removeDownstreamPrefix(ifname, route.getDestination().toString());
        }
    }

    private boolean isOffloadDisabled() {
        final int defaultDisposition = mHwInterface.getDefaultTetherOffloadDisabled();
        return (Settings.Global.getInt(
                mContentResolver, TETHER_OFFLOAD_DISABLED, defaultDisposition) != 0);
    }

    private boolean pushUpstreamParameters(String prevUpstream) {
        final String iface = currentUpstreamInterface();

        if (TextUtils.isEmpty(iface)) {
            final boolean rval = mHwInterface.setUpstreamParameters("", ANYIP, ANYIP, null);
            // Update stats after we've told the hardware to stop forwarding so
            // we don't miss packets.
            maybeUpdateStats(prevUpstream);
            return rval;
        }

        // A stacked interface cannot be an upstream for hardware offload.
        // Consequently, we examine only the primary interface name, look at
        // getAddresses() rather than getAllAddresses(), and check getRoutes()
        // rather than getAllRoutes().
        final ArrayList<String> v6gateways = new ArrayList<>();
        String v4addr = null;
        String v4gateway = null;

        for (InetAddress ip : mUpstreamLinkProperties.getAddresses()) {
            if (ip instanceof Inet4Address) {
                v4addr = ip.getHostAddress();
                break;
            }
        }

        // Find the gateway addresses of all default routes of either address family.
        for (RouteInfo ri : mUpstreamLinkProperties.getRoutes()) {
            if (!ri.hasGateway()) continue;

            final String gateway = ri.getGateway().getHostAddress();
            final InetAddress address = ri.getDestination().getAddress();
            if (ri.isDefaultRoute() && address instanceof Inet4Address) {
                v4gateway = gateway;
            } else if (ri.isDefaultRoute() && address instanceof Inet6Address) {
                v6gateways.add(gateway);
            }
        }

        boolean success = mHwInterface.setUpstreamParameters(
                iface, v4addr, v4gateway, (v6gateways.isEmpty() ? null : v6gateways));

        if (!success) {
            return success;
        }

        // Update stats after we've told the hardware to change routing so we don't miss packets.
        maybeUpdateStats(prevUpstream);

        // Data limits can only be set once offload is running on the upstream.
        success = maybeUpdateDataLimit(iface);
        if (!success) {
            // If we failed to set a data limit, don't use this upstream, because we don't want to
            // blow through the data limit that we were told to apply.
            mLog.log("Setting data limit for " + iface + " failed, disabling offload.");
            stop();
        }

        return success;
    }

    private boolean computeAndPushLocalPrefixes(UpdateType how) {
        final boolean force = (how == UpdateType.FORCE);
        final Set<String> localPrefixStrs = computeLocalPrefixStrings(
                mExemptPrefixes, mUpstreamLinkProperties);
        if (!force && mLastLocalPrefixStrs.equals(localPrefixStrs)) return true;

        mLastLocalPrefixStrs = localPrefixStrs;
        return mHwInterface.setLocalPrefixes(new ArrayList<>(localPrefixStrs));
    }

    // TODO: Factor in downstream LinkProperties once that information is available.
    private static Set<String> computeLocalPrefixStrings(
            Set<IpPrefix> localPrefixes, LinkProperties upstreamLinkProperties) {
        // Create an editable copy.
        final Set<IpPrefix> prefixSet = new HashSet<>(localPrefixes);

        // TODO: If a downstream interface (not currently passed in) is reusing
        // the /64 of the upstream (64share) then:
        //
        //     [a] remove that /64 from the local prefixes
        //     [b] add in /128s for IP addresses on the downstream interface
        //     [c] add in /128s for IP addresses on the upstream interface
        //
        // Until downstream information is available here, simply add /128s from
        // the upstream network; they'll just be redundant with their /64.
        if (upstreamLinkProperties != null) {
            for (LinkAddress linkAddr : upstreamLinkProperties.getLinkAddresses()) {
                if (!linkAddr.isGlobalPreferred()) continue;
                final InetAddress ip = linkAddr.getAddress();
                if (!(ip instanceof Inet6Address)) continue;
                prefixSet.add(new IpPrefix(ip, 128));
            }
        }

        final HashSet<String> localPrefixStrs = new HashSet<>();
        for (IpPrefix pfx : prefixSet) localPrefixStrs.add(pfx.toString());
        return localPrefixStrs;
    }

    private static boolean shouldIgnoreDownstreamRoute(RouteInfo route) {
        // Ignore any link-local routes.
        final IpPrefix destination = route.getDestination();
        final LinkAddress linkAddr = new LinkAddress(destination.getAddress(),
                destination.getPrefixLength());
        if (!linkAddr.isGlobalPreferred()) return true;

        return false;
    }

    /** Dump information. */
    public void dump(IndentingPrintWriter pw) {
        if (isOffloadDisabled()) {
            pw.println("Offload disabled");
            return;
        }
        final boolean isStarted = started();
        pw.println("Offload HALs " + (isStarted ? "started" : "not started"));
        LinkProperties lp = mUpstreamLinkProperties;
        String upstream = (lp != null) ? lp.getInterfaceName() : null;
        pw.println("Current upstream: " + upstream);
        pw.println("Exempt prefixes: " + mLastLocalPrefixStrs);
        pw.println("NAT timeout update callbacks received during the "
                + (isStarted ? "current" : "last")
                + " offload session: "
                + mNatUpdateCallbacksReceived);
        pw.println("NAT timeout update netlink errors during the "
                + (isStarted ? "current" : "last")
                + " offload session: "
                + mNatUpdateNetlinkErrors);
    }

    private void updateNatTimeout(
            int proto, String srcAddr, int srcPort, String dstAddr, int dstPort) {
        final String protoName = protoNameFor(proto);
        if (protoName == null) {
            mLog.e("Unknown NAT update callback protocol: " + proto);
            return;
        }

        final Inet4Address src = parseIPv4Address(srcAddr);
        if (src == null) {
            mLog.e("Failed to parse IPv4 address: " + srcAddr);
            return;
        }

        if (!isValidUdpOrTcpPort(srcPort)) {
            mLog.e("Invalid src port: " + srcPort);
            return;
        }

        final Inet4Address dst = parseIPv4Address(dstAddr);
        if (dst == null) {
            mLog.e("Failed to parse IPv4 address: " + dstAddr);
            return;
        }

        if (!isValidUdpOrTcpPort(dstPort)) {
            mLog.e("Invalid dst port: " + dstPort);
            return;
        }

        mNatUpdateCallbacksReceived++;
        final String natDescription = String.format("%s (%s, %s) -> (%s, %s)",
                protoName, srcAddr, srcPort, dstAddr, dstPort);
        if (DBG) {
            mLog.log("NAT timeout update: " + natDescription);
        }

        final int timeoutSec = connectionTimeoutUpdateSecondsFor(proto);
        final byte[] msg = ConntrackMessage.newIPv4TimeoutUpdateRequest(
                proto, src, srcPort, dst, dstPort, timeoutSec);

        try {
            NetlinkSocket.sendOneShotKernelMessage(OsConstants.NETLINK_NETFILTER, msg);
        } catch (ErrnoException e) {
            mNatUpdateNetlinkErrors++;
            mLog.e("Error updating NAT conntrack entry >" + natDescription + "<: " + e
                    + ", msg: " + NetlinkConstants.hexify(msg));
            mLog.log("NAT timeout update callbacks received: " + mNatUpdateCallbacksReceived);
            mLog.log("NAT timeout update netlink errors: " + mNatUpdateNetlinkErrors);
        }
    }

    private static Inet4Address parseIPv4Address(String addrString) {
        try {
            final InetAddress ip = InetAddresses.parseNumericAddress(addrString);
            // TODO: Consider other sanitization steps here, including perhaps:
            //           not eql to 0.0.0.0
            //           not within 169.254.0.0/16
            //           not within ::ffff:0.0.0.0/96
            //           not within ::/96
            // et cetera.
            if (ip instanceof Inet4Address) {
                return (Inet4Address) ip;
            }
        } catch (IllegalArgumentException iae) { }
        return null;
    }

    private static String protoNameFor(int proto) {
        // OsConstants values are not constant expressions; no switch statement.
        if (proto == OsConstants.IPPROTO_UDP) {
            return "UDP";
        } else if (proto == OsConstants.IPPROTO_TCP) {
            return "TCP";
        }
        return null;
    }

    private static int connectionTimeoutUpdateSecondsFor(int proto) {
        // TODO: Replace this with more thoughtful work, perhaps reading from
        // and maybe writing to any required
        //
        //     /proc/sys/net/netfilter/nf_conntrack_tcp_timeout_*
        //     /proc/sys/net/netfilter/nf_conntrack_udp_timeout{,_stream}
        //
        // entries.  TBD.
        if (proto == OsConstants.IPPROTO_TCP) {
            // Cf. /proc/sys/net/netfilter/nf_conntrack_tcp_timeout_established
            return 432000;
        } else {
            // Cf. /proc/sys/net/netfilter/nf_conntrack_udp_timeout_stream
            return 180;
        }
    }

    private static boolean isValidUdpOrTcpPort(int port) {
        return port > 0 && port < 65536;
    }
}
