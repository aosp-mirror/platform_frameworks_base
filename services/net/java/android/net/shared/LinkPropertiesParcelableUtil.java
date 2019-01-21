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

import static android.net.shared.IpConfigurationParcelableUtil.parcelAddress;
import static android.net.shared.IpConfigurationParcelableUtil.unparcelAddress;
import static android.net.shared.ParcelableUtil.fromParcelableArray;
import static android.net.shared.ParcelableUtil.toParcelableArray;

import android.annotation.Nullable;
import android.net.IpPrefix;
import android.net.IpPrefixParcelable;
import android.net.LinkAddress;
import android.net.LinkAddressParcelable;
import android.net.LinkProperties;
import android.net.LinkPropertiesParcelable;
import android.net.ProxyInfo;
import android.net.ProxyInfoParcelable;
import android.net.RouteInfo;
import android.net.RouteInfoParcelable;
import android.net.Uri;

import java.util.Arrays;

/**
 * Collection of utility methods to convert to and from stable AIDL parcelables for LinkProperties
 * and its attributes.
 * @hide
 */
public final class LinkPropertiesParcelableUtil {

    /**
     * Convert a ProxyInfo to a ProxyInfoParcelable
     */
    public static ProxyInfoParcelable toStableParcelable(@Nullable ProxyInfo proxyInfo) {
        if (proxyInfo == null) {
            return null;
        }
        final ProxyInfoParcelable parcel = new ProxyInfoParcelable();
        parcel.host = proxyInfo.getHost();
        parcel.port = proxyInfo.getPort();
        parcel.exclusionList = proxyInfo.getExclusionList();
        parcel.pacFileUrl = proxyInfo.getPacFileUrl().toString();
        return parcel;
    }

    /**
     * Convert a ProxyInfoParcelable to a ProxyInfo
     */
    public static ProxyInfo fromStableParcelable(@Nullable ProxyInfoParcelable parcel) {
        if (parcel == null) {
            return null;
        }
        if (Uri.EMPTY.toString().equals(parcel.pacFileUrl)) {
            return ProxyInfo.buildDirectProxy(
                    parcel.host, parcel.port, Arrays.asList(parcel.exclusionList));
        } else {
            return ProxyInfo.buildPacProxy(Uri.parse(parcel.pacFileUrl));
        }
    }

    /**
     * Convert an IpPrefixParcelable to an IpPrefix
     */
    public static IpPrefixParcelable toStableParcelable(@Nullable IpPrefix ipPrefix) {
        if (ipPrefix == null) {
            return null;
        }
        final IpPrefixParcelable parcel = new IpPrefixParcelable();
        parcel.address = parcelAddress(ipPrefix.getAddress());
        parcel.prefixLength = ipPrefix.getPrefixLength();
        return parcel;
    }

    /**
     * Convert an IpPrefix to an IpPrefixParcelable
     */
    public static IpPrefix fromStableParcelable(@Nullable IpPrefixParcelable parcel) {
        if (parcel == null) {
            return null;
        }
        return new IpPrefix(unparcelAddress(parcel.address), parcel.prefixLength);
    }

    /**
     * Convert a RouteInfoParcelable to a RouteInfo
     */
    public static RouteInfoParcelable toStableParcelable(@Nullable RouteInfo routeInfo) {
        if (routeInfo == null) {
            return null;
        }
        final RouteInfoParcelable parcel = new RouteInfoParcelable();
        parcel.destination = toStableParcelable(routeInfo.getDestination());
        parcel.gatewayAddr = parcelAddress(routeInfo.getGateway());
        parcel.ifaceName = routeInfo.getInterface();
        parcel.type = routeInfo.getType();
        return parcel;
    }

    /**
     * Convert a RouteInfo to a RouteInfoParcelable
     */
    public static RouteInfo fromStableParcelable(@Nullable RouteInfoParcelable parcel) {
        if (parcel == null) {
            return null;
        }
        final IpPrefix destination = fromStableParcelable(parcel.destination);
        return new RouteInfo(
                destination, unparcelAddress(parcel.gatewayAddr),
                parcel.ifaceName, parcel.type);
    }

