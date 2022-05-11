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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Collections;

/** Tests for non-API methods */
public class TimeZoneProviderSuggestionTest {

    @Test
    public void isEquivalentToAndEquals() {
        TimeZoneProviderSuggestion suggestion1 = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(1111L)
                .setTimeZoneIds(Collections.singletonList("Europe/London"))
                .build();
        assertEquals(suggestion1, suggestion1);
        assertIsEquivalentTo(suggestion1, suggestion1);
        assertNotEquals(suggestion1, null);
        assertNotEquivalentTo(suggestion1, null);

        // Same time zone IDs, different time.
        TimeZoneProviderSuggestion suggestion2 = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(2222L)
                .setTimeZoneIds(Collections.singletonList("Europe/London"))
                .build();
        assertNotEquals(suggestion1, suggestion2);
        assertIsEquivalentTo(suggestion1, suggestion2);

        // Different time zone IDs.
        TimeZoneProviderSuggestion suggestion3 = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(1111L)
                .setTimeZoneIds(Collections.singletonList("Europe/Paris"))
                .build();
        assertNotEquals(suggestion1, suggestion3);
        assertNotEquivalentTo(suggestion1, suggestion3);
    }

    static void assertNotEquivalentTo(
            TimeZoneProviderSuggestion one, TimeZoneProviderSuggestion two) {
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

    static void assertIsEquivalentTo(
            TimeZoneProviderSuggestion one, TimeZoneProviderSuggestion two) {
        if (one == null || two == null) {
            fail("null arguments");
        }
        assertTrue("one=" + one + ", two=" + two, one.isEquivalentTo(two));
        assertTrue("one=" + one + ", two=" + two, two.isEquivalentTo(one));
    }
}
