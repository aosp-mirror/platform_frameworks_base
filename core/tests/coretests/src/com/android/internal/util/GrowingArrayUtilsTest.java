/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.util;

import static com.android.internal.util.GrowingArrayUtils.append;
import static com.android.internal.util.GrowingArrayUtils.insert;

import static org.junit.Assert.assertArrayEquals;

import android.util.EmptyArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class GrowingArrayUtilsTest {
    private final Object TEST_OBJECT = new Object();

    @Test
    public void testAppend_Object() {
        assertArrayEqualsPrefix(new Object[]{TEST_OBJECT},
                append(EmptyArray.OBJECT, 0, TEST_OBJECT));
        assertArrayEqualsPrefix(new Object[]{TEST_OBJECT, TEST_OBJECT},
                append(new Object[]{TEST_OBJECT}, 1, TEST_OBJECT));
        assertArrayEqualsPrefix(new Object[]{TEST_OBJECT},
                append(new Object[]{null, null}, 0, TEST_OBJECT));
    }

    @Test
    public void testInsert_Object() {
        assertArrayEqualsPrefix(new Object[]{TEST_OBJECT},
                insert(EmptyArray.OBJECT, 0, 0, TEST_OBJECT));
        assertArrayEqualsPrefix(new Object[]{null, TEST_OBJECT},
                insert(new Object[]{TEST_OBJECT}, 1, 0, null));
        assertArrayEqualsPrefix(new Object[]{TEST_OBJECT, null},
                insert(new Object[]{TEST_OBJECT}, 1, 1, null));
        assertArrayEqualsPrefix(new Object[]{TEST_OBJECT, null, TEST_OBJECT},
                insert(new Object[]{TEST_OBJECT, TEST_OBJECT}, 2, 1, null));
    }

    @Test
    public void testAppend_Int() {
        assertArrayEqualsPrefix(new int[]{42},
                append(EmptyArray.INT, 0, 42));
        assertArrayEqualsPrefix(new int[]{42, 42},
                append(new int[]{42}, 1, 42));
        assertArrayEqualsPrefix(new int[]{42},
                append(new int[]{0, 0}, 0, 42));
    }

    @Test
    public void testInsert_Int() {
        assertArrayEqualsPrefix(new int[]{42},
                insert(EmptyArray.INT, 0, 0, 42));
        assertArrayEqualsPrefix(new int[]{21, 42},
                insert(new int[]{42}, 1, 0, 21));
        assertArrayEqualsPrefix(new int[]{42, 21},
                insert(new int[]{42}, 1, 1, 21));
        assertArrayEqualsPrefix(new int[]{42, 21, 43},
                insert(new int[]{42, 43}, 2, 1, 21));
    }

    @Test
    public void testAppend_Long() {
        assertArrayEqualsPrefix(new long[]{42},
                append(EmptyArray.LONG, 0, 42));
        assertArrayEqualsPrefix(new long[]{42, 42},
                append(new long[]{42}, 1, 42));
        assertArrayEqualsPrefix(new long[]{42},
                append(new long[]{0, 0}, 0, 42));
    }

    @Test
    public void testInsert_Long() {
        assertArrayEqualsPrefix(new long[]{42},
                insert(EmptyArray.LONG, 0, 0, 42));
        assertArrayEqualsPrefix(new long[]{21, 42},
                insert(new long[]{42}, 1, 0, 21));
        assertArrayEqualsPrefix(new long[]{42, 21},
                insert(new long[]{42}, 1, 1, 21));
        assertArrayEqualsPrefix(new long[]{42, 21, 43},
                insert(new long[]{42, 43}, 2, 1, 21));
    }

    @Test
    public void testAppend_Boolean() {
        assertArrayEqualsPrefix(new boolean[]{true},
                append(EmptyArray.BOOLEAN, 0, true));
        assertArrayEqualsPrefix(new boolean[]{true, true},
                append(new boolean[]{true}, 1, true));
        assertArrayEqualsPrefix(new boolean[]{true},
                append(new boolean[]{false, false}, 0, true));
    }

    @Test
    public void testInsert_Boolean() {
        assertArrayEqualsPrefix(new boolean[]{true},
                insert(EmptyArray.BOOLEAN, 0, 0, true));
        assertArrayEqualsPrefix(new boolean[]{false, true},
                insert(new boolean[]{true}, 1, 0, false));
        assertArrayEqualsPrefix(new boolean[]{true, false},
                insert(new boolean[]{true}, 1, 1, false));
        assertArrayEqualsPrefix(new boolean[]{true, false, true},
                insert(new boolean[]{true, true}, 2, 1, false));
    }

    private <T> void assertArrayEqualsPrefix(T[] expected, T[] actual) {
        assertArrayEquals(expected, Arrays.copyOf(actual, expected.length));
    }

    private void assertArrayEqualsPrefix(int[] expected, int[] actual) {
        assertArrayEquals(expected, Arrays.copyOf(actual, expected.length));
    }

    private void assertArrayEqualsPrefix(long[] expected, long[] actual) {
        assertArrayEquals(expected, Arrays.copyOf(actual, expected.length));
    }

    private void assertArrayEqualsPrefix(boolean[] expected, boolean[] actual) {
        assertArrayEquals(expected, Arrays.copyOf(actual, expected.length));
    }
}
