/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.LocaleList;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.LocaleStore.LocaleInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Unit tests for the {@link LocaleStore}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocaleStoreTest {
    @Test
    public void testTransformImeLanguageTagToLocaleInfo() {
        List<InputMethodSubtype> list = List.of(
                new InputMethodSubtypeBuilder().setLanguageTag("en-US").build(),
                new InputMethodSubtypeBuilder().setLanguageTag("zh-TW").build(),
                new InputMethodSubtypeBuilder().setLanguageTag("ja-JP").build());

        Set<LocaleInfo> localeSet = LocaleStore.transformImeLanguageTagToLocaleInfo(list);

        Set<String> expectedLanguageTag = Set.of("en-US", "zh-TW", "ja-JP");
        assertEquals(localeSet.size(), expectedLanguageTag.size());
        for (LocaleInfo info : localeSet) {
            assertEquals(info.mSuggestionFlags, LocaleInfo.SUGGESTION_TYPE_OTHER_APP_LANGUAGE);
            assertTrue(expectedLanguageTag.contains(info.getId()));
        }
    }

    @Test
    public void convertExplicitLocales_noExplicitLcoales_returnEmptyHashMap() {
        Collection<LocaleInfo> supportedLocale = getFakeSupportedLocales();

        HashMap<String, LocaleInfo> result =
                LocaleStore.convertExplicitLocales(
                        LocaleList.getEmptyLocaleList(), supportedLocale);

        assertTrue(result.isEmpty());
    }

    @Test
    public void convertExplicitLocales_hasEmptyLocale_receiveException() {
        Locale[] locales = {Locale.forLanguageTag(""), Locale.forLanguageTag("en-US")};
        LocaleList localelist = new LocaleList(locales);
        Collection<LocaleInfo> supportedLocale = getFakeSupportedLocales();

        boolean isReceiveException = false;
        try {
            LocaleStore.convertExplicitLocales(localelist, supportedLocale);
        } catch (IllformedLocaleException e) {
            isReceiveException = true;
        }

        assertTrue(isReceiveException);
    }

    @Test
    public void convertExplicitLocales_hasSameLocale_returnNonSameLocales() {
        LocaleList locales = LocaleList.forLanguageTags("en-US,en-US");
        Collection<LocaleInfo> supportedLocale = getFakeSupportedLocales();

        HashMap<String, LocaleInfo> result =
                LocaleStore.convertExplicitLocales(locales, supportedLocale);

        // Only has "en" and "en-US".
        assertTrue(result.size() == 2);
    }

    @Test
    public void convertExplicitLocales_hasEnUs_resultHasParentEn() {
        LocaleList locales = LocaleList.forLanguageTags("en-US,ja-JP");
        Collection<LocaleInfo> supportedLocale = getFakeSupportedLocales();

        HashMap<String, LocaleInfo> result =
                LocaleStore.convertExplicitLocales(locales, supportedLocale);

        assertEquals(result.get("en").getId(), "en");
    }

    @Test
    public void convertExplicitLocales_hasZhTw_resultZhHantTw() {
        LocaleList locales = LocaleList.forLanguageTags("zh-TW,en-US,en");
        Collection<LocaleInfo> supportedLocale = getFakeSupportedLocales();

        HashMap<String, LocaleInfo> result =
                LocaleStore.convertExplicitLocales(locales, supportedLocale);

        assertEquals("zh-Hant-TW", result.get("zh-Hant-TW").getId());
    }

    @Test
    public void convertExplicitLocales_nonRegularFormat_resultEmptyContry() {
        LocaleList locales = LocaleList.forLanguageTags("de-1996,de-1901");
        Collection<LocaleInfo> supportedLocale = getFakeSupportedLocales();

        HashMap<String, LocaleInfo> result =
                LocaleStore.convertExplicitLocales(locales, supportedLocale);

        assertEquals("de-1996", result.get("de-1996").getId());
        assertTrue(result.get("de-1996").getLocale().getCountry().isEmpty());
    }

    @Test
    public void convertExplicitLocales_differentEnFormat() {
        LocaleList locales = LocaleList.forLanguageTags("en-Latn-US,en-US,en");
        Collection<LocaleInfo> supportedLocale = getFakeSupportedLocales();

        HashMap<String, LocaleInfo> result =
                LocaleStore.convertExplicitLocales(locales, supportedLocale);

        assertEquals("en", result.get("en").getId());
        assertEquals("en-US", result.get("en-US").getId());
        assertNull(result.get("en-Latn-US"));
    }

    private ArrayList<LocaleInfo> getFakeSupportedLocales() {
        String[] locales = {"en-US", "zh-Hant-TW", "ja-JP", "en-GB"};
        ArrayList<LocaleInfo> supportedLocales = new ArrayList<>();
        for (String localeTag : locales) {
            supportedLocales.add(LocaleStore.fromLocale(Locale.forLanguageTag(localeTag)));
        }
        return supportedLocales;
    }
}
