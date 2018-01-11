/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.net.ip;

import android.content.Context;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LinkProperties.ProvisioningChange;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.ip.IpNeighborMonitor.NeighborEvent;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.IpReachabilityEvent;
import android.net.netlink.StructNdMsg;
import android.net.util.InterfaceParams;
import android.net.util.MultinetworkPolicyTracker;
import android.net.util.SharedLog;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.DumpUtils.Dump;

import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * IpReachabilityMonitor.
 *
 * Monitors on-link IP reachability and notifies callers whenever any on-link
 * addresses of interest appear to have become unresponsive.
 *
 * This code does not concern itself with "why" a neighbour might have become
 * unreachable. Instead, it primarily reacts to the kernel's notion of IP
 * reachability for each of the neighbours we know to be critically important
 * to normal network connectivity. As such, it is often "just the messenger":
 * the neighbours about which it warns are already deemed by the kernel to have
 * become unreachable.
 *
 *
 * How it works:
 *
 *   1. The "on-link neighbours of interest" found in a given LinkProperties
 *      instance are added to a "watch list" via #updateLinkProperties().
 *      This usually means all default gateways and any on-link DNS servers.
 *
 *   2. We listen continuously for netlink neighbour messages (RTM_NEWNEIGH,
 *      RTM_DELNEIGH), watching only for neighbours in the watch list.
 *
 *        - A neighbour going into NUD_REACHABLE, NUD_STALE, NUD_DELAY, and
 *          even NUD_PROBE is perfectly normal; we merely record the new state.
 *
 *        - A neighbour's entry may be deleted (RTM_DELNEIGH), for example due
 *          to garbage collection.  This is not necessarily of immediate
 *          concern; we record the neighbour as moving to NUD_NONE.
 *
 *        - A neighbour transitioning to NUD_FAILED (for any reason) is
 *          critically important and is handled as described below in #4.
 *
 *   3. All on-link neighbours in the watch list can be forcibly "probed" by
 *      calling #probeAll(). This should be called whenever it is important to
 *      verify that critical neighbours on the link are still reachable, e.g.
 *      when roaming between BSSIDs.
 *
 *        - The kernel will send unicast ARP requests for IPv4 neighbours and
 *          unicast NS packets for IPv6 neighbours.  The expected replies will
 *          likely be unicast.
 *
 *        - The forced probing is done holding a wakelock. The kernel may,
 *          however, initiate probing of a neighbor on its own, i.e. whenever
 *          a neighbour has expired from NUD_DELAY.
 *
 *        - The kernel sends:
 *
 *              /proc/sys/net/ipv{4,6}/neigh/<ifname>/ucast_solicit
 *
 *          number of probes (usually 3) every:
 *
 *              /proc/sys/net/ipv{4,6}/neigh/<ifname>/retrans_time_ms
 *
 *          number of milliseconds (usually 1000ms). This normally results in
 *          3 unicast packets, 1 per second.
 *
 *        - If no response is received to any of the probe packets, the kernel
 *          marks the neighbour as being in state NUD_FAILED, and the listening
 *          process in #2 will learn of it.
 *
 *   4. We call the supplied Callback#notifyLost() function if the loss of a
 *      neighbour in NUD_FAILED would cause IPv4 or IPv6 configuration to
 *      become incomplete (a loss of provisioning).
 *
 *        - For example, losing all our IPv4 on-link DNS servers (or losing
 *          our only IPv6 default gateway) constitutes a loss of IPv4 (IPv6)
 *          provisioning; Callback#notifyLost() would be called.
 *
 *        - Since it can be non-trivial to reacquire certain IP provisioning
 *          state it may be best for the link to disconnect completely and
 *          reconnect afresh.
 *
 * Accessing an instance of this class from multiple threads is NOT safe.
 *
 * @hide
 */
public class IpReachabilityMonitor {
    private static final String TAG = "IpReachabilityMonitor";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    public interface Callback {
        // This callback function must execute as quickly as possible as it is
        // run on the same thread that listens to kernel neighbor updates.
        //
        // TODO: refactor to something like notifyProvisioningLost(String msg).
        public void notifyLost(InetAddress ip, String logMsg);
    }

    /**
     * Encapsulates IpReachabilityMonitor depencencies on systems that hinder unit testing.
     * TODO: consider also wrapping MultinetworkPolicyTracker in this interface.
     */
    interface Dependencies {
        void acquireWakeLock(long durationMs);

        static Dependencies makeDefault(Context context, String iface) {
            final String lockName = TAG + "." + iface;
            final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            final WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName);

