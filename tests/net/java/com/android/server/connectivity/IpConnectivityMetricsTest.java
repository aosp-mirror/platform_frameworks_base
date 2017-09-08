/*
 * Copyright (C) 2016, The Android Open Source Project
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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityMetricsEvent;
import android.net.IIpConnectivityMetrics;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.metrics.ApfProgramEvent;
import android.net.metrics.ApfStats;
import android.net.metrics.DefaultNetworkEvent;
import android.net.metrics.DhcpClientEvent;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.IpManagerEvent;
import android.net.metrics.IpReachabilityEvent;
import android.net.metrics.RaEvent;
import android.net.metrics.ValidationProbeEvent;
import android.system.OsConstants;
import android.os.Parcelable;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Base64;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpConnectivityMetricsTest {
    static final IpReachabilityEvent FAKE_EV =
            new IpReachabilityEvent(IpReachabilityEvent.NUD_FAILED);

    private static final String EXAMPLE_IPV4 = "192.0.2.1";
    private static final String EXAMPLE_IPV6 = "2001:db8:1200::2:1";

    @Mock Context mCtx;
    @Mock IIpConnectivityMetrics mMockService;
    @Mock ConnectivityManager mCm;

    IpConnectivityMetrics mService;
    NetdEventListenerService mNetdListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mService = new IpConnectivityMetrics(mCtx, (ctx) -> 2000);
        mNetdListener = new NetdEventListenerService(mCm);
        mService.mNetdListener = mNetdListener;
    }

    @Test
    public void testLoggingEvents() throws Exception {
        IpConnectivityLog logger = new IpConnectivityLog(mMockService);

        assertTrue(logger.log(1, FAKE_EV));
        assertTrue(logger.log(2, FAKE_EV));
        assertTrue(logger.log(3, FAKE_EV));

        List<ConnectivityMetricsEvent> got = verifyEvents(3);
        assertEventsEqual(expectedEvent(1), got.get(0));
        assertEventsEqual(expectedEvent(2), got.get(1));
        assertEventsEqual(expectedEvent(3), got.get(2));
    }

    @Test
    public void testLoggingEventsWithMultipleCallers() throws Exception {
        IpConnectivityLog logger = new IpConnectivityLog(mMockService);

        final int nCallers = 10;
        final int nEvents = 10;
        for (int n = 0; n < nCallers; n++) {
            final int i = n;
            new Thread() {
                public void run() {
                    for (int j = 0; j < nEvents; j++) {
                        assertTrue(logger.log(1 + i * 100 + j, FAKE_EV));
                    }
                }
            }.start();
        }

        List<ConnectivityMetricsEvent> got = verifyEvents(nCallers * nEvents, 200);
        Collections.sort(got, EVENT_COMPARATOR);
        Iterator<ConnectivityMetricsEvent> iter = got.iterator();
        for (int i = 0; i < nCallers; i++) {
            for (int j = 0; j < nEvents; j++) {
                int expectedTimestamp = 1 + i * 100 + j;
                assertEventsEqual(expectedEvent(expectedTimestamp), iter.next());
            }
        }
    }

    @Test
    public void testBufferFlushing() {
        String output1 = getdump("flush");
        assertEquals("", output1);

        new IpConnectivityLog(mService.impl).log(1, FAKE_EV);
        String output2 = getdump("flush");
        assertFalse("".equals(output2));

        String output3 = getdump("flush");
        assertEquals("", output3);
    }

    @Test
    public void testRateLimiting() {
        final IpConnectivityLog logger = new IpConnectivityLog(mService.impl);
        final ApfProgramEvent ev = new ApfProgramEvent();
        final long fakeTimestamp = 1;

        int attempt = 100; // More than burst quota, but less than buffer size.
        for (int i = 0; i < attempt; i++) {
            logger.log(ev);
        }

        String output1 = getdump("flush");
        assertFalse("".equals(output1));

        for (int i = 0; i < attempt; i++) {
            assertFalse("expected event to be dropped", logger.log(fakeTimestamp, ev));
        }

        String output2 = getdump("flush");
        assertEquals("", output2);
    }

    @Test
    public void testEndToEndLogging() throws Exception {
        // TODO: instead of comparing textpb to textpb, parse textpb and compare proto to proto.
        IpConnectivityLog logger = new IpConnectivityLog(mService.impl);

        NetworkCapabilities ncWifi = new NetworkCapabilities();
        NetworkCapabilities ncCell = new NetworkCapabilities();
        ncWifi.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        ncCell.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);

        when(mCm.getNetworkCapabilities(new Network(100))).thenReturn(ncWifi);
        when(mCm.getNetworkCapabilities(new Network(101))).thenReturn(ncCell);

        ApfStats apfStats = new ApfStats();
        apfStats.durationMs = 45000;
        apfStats.receivedRas = 10;
        apfStats.matchingRas = 2;
        apfStats.droppedRas = 2;
        apfStats.parseErrors = 2;
        apfStats.zeroLifetimeRas = 1;
        apfStats.programUpdates = 4;
        apfStats.programUpdatesAll = 7;
        apfStats.programUpdatesAllowingMulticast = 3;
        apfStats.maxProgramSize = 2048;

        ValidationProbeEvent validationEv = new ValidationProbeEvent();
        validationEv.durationMs = 40730;
        validationEv.probeType = ValidationProbeEvent.PROBE_HTTP;
        validationEv.returnCode = 204;

        Parcelable[] events = {
            new IpReachabilityEvent(IpReachabilityEvent.NUD_FAILED),
            new DhcpClientEvent("SomeState", 192),
            new DefaultNetworkEvent(102, new int[]{1,2,3}, 101, true, false),
            new IpManagerEvent(IpManagerEvent.PROVISIONING_OK, 5678),
            validationEv,
            apfStats,
            new RaEvent(2000, 400, 300, -1, 1000, -1)
        };

        for (int i = 0; i < events.length; i++) {
            ConnectivityMetricsEvent ev = new ConnectivityMetricsEvent();
            ev.timestamp = 100 * (i + 1);
            ev.ifname = "wlan0";
            ev.data = events[i];
            logger.log(ev);
        }

        // netId, errno, latency, destination
        connectEvent(100, OsConstants.EALREADY, 0, EXAMPLE_IPV4);
        connectEvent(100, OsConstants.EINPROGRESS, 0, EXAMPLE_IPV6);
        connectEvent(100, 0, 110, EXAMPLE_IPV4);
        connectEvent(101, 0, 23, EXAMPLE_IPV4);
        connectEvent(101, 0, 45, EXAMPLE_IPV6);
        connectEvent(100, OsConstants.EAGAIN, 0, EXAMPLE_IPV4);

        // netId, type, return code, latency
        dnsEvent(100, EVENT_GETADDRINFO, 0, 3456);
        dnsEvent(100, EVENT_GETADDRINFO, 3, 45);
        dnsEvent(100, EVENT_GETHOSTBYNAME, 0, 638);
        dnsEvent(101, EVENT_GETADDRINFO, 0, 56);
        dnsEvent(101, EVENT_GETHOSTBYNAME, 0, 34);

        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 0",
                "  time_ms: 100",
                "  transports: 0",
                "  ip_reachability_event <",
                "    event_type: 512",
                "    if_name: \"\"",
                "  >",
                ">",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 0",
                "  time_ms: 200",
                "  transports: 0",
                "  dhcp_event <",
                "    duration_ms: 192",
                "    if_name: \"\"",
                "    state_transition: \"SomeState\"",
                "  >",
                ">",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 0",
                "  time_ms: 300",
                "  transports: 0",
                "  default_network_event <",
                "    default_network_duration_ms: 0",
                "    final_score: 0",
                "    initial_score: 0",
                "    ip_support: 0",
                "    network_id <",
                "      network_id: 102",
                "    >",
                "    no_default_network_duration_ms: 0",
                "    previous_network_id <",
                "      network_id: 101",
                "    >",
                "    previous_network_ip_support: 1",
                "    transport_types: 1",
                "    transport_types: 2",
                "    transport_types: 3",
                "  >",
                ">",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 0",
                "  time_ms: 400",
                "  transports: 0",
                "  ip_provisioning_event <",
                "    event_type: 1",
                "    if_name: \"\"",
                "    latency_ms: 5678",
                "  >",
                ">",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 0",
                "  time_ms: 500",
                "  transports: 0",
                "  validation_probe_event <",
                "    latency_ms: 40730",
                "    probe_result: 204",
                "    probe_type: 1",
                "  >",
                ">",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 0",
                "  time_ms: 600",
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
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 0",
                "  time_ms: 700",
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
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 100",
                "  time_ms: 0",
                "  transports: 2",
                "  connect_statistics <",
                "    connect_blocking_count: 1",
                "    connect_count: 3",
                "    errnos_counters <",
                "      key: 11",
                "      value: 1",
                "    >",
                "    ipv6_addr_count: 1",
                "    latencies_ms: 110",
                "  >",
                ">",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 2",
                "  network_id: 101",
                "  time_ms: 0",
                "  transports: 1",
                "  connect_statistics <",
                "    connect_blocking_count: 2",
                "    connect_count: 2",
                "    ipv6_addr_count: 1",
                "    latencies_ms: 23",
                "    latencies_ms: 45",
                "  >",
                ">",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 100",
                "  time_ms: 0",
                "  transports: 2",
                "  dns_lookup_batch <",
                "    event_types: 1",
                "    event_types: 1",
                "    event_types: 2",
                "    getaddrinfo_error_count: 0",
                "    getaddrinfo_query_count: 0",
                "    gethostbyname_error_count: 0",
                "    gethostbyname_query_count: 0",
                "    latencies_ms: 3456",
                "    latencies_ms: 45",
                "    latencies_ms: 638",
                "    return_codes: 0",
                "    return_codes: 3",
                "    return_codes: 0",
                "  >",
                ">",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 2",
                "  network_id: 101",
                "  time_ms: 0",
                "  transports: 1",
                "  dns_lookup_batch <",
                "    event_types: 1",
                "    event_types: 2",
                "    getaddrinfo_error_count: 0",
                "    getaddrinfo_query_count: 0",
                "    gethostbyname_error_count: 0",
                "    gethostbyname_query_count: 0",
                "    latencies_ms: 56",
                "    latencies_ms: 34",
                "    return_codes: 0",
                "    return_codes: 0",
                "  >",
                ">",
                "version: 2\n");

        verifySerialization(want, getdump("flush"));
    }

    String getdump(String ... command) {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        mService.impl.dump(null, writer, command);
        return buffer.toString();
    }

    void connectEvent(int netid, int error, int latencyMs, String ipAddr) throws Exception {
        mNetdListener.onConnectEvent(netid, error, latencyMs, ipAddr, 80, 1);
    }

    void dnsEvent(int netId, int type, int result, int latency) throws Exception {
        mNetdListener.onDnsEvent(netId, type, result, latency, "", null, 0, 0);
    }

    List<ConnectivityMetricsEvent> verifyEvents(int n, int timeoutMs) throws Exception {
        ArgumentCaptor<ConnectivityMetricsEvent> captor =
                ArgumentCaptor.forClass(ConnectivityMetricsEvent.class);
        verify(mMockService, timeout(timeoutMs).times(n)).logEvent(captor.capture());
        return captor.getAllValues();
    }

    List<ConnectivityMetricsEvent> verifyEvents(int n) throws Exception {
        return verifyEvents(n, 10);
    }

    static void verifySerialization(String want, String output) {
        try {
            byte[] got = Base64.decode(output, Base64.DEFAULT);
            IpConnectivityLogClass.IpConnectivityLog log =
                    IpConnectivityLogClass.IpConnectivityLog.parseFrom(got);
            assertEquals(want, log.toString());
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    static String joinLines(String ... elems) {
        StringBuilder b = new StringBuilder();
        for (String s : elems) {
            b.append(s).append("\n");
        }
        return b.toString();
    }

    static ConnectivityMetricsEvent expectedEvent(int timestamp) {
        ConnectivityMetricsEvent ev = new ConnectivityMetricsEvent();
        ev.timestamp = timestamp;
        ev.data = FAKE_EV;
        return ev;
    }

    /** Outer equality for ConnectivityMetricsEvent to avoid overriding equals() and hashCode(). */
    static void assertEventsEqual(ConnectivityMetricsEvent expected, ConnectivityMetricsEvent got) {
        assertEquals(expected.timestamp, got.timestamp);
        assertEquals(expected.data, got.data);
    }

    static final Comparator<ConnectivityMetricsEvent> EVENT_COMPARATOR =
        Comparator.comparingLong((ev) -> ev.timestamp);
}
