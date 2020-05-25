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

import static android.net.shared.Inet4AddressUtils.inet4AddressToIntHTH;

import android.net.LinkAddress;
import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Subclass of {@link DhcpServingParamsParcel} with additional utility methods for building.
 *
 * <p>This utility class does not check for validity of the parameters: invalid parameters are
 * reported by the receiving module when unparceling the parcel.
 *
 * @see DhcpServingParams
 * @hide
 */
public class DhcpServingParamsParcelExt extends DhcpServingParamsParcel {
    public static final int MTU_UNSET = 0;

    /**
     * Set the server address and served prefix for the DHCP server.
     *
     * <p>This parameter is required.
     */
    public DhcpServingParamsParcelExt setServerAddr(@NonNull LinkAddress serverAddr) {
        this.serverAddr = inet4AddressToIntHTH((Inet4Address) serverAddr.getAddress());
        this.serverAddrPrefixLength = serverAddr.getPrefixLength();
        return this;
    }

    /**
     * Set the default routers to be advertised to DHCP clients.
     *
     * <p>Each router must be inside the served prefix. This may be an empty set, but it must
     * always be set explicitly.
     */
    public DhcpServingParamsParcelExt setDefaultRouters(@NonNull Set<Inet4Address> defaultRouters) {
        this.defaultRouters = toIntArray(defaultRouters);
        return this;
    }

    /**
     * Set the default routers to be advertised to DHCP clients.
     *
     * <p>Each router must be inside the served prefix. This may be an empty list of routers,
     * but it must always be set explicitly.
     */
    public DhcpServingParamsParcelExt setDefaultRouters(@NonNull Inet4Address... defaultRouters) {
        return setDefaultRouters(newArraySet(defaultRouters));
    }

    /**
     * Convenience method to build the parameters with no default router.
     *
     * <p>Equivalent to calling {@link #setDefaultRouters(Inet4Address...)} with no address.
     */
    public DhcpServingParamsParcelExt setNoDefaultRouter() {
        return setDefaultRouters();
    }

    /**
     * Set the DNS servers to be advertised to DHCP clients.
     *
     * <p>This may be an empty set, but it must always be set explicitly.
     */
    public DhcpServingParamsParcelExt setDnsServers(@NonNull Set<Inet4Address> dnsServers) {
        this.dnsServers = toIntArray(dnsServers);
        return this;
    }

    /**
     * Set the DNS servers to be advertised to DHCP clients.
     *
     * <p>This may be an empty list of servers, but it must always be set explicitly.
     */
    public DhcpServingParamsParcelExt setDnsServers(@NonNull Inet4Address... dnsServers) {
        return setDnsServers(newArraySet(dnsServers));
    }

    /**
     * Convenience method to build the parameters with no DNS server.
     *
     * <p>Equivalent to calling {@link #setDnsServers(Inet4Address...)} with no address.
     */
    public DhcpServingParamsParcelExt setNoDnsServer() {
        return setDnsServers();
    }

    /**
     * Set excluded addresses that the DHCP server is not allowed to assign to clients.
     *
     * <p>This parameter is optional. DNS servers and default routers are always excluded
     * and do not need to be set here.
     */
    public DhcpServingParamsParcelExt setExcludedAddrs(@NonNull Set<Inet4Address> excludedAddrs) {
        this.excludedAddrs = toIntArray(excludedAddrs);
        return this;
    }

    /**
     * Set excluded addresses that the DHCP server is not allowed to assign to clients.
     *
     * <p>This parameter is optional. DNS servers and default routers are always excluded
     * and do not need to be set here.
     */
    public DhcpServingParamsParcelExt setExcludedAddrs(@NonNull Inet4Address... excludedAddrs) {
        return setExcludedAddrs(newArraySet(excludedAddrs));
    }

    /**
     * Set the lease time for leases assigned by the DHCP server.
     *
     * <p>This parameter is required.
     */
    public DhcpServingParamsParcelExt setDhcpLeaseTimeSecs(long dhcpLeaseTimeSecs) {
        this.dhcpLeaseTimeSecs = dhcpLeaseTimeSecs;
        return this;
    }

    /**
     * Set the link MTU to be advertised to DHCP clients.
     *
     * <p>If set to {@link #MTU_UNSET}, no MTU will be advertised to clients. This parameter
     * is optional and defaults to {@link #MTU_UNSET}.
     */
    public DhcpServingParamsParcelExt setLinkMtu(int linkMtu) {
        this.linkMtu = linkMtu;
        return this;
    }

    /**
     * Set whether the DHCP server should send the ANDROID_METERED vendor-specific option.
     *
     * <p>If not set, the default value is false.
     */
    public DhcpServingParamsParcelExt setMetered(boolean metered) {
        this.metered = metered;
        return this;
    }

    /**
     * Set the client address to tell DHCP server only offer this address.
     * The client's prefix length is the same as server's.
     *
     * <p>If not set, the default value is null.
     */
    public DhcpServingParamsParcelExt setSingleClientAddr(@Nullable Inet4Address clientAddr) {
        this.singleClientAddr = clientAddr == null ? 0 : inet4AddressToIntHTH(clientAddr);
        return this;
    }

    /**
     * Set whether the DHCP server should request a new prefix from IpServer when receiving
     * DHCPDECLINE message in certain particular link (e.g. there is only one downstream USB
     * tethering client). If it's false, process DHCPDECLINE message as RFC2131#4.3.3 suggests.
     *
     * <p>If not set, the default value is false.
     */
    public DhcpServingParamsParcelExt setChangePrefixOnDecline(boolean changePrefixOnDecline) {
        this.changePrefixOnDecline = changePrefixOnDecline;
        return this;
    }

    private static int[] toIntArray(@NonNull Collection<Inet4Address> addrs) {
        int[] res = new int[addrs.size()];
        int i = 0;
        for (Inet4Address addr : addrs) {
            res[i] = inet4AddressToIntHTH(addr);
            i++;
        }
        return res;
    }

    private static ArraySet<Inet4Address> newArraySet(Inet4Address... addrs) {
        ArraySet<Inet4Address> addrSet = new ArraySet<>(addrs.length);
        Collections.addAll(addrSet, addrs);
        return addrSet;
    }
}
