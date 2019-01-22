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

import android.annotation.SystemApi;
import android.net.NetworkUtils;
import android.system.ErrnoException;
import android.system.NetlinkSocketAddress;
import android.system.Os;
import android.system.PacketSocketAddress;

import java.io.FileDescriptor;
import java.net.SocketAddress;

/**
 * Collection of utilities to interact with raw sockets.
 * @hide
 */
@SystemApi
public class SocketUtils {
    /**
     * Create a raw datagram socket that is bound to an interface.
     *
     * <p>Data sent through the socket will go directly to the underlying network, ignoring VPNs.
     */
    public static void bindSocketToInterface(FileDescriptor socket, String iface)
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
    public static SocketAddress makeNetlinkSocketAddress(int portId, int groupsMask) {
        return new NetlinkSocketAddress(portId, groupsMask);
    }

    /**
     * Make a socket address to bind to packet sockets.
     */
    public static SocketAddress makePacketSocketAddress(short protocol, int ifIndex) {
        return new PacketSocketAddress(protocol, ifIndex);
    }

    /**
     * Make a socket address to send raw packets.
     */
    public static SocketAddress makePacketSocketAddress(int ifIndex, byte[] hwAddr) {
        return new PacketSocketAddress(ifIndex, hwAddr);
    }

    private SocketUtils() {}
}
