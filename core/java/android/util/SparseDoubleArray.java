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

/**
 * SparseDoubleArrays map integers to doubles.  Unlike a normal array of doubles,
 * there can be gaps in the indices.  It is intended to be more memory efficient
 * than using a HashMap to map Integers to Doubles, both because it avoids
 * auto-boxing keys and values and its data structure doesn't rely on an extra entry object
 * for each mapping.
 *
 * <p>Note that this container keeps its mappings in an array data structure,
 * using a binary search to find keys.  The implementation is not intended to be appropriate for
 * data structures
 * that may contain large numbers of items.  It is generally slower than a traditional
 * HashMap, since lookups require a binary search and adds and removes require inserting
 * and deleting entries in the array.  For containers holding up to hundreds of items,
 * the performance difference is not significant, less than 50%.</p>
 *
 * <p>It is possible to iterate over the items in this container using
 * {@link #keyAt(int)} and {@link #valueAt(int)}. Iterating over the keys using
 * <code>keyAt(int)</code> with ascending values of the index will return the
 * keys in ascending order, or the values corresponding to the keys in ascending
 * order in the case of <code>valueAt(int)</code>.</p>
 *
 * @see SparseLongArray
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class SparseDoubleArray implements Cloneable {
    /**
     * The int->double map, but storing the doubles as longs using
     * {@link Double#doubleToRawLongBits(double)}.
     */
    private SparseLongArray mValues;

    /** Creates a new SparseDoubleArray containing no mappings. */
    public SparseDoubleArray() {
        this(0);
    }

    /**
     * Creates a new SparseDoubleArray, containing no mappings, that will not
     * require any additional memory allocation to store the specified
     * number of mappings.  If you supply an initial capacity of 0, the
     * sparse array will be initialized with a light-weight representation
     * not requiring any additional array allocations.
     */
    public SparseDoubleArray(int initialCapacity) {
        mValues = new SparseLongArray(initialCapacity);
    }

    @Override
    public SparseDoubleArray clone() {
        SparseDoubleArray clone = null;
        try {
            clone = (SparseDoubleArray) super.clone();
            clone.mValues = mValues.clone();
        } catch (CloneNotSupportedException cnse) {
            /* ignore */
        }
        return clone;
    }

    /**
     * Gets the double mapped from the specified key, or <code>0</code>
     * if no such mapping has been made.
     */
    public double get(int key) {
        return get(key, 0);
    }

    /**
     * Gets the double mapped from the specified key, or the specified value
     * if no such mapping has been made.
     */
    public double get(int key, double valueIfKeyNotFound) {
        final int index = mValues.indexOfKey(key);
        if (index < 0) {
            return valueIfKeyNotFound;
        }
        return valueAt(index);
    }

    /**
     * Adds a mapping from the specified key to the specified value,
     * replacing the previous mapping from the specified key if there
     * was one.
     */
    public void put(int key, double value) {
        mValues.put(key, Double.doubleToRawLongBits(value));
    }

    /**
     * Adds a mapping from the specified key to the specified value,
     * <b>adding</b> its value to the previous mapping from the specified key if there
     * was one.
     *
     * <p>This differs from {@link #put} because instead of replacing any previous value, it adds
     * (in the numerical sense) to it.
     */
    public void incrementValue(int key, double summand) {
        final double oldValue = get(key);
        put(key, oldValue + summand);
    }

    /** Returns the number of key-value mappings that this SparseDoubleArray currently stores. */
    public int size() {
        return mValues.size();
    }

    /**
     * Returns the index for which {@link #keyAt} would return the
     * specified key, or a negative number if the specified
     * key is not mapped.
     */
    public int indexOfKey(int key) {
        return mValues.indexOfKey(key);
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns
     * the key from the <code>index</code>th key-value mapping that this
     * SparseDoubleArray stores.
     *
     * @see SparseLongArray#keyAt(int)
     */
    public int keyAt(int index) {
        return mValues.keyAt(index);
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns
     * the value from the <code>index</code>th key-value mapping that this
     * SparseDoubleArray stores.
     *
     * @see SparseLongArray#valueAt(int)
     */
    public double valueAt(int index) {
        return Double.longBitsToDouble(mValues.valueAt(index));
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, sets a new
     * value for the <code>index</code>th key-value mapping that this
     * SparseDoubleArray stores.
     *
     * <p>For indices outside of the range <code>0...size()-1</code>, the behavior is undefined for
     * apps targeting {@link android.os.Build.VERSION_CODES#P} and earlier, and an
     * {@link ArrayIndexOutOfBoundsException} is thrown for apps targeting
     * {@link android.os.Build.VERSION_CODES#Q} and later.</p>
     */
    public void setValueAt(int index, double value) {
        mValues.setValueAt(index, Double.doubleToRawLongBits(value));
    }

    /**
     * Removes the mapping at the given index.
     */
    public void removeAt(int index) {
        mValues.removeAt(index);
    }

    /**
     * Removes the mapping from the specified key, if there was any.
     */
    public void delete(int key) {
        mValues.delete(key);
    }

    /**
     * Removes all key-value mappings from this SparseDoubleArray.
     */
    public void clear() {
        mValues.clear();
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation composes a string by iterating over its mappings.
     */
    @Override
    public String toString() {
        if (size() <= 0) {
            return "{}";
        }

        StringBuilder buffer = new StringBuilder(size() * 34);
        buffer.append('{');
        for (int i = 0; i < size(); i++) {
            if (i > 0) {
                buffer.append(", ");
            }
            int key = keyAt(i);
            buffer.append(key);
            buffer.append('=');
            double value = valueAt(i);
            buffer.append(value);
        }
        buffer.append('}');
        return buffer.toString();
    }
}
