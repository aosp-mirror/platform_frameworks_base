/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.util.ArraySet;
import android.util.Pair;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Tests for {@link HeavyHitterSketch}.
 */
public final class HeavyHitterSketchTest extends TestCase {

    private static final float EPSILON = 0.00001f;

    /**
     * A naive counter based heavy hitter sketch, tracks every single input. To be used to validate
     * the correctness of {@link HeavyHitterSketch}.
     */
    private class CounterBased<T> {
        private final HashMap<T, Integer> mData = new HashMap<>();
        private int mTotalInput = 0;

        public void add(final T newInstance) {
            int val = mData.getOrDefault(newInstance, 0);
            mData.put(newInstance, val + 1);
            mTotalInput++;
        }

        public List<Pair<T, Float>> getTopHeavyHitters(final int k) {
            final int lower = mTotalInput / (k + 1);
            return mData.entrySet().stream()
                    .filter(e -> e.getValue() >= lower)
                    .limit(k)
                    .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                    .map((v) -> new Pair<T, Float>(v.getKey(), (float) v.getValue() / mTotalInput))
                    .collect(Collectors.toList());
        }
    }

    private List<Pair<Integer, Float>> getTopHeavyHitters(final int[] input, final int capacity) {
        final CounterBased counter = new CounterBased<Integer>();
        final HeavyHitterSketch<Integer> sketcher = HeavyHitterSketch.<Integer>newDefault();
        final float ratio = sketcher.getRequiredValidationInputRatio();
        final int total = (int) (input.length / (1 - ratio));
        sketcher.setConfig(total, capacity);
        for (int i = 0; i < input.length; i++) {
            sketcher.add(input[i]);
            counter.add(input[i]);
        }
        int validationSize = total - input.length;
        assertTrue(validationSize <= input.length);
        for (int i = 0; i < validationSize; i++) {
            sketcher.add(input[i]);
        }
        final List<Float> freqs = new ArrayList<>();
        final List<Integer> tops = sketcher.getTopHeavyHitters(capacity - 1, null, freqs);
        final List<Pair<Integer, Float>> result = new ArrayList<>();
        if (tops != null) {
            assertEquals(freqs.size(), tops.size());
            final List<Pair<Integer, Float>> cl = counter.getTopHeavyHitters(capacity - 1);
            for (int i = 0; i < tops.size(); i++) {
                final Pair<Integer, Float> pair = cl.get(i);
                assertEquals(pair.first.intValue(), tops.get(i).intValue());
                assertTrue(Math.abs(pair.second - freqs.get(i)) < EPSILON);
                result.add(new Pair<>(tops.get(i), freqs.get(i)));
            }
        } else {
            assertTrue(counter.getTopHeavyHitters(capacity - 1).isEmpty());
        }
        return result;
    }

    private List<Integer> getCandidates(final int[] input, final int capacity) {
        final HeavyHitterSketch<Integer> sketcher = HeavyHitterSketch.<Integer>newDefault();
        final float ratio = sketcher.getRequiredValidationInputRatio();
        final int total = (int) (input.length / (1 - ratio));
        sketcher.setConfig(total, capacity);
        for (int i = 0; i < input.length; i++) {
            sketcher.add(input[i]);
        }
        return sketcher.getCandidates(null);
    }

    private void verify(final int[] input, final int capacity, final int[] expected,
            final float[] freqs) throws Exception {
        final List<Integer> candidates = getCandidates(input, capacity);
        final List<Pair<Integer, Float>> result = getTopHeavyHitters(input, capacity);
        if (expected != null) {
            assertTrue(candidates != null);
            for (int i = 0; i < expected.length; i++) {
                assertTrue(candidates.contains(expected[i]));
            }
            assertTrue(result != null);
            assertEquals(expected.length, result.size());
            for (int i = 0; i < expected.length; i++) {
                final Pair<Integer, Float> pair = result.get(i);
                assertEquals(expected[i], pair.first.intValue());
                assertTrue(Math.abs(freqs[i] - pair.second) < EPSILON);
            }
        } else {
            assertEquals(null, result);
        }
    }

    private void verifyNotExpected(final int[] input, final int capacity, final int[] notExpected)
            throws Exception {
        final List<Pair<Integer, Float>> result = getTopHeavyHitters(input, capacity);
        if (result != null) {
            final ArraySet<Integer> set = new ArraySet<>();
            for (Pair<Integer, Float> p : result) {
                set.add(p.first);
            }
            for (int i = 0; i < notExpected.length; i++) {
                assertFalse(set.contains(notExpected[i]));
            }
        }
    }

    private int[] generateRandomInput(final int size, final int[] hitters) {
        final Random random = new Random();
        final Random random2 = new Random();
        final int[] input = new int[size];
        // 80% of them would be hitters, 20% will be random numbers
        final int numOfRandoms = size / 5;
        final int numOfHitters = size - numOfRandoms;
        for (int i = 0, j = 0, m = numOfRandoms, n = numOfHitters; i < size; i++) {
            int r = m > 0 && n > 0 ? random2.nextInt(size) : (m > 0 ? 0 : numOfRandoms);
            if (r < numOfRandoms) {
                input[i] = random.nextInt(size);
                m--;
            } else {
                input[i] = hitters[j++];
                if (j == hitters.length) {
                    j = 0;
                }
                n--;
            }
        }
        return input;
    }

    public void testPositive() throws Exception {
        // Simple case
        verify(new int[]{2, 9, 9, 9, 7, 6, 4, 9, 9, 9, 3, 9}, 2, new int[]{9},
                new float[]{0.583333f});

        // Two heavy hitters
        verify(new int[]{2, 3, 9, 3, 9, 3, 9, 7, 6, 4, 9, 9, 3, 9, 3, 9}, 3, new int[]{9, 3},
                new float[]{0.4375f, 0.3125f});

        // Create a random data set and insert some numbers
        final int[] input = generateRandomInput(100,
                new int[]{1001, 1002, 1002, 1003, 1003, 1003, 1004, 1004, 1004, 1004});
        verify(input, 12, new int[]{1004, 1003, 1002, 1001},
                new float[]{0.32f, 0.24f, 0.16f, 0.08f});
    }

    public void testNegative() throws Exception {
        // Simple case
        verifyNotExpected(new int[]{2, 9, 9, 9, 7, 6, 4, 9, 9, 9, 3, 9}, 2, new int[]{0, 1, 2});

        // Two heavy hitters
        verifyNotExpected(new int[]{2, 3, 9, 3, 9, 3, 9, 7, 6, 4, 9, 9, 3, 9, 3, 9}, 3,
                new int[]{0, 1, 2});

        // Create a random data set and insert some numbers
        final int[] input = generateRandomInput(100,
                new int[]{1001, 1002, 1002, 1003, 1003, 1003, 1004, 1004, 1004, 1004});
        verifyNotExpected(input, 12, new int[]{0, 1, 2, 1000, 1005});
    }

    public void testFalsePositive() throws Exception {
        // Simple case
        verifyNotExpected(new int[]{2, 9, 2, 2, 7, 6, 4, 9, 9, 9, 3, 9}, 2, new int[]{9});

        // One heavy hitter
        verifyNotExpected(new int[]{2, 3, 9, 3, 9, 3, 9, 7, 6, 4, 9, 9, 3, 9, 2, 9}, 3,
                new int[]{3});

        // Create a random data set and insert some numbers
        final int[] input = generateRandomInput(100,
                new int[]{1001, 1002, 1002, 1003, 1003, 1003, 1004, 1004, 1004, 1004});
        verifyNotExpected(input, 11, new int[]{1001});
    }
}
