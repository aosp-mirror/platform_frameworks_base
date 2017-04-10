/*
 * Copyright (C) 2013 The Android Open Source Project
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
import libcore.util.EmptyArray;

/**
 * Implements a growing array of long primitives.
 *
 * @hide
 */
public class LongArray implements Cloneable {
    private static final int MIN_CAPACITY_INCREMENT = 12;

    private long[] mValues;
    private int mSize;

    private  LongArray(long[] array, int size) {
        mValues = array;
        mSize = Preconditions.checkArgumentInRange(size, 0, array.length, "size");
    }

    /**
     * Creates an empty LongArray with the default initial capacity.
     */
    public LongArray() {
        this(10);
    }

    /**
     * Creates an empty LongArray with the specified initial capacity.
     */
    public LongArray(int initialCapacity) {
        if (initialCapacity == 0) {
            mValues = EmptyArray.LONG;
        } else {
            mValues = ArrayUtils.newUnpaddedLongArray(initialCapacity);
        }
        mSize = 0;
    }

    /**
     * Creates an LongArray wrapping the given primitive long array.
     */
    public static LongArray wrap(long[] array) {
        return new LongArray(array, array.length);
    }

    /**
     * Creates an LongArray from the given primitive long array, copying it.
     */
    public static LongArray fromArray(long[] array, int size) {
        return wrap(Arrays.copyOf(array, size));
    }

    /**
     * Changes the size of this LongArray. If this LongArray is shrinked, the backing array capacity
     * is unchanged. If the new size is larger than backing array capacity, a new backing array is
     * created from the current content of this LongArray padded with 0s.
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
    public void add(long value) {
        add(mSize, value);
    }

    /**
     * Inserts a value at the specified position in this array. If the specified index is equal to
     * the length of the array, the value is added at the end.
     *
     * @throws IndexOutOfBoundsException when index &lt; 0 || index &gt; size()
     */
    public void add(int index, long value) {
        ensureCapacity(1);
        int rightSegment = mSize - index;
        mSize++;
        checkBounds(index);

        if (rightSegment != 0) {
            // Move by 1 all values from the right of 'index'
            System.arraycopy(mValues, index, mValues, index + 1, rightSegment);
        }

        mValues[index] = value;
    }

    /**
     * Adds the values in the specified array to this array.
     */
    public void addAll(LongArray values) {
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
            final long[] newValues = ArrayUtils.newUnpaddedLongArray(newCapacity);
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
    public LongArray clone() {
        LongArray clone = null;
        try {
            clone = (LongArray) super.clone();
            clone.mValues = mValues.clone();
        } catch (CloneNotSupportedException cnse) {
            /* ignore */
        }
        return clone;
    }

    /**
     * Returns the value at the specified position in this array.
     */
    public long get(int index) {
        checkBounds(index);
        return mValues[index];
    }

    /**
     * Sets the value at the specified position in this array.
     */
    public void set(int index, long value) {
        checkBounds(index);
        mValues[index] = value;
    }

    /**
     * Returns the index of the first occurrence of the specified value in this
     * array, or -1 if this array does not contain the value.
     */
    public int indexOf(long value) {
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
        checkBounds(index);
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
     * Returns a new array with the contents of this LongArray.
     */
    public long[] toArray() {
        return Arrays.copyOf(mValues, mSize);
    }

    private void checkBounds(int index) {
        if (index < 0 || mSize <= index) {
            throw new ArrayIndexOutOfBoundsException(mSize, index);
        }
    }
}
