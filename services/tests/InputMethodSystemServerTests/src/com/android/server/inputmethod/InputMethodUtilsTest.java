/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import android.os.Parcel;
import android.util.ArrayMap;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.inputmethod.StartInputFlags;

import com.google.common.truth.Truth;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class InputMethodUtilsTest {
    private static final boolean IS_AUX = true;
    private static final boolean IS_DEFAULT = true;
    private static final boolean IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE = true;
    private static final boolean IS_ASCII_CAPABLE = true;
    private static final boolean IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE = true;
    private static final boolean CHECK_COUNTRY = true;
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
    private static final Locale LOCALE_JA_JP = new Locale("ja", "JP");
    private static final Locale LOCALE_ZH_CN = new Locale("zh", "CN");
    private static final Locale LOCALE_ZH_TW = new Locale("zh", "TW");
    private static final Locale LOCALE_IN = new Locale("in");
    private static final Locale LOCALE_ID = new Locale("id");
    private static final String SUBTYPE_MODE_KEYBOARD = "keyboard";
    private static final String SUBTYPE_MODE_VOICE = "voice";
    private static final String SUBTYPE_MODE_HANDWRITING = "handwriting";
    private static final String SUBTYPE_MODE_ANY = null;
    private static final String EXTRA_VALUE_PAIR_SEPARATOR = ",";
    private static final String EXTRA_VALUE_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE =
            "EnabledWhenDefaultIsNotAsciiCapable";

    @Test
    public void testVoiceImes() throws Exception {
        // locale: en_US
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_EN_US,
                "FakeDefaultEnKeyboardIme", "FakeDefaultAutoVoiceIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_US,
                "FakeDefaultEnKeyboardIme", "FakeNonDefaultAutoVoiceIme0",
                "FakeNonDefaultAutoVoiceIme1");
        assertDefaultEnabledMinimumImes(getImesWithDefaultVoiceIme(), LOCALE_EN_US,
                "FakeDefaultEnKeyboardIme");
        assertDefaultEnabledMinimumImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_US,
                "FakeDefaultEnKeyboardIme");

        // locale: en_GB
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_EN_GB,
                "FakeDefaultEnKeyboardIme", "FakeDefaultAutoVoiceIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_GB,
                "FakeDefaultEnKeyboardIme", "FakeNonDefaultAutoVoiceIme0",
                "FakeNonDefaultAutoVoiceIme1");
        assertDefaultEnabledMinimumImes(getImesWithDefaultVoiceIme(), LOCALE_EN_GB,
                "FakeDefaultEnKeyboardIme");
        assertDefaultEnabledMinimumImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_GB,
                "FakeDefaultEnKeyboardIme");

        // locale: ja_JP
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_JA_JP,
                "FakeDefaultEnKeyboardIme", "FakeDefaultAutoVoiceIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_JA_JP,
                "FakeDefaultEnKeyboardIme", "FakeNonDefaultAutoVoiceIme0",
                "FakeNonDefaultAutoVoiceIme1");
        assertDefaultEnabledMinimumImes(getImesWithDefaultVoiceIme(), LOCALE_JA_JP,
                "FakeDefaultEnKeyboardIme");
        assertDefaultEnabledMinimumImes(getImesWithoutDefaultVoiceIme(), LOCALE_JA_JP,
                "FakeDefaultEnKeyboardIme");
    }

    @Test
    public void testKeyboardImes() throws Exception {
        // locale: en_US
        assertDefaultEnabledImes(getSamplePreinstalledImes("en-rUS"), LOCALE_EN_US,
                "com.android.apps.inputmethod.latin", "com.android.apps.inputmethod.voice");
        assertDefaultEnabledMinimumImes(getSamplePreinstalledImes("en-rUS"), LOCALE_EN_US,
                "com.android.apps.inputmethod.latin");

        // locale: en_GB
        assertDefaultEnabledImes(getSamplePreinstalledImes("en-rGB"), LOCALE_EN_GB,
                "com.android.apps.inputmethod.latin", "com.android.apps.inputmethod.voice");
        assertDefaultEnabledMinimumImes(getSamplePreinstalledImes("en-rGB"), LOCALE_EN_GB,
                "com.android.apps.inputmethod.latin");

        // locale: en_IN
        assertDefaultEnabledImes(getSamplePreinstalledImes("en-rIN"), LOCALE_EN_IN,
                "com.android.apps.inputmethod.hindi",
                "com.android.apps.inputmethod.latin", "com.android.apps.inputmethod.voice");
        assertDefaultEnabledMinimumImes(getSamplePreinstalledImes("en-rIN"), LOCALE_EN_IN,
                "com.android.apps.inputmethod.hindi",
                "com.android.apps.inputmethod.latin");

        // locale: hi
        assertDefaultEnabledImes(getSamplePreinstalledImes("hi"), LOCALE_HI,
                "com.android.apps.inputmethod.hindi", "com.android.apps.inputmethod.latin",
                "com.android.apps.inputmethod.voice");
        assertDefaultEnabledMinimumImes(getSamplePreinstalledImes("hi"), LOCALE_HI,
                "com.android.apps.inputmethod.hindi", "com.android.apps.inputmethod.latin");

        // locale: ja_JP
        assertDefaultEnabledImes(getSamplePreinstalledImes("ja-rJP"), LOCALE_JA_JP,
                "com.android.apps.inputmethod.japanese", "com.android.apps.inputmethod.voice");
        assertDefaultEnabledMinimumImes(getSamplePreinstalledImes("ja-rJP"), LOCALE_JA_JP,
                "com.android.apps.inputmethod.japanese");

        // locale: zh_CN
        assertDefaultEnabledImes(getSamplePreinstalledImes("zh-rCN"), LOCALE_ZH_CN,
                "com.android.apps.inputmethod.pinyin", "com.android.apps.inputmethod.voice");
        assertDefaultEnabledMinimumImes(getSamplePreinstalledImes("zh-rCN"), LOCALE_ZH_CN,
                "com.android.apps.inputmethod.pinyin");

        // locale: zh_TW
        // Note: In this case, no IME is suitable for the system locale. Hence we will pick up a
        // fallback IME regardless of the "default" attribute.
        assertDefaultEnabledImes(getSamplePreinstalledImes("zh-rTW"), LOCALE_ZH_TW,
                "com.android.apps.inputmethod.latin", "com.android.apps.inputmethod.voice");
        assertDefaultEnabledMinimumImes(getSamplePreinstalledImes("zh-rTW"), LOCALE_ZH_TW,
                "com.android.apps.inputmethod.latin");
    }

    @Test
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

    @Test
    public void testGetImplicitlyApplicableSubtypesLocked() throws Exception {
        final InputMethodSubtype nonAutoEnUS = createFakeInputMethodSubtype("en_US",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoEnGB = createFakeInputMethodSubtype("en_GB",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoFrCA = createFakeInputMethodSubtype("fr_CA",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoFr = createFakeInputMethodSubtype("fr_CA",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoFil = createFakeInputMethodSubtype("fil",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoIn = createFakeInputMethodSubtype("in",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoId = createFakeInputMethodSubtype("id",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype autoSubtype = createFakeInputMethodSubtype("auto",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoJa = createFakeInputMethodSubtype("ja",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                !IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoHi = createFakeInputMethodSubtype("hi",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                !IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoSrCyrl = createFakeInputMethodSubtype("sr",
                "sr-Cyrl", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoSrLatn = createFakeInputMethodSubtype("sr_ZZ",
                "sr-Latn", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, IS_ASCII_CAPABLE,
                !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoHandwritingEn = createFakeInputMethodSubtype("en",
                SUBTYPE_MODE_HANDWRITING, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                !IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoHandwritingFr = createFakeInputMethodSubtype("fr",
                SUBTYPE_MODE_HANDWRITING, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                !IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoHandwritingSrCyrl = createFakeInputMethodSubtype("sr",
                "sr-Cyrl", SUBTYPE_MODE_HANDWRITING, !IS_AUX,
                !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoHandwritingSrLatn = createFakeInputMethodSubtype("sr_ZZ",
                "sr-Latn", SUBTYPE_MODE_HANDWRITING, !IS_AUX,
                !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype =
                createFakeInputMethodSubtype("zz", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                        !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                        IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2 =
                createFakeInputMethodSubtype("zz", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                        !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                        IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);

        // Make sure that an automatic subtype (overridesImplicitlyEnabledSubtype:true) is
        // selected no matter what locale is specified.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoEnGB);
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoFil);
            subtypes.add(autoSubtype);  // overridesImplicitlyEnabledSubtype == true
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2);
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_EN_US), imi);
            assertEquals(1, result.size());
            verifyEquality(autoSubtype, result.get(0));
        }

        // Make sure that a subtype whose locale is exactly equal to the specified locale is
        // selected as long as there is no no automatic subtype
        // (overridesImplicitlyEnabledSubtype:true) in the given list.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoEnUS);  // locale == "en_US"
            subtypes.add(nonAutoEnGB);
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoFil);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2);
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_EN_US), imi);
            assertEquals(2, result.size());
            verifyEquality(nonAutoEnUS, result.get(0));
            verifyEquality(nonAutoHandwritingEn, result.get(1));
        }

        // Make sure that a subtype whose locale is exactly equal to the specified locale is
        // selected as long as there is no automatic subtype
        // (overridesImplicitlyEnabledSubtype:true) in the given list.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoEnGB); // locale == "en_GB"
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoFil);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_EN_GB), imi);
            assertEquals(2, result.size());
            verifyEquality(nonAutoEnGB, result.get(0));
            verifyEquality(nonAutoHandwritingEn, result.get(1));
        }

        // If there is no automatic subtype (overridesImplicitlyEnabledSubtype:true) and
        // any subtype whose locale is exactly equal to the specified locale in the given list,
        // try to find a subtype whose language is equal to the language part of the given locale.
        // Here make sure that a subtype (locale: "fr_CA") can be found with locale: "fr".
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoFrCA);  // locale == "fr_CA"
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoFil);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2);
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_FR), imi);
            assertEquals(2, result.size());
            verifyEquality(nonAutoFrCA, result.get(0));
            verifyEquality(nonAutoHandwritingFr, result.get(1));
        }
        // Then make sure that a subtype (locale: "fr") can be found with locale: "fr_CA".
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoFr);  // locale == "fr"
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoFil);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2);
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_FR_CA), imi);
            assertEquals(2, result.size());
            verifyEquality(nonAutoFrCA, result.get(0));
            verifyEquality(nonAutoHandwritingFr, result.get(1));
        }

        // Make sure that subtypes which have "EnabledWhenDefaultIsNotAsciiCapable" in its
        // extra value is selected if and only if all other selected IMEs are not AsciiCapable.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoJa);    // not ASCII capable
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2);
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_JA_JP), imi);
            assertEquals(3, result.size());
            verifyEquality(nonAutoJa, result.get(0));
            verifyEquality(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype, result.get(1));
            verifyEquality(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2, result.get(2));
        }

        // Make sure that if there is no subtype that matches the language requested, then we just
        // use the first keyboard subtype.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoHi);
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_JA_JP), imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoHi, result.get(0));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoHi);
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_JA_JP), imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoEnUS, result.get(0));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoHi);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_JA_JP), imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoEnUS, result.get(0));
        }

        // Make sure that both language and script are taken into account to find the best matching
        // subtype.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoSrCyrl);
            subtypes.add(nonAutoSrLatn);
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            subtypes.add(nonAutoHandwritingSrCyrl);
            subtypes.add(nonAutoHandwritingSrLatn);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(Locale.forLanguageTag("sr-Latn-RS")), imi);
            assertEquals(2, result.size());
            assertThat(nonAutoSrLatn, is(in(result)));
            assertThat(nonAutoHandwritingSrLatn, is(in(result)));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoSrCyrl);
            subtypes.add(nonAutoSrLatn);
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            subtypes.add(nonAutoHandwritingSrCyrl);
            subtypes.add(nonAutoHandwritingSrLatn);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(Locale.forLanguageTag("sr-Cyrl-RS")), imi);
            assertEquals(2, result.size());
            assertThat(nonAutoSrCyrl, is(in(result)));
            assertThat(nonAutoHandwritingSrCyrl, is(in(result)));
        }

        // Make sure that secondary locales are taken into account to find the best matching
        // subtype.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoEnGB);
            subtypes.add(nonAutoSrCyrl);
            subtypes.add(nonAutoSrLatn);
            subtypes.add(nonAutoFr);
            subtypes.add(nonAutoFrCA);
            subtypes.add(nonAutoHandwritingEn);
            subtypes.add(nonAutoHandwritingFr);
            subtypes.add(nonAutoHandwritingSrCyrl);
            subtypes.add(nonAutoHandwritingSrLatn);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(
                                    Locale.forLanguageTag("sr-Latn-RS-x-android"),
                                    Locale.forLanguageTag("ja-JP"),
                                    Locale.forLanguageTag("fr-FR"),
                                    Locale.forLanguageTag("en-GB"),
                                    Locale.forLanguageTag("en-US")),
                            imi);
            assertEquals(6, result.size());
            assertThat(nonAutoEnGB, is(in(result)));
            assertThat(nonAutoFr, is(in(result)));
            assertThat(nonAutoSrLatn, is(in(result)));
            assertThat(nonAutoHandwritingEn, is(in(result)));
            assertThat(nonAutoHandwritingFr, is(in(result)));
            assertThat(nonAutoHandwritingSrLatn, is(in(result)));
        }

        // Make sure that 3-letter language code can be handled.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoFil);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_FIL_PH), imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoFil, result.get(0));
        }

        // Make sure that we never end up matching "fi" (finnish) with "fil" (filipino).
        // Also make sure that the first subtype will be used as the last-resort candidate.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoFil);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_FI), imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoJa, result.get(0));
        }

        // Make sure that "in" and "id" conversion is taken into account.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoIn);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_IN), imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoIn, result.get(0));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoIn);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_ID), imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoIn, result.get(0));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoId);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_IN), imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoId, result.get(0));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoId);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_ID), imi);
            assertEquals(1, result.size());
            verifyEquality(nonAutoId, result.get(0));
        }

        // If there is no automatic subtype (overridesImplicitlyEnabledSubtype:true) and the system
        // provides multiple locales, we try to enable multiple subtypes.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoEnUS);
            subtypes.add(nonAutoFrCA);
            subtypes.add(nonAutoIn);
            subtypes.add(nonAutoJa);
            subtypes.add(nonAutoFil);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype);
            subtypes.add(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype2);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            final ArrayList<InputMethodSubtype> result =
                    SubtypeUtils.getImplicitlyApplicableSubtypes(
                            new LocaleList(LOCALE_FR, LOCALE_EN_US, LOCALE_JA_JP), imi);
            assertThat(nonAutoFrCA, is(in(result)));
            assertThat(nonAutoEnUS, is(in(result)));
            assertThat(nonAutoJa, is(in(result)));
            assertThat(nonAutoIn, not(is(in(result))));
            assertThat(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype, not(is(in(result))));
            assertThat(nonAutoEnabledWhenDefaultIsNotAsciiCalableSubtype, not(is(in(result))));
        }
    }

    @Test
    public void testContainsSubtypeOf() throws Exception {
        final InputMethodSubtype nonAutoEnUS = createFakeInputMethodSubtype("en_US",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoFil = createFakeInputMethodSubtype("fil",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoFilPH = createFakeInputMethodSubtype("fil_PH",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoIn = createFakeInputMethodSubtype("in",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        final InputMethodSubtype nonAutoId = createFakeInputMethodSubtype("id",
                SUBTYPE_MODE_KEYBOARD, !IS_AUX, !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE,
                IS_ASCII_CAPABLE, IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);

        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);

            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_EN, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_EN, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_EN_US, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_EN_US, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_EN_US, !CHECK_COUNTRY,
                    SUBTYPE_MODE_VOICE));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_EN_US, CHECK_COUNTRY,
                    SUBTYPE_MODE_VOICE));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_EN_US, !CHECK_COUNTRY,
                    SUBTYPE_MODE_ANY));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_EN_US, CHECK_COUNTRY,
                    SUBTYPE_MODE_ANY));

            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_EN_GB, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_EN_GB, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
        }

        // Make sure that 3-letter language code ("fil") can be handled.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoFil);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FIL, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FIL, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FIL_PH, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FIL_PH, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));

            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FI, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FI, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FI_FI, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FI_FI, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
        }

        // Make sure that 3-letter language code ("fil_PH") can be handled.
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoFilPH);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FIL, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FIL, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FIL_PH, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FIL_PH, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));

            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FI, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FI, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FI_FI, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertFalse(SubtypeUtils.containsSubtypeOf(imi, LOCALE_FI_FI, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
        }

        // Make sure that a subtype whose locale is "in" can be queried with "id".
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoIn);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_IN, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_IN, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_ID, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_ID, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
        }

        // Make sure that a subtype whose locale is "id" can be queried with "in".
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(nonAutoId);
            subtypes.add(nonAutoEnUS);
            final InputMethodInfo imi = createFakeInputMethodInfo(
                    "com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes);
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_IN, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_IN, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_ID, !CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
            assertTrue(SubtypeUtils.containsSubtypeOf(imi, LOCALE_ID, CHECK_COUNTRY,
                    SUBTYPE_MODE_KEYBOARD));
        }
    }

    @Test
    public void testChooseSystemVoiceIme() throws Exception {
        final InputMethodInfo systemIme = createFakeInputMethodInfo("SystemIme", "fake.voice0",
                true /* isSystem */);

        // Returns null when the config value is null.
        {
            final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            methodMap.put(systemIme.getId(), systemIme);
            assertNull(InputMethodInfoUtils.chooseSystemVoiceIme(InputMethodMap.of(methodMap),
                    null, ""));
        }

        // Returns null when the config value is empty.
        {
            final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            methodMap.put(systemIme.getId(), systemIme);
            assertNull(InputMethodInfoUtils.chooseSystemVoiceIme(InputMethodMap.of(methodMap), "",
                    ""));
        }

        // Returns null when the configured package doesn't have an IME.
        {
            assertNull(InputMethodInfoUtils.chooseSystemVoiceIme(
                    InputMethodMap.emptyMap(),
                    systemIme.getPackageName(), ""));
        }

        // Returns the right one when the current default is null.
        {
            final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            methodMap.put(systemIme.getId(), systemIme);
            assertEquals(systemIme, InputMethodInfoUtils.chooseSystemVoiceIme(
                    InputMethodMap.of(methodMap),
                    systemIme.getPackageName(), null));
        }

        // Returns the right one when the current default is empty.
        {
            final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            methodMap.put(systemIme.getId(), systemIme);
            assertEquals(systemIme, InputMethodInfoUtils.chooseSystemVoiceIme(
                    InputMethodMap.of(methodMap),
                    systemIme.getPackageName(), ""));
        }

        // Returns null when the current default isn't found.
        {
            assertNull(InputMethodInfoUtils.chooseSystemVoiceIme(
                    InputMethodMap.emptyMap(),
                    systemIme.getPackageName(), systemIme.getId()));
        }

        // Returns null when there are multiple IMEs defined by the config package.
        {
            final InputMethodInfo secondIme = createFakeInputMethodInfo(systemIme.getPackageName(),
                    "fake.voice1", true /* isSystem */);
            final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            methodMap.put(systemIme.getId(), systemIme);
            methodMap.put(secondIme.getId(), secondIme);
            assertNull(InputMethodInfoUtils.chooseSystemVoiceIme(InputMethodMap.of(methodMap),
                    systemIme.getPackageName(), ""));
        }

        // Returns the current one when the current default and config point to the same package.
        {
            final InputMethodInfo secondIme = createFakeInputMethodInfo("SystemIme", "fake.voice1",
                    true /* isSystem */);
            final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            methodMap.put(systemIme.getId(), systemIme);
            methodMap.put(secondIme.getId(), secondIme);
            assertEquals(systemIme, InputMethodInfoUtils.chooseSystemVoiceIme(
                    InputMethodMap.of(methodMap),
                    systemIme.getPackageName(), systemIme.getId()));
        }

        // Doesn't return the current default if it isn't a system app.
        {
            final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            final InputMethodInfo nonSystemIme = createFakeInputMethodInfo("NonSystemIme",
                    "fake.voice0", false /* isSystem */);
            methodMap.put(nonSystemIme.getId(), nonSystemIme);
            assertNull(InputMethodInfoUtils.chooseSystemVoiceIme(InputMethodMap.of(methodMap),
                    nonSystemIme.getPackageName(), nonSystemIme.getId()));
        }

        // Returns null if the configured one isn't a system app.
        {
            final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            final InputMethodInfo nonSystemIme = createFakeInputMethodInfo(
                    "FakeDefaultAutoVoiceIme", "fake.voice0", false /* isSystem */);
            methodMap.put(systemIme.getId(), systemIme);
            methodMap.put(nonSystemIme.getId(), nonSystemIme);
            assertNull(InputMethodInfoUtils.chooseSystemVoiceIme(InputMethodMap.of(methodMap),
                    nonSystemIme.getPackageName(), ""));
        }
    }

    private void assertDefaultEnabledImes(final ArrayList<InputMethodInfo> preinstalledImes,
            final Locale systemLocale, String... expectedImeNames) {
        final Context context = createTargetContextWithLocales(new LocaleList(systemLocale));
        final String[] actualImeNames = getPackageNames(
                InputMethodInfoUtils.getDefaultEnabledImes(context, preinstalledImes));
        assertEquals(expectedImeNames.length, actualImeNames.length);
        for (int i = 0; i < expectedImeNames.length; ++i) {
            assertEquals(expectedImeNames[i], actualImeNames[i]);
        }
    }

    private void assertDefaultEnabledMinimumImes(final ArrayList<InputMethodInfo> preinstalledImes,
            final Locale systemLocale, String... expectedImeNames) {
        final Context context = createTargetContextWithLocales(new LocaleList(systemLocale));
        final String[] actualImeNames = getPackageNames(
                InputMethodInfoUtils.getDefaultEnabledImes(context, preinstalledImes,
                        true /* onlyMinimum */));
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

    private Context createTargetContextWithLocales(final LocaleList locales) {
        final Configuration resourceConfiguration = new Configuration();
        resourceConfiguration.setLocales(locales);
        return InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .createConfigurationContext(resourceConfiguration);
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

    private static InputMethodInfo createFakeInputMethodInfo(String packageName, String name,
            boolean isSystem) {
        final ResolveInfo ri = new ResolveInfo();
        final ServiceInfo si = new ServiceInfo();
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.enabled = true;
        if (isSystem) {
            ai.flags |= ApplicationInfo.FLAG_SYSTEM;
        }
        si.applicationInfo = ai;
        si.enabled = true;
        si.packageName = packageName;
        si.name = name;
        si.exported = true;
        ri.serviceInfo = si;
        return new InputMethodInfo(ri, false, "", Collections.emptyList(), 1, true);
    }

    private static InputMethodInfo createFakeInputMethodInfo(String packageName, String name,
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

    private static InputMethodSubtype createFakeInputMethodSubtype(String locale, String mode,
            boolean isAuxiliary, boolean overridesImplicitlyEnabledSubtype,
            boolean isAsciiCapable, boolean isEnabledWhenDefaultIsNotAsciiCapable) {
        return createFakeInputMethodSubtype(locale, null /* languageTag */, mode, isAuxiliary,
                overridesImplicitlyEnabledSubtype, isAsciiCapable,
                isEnabledWhenDefaultIsNotAsciiCapable);
    }

    private static InputMethodSubtype createFakeInputMethodSubtype(String locale,
            String languageTag, String mode, boolean isAuxiliary,
            boolean overridesImplicitlyEnabledSubtype, boolean isAsciiCapable,
            boolean isEnabledWhenDefaultIsNotAsciiCapable) {
        final StringBuilder subtypeExtraValue = new StringBuilder();
        if (isEnabledWhenDefaultIsNotAsciiCapable) {
            subtypeExtraValue.append(EXTRA_VALUE_PAIR_SEPARATOR);
            subtypeExtraValue.append(EXTRA_VALUE_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE);
        }

        return new InputMethodSubtypeBuilder()
                .setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeLocale(locale)
                .setLanguageTag(languageTag)
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
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(createFakeInputMethodSubtype("auto", SUBTYPE_MODE_VOICE, IS_AUX,
                    IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createFakeInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createFakeInputMethodInfo("FakeDefaultAutoVoiceIme",
                    "fake.voice0", "FakeVoice0", IS_AUX, IS_DEFAULT, subtypes));
        }
        preinstalledImes.addAll(getImesWithoutDefaultVoiceIme());
        return preinstalledImes;
    }

    private static ArrayList<InputMethodInfo> getImesWithoutDefaultVoiceIme() {
        ArrayList<InputMethodInfo> preinstalledImes = new ArrayList<>();
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(createFakeInputMethodSubtype("auto", SUBTYPE_MODE_VOICE, IS_AUX,
                    IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createFakeInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createFakeInputMethodInfo("FakeNonDefaultAutoVoiceIme0",
                    "fake.voice1", "FakeVoice1", IS_AUX, !IS_DEFAULT, subtypes));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(createFakeInputMethodSubtype("auto", SUBTYPE_MODE_VOICE, IS_AUX,
                    IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createFakeInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createFakeInputMethodInfo("FakeNonDefaultAutoVoiceIme1",
                    "fake.voice2", "FakeVoice2", IS_AUX, !IS_DEFAULT, subtypes));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(createFakeInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createFakeInputMethodInfo("FakeNonDefaultVoiceIme2",
                    "fake.voice3", "FakeVoice3", IS_AUX, !IS_DEFAULT, subtypes));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(createFakeInputMethodSubtype("en_US", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createFakeInputMethodInfo("FakeDefaultEnKeyboardIme",
                    "fake.keyboard0", "FakeKeyboard0", !IS_AUX, IS_DEFAULT, subtypes));
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

        // a fake Voice IME
        {
            final boolean isDefaultIme = false;
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(createFakeInputMethodSubtype("", SUBTYPE_MODE_VOICE, IS_AUX,
                    IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createFakeInputMethodInfo("com.android.apps.inputmethod.voice",
                    "com.android.inputmethod.voice", "FakeVoiceIme", IS_AUX, isDefaultIme,
                    subtypes));
        }
        // a fake Hindi IME
        {
            final boolean isDefaultIme = contains(new String[]{ "hi", "en-rIN" }, localeString);
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            // TODO: This subtype should be marked as IS_ASCII_CAPABLE
            subtypes.add(createFakeInputMethodSubtype("en_IN", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createFakeInputMethodSubtype("hi", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createFakeInputMethodInfo("com.android.apps.inputmethod.hindi",
                    "com.android.inputmethod.hindi", "FakeHindiIme", !IS_AUX, isDefaultIme,
                    subtypes));
        }

        // a fake Pinyin IME
        {
            final boolean isDefaultIme = contains(new String[]{ "zh-rCN" }, localeString);
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(createFakeInputMethodSubtype("zh_CN", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createFakeInputMethodInfo("com.android.apps.inputmethod.pinyin",
                    "com.android.apps.inputmethod.pinyin", "FakePinyinIme", !IS_AUX, isDefaultIme,
                    subtypes));
        }

        // a fake Korean IME
        {
            final boolean isDefaultIme = contains(new String[]{ "ko" }, localeString);
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(createFakeInputMethodSubtype("ko", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createFakeInputMethodInfo("com.android.apps.inputmethod.korean",
                    "com.android.apps.inputmethod.korean", "FakeKoreanIme", !IS_AUX, isDefaultIme,
                    subtypes));
        }

        // a fake Latin IME
        {
            final boolean isDefaultIme = contains(
                    new String[]{ "en-rUS", "en-rGB", "en-rIN", "en", "hi" }, localeString);
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(createFakeInputMethodSubtype("en_US", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createFakeInputMethodSubtype("en_GB", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createFakeInputMethodSubtype("en_IN", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createFakeInputMethodSubtype("hi", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createFakeInputMethodInfo("com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "FakeLatinIme", !IS_AUX, isDefaultIme,
                    subtypes));
        }

        // a fake Japanese IME
        {
            final boolean isDefaultIme = contains(new String[]{ "ja", "ja-rJP" }, localeString);
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
            subtypes.add(createFakeInputMethodSubtype("ja", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            subtypes.add(createFakeInputMethodSubtype("emoji", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_OVERRIDES_IMPLICITLY_ENABLED_SUBTYPE, !IS_ASCII_CAPABLE,
                    !IS_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE));
            preinstalledImes.add(createFakeInputMethodInfo("com.android.apps.inputmethod.japanese",
                    "com.android.apps.inputmethod.japanese", "FakeJapaneseIme", !IS_AUX,
                    isDefaultIme, subtypes));
        }

        return preinstalledImes;
    }

    @Test
    public void testIsSoftInputModeStateVisibleAllowed() {
        // On pre-P devices, SOFT_INPUT_STATE_VISIBLE/SOFT_INPUT_STATE_ALWAYS_VISIBLE are always
        // allowed, regardless of the focused view state.
        assertTrue(InputMethodUtils.isSoftInputModeStateVisibleAllowed(
                Build.VERSION_CODES.O_MR1, 0));
        assertTrue(InputMethodUtils.isSoftInputModeStateVisibleAllowed(
                Build.VERSION_CODES.O_MR1, StartInputFlags.VIEW_HAS_FOCUS));
        assertTrue(InputMethodUtils.isSoftInputModeStateVisibleAllowed(
                Build.VERSION_CODES.O_MR1,
                StartInputFlags.VIEW_HAS_FOCUS | StartInputFlags.IS_TEXT_EDITOR));

        // On P+ devices, SOFT_INPUT_STATE_VISIBLE/SOFT_INPUT_STATE_ALWAYS_VISIBLE are allowed only
        // when there is a focused View and its View#onCheckIsTextEditor() returns true.
        assertFalse(InputMethodUtils.isSoftInputModeStateVisibleAllowed(
                Build.VERSION_CODES.P, 0));
        assertFalse(InputMethodUtils.isSoftInputModeStateVisibleAllowed(
                Build.VERSION_CODES.P, StartInputFlags.VIEW_HAS_FOCUS));
        assertTrue(InputMethodUtils.isSoftInputModeStateVisibleAllowed(
                Build.VERSION_CODES.P,
                StartInputFlags.VIEW_HAS_FOCUS | StartInputFlags.IS_TEXT_EDITOR));
    }

    private static void verifySplitEnabledImeStr(@NonNull String enabledImeStr,
            @NonNull String... expected) {
        final ArrayList<String> actual = new ArrayList<>();
        InputMethodUtils.splitEnabledImeStr(enabledImeStr, actual::add);
        if (expected.length == 0) {
            Truth.assertThat(actual).isEmpty();
        } else {
            Truth.assertThat(actual).containsExactlyElementsIn(expected);
        }
    }

    @Test
    public void testSplitEnabledImeStr() {
        verifySplitEnabledImeStr("");
        verifySplitEnabledImeStr("com.android/.ime1", "com.android/.ime1");
        verifySplitEnabledImeStr("com.android/.ime1;1;2;3", "com.android/.ime1");
        verifySplitEnabledImeStr("com.android/.ime1;1;2;3:com.android/.ime2",
                "com.android/.ime1", "com.android/.ime2");
        verifySplitEnabledImeStr("com.android/.ime1:com.android/.ime2",
                "com.android/.ime1", "com.android/.ime2");
        verifySplitEnabledImeStr("com.android/.ime1:com.android/.ime2:com.android/.ime3",
                "com.android/.ime1", "com.android/.ime2", "com.android/.ime3");
        verifySplitEnabledImeStr("com.android/.ime1;1:com.android/.ime2;1:com.android/.ime3;1",
                "com.android/.ime1", "com.android/.ime2", "com.android/.ime3");
    }

    @Test
    public void testConcatEnabledImeIds() {
        Truth.assertThat(InputMethodUtils.concatEnabledImeIds("")).isEmpty();
        Truth.assertThat(InputMethodUtils.concatEnabledImeIds("", "com.android/.ime1"))
                .isEqualTo("com.android/.ime1");
        Truth.assertThat(InputMethodUtils.concatEnabledImeIds(
                        "com.android/.ime1", "com.android/.ime1"))
                .isEqualTo("com.android/.ime1");
        Truth.assertThat(InputMethodUtils.concatEnabledImeIds(
                        "com.android/.ime1", "com.android/.ime2"))
                .isEqualTo("com.android/.ime1:com.android/.ime2");
        Truth.assertThat(InputMethodUtils.concatEnabledImeIds(
                        "com.android/.ime1", "com.android/.ime2", "com.android/.ime3"))
                .isEqualTo("com.android/.ime1:com.android/.ime2:com.android/.ime3");
        Truth.assertThat(InputMethodUtils.concatEnabledImeIds(
                        "com.android/.ime1:com.android/.ime2", "com.android/.ime1"))
                .isEqualTo("com.android/.ime1:com.android/.ime2");
        Truth.assertThat(InputMethodUtils.concatEnabledImeIds(
                        "com.android/.ime1:com.android/.ime2", "com.android/.ime3"))
                .isEqualTo("com.android/.ime1:com.android/.ime2:com.android/.ime3");
    }
}
