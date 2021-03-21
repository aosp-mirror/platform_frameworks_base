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

package android.net.util;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.InetAddresses;
import android.net.Network;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * @hide
 */
public class DnsUtils {
    private static final String TAG = "DnsUtils";
    private static final int CHAR_BIT = 8;
    public static final int IPV6_ADDR_SCOPE_NODELOCAL = 0x01;
    public static final int IPV6_ADDR_SCOPE_LINKLOCAL = 0x02;
    public static final int IPV6_ADDR_SCOPE_SITELOCAL = 0x05;
    public static final int IPV6_ADDR_SCOPE_GLOBAL = 0x0e;
    private static final Comparator<SortableAddress> sRfc6724Comparator = new Rfc6724Comparator();

    /**
     * Comparator to sort SortableAddress in Rfc6724 style.
     */
    public static class Rfc6724Comparator implements Comparator<SortableAddress> {
        // This function matches the behaviour of _rfc6724_compare in the native resolver.
        @Override
        public int compare(SortableAddress span1, SortableAddress span2) {
            // Rule 1: Avoid unusable destinations.
            if (span1.hasSrcAddr != span2.hasSrcAddr) {
                return span2.hasSrcAddr - span1.hasSrcAddr;
            }

            // Rule 2: Prefer matching scope.
            if (span1.scopeMatch != span2.scopeMatch) {
                return span2.scopeMatch - span1.scopeMatch;
            }

            // TODO: Implement rule 3: Avoid deprecated addresses.
            // TODO: Implement rule 4: Prefer home addresses.

            // Rule 5: Prefer matching label.
            if (span1.labelMatch != span2.labelMatch) {
                return span2.labelMatch - span1.labelMatch;
            }

            // Rule 6: Prefer higher precedence.
            if (span1.precedence != span2.precedence) {
                return span2.precedence - span1.precedence;
            }

            // TODO: Implement rule 7: Prefer native transport.

            // Rule 8: Prefer smaller scope.
            if (span1.scope != span2.scope) {
                return span1.scope - span2.scope;
            }

            // Rule 9: Use longest matching prefix. IPv6 only.
            if (span1.prefixMatchLen != span2.prefixMatchLen) {
                return span2.prefixMatchLen - span1.prefixMatchLen;
            }

            // Rule 10: Leave the order unchanged. Collections.sort is a stable sort.
            return 0;
        }
    }

    /**
     * Class used to sort with RFC 6724
     */
    public static class SortableAddress {
        public final int label;
        public final int labelMatch;
        public final int scope;
        public final int scopeMatch;
        public final int precedence;
        public final int prefixMatchLen;
        public final int hasSrcAddr;
        public final InetAddress address;

        public SortableAddress(@NonNull InetAddress addr, @Nullable InetAddress srcAddr) {
            address = addr;
            hasSrcAddr = (srcAddr != null) ? 1 : 0;
            label = findLabel(addr);
            scope = findScope(addr);
            precedence = findPrecedence(addr);
            labelMatch = ((srcAddr != null) && (label == findLabel(srcAddr))) ? 1 : 0;
            scopeMatch = ((srcAddr != null) && (scope == findScope(srcAddr))) ? 1 : 0;
            if (isIpv6Address(addr) && isIpv6Address(srcAddr)) {
                prefixMatchLen = compareIpv6PrefixMatchLen(srcAddr, addr);
            } else {
                prefixMatchLen = 0;
            }
        }
    }

    /**
     * Sort the given address list in RFC6724 order.
     * Will leave the list unchanged if an error occurs.
     *
     * This function matches the behaviour of _rfc6724_sort in the native resolver.
     */
    public static @NonNull List<InetAddress> rfc6724Sort(@Nullable Network network,
            @NonNull List<InetAddress> answers) {
        final ArrayList<SortableAddress> sortableAnswerList = new ArrayList<>();
        for (InetAddress addr : answers) {
            sortableAnswerList.add(new SortableAddress(addr, findSrcAddress(network, addr)));
        }

        Collections.sort(sortableAnswerList, sRfc6724Comparator);

        final List<InetAddress> sortedAnswers = new ArrayList<>();
        for (SortableAddress ans : sortableAnswerList) {
            sortedAnswers.add(ans.address);
        }

        return sortedAnswers;
    }

