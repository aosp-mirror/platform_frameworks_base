/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.net.dhcp.DhcpPacket;
import android.net.MacAddress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.StringJoiner;

import static android.system.OsConstants.*;
import static android.net.util.NetworkConstants.*;


/**
 * Critical connectivity packet summarizing class.
 *
 * Outputs short descriptions of ARP, DHCPv4, and IPv6 RS/RA/NS/NA packets.
 *
 * @hide
 */
public class ConnectivityPacketSummary {
    private static final String TAG = ConnectivityPacketSummary.class.getSimpleName();

    private final byte[] mHwAddr;
    private final byte[] mBytes;
    private final int mLength;
    private final ByteBuffer mPacket;
    private final String mSummary;

    public static String summarize(MacAddress hwaddr, byte[] buffer) {
        return summarize(hwaddr, buffer, buffer.length);
    }

    // Methods called herein perform some but by no means all error checking.
    // They may throw runtime exceptions on malformed packets.
    public static String summarize(MacAddress macAddr, byte[] buffer, int length) {
        if ((macAddr == null) || (buffer == null)) return null;
        length = Math.min(length, buffer.length);
        return (new ConnectivityPacketSummary(macAddr, buffer, length)).toString();
    }

    private ConnectivityPacketSummary(MacAddress macAddr, byte[] buffer, int length) {
        mHwAddr = macAddr.toByteArray();
        mBytes = buffer;
        mLength = Math.min(length, mBytes.length);
        mPacket = ByteBuffer.wrap(mBytes, 0, mLength);
        mPacket.order(ByteOrder.BIG_ENDIAN);

        final StringJoiner sj = new StringJoiner(" ");
        // TODO: support other link-layers, or even no link-layer header.
        parseEther(sj);
        mSummary = sj.toString();
    }

    public String toString() {
        return mSummary;
    }

    private void parseEther(StringJoiner sj) {
        if (mPacket.remaining() < ETHER_HEADER_LEN) {
            sj.add("runt:").add(asString(mPacket.remaining()));
            return;
        }

        mPacket.position(ETHER_SRC_ADDR_OFFSET);
        final ByteBuffer srcMac = (ByteBuffer) mPacket.slice().limit(ETHER_ADDR_LEN);
        sj.add(ByteBuffer.wrap(mHwAddr).equals(srcMac) ? "TX" : "RX");
        sj.add(getMacAddressString(srcMac));

        mPacket.position(ETHER_DST_ADDR_OFFSET);
        final ByteBuffer dstMac = (ByteBuffer) mPacket.slice().limit(ETHER_ADDR_LEN);
        sj.add(">").add(getMacAddressString(dstMac));

        mPacket.position(ETHER_TYPE_OFFSET);
        final int etherType = asUint(mPacket.getShort());
        switch (etherType) {
            case ETHER_TYPE_ARP:
                sj.add("arp");
                parseARP(sj);
                break;
            case ETHER_TYPE_IPV4:
                sj.add("ipv4");
                parseIPv4(sj);
                break;
            case ETHER_TYPE_IPV6:
                sj.add("ipv6");
                parseIPv6(sj);
                break;
            default:
                // Unknown ether type.
                sj.add("ethtype").add(asString(etherType));
                break;
        }
    }

    private void parseARP(StringJoiner sj) {
        if (mPacket.remaining() < ARP_PAYLOAD_LEN) {
            sj.add("runt:").add(asString(mPacket.remaining()));
            return;
        }

        if (asUint(mPacket.getShort()) != ARP_HWTYPE_ETHER ||
            asUint(mPacket.getShort()) != ETHER_TYPE_IPV4 ||
            asUint(mPacket.get()) != ETHER_ADDR_LEN ||
            asUint(mPacket.get()) != IPV4_ADDR_LEN) {
            sj.add("unexpected header");
            return;
        }

        final int opCode = asUint(mPacket.getShort());

        final String senderHwAddr = getMacAddressString(mPacket);
        final String senderIPv4 = getIPv4AddressString(mPacket);
        getMacAddressString(mPacket);  // target hardware address, unused
        final String targetIPv4 = getIPv4AddressString(mPacket);

        if (opCode == ARP_REQUEST) {
            sj.add("who-has").add(targetIPv4);
        } else if (opCode == ARP_REPLY) {
            sj.add("reply").add(senderIPv4).add(senderHwAddr);
        } else {
            sj.add("unknown opcode").add(asString(opCode));
        }
    }

