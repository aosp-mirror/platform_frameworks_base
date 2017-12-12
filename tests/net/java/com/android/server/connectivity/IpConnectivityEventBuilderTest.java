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

import static android.net.metrics.INetdEventListener.EVENT_GETADDRINFO;
import static android.net.metrics.INetdEventListener.EVENT_GETHOSTBYNAME;
import static com.android.server.connectivity.MetricsTestUtil.aBool;
import static com.android.server.connectivity.MetricsTestUtil.aByteArray;
import static com.android.server.connectivity.MetricsTestUtil.aLong;
import static com.android.server.connectivity.MetricsTestUtil.aString;
import static com.android.server.connectivity.MetricsTestUtil.aType;
import static com.android.server.connectivity.MetricsTestUtil.anInt;
import static com.android.server.connectivity.MetricsTestUtil.anIntArray;
import static com.android.server.connectivity.MetricsTestUtil.b;
import static com.android.server.connectivity.MetricsTestUtil.describeIpEvent;
import static com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityLog;
import static com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.BLUETOOTH;
import static com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.CELLULAR;
import static com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.ETHERNET;
import static com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.MULTIPLE;
import static com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.WIFI;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.net.ConnectivityMetricsEvent;
import android.net.metrics.ApfProgramEvent;
import android.net.metrics.ApfStats;
import android.net.metrics.ConnectStats;
import android.net.metrics.DefaultNetworkEvent;
import android.net.metrics.DhcpClientEvent;
import android.net.metrics.DhcpErrorEvent;
import android.net.metrics.DnsEvent;
import android.net.metrics.DnsEvent;
import android.net.metrics.IpManagerEvent;
import android.net.metrics.IpReachabilityEvent;
import android.net.metrics.NetworkEvent;
import android.net.metrics.RaEvent;
import android.net.metrics.ValidationProbeEvent;
import android.net.metrics.WakeupStats;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityEvent;

import java.util.Arrays;
import java.util.List;

import org.junit.runner.RunWith;
import org.junit.Test;

