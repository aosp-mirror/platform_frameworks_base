/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.util;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * Internal tests for {@link SparseDoubleArray}.
 *
 * Run using:
 *  atest FrameworksCoreTests:android.util.SparseDoubleArrayTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SparseDoubleArrayTest {
    private static final double EXACT_PRECISION = 0;
    private static final double PRECISION = 0.000000001;

    @Test
    public void testPutGet() {
        final SparseDoubleArray sda = new SparseDoubleArray();
        assertEquals("Array should be empty", 0, sda.size());

        final int[] keys = {1, 6, -14, 53251, 5, -13412, 12, 0, 2};
        final double[] values = {7, -12.4, 7, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NaN,
                4311236312.0 / 3431470161413514334123.0,
                431123636434132151313412.0 / 34323.0,
                0};
        for (int i = 0; i < keys.length; i++) {
            sda.put(keys[i], values[i]);
        }

        assertEquals("Wrong size array", keys.length, sda.size());
        // Due to the implementation, we actually expect EXACT double equality.
        for (int i = 0; i < keys.length; i++) {
            assertEquals("Wrong value at index " + i, values[i], sda.get(keys[i]), EXACT_PRECISION);
        }

        // Now check something that was never put in
        assertEquals("Wrong value for absent index", 0, sda.get(100000), EXACT_PRECISION);
    }

    @Test
    public void testAdd() {
        final SparseDoubleArray sda = new SparseDoubleArray();

        sda.put(4, 6.1);
        sda.add(4, -1.2);
        sda.add(2, -1.2);

        assertEquals(6.1 - 1.2, sda.get(4), PRECISION);
        assertEquals(-1.2, sda.get(2), PRECISION);
    }

    @Test
    public void testKeyValueAt() {
        final SparseDoubleArray sda = new SparseDoubleArray();

        final int[] keys = {1, 6, -14, 53251, 5, -13412, 12, 0, 2};
        final double[] values = {7, -12.4, 7, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NaN,
                4311236312.0 / 3431470161413514334123.0,
                431123636434132151313412.0 / 34323.0,
                0};
        for (int i = 0; i < keys.length; i++) {
            sda.put(keys[i], values[i]);
        }

        // Sort the sample data.
        final ArrayMap<Integer, Double> map = new ArrayMap<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], values[i]);
        }
        final int[] sortedKeys = Arrays.copyOf(keys, keys.length);
        Arrays.sort(sortedKeys);

        for (int i = 0; i < sortedKeys.length; i++) {
            final int expectedKey = sortedKeys[i];
            final double expectedValue = map.get(expectedKey);

            assertEquals("Wrong key at index " + i, expectedKey, sda.keyAt(i), PRECISION);
            assertEquals("Wrong value at index " + i, expectedValue, sda.valueAt(i), PRECISION);
        }
    }
}
