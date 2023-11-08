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
import com.android.internal.util.Preconditions;

import java.util.Arrays;

/**
 * Implements a growing array of int primitives.
 *
 * @hide
 */
public class IntArray implements Cloneable {
    private static final int MIN_CAPACITY_INCREMENT = 12;

    private int[] mValues;
    private int mSize;

    private IntArray(int[] array, int size) {
        mValues = array;
        mSize = Preconditions.checkArgumentInRange(size, 0, array.length, "size");
    }

    /**
     * Creates an empty IntArray with the default initial capacity.
     */
    public IntArray() {
        this(0);
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
     * Creates an IntArray wrapping the given primitive int array.
     */
    public static IntArray wrap(int[] array) {
        return new IntArray(array, array.length);
    }

    /**
     * Creates an IntArray from the given primitive int array, copying it.
     */
    public static IntArray fromArray(int[] array, int size) {
        return wrap(Arrays.copyOf(array, size));
    }

    /**
     * Changes the size of this IntArray. If this IntArray is shrinked, the backing array capacity
     * is unchanged. If the new size is larger than backing array capacity, a new backing array is
     * created from the current content of this IntArray padded with 0s.
     */
    public void resize(int newSize) {
        Preconditions.checkArgumentNonnegative(newSize);
        if (newSize <= mValues.length) {
            Arrays.fill(mValues, newSize, mValues.length, 0);
        } else {
            ensureCapacity(newSize - mSize);
        }
        mSize = newSize;
    }

    /**
     * Appends the specified value to the end of this array.
     */
    public void add(int value) {
        add(mSize, value);
    }

    /**
     * Inserts a value at the specified position in this array. If the specified index is equal to
     * the length of the array, the value is added at the end.
     *
     * @throws IndexOutOfBoundsException when index &lt; 0 || index &gt; size()
     */
    public void add(int index, int value) {
        ensureCapacity(1);
        int rightSegment = mSize - index;
        mSize++;
        ArrayUtils.checkBounds(mSize, index);

        if (rightSegment != 0) {
            // Move by 1 all values from the right of 'index'
            System.arraycopy(mValues, index, mValues, index + 1, rightSegment);
        }

        mValues[index] = value;
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
     * Adds the values in the specified array to this array.
     */
    public void addAll(int[] values) {
        final int count = values.length;
        ensureCapacity(count);

        System.arraycopy(values, 0, mValues, mSize, count);
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
    public IntArray clone() {
        return new IntArray(mValues.clone(), mSize);
    }

    /**
     * Returns the value at the specified position in this array.
     */
    public int get(int index) {
        ArrayUtils.checkBounds(mSize, index);
        return mValues[index];
    }

    /**
     * Sets the value at the specified position in this array.
     */
    public void set(int index, int value) {
        ArrayUtils.checkBounds(mSize, index);
        mValues[index] = value;
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

    /** Returns {@code true} if this array contains the specified value. */
    public boolean contains(int value) {
        return indexOf(value) != -1;
    }

    /**
     * Removes the value at the specified index from this array.
     */
    public void remove(int index) {
        ArrayUtils.checkBounds(mSize, index);
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

    @Override
    public String toString() {
        // Code below is copied from Arrays.toString(), but uses mSize in the lopp (it cannot call
        // Arrays.toString() directly as it would return the unused elements as well)
        int iMax = mSize - 1;
        if (iMax == -1) {
            return "[]";
        }
        StringBuilder b = new StringBuilder();
        b.append('[');
        for (int i = 0;; i++) {
            b.append(mValues[i]);
            if (i == iMax) {
                return b.append(']').toString();
            }
            b.append(", ");
        }
    }
}
