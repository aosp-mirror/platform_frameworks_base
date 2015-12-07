/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.view.textservice;

import android.os.Parcel;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.Arrays;
import java.util.Locale;

import static android.test.MoreAsserts.assertNotEqual;

/**
 * TODO: Most of part can be, and probably should be, moved to CTS.
 */
public class SpellCheckerSubtypeTest extends InstrumentationTestCase {
    private static final int SUBTYPE_SUBTYPE_ID_NONE = 0;
    private static final String SUBTYPE_SUBTYPE_LOCALE_STRING_A = "en_GB";
    private static final int SUBTYPE_NAME_RES_ID_A = 0x12345;
    private static final String SUBTYPE_EXTRA_VALUE_A = "Key1=Value1,Key2=Value2";
    private static final int SUBTYPE_SUBTYPE_ID_A = 42;
    private static final String SUBTYPE_SUBTYPE_LOCALE_STRING_B = "en_IN";
    private static final int SUBTYPE_NAME_RES_ID_B = 0x54321;
    private static final String SUBTYPE_EXTRA_VALUE_B = "Key3=Value3,Key4=Value4";
    private static final int SUBTYPE_SUBTYPE_ID_B = -42;

    private static int defaultHashCodeAlgorithm(String locale, String extraValue) {
        return Arrays.hashCode(new Object[] {locale, extraValue});
    }

    private static final SpellCheckerSubtype cloneViaParcel(final SpellCheckerSubtype original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return SpellCheckerSubtype.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    @SmallTest
    public void testSubtypeWithNoSubtypeId() throws Exception {
        final SpellCheckerSubtype subtype = new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A,
                SUBTYPE_SUBTYPE_LOCALE_STRING_A, SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_NONE);
        assertEquals(SUBTYPE_NAME_RES_ID_A, subtype.getNameResId());
        assertEquals(SUBTYPE_SUBTYPE_LOCALE_STRING_A, subtype.getLocale());
        assertEquals("Value1", subtype.getExtraValueOf("Key1"));
        assertEquals("Value2", subtype.getExtraValueOf("Key2"));
        // Historically we have used SpellCheckerSubtype#hashCode() to track which subtype is
        // enabled, and it is supposed to be stored in SecureSettings.  Therefore we have to
        // keep using the same algorithm for compatibility reasons.
        assertEquals(
                defaultHashCodeAlgorithm(SUBTYPE_SUBTYPE_LOCALE_STRING_A, SUBTYPE_EXTRA_VALUE_A),
                subtype.hashCode());

        final SpellCheckerSubtype clonedSubtype = cloneViaParcel(subtype);
        assertEquals(SUBTYPE_NAME_RES_ID_A, clonedSubtype.getNameResId());
        assertEquals(SUBTYPE_SUBTYPE_LOCALE_STRING_A, clonedSubtype.getLocale());
        assertEquals("Value1", clonedSubtype.getExtraValueOf("Key1"));
        assertEquals("Value2", clonedSubtype.getExtraValueOf("Key2"));
        assertEquals(
                defaultHashCodeAlgorithm(SUBTYPE_SUBTYPE_LOCALE_STRING_A, SUBTYPE_EXTRA_VALUE_A),
                clonedSubtype.hashCode());
    }

    public void testSubtypeWithSubtypeId() throws Exception {
        final SpellCheckerSubtype subtype = new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A,
                SUBTYPE_SUBTYPE_LOCALE_STRING_A, SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_A);

        assertEquals(SUBTYPE_NAME_RES_ID_A, subtype.getNameResId());
        assertEquals(SUBTYPE_SUBTYPE_LOCALE_STRING_A, subtype.getLocale());
        assertEquals("Value1", subtype.getExtraValueOf("Key1"));
        assertEquals("Value2", subtype.getExtraValueOf("Key2"));
        // Similar to "SubtypeId" in InputMethodSubtype, "SubtypeId" in SpellCheckerSubtype enables
        // developers to specify a stable and consistent ID for each subtype.
        assertEquals(SUBTYPE_SUBTYPE_ID_A, subtype.hashCode());

