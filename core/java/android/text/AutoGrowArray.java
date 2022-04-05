/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.text;

import android.annotation.IntRange;
import android.annotation.NonNull;

import com.android.internal.util.ArrayUtils;

import libcore.util.EmptyArray;

/**
 * Implements a growing array of int primitives.
 *
 * These arrays are NOT thread safe.
 *
 * @hide
 */
public final class AutoGrowArray {
    private static final int MIN_CAPACITY_INCREMENT = 12;
    private static final int MAX_CAPACITY_TO_BE_KEPT = 10000;

    /**
     * Returns next capacity size.
     *
     * The returned capacity is larger than requested capacity.
     */
    private static int computeNewCapacity(int currentSize, int requested) {
        final int targetCapacity = currentSize + (currentSize < (MIN_CAPACITY_INCREMENT / 2)
                ?  MIN_CAPACITY_INCREMENT : currentSize >> 1);
        return targetCapacity > requested ? targetCapacity : requested;
    }

    /**
     * An auto growing byte array.
     */
    public static class ByteArray {

        private @NonNull byte[] mValues;
        private @IntRange(from = 0) int mSize;

        /**
         * Creates an empty ByteArray with the default initial capacity.
         */
        public ByteArray() {
            this(10);
        }

        /**
         * Creates an empty ByteArray with the specified initial capacity.
         */
        public ByteArray(@IntRange(from = 0) int initialCapacity) {
            if (initialCapacity == 0) {
                mValues = EmptyArray.BYTE;
            } else {
                mValues = ArrayUtils.newUnpaddedByteArray(initialCapacity);
            }
            mSize = 0;
        }

        /**
         * Changes the size of this ByteArray. If this ByteArray is shrinked, the backing array
         * capacity is unchanged.
         */
        public void resize(@IntRange(from = 0) int newSize) {
            if (newSize > mValues.length) {
                ensureCapacity(newSize - mSize);
            }
            mSize = newSize;
        }

        /**
         * Appends the specified value to the end of this array.
         */
        public void append(byte value) {
            ensureCapacity(1);
            mValues[mSize++] = value;
        }

        /**
         * Ensures capacity to append at least <code>count</code> values.
         */
        private void ensureCapacity(@IntRange int count) {
            final int requestedSize = mSize + count;
            if (requestedSize >= mValues.length) {
                final int newCapacity = computeNewCapacity(mSize, requestedSize);
                final byte[] newValues = ArrayUtils.newUnpaddedByteArray(newCapacity);
                System.arraycopy(mValues, 0, newValues, 0, mSize);
                mValues = newValues;
            }
        }

        /**
         * Removes all values from this array.
         */
        public void clear() {
            mSize = 0;
        }

        /**
         * Removes all values from this array and release the internal array object if it is too
         * large.
         */
        public void clearWithReleasingLargeArray() {
            clear();
            if (mValues.length > MAX_CAPACITY_TO_BE_KEPT) {
                mValues = EmptyArray.BYTE;
            }
        }

        /**
         * Returns the value at the specified position in this array.
         */
        public byte get(@IntRange(from = 0) int index) {
            return mValues[index];
        }

        /**
         * Sets the value at the specified position in this array.
         */
        public void set(@IntRange(from = 0) int index, byte value) {
            mValues[index] = value;
        }

        /**
         * Returns the number of values in this array.
         */
        public @IntRange(from = 0) int size() {
            return mSize;
        }

        /**
         * Returns internal raw array.
         *
         * Note that this array may have larger size than you requested.
         * Use size() instead for getting the actual array size.
         */
        public @NonNull byte[] getRawArray() {
            return mValues;
        }
    }

    /**
     * An auto growing int array.
     */
    public static class IntArray {

        private @NonNull int[] mValues;
        private @IntRange(from = 0) int mSize;

        /**
         * Creates an empty IntArray with the default initial capacity.
         */
        public IntArray() {
            this(10);
        }

        /**
         * Creates an empty IntArray with the specified initial capacity.
         */
        public IntArray(@IntRange(from = 0) int initialCapacity) {
            if (initialCapacity == 0) {
                mValues = EmptyArray.INT;
            } else {
                mValues = ArrayUtils.newUnpaddedIntArray(initialCapacity);
            }
            mSize = 0;
        }

        /**
         * Changes the size of this IntArray. If this IntArray is shrinked, the backing array
         * capacity is unchanged.
         */
        public void resize(@IntRange(from = 0) int newSize) {
            if (newSize > mValues.length) {
                ensureCapacity(newSize - mSize);
            }
            mSize = newSize;
        }

