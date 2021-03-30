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

import static com.android.testutils.MiscAsserts.assertStringContains;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.system.OsConstants;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Base64;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityEvent;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityLog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetdEventListenerServiceTest {
    private static final String EXAMPLE_IPV4 = "192.0.2.1";
    private static final String EXAMPLE_IPV6 = "2001:db8:1200::2:1";

    private static final byte[] MAC_ADDR =
            {(byte)0x84, (byte)0xc9, (byte)0xb2, (byte)0x6a, (byte)0xed, (byte)0x4b};

    NetdEventListenerService mService;
    ConnectivityManager mCm;
    private static final NetworkCapabilities CAPABILITIES_WIFI = new NetworkCapabilities.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build();
    private static final NetworkCapabilities CAPABILITIES_CELL = new NetworkCapabilities.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build();

    @Before
    public void setUp() {
        mCm = mock(ConnectivityManager.class);
        mService = new NetdEventListenerService(mCm);
    }

    @Test
    public void testWakeupEventLogging() throws Exception {
        final int BUFFER_LENGTH = NetdEventListenerService.WAKEUP_EVENT_BUFFER_LENGTH;
        final long now = System.currentTimeMillis();
        final String iface = "wlan0";
        final byte[] mac = MAC_ADDR;
        final String srcIp = "192.168.2.1";
        final String dstIp = "192.168.2.23";
        final String srcIp6 = "2001:db8:4:fd00:a585:13d1:6a23:4fb4";
        final String dstIp6 = "2001:db8:4006:807::200a";
        final int sport = 2356;
        final int dport = 13489;

        final int v4 = 0x800;
        final int v6 = 0x86dd;
        final int tcp = 6;
        final int udp = 17;
        final int icmp6 = 58;

        // Baseline without any event
        String[] baseline = listNetdEvent();

        int[] uids = {10001, 10002, 10004, 1000, 10052, 10023, 10002, 10123, 10004};
        wakeupEvent(iface, uids[0], v4, tcp, mac, srcIp, dstIp, sport, dport, now);
        wakeupEvent(iface, uids[1], v6, udp, mac, srcIp6, dstIp6, sport, dport, now);
        wakeupEvent(iface, uids[2], v6, udp, mac, srcIp6, dstIp6, sport, dport, now);
        wakeupEvent(iface, uids[3], v4, icmp6, mac, srcIp, dstIp, sport, dport, now);
        wakeupEvent(iface, uids[4], v6, tcp, mac, srcIp6, dstIp6, sport, dport, now);
        wakeupEvent(iface, uids[5], v4, tcp, mac, srcIp, dstIp, sport, dport, now);
        wakeupEvent(iface, uids[6], v6, udp, mac, srcIp6, dstIp6, sport, dport, now);
        wakeupEvent(iface, uids[7], v6, tcp, mac, srcIp6, dstIp6, sport, dport, now);
        wakeupEvent(iface, uids[8], v6, udp, mac, srcIp6, dstIp6, sport, dport, now);

        String[] events2 = remove(listNetdEvent(), baseline);
        int expectedLength2 = uids.length + 1; // +1 for the WakeupStats line
        assertEquals(expectedLength2, events2.length);
        assertStringContains(events2[0], "WakeupStats");
        assertStringContains(events2[0], "wlan0");
        assertStringContains(events2[0], "0x800");
        assertStringContains(events2[0], "0x86dd");
        for (int i = 0; i < uids.length; i++) {
            String got = events2[i+1];
            assertStringContains(got, "WakeupEvent");
            assertStringContains(got, "wlan0");
            assertStringContains(got, "uid: " + uids[i]);
        }

        int uid = 20000;
        for (int i = 0; i < BUFFER_LENGTH * 2; i++) {
            long ts = now + 10;
            wakeupEvent(iface, uid, 0x800, 6, mac, srcIp, dstIp, 23, 24, ts);
        }

        String[] events3 = remove(listNetdEvent(), baseline);
        int expectedLength3 = BUFFER_LENGTH + 1; // +1 for the WakeupStats line
        assertEquals(expectedLength3, events3.length);
        assertStringContains(events2[0], "WakeupStats");
        assertStringContains(events2[0], "wlan0");
        for (int i = 1; i < expectedLength3; i++) {
            String got = events3[i];
            assertStringContains(got, "WakeupEvent");
            assertStringContains(got, "wlan0");
            assertStringContains(got, "uid: " + uid);
        }

        uid = 45678;
        wakeupEvent(iface, uid, 0x800, 6, mac, srcIp, dstIp, 23, 24, now);

        String[] events4 = remove(listNetdEvent(), baseline);
        String lastEvent = events4[events4.length - 1];
        assertStringContains(lastEvent, "WakeupEvent");
        assertStringContains(lastEvent, "wlan0");
        assertStringContains(lastEvent, "uid: " + uid);
    }

    @Test
    public void testWakeupStatsLogging() throws Exception {
        final byte[] mac = MAC_ADDR;
        final String srcIp = "192.168.2.1";
        final String dstIp = "192.168.2.23";
        final String srcIp6 = "2401:fa00:4:fd00:a585:13d1:6a23:4fb4";
        final String dstIp6 = "2404:6800:4006:807::200a";
        final int sport = 2356;
        final int dport = 13489;
        final long now = 1001L;

        final int v4 = 0x800;
        final int v6 = 0x86dd;
        final int tcp = 6;
        final int udp = 17;
        final int icmp6 = 58;

        wakeupEvent("wlan0", 1000, v4, tcp, mac, srcIp, dstIp, sport, dport, now);
        wakeupEvent("rmnet0", 10123, v4, tcp, mac, srcIp, dstIp, sport, dport, now);
        wakeupEvent("wlan0", 1000, v4, udp, mac, srcIp, dstIp, sport, dport, now);
        wakeupEvent("rmnet0", 10008, v4, tcp, mac, srcIp, dstIp, sport, dport, now);
        wakeupEvent("wlan0", -1, v6, icmp6, mac, srcIp6, dstIp6, sport, dport, now);
        wakeupEvent("wlan0", 10008, v4, tcp, mac, srcIp, dstIp, sport, dport, now);
        wakeupEvent("rmnet0", 1000, v4, tcp, mac, srcIp, dstIp, sport, dport, now);
        wakeupEvent("wlan0", 10004, v4, udp, mac, srcIp, dstIp, sport, dport, now);
        wakeupEvent("wlan0", 1000, v6, tcp, mac, srcIp6, dstIp6, sport, dport, now);
        wakeupEvent("wlan0", 0, v6, udp, mac, srcIp6, dstIp6, sport, dport, now);
        wakeupEvent("wlan0", -1, v6, icmp6, mac, srcIp6, dstIp6, sport, dport, now);
        wakeupEvent("rmnet0", 10052, v4, tcp, mac, srcIp, dstIp, sport, dport, now);
        wakeupEvent("wlan0", 0, v6, udp, mac, srcIp6, dstIp6, sport, dport, now);
        wakeupEvent("rmnet0", 1000, v6, tcp, mac, srcIp6, dstIp6, sport, dport, now);
        wakeupEvent("wlan0", 1010, v4, udp, mac, srcIp, dstIp, sport, dport, now);

        String got = flushStatistics();
        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 2",
                "  network_id: 0",
                "  time_ms: 0",
                "  transports: 0",
                "  wakeup_stats <",
                "    application_wakeups: 3",
                "    duration_sec: 0",
                "    ethertype_counts <",
                "      key: 2048",
                "      value: 4",
                "    >",
                "    ethertype_counts <",
                "      key: 34525",
                "      value: 1",
                "    >",
                "    ip_next_header_counts <",
                "      key: 6",
                "      value: 5",
                "    >",
                "    l2_broadcast_count: 0",
                "    l2_multicast_count: 0",
                "    l2_unicast_count: 5",
                "    no_uid_wakeups: 0",
                "    non_application_wakeups: 0",
                "    root_wakeups: 0",
                "    system_wakeups: 2",
                "    total_wakeups: 5",
                "  >",
                ">",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 0",
                "  time_ms: 0",
                "  transports: 0",
                "  wakeup_stats <",
                "    application_wakeups: 2",
                "    duration_sec: 0",
                "    ethertype_counts <",
                "      key: 2048",
                "      value: 5",
                "    >",
                "    ethertype_counts <",
                "      key: 34525",
                "      value: 5",
                "    >",
                "    ip_next_header_counts <",
                "      key: 6",
                "      value: 3",
                "    >",
                "    ip_next_header_counts <",
                "      key: 17",
                "      value: 5",
                "    >",
                "    ip_next_header_counts <",
                "      key: 58",
                "      value: 2",
                "    >",
                "    l2_broadcast_count: 0",
                "    l2_multicast_count: 0",
                "    l2_unicast_count: 10",
                "    no_uid_wakeups: 2",
                "    non_application_wakeups: 1",
                "    root_wakeups: 2",
                "    system_wakeups: 3",
                "    total_wakeups: 10",
                "  >",
                ">",
                "version: 2\n");
        assertEquals(want, got);
    }

    @Test
    public void testDnsLogging() throws Exception {
        asyncDump(100);

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
                "    getaddrinfo_error_count: 0",
                "    getaddrinfo_query_count: 0",
                "    gethostbyname_error_count: 0",
                "    gethostbyname_query_count: 0",
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
                "    getaddrinfo_error_count: 0",
                "    getaddrinfo_query_count: 0",
                "    gethostbyname_error_count: 0",
                "    gethostbyname_query_count: 0",
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
        asyncDump(100);

        final int OK = 0;
        Thread[] logActions = {
            // ignored
            connectEventAction(100, OsConstants.EALREADY, 0, EXAMPLE_IPV4),
            connectEventAction(100, OsConstants.EALREADY, 0, EXAMPLE_IPV6),
            connectEventAction(100, OsConstants.EINPROGRESS, 0, EXAMPLE_IPV4),
            connectEventAction(101, OsConstants.EINPROGRESS, 0, EXAMPLE_IPV6),
            connectEventAction(101, OsConstants.EINPROGRESS, 0, EXAMPLE_IPV6),
            // valid latencies
            connectEventAction(100, OK, 110, EXAMPLE_IPV4),
            connectEventAction(100, OK, 23, EXAMPLE_IPV4),
            connectEventAction(100, OK, 45, EXAMPLE_IPV4),
            connectEventAction(101, OK, 56, EXAMPLE_IPV4),
            connectEventAction(101, OK, 523, EXAMPLE_IPV6),
            connectEventAction(101, OK, 214, EXAMPLE_IPV6),
            connectEventAction(101, OK, 67, EXAMPLE_IPV6),
            // errors
            connectEventAction(100, OsConstants.EPERM, 0, EXAMPLE_IPV4),
            connectEventAction(101, OsConstants.EPERM, 0, EXAMPLE_IPV4),
            connectEventAction(100, OsConstants.EAGAIN, 0, EXAMPLE_IPV4),
            connectEventAction(100, OsConstants.EACCES, 0, EXAMPLE_IPV4),
            connectEventAction(101, OsConstants.EACCES, 0, EXAMPLE_IPV4),
            connectEventAction(101, OsConstants.EACCES, 0, EXAMPLE_IPV6),
            connectEventAction(100, OsConstants.EADDRINUSE, 0, EXAMPLE_IPV4),
            connectEventAction(101, OsConstants.ETIMEDOUT, 0, EXAMPLE_IPV4),
            connectEventAction(100, OsConstants.ETIMEDOUT, 0, EXAMPLE_IPV6),
            connectEventAction(100, OsConstants.ETIMEDOUT, 0, EXAMPLE_IPV6),
            connectEventAction(101, OsConstants.ECONNREFUSED, 0, EXAMPLE_IPV4),
        };

        for (Thread t : logActions) {
            t.start();
        }
        for (Thread t : logActions) {
            t.join();
        }

        String got = flushStatistics();
        String want = String.join("\n",
                "dropped_events: 0",
                "events <",
                "  if_name: \"\"",
                "  link_layer: 4",
                "  network_id: 100",
                "  time_ms: 0",
                "  transports: 2",
                "  connect_statistics <",
                "    connect_blocking_count: 3",
                "    connect_count: 6",
                "    errnos_counters <",
                "      key: 1",
                "      value: 1",
                "    >",
                "    errnos_counters <",
                "      key: 11",
                "      value: 1",
                "    >",
                "    errnos_counters <",
                "      key: 13",
                "      value: 1",
                "    >",
                "    errnos_counters <",
                "      key: 98",
                "      value: 1",
                "    >",
                "    errnos_counters <",
                "      key: 110",
                "      value: 2",
                "    >",
                "    ipv6_addr_count: 1",
                "    latencies_ms: 23",
                "    latencies_ms: 45",
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
                "    connect_blocking_count: 4",
                "    connect_count: 6",
                "    errnos_counters <",
                "      key: 1",
                "      value: 1",
                "    >",
                "    errnos_counters <",
                "      key: 13",
                "      value: 2",
                "    >",
                "    errnos_counters <",
                "      key: 110",
                "      value: 1",
                "    >",
                "    errnos_counters <",
                "      key: 111",
                "      value: 1",
                "    >",
                "    ipv6_addr_count: 5",
                "    latencies_ms: 56",
                "    latencies_ms: 67",
                "    latencies_ms: 214",
                "    latencies_ms: 523",
                "  >",
                ">",
                "version: 2\n");
        assertEquals(want, got);
    }

    private void setCapabilities(int netId) {
        final ArgumentCaptor<ConnectivityManager.NetworkCallback> networkCallback =
                ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        verify(mCm).registerNetworkCallback(any(), networkCallback.capture());
        networkCallback.getValue().onCapabilitiesChanged(new Network(netId),
                netId == 100 ? CAPABILITIES_WIFI : CAPABILITIES_CELL);
    }

    Thread connectEventAction(int netId, int error, int latencyMs, String ipAddr) {
        setCapabilities(netId);
        return new Thread(() -> {
            try {
                mService.onConnectEvent(netId, error, latencyMs, ipAddr, 80, 1);
            } catch (Exception e) {
                fail(e.toString());
            }
        });
    }

    void dnsEvent(int netId, int type, int result, int latency) throws Exception {
        setCapabilities(netId);
        mService.onDnsEvent(netId, type, result, latency, "", null, 0, 0);
    }

    void wakeupEvent(String iface, int uid, int ether, int ip, byte[] mac, String srcIp,
            String dstIp, int sport, int dport, long now) throws Exception {
        String prefix = NetdEventListenerService.WAKEUP_EVENT_IFACE_PREFIX + iface;
        mService.onWakeupEvent(prefix, uid, ether, ip, mac, srcIp, dstIp, sport, dport, now);
    }

    void asyncDump(long durationMs) throws Exception {
        final long stop = System.currentTimeMillis() + durationMs;
        final PrintWriter pw = new PrintWriter(new FileOutputStream("/dev/null"));
        new Thread(() -> {
            while (System.currentTimeMillis() < stop) {
                mService.list(pw);
            }
        }).start();
    }

    // TODO: instead of comparing textpb to textpb, parse textpb and compare proto to proto.
    String flushStatistics() throws Exception {
        IpConnectivityMetrics metricsService =
                new IpConnectivityMetrics(mock(Context.class), (ctx) -> 2000);
        metricsService.mNetdListener = mService;

        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        metricsService.impl.dump(null, writer, new String[]{"flush"});
        byte[] bytes = Base64.decode(buffer.toString(), Base64.DEFAULT);
        IpConnectivityLog log = IpConnectivityLog.parseFrom(bytes);
        for (IpConnectivityEvent ev : log.events) {
            if (ev.getConnectStatistics() == null) {
                continue;
            }
            // Sort repeated fields of connect() events arriving in non-deterministic order.
            Arrays.sort(ev.getConnectStatistics().latenciesMs);
            Arrays.sort(ev.getConnectStatistics().errnosCounters,
                    Comparator.comparingInt((p) -> p.key));
        }
        return log.toString();
    }

    String[] listNetdEvent() throws Exception {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        mService.list(writer);
        return buffer.toString().split("\\n");
    }

    static <T> T[] remove(T[] array, T[] filtered) {
        List<T> c = Arrays.asList(filtered);
        int next = 0;
        for (int i = 0; i < array.length; i++) {
            if (c.contains(array[i])) {
                continue;
            }
            array[next++] = array[i];
        }
        return Arrays.copyOf(array, next);
    }
}
