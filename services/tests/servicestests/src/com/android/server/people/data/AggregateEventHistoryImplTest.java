/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.data;

import static com.android.server.people.data.TestUtils.timestamp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.List;

@RunWith(JUnit4.class)
public final class AggregateEventHistoryImplTest {

    private static final long CURRENT_TIMESTAMP = timestamp("01-30 18:50");

    private static final Event E1 = new Event(timestamp("01-06 05:26"),
            Event.TYPE_NOTIFICATION_OPENED);
    private static final Event E2 = new Event(timestamp("01-27 18:41"),
            Event.TYPE_NOTIFICATION_OPENED);
    private static final Event E3 = new Event(timestamp("01-30 03:06"),
            Event.TYPE_SMS_OUTGOING);
    private static final Event E4 = new Event(timestamp("01-30 18:14"),
            Event.TYPE_SMS_INCOMING);

    private EventHistoryImpl mEventHistory1;
    private EventHistoryImpl mEventHistory2;

    private AggregateEventHistoryImpl mAggEventHistory;

    private EventIndex.Injector mInjector = new EventIndex.Injector() {
        @Override
        long currentTimeMillis() {
            return CURRENT_TIMESTAMP;
        }
    };

    @Before
    public void setUp() {
        mAggEventHistory = new AggregateEventHistoryImpl();

        EventHistoryImpl.Injector injector = new EventHistoryImplInjector();

        Context ctx = InstrumentationRegistry.getContext();
        File testDir = new File(ctx.getCacheDir(), "testdir");
        MockScheduledExecutorService mockScheduledExecutorService =
                new MockScheduledExecutorService();

        mEventHistory1 = new EventHistoryImpl(injector, testDir, mockScheduledExecutorService);
        mEventHistory1.addEvent(E1);
        mEventHistory1.addEvent(E2);

        mEventHistory2 = new EventHistoryImpl(injector, testDir, mockScheduledExecutorService);
        mEventHistory2.addEvent(E3);
        mEventHistory2.addEvent(E4);
    }

    @Test
    public void testEmptyAggregateEventHistory() {
        assertTrue(mAggEventHistory.getEventIndex(Event.TYPE_SHORTCUT_INVOCATION).isEmpty());
        assertTrue(mAggEventHistory.getEventIndex(Event.ALL_EVENT_TYPES).isEmpty());
        assertTrue(mAggEventHistory.queryEvents(
                Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE).isEmpty());
    }

    @Test
    public void testQueryEventIndexForSingleEventType() {
        mAggEventHistory.addEventHistory(mEventHistory1);
        mAggEventHistory.addEventHistory(mEventHistory2);

        EventIndex eventIndex;

        eventIndex = mAggEventHistory.getEventIndex(Event.TYPE_NOTIFICATION_OPENED);
        assertEquals(2, eventIndex.getActiveTimeSlots().size());

        eventIndex = mAggEventHistory.getEventIndex(Event.TYPE_SMS_OUTGOING);
        assertEquals(1, eventIndex.getActiveTimeSlots().size());

        eventIndex = mAggEventHistory.getEventIndex(Event.TYPE_SHORTCUT_INVOCATION);
        assertTrue(eventIndex.isEmpty());
    }

    @Test
    public void testQueryEventIndexForMultipleEventTypes() {
        mAggEventHistory.addEventHistory(mEventHistory1);
        mAggEventHistory.addEventHistory(mEventHistory2);

        EventIndex eventIndex;

        eventIndex = mAggEventHistory.getEventIndex(Event.SMS_EVENT_TYPES);
        assertEquals(2, eventIndex.getActiveTimeSlots().size());

        eventIndex = mAggEventHistory.getEventIndex(Event.ALL_EVENT_TYPES);
        assertEquals(4, eventIndex.getActiveTimeSlots().size());
    }

    @Test
    public void testQueryEvents() {
        mAggEventHistory.addEventHistory(mEventHistory1);
        mAggEventHistory.addEventHistory(mEventHistory2);

        List<Event> events;

        events = mAggEventHistory.queryEvents(Event.NOTIFICATION_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(2, events.size());

        events = mAggEventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(4, events.size());
    }

    private class EventHistoryImplInjector extends EventHistoryImpl.Injector {

        EventIndex createEventIndex() {
            return new EventIndex(mInjector);
        }
    }
}