    /**
     * Convert a LinkAddressParcelable to a LinkAddress
     */
    public static LinkAddressParcelable toStableParcelable(@Nullable LinkAddress la) {
        if (la == null) {
            return null;
        }
        final LinkAddressParcelable parcel = new LinkAddressParcelable();
        parcel.address = parcelAddress(la.getAddress());
        parcel.prefixLength = la.getPrefixLength();
        parcel.flags = la.getFlags();
        parcel.scope = la.getScope();
        return parcel;
    }

    /**
     * Convert a LinkAddress to a LinkAddressParcelable
     */
    public static LinkAddress fromStableParcelable(@Nullable LinkAddressParcelable parcel) {
        if (parcel == null) {
            return null;
        }
        return new LinkAddress(
                unparcelAddress(parcel.address),
                parcel.prefixLength,
                parcel.flags,
                parcel.scope);
    }

    /**
     * Convert a LinkProperties to a LinkPropertiesParcelable
     */
    public static LinkPropertiesParcelable toStableParcelable(@Nullable LinkProperties lp) {
        if (lp == null) {
            return null;
        }
        final LinkPropertiesParcelable parcel = new LinkPropertiesParcelable();
        parcel.ifaceName = lp.getInterfaceName();
        parcel.linkAddresses = toParcelableArray(
                lp.getLinkAddresses(),
                LinkPropertiesParcelableUtil::toStableParcelable,
                LinkAddressParcelable.class);
        parcel.dnses = toParcelableArray(
                lp.getDnsServers(), IpConfigurationParcelableUtil::parcelAddress, String.class);
        parcel.pcscfs = toParcelableArray(
                lp.getPcscfServers(), IpConfigurationParcelableUtil::parcelAddress, String.class);
        parcel.validatedPrivateDnses = toParcelableArray(lp.getValidatedPrivateDnsServers(),
                IpConfigurationParcelableUtil::parcelAddress, String.class);
        parcel.usePrivateDns = lp.isPrivateDnsActive();
        parcel.privateDnsServerName = lp.getPrivateDnsServerName();
        parcel.domains = lp.getDomains();
        parcel.routes = toParcelableArray(
                lp.getRoutes(), LinkPropertiesParcelableUtil::toStableParcelable,
                RouteInfoParcelable.class);
        parcel.httpProxy = toStableParcelable(lp.getHttpProxy());
        parcel.mtu = lp.getMtu();
        parcel.tcpBufferSizes = lp.getTcpBufferSizes();
        parcel.nat64Prefix = toStableParcelable(lp.getNat64Prefix());
        parcel.stackedLinks = toParcelableArray(
                lp.getStackedLinks(), LinkPropertiesParcelableUtil::toStableParcelable,
                LinkPropertiesParcelable.class);
        return parcel;
    }

    /**
     * Convert a LinkPropertiesParcelable to a LinkProperties
     */
    public static LinkProperties fromStableParcelable(@Nullable LinkPropertiesParcelable parcel) {
        if (parcel == null) {
            return null;
        }
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(parcel.ifaceName);
        lp.setLinkAddresses(fromParcelableArray(parcel.linkAddresses,
                LinkPropertiesParcelableUtil::fromStableParcelable));
        lp.setDnsServers(fromParcelableArray(
                parcel.dnses, IpConfigurationParcelableUtil::unparcelAddress));
        lp.setPcscfServers(fromParcelableArray(
                parcel.pcscfs, IpConfigurationParcelableUtil::unparcelAddress));
        lp.setValidatedPrivateDnsServers(
                fromParcelableArray(parcel.validatedPrivateDnses,
                IpConfigurationParcelableUtil::unparcelAddress));
        lp.setUsePrivateDns(parcel.usePrivateDns);
        lp.setPrivateDnsServerName(parcel.privateDnsServerName);
        lp.setDomains(parcel.domains);
        for (RouteInfoParcelable route : parcel.routes) {
            lp.addRoute(fromStableParcelable(route));
        }
        lp.setHttpProxy(fromStableParcelable(parcel.httpProxy));
        lp.setMtu(parcel.mtu);
        lp.setTcpBufferSizes(parcel.tcpBufferSizes);
        lp.setNat64Prefix(fromStableParcelable(parcel.nat64Prefix));
        for (LinkPropertiesParcelable stackedLink : parcel.stackedLinks) {
            lp.addStackedLink(fromStableParcelable(stackedLink));
        }
        return lp;
    }
}
