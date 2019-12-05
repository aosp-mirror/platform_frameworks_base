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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PhoneTimeZoneSuggestionTest {
    private static final int PHONE_ID = 99999;

    @Test
    public void testEquals() {
        PhoneTimeZoneSuggestion.Builder builder1 = new PhoneTimeZoneSuggestion.Builder(PHONE_ID);
        {
            PhoneTimeZoneSuggestion one = builder1.build();
            assertEquals(one, one);
        }

        PhoneTimeZoneSuggestion.Builder builder2 = new PhoneTimeZoneSuggestion.Builder(PHONE_ID);
        {
            PhoneTimeZoneSuggestion one = builder1.build();
            PhoneTimeZoneSuggestion two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        PhoneTimeZoneSuggestion.Builder builder3 =
                new PhoneTimeZoneSuggestion.Builder(PHONE_ID + 1);
        {
            PhoneTimeZoneSuggestion one = builder1.build();
            PhoneTimeZoneSuggestion three = builder3.build();
            assertNotEquals(one, three);
            assertNotEquals(three, one);
        }

        builder1.setZoneId("Europe/London");
        builder1.setMatchType(PhoneTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY);
        builder1.setQuality(PhoneTimeZoneSuggestion.QUALITY_SINGLE_ZONE);
        {
            PhoneTimeZoneSuggestion one = builder1.build();
            PhoneTimeZoneSuggestion two = builder2.build();
            assertNotEquals(one, two);
        }

        builder2.setZoneId("Europe/Paris");
        builder2.setMatchType(PhoneTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY);
        builder2.setQuality(PhoneTimeZoneSuggestion.QUALITY_SINGLE_ZONE);
        {
            PhoneTimeZoneSuggestion one = builder1.build();
            PhoneTimeZoneSuggestion two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setZoneId("Europe/Paris");
        {
            PhoneTimeZoneSuggestion one = builder1.build();
            PhoneTimeZoneSuggestion two = builder2.build();
            assertEquals(one, two);
        }

        builder1.setMatchType(PhoneTimeZoneSuggestion.MATCH_TYPE_EMULATOR_ZONE_ID);
        builder2.setMatchType(PhoneTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY);
        {
            PhoneTimeZoneSuggestion one = builder1.build();
            PhoneTimeZoneSuggestion two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setMatchType(PhoneTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY);
        {
            PhoneTimeZoneSuggestion one = builder1.build();
            PhoneTimeZoneSuggestion two = builder2.build();
            assertEquals(one, two);
        }

        builder1.setQuality(PhoneTimeZoneSuggestion.QUALITY_SINGLE_ZONE);
        builder2.setQuality(PhoneTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS);
        {
            PhoneTimeZoneSuggestion one = builder1.build();
            PhoneTimeZoneSuggestion two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setQuality(PhoneTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS);
        {
            PhoneTimeZoneSuggestion one = builder1.build();
            PhoneTimeZoneSuggestion two = builder2.build();
            assertEquals(one, two);
        }

        // DebugInfo must not be considered in equals().
        {
            PhoneTimeZoneSuggestion one = builder1.build();
            PhoneTimeZoneSuggestion two = builder2.build();
            one.addDebugInfo("Debug info 1");
            two.addDebugInfo("Debug info 2");
            assertEquals(one, two);
        }
    }

    @Test(expected = RuntimeException.class)
    public void testBuilderValidates_emptyZone_badMatchType() {
        PhoneTimeZoneSuggestion.Builder builder = new PhoneTimeZoneSuggestion.Builder(PHONE_ID);
        // No zone ID, so match type should be left unset.
        builder.setMatchType(PhoneTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET);
        builder.build();
    }

    @Test(expected = RuntimeException.class)
    public void testBuilderValidates_zoneSet_badMatchType() {
        PhoneTimeZoneSuggestion.Builder builder = new PhoneTimeZoneSuggestion.Builder(PHONE_ID);
        builder.setZoneId("Europe/London");
        builder.setQuality(PhoneTimeZoneSuggestion.QUALITY_SINGLE_ZONE);
        builder.build();
    }

    @Test
    public void testParcelable() {
        PhoneTimeZoneSuggestion.Builder builder = new PhoneTimeZoneSuggestion.Builder(PHONE_ID);
        assertRoundTripParcelable(builder.build());

        builder.setZoneId("Europe/London");
        builder.setMatchType(PhoneTimeZoneSuggestion.MATCH_TYPE_EMULATOR_ZONE_ID);
        builder.setQuality(PhoneTimeZoneSuggestion.QUALITY_SINGLE_ZONE);
        PhoneTimeZoneSuggestion suggestion1 = builder.build();
        assertRoundTripParcelable(suggestion1);

        // DebugInfo should also be stored (but is not checked by equals()
        String debugString = "This is debug info";
        suggestion1.addDebugInfo(debugString);
        PhoneTimeZoneSuggestion suggestion1_2 = roundTripParcelable(suggestion1);
        assertEquals(suggestion1, suggestion1_2);
        assertTrue(suggestion1_2.getDebugInfo().contains(debugString));
    }
}
