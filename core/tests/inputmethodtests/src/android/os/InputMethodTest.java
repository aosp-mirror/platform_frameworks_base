/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import com.android.internal.inputmethod.InputMethodUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class InputMethodTest extends InstrumentationTestCase {
    private static final boolean IS_AUX = true;
    private static final boolean IS_DEFAULT = true;
    private static final boolean IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE = true;
    private static final boolean IS_ASCII_CAPABLE = true;
    private static final boolean IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE = true;
    private static final boolean IS_SYSTEM_READY = true;
    private static final Locale LOCALE_EN = new Locale("en");
    private static final Locale LOCALE_EN_US = new Locale("en", "US");
    private static final Locale LOCALE_EN_GB = new Locale("en", "GB");
    private static final Locale LOCALE_EN_IN = new Locale("en", "IN");
    private static final Locale LOCALE_FI = new Locale("fi");
    private static final Locale LOCALE_FI_FI = new Locale("fi", "FI");
    private static final Locale LOCALE_FIL = new Locale("fil");
    private static final Locale LOCALE_FIL_PH = new Locale("fil", "PH");
    private static final Locale LOCALE_FR = new Locale("fr");
    private static final Locale LOCALE_FR_CA = new Locale("fr", "CA");
    private static final Locale LOCALE_HI = new Locale("hi");
    private static final Locale LOCALE_JA = new Locale("ja");
    private static final Locale LOCALE_JA_JP = new Locale("ja", "JP");
    private static final Locale LOCALE_ZH_CN = new Locale("zh", "CN");
    private static final Locale LOCALE_ZH_TW = new Locale("zh", "TW");
    private static final Locale LOCALE_IN = new Locale("in");
    private static final Locale LOCALE_ID = new Locale("id");
    private static final Locale LOCALE_TH = new Locale("ht");
    private static final Locale LOCALE_TH_TH = new Locale("ht", "TH");
    private static final Locale LOCALE_TH_TH_TH = new Locale("ht", "TH", "TH");
    private static final String SUBTYPE_MODE_KEYBOARD = "keyboard";
    private static final String SUBTYPE_MODE_VOICE = "voice";
    private static final String SUBTYPE_MODE_ANY = null;
    private static final String EXTRA_VALUE_PAIR_SEPARATOR = ",";
    private static final String EXTRA_VALUE_ASCII_CAPABLE = "AsciiCapable";
    private static final String EXTRA_VALUE_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE =
            "EnabledWhenDefaultIsNotAsciiCapable";

    @SmallTest
    public void testVoiceImes() throws Exception {
        // locale: en_US
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_EN_US,
                !IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme", "DummyDefaultAutoVoiceIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_US,
                !IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme");
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_EN_US,
                IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme", "DummyDefaultAutoVoiceIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_US,
                IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme", "DummyNonDefaultAutoVoiceIme0",
                "DummyNonDefaultAutoVoiceIme1");

        // locale: en_GB
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_EN_GB,
                !IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme", "DummyDefaultAutoVoiceIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_GB,
                !IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme");
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_EN_GB,
                IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme", "DummyDefaultAutoVoiceIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_GB,
                IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme", "DummyNonDefaultAutoVoiceIme0",
                "DummyNonDefaultAutoVoiceIme1");

        // locale: ja_JP
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_JA_JP,
                !IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme", "DummyDefaultAutoVoiceIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_JA_JP,
                !IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme");
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_JA_JP,
                IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme", "DummyDefaultAutoVoiceIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_JA_JP,
                IS_SYSTEM_READY, "DummyDefaultEnKeyboardIme", "DummyNonDefaultAutoVoiceIme0",
                "DummyNonDefaultAutoVoiceIme1");
    }

    @SmallTest
    public void testKeyboardImes() throws Exception {
        // locale: en_US
        assertDefaultEnabledImes(getSamplePreinstalledImes("en-rUS"), LOCALE_EN_US,
                !IS_SYSTEM_READY, "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes("en-rUS"), LOCALE_EN_US,
                IS_SYSTEM_READY, "com.android.apps.inputmethod.latin",
                "com.android.apps.inputmethod.voice");

        // locale: en_GB
        assertDefaultEnabledImes(getSamplePreinstalledImes("en-rGB"), LOCALE_EN_GB,
                !IS_SYSTEM_READY, "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes("en-rGB"), LOCALE_EN_GB,
                IS_SYSTEM_READY, "com.android.apps.inputmethod.latin",
                "com.android.apps.inputmethod.voice");

        // locale: en_IN
        assertDefaultEnabledImes(getSamplePreinstalledImes("en-rIN"), LOCALE_EN_IN,
                !IS_SYSTEM_READY, "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes("en-rIN"), LOCALE_EN_IN,
                IS_SYSTEM_READY, "com.android.apps.inputmethod.hindi",
                "com.android.apps.inputmethod.latin", "com.android.apps.inputmethod.voice");

        // locale: hi
        assertDefaultEnabledImes(getSamplePreinstalledImes("hi"), LOCALE_HI,
                !IS_SYSTEM_READY, "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes("hi"), LOCALE_HI,
                IS_SYSTEM_READY, "com.android.apps.inputmethod.hindi",
                "com.android.apps.inputmethod.latin", "com.android.apps.inputmethod.voice");

        // locale: ja_JP
        assertDefaultEnabledImes(getSamplePreinstalledImes("ja-rJP"), LOCALE_JA_JP,
                !IS_SYSTEM_READY, "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes("ja-rJP"), LOCALE_JA_JP,
                IS_SYSTEM_READY, "com.android.apps.inputmethod.japanese",
                "com.android.apps.inputmethod.voice");

        // locale: zh_CN
        assertDefaultEnabledImes(getSamplePreinstalledImes("zh-rCN"), LOCALE_ZH_CN,
                !IS_SYSTEM_READY, "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes("zh-rCN"), LOCALE_ZH_CN,
                IS_SYSTEM_READY, "com.android.apps.inputmethod.pinyin",
                "com.android.apps.inputmethod.voice");

        // locale: zh_TW
        // Note: In this case, no IME is suitable for the system locale. Hence we will pick up a
        // fallback IME regardless of the "default" attribute.
        assertDefaultEnabledImes(getSamplePreinstalledImes("zh-rTW"), LOCALE_ZH_TW,
                !IS_SYSTEM_READY, "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes("zh-rTW"), LOCALE_ZH_TW,
                IS_SYSTEM_READY, "com.android.apps.inputmethod.latin",
                "com.android.apps.inputmethod.voice");
    }

    @SmallTest
    public void testParcelable() throws Exception {
        final ArrayList<InputMethodInfo> originalList = getSamplePreinstalledImes("en-rUS");
        final List<InputMethodInfo> clonedList = cloneViaParcel(originalList);
        assertNotNull(clonedList);
        final List<InputMethodInfo> clonedClonedList = cloneViaParcel(clonedList);
        assertNotNull(clonedClonedList);
        assertEquals(originalList, clonedList);
        assertEquals(clonedList, clonedClonedList);
        assertEquals(originalList.size(), clonedList.size());
        assertEquals(clonedList.size(), clonedClonedList.size());
        for (int imeIndex = 0; imeIndex < originalList.size(); ++imeIndex) {
            verifyEquality(originalList.get(imeIndex), clonedList.get(imeIndex));
            verifyEquality(clonedList.get(imeIndex), clonedClonedList.get(imeIndex));
        }
    }

    @SmallTest
    public void testGetImplicitlyApplicableSubtypesLocked() throws Exception {
        final InputMethodSubtype nonAutoEnUS = createDummyInputMethodSubtype("en_US",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoEnGB = createDummyInputMethodSubtype("en_GB",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoFrCA = createDummyInputMethodSubtype("fr_CA",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoFr = createDummyInputMethodSubtype("fr_CA",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoFil = createDummyInputMethodSubtype("fil",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoIn = createDummyInputMethodSubtype("in",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoId = createDummyInputMethodSubtype("id",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype autoSubtype = createDummyInputMethodSubtype("auto",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoJa = createDummyInputMethodSubtype("ja",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                !IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype =
                createDummyInputMethodSubtype("zz", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                        !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                        IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2 =
                createDummyInputMethodSubtype("zz", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                        !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                        IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);

        // Make sure that an automatic subtype (overridesImplicitlyEnabledSubtype:true) is
        // selected no matter what locale is specified.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoEnGB);
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoFil);
            subtypes.add(autoSubtype);  // overridesImplicitlyEnabledSubtype == true
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_EN_US, imi);
            assertEquals(1, result.size());
            verifyEquality(autoSubtype, result.get(0));
        }

        // Make sure that a subtype whose locale is exactly equal to the specified locale is
        // selected as long as there is no no automatic subtype
        // (overridesImplicitlyEnabledSubtype:true) in the given list.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoEnUS);  // locale == "en_US"
            subtypes.add(nonAutoEnGB);
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoFil);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_EN_US, imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoEnUS, result.get(0));
        }

        // Make sure that a subtype whose locale is exactly equal to the specified locale is
        // selected as long as there is no automatic subtype
        // (overridesImplicitlyEnabledSubtype:true) in the given list.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoEnGB); // locale == "en_GB"
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoFil);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_EN_GB, imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoEnGB, result.get(0));
        }

        // If there is no automatic subtype (overridesImplicitlyEnabledSubtype:true) and
        // any subtype whose locale is exactly equal to the specified locale in the given list,
        // try to find a subtype whose language is equal to the language part of the given locale.
        // Here make sure that a subtype (locale: "fr_CA") can be found with locale: "fr".
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoFrCA);  // locale == "fr_CA"
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoFil);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_FR, imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoFrCA, result.get(0));
        }
        // Then make sure that a subtype (locale: "fr") can be found with locale: "fr_CA".
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoFr);  // locale == "fr"
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoFil);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_FR_CA, imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoFrCA, result.get(0));
        }

        // Make sure that subtypes which have "EnabledWhenDefaultIsNotAsciiCapable" in its
        // extra value is selected if and only if all other selected IMEs are not AsciiCapable.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoJa);    // not ASCII capable
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_JA_JP, imi);
            assertEquals(3, result.size());
            verifyEquality(nonAutoJa, result.get(0));
            verifyEquality(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype, result.get(1));
            verifyEquality(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2, result.get(2));
        }

        // Make sure that 3-letter language code can be handled.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoFil);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_FIL_PH, imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoFil, result.get(0));
        }

        // Make sure that we never end up matching "fi" (finnish) with "fil" (filipino).
        // Also make sure that the first subtype will be used as the last-resort candidate.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoFil);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_FI, imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoJa, result.get(0));
        }

        // Make sure that "in" and "id" conversion is taken into account.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoIn);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_IN, imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoIn, result.get(0));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoIn);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_ID, imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoIn, result.get(0));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoId);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_IN, imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoId, result.get(0));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoId);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    callGetImplicitlyApplicableSubtypesLockedWithLocale(LOCALE_ID, imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoId, result.get(0));
        }
    }

    @SmallTest
    public void testContainsSubtypeOf() throws Exception {
        final InputMethodSubtype nonAutoEnUS = createDummyInputMethodSubtype("en_US",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoEnGB = createDummyInputMethodSubtype("en_GB",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoFil = createDummyInputMethodSubtype("fil",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoFilPH = createDummyInputMethodSubtype("fil_PH",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoIn = createDummyInputMethodSubtype("in",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoId = createDummyInputMethodSubtype("id",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);

        final boolean CHECK_COUNTRY = true;

        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);

            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_EN, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_EN, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_EN_US, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_EN_US, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_EN_US, !CHECK_COUNTRY,
                    SUBTYPE_MODE_VOICE));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_EN_US, CHECK_COUNTRY,
                    SUBTYPE_MODE_VOICE));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_EN_US, !CHECK_COUNTRY,
                    SUBTYPE_MODE_ANY));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_EN_US, CHECK_COUNTRY,
                    SUBTYPE_MODE_ANY));

            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_EN_GB, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_EN_GB, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
        }

        // Make sure that 3-letter language code ("fil") can be handled.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoFil);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FIL, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FIL, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FIL_PH, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FIL_PH, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));

            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FI, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FI, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FI_FI, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FI_FI, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
        }

        // Make sure that 3-letter language code ("fil_PH") can be handled.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoFilPH);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FIL, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FIL, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FIL_PH, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FIL_PH, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));

            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FI, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FI, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FI_FI, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(InputMethodUtils.containsSubtypeOf(imi, LOCALE_FI_FI, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
        }

        // Make sure that a subtype whose locale is "in" can be queried with "id".
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoIn);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_IN, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_IN, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_ID, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_ID, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
        }

        // Make sure that a subtype whose locale is "id" can be queried with "in".
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(nonAutoId);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createDummyInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_IN, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_IN, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_ID, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(InputMethodUtils.containsSubtypeOf(imi, LOCALE_ID, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
        }
    }

    private ArrayList<InputMethodSubtype> callGetImplicitlyApplicableSubtypesLockedWithLocale(
            final Locale locale, final InputMethodInfo imi) {
        final Context context = getInstrumentation().getTargetContext();
        final Locale initialLocale = context.getResources().getConfiguration().locale;
        try {
            context.getResources().getConfiguration().setLocale(locale);
            return InputMethodUtils.getImplicitlyApplicableSubtypesLocked(context.getResources(),
                    imi);
        } finally {
            context.getResources().getConfiguration().setLocale(initialLocale);
        }
    }

    private void assertDefaultEnabledImes(final ArrayList<InputMethodInfo> preinstalledImes,
            final Locale systemLocale, final boolean isSystemReady, String... expectedImeNames) {
        final Context context = getInstrumentation().getTargetContext();
        final String[] actualImeNames = getPackageNames(callGetDefaultEnabledImesWithLocale(
                context, isSystemReady, preinstalledImes, systemLocale));
        assertEquals(expectedImeNames.length, actualImeNames.length);
        for (int i = 0; i < expectedImeNames.length; ++i) {
            assertEquals(expectedImeNames[i], actualImeNames[i]);
        }
    }

    private static List<InputMethodInfo> cloneViaParcel(final List<InputMethodInfo> list) {
        Parcel p = null;
        try {
            p = Parcel.obtain();
            p.writeTypedList(list);
            p.setDataPosition(0);
            return p.createTypedArrayList(InputMethodInfo.CREATOR);
        } finally {
            if (p != null) {
                p.recycle();
            }
        }
    }

    private static ArrayList<InputMethodInfo> callGetDefaultEnabledImesWithLocale(
            final Context context, final boolean isSystemReady,
            final ArrayList<InputMethodInfo> imis, final Locale locale) {
        final Locale initialLocale = context.getResources().getConfiguration().locale;
        try {
            context.getResources().getConfiguration().setLocale(locale);
            return InputMethodUtils.getDefaultEnabledImes(context, isSystemReady, imis);
        } finally {
            context.getResources().getConfiguration().setLocale(initialLocale);
        }
    }

    private String[] getPackageNames(final ArrayList<InputMethodInfo> imis) {
        final String[] packageNames = new String[imis.size()];
        for (int i = 0; i < imis.size(); ++i) {
            packageNames[i] = imis.get(i).getPackageName();
        }
        return packageNames;
    }

    private static void verifyEquality(InputMethodInfo expected, InputMethodInfo actual) {
        assertEquals(expected, actual);
        assertEquals(expected.getSubtypeCount(), actual.getSubtypeCount());
        for (int subtypeIndex = 0; subtypeIndex < expected.getSubtypeCount(); ++subtypeIndex) {
            final InputMethodSubtype expectedSubtype = expected.getSubtypeAt(subtypeIndex);
            final InputMethodSubtype actualSubtype = actual.getSubtypeAt(subtypeIndex);
            verifyEquality(expectedSubtype, actualSubtype);
        }
    }

    private static void verifyEquality(InputMethodSubtype expected, InputMethodSubtype actual) {
        assertEquals(expected, actual);
        assertEquals(expected.hashCode(), actual.hashCode());
    }

    private static InputMethodInfo createDummyInputMethodInfo(String packageName, String name,
            CharSequence label, boolean isAuxIme, boolean isDefault,
            List<InputMethodSubtype> subtypes) {
        final ResolveInfo ri = new ResolveInfo();
        final ServiceInfo si = new ServiceInfo();
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.enabled = true;
        ai.flags |= ApplicationInfo.FLAG_SYSTEM;
        si.applicationInfo = ai;
        si.enabled = true;
        si.packageName = packageName;
        si.name = name;
        si.exported = true;
        si.nonLocalizedLabel = label;
        ri.serviceInfo = si;
        return new InputMethodInfo(ri, isAuxIme, "", subtypes, 1, isDefault);
    }

    private static InputMethodSubtype createDummyInputMethodSubtype(String locale, String mode,
            boolean isAuxiliary, boolean overridesImplicitlyEnabledSubtype,
            boolean isAsciiCapable, boolean isEnabledWhenDefaultIsNotAsciiCapable) {

        final StringBuilder subtypeExtraValue = new StringBuilder();
        if (isEnabledWhenDefaultIsNotAsciiCapable) {
            subtypeExtraValue.append(EXTRA_VALUE_PAIR_SEPARATOR);
            subtypeExtraValue.append(EXTRA_VALUE_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        }

        // TODO: Remove following code. InputMethodSubtype#isAsciiCapable() has been publicly
        // available since API level 19 (KitKat). We no longer need to rely on extra value.
        if (isAsciiCapable) {
            subtypeExtraValue.append(EXTRA_VALUE_PAIR_SEPARATOR);
            subtypeExtraValue.append(EXTRA_VALUE_ASCII_CAPABLE);
        }

        return new InputMethodSubtypeBuilder()
                .setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeLocale(locale)
                .setSubtypeMode(mode)
                .setSubtypeExtraValue(subtypeExtraValue.toString())
                .setIsAuxiliary(isAuxiliary)
                .setOverridesImplicitlyEnabledSubtype(overridesImplicitlyEnabledSubtype)
                .setIsAsciiCapable(isAsciiCapable)
                .build();
    }

    private static ArrayList<InputMethodInfo> getImesWithDefaultVoiceIme() {
        ArrayList<InputMethodInfo> preinstalledImes = new ArrayList<>();
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("auto", SUBTYPE_MODE_VOICE, IS_AUX,
                    IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("DummyDefaultAutoVoiceIme",
                    "dummy.voice0", "DummyVoice0", IS_AUX, IS_DEFAULT, subtypes));
        }
        preinstalledImes.addAll(getImesWithoutDefaultVoiceIme());
        return preinstalledImes;
    }

    private static ArrayList<InputMethodInfo> getImesWithoutDefaultVoiceIme() {
        ArrayList<InputMethodInfo> preinstalledImes = new ArrayList<>();
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("auto", SUBTYPE_MODE_VOICE, IS_AUX,
                    IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("DummyNonDefaultAutoVoiceIme0",
                    "dummy.voice1", "DummyVoice1", IS_AUX, !IS_DEFAULT, subtypes));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("auto", SUBTYPE_MODE_VOICE, IS_AUX,
                    IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("DummyNonDefaultAutoVoiceIme1",
                    "dummy.voice2", "DummyVoice2", IS_AUX, !IS_DEFAULT, subtypes));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("DummyNonDefaultVoiceIme2",
                    "dummy.voice3", "DummyVoice3", IS_AUX, !IS_DEFAULT, subtypes));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("DummyDefaultEnKeyboardIme",
                    "dummy.keyboard0", "DummyKeyboard0", !IS_AUX, IS_DEFAULT, subtypes));
        }
        return preinstalledImes;
    }

    private static boolean contains(final String[] textList, final String textToBeChecked) {
        if (textList == null) {
            return false;
        }
        for (final String text : textList) {
            if (Objects.equals(textToBeChecked, text)) {
                return true;
            }
        }
        return false;
    }

    private static ArrayList<InputMethodInfo> getSamplePreinstalledImes(final String localeString) {
        ArrayList<InputMethodInfo> preinstalledImes = new ArrayList<>();

        // a dummy Voice IME
        {
            final boolean isDefaultIme = false;
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("", SUBTYPE_MODE_VOICE, IS_AUX,
                    IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("com.android.apps.inputmethod.voice",
                    "com.android.inputmethod.voice", "DummyVoiceIme", IS_AUX, isDefaultIme,
                    subtypes));
        }
        // a dummy Hindi IME
        {
            final boolean isDefaultIme = contains(new String[]{ "hi", "en-rIN" }, localeString);
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            // TODO: This subtype should be marked as IS_ASCII_CAPABLE
            subtypes.add(createDummyInputMethodSubtype("en_IN", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("hi", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("com.android.apps.inputmethod.hindi",
                    "com.android.inputmethod.hindi", "DummyHindiIme", !IS_AUX, isDefaultIme,
                    subtypes));
        }

        // a dummy Pinyin IME
        {
            final boolean isDefaultIme = contains(new String[]{ "zh-rCN" }, localeString);
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("zh_CN", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("com.android.apps.inputmethod.pinyin",
                    "com.android.apps.inputmethod.pinyin", "DummyPinyinIme", !IS_AUX, isDefaultIme,
                    subtypes));
        }

        // a dummy Korean IME
        {
            final boolean isDefaultIme = contains(new String[]{ "ko" }, localeString);
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("ko", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("com.android.apps.inputmethod.korean",
                    "com.android.apps.inputmethod.korean", "DummyKoreanIme", !IS_AUX, isDefaultIme,
                    subtypes));
        }

        // a dummy Latin IME
        {
            final boolean isDefaultIme = contains(
                    new String[]{ "en-rUS", "en-rGB", "en-rIN", "en", "hi" }, localeString);
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("en_GB", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("en_IN", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("hi", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, isDefaultIme,
                    subtypes));
        }

        // a dummy Japanese IME
        {
            final boolean isDefaultIme = contains(new String[]{ "ja", "ja-rJP" }, localeString);
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("ja", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("emoji", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("com.android.apps.inputmethod.japanese",
                    "com.android.apps.inputmethod.japanese", "DummyJapaneseIme", !IS_AUX,
                    isDefaultIme, subtypes));
        }

        return preinstalledImes;
    }

    @SmallTest
    public void testGetSuitableLocalesForSpellChecker() throws Exception {
        {
            final ArrayList<Locale> locales =
                    InputMethodUtils.getSuitableLocalesForSpellChecker(LOCALE_EN_US);
            assertEquals(3, locales.size());
            assertEquals(LOCALE_EN_US, locales.get(0));
            assertEquals(LOCALE_EN_GB, locales.get(1));
            assertEquals(LOCALE_EN, locales.get(2));
        }

        {
            final ArrayList<Locale> locales =
                    InputMethodUtils.getSuitableLocalesForSpellChecker(LOCALE_EN_GB);
            assertEquals(3, locales.size());
            assertEquals(LOCALE_EN_GB, locales.get(0));
            assertEquals(LOCALE_EN_US, locales.get(1));
            assertEquals(LOCALE_EN, locales.get(2));
        }

        {
            final ArrayList<Locale> locales =
                    InputMethodUtils.getSuitableLocalesForSpellChecker(LOCALE_EN);
            assertEquals(3, locales.size());
            assertEquals(LOCALE_EN, locales.get(0));
            assertEquals(LOCALE_EN_US, locales.get(1));
            assertEquals(LOCALE_EN_GB, locales.get(2));
        }

        {
            final ArrayList<Locale> locales =
                    InputMethodUtils.getSuitableLocalesForSpellChecker(LOCALE_EN_IN);
            assertEquals(4, locales.size());
            assertEquals(LOCALE_EN_IN, locales.get(0));
            assertEquals(LOCALE_EN_US, locales.get(1));
            assertEquals(LOCALE_EN_GB, locales.get(2));
            assertEquals(LOCALE_EN, locales.get(3));
        }

        {
            final ArrayList<Locale> locales =
                    InputMethodUtils.getSuitableLocalesForSpellChecker(LOCALE_JA_JP);
            assertEquals(5, locales.size());
            assertEquals(LOCALE_JA_JP, locales.get(0));
            assertEquals(LOCALE_JA, locales.get(1));
            assertEquals(LOCALE_EN_US, locales.get(2));
            assertEquals(LOCALE_EN_GB, locales.get(3));
            assertEquals(Locale.ENGLISH, locales.get(4));
        }

        // Test 3-letter language code.
        {
            final ArrayList<Locale> locales =
                    InputMethodUtils.getSuitableLocalesForSpellChecker(LOCALE_FIL_PH);
            assertEquals(5, locales.size());
            assertEquals(LOCALE_FIL_PH, locales.get(0));
            assertEquals(LOCALE_FIL, locales.get(1));
            assertEquals(LOCALE_EN_US, locales.get(2));
            assertEquals(LOCALE_EN_GB, locales.get(3));
            assertEquals(Locale.ENGLISH, locales.get(4));
        }

        // Test variant.
        {
            final ArrayList<Locale> locales =
                    InputMethodUtils.getSuitableLocalesForSpellChecker(LOCALE_TH_TH_TH);
            assertEquals(6, locales.size());
            assertEquals(LOCALE_TH_TH_TH, locales.get(0));
            assertEquals(LOCALE_TH_TH, locales.get(1));
            assertEquals(LOCALE_TH, locales.get(2));
            assertEquals(LOCALE_EN_US, locales.get(3));
            assertEquals(LOCALE_EN_GB, locales.get(4));
            assertEquals(Locale.ENGLISH, locales.get(5));
        }

        // Test Locale extension.
        {
            final Locale localeWithoutVariant = LOCALE_JA_JP;
            final Locale localeWithVariant = new Locale.Builder()
                    .setLocale(LOCALE_JA_JP)
                    .setExtension('x', "android")
                    .build();
            assertFalse(localeWithoutVariant.equals(localeWithVariant));

            final ArrayList<Locale> locales =
                    InputMethodUtils.getSuitableLocalesForSpellChecker(localeWithVariant);
            assertEquals(5, locales.size());
            assertEquals(LOCALE_JA_JP, locales.get(0));
            assertEquals(LOCALE_JA, locales.get(1));
            assertEquals(LOCALE_EN_US, locales.get(2));
            assertEquals(LOCALE_EN_GB, locales.get(3));
            assertEquals(Locale.ENGLISH, locales.get(4));
        }
    }
}
