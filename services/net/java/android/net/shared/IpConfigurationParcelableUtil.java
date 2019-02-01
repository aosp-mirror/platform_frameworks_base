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

import android.annotation.Nullable;
import android.net.ApfCapabilitiesParcelable;
import android.net.DhcpResults;
import android.net.DhcpResultsParcelable;
import android.net.InetAddresses;
import android.net.StaticIpConfiguration;
import android.net.StaticIpConfigurationParcelable;
import android.net.apf.ApfCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Collection of utility methods to convert to and from stable AIDL parcelables for IpClient
 * configuration classes.
 * @hide
 */
public final class IpConfigurationParcelableUtil {
    /**
     * Convert a StaticIpConfiguration to a StaticIpConfigurationParcelable.
     */
    public static StaticIpConfigurationParcelable toStableParcelable(
            @Nullable StaticIpConfiguration config) {
        if (config == null) return null;
        final StaticIpConfigurationParcelable p = new StaticIpConfigurationParcelable();
        p.ipAddress = LinkPropertiesParcelableUtil.toStableParcelable(config.getIpAddress());
        p.gateway = parcelAddress(config.getGateway());
        p.dnsServers = toParcelableArray(
                config.getDnsServers(), IpConfigurationParcelableUtil::parcelAddress, String.class);
        p.domains = config.getDomains();
        return p;
    }

    /**
     * Convert a StaticIpConfigurationParcelable to a StaticIpConfiguration.
     */
    public static StaticIpConfiguration fromStableParcelable(
            @Nullable StaticIpConfigurationParcelable p) {
        if (p == null) return null;
        final StaticIpConfiguration config = new StaticIpConfiguration();
        config.setIpAddress(LinkPropertiesParcelableUtil.fromStableParcelable(p.ipAddress));
        config.setGateway(unparcelAddress(p.gateway));
        for (InetAddress addr : fromParcelableArray(
                p.dnsServers, IpConfigurationParcelableUtil::unparcelAddress)) {
            config.addDnsServer(addr);
        }
        config.setDomains(p.domains);
        return config;
    }

    /**
     * Convert DhcpResults to a DhcpResultsParcelable.
     */
    public static DhcpResultsParcelable toStableParcelable(@Nullable DhcpResults results) {
        if (results == null) return null;
        final DhcpResultsParcelable p = new DhcpResultsParcelable();
        p.baseConfiguration = toStableParcelable(results.toStaticIpConfiguration());
        p.leaseDuration = results.leaseDuration;
        p.mtu = results.mtu;
        p.serverAddress = parcelAddress(results.serverAddress);
        p.vendorInfo = results.vendorInfo;
        return p;
    }

    /**
     * Convert a DhcpResultsParcelable to DhcpResults.
     */
    public static DhcpResults fromStableParcelable(@Nullable DhcpResultsParcelable p) {
        if (p == null) return null;
        final DhcpResults results = new DhcpResults(fromStableParcelable(p.baseConfiguration));
        results.leaseDuration = p.leaseDuration;
        results.mtu = p.mtu;
        results.serverAddress = (Inet4Address) unparcelAddress(p.serverAddress);
        results.vendorInfo = p.vendorInfo;
        return results;
    }

    /**
     * Convert ApfCapabilities to ApfCapabilitiesParcelable.
     */
    public static ApfCapabilitiesParcelable toStableParcelable(@Nullable ApfCapabilities caps) {
        if (caps == null) return null;
        final ApfCapabilitiesParcelable p = new ApfCapabilitiesParcelable();
        p.apfVersionSupported = caps.apfVersionSupported;
        p.maximumApfProgramSize = caps.maximumApfProgramSize;
        p.apfPacketFormat = caps.apfPacketFormat;
        return p;
    }

    /**
     * Convert ApfCapabilitiesParcelable toApfCapabilities.
     */
    public static ApfCapabilities fromStableParcelable(@Nullable ApfCapabilitiesParcelable p) {
        if (p == null) return null;
        return new ApfCapabilities(
                p.apfVersionSupported, p.maximumApfProgramSize, p.apfPacketFormat);
    }

    /**
     * Convert InetAddress to String.
     * TODO: have an InetAddressParcelable
     */
    public static String parcelAddress(@Nullable InetAddress addr) {
        if (addr == null) return null;
        return addr.getHostAddress();
    }

    /**
     * Convert String to InetAddress.
     * TODO: have an InetAddressParcelable
     */
    public static InetAddress unparcelAddress(@Nullable String addr) {
        if (addr == null) return null;
        return InetAddresses.parseNumericAddress(addr);
    }
}
