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

package com.android.server.connectivity;

import android.system.OsConstants;
import android.net.ConnectivityManager;
import android.net.NetworkUtils;
import android.net.util.IpUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static android.net.ConnectivityManager.PacketKeepalive.*;

/**
 * Represents the actual packets that are sent by the
 * {@link android.net.ConnectivityManager.PacketKeepalive} API.
 *
 * @hide
 */
public class KeepalivePacketData {
    /** Protocol of the packet to send; one of the OsConstants.ETH_P_* values. */
    public final int protocol;

    /** Source IP address */
    public final InetAddress srcAddress;

    /** Destination IP address */
    public final InetAddress dstAddress;

    /** Source port */
    public final int srcPort;

    /** Destination port */
    public final int dstPort;

    /** Destination MAC address. Can change if routing changes. */
    public byte[] dstMac;

    /** Packet data. A raw byte string of packet data, not including the link-layer header. */
    public final byte[] data;

    private static final int IPV4_HEADER_LENGTH = 20;
    private static final int UDP_HEADER_LENGTH = 8;

    protected KeepalivePacketData(InetAddress srcAddress, int srcPort,
            InetAddress dstAddress, int dstPort, byte[] data) throws InvalidPacketException {
        this.srcAddress = srcAddress;
        this.dstAddress = dstAddress;
        this.srcPort = srcPort;
        this.dstPort = dstPort;
        this.data = data;

        // Check we have two IP addresses of the same family.
        if (srcAddress == null || dstAddress == null ||
                !srcAddress.getClass().getName().equals(dstAddress.getClass().getName())) {
            throw new InvalidPacketException(ERROR_INVALID_IP_ADDRESS);
        }

        // Set the protocol.
        if (this.dstAddress instanceof Inet4Address) {
            this.protocol = OsConstants.ETH_P_IP;
        } else if (this.dstAddress instanceof Inet6Address) {
            this.protocol = OsConstants.ETH_P_IPV6;
        } else {
            throw new InvalidPacketException(ERROR_INVALID_IP_ADDRESS);
        }

        // Check the ports.
        if (!IpUtils.isValidUdpOrTcpPort(srcPort) || !IpUtils.isValidUdpOrTcpPort(dstPort)) {
            throw new InvalidPacketException(ERROR_INVALID_PORT);
        }
    }

    public static class InvalidPacketException extends Exception {
        final public int error;
        public InvalidPacketException(int error) {
            this.error = error;
        }
    }

    /**
     * Creates an IPsec NAT-T keepalive packet with the specified parameters.
     */
    public static KeepalivePacketData nattKeepalivePacket(
            InetAddress srcAddress, int srcPort,
            InetAddress dstAddress, int dstPort) throws InvalidPacketException {

        if (!(srcAddress instanceof Inet4Address) || !(dstAddress instanceof Inet4Address)) {
            throw new InvalidPacketException(ERROR_INVALID_IP_ADDRESS);
        }

        if (dstPort != NATT_PORT) {
            throw new InvalidPacketException(ERROR_INVALID_PORT);
        }

        int length = IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH + 1;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) 0x4500);             // IP version and TOS
        buf.putShort((short) length);
        buf.putInt(0);                            // ID, flags, offset
        buf.put((byte) 64);                       // TTL
        buf.put((byte) OsConstants.IPPROTO_UDP);
        int ipChecksumOffset = buf.position();
        buf.putShort((short) 0);                  // IP checksum
        buf.put(srcAddress.getAddress());
        buf.put(dstAddress.getAddress());
        buf.putShort((short) srcPort);
        buf.putShort((short) dstPort);
        buf.putShort((short) (length - 20));      // UDP length
        int udpChecksumOffset = buf.position();
        buf.putShort((short) 0);                  // UDP checksum
        buf.put((byte) 0xff);                     // NAT-T keepalive
        buf.putShort(ipChecksumOffset, IpUtils.ipChecksum(buf, 0));
        buf.putShort(udpChecksumOffset, IpUtils.udpChecksum(buf, 0, IPV4_HEADER_LENGTH));

        return new KeepalivePacketData(srcAddress, srcPort, dstAddress, dstPort, buf.array());
    }
}