        final SpellCheckerSubtype clonedSubtype = cloneViaParcel(subtype);
        assertEquals(SUBTYPE_NAME_RES_ID_A, clonedSubtype.getNameResId());
        assertEquals(SUBTYPE_SUBTYPE_LOCALE_STRING_A, clonedSubtype.getLocale());
        assertEquals("Value1", clonedSubtype.getExtraValueOf("Key1"));
        assertEquals("Value2", clonedSubtype.getExtraValueOf("Key2"));
        assertEquals(SUBTYPE_SUBTYPE_ID_A, clonedSubtype.hashCode());
    }

    @SmallTest
    public void testGetLocaleObject() throws Exception {
        assertEquals(new Locale("en"), new SpellCheckerSubtype(
                SUBTYPE_NAME_RES_ID_A, "en", SUBTYPE_EXTRA_VALUE_A).getLocaleObject());
        assertEquals(new Locale("en", "US"), new SpellCheckerSubtype(
                SUBTYPE_NAME_RES_ID_A, "en_US", SUBTYPE_EXTRA_VALUE_A).getLocaleObject());
        assertEquals(new Locale("en", "US", "POSIX"), new SpellCheckerSubtype(
                SUBTYPE_NAME_RES_ID_A, "en_US_POSIX", SUBTYPE_EXTRA_VALUE_A).getLocaleObject());

        // Special rewrite rule for "tl" for versions of Android earlier than Lollipop that did not
        // support three letter language codes, and used "tl" (Tagalog) as the language string for
        // "fil" (Filipino).
        assertEquals(new Locale("fil"), new SpellCheckerSubtype(
                SUBTYPE_NAME_RES_ID_A, "tl", SUBTYPE_EXTRA_VALUE_A).getLocaleObject());
        assertEquals(new Locale("fil", "PH"), new SpellCheckerSubtype(
                SUBTYPE_NAME_RES_ID_A, "tl_PH", SUBTYPE_EXTRA_VALUE_A).getLocaleObject());
        assertEquals(new Locale("fil", "PH", "POSIX"), new SpellCheckerSubtype(
                SUBTYPE_NAME_RES_ID_A, "tl_PH_POSIX", SUBTYPE_EXTRA_VALUE_A).getLocaleObject());

        // So far rejecting invalid/unexpected locale strings is out of the scope.
        assertEquals(new Locale("a"), new SpellCheckerSubtype(
                SUBTYPE_NAME_RES_ID_A, "a", SUBTYPE_EXTRA_VALUE_A).getLocaleObject());
        assertEquals(new Locale("a b c"), new SpellCheckerSubtype(
                SUBTYPE_NAME_RES_ID_A, "a b c", SUBTYPE_EXTRA_VALUE_A).getLocaleObject());
        assertEquals(new Locale("en-US"), new SpellCheckerSubtype(
                SUBTYPE_NAME_RES_ID_A, "en-US", SUBTYPE_EXTRA_VALUE_A).getLocaleObject());
    }

    @SmallTest
    public void testEquality() throws Exception {
        assertEquals(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_B, SUBTYPE_SUBTYPE_LOCALE_STRING_B,
                        SUBTYPE_EXTRA_VALUE_A));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_B,
                        SUBTYPE_EXTRA_VALUE_A));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_B));

        // If subtype ID is 0 (== SUBTYPE_SUBTYPE_ID_NONE), we keep the same behavior.
        assertEquals(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_NONE),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_NONE));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_NONE),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_B, SUBTYPE_SUBTYPE_LOCALE_STRING_B,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_NONE));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_NONE),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_B,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_NONE));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_NONE),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_B, SUBTYPE_SUBTYPE_ID_NONE));

        // If subtype ID is not 0, we test the equality based only on the subtype ID.
        assertEquals(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_A));
        assertEquals(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_B, SUBTYPE_SUBTYPE_LOCALE_STRING_B,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_A));
        assertEquals(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_B,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_A));
        assertEquals(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_B, SUBTYPE_SUBTYPE_ID_A));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_B));
    }
}
