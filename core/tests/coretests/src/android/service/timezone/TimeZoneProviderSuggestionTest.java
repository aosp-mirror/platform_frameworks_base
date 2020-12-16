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

package android.service.timezone;

import static android.service.timezone.ParcelableTestSupport.assertRoundTripParcelable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import static java.util.Collections.singletonList;

import org.junit.Test;

import java.util.List;

public class TimeZoneProviderSuggestionTest {

    private static final long ARBITRARY_ELAPSED_REALTIME_MILLIS = 9999;

    private static final List<String> ARBITRARY_TIME_ZONE_IDS = singletonList("Europe/London");

    @Test(expected = RuntimeException.class)
    public void testInvalidTimeZoneIds() {
        new TimeZoneProviderSuggestion.Builder()
                .setTimeZoneIds(null);
    }

    @Test
    public void testEquals() {
        TimeZoneProviderSuggestion.Builder builder1 = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);
        {
            TimeZoneProviderSuggestion one = builder1.build();
            assertEquals(one, one);
        }

        TimeZoneProviderSuggestion.Builder builder2 = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);
        {
            TimeZoneProviderSuggestion one = builder1.build();
            TimeZoneProviderSuggestion two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        builder1.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS + 1);
        {
            TimeZoneProviderSuggestion one = builder1.build();
            TimeZoneProviderSuggestion two = builder2.build();
            assertNotEquals(one, two);
            assertNotEquals(two, one);
        }

        builder2.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS + 1);
        {
            TimeZoneProviderSuggestion one = builder1.build();
            TimeZoneProviderSuggestion two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        builder2.setTimeZoneIds(ARBITRARY_TIME_ZONE_IDS);
        {
            TimeZoneProviderSuggestion one = builder1.build();
            TimeZoneProviderSuggestion two = builder2.build();
            assertNotEquals(one, two);
            assertNotEquals(two, one);
        }

        builder1.setTimeZoneIds(ARBITRARY_TIME_ZONE_IDS);
        {
            TimeZoneProviderSuggestion one = builder1.build();
            TimeZoneProviderSuggestion two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }
    }

    @Test
    public void testParcelable_noTimeZoneIds() {
        TimeZoneProviderSuggestion.Builder builder = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);
        assertRoundTripParcelable(builder.build());
    }

    @Test
    public void testParcelable_withTimeZoneIds() {
        TimeZoneProviderSuggestion.Builder builder = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                .setTimeZoneIds(ARBITRARY_TIME_ZONE_IDS);
        assertRoundTripParcelable(builder.build());
    }
}
