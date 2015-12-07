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
    private static final String SUBTYPE_SUBTYPE_LOCALE_STRING_NONE = "";
    private static final String SUBTYPE_SUBTYPE_LANGUAGE_TAG_NONE = "";

    private static final String SUBTYPE_SUBTYPE_LOCALE_STRING_A = "en_GB";
    private static final String SUBTYPE_SUBTYPE_LANGUAGE_TAG_A = "en-GB";
    private static final int SUBTYPE_NAME_RES_ID_A = 0x12345;
    private static final String SUBTYPE_EXTRA_VALUE_A = "Key1=Value1,Key2=Value2";
    private static final int SUBTYPE_SUBTYPE_ID_A = 42;
    private static final String SUBTYPE_SUBTYPE_LOCALE_STRING_B = "en_IN";
    private static final String SUBTYPE_SUBTYPE_LANGUAGE_TAG_B = "en-IN";
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
                SUBTYPE_SUBTYPE_LOCALE_STRING_A, SUBTYPE_SUBTYPE_LANGUAGE_TAG_A,
                SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_NONE);
        assertEquals(SUBTYPE_NAME_RES_ID_A, subtype.getNameResId());
        assertEquals(SUBTYPE_SUBTYPE_LOCALE_STRING_A, subtype.getLocale());
        assertEquals(SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, subtype.getLanguageTag());
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
        assertEquals(SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, clonedSubtype.getLanguageTag());
        assertEquals("Value1", clonedSubtype.getExtraValueOf("Key1"));
        assertEquals("Value2", clonedSubtype.getExtraValueOf("Key2"));
        assertEquals(
                defaultHashCodeAlgorithm(SUBTYPE_SUBTYPE_LOCALE_STRING_A, SUBTYPE_EXTRA_VALUE_A),
                clonedSubtype.hashCode());
    }

    public void testSubtypeWithSubtypeId() throws Exception {
        final SpellCheckerSubtype subtype = new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A,
                SUBTYPE_SUBTYPE_LOCALE_STRING_A, SUBTYPE_SUBTYPE_LANGUAGE_TAG_A,
                SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_A);

        assertEquals(SUBTYPE_NAME_RES_ID_A, subtype.getNameResId());
        assertEquals(SUBTYPE_SUBTYPE_LOCALE_STRING_A, subtype.getLocale());
        assertEquals(SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, subtype.getLanguageTag());
        assertEquals("Value1", subtype.getExtraValueOf("Key1"));
        assertEquals("Value2", subtype.getExtraValueOf("Key2"));
        // Similar to "SubtypeId" in InputMethodSubtype, "SubtypeId" in SpellCheckerSubtype enables
        // developers to specify a stable and consistent ID for each subtype.
        assertEquals(SUBTYPE_SUBTYPE_ID_A, subtype.hashCode());

        final SpellCheckerSubtype clonedSubtype = cloneViaParcel(subtype);
        assertEquals(SUBTYPE_NAME_RES_ID_A, clonedSubtype.getNameResId());
        assertEquals(SUBTYPE_SUBTYPE_LOCALE_STRING_A, clonedSubtype.getLocale());
        assertEquals(SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, clonedSubtype.getLanguageTag());
        assertEquals("Value1", clonedSubtype.getExtraValueOf("Key1"));
        assertEquals("Value2", clonedSubtype.getExtraValueOf("Key2"));
        assertEquals(SUBTYPE_SUBTYPE_ID_A, clonedSubtype.hashCode());
    }

    @SmallTest
    public void testGetLocaleObject() throws Exception {
        assertEquals(new Locale("en", "GB"),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, "en_GB",
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_NONE, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE).getLocaleObject());
        assertEquals(new Locale("en", "GB"),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_NONE,
                        "en-GB", SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE).getLocaleObject());

        // If neither locale string nor language tag is specified,
        // {@link SpellCheckerSubtype#getLocaleObject} returns null.
        assertNull(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_NONE,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_NONE, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE).getLocaleObject());

        // If both locale string and language tag are specified,
        // {@link SpellCheckerSubtype#getLocaleObject} uses language tag.
        assertEquals(new Locale("en", "GB"),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, "en_US", "en-GB",
                        SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_NONE).getLocaleObject());

        // Make sure that "tl_PH" is rewritten to "fil_PH" for spell checkers that need to support
        // Android KitKat and prior, which do not support 3-letter language codes.
        assertEquals(new Locale("fil", "PH"),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, "tl_PH",
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_NONE, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE).getLocaleObject());

        // "languageTag" attribute is available in Android N and later, where 3-letter country codes
        // are guaranteed to be available.  It's developers' responsibility for specifying a valid
        // country subtags here and we do not rewrite "tl" to "fil" for simplicity.
        assertEquals("tl",
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_NONE,
                        "tl-PH", SUBTYPE_EXTRA_VALUE_A, SUBTYPE_SUBTYPE_ID_NONE)
                        .getLocaleObject().getLanguage());
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
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_B, SUBTYPE_SUBTYPE_LOCALE_STRING_B,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_B,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_B, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_NONE),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_B,
                        SUBTYPE_SUBTYPE_ID_NONE));

        // If subtype ID is not 0, we test the equality based only on the subtype ID.
        assertEquals(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_A));
        assertEquals(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_B, SUBTYPE_SUBTYPE_LOCALE_STRING_B,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_A));
        assertEquals(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_B,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_A));
        assertEquals(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_B, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_A));
        assertEquals(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_B,
                        SUBTYPE_SUBTYPE_ID_A));
        assertNotEqual(
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_A),
                new SpellCheckerSubtype(SUBTYPE_NAME_RES_ID_A, SUBTYPE_SUBTYPE_LOCALE_STRING_A,
                        SUBTYPE_SUBTYPE_LANGUAGE_TAG_A, SUBTYPE_EXTRA_VALUE_A,
                        SUBTYPE_SUBTYPE_ID_B));
    }

}
