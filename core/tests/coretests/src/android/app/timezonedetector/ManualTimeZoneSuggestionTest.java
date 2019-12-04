/*
 * Copyright 2019 The Android Open Source Project
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

package android.app.timezonedetector;

import static android.app.timezonedetector.ParcelableTestSupport.assertRoundTripParcelable;
import static android.app.timezonedetector.ParcelableTestSupport.roundTripParcelable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class ManualTimeZoneSuggestionTest {

    private static final String ARBITRARY_ZONE_ID1 = "Europe/London";
    private static final String ARBITRARY_ZONE_ID2 = "Europe/Paris";

    @Test
    public void testEquals() {
        ManualTimeZoneSuggestion one = new ManualTimeZoneSuggestion(ARBITRARY_ZONE_ID1);
        assertEquals(one, one);

        ManualTimeZoneSuggestion two = new ManualTimeZoneSuggestion(ARBITRARY_ZONE_ID1);
        assertEquals(one, two);
        assertEquals(two, one);

        ManualTimeZoneSuggestion three = new ManualTimeZoneSuggestion(ARBITRARY_ZONE_ID2);
        assertNotEquals(one, three);
        assertNotEquals(three, one);

        // DebugInfo must not be considered in equals().
        one.addDebugInfo("Debug info 1");
        two.addDebugInfo("Debug info 2");
        assertEquals(one, two);
    }

    @Test
    public void testParcelable() {
        ManualTimeZoneSuggestion suggestion = new ManualTimeZoneSuggestion(ARBITRARY_ZONE_ID1);
        assertRoundTripParcelable(suggestion);

        // DebugInfo should also be stored (but is not checked by equals()
        suggestion.addDebugInfo("This is debug info");
        ManualTimeZoneSuggestion rtSuggestion = roundTripParcelable(suggestion);
        assertEquals(suggestion.getDebugInfo(), rtSuggestion.getDebugInfo());
    }
}
