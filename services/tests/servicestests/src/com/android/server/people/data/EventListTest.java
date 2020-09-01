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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@RunWith(JUnit4.class)
public final class EventListTest {

    private static final Event E1 = new Event(101L, Event.TYPE_NOTIFICATION_OPENED);
    private static final Event E2 = new Event(103L, Event.TYPE_NOTIFICATION_OPENED);
    private static final Event E3 = new Event(107L, Event.TYPE_SHARE_IMAGE);
    private static final Event E4 = new Event(109L, Event.TYPE_SMS_INCOMING);

    private EventList mEventList;

    @Before
    public void setUp() {
        mEventList = new EventList();
    }

    @Test
    public void testQueryEmptyEventList() {
        List<Event> events = mEventList.queryEvents(Event.ALL_EVENT_TYPES, 0L, 999L);
        assertTrue(events.isEmpty());
    }

    @Test
    public void testAddAndQueryEvents() {
        List<Event> in = Lists.newArrayList(E1, E2, E3, E4);
        for (Event e : in) {
            mEventList.add(e);
        }

        List<Event> out = mEventList.queryEvents(Event.ALL_EVENT_TYPES, 0L, 999L);
        assertEventListEquals(in, out);
    }

    @Test
    public void testAddEventsNotInOrder() {
        mEventList.add(E3);
        mEventList.add(E1);
        mEventList.add(E4);
        mEventList.add(E2);

        List<Event> out = mEventList.queryEvents(Event.ALL_EVENT_TYPES, 0L, 999L);
        List<Event> expected = Lists.newArrayList(E1, E2, E3, E4);
        assertEventListEquals(expected, out);
    }

    @Test
    public void testQueryEventsByType() {
        mEventList.add(E1);
        mEventList.add(E2);
        mEventList.add(E3);
        mEventList.add(E4);

        List<Event> out = mEventList.queryEvents(
                Sets.newArraySet(Event.TYPE_NOTIFICATION_OPENED), 0L, 999L);
        assertEventListEquals(Lists.newArrayList(E1, E2), out);
    }

    @Test
    public void testQueryEventsByTimeRange() {
        mEventList.add(E1);
        mEventList.add(E2);
        mEventList.add(E3);
        mEventList.add(E4);

        List<Event> out = mEventList.queryEvents(Event.ALL_EVENT_TYPES, 103L, 109L);
        // Only E2 and E3 are in the time range [103L, 109L).
        assertEventListEquals(Lists.newArrayList(E2, E3), out);
    }

    @Test
    public void testQueryEventsOutOfRange() {
        mEventList.add(E1);
        mEventList.add(E2);
        mEventList.add(E3);
        mEventList.add(E4);

        List<Event> out = mEventList.queryEvents(Event.ALL_EVENT_TYPES, 900L, 900L);
        assertTrue(out.isEmpty());
    }

    @Test
    public void testAddDuplicateEvents() {
        mEventList.add(E1);
        mEventList.add(E2);
        mEventList.add(E2);
        mEventList.add(E3);
        mEventList.add(E2);
        mEventList.add(E3);
        mEventList.add(E3);
        mEventList.add(E4);
        mEventList.add(E1);
        mEventList.add(E3);
        mEventList.add(E2);

        List<Event> out = mEventList.queryEvents(Event.ALL_EVENT_TYPES, 0L, 999L);
        List<Event> expected = Lists.newArrayList(E1, E2, E3, E4);
        assertEventListEquals(expected, out);
    }

    private static void assertEventListEquals(List<Event> expected, List<Event> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).getTimestamp(), actual.get(i).getTimestamp());
            assertEquals(expected.get(i).getType(), actual.get(i).getType());
        }
    }
}
