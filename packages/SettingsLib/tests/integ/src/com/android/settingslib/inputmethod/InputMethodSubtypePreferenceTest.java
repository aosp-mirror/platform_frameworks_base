/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.inputmethod;

import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputMethodSubtypePreferenceTest {

    private static final List<InputMethodSubtypePreference> ITEMS_IN_ASCENDING = Arrays.asList(
            // Subtypes that has the same locale of the system's.
            createPreference("", "en_US", Locale.US),
            createPreference("E", "en_US", Locale.US),
            createPreference("Z", "en_US", Locale.US),
            // Subtypes that has the same language of the system's.
            createPreference("", "en", Locale.US),
            createPreference("E", "en", Locale.US),
            createPreference("Z", "en", Locale.US),
            // Subtypes that has different language than the system's.
            createPreference("", "ja", Locale.US),
            createPreference("A", "hi_IN", Locale.US),
            createPreference("B", "", Locale.US),
            createPreference("E", "ja", Locale.US),
            createPreference("Z", "ja", Locale.US)
    );
    private static final List<InputMethodSubtypePreference> SAME_ORDER_ITEMS = Arrays.asList(
            // Subtypes that has different language than the system's.
            createPreference("A", "ja_JP", Locale.US),
            createPreference("A", "hi_IN", Locale.US),
            // Subtypes that has an empty subtype locale string.
            createPreference("A", "", Locale.US)
    );
    private static final Collator COLLATOR = Collator.getInstance(Locale.US);

    @Test
    public void testComparableOrdering() throws Exception {
        ComparableUtils.assertAscendingOrdering(
                ITEMS_IN_ASCENDING,
                (x, y) -> x.compareTo(y, COLLATOR),
                InputMethodSubtypePreference::getKey);
    }

    @Test
    public void testComparableEquality() {
        ComparableUtils.assertSameOrdering(
                SAME_ORDER_ITEMS,
                (x, y) -> x.compareTo(y, COLLATOR),
                InputMethodSubtypePreference::getKey);
    }

    @Test
    public void testComparableContracts() {
        final Collection<InputMethodSubtypePreference> items = new ArrayList<>();
        items.addAll(ITEMS_IN_ASCENDING);
        items.addAll(SAME_ORDER_ITEMS);
        items.add(createPreference("", "", Locale.US));
        items.add(createPreference("A", "en", Locale.US));
        items.add(createPreference("A", "en_US", Locale.US));
        items.add(createPreference("E", "hi_IN", Locale.US));
        items.add(createPreference("E", "en", Locale.US));
        items.add(createPreference("Z", "en_US", Locale.US));

        ComparableUtils.assertComparableContracts(
                items,
                (x, y) -> x.compareTo(y, COLLATOR),
                InputMethodSubtypePreference::getKey);
    }

    private static InputMethodSubtypePreference createPreference(
            final String subtypeName,
            final String subtypeLocaleString,
            final Locale systemLocale) {
        final String key = subtypeName + "-" + subtypeLocaleString + "-" + systemLocale;
        final String subtypeLanguageTag = subtypeLocaleString.replace('_', '-');
        final Locale subtypeLocale = TextUtils.isEmpty(subtypeLanguageTag)
                ? null : Locale.forLanguageTag(subtypeLanguageTag);
        return new InputMethodSubtypePreference(
                InstrumentationRegistry.getTargetContext(),
                key,
                subtypeName,
                subtypeLocale,
                systemLocale);
    }
}
