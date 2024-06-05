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

package android.app.time;

import static org.junit.Assert.assertEquals;

import android.os.Parcel;
import android.os.Parcelable;

import java.lang.reflect.Field;

/** Utility methods related to {@link Parcelable} objects used in several tests. */
public final class ParcelableTestSupport {

    private ParcelableTestSupport() {}

    /** Returns the result of parceling and unparceling the argument. */
    @SuppressWarnings("unchecked")
    public static <T extends Parcelable> T roundTripParcelable(T parcelable) {
        Parcel parcel = Parcel.obtain();
        parcel.writeTypedObject(parcelable, 0);
        parcel.setDataPosition(0);

        Parcelable.Creator<T> creator;
        try {
            Field creatorField = parcelable.getClass().getField("CREATOR");
            creator = (Parcelable.Creator<T>) creatorField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
        T toReturn = parcel.readTypedObject(creator);
        parcel.recycle();
        return toReturn;
    }

    /**
     * Asserts that the parameter can be parceled and unparceled and return an object considered
     * equal to the original.
     */
    public static <T extends Parcelable> void assertRoundTripParcelable(T instance) {
        assertEqualsAndHashCode(instance, roundTripParcelable(instance));
    }

    /** Asserts that the objects are equal and return identical hash codes. */
    public static void assertEqualsAndHashCode(Object one, Object two) {
        assertEquals(one, two);
        assertEquals(two, one);
        assertEquals(one.hashCode(), two.hashCode());
    }
}
