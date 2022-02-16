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

package com.android.server.timezonedetector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GeolocationTimeZoneSuggestionTest {

    private static final List<String> ARBITRARY_ZONE_IDS1 =
            Collections.singletonList("Europe/London");
    private static final List<String> ARBITRARY_ZONE_IDS2 =
            Arrays.asList("Europe/Paris", "Europe/Brussels");

    @Test
    public void testEquals() {
        GeolocationTimeZoneSuggestion one = new GeolocationTimeZoneSuggestion(ARBITRARY_ZONE_IDS1);
        assertEquals(one, one);

        GeolocationTimeZoneSuggestion two = new GeolocationTimeZoneSuggestion(ARBITRARY_ZONE_IDS1);
        assertEquals(one, two);
        assertEquals(two, one);

        GeolocationTimeZoneSuggestion nullZone = new GeolocationTimeZoneSuggestion(null);
        assertNotEquals(one, nullZone);
        assertNotEquals(nullZone, one);
        assertEquals(nullZone, nullZone);

        GeolocationTimeZoneSuggestion three =
                new GeolocationTimeZoneSuggestion(ARBITRARY_ZONE_IDS2);
        assertNotEquals(one, three);
        assertNotEquals(three, one);

        // DebugInfo must not be considered in equals().
        one.addDebugInfo("Debug info 1");
        two.addDebugInfo("Debug info 2");
        assertEquals(one, two);
    }
}
