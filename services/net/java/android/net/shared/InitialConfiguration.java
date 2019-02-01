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

import static android.net.shared.ParcelableUtil.fromParcelableArray;
import static android.net.shared.ParcelableUtil.toParcelableArray;
import static android.text.TextUtils.join;

import android.net.InetAddresses;
import android.net.InitialConfigurationParcelable;
import android.net.IpPrefix;
import android.net.IpPrefixParcelable;
import android.net.LinkAddress;
import android.net.LinkAddressParcelable;
import android.net.RouteInfo;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** @hide */
public class InitialConfiguration {
    public final Set<LinkAddress> ipAddresses = new HashSet<>();
    public final Set<IpPrefix> directlyConnectedRoutes = new HashSet<>();
    public final Set<InetAddress> dnsServers = new HashSet<>();

    private static final int RFC6177_MIN_PREFIX_LENGTH = 48;
    private static final int RFC7421_PREFIX_LENGTH = 64;

    public static final InetAddress INET6_ANY = InetAddresses.parseNumericAddress("::");

    /**
     * Create a InitialConfiguration that is a copy of the specified configuration.
     */
    public static InitialConfiguration copy(InitialConfiguration config) {
        if (config == null) {
            return null;
        }
        InitialConfiguration configCopy = new InitialConfiguration();
        configCopy.ipAddresses.addAll(config.ipAddresses);
        configCopy.directlyConnectedRoutes.addAll(config.directlyConnectedRoutes);
        configCopy.dnsServers.addAll(config.dnsServers);
        return configCopy;
    }

    @Override
    public String toString() {
        return String.format(
                "InitialConfiguration(IPs: {%s}, prefixes: {%s}, DNS: {%s})",
                join(", ", ipAddresses), join(", ", directlyConnectedRoutes),
                join(", ", dnsServers));
    }

    /**
     * Tests whether the contents of this IpConfiguration represent a valid configuration.
     */
    public boolean isValid() {
        if (ipAddresses.isEmpty()) {
            return false;
        }

        // For every IP address, there must be at least one prefix containing that address.
        for (LinkAddress addr : ipAddresses) {
            if (!any(directlyConnectedRoutes, (p) -> p.contains(addr.getAddress()))) {
                return false;
            }
        }
        // For every dns server, there must be at least one prefix containing that address.
        for (InetAddress addr : dnsServers) {
            if (!any(directlyConnectedRoutes, (p) -> p.contains(addr))) {
                return false;
            }
        }
        // All IPv6 LinkAddresses have an RFC7421-suitable prefix length
        // (read: compliant with RFC4291#section2.5.4).
        if (any(ipAddresses, not(InitialConfiguration::isPrefixLengthCompliant))) {
            return false;
        }
        // If directlyConnectedRoutes contains an IPv6 default route
        // then ipAddresses MUST contain at least one non-ULA GUA.
        if (any(directlyConnectedRoutes, InitialConfiguration::isIPv6DefaultRoute)
                && all(ipAddresses, not(InitialConfiguration::isIPv6GUA))) {
            return false;
        }
        // The prefix length of routes in directlyConnectedRoutes be within reasonable
        // bounds for IPv6: /48-/64 just as we’d accept in RIOs.
        if (any(directlyConnectedRoutes, not(InitialConfiguration::isPrefixLengthCompliant))) {
            return false;
        }
        // There no more than one IPv4 address
        if (ipAddresses.stream().filter(InitialConfiguration::isIPv4).count() > 1) {
            return false;
        }

        return true;
    }

