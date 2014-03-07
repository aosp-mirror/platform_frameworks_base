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
     * Appends the specified value to the end of this array.
     */
    public void add(long value) {
        add(mSize, value);
    }

    /**
     * Inserts a value at the specified position in this array.
     *
     * @throws IndexOutOfBoundsException when index &lt; 0 || index &gt; size()
     */
    public void add(int index, long value) {
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
        if (index >= mSize) {
            throw new ArrayIndexOutOfBoundsException(mSize, index);
        }
        return mValues[index];
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
}
