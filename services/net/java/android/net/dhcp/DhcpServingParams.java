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

package android.net.dhcp;

import static android.net.NetworkUtils.getPrefixMaskAsInet4Address;
import static android.net.dhcp.DhcpPacket.INFINITE_LEASE;
import static android.net.util.NetworkConstants.IPV4_MAX_MTU;
import static android.net.util.NetworkConstants.IPV4_MIN_MTU;

import static java.lang.Integer.toUnsignedLong;

import android.annotation.NonNull;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.NetworkUtils;

import com.google.android.collect.Sets;

import java.net.Inet4Address;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Parameters used by the DhcpServer to serve requests.
 *
 * <p>Instances are immutable. Use {@link DhcpServingParams.Builder} to instantiate.
 * @hide
 */
public class DhcpServingParams {
    public static final int MTU_UNSET = 0;
    public static final int MIN_PREFIX_LENGTH = 16;
    public static final int MAX_PREFIX_LENGTH = 30;

    /** Server inet address and prefix to serve */
    @NonNull
    public final LinkAddress serverAddr;

    /**
     * Default routers to be advertised to DHCP clients. May be empty.
     * This set is provided by {@link DhcpServingParams.Builder} and is immutable.
     */
    @NonNull
    public final Set<Inet4Address> defaultRouters;

    /**
     * DNS servers to be advertised to DHCP clients. May be empty.
     * This set is provided by {@link DhcpServingParams.Builder} and is immutable.
     */
    @NonNull
    public final Set<Inet4Address> dnsServers;

    /**
     * Excluded addresses that the DHCP server is not allowed to assign to clients.
     * This set is provided by {@link DhcpServingParams.Builder} and is immutable.
     */
    @NonNull
    public final Set<Inet4Address> excludedAddrs;

    // DHCP uses uint32. Use long for clearer code, and check range when building.
    public final long dhcpLeaseTimeSecs;
    public final int linkMtu;

    /**
     * Checked exception thrown when some parameters used to build {@link DhcpServingParams} are
     * missing or invalid.
     */
    public static class InvalidParameterException extends Exception {
        public InvalidParameterException(String message) {
            super(message);
        }
    }

    private DhcpServingParams(@NonNull LinkAddress serverAddr,
            @NonNull Set<Inet4Address> defaultRouters,
            @NonNull Set<Inet4Address> dnsServers, @NonNull Set<Inet4Address> excludedAddrs,
            long dhcpLeaseTimeSecs, int linkMtu) {
        this.serverAddr = serverAddr;
        this.defaultRouters = defaultRouters;
        this.dnsServers = dnsServers;
        this.excludedAddrs = excludedAddrs;
        this.dhcpLeaseTimeSecs = dhcpLeaseTimeSecs;
        this.linkMtu = linkMtu;
    }

    @NonNull
    public Inet4Address getServerInet4Addr() {
        return (Inet4Address) serverAddr.getAddress();
    }

    /**
     * Get the served prefix mask as an IPv4 address.
     *
     * <p>For example, if the served prefix is 192.168.42.0/24, this will return 255.255.255.0.
     */
    @NonNull
    public Inet4Address getPrefixMaskAsAddress() {
        return getPrefixMaskAsInet4Address(serverAddr.getPrefixLength());
    }

    /**
     * Get the server broadcast address.
     *
     * <p>For example, if the server {@link LinkAddress} is 192.168.42.1/24, this will return
     * 192.168.42.255.
     */
    @NonNull
    public Inet4Address getBroadcastAddress() {
        return NetworkUtils.getBroadcastAddress(getServerInet4Addr(), serverAddr.getPrefixLength());
    }

    /**
     * Utility class to create new instances of {@link DhcpServingParams} while checking validity
     * of the parameters.
     */
    public static class Builder {
        private LinkAddress serverAddr;
        private Set<Inet4Address> defaultRouters;
        private Set<Inet4Address> dnsServers;
        private Set<Inet4Address> excludedAddrs;
        private long dhcpLeaseTimeSecs;
        private int linkMtu = MTU_UNSET;

        /**
         * Set the server address and served prefix for the DHCP server.
         *
         * <p>This parameter is required.
         */
        public Builder setServerAddr(@NonNull LinkAddress serverAddr) {
            this.serverAddr = serverAddr;
            return this;
        }

        /**
         * Set the default routers to be advertised to DHCP clients.
         *
         * <p>Each router must be inside the served prefix. This may be an empty set, but it must
         * always be set explicitly before building the {@link DhcpServingParams}.
         */
        public Builder setDefaultRouters(@NonNull Set<Inet4Address> defaultRouters) {
            this.defaultRouters = defaultRouters;
            return this;
        }

        /**
         * Set the default routers to be advertised to DHCP clients.
         *
         * <p>Each router must be inside the served prefix. This may be an empty list of routers,
         * but it must always be set explicitly before building the {@link DhcpServingParams}.
         */
        public Builder setDefaultRouters(@NonNull Inet4Address... defaultRouters) {
            return setDefaultRouters(Sets.newArraySet(defaultRouters));
        }

        /**
         * Convenience method to build the parameters with no default router.
         *
         * <p>Equivalent to calling {@link #setDefaultRouters(Inet4Address...)} with no address.
         */
        public Builder withNoDefaultRouter() {
            return setDefaultRouters();
        }