    /**
     * @return true if the given list of addressess and routes satisfies provisioning for this
     * InitialConfiguration. LinkAddresses and RouteInfo objects are not compared with equality
     * because addresses and routes seen by Netlink will contain additional fields like flags,
     * interfaces, and so on. If this InitialConfiguration has no IP address specified, the
     * provisioning check always fails.
     *
     * If the given list of routes is null, only addresses are taken into considerations.
     */
    public boolean isProvisionedBy(List<LinkAddress> addresses, List<RouteInfo> routes) {
        if (ipAddresses.isEmpty()) {
            return false;
        }

        for (LinkAddress addr : ipAddresses) {
            if (!any(addresses, (addrSeen) -> addr.isSameAddressAs(addrSeen))) {
                return false;
            }
        }

        if (routes != null) {
            for (IpPrefix prefix : directlyConnectedRoutes) {
                if (!any(routes, (routeSeen) -> isDirectlyConnectedRoute(routeSeen, prefix))) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Convert this configuration to a {@link InitialConfigurationParcelable}.
     */
    public InitialConfigurationParcelable toStableParcelable() {
        final InitialConfigurationParcelable p = new InitialConfigurationParcelable();
        p.ipAddresses = toParcelableArray(ipAddresses,
                LinkPropertiesParcelableUtil::toStableParcelable, LinkAddressParcelable.class);
        p.directlyConnectedRoutes = toParcelableArray(directlyConnectedRoutes,
                LinkPropertiesParcelableUtil::toStableParcelable, IpPrefixParcelable.class);
        p.dnsServers = toParcelableArray(
                dnsServers, IpConfigurationParcelableUtil::parcelAddress, String.class);
        return p;
    }

    /**
     * Create an instance of {@link InitialConfiguration} based on the contents of the specified
     * {@link InitialConfigurationParcelable}.
     */
    public static InitialConfiguration fromStableParcelable(InitialConfigurationParcelable p) {
        if (p == null) return null;
        final InitialConfiguration config = new InitialConfiguration();
        config.ipAddresses.addAll(fromParcelableArray(
                p.ipAddresses, LinkPropertiesParcelableUtil::fromStableParcelable));
        config.directlyConnectedRoutes.addAll(fromParcelableArray(
                p.directlyConnectedRoutes, LinkPropertiesParcelableUtil::fromStableParcelable));
        config.dnsServers.addAll(
                fromParcelableArray(p.dnsServers, IpConfigurationParcelableUtil::unparcelAddress));
        return config;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof InitialConfiguration)) return false;
        final InitialConfiguration other = (InitialConfiguration) obj;
        return ipAddresses.equals(other.ipAddresses)
                && directlyConnectedRoutes.equals(other.directlyConnectedRoutes)
                && dnsServers.equals(other.dnsServers);
    }

    private static boolean isDirectlyConnectedRoute(RouteInfo route, IpPrefix prefix) {
        return !route.hasGateway() && prefix.equals(route.getDestination());
    }

    private static boolean isPrefixLengthCompliant(LinkAddress addr) {
        return isIPv4(addr) || isCompliantIPv6PrefixLength(addr.getPrefixLength());
    }

    private static boolean isPrefixLengthCompliant(IpPrefix prefix) {
        return isIPv4(prefix) || isCompliantIPv6PrefixLength(prefix.getPrefixLength());
    }

    private static boolean isCompliantIPv6PrefixLength(int prefixLength) {
        return (RFC6177_MIN_PREFIX_LENGTH <= prefixLength)
                && (prefixLength <= RFC7421_PREFIX_LENGTH);
    }

    private static boolean isIPv4(IpPrefix prefix) {
        return prefix.getAddress() instanceof Inet4Address;
    }

    private static boolean isIPv4(LinkAddress addr) {
        return addr.getAddress() instanceof Inet4Address;
    }

    private static boolean isIPv6DefaultRoute(IpPrefix prefix) {
        return prefix.getAddress().equals(INET6_ANY);
    }

    private static boolean isIPv6GUA(LinkAddress addr) {
        return addr.isIPv6() && addr.isGlobalPreferred();
    }

    // TODO: extract out into CollectionUtils.

    /**
     * Indicate whether any element of the specified iterable verifies the specified predicate.
     */
    public static <T> boolean any(Iterable<T> coll, Predicate<T> fn) {
        for (T t : coll) {
            if (fn.test(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Indicate whether all elements of the specified iterable verifies the specified predicate.
     */
    public static <T> boolean all(Iterable<T> coll, Predicate<T> fn) {
        return !any(coll, not(fn));
    }

    /**
     * Create a predicate that returns the opposite value of the specified predicate.
     */
    public static <T> Predicate<T> not(Predicate<T> fn) {
        return (t) -> !fn.test(t);
    }
}
