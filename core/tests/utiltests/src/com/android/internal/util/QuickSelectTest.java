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

import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link QuickSelect}.
 */
@RunWith(AndroidJUnit4.class)
public class QuickSelectTest {

    @Test
    public void testQuickSelect() throws Exception {
        test((List<Integer>) null, 0, null);
        test(Arrays.asList(), -1, null);
        test(Arrays.asList(), 0, null);
        test(Arrays.asList(), 1, null);
        test(Arrays.asList(1), -1, 1, 0, null);
        test(Arrays.asList(1), 1, -1, 0, null);
        test(Arrays.asList(1), 0, 1, -1, null);
        test(Arrays.asList(1), 1, 1, 0, null);
        test(Arrays.asList(1), 0, 1);
        test(Arrays.asList(1), 1, null);
        test(Arrays.asList(1, 2, 3, 4, 5), 0, 1);
        test(Arrays.asList(1, 2, 3, 4, 5), 1, 2);
        test(Arrays.asList(1, 2, 3, 4, 5), 2, 3);
        test(Arrays.asList(1, 2, 3, 4, 5), 3, 4);
        test(Arrays.asList(1, 2, 3, 4, 5), 4, 5);
        test(Arrays.asList(1, 2, 3, 4, 5), 5, null);
        test(Arrays.asList(7, 10, 4, 3, 20, 15, 8, 9), 2, 7);
        test(Arrays.asList(7, 10, 4, 3, 20, 15, 8, 9), 4, 9);
        test(Arrays.asList(7, 10, 4, 3, 20, 15, 8, 9), 7, 20);
        test(Arrays.asList(7, 10, 4, 3, 20, 15, 8, 9), 8, null);
        test(Arrays.asList(7, 10, 4, 3, 20, 15, 8, 9), 1, 3, 0, 3);
        test(Arrays.asList(7, 10, 4, 3, 20, 15, 8, 9), 1, 3, 1, 4);
        test(Arrays.asList(7, 10, 4, 3, 20, 15, 8, 9), 1, 3, 2, 10);
        test(Arrays.asList(7, 10, 4, 3, 20, 15, 8, 9), 1, 3, 3, null);

        test((int[]) null, 0, null);
        test(new int[0], -1, null);
        test(new int[0], 0, null);
        test(new int[0], 1, null);
        test(new int[] {1}, -1, 1, 0, null);
        test(new int[] {1}, 1, -1, 0, null);
        test(new int[] {1}, 1, 0, -1, null);
        test(new int[] {1}, 1, 1, 0, null);
        test(new int[] {1}, 0, 1);
        test(new int[] {1}, 1, null);
        test(new int[] {1, 2, 3, 4, 5}, 0, 1);
        test(new int[] {1, 2, 3, 4, 5}, 1, 2);
        test(new int[] {1, 2, 3, 4, 5}, 2, 3);
        test(new int[] {1, 2, 3, 4, 5}, 3, 4);
        test(new int[] {1, 2, 3, 4, 5}, 4, 5);
        test(new int[] {1, 2, 3, 4, 5}, 5, null);
        test(new int[] {7, 10, 4, 3, 20, 15, 8, 9}, 2, 7);
        test(new int[] {7, 10, 4, 3, 20, 15, 8, 9}, 4, 9);
        test(new int[] {7, 10, 4, 3, 20, 15, 8, 9}, 7, 20);
        test(new int[] {7, 10, 4, 3, 20, 15, 8, 9}, 8, null);
        test(new int[] {7, 10, 4, 3, 20, 15, 8, 9}, 1, 3, 0, 3);
        test(new int[] {7, 10, 4, 3, 20, 15, 8, 9}, 1, 3, 1, 4);
        test(new int[] {7, 10, 4, 3, 20, 15, 8, 9}, 1, 3, 2, 10);
        test(new int[] {7, 10, 4, 3, 20, 15, 8, 9}, 1, 3, 3, null);

        test((long[]) null, 0, null);
        test(new long[0], -1, null);
        test(new long[0], 0, null);
        test(new long[0], 1, null);
        test(new long[] {1}, -1, 1, 0, null);
        test(new long[] {1}, 1, -1, 0, null);
        test(new long[] {1}, 1, 0, -1, null);
        test(new long[] {1}, 1, 1, 0, null);
        test(new long[] {1}, 0, 1L);
        test(new long[] {1}, 1, null);
        test(new long[] {1, 2, 3, 4, 5}, 0, 1L);
        test(new long[] {1, 2, 3, 4, 5}, 1, 2L);
        test(new long[] {1, 2, 3, 4, 5}, 2, 3L);
        test(new long[] {1, 2, 3, 4, 5}, 3, 4L);
        test(new long[] {1, 2, 3, 4, 5}, 4, 5L);
        test(new long[] {1, 2, 3, 4, 5}, 5, null);
        test(new long[] {7, 10, 4, 3, 20, 15, 8, 9}, 2, 7L);
        test(new long[] {7, 10, 4, 3, 20, 15, 8, 9}, 4, 9L);
        test(new long[] {7, 10, 4, 3, 20, 15, 8, 9}, 7, 20L);
        test(new long[] {7, 10, 4, 3, 20, 15, 8, 9}, 8, null);
        test(new long[] {7, 10, 4, 3, 20, 15, 8, 9}, 1, 3, 0, 3L);
        test(new long[] {7, 10, 4, 3, 20, 15, 8, 9}, 1, 3, 1, 4L);
        test(new long[] {7, 10, 4, 3, 20, 15, 8, 9}, 1, 3, 2, 10L);
        test(new long[] {7, 10, 4, 3, 20, 15, 8, 9}, 1, 3, 3, null);
    }

    private void test(List<Integer> input, int k, Integer expected) throws Exception {
        test(input, 0, input == null ? 0 : input.size(), k, expected);
    }

    private void test(List<Integer> input, int start, int length, int k, Integer expected)
            throws Exception {
        try {
            final Integer result = QuickSelect.select(input, start, length, k, Integer::compare);
            assertEquals(expected, result);
        } catch (IllegalArgumentException e) {
            if (expected != null) {
                throw new Exception(e);
            }
        }
    }

    private void test(int[] input, int k, Integer expected) throws Exception {
        test(input, 0, input == null ? 0 : input.length, k, expected);
    }

    private void test(int[] input, int start, int length, int k, Integer expected)
            throws Exception {
        try {
            final int result = QuickSelect.select(input, start, length, k);
            assertEquals((int) expected, result);
        } catch (IllegalArgumentException e) {
            if (expected != null) {
                throw new Exception(e);
            }
        }
    }

    private void test(long[] input, int k, Long expected) throws Exception {
        test(input, 0, input == null ? 0 : input.length, k, expected);
    }

    private void test(long[] input, int start, int length, int k, Long expected) throws Exception {
        try {
            final long result = QuickSelect.select(input, start, length, k);
            assertEquals((long) expected, result);
        } catch (IllegalArgumentException e) {
            if (expected != null) {
                throw new Exception(e);
            }
        }
    }
}
