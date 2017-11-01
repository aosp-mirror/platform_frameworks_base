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

package com.android.server.connectivity.tethering;

import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.STATS_PER_UID;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.TrafficStats.UID_TETHERING;
import static android.provider.Settings.Global.TETHER_OFFLOAD_DISABLED;

import android.content.ContentResolver;
import android.net.ITetheringStatsProvider;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkStats;
import android.net.RouteInfo;
import android.net.netlink.ConntrackMessage;
import android.net.netlink.NetlinkConstants;
import android.net.netlink.NetlinkSocket;
import android.net.util.IpUtils;
import android.net.util.SharedLog;
import android.os.Handler;
import android.os.Looper;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.text.TextUtils;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.connectivity.tethering.OffloadHardwareInterface.ForwardedStats;

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
import java.util.concurrent.TimeUnit;

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

    private static enum UpdateType { IF_NEEDED, FORCE };

    private final Handler mHandler;
    private final OffloadHardwareInterface mHwInterface;
    private final ContentResolver mContentResolver;
    private final INetworkManagementService mNms;
    private final ITetheringStatsProvider mStatsProvider;
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

    private int mNatUpdateCallbacksReceived;
    private int mNatUpdateNetlinkErrors;

    public OffloadController(Handler h, OffloadHardwareInterface hwi,
            ContentResolver contentResolver, INetworkManagementService nms, SharedLog log) {
        mHandler = h;
        mHwInterface = hwi;
        mContentResolver = contentResolver;
        mNms = nms;
        mStatsProvider = new OffloadTetheringStatsProvider();
        mLog = log.forSubComponent(TAG);
        mDownstreams = new HashMap<>();
        mExemptPrefixes = new HashSet<>();
        mLastLocalPrefixStrs = new HashSet<>();

        try {
            mNms.registerTetheringStatsProvider(mStatsProvider, getClass().getSimpleName());
        } catch (RemoteException e) {
            mLog.e("Cannot register offload stats provider: " + e);
        }
    }

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
                        forceTetherStatsPoll();
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
                        forceTetherStatsPoll();
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

                        // Fetch current stats, so that when our notification reaches
                        // NetworkStatsService and triggers a poll, we will respond with
                        // current data (which will be above the limit that was reached).
                        // Note that if we just changed upstream, this is unnecessary but harmless.
                        // The stats for the previous upstream were already updated on this thread
                        // just after the upstream was changed, so they are also up-to-date.
                        updateStatsForCurrentUpstream();
                        forceTetherStatsPoll();
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
        }
        return isStarted;
    }

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
        if (wasStarted) mLog.log("tethering offload stopped");
    }

    private boolean started() {
        return mConfigInitialized && mControlInitialized;
    }

    private class OffloadTetheringStatsProvider extends ITetheringStatsProvider.Stub {
        @Override
        public NetworkStats getTetherStats(int how) {
            // getTetherStats() is the only function in OffloadController that can be called from
            // a different thread. Do not attempt to update stats by querying the offload HAL
            // synchronously from a different thread than our Handler thread. http://b/64771555.
            Runnable updateStats = () -> { updateStatsForCurrentUpstream(); };
            if (Looper.myLooper() == mHandler.getLooper()) {
                updateStats.run();
            } else {
                mHandler.post(updateStats);
            }

            NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 0);
            NetworkStats.Entry entry = new NetworkStats.Entry();
            entry.set = SET_DEFAULT;
            entry.tag = TAG_NONE;
            entry.uid = (how == STATS_PER_UID) ? UID_TETHERING : UID_ALL;

            for (Map.Entry<String, ForwardedStats> kv : mForwardedStats.entrySet()) {
                ForwardedStats value = kv.getValue();
                entry.iface = kv.getKey();
                entry.rxBytes = value.rxBytes;
                entry.txBytes = value.txBytes;
                stats.addValues(entry);
            }

            return stats;
        }

        public void setInterfaceQuota(String iface, long quotaBytes) {
            mHandler.post(() -> {
                if (quotaBytes == ITetheringStatsProvider.QUOTA_UNLIMITED) {
                    mInterfaceQuotas.remove(iface);
                } else {
                    mInterfaceQuotas.put(iface, quotaBytes);
                }
                maybeUpdateDataLimit(iface);
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
        ForwardedStats base = mForwardedStats.get(iface);
        if (base != null) {
            diff.add(base);
        }
        mForwardedStats.put(iface, diff);
        // diff is a new object, just created by getForwardedStats(). Therefore, anyone reading from
        // mForwardedStats (i.e., any caller of getTetherStats) will see the new stats immediately.
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

    private void forceTetherStatsPoll() {
        try {
            mNms.tetherLimitReached(mStatsProvider);
        } catch (RemoteException e) {
            mLog.e("Cannot report data limit reached: " + e);
        }
    }

    public void setUpstreamLinkProperties(LinkProperties lp) {
        if (!started() || Objects.equals(mUpstreamLinkProperties, lp)) return;

        final String prevUpstream = currentUpstreamInterface();

        mUpstreamLinkProperties = (lp != null) ? new LinkProperties(lp) : null;
        // Make sure we record this interface in the ForwardedStats map.
        final String iface = currentUpstreamInterface();
        if (!TextUtils.isEmpty(iface)) mForwardedStats.putIfAbsent(iface, EMPTY_STATS);

        // TODO: examine return code and decide what to do if programming
        // upstream parameters fails (probably just wait for a subsequent
        // onOffloadEvent() callback to tell us offload is available again and
        // then reapply all state).
        computeAndPushLocalPrefixes(UpdateType.IF_NEEDED);
        pushUpstreamParameters(prevUpstream);
    }

    public void setLocalPrefixes(Set<IpPrefix> localPrefixes) {
        mExemptPrefixes = localPrefixes;

        if (!started()) return;
        computeAndPushLocalPrefixes(UpdateType.IF_NEEDED);
    }

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
            if (ri.isIPv4Default()) {
                v4gateway = gateway;
            } else if (ri.isIPv6Default()) {
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
        if (!route.getDestinationLinkAddress().isGlobalPreferred()) return true;

        return false;
    }

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

        if (!IpUtils.isValidUdpOrTcpPort(srcPort)) {
            mLog.e("Invalid src port: " + srcPort);
            return;
        }

        final Inet4Address dst = parseIPv4Address(dstAddr);
        if (dst == null) {
            mLog.e("Failed to parse IPv4 address: " + dstAddr);
            return;
        }

        if (!IpUtils.isValidUdpOrTcpPort(dstPort)) {
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
            final InetAddress ip = InetAddress.parseNumericAddress(addrString);
            // TODO: Consider other sanitization steps here, including perhaps:
            //           not eql to 0.0.0.0
            //           not within 169.254.0.0/16
            //           not within ::ffff:0.0.0.0/96
            //           not within ::/96
            // et cetera.
            if (ip instanceof Inet4Address) {
                return (Inet4Address) ip;
            }
        } catch (IllegalArgumentException iae) {}
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
}