    private static @Nullable InetAddress findSrcAddress(@Nullable Network network,
            @NonNull InetAddress addr) {
        final int domain;
        if (isIpv4Address(addr)) {
            domain = AF_INET;
        } else if (isIpv6Address(addr)) {
            domain = AF_INET6;
        } else {
            return null;
        }
        final FileDescriptor socket;
        try {
            socket = Os.socket(domain, SOCK_DGRAM, IPPROTO_UDP);
        } catch (ErrnoException e) {
            Log.e(TAG, "findSrcAddress:" + e.toString());
            return null;
        }
        try {
            if (network != null) network.bindSocket(socket);
            Os.connect(socket, new InetSocketAddress(addr, 0));
            return ((InetSocketAddress) Os.getsockname(socket)).getAddress();
        } catch (IOException | ErrnoException e) {
            return null;
        } finally {
            IoUtils.closeQuietly(socket);
        }
    }

    /**
     * Get the label for a given IPv4/IPv6 address.
     * RFC 6724, section 2.1.
     *
     * Note that Java will return an IPv4-mapped address as an IPv4 address.
     */
    private static int findLabel(@NonNull InetAddress addr) {
        if (isIpv4Address(addr)) {
            return 4;
        } else if (isIpv6Address(addr)) {
            if (addr.isLoopbackAddress()) {
                return 0;
            } else if (isIpv6Address6To4(addr)) {
                return 2;
            } else if (isIpv6AddressTeredo(addr)) {
                return 5;
            } else if (isIpv6AddressULA(addr)) {
                return 13;
            } else if (((Inet6Address) addr).isIPv4CompatibleAddress()) {
                return 3;
            } else if (addr.isSiteLocalAddress()) {
                return 11;
            } else if (isIpv6Address6Bone(addr)) {
                return 12;
            } else {
                // All other IPv6 addresses, including global unicast addresses.
                return 1;
            }
        } else {
            // This should never happen.
            return 1;
        }
    }

    private static boolean isIpv6Address(@Nullable InetAddress addr) {
        return addr instanceof Inet6Address;
    }

    private static boolean isIpv4Address(@Nullable InetAddress addr) {
        return addr instanceof Inet4Address;
    }

    private static boolean isIpv6Address6To4(@NonNull InetAddress addr) {
        if (!isIpv6Address(addr)) return false;
        final byte[] byteAddr = addr.getAddress();
        return byteAddr[0] == 0x20 && byteAddr[1] == 0x02;
    }

    private static boolean isIpv6AddressTeredo(@NonNull InetAddress addr) {
        if (!isIpv6Address(addr)) return false;
        final byte[] byteAddr = addr.getAddress();
        return byteAddr[0] == 0x20 && byteAddr[1] == 0x01 && byteAddr[2] == 0x00
                && byteAddr[3] == 0x00;
    }

    private static boolean isIpv6AddressULA(@NonNull InetAddress addr) {
        return isIpv6Address(addr) && (addr.getAddress()[0] & 0xfe) == 0xfc;
    }

    private static boolean isIpv6Address6Bone(@NonNull InetAddress addr) {
        if (!isIpv6Address(addr)) return false;
        final byte[] byteAddr = addr.getAddress();
        return byteAddr[0] == 0x3f && byteAddr[1] == (byte) 0xfe;
    }

    private static int getIpv6MulticastScope(@NonNull InetAddress addr) {
        return !isIpv6Address(addr) ? 0 : (addr.getAddress()[1] & 0x0f);
    }

