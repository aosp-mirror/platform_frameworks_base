/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.net.watchlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.ConnectivityMetricsEvent;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.ServiceThread;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * runtest frameworks-services -c com.android.server.net.watchlist.NetworkWatchlistServiceTests
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class NetworkWatchlistServiceTests {

    private static final long NETWORK_EVENT_TIMEOUT_SEC = 1;
    private static final int TEST_NETID = 100;
    private static final int TEST_EVENT_TYPE = 1;
    private static final String TEST_HOST = "testhost.com";
    private static final String TEST_IP = "7.6.8.9";
    private static final String[] TEST_IPS =
            new String[] {"1.2.3.4", "4.6.8.9", "2001:0db8:0001:0000:0000:0ab9:C0A8:0102"};

    private static class TestHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case WatchlistLoggingHandler.LOG_WATCHLIST_EVENT_MSG:
                    onLogEvent();
                    break;
                case WatchlistLoggingHandler.REPORT_RECORDS_IF_NECESSARY_MSG:
                    onAggregateEvent();
                    break;
                default:
                    fail("Unexpected message: " + msg.what);
            }
        }

        public void onLogEvent() {}
        public void onAggregateEvent() {}
    }

    private static class TestIIpConnectivityMetrics implements IIpConnectivityMetrics {

        int counter = 0;
        INetdEventCallback callback = null;

        @Override
        public IBinder asBinder() {
            return null;
        }

        @Override
        public int logEvent(ConnectivityMetricsEvent connectivityMetricsEvent)
                    throws RemoteException {
            return 0;
        }

        @Override
        public boolean addNetdEventCallback(int callerType, INetdEventCallback callback) {
            counter++;
            this.callback = callback;
            return true;
        }

        @Override
        public boolean removeNetdEventCallback(int callerType) {
            counter--;
            return true;
        }
    };

    ServiceThread mHandlerThread;
    WatchlistLoggingHandler mWatchlistHandler;
    NetworkWatchlistService mWatchlistService;

    @Before
    public void setUp() {
        mHandlerThread = new ServiceThread("NetworkWatchlistServiceTests",
                Process.THREAD_PRIORITY_BACKGROUND, /* allowIo */ false);
        mHandlerThread.start();
        mWatchlistHandler = new WatchlistLoggingHandler(InstrumentationRegistry.getContext(),
                mHandlerThread.getLooper());
        mWatchlistService = new NetworkWatchlistService(InstrumentationRegistry.getContext(),
                mHandlerThread, mWatchlistHandler, null);
    }

    @After
    public void tearDown() {
        mHandlerThread.quitSafely();
    }

    @Test
    public void testStartStopWatchlistLogging() throws Exception {
        TestIIpConnectivityMetrics connectivityMetrics = new TestIIpConnectivityMetrics() {
            @Override
            public boolean addNetdEventCallback(int callerType, INetdEventCallback callback) {
                super.addNetdEventCallback(callerType, callback);
                assertEquals(callerType, INetdEventCallback.CALLBACK_CALLER_NETWORK_WATCHLIST);
                return true;
            }

            @Override
            public boolean removeNetdEventCallback(int callerType) {
                super.removeNetdEventCallback(callerType);
                assertEquals(callerType, INetdEventCallback.CALLBACK_CALLER_NETWORK_WATCHLIST);
                return true;
            }
        };
        assertEquals(connectivityMetrics.counter, 0);
        mWatchlistService.mIpConnectivityMetrics = connectivityMetrics;
        assertTrue(mWatchlistService.startWatchlistLoggingImpl());
        assertEquals(connectivityMetrics.counter, 1);
        assertTrue(mWatchlistService.startWatchlistLoggingImpl());
        assertEquals(connectivityMetrics.counter, 1);
        assertTrue(mWatchlistService.stopWatchlistLoggingImpl());
        assertEquals(connectivityMetrics.counter, 0);
        assertTrue(mWatchlistService.stopWatchlistLoggingImpl());
        assertEquals(connectivityMetrics.counter, 0);
        assertTrue(mWatchlistService.startWatchlistLoggingImpl());
        assertEquals(connectivityMetrics.counter, 1);
        assertTrue(mWatchlistService.stopWatchlistLoggingImpl());
        assertEquals(connectivityMetrics.counter, 0);
    }

    @Test
    public void testNetworkEvents() throws Exception {
        TestIIpConnectivityMetrics connectivityMetrics = new TestIIpConnectivityMetrics();
        mWatchlistService.mIpConnectivityMetrics = connectivityMetrics;
        assertTrue(mWatchlistService.startWatchlistLoggingImpl());

        // Test DNS events
        final CountDownLatch testDnsLatch = new CountDownLatch(1);
        final Object[] dnsParams = new Object[3];
        final WatchlistLoggingHandler testDnsHandler =
                new WatchlistLoggingHandler(InstrumentationRegistry.getContext(),
                        mHandlerThread.getLooper()) {
                    @Override
                    public void asyncNetworkEvent(String host, String[] ipAddresses, int uid) {
                        dnsParams[0] = host;
                        dnsParams[1] = ipAddresses;
                        dnsParams[2] = uid;
                        testDnsLatch.countDown();
                    }
                };
        mWatchlistService.mNetworkWatchlistHandler = testDnsHandler;
        connectivityMetrics.callback.onDnsEvent(TEST_NETID, TEST_EVENT_TYPE, 0,
                TEST_HOST, TEST_IPS, TEST_IPS.length, 123L, 456);
        if (!testDnsLatch.await(NETWORK_EVENT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            fail("Timed out waiting for network event");
        }
        assertEquals(TEST_HOST, dnsParams[0]);
        for (int i = 0; i < TEST_IPS.length; i++) {
            assertEquals(TEST_IPS[i], ((String[])dnsParams[1])[i]);
        }
        assertEquals(456, dnsParams[2]);

        // Test connect events
        final CountDownLatch testConnectLatch = new CountDownLatch(1);
        final Object[] connectParams = new Object[3];
        final WatchlistLoggingHandler testConnectHandler =
                new WatchlistLoggingHandler(InstrumentationRegistry.getContext(),
                        mHandlerThread.getLooper()) {
                    @Override
                    public void asyncNetworkEvent(String host, String[] ipAddresses, int uid) {
                        connectParams[0] = host;
                        connectParams[1] = ipAddresses;
                        connectParams[2] = uid;
                        testConnectLatch.countDown();
                    }
                };
        mWatchlistService.mNetworkWatchlistHandler = testConnectHandler;
        connectivityMetrics.callback.onConnectEvent(TEST_IP, 80, 123L, 456);
        if (!testConnectLatch.await(NETWORK_EVENT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            fail("Timed out waiting for network event");
        }
        assertNull(connectParams[0]);
        assertEquals(1, ((String[]) connectParams[1]).length);
        assertEquals(TEST_IP, ((String[]) connectParams[1])[0]);
        assertEquals(456, connectParams[2]);
    }
}
