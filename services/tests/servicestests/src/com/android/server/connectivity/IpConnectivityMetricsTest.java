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

import android.content.Context;
import android.net.ConnectivityMetricsEvent;
import android.net.IIpConnectivityMetrics;
import android.net.metrics.ApfStats;
import android.net.metrics.DefaultNetworkEvent;
import android.net.metrics.DhcpClientEvent;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.IpManagerEvent;
import android.net.metrics.IpReachabilityEvent;
import android.net.metrics.RaEvent;
import android.net.metrics.ValidationProbeEvent;
import android.os.Parcelable;
import android.util.Base64;
import com.android.server.connectivity.metrics.IpConnectivityLogClass;
import com.google.protobuf.nano.MessageNano;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import junit.framework.TestCase;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class IpConnectivityMetricsTest extends TestCase {
    static final IpReachabilityEvent FAKE_EV =
            new IpReachabilityEvent("wlan0", IpReachabilityEvent.NUD_FAILED);

    @Mock Context mCtx;
    @Mock IIpConnectivityMetrics mMockService;

    IpConnectivityMetrics mService;

    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mService = new IpConnectivityMetrics(mCtx);
    }

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

    public void testLoggingEventsWithMultipleCallers() throws Exception {
        IpConnectivityLog logger = new IpConnectivityLog(mMockService);

        final int nCallers = 10;
        final int nEvents = 10;
        for (int n = 0; n < nCallers; n++) {
            final int i = n;
            new Thread() {
                public void run() {
                    for (int j = 0; j < nEvents; j++) {
                        assertTrue(logger.log(i * 100 + j, FAKE_EV));
                    }
                }
            }.start();
        }

        List<ConnectivityMetricsEvent> got = verifyEvents(nCallers * nEvents, 100);
        Collections.sort(got, EVENT_COMPARATOR);
        Iterator<ConnectivityMetricsEvent> iter = got.iterator();
        for (int i = 0; i < nCallers; i++) {
            for (int j = 0; j < nEvents; j++) {
                int expectedTimestamp = i * 100 + j;
                assertEventsEqual(expectedEvent(expectedTimestamp), iter.next());
            }
        }
    }

    public void testBufferFlushing() {
        String output1 = getdump("flush");
        assertEquals("", output1);

        new IpConnectivityLog(mService.impl).log(1, FAKE_EV);
        String output2 = getdump("flush");
        assertFalse("".equals(output2));

        String output3 = getdump("flush");
        assertEquals("", output3);
    }

    public void testEndToEndLogging() {
        IpConnectivityLog logger = new IpConnectivityLog(mService.impl);

        Parcelable[] events = {
            new IpReachabilityEvent("wlan0", IpReachabilityEvent.NUD_FAILED),
            new DhcpClientEvent("wlan0", "SomeState", 192),
            new DefaultNetworkEvent(102, new int[]{1,2,3}, 101, true, false),
            new IpManagerEvent("wlan0", IpManagerEvent.PROVISIONING_OK, 5678),
            new ValidationProbeEvent(120, 40730, ValidationProbeEvent.PROBE_HTTP, 204),
            new ApfStats(45000, 10, 2, 2, 1, 2, 4, 2048),
            new RaEvent(2000, 400, 300, -1, 1000, -1)
        };

        for (int i = 0; i < events.length; i++) {
            logger.log(100 * (i + 1), events[i]);
        }

        String want = joinLines(
                "dropped_events: 0",
                "events <",
                "  ip_reachability_event <",
                "    event_type: 512",
                "    if_name: \"wlan0\"",
                "  >",
                "  time_ms: 100",
                ">",
                "events <",
                "  dhcp_event <",
                "    duration_ms: 192",
                "    error_code: 0",
                "    if_name: \"wlan0\"",
                "    state_transition: \"SomeState\"",
                "  >",
                "  time_ms: 200",
                ">",
                "events <",
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
                "  time_ms: 300",
                ">",
                "events <",
                "  ip_provisioning_event <",
                "    event_type: 1",
                "    if_name: \"wlan0\"",
                "    latency_ms: 5678",
                "  >",
                "  time_ms: 400",
                ">",
                "events <",
                "  time_ms: 500",
                "  validation_probe_event <",
                "    latency_ms: 40730",
                "    network_id <",
                "      network_id: 120",
                "    >",
                "    probe_result: 204",
                "    probe_type: 1",
                "  >",
                ">",
                "events <",
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
                "  time_ms: 600",
                ">",
                "events <",
                "  ra_event <",
                "    dnssl_lifetime: -1",
                "    prefix_preferred_lifetime: 300",
                "    prefix_valid_lifetime: 400",
                "    rdnss_lifetime: 1000",
                "    route_info_lifetime: -1",
                "    router_lifetime: 2000",
                "  >",
                "  time_ms: 700",
                ">");

        verifySerialization(want, getdump("flush"));
    }

    String getdump(String ... command) {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        mService.impl.dump(null, writer, command);
        return buffer.toString();
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
                    new IpConnectivityLogClass.IpConnectivityLog();
            MessageNano.mergeFrom(log, got);
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
        return new ConnectivityMetricsEvent((long)timestamp, 0, 0, FAKE_EV);
    }

    /** Outer equality for ConnectivityMetricsEvent to avoid overriding equals() and hashCode(). */
    static void assertEventsEqual(ConnectivityMetricsEvent expected, ConnectivityMetricsEvent got) {
        assertEquals(expected.timestamp, got.timestamp);
        assertEquals(expected.componentTag, got.componentTag);
        assertEquals(expected.eventTag, got.eventTag);
        assertEquals(expected.data, got.data);
    }

    static final Comparator<ConnectivityMetricsEvent> EVENT_COMPARATOR =
        new Comparator<ConnectivityMetricsEvent>() {
            @Override
            public int compare(ConnectivityMetricsEvent ev1, ConnectivityMetricsEvent ev2) {
                return (int) (ev1.timestamp - ev2.timestamp);
            }
        };
}
