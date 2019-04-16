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

package android.net.ip;

import static android.net.netlink.NetlinkConstants.RTM_DELNEIGH;
import static android.net.netlink.NetlinkConstants.hexify;
import static android.net.netlink.NetlinkConstants.stringForNlMsgType;
import static android.net.util.SocketUtils.makeNetlinkSocketAddress;
import static android.system.OsConstants.AF_NETLINK;
import static android.system.OsConstants.NETLINK_ROUTE;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_NONBLOCK;

import android.net.MacAddress;
import android.net.netlink.NetlinkErrorMessage;
import android.net.netlink.NetlinkMessage;
import android.net.netlink.NetlinkSocket;
import android.net.netlink.RtNetlinkNeighborMessage;
import android.net.netlink.StructNdMsg;
import android.net.util.NetworkStackUtils;
import android.net.util.PacketReader;
import android.net.util.SharedLog;
import android.os.Handler;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.StringJoiner;


/**
 * IpNeighborMonitor.
 *
 * Monitors the kernel rtnetlink neighbor notifications and presents to callers
 * NeighborEvents describing each event. Callers can provide a consumer instance
 * to both filter (e.g. by interface index and IP address) and handle the
 * generated NeighborEvents.
 *
 * @hide
 */
public class IpNeighborMonitor extends PacketReader {
    private static final String TAG = IpNeighborMonitor.class.getSimpleName();
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    /**
     * Make the kernel perform neighbor reachability detection (IPv4 ARP or IPv6 ND)
     * for the given IP address on the specified interface index.
     *
     * @return 0 if the request was successfully passed to the kernel; otherwise return
     *         a non-zero error code.
     */
    public static int startKernelNeighborProbe(int ifIndex, InetAddress ip) {
        final String msgSnippet = "probing ip=" + ip.getHostAddress() + "%" + ifIndex;
        if (DBG) { Log.d(TAG, msgSnippet); }

        final byte[] msg = RtNetlinkNeighborMessage.newNewNeighborMessage(
                1, ip, StructNdMsg.NUD_PROBE, ifIndex, null);

        try {
            NetlinkSocket.sendOneShotKernelMessage(NETLINK_ROUTE, msg);
        } catch (ErrnoException e) {
            Log.e(TAG, "Error " + msgSnippet + ": " + e);
            return -e.errno;
        }

        return 0;
    }

    public static class NeighborEvent {
        final long elapsedMs;
        final short msgType;
        final int ifindex;
        final InetAddress ip;
        final short nudState;
        final MacAddress macAddr;

        public NeighborEvent(long elapsedMs, short msgType, int ifindex, InetAddress ip,
                short nudState, MacAddress macAddr) {
            this.elapsedMs = elapsedMs;
            this.msgType = msgType;
            this.ifindex = ifindex;
            this.ip = ip;
            this.nudState = nudState;
            this.macAddr = macAddr;
        }

        boolean isConnected() {
            return (msgType != RTM_DELNEIGH) && StructNdMsg.isNudStateConnected(nudState);
        }

        boolean isValid() {
            return (msgType != RTM_DELNEIGH) && StructNdMsg.isNudStateValid(nudState);
        }

        @Override
        public String toString() {
            final StringJoiner j = new StringJoiner(",", "NeighborEvent{", "}");
            return j.add("@" + elapsedMs)
                    .add(stringForNlMsgType(msgType))
                    .add("if=" + ifindex)
                    .add(ip.getHostAddress())
                    .add(StructNdMsg.stringForNudState(nudState))
                    .add("[" + macAddr + "]")
                    .toString();
        }
    }

    public interface NeighborEventConsumer {
        // Every neighbor event received on the netlink socket is passed in
        // here. Subclasses should filter for events of interest.
        public void accept(NeighborEvent event);
    }

    private final SharedLog mLog;
    private final NeighborEventConsumer mConsumer;

