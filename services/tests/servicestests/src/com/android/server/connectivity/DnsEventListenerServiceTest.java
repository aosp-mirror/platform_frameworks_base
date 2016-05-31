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

import android.net.ConnectivityManager.NetworkCallback;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.metrics.DnsEvent;
import android.net.metrics.IDnsEventListener;
import android.net.metrics.IpConnectivityLog;

import junit.framework.TestCase;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;

public class DnsEventListenerServiceTest extends TestCase {

    // TODO: read from DnsEventListenerService after this constant is read from system property
    static final int BATCH_SIZE = 100;
    static final int EVENT_TYPE = IDnsEventListener.EVENT_GETADDRINFO;
    // TODO: read from IDnsEventListener
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

    DnsEventListenerService mDnsService;

    @Mock ConnectivityManager mCm;
    @Mock IpConnectivityLog mLog;
    ArgumentCaptor<NetworkCallback> mCallbackCaptor;
    ArgumentCaptor<DnsEvent> mEvCaptor;

    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mCallbackCaptor = ArgumentCaptor.forClass(NetworkCallback.class);
        mEvCaptor = ArgumentCaptor.forClass(DnsEvent.class);
        mDnsService = new DnsEventListenerService(mCm, mLog);

        verify(mCm, times(1)).registerNetworkCallback(any(), mCallbackCaptor.capture());
    }

    public void testOneBatch() throws Exception {
        log(105, LATENCIES);
        log(106, Arrays.copyOf(LATENCIES, BATCH_SIZE - 1)); // one lookup short of a batch event

        verifyLoggedEvents(new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES));

        log(106, Arrays.copyOfRange(LATENCIES, BATCH_SIZE - 1, BATCH_SIZE));

        mEvCaptor = ArgumentCaptor.forClass(DnsEvent.class); // reset argument captor
        verifyLoggedEvents(
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(106, EVENT_TYPES, RETURN_CODES, LATENCIES));
    }

    public void testSeveralBatches() throws Exception {
        log(105, LATENCIES);
        log(106, LATENCIES);
        log(105, LATENCIES);
        log(107, LATENCIES);

        verifyLoggedEvents(
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(106, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(107, EVENT_TYPES, RETURN_CODES, LATENCIES));
    }

    public void testBatchAndNetworkLost() throws Exception {
        byte[] eventTypes = Arrays.copyOf(EVENT_TYPES, 20);
        byte[] returnCodes = Arrays.copyOf(RETURN_CODES, 20);
        int[] latencies = Arrays.copyOf(LATENCIES, 20);

        log(105, LATENCIES);
        log(105, latencies);
        mCallbackCaptor.getValue().onLost(new Network(105));
        log(105, LATENCIES);

        verifyLoggedEvents(
            new DnsEvent(105, eventTypes, returnCodes, latencies),
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES));
    }

    public void testConcurrentBatchesAndDumps() throws Exception {
        final long stop = System.currentTimeMillis() + 100;
        final PrintWriter pw = new PrintWriter(new FileOutputStream("/dev/null"));
        new Thread() {
            public void run() {
                while (System.currentTimeMillis() < stop) {
                    mDnsService.dump(pw);
                }
            }
        }.start();

        logAsync(105, LATENCIES);
        logAsync(106, LATENCIES);
        logAsync(107, LATENCIES);

        verifyLoggedEvents(500,
            new DnsEvent(105, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(106, EVENT_TYPES, RETURN_CODES, LATENCIES),
            new DnsEvent(107, EVENT_TYPES, RETURN_CODES, LATENCIES));
    }

    public void testConcurrentBatchesAndNetworkLoss() throws Exception {
        logAsync(105, LATENCIES);
        Thread.sleep(10L);
        // call onLost() asynchronously to logAsync's onDnsEvent() calls.
        mCallbackCaptor.getValue().onLost(new Network(105));

        // do not verify unpredictable batch
        verify(mLog, timeout(500).times(1)).log(any());
    }

    void log(int netId, int[] latencies) {
        for (int l : latencies) {
            mDnsService.onDnsEvent(netId, EVENT_TYPE, RETURN_CODE, l);
        }
    }

    void logAsync(int netId, int[] latencies) {
        new Thread() {
            public void run() {
                log(netId, latencies);
            }
        }.start();
    }

    void verifyLoggedEvents(DnsEvent... expected) {
        verifyLoggedEvents(0, expected);
    }

    void verifyLoggedEvents(int wait, DnsEvent... expectedEvents) {
        verify(mLog, timeout(wait).times(expectedEvents.length)).log(mEvCaptor.capture());
        for (DnsEvent got : mEvCaptor.getAllValues()) {
            OptionalInt index = IntStream.range(0, expectedEvents.length)
                    .filter(i -> eventsEqual(expectedEvents[i], got))
                    .findFirst();
            // Don't match same expected event more than once.
            index.ifPresent(i -> expectedEvents[i] = null);
            assertTrue(index.isPresent());
        }
    }

    /** equality function for DnsEvent to avoid overriding equals() and hashCode(). */
    static boolean eventsEqual(DnsEvent expected, DnsEvent got) {
        return (expected == got) || ((expected != null) && (got != null)
                && (expected.netId == got.netId)
                && Arrays.equals(expected.eventTypes, got.eventTypes)
                && Arrays.equals(expected.returnCodes, got.returnCodes)
                && Arrays.equals(expected.latenciesMs, got.latenciesMs));
    }
}
