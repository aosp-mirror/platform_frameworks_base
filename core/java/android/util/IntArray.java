/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.android.internal.util.ArrayUtils;

import java.util.Arrays;
import libcore.util.EmptyArray;

/**
 * Implements a growing array of int primitives.
 *
 * @hide
 */
public class IntArray implements Cloneable {
    private static final int MIN_CAPACITY_INCREMENT = 12;

    private int[] mValues;
    private int mSize;

    /**
     * Creates an empty IntArray with the default initial capacity.
     */
    public IntArray() {
        this(10);
    }

    /**
     * Creates an empty IntArray with the specified initial capacity.
     */
    public IntArray(int initialCapacity) {
        if (initialCapacity == 0) {
            mValues = EmptyArray.INT;
        } else {
            mValues = ArrayUtils.newUnpaddedIntArray(initialCapacity);
        }
        mSize = 0;
    }

    /**
     * Appends the specified value to the end of this array.
     */
    public void add(int value) {
        add(mSize, value);
    }

    /**
     * Inserts a value at the specified position in this array.
     *
     * @throws IndexOutOfBoundsException when index &lt; 0 || index &gt; size()
     */
    public void add(int index, int value) {
        if (index < 0 || index > mSize) {
            throw new IndexOutOfBoundsException();
        }

        ensureCapacity(1);

        if (mSize - index != 0) {
            System.arraycopy(mValues, index, mValues, index + 1, mSize - index);
        }

        mValues[index] = value;
        mSize++;
    }

    /**
     * Searches the array for the specified value using the binary search algorithm. The array must
     * be sorted (as by the {@link Arrays#sort(int[], int, int)} method) prior to making this call.
     * If it is not sorted, the results are undefined. If the range contains multiple elements with
     * the specified value, there is no guarantee which one will be found.
     *
     * @param value The value to search for.
     * @return index of the search key, if it is contained in the array; otherwise, <i>(-(insertion
     *         point) - 1)</i>. The insertion point is defined as the point at which the key would
     *         be inserted into the array: the index of the first element greater than the key, or
     *         {@link #size()} if all elements in the array are less than the specified key.
     *         Note that this guarantees that the return value will be >= 0 if and only if the key
     *         is found.
     */
    public int binarySearch(int value) {
        return ContainerHelpers.binarySearch(mValues, mSize, value);
    }

    /**
     * Adds the values in the specified array to this array.
     */
    public void addAll(IntArray values) {
        final int count = values.mSize;
        ensureCapacity(count);

        System.arraycopy(values.mValues, 0, mValues, mSize, count);
        mSize += count;
    }

    /**
     * Ensures capacity to append at least <code>count</code> values.
     */
    private void ensureCapacity(int count) {
        final int currentSize = mSize;
        final int minCapacity = currentSize + count;
        if (minCapacity >= mValues.length) {
            final int targetCap = currentSize + (currentSize < (MIN_CAPACITY_INCREMENT / 2) ?
                    MIN_CAPACITY_INCREMENT : currentSize >> 1);
            final int newCapacity = targetCap > minCapacity ? targetCap : minCapacity;
            final int[] newValues = ArrayUtils.newUnpaddedIntArray(newCapacity);
            System.arraycopy(mValues, 0, newValues, 0, currentSize);
            mValues = newValues;
        }
    }

    /**
     * Removes all values from this array.
     */
    public void clear() {
        mSize = 0;
    }

    @Override
    public IntArray clone() throws CloneNotSupportedException {
        final IntArray clone = (IntArray) super.clone();
        clone.mValues = mValues.clone();
        return clone;
    }

    /**
     * Returns the value at the specified position in this array.
     */
    public int get(int index) {
        if (index >= mSize) {
            throw new ArrayIndexOutOfBoundsException(mSize, index);
        }
        return mValues[index];
    }

    /**
     * Returns the index of the first occurrence of the specified value in this
     * array, or -1 if this array does not contain the value.
     */
    public int indexOf(int value) {
        final int n = mSize;
        for (int i = 0; i < n; i++) {
            if (mValues[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Removes the value at the specified index from this array.
     */
    public void remove(int index) {
        if (index >= mSize) {
            throw new ArrayIndexOutOfBoundsException(mSize, index);
        }
        System.arraycopy(mValues, index + 1, mValues, index, mSize - index - 1);
        mSize--;
    }

    /**
     * Returns the number of values in this array.
     */
    public int size() {
        return mSize;
    }

    /**
     * Returns a new array with the contents of this IntArray.
     */
    public int[] toArray() {
        return Arrays.copyOf(mValues, mSize);
    }
}
