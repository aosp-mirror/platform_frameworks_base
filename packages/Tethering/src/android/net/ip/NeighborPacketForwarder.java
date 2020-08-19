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

package android.net.ip;

import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.AF_PACKET;
import static android.system.OsConstants.ETH_P_IPV6;
import static android.system.OsConstants.IPPROTO_RAW;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_NONBLOCK;
import static android.system.OsConstants.SOCK_RAW;

import android.net.util.InterfaceParams;
import android.net.util.SocketUtils;
import android.net.util.TetheringUtils;
import android.os.Handler;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.android.net.module.util.PacketReader;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Basic IPv6 Neighbor Advertisement Forwarder.
 *
 * Forward NA packets from upstream iface to tethered iface
 * and NS packets from tethered iface to upstream iface.
 *
 * @hide
 */
public class NeighborPacketForwarder extends PacketReader {
    private final String mTag;

    private FileDescriptor mFd;

    // TODO: get these from NetworkStackConstants.
    private static final int IPV6_ADDR_LEN = 16;
    private static final int IPV6_DST_ADDR_OFFSET = 24;
    private static final int IPV6_HEADER_LEN = 40;
    private static final int ETH_HEADER_LEN = 14;

    private InterfaceParams mListenIfaceParams, mSendIfaceParams;

    private final int mType;
    public static final int ICMPV6_NEIGHBOR_ADVERTISEMENT  = 136;
    public static final int ICMPV6_NEIGHBOR_SOLICITATION = 135;

    public NeighborPacketForwarder(Handler h, InterfaceParams tetheredInterface, int type) {
        super(h);
        mTag = NeighborPacketForwarder.class.getSimpleName() + "-"
                + tetheredInterface.name + "-" + type;
        mType = type;

        if (mType == ICMPV6_NEIGHBOR_ADVERTISEMENT) {
            mSendIfaceParams = tetheredInterface;
        } else {
            mListenIfaceParams = tetheredInterface;
        }
    }

    /** Set new upstream iface and start/stop based on new params. */
    public void setUpstreamIface(InterfaceParams upstreamParams) {
        final InterfaceParams oldUpstreamParams;

        if (mType == ICMPV6_NEIGHBOR_ADVERTISEMENT) {
            oldUpstreamParams = mListenIfaceParams;
            mListenIfaceParams = upstreamParams;
        } else {
            oldUpstreamParams = mSendIfaceParams;
            mSendIfaceParams = upstreamParams;
        }

        if (oldUpstreamParams == null && upstreamParams != null) {
            start();
        } else if (oldUpstreamParams != null && upstreamParams == null) {
            stop();
        } else if (oldUpstreamParams != null && upstreamParams != null
                   && oldUpstreamParams.index != upstreamParams.index) {
            stop();
            start();
        }
    }

    // TODO: move NetworkStackUtils.closeSocketQuietly to
    // frameworks/libs/net/common/device/com/android/net/module/util/[someclass].
    private void closeSocketQuietly(FileDescriptor fd) {
        try {
            SocketUtils.closeSocket(fd);
        } catch (IOException ignored) {
        }
    }

    @Override
    protected FileDescriptor createFd() {
        try {
            // ICMPv6 packets from modem do not have eth header, so RAW socket cannot be used.
            // To keep uniformity in both directions PACKET socket can be used.
            mFd = Os.socket(AF_PACKET, SOCK_DGRAM | SOCK_NONBLOCK, 0);

            // TODO: convert setup*Socket to setupICMPv6BpfFilter with filter type?
            if (mType == ICMPV6_NEIGHBOR_ADVERTISEMENT) {
                TetheringUtils.setupNaSocket(mFd);
            } else if (mType == ICMPV6_NEIGHBOR_SOLICITATION) {
                TetheringUtils.setupNsSocket(mFd);
            }

            SocketAddress bindAddress = SocketUtils.makePacketSocketAddress(
                                                        ETH_P_IPV6, mListenIfaceParams.index);
            Os.bind(mFd, bindAddress);
        } catch (ErrnoException | SocketException e) {
            Log.wtf(mTag, "Failed to create  socket", e);
            closeSocketQuietly(mFd);
            return null;
        }

        return mFd;
    }

    private Inet6Address getIpv6DestinationAddress(byte[] recvbuf) {
        Inet6Address dstAddr;
        try {
            dstAddr = (Inet6Address) Inet6Address.getByAddress(Arrays.copyOfRange(recvbuf,
                    IPV6_DST_ADDR_OFFSET, IPV6_DST_ADDR_OFFSET + IPV6_ADDR_LEN));
        } catch (UnknownHostException | ClassCastException impossible) {
            throw new AssertionError("16-byte array not valid IPv6 address?");
        }
        return dstAddr;
    }

    @Override
    protected void handlePacket(byte[] recvbuf, int length) {
        if (mSendIfaceParams == null) {
            return;
        }

        // The BPF filter should already have checked the length of the packet, but...
        if (length < IPV6_HEADER_LEN) {
            return;
        }
        Inet6Address destv6 = getIpv6DestinationAddress(recvbuf);
        if (!destv6.isMulticastAddress()) {
            return;
        }
        InetSocketAddress dest = new InetSocketAddress(destv6, 0);

        FileDescriptor fd = null;
        try {
            fd = Os.socket(AF_INET6, SOCK_RAW | SOCK_NONBLOCK, IPPROTO_RAW);
            SocketUtils.bindSocketToInterface(fd, mSendIfaceParams.name);

            int ret = Os.sendto(fd, recvbuf, 0, length, 0, dest);
        } catch (ErrnoException | SocketException e) {
            Log.e(mTag, "handlePacket error: " + e);
        } finally {
            closeSocketQuietly(fd);
        }
    }
}
