/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.util.ArraySet;
import android.util.SparseSetArray;


/**
 * A watched variant of SparseSetArray.  Changes to the array are notified to
 * registered {@link Watcher}s.
 * @param <T> The element type, stored in the SparseSetArray.
 */
public class WatchedSparseSetArray<T> extends WatchableImpl implements Snappable {
    // The storage
    private final SparseSetArray mStorage;

    // A private convenience function
    private void onChanged() {
        dispatchChange(this);
    }

    public WatchedSparseSetArray() {
        mStorage = new SparseSetArray();
    }

    /**
     * Creates a new WatchedSparseSetArray from an existing WatchedSparseSetArray and copy its data
     */
    public WatchedSparseSetArray(@NonNull WatchedSparseSetArray<T> watchedSparseSetArray) {
        mStorage = new SparseSetArray(watchedSparseSetArray.untrackedStorage());
    }

    /**
     * Return the underlying storage.  This breaks the wrapper but is necessary when
     * passing the array to distant methods.
     */
    public SparseSetArray<T> untrackedStorage() {
        return mStorage;
    }

    /**
     * Add a value for key n.
     * @return FALSE when the value already existed for the given key, TRUE otherwise.
     */
    public boolean add(int n, T value) {
        final boolean res = mStorage.add(n, value);
        onChanged();
        return res;
    }

    /**
     * Removes all mappings from this SparseSetArray.
     */
    public void clear() {
        mStorage.clear();
        onChanged();
    }

    /**
     * @return whether the value exists for the key n.
     */
    public boolean contains(int n, T value) {
        return mStorage.contains(n, value);
    }

    /**
     * @return the set of items of key n
     */
    public ArraySet<T> get(int n) {
        return mStorage.get(n);
    }

    /**
     * Remove a value for key n.
     * @return TRUE when the value existed for the given key and removed, FALSE otherwise.
     */
    public boolean remove(int n, T value) {
        if (mStorage.remove(n, value)) {
            onChanged();
            return true;
        }
        return false;
    }

    /**
     * Remove all values for key n.
     */
    public void remove(int n) {
        mStorage.remove(n);
        onChanged();
    }

    /**
     * Return the size of the SparseSetArray.
     */
    public int size() {
        return mStorage.size();
    }

    /**
     * Return the key stored at the given index.
     */
    public int keyAt(int index) {
        return mStorage.keyAt(index);
    }

    /**
     * Return the size of the array at the given index.
     */
    public int sizeAt(int index) {
        return mStorage.sizeAt(index);
    }

    /**
     * Return the value in the SetArray at the given key index and value index.
     */
    public T valueAt(int intIndex, int valueIndex) {
        return (T) mStorage.valueAt(intIndex, valueIndex);
    }

    @NonNull
    @Override
    public Object snapshot() {
        WatchedSparseSetArray l = new WatchedSparseSetArray(this);
        l.seal();
        return l;
    }

    /**
     * Make <this> a snapshot of the argument.  Note that <this> is immutable when the
     * method returns.  <this> must be empty when the function is called.
     * @param r The source array, which is copied into <this>
     */
    public void snapshot(@NonNull WatchedSparseSetArray<T> r) {
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
    public static void snapshot(@NonNull WatchedSparseSetArray dst,
            @NonNull WatchedSparseSetArray src) {
        if (dst.size() != 0) {
            throw new IllegalArgumentException("snapshot destination is not empty");
        }
        final int arraySize = src.size();
        for (int i = 0; i < arraySize; i++) {
            final ArraySet set = src.get(i);
            final int setSize = set.size();
            for (int j = 0; j < setSize; j++) {
                dst.add(src.keyAt(i), set.valueAt(j));
            }
        }
        dst.seal();
    }
}
