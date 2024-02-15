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

import static com.google.common.truth.Truth.assertThat;

import android.util.ArrayMap;
import android.view.inputmethod.InputMethodSubtype;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.List;
public final class AdditionalSubtypeMapTest {

    private static final String TEST_IME1_ID = "com.android.test.inputmethod/.TestIme1";
    private static final String TEST_IME2_ID = "com.android.test.inputmethod/.TestIme2";

    private static InputMethodSubtype createTestSubtype(String locale) {
        return new InputMethodSubtype
                .InputMethodSubtypeBuilder()
                .setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeLocale(locale)
                .setIsAsciiCapable(true)
                .build();
    }

    private static final InputMethodSubtype TEST_SUBTYPE_EN_US = createTestSubtype("en_US");
    private static final InputMethodSubtype TEST_SUBTYPE_JA_JP = createTestSubtype("ja_JP");

    private static final List<InputMethodSubtype> TEST_SUBTYPE_LIST1 = List.of(TEST_SUBTYPE_EN_US);
    private static final List<InputMethodSubtype> TEST_SUBTYPE_LIST2 = List.of(TEST_SUBTYPE_JA_JP);

    private static ArrayMap<String, List<InputMethodSubtype>> mapOf(
            @NonNull String key1, @NonNull List<InputMethodSubtype> value1) {
        final ArrayMap<String, List<InputMethodSubtype>> map = new ArrayMap<>();
        map.put(key1, value1);
        return map;
    }

    private static ArrayMap<String, List<InputMethodSubtype>> mapOf(
            @NonNull String key1, @NonNull List<InputMethodSubtype> value1,
            @NonNull String key2, @NonNull List<InputMethodSubtype> value2) {
        final ArrayMap<String, List<InputMethodSubtype>> map = new ArrayMap<>();
        map.put(key1, value1);
        map.put(key2, value2);
        return map;
    }

    @Test
    public void testOfReturnsEmptyInstance() {
        assertThat(AdditionalSubtypeMap.of(new ArrayMap<>()))
                .isSameInstanceAs(AdditionalSubtypeMap.EMPTY_MAP);
    }

    @Test
    public void testOfReturnsNewInstance() {
        final AdditionalSubtypeMap instance = AdditionalSubtypeMap.of(
                mapOf(TEST_IME1_ID, TEST_SUBTYPE_LIST1));
        assertThat(instance.keySet()).containsExactly(TEST_IME1_ID);
        assertThat(instance.get(TEST_IME1_ID)).containsExactlyElementsIn(TEST_SUBTYPE_LIST1);
    }

    @Test
    public void testCloneWithRemoveOrSelfReturnsEmptyInstance() {
        final AdditionalSubtypeMap original = AdditionalSubtypeMap.of(
                mapOf(TEST_IME1_ID, TEST_SUBTYPE_LIST1));
        final AdditionalSubtypeMap result = original.cloneWithRemoveOrSelf(TEST_IME1_ID);
        assertThat(result).isSameInstanceAs(AdditionalSubtypeMap.EMPTY_MAP);
    }

    @Test
    public void testCloneWithRemoveOrSelfWithMultipleKeysReturnsEmptyInstance() {
        final AdditionalSubtypeMap original = AdditionalSubtypeMap.of(
                mapOf(TEST_IME1_ID, TEST_SUBTYPE_LIST1, TEST_IME2_ID, TEST_SUBTYPE_LIST2));
        final AdditionalSubtypeMap result = original.cloneWithRemoveOrSelf(
                List.of(TEST_IME1_ID, TEST_IME2_ID));
        assertThat(result).isSameInstanceAs(AdditionalSubtypeMap.EMPTY_MAP);
    }

    @Test
    public void testCloneWithRemoveOrSelfReturnsNewInstance() {
        final AdditionalSubtypeMap original = AdditionalSubtypeMap.of(
                mapOf(TEST_IME1_ID, TEST_SUBTYPE_LIST1, TEST_IME2_ID, TEST_SUBTYPE_LIST2));
        final AdditionalSubtypeMap result = original.cloneWithRemoveOrSelf(TEST_IME1_ID);
        assertThat(result.keySet()).containsExactly(TEST_IME2_ID);
        assertThat(result.get(TEST_IME2_ID)).containsExactlyElementsIn(TEST_SUBTYPE_LIST2);
    }

    @Test
    public void testCloneWithPutWithNewKey() {
        final AdditionalSubtypeMap original = AdditionalSubtypeMap.of(
                mapOf(TEST_IME1_ID, TEST_SUBTYPE_LIST1));
        final AdditionalSubtypeMap result = original.cloneWithPut(TEST_IME2_ID, TEST_SUBTYPE_LIST2);
        assertThat(result.keySet()).containsExactly(TEST_IME1_ID, TEST_IME2_ID);
        assertThat(result.get(TEST_IME1_ID)).containsExactlyElementsIn(TEST_SUBTYPE_LIST1);
        assertThat(result.get(TEST_IME2_ID)).containsExactlyElementsIn(TEST_SUBTYPE_LIST2);
    }

    @Test
    public void testCloneWithPutWithExistingKey() {
        final AdditionalSubtypeMap original = AdditionalSubtypeMap.of(
                mapOf(TEST_IME1_ID, TEST_SUBTYPE_LIST1, TEST_IME2_ID, TEST_SUBTYPE_LIST2));
        final AdditionalSubtypeMap result = original.cloneWithPut(TEST_IME2_ID, TEST_SUBTYPE_LIST1);
        assertThat(result.keySet()).containsExactly(TEST_IME1_ID, TEST_IME2_ID);
        assertThat(result.get(TEST_IME1_ID)).containsExactlyElementsIn(TEST_SUBTYPE_LIST1);
        assertThat(result.get(TEST_IME2_ID)).containsExactlyElementsIn(TEST_SUBTYPE_LIST1);
    }
}
