/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.os;

import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import java.util.Objects;

public class InputMethodSubtypeTest extends InstrumentationTestCase {

    public void verifyLocale(final String localeString) {
        // InputMethodSubtype#getLocale() returns exactly the same string that is passed to the
        // constructor.
        assertEquals(localeString, createDummySubtype(localeString).getLocale());

        // InputMethodSubtype#getLocale() should be preserved via marshaling.
        assertEquals(createDummySubtype(localeString).getLocale(),
                cloneViaParcel(createDummySubtype(localeString)).getLocale());

        // InputMethodSubtype#getLocale() should be preserved via marshaling.
        assertEquals(createDummySubtype(localeString).getLocale(),
                cloneViaParcel(cloneViaParcel(createDummySubtype(localeString))).getLocale());

        // Make sure InputMethodSubtype#hashCode() returns the same hash code.
        assertEquals(createDummySubtype(localeString).hashCode(),
                createDummySubtype(localeString).hashCode());
        assertEquals(createDummySubtype(localeString).hashCode(),
                cloneViaParcel(createDummySubtype(localeString)).hashCode());
        assertEquals(createDummySubtype(localeString).hashCode(),
                cloneViaParcel(cloneViaParcel(createDummySubtype(localeString))).hashCode());
    }

    @SmallTest
    public void testLocaleString() throws Exception {
        // The locale string in InputMethodSubtype has accepted an arbitrary text actually,
        // regardless of the validity of the text as a locale string.
        verifyLocale("en_US");
        verifyLocale("apparently invalid locale string");
        verifyLocale("zz");
        verifyLocale("iw");
        verifyLocale("he");
        verifyLocale("tl");
        verifyLocale("tl_PH");
        verifyLocale("fil");
        verifyLocale("fil_PH");
    }

    @SmallTest
    public void testDeprecatedLocaleString() throws Exception {
        // Make sure "iw" is not automatically replaced with "he".
        final InputMethodSubtype subtypeIw = createDummySubtype("iw");
        final InputMethodSubtype subtypeHe = createDummySubtype("he");
        assertEquals("iw", subtypeIw.getLocale());
        assertEquals("he", subtypeHe.getLocale());
        assertFalse(Objects.equals(subtypeIw, subtypeHe));
        assertFalse(Objects.equals(subtypeIw.hashCode(), subtypeHe.hashCode()));

        final InputMethodSubtype clonedSubtypeIw = cloneViaParcel(subtypeIw);
        final InputMethodSubtype clonedSubtypeHe = cloneViaParcel(subtypeHe);
        assertEquals(subtypeIw, clonedSubtypeIw);
        assertEquals(subtypeHe, clonedSubtypeHe);
        assertEquals("iw", clonedSubtypeIw.getLocale());
        assertEquals("he", clonedSubtypeHe.getLocale());
    }

    private static final InputMethodSubtype cloneViaParcel(final InputMethodSubtype original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return InputMethodSubtype.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    private static final InputMethodSubtype createDummySubtype(final String locale) {
        final InputMethodSubtypeBuilder builder = new InputMethodSubtypeBuilder();
        return builder.setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeLocale(locale)
                .setIsAsciiCapable(true)
                .build();
    }
}
