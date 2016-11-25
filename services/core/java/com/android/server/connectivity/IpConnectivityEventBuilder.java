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
import com.android.server.connectivity.metrics.IpConnectivityLogClass;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.android.server.connectivity.metrics.IpConnectivityLogClass.IpConnectivityEvent;
import static com.android.server.connectivity.metrics.IpConnectivityLogClass.IpConnectivityLog;
import static com.android.server.connectivity.metrics.IpConnectivityLogClass.NetworkId;

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
        out.dhcpEvent = new IpConnectivityLogClass.DHCPEvent();
        out.dhcpEvent.ifName = in.ifName;
        out.dhcpEvent.errorCode = in.errorCode;
    }

    private static void setDhcpClientEvent(IpConnectivityEvent out, DhcpClientEvent in) {
        out.dhcpEvent = new IpConnectivityLogClass.DHCPEvent();
        out.dhcpEvent.ifName = in.ifName;
        out.dhcpEvent.stateTransition = in.msg;
        out.dhcpEvent.durationMs = in.durationMs;
    }

    private static void setDnsEvent(IpConnectivityEvent out, DnsEvent in) {
        out.dnsLookupBatch = new IpConnectivityLogClass.DNSLookupBatch();
        out.dnsLookupBatch.networkId = netIdOf(in.netId);
        out.dnsLookupBatch.eventTypes = bytesToInts(in.eventTypes);
        out.dnsLookupBatch.returnCodes = bytesToInts(in.returnCodes);
        out.dnsLookupBatch.latenciesMs = in.latenciesMs;
    }

    private static void setIpManagerEvent(IpConnectivityEvent out, IpManagerEvent in) {
        out.ipProvisioningEvent = new IpConnectivityLogClass.IpProvisioningEvent();
        out.ipProvisioningEvent.ifName = in.ifName;
        out.ipProvisioningEvent.eventType = in.eventType;
        out.ipProvisioningEvent.latencyMs = (int) in.durationMs;
    }

    private static void setIpReachabilityEvent(IpConnectivityEvent out, IpReachabilityEvent in) {
        out.ipReachabilityEvent = new IpConnectivityLogClass.IpReachabilityEvent();
        out.ipReachabilityEvent.ifName = in.ifName;
        out.ipReachabilityEvent.eventType = in.eventType;
    }

    private static void setDefaultNetworkEvent(IpConnectivityEvent out, DefaultNetworkEvent in) {
        out.defaultNetworkEvent = new IpConnectivityLogClass.DefaultNetworkEvent();
        out.defaultNetworkEvent.networkId = netIdOf(in.netId);
        out.defaultNetworkEvent.previousNetworkId = netIdOf(in.prevNetId);
        out.defaultNetworkEvent.transportTypes = in.transportTypes;
        out.defaultNetworkEvent.previousNetworkIpSupport = ipSupportOf(in);
    }

    private static void setNetworkEvent(IpConnectivityEvent out, NetworkEvent in) {
        out.networkEvent = new IpConnectivityLogClass.NetworkEvent();
        out.networkEvent.networkId = netIdOf(in.netId);
        out.networkEvent.eventType = in.eventType;
        out.networkEvent.latencyMs = (int) in.durationMs;
    }

    private static void setValidationProbeEvent(IpConnectivityEvent out, ValidationProbeEvent in) {
        out.validationProbeEvent = new IpConnectivityLogClass.ValidationProbeEvent();
        out.validationProbeEvent.networkId = netIdOf(in.netId);
        out.validationProbeEvent.latencyMs = (int) in.durationMs;
        out.validationProbeEvent.probeType = in.probeType;
        out.validationProbeEvent.probeResult = in.returnCode;
    }

    private static void setApfProgramEvent(IpConnectivityEvent out, ApfProgramEvent in) {
        out.apfProgramEvent = new IpConnectivityLogClass.ApfProgramEvent();
        out.apfProgramEvent.lifetime = in.lifetime;
        out.apfProgramEvent.filteredRas = in.filteredRas;
        out.apfProgramEvent.currentRas = in.currentRas;
        out.apfProgramEvent.programLength = in.programLength;
        if (isBitSet(in.flags, ApfProgramEvent.FLAG_MULTICAST_FILTER_ON)) {
            out.apfProgramEvent.dropMulticast = true;
        }
        if (isBitSet(in.flags, ApfProgramEvent.FLAG_HAS_IPV4_ADDRESS)) {
            out.apfProgramEvent.hasIpv4Addr = true;
        }
    }

    private static void setApfStats(IpConnectivityEvent out, ApfStats in) {
        out.apfStatistics = new IpConnectivityLogClass.ApfStatistics();
        out.apfStatistics.durationMs = in.durationMs;
        out.apfStatistics.receivedRas = in.receivedRas;
        out.apfStatistics.matchingRas = in.matchingRas;
        out.apfStatistics.droppedRas = in.droppedRas;
        out.apfStatistics.zeroLifetimeRas = in.zeroLifetimeRas;
        out.apfStatistics.parseErrors = in.parseErrors;
        out.apfStatistics.programUpdates = in.programUpdates;
        out.apfStatistics.maxProgramSize = in.maxProgramSize;
    }

    private static void setRaEvent(IpConnectivityEvent out, RaEvent in) {
        out.raEvent = new IpConnectivityLogClass.RaEvent();
        out.raEvent.routerLifetime = in.routerLifetime;
        out.raEvent.prefixValidLifetime = in.prefixValidLifetime;
        out.raEvent.prefixPreferredLifetime = in.prefixPreferredLifetime;
        out.raEvent.routeInfoLifetime = in.routeInfoLifetime;
        out.raEvent.rdnssLifetime = in.rdnssLifetime;
        out.raEvent.dnsslLifetime = in.dnsslLifetime;
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
