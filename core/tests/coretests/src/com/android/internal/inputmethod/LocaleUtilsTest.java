/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.os.LocaleList;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocaleUtilsTest {

    private static final LocaleUtils.LocaleExtractor<Locale> sIdentityMapper = source -> source;

    private static final Locale LOCALE_EN = new Locale("en");
    private static final Locale LOCALE_EN_US = new Locale("en", "US");
    private static final Locale LOCALE_EN_GB = new Locale("en", "GB");
    private static final Locale LOCALE_EN_IN = new Locale("en", "IN");
    private static final Locale LOCALE_FIL = new Locale("fil");
    private static final Locale LOCALE_FIL_PH = new Locale("fil", "PH");
    private static final Locale LOCALE_JA = new Locale("ja");
    private static final Locale LOCALE_JA_JP = new Locale("ja", "JP");
    private static final Locale LOCALE_TH = new Locale("ht");
    private static final Locale LOCALE_TH_TH = new Locale("ht", "TH");
    private static final Locale LOCALE_TH_TH_TH = new Locale("ht", "TH", "TH");

    @Test
    public void testFilterByLanguageEmptyLanguageList() throws Exception {
        final ArrayList<Locale> availableLocales = new ArrayList<>();
        availableLocales.add(Locale.forLanguageTag("en-US"));
        availableLocales.add(Locale.forLanguageTag("fr-CA"));
        availableLocales.add(Locale.forLanguageTag("in"));
        availableLocales.add(Locale.forLanguageTag("ja"));
        availableLocales.add(Locale.forLanguageTag("fil"));

        final LocaleList preferredLocales = LocaleList.getEmptyLocaleList();

        final ArrayList<Locale> dest = new ArrayList<>();
        LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
        assertEquals(0, dest.size());
    }

    @Test
    public void testFilterDoesNotMatchAnything() throws Exception {
        final ArrayList<Locale> availableLocales = new ArrayList<>();
        availableLocales.add(Locale.forLanguageTag("en-US"));
        availableLocales.add(Locale.forLanguageTag("fr-CA"));
        availableLocales.add(Locale.forLanguageTag("in"));
        availableLocales.add(Locale.forLanguageTag("ja"));
        availableLocales.add(Locale.forLanguageTag("fil"));

        final LocaleList preferredLocales = LocaleList.forLanguageTags("zh-Hans-TW");

        final ArrayList<Locale> dest = new ArrayList<>();
        LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
        assertEquals(0, dest.size());
    }

    @Test
    public void testFilterByLanguageEmptySource() throws Exception {
        final ArrayList<Locale> availableLocales = new ArrayList<>();

        final LocaleList preferredLocales = LocaleList.forLanguageTags("fr,en-US,ja-JP");

        final ArrayList<Locale> dest = new ArrayList<>();
        LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
        assertEquals(0, dest.size());
    }

    @Test
    public void testFilterByLanguageNullAvailableLocales() throws Exception {
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(null);
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(0, dest.size());
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(null);
            availableLocales.add(null);
            availableLocales.add(null);
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(0, dest.size());
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(null);
            availableLocales.add(Locale.forLanguageTag("en-US"));
            availableLocales.add(null);
            availableLocales.add(null);
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "en-US"
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(null);
            availableLocales.add(Locale.forLanguageTag("en"));
            availableLocales.add(null);
            availableLocales.add(null);
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "en"
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(null);
            availableLocales.add(Locale.forLanguageTag("ja-JP"));
            availableLocales.add(null);
            availableLocales.add(null);
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(0, dest.size());
        }
    }

    @Test
    public void testFilterByLanguage() throws Exception {
        {
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("en-US"));
            availableLocales.add(Locale.forLanguageTag("fr-CA"));
            availableLocales.add(Locale.forLanguageTag("in"));
            availableLocales.add(Locale.forLanguageTag("ja"));
            availableLocales.add(Locale.forLanguageTag("fil"));

            final LocaleList preferredLocales = LocaleList.forLanguageTags("fr,en-US,ja-JP");

            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(3, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "fr-CA"
            assertEquals(availableLocales.get(0), dest.get(1));  // "en-US"
            assertEquals(availableLocales.get(3), dest.get(2));  // "ja"
        }
        {
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("en-US"));
            availableLocales.add(Locale.forLanguageTag("en-GB"));
            availableLocales.add(Locale.forLanguageTag("en-IN"));

            final LocaleList preferredLocales = LocaleList.forLanguageTags("en-US");

            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(0), dest.get(0));  // "en-US"
        }
    }

    @Test
    public void testFilterByLanguageTheSameLanguage() throws Exception {
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("fr-CA"));
            availableLocales.add(Locale.forLanguageTag("en-US"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "en-US"
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("fr-CA"));
            availableLocales.add(Locale.forLanguageTag("en"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "en"
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("fr-CA"));
            availableLocales.add(Locale.forLanguageTag("en-CA"));
            availableLocales.add(Locale.forLanguageTag("en-IN"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(2), dest.get(0));  // "en-IN"
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("fr-CA"));
            availableLocales.add(Locale.forLanguageTag("en-CA"));
            availableLocales.add(Locale.forLanguageTag("en-NZ"));
            availableLocales.add(Locale.forLanguageTag("en-BZ"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "en-CA"
        }
    }

    @Test
    public void testFilterByLanguageFallbackRules() throws Exception {
        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("sr-Latn-RS");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-BA"));
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-CS"));
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-ME"));
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-RS"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-BA"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-CS"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-ME"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-RS"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(7), dest.get(0));  // "sr-Latn-RS"
        }
        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("sr-Latn-RS-x-android");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-BA"));
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-CS"));
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-ME"));
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-RS"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-BA"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-CS"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-ME"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-RS"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(7), dest.get(0));  // "sr-Latn-RS"
        }
        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("sr-Latn-RS");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-BA-x-android"));
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-CS-x-android"));
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-ME-x-android"));
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-RS-x-android"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-BA-x-android"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-CS-x-android"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-ME-x-android"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-RS-x-android"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(7), dest.get(0));  // "sr-Latn-RS-x-android"
        }

        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("sr-Latn-RS");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("sr"));
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(2), dest.get(0));  // "sr-Latn"
        }

        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("sr-RS");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("sr"));
            availableLocales.add(Locale.forLanguageTag("sr-RS"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(0), dest.get(0));  // "sr"
        }

        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("sr-Latn");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("sr"));
            availableLocales.add(Locale.forLanguageTag("sr-RS"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(2), dest.get(0));  // "sr-Latn"
        }

        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("sr");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("sr"));
            availableLocales.add(Locale.forLanguageTag("sr-RS"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(0), dest.get(0));  // "sr"
        }
        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("sr");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("sr-Latn"));
            availableLocales.add(Locale.forLanguageTag("sr-RS"));
            availableLocales.add(Locale.forLanguageTag("sr"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "sr-RS"
        }

        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("sr");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-RS"));
            availableLocales.add(Locale.forLanguageTag("sr-Latn-RS"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(0), dest.get(0));  // "sr-Cyrl-RS"
        }
        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("sr-Latn");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("sr-Latn-RS"));
            availableLocales.add(Locale.forLanguageTag("sr-Cyrl-RS"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(0), dest.get(0));  // "sr-Latn-RS"
        }
    }

    @Test
    public void testFilterKnownLimitation() throws Exception {
        // Following test cases are not for intentional behavior but checks for preventing the
        // behavior from becoming worse.
        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("ja-Hrkt");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("ja-Jpan"));
            availableLocales.add(Locale.forLanguageTag("ja-Hrkt"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            // Should be ja-Jpan since it supports ja-Hrkt and listed before ja-Hrkt.
            assertEquals(availableLocales.get(1), dest.get(0));
        }
        {
            final LocaleList preferredLocales = LocaleList.forLanguageTags("zh-Hani");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("zh-Hans"));
            availableLocales.add(Locale.forLanguageTag("zh-Hant"));
            availableLocales.add(Locale.forLanguageTag("zh-Hanb"));
            availableLocales.add(Locale.forLanguageTag("zh-Hani"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            // Should be zh-Hans since it supports zh-Hani. Also zh-Hant, zh-Hanb supports zh-Hani.
            assertEquals(availableLocales.get(3), dest.get(0));
        }
    }

    @Test
    public void testGetSuitableLocalesForSpellChecker() throws Exception {
        {
            final ArrayList<Locale> locales =
                    LocaleUtils.getSuitableLocalesForSpellChecker(LOCALE_EN_US);
            assertEquals(3, locales.size());
            assertEquals(LOCALE_EN_US, locales.get(0));
            assertEquals(LOCALE_EN_GB, locales.get(1));
            assertEquals(LOCALE_EN, locales.get(2));
        }

        {
            final ArrayList<Locale> locales =
                    LocaleUtils.getSuitableLocalesForSpellChecker(LOCALE_EN_GB);
            assertEquals(3, locales.size());
            assertEquals(LOCALE_EN_GB, locales.get(0));
            assertEquals(LOCALE_EN_US, locales.get(1));
            assertEquals(LOCALE_EN, locales.get(2));
        }

        {
            final ArrayList<Locale> locales =
                    LocaleUtils.getSuitableLocalesForSpellChecker(LOCALE_EN);
            assertEquals(3, locales.size());
            assertEquals(LOCALE_EN, locales.get(0));
            assertEquals(LOCALE_EN_US, locales.get(1));
            assertEquals(LOCALE_EN_GB, locales.get(2));
        }

        {
            final ArrayList<Locale> locales =
                    LocaleUtils.getSuitableLocalesForSpellChecker(LOCALE_EN_IN);
            assertEquals(4, locales.size());
            assertEquals(LOCALE_EN_IN, locales.get(0));
            assertEquals(LOCALE_EN_US, locales.get(1));
            assertEquals(LOCALE_EN_GB, locales.get(2));
            assertEquals(LOCALE_EN, locales.get(3));
        }

        {
            final ArrayList<Locale> locales =
                    LocaleUtils.getSuitableLocalesForSpellChecker(LOCALE_JA_JP);
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
                    LocaleUtils.getSuitableLocalesForSpellChecker(LOCALE_FIL_PH);
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
                    LocaleUtils.getSuitableLocalesForSpellChecker(LOCALE_TH_TH_TH);
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
                    LocaleUtils.getSuitableLocalesForSpellChecker(localeWithVariant);
            assertEquals(5, locales.size());
            assertEquals(LOCALE_JA_JP, locales.get(0));
            assertEquals(LOCALE_JA, locales.get(1));
            assertEquals(LOCALE_EN_US, locales.get(2));
            assertEquals(LOCALE_EN_GB, locales.get(3));
            assertEquals(Locale.ENGLISH, locales.get(4));
        }
    }

    @Test
    public void testConstructLocaleFromString() throws Exception {
        assertEquals(new Locale("en"), LocaleUtils.constructLocaleFromString("en"));
        assertEquals(new Locale("en", "US"), LocaleUtils.constructLocaleFromString("en_US"));
        assertEquals(new Locale("en", "US", "POSIX"),
                LocaleUtils.constructLocaleFromString("en_US_POSIX"));

        // Special rewrite rule for "tl" for versions of Android earlier than Lollipop that did not
        // support three letter language codes, and used "tl" (Tagalog) as the language string for
        // "fil" (Filipino).
        assertEquals(new Locale("fil"), LocaleUtils.constructLocaleFromString("tl"));
        assertEquals(new Locale("fil", "PH"), LocaleUtils.constructLocaleFromString("tl_PH"));
        assertEquals(new Locale("fil", "PH", "POSIX"),
                LocaleUtils.constructLocaleFromString("tl_PH_POSIX"));

        // So far rejecting an invalid/unexpected locale string is out of the scope of this method.
        assertEquals(new Locale("a"), LocaleUtils.constructLocaleFromString("a"));
        assertEquals(new Locale("a b c"), LocaleUtils.constructLocaleFromString("a b c"));
        assertEquals(new Locale("en-US"), LocaleUtils.constructLocaleFromString("en-US"));
    }
}
