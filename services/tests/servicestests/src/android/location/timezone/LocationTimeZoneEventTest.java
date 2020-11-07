/*
 * Copyright 2020 The Android Open Source Project
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

package android.location.timezone;

import static android.location.timezone.ParcelableTestSupport.assertRoundTripParcelable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import static java.util.Collections.singletonList;

import org.junit.Test;

import java.util.List;

public class LocationTimeZoneEventTest {

    private static final long ARBITRARY_ELAPSED_REALTIME_NANOS = 9999;

    private static final List<String> ARBITRARY_TIME_ZONE_IDS = singletonList("Europe/London");

    @Test(expected = RuntimeException.class)
    public void testSetInvalidEventType() {
        new LocationTimeZoneEvent.Builder().setEventType(Integer.MAX_VALUE);
    }

    @Test(expected = RuntimeException.class)
    public void testBuildUnsetEventType() {
        new LocationTimeZoneEvent.Builder()
                .setTimeZoneIds(ARBITRARY_TIME_ZONE_IDS)
                .setElapsedRealtimeNanos(ARBITRARY_ELAPSED_REALTIME_NANOS)
                .build();
    }

    @Test(expected = RuntimeException.class)
    public void testInvalidTimeZoneIds() {
        new LocationTimeZoneEvent.Builder()
                .setEventType(LocationTimeZoneEvent.EVENT_TYPE_UNCERTAIN)
                .setTimeZoneIds(ARBITRARY_TIME_ZONE_IDS)
                .setElapsedRealtimeNanos(ARBITRARY_ELAPSED_REALTIME_NANOS)
                .build();
    }

    @Test
    public void testEquals() {
        LocationTimeZoneEvent.Builder builder1 = new LocationTimeZoneEvent.Builder()
                .setEventType(LocationTimeZoneEvent.EVENT_TYPE_UNCERTAIN)
                .setElapsedRealtimeNanos(ARBITRARY_ELAPSED_REALTIME_NANOS);
        {
            LocationTimeZoneEvent one = builder1.build();
            assertEquals(one, one);
        }

        LocationTimeZoneEvent.Builder builder2 = new LocationTimeZoneEvent.Builder()
                .setEventType(LocationTimeZoneEvent.EVENT_TYPE_UNCERTAIN)
                .setElapsedRealtimeNanos(ARBITRARY_ELAPSED_REALTIME_NANOS);
        {
            LocationTimeZoneEvent one = builder1.build();
            LocationTimeZoneEvent two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        builder1.setElapsedRealtimeNanos(ARBITRARY_ELAPSED_REALTIME_NANOS + 1);
        {
            LocationTimeZoneEvent one = builder1.build();
            LocationTimeZoneEvent two = builder2.build();
            assertNotEquals(one, two);
            assertNotEquals(two, one);
        }

        builder2.setElapsedRealtimeNanos(ARBITRARY_ELAPSED_REALTIME_NANOS + 1);
        {
            LocationTimeZoneEvent one = builder1.build();
            LocationTimeZoneEvent two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        builder2.setEventType(LocationTimeZoneEvent.EVENT_TYPE_SUCCESS);
        {
            LocationTimeZoneEvent one = builder1.build();
            LocationTimeZoneEvent two = builder2.build();
            assertNotEquals(one, two);
            assertNotEquals(two, one);
        }

        builder1.setEventType(LocationTimeZoneEvent.EVENT_TYPE_SUCCESS);
        {
            LocationTimeZoneEvent one = builder1.build();
            LocationTimeZoneEvent two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        builder2.setTimeZoneIds(ARBITRARY_TIME_ZONE_IDS);
        {
            LocationTimeZoneEvent one = builder1.build();
            LocationTimeZoneEvent two = builder2.build();
            assertNotEquals(one, two);
            assertNotEquals(two, one);
        }

        builder1.setTimeZoneIds(ARBITRARY_TIME_ZONE_IDS);
        {
            LocationTimeZoneEvent one = builder1.build();
            LocationTimeZoneEvent two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }
    }

    @Test
    public void testParcelable() {
        LocationTimeZoneEvent.Builder builder = new LocationTimeZoneEvent.Builder()
                .setEventType(LocationTimeZoneEvent.EVENT_TYPE_PERMANENT_FAILURE)
                .setElapsedRealtimeNanos(ARBITRARY_ELAPSED_REALTIME_NANOS);
        assertRoundTripParcelable(builder.build());

        builder.setEventType(LocationTimeZoneEvent.EVENT_TYPE_SUCCESS)
                .setTimeZoneIds(ARBITRARY_TIME_ZONE_IDS);
        assertRoundTripParcelable(builder.build());
    }
}
