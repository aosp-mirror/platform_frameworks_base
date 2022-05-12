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
import android.util.SparseIntArray;

/**
 * A watched variant of SparseIntArray.  Changes to the array are notified to
 * registered {@link Watcher}s.
 */
public class WatchedSparseIntArray extends WatchableImpl
        implements Snappable {

    // The storage
    private final SparseIntArray mStorage;

    // A private convenience function
    private void onChanged() {
        dispatchChange(this);
    }

    /**
     * Creates a new WatchedSparseIntArray containing no mappings.
     */
    public WatchedSparseIntArray() {
        mStorage = new SparseIntArray();
    }

    /**
     * Creates a new WatchedSparseIntArray containing no mappings that
     * will not require any additional memory allocation to store the
     * specified number of mappings.  If you supply an initial capacity of
     * 0, the sparse array will be initialized with a light-weight
     * representation not requiring any additional array allocations.
     */
    public WatchedSparseIntArray(int initialCapacity) {
        mStorage = new SparseIntArray(initialCapacity);
    }

    /**
     * Create a {@link WatchedSparseIntArray} from a {@link SparseIntArray}
     */
    public WatchedSparseIntArray(@NonNull SparseIntArray c) {
        mStorage = c.clone();
    }

    /**
     * The copy constructor does not copy the watcher data.
     */
    public WatchedSparseIntArray(@NonNull WatchedSparseIntArray r) {
        mStorage = r.mStorage.clone();
    }

    /**
     * Make <this> a copy of src.  Any data in <this> is discarded.
     */
    public void copyFrom(@NonNull SparseIntArray src) {
        clear();
        final int end = src.size();
        for (int i = 0; i < end; i++) {
            put(src.keyAt(i), src.valueAt(i));
        }
    }

    /**
     * Make dst a copy of <this>.  Any previous data in dst is discarded.
     */
    public void copyTo(@NonNull SparseIntArray dst) {
        dst.clear();
        final int end = size();
        for (int i = 0; i < end; i++) {
            dst.put(keyAt(i), valueAt(i));
        }
    }

    /**
     * Return the underlying storage.  This breaks the wrapper but is necessary when
     * passing the array to distant methods.
     */
    public SparseIntArray untrackedStorage() {
        return mStorage;
    }

    /**
     * Gets the boolean mapped from the specified key, or <code>false</code>
     * if no such mapping has been made.
     */
    public int get(int key) {
        return mStorage.get(key);
    }

    /**
     * Gets the boolean mapped from the specified key, or the specified value
     * if no such mapping has been made.
     */
    public int get(int key, int valueIfKeyNotFound) {
        return mStorage.get(key, valueIfKeyNotFound);
    }

    /**
     * Removes the mapping from the specified key, if there was any.
     */
    public void delete(int key) {
        // This code ensures that onChanged is called only if the key is actually
        // present.
        final int index = mStorage.indexOfKey(key);
        if (index >= 0) {
            mStorage.removeAt(index);
            onChanged();
        }
    }

    /**
     * Removes the mapping at the specified index.
     */
    public void removeAt(int index) {
        mStorage.removeAt(index);
        onChanged();
    }

    /**
     * Adds a mapping from the specified key to the specified value,
     * replacing the previous mapping from the specified key if there
     * was one.
     */
    public void put(int key, int value) {
        // There is no fast way to know if the key exists with the input value, so this
        // method always notifies change listeners.
        mStorage.put(key, value);
        onChanged();
    }

    /**
     * Returns the number of key-value mappings that this SparseIntArray
     * currently stores.
     */
    public int size() {
        return mStorage.size();
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns
     * the key from the <code>index</code>th key-value mapping that this
     * SparseIntArray stores.
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
     * SparseIntArray stores.
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
    public int valueAt(int index) {
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
    public void setValueAt(int index, int value) {
        if (mStorage.valueAt(index) != value) {
            mStorage.setValueAt(index, value);
            onChanged();
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
    public int indexOfValue(int value) {
        return mStorage.indexOfValue(value);
    }

    /**
     * Removes all key-value mappings from this SparseIntArray.
     */
    public void clear() {
        final int count = size();
        mStorage.clear();
        if (count > 0) {
            onChanged();
        }
    }

    /**
     * Puts a key/value pair into the array, optimizing for the case where
     * the key is greater than all existing keys in the array.
     */
    public void append(int key, int value) {
        mStorage.append(key, value);
        onChanged();
    }

    /**
     * Provides a copy of keys.
     **/
    public int[] copyKeys() {
        return mStorage.copyKeys();
    }

    @Override
    public int hashCode() {
        return mStorage.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof WatchedSparseIntArray) {
            WatchedSparseIntArray w = (WatchedSparseIntArray) o;
            return mStorage.equals(w.mStorage);
        } else {
            return false;
        }
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

    /**
     * Create a snapshot.  The snapshot does not include any {@link Watchable}
     * information.
     */
    public WatchedSparseIntArray snapshot() {
        WatchedSparseIntArray l = new WatchedSparseIntArray(this);
        l.seal();
        return l;
    }

    /**
     * Make <this> a snapshot of the argument.  Note that <this> is immutable when the
     * method returns.  <this> must be empty when the function is called.
     * @param r The source array, which is copied into <this>
     */
    public void snapshot(@NonNull WatchedSparseIntArray r) {
        snapshot(this, r);
    }

    /**
     * Make the destination a copy of the source.  If the element is a subclass of Snapper then the
     * copy contains snapshots of the elements.  Otherwise the copy contains references to the
     * elements.  The destination must be initially empty.  Upon return, the destination is
     * immutable.
     * @param dst The destination array.  It must be empty.
     * @param src The source array.  It is not modified.
     */
    public static void snapshot(@NonNull WatchedSparseIntArray dst,
            @NonNull WatchedSparseIntArray src) {
        if (dst.size() != 0) {
            throw new IllegalArgumentException("snapshot destination is not empty");
        }
        final int end = src.size();
        for (int i = 0; i < end; i++) {
            dst.mStorage.put(src.keyAt(i), src.valueAt(i));
        }
        dst.seal();
    }

}
