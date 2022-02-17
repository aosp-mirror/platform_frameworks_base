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
        long time1 = 1111L;
        GeolocationTimeZoneSuggestion certain1v1 =
                GeolocationTimeZoneSuggestion.createCertainSuggestion(time1, ARBITRARY_ZONE_IDS1);
        assertEquals(certain1v1, certain1v1);

        GeolocationTimeZoneSuggestion certain1v2 =
                GeolocationTimeZoneSuggestion.createCertainSuggestion(time1, ARBITRARY_ZONE_IDS1);
        assertEquals(certain1v1, certain1v2);
        assertEquals(certain1v2, certain1v1);

        // DebugInfo must not be considered in equals().
        certain1v1.addDebugInfo("Debug info 1");
        certain1v2.addDebugInfo("Debug info 2");
        assertEquals(certain1v1, certain1v2);

        long time2 = 2222L;
        GeolocationTimeZoneSuggestion certain2 =
                GeolocationTimeZoneSuggestion.createCertainSuggestion(time2, ARBITRARY_ZONE_IDS1);
        assertNotEquals(certain1v1, certain2);
        assertNotEquals(certain2, certain1v1);

        GeolocationTimeZoneSuggestion uncertain =
                GeolocationTimeZoneSuggestion.createUncertainSuggestion(time1);
        assertNotEquals(certain1v1, uncertain);
        assertNotEquals(uncertain, certain1v1);
        assertEquals(uncertain, uncertain);

        GeolocationTimeZoneSuggestion certain3 =
                GeolocationTimeZoneSuggestion.createCertainSuggestion(time1, ARBITRARY_ZONE_IDS2);
        assertNotEquals(certain1v1, certain3);
        assertNotEquals(certain3, certain1v1);
    }
}
