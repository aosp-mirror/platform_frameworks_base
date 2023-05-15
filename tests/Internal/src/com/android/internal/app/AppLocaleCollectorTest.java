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

import static com.android.internal.app.AppLocaleStore.AppLocaleResult.LocaleStatus.GET_SUPPORTED_LANGUAGE_FROM_LOCAL_CONFIG;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.os.LocaleList;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.LocaleStore.LocaleInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Unit tests for the {@link AppLocaleCollector}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppLocaleCollectorTest {
    private static final String TAG = "AppLocaleCollectorTest";
    private AppLocaleCollector mAppLocaleCollector;
    private LocaleStore.LocaleInfo mAppCurrentLocale;
    private Set<LocaleInfo> mAllAppActiveLocales;
    private Set<LocaleInfo> mImeLocales;
    private List<LocaleInfo> mSystemCurrentLocales;
    private Set<LocaleInfo> mSystemSupportedLocales;
    private AppLocaleStore.AppLocaleResult mResult;
    private static final String PKG1 = "pkg1";
    private static final int NONE = LocaleInfo.SUGGESTION_TYPE_NONE;
    private static final int SIM = LocaleInfo.SUGGESTION_TYPE_SIM;
    private static final int CFG = LocaleInfo.SUGGESTION_TYPE_CFG;
    private static final int SIM_CFG = SIM | CFG;
    private static final int CURRENT = LocaleInfo.SUGGESTION_TYPE_CURRENT;
    private static final int SYSTEM = LocaleInfo.SUGGESTION_TYPE_SYSTEM_LANGUAGE;
    private static final int OTHERAPP = LocaleInfo.SUGGESTION_TYPE_OTHER_APP_LANGUAGE;
    private static final int IME = LocaleInfo.SUGGESTION_TYPE_IME_LANGUAGE;
    private static final int SYSTEM_AVAILABLE =
            LocaleInfo.SUGGESTION_TYPE_SYSTEM_AVAILABLE_LANGUAGE;

    @Before
    public void setUp() throws Exception {
        mAppLocaleCollector = spy(
                new AppLocaleCollector(InstrumentationRegistry.getContext(), PKG1));

        mAppCurrentLocale = createLocaleInfo("en-US", CURRENT);
        mAllAppActiveLocales = initAllAppActivatedLocales();
        mImeLocales = initImeLocales();
        mSystemSupportedLocales = initSystemSupportedLocales();
        mSystemCurrentLocales = initSystemCurrentLocales();
        mResult = new AppLocaleStore.AppLocaleResult(GET_SUPPORTED_LANGUAGE_FROM_LOCAL_CONFIG,
                initAppSupportedLocale());
    }

    @Test
    public void testGetSystemCurrentLocales() {
        LocaleList.setDefault(
                LocaleList.forLanguageTags("en-US-u-mu-fahrenhe,ar-JO-u-mu-fahrenhe-nu-latn"));

        List<LocaleStore.LocaleInfo> list =
                mAppLocaleCollector.getSystemCurrentLocales();

        LocaleList expected = LocaleList.forLanguageTags("en-US,ar-JO-u-nu-latn");
        assertEquals(list.size(), expected.size());
        for (LocaleStore.LocaleInfo info : list) {
            assertTrue(expected.indexOf(info.getLocale()) != -1);
        }
    }

    @Test
    public void testGetSupportedLocaleList() {
        doReturn(mAppCurrentLocale).when(mAppLocaleCollector).getAppCurrentLocale();
        doReturn(mResult).when(mAppLocaleCollector).getAppSupportedLocales();
        doReturn(mAllAppActiveLocales).when(mAppLocaleCollector).getAllAppActiveLocales();
        doReturn(mImeLocales).when(mAppLocaleCollector).getActiveImeLocales();
        doReturn(mSystemSupportedLocales).when(mAppLocaleCollector).getSystemSupportedLocale(
                anyObject(), eq(null), eq(true));
        doReturn(mSystemCurrentLocales).when(
                mAppLocaleCollector).getSystemCurrentLocales();

        Set<LocaleInfo> result = mAppLocaleCollector.getSupportedLocaleList(null, true, false);

        HashMap<String, Integer> expectedResult = getExpectedResult();
        assertEquals(result.size(), expectedResult.size());
        for (LocaleInfo source : result) {
            int suggestionFlags = expectedResult.getOrDefault(source.getId(), -1);
            assertEquals(source.mSuggestionFlags, suggestionFlags);
        }
    }

    private HashMap<String, Integer> getExpectedResult() {
        HashMap<String, Integer> map = new HashMap<>();
        map.put("en-US", CURRENT); // The locale current App activates.
        map.put("fr", NONE); // The locale App and system support.
        map.put("zu", NONE); // The locale App and system support.
        map.put("en", NONE); // Use en because System supports en while APP supports en-CA, en-GB.
        map.put("ko", NONE); // The locale App and system support.
        map.put("en-AU", OTHERAPP); // The locale other App activates and current App supports.
        map.put("en-CA", OTHERAPP); // The locale other App activates and current App supports.
        map.put("en-IN", IME); // The locale IME supports.
        map.put("ja-JP",
                OTHERAPP | SYSTEM_AVAILABLE | IME); // The locale exists in OTHERAPP, SYSTEM and IME
        map.put("zh-Hant-TW", SYSTEM_AVAILABLE);  // The locale system activates.
        map.put(createLocaleInfo("", SYSTEM).getId(), SYSTEM); // System language title
        return map;
    }

    private Set<LocaleInfo> initSystemSupportedLocales() {
        return Set.of(
                createLocaleInfo("en", NONE),
                createLocaleInfo("fr", NONE),
                createLocaleInfo("zu", NONE),
                createLocaleInfo("ko", NONE),
                // will be filtered because current App doesn't support.
                createLocaleInfo("es-US", SIM_CFG)
        );
    }

    private List<LocaleInfo> initSystemCurrentLocales() {
        return List.of(createLocaleInfo("zh-Hant-TW", SYSTEM_AVAILABLE),
                createLocaleInfo("ja-JP", SYSTEM_AVAILABLE),
                // will be filtered because current App activates this locale.
                createLocaleInfo("en-US", SYSTEM_AVAILABLE));
    }

    private Set<LocaleInfo> initAllAppActivatedLocales() {
        return Set.of(
                createLocaleInfo("en-CA", OTHERAPP),
                createLocaleInfo("en-AU", OTHERAPP),
                createLocaleInfo("ja-JP", OTHERAPP),
                // will be filtered because current App activates this locale.
                createLocaleInfo("en-US", OTHERAPP));
    }

    private Set<LocaleInfo> initImeLocales() {
        return Set.of(
                // will be filtered because system activates zh-Hant-TW.
                createLocaleInfo("zh-TW", IME),
                // will be filtered because current App's activats this locale.
                createLocaleInfo("en-US", IME),
                createLocaleInfo("ja-JP", IME),
                createLocaleInfo("en-IN", IME));
    }

    private HashSet<Locale> initAppSupportedLocale() {
        HashSet<Locale> hs = new HashSet();
        hs.add(Locale.forLanguageTag("en-US"));
        hs.add(Locale.forLanguageTag("en-CA"));
        hs.add(Locale.forLanguageTag("en-GB"));
        hs.add(Locale.forLanguageTag("zh-TW"));
        hs.add(Locale.forLanguageTag("ja"));
        hs.add(Locale.forLanguageTag("fr"));
        hs.add(Locale.forLanguageTag("zu"));
        hs.add(Locale.forLanguageTag("ko"));
        // will be filtered because it's not in the system language.
        hs.add(Locale.forLanguageTag("mn"));
        return hs;
    }

    private LocaleInfo createLocaleInfo(String languageTag, int suggestionFlag) {
        LocaleInfo localeInfo = LocaleStore.fromLocale(Locale.forLanguageTag(languageTag));
        localeInfo.mSuggestionFlags = suggestionFlag;
        localeInfo.setTranslated(true);
        return localeInfo;
    }
}
