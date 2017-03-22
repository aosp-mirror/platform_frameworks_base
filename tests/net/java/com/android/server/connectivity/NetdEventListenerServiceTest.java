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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.support.test.runner.AndroidJUnit4;
import android.system.OsConstants;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Base64;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.DNSLookupBatch;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityEvent;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityLog;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetdEventListenerServiceTest {
    private static final String EXAMPLE_IPV4 = "192.0.2.1";
    private static final String EXAMPLE_IPV6 = "2001:db8:1200::2:1";

    NetdEventListenerService mNetdEventListenerService;
    ConnectivityManager mCm;

    @Before
    public void setUp() {
        mCm = mock(ConnectivityManager.class);
        mNetdEventListenerService = new NetdEventListenerService(mCm);
    }

    @Test
    public void testDnsLogging() throws Exception {
        NetworkCapabilities ncWifi = new NetworkCapabilities();
        NetworkCapabilities ncCell = new NetworkCapabilities();
        ncWifi.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        ncCell.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);

        when(mCm.getNetworkCapabilities(new Network(100))).thenReturn(ncWifi);
        when(mCm.getNetworkCapabilities(new Network(101))).thenReturn(ncCell);

        dnsEvent(100, EVENT_GETADDRINFO, 0, 3456);
        dnsEvent(100, EVENT_GETADDRINFO, 0, 267);
        dnsEvent(100, EVENT_GETHOSTBYNAME, 22, 1230);
        dnsEvent(100, EVENT_GETADDRINFO, 3, 45);
        dnsEvent(100, EVENT_GETADDRINFO, 1, 2111);
        dnsEvent(100, EVENT_GETADDRINFO, 0, 450);
        dnsEvent(100, EVENT_GETHOSTBYNAME, 200, 638);
        dnsEvent(100, EVENT_GETHOSTBYNAME, 178, 1300);
        dnsEvent(101, EVENT_GETADDRINFO, 0, 56);
        dnsEvent(101, EVENT_GETADDRINFO, 0, 78);
        dnsEvent(101, EVENT_GETADDRINFO, 0, 14);
        dnsEvent(101, EVENT_GETHOSTBYNAME, 0, 56);
        dnsEvent(101, EVENT_GETADDRINFO, 0, 78);
        dnsEvent(101, EVENT_GETADDRINFO, 0, 14);

        String got = flushStatistics();
        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 0",
                "  network_id: 0",
                "  time_ms: 0",
                "  transports: 0",
                "  connect_statistics <",
                "    connect_blocking_count: 0",
                "    connect_count: 0",
                "    ipv6_addr_count: 0",
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
                "events <",
                "  if_name: \"\"",
                "  link_layer: 2",
                "  network_id: 101",
                "  time_ms: 0",
                "  transports: 1",
                "  dns_lookup_batch <",
                "    event_types: 1",
                "    event_types: 1",
                "    event_types: 1",
                "    event_types: 2",
                "    event_types: 1",
                "    event_types: 1",
                "    latencies_ms: 56",
                "    latencies_ms: 78",
                "    latencies_ms: 14",
                "    latencies_ms: 56",
                "    latencies_ms: 78",
                "    latencies_ms: 14",
                "    return_codes: 0",
                "    return_codes: 0",
                "    return_codes: 0",
                "    return_codes: 0",
                "    return_codes: 0",
                "    return_codes: 0",
                "  >",
                ">",
                "version: 2\n");
        assertEquals(want, got);
    }

    @Test
    public void testConnectLogging() throws Exception {
        final int OK = 0;
        Thread[] logActions = {
            // ignored
            connectEventAction(OsConstants.EALREADY, 0, EXAMPLE_IPV4),
            connectEventAction(OsConstants.EALREADY, 0, EXAMPLE_IPV6),
            connectEventAction(OsConstants.EINPROGRESS, 0, EXAMPLE_IPV4),
            connectEventAction(OsConstants.EINPROGRESS, 0, EXAMPLE_IPV6),
            connectEventAction(OsConstants.EINPROGRESS, 0, EXAMPLE_IPV6),
            // valid latencies
            connectEventAction(OK, 110, EXAMPLE_IPV4),
            connectEventAction(OK, 23, EXAMPLE_IPV4),
            connectEventAction(OK, 45, EXAMPLE_IPV4),
            connectEventAction(OK, 56, EXAMPLE_IPV4),
            connectEventAction(OK, 523, EXAMPLE_IPV6),
            connectEventAction(OK, 214, EXAMPLE_IPV6),
            connectEventAction(OK, 67, EXAMPLE_IPV6),
            // errors
            connectEventAction(OsConstants.EPERM, 0, EXAMPLE_IPV4),
            connectEventAction(OsConstants.EPERM, 0, EXAMPLE_IPV4),
            connectEventAction(OsConstants.EAGAIN, 0, EXAMPLE_IPV4),
            connectEventAction(OsConstants.EACCES, 0, EXAMPLE_IPV4),
            connectEventAction(OsConstants.EACCES, 0, EXAMPLE_IPV4),
            connectEventAction(OsConstants.EACCES, 0, EXAMPLE_IPV6),
            connectEventAction(OsConstants.EADDRINUSE, 0, EXAMPLE_IPV4),
            connectEventAction(OsConstants.ETIMEDOUT, 0, EXAMPLE_IPV4),
            connectEventAction(OsConstants.ETIMEDOUT, 0, EXAMPLE_IPV6),
            connectEventAction(OsConstants.ETIMEDOUT, 0, EXAMPLE_IPV6),
            connectEventAction(OsConstants.ECONNREFUSED, 0, EXAMPLE_IPV4),
        };

        for (Thread t : logActions) {
            t.start();
        }
        for (Thread t : logActions) {
            t.join();
        }

        List<IpConnectivityEvent> events = new ArrayList<>();
        mNetdEventListenerService.flushStatistics(events);

        IpConnectivityEvent got = events.get(0);
        String want = String.join("\n",
                "if_name: \"\"",
                "link_layer: 0",
                "network_id: 0",
                "time_ms: 0",
                "transports: 0",
                "connect_statistics <",
                "  connect_blocking_count: 7",
                "  connect_count: 12",
                "  errnos_counters <",
                "    key: 1",
                "    value: 2",
                "  >",
                "  errnos_counters <",
                "    key: 11",
                "    value: 1",
                "  >",
                "  errnos_counters <",
                "    key: 13",
                "    value: 3",
                "  >",
                "  errnos_counters <",
                "    key: 98",
                "    value: 1",
                "  >",
                "  errnos_counters <",
                "    key: 110",
                "    value: 3",
                "  >",
                "  errnos_counters <",
                "    key: 111",
                "    value: 1",
                "  >",
                "  ipv6_addr_count: 6",
                "  latencies_ms: 23",
                "  latencies_ms: 45",
                "  latencies_ms: 56",
                "  latencies_ms: 67",
                "  latencies_ms: 110",
                "  latencies_ms: 214",
                "  latencies_ms: 523",
                ">\n");
        verifyConnectEvent(want, got);
    }

    Thread connectEventAction(int error, int latencyMs, String ipAddr) {
        return new Thread(() -> {
            try {
                mNetdEventListenerService.onConnectEvent(100, error, latencyMs, ipAddr, 80, 1);
            } catch (Exception e) {
                fail(e.toString());
            }
        });
    }

    void dnsEvent(int netId, int type, int result, int latency) throws Exception {
        mNetdEventListenerService.onDnsEvent(netId, type, result, latency, "", null, 0, 0);
    }

    Thread dumpAction(long durationMs) throws Exception {
        final long stop = System.currentTimeMillis() + durationMs;
        final PrintWriter pw = new PrintWriter(new FileOutputStream("/dev/null"));
        return new Thread(() -> {
            while (System.currentTimeMillis() < stop) {
                mNetdEventListenerService.dump(pw);
            }
        });
    }

    static void verifyConnectEvent(String expected, IpConnectivityEvent got) {
        try {
            Arrays.sort(got.getConnectStatistics().latenciesMs);
            Arrays.sort(got.getConnectStatistics().errnosCounters,
                    Comparator.comparingInt((p) -> p.key));
            assertEquals(expected, got.toString());
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    // TODO: instead of comparing textpb to textpb, parse textpb and compare proto to proto.
    String flushStatistics() throws Exception {
        IpConnectivityMetrics metricsService =
                new IpConnectivityMetrics(mock(Context.class), (ctx) -> 2000);
        metricsService.mNetdListener = mNetdEventListenerService;

        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        metricsService.impl.dump(null, writer, new String[]{"flush"});
        byte[] bytes = Base64.decode(buffer.toString(), Base64.DEFAULT);
        return IpConnectivityLog.parseFrom(bytes).toString();
    }
}
