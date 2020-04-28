/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import android.os.Parcel;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TimestampedValueTest {

    @Test
    public void testEqualsAndHashcode() {
        TimestampedValue<String> one1000one = new TimestampedValue<>(1000, "one");
        assertEqualsAndHashCode(one1000one, one1000one);

        TimestampedValue<String> one1000two = new TimestampedValue<>(1000, "one");
        assertEqualsAndHashCode(one1000one, one1000two);

        TimestampedValue<String> two1000 = new TimestampedValue<>(1000, "two");
        assertNotEquals(one1000one, two1000);

        TimestampedValue<String> one2000 = new TimestampedValue<>(2000, "one");
        assertNotEquals(one1000one, one2000);
    }

    private static void assertEqualsAndHashCode(Object one, Object two) {
        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
    }

    @Test
    public void testParceling() {
        TimestampedValue<String> stringValue = new TimestampedValue<>(1000, "Hello");
        Parcel parcel = Parcel.obtain();
        try {
            TimestampedValue.writeToParcel(parcel, stringValue);

            parcel.setDataPosition(0);

            TimestampedValue<String> stringValueCopy =
                    TimestampedValue.readFromParcel(parcel, null /* classLoader */, String.class);
            assertEquals(stringValue, stringValueCopy);
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testParceling_valueClassOk() {
        TimestampedValue<String> stringValue = new TimestampedValue<>(1000, "Hello");
        Parcel parcel = Parcel.obtain();
        try {
            TimestampedValue.writeToParcel(parcel, stringValue);

            parcel.setDataPosition(0);

            TimestampedValue<Object> stringValueCopy =
                    TimestampedValue.readFromParcel(parcel, null /* classLoader */, Object.class);
            assertEquals(stringValue, stringValueCopy);
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testParceling_valueClassIncompatible() {
        TimestampedValue<String> stringValue = new TimestampedValue<>(1000, "Hello");
        Parcel parcel = Parcel.obtain();
        try {
            TimestampedValue.writeToParcel(parcel, stringValue);

            parcel.setDataPosition(0);

            TimestampedValue.readFromParcel(parcel, null /* classLoader */, Double.class);
            fail();
        } catch (RuntimeException expected) {
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testParceling_nullValue() {
        TimestampedValue<String> nullValue = new TimestampedValue<>(1000, null);
        Parcel parcel = Parcel.obtain();
        try {
            TimestampedValue.writeToParcel(parcel, nullValue);

            parcel.setDataPosition(0);

            TimestampedValue<Object> nullValueCopy =
                    TimestampedValue.readFromParcel(parcel, null /* classLoader */, String.class);
            assertEquals(nullValue, nullValueCopy);
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testReferenceTimeDifference() {
        TimestampedValue<Long> value1 = new TimestampedValue<>(1000, 123L);
        assertEquals(0, TimestampedValue.referenceTimeDifference(value1, value1));

        TimestampedValue<Long> value2 = new TimestampedValue<>(1, 321L);
        assertEquals(999, TimestampedValue.referenceTimeDifference(value1, value2));
        assertEquals(-999, TimestampedValue.referenceTimeDifference(value2, value1));
    }
}
