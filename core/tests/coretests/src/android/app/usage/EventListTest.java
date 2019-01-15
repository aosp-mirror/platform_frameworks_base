/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class EventListTest {
    private static final String TAG = EventListTest.class.getSimpleName();

    private UsageEvents.Event getUsageEvent(long timeStamp) {
        final UsageEvents.Event event = new UsageEvents.Event();
        event.mTimeStamp = timeStamp;
        return event;
    }

    private static String getListTimeStamps(EventList list) {
        final StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < list.size() - 1; i++) {
            builder.append(list.get(i).mTimeStamp);
            builder.append(", ");
        }
        builder.append(list.get(list.size() - 1).mTimeStamp);
        builder.append("]");
        return builder.toString();
    }

    private static void assertSorted(EventList eventList) {
        for (int i = 1; i < eventList.size(); i++) {
            final long lastTimeStamp = eventList.get(i - 1).mTimeStamp;
            if (eventList.get(i).mTimeStamp < lastTimeStamp) {
                Log.e(TAG, "Unsorted timestamps in list: " + getListTimeStamps(eventList));
                fail("Timestamp " + eventList.get(i).mTimeStamp + " at " + i
                        + " follows larger timestamp " + lastTimeStamp);
            }
        }
    }

    @Test
    public void testInsertsSortedRandom() {
        final Random random = new Random(128);
        final EventList listUnderTest = new EventList();
        for (int i = 0; i < 100; i++) {
            listUnderTest.insert(getUsageEvent(random.nextLong()));
        }
        assertSorted(listUnderTest);
    }

    @Test
    public void testInsertsSortedWithDuplicates() {
        final Random random = new Random(256);
        final EventList listUnderTest = new EventList();
        for (int i = 0; i < 10; i++) {
            final long randomTimeStamp = random.nextLong();
            for (int j = 0; j < 10; j++) {
                listUnderTest.insert(getUsageEvent(randomTimeStamp));
            }
        }
        assertSorted(listUnderTest);
    }

    @Test
    public void testFirstIndexOnOrAfter() {
        final EventList listUnderTest = new EventList();
        listUnderTest.insert(getUsageEvent(2));
        listUnderTest.insert(getUsageEvent(5));
        listUnderTest.insert(getUsageEvent(5));
        listUnderTest.insert(getUsageEvent(5));
        listUnderTest.insert(getUsageEvent(8));
        assertTrue(listUnderTest.firstIndexOnOrAfter(1) == 0);
        assertTrue(listUnderTest.firstIndexOnOrAfter(2) == 0);
        assertTrue(listUnderTest.firstIndexOnOrAfter(3) == 1);
        assertTrue(listUnderTest.firstIndexOnOrAfter(4) == 1);
        assertTrue(listUnderTest.firstIndexOnOrAfter(5) == 1);
        assertTrue(listUnderTest.firstIndexOnOrAfter(6) == 4);
        assertTrue(listUnderTest.firstIndexOnOrAfter(7) == 4);
        assertTrue(listUnderTest.firstIndexOnOrAfter(8) == 4);
        assertTrue(listUnderTest.firstIndexOnOrAfter(9) == listUnderTest.size());
        assertTrue(listUnderTest.firstIndexOnOrAfter(100) == listUnderTest.size());

        listUnderTest.clear();
        assertTrue(listUnderTest.firstIndexOnOrAfter(5) == 0);
        assertTrue(listUnderTest.firstIndexOnOrAfter(100) == 0);
    }

    @Test
    public void testClear() {
        final EventList listUnderTest = new EventList();
        for (int i = 1; i <= 100; i++) {
            listUnderTest.insert(getUsageEvent(i));
        }
        listUnderTest.clear();
        assertEquals(0, listUnderTest.size());
    }

    @Test
    public void testSize() {
        final EventList listUnderTest = new EventList();
        for (int i = 1; i <= 100; i++) {
            listUnderTest.insert(getUsageEvent(i));
        }
        assertEquals(100, listUnderTest.size());
    }
}
