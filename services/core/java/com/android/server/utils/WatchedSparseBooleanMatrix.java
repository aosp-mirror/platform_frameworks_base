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

package com.android.server.utils;

import android.annotation.Nullable;
import android.annotation.Size;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

import java.util.Arrays;

/**
 * A {@link WatchedSparseBooleanMatrix} is an compact NxN array of booleans.  The rows and
 * columns of the array are indexed by integers, which need not be contiguous.  The matrix
 * is square and the row and column indices are identical.  This matrix is intended to be
 * very memory efficient.
 *
 * The matrix contains a map from indices to columns: this map requires 2*N integers.  The
 * boolean array is bit-packed and requires N*N/8 bytes.  The memory required for an
 * order-N matrix is therefore 2*N*4 + N*N bytes.
 *
 * See {@link SparseBooleanArray} for a discussion of sparse arrays.
 */
public class WatchedSparseBooleanMatrix extends WatchableImpl implements Snappable {

    /**
     * The matrix is implemented through four arrays.  The matrix of booleans is stored in
     * a one-dimensional {@code mValues} array.  {@code mValues} is always of size
     * {@code mOrder * mOrder}.  Elements of {@code mValues} are addressed with
     * arithmetic: the offset of the element {@code {row, col}} is at
     * {@code row * mOrder + col}.  The term "storage index" applies to {@code mValues}.
     * A storage index designates a row (column) in the underlying storage.  This is not
     * the same as the row seen by client code.
     *
     * Client code addresses the matrix through indices.  These are integers that need not
     * be contiguous.  Client indices are mapped to storage indices through two linear
     * integer arrays.  {@code mKeys} is a sorted list of client indices.
     * {@code mIndices} is a parallel array that contains storage indices.  The storage
     * index of a client index {@code k} is {@code mIndices[i]}, where
     * {@code mKeys[i] == k}.
     *
     * A final array, {@code mInUse} records if storage indices are free or in use.  This
     * array is of size {@code mOrder}.  A client index is deleted by removing it from
     * {@code mKeys} and {@code mIndices} and then setting the original storage index
     * false in {@code mInUse}.
     *
     * Some notes:
     * <ul>
     * <li> The matrix never shrinks.
     * <li> Equality is a very, very expesive operation.
     * </ul>
     */

    /**
     * mOrder is always a multiple of this value.  A  minimal matrix therefore holds 2^12
     * values and requires 1024 bytes.
     */
    private static final int STEP = 64;

    /**
     * The order of the matrix storage, including any padding.  The matrix is always
     * square.  mOrder is always greater than or equal to mSize.
     */
    private int mOrder;

    /**
     * The number of client keys.  This is always less than or equal to mOrder.  It is the
     * order of the matrix as seen by the client.
     */
    private int mSize;

    /**
     * The in-use list.
     */
    private boolean[] mInUse;

    /**
     * The array of client keys (indices), in sorted order.
     */
    private int[] mKeys;

    /**
     * The mapping from a client key to an storage index.  If client key K is at index N
     * in mKeys, then the storage index for K is at mMap[N].
     */
    private int[] mMap;

    /**
     * The boolean array.  This array is always {@code mOrder x mOrder} in size.
     */
    private boolean[] mValues;

    /**
     * A convenience function called when the elements are added to or removed from the storage.
     * The watchable is always {@link this}.
     */
    private void onChanged() {
        dispatchChange(this);
    }

    /**
     * Creates a new WatchedSparseBooleanMatrix containing no mappings.
     */
    public WatchedSparseBooleanMatrix() {
        this(STEP);
    }

