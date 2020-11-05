/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;

import android.util.SparseBooleanArray;

/**
 * A watched variant of SparseBooleanArray.  Changes to the array are notified to
 * registered {@link Watcher}s.
 */
public class WatchedSparseBooleanArray extends WatchableImpl {

    // The storage
    private final SparseBooleanArray mStorage;

    // A private convenience function
    private void dispatchChange() {
        dispatchChange(this);
    }

    /**
     * Creates a new WatchedSparseBooleanArray containing no mappings.
     */
    public WatchedSparseBooleanArray() {
        mStorage = new SparseBooleanArray();
    }

    /**
     * Creates a new WatchedSparseBooleanArray containing no mappings that
     * will not require any additional memory allocation to store the
     * specified number of mappings.  If you supply an initial capacity of
     * 0, the sparse array will be initialized with a light-weight
     * representation not requiring any additional array allocations.
     */
    public WatchedSparseBooleanArray(int initialCapacity) {
        mStorage = new SparseBooleanArray(initialCapacity);
    }

    /**
     * The copy constructor does not copy the watcher data.
     */
    public WatchedSparseBooleanArray(@NonNull WatchedSparseBooleanArray r) {
        mStorage = r.mStorage.clone();
    }

    /**
     * Gets the boolean mapped from the specified key, or <code>false</code>
     * if no such mapping has been made.
     */
    public boolean get(int key) {
        return mStorage.get(key);
    }

    /**
     * Gets the boolean mapped from the specified key, or the specified value
     * if no such mapping has been made.
     */
    public boolean get(int key, boolean valueIfKeyNotFound) {
        return mStorage.get(key, valueIfKeyNotFound);
    }

    /**
     * Removes the mapping from the specified key, if there was any.
     */
    public void delete(int key) {
        mStorage.delete(key);
        dispatchChange();
    }

    /**
     * Removes the mapping at the specified index.
     * <p>
     * For indices outside of the range {@code 0...size()-1}, the behavior is undefined.
     */
    public void removeAt(int index) {
        mStorage.removeAt(index);
        dispatchChange();
    }

    /**
     * Adds a mapping from the specified key to the specified value,
     * replacing the previous mapping from the specified key if there
     * was one.
     */
    public void put(int key, boolean value) {
        if (mStorage.get(key) != value) {
            mStorage.put(key, value);
            dispatchChange();
        }
    }

    /**
     * Returns the number of key-value mappings that this SparseBooleanArray
     * currently stores.
     */
    public int size() {
        return mStorage.size();
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns
     * the key from the <code>index</code>th key-value mapping that this
     * SparseBooleanArray stores.
     *
     * <p>The keys corresponding to indices in ascending order are guaranteed to
     * be in ascending order, e.g., <code>keyAt(0)</code> will return the
     * smallest key and <code>keyAt(size()-1)</code> will return the largest
     * key.</p>
     *
     * <p>For indices outside of the range <code>0...size()-1</code>, the behavior is undefined for
     * apps targeting {@link android.os.Build.VERSION_CODES#P} and earlier, and an
     * {@link ArrayIndexOutOfBoundsException} is thrown for apps targeting
     * {@link android.os.Build.VERSION_CODES#Q} and later.</p>
     */
    public int keyAt(int index) {
        return mStorage.keyAt(index);
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns
     * the value from the <code>index</code>th key-value mapping that this
     * SparseBooleanArray stores.
     *
     * <p>The values corresponding to indices in ascending order are guaranteed
     * to be associated with keys in ascending order, e.g.,
     * <code>valueAt(0)</code> will return the value associated with the
     * smallest key and <code>valueAt(size()-1)</code> will return the value
     * associated with the largest key.</p>
     *
     * <p>For indices outside of the range <code>0...size()-1</code>, the behavior is undefined for
     * apps targeting {@link android.os.Build.VERSION_CODES#P} and earlier, and an
     * {@link ArrayIndexOutOfBoundsException} is thrown for apps targeting
     * {@link android.os.Build.VERSION_CODES#Q} and later.</p>
     */
    public boolean valueAt(int index) {
        return mStorage.valueAt(index);
    }

    /**
     * Directly set the value at a particular index.
     *
     * <p>For indices outside of the range <code>0...size()-1</code>, the behavior is undefined for
     * apps targeting {@link android.os.Build.VERSION_CODES#P} and earlier, and an
     * {@link ArrayIndexOutOfBoundsException} is thrown for apps targeting
     * {@link android.os.Build.VERSION_CODES#Q} and later.</p>
     */
    public void setValueAt(int index, boolean value) {
        if (mStorage.valueAt(index) != value) {
            mStorage.setValueAt(index, value);
            dispatchChange();
        }
    }

    /** @hide */
    public void setKeyAt(int index, int key) {
        if (mStorage.keyAt(index) != key) {
            mStorage.setKeyAt(index, key);
            dispatchChange();
        }
    }

    /**
     * Returns the index for which {@link #keyAt} would return the
     * specified key, or a negative number if the specified
     * key is not mapped.
     */
    public int indexOfKey(int key) {
        return mStorage.indexOfKey(key);
    }

    /**
     * Returns an index for which {@link #valueAt} would return the
     * specified key, or a negative number if no keys map to the
     * specified value.
     * Beware that this is a linear search, unlike lookups by key,
     * and that multiple keys can map to the same value and this will
     * find only one of them.
     */
    public int indexOfValue(boolean value) {
        return mStorage.indexOfValue(value);
    }

    /**
     * Removes all key-value mappings from this SparseBooleanArray.
     */
    public void clear() {
        mStorage.clear();
        dispatchChange();
    }

    /**
     * Puts a key/value pair into the array, optimizing for the case where
     * the key is greater than all existing keys in the array.
     */
    public void append(int key, boolean value) {
        mStorage.append(key, value);
        dispatchChange();
    }

    @Override
    public int hashCode() {
        return mStorage.hashCode();
    }

    @Override
    public boolean equals(Object that) {
        return this == that || mStorage.equals(that);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation composes a string by iterating over its mappings.
     */
    @Override
    public String toString() {
        return mStorage.toString();
    }
}
