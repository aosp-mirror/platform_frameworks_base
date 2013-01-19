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

import com.android.internal.util.ArrayUtils;

import java.util.Arrays;

/**
 * Map of {@code long} to {@code long}. Unlike a normal array of longs, there
 * can be gaps in the indices. It is intended to be more efficient than using a
 * {@code HashMap}.
 *
 * @hide
 */
public class LongSparseLongArray implements Cloneable {
    private long[] mKeys;
    private long[] mValues;
    private int mSize;

    /**
     * Creates a new SparseLongArray containing no mappings.
     */
    public LongSparseLongArray() {
        this(10);
    }

    /**
     * Creates a new SparseLongArray containing no mappings that will not
     * require any additional memory allocation to store the specified
     * number of mappings.
     */
    public LongSparseLongArray(int initialCapacity) {
        initialCapacity = ArrayUtils.idealLongArraySize(initialCapacity);

        mKeys = new long[initialCapacity];
        mValues = new long[initialCapacity];
        mSize = 0;
    }

    @Override
    public LongSparseLongArray clone() {
        LongSparseLongArray clone = null;
        try {
            clone = (LongSparseLongArray) super.clone();
            clone.mKeys = mKeys.clone();
            clone.mValues = mValues.clone();
        } catch (CloneNotSupportedException cnse) {
            /* ignore */
        }
        return clone;
    }

    /**
     * Gets the long mapped from the specified key, or <code>0</code>
     * if no such mapping has been made.
     */
    public long get(long key) {
        return get(key, 0);
    }

    /**
     * Gets the long mapped from the specified key, or the specified value
     * if no such mapping has been made.
     */
    public long get(long key, long valueIfKeyNotFound) {
        int i = Arrays.binarySearch(mKeys, 0, mSize, key);

        if (i < 0) {
            return valueIfKeyNotFound;
        } else {
            return mValues[i];
        }
    }

    /**
     * Removes the mapping from the specified key, if there was any.
     */
    public void delete(long key) {
        int i = Arrays.binarySearch(mKeys, 0, mSize, key);

        if (i >= 0) {
            removeAt(i);
        }
    }

    /**
     * Removes the mapping at the given index.
     */
    public void removeAt(int index) {
        System.arraycopy(mKeys, index + 1, mKeys, index, mSize - (index + 1));
        System.arraycopy(mValues, index + 1, mValues, index, mSize - (index + 1));
        mSize--;
    }

    /**
     * Adds a mapping from the specified key to the specified value,
     * replacing the previous mapping from the specified key if there
     * was one.
     */
    public void put(long key, long value) {
        int i = Arrays.binarySearch(mKeys, 0, mSize, key);

        if (i >= 0) {
            mValues[i] = value;
        } else {
            i = ~i;

            if (mSize >= mKeys.length) {
                growKeyAndValueArrays(mSize + 1);
            }

            if (mSize - i != 0) {
                System.arraycopy(mKeys, i, mKeys, i + 1, mSize - i);
                System.arraycopy(mValues, i, mValues, i + 1, mSize - i);
            }

            mKeys[i] = key;
            mValues[i] = value;
            mSize++;
        }
    }

    /**
     * Returns the number of key-value mappings that this SparseIntArray
     * currently stores.
     */
    public int size() {
        return mSize;
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns
     * the key from the <code>index</code>th key-value mapping that this
     * SparseLongArray stores.
     */
    public long keyAt(int index) {
        return mKeys[index];
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns
     * the value from the <code>index</code>th key-value mapping that this
     * SparseLongArray stores.
     */
    public long valueAt(int index) {
        return mValues[index];
    }

    /**
     * Returns the index for which {@link #keyAt} would return the
     * specified key, or a negative number if the specified
     * key is not mapped.
     */
    public int indexOfKey(long key) {
        return Arrays.binarySearch(mKeys, 0, mSize, key);
    }

    /**
     * Returns an index for which {@link #valueAt} would return the
     * specified key, or a negative number if no keys map to the
     * specified value.
     * Beware that this is a linear search, unlike lookups by key,
     * and that multiple keys can map to the same value and this will
     * find only one of them.
     */
    public int indexOfValue(long value) {
        for (int i = 0; i < mSize; i++)
            if (mValues[i] == value)
                return i;

        return -1;
    }

    /**
     * Removes all key-value mappings from this SparseIntArray.
     */
    public void clear() {
        mSize = 0;
    }

    /**
     * Puts a key/value pair into the array, optimizing for the case where
     * the key is greater than all existing keys in the array.
     */
    public void append(long key, long value) {
        if (mSize != 0 && key <= mKeys[mSize - 1]) {
            put(key, value);
            return;
        }

        int pos = mSize;
        if (pos >= mKeys.length) {
            growKeyAndValueArrays(pos + 1);
        }

        mKeys[pos] = key;
        mValues[pos] = value;
        mSize = pos + 1;
    }

    private void growKeyAndValueArrays(int minNeededSize) {
        int n = ArrayUtils.idealLongArraySize(minNeededSize);

        long[] nkeys = new long[n];
        long[] nvalues = new long[n];

        System.arraycopy(mKeys, 0, nkeys, 0, mKeys.length);
        System.arraycopy(mValues, 0, nvalues, 0, mValues.length);

        mKeys = nkeys;
        mValues = nvalues;
    }
}
