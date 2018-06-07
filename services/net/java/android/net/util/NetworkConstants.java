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

import java.nio.ByteBuffer;


/**
 * Networking protocol constants.
 *
 * Includes:
 *     - constants that describe packet layout
 *     - various helper functions
 *
 * @hide
 */
public final class NetworkConstants {
    private NetworkConstants() { throw new RuntimeException("no instance permitted"); }

    /**
     * Ethernet constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc894
     *     - https://tools.ietf.org/html/rfc2464
     *     - https://tools.ietf.org/html/rfc7042
     *     - http://www.iana.org/assignments/ethernet-numbers/ethernet-numbers.xhtml
     *     - http://www.iana.org/assignments/ieee-802-numbers/ieee-802-numbers.xhtml
     */
    public static final int ETHER_DST_ADDR_OFFSET = 0;
    public static final int ETHER_SRC_ADDR_OFFSET = 6;
    public static final int ETHER_ADDR_LEN = 6;

    public static final int ETHER_TYPE_OFFSET = 12;
    public static final int ETHER_TYPE_LENGTH = 2;
    public static final int ETHER_TYPE_ARP  = 0x0806;
    public static final int ETHER_TYPE_IPV4 = 0x0800;
    public static final int ETHER_TYPE_IPV6 = 0x86dd;

    public static final int ETHER_HEADER_LEN = 14;

    public static final byte FF = asByte(0xff);
    public static final byte[] ETHER_ADDR_BROADCAST = {
        FF, FF, FF, FF, FF, FF
    };

    public static final int ETHER_MTU = 1500;

    /**
     * ARP constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc826
     *     - http://www.iana.org/assignments/arp-parameters/arp-parameters.xhtml
     */
    public static final int ARP_PAYLOAD_LEN = 28;  // For Ethernet+IPv4.
    public static final int ARP_REQUEST = 1;
    public static final int ARP_REPLY   = 2;
    public static final int ARP_HWTYPE_RESERVED_LO = 0;
    public static final int ARP_HWTYPE_ETHER       = 1;
    public static final int ARP_HWTYPE_RESERVED_HI = 0xffff;

    /**
     * IPv4 constants.
     *
     * See als:
     *     - https://tools.ietf.org/html/rfc791
     */
    public static final int IPV4_HEADER_MIN_LEN = 20;
    public static final int IPV4_IHL_MASK = 0xf;
    public static final int IPV4_FLAGS_OFFSET = 6;
    public static final int IPV4_FRAGMENT_MASK = 0x1fff;
    public static final int IPV4_PROTOCOL_OFFSET = 9;
    public static final int IPV4_SRC_ADDR_OFFSET = 12;
    public static final int IPV4_DST_ADDR_OFFSET = 16;
    public static final int IPV4_ADDR_BITS = 32;
    public static final int IPV4_ADDR_LEN = 4;

    /**
     * IPv6 constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc2460
     */
    public static final int IPV6_HEADER_LEN = 40;
    public static final int IPV6_PROTOCOL_OFFSET = 6;
    public static final int IPV6_SRC_ADDR_OFFSET = 8;
    public static final int IPV6_DST_ADDR_OFFSET = 24;
    public static final int IPV6_ADDR_BITS = 128;
    public static final int IPV6_ADDR_LEN = 16;
    public static final int IPV6_MIN_MTU = 1280;
    public static final int RFC7421_PREFIX_LENGTH = 64;
    public static final int RFC6177_MIN_PREFIX_LENGTH = 48;

    /**
     * ICMP common (v4/v6) constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc792
     *     - https://tools.ietf.org/html/rfc4443
     */
    public static final int ICMP_HEADER_TYPE_OFFSET = 0;
    public static final int ICMP_HEADER_CODE_OFFSET = 1;
    public static final int ICMP_HEADER_CHECKSUM_OFFSET = 2;
    public static final int ICMP_ECHO_IDENTIFIER_OFFSET = 4;
    public static final int ICMP_ECHO_SEQUENCE_NUMBER_OFFSET = 6;
    public static final int ICMP_ECHO_DATA_OFFSET = 8;

    /**
     * ICMPv4 constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc792
     */
    public static final int ICMPV4_ECHO_REQUEST_TYPE = 8;

    /**
     * ICMPv6 constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc4443
     *     - https://tools.ietf.org/html/rfc4861
     */
    public static final int ICMPV6_HEADER_MIN_LEN = 4;
    public static final int ICMPV6_ECHO_REQUEST_TYPE = 128;
    public static final int ICMPV6_ECHO_REPLY_TYPE = 129;
    public static final int ICMPV6_ROUTER_SOLICITATION    = 133;
    public static final int ICMPV6_ROUTER_ADVERTISEMENT   = 134;
    public static final int ICMPV6_NEIGHBOR_SOLICITATION  = 135;
    public static final int ICMPV6_NEIGHBOR_ADVERTISEMENT = 136;

    public static final int ICMPV6_ND_OPTION_MIN_LENGTH = 8;
    public static final int ICMPV6_ND_OPTION_LENGTH_SCALING_FACTOR = 8;
    public static final int ICMPV6_ND_OPTION_SLLA = 1;
    public static final int ICMPV6_ND_OPTION_TLLA = 2;
    public static final int ICMPV6_ND_OPTION_MTU  = 5;


    /**
     * UDP constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc768
     */
    public static final int UDP_HEADER_LEN = 8;

    /**
     * DHCP(v4) constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc2131
     */
    public static final int DHCP4_SERVER_PORT = 67;
    public static final int DHCP4_CLIENT_PORT = 68;

    /**
     * DNS constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc1035
     */
    public static final int DNS_SERVER_PORT = 53;

    /**
     * Utility functions.
     */
    public static byte asByte(int i) { return (byte) i; }

    public static String asString(int i) { return Integer.toString(i); }

    public static int asUint(byte b) { return (b & 0xff); }
    public static int asUint(short s) { return (s & 0xffff); }
}
