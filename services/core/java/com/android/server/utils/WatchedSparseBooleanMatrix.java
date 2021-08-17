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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import android.annotation.Nullable;
import android.annotation.Size;

import com.android.internal.annotations.VisibleForTesting;
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
     * The matrix is implemented through four arrays.  First, the matrix of booleans is
     * stored in a two-dimensional {@code mValues} array of bit-packed booleans.
     * {@code mValues} is always of size {@code mOrder * mOrder / 8}.  The factor of 8 is
     * present because there are 8 bits in a byte.  Elements of {@code mValues} are
     * addressed with arithmetic: the element {@code {row, col}} is bit {@code col % 8} in
     * byte * {@code (row * mOrder + col) / 8}.  The term "storage index" applies to
     * {@code mValues}.  A storage index designates a row (column) in the underlying
     * storage.  This is not the same as the row seen by client code.
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
     * <li> The matrix does not automatically shrink but there is a compress() method that
     *      will recover unused space.
     * <li> Equality is a very, very expensive operation because it must walk the matrices
     *      beimg compared element by element.
     * </ul>
     */

    /**
     * mOrder is always a multiple of this value.  A  minimal matrix therefore holds 2^12
     * values and requires 1024 bytes.  The value is visible for testing.
     */
    @VisibleForTesting(visibility = PRIVATE)
    static final int STEP = 64;

    /**
     * The number of bits in the mValues array element.
     */
    private static final int PACKING = 32;

    /**
     * Constants that index into the string array returned by matrixToString.  The primary
     * consumer is test code.
     */
    static final int STRING_KEY_INDEX = 0;
    static final int STRING_MAP_INDEX = 1;
    static final int STRING_INUSE_INDEX = 2;

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
    private int[] mValues;

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

        mInUse = ArrayUtils.newUnpaddedBooleanArray(mOrder);
        mKeys = ArrayUtils.newUnpaddedIntArray(mOrder);
        mMap = ArrayUtils.newUnpaddedIntArray(mOrder);
        mValues = ArrayUtils.newUnpaddedIntArray(mOrder * mOrder / PACKING);
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
            // setValueAt() will call onChanged().
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
        // Remove the specified index and ensure that unused words in mKeys and mMap are
        // always zero, to simplify the equality function.
        System.arraycopy(mKeys, index + 1, mKeys, index, mSize - (index + 1));
        mKeys[mSize - 1] = 0;
        System.arraycopy(mMap, index + 1, mMap, index, mSize - (index + 1));
        mMap[mSize - 1] = 0;
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
     * An internal method to fetch the boolean value given the mValues row and column
     * indices.  These are not the indices used by the *At() methods.
     */
    private boolean valueAtInternal(int row, int col) {
        int element = row * mOrder + col;
        int offset = element / PACKING;
        int mask = 1 << (element % PACKING);
        return (mValues[offset] & mask) != 0;
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
        return valueAtInternal(r, c);
    }

    /**
     * An internal method to set the boolean value given the mValues row and column
     * indices.  These are not the indices used by the *At() methods.
     */
    private void setValueAtInternal(int row, int col, boolean value) {
        int element = row * mOrder + col;
        int offset = element / PACKING;
        int mask = 1 << (element % PACKING);
        if (value) {
            mValues[offset] |= mask;
        } else {
            mValues[offset] &= ~mask;
        }
    }

    /**
     * Directly set the value at a particular index.
     */
    public void setValueAt(int rowIndex, int colIndex, boolean value) {
        validateIndex(rowIndex, colIndex);
        int r = mMap[rowIndex];
        int c = mMap[colIndex];
        setValueAtInternal(r, c, value);
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
            int valueRow = mOrder / PACKING;
            int offset = newIndex / PACKING;
            int mask = ~(1 << (newIndex % PACKING));
            Arrays.fill(mValues, newIndex * valueRow, (newIndex + 1) * valueRow, 0);
            for (int n = 0; n < mSize; n++) {
                mValues[n * valueRow + offset] &= mask;
            }
            // Do not report onChanged() from this private method.  onChanged() is the
            // responsibility of public methods that call this one.
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
     * Expand the 2D array.  This also extends the free list.
     */
    private void growMatrix() {
        resizeMatrix(mOrder + STEP);
    }

    /**
     * Resize the values array to the new dimension.
     */
    private void resizeMatrix(int newOrder) {
        if (newOrder % STEP != 0) {
            throw new IllegalArgumentException("matrix order " + newOrder
                                               + " is not a multiple of " + STEP);
        }
        int minOrder = Math.min(mOrder, newOrder);

        boolean[] newInUse = ArrayUtils.newUnpaddedBooleanArray(newOrder);
        System.arraycopy(mInUse, 0, newInUse, 0, minOrder);
        int[] newMap = ArrayUtils.newUnpaddedIntArray(newOrder);
        System.arraycopy(mMap, 0, newMap, 0, minOrder);
        int[] newKeys = ArrayUtils.newUnpaddedIntArray(newOrder);
        System.arraycopy(mKeys, 0, newKeys, 0, minOrder);

        int[] newValues = ArrayUtils.newUnpaddedIntArray(newOrder * newOrder / PACKING);
        for (int i = 0; i < minOrder; i++) {
            int row = mOrder * i / PACKING;
            int newRow = newOrder * i / PACKING;
            System.arraycopy(mValues, row, newValues, newRow, minOrder / PACKING);
        }

        mInUse = newInUse;
        mMap = newMap;
        mKeys = newKeys;
        mValues = newValues;
        mOrder = newOrder;
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
     * Return the index of the key that uses the highest row index in use.  This returns
     * -1 if the matrix is empty.  Note that the return is an index suitable for the *At()
     * methods.  It is not the index in the mInUse array.
     */
    private int lastInuse() {
        for (int i = mOrder - 1; i >= 0; i--) {
            if (mInUse[i]) {
                for (int j = 0; j < mSize; j++) {
                    if (mMap[j] == i) {
                        return j;
                    }
                }
                throw new IndexOutOfBoundsException();
            }
        }
        return -1;
    }

    /**
     * Compress the matrix by packing keys into consecutive indices.  If the compression
     * is sufficient, the mValues array can be shrunk.
     */
    private void pack() {
        if (mSize == 0 || mSize == mOrder) {
            return;
        }
        // dst and src are identify raw (row, col) in mValues.  srcIndex is the index (as
        // in the result of keyAt()) of the key being relocated.
        for (int dst = nextFree(); dst < mSize; dst = nextFree()) {
            int srcIndex = lastInuse();
            int src = mMap[srcIndex];
            mInUse[src] = false;
            mMap[srcIndex] = dst;
            System.arraycopy(mValues, src * mOrder / PACKING,
                             mValues, dst * mOrder / PACKING,
                             mOrder / PACKING);
            int srcOffset = (src / PACKING);
            int srcMask = 1 << (src % PACKING);
            int dstOffset = (dst / PACKING);
            int dstMask = 1 << (dst % PACKING);
            for (int i = 0; i < mOrder; i++) {
                if ((mValues[srcOffset] & srcMask) == 0) {
                    mValues[dstOffset] &= ~dstMask;
                } else {
                    mValues[dstOffset] |= dstMask;
                }
                srcOffset += mOrder / PACKING;
                dstOffset += mOrder / PACKING;
            }
        }
    }

    /**
     * Shrink the matrix, if possible.
     */
    public void compact() {
        pack();
        int unused = (mOrder - mSize) / STEP;
        if (unused > 0) {
            resizeMatrix(mOrder - (unused * STEP));
        }
    }

    /**
     * Return a copy of the keys that are in use by the matrix.
     */
    public int[] keys() {
        return Arrays.copyOf(mKeys, mSize);
    }

    /**
     * Return the size of the 2D matrix.  This is always greater than or equal to size().
     * This does not reflect the sizes of the meta-information arrays (such as mKeys).
     */
    public int capacity() {
        return mOrder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int hashCode = mSize;
        hashCode = 31 * hashCode + Arrays.hashCode(mKeys);
        hashCode = 31 * hashCode + Arrays.hashCode(mMap);
        for (int i = 0; i < mSize; i++) {
            int row = mMap[i];
            for (int j = 0; j < mSize; j++) {
                hashCode = 31 * hashCode + (valueAtInternal(row, mMap[j]) ? 1 : 0);
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
        if (!Arrays.equals(mKeys, other.mKeys)) {
            // mKeys is zero padded at the end and is sorted, so the arrays can always be
            // directly compared.
            return false;
        }
        for (int i = 0; i < mSize; i++) {
            int row = mMap[i];
            for (int j = 0; j < mSize; j++) {
                int col = mMap[j];
                if (valueAtInternal(row, col) != other.valueAtInternal(row, col)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Return the matrix meta information.  This is always three strings long.  The
     * strings are indexed by the constants STRING_KEY_INDEX, STRING_MAP_INDEX, and
     * STRING_INUSE_INDEX.
     */
    @VisibleForTesting(visibility = PRIVATE)
    @Size(3) String[] matrixToStringMeta() {
        String[] result = new String[3];

        StringBuilder k = new StringBuilder();
        for (int i = 0; i < mSize; i++) {
            k.append(mKeys[i]);
            if (i < mSize - 1) {
                k.append(" ");
            }
        }
        result[STRING_KEY_INDEX] = k.substring(0);

        StringBuilder m = new StringBuilder();
        for (int i = 0; i < mSize; i++) {
            m.append(mMap[i]);
            if (i < mSize - 1) {
                m.append(" ");
            }
        }
        result[STRING_MAP_INDEX] = m.substring(0);

        StringBuilder u = new StringBuilder();
        for (int i = 0; i < mOrder; i++) {
            u.append(mInUse[i] ? "1" : "0");
        }
        result[STRING_INUSE_INDEX] = u.substring(0);
        return result;
    }

    /**
     * Return the matrix as an array of strings.  There is one string per row.  Each
     * string has a '1' or a '0' in the proper column.  This is the raw data indexed by
     * row/column disregarding the key map.
     */
    @VisibleForTesting(visibility = PRIVATE)
    String[] matrixToStringRaw() {
        String[] result = new String[mOrder];
        for (int i = 0; i < mOrder; i++) {
            StringBuilder line = new StringBuilder(mOrder);
            for (int j = 0; j < mOrder; j++) {
                line.append(valueAtInternal(i, j) ? "1" : "0");
            }
            result[i] = line.substring(0);
        }
        return result;
    }

    /**
     * Return the matrix as an array of strings.  There is one string per row.  Each
     * string has a '1' or a '0' in the proper column.  This is the cooked data indexed by
     * keys, in key order.
     */
    @VisibleForTesting(visibility = PRIVATE)
    String[] matrixToStringCooked() {
        String[] result = new String[mSize];
        for (int i = 0; i < mSize; i++) {
            int row = mMap[i];
            StringBuilder line = new StringBuilder(mSize);
            for (int j = 0; j < mSize; j++) {
                line.append(valueAtInternal(row, mMap[j]) ? "1" : "0");
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
