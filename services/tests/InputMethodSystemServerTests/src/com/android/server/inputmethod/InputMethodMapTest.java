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

import static com.android.server.inputmethod.TestUtils.TEST_IME_ID1;
import static com.android.server.inputmethod.TestUtils.TEST_IME_ID2;
import static com.android.server.inputmethod.TestUtils.TEST_IME_ID3;
import static com.android.server.inputmethod.TestUtils.createFakeInputMethodInfo;
import static com.android.server.inputmethod.TestUtils.createFakeSubtypes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.ArrayMap;
import android.view.inputmethod.InputMethodInfo;

import androidx.annotation.NonNull;

import org.junit.Test;

public final class InputMethodMapTest {

    @NonNull
    private static InputMethodMap toMap(InputMethodInfo... list) {
        final ArrayMap<String, InputMethodInfo> map = new ArrayMap<>();
        for (var imi : list) {
            map.put(imi.getId(), imi);
        }
        return InputMethodMap.of(map);
    }

    @Test
    public void testAreSameSameObject() {
        final var imi1 = createFakeInputMethodInfo(TEST_IME_ID1, createFakeSubtypes(0));
        final var imi2 = createFakeInputMethodInfo(TEST_IME_ID2, createFakeSubtypes(3));
        final var map = toMap(imi1, imi2);
        assertTrue("Must return true for the same instance",
                InputMethodMap.areSame(map, map));
    }

    @Test
    public void testAreSameEquivalentObject() {
        final var imi1 = createFakeInputMethodInfo(TEST_IME_ID1, createFakeSubtypes(0));
        final var imi2 = createFakeInputMethodInfo(TEST_IME_ID2, createFakeSubtypes(3));
        assertTrue("Must return true for the equivalent instances",
                InputMethodMap.areSame(toMap(imi1, imi2), toMap(imi1, imi2)));

        assertTrue("Must return true for the equivalent instances",
                InputMethodMap.areSame(toMap(imi1, imi2), toMap(imi2, imi1)));
    }

    @Test
    public void testAreSameDifferentKeys() {
        final var imi1 = createFakeInputMethodInfo(TEST_IME_ID1, createFakeSubtypes(0));
        final var imi2 = createFakeInputMethodInfo(TEST_IME_ID2, createFakeSubtypes(3));
        final var imi3 = createFakeInputMethodInfo(TEST_IME_ID3, createFakeSubtypes(3));
        assertFalse("Must return false if keys are different",
                InputMethodMap.areSame(toMap(imi1), toMap(imi1, imi2)));
        assertFalse("Must return false if keys are different",
                InputMethodMap.areSame(toMap(imi1, imi2), toMap(imi1)));
        assertFalse("Must return false if keys are different",
                InputMethodMap.areSame(toMap(imi1, imi2), toMap(imi1, imi3)));
    }

    @Test
    public void testAreSameDifferentValues() {
        final var imi1_without_subtypes =
                createFakeInputMethodInfo(TEST_IME_ID1, createFakeSubtypes(0));
        final var imi1_with_subtypes =
                createFakeInputMethodInfo(TEST_IME_ID1, createFakeSubtypes(3));
        final var imi2 = createFakeInputMethodInfo(TEST_IME_ID2, createFakeSubtypes(3));
        assertFalse("Must return false if values are different",
                InputMethodMap.areSame(toMap(imi1_without_subtypes), toMap(imi1_with_subtypes)));
        assertFalse("Must return false if values are different",
                InputMethodMap.areSame(
                        toMap(imi1_without_subtypes, imi2),
                        toMap(imi1_with_subtypes, imi2)));
    }
}