    /**
     * Creates a new SparseBooleanMatrix containing no mappings that will not require any
     * additional memory allocation to store the specified number of mappings.  The
     * capacity is always rounded up to a non-zero multiple of STEP.
     */
    public WatchedSparseBooleanMatrix(int initialCapacity) {
        mOrder = initialCapacity;
        if (mOrder < STEP) {
            mOrder = STEP;
        }
        if (mOrder % STEP != 0) {
            mOrder = ((initialCapacity / STEP) + 1) * STEP;
        }
        if (mOrder < STEP || (mOrder % STEP != 0)) {
            throw new RuntimeException("mOrder is " + mOrder + " initCap is " + initialCapacity);
        }

        mInUse = new boolean[mOrder];
        mKeys = ArrayUtils.newUnpaddedIntArray(mOrder);
        mMap = ArrayUtils.newUnpaddedIntArray(mOrder);
        mValues = new boolean[mOrder * mOrder];
        mSize = 0;
    }

    /**
     * A copy constructor that can be used for snapshotting.
     */
    private WatchedSparseBooleanMatrix(WatchedSparseBooleanMatrix r) {
        mOrder = r.mOrder;
        mSize = r.mSize;
        mKeys = r.mKeys.clone();
        mMap = r.mMap.clone();
        mInUse = r.mInUse.clone();
        mValues = r.mValues.clone();
    }

    /**
     * Return a copy of this object.
     */
    public WatchedSparseBooleanMatrix snapshot() {
        return new WatchedSparseBooleanMatrix(this);
    }

    /**
     * Gets the boolean mapped from the specified key, or <code>false</code>
     * if no such mapping has been made.
     */
    public boolean get(int row, int col) {
        return get(row, col, false);
    }

    /**
     * Gets the boolean mapped from the specified key, or the specified value
     * if no such mapping has been made.
     */
    public boolean get(int row, int col, boolean valueIfKeyNotFound) {
        int r = indexOfKey(row, false);
        int c = indexOfKey(col, false);
        if (r >= 0 && c >= 0) {
            return valueAt(r, c);
        } else {
            return valueIfKeyNotFound;
        }
    }

    /**
     * Adds a mapping from the specified keys to the specified value, replacing the
     * previous mapping from the specified keys if there was one.
     */
    public void put(int row, int col, boolean value) {
        int r = indexOfKey(row);
        int c = indexOfKey(col);
        if (r < 0 || c < 0) {
            // One or both of the keys has not be installed yet.  Install them now.
            // Installing either key may shift the other key.  The safest course is to
            // install the keys that are not present and then recompute both indices.
            if (r < 0) {
                r = indexOfKey(row, true);
            }
            if (c < 0) {
                c = indexOfKey(col, true);
            }
            r = indexOfKey(row);
            c = indexOfKey(col);
        }
        if (r >= 0 && c >= 0) {
            setValueAt(r, c, value);
            onChanged();
        } else {
            throw new RuntimeException("matrix overflow");
        }
    }

    /**
     * Removes the mapping from the specified key, if there was any.  Note that deletion
     * applies to a single index, not to an element.  The matrix never shrinks but the
     * space will be reused the next time an index is added.
     */
    public void deleteKey(int key) {
        int i = indexOfKey(key, false);
        if (i >= 0) {
            removeAt(i);
        }
    }

    /**
     * Removes the mapping at the specified index.  The matrix does not shrink.  This
     * throws ArrayIndexOutOfBounds if the index out outside the range {@code 0..size()-1}.
     */
    public void removeAt(int index) {
        validateIndex(index);
        mInUse[mMap[index]] = false;
        System.arraycopy(mKeys, index + 1, mKeys, index, mSize - (index + 1));
        System.arraycopy(mMap, index + 1, mMap, index, mSize - (index + 1));
        mSize--;
        onChanged();
    }

    /**
     * Returns the number of key-value mappings that this WatchedSparseBooleanMatrix
     * currently stores.
     */
    public int size() {
        return mSize;
    }