    private void parseIPv4(StringJoiner sj) {
        if (!mPacket.hasRemaining()) {
            sj.add("runt");
            return;
        }

        final int startOfIpLayer = mPacket.position();
        final int ipv4HeaderLength = (mPacket.get(startOfIpLayer) & IPV4_IHL_MASK) * 4;
        if (mPacket.remaining() < ipv4HeaderLength ||
            mPacket.remaining() < IPV4_HEADER_MIN_LEN) {
            sj.add("runt:").add(asString(mPacket.remaining()));
            return;
        }
        final int startOfTransportLayer = startOfIpLayer + ipv4HeaderLength;

        mPacket.position(startOfIpLayer + IPV4_FLAGS_OFFSET);
        final int flagsAndFragment = asUint(mPacket.getShort());
        final boolean isFragment = (flagsAndFragment & IPV4_FRAGMENT_MASK) != 0;

        mPacket.position(startOfIpLayer + IPV4_PROTOCOL_OFFSET);
        final int protocol = asUint(mPacket.get());

        mPacket.position(startOfIpLayer + IPV4_SRC_ADDR_OFFSET);
        final String srcAddr = getIPv4AddressString(mPacket);

        mPacket.position(startOfIpLayer + IPV4_DST_ADDR_OFFSET);
        final String dstAddr = getIPv4AddressString(mPacket);

        sj.add(srcAddr).add(">").add(dstAddr);

        mPacket.position(startOfTransportLayer);
        if (protocol == IPPROTO_UDP) {
            sj.add("udp");
            if (isFragment) sj.add("fragment");
            else parseUDP(sj);
        } else {
            sj.add("proto").add(asString(protocol));
            if (isFragment) sj.add("fragment");
        }
    }

    private void parseIPv6(StringJoiner sj) {
        if (mPacket.remaining() < IPV6_HEADER_LEN) {
            sj.add("runt:").add(asString(mPacket.remaining()));
            return;
        }

        final int startOfIpLayer = mPacket.position();

        mPacket.position(startOfIpLayer + IPV6_PROTOCOL_OFFSET);
        final int protocol = asUint(mPacket.get());

        mPacket.position(startOfIpLayer + IPV6_SRC_ADDR_OFFSET);
        final String srcAddr = getIPv6AddressString(mPacket);
        final String dstAddr = getIPv6AddressString(mPacket);

        sj.add(srcAddr).add(">").add(dstAddr);

        mPacket.position(startOfIpLayer + IPV6_HEADER_LEN);
        if (protocol == IPPROTO_ICMPV6) {
            sj.add("icmp6");
            parseICMPv6(sj);
        } else {
            sj.add("proto").add(asString(protocol));
        }
    }

    private void parseICMPv6(StringJoiner sj) {
        if (mPacket.remaining() < ICMPV6_HEADER_MIN_LEN) {
            sj.add("runt:").add(asString(mPacket.remaining()));
            return;
        }

        final int icmp6Type = asUint(mPacket.get());
        final int icmp6Code = asUint(mPacket.get());
        mPacket.getShort();  // checksum, unused

        switch (icmp6Type) {
            case ICMPV6_ROUTER_SOLICITATION:
                sj.add("rs");
                parseICMPv6RouterSolicitation(sj);
                break;
            case ICMPV6_ROUTER_ADVERTISEMENT:
                sj.add("ra");
                parseICMPv6RouterAdvertisement(sj);
                break;
            case ICMPV6_NEIGHBOR_SOLICITATION:
                sj.add("ns");
                parseICMPv6NeighborMessage(sj);
                break;
            case ICMPV6_NEIGHBOR_ADVERTISEMENT:
                sj.add("na");
                parseICMPv6NeighborMessage(sj);
                break;
            default:
                sj.add("type").add(asString(icmp6Type));
                sj.add("code").add(asString(icmp6Code));
                break;
        }
    }

    private void parseICMPv6RouterSolicitation(StringJoiner sj) {
        final int RESERVED = 4;
        if (mPacket.remaining() < RESERVED) {
            sj.add("runt:").add(asString(mPacket.remaining()));
            return;
        }

        mPacket.position(mPacket.position() + RESERVED);
        parseICMPv6NeighborDiscoveryOptions(sj);
    }

    private void parseICMPv6RouterAdvertisement(StringJoiner sj) {
        final int FLAGS_AND_TIMERS = 3 * 4;
        if (mPacket.remaining() < FLAGS_AND_TIMERS) {
            sj.add("runt:").add(asString(mPacket.remaining()));
            return;
        }

        mPacket.position(mPacket.position() + FLAGS_AND_TIMERS);
        parseICMPv6NeighborDiscoveryOptions(sj);
    }

