/*
 * Copyright (C) 2007 The Android Open Source Project
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
import static org.junit.Assert.assertTrue;

import android.annotation.NonNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

/**
 * Internal tests for {@link SparseLongArray}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SparseLongArrayTest {

    private static final int TEST_SIZE = 1000;

    private SparseLongArray mSparseLongArray;
    private int[] mKeys;
    private long[] mValues;
    private Random mRandom;

    private static boolean isSame(@NonNull SparseLongArray array1,
            @NonNull SparseLongArray array2) {
        if (array1.size() != array2.size()) {
            return false;
        }
        for (int i = 0; i < array1.size(); i++) {
            if (array1.keyAt(i) != array2.keyAt(i) || array1.valueAt(i) != array2.valueAt(i)) {
                return false;
            }
        }
        return true;
    }

    private void assertRemoved(int startIndex, int endIndex) {
        for (int i = 0; i < TEST_SIZE; i++) {
            if (i >= startIndex && i <= endIndex) {
                assertEquals("Entry not removed", Long.MIN_VALUE,
                        mSparseLongArray.get(mKeys[i], Long.MIN_VALUE));
            } else {
                assertEquals("Untouched entry corrupted", mValues[i],
                        mSparseLongArray.get(mKeys[i]));
            }
        }
    }

    /**
     * Generates a sorted array of distinct and random keys
     *
     * @param size the number of keys to return in the array. Should be < (2^31)/1000.
     * @return the array of keys
     */
    private int[] generateRandomKeys(int size) {
        final int[] keys = new int[size];
        keys[0] = -1 * mRandom.nextInt(size * 500);
        for (int i = 1; i < size; i++) {
            keys[i] = keys[i - 1] + 1 + mRandom.nextInt(1000);
            assertTrue(keys[i] > keys[i - 1]);
        }
        return keys;
    }

    @Before
    public void setUp() {
        mSparseLongArray = new SparseLongArray();
        mRandom = new Random(12345);
        mKeys = generateRandomKeys(TEST_SIZE);
        mValues = new long[TEST_SIZE];
        for (int i = 0; i < TEST_SIZE; i++) {
            mValues[i] = i + 1;
            mSparseLongArray.put(mKeys[i], mValues[i]);
        }
    }

    @Test
    public void testRemoveAtRange_removeHead() {
        mSparseLongArray.removeAtRange(0, 100);
        assertEquals(TEST_SIZE - 100, mSparseLongArray.size());
        assertRemoved(0, 99);
    }

    @Test
    public void testRemoveAtRange_removeTail() {
        mSparseLongArray.removeAtRange(TEST_SIZE - 200, 200);
        assertEquals(TEST_SIZE - 200, mSparseLongArray.size());
        assertRemoved(TEST_SIZE - 200, TEST_SIZE - 1);
    }

    @Test
    public void testRemoveAtRange_removeOverflow() {
        mSparseLongArray.removeAtRange(TEST_SIZE - 100, 200);
        assertEquals(TEST_SIZE - 100, mSparseLongArray.size());
        assertRemoved(TEST_SIZE - 100, TEST_SIZE - 1);
    }

    @Test
    public void testRemoveAtRange_removeEverything() {
        mSparseLongArray.removeAtRange(0, TEST_SIZE);
        assertEquals(0, mSparseLongArray.size());
        assertRemoved(0, TEST_SIZE - 1);
    }

    @Test
    public void testRemoveAtRange_removeMiddle() {
        mSparseLongArray.removeAtRange(200, 200);
        assertEquals(TEST_SIZE - 200, mSparseLongArray.size());
        assertRemoved(200, 399);
    }

    @Test
    public void testRemoveAtRange_removeSingle() {
        mSparseLongArray.removeAtRange(300, 1);
        assertEquals(TEST_SIZE - 1, mSparseLongArray.size());
        assertRemoved(300, 300);
    }

    @Test
    public void testRemoveAtRange_compareRemoveAt() {
        final SparseLongArray sparseLongArray2 = mSparseLongArray.clone();
        assertTrue(isSame(mSparseLongArray, sparseLongArray2));

        final int startIndex = 101;
        final int endIndex = 200;
        mSparseLongArray.removeAtRange(startIndex, endIndex - startIndex + 1);
        for (int i = endIndex; i >= startIndex; i--) {
            sparseLongArray2.removeAt(i);
        }
        assertEquals(TEST_SIZE - (endIndex - startIndex + 1), mSparseLongArray.size());
        assertRemoved(startIndex, endIndex);
        assertTrue(isSame(sparseLongArray2, mSparseLongArray));
    }

    @Test
    public void testIncrementValue() {
        final SparseLongArray sla = new SparseLongArray();

        sla.put(4, 6);
        sla.incrementValue(4, 4);
        sla.incrementValue(2, 5);

        assertEquals(6 + 4, sla.get(4));
        assertEquals(5, sla.get(2));
    }
}
