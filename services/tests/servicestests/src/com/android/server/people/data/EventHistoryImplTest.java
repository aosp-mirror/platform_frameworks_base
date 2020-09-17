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
import android.os.FileUtils;
import android.text.format.DateUtils;

import androidx.test.InstrumentationRegistry;

import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public final class EventHistoryImplTest {
    private static final long CURRENT_TIMESTAMP = timestamp("01-30 18:50");

    private static final Event E1 = new Event(timestamp("01-06 05:26"),
            Event.TYPE_NOTIFICATION_OPENED);
    private static final Event E2 = new Event(timestamp("01-27 18:41"),
            Event.TYPE_NOTIFICATION_OPENED);
    private static final Event E3 = new Event(timestamp("01-30 03:06"),
            Event.TYPE_SHARE_IMAGE);
    private static final Event E4 = new Event(timestamp("01-30 16:14"),
            Event.TYPE_SMS_INCOMING);
    private static final Event E5 = new Event(timestamp("01-30 18:30"),
            Event.TYPE_SMS_INCOMING);

    private static final EventIndex.Injector EVENT_INDEX_INJECTOR = new EventIndex.Injector() {
        @Override
        long currentTimeMillis() {
            return CURRENT_TIMESTAMP;
        }
    };
    private static final EventHistoryImpl.Injector EVENT_HISTORY_INJECTOR =
            new EventHistoryImpl.Injector() {
                @Override
                EventIndex createEventIndex() {
                    return new EventIndex(EVENT_INDEX_INJECTOR);
                }

                @Override
                long currentTimeMillis() {
                    return CURRENT_TIMESTAMP;
                }
            };

    private EventHistoryImpl mEventHistory;
    private File mCacheDir;
    private File mFile;
    private MockScheduledExecutorService mMockScheduledExecutorService;

    @Before
    public void setUp() {
        Context ctx = InstrumentationRegistry.getContext();
        mCacheDir = ctx.getCacheDir();
        mFile = new File(mCacheDir, "testdir");
        mMockScheduledExecutorService = new MockScheduledExecutorService();
        mEventHistory = new EventHistoryImpl(EVENT_HISTORY_INJECTOR, mFile,
                mMockScheduledExecutorService);
    }

    @After
    public void tearDown() {
        FileUtils.deleteContentsAndDir(mFile);
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

    @Test
    public void testPersistenceAndRestoration() {
        mEventHistory.addEvent(E1);
        mEventHistory.addEvent(E2);
        mEventHistory.addEvent(E3);
        mEventHistory.addEvent(E4);
        mEventHistory.addEvent(E5);

        // futures of events and event index flush.
        long futuresExecuted = mMockScheduledExecutorService.fastForwardTime(
                3L * DateUtils.MINUTE_IN_MILLIS);
        assertEquals(2, futuresExecuted);

        EventIndex indexBeforePowerOff = mEventHistory.getEventIndex(Event.ALL_EVENT_TYPES);

        resetAndLoadEventHistory();

        List<Event> events = mEventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(2, events.size());
        assertTrue(events.containsAll(Lists.newArrayList(E4, E5)));

        EventIndex indexAfterPowerOff = mEventHistory.getEventIndex(Event.ALL_EVENT_TYPES);
        assertEquals(indexBeforePowerOff, indexAfterPowerOff);
    }

    @Test
    public void testMimicDevicePowerOff() {
        mEventHistory.addEvent(E1);
        mEventHistory.addEvent(E2);
        mEventHistory.addEvent(E3);
        mEventHistory.addEvent(E4);
        mEventHistory.addEvent(E5);
        mEventHistory.saveToDisk();

        EventIndex indexBeforePowerOff = mEventHistory.getEventIndex(Event.ALL_EVENT_TYPES);

        // Ensure that futures were cancelled and the immediate flush occurred.
        assertEquals(0, mMockScheduledExecutorService.getFutures().size());

        // Expect to see 2 executes from #saveToDisk, one for events and another for index.
        assertEquals(2, mMockScheduledExecutorService.getExecutes().size());

        resetAndLoadEventHistory();

        List<Event> events = mEventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(2, events.size());
        assertTrue(events.containsAll(Lists.newArrayList(E4, E5)));

        EventIndex indexAfterPowerOff = mEventHistory.getEventIndex(Event.ALL_EVENT_TYPES);
        assertEquals(indexBeforePowerOff, indexAfterPowerOff);
    }

    @Test
    public void testOnDestroy() {
        mEventHistory.addEvent(E1);
        mEventHistory.addEvent(E2);
        mEventHistory.addEvent(E3);
        mEventHistory.addEvent(E4);
        mEventHistory.addEvent(E5);
        mEventHistory.saveToDisk();

        mEventHistory.onDestroy();

        List<Event> events = mEventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertTrue(events.isEmpty());

        EventIndex index = mEventHistory.getEventIndex(Event.ALL_EVENT_TYPES);
        assertTrue(index.isEmpty());
    }

    @Test
    public void testEventHistoriesImplFromDisk() {
        mEventHistory.addEvent(E1);
        mEventHistory.addEvent(E2);
        mEventHistory.addEvent(E3);
        mEventHistory.addEvent(E4);
        mEventHistory.addEvent(E5);
        mEventHistory.saveToDisk();

        EventIndex indexBefore = mEventHistory.getEventIndex(Event.ALL_EVENT_TYPES);

        Map<String, EventHistoryImpl> map = EventHistoryImpl.eventHistoriesImplFromDisk(
                EVENT_HISTORY_INJECTOR, mCacheDir, mMockScheduledExecutorService);
        assertEquals(1, map.size());
        assertTrue(map.containsKey("testdir"));

        List<Event> events = map.get("testdir").queryEvents(Event.ALL_EVENT_TYPES, 0L,
                Long.MAX_VALUE);
        assertEquals(2, events.size());
        assertTrue(events.containsAll(Lists.newArrayList(E4, E5)));

        EventIndex indexAfter = map.get("testdir").getEventIndex(Event.ALL_EVENT_TYPES);
        assertEquals(indexBefore, indexAfter);
    }

    private void resetAndLoadEventHistory() {
        mEventHistory = new EventHistoryImpl(EVENT_HISTORY_INJECTOR, mFile,
                mMockScheduledExecutorService);
        mEventHistory.loadFromDisk();
    }
}
