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

package com.android.server.textservices;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocaleUtilsTest {
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
}
