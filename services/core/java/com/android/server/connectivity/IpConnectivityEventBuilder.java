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

package com.android.server.connectivity;

import android.net.ConnectivityMetricsEvent;
import android.net.metrics.ApfProgramEvent;
import android.net.metrics.ApfStats;
import android.net.metrics.DefaultNetworkEvent;
import android.net.metrics.DhcpClientEvent;
import android.net.metrics.DhcpErrorEvent;
import android.net.metrics.DnsEvent;
import android.net.metrics.IpManagerEvent;
import android.net.metrics.IpReachabilityEvent;
import android.net.metrics.NetworkEvent;
import android.net.metrics.RaEvent;
import android.net.metrics.ValidationProbeEvent;
import android.os.Parcelable;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityEvent;
import static com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityLog;
import static com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.NetworkId;

/** {@hide} */
final public class IpConnectivityEventBuilder {
    private IpConnectivityEventBuilder() {
    }

    public static byte[] serialize(int dropped, List<IpConnectivityEvent> events)
            throws IOException {
        final IpConnectivityLog log = new IpConnectivityLog();
        log.events = events.toArray(new IpConnectivityEvent[events.size()]);
        log.droppedEvents = dropped;
        if ((log.events.length > 0) || (dropped > 0)) {
            // Only write version number if log has some information at all.
            log.version = IpConnectivityMetrics.VERSION;
        }
        return IpConnectivityLog.toByteArray(log);
    }

    public static List<IpConnectivityEvent> toProto(List<ConnectivityMetricsEvent> eventsIn) {
        final ArrayList<IpConnectivityEvent> eventsOut = new ArrayList<>(eventsIn.size());
        for (ConnectivityMetricsEvent in : eventsIn) {
            final IpConnectivityEvent out = toProto(in);
            if (out == null) {
                continue;
            }
            eventsOut.add(out);
        }
        return eventsOut;
    }

    public static IpConnectivityEvent toProto(ConnectivityMetricsEvent ev) {
        final IpConnectivityEvent out = new IpConnectivityEvent();
        if (!setEvent(out, ev.data)) {
            return null;
        }
        out.timeMs = ev.timestamp;
        return out;
    }

    private static boolean setEvent(IpConnectivityEvent out, Parcelable in) {
        if (in instanceof DhcpErrorEvent) {
            setDhcpErrorEvent(out, (DhcpErrorEvent) in);
            return true;
        }

        if (in instanceof DhcpClientEvent) {
            setDhcpClientEvent(out, (DhcpClientEvent) in);
            return true;
        }

        if (in instanceof DnsEvent) {
            setDnsEvent(out, (DnsEvent) in);
            return true;
        }

        if (in instanceof IpManagerEvent) {
            setIpManagerEvent(out, (IpManagerEvent) in);
            return true;
        }

        if (in instanceof IpReachabilityEvent) {
            setIpReachabilityEvent(out, (IpReachabilityEvent) in);
            return true;
        }

        if (in instanceof DefaultNetworkEvent) {
            setDefaultNetworkEvent(out, (DefaultNetworkEvent) in);
            return true;
        }

        if (in instanceof NetworkEvent) {
            setNetworkEvent(out, (NetworkEvent) in);
            return true;
        }

        if (in instanceof ValidationProbeEvent) {
            setValidationProbeEvent(out, (ValidationProbeEvent) in);
            return true;
        }

        if (in instanceof ApfProgramEvent) {
            setApfProgramEvent(out, (ApfProgramEvent) in);
            return true;
        }

        if (in instanceof ApfStats) {
            setApfStats(out, (ApfStats) in);
            return true;
        }

        if (in instanceof RaEvent) {
            setRaEvent(out, (RaEvent) in);
            return true;
        }

        return false;
    }

    private static void setDhcpErrorEvent(IpConnectivityEvent out, DhcpErrorEvent in) {
        IpConnectivityLogClass.DHCPEvent dhcpEvent = new IpConnectivityLogClass.DHCPEvent();
        dhcpEvent.ifName = in.ifName;
        dhcpEvent.setErrorCode(in.errorCode);
        out.setDhcpEvent(dhcpEvent);
    }

    private static void setDhcpClientEvent(IpConnectivityEvent out, DhcpClientEvent in) {
        IpConnectivityLogClass.DHCPEvent dhcpEvent = new IpConnectivityLogClass.DHCPEvent();
        dhcpEvent.ifName = in.ifName;
        dhcpEvent.setStateTransition(in.msg);
        dhcpEvent.durationMs = in.durationMs;
        out.setDhcpEvent(dhcpEvent);
    }

    private static void setDnsEvent(IpConnectivityEvent out, DnsEvent in) {
        IpConnectivityLogClass.DNSLookupBatch dnsLookupBatch =
                new IpConnectivityLogClass.DNSLookupBatch();
        dnsLookupBatch.networkId = netIdOf(in.netId);
        dnsLookupBatch.eventTypes = bytesToInts(in.eventTypes);
        dnsLookupBatch.returnCodes = bytesToInts(in.returnCodes);
        dnsLookupBatch.latenciesMs = in.latenciesMs;
        out.setDnsLookupBatch(dnsLookupBatch);
    }

    private static void setIpManagerEvent(IpConnectivityEvent out, IpManagerEvent in) {
        IpConnectivityLogClass.IpProvisioningEvent ipProvisioningEvent =
                new IpConnectivityLogClass.IpProvisioningEvent();
        ipProvisioningEvent.ifName = in.ifName;
        ipProvisioningEvent.eventType = in.eventType;
        ipProvisioningEvent.latencyMs = (int) in.durationMs;
        out.setIpProvisioningEvent(ipProvisioningEvent);
    }

