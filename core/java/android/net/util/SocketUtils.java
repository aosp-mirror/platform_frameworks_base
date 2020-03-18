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

package android.net.util;

import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_BINDTODEVICE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.net.NetworkUtils;
import android.system.ErrnoException;
import android.system.NetlinkSocketAddress;
import android.system.Os;
import android.system.PacketSocketAddress;

import libcore.io.IoBridge;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.SocketAddress;

/**
 * Collection of utilities to interact with raw sockets.
 * @hide
 */
@SystemApi
@TestApi
public final class SocketUtils {
    /**
     * Create a raw datagram socket that is bound to an interface.
     *
     * <p>Data sent through the socket will go directly to the underlying network, ignoring VPNs.
     */
    public static void bindSocketToInterface(@NonNull FileDescriptor socket, @NonNull String iface)
            throws ErrnoException {
        // SO_BINDTODEVICE actually takes a string. This works because the first member
        // of struct ifreq is a NULL-terminated interface name.
        // TODO: add a setsockoptString()
        Os.setsockoptIfreq(socket, SOL_SOCKET, SO_BINDTODEVICE, iface);
        NetworkUtils.protectFromVpn(socket);
    }

    /**
     * Make a socket address to communicate with netlink.
     */
    @NonNull
    public static SocketAddress makeNetlinkSocketAddress(int portId, int groupsMask) {
        return new NetlinkSocketAddress(portId, groupsMask);
    }

    /**
     * Make socket address that packet sockets can bind to.
     *
     * @param protocol the layer 2 protocol of the packets to receive. One of the {@code ETH_P_*}
     *                 constants in {@link android.system.OsConstants}.
     * @param ifIndex the interface index on which packets will be received.
     */
    @NonNull
    public static SocketAddress makePacketSocketAddress(int protocol, int ifIndex) {
        return new PacketSocketAddress(
                protocol /* sll_protocol */,
                ifIndex /* sll_ifindex */,
                null /* sll_addr */);
    }

    /**
     * Make a socket address that packet socket can send packets to.
     * @deprecated Use {@link #makePacketSocketAddress(int, int, byte[])} instead.
     *
     * @param ifIndex the interface index on which packets will be sent.
     * @param hwAddr the hardware address to which packets will be sent.
     */
    @Deprecated
    @NonNull
    public static SocketAddress makePacketSocketAddress(int ifIndex, @NonNull byte[] hwAddr) {
        return new PacketSocketAddress(
                0 /* sll_protocol */,
                ifIndex /* sll_ifindex */,
                hwAddr /* sll_addr */);
    }

    /**
     * Make a socket address that a packet socket can send packets to.
     *
     * @param protocol the layer 2 protocol of the packets to send. One of the {@code ETH_P_*}
     *                 constants in {@link android.system.OsConstants}.
     * @param ifIndex the interface index on which packets will be sent.
     * @param hwAddr the hardware address to which packets will be sent.
     */
    @NonNull
    public static SocketAddress makePacketSocketAddress(int protocol, int ifIndex,
            @NonNull byte[] hwAddr) {
        return new PacketSocketAddress(
                protocol /* sll_protocol */,
                ifIndex /* sll_ifindex */,
                hwAddr /* sll_addr */);
    }

    /**
     * @see IoBridge#closeAndSignalBlockedThreads(FileDescriptor)
     */
    public static void closeSocket(@Nullable FileDescriptor fd) throws IOException {
        IoBridge.closeAndSignalBlockedThreads(fd);
    }

    private SocketUtils() {}
}