        /**
         * Set the DNS servers to be advertised to DHCP clients.
         *
         * <p>This may be an empty set, but it must always be set explicitly before building the
         * {@link DhcpServingParams}.
         */
        public Builder setDnsServers(@NonNull Set<Inet4Address> dnsServers) {
            this.dnsServers = dnsServers;
            return this;
        }

        /**
         * Set the DNS servers to be advertised to DHCP clients.
         *
         * <p>This may be an empty list of servers, but it must always be set explicitly before
         * building the {@link DhcpServingParams}.
         */
        public Builder setDnsServers(@NonNull Inet4Address... dnsServers) {
            return setDnsServers(Sets.newArraySet(dnsServers));
        }

        /**
         * Convenience method to build the parameters with no DNS server.
         *
         * <p>Equivalent to calling {@link #setDnsServers(Inet4Address...)} with no address.
         */
        public Builder withNoDnsServer() {
            return setDnsServers();
        }

        /**
         * Set excluded addresses that the DHCP server is not allowed to assign to clients.
         *
         * <p>This parameter is optional. DNS servers and default routers are always excluded
         * and do not need to be set here.
         */
        public Builder setExcludedAddrs(@NonNull Set<Inet4Address> excludedAddrs) {
            this.excludedAddrs = excludedAddrs;
            return this;
        }

        /**
         * Set excluded addresses that the DHCP server is not allowed to assign to clients.
         *
         * <p>This parameter is optional. DNS servers and default routers are always excluded
         * and do not need to be set here.
         */
        public Builder setExcludedAddrs(@NonNull Inet4Address... excludedAddrs) {
            return setExcludedAddrs(Sets.newArraySet(excludedAddrs));
        }

        /**
         * Set the lease time for leases assigned by the DHCP server.
         *
         * <p>This parameter is required.
         */
        public Builder setDhcpLeaseTimeSecs(long dhcpLeaseTimeSecs) {
            this.dhcpLeaseTimeSecs = dhcpLeaseTimeSecs;
            return this;
        }

        /**
         * Set the link MTU to be advertised to DHCP clients.
         *
         * <p>If set to {@link #MTU_UNSET}, no MTU will be advertised to clients. This parameter
         * is optional and defaults to {@link #MTU_UNSET}.
         */
        public Builder setLinkMtu(int linkMtu) {
            this.linkMtu = linkMtu;
            return this;
        }

        /**
         * Create a new {@link DhcpServingParams} instance based on parameters set in the builder.
         *
         * <p>This method has no side-effects. If it does not throw, a valid
         * {@link DhcpServingParams} is returned.
         * @return The constructed parameters.
         * @throws InvalidParameterException At least one parameter is missing or invalid.
         */
        @NonNull
        public DhcpServingParams build() throws InvalidParameterException {
            if (serverAddr == null) {
                throw new InvalidParameterException("Missing serverAddr");
            }
            if (defaultRouters == null) {
                throw new InvalidParameterException("Missing defaultRouters");
            }
            if (dnsServers == null) {
                // Empty set is OK, but enforce explicitly setting it
                throw new InvalidParameterException("Missing dnsServers");
            }
            if (dhcpLeaseTimeSecs <= 0 || dhcpLeaseTimeSecs > toUnsignedLong(INFINITE_LEASE)) {
                throw new InvalidParameterException("Invalid lease time: " + dhcpLeaseTimeSecs);
            }
            if (linkMtu != MTU_UNSET && (linkMtu < IPV4_MIN_MTU || linkMtu > IPV4_MAX_MTU)) {
                throw new InvalidParameterException("Invalid link MTU: " + linkMtu);
            }
            if (!serverAddr.isIPv4()) {
                throw new InvalidParameterException("serverAddr must be IPv4");
            }
            if (serverAddr.getPrefixLength() < MIN_PREFIX_LENGTH
                    || serverAddr.getPrefixLength() > MAX_PREFIX_LENGTH) {
                throw new InvalidParameterException("Prefix length is not in supported range");
            }

            final IpPrefix prefix = makeIpPrefix(serverAddr);
            for (Inet4Address addr : defaultRouters) {
                if (!prefix.contains(addr)) {
                    throw new InvalidParameterException(String.format(
                            "Default router %s is not in server prefix %s", addr, serverAddr));
                }
            }

            final Set<Inet4Address> excl = new HashSet<>();
            if (excludedAddrs != null) {
                excl.addAll(excludedAddrs);
            }
            excl.add((Inet4Address) serverAddr.getAddress());
            excl.addAll(defaultRouters);
            excl.addAll(dnsServers);

            return new DhcpServingParams(serverAddr,
                    Collections.unmodifiableSet(new HashSet<>(defaultRouters)),
                    Collections.unmodifiableSet(new HashSet<>(dnsServers)),
                    Collections.unmodifiableSet(excl),
                    dhcpLeaseTimeSecs, linkMtu);
        }
    }

    @NonNull
    static IpPrefix makeIpPrefix(@NonNull LinkAddress addr) {
        return new IpPrefix(addr.getAddress(), addr.getPrefixLength());
    }
}
