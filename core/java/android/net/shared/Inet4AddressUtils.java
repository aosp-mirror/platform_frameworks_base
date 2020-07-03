/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.shared;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Collection of utilities to work with IPv4 addresses.
 * @hide
 */
public class Inet4AddressUtils {

    /**
     * Convert a IPv4 address from an integer to an InetAddress (0x04030201 -> 1.2.3.4)
     *
     * <p>This method uses the higher-order int bytes as the lower-order IPv4 address bytes,
     * which is an unusual convention. Consider {@link #intToInet4AddressHTH(int)} instead.
     * @param hostAddress an int coding for an IPv4 address, where higher-order int byte is
     *                    lower-order IPv4 address byte
     */
    public static Inet4Address intToInet4AddressHTL(int hostAddress) {
        return intToInet4AddressHTH(Integer.reverseBytes(hostAddress));
    }

    /**
     * Convert a IPv4 address from an integer to an InetAddress (0x01020304 -> 1.2.3.4)
     * @param hostAddress an int coding for an IPv4 address
     */
    public static Inet4Address intToInet4AddressHTH(int hostAddress) {
        byte[] addressBytes = { (byte) (0xff & (hostAddress >> 24)),
                (byte) (0xff & (hostAddress >> 16)),
                (byte) (0xff & (hostAddress >> 8)),
                (byte) (0xff & hostAddress) };

        try {
            return (Inet4Address) InetAddress.getByAddress(addressBytes);
        } catch (UnknownHostException e) {
            throw new AssertionError();
        }
    }

    /**
     * Convert an IPv4 address from an InetAddress to an integer (1.2.3.4 -> 0x01020304)
     *
     * <p>This conversion can help order IP addresses: considering the ordering
     * 192.0.2.1 < 192.0.2.2 < ..., resulting ints will follow that ordering if read as unsigned
     * integers with {@link Integer#toUnsignedLong}.
     * @param inetAddr is an InetAddress corresponding to the IPv4 address
     * @return the IP address as integer
     */
    public static int inet4AddressToIntHTH(Inet4Address inetAddr)
            throws IllegalArgumentException {
        byte [] addr = inetAddr.getAddress();
        return ((addr[0] & 0xff) << 24) | ((addr[1] & 0xff) << 16)
                | ((addr[2] & 0xff) << 8) | (addr[3] & 0xff);
    }

    /**
     * Convert a IPv4 address from an InetAddress to an integer (1.2.3.4 -> 0x04030201)
     *
     * <p>This method stores the higher-order IPv4 address bytes in the lower-order int bytes,
     * which is an unusual convention. Consider {@link #inet4AddressToIntHTH(Inet4Address)} instead.
     * @param inetAddr is an InetAddress corresponding to the IPv4 address
     * @return the IP address as integer
     */
    public static int inet4AddressToIntHTL(Inet4Address inetAddr) {
        return Integer.reverseBytes(inet4AddressToIntHTH(inetAddr));
    }

    /**
     * Convert a network prefix length to an IPv4 netmask integer (prefixLength 17 -> 0xffff8000)
     * @return the IPv4 netmask as an integer
     */
    public static int prefixLengthToV4NetmaskIntHTH(int prefixLength)
            throws IllegalArgumentException {
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("Invalid prefix length (0 <= prefix <= 32)");
        }
        // (int)a << b is equivalent to a << (b & 0x1f): can't shift by 32 (-1 << 32 == -1)
        return prefixLength == 0 ? 0 : 0xffffffff << (32 - prefixLength);
    }

    /**
     * Convert a network prefix length to an IPv4 netmask integer (prefixLength 17 -> 0x0080ffff).
     *
     * <p>This method stores the higher-order IPv4 address bytes in the lower-order int bytes,
     * which is an unusual convention. Consider {@link #prefixLengthToV4NetmaskIntHTH(int)} instead.
     * @return the IPv4 netmask as an integer
     */
    public static int prefixLengthToV4NetmaskIntHTL(int prefixLength)
            throws IllegalArgumentException {
        return Integer.reverseBytes(prefixLengthToV4NetmaskIntHTH(prefixLength));
    }

    /**
     * Convert an IPv4 netmask to a prefix length, checking that the netmask is contiguous.
     * @param netmask as a {@code Inet4Address}.
     * @return the network prefix length
     * @throws IllegalArgumentException the specified netmask was not contiguous.
     * @hide
     */
    public static int netmaskToPrefixLength(Inet4Address netmask) {
        // inetAddressToInt returns an int in *network* byte order.
        int i = inet4AddressToIntHTH(netmask);
        int prefixLength = Integer.bitCount(i);
        int trailingZeros = Integer.numberOfTrailingZeros(i);
        if (trailingZeros != 32 - prefixLength) {
            throw new IllegalArgumentException("Non-contiguous netmask: " + Integer.toHexString(i));
        }
        return prefixLength;
    }

    /**
     * Returns the implicit netmask of an IPv4 address, as was the custom before 1993.
     */
    public static int getImplicitNetmask(Inet4Address address) {
        int firstByte = address.getAddress()[0] & 0xff;  // Convert to an unsigned value.
        if (firstByte < 128) {
            return 8;
        } else if (firstByte < 192) {
            return 16;
        } else if (firstByte < 224) {
            return 24;
        } else {
            return 32;  // Will likely not end well for other reasons.
        }
    }

    /**
     * Get the broadcast address for a given prefix.
     *
     * <p>For example 192.168.0.1/24 -> 192.168.0.255
     */
    public static Inet4Address getBroadcastAddress(Inet4Address addr, int prefixLength)
            throws IllegalArgumentException {
        final int intBroadcastAddr = inet4AddressToIntHTH(addr)
                | ~prefixLengthToV4NetmaskIntHTH(prefixLength);
        return intToInet4AddressHTH(intBroadcastAddr);
    }

    /**
     * Get a prefix mask as Inet4Address for a given prefix length.
     *
     * <p>For example 20 -> 255.255.240.0
     */
    public static Inet4Address getPrefixMaskAsInet4Address(int prefixLength)
            throws IllegalArgumentException {
        return intToInet4AddressHTH(prefixLengthToV4NetmaskIntHTH(prefixLength));
    }
}
