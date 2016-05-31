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

package android.net.metrics;

import android.os.Bundle;
import android.os.Parcel;
import android.net.ConnectivityMetricsEvent;
import android.net.IConnectivityMetricsLogger;

import junit.framework.TestCase;
import org.junit.Before;
import org.junit.Test;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

public class IpConnectivityLogTest extends TestCase {

    // use same Parcel object everywhere for pointer equality
    static final Bundle FAKE_EV = new Bundle();

    @Mock IConnectivityMetricsLogger mService;
    ArgumentCaptor<ConnectivityMetricsEvent> evCaptor;

    IpConnectivityLog mLog;

    public void setUp() {
        MockitoAnnotations.initMocks(this);
        evCaptor = ArgumentCaptor.forClass(ConnectivityMetricsEvent.class);
        mLog = new IpConnectivityLog(mService);
    }

    public void testLogEvents() throws Exception {
        assertTrue(mLog.log(1, FAKE_EV));
        assertTrue(mLog.log(2, FAKE_EV));
        assertTrue(mLog.log(3, FAKE_EV));

        List<ConnectivityMetricsEvent> gotEvents = verifyEvents(3);
        assertEventsEqual(expectedEvent(1), gotEvents.get(0));
        assertEventsEqual(expectedEvent(2), gotEvents.get(1));
        assertEventsEqual(expectedEvent(3), gotEvents.get(2));
    }

    public void testLogEventTriggerThrottling() throws Exception {
        when(mService.logEvent(any())).thenReturn(1234L);

        assertFalse(mLog.log(1, FAKE_EV));
    }

    public void testLogEventFails() throws Exception {
        when(mService.logEvent(any())).thenReturn(-1L); // Error.

        assertFalse(mLog.log(1, FAKE_EV));
    }

    public void testLogEventWhenThrottling() throws Exception {
        when(mService.logEvent(any())).thenReturn(Long.MAX_VALUE); // Throttled

        // No events are logged. The service is only called once
        // After that, throttling state is maintained locally.
        assertFalse(mLog.log(1, FAKE_EV));
        assertFalse(mLog.log(2, FAKE_EV));

        List<ConnectivityMetricsEvent> gotEvents = verifyEvents(1);
        assertEventsEqual(expectedEvent(1), gotEvents.get(0));
    }

    public void testLogEventRecoverFromThrottling() throws Exception {
        final long throttleTimeout = System.currentTimeMillis() + 50;
        when(mService.logEvent(any())).thenReturn(throttleTimeout, 0L);

        assertFalse(mLog.log(1, FAKE_EV));
        new Thread() {
            public void run() {
                busySpinLog();
            }
        }.start();

        List<ConnectivityMetricsEvent> gotEvents = verifyEvents(2, 200);
        assertEventsEqual(expectedEvent(1), gotEvents.get(0));
        assertEventsEqual(expectedEvent(2), gotEvents.get(1));
    }

    public void testLogEventRecoverFromThrottlingWithMultipleCallers() throws Exception {
        final long throttleTimeout = System.currentTimeMillis() + 50;
        when(mService.logEvent(any())).thenReturn(throttleTimeout, 0L);

        assertFalse(mLog.log(1, FAKE_EV));
        final int nCallers = 10;
        for (int i = 0; i < nCallers; i++) {
            new Thread() {
                public void run() {
                    busySpinLog();
                }
            }.start();
        }

        List<ConnectivityMetricsEvent> gotEvents = verifyEvents(1 + nCallers, 200);
        assertEventsEqual(expectedEvent(1), gotEvents.get(0));
        for (int i = 0; i < nCallers; i++) {
            assertEventsEqual(expectedEvent(2), gotEvents.get(1 + i));
        }
    }

    void busySpinLog() {
        final long timeout = 200;
        final long stop = System.currentTimeMillis() + timeout;
        try {
            while (System.currentTimeMillis() < stop) {
                if (mLog.log(2, FAKE_EV)) {
                    return;
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException e) { }
    }

    List<ConnectivityMetricsEvent> verifyEvents(int n) throws Exception {
        verify(mService, times(n)).logEvent(evCaptor.capture());
        return evCaptor.getAllValues();
    }

    List<ConnectivityMetricsEvent> verifyEvents(int n, int timeoutMs) throws Exception {
        verify(mService, timeout(timeoutMs).times(n)).logEvent(evCaptor.capture());
        return evCaptor.getAllValues();
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
}
