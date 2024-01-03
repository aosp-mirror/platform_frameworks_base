/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.app.usage;

import static android.app.usage.Flags.FLAG_FILTER_BASED_EVENT_QUERY_API;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.app.usage.UsageEvents.Event;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UsageEventsQueryTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    @RequiresFlagsEnabled(FLAG_FILTER_BASED_EVENT_QUERY_API)
    public void testQueryDuration() {
        // Test with negative beginTimeMillis.
        long beginTimeMillis = -100;
        long endTimeMillis = 100;
        try {
            UsageEventsQuery query = new UsageEventsQuery.Builder(beginTimeMillis, endTimeMillis)
                    .build();
            fail("beginTimeMillis should be a non-negative timestamp measured as the number of"
                    + " milliseconds since 1970-01-01T00:00:00Z.");
        } catch (IllegalArgumentException e) {
            // Expected, fall through;
        }

        // Test with negative endTimeMillis.
        beginTimeMillis = 1001;
        endTimeMillis = -1;
        try {
            UsageEventsQuery query = new UsageEventsQuery.Builder(beginTimeMillis, endTimeMillis)
                    .build();
            fail("endTimeMillis should be a non-negative timestamp measured as the number of"
                    + " milliseconds since 1970-01-01T00:00:00Z.");
        } catch (IllegalArgumentException e) {
            // Expected, fall through;
        }

        // Test with beginTimeMillis < endTimeMillis;
        beginTimeMillis = 2001;
        endTimeMillis = 1000;
        try {
            UsageEventsQuery query = new UsageEventsQuery.Builder(beginTimeMillis, endTimeMillis)
                    .build();
            fail("beginTimeMillis should be smaller than endTimeMillis");
        } catch (IllegalArgumentException e) {
            // Expected, fall through;
        }

        // Test with beginTimeMillis == endTimeMillis, valid.
        beginTimeMillis = 1001;
        endTimeMillis = 1001;
        try {
            UsageEventsQuery query = new UsageEventsQuery.Builder(beginTimeMillis, endTimeMillis)
                    .build();
            assertEquals(query.getBeginTimeMillis(), query.getEndTimeMillis());
        } catch (IllegalArgumentException e) {
            // Not expected for valid duration.
            fail("Valid duration for beginTimeMillis=" + beginTimeMillis
                    + ", endTimeMillis=" + endTimeMillis);
        }

        beginTimeMillis = 2001;
        endTimeMillis = 3001;
        try {
            UsageEventsQuery query = new UsageEventsQuery.Builder(beginTimeMillis, endTimeMillis)
                    .build();
            assertEquals(query.getBeginTimeMillis(), 2001);
            assertEquals(query.getEndTimeMillis(), 3001);
        } catch (IllegalArgumentException e) {
            // Not expected for valid duration.
            fail("Valid duration for beginTimeMillis=" + beginTimeMillis
                    + ", endTimeMillis=" + endTimeMillis);
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_FILTER_BASED_EVENT_QUERY_API)
    public void testQueryEventTypes() {
        Random rnd = new Random();
        UsageEventsQuery.Builder queryBuilder = new UsageEventsQuery.Builder(1000, 2000);

        // Test with invalid event type.
        int eventType = Event.NONE - 1;
        try {
            queryBuilder.setEventTypes(eventType);
            fail("Invalid event type: " + eventType);
        } catch (IllegalArgumentException e) {
            // Expected, fall through.
        }

        eventType = Event.MAX_EVENT_TYPE + 1;
        try {
            queryBuilder.setEventTypes(eventType);
            fail("Invalid event type: " + eventType);
        } catch (IllegalArgumentException e) {
            // Expected, fall through.
        }

        // Test with valid and duplicate event types.
        eventType = rnd.nextInt(Event.MAX_EVENT_TYPE + 1);
        try {
            UsageEventsQuery query = queryBuilder.setEventTypes(eventType, eventType, eventType)
                    .build();
            final int[] eventTypesArray = query.getEventTypes();
            assertEquals(eventTypesArray.length, 1);
            int type = eventTypesArray[0];
            assertEquals(type, eventType);
        } catch (IllegalArgumentException e) {
            fail("Valid event type: " + eventType);
        }
    }
}