            return new Dependencies() {
                public void acquireWakeLock(long durationMs) {
                    lock.acquire(durationMs);
                }
            };
        }
    }

    private final InterfaceParams mInterfaceParams;
    private final IpNeighborMonitor mIpNeighborMonitor;
    private final SharedLog mLog;
    private final Callback mCallback;
    private final Dependencies mDependencies;
    private final MultinetworkPolicyTracker mMultinetworkPolicyTracker;
    private final IpConnectivityLog mMetricsLog = new IpConnectivityLog();
    private LinkProperties mLinkProperties = new LinkProperties();
    private Map<InetAddress, NeighborEvent> mNeighborWatchList = new HashMap<>();
    // Time in milliseconds of the last forced probe request.
    private volatile long mLastProbeTimeMs;

    public IpReachabilityMonitor(
            Context context, InterfaceParams ifParams, Handler h, SharedLog log, Callback callback,
            MultinetworkPolicyTracker tracker) {
        this(ifParams, h, log, callback, tracker, Dependencies.makeDefault(context, ifParams.name));
    }

    @VisibleForTesting
    IpReachabilityMonitor(InterfaceParams ifParams, Handler h, SharedLog log, Callback callback,
            MultinetworkPolicyTracker tracker, Dependencies dependencies) {
        if (ifParams == null) throw new IllegalArgumentException("null InterfaceParams");

        mInterfaceParams = ifParams;
        mLog = log.forSubComponent(TAG);
        mCallback = callback;
        mMultinetworkPolicyTracker = tracker;
        mDependencies = dependencies;

        mIpNeighborMonitor = new IpNeighborMonitor(h, mLog,
                (NeighborEvent event) -> {
                    if (mInterfaceParams.index != event.ifindex) return;
                    if (!mNeighborWatchList.containsKey(event.ip)) return;

                    final NeighborEvent prev = mNeighborWatchList.put(event.ip, event);

                    // TODO: Consider what to do with other states that are not within
                    // NeighborEvent#isValid() (i.e. NUD_NONE, NUD_INCOMPLETE).
                    if (event.nudState == StructNdMsg.NUD_FAILED) {
                        mLog.w("ALERT neighbor went from: " + prev + " to: " + event);
                        handleNeighborLost(event);
                    }
                });
        mIpNeighborMonitor.start();
    }

    public void stop() {
        mIpNeighborMonitor.stop();
        clearLinkProperties();
    }

    public void dump(PrintWriter pw) {
        DumpUtils.dumpAsync(
                mIpNeighborMonitor.getHandler(),
                new Dump() {
                    @Override
                    public void dump(PrintWriter pw, String prefix) {
                        pw.println(describeWatchList("\n"));
                    }
                },
                pw, "", 1000);
    }

    private String describeWatchList() { return describeWatchList(" "); }

    private String describeWatchList(String sep) {
        final StringBuilder sb = new StringBuilder();
        sb.append("iface{" + mInterfaceParams + "}," + sep);
        sb.append("ntable=[" + sep);
        String delimiter = "";
        for (Map.Entry<InetAddress, NeighborEvent> entry : mNeighborWatchList.entrySet()) {
            sb.append(delimiter).append(entry.getKey().getHostAddress() + "/" + entry.getValue());
            delimiter = "," + sep;
        }
        sb.append("]");
        return sb.toString();
    }

    private static boolean isOnLink(List<RouteInfo> routes, InetAddress ip) {
        for (RouteInfo route : routes) {
            if (!route.hasGateway() && route.matches(ip)) {
                return true;
            }
        }
        return false;
    }

    public void updateLinkProperties(LinkProperties lp) {
        if (!mInterfaceParams.name.equals(lp.getInterfaceName())) {
            // TODO: figure out whether / how to cope with interface changes.
            Log.wtf(TAG, "requested LinkProperties interface '" + lp.getInterfaceName() +
                    "' does not match: " + mInterfaceParams.name);
            return;
        }

        mLinkProperties = new LinkProperties(lp);
        Map<InetAddress, NeighborEvent> newNeighborWatchList = new HashMap<>();

        final List<RouteInfo> routes = mLinkProperties.getRoutes();
        for (RouteInfo route : routes) {
            if (route.hasGateway()) {
                InetAddress gw = route.getGateway();
                if (isOnLink(routes, gw)) {
                    newNeighborWatchList.put(gw, mNeighborWatchList.getOrDefault(gw, null));
                }
            }
        }

        for (InetAddress dns : lp.getDnsServers()) {
            if (isOnLink(routes, dns)) {
                newNeighborWatchList.put(dns, mNeighborWatchList.getOrDefault(dns, null));
            }
        }

        mNeighborWatchList = newNeighborWatchList;
        if (DBG) { Log.d(TAG, "watch: " + describeWatchList()); }
    }

    public void clearLinkProperties() {
        mLinkProperties.clear();
        mNeighborWatchList.clear();
        if (DBG) { Log.d(TAG, "clear: " + describeWatchList()); }
    }

    private void handleNeighborLost(NeighborEvent event) {
        final LinkProperties whatIfLp = new LinkProperties(mLinkProperties);

        InetAddress ip = null;
        for (Map.Entry<InetAddress, NeighborEvent> entry : mNeighborWatchList.entrySet()) {
            // TODO: Consider using NeighborEvent#isValid() here; it's more
            // strict but may interact badly if other entries are somehow in
            // NUD_INCOMPLETE (say, during network attach).
            if (entry.getValue().nudState != StructNdMsg.NUD_FAILED) continue;

            ip = entry.getKey();
            for (RouteInfo route : mLinkProperties.getRoutes()) {
                if (ip.equals(route.getGateway())) {
                    whatIfLp.removeRoute(route);
                }
            }

            if (avoidingBadLinks() || !(ip instanceof Inet6Address)) {
                // We should do this unconditionally, but alas we cannot: b/31827713.
                whatIfLp.removeDnsServer(ip);
            }
        }

        final ProvisioningChange delta = LinkProperties.compareProvisioning(
                mLinkProperties, whatIfLp);

        if (delta == ProvisioningChange.LOST_PROVISIONING) {
            final String logMsg = "FAILURE: LOST_PROVISIONING, " + event;
            Log.w(TAG, logMsg);
            if (mCallback != null) {
                // TODO: remove |ip| when the callback signature no longer has
                // an InetAddress argument.
                mCallback.notifyLost(ip, logMsg);
            }
        }
        logNudFailed(delta);
    }

    private boolean avoidingBadLinks() {
        return (mMultinetworkPolicyTracker == null) || mMultinetworkPolicyTracker.getAvoidBadWifi();
    }

    public void probeAll() {
        final List<InetAddress> ipProbeList = new ArrayList<>(mNeighborWatchList.keySet());

        if (!ipProbeList.isEmpty()) {
            // Keep the CPU awake long enough to allow all ARP/ND
            // probes a reasonable chance at success. See b/23197666.
            //
            // The wakelock we use is (by default) refcounted, and this version
            // of acquire(timeout) queues a release message to keep acquisitions
            // and releases balanced.
            mDependencies.acquireWakeLock(getProbeWakeLockDuration());
        }

        for (InetAddress ip : ipProbeList) {
            final int rval = IpNeighborMonitor.startKernelNeighborProbe(mInterfaceParams.index, ip);
            mLog.log(String.format("put neighbor %s into NUD_PROBE state (rval=%d)",
                     ip.getHostAddress(), rval));
            logEvent(IpReachabilityEvent.PROBE, rval);
        }
        mLastProbeTimeMs = SystemClock.elapsedRealtime();
    }

    private static long getProbeWakeLockDuration() {
        // Ideally, this would be computed by examining the values of:
        //
        //     /proc/sys/net/ipv[46]/neigh/<ifname>/ucast_solicit
        //
        // and:
        //
        //     /proc/sys/net/ipv[46]/neigh/<ifname>/retrans_time_ms
        //
        // For now, just make some assumptions.
        final long numUnicastProbes = 3;
        final long retransTimeMs = 1000;
        final long gracePeriodMs = 500;
        return (numUnicastProbes * retransTimeMs) + gracePeriodMs;
    }

    private void logEvent(int probeType, int errorCode) {
        int eventType = probeType | (errorCode & 0xff);
        mMetricsLog.log(mInterfaceParams.name, new IpReachabilityEvent(eventType));
    }

    private void logNudFailed(ProvisioningChange delta) {
        long duration = SystemClock.elapsedRealtime() - mLastProbeTimeMs;
        boolean isFromProbe = (duration < getProbeWakeLockDuration());
        boolean isProvisioningLost = (delta == ProvisioningChange.LOST_PROVISIONING);
        int eventType = IpReachabilityEvent.nudFailureEventType(isFromProbe, isProvisioningLost);
        mMetricsLog.log(mInterfaceParams.name, new IpReachabilityEvent(eventType));
    }
}
