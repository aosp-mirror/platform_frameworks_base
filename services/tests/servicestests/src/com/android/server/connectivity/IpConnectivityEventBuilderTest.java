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

import static com.android.server.connectivity.MetricsTestUtil.aBool;
import static com.android.server.connectivity.MetricsTestUtil.aByteArray;
import static com.android.server.connectivity.MetricsTestUtil.aLong;
import static com.android.server.connectivity.MetricsTestUtil.aString;
import static com.android.server.connectivity.MetricsTestUtil.aType;
import static com.android.server.connectivity.MetricsTestUtil.anInt;
import static com.android.server.connectivity.MetricsTestUtil.anIntArray;
import static com.android.server.connectivity.MetricsTestUtil.b;
import static com.android.server.connectivity.MetricsTestUtil.describeIpEvent;
import static com.android.server.connectivity.metrics.IpConnectivityLogClass.IpConnectivityLog;

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
import android.test.suitebuilder.annotation.SmallTest;
import java.util.Arrays;
import junit.framework.TestCase;

public class IpConnectivityEventBuilderTest extends TestCase {

    @SmallTest
    public void testDefaultNetworkEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(DefaultNetworkEvent.class),
                anInt(102),
                anIntArray(1, 2, 3),
                anInt(101),
                aBool(true),
                aBool(false));

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  time_ms: 1",
                "  transport: 0",
                "  default_network_event <",
                "    network_id <",
                "      network_id: 102",
                "    >",
                "    previous_network_id <",
                "      network_id: 101",
                "    >",
                "    previous_network_ip_support: 1",
                "    transport_types: 1",
                "    transport_types: 2",
                "    transport_types: 3",
                "  >",
                ">",
                "version: 2");

        verifySerialization(want, ev);
    }

    @SmallTest
    public void testDhcpClientEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(DhcpClientEvent.class),
                aString("wlan0"),
                aString("SomeState"),
                anInt(192));

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  time_ms: 1",
                "  transport: 0",
                "  dhcp_event <",
                "    duration_ms: 192",
                "    if_name: \"wlan0\"",
                "    state_transition: \"SomeState\"",
                "  >",
                ">",
                "version: 2");

        verifySerialization(want, ev);
    }

    @SmallTest
    public void testDhcpErrorEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(DhcpErrorEvent.class),
                aString("wlan0"),
                anInt(DhcpErrorEvent.L4_NOT_UDP));

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  time_ms: 1",
                "  transport: 0",
                "  dhcp_event <",
                "    duration_ms: 0",
                "    if_name: \"wlan0\"",
                "    error_code: 50397184",
                "  >",
                ">",
                "version: 2");

        verifySerialization(want, ev);
    }

    @SmallTest
    public void testDnsEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(DnsEvent.class),
                anInt(101),
                aByteArray(b(1), b(1), b(2), b(1), b(1), b(1), b(2), b(2)),
                aByteArray(b(0), b(0), b(22), b(3), b(1), b(0), b(200), b(178)),
                anIntArray(3456, 267, 1230, 45, 2111, 450, 638, 1300));

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  time_ms: 1",
                "  transport: 0",
                "  dns_lookup_batch <",
                "    event_types: 1",
                "    event_types: 1",
                "    event_types: 2",
                "    event_types: 1",
                "    event_types: 1",
                "    event_types: 1",
                "    event_types: 2",
                "    event_types: 2",
                "    latencies_ms: 3456",
                "    latencies_ms: 267",
                "    latencies_ms: 1230",
                "    latencies_ms: 45",
                "    latencies_ms: 2111",
                "    latencies_ms: 450",
                "    latencies_ms: 638",
                "    latencies_ms: 1300",
                "    network_id <",
                "      network_id: 101",
                "    >",
                "    return_codes: 0",
                "    return_codes: 0",
                "    return_codes: 22",
                "    return_codes: 3",
                "    return_codes: 1",
                "    return_codes: 0",
                "    return_codes: 200",
                "    return_codes: 178",
                "  >",
                ">",
                "version: 2");

        verifySerialization(want, ev);
    }

    @SmallTest
    public void testIpManagerEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(IpManagerEvent.class),
                aString("wlan0"),
                anInt(IpManagerEvent.PROVISIONING_OK),
                aLong(5678));

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  time_ms: 1",
                "  transport: 0",
                "  ip_provisioning_event <",
                "    event_type: 1",
                "    if_name: \"wlan0\"",
                "    latency_ms: 5678",
                "  >",
                ">",
                "version: 2");

        verifySerialization(want, ev);
    }

    @SmallTest
    public void testIpReachabilityEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(IpReachabilityEvent.class),
                aString("wlan0"),
                anInt(IpReachabilityEvent.NUD_FAILED));

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  time_ms: 1",
                "  transport: 0",
                "  ip_reachability_event <",
                "    event_type: 512",
                "    if_name: \"wlan0\"",
                "  >",
                ">",
                "version: 2");

        verifySerialization(want, ev);
    }

    @SmallTest
    public void testNetworkEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(NetworkEvent.class),
                anInt(100),
                anInt(5),
                aLong(20410));

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  time_ms: 1",
                "  transport: 0",
                "  network_event <",
                "    event_type: 5",
                "    latency_ms: 20410",
                "    network_id <",
                "      network_id: 100",
                "    >",
                "  >",
                ">",
                "version: 2");

        verifySerialization(want, ev);
    }

    @SmallTest
    public void testValidationProbeEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(ValidationProbeEvent.class),
                anInt(120),
                aLong(40730),
                anInt(ValidationProbeEvent.PROBE_HTTP),
                anInt(204));

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  time_ms: 1",
                "  transport: 0",
                "  validation_probe_event <",
                "    latency_ms: 40730",
                "    network_id <",
                "      network_id: 120",
                "    >",
                "    probe_result: 204",
                "    probe_type: 1",
                "  >",
                ">",
                "version: 2");

        verifySerialization(want, ev);
    }

    @SmallTest
    public void testApfProgramEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(ApfProgramEvent.class),
                aLong(200),
                anInt(7),
                anInt(9),
                anInt(2048),
                anInt(3));

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  time_ms: 1",
                "  transport: 0",
                "  apf_program_event <",
                "    current_ras: 9",
                "    drop_multicast: true",
                "    filtered_ras: 7",
                "    has_ipv4_addr: true",
                "    lifetime: 200",
                "    program_length: 2048",
                "  >",
                ">",
                "version: 2");

        verifySerialization(want, ev);
    }

    @SmallTest
    public void testApfStatsSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(ApfStats.class),
                aLong(45000),
                anInt(10),
                anInt(2),
                anInt(2),
                anInt(1),
                anInt(2),
                anInt(4),
                anInt(2048));

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  time_ms: 1",
                "  transport: 0",
                "  apf_statistics <",
                "    dropped_ras: 2",
                "    duration_ms: 45000",
                "    matching_ras: 2",
                "    max_program_size: 2048",
                "    parse_errors: 2",
                "    program_updates: 4",
                "    received_ras: 10",
                "    zero_lifetime_ras: 1",
                "  >",
                ">",
                "version: 2");

        verifySerialization(want, ev);
    }

    @SmallTest
    public void testRaEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(RaEvent.class),
                aLong(2000),
                aLong(400),
                aLong(300),
                aLong(-1),
                aLong(1000),
                aLong(-1));

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  time_ms: 1",
                "  transport: 0",
                "  ra_event <",
                "    dnssl_lifetime: -1",
                "    prefix_preferred_lifetime: 300",
                "    prefix_valid_lifetime: 400",
                "    rdnss_lifetime: 1000",
                "    route_info_lifetime: -1",
                "    router_lifetime: 2000",
                "  >",
                ">",
                "version: 2");

        verifySerialization(want, ev);
    }

    static void verifySerialization(String want, ConnectivityMetricsEvent... input) {
        try {
            byte[] got = IpConnectivityEventBuilder.serialize(0,
                    IpConnectivityEventBuilder.toProto(Arrays.asList(input)));
            IpConnectivityLog log = IpConnectivityLog.parseFrom(got);
            assertEquals(want, log.toString());
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    static String joinLines(String ... elems) {
        StringBuilder b = new StringBuilder();
        for (String s : elems) {
            b.append(s);
            b.append("\n");
        }
        return b.toString();
    }
}
