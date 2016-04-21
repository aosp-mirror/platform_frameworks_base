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

import com.android.internal.annotations.GuardedBy;

import android.content.Context;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LinkProperties.ProvisioningChange;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.metrics.IpReachabilityEvent;
import android.net.netlink.NetlinkConstants;
import android.net.netlink.NetlinkErrorMessage;
import android.net.netlink.NetlinkMessage;
import android.net.netlink.NetlinkSocket;
import android.net.netlink.RtNetlinkNeighborMessage;
import android.net.netlink.StructNdaCacheInfo;
import android.net.netlink.StructNdMsg;
import android.net.netlink.StructNlMsgHdr;
import android.os.PowerManager;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.NetlinkSocketAddress;
import android.system.OsConstants;
import android.util.Log;

import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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

    private final Object mLock = new Object();
    private final PowerManager.WakeLock mWakeLock;
    private final String mInterfaceName;
    private final int mInterfaceIndex;
    private final Callback mCallback;
    private final NetlinkSocketObserver mNetlinkSocketObserver;
    private final Thread mObserverThread;
    @GuardedBy("mLock")
    private LinkProperties mLinkProperties = new LinkProperties();
    // TODO: consider a map to a private NeighborState class holding more
    // information than a single NUD state entry.
    @GuardedBy("mLock")
    private Map<InetAddress, Short> mIpWatchList = new HashMap<>();
    @GuardedBy("mLock")
    private int mIpWatchListVersion;
    @GuardedBy("mLock")
    private boolean mRunning;

    /**
     * Make the kernel perform neighbor reachability detection (IPv4 ARP or IPv6 ND)
     * for the given IP address on the specified interface index.
     *
     * @return 0 if the request was successfully passed to the kernel; otherwise return
     *         a non-zero error code.
     */
    private static int probeNeighbor(int ifIndex, InetAddress ip) {
        final String msgSnippet = "probing ip=" + ip.getHostAddress() + "%" + ifIndex;
        if (DBG) { Log.d(TAG, msgSnippet); }

        final byte[] msg = RtNetlinkNeighborMessage.newNewNeighborMessage(
                1, ip, StructNdMsg.NUD_PROBE, ifIndex, null);

        int errno = -OsConstants.EPROTO;
        try (NetlinkSocket nlSocket = new NetlinkSocket(OsConstants.NETLINK_ROUTE)) {
            final long IO_TIMEOUT = 300L;
            nlSocket.connectToKernel();
            nlSocket.sendMessage(msg, 0, msg.length, IO_TIMEOUT);
            final ByteBuffer bytes = nlSocket.recvMessage(IO_TIMEOUT);
            // recvMessage() guaranteed to not return null if it did not throw.
            final NetlinkMessage response = NetlinkMessage.parse(bytes);
            if (response != null && response instanceof NetlinkErrorMessage &&
                    (((NetlinkErrorMessage) response).getNlMsgError() != null)) {
                errno = ((NetlinkErrorMessage) response).getNlMsgError().error;
                if (errno != 0) {
                    // TODO: consider ignoring EINVAL (-22), which appears to be
                    // normal when probing a neighbor for which the kernel does
                    // not already have / no longer has a link layer address.
                    Log.e(TAG, "Error " + msgSnippet + ", errmsg=" + response.toString());
                }
            } else {
                String errmsg;
                if (response == null) {
                    bytes.position(0);
                    errmsg = "raw bytes: " + NetlinkConstants.hexify(bytes);
                } else {
                    errmsg = response.toString();
                }
                Log.e(TAG, "Error " + msgSnippet + ", errmsg=" + errmsg);
            }
        } catch (ErrnoException e) {
            Log.e(TAG, "Error " + msgSnippet, e);
            errno = -e.errno;
        } catch (InterruptedIOException e) {
            Log.e(TAG, "Error " + msgSnippet, e);
            errno = -OsConstants.ETIMEDOUT;
        } catch (SocketException e) {
            Log.e(TAG, "Error " + msgSnippet, e);
            errno = -OsConstants.EIO;
        }
        return errno;
    }

    public IpReachabilityMonitor(Context context, String ifName, Callback callback)
                throws IllegalArgumentException {
        mInterfaceName = ifName;
        int ifIndex = -1;
        try {
            NetworkInterface netIf = NetworkInterface.getByName(ifName);
            mInterfaceIndex = netIf.getIndex();
        } catch (SocketException | NullPointerException e) {
            throw new IllegalArgumentException("invalid interface '" + ifName + "': ", e);
        }
        mWakeLock = ((PowerManager) context.getSystemService(Context.POWER_SERVICE)).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, TAG + "." + mInterfaceName);
        mCallback = callback;
        mNetlinkSocketObserver = new NetlinkSocketObserver();
        mObserverThread = new Thread(mNetlinkSocketObserver);
        mObserverThread.start();
    }

    public void stop() {
        synchronized (mLock) { mRunning = false; }
        clearLinkProperties();
        mNetlinkSocketObserver.clearNetlinkSocket();
    }

    // TODO: add a public dump() method that can be called during a bug report.

    private String describeWatchList() {
        final String delimiter = ", ";
        StringBuilder sb = new StringBuilder();
        synchronized (mLock) {
            sb.append("iface{" + mInterfaceName + "/" + mInterfaceIndex + "}, ");
            sb.append("v{" + mIpWatchListVersion + "}, ");
            sb.append("ntable=[");
            boolean firstTime = true;
            for (Map.Entry<InetAddress, Short> entry : mIpWatchList.entrySet()) {
                if (firstTime) {
                    firstTime = false;
                } else {
                    sb.append(delimiter);
                }
                sb.append(entry.getKey().getHostAddress() + "/" +
                        StructNdMsg.stringForNudState(entry.getValue()));
            }
            sb.append("]");
        }
        return sb.toString();
    }

    private boolean isWatching(InetAddress ip) {
        synchronized (mLock) {
            return mRunning && mIpWatchList.containsKey(ip);
        }
    }

    private boolean stillRunning() {
        synchronized (mLock) {
            return mRunning;
        }
    }

    private static boolean isOnLink(List<RouteInfo> routes, InetAddress ip) {
        for (RouteInfo route : routes) {
            if (!route.hasGateway() && route.matches(ip)) {
                return true;
            }
        }
        return false;
    }

    private short getNeighborStateLocked(InetAddress ip) {
        if (mIpWatchList.containsKey(ip)) {
            return mIpWatchList.get(ip);
        }
        return StructNdMsg.NUD_NONE;
    }

    public void updateLinkProperties(LinkProperties lp) {
        if (!mInterfaceName.equals(lp.getInterfaceName())) {
            // TODO: figure out whether / how to cope with interface changes.
            Log.wtf(TAG, "requested LinkProperties interface '" + lp.getInterfaceName() +
                    "' does not match: " + mInterfaceName);
            return;
        }

        synchronized (mLock) {
            mLinkProperties = new LinkProperties(lp);
            Map<InetAddress, Short> newIpWatchList = new HashMap<>();

            final List<RouteInfo> routes = mLinkProperties.getRoutes();
            for (RouteInfo route : routes) {
                if (route.hasGateway()) {
                    InetAddress gw = route.getGateway();
                    if (isOnLink(routes, gw)) {
                        newIpWatchList.put(gw, getNeighborStateLocked(gw));
                    }
                }
            }

            for (InetAddress nameserver : lp.getDnsServers()) {
                if (isOnLink(routes, nameserver)) {
                    newIpWatchList.put(nameserver, getNeighborStateLocked(nameserver));
                }
            }

            mIpWatchList = newIpWatchList;
            mIpWatchListVersion++;
        }
        if (DBG) { Log.d(TAG, "watch: " + describeWatchList()); }
    }

    public void clearLinkProperties() {
        synchronized (mLock) {
            mLinkProperties.clear();
            mIpWatchList.clear();
            mIpWatchListVersion++;
        }
        if (DBG) { Log.d(TAG, "clear: " + describeWatchList()); }
    }

    private void handleNeighborLost(String msg) {
        InetAddress ip = null;
        ProvisioningChange delta;
        synchronized (mLock) {
            LinkProperties whatIfLp = new LinkProperties(mLinkProperties);

            for (Map.Entry<InetAddress, Short> entry : mIpWatchList.entrySet()) {
                if (entry.getValue() != StructNdMsg.NUD_FAILED) {
                    continue;
                }

                ip = entry.getKey();
                for (RouteInfo route : mLinkProperties.getRoutes()) {
                    if (ip.equals(route.getGateway())) {
                        whatIfLp.removeRoute(route);
                    }
                }
                whatIfLp.removeDnsServer(ip);
            }

            delta = LinkProperties.compareProvisioning(mLinkProperties, whatIfLp);
        }

        if (delta == ProvisioningChange.LOST_PROVISIONING) {
            IpReachabilityEvent.logProvisioningLost(mInterfaceName);
            final String logMsg = "FAILURE: LOST_PROVISIONING, " + msg;
            Log.w(TAG, logMsg);
            if (mCallback != null) {
                // TODO: remove |ip| when the callback signature no longer has
                // an InetAddress argument.
                mCallback.notifyLost(ip, logMsg);
            }
        } else {
            IpReachabilityEvent.logNudFailed(mInterfaceName);
        }
    }

    public void probeAll() {
        Set<InetAddress> ipProbeList = new HashSet<InetAddress>();
        synchronized (mLock) {
            ipProbeList.addAll(mIpWatchList.keySet());
        }

        if (!ipProbeList.isEmpty() && stillRunning()) {
            // Keep the CPU awake long enough to allow all ARP/ND
            // probes a reasonable chance at success. See b/23197666.
            //
            // The wakelock we use is (by default) refcounted, and this version
            // of acquire(timeout) queues a release message to keep acquisitions
            // and releases balanced.
            mWakeLock.acquire(getProbeWakeLockDuration());
        }

        for (InetAddress target : ipProbeList) {
            if (!stillRunning()) {
                break;
            }
            final int returnValue = probeNeighbor(mInterfaceIndex, target);
            IpReachabilityEvent.logProbeEvent(mInterfaceName, returnValue);
        }
    }

    private long getProbeWakeLockDuration() {
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

    // TODO: simplify the number of objects by making this extend Thread.
    private final class NetlinkSocketObserver implements Runnable {
        private NetlinkSocket mSocket;

        @Override
        public void run() {
            if (VDBG) { Log.d(TAG, "Starting observing thread."); }
            synchronized (mLock) { mRunning = true; }

            try {
                setupNetlinkSocket();
            } catch (ErrnoException | SocketException e) {
                Log.e(TAG, "Failed to suitably initialize a netlink socket", e);
                synchronized (mLock) { mRunning = false; }
            }

            ByteBuffer byteBuffer;
            while (stillRunning()) {
                try {
                    byteBuffer = recvKernelReply();
                } catch (ErrnoException e) {
                    if (stillRunning()) { Log.w(TAG, "ErrnoException: ", e); }
                    break;
                }
                final long whenMs = SystemClock.elapsedRealtime();
                if (byteBuffer == null) {
                    continue;
                }
                parseNetlinkMessageBuffer(byteBuffer, whenMs);
            }

            clearNetlinkSocket();

            synchronized (mLock) { mRunning = false; }
            if (VDBG) { Log.d(TAG, "Finishing observing thread."); }
        }

        private void clearNetlinkSocket() {
            if (mSocket != null) {
                mSocket.close();
            }
        }

            // TODO: Refactor the main loop to recreate the socket upon recoverable errors.
        private void setupNetlinkSocket() throws ErrnoException, SocketException {
            clearNetlinkSocket();
            mSocket = new NetlinkSocket(OsConstants.NETLINK_ROUTE);

            final NetlinkSocketAddress listenAddr = new NetlinkSocketAddress(
                    0, OsConstants.RTMGRP_NEIGH);
            mSocket.bind(listenAddr);

            if (VDBG) {
                final NetlinkSocketAddress nlAddr = mSocket.getLocalAddress();
                Log.d(TAG, "bound to sockaddr_nl{"
                        + ((long) (nlAddr.getPortId() & 0xffffffff)) + ", "
                        + nlAddr.getGroupsMask()
                        + "}");
            }
        }

        private ByteBuffer recvKernelReply() throws ErrnoException {
            try {
                return mSocket.recvMessage(0);
            } catch (InterruptedIOException e) {
                // Interruption or other error, e.g. another thread closed our file descriptor.
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EAGAIN) {
                    throw e;
                }
            }
            return null;
        }

        private void parseNetlinkMessageBuffer(ByteBuffer byteBuffer, long whenMs) {
            while (byteBuffer.remaining() > 0) {
                final int position = byteBuffer.position();
                final NetlinkMessage nlMsg = NetlinkMessage.parse(byteBuffer);
                if (nlMsg == null || nlMsg.getHeader() == null) {
                    byteBuffer.position(position);
                    Log.e(TAG, "unparsable netlink msg: " + NetlinkConstants.hexify(byteBuffer));
                    break;
                }

                final int srcPortId = nlMsg.getHeader().nlmsg_pid;
                if (srcPortId !=  0) {
                    Log.e(TAG, "non-kernel source portId: " + ((long) (srcPortId & 0xffffffff)));
                    break;
                }

                if (nlMsg instanceof NetlinkErrorMessage) {
                    Log.e(TAG, "netlink error: " + nlMsg);
                    continue;
                } else if (!(nlMsg instanceof RtNetlinkNeighborMessage)) {
                    if (DBG) {
                        Log.d(TAG, "non-rtnetlink neighbor msg: " + nlMsg);
                    }
                    continue;
                }

                evaluateRtNetlinkNeighborMessage((RtNetlinkNeighborMessage) nlMsg, whenMs);
            }
        }

        private void evaluateRtNetlinkNeighborMessage(
                RtNetlinkNeighborMessage neighMsg, long whenMs) {
            final StructNdMsg ndMsg = neighMsg.getNdHeader();
            if (ndMsg == null || ndMsg.ndm_ifindex != mInterfaceIndex) {
                return;
            }

            final InetAddress destination = neighMsg.getDestination();
            if (!isWatching(destination)) {
                return;
            }

            final short msgType = neighMsg.getHeader().nlmsg_type;
            final short nudState = ndMsg.ndm_state;
            final String eventMsg = "NeighborEvent{"
                    + "elapsedMs=" + whenMs + ", "
                    + destination.getHostAddress() + ", "
                    + "[" + NetlinkConstants.hexify(neighMsg.getLinkLayerAddress()) + "], "
                    + NetlinkConstants.stringForNlMsgType(msgType) + ", "
                    + StructNdMsg.stringForNudState(nudState)
                    + "}";

            if (VDBG) {
                Log.d(TAG, neighMsg.toString());
            } else if (DBG) {
                Log.d(TAG, eventMsg);
            }

            synchronized (mLock) {
                if (mIpWatchList.containsKey(destination)) {
                    final short value =
                            (msgType == NetlinkConstants.RTM_DELNEIGH)
                            ? StructNdMsg.NUD_NONE
                            : nudState;
                    mIpWatchList.put(destination, value);
                }
            }

            if (nudState == StructNdMsg.NUD_FAILED) {
                Log.w(TAG, "ALERT: " + eventMsg);
                handleNeighborLost(eventMsg);
            }
        }
    }
}
