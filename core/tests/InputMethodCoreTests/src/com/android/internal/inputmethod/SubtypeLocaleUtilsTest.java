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

package com.android.internal.inputmethod;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SubtypeLocaleUtilsTest {
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
    public void testConstructLocaleFromString() throws Exception {
        assertEquals(new Locale("en"), SubtypeLocaleUtils.constructLocaleFromString("en"));
        assertEquals(new Locale("en", "US"), SubtypeLocaleUtils.constructLocaleFromString("en_US"));
        assertEquals(new Locale("en", "US", "POSIX"),
                SubtypeLocaleUtils.constructLocaleFromString("en_US_POSIX"));

        // Special rewrite rule for "tl" for versions of Android earlier than Lollipop that did not
        // support three letter language codes, and used "tl" (Tagalog) as the language string for
        // "fil" (Filipino).
        assertEquals(new Locale("fil"), SubtypeLocaleUtils.constructLocaleFromString("tl"));
        assertEquals(new Locale("fil", "PH"),
                SubtypeLocaleUtils.constructLocaleFromString("tl_PH"));
        assertEquals(new Locale("fil", "PH", "POSIX"),
                SubtypeLocaleUtils.constructLocaleFromString("tl_PH_POSIX"));

        // So far rejecting an invalid/unexpected locale string is out of the scope of this method.
        assertEquals(new Locale("a"), SubtypeLocaleUtils.constructLocaleFromString("a"));
        assertEquals(new Locale("a b c"), SubtypeLocaleUtils.constructLocaleFromString("a b c"));
        assertEquals(new Locale("en-US"), SubtypeLocaleUtils.constructLocaleFromString("en-US"));
    }
}