// TODO: instead of comparing textpb to textpb, parse textpb and compare proto to proto.
@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpConnectivityEventBuilderTest {

    @Test
    public void testLinkLayerInferrence() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(IpReachabilityEvent.class),
                anInt(IpReachabilityEvent.NUD_FAILED));

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 0",
                "  network_id: 0",
                "  time_ms: 1",
                "  transports: 0",
                "  ip_reachability_event <",
                "    event_type: 512",
                "    if_name: \"\"",
                "  >",
                ">",
                "version: 2\n");
        verifySerialization(want, ev);

        ev.netId = 123;
        ev.transports = 3; // transports have priority for inferrence of link layer
        ev.ifname = "wlan0";
        want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                String.format("  link_layer: %d", MULTIPLE),
                "  network_id: 123",
                "  time_ms: 1",
                "  transports: 3",
                "  ip_reachability_event <",
                "    event_type: 512",
                "    if_name: \"\"",
                "  >",
                ">",
                "version: 2\n");
        verifySerialization(want, ev);

        ev.transports = 1;
        ev.ifname = null;
        want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                String.format("  link_layer: %d", CELLULAR),
                "  network_id: 123",
                "  time_ms: 1",
                "  transports: 1",
                "  ip_reachability_event <",
                "    event_type: 512",
                "    if_name: \"\"",
                "  >",
                ">",
                "version: 2\n");
        verifySerialization(want, ev);

        ev.transports = 0;
        ev.ifname = "not_inferred";
        want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"not_inferred\"",
                "  link_layer: 0",
                "  network_id: 123",
                "  time_ms: 1",
                "  transports: 0",
                "  ip_reachability_event <",
                "    event_type: 512",
                "    if_name: \"\"",
                "  >",
                ">",
                "version: 2\n");
        verifySerialization(want, ev);

        ev.ifname = "bt-pan";
        want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                String.format("  link_layer: %d", BLUETOOTH),
                "  network_id: 123",
                "  time_ms: 1",
                "  transports: 0",
                "  ip_reachability_event <",
                "    event_type: 512",
                "    if_name: \"\"",
                "  >",
                ">",
                "version: 2\n");
        verifySerialization(want, ev);

        ev.ifname = "rmnet_ipa0";
        want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                String.format("  link_layer: %d", CELLULAR),
                "  network_id: 123",
                "  time_ms: 1",
                "  transports: 0",
                "  ip_reachability_event <",
                "    event_type: 512",
                "    if_name: \"\"",
                "  >",
                ">",
                "version: 2\n");
        verifySerialization(want, ev);

        ev.ifname = "wlan0";
        want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                String.format("  link_layer: %d", WIFI),
                "  network_id: 123",
                "  time_ms: 1",
                "  transports: 0",
                "  ip_reachability_event <",
                "    event_type: 512",
                "    if_name: \"\"",
                "  >",
                ">",
                "version: 2\n");
        verifySerialization(want, ev);
    }

    @Test
    public void testDefaultNetworkEventSerialization() {
        DefaultNetworkEvent ev = new DefaultNetworkEvent(1001);
        ev.netId = 102;
        ev.transports = 2;
        ev.previousTransports = 4;
        ev.ipv4 = true;
        ev.initialScore = 20;
        ev.finalScore = 60;
        ev.durationMs = 54;
        ev.validatedMs = 27;

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 102",
                "  time_ms: 0",
                "  transports: 2",
                "  default_network_event <",
                "    default_network_duration_ms: 54",
                "    final_score: 60",
                "    initial_score: 20",
                "    ip_support: 1",
                "    no_default_network_duration_ms: 0",
                "    previous_default_network_link_layer: 1",
                "    previous_network_ip_support: 0",
                "    validation_duration_ms: 27",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, IpConnectivityEventBuilder.toProto(ev));
    }

    @Test
    public void testDhcpClientEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(DhcpClientEvent.class),
                aString("SomeState"),
                anInt(192));

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 0",
                "  network_id: 0",
                "  time_ms: 1",
                "  transports: 0",
                "  dhcp_event <",
                "    duration_ms: 192",
                "    if_name: \"\"",
                "    state_transition: \"SomeState\"",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, ev);
    }

    @Test
    public void testDhcpErrorEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(DhcpErrorEvent.class),
                anInt(DhcpErrorEvent.L4_NOT_UDP));

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 0",
                "  network_id: 0",
                "  time_ms: 1",
                "  transports: 0",
                "  dhcp_event <",
                "    duration_ms: 0",
                "    if_name: \"\"",
                "    error_code: 50397184",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, ev);
    }

    @Test
    public void testIpManagerEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(IpManagerEvent.class),
                anInt(IpManagerEvent.PROVISIONING_OK),
                aLong(5678));

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 0",
                "  network_id: 0",
                "  time_ms: 1",
                "  transports: 0",
                "  ip_provisioning_event <",
                "    event_type: 1",
                "    if_name: \"\"",
                "    latency_ms: 5678",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, ev);
    }

    @Test
    public void testIpReachabilityEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(IpReachabilityEvent.class),
                anInt(IpReachabilityEvent.NUD_FAILED));

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 0",
                "  network_id: 0",
                "  time_ms: 1",
                "  transports: 0",
                "  ip_reachability_event <",
                "    event_type: 512",
                "    if_name: \"\"",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, ev);
    }

    @Test
    public void testNetworkEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(NetworkEvent.class),
                anInt(5),
                aLong(20410));

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 0",
                "  network_id: 0",
                "  time_ms: 1",
                "  transports: 0",
                "  network_event <",
                "    event_type: 5",
                "    latency_ms: 20410",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, ev);
    }

    @Test
    public void testValidationProbeEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(ValidationProbeEvent.class),
                aLong(40730),
                anInt(ValidationProbeEvent.PROBE_HTTP),
                anInt(204));

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 0",
                "  network_id: 0",
                "  time_ms: 1",
                "  transports: 0",
                "  validation_probe_event <",
                "    latency_ms: 40730",
                "    probe_result: 204",
                "    probe_type: 1",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, ev);
    }

    @Test
    public void testApfProgramEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(ApfProgramEvent.class),
                aLong(200),
                aLong(18),
                anInt(7),
                anInt(9),
                anInt(2048),
                anInt(3));

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 0",
                "  network_id: 0",
                "  time_ms: 1",
                "  transports: 0",
                "  apf_program_event <",
                "    current_ras: 9",
                "    drop_multicast: true",
                "    effective_lifetime: 18",
                "    filtered_ras: 7",
                "    has_ipv4_addr: true",
                "    lifetime: 200",
                "    program_length: 2048",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, ev);
    }

    @Test
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
                anInt(7),
                anInt(3),
                anInt(2048));

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 0",
                "  network_id: 0",
                "  time_ms: 1",
                "  transports: 0",
                "  apf_statistics <",
                "    dropped_ras: 2",
                "    duration_ms: 45000",
                "    matching_ras: 2",
                "    max_program_size: 2048",
                "    parse_errors: 2",
                "    program_updates: 4",
                "    program_updates_all: 7",
                "    program_updates_allowing_multicast: 3",
                "    received_ras: 10",
                "    total_packet_dropped: 0",
                "    total_packet_processed: 0",
                "    zero_lifetime_ras: 1",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, ev);
    }

    @Test
    public void testRaEventSerialization() {
        ConnectivityMetricsEvent ev = describeIpEvent(
                aType(RaEvent.class),
                aLong(2000),
                aLong(400),
                aLong(300),
                aLong(-1),
                aLong(1000),
                aLong(-1));

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 0",
                "  network_id: 0",
                "  time_ms: 1",
                "  transports: 0",
                "  ra_event <",
                "    dnssl_lifetime: -1",
                "    prefix_preferred_lifetime: 300",
                "    prefix_valid_lifetime: 400",
                "    rdnss_lifetime: 1000",
                "    route_info_lifetime: -1",
                "    router_lifetime: 2000",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, ev);
    }

    @Test
    public void testWakeupStatsSerialization() {
        WakeupStats stats = new WakeupStats("wlan0");
        stats.totalWakeups = 14;
        stats.applicationWakeups = 5;
        stats.nonApplicationWakeups = 1;
        stats.rootWakeups = 2;
        stats.systemWakeups = 3;
        stats.noUidWakeups = 3;
        stats.l2UnicastCount = 5;
        stats.l2MulticastCount = 1;
        stats.l2BroadcastCount = 2;
        stats.ethertypes.put(0x800, 3);
        stats.ethertypes.put(0x86dd, 3);
        stats.ipNextHeaders.put(6, 5);


        IpConnectivityEvent got = IpConnectivityEventBuilder.toProto(stats);
        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 0",
                "  time_ms: 0",
                "  transports: 0",
                "  wakeup_stats <",
                "    application_wakeups: 5",
                "    duration_sec: 0",
                "    ethertype_counts <",
                "      key: 2048",
                "      value: 3",
                "    >",
                "    ethertype_counts <",
                "      key: 34525",
                "      value: 3",
                "    >",
                "    ip_next_header_counts <",
                "      key: 6",
                "      value: 5",
                "    >",
                "    l2_broadcast_count: 2",
                "    l2_multicast_count: 1",
                "    l2_unicast_count: 5",
                "    no_uid_wakeups: 3",
                "    non_application_wakeups: 1",
                "    root_wakeups: 2",
                "    system_wakeups: 3",
                "    total_wakeups: 14",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, got);
    }

    static void verifySerialization(String want, ConnectivityMetricsEvent... input) {
        List<IpConnectivityEvent> protoInput =
                IpConnectivityEventBuilder.toProto(Arrays.asList(input));
        verifySerialization(want, protoInput.toArray(new IpConnectivityEvent[0]));
    }

    static void verifySerialization(String want, IpConnectivityEvent... input) {
        try {
            byte[] got = IpConnectivityEventBuilder.serialize(0, Arrays.asList(input));
            IpConnectivityLog log = IpConnectivityLog.parseFrom(got);
            assertEquals(want, log.toString());
        } catch (Exception e) {
            fail(e.toString());
        }
    }
}
