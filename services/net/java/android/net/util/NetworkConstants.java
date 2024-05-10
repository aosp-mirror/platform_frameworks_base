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

    public static final byte FF = asByte(0xff);
    public static final byte[] ETHER_ADDR_BROADCAST = {
        FF, FF, FF, FF, FF, FF
    };

    public static final int ETHER_MTU = 1500;

    /**
     * IPv4 constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc791
     */
    public static final int IPV4_ADDR_BITS = 32;

    /**
     * IPv6 constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc2460
     */
    public static final int IPV6_ADDR_BITS = 128;
    public static final int IPV6_ADDR_LEN = 16;
    public static final int IPV6_MIN_MTU = 1280;

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
    public static final int ICMPV6_ECHO_REQUEST_TYPE = 128;

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
}
