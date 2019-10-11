/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * A sparse array of ArraySets, which is suitable to hold userid->packages association.
 *
 * @hide
 */
public class SparseSetArray<T> {
    private final SparseArray<ArraySet<T>> mData = new SparseArray<>();

    public SparseSetArray() {
    }

    /**
     * Add a value at index n.
     * @return FALSE when the value already existed at the given index, TRUE otherwise.
     */
    public boolean add(int n, T value) {
        ArraySet<T> set = mData.get(n);
        if (set == null) {
            set = new ArraySet<>();
            mData.put(n, set);
        }
        if (set.contains(value)) {
            return true;
        }
        set.add(value);
        return false;
    }

    /**
     * Removes all mappings from this SparseSetArray.
     */
    public void clear() {
        mData.clear();
    }

    /**
     * @return whether a value exists at index n.
     */
    public boolean contains(int n, T value) {
        final ArraySet<T> set = mData.get(n);
        if (set == null) {
            return false;
        }
        return set.contains(value);
    }

    /**
     * @return the set of items at index n
     */
    public ArraySet<T> get(int n) {
        return mData.get(n);
    }

    /**
     * Remove a value from index n.
     * @return TRUE when the value existed at the given index and removed, FALSE otherwise.
     */
    public boolean remove(int n, T value) {
        final ArraySet<T> set = mData.get(n);
        if (set == null) {
            return false;
        }
        final boolean ret = set.remove(value);
        if (set.size() == 0) {
            mData.remove(n);
        }
        return ret;
    }

    /**
     * Remove all values from index n.
     */
    public void remove(int n) {
        mData.remove(n);
    }
    public int size() {
        return mData.size();
    }

    public int keyAt(int index) {
        return mData.keyAt(index);
    }

    public int sizeAt(int index) {
        final ArraySet<T> set = mData.valueAt(index);
        if (set == null) {
            return 0;
        }
        return set.size();
    }

    public T valueAt(int intIndex, int valueIndex) {
        return mData.valueAt(intIndex).valueAt(valueIndex);
    }
}