    private void parseICMPv6NeighborMessage(StringJoiner sj) {
        final int RESERVED = 4;
        final int minReq = RESERVED + IPV6_ADDR_LEN;
        if (mPacket.remaining() < minReq) {
            sj.add("runt:").add(asString(mPacket.remaining()));
            return;
        }

        mPacket.position(mPacket.position() + RESERVED);
        sj.add(getIPv6AddressString(mPacket));
        parseICMPv6NeighborDiscoveryOptions(sj);
    }

    private void parseICMPv6NeighborDiscoveryOptions(StringJoiner sj) {
        // All ND options are TLV, where T is one byte and L is one byte equal
        // to the length of T + L + V in units of 8 octets.
        while (mPacket.remaining() >= ICMPV6_ND_OPTION_MIN_LENGTH) {
            final int ndType = asUint(mPacket.get());
            final int ndLength = asUint(mPacket.get());
            final int ndBytes = ndLength * ICMPV6_ND_OPTION_LENGTH_SCALING_FACTOR - 2;
            if (ndBytes < 0 || ndBytes > mPacket.remaining()) {
                sj.add("<malformed>");
                break;
            }
            final int position = mPacket.position();

            switch (ndType) {
                    case ICMPV6_ND_OPTION_SLLA:
                        sj.add("slla");
                        sj.add(getMacAddressString(mPacket));
                        break;
                    case ICMPV6_ND_OPTION_TLLA:
                        sj.add("tlla");
                        sj.add(getMacAddressString(mPacket));
                        break;
                    case ICMPV6_ND_OPTION_MTU:
                        sj.add("mtu");
                        final short reserved = mPacket.getShort();
                        sj.add(asString(mPacket.getInt()));
                        break;
                    default:
                        // Skip.
                        break;
            }

            mPacket.position(position + ndBytes);
        }
    }

    private void parseUDP(StringJoiner sj) {
        if (mPacket.remaining() < UDP_HEADER_LEN) {
            sj.add("runt:").add(asString(mPacket.remaining()));
            return;
        }

        final int previous = mPacket.position();
        final int srcPort = asUint(mPacket.getShort());
        final int dstPort = asUint(mPacket.getShort());
        sj.add(asString(srcPort)).add(">").add(asString(dstPort));

        mPacket.position(previous + UDP_HEADER_LEN);
        if (srcPort == DHCP4_CLIENT_PORT || dstPort == DHCP4_CLIENT_PORT) {
            sj.add("dhcp4");
            parseDHCPv4(sj);
        }
    }

    private void parseDHCPv4(StringJoiner sj) {
        final DhcpPacket dhcpPacket;
        try {
            dhcpPacket = DhcpPacket.decodeFullPacket(mBytes, mLength, DhcpPacket.ENCAP_L2);
            sj.add(dhcpPacket.toString());
        } catch (DhcpPacket.ParseException e) {
            sj.add("parse error: " + e);
        }
    }

    private static String getIPv4AddressString(ByteBuffer ipv4) {
        return getIpAddressString(ipv4, IPV4_ADDR_LEN);
    }

    private static String getIPv6AddressString(ByteBuffer ipv6) {
        return getIpAddressString(ipv6, IPV6_ADDR_LEN);
    }

    private static String getIpAddressString(ByteBuffer ip, int byteLength) {
        if (ip == null || ip.remaining() < byteLength) return "invalid";

        byte[] bytes = new byte[byteLength];
        ip.get(bytes, 0, byteLength);
        try {
            InetAddress addr = InetAddress.getByAddress(bytes);
            return addr.getHostAddress();
        } catch (UnknownHostException uhe) {
            return "unknown";
        }
    }

    private static String getMacAddressString(ByteBuffer mac) {
        if (mac == null || mac.remaining() < ETHER_ADDR_LEN) return "invalid";

        byte[] bytes = new byte[ETHER_ADDR_LEN];
        mac.get(bytes, 0, bytes.length);
        Object[] printableBytes = new Object[bytes.length];
        int i = 0;
        for (byte b : bytes) printableBytes[i++] = new Byte(b);

        final String MAC48_FORMAT = "%02x:%02x:%02x:%02x:%02x:%02x";
        return String.format(MAC48_FORMAT, printableBytes);
    }
}
