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
import static org.junit.Assert.assertTrue;

import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.LocaleStore.LocaleInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Set;

/**
 * Unit tests for the {@link LocaleStore}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocaleStoreTest {
    @Before
    public void setUp() {
    }

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
}