    public IpNeighborMonitor(Handler h, SharedLog log, NeighborEventConsumer cb) {
        super(h, NetlinkSocket.DEFAULT_RECV_BUFSIZE);
        mLog = log.forSubComponent(TAG);
        mConsumer = (cb != null) ? cb : (event) -> { /* discard */ };
    }

    @Override
    protected FileDescriptor createFd() {
        FileDescriptor fd = null;

        try {
            fd = Os.socket(AF_NETLINK, SOCK_DGRAM | SOCK_NONBLOCK, NETLINK_ROUTE);
            Os.bind(fd, makeNetlinkSocketAddress(0, OsConstants.RTMGRP_NEIGH));
            NetlinkSocket.connectToKernel(fd);

            if (VDBG) {
                final SocketAddress nlAddr = Os.getsockname(fd);
                Log.d(TAG, "bound to sockaddr_nl{" + nlAddr.toString() + "}");
            }
        } catch (ErrnoException|SocketException e) {
            logError("Failed to create rtnetlink socket", e);
            NetworkStackUtils.closeSocketQuietly(fd);
            return null;
        }

        return fd;
    }

    @Override
    protected void handlePacket(byte[] recvbuf, int length) {
        final long whenMs = SystemClock.elapsedRealtime();

        final ByteBuffer byteBuffer = ByteBuffer.wrap(recvbuf, 0, length);
        byteBuffer.order(ByteOrder.nativeOrder());

        parseNetlinkMessageBuffer(byteBuffer, whenMs);
    }

    private void parseNetlinkMessageBuffer(ByteBuffer byteBuffer, long whenMs) {
        while (byteBuffer.remaining() > 0) {
            final int position = byteBuffer.position();
            final NetlinkMessage nlMsg = NetlinkMessage.parse(byteBuffer);
            if (nlMsg == null || nlMsg.getHeader() == null) {
                byteBuffer.position(position);
                mLog.e("unparsable netlink msg: " + hexify(byteBuffer));
                break;
            }

            final int srcPortId = nlMsg.getHeader().nlmsg_pid;
            if (srcPortId !=  0) {
                mLog.e("non-kernel source portId: " + Integer.toUnsignedLong(srcPortId));
                break;
            }

            if (nlMsg instanceof NetlinkErrorMessage) {
                mLog.e("netlink error: " + nlMsg);
                continue;
            } else if (!(nlMsg instanceof RtNetlinkNeighborMessage)) {
                mLog.i("non-rtnetlink neighbor msg: " + nlMsg);
                continue;
            }

            evaluateRtNetlinkNeighborMessage((RtNetlinkNeighborMessage) nlMsg, whenMs);
        }
    }

    private void evaluateRtNetlinkNeighborMessage(
            RtNetlinkNeighborMessage neighMsg, long whenMs) {
        final short msgType = neighMsg.getHeader().nlmsg_type;
        final StructNdMsg ndMsg = neighMsg.getNdHeader();
        if (ndMsg == null) {
            mLog.e("RtNetlinkNeighborMessage without ND message header!");
            return;
        }

        final int ifindex = ndMsg.ndm_ifindex;
        final InetAddress destination = neighMsg.getDestination();
        final short nudState =
                (msgType == RTM_DELNEIGH)
                ? StructNdMsg.NUD_NONE
                : ndMsg.ndm_state;

        final NeighborEvent event = new NeighborEvent(
                whenMs, msgType, ifindex, destination, nudState,
                getMacAddress(neighMsg.getLinkLayerAddress()));

        if (VDBG) {
            Log.d(TAG, neighMsg.toString());
        }
        if (DBG) {
            Log.d(TAG, event.toString());
        }

        mConsumer.accept(event);
    }

    private static MacAddress getMacAddress(byte[] linkLayerAddress) {
        if (linkLayerAddress != null) {
            try {
                return MacAddress.fromBytes(linkLayerAddress);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to parse link-layer address: " + hexify(linkLayerAddress));
            }
        }

        return null;
    }
}
