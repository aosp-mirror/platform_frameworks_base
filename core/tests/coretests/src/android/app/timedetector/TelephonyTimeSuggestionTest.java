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

package android.app.timedetector;

import static android.app.timezonedetector.ParcelableTestSupport.assertRoundTripParcelable;
import static android.app.timezonedetector.ParcelableTestSupport.roundTripParcelable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.TimestampedValue;

import org.junit.Test;

public class TelephonyTimeSuggestionTest {
    private static final int SLOT_INDEX = 99999;

    @Test
    public void testEquals() {
        TelephonyTimeSuggestion.Builder builder1 = new TelephonyTimeSuggestion.Builder(SLOT_INDEX);
        {
            TelephonyTimeSuggestion one = builder1.build();
            assertEquals(one, one);
        }

        TelephonyTimeSuggestion.Builder builder2 = new TelephonyTimeSuggestion.Builder(SLOT_INDEX);
        {
            TelephonyTimeSuggestion one = builder1.build();
            TelephonyTimeSuggestion two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        builder1.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        {
            TelephonyTimeSuggestion one = builder1.build();
            assertEquals(one, one);
        }

        builder2.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        {
            TelephonyTimeSuggestion one = builder1.build();
            TelephonyTimeSuggestion two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        TelephonyTimeSuggestion.Builder builder3 =
                new TelephonyTimeSuggestion.Builder(SLOT_INDEX + 1);
        builder3.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        {
            TelephonyTimeSuggestion one = builder1.build();
            TelephonyTimeSuggestion three = builder3.build();
            assertNotEquals(one, three);
            assertNotEquals(three, one);
        }

        // DebugInfo must not be considered in equals().
        builder1.addDebugInfo("Debug info 1");
        builder2.addDebugInfo("Debug info 2");
        {
            TelephonyTimeSuggestion one = builder1.build();
            TelephonyTimeSuggestion two = builder2.build();
            assertEquals(one, two);
        }
    }

    @Test
    public void testParcelable() {
        TelephonyTimeSuggestion.Builder builder = new TelephonyTimeSuggestion.Builder(SLOT_INDEX);
        assertRoundTripParcelable(builder.build());

        builder.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        assertRoundTripParcelable(builder.build());

        // DebugInfo should also be stored (but is not checked by equals()
        {
            TelephonyTimeSuggestion suggestion1 = builder.build();
            builder.addDebugInfo("This is debug info");
            TelephonyTimeSuggestion rtSuggestion1 = roundTripParcelable(suggestion1);
            assertEquals(suggestion1.getDebugInfo(), rtSuggestion1.getDebugInfo());
        }
    }
}
