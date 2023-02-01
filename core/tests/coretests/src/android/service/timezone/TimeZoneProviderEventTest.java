/*
 * Copyright 2021 The Android Open Source Project
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

import static android.app.timezonedetector.ParcelableTestSupport.assertRoundTripParcelable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class TimeZoneProviderEventTest {

    @Test
    public void isEquivalentToAndEquals() {
        TimeZoneProviderEvent fail1v1 =
                TimeZoneProviderEvent.createPermanentFailureEvent(1111L, "one");
        assertEquals(fail1v1, fail1v1);
        assertIsEquivalentTo(fail1v1, fail1v1);
        assertNotEquals(fail1v1, null);
        assertNotEquivalentTo(fail1v1, null);

        {
            TimeZoneProviderEvent fail1v2 =
                    TimeZoneProviderEvent.createPermanentFailureEvent(1111L, "one");
            assertEquals(fail1v1, fail1v2);
            assertIsEquivalentTo(fail1v1, fail1v2);

            TimeZoneProviderEvent fail2 =
                    TimeZoneProviderEvent.createPermanentFailureEvent(2222L, "two");
            assertNotEquals(fail1v1, fail2);
            assertIsEquivalentTo(fail1v1, fail2);
        }

        TimeZoneProviderEvent uncertain1v1 = TimeZoneProviderEvent.createUncertainEvent(1111L);
        assertEquals(uncertain1v1, uncertain1v1);
        assertIsEquivalentTo(uncertain1v1, uncertain1v1);
        assertNotEquals(uncertain1v1, null);
        assertNotEquivalentTo(uncertain1v1, null);

        {
            TimeZoneProviderEvent uncertain1v2 = TimeZoneProviderEvent.createUncertainEvent(1111L);
            assertEquals(uncertain1v1, uncertain1v2);
            assertIsEquivalentTo(uncertain1v1, uncertain1v2);

            TimeZoneProviderEvent uncertain2 = TimeZoneProviderEvent.createUncertainEvent(2222L);
            assertNotEquals(uncertain1v1, uncertain2);
            assertIsEquivalentTo(uncertain1v1, uncertain2);
        }

        TimeZoneProviderSuggestion suggestion1 = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(1111L)
                .setTimeZoneIds(Collections.singletonList("Europe/London"))
                .build();
        TimeZoneProviderEvent certain1v1 =
                TimeZoneProviderEvent.createSuggestionEvent(1111L, suggestion1);
        assertEquals(certain1v1, certain1v1);
        assertIsEquivalentTo(certain1v1, certain1v1);
        assertNotEquals(certain1v1, null);
        assertNotEquivalentTo(certain1v1, null);

        {
            // Same suggestion, same time.
            TimeZoneProviderEvent certain1v2 =
                    TimeZoneProviderEvent.createSuggestionEvent(1111L, suggestion1);
            assertEquals(certain1v1, certain1v2);
            assertIsEquivalentTo(certain1v1, certain1v2);

            // Same suggestion, different time.
            TimeZoneProviderEvent certain1v3 =
                    TimeZoneProviderEvent.createSuggestionEvent(2222L, suggestion1);
            assertNotEquals(certain1v1, certain1v3);
            assertIsEquivalentTo(certain1v1, certain1v3);

            // suggestion1 is equivalent to suggestion2, but not equal
            TimeZoneProviderSuggestion suggestion2 = new TimeZoneProviderSuggestion.Builder()
                    .setElapsedRealtimeMillis(2222L)
                    .setTimeZoneIds(Collections.singletonList("Europe/London"))
                    .build();
            assertNotEquals(suggestion1, suggestion2);
            TimeZoneProviderSuggestionTest.assertIsEquivalentTo(suggestion1, suggestion2);
            TimeZoneProviderEvent certain2 =
                    TimeZoneProviderEvent.createSuggestionEvent(2222L, suggestion2);
            assertNotEquals(certain1v1, certain2);
            assertIsEquivalentTo(certain1v1, certain2);

            // suggestion3 is not equivalent to suggestion1
            TimeZoneProviderSuggestion suggestion3 = new TimeZoneProviderSuggestion.Builder()
                    .setTimeZoneIds(Collections.singletonList("Europe/Paris"))
                    .build();
            TimeZoneProviderEvent certain3 =
                    TimeZoneProviderEvent.createSuggestionEvent(2222L, suggestion3);
            assertNotEquals(certain1v1, certain3);
            assertNotEquivalentTo(certain1v1, certain3);
        }

        assertNotEquals(fail1v1, uncertain1v1);
        assertNotEquivalentTo(fail1v1, uncertain1v1);

        assertNotEquals(fail1v1, certain1v1);
        assertNotEquivalentTo(fail1v1, certain1v1);
    }

    @Test
    public void testParcelable_failureEvent() {
        TimeZoneProviderEvent event =
                TimeZoneProviderEvent.createPermanentFailureEvent(1111L, "failure reason");
        assertRoundTripParcelable(event);
    }

    @Test
    public void testParcelable_uncertain() {
        TimeZoneProviderEvent event = TimeZoneProviderEvent.createUncertainEvent(1111L);
        assertRoundTripParcelable(event);
    }

    @Test
    public void testParcelable_suggestion() {
        TimeZoneProviderSuggestion suggestion = new TimeZoneProviderSuggestion.Builder()
                .setTimeZoneIds(Arrays.asList("Europe/London", "Europe/Paris"))
                .build();
        TimeZoneProviderEvent event =
                TimeZoneProviderEvent.createSuggestionEvent(1111L, suggestion);
        assertRoundTripParcelable(event);
    }

    private static void assertNotEquivalentTo(
            TimeZoneProviderEvent one, TimeZoneProviderEvent two) {
        if (one == null && two == null) {
            fail("null arguments");
        }
        if (one != null) {
            assertFalse("one=" + one + ", two=" + two, one.isEquivalentTo(two));
        }
        if (two != null) {
            assertFalse("one=" + one + ", two=" + two, two.isEquivalentTo(one));
        }
    }

    private static void assertIsEquivalentTo(TimeZoneProviderEvent one, TimeZoneProviderEvent two) {
        if (one == null || two == null) {
            fail("null arguments");
        }
        assertTrue("one=" + one + ", two=" + two, one.isEquivalentTo(two));
        assertTrue("one=" + one + ", two=" + two, two.isEquivalentTo(one));
    }
}
