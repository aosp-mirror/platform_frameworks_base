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

package android.net.util;

import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;


/**
 * @hide
 */
public class PrefixUtils {
    private static final IpPrefix[] MIN_NON_FORWARDABLE_PREFIXES = {
            pfx("127.0.0.0/8"),     // IPv4 loopback
            pfx("169.254.0.0/16"),  // IPv4 link-local, RFC3927#section-8
            pfx("::/3"),
            pfx("fe80::/64"),       // IPv6 link-local
            pfx("fc00::/7"),        // IPv6 ULA
            pfx("ff02::/8"),        // IPv6 link-local multicast
    };

    public static final IpPrefix DEFAULT_WIFI_P2P_PREFIX = pfx("192.168.49.0/24");

    /** Get non forwardable prefixes. */
    public static Set<IpPrefix> getNonForwardablePrefixes() {
        final HashSet<IpPrefix> prefixes = new HashSet<>();
        addNonForwardablePrefixes(prefixes);
        return prefixes;
    }

    /** Add non forwardable prefixes. */
    public static void addNonForwardablePrefixes(Set<IpPrefix> prefixes) {
        Collections.addAll(prefixes, MIN_NON_FORWARDABLE_PREFIXES);
    }

    /** Get local prefixes from |lp|. */
    public static Set<IpPrefix> localPrefixesFrom(LinkProperties lp) {
        final HashSet<IpPrefix> localPrefixes = new HashSet<>();
        if (lp == null) return localPrefixes;

        for (LinkAddress addr : lp.getAllLinkAddresses()) {
            if (addr.getAddress().isLinkLocalAddress()) continue;
            localPrefixes.add(asIpPrefix(addr));
        }
        // TODO: Add directly-connected routes as well (ones from which we did
        // not also form a LinkAddress)?

        return localPrefixes;
    }

    /** Convert LinkAddress |addr| to IpPrefix. */
    public static IpPrefix asIpPrefix(LinkAddress addr) {
        return new IpPrefix(addr.getAddress(), addr.getPrefixLength());
    }

    /** Convert InetAddress |ip| to IpPrefix. */
    public static IpPrefix ipAddressAsPrefix(InetAddress ip) {
        final int bitLength = (ip instanceof Inet4Address)
                ? NetworkConstants.IPV4_ADDR_BITS
                : NetworkConstants.IPV6_ADDR_BITS;
        return new IpPrefix(ip, bitLength);
    }

    private static IpPrefix pfx(String prefixStr) {
        return new IpPrefix(prefixStr);
    }
}
