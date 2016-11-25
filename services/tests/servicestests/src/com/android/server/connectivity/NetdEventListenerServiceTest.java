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

import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.metrics.DnsEvent;
import android.net.metrics.INetdEventListener;
import android.net.metrics.IpConnectivityLog;
import android.os.RemoteException;
import android.system.OsConstants;
import android.test.suitebuilder.annotation.SmallTest;
import com.android.server.connectivity.metrics.IpConnectivityLogClass.IpConnectivityEvent;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import junit.framework.TestCase;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class NetdEventListenerServiceTest extends TestCase {

    // TODO: read from NetdEventListenerService after this constant is read from system property
    static final int BATCH_SIZE = 100;
    static final int EVENT_TYPE = INetdEventListener.EVENT_GETADDRINFO;
    // TODO: read from INetdEventListener
    static final int RETURN_CODE = 1;

    static final byte[] EVENT_TYPES  = new byte[BATCH_SIZE];
    static final byte[] RETURN_CODES = new byte[BATCH_SIZE];
    static final int[] LATENCIES     = new int[BATCH_SIZE];
    static {
        for (int i = 0; i < BATCH_SIZE; i++) {
            EVENT_TYPES[i] = EVENT_TYPE;
            RETURN_CODES[i] = RETURN_CODE;
            LATENCIES[i] = i;
        }
    }

    private static final String EXAMPLE_IPV4 = "192.0.2.1";
    private static final String EXAMPLE_IPV6 = "2001:db8:1200::2:1";

    NetdEventListenerService mNetdEventListenerService;

    @Mock ConnectivityManager mCm;
    @Mock IpConnectivityLog mLog;
    ArgumentCaptor<NetworkCallback> mCallbackCaptor;
    ArgumentCaptor<DnsEvent> mDnsEvCaptor;

    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mCallbackCaptor = ArgumentCaptor.forClass(NetworkCallback.class);
        mDnsEvCaptor = ArgumentCaptor.forClass(DnsEvent.class);
        mNetdEventListenerService = new NetdEventListenerService(mCm, mLog);

        verify(mCm, times(1)).registerNetworkCallback(any(), mCallbackCaptor.capture());
    }

    @SmallTest
    public void testOneDnsBatch() throws Exception {
        log(105, LATENCIES);
        log(106, Arrays.copyOf(LATENCIES, BATCH_SIZE - 1)); // one lookup short of a batch event

        verifyLoggedDnsEvents(new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES));

        log(106, Arrays.copyOfRange(LATENCIES, BATCH_SIZE - 1, BATCH_SIZE));

        mDnsEvCaptor = ArgumentCaptor.forClass(DnsEvent.class); // reset argument captor
        verifyLoggedDnsEvents(
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(106, EVENT_TYPES, RETURN_CODES, LATENCIES));
    }

    @SmallTest
    public void testSeveralDmsBatches() throws Exception {
        log(105, LATENCIES);
        log(106, LATENCIES);
        log(105, LATENCIES);
        log(107, LATENCIES);

        verifyLoggedDnsEvents(
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(106, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(107, EVENT_TYPES, RETURN_CODES, LATENCIES));
    }

    @SmallTest
    public void testDnsBatchAndNetworkLost() throws Exception {
        byte[] eventTypes = Arrays.copyOf(EVENT_TYPES, 20);
        byte[] returnCodes = Arrays.copyOf(RETURN_CODES, 20);
        int[] latencies = Arrays.copyOf(LATENCIES, 20);

        log(105, LATENCIES);
        log(105, latencies);
        mCallbackCaptor.getValue().onLost(new Network(105));
        log(105, LATENCIES);

        verifyLoggedDnsEvents(
            new DnsEvent(105, eventTypes, returnCodes, latencies),
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES));
    }

    @SmallTest
    public void testConcurrentDnsBatchesAndDumps() throws Exception {
        final long stop = System.currentTimeMillis() + 100;
        final PrintWriter pw = new PrintWriter(new FileOutputStream("/dev/null"));
        new Thread() {
            public void run() {
                while (System.currentTimeMillis() < stop) {
                    mNetdEventListenerService.dump(pw);
                }
            }
        }.start();

        logDnsAsync(105, LATENCIES);
        logDnsAsync(106, LATENCIES);
        logDnsAsync(107, LATENCIES);

        verifyLoggedDnsEvents(500,
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(106, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(107, EVENT_TYPES, RETURN_CODES, LATENCIES));
    }

    @SmallTest
    public void testConcurrentDnsBatchesAndNetworkLoss() throws Exception {
        logDnsAsync(105, LATENCIES);
        Thread.sleep(10L);
        // call onLost() asynchronously to logDnsAsync's onDnsEvent() calls.
        mCallbackCaptor.getValue().onLost(new Network(105));

        // do not verify unpredictable batch
        verify(mLog, timeout(500).times(1)).log(any());
    }

    @SmallTest
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
        String want = joinLines(
                "time_ms: 0",
                "transport: 0",
                "connect_statistics <",
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
                "  latencies_ms: 523");
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

    void log(int netId, int[] latencies) {
        try {
            for (int l : latencies) {
                mNetdEventListenerService.onDnsEvent(netId, EVENT_TYPE, RETURN_CODE, l, null, null,
                        0, 0);
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    void logDnsAsync(int netId, int[] latencies) {
        new Thread() {
            public void run() {
                log(netId, latencies);
            }
        }.start();
    }

    void verifyLoggedDnsEvents(DnsEvent... expected) {
        verifyLoggedDnsEvents(0, expected);
    }

    void verifyLoggedDnsEvents(int wait, DnsEvent... expectedEvents) {
        verify(mLog, timeout(wait).times(expectedEvents.length)).log(mDnsEvCaptor.capture());
        for (DnsEvent got : mDnsEvCaptor.getAllValues()) {
            OptionalInt index = IntStream.range(0, expectedEvents.length)
                    .filter(i -> dnsEventsEqual(expectedEvents[i], got))
                    .findFirst();
            // Don't match same expected event more than once.
            index.ifPresent(i -> expectedEvents[i] = null);
            assertTrue(index.isPresent());
        }
    }

    /** equality function for DnsEvent to avoid overriding equals() and hashCode(). */
    static boolean dnsEventsEqual(DnsEvent expected, DnsEvent got) {
        return (expected == got) || ((expected != null) && (got != null)
                && (expected.netId == got.netId)
                && Arrays.equals(expected.eventTypes, got.eventTypes)
                && Arrays.equals(expected.returnCodes, got.returnCodes)
                && Arrays.equals(expected.latenciesMs, got.latenciesMs));
    }

    static String joinLines(String ... elems) {
        StringBuilder b = new StringBuilder();
        for (String s : elems) {
            b.append(s).append("\n");
        }
        return b.toString();
    }

    static void verifyConnectEvent(String expected, IpConnectivityEvent got) {
        try {
            Arrays.sort(got.connectStatistics.latenciesMs);
            Arrays.sort(got.connectStatistics.errnosCounters,
                    Comparator.comparingInt((p) -> p.key));
            assertEquals(expected, got.toString());
        } catch (Exception e) {
            fail(e.toString());
        }
    }
}
