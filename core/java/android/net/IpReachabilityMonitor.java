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

package android.net;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.netlink.NetlinkConstants;
import android.net.netlink.NetlinkErrorMessage;
import android.net.netlink.NetlinkMessage;
import android.net.netlink.NetlinkSocket;
import android.net.netlink.RtNetlinkNeighborMessage;
import android.net.netlink.StructNdaCacheInfo;
import android.net.netlink.StructNdMsg;
import android.net.netlink.StructNlMsgHdr;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.NetlinkSocketAddress;
import android.system.OsConstants;
import android.text.TextUtils;
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
import java.util.Set;


/**
 * IpReachabilityMonitor.
 *
 * Monitors on-link IP reachability and notifies callers whenever any on-link
 * addresses of interest appear to have become unresponsive.
 *
 * @hide
 */
public class IpReachabilityMonitor {
    private static final String TAG = "IpReachabilityMonitor";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    public interface Callback {
        public void notifyLost(InetAddress ip, String logMsg);
    }

    private final Object mLock = new Object();
    private final String mInterfaceName;
    private final int mInterfaceIndex;
    private final Callback mCallback;
    private final Set<InetAddress> mIpWatchList;
    private int mIpWatchListVersion;
    private boolean mRunning;
    private final NetlinkSocketObserver mNetlinkSocketObserver;
    private final Thread mObserverThread;

    /**
     * Make the kernel to perform neighbor reachability detection (IPv4 ARP or IPv6 ND)
     * for the given IP address on the specified interface index.
     *
     * @return true, if the request was successfully passed to the kernel; false otherwise.
     */
    public static boolean probeNeighbor(int ifIndex, InetAddress ip) {
        final long IO_TIMEOUT = 300L;
        // This currently does not cause neighbor probing if the target |ip|
        // has been confirmed reachable within the past "delay_probe_time"
        // seconds, i.e. within the past 5 seconds.
        //
        // TODO: replace with a transition directly to NUD_PROBE state once
        // kernels are updated to do so correctly.
        if (DBG) { Log.d(TAG, "Probing ip=" + ip.getHostAddress()); }

        final byte[] msg = RtNetlinkNeighborMessage.newNewNeighborMessage(
                1, ip, StructNdMsg.NUD_DELAY, ifIndex, null);
        NetlinkSocket nlSocket = null;
        boolean returnValue = false;

        try {
            nlSocket = new NetlinkSocket(OsConstants.NETLINK_ROUTE);
            nlSocket.connectToKernel();
            nlSocket.sendMessage(msg, 0, msg.length, IO_TIMEOUT);
            final ByteBuffer bytes = nlSocket.recvMessage(IO_TIMEOUT);
            final NetlinkMessage response = NetlinkMessage.parse(bytes);
            if (response != null && response instanceof NetlinkErrorMessage &&
                    (((NetlinkErrorMessage) response).getNlMsgError() != null) &&
                    (((NetlinkErrorMessage) response).getNlMsgError().error == 0)) {
                returnValue = true;
            } else {
                String errmsg;
                if (bytes == null) {
                    errmsg = "null recvMessage";
                } else if (response == null) {
                    bytes.position(0);
                    errmsg = "raw bytes: " + NetlinkConstants.hexify(bytes);
                } else {
                    errmsg = response.toString();
                }
                Log.e(TAG, "Error probing ip=" + ip.getHostAddress() +
                        ", errmsg=" + errmsg);
            }
        } catch (ErrnoException | InterruptedIOException | SocketException e) {
            Log.d(TAG, "Error probing ip=" + ip.getHostAddress(), e);
        }

        if (nlSocket != null) {
            nlSocket.close();
        }
        return returnValue;
    }

