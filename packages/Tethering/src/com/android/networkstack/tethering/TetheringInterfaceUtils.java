/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.networkstack.tethering;

import android.annotation.Nullable;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.util.InterfaceSet;

import com.android.net.module.util.NetUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @hide
 */
public final class TetheringInterfaceUtils {
    private static final InetAddress IN6ADDR_ANY = getByAddress(
            new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    private static final InetAddress INADDR_ANY = getByAddress(new byte[] {0, 0, 0, 0});

    /**
     * Get upstream interfaces for tethering based on default routes for IPv4/IPv6.
     * @return null if there is no usable interface, or a set of at least one interface otherwise.
     */
    public static @Nullable InterfaceSet getTetheringInterfaces(UpstreamNetworkState ns) {
        if (ns == null) {
            return null;
        }

        final LinkProperties lp = ns.linkProperties;
        final String if4 = getInterfaceForDestination(lp, INADDR_ANY);
        final String if6 = getIPv6Interface(ns);

        return (if4 == null && if6 == null) ? null : new InterfaceSet(if4, if6);
    }

    /**
     * Get the upstream interface for IPv6 tethering.
     * @return null if there is no usable interface, or the interface name otherwise.
     */
    public static @Nullable String getIPv6Interface(UpstreamNetworkState ns) {
        // Broadly speaking:
        //
        //     [1] does the upstream have an IPv6 default route?
        //
        // and
        //
        //     [2] does the upstream have one or more global IPv6 /64s
        //         dedicated to this device?
        //
        // In lieu of Prefix Delegation and other evaluation of whether a
        // prefix may or may not be dedicated to this device, for now just
        // check whether the upstream is TRANSPORT_CELLULAR. This works
        // because "[t]he 3GPP network allocates each default bearer a unique
        // /64 prefix", per RFC 6459, Section 5.2.
        final boolean canTether =
                (ns != null) && (ns.network != null)
                && (ns.linkProperties != null) && (ns.networkCapabilities != null)
                // At least one upstream DNS server:
                && ns.linkProperties.hasIpv6DnsServer()
                // Minimal amount of IPv6 provisioning:
                && ns.linkProperties.hasGlobalIpv6Address()
                // Temporary approximation of "dedicated prefix":
                && ns.networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);

        return canTether
                ? getInterfaceForDestination(ns.linkProperties, IN6ADDR_ANY)
                : null;
    }

    private static String getInterfaceForDestination(LinkProperties lp, InetAddress dst) {
        final RouteInfo ri = (lp != null)
                ? NetUtils.selectBestRoute(lp.getAllRoutes(), dst)
                : null;
        return (ri != null) ? ri.getInterface() : null;
    }

    private static InetAddress getByAddress(final byte[] addr) {
        try {
            return InetAddress.getByAddress(null, addr);
        } catch (UnknownHostException e) {
            throw new AssertionError("illegal address length" + addr.length);
        }
    }
}