    private static void setIpReachabilityEvent(IpConnectivityEvent out, IpReachabilityEvent in) {
        IpConnectivityLogClass.IpReachabilityEvent ipReachabilityEvent =
                new IpConnectivityLogClass.IpReachabilityEvent();
        ipReachabilityEvent.ifName = in.ifName;
        ipReachabilityEvent.eventType = in.eventType;
        out.setIpReachabilityEvent(ipReachabilityEvent);
    }

    private static void setDefaultNetworkEvent(IpConnectivityEvent out, DefaultNetworkEvent in) {
        IpConnectivityLogClass.DefaultNetworkEvent defaultNetworkEvent =
                new IpConnectivityLogClass.DefaultNetworkEvent();
        defaultNetworkEvent.networkId = netIdOf(in.netId);
        defaultNetworkEvent.previousNetworkId = netIdOf(in.prevNetId);
        defaultNetworkEvent.transportTypes = in.transportTypes;
        defaultNetworkEvent.previousNetworkIpSupport = ipSupportOf(in);
        out.setDefaultNetworkEvent(defaultNetworkEvent);
    }

    private static void setNetworkEvent(IpConnectivityEvent out, NetworkEvent in) {
        IpConnectivityLogClass.NetworkEvent networkEvent =
                new IpConnectivityLogClass.NetworkEvent();
        networkEvent.networkId = netIdOf(in.netId);
        networkEvent.eventType = in.eventType;
        networkEvent.latencyMs = (int) in.durationMs;
        out.setNetworkEvent(networkEvent);
    }

    private static void setValidationProbeEvent(IpConnectivityEvent out, ValidationProbeEvent in) {
        IpConnectivityLogClass.ValidationProbeEvent validationProbeEvent =
                new IpConnectivityLogClass.ValidationProbeEvent();
        validationProbeEvent.networkId = netIdOf(in.netId);
        validationProbeEvent.latencyMs = (int) in.durationMs;
        validationProbeEvent.probeType = in.probeType;
        validationProbeEvent.probeResult = in.returnCode;
        out.setValidationProbeEvent(validationProbeEvent);
    }

    private static void setApfProgramEvent(IpConnectivityEvent out, ApfProgramEvent in) {
        IpConnectivityLogClass.ApfProgramEvent apfProgramEvent =
                new IpConnectivityLogClass.ApfProgramEvent();
        apfProgramEvent.lifetime = in.lifetime;
        apfProgramEvent.effectiveLifetime = in.actualLifetime;
        apfProgramEvent.filteredRas = in.filteredRas;
        apfProgramEvent.currentRas = in.currentRas;
        apfProgramEvent.programLength = in.programLength;
        if (isBitSet(in.flags, ApfProgramEvent.FLAG_MULTICAST_FILTER_ON)) {
            apfProgramEvent.dropMulticast = true;
        }
        if (isBitSet(in.flags, ApfProgramEvent.FLAG_HAS_IPV4_ADDRESS)) {
            apfProgramEvent.hasIpv4Addr = true;
        }
        out.setApfProgramEvent(apfProgramEvent);
    }

    private static void setApfStats(IpConnectivityEvent out, ApfStats in) {
        IpConnectivityLogClass.ApfStatistics apfStatistics =
                new IpConnectivityLogClass.ApfStatistics();
        apfStatistics.durationMs = in.durationMs;
        apfStatistics.receivedRas = in.receivedRas;
        apfStatistics.matchingRas = in.matchingRas;
        apfStatistics.droppedRas = in.droppedRas;
        apfStatistics.zeroLifetimeRas = in.zeroLifetimeRas;
        apfStatistics.parseErrors = in.parseErrors;
        apfStatistics.programUpdates = in.programUpdates;
        apfStatistics.programUpdatesAll = in.programUpdatesAll;
        apfStatistics.programUpdatesAllowingMulticast = in.programUpdatesAllowingMulticast;
        apfStatistics.maxProgramSize = in.maxProgramSize;
        out.setApfStatistics(apfStatistics);
    }

    private static void setRaEvent(IpConnectivityEvent out, RaEvent in) {
        IpConnectivityLogClass.RaEvent raEvent = new IpConnectivityLogClass.RaEvent();
        raEvent.routerLifetime = in.routerLifetime;
        raEvent.prefixValidLifetime = in.prefixValidLifetime;
        raEvent.prefixPreferredLifetime = in.prefixPreferredLifetime;
        raEvent.routeInfoLifetime = in.routeInfoLifetime;
        raEvent.rdnssLifetime = in.rdnssLifetime;
        raEvent.dnsslLifetime = in.dnsslLifetime;
        out.setRaEvent(raEvent);
    }

    private static int[] bytesToInts(byte[] in) {
        final int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[i] & 0xFF;
        }
        return out;
    }

    private static NetworkId netIdOf(int netid) {
        final NetworkId ni = new NetworkId();
        ni.networkId = netid;
        return ni;
    }

    private static int ipSupportOf(DefaultNetworkEvent in) {
        if (in.prevIPv4 && in.prevIPv6) {
            return IpConnectivityLogClass.DefaultNetworkEvent.DUAL;
        }
        if (in.prevIPv6) {
            return IpConnectivityLogClass.DefaultNetworkEvent.IPV6;
        }
        if (in.prevIPv4) {
            return IpConnectivityLogClass.DefaultNetworkEvent.IPV4;
        }
        return IpConnectivityLogClass.DefaultNetworkEvent.NONE;
    }

    private static boolean isBitSet(int flags, int bit) {
        return (flags & (1 << bit)) != 0;
    }
}