    public IpReachabilityMonitor(String ifName, Callback callback) throws IllegalArgumentException {
        mInterfaceName = ifName;
        int ifIndex = -1;
        try {
            NetworkInterface netIf = NetworkInterface.getByName(ifName);
            mInterfaceIndex = netIf.getIndex();
        } catch (SocketException | NullPointerException e) {
            throw new IllegalArgumentException("invalid interface '" + ifName + "': ", e);
        }
        mCallback = callback;
        mIpWatchList = new HashSet<InetAddress>();
        mIpWatchListVersion = 0;
        mRunning = false;
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

    private static Set<InetAddress> getOnLinkNeighbors(LinkProperties lp) {
        Set<InetAddress> allIps = new HashSet<InetAddress>();

        final List<RouteInfo> routes = lp.getRoutes();
        for (RouteInfo route : routes) {
            if (route.hasGateway()) {
                allIps.add(route.getGateway());
            }
        }

        for (InetAddress nameserver : lp.getDnsServers()) {
            allIps.add(nameserver);
        }

        try {
            // Don't block here for DNS lookups.  If the proxy happens to be an
            // IP literal then we add it the list, but otherwise skip it.
            allIps.add(NetworkUtils.numericToInetAddress(lp.getHttpProxy().getHost()));
        } catch (NullPointerException|IllegalArgumentException e) {
            // No proxy, PAC proxy, or proxy is not a literal IP address.
        }

        Set<InetAddress> neighbors = new HashSet<InetAddress>();
        for (InetAddress ip : allIps) {
            // TODO: consider using the prefixes of the LinkAddresses instead
            // of the routes--it may be more accurate.
            for (RouteInfo route : routes) {
                if (route.hasGateway()) {
                    continue;  // Not directly connected.
                }
                if (route.matches(ip)) {
                    neighbors.add(ip);
                    break;
                }
            }
        }
        return neighbors;
    }

    private String describeWatchList() {
        synchronized (mLock) {
            return "version{" + mIpWatchListVersion + "}, " +
                    "ips=[" + TextUtils.join(",", mIpWatchList) + "]";
        }
    }

    private boolean isWatching(InetAddress ip) {
        synchronized (mLock) {
            return mRunning && mIpWatchList.contains(ip);
        }
    }

    private boolean stillRunning() {
        synchronized (mLock) {
            return mRunning;
        }
    }

    public void updateLinkProperties(LinkProperties lp) {
        if (!mInterfaceName.equals(lp.getInterfaceName())) {
            // TODO: figure out how to cope with interface changes.
            Log.wtf(TAG, "requested LinkProperties interface '" + lp.getInterfaceName() +
                    "' does not match: " + mInterfaceName);
            return;
        }

        // We rely upon the caller to determine when LinkProperties have actually
        // changed and call this at the appropriate time.  Note that even though
        // the LinkProperties may change, the set of on-link neighbors might not.
        //
        // Nevertheless, just clear and re-add everything.
        final Set<InetAddress> neighbors = getOnLinkNeighbors(lp);
        if (neighbors.isEmpty()) {
            return;
        }

        synchronized (mLock) {
            mIpWatchList.clear();
            mIpWatchList.addAll(neighbors);
            mIpWatchListVersion++;
        }
        if (DBG) { Log.d(TAG, "watch: " + describeWatchList()); }
    }

    public void clearLinkProperties() {
        synchronized (mLock) {
            mIpWatchList.clear();
            mIpWatchListVersion++;
        }
        if (DBG) { Log.d(TAG, "clear: " + describeWatchList()); }
    }

    private void notifyLost(InetAddress ip, String msg) {
        if (!isWatching(ip)) {
            // Ignore stray notifications.  This can happen when, for example,
            // several neighbors are reported unreachable or deleted
            // back-to-back.  Because these messages are parsed serially, and
            // this method is called for each notification, the caller above us
            // may have already processed an earlier lost notification and
            // cleared the watch list as it moves to handle the situation.
            return;
        }
        Log.w(TAG, "ALERT: " + ip.getHostAddress() + " -- " + msg);
        if (mCallback != null) {
            mCallback.notifyLost(ip, msg);
        }
    }

    public void probeAll() {
        Set<InetAddress> ipProbeList = new HashSet<InetAddress>();
        synchronized (mLock) {
            ipProbeList.addAll(mIpWatchList);
        }
        for (InetAddress target : ipProbeList) {
            if (!stillRunning()) {
                break;
            }
            probeNeighbor(mInterfaceIndex, target);
        }
    }


    // TODO: simply the number of objects by making this extend Thread.
    private final class NetlinkSocketObserver implements Runnable {
        private static final String TAG = "NetlinkSocketObserver";
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
                    Log.w(TAG, "ErrnoException: ", e);
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

            if ((msgType == NetlinkConstants.RTM_DELNEIGH) ||
                (nudState == StructNdMsg.NUD_FAILED)) {
                final String logMsg = "FAILURE: " + eventMsg;
                notifyLost(destination, logMsg);
            }
        }
    }
}
