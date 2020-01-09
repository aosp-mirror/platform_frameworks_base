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

public class PhoneTimeSuggestionTest {
    private static final int PHONE_ID = 99999;

    @Test
    public void testEquals() {
        PhoneTimeSuggestion.Builder builder1 = new PhoneTimeSuggestion.Builder(PHONE_ID);
        {
            PhoneTimeSuggestion one = builder1.build();
            assertEquals(one, one);
        }

        PhoneTimeSuggestion.Builder builder2 = new PhoneTimeSuggestion.Builder(PHONE_ID);
        {
            PhoneTimeSuggestion one = builder1.build();
            PhoneTimeSuggestion two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        builder1.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        {
            PhoneTimeSuggestion one = builder1.build();
            assertEquals(one, one);
        }

        builder2.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        {
            PhoneTimeSuggestion one = builder1.build();
            PhoneTimeSuggestion two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        PhoneTimeSuggestion.Builder builder3 = new PhoneTimeSuggestion.Builder(PHONE_ID + 1);
        builder3.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        {
            PhoneTimeSuggestion one = builder1.build();
            PhoneTimeSuggestion three = builder3.build();
            assertNotEquals(one, three);
            assertNotEquals(three, one);
        }

        // DebugInfo must not be considered in equals().
        builder1.addDebugInfo("Debug info 1");
        builder2.addDebugInfo("Debug info 2");
        {
            PhoneTimeSuggestion one = builder1.build();
            PhoneTimeSuggestion two = builder2.build();
            assertEquals(one, two);
        }
    }

    @Test
    public void testParcelable() {
        PhoneTimeSuggestion.Builder builder = new PhoneTimeSuggestion.Builder(PHONE_ID);
        assertRoundTripParcelable(builder.build());

        builder.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        assertRoundTripParcelable(builder.build());

        // DebugInfo should also be stored (but is not checked by equals()
        {
            PhoneTimeSuggestion suggestion1 = builder.build();
            builder.addDebugInfo("This is debug info");
            PhoneTimeSuggestion rtSuggestion1 = roundTripParcelable(suggestion1);
            assertEquals(suggestion1.getDebugInfo(), rtSuggestion1.getDebugInfo());
        }
    }
}
