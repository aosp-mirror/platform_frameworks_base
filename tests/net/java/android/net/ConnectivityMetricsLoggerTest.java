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

package android.net;

import android.os.Bundle;
import android.os.Parcel;
import java.util.List;
import junit.framework.TestCase;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConnectivityMetricsLoggerTest extends TestCase {

    // use same Parcel object everywhere for pointer equality
    static final Bundle FAKE_EV = new Bundle();
    static final int FAKE_COMPONENT = 1;
    static final int FAKE_EVENT = 2;

    @Mock IConnectivityMetricsLogger mService;
    ArgumentCaptor<ConnectivityMetricsEvent> evCaptor;
    ArgumentCaptor<ConnectivityMetricsEvent[]> evArrayCaptor;

    ConnectivityMetricsLogger mLog;

    public void setUp() {
        MockitoAnnotations.initMocks(this);
        evCaptor = ArgumentCaptor.forClass(ConnectivityMetricsEvent.class);
        evArrayCaptor = ArgumentCaptor.forClass(ConnectivityMetricsEvent[].class);
        mLog = new ConnectivityMetricsLogger(mService);
    }

    public void testLogEvents() throws Exception {
        mLog.logEvent(1, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);
        mLog.logEvent(2, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);
        mLog.logEvent(3, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);

        List<ConnectivityMetricsEvent> gotEvents = verifyEvents(3);
        assertEventsEqual(expectedEvent(1), gotEvents.get(0));
        assertEventsEqual(expectedEvent(2), gotEvents.get(1));
        assertEventsEqual(expectedEvent(3), gotEvents.get(2));
    }

    public void testLogEventTriggerThrottling() throws Exception {
        when(mService.logEvent(any())).thenReturn(1234L);

        mLog.logEvent(1, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);
        mLog.logEvent(2, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);

        List<ConnectivityMetricsEvent> gotEvents = verifyEvents(1);
        assertEventsEqual(expectedEvent(1), gotEvents.get(0));
    }

    public void testLogEventFails() throws Exception {
        when(mService.logEvent(any())).thenReturn(-1L); // Error.

        mLog.logEvent(1, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);
        mLog.logEvent(2, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);

        List<ConnectivityMetricsEvent> gotEvents = verifyEvents(1);
        assertEventsEqual(expectedEvent(1), gotEvents.get(0));
    }

    public void testLogEventWhenThrottling() throws Exception {
        when(mService.logEvent(any())).thenReturn(Long.MAX_VALUE); // Throttled

        // No events are logged. The service is only called once
        // After that, throttling state is maintained locally.
        mLog.logEvent(1, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);
        mLog.logEvent(2, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);

        List<ConnectivityMetricsEvent> gotEvents = verifyEvents(1);
        assertEventsEqual(expectedEvent(1), gotEvents.get(0));
    }

    public void testLogEventRecoverFromThrottling() throws Exception {
        final long throttleTimeout = System.currentTimeMillis() + 10;
        when(mService.logEvent(any())).thenReturn(throttleTimeout, 0L);

        mLog.logEvent(1, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);
        mLog.logEvent(2, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);
        mLog.logEvent(3, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);
        Thread.sleep(100);
        mLog.logEvent(53, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);

        List<ConnectivityMetricsEvent> gotEvents = verifyEvents(1);
        assertEventsEqual(expectedEvent(1), gotEvents.get(0));

        verify(mService, times(1)).logEvents(evArrayCaptor.capture());
        ConnectivityMetricsEvent[] gotOtherEvents = evArrayCaptor.getAllValues().get(0);
        assertEquals(ConnectivityMetricsLogger.TAG_SKIPPED_EVENTS, gotOtherEvents[0].eventTag);
        assertEventsEqual(expectedEvent(53), gotOtherEvents[1]);
    }

    List<ConnectivityMetricsEvent> verifyEvents(int n) throws Exception {
        verify(mService, times(n)).logEvent(evCaptor.capture());
        return evCaptor.getAllValues();
    }

    static ConnectivityMetricsEvent expectedEvent(int timestamp) {
        return new ConnectivityMetricsEvent((long)timestamp, FAKE_COMPONENT, FAKE_EVENT, FAKE_EV);
    }

    /** Outer equality for ConnectivityMetricsEvent to avoid overriding equals() and hashCode(). */
    static void assertEventsEqual(ConnectivityMetricsEvent expected, ConnectivityMetricsEvent got) {
        assertEquals(expected.timestamp, got.timestamp);
        assertEquals(expected.componentTag, got.componentTag);
        assertEquals(expected.eventTag, got.eventTag);
        assertEquals(expected.data, got.data);
    }
}
