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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.Range;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@RunWith(JUnit4.class)
public final class EventIndexTest {

    private static final long CURRENT_TIMESTAMP = timestamp("01-30 18:50");
    private static final long SECONDS_PER_HOUR = 60L * 60L;
    private static final long SECONDS_PER_DAY = SECONDS_PER_HOUR * 24L;

    private TestInjector mInjector;
    private EventIndex mEventIndex;

    @Before
    public void setUp() {
        mInjector = new TestInjector(CURRENT_TIMESTAMP);
        mEventIndex = new EventIndex(mInjector);
    }

    @Test
    public void testNoEvents() {
        assertTrue(mEventIndex.isEmpty());
        assertNull(mEventIndex.getMostRecentActiveTimeSlot());
        assertTrue(mEventIndex.getActiveTimeSlots().isEmpty());
    }

    @Test
    public void testMultipleEvents() {
        mEventIndex.addEvent(timestamp("01-06 05:26"));
        mEventIndex.addEvent(timestamp("01-27 18:41"));
        mEventIndex.addEvent(timestamp("01-30 03:06"));
        mEventIndex.addEvent(timestamp("01-30 18:14"));

        assertFalse(mEventIndex.isEmpty());
        Range<Long> mostRecentSlot = mEventIndex.getMostRecentActiveTimeSlot();
        assertNotNull(mostRecentSlot);
        assertTimeSlot(timestamp("01-30 18:14"), timestamp("01-30 18:16"), mostRecentSlot);

        List<Range<Long>> slots = mEventIndex.getActiveTimeSlots();
        assertEquals(4, slots.size());
        assertTimeSlot(timestamp("01-06 00:00"), timestamp("01-07 00:00"), slots.get(0));
        assertTimeSlot(timestamp("01-27 16:00"), timestamp("01-27 20:00"), slots.get(1));
        assertTimeSlot(timestamp("01-30 03:00"), timestamp("01-30 04:00"), slots.get(2));
        assertTimeSlot(timestamp("01-30 18:14"), timestamp("01-30 18:16"), slots.get(3));
    }

    @Test
    public void testBitmapShift() {
        mEventIndex.addEvent(CURRENT_TIMESTAMP);
        List<Range<Long>> slots;

        slots = mEventIndex.getActiveTimeSlots();
        assertEquals(1, slots.size());
        assertTimeSlot(timestamp("01-30 18:50"), timestamp("01-30 18:52"), slots.get(0));

        mInjector.moveTimeForwardSeconds(SECONDS_PER_HOUR * 3L);
        mEventIndex.update();
        slots = mEventIndex.getActiveTimeSlots();
        assertEquals(1, slots.size());
        assertTimeSlot(timestamp("01-30 18:00"), timestamp("01-30 19:00"), slots.get(0));

        mInjector.moveTimeForwardSeconds(SECONDS_PER_DAY * 6L);
        mEventIndex.update();
        slots = mEventIndex.getActiveTimeSlots();
        assertEquals(1, slots.size());
        assertTimeSlot(timestamp("01-30 16:00"), timestamp("01-30 20:00"), slots.get(0));

        mInjector.moveTimeForwardSeconds(SECONDS_PER_DAY * 30L);
        mEventIndex.update();
        slots = mEventIndex.getActiveTimeSlots();
        assertEquals(1, slots.size());
        assertTimeSlot(timestamp("01-30 00:00"), timestamp("01-31 00:00"), slots.get(0));

        mInjector.moveTimeForwardSeconds(SECONDS_PER_DAY * 80L);
        mEventIndex.update();
        slots = mEventIndex.getActiveTimeSlots();
        // The event has been shifted off the left end.
        assertTrue(slots.isEmpty());
    }

    @Test
    public void testCopyConstructor() {
        mEventIndex.addEvent(timestamp("01-06 05:26"));
        mEventIndex.addEvent(timestamp("01-27 18:41"));
        mEventIndex.addEvent(timestamp("01-30 03:06"));
        mEventIndex.addEvent(timestamp("01-30 18:14"));

        List<Range<Long>> slots = mEventIndex.getActiveTimeSlots();

        EventIndex newIndex = new EventIndex(mEventIndex);
        List<Range<Long>> newSlots = newIndex.getActiveTimeSlots();

        assertEquals(slots.size(), newSlots.size());
        for (int i = 0; i < slots.size(); i++) {
            assertEquals(slots.get(i), newSlots.get(i));
        }
    }

    @Test
    public void combineEventIndexes() {
        EventIndex a = new EventIndex(mInjector);
        mInjector.mCurrentTimeMillis = timestamp("01-27 18:41");
        a.addEvent(mInjector.mCurrentTimeMillis);
        mInjector.mCurrentTimeMillis = timestamp("01-30 03:06");
        a.addEvent(mInjector.mCurrentTimeMillis);

        mInjector.mCurrentTimeMillis = CURRENT_TIMESTAMP;
        EventIndex b = new EventIndex(mInjector);
        b.addEvent(timestamp("01-06 05:26"));
        b.addEvent(timestamp("01-30 18:14"));

        EventIndex combined = EventIndex.combine(a, b);
        List<Range<Long>> slots = combined.getActiveTimeSlots();
        assertEquals(4, slots.size());
        assertTimeSlot(timestamp("01-06 00:00"), timestamp("01-07 00:00"), slots.get(0));
        assertTimeSlot(timestamp("01-27 16:00"), timestamp("01-27 20:00"), slots.get(1));
        assertTimeSlot(timestamp("01-30 03:00"), timestamp("01-30 04:00"), slots.get(2));
        assertTimeSlot(timestamp("01-30 18:14"), timestamp("01-30 18:16"), slots.get(3));
    }

    private static void assertTimeSlot(
            long expectedLower, long expectedUpper, Range<Long> actualSlot) {
        assertEquals(expectedLower, actualSlot.getLower().longValue());
        assertEquals(expectedUpper, actualSlot.getUpper().longValue());
    }

    private class TestInjector extends EventIndex.Injector {

        private long mCurrentTimeMillis;

        TestInjector(long currentTimeMillis) {
            mCurrentTimeMillis = currentTimeMillis;
        }

        private void moveTimeForwardSeconds(long seconds) {
            mCurrentTimeMillis += (seconds * 1000L);
        }

        @Override
        long currentTimeMillis() {
            return mCurrentTimeMillis;
        }
    }
}