    private static int findScope(@NonNull InetAddress addr) {
        if (isIpv6Address(addr)) {
            if (addr.isMulticastAddress()) {
                return getIpv6MulticastScope(addr);
            } else if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                /**
                 * RFC 4291 section 2.5.3 says loopback is to be treated as having
                 * link-local scope.
                 */
                return IPV6_ADDR_SCOPE_LINKLOCAL;
            } else if (addr.isSiteLocalAddress()) {
                return IPV6_ADDR_SCOPE_SITELOCAL;
            } else {
                return IPV6_ADDR_SCOPE_GLOBAL;
            }
        } else if (isIpv4Address(addr)) {
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                return IPV6_ADDR_SCOPE_LINKLOCAL;
            } else {
                /**
                 * RFC 6724 section 3.2. Other IPv4 addresses, including private addresses
                 * and shared addresses (100.64.0.0/10), are assigned global scope.
                 */
                return IPV6_ADDR_SCOPE_GLOBAL;
            }
        } else {
            /**
             * This should never happen.
             * Return a scope with low priority as a last resort.
             */
            return IPV6_ADDR_SCOPE_NODELOCAL;
        }
    }

    /**
     * Get the precedence for a given IPv4/IPv6 address.
     * RFC 6724, section 2.1.
     *
     * Note that Java will return an IPv4-mapped address as an IPv4 address.
     */
    private static int findPrecedence(@NonNull InetAddress addr) {
        if (isIpv4Address(addr)) {
            return 35;
        } else if (isIpv6Address(addr)) {
            if (addr.isLoopbackAddress()) {
                return 50;
            } else if (isIpv6Address6To4(addr)) {
                return 30;
            } else if (isIpv6AddressTeredo(addr)) {
                return 5;
            } else if (isIpv6AddressULA(addr)) {
                return 3;
            } else if (((Inet6Address) addr).isIPv4CompatibleAddress() || addr.isSiteLocalAddress()
                    || isIpv6Address6Bone(addr)) {
                return 1;
            } else {
                // All other IPv6 addresses, including global unicast addresses.
                return 40;
            }
        } else {
            return 1;
        }
    }

    /**
     * Find number of matching initial bits between the two addresses.
     */
    private static int compareIpv6PrefixMatchLen(@NonNull InetAddress srcAddr,
            @NonNull InetAddress dstAddr) {
        final byte[] srcByte = srcAddr.getAddress();
        final byte[] dstByte = dstAddr.getAddress();

        // This should never happen.
        if (srcByte.length != dstByte.length) return 0;

        for (int i = 0; i < dstByte.length; ++i) {
            if (srcByte[i] == dstByte[i]) {
                continue;
            }
            int x = (srcByte[i] & 0xff) ^ (dstByte[i] & 0xff);
            return i * CHAR_BIT + (Integer.numberOfLeadingZeros(x) - 24);  // Java ints are 32 bits
        }
        return dstByte.length * CHAR_BIT;
    }

    /**
     * Check if given network has Ipv4 capability
     * This function matches the behaviour of have_ipv4 in the native resolver.
     */
    public static boolean haveIpv4(@Nullable Network network) {
        final SocketAddress addrIpv4 =
                new InetSocketAddress(InetAddresses.parseNumericAddress("8.8.8.8"), 0);
        return checkConnectivity(network, AF_INET, addrIpv4);
    }

    /**
     * Check if given network has Ipv6 capability
     * This function matches the behaviour of have_ipv6 in the native resolver.
     */
    public static boolean haveIpv6(@Nullable Network network) {
        final SocketAddress addrIpv6 =
                new InetSocketAddress(InetAddresses.parseNumericAddress("2000::"), 0);
        return checkConnectivity(network, AF_INET6, addrIpv6);
    }

    private static boolean checkConnectivity(@Nullable Network network,
            int domain, @NonNull SocketAddress addr) {
        final FileDescriptor socket;
        try {
            socket = Os.socket(domain, SOCK_DGRAM, IPPROTO_UDP);
        } catch (ErrnoException e) {
            return false;
        }
        try {
            if (network != null) network.bindSocket(socket);
            Os.connect(socket, addr);
        } catch (IOException | ErrnoException e) {
            return false;
        } finally {
            IoUtils.closeQuietly(socket);
        }
        return true;
    }
}