    /**
     * Removes all key-value mappings from this WatchedSparseBooleanMatrix.
     */
    public void clear() {
        mSize = 0;
        Arrays.fill(mInUse, false);
        onChanged();
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns the key from the
     * <code>index</code>th key-value mapping that this WatchedSparseBooleanMatrix stores.
     *
     * <p>The keys corresponding to indices in ascending order are guaranteed to be in
     * ascending order, e.g., <code>keyAt(0)</code> will return the smallest key and
     * <code>keyAt(size()-1)</code> will return the largest key.</p>
     *
     * <p>{@link ArrayIndexOutOfBoundsException} is thrown for indices outside of the
     * range <code>0...size()-1</code></p>
     */
    public int keyAt(int index) {
        validateIndex(index);
        return mKeys[index];
    }

    /**
     * Given a row and column, each in the range <code>0...size()-1</code>, returns the
     * value from the <code>index</code>th key-value mapping that this WatchedSparseBooleanMatrix
     * stores.
     */
    public boolean valueAt(int rowIndex, int colIndex) {
        validateIndex(rowIndex, colIndex);
        int r = mMap[rowIndex];
        int c = mMap[colIndex];
        int element = r * mOrder + c;
        return mValues[element];
    }

    /**
     * Directly set the value at a particular index.
     */
    public void setValueAt(int rowIndex, int colIndex, boolean value) {
        validateIndex(rowIndex, colIndex);
        int r = mMap[rowIndex];
        int c = mMap[colIndex];
        int element = r * mOrder + c;
        mValues[element] = value;
        onChanged();
    }

    /**
     * Returns the index for which {@link #keyAt} would return the specified key, or a
     * negative number if the specified key is not mapped.
     */
    public int indexOfKey(int key) {
        return binarySearch(mKeys, mSize, key);
    }

    /**
     * Return true if the matrix knows the user index.
     */
    public boolean contains(int key) {
        return indexOfKey(key) >= 0;
    }

    /**
     * Fetch the index of a key.  If the key does not exist and grow is true, then add the
     * key.  If the does not exist and grow is false, return -1.
     */
    private int indexOfKey(int key, boolean grow) {
        int i = binarySearch(mKeys, mSize, key);
        if (i < 0 && grow) {
            i = ~i;
            if (mSize >= mOrder) {
                // Preemptively grow the matrix, which also grows the free list.
                growMatrix();
            }
            int newIndex = nextFree();
            mKeys = GrowingArrayUtils.insert(mKeys, mSize, i, key);
            mMap = GrowingArrayUtils.insert(mMap, mSize, i, newIndex);
            mSize++;
            // Initialize the row and column corresponding to the new index.
            for (int n = 0; n < mSize; n++) {
                mValues[n * mOrder + newIndex] = false;
                mValues[newIndex * mOrder + n] = false;
            }
            onChanged();
        }
        return i;
    }

    /**
     * Validate the index.  This can throw.
     */
    private void validateIndex(int index) {
        if (index >= mSize) {
            // The array might be slightly bigger than mSize, in which case, indexing won't fail.
            throw new ArrayIndexOutOfBoundsException(index);
        }
    }

    /**
     * Validate two indices.
     */
    private void validateIndex(int row, int col) {
        validateIndex(row);
        validateIndex(col);
    }

    /**
     * Find an unused storage index, mark it in-use, and return it.
     */
    private int nextFree() {
        for (int i = 0; i < mInUse.length; i++) {
            if (!mInUse[i]) {
                mInUse[i] = true;
                return i;
            }
        }
        throw new RuntimeException();
    }

    /**
     * Expand the 2D array.  This also extends the free list.
     */
    private void growMatrix() {
        int newOrder = mOrder + STEP;

        boolean[] newInuse = Arrays.copyOf(mInUse, newOrder);

        boolean[] newValues = new boolean[newOrder * newOrder];
        for (int i = 0; i < mOrder; i++) {
            int row = mOrder * i;
            int newRow = newOrder * i;
            for (int j = 0; j < mOrder; j++) {
                int index = row + j;
                int newIndex = newRow + j;
                newValues[newIndex] = mValues[index];
            }
        }

        mInUse = newInuse;
        mValues = newValues;
        mOrder = newOrder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int hashCode = mSize;
        for (int i = 0; i < mSize; i++) {
            hashCode = 31 * hashCode + mKeys[i];
            hashCode = 31 * hashCode + mMap[i];
        }
        for (int i = 0; i < mSize; i++) {
            int row = mMap[i] * mOrder;
            for (int j = 0; j < mSize; j++) {
                int element = mMap[j] + row;
                hashCode = 31 * hashCode + (mValues[element] ? 1 : 0);
            }
        }
        return hashCode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(@Nullable Object that) {
        if (this == that) {
            return true;
        }

        if (!(that instanceof WatchedSparseBooleanMatrix)) {
            return false;
        }

        WatchedSparseBooleanMatrix other = (WatchedSparseBooleanMatrix) that;
        if (mSize != other.mSize) {
            return false;
        }

        for (int i = 0; i < mSize; i++) {
            if (mKeys[i] != other.mKeys[i]) {
                return false;
            }
            if (mMap[i] != other.mMap[i]) {
                return false;
            }
        }
        for (int i = 0; i < mSize; i++) {
            int row = mMap[i] * mOrder;
            for (int j = 0; j < mSize; j++) {
                int element = mMap[j] + row;
                if (mValues[element] != other.mValues[element]) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Return the matrix meta information.  This is always three strings long.
     */
    private @Size(3) String[] matrixToStringMeta() {
        String[] result = new String[3];

        StringBuilder k = new StringBuilder();
        for (int i = 0; i < mSize; i++) {
            k.append(mKeys[i]);
            if (i < mSize - 1) {
                k.append(" ");
            }
        }
        result[0] = k.substring(0);

        StringBuilder m = new StringBuilder();
        for (int i = 0; i < mSize; i++) {
            m.append(mMap[i]);
            if (i < mSize - 1) {
                m.append(" ");
            }
        }
        result[1] = m.substring(0);

        StringBuilder u = new StringBuilder();
        for (int i = 0; i < mOrder; i++) {
            u.append(mInUse[i] ? "1" : "0");
        }
        result[2] = u.substring(0);
        return result;
    }

    /**
     * Return the matrix as an array of strings.  There is one string per row.  Each
     * string has a '1' or a '0' in the proper column.
     */
    private String[] matrixToStringRaw() {
        String[] result = new String[mOrder];
        for (int i = 0; i < mOrder; i++) {
            int row = i * mOrder;
            StringBuilder line = new StringBuilder(mOrder);
            for (int j = 0; j < mOrder; j++) {
                int element = row + j;
                line.append(mValues[element] ? "1" : "0");
            }
            result[i] = line.substring(0);
        }
        return result;
    }

    private String[] matrixToStringCooked() {
        String[] result = new String[mSize];
        for (int i = 0; i < mSize; i++) {
            int row = mMap[i] * mOrder;
            StringBuilder line = new StringBuilder(mSize);
            for (int j = 0; j < mSize; j++) {
                int element = row + mMap[j];
                line.append(mValues[element] ? "1" : "0");
            }
            result[i] = line.substring(0);
        }
        return result;
    }

    public String[] matrixToString(boolean raw) {
        String[] meta = matrixToStringMeta();
        String[] data;
        if (raw) {
            data = matrixToStringRaw();
        } else {
            data = matrixToStringCooked();
        }
        String[] result = new String[meta.length + data.length];
        System.arraycopy(meta, 0, result, 0, meta.length);
        System.arraycopy(data, 0, result, meta.length, data.length);
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation creates a string that describes the size of the array.  A
     * string with all the values could easily exceed 1Mb.
     */
    @Override
    public String toString() {
        return "{" + mSize + "x" + mSize + "}";
    }

    // Copied from android.util.ContainerHelpers, which is not visible outside the
    // android.util package.
    private static int binarySearch(int[] array, int size, int value) {
        int lo = 0;
        int hi = size - 1;

        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final int midVal = array[mid];

            if (midVal < value) {
                lo = mid + 1;
            } else if (midVal > value) {
                hi = mid - 1;
            } else {
                return mid;  // value found
            }
        }
        return ~lo;  // value not present
    }
}
