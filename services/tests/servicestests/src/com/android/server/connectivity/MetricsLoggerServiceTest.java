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
import android.os.Bundle;
import android.os.RemoteException;
import static android.net.ConnectivityMetricsEvent.Reference;

import junit.framework.TestCase;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/*
 * TODO:
 *  - allow overriding MetricsLoggerService constants in tests.
 *  - test intents are correctly sent after the notification threshold.
 *  - test oldest events are correctly pushed out when internal deque is full.
 *  - test throttling triggers correctly.
 */
public class MetricsLoggerServiceTest extends TestCase {

    static final int COMPONENT_TAG = 1;
    static final long N_EVENTS = 10L;
    static final ConnectivityMetricsEvent EVENTS[] = new ConnectivityMetricsEvent[(int)N_EVENTS];
    static {
        for (int i = 0; i < N_EVENTS; i++) {
            EVENTS[i] = new ConnectivityMetricsEvent(i, COMPONENT_TAG, i, new Bundle());
        }
    }

    static final ConnectivityMetricsEvent NO_EVENTS[] = new ConnectivityMetricsEvent[0];

    @Mock Context mContext;
    MetricsLoggerService mService;

    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mService = new MetricsLoggerService(mContext);
        mService.onStart();
    }

    public void testGetNoEvents() throws Exception {
        Reference r = new Reference(0);
        assertArrayEquals(NO_EVENTS, mService.mBinder.getEvents(r));
        assertEquals(0, r.getValue());
    }

    public void testLogAndGetEvents() throws Exception {
        mService.mBinder.logEvents(EVENTS);

        Reference r = new Reference(0);

        assertArrayEquals(EVENTS, mService.mBinder.getEvents(r));
        assertEquals(N_EVENTS, r.getValue());

        assertArrayEquals(NO_EVENTS, mService.mBinder.getEvents(r));
        assertEquals(N_EVENTS, r.getValue());
    }

    public void testLogOneByOne() throws Exception {
        for (ConnectivityMetricsEvent ev : EVENTS) {
            mService.mBinder.logEvent(ev);
        }

        Reference r = new Reference(0);

        assertArrayEquals(EVENTS, mService.mBinder.getEvents(r));
        assertEquals(N_EVENTS, r.getValue());

        assertArrayEquals(NO_EVENTS, mService.mBinder.getEvents(r));
        assertEquals(N_EVENTS, r.getValue());
    }

    public void testInterleavedLogAndGet() throws Exception {
        mService.mBinder.logEvents(Arrays.copyOfRange(EVENTS, 0, 3));

        Reference r = new Reference(0);

        assertArrayEquals(Arrays.copyOfRange(EVENTS, 0, 3), mService.mBinder.getEvents(r));
        assertEquals(3, r.getValue());

        mService.mBinder.logEvents(Arrays.copyOfRange(EVENTS, 3, 8));
        mService.mBinder.logEvents(Arrays.copyOfRange(EVENTS, 8, 10));

        assertArrayEquals(Arrays.copyOfRange(EVENTS, 3, 10), mService.mBinder.getEvents(r));
        assertEquals(N_EVENTS, r.getValue());

        assertArrayEquals(NO_EVENTS, mService.mBinder.getEvents(r));
        assertEquals(N_EVENTS, r.getValue());
    }

    public void testMultipleGetAll() throws Exception {
        mService.mBinder.logEvents(Arrays.copyOf(EVENTS, 3));

        Reference r1 = new Reference(0);
        assertArrayEquals(Arrays.copyOf(EVENTS, 3), mService.mBinder.getEvents(r1));
        assertEquals(3, r1.getValue());

        mService.mBinder.logEvents(Arrays.copyOfRange(EVENTS, 3, 10));

        Reference r2 = new Reference(0);
        assertArrayEquals(EVENTS, mService.mBinder.getEvents(r2));
        assertEquals(N_EVENTS, r2.getValue());
    }

    public void testLogAndDumpConcurrently() throws Exception {
        for (int i = 0; i < 50; i++) {
            mContext = null;
            mService = null;
            setUp();
            logAndDumpConcurrently();
        }
    }

    public void logAndDumpConcurrently() throws Exception {
        final CountDownLatch latch = new CountDownLatch((int)N_EVENTS);
        final FileDescriptor fd = new FileOutputStream("/dev/null").getFD();

        for (ConnectivityMetricsEvent ev : EVENTS) {
            new Thread() {
                public void run() {
                    mService.mBinder.logEvent(ev);
                    latch.countDown();
                }
            }.start();
        }

        new Thread() {
            public void run() {
                while (latch.getCount() > 0) {
                    mService.mBinder.dump(fd, new String[]{"--all"});
                }
            }
        }.start();

        latch.await(100, TimeUnit.MILLISECONDS);

        Reference r = new Reference(0);
        ConnectivityMetricsEvent[] got = mService.mBinder.getEvents(r);
        Arrays.sort(got, new EventComparator());
        assertArrayEquals(EVENTS, got);
        assertEquals(N_EVENTS, r.getValue());
    }

    static class EventComparator implements Comparator<ConnectivityMetricsEvent> {
        public int compare(ConnectivityMetricsEvent ev1, ConnectivityMetricsEvent ev2) {
            return Long.compare(ev1.timestamp, ev2.timestamp);
        }
        public boolean equal(Object o) {
            return o instanceof EventComparator;
        }
    };
}
