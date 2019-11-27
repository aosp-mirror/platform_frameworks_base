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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.TimestampedValue;

import org.junit.Test;

public class PhoneTimeSuggestionTest {
    private static final int PHONE_ID = 99999;

    @Test
    public void testEquals() {
        PhoneTimeSuggestion one = new PhoneTimeSuggestion(PHONE_ID);
        assertEquals(one, one);

        PhoneTimeSuggestion two = new PhoneTimeSuggestion(PHONE_ID);
        assertEquals(one, two);
        assertEquals(two, one);

        one.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        assertEquals(one, one);

        two.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        assertEquals(one, two);
        assertEquals(two, one);

        PhoneTimeSuggestion three = new PhoneTimeSuggestion(PHONE_ID + 1);
        three.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        assertNotEquals(one, three);
        assertNotEquals(three, one);

        // DebugInfo must not be considered in equals().
        one.addDebugInfo("Debug info 1");
        two.addDebugInfo("Debug info 2");
        assertEquals(one, two);
    }

    @Test
    public void testParcelable() {
        PhoneTimeSuggestion one = new PhoneTimeSuggestion(PHONE_ID);
        assertEquals(one, roundTripParcelable(one));

        one.setUtcTime(new TimestampedValue<>(1111L, 2222L));
        assertEquals(one, roundTripParcelable(one));

        // DebugInfo should also be stored (but is not checked by equals()
        one.addDebugInfo("This is debug info");
        PhoneTimeSuggestion two = roundTripParcelable(one);
        assertEquals(one.getDebugInfo(), two.getDebugInfo());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Parcelable> T roundTripParcelable(T one) {
        Parcel parcel = Parcel.obtain();
        parcel.writeTypedObject(one, 0);
        parcel.setDataPosition(0);

        T toReturn = (T) parcel.readTypedObject(PhoneTimeSuggestion.CREATOR);
        parcel.recycle();
        return toReturn;
    }
}
