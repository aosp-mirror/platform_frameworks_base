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

package android.view.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;
import java.util.Objects;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputMethodSubtypeTest {

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

    @Test
    public void testLocaleObj_locale() {
        final InputMethodSubtype usSubtype = createDummySubtype("en_US");
        Locale localeObject = usSubtype.getLocaleObject();
        assertEquals("en", localeObject.getLanguage());
        assertEquals("US", localeObject.getCountry());

        // The locale object should be cached.
        assertTrue(localeObject == usSubtype.getLocaleObject());
    }

    @Test
    public void testLocaleObj_languageTag() {
        final InputMethodSubtype usSubtype = createDummySubtypeUsingLanguageTag("en-US");
        Locale localeObject = usSubtype.getLocaleObject();
        assertNotNull(localeObject);
        assertEquals("en", localeObject.getLanguage());
        assertEquals("US", localeObject.getCountry());

        // The locale object should be cached.
        assertTrue(localeObject == usSubtype.getLocaleObject());
    }

    @Test
    public void testLocaleObj_emptyLocale() {
        final InputMethodSubtype emptyLocaleSubtype = createDummySubtype("");
        assertNull(emptyLocaleSubtype.getLocaleObject());
        // It should continue returning null when called multiple times.
        assertNull(emptyLocaleSubtype.getLocaleObject());
        assertNull(emptyLocaleSubtype.getLocaleObject());
    }

    @Test
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

    @Test
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

    private static InputMethodSubtype createDummySubtype(final String locale) {
        final InputMethodSubtypeBuilder builder = new InputMethodSubtypeBuilder();
        return builder.setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeLocale(locale)
                .setIsAsciiCapable(true)
                .build();
    }

    private static InputMethodSubtype createDummySubtypeUsingLanguageTag(
            final String languageTag) {
        final InputMethodSubtypeBuilder builder = new InputMethodSubtypeBuilder();
        return builder.setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setLanguageTag(languageTag)
                .setIsAsciiCapable(true)
                .build();
    }
}