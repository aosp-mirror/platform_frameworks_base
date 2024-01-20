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

package com.android.server.inputmethod;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.os.LocaleList;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocaleUtilsTest {

    private static final LocaleUtils.LocaleExtractor<Locale> sIdentityMapper = source -> source;

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
    public void testGetLanguageFromLocaleString() {
        assertThat(LocaleUtils.getLanguageFromLocaleString("en")).isEqualTo("en");
        assertThat(LocaleUtils.getLanguageFromLocaleString("en_US")).isEqualTo("en");
    }
}
