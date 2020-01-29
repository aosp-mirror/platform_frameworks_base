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

import com.google.android.collect.Sets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@RunWith(JUnit4.class)
public final class EventHistoryImplTest {

    private static final long CURRENT_TIMESTAMP = timestamp("01-30 18:50");

    private static final Event E1 = new Event(timestamp("01-06 05:26"),
            Event.TYPE_NOTIFICATION_OPENED);
    private static final Event E2 = new Event(timestamp("01-27 18:41"),
            Event.TYPE_NOTIFICATION_OPENED);
    private static final Event E3 = new Event(timestamp("01-30 03:06"),
            Event.TYPE_SHARE_IMAGE);
    private static final Event E4 = new Event(timestamp("01-30 18:14"),
            Event.TYPE_SMS_INCOMING);

    private EventHistoryImpl mEventHistory;

    @Before
    public void setUp() {
        EventIndex.Injector eventIndexInjector = new EventIndex.Injector() {
            @Override
            long currentTimeMillis() {
                return CURRENT_TIMESTAMP;
            }
        };
        EventHistoryImpl.Injector eventHistoryInjector = new EventHistoryImpl.Injector() {
            @Override
            EventIndex createEventIndex() {
                return new EventIndex(eventIndexInjector);
            }
        };
        mEventHistory = new EventHistoryImpl(eventHistoryInjector);
    }

    @Test
    public void testNoEvents() {
        EventIndex eventIndex = mEventHistory.getEventIndex(Event.ALL_EVENT_TYPES);
        assertTrue(eventIndex.isEmpty());

        List<Event> events = mEventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, 999L);
        assertTrue(events.isEmpty());
    }

    @Test
    public void testMultipleEvents() {
        mEventHistory.addEvent(E1);
        mEventHistory.addEvent(E2);
        mEventHistory.addEvent(E3);
        mEventHistory.addEvent(E4);

        EventIndex eventIndex = mEventHistory.getEventIndex(Event.ALL_EVENT_TYPES);
        assertEquals(4, eventIndex.getActiveTimeSlots().size());

        List<Event> events = mEventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(4, events.size());
    }

    @Test
    public void testQuerySomeEventTypes() {
        mEventHistory.addEvent(E1);
        mEventHistory.addEvent(E2);
        mEventHistory.addEvent(E3);
        mEventHistory.addEvent(E4);

        EventIndex eventIndex = mEventHistory.getEventIndex(Event.NOTIFICATION_EVENT_TYPES);
        assertEquals(2, eventIndex.getActiveTimeSlots().size());

        List<Event> events = mEventHistory.queryEvents(
                Event.NOTIFICATION_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(2, events.size());
    }

    @Test
    public void testQuerySingleEventType() {
        mEventHistory.addEvent(E1);
        mEventHistory.addEvent(E2);
        mEventHistory.addEvent(E3);
        mEventHistory.addEvent(E4);

        EventIndex eventIndex = mEventHistory.getEventIndex(Event.TYPE_SHARE_IMAGE);
        assertEquals(1, eventIndex.getActiveTimeSlots().size());

        List<Event> events = mEventHistory.queryEvents(
                Sets.newArraySet(Event.TYPE_SHARE_IMAGE), 0L, Long.MAX_VALUE);
        assertEquals(1, events.size());
    }
}