        /**
         * Appends the specified value to the end of this array.
         */
        public void append(int value) {
            ensureCapacity(1);
            mValues[mSize++] = value;
        }

        /**
         * Ensures capacity to append at least <code>count</code> values.
         */
        private void ensureCapacity(@IntRange(from = 0) int count) {
            final int requestedSize = mSize + count;
            if (requestedSize >= mValues.length) {
                final int newCapacity = computeNewCapacity(mSize, requestedSize);
                final int[] newValues = ArrayUtils.newUnpaddedIntArray(newCapacity);
                System.arraycopy(mValues, 0, newValues, 0, mSize);
                mValues = newValues;
            }
        }

        /**
         * Removes all values from this array.
         */
        public void clear() {
            mSize = 0;
        }

        /**
         * Removes all values from this array and release the internal array object if it is too
         * large.
         */
        public void clearWithReleasingLargeArray() {
            clear();
            if (mValues.length > MAX_CAPACITY_TO_BE_KEPT) {
                mValues = EmptyArray.INT;
            }
        }

        /**
         * Returns the value at the specified position in this array.
         */
        public int get(@IntRange(from = 0) int index) {
            return mValues[index];
        }

        /**
         * Sets the value at the specified position in this array.
         */
        public void set(@IntRange(from = 0) int index, int value) {
            mValues[index] = value;
        }

        /**
         * Returns the number of values in this array.
         */
        public @IntRange(from = 0) int size() {
            return mSize;
        }

        /**
         * Returns internal raw array.
         *
         * Note that this array may have larger size than you requested.
         * Use size() instead for getting the actual array size.
         */
        public @NonNull int[] getRawArray() {
            return mValues;
        }
    }

    /**
     * An auto growing float array.
     */
    public static class FloatArray {

        private @NonNull float[] mValues;
        private @IntRange(from = 0) int mSize;

        /**
         * Creates an empty FloatArray with the default initial capacity.
         */
        public FloatArray() {
            this(10);
        }

        /**
         * Creates an empty FloatArray with the specified initial capacity.
         */
        public FloatArray(@IntRange(from = 0) int initialCapacity) {
            if (initialCapacity == 0) {
                mValues = EmptyArray.FLOAT;
            } else {
                mValues = ArrayUtils.newUnpaddedFloatArray(initialCapacity);
            }
            mSize = 0;
        }

        /**
         * Changes the size of this FloatArray. If this FloatArray is shrinked, the backing array
         * capacity is unchanged.
         */
        public void resize(@IntRange(from = 0) int newSize) {
            if (newSize > mValues.length) {
                ensureCapacity(newSize - mSize);
            }
            mSize = newSize;
        }

        /**
         * Appends the specified value to the end of this array.
         */
        public void append(float value) {
            ensureCapacity(1);
            mValues[mSize++] = value;
        }

        /**
         * Ensures capacity to append at least <code>count</code> values.
         */
        private void ensureCapacity(int count) {
            final int requestedSize = mSize + count;
            if (requestedSize >= mValues.length) {
                final int newCapacity = computeNewCapacity(mSize, requestedSize);
                final float[] newValues = ArrayUtils.newUnpaddedFloatArray(newCapacity);
                System.arraycopy(mValues, 0, newValues, 0, mSize);
                mValues = newValues;
            }
        }

        /**
         * Removes all values from this array.
         */
        public void clear() {
            mSize = 0;
        }

        /**
         * Removes all values from this array and release the internal array object if it is too
         * large.
         */
        public void clearWithReleasingLargeArray() {
            clear();
            if (mValues.length > MAX_CAPACITY_TO_BE_KEPT) {
                mValues = EmptyArray.FLOAT;
            }
        }

        /**
         * Returns the value at the specified position in this array.
         */
        public float get(@IntRange(from = 0) int index) {
            return mValues[index];
        }

        /**
         * Sets the value at the specified position in this array.
         */
        public void set(@IntRange(from = 0) int index, float value) {
            mValues[index] = value;
        }

        /**
         * Returns the number of values in this array.
         */
        public @IntRange(from = 0) int size() {
            return mSize;
        }

        /**
         * Returns internal raw array.
         *
         * Note that this array may have larger size than you requested.
         * Use size() instead for getting the actual array size.
         */
        public @NonNull float[] getRawArray() {
            return mValues;
        }
    }
}
