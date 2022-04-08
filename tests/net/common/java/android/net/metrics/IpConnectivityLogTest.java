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

package android.net.metrics;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.net.ConnectivityMetricsEvent;
import android.net.IIpConnectivityMetrics;
import android.net.Network;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.BitUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpConnectivityLogTest {
    private static final int FAKE_NET_ID = 100;
    private static final int[] FAKE_TRANSPORT_TYPES = BitUtils.unpackBits(TRANSPORT_WIFI);
    private static final long FAKE_TIME_STAMP = System.currentTimeMillis();
    private static final String FAKE_INTERFACE_NAME = "test";
    private static final IpReachabilityEvent FAKE_EV =
            new IpReachabilityEvent(IpReachabilityEvent.NUD_FAILED);

    @Mock IIpConnectivityMetrics mMockService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testLoggingEvents() throws Exception {
        IpConnectivityLog logger = new IpConnectivityLog(mMockService);

        assertTrue(logger.log(FAKE_EV));
        assertTrue(logger.log(FAKE_TIME_STAMP, FAKE_EV));
        assertTrue(logger.log(FAKE_NET_ID, FAKE_TRANSPORT_TYPES, FAKE_EV));
        assertTrue(logger.log(new Network(FAKE_NET_ID), FAKE_TRANSPORT_TYPES, FAKE_EV));
        assertTrue(logger.log(FAKE_INTERFACE_NAME, FAKE_EV));
        assertTrue(logger.log(makeExpectedEvent(FAKE_TIME_STAMP, FAKE_NET_ID, TRANSPORT_WIFI,
                FAKE_INTERFACE_NAME)));

        List<ConnectivityMetricsEvent> got = verifyEvents(6);
        assertEventsEqual(makeExpectedEvent(got.get(0).timestamp, 0, 0, null), got.get(0));
        assertEventsEqual(makeExpectedEvent(FAKE_TIME_STAMP, 0, 0, null), got.get(1));
        assertEventsEqual(makeExpectedEvent(got.get(2).timestamp, FAKE_NET_ID,
                TRANSPORT_WIFI, null), got.get(2));
        assertEventsEqual(makeExpectedEvent(got.get(3).timestamp, FAKE_NET_ID,
                TRANSPORT_WIFI, null), got.get(3));
        assertEventsEqual(makeExpectedEvent(got.get(4).timestamp, 0, 0, FAKE_INTERFACE_NAME),
                got.get(4));
        assertEventsEqual(makeExpectedEvent(FAKE_TIME_STAMP, FAKE_NET_ID,
                TRANSPORT_WIFI, FAKE_INTERFACE_NAME), got.get(5));
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
                        assertTrue(logger.log(makeExpectedEvent(
                                FAKE_TIME_STAMP + i * 100 + j,
                                FAKE_NET_ID + i * 100 + j,
                                ((i + j) % 2 == 0) ? TRANSPORT_WIFI : TRANSPORT_CELLULAR,
                                FAKE_INTERFACE_NAME)));
                    }
                }
            }.start();
        }

        List<ConnectivityMetricsEvent> got = verifyEvents(nCallers * nEvents, 200);
        Collections.sort(got, EVENT_COMPARATOR);
        Iterator<ConnectivityMetricsEvent> iter = got.iterator();
        for (int i = 0; i < nCallers; i++) {
            for (int j = 0; j < nEvents; j++) {
                final long expectedTimestamp = FAKE_TIME_STAMP + i * 100 + j;
                final int expectedNetId = FAKE_NET_ID + i * 100 + j;
                final long expectedTransports =
                        ((i + j) % 2 == 0) ? TRANSPORT_WIFI : TRANSPORT_CELLULAR;
                assertEventsEqual(makeExpectedEvent(expectedTimestamp, expectedNetId,
                        expectedTransports, FAKE_INTERFACE_NAME), iter.next());
            }
        }
    }

    private List<ConnectivityMetricsEvent> verifyEvents(int n, int timeoutMs) throws Exception {
        ArgumentCaptor<ConnectivityMetricsEvent> captor =
                ArgumentCaptor.forClass(ConnectivityMetricsEvent.class);
        verify(mMockService, timeout(timeoutMs).times(n)).logEvent(captor.capture());
        return captor.getAllValues();
    }

    private List<ConnectivityMetricsEvent> verifyEvents(int n) throws Exception {
        return verifyEvents(n, 10);
    }


    private ConnectivityMetricsEvent makeExpectedEvent(long timestamp, int netId, long transports,
            String ifname) {
        ConnectivityMetricsEvent ev = new ConnectivityMetricsEvent();
        ev.timestamp = timestamp;
        ev.data = FAKE_EV;
        ev.netId = netId;
        ev.transports = transports;
        ev.ifname = ifname;
        return ev;
    }

    /** Outer equality for ConnectivityMetricsEvent to avoid overriding equals() and hashCode(). */
    private void assertEventsEqual(ConnectivityMetricsEvent expected,
            ConnectivityMetricsEvent got) {
        assertEquals(expected.data, got.data);
        assertEquals(expected.timestamp, got.timestamp);
        assertEquals(expected.netId, got.netId);
        assertEquals(expected.transports, got.transports);
        assertEquals(expected.ifname, got.ifname);
    }

    static final Comparator<ConnectivityMetricsEvent> EVENT_COMPARATOR =
            Comparator.comparingLong((ev) -> ev.timestamp);
}
